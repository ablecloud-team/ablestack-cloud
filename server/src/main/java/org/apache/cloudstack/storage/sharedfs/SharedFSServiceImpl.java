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

package org.apache.cloudstack.storage.sharedfs;

import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSCleanupDelay;
import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSCleanupInterval;
import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSFeatureEnabled;
import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSExpungeWorkers;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.org.Grouping;
import com.cloud.projects.Project;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.NetUtils;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ChangeSharedFSDiskOfferingCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ChangeSharedFSServiceOfferingCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ExpungeSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ListSharedFSProvidersCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ListSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.DestroySharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.RecoverSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.RestartSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.StartSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.StopSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.UpdateSharedFSCmd;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.dataservice.StorageAccessRuleVO;
import org.apache.cloudstack.storage.dataservice.StorageFileShareVO;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstance;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommand;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandDispatcher;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandResult;
import org.apache.cloudstack.storage.dataservice.StorageServiceProtocolVO;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.sharedfs.SharedFS.Event;
import org.apache.cloudstack.storage.sharedfs.SharedFS.State;
import org.apache.cloudstack.storage.sharedfs.query.dao.SharedFSJoinDao;
import org.apache.cloudstack.storage.sharedfs.query.vo.SharedFSJoinVO;
import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonObject;

import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

public class SharedFSServiceImpl extends ManagerBase implements SharedFSService, Configurable, PluggableService {
    private static final String SHAREDFS_COMPAT_PROVIDER = "SHAREDFS_COMPATIBILITY";
    private static final String CONFIGURE_SHAREDFS_STATIC_NETWORK = "configure-sharedfs-static-network";
    private static final int STATIC_NETWORK_QGA_ATTEMPTS = 30;
    private static final int STATIC_NETWORK_QGA_RETRY_MILLIS = 2000;

    protected static class StaticNetworkConfiguration {
        final String ipAddress;
        final String networkCidr;

        StaticNetworkConfiguration(String ipAddress, String networkCidr) {
            this.ipAddress = ipAddress;
            this.networkCidr = networkCidr;
        }
    }

    @Inject
    private AccountManager accountMgr;

    @Inject
    private DataCenterDao dataCenterDao;

    @Inject
    private ConfigurationManager configMgr;

    @Inject
    private VolumeApiService volumeApiService;

    @Inject
    private SharedFSDao sharedFSDao;

    @Inject
    private SharedFSJoinDao sharedFSJoinDao;

    @Inject
    private DiskOfferingDao diskOfferingDao;

    @Inject
    ConfigurationDao configDao;

    @Inject
    VolumeDao volumeDao;

    @Inject
    PrimaryDataStoreDao storagePoolDao;

    @Inject
    NetworkDao networkDao;

    @Inject
    NetworkModel networkModel;

    @Inject
    NicDao nicDao;

    @Inject
    StorageServiceInstanceDao storageServiceInstanceDao;

    @Inject
    StorageServiceProtocolDao storageServiceProtocolDao;

    @Inject
    StorageFileShareDao storageFileShareDao;

    @Inject
    StorageAccessRuleDao storageAccessRuleDao;

    @Inject
    StorageServiceGuestCommandDispatcher guestCommandDispatcher;

    @Inject
    org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao storageBlockTargetDao;

    @Inject
    org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao storageRuntimeUpgradeDao;

    @Inject org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao storageTemplateUpgradeDao;
    @Inject javax.inject.Provider<org.apache.cloudstack.storage.dataservice.StorageService> managedStorageService;
    private final ThreadLocal<String> approvedScaleRecovery = new ThreadLocal<>();

    protected void requireNoRootMaintenance(SharedFS sharedFS) {
        StorageServiceInstanceVO instance=sharedFS.getVmId()==null?null:storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
        if (instance!=null && storageTemplateUpgradeDao.findActive(instance.getId())!=null) throw new CloudRuntimeException("ROOT template maintenance must complete or recover before a service lifecycle change");
    }

    @Inject
    com.cloud.storage.dao.VolumeDetailsDao volumeDetailsDao;

    protected void requireNoUnresolvedWriter(SharedFS sharedFS) {
        if (sharedFS.getVmId() == null) return;
        StorageServiceInstanceVO instance = storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
        if (instance == null) return;
        for (org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO operation : storageOperationDao.listByInstance(instance.getId())) {
            if (operation.getUuid().equals(approvedScaleRecovery.get()) && "SHAREDFS_ONLINE_SCALE".equals(operation.getAction())
                    && operation.getInstanceId() == instance.getId() && "RUNNING".equals(operation.getState()) && "ROLLING_BACK".equals(operation.getPhase())) continue;
            if (Set.of("RUNNING", "RECOVERY_REQUIRED", "ROLLBACK_FAILED").contains(operation.getState())) {
                throw new CloudRuntimeException("Unresolved Storage Service writer preserves VM and DATA until formal recovery");
            }
        }
    }

    protected void requireNativeLifecycleIdle(SharedFS sharedFS) {
        requireNoUnresolvedWriter(sharedFS);
        if (sharedFS.getVmId() == null) return;
        com.cloud.vm.VMInstanceVO vm = vmInstanceDao.findById(sharedFS.getVmId());
        List<VolumeVO> volumes = volumeDao.findByInstanceAndType(sharedFS.getVmId(), Volume.Type.DATADISK);
        if (vm == null || vm.getState() != com.cloud.vm.VirtualMachine.State.Running) {
            for (VolumeVO volume : volumes) {
                com.cloud.storage.VolumeDetailVO state = volumeDetailsDao.findDetail(volume.getId(), org.apache.cloudstack.storage.dataservice.StorageVolumeLifecycleProtection.STATE);
                com.cloud.storage.VolumeDetailVO receipt = volumeDetailsDao.findDetail(volume.getId(), org.apache.cloudstack.storage.dataservice.StorageVolumeLifecycleProtection.RECEIPT);
                org.apache.cloudstack.storage.dataservice.StorageVolumeLifecycleProtection.requireVerified(volume,
                        state == null ? null : state.getValue(), receipt == null ? null : receipt.getValue());
            }
            return;
        }
        StorageServiceGuestCommandResult observed = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                vm.getId(), "operation writer-idle", "{}", 15, Set.of()));
        JsonObject idle = observed.isSuccess() ? com.google.gson.JsonParser.parseString(observed.getResultJson()).getAsJsonObject() : new JsonObject();
        if (!idle.has("status") || !"WRITER_IDLE".equals(idle.get("status").getAsString())) {
            throw new CloudRuntimeException("Active or terminating formatter preserves VM and DATA until forward recovery");
        }
        for (VolumeVO volume : volumes) {
            JsonObject payload = new JsonObject();payload.addProperty("volumeUuid", volume.getUuid());
            StorageServiceGuestCommandResult probe = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                    vm.getId(), "volume operation status", payload.toString(), 5, Set.of()));
            if (!probe.isSuccess()) throw new CloudRuntimeException("Formatter journal observation is unavailable; VM and DATA lifecycle change is blocked");
            org.apache.cloudstack.storage.dataservice.StorageFormatterLifecycleGate.requireIdle(com.google.gson.JsonParser.parseString(probe.getResultJson()).getAsJsonObject());
            String receipt = org.apache.cloudstack.storage.dataservice.StorageVolumeLifecycleProtection.identity(volume).toString();
            if (receipt.length() > 512) throw new CloudRuntimeException("Persistent DATA safety identity exceeds the supported metadata bound");
            volumeDetailsDao.addDetail(volume.getId(), org.apache.cloudstack.storage.dataservice.StorageVolumeLifecycleProtection.RECEIPT, receipt, false);
            volumeDetailsDao.addDetail(volume.getId(), org.apache.cloudstack.storage.dataservice.StorageVolumeLifecycleProtection.STATE, "VERIFIED", false);
        }
    }

    private final ThreadLocal<Long> approvedRemovalVm = new ThreadLocal<>();

    @Override
    public Long getVmStorageServiceSyncId(long vmId) {
        SharedFSVO shared = sharedFSDao.findByVm(vmId);
        if (shared == null) throw new InvalidParameterValueException("SharedFS VM writer binding is unavailable");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, shared);
        StorageServiceInstanceVO instance = storageServiceInstanceDao.findByVmId(vmId);
        if (instance != null && (instance.getAccountId() != shared.getAccountId() || instance.getDomainId() != shared.getDomainId()
                || instance.getDataCenterId() != shared.getDataCenterId())) throw new CloudRuntimeException("SharedFS writer binding belongs to another owner/domain/zone");
        return instance == null ? Math.addExact(4_000_000_000_000_000_000L, shared.getId()) : instance.getId();
    }

    @Override
    public void requireVmLifecycleSafety(long vmId, String operation) {
        SharedFSVO shared = sharedFSDao.findByVm(vmId);
        if (shared == null) throw new CloudRuntimeException("SharedFS VM lifecycle binding is unavailable; preserve VM and DATA");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, shared);
        if (Set.of("DESTROY", "EXPUNGE").contains(operation)) {
            if (!Objects.equals(approvedRemovalVm.get(), vmId)) throw new InvalidParameterValueException("Use the SharedFS deletion policy API to preserve or explicitly delete DATA; direct VM removal is blocked");
            storedDeletionVolumeIds(shared);
            if (!volumeDao.findByInstanceAndType(vmId, Volume.Type.DATADISK).isEmpty()) throw new CloudRuntimeException("All approved DATA must be detached and verified before VM removal");
        } else if ("RESTORE".equals(operation)) {
            throw new InvalidParameterValueException("Use the verified SharedFS ROOT template maintenance API; generic ROOT replacement is blocked");
        } else if (!Set.of("STOP", "REBOOT", "MIGRATE").contains(operation)) {
            throw new InvalidParameterValueException("Unknown SharedFS VM lifecycle operation");
        }
        requireNoRootMaintenance(shared);
        requireNativeLifecycleIdle(shared);
    }

    protected boolean removeSharedFSProvider(SharedFSVO shared, SharedFSLifeCycle lifecycle, Set<Long> volumes) {
        Long previous = approvedRemovalVm.get();
        approvedRemovalVm.set(shared.getVmId());
        try {return lifecycle.deleteSharedFS(shared, shared.getDataVolumePolicy(), volumes);}
        finally {if (previous == null) approvedRemovalVm.remove();else approvedRemovalVm.set(previous);}
    }

    protected <T> T withSharedFSWriterLock(SharedFS sharedFS, java.util.function.Supplier<T> action) {
        StorageServiceInstanceVO instance=sharedFS.getVmId()==null ? null : storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
        String key=instance==null ? "SharedFSRemoval-"+sharedFS.getId() : "StorageServiceWriter-"+instance.getId();
        com.cloud.utils.db.GlobalLock lock=com.cloud.utils.db.GlobalLock.getInternLock(key);
        boolean held=false;
        try {
            held=lock.lock(30);
            if (!held) throw new CloudRuntimeException("Another Storage Service operation is active");
            requireNoRootMaintenance(sharedFS);
            if (completedDeletionReceipt(sharedFS)) storedDeletionVolumeIds(sharedFS);
            else requireNativeLifecycleIdle(sharedFS);
            if (instance!=null && storageRuntimeUpgradeDao.findActiveByInstanceId(instance.getId())!=null) throw new CloudRuntimeException("A runtime upgrade is active; the requested service change is blocked");
            return action.get();
        } finally { if (held) lock.unlock(); lock.releaseRef(); }
    }

    @Inject com.cloud.storage.dao.VMTemplateDao explicitTemplateDao;
    @Inject org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao explicitTemplateStoreDao;
    @Inject com.cloud.host.dao.HostDao explicitTemplateHostDao;

    protected Set<String> existingTemplateFixtureExclusions() {
        Set<String> excluded=new java.util.HashSet<>();for(StorageServiceInstanceVO instance:storageServiceInstanceDao.listAll())excluded.add(instance.getUuid());return excluded;
    }
    protected JsonObject explicitTemplateFixtureRequest(CreateSharedFSCmd cmd,Account owner,com.cloud.storage.VMTemplateVO template) {
        com.cloud.service.ServiceOfferingVO rootService=serviceOfferingDao.findById(cmd.getServiceOfferingId());
        DiskOfferingVO rootDisk=rootService==null||rootService.getDiskOfferingId()==null?null:diskOfferingDao.findById(rootService.getDiskOfferingId());
        DiskOfferingVO dataDisk=cmd.getDiskOfferingId()==null?null:diskOfferingDao.findById(cmd.getDiskOfferingId());
        if(rootDisk==null||dataDisk==null)throw new InvalidParameterValueException("Private fixture requires explicit ROOT and DATA disk offerings");
        JsonObject request=new JsonObject();request.addProperty("name",cmd.getName());request.addProperty("accountId",owner.getId());request.addProperty("accountUuid",owner.getUuid());request.addProperty("zoneId",cmd.getZoneId());
        request.addProperty("templateUuid",template.getUuid());request.addProperty("templateChecksum",template.getChecksum());request.addProperty("templateDetailsSha256",org.apache.cloudstack.storage.dataservice.StorageTemplateFixturePermit.detailsSha256(template));
        request.addProperty("serviceOfferingUuid",rootService.getUuid());request.addProperty("rootDiskOfferingUuid",rootDisk.getUuid());request.addProperty("dataDiskOfferingUuid",dataDisk.getUuid());
        request.addProperty("rootProvisioningType",rootDisk.getProvisioningType().name());request.addProperty("dataProvisioningType",dataDisk.getProvisioningType().name());
        request.addProperty("backingVolumeMode",cmd.isExistingVolume()?"EXISTING":"NEW");request.addProperty("storagePoolId",cmd.getStorageId());request.addProperty("networkId",cmd.getNetworkId());request.addProperty("sizeGiB",cmd.getSize());request.addProperty("filesystem",cmd.getFsFormat());
        request.addProperty("networkMode",cmd.getNetworkMode().name());request.addProperty("ipCidr",cmd.getIpCidr());request.addProperty("gateway",cmd.getGateway());request.addProperty("dns1",cmd.getDns1());request.addProperty("dns2",cmd.getDns2());return request;
    }
    protected com.cloud.storage.VMTemplateVO validateExplicitTemplate(CreateSharedFSCmd cmd,Account owner,DataCenter zone) {
        if(cmd.getTemplateId()==null) {
            if(cmd.getValidationArtifactUuid()!=null||cmd.getValidationArtifactSha256()!=null)throw new InvalidParameterValueException("Fixture artifact requires an explicit template");return null;
        }
        com.cloud.storage.VMTemplateVO template=explicitTemplateDao.findById(cmd.getTemplateId());
        if(template==null||template.getRemoved()!=null||template.getState()!=com.cloud.template.VirtualMachineTemplate.State.Active||!template.isDynamicallyScalable()
                ||template.getHypervisorType()!=com.cloud.hypervisor.Hypervisor.HypervisorType.KVM||template.getArch()==null
                ||!template.getArch().name().equalsIgnoreCase(com.cloud.resource.ResourceManager.SystemVmPreferredArchitecture.valueIn(zone.getId())))throw new InvalidParameterValueException("Explicit template is not an active scalable KVM template for the configured architecture");
        accountMgr.checkAccess(owner,org.apache.cloudstack.acl.SecurityChecker.AccessType.UseEntry,false,template);
        explicitTemplateDao.loadDetails(template);
        org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO ready=explicitTemplateStoreDao.findByTemplateZoneReady(template.getId(),zone.getId());
        boolean downloaded=ready!=null&&ready.getDownloadState()==com.cloud.storage.VMTemplateStorageResourceAssoc.Status.DOWNLOADED&&ready.getState()==org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State.Ready;
        boolean privateFixture=template.getTemplateType()==com.cloud.storage.Storage.TemplateType.USER;
        if(privateFixture) {
            if(StringUtils.isBlank(template.getChecksum())||template.getFormat()!=com.cloud.storage.Storage.ImageFormat.QCOW2)throw new InvalidParameterValueException("Private fixture requires a checksum-pinned QCOW2 template");
            Account caller=CallContext.current().getCallingAccount();
            if(!accountMgr.isRootAdmin(caller.getId())||template.isPublicTemplate()||template.getAccountId()!=owner.getId()||cmd.isExistingVolume())throw new PermissionDeniedException("Private USER template requires an owned NEW disposable fixture and root administrator");
            JsonObject request=explicitTemplateFixtureRequest(cmd,owner,template);
            new org.apache.cloudstack.storage.dataservice.StorageTemplateFixturePermit().approve(cmd.getValidationArtifactUuid(),cmd.getValidationArtifactSha256(),request,existingTemplateFixtureExclusions());
        } else if(cmd.getValidationArtifactUuid()!=null||cmd.getValidationArtifactSha256()!=null)throw new InvalidParameterValueException("Private fixture artifact cannot authorize a different template type");
        String managerVersion=com.cloud.server.ManagementServer.class.getPackage().getImplementationVersion();
        List<com.cloud.host.HostVO> hosts=explicitTemplateHostDao.listAllHostsUpByZoneAndHypervisor(zone.getId(),com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
        if(hosts==null||hosts.isEmpty())throw new InvalidParameterValueException("No active KVM consumer is available for explicit template validation");
        for(com.cloud.host.HostVO host:hosts) {
            JsonObject compatibility=org.apache.cloudstack.storage.dataservice.StorageTemplateCompatibility.evaluate(template,template,template.getDetails(),downloaded,managerVersion,host.getVersion(),false,privateFixture);
            if(!compatibility.get("compatible").getAsBoolean())throw new InvalidParameterValueException("Explicit Storage Service template is incompatible: "+compatibility.get("blockers"));
        }
        return template;
    }
    protected JsonObject templateFixtureDisk(VolumeVO volume,long vmId) {
        JsonObject disk=new JsonObject();disk.addProperty("volumeUuid",volume.getUuid());disk.addProperty("path",volume.getPath());disk.addProperty("poolId",volume.getPoolId());disk.addProperty("type",volume.getVolumeType().name());disk.addProperty("accountId",volume.getAccountId());disk.addProperty("zoneId",volume.getDataCenterId());disk.addProperty("sizeBytes",volume.getSize());disk.addProperty("provisioningType",volume.getProvisioningType()==null?null:volume.getProvisioningType().name());disk.addProperty("templateId",volume.getTemplateId());disk.addProperty("state",volume.getState().name());disk.addProperty("attachedToFixture",java.util.Objects.equals(volume.getInstanceId(),vmId));disk.addProperty("notRemoved",volume.getRemoved()==null);disk.addProperty("newDataWithoutBacking",volume.getTemplateId()==null&&StringUtils.isBlank(volume.getChainInfo()));return disk;
    }
    protected void completeExplicitTemplateFixture(CreateSharedFSCmd cmd,SharedFSVO sharedFS,com.cloud.storage.VMTemplateVO template) {
        if(template==null||template.getTemplateType()!=com.cloud.storage.Storage.TemplateType.USER)return;
        com.cloud.vm.VMInstanceVO vm=vmInstanceDao.findById(sharedFS.getVmId());List<VolumeVO> roots=volumeDao.findByInstanceAndType(vm.getId(),Volume.Type.ROOT);VolumeVO data=volumeDao.findById(sharedFS.getVolumeId());
        if(vm.getTemplateId()!=template.getId()||roots.size()!=1||data==null||data.getTemplateId()!=null||StringUtils.isNotBlank(data.getChainInfo())||data.getAccountId()!=sharedFS.getAccountId()||data.getDataCenterId()!=sharedFS.getDataCenterId()
                ||data.getInstanceId()==null||data.getInstanceId()!=vm.getId()||!java.util.Objects.equals(data.getPoolId(),cmd.getStorageId()))throw new CloudRuntimeException("Private fixture allocation identity changed before publication");
        JsonObject request=explicitTemplateFixtureRequest(cmd,accountMgr.getActiveAccountById(sharedFS.getAccountId()),template),rootDisk=templateFixtureDisk(roots.get(0),vm.getId()),dataDisk=templateFixtureDisk(data,vm.getId());
        JsonObject expectedRoot=rootDisk.deepCopy();expectedRoot.addProperty("accountId",sharedFS.getAccountId());expectedRoot.addProperty("zoneId",sharedFS.getDataCenterId());expectedRoot.addProperty("templateId",template.getId());expectedRoot.add("provisioningType",request.get("rootProvisioningType"));
        JsonObject expectedData=dataDisk.deepCopy();expectedData.addProperty("type","DATADISK");expectedData.addProperty("accountId",sharedFS.getAccountId());expectedData.addProperty("zoneId",sharedFS.getDataCenterId());expectedData.addProperty("poolId",cmd.getStorageId());expectedData.add("provisioningType",request.get("dataProvisioningType"));
        DiskOfferingVO offering=diskOfferingDao.findById(cmd.getDiskOfferingId());long size=offering.isCustomized()?Math.multiplyExact(cmd.getSize(),1L<<30):offering.getDiskSize();expectedData.addProperty("sizeBytes",size);
        org.apache.cloudstack.storage.dataservice.StorageTemplateFixturePermit.requireAllocatedDisk(expectedRoot,rootDisk);org.apache.cloudstack.storage.dataservice.StorageTemplateFixturePermit.requireAllocatedDisk(expectedData,dataDisk);
        JsonObject bindings=new JsonObject();bindings.addProperty("vmId",vm.getId());bindings.addProperty("vmUuid",vm.getUuid());bindings.addProperty("rootVolumeUuid",roots.get(0).getUuid());bindings.addProperty("dataVolumeUuid",data.getUuid());bindings.add("rootDisk",rootDisk);bindings.add("dataDisk",dataDisk);
        new org.apache.cloudstack.storage.dataservice.StorageTemplateFixturePermit().complete(cmd.getValidationArtifactUuid(),cmd.getValidationArtifactSha256(),sharedFS.getUuid(),explicitTemplateFixtureRequest(cmd,accountMgr.getActiveAccountById(sharedFS.getAccountId()),template),bindings);
    }

    @Inject
    com.cloud.vm.dao.VMInstanceDao vmInstanceDao;
    @Inject
    com.cloud.service.dao.ServiceOfferingDao serviceOfferingDao;

    @Override
    public org.apache.cloudstack.api.response.StorageServiceRuntimeResponse getSharedFSScalingReadiness(Long id) {
        SharedFSVO sharedFS=sharedFSDao.findById(id);
        if (sharedFS==null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(),null,false,sharedFS);
        com.google.gson.JsonObject result=new com.google.gson.JsonObject();com.google.gson.JsonArray reasons=new com.google.gson.JsonArray();
        com.cloud.vm.VMInstanceVO vm=sharedFS.getVmId()==null ? null : vmInstanceDao.findById(sharedFS.getVmId());
        if (vm==null) reasons.add("VM_UNAVAILABLE");
        else if (!vm.isDynamicallyScalable()) reasons.add("LEGACY_VM_DYNAMIC_SCALING_DISABLED");
        if (vm!=null && vm.getState()==com.cloud.vm.VirtualMachine.State.Running) {
            try {
                StorageServiceGuestCommandResult resources=guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(vm.getId(),"operation resources","{}",30,Set.of()));
                if (!resources.isSuccess()) reasons.add("GUEST_RESOURCE_OBSERVATION_UNAVAILABLE");
                else {
                    com.google.gson.JsonObject observed=new com.google.gson.JsonParser().parse(resources.getResultJson()).getAsJsonObject();
                    result.add("guestResources",observed);
                    if (!observed.has("success") || !observed.get("success").getAsBoolean()) reasons.add("GUEST_RESOURCE_OBSERVATION_UNAVAILABLE");
                    if (!observed.has("scaleActivationSupported") || !observed.get("scaleActivationSupported").getAsBoolean()) reasons.add("RUNTIME_SCALE_ACTIVATION_REQUIRED");
                    if (!observed.has("possibleCpuCount") || !observed.has("onlineCpuCount") || observed.get("possibleCpuCount").getAsInt()<=observed.get("onlineCpuCount").getAsInt()) reasons.add("CPU_HOTPLUG_HEADROOM_UNAVAILABLE");
                    if (!observed.has("memoryAutoOnline") || observed.get("memoryAutoOnline").isJsonNull()) reasons.add("GUEST_MEMORY_HOTPLUG_UNAVAILABLE");
                }
            } catch (RuntimeException failure) { reasons.add("GUEST_RESOURCE_OBSERVATION_UNAVAILABLE"); }
        } else reasons.add("VM_NOT_RUNNING");
        com.cloud.service.ServiceOfferingVO current=serviceOfferingDao.findByIdIncludingRemoved(sharedFS.getServiceOfferingId());
        if (current!=null) {
            com.google.gson.JsonObject requested=new com.google.gson.JsonObject();
            requested.addProperty("cpu",current.getCpu());requested.addProperty("memory",current.getRamSize());requested.addProperty("cpuspeed",current.getSpeed());
            result.add("currentOffering",requested);
        }
        List<org.apache.cloudstack.api.response.StorageServiceOfferingConstraintResponse> offerings=getSharedFSProvider(sharedFS.getFsProviderName()).getSharedFSLifeCycle().evaluateOfferings(validateAndGetZone(sharedFS.getDataCenterId()),List.of(sharedFS.getServiceOfferingId()));
        if (offerings.isEmpty()) reasons.add("OFFERING_CONSTRAINTS_UNAVAILABLE");
        else { result.add("offering",new com.google.gson.Gson().toJsonTree(offerings.get(0)));for (String reason:offerings.get(0).getReasons()) reasons.add(reason); }
        result.add("reasons",reasons);result.addProperty("ready",reasons.size()==0);
        org.apache.cloudstack.api.response.StorageServiceRuntimeResponse response=new org.apache.cloudstack.api.response.StorageServiceRuntimeResponse();
        response.setId(sharedFS.getUuid());response.setOperation("SCALING_READINESS");response.setSuccess(true);response.setStatus(reasons.size()==0 ? "READY" : "PREPARATION_REQUIRED");response.setResultJson(result.toString());response.setObjectName("sharedfilesystemscalingreadiness");return response;
    }

    protected List<SharedFSProvider> sharedFSProviders;

    private Map<String, SharedFSProvider> sharedFSProviderMap = new HashMap<>();

    protected final StateMachine2<State, Event, SharedFS> sharedFSStateMachine;

    ScheduledExecutorService _executor = null;

    public SharedFSServiceImpl() {
        this.sharedFSStateMachine = State.getStateMachine();
    }

    @Override
    public boolean start() {
        sharedFSProviderMap.clear();
        for (final SharedFSProvider provider : sharedFSProviders) {
            sharedFSProviderMap.put(provider.getName(), provider);
            provider.configure();
        }
        reconcileSharedFSToStorageService();
        _executor.scheduleWithFixedDelay(new SharedFSGarbageCollector(), SharedFSCleanupInterval.value(), SharedFSCleanupInterval.value(), TimeUnit.SECONDS);
        return true;
    }

    public boolean stop() {
        _executor.shutdown();
        return true;
    }

    @Override
    public List<SharedFSProvider> getSharedFSProviders() {
        return sharedFSProviders;
    }

    @Override
    public boolean stateTransitTo(SharedFS sharedFS, Event event) {
        try {
            return sharedFSStateMachine.transitTo(sharedFS, event, null, sharedFSDao);
        } catch (NoTransitionException e) {
            String message = String.format("State transit error for Shared FileSystem %s due to exception: %s.",
                    sharedFS, e.getMessage());
            logger.error(message, e);
            throw new CloudRuntimeException(message, e);
        }
    }

    @Override
    public void setSharedFSProviders(List<SharedFSProvider> sharedFSProviders) {
        this.sharedFSProviders = sharedFSProviders;
    }

    @Override
    public SharedFSProvider getSharedFSProvider(String sharedFSProviderName) {
        if (sharedFSProviderMap.containsKey(sharedFSProviderName)) {
            return sharedFSProviderMap.get(sharedFSProviderName);
        }
        throw new CloudRuntimeException("Invalid Shared FileSystem provider name!");
    }

    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        int wrks = SharedFSExpungeWorkers.value();
        _executor = Executors.newScheduledThreadPool(wrks, new NamedThreadFactory("SharedFS-Scavenger"));
        return true;
    }

    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        if (SharedFSFeatureEnabled.value()) {
            cmdList.add(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceOfferingConstraintsCmd.class);
            cmdList.add(ListSharedFSProvidersCmd.class);
            cmdList.add(CreateSharedFSCmd.class);
            cmdList.add(ListSharedFSCmd.class);
            cmdList.add(UpdateSharedFSCmd.class);
            cmdList.add(DestroySharedFSCmd.class);
            cmdList.add(RestartSharedFSCmd.class);
            cmdList.add(StartSharedFSCmd.class);
            cmdList.add(StopSharedFSCmd.class);
            cmdList.add(ChangeSharedFSDiskOfferingCmd.class);
            cmdList.add(ChangeSharedFSServiceOfferingCmd.class);
            cmdList.add(RecoverSharedFSCmd.class);
            cmdList.add(ExpungeSharedFSCmd.class);
            cmdList.add(org.apache.cloudstack.api.command.user.storage.dataservice.GetSharedFileSystemDeletionPlanCmd.class);
            cmdList.add(org.apache.cloudstack.api.command.user.storage.dataservice.GetSharedFileSystemScalingReadinessCmd.class);
            cmdList.add(org.apache.cloudstack.api.command.user.storage.dataservice.ListSharedFileSystemDeletionAuditsCmd.class);
        }
        return cmdList;
    }

    @Override
    public ListResponse<org.apache.cloudstack.api.response.StorageServiceOfferingConstraintResponse> listOfferingConstraints(Long zoneId, List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 500) throw new InvalidParameterValueException("Supply 1 to 500 compute offering IDs");
        DataCenter zone=validateAndGetZone(zoneId);
        ListResponse<org.apache.cloudstack.api.response.StorageServiceOfferingConstraintResponse> response=new ListResponse<>();
        List<org.apache.cloudstack.api.response.StorageServiceOfferingConstraintResponse> entries=getSharedFSProvider("SHAREDFSVM").getSharedFSLifeCycle().evaluateOfferings(zone,ids);
        response.setResponses(entries,entries.size());return response;
    }

    private DataCenter validateAndGetZone(Long zoneId) {
        DataCenter zone = dataCenterDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by ID: " + zoneId);
        }
        if (zone.getAllocationState() == Grouping.AllocationState.Disabled) {
            throw new PermissionDeniedException(String.format("Cannot perform this operation, zone ID: %s is currently disabled", zone.getUuid()));
        }
        if (zone.getNetworkType() == DataCenter.NetworkType.Basic ||
            zone.isSecurityGroupEnabled()) {
            throw new PermissionDeniedException("This feature is supported only on Advanced Zone without security groups");
        }
        return zone;
    }

    private void validateDiskOffering(Long diskOfferingId, Long size, Long minIops, Long maxIops, DataCenter zone) {
        Account caller = CallContext.current().getCallingAccount();
        DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
        configMgr.checkDiskOfferingAccess(caller, diskOffering, zone);

        if (!diskOffering.isCustomized() && size != null) {
            throw new InvalidParameterValueException("Size provided with a non-custom disk offering");
        }
        if ((diskOffering.isCustomizedIops() == null || diskOffering.isCustomizedIops() == false) && (minIops != null || maxIops != null)) {
            throw new InvalidParameterValueException("Iops provided with a non-custom-iops disk offering");
        }
        if ((minIops == null) != (maxIops == null)) {
            throw new InvalidParameterValueException("Either 'miniops' and 'maxiops' must both be provided or neither must be provided.");
        }
        if (minIops != null && (minIops <= 0 || maxIops <= 0)) {
            throw new InvalidParameterValueException("The 'miniops' and 'maxiops' parameters must be greater than zero.");
        }
        if (minIops != null && minIops > maxIops) {
            throw new InvalidParameterValueException("The 'miniops' parameter must be less than or equal to the 'maxiops' parameter.");
        }
    }

    private void validateInitialBackingStorage(Long diskOfferingId, Long storageId, DataCenter zone) {
        if (storageId == null) {
            throw new InvalidParameterValueException("Primary storage is required for the initial Shared FileSystem backing volume");
        }
        final DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
        final StoragePoolVO storagePool = storagePoolDao.findById(storageId);
        if (storagePool == null) {
            throw new InvalidParameterValueException("Unable to find primary storage with id " + storageId);
        }
        if (storagePool.getDataCenterId() != zone.getId()) {
            throw new InvalidParameterValueException("Selected primary storage does not belong to zone " + zone.getUuid());
        }
        if (!volumeApiService.doesStoragePoolSupportDiskOffering(storagePool, diskOffering)) {
            throw new InvalidParameterValueException("Selected primary storage does not satisfy the disk offering storage tags");
        }
    }

    protected StaticNetworkConfiguration validateStaticNetworkConfiguration(CreateSharedFSCmd cmd, NetworkVO network) {
        if (cmd.getNetworkMode() == SharedFS.NetworkMode.DHCP) {
            if (!networkModel.areServicesSupportedInNetwork(network.getId(), Network.Service.UserData)) {
                throw new InvalidParameterValueException(String.format("Network %s does not support UserData or ConfigDrive. Select STATIC network mode and provide ipcidr for this L2 SharedFS network.",
                        network.getUuid()));
            }
            return null;
        }
        if (network.getGuestType() != Network.GuestType.L2) {
            throw new InvalidParameterValueException("Static SharedFS network configuration is supported only for L2 networks");
        }
        StaticNetworkConfiguration configuration = parseStaticIpCidr(cmd.getIpCidr());
        if ((StringUtils.isNotBlank(cmd.getGateway()) && !NetUtils.isValidIp4(cmd.getGateway())) ||
                (StringUtils.isNotBlank(cmd.getDns1()) && !NetUtils.isValidIp4(cmd.getDns1())) ||
                (StringUtils.isNotBlank(cmd.getDns2()) && !NetUtils.isValidIp4(cmd.getDns2()))) {
            throw new InvalidParameterValueException("Static SharedFS network configuration must contain valid IPv4 values");
        }
        final String[] cidrParts = configuration.networkCidr.split("/");
        final int prefix = Integer.parseInt(cidrParts[1]);
        if (StringUtils.isNotBlank(cmd.getGateway()) && !NetUtils.sameSubnetCIDR(cmd.getGateway(), cidrParts[0], prefix)) {
            throw new InvalidParameterValueException("Static SharedFS IP address and gateway must belong to the selected CIDR");
        }
        final long address = NetUtils.ip2Long(configuration.ipAddress);
        final long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        final long networkAddress = NetUtils.ip2Long(cidrParts[0]);
        final long broadcastAddress = networkAddress | (~mask & 0xffffffffL);
        if (prefix <= 30 && (address == networkAddress || address == broadcastAddress)) {
            throw new InvalidParameterValueException("Static SharedFS IP address cannot be the network or broadcast address");
        }
        if (StringUtils.isNotBlank(cmd.getGateway())) {
            final long gateway = NetUtils.ip2Long(cmd.getGateway());
            if (gateway == address || gateway == networkAddress || gateway == broadcastAddress ||
                    gateway == 0 || (gateway >>> 24) == 127 || (gateway >>> 16) == 0xa9fe ||
                    (gateway >>> 28) >= 14) {
                throw new InvalidParameterValueException("Static SharedFS gateway must be a unicast router address in the selected CIDR");
            }
        }
        NicVO existingNic = nicDao.findByIp4AddressAndNetworkId(configuration.ipAddress, network.getId());
        if (existingNic != null) {
            throw new InvalidParameterValueException("Static SharedFS IP address is already allocated on the selected network");
        }
        return configuration;
    }

    protected StaticNetworkConfiguration parseStaticIpCidr(String ipCidr) {
        if (StringUtils.isBlank(ipCidr)) {
            throw new InvalidParameterValueException("ipcidr is required for static SharedFS network configuration");
        }
        final String[] parts = ipCidr.trim().split("/", -1);
        if (parts.length != 2 || !NetUtils.isValidIp4(parts[0])) {
            throw new InvalidParameterValueException("ipcidr must use IPv4/prefix format, for example 10.10.1.211/24");
        }
        final int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new InvalidParameterValueException("ipcidr prefix must be a number between 0 and 32");
        }
        if (prefix < 0 || prefix > 32) {
            throw new InvalidParameterValueException("ipcidr prefix must be a number between 0 and 32");
        }
        final long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        final String networkCidr = String.format("%s/%d", NetUtils.long2Ip(NetUtils.ip2Long(parts[0]) & mask), prefix);
        return new StaticNetworkConfiguration(parts[0], networkCidr);
    }

    protected void configureStaticNetwork(SharedFS sharedFS) {
        if (sharedFS.getNetworkMode() != SharedFS.NetworkMode.STATIC) {
            return;
        }
        if (sharedFS.getVmId() == null) {
            throw new CloudRuntimeException("Unable to configure static SharedFS network before the Storage Service VM is deployed");
        }
        List<NicVO> nics = nicDao.listByVmId(sharedFS.getVmId());
        if (nics.size() != 1) {
            throw new CloudRuntimeException("Static SharedFS network configuration requires exactly one Storage Service VM NIC");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("macAddress", nics.get(0).getMacAddress());
        payload.addProperty("ipAddress", sharedFS.getIpAddress());
        payload.addProperty("cidr", sharedFS.getCidr());
        if (StringUtils.isNotBlank(sharedFS.getGateway())) {
            payload.addProperty("gateway", sharedFS.getGateway());
        }
        if (StringUtils.isNotBlank(sharedFS.getDns1())) {
            payload.addProperty("dns1", sharedFS.getDns1());
        }
        if (StringUtils.isNotBlank(sharedFS.getDns2())) {
            payload.addProperty("dns2", sharedFS.getDns2());
        }

        String lastError = null;
        for (int attempt = 1; attempt <= STATIC_NETWORK_QGA_ATTEMPTS; attempt++) {
            StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                    sharedFS.getVmId(), CONFIGURE_SHAREDFS_STATIC_NETWORK, payload.toString(), 60, Set.of()));
            if (result.isSuccess()) {
                return;
            }
            lastError = result.getDetails();
            if (attempt < STATIC_NETWORK_QGA_ATTEMPTS) {
                try {
                    Thread.sleep(STATIC_NETWORK_QGA_RETRY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CloudRuntimeException("Interrupted while waiting for SharedFS QGA static network configuration", e);
                }
            }
        }
        throw new CloudRuntimeException("Failed to configure static SharedFS network through QGA: " + lastError);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_CREATE, eventDescription = "Allocating Shared FileSystem", create = true)
    public SharedFS allocSharedFS(CreateSharedFSCmd cmd) {
        if (!cmd.isExistingVolume()) return allocSharedFSInternal(cmd);
        if (cmd.getExistingVolumeId()==null) throw new InvalidParameterValueException("An existing volume is required for EXISTING mode");
        com.cloud.utils.db.GlobalLock lock=com.cloud.utils.db.GlobalLock.getInternLock("SharedFSInitialVolume-"+cmd.getExistingVolumeId());
        boolean held=false;
        try { held=lock.lock(30);if (!held) throw new CloudRuntimeException("Another existing-volume allocation is active");return allocSharedFSInternal(cmd); }
        finally { if (held) lock.unlock();lock.releaseRef(); }
    }

    protected VolumeVO validateExistingInitialVolume(Long id,long ownerId,long zoneId,long excludedSharedFsId) {
        VolumeVO volume=id==null ? null : volumeDao.findById(id);
        if (volume==null || volume.getVolumeType()!=Volume.Type.DATADISK || volume.getState()!=Volume.State.Ready || volume.getInstanceId()!=null
                || volume.getAccountId()!=ownerId || volume.getDataCenterId()!=zoneId || volume.getPoolId()==null || volume.getSize()==null || volume.getSize()<=0) {
            throw new InvalidParameterValueException("Existing backing volume must be a Ready unattached DATADISK in the same owner and zone");
        }
        StoragePoolVO pool=storagePoolDao.findById(volume.getPoolId());
        if (pool==null || pool.getStatus()!=com.cloud.storage.StoragePoolStatus.Up) throw new InvalidParameterValueException("Existing volume primary storage is unavailable");
        com.cloud.utils.db.SearchCriteria<SharedFSVO> reservations=sharedFSDao.createSearchCriteria();reservations.addAnd("volumeId",com.cloud.utils.db.SearchCriteria.Op.EQ,id);
        for (SharedFSVO reserved:sharedFSDao.search(reservations,null)) if (reserved.getId()!=excludedSharedFsId) throw new InvalidParameterValueException("Existing volume is reserved by another SharedFS");
        com.cloud.utils.db.SearchCriteria<StorageFileShareVO> shares=storageFileShareDao.createSearchCriteria();shares.addAnd("volumeId",com.cloud.utils.db.SearchCriteria.Op.EQ,id);
        if (!storageFileShareDao.search(shares,null).isEmpty()) throw new InvalidParameterValueException("Existing volume is used by a file service");
        com.cloud.utils.db.SearchCriteria<org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO> targets=storageBlockTargetDao.createSearchCriteria();targets.addAnd("volumeId",com.cloud.utils.db.SearchCriteria.Op.EQ,id);
        if (!storageBlockTargetDao.search(targets,null).isEmpty()) throw new InvalidParameterValueException("Existing volume is used by a block service");
        return volume;
    }

    protected SharedFS allocSharedFSInternal(CreateSharedFSCmd cmd) {
        return sharedFSDao.persist((SharedFSVO) preflightSharedFS(cmd));
    }

    protected void validateSparseNewRootOffering(Long serviceOfferingId) {
        com.cloud.service.ServiceOfferingVO service=serviceOfferingDao.findById(serviceOfferingId);
        com.cloud.storage.DiskOfferingVO root=service==null || service.getDiskOfferingId()==null ? null : diskOfferingDao.findById(service.getDiskOfferingId());
        if(root==null || !(root.getProvisioningType()==com.cloud.storage.Storage.ProvisioningType.SPARSE || root.getProvisioningType()==com.cloud.storage.Storage.ProvisioningType.FAT))throw new InvalidParameterValueException("New SharedFS/clone VM ROOT requires a SPARSE or FAT service offering; existing THIN ROOTs are preserved");
    }
    protected void validateSparseNewDataOffering(Long diskOfferingId) {
        com.cloud.storage.DiskOfferingVO offering=diskOfferingDao.findById(diskOfferingId);
        if(offering==null || !(offering.getProvisioningType()==com.cloud.storage.Storage.ProvisioningType.SPARSE || offering.getProvisioningType()==com.cloud.storage.Storage.ProvisioningType.FAT))throw new InvalidParameterValueException("New SharedFS DATA requires an explicit SPARSE or FAT disk offering");
    }
    protected void verifySparseAllocatedRoot(Long vmId) {
        List<VolumeVO> roots=volumeDao.findByInstanceAndType(vmId,Volume.Type.ROOT);
        if(roots.size()!=1 || !(roots.get(0).getProvisioningType()==com.cloud.storage.Storage.ProvisioningType.SPARSE || roots.get(0).getProvisioningType()==com.cloud.storage.Storage.ProvisioningType.FAT))throw new InvalidParameterValueException("New SharedFS ROOT allocation did not report SPARSE/FAT; storage publication is blocked");
    }

    @Override
    public SharedFS preflightSharedFS(CreateSharedFSCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();

        long ownerId = cmd.getEntityOwnerId();
        Account owner = accountMgr.getActiveAccountById(ownerId);
        accountMgr.checkAccess(caller, null, true, owner);
        DataCenter zone = validateAndGetZone(cmd.getZoneId());

        Long diskOfferingId = cmd.getDiskOfferingId();
        Long size = cmd.getSize();
        Long minIops = cmd.getMinIops();
        Long maxIops = cmd.getMaxIops();
        VolumeVO existing=null;
        if (cmd.isExistingVolume()) {
            if (diskOfferingId!=null || cmd.getStorageId()!=null || size!=null || minIops!=null || maxIops!=null) throw new InvalidParameterValueException("EXISTING mode derives offering, pool and size from the selected volume; new-volume fields must be omitted");
            existing=validateExistingInitialVolume(cmd.getExistingVolumeId(),ownerId,zone.getId(),-1);
        } else {
            if (diskOfferingId==null || cmd.getStorageId()==null) throw new InvalidParameterValueException("NEW mode requires disk offering and primary storage");
            validateDiskOffering(diskOfferingId, size, minIops, maxIops, zone);
            validateInitialBackingStorage(diskOfferingId, cmd.getStorageId(), zone);
        }

        SharedFSProvider provider = getSharedFSProvider(cmd.getSharedFSProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        validateExplicitTemplate(cmd,owner,zone);
        if(cmd.getTemplateId()==null)lifeCycle.checkPrerequisites(zone,cmd.getServiceOfferingId());else lifeCycle.checkPrerequisites(zone, cmd.getServiceOfferingId(),cmd.getTemplateId());
        validateSparseNewRootOffering(cmd.getServiceOfferingId());
        if(!cmd.isExistingVolume())validateSparseNewDataOffering(cmd.getDiskOfferingId());

        NetworkVO networkVO = networkDao.findById(cmd.getNetworkId());
        if (networkVO == null) {
            throw new InvalidParameterValueException("Unable to find a network with Network ID " + cmd.getNetworkId());
        }
        StaticNetworkConfiguration staticNetwork = validateStaticNetworkConfiguration(cmd, networkVO);
        if (networkVO.getGuestType() == Network.GuestType.Shared) {
            if ((networkVO.getAclType() != ControlledEntity.ACLType.Account) ||
                    (cmd.getDomainId() != null && (networkVO.getDomainId() != cmd.getDomainId())) ||
                    (networkVO.getAccountId() != owner.getAccountId())) {
                throw new InvalidParameterValueException("Shared network which is not Account scoped and not belonging to the same account can not be used to create a Shared FileSystem");
            }
        }

        SharedFS.FileSystemType fsType;
        if (!cmd.isExistingVolume() && StringUtils.isBlank(cmd.getFsFormat())) throw new InvalidParameterValueException("NEW mode requires an XFS or EXT4 filesystem");
        try {
            fsType = cmd.isExistingVolume() ? SharedFS.FileSystemType.XFS : SharedFS.FileSystemType.valueOf(cmd.getFsFormat().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidParameterValueException("Invalid File system format specified. Supported formats are EXT4 and XFS");
        }

        if (sharedFSDao.findSharedFSByNameAccountDomain(cmd.getName(), owner.getAccountId(), cmd.getDomainId()) != null) {
            throw new InvalidParameterValueException("There already exists a Shared FileSystem with this name for the given account and domain.");
        }

        SharedFSVO sharedFS = new SharedFSVO(cmd.getName(), cmd.getDescription(), owner.getDomainId(),
                ownerId, cmd.getZoneId(), cmd.getSharedFSProviderName(), SharedFS.Protocol.NFS,
                fsType, cmd.getServiceOfferingId());
        if (existing!=null) { sharedFS.setVolumeId(existing.getId());sharedFS.setBackingVolumeMode(SharedFS.BackingVolumeMode.EXISTING);sharedFS.setInitialImportState("RESERVED"); }
        sharedFS.setNetworkMode(cmd.getNetworkMode());
        if (cmd.getNetworkMode() == SharedFS.NetworkMode.STATIC) {
            sharedFS.setIpAddress(staticNetwork.ipAddress);
            sharedFS.setCidr(staticNetwork.networkCidr);
            sharedFS.setGateway(cmd.getGateway());
            sharedFS.setDns1(cmd.getDns1());
            sharedFS.setDns2(cmd.getDns2());
        }

        return sharedFS;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_CREATE, eventDescription = "Deploying Shared FileSystem", async = true)
    public SharedFS deploySharedFS(CreateSharedFSCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        SharedFSVO sharedFS = sharedFSDao.findById(cmd.getEntityId());
        Long diskOfferingId = cmd.getDiskOfferingId();
        Long size = cmd.getSize();
        Long minIops = cmd.getMinIops();
        Long maxIops = cmd.getMaxIops();
        SharedFSProvider provider = getSharedFSProvider(cmd.getSharedFSProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        Pair<Long, Long> result;
        try {
            if(cmd.getValidationArtifactUuid()!=null&&sharedFS.getVmId()!=null)throw new CloudRuntimeException("Private fixture already has allocated VM and DATA; reconcile that allocation before retrying creation");
            Account owner=accountMgr.getActiveAccountById(sharedFS.getAccountId());
            com.cloud.storage.VMTemplateVO explicitTemplate=cmd.getTemplateId()==null?null:validateExplicitTemplate(cmd,owner,validateAndGetZone(sharedFS.getDataCenterId()));
            if(explicitTemplate!=null&&explicitTemplate.getTemplateType()==com.cloud.storage.Storage.TemplateType.USER)new org.apache.cloudstack.storage.dataservice.StorageTemplateFixturePermit().claim(cmd.getValidationArtifactUuid(),cmd.getValidationArtifactSha256(),sharedFS.getUuid(),explicitTemplateFixtureRequest(cmd,owner,explicitTemplate));
            validateSparseNewRootOffering(sharedFS.getServiceOfferingId());
            if(!cmd.isExistingVolume())validateSparseNewDataOffering(diskOfferingId);
            if (cmd.isExistingVolume()) {
                validateExistingInitialVolume(cmd.getExistingVolumeId(),sharedFS.getAccountId(),sharedFS.getDataCenterId(),sharedFS.getId());
                result=cmd.getTemplateId()==null?lifeCycle.deployWithExistingVolume(sharedFS,cmd.getNetworkId(),cmd.getExistingVolumeId()):lifeCycle.deployWithExistingVolume(sharedFS,cmd.getNetworkId(),cmd.getExistingVolumeId(),cmd.getTemplateId());
            } else result = cmd.getTemplateId()==null?lifeCycle.deploySharedFS(sharedFS,cmd.getNetworkId(),diskOfferingId,cmd.getStorageId(),size,minIops,maxIops):lifeCycle.deploySharedFS(sharedFS, cmd.getNetworkId(), diskOfferingId, cmd.getStorageId(), size, minIops, maxIops,cmd.getTemplateId());
            sharedFS.setVolumeId(result.first());
            sharedFS.setVmId(result.second());
            if(!sharedFSDao.update(sharedFS.getId(), sharedFS))throw new CloudRuntimeException("Allocated SharedFS VM and DATA identities could not be recorded");
            verifySparseAllocatedRoot(result.second());
            completeExplicitTemplateFixture(cmd,sharedFS,explicitTemplate);
            configureStaticNetwork(sharedFSDao.findById(sharedFS.getId()));
            if (cmd.isExistingVolume()) inspectExistingInitialVolume(sharedFS);
        } catch (Exception ex) {
            if (cmd.isExistingVolume()) cleanupFailedInitialVolume(sharedFS,lifeCycle,ex);
            if (!cmd.isExistingVolume() || sharedFSDao.findById(sharedFS.getId())!=null) stateTransitTo(sharedFS, Event.OperationFailed);
            throw ex;
        }
        stateTransitTo(sharedFS, Event.OperationSucceeded);
        syncSharedFSToStorageService(sharedFSDao.findById(sharedFS.getId()));
        return sharedFS;
    }

    protected void inspectExistingInitialVolume(SharedFSVO sharedFS) {
        VolumeVO volume=volumeDao.findById(sharedFS.getVolumeId());
        if (volume==null || !sharedFS.getVmId().equals(volume.getInstanceId())) throw new CloudRuntimeException("Existing volume attachment changed before inspection");
        com.google.gson.JsonObject payload=new com.google.gson.JsonObject();
        payload.addProperty("shareUuid",sharedFS.getUuid());payload.addProperty("volumeUuid",volume.getUuid());payload.addProperty("volumeName",volume.getName());payload.addProperty("volumeSizeBytes",volume.getSize());payload.addProperty("importMode","MOUNT_EXISTING");
        StorageServiceGuestCommandResult result=guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(sharedFS.getVmId(),"volume attach inspect",payload.toString(),120,Set.of()));
        if (!result.isSuccess()) throw new CloudRuntimeException("Existing filesystem inspection failed; formatting was not permitted");
        com.google.gson.JsonObject observed=new com.google.gson.JsonParser().parse(result.getResultJson()).getAsJsonObject();
        if (!observed.has("success") || !observed.get("success").getAsBoolean() || !volume.getUuid().equals(observed.get("volumeUuid").getAsString())) throw new CloudRuntimeException("Existing filesystem identity was not verified");
        String filesystem=observed.get("filesystem").getAsString().toUpperCase(java.util.Locale.ROOT);
        sharedFS.setFsType(SharedFS.FileSystemType.valueOf(filesystem));sharedFS.setInitialImportState("MOUNTED_EXISTING");sharedFSDao.update(sharedFS.getId(),sharedFS);
    }

    protected void cleanupFailedInitialVolume(SharedFSVO sharedFS,SharedFSLifeCycle lifeCycle,Exception failure) {
        sharedFS.setInitialImportState("RECOVERY_REQUIRED");sharedFSDao.update(sharedFS.getId(),sharedFS);
        if (sharedFS.getVmId()==null) { sharedFSDao.remove(sharedFS.getId());return; }
        try {
            requireNativeLifecycleIdle(sharedFS);
            if (!lifeCycle.stopSharedFS(sharedFS,false)) throw new CloudRuntimeException("Initial VM could not be stopped for preserved-volume cleanup");
            VolumeVO observed=volumeDao.findById(sharedFS.getVolumeId());
            Set<Long> ownData=observed!=null && sharedFS.getVmId().equals(observed.getInstanceId()) ? Set.of(sharedFS.getVolumeId()) : Set.of();
            sharedFS.setDataVolumePolicy(SharedFS.DataVolumePolicy.PRESERVE_VOLUMES);
            sharedFS.setDeletionPlanJson(createDeletionPlan(sharedFS, SharedFS.DataVolumePolicy.PRESERVE_VOLUMES).toString());
            if (!sharedFSDao.update(sharedFS.getId(), sharedFS)) throw new CloudRuntimeException("Initial cleanup DATA identity plan could not be persisted");
            if (!removeSharedFSProvider(sharedFS, lifeCycle, ownData)) throw new CloudRuntimeException("Initial VM cleanup did not complete");
            sharedFSDao.remove(sharedFS.getId());
        } catch (RuntimeException recovery) { failure.addSuppressed(recovery);logger.warn("Initial existing-volume deployment requires recovery for SharedFS {}",sharedFS.getUuid()); }
    }

    private SharedFS startSharedFS(SharedFS sharedFS) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException {
        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();

        try {
            stateTransitTo(sharedFS, Event.StartRequested);
            lifeCycle.startSharedFS(sharedFS);
            configureStaticNetwork(sharedFS);
        } catch (Exception ex) {
            stateTransitTo(sharedFS, Event.OperationFailed);
            throw ex;
        }
        stateTransitTo(sharedFS, Event.OperationSucceeded);
        sharedFS = sharedFSDao.findById(sharedFS.getId());
        syncSharedFSToStorageService(sharedFS);
        return sharedFS;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_START, eventDescription = "Starting Shared FileSystem")
    public SharedFS startSharedFS(Long sharedFSId) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        requireNoRootMaintenance(sharedFS);

        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        Set<State> validStates = new HashSet<>(List.of(State.Stopped));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Shared FileSystem can be started only if it is in the " + validStates.toString() + " state");
        }
        return startSharedFS(sharedFS);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_STOP, eventDescription = "Stopping Shared FileSystem")
    public SharedFS stopSharedFS(Long sharedFSId, Boolean forced) {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        if (sharedFS == null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        requireNoRootMaintenance(sharedFS);
        requireNativeLifecycleIdle(sharedFS);
        Set<State> validStates = new HashSet<>(List.of(State.Ready));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Shared FileSystem can be stopped only if it is in the " + State.Ready + " state");
        }

        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        try {
            stateTransitTo(sharedFS, Event.StopRequested);
            if (!lifeCycle.stopSharedFS(sharedFS, forced)) throw new CloudRuntimeException("SharedFS VM stop was not confirmed");
        } catch (Exception e) {
            stateTransitTo(sharedFS, Event.OperationFailed);
            throw e;
        }
        stateTransitTo(sharedFS, Event.OperationSucceeded);
        syncSharedFSToStorageService(sharedFSDao.findById(sharedFS.getId()));
        return sharedFS;
    }

    private SharedFSVO reDeploySharedFS(SharedFSVO sharedFS) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        boolean result = lifeCycle.reDeploySharedFS(sharedFS);
        if (result) {
            configureStaticNetwork(sharedFSDao.findById(sharedFS.getId()));
        }
        return (result ? sharedFS : null);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_RESTART, eventDescription = "Restarting Shared FileSystem", async = true)
    public SharedFS restartSharedFS(Long sharedFSId, boolean cleanup) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        if (sharedFS == null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        requireNoRootMaintenance(sharedFS);
        requireNativeLifecycleIdle(sharedFS);

        Set<State> validStates = new HashSet<>(List.of(State.Ready, State.Stopped));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Restart Shared FileSystem can be done only if the shared filesystem is in " + validStates.toString() + " states");
        }

        if (!cleanup) {
            if (!sharedFS.getState().equals(State.Stopped)) {
                stopSharedFS(sharedFS.getId(), false);
            }
            return startSharedFS(sharedFS.getId());
        } else {
            return reDeploySharedFS(sharedFS);
        }
    }

    private Pair<List<Long>, Integer> searchForSharedFSIdsAndCount(ListSharedFSCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        Long id = cmd.getId();
        String name = cmd.getName();
        Long networkId = cmd.getNetworkId();
        Long diskOfferingId = cmd.getDiskOfferingId();
        Long serviceOfferingId = cmd.getServiceOfferingId();
        String keyword = cmd.getKeyword();
        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();
        Long zoneId = cmd.getZoneId();
        String accountName = cmd.getAccountName();
        Long domainId = cmd.getDomainId();
        Long projectId = cmd.getProjectId();

        Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, accountName, projectId, permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        Project.ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(SharedFSVO.class, "created", false, startIndex, pageSize);

        SearchBuilder<SharedFSVO> sharedFSSearchBuilder = sharedFSDao.createSearchBuilder();
        sharedFSSearchBuilder.select(null, SearchCriteria.Func.DISTINCT, sharedFSSearchBuilder.entity().getId()); // select distinct
        accountMgr.buildACLSearchBuilder(sharedFSSearchBuilder, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sharedFSSearchBuilder.and("id", sharedFSSearchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        sharedFSSearchBuilder.and("name", sharedFSSearchBuilder.entity().getName(), SearchCriteria.Op.EQ);
        sharedFSSearchBuilder.and("dataCenterId", sharedFSSearchBuilder.entity().getDataCenterId(), SearchCriteria.Op.EQ);

        if (keyword != null) {
            sharedFSSearchBuilder.and("keywordName", sharedFSSearchBuilder.entity().getName(), SearchCriteria.Op.LIKE);
        }

        sharedFSSearchBuilder.and("serviceOfferingId", sharedFSSearchBuilder.entity().getServiceOfferingId(), SearchCriteria.Op.EQ);

        if (diskOfferingId != null) {
            SearchBuilder<VolumeVO> volSearch = volumeDao.createSearchBuilder();
            volSearch.and("diskOfferingId", volSearch.entity().getDiskOfferingId(), SearchCriteria.Op.EQ);
            sharedFSSearchBuilder.join("volSearch", volSearch, volSearch.entity().getId(), sharedFSSearchBuilder.entity().getVolumeId(), JoinBuilder.JoinType.INNER);
        }

        if (networkId != null) {
            SearchBuilder<NicVO> nicSearch = nicDao.createSearchBuilder();
            nicSearch.and("networkId", nicSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
            sharedFSSearchBuilder.join("nicSearch", nicSearch, nicSearch.entity().getInstanceId(), sharedFSSearchBuilder.entity().getVmId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<SharedFSVO> sc = sharedFSSearchBuilder.create();
        accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (keyword != null) {
            sc.setParameters("keywordName", "%" + keyword + "%");
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        if (serviceOfferingId != null) {
            sc.setParameters("serviceOfferingId", serviceOfferingId);
        }

        if (diskOfferingId != null) {
            sc.setJoinParameters("volSearch", "diskOfferingId", diskOfferingId);
        }

        if (networkId != null) {
            sc.setJoinParameters("nicSearch", "networkId", networkId);
        }

       Pair<List<SharedFSVO>, Integer> result = sharedFSDao.searchAndCount(sc, searchFilter);
        List<Long> idsArray = result.first().stream().map(SharedFSVO::getId).collect(Collectors.toList());
        return new Pair<List<Long>, Integer>(idsArray, result.second());
    }

    private Pair<List<SharedFSJoinVO>, Integer> searchForSharedFSInternal(ListSharedFSCmd cmd) {
        Pair<List<Long>, Integer> sharedFSIds = searchForSharedFSIdsAndCount(cmd);
        if (sharedFSIds.second() == 0) {
            return new Pair<List<SharedFSJoinVO>, Integer>(null, 0);
        }

        List<SharedFSJoinVO> sharedFSs = sharedFSJoinDao.searchByIds(sharedFSIds.first().toArray(new Long[0]));
        return new Pair<List<SharedFSJoinVO>, Integer>(sharedFSs, sharedFSIds.second());
    }

    @Override
    public ListResponse<SharedFSResponse> searchForSharedFS(ResponseObject.ResponseView respView, ListSharedFSCmd cmd) {
        Pair<List<SharedFSJoinVO>, Integer> result = searchForSharedFSInternal(cmd);
        ListResponse<SharedFSResponse> response = new ListResponse<>();

        if (cmd.getRetrieveOnlyResourceCount()) {
            response.setResponses(new ArrayList<>(), result.second());
            return response;
        }

        Account caller = CallContext.current().getCallingAccount();
        if (accountMgr.isRootAdmin(caller.getId())) {
            respView = ResponseObject.ResponseView.Full;
        }

        List<SharedFSResponse> sharedFSRespons = null;
        if (result.second() > 0) {
            sharedFSRespons = sharedFSJoinDao.createSharedFSResponses(respView, result.first().toArray(new SharedFSJoinVO[result.first().size()]));
        }

        response.setResponses(sharedFSRespons, result.second());
        return response;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_UPDATE, eventDescription = "Updating Shared FileSystem")
    public SharedFS updateSharedFS(UpdateSharedFSCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getName();
        String description = cmd.getDescription();

        SharedFSVO sharedFS = sharedFSDao.findById(id);
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);

        if (name != null) {
            sharedFS.setName(name);
        }
        if (description != null) {
            sharedFS.setDescription(description);
        }

        sharedFSDao.update(sharedFS.getId(), sharedFS);
        syncSharedFSToStorageService(sharedFS);
        return sharedFS;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_CHANGE_DISK_OFFERING, eventDescription = "Change Shared FileSystem disk offering")
    public SharedFS changeSharedFSDiskOffering(ChangeSharedFSDiskOfferingCmd cmd) throws ResourceAllocationException {
        SharedFSVO sharedFS = sharedFSDao.findById(cmd.getId());
        if (sharedFS == null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        requireNoRootMaintenance(sharedFS);
        requireNativeLifecycleIdle(sharedFS);
        Set<State> validStates = new HashSet<>(List.of(State.Ready, State.Stopped));

        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Disk offering of the Shared FileSystem can be changed only if it is in " + validStates.toString() + " states");
        }

        Long diskOfferingId = cmd.getDiskOfferingId();
        Long newSize = cmd.getSize();
        Long newMinIops = cmd.getMinIops();
        Long newMaxIops = cmd.getMaxIops();
        DataCenter zone = validateAndGetZone(sharedFS.getDataCenterId());
        validateDiskOffering(diskOfferingId, newSize, newMinIops, newMaxIops, zone);
        volumeApiService.changeDiskOfferingForVolumeInternal(sharedFS.getVolumeId(), diskOfferingId, newSize, newMinIops, newMaxIops, true, false);
        syncSharedFSToStorageService(sharedFS);
        return sharedFS;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_CHANGE_SERVICE_OFFERING, eventDescription = "Change Shared FileSystem service offering")
    public SharedFS changeSharedFSServiceOffering(ChangeSharedFSServiceOfferingCmd cmd) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ManagementServerException, VirtualMachineMigrationException {
        SharedFSVO sharedFS = sharedFSDao.findById(cmd.getId());
        if (sharedFS == null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        requireNoRootMaintenance(sharedFS);
        requireNativeLifecycleIdle(sharedFS);
        if (sharedFS.getState()==State.Ready) {
            final SharedFSVO running=sharedFS;
            return withSharedFSWriterLock(running, () -> scaleSharedFSOnline(running,cmd.getServiceOfferingId()));
        }
        Set<State> validStates = new HashSet<>(List.of(State.Stopped));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Service offering of the Shared FileSystem can be changed only if it is in " + validStates.toString() + " state");
        }

        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        DataCenter zone = validateAndGetZone(sharedFS.getDataCenterId());
        lifeCycle.checkPrerequisites(zone, cmd.getServiceOfferingId());
        validateScaleOfferings(sharedFS.getServiceOfferingId(),cmd.getServiceOfferingId(),false);

        sharedFS = sharedFSDao.findById(cmd.getId());

        if (lifeCycle.changeSharedFSServiceOffering(sharedFS, cmd.getServiceOfferingId())) {
            sharedFS.setServiceOfferingId(cmd.getServiceOfferingId());
            sharedFSDao.update(sharedFS.getId(), sharedFS);
            syncSharedFSToStorageService(sharedFS);
            return sharedFS;
        } else {
            return null;
        }
    }

    @Inject
    org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao storageOperationDao;

    protected com.google.gson.JsonObject scalingGuestCommand(long vmId,String command,String payload) {
        StorageServiceGuestCommandResult result=guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(vmId,command,payload,30,Set.of()));
        if (!result.isSuccess()) throw new CloudRuntimeException("Storage Service scaling guest command failed");
        com.google.gson.JsonObject json=new com.google.gson.JsonParser().parse(result.getResultJson()).getAsJsonObject();
        if (!json.has("success") || !json.get("success").getAsBoolean()) throw new CloudRuntimeException("Storage Service scaling guest evidence is unavailable");
        return json;
    }

    protected void validateScaleOfferings(Long beforeId,Long targetId,boolean rejectNoOp) {
        com.cloud.service.ServiceOfferingVO before=serviceOfferingDao.findByIdIncludingRemoved(beforeId);
        com.cloud.service.ServiceOfferingVO target=serviceOfferingDao.findById(targetId);
        if (before==null || target==null || before.getCpu()==null || before.getRamSize()==null || before.getSpeed()==null
                || target.getCpu()==null || target.getRamSize()==null || target.getSpeed()==null) throw new InvalidParameterValueException("Fixed CPU, memory and CPU speed are required for SharedFS scaling");
        if (target.getCpu()<before.getCpu() || target.getRamSize()<before.getRamSize() || target.getSpeed()<before.getSpeed()) throw new InvalidParameterValueException("SharedFS scale-down is not supported");
        if (rejectNoOp) SharedFSOnlineScale.validate(before.getCpu(),before.getRamSize(),target.getCpu(),target.getRamSize());
    }

    protected void reconcilePreviousScaleRecovery(SharedFSVO sharedFS,StorageServiceInstanceVO instance) {
        for (org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO previous:storageOperationDao.listByInstance(instance.getId())) {
            if (!"SHAREDFS_ONLINE_SCALE".equals(previous.getAction()) || !"RECOVERY_REQUIRED".equals(previous.getState())) continue;
            com.google.gson.JsonObject original=new com.google.gson.JsonParser().parse(previous.getPreviousSnapshotJson()).getAsJsonObject();
            com.google.gson.JsonObject observed=scalingGuestCommand(sharedFS.getVmId(),"operation resources","{}");
            com.google.gson.JsonObject health=scalingGuestCommand(sharedFS.getVmId(),"operation verify","{}");
            com.cloud.vm.VMInstanceVO vm=vmInstanceDao.findById(sharedFS.getVmId());
            com.cloud.service.ServiceOfferingVO offering=serviceOfferingDao.findById(sharedFS.getServiceOfferingId());
            if (!"ok".equalsIgnoreCase(health.get("status").getAsString()) || vm==null || !sharedFS.getServiceOfferingId().equals(vm.getServiceOfferingId())
                    || offering==null || offering.getCpu()!=original.get("onlineCpuCount").getAsInt()
                    || (original.has("serviceOfferingId") && !sharedFS.getServiceOfferingId().equals(original.get("serviceOfferingId").getAsLong()))
                    || Math.abs(offering.getRamSize()*1024L*1024-original.get("memoryTotalBytes").getAsLong())>512L*1024*1024
                    || !SharedFSOnlineScale.originalResourcesMatch(original,observed)) throw new CloudRuntimeException("Previous online scaling recovery must be verified before another resize");
            org.apache.cloudstack.storage.dataservice.StorageService control = managedStorageService.get();
            control.resumeManagedOperationControl(previous.getUuid());control.verifyManagedOperationControl(previous.getUuid());control.finishManagedOperationControl(previous.getUuid(),"ROLLED_BACK");
            previous.setState("ROLLED_BACK");previous.setPhase("ROLLED_BACK");previous.setCompleted(new java.util.Date());previous.setHeartbeat(new java.util.Date());
            previous.setResultJson(observed.toString());previous.setDiagnostic("Original resources and protocol health verified after guest boot completed");storageOperationDao.update(previous.getId(),previous);
        }
    }

    protected SharedFS scaleSharedFSOnline(SharedFSVO sharedFS,Long targetId) {
        com.cloud.vm.VMInstanceVO vm=vmInstanceDao.findById(sharedFS.getVmId());
        if (vm==null || !vm.isDynamicallyScalable() || vm.getState()!=com.cloud.vm.VirtualMachine.State.Running) throw new InvalidParameterValueException("This legacy service VM requires controlled dynamic-scaling preparation before online changes");
        com.cloud.service.ServiceOfferingVO before=serviceOfferingDao.findByIdIncludingRemoved(sharedFS.getServiceOfferingId());
        com.cloud.service.ServiceOfferingVO target=serviceOfferingDao.findById(targetId);
        validateScaleOfferings(sharedFS.getServiceOfferingId(),targetId,true);
        SharedFSLifeCycle life=getSharedFSProvider(sharedFS.getFsProviderName()).getSharedFSLifeCycle();
        life.checkPrerequisites(validateAndGetZone(sharedFS.getDataCenterId()),targetId);
        StorageServiceInstanceVO instance=storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
        if (instance==null) throw new CloudRuntimeException("Storage Service operation scope is unavailable");
        reconcilePreviousScaleRecovery(sharedFS,instance);
        org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO operation=new org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO();
        operation.setInstanceId(instance.getId());operation.setAction("SHAREDFS_ONLINE_SCALE");operation.setRequestKey(java.util.UUID.randomUUID().toString());
        operation.setRevision(storageOperationDao.listByInstance(instance.getId()).stream().filter(row->"COMPLETE".equals(row.getState())).mapToLong(org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO::getRevision).max().orElse(0)+1);
        operation.setCreatedBy(CallContext.current().getCallingUserId());operation.setState("RUNNING");operation.setPhase("PREFLIGHT");
        final org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO journal=storageOperationDao.persist(operation);
        final Long originalId=sharedFS.getServiceOfferingId();
        org.apache.cloudstack.storage.dataservice.StorageService control = managedStorageService.get();
        try {
            JsonObject resourceScope = new JsonObject();resourceScope.addProperty("vmId",vm.getId());resourceScope.addProperty("sharedFsUuid",sharedFS.getUuid());
            resourceScope.addProperty("sourceOfferingId",originalId);resourceScope.addProperty("targetOfferingId",targetId);
            resourceScope.addProperty("sourceCpuCount",before.getCpu());resourceScope.addProperty("sourceMemoryMiB",before.getRamSize());resourceScope.addProperty("targetCpuCount",target.getCpu());resourceScope.addProperty("targetMemoryMiB",target.getRamSize());
            control.beginManagedOperationControl(journal.getUuid(),resourceScope.toString(),0);
            com.google.gson.JsonObject verified=SharedFSOnlineScale.execute(new SharedFSOnlineScale.Runtime() {
                public void health() { com.google.gson.JsonObject health=scalingGuestCommand(vm.getId(),"operation verify","{}");if (!"ok".equalsIgnoreCase(health.get("status").getAsString())) throw new CloudRuntimeException("Storage Service health checkpoint failed"); }
                public com.google.gson.JsonObject resources() {
                    com.google.gson.JsonObject observed=scalingGuestCommand(vm.getId(),"operation resources","{}");
                    journal.setResultJson(observed.toString());journal.setHeartbeat(new java.util.Date());storageOperationDao.update(journal.getId(),journal);return observed;
                }
                public void activate(int cpus) { control.verifyManagedOperationControl(journal.getUuid());scalingGuestCommand(vm.getId(),"operation activate-scale","{\"targetCpuCount\":"+cpus+"}"); }
                public void prepare(int cpus) {
                    control.verifyManagedOperationControl(journal.getUuid());
                    com.google.gson.JsonObject resource=resources();
                    resource.addProperty("serviceOfferingId",originalId);resource.addProperty("configuredMemoryMiB",before.getRamSize());resource.addProperty("configuredCpuCount",before.getCpu());resource.addProperty("cpuSpeed",before.getSpeed());
                    journal.setPreviousSnapshotJson(resource.toString());storageOperationDao.update(journal.getId(),journal);
                    scalingGuestCommand(vm.getId(),"operation prepare-scale","{\"targetCpuCount\":"+cpus+"}");
                }
                public void resize() {
                    control.verifyManagedOperationControl(journal.getUuid());
                    try { if (!life.changeSharedFSServiceOffering(sharedFS,targetId)) throw new CloudRuntimeException("Online offering change was not completed"); }
                    catch (Exception failure) { throw new CloudRuntimeException("Online offering change failed",failure); }
                    sharedFS.setServiceOfferingId(targetId);sharedFSDao.update(sharedFS.getId(),sharedFS);
                }
                public void restore() {
                    try {
                        control.suspendManagedOperationControl(journal.getUuid());approvedScaleRecovery.set(journal.getUuid());
                        SharedFSVO current=sharedFSDao.findById(sharedFS.getId());
                        if (current.getState()==State.Ready) stopSharedFS(current.getId(),false);
                        current=sharedFSDao.findById(current.getId());
                        com.cloud.vm.VMInstanceVO observed=vmInstanceDao.findById(current.getVmId());
                        if (observed==null || observed.getState()!=com.cloud.vm.VirtualMachine.State.Stopped) throw new CloudRuntimeException("Guest did not stop; recovery hardware change is blocked");
                        if (!life.changeSharedFSServiceOffering(current,originalId)) throw new CloudRuntimeException("Previous offering could not be restored");
                        current.setServiceOfferingId(originalId);sharedFSDao.update(current.getId(),current);startSharedFS(current.getId());
                        syncSharedFSToStorageService(sharedFSDao.findById(current.getId()));control.resumeManagedOperationControl(journal.getUuid());
                    } catch (Exception failure) { throw new CloudRuntimeException("Cold recovery of the original resources failed",failure); }
                    finally {approvedScaleRecovery.remove();}
                }
                public void pause() { journal.setHeartbeat(new java.util.Date());storageOperationDao.update(journal.getId(),journal);try { Thread.sleep(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new CloudRuntimeException("Scaling verification interrupted",interrupted); } }
                public void phase(String value) {
                    journal.setPhase(value);journal.setHeartbeat(new java.util.Date());
                    if (List.of("COMPLETE","ROLLED_BACK","RECOVERY_REQUIRED").contains(value)) { control.finishManagedOperationControl(journal.getUuid(),value);journal.setState(value);journal.setProgress(100);journal.setCompleted(new java.util.Date()); }
                    else journal.setProgress("VERIFYING".equals(value) ? 80 : "RESIZING".equals(value) ? 40 : 10);
                    storageOperationDao.update(journal.getId(),journal);
                }
            },target.getCpu(),(target.getRamSize()-before.getRamSize())*1024L*1024L);
            journal.setResultJson(verified.toString());storageOperationDao.update(journal.getId(),journal);
            syncSharedFSToStorageService(sharedFS);return sharedFS;
        } catch (RuntimeException failure) {
            if ("RUNNING".equals(journal.getState())) { journal.setState("BLOCKED");journal.setPhase("BLOCKED");journal.setCompleted(new java.util.Date()); }
            String diagnostic=failure.getMessage();
            if (failure.getSuppressed().length>0) diagnostic+="; recovery: "+failure.getSuppressed()[0].getMessage();
            journal.setDiagnostic(diagnostic);storageOperationDao.update(journal.getId(),journal);
            if ("BLOCKED".equals(journal.getState())) {
                try {control.finishManagedOperationControl(journal.getUuid(),"BLOCKED");}
                catch (RuntimeException cleanup) {failure.addSuppressed(cleanup);}
            }
            throw failure;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_DESTROY, eventDescription = "Destroy Shared FileSystem")
    public Boolean destroySharedFS(DestroySharedFSCmd cmd) {
        SharedFSVO sharedFS=sharedFSDao.findById(cmd.getId());
        if (sharedFS==null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, sharedFS);
        return withSharedFSWriterLock(sharedFS, () -> destroySharedFSInternal(cmd));
    }

    protected Boolean destroySharedFSInternal(DestroySharedFSCmd cmd) {
        Long sharedFSId = cmd.getId();
        Boolean expunge = cmd.isExpunge();
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);

        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);

        if (sharedFS.getState().equals(State.Ready) && cmd.isForced()) {
            // Reject a stale/destructive confirmation before introducing downtime.
            SharedFS.DataVolumePolicy requested=deletionPolicy(cmd.getDataVolumePolicy());
            validateDeletionConfirmation(sharedFS,requested,cmd.getConfirmDataLoss(),cmd.getExpectedPlanHash(),createDeletionPlan(sharedFS,requested));
            stopSharedFS(sharedFS.getId(), false);
        }

        sharedFS = sharedFSDao.findById(sharedFSId);
        Set<State> validStates = new HashSet<>(List.of(State.Stopped, State.Error));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Shared FileSystem can be destroyed only if it is in the " + validStates.toString() + " states");
        }

        prepareSharedFSDeletion(sharedFS, cmd.getDataVolumePolicy(), cmd.getConfirmDataLoss(), cmd.getExpectedPlanHash());
        stateTransitTo(sharedFS, Event.DestroyRequested);
        syncSharedFSToStorageService(sharedFSDao.findById(sharedFSId));
        if (expunge || sharedFS.getState().equals(State.Error)) {
            deleteSharedFSInternal(sharedFSId);
        }
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_RECOVER, eventDescription = "Recover Shared FileSystem")
    public SharedFS recoverSharedFS(Long sharedFSId) {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        if (!State.Destroyed.equals(sharedFS.getState())) {
            throw new InvalidParameterValueException("The Shared FileSystem should be in the Destroyed state to be recovered");
        }
        sharedFS.setDataVolumePolicy(SharedFS.DataVolumePolicy.PRESERVE_VOLUMES);
        sharedFS.setDeletionPlanJson(null);
        sharedFSDao.update(sharedFS.getId(), sharedFS);
        stateTransitTo(sharedFS, Event.RecoveryRequested);
        sharedFS = sharedFSDao.findById(sharedFSId);
        syncSharedFSToStorageService(sharedFS);
        return sharedFS;
    }

    @Override
    public void deleteSharedFS(Long id, String policy, String confirmation, String expectedPlanHash) {
        SharedFSVO sharedFS = sharedFSDao.findById(id);
        if (sharedFS == null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, sharedFS);
        withSharedFSWriterLock(sharedFS, () -> {
            prepareSharedFSDeletion(sharedFS, policy, confirmation, expectedPlanHash);
            deleteSharedFSInternal(id);return null;
        });
    }

    @Override
    public org.apache.cloudstack.api.response.StorageServiceDeletionPlanResponse previewSharedFSDeletion(Long id, String requestedPolicy) {
        SharedFSVO sharedFS = sharedFSDao.findById(id);
        if (sharedFS == null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, sharedFS);
        SharedFS.DataVolumePolicy policy = deletionPolicy(requestedPolicy);
        com.google.gson.JsonObject plan = createDeletionPlan(sharedFS, policy);
        org.apache.cloudstack.api.response.StorageServiceDeletionPlanResponse response = new org.apache.cloudstack.api.response.StorageServiceDeletionPlanResponse();
        response.setId(sharedFS.getUuid()); response.setPolicy(policy.name()); response.setPlanHash(plan.get("planHash").getAsString());
        response.setPlan(plan.toString()); response.setObjectName("sharedfilesystemdeletionplan");return response;
    }

    @Override
    public ListResponse<org.apache.cloudstack.api.response.SharedFSDeletionAuditResponse> listSharedFSDeletionAudits(String uuid) {
        Account caller=CallContext.current().getCallingAccount();
        boolean root=accountMgr.isRootAdmin(caller.getId());
        String query="SELECT id,sharedfs_uuid,phase,policy,plan_json,created FROM cloud.storage_service_deletion_audit WHERE 1=1";
        if (!root) query+=" AND account_id=?";
        if (uuid!=null) query+=" AND sharedfs_uuid=?";
        query+=" ORDER BY id DESC LIMIT 100";
        List<org.apache.cloudstack.api.response.SharedFSDeletionAuditResponse> entries=new ArrayList<>();
        try (java.sql.PreparedStatement statement=com.cloud.utils.db.TransactionLegacy.currentTxn().prepareAutoCloseStatement(query)) {
            int parameter=1;if (!root) statement.setLong(parameter++,caller.getId());if (uuid!=null) statement.setString(parameter,uuid);
            try (java.sql.ResultSet rows=statement.executeQuery()) {
                while (rows.next()) {
                    org.apache.cloudstack.api.response.SharedFSDeletionAuditResponse entry=new org.apache.cloudstack.api.response.SharedFSDeletionAuditResponse();
                    entry.setId(rows.getLong(1));entry.setUuid(rows.getString(2));entry.setPhase(rows.getString(3));entry.setPolicy(rows.getString(4));entry.setPlan(rows.getString(5));entry.setCreated(rows.getTimestamp(6));
                    entry.setObjectName("sharedfilesystemdeletionaudit");entries.add(entry);
                }
            }
        } catch (java.sql.SQLException e) { throw new CloudRuntimeException("Unable to read removal audit entries",e); }
        ListResponse<org.apache.cloudstack.api.response.SharedFSDeletionAuditResponse> response=new ListResponse<>();response.setResponses(entries,entries.size());return response;
    }

    protected SharedFS.DataVolumePolicy deletionPolicy(String value) {
        try { return value == null ? SharedFS.DataVolumePolicy.PRESERVE_VOLUMES : SharedFS.DataVolumePolicy.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new InvalidParameterValueException("Data volume policy must be PRESERVE_VOLUMES or DELETE_VOLUMES"); }
    }

    protected java.util.Set<Long> deletionVolumeIds(SharedFS sharedFS) {
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        if (sharedFS.getVolumeId()!=null) ids.add(sharedFS.getVolumeId());
        if (sharedFS.getVmId()!=null) {
            for (VolumeVO volume : volumeDao.findByInstanceAndType(sharedFS.getVmId(), com.cloud.storage.Volume.Type.DATADISK)) ids.add(volume.getId());
            StorageServiceInstanceVO instance=storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
            if (instance!=null) {
                for (StorageServiceInstance.Protocol protocol : List.of(StorageServiceInstance.Protocol.NFS,StorageServiceInstance.Protocol.SMB)) {
                    for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(),protocol)) if (share.getVolumeId()!=null) ids.add(share.getVolumeId());
                }
                for (StorageServiceInstance.Protocol protocol : List.of(StorageServiceInstance.Protocol.ISCSI,StorageServiceInstance.Protocol.NVME_OF)) {
                    for (org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(),protocol)) if (target.getVolumeId()!=null) ids.add(target.getVolumeId());
                }
            }
        }
        return ids;
    }

    protected com.google.gson.JsonObject deletionScope(SharedFS sharedFS, SharedFS.DataVolumePolicy policy) {
        com.google.gson.JsonObject scope = new com.google.gson.JsonObject();
        scope.addProperty("sharedfsId", sharedFS.getId());scope.addProperty("sharedfsUuid", sharedFS.getUuid());scope.addProperty("vmId", sharedFS.getVmId());scope.addProperty("policy", policy.name());
        scope.addProperty("accountId", sharedFS.getAccountId());scope.addProperty("domainId", sharedFS.getDomainId());scope.addProperty("zoneId", sharedFS.getDataCenterId());
        return scope;
    }

    protected com.google.gson.JsonObject deletionVolumeIdentity(VolumeVO volume) {
        com.google.gson.JsonObject row = new com.google.gson.JsonObject();
        row.addProperty("id", volume.getId());row.addProperty("uuid", volume.getUuid());row.addProperty("type", volume.getVolumeType().name());
        row.addProperty("accountId", volume.getAccountId());row.addProperty("domainId", volume.getDomainId());row.addProperty("zoneId", volume.getDataCenterId());
        row.addProperty("poolId", volume.getPoolId());row.addProperty("sizeBytes", volume.getSize());row.addProperty("attachedVmId", volume.getInstanceId());
        row.addProperty("name", volume.getName());row.addProperty("state", volume.getState() == null ? null : volume.getState().name());
        return row;
    }

    protected com.google.gson.JsonObject createDeletionPlan(SharedFS sharedFS, SharedFS.DataVolumePolicy policy) {
        com.google.gson.JsonArray volumes = new com.google.gson.JsonArray();
        for (Long id : deletionVolumeIds(sharedFS)) {
            VolumeVO volume = volumeDao.findById(id);
            if (volume == null) throw new InvalidParameterValueException("Deletion DATA inventory is unavailable; a missing row cannot be treated as approved deletion");
            com.google.gson.JsonObject row = deletionVolumeIdentity(volume);
            row.addProperty("action", policy == SharedFS.DataVolumePolicy.PRESERVE_VOLUMES ? "DETACH_AND_PRESERVE" : "DETACH_AND_DELETE");
            volumes.add(row);
        }
        return StorageSharedFsDeletionIdentity.freeze(deletionScope(sharedFS, policy), volumes);
    }

    protected void validateDeletionConfirmation(SharedFS sharedFS, SharedFS.DataVolumePolicy policy, String confirmation,
            String expectedHash, com.google.gson.JsonObject plan) {
        if (policy==SharedFS.DataVolumePolicy.DELETE_VOLUMES && (!sharedFS.getName().equals(confirmation) || expectedHash==null || !expectedHash.equals(plan.get("planHash").getAsString()))) {
            throw new InvalidParameterValueException("Deleting data volumes requires the exact service name and current preview plan hash");
        }
    }

    protected void prepareSharedFSDeletion(SharedFSVO sharedFS, String requestedPolicy, String confirmation, String expectedHash) {
        SharedFS.DataVolumePolicy policy=deletionPolicy(requestedPolicy);
        com.google.gson.JsonObject plan=createDeletionPlan(sharedFS,policy);
        validateDeletionConfirmation(sharedFS,policy,confirmation,expectedHash,plan);
        sharedFS.setDataVolumePolicy(policy);sharedFS.setDeletionPlanJson(plan.toString());sharedFSDao.update(sharedFS.getId(),sharedFS);
        auditSharedFSDeletion(sharedFS,"PLANNED");
    }

    protected java.util.Set<Long> storedDeletionVolumeIds(SharedFS sharedFS) {
        if (sharedFS.getDeletionPlanJson() == null) throw new CloudRuntimeException("Destructive cleanup requires a freshly reviewed DATA identity plan");
        com.google.gson.JsonObject plan = new com.google.gson.JsonParser().parse(sharedFS.getDeletionPlanJson()).getAsJsonObject();
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        for (com.google.gson.JsonElement value : plan.getAsJsonArray("volumes")) ids.add(value.getAsJsonObject().get("id").getAsLong());
        java.util.Set<Long> current = deletionVolumeIds(sharedFS);
        if (!ids.containsAll(current)) throw new CloudRuntimeException("New DATA appeared after deletion approval; refresh the identity plan");
        com.google.gson.JsonArray observed = new com.google.gson.JsonArray();
        for (Long id : ids) {
            VolumeVO volume = volumeDao.findByIdIncludingRemoved(id);
            if (volume == null) throw new CloudRuntimeException("Approved DATA row disappeared without a retained deletion receipt");
            observed.add(deletionVolumeIdentity(volume));
        }
        return StorageSharedFsDeletionIdentity.requireCurrent(plan, deletionScope(sharedFS, sharedFS.getDataVolumePolicy()), observed);
    }

    protected void auditSharedFSDeletion(SharedFS sharedFS,String phase) {
        try (java.sql.PreparedStatement statement=com.cloud.utils.db.TransactionLegacy.currentTxn().prepareAutoCloseStatement("INSERT INTO cloud.storage_service_deletion_audit(sharedfs_id,sharedfs_uuid,account_id,actor_id,policy,phase,plan_json,created) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP())")) {
            statement.setLong(1,sharedFS.getId());statement.setString(2,sharedFS.getUuid());statement.setLong(3,sharedFS.getAccountId());
            statement.setLong(4,CallContext.current().getCallingUserId());statement.setString(5,sharedFS.getDataVolumePolicy().name());statement.setString(6,phase);
            statement.setString(7,sharedFS.getDeletionPlanJson()==null ? createDeletionPlan(sharedFS,sharedFS.getDataVolumePolicy()).toString() : sharedFS.getDeletionPlanJson());statement.executeUpdate();
        } catch (java.sql.SQLException e) { throw new CloudRuntimeException("Unable to persist the data-volume retention audit",e); }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_EXPUNGE, eventDescription = "Expunge Shared FileSystem")
    public void deleteSharedFS(Long sharedFSId) {
        SharedFSVO sharedFS=sharedFSDao.findById(sharedFSId);
        if (sharedFS==null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, sharedFS);
        withSharedFSWriterLock(sharedFS, () -> { deleteSharedFSInternal(sharedFSId);return null; });
    }

    protected boolean completedDeletionReceipt(SharedFS sharedFS) {
        if (sharedFS.getDeletionPlanJson() == null) return false;
        JsonObject plan = com.google.gson.JsonParser.parseString(sharedFS.getDeletionPlanJson()).getAsJsonObject();
        if (!plan.has("executionReceipt") || !plan.get("executionReceipt").isJsonObject()) return false;
        JsonObject receipt = plan.getAsJsonObject("executionReceipt");
        return receipt.has("planHash") && receipt.get("planHash").equals(plan.get("planHash"))
                && receipt.has("providerCompleted") && receipt.get("providerCompleted").isJsonPrimitive()
                && receipt.get("providerCompleted").getAsJsonPrimitive().isBoolean() && receipt.get("providerCompleted").getAsBoolean();
    }

    protected void deleteSharedFSInternal(Long sharedFSId) {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        if (sharedFS == null) throw new InvalidParameterValueException("Shared filesystem is unavailable");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, false, sharedFS);
        boolean completed = completedDeletionReceipt(sharedFS);
        Set<State> validStates = new HashSet<>(List.of(State.Destroyed, State.Expunging, State.Error));
        if (!validStates.contains(sharedFS.getState()) && !(completed && sharedFS.getState() == State.Expunged)) {
            throw new InvalidParameterValueException("Shared FileSystem can be expunged only in Destroyed, Expunging or Error, or retried with its completed execution receipt");
        }
        if (sharedFS.getDeletionPlanJson() == null) {
            sharedFS.setDeletionPlanJson(createDeletionPlan(sharedFS, sharedFS.getDataVolumePolicy()).toString());
            if (!sharedFSDao.update(sharedFS.getId(), sharedFS)) throw new CloudRuntimeException("Removal identity plan could not be durably saved");
        }
        Set<Long> ids = storedDeletionVolumeIds(sharedFS);
        if (!completed) {
            SharedFSLifeCycle lifeCycle = getSharedFSProvider(sharedFS.getFsProviderName()).getSharedFSLifeCycle();
            stateTransitTo(sharedFS, Event.ExpungeOperation);
            auditSharedFSDeletion(sharedFS, "STARTED");
            try {
                if (!removeSharedFSProvider(sharedFS, lifeCycle, ids)) throw new CloudRuntimeException("Provider did not complete service removal");
            } catch (RuntimeException failure) {
                try { auditSharedFSDeletion(sharedFS, "FAILED_RETRYABLE"); } catch (RuntimeException auditFailure) { failure.addSuppressed(auditFailure); }
                throw failure;
            }
            auditSharedFSDeletion(sharedFS, "COMPLETE");
            JsonObject plan = com.google.gson.JsonParser.parseString(sharedFS.getDeletionPlanJson()).getAsJsonObject();
            JsonObject receipt = new JsonObject();receipt.add("planHash", plan.get("planHash"));
            receipt.addProperty("providerCompleted", true);receipt.addProperty("completedAtMillis", System.currentTimeMillis());
            plan.add("executionReceipt", receipt);sharedFS.setDeletionPlanJson(plan.toString());
            if (!sharedFSDao.update(sharedFS.getId(), sharedFS)) throw new CloudRuntimeException("Completed removal receipt could not be durably saved");
        }
        deleteStorageServiceCompatibility(sharedFS);
        if (sharedFS.getState() != State.Expunged) stateTransitTo(sharedFS, Event.OperationSucceeded);
        if (!sharedFSDao.remove(sharedFS.getId())) throw new CloudRuntimeException("Service row removal is incomplete; retry the same frozen plan without repeating provider effects");
    }

    protected void syncSharedFSToStorageService(SharedFS sharedFS) {
        if (sharedFS == null || !SharedFSFeatureEnabled.value()) {
            return;
        }
        if (sharedFS.getVmId() == null || sharedFS.getVolumeId() == null) {
            logger.debug("Skipping Storage Service compatibility sync for SharedFS [{}] without VM or volume binding", sharedFS);
            return;
        }

        try {
            StorageServiceInstanceVO instance = storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
            if (instance == null) {
                instance = new StorageServiceInstanceVO(sharedFS.getName(), sharedFS.getDescription(), sharedFS.getDomainId(),
                        sharedFS.getAccountId(), sharedFS.getDataCenterId(), sharedFS.getServiceOfferingId(), SHAREDFS_COMPAT_PROVIDER);
                instance.setVmId(sharedFS.getVmId());
                instance.setState(toStorageServiceState(sharedFS.getState()));
                instance = storageServiceInstanceDao.persist(instance);
            } else {
                instance.setName(sharedFS.getName());
                instance.setDescription(sharedFS.getDescription());
                instance.setServiceOfferingId(sharedFS.getServiceOfferingId());
                instance.setProvider(SHAREDFS_COMPAT_PROVIDER);
                instance.setState(toStorageServiceState(sharedFS.getState()));
                storageServiceInstanceDao.update(instance.getId(), instance);
            }

            StorageServiceProtocolVO protocol = null;
            for (StorageServiceProtocolVO candidate : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
                if (candidate.getPort() == null || candidate.getPort() == 2049) {
                    protocol = candidate;
                    break;
                }
                if (protocol == null) {
                    protocol = candidate;
                }
            }
            if (protocol == null) {
                protocol = new StorageServiceProtocolVO(instance.getId(), StorageServiceInstance.Protocol.NFS, isSharedFSReady(sharedFS.getState()), null, 2049);
                protocol.setState(toStorageResourceState(sharedFS.getState()));
                storageServiceProtocolDao.persist(protocol);
            } else {
                protocol.setEnabled(isSharedFSReady(sharedFS.getState()));
                protocol.setPort(protocol.getPort() == null ? 2049 : protocol.getPort());
                protocol.setState(toStorageResourceState(sharedFS.getState()));
                storageServiceProtocolDao.update(protocol.getId(), protocol);
            }

            removeLegacySharedFSRootExport(instance);
        } catch (RuntimeException e) {
            logger.warn("Unable to sync SharedFS [{}] to Storage Service compatibility model. Existing SharedFS API behavior is preserved.",
                    sharedFS, e);
        }
    }

    protected void reconcileSharedFSToStorageService() {
        if (!SharedFSFeatureEnabled.value()) {
            return;
        }
        for (SharedFSVO sharedFS : sharedFSDao.listAll()) {
            if (!shouldReconcileSharedFSToStorageService(sharedFS)) {
                continue;
            }
            syncSharedFSToStorageService(sharedFS);
        }
    }

    protected boolean shouldReconcileSharedFSToStorageService(SharedFS sharedFS) {
        return sharedFS != null && sharedFS.getVmId() != null && sharedFS.getVolumeId() != null &&
                !State.Destroyed.equals(sharedFS.getState()) && !State.Expunging.equals(sharedFS.getState()) &&
                !State.Expunged.equals(sharedFS.getState());
    }

    protected void removeLegacySharedFSRootExport(StorageServiceInstanceVO instance) {
        for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            if (SharedFS.SharedFSPath.equals(StringUtils.removeEnd(share.getPath(), "/"))) {
                for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                    storageAccessRuleDao.remove(rule.getId());
                }
                storageFileShareDao.remove(share.getId());
            }
        }
    }

    protected void deleteStorageServiceCompatibility(SharedFS sharedFS) {
        if (sharedFS == null || sharedFS.getVmId() == null || !SharedFSFeatureEnabled.value()) return;
        StorageServiceInstanceVO instance=storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
        if (instance == null) return;
        for (StorageServiceInstance.Protocol protocol : List.of(StorageServiceInstance.Protocol.NFS,StorageServiceInstance.Protocol.SMB)) {
            for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(),protocol)) {
                for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,share.getId())) storageAccessRuleDao.remove(rule.getId());
                storageFileShareDao.remove(share.getId());
            }
        }
        for (StorageServiceInstance.Protocol protocol : List.of(StorageServiceInstance.Protocol.ISCSI,StorageServiceInstance.Protocol.NVME_OF)) {
            for (org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(),protocol)) {
                for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET,target.getId())) storageAccessRuleDao.remove(rule.getId());
                storageBlockTargetDao.remove(target.getId());
            }
        }
        for (StorageServiceInstance.Protocol protocol : StorageServiceInstance.Protocol.values()) {
            for (StorageServiceProtocolVO listener : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(),protocol)) storageServiceProtocolDao.remove(listener.getId());
        }
        storageServiceInstanceDao.remove(instance.getId());
    }

    protected StorageServiceInstance.State toStorageServiceState(State sharedFSState) {
        if (State.Ready.equals(sharedFSState)) {
            return StorageServiceInstance.State.Running;
        }
        if (State.Stopped.equals(sharedFSState)) {
            return StorageServiceInstance.State.Stopped;
        }
        if (State.Destroyed.equals(sharedFSState) || State.Expunged.equals(sharedFSState)) {
            return StorageServiceInstance.State.Destroyed;
        }
        if (State.Starting.equals(sharedFSState)) {
            return StorageServiceInstance.State.Starting;
        }
        if (State.Stopping.equals(sharedFSState)) {
            return StorageServiceInstance.State.Stopping;
        }
        if (State.Error.equals(sharedFSState)) {
            return StorageServiceInstance.State.Error;
        }
        return StorageServiceInstance.State.Allocated;
    }

    protected StorageServiceInstance.ResourceState toStorageResourceState(State sharedFSState) {
        if (State.Ready.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Ready;
        }
        if (State.Destroyed.equals(sharedFSState) || State.Expunged.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Destroyed;
        }
        if (State.Error.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Error;
        }
        if (State.Starting.equals(sharedFSState) || State.Stopping.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Updating;
        }
        if (State.Stopped.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Disabled;
        }
        return StorageServiceInstance.ResourceState.Allocated;
    }

    protected boolean isSharedFSReady(State sharedFSState) {
        return State.Ready.equals(sharedFSState);
    }

    protected Long getSharedFSVolumeSize(SharedFS sharedFS) {
        if (sharedFS.getVolumeId() == null) {
            return null;
        }
        VolumeVO volume = volumeDao.findById(sharedFS.getVolumeId());
        return volume == null ? null : volume.getSize();
    }

    @Override
    public SharedFS getSharedFSByUuid(String uuid) {
        return sharedFSDao.findByUuid(uuid);
    }

    @Override
    public SharedFS getSharedFSForVmId(long vmId) {
        return sharedFSDao.findByVm(vmId);
    }

    public SharedFS updateSharedFSPostRestore(long sharedFsId, long volumeId) {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFsId);
        if (sharedFS == null) {
            throw new CloudRuntimeException("Unable to find the Shared FileSystem");
        }
        VolumeVO volume = volumeDao.findById(volumeId);
        if (volume == null) {
            throw new CloudRuntimeException("Unable to find the Volume");
        }
        if (volume.getInstanceId() == null) {
            throw new CloudRuntimeException("Volume is not attached to any Instance");
        }
        if (sharedFS.getAccountId() != volume.getAccountId() || sharedFS.getDomainId() != volume.getDomainId()) {
            throw new CloudRuntimeException("Shared FileSystem and the Volume do not belong to the same account");
        }
        sharedFS.setVolumeId(volume.getId());
        sharedFS.setVmId(volume.getInstanceId());
        if (!sharedFSDao.update(sharedFS.getId(), sharedFS)) {
            throw new CloudRuntimeException("Failed to update Shared FileSystem with the restored Volume information");
        }
        return sharedFS;
    }

    @Override
    public String getConfigComponentName() {
        return SharedFSService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                SharedFSCleanupInterval,
                SharedFSCleanupDelay,
                SharedFSFeatureEnabled,
                SharedFSExpungeWorkers
        };
    }
    protected class SharedFSGarbageCollector extends ManagedContextRunnable {

        public SharedFSGarbageCollector() {
        }

        @Override
        protected void runInContext() {
            try {
                logger.trace("Shared FileSystem Garbage Collection Thread is running.");

                cleanupSharedFS(true);

            } catch (Exception e) {
                logger.error("Caught the following Exception", e);
            }
        }
    }

    public void cleanupSharedFS(boolean recurring) {
        GlobalLock scanLock = GlobalLock.getInternLock("sharedfsservice.cleanup");

        try {
            if (scanLock.lock(30)) {
                try {

                    List<SharedFSVO> sharedFSs = sharedFSDao.listSharedFSToBeDestroyed(new Date(System.currentTimeMillis() - ((long)SharedFSCleanupDelay.value() << 10)));
                    for (SharedFSVO sharedFS : sharedFSs) {
                        try {
                            stateTransitTo(sharedFS, Event.ExpungeOperation);
                            deleteSharedFS(sharedFS.getId());
                        } catch (Exception e) {
                            stateTransitTo(sharedFS, Event.OperationFailed);
                            logger.error("Unable to expunge Shared FileSystem {} due to: [{}].", sharedFS, e.getMessage());
                        }
                    }
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }
    }
}
