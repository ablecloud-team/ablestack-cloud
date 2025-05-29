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
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UpdateHostLunDevicesResponse;
import org.apache.cloudstack.context.CallContext;
// import org.apache.cloudstack.api.response.ListResponse;
// import org.apache.cloudstack.api.response.HostResponse;

@APICommand(name = "updateHostLunDevices", description = "list Host Lun Devices'.", since = "4.20.0.0", responseObject = UpdateHostLunDevicesResponse.class, requestHasSensitiveInfo = false, responseHasSensitiveInfo = false, authorized = {
        RoleType.Admin })
public class UpdateHostLunDevicesCmd extends BaseListCmd {

    private static final String UPDATEHOSTDEVICES = "updatehostdevices";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.HOST_ID, type = BaseCmd.CommandType.UUID, entityType = UpdateHostLunDevicesResponse.class, description = "host ID", required = true, validations = {
            ApiArgValidator.PositiveNumber })
    private Long hostId;

    @Parameter(name = ApiConstants.HOSTDEVICES_NAME, type = CommandType.STRING, required = true,
            description = "Device name to allocate")
    private String hostDeviceName;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID,
            entityType = UpdateHostLunDevicesResponse.class,
            required = false, description = "VM ID to allocate the device to")
    private Long vmId;

    @Parameter(name = ApiConstants.XML_CONFIG, type = CommandType.STRING, required = false,
            description = "XML configuration for device attachment")
    private String xmlConfig;

    @Parameter(name = ApiConstants.CURRENT_VM_ID, type = CommandType.STRING, required = false,
            description = "Current VM ID")
    private String currentVmId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getHostId() {
        return hostId;
    }

    public String getHostDeviceName() {
        return hostDeviceName;
    }

    public Long getVirtualMachineId() {
        return vmId;
    }

    public String getXmlConfig() {
        return xmlConfig;
    }

    public void setXmlConfig(String xmlConfig) {
        this.xmlConfig = xmlConfig;
    }

    public String getCurrentVmId() {
        return currentVmId;
    }

    public void setCurrentVmId(String currentVmId) {
        this.currentVmId = currentVmId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    public static String getResultObjectName() {
        return "updatehostdevices";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public void execute() {
        // ListResponse<UpdateHostLunDevicesResponse> response = _mgr.updateHostLunDevices(this);
        // response.setResponseName(getCommandName());
        // response.setObjectName(getCommandName());
        // this.setResponseObject(response);
    }
}