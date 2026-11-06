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

import java.nio.file.Paths;
import java.util.function.Consumer;

import org.apache.logging.log4j.Logger;

/** Scoped to the restore worker; shared helpers also remain usable without a tracked job. */
final class LibvirtAblestackRestoreProgress implements AutoCloseable {
    private static final ThreadLocal<LibvirtAblestackRestoreProgress> CURRENT = new ThreadLocal<>();
    private final LibvirtAblestackRestoreProgress previous;
    private final Consumer<Update> reporter;
    private Integer volumeIndex;
    private Integer volumeCount;
    private String volumeLabel = "";

    static final class Update {
        final String step;
        final String details;
        final Integer volumeIndex;
        final Integer volumeCount;

        Update(String step, String details, Integer volumeIndex, Integer volumeCount) {
            this.step = step;
            this.details = details;
            this.volumeIndex = volumeIndex;
            this.volumeCount = volumeCount;
        }
    }

    private LibvirtAblestackRestoreProgress(Consumer<Update> reporter) {
        this.reporter = reporter;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static LibvirtAblestackRestoreProgress begin(Logger logger, String provider, String jobId, String vmName, String backupPath) {
        return begin(update -> LibvirtAblestackAsyncBackupRunner.markRestoreJobStep(logger, provider, jobId, vmName, backupPath,
                update.step, update.details, update.volumeIndex, update.volumeCount));
    }

    static LibvirtAblestackRestoreProgress begin(Consumer<Update> reporter) {
        return new LibvirtAblestackRestoreProgress(reporter);
    }

    static void volume(int index, int count, String source) {
        LibvirtAblestackRestoreProgress progress = CURRENT.get();
        if (progress == null) {
            return;
        }
        progress.volumeIndex = index;
        progress.volumeCount = count;
        String name = source == null ? "" : Paths.get(source).getFileName().toString();
        progress.volumeLabel = name.startsWith("root.") ? "ROOT" : name.startsWith("datadisk.") ? "DATA" : name;
        phase("RESTORE_DATA", "");
    }

    static void phase(String step, String details) {
        LibvirtAblestackRestoreProgress progress = CURRENT.get();
        if (progress == null) {
            return;
        }
        String description = progress.volumeLabel;
        if (details != null && !details.isBlank()) {
            description += (description.isBlank() ? "" : " · ") + details;
        }
        progress.reporter.accept(new Update(step, description, progress.volumeIndex, progress.volumeCount));
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
