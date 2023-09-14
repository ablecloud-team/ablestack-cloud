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

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.GetSecurityCheckResponse;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.cloudstack.api.response.GetSecurityCheckListResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.security.SecurityCheck;
import com.cloud.security.SecurityCheckService;

@APICommand(name = GetSecurityCheckCmd.APINAME,
        description = "security check results",
        responseObject = GetSecurityCheckListResponse.class,
        responseView = ResponseObject.ResponseView.Full,
        entityType = {SecurityCheck.class},
        authorized = {RoleType.Admin})
public class GetSecurityCheckCmd extends BaseCmd {
    public static final Logger LOG = LogManager.getLogger(GetSecurityCheckCmd.class);
    public static final String APINAME = "getSecurityCheck";

    @Inject
    private SecurityCheckService securityService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.MANAGEMENT_SERVER_ID, type = CommandType.UUID, entityType = ManagementServerResponse.class, description = "the uuid of the management server", required = true)
    private Long msHostId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getMsHostId() {
        return msHostId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + BaseCmd.RESPONSE_SUFFIX;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() throws ResourceUnavailableException, ServerApiException {
        try {
            List<GetSecurityCheckResponse> securityChecks = securityService.listSecurityChecks(this);
            GetSecurityCheckListResponse response = new GetSecurityCheckListResponse();
            response.setMsHostId(this._uuidMgr.getUuid(ManagementServerHost.class, getMsHostId()));
            response.setSecurityChecks(securityChecks);
            response.setObjectName("securitychecks");
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex){
            ex.printStackTrace();
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to get security check results due to: " + ex.getLocalizedMessage());
        }
    }
}
