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

import java.util.Map;

import javax.inject.Inject;

import com.cloud.user.Account;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.WallSilenceResponse;
import org.apache.cloudstack.wallAlerts.service.WallAlertsService;

/**
 * 특정 알럿(인스턴스)의 라벨 셋을 기준으로 매칭되는 Alertmanager Silences를 조회합니다.
 * 독립 리스트가 아닌, 알럿 상세 컨텍스트에서만 사용하도록 설계했습니다.
 */
@APICommand(name = ListWallAlertSilencesCmd.APINAME,
        description = "Lists Alertmanager silences that match the given alert's labels",
        responseObject = WallSilenceResponse.class,
        responseView = ResponseObject.ResponseView.Restricted,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ListWallAlertSilencesCmd extends BaseListCmd {
    public static final String APINAME = "listWallAlertSilences";

    @Inject
    private WallAlertsService wallAlertsService;

    // ---------- 필수/선택 파라미터 ----------
    @Parameter(name = "labels", type = CommandType.MAP,
            description = "Alert instance label map to evaluate (name=value). Only silences matching these labels will be returned.",
            required = true)
    private Map<String, String> labels;

    @Parameter(name = "state", type = CommandType.STRING,
            description = "Optional silence state filter (active, pending, expired)")
    private String state;

    @Parameter(name = "alertid", type = CommandType.STRING,
            description = "Optional alert instance identifier or fingerprint for logging/tracing")
    private String alertId;

    // ---------- Getters ----------
    public Map<String, String> getLabels() {
        return labels;
    }

    public String getState() {
        return state;
    }

    public String getAlertId() {
        return alertId;
    }

    // ---------- Execution ----------
    @Override
    public void execute() {
        try {
            final ListResponse<WallSilenceResponse> response = wallAlertsService.listWallAlertSilences(this);
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
