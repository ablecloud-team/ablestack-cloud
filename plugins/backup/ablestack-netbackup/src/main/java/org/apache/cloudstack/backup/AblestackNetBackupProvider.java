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
import com.google.gson.Gson;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
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
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;
import org.apache.cloudstack.api.response.BackupStagingInfoResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.netbackup.AblestackNetBackupClient;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.inject.Inject;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.cloudstack.backup.BackupManager.KvmBackupChainSize;
import static org.apache.cloudstack.backup.BackupManager.BackupDataOperationTimeout;
import static org.apache.cloudstack.backup.BackupManager.BackupQosBandwidthLimitMbps;
import static org.apache.cloudstack.backup.BackupManager.BackupFrameworkEnabled;
import static org.apache.cloudstack.backup.BackupManager.KvmIncrementalBackup;

public class AblestackNetBackupProvider extends AdapterBase implements BackupProvider, Configurable {

    private static final Logger LOG = LogManager.getLogger(AblestackNetBackupProvider.class);
    private final ThreadLocal<Boolean> detachedRestoreStart = ThreadLocal.withInitial(() -> false);

    private static final String BACKUP_TYPE_FULL = "FULL";
    private static final String BACKUP_TYPE_INCREMENTAL = "INCREMENTAL";
    private static final String BACKUP_ENGINE_QCOW2 = "QCOW2";
    private static final String BACKUP_ENGINE_RBD_DIFF = "RBD_DIFF";
    private static final String DETAIL_CHECKPOINT_NAME = "netbackup.checkpoint.name";
    private static final String DETAIL_CHECKPOINT_PATH = "netbackup.checkpoint.path";
    private static final String DETAIL_CHECKPOINT_XML = "netbackup.checkpoint.xml";
    private static final String DETAIL_PARENT_BACKUP_UUID = "netbackup.parent.backup.uuid";
    private static final String DETAIL_PARENT_BACKUP_PATH = "netbackup.parent.backup.path";
    private static final String DETAIL_PARENT_CHECKPOINT_NAME = "netbackup.parent.checkpoint.name";
    private static final String DETAIL_PARENT_CHECKPOINT_PATH = "netbackup.parent.checkpoint.path";
    private static final String DETAIL_BACKUP_ENGINE = "netbackup.backup.engine";
    private static final String DETAIL_RBD_DISK_PATHS = "netbackup.rbd.disk.paths";
    private static final String DETAIL_BACKUP_ID = "netbackup.backup.id";
    private static final String DETAIL_MEMBER_COUNT = "netbackup.backup.member.count";
    private static final String DETAIL_POLICY_NAME = "netbackup.policy.name";
    private static final String DETAIL_RESTORE_ROOT_JOB_ID = "netbackup.restore.root.job.id";
    private static final String DETAIL_RESTORE_CHAIN_JOB_ID = "netbackup.restore.chain.job.id";
    private static final String DETAIL_STAGING_METADATA_SYNCED = "netbackup.staging.metadata.synced";
    private static final String DETAIL_FAILURE_PHASE = "netbackup.failure.phase";
    private static final String DETAIL_FAILURE_REASON = "netbackup.failure.reason";
    private static final String DETAIL_CHAIN_SEALED = "netbackup.chain.sealed";
    private static final String DETAIL_CHAIN_SEAL_REASON = "netbackup.chain.seal.reason";
    private static final String BACKUP_TRACE = AblestackBackupFrameworkUtils.buildTracePrefix("netbackup", AblestackBackupFrameworkUtils.OPERATION_BACKUP);
    private static final String RESTORE_TRACE = AblestackBackupFrameworkUtils.buildTracePrefix("netbackup", AblestackBackupFrameworkUtils.OPERATION_RESTORE);
    private static final long NETBACKUP_SYNC_DELETE_GRACE_MS = 10L * 60L * 1000L;
    private static final String NETBACKUP_OFFERING_NAME = "netbackup";
    private static final String NETBACKUP_OFFERING_EXTERNAL_ID = "netbackup";

    private final ConfigKey<Integer> NetBackupPreparedRestorePathReadyTimeout = new ConfigKey<>("Advanced", Integer.class,
            "netbackup.prepared.restore.path.ready.timeout",
            "300",
            "Timeout in seconds to wait for a NetBackup WebUI-prepared restore path to become visible on the KVM host.",
            true,
            BackupFrameworkEnabled.key());

    public ConfigKey<String> NetBackupUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.netbackup.url", "https://localhost:1556/netbackup",
            "NetBackup API URL.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> NetBackupApiKey = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.netbackup.apikey", "apikey",
            "NetBackup API Key.", true, ConfigKey.Scope.Zone);

    private ConfigKey<Integer> NetBackupApiRequestTimeout = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.netbackup.request.timeout", "300",
            "NetBackup API request timeout in seconds.", true, ConfigKey.Scope.Zone);

    private ConfigKey<Integer> NetBackupRecoveryJobTimeout = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.netbackup.recovery.job.timeout", "0",
            "Timeout in seconds to wait for NetBackup recovery jobs to complete. Set to 0 to wait indefinitely.", true, ConfigKey.Scope.Zone);

    @Inject
    private BackupDao backupDao;

    @Inject
    private ThirdPartyBackupStagingService thirdPartyBackupStagingService;
    @Inject
    private ThirdPartyBackupVolumeService thirdPartyBackupVolumeService;
    @Inject
    private BackupDetailsDao backupDetailsDao;
    @Inject
    private BackupOfferingDao backupOfferingDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private VMSnapshotDetailsDao vmSnapshotDetailsDao;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private AgentManager agentManager;
    @Inject
    private BackupManager backupManager;
    @Inject
    private ResourceManager resourceManager;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private ConfigurationDao configDao;

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, final Boolean quiesceVM) {
        return takeBackup(vm, quiesceVM, null);
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, final Boolean quiesceVM, boolean isolated, final Long backupScheduleId) {
        try (ThirdPartyBackupVolumeService.Lease lease = thirdPartyBackupVolumeService.acquireLifecycle(vm.getId())) {
            return takeVolumeBackup(vm, quiesceVM, backupScheduleId);
        }
    }

    private Pair<Boolean, Backup> takeVolumeBackup(final VirtualMachine vm, final Boolean quiesceVM, final Long backupScheduleId) {
        thirdPartyBackupStagingService.requireEnabled();
        final Host host = getVMHypervisorHostForBackup(vm);
        validateVmSnapshotCoexistenceForBackup(vm);

        final List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        validateVolumePoolTypes(volumePoolsAndPaths.first());

        final BackupVO latestBackup = getLatestBackedUpBackup(vm, backupScheduleId);
        final boolean incrementalBackup = shouldUseIncrementalBackup(vm, latestBackup, backupScheduleId)
                && ThirdPartyBackupManifest.canContinue(latestBackup, getName(), vm.getInstanceName(),
                        areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2, vmVolumes);
        final String policyName = getBackupDetail(latestBackup, DETAIL_POLICY_NAME);
        BackupExecutionResult result = executeBackup(vm, quiesceVM, host, vmVolumes, volumePoolsAndPaths, latestBackup,
                incrementalBackup, policyName);
        Backup failedIncrementalBackup = null;
        if (!result.success && incrementalBackup) {
            failedIncrementalBackup = result.backup;
            final String fallbackReason = result.details;
            final boolean cleanupSuccessful = cleanupFailedBackupForFullRetry(host, failedIncrementalBackup);
            if (!cleanupSuccessful) { return new Pair<>(false, failedIncrementalBackup); }
            LOG.warn("{} phase=[INCREMENTAL_FALLBACK_TO_FULL], vmId=[{}], vmName=[{}], failedBackupUuid=[{}], reason=[{}]",
                    BACKUP_TRACE, vm.getId(), vm.getInstanceName(), failedIncrementalBackup != null ? failedIncrementalBackup.getUuid() : null,
                    fallbackReason);
            LOG.warn("Incremental NetBackup backup failed for VM [{}] due to [{}]. Retrying as full backup.", vm.getInstanceName(), fallbackReason);
            result = executeBackup(vm, quiesceVM, host, vmVolumes, volumePoolsAndPaths, null, false,
                    policyName);
            recordIncrementalFallback(result.backup, failedIncrementalBackup, fallbackReason);
            if (cleanupSuccessful && failedIncrementalBackup != null) {
                removeFailedBackupAfterSuccessfulFullRetry(failedIncrementalBackup);
            }
        }
        return new Pair<>(result.success, result.backup);
    }

    @Override
    public Pair<Boolean, Backup> takeNetBackup(final VirtualMachine vm, final String policyName) {
        return takeNetBackup(vm, policyName, null);
    }

    @Override
    public Pair<Boolean, Backup> takeNetBackup(final VirtualMachine vm, final String policyName, final Long backupScheduleId) {
        try (ThirdPartyBackupVolumeService.Lease lease = thirdPartyBackupVolumeService.acquireLifecycle(vm.getId())) {
            return takeVolumeNetBackup(vm, policyName, backupScheduleId);
        }
    }

    private Pair<Boolean, Backup> takeVolumeNetBackup(final VirtualMachine vm, final String policyName, final Long backupScheduleId) {
        thirdPartyBackupStagingService.requireEnabled();
        final Host host = getVMHypervisorHostForBackup(vm);
        validateVmSnapshotCoexistenceForBackup(vm);

        final List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        validateVolumePoolTypes(volumePoolsAndPaths.first());

        final BackupVO latestBackup = getLatestBackedUpBackup(vm, backupScheduleId);
        final boolean incrementalBackup = shouldUseIncrementalBackupForNetBackup(vm, latestBackup)
                && ThirdPartyBackupManifest.canContinue(latestBackup, getName(), vm.getInstanceName(),
                        areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2, vmVolumes);
        BackupExecutionResult result = executeBackup(vm, null, host, vmVolumes, volumePoolsAndPaths, latestBackup,
                incrementalBackup, policyName);
        Backup failedIncrementalBackup = null;
        if (!result.success && incrementalBackup) {
            failedIncrementalBackup = result.backup;
            final String fallbackReason = result.details;
            final boolean cleanupSuccessful = cleanupFailedBackupForFullRetry(host, failedIncrementalBackup);
            if (!cleanupSuccessful) { return new Pair<>(false, failedIncrementalBackup); }
            LOG.warn("{} phase=[INCREMENTAL_FALLBACK_TO_FULL], vmId=[{}], vmName=[{}], failedBackupUuid=[{}], reason=[{}]",
                    BACKUP_TRACE, vm.getId(), vm.getInstanceName(), failedIncrementalBackup != null ? failedIncrementalBackup.getUuid() : null,
                    fallbackReason);
            LOG.warn("Incremental NetBackup backup failed for VM [{}] due to [{}]. Retrying as full backup.", vm.getInstanceName(), fallbackReason);
            result = executeBackup(vm, null, host, vmVolumes, volumePoolsAndPaths, null, false,
                    policyName);
            recordIncrementalFallback(result.backup, failedIncrementalBackup, fallbackReason);
            if (cleanupSuccessful && failedIncrementalBackup != null) {
                removeFailedBackupAfterSuccessfulFullRetry(failedIncrementalBackup);
            }
        }
        return new Pair<>(result.success, result.backup);
    }

    private BackupExecutionResult executeBackup(final VirtualMachine vm, final Boolean quiesceVM, final Host vmHost,
            final List<VolumeVO> vmVolumes, final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths,
            final Backup latestBackup, final boolean incrementalBackup, final String policyName) {
        if (StringUtils.isBlank(policyName)) {
            throw new CloudRuntimeException("Volume backup requires the source NetBackup policy name; start the backup from its configured policy");
        }
        final String backupPath = buildBackupPath(vm);
        final String checkpointName = backupPath.substring(backupPath.lastIndexOf("/") + 1);
        final String backupEngine = areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2;
        final String requestedBackupType = incrementalBackup ? BACKUP_TYPE_INCREMENTAL : BACKUP_TYPE_FULL;
        validateBackupStageCapacity(vmHost, getBackupStageRootPath(), vmVolumes, vm.getInstanceName(), requestedBackupType, backupEngine, true);
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
        final Map<String, String> backupDetails = getBackupDetails(vm, backupPath, checkpointName, backupEngine, latestBackup,
                incrementalBackup, policyName);
        backupDetails.put(AblestackBackupFrameworkUtils.BACKUP_QUIESCE_DETAIL,
                String.valueOf(Boolean.TRUE.equals(quiesceVM)));

        final BackupVO backupVO = createBackupObject(vm, vmHost.getId(), backupPath, requestedBackupType, backupDetails);
        boolean planPersisted = false;
        try {
            backupVO.setBackedUpVolumes(createVolumeInfoFromVolumes(vmVolumes, backupFiles));
            final ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.create(getName(), backupVO.getUuid(), vm.getInstanceName(),
                    checkpointName, requestedBackupType, vmHost.getName(), backupPath, backupEngine, backupVO.getBackedUpVolumes(),
                    incrementalBackup ? getBackupDetail(latestBackup, ThirdPartyBackupManifest.DETAIL_KEY) : null,
                    incrementalBackup ? getBackupDetail(latestBackup, DETAIL_CHECKPOINT_NAME) : null);
            backupVO.getDetails().put(ThirdPartyBackupManifest.MODE_KEY, ThirdPartyBackupManifest.VOLUME_MODE);
            backupVO.getDetails().put(ThirdPartyBackupManifest.DETAIL_KEY, manifest.toJson());
            thirdPartyBackupVolumeService.persistBackupPlan(backupVO);
            planPersisted = true;
            AblestackNetBackupTakeBackupCommand command = new AblestackNetBackupTakeBackupCommand(vm.getInstanceName(), backupPath);
            command.setVolumeStagingManifest(manifest.toJson());
            command.setStagingQueueTimeout(BackupManager.ThirdPartyStagingQueueTimeout.value());
            command.setStagingBufferPercent(thirdPartyBackupStagingService.getCapacityBufferPercent());
            command.setWait(BackupDataOperationTimeout.value());
            command.setBackupJobId(backupVO.getUuid());
            command.setQuiesce(quiesceVM);
            command.setVolumePools(volumePoolsAndPaths.first());
            command.setVolumePaths(volumePoolsAndPaths.second());
            command.setBackupType(requestedBackupType);
            command.setCheckpointName(checkpointName);
            command.setBackupFiles(backupFiles);
            command.setPolicyId(backupDetails.get(DETAIL_POLICY_NAME));
            command.setWaitForCompletion(false);
            command.setBandwidthLimitMbps(BackupQosBandwidthLimitMbps.value());
            if (incrementalBackup && latestBackup != null) {
                command.setParentBackupPath(getBackupDetail(latestBackup, DETAIL_PARENT_BACKUP_PATH,
                        latestBackup.getExternalId()));
                command.setParentCheckpointName(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_NAME));
                command.setParentCheckpointPath(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_PATH));
                final String parentCheckpointXml = getBackupDetail(latestBackup, DETAIL_CHECKPOINT_XML);
                command.setParentCheckpointXml(BACKUP_TYPE_FULL.equalsIgnoreCase(latestBackup.getType())
                        ? removeParentFromCheckpointXml(parentCheckpointXml)
                        : parentCheckpointXml);
                command.setParentCheckpointXmlChain(getParentCheckpointXmlChain(latestBackup));
            }

            LOG.info("{} phase=[START], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], parentBackupUuid=[{}], hostId=[{}], hostName=[{}], backupPath=[{}], timeoutSeconds=[{}]",
                    BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                    latestBackup != null ? latestBackup.getUuid() : null, vmHost.getId(), vmHost.getName(), backupPath, command.getWait());
            thirdPartyBackupVolumeService.dispatchBackup(backupVO, vmHost, command, volumeTransfer(vm, backupVO, vmHost));
            LOG.info("Volume backup start submitted for reconciliation, backup [{}], VM [{}]", backupVO.getUuid(), vm.getInstanceName());
            return BackupExecutionResult.success(backupVO);
        } catch (RuntimeException e) {
            if (planPersisted) {
                // Persisted intent owns this attempt even when command construction or dispatch confirmation fails.
                // Do not delete it, force cleanup or launch a Full retry while the Host outcome is unknown.
                try {
                    thirdPartyBackupVolumeService.recordBackupStartUnconfirmed(backupVO, e.getMessage());
                    thirdPartyBackupVolumeService.track(backupVO, vmHost, volumeTransfer(vm, backupVO, vmHost));
                } catch (RuntimeException trackingFailure) {
                    LOG.warn("Backup start tracking will resume during provider reconciliation [{}]", backupVO.getUuid(), trackingFailure);
                }
                LOG.warn("Backup start is unconfirmed [{}]; the same attempt is retained", backupVO.getUuid(), e);
                return BackupExecutionResult.success(backupVO);
            }
            // No Host command can be sent before the initial plan is durably persisted.
            backupVO.getDetails().remove(ThirdPartyBackupManifest.MODE_KEY);
            backupVO.getDetails().remove(ThirdPartyBackupManifest.DETAIL_KEY);
            markBackupFailure(backupVO, "backup-plan", e.getMessage());
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.update(backupVO.getId(), backupVO);
            throw e;
        }
    }

    private boolean shouldUseIncrementalBackup(final VirtualMachine vm, final Backup latestBackup, final Long backupScheduleId) {
        if (latestBackup == null) {
            return false;
        }
        loadBackupDetailsIfNeeded(latestBackup);
        if (StringUtils.isBlank(getBackupDetail(latestBackup, ThirdPartyBackupManifest.DETAIL_KEY))) {
            return false;
        }

        if (backupScheduleId != null && !hasBackedUpBackupForSchedule(backupScheduleId)) {
            return false;
        }

        final Long clusterId = getClusterIdFromRootVolume(vm);
        if (clusterId == null || !KvmIncrementalBackup.valueIn(clusterId)) {
            return false;
        }

        if (!hasHealthyIncrementalSource(latestBackup)) {
            return false;
        }
        if (Boolean.parseBoolean(getBackupDetail(latestBackup, DETAIL_CHAIN_SEALED))) {
            LOG.warn("Skipping NetBackup incremental backup because parent backup chain [{}] is sealed. reason=[{}]",
                    latestBackup.getUuid(), getBackupDetail(latestBackup, DETAIL_CHAIN_SEAL_REASON));
            return false;
        }

        return getBackupChainSize(vm, latestBackup) < KvmBackupChainSize.value();
    }

    private boolean shouldUseIncrementalBackupForNetBackup(final VirtualMachine vm, final Backup latestBackup) {
        if (latestBackup == null) {
            return false;
        }
        loadBackupDetailsIfNeeded(latestBackup);
        if (StringUtils.isBlank(getBackupDetail(latestBackup, ThirdPartyBackupManifest.DETAIL_KEY))) {
            return false;
        }

        if (Boolean.parseBoolean(getBackupDetail(latestBackup, DETAIL_CHAIN_SEALED))) {
            return false;
        }

        final Long clusterId = getClusterIdFromRootVolume(vm);
        if (clusterId == null || !KvmIncrementalBackup.valueIn(clusterId)) {
            return false;
        }

        if (!hasHealthyIncrementalSource(latestBackup)) {
            return false;
        }

        return getBackupChainSize(vm, latestBackup) < KvmBackupChainSize.value();
    }

    private boolean hasHealthyIncrementalSource(final Backup latestBackup) {
        final String backupEngine = getBackupDetail(latestBackup, DETAIL_BACKUP_ENGINE);
        if (StringUtils.isBlank(backupEngine)) {
            return false;
        }
        if (StringUtils.isBlank(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_NAME))
                || StringUtils.isBlank(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_PATH))) {
            return false;
        }
        if (BACKUP_ENGINE_QCOW2.equals(backupEngine)) {
            return StringUtils.isNotBlank(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_XML));
        }
        return true;
    }

    private int getBackupChainSize(final VirtualMachine vm, final Backup latestBackup) {
        final List<BackupVO> backups = backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), vm.getBackupOfferingId()).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .peek(this::loadBackupDetailsIfNeeded)
                .collect(Collectors.toList());
        final Map<String, BackupVO> backupsByUuid = backups.stream().collect(Collectors.toMap(BackupVO::getUuid, backup -> backup, (left, right) -> left));
        return AblestackBackupFrameworkUtils.getBackupChainSize(latestBackup, backupsByUuid,
                current -> getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID));
    }

    private boolean hasBackedUpBackupForSchedule(final Long backupScheduleId) {
        return backupDao.listBySchedule(backupScheduleId).stream()
                .anyMatch(backup -> Backup.Status.BackedUp.equals(backup.getStatus()));
    }

    private boolean cleanupFailedBackupForFullRetry(final Host host, final Backup backup) {
        if (backup == null) {
            return true;
        }
        final boolean cleanupSuccessful = !Backup.Status.Error.equals(backup.getStatus());
        if (cleanupSuccessful) {
            LOG.info("Cleaned failed NetBackup backup path [{}] before full retry for backup [{}].",
                    backup.getExternalId(), backup.getUuid());
        } else if (backup instanceof BackupVO) {
            ((BackupVO) backup).setStatus(Backup.Status.Error);
            backupDao.update(backup.getId(), (BackupVO) backup);
        }
        return cleanupSuccessful;
    }

    private boolean cleanupFailedBackupArtifacts(final Host host, final Backup backup) {
        if (backup == null) {
            return true;
        }
        if (host == null || StringUtils.isBlank(backup.getExternalId())) {
            return false;
        }
        loadBackupDetailsIfNeeded(backup);

        final AblestackDeleteBackupCommand command = new AblestackDeleteBackupCommand(backup.getExternalId(), null, null, null, true);
        final int deleteTimeout = BackupDataOperationTimeout.value();
        if (deleteTimeout > 0) {
            command.setWait(deleteTimeout);
        }
        command.setBackupProvider(getName());
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        command.setVmName(vm != null ? vm.getInstanceName() : null);
        command.setCheckpointName(getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));
        command.setCleanupCheckpointNames(getUnreferencedQcow2CheckpointNamesAfterDelete(backup, Collections.singleton(backup.getId())));
        if (BACKUP_ENGINE_RBD_DIFF.equals(getBackupDetail(backup, DETAIL_BACKUP_ENGINE))) {
            command.setDiskPaths(getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));
        }
        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) {
                LOG.warn("Failed to cleanup artifacts for NetBackup backup [{}] on host [{}]: {}",
                        backup.getUuid(), host.getName(), answer != null ? answer.getDetails() : "no answer received");
                return false;
            }
            return true;
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Unable to cleanup artifacts for NetBackup backup [{}] on host [{}]: {}",
                    backup.getUuid(), host.getName(), e.getMessage(), e);
            return false;
        }
    }

    private void removeFailedBackupAfterSuccessfulFullRetry(final Backup backup) {
        if (backup == null) {
            return;
        }

        try {
            removeBackupWithDetails(backup.getId());
            LOG.info("Removed failed NetBackup backup row [{}] after successful full retry.", backup.getUuid());
        } catch (Exception e) {
            LOG.warn("Failed to remove failed NetBackup backup row [{}] after successful full retry: {}",
                    backup.getUuid(), e.getMessage(), e);
        }
    }

    private void recordIncrementalFallback(final Backup fullBackup, final Backup failedIncrementalBackup, final String reason) {
        if (fullBackup == null) {
            return;
        }
        updateBackupDetail(fullBackup, AblestackBackupFrameworkUtils.INCREMENTAL_FALLBACK_REASON_DETAIL,
                StringUtils.defaultString(reason, "Incremental backup failed"));
        if (failedIncrementalBackup != null) {
            updateBackupDetail(fullBackup, AblestackBackupFrameworkUtils.INCREMENTAL_FALLBACK_BACKUP_UUID_DETAIL,
                    failedIncrementalBackup.getUuid());
        }
    }

    private BackupVO createBackupObject(final VirtualMachine vm, final Long hostId, final String backupPath, final String backupType, final Map<String, String> details) {
        final BackupVO backup = new BackupVO();
        backup.setVmId(vm.getId());
        backup.setHostId(hostId);
        backup.setExternalId(backupPath);
        backup.setType(backupType);
        backup.setDate(new Date());
        long virtualSize = 0L;
        for (final Volume volume : volumeDao.findByInstance(vm.getId())) {
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
        backup.setDetails(details);
        final BackupVO persistedBackup = backupDao.persist(backup);
        persistedBackup.setHostId(hostId);
        backupDao.update(persistedBackup.getId(), persistedBackup);
        backupDao.saveDetails(persistedBackup);
        return persistedBackup;
    }

    private Map<String, String> getBackupDetails(final VirtualMachine vm, final String backupPath, final String checkpointName, final String backupEngine,
            final Backup latestBackup, final boolean incrementalBackup, final String policyName) {
        final Map<String, String> details = new HashMap<>();
        final Map<String, String> backupDetailsFromVm = backupManager.getBackupDetailsFromVM(vm);
        if (backupDetailsFromVm != null) {
            details.putAll(backupDetailsFromVm);
        }
        if (StringUtils.isNotBlank(policyName)) {
            details.put(DETAIL_POLICY_NAME, policyName);
        }
        details.put(DETAIL_BACKUP_ENGINE, backupEngine);
        details.put(DETAIL_CHECKPOINT_NAME, checkpointName);
        details.put(DETAIL_CHECKPOINT_PATH, getCheckpointPath(backupPath, checkpointName, backupEngine));
        if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
            details.put(DETAIL_RBD_DISK_PATHS, String.join(",", getVolumePoolsAndPaths(volumeDao.findByInstance(vm.getId())).second()));
        }
        if (incrementalBackup && latestBackup != null) {
            details.put(DETAIL_PARENT_BACKUP_UUID, latestBackup.getUuid());
            details.put(DETAIL_PARENT_BACKUP_PATH, latestBackup.getExternalId());
            details.put(DETAIL_PARENT_CHECKPOINT_NAME, getBackupDetail(latestBackup, DETAIL_CHECKPOINT_NAME));
            details.put(DETAIL_PARENT_CHECKPOINT_PATH, getBackupDetail(latestBackup, DETAIL_CHECKPOINT_PATH));
        }
        return details;
    }

    private String getCheckpointPath(final String backupPath, final String checkpointName, final String backupEngine) {
        if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
            return String.format("%s/checkpoints/%s.meta", backupPath, checkpointName);
        }
        return String.format("%s/checkpoints/%s.xml", backupPath, checkpointName);
    }

    private Pair<List<PrimaryDataStoreTO>, List<String>> getVolumePoolsAndPaths(final List<VolumeVO> volumes) {
        final List<PrimaryDataStoreTO> volumePools = new ArrayList<>();
        final List<String> volumePaths = new ArrayList<>();
        for (final VolumeVO volume : volumes) {
            final StoragePoolVO storagePool = primaryDataStoreDao.findById(volume.getPoolId());
            if (storagePool == null) {
                throw new CloudRuntimeException("Unable to find storage pool associated to the volume");
            }

            final DataStore dataStore = dataStoreMgr.getDataStore(storagePool.getId(), DataStoreRole.Primary);
            volumePools.add(dataStore != null ? (PrimaryDataStoreTO) dataStore.getTO() : null);

            final String volumePathPrefix = getVolumePathPrefix(storagePool);
            volumePaths.add(String.format("%s/%s", volumePathPrefix, volume.getPath()));
        }
        return new Pair<>(volumePools, volumePaths);
    }

    private String getVolumePathPrefix(final StoragePoolVO storagePool) {
        if (ScopeType.HOST.equals(storagePool.getScope())
                || Storage.StoragePoolType.SharedMountPoint.equals(storagePool.getPoolType())
                || Storage.StoragePoolType.RBD.equals(storagePool.getPoolType())) {
            return storagePool.getPath();
        }
        return String.format("/mnt/%s", storagePool.getUuid());
    }

    private void validateVolumePoolTypes(final List<PrimaryDataStoreTO> volumePools) {
        final boolean hasRbd = volumePools.stream().anyMatch(pool -> pool != null && pool.getPoolType() == Storage.StoragePoolType.RBD);
        final boolean hasNonRbd = volumePools.stream().anyMatch(pool -> pool != null && pool.getPoolType() != Storage.StoragePoolType.RBD);
        if (hasRbd && hasNonRbd) {
            throw new CloudRuntimeException("NetBackup incremental backup does not support VMs with mixed RBD and non-RBD volumes");
        }
    }

    private boolean areAllVolumesOnRbdPool(final List<PrimaryDataStoreTO> volumePools) {
        return !volumePools.isEmpty() && volumePools.stream().allMatch(pool -> pool != null && pool.getPoolType() == Storage.StoragePoolType.RBD);
    }

    private List<String> buildBackupFileNames(final List<VolumeVO> volumes, final String backupEngine, final boolean incrementalBackup) {
        final List<String> backupFiles = new ArrayList<>();
        for (final VolumeVO volume : volumes) {
            final String diskPrefix = Volume.Type.ROOT.equals(volume.getVolumeType()) ? "root" : "datadisk";
            if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
                final String suffix = incrementalBackup ? ".rbdiff" : ".raw";
                backupFiles.add(String.format("%s.%s%s", diskPrefix, volume.getUuid(), suffix));
            } else {
                backupFiles.add(String.format("%s.%s.qcow2", diskPrefix, volume.getUuid()));
            }
        }
        return backupFiles;
    }

    private String createVolumeInfoFromVolumes(final List<VolumeVO> volumes, final List<String> backupFiles) {
        final List<Backup.VolumeInfo> infoList = new ArrayList<>();
        for (int i = 0; i < volumes.size(); i++) {
            final VolumeVO volume = volumes.get(i);
            final DiskOffering diskOffering = diskOfferingDao.findById(volume.getDiskOfferingId());
            final String diskOfferingUuid = diskOffering != null ? diskOffering.getUuid() : null;
            infoList.add(new Backup.VolumeInfo(volume.getUuid(), backupFiles.get(i), volume.getVolumeType(), volume.getSize(),
                    volume.getDeviceId(), diskOfferingUuid, volume.getMinIops(), volume.getMaxIops()));
        }
        return new com.google.gson.Gson().toJson(infoList.toArray(), Backup.VolumeInfo[].class);
    }

    private String buildBackupPath(final VirtualMachine vm) {
        return String.format("%s/%s/%s", getBackupStageRootPath(), vm.getInstanceName(),
                new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss.SSS").format(new Date()));
    }

    private String getBackupStageRootPath() {
        return thirdPartyBackupStagingService.getStageRootPath(getName());
    }

    private void validateBackupStageCapacity(final Host stageHost, final String stageRootPath, final List<VolumeVO> vmVolumes,
            final String vmName, final String backupType, final String backupEngine, boolean queueOnShortage) {
        final long requiredBytes = estimateRequiredStageBytesForBackup(vmVolumes);
        final long bufferBytes = thirdPartyBackupStagingService.getCapacityBufferBytes(requiredBytes);
        final long minimumAvailableBytes = Math.addExact(requiredBytes, bufferBytes);
        final long availableBytes = getAvailableBytesOnHostPath(stageHost, stageRootPath);
        LOG.info("{} phase=[STAGE_SPACE_CHECK], vm=[{}], host=[{}], backupType=[{}], backupEngine=[{}], stageRoot=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}]",
                BACKUP_TRACE, vmName, stageHost != null ? stageHost.getName() : null, backupType, backupEngine, stageRootPath,
                requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes);
        if (!queueOnShortage && availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient stage space on host [%s] for NetBackup backup. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    stageHost != null ? stageHost.getName() : null, minimumAvailableBytes, availableBytes, stageRootPath));
        }
    }

    private long estimateRequiredStageBytesForBackup(final List<VolumeVO> vmVolumes) {
        if (CollectionUtils.isEmpty(vmVolumes)) {
            return 0L;
        }
        return vmVolumes.stream().mapToLong(VolumeVO::getSize).max().orElse(0L);
    }

    private long estimateReadyVolumeBytes(final List<VolumeVO> vmVolumes) {
        if (CollectionUtils.isEmpty(vmVolumes)) {
            return 0L;
        }
        return vmVolumes.stream()
                .filter(volume -> Volume.State.Ready.equals(volume.getState()))
                .mapToLong(VolumeVO::getSize)
                .sum();
    }

    private long getAvailableBytesOnHostPath(final Host host, final String path) {
        return thirdPartyBackupStagingService.getAvailableBytes(host, path);
    }

    private void ensureStageHostHasCapacityForRestore(final Host stageHost, final List<Backup> restoreChain,
            final Set<String> requiredVolumeUuids, final String vmName, final String backupUuid, final String volumeUuid) {
        if (stageHost == null || CollectionUtils.isEmpty(restoreChain)) {
            return;
        }
        final String stageRootPath = getBackupStageRootPath();
        final long requiredBytes = estimateRequiredStageBytesForRestore(restoreChain, requiredVolumeUuids);
        final long bufferBytes = thirdPartyBackupStagingService.getCapacityBufferBytes(requiredBytes);
        final long minimumAvailableBytes = Math.addExact(requiredBytes, bufferBytes);
        final long availableBytes = getAvailableBytesOnHostPath(stageHost, stageRootPath);
        LOG.info("{} phase=[STAGE_SPACE_CHECK], vm=[{}], backupUuid=[{}], volumeUuid=[{}], host=[{}], stageRoot=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}], restoreChain=[{}]",
                RESTORE_TRACE, vmName, backupUuid, volumeUuid, stageHost.getName(), stageRootPath, requiredBytes,
                bufferBytes, minimumAvailableBytes, availableBytes,
                restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
        if (availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient stage space on host [%s] for NetBackup restore. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    stageHost.getName(), minimumAvailableBytes, availableBytes, stageRootPath));
        }
    }

    private long estimateRequiredStageBytesForRestore(final List<Backup> restoreChain, final Set<String> requiredVolumeUuids) {
        // The current engine still prepares the complete VM/restore chain; retain its aggregate capacity check.
        long totalBytes = 0L;
        for (final Backup restoreBackup : restoreChain) {
            if (restoreBackup == null) {
                continue;
            }
            if (CollectionUtils.isEmpty(requiredVolumeUuids)) {
                totalBytes += Math.max(restoreBackup.getSize(), 0L);
                continue;
            }
            final List<Backup.VolumeInfo> backedUpVolumes = restoreBackup.getBackedUpVolumes();
            if (CollectionUtils.isEmpty(backedUpVolumes)) {
                totalBytes += Math.max(restoreBackup.getSize(), 0L);
                continue;
            }
            final long volumeBytes = backedUpVolumes.stream()
                    .filter(volume -> requiredVolumeUuids.contains(volume.getUuid()))
                    .mapToLong(Backup.VolumeInfo::getSize)
                    .sum();
            totalBytes += volumeBytes > 0L ? volumeBytes : Math.max(restoreBackup.getSize(), 0L);
        }
        return totalBytes;
    }

    private BackupVO getLatestBackedUpBackup(final VirtualMachine vm) {
        return getLatestBackedUpBackup(vm, null);
    }

    private BackupVO getLatestBackedUpBackup(final VirtualMachine vm, final Long backupScheduleId) {
        return backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), vm.getBackupOfferingId()).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .filter(backup -> backupScheduleId == null || Objects.equals(backup.getBackupScheduleId(), backupScheduleId))
                .peek(this::loadBackupDetailsIfNeeded)
                .filter(backup -> getBackupDetail(backup, DETAIL_CHECKPOINT_NAME) != null)
                .max(Comparator.comparing(BackupVO::getDate))
                .orElse(null);
    }

    private Map<String, String> getParentCheckpointXmlChain(final Backup latestBackup) {
        final Map<String, String> checkpointXmlChain = new LinkedHashMap<>();
        Backup current = latestBackup;
        final Set<String> visitedBackupUuids = new HashSet<>();
        final Set<String> visitedCheckpointNames = new HashSet<>();
        while (current != null && StringUtils.isNotBlank(current.getUuid()) && visitedBackupUuids.add(current.getUuid())) {
            loadBackupDetailsIfNeeded(current);
            final String checkpointPath = getBackupDetail(current, DETAIL_CHECKPOINT_PATH);
            final String checkpointXml = getBackupDetail(current, DETAIL_CHECKPOINT_XML);
            final String checkpointXmlForChain = BACKUP_TYPE_FULL.equalsIgnoreCase(current.getType()) ? removeParentFromCheckpointXml(checkpointXml) : checkpointXml;
            final String checkpointName = getBackupDetail(current, DETAIL_CHECKPOINT_NAME);
            if (StringUtils.isNotBlank(checkpointName)) {
                visitedCheckpointNames.add(checkpointName);
            }
            if (StringUtils.isNotBlank(checkpointPath) && StringUtils.isNotBlank(checkpointXmlForChain)) {
                checkpointXmlChain.putIfAbsent(checkpointPath, checkpointXmlForChain);
            }
            final String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (StringUtils.isNotBlank(parentBackupUuid)) {
                current = backupDao.findByUuid(parentBackupUuid);
                continue;
            }
            final String parentCheckpointName = getParentCheckpointNameFromXml(checkpointXmlForChain);
            if (StringUtils.isBlank(parentCheckpointName) || !visitedCheckpointNames.add(parentCheckpointName)) {
                break;
            }
            current = findBackedUpBackupByCheckpointName(latestBackup, parentCheckpointName);
        }
        return checkpointXmlChain;
    }

    private Backup findBackedUpBackupByCheckpointName(final Backup referenceBackup, final String checkpointName) {
        if (referenceBackup == null || StringUtils.isBlank(checkpointName)) {
            return null;
        }
        return backupDetailsDao.findDetails(DETAIL_CHECKPOINT_NAME, checkpointName, null).stream()
                .map(BackupDetailVO::getResourceId)
                .map(backupDao::findById)
                .filter(Objects::nonNull)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .filter(backup -> Objects.equals(referenceBackup.getVmId(), backup.getVmId()))
                .filter(backup -> Objects.equals(referenceBackup.getBackupOfferingId(), backup.getBackupOfferingId()))
                .findFirst()
                .orElse(null);
    }

    private String getParentCheckpointNameFromXml(final String checkpointXml) {
        if (StringUtils.isBlank(checkpointXml)) {
            return null;
        }
        try {
            final Document checkpointDocument = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(checkpointXml)));
            final String parentName = (String) XPathFactory.newInstance().newXPath()
                    .compile("/domaincheckpoint/parent/name/text()")
                    .evaluate(checkpointDocument, XPathConstants.STRING);
            return StringUtils.trimToNull(parentName);
        } catch (final Exception e) {
            LOG.warn("Failed to parse NetBackup checkpoint XML parent name. Incremental checkpoint chain may be incomplete.", e);
            return null;
        }
    }

    private String removeParentFromCheckpointXml(final String checkpointXml) {
        if (StringUtils.isBlank(checkpointXml)) {
            return checkpointXml;
        }
        try {
            final Document checkpointDocument = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(checkpointXml)));
            final Node parentNode = (Node) XPathFactory.newInstance().newXPath()
                    .compile("/domaincheckpoint/parent")
                    .evaluate(checkpointDocument, XPathConstants.NODE);
            if (parentNode == null) {
                return checkpointXml;
            }
            parentNode.getParentNode().removeChild(parentNode);
            final Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            final StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(checkpointDocument), new StreamResult(writer));
            return writer.toString();
        } catch (final Exception e) {
            LOG.warn("Failed to remove parent from NetBackup FULL checkpoint XML. Keeping original XML.", e);
            return checkpointXml;
        }
    }

    private void loadBackupDetailsIfNeeded(final Backup backup) {
        if (backup instanceof BackupVO && (backup.getDetails() == null || backup.getDetails().isEmpty())) {
            backupDao.loadDetails((BackupVO) backup);
        }
    }

    private String getBackupDetail(final Backup backup, final String key) {
        return backup == null ? null : backup.getDetail(key);
    }

    private String getBackupDetail(final Backup backup, final String key, final String defaultValue) {
        final String value = getBackupDetail(backup, key);
        return value == null ? defaultValue : value;
    }

    private void updateBackupDetail(final Backup backup, final String key, final String value) {
        if (backup == null || StringUtils.isBlank(key)) {
            return;
        }
        backupDetailsDao.removeDetail(backup.getId(), key);
        backupDetailsDao.addDetail(backup.getId(), key, value, false);
        if (backup instanceof BackupVO) {
            backupDao.loadDetails((BackupVO) backup);
        }
    }

    private void trackRestoreJob(final Backup backup, final String restoreJobId, final Host host) {
        updateBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL, restoreJobId);
        updateBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL, host != null ? String.valueOf(host.getId()) : null);
        updateBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_HOST_NAME_DETAIL, host != null ? host.getName() : null);
        updateBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "STARTING");
        updateBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL, "QUEUED");
        updateBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL, "10");
        updateBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_JOB_TRACKED_AT_DETAIL, String.valueOf(System.currentTimeMillis()));
    }

    private BackupAnswer sendAndWaitForRestore(final Long hostId, final Command restoreCommand, final String restoreJobId)
            throws AgentUnavailableException, OperationTimedoutException {
        final Answer startAnswer = agentManager.send(hostId, restoreCommand);
        if (!(startAnswer instanceof BackupAnswer) || !startAnswer.getResult()) {
            return startAnswer instanceof BackupAnswer ? (BackupAnswer) startAnswer
                    : new BackupAnswer(restoreCommand, false, "Unexpected restore start response");
        }
        if (Boolean.TRUE.equals(detachedRestoreStart.get())) {
            return (BackupAnswer) startAnswer;
        }
        return AblestackRestoreJobPoller.waitForStagedCompletion(restoreJobId, BackupDataOperationTimeout.value(),
                BackupManager.ThirdPartyStagingQueueTimeout.value(),
                () -> agentManager.send(hostId, new AblestackRestoreJobStatusCommand(restoreJobId, null, 5)));
    }

    private void markBackupFailure(final Backup backup, final String phase, final String reason) {
        if (backup == null) {
            return;
        }
        if (StringUtils.isNotBlank(getBackupDetail(backup, DETAIL_FAILURE_PHASE))) {
            return;
        }
        final String safeReason = StringUtils.defaultIfBlank(reason, "Unknown failure");
        updateBackupDetail(backup, DETAIL_FAILURE_PHASE, phase);
        updateBackupDetail(backup, DETAIL_FAILURE_REASON, StringUtils.abbreviate(safeReason, 1024));
        LOG.warn("Recorded NetBackup backup failure context [backupId: {}, backupUuid: {}, phase: {}, reason: {}]",
                backup.getId(), backup.getUuid(), phase, safeReason);
    }

    private void removeBackupWithDetails(final long backupId) {
        backupDetailsDao.removeDetails(backupId);
        backupDao.remove(backupId);
    }

   private Host getVMHypervisorHostForBackup(final VirtualMachine vm) {
        if (VirtualMachine.State.Stopped.equals(vm.getState())) {
            final List<VolumeVO> disks = volumeDao.findByInstance(vm.getId());
            if (!disks.isEmpty() && areAllVolumesOnRbdPool(getVolumePoolsAndPaths(disks).first())) {
                return thirdPartyBackupVolumeService.selectBackupHost(vm, getName(),
                        disks.stream().map(VolumeVO::getPoolId).collect(java.util.stream.Collectors.toList()),
                        disks.stream().mapToLong(VolumeVO::getSize).max().orElseThrow());
            }
        }
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
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate NetBackup backup");
        }
        return host;
    }

    private Host resolveRestoreHost(final VirtualMachine vm, final Backup backup, final String hostIp) {
        if (StringUtils.isNotBlank(hostIp)) {
            return findAvailableKvmRestoreHost(hostIp, "NetBackup restore");
        }
        final Host backupSourceHost = resolveBackupSourceHostForRestore(backup);
        if (backupSourceHost != null) {
            return backupSourceHost;
        }
        return getVMHypervisorHostForBackup(vm);
    }

    private HostVO findAvailableKvmRestoreHost(final String hostIdentifier, final String restoreContext) {
        HostVO host = hostDao.findByIp(hostIdentifier);
        if (host == null) {
            host = hostDao.findByName(hostIdentifier);
        }
        if (host == null) {
            throw new CloudRuntimeException(String.format("Unable to find restore host [%s] for %s", hostIdentifier, restoreContext));
        }
        if (!Status.Up.equals(host.getStatus()) || !Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
            throw new CloudRuntimeException(String.format("Restore host [%s] is not an available KVM host for %s", host.getName(), restoreContext));
        }
        return host;
    }

    private Host resolveBackupSourceHostForRestore(final Backup backup) {
        if (backup == null) {
            return null;
        }
        loadBackupDetailsIfNeeded(backup);
        final String sourceHostName = getBackupDetail(backup, DETAIL_POLICY_NAME);
        if (StringUtils.isBlank(sourceHostName)) {
            return null;
        }
        Host host = hostDao.findByName(sourceHostName);
        if (host == null) {
            host = hostDao.findByIp(sourceHostName);
        }
        if (host == null) {
            throw new CloudRuntimeException(String.format(
                    "Unable to find backup source host [%s] for NetBackup restore from backup [%s]",
                    sourceHostName, backup.getUuid()));
        }
        if (!Status.Up.equals(host.getStatus()) || !Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
            throw new CloudRuntimeException(String.format(
                    "Backup source host [%s] is not an available KVM host for NetBackup restore from backup [%s]",
                    host.getName(), backup.getUuid()));
        }
        return host;
    }

    private Long getClusterIdFromRootVolume(final VirtualMachine vm) {
        final VolumeVO rootVolume = volumeDao.getInstanceRootVolume(vm.getId());
        if (rootVolume != null) {
            final StoragePoolVO rootDiskPool = primaryDataStoreDao.findById(rootVolume.getPoolId());
            if (rootDiskPool != null && rootDiskPool.getClusterId() != null) {
                return rootDiskPool.getClusterId();
            }
        }

        if (vm.getHostId() != null) {
            final HostVO host = hostDao.findById(vm.getHostId());
            if (host != null && host.getClusterId() != null) {
                return host.getClusterId();
            }
        }

        if (vm.getLastHostId() != null) {
            final HostVO host = hostDao.findById(vm.getLastHostId());
            if (host != null) {
                return host.getClusterId();
            }
        }
        return null;
    }

    private void validateVmSnapshotCoexistenceForBackup(final VirtualMachine vm) {
        if (hasDiskAndMemoryVmSnapshots(vm)) {
            LOG.warn("NetBackup backup operation is not allowed for VM [{}] with disk-and-memory VM snapshots.", vm.getUuid());
            throw new CloudRuntimeException(String.format("Cannot take backup of VM [%s] as it has disk-and-memory VM snapshots.", vm.getUuid()));
        }
        if (hasKvmFileBasedVmSnapshots(vm)) {
            LOG.debug("Allowing NetBackup backup for VM [{}] with KVM file-based VM snapshots.", vm.getUuid());
        }
    }

    private boolean hasDiskAndMemoryVmSnapshots(final VirtualMachine vm) {
        return CollectionUtils.isNotEmpty(vmSnapshotDao.findByVmAndByType(vm.getId(), VMSnapshot.Type.DiskAndMemory));
    }

    private boolean hasKvmFileBasedVmSnapshots(final VirtualMachine vm) {
        for (final VMSnapshotVO vmSnapshotVO : vmSnapshotDao.findByVmAndByType(vm.getId(), VMSnapshot.Type.Disk)) {
            final List<VMSnapshotDetailsVO> vmSnapshotDetails = vmSnapshotDetailsDao.listDetails(vmSnapshotVO.getId());
            if (vmSnapshotDetails.stream().anyMatch(detail -> VolumeApiServiceImpl.KVM_FILE_BASED_STORAGE_SNAPSHOT.equals(detail.getName()))) {
                return true;
            }
        }
        return false;
    }

    private String readFileContentsOnHost(final Long hostId, final String path) {
        if (hostId == null || StringUtils.isBlank(path)) {
            return null;
        }
        try {
            final Answer answer = agentManager.send(hostId, new AblestackNetBackupReadFileCommand(path));
            if (answer != null && answer.getResult()) {
                return answer.getDetails();
            }
            LOG.warn("Failed to read NetBackup file [{}] on host [{}]: {}",
                    path, hostId, answer != null ? answer.getDetails() : "no answer received");
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to read NetBackup file [{}] on host [{}]: {}", path, hostId, e.getMessage(), e);
        }
        return null;
    }

    @Override
    public boolean assignVMToBackupOffering(final VirtualMachine vm, final BackupOffering backupOffering) {
        if (hasDiskAndMemoryVmSnapshots(vm)) {
            LOG.warn("NetBackup backup offering assignment is not allowed for VM [{}] with disk-and-memory VM snapshots.", vm.getUuid());
            return false;
        }
        return Hypervisor.HypervisorType.KVM.equals(vm.getHypervisorType());
    }

    @Override
    public boolean removeVMFromBackupOffering(final VirtualMachine vm) {
        return true;
    }

    @Override
    public boolean willDeleteBackupsOnOfferingRemoval() {
        return false;
    }

    @Override
    public boolean deleteBackup(final Backup backup, final boolean forced) {
        if (backup == null) {
            return true;
        }
        loadBackupDetailsIfNeeded(backup);
        if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
            return thirdPartyBackupVolumeService.delete(backup, (artifact, metadata, ticket, policyExpiration) ->
                    getClient(backup.getZoneId()).deleteVolumeArtifact(artifact, metadata, ticket, policyExpiration));
        }
        if (Backup.Status.Error.equals(backup.getStatus()) && !forced) {
            throw new CloudRuntimeException("NetBackup backup in Error state requires forced deletion after manual cleanup verification.");
        }
        if (Backup.Status.Failed.equals(backup.getStatus()) || Backup.Status.Error.equals(backup.getStatus())) {
            return cleanupFailedOrErrorBackupArtifacts(backup, forced);
        }
        throw new CloudRuntimeException("NetBackup backups are managed by backup ID groups and cannot be deleted individually from Mold.");
    }

    private boolean cleanupFailedOrErrorBackupArtifacts(final Backup backup, final boolean forced) {
        if ("COMPLETED".equals(getBackupDetail(backup, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL))) { return true; }
        Host cleanupHost = null;
        try {
            cleanupHost = resolveBackupCleanupHost(backup);
        } catch (final RuntimeException e) {
            LOG.warn("Unable to resolve cleanup host for NetBackup backup [{}] in [{}] state before explicit delete: {}",
                    backup.getUuid(), backup.getStatus(), e.getMessage(), e);
        }
        if (cleanupHost == null) {
            LOG.warn("Skipping artifact cleanup for NetBackup backup [{}] in [{}] state before explicit delete because cleanup host could not be resolved. forced=[{}]",
                    backup.getUuid(), backup.getStatus(), forced);
            return false;
        }
        final boolean cleanupSuccessful = cleanupFailedBackupArtifacts(cleanupHost, backup);
        if (!cleanupSuccessful) {
            LOG.warn("Artifact cleanup failed for NetBackup backup [{}] in [{}] state before explicit delete. Metadata is retained until cleanup succeeds. forced=[{}]",
                    backup.getUuid(), backup.getStatus(), forced);
        }
        return cleanupSuccessful;
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(final VirtualMachine vm, final Backup backup, final String hostIp, final String dataStoreUuid, boolean quickRestore) {
        return restoreVirtualMachine(vm, backup, hostIp);
    }

    @Override
    public boolean supportsDetachedRestoreOrchestration() {
        return true;
    }

    @Override
    public Pair<Boolean, String> startRestoreBackupToVM(final VirtualMachine vm, final Backup backup,
            final String hostIp, final String dataStoreUuid, final boolean quickRestore) {
        detachedRestoreStart.set(true);
        try {
            return restoreBackupToVM(vm, backup, hostIp, dataStoreUuid, quickRestore);
        } finally {
            detachedRestoreStart.remove();
        }
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(final Long backupId, final String vmName) {
        final Backup backup = backupDao.findByIdIncludingRemoved(backupId);
        if (backup == null) {
            return new Pair<>(false, String.format("Backup [%s] was not found for NetBackup restore", backupId));
        }

        final VMInstanceVO vm = vmInstanceDao.findVMByInstanceName(vmName);
        if (vm == null) {
            return new Pair<>(false, String.format("VM [%s] was not found for NetBackup restore", vmName));
        }

        return restoreVirtualMachine(vm, backup, null);
    }

    @Override
    public boolean restoreVMFromBackup(final VirtualMachine vm, final Backup backup, boolean quickRestore, Long hostId) {
        return restoreVirtualMachine(vm, backup, null, false).first();
    }

    @Override
    public boolean startRestoreVMFromBackup(final VirtualMachine vm, final Backup backup, final boolean quickRestore,
            final Long hostId) {
        detachedRestoreStart.set(true);
        try {
            return restoreVMFromBackup(vm, backup, quickRestore, hostId);
        } finally {
            detachedRestoreStart.remove();
        }
    }

    public boolean restoreVMFromPreparedBackup(final VirtualMachine vm, final Backup backup, final String restoreHostIp) {
        return restoreVirtualMachine(vm, backup, restoreHostIp, true).first();
    }

    @Override
    public void cleanupPreparedRestore(final VirtualMachine vm, final Backup backup, final String restoreHostName) {
        if (backup == null || StringUtils.isBlank(restoreHostName) || StringUtils.isBlank(backup.getExternalId())) {
            return;
        }
        loadBackupDetailsIfNeeded(backup);
        LOG.info("Cleaning up prepared NetBackup restore after failed Mold restore validation. vm=[{}], backup=[{}], restoreHost=[{}], restoredPath=[{}]",
                vm != null ? vm.getInstanceName() : null, backup.getUuid(), restoreHostName, backup.getExternalId());
        cleanupBackupPathsOnHost(backup.getZoneId(), restoreHostName, Collections.singletonList(backup.getExternalId()));
    }

    private Pair<Boolean, String> restoreVirtualMachine(final VirtualMachine vm, final Backup backup, final String restoreHostIp) {
        return restoreVirtualMachine(vm, backup, restoreHostIp, false);
    }

    private Pair<Boolean, String> restoreVirtualMachine(final VirtualMachine vm, final Backup backup, final String restoreHostIp,
            final boolean restoreSourcesAlreadyPrepared) {
        thirdPartyBackupStagingService.requireEnabled();
        loadBackupDetailsIfNeeded(backup);
        validateRestoreChainIntegrity(backup);
        validateNetBackupRestoreSnapshotCompatibility(vm);
        loadBackupDetailsIfNeeded(backup);
        if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
            return restoreStagedVm(vm, backup, restoreHostIp);
        }
        final Host host = resolveRestoreHost(vm, backup, restoreHostIp);
        final List<Backup> restoreChain = getRestoreChainForBackup(backup);
        validateRestoreStagingPaths(host, restoreChain);
        final List<Backup> stagedRestoreChain = getStagedRestoreChainForBackup(backup);
        final boolean incrementalRestore = StringUtils.equalsIgnoreCase(BACKUP_TYPE_INCREMENTAL, backup.getType());
        LOG.info("{} phase=[PROVIDER_ENTER], vmId=[{}], vmName=[{}], backupId=[{}], backupUuid=[{}], backupType=[{}], restoreHost=[{}], preparedSourcesAlreadyPrepared=[{}], incrementalRestore=[{}], restoreChain=[{}]",
                RESTORE_TRACE, vm.getId(), vm.getInstanceName(), backup.getId(), backup.getUuid(), backup.getType(), host.getName(),
                restoreSourcesAlreadyPrepared, incrementalRestore, restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
        LOG.info("NetBackup restore flow starting. vm=[{}], backup=[{}], restoreHost=[{}], preparedSourcesAlreadyPrepared=[{}], incrementalRestore=[{}], restoreChain={}",
                vm.getInstanceName(), backup.getUuid(), host.getName(), restoreSourcesAlreadyPrepared, incrementalRestore,
                restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
        final List<Backup> restoreSourcesToPrepare = incrementalRestore && !restoreSourcesAlreadyPrepared ? restoreChain : stagedRestoreChain;
        try {
            if (incrementalRestore) {
                if (restoreSourcesAlreadyPrepared) {
                    LOG.info("Skipping NetBackup root restore job completion wait for prepared incremental restore. vm=[{}], backup=[{}], restoreHost=[{}], rootPath=[{}]",
                            vm.getInstanceName(), backup.getUuid(), host.getName(), backup.getExternalId());
                    waitForPreparedRestorePathOnDestinationHost(host, backup.getExternalId());
                } else {
                    LOG.info("Mold-initiated incremental restore will request the complete NetBackup restore chain. vm=[{}], backup=[{}], "
                                    + "restoreHost=[{}], restoreSourcesToPrepare={}",
                            vm.getInstanceName(), backup.getUuid(), host.getName(),
                            restoreSourcesToPrepare.stream().map(Backup::getExternalId).collect(Collectors.toList()));
                }
                if (restoreSourcesAlreadyPrepared) {
                    LOG.info("Prepared incremental restore will skip the already-restored target path from staged sources. vm=[{}], backup=[{}], "
                                    + "excludedPath=[{}], stagedRestoreChain={}",
                            vm.getInstanceName(), backup.getUuid(), backup.getExternalId(),
                            stagedRestoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
                }
            }
            if (!restoreSourcesAlreadyPrepared || incrementalRestore) {
                ensureStageHostHasCapacityForRestore(host, restoreSourcesToPrepare, null,
                        vm.getInstanceName(), backup.getUuid(), null);
                prepareRestoreSourcesOnStageHosts(vm.getDataCenterId(), host.getName(), restoreSourcesToPrepare);
            }

            final List<Backup.VolumeInfo> backupVolumes = backup.getBackedUpVolumes();
            if (backupVolumes == null || backupVolumes.isEmpty()) {
                throw new CloudRuntimeException(String.format("Backup [%s] does not contain backed up volume information.", backup.getUuid()));
            }

            final List<String> backedVolumesUUIDs = backupVolumes.stream()
                    .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                    .map(Backup.VolumeInfo::getUuid)
                    .collect(Collectors.toList());

            final List<VolumeVO> restoreVolumes = volumeDao.findByInstance(vm.getId()).stream()
                    .sorted(Comparator.comparingLong(VolumeVO::getDeviceId))
                    .collect(Collectors.toList());
            if (restoreVolumes.size() != backupVolumes.size()) {
                throw new CloudRuntimeException(String.format(
                        "Unable to restore VM [%s] from NetBackup [%s] because the backup has [%s] disks but the VM has [%s] disks.",
                        vm.getInstanceName(), backup.getUuid(), backupVolumes.size(), restoreVolumes.size()));
            }

            final String restoreJobId = AblestackBackupFrameworkUtils.createRestoreJobId(getName(), backup.getUuid(), vm.getInstanceName(), null);
            final AblestackNetBackupRestoreBackupCommand restoreCommand = new AblestackNetBackupRestoreBackupCommand();
            restoreCommand.setRestoreJobId(restoreJobId);
            restoreCommand.setBackupPath(backup.getExternalId());
            restoreCommand.setVmName(vm.getName());
            restoreCommand.setBackupVolumesUUIDs(backedVolumesUUIDs);
            restoreCommand.setBackupFiles(getBackupFiles(backupVolumes, backup));
            restoreCommand.setBackupFileChains(getBackupFileChains(backupVolumes, backup));
            restoreCommand.setVolumeChainStates(getVolumeChainStates(backupVolumes, backup));
            final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(restoreVolumes);
            restoreCommand.setRestoreVolumePools(volumePoolsAndPaths.first());
            restoreCommand.setRestoreVolumePaths(volumePoolsAndPaths.second());
            restoreCommand.setVmExists(vm.getRemoved() == null);
            restoreCommand.setVmState(vm.getState());
            restoreCommand.setRestorePlan(createRestorePlan(false));
            restoreCommand.setTimeout(BackupDataOperationTimeout.value());
            restoreCommand.setWaitForCompletion(false);
            trackRestoreJob(backup, restoreJobId, host);

            final BackupAnswer answer;
            try {
                LOG.info("{} phase=[RESTORE_COMMAND_SEND], restoreJobId=[{}], jobLog=[{}], vmId=[{}], vmName=[{}], "
                                + "backupId=[{}], backupUuid=[{}], restoreHostId=[{}], restoreHostName=[{}], backupPath=[{}]",
                        RESTORE_TRACE, restoreJobId, AblestackBackupFrameworkUtils.getAsyncRestoreJobLogPath(restoreJobId),
                        vm.getId(), vm.getInstanceName(), backup.getId(), backup.getUuid(), host.getId(), host.getName(), backup.getExternalId());
                answer = sendAndWaitForRestore(host.getId(), restoreCommand, restoreJobId);
            } catch (final AgentUnavailableException e) {
                LOG.error("{} phase=[PROVIDER_FAILED], vmId=[{}], vmName=[{}], backupId=[{}], backupUuid=[{}], restoreHost=[{}], reason=[{}]",
                        RESTORE_TRACE, vm.getId(), vm.getInstanceName(), backup.getId(), backup.getUuid(), host.getName(),
                        "Unable to contact backend control plane to initiate NetBackup restore");
                throw new CloudRuntimeException("Unable to contact backend control plane to initiate NetBackup restore", e);
            } catch (final OperationTimedoutException e) {
                LOG.error("{} phase=[PROVIDER_FAILED], vmId=[{}], vmName=[{}], backupId=[{}], backupUuid=[{}], restoreHost=[{}], reason=[{}]",
                        RESTORE_TRACE, vm.getId(), vm.getInstanceName(), backup.getId(), backup.getUuid(), host.getName(),
                        "Operation to restore NetBackup backup timed out");
                throw new CloudRuntimeException("Operation to restore NetBackup backup timed out, please try again", e);
            }
            LOG.info("{} phase=[PROVIDER_DONE], vmId=[{}], vmName=[{}], backupId=[{}], backupUuid=[{}], restoreHost=[{}], result=[{}], details=[{}]",
                    RESTORE_TRACE, vm.getId(), vm.getInstanceName(), backup.getId(), backup.getUuid(), host.getName(),
                    answer != null && answer.getResult(), answer != null ? answer.getDetails() : null);
            LOG.info("{} phase=[RESTORE_COMMAND_DONE], restoreJobId=[{}], vmId=[{}], vmName=[{}], backupId=[{}], "
                            + "backupUuid=[{}], restoreHostId=[{}], restoreHostName=[{}], result=[{}], details=[{}]",
                    RESTORE_TRACE, restoreJobId, vm.getId(), vm.getInstanceName(), backup.getId(), backup.getUuid(),
                    host.getId(), host.getName(), answer != null && answer.getResult(), answer != null ? answer.getDetails() : null);
            return new Pair<>(answer != null && answer.getResult(), answer != null ? answer.getDetails() : null);
        } finally {
            if (!Boolean.TRUE.equals(detachedRestoreStart.get())) {
                cleanupRestoreSourcesOnStageHosts(vm.getDataCenterId(), host.getName(), restoreSourcesToPrepare);
            }
        }
    }

    private void validateNetBackupRestoreSnapshotCompatibility(final VirtualMachine vm) {
        final List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vm.getId());
        if (CollectionUtils.isNotEmpty(vmSnapshots)) {
            throw new CloudRuntimeException(String.format(
                    "Unable to restore VM [%s] from NetBackup while Instance snapshots exist. Remove Instance snapshots before restoring the backup.",
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
                        "Unable to restore VM [%s] from NetBackup while RBD volume snapshots exist on volume [%s]. Remove RBD volume snapshots before restoring the backup.",
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

    @Override
    public Pair<Boolean, String> restoreBackedUpVolume(final Backup backup, final Backup.VolumeInfo backupVolumeInfo, final String hostIp,
            final String dataStoreUuid, final Pair<String, VirtualMachine.State> vmNameAndState, VirtualMachine targetVm, boolean quickRestore) {
        thirdPartyBackupStagingService.requireEnabled();
        loadBackupDetailsIfNeeded(backup);
        if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
            return restoreStagedVolume(backup, backupVolumeInfo, hostIp, dataStoreUuid, targetVm);
        }
        validateRestoreChainIntegrity(backup);

        final StoragePoolVO pool = primaryDataStoreDao.findByUuid(dataStoreUuid);
        if (pool == null) {
            throw new CloudRuntimeException(String.format("Unable to find datastore [%s] for NetBackup volume restore", dataStoreUuid));
        }

        final HostVO restoreHost = findAvailableKvmRestoreHost(hostIp, "NetBackup volume restore");

        final Backup.VolumeInfo matchingVolume = getBackedUpVolumeInfo(backup.getBackedUpVolumes(), backupVolumeInfo.getUuid());
        if (matchingVolume == null) {
            throw new CloudRuntimeException(String.format(
                    "Unable to find volume [%s] in backed up volumes for backup [%s]", backupVolumeInfo.getUuid(), backup.getUuid()));
        }

        final DiskOffering diskOffering = diskOfferingDao.findByUuid(backupVolumeInfo.getDiskOfferingId());
        if (diskOffering == null) {
            throw new CloudRuntimeException(String.format("Unable to find disk offering [%s] for restored volume",
                    backupVolumeInfo.getDiskOfferingId()));
        }
        final VolumeVO volume = volumeDao.findByUuid(backupVolumeInfo.getUuid());
        String cacheMode = null;
        final VMInstanceVO vm = vmInstanceDao.findVMByInstanceName(vmNameAndState.first());
        if (vm == null) {
            throw new CloudRuntimeException(String.format("Unable to find VM [%s] for NetBackup volume restore", vmNameAndState.first()));
        }
        final List<VolumeVO> rootVolumes = volumeDao.findByInstanceAndType(vm.getId(), Volume.Type.ROOT);
        if (CollectionUtils.isNotEmpty(rootVolumes)) {
            final VolumeVO rootDisk = rootVolumes.get(0);
            final DiskOffering baseDiskOffering = diskOfferingDao.findById(rootDisk.getDiskOfferingId());
            if (baseDiskOffering != null && baseDiskOffering.getCacheMode() != null) {
                cacheMode = baseDiskOffering.getCacheMode().toString();
            }
        }

        final List<Backup> restoreChain = getRestoreChainForBackup(backup);
        final List<Backup> stagedRestoreChain = getStagedRestoreChainForBackup(backup);
        final List<Backup> restoreSourcesToPrepare = StringUtils.equalsIgnoreCase(BACKUP_TYPE_INCREMENTAL, backup.getType()) ? restoreChain : stagedRestoreChain;
        validateRestoreStagingPaths(restoreHost, restoreChain);
        try {
            LOG.info("{} phase=[PROVIDER_ENTER], vmName=[{}], backupId=[{}], backupUuid=[{}], backupType=[{}], backupVolumeUuid=[{}], restoreHost=[{}], dataStoreUuid=[{}], restoreChain=[{}]",
                    RESTORE_TRACE, vmNameAndState.first(), backup.getId(), backup.getUuid(), backup.getType(), backupVolumeInfo.getUuid(),
                    restoreHost.getName(), dataStoreUuid, restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
            ensureStageHostHasCapacityForRestore(restoreHost, restoreSourcesToPrepare, Collections.singleton(matchingVolume.getUuid()),
                    vmNameAndState.first(), backup.getUuid(), matchingVolume.getUuid());
            prepareRestoreSourcesOnStageHosts(backup.getZoneId(), restoreHost.getName(), restoreSourcesToPrepare,
                    Collections.singleton(matchingVolume.getUuid()));

            final VolumeVO restoredVolume = new VolumeVO(Volume.Type.DATADISK, null, backup.getZoneId(),
                    backup.getDomainId(), backup.getAccountId(), 0, null, backup.getSize(), null, null, null);
            final String volumeUuid = UUID.randomUUID().toString();
            final String volumeName = volume != null ? volume.getName() : backupVolumeInfo.getUuid();
            restoredVolume.setName("RestoredVol-" + volumeName);
            restoredVolume.setProvisioningType(diskOffering.getProvisioningType());
            restoredVolume.setUpdated(new Date());
            restoredVolume.setUuid(volumeUuid);
            restoredVolume.setRemoved(null);
            restoredVolume.setDisplayVolume(true);
            restoredVolume.setPoolId(pool.getId());
            restoredVolume.setPoolType(pool.getPoolType());
            restoredVolume.setPath(restoredVolume.getUuid());
            restoredVolume.setState(Volume.State.Copying);
            restoredVolume.setSize(backupVolumeInfo.getSize());
            restoredVolume.setDiskOfferingId(diskOffering.getId());
            restoredVolume.setFormat(pool.getPoolType() != Storage.StoragePoolType.RBD ? Storage.ImageFormat.QCOW2 : Storage.ImageFormat.RAW);

            final String restoreJobId = AblestackBackupFrameworkUtils.createRestoreJobId(getName(), backup.getUuid(),
                    vmNameAndState.first(), volumeUuid);
            final AblestackNetBackupRestoreBackupCommand restoreCommand = new AblestackNetBackupRestoreBackupCommand();
            restoreCommand.setRestoreJobId(restoreJobId);
            restoreCommand.setBackupPath(backup.getExternalId());
            restoreCommand.setVmName(vmNameAndState.first());
            restoreCommand.setBackupFiles(Collections.singletonList(isLegacyBackup(backup) ? getLegacyBackupFileName(matchingVolume) : matchingVolume.getPath()));
            if (!isLegacyBackup(backup)) {
                restoreCommand.setBackupFileChains(Collections.singletonList(getBackupFileChain(matchingVolume, backup)));
            }
            restoreCommand.setVolumeChainStates(getVolumeChainStates(Collections.singletonList(matchingVolume), backup));
            final String restoreVolumePath = String.format("%s/%s", getVolumePathPrefix(pool), volumeUuid);
            restoreCommand.setRestoreVolumePaths(Collections.singletonList(restoreVolumePath));
            final DataStore dataStore = dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
            if (dataStore == null) {
                throw new CloudRuntimeException(String.format(
                        "Unable to get primary datastore TO for pool [%s] while restoring volume [%s]", pool.getUuid(), backupVolumeInfo.getUuid()));
            }
            restoreCommand.setRestoreVolumePools(Collections.singletonList((PrimaryDataStoreTO) dataStore.getTO()));
            restoreCommand.setDiskType(matchingVolume.getType().name().toLowerCase(Locale.ROOT));
            restoreCommand.setVmExists(null);
            restoreCommand.setVmState(vmNameAndState.second());
            restoreCommand.setRestoreVolumeUUID(backupVolumeInfo.getUuid());
            restoreCommand.setRestorePlan(createRestorePlan(AblestackBackupFrameworkUtils.requiresRunningVmAttach(vmNameAndState.second())));
            restoreCommand.setTimeout(BackupDataOperationTimeout.value());
            restoreCommand.setCacheMode(cacheMode);
            restoreCommand.setWaitForCompletion(false);
            trackRestoreJob(backup, restoreJobId, restoreHost);

            final BackupAnswer answer;
            try {
                LOG.info("{} phase=[RESTORE_VOLUME_COMMAND_SEND], restoreJobId=[{}], jobLog=[{}], vmName=[{}], backupId=[{}], "
                                + "backupUuid=[{}], backupVolumeUuid=[{}], restoredVolumeUuid=[{}], restoreHostId=[{}], "
                                + "restoreHostName=[{}], backupPath=[{}]",
                        RESTORE_TRACE, restoreJobId, AblestackBackupFrameworkUtils.getAsyncRestoreJobLogPath(restoreJobId),
                        vmNameAndState.first(), backup.getId(), backup.getUuid(), backupVolumeInfo.getUuid(), volumeUuid,
                        restoreHost.getId(), restoreHost.getName(), backup.getExternalId());
                answer = sendAndWaitForRestore(restoreHost.getId(), restoreCommand, restoreJobId);
            } catch (AgentUnavailableException e) {
                LOG.error("{} phase=[PROVIDER_FAILED], vmName=[{}], backupId=[{}], backupUuid=[{}], backupVolumeUuid=[{}], restoreHost=[{}], reason=[{}]",
                        RESTORE_TRACE, vmNameAndState.first(), backup.getId(), backup.getUuid(), backupVolumeInfo.getUuid(), restoreHost.getName(),
                        "Unable to contact backend control plane to initiate NetBackup restore");
                throw new CloudRuntimeException("Unable to contact backend control plane to initiate NetBackup restore");
            } catch (OperationTimedoutException e) {
                LOG.error("{} phase=[PROVIDER_FAILED], vmName=[{}], backupId=[{}], backupUuid=[{}], backupVolumeUuid=[{}], restoreHost=[{}], reason=[{}]",
                        RESTORE_TRACE, vmNameAndState.first(), backup.getId(), backup.getUuid(), backupVolumeInfo.getUuid(), restoreHost.getName(),
                        "Operation to restore backed up volume timed out");
                throw new CloudRuntimeException("Operation to restore backed up volume timed out, please try again");
            }

            if (answer != null && answer.getResult()) {
                try {
                    volumeDao.persist(restoredVolume);
                } catch (Exception e) {
                    throw new CloudRuntimeException("Unable to create restored volume due to: " + e);
                }
                LOG.info("{} phase=[PROVIDER_DONE], vmName=[{}], backupId=[{}], backupUuid=[{}], backupVolumeUuid=[{}], restoreHost=[{}], restoredVolumeUuid=[{}]",
                        RESTORE_TRACE, vmNameAndState.first(), backup.getId(), backup.getUuid(), backupVolumeInfo.getUuid(), restoreHost.getName(), restoredVolume.getUuid());
                LOG.info("{} phase=[RESTORE_VOLUME_COMMAND_DONE], restoreJobId=[{}], vmName=[{}], backupId=[{}], backupUuid=[{}], "
                                + "backupVolumeUuid=[{}], restoredVolumeUuid=[{}], restoreHostId=[{}], restoreHostName=[{}], result=[{}], details=[{}]",
                        RESTORE_TRACE, restoreJobId, vmNameAndState.first(), backup.getId(), backup.getUuid(), backupVolumeInfo.getUuid(),
                        volumeUuid, restoreHost.getId(), restoreHost.getName(), true, answer.getDetails());
                return new Pair<>(true, restoredVolume.getUuid());
            }

            LOG.error("{} phase=[PROVIDER_FAILED], vmName=[{}], backupId=[{}], backupUuid=[{}], backupVolumeUuid=[{}], restoreHost=[{}], reason=[{}]",
                    RESTORE_TRACE, vmNameAndState.first(), backup.getId(), backup.getUuid(), backupVolumeInfo.getUuid(), restoreHost.getName(),
                    answer != null ? answer.getDetails() : "NetBackup restore agent returned no response");
            LOG.warn("{} phase=[RESTORE_VOLUME_COMMAND_FAILED], restoreJobId=[{}], vmName=[{}], backupId=[{}], backupUuid=[{}], "
                            + "backupVolumeUuid=[{}], restoredVolumeUuid=[{}], restoreHostId=[{}], restoreHostName=[{}], result=[{}], details=[{}]",
                    RESTORE_TRACE, restoreJobId, vmNameAndState.first(), backup.getId(), backup.getUuid(), backupVolumeInfo.getUuid(),
                    volumeUuid, restoreHost.getId(), restoreHost.getName(), false, answer != null ? answer.getDetails() : null);
            return new Pair<>(false, answer != null ? answer.getDetails() : "NetBackup restore agent returned no response");
        } finally {
            if (!Boolean.TRUE.equals(detachedRestoreStart.get())) {
                cleanupRestoreSourcesOnStageHosts(backup.getZoneId(), restoreHost.getName(), restoreSourcesToPrepare);
            }
        }
    }

    @Override
    public Pair<Boolean, String> startRestoreBackedUpVolume(final Backup backup,
            final Backup.VolumeInfo backupVolumeInfo, final String hostIp, final String dataStoreUuid,
            final Pair<String, VirtualMachine.State> vmNameAndState, final VirtualMachine targetVm,
            final boolean quickRestore) {
        detachedRestoreStart.set(true);
        try {
            return restoreBackedUpVolume(backup, backupVolumeInfo, hostIp, dataStoreUuid, vmNameAndState,
                    targetVm, quickRestore);
        } finally {
            detachedRestoreStart.remove();
        }
    }

    private void waitForPreparedRestorePathOnDestinationHost(final Host destinationHost, final String restorePath) {
        final AblestackNetBackupResolveRestorePathCommand command = new AblestackNetBackupResolveRestorePathCommand(
                restorePath, Collections.singletonList(restorePath), NetBackupPreparedRestorePathReadyTimeout.value());
        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(destinationHost.getId(), command);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(answer != null ? answer.getDetails() : String.format(
                        "No response from destination host [%s] while waiting for NetBackup restore path [%s].",
                        destinationHost.getName(), restorePath));
            }
            LOG.info("Prepared NetBackup restore path [{}] is ready on destination host [{}].",
                    answer.getDetails(), destinationHost.getName());
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException(String.format(
                    "Unable to contact destination host [%s] while waiting for NetBackup restore path [%s].",
                    destinationHost.getName(), restorePath), e);
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException(String.format(
                    "Timed out waiting for destination host [%s] to confirm NetBackup restore path [%s].",
                    destinationHost.getName(), restorePath), e);
        }
    }

    @Override
    public String getRestoreJobState(final Long zoneId, final String recoveryJobId) {
        if (StringUtils.isBlank(recoveryJobId)) {
            return null;
        }
        if (zoneId == null) {
            return null;
        }
        return getClient(zoneId).getRecoveryJobState(recoveryJobId);
    }

    @Override
    public void syncBackupMetrics(final Long zoneId) {
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(final VirtualMachine vm) {
        final List<Backup.RestorePoint> restorePoints = new ArrayList<>();
        for (final Backup backup : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (!Backup.Status.BackedUp.equals(backup.getStatus())
                    || backup.getDate() == null
                    || StringUtils.isBlank(backup.getExternalId())) {
                continue;
            }
            final BackupOfferingVO backupOffering = backupOfferingDao.findById(backup.getBackupOfferingId());
            if (backupOffering == null || !StringUtils.equalsIgnoreCase(getName(), backupOffering.getProvider())) {
                continue;
            }
            restorePoints.add(new Backup.RestorePoint(
                    backup.getExternalId(),
                    backup.getDate(),
                    backup.getType(),
                    backup.getSize(),
                    backup.getProtectedSize()));
        }
        restorePoints.sort(Comparator.comparing(Backup.RestorePoint::getCreated).reversed());
        return restorePoints;
    }

    @Override
    public Backup createNewBackupEntryForRestorePoint(final Backup.RestorePoint restorePoint, final VirtualMachine vm) {
        throw new CloudRuntimeException("NetBackup provider does not import out-of-band restore points.");
    }

    @Override
    public boolean supportsInstanceFromBackup() {
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
    public void runPostRestoreMaintenance(final VirtualMachine vm, final Backup backup, final boolean volumeOnly) {
        if (backup == null || CollectionUtils.isEmpty(backup.getBackedUpVolumes())) {
            return;
        }
        loadBackupDetailsIfNeeded(backup);
        final List<BackupVolumeChainState> chainStates = getVolumeChainStates(backup.getBackedUpVolumes(), backup);
        AblestackBackupFrameworkUtils.validateVolumeChainStates(chainStates);
        final String restoreHostName = getBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_HOST_NAME_DETAIL);
        if (StringUtils.isNotBlank(restoreHostName)) {
            cleanupRestoreSourcesOnStageHosts(backup.getZoneId(), restoreHostName, getStagedRestoreChainForBackup(backup));
        }
        LOG.debug("Completed NetBackup post-restore maintenance for VM [{}], backup [{}], volumeOnly=[{}]",
                vm != null ? vm.getInstanceName() : null, backup.getUuid(), volumeOnly);
    }

    @Override
    public void onVmRestoreCompleted(final VirtualMachine vm, final Backup backup) {
        sealActiveBackupChainsAfterRestore(vm);
    }

    private void sealActiveBackupChainsAfterRestore(final VirtualMachine vm) {
        if (vm == null || vm.getBackupOfferingId() == null) {
            return;
        }
        final Map<Long, Backup> latestBackupsBySchedule = new HashMap<>();
        for (final Backup candidate : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (!Backup.Status.BackedUp.equals(candidate.getStatus())
                    || !Objects.equals(candidate.getBackupOfferingId(), vm.getBackupOfferingId())
                    || !isNetBackupBackup(candidate)) {
                continue;
            }
            final Long scheduleId = candidate.getBackupScheduleId();
            final Backup current = latestBackupsBySchedule.get(scheduleId);
            if (current == null || candidate.getDate().after(current.getDate())) {
                latestBackupsBySchedule.put(scheduleId, candidate);
            }
        }
        final String reason = "vm-restore-completed";
        for (final Backup latestBackup : latestBackupsBySchedule.values()) {
            loadBackupDetailsIfNeeded(latestBackup);
            if (Boolean.parseBoolean(getBackupDetail(latestBackup, DETAIL_CHAIN_SEALED))) {
                continue;
            }
            updateBackupDetail(latestBackup, DETAIL_CHAIN_SEALED, Boolean.TRUE.toString());
            updateBackupDetail(latestBackup, DETAIL_CHAIN_SEAL_REASON, reason);
            LOG.info("Sealed NetBackup backup chain [{}] for VM [{}] after successful VM restore.",
                    latestBackup.getUuid(), vm.getInstanceName());
        }
    }

    @Override
    public boolean supportsMemoryVmSnapshot() {
        return false;
    }

    @Override
    public Pair<Long, Long> getBackupStorageStats(final Long zoneId) {
        return new Pair<>(0L, 0L);
    }

    @Override
    public void syncBackupStorageStats(final Long zoneId) {
    }

    @Override
    public List<BackupOffering> listBackupOfferings(final Long zoneId) {
        return Collections.singletonList(new AblestackNetBackupOffering(
                NETBACKUP_OFFERING_NAME,
                NETBACKUP_OFFERING_EXTERNAL_ID
        ));
    }

    @Override
    public boolean isValidProviderOffering(final Long zoneId, final String uuid) {
        return StringUtils.equalsIgnoreCase(uuid, NETBACKUP_OFFERING_EXTERNAL_ID);
    }

    @Override
    public Boolean crossZoneInstanceCreationEnabled(final BackupOffering backupOffering) {
        return false;
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
            NetBackupPreparedRestorePathReadyTimeout,
            NetBackupUrl,
            NetBackupApiKey,
            NetBackupApiRequestTimeout,
            NetBackupRecoveryJobTimeout
        };
    }

    @Override
    public String getName() {
        return "ablestack-netbackup";
    }

    @Override
    public String getDescription() {
        return "NetBackup Backup Plugin";
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    private List<String> getBackupFiles(final List<Backup.VolumeInfo> backedVolumes, final Backup backup) {
        return AblestackBackupFrameworkUtils.buildRestoreBackupFiles(backedVolumes, isLegacyBackup(backup), this::getLegacyBackupFileName);
    }

    private BackupRestorePlan createRestorePlan(final boolean attachRequired) {
        return AblestackBackupFrameworkUtils.createRestorePlan(attachRequired, true);
    }

    private List<String> getBackupFileChains(final List<Backup.VolumeInfo> backupVolumes, final Backup backup) {
        return AblestackBackupFrameworkUtils.buildRestoreBackupFileChains(backupVolumes, volume -> getBackupChain(volume, backup));
    }

    private String getBackupFileChain(final Backup.VolumeInfo backupVolume, final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        return AblestackBackupFrameworkUtils.buildRestoreBackupFileChain(backupVolume, volume -> getBackupChain(volume, backup));
    }

    private List<BackupVolumeChainState> getVolumeChainStates(final List<Backup.VolumeInfo> backupVolumes, final Backup backup) {
        final String backupEngine = getBackupDetail(backup, DETAIL_BACKUP_ENGINE);
        return AblestackBackupFrameworkUtils.buildRestoreVolumeChainStates(backupVolumes, backupEngine,
                volume -> getBackupChain(volume, backup));
    }

    private List<String> getBackupChain(final Backup.VolumeInfo backupVolume, final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        final List<Backup> chain = getBackupChain(backup);
        final List<String> files = new ArrayList<>();
        for (final Backup chainBackup : chain) {
            final Backup.VolumeInfo volumeInfo = getBackedUpVolumeInfo(chainBackup.getBackedUpVolumes(), backupVolume.getUuid());
            if (volumeInfo != null) {
                final String filePath = BACKUP_ENGINE_RBD_DIFF.equals(getBackupDetail(chainBackup, DETAIL_BACKUP_ENGINE))
                        ? String.format("%s/%s", chainBackup.getExternalId(), volumeInfo.getPath())
                        : String.format("%s/%s", chainBackup.getExternalId(), volumeInfo.getPath());
                files.add(filePath);
            }
        }
        return files;
    }

    private List<Backup> getBackupChain(final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        final List<Backup> backups = backupDao.listByVmIdAndOffering(backup.getZoneId(), backup.getVmId(), backup.getBackupOfferingId());
        final Map<String, Backup> backupsByUuid = new HashMap<>();
        for (final Backup candidate : backups) {
            if (candidate instanceof BackupVO) {
                backupDao.loadDetails((BackupVO) candidate);
            }
            backupsByUuid.put(candidate.getUuid(), candidate);
        }

        final List<Backup> chain = new ArrayList<>();
        Backup current = backup;
        while (current != null) {
            chain.add(current);
            final String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            current = parentBackupUuid != null ? backupsByUuid.get(parentBackupUuid) : null;
        }
        Collections.reverse(chain);
        return chain;
    }

    private List<Backup> getRestoreChainForBackup(final Backup backup) {
        if (backup != null && StringUtils.equalsIgnoreCase(BACKUP_TYPE_INCREMENTAL, backup.getType())) {
            return getBackupChain(backup);
        }
        return Collections.singletonList(backup);
    }

    private List<Backup> getStagedRestoreChainForBackup(final Backup backup) {
        final List<Backup> restoreChain = getRestoreChainForBackup(backup);
        if (CollectionUtils.isEmpty(restoreChain)) {
            return restoreChain;
        }
        if (!StringUtils.equalsIgnoreCase(BACKUP_TYPE_INCREMENTAL, backup != null ? backup.getType() : null)) {
            return restoreChain;
        }
        if (restoreChain.size() <= 1) {
            return Collections.emptyList();
        }
        return new ArrayList<>(restoreChain.subList(0, restoreChain.size() - 1));
    }

    private void validateRestoreStagingPaths(final Host host, final List<Backup> restoreChain) {
        getAvailableBytesOnHostPath(host, getBackupStageRootPath());
        for (final Backup source : restoreChain) {
            getAvailableBytesOnHostPath(host, source.getExternalId());
        }
    }

    private void prepareRestoreSourcesOnStageHosts(final Long zoneId, final String destinationHostName, final List<Backup> restoreChain) {
        prepareRestoreSourcesOnStageHosts(zoneId, destinationHostName, restoreChain, null);
    }

    private void prepareRestoreSourcesOnStageHosts(final Long zoneId, final String destinationHostName, final List<Backup> restoreChain,
            final Set<String> requiredVolumeUuids) {
        if (CollectionUtils.isEmpty(restoreChain)) {
            return;
        }
        final HostVO destinationHost = findRestoreHost(destinationHostName);
        if (destinationHost == null) {
            throw new CloudRuntimeException(String.format(
                    "Unable to find destination host [%s] while preparing NetBackup restore sources.",
                    destinationHostName));
        }
        final AblestackNetBackupClient client = getClient(zoneId);
        for (final Map.Entry<String, List<Backup>> entry : groupRestoreChainByStageHost(destinationHostName, restoreChain).entrySet()) {
            final String sourceHost = entry.getKey();
            final List<Backup> sourceHostChain = entry.getValue();
            if (CollectionUtils.isEmpty(sourceHostChain)) {
                continue;
            }
            LOG.info("Preparing NetBackup restore sources from stage/source host [{}] to destination host [{}] for backup paths {}",
                    sourceHost, destinationHostName,
                    sourceHostChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
            LOG.info("{} phase=[PREPARE_SOURCE_BEGIN], sourceHost=[{}], destinationHost=[{}], backupPaths=[{}], requiredVolumeUuids=[{}]",
                    RESTORE_TRACE, sourceHost, destinationHostName,
                    sourceHostChain.stream().map(Backup::getExternalId).collect(Collectors.toList()), requiredVolumeUuids);
            final String chainJobId = client.restoreBackupChain(sourceHost, destinationHostName, sourceHostChain);
            LOG.info("{} phase=[PREPARE_SOURCE_JOB_CREATED], sourceHost=[{}], destinationHost=[{}], backupPaths=[{}], jobId=[{}]",
                    RESTORE_TRACE, sourceHost, destinationHostName,
                    sourceHostChain.stream().map(Backup::getExternalId).collect(Collectors.toList()), chainJobId);
            if (StringUtils.isNotBlank(chainJobId) && CollectionUtils.isNotEmpty(sourceHostChain)) {
                final Backup chainTarget = sourceHostChain.get(sourceHostChain.size() - 1);
                backupDetailsDao.removeDetail(chainTarget.getId(), DETAIL_RESTORE_CHAIN_JOB_ID);
                backupDetailsDao.addDetail(chainTarget.getId(), DETAIL_RESTORE_CHAIN_JOB_ID, chainJobId, false);
            }
            waitForPreparedRestoreFilesOnDestinationHost(destinationHost, sourceHostChain, requiredVolumeUuids);
        }
    }

    private void waitForPreparedRestoreFilesOnDestinationHost(final Host destinationHost, final List<Backup> restoreChain,
            final Set<String> requiredVolumeUuids) {
        if (destinationHost == null || CollectionUtils.isEmpty(restoreChain)) {
            return;
        }
        final List<String> restorePaths = restoreChain.stream()
                .map(Backup::getExternalId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        final List<String> requiredFiles = getRequiredRestoreChainFiles(restoreChain, requiredVolumeUuids);
        if (CollectionUtils.isEmpty(restorePaths) || CollectionUtils.isEmpty(requiredFiles)) {
            return;
        }
        final AblestackNetBackupResolveRestorePathCommand command = new AblestackNetBackupResolveRestorePathCommand(
                restorePaths.get(restorePaths.size() - 1), restorePaths, requiredFiles, NetBackupPreparedRestorePathReadyTimeout.value());
        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(destinationHost.getId(), command);
            if (answer == null || !answer.getResult()) {
                LOG.error("{} phase=[PREPARE_SOURCE_FAILED], destinationHost=[{}], restorePaths=[{}], requiredFiles=[{}], reason=[{}]",
                        RESTORE_TRACE, destinationHost.getName(), restorePaths, requiredFiles,
                        answer != null ? answer.getDetails() : "No response received");
                throw new CloudRuntimeException(answer != null ? answer.getDetails() : String.format(
                        "No response from destination host [%s] while waiting for NetBackup restore chain files [%s].",
                        destinationHost.getName(), requiredFiles));
            }
            LOG.info("{} phase=[PREPARE_SOURCE_READY], destinationHost=[{}], restorePaths=[{}], requiredFiles=[{}]",
                    RESTORE_TRACE, destinationHost.getName(), restorePaths, requiredFiles);
            LOG.info("Prepared NetBackup restore chain files are ready on destination host [{}]. paths={}, files={}",
                    destinationHost.getName(), restorePaths, requiredFiles);
        } catch (AgentUnavailableException e) {
            LOG.error("{} phase=[PREPARE_SOURCE_FAILED], destinationHost=[{}], restorePaths=[{}], requiredFiles=[{}], reason=[{}]",
                    RESTORE_TRACE, destinationHost.getName(), restorePaths, requiredFiles,
                    "Unable to contact destination host while waiting for NetBackup restore chain files");
            throw new CloudRuntimeException(String.format(
                    "Unable to contact destination host [%s] while waiting for NetBackup restore chain files [%s].",
                    destinationHost.getName(), requiredFiles), e);
        } catch (OperationTimedoutException e) {
            LOG.error("{} phase=[PREPARE_SOURCE_FAILED], destinationHost=[{}], restorePaths=[{}], requiredFiles=[{}], reason=[{}]",
                    RESTORE_TRACE, destinationHost.getName(), restorePaths, requiredFiles,
                    "Timed out waiting for destination host to confirm NetBackup restore chain files");
            throw new CloudRuntimeException(String.format(
                    "Timed out waiting for destination host [%s] to confirm NetBackup restore chain files [%s].",
                    destinationHost.getName(), requiredFiles), e);
        }
    }

    private List<String> getRequiredRestoreChainFiles(final List<Backup> restoreChain) {
        return getRequiredRestoreChainFiles(restoreChain, null);
    }

    private List<String> getRequiredRestoreChainFiles(final List<Backup> restoreChain, final Set<String> requiredVolumeUuids) {
        final List<String> requiredFiles = new ArrayList<>();
        final boolean volumeOnlyRestore = CollectionUtils.isNotEmpty(requiredVolumeUuids);
        for (final Backup chainBackup : restoreChain) {
            loadBackupDetailsIfNeeded(chainBackup);
            final List<Backup.VolumeInfo> backupVolumes = chainBackup.getBackedUpVolumes();
            if (CollectionUtils.isEmpty(backupVolumes)) {
                continue;
            }
            for (final Backup.VolumeInfo volumeInfo : backupVolumes) {
                if (volumeOnlyRestore && !requiredVolumeUuids.contains(volumeInfo.getUuid())) {
                    continue;
                }
                if (StringUtils.isBlank(chainBackup.getExternalId()) || StringUtils.isBlank(volumeInfo.getPath())) {
                    continue;
                }
                requiredFiles.add(String.format("%s/%s", chainBackup.getExternalId(), volumeInfo.getPath()));
            }
            if (!volumeOnlyRestore && StringUtils.isNotBlank(chainBackup.getExternalId())) {
                final String restorePath = StringUtils.removeEnd(chainBackup.getExternalId(), "/");
                requiredFiles.add(String.format("%s/domain-config.xml", restorePath));
                requiredFiles.add(String.format("%s/domblklist.xml", restorePath));
            }
            if (BACKUP_ENGINE_RBD_DIFF.equals(getBackupDetail(chainBackup, DETAIL_BACKUP_ENGINE))) {
                requiredFiles.add(String.format("%s/rbd-backup.meta", StringUtils.removeEnd(chainBackup.getExternalId(), "/")));
            }
        }
        return requiredFiles.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }

    private void cleanupRestoreSourcesOnStageHosts(final Long zoneId, final String destinationHostName, final List<Backup> restoreChain) {
        if (CollectionUtils.isEmpty(restoreChain)) {
            return;
        }
        final LinkedHashMap<String, List<Backup>> groupedRestoreChain = groupRestoreChainByStageHost(destinationHostName, restoreChain);
        final List<String> destinationRestorePaths = groupedRestoreChain.entrySet().stream()
                .filter(entry -> !StringUtils.equalsIgnoreCase(entry.getKey(), destinationHostName))
                .flatMap(entry -> entry.getValue().stream())
                .map(Backup::getExternalId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
        for (final Map.Entry<String, List<Backup>> entry : groupedRestoreChain.entrySet()) {
            final String sourceHost = entry.getKey();
            final List<Backup> sourceHostChain = entry.getValue();
            if (CollectionUtils.isEmpty(sourceHostChain)) {
                continue;
            }
            LOG.info("Cleaning up NetBackup restore sources on stage/source host [{}] for backup paths {}",
                    sourceHost, sourceHostChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
            try {
                cleanupBackupPathsOnHost(zoneId, sourceHost, sourceHostChain.stream()
                        .map(Backup::getExternalId)
                        .filter(StringUtils::isNotBlank)
                        .distinct()
                        .collect(Collectors.toList()));
            } catch (final Exception e) {
                LOG.warn("Failed to cleanup NetBackup restore sources on stage/source host [{}]. Restore result will be preserved. paths={}",
                        sourceHost, sourceHostChain.stream().map(Backup::getExternalId).collect(Collectors.toList()), e);
            }
        }
        if (CollectionUtils.isEmpty(destinationRestorePaths)) {
            return;
        }
        LOG.info("Cleaning up NetBackup restore sources on destination host [{}] for backup paths {}",
                destinationHostName, destinationRestorePaths);
        try {
            cleanupBackupPathsOnHost(zoneId, destinationHostName, destinationRestorePaths);
        } catch (final Exception e) {
            LOG.warn("Failed to cleanup NetBackup restore sources on destination host [{}]. Restore result will be preserved. paths={}",
                    destinationHostName, destinationRestorePaths, e);
        }
    }

    private boolean cleanupBackupPathsOnHost(final Long zoneId, final String hostName, final List<String> backupPaths) {
        if (CollectionUtils.isEmpty(backupPaths) || StringUtils.isBlank(hostName)) {
            return true;
        }
        final HostVO host = findRestoreHost(hostName);
        if (host == null) {
            LOG.warn("{} phase=[CLEANUP_SOURCE_FAILED], host=[{}], backupPaths=[{}], reason=[{}]",
                    RESTORE_TRACE, hostName, backupPaths, "Unable to find restore host");
            LOG.warn("Unable to find restore host [{}] while cleaning up NetBackup restore paths {}.", hostName, backupPaths);
            return false;
        }
        try {
            LOG.info("{} phase=[CLEANUP_SOURCE_BEGIN], host=[{}], backupPaths=[{}]",
                    RESTORE_TRACE, host.getName(), backupPaths);
            final Answer answer = agentManager.send(host.getId(), new AblestackNetBackupCleanupCommand(backupPaths, getBackupStageRootPath()));
            if (answer == null || !answer.getResult()) {
                LOG.warn("{} phase=[CLEANUP_SOURCE_FAILED], host=[{}], backupPaths=[{}], reason=[{}]",
                        RESTORE_TRACE, host.getName(), backupPaths, answer != null ? answer.getDetails() : "no answer received");
                LOG.warn("NetBackup restore cleanup command failed on host [{}]: {}",
                        host.getName(), answer != null ? answer.getDetails() : "no answer received");
                return false;
            }
            LOG.info("{} phase=[CLEANUP_SOURCE_DONE], host=[{}], backupPaths=[{}]",
                    RESTORE_TRACE, host.getName(), backupPaths);
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("{} phase=[CLEANUP_SOURCE_FAILED], host=[{}], backupPaths=[{}], reason=[{}]",
                    RESTORE_TRACE, host.getName(), backupPaths, e.getMessage());
            LOG.warn("Failed to execute NetBackup restore cleanup command on host [{}]: {}",
                    host.getName(), e.getMessage(), e);
            return false;
        }
        return true;
    }

    private HostVO findRestoreHost(final String restoreHostName) {
        HostVO host = hostDao.findByName(restoreHostName);
        if (host != null) {
            return host;
        }
        return hostDao.findByIp(restoreHostName);
    }

    private LinkedHashMap<String, List<Backup>> groupRestoreChainByStageHost(final String destinationHostName, final List<Backup> restoreChain) {
        final LinkedHashMap<String, List<Backup>> grouped = new LinkedHashMap<>();
        for (final Backup chainBackup : restoreChain) {
            loadBackupDetailsIfNeeded(chainBackup);
            final String sourceHost = getRestoreSourceHost(chainBackup, destinationHostName);
            grouped.computeIfAbsent(sourceHost, key -> new ArrayList<>()).add(chainBackup);
        }
        return grouped;
    }

    private String getRestoreSourceHost(final Backup backup, final String defaultHostName) {
        final String sourceHost = getBackupDetail(backup, DETAIL_POLICY_NAME);
        if (StringUtils.isBlank(sourceHost)) {
            LOG.warn("NetBackup source/stage host detail [{}] is missing for backup [{}]. Falling back to destination host [{}].",
                    DETAIL_POLICY_NAME, backup != null ? backup.getUuid() : null, defaultHostName);
            return defaultHostName;
        }
        return sourceHost;
    }



    private Pair<Boolean, String> restoreStagedVolume(Backup backup, Backup.VolumeInfo selection, String hostIdentifier,
            String poolUuid, VirtualMachine targetVm) {
        if (targetVm == null) { throw new CloudRuntimeException("A target VM is required for volume restore"); }
        Backup.VolumeInfo source = backup.getBackedUpVolumes().stream().filter(v -> v.getUuid().equals(selection.getUuid()))
                .findFirst().orElseThrow(() -> new CloudRuntimeException("Selected volume is absent from the backup"));
        StoragePoolVO pool = primaryDataStoreDao.findByUuid(poolUuid);
        if (pool == null || pool.getDataCenterId() != targetVm.getDataCenterId()) {
            throw new CloudRuntimeException("Restore primary datastore is missing or belongs to another zone");
        }
        DiskOffering offering = diskOfferingDao.findByUuid(source.getDiskOfferingId());
        if (offering == null) { throw new CloudRuntimeException("The backed up volume's disk offering is missing"); }
        Host host = thirdPartyBackupVolumeService.selectRestoreHost(targetVm, getName(), Collections.singletonList(pool.getId()),
                hostIdentifier, source.getSize());
        if (host == null || !Status.Up.equals(host.getStatus()) || host.getDataCenterId() != targetVm.getDataCenterId()
                || !Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
            throw new CloudRuntimeException("Restore Worker Host is unavailable or belongs to another zone");
        }
        if (VirtualMachine.State.Running.equals(targetVm.getState())
                && !java.util.Objects.equals(targetVm.getHostId(), host.getId())) {
            throw new CloudRuntimeException("A restored volume must be attached on the running VM's Host");
        }
        VolumeVO restored = new VolumeVO(Volume.Type.DATADISK, null, backup.getZoneId(), backup.getDomainId(),
                backup.getAccountId(), 0, null, source.getSize(), null, null, null);
        restored.setUuid(UUID.randomUUID().toString());
        restored.setName("RestoredVol-" + source.getUuid());
        restored.setProvisioningType(offering.getProvisioningType());
        restored.setUpdated(new Date());
        restored.setRemoved(null);
        restored.setDisplayVolume(true);
        restored.setPoolId(pool.getId());
        restored.setPoolType(pool.getPoolType());
        restored.setPath(restored.getUuid());
        restored.setState(Volume.State.Copying);
        restored.setSize(source.getSize());
        restored.setDiskOfferingId(offering.getId());
        restored.setFormat(pool.getPoolType() == Storage.StoragePoolType.RBD ? Storage.ImageFormat.RAW : Storage.ImageFormat.QCOW2);
        String cacheMode = null;
        List<VolumeVO> roots = volumeDao.findByInstanceAndType(targetVm.getId(), Volume.Type.ROOT);
        if (!roots.isEmpty()) {
            DiskOffering rootOffering = diskOfferingDao.findById(roots.get(0).getDiskOfferingId());
            if (rootOffering != null && rootOffering.getCacheMode() != null) { cacheMode = rootOffering.getCacheMode().toString(); }
        }
        Pair<Boolean, String> result = restoreStagedVolumes(targetVm, backup, host, Collections.singletonList(restored),
                Collections.singletonList(source.getUuid()), true, cacheMode);
        if (Boolean.TRUE.equals(result.first())) {
            volumeDao.persist(restored);
            return new Pair<>(true, restored.getUuid());
        }
        return result;
    }

    private Pair<Boolean, String> restoreStagedVm(VirtualMachine vm, Backup backup, String hostIdentifier) {
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(getBackupDetail(backup, ThirdPartyBackupManifest.DETAIL_KEY));
        manifest.validate(true);
        List<VolumeVO> targets = volumeDao.findByInstance(vm.getId()).stream()
                .sorted(Comparator.comparingLong(VolumeVO::getDeviceId)).collect(Collectors.toList());
        List<ThirdPartyBackupManifest.Volume> sources = manifest.getVolumes().stream()
                .sorted(Comparator.comparingLong(volume -> volume.deviceId)).collect(Collectors.toList());
        if (targets.size() != sources.size()) { throw new CloudRuntimeException("VM volume count differs from the selected backup"); }
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).getDeviceId() != sources.get(i).deviceId || targets.get(i).getSize() < sources.get(i).provisionedBytes) {
                throw new CloudRuntimeException("VM volume device or capacity differs from the selected backup");
            }
        }
        Host host = thirdPartyBackupVolumeService.selectRestoreHost(vm, getName(),
                targets.stream().map(VolumeVO::getPoolId).collect(Collectors.toList()), hostIdentifier, manifest.getRequiredStagingBytes());
        return restoreStagedVolumes(vm, backup, host, targets, sources.stream().map(v -> v.uuid).collect(Collectors.toList()), false, null);
    }

    private Pair<Boolean, String> restoreStagedVolumes(VirtualMachine vm, Backup backup, Host host, List<VolumeVO> targets,
            List<String> sourceUuids, boolean singleVolume, String cacheMode) {
        Pair<List<PrimaryDataStoreTO>, List<String>> destinations = getVolumePoolsAndPaths(targets);
        ThirdPartyBackupRestore.Plan plan = thirdPartyBackupVolumeService.prepareRestore(backup, host, sourceUuids,
                targets.stream().map(VolumeVO::getSize).collect(Collectors.toList()), vm.getInstanceName(), BackupDataOperationTimeout.value());
        AblestackNetBackupRestoreBackupCommand command = new AblestackNetBackupRestoreBackupCommand();
        command.setVolumeRestorePlan(plan);
        command.setRestoreJobId(plan.jobId);
        command.setBackupPath(plan.destination);
        command.setVmName(vm.getInstanceName());
        command.setRestoreVolumePools(destinations.first());
        command.setRestoreVolumePaths(destinations.second());
        command.setBackupVolumesUUIDs(sourceUuids);
        command.setVmExists(singleVolume ? null : vm.getRemoved() == null);
        command.setVmState(vm.getState());
        command.setRestoreVolumeUUID(singleVolume ? sourceUuids.get(0) : null);
        command.setRestorePlan(createRestorePlan(singleVolume && AblestackBackupFrameworkUtils.requiresRunningVmAttach(vm.getState())));
        command.setCacheMode(cacheMode);
        command.setTimeout(BackupDataOperationTimeout.value());
        command.setWaitForCompletion(false);
        trackRestoreJob(backup, plan.jobId, host);
        thirdPartyBackupVolumeService.trackRestore(backup, host, volumeRestoreTransfer(backup, host));
        try {
            thirdPartyBackupVolumeService.markRestoreDispatch(backup, plan);
            BackupAnswer answer = sendAndWaitForRestore(host.getId(), command, plan.jobId);
            if (answer == null || !answer.getResult()) {
                updateBackupDetail(backup, AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "FAILED");
            }
            return new Pair<>(answer != null && answer.getResult(), answer == null ? "Restore Worker Host returned no response" : answer.getDetails());
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Unable to start or await volume restore on the Worker Host", e);
        }
    }

    private ThirdPartyBackupVolumeService.RestoreTransfer volumeRestoreTransfer(Backup backup, Host host) {
        return (request, saved) -> {
            final AblestackNetBackupClient client = getClient(backup.getZoneId());
            if (StringUtils.isNotBlank(saved.jobId)) { return client.pollVolumeRestore(saved.jobId); }
            final java.util.Map<String, String> options = new java.util.HashMap<>();
            options.put("server", client.getPrimaryServerName());
            options.put("destinationClient", host.getName());
            options.put("request", new Gson().toJson(request));
            try {
                final Answer answer = agentManager.send(host.getId(), new AblestackVolumeStagingCommand(request.jobId,
                        saved.recoveryOnly ? "RESTORE_NETBACKUP_RECOVER" : "RESTORE_NETBACKUP", request.sequence, new Gson().toJson(options)));
                if (answer != null && !answer.getResult() && StringUtils.startsWith(answer.getDetails(), "ABLESTACK_NOT_SUBMITTED=")) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> rejected = new Gson().fromJson(
                            answer.getDetails().substring("ABLESTACK_NOT_SUBMITTED=".length()), java.util.Map.class);
                    if (Boolean.TRUE.equals(rejected.get("notSubmitted"))) {
                        throw new org.apache.cloudstack.backup.ThirdPartyBackupSubmissionException(String.valueOf(rejected.get("failure")));
                    }
                }
                if (answer == null || !answer.getResult() || StringUtils.isBlank(answer.getDetails())) {
                    throw new CloudRuntimeException("NetBackup artifact restore submission was not confirmed");
                }
                return new ThirdPartyBackupRestore.Result(answer.getDetails(), false);
            } catch (AgentUnavailableException | OperationTimedoutException e) {
                throw new CloudRuntimeException("Unable to submit NetBackup restore on the Worker Host", e);
            }
        };
    }

    @Override
    public void reconcileRestoreJob(Backup backup) {
        resumeStagedRestore(backup);
    }

    private void resumeStagedRestore(Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        String json = getBackupDetail(backup, ThirdPartyBackupRestore.PLAN_KEY);
        if (StringUtils.isBlank(json)) { return; }
        ThirdPartyBackupRestore.Plan plan = new Gson().fromJson(json, ThirdPartyBackupRestore.Plan.class);
        Host host = hostDao.findById(plan.hostId);
        if (host != null) { thirdPartyBackupVolumeService.trackRestore(backup, host, volumeRestoreTransfer(backup, host)); }
    }

    private void validateRestoreChainIntegrity(final Backup backup) {
        if (backup == null) {
            return;
        }
        loadBackupDetailsIfNeeded(backup);
        if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
            ThirdPartyBackupManifest.fromJson(getBackupDetail(backup, ThirdPartyBackupManifest.DETAIL_KEY)).validate(true);
            return;
        }
        if (isLegacyBackup(backup)) {
            return;
        }

        final Set<String> visitedBackupUuids = new HashSet<>();
        Backup current = backup;
        while (current != null) {
            final String currentBackupUuid = current.getUuid();
            if (StringUtils.isNotBlank(currentBackupUuid) && !visitedBackupUuids.add(currentBackupUuid)) {
                throw new CloudRuntimeException(String.format(
                        "Unable to restore backup [%s] because the incremental backup chain contains a cycle at [%s].",
                        backup.getUuid(), currentBackupUuid));
            }

            final String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (StringUtils.isBlank(parentBackupUuid)) {
                return;
            }

            final Backup parentBackup = backupDao.findByUuid(parentBackupUuid);
            if (parentBackup == null) {
                throw new CloudRuntimeException(String.format(
                        "Unable to restore backup [%s] because parent backup [%s] is missing from the incremental chain.",
                        backup.getUuid(), parentBackupUuid));
            }
            loadBackupDetailsIfNeeded(parentBackup);
            current = parentBackup;
        }
    }

    private boolean isLegacyBackup(final Backup backup) {
        return getBackupDetail(backup, DETAIL_BACKUP_ENGINE) == null;
    }

    private String getLegacyBackupFileName(final Backup.VolumeInfo volumeInfo) {
        final String diskPrefix = Volume.Type.ROOT.equals(volumeInfo.getType()) ? "root" : "datadisk";
        return String.format("%s.%s.qcow2", diskPrefix, volumeInfo.getUuid());
    }

    private Backup.VolumeInfo getBackedUpVolumeInfo(final List<Backup.VolumeInfo> backedUpVolumes, final String volumeUuid) {
        return backedUpVolumes.stream()
                .filter(v -> v.getUuid().equals(volumeUuid))
                .findFirst()
                .orElse(null);
    }

    private AblestackNetBackupClient getClient(final Long zoneId) {
        try {
            return new AblestackNetBackupClient(NetBackupUrl.valueIn(zoneId), NetBackupApiKey.valueIn(zoneId),
                    NetBackupApiRequestTimeout.valueIn(zoneId), NetBackupRecoveryJobTimeout.valueIn(zoneId));
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse NetBackup API URL: " + e.getMessage());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            LOG.error("Failed to build NetBackup API client due to: ", e);
        }
        throw new CloudRuntimeException("Failed to build NetBackup API client");
    }

    @Override
    public String getCatalogBackupTime(final Long zoneId, final String backupId) {
        if (StringUtils.isBlank(backupId)) {
            return null;
        }
        return getClient(zoneId).getCatalogBackupTime(backupId);
    }

    @Override
    public void syncBackups(final VirtualMachine vm) {
        AblestackNetBackupClient client = null;
        final Map<String, Boolean> volumeAvailability = new HashMap<>();
        final Set<Long> removedBackupIds = new HashSet<>();
        for (final Backup backup : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (removedBackupIds.contains(backup.getId())) {
                continue;
            }
            resumeStagedRestore(backup);
            if (reconcileBackingUpBackup(vm, backup)) {
                continue;
            }

            loadBackupDetailsIfNeeded(backup);
            if (isNetBackupBackup(backup) && ThirdPartyBackupManifest.VOLUME_MODE.equals(
                    getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
                if (StringUtils.isNotBlank(getBackupDetail(backup, ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))
                        || (backup.getDate() != null && backup.getDate().getTime() <= System.currentTimeMillis() - NETBACKUP_SYNC_DELETE_GRACE_MS)) {
                    try {
                        final AblestackNetBackupClient volumeClient = getClient(backup.getZoneId());
                        thirdPartyBackupVolumeService.reconcileCatalog(backup,
                                artifact -> volumeAvailability.computeIfAbsent(artifact.externalId, volumeClient::backupImageExists),
                                volumeClient::deleteVolumeArtifact);
                    } catch (RuntimeException e) {
                        LOG.debug("NetBackup volume catalog sync is unavailable for backup [{}]: {}", backup.getUuid(), e.getMessage());
                    }
                }
                continue;
            }

            if (!Backup.Status.BackedUp.equals(backup.getStatus()) || backup.getDate() == null) {
                continue;
            }
            if (backup.getDate().getTime() > System.currentTimeMillis() - NETBACKUP_SYNC_DELETE_GRACE_MS) {
                continue;
            }
            final BackupOfferingVO backupOffering = backupOfferingDao.findById(backup.getBackupOfferingId());
            if (backupOffering == null || !StringUtils.equalsIgnoreCase(getName(), backupOffering.getProvider())) {
                continue;
            }

            loadBackupDetailsIfNeeded(backup);
            final String backupId = getBackupDetail(backup, DETAIL_BACKUP_ID);
            if (StringUtils.isBlank(backupId)) {
                continue;
            }

            if (client == null) {
                client = getClient(backup.getZoneId());
            }
            if (client.backupImageExists(backupId)) {
                continue;
            }

            final List<Long> removedIds = removeBackupGroup(backupId);
            removedBackupIds.addAll(removedIds);
            LOG.warn("Removed NetBackup backup group identified by backupId [{}] for VM [{}] because the catalog image no longer exists in NetBackup. Removed backup row ids={}",
                    backupId, vm.getInstanceName(), removedIds);
        }
    }

    @Override
    public boolean reconcileBackingUpBackup(final VirtualMachine vm, final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
            if (!Backup.Status.BackingUp.equals(backup.getStatus()) && !(java.util.Set.of(Backup.Status.Failed, Backup.Status.Error, Backup.Status.Canceled).contains(backup.getStatus())
                    && StringUtils.isBlank(getBackupDetail(backup, ThirdPartyBackupManifest.CATALOG_FAILURE_KEY))
                    && StringUtils.isBlank(getBackupDetail(backup, ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))
                    && ThirdPartyBackupVolumeService.needsBackupCleanup(backup))) {
                return false;
            }
            final Host host = findBackupJobHost(backup, vm);
            if (host != null) {
                final ThirdPartyBackupVolumeService.Transfer transfer = volumeTransfer(vm, backup, host);
                thirdPartyBackupVolumeService.track(backup, host, transfer);
                thirdPartyBackupVolumeService.reconcile(backup, host, transfer);
            }
            return true;
        }
        if (syncCompletedStagingMetadata(vm, backup)) {
            return true;
        }
        if (!AblestackBackupFrameworkUtils.isStaleBackingUp(backup)) {
            return false;
        }
        loadBackupDetailsIfNeeded(backup);
        if (deferUnconfirmedStaleBackup(vm, backup)) { return true; }
        LOG.warn("Removing stale NetBackup backup [{}] for VM [{}] stuck in BackingUp for over one day. "
                        + "NetBackup post notify may have failed before updateNetBackup finalized the backup metadata. "
                        + "Check NetBackup runtime JSON/context files and catalog before removal if recovery is required. "
                        + "externalId=[{}], backupId=[{}], policyName=[{}], status=[{}], date=[{}].",
                backup.getUuid(), vm.getInstanceName(), backup.getExternalId(), getBackupDetail(backup, DETAIL_BACKUP_ID),
                getBackupDetail(backup, DETAIL_POLICY_NAME), backup.getStatus(), backup.getDate());
        if (!cleanupFailedOrErrorBackupArtifacts(backup, true)) {
            BackupVO backupVO = backupDao.findById(backup.getId());
            if (backupVO != null) {
                sealParentBackupChainIfIncremental(backupVO, "failed-stale-child");
                markBackupFailure(backupVO, "stale-cleanup", "Stale NetBackup artifact cleanup failed; cleanup host may be unavailable");
                backupVO.setStatus(Backup.Status.Failed);
                backupDao.update(backupVO.getId(), backupVO);
                LOG.warn("Marked stale NetBackup backup [{}] for VM [{}] as Failed because artifact cleanup could not be completed. "
                                + "The backup metadata is retained for later forced cleanup.",
                        backup.getUuid(), vm.getInstanceName());
                return true;
            }
        }
        removeBackupWithDetails(backup.getId());
        return true;
    }

    @Override
    public BackupStagingInfoResponse reconcileStagingJob(Backup backup, String operation,
            String jobId, String action, Integer artifactIndex, String externalJobId) {
        loadBackupDetailsIfNeeded(backup);
        ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(getBackupDetail(backup, ThirdPartyBackupManifest.DETAIL_KEY));
        VirtualMachine vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        Host host = thirdPartyBackupVolumeService.getWorkerHost(backup, operation);
        if (vm == null || (host == null && !"RECHECK".equals(action))) {
            throw new CloudRuntimeException("Staging VM or Worker Host is unavailable");
        }
        return thirdPartyBackupVolumeService.manage(backup, host, operation, jobId, action, artifactIndex, externalJobId,
                "BACKUP".equals(operation) ? volumeTransfer(vm, backup, host) : null,
                "RESTORE".equals(operation) ? volumeRestoreTransfer(backup, host) : null,
                artifact -> getClient(backup.getZoneId()).backupImageExists(artifact.externalId));
    }

    private ThirdPartyBackupVolumeService.Transfer volumeTransfer(final VirtualMachine vm, final Backup backup, final Host host) {
        final String policy = getBackupDetail(backup, DETAIL_POLICY_NAME);
        return new ThirdPartyBackupVolumeService.Transfer() {
            @Override
            public boolean ready() {
                return getClient(vm.getDataCenterId()).volumeSourceReady(policy);
            }

            @Override
            public ThirdPartyBackupManifest.Artifact backup(final ThirdPartyBackupManifest.Artifact artifact, final boolean metadata) {
                return getClient(vm.getDataCenterId()).backupVolumeArtifact(policy, host.getName(), host.getPrivateIpAddress(), artifact, metadata);
            }

            @Override
            public ThirdPartyBackupManifest.Artifact recover(ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
                return getClient(backup.getZoneId()).recoverVolumeArtifact(artifact, metadata);
            }

            @Override
            public ThirdPartyBackupManifest.Artifact link(ThirdPartyBackupManifest.Artifact artifact, boolean metadata, String jobId) {
                return getClient(backup.getZoneId()).linkVolumeArtifact(artifact, metadata, jobId, host.getName(), host.getPrivateIpAddress());
            }

            @Override
            public void completed(final Backup current) {
                if (BACKUP_ENGINE_QCOW2.equals(getBackupDetail(current, DETAIL_BACKUP_ENGINE))) {
                    final String xml = readFileContentsOnHost(host.getId(), getBackupDetail(current, DETAIL_CHECKPOINT_PATH));
                    if (StringUtils.isBlank(xml)) {
                        throw new CloudRuntimeException("Completed QCOW2 backup checkpoint metadata is missing");
                    }
                    current.getDetails().put(DETAIL_CHECKPOINT_XML, xml);
                }
            }

            @Override
            public void failed(final Backup current) {
                sealParentBackupChainIfIncremental(current, "failed-volume-child");
            }
        };
    }

    private void sealParentBackupChainIfIncremental(final Backup backup, final String reason) {
        if (backup == null || !BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backup.getType())) {
            return;
        }
        loadBackupDetailsIfNeeded(backup);
        final String parentBackupUuid = getBackupDetail(backup, DETAIL_PARENT_BACKUP_UUID);
        if (StringUtils.isBlank(parentBackupUuid)) {
            return;
        }
        final Backup parentBackup = backupDao.findByUuid(parentBackupUuid);
        if (parentBackup == null) {
            return;
        }
        updateBackupDetail(parentBackup, DETAIL_CHAIN_SEALED, Boolean.TRUE.toString());
        updateBackupDetail(parentBackup, DETAIL_CHAIN_SEAL_REASON, reason);
        LOG.warn("Sealed NetBackup parent backup chain [{}] because incremental child backup [{}] failed. reason=[{}]",
                parentBackupUuid, backup.getUuid(), reason);
    }

    @Override
    public boolean retryFailedBackupAsFull(final VirtualMachine vm, final Backup backup, final String reason) {
        if (!(backup instanceof BackupVO)) { return false; }
        final BackupVO failedBackup = (BackupVO) backup;
        if (!AblestackBackupRecoveryHelper.canRetry(findBackupJobHost(backup, vm), vm)) { return false; }
        if (failedBackup == null || !BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(failedBackup.getType())) {
            return false;
        }
        try {
            final String policyName = getBackupDetail(failedBackup, DETAIL_POLICY_NAME);
            final Pair<Boolean, Backup> result;
            if (StringUtils.isNotBlank(policyName)) {
                result = takeNetBackup(vm, policyName, failedBackup.getBackupScheduleId());
            } else {
                final boolean quiesce = Boolean.parseBoolean(
                        getBackupDetail(failedBackup, AblestackBackupFrameworkUtils.BACKUP_QUIESCE_DETAIL));
                result = takeBackup(vm, quiesce, false, failedBackup.getBackupScheduleId());
            }
            final Backup fullBackup = result.second();
            if (fullBackup == null) {
                LOG.error("Failed to create FULL fallback backup for VM [{}] after asynchronous INCREMENTAL failure [{}]",
                        vm.getInstanceName(), failedBackup.getUuid());
                return false;
            }
            copyFallbackBackupMetadata(failedBackup, fullBackup);
            recordIncrementalFallback(fullBackup, failedBackup, reason);
            LOG.warn("Started FULL fallback backup [{}] for VM [{}] after asynchronous INCREMENTAL failure [{}]",
                    fullBackup.getUuid(), vm.getInstanceName(), failedBackup.getUuid());
            return true;
        } catch (Exception e) {
            LOG.error("Failed to start FULL fallback for VM [{}] after asynchronous INCREMENTAL failure [{}]: {}",
                    vm.getInstanceName(), failedBackup.getUuid(), e.getMessage(), e);
        }
        return false;
    }

    private void copyFallbackBackupMetadata(final BackupVO failedBackup, final Backup fullBackup) {
        final BackupVO fullBackupVO = backupDao.findById(fullBackup.getId());
        if (fullBackupVO == null) {
            return;
        }
        fullBackupVO.setBackupScheduleId(failedBackup.getBackupScheduleId());
        fullBackupVO.setName(failedBackup.getName());
        fullBackupVO.setDescription(failedBackup.getDescription());
        backupDao.update(fullBackupVO.getId(), fullBackupVO);
        backupDao.loadDetails(fullBackupVO);
        if (!ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(fullBackupVO, ThirdPartyBackupManifest.MODE_KEY))
                && Boolean.parseBoolean(getBackupDetail(failedBackup, AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL))) {
            updateBackupDetail(fullBackupVO, AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL, Boolean.TRUE.toString());
        }
    }

    private boolean syncCompletedStagingMetadata(final VirtualMachine vm, final Backup backup) {
        if (!Backup.Status.BackingUp.equals(backup.getStatus()) || Boolean.parseBoolean(getBackupDetail(backup, DETAIL_STAGING_METADATA_SYNCED))) {
            return false;
        }
        final Host host = findBackupJobHost(backup, vm);
        if (host == null) {
            LOG.debug("Skipping NetBackup staging metadata sync for backup [{}] because backup job host is unavailable",
                    backup.getUuid());
            return false;
        }
        final String jobState = getHostBackupJobState(host.getId(), backup.getUuid());
        final String jobLogPath = AblestackBackupFrameworkUtils.getAsyncBackupJobLogPath(backup.getUuid());
        LOG.info("{} phase=[STAGING_STATUS], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], hostId=[{}], "
                        + "hostName=[{}], backupPath=[{}], jobState=[{}], jobLog=[{}]",
                BACKUP_TRACE, backup.getId(), backup.getUuid(), vm.getId(), vm.getInstanceName(), host.getId(), host.getName(),
                backup.getExternalId(), jobState, jobLogPath);
        if ("CANCEL_PENDING".equals(jobState)) { return false; }
        if ("FAILED".equals(jobState) || "INTERRUPTED".equals(jobState)) {
            final String defaultFailureReason = "Host NetBackup staging job " + jobState.toLowerCase(Locale.ROOT);
            final String failureReason = getHostBackupJobDetails(host.getId(), backup.getUuid(), defaultFailureReason);
            final BackupVO backupVO = backupDao.findById(backup.getId());
            if (backupVO != null) {
                backupDao.loadDetails(backupVO);
                markBackupFailure(backupVO, "host-job", failureReason);
                sealParentBackupChainIfIncremental(backupVO, "failed-async-child");
                updateBackupDetail(backupVO, AblestackBackupFrameworkUtils.BACKUP_RECOVERY_REASON_DETAIL, failureReason);
                updateBackupDetail(backupVO, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL, "WAITING");
                if (BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backupVO.getType())) {
                    updateBackupDetail(backupVO, AblestackBackupFrameworkUtils.BACKUP_FULL_RETRY_STATE_DETAIL, "WAITING");
                }
                backupVO.setStatus(Backup.Status.Error);
                backupDao.update(backupVO.getId(), backupVO);
            }
            return true;
        } else if ("CANCELED".equals(jobState)) {
            final BackupVO backupVO = backupDao.findById(backup.getId());
            if (backupVO != null) {
                updateBackupDetail(backupVO, AblestackBackupFrameworkUtils.BACKUP_CANCEL_REQUESTED_DETAIL, "true");
                updateBackupDetail(backupVO, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL, "WAITING");
                backupVO.setStatus(Backup.Status.Canceled);
                backupDao.update(backupVO.getId(), backupVO);
            }
            LOG.warn("{} phase=[STAGING_CANCELED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], hostId=[{}], "
                            + "hostName=[{}], backupPath=[{}], jobLog=[{}]",
                    BACKUP_TRACE, backup.getId(), backup.getUuid(), vm.getId(), vm.getInstanceName(), host.getId(), host.getName(),
                    backup.getExternalId(), jobLogPath);
            return true;
        }
        final String completeMarker = readFileContentsOnHost(host.getId(), backup.getExternalId() + "/" + AblestackBackupFrameworkUtils.STAGING_COMPLETE_MARKER);
        if (StringUtils.isBlank(completeMarker)) {
            LOG.info("{} phase=[STAGING_MARKER_NOT_READY], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], hostId=[{}], "
                            + "hostName=[{}], markerPath=[{}]",
                    BACKUP_TRACE, backup.getId(), backup.getUuid(), vm.getId(), vm.getInstanceName(), host.getId(), host.getName(),
                    backup.getExternalId() + "/" + AblestackBackupFrameworkUtils.STAGING_COMPLETE_MARKER);
            return false;
        }

        final BackupVO backupVO = backupDao.findById(backup.getId());
        if (backupVO == null) {
            return true;
        }
        backupDao.loadDetails(backupVO);
        final String backupEngine = getBackupDetail(backupVO, DETAIL_BACKUP_ENGINE, BACKUP_ENGINE_QCOW2);
        final String checkpointName = getBackupDetail(backupVO, DETAIL_CHECKPOINT_NAME);
        if (BACKUP_ENGINE_QCOW2.equals(backupEngine)) {
            final String checkpointXml = readFileContentsOnHost(host.getId(), getCheckpointPath(backup.getExternalId(), checkpointName, backupEngine));
            if (StringUtils.isNotBlank(checkpointXml)) {
                final boolean incrementalBackup = BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backupVO.getType());
                final String checkpointXmlToStore = incrementalBackup ? checkpointXml : removeParentFromCheckpointXml(checkpointXml);
                backupVO.getDetails().put(DETAIL_CHECKPOINT_XML, checkpointXmlToStore);
                backupDetailsDao.removeDetail(backupVO.getId(), DETAIL_CHECKPOINT_XML);
                backupDetailsDao.addDetail(backupVO.getId(), DETAIL_CHECKPOINT_XML, checkpointXmlToStore, false);
            }
        }

        final List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        final boolean incrementalBackup = BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backupVO.getType());
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
        backupVO.setSize(backupVO.getProtectedSize());
        backupVO.setBackedUpVolumes(createVolumeInfoFromVolumes(vmVolumes, backupFiles));
        backupDetailsDao.removeDetail(backupVO.getId(), DETAIL_STAGING_METADATA_SYNCED);
        backupDetailsDao.addDetail(backupVO.getId(), DETAIL_STAGING_METADATA_SYNCED, Boolean.TRUE.toString(), false);
        backupDao.update(backupVO.getId(), backupVO);
        LOG.info("{} phase=[STAGING_METADATA_SYNCED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupPath=[{}]",
                BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), backup.getExternalId());
        return true;
    }

    private String getHostBackupJobState(final Long hostId, final String backupJobId) {
        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(hostId, new AblestackBackupJobStatusCommand(backupJobId));
            return answer != null && answer.getResult() ? answer.getState() : null;
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.debug("Failed to query NetBackup backup job state for job [{}] on host [{}]", backupJobId, hostId, e);
            return null;
        }
    }

    private String getHostBackupJobDetails(final Long hostId, final String backupJobId, final String defaultDetails) {
        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(hostId, new AblestackBackupJobStatusCommand(backupJobId));
            return answer != null ? StringUtils.defaultIfBlank(answer.getDetails(), defaultDetails) : defaultDetails;
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            return defaultDetails;
        }
    }

    private void cleanupBackupJobFiles(final Long hostId, final String backupJobId) {
        try {
            final Answer answer = agentManager.send(hostId, new AblestackBackupJobCleanupCommand(backupJobId));
            if (answer == null || !answer.getResult()) {
                LOG.warn("Failed to cleanup NetBackup backup job files [jobId: {}, hostId: {}]: {}",
                        backupJobId, hostId, answer != null ? answer.getDetails() : null);
            }
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to send NetBackup backup job cleanup command [jobId: {}, hostId: {}]", backupJobId, hostId, e);
        }
    }

    private Host findBackupJobHost(final Backup backup, final VirtualMachine vm) {
        if (backup != null) {
            loadBackupDetailsIfNeeded(backup);
            if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
                return thirdPartyBackupVolumeService.getWorkerHost(backup, "BACKUP");
            }
        }
        final Long backupJobHostId = getBackupJobHostId(backup);
        if (backupJobHostId != null) {
            final HostVO host = hostDao.findById(backupJobHostId);
            if (host != null) {
                return host;
            }
        }
        try {
            return getVMHypervisorHostForBackup(vm);
        } catch (CloudRuntimeException e) {
            return null;
        }
    }

    private Long getBackupJobHostId(final Backup backup) {
        if (backup == null) {
            return null;
        }
        return backup.getHostId();
    }

    @Override
    public boolean cancelBackup(final VirtualMachine vm, final Backup backup) {
        final Host host = findBackupJobHost(backup, vm);
        if (host == null) {
            LOG.warn("Failed to cancel NetBackup backup [{}] for VM [{}]: backup job host was not found",
                    backup.getUuid(), vm.getInstanceName());
            return false;
        }
        try {
            final StopBackupAnswer answer = (StopBackupAnswer) agentManager.send(host.getId(),
                    new AblestackStopBackupCommand(vm.getInstanceName(), vm.getId(), backup.getId(), backup.getUuid()));
            return answer != null && answer.getResult();
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to cancel NetBackup backup [{}] for VM [{}] on host [{}]",
                    backup.getUuid(), vm.getInstanceName(), host.getName(), e);
            return false;
        }
    }

    private boolean deferUnconfirmedStaleBackup(final VirtualMachine vm, final Backup backup) {
        if (AblestackBackupRecoveryHelper.isSourceStopped(agentManager::send, findBackupJobHost(backup, vm), backup, vm)) {
            return false;
        }
        final BackupVO current = backupDao.findById(backup.getId());
        if (current != null) {
            sealParentBackupChainIfIncremental(current, "unconfirmed-stale-child");
            markBackupFailure(current, "stale-cleanup", "Source job termination is unconfirmed; cleanup is pending");
            updateBackupDetail(current, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL, "WAITING");
            current.setStatus(Backup.Status.Error);
            backupDao.update(current.getId(), current);
        }
        return true;
    }

    @Override
    public boolean cleanupCanceledBackup(final VirtualMachine vm, final Backup backup) {
        if (!AblestackBackupRecoveryHelper.isSourceStopped(agentManager::send, findBackupJobHost(backup, vm), backup, vm)) {
            LOG.warn("Backup [{}] cleanup deferred until source termination and guest thaw are confirmed", backup.getUuid());
            return false;
        }
        sealParentBackupChainIfIncremental(backup, "canceled-or-failed-child");
        return cleanupFailedBackupArtifacts(findBackupJobHost(backup, vm), backup);
    }

    private List<Long> removeBackupGroup(final String backupId) {
        final Set<Long> backupIdsToRemove = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(backupId)) {
            backupDetailsDao.findDetails(DETAIL_BACKUP_ID, backupId, false).stream()
                    .map(BackupDetailVO::getResourceId)
                    .forEach(backupIdsToRemove::add);
        }
        if (backupIdsToRemove.isEmpty()) {
            return Collections.emptyList();
        }

        final List<Long> removedIds = new ArrayList<>();
        final List<Backup> backupsToRemove = backupIdsToRemove.stream()
                .map(backupDao::findByIdIncludingRemoved)
                .filter(Objects::nonNull)
                .filter(this::isNetBackupBackup)
                .filter(backup -> {
                    loadBackupDetailsIfNeeded(backup);
                    return !ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY));
                })
                .collect(Collectors.toList());
        cleanupExpiredBackupArtifacts(backupsToRemove, backupIdsToRemove);
        for (final Long backupIdToRemove : backupIdsToRemove) {
            final Backup backup = backupDao.findByIdIncludingRemoved(backupIdToRemove);
            if (backup == null) {
                continue;
            }
            final BackupOfferingVO backupOffering = backupOfferingDao.findById(backup.getBackupOfferingId());
            if (backupOffering == null || !StringUtils.equalsIgnoreCase(getName(), backupOffering.getProvider())) {
                continue;
            }
            loadBackupDetailsIfNeeded(backup);
            if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) { continue; }
            removeBackupWithDetails(backupIdToRemove);
            removedIds.add(backupIdToRemove);
        }
        return removedIds;
    }

    private boolean isNetBackupBackup(final Backup backup) {
        if (backup == null) {
            return false;
        }
        final BackupOfferingVO backupOffering = backupOfferingDao.findById(backup.getBackupOfferingId());
        return backupOffering != null && StringUtils.equalsIgnoreCase(getName(), backupOffering.getProvider());
    }

    private void cleanupExpiredBackupArtifacts(final List<Backup> backupsToRemove, final Set<Long> backupIdsToRemove) {
        if (CollectionUtils.isEmpty(backupsToRemove)) {
            return;
        }
        for (final Backup backup : backupsToRemove) {
            try {
                cleanupExpiredBackupArtifact(backup, backupIdsToRemove);
            } catch (final Exception e) {
                LOG.warn("Failed to cleanup expired NetBackup artifact for backup [{}]. Mold metadata will still be removed. Cause: {}",
                        backup.getUuid(), e.getMessage(), e);
            }
        }
    }

    private void cleanupExpiredBackupArtifact(final Backup backup, final Set<Long> backupIdsToRemove) {
        loadBackupDetailsIfNeeded(backup);
        if (hasDependentBackupOutsideRemoval(backup, backupIdsToRemove)) {
            LOG.info("Skipping NetBackup artifact cleanup for backup [{}] because a remaining backup still depends on checkpoint [{}].",
                    backup.getUuid(), getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));
            return;
        }

        final String checkpointName = getBackupDetail(backup, DETAIL_CHECKPOINT_NAME);
        if (StringUtils.isBlank(checkpointName) || StringUtils.isBlank(backup.getExternalId())) {
            return;
        }

        final Host cleanupHost = resolveBackupCleanupHost(backup);
        if (cleanupHost == null) {
            LOG.warn("Skipping NetBackup artifact cleanup for backup [{}] because no available KVM cleanup host was found.", backup.getUuid());
            return;
        }

        final AblestackDeleteBackupCommand command = new AblestackDeleteBackupCommand(backup.getExternalId(), null, null, null, true);
        final int deleteTimeout = BackupDataOperationTimeout.value();
        if (deleteTimeout > 0) {
            command.setWait(deleteTimeout);
        }
        command.setBackupProvider(getName());
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        command.setVmName(vm != null ? vm.getInstanceName() : null);
        command.setCheckpointName(checkpointName);
        command.setCleanupCheckpointNames(getUnreferencedQcow2CheckpointNamesAfterDelete(backup, backupIdsToRemove));
        if (BACKUP_ENGINE_RBD_DIFF.equals(getBackupDetail(backup, DETAIL_BACKUP_ENGINE))) {
            command.setDiskPaths(getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));
        }

        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(cleanupHost.getId(), command);
            if (answer == null || !answer.getResult()) {
                LOG.warn("NetBackup artifact cleanup failed for backup [{}] on host [{}]: {}",
                        backup.getUuid(), cleanupHost.getName(), answer != null ? answer.getDetails() : "no answer received");
            }
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Unable to cleanup expired NetBackup artifact for backup [{}] on host [{}]: {}",
                    backup.getUuid(), cleanupHost.getName(), e.getMessage(), e);
        }
    }

    private boolean hasDependentBackupOutsideRemoval(final Backup backup, final Set<Long> backupIdsToRemove) {
        if (backup == null || StringUtils.isBlank(backup.getUuid())) {
            return false;
        }
        return backupDetailsDao.findDetails(DETAIL_PARENT_BACKUP_UUID, backup.getUuid(), false).stream()
                .map(BackupDetailVO::getResourceId)
                .filter(childBackupId -> !backupIdsToRemove.contains(childBackupId))
                .map(backupDao::findById)
                .filter(Objects::nonNull)
                .anyMatch(childBackup -> Backup.Status.BackedUp.equals(childBackup.getStatus()));
    }

    private String getUnreferencedQcow2CheckpointNamesAfterDelete(final Backup backup, final Set<Long> backupIdsToRemove) {
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
        backupDao.listByVmId(backup.getZoneId(), backup.getVmId()).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(candidate -> !Objects.equals(candidate.getId(), backup.getId()))
                .filter(candidate -> backupIdsToRemove == null || !backupIdsToRemove.contains(candidate.getId()))
                .filter(this::isNetBackupBackup)
                .forEach(candidate -> {
                    backupDao.loadDetails(candidate);
                    // Completed failed/canceled cleanup leaves history, but no source checkpoint dependency.
                    if ("COMPLETED".equals(getBackupDetail(candidate, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL))) { return; }
                    addIfNotBlank(remainingReferences, getBackupDetail(candidate, DETAIL_CHECKPOINT_NAME));
                    addIfNotBlank(remainingReferences, getBackupDetail(candidate, DETAIL_PARENT_CHECKPOINT_NAME));
                });

        cleanupCandidates.removeAll(remainingReferences);
        return cleanupCandidates.isEmpty() ? null : StringUtils.join(cleanupCandidates, ",");
    }

    private void addIfNotBlank(final Set<String> values, final String value) {
        if (StringUtils.isNotBlank(value)) {
            values.add(value);
        }
    }

    private Host resolveBackupCleanupHost(final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        final String sourceHostName = getBackupDetail(backup, DETAIL_POLICY_NAME);
        if (StringUtils.isNotBlank(sourceHostName)) {
            Host host = hostDao.findByName(sourceHostName);
            if (host == null) {
                host = hostDao.findByIp(sourceHostName);
            }
            if (host != null && Status.Up.equals(host.getStatus()) && Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
                return host;
            }
        }
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm != null) {
            final Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
            if (hostId != null) {
                final Host host = hostDao.findById(hostId);
                if (host != null && Status.Up.equals(host.getStatus()) && Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
                    return host;
                }
            }
        }
        return resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, backup.getZoneId());
    }

    @Override
    public boolean checkBackupAgent(final Long zoneId) {
        return true;
    }

    @Override
    public boolean installBackupAgent(final Long zoneId) {
        return true;
    }

    @Override
    public boolean importBackupPlan(final Long zoneId, final String retentionPeriod, final String externalId) {
        return true;
    }

    @Override
    public boolean updateBackupPlan(final Long zoneId, final String retentionPeriod, final String externalId) {
        return true;
    }

    @Override
    public boolean supportsOutOfBandBackupSync() {
        return true;
    }

    private static final class BackupExecutionResult {
        private final boolean success;
        private final Backup backup;
        private final String details;

        private BackupExecutionResult(final boolean success, final Backup backup, final String details) {
            this.success = success;
            this.backup = backup;
            this.details = details;
        }

        private static BackupExecutionResult success(final Backup backup) {
            return new BackupExecutionResult(true, backup, null);
        }

        private static BackupExecutionResult failure(final String details, final Backup backup) {
            return new BackupExecutionResult(false, backup, details);
        }
    }
}
