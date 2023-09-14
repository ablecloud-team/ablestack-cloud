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
package org.apache.cloudstack.api.command.admin;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.security.IntegrityVerification;
import com.cloud.security.IntegrityVerificationEventTypes;
import com.cloud.security.IntegrityVerificationFinalResult;
import com.cloud.security.IntegrityVerificationService;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.GetIntegrityVerificationFinalResultListResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;

@APICommand(name = DeleteIntegrityVerificationFinalResultCmd.APINAME,
        description = "Delete integrity verification final results",
        responseObject = SuccessResponse.class,
        entityType = {IntegrityVerificationFinalResult.class},
        authorized = {RoleType.Admin})
public class DeleteIntegrityVerificationFinalResultCmd extends BaseAsyncCmd {
    public static final Logger LOG = LogManager.getLogger(DeleteIntegrityVerificationFinalResultCmd.class);
    public static final String APINAME = "deleteIntegrityVerificationFinalResult";

    @Inject
    private IntegrityVerificationService integrityService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = GetIntegrityVerificationFinalResultListResponse.class,
            required = true,
            description = "the ID of the integrity verification final result")
    private Long id;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public Long getId() {
        return id;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public Long getApiResourceId() {
        return getId();
    }


    @Override
    public String getEventType() {
        return IntegrityVerificationEventTypes.EVENT_INTEGRITY_VERIFICATION_DELETE;
    }

    @Override
    public String getEventDescription() {
        String description = "Integrity Verification Final Results";
        IntegrityVerification version = _entityMgr.findById(IntegrityVerification.class, getId());
        description += String.format(" ID: %d", getId());
        return description;
    }


    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        try {
            if (!integrityService.deleteIntegrityVerificationFinalResults(this)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Failed to delete integrity verification final results due to: ", getId()));
            }
            SuccessResponse response = new SuccessResponse(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex){
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }
}
