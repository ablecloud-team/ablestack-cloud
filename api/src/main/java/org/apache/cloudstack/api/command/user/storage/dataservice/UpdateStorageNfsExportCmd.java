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
import org.apache.cloudstack.api.response.StorageNfsExportResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "updateStorageNfsExport",
        responseObject = StorageNfsExportResponse.class,
        description = "Updates an NFS export.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateStorageNfsExportCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StorageNfsExportResponse.class, required = true, description = "NFS export ID")
    private Long id;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, description = "NFS export name")
    private String name;

    @Parameter(name = ApiConstants.PATH, type = CommandType.STRING, description = "NFS export path")
    private String path;

    @Parameter(name = "relativepath", type = CommandType.STRING,
            description = "relative directory inside the selected backing volume to expose. Used when importing or reusing a backing volume.")
    private String relativePath;

    @Parameter(name = "createdirectory", type = CommandType.BOOLEAN,
            description = "create the relative directory inside the backing volume when it does not already exist")
    private Boolean createDirectory;

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class, description = "backing volume ID")
    private Long volumeId;

    @Parameter(name = ApiConstants.FILESYSTEM, type = CommandType.STRING, description = "filesystem type")
    private String filesystem;

    @Parameter(name = "importmode", type = CommandType.STRING, description = "backing volume import mode: MOUNT_EXISTING, FORMAT_EMPTY, or INSPECT_ONLY")
    private String importMode;

    @Parameter(name = "quotabytes", type = CommandType.LONG, description = "export capacity limit in bytes")
    private Long quotaBytes;

    @Parameter(name = "readonly", type = CommandType.BOOLEAN, description = "export as read-only by default")
    private Boolean readOnly;

    @Parameter(name = "rootsquash", type = CommandType.BOOLEAN, description = "enable root squash")
    private Boolean rootSquash;

    @Parameter(name = "allsquash", type = CommandType.BOOLEAN, description = "map all client users to the anonymous NFS user")
    private Boolean allSquash;

    @Parameter(name = "anonuid", type = CommandType.INTEGER, description = "anonymous UID used by root/all squash")
    private Integer anonUid;

    @Parameter(name = "anongid", type = CommandType.INTEGER, description = "anonymous GID used by root/all squash")
    private Integer anonGid;

    @Parameter(name = "owneruid", type = CommandType.INTEGER, description = "POSIX owner UID applied to the export backing directory")
    private Integer ownerUid;

    @Parameter(name = "ownergid", type = CommandType.INTEGER, description = "POSIX owner GID applied to the export backing directory")
    private Integer ownerGid;

    @Parameter(name = "mode", type = CommandType.STRING, description = "POSIX mode applied to the export backing directory, for example 0775")
    private String mode;

    @Parameter(name = "recursivepermission", type = CommandType.BOOLEAN, description = "apply POSIX owner and mode recursively")
    private Boolean recursivePermission;

    @Parameter(name = "sync", type = CommandType.BOOLEAN, description = "use sync export option")
    private Boolean sync;

    @Parameter(name = "secure", type = CommandType.BOOLEAN, description = "use secure export option")
    private Boolean secure;

    @Parameter(name = "listenips", type = CommandType.STRING, description = "comma separated listen IPs that should expose this NFS export")
    private String listenIps;

    @Parameter(name = "listenerports", type = CommandType.STRING,
            description = "comma separated NFS listener group ports that should expose this NFS export. NFSv4-only exports are separated by listener group port.")
    private String listenerPorts;

    @Parameter(name = "endpointmode", type = CommandType.STRING, description = "NFS export endpoint exposure mode: ALL or SELECTED")
    private String endpointMode;

    @Parameter(name = "protocolmode", type = CommandType.STRING, description = "NFS protocol mode: V4_ONLY or V3V4_DUAL")
    private String protocolMode;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public Boolean getCreateDirectory() {
        return createDirectory;
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

    public Long getQuotaBytes() {
        return quotaBytes;
    }

    public Boolean getReadOnly() {
        return readOnly;
    }

    public Boolean getRootSquash() {
        return rootSquash;
    }

    public Boolean getAllSquash() {
        return allSquash;
    }

    public Integer getAnonUid() {
        return anonUid;
    }

    public Integer getAnonGid() {
        return anonGid;
    }

    public Integer getOwnerUid() {
        return ownerUid;
    }

    public Integer getOwnerGid() {
        return ownerGid;
    }

    public String getMode() {
        return mode;
    }

    public Boolean getRecursivePermission() {
        return recursivePermission;
    }

    public Boolean getSync() {
        return sync;
    }

    public Boolean getSecure() {
        return secure;
    }

    public String getListenIps() {
        return listenIps;
    }

    public String getListenerPorts() {
        return listenerPorts;
    }

    public String getEndpointMode() {
        return endpointMode;
    }

    public String getProtocolMode() {
        return protocolMode;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.NFS.EXPORT.UPDATE";
    }

    @Override
    public String getEventDescription() {
        return "Updating Storage Service NFS export " + id;
    }

    @Override
    public void execute() {
        StorageNfsExportResponse response = storageService.updateStorageNfsExport(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update NFS export");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
