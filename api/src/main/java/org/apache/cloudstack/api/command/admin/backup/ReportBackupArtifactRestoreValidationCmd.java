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
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.backup.BackupManager;

@APICommand(name = "reportBackupArtifactRestoreValidation", description = "Record NetBackup file selection diagnostics without starting a VM restore or reserving storage",
        responseObject = SuccessResponse.class, since = "4.23.0", authorized = {RoleType.Admin}, httpMethod = "POST")
public class ReportBackupArtifactRestoreValidationCmd extends BaseCmd implements AdminCmd {
    @Inject private BackupManager backupManager;
    @Parameter(name = ApiConstants.JOB_ID, type = CommandType.STRING, required = true, description = "NetBackup restore Job ID") private String jobId;
    @Parameter(name = "validation", type = CommandType.STRING, required = true, length = 65536,
            description = "Bounded JSON selection state, reason, destination Host, completion window and exact artifact references") private String validation;

    @Override public long getEntityOwnerId() { return 0; }
    @Override public void execute() {
        try {
            backupManager.reportBackupArtifactRestoreValidation(jobId, validation);
            SuccessResponse response = new SuccessResponse(getCommandName());
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (Exception e) { throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage()); }
    }
}
