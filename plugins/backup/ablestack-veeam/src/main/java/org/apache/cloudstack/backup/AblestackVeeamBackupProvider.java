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
import com.cloud.agent.api.Answer;
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

import static org.apache.cloudstack.backup.BackupManager.BackupChainSize;
import static org.apache.cloudstack.backup.BackupManager.BackupCommandTimeout;
import static org.apache.cloudstack.backup.BackupManager.BackupFrameworkEnabled;
import static org.apache.cloudstack.backup.BackupManager.BackupRestoreTimeout;
import static org.apache.cloudstack.backup.BackupManager.KvmIncrementalBackup;

public class AblestackVeeamBackupProvider extends AdapterBase implements BackupProvider, Configurable {

    private static final Logger LOG = LogManager.getLogger(AblestackVeeamBackupProvider.class);

    private static final String DEFAULT_STAGE_ROOT_PATH = "/tmp/mold/veeam";
    private static final String BACKUP_TYPE_FULL = "FULL";
    private static final String BACKUP_TYPE_INCREMENTAL = "INCREMENTAL";
    private static final String BACKUP_ENGINE_QCOW2 = "QCOW2";
    private static final String BACKUP_ENGINE_RBD_DIFF = "RBD_DIFF";
    private static final String BACKUP_TRACE = "[ABLESTACK_VEEAM_BACKUP_TRACE]";
    private static final String RESTORE_TRACE = "[ABLESTACK_VEEAM_RESTORE_TRACE]";
    private static final long STAGE_SPACE_BUFFER_BYTES = 10L * 1024L * 1024L * 1024L;
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
    /** KVM host that wrote the local staging backup files under /tmp/mold/veeam/... */
    private static final String DETAIL_SOURCE_HOST = "ablestack.veeam.source.host";
    private static final String DETAIL_RESTORE_ROOT_JOB_ID = "ablestack.veeam.restore.root.job.id";
    private static final String DETAIL_RESTORE_CHAIN_JOB_ID = "ablestack.veeam.restore.chain.job.id";
    private static final String DETAIL_FAILURE_PHASE = "ablestack.veeam.failure.phase";
    private static final String DETAIL_FAILURE_REASON = "ablestack.veeam.failure.reason";
    private static final String MISSING_PARENT_RBD_SNAPSHOT_ERROR = "Parent RBD snapshot";
    private static final String MISSING_PARENT_QCOW2_BITMAP_ERROR = "Parent qcow2 bitmap";
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
    public static final String DETAIL_VEEAM_IMPORTED = "ablestack.veeam.imported";

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

    public ConfigKey<String> AblestackVeeamStagingPath = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.staging.path", "/var/ablestack-veeam-staging",
            "Shared staging directory on the Veeam server for disk export before seed import.",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    private ConfigKey<String> AblestackVeeamStageRootPath = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.ablestack-veeam.stage.root.path", DEFAULT_STAGE_ROOT_PATH,
            "Local KVM host directory used for ABLESTACK Veeam backup staging (host Agent job SelectedFiles root).",
            true, ConfigKey.Scope.Global);

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

    public ConfigKey<Boolean> AblestackVeeamSyncDeleteRbdDiff = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.ablestack-veeam.sync.delete.rbd.diff", "true",
            "When true (default): BackupSync removes Mold RBD_DIFF rows when the matching Veeam restore "
                    + "point is gone or the Agent Disk catalog is trusted-empty (Remove from Disk). "
                    + "Unstamped RBD rows are only removed on trusted-empty catalog, not on partial time-match misses. "
                    + "Ignored when backup.plugin.ablestack-veeam.sync.delete.missing.catalog is false.",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    public ConfigKey<Boolean> AblestackVeeamSyncDeleteMissingCatalog = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.ablestack-veeam.sync.delete.missing.catalog", "false",
            "When false (default): BackupSync never removes Mold backup rows just because they are missing "
                    + "from the Veeam restore-point catalog or because a Veeam job name disappeared. "
                    + "Mold backups are only removed when the user/API explicitly deletes them. "
                    + "Set true only if Mold history must track Veeam Remove-from-Disk automatically.",
            true, ConfigKey.Scope.Zone, BackupFrameworkEnabled.key());

    @Inject
    private BackupDao backupDao;
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
        final Host host = getVMHypervisorHostForBackup(vm);
        validateVmSnapshotCoexistenceForBackup(vm);

        final List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        validateVolumePoolTypes(volumePoolsAndPaths.first());

        final BackupVO latestBackup = getLatestBackedUpBackup(vm);
        final boolean incrementalBackup = shouldUseIncrementalBackup(vm, latestBackup, backupScheduleId);
        final String resolvedJobName = resolveVeeamJobName(veeamJobName, latestBackup);
        BackupExecutionResult result = executeBackup(vm, quiesceVM, host, vmVolumes, volumePoolsAndPaths, latestBackup,
                incrementalBackup, resolvedJobName);
        Backup failedIncrementalBackup = null;
        if (!result.success && incrementalBackup && canRetryFailedIncrementalAsFull(result) && shouldRetryAsFullAfterIncrementalFailure(result, vmVolumes)) {
            failedIncrementalBackup = result.backup;
            cleanupFailedBackupForFullRetry(host, failedIncrementalBackup);
            LOG.warn("{} phase=[INCREMENTAL_FALLBACK_TO_FULL], vmId=[{}], vmName=[{}], failedBackupUuid=[{}], reason=[{}]",
                    BACKUP_TRACE, vm.getId(), vm.getInstanceName(),
                    failedIncrementalBackup != null ? failedIncrementalBackup.getUuid() : null, result.details);
            result = executeBackup(vm, quiesceVM, host, vmVolumes, volumePoolsAndPaths, null, false,
                    resolvedJobName);
            if (result.success && failedIncrementalBackup != null) {
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
        final String backupPath = buildBackupPath(vm);
        final String checkpointName = backupPath.substring(backupPath.lastIndexOf("/") + 1);
        final String backupEngine = areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2;
        final String requestedBackupType = incrementalBackup ? BACKUP_TYPE_INCREMENTAL : BACKUP_TYPE_FULL;
        validateBackupStageCapacity(vmHost, getBackupStageRootPath(), vmVolumes, vm.getInstanceName(), requestedBackupType, backupEngine);
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
        final Map<String, String> backupDetails = getBackupDetails(vm, backupPath, checkpointName, backupEngine, latestBackup,
                incrementalBackup, policyName);
        if (vmHost != null && StringUtils.isNotBlank(vmHost.getName())) {
            backupDetails.put(DETAIL_SOURCE_HOST, vmHost.getName());
        }

        final BackupVO backupVO = createBackupObject(vm, backupPath, requestedBackupType, backupDetails);
        LOG.info("{} phase=[BEGIN], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], backupPath=[{}], host=[{}]",
                BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                backupPath, vmHost != null ? vmHost.getName() : null);
        AblestackVeeamTakeBackupCommand command = new AblestackVeeamTakeBackupCommand(vm.getInstanceName(), backupPath);
        final int commandTimeout = BackupCommandTimeout.value();
        if (commandTimeout > 0) {
            command.setWait(commandTimeout);
        }
        command.setQuiesce(quiesceVM);
        command.setVolumePools(volumePoolsAndPaths.first());
        command.setVolumePaths(volumePoolsAndPaths.second());
        command.setBackupType(requestedBackupType);
        command.setCheckpointName(checkpointName);
        command.setBackupFiles(backupFiles);
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

        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(vmHost.getId(), command);
            if (answer != null && answer.getResult()) {
                if (BACKUP_ENGINE_QCOW2.equals(backupEngine)) {
                    final String checkpointXml = readFileContentsOnHost(vmHost.getId(), getCheckpointPath(backupPath, checkpointName, backupEngine));
                    if (StringUtils.isNotBlank(checkpointXml)) {
                        final String checkpointXmlToStore = incrementalBackup ? checkpointXml : removeParentFromCheckpointXml(checkpointXml);
                        backupDetails.put(DETAIL_CHECKPOINT_XML, checkpointXmlToStore);
                        backupDetailsDao.removeDetail(backupVO.getId(), DETAIL_CHECKPOINT_XML);
                        backupDetailsDao.addDetail(backupVO.getId(), DETAIL_CHECKPOINT_XML, checkpointXmlToStore, false);
                    }
                }

                backupVO.setDate(new Date());
                backupVO.setSize(answer.getSize() != null ? answer.getSize() : backupVO.getProtectedSize());
                backupVO.setDetails(backupDetails);
                backupVO.setBackedUpVolumes(createVolumeInfoFromVolumes(vmVolumes, backupFiles));
                backupVO.setStatus(Backup.Status.BackedUp);
                if (backupDao.update(backupVO.getId(), backupVO)) {
                    LOG.info("{} phase=[SUCCESS], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], size=[{}]",
                            BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType,
                            backupEngine, backupVO.getSize());
                    return BackupExecutionResult.success(backupVO);
                }
                LOG.error("{} phase=[METADATA_UPDATE_FAILED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}]",
                        BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine);
                markBackupFailure(backupVO, "metadata-update", "Failed to update Veeam backup metadata");
                backupVO.setStatus(Backup.Status.Error);
                backupDao.update(backupVO.getId(), backupVO);
                return BackupExecutionResult.failure("Failed to update Veeam backup metadata", backupVO);
            }

            final String details = answer != null ? answer.getDetails() : "No answer received";
            LOG.error("{} phase=[AGENT_FAILED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], details=[{}]",
                    BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine, details);
            markBackupFailure(backupVO, "agent-answer", details);
            final boolean cleanupSuccessful = cleanupFailedBackupArtifacts(vmHost, backupVO);
            backupVO.setStatus(cleanupSuccessful ? Backup.Status.Failed : Backup.Status.Error);
            backupDao.update(backupVO.getId(), backupVO);
            return BackupExecutionResult.failure(details, backupVO);
        } catch (final AgentUnavailableException e) {
            markBackupFailure(backupVO, "agent-send", "Unable to contact backend control plane to initiate Veeam backup");
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.update(backupVO.getId(), backupVO);
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate Veeam backup", e);
        } catch (final OperationTimedoutException e) {
            markBackupFailure(backupVO, "agent-send-timeout", "Operation to initiate Veeam backup timed out");
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.update(backupVO.getId(), backupVO);
            throw new CloudRuntimeException("Operation to initiate Veeam backup timed out, please try again", e);
        } catch (final RuntimeException e) {
            markBackupFailure(backupVO, "unexpected-runtime", e.getMessage());
            try {
                final Backup existingBackup = backupDao.findById(backupVO.getId());
                if (existingBackup != null) {
                    backupVO.setStatus(Backup.Status.Failed);
                    backupDao.update(backupVO.getId(), backupVO);
                }
            } catch (final Exception cleanupException) {
                LOG.warn("Failed to cleanup incomplete Veeam backup entry [{}]", backupVO.getUuid(), cleanupException);
            }
            throw e;
        }
    }

    private boolean shouldUseIncrementalBackup(final VirtualMachine vm, final Backup latestBackup, final Long backupScheduleId) {
        if (latestBackup == null) {
            LOG.info("Veeam backup for VM [{}] will be FULL: no previous BackedUp backup", vm.getInstanceName());
            return false;
        }
        loadBackupDetailsIfNeeded(latestBackup);

        if (backupScheduleId != null && !hasBackedUpBackupForSchedule(backupScheduleId)) {
            LOG.info("Veeam backup for VM [{}] will be FULL: first backup for schedule [{}]", vm.getInstanceName(), backupScheduleId);
            return false;
        }

        final Long clusterId = getClusterIdFromRootVolume(vm);
        if (clusterId == null) {
            LOG.info("Veeam backup for VM [{}] will be FULL: cluster id unresolved from root volume", vm.getInstanceName());
            return false;
        }
        if (!Boolean.TRUE.equals(KvmIncrementalBackup.valueIn(clusterId))) {
            LOG.info("Veeam backup for VM [{}] will be FULL: kvm.incremental.backup is not enabled on cluster [{}]",
                    vm.getInstanceName(), clusterId);
            return false;
        }

        if (!hasHealthyIncrementalSource(latestBackup)) {
            LOG.info("Veeam backup for VM [{}] will be FULL: parent backup [{}] engine={} checkpoint={} xmlPresent={}",
                    vm.getInstanceName(),
                    latestBackup.getUuid(),
                    getBackupDetail(latestBackup, DETAIL_BACKUP_ENGINE),
                    getBackupDetail(latestBackup, DETAIL_CHECKPOINT_NAME),
                    StringUtils.isNotBlank(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_XML)));
            return false;
        }

        if (getBackupChainSize(vm, latestBackup) >= BackupChainSize.value()) {
            LOG.info("Veeam backup for VM [{}] will be FULL: incremental chain size reached limit [{}]",
                    vm.getInstanceName(), BackupChainSize.value());
            return false;
        }
        LOG.info("Veeam backup for VM [{}] will be INCREMENTAL from parent [{}] engine={}",
                vm.getInstanceName(), latestBackup.getUuid(), getBackupDetail(latestBackup, DETAIL_BACKUP_ENGINE));
        return true;
    }

    private boolean shouldUseIncrementalBackupForVeeam(final VirtualMachine vm, final Backup latestBackup) {
        if (latestBackup == null) {
            return false;
        }
        loadBackupDetailsIfNeeded(latestBackup);

        final Long clusterId = getClusterIdFromRootVolume(vm);
        if (clusterId == null) {
            return false;
        }
        if (!Boolean.TRUE.equals(KvmIncrementalBackup.valueIn(clusterId))) {
            return false;
        }

        if (!hasHealthyIncrementalSource(latestBackup)) {
            return false;
        }

        if (getBackupChainSize(vm, latestBackup) >= BackupChainSize.value()) {
            return false;
        }
        return true;
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

    private boolean shouldRetryAsFullAfterIncrementalFailure(final BackupExecutionResult result, final List<VolumeVO> vmVolumes) {
        if (result == null || result.success) {
            return false;
        }
        if (StringUtils.contains(result.details, MISSING_PARENT_RBD_SNAPSHOT_ERROR)) {
            return true;
        }
        if (StringUtils.contains(result.details, MISSING_PARENT_QCOW2_BITMAP_ERROR)) {
            return true;
        }
        // qcow2 INCREMENTAL refused STOPPED/dummy path — fall back to FULL (dummy or running).
        if (StringUtils.contains(result.details, "INCREMENTAL requires VM")
                && StringUtils.contains(result.details, "to be Running")) {
            return true;
        }
        return vmVolumes.size() > 1;
    }

    private boolean canRetryFailedIncrementalAsFull(final BackupExecutionResult result) {
        return result != null && (result.backup == null || !Backup.Status.Error.equals(result.backup.getStatus()));
    }

    private void cleanupFailedBackupForFullRetry(final Host host, final Backup backup) {
        if (backup == null) {
            return;
        }

        cleanupFailedBackupArtifacts(host, backup);

        LOG.info("Removed failed Veeam backup path [{}] before full retry for backup [{}].",
                backup.getExternalId(), backup.getUuid());
    }

    private boolean cleanupFailedBackupArtifacts(final Host host, final Backup backup) {
        if (backup == null || host == null || StringUtils.isBlank(backup.getExternalId())) {
            return true;
        }
        loadBackupDetailsIfNeeded(backup);

        if (BACKUP_ENGINE_RBD_DIFF.equals(getBackupDetail(backup, DETAIL_BACKUP_ENGINE))
                && StringUtils.isNotBlank(getBackupDetail(backup, DETAIL_CHECKPOINT_NAME))
                && StringUtils.isNotBlank(getBackupDetail(backup, DETAIL_RBD_DISK_PATHS))) {
            final AblestackDeleteBackupCommand command = new AblestackDeleteBackupCommand(backup.getExternalId(), null, null, null, true);
            command.setBackupProvider(getName());
            final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
            command.setVmName(vm != null ? vm.getInstanceName() : null);
            command.setCheckpointName(getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));
            command.setDiskPaths(getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));
            try {
                final BackupAnswer answer = (BackupAnswer) agentManager.send(host.getId(), command);
                if (answer == null || !answer.getResult()) {
                    LOG.warn("Failed to cleanup RBD snapshots for failed Veeam backup [{}] on host [{}]: {}",
                            backup.getUuid(), host.getName(), answer != null ? answer.getDetails() : "no answer received");
                    return false;
                }
            } catch (final AgentUnavailableException | OperationTimedoutException e) {
                LOG.warn("Unable to cleanup RBD snapshots for failed Veeam backup [{}] on host [{}]: {}",
                        backup.getUuid(), host.getName(), e.getMessage(), e);
                return false;
            }
            return true;
        }

        return cleanupBackupPathsOnHost(backup.getZoneId(), host.getName(), List.of(backup.getExternalId()));
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

    private BackupVO createBackupObject(final VirtualMachine vm, final String backupPath, final String backupType, final Map<String, String> details) {
        final BackupVO backup = new BackupVO();
        backup.setVmId(vm.getId());
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
        return backupDao.persist(backup);
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
            details.put(DETAIL_PARENT_CHECKPOINT_NAME, getBackupDetail(latestBackup, DETAIL_CHECKPOINT_NAME));
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

    /**
     * Host-mode first FULL exports under {@code .../&lt;vm&gt;/&lt;ts&gt;/&lt;disk&gt;} and creates a live
     * qcow2 dirty bitmap named {@code &lt;ts&gt;}. Seed import previously stamped a new {@code buildBackupPath()}
     * timestamp as {@code ablestack.veeam.checkpoint.name}, so INCREMENTAL looked for a bitmap that
     * never existed on disk. For QCOW2, reuse the staging parent directory name when it looks like a
     * checkpoint timestamp.
     */
    private String resolveSeedCheckpointName(final String backupPath, final List<String> stagingDiskPaths,
            final String backupEngine) {
        final String fallback = backupPath.substring(backupPath.lastIndexOf('/') + 1);
        if (!BACKUP_ENGINE_QCOW2.equals(backupEngine) || CollectionUtils.isEmpty(stagingDiskPaths)) {
            return fallback;
        }
        final String stagingDisk = StringUtils.trimToNull(stagingDiskPaths.get(0));
        if (stagingDisk == null) {
            return fallback;
        }
        final Path parent = Path.of(stagingDisk).getParent();
        if (parent == null || parent.getFileName() == null) {
            return fallback;
        }
        final String candidate = parent.getFileName().toString();
        if (!isCheckpointTimestampName(candidate)) {
            return fallback;
        }
        if (!StringUtils.equals(candidate, fallback)) {
            LOG.info("Veeam QCOW2 seed import reusing host-export checkpoint name [{}] from staging [{}] "
                            + "(import backupPath basename was [{}])",
                    candidate, stagingDisk, fallback);
        }
        return candidate;
    }

    private boolean isCheckpointTimestampName(final String name) {
        return StringUtils.isNotBlank(name)
                && name.matches("\\d{4}\\.\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{3}");
    }

    private String getBackupStageRootPath() {
        return Path.of(StringUtils.defaultIfBlank(AblestackVeeamStageRootPath.value(), DEFAULT_STAGE_ROOT_PATH))
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private void validateBackupStageCapacity(final Host stageHost, final String stageRootPath, final List<VolumeVO> vmVolumes,
            final String vmName, final String backupType, final String backupEngine) {
        final long requiredBytes = estimateRequiredStageBytesForBackup(vmVolumes);
        final long bufferBytes = Math.max(STAGE_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        final long minimumAvailableBytes = requiredBytes + bufferBytes;
        final long availableBytes = getAvailableBytesOnHostPath(stageHost, stageRootPath);
        LOG.info("{} phase=[STAGE_SPACE_CHECK], vm=[{}], host=[{}], backupType=[{}], backupEngine=[{}], stageRoot=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}]",
                BACKUP_TRACE, vmName, stageHost != null ? stageHost.getName() : null, backupType, backupEngine, stageRootPath,
                requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes);
        if (availableBytes < minimumAvailableBytes) {
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
                .filter(volume -> Volume.State.Ready.equals(volume.getState()))
                .mapToLong(VolumeVO::getSize)
                .sum();
    }

    private long getAvailableBytesOnHostPath(final Host host, final String path) {
        if (host == null || StringUtils.isBlank(path)) {
            throw new CloudRuntimeException("Host and path are required to query available Veeam stage space");
        }
        try {
            final Answer answer = agentManager.send(host.getId(), new AblestackVeeamGetAvailableBytesCommand(path));
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(String.format("Failed to query available stage space on host %s due to: %s",
                        host.getName(), answer != null ? answer.getDetails() : "no answer received"));
            }
            return Long.parseLong(answer.getDetails());
        } catch (NumberFormatException e) {
            throw new CloudRuntimeException(String.format("Failed to parse available stage space on host %s for path %s", host.getName(), path), e);
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException(String.format("Unable to contact host %s to query available stage space", host.getName()), e);
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException(String.format("Timed out querying available stage space on host %s", host.getName()), e);
        } catch (RuntimeException e) {
            throw new CloudRuntimeException(String.format("Failed to query available stage space on host %s due to: %s", host.getName(), e.getMessage()), e);
        }
    }

    private void ensureStageHostHasCapacityForRestore(final Host stageHost, final List<Backup> restoreChain,
            final Set<String> requiredVolumeUuids, final String vmName, final String backupUuid, final String volumeUuid) {
        if (stageHost == null || CollectionUtils.isEmpty(restoreChain)) {
            return;
        }
        final String stageRootPath = getBackupStageRootPath();
        final long requiredBytes = estimateRequiredStageBytesForRestore(restoreChain, requiredVolumeUuids);
        final long bufferBytes = Math.max(STAGE_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        final long minimumAvailableBytes = requiredBytes + bufferBytes;
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

    private Host resolveRestoreHost(final VirtualMachine vm, final Backup backup, final String hostIp) {
        // Staging files live on the backup source host's local disk. Prefer that host even when
        // createVMFromBackup supplies an arbitrary volume-prepare host IP.
        final Host backupSourceHost = resolveBackupSourceHostForRestore(backup);
        if (backupSourceHost != null) {
            LOG.info("Using Veeam backup source/stage host [{}] for restore of VM [{}] (caller hostIp=[{}])",
                    backupSourceHost.getName(), vm.getInstanceName(), hostIp);
            return backupSourceHost;
        }
        if (StringUtils.isNotBlank(hostIp)) {
            return findAvailableKvmRestoreHost(hostIp, "Veeam restore");
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
    public boolean deleteBackup(final Backup backup, final boolean forced) {
        // Same model as Ablestack NetBackup: Mold never deletes rows from the UI/API.
        // Retention lives in Veeam B&R (Remove from Disk / job retention). BackupSync
        // (syncBackups → deleteMoldBackupsMissingFromVeeamCatalog) removes Mold metadata
        // and host artifacts after the restore point disappears from the Veeam catalog.
        throw new CloudRuntimeException(
                "Veeam backups are managed by Veeam Backup & Replication and cannot be deleted individually from Mold. "
                        + "Delete or expire restore points in Veeam (Remove from Disk); "
                        + "Mold backup history is cleaned up by catalog sync.");
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(final VirtualMachine vm, final Backup backup, final String hostIp, final String dataStoreUuid, final boolean quickRestore) {
        return restoreVirtualMachine(vm, backup, hostIp);
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
        return restoreVirtualMachine(vm, backup, null, false).first();
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
        LOG.info("Cleaning up prepared Veeam restore after failed Mold restore validation. vm=[{}], backup=[{}], restoreHost=[{}], restoredPath=[{}]",
                vm != null ? vm.getInstanceName() : null, backup.getUuid(), restoreHostName, backup.getExternalId());
        cleanupBackupPathsOnHost(backup.getZoneId(), restoreHostName, Collections.singletonList(backup.getExternalId()));
    }

    private Pair<Boolean, String> restoreVirtualMachine(final VirtualMachine vm, final Backup backup, final String restoreHostIp) {
        return restoreVirtualMachine(vm, backup, restoreHostIp, false);
    }

    private Pair<Boolean, String> restoreVirtualMachine(final VirtualMachine vm, final Backup backup, final String restoreHostIp,
            final boolean restoreSourcesAlreadyPrepared) {
        loadBackupDetailsIfNeeded(backup);
        validateRestoreChainIntegrity(backup);
        validateVeeamRestoreSnapshotCompatibility(vm);
        final Host host = resolveRestoreHost(vm, backup, restoreHostIp);
        final List<Backup> restoreChain = getRestoreChainForBackup(backup);
        final List<Backup> stagedRestoreChain = getStagedRestoreChainForBackup(backup);
        final boolean incrementalRestore = StringUtils.equalsIgnoreCase(BACKUP_TYPE_INCREMENTAL, backup.getType());
        LOG.info("{} phase=[BEGIN], vm=[{}], backup=[{}], restoreHost=[{}], preparedSourcesAlreadyPrepared=[{}], incrementalRestore=[{}], restoreChain={}",
                RESTORE_TRACE, vm.getInstanceName(), backup.getUuid(), host.getName(), restoreSourcesAlreadyPrepared, incrementalRestore,
                restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
        final List<Backup> restoreSourcesToPrepare = incrementalRestore && !restoreSourcesAlreadyPrepared ? restoreChain : stagedRestoreChain;
        try {
            if (!restoreSourcesAlreadyPrepared || incrementalRestore) {
                ensureStageHostHasCapacityForRestore(host, restoreSourcesToPrepare, null,
                        vm.getInstanceName(), backup.getUuid(), null);
            }
            if (incrementalRestore) {
                if (restoreSourcesAlreadyPrepared) {
                    LOG.info("Skipping Veeam root restore job completion wait for prepared incremental restore. vm=[{}], backup=[{}], restoreHost=[{}], rootPath=[{}]",
                            vm.getInstanceName(), backup.getUuid(), host.getName(), backup.getExternalId());
                    waitForPreparedRestorePathOnDestinationHost(host, backup.getExternalId());
                } else {
                    LOG.info("Mold-initiated incremental restore will request the complete Veeam restore chain. vm=[{}], backup=[{}], "
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
                        "Unable to restore VM [%s] from Veeam [%s] because the backup has [%s] disks but the VM has [%s] disks.",
                        vm.getInstanceName(), backup.getUuid(), backupVolumes.size(), restoreVolumes.size()));
            }

            final AblestackVeeamRestoreBackupCommand restoreCommand = new AblestackVeeamRestoreBackupCommand();
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
            restoreCommand.setTimeout(BackupRestoreTimeout.value());
            restoreCommand.setCheckpointName(getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));

            final BackupAnswer answer;
            try {
                answer = requireBackupAnswer(agentManager.send(host.getId(), restoreCommand), host.getName(), "Veeam restore");
            } catch (final AgentUnavailableException e) {
                throw new CloudRuntimeException("Unable to contact backend control plane to initiate Veeam restore", e);
            } catch (final OperationTimedoutException e) {
                throw new CloudRuntimeException("Operation to restore Veeam backup timed out, please try again", e);
            }
            return new Pair<>(answer.getResult(), answer.getDetails());
        } finally {
            cleanupRestoreSourcesOnStageHosts(vm.getDataCenterId(), host.getName(), restoreSourcesToPrepare);
        }
    }

    private BackupAnswer requireBackupAnswer(final Answer rawAnswer, final String hostName, final String operation) {
        if (rawAnswer == null) {
            throw new CloudRuntimeException(String.format("%s returned no response from host [%s]", operation, hostName));
        }
        if (!(rawAnswer instanceof BackupAnswer)) {
            throw new CloudRuntimeException(String.format(
                    "%s is not supported by agent on host [%s] (got %s: %s). Deploy Ablestack Veeam agent plugins to this host, or restore on the backup source host.",
                    operation, hostName, rawAnswer.getClass().getSimpleName(), rawAnswer.getDetails()));
        }
        return (BackupAnswer) rawAnswer;
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
        loadBackupDetailsIfNeeded(backup);
        validateRestoreChainIntegrity(backup);

        final StoragePoolVO pool = primaryDataStoreDao.findByUuid(dataStoreUuid);
        if (pool == null) {
            throw new CloudRuntimeException(String.format("Unable to find datastore [%s] for Veeam volume restore", dataStoreUuid));
        }

        final HostVO restoreHost = findAvailableKvmRestoreHost(hostIp, "Veeam volume restore");

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
            throw new CloudRuntimeException(String.format("Unable to find VM [%s] for Veeam volume restore", vmNameAndState.first()));
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
        try {
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

            final AblestackVeeamRestoreBackupCommand restoreCommand = new AblestackVeeamRestoreBackupCommand();
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
            restoreCommand.setTimeout(BackupRestoreTimeout.value());
            restoreCommand.setCacheMode(cacheMode);
            restoreCommand.setCheckpointName(getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));

            final BackupAnswer answer;
            try {
                answer = requireBackupAnswer(agentManager.send(restoreHost.getId(), restoreCommand), restoreHost.getName(), "Veeam volume restore");
            } catch (AgentUnavailableException e) {
                throw new CloudRuntimeException("Unable to contact backend control plane to initiate Veeam restore");
            } catch (OperationTimedoutException e) {
                throw new CloudRuntimeException("Operation to restore backed up volume timed out, please try again");
            }

            if (answer.getResult()) {
                try {
                    volumeDao.persist(restoredVolume);
                } catch (Exception e) {
                    throw new CloudRuntimeException("Unable to create restored volume due to: " + e);
                }
                return new Pair<>(true, restoredVolume.getUuid());
            }

            return new Pair<>(false, answer.getDetails());
        } finally {
            cleanupRestoreSourcesOnStageHosts(backup.getZoneId(), restoreHost.getName(), restoreSourcesToPrepare);
        }
    }

    private void waitForPreparedRestorePathOnDestinationHost(final Host destinationHost, final String restorePath) {
        // Local /tmp/mold/veeam paths are already present on the KVM host for standalone restores.
        LOG.debug("Skipping prepared restore path wait for host [{}], path [{}]",
                destinationHost != null ? destinationHost.getName() : null, restorePath);
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
        LOG.debug("Ablestack Veeam does not import out-of-band restore points into Mold; use importAblestackVeeamBackupSeed.");
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
                AblestackVeeamStagingPath,
                AblestackVeeamStageRootPath,
                AblestackVeeamUseRestApi,
                AblestackVeeamRestUrl,
                AblestackVeeamRestApiVersion,
                AblestackVeeamSyncDeleteRbdDiff,
                AblestackVeeamSyncDeleteMissingCatalog
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

    private List<String> getBackupFiles(final List<Backup.VolumeInfo> backedVolumes, final Backup backup) {
        final List<String> backupFiles = new ArrayList<>();
        final List<Backup.VolumeInfo> sortedVolumes = new ArrayList<>(backedVolumes);
        sortedVolumes.sort(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId));
        for (final Backup.VolumeInfo backedVolume : sortedVolumes) {
            if (isLegacyBackup(backup)) {
                backupFiles.add(getLegacyBackupFileName(backedVolume));
            } else {
                backupFiles.add(backedVolume.getPath());
            }
        }
        return backupFiles;
    }

    private BackupRestorePlan createRestorePlan(final boolean attachRequired) {
        // Local /tmp/mold/veeam paths are the durable backup store; never schedule CLEANUP_SOURCE.
        return AblestackBackupFrameworkUtils.createRestorePlan(attachRequired, false);
    }

    private List<String> getBackupFileChains(final List<Backup.VolumeInfo> backupVolumes, final Backup backup) {
        return backupVolumes.stream()
                .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                .map(volume -> getBackupFileChain(volume, backup))
                .collect(Collectors.toList());
    }

    private String getBackupFileChain(final Backup.VolumeInfo backupVolume, final Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        final List<String> chain = getBackupChain(backupVolume, backup);
        return String.join(";", chain);
    }

    private List<BackupVolumeChainState> getVolumeChainStates(final List<Backup.VolumeInfo> backupVolumes, final Backup backup) {
        final String backupEngine = getBackupDetail(backup, DETAIL_BACKUP_ENGINE);
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
        final String engine = getBackupDetail(chainBackup, DETAIL_BACKUP_ENGINE);
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

    private void prepareRestoreSourcesOnStageHosts(final Long zoneId, final String destinationHostName, final List<Backup> restoreChain) {
        prepareRestoreSourcesOnStageHosts(zoneId, destinationHostName, restoreChain, null);
    }

    private void prepareRestoreSourcesOnStageHosts(final Long zoneId, final String destinationHostName, final List<Backup> restoreChain,
            final Set<String> requiredVolumeUuids) {
        // Standalone Veeam keeps backup chains under /tmp/mold/veeam on the KVM host; no external catalog restore is required.
        if (CollectionUtils.isNotEmpty(restoreChain)) {
            LOG.debug("Skipping external Veeam restore-source preparation for host [{}]; using local paths {}",
                    destinationHostName, restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
        }
    }

    private void waitForPreparedRestoreFilesOnDestinationHost(final Host destinationHost, final List<Backup> restoreChain,
            final Set<String> requiredVolumeUuids) {
        // Local host staging; nothing to wait for from an external Veeam recovery job.
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
        // Mold-only Veeam keeps durable QCOW2 chains under /tmp/mold/veeam on the KVM host.
        // prepareRestoreSourcesOnStageHosts is a no-op for these local paths; cleanup must also be a no-op.
        // Deleting parent FULL/INCREMENTAL dirs after restore (success or failure) breaks later incremental restores.
        if (CollectionUtils.isNotEmpty(restoreChain)) {
            LOG.info("Skipping Veeam restore-source cleanup for local mold staging on host [{}]; preserving paths {}",
                    destinationHostName, restoreChain.stream().map(Backup::getExternalId).collect(Collectors.toList()));
        }
    }

    private boolean cleanupBackupPathsOnHost(final Long zoneId, final String hostName, final List<String> backupPaths) {
        if (CollectionUtils.isEmpty(backupPaths) || StringUtils.isBlank(hostName)) {
            return true;
        }
        final HostVO host = findRestoreHost(hostName);
        if (host == null) {
            LOG.warn("Unable to find restore host [{}] while cleaning up Veeam restore paths {}.", hostName, backupPaths);
            return false;
        }
        try {
            final Answer answer = agentManager.send(host.getId(), new AblestackVeeamCleanupCommand(backupPaths, getBackupStageRootPath()));
            if (answer == null || !answer.getResult()) {
                LOG.warn("Veeam restore cleanup command failed on host [{}]: {}",
                        host.getName(), answer != null ? answer.getDetails() : "no answer received");
                return false;
            }
        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to execute Veeam restore cleanup command on host [{}]: {}",
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
        String sourceHost = getBackupDetail(backup, DETAIL_SOURCE_HOST);
        if (StringUtils.isBlank(sourceHost)) {
            sourceHost = getBackupDetail(backup, DETAIL_POLICY_NAME);
        }
        if (StringUtils.isBlank(sourceHost)) {
            final Host originalHost = resolveOriginalBackupVmHost(backup);
            if (originalHost != null) {
                return originalHost.getName();
            }
            LOG.warn("Veeam source/stage host detail is missing for backup [{}]. Falling back to destination host [{}].",
                    backup != null ? backup.getUuid() : null, defaultHostName);
            return defaultHostName;
        }
        return sourceHost;
    }

    private void validateRestoreChainIntegrity(final Backup backup) {
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
        removeStaleBackingUpBackups(vm);
        syncMoldBackupsWithVeeamCatalog(vm);
        removeMoldBackupsForDeletedVeeamJobs(vm);
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
            LOG.warn("Removing stale Veeam backup [{}] for VM [{}] stuck in BackingUp for over one day.",
                    backup.getUuid(), vm.getInstanceName());
            removeBackupWithDetails(backup.getId());
        }
    }

    private void syncMoldBackupsWithVeeamCatalog(final VirtualMachine vm) {
        final List<Backup> moldBackups = backupDao.listByVmId(vm.getDataCenterId(), vm.getId()).stream()
                .filter(this::isVeeamBackup)
                .filter(backup -> Backup.Status.BackedUp.equals(backup.getStatus())
                        || Backup.Status.Failed.equals(backup.getStatus())
                        || Backup.Status.Error.equals(backup.getStatus()))
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
        if (!Boolean.TRUE.equals(AblestackVeeamSyncDeleteMissingCatalog.valueIn(vm.getDataCenterId()))) {
            LOG.debug("Skipping Veeam catalog delete sync for VM [{}]: "
                    + "backup.plugin.ablestack-veeam.sync.delete.missing.catalog=false", vm.getInstanceName());
            return;
        }
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
        if (!Boolean.TRUE.equals(AblestackVeeamSyncDeleteMissingCatalog.valueIn(vm.getDataCenterId()))) {
            LOG.debug("Skipping Veeam job-delete sync for VM [{}]: "
                    + "backup.plugin.ablestack-veeam.sync.delete.missing.catalog=false", vm.getInstanceName());
            return;
        }
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
            // Same as NetBackup: when the external catalog entry is gone, remove Mold RBD_DIFF too.
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

    private boolean restorePointMatchesBackupWindow(final Backup.RestorePoint restorePoint, final Backup backup) {
        return restorePoint != null && restorePoint.getCreated() != null && backup.getDate() != null
                && Math.abs(restorePoint.getCreated().getTime() - backup.getDate().getTime()) <= VEEAM_RP_TIME_MATCH_MS;
    }

    private void deleteMoldBackupsMissingFromVeeamCatalog(final VirtualMachine vm, final List<Backup> moldBackups,
            final VeeamCatalogQueryResult catalog) {
        if (!Boolean.TRUE.equals(AblestackVeeamSyncDeleteMissingCatalog.valueIn(vm.getDataCenterId()))) {
            return;
        }
        final Set<String> catalogIds = catalog.restorePoints.stream()
                .map(restorePoint -> normalizeVeeamRestorePointId(restorePoint.getId()))
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        final long now = System.currentTimeMillis();
        final Set<Long> toRemove = new LinkedHashSet<>();
        final boolean syncDeleteRbd = Boolean.TRUE.equals(
                AblestackVeeamSyncDeleteRbdDiff.valueIn(vm.getDataCenterId()));

        for (final Backup backup : moldBackups) {
            if (backup.getDate() != null && backup.getDate().getTime() > now - VEEAM_SYNC_DELETE_GRACE_MS) {
                continue;
            }
            loadBackupDetailsIfNeeded(backup);
            final boolean rbdDiff = BACKUP_ENGINE_RBD_DIFF.equalsIgnoreCase(
                    getBackupDetail(backup, DETAIL_BACKUP_ENGINE));
            // Stamped RP gone (incl. Remove-from-Disk of that point) → delete Mold row.
            // RBD_DIFF respects backup.plugin.ablestack-veeam.sync.delete.rbd.diff.
            final String restorePointId = normalizeVeeamRestorePointId(getBackupDetail(backup, DETAIL_VEEAM_RESTORE_POINT_ID));
            if (StringUtils.isNotBlank(restorePointId)) {
                if (!catalogIds.contains(restorePointId) && (!rbdDiff || syncDeleteRbd)) {
                    toRemove.add(backup.getId());
                }
                continue;
            }
            // RBD_DIFF without stamp: only clear on trusted-empty Disk (full Remove-from-Disk).
            // Do not drop new Mold RBD rows just because Agent time-match missed a partial catalog.
            if (rbdDiff) {
                if (syncDeleteRbd && catalog.trustedEmptyCatalog() && backup.getDate() != null) {
                    toRemove.add(backup.getId());
                }
                continue;
            }
            final String jobName = StringUtils.trimToNull(getBackupDetail(backup, DETAIL_VEEAM_JOB_NAME));
            if (StringUtils.isNotBlank(jobName) && backup.getDate() != null) {
                // Host Agent: one Veeam RP per job run may cover multiple guest VM Mold rows.
                // If that RP is deleted from Veeam, no time-matched RP remains for the job.
                boolean matchedLiveRp = catalog.restorePoints.stream()
                        .filter(rp -> restorePointMatchesBackupWindow(rp, backup))
                        .anyMatch(rp -> StringUtils.isBlank(rp.getJobName())
                                || normalizeVeeamJobName(jobName).equals(normalizeVeeamJobName(rp.getJobName())));
                // Fallback: Agent RPs are often named by hypervisor IP with blank/odd jobName in catalog.
                if (!matchedLiveRp) {
                    matchedLiveRp = catalog.restorePoints.stream()
                            .anyMatch(rp -> restorePointMatchesBackupWindow(rp, backup));
                }
                // Partial catalog: missing time-matched RP → delete file-level rows.
                // Trusted empty catalog after Remove-from-Disk → delete file-level rows too.
                if (!matchedLiveRp && (CollectionUtils.isNotEmpty(catalog.restorePoints) || catalog.trustedEmptyCatalog())) {
                    toRemove.add(backup.getId());
                }
                continue;
            }
            if (catalog.trustedEmptyCatalog()
                    && backup.getDate() != null) {
                // Failed/Error rows have no live RP to stamp — use short grace after Remove-from-Disk.
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

    @Override
    public Pair<Boolean, Backup> importAblestackVeeamBackupSeed(final VirtualMachine vm, final String veeamRestorePointId,
            final List<String> stagingDiskPaths, final String sourceFormat, final Boolean bootstrapCheckpoint) {
        List<String> staging = stagingDiskPaths;
        if (CollectionUtils.isEmpty(staging)) {
            if (StringUtils.isBlank(veeamRestorePointId)) {
                throw new CloudRuntimeException("Either veeam restore point id or staging disk paths are required");
            }
            final String stagingSubDir = String.format("%s/%s-import", vm.getInstanceName(), System.currentTimeMillis());
            final String stagingPath = String.format("%s/%s", AblestackVeeamStagingPath.valueIn(vm.getDataCenterId()), stagingSubDir);
            staging = getSshClient(vm.getDataCenterId()).exportRestorePointDisksToStaging(veeamRestorePointId, stagingPath);
        }
        return importSeedFromStaging(vm, staging, veeamRestorePointId, sourceFormat, bootstrapCheckpoint);
    }

    private Pair<Boolean, Backup> importSeedFromStaging(final VirtualMachine vm, final List<String> stagingDiskPaths,
            final String veeamRestorePointId, final String sourceFormat, final Boolean bootstrapCheckpoint) {
        if (CollectionUtils.isEmpty(stagingDiskPaths)) {
            throw new CloudRuntimeException("Staging disk paths are required to import a Veeam backup seed");
        }
        final Host host = getVMHypervisorHostForBackup(vm);
        validateVmSnapshotCoexistenceForBackup(vm);
        final List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        final Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        validateVolumePoolTypes(volumePoolsAndPaths.first());

        final String backupPath = buildBackupPath(vm);
        final String backupEngine = areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2;
        // QCOW2 host-export → import-seed: reuse staging dir timestamp as checkpoint so the next
        // INCREMENTAL parent matches the live dirty bitmap created during host export.
        // (A fresh buildBackupPath() timestamp would leave Mold pointing at a non-existent bitmap.)
        final String checkpointName = resolveSeedCheckpointName(backupPath, stagingDiskPaths, backupEngine);
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, false);
        final Map<String, String> details = getBackupDetails(vm, backupPath, checkpointName, backupEngine, null, false, null);
        details.put(DETAIL_VEEAM_IMPORTED, "true");
        if (StringUtils.isNotBlank(veeamRestorePointId)) {
            details.put(DETAIL_VEEAM_RESTORE_POINT_ID, veeamRestorePointId);
        }
        details.put(DETAIL_VEEAM_VM_NAME, vm.getInstanceName());
        if (host != null && StringUtils.isNotBlank(host.getName())) {
            details.put(DETAIL_SOURCE_HOST, host.getName());
        }

        final BackupVO backupVO = createBackupObject(vm, backupPath, BACKUP_TYPE_FULL, details);
        final AblestackVeeamImportSeedCommand command = new AblestackVeeamImportSeedCommand(vm.getInstanceName(), backupPath);
        command.setCheckpointName(checkpointName);
        command.setBackupFiles(backupFiles);
        command.setVolumePools(volumePoolsAndPaths.first());
        command.setVolumePaths(volumePoolsAndPaths.second());
        command.setStagingDiskPaths(stagingDiskPaths);
        command.setSourceFormat(StringUtils.defaultIfBlank(sourceFormat, "vmdk"));
        command.setVeeamRestorePointId(veeamRestorePointId);
        command.setBootstrapCheckpoint(bootstrapCheckpoint == null || bootstrapCheckpoint);
        final int commandTimeout = BackupCommandTimeout.value();
        if (commandTimeout > 0) {
            command.setWait(commandTimeout);
        }
        try {
            final BackupAnswer answer = (BackupAnswer) agentManager.send(host.getId(), command);
            if (answer == null || !answer.getResult()) {
                removeBackupWithDetails(backupVO.getId());
                return new Pair<>(false, null);
            }
            backupVO.setDate(new Date());
            backupVO.setStatus(Backup.Status.BackedUp);
            if (answer.getSize() != null) {
                backupVO.setSize(answer.getSize());
            }
            // RBD/QCOW2 restore chains resolve per-disk file names from backed_volumes.
            // Without this, incremental restore skips the FULL base (.raw) and fails.
            backupVO.setBackedUpVolumes(createVolumeInfoFromVolumes(vmVolumes, backupFiles));
            // QCOW2 INCREMENTAL requires checkpoint XML on the parent FULL (hasHealthyIncrementalSource).
            // Seed import bootstraps the libvirt checkpoint on the host — persist that XML here or the
            // next createAblestackVeeamBackup always falls back to FULL.
            if (BACKUP_ENGINE_QCOW2.equals(backupEngine)) {
                final String checkpointXml = readFileContentsOnHost(host.getId(),
                        getCheckpointPath(backupPath, checkpointName, backupEngine));
                if (StringUtils.isNotBlank(checkpointXml)) {
                    final String checkpointXmlToStore = removeParentFromCheckpointXml(checkpointXml);
                    details.put(DETAIL_CHECKPOINT_XML, checkpointXmlToStore);
                    backupDetailsDao.removeDetail(backupVO.getId(), DETAIL_CHECKPOINT_XML);
                    backupDetailsDao.addDetail(backupVO.getId(), DETAIL_CHECKPOINT_XML, checkpointXmlToStore, false);
                } else {
                    LOG.warn("Veeam seed import for VM [{}] completed without checkpoint XML at [{}]; "
                                    + "next backup will stay FULL until a healthy QCOW2 parent exists.",
                            vm.getInstanceName(), getCheckpointPath(backupPath, checkpointName, backupEngine));
                }
            }
            backupVO.setDetails(details);
            backupDao.update(backupVO.getId(), backupVO);
            return new Pair<>(true, backupVO);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            removeBackupWithDetails(backupVO.getId());
            throw new CloudRuntimeException("Failed to import Veeam backup seed: " + e.getMessage(), e);
        }
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
                .filter(this::isVeeamBackup)
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
                    backup.getUuid(), getBackupDetail(backup, DETAIL_CHECKPOINT_NAME));
            return;
        }

        final String checkpointName = getBackupDetail(backup, DETAIL_CHECKPOINT_NAME);
        if (StringUtils.isBlank(checkpointName) || StringUtils.isBlank(backup.getExternalId())) {
            return;
        }

        final Host cleanupHost = resolveBackupCleanupHost(backup);
        if (cleanupHost == null) {
            LOG.warn("Skipping Veeam artifact cleanup for backup [{}] because no available KVM cleanup host was found.", backup.getUuid());
            return;
        }

        final AblestackDeleteBackupCommand command = new AblestackDeleteBackupCommand(backup.getExternalId(), null, null, null, true);
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
                .filter(this::isVeeamBackup)
                .forEach(candidate -> {
                    backupDao.loadDetails(candidate);
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
