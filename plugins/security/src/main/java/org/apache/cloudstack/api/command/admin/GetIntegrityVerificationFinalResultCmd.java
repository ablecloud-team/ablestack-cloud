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

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.security.IntegrityVerificationFinalResult;
import com.cloud.security.IntegrityVerificationService;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.GetIntegrityVerificationFinalResultListResponse;
import org.apache.cloudstack.api.response.GetIntegrityVerificationFinalResultResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;

@APICommand(name = GetIntegrityVerificationFinalResultCmd.APINAME,
        description = "integrity verification final results",
        responseObject = GetIntegrityVerificationFinalResultListResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {IntegrityVerificationFinalResult.class},
        authorized = {RoleType.Admin})
public class GetIntegrityVerificationFinalResultCmd extends BaseListCmd {
    public static final Logger LOG = LogManager.getLogger(GetIntegrityVerificationFinalResultCmd.class);
    public static final String APINAME = "getIntegrityVerificationFinalResult";

    @Inject
    private IntegrityVerificationService integrityService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = GetIntegrityVerificationFinalResultResponse.class,
            description = "the ID of the integrity verification final result")
    private Long id;

    @Parameter(name = ApiConstants.MANAGEMENT_SERVER_ID, type = CommandType.UUID, entityType = ManagementServerResponse.class, description = "the uuid of the management server", required = true)
    private Long msHostId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////
    public Long getId() {
        return id;
    }

    public Long getMsHostId() {
        return msHostId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + BaseListCmd.RESPONSE_SUFFIX;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() throws ResourceUnavailableException, ServerApiException {
        try {
            ListResponse<GetIntegrityVerificationFinalResultListResponse> response = integrityService.listIntegrityVerificationFinalResults(this);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex){
            ex.printStackTrace();
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to get integrity verification final results due to: " + ex.getLocalizedMessage());
        }
    }
}
