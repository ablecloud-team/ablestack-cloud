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

    private ThirdPartyBackupStart() { }

    public static class Plan {
        public int version = VERSION;
        public String jobId;
        public long hostId;
        public String backupPath;
        public ThirdPartyBackupManifest manifest;
    }

    public static class Operation {
        public Plan plan;
        public String state;
        public String reason;
        public long createdAt;
        public long submittedAt;
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
