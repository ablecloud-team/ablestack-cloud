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

import com.cloud.user.Account;
import com.cloud.uservm.UserVm;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VmProcessActionResponse;
import org.apache.cloudstack.vm.process.VmProcessActionService;

import javax.inject.Inject;

public abstract class BaseVmProcessActionCmd extends BaseAsyncCmd {
    @Inject private VmProcessActionService service;

    @ACL(accessType = AccessType.OperateEntry)
    @Parameter(
            name = "virtualmachineid",
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "Target VM")
    private Long virtualMachineId;

    @Parameter(
            name = "requestid",
            type = CommandType.STRING,
            required = true,
            description = "Canonical UUID idempotency key")
    private String requestId;

    @Parameter(
            name = "snapshotid",
            type = CommandType.STRING,
            required = true,
            description = "Fresh server snapshot UUID")
    private String snapshotId;

    @Parameter(
            name = "pid",
            type = CommandType.LONG,
            required = true,
            description = "PID in the selected snapshot")
    private Long pid;

    protected abstract String action();

    protected String serviceName() {
        return null;
    }

    @Override
    public String getEventType() {
        return "VM.PROCESS."
                + action().substring(action().indexOf('.') + 1).toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public String getEventDescription() {
        return "VM process action " + action();
    }

    @Override
    public Long getApiResourceId() {
        return virtualMachineId;
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.VirtualMachine;
    }

    @Override
    public long getEntityOwnerId() {
        UserVm vm = _entityMgr.findById(UserVm.class, virtualMachineId);
        return vm == null ? Account.ACCOUNT_ID_SYSTEM : vm.getAccountId();
    }

    @Override
    public void execute() {
        VmProcessActionResponse r =
                service.execute(
                        virtualMachineId, requestId, snapshotId, pid, action(), serviceName());
        if (!"SUCCEEDED".equals(r.getProcessState().get("state")))
            throw new ServerApiException(
                    ApiErrorCode.INTERNAL_ERROR,
                    "Process action not verified; operationId="
                            + r.getProcessState().get("operationId")
                            + " state="
                            + r.getProcessState().get("state"));
        r.setObjectName("processoperation");
        r.setResponseName(getCommandName());
        setResponseObject(r);
    }
}
