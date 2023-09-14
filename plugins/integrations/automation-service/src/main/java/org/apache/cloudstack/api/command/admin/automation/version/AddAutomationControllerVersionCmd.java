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

package org.apache.cloudstack.api.command.admin.automation.version;

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
import org.apache.cloudstack.api.response.AutomationControllerVersionResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.api.response.GuestOSResponse;
import org.apache.cloudstack.api.response.TemplateResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.automation.version.AutomationControllerVersion;
import com.cloud.automation.version.AutomationVersionService;
import com.cloud.utils.exception.CloudRuntimeException;


@APICommand(name = AddAutomationControllerVersionCmd.APINAME,
        description = "Add a Automation Controller Version",
        responseObject = AutomationControllerVersionResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {AutomationControllerVersion.class},
        authorized = {RoleType.Admin})
public class AddAutomationControllerVersionCmd extends BaseCmd implements AdminCmd {
    public static final String APINAME = "addAutomationControllerVersion";

    @Inject
    private AutomationVersionService automationVersionService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.FORMAT, type = CommandType.STRING,
            description = "the format for the template. Possible values include QCOW2")
    private String format;

    @Parameter(name = ApiConstants.HYPERVISOR, type = CommandType.STRING,
            description = "the target hypervisor for the template")
    protected String hypervisor;

    @Parameter(name = ApiConstants.AUTOMATION_CONTROLLER_VERSION_NAME, type = CommandType.STRING, required = true,
            description = "the name of the automation controller version")
    private String controllerVersionName;

    @Parameter(name = ApiConstants.DESCRIPTION, type = CommandType.STRING, required = true,
            description = "the name of the automation controller version")
    private String description;

    @Parameter(name = ApiConstants.AUTOMATION_CONTROLLER_VERSION, type = CommandType.STRING, required = true,
            description = "the automation controller version.")
    private String automationControllerVersion;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            description = "the ID of the zone in which automation controller version will be available")
    private Long zoneId;

    @Parameter(name = ApiConstants.URL, type = CommandType.STRING,
            description = "the URL of the template for automation controller version")
    private String url;

    @Parameter(name = ApiConstants.OS_TYPE,
            type = CommandType.UUID,
            entityType = GuestOSResponse.class,
            description = "the ID of the OS Type that best represents the OS of this template. Not applicable with VMware, as we honour what is defined in the template")
    private Long osType;

    @Parameter(name = ApiConstants.AUTOMATION_CONTROLLER_VERSION_UPLOADTYPE, type = CommandType.STRING, required = true,
        description = "upload type for automation controller version template")
    private String uploadType;

    @Parameter(name = ApiConstants.TEMPLATE_ID,
            type = CommandType.UUID,
            entityType = TemplateResponse.class,
            description = "an optional template Id to restore vm from the new template. This can be an ISO id in case of restore vm deployed using ISO")
    private Long templateId;


    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public String getFormat() {
        return format;
    }

    public String getHypervisor() {
        return hypervisor;
    }

    public String getControllerVersionName() {
        return controllerVersionName;
    }

    public String getDescription() {
        return description;
    }

    public String getAutomationControllerVersion() {
        return automationControllerVersion;
    }

    public Long getZoneId() {
        return zoneId;
    }

    public String getUrl() {
        return url;
    }

    public Long getOsType() {
        return osType;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public String getUploadType() {
        return uploadType;
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
            AutomationControllerVersionResponse response = automationVersionService.addAutomationControllerVersion(this);
            if (response == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to Add Automation Controller Version.");
            }
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}
