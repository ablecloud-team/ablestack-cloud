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
import org.apache.cloudstack.api.response.BackupResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;

import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;

@APICommand(name = "importAblestackVeeamBackupSeed",
        description = "Import a Veeam restore point as a NAS backup seed for Ablestack Veeam incremental KVM backups",
        responseObject = BackupResponse.class,
        since = "4.22.0.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin})
public class ImportAblestackVeeamBackupSeedCmd extends BaseAsyncCreateCmd {

    @Inject
    private BackupManager backupManager;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true,
            description = "KVM instance to register the seed backup for")
    private Long vmId;

    @Parameter(name = ApiConstants.VEEAM_RESTORE_POINT_ID,
            type = CommandType.STRING,
            required = false,
            description = "Veeam restore point ID. If omitted, stagingdiskpaths must be provided.")
    private String veeamRestorePointId;

    @Parameter(name = ApiConstants.STAGING_DISK_PATHS,
            type = CommandType.STRING,
            required = false,
            description = "Comma-separated disk file paths on the KVM host (from shared staging). "
                    + "If omitted, disks are exported from Veeam using veeamrestorepointid.")
    private String stagingDiskPaths;

    @Parameter(name = ApiConstants.SOURCE_DISK_FORMAT,
            type = CommandType.STRING,
            required = false,
            description = "Staging disk format: vmdk, flat, qcow2, or raw. Default: vmdk")
    private String sourceDiskFormat;

    @Parameter(name = ApiConstants.BOOTSTRAP_CHECKPOINT,
            type = CommandType.BOOLEAN,
            required = false,
            description = "Create libvirt checkpoint on the KVM VM after import. Default: true")
    private Boolean bootstrapCheckpoint;

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            description = "the name of the backup (default: VM hostname + ISO-8601 timestamp)",
            since = "4.22.0.0")
    private String name;

    public Long getVmId() {
        return vmId;
    }

    public String getVeeamRestorePointId() {
        return veeamRestorePointId;
    }

    public String getStagingDiskPaths() {
        return stagingDiskPaths;
    }

    public String getSourceDiskFormat() {
        return sourceDiskFormat;
    }

    public Boolean getBootstrapCheckpoint() {
        return bootstrapCheckpoint;
    }

    public String getName() {
        return name;
    }

    @Override
    public void execute() throws ResourceUnavailableException, InsufficientCapacityException, ServerApiException,
            ConcurrentOperationException, ResourceAllocationException, NetworkRuleConflictException {
        try {
            Backup backup = backupManager.importAblestackVeeamBackupSeed(this);
            BackupResponse response = backupManager.createBackupResponse(backup, false);
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

    @Override
    public ApiCommandResourceType getApiResourceType() {
        return ApiCommandResourceType.Backup;
    }

    @Override
    public String getEventType() {
        return EventTypes.EVENT_VM_BACKUP_CREATE;
    }

    @Override
    public String getEventDescription() {
        return "Importing Ablestack Veeam backup seed for Instance " + getResourceUuid(ApiConstants.VIRTUAL_MACHINE_ID);
    }

    @Override
    public void create() throws ResourceAllocationException {
    }

    @Override
    public Long getEntityId() {
        return vmId;
    }
}
