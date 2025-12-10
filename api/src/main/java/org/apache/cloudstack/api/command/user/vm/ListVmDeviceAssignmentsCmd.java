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

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VmDeviceAssignmentResponse;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = "listVmDeviceAssignments",
        description = "Lists device assignments for a virtual machine based on database records.",
        responseObject = VmDeviceAssignmentResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListVmDeviceAssignmentsCmd extends BaseListCmd {
    private static final String RESPONSE_NAME = "listvmdeviceassignmentsresponse";

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "ID of the virtual machine.")
    private Long virtualMachineId;

    public static String getResultObjectName() {
        return "vmdeviceassignment";
    }

    public Long getVirtualMachineId() {
        return virtualMachineId;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public void execute() {
        ListResponse<VmDeviceAssignmentResponse> response = _mgr.listVmDeviceAssignments(this);
        response.setResponseName(RESPONSE_NAME);
        response.setObjectName(getResultObjectName());
        setResponseObject(response);
    }
}

