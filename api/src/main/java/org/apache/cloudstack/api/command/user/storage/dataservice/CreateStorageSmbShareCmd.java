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

package org.apache.cloudstack.api.command.user.storage.dataservice;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageSmbShareResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "createStorageSmbShare",
        responseObject = StorageSmbShareResponse.class,
        description = "Creates an SMB share on a Storage Service instance.",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CreateStorageSmbShareCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true, description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = true, description = "SMB share name")
    private String name;

    @Parameter(name = ApiConstants.PATH, type = CommandType.STRING, description = "SMB share path inside the Storage Service System VM")
    private String path;

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class, description = "backing volume ID")
    private Long volumeId;

    @Parameter(name = ApiConstants.FILESYSTEM, type = CommandType.STRING, description = "filesystem type")
    private String filesystem;

    @Parameter(name = "importmode", type = CommandType.STRING, description = "backing volume import mode: MOUNT_EXISTING, FORMAT_EMPTY, FORMAT_IF_EMPTY, or INSPECT_ONLY")
    private String importMode;

    @Parameter(name = "cleanupvolumeonfailure", type = CommandType.BOOLEAN, description = "delete the newly created backing volume if SMB share creation fails before it becomes usable")
    private Boolean cleanupVolumeOnFailure;

    @Parameter(name = "quotabytes", type = CommandType.LONG, description = "share capacity limit in bytes")
    private Long quotaBytes;

    @Parameter(name = "readonly", type = CommandType.BOOLEAN, description = "share as read-only by default")
    private Boolean readOnly;

    @Parameter(name = "browseable", type = CommandType.BOOLEAN, description = "whether the SMB share is browseable")
    private Boolean browseable;

    @Parameter(name = "guestok", type = CommandType.BOOLEAN, description = "whether guest access is allowed")
    private Boolean guestOk;

    @Parameter(name = "createdirectory", type = CommandType.BOOLEAN, description = "create the SMB backing directory when it does not exist")
    private Boolean createDirectory;

    @Parameter(name = "crossprotocol", type = CommandType.BOOLEAN, description = "allow sharing an existing NFS backing directory with SMB")
    private Boolean crossProtocol;

    @Parameter(name = "directorymode", type = CommandType.STRING, description = "POSIX mode to apply to a new SMB backing directory")
    private String directoryMode;

    @Parameter(name = "aclprincipaltype", type = CommandType.STRING, description = "optional initial SMB ACL principal type: LOCAL_USER, LOCAL_GROUP, AD_USER, or AD_GROUP")
    private String aclPrincipalType;

    @Parameter(name = "aclprincipal", type = CommandType.STRING, description = "optional initial SMB ACL user or group name")
    private String aclPrincipal;

    @Parameter(name = "aclpermission", type = CommandType.STRING, description = "optional initial SMB ACL permission: READ_ONLY, READ_WRITE, or ADMIN")
    private String aclPermission;

    @Parameter(name = "aclpassword", type = CommandType.STRING, description = "optional initial local SMB user password. Used only for LOCAL_USER creation and not stored.")
    private String aclPassword;

    public Long getInstanceId() {
        return instanceId;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public String getFilesystem() {
        return filesystem;
    }

    public String getImportMode() {
        return importMode;
    }

    public Boolean getCleanupVolumeOnFailure() {
        return cleanupVolumeOnFailure;
    }

    public Long getQuotaBytes() {
        return quotaBytes;
    }

    public Boolean getReadOnly() {
        return readOnly;
    }

    public Boolean getBrowseable() {
        return browseable;
    }

    public Boolean getGuestOk() {
        return guestOk;
    }

    public Boolean getCreateDirectory() {
        return createDirectory;
    }

    public Boolean getCrossProtocol() {
        return crossProtocol;
    }

    public String getDirectoryMode() {
        return directoryMode;
    }

    public String getAclPrincipalType() {
        return aclPrincipalType;
    }

    public String getAclPrincipal() {
        return aclPrincipal;
    }

    public String getAclPermission() {
        return aclPermission;
    }

    public String getAclPassword() {
        return aclPassword;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.SMB.SHARE.CREATE";
    }

    @Override
    public String getEventDescription() {
        return "Creating Storage Service SMB share " + name;
    }

    @Override
    public void execute() {
        StorageSmbShareResponse response = storageService.createStorageSmbShare(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create SMB share");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
