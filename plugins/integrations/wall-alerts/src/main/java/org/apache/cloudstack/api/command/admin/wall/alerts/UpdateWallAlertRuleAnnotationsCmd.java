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
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.WallAlertRuleResponse;
import org.apache.cloudstack.wallAlerts.service.WallAlertsService;

@APICommand(
        name = UpdateWallAlertRuleAnnotationsCmd.APINAME,
        description = "Updates Wall(Grafana) alert rule annotations (summary/description)",
        responseObject = WallAlertRuleResponse.class,
        responseView = ResponseObject.ResponseView.Restricted,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin}
)
public class UpdateWallAlertRuleAnnotationsCmd extends BaseCmd {
    public static final String APINAME = "updateWallAlertRuleAnnotations";

    @Inject
    private WallAlertsService wallAlertsService;

    @Parameter(
            name = ApiConstants.ID,
            type = CommandType.STRING,
            description = "Rule identifier (legacy). If no colon(:) is present, it is treated as uid."
    )
    private String id;

    @Parameter(
            name = "uid",
            type = CommandType.STRING,
            description = "Grafana rule UID"
    )
    private String uid;

    @Parameter(
            name = "summary",
            type = CommandType.STRING,
            description = "Rule summary text. If omitted, it will not be changed."
    )
    private String summary;

    @Parameter(
            name = "description",
            type = CommandType.STRING,
            description = "Rule description text. If omitted, it will not be changed."
    )
    private String description;

    public String getId() { return id; }
    public String getUid() { return uid; }
    public String getSummary() { return summary; }
    public String getDescription() { return description; }

    @Override
    public void execute() {
        try {
            final WallAlertRuleResponse resp = wallAlertsService.updateWallAlertRuleAnnotations(this);
            resp.setResponseName(getCommandName());
            setResponseObject(resp);
        } catch (IllegalArgumentException iae) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, iae.getMessage());
        } catch (RuntimeException re) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, re.getMessage());
        }
    }

    @Override
    public String getCommandName() {
        return "updatewallalertruleannotationsresponse";
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
}
