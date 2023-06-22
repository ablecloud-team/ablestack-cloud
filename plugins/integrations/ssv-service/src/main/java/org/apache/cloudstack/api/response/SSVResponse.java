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

import java.util.Date;
import java.util.List;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import com.cloud.ssv.SSV;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
@EntityReference(value = {SSV.class})
public class SSVResponse extends BaseResponse implements ControlledEntityResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the id of the Shared Storage VM")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "the name of the Shared Storage VM")
    private String name;

    @SerializedName(ApiConstants.DESCRIPTION)
    @Param(description = "the description of the Shared Storage VM")
    private String description;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "the name of the zone of the Shared Storage VM")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "the name of the zone of the Shared Storage VM")
    private String zoneName;

    @SerializedName(ApiConstants.SERVICE_OFFERING_ID)
    @Param(description = "the ID of the service offering of the Shared Storage VM")
    private String serviceOfferingId;

    @SerializedName(ApiConstants.SHARED_STORAGE_VM_TYPE)
    @Param(description = "the TYPE of the service offering of the Shared Storage VM")
    private String sharedStorageVmType;

    @SerializedName("serviceofferingname")
    @Param(description = "the name of the service offering of the Shared Storage VM")
    private String serviceOfferingName;

    @SerializedName(ApiConstants.ACCOUNT)
    @Param(description = "the account associated with the Shared Storage VM")
    private String accountName;

    @SerializedName(ApiConstants.PROJECT_ID)
    @Param(description = "the project id of the Shared Storage VM")
    private String projectId;

    @SerializedName(ApiConstants.PROJECT)
    @Param(description = "the project name of the Shared Storage VM")
    private String projectName;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "the ID of the domain in which the Shared Storage VM exists")
    private String domainId;

    @SerializedName(ApiConstants.DOMAIN)
    @Param(description = "the name of the domain in which the Shared Storage VM exists")
    private String domainName;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "the state of the Shared Storage VM")
    private String state;

    @SerializedName(ApiConstants.SHARED_STORAGE_VM)
    @Param(description = "the list of virtualmachine associated with this Shared Storage VM")
    private List<UserVmResponse> ssv;

    @SerializedName(ApiConstants.NETWORK)
    @Param(description = "the network list of virtualmachine associated with this Shared Storage VM")
    private List<SSVNetResponse> networks;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "the date this template was created")
    private Date created;

    public SSVResponse() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public String getSharedStorageVmType() {
        return sharedStorageVmType;
    }

    public void setSharedStorageVmType(String sharedStorageVmType) {
        this.sharedStorageVmType = sharedStorageVmType;
    }

    public String getServiceOfferingId() {
        return serviceOfferingId;
    }

    public void setServiceOfferingId(String serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }


    public String getProjectId() {
        return projectId;
    }

    @Override
    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    @Override
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    @Override
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    @Override
    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    @Override
    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServiceOfferingName() {
        return serviceOfferingName;
    }

    public void setServiceOfferingName(String serviceOfferingName) {
        this.serviceOfferingName = serviceOfferingName;
    }

    public List<UserVmResponse> getSsv() {
        return ssv;
    }

    public void setSsv(List<UserVmResponse> ssv) {
        this.ssv = ssv;
    }

    public void setNetworks(List<SSVNetResponse> networks) {
        this.networks = networks;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
