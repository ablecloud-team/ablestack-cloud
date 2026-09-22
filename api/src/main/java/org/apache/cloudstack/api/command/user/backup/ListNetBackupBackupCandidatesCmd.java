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

package org.apache.cloudstack.api.command.user.backup;

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.NetBackupBackupCandidateResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = "listNetBackupBackupCandidates",
        description = "List VM backup schedules due for NetBackup host-policy execution",
        responseObject = NetBackupBackupCandidateResponse.class, since = "4.23.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin})
public class ListNetBackupBackupCandidatesCmd extends BaseListCmd {

    @Inject
    private BackupManager backupManager;

    @Parameter(name = ApiConstants.HOST_ID,
            type = CommandType.UUID,
            entityType = HostResponse.class,
            description = "ID of the host running the NetBackup policy")
    private Long hostId;

    @Parameter(name = ApiConstants.HOST_NAME,
            type = CommandType.STRING,
            description = "Name of the host running the NetBackup policy")
    private String hostName;

    @Parameter(name = ApiConstants.POLICY_ID,
            type = CommandType.STRING,
            description = "NetBackup policy ID/name")
    private String policyId;

    @Parameter(name = "claim",
            type = CommandType.BOOLEAN,
            description = "Whether to claim returned schedules by advancing their next scheduled timestamp")
    private Boolean claim;

    public Long getHostId() {
        return hostId;
    }

    public String getHostName() {
        return hostName;
    }

    public String getPolicyId() {
        return policyId;
    }

    public boolean isClaim() {
        return Boolean.TRUE.equals(claim);
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            final List<NetBackupBackupCandidateResponse> candidates = backupManager.listNetBackupBackupCandidates(this);
            final ListResponse<NetBackupBackupCandidateResponse> response = new ListResponse<>();
            response.setResponses(candidates, candidates.size());
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (Exception e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
