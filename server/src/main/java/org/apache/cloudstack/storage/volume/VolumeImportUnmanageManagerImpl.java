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

package org.apache.cloudstack.storage.volume;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVolumesOnStorageAnswer;
import com.cloud.agent.api.GetVolumesOnStorageCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.offering.DiskOffering;
import com.cloud.resourcelimit.ReservationHelper;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplatePoolDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DiskProfile;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.admin.volume.ImportVolumeCmd;
import org.apache.cloudstack.api.command.admin.volume.ListVolumesForImportCmd;
import org.apache.cloudstack.api.command.admin.volume.UnmanageVolumeCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VolumeForImportResponse;
import org.apache.cloudstack.api.response.VolumeResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.bytescale.ByteScaleUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class VolumeImportUnmanageManagerImpl implements VolumeImportUnmanageService {
    protected Logger logger = LogManager.getLogger(VolumeImportUnmanageManagerImpl.class);

    @Inject
    private AccountManager accountMgr;
    @Inject
    private AgentManager agentManager;
    @Inject
    private HostDao hostDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private ResponseGenerator responseGenerator;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private com.cloud.storage.dao.VolumeDetailsDao volumeDetailsDao;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private StoragePoolHostDao storagePoolHostDao;
    @Inject
    private ConfigurationManager configMgr;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private VolumeOrchestrationService volumeManager;
    @Inject
    private VMTemplatePoolDao templatePoolDao;
    @Inject
    private VolumeApiService volumeApiService;
    @Inject
    private SnapshotDataStoreDao snapshotDataStoreDao;

    @Inject private org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Inject private com.cloud.vm.dao.VMInstanceDao vmInstanceDao;

    static final String DEFAULT_DISK_OFFERING_NAME = "Default Custom Offering for Volume Import";
    static final String DEFAULT_DISK_OFFERING_UNIQUE_NAME = "Volume-Import";
    static final String DISK_OFFERING_NAME_SUFFIX_LOCAL = " - Local Storage";
    static final String DISK_OFFERING_UNIQUE_NAME_SUFFIX_LOCAL = "-Local";

    protected void logFailureAndThrowException(String msg) {
        logger.error(msg);
        throw new CloudRuntimeException(msg);
    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        cmdList.add(ListVolumesForImportCmd.class);
        cmdList.add(ImportVolumeCmd.class);
        cmdList.add(UnmanageVolumeCmd.class);
        cmdList.add(org.apache.cloudstack.api.command.admin.volume.VerifyImportedVolumeAllocationCmd.class);
        return cmdList;
    }

    @Override
    public ListResponse<VolumeForImportResponse> listVolumesForImport(ListVolumesForImportCmd cmd) {
        Long poolId = cmd.getStorageId();
        String path = cmd.getPath();
        String keyword = cmd.getKeyword();
        if (StringUtils.isNotBlank(keyword)) {
            keyword = keyword.trim();
        }

        StoragePoolVO pool = checkIfPoolAvailable(poolId);
        List<VolumeOnStorageTO> volumes = listVolumesForImportInternal(pool, path, keyword);

        List<VolumeForImportResponse> responses = new ArrayList<>();
        for (VolumeOnStorageTO volume : volumes) {
            if (checkIfVolumeManaged(pool, volume.getPath())
                    || checkIfVolumeForTemplate(pool, volume.getPath())
                    || checkIfVolumeForSnapshot(pool, volume.getFullPath())) {
                continue;
            }
            responses.add(createVolumeForImportResponse(volume, pool));
        }
        ListResponse<VolumeForImportResponse> listResponses = new ListResponse<>();
        listResponses.setResponses(responses, responses.size());
        return listResponses;
    }

    @Override
    public VolumeResponse importVolume(ImportVolumeCmd cmd) {
        // 1. verify owner
        final Account caller = CallContext.current().getCallingAccount();
        if (caller.getType() != Account.Type.ADMIN) {
            throw new PermissionDeniedException(String.format("Cannot import volume as the caller account [%s] is not ROOT Admin.", caller.getUuid()));
        }
        Account owner = accountMgr.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
        if (owner == null) {
            logFailureAndThrowException("Cannot import volume due to unknown owner");
        }

        // 2. check if pool exists and not in maintenance
        Long poolId = cmd.getStorageId();
        StoragePoolVO pool = checkIfPoolAvailable(poolId);

        if (cmd.getAllocationPolicy() != null || cmd.getAllocationFormat() != null || cmd.getAllocationRequestId() != null ||
                cmd.getAllocationContractVersion() != null || cmd.isAllocationAllowCopy()) {
            return importAllocatedVolume(cmd, owner, pool);
        }

        String volumePath = cmd.getPath();
        if (StringUtils.isBlank(volumePath)) {
            throw new InvalidParameterValueException("Volume path is required");
        }

        VolumeOnStorageTO selected = getVolumeOnStorageAndCheck(pool, volumePath);
        GlobalLock sourceLock = getImportSourceLock(pool, selected.getPath());
        boolean acquired = false;
        try {
            if (!(acquired = sourceLock.lock(30))) {
                throw new CloudRuntimeException("Volume source is busy. Retry after the current operation completes.");
            }
            if (checkIfVolumeManaged(pool, selected.getPath())) {
                throw new InvalidParameterValueException("Volume is already managed by CloudStack: " + selected.getPath());
            }
            if (checkIfVolumeForTemplate(pool, selected.getPath())) {
                throw new InvalidParameterValueException("Volume is a base image of a template: " + selected.getPath());
            }
            VolumeOnStorageTO volume = getVolumeOnStorageAndCheck(pool, selected.getPath());
            verifySourceIdentity(cmd.getSourceIdentity(), selected, volume);
            if (checkIfVolumeForSnapshot(pool, volume.getFullPath())) {
                throw new InvalidParameterValueException("Volume is a reference of snapshot on primary: " + volume.getFullPath());
            }

            return registerVolume(cmd, owner, pool, volume);
        } finally {
            if (acquired) {
                sourceLock.unlock();
            }
            sourceLock.releaseRef();
        }
    }

    protected GlobalLock getImportSourceLock(StoragePoolVO pool, String path) {
        UUID lockId = UUID.nameUUIDFromBytes((pool.getUuid() + "/" + path).getBytes(StandardCharsets.UTF_8));
        return GlobalLock.getInternLock("allocation-source-" + lockId);
    }

    protected void verifySourceIdentity(String expectedIdentity, VolumeOnStorageTO selected, VolumeOnStorageTO current) {
        String selectedIdentity = sourceIdentity(selected);
        String currentIdentity = sourceIdentity(current);
        if (!Objects.equals(selected.getPath(), current.getPath()) || !Objects.equals(selected.getFormat(), current.getFormat())
                || selected.getVirtualSize() != current.getVirtualSize() || !Objects.equals(selectedIdentity, currentIdentity)
                || (StringUtils.isNotBlank(expectedIdentity) && !expectedIdentity.equals(currentIdentity))) {
            throw new InvalidParameterValueException("Volume source changed. Refresh the list and select the source again.");
        }
    }

    private String sourceIdentity(VolumeOnStorageTO volume) {
        Map<VolumeOnStorageTO.Detail, String> details = volume.getDetails();
        return details == null ? null : details.get(VolumeOnStorageTO.Detail.SOURCE_IDENTITY);
    }

    private VolumeResponse registerVolume(ImportVolumeCmd cmd, Account owner, StoragePoolVO pool, VolumeOnStorageTO volume) {
        DiskOfferingVO diskOffering = getOrCreateDiskOffering(owner, cmd.getDiskOfferingId(), pool.getDataCenterId(), pool.isLocal());
        if (diskOffering.isCustomized()) {
            volumeApiService.validateCustomDiskOfferingSizeRange(volume.getVirtualSize() / ByteScaleUtils.GiB);
        }
        if (!volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering)) {
            throw new InvalidParameterValueException(String.format("Disk offering %s storage tags are not compatible with selected storage pool %s",
                    diskOffering.getId(), pool.getId()));
        }
        List<Reserver> reservations = new ArrayList<>();
        try {
            checkResourceLimitForImportVolume(owner, volume, diskOffering, reservations);
            String volumeName = StringUtils.defaultIfBlank(StringUtils.trim(cmd.getName()), cmd.getPath());
            VolumeVO registered = Transaction.execute((TransactionCallback<VolumeVO>) status -> {
                VolumeVO imported = importVolumeInternal(volume, diskOffering, owner, pool, volumeName);
                updateResourceLimitForVolumeImport(imported);
                return imported;
            });
            publicUsageEventForVolumeImportAndUnmanage(registered, true);
            return responseGenerator.createVolumeResponse(ResponseObject.ResponseView.Full, registered);
        } finally {
            ReservationHelper.closeAll(reservations);
        }
    }

    protected VolumeOnStorageTO getVolumeOnStorageAndCheck(StoragePoolVO pool, String volumePath) {
        // send a command to hypervisor to check
        List<VolumeOnStorageTO> volumes = listVolumesForImportInternal(pool, volumePath, null);
        if (CollectionUtils.isEmpty(volumes)) {
            logFailureAndThrowException("Cannot find volume on storage pool: " + volumePath);
        }

        VolumeOnStorageTO volume = volumes.get(0);

        if (volume.getVirtualSize() <= 0) {
            throw new InvalidParameterValueException("Source virtual size must be positive");
        }

        // Do not admit an active, encrypted or dependent source.
        checkIfVolumeIsLocked(volume);
        checkIfVolumeIsEncrypted(volume);
        checkIfVolumeHasBackingFile(volume);

        return volume;
    }

    protected List<VolumeOnStorageTO> listVolumesForImportInternal(StoragePoolVO pool, String volumePath, String keyword) {
        Pair<HostVO, String> hostAndLocalPath = findHostAndLocalPathForVolumeImport(pool);
        HostVO host = hostAndLocalPath.first();
        checkIfHostAndPoolSupported(host, pool);

        StorageFilerTO storageTO = new StorageFilerTO(pool);
        GetVolumesOnStorageCommand command = new GetVolumesOnStorageCommand(storageTO, volumePath, keyword);
        Answer answer = agentManager.easySend(host.getId(), command);
        if (answer == null || !(answer instanceof GetVolumesOnStorageAnswer)) {
            logFailureAndThrowException(String.format("Cannot get volumes on storage pool via host %s", host));
        }
        if (!answer.getResult()) {
            logFailureAndThrowException("Volume cannot be imported due to " + answer.getDetails());
        }
        return  ((GetVolumesOnStorageAnswer) answer).getVolumes();
    }


    private VolumeResponse importAllocatedVolume(ImportVolumeCmd cmd, Account owner, StoragePoolVO pool) {
        if (pool.getPoolType() != Storage.StoragePoolType.SharedMountPoint) {
            throw new com.cloud.exception.InvalidParameterValueException("ALLOCATION_IMPORT_UNSUPPORTED: qualified GFS2 file storage required");
        }
        if (cmd.getDiskOfferingId() == null) throw new com.cloud.exception.InvalidParameterValueException("ALLOCATION_POLICY_MISMATCH: explicit allocation disk offering is required");
        DiskOfferingVO offering = getOrCreateDiskOffering(owner, cmd.getDiskOfferingId(), pool.getDataCenterId(), pool.isLocal(), true);
        Map<String, String> intent = com.cloud.storage.VolumeAllocationImport.expected(
                diskOfferingDetailsDao.listDetailsKeyPairs(offering.getId()), cmd.getAllocationPolicy(), cmd.getAllocationFormat(),
                cmd.getAllocationContractVersion(), cmd.getAllocationRequestId());
        if (com.cloud.storage.VolumeAllocationImport.requiresCopy(intent.get(com.cloud.storage.VolumeAllocationPolicy.POLICY)) && !cmd.isAllocationAllowCopy()) {
            throw new com.cloud.exception.InvalidParameterValueException("ALLOCATION_EVIDENCE_REQUIRED: explicitly allow allocation copy for metadata/full");
        }
        if (!volumeApiService.doesStoragePoolSupportDiskOffering(pool, offering)) {
            throw new com.cloud.exception.InvalidParameterValueException("ALLOCATION_POLICY_MISMATCH: offering storage tags do not match pool");
        }
        String requestId = java.util.UUID.fromString(cmd.getAllocationRequestId()).toString();
        com.cloud.utils.db.GlobalLock lock = com.cloud.utils.db.GlobalLock.getInternLock("volume-allocation-import-" + requestId);
        boolean acquired = false;
        try {
            if (!(acquired = lock.lock(30))) throw new CloudRuntimeException("ALLOCATION_IMPORT_BUSY: retry the same request UUID");
            VolumeVO existing = volumeDao.findByUuid(requestId);
            if (existing != null) {
                Map<String, String> saved = volumeDetailsDao.listDetailsKeyPairs(existing.getId());
                if (existing.getRemoved() != null || existing.getState() != Volume.State.Ready || existing.getAccountId() != owner.getId() ||
                        !java.util.Objects.equals(existing.getPoolId(), pool.getId()) || existing.getDiskOfferingId() != offering.getId() ||
                        !cmd.getPath().equals(saved.get(com.cloud.storage.VolumeAllocationImport.REQUEST_PATH)) ||
                        !intent.equals(com.cloud.storage.VolumeAllocationPolicy.validateStored(saved))) {
                    throw new com.cloud.exception.InvalidParameterValueException("ALLOCATION_POLICY_MISMATCH: request UUID belongs to another import scope");
                }
                // A committed request is reconciled, never re-issued against its source image.
                return responseGenerator.createVolumeResponse(ResponseObject.ResponseView.Full, existing);
            }
            VolumeOnStorageTO source = getVolumeOnStorageAndCheck(pool, cmd.getPath());
            if (!intent.get(com.cloud.storage.VolumeAllocationPolicy.FORMAT).equalsIgnoreCase(source.getFormat())) {
                throw new com.cloud.exception.InvalidParameterValueException("ALLOCATION_POLICY_MISMATCH: inspected source format differs from offering");
            }
            GlobalLock sourceLock = getImportSourceLock(pool, source.getPath());
            boolean sourceAcquired = false;
            try {
            if (!(sourceAcquired = sourceLock.lock(30))) throw new CloudRuntimeException("ALLOCATION_IMPORT_BUSY: source is being imported");
            VolumeOnStorageTO current = getVolumeOnStorageAndCheck(pool, source.getPath());
            verifySourceIdentity(cmd.getSourceIdentity(), source, current);
            if (checkIfVolumeManaged(pool, source.getPath()) || checkIfVolumeForTemplate(pool, source.getPath()) || checkIfVolumeForSnapshot(pool, source.getFullPath())) {
                throw new com.cloud.exception.InvalidParameterValueException("ALLOCATION_IMPORT_SOURCE_MANAGED: source must be an independent unmanaged image");
            }
            if (offering.isCustomized()) volumeApiService.validateCustomDiskOfferingSizeRange(source.getVirtualSize() / ByteScaleUtils.GiB);
            List<Reserver> reservations = new ArrayList<>();
            try {
                checkResourceLimitForImportVolume(owner, source, offering, reservations);
                com.cloud.agent.api.ImportVolumeAllocationAnswer answer = executeAllocation(pool, "IMPORT", source.getPath(), null,
                        requestId, owner.getId(), source.getVirtualSize(), cmd.isAllocationAllowCopy(), intent);
                Map<String, String> evidence = new java.util.LinkedHashMap<>(answer.getAllocation());
                evidence.put(com.cloud.storage.VolumeAllocationImport.REQUEST_PATH, cmd.getPath());
                VolumeOnStorageTO adopted = answer.getVolume();
                VolumeVO registered = com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<VolumeVO>) status -> {
                    VolumeVO volume = new VolumeVO(Volume.Type.DATADISK, StringUtils.isNotBlank(cmd.getName()) ? cmd.getName().trim() : source.getName(),
                            pool.getDataCenterId(), owner.getDomainId(), owner.getId(), offering.getId(), offering.getProvisioningType(),
                            adopted.getVirtualSize(), offering.getMinIops(), offering.getMaxIops(), null);
                    volume.setUuid(requestId); volume.setPoolId(pool.getId()); volume.setPoolType(pool.getPoolType());
                    volume.setPath(adopted.getPath()); volume.setDeviceId(1L);
                    volume.setFormat(Storage.ImageFormat.valueOf(adopted.getFormat().toUpperCase(java.util.Locale.ROOT)));
                    volume.setState(Volume.State.Ready); volume = volumeDao.persist(volume);
                    for (Map.Entry<String, String> entry : evidence.entrySet()) volumeDetailsDao.addDetail(volume.getId(), entry.getKey(), entry.getValue(), false);
                    volumeDetailsDao.addDetail(volume.getId(), com.cloud.vm.VmCreationSourceService.PREFIX + "formatorigin", "driver-inspected", false);
                    updateResourceLimitForVolumeImport(volume);
                    return volume;
                });
                publicUsageEventForVolumeImportAndUnmanage(registered, true);
                return responseGenerator.createVolumeResponse(ResponseObject.ResponseView.Full, registered);
            } finally { ReservationHelper.closeAll(reservations); }
            } finally { if (sourceAcquired) sourceLock.unlock(); sourceLock.releaseRef(); }
        } finally { if (acquired) lock.unlock(); lock.releaseRef(); }
    }

    private com.cloud.agent.api.ImportVolumeAllocationAnswer executeAllocation(StoragePoolVO pool, String action, String sourcePath,
            String finalPath, String requestId, long accountId, long size, boolean allowCopy, Map<String, String> intent) {
        String format = intent.get(com.cloud.storage.VolumeAllocationPolicy.FORMAT);
        HostVO selected = null;
        for (StoragePoolHostVO link : storagePoolHostDao.listByPoolId(pool.getId())) {
            HostVO host = hostDao.findById(link.getHostId());
            if (host == null || host.getStatus() != com.cloud.host.Status.Up || host.getResourceState() != com.cloud.resource.ResourceState.Enabled || host.getHypervisorType() != Hypervisor.HypervisorType.KVM) continue;
            com.cloud.agent.api.GetVolumeAllocationCapabilitiesCommand probe = new com.cloud.agent.api.GetVolumeAllocationCapabilitiesCommand(pool.getUuid(), format, "import");
            Answer response = agentManager.easySend(host.getId(), probe);
            if (!(response instanceof com.cloud.agent.api.GetVolumeAllocationCapabilitiesAnswer) || !response.getResult()) continue;
            com.cloud.agent.api.GetVolumeAllocationCapabilitiesAnswer capability = (com.cloud.agent.api.GetVolumeAllocationCapabilitiesAnswer) response;
            if (capability.getContractVersion() != 1 || !pool.getUuid().equals(capability.getPoolUuid()) || !format.equals(capability.getFormat()) ||
                    !"import".equals(capability.getOperation()) || !"GFS2".equals(capability.getBackend())) continue;
            com.cloud.storage.VolumeAllocationPolicy.validateCapability("GFS2", format, "import", capability.getMethods());
            if (capability.getMethods().containsKey(intent.get(com.cloud.storage.VolumeAllocationPolicy.POLICY))) { selected = host; break; }
        }
        if (selected == null) throw new CloudRuntimeException("ALLOCATION_IMPORT_UNSUPPORTED: no live qualified storage worker");
        com.cloud.agent.api.ImportVolumeAllocationCommand command = new com.cloud.agent.api.ImportVolumeAllocationCommand(action, new StorageFilerTO(pool),
                sourcePath, finalPath, requestId, accountId, size, allowCopy, intent);
        Answer raw = agentManager.easySend(selected.getId(), command);
        if (!(raw instanceof com.cloud.agent.api.ImportVolumeAllocationAnswer) || !raw.getResult()) {
            throw new CloudRuntimeException("ALLOCATION_IMPORT_UNCONFIRMED: " + (raw == null ? "no Agent answer; reconcile the same request UUID" : raw.getDetails()));
        }
        com.cloud.agent.api.ImportVolumeAllocationAnswer answer = (com.cloud.agent.api.ImportVolumeAllocationAnswer) raw;
        VolumeOnStorageTO volume = answer.getVolume();
        if (answer.getContractVersion() != 1 || !requestId.equals(answer.getRequestId()) || !pool.getUuid().equals(answer.getPoolUuid()) ||
                volume == null || volume.getHypervisorType() != Hypervisor.HypervisorType.KVM || volume.getVirtualSize() != size || !format.equalsIgnoreCase(volume.getFormat()) ||
                (finalPath != null && !finalPath.equals(volume.getPath()))) throw new CloudRuntimeException("ALLOCATION_EVIDENCE_REQUIRED: mismatched Agent answer");
        com.cloud.storage.VolumeAllocationImport.validateEvidence(com.cloud.storage.VolumeAllocationPolicy.validateStored(intent), answer.getAllocation(),
                requestId, pool.getUuid(), volume.getPath(), format, allowCopy);
        return answer;
    }

    @Override
    public VolumeResponse verifyImportedAllocation(org.apache.cloudstack.api.command.admin.volume.VerifyImportedVolumeAllocationCmd cmd) {
        if (CallContext.current().getCallingAccount().getType() != Account.Type.ADMIN) throw new PermissionDeniedException("Root admin required");
        VolumeVO volume = volumeDao.findById(cmd.getId());
        if (volume == null) throw new com.cloud.exception.InvalidParameterValueException("Imported volume not found");
        Map<String, String> details = volumeDetailsDao.listDetailsKeyPairs(volume.getId());
        com.cloud.storage.VolumeAllocationImport.expected(details, cmd.getAllocationPolicy(), cmd.getAllocationFormat(), 1, cmd.getAllocationRequestId());
        if (!cmd.getAllocationRequestId().equals(details.get(com.cloud.storage.VolumeAllocationImport.REQUEST_ID))) {
            throw new CloudRuntimeException("ALLOCATION_POLICY_MISMATCH: request UUID differs");
        }
        if (volume.getInstanceId() != null && vmInstanceDao.findById(volume.getInstanceId()) != null &&
                vmInstanceDao.findById(volume.getInstanceId()).getState() == com.cloud.vm.VirtualMachine.State.Running) {
            if (!details.containsKey(com.cloud.storage.VolumeAllocationImport.VERIFIED_AT)) throw new CloudRuntimeException("ALLOCATION_EVIDENCE_REQUIRED");
            recordAllocationStarted(volume.getInstanceId());
            return responseGenerator.createVolumeResponse(ResponseObject.ResponseView.Full, volume);
        }
        if (details.containsKey(com.cloud.storage.VolumeAllocationImport.STARTED_AT)) throw new CloudRuntimeException("ALLOCATION_VERIFY_SCOPE_CLOSED: first-start contract is historical after guest use");
        verifyAllocatedVolume(volume, details);
        return responseGenerator.createVolumeResponse(ResponseObject.ResponseView.Full, volume);
    }

    private void verifyAllocatedVolume(VolumeVO volume, Map<String, String> details) {
        if (volume.getState() != Volume.State.Ready || volume.getPoolId() == null ||
                (volume.getInstanceId() != null && (vmInstanceDao.findById(volume.getInstanceId()) == null ||
                    vmInstanceDao.findById(volume.getInstanceId()).getState() != com.cloud.vm.VirtualMachine.State.Stopped))) {
            throw new CloudRuntimeException("ALLOCATION_IMAGE_ACTIVE_OR_UNAVAILABLE: final image must be stopped");
        }
        StoragePoolVO pool = checkIfPoolAvailable(volume.getPoolId());
        com.cloud.agent.api.ImportVolumeAllocationAnswer verified = executeAllocation(pool, "VERIFY",
                details.get(com.cloud.storage.VolumeAllocationImport.SOURCE_PATH), volume.getPath(),
                details.get(com.cloud.storage.VolumeAllocationImport.REQUEST_ID), volume.getAccountId(), volume.getSize(),
                "copy".equals(details.get(com.cloud.storage.VolumeAllocationImport.STRATEGY)), details);
        if (!java.util.Objects.equals(details.get(com.cloud.storage.VolumeAllocationImport.IDENTITY),
                verified.getAllocation().get(com.cloud.storage.VolumeAllocationImport.IDENTITY))) throw new CloudRuntimeException("ALLOCATION_POLICY_MISMATCH: final identity changed");
        com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Void>) status -> {
            for (Map.Entry<String, String> entry : verified.getAllocation().entrySet()) volumeDetailsDao.addDetail(volume.getId(), entry.getKey(), entry.getValue(), false);
            return null;
        });
    }

    @Override public void verifyAllocationForAssembly(long volumeId) {
        VolumeVO volume = volumeDao.findById(volumeId);
        if (volume == null || volume.getInstanceId() != null) throw new CloudRuntimeException("ALLOCATION_VOLUME_ALREADY_ATTACHED");
        Map<String, String> details = volumeDetailsDao.listDetailsKeyPairs(volumeId);
        if (details == null || !details.containsKey(com.cloud.storage.VolumeAllocationImport.REQUEST_ID)) throw new CloudRuntimeException("ALLOCATION_EVIDENCE_REQUIRED");
        verifyAllocatedVolume(volume, details);
    }

    @Override public void verifyAllocationBeforeStart(long vmId) {
        List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
        if (volumes == null) return;
        for (VolumeVO volume : volumes) {
            Map<String, String> details = volumeDetailsDao.listDetailsKeyPairs(volume.getId());
            if (details == null || !details.containsKey(com.cloud.storage.VolumeAllocationImport.REQUEST_ID) ||
                    details.containsKey(com.cloud.storage.VolumeAllocationImport.STARTED_AT)) continue;
            com.cloud.vm.VMInstanceVO vm = vmInstanceDao.findById(vmId);
            if (vm != null && vm.getState() == com.cloud.vm.VirtualMachine.State.Running &&
                    details.containsKey(com.cloud.storage.VolumeAllocationImport.VERIFIED_AT)) {
                recordAllocationStarted(vmId); continue; // Lost response after a verified first start; no offline image access.
            }
            verifyAllocatedVolume(volume, details);
        }
    }

    @Override public void recordAllocationStarted(long vmId) {
        com.cloud.vm.VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null || vm.getState() != com.cloud.vm.VirtualMachine.State.Running) return;
        List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
        if (volumes == null) return;
        for (VolumeVO volume : volumes) {
            Map<String, String> details = volumeDetailsDao.listDetailsKeyPairs(volume.getId());
            if (details == null || !details.containsKey(com.cloud.storage.VolumeAllocationImport.REQUEST_ID) ||
                    details.containsKey(com.cloud.storage.VolumeAllocationImport.STARTED_AT)) continue;
            if (!details.containsKey(com.cloud.storage.VolumeAllocationImport.VERIFIED_AT)) throw new CloudRuntimeException("ALLOCATION_EVIDENCE_REQUIRED: first start was not verified");
            volumeDetailsDao.addDetail(volume.getId(), com.cloud.storage.VolumeAllocationImport.STARTED_AT, java.time.Instant.now().toString(), false);
        }
    }

    @Override
    public boolean unmanageVolume(long volumeId) {
        VolumeVO selected = checkIfVolumeCanBeUnmanaged(volumeId);
        StoragePoolVO pool = checkIfPoolAvailable(selected.getPoolId());
        GlobalLock sourceLock = getImportSourceLock(pool, selected.getPath());
        boolean acquired = false;
        try {
            if (!(acquired = sourceLock.lock(30))) {
                throw new CloudRuntimeException("Volume source is busy. Retry after the current operation completes.");
            }
            VolumeVO volume = checkIfVolumeCanBeUnmanaged(volumeId);
            if (!Objects.equals(selected.getPoolId(), volume.getPoolId()) || !Objects.equals(selected.getPath(), volume.getPath())) {
                throw new InvalidParameterValueException("Volume location changed. Refresh before unmanaging the volume.");
            }
            getVolumeOnStorageAndCheck(pool, volume.getPath());
            Transaction.execute((TransactionCallback<Void>) status -> {
                unmanageVolumeFromDatabase(volume);
                updateResourceLimitForVolumeUnmanage(volume);
                return null;
            });
            publicUsageEventForVolumeImportAndUnmanage(volume, false);
            return true;
        } finally {
            if (acquired) {
                sourceLock.unlock();
            }
            sourceLock.releaseRef();
        }
    }

    protected StoragePoolVO checkIfPoolAvailable(Long poolId) {
        StoragePoolVO pool = primaryDataStoreDao.findById(poolId);
        if (pool == null) {
            logFailureAndThrowException(String.format("Storage pool (ID: %s) does not exist", poolId));
        }
        if (pool.isInMaintenance()) {
            logFailureAndThrowException(String.format("Storage pool %s is in maintenance", pool));
        }
        if (!StoragePoolStatus.Up.equals(pool.getStatus())) {
            logFailureAndThrowException(String.format("Storage pool %s is not Up: %s", pool, pool.getStatus()));
        }
        return pool;
    }

    protected Pair<HostVO, String> findHostAndLocalPathForVolumeImport(StoragePoolVO pool) {
        List<HostVO> hosts = new ArrayList<>();
        switch (pool.getScope()) {
            case HOST:
                return findHostAndLocalPathForVolumeImportForHostScope(pool);
            case CLUSTER:
                hosts = hostDao.findHypervisorHostInCluster((pool.getClusterId()));
                break;
            case ZONE:
                hosts = hostDao.listAllHostsUpByZoneAndHypervisor(pool.getDataCenterId(), pool.getHypervisor());
                break;
        }
        for (HostVO host : hosts) {
            StoragePoolHostVO storagePoolHostVO = storagePoolHostDao.findByPoolHost(pool.getId(), host.getId());
            if (storagePoolHostVO != null) {
                return new Pair<>(host, storagePoolHostVO.getLocalPath());
            }
        }
        logFailureAndThrowException("No host found to perform volume import");
        return null;
    }

    private Pair<HostVO, String> findHostAndLocalPathForVolumeImportForHostScope(StoragePoolVO pool) {
        List<StoragePoolHostVO> storagePoolHostVOs = storagePoolHostDao.listByPoolId(pool.getId());
        if (CollectionUtils.isNotEmpty(storagePoolHostVOs)) {
            for (StoragePoolHostVO storagePoolHostVO : storagePoolHostVOs) {
                HostVO host = hostDao.findById(storagePoolHostVO.getHostId());
                if (host != null) {
                    return new Pair<>(host, storagePoolHostVO.getLocalPath());
                }
            }
        }
        logFailureAndThrowException(String.format("No host found to perform volume import on pool: %s", pool));
        return null;
    }

    private void checkIfHostAndPoolSupported(HostVO host, StoragePoolVO pool) {
        if (!SUPPORTED_HYPERVISORS.contains(host.getHypervisorType())) {
            logFailureAndThrowException("Importing and unmanaging volume are not supported for hypervisor: " + host.getHypervisorType());
        }

        if (Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType()) && !SUPPORTED_STORAGE_POOL_TYPES_FOR_KVM.contains(pool.getPoolType())) {
            logFailureAndThrowException(String.format("Importing and unmanaging volume are not supported for pool type %s on hypervisor %s", pool.getPoolType(), host.getHypervisorType()));
        }
    }

    protected VolumeForImportResponse createVolumeForImportResponse(VolumeOnStorageTO volume, StoragePoolVO pool) {
        VolumeForImportResponse response = new VolumeForImportResponse();
        response.setPath(volume.getPath());
        response.setName(volume.getName());
        response.setFullPath(volume.getFullPath());
        response.setFormat(volume.getFormat());
        response.setSize(volume.getSize());
        response.setVirtualSize(volume.getVirtualSize());
        response.setQemuEncryptFormat(volume.getQemuEncryptFormat());
        response.setStoragePoolId(pool.getUuid());
        response.setStoragePoolName(pool.getName());
        response.setStoragePoolType(String.valueOf(pool.getPoolType()));
        response.setDetails(volume.getDetails());
        response.setObjectName("volumeforimport");
        return response;
    }

    private boolean checkIfVolumeManaged(StoragePoolVO pool, String volumePath) {
        return volumeDao.findByPoolIdAndPath(pool.getId(), volumePath) != null;
    }

    private boolean checkIfVolumeForTemplate(StoragePoolVO pool, String volumePath) {
        return templatePoolDao.findByPoolPath(pool.getId(), volumePath) != null;
    }

    private boolean checkIfVolumeForSnapshot(StoragePoolVO pool, String fullVolumePath) {
        List<String> absPathList = Arrays.asList(fullVolumePath);
        return CollectionUtils.isNotEmpty(snapshotDataStoreDao.listByStoreAndInstallPaths(pool.getId(), DataStoreRole.Primary, absPathList));
    }

    protected void checkIfVolumeIsLocked(VolumeOnStorageTO volume) {
        Map<VolumeOnStorageTO.Detail, String> volumeDetails = volume.getDetails();
        if (volumeDetails != null && volumeDetails.containsKey(VolumeOnStorageTO.Detail.IS_LOCKED)) {
            String isLocked = volumeDetails.get(VolumeOnStorageTO.Detail.IS_LOCKED);
            if (Boolean.parseBoolean(isLocked)) {
                logFailureAndThrowException("Locked volume cannot be imported or unmanaged.");
            }
        }
    }

    protected void checkIfVolumeIsEncrypted(VolumeOnStorageTO volume) {
        Map<VolumeOnStorageTO.Detail, String> volumeDetails = volume.getDetails();
        if (volumeDetails != null && volumeDetails.containsKey(VolumeOnStorageTO.Detail.IS_ENCRYPTED)) {
            String isEncrypted = volumeDetails.get(VolumeOnStorageTO.Detail.IS_ENCRYPTED);
            if (Boolean.parseBoolean(isEncrypted)) {
                logFailureAndThrowException("Encrypted volume cannot be imported or unmanaged.");
            }
        }
    }

    protected void checkIfVolumeHasBackingFile(VolumeOnStorageTO volume) {
        Map<VolumeOnStorageTO.Detail, String> volumeDetails = volume.getDetails();
        if (volumeDetails != null && volumeDetails.containsKey(VolumeOnStorageTO.Detail.BACKING_FILE)) {
            String backingFile = volumeDetails.get(VolumeOnStorageTO.Detail.BACKING_FILE);
            if (StringUtils.isNotBlank(backingFile) && !AllowImportVolumeWithBackingFile.value()) {
                logFailureAndThrowException("Volume with backing file cannot be imported or unmanaged.");
            }
        }
    }

    protected DiskOfferingVO getOrCreateDiskOffering(Account owner, Long diskOfferingId, Long zoneId, boolean isLocal) {
        return getOrCreateDiskOffering(owner, diskOfferingId, zoneId, isLocal, false);
    }

    private DiskOfferingVO getOrCreateDiskOffering(Account owner, Long diskOfferingId, Long zoneId, boolean isLocal, boolean allocationImport) {
        if (diskOfferingId != null) {
            // check if disk offering exists and active
            DiskOfferingVO diskOfferingVO = diskOfferingDao.findById(diskOfferingId);
            if (diskOfferingVO == null) {
                logFailureAndThrowException(String.format("Disk offering %s does not exist", diskOfferingId));
            }
            if (!DiskOffering.State.Active.equals(diskOfferingVO.getState())) {
                logFailureAndThrowException(String.format("Disk offering %s is not active", diskOfferingVO));
            }
            if (diskOfferingVO.isUseLocalStorage() != isLocal) {
                logFailureAndThrowException(String.format("Disk offering %s should use %s storage", diskOfferingVO, isLocal ? "local": "shared"));
            }
            if (diskOfferingVO.getEncrypt()) {
                logFailureAndThrowException(String.format("Disk offering %s should not support volume encryption", diskOfferingVO));
            }
            // check if disk offering is accessible by the account/owner
            try {
                if (allocationImport) configMgr.checkDiskOfferingAccessForImport(owner, diskOfferingVO, dcDao.findById(zoneId));
                else configMgr.checkDiskOfferingAccess(owner, diskOfferingVO, dcDao.findById(zoneId));
                return diskOfferingVO;
            } catch (PermissionDeniedException ex) {
                logFailureAndThrowException(String.format("Disk offering %s is not accessible by owner %s", diskOfferingVO, owner));
            }
        }
        return getOrCreateDefaultDiskOfferingIdForVolumeImport(isLocal);
    }

    private DiskOfferingVO getOrCreateDefaultDiskOfferingIdForVolumeImport(boolean isLocalStorage) {
        final StringBuilder diskOfferingNameBuilder = new StringBuilder(DEFAULT_DISK_OFFERING_NAME);
        final StringBuilder uniqueNameBuilder = new StringBuilder(DEFAULT_DISK_OFFERING_UNIQUE_NAME);
        if (isLocalStorage) {
            diskOfferingNameBuilder.append(DISK_OFFERING_NAME_SUFFIX_LOCAL);
            uniqueNameBuilder.append(DISK_OFFERING_UNIQUE_NAME_SUFFIX_LOCAL);
        }
        final String diskOfferingName = diskOfferingNameBuilder.toString();
        final String uniqueName = uniqueNameBuilder.toString();
        DiskOfferingVO diskOffering = diskOfferingDao.findByUniqueName(uniqueName);
        if (diskOffering != null) {
            return diskOffering;
        }
        DiskOfferingVO newDiskOffering = new DiskOfferingVO(diskOfferingName, diskOfferingName,
                Storage.ProvisioningType.THIN, 0, null, true, null, null, null);
        newDiskOffering.setUseLocalStorage(isLocalStorage);
        newDiskOffering.setUniqueName(uniqueName);
        newDiskOffering = diskOfferingDao.persistDefaultDiskOffering(newDiskOffering);
        return newDiskOffering;
    }

    private VolumeVO importVolumeInternal(VolumeOnStorageTO volume, DiskOfferingVO diskOffering,
                                          Account owner, StoragePoolVO pool, String volumeName) {
        DiskProfile diskProfile = volumeManager.importVolume(Volume.Type.DATADISK, volumeName, diskOffering,
                volume.getVirtualSize(), null, null, pool.getDataCenterId(), volume.getHypervisorType(), null, null,
                owner, null, pool.getId(), pool.getPoolType(), volume.getPath(), null);
        VolumeVO imported = volumeDao.findById(diskProfile.getVolumeId());
        if (Hypervisor.HypervisorType.KVM.equals(volume.getHypervisorType())) {
            com.cloud.storage.Storage.ImageFormat format = com.cloud.storage.Storage.ImageFormat.valueOf(volume.getFormat().toUpperCase(java.util.Locale.ROOT));
            if (format != com.cloud.storage.Storage.ImageFormat.QCOW2 && format != com.cloud.storage.Storage.ImageFormat.RAW) {
                throw new com.cloud.exception.InvalidParameterValueException("Unsupported KVM import image format");
            }
            imported.setFormat(format); volumeDao.update(imported.getId(), imported);
            volumeDetailsDao.addDetail(imported.getId(), com.cloud.vm.VmCreationSourceService.PREFIX + "formatorigin", "driver-inspected", false);
        }
        return imported;
    }

    protected void checkResourceLimitForImportVolume(Account owner, VolumeOnStorageTO volume, DiskOfferingVO diskOffering, List<Reserver> reservations) {
        Long volumeSize = volume.getVirtualSize();
        try {
            resourceLimitService.checkVolumeResourceLimit(owner, true, volumeSize, diskOffering, reservations);
        } catch (ResourceAllocationException e) {
            logger.error("VM resource allocation error for account: {}", owner, e);
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("VM resource allocation error for account: %s. %s", owner.getUuid(), StringUtils.defaultString(e.getMessage())));
        }
    }

    private void updateResourceLimitForVolumeImport(VolumeVO volumeVO) {
        resourceLimitService.incrementResourceCount(volumeVO.getAccountId(), Resource.ResourceType.volume);
        resourceLimitService.incrementResourceCount(volumeVO.getAccountId(), Resource.ResourceType.primary_storage, volumeVO.getSize());
    }

    private void publicUsageEventForVolumeImportAndUnmanage(VolumeVO volumeVO, boolean isImport) {
        try {
            String eventType = isImport ? EventTypes.EVENT_VOLUME_IMPORT: EventTypes.EVENT_VOLUME_UNMANAGE;
            String eventDescription = isImport ? "Successfully imported volume " + volumeVO.getUuid(): "Successfully unmanaged volume "  + volumeVO.getUuid();
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, volumeVO.getAccountId(), EventVO.LEVEL_INFO,
                    eventType, eventDescription, volumeVO.getId(), ApiCommandResourceType.Volume.toString(),0);
            UsageEventUtils.publishUsageEvent(eventType, volumeVO.getAccountId(), volumeVO.getDataCenterId(),
                    volumeVO.getId(), volumeVO.getName(), volumeVO.getDiskOfferingId(), null, volumeVO.getSize(),
                    Volume.class.getName(), volumeVO.getUuid(), volumeVO.isDisplayVolume());
        } catch (Exception e) {
            logger.error("Failed to publish volume: {} event or usage records during volume import/unmanage", volumeVO, e);
        }
    }

    private void updateResourceLimitForVolumeUnmanage(VolumeVO volumeVO) {
        resourceLimitService.decrementResourceCount(volumeVO.getAccountId(), Resource.ResourceType.volume);
        resourceLimitService.decrementResourceCount(volumeVO.getAccountId(), Resource.ResourceType.primary_storage, volumeVO.getSize());
    }

    private VolumeVO checkIfVolumeCanBeUnmanaged(long volumeId) {
        VolumeVO volumeVO = volumeDao.findById(volumeId);
        if (volumeVO == null) {
            logFailureAndThrowException(String.format("Volume (ID: %s) does not exist", volumeId));
        }
        if (!Volume.State.Ready.equals(volumeVO.getState())) {
            logFailureAndThrowException(String.format("Volume %s is not ready", volumeVO));
        }
        if (volumeVO.getEncryptFormat() != null) {
            logFailureAndThrowException(String.format("Volume %s is encrypted", volumeVO));
        }
        if (volumeVO.getAttached() != null || volumeVO.getInstanceId() != null) {
            logFailureAndThrowException(String.format("Volume %s is attached to VM (ID: %s)", volumeVO, volumeVO.getInstanceId()));
        }
        return volumeVO;
    }

    private void unmanageVolumeFromDatabase(VolumeVO volume) {
        volume.setState(Volume.State.Destroy);
        volume.setRemoved(new Date());
        volumeDao.update(volume.getId(), volume);
    }

    @Override
    public String getConfigComponentName() {
        return VolumeImportUnmanageManagerImpl.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                AllowImportVolumeWithBackingFile
        };
    }
}
