// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup;

import com.cloud.host.Host;

/** Management-side handoff between one host artifact and one external backup job. */
public interface ThirdPartyBackupVolumeService {
    @FunctionalInterface
    interface Transfer {
        /** Submit when jobId is empty; otherwise poll that exact saved job without waiting. */
        ThirdPartyBackupManifest.Artifact backup(ThirdPartyBackupManifest.Artifact artifact, boolean metadata);

        /** An external UI backup's pre/post hooks must finish before its child jobs may start. */
        default boolean ready() { return true; }

        /** Persist engine checkpoint metadata after the Host finishes the entire logical backup. */
        default void completed(Backup backup) { }

        /** A failed checkpoint must not be reused as a healthy incremental source. */
        default void failed(Backup backup) { }
    }

    /** Returns true when this is a volume pipeline (including when it is waiting). */
    boolean reconcile(Backup backup, Host host, Transfer transfer);

    /** Drive accepted jobs independently of browser polling and resume them during provider sync. */
    void track(Backup backup, Host host, Transfer transfer);

    @FunctionalInterface
    interface RestoreTransfer {
        /** Submit once when saved.jobId is empty; otherwise poll that exact external restore. */
        ThirdPartyBackupRestore.Result restore(ThirdPartyBackupRestore.Request request, ThirdPartyBackupRestore.Result saved);
    }

    ThirdPartyBackupRestore.Plan prepareRestore(Backup backup, Host host, java.util.List<String> volumeUuids, int timeout);

    /** Resume an accepted restore from persisted selections and external job IDs. */
    void trackRestore(Backup backup, Host host, RestoreTransfer transfer);
}
