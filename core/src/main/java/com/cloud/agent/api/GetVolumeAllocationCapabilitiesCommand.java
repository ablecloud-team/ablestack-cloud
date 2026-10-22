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

/** Sent only after the Agent advertises volume.allocation.capability.version=1 at startup. */
public class GetVolumeAllocationCapabilitiesCommand extends Command {
    private int contractVersion = 1;
    private String poolUuid;
    private String format;
    private String operation;
    protected GetVolumeAllocationCapabilitiesCommand() { }
    public GetVolumeAllocationCapabilitiesCommand(String poolUuid, String format, String operation) {
        this.poolUuid = poolUuid; this.format = format; this.operation = operation;
    }
    public int getContractVersion() { return contractVersion; }
    public String getPoolUuid() { return poolUuid; }
    public String getFormat() { return format; }
    public String getOperation() { return operation; }
    @Override public boolean executeInSequence() { return false; }
}
