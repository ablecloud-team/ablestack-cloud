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
import org.apache.cloudstack.api.response.BackupJobStatusResponse;
import org.apache.cloudstack.api.response.BackupResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = "getBackupRestoreJobStatus",
        description = "Gets host-side status and progress for a running Instance backup restore",
        responseObject = BackupJobStatusResponse.class, since = "4.23.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class GetBackupRestoreJobStatusCmd extends BaseCmd {

    @Inject
    private BackupManager backupManager;

    @Parameter(name = ApiConstants.ID,
            type = CommandType.UUID,
            entityType = BackupResponse.class,
            required = true,
            description = "ID of the Instance backup being restored")
    private Long backupId;

    @Parameter(name = ApiConstants.BACKUP_JOB_EVENTS_OFFSET,
            type = CommandType.LONG,
            description = "events offset returned by a previous getBackupRestoreJobStatus response")
    private Long eventsOffset;

    @Parameter(name = ApiConstants.LIMIT,
            type = CommandType.INTEGER,
            description = "maximum number of restore job events to return")
    private Integer eventsLimit;

    public Long getId() {
        return backupId;
    }

    public Long getEventsOffset() {
        return eventsOffset;
    }

    public Integer getEventsLimit() {
        return eventsLimit;
    }

    @Override
    public void execute() throws ServerApiException {
        try {
            final BackupJobStatusResponse response = backupManager.getBackupRestoreJobStatus(getId(), getEventsOffset(), getEventsLimit());
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
