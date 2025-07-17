//
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

package org.apache.cloudstack.api.command.admin.outofbandmanagement;

import com.cloud.user.Account;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiArgValidator;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.DeleteVhbaDeviceResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;


@APICommand(name = "deleteVhbaDevice", description = "Delete a vHBA device", since = "4.20.0.0",
           responseObject = SuccessResponse.class, requestHasSensitiveInfo = false,
           responseHasSensitiveInfo = false, authorized = { RoleType.Admin })
public class DeleteVhbaDeviceCmd extends BaseCmd {

    private static final String DELETEVHBADEVICE = "deletevhbadevice";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.HOST_ID, type = BaseCmd.CommandType.UUID, entityType = DeleteVhbaDeviceResponse.class,
               description = "host ID", required = true, validations = { ApiArgValidator.PositiveNumber })
    private Long hostId;

    @Parameter(name = ApiConstants.HOSTDEVICES_NAME, type = CommandType.STRING, required = true,
               description = "vHBA device name to delete")
    private String hostDeviceName;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getHostId() {
        return hostId;
    }

    public String getHostDeviceName() {
        return hostDeviceName;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    public static String getResultObjectName() {
        return "deletevhbadevice";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public void execute() {
        ListResponse<DeleteVhbaDeviceResponse> response = _mgr.deleteVhbaDevice(this);
        response.setResponseName(getCommandName());
        response.setObjectName(getCommandName());
        this.setResponseObject(response);
    }
} 