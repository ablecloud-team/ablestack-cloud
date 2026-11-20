// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.cloud.utils.Pair;

/** Internal stall policy for data commands that expose native progress. No management configuration is added. */
final class LibvirtAblestackDataProgress {
    static final long IDLE_SECONDS = 30 * 60;
    private static final int OUTPUT_LIMIT = 16384;
    private static final Pattern QEMU = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)/100%");
    private static final Pattern RBD = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)% complete");
    private static final ScheduledExecutorService OBSERVATIONS = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "backup-progress-observation");
        thread.setDaemon(true);
        return thread;
    });

    private LibvirtAblestackDataProgress() { }

    static final class Observation implements AutoCloseable {
        private final ScheduledFuture<?> warning;
        private Observation(Consumer<String> reporter) {
            warning = OBSERVATIONS.schedule(() -> reporter.accept(
                    "BACKUP_PROGRESS_UNAVAILABLE: conversion has no reliable progress metric; execution timeout remains in effect"),
                    IDLE_SECONDS, TimeUnit.SECONDS);
        }
        @Override public void close() { warning.cancel(false); }
    }

    static Observation unmeasured(Consumer<String> warning) { return new Observation(warning); }

    static final class Deadline {
        private final LongSupplier clock;
        private final long idleNanos;
        private long lastProgress;
        private double completed = -1;
        private boolean warned;

        Deadline(long idleMillis, LongSupplier clock) {
            this.clock = clock;
            idleNanos = TimeUnit.MILLISECONDS.toNanos(idleMillis);
            lastProgress = clock.getAsLong();
        }

        synchronized void observe(double value) {
            if (Double.isFinite(value) && value >= 0 && value <= 100 && value > completed) {
                completed = value;
                lastProgress = clock.getAsLong();
                warned = false;
            }
        }

        synchronized boolean stalled() {
            return completed >= 0 && completed < 100 && clock.getAsLong() - lastProgress >= idleNanos;
        }

        synchronized boolean warnUnavailable() {
            if ((completed >= 0 && completed < 100) || warned || clock.getAsLong() - lastProgress < idleNanos) { return false; }
            warned = true;
            return true;
        }
    }

    static Pair<Integer, String> execute(String command, int timeoutSeconds, String kind, Consumer<String> warning) {
        return execute(command, timeoutSeconds <= 0 ? Long.MAX_VALUE : TimeUnit.SECONDS.toMillis(timeoutSeconds),
                TimeUnit.SECONDS.toMillis(IDLE_SECONDS), kind, warning);
    }

    static Pair<Integer, String> execute(String command, long totalMillis, long idleMillis, String kind, Consumer<String> warning) {
        Process process = null;
        StringBuilder output = new StringBuilder();
        Thread reader = null;
        try {
            process = new ProcessBuilder("/bin/bash", "-c", "exec " + command).redirectErrorStream(true).start();
            Process worker = process;
            Deadline progress = new Deadline(idleMillis, System::nanoTime);
            Pattern pattern = "rbd".equals(kind) ? RBD : QEMU;
            reader = new Thread(() -> {
                try (InputStreamReader stream = new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        synchronized (output) {
                            output.append(buffer, 0, count);
                            if (output.length() > OUTPUT_LIMIT) { output.delete(0, output.length() - OUTPUT_LIMIT); }
                            Matcher values = pattern.matcher(output);
                            while (values.find()) {
                                double value = Double.parseDouble(values.group(1));
                                // Initial 0% may include target preallocation rather than data copy.
                                if (value > 0) { progress.observe(value); }
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // A lost metric is never evidence of progress. Process exit and execution time are checked separately.
                }
            }, "backup-data-progress");
            reader.setDaemon(true);
            reader.start();
            long started = System.nanoTime();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (progress.stalled()) {
                    return new Pair<>(124, "BACKUP_DATA_NO_PROGRESS: data command made no measurable progress for " + idleMillis / 1000 + " seconds");
                }
                if (totalMillis != Long.MAX_VALUE && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= totalMillis) {
                    return new Pair<>(124, "Backup data command execution timed out");
                }
                if (progress.warnUnavailable()) {
                    warning.accept("BACKUP_PROGRESS_UNAVAILABLE: data command has no reliable progress metric; execution timeout remains in effect");
                }
            }
            reader.join(1000);
            synchronized (output) { return new Pair<>(process.exitValue(), output.toString()); }
        } catch (IOException e) {
            return new Pair<>(-1, "Unable to execute backup data command: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Pair<>(124, "Backup data command interrupted; termination and cleanup require confirmation");
        } finally {
            if (process != null && process.isAlive()) {
                List<ProcessHandle> children = process.descendants().collect(Collectors.toList());
                boolean interrupted = Thread.interrupted();
                try {
                    children.forEach(ProcessHandle::destroy);
                    process.destroy();
                    try {
                        if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); }
                    } catch (InterruptedException e) {
                        interrupted = true;
                        process.destroyForcibly();
                    }
                    children.forEach(ProcessHandle::destroyForcibly);
                    long stopping = System.nanoTime();
                    boolean warned = false;
                    // Returning before the writer exits would allow transaction rollback to delete its live target.
                    // Uninterruptible storage IO must retain this worker and its reservations until termination is proven.
                    while (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)) {
                        if (!warned && System.nanoTime() - stopping >= TimeUnit.SECONDS.toNanos(5)) {
                            warning.accept("Data writer termination is unconfirmed; source protection and reservations are retained");
                            warned = true;
                        }
                        try { Thread.sleep(100); } catch (InterruptedException e) { interrupted = true; }
                    }
                } finally {
                    if (interrupted) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }
}
