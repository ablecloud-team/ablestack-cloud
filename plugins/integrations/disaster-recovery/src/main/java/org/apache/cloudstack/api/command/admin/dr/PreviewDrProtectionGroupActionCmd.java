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

package org.apache.cloudstack.api.command.admin.dr;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.dr.DrPlanResponse;
import org.apache.cloudstack.api.response.dr.DrProtectionGroupPlanPreflightResponse;
import org.apache.cloudstack.api.response.dr.DrProtectionGroupPreflightResponse;

import com.cloud.dr.DrProtectionGroupPlanPreflight;
import com.cloud.dr.DrProtectionGroupPreflight;
import com.cloud.dr.DrProtectionGroupService;
import com.cloud.user.Account;

@APICommand(name = PreviewDrProtectionGroupActionCmd.APINAME,
        description = "Preview DR protection group action eligibility",
        responseObject = DrProtectionGroupPreflightResponse.class, authorized = {RoleType.Admin})
public class PreviewDrProtectionGroupActionCmd extends BaseCmd {
    public static final String APINAME = "previewDrProtectionGroupAction";

    @Inject private DrProtectionGroupService groupService;

    @Parameter(name = "planids", type = CommandType.LIST, collectionType = CommandType.UUID,
            entityType = DrPlanResponse.class, required = true, description = "ordered DR plan IDs")
    private List<Long> planIds;
    @Parameter(name = "action", type = CommandType.STRING, required = true, description = "group action")
    private String action;
    @Parameter(name = "quiescerequired", type = CommandType.BOOLEAN,
            description = "require application quiesce policies")
    private Boolean quiesceRequired;

    @Override
    public void execute() {
        DrProtectionGroupPreflight preflight = groupService.previewGroupRun(planIds, action, quiesceRequired);
        DrProtectionGroupPreflightResponse response = new DrProtectionGroupPreflightResponse();
        response.setAction(preflight.getAction());
        response.setReady(preflight.isReady());
        List<DrProtectionGroupPlanPreflightResponse> planResponses = new ArrayList<>();
        for (DrProtectionGroupPlanPreflight result : preflight.getPlans()) {
            DrProtectionGroupPlanPreflightResponse planResponse = new DrProtectionGroupPlanPreflightResponse();
            planResponse.setPlanId(result.getPlanUuid());
            planResponse.setPlanName(result.getPlanName());
            planResponse.setPlanState(result.getPlanState());
            planResponse.setAdminState(result.getAdminState());
            planResponse.setEligible(result.isEligible());
            planResponse.setReasonCode(result.getReasonCode());
            planResponse.setReasonArgs(result.getReasonArgs());
            planResponse.setObjectName("drprotectiongroupplanpreflight");
            planResponses.add(planResponse);
        }
        response.setPlans(planResponses);
        response.setObjectName("drprotectiongrouppreflight");
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override public String getCommandName() { return APINAME.toLowerCase() + RESPONSE_SUFFIX; }
    @Override public long getEntityOwnerId() { return Account.ACCOUNT_ID_SYSTEM; }
}
