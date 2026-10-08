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

package org.apache.cloudstack.storage.dataservice;

import org.apache.cloudstack.api.command.admin.storage.dataservice.GetStorageServiceRuntimeUpgradeCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceRuntimeBundlesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceRuntimeUpgradesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RegisterStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpdateStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.DeleteStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageServiceVolumePreparationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.AttachStorageVolumeToFileShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiTargetCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfHostAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfNamespaceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfSubsystemCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageServiceInstanceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageIscsiAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageIscsiTargetCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfHostAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfNamespaceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfSubsystemCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DetachStorageServiceBackingVolumeCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DisconnectStorageServiceSessionCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.JoinStorageServiceToAdDomainCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.LeaveStorageServiceFromAdDomainCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageIscsiAclsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageIscsiTargetsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNfsAclsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNfsExportsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNvmeOfHostAclsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNvmeOfNamespacesCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNvmeOfSubsystemsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceDomainStatusCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceHealthCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceInventoryCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceInstancesCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceProtocolsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceSessionsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbAclsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbSharesCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.PrepareStorageServiceNvmeOfVmCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ResizeStorageFileShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ResizeStorageServiceBackingVolumeCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiTargetCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfHostAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfNamespaceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfSubsystemCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RepairStorageServiceNicIdentityCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageBlockTargetResponse;
import org.apache.cloudstack.api.response.StorageIdentityDomainResponse;
import org.apache.cloudstack.api.response.StorageAccessRuleResponse;
import org.apache.cloudstack.api.response.StorageNfsExportResponse;
import org.apache.cloudstack.api.response.StorageFileShareResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceProtocolResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeCapabilityResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeUpgradeResponse;
import org.apache.cloudstack.api.response.StorageSmbShareResponse;

public interface StorageService {
    /** Called only by an already serialized lifecycle writer, never by readonly preflight. */
    String beginRuntimeOperationControl(long upgradeId, boolean rollback);
    void beginManagedOperationControl(String operationUuid, String scopeJson, long artifactBytes);
    void verifyManagedOperationControl(String operationUuid);
    void suspendManagedOperationControl(String operationUuid);
    void resumeManagedOperationControl(String operationUuid);
    void finishManagedOperationControl(String operationUuid, String terminalState);

    StorageServiceRuntimeResponse repairStorageServiceSmbIdentity(org.apache.cloudstack.api.command.user.storage.dataservice.RepairStorageServiceSmbIdentityCmd cmd);
    StorageServiceRuntimeResponse storageServiceOperationControl(org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceOperationControlCmd cmd);
    org.apache.cloudstack.api.response.StorageServiceTemplateUpgradeResponse storageServiceTemplateUpgrade(StorageTemplateUpgradeRequest request);

    org.apache.cloudstack.api.response.StorageServiceOperationResponse reconcileStorageServiceOperation(
            org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd cmd);

    org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse executeStoragePosixDirectoryPolicy(
            org.apache.cloudstack.api.command.user.storage.dataservice.BaseStoragePosixDirectoryPolicyCmd cmd);
    ListResponse<org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse> listStoragePosixDirectoryPolicies(
            org.apache.cloudstack.api.command.user.storage.dataservice.ListStoragePosixDirectoryPoliciesCmd cmd);

    ListResponse<StorageAccessRuleResponse> createStorageSmbNetworkAcl(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbNetworkAclCmd cmd);
    StorageAccessRuleResponse updateStorageSmbNetworkAcl(org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbNetworkAclCmd cmd);
    boolean deleteStorageSmbNetworkAcl(org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbNetworkAclCmd cmd);
    ListResponse<StorageAccessRuleResponse> listStorageSmbNetworkAcls(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbNetworkAclsCmd cmd);

    org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse getStorageNfsServiceSettings(org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageNfsServiceSettingsCmd cmd);
    org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse updateStorageNfsServiceSettings(org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsServiceSettingsCmd cmd);

    org.apache.cloudstack.api.response.ListResponse<org.apache.cloudstack.api.response.StorageServiceOperationResponse> listStorageServiceOperations(
            org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceOperationsCmd cmd);
    Long getStorageServiceSyncId(org.apache.cloudstack.api.BaseCmd cmd);

    StorageServiceInstanceResponse createStorageServiceInstance(CreateStorageServiceInstanceCmd cmd);

    ListResponse<StorageServiceInstanceResponse> listStorageServiceInstances(ListStorageServiceInstancesCmd cmd);

    StorageServiceProtocolResponse enableStorageServiceProtocol(EnableStorageServiceProtocolCmd cmd);

    boolean deleteStorageServiceProtocol(DeleteStorageServiceProtocolCmd cmd);

    ListResponse<StorageServiceProtocolResponse> listStorageServiceProtocols(ListStorageServiceProtocolsCmd cmd);

    StorageNfsExportResponse createStorageNfsExport(CreateStorageNfsExportCmd cmd);

    StorageNfsExportResponse updateStorageNfsExport(UpdateStorageNfsExportCmd cmd);

    boolean deleteStorageNfsExport(DeleteStorageNfsExportCmd cmd);

    ListResponse<StorageNfsExportResponse> listStorageNfsExports(ListStorageNfsExportsCmd cmd);

    StorageAccessRuleResponse createStorageNfsAcl(CreateStorageNfsAclCmd cmd);

    StorageAccessRuleResponse updateStorageNfsAcl(UpdateStorageNfsAclCmd cmd);

    boolean deleteStorageNfsAcl(DeleteStorageNfsAclCmd cmd);

    ListResponse<StorageAccessRuleResponse> listStorageNfsAcls(ListStorageNfsAclsCmd cmd);

    StorageSmbShareResponse createStorageSmbShare(CreateStorageSmbShareCmd cmd);

    StorageSmbShareResponse updateStorageSmbShare(UpdateStorageSmbShareCmd cmd);

    boolean deleteStorageSmbShare(DeleteStorageSmbShareCmd cmd);

    ListResponse<StorageSmbShareResponse> listStorageSmbShares(ListStorageSmbSharesCmd cmd);

    StorageAccessRuleResponse createStorageSmbAcl(CreateStorageSmbAclCmd cmd);

    StorageAccessRuleResponse updateStorageSmbAcl(UpdateStorageSmbAclCmd cmd);

    boolean deleteStorageSmbAcl(DeleteStorageSmbAclCmd cmd);

    ListResponse<StorageAccessRuleResponse> listStorageSmbAcls(ListStorageSmbAclsCmd cmd);

    StorageIdentityDomainResponse joinStorageServiceToAdDomain(JoinStorageServiceToAdDomainCmd cmd);

    StorageIdentityDomainResponse leaveStorageServiceFromAdDomain(LeaveStorageServiceFromAdDomainCmd cmd);

    ListResponse<StorageIdentityDomainResponse> listStorageServiceDomainStatus(ListStorageServiceDomainStatusCmd cmd);

    ListResponse<StorageServiceRuntimeResponse> listStorageServiceHealth(ListStorageServiceHealthCmd cmd);

    ListResponse<StorageServiceRuntimeResponse> listStorageServiceInventory(ListStorageServiceInventoryCmd cmd);

    ListResponse<StorageServiceRuntimeResponse> listStorageServiceSessions(ListStorageServiceSessionsCmd cmd);

    StorageServiceRuntimeResponse disconnectStorageServiceSession(DisconnectStorageServiceSessionCmd cmd);

    StorageFileShareResponse attachStorageVolumeToFileShare(AttachStorageVolumeToFileShareCmd cmd);

    StorageServiceRuntimeResponse getStorageServiceVolumePreparation(GetStorageServiceVolumePreparationCmd cmd);
    StorageServiceRuntimeResponse resumeStorageServiceVolumePreparation(org.apache.cloudstack.api.command.user.storage.dataservice.ResumeStorageServiceVolumePreparationCmd cmd);

    StorageServiceRuntimeResponse detachStorageServiceBackingVolume(DetachStorageServiceBackingVolumeCmd cmd);

    StorageFileShareResponse resizeStorageFileShare(ResizeStorageFileShareCmd cmd);

    StorageServiceRuntimeResponse resizeStorageServiceBackingVolume(ResizeStorageServiceBackingVolumeCmd cmd);

    StorageServiceRuntimeResponse prepareStorageServiceNvmeOfVm(PrepareStorageServiceNvmeOfVmCmd cmd);

    StorageServiceRuntimeResponse repairStorageServiceNicIdentity(RepairStorageServiceNicIdentityCmd cmd);

    StorageBlockTargetResponse createStorageIscsiTarget(CreateStorageIscsiTargetCmd cmd);

    StorageBlockTargetResponse updateStorageIscsiTarget(UpdateStorageIscsiTargetCmd cmd);

    boolean deleteStorageIscsiTarget(DeleteStorageIscsiTargetCmd cmd);

    ListResponse<StorageBlockTargetResponse> listStorageIscsiTargets(ListStorageIscsiTargetsCmd cmd);

    StorageAccessRuleResponse createStorageIscsiAcl(CreateStorageIscsiAclCmd cmd);

    StorageAccessRuleResponse updateStorageIscsiAcl(UpdateStorageIscsiAclCmd cmd);

    boolean deleteStorageIscsiAcl(DeleteStorageIscsiAclCmd cmd);

    ListResponse<StorageAccessRuleResponse> listStorageIscsiAcls(ListStorageIscsiAclsCmd cmd);

    StorageBlockTargetResponse createStorageNvmeOfSubsystem(CreateStorageNvmeOfSubsystemCmd cmd);

    StorageBlockTargetResponse updateStorageNvmeOfSubsystem(UpdateStorageNvmeOfSubsystemCmd cmd);

    boolean deleteStorageNvmeOfSubsystem(DeleteStorageNvmeOfSubsystemCmd cmd);

    ListResponse<StorageBlockTargetResponse> listStorageNvmeOfSubsystems(ListStorageNvmeOfSubsystemsCmd cmd);

    ListResponse<StorageBlockTargetResponse> listStorageNvmeOfNamespaces(ListStorageNvmeOfNamespacesCmd cmd);

    StorageBlockTargetResponse createStorageNvmeOfNamespace(CreateStorageNvmeOfNamespaceCmd cmd);

    StorageBlockTargetResponse updateStorageNvmeOfNamespace(UpdateStorageNvmeOfNamespaceCmd cmd);

    boolean deleteStorageNvmeOfNamespace(DeleteStorageNvmeOfNamespaceCmd cmd);

    StorageAccessRuleResponse createStorageNvmeOfHostAcl(CreateStorageNvmeOfHostAclCmd cmd);

    StorageAccessRuleResponse updateStorageNvmeOfHostAcl(UpdateStorageNvmeOfHostAclCmd cmd);

    boolean deleteStorageNvmeOfHostAcl(DeleteStorageNvmeOfHostAclCmd cmd);

    ListResponse<StorageAccessRuleResponse> listStorageNvmeOfHostAcls(ListStorageNvmeOfHostAclsCmd cmd);

    StorageServiceRuntimeBundleResponse updateStorageServiceRuntimeBundle(UpdateStorageServiceRuntimeBundleCmd cmd);
    boolean deleteStorageServiceRuntimeBundle(DeleteStorageServiceRuntimeBundleCmd cmd);

    StorageServiceRuntimeBundleResponse registerStorageServiceRuntimeBundle(RegisterStorageServiceRuntimeBundleCmd cmd);

    ListResponse<StorageServiceRuntimeBundleResponse> listStorageServiceRuntimeBundles(ListStorageServiceRuntimeBundlesCmd cmd);

    StorageServiceRuntimeCapabilityResponse getStorageServiceRuntimeUpgradeCapabilities(GetStorageServiceRuntimeUpgradeCapabilitiesCmd cmd);

    StorageServiceRuntimeUpgradeResponse preflightStorageServiceRuntimeUpgrade(PreflightStorageServiceRuntimeUpgradeCmd cmd);

    StorageServiceRuntimeUpgradeResponse upgradeStorageServiceRuntime(UpgradeStorageServiceRuntimeCmd cmd);

    ListResponse<StorageServiceRuntimeUpgradeResponse> listStorageServiceRuntimeUpgrades(ListStorageServiceRuntimeUpgradesCmd cmd);

    StorageServiceRuntimeUpgradeResponse rollbackStorageServiceRuntimeUpgrade(RollbackStorageServiceRuntimeUpgradeCmd cmd);
    org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse storageServiceConfiguration(StorageConfigRequest cmd);

    StorageServiceRuntimeResponse getStorageServiceControlPolicy(org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageServiceControlPolicyCmd cmd);
    StorageServiceRuntimeResponse configureStorageServiceControlPolicy(org.apache.cloudstack.api.command.admin.storage.dataservice.ConfigureStorageServiceControlPolicyCmd cmd);

}
