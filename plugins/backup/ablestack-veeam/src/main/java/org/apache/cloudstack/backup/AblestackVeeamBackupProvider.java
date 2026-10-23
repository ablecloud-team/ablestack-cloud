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
import org.apache.cloudstack.backup.ablestackveeam.AblestackVeeamClient;
import org.apache.cloudstack.backup.ablestackveeam.AblestackVeeamRestClient;
import org.apache.cloudstack.backup.ablestackveeam.AblestackVeeamSshClient;
import java.net.URI;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
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
import java.nio.file.Path;
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

public class AblestackVeeamBackupProvider extends AdapterBase implements BackupProvider, Configurable {
    @Inject private BackupVolumeAllocationService backupVolumeAllocationService;

    private static final Logger LOG = LogManager.getLogger(AblestackVeeamBackupProvider.class);
    private final ThreadLocal<Boolean> detachedRestoreStart = ThreadLocal.withInitial(() -> false);

    private static final String BACKUP_TYPE_FULL = "FULL";
    private static final String BACKUP_TYPE_INCREMENTAL = "INCREMENTAL";
    private static final String BACKUP_ENGINE_QCOW2 = "QCOW2";
    private static final String BACKUP_ENGINE_RBD_DIFF = "RBD_DIFF";
    private static final String DETAIL_CHAIN_SEALED = "veeam.chain.sealed";
    private static final String DETAIL_CHAIN_SEAL_REASON = "veeam.chain.seal.reason";
    private static final String DETAIL_ARTIFACT_JOBS_CLEANUP = "veeam.artifact.jobs.cleanup";
    private static final String DETAIL_ARTIFACT_JOB_REMOVED_PREFIX = "veeam.artifact.job.removed.";
    private static final String BACKUP_TRACE = "[ABLESTACK_VEEAM_BACKUP_TRACE]";
    private static final String RESTORE_TRACE = "[ABLESTACK_VEEAM_RESTORE_TRACE]";
    private static final String DETAIL_CHECKPOINT_NAME = "ablestack.veeam.checkpoint.name";
    private static final String DETAIL_CHECKPOINT_PATH = "ablestack.veeam.checkpoint.path";
    private static final String DETAIL_CHECKPOINT_XML = "ablestack.veeam.checkpoint.xml";
    private static final String DETAIL_PARENT_BACKUP_UUID = "ablestack.veeam.parent.backup.uuid";
    private static final String DETAIL_PARENT_BACKUP_PATH = "ablestack.veeam.parent.backup.path";
    private static final String DETAIL_PARENT_CHECKPOINT_NAME = "ablestack.veeam.parent.checkpoint.name";
    private static final String DETAIL_PARENT_CHECKPOINT_PATH = "ablestack.veeam.parent.checkpoint.path";
    private static final String DETAIL_BACKUP_ENGINE = "ablestack.veeam.backup.engine";
    private static final String DETAIL_RBD_DISK_PATHS = "ablestack.veeam.rbd.disk.paths";
    private static final String DETAIL_BACKUP_ID = "ablestack.veeam.backup.id";
    private static final String DETAIL_MEMBER_COUNT = "ablestack.veeam.backup.member.count";
    private static final String DETAIL_POLICY_NAME = "ablestack.veeam.policy.name";
    /** KVM host that wrote the backup files to the configured common staging filesystem. */
    private static final String DETAIL_SOURCE_HOST = "ablestack.veeam.source.host";
    private static final String DETAIL_RESTORE_ROOT_JOB_ID = "ablestack.veeam.restore.root.job.id";
    private static final String DETAIL_RESTORE_CHAIN_JOB_ID = "ablestack.veeam.restore.chain.job.id";
    private static final String DETAIL_FAILURE_PHASE = "ablestack.veeam.failure.phase";
    private static final String DETAIL_FAILURE_REASON = "ablestack.veeam.failure.reason";
    private static final long STALE_BACKUP_THRESHOLD_MS = 24L * 60L * 60L * 1000L;
    private static final long VEEAM_SYNC_DELETE_GRACE_MS = 10L * 60L * 1000L;
    private static final long VEEAM_UNSTAMPED_DELETE_GRACE_MS = 24L * 60L * 60L * 1000L;
    private static final long VEEAM_RP_TIME_MATCH_MS = 4L * 60L * 60L * 1000L;
    private static final String VEEAM_OFFERING_NAME = "veeam";
    private static final String VEEAM_OFFERING_EXTERNAL_ID = "veeam";

    public static final String PROVIDER_NAME = "ablestack-veeam";
    public static final String DETAIL_VEEAM_RESTORE_POINT_ID = "ablestack.veeam.restore.point.id";
    public static final String DETAIL_VEEAM_VM_NAME = "ablestack.veeam.vm.name";
    public static final String DETAIL_VEEAM_JOB_NAME = "ablestack.veeam.job.name";

    public ConfigKey<String> AblestackVeeamUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.url", "https://localhost:9398/api/",
            "The Ablestack Veeam B&R REST API URL.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<Integer> AblestackVeeamVersion = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.version", "0",
            "Veeam server major version (0 = auto-detect via PowerShell).", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<String> AblestackVeeamUsername = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.username", "administrator",
            "Veeam B&R username for Ablestack Veeam plugin.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<String> AblestackVeeamPassword = new ConfigKey<>("Secure", String.class,
            "backup.plugin.ablestack-veeam.password", "",
            "Veeam B&R password for Ablestack Veeam plugin.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Boolean> AblestackVeeamValidateSsl = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.ablestack-veeam.validate.ssl", "false",
            "Validate SSL when connecting to Veeam REST API.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Integer> AblestackVeeamApiTimeout = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.request.timeout", "300",
            "Veeam API request timeout in seconds.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Integer> AblestackVeeamRestoreTimeout = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.restore.timeout", "3600",
            "Veeam export/restore operation timeout in seconds.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Integer> AblestackVeeamTaskPollInterval = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.task.poll.interval", "5",
            "Veeam task poll interval in seconds.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<Integer> AblestackVeeamTaskPollMaxRetry = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.ablestack-veeam.task.poll.max.retry", "240",
            "Max retries when polling Veeam tasks.", true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<Boolean> AblestackVeeamUseRestApi = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.ablestack-veeam.use.rest.api", "false",
            "Use the Veeam Backup & Replication REST API (port 9419) for restore point discovery.",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<String> AblestackVeeamRestUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.rest.url", "https://localhost:9419/api/",
            "Veeam Backup & Replication REST API base URL (port 9419).",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<String> AblestackVeeamRestApiVersion = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.rest.api.version", "1.3-rev1",
            "Veeam Backup & Replication REST API version header.",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    @Inject
    private BackupDeletionService backupDeletionService;

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

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, final Boolean quiesceVM) {
        return takeBackup(vm, quiesceVM, null);
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, final Boolean quiesceVM, final boolean isolated, final Long backupScheduleId) {
        return takeBackup(vm, quiesceVM, backupScheduleId, null);
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, final Boolean quiesceVM, final Long backupScheduleId) {
        return takeBackup(vm, quiesceVM, backupScheduleId, null);
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, final Boolean quiesceVM, final Long backupScheduleId,
            final String veeamJobName) {
        try (ThirdPartyBackupVolumeService.Lease lease = thirdPartyBackupVolumeService.acquireLifecycle(vm.getId())) {
            return takeVolumeBackup(vm, quiesceVM, backupScheduleId, veeamJobName);
        }
    }

    private Pair<Boolean, Backup> takeVolumeBackup(final VirtualMachine vm, final Boolean quiesceVM, final Long backupScheduleId,
            final String veeamJobName) {
        thirdPartyBackupStagingService.requireEnabled();
        final Host host = getVMHypervisorHostForBackup(vm);
        validateVmSnapshotCoexistenceForBackup(vm);

        final List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        validateVolumePoolTypes(volumePoolsAndPaths.first());

        final BackupVO latestBackup = isIncrementalBackupEnabled(vm) || StringUtils.isBlank(veeamJobName)
                ? getLatestBackedUpBackup(vm) : null;
        final boolean incrementalBackup = !backupVolumeAllocationService.requiresOfflineFull(vmVolumes) && shouldUseIncrementalBackup(vm, latestBackup, backupScheduleId)
                && ThirdPartyBackupManifest.canContinue(latestBackup, getName(), vm.getInstanceName(),
                        areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2, vmVolumes);
        final String resolvedJobName = resolveVeeamJobName(veeamJobName, latestBackup);
        BackupExecutionResult result = executeBackup(vm, quiesceVM, host, vmVolumes, volumePoolsAndPaths, incrementalBackup ? latestBackup : null,
                incrementalBackup, resolvedJobName);
        Backup failedIncrementalBackup = null;
        if (!result.success && incrementalBackup) {
            failedIncrementalBackup = result.backup;
            final String fallbackReason = result.details;
            final boolean cleanupSuccessful = cleanupFailedBackupForFullRetry(host, failedIncrementalBackup);
            if (!cleanupSuccessful) { return new Pair<>(false, failedIncrementalBackup); }
            LOG.warn("{} phase=[INCREMENTAL_FALLBACK_TO_FULL], vmId=[{}], vmName=[{}], failedBackupUuid=[{}], reason=[{}]",
                    BACKUP_TRACE, vm.getId(), vm.getInstanceName(),
                    failedIncrementalBackup != null ? failedIncrementalBackup.getUuid() : null, fallbackReason);
            result = executeBackup(vm, quiesceVM, host, vmVolumes, volumePoolsAndPaths, null, false,
                    resolvedJobName);
            recordIncrementalFallback(result.backup, failedIncrementalBackup, fallbackReason);
            if (cleanupSuccessful && failedIncrementalBackup != null) {
                removeFailedBackupAfterSuccessfulFullRetry(failedIncrementalBackup);
            }
        }
        return new Pair<>(result.success, result.backup);
    }

    private String resolveVeeamJobName(final String veeamJobName, final Backup latestBackup) {
        if (StringUtils.isNotBlank(veeamJobName)) {
            return veeamJobName.trim();
        }
        loadBackupDetailsIfNeeded(latestBackup);
        return StringUtils.trimToNull(getBackupDetail(latestBackup, DETAIL_VEEAM_JOB_NAME));
    }

    private BackupExecutionResult executeBackup(final VirtualMachine vm, final Boolean quiesceVM, final Host vmHost,
            final List<VolumeVO> vmVolumes, final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths,
            final Backup latestBackup, final boolean incrementalBackup, final String policyName) {
        if (StringUtils.isBlank(policyName)) {
            throw new CloudRuntimeException("Volume backup requires a configured Veeam Linux Agent template job name");
        }
        final String backupPath = buildBackupPath(vm);
        final String checkpointName = backupPath.substring(backupPath.lastIndexOf("/") + 1);
        final String backupEngine = backupVolumeAllocationService.requiresOfflineFull(vmVolumes) ? AblestackSharedRawBackup.ENGINE
                : areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2;
        final String requestedBackupType = incrementalBackup ? BACKUP_TYPE_INCREMENTAL : BACKUP_TYPE_FULL;
        validateBackupStageCapacity(vmHost, getBackupStageRootPath(), vmVolumes, vm.getInstanceName(), requestedBackupType, backupEngine, true);
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
        final Map<String, String> backupDetails = getBackupDetails(vm, backupPath, checkpointName, backupEngine, latestBackup,
                incrementalBackup, policyName);
        backupDetails.put(AblestackBackupFrameworkUtils.BACKUP_QUIESCE_DETAIL,
                String.valueOf(Boolean.TRUE.equals(quiesceVM)));
        if (vmHost != null && StringUtils.isNotBlank(vmHost.getName())) {
            backupDetails.put(DETAIL_SOURCE_HOST, vmHost.getName());
        }

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
            LOG.info("{} phase=[BEGIN], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], backupPath=[{}], host=[{}]",
                    BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                    backupPath, vmHost != null ? vmHost.getName() : null);
            AblestackVeeamTakeBackupCommand command = new AblestackVeeamTakeBackupCommand(vm.getInstanceName(), backupPath);
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
            command.setWaitForCompletion(false);
            command.setBandwidthLimitMbps(BackupQosBandwidthLimitMbps.value());
            if (incrementalBackup && latestBackup != null) {
                command.setParentBackupPath(getBackupDetail(latestBackup, DETAIL_PARENT_BACKUP_PATH,
                        latestBackup.getExternalId()));
                command.setParentCheckpointName(getBackupCheckpointName(latestBackup));
                command.setParentCheckpointPath(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_PATH));
                final String parentCheckpointXml = getBackupDetail(latestBackup, DETAIL_CHECKPOINT_XML);
                command.setParentCheckpointXml(BACKUP_TYPE_FULL.equalsIgnoreCase(latestBackup.getType())
                        ? removeParentFromCheckpointXml(parentCheckpointXml)
                        : parentCheckpointXml);
                command.setParentCheckpointXmlChain(getParentCheckpointXmlChain(latestBackup));
            }

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
        if (!isIncrementalBackupEnabled(vm)) {
            return false;
        }
        if (latestBackup == null) {
            LOG.info("Veeam backup for VM [{}] will be FULL: no previous BackedUp backup", vm.getInstanceName());
            return false;
        }
        loadBackupDetailsIfNeeded(latestBackup);
        if (StringUtils.isBlank(getBackupDetail(latestBackup, ThirdPartyBackupManifest.DETAIL_KEY))) {
            return false;
        }

        if (Boolean.parseBoolean(getBackupDetail(latestBackup, DETAIL_CHAIN_SEALED))) {
            LOG.info("Veeam backup for VM [{}] will be FULL: backup chain [{}] is sealed ({})",
                    vm.getInstanceName(), latestBackup.getUuid(), getBackupDetail(latestBackup, DETAIL_CHAIN_SEAL_REASON));
            return false;
        }

        if (backupScheduleId != null && !hasBackedUpBackupForSchedule(backupScheduleId)) {
            LOG.info("Veeam backup for VM [{}] will be FULL: first backup for schedule [{}]", vm.getInstanceName(), backupScheduleId);
            return false;
        }

        if (!hasHealthyIncrementalSource(latestBackup)) {
            LOG.info("Veeam backup for VM [{}] will be FULL: parent backup [{}] engine={} checkpoint={} xmlPresent={}",
                    vm.getInstanceName(),
                    latestBackup.getUuid(),
                    getBackupEngine(latestBackup),
                    getBackupCheckpointName(latestBackup),
                    StringUtils.isNotBlank(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_XML)));
            return false;
        }

        if (getBackupChainSize(vm, latestBackup) >= KvmBackupChainSize.value()) {
            LOG.info("Veeam backup for VM [{}] will be FULL: incremental chain size reached limit [{}]",
                    vm.getInstanceName(), KvmBackupChainSize.value());
            return false;
        }
        LOG.info("Veeam backup for VM [{}] will be INCREMENTAL from parent [{}] engine={}",
                vm.getInstanceName(), latestBackup.getUuid(), getBackupEngine(latestBackup));
        return true;
    }

    private boolean shouldUseIncrementalBackupForVeeam(final VirtualMachine vm, final Backup latestBackup) {
        if (!isIncrementalBackupEnabled(vm) || latestBackup == null) {
            return false;
        }
        loadBackupDetailsIfNeeded(latestBackup);
        if (StringUtils.isBlank(getBackupDetail(latestBackup, ThirdPartyBackupManifest.DETAIL_KEY))) {
            return false;
        }

        if (Boolean.parseBoolean(getBackupDetail(latestBackup, DETAIL_CHAIN_SEALED))) {
            return false;
        }

        if (!hasHealthyIncrementalSource(latestBackup)) {
            return false;
        }

        if (getBackupChainSize(vm, latestBackup) >= KvmBackupChainSize.value()) {
            return false;
        }
        return true;
    }

    private boolean isIncrementalBackupEnabled(final VirtualMachine vm) {
        final Long clusterId = getClusterIdFromRootVolume(vm);
        final boolean enabled = clusterId != null && Boolean.TRUE.equals(KvmIncrementalBackup.valueIn(clusterId));
        if (!enabled) {
            LOG.info("Veeam backup for VM [{}] will be FULL: kvm.incremental.backup is disabled or Cluster is unavailable [{}]",
                    vm.getInstanceName(), clusterId);
        }
        return enabled;
    }

    private boolean hasHealthyIncrementalSource(final Backup latestBackup) {
        final String backupEngine = getBackupEngine(latestBackup);
        if (StringUtils.isBlank(backupEngine)) {
            return false;
        }
        if (StringUtils.isBlank(getBackupCheckpointName(latestBackup))
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
            LOG.info("Cleaned failed Veeam backup path [{}] before full retry for backup [{}].",
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
        command.setCheckpointName(getBackupCheckpointName(backup));
        command.setCleanupCheckpointNames(getUnreferencedQcow2CheckpointNamesAfterDelete(backup, Collections.singleton(backup.getId())));
        if (BACKUP_ENGINE_RBD_DIFF.equals(getBackupEngine(backup))) {
            command.setDiskPaths(getRbdDiskPaths(backup));
        }
        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) {
                LOG.warn("Failed to cleanup artifacts for Veeam backup [{}] on host [{}]: {}",
                        backup.getUuid(), host.getName(), answer != null ? answer.getDetails() : "no answer received");
                return false;
            }
            return true;
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Unable to cleanup artifacts for Veeam backup [{}] on host [{}]: {}",
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
            LOG.info("Removed failed Veeam backup row [{}] after successful full retry.", backup.getUuid());
        } catch (Exception e) {
            LOG.warn("Failed to remove failed Veeam backup row [{}] after successful full retry: {}",
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
        backup.setSize(virtualSize);
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
        details.put(DETAIL_BACKUP_ENGINE, backupEngine);
        details.put(DETAIL_CHECKPOINT_NAME, checkpointName);
        details.put(DETAIL_CHECKPOINT_PATH, getCheckpointPath(backupPath, checkpointName, backupEngine));
        if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
            details.put(DETAIL_RBD_DISK_PATHS, String.join(",", getVolumePoolsAndPaths(volumeDao.findByInstance(vm.getId())).second()));
        }
        if (incrementalBackup && latestBackup != null) {
            details.put(DETAIL_PARENT_BACKUP_UUID, latestBackup.getUuid());
            details.put(DETAIL_PARENT_BACKUP_PATH, latestBackup.getExternalId());
            details.put(DETAIL_PARENT_CHECKPOINT_NAME, getBackupCheckpointName(latestBackup));
            details.put(DETAIL_PARENT_CHECKPOINT_PATH, getBackupDetail(latestBackup, DETAIL_CHECKPOINT_PATH));
        }
        details.put(DETAIL_VEEAM_VM_NAME, vm.getInstanceName());
        if (StringUtils.isNotBlank(policyName)) {
            details.put(DETAIL_VEEAM_JOB_NAME, policyName.trim());
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
            throw new CloudRuntimeException("Veeam incremental backup does not support VMs with mixed RBD and non-RBD volumes");
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
            } else if (AblestackSharedRawBackup.ENGINE.equals(backupEngine) && volume.getFormat() == Storage.ImageFormat.RAW) {
                backupFiles.add(String.format("%s.%s.raw", diskPrefix, volume.getUuid()));
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
            Backup.VolumeInfo info = new Backup.VolumeInfo(volume.getUuid(), backupFiles.get(i), volume.getVolumeType(), volume.getSize(),
                    volume.getDeviceId(), diskOfferingUuid, volume.getMinIops(), volume.getMaxIops());
            info.setAllocation(backupVolumeAllocationService.capture(volume));
            infoList.add(info);
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
                    "Insufficient stage space on host [%s] for Veeam backup. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    stageHost != null ? stageHost.getName() : null, minimumAvailableBytes, availableBytes, stageRootPath));
        }
    }

    private long estimateRequiredStageBytesForBackup(final List<VolumeVO> vmVolumes) {
        if (CollectionUtils.isEmpty(vmVolumes)) {
            return 0L;
        }
        return vmVolumes.stream()
                .mapToLong(VolumeVO::getSize)
                .max().orElse(0L);
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
                    "Insufficient stage space on host [%s] for Veeam restore. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
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
        return backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), vm.getBackupOfferingId()).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .peek(this::loadBackupDetailsIfNeeded)
                .filter(backup -> getBackupCheckpointName(backup) != null)
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
            final String checkpointName = getBackupCheckpointName(current);
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
            LOG.warn("Failed to parse Veeam checkpoint XML parent name. Incremental checkpoint chain may be incomplete.", e);
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
            LOG.warn("Failed to remove parent from Veeam FULL checkpoint XML. Keeping original XML.", e);
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

    private String getBackupCheckpointName(final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        final String checkpointName = StringUtils.trimToNull(getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));
        if (checkpointName != null) {
            return checkpointName;
        }
        if (backup == null || StringUtils.isBlank(backup.getExternalId())) {
            return null;
        }
        final String backupPath = StringUtils.removeEnd(backup.getExternalId().trim(), "/");
        final Path fileName = Path.of(backupPath).getFileName();
        final String inferred = fileName != null ? StringUtils.trimToNull(fileName.toString()) : null;
        if (inferred != null && inferred.matches("\\d{4}\\.\\d{2}\\.\\d{2}\\..*")) {
            LOG.debug("Inferred Veeam checkpoint [{}] for backup [{}] from externalId [{}]",
                    inferred, backup.getUuid(), backup.getExternalId());
            return inferred;
        }
        return null;
    }

    private String getBackupEngine(final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        final String backupEngine = StringUtils.trimToNull(getBackupDetail(backup, DETAIL_BACKUP_ENGINE));
        if (backupEngine != null) {
            return backupEngine;
        }
        if (backup != null && CollectionUtils.isNotEmpty(backup.getBackedUpVolumes())) {
            final boolean hasRbdFile = backup.getBackedUpVolumes().stream()
                    .map(Backup.VolumeInfo::getPath)
                    .filter(StringUtils::isNotBlank)
                    .anyMatch(path -> path.endsWith(".raw") || path.endsWith(".rbdiff"));
            if (hasRbdFile) {
                return BACKUP_ENGINE_RBD_DIFF;
            }
            final boolean hasQcow2File = backup.getBackedUpVolumes().stream()
                    .map(Backup.VolumeInfo::getPath)
                    .filter(StringUtils::isNotBlank)
                    .anyMatch(path -> path.endsWith(".qcow2"));
            if (hasQcow2File) {
                return BACKUP_ENGINE_QCOW2;
            }
        }
        return null;
    }

    private String getRbdDiskPaths(final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        final String rbdDiskPaths = StringUtils.trimToNull(getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));
        if (rbdDiskPaths != null) {
            return rbdDiskPaths;
        }
        if (backup == null) {
            return null;
        }
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm == null) {
            return null;
        }
        final List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        if (CollectionUtils.isEmpty(vmVolumes)) {
            return null;
        }
        final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        if (areAllVolumesOnRbdPool(volumePoolsAndPaths.first())) {
            return String.join(",", volumePoolsAndPaths.second());
        }
        return null;
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
        LOG.warn("Recorded Veeam backup failure context [backupId: {}, backupUuid: {}, phase: {}, reason: {}]",
                backup.getId(), backup.getUuid(), phase, safeReason);
    }

    private void removeBackupWithDetails(final long backupId) {
        backupDetailsDao.removeDetails(backupId);
        backupDao.remove(backupId);
    }

    private Host getVMHypervisorHost(final VirtualMachine vm) {
        Long hostId = vm.getLastHostId();
        if (hostId != null) {
            final Host host = hostDao.findById(hostId);
            if (host != null && Status.Up.equals(host.getStatus())) {
                return host;
            }
            if (host != null && host.getClusterId() != null) {
                for (final Host hostInCluster : hostDao.findHypervisorHostInCluster(host.getClusterId())) {
                    if (Status.Up.equals(hostInCluster.getStatus())) {
                        return hostInCluster;
                    }
                }
            }
        }
        return resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, vm.getDataCenterId());
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
        // RestoreBackup transitions the VM to Restoring before the provider runs; host_id is
        // already cleared for Stopped VMs, so fall back to last_host_id for Stopped/Restoring.
        if (hostId == null || VirtualMachine.State.Stopped.equals(vm.getState())
                || VirtualMachine.State.Restoring.equals(vm.getState())) {
            if (vm.getLastHostId() != null) {
                hostId = vm.getLastHostId();
            }
        }
        if (hostId == null) {
            throw new CloudRuntimeException(String.format("Unable to find the hypervisor host for stopped VM: %s", vm));
        }
        final Host host = hostDao.findById(hostId);
        if (host == null || !Status.Up.equals(host.getStatus()) || !Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate Veeam backup");
        }
        return host;
    }

    private Host resolveBackupSourceHostForRestore(final Backup backup) {
        if (backup == null) {
            return null;
        }
        loadBackupDetailsIfNeeded(backup);
        String sourceHostName = getBackupDetail(backup, DETAIL_SOURCE_HOST);
        if (StringUtils.isBlank(sourceHostName)) {
            // Legacy backups stored the stage host under policy.name
            sourceHostName = getBackupDetail(backup, DETAIL_POLICY_NAME);
        }
        if (StringUtils.isBlank(sourceHostName)) {
            return resolveOriginalBackupVmHost(backup);
        }
        Host host = hostDao.findByName(sourceHostName);
        if (host == null) {
            host = hostDao.findByIp(sourceHostName);
        }
        if (host == null) {
            LOG.warn("Unable to find backup source host [{}] for Veeam restore from backup [{}]; falling back to original VM host",
                    sourceHostName, backup.getUuid());
            return resolveOriginalBackupVmHost(backup);
        }
        if (!Status.Up.equals(host.getStatus()) || !Hypervisor.HypervisorType.KVM.equals(host.getHypervisorType())) {
            LOG.warn("Backup source host [{}] is not an available KVM host for Veeam restore from backup [{}]; falling back to original VM host",
                    host.getName(), backup.getUuid());
            return resolveOriginalBackupVmHost(backup);
        }
        return host;
    }

    private Host resolveOriginalBackupVmHost(final Backup backup) {
        if (backup == null || backup.getVmId() == null) {
            return null;
        }
        final VMInstanceVO backedVm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (backedVm == null) {
            return null;
        }
        try {
            return getVMHypervisorHostForBackup(backedVm);
        } catch (final CloudRuntimeException e) {
            LOG.warn("Unable to resolve original backup VM host for backup [{}]: {}", backup.getUuid(), e.getMessage());
            return null;
        }
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
            LOG.warn("Veeam backup operation is not allowed for VM [{}] with disk-and-memory VM snapshots.", vm.getUuid());
            throw new CloudRuntimeException(String.format("Cannot take backup of VM [%s] as it has disk-and-memory VM snapshots.", vm.getUuid()));
        }
        if (hasKvmFileBasedVmSnapshots(vm)) {
            LOG.debug("Allowing Veeam backup for VM [{}] with KVM file-based VM snapshots.", vm.getUuid());
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
            final Answer answer = agentManager.send(hostId, new AblestackVeeamReadFileCommand(path));
            if (answer != null && answer.getResult()) {
                return answer.getDetails();
            }
            LOG.warn("Failed to read Veeam file [{}] on host [{}]: {}",
                    path, hostId, answer != null ? answer.getDetails() : "no answer received");
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to read Veeam file [{}] on host [{}]: {}", path, hostId, e.getMessage(), e);
        }
        return null;
    }

    @Override
    public boolean assignVMToBackupOffering(final VirtualMachine vm, final BackupOffering backupOffering) {
        if (hasDiskAndMemoryVmSnapshots(vm)) {
            LOG.warn("Veeam backup offering assignment is not allowed for VM [{}] with disk-and-memory VM snapshots.", vm.getUuid());
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
    public boolean handlesChainDeleteResourceAccounting() { return true; }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        return deleteBackup(backup, forced, null);
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced, String expectedPlan) {
        if (backup == null) { return true; }
        loadBackupDetailsIfNeeded(backup);
        if (!ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))
                && !java.util.Set.of(Backup.Status.Failed, Backup.Status.Error).contains(backup.getStatus())) {
            throw new CloudRuntimeException("Legacy recovery points must be removed through the external backup catalog");
        }
        return backupDeletionService.delete(backup, forced, expectedPlan, point -> deleteOwnedBackup(point, false));
    }

    private boolean deleteOwnedBackup(Backup backup, boolean forced) {
        if (backup == null) {
            return true;
        }
        loadBackupDetailsIfNeeded(backup);
        if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
            return thirdPartyBackupVolumeService.deleteOwnedArtifacts(backup, (artifact, metadata, ticket, policyExpiration) ->
                    new ThirdPartyBackupVolumeService.DeleteResult(getSshClient(backup.getZoneId()).deleteVolumeArtifact(artifact, metadata, policyExpiration), null));
        }
        if (Backup.Status.Failed.equals(backup.getStatus()) || Backup.Status.Error.equals(backup.getStatus())) {
            return cleanupFailedOrErrorBackupArtifacts(backup, forced);
        }
        // NetBackup-style: BackedUp rows are removed only when the Veeam catalog image is gone (syncBackups).
        throw new CloudRuntimeException("Veeam backups are managed by Veeam restore points and cannot be deleted individually from Mold. "
                + "Delete the restore point in Veeam (Remove from Disk); Mold removes the matching backup group on the next catalog sync.");
    }

    private boolean cleanupFailedOrErrorBackupArtifacts(final Backup backup, final boolean forced) {
        if ("COMPLETED".equals(getBackupDetail(backup, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL))) { return true; }
        final Set<Long> ids = Collections.singleton(backup.getId());
        try {
            cleanupExpiredBackupArtifacts(Collections.singletonList(backup), ids);
            return true;
        } catch (final Exception e) {
            LOG.warn("Artifact cleanup failed for Veeam backup [{}] in [{}] state before explicit delete. Metadata is retained until cleanup succeeds. forced=[{}]",
                    backup.getUuid(), backup.getStatus(), forced, e);
            return false;
        }
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(final VirtualMachine vm, final Backup backup, final String hostIp, final String dataStoreUuid, final boolean quickRestore) {
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
            return new Pair<>(false, String.format("Backup [%s] was not found for Veeam restore", backupId));
        }

        final VMInstanceVO vm = vmInstanceDao.findVMByInstanceName(vmName);
        if (vm == null) {
            return new Pair<>(false, String.format("VM [%s] was not found for Veeam restore", vmName));
        }

        return restoreVirtualMachine(vm, backup, null);
    }

    @Override
    public boolean restoreVMFromBackup(final VirtualMachine vm, final Backup backup, final boolean quickRestore, final Long hostId) {
        return restoreVirtualMachine(vm, backup, null).first();
    }

    @Override
    public boolean startRestoreVMFromBackup(final VirtualMachine vm, final Backup backup,
            final boolean quickRestore, final Long hostId) {
        detachedRestoreStart.set(true);
        try {
            return restoreVMFromBackup(vm, backup, quickRestore, hostId);
        } finally {
            detachedRestoreStart.remove();
        }
    }

    private Pair<Boolean, String> restoreVirtualMachine(final VirtualMachine vm, final Backup backup, final String restoreHostIp) {
        thirdPartyBackupStagingService.requireEnabled();
        loadBackupDetailsIfNeeded(backup);
        requireVolumeStagingBackup(backup);
        validateRestoreChainIntegrity(backup);
        validateVeeamRestoreSnapshotCompatibility(vm);
        return restoreStagedVm(vm, backup, restoreHostIp);
    }

    private void requireVolumeStagingBackup(final Backup backup) {
        if (!ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
            throw new CloudRuntimeException("Veeam restore requires a backup created by the volume staging pipeline");
        }
    }

    private void validateVeeamRestoreSnapshotCompatibility(final VirtualMachine vm) {
        final List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vm.getId());
        if (CollectionUtils.isNotEmpty(vmSnapshots)) {
            throw new CloudRuntimeException(String.format(
                    "Unable to restore VM [%s] from Veeam while Instance snapshots exist. Remove Instance snapshots before restoring the backup.",
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
                        "Unable to restore VM [%s] from Veeam while RBD volume snapshots exist on volume [%s]. Remove RBD volume snapshots before restoring the backup.",
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
            final String dataStoreUuid, final Pair<String, VirtualMachine.State> vmNameAndState, final VirtualMachine targetVm, final boolean quickRestore) {
        thirdPartyBackupStagingService.requireEnabled();
        loadBackupDetailsIfNeeded(backup);
        requireVolumeStagingBackup(backup);
        validateRestoreChainIntegrity(backup);
        return restoreStagedVolume(backup, backupVolumeInfo, hostIp, dataStoreUuid, targetVm);
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

    @Override
    public String getRestoreJobState(final Long zoneId, final String recoveryJobId) {
        return null;
    }

    @Override
    public void syncBackupMetrics(final Long zoneId) {
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(final VirtualMachine vm) {
        final List<Backup.RestorePoint> restorePoints = new ArrayList<>();
        for (final Backup backup : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (backup.getDate() == null || StringUtils.isBlank(backup.getExternalId()) || !isVeeamBackup(backup)) {
                continue;
            }
            restorePoints.add(new Backup.RestorePoint(
                    backup.getExternalId(),
                    backup.getDate(),
                    StringUtils.defaultIfBlank(backup.getType(), BACKUP_TYPE_FULL),
                    backup.getSize(),
                    backup.getProtectedSize()));
        }
        restorePoints.sort(Comparator.comparing(Backup.RestorePoint::getCreated, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return restorePoints;
    }

    @Override
    public List<Backup.RestorePoint> listCatalogRestorePoints(final VirtualMachine vm) {
        final List<Backup> moldBackups = backupDao.listByVmId(vm.getDataCenterId(), vm.getId()).stream()
                .filter(this::isVeeamBackup)
                .collect(Collectors.toList());
        return queryVeeamCatalogRestorePoints(vm, moldBackups).restorePoints;
    }

    private String getVeeamSourceVmName(final VirtualMachine vm) {
        final Map<String, String> details = backupManager.getBackupDetailsFromVM(vm);
        if (details != null && StringUtils.isNotBlank(details.get(DETAIL_VEEAM_VM_NAME))) {
            return details.get(DETAIL_VEEAM_VM_NAME);
        }
        return vm.getInstanceName();
    }

    @Override
    public Backup createNewBackupEntryForRestorePoint(final Backup.RestorePoint restorePoint, final VirtualMachine vm) {
        LOG.debug("Ablestack Veeam registers backups through the volume staging pipeline only.");
        return null;
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
        LOG.debug("Completed Veeam post-restore maintenance for VM [{}], backup [{}], volumeOnly=[{}]",
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
                    || !isVeeamBackup(candidate)) {
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
            LOG.info("Sealed Veeam backup chain [{}] for VM [{}] after successful VM restore.",
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
        return Collections.singletonList(new AblestackVeeamBackupOffering(
                VEEAM_OFFERING_NAME,
                VEEAM_OFFERING_EXTERNAL_ID
        ));
    }

    @Override
    public boolean isValidProviderOffering(final Long zoneId, final String uuid) {
        return StringUtils.equalsIgnoreCase(uuid, VEEAM_OFFERING_EXTERNAL_ID);
    }

    @Override
    public Boolean crossZoneInstanceCreationEnabled(final BackupOffering backupOffering) {
        return false;
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                AblestackVeeamUrl,
                AblestackVeeamVersion,
                AblestackVeeamUsername,
                AblestackVeeamPassword,
                AblestackVeeamValidateSsl,
                AblestackVeeamApiTimeout,
                AblestackVeeamRestoreTimeout,
                AblestackVeeamTaskPollInterval,
                AblestackVeeamTaskPollMaxRetry,
                AblestackVeeamUseRestApi,
                AblestackVeeamRestUrl,
                AblestackVeeamRestApiVersion
        };
    }

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public String getDescription() {
        return "Ablestack Veeam KVM Backup Plugin";
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    private BackupRestorePlan createRestorePlan(final boolean attachRequired) {
        // The current Veeam flow retains its staged backup chains; never schedule CLEANUP_SOURCE.
        return AblestackBackupFrameworkUtils.createRestorePlan(attachRequired, false);
    }

    private List<BackupVolumeChainState> getVolumeChainStates(final List<Backup.VolumeInfo> backupVolumes, final Backup backup) {
        final String backupEngine = getBackupEngine(backup);
        final List<BackupVolumeChainState> volumeChainStates = backupVolumes.stream()
                .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                .map(volume -> new BackupVolumeChainState(volume.getUuid(), backupEngine,
                        AblestackBackupFrameworkUtils.sanitizeChainFiles(getBackupChain(volume, backup))))
                .collect(Collectors.toList());
        AblestackBackupFrameworkUtils.validateVolumeChainStates(volumeChainStates);
        return volumeChainStates;
    }

    private List<String> getBackupChain(final Backup.VolumeInfo backupVolume, final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        final List<Backup> chain = getBackupChain(backup);
        final List<String> files = new ArrayList<>();
        for (final Backup chainBackup : chain) {
            final String filePath = resolveVolumeBackupFilePath(chainBackup, backupVolume);
            if (StringUtils.isNotBlank(filePath)) {
                files.add(filePath);
            }
        }
        return files;
    }

    private String resolveVolumeBackupFilePath(final Backup chainBackup, final Backup.VolumeInfo backupVolume) {
        if (chainBackup == null || backupVolume == null || StringUtils.isBlank(chainBackup.getExternalId())) {
            return null;
        }
        final Backup.VolumeInfo volumeInfo = getBackedUpVolumeInfo(chainBackup.getBackedUpVolumes(), backupVolume.getUuid());
        if (volumeInfo != null && StringUtils.isNotBlank(volumeInfo.getPath())) {
            return String.format("%s/%s", chainBackup.getExternalId(), volumeInfo.getPath());
        }
        // Seed imports previously left backed_volumes NULL; synthesize the expected file name.
        final String diskPrefix = Volume.Type.ROOT.equals(backupVolume.getType()) ? "root" : "datadisk";
        final String engine = getBackupEngine(chainBackup);
        final boolean incremental = StringUtils.equalsIgnoreCase(BACKUP_TYPE_INCREMENTAL, chainBackup.getType());
        if (BACKUP_ENGINE_RBD_DIFF.equals(engine)) {
            return String.format("%s/%s.%s%s", chainBackup.getExternalId(), diskPrefix, backupVolume.getUuid(),
                    incremental ? ".rbdiff" : ".raw");
        }
        return String.format("%s/%s.%s.qcow2", chainBackup.getExternalId(), diskPrefix, backupVolume.getUuid());
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
        final VolumeVO sourceVolume = volumeDao.findByUuid(source.getUuid());
        VolumeVO restored = new VolumeVO(Volume.Type.DATADISK, null, backup.getZoneId(), backup.getDomainId(),
                backup.getAccountId(), 0, null, source.getSize(), null, null, null);
        restored.setUuid(UUID.randomUUID().toString());
        restored.setName(AblestackBackupFrameworkUtils.buildRestoredVolumeName(
                sourceVolume != null ? sourceVolume.getName() : null, source.getUuid()));
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
        AblestackVeeamRestoreBackupCommand command = new AblestackVeeamRestoreBackupCommand();
        command.setPrimaryStorageCapacityBufferPercent(plan.getPrimaryStorageCapacityBufferPercent());
        command.setVolumeRestorePlan(plan);
        command.setRestoreJobId(plan.jobId);
        command.setBackupPath(plan.destination);
        command.setVmName(vm.getInstanceName());
        command.setRestoreVolumePools(destinations.first());
        command.setRestoreVolumePaths(destinations.second());
        command.setRestoreVolumeProvisioningTypes(targets, destinations.second());
        command.setRestoreVolumeAllocation(backupVolumeAllocationService.configureRestore(backup, host,
                command.getRestoreJobId(), targets, destinations.second()));
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
        return new ThirdPartyBackupVolumeService.RestoreTransfer() {
            @Override
            public ThirdPartyBackupRestore.Result restore(ThirdPartyBackupRestore.Request request, ThirdPartyBackupRestore.Result saved) {
                return getSshClient(backup.getZoneId()).restoreVolumeArtifact(request, host.getName(), host.getPrivateIpAddress(),
                        saved.recoveryOnly || StringUtils.isNotBlank(saved.jobId));
            }

            @Override
            public void cleanup(ThirdPartyBackupRestore.Plan plan) {
                getSshClient(backup.getZoneId()).cleanupVolumeRestoreReceipts(plan.jobId);
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
        return getBackupEngine(backup) == null;
    }

    private String getLegacyBackupFileName(final Backup.VolumeInfo volumeInfo) {
        final String diskPrefix = Volume.Type.ROOT.equals(volumeInfo.getType()) ? "root" : "datadisk";
        return String.format("%s.%s.qcow2", diskPrefix, volumeInfo.getUuid());
    }

    private Backup.VolumeInfo getBackedUpVolumeInfo(final List<Backup.VolumeInfo> backedUpVolumes, final String volumeUuid) {
        if (CollectionUtils.isEmpty(backedUpVolumes) || StringUtils.isBlank(volumeUuid)) {
            return null;
        }
        return backedUpVolumes.stream()
                .filter(v -> volumeUuid.equals(v.getUuid()))
                .findFirst()
                .orElse(null);
    }

    protected AblestackVeeamClient getClient(final Long zoneId) {
        try {
            return new AblestackVeeamClient(
                    AblestackVeeamUrl.valueIn(zoneId),
                    AblestackVeeamVersion.valueIn(zoneId),
                    AblestackVeeamUsername.valueIn(zoneId),
                    AblestackVeeamPassword.valueIn(zoneId),
                    AblestackVeeamValidateSsl.valueIn(zoneId),
                    AblestackVeeamApiTimeout.valueIn(zoneId),
                    AblestackVeeamRestoreTimeout.valueIn(zoneId),
                    AblestackVeeamTaskPollInterval.valueIn(zoneId),
                    AblestackVeeamTaskPollMaxRetry.valueIn(zoneId));
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Ablestack Veeam API URL: " + e.getMessage());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new CloudRuntimeException("Failed to build Ablestack Veeam client: " + e.getMessage());
        }
    }

    protected AblestackVeeamRestClient getRestClient(final Long zoneId) {
        try {
            return new AblestackVeeamRestClient(
                    AblestackVeeamRestUrl.valueIn(zoneId),
                    AblestackVeeamRestApiVersion.valueIn(zoneId),
                    AblestackVeeamUsername.valueIn(zoneId),
                    AblestackVeeamPassword.valueIn(zoneId),
                    AblestackVeeamValidateSsl.valueIn(zoneId),
                    AblestackVeeamApiTimeout.valueIn(zoneId));
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Ablestack Veeam REST API URL: " + e.getMessage());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new CloudRuntimeException("Failed to build Ablestack Veeam REST client: " + e.getMessage());
        }
    }

    protected AblestackVeeamSshClient getSshClient(final Long zoneId) {
        String host = extractHost(AblestackVeeamRestUrl.valueIn(zoneId));
        if (isUnusableVeeamSshHost(host)) {
            host = extractHost(AblestackVeeamUrl.valueIn(zoneId));
        }
        if (isUnusableVeeamSshHost(host)) {
            throw new CloudRuntimeException("Unable to resolve Veeam SSH host from backup.plugin.ablestack-veeam.rest.url / url");
        }
        return new AblestackVeeamSshClient(host, AblestackVeeamUsername.valueIn(zoneId), AblestackVeeamPassword.valueIn(zoneId));
    }

    private boolean isUnusableVeeamSshHost(final String host) {
        if (StringUtils.isBlank(host)) {
            return true;
        }
        final String normalized = host.trim().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private String extractHost(final String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Veeam URL for SSH host: " + e.getMessage());
        }
    }

    @Override
    public String getCatalogBackupTime(final Long zoneId, final String backupId) {
        return null;
    }

    @Override
    public void syncBackups(final VirtualMachine vm) {
        for (Backup backup : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (isVeeamBackup(backup)) { resumeStagedRestore(backup); }
        }
        completeStartedBackups(vm);
        removeStaleBackingUpBackups(vm);
        syncMoldBackupsWithVeeamCatalog(vm);
        removeMoldBackupsForDeletedVeeamJobs(vm);
    }

    @Override
    public boolean reconcileBackingUpBackup(final VirtualMachine vm, final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        boolean failedVolume = ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))
                && java.util.Set.of(Backup.Status.Failed, Backup.Status.Error, Backup.Status.Canceled).contains(backup.getStatus())
                && StringUtils.isBlank(getBackupDetail(backup, ThirdPartyBackupManifest.CATALOG_FAILURE_KEY))
                && StringUtils.isBlank(getBackupDetail(backup, ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))
                && ThirdPartyBackupVolumeService.needsBackupCleanup(backup);
        if (!isVeeamBackup(backup) || (!Backup.Status.BackingUp.equals(backup.getStatus()) && !failedVolume)) {
            return false;
        }
        return completeStartedBackupIfHostJobFinished(vm, backup);
    }

    private void completeStartedBackups(final VirtualMachine vm) {
        for (final Backup backup : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            loadBackupDetailsIfNeeded(backup);
            boolean failedVolume = ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))
                    && java.util.Set.of(Backup.Status.Failed, Backup.Status.Error, Backup.Status.Canceled).contains(backup.getStatus())
                    && StringUtils.isBlank(getBackupDetail(backup, ThirdPartyBackupManifest.CATALOG_FAILURE_KEY))
                    && StringUtils.isBlank(getBackupDetail(backup, ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))
                    && ThirdPartyBackupVolumeService.needsBackupCleanup(backup);
            if ((!Backup.Status.BackingUp.equals(backup.getStatus()) && !failedVolume) || !isVeeamBackup(backup)) {
                continue;
            }
            completeStartedBackupIfHostJobFinished(vm, backup);
        }
    }

    private boolean completeStartedBackupIfHostJobFinished(final VirtualMachine vm, final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
            final Host host = findBackupJobHost(backup, vm);
            if (host != null) {
                final ThirdPartyBackupVolumeService.Transfer transfer = volumeTransfer(vm, backup, host);
                thirdPartyBackupVolumeService.track(backup, host, transfer);
                thirdPartyBackupVolumeService.reconcile(backup, host, transfer);
            }
            return true;
        }
        if (!(backup instanceof BackupVO)) {
            return false;
        }
        final Host host = findBackupJobHost(backup, vm);
        if (host == null) {
            LOG.debug("Skipping Veeam async completion check for backup [{}] because backup job host is unavailable",
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
            final String defaultFailureReason = "Host Veeam staging job " + jobState.toLowerCase(Locale.ROOT);
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
        }
        if ("CANCELED".equals(jobState)) {
            final BackupVO backupVO = backupDao.findById(backup.getId());
            if (backupVO != null) {
                updateBackupDetail(backupVO, AblestackBackupFrameworkUtils.BACKUP_CANCEL_REQUESTED_DETAIL, "true");
                updateBackupDetail(backupVO, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL, "WAITING");
                backupVO.setStatus(Backup.Status.Canceled);
                backupDao.update(backupVO.getId(), backupVO);
            }
            return true;
        }
        if (!"COMPLETED".equals(jobState)) {
            return false;
        }

        final BackupVO backupVO = backupDao.findById(backup.getId());
        if (backupVO == null) {
            return true;
        }
        backupDao.loadDetails(backupVO);
        final List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        final String backupEngine = StringUtils.defaultIfBlank(getBackupEngine(backupVO), BACKUP_ENGINE_QCOW2);
        final String checkpointName = getBackupCheckpointName(backupVO);
        final boolean incrementalBackup = BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backupVO.getType());
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
        if (BACKUP_ENGINE_QCOW2.equals(backupEngine)) {
            final String checkpointXml = readFileContentsOnHost(host.getId(), getCheckpointPath(backupVO.getExternalId(), checkpointName, backupEngine));
            if (StringUtils.isNotBlank(checkpointXml)) {
                final String checkpointXmlToStore = incrementalBackup ? checkpointXml : removeParentFromCheckpointXml(checkpointXml);
                backupDetailsDao.removeDetail(backupVO.getId(), DETAIL_CHECKPOINT_XML);
                backupDetailsDao.addDetail(backupVO.getId(), DETAIL_CHECKPOINT_XML, checkpointXmlToStore, false);
            }
        }
        backupVO.setDate(new Date());
        backupVO.setSize(backupVO.getProtectedSize());
        backupVO.setBackedUpVolumes(createVolumeInfoFromVolumes(vmVolumes, backupFiles));
        backupVO.setStatus(Backup.Status.BackedUp);
        backupDao.update(backupVO.getId(), backupVO);
        cleanupBackupJobFiles(host.getId(), backupVO.getUuid());
        LOG.info("{} phase=[STAGING_DONE], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupPath=[{}], size=[{}]",
                BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), backupVO.getExternalId(), backupVO.getSize());
        return true;
    }

    private String getHostBackupJobState(final Long hostId, final String backupJobId) {
        try {
            final Answer answer = agentManager.send(hostId, new AblestackBackupJobStatusCommand(backupJobId));
            if (answer instanceof BackupAnswer) {
                return StringUtils.defaultIfBlank(((BackupAnswer) answer).getState(), "UNKNOWN");
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to query Veeam backup job status [jobId: {}, hostId: {}]: {}", backupJobId, hostId, e.getMessage());
        }
        return "UNKNOWN";
    }

    private String getHostBackupJobDetails(final Long hostId, final String backupJobId, final String defaultDetails) {
        try {
            final Answer answer = agentManager.send(hostId, new AblestackBackupJobStatusCommand(backupJobId));
            return answer instanceof BackupAnswer
                    ? StringUtils.defaultIfBlank(answer.getDetails(), defaultDetails)
                    : defaultDetails;
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            return defaultDetails;
        }
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
                artifact -> getSshClient(backup.getZoneId()).volumeArtifactExists(artifact));
    }

    private ThirdPartyBackupVolumeService.Transfer volumeTransfer(final VirtualMachine vm, final Backup backup, final Host host) {
        final String template = getBackupDetail(backup, DETAIL_VEEAM_JOB_NAME);
        return new ThirdPartyBackupVolumeService.Transfer() {
            @Override
            public boolean ready() {
                return getSshClient(vm.getDataCenterId()).volumeSourceReady(template);
            }

            @Override
            public ThirdPartyBackupManifest.Artifact backup(final ThirdPartyBackupManifest.Artifact artifact, final boolean metadata) {
                return getSshClient(vm.getDataCenterId()).backupVolumeArtifact(template, host.getPrivateIpAddress(), artifact, metadata);
            }

            @Override
            public ThirdPartyBackupManifest.Artifact recover(ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
                return getSshClient(backup.getZoneId()).recoverVolumeArtifact(artifact, metadata);
            }

            @Override
            public ThirdPartyBackupManifest.Artifact link(ThirdPartyBackupManifest.Artifact artifact, boolean metadata, String jobId) {
                return getSshClient(backup.getZoneId()).linkVolumeArtifact(artifact, metadata, jobId);
            }

            @Override
            public void committed(Backup current, ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
                retireArtifactJob(current, artifact, metadata);
            }

            @Override
            public void completed(final Backup current) {
                if (ThirdPartyBackupManifest.fromJson(current.getDetail(ThirdPartyBackupManifest.DETAIL_KEY)).getOfflineGuard() == null
                        && BACKUP_ENGINE_QCOW2.equals(getBackupDetail(current, DETAIL_BACKUP_ENGINE))
                        && StringUtils.isBlank(getBackupDetail(current, DETAIL_CHECKPOINT_XML))) {
                    final String xml = readFileContentsOnHost(host.getId(), getBackupDetail(current, DETAIL_CHECKPOINT_PATH));
                    if (StringUtils.isBlank(xml)) {
                        throw new CloudRuntimeException("Completed QCOW2 backup checkpoint metadata is missing");
                    }
                    backupDetailsDao.addDetail(current.getId(), DETAIL_CHECKPOINT_XML, xml, false);
                    current.getDetails().put(DETAIL_CHECKPOINT_XML, xml);
                }
                retireCompletedArtifactJobs(current);
            }

            @Override
            public void failed(final Backup current) {
                sealParentBackupChainIfIncremental(current, "failed-volume-child");
            }
        };
    }

    private void retireArtifactJob(Backup backup, ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
        if (artifact == null || StringUtils.isBlank(artifact.path) || !backup.getUuid().equals(artifact.backupUuid)) {
            throw new CloudRuntimeException("Cannot retire another backup's Veeam job");
        }
        final String key = DETAIL_ARTIFACT_JOB_REMOVED_PREFIX + java.util.UUID.nameUUIDFromBytes(
                artifact.path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if ("COMPLETED".equals(getBackupDetail(backup, key))) { return; }
        getSshClient(backup.getZoneId()).retireVolumeArtifactJob(artifact, metadata);
        updateBackupDetail(backup, key, "COMPLETED");
    }

    private void retireCompletedArtifactJobs(Backup backup) {
        if ("COMPLETED".equals(getBackupDetail(backup, DETAIL_ARTIFACT_JOBS_CLEANUP))) { return; }
        final ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(getBackupDetail(backup, ThirdPartyBackupManifest.DETAIL_KEY));
        manifest.validate(true);
        if (!getName().equals(manifest.getProvider()) || !backup.getUuid().equals(manifest.getBackupUuid())) {
            throw new CloudRuntimeException("Veeam job cleanup manifest belongs to another backup");
        }
        updateBackupDetail(backup, DETAIL_ARTIFACT_JOBS_CLEANUP, "WAITING");
        for (ThirdPartyBackupManifest.Artifact artifact : manifest.getOwnedArtifacts()) {
            retireArtifactJob(backup, artifact, artifact == manifest.getMetadata());
        }
        updateBackupDetail(backup, DETAIL_ARTIFACT_JOBS_CLEANUP, "COMPLETED");
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

    private void sealParentBackupChainIfIncremental(final Backup backup, final String reason) {
        if (backup == null || !BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backup.getType())) {
            return;
        }
        loadBackupDetailsIfNeeded(backup);
        final String parentBackupUuid = getBackupDetail(backup, DETAIL_PARENT_BACKUP_UUID);
        final Backup parentBackup = StringUtils.isBlank(parentBackupUuid) ? null : backupDao.findByUuid(parentBackupUuid);
        if (parentBackup == null) {
            return;
        }
        updateBackupDetail(parentBackup, DETAIL_CHAIN_SEALED, Boolean.TRUE.toString());
        updateBackupDetail(parentBackup, DETAIL_CHAIN_SEAL_REASON, reason);
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
            final boolean quiesce = Boolean.parseBoolean(
                    getBackupDetail(failedBackup, AblestackBackupFrameworkUtils.BACKUP_QUIESCE_DETAIL));
            final String jobName = getBackupDetail(failedBackup, DETAIL_VEEAM_JOB_NAME);
            final Pair<Boolean, Backup> result = takeBackup(vm, quiesce, failedBackup.getBackupScheduleId(), jobName);
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

    private void cleanupBackupJobFiles(final Long hostId, final String backupJobId) {
        try {
            final Answer answer = agentManager.send(hostId, new AblestackBackupJobCleanupCommand(backupJobId));
            if (answer == null || !answer.getResult()) {
                LOG.warn("Failed to cleanup Veeam backup job files [jobId: {}, hostId: {}]: {}",
                        backupJobId, hostId, answer != null ? answer.getDetails() : null);
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to send Veeam backup job cleanup command [jobId: {}, hostId: {}]", backupJobId, hostId, e);
        }
    }

    @Override
    public boolean cancelBackup(final VirtualMachine vm, final Backup backup) {
        final Host host = findBackupJobHost(backup, vm);
        if (host == null) {
            LOG.warn("Failed to cancel Veeam backup [{}] for VM [{}]: backup job host was not found",
                    backup.getUuid(), vm.getInstanceName());
            return false;
        }
        try {
            final StopBackupAnswer answer = (StopBackupAnswer) agentManager.send(host.getId(),
                    new AblestackStopBackupCommand(vm.getInstanceName(), vm.getId(), backup.getId(), backup.getUuid()));
            return answer != null && answer.getResult();
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to cancel Veeam backup [{}] for VM [{}] on host [{}]",
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

    private void removeStaleBackingUpBackups(final VirtualMachine vm) {
        for (final Backup backup : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (!Backup.Status.BackingUp.equals(backup.getStatus())) {
                continue;
            }
            if (backup.getDate() == null || backup.getDate().getTime() > System.currentTimeMillis() - STALE_BACKUP_THRESHOLD_MS) {
                continue;
            }
            final BackupOfferingVO backupOffering = backupOfferingDao.findById(backup.getBackupOfferingId());
            if (backupOffering == null || !StringUtils.equalsIgnoreCase(getName(), backupOffering.getProvider())) {
                continue;
            }
            loadBackupDetailsIfNeeded(backup);
            if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
                // Volume jobs retain their plan, reservations and external references until the
                // common coordinator confirms termination and cleanup, regardless of their age.
                continue;
            }
            if (deferUnconfirmedStaleBackup(vm, backup)) { continue; }
            LOG.warn("Removing stale Veeam backup [{}] for VM [{}] stuck in BackingUp for over one day. "
                            + "Veeam post notify may have failed before the backup was finalized. "
                            + "Check Veeam catalog and host staging before removal if recovery is required. "
                            + "externalId=[{}], restorePointId=[{}], status=[{}], date=[{}].",
                    backup.getUuid(), vm.getInstanceName(), backup.getExternalId(),
                    getBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID), backup.getStatus(), backup.getDate());
            if (!cleanupFailedOrErrorBackupArtifacts(backup, true)) {
                final BackupVO backupVO = backupDao.findById(backup.getId());
                if (backupVO != null) {
                    markBackupFailure(backupVO, "stale-cleanup", "Stale Veeam artifact cleanup failed; cleanup host may be unavailable");
                    backupVO.setStatus(Backup.Status.Failed);
                    backupDao.update(backupVO.getId(), backupVO);
                    LOG.warn("Marked stale Veeam backup [{}] for VM [{}] as Failed because artifact cleanup could not be completed.",
                            backup.getUuid(), vm.getInstanceName());
                    continue;
                }
            }
            removeBackupWithDetails(backup.getId());
        }
    }

    private void syncMoldBackupsWithVeeamCatalog(final VirtualMachine vm) {
        final Map<String, Boolean> volumeAvailability = new HashMap<>();
        final List<Backup> moldBackups = backupDao.listByVmId(vm.getDataCenterId(), vm.getId()).stream()
                .filter(this::isVeeamBackup)
                .filter(backup -> {
                    loadBackupDetailsIfNeeded(backup);
                    if (backupDeletionService.resume(backup, point -> deleteOwnedBackup(point, false))) { return false; }
                    if (!ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) { return true; }
                    if (Backup.Status.BackedUp.equals(backup.getStatus())) {
                        try {
                            retireCompletedArtifactJobs(backup);
                        } catch (RuntimeException e) {
                            LOG.debug("Veeam temporary job cleanup remains pending for backup [{}]: {}", backup.getUuid(), e.getMessage());
                        }
                    }
                    if (StringUtils.isNotBlank(getBackupDetail(backup, ThirdPartyBackupManifest.DELETE_PROGRESS_KEY))
                            || (backup.getDate() != null && backup.getDate().getTime() <= System.currentTimeMillis() - VEEAM_SYNC_DELETE_GRACE_MS)) {
                        try {
                            final AblestackVeeamSshClient volumeClient = getSshClient(backup.getZoneId());
                            thirdPartyBackupVolumeService.reconcileCatalog(backup,
                                    artifact -> volumeAvailability.computeIfAbsent(artifact.externalId,
                                            id -> volumeClient.volumeArtifactExists(artifact)),
                                    (artifact, metadata, ticket, policyExpiration) -> new ThirdPartyBackupVolumeService.DeleteResult(
                                            volumeClient.deleteVolumeArtifact(artifact, metadata, policyExpiration), null));
                        } catch (RuntimeException e) {
                            LOG.debug("Veeam volume catalog sync is unavailable for backup [{}]: {}", backup.getUuid(), e.getMessage());
                        }
                    }
                    return false;
                })
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus()))
                .collect(Collectors.toList());
        if (moldBackups.isEmpty()) {
            return;
        }

        final VeeamCatalogQueryResult catalog;
        try {
            catalog = queryVeeamCatalogRestorePoints(vm, moldBackups);
        } catch (final Exception e) {
            LOG.warn("Skipping Veeam catalog delete sync for VM [{}]: {}", vm.getInstanceName(), e.getMessage());
            return;
        }

        stampMissingRestorePointIds(moldBackups, catalog.restorePoints);
        stampMissingVeeamJobNames(moldBackups, catalog.restorePoints);
        deleteMoldBackupsMissingFromVeeamCatalog(vm, moldBackups, catalog);
    }

    private VeeamCatalogQueryResult queryVeeamCatalogRestorePoints(final VirtualMachine vm, final List<Backup> moldBackups) {
        final Map<String, Backup.RestorePoint> byId = new LinkedHashMap<>();
        boolean anySuccess = false;
        boolean hostQuerySucceeded = false;
        boolean jobOrHostQuerySucceeded = false;
        Exception lastError = null;
        final Set<String> hostNames = getVeeamCatalogHostNames(vm);
        final Set<String> jobNames = new LinkedHashSet<>();
        final Set<String> queryNames = new LinkedHashSet<>();
        addIfNotBlank(queryNames, getVeeamSourceVmName(vm));
        addIfNotBlank(queryNames, vm.getInstanceName());
        queryNames.addAll(hostNames);
        // Agent file-level jobs are keyed by Job name (e.g. ablecube3), not guest VM name.
        for (final Backup backup : moldBackups) {
            loadBackupDetailsIfNeeded(backup);
            final String jobName = getBackupDetail(backup, DETAIL_VEEAM_JOB_NAME);
            addIfNotBlank(queryNames, jobName);
            addIfNotBlank(jobNames, jobName);
            addIfNotBlank(queryNames, getBackupDetail(backup, DETAIL_POLICY_NAME));
        }

        boolean syncedRepository = false;
        for (final String name : queryNames) {
            try {
                if (!syncedRepository && !Boolean.TRUE.equals(AblestackVeeamUseRestApi.valueIn(vm.getDataCenterId()))) {
                    try {
                        getClient(vm.getDataCenterId()).syncBackupRepository();
                        syncedRepository = true;
                    } catch (final Exception e) {
                        LOG.warn("Veeam EM repository sync skipped for VM [{}]: {}", vm.getInstanceName(), e.getMessage());
                        syncedRepository = true;
                    }
                }
                final List<Backup.RestorePoint> points = listVeeamRestorePointsForObjectName(vm.getDataCenterId(), name, true);
                anySuccess = true;
                if (hostNames.contains(name)) {
                    hostQuerySucceeded = true;
                    jobOrHostQuerySucceeded = true;
                }
                if (jobNames.stream().anyMatch(job -> normalizeVeeamJobName(job).equals(normalizeVeeamJobName(name)))) {
                    jobOrHostQuerySucceeded = true;
                }
                for (final Backup.RestorePoint restorePoint : points) {
                    if (restorePoint == null || StringUtils.isBlank(restorePoint.getId())) {
                        continue;
                    }
                    byId.putIfAbsent(normalizeVeeamRestorePointId(restorePoint.getId()), restorePoint);
                }
            } catch (final Exception e) {
                lastError = e;
                LOG.warn("Veeam restore-point query failed for name [{}] VM [{}]: {}", name, vm.getInstanceName(), e.getMessage());
            }
        }
        if (!anySuccess) {
            throw new CloudRuntimeException(String.format("Unable to query Veeam restore points for VM [%s]", vm.getInstanceName()), lastError);
        }
        return new VeeamCatalogQueryResult(new ArrayList<>(byId.values()), hostQuerySucceeded, jobOrHostQuerySucceeded);
    }

    private List<Backup.RestorePoint> listVeeamRestorePointsForObjectName(final Long zoneId, final String objectName) {
        return listVeeamRestorePointsForObjectName(zoneId, objectName, false);
    }

    private List<Backup.RestorePoint> listVeeamRestorePointsForObjectName(final Long zoneId, final String objectName,
            final boolean acceptEmptyRest) {
        if (StringUtils.isBlank(objectName)) {
            return Collections.emptyList();
        }
        if (Boolean.TRUE.equals(AblestackVeeamUseRestApi.valueIn(zoneId))) {
            try {
                final List<Backup.RestorePoint> restPoints = getRestClient(zoneId).listRestorePointsForVm(objectName);
                // Catalog-delete path: successful empty REST means Remove-from-Disk (avoid slow SSH hang).
                if (CollectionUtils.isNotEmpty(restPoints) || acceptEmptyRest) {
                    return restPoints != null ? restPoints : Collections.emptyList();
                }
                LOG.warn("Veeam REST restore-point query for [{}] returned empty; falling back to SSH", objectName);
            } catch (final Exception restError) {
                LOG.warn("Veeam REST restore-point query failed for [{}], falling back to SSH: {}", objectName, restError.getMessage());
            }
        }
        try {
            return getSshClient(zoneId).listRestorePointsForVmDisplayName(objectName);
        } catch (final Exception sshError) {
            LOG.warn("Veeam SSH restore-point query failed for [{}], falling back to EM client: {}", objectName, sshError.getMessage());
            return getClient(zoneId).listRestorePointsForVmDisplayName(objectName);
        }
    }

    private Set<String> getVeeamCatalogHostNames(final VirtualMachine vm) {
        final Set<String> names = new LinkedHashSet<>();
        addHostName(names, vm.getHostId());
        addHostName(names, vm.getLastHostId());
        final Backup latestBackup = getLatestBackedUpBackup(vm);
        if (latestBackup != null) {
            loadBackupDetailsIfNeeded(latestBackup);
            addIfNotBlank(names, getBackupDetail(latestBackup, DETAIL_SOURCE_HOST));
        }
        return names;
    }

    private void addHostName(final Set<String> names, final Long hostId) {
        if (hostId == null) {
            return;
        }
        final Host host = hostDao.findById(hostId);
        if (host == null) {
            return;
        }
        addIfNotBlank(names, host.getName());
        // Agent file-level restore points are often named by hypervisor IP, not hostname.
        addIfNotBlank(names, host.getPrivateIpAddress());
        addIfNotBlank(names, host.getPublicIpAddress());
    }

    private void stampMissingRestorePointIds(final List<Backup> moldBackups, final List<Backup.RestorePoint> catalogPoints) {
        if (CollectionUtils.isEmpty(catalogPoints)) {
            return;
        }
        final Set<String> usedRestorePointIds = new HashSet<>();
        for (final Backup backup : moldBackups) {
            loadBackupDetailsIfNeeded(backup);
            addIfNotBlank(usedRestorePointIds, normalizeVeeamRestorePointId(getBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID)));
        }

        final List<Backup> unstampedBackups = moldBackups.stream()
                .peek(this::loadBackupDetailsIfNeeded)
                .filter(backup -> StringUtils.isBlank(getBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID)))
                .filter(backup -> backup.getDate() != null)
                .sorted(Comparator.comparing(Backup::getDate))
                .collect(Collectors.toList());

        final List<Backup.RestorePoint> unusedPoints = catalogPoints.stream()
                .filter(restorePoint -> restorePoint.getCreated() != null)
                .filter(restorePoint -> !usedRestorePointIds.contains(normalizeVeeamRestorePointId(restorePoint.getId())))
                .sorted(Comparator.comparing(Backup.RestorePoint::getCreated))
                .collect(Collectors.toList());

        // Prefer job-name + time matches so Agent host jobs stamp correctly onto guest VM rows.
        for (final Backup backup : unstampedBackups) {
            if (StringUtils.isNotBlank(getBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID))) {
                continue;
            }
            final String backupJob = StringUtils.trimToNull(getBackupDetail(backup, DETAIL_VEEAM_JOB_NAME));
            Backup.RestorePoint best = null;
            long bestDelta = Long.MAX_VALUE;
            for (final Backup.RestorePoint restorePoint : unusedPoints) {
                final String rpId = normalizeVeeamRestorePointId(restorePoint.getId());
                if (StringUtils.isBlank(backupJob) && usedRestorePointIds.contains(rpId)) {
                    continue;
                }
                if (StringUtils.isNotBlank(backupJob)
                        && StringUtils.isNotBlank(restorePoint.getJobName())
                        && !normalizeVeeamJobName(backupJob).equals(normalizeVeeamJobName(restorePoint.getJobName()))) {
                    continue;
                }
                final long delta = Math.abs(restorePoint.getCreated().getTime() - backup.getDate().getTime());
                if (delta <= VEEAM_RP_TIME_MATCH_MS && delta < bestDelta) {
                    best = restorePoint;
                    bestDelta = delta;
                }
            }
            if (best == null && StringUtils.isBlank(backupJob)) {
                for (final Backup.RestorePoint restorePoint : unusedPoints) {
                    final String rpId = normalizeVeeamRestorePointId(restorePoint.getId());
                    if (usedRestorePointIds.contains(rpId)) {
                        continue;
                    }
                    final long delta = Math.abs(restorePoint.getCreated().getTime() - backup.getDate().getTime());
                    if (delta <= VEEAM_RP_TIME_MATCH_MS && delta < bestDelta) {
                        best = restorePoint;
                        bestDelta = delta;
                    }
                }
            }
            if (best == null) {
                continue;
            }
            final String restorePointId = normalizeVeeamRestorePointId(best.getId());
            updateBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID, restorePointId);
            // Agent host jobs produce one Veeam RP per run for many guest VMs — allow reuse when job matches.
            if (StringUtils.isBlank(backupJob)) {
                usedRestorePointIds.add(restorePointId);
            }
            if (StringUtils.isNotBlank(best.getJobName())
                    && StringUtils.isBlank(getBackupDetail(backup, DETAIL_VEEAM_JOB_NAME))) {
                updateBackupDetail(backup, DETAIL_VEEAM_JOB_NAME, best.getJobName().trim());
            }
            LOG.info("Stamped Veeam restore point [{}] onto Mold backup [{}] for catalog sync", restorePointId, backup.getUuid());
        }
    }

    private void stampMissingVeeamJobNames(final List<Backup> moldBackups, final List<Backup.RestorePoint> catalogPoints) {
        final Map<String, String> restorePointIdToJob = new HashMap<>();
        for (final Backup.RestorePoint restorePoint : catalogPoints) {
            if (restorePoint == null || StringUtils.isBlank(restorePoint.getId()) || StringUtils.isBlank(restorePoint.getJobName())) {
                continue;
            }
            restorePointIdToJob.putIfAbsent(normalizeVeeamRestorePointId(restorePoint.getId()), restorePoint.getJobName().trim());
        }
        final Set<String> distinctJobs = new LinkedHashSet<>(restorePointIdToJob.values());
        for (final Backup backup : moldBackups) {
            loadBackupDetailsIfNeeded(backup);
            if (StringUtils.isNotBlank(getBackupDetail(backup, DETAIL_VEEAM_JOB_NAME))) {
                continue;
            }
            String jobName = restorePointIdToJob.get(normalizeVeeamRestorePointId(getBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID)));
            if (StringUtils.isBlank(jobName) && distinctJobs.size() == 1) {
                jobName = distinctJobs.iterator().next();
            }
            if (StringUtils.isNotBlank(jobName)) {
                updateBackupDetail(backup, DETAIL_VEEAM_JOB_NAME, jobName);
                LOG.info("Stamped Veeam job [{}] onto Mold backup [{}] for job-delete sync", jobName, backup.getUuid());
            }
        }
    }

    private void removeMoldBackupsForDeletedVeeamJobs(final VirtualMachine vm) {
        // SSH Get-VBRJob inventory often hangs; with REST catalog sync, Remove-from-Disk is covered
        // by restore-point matching. Skip job-name wipe in REST mode.
        if (Boolean.TRUE.equals(AblestackVeeamUseRestApi.valueIn(vm.getDataCenterId()))) {
            LOG.debug("Skipping Veeam job-delete sync for VM [{}]: REST mode uses restore-point catalog only",
                    vm.getInstanceName());
            return;
        }
        final Set<String> liveJobs;
        try {
            liveJobs = listVeeamBackupJobNames(vm.getDataCenterId());
        } catch (final Exception e) {
            LOG.warn("Skipping Veeam job-delete sync for VM [{}]: {}", vm.getInstanceName(), e.getMessage());
            return;
        }
        // Empty live job list is untrustworthy (SSH/EM probe gaps, Agent-only labs). Never wipe Mold rows on that basis.
        if (liveJobs.isEmpty()) {
            LOG.warn("Skipping Veeam job-delete sync for VM [{}]: live Veeam job list is empty", vm.getInstanceName());
            return;
        }

        final long now = System.currentTimeMillis();
        final Set<Long> toRemove = new LinkedHashSet<>();
        final Map<Long, String> jobNamesByBackupId = new HashMap<>();
        for (final Backup backup : backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (!isVeeamBackup(backup)) {
                continue;
            }
            if (backup.getDate() != null && backup.getDate().getTime() > now - VEEAM_SYNC_DELETE_GRACE_MS) {
                continue;
            }
            loadBackupDetailsIfNeeded(backup);
            // RBD_DIFF: never wipe from job-inventory gaps (Agent catalog probes are incomplete).
            if (ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY))) {
                continue; // The template job's removal does not remove its independent child restore points.
            }
            // Explicit Veeam Remove-from-Disk for RBD is handled only when a stamped restorePointId
            // disappears from a trusted non-empty catalog (primary path below + host list-diff).
            if (BACKUP_ENGINE_RBD_DIFF.equalsIgnoreCase(getBackupEngine(backup))) {
                continue;
            }
            final String jobName = StringUtils.trimToNull(getBackupDetail(backup, DETAIL_VEEAM_JOB_NAME));
            if (StringUtils.isBlank(jobName)) {
                continue;
            }
            if (liveJobs.stream().noneMatch(liveJob -> normalizeVeeamJobName(liveJob).equals(normalizeVeeamJobName(jobName)))) {
                toRemove.add(backup.getId());
                jobNamesByBackupId.put(backup.getId(), jobName);
            }
        }
        if (toRemove.isEmpty()) {
            return;
        }

        final List<Backup> backupsToRemove = toRemove.stream()
                .map(backupDao::findByIdIncludingRemoved)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        cleanupExpiredBackupArtifacts(backupsToRemove, toRemove);
        for (final Long backupId : toRemove) {
            final Backup backup = backupDao.findById(backupId);
            LOG.warn("Removing Mold Veeam backup [{}] for VM [{}] because Veeam job [{}] no longer exists.",
                    backup != null ? backup.getUuid() : backupId, vm.getInstanceName(), jobNamesByBackupId.get(backupId));
            removeBackupWithDetails(backupId);
        }
    }

    private Set<String> listVeeamBackupJobNames(final Long zoneId) {
        // Prefer SSH/PowerShell so job-delete sync works without Enterprise Manager (9398).
        try {
            return new LinkedHashSet<>(getSshClient(zoneId).listBackupJobNames());
        } catch (final Exception sshError) {
            LOG.warn("Veeam job list over SSH failed, falling back to EM client: {}", sshError.getMessage());
            return new LinkedHashSet<>(getClient(zoneId).listBackupJobNames());
        }
    }

    private String normalizeVeeamJobName(final String jobName) {
        String normalized = StringUtils.trimToEmpty(jobName);
        // Strip smart/curly quotes often pasted into Veeam UI / host conf (U+2018/2019/201C/201D).
        normalized = normalized.replace('\u2018', ' ').replace('\u2019', ' ')
                .replace('\u201C', ' ').replace('\u201D', ' ')
                .replace('\'', ' ').replace('"', ' ');
        normalized = StringUtils.deleteWhitespace(normalized);
        return normalized.toLowerCase(Locale.ROOT);
    }

    private boolean veeamRestorePointExists(final Long zoneId, final String restorePointId) {
        if (Boolean.TRUE.equals(AblestackVeeamUseRestApi.valueIn(zoneId))) {
            try {
                // REST list is already in catalogIds when query succeeded; fall through to SSH for ID probe.
                return getSshClient(zoneId).restorePointExists(restorePointId);
            } catch (final Exception e) {
                LOG.warn("Veeam REST-mode restore-point probe via SSH failed for [{}]: {}", restorePointId, e.getMessage());
                throw e;
            }
        }
        return getSshClient(zoneId).restorePointExists(restorePointId);
    }

    private boolean restorePointMatchesBackupWindow(final Backup.RestorePoint restorePoint, final Backup backup) {
        return restorePoint != null && restorePoint.getCreated() != null && backup.getDate() != null
                && Math.abs(restorePoint.getCreated().getTime() - backup.getDate().getTime()) <= VEEAM_RP_TIME_MATCH_MS;
    }

    private void deleteMoldBackupsMissingFromVeeamCatalog(final VirtualMachine vm, final List<Backup> moldBackups,
            final VeeamCatalogQueryResult catalog) {
        // Empty catalog is untrusted when EM :9398 times out or SSH job/VM list returns nothing —
        // otherwise every BackedUp row (RBD + GFS/QCOW2) is wiped after a successful host job.
        if (catalog == null || CollectionUtils.isEmpty(catalog.restorePoints)) {
            LOG.warn("Skipping Veeam catalog-delete for VM [{}]: empty restore-point catalog is untrusted",
                    vm.getInstanceName());
            return;
        }
        final Set<String> catalogIds = catalog.restorePoints.stream()
                .map(restorePoint -> normalizeVeeamRestorePointId(restorePoint.getId()))
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        final long now = System.currentTimeMillis();
        final Set<Long> removedBackupIds = new HashSet<>();
        final Set<String> processedMissingRestorePoints = new HashSet<>();

        // NetBackup parity: stamped catalog ID gone → remove group (QCOW2 and RBD_DIFF).
        // Confirm with per-ID probe (like NetBackup GET /catalog/images/{id}) so an incomplete
        // Agent RP list cannot wipe live snaps after backup. No host catalog-sync timer.
        for (final Backup backup : moldBackups) {
            if (removedBackupIds.contains(backup.getId())) {
                continue;
            }
            if (!Backup.Status.BackedUp.equals(backup.getStatus()) || backup.getDate() == null) {
                continue;
            }
            if (backup.getDate().getTime() > now - VEEAM_SYNC_DELETE_GRACE_MS) {
                continue;
            }
            loadBackupDetailsIfNeeded(backup);
            final String restorePointId = normalizeVeeamRestorePointId(getBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID));
            if (StringUtils.isBlank(restorePointId)) {
                continue;
            }
            if (catalogIds.contains(restorePointId)) {
                continue;
            }
            if (!processedMissingRestorePoints.add(restorePointId)) {
                continue;
            }
            // List miss is not enough — verify ID-level existence (NetBackup backupImageExists).
            try {
                if (veeamRestorePointExists(vm.getDataCenterId(), restorePointId)) {
                    LOG.debug("Keeping Mold backup group for restorePointId [{}] on VM [{}]: still present in Veeam (list incomplete)",
                            restorePointId, vm.getInstanceName());
                    continue;
                }
            } catch (final Exception e) {
                LOG.warn("Skipping Veeam catalog-delete for restorePointId [{}] on VM [{}]: existence probe failed: {}",
                        restorePointId, vm.getInstanceName(), e.getMessage());
                continue;
            }
            final List<Long> removedIds = removeBackupGroupByRestorePointId(restorePointId);
            removedBackupIds.addAll(removedIds);
            LOG.warn("Removed Veeam backup group identified by restorePointId [{}] for VM [{}] because the catalog image no longer exists in Veeam. Removed backup row ids={}",
                    restorePointId, vm.getInstanceName(), removedIds);
        }

        // Fallback for unstamped QCOW2 only. RBD_DIFF requires a stamped restorePointId (above).
        final Set<Long> toRemove = new LinkedHashSet<>();
        for (final Backup backup : moldBackups) {
            if (removedBackupIds.contains(backup.getId())) {
                continue;
            }
            if (backup.getDate() != null && backup.getDate().getTime() > now - VEEAM_SYNC_DELETE_GRACE_MS) {
                continue;
            }
            loadBackupDetailsIfNeeded(backup);
            if (BACKUP_ENGINE_RBD_DIFF.equalsIgnoreCase(getBackupEngine(backup))) {
                LOG.debug("Skipping Veeam catalog fallback-delete for Mold-native RBD_DIFF backup [{}] on VM [{}]",
                        backup.getUuid(), vm.getInstanceName());
                continue;
            }
            final String restorePointId = normalizeVeeamRestorePointId(getBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID));
            if (StringUtils.isNotBlank(restorePointId)) {
                // Already handled by the group path above (present or removed).
                continue;
            }
            final String jobName = StringUtils.trimToNull(getBackupDetail(backup, DETAIL_VEEAM_JOB_NAME));
            if (StringUtils.isNotBlank(jobName) && backup.getDate() != null) {
                boolean matchedLiveRp = catalog.restorePoints.stream()
                        .filter(rp -> restorePointMatchesBackupWindow(rp, backup))
                        .anyMatch(rp -> StringUtils.isBlank(rp.getJobName())
                                || normalizeVeeamJobName(jobName).equals(normalizeVeeamJobName(rp.getJobName())));
                if (!matchedLiveRp) {
                    matchedLiveRp = catalog.restorePoints.stream()
                            .anyMatch(rp -> restorePointMatchesBackupWindow(rp, backup));
                }
                if (!matchedLiveRp && (CollectionUtils.isNotEmpty(catalog.restorePoints) || catalog.trustedEmptyCatalog())) {
                    toRemove.add(backup.getId());
                }
                continue;
            }
            if (catalog.trustedEmptyCatalog() && backup.getDate() != null) {
                final long graceMs = (Backup.Status.Failed.equals(backup.getStatus())
                        || Backup.Status.Error.equals(backup.getStatus()))
                        ? VEEAM_SYNC_DELETE_GRACE_MS
                        : VEEAM_UNSTAMPED_DELETE_GRACE_MS;
                if (backup.getDate().getTime() <= now - graceMs) {
                    toRemove.add(backup.getId());
                }
            }
        }

        toRemove.removeIf(backupId -> hasDependentBackupOutsideRemoval(backupDao.findById(backupId), toRemove));
        toRemove.removeAll(removedBackupIds);
        if (toRemove.isEmpty()) {
            return;
        }

        final List<Backup> backupsToRemove = toRemove.stream()
                .map(backupDao::findByIdIncludingRemoved)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        cleanupExpiredBackupArtifacts(backupsToRemove, toRemove);
        for (final Long backupId : toRemove) {
            final Backup backup = backupDao.findById(backupId);
            LOG.warn("Removing Mold Veeam backup [{}] for VM [{}] because it is no longer present in the Veeam catalog.",
                    backup != null ? backup.getUuid() : backupId, vm.getInstanceName());
            removeBackupWithDetails(backupId);
        }
    }

    private String normalizeVeeamRestorePointId(final String restorePointId) {
        if (StringUtils.isBlank(restorePointId)) {
            return null;
        }
        return restorePointId.replace("{", "").replace("}", "").trim().toLowerCase(Locale.ROOT);
    }

    /**
     * NetBackup-style group delete: one Veeam restore point may map to multiple Mold rows
     * (host Agent job covering several guest VMs). Remove all rows stamped with that RP ID,
     * cleanup host artifacts first, then drop metadata.
     */
    private List<Long> removeBackupGroupByRestorePointId(final String restorePointId) {
        final String normalized = normalizeVeeamRestorePointId(restorePointId);
        final Set<Long> backupIdsToRemove = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(normalized)) {
            for (final BackupDetailVO detail : backupDetailsDao.findDetails(DETAIL_VEEAM_RESTORE_POINT_ID)) {
                if (detail == null) {
                    continue;
                }
                if (normalized.equals(normalizeVeeamRestorePointId(detail.getValue()))) {
                    backupIdsToRemove.add(detail.getResourceId());
                }
            }
            // Legacy exact-value lookup (pre-normalized stamps / DETAIL_BACKUP_ID alias).
            backupDetailsDao.findDetails(DETAIL_VEEAM_RESTORE_POINT_ID, normalized, false).stream()
                    .map(BackupDetailVO::getResourceId)
                    .forEach(backupIdsToRemove::add);
            backupDetailsDao.findDetails(DETAIL_BACKUP_ID, normalized, false).stream()
                    .map(BackupDetailVO::getResourceId)
                    .forEach(backupIdsToRemove::add);
        }
        return removeBackupIdsWithArtifactCleanup(backupIdsToRemove);
    }

    private List<Long> removeBackupGroup(final String backupId) {
        // Kept for DETAIL_BACKUP_ID callers; prefer removeBackupGroupByRestorePointId for catalog sync.
        final Set<Long> backupIdsToRemove = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(backupId)) {
            backupDetailsDao.findDetails(DETAIL_BACKUP_ID, backupId, false).stream()
                    .map(BackupDetailVO::getResourceId)
                    .forEach(backupIdsToRemove::add);
            final String normalized = normalizeVeeamRestorePointId(backupId);
            if (StringUtils.isNotBlank(normalized)) {
                backupIdsToRemove.addAll(collectBackupIdsForRestorePointId(normalized));
            }
        }
        return removeBackupIdsWithArtifactCleanup(backupIdsToRemove);
    }

    private Set<Long> collectBackupIdsForRestorePointId(final String normalizedRestorePointId) {
        final Set<Long> ids = new LinkedHashSet<>();
        for (final BackupDetailVO detail : backupDetailsDao.findDetails(DETAIL_VEEAM_RESTORE_POINT_ID)) {
            if (detail == null) {
                continue;
            }
            if (normalizedRestorePointId.equals(normalizeVeeamRestorePointId(detail.getValue()))) {
                ids.add(detail.getResourceId());
            }
        }
        return ids;
    }

    private List<Long> removeBackupIdsWithArtifactCleanup(final Set<Long> backupIdsToRemove) {
        if (backupIdsToRemove == null || backupIdsToRemove.isEmpty()) {
            return Collections.emptyList();
        }

        final List<Long> removedIds = new ArrayList<>();
        final List<Backup> backupsToRemove = backupIdsToRemove.stream()
                .map(backupDao::findByIdIncludingRemoved)
                .filter(Objects::nonNull)
                .filter(this::isVeeamBackup)
                .filter(backup -> {
                    loadBackupDetailsIfNeeded(backup);
                    return !ThirdPartyBackupManifest.VOLUME_MODE.equals(getBackupDetail(backup, ThirdPartyBackupManifest.MODE_KEY));
                })
                .collect(Collectors.toList());
        final Set<Long> veeamIds = backupsToRemove.stream().map(Backup::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        veeamIds.removeIf(backupId -> hasDependentBackupOutsideRemoval(backupDao.findById(backupId), veeamIds));
        if (veeamIds.isEmpty()) {
            return Collections.emptyList();
        }
        final List<Backup> cleaned = backupsToRemove.stream()
                .filter(backup -> veeamIds.contains(backup.getId()))
                .collect(Collectors.toList());
        cleanupExpiredBackupArtifacts(cleaned, veeamIds);
        for (final Long backupIdToRemove : veeamIds) {
            final Backup backup = backupDao.findByIdIncludingRemoved(backupIdToRemove);
            if (backup == null) {
                continue;
            }
            final BackupOfferingVO backupOffering = backupOfferingDao.findById(backup.getBackupOfferingId());
            if (backupOffering == null || !StringUtils.equalsIgnoreCase(getName(), backupOffering.getProvider())) {
                continue;
            }
            removeBackupWithDetails(backupIdToRemove);
            removedIds.add(backupIdToRemove);
        }
        return removedIds;
    }

    private boolean isVeeamBackup(final Backup backup) {
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
                LOG.warn("Failed to cleanup expired Veeam artifact for backup [{}]. Mold metadata will still be removed. Cause: {}",
                        backup.getUuid(), e.getMessage(), e);
            }
        }
    }

    private void cleanupExpiredBackupArtifact(final Backup backup, final Set<Long> backupIdsToRemove) {
        loadBackupDetailsIfNeeded(backup);
        if (hasDependentBackupOutsideRemoval(backup, backupIdsToRemove)) {
            LOG.info("Skipping Veeam artifact cleanup for backup [{}] because a remaining backup still depends on checkpoint [{}].",
                    backup.getUuid(), getBackupCheckpointName(backup));
            return;
        }

        final String checkpointName = getBackupCheckpointName(backup);
        if (StringUtils.isBlank(checkpointName) || StringUtils.isBlank(backup.getExternalId())) {
            return;
        }

        final Host cleanupHost = resolveBackupCleanupHost(backup);
        if (cleanupHost == null) {
            LOG.warn("Skipping Veeam artifact cleanup for backup [{}] because no available KVM cleanup host was found.", backup.getUuid());
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
        if (BACKUP_ENGINE_RBD_DIFF.equals(getBackupEngine(backup))) {
            command.setDiskPaths(getRbdDiskPaths(backup));
        }

        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(cleanupHost.getId(), command);
            if (answer == null || !answer.getResult()) {
                LOG.warn("Veeam artifact cleanup failed for backup [{}] on host [{}]: {}",
                        backup.getUuid(), cleanupHost.getName(), answer != null ? answer.getDetails() : "no answer received");
            }
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Unable to cleanup expired Veeam artifact for backup [{}] on host [{}]: {}",
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
        if (!BACKUP_ENGINE_QCOW2.equals(getBackupEngine(backup))) {
            return null;
        }

        final Set<String> cleanupCandidates = new LinkedHashSet<>();
        addIfNotBlank(cleanupCandidates, getBackupCheckpointName(backup));
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
                .filter(this::isVeeamBackup)
                .forEach(candidate -> {
                    backupDao.loadDetails(candidate);
                    // Completed failed/canceled cleanup leaves history, but no source checkpoint dependency.
                    if ("COMPLETED".equals(getBackupDetail(candidate, AblestackBackupFrameworkUtils.BACKUP_CLEANUP_STATE_DETAIL))) { return; }
                    addIfNotBlank(remainingReferences, getBackupCheckpointName(candidate));
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

    private static final class VeeamCatalogQueryResult {
        private final List<Backup.RestorePoint> restorePoints;
        private final boolean hostQuerySucceeded;
        private final boolean jobOrHostQuerySucceeded;

        private VeeamCatalogQueryResult(final List<Backup.RestorePoint> restorePoints, final boolean hostQuerySucceeded,
                final boolean jobOrHostQuerySucceeded) {
            this.restorePoints = restorePoints != null ? restorePoints : Collections.emptyList();
            this.hostQuerySucceeded = hostQuerySucceeded;
            this.jobOrHostQuerySucceeded = jobOrHostQuerySucceeded;
        }

        /** True when a host/job-scoped query succeeded and returned zero restore points (Remove from Disk). */
        private boolean trustedEmptyCatalog() {
            return restorePoints.isEmpty() && (hostQuerySucceeded || jobOrHostQuerySucceeded);
        }
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
