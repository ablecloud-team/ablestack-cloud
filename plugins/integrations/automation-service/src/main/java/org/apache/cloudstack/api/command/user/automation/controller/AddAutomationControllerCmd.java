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

import javax.inject.Inject;

import com.cloud.automation.controller.AutomationControllerEventTypes;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.AutomationControllerResponse;
import org.apache.cloudstack.api.response.AutomationControllerVersionResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.automation.controller.AutomationController;
import com.cloud.automation.controller.AutomationControllerService;
import com.cloud.utils.exception.CloudRuntimeException;


@APICommand(name = AddAutomationControllerCmd.APINAME,
        description = "Add a Automation Controller",
        responseObject = AutomationControllerResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {AutomationController.class},
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class AddAutomationControllerCmd extends BaseAsyncCreateCmd {
    public static final String APINAME = "addAutomationController";

    @Inject
    private AutomationControllerService automationControllerService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, required = true, description = "description for the Automation Controller")
    private String description;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class,
            description = "an optional domainId for the virtual machine. If the account parameter is used, domainId must also be used.")
    private Long domainId;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.AUTOMATION_CONTROLLER_IP, type = CommandType.STRING,
            description = "Network in which Automation Controller is to be launched")
    private String automationControllerIp;

    @Parameter(name = ApiConstants.ACCOUNT_ID, type = CommandType.UUID, entityType = AccountResponse.class,
    description = "the account's id associated with this Automation")
    private Long accountId;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class,
            required = true, description = "the ID of the service offering for the virtual machines in the cluster.")
    private Long serviceOfferingId;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "name for the Automation Controller")
    private String name;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, required = true, entityType = ZoneResponse.class, description = "zone id for the Automation Controller ")
    private Long zoneId;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.NETWORK_ID, type = CommandType.UUID, entityType = NetworkResponse.class, required = true,
            description = "Network ID which Automation Controller is to be launched")
    private Long networkId;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.NETWORK_NAME, type = CommandType.STRING, required = true,
            description = "Network Name which Automation Controller is to be launched")
    private String networkName;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "an optional account for the" +
            " virtual machine. Must be used with domainId.")
    private String accountName;

    @Parameter(name = ApiConstants.AUTOMATION_TEMPLATE_ID, type = CommandType.UUID, entityType = AutomationControllerVersionResponse.class, required = true,
            description = "Automation Controller version with which cluster to be launched")
    private Long automationTemplateId;


    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public String getDescription() {
        return description;
    }

    public Long getDomainId() {
        if (domainId == null) {
            return CallContext.current().getCallingAccount().getDomainId();
        }
        return domainId;
    }

    public String getAutomationControllerIp() {
        return automationControllerIp;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public String getName() {
        return name;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public String getNetworkName() {
        return networkName;
    }

    public String getAccountName() {
        if (accountName == null) {
            return CallContext.current().getCallingAccount().getAccountName();
        }
        return accountName;
    }

    public Long getAutomationTemplateId() {
        return automationTemplateId;
    }


    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.VirtualMachine;
    }

    @Override
    public String getEventType() {
        return AutomationControllerEventTypes.EVENT_AUTOMATION_CONTROLLER_ADD;
    }

    @Override
    public String getCreateEventType() {
        return AutomationControllerEventTypes.EVENT_AUTOMATION_CONTROLLER_ADD;
    }

    @Override
    public String getCreateEventDescription() {
        return "creating Automation Controller";
    }

    @Override
    public String getEventDescription() {
        return "Creating Automation Controller. Controller Id: " + getEntityId();
    }


    @Override
    public void execute() {
        try {
            if (!automationControllerService.startAutomationController(getEntityId(), true)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to start Automation Controller");
            }
            AutomationControllerResponse response = automationControllerService.addAutomationControllerResponse(getEntityId());
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
    @Override
    public void create() throws CloudRuntimeException {
        try {
            AutomationController automationController = automationControllerService.addAutomationController(this);
            if (automationController == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create Automation Controller");
            }
            setEntityId(automationController.getId());
            setEntityUuid(automationController.getUuid());
        } catch (CloudRuntimeException e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}