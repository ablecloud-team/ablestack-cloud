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
import org.apache.cloudstack.api.response.BackupResponse;
import org.apache.cloudstack.api.response.BackupStagingInfoResponse;
import org.apache.cloudstack.backup.BackupManager;

@APICommand(name = "getBackupImageInfo", description = "Read the saved NetBackup image and Job references for a logical backup",
        responseObject = BackupStagingInfoResponse.class, since = "4.23.0", authorized = {RoleType.Admin})
public class GetBackupImageInfoCmd extends BaseCmd implements AdminCmd {
    @Inject private BackupManager backupManager;
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = BackupResponse.class, required = true,
            description = "ID of the logical Instance backup") private Long backupId;

    @Override public long getEntityOwnerId() { return 0; }

    @Override public void execute() {
        try {
            // Reuse the Root Admin and backup access checks without invoking staging management.
            BackupStagingInfoResponse response = backupManager.getBackupStagingInfo(backupId, "BACKUP", null);
            if (!"ablestack-netbackup".equals(response.provider)) {
                throw new IllegalArgumentException("Image information is available for ABLESTACK NetBackup backups");
            }
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (Exception e) { throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage()); }
    }
}
