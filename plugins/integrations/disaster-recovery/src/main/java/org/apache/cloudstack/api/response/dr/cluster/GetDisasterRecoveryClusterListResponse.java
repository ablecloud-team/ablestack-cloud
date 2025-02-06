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

package org.apache.cloudstack.api.response.dr.cluster;

import com.cloud.dr.cluster.DisasterRecoveryCluster;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.UserVmResponse;

import java.util.Date;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
@EntityReference(value = {DisasterRecoveryCluster.class})
public class GetDisasterRecoveryClusterListResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the id of the disaster recovery cluster")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "the name of the disaster recovery cluster")
    private String name;

    @SerializedName(ApiConstants.DESCRIPTION)
    @Param(description = "the description of the disaster recovery cluster")
    private String description;

    @SerializedName(ApiConstants.DR_CLUSTER_URL)
    @Param(description = "the url of the disaster recovery cluster")
    private String drClusterUrl;

    @SerializedName(ApiConstants.DR_CLUSTER_TYPE)
    @Param(description = "the cluster type of the disaster recovery cluster")
    private String drClusterType;

    @SerializedName(ApiConstants.DR_CLUSTER_STATUS)
    @Param(description = "the dr cluster status of the disaster recovery cluster")
    private String drClusterStatus;

    @SerializedName(ApiConstants.MIRRORING_AGENT_STATUS)
    @Param(description = "the mirroring agent status of the disaster recovery cluster on the mshost")
    private String mirroringAgentStatus;

    @SerializedName(ApiConstants.DR_CLUSTER_GLUE_IP_ADDRESS)
    @Param(description = "the ip address of the disaster recovery cluster on the glue")
    private String drClusterGlueIpAddress;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "the creation date of the disaster recovery cluster")
    private Date created;

    @SerializedName(ApiConstants.REMOVED)
    @Param(description = "the remove date of the disaster recovery cluster")
    private Date removed;

    @SerializedName(ApiConstants.DETAILS)
    @Param(description = "Vm details in key/value pairs.", since = "4.2.1")
    private Map details;

    @SerializedName(ApiConstants.DISASTER_RECOVERY_CLUSTER_VM_LIST)
    @Param(description = "the list of virtualmachine associated with this disaster recovery cluster")
    private List<UserVmResponse> disasterRecoveryClusterVms;

    @SerializedName(ApiConstants.SERVICE_OFFERING_DETAILS)
    @Param(description = "the list of service offering associated with this second disaster recovery cluster")
    private List<ServiceOfferingResponse> secDisasterRecoveryClusterInfo;

    @SerializedName(ApiConstants.NETWORK)
    @Param(description = "the list of networks associated with this second disaster recovery cluster")
    private List<NetworkResponse> secDisasterRecoveryClusterNetworkList;

    @SerializedName(ApiConstants.DR_CLUSTER_VM_MAP)
    @Param(description = "this list of disaster recovery cluster vm map")
    private List<GetDisasterRecoveryClusterVmListResponse> disasterRecoveryClusterVmMap;


    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDrClusterUrl() {
        return drClusterUrl;
    }

    public String getDrClusterType() {
        return drClusterType;
    }

    public String getDrClusterStatus() {
        return drClusterStatus;
    }

    public String getMirroringAgentStatus() {
        return mirroringAgentStatus;
    }

    public String getDrClusterGlueIpAddress() {
        return drClusterGlueIpAddress;
    }

    public Map getDetails() {
        return details;
    }

    public Date getCreated() {
        return created;
    }

    public Date getRemoved() {
        return removed;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setDrClusterUrl(String drClusterUrl) {
        this.drClusterUrl = drClusterUrl;
    }

    public void setDrClusterType(String drClusterType) {
        this.drClusterType = drClusterType;
    }

    public void setDrClusterStatus(String drClusterStatus) {
        this.drClusterStatus = drClusterStatus;
    }

    public void setMirroringAgentStatus(String mirroringAgentStatus) {
        this.mirroringAgentStatus = mirroringAgentStatus;
    }

    public void setDrClusterGlueIpAddress(String drClusterGlueIpAddress) {
        this.drClusterGlueIpAddress = drClusterGlueIpAddress;
    }

    public void setDetails(Map details) {
        this.details = details;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public void setRemoved(Date removed) {
        this.removed = removed;
    }

    public List<UserVmResponse> getDisasterRecoveryClusterVms() {
        return this.disasterRecoveryClusterVms;
    }

    public void setDisasterRecoveryClusterVms(final List<UserVmResponse> disasterRecoveryClusterVms) {
        this.disasterRecoveryClusterVms = disasterRecoveryClusterVms;
    }

    public void setSecDisasterRecoveryClusterServiceOfferingList(final List<ServiceOfferingResponse> secDisasterRecoveryClusterInfo) {
        this.secDisasterRecoveryClusterInfo = secDisasterRecoveryClusterInfo;
    }

    public void setSecDisasterRecoveryClusterNetworkList(final List<NetworkResponse> secDisasterRecoveryClusterNetworkList) {
        this.secDisasterRecoveryClusterNetworkList = secDisasterRecoveryClusterNetworkList;
    }

    public List<GetDisasterRecoveryClusterVmListResponse> getDisasterRecoveryClusterVmMap() {
        return this.disasterRecoveryClusterVmMap;
    }

    public void setDisasterRecoveryClusterVmMap(final List<GetDisasterRecoveryClusterVmListResponse> disasterRecoveryClusterVmMap) {
        this.disasterRecoveryClusterVmMap = disasterRecoveryClusterVmMap;
    }
}