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
import org.apache.cloudstack.storage.volume.VolumeOnStorageTO;

public class ImportVolumeAllocationAnswer extends Answer {
    private int contractVersion = 1;
    private String requestId;
    private String poolUuid;
    private VolumeOnStorageTO volume;
    private Map<String, String> allocation;
    protected ImportVolumeAllocationAnswer() { }
    public ImportVolumeAllocationAnswer(ImportVolumeAllocationCommand command, VolumeOnStorageTO volume, Map<String, String> allocation) {
        super(command, true, null); requestId = command.getRequestId(); poolUuid = command.getPool().getUuid();
        this.volume = volume; this.allocation = new LinkedHashMap<>(allocation);
    }
    public int getContractVersion() { return contractVersion; }
    public String getRequestId() { return requestId; }
    public String getPoolUuid() { return poolUuid; }
    public VolumeOnStorageTO getVolume() { return volume; }
    public Map<String, String> getAllocation() { return allocation; }
}
