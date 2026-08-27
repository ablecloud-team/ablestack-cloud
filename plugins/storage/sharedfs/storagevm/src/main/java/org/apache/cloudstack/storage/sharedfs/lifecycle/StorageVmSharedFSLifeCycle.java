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

import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSVmNamePrefix;
import static org.apache.cloudstack.storage.sharedfs.provider.StorageVmSharedFSProvider.SHAREDFSVM_MIN_CPU_COUNT;
import static org.apache.cloudstack.storage.sharedfs.provider.StorageVmSharedFSProvider.SHAREDFSVM_MIN_RAM_SIZE;

import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.LaunchPermissionVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.LaunchPermissionDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.FileUtil;
import com.cloud.utils.Pair;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSLifeCycle;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.dc.DataCenter;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.resource.ResourceManager;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachineManager;

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

    private String getStorageVmName(String fileShareName) {
        String prefix = String.format("%s-%s", SharedFSVmNamePrefix, fileShareName);
        String suffix = Long.toHexString(System.currentTimeMillis());

        if (!NetUtils.verifyDomainNameLabel(prefix, true)) {
            prefix = prefix.replaceAll("[^a-zA-Z0-9-]", "");
        }
        int nameLength = prefix.length() + suffix.length() + SharedFSVmNamePrefix.length();
        if (nameLength > 63) {
            int prefixLength = prefix.length() - (nameLength - 63);
            prefix = prefix.substring(0, prefixLength);
        }
        return (String.format("%s-%s", prefix, suffix));
    }

    private UserVm deploySharedFSVM(Long zoneId, Account owner, List<Long> networkIds, String name, Long serviceOfferingId, Long diskOfferingId,
            SharedFS.FileSystemType fileSystem, Long size, Long minIops, Long maxIops, SharedFS.NetworkMode networkMode, String requestedIp) throws OperationTimedoutException,
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

        for (final Iterator<Hypervisor.HypervisorType> iter = hypervisors.iterator(); iter.hasNext();) {
            final Hypervisor.HypervisorType hypervisor = iter.next();
            VMTemplateVO template = templateDao.findSystemVMReadyTemplate(zoneId, hypervisor);
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
                        diskOfferingId, size, null, Hypervisor.HypervisorType.None, BaseCmd.HTTPMethod.POST, base64UserData,
                        null, null, keypairs, null, addrs, null, null, null,
                        customParameterMap, null, null, null, null,
                        true, UserVmManager.SHAREDFSVM, null);
                vmContext.setEventResourceId(vm.getId());
                userVmService.startVirtualMachine(vm);
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
        Account owner = accountMgr.getActiveAccountById(sharedFS.getAccountId());
        UserVm vm = deploySharedFSVM(sharedFS.getDataCenterId(), owner, List.of(networkId), sharedFS.getName(), sharedFS.getServiceOfferingId(), diskOfferingId,
                sharedFS.getFsType(), size, minIops, maxIops, sharedFS.getNetworkMode(), sharedFS.getIpAddress());

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
    public void startSharedFS(SharedFS sharedFS) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException {
        UserVmVO vm = userVmDao.findById(sharedFS.getVmId());
        userVmService.startVirtualMachine(vm);
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
        volumeApiService.destroyVolume(volume.getId(), CallContext.current().getCallingAccount(), expunge, forceExpunge);
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
