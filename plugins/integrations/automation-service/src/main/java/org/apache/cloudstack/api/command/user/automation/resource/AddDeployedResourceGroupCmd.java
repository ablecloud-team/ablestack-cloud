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
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.api.response.AutomationControllerResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.automation.resource.AutomationDeployedUnitResource;
import com.cloud.automation.resource.AutomationResourceService;
import com.cloud.utils.exception.CloudRuntimeException;


@APICommand(name = AddDeployedResourceGroupCmd.APINAME,
        description = "Add a Automation Deployed Resource Group",
        responseObject = AutomationDeployedResourceResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {AutomationDeployedUnitResource.class},
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class AddDeployedResourceGroupCmd extends BaseCmd implements AdminCmd {
    public static final String APINAME = "addDeployedResourceGroup";

    @Inject
    private AutomationResourceService automationResourceService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true,
            description = "the name of deployed service")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, required = true,
            description = "the description of the running service")
    private String description;

    @Parameter(name = ApiConstants.ACCESS_INFO, type = CommandType.STRING, required = true,
    description = "a description of how to access the service")
    private String accessInfo;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true,
            description = "the ID of the zone in which this Automation Controller VM is deployed")
    private Long zoneId;

    @Parameter(name = ApiConstants.AUTOMATION_CONTROLLER_ID, type = CommandType.UUID, entityType = AutomationControllerResponse.class, required = true,
        description = "the ID of the automation controller id")
    private Long controllerId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getAccessInfo() {
        return accessInfo;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getControllerId() {
        return controllerId;
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
            AutomationDeployedResourceResponse response = automationResourceService.addDeployedResourceGroup(this);
            if (response == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to Add Automation Deployed Resource Group.");
            }
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}