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
package org.apache.cloudstack.api.command.user.ssv;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SSVResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.log4j.Logger;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.ssv.SSV;
import com.cloud.ssv.SSVEventTypes;
import com.cloud.ssv.SSVService;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = StartSSVCmd.APINAME, description = "Starts a stopped Shared Storage VM ",
        responseObject = SSVResponse.class,
        responseView = ResponseObject.ResponseView.Restricted,
        entityType = {SSV.class},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class StartSSVCmd extends BaseAsyncCmd {
    public static final Logger LOGGER = Logger.getLogger(StartSSVCmd.class.getName());
    public static final String APINAME = "startSSV";

    @Inject
    public SSVService ssvService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = SSVResponse.class, required = true,
            description = "the ID of the Shared Storage VM ")
    private Long id;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    @Override
    public String getEventType() {
        return SSVEventTypes.EVENT_SSV_START;
    }

    @Override
    public String getEventDescription() {
        String description = "Starting Shared Storage VM";
        SSV cluster = _entityMgr.findById(SSV.class, getId());
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

    public SSV validateRequest() {
        if (getId() == null || getId() < 1L) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Invalid Shared Storage VM ID provided");
        }
        final SSV ssv = ssvService.findById(getId());
        if (ssv == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Given Shared Storage VM was not found");
        }
        return ssv;
    }

    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        final SSV ssv = validateRequest();
        try {
            if (!ssvService.startSSV(null, ssv.getId(), false)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Failed to start Shared Storage VM ID: %d", getId()));
            }
            final SSVResponse response = ssvService.createSSVResponse(ssv.getId());
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }

}
