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

package org.apache.cloudstack.api.command.admin.wall.alerts;

import javax.inject.Inject;

import com.cloud.user.Account;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.wallAlerts.service.WallAlertsService;

@APICommand(name = ExpireWallAlertSilenceCmd.APINAME,
        description = "Expires (deletes) a specific Alertmanager silence by ID",
        responseObject = SuccessResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = { RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin })
public class ExpireWallAlertSilenceCmd extends BaseCmd {
    public static final String APINAME = "expireWallAlertSilence";

    @Inject
    private WallAlertsService wallAlertsService;

    // ---------- Parameter ----------
    @Parameter(name = ApiConstants.ID, type = CommandType.STRING, required = true,
            description = "Silence ID to expire")
    private String id;

    // ---------- Getter ----------
    public String getId() {
        return id;
    }

    // ---------- Execution ----------
    @Override
    public void execute() {
        try {
            final SuccessResponse response = wallAlertsService.expireWallAlertSilence(this);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (IllegalArgumentException iae) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, iae.getMessage());
        } catch (RuntimeException re) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, re.getMessage());
        }
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
}
