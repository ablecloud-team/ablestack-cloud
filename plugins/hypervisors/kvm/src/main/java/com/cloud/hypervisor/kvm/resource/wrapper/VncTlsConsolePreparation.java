// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.JsonParseException;

final class VncTlsConsolePreparation {
    private static final Logger LOGGER = LogManager.getLogger(VncTlsConsolePreparation.class);
    private static final Path QEMU_STATE_DIR = Path.of("/run/libvirt/qemu");
    private static final Path VNC_TLS_DIR = Path.of("/etc/pki/libvirt-vnc");
    private static final long WAIT_MILLIS = 2000;
    private static final long RETRY_DELAY_MILLIS = 100;

    private final Cache<String, String> lastSuccessfulReload = CacheBuilder.newBuilder().maximumSize(4096).build();
    private final ConcurrentMap<String, FutureTask<Void>> pendingReloads = new ConcurrentHashMap<>();
    private final Executor executor;
    private final Function<String, String> identifyCredentials;
    private final long waitMillis;

    VncTlsConsolePreparation() {
        // Bound outstanding native libvirt calls even if one stops responding.
        this(new ThreadPoolExecutor(0, 4, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                new ThreadFactoryBuilder().setNameFormat("vnc-tls-reload-%d").setDaemon(true).build()),
                VncTlsConsolePreparation::identifyCurrentCredentials, WAIT_MILLIS);
    }

    VncTlsConsolePreparation(Executor executor, Function<String, String> identifyCredentials, long waitMillis) {
        this.executor = executor;
        this.identifyCredentials = identifyCredentials;
        this.waitMillis = waitMillis;
    }

    void prepare(Connect connection, String vmName) {
        String credentials = identifyCredentials.apply(vmName);
        if (credentials != null && credentials.equals(lastSuccessfulReload.getIfPresent(vmName))) {
            return;
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        FutureTask<Void> reload = new FutureTask<Void>(() -> {
            reloadWithRetry(connection, vmName, deadline);
            return null;
        }) {
            @Override
            protected void done() {
                pendingReloads.remove(vmName, this);
            }
        };
        FutureTask<Void> pending = pendingReloads.putIfAbsent(vmName, reload);
        if (pending == null) {
            pending = reload;
            try {
                executor.execute(reload);
            } catch (RejectedExecutionException e) {
                reload.cancel(false);
                LOGGER.warn("VNC TLS reload capacity reached for VM [{}], continuing console access", vmName);
                return;
            }
        }
        try {
            pending.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.warn("Timed out waiting for VNC TLS reload for VM [{}], continuing console access", vmName);
        } catch (CancellationException e) {
            LOGGER.debug("VNC TLS reload was cancelled for VM [{}]", vmName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.warn("Failed to prepare VNC TLS for VM [{}], continuing console access", vmName, e.getCause());
        }
    }

    private void reloadWithRetry(Connect connection, String vmName, long deadline) throws LibvirtException, InterruptedException {
        try {
            reloadIfNeeded(connection, vmName);
        } catch (LibvirtException | IllegalStateException | JsonParseException firstFailure) {
            // A later click should not be necessary for a transient error within this request's wait budget.
            if (deadline - System.nanoTime() <= TimeUnit.MILLISECONDS.toNanos(RETRY_DELAY_MILLIS)) {
                throw firstFailure;
            }
            TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MILLIS);
            if (deadline - System.nanoTime() <= 0) {
                throw firstFailure;
            }
            LOGGER.debug("Retrying VNC TLS reload for VM [{}] before console access", vmName, firstFailure);
            reloadIfNeeded(connection, vmName);
        }
    }

    private void reloadIfNeeded(Connect connection, String vmName) throws LibvirtException {
        String credentials = identifyCredentials.apply(vmName);
        if (credentials != null && credentials.equals(lastSuccessfulReload.getIfPresent(vmName))) {
            return;
        }
        Domain vm = connection.domainLookupByName(vmName);
        try {
            if (!VncTlsCertificateReloader.reloadIfEnabled(vm)) {
                return;
            }
            if (credentials == null) {
                lastSuccessfulReload.invalidate(vmName);
            } else {
                lastSuccessfulReload.put(vmName, credentials);
            }
            LOGGER.debug("Reloaded VNC TLS certificate for VM [{}] before console access", vmName);
        } finally {
            try {
                vm.free();
            } catch (LibvirtException e) {
                LOGGER.debug("Failed to release libvirt domain for VM [{}]", vmName, e);
            }
        }
    }

    private static String identifyCurrentCredentials(String vmName) {
        try {
            long pid = Long.parseLong(Files.readString(QEMU_STATE_DIR.resolve(vmName + ".pid")).trim());
            Instant startTime = ProcessHandle.of(pid).flatMap(process -> process.info().startInstant()).orElse(null);
            if (startTime == null) {
                return null;
            }
            String server = Hashing.sha256().hashBytes(Files.readAllBytes(VNC_TLS_DIR.resolve("server-cert.pem"))).toString();
            String ca = Hashing.sha256().hashBytes(Files.readAllBytes(VNC_TLS_DIR.resolve("ca-cert.pem"))).toString();
            return pid + ":" + startTime + ":" + server + ":" + ca;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            LOGGER.trace("Unable to identify QEMU process and VNC TLS credentials for VM [{}]", vmName, e);
            return null;
        }
    }
}
