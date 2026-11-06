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
package org.apache.cloudstack.backup;

/** Immutable Host dispatch identity and durable confirmation of a volume backup start. */
public final class ThirdPartyBackupStart {
    public static final int VERSION = 1;
    public static final String DETAIL_KEY = "thirdparty.volume.start";
    public static final String SOURCE_RELEASED_KEY = "thirdparty.volume.source.released";

    private ThirdPartyBackupStart() { }

    public static class Plan {
        public int version = VERSION;
        public String jobId;
        public long hostId;
        public String backupPath;
        public ThirdPartyBackupManifest manifest;
        public boolean protectStoppedSource;
        public NasSource nasSource;

        public String sourceVmName() { return nasSource == null ? manifest.getVmName() : nasSource.vmName; }
        public boolean sourceIsRbd() {
            return nasSource == null ? manifest.getVolumes().stream().allMatch(volume -> "RBD_DIFF".equals(volume.engine))
                    : "RBD_DIFF".equals(nasSource.engine);
        }

        public boolean validNasSource() {
            if (nasSource == null || manifest != null || version != VERSION || !protectStoppedSource || hostId <= 0
                    || jobId == null || !jobId.matches("[A-Za-z0-9-]+") || backupPath == null || backupPath.isBlank()
                    || nasSource.vmName == null || !nasSource.vmName.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*")
                    || nasSource.volumeCount < 1 || nasSource.requestDigest == null || !nasSource.requestDigest.matches("[a-f0-9]{64}")
                    || !("QCOW2".equals(nasSource.engine) || "RBD_DIFF".equals(nasSource.engine))
                    || !("FULL".equals(nasSource.backupType) || "INCREMENTAL".equals(nasSource.backupType))
                    || nasSource.checkpointName == null || !nasSource.checkpointName.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*")) { return false; }
            java.nio.file.Path path = java.nio.file.Path.of(backupPath);
            return !path.isAbsolute() && path.normalize().equals(path) && !backupPath.contains("\n") && !backupPath.contains("\r")
                    && path.getFileName() != null && path.getFileName().toString().equals(nasSource.checkpointName)
                    && !path.startsWith("..");
        }
    }

    /** NAS keeps its repository layout and execution engine; only source ownership is shared. */
    public static class NasSource {
        public String vmName;
        public String engine;
        public String checkpointName;
        public String backupType;
        public int volumeCount;
        public String requestDigest;
    }

    public static class SourceProof {
        public int version = VERSION;
        public Plan plan;
        public String phase;
        public boolean released;
    }

    public static class Operation {
        public Plan plan;
        public String state;
        public String reason;
        public long createdAt;
        public long submittedAt;
        public long dispatchReturnedAt;
        public long checkedAt;
        public long cancelRequestedAt;
        public long cancelConfirmedAt;
        public String cancelReason;
    }

    public static class Receipt {
        public int version = VERSION;
        public Plan plan;
        public String state;
        public String reason;
        public long checkedAt;
    }
}
