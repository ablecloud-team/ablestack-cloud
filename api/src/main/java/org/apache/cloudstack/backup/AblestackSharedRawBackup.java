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

import java.util.ArrayList;
import java.util.List;

/** Immutable, fail-closed ownership for an offline shared-file recovery point. */
public final class AblestackSharedRawBackup {
    public static final String ENGINE = "OFFLINE_FILE";
    public static final String MARKER = "backup.shared.raw.guard";
    public static final String RETENTION_PENDING = "backup.shared.raw.retention.pending";
    public static final String BACKUP_KEY = "backup.shared.raw.plan";
    public static String restoreKey(String job) { return ThirdPartyBackupRestore.historyPrefix(job) + "sharedRaw"; }
    private AblestackSharedRawBackup() { }
    public static class Disk {
        public String poolUuid;
        public long poolId;
        public String path;
        public String format;
        public long bytes;
        public boolean shareable;
        public List<Long> volumeIds = new ArrayList<>();
        public List<Long> vmIds = new ArrayList<>();
    }
    public static class Plan {
        public int version = 1;
        public long backupId;
        public String jobId;
        public long hostId;
        public String operation;
        public String vmName;
        public String vmUuid;
        public java.util.Map<String, String> vmConfiguration = new java.util.LinkedHashMap<>();
        public List<Disk> disks = new ArrayList<>();
    }
}
