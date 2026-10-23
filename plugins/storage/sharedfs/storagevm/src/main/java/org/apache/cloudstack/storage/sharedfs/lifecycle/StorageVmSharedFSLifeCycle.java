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
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.storage.VolumeAllocationService;
import com.cloud.storage.dao.DiskOfferingDao;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSOfferingEligibility;
import com.cloud.configuration.ConfigurationManagerImpl;
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
    NicDao nicDao;

    @Inject
    ServiceOfferingDao serviceOfferingDao;

    @Inject
    protected LaunchPermissionDao launchPermissionDao;

    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @Inject
    private DiskOfferingDao diskOfferingDao;

    @Inject
    private DiskOfferingDetailsDao diskOfferingDetailsDao;

    @Inject
    private VolumeAllocationService allocationService;

    protected int creationMaxCpu() {
        return ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value();
    }

    protected int creationMaxRam() {
        return ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value();
    }

    protected String creationArchitecture(Long zoneId) {
        return SharedFSOfferingEligibility.normalizeArchitecture(ResourceManager.SystemVmPreferredArchitecture.valueIn(zoneId));
    }

    protected boolean hasReadyCreationTemplate(Long zoneId, String architecture) {
        if (architecture == null) return false;
        List<Hypervisor.HypervisorType> hypervisors = resourceMgr.getSupportedHypervisorTypes(zoneId, false, null);
        if (hypervisors == null) return false;
        for (Hypervisor.HypervisorType hypervisor : hypervisors) {
            VMTemplateVO template = templateDao.findSystemVMReadyTemplate(zoneId, hypervisor, architecture);
            if (template != null && template.getArch() != null
                    && architecture.equals(SharedFSOfferingEligibility.normalizeArchitecture(template.getArch().getType()))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public SharedFSOfferingEligibility getCreationOfferingEligibility(DataCenter zone, Long offeringId) {
        ServiceOfferingVO offering = serviceOfferingDao.findById(offeringId);
        if (offering == null) {
            throw new InvalidParameterValueException("Unable to find service offering with id " + offeringId);
        }
        Map<String, String> details = serviceOfferingDetailsDao.listDetailsKeyPairs(offeringId);
        SharedFSOfferingEligibility result = new SharedFSOfferingEligibility(offering,
                details == null ? Collections.emptyMap() : details,
                SHAREDFSVM_MIN_CPU_COUNT.valueIn(zone.getId()), SHAREDFSVM_MIN_RAM_SIZE.valueIn(zone.getId()),
                creationMaxCpu(), creationMaxRam());
        if (offering.getState() != ServiceOffering.State.Active) {
            result.reject("OFFERING_INACTIVE", "serviceofferingid");
        }
        DiskOfferingVO root = diskOfferingDao.findById(offering.getDiskOfferingId());
        if (root == null) {
            result.reject("ROOT_POLICY_UNKNOWN", "serviceofferingid");
        } else {
            Map<String, String> intent;
            try {
                intent = VolumeAllocationPolicy.validateStored(diskOfferingDetailsDao.listDetailsKeyPairs(root.getId()));
                String policy = intent.isEmpty() ? (root.getProvisioningType() == null ? null : root.getProvisioningType().toString())
                        : intent.get(VolumeAllocationPolicy.POLICY);
                result.setRoot(root.getUuid(), policy, intent.isEmpty() ? "legacy" : "allocation");
                if (intent.isEmpty()) {
                    if (root.getProvisioningType() == Storage.ProvisioningType.THIN) {
                        result.reject("ROOT_POLICY_THIN", "serviceofferingid");
                    } else if (root.getProvisioningType() != Storage.ProvisioningType.SPARSE
                            && root.getProvisioningType() != Storage.ProvisioningType.FAT) {
                        result.reject("ROOT_POLICY_UNKNOWN", "serviceofferingid");
                    }
                } else if ("off".equals(policy)) {
                    result.reject("ROOT_POLICY_THIN", "serviceofferingid");
                } else {
                    allocationService.validateCreate(intent, root.getProvisioningType(), zone.getId(), null, root.getTagsArray());
                }
            } catch (InvalidParameterValueException e) {
                result.reject("ROOT_POLICY_UNAVAILABLE", "serviceofferingid");
            }
        }
        String architecture = creationArchitecture(zone.getId());
        result.setArchitecture(architecture);
        if (!hasReadyCreationTemplate(zone.getId(), architecture)) {
            result.reject("SYSTEMVM_ARCH_UNAVAILABLE", "serviceofferingid");
        }
        return result;
    }

    @Override
    public Map<String, String> checkCreationPrerequisites(DataCenter zone, Long offeringId, Map<String, String> customValues) {
        ServiceOfferingVO offering = serviceOfferingDao.findById(offeringId);
        if (offering == null) {
            throw new InvalidParameterValueException("Unable to find service offering with id " + offeringId);
        }
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), offering, zone);
        SharedFSOfferingEligibility result = getCreationOfferingEligibility(zone, offeringId);
        Map<String, String> normalized = result.validateCustomValues(customValues);
        if (offering.isDynamic()) {
            try {
                userVmManager.validateCustomParameters(offering, normalized);
            } catch (InvalidParameterValueException e) {
                result.reject("CUSTOM_VALUES_INVALID", "serviceofferingid");
                result.requireSelectable();
            }
            ServiceOfferingVO resolved = serviceOfferingDao.getComputeOffering(offering, normalized);
            checkResourcePrerequisites(zone, resolved);
        } else {
            checkResourcePrerequisites(zone, offering);
        }
        return normalized;
    }

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
            SharedFS.FileSystemType fileSystem, Long size, Long minIops, Long maxIops, SharedFS.NetworkMode networkMode, String requestedIp, Map<String, String> customValues, String plannedVmUuid, boolean start) throws OperationTimedoutException,
            ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        ServiceOffering serviceOffering = serviceOfferingDao.findById(serviceOfferingId);
        DataCenter zone = dataCenterDao.findById(zoneId);

        List<Hypervisor.HypervisorType> hypervisors = resourceMgr.getSupportedHypervisorTypes(zoneId, false, null);
        if (hypervisors.size() > 0) {
            Collections.shuffle(hypervisors);
        } else {
            throw new CloudRuntimeException(String.format("No supported hypervisor found for zone %s.", zone.toString()));
        }

        String hostName = plannedVmUuid == null ? getStorageVmName(name) : "sharedfs-" + plannedVmUuid;
        Network.IpAddresses addrs = new Network.IpAddresses(networkMode == SharedFS.NetworkMode.STATIC ? requestedIp : null, null);
        Map<String, String> customParameterMap = new HashMap<>(customValues);
        if (minIops != null) {
            customParameterMap.put("minIopsDo", minIops.toString());
            customParameterMap.put("maxIopsDo", maxIops.toString());
        }
        List<String> keypairs = new ArrayList<String>();
        String preferredArchitecture = creationArchitecture(zoneId);

        for (final Iterator<Hypervisor.HypervisorType> iter = hypervisors.iterator(); iter.hasNext();) {
            final Hypervisor.HypervisorType hypervisor = iter.next();
            VMTemplateVO template = templateDao.findSystemVMReadyTemplate(zoneId, hypervisor, preferredArchitecture);
            if (template == null && !iter.hasNext()) {
                throw new CloudRuntimeException(String.format("Unable to find the systemvm template for %s or it was not downloaded in %s.", hypervisor.toString(), zone.toString()));
            }

            LaunchPermissionVO existingPermission = launchPermissionDao.findByTemplateAndAccount(template.getId(), owner.getId());
            if (existingPermission == null) {
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
                vm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, template, networkIds, owner, hostName, hostName,
                        diskOfferingId, size, null, null, Hypervisor.HypervisorType.None, BaseCmd.HTTPMethod.POST, base64UserData,
                        null, null, keypairs, null, addrs, null, null, null,
                        customParameterMap, plannedVmUuid, null, null, null,
                        true, UserVmManager.SHAREDFSVM, null, null, null, null);
                vmContext.setEventResourceId(vm.getId());
                if (start) userVmService.startVirtualMachine(vm, null);
            } catch (InsufficientCapacityException ex) {
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
        return null;
    }

    @Override
    public void checkPrerequisites(DataCenter zone, Long serviceOfferingId) {
        ServiceOffering serviceOffering = serviceOfferingDao.findById(serviceOfferingId);
        if (serviceOffering == null) {
            throw new InvalidParameterValueException("Unable to find service offering with id " + serviceOfferingId);
        }
        checkResourcePrerequisites(zone, serviceOffering);
    }

    private void checkResourcePrerequisites(DataCenter zone, ServiceOffering serviceOffering) {
        if (serviceOffering.getCpu() == null) {
            throw new InvalidParameterValueException("Service offering must have a fixed CPU count for SharedFS VM. Custom CPU offerings are not supported.");
        }
        if (serviceOffering.getRamSize() == null) {
            throw new InvalidParameterValueException("Service offering must have a fixed RAM size for SharedFS VM. Custom RAM offerings are not supported.");
        }
        if (serviceOffering.getCpu() < SHAREDFSVM_MIN_CPU_COUNT.valueIn(zone.getId())) {
            throw new InvalidParameterValueException("Service offering's number of cpu should be greater than or equal to " + SHAREDFSVM_MIN_CPU_COUNT.key());
        }
        if (serviceOffering.getRamSize() < SHAREDFSVM_MIN_RAM_SIZE.valueIn(zone.getId())) {
            throw new InvalidParameterValueException("Service offering's ram size should be greater than or equal to " + SHAREDFSVM_MIN_RAM_SIZE.key());
        }
        if (!serviceOffering.isOfferHA()) {
            throw new InvalidParameterValueException("Service offering's should be HA enabled");
        }
    }

    @Override
    public Pair<Long, Long> deploySharedFS(SharedFS sharedFS, Long networkId, Long diskOfferingId, Long storageId, Long size, Long minIops, Long maxIops) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        return deploySharedFS(sharedFS, networkId, diskOfferingId, storageId, size, minIops, maxIops, Collections.emptyMap());
    }

    @Override
    public Pair<Long, Long> deploySharedFS(SharedFS sharedFS, Long networkId, Long diskOfferingId, Long storageId, Long size,
            Long minIops, Long maxIops, Map<String, String> customValues) throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        Account owner = accountMgr.getActiveAccountById(sharedFS.getAccountId());
        UserVm vm = deploySharedFSVM(sharedFS.getDataCenterId(), owner, List.of(networkId), sharedFS.getName(), sharedFS.getServiceOfferingId(), diskOfferingId,
                sharedFS.getFsType(), size, minIops, maxIops, sharedFS.getNetworkMode(), sharedFS.getIpAddress(), customValues, null, true);

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
    public Long allocateInitialSharedFSVm(SharedFS sharedFS, Long networkId, Map<String, String> compute, String vmUuid)
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        UserVmVO existing = userVmDao.findByUuid(vmUuid);
        if (existing != null) {
            if (existing.getRemoved() != null || existing.getAccountId() != sharedFS.getAccountId()
                    || existing.getDataCenterId() != sharedFS.getDataCenterId()
                    || !UserVmManager.SHAREDFSVM.equals(existing.getUserVmType()))
                throw new CloudRuntimeException("Planned SharedFS VM UUID belongs to a different allocation");
            return existing.getId();
        }
        Account owner = accountMgr.getActiveAccountById(sharedFS.getAccountId());
        org.apache.cloudstack.storage.sharedfs.SharedFSCreationContext context = org.apache.cloudstack.storage.sharedfs.SharedFSCreationContext.read(sharedFS.getCreationContext());
        UserVm vm = deploySharedFSVM(sharedFS.getDataCenterId(), owner, List.of(networkId), sharedFS.getName(),
                sharedFS.getServiceOfferingId(), context.existing ? null : context.diskOfferingId, sharedFS.getFsType(),
                context.existing ? null : context.size, context.minIops, context.maxIops,
                sharedFS.getNetworkMode(), sharedFS.getIpAddress(), compute, vmUuid, false);
        return vm.getId();
    }

    @Override
    public void cleanupImportedSharedFSVm(SharedFS sharedFS) {
        if (sharedFS.getVmId() == null) return;
        if (volumeDao.findByInstance(sharedFS.getVmId()).stream().anyMatch(v -> v.getVolumeType() != Volume.Type.ROOT))
            throw new CloudRuntimeException("Existing DATA must be detached before cleaning up the owned SharedFS VM");
        expungeVm(sharedFS.getVmId());
    }

    @Override
    public void startSharedFS(SharedFS sharedFS) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException {
        UserVmVO vm = userVmDao.findById(sharedFS.getVmId());
        userVmService.startVirtualMachine(vm, null);
    }

    @Override
    public boolean stopSharedFS(SharedFS sharedFS, Boolean forced) {
        userVmManager.stopVirtualMachine(sharedFS.getVmId(), Boolean.TRUE.equals(forced));
        return true;
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
        org.apache.cloudstack.storage.sharedfs.SharedFSCreationContext c = org.apache.cloudstack.storage.sharedfs.SharedFSCreationContext.read(sharedFS.getCreationContext());
        if (c != null && c.existing && !c.cancelled)
            throw new CloudRuntimeException("Imported DATA is protected; cancel initial preparation before deleting the allocation");
        Long vmId = sharedFS.getVmId();
        Long volumeId = sharedFS.getVolumeId();
        if (vmId != null) {
            expungeVm(vmId);
        }

        if (volumeId == null) {
            return true;
        }
        VolumeVO volume = volumeDao.findById(volumeId);
        Boolean expunge = false;
        Boolean forceExpunge = false;
        if (volume.getState() == Volume.State.Allocated) {
            expunge = true;
            forceExpunge = true;
        }
        volumeApiService.destroyVolume(volume.getId(), CallContext.current().getCallingAccount(), expunge, forceExpunge, null);
        return true;
    }

    @Override
    public boolean reDeploySharedFS(SharedFS sharedFS) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        org.apache.cloudstack.storage.sharedfs.SharedFSCreationContext c = org.apache.cloudstack.storage.sharedfs.SharedFSCreationContext.read(sharedFS.getCreationContext());
        if (c != null && c.existing) throw new CloudRuntimeException("Cleanup redeploy is unavailable for imported backing DATA");
        UserVm vm =  virtualMachineManager.restoreVirtualMachine(sharedFS.getVmId(), null, null, true, null);
        return (vm != null);
    }

    @Override
    public boolean changeSharedFSServiceOffering(SharedFS sharedFS, Long serviceOfferingId) throws ManagementServerException, ResourceUnavailableException, VirtualMachineMigrationException {
        return userVmManager.upgradeVirtualMachine(sharedFS.getVmId(), serviceOfferingId, new HashMap<String, String>());
    }
}
