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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** External metadata ownership, independent of the VM restore worker's staging directory. */
public class ThirdPartyExternalRestore {
    // Restore history is preserved by BackupDetailsDao even when a stale provider saves its details.
    // Retain the existing prefix for receipts created before accepted restores were tracked.
    public static final String PREFIX = ThirdPartyBackupRestore.HISTORY_PREFIX + "external-rejected.";
    public static final String LATEST_KEY = PREFIX + "latest.operation";
    public static final String PENDING_KEY = PREFIX + "pending.operation";
    public static final String STARTED_KEY = PREFIX + "vm-restore-started.operation";

    public static String id(String provider, String jobId) {
        return "external-restore-" + UUID.nameUUIDFromBytes((provider + ":" + jobId).getBytes(StandardCharsets.UTF_8));
    }

    public static String key(String provider, String jobId) { return PREFIX + id(provider, jobId) + ".operation"; }

    public static String claimKey(String provider, String jobId) {
        return "thirdparty.volume.ui.restore." + UUID.nameUUIDFromBytes((provider + ":" + jobId).getBytes(StandardCharsets.UTF_8));
    }

    public static class Proof {
        public String hostName;
        public long startedAt;
        public long endedAt;
    }

    public static class Destination {
        public String path;
        public ThirdPartyBackupStagingService.Snapshot configuration;
    }

    public static class Attempt {
        public int version = 1;
        public String id;
        public String provider;
        public String jobId;
        public String externalId;
        public String backupUuid;
        public String vmName;
        public String vmUuid;
        public long hostId;
        public Proof proof;
        public long recordedAt;
        /** Accepted metadata must wait for VM writers and their staging cleanup to finish. */
        public boolean accepted;
        public String state = "FAILED";
        public String step = "PRECHECK";
        public String reason;
        public String cleanupState = "WAITING";
        public String cleanupDetails;
        public ThirdPartyBackupManifest manifest;
        public List<Destination> destinations = new ArrayList<>();
    }
}
