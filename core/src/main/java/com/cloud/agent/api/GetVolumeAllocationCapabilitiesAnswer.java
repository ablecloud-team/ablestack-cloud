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

import java.util.LinkedHashMap;
import java.util.Map;

/** Backend identity must come from runtime probing, never from a pool's display name or tag. */
public class GetVolumeAllocationCapabilitiesAnswer extends Answer {
    private int contractVersion;
    private String poolUuid;
    private String format;
    private String operation;
    private String backend;
    private Map<String, String> methods;
    protected GetVolumeAllocationCapabilitiesAnswer() { }
    public GetVolumeAllocationCapabilitiesAnswer(GetVolumeAllocationCapabilitiesCommand cmd, String backend, Map<String, String> methods) {
        super(cmd, true, null);
        contractVersion = cmd.getContractVersion(); poolUuid = cmd.getPoolUuid(); format = cmd.getFormat(); operation = cmd.getOperation();
        this.backend = backend; this.methods = new LinkedHashMap<>(methods);
    }
    public int getContractVersion() { return contractVersion; }
    public String getPoolUuid() { return poolUuid; }
    public String getFormat() { return format; }
    public String getOperation() { return operation; }
    public String getBackend() { return backend; }
    public Map<String, String> getMethods() { return methods; }
}
