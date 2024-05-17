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

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
public class CapabilitiesResponse extends BaseResponse {
    @SerializedName("securitygroupsenabled")
    @Param(description = "true if security groups support is enabled, false otherwise")
    private boolean securityGroupsEnabled;

    @SerializedName("dynamicrolesenabled")
    @Param(description = "true if dynamic role-based api checker is enabled, false otherwise")
    private boolean dynamicRolesEnabled;

    @SerializedName("cloudstackversion")
    @Param(description = "version of the cloud stack")
    private String cloudStackVersion;

    @SerializedName("userpublictemplateenabled")
    @Param(description = "true if user and domain admins can set templates to be shared, false otherwise")
    private boolean userPublicTemplateEnabled;

    @SerializedName("supportELB")
    @Param(description = "true if region supports elastic load balancer on basic zones")
    private String supportELB;

    @SerializedName(ApiConstants.PROJECT_INVITE_REQUIRED)
    @Param(description = "If invitation confirmation is required when add account to project")
    private Boolean projectInviteRequired;

    @SerializedName(ApiConstants.ALLOW_USER_CREATE_PROJECTS)
    @Param(description = "true if regular user is allowed to create projects")
    private Boolean allowUsersCreateProjects;

    @SerializedName(ApiConstants.CUSTOM_DISK_OFF_MIN_SIZE)
    @Param(description = "minimum size that can be specified when " + "create disk from disk offering with custom size")
    private Long diskOffMinSize;

    @SerializedName(ApiConstants.CUSTOM_DISK_OFF_MAX_SIZE)
    @Param(description = "maximum size that can be specified when " + "create disk from disk offering with custom size")
    private Long diskOffMaxSize;

    @SerializedName("regionsecondaryenabled")
    @Param(description = "true if region wide secondary is enabled, false otherwise")
    private boolean regionSecondaryEnabled;

    @SerializedName("apilimitinterval")
    @Param(description = "time interval (in seconds) to reset api count")
    private Integer apiLimitInterval;

    @SerializedName("kvmsnapshotenabled")
    @Param(description = "true if snapshot is supported for KVM host, false otherwise")
    private boolean kvmSnapshotEnabled;

    @SerializedName("apilimitmax")
    @Param(description = "Max allowed number of api requests within the specified interval")
    private Integer apiLimitMax;

    @SerializedName("allowuserviewdestroyedvm")
    @Param(description = "true if the user is allowed to view destroyed virtualmachines, false otherwise", since = "4.6.0")
    private boolean allowUserViewDestroyedVM;

    @SerializedName("allowuserexpungerecovervm")
    @Param(description = "true if the user can recover and expunge virtualmachines, false otherwise", since = "4.6.0")
    private boolean allowUserExpungeRecoverVM;

    @SerializedName("allowuserexpungerecovervolume")
    @Param(description = "true if the user can recover and expunge volumes, false otherwise", since = "4.14.0")
    private boolean allowUserExpungeRecoverVolume;

    @SerializedName("allowuserviewalldomainaccounts")
    @Param(description = "true if users can see all accounts within the same domain, false otherwise")
    private boolean allowUserViewAllDomainAccounts;

    @SerializedName("kubernetesserviceenabled")
    @Param(description = "true if Kubernetes Service plugin is enabled, false otherwise")
    private boolean kubernetesServiceEnabled;

    @SerializedName("kubernetesclusterexperimentalfeaturesenabled")
    @Param(description = "true if experimental features for Kubernetes cluster such as Docker private registry are enabled, false otherwise")
    private boolean kubernetesClusterExperimentalFeaturesEnabled;

    @SerializedName("customhypervisordisplayname")
    @Param(description = "Display name for custom hypervisor", since = "4.19.0")
    private String customHypervisorDisplayName;

    @SerializedName("desktopserviceenabled")
    @Param(description = "true if Desktop Service plugin is enabled, false otherwise")
    private boolean desktopServiceEnabled;

    @SerializedName("desktopworksportalport")
    @Param(description = "Desktop Service Works Portal URL setting")
    private String desktopWorksPortalPort;

    @SerializedName("automationserviceenabled")
    @Param(description = "true if Automation Service plugin is enabled, false otherwise")
    private boolean automationServiceEnabled;

    @SerializedName("wallportalprotocol")
    @Param(description = "Monitoring Service Wall Portal Protocol setting")
    private String wallPortalProtocol;

    @SerializedName("wallportaldomain")
    @Param(description = "Monitoring Service Wall Portal Domain setting")
    private String wallPortalDomain;

    @SerializedName("wallportalport")
    @Param(description = "Monitoring Service Wall Portal Port setting")
    private String wallPortalPort;

    @SerializedName("wallportalvmuri")
    @Param(description = "Monitoring Service Wall Portal for VM Uri setting")
    private String wallPortalVmUri;

    @SerializedName("securityfeaturesenabled")
    @Param(description = "A setting that enables/disables features developed for security features.")
    private boolean securityFeaturesEnabled;

    @SerializedName("host")
    @Param(description = "Management Server Host Ip")
    private String host;

    @SerializedName("defaultuipagesize")
    @Param(description = "default page size in the UI for various views, value set in the configurations", since = "4.15.2")
    private Long defaultUiPageSize;

    @SerializedName("balancingserviceenabled")
    @Param(description = "true if Balancing Service plugin is enabled, false otherwise")
    private boolean balancingServiceEnabled;

    @SerializedName("eventdeleteenabled")
    @Param(description = "true if Event Delete Button is enabled, false otherwise")
    private boolean eventDeleteEnabled;

    @SerializedName(ApiConstants.INSTANCES_STATS_RETENTION_TIME)
    @Param(description = "the retention time for Instances stats", since = "4.18.0")
    private Integer instancesStatsRetentionTime;

    @SerializedName(ApiConstants.INSTANCES_STATS_USER_ONLY)
    @Param(description = "true if stats are collected only for user instances, false if system instance stats are also collected", since = "4.18.0")
    private Boolean instancesStatsUserOnly;

    @SerializedName(ApiConstants.INSTANCES_DISKS_STATS_RETENTION_ENABLED)
    @Param(description = "true if stats are retained for instance disks otherwise false", since = "4.18.0")
    private Boolean instancesDisksStatsRetentionEnabled;

    @SerializedName(ApiConstants.INSTANCES_DISKS_STATS_RETENTION_TIME)
    @Param(description = "the retention time for Instances disks stats", since = "4.18.0")
    private Integer instancesDisksStatsRetentionTime;

    public void setSecurityGroupsEnabled(boolean securityGroupsEnabled) {
        this.securityGroupsEnabled = securityGroupsEnabled;
    }

    public void setDynamicRolesEnabled(boolean dynamicRolesEnabled) {
        this.dynamicRolesEnabled = dynamicRolesEnabled;
    }

    public void setCloudStackVersion(String cloudStackVersion) {
        this.cloudStackVersion = cloudStackVersion;
    }

    public void setUserPublicTemplateEnabled(boolean userPublicTemplateEnabled) {
        this.userPublicTemplateEnabled = userPublicTemplateEnabled;
    }

    public void setSupportELB(String supportELB) {
        this.supportELB = supportELB;
    }

    public void setProjectInviteRequired(Boolean projectInviteRequired) {
        this.projectInviteRequired = projectInviteRequired;
    }

    public void setAllowUsersCreateProjects(Boolean allowUsersCreateProjects) {
        this.allowUsersCreateProjects = allowUsersCreateProjects;
    }

    public void setDiskOffMinSize(Long diskOffMinSize) {
        this.diskOffMinSize = diskOffMinSize;
    }

    public void setDiskOffMaxSize(Long diskOffMaxSize) {
        this.diskOffMaxSize = diskOffMaxSize;
    }

    public void setRegionSecondaryEnabled(boolean regionSecondaryEnabled) {
        this.regionSecondaryEnabled = regionSecondaryEnabled;
    }

    public void setKVMSnapshotEnabled(boolean kvmSnapshotEnabled) {
        this.kvmSnapshotEnabled = kvmSnapshotEnabled;
    }

    public void setApiLimitInterval(Integer apiLimitInterval) {
        this.apiLimitInterval = apiLimitInterval;
    }

    public void setApiLimitMax(Integer apiLimitMax) {
        this.apiLimitMax = apiLimitMax;
    }

    public void setAllowUserViewDestroyedVM(boolean allowUserViewDestroyedVM) {
        this.allowUserViewDestroyedVM = allowUserViewDestroyedVM;
    }

    public void setAllowUserExpungeRecoverVM(boolean allowUserExpungeRecoverVM) {
        this.allowUserExpungeRecoverVM = allowUserExpungeRecoverVM;
    }

    public void setAllowUserExpungeRecoverVolume(boolean allowUserExpungeRecoverVolume) {
        this.allowUserExpungeRecoverVolume = allowUserExpungeRecoverVolume;
    }

    public void setAllowUserViewAllDomainAccounts(boolean allowUserViewAllDomainAccounts) {
        this.allowUserViewAllDomainAccounts = allowUserViewAllDomainAccounts;
    }

    public void setKubernetesServiceEnabled(boolean kubernetesServiceEnabled) {
        this.kubernetesServiceEnabled = kubernetesServiceEnabled;
    }

    public void setKubernetesClusterExperimentalFeaturesEnabled(boolean kubernetesClusterExperimentalFeaturesEnabled) {
        this.kubernetesClusterExperimentalFeaturesEnabled = kubernetesClusterExperimentalFeaturesEnabled;
    }

    public void setDesktopServiceEnabled(boolean desktopServiceEnabled) {
        this.desktopServiceEnabled = desktopServiceEnabled;
    }

    public void setDesktopWorksPortalPort(String desktopWorksPortalPort) {
        this.desktopWorksPortalPort = desktopWorksPortalPort;
    }

    public void setAutomationServiceEnabled(boolean automationServiceEnabled) {
        this.automationServiceEnabled = automationServiceEnabled;
    }

    public void setWallPortalProtocol(String wallPortalProtocol) {
        this.wallPortalProtocol = wallPortalProtocol;
    }

    public void setWallPortalDomain(String wallPortalDomain) {
        this.wallPortalDomain = wallPortalDomain;
    }

    public void setWallPortalPort(String wallPortalPort) {
        this.wallPortalPort = wallPortalPort;
    }

    public void setWallPortalVmUri(String wallPortalVmUri) {
        this.wallPortalVmUri = wallPortalVmUri;
    }

    public void setSecurityFeaturesEnabled(boolean securityFeaturesEnabled) {
        this.securityFeaturesEnabled = securityFeaturesEnabled;
    }

    public void setHost(String host) {
        this.host = host;
    }
    public void setDefaultUiPageSize(Long defaultUiPageSize) {
        this.defaultUiPageSize = defaultUiPageSize;
    }

    public void setBalancingServiceEnabled(boolean balancingServiceEnabled) {
        this.balancingServiceEnabled = balancingServiceEnabled;
    }

    public void setEventDeleteEnabled(boolean eventDeleteEnabled) {
        this.eventDeleteEnabled = eventDeleteEnabled;
    }

    public void setInstancesStatsRetentionTime(Integer instancesStatsRetentionTime) {
        this.instancesStatsRetentionTime = instancesStatsRetentionTime;
    }

    public void setInstancesStatsUserOnly(Boolean instancesStatsUserOnly) {
        this.instancesStatsUserOnly = instancesStatsUserOnly;
    }

    public void setInstancesDisksStatsRetentionEnabled(Boolean instancesDisksStatsRetentionEnabled) {
        this.instancesDisksStatsRetentionEnabled = instancesDisksStatsRetentionEnabled;
    }

    public void setInstancesDisksStatsRetentionTime(Integer instancesDisksStatsRetentionTime) {
        this.instancesDisksStatsRetentionTime = instancesDisksStatsRetentionTime;
    }

    public void setCustomHypervisorDisplayName(String customHypervisorDisplayName) {
        this.customHypervisorDisplayName = customHypervisorDisplayName;
    }
}
