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

package org.apache.cloudstack.api.command.user.automation.resource;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.AutomationDeployedResourceResponse;
import org.apache.cloudstack.api.response.AutomationDeployedUnitResourceResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.automation.resource.AutomationDeployedUnitResource;
import com.cloud.automation.resource.AutomationResourceService;
import com.cloud.utils.exception.CloudRuntimeException;


@APICommand(name = AddDeployedUnitResourceCmd.APINAME,
        description = "Add a Automation Unit Resource",
        responseObject = AutomationDeployedUnitResourceResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {AutomationDeployedUnitResource.class},
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class AddDeployedUnitResourceCmd extends BaseCmd implements AdminCmd {
    public static final String APINAME = "addDeployedUnitResource";

    @Inject
    private AutomationResourceService automationResourceService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.DEPLOYED_GROUP_ID, type = CommandType.UUID, entityType = AutomationDeployedResourceResponse.class, required = true,
        description = "the ID of the deployed service")
    private Long deployedGroupId;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, entityType = UserVmResponse.class, required = true,
        description = "the ID of the instance the service is running on")
    private Long deployedVmId;

    @Parameter(name = ApiConstants.SERVICE_UNIT_NAME, type = CommandType.STRING, required = true,
        description = "the name of deployed unit service")
    private String serviceUnitName;

    @Parameter(name = ApiConstants.STATE, type = CommandType.STRING, required = true,
        description = "state of deployed unit service")
    private String state;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public Long getDeployedGroupId() {
        return deployedGroupId;
    }

    public Long getDeployedVmId() {
        return deployedVmId;
    }

    public String getServiceUnitName() {
        return serviceUnitName;
    }

    public String getState() {
        return state;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////
    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        try {
            AutomationDeployedUnitResourceResponse response = automationResourceService.addDeployedUnitResource(this);
            if (response == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to Add Deployed Unit Resource.");
            }
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}