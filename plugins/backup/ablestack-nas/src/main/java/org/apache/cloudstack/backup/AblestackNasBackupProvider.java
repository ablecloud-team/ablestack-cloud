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
package org.apache.cloudstack.backup;

import com.cloud.agent.AgentManager;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.offering.DiskOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;


import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupRepositoryDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import javax.inject.Inject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.cloudstack.backup.BackupManager.BackupChainSize;
import static org.apache.cloudstack.backup.BackupManager.BackupCommandTimeout;
import static org.apache.cloudstack.backup.BackupManager.BackupRestoreTimeout;
import static org.apache.cloudstack.backup.BackupManager.BackupFrameworkEnabled;
import static org.apache.cloudstack.backup.BackupManager.KvmIncrementalBackup;

public class AblestackNasBackupProvider extends AdapterBase implements BackupProvider, Configurable {
    private static final Logger LOG = LogManager.getLogger(AblestackNasBackupProvider.class);
    private static final String BACKUP_TYPE_FULL = "FULL";
    private static final String BACKUP_TYPE_INCREMENTAL = "INCREMENTAL";
    private static final String BACKUP_ENGINE_QCOW2 = "QCOW2";
    private static final String BACKUP_ENGINE_RBD_DIFF = "RBD_DIFF";
    private static final String DETAIL_CHECKPOINT_NAME = "nas.checkpoint.name";
    private static final String DETAIL_CHECKPOINT_PATH = "nas.checkpoint.path";
    private static final String DETAIL_PARENT_BACKUP_UUID = "nas.parent.backup.uuid";
    private static final String DETAIL_PARENT_BACKUP_PATH = "nas.parent.backup.path";
    private static final String DETAIL_PARENT_CHECKPOINT_NAME = "nas.parent.checkpoint.name";
    private static final String DETAIL_PARENT_CHECKPOINT_PATH = "nas.parent.checkpoint.path";
    private static final String DETAIL_BACKUP_ENGINE = "nas.backup.engine";
    private static final String DETAIL_RBD_DISK_PATHS = "nas.rbd.disk.paths";
    private static final String DETAIL_CHAIN_SEALED = "nas.chain.sealed";
    private static final String DETAIL_CHAIN_SEAL_REASON = "nas.chain.seal.reason";
    private static final String DETAIL_FALLBACK_VOLUME_UUIDS = "nas.fallback.volume.uuids";
    private static final String DETAIL_FAILURE_PHASE = "nas.failure.phase";
    private static final String DETAIL_FAILURE_REASON = "nas.failure.reason";
    public static final String DETAIL_BACKUP_SOURCE = "nas.backup.source";
    public static final String DETAIL_VEEAM_RESTORE_POINT_ID = "nas.veeam.restore.point.id";
    public static final String DETAIL_VEEAM_IMPORTED = "nas.veeam.imported";
    private static final String MISSING_PARENT_RBD_SNAPSHOT_ERROR = "Parent RBD snapshot";
    private static final String MISSING_PARENT_QCOW2_BITMAP_ERROR = "Parent qcow2 bitmap";
    private static final long STALE_BACKUP_THRESHOLD_MS = TimeUnit.DAYS.toMillis(1);

    ConfigKey<Integer> NASBackupRestoreMountTimeout = new ConfigKey<>("Advanced", Integer.class,
            "nas.backup.restore.mount.timeout",
            "60",
            "Timeout in seconds after which backup repository mount for restore fails.",
            true,
            BackupFrameworkEnabled.key());

    ConfigKey<Integer> NASBackupRestoreTimeout = new ConfigKey<>("Advanced", Integer.class,
            "nas.backup.restore.timeout",
            "7200",
            "Timeout in seconds after which NAS backup restore operations fail.",
            true,
            BackupFrameworkEnabled.key());

    @Inject
    private BackupDao backupDao;

    @Inject
    private BackupDetailsDao backupDetailsDao;

    @Inject
    private BackupRepositoryDao backupRepositoryDao;

    @Inject
    private BackupOfferingDao backupOfferingDao;

    @Inject
    private BackupRepositoryService backupRepositoryService;

    @Inject
    private HostDao hostDao;

    @Inject
    private VolumeDao volumeDao;

    @Inject
    private SnapshotDao snapshotDao;

    @Inject
    private StoragePoolHostDao storagePoolHostDao;

    @Inject
    private VMInstanceDao vmInstanceDao;

    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;

    @Inject
    DataStoreManager dataStoreMgr;

    @Inject
    private AgentManager agentManager;

    @Inject
    private VMSnapshotDao vmSnapshotDao;

    @Inject
    private VMSnapshotDetailsDao vmSnapshotDetailsDao;

    @Inject
    BackupManager backupManager;

    @Inject
    ResourceManager resourceManager;

    @Inject
    private DiskOfferingDao diskOfferingDao;


    private Long getClusterIdFromRootVolume(VirtualMachine vm) {
        VolumeVO rootVolume = volumeDao.getInstanceRootVolume(vm.getId());
        if (rootVolume != null) {
            StoragePoolVO rootDiskPool = primaryDataStoreDao.findById(rootVolume.getPoolId());
            if (rootDiskPool != null && rootDiskPool.getClusterId() != null) {
                return rootDiskPool.getClusterId();
            }
        }

        if (vm.getHostId() != null) {
            HostVO host = hostDao.findById(vm.getHostId());
            if (host != null && host.getClusterId() != null) {
                return host.getClusterId();
            }
        }

        if (vm.getLastHostId() != null) {
            HostVO host = hostDao.findById(vm.getLastHostId());
            if (host != null) {
                return host.getClusterId();
            }
        }

        return null;
    }

    protected Host getVMHypervisorHost(VirtualMachine vm) {
        Long hostId = vm.getLastHostId();
        Long clusterId = null;

        if (hostId != null) {
            Host host = hostDao.findById(hostId);
            if (host.getStatus() == Status.Up) {
                return host;
            }
            // Try to find any Up host in the same cluster
            clusterId = host.getClusterId();
        } else {
            // Try to find any Up host in the same cluster as the root volume
            clusterId = getClusterIdFromRootVolume(vm);
        }

        if (clusterId != null) {
            for (final Host hostInCluster : hostDao.findHypervisorHostInCluster(clusterId)) {
                if (hostInCluster.getStatus() == Status.Up) {
                    LOG.debug("Found Host {} in cluster {}", hostInCluster, clusterId);
                    return hostInCluster;
                }
            }
        }

        // Try to find any Host in the zone
        return resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, vm.getDataCenterId());
    }

    protected Host getVMHypervisorHostForBackup(VirtualMachine vm) {
        Long hostId = vm.getHostId();
        if (hostId == null && VirtualMachine.State.Running.equals(vm.getState())) {
            throw new CloudRuntimeException(String.format("Unable to find the hypervisor host for %s. Make sure the virtual machine is running", vm.getName()));
        }
        if (VirtualMachine.State.Stopped.equals(vm.getState())) {
            hostId = vm.getLastHostId();
        }
        if (hostId == null) {
            throw new CloudRuntimeException(String.format("Unable to find the hypervisor host for stopped VM: %s", vm));
        }
        final Host host = hostDao.findById(hostId);
        if (host == null || !Status.Up.equals(host.getStatus()) || !Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        }
        return host;
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, Boolean quiesceVM) {
        return takeBackup(vm, quiesceVM, null);
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, Boolean quiesceVM, Long backupScheduleId) {
        final Host host = getVMHypervisorHostForBackup(vm);

        final BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(vm.getBackupOfferingId());
        if (backupRepository == null) {
            throw new CloudRuntimeException("No valid backup repository found for the VM, please check the attached backup offering");
        }

        validateNoKvmFileBasedVmSnapshots(vm);
        List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        validateVolumePoolTypes(volumePoolsAndPaths.first());
        final BackupVO latestBackup = getLatestBackedUpBackup(vm, backupScheduleId);
        final boolean incrementalBackup = shouldUseIncrementalBackup(vm, latestBackup, vmVolumes, backupScheduleId);
        BackupExecutionResult result = executeBackup(vm, quiesceVM, host, backupRepository, vmVolumes, volumePoolsAndPaths, latestBackup, incrementalBackup,
                incrementalBackup && vmVolumes.size() > 1);
        if (!result.success && incrementalBackup && shouldRetryAsFullAfterIncrementalFailure(result, vmVolumes)) {
            cleanupFailedBackupForFullRetry(result.backup);
            LOG.warn("Incremental backup failed for VM [{}] due to [{}]. Retrying as full backup.", vm, result.details);
            result = executeBackup(vm, quiesceVM, host, backupRepository, vmVolumes, volumePoolsAndPaths, null, false, false);
        }
        return new Pair<>(result.success, result.backup);
    }

    /**
     * Import Veeam-exported disks as a FULL NAS backup seed for subsequent incremental backups.
     */
    public Pair<Boolean, Backup> importVeeamBackupSeed(final VirtualMachine vm, final List<String> stagingDiskPaths,
            final String veeamRestorePointId, final String sourceFormat, final Boolean bootstrapCheckpoint) {
        if (CollectionUtils.isEmpty(stagingDiskPaths)) {
            throw new CloudRuntimeException("Staging disk paths are required to import a Veeam backup seed");
        }
        final Host host = getVMHypervisorHostForBackup(vm);
        final BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(vm.getBackupOfferingId());
        if (backupRepository == null) {
            throw new CloudRuntimeException("No valid backup repository found for the VM");
        }

        validateNoKvmFileBasedVmSnapshots(vm);
        List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        validateVolumePoolTypes(volumePoolsAndPaths.first());

        final String backupPath = buildBackupPath(vm);
        final String checkpointName = backupPath.substring(backupPath.lastIndexOf("/") + 1);
        final String backupEngine = areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2;
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, false);

        BackupVO backupVO = createBackupObject(vm, backupPath, BACKUP_TYPE_FULL, checkpointName, backupEngine, null, volumePoolsAndPaths.second());
        updateBackupDetail(backupVO, DETAIL_BACKUP_SOURCE, "ablestack-veeam");
        updateBackupDetail(backupVO, DETAIL_VEEAM_IMPORTED, "true");
        if (StringUtils.isNotBlank(veeamRestorePointId)) {
            updateBackupDetail(backupVO, DETAIL_VEEAM_RESTORE_POINT_ID, veeamRestorePointId);
        }

        AblestackNasImportVeeamSeedCommand command = new AblestackNasImportVeeamSeedCommand(vm.getInstanceName(), backupPath);
        command.setCheckpointName(checkpointName);
        command.setBackupFiles(backupFiles);
        command.setVolumePools(volumePoolsAndPaths.first());
        command.setVolumePaths(volumePoolsAndPaths.second());
        command.setStagingDiskPaths(stagingDiskPaths);
        command.setSourceFormat(StringUtils.defaultIfBlank(sourceFormat, "vmdk"));
        command.setVeeamRestorePointId(veeamRestorePointId);
        command.setBootstrapCheckpoint(bootstrapCheckpoint == null || bootstrapCheckpoint);
        command.setBackupRepoType(backupRepository.getType());
        command.setBackupRepoAddress(backupRepository.getAddress());
        command.setMountOptions(backupRepository.getMountOptions());

        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(host.getId(), command);
        } catch (AgentUnavailableException e) {
            removeBackupWithDetails(backupVO.getId());
            throw new CloudRuntimeException("Unable to contact hypervisor host to import Veeam backup seed");
        } catch (OperationTimedoutException e) {
            removeBackupWithDetails(backupVO.getId());
            throw new CloudRuntimeException("Timed out importing Veeam backup seed, please try again");
        }

        if (answer != null && answer.getResult()) {
            backupVO.setDate(new Date());
            backupVO.setSize(answer.getSize());
            backupVO.setStatus(Backup.Status.BackedUp);
            backupVO.setBackedUpVolumes(createVolumeInfoFromVolumes(vmVolumes, backupFiles));
            backupDao.update(backupVO.getId(), backupVO);
            return new Pair<>(true, backupVO);
        }

        final String details = answer != null ? answer.getDetails() : "No answer received";
        logger.error("Failed to import Veeam backup seed for VM {}: {}", vm.getInstanceName(), details);
        removeBackupWithDetails(backupVO.getId());
        throw new CloudRuntimeException("Failed to import Veeam backup seed: " + details);
    }

    public boolean hasBackedUpSeed(final VirtualMachine vm) {
        return getLatestBackedUpBackup(vm) != null;
    }

    private BackupExecutionResult executeBackup(VirtualMachine vm, Boolean quiesceVM, Host host, BackupRepository backupRepository,
                                                List<VolumeVO> vmVolumes, Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths,
                                                Backup parentBackup, boolean incrementalBackup, boolean retryAsFullOnFailure) {
        final String backupPath = buildBackupPath(vm);
        final String checkpointName = backupPath.substring(backupPath.lastIndexOf("/") + 1);
        final String backupEngine = areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2;
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
        final String requestedBackupType = incrementalBackup ? BACKUP_TYPE_INCREMENTAL : BACKUP_TYPE_FULL;

        BackupVO backupVO = createBackupObject(vm, backupPath, requestedBackupType,
                checkpointName, backupEngine, incrementalBackup ? parentBackup : null, volumePoolsAndPaths.second());
        AblestackNasTakeBackupCommand command = new AblestackNasTakeBackupCommand(vm.getInstanceName(), backupPath);
        final int commandTimeout = BackupCommandTimeout.value();
        if (commandTimeout > 0) {
            command.setWait(commandTimeout);
        }
        command.setBackupType(requestedBackupType);
        command.setCheckpointName(checkpointName);
        command.setBackupFiles(backupFiles);
        command.setVolumePools(volumePoolsAndPaths.first());
        command.setVolumePaths(volumePoolsAndPaths.second());
        if (incrementalBackup && parentBackup != null) {
            command.setParentBackupPath(parentBackup.getExternalId());
            command.setParentCheckpointName(getBackupDetail(parentBackup, DETAIL_CHECKPOINT_NAME));
            command.setParentCheckpointPath(getBackupDetail(parentBackup, DETAIL_CHECKPOINT_PATH));
        }
        command.setBackupRepoType(backupRepository.getType());
        command.setBackupRepoAddress(backupRepository.getAddress());
        command.setMountOptions(backupRepository.getMountOptions());
        command.setQuiesce(quiesceVM);

        BackupAnswer answer;
        final long backupStartTime = System.currentTimeMillis();
        LOG.info("Starting ABLESTACK NAS backup [backupId: {}, backupUuid: {}, vmId: {}, vmName: {}, backupType: {}, backupEngine: {}, parentBackupUuid: {}, hostId: {}, hostName: {}, repositoryId: {}, repositoryName: {}, repositoryType: {}, repositoryAddress: {}, backupPath: {}, timeoutSeconds: {}]",
                backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                parentBackup != null ? parentBackup.getUuid() : null, host.getId(), host.getName(), backupRepository.getId(), backupRepository.getName(),
                backupRepository.getType(), backupRepository.getAddress(), backupPath, commandTimeout > 0 ? commandTimeout : command.getWait());
        try {
            answer = (BackupAnswer) agentManager.send(host.getId(), command);
        } catch (AgentUnavailableException e) {
            logger.error("Unable to contact backend control plane to initiate ABLESTACK NAS backup [backupId: {}, backupUuid: {}, vmId: {}, vmName: {}, backupType: {}, backupEngine: {}, hostId: {}, repositoryId: {}, repositoryAddress: {}]",
                    backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                    host.getId(), backupRepository.getId(), backupRepository.getAddress(), e);
            markBackupFailure(backupVO, "agent-send", "Unable to contact backend control plane to initiate backup");
            backupVO.setStatus(Backup.Status.Failed);
            removeBackupWithDetails(backupVO.getId());
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        } catch (OperationTimedoutException e) {
            logger.error("Operation to initiate ABLESTACK NAS backup timed out [backupId: {}, backupUuid: {}, vmId: {}, vmName: {}, backupType: {}, backupEngine: {}, hostId: {}, repositoryId: {}, repositoryAddress: {}, elapsedMs: {}, timeoutSeconds: {}]",
                    backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                    host.getId(), backupRepository.getId(), backupRepository.getAddress(), System.currentTimeMillis() - backupStartTime,
                    commandTimeout > 0 ? commandTimeout : command.getWait(), e);
            markBackupFailure(backupVO, "agent-send-timeout", "Operation to initiate backup timed out");
            backupVO.setStatus(Backup.Status.Failed);
            removeBackupWithDetails(backupVO.getId());
            throw new CloudRuntimeException("Operation to initiate backup timed out, please try again");
        }

        if (answer != null && answer.getResult()) {
            LOG.info("Completed ABLESTACK NAS backup [backupId: {}, backupUuid: {}, vmId: {}, vmName: {}, backupType: {}, backupEngine: {}, repositoryId: {}, backupPath: {}, size: {}, elapsedMs: {}]",
                    backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                    backupRepository.getId(), backupPath, answer.getSize(), System.currentTimeMillis() - backupStartTime);
            try {
                backupVO.setDate(new Date());
                backupVO.setSize(answer.getSize());
                backupVO.setStatus(Backup.Status.BackedUp);
                backupVO.setBackedUpVolumes(createVolumeInfoFromVolumes(vmVolumes, backupFiles));
                if (backupDao.update(backupVO.getId(), backupVO)) {
                    return BackupExecutionResult.success(backupVO);
                }
                LOG.error("ABLESTACK NAS backup completed for VM [{}], but backup [{}] metadata update failed. Leaving it in Error state.",
                        vm.getInstanceName(), backupVO.getUuid());
                return failCompletedNasBackupMetadata(backupVO, "Failed to update completed NAS backup metadata");
            } catch (RuntimeException e) {
                LOG.error("ABLESTACK NAS backup completed for VM [{}], but backup [{}] metadata could not be finalized. Leaving it in Error state.",
                        vm.getInstanceName(), backupVO.getUuid(), e);
                return failCompletedNasBackupMetadata(backupVO, "Failed to finalize completed NAS backup metadata");
            }
        }

        final String details = answer != null ? answer.getDetails() : "No answer received";
        logger.error("Failed to take ABLESTACK NAS backup [backupId: {}, backupUuid: {}, vmId: {}, vmName: {}, backupType: {}, backupEngine: {}, repositoryId: {}, backupPath: {}, elapsedMs: {}]: {}",
                backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                backupRepository.getId(), backupPath, System.currentTimeMillis() - backupStartTime, details);
        markBackupFailure(backupVO, "agent-answer", details);
        if (retryAsFullOnFailure) {
            backupVO.setStatus(Backup.Status.Failed);
            removeBackupWithDetails(backupVO.getId());
        } else if (answer != null && answer.getNeedsCleanup()) {
            logger.error("Backup cleanup failed for VM {}. Leaving the backup in Error state.", vm.getInstanceName());
            backupVO.setStatus(Backup.Status.Error);
            backupDao.update(backupVO.getId(), backupVO);
        } else {
            backupVO.setStatus(Backup.Status.Failed);
            removeBackupWithDetails(backupVO.getId());
        }
        return BackupExecutionResult.failure(details, backupVO);
    }

    private BackupExecutionResult failCompletedNasBackupMetadata(BackupVO backupVO, String details) {
        markBackupFailure(backupVO, "metadata-finalize", details);
        backupVO.setStatus(Backup.Status.Error);
        backupDao.update(backupVO.getId(), backupVO);
        return BackupExecutionResult.failure(details, backupVO);
    }

    private boolean shouldRetryAsFullAfterIncrementalFailure(BackupExecutionResult result, List<VolumeVO> vmVolumes) {
        if (result == null || result.success) {
            return false;
        }
        if (StringUtils.contains(result.details, MISSING_PARENT_RBD_SNAPSHOT_ERROR)) {
            return true;
        }
        if (StringUtils.contains(result.details, MISSING_PARENT_QCOW2_BITMAP_ERROR)) {
            return true;
        }
        return vmVolumes.size() > 1;
    }

    private void cleanupFailedBackupForFullRetry(Backup backup) {
        if (backup == null) {
            return;
        }
        removeBackupWithDetails(backup.getId());
    }

    private static final class BackupExecutionResult {
        private final boolean success;
        private final Backup backup;
        private final String details;

        private BackupExecutionResult(boolean success, Backup backup, String details) {
            this.success = success;
            this.backup = backup;
            this.details = details;
        }

        private static BackupExecutionResult success(Backup backup) {
            return new BackupExecutionResult(true, backup, null);
        }

        private static BackupExecutionResult failure(String details, Backup backup) {
            return new BackupExecutionResult(false, backup, details);
        }
    }

    private String buildBackupPath(VirtualMachine vm) {
        return String.format("%s/%s", vm.getInstanceName(),
                new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss.SSS").format(new Date()));
    }

    private BackupVO createBackupObject(VirtualMachine vm, String backupPath, String backupType, String checkpointName, String backupEngine, Backup parentBackup,
                                        List<String> diskPaths) {
        BackupVO backup = new BackupVO();
        backup.setVmId(vm.getId());
        backup.setExternalId(backupPath);
        backup.setType(backupType);
        backup.setDate(new Date());
        long virtualSize = 0L;
        for (final Volume volume: volumeDao.findByInstance(vm.getId())) {
            if (Volume.State.Ready.equals(volume.getState())) {
                virtualSize += volume.getSize();
            }
        }
        backup.setProtectedSize(virtualSize);
        backup.setStatus(Backup.Status.BackingUp);
        backup.setBackupOfferingId(vm.getBackupOfferingId());
        backup.setAccountId(vm.getAccountId());
        backup.setDomainId(vm.getDomainId());
        backup.setZoneId(vm.getDataCenterId());
        backup.setName(backupManager.getBackupNameFromVM(vm));
        Map<String, String> details = new HashMap<>();
        Map<String, String> backupDetails = backupManager.getBackupDetailsFromVM(vm);
        if (backupDetails != null) {
            details.putAll(backupDetails);
        }
        details.put(DETAIL_CHECKPOINT_NAME, checkpointName);
        details.put(DETAIL_CHECKPOINT_PATH, getCheckpointPath(backupPath, checkpointName, backupEngine));
        details.put(DETAIL_BACKUP_ENGINE, backupEngine);
        if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine) && CollectionUtils.isNotEmpty(diskPaths)) {
            details.put(DETAIL_RBD_DISK_PATHS, String.join(",", diskPaths));
        }
        if (parentBackup != null) {
            details.put(DETAIL_PARENT_BACKUP_UUID, parentBackup.getUuid());
            details.put(DETAIL_PARENT_BACKUP_PATH, parentBackup.getExternalId());
            details.put(DETAIL_PARENT_CHECKPOINT_NAME, getBackupDetail(parentBackup, DETAIL_CHECKPOINT_NAME));
            details.put(DETAIL_PARENT_CHECKPOINT_PATH, getBackupDetail(parentBackup, DETAIL_CHECKPOINT_PATH));
        }
        backup.setDetails(details);

        return backupDao.persist(backup);
    }

    private String getCheckpointPath(String backupPath, String checkpointName, String backupEngine) {
        if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
            return String.format("%s/checkpoints/%s.meta", backupPath, checkpointName);
        }
        return String.format("%s/checkpoints/%s.xml", backupPath, checkpointName);
    }

    private BackupVO getLatestBackedUpBackup(VirtualMachine vm) {
        List<Backup> backups = backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), vm.getBackupOfferingId());
        return backups.stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .peek(backupDao::loadDetails)
                .filter(backup -> getBackupDetail(backup, DETAIL_CHECKPOINT_NAME) != null)
                .max(Comparator.comparing(BackupVO::getDate))
                .orElse(null);
    }

    private BackupVO getLatestBackedUpBackup(VirtualMachine vm, Long backupScheduleId) {
        List<Backup> backups = backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), vm.getBackupOfferingId());
        return backups.stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .filter(backup -> Objects.equals(backup.getBackupScheduleId(), backupScheduleId))
                .peek(backupDao::loadDetails)
                .filter(backup -> getBackupDetail(backup, DETAIL_CHECKPOINT_NAME) != null)
                .max(Comparator.comparing(BackupVO::getDate))
                .orElse(null);
    }

    private boolean shouldUseIncrementalBackup(VirtualMachine vm, Backup latestBackup, List<VolumeVO> vmVolumes, Long backupScheduleId) {
        if (latestBackup == null) {
            LOG.debug("NAS backup for VM [{}] will be FULL: no previous BackedUp backup.", vm.getInstanceName());
            return false;
        }
        loadBackupDetailsIfNeeded(latestBackup);

        if (Boolean.parseBoolean(getBackupDetail(latestBackup, DETAIL_CHAIN_SEALED))) {
            LOG.info("NAS backup for VM [{}] will be FULL: backup chain [{}] is sealed ({})",
                    vm.getInstanceName(), latestBackup.getUuid(), getBackupDetail(latestBackup, DETAIL_CHAIN_SEAL_REASON));
            return false;
        }

        if (backupScheduleId != null && !hasBackedUpBackupForSchedule(backupScheduleId)) {
            LOG.debug("NAS backup for VM [{}] will be FULL: no BackedUp backup for schedule [{}].", vm.getInstanceName(), backupScheduleId);
            return false;
        }

        final Long clusterId = getClusterIdFromRootVolume(vm);
        if (clusterId == null) {
            LOG.info("NAS backup for VM [{}] will be FULL: unable to resolve cluster from root volume.", vm.getInstanceName());
            return false;
        }

        if (!Boolean.TRUE.equals(KvmIncrementalBackup.valueIn(clusterId))) {
            LOG.info("NAS backup for VM [{}] will be FULL: kvm.incremental.backup is disabled for cluster [{}] "
                    + "(set cluster configuration kvm.incremental.backup=true or run veeam_config.sh / pre-notify).",
                    vm.getInstanceName(), clusterId);
            return false;
        }

        if (!hasHealthyIncrementalSource(latestBackup)) {
            markVolumeFallbackAndSeal(latestBackup, "unhealthy-chain");
            LOG.info("NAS backup for VM [{}] will be FULL: latest backup [{}] has an unhealthy volume chain.",
                    vm.getInstanceName(), latestBackup.getUuid());
            return false;
        }
        if (getBackupChainSize(vm, latestBackup) >= BackupChainSize.value()) {
            sealBackupChain(latestBackup, "chain-size-limit");
            LOG.info("NAS backup for VM [{}] will be FULL: incremental chain size limit reached for backup [{}].",
                    vm.getInstanceName(), latestBackup.getUuid());
            return false;
        }
        LOG.info("NAS incremental backup selected for VM [{}] using parent backup [{}].",
                vm.getInstanceName(), latestBackup.getUuid());
        return true;
    }

    private boolean hasBackedUpBackupForSchedule(Long backupScheduleId) {
        return backupDao.listBySchedule(backupScheduleId).stream()
                .anyMatch(backup -> Backup.Status.BackedUp.equals(backup.getStatus()));
    }

    private int getBackupChainSize(VirtualMachine vm, Backup latestBackup) {
        List<BackupVO> backups = backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), vm.getBackupOfferingId()).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .peek(backupDao::loadDetails)
                .collect(Collectors.toList());
        Map<String, Backup> backupsByUuid = backups.stream().collect(Collectors.toMap(BackupVO::getUuid, backup -> (Backup) backup, (left, right) -> left));
        return AblestackBackupFrameworkUtils.getBackupChainSize(latestBackup, backupsByUuid,
                current -> getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID));
    }

    private boolean hasHealthyIncrementalSource(Backup latestBackup) {
        try {
            return AblestackBackupFrameworkUtils.hasUsableVolumeChainStates(getVolumeChainStates(latestBackup.getBackedUpVolumes(), latestBackup));
        } catch (Exception e) {
            LOG.warn("Latest NAS backup chain [{}] is not healthy enough for incremental reuse: {}", latestBackup.getUuid(), e.getMessage());
            return false;
        }
    }

    private void markVolumeFallbackAndSeal(Backup latestBackup, String reason) {
        List<String> unhealthyVolumeUuids = listUnhealthyVolumeUuids(latestBackup);
        if (!unhealthyVolumeUuids.isEmpty()) {
            updateBackupDetail(latestBackup, DETAIL_FALLBACK_VOLUME_UUIDS, String.join(",", unhealthyVolumeUuids));
        }
        sealBackupChain(latestBackup, reason);
    }

    private List<String> listUnhealthyVolumeUuids(Backup backup) {
        List<String> unhealthy = new ArrayList<>();
        if (backup == null || CollectionUtils.isEmpty(backup.getBackedUpVolumes())) {
            return unhealthy;
        }
        for (Backup.VolumeInfo volumeInfo : backup.getBackedUpVolumes()) {
            List<String> chainFiles = AblestackBackupFrameworkUtils.sanitizeChainFiles(getBackupFileChain(volumeInfo.getUuid(), backup));
            if (chainFiles.isEmpty()) {
                unhealthy.add(volumeInfo.getUuid());
            }
        }
        return unhealthy;
    }

    private void sealBackupChain(Backup backup, String reason) {
        updateBackupDetail(backup, DETAIL_CHAIN_SEALED, "true");
        updateBackupDetail(backup, DETAIL_CHAIN_SEAL_REASON, reason);
    }

    public void updateBackupDetail(Backup backup, String key, String value) {
        if (backup == null || StringUtils.isBlank(key)) {
            return;
        }
        backupDetailsDao.removeDetail(backup.getId(), key);
        backupDetailsDao.addDetail(backup.getId(), key, value, false);
        if (backup instanceof BackupVO) {
            backupDao.loadDetails((BackupVO) backup);
        }
    }

    private void markBackupFailure(Backup backup, String phase, String reason) {
        if (backup == null) {
            return;
        }
        if (StringUtils.isNotBlank(getBackupDetail(backup, DETAIL_FAILURE_PHASE))) {
            return;
        }
        final String safeReason = StringUtils.defaultIfBlank(reason, "Unknown failure");
        updateBackupDetail(backup, DETAIL_FAILURE_PHASE, phase);
        updateBackupDetail(backup, DETAIL_FAILURE_REASON, StringUtils.abbreviate(safeReason, 1024));
        LOG.warn("Recorded NAS backup failure context [backupId: {}, backupUuid: {}, phase: {}, reason: {}]",
                backup.getId(), backup.getUuid(), phase, safeReason);
    }

    private void removeBackupWithDetails(long backupId) {
        backupDetailsDao.removeDetails(backupId);
        backupDao.remove(backupId);
    }

    private boolean hasDependentBackups(Backup backup) {
        List<Backup> backups = backupDao.listByVmIdAndOffering(backup.getZoneId(), backup.getVmId(), backup.getBackupOfferingId());
        return backups.stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(candidate -> !Objects.equals(candidate.getId(), backup.getId()))
                .peek(backupDao::loadDetails)
                .anyMatch(candidate -> Objects.equals(getBackupDetail(candidate, DETAIL_PARENT_BACKUP_UUID), backup.getUuid()));
    }

    private String getUnreferencedQcow2CheckpointNamesAfterDelete(Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        if (!BACKUP_ENGINE_QCOW2.equals(getBackupDetail(backup, DETAIL_BACKUP_ENGINE))) {
            return null;
        }

        final Set<String> cleanupCandidates = new LinkedHashSet<>();
        addIfNotBlank(cleanupCandidates, getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));
        addIfNotBlank(cleanupCandidates, getBackupDetail(backup, DETAIL_PARENT_CHECKPOINT_NAME));
        if (cleanupCandidates.isEmpty()) {
            return null;
        }

        final Set<String> remainingReferences = new HashSet<>();
        backupDao.listByVmIdAndOffering(backup.getZoneId(), backup.getVmId(), backup.getBackupOfferingId()).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(candidate -> !Objects.equals(candidate.getId(), backup.getId()))
                .filter(this::isBackupManagedByThisProvider)
                .forEach(candidate -> {
                    backupDao.loadDetails(candidate);
                    addIfNotBlank(remainingReferences, getBackupDetail(candidate, DETAIL_CHECKPOINT_NAME));
                    addIfNotBlank(remainingReferences, getBackupDetail(candidate, DETAIL_PARENT_CHECKPOINT_NAME));
                });

        cleanupCandidates.removeAll(remainingReferences);
        return cleanupCandidates.isEmpty() ? null : StringUtils.join(cleanupCandidates, ",");
    }

    private void addIfNotBlank(Set<String> values, String value) {
        if (StringUtils.isNotBlank(value)) {
            values.add(value);
        }
    }

    private String getBackupDetail(Backup backup, String key) {
        Map<String, String> details = backup.getDetails();
        return details != null ? details.get(key) : null;
    }

    private void validateVolumePoolTypes(List<PrimaryDataStoreTO> volumePools) {
        boolean hasRbd = volumePools.stream().anyMatch(pool -> pool != null && Storage.StoragePoolType.RBD.equals(pool.getPoolType()));
        boolean hasNonRbd = volumePools.stream().anyMatch(pool -> pool != null && !Storage.StoragePoolType.RBD.equals(pool.getPoolType()));
        if (hasRbd && hasNonRbd) {
            throw new CloudRuntimeException("NAS incremental backup does not support VMs with mixed RBD and non-RBD volumes");
        }
    }

    private boolean areAllVolumesOnRbdPool(List<PrimaryDataStoreTO> volumePools) {
        return CollectionUtils.isNotEmpty(volumePools) &&
                volumePools.stream().allMatch(pool -> pool != null && Storage.StoragePoolType.RBD.equals(pool.getPoolType()));
    }

    private List<String> buildBackupFileNames(List<VolumeVO> volumes, String backupEngine, boolean incrementalBackup) {
        List<String> backupFiles = new ArrayList<>();
        for (VolumeVO volume : volumes) {
            String diskPrefix = Volume.Type.ROOT.equals(volume.getVolumeType()) ? "root" : "datadisk";
            if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
                String suffix = incrementalBackup ? ".rbdiff" : ".raw";
                backupFiles.add(String.format("%s.%s%s", diskPrefix, volume.getUuid(), suffix));
            } else {
                backupFiles.add(String.format("%s.%s.qcow2", diskPrefix, volume.getUuid()));
            }
        }
        return backupFiles;
    }

    private String createVolumeInfoFromVolumes(List<VolumeVO> volumes, List<String> backupFiles) {
        List<Backup.VolumeInfo> infoList = new ArrayList<>();
        for (int i = 0; i < volumes.size(); i++) {
            VolumeVO vol = volumes.get(i);
            DiskOffering diskOffering = diskOfferingDao.findById(vol.getDiskOfferingId());
            String diskOfferingUuid = diskOffering != null ? diskOffering.getUuid() : null;
            infoList.add(new Backup.VolumeInfo(vol.getUuid(), backupFiles.get(i), vol.getVolumeType(), vol.getSize(),
                    vol.getDeviceId(), diskOfferingUuid, vol.getMinIops(), vol.getMaxIops()));
        }
        return new com.google.gson.Gson().toJson(infoList.toArray(), Backup.VolumeInfo[].class);
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid) {
        return restoreVMBackup(vm, backup);
    }

    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup) {
        return restoreVMBackup(vm, backup).first();
    }

    private Pair<Boolean, String> restoreVMBackup(VirtualMachine vm, Backup backup) {
        validateNasRestoreSnapshotCompatibility(vm);
        validateRestoreChainIntegrity(backup);
        List<Backup.VolumeInfo> backupVolumes = backup.getBackedUpVolumes();
        List<String> backedVolumesUUIDs = backupVolumes.stream()
                .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                .map(Backup.VolumeInfo::getUuid)
                .collect(Collectors.toList());

        List<VolumeVO> restoreVolumes = volumeDao.findByInstance(vm.getId()).stream()
                .sorted(Comparator.comparingLong(VolumeVO::getDeviceId))
                .collect(Collectors.toList());

        LOG.debug("Restoring vm {} from backup {} on the NAS Backup Provider", vm, backup);
        BackupRepository backupRepository = getBackupRepository(backup);

        final Host host = getVMHypervisorHost(vm);
        AblestackNasRestoreBackupCommand restoreCommand = new AblestackNasRestoreBackupCommand();
        restoreCommand.setBackupPath(backup.getExternalId());
        restoreCommand.setBackupRepoType(backupRepository.getType());
        restoreCommand.setBackupRepoAddress(backupRepository.getAddress());
        restoreCommand.setMountOptions(backupRepository.getMountOptions());
        restoreCommand.setVmName(vm.getName());
        restoreCommand.setBackupVolumesUUIDs(backedVolumesUUIDs);
        Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(restoreVolumes);
        restoreCommand.setRestoreVolumePools(volumePoolsAndPaths.first());
        restoreCommand.setRestoreVolumePaths(volumePoolsAndPaths.second());
        restoreCommand.setVolumePaths(getVolumePaths(restoreVolumes));
        restoreCommand.setBackupFiles(getBackupFiles(backupVolumes, backup));
        restoreCommand.setBackupFileChains(getBackupFileChains(backupVolumes, backup));
        restoreCommand.setVolumeChainStates(getVolumeChainStates(backupVolumes, backup));
        restoreCommand.setVmExists(vm.getRemoved() == null);
        restoreCommand.setVmState(vm.getState());
        restoreCommand.setRestorePlan(createRestorePlan(false));
        restoreCommand.setMountTimeout(NASBackupRestoreMountTimeout.value());
        restoreCommand.setWait(BackupRestoreTimeout.value());

        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(host.getId(), restoreCommand);
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation to restore backup timed out, please try again");
        }
        return new Pair<>(answer.getResult(), answer.getDetails());
    }

    private List<String> getBackupFiles(List<Backup.VolumeInfo> backedVolumes, Backup backup) {
        List<String> backupFiles = new ArrayList<>();
        List<Backup.VolumeInfo> sortedVolumes = new ArrayList<>(backedVolumes);
        sortedVolumes.sort(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId));
        for (Backup.VolumeInfo backedVolume : sortedVolumes) {
            if (isLegacyBackup(backup)) {
                backupFiles.add(getLegacyBackupFileName(backedVolume));
            } else {
                backupFiles.add(backedVolume.getPath());
            }
        }
        return backupFiles;
    }

    private List<String> getBackupFileChains(List<Backup.VolumeInfo> backedVolumes, Backup backup) {
        List<String> backupFileChains = new ArrayList<>();
        List<Backup.VolumeInfo> sortedVolumes = new ArrayList<>(backedVolumes);
        sortedVolumes.sort(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId));
        for (Backup.VolumeInfo backedVolume : sortedVolumes) {
            backupFileChains.add(String.join(";", getBackupFileChain(backedVolume.getUuid(), backup)));
        }
        return backupFileChains;
    }

    private List<BackupVolumeChainState> getVolumeChainStates(List<Backup.VolumeInfo> backedVolumes, Backup backup) {
        List<BackupVolumeChainState> volumeChainStates = new ArrayList<>();
        List<Backup.VolumeInfo> sortedVolumes = new ArrayList<>(backedVolumes);
        sortedVolumes.sort(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId));
        String backupEngine = getBackupDetail(backup, DETAIL_BACKUP_ENGINE);
        for (Backup.VolumeInfo backedVolume : sortedVolumes) {
            volumeChainStates.add(new BackupVolumeChainState(backedVolume.getUuid(), backupEngine,
                    AblestackBackupFrameworkUtils.sanitizeChainFiles(getBackupFileChain(backedVolume.getUuid(), backup))));
        }
        AblestackBackupFrameworkUtils.validateVolumeChainStates(volumeChainStates);
        return volumeChainStates;
    }

    private BackupRestorePlan createRestorePlan(boolean attachRequired) {
        return AblestackBackupFrameworkUtils.createRestorePlan(attachRequired, true);
    }

    @Override
    public boolean supportsVolumeLevelChainState() {
        return true;
    }

    @Override
    public boolean supportsRestorePlan() {
        return true;
    }

    @Override
    public boolean supportsRestoreChainValidation() {
        return true;
    }

    @Override
    public boolean supportsPostRestoreMaintenance() {
        return true;
    }

    @Override
    public void runPostRestoreMaintenance(VirtualMachine vm, Backup backup, boolean volumeOnly) {
        if (backup == null || CollectionUtils.isEmpty(backup.getBackedUpVolumes())) {
            return;
        }
        final List<BackupVolumeChainState> chainStates = getVolumeChainStates(backup.getBackedUpVolumes(), backup);
        AblestackBackupFrameworkUtils.validateVolumeChainStates(chainStates);
        LOG.debug("Completed NAS post-restore maintenance for VM [{}], backup [{}], volumeOnly=[{}]", vm != null ? vm.getInstanceName() : null,
                backup.getUuid(), volumeOnly);
    }

    @Override
    public boolean supportsBackgroundChainValidation() {
        return true;
    }

    @Override
    public void validateChains(Long zoneId) {
        final List<Long> vmIdsWithBackups = backupDao.listVmIdsWithBackupsInZone(zoneId);
        if (CollectionUtils.isEmpty(vmIdsWithBackups)) {
            return;
        }
        for (final Long vmId : vmIdsWithBackups) {
            final Backup latestBackup = getLatestBackedUpBackupForProvider(zoneId, vmId);
            if (latestBackup == null) {
                continue;
            }
            loadBackupDetailsIfNeeded(latestBackup);
            if (Boolean.parseBoolean(getBackupDetail(latestBackup, DETAIL_CHAIN_SEALED))) {
                continue;
            }
            if (!hasHealthyIncrementalSource(latestBackup)) {
                markVolumeFallbackAndSeal(latestBackup, "background-chain-validation");
                LOG.warn("Sealed NAS backup chain [{}] during background validation in zone [{}]", latestBackup.getUuid(), zoneId);
            }
        }
    }

    private Backup getLatestBackedUpBackupForProvider(Long zoneId, Long vmId) {
        return backupDao.listByVmId(zoneId, vmId).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .filter(this::isBackupManagedByThisProvider)
                .peek(backupDao::loadDetails)
                .max(Comparator.comparing(BackupVO::getDate))
                .orElse(null);
    }

    private boolean isBackupManagedByThisProvider(Backup backup) {
        BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        return offering != null && BackupProviderNameUtils.isNasFamily(offering.getProvider());
    }

    private List<String> getBackupFileChain(String volumeUuid, Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        if (isLegacyBackup(backup)) {
            Backup.VolumeInfo volumeInfo = getBackedUpVolumeInfo(backup.getBackedUpVolumes(), volumeUuid);
            return volumeInfo != null ? List.of(getLegacyBackupFileName(volumeInfo)) : List.of();
        }

        String backupEngine = getBackupDetail(backup, DETAIL_BACKUP_ENGINE);
        if (!BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
            Backup.VolumeInfo volumeInfo = getBackedUpVolumeInfo(backup.getBackedUpVolumes(), volumeUuid);
            return volumeInfo != null ? List.of(volumeInfo.getPath()) : List.of();
        }

        List<Backup> chain = getBackupChain(backup);
        List<String> files = new ArrayList<>();
        for (Backup chainBackup : chain) {
            Backup.VolumeInfo volumeInfo = getBackedUpVolumeInfo(chainBackup.getBackedUpVolumes(), volumeUuid);
            if (volumeInfo != null) {
                files.add(String.format("%s/%s", chainBackup.getExternalId(), volumeInfo.getPath()));
            }
        }
        return files;
    }

    private List<Backup> getBackupChain(Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        List<Backup> backups = backupDao.listByVmIdAndOffering(backup.getZoneId(), backup.getVmId(), backup.getBackupOfferingId());
        Map<String, Backup> backupsByUuid = new HashMap<>();
        for (Backup candidate : backups) {
            if (candidate instanceof BackupVO) {
                backupDao.loadDetails((BackupVO) candidate);
            }
            backupsByUuid.put(candidate.getUuid(), candidate);
        }

        List<Backup> chain = new ArrayList<>();
        Backup current = backup;
        while (current != null) {
            chain.add(current);
            String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            current = parentBackupUuid != null ? backupsByUuid.get(parentBackupUuid) : null;
        }
        Collections.reverse(chain);
        return chain;
    }

    private void loadBackupDetailsIfNeeded(Backup backup) {
        if (backup instanceof BackupVO && backup.getDetails() == null) {
            backupDao.loadDetails((BackupVO) backup);
        }
    }

    private void validateRestoreChainIntegrity(Backup backup) {
        if (backup == null) {
            return;
        }

        loadBackupDetailsIfNeeded(backup);
        if (isLegacyBackup(backup)) {
            return;
        }
        final Set<String> visitedBackupUuids = new HashSet<>();
        Backup current = backup;
        while (current != null) {
            final String currentBackupUuid = current.getUuid();
            if (StringUtils.isNotBlank(currentBackupUuid) && !visitedBackupUuids.add(currentBackupUuid)) {
                throw new CloudRuntimeException(String.format("Unable to restore backup [%s] because the incremental backup chain contains a cycle at [%s].",
                        backup.getUuid(), currentBackupUuid));
            }

            final String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (StringUtils.isBlank(parentBackupUuid)) {
                return;
            }

            final Backup parentBackup = backupDao.findByUuid(parentBackupUuid);
            if (parentBackup == null) {
                throw new CloudRuntimeException(String.format("Unable to restore backup [%s] because parent backup [%s] is missing from the incremental chain.",
                        backup.getUuid(), parentBackupUuid));
            }

            loadBackupDetailsIfNeeded(parentBackup);
            current = parentBackup;
        }
    }

    private boolean isLegacyBackup(Backup backup) {
        return getBackupDetail(backup, DETAIL_BACKUP_ENGINE) == null;
    }

    private String getLegacyBackupFileName(Backup.VolumeInfo volumeInfo) {
        String diskPrefix = Volume.Type.ROOT.equals(volumeInfo.getType()) ? "root" : "datadisk";
        return String.format("%s.%s.qcow2", diskPrefix, volumeInfo.getUuid());
    }

    private List<String> getVolumePaths(List<VolumeVO> volumes) {
        List<String> volumePaths = new ArrayList<>();
        for (VolumeVO volume : volumes) {
            StoragePoolVO storagePool = primaryDataStoreDao.findById(volume.getPoolId());
            if (Objects.isNull(storagePool)) {
                throw new CloudRuntimeException("Unable to find storage pool associated to the volume");
            }
            String volumePathPrefix;
            if (ScopeType.HOST.equals(storagePool.getScope())) {
                volumePathPrefix = storagePool.getPath();
            } else if (Storage.StoragePoolType.SharedMountPoint.equals(storagePool.getPoolType())) {
                volumePathPrefix = storagePool.getPath();
            } else {
                volumePathPrefix = String.format("/mnt/%s", storagePool.getUuid());
            }
            volumePaths.add(String.format("%s/%s", volumePathPrefix, volume.getPath()));
        }
        return volumePaths;
    }

    private Pair<List<PrimaryDataStoreTO>, List<String>> getVolumePoolsAndPaths(List<VolumeVO> volumes) {
        List<PrimaryDataStoreTO> volumePools = new ArrayList<>();
        List<String> volumePaths = new ArrayList<>();
        for (VolumeVO volume : volumes) {
            StoragePoolVO storagePool = primaryDataStoreDao.findById(volume.getPoolId());
            if (Objects.isNull(storagePool)) {
                throw new CloudRuntimeException("Unable to find storage pool associated to the volume");
            }

            DataStore dataStore = dataStoreMgr.getDataStore(storagePool.getId(), DataStoreRole.Primary);
            volumePools.add(dataStore != null ? (PrimaryDataStoreTO)dataStore.getTO() : null);

            String volumePathPrefix = getVolumePathPrefix(storagePool);
            volumePaths.add(String.format("%s/%s", volumePathPrefix, volume.getPath()));
        }
        return new Pair<>(volumePools, volumePaths);
    }

    private String getVolumePathPrefix(StoragePoolVO storagePool) {
        String volumePathPrefix;
        if (ScopeType.HOST.equals(storagePool.getScope()) ||
                Storage.StoragePoolType.SharedMountPoint.equals(storagePool.getPoolType()) ||
                Storage.StoragePoolType.RBD.equals(storagePool.getPoolType())) {
            volumePathPrefix = storagePool.getPath();
        } else {
            // Should be Storage.StoragePoolType.NetworkFilesystem
            volumePathPrefix = String.format("/mnt/%s", storagePool.getUuid());
        }
        return volumePathPrefix;
    }

    @Override
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid, Pair<String, VirtualMachine.State> vmNameAndState) {
        validateRestoreChainIntegrity(backup);
        final VolumeVO volume = volumeDao.findByUuid(backupVolumeInfo.getUuid());
        final DiskOffering diskOffering = diskOfferingDao.findByUuid(backupVolumeInfo.getDiskOfferingId());
        if (diskOffering == null) {
            throw new CloudRuntimeException(String.format("Unable to find disk offering [%s] for backed up volume [%s]",
                    backupVolumeInfo.getDiskOfferingId(), backupVolumeInfo.getUuid()));
        }
        String cacheMode = null;
        final VMInstanceVO vm = vmInstanceDao.findVMByInstanceName(vmNameAndState.first());
        if (vm == null) {
            throw new CloudRuntimeException(String.format("Unable to find VM [%s] for NAS volume restore", vmNameAndState.first()));
        }
        List<VolumeVO> listVolumes = volumeDao.findByInstanceAndType(vm.getId(), Type.ROOT);
        if(CollectionUtils.isNotEmpty(listVolumes)) {
            VolumeVO rootDisk = listVolumes.get(0);
            DiskOffering baseDiskOffering = diskOfferingDao.findById(rootDisk.getDiskOfferingId());
            if (baseDiskOffering != null && baseDiskOffering.getCacheMode() != null) {
                cacheMode = baseDiskOffering.getCacheMode().toString();
            }
        }
        StoragePoolVO pool = primaryDataStoreDao.findByUuid(dataStoreUuid);
        if (pool == null) {
            List<StoragePoolVO> pools = primaryDataStoreDao.findPoolByName(dataStoreUuid);
            if (CollectionUtils.isNotEmpty(pools)) {
                pool = pools.get(0);
            }
        }
        if (pool == null) {
            throw new CloudRuntimeException(String.format("Unable to find primary storage pool for restore target [%s]", dataStoreUuid));
        }
        HostVO vmHost = hostDao.findByIp(hostIp);
        if (vmHost == null) {
            vmHost = hostDao.findByName(hostIp);
        }
        if (vmHost == null) {
            throw new CloudRuntimeException(String.format("Unable to find VM host [%s] for NAS volume restore", hostIp));
        }

        Backup.VolumeInfo matchingVolume = getBackedUpVolumeInfo(backup.getBackedUpVolumes(), volume.getUuid());
        if (matchingVolume == null) {
            throw new CloudRuntimeException(String.format("Unable to find volume %s in the list of backed up volumes for backup %s, cannot proceed with restore", volume.getUuid(), backup));
        }
        Long backedUpVolumeSize = matchingVolume.getSize();

        LOG.debug("Restoring vm volume {} from backup {} on the NAS Backup Provider", volume, backup);
        BackupRepository backupRepository = getBackupRepository(backup);

        VolumeVO restoredVolume = new VolumeVO(Volume.Type.DATADISK, null, backup.getZoneId(),
                backup.getDomainId(), backup.getAccountId(), 0, null,
                backup.getSize(), null, null, null);
        String volumeUUID = UUID.randomUUID().toString();
        String volumeName = volume != null ? volume.getName() : backupVolumeInfo.getUuid();
        restoredVolume.setName("RestoredVol-" + volumeName);
        restoredVolume.setProvisioningType(diskOffering.getProvisioningType());
        restoredVolume.setUpdated(new Date());
        restoredVolume.setUuid(volumeUUID);
        restoredVolume.setRemoved(null);
        restoredVolume.setDisplayVolume(true);
        restoredVolume.setPoolId(pool.getId());
        restoredVolume.setPoolType(pool.getPoolType());
        restoredVolume.setPath(restoredVolume.getUuid());
        restoredVolume.setState(Volume.State.Copying);
        restoredVolume.setSize(backupVolumeInfo.getSize());
        restoredVolume.setDiskOfferingId(diskOffering.getId());
        if (pool.getPoolType() != Storage.StoragePoolType.RBD) {
            restoredVolume.setFormat(Storage.ImageFormat.QCOW2);
        } else {
            restoredVolume.setFormat(Storage.ImageFormat.RAW);
        }

        AblestackNasRestoreBackupCommand restoreCommand = new AblestackNasRestoreBackupCommand();
        restoreCommand.setBackupPath(backup.getExternalId());
        restoreCommand.setBackupRepoType(backupRepository.getType());
        restoreCommand.setBackupRepoAddress(backupRepository.getAddress());
        restoreCommand.setVmName(vmNameAndState.first());
        restoreCommand.setRestoreVolumePaths(Collections.singletonList(String.format("%s/%s", getVolumePathPrefix(pool), volumeUUID)));
        DataStore dataStore = dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
        if (dataStore == null) {
            throw new CloudRuntimeException(String.format("Unable to get primary datastore TO for pool [%s] while restoring volume [%s]",
                    pool.getUuid(), backupVolumeInfo.getUuid()));
        }
        restoreCommand.setRestoreVolumePools(Collections.singletonList(dataStore != null ? (PrimaryDataStoreTO)dataStore.getTO() : null));
        restoreCommand.setDiskType(matchingVolume.getType().name().toLowerCase(Locale.ROOT));
        restoreCommand.setMountOptions(backupRepository.getMountOptions());
        restoreCommand.setVmExists(null);
        restoreCommand.setVmState(vmNameAndState.second());
        restoreCommand.setMountTimeout(NASBackupRestoreMountTimeout.value());
        restoreCommand.setWait(BackupRestoreTimeout.value());
        restoreCommand.setCacheMode(cacheMode);
        restoreCommand.setVolumePaths(Collections.singletonList(String.format("%s/%s", pool.getPath(), volumeUUID)));
        restoreCommand.setBackupFiles(getBackupFiles(Collections.singletonList(matchingVolume), backup));
        restoreCommand.setBackupFileChains(Collections.singletonList(String.join(";", getBackupFileChain(matchingVolume.getUuid(), backup))));
        restoreCommand.setVolumeChainStates(getVolumeChainStates(Collections.singletonList(matchingVolume), backup));
        restoreCommand.setRestorePlan(createRestorePlan(AblestackBackupFrameworkUtils.requiresRunningVmAttach(vmNameAndState.second())));

        BackupAnswer answer;
        try {
            LOG.info("Restoring volume {} from backup {} on the NAS Backup Provider using VM host [{}]",
                    backupVolumeInfo.getUuid(), backup, vmHost.getName());
            answer = (BackupAnswer) agentManager.send(vmHost.getId(), restoreCommand);
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation to restore backed up volume timed out, please try again");
        }

        if (answer.getResult()) {
            try {
                volumeDao.persist(restoredVolume);
                LOG.info("Successfully restored volume {} from backup {} on the NAS Backup Provider. Restored volume UUID: {}",
                        backupVolumeInfo.getUuid(), backup, restoredVolume.getUuid());
            } catch (Exception e) {
                throw new CloudRuntimeException("Unable to create restored volume due to: " + e);
            }
        }

        return new Pair<>(answer.getResult(), answer.getDetails());
    }

    private BackupRepository getBackupRepository(Backup backup) {
        BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(backup.getBackupOfferingId());
        if (backupRepository == null) {
            throw new CloudRuntimeException(String.format("No valid backup repository found for the backup %s, please check the attached backup offering", backup.getUuid()));
        }
        return backupRepository;
    }

    private Backup.VolumeInfo getBackedUpVolumeInfo(List<Backup.VolumeInfo> backedUpVolumes, String volumeUuid) {
        return backedUpVolumes.stream()
                .filter(v -> v.getUuid().equals(volumeUuid))
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        if (backup instanceof BackupVO && backup.getDetails() == null) {
            backupDao.loadDetails((BackupVO) backup);
        }
        if (!forced && hasDependentBackups(backup)) {
            throw new CloudRuntimeException(String.format("Backup [%s] cannot be deleted because one or more incremental backups depend on it.", backup.getUuid()));
        }

        final BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(backup.getBackupOfferingId());
        if (backupRepository == null) {
            throw new CloudRuntimeException("No valid backup repository found for the VM, please check the attached backup offering");
        }

        final Host host;
        final VirtualMachine vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm != null) {
            host = getVMHypervisorHost(vm);
        } else {
            host = resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, backup.getZoneId());
        }

        AblestackDeleteBackupCommand command = new AblestackDeleteBackupCommand(backup.getExternalId(), backupRepository.getType(),
                backupRepository.getAddress(), backupRepository.getMountOptions(), forced);
        command.setBackupProvider("ablestack-nas");
        command.setVmName(vm != null ? vm.getInstanceName() : null);
        command.setCheckpointName(getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));
        command.setCleanupCheckpointNames(getUnreferencedQcow2CheckpointNamesAfterDelete(backup));
        command.setDiskPaths(getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));

        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(host.getId(), command);
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation to delete backup timed out, please try again");
        }

        if (answer != null && answer.getResult()) {
            return true;
        }

        logger.debug("There was an error removing the backup with id {}", backup.getId());
        return false;
    }

    public void syncBackupMetrics(Long zoneId) {
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(VirtualMachine vm) {
        return null;
    }

    @Override
    public Backup createNewBackupEntryForRestorePoint(Backup.RestorePoint restorePoint, VirtualMachine vm) {
        return null;
    }

    @Override
    public boolean assignVMToBackupOffering(VirtualMachine vm, BackupOffering backupOffering) {
        if (hasDiskAndMemoryVmSnapshots(vm)) {
            logger.warn("NAS backup offering assignment is not allowed for VM [{}] with disk-and-memory VM snapshots.", vm);
            return false;
        }
        if (hasKvmFileBasedVmSnapshots(vm)) {
            logger.warn("Allowing NAS backup offering assignment for VM [{}] with KVM file-based VM snapshots for snapshot coexistence testing.", vm);
        }

        return Hypervisor.HypervisorType.KVM.equals(vm.getHypervisorType());
    }

    private void validateNoKvmFileBasedVmSnapshots(VirtualMachine vm) {
        if (hasDiskAndMemoryVmSnapshots(vm)) {
            logger.warn("NAS backup operation is not allowed for VM [{}] with disk-and-memory VM snapshots.", vm);
            throw new CloudRuntimeException(String.format("Cannot take backup of VM [%s] as it has disk-and-memory VM snapshots.", vm.getUuid()));
        }
        if (hasKvmFileBasedVmSnapshots(vm)) {
            logger.warn("Allowing NAS backup operation for VM [{}] with KVM file-based VM snapshots for snapshot coexistence testing.", vm);
        }
    }

    private void validateNasRestoreSnapshotCompatibility(VirtualMachine vm) {
        final List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vm.getId());
        if (CollectionUtils.isNotEmpty(vmSnapshots)) {
            throw new CloudRuntimeException(String.format(
                    "Unable to restore VM [%s] from NAS backup while Instance snapshots exist. Remove Instance snapshots before restoring the backup.",
                    vm.getInstanceName()));
        }

        final List<VolumeVO> restoreVolumes = volumeDao.findByInstance(vm.getId());
        for (final VolumeVO volume : restoreVolumes) {
            final StoragePoolVO storagePool = primaryDataStoreDao.findById(volume.getPoolId());
            if (storagePool == null || !Storage.StoragePoolType.RBD.equals(storagePool.getPoolType())) {
                continue;
            }
            if (hasActiveVolumeSnapshot(volume)) {
                throw new CloudRuntimeException(String.format(
                        "Unable to restore VM [%s] from NAS backup while RBD volume snapshots exist on volume [%s]. Remove RBD volume snapshots before restoring the backup.",
                        vm.getInstanceName(), volume.getUuid()));
            }
        }
    }

    private boolean hasActiveVolumeSnapshot(final VolumeVO volume) {
        final List<SnapshotVO> snapshots = snapshotDao.listByVolumeId(volume.getId());
        return snapshots.stream()
                .anyMatch(snapshot -> snapshot.getRemoved() == null
                        && !Snapshot.State.Destroyed.equals(snapshot.getState())
                        && !Snapshot.State.Error.equals(snapshot.getState()));
    }

    private boolean hasDiskAndMemoryVmSnapshots(VirtualMachine vm) {
        return CollectionUtils.isNotEmpty(vmSnapshotDao.findByVmAndByType(vm.getId(), VMSnapshot.Type.DiskAndMemory));
    }

    private boolean hasKvmFileBasedVmSnapshots(VirtualMachine vm) {
        for (VMSnapshotVO vmSnapshotVO : vmSnapshotDao.findByVmAndByType(vm.getId(), VMSnapshot.Type.Disk)) {
            List<VMSnapshotDetailsVO> vmSnapshotDetails = vmSnapshotDetailsDao.listDetails(vmSnapshotVO.getId());
            if (vmSnapshotDetails.stream().anyMatch(vmSnapshotDetailsVO -> VolumeApiServiceImpl.KVM_FILE_BASED_STORAGE_SNAPSHOT.equals(vmSnapshotDetailsVO.getName()))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean removeVMFromBackupOffering(VirtualMachine vm) {
        return true;
    }

    @Override
    public boolean willDeleteBackupsOnOfferingRemoval() {
        return false;
    }

    @Override
    public boolean supportsInstanceFromBackup() {
        return true;
    }

    @Override
    public boolean supportsMemoryVmSnapshot() {
        return false;
    }

    @Override
    public Pair<Long, Long> getBackupStorageStats(Long zoneId) {
        final List<BackupRepository> repositories = backupRepositoryDao.listByZoneAndProvider(zoneId, BackupProviderNameUtils.toDisplayName(getName()));
        Long totalSize = 0L;
        Long usedSize = 0L;
        for (final BackupRepository repository : repositories) {
            if (repository.getCapacityBytes() != null) {
                totalSize += repository.getCapacityBytes();
            }
            if (repository.getUsedBytes() != null) {
                usedSize += repository.getUsedBytes();
            }
        }
        return new Pair<>(usedSize, totalSize);
    }

    @Override
    public void syncBackupStorageStats(Long zoneId) {
        final List<BackupRepository> repositories = backupRepositoryDao.listByZoneAndProvider(zoneId, BackupProviderNameUtils.toDisplayName(getName()));
        final Host host = resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, zoneId);
        if (host == null) {
            LOG.debug("Skipping NAS backup repository stats refresh for provider [{}] in zone [{}] because no Up/Enabled KVM routing host was available at this sync cycle. Backup sync is not affected.",
                    getName(), zoneId);
            return;
        }
        for (final BackupRepository repository : repositories) {
            GetBackupStorageStatsCommand command = new GetBackupStorageStatsCommand(repository.getType(), repository.getAddress(), repository.getMountOptions());
            BackupStorageStatsAnswer answer;
            try {
                answer = (BackupStorageStatsAnswer) agentManager.send(host.getId(), command);
                backupRepositoryDao.updateCapacity(repository, answer.getTotalSize(), answer.getUsedSize());
            } catch (AgentUnavailableException e) {
                logger.warn("Unable to contact backend control plane to get backup stats for repository: {}", repository.getName());
            } catch (OperationTimedoutException e) {
                logger.warn("Operation to get backup stats timed out for the repository: " + repository.getName());
            }
        }
    }

    @Override
    public List<BackupOffering> listBackupOfferings(Long zoneId) {
        final List<BackupRepository> repositories = backupRepositoryDao.listByZoneAndProvider(zoneId, BackupProviderNameUtils.toDisplayName(getName()));
        final List<BackupOffering> offerings = new ArrayList<>();
        for (final BackupRepository repository : repositories) {
            offerings.add(new AblestackNasBackupOffering(repository.getName(), repository.getUuid()));
        }
        return offerings;
    }

    @Override
    public boolean isValidProviderOffering(Long zoneId, String uuid) {
        return true;
    }

    @Override
    public Boolean crossZoneInstanceCreationEnabled(BackupOffering backupOffering) {
        final BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(backupOffering.getId());
        if (backupRepository == null) {
            throw new CloudRuntimeException("Backup repository not found for the backup offering" + backupOffering.getName());
        }
        return Boolean.TRUE.equals(backupRepository.crossZoneInstanceCreationEnabled());
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                NASBackupRestoreMountTimeout,
                NASBackupRestoreTimeout
        };
    }

    @Override
    public String getName() {
        return "ablestack-nas";
    }

    @Override
    public String getDescription() {
        return "NAS Backup Plugin";
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    @Override
    public void syncBackups(VirtualMachine vm) {
        for (final Backup backup : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (!isBackupManagedByThisProvider(backup)) {
                continue;
            }
            if (!(backup instanceof BackupVO) || !Backup.Status.BackingUp.equals(backup.getStatus()) || !isOlderThanOneDay(backup)) {
                continue;
            }
            LOG.warn("Removing stale NAS backup [{}] for VM [{}] stuck in BackingUp for over one day. Repository path: [{}]",
                    backup.getUuid(), vm.getInstanceName(), backup.getExternalId());
            try {
                if (deleteBackup(backup, true)) {
                    backupDao.remove(backup.getId());
                }
            } catch (Exception e) {
                LOG.warn("Failed to delete stale NAS backup [{}] for VM [{}]", backup.getUuid(), vm.getInstanceName(), e);
            }
        }
    }

    private boolean isOlderThanOneDay(Backup backup) {
        return backup != null && backup.getDate() != null
                && backup.getDate().getTime() <= System.currentTimeMillis() - STALE_BACKUP_THRESHOLD_MS;
    }

    private String getBackupDetail(Backup backup, String key, String defaultValue) {
        String value = getBackupDetail(backup, key);
        return value == null ? defaultValue : value;
    }

    @Override
    public boolean checkBackupAgent(final Long zoneId) { return true; }

    @Override
    public boolean installBackupAgent(final Long zoneId) { return true; }

    @Override
    public boolean importBackupPlan(final Long zoneId, final String retentionPeriod, final String externalId) { return true; }

    @Override
    public boolean updateBackupPlan(final Long zoneId, final String retentionPeriod, final String externalId) { return true; }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(Long backupId, String vmName) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'restoreBackupToVM'");
    }
}
