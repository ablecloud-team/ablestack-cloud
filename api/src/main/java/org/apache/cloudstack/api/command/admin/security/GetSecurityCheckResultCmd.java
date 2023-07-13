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

package org.apache.cloudstack.api.command.admin.security;

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SecurityCheckResultResponse;
import org.apache.cloudstack.security.SecurityCheckService;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.cloudstack.api.response.SecurityCheckResultListResponse;
import org.apache.log4j.Logger;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = GetSecurityCheckResultCmd.APINAME,
        responseObject = SecurityCheckResultListResponse.class,
        description = "security check results",
        entityType = {ManagementServerHost.class},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin},
        since = "ABLESTACK-Diplo")
public class GetSecurityCheckResultCmd extends BaseCmd {
    public static final Logger LOG = Logger.getLogger(GetSecurityCheckResultCmd.class);
    public static final String APINAME = "getSecurityCheckResult";

    @Inject
    private SecurityCheckService securityService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.MANAGEMENT_SERVER_ID, type = CommandType.UUID, entityType = ManagementServerResponse.class,
            required = true, description = "the ID of the mshost")
    private Long mshostId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getMshostId() {
        return mshostId;
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
            List<SecurityCheckResultResponse> securityChecks = securityService.listSecurityChecks(this);
            SecurityCheckResultListResponse response = new SecurityCheckResultListResponse();
            response.setMshostId(this._uuidMgr.getUuid(ManagementServerHost.class, getMshostId()));
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
