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

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiArgValidator;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.CreateVhbaDeviceResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = "createVhbaDevice", description = "Create a vHBA device", since = "4.20.0.0", 
           responseObject = CreateVhbaDeviceResponse.class, requestHasSensitiveInfo = false, 
           responseHasSensitiveInfo = false, authorized = { RoleType.Admin })
public class CreateVhbaDeviceCmd extends BaseCmd {

    private static final String CREATEVHBADEVICE = "createvhbadevice";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.HOST_ID, type = BaseCmd.CommandType.UUID, entityType = CreateVhbaDeviceResponse.class, 
               description = "host ID", required = true, validations = { ApiArgValidator.PositiveNumber })
    private Long hostId;

    @Parameter(name = ApiConstants.PARENT_HB_NAME, type = CommandType.STRING, required = true,
               description = "Parent HBA device name")
    private String parentHbaName;

    @Parameter(name = ApiConstants.WWNN, type = CommandType.STRING, required = false,
               description = "World Wide Node Name")
    private String wwnn;

    @Parameter(name = ApiConstants.WWPN, type = CommandType.STRING, required = false,
               description = "World Wide Port Name")
    private String wwpn;

    @Parameter(name = ApiConstants.VHBA_NAME, type = CommandType.STRING, required = true,
               description = "vHBA device name")
    private String vhbaName;

    @Parameter(name = ApiConstants.XML_CONFIG, type = CommandType.STRING, required = true,
               description = "XML configuration for vHBA device creation")
    private String xmlContent;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getHostId() {
        return hostId;
    }

    public String getParentHbaName() {
        return parentHbaName;
    }

    public String getWwnn() {
        return wwnn;
    }

    public String getWwpn() {
        return wwpn;
    }

    public String getVhbaName() {
        return vhbaName;
    }

    public String getXmlContent() {
        return xmlContent;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    public static String getResultObjectName() {
        return "createvhbadevice";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }
    @Override
    public void execute() {
        ListResponse<CreateVhbaDeviceResponse> response = _mgr.createVhbaDevice(this);
        response.setResponseName(getCommandName());
        response.setObjectName(getCommandName());
        this.setResponseObject(response);
    }
}