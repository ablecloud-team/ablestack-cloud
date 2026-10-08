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
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

public abstract class BaseStoragePosixDirectoryPolicyCmd extends BaseStorageServiceAsyncCmd implements UserCmd {
    @Inject private StorageService storageService;
    @Parameter(name = "id", type = CommandType.UUID, entityType = StoragePosixDirectoryPolicyResponse.class, description = "Directory policy ID")
    private Long id;
    @Parameter(name = "instanceid", type = CommandType.UUID, entityType = StorageServiceInstanceResponse.class, description = "Storage Service instance ID")
    private Long instanceId;
    @Parameter(name = "volumeid", type = CommandType.UUID, entityType = VolumeResponse.class, description = "Backing volume ID")
    private Long volumeId;
    @Parameter(name = "relativepath", type = CommandType.STRING, description = "Volume-relative directory path")
    private String relativePath;
    @Parameter(name = "owneruid", type = CommandType.LONG, description = "Desired numeric owner UID")
    private Long ownerUid;
    @Parameter(name = "ownergid", type = CommandType.LONG, description = "Desired numeric owner GID")
    private Long ownerGid;
    @Parameter(name = "directorymode", type = CommandType.STRING, description = "Octal directory mode, including optional setgid")
    private String directoryMode;
    @Parameter(name = "applyowner", type = CommandType.BOOLEAN, description = "Explicitly change the current directory owner only")
    private Boolean applyOwner;
    @Parameter(name = "recursive", type = CommandType.BOOLEAN, description = "Recursive changes are unsupported and must be false")
    private Boolean recursive;
    @Parameter(name = "accessentries", type = CommandType.STRING, description = "Structured JSON array of access POSIX ACL entries")
    private String accessEntries;
    @Parameter(name = "defaultentries", type = CommandType.STRING, description = "Structured JSON array of default POSIX ACL entries")
    private String defaultEntries;
    @Parameter(name = "preview", type = CommandType.BOOLEAN, description = "Read-only impact and current stat/ACL preview")
    private Boolean preview;
    @Parameter(name = "expectedpolicyrevision", type = CommandType.LONG, description = "Expected current common directory policy revision")
    private Long expectedPolicyRevision;
    @Parameter(name = "exportid", type = CommandType.UUID, entityType = org.apache.cloudstack.api.response.StorageNfsExportResponse.class, description = "NFS export whose exact existing backing directory is previewed")
    private Long exportId;
    @Parameter(name = "previewtoken", type = CommandType.STRING, description = "Actor-bound permission preview approval token")
    private String previewToken;
    @Parameter(name = "applyconfirmation", type = CommandType.BOOLEAN, description = "Explicitly apply the previewed policy to this directory inode only")
    private Boolean applyConfirmation;
    @Parameter(name = "readonly", type = CommandType.BOOLEAN, description = "NFS recommendation context; read-only preserves owner and mode")
    private Boolean readOnly;
    @Parameter(name = "rootsquash", type = CommandType.BOOLEAN, description = "NFS root squash recommendation context")
    private Boolean rootSquash;
    @Parameter(name = "allsquash", type = CommandType.BOOLEAN, description = "NFS all squash recommendation context")
    private Boolean allSquash;
    @Parameter(name = "anonuid", type = CommandType.LONG, description = "NFS anonymous UID recommendation context")
    private Long anonUid;
    @Parameter(name = "anongid", type = CommandType.LONG, description = "NFS anonymous GID recommendation context")
    private Long anonGid;
    public Long getExportId() { return exportId; }
    public String getPreviewToken() { return previewToken; }
    public Boolean getApplyConfirmation() { return applyConfirmation; }
    public Boolean getReadOnly() { return readOnly; }
    public Boolean getRootSquash() { return rootSquash; }
    public Boolean getAllSquash() { return allSquash; }
    public Long getAnonUid() { return anonUid; }
    public Long getAnonGid() { return anonGid; }
    public Long getId() { return id; }
    public Long getInstanceId() { return instanceId; }
    public Long getVolumeId() { return volumeId; }
    public String getRelativePath() { return relativePath; }
    public Long getOwnerUid() { return ownerUid; }
    public Long getOwnerGid() { return ownerGid; }
    public String getDirectoryMode() { return directoryMode; }
    public Boolean getApplyOwner() { return applyOwner; }
    public Boolean getRecursive() { return recursive; }
    public String getAccessEntries() { return accessEntries; }
    public String getDefaultEntries() { return defaultEntries; }
    public Boolean getPreview() { return preview; }
    public Long getExpectedPolicyRevision() { return expectedPolicyRevision; }
    public abstract String getPolicyAction();
    public long getEntityOwnerId() { return org.apache.cloudstack.context.CallContext.current().getCallingAccountId(); }
    public String getEventType() { return "STORAGE.POSIX.DIRECTORY." + getPolicyAction(); }
    public String getEventDescription() { return "Managing protocol-neutral Storage Service POSIX directory policy"; }
    public void execute() {
        StoragePosixDirectoryPolicyResponse response = storageService.executeStoragePosixDirectoryPolicy(this);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}
