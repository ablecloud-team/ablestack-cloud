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
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "createStorageIscsiTarget",
        responseObject = StorageBlockTargetResponse.class,
        description = "Creates an iSCSI target and LUN on a Storage Service instance.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CreateStorageIscsiTargetCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, required = true, description = "Storage Service instance ID")
    private Long instanceId;

    @Parameter(name = "targetname", type = CommandType.STRING, required = true, description = "iSCSI target IQN")
    private String targetName;

    @Parameter(name = "lun", type = CommandType.STRING, description = "LUN ID")
    private String lun;

    @Parameter(name = "lunsizebytes", type = CommandType.LONG, description = "LUN size in bytes")
    private Long lunSizeBytes;

    @Parameter(name = ApiConstants.VOLUME_ID, type = CommandType.UUID, entityType = VolumeResponse.class, required = true, description = "backing volume ID")
    private Long volumeId;

    @Parameter(name = "backingpath", type = CommandType.STRING, description = "optional block device path inside the Storage Service System VM")
    private String backingPath;

    @Parameter(name = "backstoretype", type = CommandType.STRING, description = "legacy iSCSI LIO backstore type parameter. Only BLOCK is supported")
    private String backstoreType;

    @Parameter(name = "endpointmode", type = CommandType.STRING, description = "iSCSI target endpoint exposure mode: ALL or LISTENER_GROUP")
    private String endpointMode;

    @Parameter(name = "listenerports", type = CommandType.STRING, description = "comma-separated iSCSI listener port groups for this target")
    private String listenerPorts;

    @Parameter(name = "cleanupvolumeonfailure", type = CommandType.BOOLEAN, description = "cleanup newly created backing volume if target creation fails")
    private Boolean cleanupVolumeOnFailure;

    public Long getInstanceId() {
        return instanceId;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getLun() {
        return lun;
    }

    public Long getLunSizeBytes() {
        return lunSizeBytes;
    }

    public Long getVolumeId() {
        return volumeId;
    }

    public String getBackingPath() {
        return backingPath;
    }

    public String getBackstoreType() {
        return backstoreType;
    }

    public String getEndpointMode() {
        return endpointMode;
    }

    public String getListenerPorts() {
        return listenerPorts;
    }

    public Boolean getCleanupVolumeOnFailure() {
        return cleanupVolumeOnFailure;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.ISCSI.TARGET.CREATE";
    }

    @Override
    public String getEventDescription() {
        return "Creating Storage Service iSCSI target " + targetName;
    }

    @Override
    public void execute() {
        StorageBlockTargetResponse response = storageService.createStorageIscsiTarget(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create iSCSI target");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
