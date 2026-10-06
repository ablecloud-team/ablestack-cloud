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
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;
import com.cloud.event.EventTypes;

@APICommand(name = "restoreBackupArtifact", description = "Claim an external QCOW2 metadata restore callback and restore its logical backup through Mold",
        responseObject = SuccessResponse.class, since = "4.23.0", authorized = {RoleType.Admin})
public class RestoreBackupArtifactCmd extends BaseAsyncCmd implements AdminCmd {
    @Inject private BackupManager backupManager;
    @Parameter(name = "provider", type = CommandType.STRING, required = true, description = "ablestack-netbackup or ablestack-veeam") private String provider;
    @Parameter(name = ApiConstants.EXTERNAL_ID, type = CommandType.STRING, required = true, description = "Exact metadata catalog artifact ID or original metadata selection") private String externalId;
    @Parameter(name = ApiConstants.JOB_ID, type = CommandType.STRING, required = true, description = "External metadata restore Job ID") private String jobId;

    @Override public long getEntityOwnerId() { return CallContext.current().getCallingAccount().getId(); }
    @Override public String getEventType() { return EventTypes.EVENT_VM_BACKUP_RESTORE; }
    @Override public String getEventDescription() { return "Restoring logical backup from " + provider + " metadata restore Job " + jobId; }
    @Override public void execute() {
        try {
            if (!backupManager.restoreBackupArtifact(provider, externalId, jobId)) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Logical backup restore was not submitted");
            }
            SuccessResponse response = new SuccessResponse(getCommandName());
            response.setResponseName(getCommandName());
            setResponseObject(response);
        } catch (Exception e) { throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage()); }
    }
}
