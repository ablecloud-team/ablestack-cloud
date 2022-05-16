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
package org.apache.cloudstack.api.command.user.automation.controller;

import com.cloud.automation.controller.AutomationController;
import com.cloud.automation.controller.AutomationControllerEventTypes;
import com.cloud.automation.controller.AutomationControllerService;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AutomationControllerResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.log4j.Logger;

import javax.inject.Inject;

@APICommand(name = StartAutomationControllerCmd.APINAME, description = "Starts a stopped Automation Controller",
        responseObject = AutomationControllerResponse.class,
        responseView = ResponseObject.ResponseView.Restricted,
        entityType = {AutomationController.class},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class StartAutomationControllerCmd extends BaseAsyncCmd {
    public static final Logger LOGGER = Logger.getLogger(StartAutomationControllerCmd.class.getName());
    public static final String APINAME = "startAutomationController";

    @Inject
    public AutomationControllerService automationControllerService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = AutomationControllerResponse.class, required = true,
            description = "the ID of the Automation Controller")
    private Long id;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    @Override
    public String getEventType() {
        return AutomationControllerEventTypes.EVENT_AUTOMATION_CONTROLLER_START;
    }

    @Override
    public String getEventDescription() {
        String description = "Starting Automation Controller";
        AutomationController cluster = _entityMgr.findById(AutomationController.class, getId());
        if (cluster != null) {
            description += String.format(" ID: %s", cluster.getUuid());
        } else {
            description += String.format(" ID: %d", getId());
        }
        return description;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    public AutomationController validateRequest() {
        if (getId() == null || getId() < 1L) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Invalid Automation Controller ID provided");
        }
        final AutomationController automationController = automationControllerService.findById(getId());
        if (automationController == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Given Automation Controller was not found");
        }
        return automationController;
    }

    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        final AutomationController automationController = validateRequest();
        try {
            if (!automationControllerService.startAutomationController(automationController.getId(), false)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Failed to start Automation Controller ID: %d", getId()));
            }
            final AutomationControllerResponse response = automationControllerService.addAutomationControllerResponse(automationController);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }

}
