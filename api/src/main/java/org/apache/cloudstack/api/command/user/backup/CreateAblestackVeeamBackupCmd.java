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
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCreateCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = "createAblestackVeeamBackup",
        description = "Create a backup for a VM assigned to the ablestack-veeam offering "
                + "(typically after a Veeam job completes)",
        responseObject = SuccessResponse.class,
        since = "4.22.0.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin})
public class CreateAblestackVeeamBackupCmd extends BaseAsyncCreateCmd {

    @Inject
    private BackupManager backupManager;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "KVM instance ID")
    private Long vmId;

    @Parameter(name = ApiConstants.QUIESCE_VM,
            type = CommandType.BOOLEAN,
            required = false,
            description = "Quiesce VM via QEMU guest agent before backup")
    private Boolean quiesceVM;

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            description = "the name of the backup (default: VM hostname + ISO-8601 timestamp)",
            since = "4.22.0.0")
    private String name;

    @Parameter(name = ApiConstants.INTERVAL_TYPE,
            type = CommandType.STRING,
            required = false,
            description = "Backup interval type shown in Mold UI for Veeam-triggered backups. "
                    + "Valid values: EXTERNAL, MANUAL, HOURLY, DAILY, WEEKLY, MONTHLY. "
                    + "Veeam-server backups default to EXTERNAL.",
            since = "4.22.0.0")
    private String intervalType;

    @Parameter(name = ApiConstants.VEEAM_JOB_NAME,
            type = CommandType.STRING,
            required = false,
            description = "Veeam backup job name that created this backup. "
                    + "When that job is deleted on the Veeam server, Mold removes matching backup rows.",
            since = "4.22.0.0")
    private String veeamJobName;

    public Long getVmId() {
        return vmId;
    }

    public Boolean getQuiesceVM() {
        return quiesceVM;
    }

    public String getName() {
        return name;
    }

    public String getIntervalType() {
        return intervalType;
    }

    public String getVeeamJobName() {
        return veeamJobName;
    }

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException,
            ConcurrentOperationException, ResourceAllocationException, NetworkRuleConflictException {
        try {
            boolean result = backupManager.createAblestackVeeamBackup(this, getJob());
            if (result) {
                SuccessResponse response = new SuccessResponse(getCommandName());
                response.setResponseName(getCommandName());
                setResponseObject(response);
            } else {
                throw new CloudRuntimeException("Failed to create Ablestack Veeam backup");
            }
        } catch (Exception e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.Backup;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_VM_BACKUP_CREATE;
    }

    @Override
    public String getEventDescription() {
        return "Creating Ablestack Veeam backup for Instance " + getResourceUuid(ApiConstants.VIRTUAL_MACHINE_ID);
    }

    @Override
    public void create() throws ResourceAllocationException {
    }

    @Override
    public Long getEntityId() {
        return vmId;
    }
}
