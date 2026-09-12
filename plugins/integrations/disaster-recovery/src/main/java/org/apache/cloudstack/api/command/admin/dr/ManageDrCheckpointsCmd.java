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
import org.apache.cloudstack.api.response.dr.DrCheckpointManagementResponse;
import com.cloud.dr.DrCheckpointCleanupService;
import com.cloud.user.Account;

@APICommand(name = "manageDrCheckpoints", description = "Preview or explicitly clean owned checkpoint sets; list independent unregister records",
        responseObject = DrCheckpointManagementResponse.class, authorized = {RoleType.Admin})
public class ManageDrCheckpointsCmd extends BaseCmd {
    @Inject private DrCheckpointCleanupService service;
    @Parameter(name = "planuuid", type = CommandType.STRING, description = "Active plan UUID; omit to list unregister records")
    private String planUuid;
    @Parameter(name = "keepcount", type = CommandType.INTEGER, description = "Minimum recent sets retained, at least two")
    private Integer keepCount;
    @Parameter(name = "keepdays", type = CommandType.INTEGER, description = "Retain sets within this many days")
    private Integer keepDays;
    @Parameter(name = "selection", type = CommandType.STRING, description = "Explicit JSON array of checkpointRef and manifestSha256")
    private String selection;
    @Parameter(name = "reason", type = CommandType.STRING, description = "Cleanup reason")
    private String reason;
    @Parameter(name = "savepolicy", type = CommandType.BOOLEAN, description = "Persist manual retention selection policy, without automatic deletion")
    private Boolean savePolicy;
    @Override public void execute() {
        String details = (planUuid == null ? service.records()
                : service.manage(planUuid, keepCount, keepDays, selection, reason, Boolean.TRUE.equals(savePolicy))).toString();
        DrCheckpointManagementResponse response = new DrCheckpointManagementResponse(details);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
    @Override public String getCommandName() { return "managedrcheckpointsresponse"; }
    @Override public long getEntityOwnerId() { return Account.ACCOUNT_ID_SYSTEM; }
}
