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

import java.util.Map;
import com.cloud.agent.api.Command;

/** CHECK proves the installed Agent understands the contract; RESULT reads a committed receipt. */
public class BackupRestoreAllocationCommand extends Command {
    private String action;
    private String jobId;
    private Map<String, BackupVolumeAllocation> policies;
    public BackupRestoreAllocationCommand(String action, String jobId, Map<String, BackupVolumeAllocation> policies) {
        this.action = action; this.jobId = jobId; this.policies = policies; setWait(120);
    }
    public String getAction() { return action; }
    public String getJobId() { return jobId; }
    public Map<String, BackupVolumeAllocation> getPolicies() { return policies; }
    @Override public boolean executeInSequence() { return true; }
}
