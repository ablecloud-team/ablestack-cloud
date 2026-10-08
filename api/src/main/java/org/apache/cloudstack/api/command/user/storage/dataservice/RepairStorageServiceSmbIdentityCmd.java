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

package org.apache.cloudstack.api.command.user.storage.dataservice;

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "repairStorageServiceSmbIdentity", responseObject = StorageServiceRuntimeResponse.class,
        description = "Rebinds verified owned SMB masters to the current authentication databases during an explicitly confirmed maintenance window. No password, desired configuration or DATA is modified.",
        since = "4.23.0", authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class RepairStorageServiceSmbIdentityCmd extends BaseStorageServiceAsyncCmd implements UserCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true, description = "Storage Service instance")
    private Long instanceId;
    @Parameter(name = "confirmation", type = CommandType.STRING, required = true, description = "Exact instance name acknowledging SMB maintenance")
    private String confirmation;
    @Parameter(name = "maintenancewindow", type = CommandType.BOOLEAN, required = true, description = "Explicit maintenance-window acknowledgment; repair still requires independently verified zero sessions")
    private Boolean maintenanceWindow;
    public Long getInstanceId() { return instanceId; }
    public String getConfirmation() { return confirmation; }
    public Boolean getMaintenanceWindow() { return maintenanceWindow; }
    @Override public long getEntityOwnerId() { return org.apache.cloudstack.context.CallContext.current().getCallingAccount().getId(); }
    @Override public String getEventType() { return "STORAGE.SMB.IDENTITY.REPAIR"; }
    @Override public String getEventDescription() { return "Repairing SMB authentication database bindings for instance " + instanceId; }
    @Override public void execute() {
        StorageServiceRuntimeResponse response = storageService.repairStorageServiceSmbIdentity(this);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}
