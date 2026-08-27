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
import org.apache.cloudstack.storage.dataservice.StorageServiceInstance;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = StorageServiceInstance.class)
public class StorageServiceInstanceResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "ID of the Storage Service instance")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "name of the Storage Service instance")
    private String name;

    @SerializedName(ApiConstants.DESCRIPTION)
    @Param(description = "description of the Storage Service instance")
    private String description;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "zone ID of the Storage Service instance")
    private String zoneId;

    @SerializedName(ApiConstants.VIRTUAL_MACHINE_ID)
    @Param(description = "System VM ID backing the Storage Service instance")
    private String virtualMachineId;

    @SerializedName(ApiConstants.SERVICE_OFFERING_ID)
    @Param(description = "service offering ID for the Storage Service System VM")
    private String serviceOfferingId;

    @SerializedName(ApiConstants.PROVIDER)
    @Param(description = "Storage Service provider")
    private String provider;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "Storage Service instance state")
    private String state;

    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public void setVirtualMachineId(String virtualMachineId) {
        this.virtualMachineId = virtualMachineId;
    }

    public void setServiceOfferingId(String serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setState(String state) {
        this.state = state;
    }
}
