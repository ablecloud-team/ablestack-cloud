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
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageFileShareResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "attachStorageVolumeToFileShare",
        responseObject = StorageFileShareResponse.class,
        description = "Attaches an existing ABLESTACK volume to a Storage Service file share and inspects it through the Storage Service System VM.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class AttachStorageVolumeToFileShareCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StorageFileShareResponse.class, required = true, description = "Storage Service file share ID")
    private Long id;

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class, required = true, description = "existing ABLESTACK volume ID")
    private Long volumeId;

    @Parameter(name = ApiConstants.PATH, type = CommandType.STRING, description = "mount path inside the Storage Service System VM")
    private String path;

    @Parameter(name = ApiConstants.FILESYSTEM, type = CommandType.STRING, description = "expected filesystem type")
    private String filesystem;

    @Parameter(name = "importmode", type = CommandType.STRING, description = "existing volume import mode: MOUNT_EXISTING or INSPECT_ONLY")
    private String importMode;

    public Long getId() {
        return id;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public String getPath() {
        return path;
    }

    public String getFilesystem() {
        return filesystem;
    }

    public String getImportMode() {
        return importMode;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.FILESHARE.VOLUME.ATTACH";
    }

    @Override
    public String getEventDescription() {
        return "Attaching volume " + volumeId + " to Storage Service file share " + id;
    }

    @Override
    public void execute() {
        StorageFileShareResponse response = storageService.attachStorageVolumeToFileShare(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to attach volume to file share");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
