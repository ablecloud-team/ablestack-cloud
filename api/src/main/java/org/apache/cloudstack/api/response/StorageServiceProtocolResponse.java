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

import java.util.List;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class StorageServiceProtocolResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "ID of the Storage Service protocol")
    private String id;

    @SerializedName("instanceid")
    @Param(description = "Storage Service instance ID")
    private String instanceId;

    @SerializedName("protocol")
    @Param(description = "storage protocol")
    private String protocol;

    @SerializedName("enabled")
    @Param(description = "whether the protocol is enabled")
    private Boolean enabled;

    @SerializedName("listenip")
    @Param(description = "protocol listen IP")
    private String listenIp;

    @SerializedName("port")
    @Param(description = "protocol port")
    private Integer port;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "protocol state")
    private String state;

    @SerializedName("protocolmode")
    @Param(description = "NFS protocol mode when protocol is NFS")
    private String protocolMode;

    @SerializedName("config")
    @Param(description = "protocol configuration JSON")
    private String config;

    @SerializedName("listenertype")
    @Param(description = "listener type: WILDCARD or DEDICATED")
    private String listenerType;

    @SerializedName("primaryip")
    @Param(description = "primary IPv4 address of the Storage Service VM")
    private String primaryIp;

    @SerializedName("runtimeprimaryip")
    @Param(description = "primary IPv4 address observed inside the Storage Service VM")
    private String runtimePrimaryIp;

    @SerializedName("identitystatus")
    @Param(description = "NIC identity consistency status: CONSISTENT, DRIFT, or UNKNOWN")
    private String identityStatus;

    @SerializedName("identitywarning")
    @Param(description = "diagnostic warning when persisted and runtime NIC identities disagree")
    private String identityWarning;

    @SerializedName("effectiveendpoints")
    @Param(description = "effective IP and port combinations exposed by this listener")
    private List<StorageServiceProtocolEndpointResponse> effectiveEndpoints;

    @SerializedName("runtimestate")
    @Param(description = "normalized listener runtime state")
    private String runtimeState;

    @SerializedName("linkedresourcecount")
    @Param(description = "number of protocol resources linked to this listener port")
    private Integer linkedResourceCount;

    public void setId(String id) {
        this.id = id;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public void setListenIp(String listenIp) {
        this.listenIp = listenIp;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setProtocolMode(String protocolMode) {
        this.protocolMode = protocolMode;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public void setListenerType(final String listenerType) {
        this.listenerType = listenerType;
    }

    public void setPrimaryIp(final String primaryIp) {
        this.primaryIp = primaryIp;
    }

    public void setRuntimePrimaryIp(final String runtimePrimaryIp) {
        this.runtimePrimaryIp = runtimePrimaryIp;
    }

    public void setIdentityStatus(final String identityStatus) {
        this.identityStatus = identityStatus;
    }

    public void setIdentityWarning(final String identityWarning) {
        this.identityWarning = identityWarning;
    }

    public void setEffectiveEndpoints(final List<StorageServiceProtocolEndpointResponse> effectiveEndpoints) {
        this.effectiveEndpoints = effectiveEndpoints;
    }

    public void setRuntimeState(final String runtimeState) {
        this.runtimeState = runtimeState;
    }

    public void setLinkedResourceCount(final Integer linkedResourceCount) {
        this.linkedResourceCount = linkedResourceCount;
    }
}
