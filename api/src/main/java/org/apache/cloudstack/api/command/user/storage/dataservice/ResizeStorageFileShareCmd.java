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
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "resizeStorageFileShare",
        responseObject = StorageFileShareResponse.class,
        description = "Expands a Storage Service file share by optionally resizing the backing volume and growing the guest filesystem.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ResizeStorageFileShareCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StorageFileShareResponse.class, required = true, description = "Storage Service file share ID")
    private Long id;

    @Parameter(name = ApiConstants.SIZE, type = CommandType.LONG, description = "new backing volume size in GB")
    private Long size;

    @Parameter(name = "quotabytes", type = CommandType.LONG, description = "new file share capacity limit in bytes")
    private Long quotaBytes;

    @Parameter(name = "resizevolume", type = CommandType.BOOLEAN, description = "resize the ABLESTACK backing volume before growing the filesystem")
    private Boolean resizeVolume;

    public Long getId() {
        return id;
    }

    public Long getSize() {
        return size;
    }

    public Long getQuotaBytes() {
        return quotaBytes;
    }

    public Boolean getResizeVolume() {
        return resizeVolume;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.FILESHARE.RESIZE";
    }

    @Override
    public String getEventDescription() {
        return "Resizing Storage Service file share " + id;
    }

    @Override
    public void execute() {
        StorageFileShareResponse response = storageService.resizeStorageFileShare(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to resize file share");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
