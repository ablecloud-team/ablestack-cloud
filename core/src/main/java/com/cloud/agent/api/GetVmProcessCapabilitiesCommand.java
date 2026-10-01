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
package com.cloud.agent.api;

public class GetVmProcessCapabilitiesCommand extends Command {
    private String vmName;
    private String vmUuid;
    private String hostUuid;
    private String generation;
    private String requestId;
    public GetVmProcessCapabilitiesCommand(String vmName, String vmUuid, String hostUuid,
            String generation, String requestId) {
        this.vmName = vmName; this.vmUuid = vmUuid; this.hostUuid = hostUuid;
        this.generation = generation; this.requestId = requestId;
        setWait(10);
    }
    public String getVmName() { return vmName; }
    public String getVmUuid() { return vmUuid; }
    public String getHostUuid() { return hostUuid; }
    public String getGeneration() { return generation; }
    public String getRequestId() { return requestId; }
    @Override public boolean executeInSequence() { return false; }
}
