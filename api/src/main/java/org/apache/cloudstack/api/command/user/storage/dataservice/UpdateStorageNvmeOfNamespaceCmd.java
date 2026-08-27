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
import org.apache.cloudstack.api.response.StorageBlockTargetResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "updateStorageNvmeOfNamespace",
        responseObject = StorageBlockTargetResponse.class,
        description = "Updates an NVMe-oF namespace on a Storage Service instance.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateStorageNvmeOfNamespaceCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StorageBlockTargetResponse.class, required = true, description = "NVMe-oF namespace ID")
    private Long id;

    @Parameter(name = "namespaceid", type = CommandType.STRING, description = "namespace ID")
    private String namespaceId;

    @Parameter(name = "namespacesizebytes", type = CommandType.LONG, description = "namespace size in bytes")
    private Long namespaceSizeBytes;

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class, description = "backing volume ID")
    private Long volumeId;

    @Parameter(name = "backingpath", type = CommandType.STRING, description = "optional block device path inside the Storage Service System VM")
    private String backingPath;

    @Parameter(name = "listenerports", type = CommandType.STRING, description = "comma-separated NVMe-oF listener port groups for this namespace")
    private String listenerPorts;

    public Long getId() {
        return id;
    }

    public String getNamespaceId() {
        return namespaceId;
    }

    public Long getNamespaceSizeBytes() {
        return namespaceSizeBytes;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public String getBackingPath() {
        return backingPath;
    }

    public String getListenerPorts() {
        return listenerPorts;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.NVMEOF.NAMESPACE.UPDATE";
    }

    @Override
    public String getEventDescription() {
        return "Updating Storage Service NVMe-oF namespace " + id;
    }

    @Override
    public void execute() {
        StorageBlockTargetResponse response = storageService.updateStorageNvmeOfNamespace(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update NVMe-oF namespace");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
