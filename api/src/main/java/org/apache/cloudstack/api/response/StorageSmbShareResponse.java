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

package org.apache.cloudstack.api.response;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.storage.dataservice.StorageFileShare;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = StorageFileShare.class)
public class StorageSmbShareResponse extends BaseResponse {
    @SerializedName("networkaccessmode")
    @Param(description="Source policy, ANY_SOURCE or ALLOW_LIST, independent of account ACLs")
    private String networkAccessMode;

    @SerializedName("allowedsources")
    @Param(description="Canonical client source IP/CIDR allow-list")
    private java.util.List<String> allowedSources;

    @SerializedName("runtimestate") @Param(description="Fresh owned SMB listener state") private String runtimeState;
    @SerializedName("listenerobservations") @Param(description="Fresh per-IP and port owned SMB listeners") private String listenerObservations;
    @SerializedName("listenerscope") @Param(description="SMB shares are exposed through all enabled service listeners") private String listenerScope;
    public void setListenerScope(String value){listenerScope=value;}
    public void setRuntimeState(String value){runtimeState=value;}
    public void setListenerObservations(String value){listenerObservations=value;}

    public void setNetworkAccessMode(String value) { networkAccessMode=value; }
    public void setAllowedSources(java.util.List<String> value) { allowedSources=value; }

    @SerializedName("creationpolicy")
    @Param(description = "Desired SMB new file and directory permission policy")
    private String creationPolicy;

    @SerializedName("effectivecreationpolicy")
    @Param(description = "Observed SMB creation permission policy; absent when unobserved")
    private String effectiveCreationPolicy;

    @SerializedName("creationpolicydrift")
    @Param(description = "CONSISTENT, DRIFT or UNOBSERVED creation policy state")
    private String creationPolicyDrift;

    public void setCreationPolicy(String value) { creationPolicy = value; }
    public void setEffectiveCreationPolicy(String value) { effectiveCreationPolicy = value; }
    public void setCreationPolicyDrift(String value) { creationPolicyDrift = value; }

    @SerializedName("posixpolicyid")
    @Param(description = "Common protocol-neutral POSIX directory policy UUID")
    private String posixPolicyId;
    public void setPosixPolicyId(String value) { posixPolicyId = value; }

    @SerializedName("ownershipinheritance")
    @Param(description = "Requested new-object owner inheritance mode")
    private String ownershipInheritance;
    @SerializedName("effectiveownershipinheritance")
    @Param(description = "Observed Samba inheritance mode")
    private String effectiveOwnershipInheritance;
    @SerializedName("inheritgroup")
    @Param(description = "Requested parent group inheritance via setgid")
    private Boolean inheritGroup;
    @SerializedName("effectiveowneruid")
    @Param(description = "Observed current parent owner UID")
    private Long effectiveOwnerUid;
    @SerializedName("effectiveownergid")
    @Param(description = "Observed current parent owner GID")
    private Long effectiveOwnerGid;
    @SerializedName("effectivedirectorymode")
    @Param(description = "Observed current directory mode including setgid")
    private String effectiveDirectoryMode;
    public void setOwnershipInheritance(String value) { ownershipInheritance = value; }
    public void setEffectiveOwnershipInheritance(String value) { effectiveOwnershipInheritance = value; }
    public void setInheritGroup(Boolean value) { inheritGroup = value; }
    public void setEffectiveOwnerUid(Long value) { effectiveOwnerUid = value; }
    public void setEffectiveOwnerGid(Long value) { effectiveOwnerGid = value; }
    public void setEffectiveDirectoryMode(String value) { effectiveDirectoryMode = value; }

    @SerializedName("posixownershipmode")
    @Param(description = "Requested authenticated or forced POSIX file-operation identity")
    private String posixOwnershipMode;
    @SerializedName("effectiveposixownershipmode")
    @Param(description = "Observed POSIX file-operation identity mode")
    private String effectivePosixOwnershipMode;
    @SerializedName("managedposixuser")
    @Param(description = "Managed non-login UNIX identity; never a Samba authentication account")
    private String managedPosixUser;
    @SerializedName("managedposixgroup")
    @Param(description = "Managed UNIX group for fixed file-operation identity")
    private String managedPosixGroup;
    public void setPosixOwnershipMode(String value) { posixOwnershipMode = value; }
    public void setEffectivePosixOwnershipMode(String value) { effectivePosixOwnershipMode = value; }
    public void setManagedPosixUser(String value) { managedPosixUser = value; }
    public void setManagedPosixGroup(String value) { managedPosixGroup = value; }

    @SerializedName(ApiConstants.ID)
    @Param(description = "ID of the SMB share")
    private String id;

    @SerializedName("instanceid")
    @Param(description = "Storage Service instance ID")
    private String instanceId;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "SMB share name")
    private String name;

    @SerializedName(ApiConstants.PATH)
    @Param(description = "SMB share path inside the Storage Service System VM")
    private String path;

    @SerializedName(ApiConstants.VOLUME_ID)
    @Param(description = "backing volume ID")
    private String volumeId;

    @SerializedName("volumeuuid")
    @Param(description = "stable ABLESTACK backing volume UUID")
    private String volumeUuid;

    @SerializedName("filesystemuuid")
    @Param(description = "stable backing filesystem UUID")
    private String filesystemUuid;

    @SerializedName("volumemountpath")
    @Param(description = "managed backing volume mount path")
    private String volumeMountPath;

    @SerializedName("runtimedevicepath")
    @Param(description = "guest device path observed during the current System VM boot")
    private String runtimeDevicePath;

    @SerializedName("runtimeobservedat")
    @Param(description = "time when the runtime volume mapping was observed")
    private String runtimeObservedAt;

    @SerializedName("runtimebootid")
    @Param(description = "System VM boot ID associated with the runtime observation")
    private String runtimeBootId;

    @SerializedName("runtimematchedby")
    @Param(description = "stable identity used to resolve the runtime device")
    private String runtimeMatchedBy;

    @SerializedName("mappingstatus")
    @Param(description = "runtime backing volume mapping status")
    private String mappingStatus;

    @SerializedName(ApiConstants.FILESYSTEM)
    @Param(description = "filesystem type")
    private String filesystem;

    @SerializedName("quotabytes")
    @Param(description = "share capacity limit in bytes")
    private Long quotaBytes;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "SMB share state")
    private String state;

    @SerializedName("config")
    @Param(description = "SMB share configuration")
    private String config;

    @SerializedName("volumerelativepath")
    @Param(description = "directory relative to the managed backing volume root")
    private String volumeRelativePath;

    @SerializedName("backingpath")
    @Param(description = "physical backing directory resolved by the Storage Service runtime")
    private String backingPath;

    public void setVolumeRelativePath(final String value) { this.volumeRelativePath = value; }
    public void setBackingPath(final String value) { this.backingPath = value; }

    public void setId(String id) {
        this.id = id;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setVolumeId(String volumeId) {
        this.volumeId = volumeId;
    }

    public void setVolumeUuid(final String volumeUuid) { this.volumeUuid = volumeUuid; }
    public void setFilesystemUuid(final String filesystemUuid) { this.filesystemUuid = filesystemUuid; }
    public void setVolumeMountPath(final String volumeMountPath) { this.volumeMountPath = volumeMountPath; }
    public void setRuntimeDevicePath(final String runtimeDevicePath) { this.runtimeDevicePath = runtimeDevicePath; }
    public void setRuntimeObservedAt(final String runtimeObservedAt) { this.runtimeObservedAt = runtimeObservedAt; }
    public void setRuntimeBootId(final String runtimeBootId) { this.runtimeBootId = runtimeBootId; }
    public void setRuntimeMatchedBy(final String runtimeMatchedBy) { this.runtimeMatchedBy = runtimeMatchedBy; }
    public void setMappingStatus(final String mappingStatus) { this.mappingStatus = mappingStatus; }

    public void setFilesystem(String filesystem) {
        this.filesystem = filesystem;
    }

    public void setQuotaBytes(Long quotaBytes) {
        this.quotaBytes = quotaBytes;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setConfig(String config) {
        this.config = config;
    }
}
