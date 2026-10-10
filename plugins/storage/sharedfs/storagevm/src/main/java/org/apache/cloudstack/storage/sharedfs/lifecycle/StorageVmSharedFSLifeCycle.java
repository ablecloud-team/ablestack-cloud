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

package org.apache.cloudstack.storage.sharedfs.lifecycle;

import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.LaunchPermissionVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.LaunchPermissionDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.FileUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSLifeCycle;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSVmNamePrefix;
import static org.apache.cloudstack.storage.sharedfs.provider.StorageVmSharedFSProvider.SHAREDFSVM_MIN_CPU_COUNT;
import static org.apache.cloudstack.storage.sharedfs.provider.StorageVmSharedFSProvider.SHAREDFSVM_MIN_RAM_SIZE;

public class StorageVmSharedFSLifeCycle implements SharedFSLifeCycle {
    private static final String STORAGE_VM_CONFIG_RESOURCE = "conf/fsvm-init.yml";

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private AccountManager accountMgr;

    @Inject
    protected ResourceManager resourceMgr;

    @Inject
    private VirtualMachineManager virtualMachineManager;

    @Inject
    private VolumeApiService volumeApiService;

    @Inject
    protected UserVmService userVmService;

    @Inject
    protected UserVmManager userVmManager;

    @Inject
    private DataCenterDao dataCenterDao;

    @Inject
    private VMTemplateDao templateDao;

    @Inject
    VolumeDao volumeDao;

    @Inject
    private UserVmDao userVmDao;

    @Inject
    private org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao recordedStoragePoolDao;

    @Inject
    NicDao nicDao;

    @Inject
    ServiceOfferingDao serviceOfferingDao;

    @Inject
    protected LaunchPermissionDao launchPermissionDao;

    private String readResourceFile(String resource) {
        String normalizedResource = resource != null && resource.startsWith("/") ? resource.substring(1) : resource;
        try {
            return FileUtil.readResourceFile(normalizedResource);
        } catch (NullPointerException e) {
            throw new CloudRuntimeException(String.format("Unable to read the user data resource file [%s]: resource was not found on classpath", normalizedResource), e);
        } catch (IOException e) {
            throw new CloudRuntimeException(String.format("Unable to read the user data resource file [%s] due to exception %s", normalizedResource, e.getMessage()), e);
        }
    }

    private String getStorageVmConfig() {
        return readResourceFile(STORAGE_VM_CONFIG_RESOURCE);
    }

    private String getStorageVmPrefix(String fileShareName) {
        String prefix = String.format("%s-%s", SharedFSVmNamePrefix, fileShareName);
        if (!NetUtils.verifyDomainNameLabel(prefix, true)) {
            prefix = prefix.replaceAll("[^a-zA-Z0-9-]", "");
        }
        return prefix;
    }

    private String getStorageVmName(String fileShareName) {
        String prefix = getStorageVmPrefix(fileShareName);
        String suffix = Long.toHexString(System.currentTimeMillis());

        int nameLength = prefix.length() + suffix.length() + SharedFSVmNamePrefix.length();
        if (nameLength > 63) {
            int prefixLength = prefix.length() - (nameLength - 63);
            prefix = prefix.substring(0, prefixLength);
        }
        return (String.format("%s-%s", prefix, suffix));
    }

    private UserVm deploySharedFSVM(Long zoneId, Account owner, List<Long> networkIds, String name, Long serviceOfferingId, Long diskOfferingId,
            SharedFS.FileSystemType fileSystem, Long size, Long minIops, Long maxIops, SharedFS.NetworkMode networkMode, String requestedIp, Long explicitTemplateId) throws OperationTimedoutException,
            ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        return deploySharedFSVM(zoneId, owner, networkIds, name, serviceOfferingId, diskOfferingId,
                fileSystem, size, minIops, maxIops, networkMode, requestedIp, explicitTemplateId, null);
    }

    private UserVm deploySharedFSVM(Long zoneId, Account owner, List<Long> networkIds, String name, Long serviceOfferingId, Long diskOfferingId,
            SharedFS.FileSystemType fileSystem, Long size, Long minIops, Long maxIops, SharedFS.NetworkMode networkMode, String requestedIp, Long explicitTemplateId, java.util.function.LongConsumer allocatedRecorder) throws OperationTimedoutException,
            ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        ServiceOffering serviceOffering = serviceOfferingDao.findById(serviceOfferingId);
        DataCenter zone = dataCenterDao.findById(zoneId);

        List<Hypervisor.HypervisorType> hypervisors = resourceMgr.getSupportedHypervisorTypes(zoneId, false, null);
        if (hypervisors.size() > 0) {
            Collections.shuffle(hypervisors);
        } else {
            throw new CloudRuntimeException(String.format("No supported hypervisor found for zone %s.", zone.toString()));
        }

        String hostName = getStorageVmName(name);
        Network.IpAddresses addrs = new Network.IpAddresses(networkMode == SharedFS.NetworkMode.STATIC ? requestedIp : null, null);
        Map<String, String> customParameterMap = new HashMap<String, String>();
        if (minIops != null) {
            customParameterMap.put("minIopsDo", minIops.toString());
            customParameterMap.put("maxIopsDo", maxIops.toString());
        }
        List<String> keypairs = new ArrayList<String>();
        String preferredArchitecture = ResourceManager.SystemVmPreferredArchitecture.valueIn(zoneId);

        for (final Iterator<Hypervisor.HypervisorType> iter = hypervisors.iterator(); iter.hasNext();) {
            final Hypervisor.HypervisorType hypervisor = iter.next();
            VMTemplateVO template = explicitTemplateId == null ? templateDao.findSystemVMReadyTemplate(zoneId, hypervisor, preferredArchitecture) : templateDao.findById(explicitTemplateId);
            if (template == null && !iter.hasNext()) {
                throw new CloudRuntimeException(String.format("Unable to find the systemvm template for %s or it was not downloaded in %s.", hypervisor.toString(), zone.toString()));
            }

            if (template == null || !template.isDynamicallyScalable() || hypervisor != Hypervisor.HypervisorType.KVM) continue;

            LaunchPermissionVO existingPermission = launchPermissionDao.findByTemplateAndAccount(template.getId(), owner.getId());
            if (existingPermission == null && explicitTemplateId == null) {
                LaunchPermissionVO launchPermission = new LaunchPermissionVO(template.getId(), owner.getId());
                launchPermissionDao.persist(launchPermission);
            }

            UserVm vm = null;
            String base64UserData = null;
            if (networkMode == SharedFS.NetworkMode.DHCP) {
                String fsVmConfig = getStorageVmConfig();
                base64UserData = Base64.encodeBase64String(fsVmConfig.getBytes(com.cloud.utils.StringUtils.getPreferredCharset()));
            }
            CallContext vmContext = CallContext.register(CallContext.current(), ApiCommandResourceType.VirtualMachine);
            try {
                if (allocatedRecorder == null) {
                vm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, template, networkIds, owner, hostName, hostName,
                        diskOfferingId, size, null, null, Hypervisor.HypervisorType.None, BaseCmd.HTTPMethod.POST, base64UserData,
                        null, null, keypairs, null, addrs, null, null, null,
                        customParameterMap, null, null, null, null,
                        true, UserVmManager.SHAREDFSVM, null, null, null, null);
                } else {
                    // Same-thread Cloud DB transactions below join this outer allocation transaction.
                    try (com.cloud.utils.db.TransactionLegacy transaction =
                            com.cloud.utils.db.TransactionLegacy.open("RecordedSharedFSVmAllocation")) {
                        transaction.start();
                vm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, template, networkIds, owner, hostName, hostName,
                        diskOfferingId, size, null, null, Hypervisor.HypervisorType.None, BaseCmd.HTTPMethod.POST, base64UserData,
                        null, null, keypairs, null, addrs, null, null, null,
                        customParameterMap, null, null, null, null,
                        true, UserVmManager.SHAREDFSVM, null, null, null, null);
                        allocatedRecorder.accept(vm.getId());
                        if (!transaction.commit())
                            throw new CloudRuntimeException("Recorded VM allocation must own its commit before start");
                    }
                }
                vmContext.setEventResourceId(vm.getId());
                userVmService.startVirtualMachine(vm, null);
            } catch (InsufficientCapacityException ex) {
                if (allocatedRecorder != null) throw ex;
                if (vm != null) {
                    expungeVm(vm.getId());
                }
                if (iter.hasNext()) {
                    continue;
                } else {
                    throw ex;
                }
            } finally {
                CallContext.unregister();
            }
            return vm;
        }
        throw new CloudRuntimeException("No compatible scalable KVM SystemVM template could be deployed in the selected zone");
    }

    protected boolean zoneScalingEnabled(long zoneId) {
        return UserVmManager.EnableDynamicallyScaleVm.valueIn(zoneId);
    }

    @Override
    public List<org.apache.cloudstack.api.response.StorageServiceOfferingConstraintResponse> evaluateOfferings(DataCenter zone, List<Long> ids) {
        int cpu=SHAREDFSVM_MIN_CPU_COUNT.valueIn(zone.getId());
        int memory=SHAREDFSVM_MIN_RAM_SIZE.valueIn(zone.getId());
        boolean zoneScaling=zoneScalingEnabled(zone.getId());
        boolean hypervisorReady=false;boolean templateReady=false;
        List<Hypervisor.HypervisorType> hypervisors=resourceMgr.getSupportedHypervisorTypes(zone.getId(),false,null);
        if (hypervisors != null) for (Hypervisor.HypervisorType hypervisor:hypervisors) {
            if (hypervisor != Hypervisor.HypervisorType.KVM) continue;
            hypervisorReady=true;
            VMTemplateVO template=templateDao.findSystemVMReadyTemplate(zone.getId(),hypervisor,ResourceManager.SystemVmPreferredArchitecture.valueIn(zone.getId()));
            if (template != null && template.isDynamicallyScalable()) templateReady=true;
        }
        List<org.apache.cloudstack.api.response.StorageServiceOfferingConstraintResponse> responses=new ArrayList<>();
        for(Long id:ids) {
            ServiceOffering offering=serviceOfferingDao.findById(id);
            List<String> reasons=org.apache.cloudstack.storage.sharedfs.SharedFSOfferingValidator.reasons(offering,cpu,memory,zoneScaling,templateReady,hypervisorReady);
            responses.add(new org.apache.cloudstack.api.response.StorageServiceOfferingConstraintResponse(
                    offering == null ? null : offering.getUuid(),reasons,cpu,memory,zoneScaling,templateReady,hypervisorReady));
        }
        return responses;
    }

    @Override
    public void checkPrerequisites(DataCenter zone, Long serviceOfferingId) {
        org.apache.cloudstack.api.response.StorageServiceOfferingConstraintResponse result=evaluateOfferings(zone,List.of(serviceOfferingId)).get(0);
        if (!result.isCompatible()) throw new InvalidParameterValueException("SharedFS offering constraints: " + String.join(",",result.getReasons()));
    }

    @Override
    public void checkPrerequisites(DataCenter zone, Long serviceOfferingId, Long templateId) {
        if (templateId == null) { checkPrerequisites(zone, serviceOfferingId); return; }
        VMTemplateVO template=templateDao.findById(templateId);
        boolean hypervisorReady=resourceMgr.getSupportedHypervisorTypes(zone.getId(),false,null).contains(Hypervisor.HypervisorType.KVM);
        boolean templateReady=template!=null&&template.isDynamicallyScalable()&&template.getHypervisorType()==Hypervisor.HypervisorType.KVM;
        List<String> reasons=org.apache.cloudstack.storage.sharedfs.SharedFSOfferingValidator.reasons(serviceOfferingDao.findById(serviceOfferingId),
                SHAREDFSVM_MIN_CPU_COUNT.valueIn(zone.getId()),SHAREDFSVM_MIN_RAM_SIZE.valueIn(zone.getId()),zoneScalingEnabled(zone.getId()),templateReady,hypervisorReady);
        if(!reasons.isEmpty())throw new InvalidParameterValueException("SharedFS offering constraints: "+String.join(",",reasons));
    }

    @Override
    public Pair<Long, Long> deploySharedFS(SharedFS sharedFS, Long networkId, Long diskOfferingId, Long storageId, Long size, Long minIops, Long maxIops) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        return deploySharedFS(sharedFS, networkId, diskOfferingId, storageId, size, minIops, maxIops, null);
    }

    @Override
    public Pair<Long, Long> deploySharedFS(SharedFS sharedFS, Long networkId, Long diskOfferingId, Long storageId,
            Long size, Long minIops, Long maxIops, Long templateId) throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        Account owner = accountMgr.getActiveAccountById(sharedFS.getAccountId());
        UserVm vm = deploySharedFSVM(sharedFS.getDataCenterId(), owner, List.of(networkId), sharedFS.getName(), sharedFS.getServiceOfferingId(), diskOfferingId,
                sharedFS.getFsType(), size, minIops, maxIops, sharedFS.getNetworkMode(), sharedFS.getIpAddress(), templateId);

        List<VolumeVO> volumes = volumeDao.findByInstance(vm.getId());
        VolumeVO dataVol = null;
        for (VolumeVO vol : volumes) {
            String volumeName = vol.getName();
            String updatedVolumeName = SharedFSVmNamePrefix + "-" + volumeName;
            vol.setName(updatedVolumeName);
            volumeDao.update(vol.getId(), vol);
            if (vol.getVolumeType() == Volume.Type.DATADISK) {
                dataVol = vol;
            }
        }
        if (dataVol == null) {
            throw new CloudRuntimeException("SharedFS VM was deployed without an initial data volume");
        }
        if (storageId != null && dataVol.getPoolId() != null && !storageId.equals(dataVol.getPoolId())) {
            expungeVm(vm.getId());
            throw new CloudRuntimeException(String.format("Initial SharedFS backing volume was allocated on storage pool %s instead of selected storage pool %s",
                    dataVol.getPoolId(), storageId));
        }
        return new Pair<>(dataVol.getId(), vm.getId());
    }

    public Pair<Long, Long> deploySharedFS(SharedFS sharedFS, Long networkId, Long diskOfferingId, Long size, Long minIops, Long maxIops) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        return deploySharedFS(sharedFS, networkId, diskOfferingId, null, size, minIops, maxIops);
    }

    @Override
    public Pair<Long, Long> deploySharedFS(SharedFS sharedFS, Long networkId, Long diskOfferingId, Long storageId,
            Long size, Long minIops, Long maxIops, Long templateId, java.util.function.LongConsumer allocatedRecorder)
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        if (templateId == null || allocatedRecorder == null || sharedFS.getBackingVolumeMode() != SharedFS.BackingVolumeMode.NEW)
            throw new InvalidParameterValueException("Recorded deployment requires a pinned template and new DATA");
        UserVm vm;
        if (sharedFS.getVmId() == null) {
            Account owner = accountMgr.getActiveAccountById(sharedFS.getAccountId());
            vm = deploySharedFSVM(sharedFS.getDataCenterId(), owner, List.of(networkId), sharedFS.getName(),
                    sharedFS.getServiceOfferingId(), diskOfferingId, sharedFS.getFsType(), size, minIops, maxIops,
                    sharedFS.getNetworkMode(), sharedFS.getIpAddress(), templateId, allocatedRecorder);
        } else {
            UserVmVO retained = userVmDao.findById(sharedFS.getVmId());
            if (retained == null || retained.getRemoved() != null || retained.getAccountId() != sharedFS.getAccountId()
                    || retained.getDataCenterId() != sharedFS.getDataCenterId() || retained.getTemplateId() != templateId
                    || !UserVmManager.SHAREDFSVM.equals(retained.getUserVmType()))
                throw new CloudRuntimeException("Recorded VM ownership or template changed");
            List<VolumeVO> retainedRoots = volumeDao.findByInstanceAndType(retained.getId(), com.cloud.storage.Volume.Type.ROOT);
            List<VolumeVO> retainedData = volumeDao.findByInstanceAndType(retained.getId(), com.cloud.storage.Volume.Type.DATADISK);
            if (retainedRoots.size() != 1 || retainedData.size() != 1 || sharedFS.getVolumeId() == null
                    || sharedFS.getVolumeId() != retainedData.get(0).getId())
                throw new CloudRuntimeException("Recorded VM has replaced or ambiguous disks before start");
            requireRetainedDisk(retainedRoots.get(0), retained, true, storageId);
            requireRetainedDisk(retainedData.get(0), retained, false, storageId);
            // Re-observe the immutable ROOT UUID/size receipt at this last provider boundary.
            allocatedRecorder.accept(retained.getId());
            if (retained.getState() == com.cloud.vm.VirtualMachine.State.Stopped) {
                userVmService.startVirtualMachine(retained, null);
            } else if (retained.getState() != com.cloud.vm.VirtualMachine.State.Running) {
                throw new CloudRuntimeException("Recorded VM start has an unresolved state; new allocation is forbidden");
            }
            vm = retained;
        }
        List<VolumeVO> roots = volumeDao.findByInstanceAndType(vm.getId(), com.cloud.storage.Volume.Type.ROOT);
        List<VolumeVO> data = volumeDao.findByInstanceAndType(vm.getId(), com.cloud.storage.Volume.Type.DATADISK);
        if (roots.size() != 1 || data.size() != 1 || sharedFS.getVolumeId() == null
                || sharedFS.getVolumeId() != data.get(0).getId() || roots.get(0).getRemoved() != null
                || data.get(0).getRemoved() != null)
            throw new CloudRuntimeException("Recorded VM disk identity changed");
        if (storageId != null && !storageId.equals(data.get(0).getPoolId()))
            throw new CloudRuntimeException("Recorded DATA is not on the selected pool; allocation is preserved");
        for (VolumeVO volume : volumeDao.findByInstance(vm.getId())) {
            if (!volume.getName().startsWith(SharedFSVmNamePrefix + "-")) {
                volume.setName(SharedFSVmNamePrefix + "-" + volume.getName());
                if (!volumeDao.update(volume.getId(), volume))
                    throw new CloudRuntimeException("Recorded disk metadata update failed");
            }
        }
        return new Pair<>(data.get(0).getId(), vm.getId());
    }

    private void requireRetainedDisk(VolumeVO disk, UserVmVO vm, boolean root, Long selectedDataPool) {
        if (disk.getRemoved() != null || disk.getAccountId() != vm.getAccountId() || disk.getDataCenterId() != vm.getDataCenterId()
                || !java.util.Objects.equals(disk.getInstanceId(), vm.getId())
                || disk.getVolumeType() != (root ? com.cloud.storage.Volume.Type.ROOT : com.cloud.storage.Volume.Type.DATADISK)
                || disk.getState() == null || !java.util.Set.of(com.cloud.storage.Volume.State.Allocated, com.cloud.storage.Volume.State.Ready).contains(disk.getState())
                || disk.getProvisioningType() == null || !java.util.Set.of(com.cloud.storage.Storage.ProvisioningType.SPARSE,
                        com.cloud.storage.Storage.ProvisioningType.FAT).contains(disk.getProvisioningType())
                || root && !java.util.Objects.equals(disk.getTemplateId(), vm.getTemplateId())
                || !root && (disk.getTemplateId() != null || org.apache.commons.lang3.StringUtils.isNotBlank(disk.getChainInfo())))
            throw new CloudRuntimeException("Recorded disk ownership source state or provisioning changed before start");
        Long poolId = disk.getPoolId();
        if (!root && (poolId == null && disk.getState() != com.cloud.storage.Volume.State.Allocated
                || poolId != null && !java.util.Objects.equals(poolId, selectedDataPool)))
            throw new CloudRuntimeException("Recorded DATA pool changed before start");
        if (poolId != null) {
            org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool = recordedStoragePoolDao.findById(poolId);
            if (pool == null || pool.getDataCenterId() != vm.getDataCenterId() || pool.getStatus() != com.cloud.storage.StoragePoolStatus.Up
                    || pool.getPoolType() == null || !java.util.Set.of(com.cloud.storage.Storage.StoragePoolType.Filesystem,
                            com.cloud.storage.Storage.StoragePoolType.NetworkFilesystem, com.cloud.storage.Storage.StoragePoolType.SharedMountPoint,
                            com.cloud.storage.Storage.StoragePoolType.RBD).contains(pool.getPoolType()))
                throw new CloudRuntimeException("Recorded disk pool has no supported format policy");
            if (disk.getFormat() == com.cloud.storage.Storage.ImageFormat.RAW
                    && (disk.getState() != com.cloud.storage.Volume.State.Ready || pool.getPoolType() != com.cloud.storage.Storage.StoragePoolType.RBD))
                throw new CloudRuntimeException("Recorded RAW disk is not an exact Ready RBD realization");
        } else if (disk.getState() != com.cloud.storage.Volume.State.Allocated) {
            throw new CloudRuntimeException("Recorded Ready disk pool is unavailable");
        }
        if (disk.getFormat() != com.cloud.storage.Storage.ImageFormat.QCOW2
                && (disk.getFormat() != com.cloud.storage.Storage.ImageFormat.RAW || poolId == null))
            throw new CloudRuntimeException("Recorded disk image format is unsupported before start");
    }

    @Override
    public Pair<Long, Long> deployWithExistingVolume(SharedFS sharedFS, Long networkId, Long volumeId) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        return deployWithExistingVolume(sharedFS, networkId, volumeId, null);
    }

    @Override
    public Pair<Long, Long> deployWithExistingVolume(SharedFS sharedFS, Long networkId, Long volumeId, Long templateId)
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        Account owner=accountMgr.getActiveAccountById(sharedFS.getAccountId());
        UserVm vm=deploySharedFSVM(sharedFS.getDataCenterId(),owner,List.of(networkId),sharedFS.getName(),sharedFS.getServiceOfferingId(),null,
                sharedFS.getFsType(),null,null,null,sharedFS.getNetworkMode(),sharedFS.getIpAddress(),templateId);
        sharedFS.setVmId(vm.getId());
        try {
            Volume attached=volumeApiService.attachVolumeToVM(vm.getId(),volumeId,null,true);
            if (attached==null || attached.getInstanceId()==null || attached.getInstanceId()!=vm.getId()) throw new CloudRuntimeException("Initial existing volume attachment was not confirmed");
            return new Pair<>(volumeId,vm.getId());
        } catch (RuntimeException failure) {
            // The service records the created VM and performs preservation-aware cleanup, including a partial attachment.
            throw failure;
        }
    }

    @Override
    public void startSharedFS(SharedFS sharedFS) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException {
        UserVmVO vm = userVmDao.findById(sharedFS.getVmId());
        userVmService.startVirtualMachine(vm, null);
    }

    @Override
    public boolean stopSharedFS(SharedFS sharedFS, Boolean forced) {
        UserVm stopped=userVmManager.stopVirtualMachine(sharedFS.getVmId(),Boolean.TRUE.equals(forced));
        return stopped!=null && stopped.getState()==com.cloud.vm.VirtualMachine.State.Stopped;
    }

    private void expungeVm(Long vmId) {
        UserVmVO userVM = userVmDao.findById(vmId);
        if (userVM == null) {
            return;
        }
        try {
            UserVm vm = userVmService.destroyVm(userVM.getId(), true);
            if (!userVmManager.expunge(userVM)) {
                throw new CloudRuntimeException("Failed to expunge VM " + userVM.toString());
            }
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("Failed to expunge VM " + userVM.toString());
        }
        userVmDao.remove(vmId);
    }

    @Override
    public boolean deleteSharedFS(SharedFS sharedFS) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        if (sharedFS.getVolumeId() != null) ids.add(sharedFS.getVolumeId());
        if (sharedFS.getVmId() != null) for (VolumeVO volume : volumeDao.findByInstanceAndType(sharedFS.getVmId(), Volume.Type.DATADISK)) ids.add(volume.getId());
        return deleteSharedFS(sharedFS, SharedFS.DataVolumePolicy.PRESERVE_VOLUMES, ids);
    }

    @Override
    public boolean deleteSharedFS(SharedFS sharedFS, SharedFS.DataVolumePolicy policy, java.util.Set<Long> volumeIds) {
        final Long vmId = sharedFS.getVmId();
        if (policy == null) policy = SharedFS.DataVolumePolicy.PRESERVE_VOLUMES;
        if (vmId != null) {
            final UserVmVO vm = userVmDao.findById(vmId);
            if (vm != null && vm.getState() != com.cloud.vm.VirtualMachine.State.Stopped && vm.getState() != com.cloud.vm.VirtualMachine.State.Destroyed) {
                throw new CloudRuntimeException("Stop the Storage Service VM cleanly before data-volume retention");
            }
            for (VolumeVO volume : volumeDao.findByInstanceAndType(vmId, Volume.Type.DATADISK)) {
                if (!volumeIds.contains(volume.getId())) throw new CloudRuntimeException("Backing volume inventory changed; deletion plan must be refreshed");
            }
        }
        // Validate the entire plan before the first detach or VM removal.
        for (Long id : volumeIds) {
            VolumeVO volume = volumeDao.findByIdIncludingRemoved(id);
            if (volume == null) throw new CloudRuntimeException("A reviewed DATA row disappeared without retained identity; VM removal is blocked");
            if (policy == SharedFS.DataVolumePolicy.PRESERVE_VOLUMES && (volume.getRemoved() != null
                    || volume.getState() == Volume.State.Destroy || volume.getState() == Volume.State.Expunging || volume.getState() == Volume.State.Expunged)) {
                throw new CloudRuntimeException("A reviewed DATA volume was removed externally; preservation cannot be claimed");
            }
            if (volume.getVolumeType() != Volume.Type.DATADISK || volume.getAccountId() != sharedFS.getAccountId()) {
                throw new CloudRuntimeException("Deletion plan contains a non-data volume or a foreign account volume");
            }
            if (volume.getInstanceId() != null && !volume.getInstanceId().equals(vmId)) {
                throw new CloudRuntimeException("A planned data volume is attached to another VM");
            }
        }
        for (Long id : volumeIds) {
            VolumeVO volume = volumeDao.findById(id);
            if (volume != null && vmId != null && vmId.equals(volume.getInstanceId())) {
                volumeApiService.detachVolumeViaDestroyVM(vmId, id);
                VolumeVO observed = volumeDao.findById(id);
                if (observed == null || observed.getInstanceId() != null) {
                    throw new CloudRuntimeException("Data volume detach was not verified; VM removal is blocked");
                }
            }
        }
        if (vmId != null) expungeVm(vmId);
        if (policy == SharedFS.DataVolumePolicy.DELETE_VOLUMES) {
            for (Long id : volumeIds) {
                VolumeVO volume = volumeDao.findByIdIncludingRemoved(id);
                if (volume == null) throw new CloudRuntimeException("Reviewed DATA identity disappeared during deletion");
                if (volume.getRemoved() != null || volume.getState() == Volume.State.Destroy || volume.getState() == Volume.State.Expunging || volume.getState() == Volume.State.Expunged) continue;
                boolean allocated = volume.getState() == Volume.State.Allocated;
                Volume removed = volumeApiService.destroyVolume(id, CallContext.current().getCallingAccount(), allocated, allocated, null);
                VolumeVO remaining = volumeDao.findById(id);
                if (removed == null || (remaining != null && remaining.getState() != Volume.State.Destroy
                        && remaining.getState() != Volume.State.Expunging && remaining.getState() != Volume.State.Expunged)) {
                    throw new CloudRuntimeException("A planned data volume could not be deleted; the removal plan remains retryable");
                }
            }
        }
        return true;
    }

    @Override
    public boolean reDeploySharedFS(SharedFS sharedFS) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        UserVm vm =  virtualMachineManager.restoreVirtualMachine(sharedFS.getVmId(), null, null, true, null);
        return (vm != null);
    }

    @Override
    public boolean changeSharedFSServiceOffering(SharedFS sharedFS, Long serviceOfferingId) throws ManagementServerException, ResourceUnavailableException, VirtualMachineMigrationException {
        return userVmManager.upgradeVirtualMachine(sharedFS.getVmId(), serviceOfferingId, new HashMap<String, String>());
    }
}
