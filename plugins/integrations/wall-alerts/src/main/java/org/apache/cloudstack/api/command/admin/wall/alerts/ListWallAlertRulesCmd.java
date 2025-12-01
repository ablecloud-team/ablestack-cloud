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

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.WallAlertRuleResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.wallAlerts.service.WallAlertsService;

@APICommand(name = ListWallAlertRulesCmd.APINAME,
        description = "Lists Wall(Grafana) alert rules",
        responseObject = WallAlertRuleResponse.class,
        responseView = ResponseObject.ResponseView.Restricted,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin})
public class ListWallAlertRulesCmd extends BaseListCmd {
    public static final String APINAME = "listWallAlertRules";

    @Inject
    private WallAlertsService wallAlertsService;

    // ---------- 검색/필터 파라미터 ----------
    @Parameter(name = ApiConstants.ID, type = CommandType.STRING,
            description = "Rule identifier (e.g., dashboardUid:panelId) to filter by")
    private String id;

    @Parameter(name = "uid", type = CommandType.STRING, description = "Grafana rule UID")
    private String uid;

    @Parameter(name = ApiConstants.KEYWORD, type = CommandType.STRING,
            description = "Keyword to search (applies to name/group/query/operator/threshold)")
    private String keyword;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING,
            description = "Exact rule name to filter by")
    private String name;

    @Parameter(name = "state", type = CommandType.STRING,
            description = "Rule state to filter by (ALERTING, PENDING, OK, NODATA)")
    private String state;

    @Parameter(name = "kind", type = CommandType.STRING,
            description = "Rule kind to filter by (HOST, STORAGE, CLOUD, USER)")
    private String kind;

    // 선택: 도메인/계정 범위(필요 시 사용)
    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class)
    private Long domainId;

    @Parameter(name = ApiConstants.ACCOUNT, type = CommandType.STRING, entityType = AccountResponse.class)
    private String accountName;

    // ---------- 선택 옵션 ----------
    @Parameter(name = "includestatus", type = CommandType.BOOLEAN,
            description = "Include alert instance status")
    private Boolean includeStatus;

    // ---------- Getters ----------
    public String getId() { return id; }
    public String getUid() { return id; }
    public String getKeyword() { return keyword; }
    public String getName() { return name; }
    public String getState() { return state; }
    public String getKind() { return kind; }
    public boolean getIncludeStatus() { return Boolean.TRUE.equals(includeStatus); }

    public Long getDomainId() {
        if (domainId == null) {
            return CallContext.current().getCallingAccount().getDomainId();
        }
        return domainId;
    }

    public String getAccountName() {
        if (accountName == null) {
            return CallContext.current().getCallingAccount().getAccountName();
        }
        return accountName;
    }

    // ---------- Execution ----------
    @Override
    public void execute() {
        try {
            final ListResponse<WallAlertRuleResponse> response = wallAlertsService.listWallAlertRules(this);
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
        return "listwallalertrulesresponse";
    }
}
