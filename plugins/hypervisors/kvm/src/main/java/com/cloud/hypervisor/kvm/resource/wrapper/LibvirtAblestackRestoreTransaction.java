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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.logging.log4j.Logger;

import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;

/** Prepare every disk before switching any disk; retain originals until the entire switch succeeds. */
final class LibvirtAblestackRestoreTransaction {
    private static final Path ROOT = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, "restore-transactions");

    @FunctionalInterface
    interface Restorer {
        boolean prepare(int index, String temporaryTarget);
    }

    private LibvirtAblestackRestoreTransaction() {
    }

    static void validateCapacity(final Logger logger, final String trace, final KVMStoragePoolManager manager,
            final List<PrimaryDataStoreTO> pools, final List<String> targets,
            final List<List<String>> chains, final int timeout) {
        final Map<String, Long> preparedBytes = new HashMap<>();
        final Map<String, Long> overlayBytes = new HashMap<>();
        final Map<String, Long> availableBytes = new HashMap<>();
        try {
            for (int i = 0; i < targets.size(); i++) {
                final Backend backend = new Backend(manager, pools.get(i), timeout);
                final List<String> chain = chains.get(i);
                if (chain.isEmpty()) {
                    throw new CloudRuntimeException("Empty restore chain for " + targets.get(i));
                }
                long volumeBytes = 0;
                long largestDiff = 0;
                for (String artifact : chain) {
                    if (!artifact.endsWith(".rbdiff")) {
                        volumeBytes = Math.max(volumeBytes, LibvirtAblestackFileRestoreHelper.estimateRequiredBytesForFileRestore(artifact));
                    }
                    if (chain.size() > 1 && artifact.endsWith(".qcow2")) {
                        largestDiff = Math.max(largestDiff, Files.size(Path.of(artifact)));
                    }
                }
                final String key;
                final Long available;
                if (backend.pool != null) {
                    key = "rbd:" + pools.get(i).getUuid();
                    available = LibvirtAblestackRbdRestoreHelper.getCephPoolAvailableBytes(backend.pool, timeout);
                } else {
                    final var store = Files.getFileStore(Path.of(targets.get(i)).toAbsolutePath().getParent());
                    key = "fs:" + store.toString();
                    available = store.getUsableSpace();
                    overlayBytes.merge(key, largestDiff, Math::max);
                }
                if (available == null || volumeBytes <= 0) {
                    throw new CloudRuntimeException("Unable to validate primary storage capacity for " + targets.get(i));
                }
                preparedBytes.merge(key, volumeBytes, Math::addExact);
                availableBytes.put(key, available);
            }
            for (Map.Entry<String, Long> entry : preparedBytes.entrySet()) {
                final long required = Math.addExact(entry.getValue(), overlayBytes.getOrDefault(entry.getKey(), 0L));
                final long buffered = Math.addExact(required, Math.max(10L * 1024L * 1024L * 1024L, required / 5L));
                logger.info("{} phase=[VM_RESTORE_PRIMARY_CAPACITY], storage=[{}], requiredBytes=[{}], availableBytes=[{}]",
                        trace, entry.getKey(), buffered, availableBytes.get(entry.getKey()));
                if (availableBytes.get(entry.getKey()) < buffered) {
                    throw new CloudRuntimeException("Insufficient primary storage capacity while retaining all original VM volumes: " + entry.getKey());
                }
            }
        } catch (IOException | org.apache.cloudstack.utils.qemu.QemuImgException | org.libvirt.LibvirtException | ArithmeticException e) {
            throw new CloudRuntimeException("Unable to validate prepared VM restore capacity", e);
        }
    }

    static void restore(final Logger logger, final String trace, final String vmName,
            final KVMStoragePoolManager manager, final List<PrimaryDataStoreTO> pools,
            final List<String> targets, final List<List<String>> chains, final int timeout, final Restorer restorer) {
        if (targets.isEmpty() || pools.size() != targets.size() || chains.size() != targets.size()) {
            throw new CloudRuntimeException("Invalid VM restore volume plan");
        }
        final Path directory = vmDirectory(vmName);
        try {
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(directory.resolve("transaction.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    throw new CloudRuntimeException("Another VM restore transaction is running for " + vmName);
                }
                final List<Backend> backends = new ArrayList<>();
                final java.util.Set<String> destinations = new java.util.HashSet<>();
                for (int i = 0; i < pools.size(); i++) {
                    final Backend backend = new Backend(manager, pools.get(i), timeout);
                    if (!destinations.add(pools.get(i).getUuid() + ":" + backend.normalize(targets.get(i)))) {
                        throw new CloudRuntimeException("Duplicate restore destination: " + targets.get(i));
                    }
                    backends.add(backend);
                }
                recoverInterruptedTransactions(logger, directory, pools, targets, backends);
                validateCapacity(logger, trace, manager, pools, targets, chains, timeout);
                final String id = UUID.randomUUID().toString();
                final Path journal = directory.resolve(id + ".properties");
                final Properties state = new Properties();
                state.setProperty("vm", vmName);
                state.setProperty("count", String.valueOf(targets.size()));
                state.setProperty("phase", "PREPARING");
                for (int i = 0; i < targets.size(); i++) {
                    final String target = backends.get(i).normalize(targets.get(i));
                    state.setProperty(i + ".pool", pools.get(i).getUuid());
                    state.setProperty(i + ".rbd", String.valueOf(backends.get(i).pool != null));
                    state.setProperty(i + ".target", target);
                    state.setProperty(i + ".prepared", target + "-csrestore-prepared-" + id);
                    state.setProperty(i + ".original", target + "-csrestore-original-" + id);
                    state.setProperty(i + ".hadOriginal", String.valueOf(backends.get(i).exists(target)));
                }
                save(journal, state);
                try {
                    for (int i = 0; i < targets.size(); i++) {
                        if (!restorer.prepare(i, state.getProperty(i + ".prepared"))) {
                            throw new CloudRuntimeException("Unable to prepare restored volume " + targets.get(i));
                        }
                        if (!backends.get(i).exists(state.getProperty(i + ".prepared"))) {
                            throw new CloudRuntimeException("Prepared restore volume is missing: " + targets.get(i));
                        }
                        state.setProperty(i + ".ready", "true");
                        save(journal, state);
                        logger.info("{} phase=[VOLUME_PREPARED], vm=[{}], volume=[{}]", trace, vmName, targets.get(i));
                    }
                    state.setProperty("phase", "COMMITTING");
                    save(journal, state);
                    for (int i = 0; i < targets.size(); i++) {
                        final Backend backend = backends.get(i);
                        final String target = state.getProperty(i + ".target");
                        state.setProperty(i + ".switching", "true");
                        save(journal, state);
                        if (Boolean.parseBoolean(state.getProperty(i + ".hadOriginal"))) {
                            backend.move(target, state.getProperty(i + ".original"));
                        } else if (backend.exists(target)) {
                            throw new CloudRuntimeException("Restore destination appeared during preparation: " + target);
                        }
                        backend.move(state.getProperty(i + ".prepared"), target);
                        state.setProperty(i + ".switched", "true");
                        save(journal, state);
                    }
                    // Persist the commit decision before deleting any original; recovery must never roll this back.
                    state.setProperty("phase", "COMMITTED");
                    save(journal, state);
                } catch (Exception failure) {
                    try {
                        rollback(journal, state, backends);
                    } catch (Exception rollbackFailure) {
                        throw new CloudRuntimeException(AblestackBackupFrameworkUtils.RESTORE_ROLLBACK_REQUIRED +
                                " VM [" + vmName + "] journal [" + journal + "]: " + rollbackFailure.getMessage(), failure);
                    }
                    throw new CloudRuntimeException("VM restore failed; original volumes were retained: " + failure.getMessage(), failure);
                }
                cleanupCommitted(logger, journal, state, backends);
                logger.info("{} phase=[VM_VOLUMES_COMMITTED], vm=[{}], journal=[{}]", trace, vmName, journal);
            }
        } catch (java.nio.channels.OverlappingFileLockException e) {
            throw new CloudRuntimeException("Another VM restore transaction is running for " + vmName, e);
        } catch (IOException e) {
            throw new CloudRuntimeException("Unable to persist VM restore transaction for " + vmName, e);
        }
    }

    static void assertStartAllowed(final String vmName) {
        final Path directory = vmDirectory(vmName);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            for (Path journal : files.filter(path -> path.toString().endsWith(".properties")).collect(java.util.stream.Collectors.toList())) {
                final Properties state = read(journal);
                if (!"COMMITTED".equals(state.getProperty("phase"))) {
                    throw new CloudRuntimeException(AblestackBackupFrameworkUtils.RESTORE_ROLLBACK_REQUIRED +
                            " VM [" + vmName + "] has an unfinished volume switch: " + journal);
                }
            }
        } catch (IOException e) {
            throw new CloudRuntimeException("Unable to check VM restore transaction for " + vmName, e);
        }
    }

    static void recoverForVm(final Logger logger, final KVMStoragePoolManager manager,
            final String vmName, final int timeout) {
        final Path directory = vmDirectory(vmName);
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory.resolve("transaction.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            if (lock == null) {
                throw new CloudRuntimeException("A VM restore transaction is still active for " + vmName);
            }
            try (var files = Files.list(directory)) {
                for (Path journal : files.filter(path -> path.toString().endsWith(".properties")).collect(java.util.stream.Collectors.toList())) {
                    final Properties state = read(journal);
                    final List<Backend> backends = new ArrayList<>();
                    final int count = Integer.parseInt(state.getProperty("count"));
                    for (int i = 0; i < count; i++) {
                        final boolean rbd = Boolean.parseBoolean(state.getProperty(i + ".rbd"));
                        final KVMStoragePool pool = rbd
                                ? manager.getStoragePool(Storage.StoragePoolType.RBD, state.getProperty(i + ".pool")) : null;
                        if (rbd && pool == null) {
                            throw new CloudRuntimeException("Cannot recover restore transaction: RBD pool is unavailable: "
                                    + state.getProperty(i + ".pool"));
                        }
                        backends.add(new Backend(pool, timeout));
                    }
                    if ("COMMITTED".equals(state.getProperty("phase"))) {
                        cleanupCommitted(logger, journal, state, backends);
                    } else {
                        rollback(journal, state, backends);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new CloudRuntimeException(AblestackBackupFrameworkUtils.RESTORE_ROLLBACK_REQUIRED +
                    " VM [" + vmName + "]: " + e.getMessage(), e);
        }
    }

    private static Path vmDirectory(final String vmName) {
        if (vmName == null || !vmName.matches("[A-Za-z0-9_.-]+") || ".".equals(vmName) || "..".equals(vmName)) {
            throw new CloudRuntimeException("Invalid VM name for restore transaction");
        }
        return ROOT.resolve(vmName);
    }

    private static void recoverInterruptedTransactions(final Logger logger, final Path directory,
            final List<PrimaryDataStoreTO> pools, final List<String> targets, final List<Backend> backends) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path journal : files.filter(path -> path.toString().endsWith(".properties")).collect(java.util.stream.Collectors.toList())) {
                final Properties state = read(journal);
                if (!String.valueOf(targets.size()).equals(state.getProperty("count"))) {
                    throw new CloudRuntimeException(AblestackBackupFrameworkUtils.RESTORE_ROLLBACK_REQUIRED + " Restore volume plan changed: " + journal);
                }
                for (int i = 0; i < targets.size(); i++) {
                    if (!pools.get(i).getUuid().equals(state.getProperty(i + ".pool"))
                            || !backends.get(i).normalize(targets.get(i)).equals(state.getProperty(i + ".target"))) {
                        throw new CloudRuntimeException(AblestackBackupFrameworkUtils.RESTORE_ROLLBACK_REQUIRED + " Restore target changed: " + journal);
                    }
                }
                if ("COMMITTED".equals(state.getProperty("phase"))) {
                    cleanupCommitted(logger, journal, state, backends);
                    if (Files.exists(journal)) {
                        throw new CloudRuntimeException("Committed restore cleanup is still pending: " + journal);
                    }
                } else {
                    try {
                        rollback(journal, state, backends);
                    } catch (Exception e) {
                        throw new CloudRuntimeException(AblestackBackupFrameworkUtils.RESTORE_ROLLBACK_REQUIRED + " " + journal, e);
                    }
                }
            }
        }
    }

    private static void rollback(final Path journal, final Properties state, final List<Backend> backends) throws IOException {
        state.setProperty("phase", "ROLLING_BACK");
        save(journal, state);
        for (int i = backends.size() - 1; i >= 0; i--) {
            final Backend backend = backends.get(i);
            final String target = state.getProperty(i + ".target");
            final String original = state.getProperty(i + ".original");
            final String prepared = state.getProperty(i + ".prepared");
            if (backend.exists(original)) {
                if (backend.exists(target)) {
                    backend.move(target, prepared);
                }
                backend.move(original, target);
            } else if (!Boolean.parseBoolean(state.getProperty(i + ".hadOriginal"))
                    && Boolean.parseBoolean(state.getProperty(i + ".switching"))
                    && !backend.exists(prepared) && backend.exists(target)) {
                backend.move(target, prepared);
            }
            if (Boolean.parseBoolean(state.getProperty(i + ".hadOriginal")) && !backend.exists(target)) {
                throw new IOException("Original volume is missing during rollback: " + target);
            }
            backend.delete(prepared);
        }
        Files.delete(journal);
    }

    private static void cleanupCommitted(final Logger logger, final Path journal, final Properties state,
            final List<Backend> backends) {
        try {
            for (int i = 0; i < backends.size(); i++) {
                backends.get(i).delete(state.getProperty(i + ".original"));
            }
            Files.delete(journal);
        } catch (Exception e) {
            logger.warn("VM restore committed; original-volume cleanup will be retried using journal [{}]: {}", journal, e.getMessage());
        }
    }

    private static Properties read(final Path path) throws IOException {
        final Properties state = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            state.load(input);
        }
        return state;
    }

    private static void save(final Path path, final Properties state) throws IOException {
        final Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            state.store(output, "ABLESTACK VM restore transaction");
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    private static final class Backend {
        private final KVMStoragePool pool;
        private final int timeout;

        private Backend(final KVMStoragePoolManager manager, final PrimaryDataStoreTO target, final int timeout) {
            pool = target.getPoolType() == Storage.StoragePoolType.RBD ? manager.getStoragePool(target.getPoolType(), target.getUuid()) : null;
            if (target.getPoolType() == Storage.StoragePoolType.RBD && pool == null) {
                throw new CloudRuntimeException("RBD restore pool is unavailable: " + target.getUuid());
            }
            this.timeout = timeout;
        }

        private Backend(final KVMStoragePool pool, final int timeout) {
            this.pool = pool;
            this.timeout = timeout;
        }

        private String normalize(final String path) {
            if (pool == null) {
                return Path.of(path).toAbsolutePath().normalize().toString();
            }
            final String prefix = pool.getSourceDir() + "/";
            return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        }

        private boolean exists(final String path) {
            return pool == null ? Files.exists(Path.of(path)) : LibvirtAblestackRbdRestoreHelper.rbdImageExists(pool, path, timeout);
        }

        private void move(final String source, final String target) throws IOException {
            if (exists(target)) {
                throw new IOException("Restore transaction refuses to overwrite " + target);
            }
            if (pool == null) {
                Files.move(Path.of(source), Path.of(target), StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(Path.of(target).getParent());
            } else if (!LibvirtAblestackRbdRestoreHelper.renameRbdImage(pool, source, target, timeout)) {
                throw new IOException("Unable to rename RBD restore image " + source + " to " + target);
            }
        }

        private void delete(final String path) throws IOException {
            if (pool == null) {
                Files.deleteIfExists(Path.of(path));
                forceDirectory(Path.of(path).getParent());
            } else if (!LibvirtAblestackRbdRestoreHelper.deleteRbdImageIfPresent(pool, path, timeout)) {
                throw new IOException("Unable to remove RBD restore image " + path);
            }
        }

        private void forceDirectory(final Path directory) throws IOException {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }
    }
}
