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
package org.apache.cloudstack.api.response;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.BaseResponse;

import java.util.ArrayList;
import java.util.List;

/** Additive batch view; the legacy successful VM response remains unchanged. */
public class VmCloneBatchResponse extends BaseResponse {
    public VmCloneBatchResponse() {
        super("clonebatch");
    }

    @SerializedName("requestid")
    @Param(description = "Stable batch identifier")
    public String requestId;

    @SerializedName("attemptid")
    @Param(description = "Current attempt customjobid, including before job insertion")
    public String attemptId;

    @SerializedName("sourceid")
    @Param(description = "Source VM UUID")
    public String sourceId;

    @SerializedName("status")
    @Param(description = "QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED or INTERRUPTED")
    public String status;

    @SerializedName("requestedcount")
    @Param(description = "Original number of targets")
    public Integer requestedCount;

    @SerializedName("startvm")
    @Param(description = "Requested start policy")
    public Boolean startVm;

    @SerializedName("clonetype")
    @Param(description = "Requested clone type")
    public String cloneType;

    @SerializedName("error")
    @Param(description = "Batch failure summary")
    public String error;

    @SerializedName("cleanupavailable")
    @Param(description = "Failed batch resources can be reconciled without recreating successful targets")
    public boolean cleanupAvailable;

    @SerializedName("snapshotcleanuperror")
    @Param(description = "Temporary snapshot cleanup is pending; existing dependency guards remain enforced")
    public String snapshotCleanupError;

    @SerializedName("cleanupjobid")
    @Param(description = "Most recent asynchronous cleanup attempt")
    public String cleanupJobId;

    @SerializedName("cleanupjobstatus")
    @Param(description = "Cleanup Job status: 0 running, 1 completed, 2 failed")
    public Integer cleanupJobStatus;

    @SerializedName("targets")
    @Param(description = "All planned targets")
    public List<Target> targets = new ArrayList<>();

    public static class Target extends BaseResponse {
        public Target() { super("targets"); }
        @SerializedName("index")
        @Param(description = "Original 1-based target index")
        public int index;

        @SerializedName("name")
        @Param(description = "Final target name")
        public String name;

        @SerializedName("id")
        @Param(description = "Created VM UUID, when accessible")
        public String id;

        @SerializedName("created")
        @Param(description = "Whether this target was created")
        public Boolean created;

        @SerializedName("state")
        @Param(description = "Current VM state")
        public String state;

        @SerializedName("phase")
        @Param(description = "Last recorded creation or start phase")
        public String phase;

        @SerializedName("error")
        @Param(description = "Target failure summary")
        public String error;

        @SerializedName("cleanup")
        @Param(description = "Cloud cleanup state, not an independent physical audit")
        public String cleanup;

        @SerializedName("cleanuperror")
        @Param(description = "Why physical cleanup could not be verified")
        public String cleanupError;

        @SerializedName("retryaction")
        @Param(description = "CREATE recaptures the current source, RESUME reuses prepared disks, START starts the existing VM, NONE blocks retry")
        public String retryAction;

        @SerializedName("volumes")
        @Param(description = "Allocated volumes and current cleanup state")
        public List<Disk> volumes = new ArrayList<>();
    }

    public static class Disk extends BaseResponse {
        public Disk() { super("volumes"); }
        @SerializedName("id")
        @Param(description = "Volume UUID")
        public String id;

        @SerializedName("type")
        @Param(description = "ROOT or DATADISK")
        public String type;

        @SerializedName("state")
        @Param(description = "Current Cloud volume state")
        public String state;

        @SerializedName("cleanup")
        @Param(description = "retained, pending or removed-in-cloud")
        public String cleanup;
    }
}
