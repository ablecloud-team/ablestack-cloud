

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
package com.cloud.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.naming.ConfigurationException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import com.cloud.cpu.CPU;
import com.cloud.dc.VlanDetailsVO;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PublicIpQuarantineDao;
import com.cloud.utils.security.CertificateHelper;
import com.cloud.api.query.dao.ManagementServerJoinDao;
import com.cloud.api.query.vo.ManagementServerJoinVO;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.offering.ServiceOffering;
import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.affinity.AffinityGroupProcessor;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.account.CreateAccountCmd;
import org.apache.cloudstack.api.command.admin.account.DeleteAccountCmd;
import org.apache.cloudstack.api.command.admin.account.DisableAccountCmd;
import org.apache.cloudstack.api.command.admin.account.EnableAccountCmd;
import org.apache.cloudstack.api.command.admin.account.ListAccountsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.account.LockAccountCmd;
import org.apache.cloudstack.api.command.admin.account.UpdateAccountCmd;
import org.apache.cloudstack.api.command.admin.address.AcquirePodIpCmdByAdmin;
import org.apache.cloudstack.api.command.admin.address.AssociateIPAddrCmdByAdmin;
import org.apache.cloudstack.api.command.admin.address.ListPublicIpAddressesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.affinitygroup.UpdateVMAffinityGroupCmdByAdmin;
import org.apache.cloudstack.api.command.admin.alert.GenerateAlertCmd;
import org.apache.cloudstack.api.command.admin.autoscale.CreateCounterCmd;
import org.apache.cloudstack.api.command.admin.autoscale.DeleteCounterCmd;
import org.apache.cloudstack.api.command.admin.cluster.AddClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.DeleteClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.ListClustersCmd;
import org.apache.cloudstack.api.command.admin.cluster.UpdateClusterCmd;
import org.apache.cloudstack.api.command.admin.config.ListCfgGroupsByCmd;
import org.apache.cloudstack.api.command.admin.config.ListCfgsByCmd;
import org.apache.cloudstack.api.command.admin.config.ListDeploymentPlannersCmd;
import org.apache.cloudstack.api.command.admin.config.ListHypervisorCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.config.ResetCfgCmd;
import org.apache.cloudstack.api.command.admin.config.UpdateCfgCmd;
import org.apache.cloudstack.api.command.admin.config.UpdateHypervisorCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.direct.download.ListTemplateDirectDownloadCertificatesCmd;
import org.apache.cloudstack.api.command.admin.direct.download.ProvisionTemplateDirectDownloadCertificateCmd;
import org.apache.cloudstack.api.command.admin.direct.download.RevokeTemplateDirectDownloadCertificateCmd;
import org.apache.cloudstack.api.command.admin.domain.CreateDomainCmd;
import org.apache.cloudstack.api.command.admin.domain.DeleteDomainCmd;
import org.apache.cloudstack.api.command.admin.domain.ListDomainChildrenCmd;
import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmd;
import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.domain.MoveDomainCmd;
import org.apache.cloudstack.api.command.admin.domain.UpdateDomainCmd;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.AddGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.DeleteGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.GetHypervisorGuestOsNamesCmd;
import org.apache.cloudstack.api.command.admin.guest.ListGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.RemoveGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.RemoveGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsCategoryCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsCmd;
import org.apache.cloudstack.api.command.admin.guest.UpdateGuestOsMappingCmd;
import org.apache.cloudstack.api.command.admin.host.AddHostCmd;
import org.apache.cloudstack.api.command.admin.host.AddSecondaryStorageCmd;
import org.apache.cloudstack.api.command.admin.host.CancelHostAsDegradedCmd;
import org.apache.cloudstack.api.command.admin.host.CancelHostMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.host.DeclareHostAsDegradedCmd;
import org.apache.cloudstack.api.command.admin.host.DeleteHostCmd;
import org.apache.cloudstack.api.command.admin.host.FindHostsForMigrationCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostTagsCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostsCmd;
import org.apache.cloudstack.api.command.admin.host.PrepareForHostMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.host.ReconnectHostCmd;
import org.apache.cloudstack.api.command.admin.host.ReleaseHostReservationCmd;
import org.apache.cloudstack.api.command.admin.host.UpdateHostCmd;
import org.apache.cloudstack.api.command.admin.host.UpdateHostPasswordCmd;
import org.apache.cloudstack.api.command.admin.internallb.ConfigureInternalLoadBalancerElementCmd;
import org.apache.cloudstack.api.command.admin.internallb.CreateInternalLoadBalancerElementCmd;
import org.apache.cloudstack.api.command.admin.internallb.ListInternalLBVMsCmd;
import org.apache.cloudstack.api.command.admin.internallb.ListInternalLoadBalancerElementsCmd;
import org.apache.cloudstack.api.command.admin.internallb.StartInternalLBVMCmd;
import org.apache.cloudstack.api.command.admin.internallb.StopInternalLBVMCmd;
import org.apache.cloudstack.api.command.admin.iso.AttachIsoCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.CopyIsoCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.DetachIsoCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.ListIsoPermissionsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.ListIsosCmdByAdmin;
import org.apache.cloudstack.api.command.admin.iso.RegisterIsoCmdByAdmin;
import org.apache.cloudstack.api.command.admin.loadbalancer.ListLoadBalancerRuleInstancesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.management.ListMgmtsCmd;
import org.apache.cloudstack.api.command.admin.management.RemoveManagementServerCmd;
import org.apache.cloudstack.api.command.admin.network.AddNetworkDeviceCmd;
import org.apache.cloudstack.api.command.admin.network.AddNetworkServiceProviderCmd;
import org.apache.cloudstack.api.command.admin.network.CreateNetworkCmdByAdmin;
import org.apache.cloudstack.api.command.admin.network.CreateNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.CreatePhysicalNetworkCmd;
import org.apache.cloudstack.api.command.admin.network.CreateStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DedicateGuestVlanRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkDeviceCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkServiceProviderCmd;
import org.apache.cloudstack.api.command.admin.network.DeletePhysicalNetworkCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListDedicatedGuestVlanRangesCmd;
import org.apache.cloudstack.api.command.admin.network.ListGuestVlansCmd;
import org.apache.cloudstack.api.command.admin.network.ListNetworkDeviceCmd;
import org.apache.cloudstack.api.command.admin.network.ListNetworkIsolationMethodsCmd;
import org.apache.cloudstack.api.command.admin.network.ListNetworkServiceProvidersCmd;
import org.apache.cloudstack.api.command.admin.network.ListPhysicalNetworksCmd;
import org.apache.cloudstack.api.command.admin.network.ListStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListSupportedNetworkServicesCmd;
import org.apache.cloudstack.api.command.admin.network.MigrateNetworkCmd;
import org.apache.cloudstack.api.command.admin.network.MigrateVPCCmd;
import org.apache.cloudstack.api.command.admin.network.ReleaseDedicatedGuestVlanRangeCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkServiceProviderCmd;
import org.apache.cloudstack.api.command.admin.network.UpdatePhysicalNetworkCmd;
import org.apache.cloudstack.api.command.admin.network.UpdatePodManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateStorageNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.IsAccountAllowedToCreateOfferingsWithTagsCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ChangeOutOfBandManagementPasswordCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ConfigureOutOfBandManagementCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.CreateVhbaDeviceCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.DeleteVhbaDeviceCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.DisableOutOfBandManagementForClusterCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.DisableOutOfBandManagementForHostCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.DisableOutOfBandManagementForZoneCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.EnableOutOfBandManagementForClusterCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.EnableOutOfBandManagementForHostCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.EnableOutOfBandManagementForZoneCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.IssueOutOfBandManagementPowerActionCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.LicenseCheckCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ListHostDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ListHostHbaDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ListHostLunDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ListHostRedfishDataCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ListHostScsiDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ListHostUsbDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.ListVhbaDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.UpdateHostDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.UpdateHostHbaDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.UpdateHostLunDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.UpdateHostScsiDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.UpdateHostUsbDevicesCmd;
import org.apache.cloudstack.api.command.admin.outofbandmanagement.UpdateHostVhbaDevicesCmd;
import org.apache.cloudstack.api.command.admin.pod.CreatePodCmd;
import org.apache.cloudstack.api.command.admin.pod.DeletePodCmd;
import org.apache.cloudstack.api.command.admin.pod.ListPodsByCmd;
import org.apache.cloudstack.api.command.admin.pod.UpdatePodCmd;
import org.apache.cloudstack.api.command.admin.region.AddRegionCmd;
import org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.DeletePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.ListPortableIpRangesCmd;
import org.apache.cloudstack.api.command.admin.region.RemoveRegionCmd;
import org.apache.cloudstack.api.command.admin.region.UpdateRegionCmd;
import org.apache.cloudstack.api.command.admin.resource.ArchiveAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.CleanVMReservationsCmd;
import org.apache.cloudstack.api.command.admin.resource.DeleteAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.ListAlertTypesCmd;
import org.apache.cloudstack.api.command.admin.resource.ListAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.ListCapacityCmd;
import org.apache.cloudstack.api.command.admin.resource.StartRollingMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.resource.UploadCustomCertificateCmd;
import org.apache.cloudstack.api.command.admin.resource.icon.DeleteResourceIconCmd;
import org.apache.cloudstack.api.command.admin.resource.icon.ListResourceIconCmd;
import org.apache.cloudstack.api.command.admin.resource.icon.UploadResourceIconCmd;
import org.apache.cloudstack.api.command.admin.router.ConfigureOvsElementCmd;
import org.apache.cloudstack.api.command.admin.router.ConfigureVirtualRouterElementCmd;
import org.apache.cloudstack.api.command.admin.router.CreateVirtualRouterElementCmd;
import org.apache.cloudstack.api.command.admin.router.DestroyRouterCmd;
import org.apache.cloudstack.api.command.admin.router.GetRouterHealthCheckResultsCmd;
import org.apache.cloudstack.api.command.admin.router.ListOvsElementsCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.command.admin.router.ListVirtualRouterElementsCmd;
import org.apache.cloudstack.api.command.admin.router.RebootRouterCmd;
import org.apache.cloudstack.api.command.admin.router.StartRouterCmd;
import org.apache.cloudstack.api.command.admin.router.StopRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterTemplateCmd;
import org.apache.cloudstack.api.command.admin.snapshot.ListSnapshotsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.storage.AddImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.AddImageStoreS3CMD;
import org.apache.cloudstack.api.command.admin.storage.AddObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.CancelPrimaryStorageMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.storage.ChangeStoragePoolScopeCmd;
import org.apache.cloudstack.api.command.admin.storage.ConfigureStorageAccessCmd;
import org.apache.cloudstack.api.command.admin.storage.CreateSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.CreateStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeletePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.FindStoragePoolsForMigrationCmd;
import org.apache.cloudstack.api.command.admin.storage.ListImageStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListObjectStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListSecondaryStagingStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageAccessGroupsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStoragePoolsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageProvidersCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageTagsCmd;
import org.apache.cloudstack.api.command.admin.storage.MigrateResourcesToAnotherSecondaryStorageCmd;
import org.apache.cloudstack.api.command.admin.storage.MigrateSecondaryStorageDataCmd;
import org.apache.cloudstack.api.command.admin.storage.PreparePrimaryStorageForMaintenanceCmd;
import org.apache.cloudstack.api.command.admin.storage.SyncStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateCloudToUseObjectStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateStorageCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.CreateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.ListSecondaryStorageSelectorsCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.RemoveSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.storage.heuristics.UpdateSecondaryStorageSelectorCmd;
import org.apache.cloudstack.api.command.admin.swift.AddSwiftCmd;
import org.apache.cloudstack.api.command.admin.swift.ListSwiftsCmd;
import org.apache.cloudstack.api.command.admin.systemvm.DestroySystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ListSystemVMsCmd;
import org.apache.cloudstack.api.command.admin.systemvm.MigrateSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.PatchSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.RebootSystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ScaleSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.StartSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.StopSystemVmCmd;
import org.apache.cloudstack.api.command.admin.systemvm.UpgradeSystemVMCmd;
import org.apache.cloudstack.api.command.admin.template.CopyTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.admin.template.CreateTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.admin.template.ListTemplatePermissionsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.template.ListTemplatesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.template.PrepareTemplateCmd;
import org.apache.cloudstack.api.command.admin.template.RegisterTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.admin.usage.AddTrafficMonitorCmd;
import org.apache.cloudstack.api.command.admin.usage.AddTrafficTypeCmd;
import org.apache.cloudstack.api.command.admin.usage.DeleteTrafficMonitorCmd;
import org.apache.cloudstack.api.command.admin.usage.DeleteTrafficTypeCmd;
import org.apache.cloudstack.api.command.admin.usage.GenerateUsageRecordsCmd;
import org.apache.cloudstack.api.command.admin.usage.ListTrafficMonitorsCmd;
import org.apache.cloudstack.api.command.admin.usage.ListTrafficTypeImplementorsCmd;
import org.apache.cloudstack.api.command.admin.usage.ListTrafficTypesCmd;
import org.apache.cloudstack.api.command.admin.usage.ListUsageRecordsCmd;
import org.apache.cloudstack.api.command.admin.usage.ListUsageTypesCmd;
import org.apache.cloudstack.api.command.admin.usage.RemoveRawUsageRecordsCmd;
import org.apache.cloudstack.api.command.admin.usage.UpdateTrafficTypeCmd;
import org.apache.cloudstack.api.command.admin.user.CreateUserCmd;
import org.apache.cloudstack.api.command.admin.user.DeleteUserCmd;
import org.apache.cloudstack.api.command.admin.user.DisableUserCmd;
import org.apache.cloudstack.api.command.admin.user.EnableUserCmd;
import org.apache.cloudstack.api.command.admin.user.GetUserCmd;
import org.apache.cloudstack.api.command.admin.user.GetUserKeysCmd;
import org.apache.cloudstack.api.command.admin.user.ListUsersCmd;
import org.apache.cloudstack.api.command.admin.user.LockUserCmd;
import org.apache.cloudstack.api.command.admin.user.MoveUserCmd;
import org.apache.cloudstack.api.command.admin.user.RegisterCmd;
import org.apache.cloudstack.api.command.admin.user.UpdateUserCmd;
import org.apache.cloudstack.api.command.admin.vlan.CreateVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DedicatePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DeleteVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.ListVlanIpRangesCmd;
import org.apache.cloudstack.api.command.admin.vlan.ReleasePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.UpdateVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vm.AddNicToVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.AssignVMCmd;
import org.apache.cloudstack.api.command.admin.vm.DeployVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.DeployVMVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.DestroyVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ExpungeVMCmd;
import org.apache.cloudstack.api.command.admin.vm.GetVMUserDataCmd;
import org.apache.cloudstack.api.command.admin.vm.ListAffectedVmsForStorageScopeChangeCmd;
import org.apache.cloudstack.api.command.admin.vm.ListVMsCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.MigrateVMCmd;
import org.apache.cloudstack.api.command.admin.vm.MigrateVirtualMachineWithVolumeCmd;
import org.apache.cloudstack.api.command.admin.vm.RebootVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.RecoverVMCmd;
import org.apache.cloudstack.api.command.admin.vm.RemoveNicFromVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ResetVMPasswordCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ResetVMSSHKeyCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.RestoreVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.ScaleVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.StartVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.StopVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.UpdateDefaultNicForVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.UpdateVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vm.UpgradeVMCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vmsnapshot.RevertToVMSnapshotCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.AttachVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.CreateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.DestroyVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.DetachVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.ListVolumesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.MigrateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.RecoverVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.ResizeVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.UpdateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.volume.UploadVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vpc.CreatePrivateGatewayByAdminCmd;
import org.apache.cloudstack.api.command.admin.vpc.CreateVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.DeletePrivateGatewayCmd;
import org.apache.cloudstack.api.command.admin.vpc.DeleteVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.ListPrivateGatewaysCmdByAdminCmd;
import org.apache.cloudstack.api.command.admin.vpc.UpdateVPCCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vpc.UpdateVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.zone.CreateZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.DeleteZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.ListZonesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.zone.MarkDefaultZoneForAccountCmd;
import org.apache.cloudstack.api.command.admin.zone.UpdateZoneCmd;
import org.apache.cloudstack.api.command.user.account.AddAccountToProjectCmd;
import org.apache.cloudstack.api.command.user.account.AddUserToProjectCmd;
import org.apache.cloudstack.api.command.user.account.DeleteAccountFromProjectCmd;
import org.apache.cloudstack.api.command.user.account.DeleteUserFromProjectCmd;
import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;
import org.apache.cloudstack.api.command.user.account.ListProjectAccountsCmd;
import org.apache.cloudstack.api.command.user.address.AssociateIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.DisassociateIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.ListPublicIpAddressesCmd;
import org.apache.cloudstack.api.command.user.address.ListQuarantinedIpsCmd;
import org.apache.cloudstack.api.command.user.address.ReleaseIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.RemoveQuarantinedIpCmd;
import org.apache.cloudstack.api.command.user.address.ReserveIPAddrCmd;
import org.apache.cloudstack.api.command.user.address.UpdateIPAddrCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.CreateAffinityGroupCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.DeleteAffinityGroupCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupTypesCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupsCmd;
import org.apache.cloudstack.api.command.user.affinitygroup.UpdateVMAffinityGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScalePolicyCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateAutoScaleVmProfileCmd;
import org.apache.cloudstack.api.command.user.autoscale.CreateConditionCmd;
import org.apache.cloudstack.api.command.user.autoscale.DeleteAutoScalePolicyCmd;
import org.apache.cloudstack.api.command.user.autoscale.DeleteAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.DeleteAutoScaleVmProfileCmd;
import org.apache.cloudstack.api.command.user.autoscale.DeleteConditionCmd;
import org.apache.cloudstack.api.command.user.autoscale.DisableAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.EnableAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScalePoliciesCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScaleVmGroupsCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListAutoScaleVmProfilesCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListConditionsCmd;
import org.apache.cloudstack.api.command.user.autoscale.ListCountersCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScalePolicyCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScaleVmGroupCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateAutoScaleVmProfileCmd;
import org.apache.cloudstack.api.command.user.autoscale.UpdateConditionCmd;
import org.apache.cloudstack.api.command.user.bucket.CreateBucketCmd;
import org.apache.cloudstack.api.command.user.bucket.DeleteBucketCmd;
import org.apache.cloudstack.api.command.user.bucket.ListBucketsCmd;
import org.apache.cloudstack.api.command.user.bucket.UpdateBucketCmd;
import org.apache.cloudstack.api.command.user.config.ListCapabilitiesCmd;
import org.apache.cloudstack.api.command.user.consoleproxy.CreateConsoleEndpointCmd;
import org.apache.cloudstack.api.command.user.consoleproxy.ListConsoleSessionsCmd;
import org.apache.cloudstack.api.command.user.event.ArchiveEventsCmd;
import org.apache.cloudstack.api.command.user.event.DeleteEventsCmd;
import org.apache.cloudstack.api.command.user.event.DownloadEventsCmd;
import org.apache.cloudstack.api.command.user.event.ListEventTypesCmd;
import org.apache.cloudstack.api.command.user.event.ListEventsCmd;
import org.apache.cloudstack.api.command.user.firewall.CreateEgressFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.CreateFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.CreatePortForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.DeleteEgressFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.DeleteFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.DeletePortForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.ListEgressFirewallRulesCmd;
import org.apache.cloudstack.api.command.user.firewall.ListFirewallRulesCmd;
import org.apache.cloudstack.api.command.user.firewall.ListPortForwardingRulesCmd;
import org.apache.cloudstack.api.command.user.firewall.UpdateEgressFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.UpdateFirewallRuleCmd;
import org.apache.cloudstack.api.command.user.firewall.UpdatePortForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.guest.ListGuestOsCategoriesCmd;
import org.apache.cloudstack.api.command.user.guest.ListGuestOsCmd;
import org.apache.cloudstack.api.command.user.gui.theme.CreateGuiThemeCmd;
import org.apache.cloudstack.api.command.user.gui.theme.ListGuiThemesCmd;
import org.apache.cloudstack.api.command.user.gui.theme.RemoveGuiThemeCmd;
import org.apache.cloudstack.api.command.user.gui.theme.UpdateGuiThemeCmd;
import org.apache.cloudstack.api.command.user.iso.AttachIsoCmd;
import org.apache.cloudstack.api.command.user.iso.CopyIsoCmd;
import org.apache.cloudstack.api.command.user.iso.DeleteIsoCmd;
import org.apache.cloudstack.api.command.user.iso.DetachIsoCmd;
import org.apache.cloudstack.api.command.user.iso.ExtractIsoCmd;
import org.apache.cloudstack.api.command.user.iso.GetUploadParamsForIsoCmd;
import org.apache.cloudstack.api.command.user.iso.ListIsoPermissionsCmd;
import org.apache.cloudstack.api.command.user.iso.ListIsosCmd;
import org.apache.cloudstack.api.command.user.iso.RegisterIsoCmd;
import org.apache.cloudstack.api.command.user.iso.UpdateIsoCmd;
import org.apache.cloudstack.api.command.user.iso.UpdateIsoPermissionsCmd;
import org.apache.cloudstack.api.command.user.job.ListAsyncJobsCmd;
import org.apache.cloudstack.api.command.user.job.QueryAsyncJobResultCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.AssignCertToLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.AssignToLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.CreateApplicationLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.CreateLBHealthCheckPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.CreateLBStickinessPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.CreateLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteApplicationLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteLBHealthCheckPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteLBStickinessPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.DeleteSslCertCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListApplicationLoadBalancersCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListLBHealthCheckPoliciesCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListLBStickinessPoliciesCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListLoadBalancerRuleInstancesCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListLoadBalancerRulesCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.ListSslCertsCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.RemoveCertFromLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.RemoveFromLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UpdateApplicationLoadBalancerCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UpdateLBHealthCheckPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UpdateLBStickinessPolicyCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UpdateLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.loadbalancer.UploadSslCertCmd;
import org.apache.cloudstack.api.command.user.nat.CreateIpForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.nat.DeleteIpForwardingRuleCmd;
import org.apache.cloudstack.api.command.user.nat.DisableStaticNatCmd;
import org.apache.cloudstack.api.command.user.nat.EnableStaticNatCmd;
import org.apache.cloudstack.api.command.user.nat.ListIpForwardingRulesCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkACLCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkACLListCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.DeleteNetworkACLCmd;
import org.apache.cloudstack.api.command.user.network.DeleteNetworkACLListCmd;
import org.apache.cloudstack.api.command.user.network.DeleteNetworkCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkACLListsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkACLsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkOfferingsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkProtocolsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworksCmd;
import org.apache.cloudstack.api.command.user.network.MoveNetworkAclItemCmd;
import org.apache.cloudstack.api.command.user.network.RemoveNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ReplaceNetworkACLListCmd;
import org.apache.cloudstack.api.command.user.network.ResetNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.RestartNetworkCmd;
import org.apache.cloudstack.api.command.user.network.UpdateNetworkACLItemCmd;
import org.apache.cloudstack.api.command.user.network.UpdateNetworkACLListCmd;
import org.apache.cloudstack.api.command.user.network.UpdateNetworkCmd;
import org.apache.cloudstack.api.command.user.offering.ListDiskOfferingsCmd;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.command.user.project.ActivateProjectCmd;
import org.apache.cloudstack.api.command.user.project.CreateProjectCmd;
import org.apache.cloudstack.api.command.user.project.DeleteProjectCmd;
import org.apache.cloudstack.api.command.user.project.DeleteProjectInvitationCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectInvitationsCmd;
import org.apache.cloudstack.api.command.user.project.ListProjectsCmd;
import org.apache.cloudstack.api.command.user.project.SuspendProjectCmd;
import org.apache.cloudstack.api.command.user.project.UpdateProjectCmd;
import org.apache.cloudstack.api.command.user.project.UpdateProjectInvitationCmd;
import org.apache.cloudstack.api.command.user.region.ListRegionsCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.AssignToGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.CreateGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.DeleteGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.ListGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.RemoveFromGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.region.ha.gslb.UpdateGlobalLoadBalancerRuleCmd;
import org.apache.cloudstack.api.command.user.resource.GetCloudIdentifierCmd;
import org.apache.cloudstack.api.command.user.resource.ListDetailOptionsCmd;
import org.apache.cloudstack.api.command.user.resource.ListHypervisorsCmd;
import org.apache.cloudstack.api.command.user.resource.ListResourceLimitsCmd;
import org.apache.cloudstack.api.command.user.resource.UpdateResourceCountCmd;
import org.apache.cloudstack.api.command.user.resource.UpdateResourceLimitCmd;
import org.apache.cloudstack.api.command.user.securitygroup.AuthorizeSecurityGroupEgressCmd;
import org.apache.cloudstack.api.command.user.securitygroup.AuthorizeSecurityGroupIngressCmd;
import org.apache.cloudstack.api.command.user.securitygroup.CreateSecurityGroupCmd;
import org.apache.cloudstack.api.command.user.securitygroup.DeleteSecurityGroupCmd;
import org.apache.cloudstack.api.command.user.securitygroup.ListSecurityGroupsCmd;
import org.apache.cloudstack.api.command.user.securitygroup.RevokeSecurityGroupEgressCmd;
import org.apache.cloudstack.api.command.user.securitygroup.RevokeSecurityGroupIngressCmd;
import org.apache.cloudstack.api.command.user.securitygroup.UpdateSecurityGroupCmd;
import org.apache.cloudstack.api.command.user.snapshot.ArchiveSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CopySnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotBackupCmd;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotFromVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.CreateSnapshotPolicyCmd;
import org.apache.cloudstack.api.command.user.snapshot.DeleteSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.DeleteSnapshotPoliciesCmd;
import org.apache.cloudstack.api.command.user.snapshot.ExtractSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.ListSnapshotPoliciesCmd;
import org.apache.cloudstack.api.command.user.snapshot.ListSnapshotsCmd;
import org.apache.cloudstack.api.command.user.snapshot.RevertSnapshotCmd;
import org.apache.cloudstack.api.command.user.snapshot.UpdateSnapshotPolicyCmd;
import org.apache.cloudstack.api.command.user.ssh.CreateSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.DeleteSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.ssh.ListSSHKeyPairsCmd;
import org.apache.cloudstack.api.command.user.ssh.RegisterSSHKeyPairCmd;
import org.apache.cloudstack.api.command.user.tag.CreateTagsCmd;
import org.apache.cloudstack.api.command.user.tag.DeleteTagsCmd;
import org.apache.cloudstack.api.command.user.tag.ListTagsCmd;
import org.apache.cloudstack.api.command.user.template.CopyTemplateCmd;
import org.apache.cloudstack.api.command.user.template.CreateTemplateCmd;
import org.apache.cloudstack.api.command.user.template.DeleteTemplateCmd;
import org.apache.cloudstack.api.command.user.template.ExtractTemplateCmd;
import org.apache.cloudstack.api.command.user.template.GetUploadParamsForTemplateCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatePermissionsCmd;
import org.apache.cloudstack.api.command.user.template.ListTemplatesCmd;
import org.apache.cloudstack.api.command.user.template.RegisterTemplateCmd;
import org.apache.cloudstack.api.command.user.template.UpdateTemplateCmd;
import org.apache.cloudstack.api.command.user.template.UpdateTemplatePermissionsCmd;
import org.apache.cloudstack.api.command.user.userdata.BaseRegisterUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.LinkUserDataToTemplateCmd;
import org.apache.cloudstack.api.command.user.userdata.ListCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.ListUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterUserDataCmd;
import org.apache.cloudstack.api.command.user.vm.AddIpToVmNicCmd;
import org.apache.cloudstack.api.command.user.vm.AddNicToVMCmd;
import org.apache.cloudstack.api.command.user.vm.AllocateVbmcToVMCmd;
import org.apache.cloudstack.api.command.user.vm.CloneVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVMVolumeCmd;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.api.command.user.vm.GetVMPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.ListNicsCmd;
import org.apache.cloudstack.api.command.user.vm.ListVMsCmd;
import org.apache.cloudstack.api.command.user.vm.RebootVMCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveIpFromVmNicCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveNicFromVMCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveVbmcToVMCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMSSHKeyCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;
import org.apache.cloudstack.api.command.user.vm.RestoreVMCmd;
import org.apache.cloudstack.api.command.user.vm.ScaleVMCmd;
import org.apache.cloudstack.api.command.user.vm.StartVMCmd;
import org.apache.cloudstack.api.command.user.vm.StopVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateDefaultNicForVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicIpCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicLinkStateCmd;
import org.apache.cloudstack.api.command.user.vm.UpgradeVMCmd;
import org.apache.cloudstack.api.command.user.vmgroup.CreateVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmgroup.DeleteVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmgroup.ListVMGroupsCmd;
import org.apache.cloudstack.api.command.user.vmgroup.UpdateVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmsnapshot.CreateVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.vmsnapshot.DeleteVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.vmsnapshot.ListVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.vmsnapshot.RevertToVMSnapshotCmd;
import org.apache.cloudstack.api.command.user.volume.AddResourceDetailCmd;
import org.apache.cloudstack.api.command.user.volume.AssignVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.AttachVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ChangeOfferingForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.CheckAndRepairVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.CreateVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.DeleteVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.DestroyVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.DetachVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ExtractVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.GetUploadParamsForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ListResourceDetailsCmd;
import org.apache.cloudstack.api.command.user.volume.ListVolumesCmd;
import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.RecoverVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.RemoveResourceDetailCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.UpdateCompressDedupCmd;
import org.apache.cloudstack.api.command.user.volume.UpdateVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.UploadVolumeCmd;
import org.apache.cloudstack.api.command.user.vpc.CreatePrivateGatewayCmd;
import org.apache.cloudstack.api.command.user.vpc.CreateStaticRouteCmd;
import org.apache.cloudstack.api.command.user.vpc.CreateVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.DeleteStaticRouteCmd;
import org.apache.cloudstack.api.command.user.vpc.DeleteVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.ListPrivateGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpc.ListStaticRoutesCmd;
import org.apache.cloudstack.api.command.user.vpc.ListVPCOfferingsCmd;
import org.apache.cloudstack.api.command.user.vpc.ListVPCsCmd;
import org.apache.cloudstack.api.command.user.vpc.RestartVPCCmd;
import org.apache.cloudstack.api.command.user.vpc.UpdateVPCCmd;
import org.apache.cloudstack.api.command.user.vpn.AddVpnUserCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateRemoteAccessVpnCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnCustomerGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.CreateVpnGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteRemoteAccessVpnCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnCustomerGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.DeleteVpnGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.ListRemoteAccessVpnsCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnConnectionsCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnCustomerGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnGatewaysCmd;
import org.apache.cloudstack.api.command.user.vpn.ListVpnUsersCmd;
import org.apache.cloudstack.api.command.user.vpn.RemoveVpnUserCmd;
import org.apache.cloudstack.api.command.user.vpn.ResetVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateRemoteAccessVpnCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateVpnConnectionCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateVpnCustomerGatewayCmd;
import org.apache.cloudstack.api.command.user.vpn.UpdateVpnGatewayCmd;
import org.apache.cloudstack.api.command.user.zone.ListZonesCmd;
import org.apache.cloudstack.api.response.CreateVhbaDeviceResponse;
import org.apache.cloudstack.api.response.DeleteVhbaDeviceResponse;
import org.apache.cloudstack.api.response.LicenseCheckerResponse;
import org.apache.cloudstack.api.response.ListHostDevicesResponse;
import org.apache.cloudstack.api.response.ListHostHbaDevicesResponse;
import org.apache.cloudstack.api.response.ListHostLunDevicesResponse;
import org.apache.cloudstack.api.response.ListHostScsiDevicesResponse;
import org.apache.cloudstack.api.response.ListHostUsbDevicesResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ListVhbaDevicesResponse;
import org.apache.cloudstack.api.response.UpdateHostDevicesResponse;
import org.apache.cloudstack.api.response.UpdateHostHbaDevicesResponse;
import org.apache.cloudstack.api.response.UpdateHostLunDevicesResponse;
import org.apache.cloudstack.api.response.UpdateHostScsiDevicesResponse;
import org.apache.cloudstack.api.response.UpdateHostUsbDevicesResponse;
import org.apache.cloudstack.api.response.UpdateHostVhbaDevicesResponse;
import org.apache.cloudstack.auth.UserAuthenticator;
import org.apache.cloudstack.auth.UserTwoFactorAuthenticator;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.config.ConfigurationGroup;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationGroupDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationSubGroupDao;
import org.apache.cloudstack.framework.config.impl.ConfigurationGroupVO;
import org.apache.cloudstack.framework.config.impl.ConfigurationSubGroupVO;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.framework.security.keystore.KeystoreManager;
import org.apache.cloudstack.ha.HAConfigManager;
import org.apache.cloudstack.ha.HAConfigVO;
import org.apache.cloudstack.ha.HAResource;
import org.apache.cloudstack.ha.dao.HAConfigDao;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.management.ManagementServerHost;
import org.apache.cloudstack.query.QueryService;
import org.apache.cloudstack.resourcedetail.dao.GuestOsDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.cloudstack.utils.CloudStackVersion;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import com.cloud.agent.api.UpdateHostScsiDeviceCommand;
import com.cloud.agent.api.UpdateHostHbaDeviceCommand;
import com.cloud.agent.api.UpdateHostLunDeviceCommand;
import com.cloud.agent.api.UpdateHostUsbDeviceCommand;
import com.cloud.agent.api.UpdateHostVhbaDeviceCommand;
import com.cloud.agent.api.ListHostScsiDeviceAnswer;
import com.cloud.agent.api.ListHostHbaDeviceAnswer;
import com.cloud.agent.api.ListHostLunDeviceAnswer;
import com.cloud.agent.api.ListHostUsbDeviceAnswer;
import com.cloud.agent.api.UpdateHostVhbaDeviceAnswer;
import com.cloud.agent.api.UpdateHostHbaDeviceAnswer;
import com.cloud.agent.api.UpdateHostLunDeviceAnswer;
import com.cloud.agent.api.UpdateHostUsbDeviceAnswer;
import com.cloud.agent.api.UpdateHostScsiDeviceAnswer;
import com.cloud.agent.api.ListHostScsiDeviceCommand;
import com.cloud.agent.api.ListHostHbaDeviceCommand;
import com.cloud.agent.api.ListHostLunDeviceCommand;
import com.cloud.agent.api.ListHostUsbDeviceCommand;
import com.cloud.agent.api.CreateVhbaDeviceCommand;
import com.cloud.agent.api.DeleteVhbaDeviceCommand;
import com.cloud.agent.api.ListVhbaDevicesCommand;

import com.cloud.agent.api.CreateVhbaDeviceAnswer;
import com.cloud.agent.api.DeleteVhbaDeviceAnswer;
import com.cloud.agent.api.ListVhbaDevicesAnswer;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckGuestOsMappingAnswer;
import com.cloud.agent.api.CheckGuestOsMappingCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.GetHypervisorGuestOsNamesAnswer;
import com.cloud.agent.api.GetHypervisorGuestOsNamesCommand;
import com.cloud.agent.api.GetVncPortAnswer;
import com.cloud.agent.api.GetVncPortCommand;
import com.cloud.agent.api.ListHostDeviceAnswer;
import com.cloud.agent.api.ListHostDeviceCommand;
import com.cloud.agent.api.PatchSystemVmAnswer;
import com.cloud.agent.api.PatchSystemVmCommand;
import com.cloud.agent.api.proxy.AllowConsoleAccessCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.manager.Commands;
import com.cloud.agent.manager.allocator.HostAllocator;
import com.cloud.alert.Alert;
import com.cloud.alert.AlertManager;
import com.cloud.alert.AlertVO;
import com.cloud.alert.dao.AlertDao;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.ManagementServerJoinDao;
import com.cloud.api.query.dao.StoragePoolJoinDao;
import com.cloud.api.query.vo.ManagementServerJoinVO;
import com.cloud.api.query.vo.StoragePoolJoinVO;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.capacity.dao.CapacityDaoImpl.SummedCapacity;
import com.cloud.cluster.ClusterManager;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.consoleproxy.ConsoleProxyManagementState;
import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.cpu.CPU;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DomainVlanMapVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanDetailsVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.gpu.GPU;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.Host.Type;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorCapabilities;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.info.ConsoleProxyInfo;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.IpAddressManagerImpl;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NetworkAccountDao;
import com.cloud.network.dao.NetworkAccountVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkDomainVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.projects.Project;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.projects.ProjectManager;
import com.cloud.resource.ResourceManager;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOS;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.GuestOSHypervisor;
import com.cloud.storage.GuestOSHypervisorVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.GuestOsCategory;
import com.cloud.storage.ScopeType;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.GuestOSHypervisorDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.secondary.SecondaryStorageVmManager;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.template.TemplateManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.SSHKeyPair;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.User;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.user.dao.UserDao;
import com.cloud.user.dao.UserDataDao;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.PasswordGenerator;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ComponentLifecycle;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.JoinBuilder.JoinType;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.db.UUIDManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.MacAddress;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.nio.TrustAllManager;
import com.cloud.utils.security.CertificateHelper;
import com.cloud.utils.ssh.SSHKeysHelper;
import com.cloud.vm.ConsoleProxyVO;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.InstanceGroupVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.SecondaryStorageVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.dao.ConsoleProxyDao;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.InstanceGroupDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.SecondaryStorageVmDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ManagementServerImpl extends ManagerBase implements ManagementServer, Configurable {
    protected StateMachine2<State, VirtualMachine.Event, VirtualMachine> _stateMachine;

    static final String FOR_SYSTEMVMS = "forsystemvms";
    static final ConfigKey<Integer> vmPasswordLength = new ConfigKey<>("Advanced", Integer.class, "vm.password.length", "6", "Specifies the length of a randomly generated password", false);
    static final ConfigKey<Integer> sshKeyLength = new ConfigKey<>("Advanced", Integer.class, "ssh.key.length", "2048", "Specifies custom SSH key length (bit)", true, ConfigKey.Scope.Global);
    static final ConfigKey<Integer> LicenseCheckInterval = new ConfigKey<>("Advanced", Integer.class,
            "license.check.interval", "1",
            "License check interval (days)", true);
    static final ConfigKey<Boolean> humanReadableSizes = new ConfigKey<>("Advanced", Boolean.class, "display.human.readable.sizes", "true", "Enables outputting human readable byte sizes to logs and usage records.", false, ConfigKey.Scope.Global);
    public static final ConfigKey<String> customCsIdentifier = new ConfigKey<>("Advanced", String.class, "custom.cs.identifier", UUID.randomUUID().toString().split("-")[0].substring(4), "Custom identifier for the cloudstack installation", true, ConfigKey.Scope.Global);
    public static final ConfigKey<Boolean> exposeCloudStackVersionInApiXmlResponse = new ConfigKey<Boolean>("Advanced", Boolean.class, "expose.cloudstack.version.api.xml.response", "true", "Indicates whether ACS version should appear in the root element of an API XML response.", true, ConfigKey.Scope.Global);
    public static final ConfigKey<Boolean> exposeCloudStackVersionInApiListCapabilities = new ConfigKey<Boolean>("Advanced", Boolean.class, "expose.cloudstack.version.api.list.capabilities", "true", "Indicates whether ACS version should show in the listCapabilities API.", true, ConfigKey.Scope.Global);

    private static final VirtualMachine.Type []systemVmTypes = { VirtualMachine.Type.SecondaryStorageVm, VirtualMachine.Type.ConsoleProxy};
    private static final List<HypervisorType> LIVE_MIGRATION_SUPPORTING_HYPERVISORS = List.of(HypervisorType.Hyperv, HypervisorType.KVM,
            HypervisorType.LXC, HypervisorType.Ovm, HypervisorType.Ovm3, HypervisorType.Simulator, HypervisorType.VMware, HypervisorType.XenServer);

    @Inject
    public AccountManager _accountMgr;
    @Inject
    private AgentManager _agentMgr;
    @Inject
    private AlertManager _alertMgr;
    @Inject
    private IPAddressDao _publicIpAddressDao;
    @Inject
    private ConsoleProxyDao _consoleProxyDao;
    @Inject
    private ClusterDao _clusterDao;
    @Inject
    protected VMInstanceDetailsDao _vmInstanceDetailsDao;
    @Inject
    private SecondaryStorageVmDao _secStorageVmDao;
    @Inject
    public EventDao _eventDao;
    @Inject
    private DataCenterDao _dcDao;
    @Inject
    private VlanDao _vlanDao;
    @Inject
    private VlanDetailsDao vlanDetailsDao;
    @Inject
    private AccountVlanMapDao _accountVlanMapDao;
    @Inject
    private PodVlanMapDao _podVlanMapDao;
    @Inject
    private HostDao _hostDao;
    @Inject
    protected HostDetailsDao _detailsDao;
    @Inject
    private UserDao _userDao;
    @Inject
    protected UserVmDao _userVmDao;
    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    private ConfigurationGroupDao _configGroupDao;
    @Inject
    private ConfigurationSubGroupDao _configSubGroupDao;
    @Inject
    private ConsoleProxyManager _consoleProxyMgr;
    @Inject
    private SecondaryStorageVmManager _secStorageVmMgr;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    protected DomainDao _domainDao;
    @Inject
    private AccountDao _accountDao;
    @Inject
    public AlertDao _alertDao;
    @Inject
    private CapacityDao _capacityDao;
    @Inject
    private GuestOSDao _guestOSDao;
    @Inject
    private GuestOSCategoryDao _guestOSCategoryDao;
    @Inject
    private GuestOSHypervisorDao _guestOSHypervisorDao;
    @Inject
    private PrimaryDataStoreDao _poolDao;
    @Inject
    private StoragePoolJoinDao _poolJoinDao;
    @Inject
    protected NetworkDao networkDao;
    @Inject
    private StorageManager _storageMgr;
    @Inject
    private VirtualMachineManager _itMgr;
    @Inject
    private HostPodDao _hostPodDao;
    @Inject
    private VgpuProfileDao vgpuProfileDao;
    @Inject
    private VMInstanceDao _vmInstanceDao;
    @Inject
    private VolumeDao _volumeDao;
    private int _purgeDelay;
    private int _alertPurgeDelay;
    @Inject
    private InstanceGroupDao _vmGroupDao;
    @Inject
    protected SSHKeyPairDao _sshKeyPairDao;
    @Inject
    private LoadBalancerDao _loadbalancerDao;
    @Inject
    private HypervisorCapabilitiesDao _hypervisorCapabilitiesDao;
    private List<HostAllocator> hostAllocators;
    private List<StoragePoolAllocator> _storagePoolAllocators;
    @Inject
    private ResourceTagDao _resourceTagDao;
    @Inject
    private ImageStoreDao _imgStoreDao;
    @Inject
    private ServiceOfferingDetailsDao _serviceOfferingDetailsDao;
    @Inject
    private ProjectManager _projectMgr;
    @Inject
    private ResourceManager _resourceMgr;
    @Inject
    private HighAvailabilityManager _haMgr;
    @Inject
    private HostTagsDao _hostTagsDao;
    @Inject
    protected ConfigDepot _configDepot;
    @Inject
    private UserVmManager _userVmMgr;
    @Inject
    private AccountService _accountService;
    @Inject
    private ServiceOfferingDao _offeringDao;
    @Inject
    private DeploymentPlanningManager _dpMgr;
    @Inject
    private GuestOsDetailsDao _guestOsDetailsDao;
    @Inject
    private KeystoreManager _ksMgr;
    @Inject
    private DpdkHelper dpdkHelper;
    @Inject
    private PrimaryDataStoreDao _primaryDataStoreDao;
    @Inject
    private DataStoreManager dataStoreManager;
    @Inject
    private VolumeDataStoreDao _volumeStoreDao;
    @Inject
    private TemplateDataStoreDao _vmTemplateStoreDao;
    @Inject
    private IpAddressManager _ipAddressMgr;
    @Inject
    private NetworkAccountDao _networkAccountDao;
    @Inject
    private NetworkDomainDao _networkDomainDao;
    @Inject
    private NetworkModel _networkMgr;
    @Inject
    protected VpcDao _vpcDao;
    @Inject
    private DomainVlanMapDao _domainVlanMapDao;
    @Inject
    private NicDao nicDao;
    @Inject
    DomainRouterDao routerDao;
    @Inject
    public UUIDManager uuidMgr;
    @Inject
    protected UserDataDao userDataDao;
    @Inject
    protected VMTemplateDao templateDao;
    @Inject
    protected AnnotationDao annotationDao;
    @Inject
    UserDataManager userDataManager;
    @Inject
    StoragePoolTagsDao storagePoolTagsDao;
    @Inject
    private BackupManager backupManager;
    @Inject
    protected ManagementServerJoinDao managementServerJoinDao;

    @Inject
    private PublicIpQuarantineDao publicIpQuarantineDao;

    @Inject
    ClusterManager _clusterMgr;

    @Inject
    protected AffinityGroupVMMapDao _affinityGroupVMMapDao;
    @Inject
    ResourceLimitService resourceLimitService;
    @Inject
    NsxProviderDao nsxProviderDao;
    @Inject
    NetrisProviderDao netrisProviderDao;
    @Inject
    ExtensionsManager extensionsManager;

    @Inject
    private HAConfigManager haConfigManager;

    @Inject
    private HAConfigDao haConfigDao;

    @Inject
    private HostDetailsDao _hostDetailsDao;

    private LockControllerListener _lockControllerListener;
    private final ScheduledExecutorService _eventExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("EventChecker"));
    private final ScheduledExecutorService _alertExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("AlertChecker"));
    private final ScheduledExecutorService _licenseExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("LicenseCheck"));
    private static final int patchCommandTimeout = 600000;

    private Map<String, String> _configs;

    private Map<String, Boolean> _availableIdsMap;

    private List<UserAuthenticator> _userAuthenticators;
    private List<UserTwoFactorAuthenticator> _userTwoFactorAuthenticators;
    private List<UserAuthenticator> _userPasswordEncoders;
    protected boolean _executeInSequence;

    protected List<DeploymentPlanner> _planners;

    private boolean jsInterpretationEnabled = false;

    private final List<HypervisorType> supportedHypervisors = new ArrayList<>();

    public List<DeploymentPlanner> getPlanners() {
        return _planners;
    }

    public void setPlanners(final List<DeploymentPlanner> planners) {
        _planners = planners;
    }

    protected List<AffinityGroupProcessor> _affinityProcessors;

    public List<AffinityGroupProcessor> getAffinityGroupProcessors() {
        return _affinityProcessors;
    }

    public void setAffinityGroupProcessors(final List<AffinityGroupProcessor> affinityProcessors) {
        _affinityProcessors = affinityProcessors;
    }

    public ManagementServerImpl() {
        setRunLevel(ComponentLifecycle.RUN_LEVEL_APPLICATION_MAINLOOP);
        setStateMachine();
    }

    public List<UserAuthenticator> getUserAuthenticators() {
        return _userAuthenticators;
    }

    public void setUserAuthenticators(final List<UserAuthenticator> authenticators) {
        _userAuthenticators = authenticators;
    }

    public List<UserTwoFactorAuthenticator> getUserTwoFactorAuthenticators() {
        return _userTwoFactorAuthenticators;
    }

    public void setUserTwoFactorAuthenticators(final List<UserTwoFactorAuthenticator> userTwoFactorAuthenticators) {
        _userTwoFactorAuthenticators = userTwoFactorAuthenticators;
    }

    public List<UserAuthenticator> getUserPasswordEncoders() {
        return _userPasswordEncoders;
    }

    public void setUserPasswordEncoders(final List<UserAuthenticator> encoders) {
        _userPasswordEncoders = encoders;
    }

    public List<HostAllocator> getHostAllocators() {
        return hostAllocators;
    }

    public void setHostAllocators(final List<HostAllocator> hostAllocators) {
        this.hostAllocators = hostAllocators;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {

        _configs = _configDao.getConfiguration();

        final String value = _configs.get("event.purge.interval");
        final int cleanup = NumbersUtil.parseInt(value, 60 * 60 * 24); // 1 day.

        _purgeDelay = NumbersUtil.parseInt(_configs.get("event.purge.delay"), 0);
        if (_purgeDelay != 0) {
            _eventExecutor.scheduleAtFixedRate(new EventPurgeTask(), cleanup, cleanup, TimeUnit.SECONDS);
        }

        //Alerts purge configurations
        final int alertPurgeInterval = NumbersUtil.parseInt(_configDao.getValue(Config.AlertPurgeInterval.key()), 60 * 60 * 24); // 1 day.
        _alertPurgeDelay = NumbersUtil.parseInt(_configDao.getValue(Config.AlertPurgeDelay.key()), 0);
        if (_alertPurgeDelay != 0) {
            _alertExecutor.scheduleAtFixedRate(new AlertPurgeTask(), alertPurgeInterval, alertPurgeInterval, TimeUnit.SECONDS);
        }

        if(LicenseCheckInterval.value() > 0) {
            _licenseExecutor.scheduleAtFixedRate(new LicenseCheckTask(), 0, LicenseCheckInterval.value(), TimeUnit.DAYS);
        }

        final String[] availableIds = TimeZone.getAvailableIDs();
        _availableIdsMap = new HashMap<>(availableIds.length);
        for (final String id : availableIds) {
            _availableIdsMap.put(id, true);
        }

        supportedHypervisors.add(HypervisorType.KVM);
        supportedHypervisors.add(HypervisorType.XenServer);

        jsInterpretationEnabled = JsInterpretationEnabled.value();

        return true;
    }

    private void setStateMachine() {
        _stateMachine = VirtualMachine.State.getStateMachine();
    }

    @Override
    public boolean start() {
        logger.info("Startup CloudStack management server...");
        // Set human readable sizes
        NumbersUtil.enableHumanReadableSizes = _configDao.findByName("display.human.readable.sizes").getValue().equals("true");

        if (_lockControllerListener == null) {
            _lockControllerListener = new LockControllerListener(ManagementServerNode.getManagementServerId());
        }

        _clusterMgr.registerListener(_lockControllerListener);

        enableAdminUser("password");

         // Initialize license check and agent control for all hosts


        return true;
    }

    private class LicenseCheckTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                logger.info("Starting license check for all hosts...");
                List<HostVO> hosts = _hostDao.listByType(Host.Type.Routing);

                if (hosts != null && !hosts.isEmpty()) {
                    for (HostVO host : hosts) {
                        try {
                            checkLicense(host);
                        } catch (Exception e) {
                            logger.error("Failed to check license for host: " + host.getId(), e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error in license check task", e);
            }
        }
    }

    private void controlHostAgent(HostVO host, String action) {
        HttpsURLConnection connection = null;
        try {
            String ipAddress = host.getPrivateIpAddress();
            String glueEndpoint = "https://" + ipAddress + ":8080/api/v1/license/controlHostAgent/" + action;

            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());

            URL url = new URL(glueEndpoint);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setDoOutput(true);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/json");

            // 연결 시도 전에 소켓 설정
            System.setProperty("sun.net.client.defaultConnectTimeout", "5000");
            System.setProperty("sun.net.client.defaultReadTimeout", "15000");

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                logger.info("Successfully " + action + "ed agent for host: " + host.getId() + " (IP: " + ipAddress + ")");
            } else {
                logger.error("Failed to " + action + " agent for host: " + host.getId() + " (IP: " + ipAddress + "). Response code: " + responseCode);
            }
        } catch (SocketTimeoutException e) {
            logger.error("Connection timed out while controlling agent for host: " + host.getId() + " (IP: " + host.getPrivateIpAddress() + ")", e);
        } catch (Exception e) {
            logger.error("Error controlling agent for host: " + host.getId() + " (IP: " + host.getPrivateIpAddress() + ")", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    protected Map<String, String> getConfigs() {
        return _configs;
    }

    @Override
    public String generateRandomPassword() {
        final Integer passwordLength = vmPasswordLength.value();
        return PasswordGenerator.generateRandomPassword(passwordLength);
    }

    @Override
    public HostVO getHostBy(final long hostId) {
        return _hostDao.findById(hostId);
    }

    @Override
    public DetailVO findDetail(final long hostId, final String name) {
        return _detailsDao.findDetail(hostId, name);
    }

    @Override
    public long getId() {
        return MacAddress.getMacAddress().toLong();
    }

    protected void checkPortParameters(final String publicPort, final String privatePort, final String privateIp, final String proto) {

        if (!NetUtils.isValidPort(publicPort)) {
            throw new InvalidParameterValueException("publicPort is an invalid value");
        }
        if (!NetUtils.isValidPort(privatePort)) {
            throw new InvalidParameterValueException("privatePort is an invalid value");
        }

        // logger.debug("Checking if " + privateIp +
        // " is a valid private IP address. Guest IP address is: " +
        // _configs.get("guest.ip.network"));
        //
        // if (!NetUtils.isValidPrivateIp(privateIp,
        // _configs.get("guest.ip.network"))) {
        // throw new
        // InvalidParameterValueException("Invalid private ip address");
        // }
        if (!NetUtils.isValidProto(proto)) {
            throw new InvalidParameterValueException("Invalid protocol");
        }
    }

    @Override
    public boolean archiveEvents(final ArchiveEventsCmd cmd) {
        final Account caller = getCaller();
        final List<Long> ids = cmd.getIds();
        boolean result = true;
        List<Long> permittedAccountIds = new ArrayList<>();

        if (_accountService.isNormalUser(caller.getId()) || caller.getType() == Account.Type.PROJECT) {
            permittedAccountIds.add(caller.getId());
        } else {
            final DomainVO domain = _domainDao.findById(caller.getDomainId());
            final List<Long> permittedDomainIds = _domainDao.getDomainChildrenIds(domain.getPath());
            permittedAccountIds = _accountDao.getAccountIdsForDomains(permittedDomainIds);
        }

        final List<EventVO> events = _eventDao.listToArchiveOrDeleteEvents(ids, cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), permittedAccountIds);
        final ControlledEntity[] sameOwnerEvents = events.toArray(new ControlledEntity[events.size()]);
        _accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, sameOwnerEvents);

        if (ids != null && events.size() < ids.size()) {
            return false;
        }
        _eventDao.archiveEvents(events);
        return result;
    }

    @Override
    public boolean downloadEvents(final DownloadEventsCmd cmd) {
        final Account caller = getCaller();
        final List<Long> ids = cmd.getIds();
        boolean result = true;
        List<Long> permittedAccountIds = new ArrayList<Long>();

        if (_accountService.isNormalUser(caller.getId()) || caller.getType() == Account.Type.PROJECT) {
            permittedAccountIds.add(caller.getId());
        } else {
            final DomainVO domain = _domainDao.findById(caller.getDomainId());
            final List<Long> permittedDomainIds = _domainDao.getDomainChildrenIds(domain.getPath());
            permittedAccountIds = _accountDao.getAccountIdsForDomains(permittedDomainIds);
        }

        final List<EventVO> events = _eventDao.listToArchiveOrDeleteEvents(ids, cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), permittedAccountIds);
        final ControlledEntity[] sameOwnerEvents = events.toArray(new ControlledEntity[events.size()]);
        _accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, sameOwnerEvents);

        if (ids != null && events.size() < ids.size()) {
            result = false;
            return result;
        }
        _eventDao.archiveEvents(events);
        return result;
    }

    @Override
    public boolean deleteEvents(final DeleteEventsCmd cmd) {
        final Account caller = getCaller();
        final List<Long> ids = cmd.getIds();
        boolean result = true;
        List<Long> permittedAccountIds = new ArrayList<>();

        if (_accountMgr.isNormalUser(caller.getId()) || caller.getType() == Account.Type.PROJECT) {
            permittedAccountIds.add(caller.getId());
        } else {
            final DomainVO domain = _domainDao.findById(caller.getDomainId());
            final List<Long> permittedDomainIds = _domainDao.getDomainChildrenIds(domain.getPath());
            permittedAccountIds = _accountDao.getAccountIdsForDomains(permittedDomainIds);
        }

        final List<EventVO> events = _eventDao.listToArchiveOrDeleteEvents(ids, cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), permittedAccountIds);
        final ControlledEntity[] sameOwnerEvents = events.toArray(new ControlledEntity[events.size()]);
        _accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, sameOwnerEvents);

        if (ids != null && events.size() < ids.size()) {
            return false;
        }
        for (final EventVO event : events) {
            _eventDao.remove(event.getId());
        }
        return result;
    }

    @Override
    public List<? extends Cluster> searchForClusters(long zoneId, final Long startIndex, final Long pageSizeVal, final String hypervisorType) {
        final Filter searchFilter = new Filter(ClusterVO.class, "id", true, startIndex, pageSizeVal);
        final SearchCriteria<ClusterVO> sc = _clusterDao.createSearchCriteria();

        zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);

        sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        sc.addAnd("hypervisorType", SearchCriteria.Op.EQ, hypervisorType);

        return _clusterDao.search(sc, searchFilter);
    }

    @Override
    public Pair<List<? extends Cluster>, Integer> searchForClusters(final ListClustersCmd cmd) {
        final Object id = cmd.getId();
        final Object name = cmd.getClusterName();
        final Object podId = cmd.getPodId();
        Long zoneId = cmd.getZoneId();
        final Object hypervisorType = cmd.getHypervisorType();
        final Object clusterType = cmd.getClusterType();
        final Object allocationState = cmd.getAllocationState();
        final String keyword = cmd.getKeyword();
        final CPU.CPUArch arch = cmd.getArch();
        final String storageAccessGroup = cmd.getStorageAccessGroup();
        zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);

        final Filter searchFilter = new Filter(ClusterVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        final SearchBuilder<ClusterVO> sb = _clusterDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("podId", sb.entity().getPodId(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("hypervisorType", sb.entity().getHypervisorType(), SearchCriteria.Op.EQ);
        sb.and("clusterType", sb.entity().getClusterType(), SearchCriteria.Op.EQ);
        sb.and("allocationState", sb.entity().getAllocationState(), SearchCriteria.Op.EQ);
        sb.and("arch", sb.entity().getArch(), SearchCriteria.Op.EQ);
        if (storageAccessGroup != null) {
            sb.and().op("storageAccessGroupExact", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.EQ);
            sb.or("storageAccessGroupPrefix", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.or("storageAccessGroupSuffix", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.or("storageAccessGroupMiddle", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.cp();
        }

        final SearchCriteria<ClusterVO> sc = sb.create();
        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (podId != null) {
            sc.setParameters("podId", podId);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        if (hypervisorType != null) {
            String hypervisorStr = (String) hypervisorType;
            String hypervisorSearch = HypervisorType.getType(hypervisorStr).toString();
            sc.setParameters("hypervisorType", hypervisorSearch);
        }

        if (clusterType != null) {
            sc.setParameters("clusterType", clusterType);
        }

        if (allocationState != null) {
            sc.setParameters("allocationState", allocationState);
        }

        if (keyword != null) {
            final SearchCriteria<ClusterVO> ssc = _clusterDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("hypervisorType", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (arch != null) {
            sc.setParameters("arch", arch);
        }

        if (storageAccessGroup != null) {
            sc.setParameters("storageAccessGroupExact", storageAccessGroup);
            sc.setParameters("storageAccessGroupPrefix", storageAccessGroup + ",%");
            sc.setParameters("storageAccessGroupSuffix", "%," + storageAccessGroup);
            sc.setParameters("storageAccessGroupMiddle", "%," + storageAccessGroup + ",%");
        }

        final Pair<List<ClusterVO>, Integer> result = _clusterDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    private HypervisorType getHypervisorType(VMInstanceVO vm, StoragePool srcVolumePool) {
        HypervisorType type = null;
        if (vm == null) {
            StoragePoolVO poolVo = _poolDao.findById(srcVolumePool.getId());
            if (ScopeType.CLUSTER.equals(poolVo.getScope())) {
                Long clusterId = poolVo.getClusterId();
                if (clusterId != null) {
                    ClusterVO cluster = _clusterDao.findById(clusterId);
                    type = cluster.getHypervisorType();
                }
            }

            if (null == type) {
                type = srcVolumePool.getHypervisor();
            }
        } else {
            type = vm.getHypervisorType();
        }
        return type;
    }

    @Override
    public Pair<List<? extends Host>, Integer> searchForServers(final ListHostsCmd cmd) {

        final Long zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), cmd.getZoneId());
        final Object name = cmd.getHostName();
        final Object type = cmd.getType();
        final Object state = cmd.getState();
        final Object pod = cmd.getPodId();
        final Object cluster = cmd.getClusterId();
        final Object id = cmd.getId();
        final Object keyword = cmd.getKeyword();
        final Object resourceState = cmd.getResourceState();
        final Object haHosts = cmd.getHaHost();

        final Pair<List<HostVO>, Integer> result = searchForServers(cmd.getStartIndex(), cmd.getPageSizeVal(), name, type, state, zoneId, pod,
            cluster, id, keyword, resourceState, haHosts, null, null);
        return new Pair<>(result.first(), result.second());
    }

    protected Pair<Boolean, List<HostVO>> filterUefiHostsForMigration(List<HostVO> allHosts, List<HostVO> filteredHosts, VirtualMachine vm) {
        VMInstanceDetailVO vmInstanceDetailVO = _vmInstanceDetailsDao.findDetail(vm.getId(), ApiConstants.BootType.UEFI.toString());
        if (vmInstanceDetailVO != null &&
                (ApiConstants.BootMode.LEGACY.toString().equalsIgnoreCase(vmInstanceDetailVO.getValue()) ||
                        ApiConstants.BootMode.SECURE.toString().equalsIgnoreCase(vmInstanceDetailVO.getValue()))) {
            logger.info(" Live Migration of UEFI enabled VM : " + vm.getInstanceName() + " is not supported");
            if (CollectionUtils.isEmpty(filteredHosts)) {
                filteredHosts = new ArrayList<>(allHosts);
            }
            List<DetailVO> details = _detailsDao.findByName(Host.HOST_UEFI_ENABLE);
            List<Long> uefiEnabledHosts = details.stream().filter(x -> Boolean.TRUE.toString().equalsIgnoreCase(x.getValue())).map(DetailVO::getHostId).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(uefiEnabledHosts)) {
                return new Pair<>(false, null);
            }
            filteredHosts.removeIf(host -> !uefiEnabledHosts.contains(host.getId()));
            return new Pair<>(!filteredHosts.isEmpty(), filteredHosts);
        }
        return new Pair<>(true, filteredHosts);
    }

    private void validateVmForHostMigration(VirtualMachine vm) {
        final Account caller = getCaller();
        if (!_accountMgr.isRootAdmin(caller.getId())) {
            if (logger.isDebugEnabled()) {
                logger.debug("Caller is not a root admin, permission denied to migrate the VM");
            }
            throw new PermissionDeniedException("No permission to migrate VM, Only Root Admin can migrate a VM!");
        }

        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find the VM with given id");
        }

        if (vm.getState() != State.Running) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM is not running, cannot migrate the vm" + vm);
            }
            final InvalidParameterValueException ex = new InvalidParameterValueException("VM is not Running, cannot " + "migrate the vm with specified id");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        if (!LIVE_MIGRATION_SUPPORTING_HYPERVISORS.contains(vm.getHypervisorType())) {
            if (logger.isDebugEnabled()) {
                logger.debug(vm + " is not XenServer/VMware/KVM/Ovm/Hyperv/Ovm3, cannot migrate this VM.");
            }
            throw new InvalidParameterValueException("Unsupported Hypervisor Type for VM migration, we support " + "XenServer/VMware/KVM/Ovm/Hyperv/Ovm3 only");
        }

        if (VirtualMachine.Type.User.equals(vm.getType()) && HypervisorType.LXC.equals(vm.getHypervisorType())) {
            throw new InvalidParameterValueException("Unsupported Hypervisor Type for User VM migration, we support XenServer/VMware/KVM/Ovm/Hyperv/Ovm3 only");
        }
    }

    @Override
    public Ternary<Pair<List<? extends Host>, Integer>, List<? extends Host>, Map<Host, Boolean>> listHostsForMigrationOfVM(final Long vmId, final Long startIndex, final Long pageSize,
                                                                                                                            final String keyword) {
        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        return listHostsForMigrationOfVM(vm, startIndex, pageSize, keyword, Collections.emptyList());
    }

    protected boolean zoneWideVolumeRequiresStorageMotion(PrimaryDataStore volumeDataStore,
            final Host sourceHost, final Host destinationHost) {
        if (volumeDataStore.isManaged() && sourceHost.getClusterId() != destinationHost.getClusterId()) {
            PrimaryDataStoreDriver driver = (PrimaryDataStoreDriver)volumeDataStore.getDriver();
            // Depends on the storage driver. For some storages simply
            // changing volume access to host should work: grant access on destination
            // host and revoke access on source host. For others, we still have to perform a storage migration
            // because we need to create a new target volume and copy the contents of the
            // source volume into it before deleting the source volume.
            return !driver.zoneWideVolumesAvailableWithoutClusterMotion();
        }
        return false;
    }

    @Override
    public Ternary<Pair<List<? extends Host>, Integer>, List<? extends Host>, Map<Host, Boolean>> listHostsForMigrationOfVM(final VirtualMachine vm, final Long startIndex, final Long pageSize,
            final String keyword, List<VirtualMachine> vmList) {

        validateVmForHostMigration(vm);

        if (_serviceOfferingDetailsDao.findDetail(vm.getServiceOfferingId(), GPU.Keys.pciDevice.toString()) != null) {
            logger.info(" Live Migration of GPU enabled VM : " + vm.getInstanceName() + " is not supported");
            // Return empty list.
            return new Ternary<>(new Pair<>(new ArrayList<>(), 0),
                    new ArrayList<>(), new HashMap<>());
        }

        final long srcHostId = vm.getHostId();
        final Host srcHost = _hostDao.findById(srcHostId);
        if (srcHost == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to find the host with id: " + srcHostId + " of this VM:" + vm);
            }
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find the host (with specified id) of VM with specified id");
            ex.addProxyObject(String.valueOf(srcHostId), "hostId");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }
        String srcHostVersion = srcHost.getHypervisorVersion();
        if (HypervisorType.KVM.equals(srcHost.getHypervisorType()) && srcHostVersion == null) {
            srcHostVersion = "";
        }

        // Check if the vm can be migrated with storage.
        boolean canMigrateWithStorage = false;

        List<HypervisorType> hypervisorTypes = Arrays.asList(new HypervisorType[]{HypervisorType.VMware, HypervisorType.KVM});
        if (VirtualMachine.Type.User.equals(vm.getType()) || hypervisorTypes.contains(vm.getHypervisorType())) {
            canMigrateWithStorage = _hypervisorCapabilitiesDao.isStorageMotionSupported(srcHost.getHypervisorType(), srcHostVersion);
        }

        // Check if the vm is using any disks on local storage.
        final VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vm, null, _offeringDao.findById(vm.getId(), vm.getServiceOfferingId()), null, null);
        final List<VolumeVO> volumes = _volumeDao.findCreatedByInstance(vmProfile.getId());
        boolean usesLocal = false;
        for (final VolumeVO volume : volumes) {
            final DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
            final DiskProfile diskProfile = new DiskProfile(volume, diskOffering, vmProfile.getHypervisorType());
            if (diskProfile.useLocalStorage()) {
                usesLocal = true;
                break;
            }
        }

        if (!canMigrateWithStorage && usesLocal) {
            throw new InvalidParameterValueException("Unsupported operation, VM uses Local storage, cannot migrate");
        }

        validateVgpuProfileForVmMigration(vmProfile);

        final Type hostType = srcHost.getType();
        Pair<List<HostVO>, Integer> allHostsPair = null;
        List<HostVO> allHosts = null;
        List<HostVO> filteredHosts = null;
        final Map<Host, Boolean> requiresStorageMotion = new HashMap<>();
        DataCenterDeployment plan = null;
        if (canMigrateWithStorage) {
            Long podId = !VirtualMachine.Type.User.equals(vm.getType()) ? srcHost.getPodId() : null;
            allHostsPair = searchForServers(startIndex, pageSize, null, hostType, null, srcHost.getDataCenterId(), podId, null, null, keyword,
                null, null, srcHost.getHypervisorType(), null, srcHost.getId());
            allHosts = allHostsPair.first();
            filteredHosts = new ArrayList<>(allHosts);

            for (final VolumeVO volume : volumes) {
                PrimaryDataStore primaryDataStore = (PrimaryDataStore)dataStoreManager.getPrimaryDataStore(volume.getPoolId());
                Long volClusterId = primaryDataStore.getClusterId();

                for (Iterator<HostVO> iterator = filteredHosts.iterator(); iterator.hasNext();) {
                    final Host host = iterator.next();
                    String hostVersion = host.getHypervisorVersion();
                    if (HypervisorType.KVM.equals(host.getHypervisorType()) && hostVersion == null) {
                        hostVersion = "";
                    }

                    if (volClusterId != null) {
                        if (primaryDataStore.isLocal() || !host.getClusterId().equals(volClusterId) || usesLocal) {
                            if (primaryDataStore.isManaged()) {
                                // At the time being, we do not support storage migration of a volume from managed storage unless the managed storage
                                // is at the zone level and the source and target storage pool is the same.
                                // If the source and target storage pool is the same and it is managed, then we still have to perform a storage migration
                                // because we need to create a new target volume and copy the contents of the source volume into it before deleting the
                                // source volume.
                                iterator.remove();
                            } else {
                                boolean hostSupportsStorageMigration = (srcHostVersion != null && srcHostVersion.equals(hostVersion)) ||
                                        _hypervisorCapabilitiesDao.isStorageMotionSupported(host.getHypervisorType(), hostVersion);
                                if (hostSupportsStorageMigration && hasSuitablePoolsForVolume(volume, host, vmProfile)) {
                                    requiresStorageMotion.put(host, true);
                                } else {
                                    iterator.remove();
                                }
                            }
                        }
                    } else {
                        if (zoneWideVolumeRequiresStorageMotion(primaryDataStore, srcHost, host)) {
                            requiresStorageMotion.put(host, true);
                        }
                    }
                }
            }

            if (CollectionUtils.isEmpty(filteredHosts)) {
                return new Ternary<>(new Pair<>(allHosts, allHostsPair.second()), new ArrayList<>(), new HashMap<>());
            }
            plan = new DataCenterDeployment(srcHost.getDataCenterId(), podId, null, null, null, null);
        } else {
            final Long cluster = srcHost.getClusterId();
            if (logger.isDebugEnabled()) {
                logger.debug("Searching for all hosts in cluster " + cluster + " for migrating VM " + vm);
            }
            allHostsPair = searchForServers(startIndex, pageSize, null, hostType, null, null, null, cluster, null, keyword, null, null, null,
                null, srcHost.getId());
            allHosts = allHostsPair.first();
            plan = new DataCenterDeployment(srcHost.getDataCenterId(), srcHost.getPodId(), srcHost.getClusterId(), null, null, null);
        }

        final Pair<List<? extends Host>, Integer> otherHosts = new Pair<>(allHosts, allHostsPair.second());
        Pair<Boolean, List<HostVO>> uefiFilteredResult = filterUefiHostsForMigration(allHosts, filteredHosts, vm);
        if (!uefiFilteredResult.first()) {
            return new Ternary<>(otherHosts, new ArrayList<>(), new HashMap<>());
        }
        filteredHosts = uefiFilteredResult.second();

        List<Host> suitableHosts = new ArrayList<>();
        final ExcludeList excludes = new ExcludeList();
        excludes.addHost(srcHostId);

        if (dpdkHelper.isVMDpdkEnabled(vm.getId())) {
            excludeNonDPDKEnabledHosts(plan, excludes);
        }

        // call affinitygroup chain
        final long vmGroupCount = _affinityGroupVMMapDao.countAffinityGroupsForVm(vm.getId());

        if (vmGroupCount > 0) {
            for (final AffinityGroupProcessor processor : _affinityProcessors) {
                processor.process(vmProfile, plan, excludes, vmList);
            }
        }

        if (vm.getType() == VirtualMachine.Type.User || vm.getType() == VirtualMachine.Type.DomainRouter) {
            final DataCenterVO dc = _dcDao.findById(srcHost.getDataCenterId());
            _dpMgr.checkForNonDedicatedResources(vmProfile, dc, excludes);
        }

        for (final HostAllocator allocator : hostAllocators) {
            if (CollectionUtils.isNotEmpty(filteredHosts)) {
                suitableHosts = allocator.allocateTo(vmProfile, plan, Host.Type.Routing, excludes, filteredHosts, HostAllocator.RETURN_UPTO_ALL, false);
            } else {
                suitableHosts = allocator.allocateTo(vmProfile, plan, Host.Type.Routing, excludes, HostAllocator.RETURN_UPTO_ALL, false);
            }

            if (CollectionUtils.isNotEmpty(suitableHosts)) {
                break;
            }
        }

        _dpMgr.reorderHostsByPriority(plan.getHostPriorities(), suitableHosts);

        if (suitableHosts.isEmpty()) {
            logger.warn("No suitable hosts found.");
        } else {
            logger.debug("Hosts having capacity and suitable for migration: {}", suitableHosts);
        }

        // Only list hosts of the same architecture as the source Host in a multi-arch zone
        if (!suitableHosts.isEmpty()) {
            List<CPU.CPUArch> clusterArchs = ApiDBUtils.listZoneClustersArchs(vm.getDataCenterId());
            if (CollectionUtils.isNotEmpty(clusterArchs) && clusterArchs.size() > 1) {
                suitableHosts = suitableHosts.stream().filter(h -> h.getArch() == srcHost.getArch()).collect(Collectors.toList());
            }
        }

        return new Ternary<>(otherHosts, suitableHosts, requiresStorageMotion);
    }

    private void validateVgpuProfileForVmMigration(final VirtualMachineProfile vmProfile) {
        // Validate if the VM is using a vGPU profile that supports migration.
        ServiceOffering serviceOffering = vmProfile.getServiceOffering();
        if (serviceOffering.getVgpuProfileId() != null) {
            VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(serviceOffering.getVgpuProfileId());
            if (vgpuProfile == null || "passthrough".equals(vgpuProfile.getName())) {
                throw new InvalidParameterValueException("Unsupported operation, VM uses host passthrough, cannot migrate");
            }
        }
    }

    /**
     * Add non DPDK enabled hosts to the avoid list
     */
    private void excludeNonDPDKEnabledHosts(DataCenterDeployment plan, ExcludeList excludes) {
        long dataCenterId = plan.getDataCenterId();
        Long clusterId = plan.getClusterId();
        Long podId = plan.getPodId();
        List<HostVO> hosts = _hostDao.listAllUpAndEnabledNonHAHosts(Type.Routing, clusterId, podId, dataCenterId, null);
        for (HostVO host : hosts) {
            if (!dpdkHelper.isHostDpdkEnabled(host.getId())) {
                excludes.addHost(host.getId());
            }
        }
    }

    private boolean hasSuitablePoolsForVolume(final VolumeVO volume, final Host host, final VirtualMachineProfile vmProfile) {
        final DiskOfferingVO diskOffering = _diskOfferingDao.findById(volume.getDiskOfferingId());
        final DiskProfile diskProfile = new DiskProfile(volume, diskOffering, vmProfile.getHypervisorType());
        final DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), host.getId(), null, null);
        final ExcludeList avoid = new ExcludeList();

        for (final StoragePoolAllocator allocator : _storagePoolAllocators) {
            final List<StoragePool> poolList = allocator.allocateToPool(diskProfile, vmProfile, plan, avoid, 1);
            if (poolList != null && !poolList.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForMigrationOfVolume(final Long volumeId, String keyword) {

        Pair<List<? extends StoragePool>, List<? extends StoragePool>> allPoolsAndSuitablePoolsPair = listStoragePoolsForMigrationOfVolumeInternal(volumeId, null, null, null, null, false, true, false, keyword);
        List<? extends StoragePool> allPools = allPoolsAndSuitablePoolsPair.first();
        List<? extends StoragePool> suitablePools = allPoolsAndSuitablePoolsPair.second();
        List<StoragePool> avoidPools = new ArrayList<>();

        final VolumeVO volume = _volumeDao.findById(volumeId);
        StoragePool srcVolumePool = _poolDao.findById(volume.getPoolId());
        if (srcVolumePool.getParent() != 0L) {
            StoragePool datastoreCluster = _poolDao.findById(srcVolumePool.getParent());
            avoidPools.add(datastoreCluster);
        }
        abstractDataStoreClustersList((List<StoragePool>) allPools, new ArrayList<>());
        abstractDataStoreClustersList((List<StoragePool>) suitablePools, avoidPools);
        return new Pair<>(allPools, suitablePools);
    }

    @Override
    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForSystemMigrationOfVolume(final Long volumeId, Long newDiskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, boolean keepSourceStoragePool, boolean bypassStorageTypeCheck) {
        return listStoragePoolsForMigrationOfVolumeInternal(volumeId, newDiskOfferingId, newSize, newMinIops, newMaxIops, keepSourceStoragePool, bypassStorageTypeCheck, true, null);
    }

    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForMigrationOfVolumeInternal(final Long volumeId, Long newDiskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, boolean keepSourceStoragePool, boolean bypassStorageTypeCheck, boolean bypassAccountCheck, String keyword) {
        if (!bypassAccountCheck) {
            final Account caller = getCaller();
            if (!_accountMgr.isRootAdmin(caller.getId())) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Caller is not a root admin, permission denied to migrate the volume");
                }
                throw new PermissionDeniedException("No permission to migrate volume, only root admin can migrate a volume");
            }
        }

        final VolumeVO volume = _volumeDao.findById(volumeId);
        if (volume == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find volume with" + " specified id.");
            ex.addProxyObject(volumeId.toString(), "volumeId");
            throw ex;
        }

        Long diskOfferingId = volume.getDiskOfferingId();
        if (newDiskOfferingId != null) {
            diskOfferingId = newDiskOfferingId;
        }

        // Volume must be attached to an instance for live migration.
        List<? extends StoragePool> allPools = new ArrayList<>();
        List<? extends StoragePool> suitablePools = new ArrayList<>();

        // Volume must be in Ready state to be migrated.
        if (!Volume.State.Ready.equals(volume.getState())) {
            logger.info("Volume " + volume + " must be in ready state for migration.");
            return new Pair<>(allPools, suitablePools);
        }

        final Long instanceId = volume.getInstanceId();
        VMInstanceVO vm = null;
        if (instanceId != null) {
            vm = _vmInstanceDao.findById(instanceId);
        }

        if (vm == null) {
            logger.info("Volume " + volume + " isn't attached to any vm. Looking for storage pools in the " + "zone to which this volumes can be migrated.");
        } else if (vm.getState() != State.Running) {
            logger.info("Volume " + volume + " isn't attached to any running vm. Looking for storage pools in the " + "cluster to which this volumes can be migrated.");
        } else {
            logger.info("Volume " + volume + " is attached to any running vm. Looking for storage pools in the " + "cluster to which this volumes can be migrated.");
            boolean storageMotionSupported = false;
            // Check if the underlying hypervisor supports storage motion.
            final Long hostId = vm.getHostId();
            if (hostId != null) {
                final HostVO host = _hostDao.findById(hostId);
                HypervisorCapabilitiesVO capabilities = null;
                if (host != null) {
                    capabilities = _hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(host.getHypervisorType(), host.getHypervisorVersion());
                } else {
                    logger.error("Details of the host on which the vm " + vm + ", to which volume " + volume + " is " + "attached, couldn't be retrieved.");
                }

                if (capabilities != null) {
                    storageMotionSupported = capabilities.isStorageMotionSupported();
                } else {
                    logger.error("Capabilities for host " + host + " couldn't be retrieved.");
                }
            }

            if (!storageMotionSupported) {
                logger.info("Volume " + volume + " is attached to a running vm and the hypervisor doesn't support" + " storage motion.");
                return new Pair<>(allPools, suitablePools);
            }
        }

        StoragePool srcVolumePool = _poolDao.findById(volume.getPoolId());
        HypervisorType hypervisorType = getHypervisorType(vm, srcVolumePool);
        Pair<Host, List<Cluster>> hostClusterPair = getVolumeVmHostClusters(srcVolumePool, vm, hypervisorType);
        Host vmHost = hostClusterPair.first();
        List<Cluster> clusters = hostClusterPair.second();
        allPools = getAllStoragePoolCompatibleWithVolumeSourceStoragePool(srcVolumePool, hypervisorType, clusters, keyword);
        ExcludeList avoid = new ExcludeList();
        if (!keepSourceStoragePool) {
            allPools.remove(srcVolumePool);
            avoid.addPool(srcVolumePool.getId());
        }
        if (vm != null) {
            suitablePools = findAllSuitableStoragePoolsForVm(volume, diskOfferingId, newSize, newMinIops, newMaxIops, vm, vmHost, avoid,
                    CollectionUtils.isNotEmpty(clusters) ? clusters.get(0) : null, hypervisorType, bypassStorageTypeCheck, keyword);
        } else {
            suitablePools = findAllSuitableStoragePoolsForDetachedVolume(volume, diskOfferingId, allPools);
        }
        removeDataStoreClusterParents((List<StoragePool>) allPools);
        removeDataStoreClusterParents((List<StoragePool>) suitablePools);
        return new Pair<>(allPools, suitablePools);
    }

    private void removeDataStoreClusterParents(List<StoragePool> storagePools) {
        Predicate<StoragePool> childDatastorePredicate = pool -> (pool.getParent() != 0);
        List<StoragePool> childDatastores = storagePools.stream().filter(childDatastorePredicate).collect(Collectors.toList());
        if (!childDatastores.isEmpty()) {
            Set<Long> parentStoragePoolIds = childDatastores.stream().map(mo -> mo.getParent()).collect(Collectors.toSet());
            for (Long parentStoragePoolId : parentStoragePoolIds) {
                StoragePool parentPool = _poolDao.findById(parentStoragePoolId);
                storagePools.remove(parentPool);
            }
        }
    }

        private void abstractDataStoreClustersList(List<StoragePool> storagePools, List<StoragePool> avoidPools) {
        Predicate<StoragePool> childDatastorePredicate = pool -> (pool.getParent() != 0);
        List<StoragePool> childDatastores = storagePools.stream().filter(childDatastorePredicate).collect(Collectors.toList());
        storagePools.removeAll(avoidPools);
        if (!childDatastores.isEmpty()) {
            storagePools.removeAll(childDatastores);
            Set<Long> parentStoragePoolIds = childDatastores.stream().map(mo -> mo.getParent()).collect(Collectors.toSet());
            for (Long parentStoragePoolId : parentStoragePoolIds) {
                StoragePool parentPool = _poolDao.findById(parentStoragePoolId);
                if (!storagePools.contains(parentPool) && !avoidPools.contains(parentPool))
                    storagePools.add(parentPool);
            }
        }
    }

    private Pair<Host, List<Cluster>> getVolumeVmHostClusters(StoragePool srcVolumePool, VirtualMachine vm, HypervisorType hypervisorType) {
        Host host = null;
        List<Cluster> clusters = new ArrayList<>();
        Long clusterId = srcVolumePool.getClusterId();
        if (vm != null) {
            Long hostId = vm.getHostId();
            if (hostId == null) {
                hostId = vm.getLastHostId();
            }
            if (hostId != null) {
                host = _hostDao.findById(hostId);
            }
        }
        if (clusterId == null && host != null) {
            clusterId = host.getClusterId();
        }
        if (clusterId != null && vm != null) {
            clusters.add(_clusterDao.findById(clusterId));
        } else {
            clusters.addAll(_clusterDao.listByDcHyType(srcVolumePool.getDataCenterId(), hypervisorType.toString()));
        }
        return new Pair<>(host, clusters);
    }

    /**
     * This method looks for all storage pools that are compatible with the given volume.
     * <ul>
     *  <li>We will look for storage systems that are zone wide.</li>
     *  <li>We also all storage available filtering by data center, pod and cluster as the current storage pool used by the given volume.</li>
     * </ul>
     */
    private List<? extends StoragePool> getAllStoragePoolCompatibleWithVolumeSourceStoragePool(StoragePool srcVolumePool, HypervisorType hypervisorType, List<Cluster> clusters, String keyword) {
        List<StoragePoolVO> storagePools = new ArrayList<>();
        List<StoragePoolVO> zoneWideStoragePools = _poolDao.findZoneWideStoragePoolsByHypervisor(srcVolumePool.getDataCenterId(), hypervisorType, keyword);
        if (CollectionUtils.isNotEmpty(zoneWideStoragePools)) {
            storagePools.addAll(zoneWideStoragePools);
        }
        if (CollectionUtils.isNotEmpty(clusters)) {
            List<Long> clusterIds = clusters.stream().map(Cluster::getId).collect(Collectors.toList());
            List<StoragePoolVO> clusterAndLocalStoragePools = _poolDao.findPoolsInClusters(clusterIds, keyword);
            if (CollectionUtils.isNotEmpty(clusterAndLocalStoragePools)) {
                storagePools.addAll(clusterAndLocalStoragePools);
            }
        }

        return storagePools;
    }

    /**
     *  Looks for all suitable storage pools to allocate the given volume.
     *  We take into account the service offering of the VM and volume to find suitable storage pools. It is also excluded from the search the current storage pool used by the volume.
     *  We use {@link StoragePoolAllocator} to look for possible storage pools to allocate the given volume. We will look for possible local storage poosl even if the volume is using a shared storage disk offering.
     *
     *  Side note: the idea behind this method is to provide power for administrators of manually overriding deployments defined by CloudStack.
     */
    private List<StoragePool> findAllSuitableStoragePoolsForVm(final VolumeVO volume, Long diskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, VMInstanceVO vm, Host vmHost, ExcludeList avoid, Cluster srcCluster, HypervisorType hypervisorType, boolean bypassStorageTypeCheck, String keyword) {
        List<StoragePool> suitablePools = new ArrayList<>();
        Long clusterId = null;
        Long podId = null;
        if (srcCluster != null) {
            clusterId = srcCluster.getId();
            podId = srcCluster.getPodId();
        }
        DataCenterDeployment plan = new DataCenterDeployment(volume.getDataCenterId(), podId, clusterId,
                null, null, null, null);
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        // OfflineVmwareMigration: vm might be null here; deal!

        DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
        DiskProfile diskProfile = new DiskProfile(volume, diskOffering, hypervisorType);
        if (!Objects.equals(volume.getDiskOfferingId(), diskOfferingId)) {
            diskProfile.setSize(newSize);
            diskProfile.setMinIops(newMinIops);
            diskProfile.setMaxIops(newMaxIops);
        }

        for (StoragePoolAllocator allocator : _storagePoolAllocators) {
            List<StoragePool> pools = allocator.allocateToPool(diskProfile, profile, plan, avoid, StoragePoolAllocator.RETURN_UPTO_ALL, bypassStorageTypeCheck, keyword);
            if (CollectionUtils.isEmpty(pools)) {
                continue;
            }
            for (StoragePool pool : pools) {
                boolean isLocalPoolSameHostAsVmHost = pool.isLocal() &&
                        (vmHost == null || StringUtils.equals(vmHost.getPrivateIpAddress(), pool.getHostAddress()));
                if (isLocalPoolSameHostAsVmHost || pool.isShared()) {
                    suitablePools.add(pool);
                }
            }
        }
        return suitablePools;
    }

    private List<StoragePool> findAllSuitableStoragePoolsForDetachedVolume(Volume volume, Long diskOfferingId, List<? extends StoragePool> allPools) {
        List<StoragePool> suitablePools = new ArrayList<>();
        if (CollectionUtils.isEmpty(allPools)) {
            return  suitablePools;
        }
        DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
        List<String> tags = new ArrayList<>();
        String[] tagsArray = diskOffering.getTagsArray();
        if (tagsArray != null && tagsArray.length > 0) {
            tags = Arrays.asList(tagsArray);
        }
        Long[] poolIds = allPools.stream().map(StoragePool::getId).toArray(Long[]::new);
        List<StoragePoolJoinVO> pools = _poolJoinDao.searchByIds(poolIds);
        for (StoragePoolJoinVO storagePool : pools) {
            if (StoragePoolStatus.Up.equals(storagePool.getStatus()) &&
                    (CollectionUtils.isEmpty(tags) || tags.contains(storagePool.getTag()))) {
                Optional<? extends StoragePool> match = allPools.stream().filter(x -> x.getId() == storagePool.getId()).findFirst();
                match.ifPresent(suitablePools::add);
            }
        }
        return suitablePools;
    }

    private Pair<List<HostVO>, Integer> searchForServers(final Long startIndex, final Long pageSize, final Object name, final Object type,
        final Object state, final Object zone, final Object pod, final Object cluster, final Object id, final Object keyword,
        final Object resourceState, final Object haHosts, final Object hypervisorType, final Object hypervisorVersion, final Object... excludes) {
        final Filter searchFilter = new Filter(HostVO.class, "id", Boolean.TRUE, startIndex, pageSize);

        final SearchBuilder<HostVO> sb = _hostDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("idsNotIn", sb.entity().getId(), SearchCriteria.Op.NOTIN);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.LIKE);
        sb.and("status", sb.entity().getStatus(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("podId", sb.entity().getPodId(), SearchCriteria.Op.EQ);
        sb.and("clusterId", sb.entity().getClusterId(), SearchCriteria.Op.EQ);
        sb.and("resourceState", sb.entity().getResourceState(), SearchCriteria.Op.EQ);
        sb.and("hypervisorType", sb.entity().getHypervisorType(), SearchCriteria.Op.EQ);
        sb.and("hypervisorVersion", sb.entity().getHypervisorVersion(), SearchCriteria.Op.GTEQ);

        final String haTag = _haMgr.getHaTag();
        SearchBuilder<HostTagVO> hostTagSearch = null;
        if (haHosts != null && StringUtils.isNotEmpty(haTag)) {
            hostTagSearch = _hostTagsDao.createSearchBuilder();
            if ((Boolean)haHosts) {
                hostTagSearch.and().op("tag", hostTagSearch.entity().getTag(), SearchCriteria.Op.EQ);
            } else {
                hostTagSearch.and().op("tag", hostTagSearch.entity().getTag(), SearchCriteria.Op.NEQ);
                hostTagSearch.or("tagNull", hostTagSearch.entity().getTag(), SearchCriteria.Op.NULL);
            }

            hostTagSearch.cp();
            sb.join("hostTagSearch", hostTagSearch, sb.entity().getId(), hostTagSearch.entity().getHostId(), JoinBuilder.JoinType.LEFTOUTER);
        }

        final SearchCriteria<HostVO> sc = sb.create();

        if (keyword != null) {
            final SearchCriteria<HostVO> ssc = _hostDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("status", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("type", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (excludes != null && excludes.length > 0) {
            sc.setParameters("idsNotIn", excludes);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }
        if (type != null) {
            sc.setParameters("type", "%" + type);
        }
        if (state != null) {
            sc.setParameters("status", state);
        }
        if (zone != null) {
            sc.setParameters("dataCenterId", zone);
        }
        if (pod != null) {
            sc.setParameters("podId", pod);
        }
        if (cluster != null) {
            sc.setParameters("clusterId", cluster);
        }
        if (hypervisorType != null) {
            sc.setParameters("hypervisorType", hypervisorType);
        }
        if (hypervisorVersion != null) {
            sc.setParameters("hypervisorVersion", hypervisorVersion);
        }

        if (resourceState != null) {
            sc.setParameters("resourceState", resourceState);
        }

        if (haHosts != null && StringUtils.isNotEmpty(haTag)) {
            sc.setJoinParameters("hostTagSearch", "tag", haTag);
        }

        return _hostDao.searchAndCount(sc, searchFilter);
    }

    @Override
    public Pair<List<? extends Pod>, Integer> searchForPods(final ListPodsByCmd cmd) {
        final String podName = cmd.getPodName();
        final Long id = cmd.getId();
        Long zoneId = cmd.getZoneId();
        final Object keyword = cmd.getKeyword();
        final Object allocationState = cmd.getAllocationState();
        final String storageAccessGroup = cmd.getStorageAccessGroup();

        zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);

        final Filter searchFilter = new Filter(HostPodVO.class, "dataCenterId", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchBuilder<HostPodVO> sb = _hostPodDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("allocationState", sb.entity().getAllocationState(), SearchCriteria.Op.EQ);
        if (storageAccessGroup != null) {
            sb.and().op("storageAccessGroupExact", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.EQ);
            sb.or("storageAccessGroupPrefix", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.or("storageAccessGroupSuffix", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.or("storageAccessGroupMiddle", sb.entity().getStorageAccessGroups(), SearchCriteria.Op.LIKE);
            sb.cp();
        }

        final SearchCriteria<HostPodVO> sc = sb.create();
        if (keyword != null) {
            final SearchCriteria<HostPodVO> ssc = _hostPodDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("description", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (podName != null) {
            sc.setParameters("name", podName);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        if (allocationState != null) {
            sc.setParameters("allocationState", allocationState);
        }

        if (storageAccessGroup != null) {
            sc.setParameters("storageAccessGroupExact", storageAccessGroup);
            sc.setParameters("storageAccessGroupPrefix", storageAccessGroup + ",%");
            sc.setParameters("storageAccessGroupSuffix", "%," + storageAccessGroup);
            sc.setParameters("storageAccessGroupMiddle", "%," + storageAccessGroup + ",%");
        }

        final Pair<List<HostPodVO>, Integer> result = _hostPodDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }


    @Override
    public ListResponse<ListHostUsbDevicesResponse> listHostUsbDevices(ListHostUsbDevicesCmd cmd) {
        Long id = cmd.getId();
        HostVO hostVO = _hostDao.findById(id);
        if (hostVO == null) {
            throw new CloudRuntimeException("Host not found with ID: " + id);
        }

        ListHostUsbDeviceCommand usbCmd = new ListHostUsbDeviceCommand(id);
        Answer answer;
        try {
            answer = _agentMgr.send(hostVO.getId(), usbCmd);
        } catch (Exception e) {
            String errorMsg = "Error sending ListHostUsbDeviceCommand: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new CloudRuntimeException(errorMsg, e);
        }

        if (answer == null) {
            throw new CloudRuntimeException("Answer is null");
        }
        if (!answer.getResult()) {
            String errorDetails = (answer.getDetails() != null) ? answer.getDetails()
                    : "No additional details available";
            String errorMsg = "Answer result is false. Details: " + errorDetails;
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }
        if (!(answer instanceof ListHostUsbDeviceAnswer)) {
            throw new CloudRuntimeException("Answer is not an instance of listHostUsbDeviceAnswer");
        }

        ListHostUsbDeviceAnswer usbAnswer = (ListHostUsbDeviceAnswer) answer;
        if (!usbAnswer.isSuccessMessage()) {
            throw new IllegalArgumentException("Failed to list VM USB objects.");
        }

        List<ListHostUsbDevicesResponse> responses = new ArrayList<>();
        ListResponse<ListHostUsbDevicesResponse> listResponse = new ListResponse<>();

        List<String> hostDevicesTexts = usbAnswer.getHostDevicesTexts();

        List<String> hostDevicesNames = new ArrayList<>();
        List<String> pciDescriptions = new ArrayList<>();

        for (String hostDevicesText : hostDevicesTexts) {
            String[] parts = hostDevicesText.split(": ", 2);
            if (parts.length == 2) {
                hostDevicesNames.add(parts[0].trim());
                pciDescriptions.add(parts[1].trim());
            } else {
                logger.warn("Unexpected PCI info format: " + hostDevicesText);
            }
        }

        ListHostUsbDevicesResponse response = new ListHostUsbDevicesResponse();
        response.setHostDevicesNames(hostDevicesNames);
        response.setHostDevicesTexts(pciDescriptions);

        // VM 할당 정보 확인 및 업데이트
        Map<String, String> vmAllocations = new HashMap<>();
        for (String deviceName : hostDevicesNames) {
            // 현재 할당 상태 확인
            String currentAllocation = getDeviceAllocation(id, deviceName);

            if (currentAllocation != null) {
                // 이미 할당된 경우에만 맵에 추가
                vmAllocations.put(deviceName, currentAllocation);
            }
        }
        response.setVmAllocations(vmAllocations);

        responses.add(response);
        listResponse.setResponses(responses);
        return listResponse;
    }

    @Override
    public ListResponse<ListHostLunDevicesResponse> listHostLunDevices(ListHostLunDevicesCmd cmd) {
        Long id = cmd.getId();
        HostVO hostVO = _hostDao.findById(id);
        if (hostVO == null) {
            throw new CloudRuntimeException("Host not found with ID: " + id);
        }

        ListHostLunDeviceCommand lunCmd = new ListHostLunDeviceCommand(id);
        Answer answer;
        try {
            answer = _agentMgr.send(hostVO.getId(), lunCmd);
        } catch (Exception e) {
            String errorMsg = "Error sending ListHostLunDeviceCommand: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new CloudRuntimeException(errorMsg, e);
        }

        validateLunAnswer(answer);
        ListHostLunDeviceAnswer lunAnswer = (ListHostLunDeviceAnswer) answer;

        ListResponse<ListHostLunDevicesResponse> listResponse = new ListResponse<>();
        List<ListHostLunDevicesResponse> responses = new ArrayList<>();

        ListHostLunDevicesResponse response = createResponse(lunAnswer, id);
        responses.add(response);

        listResponse.setResponses(responses);
        return listResponse;
    }

    private ListHostLunDevicesResponse createResponse(ListHostLunDeviceAnswer lunAnswer, Long hostId) {
        ListHostLunDevicesResponse response = new ListHostLunDevicesResponse();

        List<String> deviceNames = new ArrayList<>();
        List<String> deviceDescriptions = new ArrayList<>();
        List<Boolean> hasPartitions = lunAnswer.getHasPartitions();

        for (int i = 0; i < lunAnswer.getHostDevicesTexts().size(); i++) {
            String deviceText = lunAnswer.getHostDevicesTexts().get(i);
            String deviceName = lunAnswer.getHostDevicesNames().get(i);
            boolean hasPartition = hasPartitions.get(i);

            deviceNames.add(deviceName);
            deviceDescriptions.add(deviceText);
            response.addPartitionInfo(deviceName, hasPartition);
        }

        response.setHostDevicesNames(deviceNames);
        response.setHostDevicesTexts(deviceDescriptions);

        // LUN 디바이스별 할당된 VM ID 조회
        Map<String, String> vmAllocations = new HashMap<>();
        for (String deviceName : deviceNames) {
            String allocation = getDeviceAllocation(hostId, deviceName);
            if (allocation != null) {
                vmAllocations.put(deviceName, allocation);
            }
        }
        response.setVmAllocations(vmAllocations);

        return response;
    }

    @Override
    public ListResponse<ListHostScsiDevicesResponse> listHostScsiDevices(ListHostScsiDevicesCmd cmd) {
        Long id = cmd.getId();
        HostVO hostVO = _hostDao.findById(id);
        if (hostVO == null) {
            throw new CloudRuntimeException("Host not found with ID: " + id);
        }

        ListHostScsiDeviceCommand scsiCmd = new ListHostScsiDeviceCommand(id);
        Answer answer;
        try {
            answer = _agentMgr.send(hostVO.getId(), scsiCmd);
        } catch (Exception e) {
            String errorMsg = "Error sending ListHostScsiDeviceCommand: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new CloudRuntimeException(errorMsg, e);
        }

        validateScsiAnswer(answer);
        ListHostScsiDeviceAnswer scsiAnswer = (ListHostScsiDeviceAnswer) answer;

        ListResponse<ListHostScsiDevicesResponse> listResponse = new ListResponse<>();
        List<ListHostScsiDevicesResponse> responses = new ArrayList<>();

        ListHostScsiDevicesResponse response = createResponse(scsiAnswer, id);
        responses.add(response);

        listResponse.setResponses(responses);
        return listResponse;
    }

    private void validateAnswer(Answer answer) {
        if (answer == null) {
            throw new CloudRuntimeException("Answer is null");
        }
        if (!answer.getResult()) {
            String errorDetails = answer.getDetails() != null ? answer.getDetails()
                              : "No additional details available";
            throw new CloudRuntimeException("Answer result is false. Details: " + errorDetails);
        }
    }

    private void validateScsiAnswer(Answer answer) {
        validateAnswer(answer);
        if (!(answer instanceof ListHostScsiDeviceAnswer)) {
            throw new CloudRuntimeException("Answer is not an instance of ListHostScsiDeviceAnswer");
        }
    }

    private void validateLunAnswer(Answer answer) {
        validateAnswer(answer);
        if (!(answer instanceof ListHostLunDeviceAnswer)) {
            throw new CloudRuntimeException("Answer is not an instance of ListHostLunDeviceAnswer");
        }
    }

    private ListHostScsiDevicesResponse createResponse(ListHostScsiDeviceAnswer scsiAnswer, Long hostId) {
        ListHostScsiDevicesResponse response = new ListHostScsiDevicesResponse();

        List<String> deviceNames = new ArrayList<>();
        List<String> deviceDescriptions = new ArrayList<>();
        List<Boolean> hasPartitions = scsiAnswer.getHasPartitions();

        for (int i = 0; i < scsiAnswer.getHostDevicesTexts().size(); i++) {
            String deviceText = scsiAnswer.getHostDevicesTexts().get(i);
            String deviceName = scsiAnswer.getHostDevicesNames().get(i);
            boolean hasPartition = hasPartitions.get(i);

            deviceNames.add(deviceName);
            deviceDescriptions.add(deviceText);
            response.addPartitionInfo(deviceName, hasPartition);
        }

        response.setHostDevicesNames(deviceNames);
        response.setHostDevicesTexts(deviceDescriptions);

        // LUN 디바이스별 할당된 VM ID 조회
        Map<String, String> vmAllocations = new HashMap<>();
        for (String deviceName : deviceNames) {
            String allocation = getDeviceAllocation(hostId, deviceName);
            if (allocation != null) {
                vmAllocations.put(deviceName, allocation);
            }
        }
        response.setVmAllocations(vmAllocations);

        return response;
    }

    @Override
    public ListResponse<ListHostDevicesResponse> listHostDevices(ListHostDevicesCmd cmd) {
        Long id = cmd.getId();
        HostVO hostVO = _hostDao.findById(id);
        if (hostVO == null) {
            throw new CloudRuntimeException("Host not found with ID: " + id);
        }

        ListHostDeviceCommand pciCmd = new ListHostDeviceCommand(id);
        Answer answer;
        try {
            answer = _agentMgr.send(hostVO.getId(), pciCmd);
        } catch (Exception e) {
            String errorMsg = "Error sending ListHostDevicesCommand: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new CloudRuntimeException(errorMsg, e);
        }

        if (answer == null) {
            throw new CloudRuntimeException("Answer is null");
        }
        if (!answer.getResult()) {
            String errorDetails = (answer.getDetails() != null) ? answer.getDetails()
                    : "No additional details available";
            String errorMsg = "Answer result is false. Details: " + errorDetails;
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }
        if (!(answer instanceof ListHostDeviceAnswer)) {
            throw new CloudRuntimeException("Answer is not an instance of listHostDeviceAnswer");
        }

        ListHostDeviceAnswer pciAnswer = (ListHostDeviceAnswer) answer;
        if (!pciAnswer.isSuccessMessage()) {
            throw new IllegalArgumentException("Failed to list VM PCI objects.");
        }

        List<ListHostDevicesResponse> responses = new ArrayList<>();
        ListResponse<ListHostDevicesResponse> listResponse = new ListResponse<>();

        List<String> hostDevicesTexts = pciAnswer.getHostDevicesTexts();

        List<String> hostDevicesNames = new ArrayList<>();
        List<String> pciDescriptions = new ArrayList<>();

        for (String hostDevicesText : hostDevicesTexts) {
            String[] parts = hostDevicesText.split(": ", 2);
            if (parts.length == 2) {
                hostDevicesNames.add(parts[0].trim());
                pciDescriptions.add(parts[1].trim());
            } else {
                logger.warn("Unexpected PCI info format: " + hostDevicesText);
            }
        }

        ListHostDevicesResponse response = new ListHostDevicesResponse();
        response.setHostDevicesNames(hostDevicesNames);
        response.setHostDevicesTexts(pciDescriptions);

        // VM 할당 정보 확인 및 업데이트
        Map<String, String> vmAllocations = new HashMap<>();
        for (String deviceName : hostDevicesNames) {
            // 현재 할당 상태 확인
            String currentAllocation = getDeviceAllocation(id, deviceName);

            if (currentAllocation != null) {
                // 이미 할당된 경우에만 맵에 추가
                vmAllocations.put(deviceName, currentAllocation);
            }
        }
        response.setVmAllocations(vmAllocations);

        responses.add(response);
        listResponse.setResponses(responses);
        return listResponse;
    }

    @Override
    public ListResponse<ListHostHbaDevicesResponse> listHostHbaDevices(ListHostHbaDevicesCmd cmd) {
        Long id = cmd.getId();
        HostVO hostVO = _hostDao.findById(id);
        if (hostVO == null) {
            throw new CloudRuntimeException("Host not found with ID: " + id);
        }

        ListHostHbaDeviceCommand hbaCmd = new ListHostHbaDeviceCommand(id);
        Answer answer;
        try {
            answer = _agentMgr.send(hostVO.getId(), hbaCmd);
        } catch (Exception e) {
            String errorMsg = "Error sending ListHostUsbDeviceCommand: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new CloudRuntimeException(errorMsg, e);
        }

        if (answer == null) {
            throw new CloudRuntimeException("Answer is null");
        }
        if (!answer.getResult()) {
            String errorDetails = (answer.getDetails() != null) ? answer.getDetails()
                    : "No additional details available";
            String errorMsg = "Answer result is false. Details: " + errorDetails;
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }
        if (!(answer instanceof ListHostHbaDeviceAnswer)) {
            throw new CloudRuntimeException("Answer is not an instance of listHostHbaDeviceAnswer");
        }

        ListHostHbaDeviceAnswer hbaAnswer = (ListHostHbaDeviceAnswer) answer;
        if (!hbaAnswer.isSuccessMessage()) {
            throw new IllegalArgumentException("Failed to list VM HBA objects.");
        }

        List<ListHostHbaDevicesResponse> responses = new ArrayList<>();
        ListResponse<ListHostHbaDevicesResponse> listResponse = new ListResponse<>();

        List<String> hostDevicesTexts = hbaAnswer.getHostDevicesTexts();
        List<String> hostDevicesNames = hbaAnswer.getHostDevicesNames();
        List<String> deviceTypes = hbaAnswer.getDeviceTypes();
        List<String> parentHbaNames = hbaAnswer.getParentHbaNames();

        ListHostHbaDevicesResponse response = new ListHostHbaDevicesResponse();
        response.setHostDevicesNames(hostDevicesNames);
        response.setHostDevicesTexts(hostDevicesTexts);
        response.setDeviceTypes(deviceTypes);
        response.setParentHbaNames(parentHbaNames);

        // VM 할당 정보 확인 및 업데이트
        Map<String, String> vmAllocations = new HashMap<>();
        for (String deviceName : hostDevicesNames) {
            // 현재 할당 상태 확인
            String currentAllocation = getDeviceAllocation(id, deviceName);

            if (currentAllocation != null) {
                // 이미 할당된 경우에만 맵에 추가
                vmAllocations.put(deviceName, currentAllocation);
            }
        }
        response.setVmAllocations(vmAllocations);

        responses.add(response);
        listResponse.setResponses(responses);
        return listResponse;
    }

   @Override
    public ListResponse<UpdateHostDevicesResponse> updateHostDevices(UpdateHostDevicesCmd cmd) {
        Long hostId = cmd.getHostId();
        String hostDeviceName = cmd.getHostDeviceName();
        Long vmId = cmd.getVirtualMachineId();

        // 호스트 존재 여부 확인
        HostVO hostVO = _hostDao.findById(hostId);
        if (hostVO == null) {
            throw new CloudRuntimeException("Host not found with ID: " + hostId);
        }

        // VM 존재 여부 확인 (vmId가 null이 아닌 경우)
        VMInstanceVO vmInstance = null;
        if (vmId != null) {
            vmInstance = _vmInstanceDao.findById(vmId);
            if (vmInstance == null) {
                throw new CloudRuntimeException("VM not found with ID: " + vmId);
            }
        }

        logger.info("Updating host device allocation - hostId: {}, hostDeviceName: {}, virtualMachineId: {}",
            hostId, hostDeviceName, vmId);

        try {
            DetailVO currentAllocation = _hostDetailsDao.findDetail(hostId, hostDeviceName);

            if (vmId == null) {
                // 디바이스 할당 해제
                if (currentAllocation != null) {
                    String currentVmId = currentAllocation.getValue();
                    VMInstanceVO vm = _vmInstanceDao.findById(Long.parseLong(currentVmId));
                    if (vm != null) {
                        // 해당 VM의 모든 extraconfig 항목 조회
                        List<VMInstanceDetailVO> existingConfigs = _vmInstanceDetailsDao.listDetails(vm.getId());

                        // 현재 디바이스의 설정 찾기 및 제거
                        for (VMInstanceDetailVO detail : existingConfigs) {
                            if (detail.getName().startsWith("extraconfig-") &&
                                detail.getValue().contains(hostDeviceName)) {
                                _vmInstanceDetailsDao.removeDetail(vm.getId(), detail.getName());
                                logger.info("Successfully removed device configuration {} from VM {}",
                                    detail.getName(), vm.getInstanceName());
                                break;
                            }
                        }
                    }
                    // DB에서 해당 디바이스 레코드 삭제
                    _hostDetailsDao.remove(currentAllocation.getId());
                    logger.info("Successfully removed device {} allocation from host {}", hostDeviceName, hostId);
                }
            } else {
               // 새로운 할당
            if (currentAllocation != null) {
                String currentVmId = currentAllocation.getValue();
                VMInstanceVO vm = _vmInstanceDao.findById(Long.parseLong(currentVmId));
                throw new CloudRuntimeException("Device " + hostDeviceName + " is already allocated to VM: " +
                    (vm != null ? vm.getInstanceName() : currentVmId));
            }

            // 다음 사용 가능한 extraconfig 번호 찾기
            List<VMInstanceDetailVO> existingConfigs = _vmInstanceDetailsDao.listDetails(vmInstance.getId());
                int nextConfigNum = 1;
                Set<Integer> usedNums = new HashSet<>();

                for (VMInstanceDetailVO detail : existingConfigs) {
                    if (detail.getName().startsWith("extraconfig-")) {
                        try {
                            int num = Integer.parseInt(detail.getName().split("-")[1]);
                            usedNums.add(num);
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid extraconfig number format: {}", detail.getName());
                        }
                    }
                }

                while (usedNums.contains(nextConfigNum)) {
                    nextConfigNum++;
                }
                logger.info("Successfully added device configuration to VM {} with config number {}",
                    vmInstance.getInstanceName(), nextConfigNum);

                // DB에 할당 정보 저장
                Map<String, String> details = new HashMap<>();
                details.put(hostDeviceName, vmId.toString());
                _hostDetailsDao.persist(hostId, details);
                logger.info("Successfully allocated device {} to VM {} on host {}",
                    hostDeviceName, vmId, hostId);
            }
        } catch (Exception e) {
            logger.error("Error during device allocation/deallocation - hostDeviceName: {}, error: {}",
                hostDeviceName, e.getMessage(), e);
            throw new CloudRuntimeException("Failed to update device allocation: " + e.getMessage(), e);
        }

        // 응답 생성
        ListResponse<UpdateHostDevicesResponse> response = new ListResponse<>();
        List<UpdateHostDevicesResponse> responses = new ArrayList<>();
        UpdateHostDevicesResponse deviceResponse = new UpdateHostDevicesResponse();

        DetailVO allocation = _hostDetailsDao.findDetail(hostId, hostDeviceName);
        deviceResponse.setHostDeviceName(hostDeviceName);
        deviceResponse.setVirtualMachineId(allocation != null ? allocation.getValue() : null);
        deviceResponse.setAllocated(allocation != null && allocation.getValue() != null);

        responses.add(deviceResponse);
        response.setResponses(responses);

        return response;
    }

    // HBA 디바이스의 VM 할당 상태 조회 (다중 할당 지원)
    public String getDeviceAllocation(Long hostId, String deviceName) {
        // HBA 디바이스의 경우 여러 할당이 있을 수 있으므로 첫 번째 할당만 반환
        List<DetailVO> allocations = _hostDetailsDao.findByName(deviceName);
        if (allocations != null && !allocations.isEmpty()) {
            // 현재 호스트의 할당만 필터링
            for (DetailVO allocation : allocations) {
                if (allocation.getHostId() == hostId) {
                    return allocation.getValue();
                }
            }
        }
        return null;
    }

    // VM에 디바이스가 할당될 때 호출되는 메서드
    @Override
        public ListResponse<UpdateHostUsbDevicesResponse> updateHostUsbDevices(UpdateHostUsbDevicesCmd cmd) {
            Long hostId = cmd.getHostId();
            String hostDeviceName = cmd.getHostDeviceName();
            Long vmId = cmd.getVirtualMachineId();
            String xmlConfig = cmd.getXmlConfig();

            // 호스트 존재 여부 확인
            HostVO hostVO = _hostDao.findById(hostId);
            if (hostVO == null) {
                throw new CloudRuntimeException("Host not found with ID: " + hostId);
            }

            // VM 존재 여부 확인 (vmId가 null이 아닌 경우)
            VMInstanceVO vmInstance = null;
            if (vmId != null) {
                vmInstance = _vmInstanceDao.findById(vmId);
                if (vmInstance == null) {
                    throw new CloudRuntimeException("VM not found with ID: " + vmId);
                }
            }

            logger.info("Updating host device allocation - hostId: {}, hostDeviceName: {}, virtualMachineId: {}",
                hostId, hostDeviceName, vmId);

            try {
                DetailVO currentAllocation = _hostDetailsDao.findDetail(hostId, hostDeviceName);
                String vmInternalName = null;
                boolean isAttach = (vmId != null);

                if (!isAttach) {
                    // 디바이스 할당 해제
                    String currentVmId = cmd.getCurrentVmId();
                    if (currentAllocation != null) {
                        // currentVmId가 제공된 경우 해당 VM과 일치하는지 확인
                        if (currentVmId != null && !currentVmId.equals(currentAllocation.getValue())) {
                            throw new CloudRuntimeException("Device is allocated to a different VM");
                        }

                        // VM 인스턴스 이름 가져오기 (currentVmId 우선, 없으면 currentAllocation에서)
                        String vmIdToUse = (currentVmId != null) ? currentVmId : currentAllocation.getValue();
                        VMInstanceVO vm = _vmInstanceDao.findById(Long.parseLong(vmIdToUse));
                        if (vm != null) {
                            vmInternalName = vm.getInstanceName();
                        } else {
                            throw new CloudRuntimeException("VM not found with ID: " + vmIdToUse);
                        }
                    } else {
                        throw new CloudRuntimeException("No allocation found for device: " + hostDeviceName);
                    }
                } else {
                    // 새로운 할당
                    if (currentAllocation != null) {
                        throw new CloudRuntimeException("Device is already allocated to VM: " + currentAllocation.getValue());
                    }
                    vmInternalName = vmInstance.getInstanceName();
                }

                if (vmInternalName == null) {
                    throw new CloudRuntimeException("Unable to get VM instance name");
                }

                // 호스트에 명령 전송
               UpdateHostUsbDeviceCommand usbCmd = new UpdateHostUsbDeviceCommand(vmInternalName, xmlConfig, isAttach);
                Answer answer;
                try {
                    answer = _agentMgr.send(hostVO.getId(), usbCmd);
                } catch (Exception e) {
                    String errorMsg = "Error sending UpdateHostUsbDeviceCommand: " + e.getMessage();
                    logger.error(errorMsg, e);
                    throw new CloudRuntimeException(errorMsg, e);
                }

                if (answer == null) {
                    throw new CloudRuntimeException("Answer is null");
                }
                if (!answer.getResult()) {
                    String errorDetails = (answer.getDetails() != null) ?
                        answer.getDetails() : "No additional details available";
                    throw new CloudRuntimeException("Failed to update USB device. Details: " + errorDetails);
                }
                if (!(answer instanceof UpdateHostUsbDeviceAnswer)) {
                    throw new CloudRuntimeException("Answer is not an instance of UpdateHostUsbDeviceAnswer");
                }

                UpdateHostUsbDeviceAnswer usbAnswer = (UpdateHostUsbDeviceAnswer) answer;
                if (!usbAnswer.isSuccessMessage()) {
                    throw new CloudRuntimeException("Failed to update USB device for VM: " + usbAnswer.getVmName());
                }

                // DB 업데이트
                if (!isAttach) {
                    // 할당 해제
                    if (currentAllocation != null) {
                        _hostDetailsDao.remove(currentAllocation.getId());

                        // VM extraconfig에서 해당 디바이스 설정 제거
                        String vmIdToUse = (cmd.getCurrentVmId() != null) ? cmd.getCurrentVmId() : currentAllocation.getValue();
                        removeDeviceFromVmExtraConfig(Long.parseLong(vmIdToUse), hostDeviceName, xmlConfig);
                    }
                } else {
                    // 새로운 할당
                    DetailVO detail = new DetailVO(hostId, hostDeviceName, vmId.toString());
                    _hostDetailsDao.persist(detail);

                    // VM extraconfig에 디바이스 설정 추가
                    addDeviceToVmExtraConfig(vmInstance.getId(), hostDeviceName, xmlConfig);
                }

                // 응답 생성
                ListResponse<UpdateHostUsbDevicesResponse> response = new ListResponse<>();
                List<UpdateHostUsbDevicesResponse> responses = new ArrayList<>();
                UpdateHostUsbDevicesResponse deviceResponse = new UpdateHostUsbDevicesResponse();

                DetailVO allocation = _hostDetailsDao.findDetail(hostId, hostDeviceName);
                deviceResponse.setHostDeviceName(hostDeviceName);
                deviceResponse.setVirtualMachineId(allocation != null ? allocation.getValue() : null);
                deviceResponse.setAllocated(allocation != null);

                responses.add(deviceResponse);
                response.setResponses(responses);

                return response;

            } catch (Exception e) {
                logger.error("Error during USB device allocation/deallocation - hostDeviceName: {}, error: {}",
                    hostDeviceName, e.getMessage(), e);
                throw new CloudRuntimeException("Failed to update USB device allocation: " + e.getMessage(), e);
            }
        }
        // VM에 디바이스가 할당될 때 호출되는 메서드
        @Override
        public ListResponse<UpdateHostLunDevicesResponse> updateHostLunDevices(UpdateHostLunDevicesCmd cmd) {
            Long hostId = cmd.getHostId();
            String hostDeviceName = cmd.getHostDeviceName();
            Long vmId = cmd.getVirtualMachineId();
            String xmlConfig = cmd.getXmlConfig();

            // 호스트 존재 여부 확인
            HostVO hostVO = _hostDao.findById(hostId);
            if (hostVO == null) {
                throw new CloudRuntimeException("Host not found with ID: " + hostId);
            }

            // VM 존재 여부 확인 (vmId가 null이 아닌 경우)
            VMInstanceVO vmInstance = null;
            if (vmId != null) {
                vmInstance = _vmInstanceDao.findById(vmId);
                if (vmInstance == null) {
                    throw new CloudRuntimeException("VM not found with ID: " + vmId);
                }
            }

            logger.info("Updating host device allocation - hostId: {}, hostDeviceName: {}, virtualMachineId: {}",
                hostId, hostDeviceName, vmId);

            try {
                // 같은 디바이스 이름의 모든 할당을 가져오기
                List<DetailVO> currentAllocations = _hostDetailsDao.findByName(hostDeviceName);
                // 현재 호스트의 할당만 필터링
                currentAllocations = currentAllocations.stream()
                    .filter(allocation -> allocation.getHostId() == hostId)
                    .collect(Collectors.toList());
                String vmInternalName = null;
                boolean isAttach = (vmId != null);

                if (!isAttach) {
                    // 디바이스 할당 해제
                    String currentVmId = cmd.getCurrentVmId();

                    if (!currentAllocations.isEmpty()) {
                        String vmIdToUse = (currentVmId != null) ? currentVmId : currentAllocations.get(0).getValue();

                        if (currentVmId != null) {
                            // 특정 VM에 대한 할당이 있는지 확인
                            boolean found = false;
                            for (DetailVO allocation : currentAllocations) {
                                if (allocation.getValue().equals(currentVmId)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                logger.warn("Device {} is not allocated to the specified VM {}", hostDeviceName, currentVmId);
                                // 특정 VM에 할당되지 않았다면 첫 번째 할당을 사용
                                vmIdToUse = currentAllocations.get(0).getValue();
                            }
                        }

                        if (vmIdToUse != null && !vmIdToUse.isEmpty()) {
                            try {
                                VMInstanceVO vm = _vmInstanceDao.findById(Long.parseLong(vmIdToUse));
                                if (vm != null) {
                                    vmInternalName = vm.getInstanceName();
                                } else {
                                    logger.warn("VM not found with ID: {}", vmIdToUse);
                                }
                            } catch (NumberFormatException e) {
                                logger.error("Invalid VM ID format: {}", vmIdToUse, e);
                            } catch (Exception e) {
                                logger.error("Error retrieving VM with ID: {}", vmIdToUse, e);
                            }
                        }
                    } else {
                        logger.warn("No current allocations found for device: {}", hostDeviceName);
                    }
                } else {
                    if (!currentAllocations.isEmpty()) {
                    }

                    if (vmInstance != null) {
                        vmInternalName = vmInstance.getInstanceName();
                    } else {
                        logger.error("vmInstance is null during allocation");
                    }
                }

                if (vmInternalName == null) {
                    throw new CloudRuntimeException("Unable to get VM instance name");
                }

                // 호스트에 명령 전송
               UpdateHostLunDeviceCommand lunCmd = new UpdateHostLunDeviceCommand(vmInternalName, xmlConfig, isAttach, hostDeviceName);
                Answer answer;
                try {
                    answer = _agentMgr.send(hostVO.getId(), lunCmd);
                } catch (Exception e) {
                    String errorMsg = "Error sending UpdateHostLunDeviceCommand: " + e.getMessage();
                    logger.error(errorMsg, e);
                    throw new CloudRuntimeException(errorMsg, e);
                }

                if (answer == null) {
                    throw new CloudRuntimeException("Answer is null");
                }
                if (!answer.getResult()) {
                    String errorDetails = (answer.getDetails() != null) ?
                        answer.getDetails() : "No additional details available";
                    throw new CloudRuntimeException("Failed to update LUN device. Details: " + errorDetails);
                }
                if (!(answer instanceof UpdateHostLunDeviceAnswer)) {
                    throw new CloudRuntimeException("Answer is not an instance of UpdateHostLunDeviceAnswer");
                }

                UpdateHostLunDeviceAnswer lunAnswer = (UpdateHostLunDeviceAnswer) answer;
                if (!lunAnswer.isSuccessMessage()) {
                    throw new CloudRuntimeException("Failed to update LUN device for VM: " + lunAnswer.getVmName());
                }

                // DB 업데이트
                if (!isAttach) {
                    // 할당 해제 - 같은 LUN 디바이스의 특정 VM 할당만 해제
                    String currentVmId = cmd.getCurrentVmId();
                    if (currentVmId != null) {
                        // 특정 VM에 대한 할당을 찾아서 제거
                        for (DetailVO allocation : currentAllocations) {
                            if (allocation.getValue().equals(currentVmId)) {
                                _hostDetailsDao.remove(allocation.getId());

                                // VM extraconfig에서 해당 디바이스 설정 제거
                                removeDeviceFromVmExtraConfig(Long.parseLong(currentVmId), hostDeviceName, xmlConfig);

                                logger.info("Removed specific allocation for device: " + hostDeviceName +
                                          " from VM: " + currentVmId);
                                break;
                            }
                        }
                    } else {
                        // currentVmId가 null이면 첫 번째 할당을 제거 (기존 로직 유지)
                        if (!currentAllocations.isEmpty()) {
                            DetailVO firstAllocation = currentAllocations.get(0);
                            _hostDetailsDao.remove(firstAllocation.getId());

                            // VM extraconfig에서 해당 디바이스 설정 제거
                            removeDeviceFromVmExtraConfig(Long.parseLong(firstAllocation.getValue()), hostDeviceName, xmlConfig);

                            logger.info("Removed first allocation for device: " + hostDeviceName +
                                      " from VM: " + firstAllocation.getValue());
                        }
                    }
                } else {
                    // LUN 디바이스는 같은 이름으로도 여러 할당 가능하도록 허용
                    // 기존 할당이 있어도 새로 할당 (덮어쓰지 않고 추가)
                    if (!currentAllocations.isEmpty()) {
                        logger.info("Device is already allocated to VMs: " +
                                  currentAllocations.stream().map(DetailVO::getValue).collect(Collectors.joining(", ")) +
                                  ". Allowing multiple allocations for same LUN device.");
                    }

                    // LUN 디바이스를 host_details 테이블에 저장 (같은 이름으로도 여러 할당 가능)
                    DetailVO detail = new DetailVO(hostId, hostDeviceName, vmId.toString());
                    _hostDetailsDao.persist(detail);

                    // VM extraconfig에 디바이스 설정 추가
                    addDeviceToVmExtraConfig(vmId, hostDeviceName, xmlConfig);

                    logger.info("Created allocation for device: " + hostDeviceName +
                              " to VM: " + vmId + " (multiple allocations allowed)");
                }

                // 응답 생성
                ListResponse<UpdateHostLunDevicesResponse> response = new ListResponse<>();
                List<UpdateHostLunDevicesResponse> responses = new ArrayList<>();
                UpdateHostLunDevicesResponse deviceResponse = new UpdateHostLunDevicesResponse();

                // 현재 할당 상태 확인 (여러 할당이 있을 수 있음)
                List<DetailVO> currentAllocationsForResponse = _hostDetailsDao.findByName(hostDeviceName);
                // 현재 호스트의 할당만 필터링
                currentAllocationsForResponse = currentAllocationsForResponse.stream()
                    .filter(allocation -> allocation.getHostId() == hostId)
                    .collect(Collectors.toList());
                deviceResponse.setHostDeviceName(hostDeviceName);
                if (!currentAllocationsForResponse.isEmpty()) {
                    // 여러 할당이 있을 수 있으므로 첫 번째 할당을 표시
                    deviceResponse.setVirtualMachineId(currentAllocationsForResponse.get(0).getValue());
                    deviceResponse.setAllocated(true);
                } else {
                    deviceResponse.setVirtualMachineId(null);
                    deviceResponse.setAllocated(false);
                }

                responses.add(deviceResponse);
                response.setResponses(responses);

                return response;

            } catch (Exception e) {
                logger.error("Error during LUN device allocation/deallocation - hostDeviceName: {}, error: {}",
                    hostDeviceName, e.getMessage(), e);
                throw new CloudRuntimeException("Failed to update LUN device allocation: " + e.getMessage(), e);
            }
        }

        @Override
        public ListResponse<UpdateHostScsiDevicesResponse> updateHostScsiDevices(UpdateHostScsiDevicesCmd cmd) {
            Long hostId = cmd.getHostId();
            String hostDeviceName = cmd.getHostDeviceName();
            Long vmId = cmd.getVirtualMachineId();
            String xmlConfig = cmd.getXmlConfig();

            // 호스트 존재 여부 확인
            HostVO hostVO = _hostDao.findById(hostId);
            if (hostVO == null) {
                throw new CloudRuntimeException("Host not found with ID: " + hostId);
            }

            // VM 존재 여부 확인 (vmId가 null이 아닌 경우)
            VMInstanceVO vmInstance = null;
            if (vmId != null) {
                vmInstance = _vmInstanceDao.findById(vmId);
                if (vmInstance == null) {
                    throw new CloudRuntimeException("VM not found with ID: " + vmId);
                }
            }

            logger.info("Updating host device allocation - hostId: {}, hostDeviceName: {}, virtualMachineId: {}",
                hostId, hostDeviceName, vmId);

            try {
                // 같은 디바이스 이름의 모든 할당을 가져오기
                List<DetailVO> currentAllocations = _hostDetailsDao.findByName(hostDeviceName);
                // 현재 호스트의 할당만 필터링
                currentAllocations = currentAllocations.stream()
                    .filter(allocation -> allocation.getHostId() == hostId)
                    .collect(Collectors.toList());
                String vmInternalName = null;
                boolean isAttach = (vmId != null);

                if (!isAttach) {
                    // 디바이스 할당 해제
                    String currentVmId = cmd.getCurrentVmId();

                    String vmIdToUse = null;

                    if (!currentAllocations.isEmpty()) {
                        vmIdToUse = (currentVmId != null) ? currentVmId : currentAllocations.get(0).getValue();

                        if (currentVmId != null) {
                            // 특정 VM에 대한 할당이 있는지 확인
                            boolean found = false;
                            for (DetailVO allocation : currentAllocations) {
                                if (allocation.getValue().equals(currentVmId)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                logger.warn("Device {} is not allocated to the specified VM {}", hostDeviceName, currentVmId);
                                // 특정 VM에 할당되지 않았다면 첫 번째 할당을 사용
                                vmIdToUse = currentAllocations.get(0).getValue();
                            }
                        }
                    } else {
                        // SCSI 디바이스에 직접 할당이 없으면, 같은 물리적 디바이스의 LUN 할당을 찾아보기
                        vmIdToUse = findVmIdFromLunAllocation(hostId, hostDeviceName, currentVmId);
                        if (vmIdToUse != null) {
                        }
                    }

                    if (vmIdToUse != null && !vmIdToUse.isEmpty()) {
                        try {
                            VMInstanceVO vm = _vmInstanceDao.findById(Long.parseLong(vmIdToUse));
                            if (vm != null) {
                                vmInternalName = vm.getInstanceName();
                            } else {
                                logger.warn("VM not found with ID: {}", vmIdToUse);
                            }
                        } catch (NumberFormatException e) {
                            logger.error("Invalid VM ID format: {}", vmIdToUse, e);
                        } catch (Exception e) {
                            logger.error("Error retrieving VM with ID: {}", vmIdToUse, e);
                        }
                    } else {
                        logger.warn("No VM ID found for deallocation of device: {}", hostDeviceName);
                    }
                } else {
                    // SCSI 디바이스는 같은 VM에 여러 개 할당 가능하므로 기존 할당 확인만 하고 계속 진행
                    if (!currentAllocations.isEmpty()) {
                    }

                    if (vmInstance != null) {
                        vmInternalName = vmInstance.getInstanceName();
                    } else {
                        logger.error("vmInstance is null during allocation");
                    }
                }

                if (vmInternalName == null) {
                    throw new CloudRuntimeException("Unable to get VM instance name");
                }

                // 호스트에 명령 전송
               UpdateHostScsiDeviceCommand scsiCmd = new UpdateHostScsiDeviceCommand(vmInternalName, xmlConfig, isAttach);
                Answer answer;
                try {
                    answer = _agentMgr.send(hostVO.getId(), scsiCmd);
                } catch (Exception e) {
                    String errorMsg = "Error sending UpdateHostScsiDeviceCommand: " + e.getMessage();
                    logger.error(errorMsg, e);
                    throw new CloudRuntimeException(errorMsg, e);
                }

                if (answer == null) {
                    throw new CloudRuntimeException("Answer is null");
                }
                if (!answer.getResult()) {
                    String errorDetails = (answer.getDetails() != null) ?
                        answer.getDetails() : "No additional details available";
                    throw new CloudRuntimeException("Failed to update SCSI device. Details: " + errorDetails);
                }
                if (!(answer instanceof UpdateHostScsiDeviceAnswer)) {
                    throw new CloudRuntimeException("Answer is not an instance of UpdateHostScsiDeviceAnswer");
                }

                UpdateHostScsiDeviceAnswer scsiAnswer = (UpdateHostScsiDeviceAnswer) answer;
                if (!scsiAnswer.isSuccessMessage()) {
                    throw new CloudRuntimeException("Failed to update SCSI device for VM: " + scsiAnswer.getVmName());
                }

                // DB 업데이트
                if (!isAttach) {
                    // 할당 해제 - 같은 SCSI 디바이스의 특정 VM 할당만 해제
                    String currentVmId = cmd.getCurrentVmId();
                    if (currentVmId != null) {
                        // 특정 VM에 대한 할당을 찾아서 제거
                        for (DetailVO allocation : currentAllocations) {
                            if (allocation.getValue().equals(currentVmId)) {
                                _hostDetailsDao.remove(allocation.getId());

                                // VM extraconfig에서 해당 디바이스 설정 제거
                                removeDeviceFromVmExtraConfig(Long.parseLong(currentVmId), hostDeviceName, xmlConfig);

                                logger.info("Removed specific allocation for device: " + hostDeviceName +
                                          " from VM: " + currentVmId);
                                break;
                            }
                        }
                    } else {
                        // currentVmId가 null이면 첫 번째 할당을 제거 (기존 로직 유지)
                        if (!currentAllocations.isEmpty()) {
                            DetailVO firstAllocation = currentAllocations.get(0);
                            _hostDetailsDao.remove(firstAllocation.getId());

                            // VM extraconfig에서 해당 디바이스 설정 제거
                            removeDeviceFromVmExtraConfig(Long.parseLong(firstAllocation.getValue()), hostDeviceName, xmlConfig);

                            logger.info("Removed first allocation for device: " + hostDeviceName +
                                      " from VM: " + firstAllocation.getValue());
                        }
                    }
                } else {
                    // SCSI 디바이스는 같은 이름으로도 여러 할당 가능하도록 허용
                    // 기존 할당이 있어도 새로 할당 (덮어쓰지 않고 추가)
                    if (!currentAllocations.isEmpty()) {
                        logger.info("Device is already allocated to VMs: " +
                                  currentAllocations.stream().map(DetailVO::getValue).collect(Collectors.joining(", ")) +
                                  ". Allowing multiple allocations for same SCSI device.");
                    }

                    // SCSI 디바이스를 host_details 테이블에 저장 (같은 이름으로도 여러 할당 가능)
                    DetailVO detail = new DetailVO(hostId, hostDeviceName, vmId.toString());
                    _hostDetailsDao.persist(detail);

                    // VM extraconfig에 디바이스 설정 추가
                    addDeviceToVmExtraConfig(vmId, hostDeviceName, xmlConfig);

                    logger.info("Created allocation for device: " + hostDeviceName +
                              " to VM: " + vmId + " (multiple allocations allowed)");
                }

                // 응답 생성
                ListResponse<UpdateHostScsiDevicesResponse> response = new ListResponse<>();
                List<UpdateHostScsiDevicesResponse> responses = new ArrayList<>();
                UpdateHostScsiDevicesResponse deviceResponse = new UpdateHostScsiDevicesResponse();

                // 현재 할당 상태 확인 (여러 할당이 있을 수 있음)
                List<DetailVO> currentAllocationsForResponse = _hostDetailsDao.findByName(hostDeviceName);
                // 현재 호스트의 할당만 필터링
                currentAllocationsForResponse = currentAllocationsForResponse.stream()
                    .filter(allocation -> allocation.getHostId() == hostId)
                    .collect(Collectors.toList());
                deviceResponse.setHostDeviceName(hostDeviceName);
                if (!currentAllocationsForResponse.isEmpty()) {
                    // 여러 할당이 있을 수 있으므로 첫 번째 할당을 표시
                    deviceResponse.setVirtualMachineId(currentAllocationsForResponse.get(0).getValue());
                    deviceResponse.setAllocated(true);
                } else {
                    deviceResponse.setVirtualMachineId(null);
                    deviceResponse.setAllocated(false);
                }

                responses.add(deviceResponse);
                response.setResponses(responses);

                return response;

            } catch (Exception e) {
                logger.error("Error during SCSI device allocation/deallocation - hostDeviceName: {}, error: {}",
                    hostDeviceName, e.getMessage(), e);
                throw new CloudRuntimeException("Failed to update SCSI device allocation: " + e.getMessage(), e);
            }
        }


        @Override
        public ListResponse<UpdateHostHbaDevicesResponse> updateHostHbaDevices(UpdateHostHbaDevicesCmd cmd) {
            Long hostId = cmd.getHostId();
            String hostDeviceName = cmd.getHostDeviceName();
            Long vmId = cmd.getVirtualMachineId();
            String xmlConfig = cmd.getXmlConfig();

            // 호스트 존재 여부 확인
            HostVO hostVO = _hostDao.findById(hostId);
            if (hostVO == null) {
                throw new CloudRuntimeException("Host not found with ID: " + hostId);
            }

            // 할당/해제 여부 확인
            boolean isAttach = (vmId != null);

            // VM 존재 여부 확인 및 상태 검증
            VMInstanceVO vmInstance = null;
            VMInstanceVO targetVmInstance = null;

            if (isAttach && vmId != null) {
                vmInstance = _vmInstanceDao.findById(vmId);
                if (vmInstance == null) {
                    throw new CloudRuntimeException("VM not found with ID: " + vmId);
                }
                targetVmInstance = vmInstance;
            } else if (!isAttach) {
                // 해제 시에는 currentVmId를 사용
                String currentVmId = cmd.getCurrentVmId();
                if (currentVmId != null) {
                    targetVmInstance = _vmInstanceDao.findById(Long.parseLong(currentVmId));
                    if (targetVmInstance == null) {
                        throw new CloudRuntimeException("VM not found with ID: " + currentVmId);
                    }
                }
            }

            // VM 상태 검증 - Running 상태일 때만 HBA 디바이스 조작 가능
            if (targetVmInstance != null && targetVmInstance.getState() != VirtualMachine.State.Running) {
                throw new CloudRuntimeException("VM must be in Running state for HBA device operations. Current state: " + targetVmInstance.getState());
            }

            // XML 설정 검증
            if (xmlConfig == null || xmlConfig.trim().isEmpty()) {
                throw new CloudRuntimeException("XML configuration is required for HBA device allocation");
            }

            logger.info("Updating host device allocation - hostId: {}, hostDeviceName: {}, virtualMachineId: {}, isAttach: {}",
                hostId, hostDeviceName, vmId, isAttach);

            try {
                // 같은 디바이스 이름의 모든 할당을 가져오기
                List<DetailVO> currentAllocations = _hostDetailsDao.findByName(hostDeviceName);
                // 현재 호스트의 할당만 필터링
                currentAllocations = currentAllocations.stream()
                    .filter(allocation -> allocation.getHostId() == hostId)
                    .collect(Collectors.toList());
                String vmInternalName = null;

                if (!isAttach) {
                    // 디바이스 할당 해제
                    String currentVmId = cmd.getCurrentVmId();
                    if (!currentAllocations.isEmpty()) {
                        String vmIdToUse = (currentVmId != null) ? currentVmId : currentAllocations.get(0).getValue();
                        if (currentVmId != null) {
                            // 특정 VM에 대한 할당이 있는지 확인
                            boolean found = false;
                            for (DetailVO allocation : currentAllocations) {
                                if (allocation.getValue().equals(currentVmId)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                throw new CloudRuntimeException("Device is not allocated to the specified VM");
                            }
                        }
                        VMInstanceVO vm = _vmInstanceDao.findById(Long.parseLong(vmIdToUse));
                        if (vm != null) {
                            vmInternalName = vm.getInstanceName();
                        }
                    }
                } else {
                    // HBA 디바이스는 같은 VM에 여러 개 할당 가능하므로 기존 할당 확인만 하고 계속 진행
                    if (!currentAllocations.isEmpty()) {
                        logger.info("Device is already allocated to VMs: " +
                                  currentAllocations.stream().map(DetailVO::getValue).collect(Collectors.joining(", ")) +
                                  ". Allowing multiple HBA allocation to same VM.");
                    }
                    vmInternalName = vmInstance.getInstanceName();
                }

                if (vmInternalName == null) {
                    throw new CloudRuntimeException("Unable to get VM instance name");
                }

                // 호스트에 명령 전송 전 디버그 로그 (XML 포함)
                String compactXml = xmlConfig != null ? xmlConfig.replaceAll("\\s+", " ") : "";
                logger.info("Sending UpdateHostHbaDeviceCommand: vmName=" + vmInternalName +
                    ", hostDeviceName=" + hostDeviceName + ", isAttach=" + isAttach + ", xmlConfig=" + compactXml);

               UpdateHostHbaDeviceCommand hbaCmd = new UpdateHostHbaDeviceCommand(vmInternalName, xmlConfig, isAttach);
                Answer answer;
                try {
                    answer = _agentMgr.send(hostVO.getId(), hbaCmd);
                } catch (Exception e) {
                    String errorMsg = "Error sending UpdateHostHbaDeviceCommand: " + e.getMessage();
                    logger.error(errorMsg, e);
                    throw new CloudRuntimeException(errorMsg, e);
                }

                if (answer == null) {
                    throw new CloudRuntimeException("Answer is null");
                }
                if (!answer.getResult()) {
                    String errorDetails = (answer.getDetails() != null) ?
                        answer.getDetails() : "No additional details available";
                    throw new CloudRuntimeException("Failed to update HBA device. Details: " + errorDetails);
                }
                if (!(answer instanceof UpdateHostHbaDeviceAnswer)) {
                    throw new CloudRuntimeException("Answer is not an instance of UpdateHostHbaDeviceAnswer");
                }

                UpdateHostHbaDeviceAnswer hbaAnswer = (UpdateHostHbaDeviceAnswer) answer;
                logger.info("Received UpdateHostHbaDeviceAnswer: success=" + hbaAnswer.isSuccessMessage() +
                    ", vmName=" + hbaAnswer.getVmName() + ", details=" + (hbaAnswer.getDetails() != null ? hbaAnswer.getDetails() : "") );
                if (!hbaAnswer.isSuccessMessage()) {
                    String agentDetails = hbaAnswer.getDetails() != null ? hbaAnswer.getDetails() : "No additional details available";
                    throw new CloudRuntimeException("Failed to update HBA device for VM: " + hbaAnswer.getVmName() + ". Details: " + agentDetails);
                }

                // DB 업데이트
                if (!isAttach) {
                    // 할당 해제 - 같은 HBA 디바이스의 특정 VM 할당만 해제
                    String currentVmId = cmd.getCurrentVmId();
                    if (currentVmId != null) {
                        // 특정 VM에 대한 할당을 찾아서 제거
                        for (DetailVO allocation : currentAllocations) {
                            if (allocation.getValue().equals(currentVmId)) {
                                _hostDetailsDao.remove(allocation.getId());

                                // VM extraconfig에서 해당 디바이스 설정 제거
                                removeDeviceFromVmExtraConfig(Long.parseLong(currentVmId), hostDeviceName, xmlConfig);

                                logger.info("Removed specific allocation for device: " + hostDeviceName +
                                          " from VM: " + currentVmId);
                                break;
                            }
                        }
                    } else {
                        // currentVmId가 null이면 첫 번째 할당을 제거 (기존 로직 유지)
                        if (!currentAllocations.isEmpty()) {
                            DetailVO firstAllocation = currentAllocations.get(0);
                            _hostDetailsDao.remove(firstAllocation.getId());

                            // VM extraconfig에서 해당 디바이스 설정 제거
                            removeDeviceFromVmExtraConfig(Long.parseLong(firstAllocation.getValue()), hostDeviceName, xmlConfig);

                            logger.info("Removed first allocation for device: " + hostDeviceName +
                                      " from VM: " + firstAllocation.getValue());
                        }
                    }
                } else {
                    // HBA 디바이스는 같은 이름으로도 여러 할당 가능하도록 허용
                    // 기존 할당이 있어도 새로 할당 (덮어쓰지 않고 추가)
                    if (!currentAllocations.isEmpty()) {
                        logger.info("Device is already allocated to VMs: " +
                                  currentAllocations.stream().map(DetailVO::getValue).collect(Collectors.joining(", ")) +
                                  ". Allowing multiple allocations for same HBA device.");
                    }

                    // HBA 디바이스를 host_details 테이블에 저장 (같은 이름으로도 여러 할당 가능)
                    DetailVO detail = new DetailVO(hostId, hostDeviceName, vmId.toString());
                    _hostDetailsDao.persist(detail);

                    // VM extraconfig에 디바이스 설정 추가
                    addDeviceToVmExtraConfig(vmId, hostDeviceName, xmlConfig);

                    logger.info("Created allocation for device: " + hostDeviceName +
                              " to VM: " + vmId + " (multiple allocations allowed)");
                }

                // 응답 생성
                ListResponse<UpdateHostHbaDevicesResponse> response = new ListResponse<>();
                List<UpdateHostHbaDevicesResponse> responses = new ArrayList<>();
                UpdateHostHbaDevicesResponse deviceResponse = new UpdateHostHbaDevicesResponse();

                // 현재 할당 상태 확인 (여러 할당이 있을 수 있음)
                List<DetailVO> currentAllocationsForResponse = _hostDetailsDao.findByName(hostDeviceName);
                // 현재 호스트의 할당만 필터링
                currentAllocationsForResponse = currentAllocationsForResponse.stream()
                    .filter(allocation -> allocation.getHostId() == hostId)
                    .collect(Collectors.toList());
                deviceResponse.setHostDeviceName(hostDeviceName);
                if (!currentAllocationsForResponse.isEmpty()) {
                    // 여러 할당이 있을 수 있으므로 첫 번째 할당을 표시
                    deviceResponse.setVirtualMachineId(currentAllocationsForResponse.get(0).getValue());
                    deviceResponse.setAllocated(true);
                } else {
                    deviceResponse.setVirtualMachineId(null);
                    deviceResponse.setAllocated(false);
                }

                responses.add(deviceResponse);
                response.setResponses(responses);
                return response;

            } catch (Exception e) {
                logger.error("Error during HBA device allocation/deallocation - hostDeviceName: {}, error: {}",
                    hostDeviceName, e.getMessage(), e);
                throw new CloudRuntimeException("Failed to update HBA device allocation: " + e.getMessage(), e);
            }
        }

        @Override
        public ListResponse<CreateVhbaDeviceResponse> createVhbaDevice(CreateVhbaDeviceCmd cmd) {
            Long hostId = cmd.getHostId();
            String parentHbaName = cmd.getParentHbaName();
            String wwnn = cmd.getWwnn();
            String wwpn = cmd.getWwpn();
            String vhbaName = cmd.getVhbaName();
            String xmlContent = cmd.getXmlContent();

            logger.info("createVhbaDevice 호출됨 - hostId: {}, parentHbaName: {}, vhbaName: {}, wwnn: {}, wwpn: {}",
                hostId, parentHbaName, vhbaName, wwnn, wwpn);

            // 1. 호스트 존재 여부 확인
            HostVO hostVO = _hostDao.findById(hostId);
            if (hostVO == null) {
                String errorMsg = "Host not found with ID: " + hostId;
                logger.error(errorMsg);
                throw new CloudRuntimeException(errorMsg);
            }

            // 2. 필수 파라미터 검증
            if (parentHbaName == null || parentHbaName.trim().isEmpty()) {
                String errorMsg = "Parent HBA name is required";
                logger.error(errorMsg);
                throw new CloudRuntimeException(errorMsg);
            }

            if (vhbaName == null || vhbaName.trim().isEmpty()) {
                String errorMsg = "vHBA name is required";
                logger.error(errorMsg);
                throw new CloudRuntimeException(errorMsg);
            }

            logger.info("호스트 정보 - ID: {}, 이름: {}, 상태: {}", hostVO.getId(), hostVO.getName(), hostVO.getStatus());

            // 3. CreateVhbaDeviceCommand 생성
            CreateVhbaDeviceCommand hbaCmd = new CreateVhbaDeviceCommand(hostId, parentHbaName, wwnn, wwpn, vhbaName, xmlContent);

            logger.info("CreateVhbaDeviceCommand 생성 완료 - hostId: {}, parentHbaName: {}, vhbaName: {}",
                hbaCmd.getHostId(), hbaCmd.getParentHbaName(), hbaCmd.getVhbaName());

            // 4. 에이전트로 명령 전송
            Answer answer;
            try {
                logger.info("에이전트로 명령 전송 시작 - hostId: {}", hostVO.getId());
                answer = _agentMgr.send(hostVO.getId(), hbaCmd);
                logger.info("에이전트로부터 응답 수신");
            } catch (Exception e) {
                String errorMsg = "Error sending CreateVhbaDeviceCommand: " + e.getMessage();
                logger.error(errorMsg, e);
                throw new CloudRuntimeException(errorMsg, e);
            }

            // 5. 응답 검증
            if (answer == null) {
                String errorMsg = "Answer is null";
                logger.error(errorMsg);
                throw new CloudRuntimeException(errorMsg);
            }

            logger.info("응답 결과: {}, 상세: {}", answer.getResult(), answer.getDetails());

            if (!answer.getResult()) {
                String errorDetails = (answer.getDetails() != null) ? answer.getDetails()
                        : "No additional details available";
                String errorMsg = "Answer result is false. Details: " + errorDetails;
                logger.error(errorMsg);
                throw new CloudRuntimeException(errorMsg);
            }

            if (!(answer instanceof CreateVhbaDeviceAnswer)) {
                String errorMsg = "Answer is not an instance of CreateVhbaDeviceAnswer. Actual type: " + answer.getClass().getSimpleName();
                logger.error(errorMsg);
                throw new CloudRuntimeException(errorMsg);
            }

            // 6. 응답 처리
            CreateVhbaDeviceAnswer hbaAnswer = (CreateVhbaDeviceAnswer) answer;
            logger.info("vHBA 생성 결과 - 성공: {}, vHBA 이름: {}, 생성된 디바이스: {}",
                hbaAnswer.isSuccess(), hbaAnswer.getVhbaName(), hbaAnswer.getCreatedDeviceName());

            List<CreateVhbaDeviceResponse> responses = new ArrayList<>();
            ListResponse<CreateVhbaDeviceResponse> listResponse = new ListResponse<>();

            CreateVhbaDeviceResponse response = new CreateVhbaDeviceResponse();
            response.setVhbaName(hbaAnswer.getVhbaName());
            response.setDetails(hbaAnswer.getCreatedDeviceName());
            response.setSuccess(hbaAnswer.isSuccess());

            responses.add(response);
            listResponse.setResponses(responses);

            logger.info("createVhbaDevice 완료 - 응답 생성 완료");
            return listResponse;
        }

        @Override
    public ListResponse<UpdateHostVhbaDevicesResponse> updateHostVhbaDevices(UpdateHostVhbaDevicesCmd cmd) {
        Long hostId = cmd.getHostId();
        String hostDeviceName = cmd.getHostDeviceName();
        Long vmId = cmd.getVirtualMachineId();
        String xmlConfig = cmd.getXmlConfig();

        // 호스트 존재 여부 확인
        HostVO hostVO = _hostDao.findById(hostId);
        if (hostVO == null) {
            throw new CloudRuntimeException("Host not found with ID: " + hostId);
        }

        // VM 존재 여부 확인 (vmId가 null이 아닌 경우)
        VMInstanceVO vmInstance = null;
        if (vmId != null) {
            vmInstance = _vmInstanceDao.findById(vmId);
            if (vmInstance == null) {
                throw new CloudRuntimeException("VM not found with ID: " + vmId);
            }
        }

        logger.info("Updating vHBA device allocation - hostId: {}, hostDeviceName: {}, virtualMachineId: {}",
            hostId, hostDeviceName, vmId);

        try {
            // 현재 할당 상태 확인 (디바이스 이름으로 모두 조회 후 hostId로 필터)
            List<DetailVO> currentAllocations = _hostDetailsDao.findByName(hostDeviceName);
            if (currentAllocations != null) {
                currentAllocations = currentAllocations.stream()
                    .filter(allocation -> allocation.getHostId() == hostId)
                    .collect(java.util.stream.Collectors.toList());
            }
            String vmInternalName = null;
            boolean isAttach = (vmId != null);

            if (!isAttach) {
                // 디바이스 할당 해제
                String currentVmId = cmd.getCurrentVmId();
                if (currentAllocations != null && !currentAllocations.isEmpty()) {
                    DetailVO targetAllocation = null;

                    if (currentVmId != null) {
                        // 특정 VM에서 해제
                        for (DetailVO allocation : currentAllocations) {
                            if (currentVmId.equals(allocation.getValue())) {
                                targetAllocation = allocation;
                                break;
                            }
                        }
                    } else {
                        // 첫 번째 할당 해제
                        targetAllocation = currentAllocations.get(0);
                    }

                    if (targetAllocation != null) {
                        VMInstanceVO vm = _vmInstanceDao.findById(Long.parseLong(targetAllocation.getValue()));
                        if (vm != null) {
                            vmInternalName = vm.getInstanceName();
                        }
                    }
                }
            } else {
                // 새로운 할당
                vmInternalName = vmInstance.getInstanceName();
            }

            if (vmInternalName == null) {
                throw new CloudRuntimeException("Unable to get VM instance name");
            }

            // 호스트에 명령 전송
            UpdateHostVhbaDeviceCommand vhbaCmd = new UpdateHostVhbaDeviceCommand(hostId, hostDeviceName, vmInternalName, xmlConfig, isAttach);
            Answer answer;
            try {
                answer = _agentMgr.send(hostVO.getId(), vhbaCmd);
            } catch (Exception e) {
                String errorMsg = "Error sending UpdateHostVhbaDeviceCommand: " + e.getMessage();
                logger.error(errorMsg, e);
                throw new CloudRuntimeException(errorMsg, e);
            }

            if (answer == null) {
                throw new CloudRuntimeException("Answer is null");
            }
            if (!answer.getResult()) {
                String errorDetails = (answer.getDetails() != null) ?
                    answer.getDetails() : "No additional details available";
                throw new CloudRuntimeException("Failed to update vHBA device. Details: " + errorDetails);
            }
            if (!(answer instanceof UpdateHostVhbaDeviceAnswer)) {
                throw new CloudRuntimeException("Answer is not an instance of UpdateHostVhbaDeviceAnswer");
            }

            UpdateHostVhbaDeviceAnswer vhbaAnswer = (UpdateHostVhbaDeviceAnswer) answer;
            if (!vhbaAnswer.isSuccess()) {
                throw new CloudRuntimeException("Failed to update vHBA device for VM: " + vhbaAnswer.getVmName());
            }

            // DB 업데이트
            if (!isAttach) {
                // 할당 해제
                String currentVmId = cmd.getCurrentVmId();
                if (currentVmId != null) {
                    // 특정 VM에서 해제
                    for (DetailVO allocation : currentAllocations) {
                        if (currentVmId.equals(allocation.getValue())) {
                            _hostDetailsDao.remove(allocation.getId());

                            // VM extraconfig에서 해당 디바이스 설정 제거
                            removeDeviceFromVmExtraConfig(Long.parseLong(currentVmId), hostDeviceName, xmlConfig);

                            break;
                        }
                    }
                } else {
                    // 첫 번째 할당 해제
                    if (!currentAllocations.isEmpty()) {
                        DetailVO firstAllocation = currentAllocations.get(0);
                        _hostDetailsDao.remove(firstAllocation.getId());

                        // VM extraconfig에서 해당 디바이스 설정 제거
                        removeDeviceFromVmExtraConfig(Long.parseLong(firstAllocation.getValue()), hostDeviceName, xmlConfig);
                    }
                }
            } else {
                // 새로운 할당 (다중 할당 허용)
                DetailVO detail = new DetailVO(hostId, hostDeviceName, vmId.toString());
                _hostDetailsDao.persist(detail);

                // VM extraconfig에 디바이스 설정 추가
                addDeviceToVmExtraConfig(vmId, hostDeviceName, xmlConfig);
            }

            // 응답 생성
            ListResponse<UpdateHostVhbaDevicesResponse> response = new ListResponse<>();
            List<UpdateHostVhbaDevicesResponse> responses = new ArrayList<>();
            UpdateHostVhbaDevicesResponse deviceResponse = new UpdateHostVhbaDevicesResponse();

            // 현재 할당 상태 조회 (응답은 HBA와 동일하게 단일 VM/플래그로 표기)
            List<DetailVO> allocations = _hostDetailsDao.findByName(hostDeviceName);
            if (allocations != null) {
                allocations = allocations.stream()
                    .filter(allocation -> allocation.getHostId() == hostId)
                    .collect(java.util.stream.Collectors.toList());
            }

            deviceResponse.setVhbaName(hostDeviceName);
            if (allocations != null && !allocations.isEmpty()) {
                deviceResponse.setVirtualMachineId(allocations.get(0).getValue());
                deviceResponse.setIsAttached(true);
            } else {
                deviceResponse.setVirtualMachineId(null);
                deviceResponse.setIsAttached(false);
            }

            responses.add(deviceResponse);
            response.setResponses(responses);

            return response;

        } catch (Exception e) {
            logger.error("Error during vHBA device allocation/deallocation - hostDeviceName: {}, error: {}",
                hostDeviceName, e.getMessage(), e);
            throw new CloudRuntimeException("Failed to update vHBA device allocation: " + e.getMessage(), e);
        }
    }

    @Override
    public ListResponse<ListVhbaDevicesResponse> listVhbaDevices(ListVhbaDevicesCmd cmd) {
        Long hostId = cmd.getHostId();
        String keyword = cmd.getKeyword();

        HostVO hostVO = _hostDao.findById(hostId);
        if (hostVO == null) {
            throw new CloudRuntimeException("Host not found with ID: " + hostId);
        }

        ListVhbaDevicesCommand vhbaCmd = new ListVhbaDevicesCommand(hostId, keyword);
        Answer answer;
        try {
            answer = _agentMgr.send(hostVO.getId(), vhbaCmd);
        } catch (Exception e) {
            String errorMsg = "Error sending ListVhbaDevicesCommand: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new CloudRuntimeException(errorMsg, e);
        }

        if (answer == null) {
            throw new CloudRuntimeException("Answer is null");
        }
        if (!answer.getResult()) {
            String errorDetails = (answer.getDetails() != null) ? answer.getDetails()
                    : "No additional details available";
            String errorMsg = "Answer result is false. Details: " + errorDetails;
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }
        if (!(answer instanceof ListVhbaDevicesAnswer)) {
            throw new CloudRuntimeException("Answer is not an instance of ListVhbaDevicesAnswer");
        }

        ListVhbaDevicesAnswer vhbaAnswer = (ListVhbaDevicesAnswer) answer;

       // 집합 응답용 컨테이너들
        List<String> hostDevicesNames = new ArrayList<>();
        List<String> hostDevicesTexts = new ArrayList<>();
        List<String> deviceTypes = new ArrayList<>();
        List<String> parentHbaNames = new ArrayList<>();
        List<String> wwnns = new ArrayList<>();
        List<String> wwpns = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        Map<String, String> vmAllocations = new HashMap<>();

        for (ListVhbaDevicesCommand.VhbaDeviceInfo vhbaInfo : vhbaAnswer.getVhbaDevices()) {
            String name = vhbaInfo.getVhbaName();
            String parent = vhbaInfo.getParentHbaName();
            String desc = vhbaInfo.getDescription() != null ? vhbaInfo.getDescription() : "";
            String status = vhbaInfo.getStatus();
            String wwnn = vhbaInfo.getWwnn() != null ? vhbaInfo.getWwnn() : "";
            String wwpn = vhbaInfo.getWwpn() != null ? vhbaInfo.getWwpn() : "";

            hostDevicesNames.add(name);
            hostDevicesTexts.add(desc);
            deviceTypes.add("virtual");
            parentHbaNames.add(parent);
            wwnns.add(wwnn);
            wwpns.add(wwpn);
            descriptions.add(desc);
            statuses.add(status);

            // vHBA 할당 여부 조회 -> vmallocations 채움
            String vmId = getDeviceAllocation(hostId, name);
            if (vmId != null) {
                vmAllocations.put(name, vmId);
            }
        }

        // 단일 응답 객체 구성
        ListVhbaDevicesResponse response = new ListVhbaDevicesResponse();
        response.setHostDevicesNames(hostDevicesNames);
        response.setHostDevicesTexts(hostDevicesTexts);
        response.setDeviceTypes(deviceTypes);
        response.setParentHbaNames(parentHbaNames);
        response.setWwnns(wwnns);
        response.setWwpns(wwpns);
        response.setDescriptions(descriptions);
        response.setStatuses(statuses);
        response.setVmAllocations(vmAllocations);

        List<ListVhbaDevicesResponse> responses = new ArrayList<>();
        ListResponse<ListVhbaDevicesResponse> listResponse = new ListResponse<>();
        responses.add(response);
        listResponse.setResponses(responses);
        return listResponse;
    }

    @Override
    public ListResponse<DeleteVhbaDeviceResponse> deleteVhbaDevice(DeleteVhbaDeviceCmd cmd) {
        Long hostId = cmd.getHostId();
        String hostDeviceName = cmd.getHostDeviceName();
        String wwnn = cmd.getWwnn();

        logger.info("deleteVhbaDevice 호출됨 - hostId: {}, hostDeviceName: {}, WWNN: {}", hostId, hostDeviceName, wwnn);

        HostVO hostVO = _hostDao.findById(hostId);
        if (hostVO == null) {
            String errorMsg = "Host not found with ID: " + hostId;
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        if ((hostDeviceName == null || hostDeviceName.trim().isEmpty()) &&
            (wwnn == null || wwnn.trim().isEmpty())) {
            String errorMsg = "Either host device name or WWNN is required";
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }


        DetailVO currentAllocation = _hostDetailsDao.findDetail(hostId, hostDeviceName);
        if (currentAllocation != null) {
            String errorMsg = "vHBA device is currently allocated to a VM. Please deallocate it first.";
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        DeleteVhbaDeviceCommand deleteCmd;
        if (wwnn != null && !wwnn.trim().isEmpty()) {
            deleteCmd = new DeleteVhbaDeviceCommand(hostId, hostDeviceName, wwnn);
                deleteCmd.getHostId();
                deleteCmd.getWwnn();
        } else {
            deleteCmd = new DeleteVhbaDeviceCommand(hostId, hostDeviceName);
                deleteCmd.getHostId();
                deleteCmd.getVhbaName();
        }

        Answer answer;
        try {
            logger.info("에이전트로 명령 전송 시작 - hostId: {}", hostVO.getId());
            answer = _agentMgr.send(hostVO.getId(), deleteCmd);
            logger.info("에이전트로부터 응답 수신");
        } catch (Exception e) {
            String errorMsg = "Error sending DeleteVhbaDeviceCommand: " + e.getMessage();
            logger.error(errorMsg, e);
            throw new CloudRuntimeException(errorMsg, e);
        }

        // 6. 응답 검증
        if (answer == null) {
            String errorMsg = "Answer is null";
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        logger.info("응답 결과: {}, 상세: {}", answer.getResult(), answer.getDetails());

        if (!answer.getResult()) {
            String errorDetails = (answer.getDetails() != null) ? answer.getDetails()
                    : "No additional details available";
            String errorMsg = "Answer result is false. Details: " + errorDetails;
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        if (!(answer instanceof DeleteVhbaDeviceAnswer)) {
            String errorMsg = "Answer is not an instance of DeleteVhbaDeviceAnswer. Actual type: " + answer.getClass().getSimpleName();
            logger.error(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        // 7. 응답 처리
        DeleteVhbaDeviceAnswer deleteAnswer = (DeleteVhbaDeviceAnswer) answer;
        logger.info("vHBA 삭제 결과 - 성공: {}, vHBA 이름: {}, 상세: {}",
            deleteAnswer.getResult(), deleteAnswer.getVhbaName(), deleteAnswer.getDetails());

        List<DeleteVhbaDeviceResponse> responses = new ArrayList<>();
        ListResponse<DeleteVhbaDeviceResponse> listResponse = new ListResponse<>();

        DeleteVhbaDeviceResponse response = new DeleteVhbaDeviceResponse();
        response.setSuccess(deleteAnswer.getResult());
        response.setHostDeviceName(deleteAnswer.getVhbaName());
        response.setDetails(deleteAnswer.getDetails());

        responses.add(response);
        listResponse.setResponses(responses);

        logger.info("deleteVhbaDevice 완료 - 응답 생성 완료");
        return listResponse;
    }

    @Override
    public Pair<List<? extends Vlan>, Integer> searchForVlans(final ListVlanIpRangesCmd cmd) {
        // If an account name and domain ID are specified, look up the account
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        Long accountId = null;
        final Long networkId = cmd.getNetworkId();
        final Boolean forVirtual = cmd.isForVirtualNetwork();
        String vlanType = null;
        final Long projectId = cmd.getProjectId();
        final Long physicalNetworkId = cmd.getPhysicalNetworkId();

        if (accountName != null && domainId != null) {
            if (projectId != null) {
                throw new InvalidParameterValueException("Account and projectId can't be specified together");
            }
            final Account account = _accountDao.findActiveAccount(accountName, domainId);
            if (account == null) {
                final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find account " + accountName + " in specified domain");
                // Since we don't have a DomainVO object here, we directly set
                // tablename to "domain".
                final DomainVO domain = ApiDBUtils.findDomainById(domainId);
                String domainUuid = domainId.toString();
                if (domain != null) {
                    domainUuid = domain.getUuid();
                }
                ex.addProxyObject(domainUuid, "domainId");
                throw ex;
            } else {
                accountId = account.getId();
            }
        }

        if (forVirtual != null) {
            if (forVirtual) {
                vlanType = VlanType.VirtualNetwork.toString();
            } else {
                vlanType = VlanType.DirectAttached.toString();
            }
        }

        // set project information
        if (projectId != null) {
            final Project project = _projectMgr.getProject(projectId);
            if (project == null) {
                final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find project by id " + projectId);
                ex.addProxyObject(projectId.toString(), "projectId");
                throw ex;
            }
            accountId = project.getProjectAccountId();
        }

        final Filter searchFilter = new Filter(VlanVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        final Object id = cmd.getId();
        final Object vlan = cmd.getVlan();
        final Object dataCenterId = cmd.getZoneId();
        final Object podId = cmd.getPodId();
        final Object keyword = cmd.getKeyword();

        final SearchBuilder<VlanVO> sb = _vlanDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("vlan", sb.entity().getVlanTag(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("vlan", sb.entity().getVlanTag(), SearchCriteria.Op.EQ);
        sb.and("networkId", sb.entity().getNetworkId(), SearchCriteria.Op.EQ);
        sb.and("vlanType", sb.entity().getVlanType(), SearchCriteria.Op.EQ);
        sb.and("physicalNetworkId", sb.entity().getPhysicalNetworkId(), SearchCriteria.Op.EQ);

        if (accountId != null) {
            final SearchBuilder<AccountVlanMapVO> accountVlanMapSearch = _accountVlanMapDao.createSearchBuilder();
            accountVlanMapSearch.and("accountId", accountVlanMapSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
            sb.join("accountVlanMapSearch", accountVlanMapSearch, sb.entity().getId(), accountVlanMapSearch.entity().getVlanDbId(), JoinBuilder.JoinType.INNER);
        }

        if (domainId != null) {
            DomainVO domain = ApiDBUtils.findDomainById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Unable to find domain with id " + domainId);
            }
            final SearchBuilder<DomainVlanMapVO> domainVlanMapSearch = _domainVlanMapDao.createSearchBuilder();
            domainVlanMapSearch.and("domainId", domainVlanMapSearch.entity().getDomainId(), SearchCriteria.Op.EQ);
            sb.join("domainVlanMapSearch", domainVlanMapSearch, sb.entity().getId(), domainVlanMapSearch.entity().getVlanDbId(), JoinType.INNER);
        }

        if (podId != null) {
            final SearchBuilder<PodVlanMapVO> podVlanMapSearch = _podVlanMapDao.createSearchBuilder();
            podVlanMapSearch.and("podId", podVlanMapSearch.entity().getPodId(), SearchCriteria.Op.EQ);
            sb.join("podVlanMapSearch", podVlanMapSearch, sb.entity().getId(), podVlanMapSearch.entity().getVlanDbId(), JoinBuilder.JoinType.INNER);
        }

        final SearchCriteria<VlanVO> sc = sb.create();
        if (keyword != null) {
            final SearchCriteria<VlanVO> ssc = _vlanDao.createSearchCriteria();
            ssc.addOr("vlanTag", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("ipRange", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("vlanTag", SearchCriteria.Op.SC, ssc);
        } else {
            if (id != null) {
                sc.setParameters("id", id);
            }

            if (vlan != null) {
                sc.setParameters("vlan", vlan);
            }

            if (dataCenterId != null) {
                sc.setParameters("dataCenterId", dataCenterId);
            }

            if (networkId != null) {
                sc.setParameters("networkId", networkId);
            }

            if (accountId != null) {
                sc.setJoinParameters("accountVlanMapSearch", "accountId", accountId);
            }

            if (podId != null) {
                sc.setJoinParameters("podVlanMapSearch", "podId", podId);
            }
            if (vlanType != null) {
                sc.setParameters("vlanType", vlanType);
            }

            if (physicalNetworkId != null) {
                sc.setParameters("physicalNetworkId", physicalNetworkId);
            }

            if (domainId != null) {
                sc.setJoinParameters("domainVlanMapSearch", "domainId", domainId);
            }
        }

        final Pair<List<VlanVO>, Integer> result = _vlanDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends Configuration>, Integer> searchForConfigurations(final ListCfgsByCmd cmd) {
        final Filter searchFilter = new Filter(ConfigurationVO.class, "name", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchCriteria<ConfigurationVO> sc = _configDao.createSearchCriteria();

        final Object name = cmd.getConfigName();
        final Object category = cmd.getCategory();
        final Object keyword = cmd.getKeyword();
        final Long zoneId = cmd.getZoneId();
        final Long clusterId = cmd.getClusterId();
        final Long storagepoolId = cmd.getStoragepoolId();
        final Long imageStoreId = cmd.getImageStoreId();
        Long accountId = cmd.getAccountId();
        Long domainId = cmd.getDomainId();
        final String groupName = cmd.getGroupName();
        final String subGroupName = cmd.getSubGroupName();
        final String parentName = cmd.getParentName();
        ConfigKey.Scope scope = null;
        Long id = null;
        int paramCountCheck = 0;

        final Account caller = CallContext.current().getCallingAccount();
        if (_accountMgr.isDomainAdmin(caller.getId())) {
            if (accountId == null && domainId == null) {
                domainId = caller.getDomainId();
            }
        } else if (_accountMgr.isNormalUser(caller.getId())) {
            if (accountId == null) {
                accountId = caller.getAccountId();
            }
        }

        if (zoneId != null) {
            scope = ConfigKey.Scope.Zone;
            id = zoneId;
            paramCountCheck++;
        }
        if (clusterId != null) {
            scope = ConfigKey.Scope.Cluster;
            id = clusterId;
            paramCountCheck++;
        }
        if (accountId != null) {
            Account account = _accountMgr.getAccount(accountId);
            _accountMgr.checkAccess(caller, null, false, account);
            scope = ConfigKey.Scope.Account;
            id = accountId;
            paramCountCheck++;
        }
        if (domainId != null) {
            _accountMgr.checkAccess(caller, _domainDao.findById(domainId));
            scope = ConfigKey.Scope.Domain;
            id = domainId;
            paramCountCheck++;
        }
        if (storagepoolId != null) {
            scope = ConfigKey.Scope.StoragePool;
            id = storagepoolId;
            paramCountCheck++;
        }
        if (imageStoreId != null) {
            scope = ConfigKey.Scope.ImageStore;
            id = imageStoreId;
            paramCountCheck++;
        }

        if (paramCountCheck > 1) {
            throw new InvalidParameterValueException("cannot handle multiple IDs, provide only one ID corresponding to the scope");
        }

        if (keyword != null) {
            final SearchCriteria<ConfigurationVO> ssc = _configDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("instance", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("component", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("description", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("category", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("value", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.LIKE, "%" + name + "%");
        }

        if (groupName != null) {
            ConfigurationGroupVO configGroupVO = _configGroupDao.findByName(groupName);
            if (configGroupVO == null) {
                throw new InvalidParameterValueException("Invalid configuration group: " + groupName);
            }
            Long groupId = configGroupVO.getId();
            sc.addAnd("groupId", SearchCriteria.Op.EQ, groupId);
        }

        if (subGroupName != null) {
            ConfigurationSubGroupVO configSubGroupVO = _configSubGroupDao.findByName(subGroupName);
            if (configSubGroupVO == null) {
                throw new InvalidParameterValueException("Invalid configuration subgroup: " + subGroupName);
            }

            Long subGroupId = configSubGroupVO.getId();
            sc.addAnd("subGroupId", SearchCriteria.Op.EQ, subGroupId);
        }

        if (parentName != null) {
            sc.addAnd("parent", SearchCriteria.Op.EQ, parentName);
        }

        if (category != null) {
            sc.addAnd("category", SearchCriteria.Op.EQ, category);
        }

        // hidden configurations are not displayed using the search API
        sc.addAnd("category", SearchCriteria.Op.NEQ, "Hidden");

        if (scope != null) {
            // getting the list of parameters at requested scope
            if (ConfigurationManagerImpl.ENABLE_ACCOUNT_SETTINGS_FOR_DOMAIN.value()
                && scope.equals(ConfigKey.Scope.Domain)) {
                sc.addAnd("scope", SearchCriteria.Op.BINARY_OR, (ConfigKey.Scope.Domain.getBitValue() | ConfigKey.Scope.Account.getBitValue()));
            } else {
                sc.addAnd("scope", SearchCriteria.Op.BINARY_OR, scope.getBitValue());
            }
        }

        final Pair<List<ConfigurationVO>, Integer> result = _configDao.searchAndCount(sc, searchFilter);

        if (scope != null) {
            // Populate values corresponding the resource id
            final List<ConfigurationVO> configVOList = new ArrayList<>();
            for (final ConfigurationVO param : result.first()) {
                final ConfigurationVO configVo = _configDao.findByName(param.getName());
                if (configVo != null) {
                    final ConfigKey<?> key = _configDepot.get(param.getName());
                    if (key != null) {
                        Object value = key.valueInScope(scope, id);
                        configVo.setValue(value == null ? null : value.toString());
                        configVOList.add(configVo);
                    } else {
                        logger.warn("ConfigDepot could not find parameter " + param.getName() + " for scope " + scope);
                    }
                } else {
                    logger.warn("Configuration item  " + param.getName() + " not found in " + scope);
                }
            }

            return new Pair<>(configVOList, configVOList.size());
        }

        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends ConfigurationGroup>, Integer> listConfigurationGroups(ListCfgGroupsByCmd cmd) {
        final Filter searchFilter = new Filter(ConfigurationGroupVO.class, "precedence", true, null, null);
        final SearchCriteria<ConfigurationGroupVO> sc = _configGroupDao.createSearchCriteria();

        final String groupName = cmd.getGroupName();
        if (StringUtils.isNotBlank(groupName)) {
            sc.addAnd("name", SearchCriteria.Op.EQ, groupName);
        }

        final Pair<List<ConfigurationGroupVO>, Integer> result = _configGroupDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends IpAddress>, Integer> searchForIPAddresses(final ListPublicIpAddressesCmd cmd) {
        final Long associatedNetworkId = cmd.getAssociatedNetworkId();
        final Long zone = cmd.getZoneId();
        final Long vlan = cmd.getVlanId();
        final Boolean forVirtualNetwork = cmd.isForVirtualNetwork();
        final Long ipId = cmd.getId();
        final Long networkId = cmd.getNetworkId();
        final Long vpcId = cmd.getVpcId();

        final String state = cmd.getState();
        Boolean isAllocated = cmd.isAllocatedOnly();
        if (isAllocated == null) {
            if (state != null && (state.equalsIgnoreCase(IpAddress.State.Free.name()) || state.equalsIgnoreCase(IpAddress.State.Reserved.name()))) {
                isAllocated = Boolean.FALSE;
            } else {
                isAllocated = Boolean.TRUE; // default
            }
        } else {
            if (state != null && (state.equalsIgnoreCase(IpAddress.State.Free.name()) || state.equalsIgnoreCase(IpAddress.State.Reserved.name()))) {
                if (isAllocated) {
                    throw new InvalidParameterValueException("Conflict: allocatedonly is true but state is Free");
                }
            } else if (state != null && state.equalsIgnoreCase(IpAddress.State.Allocated.name())) {
                isAllocated = Boolean.TRUE;
            }
        }
        boolean isAllocatedTemp = isAllocated;

        VlanType vlanType = null;
        if (forVirtualNetwork != null) {
            vlanType = forVirtualNetwork ? VlanType.VirtualNetwork : VlanType.DirectAttached;
        } else {
            vlanType = VlanType.VirtualNetwork;
        }

        final Account caller = getCaller();
        List<IPAddressVO> addrs = new ArrayList<>();
        NetworkVO network = null;   // shared network

        if (vlanType == VlanType.DirectAttached && networkId == null && ipId == null) { // only root admin can list public ips in all shared networks
            if (caller.getType() != Account.Type.ADMIN) {
                isAllocated = true;
            }
        } else if (vlanType == VlanType.DirectAttached) {
            // list public ip address on shared network
            // access control. admin: all Ips, domain admin/user: all Ips in shared network in the domain/sub-domain/user
            if (networkId == null) {
                IPAddressVO ip = _publicIpAddressDao.findById(ipId);
                if (ip == null) {
                    throw new InvalidParameterValueException("Please specify a valid ipaddress id");
                }
                network = networkDao.findById(ip.getSourceNetworkId());
            } else {
                network = networkDao.findById(networkId);
            }
            if (network == null || network.getGuestType() != Network.GuestType.Shared) {
                throw new InvalidParameterValueException("Please specify a valid network id");
            }
            if (network.getAclType() == ControlledEntity.ACLType.Account) {
                NetworkAccountVO networkMap = _networkAccountDao.getAccountNetworkMapByNetworkId(network.getId());
                if (networkMap == null) {
                    return new Pair<>(addrs, 0);
                }
                try {
                    _accountMgr.checkAccess(caller, null, false, _accountDao.findById(networkMap.getAccountId()));
                } catch (PermissionDeniedException ex) {
                    logger.info("Account " + caller + " do not have permission to access account of network " + network);
                    _accountMgr.checkAccess(caller, SecurityChecker.AccessType.UseEntry, false, network);
                    isAllocated = Boolean.TRUE;
                }
            } else { // Domain level
                NetworkDomainVO networkMap = _networkDomainDao.getDomainNetworkMapByNetworkId(network.getId());
                if (networkMap == null) {
                    return new Pair<>(addrs, 0);
                }
                if (caller.getType() == Account.Type.NORMAL || caller.getType() == Account.Type.PROJECT) {
                    if (_networkMgr.isNetworkAvailableInDomain(network.getId(), caller.getDomainId())) {
                        isAllocated = Boolean.TRUE;
                    } else {
                        return new Pair<>(addrs, 0);
                    }
                } else if (caller.getType() == Account.Type.DOMAIN_ADMIN || caller.getType() == Account.Type.RESOURCE_DOMAIN_ADMIN) {
                    if (caller.getDomainId() == networkMap.getDomainId() || _domainDao.isChildDomain(caller.getDomainId(), networkMap.getDomainId())) {
                        logger.debug("Caller " + caller.getUuid() + " has permission to access the network : " + network.getUuid());
                    } else {
                        if (_networkMgr.isNetworkAvailableInDomain(network.getId(), caller.getDomainId())) {
                            isAllocated = Boolean.TRUE;
                        } else {
                            return new Pair<>(addrs, 0);
                        }
                    }
                }
            }
        }

        final Filter searchFilter = new Filter(IPAddressVO.class, "address", false, null, null);
        final SearchBuilder<IPAddressVO> sb = _publicIpAddressDao.createSearchBuilder();
        Long domainId = null;
        Boolean isRecursive = cmd.isRecursive();
        final List<Long> permittedAccounts = new ArrayList<>();
        ListProjectResourcesCriteria listProjectResourcesCriteria = null;
        Boolean isAllocatedOrReserved = false;
        if (isAllocated || IpAddress.State.Reserved.name().equalsIgnoreCase(state)) {
            isAllocatedOrReserved = true;
        }
        if (isAllocatedOrReserved || (vlanType == VlanType.VirtualNetwork && (caller.getType() != Account.Type.ADMIN || cmd.getDomainId() != null))) {
            final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(),
                    null);
            _accountMgr.buildACLSearchParameters(caller, cmd.getId(), cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
            domainId = domainIdRecursiveListProject.first();
            isRecursive = domainIdRecursiveListProject.second();
            listProjectResourcesCriteria = domainIdRecursiveListProject.third();
            _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        }

        buildParameters(sb, cmd, vlanType == VlanType.VirtualNetwork ? true : isAllocated);

        SearchCriteria<IPAddressVO> sc = sb.create();
        setParameters(sc, cmd, vlanType, isAllocated);

        if (isAllocatedOrReserved || (vlanType == VlanType.VirtualNetwork && (caller.getType() != Account.Type.ADMIN || cmd.getDomainId() != null))) {
            _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        }

        if (associatedNetworkId != null) {
            _accountMgr.checkAccess(caller, null, false, networkDao.findById(associatedNetworkId));
            sc.setParameters("associatedNetworkIdEq", associatedNetworkId);
        }
        if (vpcId != null) {
            _accountMgr.checkAccess(caller, null, false, _vpcDao.findById(vpcId));
            sc.setParameters("vpcId", vpcId);
        }

        addrs = _publicIpAddressDao.search(sc, searchFilter); // Allocated

        // Free IP addresses in system IP ranges
        List<Long> freeAddrIds = new ArrayList<>();
        if (!(isAllocatedOrReserved || vlanType == VlanType.DirectAttached)) {
            Long zoneId = zone;
            Account owner;
            if (cmd.getProjectId() != null && cmd.getProjectId() != -1) {
                owner = _accountMgr.finalizeOwner(CallContext.current().getCallingAccount(), cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
            } else {
                owner = _accountMgr.finalizeOwner(CallContext.current().getCallingAccount(), cmd.getAccountName(), cmd.getDomainId(), null);
            }
            if (associatedNetworkId != null) {
                NetworkVO guestNetwork = networkDao.findById(associatedNetworkId);
                if (zoneId == null) {
                    zoneId = guestNetwork.getDataCenterId();
                } else if (zoneId != guestNetwork.getDataCenterId()) {
                    InvalidParameterValueException ex = new InvalidParameterValueException("Please specify a valid associated network id in the specified zone.");
                    throw ex;
                }
                owner = _accountDao.findById(guestNetwork.getAccountId());
            }
            List<DataCenterVO> dcList = new ArrayList<>();
            if (zoneId == null){
                dcList = ApiDBUtils.listZones();
            } else {
                dcList.add(ApiDBUtils.findZoneById(zoneId));
            }
            List<Long> vlanDbIds = null;
            if (vlan != null) {
                vlanDbIds = new ArrayList<>();
                vlanDbIds.add(vlan);
            }
            List<IPAddressVO> freeAddrs = new ArrayList<>();
            for (DataCenterVO dc : dcList) {
                long dcId = dc.getId();
                try {
                    freeAddrs.addAll(_ipAddressMgr.listAvailablePublicIps(dcId, null, vlanDbIds, owner, VlanType.VirtualNetwork, associatedNetworkId,
                            false, false, false, null, null, false, cmd.getVpcId(), cmd.isDisplay(), false, false)); // Free
                } catch (InsufficientAddressCapacityException e) {
                    logger.warn("no free address is found in zone {}", dc);
                }
            }
            for (IPAddressVO addr: freeAddrs) {
                freeAddrIds.add(addr.getId());
            }
        } else if (vlanType == VlanType.DirectAttached && network != null && !isAllocatedTemp && isAllocated) {
            if (caller.getType() != Account.Type.ADMIN && !IpAddressManager.AllowUserListAvailableIpsOnSharedNetwork.value()) {
                logger.debug("Non-admin users are not allowed to list available IPs on shared networks");
            } else {
                final SearchBuilder<IPAddressVO> searchBuilder = _publicIpAddressDao.createSearchBuilder();
                buildParameters(searchBuilder, cmd, false);

                SearchCriteria<IPAddressVO> searchCriteria = searchBuilder.create();
                setParameters(searchCriteria, cmd, vlanType, false);
                searchCriteria.setParameters("state", IpAddress.State.Free.name());
                addrs.addAll(_publicIpAddressDao.search(searchCriteria, searchFilter)); // Free IPs on shared network
            }
        }

        if (freeAddrIds.size() > 0) {
            final SearchBuilder<IPAddressVO> sb2 = _publicIpAddressDao.createSearchBuilder();
            buildParameters(sb2, cmd, false);
            sb2.and("ids", sb2.entity().getId(), SearchCriteria.Op.IN);
            sb2.and("quarantinedPublicIpsIdsNIN", sb2.entity().getId(), SearchCriteria.Op.NIN);

            SearchCriteria<IPAddressVO> sc2 = sb2.create();
            setParameters(sc2, cmd, vlanType, isAllocated);
            sc2.setParameters("ids", freeAddrIds.toArray());
            _publicIpAddressDao.buildQuarantineSearchCriteria(sc2);
            addrs.addAll(_publicIpAddressDao.search(sc2, searchFilter)); // Allocated + Free
        }
        Collections.sort(addrs, Comparator.comparing(IPAddressVO::getAddress));
        List<? extends IpAddress> wPagination = com.cloud.utils.StringUtils.applyPagination(addrs, cmd.getStartIndex(), cmd.getPageSizeVal());
        if (wPagination != null) {
            return new Pair<>(wPagination, addrs.size());
        }
        return new Pair<>(addrs, addrs.size());
    }

    private void buildParameters(final SearchBuilder<IPAddressVO> sb, final ListPublicIpAddressesCmd cmd, final Boolean isAllocated) {
        final Object keyword = cmd.getKeyword();
        final String address = cmd.getIpAddress();
        final Boolean forLoadBalancing = cmd.isForLoadBalancing();
        final Map<String, String> tags = cmd.getTags();
        boolean forProvider = cmd.isForProvider();

        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("address", sb.entity().getAddress(), SearchCriteria.Op.EQ);
        sb.and("vlanDbId", sb.entity().getVlanId(), SearchCriteria.Op.EQ);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("physicalNetworkId", sb.entity().getPhysicalNetworkId(), SearchCriteria.Op.EQ);
        sb.and("associatedNetworkIdEq", sb.entity().getAssociatedWithNetworkId(), SearchCriteria.Op.EQ);
        sb.and("sourceNetworkId", sb.entity().getSourceNetworkId(), SearchCriteria.Op.EQ);
        sb.and("isSourceNat", sb.entity().isSourceNat(), SearchCriteria.Op.EQ);
        sb.and("isStaticNat", sb.entity().isOneToOneNat(), SearchCriteria.Op.EQ);
        sb.and("vpcId", sb.entity().getVpcId(), SearchCriteria.Op.EQ);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("display", sb.entity().isDisplay(), SearchCriteria.Op.EQ);
        sb.and(FOR_SYSTEMVMS, sb.entity().isForSystemVms(), SearchCriteria.Op.EQ);

        if (forLoadBalancing != null && forLoadBalancing) {
            final SearchBuilder<LoadBalancerVO> lbSearch = _loadbalancerDao.createSearchBuilder();
            sb.join("lbSearch", lbSearch, sb.entity().getId(), lbSearch.entity().getSourceIpAddressId(), JoinType.INNER);
            sb.groupBy(sb.entity().getId());
        }
        if (keyword != null && address == null) {
            sb.and("addressLIKE", sb.entity().getAddress(), SearchCriteria.Op.LIKE);
        }

        if (tags != null && !tags.isEmpty()) {
            final SearchBuilder<ResourceTagVO> tagSearch = _resourceTagDao.createSearchBuilder();
            for (int count = 0; count < tags.size(); count++) {
                tagSearch.or().op("key" + String.valueOf(count), tagSearch.entity().getKey(), SearchCriteria.Op.EQ);
                tagSearch.and("value" + String.valueOf(count), tagSearch.entity().getValue(), SearchCriteria.Op.EQ);
                tagSearch.cp();
            }
            tagSearch.and("resourceType", tagSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
            sb.groupBy(sb.entity().getId());
            sb.join("tagSearch", tagSearch, sb.entity().getId(), tagSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }

        final SearchBuilder<VlanVO> vlanSearch = _vlanDao.createSearchBuilder();
        vlanSearch.and("vlanType", vlanSearch.entity().getVlanType(), SearchCriteria.Op.EQ);
        vlanSearch.and("removed", vlanSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        sb.join("vlanSearch", vlanSearch, sb.entity().getVlanId(), vlanSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        if (isAllocated != null && isAllocated) {
            sb.and("allocated", sb.entity().getAllocatedTime(), SearchCriteria.Op.NNULL);
        }

        if (forProvider) {
            SearchBuilder<VlanDetailsVO> vlanDetailsSearch = vlanDetailsDao.createSearchBuilder();
            vlanDetailsSearch.and("name", vlanDetailsSearch.entity().getName(), SearchCriteria.Op.IN);
            vlanDetailsSearch.and("value", vlanDetailsSearch.entity().getValue(), SearchCriteria.Op.EQ);
            sb.join("vlanDetailSearch", vlanDetailsSearch, sb.entity().getVlanId(), vlanDetailsSearch.entity().getResourceId(), JoinType.LEFT);
        }
    }

    protected void setParameters(SearchCriteria<IPAddressVO> sc, final ListPublicIpAddressesCmd cmd, VlanType vlanType, Boolean isAllocated) {
        final Object keyword = cmd.getKeyword();
        final Long physicalNetworkId = cmd.getPhysicalNetworkId();
        final Long sourceNetworkId = cmd.getNetworkId();
        final Long vpcId = cmd.getVpcId();
        Long zone = cmd.getZoneId();
        final String address = cmd.getIpAddress();
        final Long vlan = cmd.getVlanId();
        final Long ipId = cmd.getId();
        final Boolean sourceNat = cmd.isSourceNat();
        final Boolean staticNat = cmd.isStaticNat();
        final Boolean forDisplay = cmd.getDisplay();
        final String state = cmd.getState();
        final Boolean forSystemVms = cmd.getForSystemVMs();
        final boolean forProvider = cmd.isForProvider();
        final Map<String, String> tags = cmd.getTags();

        sc.setJoinParameters("vlanSearch", "vlanType", vlanType);

        if (tags != null && !tags.isEmpty()) {
            int count = 0;
            sc.setJoinParameters("tagSearch", "resourceType", ResourceObjectType.PublicIpAddress.toString());
            for (final String key : tags.keySet()) {
                sc.setJoinParameters("tagSearch", "key" + String.valueOf(count), key);
                sc.setJoinParameters("tagSearch", "value" + String.valueOf(count), tags.get(key));
                count++;
            }
        }

        if (zone != null) {
            sc.setParameters("dataCenterId", zone);
        }

        if (ipId != null) {
            sc.setParameters("id", ipId);
        }

        if (sourceNat != null) {
            sc.setParameters("isSourceNat", sourceNat);
        }

        if (staticNat != null) {
            sc.setParameters("isStaticNat", staticNat);
        }

        if (address == null && keyword != null) {
            sc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.setParameters("addressLIKE", "%" + keyword + "%");
        }
        if (address != null) {
            sc.setParameters("address", address);
        }

        if (vlan != null) {
            sc.setParameters("vlanDbId", vlan);
        }

        if (physicalNetworkId != null) {
            sc.setParameters("physicalNetworkId", physicalNetworkId);
        }

        if (sourceNetworkId != null) {
            sc.setParameters("sourceNetworkId", sourceNetworkId);
        }

        if (forDisplay != null) {
            sc.setParameters("display", forDisplay);
        }

        if (state != null) {
            sc.setParameters("state", state);
        } else if (isAllocated != null && isAllocated) {
            sc.setParameters("state", IpAddress.State.Allocated);
        }

        if (IpAddressManagerImpl.getSystemvmpublicipreservationmodestrictness().value() && IpAddress.State.Free.name().equalsIgnoreCase(state)) {
            sc.setParameters(FOR_SYSTEMVMS, false);
        } else {
            sc.setParameters(FOR_SYSTEMVMS, forSystemVms);
        }

        if (forProvider) {
            sc.setJoinParameters("vlanDetailSearch", "name", ApiConstants.NETRIS_DETAIL_KEY, ApiConstants.NSX_DETAIL_KEY);
            sc.setJoinParameters("vlanDetailSearch", "value", "true");
        }
    }

    @Override
    public Pair<List<? extends GuestOS>, Integer> listGuestOSByCriteria(final ListGuestOsCmd cmd) {
        final Long id = cmd.getId();
        final Long osCategoryId = cmd.getOsCategoryId();
        final String description = cmd.getDescription();
        final String keyword = cmd.getKeyword();
        final Long startIndex = cmd.getStartIndex();
        final Long pageSize = cmd.getPageSizeVal();
        Boolean forDisplay = cmd.getDisplay();

        return _guestOSDao.listGuestOSByCriteria(startIndex, pageSize, id, osCategoryId, description, keyword, forDisplay);
    }

    @Override
    public Pair<List<? extends GuestOsCategory>, Integer> listGuestOSCategoriesByCriteria(final ListGuestOsCategoriesCmd cmd) {
        final Filter searchFilter = new Filter(GuestOSCategoryVO.class, "sortKey", true,
                cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(GuestOSCategoryVO.class, "id", true);
        final Long id = cmd.getId();
        final String name = cmd.getName();
        final String keyword = cmd.getKeyword();
        final Boolean featured = cmd.isFeatured();
        final Boolean isIso = cmd.isIso();
        final Boolean isVnf = cmd.isVnf();
        final Long zoneId = cmd.getZoneId();
        final CPU.CPUArch arch = cmd.getArch();

        final SearchBuilder<GuestOSCategoryVO> sb = _guestOSCategoryDao.createSearchBuilder();
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and("featured", sb.entity().isFeatured(), SearchCriteria.Op.EQ);
        if (ObjectUtils.anyNotNull(zoneId, arch, isIso, isVnf)) {
            final SearchBuilder<GuestOSVO> guestOsSearch = _guestOSDao.createSearchBuilder();
            guestOsSearch.and("ids", guestOsSearch.entity().getId(), SearchCriteria.Op.IN);
            sb.join("guestOsSearch", guestOsSearch, guestOsSearch.entity().getCategoryId(), sb.entity().getId(),
                    JoinType.INNER);
            guestOsSearch.done();
            sb.groupBy(sb.entity().getId());
        }
        sb.done();
        SearchCriteria<GuestOSCategoryVO> sc = sb.create();
        if (id != null) {
            sc.setParameters("id", id);
        }
        if (name != null) {
            sc.setParameters("name", "%" + name + "%");
        }
        if (keyword != null) {
            sc.setParameters("name", "%" + keyword + "%");
        }
        if (featured != null) {
            sc.setParameters("featured", featured);
        }
        if (ObjectUtils.anyNotNull(zoneId, arch, isIso, isVnf)) {
            List<Long> guestOsIds = templateDao.listTemplateIsoByArchVnfAndZone(zoneId, arch, isIso, isVnf);
            if (CollectionUtils.isEmpty(guestOsIds)) {
                return new Pair<>(Collections.emptyList(), 0);
            }
            sc.setJoinParameters("guestOsSearch", "ids", guestOsIds.toArray());
        }
        final Pair<List<GuestOSCategoryVO>, Integer> result = _guestOSCategoryDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }


    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_CATEGORY_ADD, eventDescription = "adding OS category")
    public GuestOsCategory addGuestOsCategory(AddGuestOsCategoryCmd cmd) {
        final String name = cmd.getName();
        final boolean featured = cmd.isFeatured();
        final GuestOSCategoryVO guestOSCategory = new GuestOSCategoryVO(name, featured);
        GuestOsCategory guestOsCategory = _guestOSCategoryDao.persist(guestOSCategory);
        CallContext.current().setEventResourceId(guestOsCategory.getId());
        CallContext.current().setEventResourceType(ApiCommandResourceType.GuestOsCategory);
        return guestOSCategory;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_CATEGORY_UPDATE, eventDescription = "updating OS category")
    public GuestOsCategory updateGuestOsCategory(UpdateGuestOsCategoryCmd cmd) {
        final long id = cmd.getId();
        final String name = cmd.getName();
        final Boolean featured = cmd.isFeatured();
        Integer sortKey = cmd.getSortKey();
        final GuestOSCategoryVO guestOSCategory = _guestOSCategoryDao.findById(id);
        if (guestOSCategory == null) {
            throw new InvalidParameterValueException("Invalid OS category ID specified");
        }
        if (ObjectUtils.allNull(name, featured, sortKey)) {
            return guestOSCategory;
        }
        if (StringUtils.isNotBlank(name)) {
            guestOSCategory.setName(name);
        }
        if (featured != null) {
            guestOSCategory.setFeatured(featured);
        }
        if (sortKey != null) {
            guestOSCategory.setSortKey(sortKey);
        }
        if (!_guestOSCategoryDao.update(id, guestOSCategory)) {
            return null;
        }
        return guestOSCategory;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_CATEGORY_DELETE, eventDescription = "deleting OS category")
    public boolean deleteGuestOsCategory(DeleteGuestOsCategoryCmd cmd) {
        final long id = cmd.getId();
        final GuestOSCategoryVO guestOSCategory = _guestOSCategoryDao.findById(id);
        if (guestOSCategory == null) {
            throw new InvalidParameterValueException("Invalid OS category ID specified");
        }
        List<Long> guestOses = _guestOSDao.listIdsByCategoryId(id);
        if (!guestOses.isEmpty()) {
            throw new InvalidParameterValueException(String.format(
                    "Unable to delete the OS category. %d guest OS exist for it.", guestOses.size()));
        }
        return _guestOSCategoryDao.remove(id);
    }

    @Override
    public Pair<List<? extends GuestOSHypervisor>, Integer> listGuestOSMappingByCriteria(final ListGuestOsMappingCmd cmd) {
        final String guestOsId = "guestOsId";
        final Filter searchFilter = new Filter(GuestOSHypervisorVO.class, "hypervisorType", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(GuestOSHypervisorVO.class, "hypervisorVersion", false);
        searchFilter.addOrderBy(GuestOSHypervisorVO.class, guestOsId, true);
        searchFilter.addOrderBy(GuestOSHypervisorVO.class, "created", false);
        final Long id = cmd.getId();
        final Long osTypeId = cmd.getOsTypeId();
        final String osDisplayName = cmd.getOsDisplayName();
        final String osNameForHypervisor = cmd.getOsNameForHypervisor();
        final String hypervisor = cmd.getHypervisor();
        final String hypervisorVersion = cmd.getHypervisorVersion();

        //throw exception if hypervisor name is not passed, but version is
        if (hypervisorVersion != null && (hypervisor == null || hypervisor.isEmpty())) {
            throw new InvalidParameterValueException("Hypervisor version parameter cannot be used without specifying a hypervisor : XenServer, KVM or VMware");
        }

        final SearchCriteria<GuestOSHypervisorVO> sc = _guestOSHypervisorDao.createSearchCriteria();

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (osTypeId != null) {
            sc.addAnd(guestOsId, SearchCriteria.Op.EQ, osTypeId);
        }

        if (osNameForHypervisor != null) {
            sc.addAnd("guestOsName", SearchCriteria.Op.LIKE, "%" + osNameForHypervisor + "%");
        }

        if (hypervisor != null) {
            sc.addAnd("hypervisorType", SearchCriteria.Op.LIKE, "%" + hypervisor + "%");
        }

        if (hypervisorVersion != null) {
            sc.addAnd("hypervisorVersion", SearchCriteria.Op.LIKE, "%" + hypervisorVersion + "%");
        }

        if (osDisplayName != null) {
            List<GuestOSVO> guestOSVOS = _guestOSDao.listLikeDisplayName(osDisplayName);
            if (CollectionUtils.isNotEmpty(guestOSVOS)) {
                List<Long> guestOSids = guestOSVOS.stream().map(mo -> mo.getId()).collect(Collectors.toList());
                sc.addAnd(guestOsId, SearchCriteria.Op.IN, guestOSids.toArray());
            }
        }

        final Pair<List<GuestOSHypervisorVO>, Integer> result = _guestOSHypervisorDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_MAPPING_ADD, eventDescription = "Adding new guest OS to hypervisor name mapping", create = true)
    public GuestOSHypervisor addGuestOsMapping(final AddGuestOsMappingCmd cmd) {
        final Long osTypeId = cmd.getOsTypeId();
        final String osStdName = cmd.getOsStdName();
        final String hypervisor = cmd.getHypervisor();
        final String hypervisorVersion = cmd.getHypervisorVersion();
        final String osNameForHypervisor = cmd.getOsNameForHypervisor();
        GuestOS guestOs = null;

        if (osTypeId == null && StringUtils.isEmpty(osStdName)) {
            throw new InvalidParameterValueException("Please specify either a guest OS name or UUID");
        }

        final HypervisorType hypervisorType = HypervisorType.getType(hypervisor);

        if (!(hypervisorType == HypervisorType.KVM || hypervisorType == HypervisorType.XenServer || hypervisorType == HypervisorType.VMware)) {
            throw new InvalidParameterValueException("Please specify a valid hypervisor : XenServer, KVM or VMware");
        }

        final HypervisorCapabilitiesVO hypervisorCapabilities = _hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(hypervisorType, hypervisorVersion);
        if (hypervisorCapabilities == null) {
            throw new InvalidParameterValueException("Please specify a valid hypervisor and supported version");
        }

        //by this point either osTypeId or osStdType is non-empty. Find by either of them. ID takes preference if both are specified
        if (osTypeId != null) {
            guestOs = ApiDBUtils.findGuestOSById(osTypeId);
        } else if (osStdName != null) {
            guestOs = ApiDBUtils.findGuestOSByDisplayName(osStdName);
        }

        if (guestOs == null) {
            throw new InvalidParameterValueException("Unable to find the guest OS by name or UUID");
        }
        //check for duplicates
        final GuestOSHypervisorVO duplicate = _guestOSHypervisorDao.findByOsIdAndHypervisorAndUserDefined(guestOs.getId(), hypervisorType.toString(), hypervisorVersion, true);

        if (duplicate != null) {
            if (!cmd.isForced()) {
                throw new InvalidParameterValueException(
                        "Mapping from hypervisor : " + hypervisorType.toString() + ", version : " + hypervisorVersion + " and guest OS : " + guestOs.getDisplayName() + " already exists!");
            }

            if (Boolean.TRUE.equals(cmd.getOsMappingCheckEnabled())) {
                checkGuestOSHypervisorMapping(hypervisorType, hypervisorVersion, guestOs.getDisplayName(), osNameForHypervisor);
            }

            final long guestOsId = duplicate.getId();
            final GuestOSHypervisorVO guestOsHypervisor = _guestOSHypervisorDao.createForUpdate(guestOsId);
            guestOsHypervisor.setGuestOsName(osNameForHypervisor);
            if (_guestOSHypervisorDao.update(guestOsId, guestOsHypervisor)) {
                return _guestOSHypervisorDao.findById(guestOsId);
            }
            return null;
        }

        if (Boolean.TRUE.equals(cmd.getOsMappingCheckEnabled())) {
            checkGuestOSHypervisorMapping(hypervisorType, hypervisorVersion, guestOs.getDisplayName(), osNameForHypervisor);
        }

        final GuestOSHypervisorVO guestOsMapping = new GuestOSHypervisorVO();
        guestOsMapping.setGuestOsId(guestOs.getId());
        guestOsMapping.setGuestOsName(osNameForHypervisor);
        guestOsMapping.setHypervisorType(hypervisorType.toString());
        guestOsMapping.setHypervisorVersion(hypervisorVersion);
        guestOsMapping.setIsUserDefined(true);
        return _guestOSHypervisorDao.persist(guestOsMapping);
    }

    private void checkGuestOSHypervisorMapping(HypervisorType hypervisorType, String hypervisorVersion, String guestOsName, String guestOsNameForHypervisor) {
        if (!canCheckGuestOsNameInHypervisor(hypervisorType)) {
            throw new InvalidParameterValueException(String.format("Guest OS mapping check is not supported for hypervisor: %s, please specify a valid hypervisor : VMware, XenServer", hypervisorType.toString()));
        }
        final HostVO host = _hostDao.findHostByHypervisorTypeAndVersion(hypervisorType, hypervisorVersion);
        if (host == null) {
            throw new CloudRuntimeException(String.format("No %s hypervisor with version: %s exists, please specify available hypervisor and version", hypervisorType.toString(), hypervisorVersion));
        }
        CheckGuestOsMappingAnswer answer = (CheckGuestOsMappingAnswer) _agentMgr.easySend(host.getId(), new CheckGuestOsMappingCommand(guestOsName, guestOsNameForHypervisor, hypervisorVersion));
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException(String.format("Invalid hypervisor os mapping: %s for guest os: %s, hypervisor: %s and version: %s", guestOsNameForHypervisor, guestOsName, hypervisorType.toString(), hypervisorVersion));
        }
    }

    private boolean canCheckGuestOsNameInHypervisor(HypervisorType hypervisorType) {
        return (hypervisorType == HypervisorType.VMware || hypervisorType == HypervisorType.XenServer);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_MAPPING_ADD, eventDescription = "Adding a new guest OS to hypervisor name mapping", async = true)
    public GuestOSHypervisor getAddedGuestOsMapping(final Long guestOsMappingId) {
        return getGuestOsHypervisor(guestOsMappingId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_HYPERVISOR_NAME_FETCH, eventDescription = "Getting guest OS names from hypervisor", async = true)
    public List<Pair<String, String>> getHypervisorGuestOsNames(GetHypervisorGuestOsNamesCmd getHypervisorGuestOsNamesCmd) {
        final HypervisorType hypervisorType = HypervisorType.getType(getHypervisorGuestOsNamesCmd.getHypervisor());
        if (!canCheckGuestOsNameInHypervisor(hypervisorType)) {
            throw new InvalidParameterValueException(String.format("Guest OS names cannot be fetched for hypervisor: %s, please specify a valid hypervisor : VMware, XenServer", hypervisorType.toString()));
        }

        final HostVO host = _hostDao.findHostByHypervisorTypeAndVersion(hypervisorType, getHypervisorGuestOsNamesCmd.getHypervisorVersion());
        if (host == null) {
            throw new CloudRuntimeException(String.format("No %s hypervisor with version: %s exists, please specify available hypervisor and version", hypervisorType.toString(), getHypervisorGuestOsNamesCmd.getHypervisorVersion()));
        }
        GetHypervisorGuestOsNamesAnswer answer = (GetHypervisorGuestOsNamesAnswer) _agentMgr.easySend(host.getId(), new GetHypervisorGuestOsNamesCommand(getHypervisorGuestOsNamesCmd.getKeyword()));
        if (answer == null || !answer.getResult()) {
            throw new CloudRuntimeException(String.format("Unable to get guest os names for hypervisor: %s, version: %s", hypervisorType.toString(), getHypervisorGuestOsNamesCmd.getHypervisorVersion()));
        }
        return answer.getHypervisorGuestOsNames();
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_ADD, eventDescription = "Adding new guest OS type", create = true)
    public GuestOS addGuestOs(final AddGuestOsCmd cmd) {
        final Long categoryId = cmd.getOsCategoryId();
        final String displayName = cmd.getOsDisplayName();
        final String name = cmd.getOsName();

        final GuestOSCategoryVO guestOsCategory = ApiDBUtils.findGuestOsCategoryById(categoryId);
        if (guestOsCategory == null) {
            throw new InvalidParameterValueException("Guest OS category not found. Please specify a valid Guest OS category");
        }

        final GuestOS guestOs = ApiDBUtils.findGuestOSByDisplayName(displayName);
        if (guestOs != null) {
            throw new InvalidParameterValueException("The specified Guest OS name : " + displayName + " already exists. Please specify a unique name");
        }

        logger.debug("GuestOSDetails");
        final GuestOSVO guestOsVo = new GuestOSVO();
        guestOsVo.setCategoryId(categoryId.longValue());
        guestOsVo.setDisplayName(displayName);
        guestOsVo.setName(name);
        guestOsVo.setIsUserDefined(true);
        guestOsVo.setDisplay(cmd.getForDisplay() == null ? true : cmd.getForDisplay());
        final GuestOS guestOsPersisted = _guestOSDao.persist(guestOsVo);

        persistGuestOsDetails(cmd.getDetails(), guestOsPersisted.getId());

        return guestOsPersisted;
    }

    private void persistGuestOsDetails(Map<String, String> details, long guestOsPersistedId) {
        for (Object key : details.keySet()) {
            _guestOsDetailsDao.addDetail(guestOsPersistedId, (String)key, details.get(key), false);
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_ADD, eventDescription = "Adding a new guest OS type", async = true)
    public GuestOS getAddedGuestOs(final Long guestOsId) {
        return getGuestOs(guestOsId);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_UPDATE, eventDescription = "updating guest OS type", async = true)
    public GuestOS updateGuestOs(final UpdateGuestOsCmd cmd) {
        final Long id = cmd.getId();
        final String displayName = cmd.getOsDisplayName();
        final Long osCategoryId = cmd.getOsCategoryId();
        final Boolean display = cmd.getForDisplay();
        final Map<String, String> details = cmd.getDetails();
        boolean updateNeeded = false;

        //check if guest OS exists
        final GuestOS guestOsHandle = ApiDBUtils.findGuestOSById(id);
        if (guestOsHandle == null) {
            throw new InvalidParameterValueException("Guest OS not found. Please specify a valid ID for the Guest OS");
        }

        //Check if update is needed
        if (StringUtils.isNotBlank(displayName) && !displayName.equals(guestOsHandle.getDisplayName())) {
            //Check if another Guest OS by same name exists
            final GuestOS duplicate = ApiDBUtils.findGuestOSByDisplayName(displayName);
            if (duplicate != null) {
                throw new InvalidParameterValueException("The specified Guest OS name : " + displayName + " already exists. Please specify a unique guest OS name");
            }
            updateNeeded = true;
        }

        if (osCategoryId != null) {
            if (_guestOSCategoryDao.findById(osCategoryId) == null) {
                throw new InvalidParameterValueException("Invalid OS category ID specified");
            }
            updateNeeded = true;
        }

        if (!guestOsHandle.getIsUserDefined() && (StringUtils.isNotBlank(displayName) || MapUtils.isNotEmpty(details)
                || display != null)) {
            throw new InvalidParameterValueException("Unable to modify system defined guest OS");
        }

        if (MapUtils.isNotEmpty(details)) {
            persistGuestOsDetails(details, id);
        }

        if (!updateNeeded) {
            return guestOsHandle;
        }

        final GuestOSVO guestOs = _guestOSDao.createForUpdate(id);
        if (StringUtils.isNotBlank(displayName)) {
            guestOs.setDisplayName(displayName);
        }
        if (cmd.getForDisplay() != null) {
            guestOs.setDisplay(cmd.getForDisplay());
        }
        if (osCategoryId != null) {
            guestOs.setCategoryId(osCategoryId);
        }
        if (_guestOSDao.update(id, guestOs)) {
            return _guestOSDao.findById(id);
        } else {
            return null;
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_REMOVE, eventDescription = "removing guest OS type", async = true)
    public boolean removeGuestOs(final RemoveGuestOsCmd cmd) {
        final Long id = cmd.getId();

        //check if guest OS exists
        final GuestOS guestOs = ApiDBUtils.findGuestOSById(id);
        if (guestOs == null) {
            throw new InvalidParameterValueException("Guest OS not found. Please specify a valid ID for the Guest OS");
        }

        if (!guestOs.getIsUserDefined()) {
            throw new InvalidParameterValueException("Unable to remove system defined guest OS");
        }

        return _guestOSDao.remove(id);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_MAPPING_UPDATE, eventDescription = "updating guest OS mapping", async = true)
    public GuestOSHypervisor updateGuestOsMapping(final UpdateGuestOsMappingCmd cmd) {
        final Long id = cmd.getId();
        final String osNameForHypervisor = cmd.getOsNameForHypervisor();

        //check if mapping exists
        final GuestOSHypervisor guestOsHypervisorHandle = _guestOSHypervisorDao.findById(id);
        if (guestOsHypervisorHandle == null) {
            throw new InvalidParameterValueException("Guest OS Mapping not found. Please specify a valid ID for the Guest OS Mapping");
        }

        if (!guestOsHypervisorHandle.getIsUserDefined()) {
            throw new InvalidParameterValueException("Unable to modify system defined Guest OS mapping");
        }

        if (Boolean.TRUE.equals(cmd.getOsMappingCheckEnabled())) {
            GuestOS guestOs = ApiDBUtils.findGuestOSById(guestOsHypervisorHandle.getGuestOsId());
            if (guestOs == null) {
                throw new InvalidParameterValueException("Unable to find the guest OS for the mapping");
            }
            checkGuestOSHypervisorMapping(HypervisorType.getType(guestOsHypervisorHandle.getHypervisorType()), guestOsHypervisorHandle.getHypervisorVersion(), guestOs.getDisplayName(), osNameForHypervisor);
        }

        final GuestOSHypervisorVO guestOsHypervisor = _guestOSHypervisorDao.createForUpdate(id);
        guestOsHypervisor.setGuestOsName(osNameForHypervisor);
        if (_guestOSHypervisorDao.update(id, guestOsHypervisor)) {
            return _guestOSHypervisorDao.findById(id);
        } else {
            return null;
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_GUEST_OS_MAPPING_REMOVE, eventDescription = "removing guest OS mapping", async = true)
    public boolean removeGuestOsMapping(final RemoveGuestOsMappingCmd cmd) {
        final Long id = cmd.getId();

        //check if mapping exists
        final GuestOSHypervisor guestOsHypervisorHandle = _guestOSHypervisorDao.findById(id);
        if (guestOsHypervisorHandle == null) {
            throw new InvalidParameterValueException("Guest OS Mapping not found. Please specify a valid ID for the Guest OS Mapping");
        }

        if (!guestOsHypervisorHandle.getIsUserDefined()) {
            throw new InvalidParameterValueException("Unable to remove system defined Guest OS mapping");
        }

        return _guestOSHypervisorDao.removeGuestOsMapping(id);

    }

    protected ConsoleProxyInfo getConsoleProxyForVm(final long dataCenterId, final VMInstanceVO userVm) {
        return _consoleProxyMgr.assignProxy(dataCenterId, userVm);
    }

    private ConsoleProxyVO startConsoleProxy(final long instanceId) {
        return _consoleProxyMgr.startProxy(instanceId, true);
    }

    private ConsoleProxyVO stopConsoleProxy(final VMInstanceVO systemVm, final boolean isForced) throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        _itMgr.advanceStop(systemVm.getUuid(), isForced);
        return _consoleProxyDao.findById(systemVm.getId());
    }

    private ConsoleProxyVO rebootConsoleProxy(final long instanceId) {
        _consoleProxyMgr.rebootProxy(instanceId);
        return _consoleProxyDao.findById(instanceId);
    }

    private ConsoleProxyVO forceRebootConsoleProxy(final VMInstanceVO systemVm)  throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        _itMgr.advanceStop(systemVm.getUuid(), false);
        return _consoleProxyMgr.startProxy(systemVm.getId(), true);
    }

    protected ConsoleProxyVO destroyConsoleProxy(final long instanceId) {
        final ConsoleProxyVO proxy = _consoleProxyDao.findById(instanceId);

        if (_consoleProxyMgr.destroyProxy(instanceId)) {
            return proxy;
        }
        return null;
    }

    @Override
    public String getConsoleAccessUrlRoot(final long vmId) {
        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        if (vm != null) {
            final ConsoleProxyInfo proxy = getConsoleProxyForVm(vm.getDataCenterId(), vm);
            if (proxy != null) {
                return proxy.getProxyImageUrl();
            }
        }
        return null;
    }

    @Override
    public Pair<Boolean, String> setConsoleAccessForVm(long vmId, String sessionUuid) {
        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        if (vm == null) {
            return new Pair<>(false, "Cannot find a VM with id = " + vmId);
        }
        final ConsoleProxyInfo proxy = getConsoleProxyForVm(vm.getDataCenterId(), vm);
        if (proxy == null) {
            return new Pair<>(false, "Cannot find a console proxy for the VM " + vmId);
        }
        AllowConsoleAccessCommand cmd = new AllowConsoleAccessCommand(sessionUuid);
        HostVO hostVO = _hostDao.findByTypeNameAndZoneId(vm.getDataCenterId(), proxy.getProxyName(), Type.ConsoleProxy);
        if (hostVO == null) {
            return new Pair<>(false, "Cannot find a console proxy agent for CPVM with name " + proxy.getProxyName());
        }
        Answer answer;
        try {
            answer = _agentMgr.send(hostVO.getId(), cmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            String errorMsg = "Could not send allow session command to CPVM: " + e.getMessage();
            logger.error(errorMsg, e);
            return new Pair<>(false, errorMsg);
        }
        boolean result = false;
        String details = "null answer";

        if (answer != null) {
            result = answer.getResult();
            details = answer.getDetails();
        }
        return new Pair<>(result, details);
    }

    @Override
    public String getConsoleAccessAddress(long vmId) {
        final VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        if (vm != null) {
            final ConsoleProxyInfo proxy = getConsoleProxyForVm(vm.getDataCenterId(), vm);
            return proxy != null ? proxy.getProxyAddress() : null;
        }
        return null;
    }

    @Override
    public Pair<String, Integer> getVncPort(final VirtualMachine vm) {
        if (vm.getHostId() == null) {
            logger.warn("VM " + vm.getHostName() + " does not have host, return -1 for its VNC port");
            return new Pair<>(null, -1);
        }

        if (logger.isTraceEnabled()) {
            logger.trace("Trying to retrieve VNC port from agent about VM " + vm.getHostName());
        }

        GetVncPortAnswer answer = null;
        if (vm.getState() == State.Migrating && vm.getLastHostId() != null) {
            answer = (GetVncPortAnswer)_agentMgr.easySend(vm.getLastHostId(), new GetVncPortCommand(vm.getId(), vm.getInstanceName()));
        } else {
            answer = (GetVncPortAnswer)_agentMgr.easySend(vm.getHostId(), new GetVncPortCommand(vm.getId(), vm.getInstanceName()));
        }
        if (answer != null && answer.getResult()) {
            return new Pair<>(answer.getAddress(), answer.getPort());
        }

        return new Pair<>(null, -1);
    }

    @Override
    public Pair<List<? extends Alert>, Integer> searchForAlerts(final ListAlertsCmd cmd) {
        final Filter searchFilter = new Filter(AlertVO.class, "lastSent", false, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchCriteria<AlertVO> sc = _alertDao.createSearchCriteria();

        final Object id = cmd.getId();
        final Object type = cmd.getType();
        final Object keyword = cmd.getKeyword();
        final Object name = cmd.getName();

        final Long zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), null);
        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }
        if (zoneId != null) {
            sc.addAnd("data_center_id", SearchCriteria.Op.EQ, zoneId);
        }

        if (keyword != null) {
            final SearchCriteria<AlertVO> scc = _alertDao.createSearchCriteria();
            scc.addOr("subject", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            scc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("subject", SearchCriteria.Op.SC, scc);
        }

        if (type != null) {
            sc.addAnd("type", SearchCriteria.Op.EQ, type);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }

        sc.addAnd("archived", SearchCriteria.Op.EQ, false);
        final Pair<List<AlertVO>, Integer> result = _alertDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public boolean archiveAlerts(final ArchiveAlertsCmd cmd) {
        final Long zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), null);
        return _alertDao.archiveAlert(cmd.getIds(), cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), zoneId);
    }

    @Override
    public boolean deleteAlerts(final DeleteAlertsCmd cmd) {
        final Long zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), null);
        return _alertDao.deleteAlert(cmd.getIds(), cmd.getType(), cmd.getStartDate(), cmd.getEndDate(), zoneId);
    }

    Pair<Boolean, List<Long>> getHostIdsForCapacityListing(Long zoneId, Long podId, Long clusterId, Integer capacityType, String tag) {
        if (StringUtils.isEmpty(tag)) {
            return new Pair<>(true, null);
        }
        Short type = capacityType == null ? null : capacityType.shortValue();
        if (type != null && Capacity.STORAGE_CAPACITY_TYPES.contains(type)) {
            return new Pair<>(false, null);
        }
        List<Long> hostIds = null;
        try {
            List<HostVO> hosts = _hostDao.listByHostTag(Type.Routing, clusterId, podId, zoneId, tag);
            hostIds = hosts.stream().map(HostVO::getId).collect(Collectors.toList());
        } catch (CloudRuntimeException ignored) {}
        return new Pair<>(CollectionUtils.isNotEmpty(hostIds), hostIds);
    }

    protected List<String> getResourceLimitTagsForCapacityListing() {
        List<String> tags = new ArrayList<>();
        tags.add(null);
        tags.addAll(resourceLimitService.getResourceLimitHostTags());
        tags.addAll(resourceLimitService.getResourceLimitStorageTags());
        tags = tags.stream().distinct().collect(Collectors.toList());
        return tags;
    }

    protected Pair<Boolean, List<Long>> getStoragePoolIdsForCapacityListing(Integer capacityType, String tag) {
        if (StringUtils.isEmpty(tag)) {
            return new Pair<>(true, null);
        }
        Short type = capacityType == null ? null : capacityType.shortValue();
        if (type != null && !Capacity.STORAGE_CAPACITY_TYPES.contains(type)) {
            return new Pair<>(false, null);
        }
        List<Long> storagePoolIds = storagePoolTagsDao.listPoolIdsByTag(tag);
        return new Pair<>(CollectionUtils.isNotEmpty(storagePoolIds), storagePoolIds);
    }

    protected List<SummedCapacity> getCapacitiesWithDetails(final Long zoneId, final Long podId, Long clusterId,
            final Integer capacityType, final String tag, int level, Long pageSize) {
        List<String> tags = new ArrayList<>();
        if (StringUtils.isNotEmpty(tag)) {
            tags.add(tag);
        } else {
            tags = getResourceLimitTagsForCapacityListing();
        }
        List<SummedCapacity> summedCapacities = new ArrayList<>();
        for (String t : tags) {
            List<SummedCapacity> taggedSummedCapacities = new ArrayList<>();
            Pair<Boolean, List<Long>> hostIdsForCapacity = getHostIdsForCapacityListing(zoneId, podId, clusterId, capacityType, t);
            Pair<Boolean, List<Long>> storagePoolIdsForCapacity = getStoragePoolIdsForCapacityListing(capacityType, t);
            if (hostIdsForCapacity.first() || storagePoolIdsForCapacity.first()) {
                final List<SummedCapacity> summedHostCapacities = _capacityDao.listCapacitiesGroupedByLevelAndType(
                        capacityType, zoneId, podId, clusterId, level, hostIdsForCapacity.second(),
                        storagePoolIdsForCapacity.second(), pageSize);
                if (summedHostCapacities != null) {
                    taggedSummedCapacities.addAll(summedHostCapacities);
                }
            }
            if (storagePoolIdsForCapacity.first()) {
                List<SummedCapacity> summedStorageCapacities = getStorageCapacities(clusterId, podId, zoneId,
                        storagePoolIdsForCapacity.second(), capacityType == null ? null : capacityType.shortValue());
                if (summedStorageCapacities != null) {
                    taggedSummedCapacities.addAll(summedStorageCapacities);
                }
            }
            taggedSummedCapacities.forEach(x -> x.setTag(t));
            summedCapacities.addAll(taggedSummedCapacities);
        }
        return summedCapacities;
    }

    @Override
    public List<CapacityVO> listTopConsumedResources(final ListCapacityCmd cmd) {

        final Integer capacityType = cmd.getType();
        Long zoneId = cmd.getZoneId();
        final Long podId = cmd.getPodId();
        final Long clusterId = cmd.getClusterId();
        final Boolean fetchLatest = cmd.getFetchLatest();
        final String tag = cmd.getTag();

        if (clusterId != null) {
            throw new InvalidParameterValueException("Currently clusterId param is not supported");
        }
        zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);

        if (fetchLatest != null && fetchLatest) {
            _alertMgr.recalculateCapacity();
        }

        int level = 3;
        if (zoneId == null && podId == null) {// Group by Zone, capacity type
            level = 1;
        } else if (podId == null) {// Group by Pod, capacity type
            level = 2;
        }

        final List<CapacityVO> capacities = new ArrayList<>();
        List<SummedCapacity> summedCapacities = getCapacitiesWithDetails(zoneId, podId, clusterId, capacityType, tag, level, cmd.getPageSizeVal());

        // Sort Capacities
        summedCapacities.sort((arg0, arg1) -> {
            if (arg0.getPercentUsed() < arg1.getPercentUsed()) {
                return 1;
            } else if (arg0.getPercentUsed().equals(arg1.getPercentUsed())) {
                return 0;
            }
            return -1;
        });


        Integer pageSize = null;
        try {
            pageSize = Integer.valueOf(cmd.getPageSizeVal().toString());
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("pageSize " + cmd.getPageSizeVal() + " is out of Integer range is not supported for this call");
        }

        summedCapacities = summedCapacities.subList(0, summedCapacities.size() < cmd.getPageSizeVal() ? summedCapacities.size() : pageSize);
        for (final SummedCapacity summedCapacity : summedCapacities) {
            final CapacityVO capacity = new CapacityVO(summedCapacity.getDataCenterId(), summedCapacity.getPodId(), summedCapacity.getClusterId(), summedCapacity.getCapacityType(),
                    summedCapacity.getPercentUsed());
            capacity.setUsedCapacity(summedCapacity.getUsedCapacity() + summedCapacity.getReservedCapacity());
            capacity.setTotalCapacity(summedCapacity.getTotalCapacity());
            capacity.setTag(summedCapacity.getTag());
            capacities.add(capacity);
        }
        return capacities;
    }

    List<SummedCapacity> getStorageCapacities(Long clusterId, Long podId, Long zoneId, List<Long> poolIds, Short capacityType) {
        List<Short> capacityTypes = Arrays.asList(Capacity.CAPACITY_TYPE_STORAGE, Capacity.CAPACITY_TYPE_SECONDARY_STORAGE,
                Capacity.CAPACITY_TYPE_BACKUP_STORAGE, Capacity.CAPACITY_TYPE_OBJECT_STORAGE);
        if (capacityType != null && !capacityTypes.contains(capacityType)) {
            return null;
        }
        if (capacityType != null) {
            capacityTypes = capacityTypes.stream().filter(x -> x.equals(capacityType)).collect(Collectors.toList());
        }
        if (CollectionUtils.isNotEmpty(poolIds)) {
            capacityTypes = capacityTypes.stream().filter(x -> x == Capacity.CAPACITY_TYPE_STORAGE).collect(Collectors.toList());
        }
        if (CollectionUtils.isEmpty(capacityTypes)) {
            return null;
        }
        final List<SummedCapacity> list = new ArrayList<>();
        List<DataCenterVO> dcList = new ArrayList<>();
        if (zoneId != null) {
            final DataCenterVO zone = ApiDBUtils.findZoneById(zoneId);
            if (zone == null || zone.getAllocationState() == AllocationState.Disabled) {
                return null;
            }
            dcList.add(zone);
        } else {
            dcList = _dcDao.listEnabledZones();
            podId = null;
            clusterId = null;
        }
        for (DataCenterVO dc : dcList) {
            List<CapacityVO> capacities = new ArrayList<>();
            if (capacityTypes.contains(Capacity.CAPACITY_TYPE_SECONDARY_STORAGE)) {
                capacities.add(_storageMgr.getSecondaryStorageUsedStats(null, dc.getId()));
            }
            if (capacityTypes.contains(Capacity.CAPACITY_TYPE_STORAGE)) {
                capacities.add(_storageMgr.getStoragePoolUsedStats(dc.getId(), podId, clusterId, poolIds));
            }
            if (capacityTypes.contains(Capacity.CAPACITY_TYPE_OBJECT_STORAGE)) {
                capacities.add(_storageMgr.getObjectStorageUsedStats(dc.getId()));
            }
            if (capacityTypes.contains(Capacity.CAPACITY_TYPE_BACKUP_STORAGE)) {
                capacities.add((CapacityVO) backupManager.getBackupStorageUsedStats(dc.getId()));
            }
            for (CapacityVO capacity : capacities) {
                if (capacity.getTotalCapacity() != 0) {
                    capacity.setUsedPercentage((float)capacity.getUsedCapacity() / capacity.getTotalCapacity());
                } else {
                    capacity.setUsedPercentage(0);
                }
                SummedCapacity summedCapacity = new SummedCapacity(capacity.getUsedCapacity(), capacity.getTotalCapacity(), capacity.getUsedPercentage(), capacity.getCapacityType(),
                        capacity.getDataCenterId(), capacity.getPodId(), capacity.getClusterId());
                list.add(summedCapacity);
            }
        }// End of for
        return list;
    }

    private void addZoneWideCapacitiesByType(final Integer capacityType, Long zId, List<CapacityVO> taggedCapacities) {
        if (capacityType == null) {
            taggedCapacities.add(_storageMgr.getSecondaryStorageUsedStats(null, zId));
            taggedCapacities.add(_storageMgr.getObjectStorageUsedStats(zId));
            taggedCapacities.add((CapacityVO) backupManager.getBackupStorageUsedStats(zId));
            return;
        }

        if (capacityType == Capacity.CAPACITY_TYPE_SECONDARY_STORAGE) {
            taggedCapacities.add(_storageMgr.getSecondaryStorageUsedStats(null, zId));
        } else if (capacityType == Capacity.CAPACITY_TYPE_OBJECT_STORAGE) {
            taggedCapacities.add(_storageMgr.getObjectStorageUsedStats(zId));
        } else if (capacityType == Capacity.CAPACITY_TYPE_BACKUP_STORAGE) {
            taggedCapacities.add((CapacityVO) backupManager.getBackupStorageUsedStats(zId));
        }
    }

    protected List<CapacityVO> listCapacitiesWithDetails(final Long zoneId, final Long podId, Long clusterId,
            final Integer capacityType, final String tag, List<Long> dcList) {
        List<String> tags = new ArrayList<>();
        if (StringUtils.isNotEmpty(tag)) {
            tags.add(tag);
        } else {
            tags = getResourceLimitTagsForCapacityListing();
        }
        List<CapacityVO> capacities = new ArrayList<>();
        for (String t : tags) {
            List<CapacityVO> taggedCapacities = new ArrayList<>();
            Pair<Boolean, List<Long>> hostIdsForCapacity = getHostIdsForCapacityListing(zoneId, podId, clusterId, capacityType, t);
            Pair<Boolean, List<Long>> storagePoolIdsForCapacity = getStoragePoolIdsForCapacityListing(capacityType, t);
            if (hostIdsForCapacity.first() || storagePoolIdsForCapacity.first()) {
                final List<SummedCapacity> summedCapacities = _capacityDao.findFilteredCapacityBy(capacityType,
                        zoneId, podId, clusterId, hostIdsForCapacity.second(), storagePoolIdsForCapacity.second());

                for (final SummedCapacity summedCapacity : summedCapacities) {
                    final CapacityVO capacity = new CapacityVO(null, summedCapacity.getDataCenterId(), summedCapacity.getPodId(), summedCapacity.getClusterId(),
                            summedCapacity.getUsedCapacity() + summedCapacity.getReservedCapacity(), summedCapacity.getTotalCapacity(), summedCapacity.getCapacityType());
                    capacity.setAllocatedCapacity(summedCapacity.getAllocatedCapacity());
                    taggedCapacities.add(capacity);
                }
            }
            for (final Long zId : dcList) {
                // op_host_Capacity contains only allocated stats and the real time
                // stats are stored "in memory".
                // List secondary, object and backup storage capacities only when the api is invoked for the zone layer.
                if (podId == null && clusterId == null && StringUtils.isEmpty(t)) {
                    addZoneWideCapacitiesByType(capacityType, zId, taggedCapacities);
                }
                if ((capacityType == null || capacityType == Capacity.CAPACITY_TYPE_STORAGE) && storagePoolIdsForCapacity.first()) {
                    taggedCapacities.add(_storageMgr.getStoragePoolUsedStats(zId, podId, clusterId, storagePoolIdsForCapacity.second()));
                }
            }
            taggedCapacities.forEach(x -> x.setTag(t));
            capacities.addAll(taggedCapacities);
        }
        return capacities;

    }

    @Override
    public List<CapacityVO> listCapacities(final ListCapacityCmd cmd) {

        final Integer capacityType = cmd.getType();
        Long zoneId = cmd.getZoneId();
        final Long podId = cmd.getPodId();
        final Long clusterId = cmd.getClusterId();
        final Boolean fetchLatest = cmd.getFetchLatest();
        final String tag = cmd.getTag();

        zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);
        if (fetchLatest != null && fetchLatest) {
            _alertMgr.recalculateCapacity();
        }
        List<Long> dcList = new ArrayList<>();
        if (zoneId != null) {
            dcList.add(zoneId);
        } else {
            if (podId == null && clusterId == null) {
                dcList.addAll(ApiDBUtils.listZones().stream().map(DataCenterVO::getId).collect(Collectors.toList()));
            } if (clusterId != null) {
                dcList.add(ApiDBUtils.findClusterById(clusterId).getDataCenterId());
            } else if (podId != null) {
                dcList.add(ApiDBUtils.findPodById(podId).getDataCenterId());
            }
        }
        return listCapacitiesWithDetails(zoneId, podId, clusterId, capacityType, tag, dcList);
    }

    @Override
    public long getMemoryOrCpuCapacityByHost(final Long hostId, final short capacityType) {

        final CapacityVO capacity = _capacityDao.findByHostIdType(hostId, capacityType);
        return capacity == null ? 0 : capacity.getReservedCapacity() + capacity.getUsedCapacity();

    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(CreateAccountCmd.class);
        cmdList.add(DeleteAccountCmd.class);
        cmdList.add(DisableAccountCmd.class);
        cmdList.add(EnableAccountCmd.class);
        cmdList.add(LockAccountCmd.class);
        cmdList.add(UpdateAccountCmd.class);
        cmdList.add(CreateCounterCmd.class);
        cmdList.add(DeleteCounterCmd.class);
        cmdList.add(AddClusterCmd.class);
        cmdList.add(DeleteClusterCmd.class);
        cmdList.add(ListClustersCmd.class);
        cmdList.add(UpdateClusterCmd.class);
        cmdList.add(ListCfgsByCmd.class);
        cmdList.add(ListCfgGroupsByCmd.class);
        cmdList.add(ListHypervisorCapabilitiesCmd.class);
        cmdList.add(UpdateCfgCmd.class);
        cmdList.add(UpdateHypervisorCapabilitiesCmd.class);
        cmdList.add(ResetCfgCmd.class);
        cmdList.add(CreateDomainCmd.class);
        cmdList.add(DeleteDomainCmd.class);
        cmdList.add(ListDomainChildrenCmd.class);
        cmdList.add(ListDomainsCmd.class);
        cmdList.add(ListDomainsCmdByAdmin.class);
        cmdList.add(UpdateDomainCmd.class);
        cmdList.add(MoveDomainCmd.class);
        cmdList.add(AddHostCmd.class);
        cmdList.add(AddSecondaryStorageCmd.class);
        cmdList.add(CancelHostMaintenanceCmd.class);
        cmdList.add(CancelHostAsDegradedCmd.class);
        cmdList.add(DeclareHostAsDegradedCmd.class);
        cmdList.add(DeleteHostCmd.class);
        cmdList.add(ListHostsCmd.class);
        cmdList.add(ListHostTagsCmd.class);
        cmdList.add(FindHostsForMigrationCmd.class);
        cmdList.add(PrepareForHostMaintenanceCmd.class);
        cmdList.add(ReconnectHostCmd.class);
        cmdList.add(UpdateHostCmd.class);
        cmdList.add(UpdateHostPasswordCmd.class);
        cmdList.add(AddNetworkDeviceCmd.class);
        cmdList.add(AddNetworkServiceProviderCmd.class);
        cmdList.add(CreateNetworkOfferingCmd.class);
        cmdList.add(CreatePhysicalNetworkCmd.class);
        cmdList.add(CreateStorageNetworkIpRangeCmd.class);
        cmdList.add(DeleteNetworkDeviceCmd.class);
        cmdList.add(DeleteNetworkOfferingCmd.class);
        cmdList.add(DeleteNetworkServiceProviderCmd.class);
        cmdList.add(DeletePhysicalNetworkCmd.class);
        cmdList.add(DeleteStorageNetworkIpRangeCmd.class);
        cmdList.add(ListNetworkDeviceCmd.class);
        cmdList.add(ListNetworkServiceProvidersCmd.class);
        cmdList.add(ListPhysicalNetworksCmd.class);
        cmdList.add(ListStorageNetworkIpRangeCmd.class);
        cmdList.add(ListSupportedNetworkServicesCmd.class);
        cmdList.add(UpdateNetworkOfferingCmd.class);
        cmdList.add(UpdateNetworkServiceProviderCmd.class);
        cmdList.add(UpdatePhysicalNetworkCmd.class);
        cmdList.add(UpdateStorageNetworkIpRangeCmd.class);
        cmdList.add(DedicateGuestVlanRangeCmd.class);
        cmdList.add(ListDedicatedGuestVlanRangesCmd.class);
        cmdList.add(ReleaseDedicatedGuestVlanRangeCmd.class);
        cmdList.add(CreateDiskOfferingCmd.class);
        cmdList.add(CreateServiceOfferingCmd.class);
        cmdList.add(DeleteDiskOfferingCmd.class);
        cmdList.add(DeleteServiceOfferingCmd.class);
        cmdList.add(IsAccountAllowedToCreateOfferingsWithTagsCmd.class);
        cmdList.add(UpdateDiskOfferingCmd.class);
        cmdList.add(UpdateServiceOfferingCmd.class);
        cmdList.add(CreatePodCmd.class);
        cmdList.add(DeletePodCmd.class);
        cmdList.add(ListPodsByCmd.class);
        cmdList.add(UpdatePodCmd.class);
        cmdList.add(AddRegionCmd.class);
        cmdList.add(RemoveRegionCmd.class);
        cmdList.add(UpdateRegionCmd.class);
        cmdList.add(ListAlertsCmd.class);
        cmdList.add(ListAlertTypesCmd.class);
        cmdList.add(ListCapacityCmd.class);
        cmdList.add(UpdatePodManagementNetworkIpRangeCmd.class);
        cmdList.add(UploadCustomCertificateCmd.class);
        cmdList.add(ConfigureVirtualRouterElementCmd.class);
        cmdList.add(CreateVirtualRouterElementCmd.class);
        cmdList.add(DestroyRouterCmd.class);
        cmdList.add(ListRoutersCmd.class);
        cmdList.add(ListVirtualRouterElementsCmd.class);
        cmdList.add(RebootRouterCmd.class);
        cmdList.add(StartRouterCmd.class);
        cmdList.add(StopRouterCmd.class);
        cmdList.add(UpgradeRouterCmd.class);
        cmdList.add(AddSwiftCmd.class);
        cmdList.add(CancelPrimaryStorageMaintenanceCmd.class);
        cmdList.add(ChangeStoragePoolScopeCmd.class);
        cmdList.add(CreateStoragePoolCmd.class);
        cmdList.add(DeletePoolCmd.class);
        cmdList.add(ListSwiftsCmd.class);
        cmdList.add(ListStoragePoolsCmd.class);
        cmdList.add(ListStorageTagsCmd.class);
        cmdList.add(ListStorageAccessGroupsCmd.class);
        cmdList.add(FindStoragePoolsForMigrationCmd.class);
        cmdList.add(PreparePrimaryStorageForMaintenanceCmd.class);
        cmdList.add(UpdateStoragePoolCmd.class);
        cmdList.add(SyncStoragePoolCmd.class);
        cmdList.add(UpdateStorageCapabilitiesCmd.class);
        cmdList.add(UpdateImageStoreCmd.class);
        cmdList.add(ConfigureStorageAccessCmd.class);
        cmdList.add(DestroySystemVmCmd.class);
        cmdList.add(ListSystemVMsCmd.class);
        cmdList.add(MigrateSystemVMCmd.class);
        cmdList.add(RebootSystemVmCmd.class);
        cmdList.add(StartSystemVMCmd.class);
        cmdList.add(StopSystemVmCmd.class);
        cmdList.add(UpgradeSystemVMCmd.class);
        cmdList.add(PrepareTemplateCmd.class);
        cmdList.add(AddTrafficMonitorCmd.class);
        cmdList.add(AddTrafficTypeCmd.class);
        cmdList.add(DeleteTrafficMonitorCmd.class);
        cmdList.add(DeleteTrafficTypeCmd.class);
        cmdList.add(GenerateUsageRecordsCmd.class);
        cmdList.add(ListUsageRecordsCmd.class);
        cmdList.add(RemoveRawUsageRecordsCmd.class);
        cmdList.add(ListTrafficMonitorsCmd.class);
        cmdList.add(ListTrafficTypeImplementorsCmd.class);
        cmdList.add(ListTrafficTypesCmd.class);
        cmdList.add(ListUsageTypesCmd.class);
        cmdList.add(UpdateTrafficTypeCmd.class);
        cmdList.add(CreateUserCmd.class);
        cmdList.add(DeleteUserCmd.class);
        cmdList.add(DisableUserCmd.class);
        cmdList.add(EnableUserCmd.class);
        cmdList.add(GetUserCmd.class);
        cmdList.add(ListUsersCmd.class);
        cmdList.add(LockUserCmd.class);
        cmdList.add(MoveUserCmd.class);
        cmdList.add(RegisterCmd.class);
        cmdList.add(UpdateUserCmd.class);
        cmdList.add(CreateVlanIpRangeCmd.class);
        cmdList.add(UpdateVlanIpRangeCmd.class);
        cmdList.add(DeleteVlanIpRangeCmd.class);
        cmdList.add(ListVlanIpRangesCmd.class);
        cmdList.add(DedicatePublicIpRangeCmd.class);
        cmdList.add(ReleasePublicIpRangeCmd.class);
        cmdList.add(AssignVMCmd.class);
        cmdList.add(MigrateVMCmd.class);
        cmdList.add(MigrateVirtualMachineWithVolumeCmd.class);
        cmdList.add(RecoverVMCmd.class);
        cmdList.add(CreatePrivateGatewayCmd.class);
        cmdList.add(CreateVPCOfferingCmd.class);
        cmdList.add(DeletePrivateGatewayCmd.class);
        cmdList.add(DeleteVPCOfferingCmd.class);
        cmdList.add(UpdateVPCOfferingCmd.class);
        cmdList.add(CreateZoneCmd.class);
        cmdList.add(DeleteZoneCmd.class);
        cmdList.add(MarkDefaultZoneForAccountCmd.class);
        cmdList.add(UpdateZoneCmd.class);
        cmdList.add(AddAccountToProjectCmd.class);
        cmdList.add(AddUserToProjectCmd.class);
        cmdList.add(DeleteAccountFromProjectCmd.class);
        cmdList.add(DeleteUserFromProjectCmd.class);
        cmdList.add(ListAccountsCmd.class);
        cmdList.add(ListProjectAccountsCmd.class);
        cmdList.add(AssociateIPAddrCmd.class);
        cmdList.add(DisassociateIPAddrCmd.class);
        cmdList.add(ReserveIPAddrCmd.class);
        cmdList.add(ReleaseIPAddrCmd.class);
        cmdList.add(ListPublicIpAddressesCmd.class);
        cmdList.add(CreateAutoScalePolicyCmd.class);
        cmdList.add(CreateAutoScaleVmGroupCmd.class);
        cmdList.add(CreateAutoScaleVmProfileCmd.class);
        cmdList.add(CreateConditionCmd.class);
        cmdList.add(DeleteAutoScalePolicyCmd.class);
        cmdList.add(DeleteAutoScaleVmGroupCmd.class);
        cmdList.add(DeleteAutoScaleVmProfileCmd.class);
        cmdList.add(DeleteConditionCmd.class);
        cmdList.add(DisableAutoScaleVmGroupCmd.class);
        cmdList.add(EnableAutoScaleVmGroupCmd.class);
        cmdList.add(ListAutoScalePoliciesCmd.class);
        cmdList.add(ListAutoScaleVmGroupsCmd.class);
        cmdList.add(ListAutoScaleVmProfilesCmd.class);
        cmdList.add(ListConditionsCmd.class);
        cmdList.add(ListCountersCmd.class);
        cmdList.add(UpdateAutoScalePolicyCmd.class);
        cmdList.add(UpdateAutoScaleVmGroupCmd.class);
        cmdList.add(UpdateAutoScaleVmProfileCmd.class);
        cmdList.add(UpdateConditionCmd.class);
        cmdList.add(ListCapabilitiesCmd.class);
        cmdList.add(ListEventsCmd.class);
        cmdList.add(ListEventTypesCmd.class);
        cmdList.add(CreateEgressFirewallRuleCmd.class);
        cmdList.add(CreateFirewallRuleCmd.class);
        cmdList.add(CreatePortForwardingRuleCmd.class);
        cmdList.add(DeleteEgressFirewallRuleCmd.class);
        cmdList.add(DeleteFirewallRuleCmd.class);
        cmdList.add(DeletePortForwardingRuleCmd.class);
        cmdList.add(ListEgressFirewallRulesCmd.class);
        cmdList.add(ListFirewallRulesCmd.class);
        cmdList.add(ListPortForwardingRulesCmd.class);
        cmdList.add(UpdatePortForwardingRuleCmd.class);
        cmdList.add(ListGuestOsCategoriesCmd.class);
        cmdList.add(AddGuestOsCategoryCmd.class);
        cmdList.add(UpdateGuestOsCategoryCmd.class);
        cmdList.add(DeleteGuestOsCategoryCmd.class);
        cmdList.add(ListGuestOsCmd.class);
        cmdList.add(ListGuestOsMappingCmd.class);
        cmdList.add(AddGuestOsCmd.class);
        cmdList.add(AddGuestOsMappingCmd.class);
        cmdList.add(UpdateGuestOsCmd.class);
        cmdList.add(UpdateGuestOsMappingCmd.class);
        cmdList.add(RemoveGuestOsCmd.class);
        cmdList.add(RemoveGuestOsMappingCmd.class);
        cmdList.add(GetHypervisorGuestOsNamesCmd.class);
        cmdList.add(AttachIsoCmd.class);
        cmdList.add(CopyIsoCmd.class);
        cmdList.add(DeleteIsoCmd.class);
        cmdList.add(DetachIsoCmd.class);
        cmdList.add(ExtractIsoCmd.class);
        cmdList.add(ListIsoPermissionsCmd.class);
        cmdList.add(ListIsosCmd.class);
        cmdList.add(RegisterIsoCmd.class);
        cmdList.add(UpdateIsoCmd.class);
        cmdList.add(UpdateIsoPermissionsCmd.class);
        cmdList.add(ListAsyncJobsCmd.class);
        cmdList.add(QueryAsyncJobResultCmd.class);
        cmdList.add(AssignToLoadBalancerRuleCmd.class);
        cmdList.add(CreateLBStickinessPolicyCmd.class);
        cmdList.add(CreateLBHealthCheckPolicyCmd.class);
        cmdList.add(CreateLoadBalancerRuleCmd.class);
        cmdList.add(DeleteLBStickinessPolicyCmd.class);
        cmdList.add(DeleteLBHealthCheckPolicyCmd.class);
        cmdList.add(DeleteLoadBalancerRuleCmd.class);
        cmdList.add(ListLBStickinessPoliciesCmd.class);
        cmdList.add(ListLBHealthCheckPoliciesCmd.class);
        cmdList.add(ListLoadBalancerRuleInstancesCmd.class);
        cmdList.add(ListLoadBalancerRulesCmd.class);
        cmdList.add(RemoveFromLoadBalancerRuleCmd.class);
        cmdList.add(UpdateLoadBalancerRuleCmd.class);
        cmdList.add(CreateIpForwardingRuleCmd.class);
        cmdList.add(DeleteIpForwardingRuleCmd.class);
        cmdList.add(DisableStaticNatCmd.class);
        cmdList.add(EnableStaticNatCmd.class);
        cmdList.add(ListIpForwardingRulesCmd.class);
        cmdList.add(CreateNetworkACLCmd.class);
        cmdList.add(CreateNetworkCmd.class);
        cmdList.add(DeleteNetworkACLCmd.class);
        cmdList.add(DeleteNetworkCmd.class);
        cmdList.add(ListNetworkACLListsCmd.class);
        cmdList.add(ListNetworkACLsCmd.class);
        cmdList.add(ListNetworkOfferingsCmd.class);
        cmdList.add(ListNetworkPermissionsCmd.class);
        cmdList.add(ListNetworkProtocolsCmd.class);
        cmdList.add(ListNetworksCmd.class);
        cmdList.add(RestartNetworkCmd.class);
        cmdList.add(UpdateNetworkCmd.class);
        cmdList.add(CreateNetworkPermissionsCmd.class);
        cmdList.add(ListNetworkPermissionsCmd.class);
        cmdList.add(RemoveNetworkPermissionsCmd.class);
        cmdList.add(ResetNetworkPermissionsCmd.class);
        cmdList.add(ListDiskOfferingsCmd.class);
        cmdList.add(ListServiceOfferingsCmd.class);
        cmdList.add(ActivateProjectCmd.class);
        cmdList.add(CreateProjectCmd.class);
        cmdList.add(DeleteProjectCmd.class);
        cmdList.add(DeleteProjectInvitationCmd.class);
        cmdList.add(ListProjectInvitationsCmd.class);
        cmdList.add(ListProjectsCmd.class);
        cmdList.add(SuspendProjectCmd.class);
        cmdList.add(UpdateProjectCmd.class);
        cmdList.add(UpdateProjectInvitationCmd.class);
        cmdList.add(ListRegionsCmd.class);
        cmdList.add(GetCloudIdentifierCmd.class);
        cmdList.add(ListHypervisorsCmd.class);
        cmdList.add(ListResourceLimitsCmd.class);
        cmdList.add(UpdateResourceCountCmd.class);
        cmdList.add(UpdateResourceLimitCmd.class);
        cmdList.add(AuthorizeSecurityGroupEgressCmd.class);
        cmdList.add(AuthorizeSecurityGroupIngressCmd.class);
        cmdList.add(CreateSecurityGroupCmd.class);
        cmdList.add(DeleteSecurityGroupCmd.class);
        cmdList.add(ListSecurityGroupsCmd.class);
        cmdList.add(RevokeSecurityGroupEgressCmd.class);
        cmdList.add(RevokeSecurityGroupIngressCmd.class);
        cmdList.add(UpdateSecurityGroupCmd.class);
        cmdList.add(CreateSnapshotCmd.class);
        cmdList.add(CreateSnapshotBackupCmd.class);
        cmdList.add(CreateSnapshotFromVMSnapshotCmd.class);
        cmdList.add(CopySnapshotCmd.class);
        cmdList.add(DeleteSnapshotCmd.class);
        cmdList.add(ExtractSnapshotCmd.class);
        cmdList.add(ArchiveSnapshotCmd.class);
        cmdList.add(CreateSnapshotPolicyCmd.class);
        cmdList.add(UpdateSnapshotPolicyCmd.class);
        cmdList.add(DeleteSnapshotPoliciesCmd.class);
        cmdList.add(ListSnapshotPoliciesCmd.class);
        cmdList.add(ListSnapshotsCmd.class);
        cmdList.add(RevertSnapshotCmd.class);
        cmdList.add(CreateSSHKeyPairCmd.class);
        cmdList.add(DeleteSSHKeyPairCmd.class);
        cmdList.add(ListSSHKeyPairsCmd.class);
        cmdList.add(RegisterSSHKeyPairCmd.class);
        cmdList.add(CreateTagsCmd.class);
        cmdList.add(DeleteTagsCmd.class);
        cmdList.add(ListTagsCmd.class);
        cmdList.add(CopyTemplateCmd.class);
        cmdList.add(CreateTemplateCmd.class);
        cmdList.add(DeleteTemplateCmd.class);
        cmdList.add(ExtractTemplateCmd.class);
        cmdList.add(ListTemplatePermissionsCmd.class);
        cmdList.add(ListTemplatesCmd.class);
        cmdList.add(ListDetailOptionsCmd.class);
        cmdList.add(RegisterTemplateCmd.class);
        cmdList.add(UpdateTemplateCmd.class);
        cmdList.add(UpdateTemplatePermissionsCmd.class);
        cmdList.add(AddNicToVMCmd.class);
        cmdList.add(DeployVMCmd.class);
        cmdList.add(DestroyVMCmd.class);
        cmdList.add(ExpungeVMCmd.class);
        cmdList.add(GetVMPasswordCmd.class);
        cmdList.add(ListVMsCmd.class);
        cmdList.add(CloneVMCmd.class);
        cmdList.add(ScaleVMCmd.class);
        cmdList.add(RebootVMCmd.class);
        cmdList.add(RemoveNicFromVMCmd.class);
        cmdList.add(ResetVMPasswordCmd.class);
        cmdList.add(ResetVMSSHKeyCmd.class);
        cmdList.add(ResetVMUserDataCmd.class);
        cmdList.add(RestoreVMCmd.class);
        cmdList.add(StartVMCmd.class);
        cmdList.add(StopVMCmd.class);
        cmdList.add(UpdateDefaultNicForVMCmd.class);
        cmdList.add(UpdateVmNicLinkStateCmd.class);
        cmdList.add(UpdateVMCmd.class);
        cmdList.add(UpgradeVMCmd.class);
        cmdList.add(CreateVMGroupCmd.class);
        cmdList.add(DeleteVMGroupCmd.class);
        cmdList.add(ListVMGroupsCmd.class);
        cmdList.add(UpdateVMGroupCmd.class);
        cmdList.add(AttachVolumeCmd.class);
        cmdList.add(CheckAndRepairVolumeCmd.class);
        cmdList.add(CreateVolumeCmd.class);
        cmdList.add(DeleteVolumeCmd.class);
        cmdList.add(UpdateVolumeCmd.class);
        cmdList.add(DetachVolumeCmd.class);
        cmdList.add(ExtractVolumeCmd.class);
        cmdList.add(ListVolumesCmd.class);
        cmdList.add(MigrateVolumeCmd.class);
        cmdList.add(ResizeVolumeCmd.class);
        cmdList.add(UploadVolumeCmd.class);
        cmdList.add(DestroyVolumeCmd.class);
        cmdList.add(RecoverVolumeCmd.class);
        cmdList.add(UpdateCompressDedupCmd.class);
        cmdList.add(ChangeOfferingForVolumeCmd.class);
        cmdList.add(CreateStaticRouteCmd.class);
        cmdList.add(CreateVPCCmd.class);
        cmdList.add(DeleteStaticRouteCmd.class);
        cmdList.add(DeleteVPCCmd.class);
        cmdList.add(ListPrivateGatewaysCmd.class);
        cmdList.add(ListStaticRoutesCmd.class);
        cmdList.add(ListVPCOfferingsCmd.class);
        cmdList.add(ListVPCsCmd.class);
        cmdList.add(RestartVPCCmd.class);
        cmdList.add(UpdateVPCCmd.class);
        cmdList.add(AddVpnUserCmd.class);
        cmdList.add(CreateRemoteAccessVpnCmd.class);
        cmdList.add(CreateVpnConnectionCmd.class);
        cmdList.add(CreateVpnCustomerGatewayCmd.class);
        cmdList.add(CreateVpnGatewayCmd.class);
        cmdList.add(DeleteRemoteAccessVpnCmd.class);
        cmdList.add(DeleteVpnConnectionCmd.class);
        cmdList.add(DeleteVpnCustomerGatewayCmd.class);
        cmdList.add(DeleteVpnGatewayCmd.class);
        cmdList.add(ListRemoteAccessVpnsCmd.class);
        cmdList.add(ListVpnConnectionsCmd.class);
        cmdList.add(ListVpnCustomerGatewaysCmd.class);
        cmdList.add(ListVpnGatewaysCmd.class);
        cmdList.add(ListVpnUsersCmd.class);
        cmdList.add(RemoveVpnUserCmd.class);
        cmdList.add(ResetVpnConnectionCmd.class);
        cmdList.add(UpdateVpnCustomerGatewayCmd.class);
        cmdList.add(ListZonesCmd.class);
        cmdList.add(ListVMSnapshotCmd.class);
        cmdList.add(CreateVMSnapshotCmd.class);
        cmdList.add(RevertToVMSnapshotCmd.class);
        cmdList.add(DeleteVMSnapshotCmd.class);
        cmdList.add(AddIpToVmNicCmd.class);
        cmdList.add(RemoveIpFromVmNicCmd.class);
        cmdList.add(UpdateVmNicIpCmd.class);
        cmdList.add(ListNicsCmd.class);
        cmdList.add(ArchiveAlertsCmd.class);
        cmdList.add(DeleteAlertsCmd.class);
        cmdList.add(ArchiveEventsCmd.class);
        cmdList.add(DownloadEventsCmd.class);
        cmdList.add(DeleteEventsCmd.class);
        cmdList.add(CreateGlobalLoadBalancerRuleCmd.class);
        cmdList.add(DeleteGlobalLoadBalancerRuleCmd.class);
        cmdList.add(ListGlobalLoadBalancerRuleCmd.class);
        cmdList.add(UpdateGlobalLoadBalancerRuleCmd.class);
        cmdList.add(AssignToGlobalLoadBalancerRuleCmd.class);
        cmdList.add(RemoveFromGlobalLoadBalancerRuleCmd.class);
        cmdList.add(ListStorageProvidersCmd.class);
        cmdList.add(AddImageStoreCmd.class);
        cmdList.add(AddImageStoreS3CMD.class);
        cmdList.add(ListImageStoresCmd.class);
        cmdList.add(DeleteImageStoreCmd.class);
        cmdList.add(CreateSecondaryStagingStoreCmd.class);
        cmdList.add(ListSecondaryStagingStoresCmd.class);
        cmdList.add(DeleteSecondaryStagingStoreCmd.class);
        cmdList.add(UpdateCloudToUseObjectStoreCmd.class);
        cmdList.add(CreateApplicationLoadBalancerCmd.class);
        cmdList.add(ListApplicationLoadBalancersCmd.class);
        cmdList.add(DeleteApplicationLoadBalancerCmd.class);
        cmdList.add(ConfigureInternalLoadBalancerElementCmd.class);
        cmdList.add(CreateInternalLoadBalancerElementCmd.class);
        cmdList.add(ListInternalLoadBalancerElementsCmd.class);
        cmdList.add(CreateAffinityGroupCmd.class);
        cmdList.add(DeleteAffinityGroupCmd.class);
        cmdList.add(ListAffinityGroupsCmd.class);
        cmdList.add(UpdateVMAffinityGroupCmd.class);
        cmdList.add(ListAffinityGroupTypesCmd.class);
        cmdList.add(CreatePortableIpRangeCmd.class);
        cmdList.add(DeletePortableIpRangeCmd.class);
        cmdList.add(ListPortableIpRangesCmd.class);
        cmdList.add(ListDeploymentPlannersCmd.class);
        cmdList.add(ReleaseHostReservationCmd.class);
        cmdList.add(ScaleSystemVMCmd.class);
        cmdList.add(AddResourceDetailCmd.class);
        cmdList.add(RemoveResourceDetailCmd.class);
        cmdList.add(ListResourceDetailsCmd.class);
        cmdList.add(StopInternalLBVMCmd.class);
        cmdList.add(StartInternalLBVMCmd.class);
        cmdList.add(ListInternalLBVMsCmd.class);
        cmdList.add(ListNetworkIsolationMethodsCmd.class);
        cmdList.add(CreateNetworkACLListCmd.class);
        cmdList.add(DeleteNetworkACLListCmd.class);
        cmdList.add(ListNetworkACLListsCmd.class);
        cmdList.add(ReplaceNetworkACLListCmd.class);
        cmdList.add(UpdateNetworkACLItemCmd.class);
        cmdList.add(MoveNetworkAclItemCmd.class);
        cmdList.add(CleanVMReservationsCmd.class);
        cmdList.add(UpgradeRouterTemplateCmd.class);
        cmdList.add(UploadSslCertCmd.class);
        cmdList.add(DeleteSslCertCmd.class);
        cmdList.add(ListSslCertsCmd.class);
        cmdList.add(AssignCertToLoadBalancerCmd.class);
        cmdList.add(RemoveCertFromLoadBalancerCmd.class);
        cmdList.add(GenerateAlertCmd.class);
        cmdList.add(ListOvsElementsCmd.class);
        cmdList.add(ConfigureOvsElementCmd.class);
        cmdList.add(GetVMUserDataCmd.class);
        cmdList.add(UpdateEgressFirewallRuleCmd.class);
        cmdList.add(UpdateFirewallRuleCmd.class);
        cmdList.add(UpdateNetworkACLListCmd.class);
        cmdList.add(UpdateApplicationLoadBalancerCmd.class);
        cmdList.add(UpdateIPAddrCmd.class);
        cmdList.add(UpdateRemoteAccessVpnCmd.class);
        cmdList.add(UpdateVpnConnectionCmd.class);
        cmdList.add(UpdateVpnGatewayCmd.class);
        cmdList.add(ListQuarantinedIpsCmd.class);
        cmdList.add(RemoveQuarantinedIpCmd.class);
        // separated admin commands
        cmdList.add(ListAccountsCmdByAdmin.class);
        cmdList.add(ListZonesCmdByAdmin.class);
        cmdList.add(ListTemplatesCmdByAdmin.class);
        cmdList.add(CreateTemplateCmdByAdmin.class);
        cmdList.add(CopyTemplateCmdByAdmin.class);
        cmdList.add(RegisterTemplateCmdByAdmin.class);
        cmdList.add(ListTemplatePermissionsCmdByAdmin.class);
        cmdList.add(ListSnapshotsCmdByAdmin.class);
        cmdList.add(RegisterIsoCmdByAdmin.class);
        cmdList.add(CopyIsoCmdByAdmin.class);
        cmdList.add(ListIsosCmdByAdmin.class);
        cmdList.add(AttachIsoCmdByAdmin.class);
        cmdList.add(DetachIsoCmdByAdmin.class);
        cmdList.add(ListIsoPermissionsCmdByAdmin.class);
        cmdList.add(UpdateVMAffinityGroupCmdByAdmin.class);
        cmdList.add(AddNicToVMCmdByAdmin.class);
        cmdList.add(RemoveNicFromVMCmdByAdmin.class);
        cmdList.add(UpdateDefaultNicForVMCmdByAdmin.class);
        cmdList.add(ListLoadBalancerRuleInstancesCmdByAdmin.class);
        cmdList.add(DeployVMCmdByAdmin.class);
        cmdList.add(DestroyVMCmdByAdmin.class);
        cmdList.add(DeployVMVolumeCmdByAdmin.class);
        cmdList.add(DeployVMVolumeCmd.class);
        cmdList.add(RebootVMCmdByAdmin.class);
        cmdList.add(ResetVMPasswordCmdByAdmin.class);
        cmdList.add(ResetVMSSHKeyCmdByAdmin.class);
        cmdList.add(RestoreVMCmdByAdmin.class);
        cmdList.add(ScaleVMCmdByAdmin.class);
        cmdList.add(StartVMCmdByAdmin.class);
        cmdList.add(StopVMCmdByAdmin.class);
        cmdList.add(UpdateVMCmdByAdmin.class);
        cmdList.add(UpgradeVMCmdByAdmin.class);
        cmdList.add(RevertToVMSnapshotCmdByAdmin.class);
        cmdList.add(ListVMsCmdByAdmin.class);
        cmdList.add(AttachVolumeCmdByAdmin.class);
        cmdList.add(CreateVolumeCmdByAdmin.class);
        cmdList.add(DetachVolumeCmdByAdmin.class);
        cmdList.add(MigrateVolumeCmdByAdmin.class);
        cmdList.add(ResizeVolumeCmdByAdmin.class);
        cmdList.add(UpdateVolumeCmdByAdmin.class);
        cmdList.add(UploadVolumeCmdByAdmin.class);
        cmdList.add(ListVolumesCmdByAdmin.class);
        cmdList.add(DestroyVolumeCmdByAdmin.class);
        cmdList.add(RecoverVolumeCmdByAdmin.class);
        cmdList.add(AssociateIPAddrCmdByAdmin.class);
        cmdList.add(ListPublicIpAddressesCmdByAdmin.class);
        cmdList.add(CreateNetworkCmdByAdmin.class);
        // cmdList.add(ListNetworksCmdByAdmin.class);
        // cmdList.add(CreateVPCCmdByAdmin.class);
        // cmdList.add(ListVPCsCmdByAdmin.class);
        cmdList.add(UpdateVPCCmdByAdmin.class);
        cmdList.add(CreatePrivateGatewayByAdminCmd.class);
        cmdList.add(ListPrivateGatewaysCmdByAdminCmd.class);
        cmdList.add(UpdateLBStickinessPolicyCmd.class);
        cmdList.add(UpdateLBHealthCheckPolicyCmd.class);
        cmdList.add(GetUploadParamsForTemplateCmd.class);
        cmdList.add(GetUploadParamsForVolumeCmd.class);
        cmdList.add(MigrateNetworkCmd.class);
        cmdList.add(MigrateVPCCmd.class);
        cmdList.add(AcquirePodIpCmdByAdmin.class);
        cmdList.add(RevokeTemplateDirectDownloadCertificateCmd.class);
        cmdList.add(ListTemplateDirectDownloadCertificatesCmd.class);
        cmdList.add(ProvisionTemplateDirectDownloadCertificateCmd.class);
        cmdList.add(ListMgmtsCmd.class);
        cmdList.add(RemoveManagementServerCmd.class);
        cmdList.add(GetUploadParamsForIsoCmd.class);
        cmdList.add(GetRouterHealthCheckResultsCmd.class);
        cmdList.add(StartRollingMaintenanceCmd.class);
        cmdList.add(MigrateSecondaryStorageDataCmd.class);
        cmdList.add(MigrateResourcesToAnotherSecondaryStorageCmd.class);
        cmdList.add(UploadResourceIconCmd.class);
        cmdList.add(DeleteResourceIconCmd.class);
        cmdList.add(ListResourceIconCmd.class);
        cmdList.add(PatchSystemVMCmd.class);
        cmdList.add(ListGuestVlansCmd.class);
        cmdList.add(AssignVolumeCmd.class);
        cmdList.add(ListSecondaryStorageSelectorsCmd.class);
        if (jsInterpretationEnabled) {
            cmdList.add(CreateSecondaryStorageSelectorCmd.class);
            cmdList.add(UpdateSecondaryStorageSelectorCmd.class);
        }
        cmdList.add(RemoveSecondaryStorageSelectorCmd.class);
        cmdList.add(ListAffectedVmsForStorageScopeChangeCmd.class);
        cmdList.add(ListGuiThemesCmd.class);
        cmdList.add(UpdateGuiThemeCmd.class);
        cmdList.add(CreateGuiThemeCmd.class);
        cmdList.add(RemoveGuiThemeCmd.class);

        // Out-of-band management APIs for admins
        cmdList.add(EnableOutOfBandManagementForHostCmd.class);
        cmdList.add(DisableOutOfBandManagementForHostCmd.class);
        cmdList.add(EnableOutOfBandManagementForClusterCmd.class);
        cmdList.add(DisableOutOfBandManagementForClusterCmd.class);
        cmdList.add(EnableOutOfBandManagementForZoneCmd.class);
        cmdList.add(DisableOutOfBandManagementForZoneCmd.class);
        cmdList.add(ConfigureOutOfBandManagementCmd.class);
        cmdList.add(IssueOutOfBandManagementPowerActionCmd.class);
        cmdList.add(ChangeOutOfBandManagementPasswordCmd.class);

        cmdList.add(GetUserKeysCmd.class);

        // Console Session APIs
        cmdList.add(CreateConsoleEndpointCmd.class);
        cmdList.add(ListConsoleSessionsCmd.class);

        //user data APIs
        cmdList.add(RegisterUserDataCmd.class);
        cmdList.add(DeleteUserDataCmd.class);
        cmdList.add(ListUserDataCmd.class);
        cmdList.add(LinkUserDataToTemplateCmd.class);
        cmdList.add(RegisterCniConfigurationCmd.class);
        cmdList.add(ListCniConfigurationCmd.class);
        cmdList.add(DeleteCniConfigurationCmd.class);

        //vbmc for vm APIs
        cmdList.add(AllocateVbmcToVMCmd.class);
        cmdList.add(RemoveVbmcToVMCmd.class);
        cmdList.add(ListHostDevicesCmd.class);
        cmdList.add(UpdateHostDevicesCmd.class);
        cmdList.add(ListHostUsbDevicesCmd.class);
        cmdList.add(ListHostLunDevicesCmd.class);
        cmdList.add(UpdateHostUsbDevicesCmd.class);
        cmdList.add(UpdateHostLunDevicesCmd.class);
        cmdList.add(ListHostHbaDevicesCmd.class);
        cmdList.add(UpdateHostHbaDevicesCmd.class);
        cmdList.add(CreateVhbaDeviceCmd.class);
        cmdList.add(ListVhbaDevicesCmd.class);
        cmdList.add(UpdateHostVhbaDevicesCmd.class);
        cmdList.add(ListHostScsiDevicesCmd.class);
        cmdList.add(UpdateHostScsiDevicesCmd.class);
        cmdList.add(DeleteVhbaDeviceCmd.class);
        //object store APIs
        cmdList.add(AddObjectStoragePoolCmd.class);
        cmdList.add(ListObjectStoragePoolsCmd.class);
        cmdList.add(UpdateObjectStoragePoolCmd.class);
        cmdList.add(DeleteObjectStoragePoolCmd.class);
        cmdList.add(CreateBucketCmd.class);
        cmdList.add(UpdateBucketCmd.class);
        cmdList.add(DeleteBucketCmd.class);
        cmdList.add(ListBucketsCmd.class);
        cmdList.add(LicenseCheckCmd.class);
        cmdList.add(ListHostRedfishDataCmd.class);

        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return ManagementServer.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {vmPasswordLength, sshKeyLength, humanReadableSizes, customCsIdentifier,
                JsInterpretationEnabled};
    }

    protected class EventPurgeTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                final GlobalLock lock = GlobalLock.getInternLock("EventPurge");
                if (lock == null) {
                    logger.debug("Couldn't get the global lock");
                    return;
                }
                if (!lock.lock(30)) {
                    logger.debug("Couldn't lock the db");
                    return;
                }
                try {
                    final Calendar purgeCal = Calendar.getInstance();
                    purgeCal.add(Calendar.DAY_OF_YEAR, -_purgeDelay);
                    final Date purgeTime = purgeCal.getTime();
                    logger.debug("Deleting events older than: " + purgeTime.toString());
                    final List<EventVO> oldEvents = _eventDao.listOlderEvents(purgeTime);
                    logger.debug("Found " + oldEvents.size() + " events to be purged");
                    for (final EventVO event : oldEvents) {
                        _eventDao.expunge(event.getId());
                    }
                } catch (final Exception e) {
                    logger.error("Exception ", e);
                } finally {
                    lock.unlock();
                }
            } catch (final Exception e) {
                logger.error("Exception ", e);
            }
        }
    }

    protected class AlertPurgeTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                final GlobalLock lock = GlobalLock.getInternLock("AlertPurge");
                if (lock == null) {
                    logger.debug("Couldn't get the global lock");
                    return;
                }
                if (!lock.lock(30)) {
                    logger.debug("Couldn't lock the db");
                    return;
                }
                try {
                    final Calendar purgeCal = Calendar.getInstance();
                    purgeCal.add(Calendar.DAY_OF_YEAR, -_alertPurgeDelay);
                    final Date purgeTime = purgeCal.getTime();
                    logger.debug("Deleting alerts older than: " + purgeTime.toString());
                    final List<AlertVO> oldAlerts = _alertDao.listOlderAlerts(purgeTime);
                    logger.debug("Found " + oldAlerts.size() + " events to be purged");
                    for (final AlertVO alert : oldAlerts) {
                        _alertDao.expunge(alert.getId());
                    }
                } catch (final Exception e) {
                    logger.error("Exception ", e);
                } finally {
                    lock.unlock();
                }
            } catch (final Exception e) {
                logger.error("Exception ", e);
            }
        }
    }

    private void cleanupDownloadUrlsInZone(final long zoneId) {
        // clean download URLs when destroying ssvm
        // clean only the volumes and templates of the zone to which ssvm belongs to
        for (VolumeDataStoreVO volume :_volumeStoreDao.listVolumeDownloadUrlsByZoneId(zoneId)) {
            volume.setExtractUrl(null);
            _volumeStoreDao.update(volume.getId(), volume);
        }
        for (ImageStoreVO imageStore : _imgStoreDao.listStoresByZoneId(zoneId)) {
            for (TemplateDataStoreVO template : _vmTemplateStoreDao.listTemplateDownloadUrlsByStoreId(imageStore.getId())) {
                template.setExtractUrl(null);
                template.setExtractUrlCreated(null);
                _vmTemplateStoreDao.update(template.getId(), template);
            }
        }
    }

    private SecondaryStorageVmVO startSecondaryStorageVm(final long instanceId) {
        return _secStorageVmMgr.startSecStorageVm(instanceId);
    }

    private SecondaryStorageVmVO stopSecondaryStorageVm(final VMInstanceVO systemVm, final boolean isForced)
            throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        _itMgr.advanceStop(systemVm.getUuid(), isForced);
        return _secStorageVmDao.findById(systemVm.getId());
    }

    public SecondaryStorageVmVO rebootSecondaryStorageVm(final long instanceId) {
        _secStorageVmMgr.rebootSecStorageVm(instanceId);
        return _secStorageVmDao.findById(instanceId);
    }

    private SecondaryStorageVmVO forceRebootSecondaryStorageVm(final VMInstanceVO systemVm)  throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        _itMgr.advanceStop(systemVm.getUuid(), false);
        return _secStorageVmMgr.startSecStorageVm(systemVm.getId());
    }

    protected SecondaryStorageVmVO destroySecondaryStorageVm(final long instanceId) {
        final SecondaryStorageVmVO secStorageVm = _secStorageVmDao.findById(instanceId);
        cleanupDownloadUrlsInZone(secStorageVm.getDataCenterId());
        if (_secStorageVmMgr.destroySecStorageVm(instanceId)) {
            return secStorageVm;
        }
        return null;
    }

    @Override
    public Pair<List<? extends VirtualMachine>, Integer> searchForSystemVm(final ListSystemVMsCmd cmd) {
        final String type = cmd.getSystemVmType();
        final Long zoneId = _accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), cmd.getZoneId());
        final Long id = cmd.getId();
        final String name = cmd.getSystemVmName();
        final String state = cmd.getState();
        final String keyword = cmd.getKeyword();
        final Long podId = cmd.getPodId();
        final Long hostId = cmd.getHostId();
        final Long storageId = cmd.getStorageId();
        final CPU.CPUArch arch = cmd.getArch();

        final Filter searchFilter = new Filter(VMInstanceVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchBuilder<VMInstanceVO> sb = _vmInstanceDao.createSearchBuilder();

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("hostName", sb.entity().getHostName(), SearchCriteria.Op.LIKE);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("podId", sb.entity().getPodIdToDeployIn(), SearchCriteria.Op.EQ);
        sb.and("hostId", sb.entity().getHostId(), SearchCriteria.Op.EQ);
        sb.and("type", sb.entity().getType(), SearchCriteria.Op.EQ);
        sb.and("nulltype", sb.entity().getType(), SearchCriteria.Op.IN);

        if (storageId != null) {
            StoragePoolVO storagePool = _primaryDataStoreDao.findById(storageId);
            if (storagePool.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
                final SearchBuilder<VolumeVO> volumeSearch = _volumeDao.createSearchBuilder();
                volumeSearch.and("poolId", volumeSearch.entity().getPoolId(), SearchCriteria.Op.IN);
                sb.join("volumeSearch", volumeSearch, sb.entity().getId(), volumeSearch.entity().getInstanceId(), JoinBuilder.JoinType.INNER);
            } else {
                final SearchBuilder<VolumeVO> volumeSearch = _volumeDao.createSearchBuilder();
                volumeSearch.and("poolId", volumeSearch.entity().getPoolId(), SearchCriteria.Op.EQ);
                sb.join("volumeSearch", volumeSearch, sb.entity().getId(), volumeSearch.entity().getInstanceId(), JoinBuilder.JoinType.INNER);
            }
        }

        boolean templateJoinNeeded = arch != null;
        if (templateJoinNeeded) {
            SearchBuilder<VMTemplateVO> templateSearch = templateDao.createSearchBuilder();
            templateSearch.and("templateArch", templateSearch.entity().getArch(), SearchCriteria.Op.EQ);
            sb.join("vmTemplate", templateSearch, templateSearch.entity().getId(), sb.entity().getTemplateId(), JoinBuilder.JoinType.INNER);
        }

        final SearchCriteria<VMInstanceVO> sc = sb.create();

        if (keyword != null) {
            final SearchCriteria<VMInstanceVO> ssc = _vmInstanceDao.createSearchCriteria();
            ssc.addOr("hostName", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("state", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("hostName", SearchCriteria.Op.SC, ssc);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("hostName", name);
        }
        if (state != null) {
            sc.setParameters("state", state);
        }
        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }
        if (podId != null) {
            sc.setParameters("podId", podId);
        }
        if (hostId != null) {
            sc.setParameters("hostId", hostId);
        }

        if (type != null) {
            sc.setParameters("type", type);
        } else {
            sc.setParameters("nulltype", VirtualMachine.Type.SecondaryStorageVm, VirtualMachine.Type.ConsoleProxy);
        }

        if (storageId != null) {
            StoragePoolVO storagePool = _primaryDataStoreDao.findById(storageId);
            if (storagePool.getPoolType() == Storage.StoragePoolType.DatastoreCluster) {
                List<StoragePoolVO> childDataStores = _primaryDataStoreDao.listChildStoragePoolsInDatastoreCluster(storageId);
                List<Long> childDatastoreIds = childDataStores.stream().map(mo -> mo.getId()).collect(Collectors.toList());
                sc.setJoinParameters("volumeSearch", "poolId", childDatastoreIds.toArray());
            } else {
                sc.setJoinParameters("volumeSearch", "poolId", storageId);
            }
        }

        if (arch != null) {
            sc.setJoinParameters("vmTemplate", "templateArch", arch);
        }

        final Pair<List<VMInstanceVO>, Integer> result = _vmInstanceDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public VirtualMachine.Type findSystemVMTypeById(final long instanceId) {
        final VMInstanceVO systemVm = _vmInstanceDao.findByIdTypes(instanceId, VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);
        if (systemVm == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find a system vm of specified instanceId");
            ex.addProxyObject(String.valueOf(instanceId), "instanceId");
            throw ex;
        }
        return systemVm.getType();
    }

    @Override
    @ActionEvent(eventType = "", eventDescription = "", async = true)
    public VirtualMachine startSystemVM(final long vmId) {

        final VMInstanceVO systemVm = _vmInstanceDao.findByIdTypes(vmId, VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);
        if (systemVm == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("unable to find a system vm with specified vmId");
            ex.addProxyObject(String.valueOf(vmId), "vmId");
            throw ex;
        }

        if (systemVm.getType() == VirtualMachine.Type.ConsoleProxy) {
            ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_PROXY_START, "starting console proxy Vm", vmId, ApiCommandResourceType.ConsoleProxy.toString());
            return startConsoleProxy(vmId);
        } else if (systemVm.getType() == VirtualMachine.Type.SecondaryStorageVm) {
            ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_SSVM_START, "starting secondary storage Vm", vmId, ApiCommandResourceType.SystemVm.toString());
            return startSecondaryStorageVm(vmId);
        } else {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find a system vm with specified vmId");
            ex.addProxyObject(systemVm.getUuid(), "vmId");
            throw ex;
        }
    }

    @Override
    @ActionEvent(eventType = "", eventDescription = "", async = true)
    public VMInstanceVO stopSystemVM(final StopSystemVmCmd cmd) throws ResourceUnavailableException, ConcurrentOperationException {
        final Long id = cmd.getId();

        // verify parameters
        final VMInstanceVO systemVm = _vmInstanceDao.findByIdTypes(id, VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);
        if (systemVm == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("unable to find a system vm with specified vmId");
            ex.addProxyObject(id.toString(), "vmId");
            throw ex;
        }

        try {
            if (systemVm.getType() == VirtualMachine.Type.ConsoleProxy) {
                ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_PROXY_STOP, "stopping console proxy Vm", systemVm.getId(), ApiCommandResourceType.ConsoleProxy.toString());
                return stopConsoleProxy(systemVm, cmd.isForced());
            } else if (systemVm.getType() == VirtualMachine.Type.SecondaryStorageVm) {
                ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_SSVM_STOP, "stopping secondary storage Vm", systemVm.getId(), ApiCommandResourceType.SystemVm.toString());
                return stopSecondaryStorageVm(systemVm, cmd.isForced());
            }
            return null;
        } catch (final OperationTimedoutException e) {
            throw new CloudRuntimeException("Unable to stop " + systemVm, e);
        }
    }

    @Override
    public VMInstanceVO rebootSystemVM(final RebootSystemVmCmd cmd) {
        final VMInstanceVO systemVm = _vmInstanceDao.findByIdTypes(cmd.getId(), VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);

        if (systemVm == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("unable to find a system vm with specified vmId");
            ex.addProxyObject(cmd.getId().toString(), "vmId");
            throw ex;
        }

        try {
            if (systemVm.getType().equals(VirtualMachine.Type.ConsoleProxy)) {
                ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_PROXY_REBOOT, "rebooting console proxy Vm", systemVm.getId(), ApiCommandResourceType.ConsoleProxy.toString());
                if (cmd.isForced()) {
                    return forceRebootConsoleProxy(systemVm);
                }
                return rebootConsoleProxy(cmd.getId());
            } else {
                ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_SSVM_REBOOT, "rebooting secondary storage Vm", systemVm.getId(), ApiCommandResourceType.SystemVm.toString());
                if (cmd.isForced()) {
                    return forceRebootSecondaryStorageVm(systemVm);
                }
                return rebootSecondaryStorageVm(cmd.getId());
            }
        } catch (final ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to reboot " + systemVm, e);
        } catch (final OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation timed out - Unable to reboot " + systemVm, e);
        }
    }

    @Override
    @ActionEvent(eventType = "", eventDescription = "", async = true)
    public VMInstanceVO destroySystemVM(final DestroySystemVmCmd cmd) {
        final VMInstanceVO systemVm = _vmInstanceDao.findByIdTypes(cmd.getId(), VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);

        if (systemVm == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("unable to find a system vm with specified vmId");
            ex.addProxyObject(cmd.getId().toString(), "vmId");
            throw ex;
        }

        if (systemVm.getType().equals(VirtualMachine.Type.ConsoleProxy)) {
            ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_PROXY_DESTROY, "destroying console proxy Vm", systemVm.getId(), ApiCommandResourceType.ConsoleProxy.toString());
            return destroyConsoleProxy(cmd.getId());
        } else {
            ActionEventUtils.startNestedActionEvent(EventTypes.EVENT_SSVM_DESTROY, "destroying secondary storage Vm", systemVm.getId(), ApiCommandResourceType.SystemVm.toString());
            return destroySecondaryStorageVm(cmd.getId());
        }
    }

    private String signRequest(final String request, final String key) {
        try {
            logger.info("Request: " + request);
            logger.info("Key: " + key);

            if (key != null && request != null) {
                final Mac mac = Mac.getInstance("HmacSHA256");
                final SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "HmacSHA256");
                mac.init(keySpec);
                mac.update(request.getBytes());
                final byte[] encryptedBytes = mac.doFinal();
                return new String(Base64.encodeBase64(encryptedBytes));
            }
        } catch (final Exception ex) {
            logger.error("unable to sign request", ex);
        }
        return null;
    }

    @Override
    public ArrayList<String> getCloudIdentifierResponse(final long userId) {
        final Account caller = getCaller();

        // verify that user exists
        User user = _accountMgr.getUserIncludingRemoved(userId);
        if (user == null || user.getRemoved() != null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find active user of specified id");
            ex.addProxyObject(String.valueOf(userId), "userId");
            throw ex;
        }

        // check permissions
        _accountMgr.checkAccess(caller, null, true, _accountMgr.getAccount(user.getAccountId()));

        String cloudIdentifier = _configDao.getValue("cloud.identifier");
        if (cloudIdentifier == null) {
            cloudIdentifier = "";
        }

        String signature = "";
        try {
            // get the user obj to get their secret key
            user = _accountMgr.getActiveUser(userId);
            final String secretKey = user.getSecretKey();
            final String input = cloudIdentifier;
            signature = signRequest(input, secretKey);
        } catch (final Exception e) {
            logger.warn("Exception whilst creating a signature:" + e);
        }

        final ArrayList<String> cloudParams = new ArrayList<>();
        cloudParams.add(cloudIdentifier);
        cloudParams.add(signature);

        return cloudParams;
    }

    @Override
    public Map<String, Object> listCapabilities(final ListCapabilitiesCmd cmd) {
        final Map<String, Object> capabilities = new HashMap<>();

        final Account caller = getCaller();
        final boolean isCallerAdmin = _accountService.isAdmin(caller.getId());
        boolean securityGroupsEnabled = false;
        boolean elasticLoadBalancerEnabled = false;
        boolean KVMSnapshotEnabled = false;
        String supportELB = "false";
        final List<NetworkVO> networks = networkDao.listSecurityGroupEnabledNetworks();
        if (networks != null && !networks.isEmpty()) {
            securityGroupsEnabled = true;
            final String elbEnabled = _configDao.getValue(Config.ElasticLoadBalancerEnabled.key());
            elasticLoadBalancerEnabled = elbEnabled == null ? false : Boolean.parseBoolean(elbEnabled);
            if (elasticLoadBalancerEnabled) {
                final String networkType = _configDao.getValue(Config.ElasticLoadBalancerNetwork.key());
                if (networkType != null) {
                    supportELB = networkType;
                }
            }
        }

        final long diskOffMinSize = VolumeOrchestrationService.CustomDiskOfferingMinSize.value();
        final long diskOffMaxSize = VolumeOrchestrationService.CustomDiskOfferingMaxSize.value();
        final boolean KVMSnapshotEnabled = SnapshotManager.KVMSnapshotEnabled.value();

        final boolean userPublicTemplateEnabled = TemplateManager.AllowPublicUserTemplates.valueIn(caller.getId());

        // add some parameters UI needs to handle API throttling
        final boolean apiLimitEnabled = Boolean.parseBoolean(_configDao.getValue(Config.ApiLimitEnabled.key()));
        final Integer apiLimitInterval = Integer.valueOf(_configDao.getValue(Config.ApiLimitInterval.key()));
        final Integer apiLimitMax = Integer.valueOf(_configDao.getValue(Config.ApiLimitMax.key()));

        final boolean allowUserViewDestroyedVM = (QueryService.AllowUserViewDestroyedVM.valueIn(caller.getId()) | isCallerAdmin);
        final boolean allowUserExpungeRecoverVM = (UserVmManager.AllowUserExpungeRecoverVm.valueIn(caller.getId()) | isCallerAdmin);
        final boolean allowUserExpungeRecoverVolume = (VolumeApiServiceImpl.AllowUserExpungeRecoverVolume.valueIn(caller.getId()) | isCallerAdmin);
        final boolean allowUserForceStopVM = (UserVmManager.AllowUserForceStopVm.valueIn(caller.getId()) | isCallerAdmin);

        final boolean allowUserViewAllDomainAccounts = (QueryService.AllowUserViewAllDomainAccounts.valueIn(caller.getDomainId()));

        final boolean kubernetesServiceEnabled = Boolean.parseBoolean(_configDao.getValue("cloud.kubernetes.service.enabled"));
        final boolean kubernetesClusterExperimentalFeaturesEnabled = Boolean.parseBoolean(_configDao.getValue("cloud.kubernetes.cluster.experimental.features.enabled"));

        final boolean desktopServiceEnabled = Boolean.parseBoolean(_configDao.getValue("cloud.desktop.service.enabled"));
        final boolean automationServiceEnabled = Boolean.parseBoolean(_configDao.getValue("cloud.automation.service.enabled"));
        final String desktopWorksPortalPort = _configDao.getValue("cloud.desktop.service.works.portal.port");
        final boolean securityFeaturesEnabled = Boolean.parseBoolean(_configDao.getValue("security.features.enabled"));
        final boolean disasterRecoveryEnabled = Boolean.parseBoolean(_configDao.getValue("cloud.dr.service.enabled"));
        final String host = _configDao.getValue("host");
        final boolean balancingServiceEnabled = Boolean.parseBoolean(_configDao.getValue("cloud.balancing.service.enabled"));
        final boolean eventDeleteEnabled = Boolean.parseBoolean(_configDao.getValue("event.delete.enabled"));

        // check if region-wide secondary storage is used
        boolean regionSecondaryEnabled = false;
        final List<ImageStoreVO> imgStores = _imgStoreDao.findRegionImageStores();
        if (imgStores != null && imgStores.size() > 0) {
            regionSecondaryEnabled = true;
        }

        final Integer fsVmMinCpu = Integer.parseInt(_configDao.getValue("sharedfsvm.min.cpu.count"));
        final Integer fsVmMinRam = Integer.parseInt(_configDao.getValue("sharedfsvm.min.ram.size"));
        if (exposeCloudStackVersionInApiListCapabilities.value()) {
            capabilities.put("cloudStackVersion", getVersion());
        }

        capabilities.put("securityGroupsEnabled", securityGroupsEnabled);
        capabilities.put("userPublicTemplateEnabled", userPublicTemplateEnabled);
        capabilities.put("supportELB", supportELB);
        capabilities.put("projectInviteRequired", _projectMgr.projectInviteRequired());
        capabilities.put("allowusercreateprojects", _projectMgr.allowUserToCreateProject());
        capabilities.put("customDiskOffMinSize", diskOffMinSize);
        capabilities.put("customDiskOffMaxSize", diskOffMaxSize);
        capabilities.put("regionSecondaryEnabled", regionSecondaryEnabled);
        capabilities.put("KVMSnapshotEnabled", KVMSnapshotEnabled);
        capabilities.put("allowUserViewDestroyedVM", allowUserViewDestroyedVM);
        capabilities.put("allowUserExpungeRecoverVM", allowUserExpungeRecoverVM);
        capabilities.put("allowUserExpungeRecoverVolume", allowUserExpungeRecoverVolume);
        capabilities.put("allowUserViewAllDomainAccounts", allowUserViewAllDomainAccounts);
        capabilities.put(ApiConstants.ALLOW_USER_FORCE_STOP_VM, allowUserForceStopVM);
        capabilities.put("kubernetesServiceEnabled", kubernetesServiceEnabled);
        capabilities.put("kubernetesClusterExperimentalFeaturesEnabled", kubernetesClusterExperimentalFeaturesEnabled);
        capabilities.put("customHypervisorDisplayName", HypervisorGuru.HypervisorCustomDisplayName.value());
        capabilities.put("desktopServiceEnabled", desktopServiceEnabled);
        capabilities.put("automationServiceEnabled", automationServiceEnabled);
        capabilities.put("desktopWorksPortalPort", desktopWorksPortalPort);
        capabilities.put("securityFeaturesEnabled", securityFeaturesEnabled);
        capabilities.put("disasterRecoveryEnabled", disasterRecoveryEnabled);
        capabilities.put("host", host);
        capabilities.put("balancingServiceEnabled", balancingServiceEnabled);
        capabilities.put("eventDeleteEnabled", eventDeleteEnabled);
        capabilities.put(ApiServiceConfiguration.DefaultUIPageSize.key(), ApiServiceConfiguration.DefaultUIPageSize.value());

        capabilities.put(ApiConstants.INSTANCES_STATS_RETENTION_TIME, StatsCollector.vmStatsMaxRetentionTime.value());
        capabilities.put(ApiConstants.INSTANCES_STATS_USER_ONLY, StatsCollector.vmStatsCollectUserVMOnly.value());
        capabilities.put(ApiConstants.INSTANCES_DISKS_STATS_RETENTION_ENABLED, StatsCollector.vmDiskStatsRetentionEnabled.value());
        capabilities.put(ApiConstants.INSTANCES_DISKS_STATS_RETENTION_TIME, StatsCollector.vmDiskStatsMaxRetentionTime.value());
        capabilities.put(ApiConstants.INSTANCE_LEASE_ENABLED, VMLeaseManager.InstanceLeaseEnabled.value());
        capabilities.put(ApiConstants.DYNAMIC_SCALING_ENABLED, UserVmManager.EnableDynamicallyScaleVm.value());
        if (apiLimitEnabled) {
            capabilities.put("apiLimitInterval", apiLimitInterval);
            capabilities.put("apiLimitMax", apiLimitMax);
        }
        capabilities.put(ApiConstants.SHAREDFSVM_MIN_CPU_COUNT, fsVmMinCpu);
        capabilities.put(ApiConstants.SHAREDFSVM_MIN_RAM_SIZE, fsVmMinRam);
        if (isCallerAdmin) {
            capabilities.put(ApiConstants.EXTENSIONS_PATH, extensionsManager.getExtensionsPath());
        }
        capabilities.put(ApiConstants.ADDITONAL_CONFIG_ENABLED, UserVmManager.EnableAdditionalVmConfig.valueIn(caller.getId()));


        return capabilities;
    }

    @Override
    public GuestOSVO getGuestOs(final Long guestOsId) {
        return _guestOSDao.findById(guestOsId);
    }

    @Override
    public GuestOSHypervisorVO getGuestOsHypervisor(final Long guestOsHypervisorId) {
        return _guestOSHypervisorDao.findById(guestOsHypervisorId);
    }

    @Override
    public InstanceGroupVO updateVmGroup(final UpdateVMGroupCmd cmd) {
        final Account caller = getCaller();
        final Long groupId = cmd.getId();
        final String groupName = cmd.getGroupName();

        // Verify input parameters
        final InstanceGroupVO group = _vmGroupDao.findById(groupId.longValue());
        if (group == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("unable to find a vm group with specified groupId");
            ex.addProxyObject(groupId.toString(), "groupId");
            throw ex;
        }

        _accountMgr.checkAccess(caller, null, true, group);

        // Check if name is already in use by this account (exclude this group)
        final boolean isNameInUse = _vmGroupDao.isNameInUse(group.getAccountId(), groupName);

        if (isNameInUse && !group.getName().equals(groupName)) {
            throw new InvalidParameterValueException("Unable to update vm group, a group with name " + groupName + " already exists for account");
        }

        if (groupName != null) {
            _vmGroupDao.updateVmGroup(groupId, groupName);
        }

        return _vmGroupDao.findById(groupId);
    }

    @Override
    public String getVersion() {
        final Class<?> c = ManagementServer.class;
        final String fullVersion = c.getPackage().getImplementationVersion();
        if (fullVersion != null && fullVersion.length() > 0) {
            return fullVersion;
        }

        return "unknown";
    }

    @Override
    @DB
    public String uploadCertificate(final UploadCustomCertificateCmd cmd) {
        if (cmd.getPrivateKey() != null && cmd.getAlias() != null) {
            throw new InvalidParameterValueException("Can't change the alias for private key certification");
        }

        if (cmd.getPrivateKey() == null) {
            if (cmd.getAlias() == null) {
                throw new InvalidParameterValueException("alias can't be empty, if it's a certification chain");
            }

            if (cmd.getCertIndex() == null) {
                throw new InvalidParameterValueException("index can't be empty, if it's a certification chain");
            }
        }

        final String certificate = cmd.getCertificate();
        final String key = cmd.getPrivateKey();
        String domainSuffix = cmd.getDomainSuffix();

        validateCertificate(certificate, key, domainSuffix);

        if (cmd.getPrivateKey() != null) {
            _ksMgr.saveCertificate(ConsoleProxyManager.CERTIFICATE_NAME, certificate, key, domainSuffix);

            // Reboot ssv m here since private key is present - meaning server cert being passed
            final List<SecondaryStorageVmVO> alreadyRunning = _secStorageVmDao.getSecStorageVmListInStates(null, State.Running, State.Migrating, State.Starting);
            for (final SecondaryStorageVmVO ssVmVm : alreadyRunning) {
                _secStorageVmMgr.rebootSecStorageVm(ssVmVm.getId());
            }
        } else {
            _ksMgr.saveCertificate(cmd.getAlias(), certificate, cmd.getCertIndex(), cmd.getDomainSuffix());
        }

        _consoleProxyMgr.setManagementState(ConsoleProxyManagementState.ResetSuspending);
        return "Certificate has been successfully updated, if its the server certificate we would reboot all "
        + "running console proxy VMs and secondary storage VMs to propagate the new certificate, "
        + "please give a few minutes for console access and storage services service to be up and working again";
    }

    private void validateCertificate(String certificate, String key, String domainSuffix) {
        if (key != null) {
            Pair<Boolean, String> result = _ksMgr.validateCertificate(certificate, key, domainSuffix);
            if (!result.first()) {
                throw new InvalidParameterValueException(String.format("Failed to pass certificate validation check with error: %s", result.second()));
            }
        } else {
            try {
                logger.debug(String.format("Trying to validate the root certificate format"));
                CertificateHelper.buildCertificate(certificate);
            } catch (CertificateException e) {
                String errorMsg = String.format("Failed to pass certificate validation check with error: Certificate validation failed due to exception: %s", e.getMessage());
                logger.error(errorMsg);
                throw new InvalidParameterValueException(errorMsg);
            }
        }
    }

    @Override
    public List<String> getHypervisors(final Long zoneId) {
        final List<String> result = new ArrayList<>();
        final String hypers = _configDao.getValue(Config.HypervisorList.key());
        final String[] hypervisors = hypers.split(",");

        if (zoneId != null) {
            if (zoneId.longValue() == -1L) {
                final List<DataCenterVO> zones = _dcDao.listAll();

                for (final String hypervisor : hypervisors) {
                    int hyperCount = 0;
                    for (final DataCenterVO zone : zones) {
                        final List<ClusterVO> clusters = _clusterDao.listByDcHyType(zone.getId(), hypervisor);
                        if (!clusters.isEmpty()) {
                            hyperCount++;
                        }
                    }
                    if (hyperCount == zones.size()) {
                        result.add(hypervisor);
                    }
                }
            } else {
                final List<ClusterVO> clustersForZone = _clusterDao.listByZoneId(zoneId);
                for (final ClusterVO cluster : clustersForZone) {
                    result.add(cluster.getHypervisorType().getHypervisorDisplayName());
                }
            }

        } else {
            return Arrays.asList(hypervisors);
        }
        return result;
    }

    @Override
    public SSHKeyPair createSSHKeyPair(final CreateSSHKeyPairCmd cmd) {
        final Account caller = getCaller();
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        final Long projectId = cmd.getProjectId();

        final String name = cmd.getName();

        if (StringUtils.isBlank(name)) {
            throw new InvalidParameterValueException("Please specify a valid name for the key pair. The key name can't be empty");
        }

        final Account owner = _accountMgr.finalizeOwner(caller, accountName, domainId, projectId);

        final SSHKeyPairVO s = _sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
        if (s != null) {
            throw new InvalidParameterValueException("A key pair with name '" + cmd.getName() + "' already exists.");
        }

        final SSHKeysHelper keys = new SSHKeysHelper(sshKeyLength.value());
        final String publicKey = keys.getPublicKey();
        final String fingerprint = keys.getPublicKeyFingerPrint();
        final String privateKey = keys.getPrivateKey();

        return createAndSaveSSHKeyPair(name, fingerprint, publicKey, privateKey, owner);
    }

    @Override
    public boolean deleteSSHKeyPair(final DeleteSSHKeyPairCmd cmd) {
        final Account caller = getCaller();
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        final Long projectId = cmd.getProjectId();

        Account owner = null;
        try {
            owner = _accountMgr.finalizeOwner(caller, accountName, domainId, projectId);
        } catch (InvalidParameterValueException ex) {
            if (caller.getType() == Account.Type.ADMIN && accountName != null && domainId != null) {
                owner = _accountDao.findAccountIncludingRemoved(accountName, domainId);
            }
            if (owner == null) {
                throw ex;
            }
        }

        final SSHKeyPairVO s = _sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
        if (s == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException(
                    "A key pair with name '" + cmd.getName() + "' does not exist for account " + owner.getAccountName() + " in specified domain id");
            final DomainVO domain = ApiDBUtils.findDomainById(owner.getDomainId());
            String domainUuid = String.valueOf(owner.getDomainId());
            if (domain != null) {
                domainUuid = domain.getUuid();
            }
            ex.addProxyObject(domainUuid, "domainId");
            throw ex;
        }
        annotationDao.removeByEntityType(AnnotationService.EntityType.SSH_KEYPAIR.name(), s.getUuid());

        return _sshKeyPairDao.deleteByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
    }

    @Override
    public Pair<List<? extends SSHKeyPair>, Integer> listSSHKeyPairs(final ListSSHKeyPairsCmd cmd) {
        final String name = cmd.getName();
        final String fingerPrint = cmd.getFingerprint();
        final String keyword = cmd.getKeyword();
        final Long domainid = cmd.getDomainId();
        final String accountName = cmd.getAccount();

        final Account caller = getCaller();
        final List<Long> permittedAccounts = new ArrayList<>();

        final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        _accountMgr.buildACLSearchParameters(caller, null, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        final Long domainId = domainIdRecursiveListProject.first();
        final Boolean isRecursive = domainIdRecursiveListProject.second();
        final ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        final SearchBuilder<SSHKeyPairVO> sb = _sshKeyPairDao.createSearchBuilder();
        _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        final Filter searchFilter = new Filter(SSHKeyPairVO.class, "id", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        final SearchCriteria<SSHKeyPairVO> sc = sb.create();
        _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }

        if (fingerPrint != null) {
            sc.addAnd("fingerprint", SearchCriteria.Op.EQ, fingerPrint);
        }

        if (keyword != null) {
            final SearchCriteria<SSHKeyPairVO> ssc = _sshKeyPairDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("fingerprint", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        final Pair<List<SSHKeyPairVO>, Integer> result = _sshKeyPairDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_REGISTER_SSH_KEYPAIR, eventDescription = "registering ssh keypair", async = true)
    public SSHKeyPair registerSSHKeyPair(final RegisterSSHKeyPairCmd cmd) {
        final Account owner = getOwner(cmd);
        checkForKeyByName(cmd, owner);
        checkForKeyByPublicKey(cmd, owner);

        final String name = cmd.getName();
        String key = cmd.getPublicKey();

        final String publicKey = getPublicKeyFromKeyKeyMaterial(key);
        final String fingerprint = getFingerprint(publicKey);

        return createAndSaveSSHKeyPair(name, fingerprint, publicKey, null, owner);
    }

    @Override
    public boolean deleteUserData(final DeleteUserDataCmd cmd) {
        final Account caller = getCaller();
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        final Long projectId = cmd.getProjectId();

        Account owner = null;
        try {
            owner = _accountMgr.finalizeOwner(caller, accountName, domainId, projectId);
        } catch (InvalidParameterValueException ex) {
            if (caller.getType() == Account.Type.ADMIN && accountName != null && domainId != null) {
                owner = _accountDao.findAccountIncludingRemoved(accountName, domainId);
            }
            if (owner == null) {
                throw ex;
            }
        }

        final UserDataVO userData = userDataDao.findById(cmd.getId());
        if (userData == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException(
                    "A UserData with id '" + cmd.getId() + "' does not exist for account " + owner.getAccountName() + " in specified domain id");
            final DomainVO domain = ApiDBUtils.findDomainById(owner.getDomainId());
            String domainUuid = String.valueOf(owner.getDomainId());
            if (domain != null) {
                domainUuid = domain.getUuid();
            }
            ex.addProxyObject(domainUuid, "domainId");
            throw ex;
        }

        List<VMTemplateVO> templatesLinkedToUserData = templateDao.findTemplatesLinkedToUserdata(userData.getId());
        if (CollectionUtils.isNotEmpty(templatesLinkedToUserData)) {
            throw new CloudRuntimeException(String.format("Userdata %s cannot be removed as it is linked to active template/templates", userData.getName()));
        }

        List<UserVmVO> userVMsHavingUserdata = _userVmDao.findByUserDataId(userData.getId());
        if (CollectionUtils.isNotEmpty(userVMsHavingUserdata)) {
            throw new CloudRuntimeException(String.format("Userdata %s cannot be removed as it is being used by some VMs", userData.getName()));
        }

        annotationDao.removeByEntityType(AnnotationService.EntityType.USER_DATA.name(), userData.getUuid());

        return userDataDao.remove(userData.getId());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DELETE_CNI_CONFIG, eventDescription = "CNI Configuration deletion")
    public boolean deleteCniConfiguration(DeleteCniConfigurationCmd cmd) {
        return deleteUserData(cmd);
    }

    @Override
    public Pair<List<? extends UserData>, Integer> listUserDatas(final ListUserDataCmd cmd, final boolean forCks) {
        final Long id = cmd.getId();
        final String name = cmd.getName();
        final String keyword = cmd.getKeyword();

        final Account caller = getCaller();
        final List<Long> permittedAccounts = new ArrayList<>();

        final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        _accountMgr.buildACLSearchParameters(caller, null, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        final Long domainId = domainIdRecursiveListProject.first();
        final Boolean isRecursive = domainIdRecursiveListProject.second();
        final ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        final SearchBuilder<UserDataVO> sb = userDataDao.createSearchBuilder();
        _accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        final Filter searchFilter = new Filter(UserDataVO.class, "id", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("forCks", sb.entity().isForCks(), SearchCriteria.Op.EQ);
        final SearchCriteria<UserDataVO> sc = sb.create();
        _accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (keyword != null) {
            sc.setParameters("keyword",  "%" + keyword + "%");
        }

        sc.setParameters("forCks", forCks);

        final Pair<List<UserDataVO>, Integer> result = userDataDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_REGISTER_CNI_CONFIG, eventDescription = "registering CNI configuration", async = true)
    public UserData registerCniConfiguration(RegisterCniConfigurationCmd cmd) {
        final Account owner = getOwner(cmd);
        checkForUserDataByName(cmd, owner);
        final String name = cmd.getName();

        String userdata = cmd.getCniConfig();
        final String params = cmd.getParams();

        userdata = userDataManager.validateUserData(userdata, cmd.getHttpMethod());

        return createAndSaveUserData(name, userdata, params, owner, true);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_REGISTER_USER_DATA, eventDescription = "registering userdata", async = true)
    public UserData registerUserData(final RegisterUserDataCmd cmd) {
        final Account owner = getOwner(cmd);
        checkForUserDataByName(cmd, owner);
        final String name = cmd.getName();

        String userdata = cmd.getUserData();
        checkForUserData(cmd, owner);
        final String params = cmd.getParams();

        userdata = userDataManager.validateUserData(userdata, cmd.getHttpMethod());

        return createAndSaveUserData(name, userdata, params, owner, false);
    }

    /**
     * @param cmd
     * @param owner
     * @throws InvalidParameterValueException
     */
    private void checkForUserData(final RegisterUserDataCmd cmd, final Account owner) throws InvalidParameterValueException {
        final UserDataVO userData = userDataDao.findByUserData(owner.getAccountId(), owner.getDomainId(), cmd.getUserData());
        if (userData != null) {
            throw new InvalidParameterValueException(String.format("Userdata %s with same content already exists for this account.", userData.getName()));
        }
    }

    /**
     * @param cmd
     * @param owner
     * @throws InvalidParameterValueException
     */
    private void checkForUserDataByName(final BaseRegisterUserDataCmd cmd, final Account owner) throws InvalidParameterValueException {
        final UserDataVO userData = userDataDao.findByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
        if (userData != null) {
            throw new InvalidParameterValueException(String.format("A userdata with name %s already exists for this account.", cmd.getName()));
        }
    }

    /**
     * @param cmd
     * @param owner
     * @throws InvalidParameterValueException
     */
    private void checkForKeyByPublicKey(final RegisterSSHKeyPairCmd cmd, final Account owner) throws InvalidParameterValueException {
        final SSHKeyPairVO existingPair = _sshKeyPairDao.findByPublicKey(owner.getAccountId(), owner.getDomainId(), getPublicKeyFromKeyKeyMaterial(cmd.getPublicKey()));
        if (existingPair != null) {
            throw new InvalidParameterValueException("A key pair with key '" + cmd.getPublicKey() + "' already exists for this account.");
        }
    }

    /**
     * @param cmd
     * @param owner
     * @throws InvalidParameterValueException
     */
    protected void checkForKeyByName(final RegisterSSHKeyPairCmd cmd, final Account owner) throws InvalidParameterValueException {
        final SSHKeyPairVO existingPair = _sshKeyPairDao.findByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
        if (existingPair != null) {
            throw new InvalidParameterValueException("A key pair with name '" + cmd.getName() + "' already exists for this account.");
        }
    }

    /**
     * @param publicKey
     * @return
     */
    private String getFingerprint(final String publicKey) {
        final String fingerprint = SSHKeysHelper.getPublicKeyFingerprint(publicKey);
        return fingerprint;
    }

    /**
     * @param key
     * @return
     * @throws InvalidParameterValueException
     */
    protected String getPublicKeyFromKeyKeyMaterial(final String key) throws InvalidParameterValueException {
        final String publicKey = SSHKeysHelper.getPublicKeyFromKeyMaterial(key);

        if (publicKey == null) {
            throw new InvalidParameterValueException("Public key is invalid");
        }
        return publicKey;
    }

    /**
     * @param cmd
     * @return
     */
    protected Account getOwner(final RegisterSSHKeyPairCmd cmd) {
        final Account caller = getCaller();

        final Account owner = _accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
        return owner;
    }

    /**
     * @param cmd
     * @return Account
     */
    protected Account getOwner(final BaseRegisterUserDataCmd cmd) {
        final Account caller = getCaller();
        return  _accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
    }

    /**
     * @return
     */
    protected Account getCaller() {
        final Account caller = CallContext.current().getCallingAccount();
        return caller;
    }

    private SSHKeyPair  createAndSaveSSHKeyPair(final String name, final String fingerprint, final String publicKey, final String privateKey, final Account owner) {
        final SSHKeyPairVO newPair = new SSHKeyPairVO();

        newPair.setAccountId(owner.getAccountId());
        newPair.setDomainId(owner.getDomainId());
        newPair.setName(name);
        newPair.setFingerprint(fingerprint);
        newPair.setPublicKey(publicKey);
        newPair.setPrivateKey(privateKey); // transient; not saved.

        _sshKeyPairDao.persist(newPair);

        return newPair;
    }

    private UserData createAndSaveUserData(final String name, final String userdata, final String params, final Account owner, final boolean isForCks) {
        final UserDataVO userDataVO = new UserDataVO();

        userDataVO.setAccountId(owner.getAccountId());
        userDataVO.setDomainId(owner.getDomainId());
        userDataVO.setName(name);
        userDataVO.setUserData(userdata);
        userDataVO.setParams(params);
        userDataVO.setForCks(isForCks);

        userDataDao.persist(userDataVO);

        return userDataVO;
    }

    @Override
    public String getVMPassword(GetVMPasswordCmd cmd) {
        Account caller = getCaller();
        long vmId = cmd.getId();
        UserVmVO vm = _userVmDao.findById(vmId);

        if (vm == null) {
            throw new InvalidParameterValueException(String.format("No VM found with id [%s].", vmId));
        }

        _accountMgr.checkAccess(caller, null, true, vm);

        _userVmDao.loadDetails(vm);
        String password = vm.getDetail("Encrypted.Password");

        if (StringUtils.isEmpty(password)) {
            throw new InvalidParameterValueException(String.format("No password found for VM [%s]. When the VM's SSH keypair is changed, the current encrypted password is "
            + "removed due to inconsistency in the encryption, as the new SSH keypair is different from which the password was encrypted. To get a new password, it must be reseted.", vm));
        }

        return password;
    }

    private boolean updateHostsInCluster(final UpdateHostPasswordCmd command) {
        // get all the hosts in this cluster
        final List<Long> hostIds = _hostDao.listIdsByClusterId(command.getClusterId());

        String userNameWithoutSpaces = StringUtils.deleteWhitespace(command.getUsername());
        if (StringUtils.isBlank(userNameWithoutSpaces)) {
            throw new InvalidParameterValueException("Username should be non empty string");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                for (final Long hostId : hostIds) {
                    logger.debug("Changing password for {}", () -> _hostDao.findById(hostId));
                    // update password for this host
                    final DetailVO nv = _detailsDao.findDetail(hostId, ApiConstants.USERNAME);
                    if (nv == null) {
                        final DetailVO nvu = new DetailVO(hostId, ApiConstants.USERNAME, userNameWithoutSpaces);
                        _detailsDao.persist(nvu);
                        final DetailVO nvp = new DetailVO(hostId, ApiConstants.PASSWORD, DBEncryptionUtil.encrypt(command.getPassword()));
                        _detailsDao.persist(nvp);
                    } else if (nv.getValue().equals(userNameWithoutSpaces)) {
                        final DetailVO nvp = _detailsDao.findDetail(hostId, ApiConstants.PASSWORD);
                        nvp.setValue(DBEncryptionUtil.encrypt(command.getPassword()));
                        _detailsDao.persist(nvp);
                    } else {
                        // if one host in the cluster has diff username then
                        // rollback to maintain consistency
                        throw new InvalidParameterValueException("The username is not same for all hosts, please modify passwords for individual hosts.");
                    }
                }
            }
        });
        return true;
    }

    /**
     * This method updates the password of all hosts in a given cluster.
     */
    @Override
    @DB
    public boolean updateClusterPassword(final UpdateHostPasswordCmd command) {
        if (command.getClusterId() == null) {
            throw new InvalidParameterValueException("You should provide a cluster id.");
        }

        final ClusterVO cluster = ApiDBUtils.findClusterById(command.getClusterId());
        if (cluster == null || !supportedHypervisors.contains(cluster.getHypervisorType())) {
            throw new InvalidParameterValueException("This operation is not supported for this hypervisor type");
        }
        return updateHostsInCluster(command);
    }

    @Override
    @DB
    public boolean updateHostPassword(final UpdateHostPasswordCmd cmd) {
        if (cmd.getHostId() == null) {
            throw new InvalidParameterValueException("You should provide an host id.");
        }

        final HostVO host = _hostDao.findById(cmd.getHostId());

        if (host.getHypervisorType() == HypervisorType.XenServer) {
            throw new InvalidParameterValueException("Single host update is not supported by XenServer hypervisors. Please try again informing the Cluster ID.");
        }

        if (!supportedHypervisors.contains(host.getHypervisorType())) {
            throw new InvalidParameterValueException("This operation is not supported for this hypervisor type");
        }

        String userNameWithoutSpaces = StringUtils.deleteWhitespace(cmd.getUsername());
        if (StringUtils.isBlank(userNameWithoutSpaces)) {
            throw new InvalidParameterValueException("Username should be non empty string");
        }

        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Changing password for host {}", host);
                }
                // update password for this host
                final DetailVO nv = _detailsDao.findDetail(host.getId(), ApiConstants.USERNAME);
                if (nv == null) {
                    final DetailVO nvu = new DetailVO(host.getId(), ApiConstants.USERNAME, userNameWithoutSpaces);
                    _detailsDao.persist(nvu);
                    final DetailVO nvp = new DetailVO(host.getId(), ApiConstants.PASSWORD, DBEncryptionUtil.encrypt(cmd.getPassword()));
                    _detailsDao.persist(nvp);
                } else if (nv.getValue().equals(userNameWithoutSpaces)) {
                    final DetailVO nvp = _detailsDao.findDetail(host.getId(), ApiConstants.PASSWORD);
                    nvp.setValue(DBEncryptionUtil.encrypt(cmd.getPassword()));
                    _detailsDao.persist(nvp);
                } else {
                    // if one host in the cluster has diff username then
                    // rollback to maintain consistency
                    throw new InvalidParameterValueException("The username is not same for the hosts..");
                }
            }
        });
        return true;
    }

    @Override
    public String[] listEventTypes() {
        final Object eventObj = new EventTypes();
        final Class<EventTypes> c = EventTypes.class;
        final Field[] fields = c.getFields();
        final String[] eventTypes = new String[fields.length];
        try {
            int i = 0;
            for (final Field field : fields) {
                eventTypes[i++] = field.get(eventObj).toString();
            }
            return eventTypes;
        } catch (final IllegalArgumentException e) {
            logger.error("Error while listing Event Types", e);
        } catch (final IllegalAccessException e) {
            logger.error("Error while listing Event Types", e);
        }
        return null;
    }

    @Override
    public Pair<List<? extends HypervisorCapabilities>, Integer> listHypervisorCapabilities(final Long id, final HypervisorType hypervisorType, final String keyword, final Long startIndex,
            final Long pageSizeVal) {
        final Filter searchFilter = new Filter(HypervisorCapabilitiesVO.class, "id", true, startIndex, pageSizeVal);
        final SearchCriteria<HypervisorCapabilitiesVO> sc = _hypervisorCapabilitiesDao.createSearchCriteria();

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (hypervisorType != null) {
            sc.addAnd("hypervisorType", SearchCriteria.Op.EQ, hypervisorType);
        }

        if (keyword != null) {
            final SearchCriteria<HypervisorCapabilitiesVO> ssc = _hypervisorCapabilitiesDao.createSearchCriteria();
            ssc.addOr("hypervisorType", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("hypervisorType", SearchCriteria.Op.SC, ssc);
        }

        final Pair<List<HypervisorCapabilitiesVO>, Integer> result = _hypervisorCapabilitiesDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    protected HypervisorCapabilitiesVO getHypervisorCapabilitiesForUpdate(final Long id, final String hypervisorStr, final String hypervisorVersion) {
        if (id == null && StringUtils.isAllEmpty(hypervisorStr, hypervisorVersion)) {
            throw new InvalidParameterValueException("Either ID or hypervisor and hypervisor version must be specified");
        }
        if (id != null) {
            if (!StringUtils.isAllBlank(hypervisorStr, hypervisorVersion)) {
                throw new InvalidParameterValueException("ID can not be specified together with hypervisor and hypervisor version");
            }
            HypervisorCapabilitiesVO hpvCapabilities = _hypervisorCapabilitiesDao.findById(id, true);
            if (hpvCapabilities == null) {
                final InvalidParameterValueException ex = new InvalidParameterValueException("unable to find the hypervisor capabilities for specified id");
                ex.addProxyObject(id.toString(), "Id");
                throw ex;
            }
            return hpvCapabilities;
        }
        if (StringUtils.isAnyBlank(hypervisorStr, hypervisorVersion)) {
            throw new InvalidParameterValueException("Hypervisor and hypervisor version must be specified together");
        }
        HypervisorType hypervisorType = HypervisorType.getType(hypervisorStr);
        if (hypervisorType == HypervisorType.None) {
            throw new InvalidParameterValueException("Invalid hypervisor specified");
        }
        HypervisorCapabilitiesVO hpvCapabilities = _hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(hypervisorType, hypervisorVersion);
        if (hpvCapabilities == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find the hypervisor capabilities for specified hypervisor and hypervisor version");
            ex.addProxyObject(hypervisorStr, "hypervisor");
            ex.addProxyObject(hypervisorVersion, "hypervisorVersion");
            throw ex;
        }
        return hpvCapabilities;
    }

    @Override
    public HypervisorCapabilities updateHypervisorCapabilities(UpdateHypervisorCapabilitiesCmd cmd) {
        Long id = cmd.getId();
        final String hypervisorStr = cmd.getHypervisor();
        final String hypervisorVersion = cmd.getHypervisorVersion();
        final Boolean securityGroupEnabled = cmd.getSecurityGroupEnabled();
        final Long maxGuestsLimit = cmd.getMaxGuestsLimit();
        final Integer maxDataVolumesLimit = cmd.getMaxDataVolumesLimit();
        final Boolean storageMotionSupported = cmd.getStorageMotionSupported();
        final Integer maxHostsPerClusterLimit = cmd.getMaxHostsPerClusterLimit();
        final Boolean vmSnapshotEnabled = cmd.getVmSnapshotEnabled();
        HypervisorCapabilitiesVO hpvCapabilities = getHypervisorCapabilitiesForUpdate(id, hypervisorStr, hypervisorVersion);

        final boolean updateNeeded = securityGroupEnabled != null || maxGuestsLimit != null ||
                maxDataVolumesLimit != null || storageMotionSupported != null || maxHostsPerClusterLimit != null ||
                vmSnapshotEnabled != null;
        if (!updateNeeded) {
            return hpvCapabilities;
        }
        if (StringUtils.isNotBlank(hypervisorVersion) && !hpvCapabilities.getHypervisorVersion().equals(hypervisorVersion)) {
            logger.debug(String.format("Hypervisor capabilities for hypervisor: %s and version: %s does not exist, creating a copy from the parent version: %s for update.", hypervisorStr, hypervisorVersion, hpvCapabilities.getHypervisorVersion()));
            HypervisorCapabilitiesVO copy = new HypervisorCapabilitiesVO(hpvCapabilities);
            copy.setHypervisorVersion(hypervisorVersion);
            hpvCapabilities = _hypervisorCapabilitiesDao.persist(copy);
        }

        id = hpvCapabilities.getId();
        hpvCapabilities = _hypervisorCapabilitiesDao.createForUpdate(id);

        if (securityGroupEnabled != null) {
            hpvCapabilities.setSecurityGroupEnabled(securityGroupEnabled);
        }

        if (maxGuestsLimit != null) {
            hpvCapabilities.setMaxGuestsLimit(maxGuestsLimit);
        }

        if (maxDataVolumesLimit != null) {
            hpvCapabilities.setMaxDataVolumesLimit(maxDataVolumesLimit);
        }

        if (storageMotionSupported != null) {
            hpvCapabilities.setStorageMotionSupported(storageMotionSupported);
        }

        if (maxHostsPerClusterLimit != null) {
            hpvCapabilities.setMaxHostsPerCluster(maxHostsPerClusterLimit);
        }

        if (vmSnapshotEnabled != null) {
            hpvCapabilities.setVmSnapshotEnabled(vmSnapshotEnabled);
        }

        if (_hypervisorCapabilitiesDao.update(id, hpvCapabilities)) {
            hpvCapabilities = _hypervisorCapabilitiesDao.findById(id);
            CallContext.current().setEventDetails("Hypervisor Capabilities id=" + hpvCapabilities.getId());
            return hpvCapabilities;
        } else {
            return null;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_UPGRADE, eventDescription = "Upgrading system VM", async = true)
    public VirtualMachine upgradeSystemVM(final ScaleSystemVMCmd cmd) throws ResourceUnavailableException, ManagementServerException, VirtualMachineMigrationException, ConcurrentOperationException {

        final VMInstanceVO vmInstance = _vmInstanceDao.findById(cmd.getId());
        if (vmInstance.getHypervisorType() == HypervisorType.XenServer && vmInstance.getState().equals(State.Running)) {
            throw new InvalidParameterValueException("Dynamic Scaling operation is not permitted for this hypervisor on system vm");
        }
        final boolean result = _userVmMgr.upgradeVirtualMachine(cmd.getId(), cmd.getServiceOfferingId(), cmd.getDetails());
        if (result) {
            final VirtualMachine vm = _vmInstanceDao.findById(cmd.getId());
            return vm;
        } else {
            throw new CloudRuntimeException("Failed to upgrade System VM");
        }
    }

    @Override
    public VirtualMachine upgradeSystemVM(final UpgradeSystemVMCmd cmd) {
        final Long systemVmId = cmd.getId();
        final Long serviceOfferingId = cmd.getServiceOfferingId();
        return upgradeStoppedSystemVm(systemVmId, serviceOfferingId, cmd.getDetails());

    }

    private VirtualMachine upgradeStoppedSystemVm(final Long systemVmId, final Long serviceOfferingId, final Map<String, String> customparameters) {
        final Account caller = getCaller();

        final VMInstanceVO systemVm = _vmInstanceDao.findByIdTypes(systemVmId, VirtualMachine.Type.ConsoleProxy, VirtualMachine.Type.SecondaryStorageVm);
        if (systemVm == null) {
            throw new InvalidParameterValueException("Unable to find SystemVm with id " + systemVmId);
        }

        _accountMgr.checkAccess(caller, null, true, systemVm);

        // Check that the specified service offering ID is valid
        ServiceOfferingVO newServiceOffering = _offeringDao.findById(serviceOfferingId);
        final ServiceOfferingVO currentServiceOffering = _offeringDao.findById(systemVmId, systemVm.getServiceOfferingId());
        if (newServiceOffering.isDynamic()) {
            newServiceOffering.setDynamicFlag(true);
            _userVmMgr.validateCustomParameters(newServiceOffering, customparameters);
            newServiceOffering = _offeringDao.getComputeOffering(newServiceOffering, customparameters);
        }
        _itMgr.checkIfCanUpgrade(systemVm, newServiceOffering);

        final boolean result = _itMgr.upgradeVmDb(systemVmId, newServiceOffering, currentServiceOffering);

        if (result) {
            return _vmInstanceDao.findById(systemVmId);
        } else {
            throw new CloudRuntimeException("Unable to upgrade system vm " + systemVm);
        }

    }

    private void enableAdminUser(final String password) {
        String encodedPassword = null;

        final UserVO adminUser = _userDao.getUser(2);
        if (adminUser == null) {
            final String msg = "CANNOT find admin user";
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        if (adminUser.getState() == Account.State.DISABLED) {
            // This means its a new account, set the password using the
            // authenticator

            for (final UserAuthenticator authenticator : _userPasswordEncoders) {
                encodedPassword = authenticator.encode(password);
                if (encodedPassword != null) {
                    break;
                }
            }

            adminUser.setPassword(encodedPassword);
            adminUser.setState(Account.State.ENABLED);
            _userDao.persist(adminUser);
            logger.info("Admin user enabled");
        }

    }

    @Override
    public List<String> listDeploymentPlanners() {
        final List<String> plannersAvailable = new ArrayList<>();
        for (final DeploymentPlanner planner : _planners) {
            plannersAvailable.add(planner.getName());
        }

        return plannersAvailable;
    }

    @Override
    public void cleanupVMReservations() {
        if (logger.isDebugEnabled()) {
            logger.debug("Processing cleanupVMReservations");
        }

        _dpMgr.cleanupVMReservations();
    }

    @Override
    public Pair<Boolean, String> patchSystemVM(PatchSystemVMCmd cmd) {
        Long systemVmId = cmd.getId();
        boolean forced = cmd.isForced();

        if (systemVmId == null) {
            throw new InvalidParameterValueException("Please provide a valid ID of a system VM to be patched");
        }

        final VMInstanceVO systemVm = _vmInstanceDao.findByIdTypes(systemVmId, systemVmTypes);
        if (systemVm == null) {
            throw new InvalidParameterValueException(String.format("Unable to find SystemVm with id %s. patchSystemVm API can be used to patch CPVM / SSVM only.", systemVmId));
        }

        return updateSystemVM(systemVm, forced);
    }


    private String getControlIp(final long systemVmId) {
        String controlIpAddress = null;
        final List<NicVO> nics = nicDao.listByVmId(systemVmId);
        for (final NicVO n : nics) {
            final NetworkVO nc = networkDao.findById(n.getNetworkId());
            if (nc != null && nc.getTrafficType() == Networks.TrafficType.Control) {
                controlIpAddress = n.getIPv4Address();
                // router will have only one control IP
                break;
            }
        }

        if (controlIpAddress == null) {
            logger.warn(String.format("Unable to find systemVm's control ip in its attached NICs!. systemVmId: %s", systemVmId));
            VMInstanceVO systemVM = _vmInstanceDao.findById(systemVmId);
            return systemVM.getPrivateIpAddress();
        }

        return controlIpAddress;
    }

    @Override
    public Pair<Boolean, String> updateSystemVM(VMInstanceVO systemVM, boolean forced) {
        String msg = String.format("Unable to patch SystemVM: %s as it is not in Running state. Please destroy and recreate the SystemVM.", systemVM);
        if (systemVM.getState() != State.Running) {
            logger.error(msg);
            return new Pair<>(false, msg);
        }
        return patchSystemVm(systemVM, forced);
    }

    private boolean updateRouterDetails(Long routerId, String scriptVersion, String templateVersion) {
        DomainRouterVO router = routerDao.findById(routerId);
        if (router == null) {
            throw new CloudRuntimeException(String.format("Failed to find router with id: %s", routerId));
        }

        router.setTemplateVersion(templateVersion);
        router.setScriptsVersion(scriptVersion);
        String codeVersion = getVersion();
        if (StringUtils.isNotEmpty(codeVersion)) {
            codeVersion = CloudStackVersion.parse(codeVersion).toString();
        }
        router.setSoftwareVersion(codeVersion);
        return routerDao.update(routerId, router);
    }

    private Pair<Boolean, String> patchSystemVm(VMInstanceVO systemVM, boolean forced) {
        PatchSystemVmAnswer answer;
        final PatchSystemVmCommand command = new PatchSystemVmCommand();
        command.setAccessDetail(NetworkElementCommand.ROUTER_IP, getControlIp(systemVM.getId()));
        command.setAccessDetail(NetworkElementCommand.ROUTER_NAME, systemVM.getInstanceName());
        command.setForced(forced);
        try {
            Commands cmds = new Commands(Command.OnError.Stop);
            cmds.addCommand(command);
            Answer[] answers = _agentMgr.send(systemVM.getHostId(), cmds, patchCommandTimeout);
            answer = (PatchSystemVmAnswer) answers[0];
            if (!answer.getResult()) {
                String errMsg = String.format("Failed to patch systemVM %s due to %s", systemVM.getInstanceName(), answer.getDetails());
                logger.error(errMsg);
                return new Pair<>(false, errMsg);
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            String errMsg = "SystemVM live patch failed";
            logger.error(errMsg, e);
            return new Pair<>(false,  String.format("%s due to: %s", errMsg, e.getMessage()));
        }
        logger.info(String.format("Successfully patched system VM %s", systemVM.getInstanceName()));
        List<VirtualMachine.Type> routerTypes = new ArrayList<>();
        routerTypes.add(VirtualMachine.Type.DomainRouter);
        routerTypes.add(VirtualMachine.Type.InternalLoadBalancerVm);
        if (routerTypes.contains(systemVM.getType())) {
            boolean updated = updateRouterDetails(systemVM.getId(), answer.getScriptsVersion(), answer.getTemplateVersion());
            if (!updated) {
                logger.warn("Failed to update router's script and template version details");
            }
        }
        return new Pair<>(true, answer.getDetails());
    }

    public List<StoragePoolAllocator> getStoragePoolAllocators() {
        return _storagePoolAllocators;
    }

    @Inject
    public void setStoragePoolAllocators(final List<StoragePoolAllocator> storagePoolAllocators) {
        _storagePoolAllocators = storagePoolAllocators;
    }

    public LockControllerListener getLockControllerListener() {
        return _lockControllerListener;
    }

    public void setLockControllerListener(final LockControllerListener lockControllerListener) {
        _lockControllerListener = lockControllerListener;
    }

    @Override
    public LicenseCheckerResponse checkLicense(LicenseCheckCmd cmd) {
        Long hostId = cmd.getHostId();
        if (hostId == null) {
            throw new InvalidParameterValueException("Host ID is required");
        }

        HostVO host = _hostDao.findById(hostId);
        if (host == null) {
            throw new InvalidParameterValueException("Host not found: " + hostId);
        }

        return checkLicense(host);
    }

    private LicenseCheckerResponse checkLicense(HostVO host) {
        LicenseCheckerResponse response = new LicenseCheckerResponse();
        response.setHostId(host.getId());
        response.setObjectName("licensecheck");

        try {
            String ipAddress = host.getPrivateIpAddress();
            String licenseApiUrl = "https://" + ipAddress + ":8080/api/v1/license/isLicenseExpired";

            HttpsURLConnection connection = null;
            try {
                // SSL 설정
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());

                URL url = new URL(licenseApiUrl);
                connection = (HttpsURLConnection) url.openConnection();
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                connection.setHostnameVerifier((hostname, session) -> hostname.equals(ipAddress));
                connection.setDoOutput(true);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(180000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/json");

                int responseCode = connection.getResponseCode();

                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder responseData = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        responseData.append(line);
                    }
                    in.close();

                    // JSON 응답 파싱
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode jsonNode = mapper.readTree(responseData.toString());

                    // 라이선스 정보 설정
                    if (jsonNode.has("error")) {
                        String errorMessage = jsonNode.get("error").asText();
                        response.setHasLicense(false);
                        response.setSuccess(false);
                        response.setExpiryDate(null);

                        // 에이전트 중지 및 알림 전송
                        // controlHostAgent(host, "stop");
                        // handleExpiredLicense(host);
                        // _alertMgr.sendAlert(
                        //     AlertManager.AlertType.ALERT_TYPE_HOST,
                        //     host.getDataCenterId(),
                        //     host.getId(),
                        //     "License error for host " + host.getName(),
                        //     "License check failed: " + errorMessage
                        // );
                    } else {
                        boolean isExpired = jsonNode.get("expiry_date").asBoolean();
                        boolean isIssued = jsonNode.get("issued_date").asBoolean();
                        String expiryDateStr = jsonNode.get("expired").asText();
                        String issuedDateStr = jsonNode.get("issued").asText();

                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                        Date issuedDate = null;
                        Date expiryDate = null;

                        if (issuedDateStr != null && !issuedDateStr.isEmpty()) {
                            issuedDate = sdf.parse(issuedDateStr);
                            response.setIssuedDate(issuedDate);
                        }

                        if (expiryDateStr != null && !expiryDateStr.isEmpty()) {
                            expiryDate = sdf.parse(expiryDateStr);
                            response.setExpiryDate(expiryDate);
                        }

                        // 라이선스 유효성 검사: 발급되지 않았거나 만료되었으면 false
                        boolean isValid = !isExpired && !isIssued;
                        response.setSuccess(isValid);
                        response.setHasLicense(true);

                        // 라이선스 상태에 따른 에이전트 제어
                        if (isValid) {
                            controlHostAgent(host, "start");
                        } else {
                            controlHostAgent(host, "stop");
                            handleExpiredLicense(host);
                            _alertMgr.sendAlert(
                                AlertManager.AlertType.ALERT_TYPE_HOST,
                                host.getDataCenterId(),
                                host.getId(),
                                "License expired or not yet valid for host " + host.getName(),
                                "The license has expired or is not yet valid. Agent has been stopped for host " + host.getName()
                            );
                        }
                    }
                } else {
                    response.setHasLicense(false);
                    response.setSuccess(false);
                    response.setExpiryDate(null);
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        return response;
        } catch (Exception e) {
            logger.error("Error checking license for host: " + host.getId(), e);
            throw new CloudRuntimeException("License Check Failed: " + e.getMessage());
        }
    }
    private boolean handleExpiredLicense(HostVO host) {
        HttpsURLConnection connection = null;
        boolean licenseHostValue = false;
        String ipAddress = host.getPrivateIpAddress();

        try {
            // HA 비활성화 처리
            HAConfigVO haConfig = (HAConfigVO) haConfigDao.findHAResource(host.getId(), HAResource.ResourceType.Host);
            if (haConfig != null) {
                try {
                    haConfig.setEnabled(false);
                    haConfigDao.update(haConfig.getId(), haConfig);
                    boolean result = haConfigManager.disableHA(host.getId(), HAResource.ResourceType.Host);
                    if (!result) {
                        logger.warn("Failed to disable HA for host " + host.getId() + " (IP: " + ipAddress + ")");
                    } else {
                        logger.info("Successfully disabled HA for host " + host.getId() + " (IP: " + ipAddress + ")");
                    }
                } catch (Exception e) {
                    logger.error("Failed to disable HA for host " + host.getId() + " (IP: " + ipAddress + ")", e);
                }
            }

            // 라이센스 체크 로직
            String glueEndpoint = "https://" + ipAddress + ":8080/api/v1/license/controlHostAgent/stop";

            // SSL 인증서 에러 우회 처리
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());

            URL url = new URL(glueEndpoint);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setSSLSocketFactory(sslContext.getSocketFactory());
            connection.setHostnameVerifier((hostname, session) -> hostname.equals(ipAddress));
            connection.setDoOutput(true);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            // 연결 시도 전에 소켓 설정
            System.setProperty("sun.net.client.defaultConnectTimeout", "5000");
            System.setProperty("sun.net.client.defaultReadTimeout", "15000");

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    StringBuilder response = new StringBuilder();
                    String inputLine;

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }

                    licenseHostValue = Boolean.parseBoolean(response.toString());

                    // 라이센스 만료 알림 전송
                    _alertMgr.sendAlert(
                        AlertManager.AlertType.ALERT_TYPE_HOST,
                        host.getDataCenterId(),
                        host.getId(),
                        "The host's license has expired or the file is missing. Please renew the license.",
                        "The license for host " + host.getName() + " has expired. Please renew the license."
                    );

                    logger.warn("License has expired. Host ID: " + host.getId() + " (IP: " + ipAddress + ")");
                }
            } else {
                logger.error("Failed to handle expired license for host: " + host.getId() + " (IP: " + ipAddress + "). Response code: " + responseCode);
            }
        } catch (SocketTimeoutException e) {
            logger.error("Connection timed out while handling expired license for host: " + host.getId() + " (IP: " + ipAddress + ")", e);
        } catch (Exception e) {
            logger.error("Error handling expired license for host: " + host.getId() + " (IP: " + ipAddress + ")", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return licenseHostValue;
    }

    @DB
    @ActionEvent(eventType = EventTypes.EVENT_MANAGEMENT_SERVER_REMOVE, eventDescription = "removing Management Server")
    public boolean removeManagementServer(RemoveManagementServerCmd cmd) {
        final Long id = cmd.getId();
        ManagementServerJoinVO managementServer = managementServerJoinDao.findById(id);

        if (managementServer == null) {
            throw new InvalidParameterValueException(String.format("Unable to find a Management Server with ID equal to [%s].", managementServer.getUuid()));
        }

        if (!ManagementServerHost.State.Down.equals(managementServer.getState())) {
            throw new InvalidParameterValueException(String.format("Unable to remove Management Server with ID [%s]. It can only be removed when it is in the [%s] state, however currently it is in the [%s] state.", managementServer.getUuid(), ManagementServerHost.State.Down.name(), managementServer.getState().name()));
        }

        managementServer.setRemoved(new Date());
        return managementServerJoinDao.update(id, managementServer);

    }

    @Override
    public Answer getExternalVmConsole(VirtualMachine vm, Host host) {
        return extensionsManager.getInstanceConsole(vm, host);
    }

    @Override
    public void checkJsInterpretationAllowedIfNeededForParameterValue(String paramName, boolean paramValue) {
        if (!paramValue || jsInterpretationEnabled) {
            return;
        }
        throw new InvalidParameterValueException(String.format(
                "The parameter %s cannot be set to true as JS interpretation is disabled",
                paramName));
    }
    
    /**
     * VM extraconfig에 디바이스 설정을 추가합니다.
     * PCI 디바이스와 동일한 방식으로 extraconfig-N 형태로 저장합니다.
     * 동기화를 보장하여 여러 디바이스가 동시에 할당될 때 중복 키 문제를 방지합니다.
     */
    private void addDeviceToVmExtraConfig(Long vmId, String deviceName, String xmlConfig) {
        // VM별 동기화를 위한 락 객체 사용
        synchronized (getVmExtraConfigLock(vmId)) {
            try {
                // 현재 시점의 최신 extraconfig 목록 가져오기
                List<UserVmDetailVO> existingConfigs = _vmDetailsDao.listDetails(vmId);
                int nextConfigNum = 1;
                Set<Integer> usedNums = new HashSet<>();

                // 기존 extraconfig 번호들 수집
                for (UserVmDetailVO detail : existingConfigs) {
                    if (detail.getName().startsWith("extraconfig-")) {
                        try {
                            int num = Integer.parseInt(detail.getName().split("-")[1]);
                            usedNums.add(num);
                        } catch (NumberFormatException e) {
                            logger.warn("Invalid extraconfig number format: {}", detail.getName());
                        }
                    }
                }

                // 사용 가능한 다음 번호 찾기
                while (usedNums.contains(nextConfigNum)) {
                    nextConfigNum++;
                }

                String extraConfigKey = "extraconfig-" + nextConfigNum;

                // 중복 체크를 위한 재시도 로직
                int maxRetries = 5;
                int retryCount = 0;
                boolean success = false;

                while (!success && retryCount < maxRetries) {
                    try {
                        // 다시 한 번 최신 상태 확인 (동시 할당 방지)
                        List<UserVmDetailVO> latestConfigs = _vmDetailsDao.listDetails(vmId);
                        boolean keyExists = false;

                        for (UserVmDetailVO detail : latestConfigs) {
                            if (extraConfigKey.equals(detail.getName())) {
                                keyExists = true;
                                break;
                            }
                        }

                        if (keyExists) {
                            // 키가 이미 존재하면 다음 번호로 이동
                            nextConfigNum++;
                            extraConfigKey = "extraconfig-" + nextConfigNum;
                            retryCount++;
                            logger.debug("Extraconfig key {} already exists, trying {}",
                                       "extraconfig-" + (nextConfigNum - 1), extraConfigKey);
                            continue;
                        }

                        // 키 추가 시도
                        _vmDetailsDao.addDetail(vmId, extraConfigKey, xmlConfig, true);
                        success = true;

                        logger.info("Added device {} configuration to VM {} with extraconfig key: {} (attempt {})",
                                   deviceName, vmId, extraConfigKey, retryCount + 1);

                    } catch (Exception e) {
                        retryCount++;
                        if (retryCount >= maxRetries) {
                            logger.error("Failed to add device {} to VM {} extraconfig after {} retries: {}",
                                       deviceName, vmId, maxRetries, e.getMessage());
                            throw e;
                        }
                        logger.warn("Failed to add device {} to VM {} extraconfig (attempt {}): {}, retrying...",
                                  deviceName, vmId, retryCount, e.getMessage());

                        // 잠시 대기 후 재시도
                        try {
                            Thread.sleep(100 * retryCount); // 백오프 전략
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new CloudRuntimeException("Interrupted while waiting to retry extraconfig addition", ie);
                        }

                        nextConfigNum++;
                        extraConfigKey = "extraconfig-" + nextConfigNum;
                    }
                }

                if (!success) {
                    throw new CloudRuntimeException("Failed to add device " + deviceName +
                                                  " to VM " + vmId + " extraconfig after maximum retries");
                }

            } catch (Exception e) {
                logger.error("Failed to add device {} to VM {} extraconfig: {}", deviceName, vmId, e.getMessage(), e);
                throw new CloudRuntimeException("Failed to add device configuration to VM extraconfig", e);
            }
        }
    }

    /**
     * VM별 extraconfig 동기화를 위한 락 객체를 반환합니다.
     */
    private static final Map<Long, Object> vmExtraConfigLocks = new ConcurrentHashMap<>();

    private Object getVmExtraConfigLock(Long vmId) {
        return vmExtraConfigLocks.computeIfAbsent(vmId, k -> new Object());
    }

    /**
     * SCSI 디바이스 이름으로 같은 물리적 디바이스의 LUN 할당을 찾아서 VM ID를 반환합니다.
     */
    private String findVmIdFromLunAllocation(Long hostId, String scsiDeviceName, String currentVmId) {
        try {
            logger.debug("Looking for LUN allocation for SCSI device: {}", scsiDeviceName);

            // SCSI 디바이스에서 물리적 디바이스 경로 추출
            String physicalDevicePath = extractPhysicalDeviceFromScsi(scsiDeviceName);
            if (physicalDevicePath == null) {
                logger.debug("Could not extract physical device path from SCSI device: {}", scsiDeviceName);
                return null;
            }

            logger.debug("Extracted physical device path: {}", physicalDevicePath);

            // 호스트의 모든 LUN 디바이스 할당 확인 (findByName으로 모든 할당을 찾고 호스트 ID로 필터링)
            List<DetailVO> allLunAllocations = _hostDetailsDao.findByName(physicalDevicePath);
            for (DetailVO detail : allLunAllocations) {
                if (detail.getHostId() == hostId) {
                    String lunDeviceName = detail.getName();
                    logger.debug("Checking LUN device: {}", lunDeviceName);

                    // 같은 물리적 디바이스인지 확인
                    if (isSamePhysicalDevice(lunDeviceName, physicalDevicePath)) {
                        logger.info("Found matching LUN device: {} for SCSI device: {}", lunDeviceName, scsiDeviceName);

                        if (currentVmId != null) {
                            // 특정 VM ID가 요청되었으면 해당 VM의 할당만 확인
                            if (detail.getValue().equals(currentVmId)) {
                                return currentVmId;
                            }
                        } else {
                            // VM ID가 지정되지 않았으면 첫 번째 할당 반환
                            return detail.getValue();
                        }
                    }
                }
            }

            logger.debug("No matching LUN allocation found for SCSI device: {}", scsiDeviceName);
            return null;

        } catch (Exception e) {
            logger.error("Error finding VM ID from LUN allocation for SCSI device {}: {}", scsiDeviceName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * SCSI 디바이스 이름에서 물리적 디바이스 경로를 추출합니다.
     */
    private String extractPhysicalDeviceFromScsi(String scsiDeviceName) {
        try {
            // SCSI 디바이스 이름이 /dev/로 시작하면 직접 반환 (LUN과 같은 물리적 디바이스)
            if (scsiDeviceName.startsWith("/dev/")) {
                logger.debug("SCSI device is already a physical device path: {}", scsiDeviceName);
                return scsiDeviceName;
            }

            // SCSI 디바이스 이름 형태: "scsi_host0:0:0:0" 또는 "scsi_host0:0:1:0"
            // 이를 /dev/sdX 형태로 변환해야 함
            // 실제 구현에서는 더 정교한 매핑이 필요할 수 있음

            if (scsiDeviceName.startsWith("scsi_host")) {
                // SCSI 주소에서 물리적 디바이스 경로 추출
                // 이는 하이퍼바이저별로 다를 수 있음
                logger.debug("Extracting physical device from SCSI address: {}", scsiDeviceName);

                // 간단한 예시: scsi_host0:0:0:0 -> /dev/sda
                // 실제로는 더 복잡한 매핑 로직이 필요
                String[] parts = scsiDeviceName.split(":");
                if (parts.length >= 4) {
                    try {
                        int target = Integer.parseInt(parts[2]);
                        int lun = Integer.parseInt(parts[3]);

                        // target과 lun을 기반으로 물리적 디바이스 경로 생성
                        // 이는 하드코딩된 예시이며, 실제로는 호스트의 디바이스 정보를 확인해야 함
                        char deviceLetter = (char) ('a' + target);
                        return "/dev/" + deviceLetter;
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid SCSI address format: {}", scsiDeviceName);
                    }
                }
            }

            return null;
        } catch (Exception e) {
            logger.error("Error extracting physical device from SCSI device {}: {}", scsiDeviceName, e.getMessage());
            return null;
        }
    }

    /**
     * 두 디바이스가 같은 물리적 디바이스를 참조하는지 확인합니다.
     */
    private boolean isSamePhysicalDevice(String lunDeviceName, String physicalDevicePath) {
        try {
            if (lunDeviceName == null || physicalDevicePath == null) {
                return false;
            }

            // LUN 디바이스 이름에서 기본 경로 추출
            String lunBasePath = extractBasePathFromDeviceName(lunDeviceName);
            if (lunBasePath == null) {
                lunBasePath = lunDeviceName;
            }

            logger.debug("Comparing LUN device: {} with physical path: {}", lunBasePath, physicalDevicePath);

            // 직접 경로 비교
            if (lunBasePath.equals(physicalDevicePath)) {
                return true;
            }

            // by-id 경로도 확인
            String lunByIdPath = "/dev/disk/by-id/" + extractByIdFromDeviceName(lunDeviceName);
            if (lunByIdPath.equals(physicalDevicePath)) {
                return true;
            }

            return false;
        } catch (Exception e) {
            logger.error("Error comparing devices {} and {}: {}", lunDeviceName, physicalDevicePath, e.getMessage());
            return false;
        }
    }

    /**
     * VM extraconfig에서 디바이스 설정을 제거합니다.
     */
    private void removeDeviceFromVmExtraConfig(Long vmId, String deviceName, String xmlConfig) {
        try {
            List<UserVmDetailVO> existingConfigs = _vmDetailsDao.listDetails(vmId);
            logger.info("Removing device {} from VM {} extraconfig, total configs: {}",
                       deviceName, vmId, existingConfigs.size());

            for (UserVmDetailVO detail : existingConfigs) {
                if (detail.getName().startsWith("extraconfig-") && detail.getValue() != null) {
                    String value = detail.getValue();
                    boolean shouldRemove = false;
                    String matchReason = "";

                    // 디바이스 타입별 매칭 로직
                    if (isPciDevice(deviceName)) {
                        // PCI 디바이스: 디바이스 이름으로 직접 매칭
                        if (value.contains(deviceName)) {
                            shouldRemove = true;
                            matchReason = "PCI device name match";
                        }
                    } else if (isUsbDevice(deviceName)) {
                        // USB 디바이스: bus와 device 번호로 매칭
                        if (matchUsbDevice(value, deviceName)) {
                            shouldRemove = true;
                            matchReason = "USB device bus/device match";
                        }
                    } else if (isHbaDevice(deviceName)) {
                        // HBA 디바이스: adapter name으로 매칭
                        if (matchHbaDevice(value, deviceName)) {
                            shouldRemove = true;
                            matchReason = "HBA device adapter name match";
                        }
                    } else if (isVhbaDevice(deviceName)) {
                        // vHBA 디바이스: adapter name으로 매칭
                        if (matchVhbaDevice(value, deviceName)) {
                            shouldRemove = true;
                            matchReason = "vHBA device adapter name match";
                        }
                    } else if (isLunDevice(deviceName)) {
                        // LUN 디바이스: 복잡한 경로 매칭 로직 사용
                        if (matchLunDevice(value, deviceName)) {
                            shouldRemove = true;
                            matchReason = "LUN device path match";
                        }
                    } else if (isScsiDevice(deviceName)) {
                        // SCSI 디바이스: adapter name으로 매칭
                        if (matchScsiDevice(value, deviceName)) {
                            shouldRemove = true;
                            matchReason = "SCSI device adapter name match";
                        }
                    } else {
                        // 기타 디바이스: XML 내용으로 매칭 (기존 방식)
                        if (value.contains(xmlConfig.trim())) {
                            shouldRemove = true;
                            matchReason = "XML content match";
                        }
                    }

                    if (shouldRemove) {
                        _vmDetailsDao.remove(detail.getId());
                        logger.info("Removed device {} configuration from VM {} extraconfig key: {} (reason: {})",
                                   deviceName, vmId, detail.getName(), matchReason);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to remove device {} from VM {} extraconfig: {}", deviceName, vmId, e.getMessage());
        }
    }

    /**
     * 디바이스 타입 판별 메서드들
     */
    private boolean isPciDevice(String deviceName) {
        return deviceName.matches("\\d{4}:\\d{2}:\\d{2}\\.\\d");
    }

    private boolean isUsbDevice(String deviceName) {
        return deviceName.matches("\\d{3} Device \\d{3}");
    }

    private boolean isHbaDevice(String deviceName) {
        return deviceName.startsWith("scsi_host") && !deviceName.contains("vhba");
    }

    private boolean isVhbaDevice(String deviceName) {
        return deviceName.startsWith("scsi_host") && deviceName.contains("vhba");
    }

    private boolean isLunDevice(String deviceName) {
        return deviceName.startsWith("/dev/");
    }

    private boolean isScsiDevice(String deviceName) {
        return deviceName.startsWith("/dev/sg");
    }

    /**
     * 디바이스별 매칭 메서드들
     */
    private boolean matchUsbDevice(String xmlValue, String deviceName) {
        try {
            // "001 Device 004" -> bus="001", device="004"
            String[] parts = deviceName.split(" ");
            if (parts.length >= 3) {
                String bus = parts[0];
                String device = parts[2];

                // XML에서 bus와 device 번호 추출하여 매칭
                String busPattern = "bus='0x" + bus + "'";
                String devicePattern = "device='0x" + device + "'";

                boolean busMatch = xmlValue.contains(busPattern);
                boolean deviceMatch = xmlValue.contains(devicePattern);

                logger.debug("USB device match - deviceName: {}, bus: {}, device: {}, busMatch: {}, deviceMatch: {}",
                           deviceName, bus, device, busMatch, deviceMatch);

                return busMatch && deviceMatch;
            }
        } catch (Exception e) {
            logger.warn("Error matching USB device {}: {}", deviceName, e.getMessage());
        }
        return false;
    }

    private boolean matchHbaDevice(String xmlValue, String deviceName) {
        try {
            // "scsi_host18" -> adapter name="scsi_host18"
            String adapterPattern = "adapter name='" + deviceName + "'";
            boolean match = xmlValue.contains(adapterPattern);

            logger.debug("HBA device match - deviceName: {}, adapterPattern: {}, match: {}",
                       deviceName, adapterPattern, match);

            return match;
        } catch (Exception e) {
            logger.warn("Error matching HBA device {}: {}", deviceName, e.getMessage());
        }
        return false;
    }

    private boolean matchVhbaDevice(String xmlValue, String deviceName) {
        try {
            // "scsi_host18" -> adapter name="scsi_host18"
            String adapterPattern = "adapter name='" + deviceName + "'";
            boolean match = xmlValue.contains(adapterPattern);

            logger.debug("vHBA device match - deviceName: {}, adapterPattern: {}, match: {}",
                       deviceName, adapterPattern, match);

            return match;
        } catch (Exception e) {
            logger.warn("Error matching vHBA device {}: {}", deviceName, e.getMessage());
        }
        return false;
    }

    private boolean matchScsiDevice(String xmlValue, String deviceName) {
        try {
            // "/dev/sg33" -> scsi_host 번호 추출하여 매칭
            // 실제로는 동적으로 생성된 SCSI 주소를 사용하므로 XML 내용으로 매칭
            // 이 경우는 xmlConfig 매개변수를 사용하는 것이 더 안전
            return xmlValue.contains(deviceName);
        } catch (Exception e) {
            logger.warn("Error matching SCSI device {}: {}", deviceName, e.getMessage());
        }
        return false;
    }

    private boolean matchLunDevice(String xmlValue, String deviceName) {
        try {
            logger.debug("Matching LUN device - deviceName: {}, xmlValue: {}", deviceName, xmlValue);

            // LUN 디바이스 이름은 다음과 같은 형태일 수 있음:
            // "/dev/sdc (scsi-360000000000000000000000000000000)"
            // "/dev/dm-10 (dm-uuid-360000000000000000000000000000000)"

            // 1. XML에서 실제 사용되는 경로를 우선적으로 매칭
            if (xmlValue.contains("/dev/disk/by-id/")) {
                // XML에서 by-id 경로 추출하여 매칭
                java.util.regex.Pattern byIdPattern = java.util.regex.Pattern.compile("/dev/disk/by-id/([^\"'\\s>]+)");
                java.util.regex.Matcher byIdMatcher = byIdPattern.matcher(xmlValue);
                if (byIdMatcher.find()) {
                    String xmlByIdPath = byIdMatcher.group(1);
                    logger.debug("Found by-id path in XML: {}", xmlByIdPath);

                    // 디바이스 이름에서 추출한 by-id 값과 비교
                    String byIdValue = extractByIdFromDeviceName(deviceName);
                    if (byIdValue != null) {
                        // 다양한 접두사로 매칭 시도
                        String[] prefixes = {"wwn-", "scsi-", "scsi-SATA_", "dm-uuid-"};
                        for (String prefix : prefixes) {
                            if (xmlByIdPath.equals(prefix + byIdValue)) {
                                logger.debug("XML by-id path match found: {}", xmlByIdPath);
                                return true;
                            }
                        }

                        // 접두사 없이도 매칭 시도
                        if (xmlByIdPath.equals(byIdValue)) {
                            logger.debug("XML by-id path match found (no prefix): {}", xmlByIdPath);
                            return true;
                        }
                    }
                }
            }

            // 2. 직접적인 경로 매칭
            if (xmlValue.contains(deviceName)) {
                logger.debug("Direct device name match found");
                return true;
            }

            // 3. dm 디바이스의 경우 기존 로직 유지
            if (deviceName.contains("dm-") || deviceName.contains("dm-uuid-")) {
                // dm 디바이스는 기존 로직으로 처리
                String byIdValue = extractByIdFromDeviceName(deviceName);
                if (byIdValue != null) {
                    String byIdPath = "/dev/disk/by-id/" + byIdValue;
                    if (xmlValue.contains(byIdPath)) {
                        logger.debug("DM by-ID path match found: {}", byIdPath);
                        return true;
                    }
                }

                // dm-uuid에서 실제 dm-X 경로로 변환하여 매칭
                if (deviceName.contains("dm-uuid-")) {
                    String dmPath = convertDmUuidToDmPath(deviceName);
                    if (dmPath != null && xmlValue.contains(dmPath)) {
                        logger.debug("DM path match found: {}", dmPath);
                        return true;
                    }
                }
            } else {
                // 4. dm이 아닌 디바이스의 경우 기본 경로 우선 매칭 (마이그레이션 호환성)
                String basePath = extractBasePathFromDeviceName(deviceName);
                if (basePath != null && xmlValue.contains(basePath)) {
                    logger.debug("Base path match found: {}", basePath);
                    return true;
                }
            }

            logger.debug("No LUN device match found");
            return false;
        } catch (Exception e) {
            logger.warn("Error matching LUN device {}: {}", deviceName, e.getMessage());
        }
        return false;
    }

    private String extractByIdFromDeviceName(String deviceName) {
        try {
            // "/dev/sdc (scsi-360000000000000000000000000000000)" -> "scsi-360000000000000000000000000000000"
            // "/dev/sdc (wwn-0x5ace42e4350075f6)" -> "wwn-0x5ace42e4350075f6"
            // "/dev/sdc (scsi-SATA_HFS3T8G3H2X069N_KJD3N4392I0903D2S)" -> "scsi-SATA_HFS3T8G3H2X069N_KJD3N4392I0903D2S"
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\(([^)]+)\\)");
            java.util.regex.Matcher matcher = pattern.matcher(deviceName);
            if (matcher.find()) {
                String byIdValue = matcher.group(1);
                // 접두사가 있는 경우 접두사 제거하여 순수 ID만 반환
                if (byIdValue.startsWith("wwn-") || byIdValue.startsWith("scsi-") || byIdValue.startsWith("dm-uuid-")) {
                    // scsi-SATA_ 같은 복합 접두사의 경우 첫 번째 '-' 이후의 모든 내용을 ID로 사용
                    return byIdValue.substring(byIdValue.indexOf('-') + 1);
                }
                return byIdValue;
            }
        } catch (Exception e) {
            logger.debug("Error extracting by-id from device name: {}", e.getMessage());
        }
        return null;
    }

    private String extractBasePathFromDeviceName(String deviceName) {
        try {
            // "/dev/sdc (scsi-360000000000000000000000000000000)" -> "/dev/sdc"
            if (deviceName.contains(" (")) {
                return deviceName.split(" \\(")[0];
            }
            return deviceName;
        } catch (Exception e) {
            logger.debug("Error extracting base path from device name: {}", e.getMessage());
        }
        return null;
    }

    private String convertDmUuidToDmPath(String deviceName) {
        try {
            // dm-uuid가 포함된 경우 일반적으로 /dev/dm-X 형태로 변환
            // 실제 구현에서는 백엔드에서 동적으로 변환하므로 여기서는 일반적인 패턴 사용
            if (deviceName.contains("dm-uuid-")) {
                return "/dev/dm-10"; // 일반적인 dm 디바이스 경로
            }
        } catch (Exception e) {
            logger.debug("Error converting dm-uuid to dm path: {}", e.getMessage());
        }
        return null;
    }

    /**
     * VM 삭제 시 해당 VM에 할당된 모든 디바이스를 자동으로 해제합니다.
     */
    @Override
    public void deallocateAllDevicesOnVmDestroy(Long vmId) {
        if (vmId == null) {
            logger.warn("VM ID is null, skipping device deallocation");
            return;
        }

        logger.info("Deallocating all devices for VM: {}", vmId);

        try {
            // VM 정보 조회
            VMInstanceVO vm = _vmInstanceDao.findById(vmId);
            if (vm == null) {
                logger.warn("VM not found with ID: {}, skipping device deallocation", vmId);
                return;
            }

            String vmIdStr = vmId.toString();
            String vmInstanceName = vm.getInstanceName();
            Long hostId = vm.getHostId();
            if (hostId == null) {
                hostId = vm.getLastHostId();
            }

            logger.info("VM {} (instance: {}) on host: {}", vmId, vmInstanceName, hostId);

            // 1. PCI 디바이스 해제
            deallocatePciDevicesForVm(vmIdStr, hostId);

            // 2. USB 디바이스 해제
            deallocateUsbDevicesForVm(vmIdStr, vmInstanceName, hostId);

            // 3. HBA 디바이스 해제
            deallocateHbaDevicesForVm(vmIdStr, vmInstanceName, hostId);

            // 4. LUN 디바이스 해제
            deallocateLunDevicesForVm(vmIdStr, vmInstanceName, hostId);

            // 5. SCSI 디바이스 해제
            deallocateScsiDevicesForVm(vmIdStr, vmInstanceName, hostId);

            // 6. vHBA 디바이스 해제
            deallocateVhbaDevicesForVm(vmIdStr, vmInstanceName, hostId);

            logger.info("Successfully deallocated all devices for VM: {}", vmId);

        } catch (Exception e) {
            logger.error("Error deallocating devices for VM {}: {}", vmId, e.getMessage(), e);
        }
    }

    private void deallocatePciDevicesForVm(String vmId, Long hostId) {
        try {
            if (hostId == null) return;

            // VM ID로 할당된 모든 디바이스 찾기 (값으로 검색)
            SearchCriteria<DetailVO> sc = _hostDetailsDao.createSearchCriteria();
            sc.addAnd("hostId", SearchCriteria.Op.EQ, hostId);
            sc.addAnd("value", SearchCriteria.Op.EQ, vmId);
            List<DetailVO> allocations = _hostDetailsDao.search(sc, null);

            for (DetailVO allocation : allocations) {
                String deviceName = allocation.getName();
                if (isPciDevice(deviceName)) {
                    logger.info("Deallocating PCI device {} from VM {}", deviceName, vmId);
                    _hostDetailsDao.remove(allocation.getId());

                    // extraconfig 삭제
                    try {
                        removeDeviceFromVmExtraConfig(Long.parseLong(vmId), deviceName, "");
                    } catch (Exception e) {
                        logger.warn("Failed to remove PCI device {} from extraconfig: {}", deviceName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error deallocating PCI devices for VM {}: {}", vmId, e.getMessage());
        }
    }

    private void deallocateUsbDevicesForVm(String vmId, String vmInstanceName, Long hostId) {
        try {
            if (hostId == null) return;

            SearchCriteria<DetailVO> sc = _hostDetailsDao.createSearchCriteria();
            sc.addAnd("hostId", SearchCriteria.Op.EQ, hostId);
            sc.addAnd("value", SearchCriteria.Op.EQ, vmId);
            List<DetailVO> allocations = _hostDetailsDao.search(sc, null);

            for (DetailVO allocation : allocations) {
                String deviceName = allocation.getName();
                if (isUsbDevice(deviceName)) {
                    logger.info("Deallocating USB device {} from VM {}", deviceName, vmId);
                    _hostDetailsDao.remove(allocation.getId());

                    // extraconfig 삭제
                    try {
                        removeDeviceFromVmExtraConfig(Long.parseLong(vmId), deviceName, "");
                    } catch (Exception e) {
                        logger.warn("Failed to remove USB device {} from extraconfig: {}", deviceName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error deallocating USB devices for VM {}: {}", vmId, e.getMessage());
        }
    }

    private void deallocateHbaDevicesForVm(String vmId, String vmInstanceName, Long hostId) {
        try {
            if (hostId == null) return;

            SearchCriteria<DetailVO> sc = _hostDetailsDao.createSearchCriteria();
            sc.addAnd("hostId", SearchCriteria.Op.EQ, hostId);
            sc.addAnd("value", SearchCriteria.Op.EQ, vmId);
            List<DetailVO> allocations = _hostDetailsDao.search(sc, null);

            for (DetailVO allocation : allocations) {
                String deviceName = allocation.getName();
                if (isHbaDevice(deviceName)) {
                    logger.info("Deallocating HBA device {} from VM {}", deviceName, vmId);
                    _hostDetailsDao.remove(allocation.getId());

                    // extraconfig 삭제
                    try {
                        removeDeviceFromVmExtraConfig(Long.parseLong(vmId), deviceName, "");
                    } catch (Exception e) {
                        logger.warn("Failed to remove HBA device {} from extraconfig: {}", deviceName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error deallocating HBA devices for VM {}: {}", vmId, e.getMessage());
        }
    }

    private void deallocateLunDevicesForVm(String vmId, String vmInstanceName, Long hostId) {
        try {
            if (hostId == null) return;

            SearchCriteria<DetailVO> sc = _hostDetailsDao.createSearchCriteria();
            sc.addAnd("hostId", SearchCriteria.Op.EQ, hostId);
            sc.addAnd("value", SearchCriteria.Op.EQ, vmId);
            List<DetailVO> allocations = _hostDetailsDao.search(sc, null);

            for (DetailVO allocation : allocations) {
                String deviceName = allocation.getName();
                if (isLunDevice(deviceName)) {
                    logger.info("Deallocating LUN device {} from VM {}", deviceName, vmId);
                    _hostDetailsDao.remove(allocation.getId());

                    // extraconfig 삭제
                    try {
                        removeDeviceFromVmExtraConfig(Long.parseLong(vmId), deviceName, "");
                    } catch (Exception e) {
                        logger.warn("Failed to remove LUN device {} from extraconfig: {}", deviceName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error deallocating LUN devices for VM {}: {}", vmId, e.getMessage());
        }
    }

    private void deallocateScsiDevicesForVm(String vmId, String vmInstanceName, Long hostId) {
        try {
            if (hostId == null) return;

            SearchCriteria<DetailVO> sc = _hostDetailsDao.createSearchCriteria();
            sc.addAnd("hostId", SearchCriteria.Op.EQ, hostId);
            sc.addAnd("value", SearchCriteria.Op.EQ, vmId);
            List<DetailVO> allocations = _hostDetailsDao.search(sc, null);

            for (DetailVO allocation : allocations) {
                String deviceName = allocation.getName();
                if (isScsiDevice(deviceName)) {
                    logger.info("Deallocating SCSI device {} from VM {}", deviceName, vmId);
                    _hostDetailsDao.remove(allocation.getId());

                    // extraconfig 삭제
                    try {
                        removeDeviceFromVmExtraConfig(Long.parseLong(vmId), deviceName, "");
                    } catch (Exception e) {
                        logger.warn("Failed to remove SCSI device {} from extraconfig: {}", deviceName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error deallocating SCSI devices for VM {}: {}", vmId, e.getMessage());
        }
    }

    private void deallocateVhbaDevicesForVm(String vmId, String vmInstanceName, Long hostId) {
        try {
            if (hostId == null) return;

            SearchCriteria<DetailVO> sc = _hostDetailsDao.createSearchCriteria();
            sc.addAnd("hostId", SearchCriteria.Op.EQ, hostId);
            sc.addAnd("value", SearchCriteria.Op.EQ, vmId);
            List<DetailVO> allocations = _hostDetailsDao.search(sc, null);

            for (DetailVO allocation : allocations) {
                String deviceName = allocation.getName();
                if (isVhbaDevice(deviceName)) {
                    logger.info("Deallocating vHBA device {} from VM {}", deviceName, vmId);
                    _hostDetailsDao.remove(allocation.getId());

                    // extraconfig 삭제
                    try {
                        removeDeviceFromVmExtraConfig(Long.parseLong(vmId), deviceName, "");
                    } catch (Exception e) {
                        logger.warn("Failed to remove vHBA device {} from extraconfig: {}", deviceName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error deallocating vHBA devices for VM {}: {}", vmId, e.getMessage());
        }
    }
}
