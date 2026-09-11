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

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.BackupResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;

import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = "updateAblestackVeeamBackup",
        description = "Stamp Veeam restore-point / job metadata onto an Ablestack Veeam Mold backup row "
                + "so catalog sync can delete Mold history when the restore point is removed in Veeam.",
        responseObject = SuccessResponse.class,
        since = "4.22.0.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin})
public class UpdateAblestackVeeamBackupCmd extends BaseCmd {

    @Inject
    private BackupManager backupManager;

    @Parameter(name = ApiConstants.ID,
            type = CommandType.UUID,
            entityType = BackupResponse.class,
            required = true,
            description = "Mold backup ID")
    private Long id;

    @Parameter(name = ApiConstants.VEEAM_RESTORE_POINT_ID,
            type = CommandType.STRING,
            required = false,
            description = "Veeam restore point GUID to associate with this Mold backup")
    private String veeamRestorePointId;

    @Parameter(name = ApiConstants.VEEAM_JOB_NAME,
            type = CommandType.STRING,
            required = false,
            description = "Veeam Agent / backup job name")
    private String veeamJobName;

    public Long getId() {
        return id;
    }

    public String getVeeamRestorePointId() {
        return veeamRestorePointId;
    }

    public String getVeeamJobName() {
        return veeamJobName;
    }

    @Override
    public void execute() {
        try {
            boolean result = backupManager.updateAblestackVeeamBackup(this);
            if (result) {
                SuccessResponse response = new SuccessResponse(getCommandName());
                response.setResponseName(getCommandName());
                setResponseObject(response);
            } else {
                throw new CloudRuntimeException("Failed to update Ablestack Veeam backup metadata");
            }
        } catch (Exception e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
