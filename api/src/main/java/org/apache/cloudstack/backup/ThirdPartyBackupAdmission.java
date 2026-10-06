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

/** Persisted queue entry; uncertain admission occupies a slot until Host reconciliation. */
public final class ThirdPartyBackupAdmission {
    public static final String INSPECTION_BACKUP_KEY = "thirdparty.staging.manual.inspection.BACKUP";
    public static final String INSPECTION_RESTORE_KEY = "thirdparty.staging.manual.inspection.RESTORE";
    public static final String BACKUP_KEY = "thirdparty.staging.admission.backup";
    public static final String RESTORE_KEY = "thirdparty.staging.admission.restore";

    private ThirdPartyBackupAdmission() { }

    public static class Entry {
        public String jobId;
        public String operation;
        public long hostId;
        public Long clusterId;
        public long queuedAt;
        public long deadline;
        public String state;
        public String reason;
        public long requiredBytes;
        public long effectiveAvailableBytes;
        public int capacityVersion;
        public String stagingStorageKey;
        public String admissionToken;
        public boolean capacityReady;
        public long capacityCheckedAt;
        public java.util.List<PrimaryClaim> primaryClaims = new java.util.ArrayList<>();

        public boolean occupiesSlot() { return "ADMITTING".equals(state) || "ADMITTED".equals(state); }
    }

    /** Physical storage identity, independent of CloudStack pool aliases and Host mount paths. */
    public static class PrimaryClaim {
        public String storageKey;
        public long volumeBytes;
        public long requiredBytes;
        public long availableBytes;
        public long effectiveAvailableBytes;
    }

    public static class PrimaryCapacity {
        public int version = 1;
        public String jobId;
        public String stagingStorageKey;
        public java.util.List<PrimaryClaim> primaryClaims = new java.util.ArrayList<>();
    }
}
