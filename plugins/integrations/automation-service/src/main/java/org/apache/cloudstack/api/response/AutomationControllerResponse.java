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

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import com.cloud.automation.controller.AutomationController;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
@EntityReference(value = {AutomationController.class})
public class AutomationControllerResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the id of the Automation Controller")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "Name of the Automation Controller")
    private String name;

    @SerializedName(ApiConstants.DESCRIPTION)
    @Param(description = "the description of the Automation Controller")
    private String description;

    @SerializedName(ApiConstants.AUTOMATION_TEMPLATE_ID)
    @Param(description = "the template's id associated with this Automation")
    private String automationTemplateId;

    @SerializedName(ApiConstants.SERVICE_OFFERING_ID)
    @Param(description = "the service offering's id associated with this Automation")
    private String serviceOfferingId;

    @SerializedName(ApiConstants.INSTANCE)
    @Param(description = "the instance associated with this Automation")
    private String instanceId;

    @SerializedName(ApiConstants.NETWORK_ID)
    @Param(description = "the network's id associated with this Automation")
    private String networkId;

    @SerializedName(ApiConstants.ACCOUNT_ID)
    @Param(description = "the account's id associated with this Automation")
    private String accountId;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "the domain's id associated with this Automation")
    private String domainId;

    @SerializedName(ApiConstants.SERVICE_IP)
    @Param(description = "the service ip address associated with this Automation")
    private String serviceIp;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "the id of the zone in which Automation Controller is available")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "the name of the zone in which Automation Controller is available")
    private String zoneName;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "the enabled or disabled state of the Automation Controller")
    private String state;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "the date this template was created")
    private Date created;

    @SerializedName(ApiConstants.REMOVED)
    @Param(description = "the date this template was created")
    private Date removed;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public String getAutomationTemplateId() {
        return automationTemplateId;
    }

    public void setAutomationTemplateId(String automationTemplateId) {
        this.automationTemplateId = automationTemplateId;
    }

    public String getServiceOfferingId() {
        return serviceOfferingId;
    }

    public void setServiceOfferingId(String serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public String getAccountId(long accountId) {
        return this.accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getDomainId(long domainId) {
        return this.domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getServiceIp(String serviceIp) {
        return this.serviceIp;
    }

    public void setServiceIp(String serviceIp) {
        this.serviceIp = serviceIp;
    }

    public void getRemoved(Date removed) {
    }
}
