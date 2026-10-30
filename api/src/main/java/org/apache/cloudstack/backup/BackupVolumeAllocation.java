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

import java.util.LinkedHashMap;
import java.util.Map;
import com.cloud.storage.VolumeAllocationLifecycle;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;

/** Exact allocation intent; legacy thin/sparse/fat must never be used to infer it. */
public class BackupVolumeAllocation implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public static String restoreKey(String jobId) { return ThirdPartyBackupRestore.historyPrefix(jobId) + "allocation"; }
    public int version = 1;
    public String volumeUuid;
    public long volumeId;
    public long accountId;
    public long bytes;
    public String poolUuid;
    public String backend;
    public String format;
    public Map<String, String> intent = new LinkedHashMap<>();

    public void validate() {
        Map<String, String> parsed = VolumeAllocationLifecycle.intent(intent);
        if (version != 1 || volumeUuid == null || bytes <= 0 || parsed.isEmpty() || !parsed.equals(intent)
                || format == null || !format.equals(parsed.get(VolumeAllocationPolicy.FORMAT))) {
            throw new CloudRuntimeException("BACKUP_ALLOCATION_INVALID: incomplete disk allocation contract");
        }
        String policy = parsed.get(VolumeAllocationPolicy.POLICY);
        boolean supported = "GFS2".equals(backend) ? ("qcow2".equals(format) && java.util.List.of("metadata", "falloc", "full").contains(policy))
                    || ("raw".equals(format) && java.util.List.of("off", "falloc", "full").contains(policy))
                : "RBD".equals(backend) && "raw".equals(format) && java.util.List.of("off", "full").contains(policy);
        if (!supported) throw new CloudRuntimeException("BACKUP_ALLOCATION_UNSUPPORTED: " + backend + "/" + format + "/" + policy);
    }

    public static class Result {
        public int version = 1;
        public String jobId;
        public Map<String, BackupVolumeAllocation> policies = new LinkedHashMap<>();
        public Map<String, Map<String, String>> evidence = new LinkedHashMap<>();
    }
}
