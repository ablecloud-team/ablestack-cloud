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

    @SerializedName(ApiConstants.AUTOMATION_TEMPLATE_NAME)
    @Param(description = "the template's name associated with this Automation")
    private String automationTemplateName;

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

    @SerializedName(ApiConstants.IP_ADDRESS)
    @Param(description = "Public IP Address of the cluster")
    private String ipAddress;

    @SerializedName(ApiConstants.IP_ADDRESS_ID)
    @Param(description = "Public IP Address ID of the cluster")
    private String ipAddressId;

    @SerializedName("serviceofferingname")
    @Param(description = "the name of the service offering of the Automation Controller")
    private String serviceOfferingName;

    @SerializedName(ApiConstants.ACCOUNT)
    @Param(description = "the account associated with the Automation Controller")
    private String accountName;

    @SerializedName(ApiConstants.PROJECT_ID)
    @Param(description = "the project id of the Automation Controller")
    private String projectId;

    @SerializedName(ApiConstants.PROJECT)
    @Param(description = "the project name of the Automation Controller")
    private String projectName;

    @SerializedName("hostname")
    @Param(description = "the name of the host for the virtual machine")
    private String hostName;

    @SerializedName(ApiConstants.AUTOMATION_USER_VIRTUAL_MACHINES)
    @Param(description = "the list of virtualmachine associated with this Automation Controller")
    private List<UserVmResponse> automationControllerVms;

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

    public String getTemplateName() {
        return automationTemplateName;
    }

    public void setAutomationTemplateName(String automationTemplateName) {
        this.automationTemplateName = automationTemplateName;
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

    public String getAccountId(String accountId) { return accountId; }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getDomainId(String domainId) {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public String getServiceIp(String serviceIp) {
        return serviceIp;
    }

    public void setServiceIp(String serviceIp) {
        this.serviceIp = serviceIp;
    }
    public void getRemoved(Date removed) {
    }

    public String getIpAddress(String ipAddress) {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setIpAddressId(String ipAddressId) {
        this.ipAddressId = ipAddressId;
    }

    public void setServiceOfferingName(String serviceOfferingName) {
        this.serviceOfferingName = serviceOfferingName;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public List<UserVmResponse> getAutomationControllerVms() { return automationControllerVms; }

    public void setAutomationControllerVms(List<UserVmResponse> automationControllerVms) {
        this.automationControllerVms = automationControllerVms;
    }

}
