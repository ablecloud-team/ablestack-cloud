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

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.automation.resource.AutomationResourceService;

@APICommand(name = UpdateDeployedResourceGroupCmd.APINAME,
        description = "Update a Automation Deployed Resource Group",
        responseObject = AutomationDeployedResourceResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {AutomationResourceService.class},
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateDeployedResourceGroupCmd extends BaseCmd implements AdminCmd {
    public static final String APINAME = "updateDeployedResourceGroup";

    @Inject
    private AutomationResourceService automationResourceService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = BaseCmd.CommandType.UUID,
            entityType = AutomationDeployedResourceResponse.class,
            description = "the ID of the automation deployed resource",
            required = true)
    private Long id;

    @Parameter(name = ApiConstants.ACCESS_INFO, type = CommandType.STRING, required = true,
    description = "a description of how to access the service")
    private String accessInfo;

    @Parameter(name = ApiConstants.STATE, type = CommandType.STRING,
            description = "the Running or Stopped state of the automation deployed resource",
            required = true)
    private String state;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public Long getId() {
        return id;
    }

    public String getAccessInfo() {
        return accessInfo;
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
        return 0;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////
    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        try {
            AutomationDeployedResourceResponse response = automationResourceService.updateDeployedResourceGroup(this);
            if (response == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update automation deployed resource");
            }
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}