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

package org.apache.cloudstack.api.command.admin.desktop;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.DesktopControllerVersionResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.desktop.version.DesktopControllerVersion;
import com.cloud.desktop.version.DesktopVersionEventTypes;
import com.cloud.desktop.version.DesktopVersionService;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = DeleteDesktopControllerVersionCmd.APINAME,
        description = "Delete a Desktop Controller Version",
        responseObject = SuccessResponse.class,
        entityType = {DesktopControllerVersion.class},
        authorized = {RoleType.Admin})
public class DeleteDesktopControllerVersionCmd extends BaseAsyncCmd implements AdminCmd {
    public static final String APINAME = "deleteDesktopControllerVersion";

    @Inject
    private DesktopVersionService desktopVersionService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = DesktopControllerVersionResponse.class,
            description = "the ID of the Desktop Controller Version",
            required = true)
    private Long id;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public Long getId() {
        return id;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    @Override
    public String getEventType() {
        return DesktopVersionEventTypes.EVENT_DESKTOP_CONTROLLER_VERSION_DELETE;
    }

    @Override
    public String getEventDescription() {
        String description = "Deleting Desktop Contoller Version";
        DesktopControllerVersion version = _entityMgr.findById(DesktopControllerVersion.class, getId());
        if (version != null) {
            description += String.format(" ID: %s", version.getUuid());
        } else {
            description += String.format(" ID: %d", getId());
        }
        return description;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////
    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        try {
            if (!desktopVersionService.deleteDesktopContollerVersion(this)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Failed to delete desktop controller version ID: %d", getId()));
            }
            SuccessResponse response = new SuccessResponse(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}
