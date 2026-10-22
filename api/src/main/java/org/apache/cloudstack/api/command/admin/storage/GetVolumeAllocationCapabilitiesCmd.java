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
package org.apache.cloudstack.api.command.admin.storage;

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.VolumeAllocationCapabilityResponse;
import com.cloud.storage.VolumeAllocationService;

@APICommand(name = "getVolumeAllocationCapabilities", responseObject = VolumeAllocationCapabilityResponse.class,
        description = "Returns Agent-declared allocation capabilities for a specific host, pool, format and operation. Unknown capability never implies support.",
        since = "4.23.0", requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, authorized = {RoleType.Admin})
public class GetVolumeAllocationCapabilitiesCmd extends BaseCmd implements AdminCmd {
    @Inject private VolumeAllocationService allocationService;
    @Parameter(name = "storageid", type = CommandType.UUID, entityType = StoragePoolResponse.class, required = true)
    private Long storageId;
    @Parameter(name = "hostid", type = CommandType.UUID, entityType = HostResponse.class, required = true)
    private Long hostId;
    @Parameter(name = "allocationformat", type = CommandType.STRING, required = true, description = "qcow2 or raw")
    private String allocationFormat;
    @Parameter(name = "operation", type = CommandType.STRING, required = true, description = "create, copy, resize, restore, migrate or clone")
    private String operation;
    @Parameter(name = "allocationpolicy", type = CommandType.STRING, description = "Optional requested policy to validate against the pool/format/operation")
    private String allocationPolicy;
    public String getAllocationPolicy() { return allocationPolicy; }
    public Long getStorageId() { return storageId; }
    public Long getHostId() { return hostId; }
    public String getAllocationFormat() { return allocationFormat; }
    public String getOperation() { return operation; }
    @Override public long getEntityOwnerId() { return 0; }
    @Override public void execute() {
        VolumeAllocationCapabilityResponse response = allocationService.getCapabilities(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
