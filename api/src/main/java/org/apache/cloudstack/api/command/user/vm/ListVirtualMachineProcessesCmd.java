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
package org.apache.cloudstack.api.command.user.vm;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.VmProcessSnapshotResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.vm.process.VmProcessSnapshotService;

import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine;

@APICommand(name = "listVirtualMachineProcesses",
        description = "Returns a bounded VM process snapshot. Requires vm.process.management.enabled. Permission: vm.process.read.",
        responseObject = VmProcessSnapshotResponse.class,
        entityType = {VirtualMachine.class},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        since = "4.23.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListVirtualMachineProcessesCmd extends BaseCmd {
    @Inject
    private VmProcessSnapshotService processSnapshotService;

    @ACL(accessType = AccessType.ListEntry)
    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "The ID of the Instance")
    private Long virtualMachineId;

    @Parameter(name = "snapshotid", type = CommandType.STRING, required = true, description = "Snapshot returned by refresh; expired snapshots are never recollected implicitly")
    private String snapshotId;
    @Parameter(name = "keyword", type = CommandType.STRING, description = "Case insensitive process name search, maximum 256 characters")
    private String keyword;
    @Parameter(name = "sortby", type = CommandType.STRING, description = "pid, name, memoryBytes or cpuPercent. CPU is interval usage with one core = 100%; multi-core processes may exceed 100%")
    private String sort;
    @Parameter(name = "descending", type = CommandType.BOOLEAN, description = "Sort descending")
    private Boolean descending;
    @Parameter(name = "page", type = CommandType.INTEGER, description = "One based page")
    private Integer page;
    @Parameter(name = "pagesize", type = CommandType.INTEGER, description = "Page size, maximum 200")
    private Integer pageSize;
    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    public void execute() {
        VmProcessSnapshotResponse response = processSnapshotService.list(virtualMachineId, snapshotId, keyword, sort, Boolean.TRUE.equals(descending), page == null ? 1 : page, pageSize == null ? 50 : pageSize);
        response.setObjectName("processsnapshot");
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        UserVm userVm = _entityMgr.findById(UserVm.class, virtualMachineId);
        return userVm == null ? Account.ACCOUNT_ID_SYSTEM : userVm.getAccountId();
    }
}
