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

import java.util.List;

/** Persisted restore destination and exact catalog selections, independent of the source Host. */
public final class ThirdPartyBackupRestore {
    public static final String PLAN_KEY = "thirdparty.volume.restore.plan";
    public static final String TRANSFER_KEY = "thirdparty.volume.restore.transfer";

    private ThirdPartyBackupRestore() { }

    public static class Plan {
        public String jobId;
        public long hostId;
        public String hostName;
        public String stageRoot;
        public String destination;
        public int bufferPercent;
        public int timeout;
        public ThirdPartyBackupManifest manifest;
        public List<String> volumeUuids;
    }

    public static class Request {
        public String jobId;
        public int sequence;
        public int volumeIndex;
        public int chainIndex;
        public boolean metadata;
        /** Exact restored file/directory; providers restore its basename into its parent. */
        public String destination;
        public ThirdPartyBackupManifest.Artifact artifact;
    }

    public static class Result {
        public String jobId;
        public boolean completed;
        public boolean submissionPending;
        public int sequence;

        public Result() { }
        public Result(String jobId, boolean completed) {
            this.jobId = jobId;
            this.completed = completed;
        }
    }
}
