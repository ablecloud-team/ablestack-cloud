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
package org.apache.cloudstack.api.command.user.vm;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.UserVmResponse;

import com.cloud.event.EventTypes;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine;

@APICommand(name = "updateVmCloneFlattenBandwidth", responseObject = SuccessResponse.class,
        description = "Updates the per-disk SharedMountPoint QCOW2 clone flatten bandwidth, including active blockpull jobs.",
        entityType = {VirtualMachine.class}, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateVmCloneFlattenBandwidthCmd extends BaseAsyncCmd {
    @ACL(accessType = AccessType.OperateEntry)
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = UserVmResponse.class,
            required = true, description = "The ID of the clone Instance")
    private Long id;

    @Parameter(name = "bandwidth", type = CommandType.INTEGER, required = true,
            description = "Bandwidth limit per clone disk in MiB/s. Zero means unlimited. Also used when flatten resumes.")
    private Integer bandwidth;

    @Override
    public void execute() {
        _userVmService.updateVmCloneFlattenBandwidth(id, bandwidth);
        SuccessResponse response = new SuccessResponse(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        UserVm vm = _entityMgr.findById(UserVm.class, id);
        return vm == null ? Account.ACCOUNT_ID_SYSTEM : vm.getAccountId();
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_VM_UPDATE;
    }

    @Override
    public String getEventDescription() {
        return "Updating clone flatten bandwidth for Instance " + id + " to " + bandwidth + " MiB/s";
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.VirtualMachine;
    }

    @Override
    public Long getApiResourceId() {
        return id;
    }
}
