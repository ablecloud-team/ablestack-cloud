// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.api.command.admin.backup;

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.BackupArtifactResolutionResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.backup.BackupManager;

@APICommand(name = "resolveBackupArtifact", description = "Resolve an exact third-party artifact or restore job to one logical Mold backup",
        responseObject = BackupArtifactResolutionResponse.class, since = "4.23.0", authorized = {RoleType.Admin})
public class ResolveBackupArtifactCmd extends BaseCmd implements AdminCmd {
    @Inject private BackupManager backupManager;
    @Parameter(name = "provider", type = CommandType.STRING, required = true, description = "ABLESTACK provider name") private String provider;
    @Parameter(name = ApiConstants.EXTERNAL_ID, type = CommandType.STRING, description = "Exact catalog artifact ID or original artifact selection") private String externalId;
    @Parameter(name = ApiConstants.JOB_ID, type = CommandType.STRING, description = "External restore Job ID for duplicate callback detection") private String jobId;
    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID, type = CommandType.UUID, entityType = UserVmResponse.class,
            description = "Optional original VM filter") private Long vmId;

    @Override public long getEntityOwnerId() { return 0; }
    @Override public void execute() {
        try {
            BackupArtifactResolutionResponse response = backupManager.resolveBackupArtifact(provider, externalId, jobId, vmId);
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (Exception e) { throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage()); }
    }
}

