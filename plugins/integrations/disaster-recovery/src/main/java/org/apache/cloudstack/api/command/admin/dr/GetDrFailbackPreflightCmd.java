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

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.dr.DrFailbackPreflightResponse;
import org.apache.cloudstack.api.response.dr.DrPlanResponse;

import com.cloud.dr.DrFailbackPreflightService;
import com.cloud.user.Account;

@APICommand(name = GetDrFailbackPreflightCmd.APINAME, description = "Get the site-derived DR failback preflight",
        responseObject = DrFailbackPreflightResponse.class, authorized = {RoleType.Admin})
public class GetDrFailbackPreflightCmd extends BaseCmd {
    public static final String APINAME = "getDrFailbackPreflight";

    @Inject
    private DrFailbackPreflightService drFailbackPreflightService;

    @Parameter(name = "planid", type = CommandType.UUID, entityType = DrPlanResponse.class,
            required = true, description = "the DR plan ID")
    private Long planId;

    @Override
    public void execute() {
        DrFailbackPreflightResponse response = DrFailbackPreflightResponse.from(
                drFailbackPreflightService.validate(planId));
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + RESPONSE_SUFFIX;
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
}
