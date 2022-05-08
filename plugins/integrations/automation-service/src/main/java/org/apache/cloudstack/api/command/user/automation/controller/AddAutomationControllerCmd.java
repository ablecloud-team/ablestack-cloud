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

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.AutomationControllerResponse;
import org.apache.cloudstack.api.response.AutomationControllerVersionResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ProjectResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.log4j.Logger;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.automation.controller.AutomationController;
import com.cloud.automation.controller.AutomationControllerService;
import com.cloud.utils.exception.CloudRuntimeException;


@APICommand(name = AddAutomationControllerCmd.APINAME,
        description = "Add a Automation Controller",
        responseObject = AutomationControllerResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {AutomationController.class},
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class AddAutomationControllerCmd extends BaseCmd implements AdminCmd {
    public static final Logger LOGGER = Logger.getLogger(AddAutomationControllerCmd.class.getName());
    public static final String APINAME = "addAutomationController";

    @Inject
    private AutomationControllerService automationControllerService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.STRING, required = true, description = "id for the Automation Controller")
    private String id;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "name for the Automation Controller")
    private String name;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.STRING, required = true, description = "zone id for the Automation Controller ")
    private String zoneId;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, required = true, description = "description for the Automation Controller")
    private String description;

    @Parameter(name = ApiConstants.AUTOMATION_TEMPLATE_ID, type = CommandType.UUID, entityType = AutomationControllerVersionResponse.class, required = true,
            description = "Desktop version with which cluster to be launched")
    private Long automationTemplateId;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.SERVICE_OFFERING_ID, type = CommandType.UUID, entityType = ServiceOfferingResponse.class,
            required = true, description = "the ID of the service offering for the virtual machines in the cluster.")
    private Long serviceOfferingId;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, description = "an optional account for the" +
            " virtual machine. Must be used with domainId.")
    private String accountName;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class,
            description = "an optional domainId for the virtual machine. If the account parameter is used, domainId must also be used.")
    private Long domainId;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.PROJECT_ID, type = CommandType.UUID, entityType = ProjectResponse.class,
            description = "Deploy cluster for the project")
    private Long projectId;

    @ACL(accessType = SecurityChecker.AccessType.UseEntry)
    @Parameter(name = ApiConstants.NETWORK_ID, type = CommandType.UUID, entityType = NetworkResponse.class, required = true,
            description = "Network in which Automation Controller is to be launched")
    private Long networkId;

    public AddAutomationControllerCmd() {
    }


    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getZoneId() {
        return zoneId;
    }

    public String getDescription() {
        return description;
    }

    public Long getAutomationTemplateId() {
        return automationTemplateId;
    }

    public Long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public String getAccountName() {
        return accountName;
    }

    public Long getDomainId() {
        return domainId;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }
    public static String getResultObjectName() {
        return "automationcontroller";
    }
    public Long getProjectId() {
        return projectId;
    }

    public Long getNetworkId() {
        return networkId;
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
            AutomationControllerResponse response = automationControllerService.addAutomationController(this);
            if (response == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to Add Automation Controller.");
            }
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}