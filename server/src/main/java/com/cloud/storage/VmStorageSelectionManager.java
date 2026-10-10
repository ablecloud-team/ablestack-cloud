/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.storage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.inject.Inject;

import org.apache.cloudstack.api.command.admin.storage.ListDeploymentStoragePoolsCmd;
import org.apache.cloudstack.api.command.user.vm.BaseDeployVMCmd;
import org.apache.cloudstack.api.response.DeploymentStoragePoolResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.springframework.stereotype.Component;

import com.cloud.capacity.CapacityManager;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceState;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.VmDiskInfo;
import com.cloud.uservm.UserVm;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.storage.Storage.ImageFormat;

@Component
public class VmStorageSelectionManager extends ManagerBase implements VmStorageSelectionService {
    public static class Selection {
        private final Map<Long, Long> pools;
        public Selection(Map<Long, Long> pools) { this.pools = Collections.unmodifiableMap(new HashMap<>(pools)); }
        public Map<Long, Long> getPools() { return pools; }
    }

    @Inject private org.apache.cloudstack.api.ResponseGenerator responseGenerator;
    @Inject private PrimaryDataStoreDao poolDao;
    @Inject private VolumeDao volumeDao;
    @Inject private VolumeDetailsDao detailsDao;
    @Inject private DiskOfferingDao offeringDao;
    @Inject private ServiceOfferingDao computeDao;
    @Inject private VMTemplateDao templateDao;
    @Inject private DataCenterDao zoneDao;
    @Inject private HostDao hostDao;
    @Inject private AccountManager accountManager;
    @Inject private ConfigurationManager configurationManager;
    @Inject private CapacityManager capacityManager;
    @Inject private StorageManager storageManager;
    @Inject private com.cloud.vm.VmCreationSourceService creationSources;
    @Inject private com.cloud.storage.dao.SnapshotDao snapshots;
    @Inject private com.cloud.storage.dao.SnapshotDetailsDao snapshotDetails;
    private List<StoragePoolAllocator> allocators;
    public void setStoragePoolAllocators(List<StoragePoolAllocator> values) {
        allocators = values;
    }

    private VMTemplateVO executionTemplate() {
        VMTemplateVO profile = VMTemplateVO.createSystemIso(-1L, com.cloud.vm.VirtualMachineManager.KVM_BLANK_VM_TEMPLATE_NAME,
                "Generic volume execution profile", true, "", true, 64, Account.ACCOUNT_ID_SYSTEM, "", "Volume execution profile", false, 1);
        profile.setFormat(ImageFormat.QCOW2); profile.setHypervisorType(HypervisorType.KVM); profile.setDetails(new HashMap<>());
        return profile;
    }

    @Override
    public List<DeploymentStoragePoolResponse> listPools(ListDeploymentStoragePoolsCmd cmd) {
        DataCenter zone = zoneDao.findById(cmd.getZoneId());
        ServiceOffering compute = computeDao.findById(cmd.getServiceOfferingId());
        if ((cmd.getTemplateId() == null ? 0 : 1) + (cmd.getSnapshotId() == null ? 0 : 1) + (cmd.getVolumeId() == null ? 0 : 1) != 1) {
            throw new InvalidParameterValueException("Specify exactly one templateid, snapshotid or volumeid");
        }
        VolumeVO sourceVolume = cmd.getVolumeId() == null ? null : volumeDao.findById(cmd.getVolumeId());
        if (cmd.getVolumeId() != null) {
            if (sourceVolume == null || sourceVolume.getDataCenterId() != cmd.getZoneId()) { throw new InvalidParameterValueException("Invalid creation volume"); }
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, sourceVolume);
        }
        SnapshotVO sourceSnapshot = cmd.getSnapshotId() == null ? null : snapshots.findById(cmd.getSnapshotId());
        Long sourceTemplateId = cmd.getTemplateId();
        if (cmd.getSnapshotId() != null) {
            if (sourceSnapshot == null) { throw new InvalidParameterValueException("Snapshot does not exist"); }
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, sourceSnapshot);
            com.cloud.storage.dao.SnapshotDetailsVO recorded = snapshotDetails.findDetail(sourceSnapshot.getId(), com.cloud.vm.VmCreationSourceService.PREFIX + "templateid");
            VolumeVO original = volumeDao.findByIdIncludingRemoved(sourceSnapshot.getVolumeId());
            sourceTemplateId = recorded == null ? original == null ? null : original.getTemplateId() : Long.valueOf(recorded.getValue());
        }
        com.cloud.storage.VMTemplateVO template = sourceTemplateId == null ? null : templateDao.findByIdIncludingRemoved(sourceTemplateId);
        if (template == null && (sourceVolume != null || sourceSnapshot != null)) { template = executionTemplate(); }
        if (zone == null || compute == null || template == null) {
            throw new InvalidParameterValueException("Invalid zone, compute offering or image");
        }
        configurationManager.checkZoneAccess(CallContext.current().getCallingAccount(), zone);
        if (template.getId() > 0) { templateDao.loadDetails(template); }
        Long offeringId = cmd.getDiskOfferingId() == null ? compute.getDiskOfferingId() : cmd.getDiskOfferingId();
        DiskOffering offering = offeringDao.findById(offeringId);
        if (offering == null) { throw new InvalidParameterValueException("Invalid disk offering"); }
        configurationManager.checkDiskOfferingAccess(CallContext.current().getCallingAccount(), offering, zone);
        if (cmd.isRootDisk() && Boolean.TRUE.equals(compute.getDiskOfferingStrictness()) && !compute.getDiskOfferingId().equals(offeringId)) {
            throw new InvalidParameterValueException("The compute offering requires its mapped root disk offering");
        }
        if (cmd.getDiskCount() < 1 || cmd.getVmCount() < 1 || cmd.getVmCount() > 50 || cmd.getOtherRequiredBytes() < 0 || cmd.getOtherRequiredIops() < 0 || (cmd.getMinIops() != null && cmd.getMinIops() < 0)) {
            throw new InvalidParameterValueException("Invalid disk or VM count / requested capacity");
        }
        HypervisorType hypervisor = hypervisor(template, cmd.getHypervisor());
        Long size = sourceVolume != null && cmd.isRootDisk() ? sourceVolume.getSize() : sourceSnapshot != null && cmd.isRootDisk() ? sourceSnapshot.getSize() : requestedSize(offering, template, cmd.isRootDisk(), cmd.getSize());
        Long required = size == null ? null : multiply(size, (long) cmd.getDiskCount() * cmd.getVmCount());
        DiskProfile disk = profile(offering, cmd.isRootDisk(), 1, hypervisor, template.getId(), cmd.getMinIops());
        VirtualMachineProfile vm = vmProfile(zone, compute, template, hypervisor, CallContext.current().getCallingAccount());
        Map<Long, Set<String>> pools = candidates(disk, vm, cmd.getPodId(), cmd.getClusterId(), cmd.getHostId());
        StoragePool other = cmd.getOtherStorageId() == null ? null : poolDao.findById(cmd.getOtherStorageId());
        if (cmd.getOtherStorageId() != null && (other == null || other.getDataCenterId() != zone.getId())) {
            throw new InvalidParameterValueException("Invalid other storage pool");
        }
        List<DeploymentStoragePoolResponse> responses = new ArrayList<>();
        for (Map.Entry<Long, Set<String>> entry : pools.entrySet()) {
            StoragePoolVO pool = poolDao.findById(entry.getKey());
            Set<String> hosts = entry.getValue();
            if (other != null) {
                hosts.retainAll(accessibleHosts(other, vm, cmd.getPodId(), cmd.getClusterId(), cmd.getHostId()));
                if (hosts.isEmpty()) { continue; }
            }
            Long requested = required;
            if (other != null && other.getId() == pool.getId() && required != null) { requested = add(required, cmd.getOtherRequiredBytes()); }
            long allocated = capacityManager.getAllocatedPoolCapacity(pool, null);
            Long available = availableBytes(pool, allocated);
            Long physical = pool.isManaged() || pool.getUsedBytes() < 0 ? null : Math.max(0L, pool.getCapacityBytes() - pool.getUsedBytes());
            DeploymentStoragePoolResponse response = new DeploymentStoragePoolResponse();
            response.setPool(pool, allocated, physical, available, requested, new ArrayList<>(hosts), pool.getScope().toString());
            Long perDiskIops = cmd.getMinIops() == null ? offering.getMinIops() : cmd.getMinIops();
            long requiredIops = perDiskIops == null ? 0 : multiply(perDiskIops, (long) cmd.getDiskCount() * cmd.getVmCount());
            if (other != null && other.getId() == pool.getId()) { requiredIops = add(requiredIops, cmd.getOtherRequiredIops()); }
            response.setIopsSufficient(storageManager.storagePoolHasEnoughIops(requiredIops, pool));
            response.setDiskOffering(responseGenerator.createDiskOfferingResponse(offering));
            responses.add(response);
        }
        return responses;
    }

    protected Long availableBytes(StoragePool pool, long allocated) {
        if (pool.getCapacityBytes() < 0) { return null; }
        double physicalThreshold = CapacityManager.StorageCapacityDisableThreshold.valueIn(pool.getId());
        if (pool.getCapacityBytes() > 0 && (double) pool.getUsedBytes() / pool.getCapacityBytes() > physicalThreshold) { return 0L; }
        double factor = pool.getPoolType().supportsOverProvisioning() ? CapacityManager.StorageOverprovisioningFactor.valueIn(pool.getId()) : 1;
        BigDecimal ceiling = BigDecimal.valueOf(pool.getCapacityBytes()).multiply(BigDecimal.valueOf(factor))
                .multiply(BigDecimal.valueOf(CapacityManager.StorageAllocatedCapacityDisableThreshold.valueIn(pool.getId())));
        return Math.max(0L, ceiling.min(BigDecimal.valueOf(Long.MAX_VALUE)).longValue() - allocated);
    }

    private long multiply(long bytes, long count) {
        try { return Math.multiplyExact(bytes, count); }
        catch (ArithmeticException e) { throw new InvalidParameterValueException("Requested disk capacity is too large"); }
    }
    private long add(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException e) { throw new InvalidParameterValueException("Requested disk capacity is too large"); }
    }
    private HypervisorType hypervisor(VirtualMachineTemplate template, String value) {
        HypervisorType type = template.getHypervisorType();
        if (template.getFormat() == ImageFormat.ISO) { type = value == null ? HypervisorType.None : HypervisorType.getType(value); }
        if (type == null || type == HypervisorType.None || type == HypervisorType.External) {
            throw new InvalidParameterValueException("A supported deployment hypervisor is required");
        }
        return type;
    }
    private Long requestedSize(DiskOffering offering, VMTemplateVO template, boolean root, Long sizeGb) {
        if (sizeGb != null && sizeGb <= 0) { throw new InvalidParameterValueException("Disk size must be positive"); }
        if (offering.isCustomized() && sizeGb == null && (!root || template.getFormat() == ImageFormat.ISO)) { return null; }
        long bytes = sizeGb != null ? multiply(sizeGb, 1024L * 1024 * 1024) : offering.getDiskSize();
        if (!offering.isCustomized() && !root) { bytes = offering.getDiskSize(); }
        if (root && template.getFormat() != ImageFormat.ISO) { bytes = Math.max(bytes, template.getSize() == null ? 0 : template.getSize()); }
        return bytes;
    }
    private DiskProfile profile(DiskOffering offering, boolean root, long size, HypervisorType type, Long templateId, Long minIops) {
        DiskProfile profile = new DiskProfile(Volume.DISK_OFFERING_SUITABILITY_CHECK_VOLUME_ID, root ? Volume.Type.ROOT : Volume.Type.DATADISK,
                "deployment-candidate", offering.getId(), size, offering.getTagsArray(), offering.isUseLocalStorage(), false,
                root ? templateId : null, offering.getEncrypt());
        profile.setHyperType(type);
        profile.setProvisioningType(offering.getProvisioningType());
        profile.setMinIops(minIops == null ? offering.getMinIops() : minIops);
        return profile;
    }
    private VirtualMachineProfile vmProfile(DataCenter zone, ServiceOffering offering, VirtualMachineTemplate template, HypervisorType type, Account owner) {
        UserVmVO vm = new UserVmVO(-1L, "deployment-candidate", "deployment-candidate", template.getId(), type, template.getGuestOSId(), false, false,
                owner.getDomainId(), owner.getId(), CallContext.current().getCallingUserId(), offering.getId(), null, null, null, "deployment-candidate");
        vm.setDataCenterId(zone.getId());
        return new VirtualMachineProfileImpl(vm, template, offering, owner, null);
    }
    private List<HostVO> hosts(VirtualMachineProfile vm, Long podId, Long clusterId, Long hostId) {
        List<HostVO> result = new ArrayList<>();
        for (HostVO host : hostDao.listAllRoutingHostsByZoneAndHypervisorType(vm.getVirtualMachine().getDataCenterId(), vm.getHypervisorType())) {
            if (host.getStatus() != Status.Up || host.getResourceState() != ResourceState.Enabled || host.getType() != Host.Type.Routing) { continue; }
            if (podId != null && !podId.equals(host.getPodId()) || clusterId != null && !clusterId.equals(host.getClusterId()) || hostId != null && !hostId.equals(host.getId())) { continue; }
            if (!host.checkHostServiceOfferingAndTemplateTags(vm.getServiceOffering(), vm.getTemplate(), UserVmManager.getStrictHostTags())) { continue; }
            result.add(host);
        }
        return result;
    }
    private Set<String> accessibleHosts(StoragePool pool, VirtualMachineProfile vm, Long podId, Long clusterId, Long hostId) {
        Set<String> result = new HashSet<>();
        for (HostVO host : hosts(vm, podId, clusterId, hostId)) {
            if (pool.getStatus() != StoragePoolStatus.Up || pool.getDataCenterId() != host.getDataCenterId()) { continue; }
            if (pool.getClusterId() != null && !pool.getClusterId().equals(host.getClusterId())) { continue; }
            if (!pool.isShared() && !storageManager.getUpHostsInPool(pool.getId()).contains(host.getId())) { continue; }
            if (storageManager.checkIfHostAndStoragePoolHasCommonStorageAccessGroups(host, pool)) { result.add(host.getUuid()); }
        }
        return result;
    }
    private Map<Long, Set<String>> candidates(DiskProfile disk, VirtualMachineProfile vm, Long podId, Long clusterId, Long hostId) {
        Map<Long, Set<String>> result = new TreeMap<>();
        for (HostVO host : hosts(vm, podId, clusterId, hostId)) {
            DataCenterDeployment plan = new DataCenterDeployment(host.getDataCenterId(), host.getPodId(), host.getClusterId(), host.getId(), null, null);
            for (StoragePoolAllocator allocator : allocators) {
                List<StoragePool> pools = allocator.allocateToPool(disk, vm, plan, new ExcludeList(), StoragePoolAllocator.RETURN_UPTO_ALL);
                if (pools == null) { continue; }
                for (StoragePool pool : pools) {
                    if (pool.getStatus() == StoragePoolStatus.Up && accessibleHosts(pool, vm, podId, clusterId, hostId).contains(host.getUuid())) {
                        result.computeIfAbsent(pool.getId(), id -> new TreeSet<>()).add(host.getUuid());
                    }
                }
            }
        }
        return result;
    }
    @Override
    public Map<Long, Long> prepare(BaseDeployVMCmd cmd, DataCenter zone, Account owner, ServiceOffering compute, VirtualMachineTemplate template) {
        Map<Long, Long> selections = new HashMap<>();
        if (cmd.getRootStorageId() != null) { selections.put(0L, cmd.getRootStorageId()); }
        List<VmDiskInfo> disks = cmd.getDataDiskInfoList();
        if (disks != null) {
            for (VmDiskInfo disk : disks) {
                if (disk.getStoragePoolId() != null) { selections.put(disk.getDeviceId(), disk.getStoragePoolId()); }
            }
        }
        if (selections.isEmpty()) { return selections; }
        if (!accountManager.isRootAdmin(CallContext.current().getCallingAccount().getId())) {
            throw new PermissionDeniedException("Direct primary storage selection requires administrator permission");
        }
        org.apache.cloudstack.api.command.user.vm.DeployVMCmd sourceCmd = cmd instanceof org.apache.cloudstack.api.command.user.vm.DeployVMCmd
                ? (org.apache.cloudstack.api.command.user.vm.DeployVMCmd) cmd : null;
        VolumeVO adopting = sourceCmd == null || sourceCmd.getVolumeId() == null ? null : volumeDao.findById(sourceCmd.getVolumeId());
        if (adopting != null && adopting.getState() != Volume.State.Uploaded && selections.containsKey(0L)) {
            throw new InvalidParameterValueException("Existing primary volume storage is fixed");
        }
        SnapshotVO restoring = sourceCmd == null || sourceCmd.getSnapshotId() == null ? null : snapshots.findById(sourceCmd.getSnapshotId());
        configurationManager.checkZoneAccess(owner, zone);
        for (Long id : selections.values()) {
            StoragePool pool = poolDao.findById(id);
            if (pool == null || pool.getDataCenterId() != zone.getId() || pool.getStatus() != StoragePoolStatus.Up) {
                throw new InvalidParameterValueException("Selected primary storage is unavailable or outside the deployment zone");
            }
        }
        VMTemplateVO storedTemplate = templateDao.findByIdIncludingRemoved(template.getId());
        templateDao.loadDetails(storedTemplate);
        HypervisorType type = hypervisor(storedTemplate, cmd.getHypervisor() == null ? null : cmd.getHypervisor().toString());
        VirtualMachineProfile vmProfile = vmProfile(zone, compute, storedTemplate, type, owner);
        Map<Long, Long> totals = new HashMap<>();
        Set<String> commonHosts = null;
        for (Map.Entry<Long, Long> choice : selections.entrySet()) {
            boolean root = choice.getKey() == 0L;
            DiskOffering offering;
            Long sizeGb;
            if (root) {
                Long rootOffering = storedTemplate.getFormat() == ImageFormat.ISO ? cmd.getDiskOfferingId()
                        : cmd.getOverrideDiskOfferingId() == null ? compute.getDiskOfferingId() : cmd.getOverrideDiskOfferingId();
                offering = offeringDao.findById(rootOffering);
                String requestedRootSize = cmd.getDetails().get(VmDetailConstants.ROOT_DISK_SIZE);
                sizeGb = storedTemplate.getFormat() == ImageFormat.ISO ? cmd.getSize() : requestedRootSize == null ? null : Long.valueOf(requestedRootSize);
            } else {
                VmDiskInfo diskInfo = disks.stream().filter(disk -> choice.getKey().equals(disk.getDeviceId())).findFirst().orElseThrow(
                        () -> new InvalidParameterValueException("Missing selected data disk"));
                offering = diskInfo.getDiskOffering();
                sizeGb = diskInfo.getSize();
            }
            if (offering == null) { throw new InvalidParameterValueException("Invalid selected disk offering"); }
            configurationManager.checkDiskOfferingAccess(owner, offering, zone);
            Long bytes = adopting != null && root ? adopting.getSize() : restoring != null && root ? restoring.getSize() : requestedSize(offering, storedTemplate, root, sizeGb);
            if (bytes == null || bytes <= 0) { throw new InvalidParameterValueException("Disk size must be specified before selecting storage"); }
            DiskProfile disk = profile(offering, root, bytes, type, storedTemplate.getId(), null);
            Set<String> eligible = candidates(disk, vmProfile, null, null, cmd.getHostId()).get(choice.getValue());
            if (eligible == null || eligible.isEmpty()) {
                throw new InvalidParameterValueException("Selected storage is incompatible with disk " + choice.getKey() + " or deployment hosts");
            }
            if (commonHosts == null) { commonHosts = new HashSet<>(eligible); } else { commonHosts.retainAll(eligible); }
            totals.put(choice.getValue(), add(totals.getOrDefault(choice.getValue(), 0L), bytes));
        }
        if (commonHosts == null || commonHosts.isEmpty()) { throw new InvalidParameterValueException("Selected disks require a common deployment host"); }
        for (Map.Entry<Long, Long> total : totals.entrySet()) {
            if (!storageManager.storagePoolHasEnoughSpace(total.getValue(), poolDao.findById(total.getKey()))) {
                throw new InvalidParameterValueException("Selected storage has insufficient capacity for all requested disks");
            }
        }
        return selections;
    }
    @Override
    public void bind(UserVm vm, Map<Long, Long> selections) {
        if (selections == null || selections.isEmpty()) { return; }
        Account owner = accountManager.getAccount(vm.getAccountId());
        DataCenter zone = zoneDao.findById(vm.getDataCenterId());
        ServiceOffering compute = computeDao.findById(vm.getServiceOfferingId());
        com.cloud.storage.VMTemplateVO template = templateDao.findById(vm.getTemplateId());
        if (template == null) { template = templateDao.findByIdIncludingRemoved(vm.getTemplateId()); }
        templateDao.loadDetails(template);
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm, template, compute, owner, null);
        Map<Long, Long> totals = new HashMap<>();
        Map<Long, Long> iopsTotals = new HashMap<>();
        Set<String> commonHosts = null;
        Set<Long> matched = new HashSet<>();
        List<VolumeVO> volumes = volumeDao.findUsableVolumesForInstance(vm.getId());
        // Preserve every chosen target before any race-time validation can fail. Soft-deleted
        // pools retain their UUID so a later start fails closed instead of losing the intent.
        for (VolumeVO volume : volumes) {
            Long poolId = selections.get(volume.getVolumeType() == Volume.Type.ROOT ? 0L : volume.getDeviceId());
            if (poolId == null) { continue; }
            StoragePoolVO selected = poolDao.findByIdIncludingRemoved(poolId);
            if (selected == null) { throw new InvalidParameterValueException("Selected storage no longer exists"); }
            detailsDao.addDetail(volume.getId(), REQUIRED_POOL, selected.getUuid(), false);
        }
        for (VolumeVO volume : volumes) {
            Long device = volume.getVolumeType() == Volume.Type.ROOT ? 0L : volume.getDeviceId();
            Long poolId = selections.get(device);
            if (poolId == null) { continue; }
            matched.add(device);
            DiskOffering offering = offeringDao.findById(volume.getDiskOfferingId());
            configurationManager.checkDiskOfferingAccess(owner, offering, zone);
            DiskProfile disk = profile(offering, volume.getVolumeType() == Volume.Type.ROOT, volume.getSize(), vm.getHypervisorType(), template.getId(), volume.getMinIops());
            Map<Long, Set<String>> available = candidates(disk, profile, null, null, vm.getHostId());
            Set<String> hosts = available.get(poolId);
            if (hosts == null || hosts.isEmpty()) { throw new InvalidParameterValueException("Selected storage is incompatible with disk " + device + " or deployment hosts"); }
            if (commonHosts == null) { commonHosts = new HashSet<>(hosts); } else { commonHosts.retainAll(hosts); }
            totals.put(poolId, add(totals.getOrDefault(poolId, 0L), volume.getSize()));
            if (volume.getMinIops() != null) { iopsTotals.put(poolId, add(iopsTotals.getOrDefault(poolId, 0L), volume.getMinIops())); }
        }
        if (!matched.equals(selections.keySet()) || commonHosts == null || commonHosts.isEmpty()) {
            throw new InvalidParameterValueException("Selected disks cannot be deployed together on a common host");
        }
        for (Map.Entry<Long, Long> total : totals.entrySet()) {
            if (!storageManager.storagePoolHasEnoughSpace(total.getValue(), poolDao.findById(total.getKey()))) {
                throw new InvalidParameterValueException("Selected storage has insufficient capacity for all requested disks");
            }
        }
        for (Map.Entry<Long, Long> entry : iopsTotals.entrySet()) {
            if (!storageManager.storagePoolHasEnoughIops(entry.getValue(), poolDao.findById(entry.getKey()))) {
                throw new InvalidParameterValueException("Selected storage has insufficient IOPS for all requested disks");
            }
        }

        // Allocated volumes reserve capacity in the selected pool; the planner may clear this field,
        // while the immutable placement detail continues to enforce the first-deployment target.
        for (VolumeVO volume : volumeDao.findUsableVolumesForInstance(vm.getId())) {
            Long poolId = selections.get(volume.getVolumeType() == Volume.Type.ROOT ? 0L : volume.getDeviceId());
            if (poolId != null && volume.getState() == Volume.State.Allocated) {
                volume.setPoolId(poolId);
                volumeDao.update(volume.getId(), volume);
            }
        }
    }
    @Override
    public Long requiredPool(Volume volume, VirtualMachine vm) {
        if (vm.getLastHostId() != null) { return null; }
        VolumeDetailVO detail = detailsDao.findDetail(volume.getId(), REQUIRED_POOL);
        if (detail == null) { return null; }
        StoragePool pool = poolDao.findByUuid(detail.getValue());
        if (pool == null || pool.getStatus() != StoragePoolStatus.Up || pool.getDataCenterId() != volume.getDataCenterId()) {
            throw new InvalidParameterValueException("Required first-deployment storage is unavailable: " + detail.getValue());
        }
        return pool.getId();
    }
}
