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
import com.cloud.dc.dao.ClusterDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.offering.DiskOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.User;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.VMSnapshotDetailsVO;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.backup.commvault.AblestackCommvaultClient;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDaoImpl;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.xml.utils.URI;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

import static org.apache.cloudstack.backup.BackupManager.BackupChainSize;
import static org.apache.cloudstack.backup.BackupManager.BackupCommandTimeout;
import static org.apache.cloudstack.backup.BackupManager.BackupQosBandwidthLimitMbps;
import static org.apache.cloudstack.backup.BackupManager.BackupRestoreTimeout;
import static org.apache.cloudstack.backup.BackupManager.KvmIncrementalBackup;

public class AblestackCommvaultBackupProvider extends AdapterBase implements BackupProvider, Configurable {

    private static final Logger LOG = LogManager.getLogger(AblestackCommvaultBackupProvider.class);
    private static final String BACKUP_TYPE_FULL = "FULL";
    private static final String BACKUP_TYPE_INCREMENTAL = "INCREMENTAL";
    private static final String BACKUP_ENGINE_QCOW2 = "QCOW2";
    private static final String BACKUP_ENGINE_RBD_DIFF = "RBD_DIFF";
    private static final long COMMVAULT_INSTALL_JOB_POLL_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long COMMVAULT_INSTALL_JOB_WAIT_TIMEOUT_MS = TimeUnit.HOURS.toMillis(12);
    private static final String DETAIL_CHECKPOINT_NAME = "commvault.checkpoint.name";
    private static final String DETAIL_CHECKPOINT_PATH = "commvault.checkpoint.path";
    private static final String DETAIL_CHECKPOINT_XML = "commvault.checkpoint.xml";
    private static final String DETAIL_PARENT_BACKUP_UUID = "commvault.parent.backup.uuid";
    private static final String DETAIL_PARENT_BACKUP_PATH = "commvault.parent.backup.path";
    private static final String DETAIL_PARENT_CHECKPOINT_NAME = "commvault.parent.checkpoint.name";
    private static final String DETAIL_PARENT_CHECKPOINT_PATH = "commvault.parent.checkpoint.path";
    private static final String DETAIL_BACKUP_ENGINE = "commvault.backup.engine";
    private static final String DETAIL_RBD_DISK_PATHS = "commvault.rbd.disk.paths";
    private static final String MISSING_PARENT_RBD_SNAPSHOT_ERROR = "Parent RBD snapshot";
    private static final String MISSING_PARENT_QCOW2_BITMAP_ERROR = "Parent qcow2 bitmap";
    private static final String BACKUP_TRACE = "[ABLESTACK_COMMVAULT_BACKUP_TRACE]";
    private static final String DETAIL_STAGE_HOST = "commvault.stage.host";
    private static final String DETAIL_CHAIN_SEALED = "commvault.chain.sealed";
    private static final String DETAIL_CHAIN_SEAL_REASON = "commvault.chain.seal.reason";
    private static final String DETAIL_FALLBACK_VOLUME_UUIDS = "commvault.fallback.volume.uuids";
    private static final String DETAIL_ERROR_REASON = "commvault.error.reason";
    private static final String DETAIL_FAILURE_PHASE = "commvault.failure.phase";
    private static final String DETAIL_FAILURE_REASON = "commvault.failure.reason";
    private static final String ERROR_REASON_METADATA_FINALIZE = "metadata-finalize";
    private static final String COMMVAULT_PERMANENT_INSTALL_FAILURE_MESSAGE = "Commvault backup agent automatic installation cannot continue because required install media is missing in the Commvault Software Cache.";
    private static final int BASE_MAJOR = 11;
    private static final int BASE_FR = 32;
    private static final int BASE_MT = 89;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\s*SP\\s*(\\d+)(?:\\.(\\d+))?$", Pattern.CASE_INSENSITIVE);
    private static final long STAGE_SPACE_BUFFER_BYTES = 10L * 1024L * 1024L * 1024L;
    private static final int INCREMENTAL_BACKUP_CAPACITY_ESTIMATE_PERCENT = 10;
    private static final long BACKING_UP_SYNC_GRACE_PERIOD_MS = 24L * 60L * 60L * 1000L;

    public ConfigKey<String> CommvaultUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.commvault.url", "https://localhost/commandcenter/api",
            "Commvault Command Center API URL.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> CommvaultUsername = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.commvault.username", "admin",
            "Commvault Command Center API username.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> CommvaultPassword = new ConfigKey<>("Secure", String.class,
            "backup.plugin.commvault.password", "password",
            "Commvault Command Center API password.", true, ConfigKey.Scope.Zone);

    private ConfigKey<Boolean> CommvaultValidateSSLSecurity = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.commvault.validate.ssl", "false",
            "Validate the SSL certificate when connecting to Commvault Command Center API service.", true, ConfigKey.Scope.Zone);

    private ConfigKey<Integer> CommvaultApiRequestTimeout = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.commvault.request.timeout", "300",
            "Commvault Command Center API request timeout in seconds.", true, ConfigKey.Scope.Zone);

    private ConfigKey<String> CommvaultStageRootPath = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.commvault.stage.root.path", "/tmp/mold/backup",
            "Local KVM host directory used for ABLESTACK Commvault backup staging.", true, ConfigKey.Scope.Global);

    @Inject
    private BackupDao backupDao;

    @Inject
    private BackupDetailsDao backupDetailsDao;

    @Inject
    private BackupOfferingDao backupOfferingDao;

    @Inject
    private HostDao hostDao;

    @Inject
    private ClusterDao clusterDao;

    @Inject
    private VolumeDao volumeDao;

    @Inject
    private SnapshotDao snapshotDao;

    @Inject
    private SnapshotDataStoreDao snapshotStoreDao;

    @Inject
    private StoragePoolHostDao storagePoolHostDao;

    @Inject
    private VMInstanceDao vmInstanceDao;

    @Inject
    private AccountService accountService;

    @Inject
    DataStoreManager dataStoreMgr;

    @Inject
    private AgentManager agentManager;

    @Inject
    private VMSnapshotDao vmSnapshotDao;

    @Inject
    private VMSnapshotDetailsDao vmSnapshotDetailsDao;

    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;

    @Inject
    private ConfigurationDao configDao;

    @Inject
    private BackupManager backupManager;

    @Inject
    ResourceManager resourceManager;

    @Inject
    private DiskOfferingDao diskOfferingDao;

    @Inject
    private EventDao eventDao;


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
    public Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM) {
        return takeBackup(vm, quiesceVM, null);
    }

    @Override
    public Pair<Boolean, Backup> takeBackup(VirtualMachine vm, Boolean quiesceVM, boolean isolated, Long backupScheduleId) {
        final Host vmHost = getVMHypervisorHostForBackup(vm);
        final HostVO vmHostVO = hostDao.findById(vmHost.getId());
        validateNoKvmFileBasedVmSnapshots(vm);

        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        // 백업 중인 작업 조회
        final AblestackCommvaultClient client = getClient(vm.getDataCenterId());
        boolean activeJob = client.getActiveJob(vm.getInstanceName());
        if (activeJob) {
            throw new CloudRuntimeException("There are backup jobs running on the virtual machine. Please try again later.");
        }

        BackupOfferingVO vmBackupOffering = new BackupOfferingDaoImpl().findById(vm.getBackupOfferingId());
        String planId = vmBackupOffering.getExternalId();

        // 클라이언트의 백업세트 조회하여 호스트 정의
        String checkVm = client.getVmBackupSetId(vmHost.getName(), vm.getInstanceName());
        if (checkVm == null) {
            String clientId = client.getClientId(vmHost.getName());
            String applicationId = client.getApplicationId(clientId);
            boolean result = client.createBackupSet(vm.getInstanceName(), applicationId, clientId, planId);
            if (!result) {
                throw new CloudRuntimeException("Execution of the API that creates a backup set of a virtual machine on the host failed.");
            }
        }

        final String backupPath = buildBackupPath(vm);
        final String backupContentPath = buildBackupContentPath(vm);
        List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
        validateVolumePoolTypes(volumePoolsAndPaths.first());
        final Backup latestBackup = getLatestBackedUpBackup(vm, backupScheduleId);
        final boolean incrementalBackup = shouldUseIncrementalBackup(vm, latestBackup, vmHost, vmVolumes, backupScheduleId);
        BackupExecutionResult result = executeBackup(vm, quiesceVM, vmHost, vmHostVO, client, planId, backupPath, backupContentPath, vmVolumes, volumePoolsAndPaths,
                latestBackup, incrementalBackup, incrementalBackup && vmVolumes.size() > 1);
        if (!result.success && incrementalBackup && shouldRetryAsFullAfterIncrementalFailure(result, vmVolumes)) {
            cleanupFailedBackupForFullRetry(result.backup);
            LOG.warn("{} phase=[INCREMENTAL_FALLBACK_TO_FULL], vmId=[{}], vmName=[{}], failedBackupUuid=[{}], reason=[{}]",
                    BACKUP_TRACE, vm.getId(), vm.getInstanceName(), result.backup != null ? result.backup.getUuid() : null, result.details);
            LOG.warn("Incremental backup failed for VM [{}] due to [{}]. Retrying as full backup.", vm, result.details);
            String fallbackBackupPath = buildBackupPath(vm);
            String fallbackBackupContentPath = buildBackupContentPath(vm);
            result = executeBackup(vm, quiesceVM, vmHost, vmHostVO, client, planId, fallbackBackupPath, fallbackBackupContentPath, vmVolumes, volumePoolsAndPaths,
                    null, false, false);
        }
        return new Pair<>(result.success, result.backup);
    }

    private Backup getLatestBackedUpBackup(VirtualMachine vm, Long backupScheduleId) {
        List<Backup> backups = backupDao.listByVmId(null, vm.getId());
        return backups.stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(b -> Backup.Status.BackedUp.equals(b.getStatus()))
                .filter(backup -> Objects.equals(backup.getBackupScheduleId(), backupScheduleId))
                .peek(backupDao::loadDetails)
                .max(Comparator.comparing(BackupVO::getDate))
                .orElse(null);
    }

    private boolean shouldUseIncrementalBackup(VirtualMachine vm, Backup latestBackup, Host vmHost, List<VolumeVO> vmVolumes, Long backupScheduleId) {
        if (latestBackup == null) {
            return false;
        }
        loadBackupDetailsIfNeeded(latestBackup);

        Long clusterId = getClusterIdFromRootVolume(vm);
        if (clusterId == null) {
            return false;
        }

        if (!Boolean.TRUE.equals(KvmIncrementalBackup.valueIn(clusterId))) {
            return false;
        }

        if (!hasHealthyIncrementalSource(latestBackup)) {
            markVolumeFallbackAndSeal(latestBackup, "unhealthy-chain");
            return false;
        }
        if (!canContinueIncrementalChain(vm, latestBackup, vmHost)) {
            sealBackupChain(latestBackup, "stage-host-mismatch");
            return false;
        }
        if (getBackupChainSize(vm, latestBackup) >= BackupChainSize.value()) {
            sealBackupChain(latestBackup, "chain-size-limit");
            return false;
        }
        return true;
    }

    private boolean canContinueIncrementalChain(VirtualMachine vm, Backup latestBackup, Host vmHost) {
        final String backupEngine = getBackupDetail(latestBackup, DETAIL_BACKUP_ENGINE);
        if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
            LOG.debug("Allowing Commvault incremental backup for VM [{}] on host [{}] using RBD chain from previous stage host [{}]",
                    vm.getInstanceName(), vmHost.getName(), getBackupDetail(latestBackup, DETAIL_STAGE_HOST));
            return true;
        }

        return hasMatchingQcow2StageHostChain(vm, latestBackup, vmHost);
    }

    private boolean hasMatchingQcow2StageHostChain(VirtualMachine vm, Backup latestBackup, Host vmHost) {
        final String currentHostName = vmHost.getName();
        Backup current = latestBackup;
        while (current != null) {
            loadBackupDetailsIfNeeded(current);
            final String stageHost = getBackupDetail(current, DETAIL_STAGE_HOST);
            if (!Objects.equals(stageHost, currentHostName)) {
                LOG.debug("Commvault QCOW2 incremental backup for VM [{}] cannot continue on host [{}] because backup [{}] belongs to stage host [{}]",
                        vm.getInstanceName(), currentHostName, current.getUuid(), stageHost);
                return false;
            }
            final String checkpointXml = getBackupDetail(current, DETAIL_CHECKPOINT_XML);
            if (StringUtils.isBlank(checkpointXml)) {
                LOG.debug("Commvault QCOW2 incremental backup for VM [{}] cannot continue because backup [{}] is missing [{}] detail",
                        vm.getInstanceName(), current.getUuid(), DETAIL_CHECKPOINT_XML);
                return false;
            }
            final String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (StringUtils.isBlank(parentBackupUuid)) {
                break;
            }
            current = backupDao.findByUuid(parentBackupUuid);
        }
        return true;
    }

    private int getBackupChainSize(VirtualMachine vm, Backup latestBackup) {
        List<BackupVO> backups = backupDao.listByVmId(null, vm.getId()).stream()
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
            LOG.warn("Latest Commvault backup chain [{}] is not healthy enough for incremental reuse: {}", latestBackup.getUuid(), e.getMessage());
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
            List<String> chainFiles = AblestackBackupFrameworkUtils.sanitizeChainFiles(getBackupChain(volumeInfo, backup));
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

    private void updateBackupDetail(Backup backup, String key, String value) {
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
        LOG.warn("Recorded Commvault backup failure context [backupId: {}, backupUuid: {}, phase: {}, reason: {}]",
                backup.getId(), backup.getUuid(), phase, safeReason);
    }

    private void removeBackupWithDetails(long backupId) {
        backupDetailsDao.removeDetails(backupId);
        backupDao.remove(backupId);
    }

    @Override
    public boolean supportsProviderManagedBackupAgents() {
        return true;
    }

    @Override
    public boolean supportsRetentionPlanUpdate() {
        return true;
    }

    private boolean hasDependentBackups(Backup backup) {
        List<Backup> backups = backupDao.listByVmId(null, backup.getVmId());
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
        backupDao.listByVmId(null, backup.getVmId()).stream()
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

    private BackupVO createBackupObject(VirtualMachine vm, String backupPath, String backupType, Map<String, String> details) {
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
        backup.setDetails(details);

        return backupDao.persist(backup);
    }

    private Map<String, String> getBackupDetails(VirtualMachine vm, String backupPath, String checkpointName, String backupEngine, Backup latestBackup,
                                                 boolean incrementalBackup, String stageHost) {
        Map<String, String> details = backupManager.getBackupDetailsFromVM(vm);
        details.put(DETAIL_BACKUP_ENGINE, backupEngine);
        details.put(DETAIL_STAGE_HOST, stageHost);
        details.put(DETAIL_CHECKPOINT_NAME, checkpointName);
        details.put(DETAIL_CHECKPOINT_PATH, getCheckpointPath(backupPath, checkpointName, backupEngine));
        if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
            details.put(DETAIL_RBD_DISK_PATHS, String.join(",", getVolumePoolsAndPaths(volumeDao.findByInstance(vm.getId())).second()));
        }
        if (!incrementalBackup) {
            return details;
        }

        details.put(DETAIL_PARENT_BACKUP_UUID, latestBackup.getUuid());
        details.put(DETAIL_PARENT_BACKUP_PATH, latestBackup.getExternalId().substring(0, latestBackup.getExternalId().lastIndexOf(',')));
        details.put(DETAIL_PARENT_CHECKPOINT_NAME, getBackupDetail(latestBackup, DETAIL_CHECKPOINT_NAME));
        details.put(DETAIL_PARENT_CHECKPOINT_PATH, getBackupDetail(latestBackup, DETAIL_CHECKPOINT_PATH));
        return details;
    }

    private String getBackupPathFromExternalId(final Backup backup) {
        return backup == null ? null : parseExternalId(backup.getExternalId()).first();
    }

    private String getCheckpointPath(String backupPath, String checkpointName, String backupEngine) {
        if (BACKUP_ENGINE_RBD_DIFF.equals(backupEngine)) {
            return String.format("%s/checkpoints/%s.meta", backupPath, checkpointName);
        }
        return String.format("%s/checkpoints/%s.xml", backupPath, checkpointName);
    }

    private String getBackupDetail(Backup backup, String key) {
        return backup == null ? null : backup.getDetail(key);
    }

    private String getBackupDetail(Backup backup, String key, String defaultValue) {
        String value = getBackupDetail(backup, key);
        return value == null ? defaultValue : value;
    }

    private Pair<String, String> parseExternalId(String externalId) {
        if (StringUtils.isBlank(externalId)) {
            throw new CloudRuntimeException("Backup externalId is empty");
        }

        final int separatorIndex = externalId.lastIndexOf(',');
        if (separatorIndex < 0) {
            throw new CloudRuntimeException(String.format("Invalid Commvault backup externalId format: [%s]", externalId));
        }

        final String path = externalId.substring(0, separatorIndex);
        final String jobId = externalId.substring(separatorIndex + 1).trim();
        if (StringUtils.isAnyBlank(path, jobId)) {
            throw new CloudRuntimeException(String.format("Invalid Commvault backup externalId format: [%s]", externalId));
        }
        return new Pair<>(path, jobId);
    }

    private BackupExecutionResult executeBackup(VirtualMachine vm, Boolean quiesceVM, Host vmHost, HostVO vmHostVO, AblestackCommvaultClient client,
                                                String planId, String backupPath, String backupContentPath, List<VolumeVO> vmVolumes,
                                                Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths, Backup latestBackup,
                                                boolean incrementalBackup, boolean retryAsFullOnFailure) {
        final String checkpointName = backupPath.substring(backupPath.lastIndexOf("/") + 1);
        final String backupEngine = areAllVolumesOnRbdPool(volumePoolsAndPaths.first()) ? BACKUP_ENGINE_RBD_DIFF : BACKUP_ENGINE_QCOW2;
        final String requestedBackupType = incrementalBackup ? BACKUP_TYPE_INCREMENTAL : BACKUP_TYPE_FULL;
        validateBackupStageCapacity(vmHostVO, getBackupStageRootPath(), vmVolumes, vm.getInstanceName(), requestedBackupType, backupEngine);
        final List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
        final Map<String, String> backupDetails = getBackupDetails(vm, backupPath, checkpointName, backupEngine, latestBackup,
                BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(requestedBackupType), vmHost.getName());

        BackupVO backupVO = createBackupObject(vm, backupPath, requestedBackupType, backupDetails);
        AblestackCommvaultTakeBackupCommand command = new AblestackCommvaultTakeBackupCommand(vm.getInstanceName(), backupPath);
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
        command.setBandwidthLimitMbps(BackupQosBandwidthLimitMbps.value());
        if (incrementalBackup && latestBackup != null) {
            command.setParentBackupPath(getBackupPathFromExternalId(latestBackup));
            command.setParentCheckpointName(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_NAME));
            command.setParentCheckpointPath(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_PATH));
            command.setParentCheckpointXml(getBackupDetail(latestBackup, DETAIL_CHECKPOINT_XML));
            command.setParentCheckpointXmlChain(getParentCheckpointXmlChain(latestBackup));
        }

        final long backupStartTime = System.currentTimeMillis();
        LOG.info("{} phase=[START], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], parentBackupUuid=[{}], hostId=[{}], hostName=[{}], backupPath=[{}], timeoutSeconds=[{}]",
                BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                latestBackup != null ? latestBackup.getUuid() : null, vmHost.getId(), vmHost.getName(), backupPath, command.getWait());
        LOG.info("Submitting Commvault backup staging command for VM [{}] on host [{}] with backup [{}], path [{}], state [{}], timeout [{}] seconds, volumes [{}]",
                vm.getInstanceName(), vmHost.getName(), backupVO.getUuid(), backupPath, vm.getState(), command.getWait(), vmVolumes.size());
        try {
            BackupAnswer answer;
            try {
                answer = (BackupAnswer) agentManager.send(vmHost.getId(), command);
            } catch (AgentUnavailableException e) {
                LOG.error("{} phase=[FAILED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], backupPath=[{}], elapsedMs=[{}], reason=[{}]",
                        BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                        backupPath, System.currentTimeMillis() - backupStartTime, "Unable to contact backend control plane to initiate backup");
                LOG.error("Unable to contact backend control plane to initiate backup for VM {}", vm.getInstanceName());
                markBackupFailure(backupVO, "agent-send", "Unable to contact backend control plane to initiate backup");
                backupVO.setStatus(Backup.Status.Failed);
                removeBackupWithDetails(backupVO.getId());
                throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
            } catch (OperationTimedoutException e) {
                LOG.error("{} phase=[FAILED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], backupPath=[{}], elapsedMs=[{}], reason=[{}]",
                        BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                        backupPath, System.currentTimeMillis() - backupStartTime, "Operation to initiate backup timed out");
                LOG.error("Operation to initiate backup timed out for VM {}", vm.getInstanceName());
                markBackupFailure(backupVO, "agent-send-timeout", "Operation to initiate backup timed out");
                backupVO.setStatus(Backup.Status.Failed);
                removeBackupWithDetails(backupVO.getId());
                throw new CloudRuntimeException("Operation to initiate backup timed out, please try again");
            }

            if (answer != null && answer.getResult()) {
                LOG.info("Commvault backup staging command completed for VM [{}], backup [{}], path [{}]",
                    vm.getInstanceName(), backupVO.getUuid(), backupPath);
                if (BACKUP_ENGINE_QCOW2.equals(backupEngine)) {
                    String checkpointXml = readFileContentsOnHost(vmHostVO,
                            getCheckpointPath(backupPath, checkpointName, backupEngine));
                    if (StringUtils.isNotBlank(checkpointXml)) {
                        backupDetails.put(DETAIL_CHECKPOINT_XML, checkpointXml);
                    }
                }
                String clientId = client.getClientId(vmHost.getName());
                String subClientEntity = client.getSubclient(clientId, vm.getInstanceName());
                if (subClientEntity == null) {
                    LOG.error("Failed to take backup for VM {} to get subclient info commvault api", vm.getInstanceName());
                    markBackupFailure(backupVO, "commvault-subclient", "Failed to get Commvault subclient information");
                } else {
                    JSONObject jsonObject = new JSONObject(subClientEntity);
                    String subclientId = String.valueOf(jsonObject.get("subclientId"));
                    String applicationId = String.valueOf(jsonObject.get("applicationId"));
                    String backupsetId = String.valueOf(jsonObject.get("backupsetId"));
                    String instanceId = String.valueOf(jsonObject.get("instanceId"));
                    String backupsetName = String.valueOf(jsonObject.get("backupsetName"));
                    String displayName = String.valueOf(jsonObject.get("displayName"));
                    String commCellName = String.valueOf(jsonObject.get("commCellName"));
                    String companyId = String.valueOf(jsonObject.getJSONObject("entityInfo").get("companyId"));
                    String companyName = String.valueOf(jsonObject.getJSONObject("entityInfo").get("companyName"));
                    String instanceName = String.valueOf(jsonObject.get("instanceName"));
                    String appName = String.valueOf(jsonObject.get("appName"));
                    String clientName = String.valueOf(jsonObject.get("clientName"));
                    String subclientGUID = String.valueOf(jsonObject.get("subclientGUID"));
                    String subclientName = String.valueOf(jsonObject.get("subclientName"));
                    String csGUID = String.valueOf(jsonObject.get("csGUID"));
                    boolean upResult = client.updateBackupSet(backupContentPath, subclientId, clientId, planId, applicationId, backupsetId, instanceId, subclientName, backupsetName);
                    if (upResult) {
                        String planName = client.getPlanName(planId);
                        String storagePolicyId = client.getStoragePolicyId(planName);
                        if (planName == null || storagePolicyId == null) {
                            LOG.error("Failed to take backup for VM {} to get storage policy id commvault api", vm.getInstanceName());
                            markBackupFailure(backupVO, "commvault-storage-policy", "Failed to get Commvault storage policy information");
                        } else {
                            String jobId = client.createBackup(subclientId, storagePolicyId, displayName, commCellName, clientId, companyId, companyName, instanceName, appName,
                                    applicationId, clientName, backupsetId, instanceId, subclientGUID, subclientName, csGUID, backupsetName, requestedBackupType);
                            if (jobId != null) {
                                String externalId = backupPath + "," + jobId;
                                backupVO.setExternalId(externalId);
                                backupDao.update(backupVO.getId(), backupVO);
                                String jobStatus = client.getJobStatus(jobId);
                                if (jobStatus.equalsIgnoreCase("Completed")) {
                                    String jobDetails = client.getJobDetails(jobId);
                                    if (jobDetails == null) {
                                        LOG.error("Commvault job [{}] completed for VM [{}], but job details could not be fetched. Leaving backup [{}] in Error state.",
                                                jobId, vm.getInstanceName(), backupVO.getUuid());
                                        return failCompletedCommvaultBackupMetadata(backupVO, externalId,
                                                "Failed to get completed Commvault job details");
                                    }
                                    try {
                                        updateBackupAsCompleted(backupVO, externalId, jobDetails, backupDetails,
                                                createVolumeInfoFromVolumes(vmVolumes, backupFiles));
                                        if (backupDao.update(backupVO.getId(), backupVO)) {
                                            LOG.info("{} phase=[DONE], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], backupPath=[{}], externalId=[{}], elapsedMs=[{}]",
                                                    BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                                                    backupPath, externalId, System.currentTimeMillis() - backupStartTime);
                                            cleanupBackupPathsAfterSuccessfulBackup(vmHostVO, Collections.singletonList(backupPath), backupVO);
                                            return BackupExecutionResult.success(backupVO);
                                        }
                                        LOG.error("Commvault job [{}] completed for VM [{}], but backup [{}] metadata update failed. Leaving it in Error state.",
                                                jobId, vm.getInstanceName(), backupVO.getUuid());
                                        return failCompletedCommvaultBackupMetadata(backupVO, externalId,
                                                "Failed to update completed Commvault backup metadata");
                                    } catch (RuntimeException e) {
                                        LOG.error("Commvault job [{}] completed for VM [{}], but backup [{}] metadata could not be finalized. Leaving it in Error state.",
                                                jobId, vm.getInstanceName(), backupVO.getUuid(), e);
                                        return failCompletedCommvaultBackupMetadata(backupVO, externalId,
                                                "Failed to finalize completed Commvault backup metadata");
                                    }
                                } else {
                                    LOG.error("Failed to take backup for VM {} to create backup job status is {}", vm.getInstanceName(), jobStatus);
                                    markBackupFailure(backupVO, "commvault-job", "Commvault backup job status is " + jobStatus);
                                }
                            } else {
                                LOG.error("Failed to take backup for VM {} to create backup job commvault api", vm.getInstanceName());
                                markBackupFailure(backupVO, "commvault-create-job", "Failed to create Commvault backup job");
                            }
                        }
                    } else {
                        LOG.error("Failed to take backup for VM {} to update backupset content path commvault api", vm.getInstanceName());
                        markBackupFailure(backupVO, "commvault-update-backupset", "Failed to update Commvault backupset content path");
                    }
                }
                markBackupFailure(backupVO, "commvault-job", "Failed to complete Commvault backup job");
                LOG.error("{} phase=[FAILED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], backupPath=[{}], elapsedMs=[{}], reason=[{}]",
                        BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                        backupPath, System.currentTimeMillis() - backupStartTime, "Failed to complete Commvault backup job");
                backupVO.setStatus(Backup.Status.Failed);
                removeBackupWithDetails(backupVO.getId());
                cleanupBackupPathsOnHost(vmHostVO, Collections.singletonList(backupPath));
                return BackupExecutionResult.failure("Failed to complete Commvault backup job", backupVO);
            }

            final String details = answer != null ? answer.getDetails() : "No answer received";
            LOG.error("{} phase=[FAILED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], backupPath=[{}], elapsedMs=[{}], reason=[{}]",
                    BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                    backupPath, System.currentTimeMillis() - backupStartTime, details);
            LOG.error("Failed to take backup for VM {}: {}", vm.getInstanceName(), details);
            markBackupFailure(backupVO, "agent-answer", details);
            if (retryAsFullOnFailure) {
                backupVO.setStatus(Backup.Status.Failed);
                removeBackupWithDetails(backupVO.getId());
            } else if (answer != null && answer.getNeedsCleanup()) {
                LOG.error("Backup cleanup failed for VM {}. Leaving the backup in Error state.", vm.getInstanceName());
                backupVO.setStatus(Backup.Status.Error);
                backupDao.update(backupVO.getId(), backupVO);
            } else {
                backupVO.setStatus(Backup.Status.Failed);
                removeBackupWithDetails(backupVO.getId());
            }
            return BackupExecutionResult.failure(details, backupVO);
        } catch (RuntimeException e) {
            LOG.error("{} phase=[FAILED], backupId=[{}], backupUuid=[{}], vmId=[{}], vmName=[{}], backupType=[{}], backupEngine=[{}], backupPath=[{}], elapsedMs=[{}], reason=[{}]",
                    BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), vm.getId(), vm.getInstanceName(), requestedBackupType, backupEngine,
                    backupPath, System.currentTimeMillis() - backupStartTime, e.getMessage());
            LOG.error("Unexpected failure while executing Commvault backup for VM {}. Cleaning up incomplete backup entry [{}].",
                    vm.getInstanceName(), backupVO.getUuid(), e);
            markBackupFailure(backupVO, "unexpected-runtime", e.getMessage());
            try {
                Backup existingBackup = backupDao.findById(backupVO.getId());
                if (existingBackup != null) {
                    backupVO.setStatus(Backup.Status.Failed);
                    removeBackupWithDetails(backupVO.getId());
                }
            } catch (Exception cleanupException) {
                LOG.warn("Failed to cleanup incomplete Commvault backup entry [{}] after unexpected error", backupVO.getUuid(), cleanupException);
            }
            throw e;
        }
    }

    private BackupExecutionResult failCompletedCommvaultBackupMetadata(BackupVO backupVO, String externalId, String details) {
        backupVO.setExternalId(externalId);
        LOG.error("{} phase=[FAILED], backupId=[{}], backupUuid=[{}], vmId=[{}], backupType=[{}], backupPath=[{}], externalId=[{}], reason=[{}]",
                BACKUP_TRACE, backupVO.getId(), backupVO.getUuid(), backupVO.getVmId(), backupVO.getType(), getBackupPathFromExternalId(backupVO), externalId, details);
        markBackupFailure(backupVO, "metadata-finalize", details);
        backupVO.setStatus(Backup.Status.Error);
        backupDao.update(backupVO.getId(), backupVO);
        updateBackupDetail(backupVO, DETAIL_ERROR_REASON, ERROR_REASON_METADATA_FINALIZE);
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
        return String.format("%s/%s/%s", getBackupStageRootPath(), vm.getInstanceName(),
                new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss.SSS").format(new Date()));
    }

    private String buildBackupContentPath(VirtualMachine vm) {
        return String.format("%s/%s", getBackupStageRootPath(), vm.getInstanceName());
    }

    private String getBackupStageRootPath() {
        return Path.of(StringUtils.defaultIfBlank(CommvaultStageRootPath.value(), "/tmp/mold/backup"))
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private void validateVolumePoolTypes(List<PrimaryDataStoreTO> volumePools) {
        boolean hasRbd = volumePools.stream().anyMatch(pool -> pool.getPoolType() == Storage.StoragePoolType.RBD);
        boolean hasNonRbd = volumePools.stream().anyMatch(pool -> pool.getPoolType() != Storage.StoragePoolType.RBD);
        if (hasRbd && hasNonRbd) {
            throw new CloudRuntimeException("Commvault incremental backup does not support VMs with mixed RBD and non-RBD volumes");
        }
    }

    private boolean areAllVolumesOnRbdPool(List<PrimaryDataStoreTO> volumePools) {
        return volumePools.stream().allMatch(pool -> pool.getPoolType() == Storage.StoragePoolType.RBD);
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

    private List<String> getBackupFileChains(List<Backup.VolumeInfo> backupVolumes, Backup backup) {
        return backupVolumes.stream()
                .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                .map(volume -> getBackupFileChain(volume, backup))
                .collect(Collectors.toList());
    }

    private String getBackupFileChain(Backup.VolumeInfo backupVolume, Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        List<String> chain = getBackupChain(backupVolume, backup);
        return String.join(";", chain);
    }

    private List<BackupVolumeChainState> getVolumeChainStates(List<Backup.VolumeInfo> backupVolumes, Backup backup) {
        String backupEngine = getBackupDetail(backup, DETAIL_BACKUP_ENGINE);
        List<BackupVolumeChainState> volumeChainStates = backupVolumes.stream()
                .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                .map(volume -> new BackupVolumeChainState(volume.getUuid(), backupEngine,
                        AblestackBackupFrameworkUtils.sanitizeChainFiles(getBackupChain(volume, backup))))
                .collect(Collectors.toList());
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
        loadBackupDetailsIfNeeded(backup);
        final List<BackupVolumeChainState> chainStates = getVolumeChainStates(backup.getBackedUpVolumes(), backup);
        AblestackBackupFrameworkUtils.validateVolumeChainStates(chainStates);
        LOG.debug("Completed Commvault post-restore maintenance for VM [{}], backup [{}], volumeOnly=[{}]", vm != null ? vm.getInstanceName() : null,
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
                LOG.warn("Sealed Commvault backup chain [{}] during background validation in zone [{}]", latestBackup.getUuid(), zoneId);
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
        return offering != null && Objects.equals(getName(), offering.getProvider());
    }

    private List<String> getBackupChain(Backup.VolumeInfo backupVolume, Backup backup) {
        loadBackupDetailsIfNeeded(backup);
        List<String> chain = new ArrayList<>();
        Backup current = backup;
        while (current != null) {
            loadBackupDetailsIfNeeded(current);
            Backup.VolumeInfo currentVolumeInfo = current.getBackedUpVolumes().stream()
                    .filter(volume -> Objects.equals(volume.getUuid(), backupVolume.getUuid()))
                    .findFirst()
                    .orElse(null);
            if (currentVolumeInfo == null) {
                break;
            }
            chain.add(0, getRestoreBackupFilePath(current, currentVolumeInfo));
            if (StringUtils.endsWith(currentVolumeInfo.getPath(), ".raw")) {
                break;
            }
            String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (parentBackupUuid == null) {
                break;
            }
            current = backupDao.findByUuid(parentBackupUuid);
        }
        if (chain.isEmpty()) {
            chain.add(backupVolume.getPath());
        }
        return chain;
    }

    private LinkedHashMap<String, Backup> getBackupChainStageHosts(Backup backup) {
        LinkedHashMap<String, Backup> stageHosts = new LinkedHashMap<>();
        Backup current = backup;
        while (current != null) {
            loadBackupDetailsIfNeeded(current);
            String stageHost = getBackupDetail(current, DETAIL_STAGE_HOST);
            if (StringUtils.isNotBlank(stageHost)) {
                stageHosts.putIfAbsent(stageHost, current);
            }
            String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (parentBackupUuid == null) {
                break;
            }
            current = backupDao.findByUuid(parentBackupUuid);
        }
        return stageHosts;
    }

    private List<String> getRestoreSourcePathsForStageHost(Backup backup, String stageHost) {
        List<String> restoreSourcePaths = new ArrayList<>();
        Backup current = backup;
        while (current != null) {
            loadBackupDetailsIfNeeded(current);
            String currentStageHost = getBackupDetail(current, DETAIL_STAGE_HOST);
            if (Objects.equals(currentStageHost, stageHost)) {
                String backupPath = parseExternalId(current.getExternalId()).first();
                if (!restoreSourcePaths.contains(backupPath)) {
                    restoreSourcePaths.add(0, backupPath);
                }
            }
            String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (parentBackupUuid == null) {
                break;
            }
            current = backupDao.findByUuid(parentBackupUuid);
        }

        if (restoreSourcePaths.isEmpty()) {
            restoreSourcePaths.add(getRestoreBackupRootPath(backup));
        }
        return restoreSourcePaths;
    }

    private void loadBackupDetailsIfNeeded(Backup backup) {
        if (backup instanceof BackupVO && backup.getDetails() == null) {
            backupDao.loadDetails((BackupVO) backup);
        }
    }

    private Map<String, String> getParentCheckpointXmlChain(Backup latestBackup) {
        Map<String, String> checkpointXmlChain = new LinkedHashMap<>();
        Backup current = latestBackup;
        Set<String> visitedBackupUuids = new HashSet<>();
        while (current != null && StringUtils.isNotBlank(current.getUuid()) && visitedBackupUuids.add(current.getUuid())) {
            loadBackupDetailsIfNeeded(current);
            String checkpointPath = getBackupDetail(current, DETAIL_CHECKPOINT_PATH);
            String checkpointXml = getBackupDetail(current, DETAIL_CHECKPOINT_XML);
            if (StringUtils.isNotBlank(checkpointPath) && StringUtils.isNotBlank(checkpointXml)) {
                checkpointXmlChain.putIfAbsent(checkpointPath, checkpointXml);
            } else {
                LOG.debug("Skipping checkpoint XML chain entry for backup [{}] due to missing path/xml details", current.getUuid());
            }

            String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (StringUtils.isBlank(parentBackupUuid)) {
                break;
            }
            current = backupDao.findByUuid(parentBackupUuid);
        }
        return checkpointXmlChain;
    }

    private String getRestoreBackupRootPath(Backup backup) {
        final String backupPath = parseExternalId(backup.getExternalId()).first();
        if (BACKUP_ENGINE_RBD_DIFF.equals(getBackupDetail(backup, DETAIL_BACKUP_ENGINE))) {
            return java.nio.file.Path.of(backupPath).getParent().toString();
        }
        return backupPath;
    }

    private String getRestoreBackupFilePath(Backup backup, Backup.VolumeInfo volumeInfo) {
        final String backupPath = parseExternalId(backup.getExternalId()).first();
        final String filePath = volumeInfo.getPath();
        if (BACKUP_ENGINE_RBD_DIFF.equals(getBackupDetail(backup, DETAIL_BACKUP_ENGINE))) {
            return java.nio.file.Path.of(backupPath).getFileName().resolve(filePath).toString();
        }
        return java.nio.file.Path.of(backupPath, filePath).toString();
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

    private LinkedHashMap<String, List<String>> restoreBackupSourcesOnAdditionalHosts(AblestackCommvaultClient client, Backup backup, String executionHostName) {
        if (!BACKUP_ENGINE_RBD_DIFF.equals(getBackupDetail(backup, DETAIL_BACKUP_ENGINE))) {
            return new LinkedHashMap<>();
        }

        LinkedHashMap<String, List<String>> additionalHosts = new LinkedHashMap<>();
        for (Map.Entry<String, Backup> entry : getBackupChainStageHosts(backup).entrySet()) {
            String stageHost = entry.getKey();
            if (StringUtils.isBlank(stageHost) || Objects.equals(stageHost, executionHostName)) {
                continue;
            }
            List<String> restoreSourcePaths = getRestoreSourcePathsForStageHost(backup, stageHost);
            restoreBackupPathsOnStageHost(client, entry.getValue(), restoreSourcePaths);
            additionalHosts.put(stageHost, restoreSourcePaths);
        }
        return additionalHosts;
    }

    private void restoreBackupPathsOnStageHost(AblestackCommvaultClient client, Backup backup, List<String> restoreSourcePaths) {
        final Pair<String, String> externalIdParts = parseExternalId(backup.getExternalId());
        final String jobId = externalIdParts.second();
        String jobDetails = client.getJobDetails(jobId);
        if (jobDetails == null) {
            throw new CloudRuntimeException("Failed to get job details commvault api");
        }

        JSONObject jsonObject = new JSONObject(jobDetails);
        String endTime = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").get("endTime"));
        String subclientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("subclientId"));
        String displayName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("displayName"));
        String clientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientId"));
        String companyId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").get("companyId"));
        String companyName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").get("companyName"));
        String instanceName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceName"));
        String appName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("appName"));
        String applicationId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("applicationId"));
        String clientName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientName"));
        String backupsetId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetId"));
        String instanceId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceId"));
        String backupsetName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetName"));
        String commCellId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("commcell").get("commCellId"));
        String backupsetGUID = client.getVmBackupSetGuid(clientName, backupsetName);
        if (backupsetGUID == null) {
            throw new CloudRuntimeException("Failed to get vm backup set guid commvault api");
        }

        ensureStageHostHasCapacityForRestore(backup, clientName, restoreSourcePaths);
        String restoreJobId = client.restoreFullVM(subclientId, displayName, backupsetGUID, clientId, companyId, companyName, instanceName,
                appName, applicationId, clientName, backupsetId, instanceId, backupsetName, commCellId, endTime, restoreSourcePaths);
        if (restoreJobId == null) {
            throw new CloudRuntimeException("Failed to restore Full VM commvault api");
        }

        String jobStatus = client.getJobStatus(restoreJobId);
        if (!jobStatus.equalsIgnoreCase("Completed")) {
            throw new CloudRuntimeException("Failed to restore Full VM commvault api resulted in " + jobStatus);
        }
    }

    private void cleanupBackupPathsOnAdditionalHosts(Map<String, List<String>> hostPaths) {
        if (hostPaths == null || hostPaths.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : hostPaths.entrySet()) {
            String hostName = entry.getKey();
            if (StringUtils.isBlank(hostName)) {
                continue;
            }
            HostVO host = hostDao.findByName(hostName);
            if (host == null) {
                continue;
            }
            try {
                cleanupBackupPathsOnHost(host, entry.getValue());
            } catch (Exception e) {
                LOG.warn("Failed to cleanup Commvault restore source paths {} on host [{}]", entry.getValue(), hostName, e);
            }
        }
    }

    private void cleanupBackupPathsAfterSuccessfulBackup(HostVO host, List<String> backupPaths, Backup backup) {
        if (host == null || CollectionUtils.isEmpty(backupPaths)) {
            return;
        }
        LOG.info("Cleaning up Commvault staging paths after successful backup [{}] on host [{}]: {}",
                backup != null ? backup.getUuid() : null, host.getName(), backupPaths);
        cleanupBackupPathsOnHost(host, backupPaths);
    }

    private void cleanupBackupPathsOnHost(HostVO host, List<String> backupPaths) {
        if (host == null || CollectionUtils.isEmpty(backupPaths)) {
            return;
        }
        try {
            final Answer answer = agentManager.send(host.getId(), new AblestackCommvaultCleanupCommand(backupPaths, getBackupStageRootPath()));
            if (answer == null || !answer.getResult()) {
                LOG.warn("Failed to cleanup Commvault paths {} on host [{}]: {}", backupPaths, host.getName(),
                        answer != null ? answer.getDetails() : "no answer received");
            }
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to cleanup Commvault paths {} on host [{}]", backupPaths, host.getName(), e);
        }
    }

    private void cleanupBackupStagingPathFromDetails(Backup backup) {
        if (backup == null || StringUtils.isBlank(backup.getExternalId())) {
            return;
        }
        final String stageHostName = getBackupDetail(backup, DETAIL_STAGE_HOST);
        if (StringUtils.isBlank(stageHostName)) {
            LOG.warn("Skipping Commvault staging cleanup for backup [{}] because stage host detail is missing", backup.getUuid());
            return;
        }
        final String backupPath;
        try {
            backupPath = backup.getExternalId().contains(",") ? parseExternalId(backup.getExternalId()).first() : backup.getExternalId();
        } catch (CloudRuntimeException e) {
            LOG.warn("Skipping Commvault staging cleanup for backup [{}] due to invalid externalId [{}]",
                    backup.getUuid(), backup.getExternalId());
            return;
        }
        HostVO stageHost = hostDao.findByName(stageHostName);
        if (stageHost == null) {
            LOG.warn("Skipping Commvault staging cleanup for backup [{}] because stage host [{}] was not found",
                    backup.getUuid(), stageHostName);
            return;
        }
        cleanupBackupPathsOnHost(stageHost, Collections.singletonList(backupPath));
    }

    private boolean isSameHost(HostVO firstHost, HostVO secondHost) {
        return firstHost != null && secondHost != null && Objects.equals(firstHost.getId(), secondHost.getId());
    }

    private String getLegacyBackupFileName(Backup.VolumeInfo backupVolumeInfo) {
        String diskType = Volume.Type.ROOT.equals(backupVolumeInfo.getType()) ? "root" : "datadisk";
        return String.format("%s.%s.qcow2", diskType, backupVolumeInfo.getUuid());
    }

    // 백업에서 새 인스턴스 생성
    @Override
    public Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid, boolean quickRestore) {
        return restoreVMBackup(vm, backup);
    }

    // 가상머신 백업 복원
    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup, boolean quickRestore, Long hostId) {
        return restoreVMBackup(vm, backup).first();
    }

    private Pair<Boolean, String> restoreVMBackup(VirtualMachine vm, Backup backup) {
        validateCommvaultRestoreSnapshotCompatibility(vm);
        validateRestoreChainIntegrity(backup);
        loadBackupDetailsIfNeeded(backup);
        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        final AblestackCommvaultClient client = getClient(vm.getDataCenterId());
        final String externalId = backup.getExternalId();
        final Pair<String, String> externalIdParts = parseExternalId(externalId);
        final String path = externalIdParts.first();
        final String restoreSourcePath = getRestoreBackupRootPath(backup);
        final String jobId = externalIdParts.second();
        String jobDetails = client.getJobDetails(jobId);
        if (jobDetails == null) {
            throw new CloudRuntimeException("Failed to get job details commvault api");
        }
        JSONObject jsonObject = new JSONObject(jobDetails);
        String endTime = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").get("endTime"));
        String subclientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("subclientId"));
        String displayName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("displayName"));
        String clientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientId"));
        String companyId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").get("companyId"));
        String companyName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").get("companyName"));
        String instanceName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceName"));
        String appName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("appName"));
        String applicationId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("applicationId"));
        String clientName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientName"));
        String backupsetId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetId"));
        String instanceId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceId"));
        String backupsetName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetName"));
        String commCellId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("commcell").get("commCellId"));
        String backupsetGUID = client.getVmBackupSetGuid(clientName, backupsetName);
        if (backupsetGUID == null) {
            throw new CloudRuntimeException("Failed to get vm backup set guid commvault api");
        }
        // 복원된 호스트 정의
        final HostVO restoreHost = hostDao.findByName(clientName);
        final HostVO restoreHostVO = hostDao.findById(restoreHost.getId());
        final LinkedHashMap<String, List<String>> additionalSourceHostPaths = restoreBackupSourcesOnAdditionalHosts(client, backup, clientName);
        final List<String> restoreSourcePaths = getRestoreSourcePathsForStageHost(backup, clientName);
        LOG.info(String.format("Restoring vm %s from backup %s on the Commvault Backup Provider", vm, backup));
        try {
            ensureStageHostHasCapacityForRestore(backup, clientName, restoreSourcePaths);
            String jobId2 = client.restoreFullVM(subclientId, displayName, backupsetGUID, clientId, companyId, companyName, instanceName, appName, applicationId, clientName, backupsetId, instanceId, backupsetName, commCellId, endTime, restoreSourcePaths);
            if (jobId2 != null) {
                String jobStatus = client.getJobStatus(jobId2);
                if (jobStatus.equalsIgnoreCase("Completed")) {
                List<String> backedVolumesUUIDs = backup.getBackedUpVolumes().stream()
                        .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                        .map(Backup.VolumeInfo::getUuid)
                        .collect(Collectors.toList());

                List<VolumeVO> restoreVolumes = volumeDao.findByInstance(vm.getId()).stream()
                        .sorted(Comparator.comparingLong(VolumeVO::getDeviceId))
                        .collect(Collectors.toList());

                LOG.debug("Restoring vm {} from backup {} on the Commvault Backup Provider", vm, backup);
                AblestackCommvaultRestoreBackupCommand restoreCommand = new AblestackCommvaultRestoreBackupCommand();
                LOG.info(restoreSourcePath);
                restoreCommand.setBackupPath(restoreSourcePath);
                restoreCommand.setVmName(vm.getName());
                restoreCommand.setBackupVolumesUUIDs(backedVolumesUUIDs);
                if (isLegacyBackup(backup)) {
                    restoreCommand.setBackupFiles(backup.getBackedUpVolumes().stream()
                            .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                            .map(this::getLegacyBackupFileName)
                            .collect(Collectors.toList()));
                } else {
                    restoreCommand.setBackupFiles(backup.getBackedUpVolumes().stream()
                            .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                            .map(volume -> getRestoreBackupFilePath(backup, volume))
                            .collect(Collectors.toList()));
                    restoreCommand.setBackupFileChains(getBackupFileChains(backup.getBackedUpVolumes(), backup));
                }
                restoreCommand.setVolumeChainStates(getVolumeChainStates(backup.getBackedUpVolumes(), backup));
                Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(restoreVolumes);
                restoreCommand.setRestoreVolumePools(volumePoolsAndPaths.first());
                restoreCommand.setRestoreVolumePaths(volumePoolsAndPaths.second());
                restoreCommand.setVmExists(vm.getRemoved() == null);
                restoreCommand.setVmState(vm.getState());
                restoreCommand.setRestorePlan(createRestorePlan(false));
                restoreCommand.setTimeout(BackupRestoreTimeout.value());
                restoreCommand.setHostName(null);
                restoreCommand.setBackupSourceHosts(new ArrayList<>(additionalSourceHostPaths.keySet()));

                BackupAnswer answer;
                try {
                    answer = (BackupAnswer) agentManager.send(restoreHost.getId(), restoreCommand);
                } catch (AgentUnavailableException e) {
                    throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
                } catch (OperationTimedoutException e) {
                    throw new CloudRuntimeException("Operation to restore backup timed out, please try again");
                }
                if (!answer.getResult()) {
                    cleanupBackupPathsOnHost(restoreHostVO, Collections.singletonList(restoreSourcePath));
                }
                return new Pair<>(answer.getResult(), answer.getDetails());
                } else {
                    throw new CloudRuntimeException("Failed to restore Full VM commvault api resulted in " + jobStatus);
                }
            } else {
                throw new CloudRuntimeException("Failed to restore Full VM commvault api");
            }
        } finally {
            cleanupBackupPathsOnHost(restoreHostVO, restoreSourcePaths);
            cleanupBackupPathsOnAdditionalHosts(additionalSourceHostPaths);
        }
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

    // 백업 볼륨 복원 및 연결
    @Override
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid, Pair<String, VirtualMachine.State> vmNameAndState, VirtualMachine targetVm, boolean quickRestore) {
        validateRestoreChainIntegrity(backup);
        loadBackupDetailsIfNeeded(backup);
        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        final String externalId = backup.getExternalId();
        final Long zoneId = backup.getZoneId();
        final AblestackCommvaultClient client = getClient(zoneId);
        final Pair<String, String> externalIdParts = parseExternalId(externalId);
        final String path = externalIdParts.first();
        final String restoreSourcePath = getRestoreBackupRootPath(backup);
        final String jobId = externalIdParts.second();
        String jobDetails = client.getJobDetails(jobId);
        if (jobDetails == null) {
            throw new CloudRuntimeException("Failed to get job details commvault api");
        }
        JSONObject jsonObject = new JSONObject(jobDetails);
        String endTime = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").get("endTime"));
        String subclientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("subclientId"));
        String displayName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("displayName"));
        String clientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientId"));
        String companyId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").get("companyId"));
        String companyName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").get("companyName"));
        String instanceName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceName"));
        String appName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("appName"));
        String applicationId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("applicationId"));
        String clientName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientName"));
        String backupsetId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetId"));
        String instanceId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceId"));
        String backupsetName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetName"));
        String commCellId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("commcell").get("commCellId"));
        String backupsetGUID = client.getVmBackupSetGuid(clientName, backupsetName);
        if (backupsetGUID == null) {
            throw new CloudRuntimeException("Failed to get vm backup set guid commvault api");
        }
        HostVO restoreHost = hostDao.findByName(clientName);
        if (restoreHost == null) {
            restoreHost = hostDao.findByIp(clientName);
        }
        final HostVO restoreHostVO = restoreHost != null ? hostDao.findById(restoreHost.getId()) : null;
        final List<String> restoreSourcePaths = getRestoreSourcePathsForStageHost(backup, clientName);
        final LinkedHashMap<String, List<String>> additionalSourceHostPaths = restoreBackupSourcesOnAdditionalHosts(client, backup, clientName);
        HostVO commandHostForCleanup = null;
        try {
            ensureStageHostHasCapacityForRestore(backup, clientName, restoreSourcePaths);
            String jobId2 = client.restoreFullVM(subclientId, displayName, backupsetGUID, clientId, companyId, companyName, instanceName, appName, applicationId, clientName, backupsetId, instanceId, backupsetName, commCellId, endTime, restoreSourcePaths);
            if (jobId2 != null) {
                String jobStatus = client.getJobStatus(jobId2);
                if (jobStatus.equalsIgnoreCase("Completed")) {
                    final VolumeVO volume = volumeDao.findByUuid(backupVolumeInfo.getUuid());
                    final DiskOffering diskOffering = diskOfferingDao.findByUuid(backupVolumeInfo.getDiskOfferingId());
                    if (diskOffering == null) {
                        throw new CloudRuntimeException(String.format("Unable to find disk offering [%s] for backed up volume [%s]",
                                backupVolumeInfo.getDiskOfferingId(), backupVolumeInfo.getUuid()));
                    }
                    final Backup.VolumeInfo matchingVolume = getBackedUpVolumeInfo(backup.getBackedUpVolumes(), backupVolumeInfo.getUuid())
                            .orElseThrow(() -> new CloudRuntimeException(String.format(
                                    "Unable to find volume %s in the list of backed up volumes for backup %s, cannot proceed with restore",
                                    backupVolumeInfo.getUuid(), backup)));
                    String cacheMode = null;
                    final VMInstanceVO vm = vmInstanceDao.findVMByInstanceName(vmNameAndState.first());
                    List<VolumeVO> listVolumes = volumeDao.findByInstanceAndType(vm.getId(), Type.ROOT);
                    if(CollectionUtils.isNotEmpty(listVolumes)) {
                        VolumeVO rootDisk = listVolumes.get(0);
                        DiskOffering baseDiskOffering = diskOfferingDao.findById(rootDisk.getDiskOfferingId());
                        if (baseDiskOffering.getCacheMode() != null) {
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
                        throw new CloudRuntimeException(String.format("Unable to find VM host [%s] for Commvault volume restore", hostIp));
                    }
                    commandHostForCleanup = vmHost;
                    // 복원된 호스트 정의
                    restoreHost = hostDao.findByName(clientName);
                    if (restoreHost == null) {
                        restoreHost = hostDao.findByIp(clientName);
                    }
                    if (restoreHost == null) {
                        throw new CloudRuntimeException(String.format("Unable to find restore host [%s] for Commvault volume restore", clientName));
                    }
                    LOG.info(String.format("Restoring volume %s from backup %s on the Commvault Backup Provider", backupVolumeInfo.getUuid(), backup));
                    LOG.debug("Restoring vm volume {} from backup {} on the Commvault Backup Provider", backupVolumeInfo, backup);
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

                    AblestackCommvaultRestoreBackupCommand restoreCommand = new AblestackCommvaultRestoreBackupCommand();
                    restoreCommand.setBackupPath(restoreSourcePath);
                    restoreCommand.setVmName(vmNameAndState.first());
                    restoreCommand.setBackupFiles(Collections.singletonList(isLegacyBackup(backup) ? getLegacyBackupFileName(matchingVolume) : getRestoreBackupFilePath(backup, matchingVolume)));
                    if (!isLegacyBackup(backup)) {
                        restoreCommand.setBackupFileChains(Collections.singletonList(getBackupFileChain(matchingVolume, backup)));
                    }
                    restoreCommand.setVolumeChainStates(getVolumeChainStates(Collections.singletonList(matchingVolume), backup));
                    restoreCommand.setRestoreVolumePaths(Collections.singletonList(String.format("%s/%s", getVolumePathPrefix(pool), volumeUUID)));
                    DataStore dataStore = dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
                    if (dataStore == null) {
                        throw new CloudRuntimeException(String.format("Unable to get primary datastore TO for pool [%s] while restoring volume [%s]",
                                pool.getUuid(), backupVolumeInfo.getUuid()));
                    }
                    restoreCommand.setRestoreVolumePools(Collections.singletonList((PrimaryDataStoreTO) dataStore.getTO()));
                    restoreCommand.setDiskType(matchingVolume.getType().name().toLowerCase(Locale.ROOT));
                    restoreCommand.setVmExists(null);
                    restoreCommand.setVmState(vmNameAndState.second());
                    restoreCommand.setRestoreVolumeUUID(backupVolumeInfo.getUuid());
                    restoreCommand.setRestorePlan(createRestorePlan(AblestackBackupFrameworkUtils.requiresRunningVmAttach(vmNameAndState.second())));
                    restoreCommand.setTimeout(BackupRestoreTimeout.value());
                    restoreCommand.setCacheMode(cacheMode);
                    restoreCommand.setHostName(restoreHost.getName());
                    restoreCommand.setBackupSourceHosts(new ArrayList<>(additionalSourceHostPaths.keySet()));

                    BackupAnswer answer;
                    try {
                        answer = (BackupAnswer) agentManager.send(vmHost.getId(), restoreCommand);
                    } catch (AgentUnavailableException e) {
                        throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
                    } catch (OperationTimedoutException e) {
                        throw new CloudRuntimeException("Operation to restore backed up volume timed out, please try again");
                    }

                    if (answer.getResult()) {
                        try {
                            volumeDao.persist(restoredVolume);
                        } catch (Exception e) {
                            throw new CloudRuntimeException("Unable to create restored volume due to: " + e);
                        }
                        LOG.info("Successfully restored volume {} from backup {} on the Commvault Backup Provider. Restored volume UUID: {}",
                                backupVolumeInfo.getUuid(), backup, restoredVolume.getUuid());
                        return new Pair<>(answer.getResult(), answer.getDetails());
                    } else {
                        cleanupBackupPathsOnHost(restoreHostVO, Collections.singletonList(restoreSourcePath));
                        return new Pair<>(false, StringUtils.defaultIfBlank(answer.getDetails(),
                                String.format("Restore agent returned failure for volume [%s] on host [%s]", backupVolumeInfo.getUuid(), restoreHost.getName())));
                    }
                } else {
                    String errorMessage = "Failed to restore backup for VM " + vmNameAndState.first() + " to restore backup job status is " + jobStatus;
                    LOG.error(errorMessage);
                    return new Pair<>(false, errorMessage);
                }
            } else {
                String errorMessage = "Failed to restore backup for VM " + vmNameAndState.first() + " to restore backup job commvault api";
                LOG.error(errorMessage);
                return new Pair<>(false, errorMessage);
            }
        } finally {
            cleanupBackupPathsOnHost(restoreHostVO, restoreSourcePaths);
            cleanupBackupPathsOnAdditionalHosts(additionalSourceHostPaths);
            if (commandHostForCleanup != null && !isSameHost(commandHostForCleanup, restoreHostVO)) {
                cleanupBackupPathsOnHost(commandHostForCleanup, Collections.singletonList(restoreSourcePath));
            }
        }
    }

    private Optional<Backup.VolumeInfo> getBackedUpVolumeInfo(List<Backup.VolumeInfo> backedUpVolumes, String volumeUuid) {
        return backedUpVolumes.stream()
                .filter(v -> v.getUuid().equals(volumeUuid))
                .findFirst();
    }

    private void ensureStageHostHasCapacityForRestore(Backup backup, String clientName, List<String> restoreSourcePaths) {
        HostVO stageHost = hostDao.findByName(clientName);
        if (stageHost == null) {
            stageHost = hostDao.findByIp(clientName);
        }
        if (stageHost == null) {
            throw new CloudRuntimeException(String.format("Unable to find stage host [%s] for Commvault restore capacity check", clientName));
        }
        long requiredBytes = estimateRequiredStageBytesForRestore(backup, restoreSourcePaths);
        long bufferBytes = Math.max(STAGE_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        long minimumAvailableBytes = requiredBytes + bufferBytes;
        String stageRootPath = getBackupStageRootPath();
        long availableBytes = getAvailableBytesOnHostPath(stageHost, stageRootPath);
        LOG.info("Checking Commvault restore stage capacity on host [{}]: required={} bytes, buffer={} bytes, minimumAvailable={} bytes, available={} bytes, sourcePaths={}",
                stageHost.getName(), requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes, restoreSourcePaths);
        if (availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient stage space on host [%s] for Commvault restore. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    stageHost.getName(), minimumAvailableBytes, availableBytes, stageRootPath));
        }
    }

    private void validateBackupStageCapacity(HostVO stageHost, String stageRootPath, List<VolumeVO> vmVolumes, String vmName, String backupType, String backupEngine) {
        long requiredBytes = estimateRequiredStageBytesForBackup(vmVolumes, backupType);
        long bufferBytes = Math.max(STAGE_SPACE_BUFFER_BYTES, requiredBytes / 5L);
        long minimumAvailableBytes = requiredBytes + bufferBytes;
        long availableBytes = getAvailableBytesOnHostPath(stageHost, stageRootPath);
        LOG.info("{} phase=[STAGE_SPACE_CHECK], vm=[{}], host=[{}], backupType=[{}], backupEngine=[{}], stageRoot=[{}], requiredBytes=[{}], bufferBytes=[{}], minimumAvailableBytes=[{}], availableBytes=[{}]",
                BACKUP_TRACE, vmName, stageHost != null ? stageHost.getName() : null, backupType, backupEngine, stageRootPath,
                requiredBytes, bufferBytes, minimumAvailableBytes, availableBytes);
        if (availableBytes < minimumAvailableBytes) {
            throw new CloudRuntimeException(String.format(
                    "Insufficient stage space on host [%s] for Commvault backup. Required at least [%d] bytes including buffer, but only [%d] bytes are available under [%s].",
                    stageHost != null ? stageHost.getName() : null, minimumAvailableBytes, availableBytes, stageRootPath));
        }
    }

    private long estimateRequiredStageBytesForBackup(List<VolumeVO> vmVolumes) {
        if (CollectionUtils.isEmpty(vmVolumes)) {
            return 0L;
        }
        return estimateReadyVolumeBytes(vmVolumes);
    }

    private long estimateRequiredStageBytesForBackup(List<VolumeVO> vmVolumes, String backupType) {
        long readyVolumeBytes = estimateRequiredStageBytesForBackup(vmVolumes);
        if (BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backupType)) {
            return Math.max(1L, readyVolumeBytes * INCREMENTAL_BACKUP_CAPACITY_ESTIMATE_PERCENT / 100L);
        }
        return readyVolumeBytes;
    }

    private long estimateReadyVolumeBytes(List<VolumeVO> vmVolumes) {
        if (CollectionUtils.isEmpty(vmVolumes)) {
            return 0L;
        }
        return vmVolumes.stream()
                .filter(volume -> Volume.State.Ready.equals(volume.getState()))
                .mapToLong(VolumeVO::getSize)
                .sum();
    }

    private long estimateRequiredStageBytesForRestore(Backup backup, List<String> restoreSourcePaths) {
        if (CollectionUtils.isEmpty(restoreSourcePaths)) {
            return Math.max(backup.getSize(), 0L);
        }
        long totalBytes = 0L;
        Backup current = backup;
        while (current != null) {
            loadBackupDetailsIfNeeded(current);
            String currentPath = parseExternalId(current.getExternalId()).first();
            if (restoreSourcePaths.contains(currentPath)) {
                totalBytes += Math.max(current.getSize(), 0L);
            }
            String parentBackupUuid = getBackupDetail(current, DETAIL_PARENT_BACKUP_UUID);
            if (StringUtils.isBlank(parentBackupUuid)) {
                break;
            }
            current = backupDao.findByUuid(parentBackupUuid);
        }
        return totalBytes > 0 ? totalBytes : Math.max(backup.getSize(), 0L);
    }

    private long getAvailableBytesOnHostPath(HostVO host, String path) {
        if (host == null || StringUtils.isBlank(path)) {
            throw new CloudRuntimeException("Host and path are required to query available Commvault stage space");
        }
        try {
            final Answer answer = agentManager.send(host.getId(), new AblestackCommvaultGetAvailableBytesCommand(path));
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(String.format("Failed to query available stage space on host %s due to: %s",
                        host.getName(), answer != null ? answer.getDetails() : "no answer received"));
            }
            String output = StringUtils.trimToEmpty(answer.getDetails());
            return Long.parseLong(output);
        } catch (NumberFormatException e) {
            throw new CloudRuntimeException(String.format("Failed to parse available stage space on host %s for path %s", host.getName(), path), e);
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException(String.format("Unable to contact host %s to query available stage space", host.getName()), e);
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException(String.format("Timed out querying available stage space on host %s", host.getName()), e);
        } catch (Exception e) {
            throw new CloudRuntimeException(String.format("Failed to query available stage space on host %s due to: %s", host.getName(), e.getMessage()), e);
        }
    }

    private String readFileContentsOnHost(HostVO host, String path) {
        if (host == null || StringUtils.isBlank(path)) {
            return null;
        }
        try {
            final Answer answer = agentManager.send(host.getId(), new AblestackCommvaultReadFileCommand(path));
            if (answer == null || !answer.getResult()) {
                return null;
            }
            return answer.getDetails();
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            LOG.warn("Failed to read file [{}] on host [{}]", path, host.getName(), e);
            return null;
        } catch (Exception e) {
            LOG.warn("Failed to read file [{}] on host [{}]", path, host.getName(), e);
            return null;
        }
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        loadBackupDetailsIfNeeded(backup);
        final VirtualMachine vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (!forced && hasDependentBackups(backup)) {
            throw new CloudRuntimeException(String.format("Backup [%s] cannot be deleted because one or more incremental backups depend on it.", backup.getUuid()));
        }
        final Long zoneId = backup.getZoneId();
        final String externalId = backup.getExternalId();
        final Pair<String, String> externalIdParts = parseExternalId(externalId);
        final String path = externalIdParts.first();
        final String jobId = externalIdParts.second();
        final AblestackCommvaultClient client = getClient(zoneId);
        String jobDetails = client.getJobDetails(jobId);
        if (StringUtils.isBlank(jobDetails)) {
            return handleUnavailableCommvaultJobDetailsOnDelete(backup, forced, jobId, "empty response");
        }
        try {
            JSONObject jsonObject = new JSONObject(jobDetails);
            if (!jsonObject.has("job")) {
                return handleUnavailableCommvaultJobDetailsOnDelete(backup, forced, jobId, "missing job object");
            }
            String subclientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("subclientId"));
            String applicationId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("applicationId"));
            String instanceId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceId"));
            String clientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientId"));
            String clientName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientName"));
            String backupsetId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetId"));
            boolean result = client.deleteBackup(subclientId, applicationId, instanceId, clientId, clientName, backupsetId, path);
            if (result) {
                cleanupBackupPathOnStageHost(clientName, path, forced, vm != null ? vm.getInstanceName() : null,
                        getBackupDetail(backup, DETAIL_CHECKPOINT_NAME), getUnreferencedQcow2CheckpointNamesAfterDelete(backup),
                        getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));
            }
            return result;
        } catch (JSONException e) {
            return handleUnavailableCommvaultJobDetailsOnDelete(backup, forced, jobId, e.getMessage());
        }
    }

    private boolean handleUnavailableCommvaultJobDetailsOnDelete(Backup backup, boolean forced, String jobId, String reason) {
        String message = String.format("Commvault job details for backup [%s], job [%s] are unavailable: %s",
                backup.getUuid(), jobId, reason);
        if (!forced) {
            throw new CloudRuntimeException(message);
        }
        LOG.warn("{}; skipping Commvault remote delete and allowing forced Mold backup metadata deletion.", message);
        return true;
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
            logger.warn("VM [{}] has disk-and-memory VM snapshots; this provider does not support backup offerings on VMs with these snapshots.", vm);
            return false;
        }
        if (hasKvmFileBasedVmSnapshots(vm)) {
            logger.warn("Allowing Commvault backup offering assignment for VM [{}] with KVM file-based VM snapshots for snapshot coexistence testing.", vm);
        }
        final AblestackCommvaultClient client = getClient(vm.getDataCenterId());
        final Host host = getVMHypervisorHostForBackup(vm);
        String clientId = client.getClientId(host.getName());
        String applicationId = client.getApplicationId(clientId);
        return client.createBackupSet(vm.getInstanceName(), applicationId, clientId, backupOffering.getExternalId());
    }

    private void validateNoKvmFileBasedVmSnapshots(VirtualMachine vm) {
        if (hasDiskAndMemoryVmSnapshots(vm)) {
            logger.warn("VM [{}] has disk-and-memory VM snapshots; backup cannot be started.", vm);
            throw new CloudRuntimeException(String.format("Cannot take backup of VM [%s] as it has disk-and-memory VM snapshots.", vm.getUuid()));
        }
        if (hasKvmFileBasedVmSnapshots(vm)) {
            logger.warn("Allowing Commvault backup operation for VM [{}] with KVM file-based VM snapshots for snapshot coexistence testing.", vm);
        }
    }

    private void validateCommvaultRestoreSnapshotCompatibility(VirtualMachine vm) {
        final List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vm.getId());
        if (CollectionUtils.isNotEmpty(vmSnapshots)) {
            throw new CloudRuntimeException(String.format(
                    "Unable to restore VM [%s] from Commvault backup while Instance snapshots exist. Remove Instance snapshots before restoring the backup.",
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
                        "Unable to restore VM [%s] from Commvault backup while RBD volume snapshots exist on volume [%s]. Remove RBD volume snapshots before restoring the backup.",
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
        final AblestackCommvaultClient client = getClient(vm.getDataCenterId());
        final List<BackupVO> backupsToCleanup = listCommvaultBackupsForOfferingRemoval(vm);
        List<HostVO> Hosts = hostDao.findByDataCenterId(vm.getDataCenterId());
        boolean allDeleted = true;
        for (final HostVO host : Hosts) {
            if (host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                String backupSetId = client.getVmBackupSetId(host.getName(), vm.getInstanceName());
                if (backupSetId != null) {
                    try {
                        boolean deleted = client.deleteBackupSet(backupSetId);
                        if (!deleted) {
                            allDeleted = false;
                            LOG.error("Failed to delete backupSetId: " + backupSetId +" for VM: " + vm.getInstanceName());
                        }
                    } catch (ServerApiException e) {
                        throw new CloudRuntimeException(String.format("Failed to delete Commvault backupSet [%s] for VM [%s] on host [%s]. %s",
                                backupSetId, vm.getInstanceName(), host.getName(), e.getMessage()), e);
                    }
                }
            }
        }
        if (allDeleted) {
            cleanupCommvaultBackupsForOfferingRemoval(vm, client, backupsToCleanup);
        }
        return allDeleted;
    }

    private List<BackupVO> listCommvaultBackupsForOfferingRemoval(VirtualMachine vm) {
        return backupDao.listByVmId(null, vm.getId()).stream()
                .filter(BackupVO.class::isInstance)
                .map(BackupVO.class::cast)
                .filter(this::isBackupManagedByThisProvider)
                .peek(backupDao::loadDetails)
                .sorted(Comparator.comparing(BackupVO::getDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .collect(Collectors.toList());
    }

    private void cleanupCommvaultBackupsForOfferingRemoval(VirtualMachine vm, AblestackCommvaultClient client, List<BackupVO> backups) {
        for (BackupVO backup : backups) {
            final Pair<String, String> externalIdParts = parseExternalId(backup.getExternalId());
            final String path = externalIdParts.first();
            final String stageHostName = getStageHostNameForCleanup(backup, client, externalIdParts.second());
            cleanupBackupPathOnStageHost(stageHostName, path, true, vm.getInstanceName(),
                    getBackupDetail(backup, DETAIL_CHECKPOINT_NAME), getUnreferencedQcow2CheckpointNamesAfterDelete(backup),
                    getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));
        }
    }

    private String getStageHostNameForCleanup(Backup backup, AblestackCommvaultClient client, String jobId) {
        final String stageHostName = getBackupDetail(backup, DETAIL_STAGE_HOST);
        if (StringUtils.isNotBlank(stageHostName)) {
            return stageHostName;
        }
        final String jobDetails = client.getJobDetails(jobId);
        if (jobDetails != null) {
            JSONObject jsonObject = new JSONObject(jobDetails);
            return String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientName"));
        }
        throw new CloudRuntimeException(String.format("Unable to resolve stage host for Commvault backup [%s]", backup.getUuid()));
    }

    // BackupSet 삭제 시 해당 VM 백업본의 복원 가능성도 함께 영향을 받으므로 Mold 백업 이력도 정리
    @Override
    public boolean willDeleteBackupsOnOfferingRemoval() {
        return true;
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
        return new Pair<>(0L, 0L);
    }

    @Override
    public void syncBackupStorageStats(Long zoneId) {
    }

    @Override
    public List<BackupOffering> listBackupOfferings(Long zoneId) {
        return getClient(zoneId).listPlans();
    }

    @Override
    public boolean isValidProviderOffering(Long zoneId, String uuid) {
        List<BackupOffering> policies = listBackupOfferings(zoneId);
        if (CollectionUtils.isEmpty(policies)) {
            return false;
        }
        for (final BackupOffering policy : policies) {
            if (policy.getExternalId().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Boolean crossZoneInstanceCreationEnabled(BackupOffering backupOffering) {
        return false;
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                CommvaultUrl,
                CommvaultUsername,
                CommvaultPassword,
                CommvaultValidateSSLSecurity,
                CommvaultApiRequestTimeout,
                CommvaultStageRootPath
        };
    }

    @Override
    public String getName() {
        return "ablestack-commvault";
    }

    @Override
    public String getDescription() {
        return "Commvault Backup Plugin";
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    @Override
    public void syncBackups(VirtualMachine vm) {
        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            return;
        }
        final AblestackCommvaultClient client = getClient(vm.getDataCenterId());
        for (final Backup backup: backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            if (!isBackupManagedByThisProvider(backup)) {
                continue;
            }
            loadBackupDetailsIfNeeded(backup);
            if (reconcileIncompleteBackup(vm, backup)) {
                continue;
            }
            final String externalId = backup.getExternalId();
            final Pair<String, String> externalIdParts;
            try {
                externalIdParts = parseExternalId(externalId);
            } catch (CloudRuntimeException e) {
                LOG.warn("Skipping Commvault backup sync for backup [{}] due to invalid externalId [{}]", backup.getUuid(), externalId);
                continue;
            }
            final String jobId = externalIdParts.second();
            if (Backup.Status.BackingUp.equals(backup.getStatus()) && reconcileInProgressBackupWithJob(vm, backup, client, jobId)) {
                continue;
            }
            if (Backup.Status.Error.equals(backup.getStatus()) && reconcileErrorBackupWithCompletedJob(vm, backup, client, jobId)) {
                continue;
            }
            final String path = externalIdParts.first();
            String jobDetails = client.getJobDetails(jobId);
            if (jobDetails != null) {
                JSONObject jsonObject = new JSONObject(jobDetails);
                String retainedUntil = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").get("retainedUntil"));
                String storagePolicyId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("storagePolicy").get("storagePolicyId"));
                BackupOfferingVO vmBackupOffering = new BackupOfferingDaoImpl().findById(vm.getBackupOfferingId());
                BackupOfferingVO offering = backupOfferingDao.createForUpdate(vmBackupOffering.getId());
                String retentionDay = client.getRetentionPeriod(storagePolicyId);
                offering.setRetentionPeriod(retentionDay);
                backupOfferingDao.update(offering.getId(), offering);
                long timestamp = Long.parseLong(retainedUntil) * 1000L;
                boolean isExpired = isRetentionExpired(retainedUntil);
                if (isExpired) {
                    String subclientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("subclientId"));
                    String applicationId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("applicationId"));
                    String instanceId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceId"));
                    String clientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientId"));
                    String clientName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientName"));
                    String backupsetId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetId"));
                    boolean result = client.deleteBackup(subclientId, applicationId, applicationId, clientId, clientName, backupsetId, path);
                    if (result) {
                        cleanupBackupPathOnStageHost(clientName, path, false, vm.getInstanceName(),
                                getBackupDetail(backup, DETAIL_CHECKPOINT_NAME), getUnreferencedQcow2CheckpointNamesAfterDelete(backup),
                                getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));
                        removeBackupWithDetails(backup.getId());
                    }
                }
            }
        }
        return;
    }

    private boolean reconcileIncompleteBackup(VirtualMachine vm, Backup backup) {
        if (!Backup.Status.BackingUp.equals(backup.getStatus())) {
            return false;
        }
        if (isWithinBackingUpSyncGracePeriod(backup)) {
            LOG.debug("Skipping Commvault stale-backup reconciliation for recent BackingUp backup [{}] on VM [{}]",
                    backup.getUuid(), vm.getInstanceName());
            return false;
        }
        final String backupPath = backup.getExternalId();
        if (StringUtils.isBlank(backupPath)) {
            LOG.warn("Removing stale Commvault backup [{}] for VM [{}] stuck in BackingUp without backup path details",
                    backup.getUuid(), vm.getInstanceName());
            removeBackupWithDetails(backup.getId());
            return true;
        }
        if (backupPath.contains(",")) {
            return false;
        }

        final String stageHostName = getBackupDetail(backup, DETAIL_STAGE_HOST);
        if (StringUtils.isBlank(stageHostName)) {
            LOG.warn("Removing stale Commvault backup [{}] for VM [{}] stuck in BackingUp without stage host details", backup.getUuid(), vm.getInstanceName());
            removeBackupWithDetails(backup.getId());
            return true;
        }

        LOG.warn("Removing stale Commvault backup [{}] for VM [{}] stuck in BackingUp before job details were saved. Stage host: [{}], path: [{}]",
                backup.getUuid(), vm.getInstanceName(), stageHostName, backupPath);
        cleanupBackupPathOnStageHost(stageHostName, backupPath, false, vm.getInstanceName(), getBackupDetail(backup, DETAIL_CHECKPOINT_NAME),
                getUnreferencedQcow2CheckpointNamesAfterDelete(backup),
                getBackupDetail(backup, DETAIL_RBD_DISK_PATHS));
        removeBackupWithDetails(backup.getId());
        return true;
    }

    private boolean isWithinBackingUpSyncGracePeriod(Backup backup) {
        if (backup == null || backup.getDate() == null) {
            return true;
        }
        return System.currentTimeMillis() - backup.getDate().getTime() < BACKING_UP_SYNC_GRACE_PERIOD_MS;
    }

    private void updateBackupAsCompleted(BackupVO backupVO, String externalId, String jobDetails, Map<String, String> backupDetails, String backedUpVolumes) {
        JSONObject jsonObject = new JSONObject(jobDetails);
        String endTime = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").get("endTime"));
        long timestamp = Long.parseLong(endTime) * 1000L;
        Date endDate = new Date(timestamp);
        SimpleDateFormat formatterDateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        String formattedString = formatterDateTime.format(endDate);
        String size = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").get("sizeOfApplication"));
        String type = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").get("backupType"));
        backupVO.setExternalId(externalId);
        backupVO.setType(type.toUpperCase());
        try {
            backupVO.setDate(formatterDateTime.parse(formattedString));
        } catch (ParseException e) {
            String msg = String.format("Unable to parse date [%s].", endTime);
            LOG.error(msg, e);
            throw new CloudRuntimeException(msg, e);
        }
        backupVO.setSize(Long.parseLong(size));
        backupVO.setStatus(Backup.Status.BackedUp);
        backupVO.setDetails(backupDetails);
        backupVO.setBackedUpVolumes(backedUpVolumes);
    }

    private boolean reconcileInProgressBackupWithJob(VirtualMachine vm, Backup backup, AblestackCommvaultClient client, String jobId) {
        String jobDetails = client.getJobDetails(jobId);
        if (jobDetails == null) {
            LOG.warn("Failed to get Commvault job details for in-progress backup [{}] and job [{}]", backup.getUuid(), jobId);
            return false;
        }

        JSONObject jsonObject = new JSONObject(jobDetails);
        String jobState = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("progressInfo").optString("state");
        BackupVO backupVO = backupDao.findById(backup.getId());
        if (backupVO == null) {
            return true;
        }
        backupDao.loadDetails(backupVO);

        if ("Completed".equalsIgnoreCase(jobState)) {
            List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
            vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
            String backupEngine = getBackupDetail(backupVO, DETAIL_BACKUP_ENGINE, BACKUP_ENGINE_QCOW2);
            boolean incrementalBackup = BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backupVO.getType());
            List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
            updateBackupAsCompleted(backupVO, backupVO.getExternalId(), jobDetails, backupVO.getDetails(),
                    createVolumeInfoFromVolumes(vmVolumes, backupFiles));
            backupDao.update(backupVO.getId(), backupVO);
            cleanupBackupStagingPathFromDetails(backupVO);
            LOG.info("Recovered Commvault backup [{}] for VM [{}] from BackingUp to BackedUp using job [{}]",
                    backupVO.getUuid(), vm.getInstanceName(), jobId);
            return true;
        }

        if ("Failed".equalsIgnoreCase(jobState) || "Killed".equalsIgnoreCase(jobState)) {
            LOG.warn("Removing incomplete Commvault backup [{}] for VM [{}] due to terminal job [{}] state [{}]",
                    backupVO.getUuid(), vm.getInstanceName(), jobId, jobState);
            backupVO.setStatus(Backup.Status.Failed);
            cleanupBackupStagingPathFromDetails(backupVO);
            removeBackupWithDetails(backupVO.getId());
            return true;
        }

        LOG.debug("Keeping Commvault backup [{}] for VM [{}] in BackingUp because job [{}] is in state [{}]",
                backupVO.getUuid(), vm.getInstanceName(), jobId, jobState);
        return true;
    }

    private boolean reconcileErrorBackupWithCompletedJob(VirtualMachine vm, Backup backup, AblestackCommvaultClient client, String jobId) {
        if (!ERROR_REASON_METADATA_FINALIZE.equals(getBackupDetail(backup, DETAIL_ERROR_REASON))) {
            LOG.debug("Skipping Commvault Error backup [{}] sync because the error reason [{}] is not recoverable by metadata reconciliation",
                    backup.getUuid(), getBackupDetail(backup, DETAIL_ERROR_REASON));
            return false;
        }
        String jobDetails = client.getJobDetails(jobId);
        if (jobDetails == null) {
            LOG.warn("Failed to get Commvault job details for Error backup [{}] and job [{}]", backup.getUuid(), jobId);
            return false;
        }

        JSONObject jsonObject = new JSONObject(jobDetails);
        String jobState = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("progressInfo").optString("state");
        if (!"Completed".equalsIgnoreCase(jobState)) {
            LOG.debug("Keeping Commvault backup [{}] for VM [{}] in Error because job [{}] is in state [{}]",
                    backup.getUuid(), vm.getInstanceName(), jobId, jobState);
            return false;
        }

        BackupVO backupVO = backupDao.findById(backup.getId());
        if (backupVO == null) {
            return true;
        }
        backupDao.loadDetails(backupVO);

        try {
            List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
            vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
            String backupEngine = getBackupDetail(backupVO, DETAIL_BACKUP_ENGINE, BACKUP_ENGINE_QCOW2);
            boolean incrementalBackup = BACKUP_TYPE_INCREMENTAL.equalsIgnoreCase(backupVO.getType());
            List<String> backupFiles = buildBackupFileNames(vmVolumes, backupEngine, incrementalBackup);
            if (backupVO.getDetails() != null) {
                backupVO.getDetails().remove(DETAIL_ERROR_REASON);
            }
            backupDetailsDao.removeDetail(backupVO.getId(), DETAIL_ERROR_REASON);
            updateBackupAsCompleted(backupVO, backupVO.getExternalId(), jobDetails, backupVO.getDetails(),
                    createVolumeInfoFromVolumes(vmVolumes, backupFiles));
            if (backupDao.update(backupVO.getId(), backupVO)) {
                cleanupBackupStagingPathFromDetails(backupVO);
                LOG.info("Recovered Commvault backup [{}] for VM [{}] from Error to BackedUp using completed job [{}]",
                        backupVO.getUuid(), vm.getInstanceName(), jobId);
                return true;
            }
            LOG.warn("Failed to update recovered Commvault Error backup [{}] for VM [{}] using job [{}]",
                    backupVO.getUuid(), vm.getInstanceName(), jobId);
            return false;
        } catch (RuntimeException e) {
            LOG.warn("Failed to recover Commvault Error backup [{}] for VM [{}] using job [{}]: {}",
                    backupVO.getUuid(), vm.getInstanceName(), jobId, e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean checkBackupAgent(final Long zoneId) {
        final AblestackCommvaultClient client = getClient(zoneId);
        String csVersionInfo = client.getCvtVersion();
        boolean version = versionCheck(csVersionInfo);
        if (version) {
            List<HostVO> Hosts = hostDao.findByDataCenterId(zoneId);
            if (CollectionUtils.isEmpty(Hosts)) {
                LOG.warn("No hosts found in zone [{}] for Commvault backup agent readiness check.", zoneId);
                return false;
            }
            int targetHostCount = 0;
            for (final HostVO host : Hosts) {
                if (host.getStatus() == Status.Up && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                    targetHostCount++;
                    String checkHost = client.getClientId(host.getName());
                    if (checkHost == null) {
                        LOG.info("Commvault client is not registered for host [{}] using host name [{}].", host.getPrivateIpAddress(), host.getName());
                        return false;
                    } else {
                        boolean installJob = client.getInstallActiveJob(host.getPrivateIpAddress());
                        boolean checkInstall = client.getClientProps(checkHost);
                        if (installJob || !checkInstall) {
                            if (!checkInstall) {
                                LOG.error("The host is registered with the client, but the readiness status is not normal and you must manually check the client status. host=[{}], clientId=[{}]",
                                        host.getPrivateIpAddress(), checkHost);
                            }
                            return false;
                        }
                    }
                }
            }
            if (targetHostCount == 0) {
                LOG.warn("No Up KVM hosts found in zone [{}] for Commvault backup agent readiness check. The check will be retried.", zoneId);
                return false;
            }
            LOG.info("Commvault backup agent readiness check passed for zone [{}].", zoneId);
            return true;
        }
        LOG.warn("Commvault version check failed for zone [{}]. version=[{}]", zoneId, csVersionInfo);
        return false;
    }

    @Override
    public boolean installBackupAgent(final Long zoneId) {
        final AblestackCommvaultClient client = getClient(zoneId);
        List<HostVO> Hosts = hostDao.findByDataCenterId(zoneId);
        if (CollectionUtils.isEmpty(Hosts)) {
            LOG.warn("No hosts found in zone [{}] for Commvault backup agent automatic installation.", zoneId);
            return false;
        }
        int targetHostCount = 0;
        for (final HostVO host : Hosts) {
            if (host.getStatus() == Status.Up && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                targetHostCount++;
                String commCell = client.getCommcell();
                JSONObject jsonObject = new JSONObject(commCell);
                String commCellId = String.valueOf(jsonObject.get("commCellId"));
                String commServeHostName = String.valueOf(jsonObject.get("commCellName"));
                Ternary<String, String, String> credentials = getKVMHyperisorCredentials(host);
                // 설치가 진행중인 호스트가 있는지 확인
                if (!waitForInstallActiveJobToFinish(client, host)) {
                    publishBackupAgentInstallFailureEventIfNeeded(host);
                    return false;
                }
                String checkHost = client.getClientId(host.getName());
                // 호스트가 클라이언트에 등록되지 않은 경우
                if (checkHost == null) {
                    LOG.info("Commvault client is not registered for host [{}] using host name [{}]. Creating install task with client name [{}].",
                            host.getPrivateIpAddress(), host.getName(), host.getPrivateIpAddress());
                    String jobId = client.installAgent(host.getPrivateIpAddress(), commCellId, commServeHostName, credentials.first(), credentials.second());
                    if (jobId != null) {
                        LOG.info("Created Commvault backup agent install job [{}] for host [{}]. Waiting for completion.", jobId, host.getPrivateIpAddress());
                        String jobStatus = client.getJobStatus(jobId, COMMVAULT_INSTALL_JOB_WAIT_TIMEOUT_MS);
                        if (!"Completed".equalsIgnoreCase(jobStatus)) {
                            String failureReason = client.getLastJobFailureReason();
                            LOG.error("installing agent on the Commvault Backup Provider failed jogId : {} , jobStatus : {}, reason=[{}]",
                                    jobId, jobStatus, failureReason);
                            publishBackupAgentInstallFailureEventIfNeeded(host);
                            if (isPermanentCommvaultInstallFailure(failureReason)) {
                                throw new CloudRuntimeException(String.format("%s host=[%s], jobId=[%s], reason=[%s]",
                                        COMMVAULT_PERMANENT_INSTALL_FAILURE_MESSAGE, host.getPrivateIpAddress(), jobId, failureReason));
                            }
                            return false;
                        }
                        LOG.info("Completed Commvault backup agent install job [{}] for host [{}].", jobId, host.getPrivateIpAddress());
                    } else {
                        LOG.error("installing agent on the Commvault Backup Provider failed to create install job on host [{}]", host.getPrivateIpAddress());
                        publishBackupAgentInstallFailureEventIfNeeded(host);
                        return false;
                    }
                } else {
                    LOG.info("Commvault client [{}] already exists for host [{}]. Checking readiness.", checkHost, host.getPrivateIpAddress());
                    // 호스트가 클라이언트에는 등록되었지만 구성이 정상적으로 되지 않은 경우 준비 상태 체크
                    boolean checkInstall = client.getClientCheckReadiness(checkHost);
                    if (!checkInstall) {
                        String readinessDetails = client.getClientCheckReadinessDetails(checkHost);
                        LOG.error("The host is registered with the client, but the readiness status is not normal and you must manually check the client status. host=[{}], clientId=[{}], details=[{}]",
                                host.getPrivateIpAddress(), checkHost, readinessDetails);
                        return false;
                    }
                }
            }
        }
        if (targetHostCount == 0) {
            LOG.warn("No Up KVM hosts found in zone [{}] for Commvault backup agent automatic installation. The installation will be retried.", zoneId);
            return false;
        }
        return true;
    }

    private boolean waitForInstallActiveJobToFinish(AblestackCommvaultClient client, HostVO host) {
        final long deadline = System.currentTimeMillis() + COMMVAULT_INSTALL_JOB_WAIT_TIMEOUT_MS;
        boolean loggedWaiting = false;
        while (hasInstallActiveJob(client, host)) {
            if (!loggedWaiting) {
                LOG.info("Waiting for existing Commvault backup agent install job to finish before creating a new install job. host=[{}], timeoutMillis=[{}]",
                        host.getPrivateIpAddress(), COMMVAULT_INSTALL_JOB_WAIT_TIMEOUT_MS);
                loggedWaiting = true;
            }
            if (System.currentTimeMillis() >= deadline) {
                LOG.warn("Timed out waiting for existing Commvault client agent install job to finish. host=[{}], timeoutMillis=[{}]",
                        host.getPrivateIpAddress(), COMMVAULT_INSTALL_JOB_WAIT_TIMEOUT_MS);
                return false;
            }
            try {
                Thread.sleep(COMMVAULT_INSTALL_JOB_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Interrupted while waiting for Commvault client agent install job to finish. host=[{}]",
                        host.getPrivateIpAddress(), e);
                return false;
            }
        }
        return true;
    }

    private boolean hasInstallActiveJob(AblestackCommvaultClient client, HostVO host) {
        return client.getInstallActiveJob(host.getName()) || client.getInstallActiveJob(host.getPrivateIpAddress());
    }

    private boolean isPermanentCommvaultInstallFailure(String failureReason) {
        if (StringUtils.isBlank(failureReason)) {
            return false;
        }
        String normalizedReason = failureReason.toLowerCase(Locale.ROOT);
        return normalizedReason.contains("software cache") &&
                (normalizedReason.contains("required media version") || normalizedReason.contains("missing"));
    }

    private void publishBackupAgentInstallFailureEventIfNeeded(HostVO host) {
        if (hasBackupAgentInstallFailureEvent(host.getId())) {
            return;
        }
        ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM, EventVO.LEVEL_ERROR, EventTypes.EVENT_BACKUP_AGENT_INSTALL,
                "Failed to install the Commvault backup agent on host: " + host.getPrivateIpAddress(), host.getId(), ApiCommandResourceType.Host.toString(), 0);
    }

    private boolean hasBackupAgentInstallFailureEvent(long hostId) {
        return eventDao.existsByTypeAndResource(EventTypes.EVENT_BACKUP_AGENT_INSTALL, hostId, ApiCommandResourceType.Host.toString());
    }

    @Override
    public boolean importBackupPlan(final Long zoneId, final String retentionPeriod, final String externalId) {
        final AblestackCommvaultClient client = getClient(zoneId);
        // 선택한 백업 정책의 RPO 편집 Commvault API 호출
        String type = "deleteRpo";
        String taskId = client.getScheduleTaskId(type, externalId);
        if (taskId != null) {
            String subTaskId = client.getSubTaskId(taskId);
            if (subTaskId != null) {
                boolean result = client.deleteSchedulePolicy(taskId, subTaskId);
                if (!result) {
                    throw new CloudRuntimeException("Failed to delete schedule policy commvault api");
                }
            }
        } else {
            throw new CloudRuntimeException("Failed to get plan details schedule task id commvault api");
        }
        // 선택한 백업 정책의 보존 기간 변경 Commvault API 호출
        type = "updateRpo";
        String planEntity = client.getScheduleTaskId(type, externalId);
        JSONObject jsonObject = new JSONObject(planEntity);
        String planType = String.valueOf(jsonObject.get("planType"));
        String planName = String.valueOf(jsonObject.get("planName"));
        String planSubtype = String.valueOf(jsonObject.get("planSubtype"));
        String planId = String.valueOf(jsonObject.get("planId"));
        JSONObject entityInfo = jsonObject.getJSONObject("entityInfo");
        String companyId = String.valueOf(entityInfo.get("companyId"));
        String storagePolicyId = client.getStoragePolicyId(planName);
        if (storagePolicyId == null) {
            throw new CloudRuntimeException("Failed to get plan storage policy id commvault api");
        }
        boolean result = client.getStoragePolicyDetails(planId, storagePolicyId, retentionPeriod);
        if (result) {
            // 호스트에 선택한 백업 정책 설정 Commvault API 호출
            String path = "/";
            List<HostVO> Hosts = hostDao.findByDataCenterId(zoneId);
            for (final HostVO host : Hosts) {
                String backupSetId = client.getDefaultBackupSetId(host.getName());
                if (backupSetId != null) {
                    if (!client.setBackupSet(path, planType, planName, planSubtype, planId, companyId, backupSetId)) {
                        throw new CloudRuntimeException("Failed to setting backup plan for client commvault api");
                    }
                }
            }
            return true;
        } else {
            throw new CloudRuntimeException("Failed to edit plan schedule retention period commvault api");
        }
    }

    @Override
    public boolean updateBackupPlan(final Long zoneId, final String retentionPeriod, final String externalId) {
        final AblestackCommvaultClient client = getClient(zoneId);
        String type = "updateRpo";
        String planEntity = client.getScheduleTaskId(type, externalId);
        JSONObject jsonObject = new JSONObject(planEntity);
        String planType = String.valueOf(jsonObject.get("planType"));
        String planName = String.valueOf(jsonObject.get("planName"));
        String planSubtype = String.valueOf(jsonObject.get("planSubtype"));
        String planId = String.valueOf(jsonObject.get("planId"));
        JSONObject entityInfo = jsonObject.getJSONObject("entityInfo");
        String companyId = String.valueOf(entityInfo.get("companyId"));
        String storagePolicyId = client.getStoragePolicyId(planName);
        if (storagePolicyId == null) {
            throw new CloudRuntimeException("Failed to get plan storage policy id commvault api");
        }
        return client.getStoragePolicyDetails(planId, storagePolicyId, retentionPeriod);
    }

    private static String getUrlDomain(String url) throws URISyntaxException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URI.MalformedURIException e) {
            throw new CloudRuntimeException("Failed to cast URI");
        }

        return uri.getHost();
    }

    private AblestackCommvaultClient getClient(final Long zoneId) {
        try {
            return new AblestackCommvaultClient(CommvaultUrl.valueIn(zoneId), CommvaultUsername.valueIn(zoneId), CommvaultPassword.valueIn(zoneId),
                    CommvaultValidateSSLSecurity.valueIn(zoneId), CommvaultApiRequestTimeout.valueIn(zoneId));
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException("Failed to parse Commvault API URL: " + e.getMessage());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            LOG.error("Failed to build Commvault API client due to: ", e);
        }
        throw new CloudRuntimeException("Failed to build Commvault API client");
    }

    protected Ternary<String, String, String> getKVMHyperisorCredentials(HostVO host) {

        String username = null;
        String password = null;

        if (host != null && host.getHypervisorType() ==  Hypervisor.HypervisorType.KVM) {
            hostDao.loadDetails(host);
            password = host.getDetail("password");
            username = host.getDetail("username");
        }
        if ( password == null  || username == null) {
            throw new CloudRuntimeException("Cannot find login credentials for HYPERVISOR " + Objects.requireNonNull(host).getUuid());
        }

        return new Ternary<>(username, password, null);
    }

    private void cleanupBackupPathOnStageHost(String clientName, String path, boolean forced, String vmName, String checkpointName,
            String cleanupCheckpointNames, String diskPaths) {
        HostVO stageHost = hostDao.findByName(clientName);
        if (stageHost == null) {
            throw new CloudRuntimeException(String.format("Unable to find stage host [%s] for backup cleanup", clientName));
        }
        AblestackDeleteBackupCommand command = new AblestackDeleteBackupCommand(path, null, null, null, forced);
        final int commandTimeout = BackupCommandTimeout.value();
        if (commandTimeout > 0) {
            command.setWait(commandTimeout);
        }
        command.setBackupProvider("ablestack-commvault");
        command.setVmName(vmName);
        command.setCheckpointName(checkpointName);
        command.setCleanupCheckpointNames(cleanupCheckpointNames);
        command.setDiskPaths(diskPaths);
        try {
            BackupAnswer answer = (BackupAnswer) agentManager.send(stageHost.getId(), command);
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(String.format("Failed to delete Commvault backup path on host %s due to: %s",
                        stageHost.getName(), answer != null ? answer.getDetails() : "no answer received"));
            }
        } catch (AgentUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact backend control plane to delete Commvault backup");
        } catch (OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation to delete Commvault backup timed out, please try again");
        }
    }

    public static boolean isRetentionExpired(String retainedUntil) {
        if (retainedUntil == null || retainedUntil.trim().isEmpty() || "null".equals(retainedUntil)) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(retainedUntil) * 1000L;
            Date retainedDate = new Date(timestamp);
            Date currentDate = new Date();
            return currentDate.after(retainedDate);
        } catch (Exception e) {
            LOG.info("parsing error: " + e.getMessage());
            return false;
        }
    }

    public static boolean versionCheck(String csVersionInfo) {
        // 버전 체크 기준 : 11 SP32.89
        if (csVersionInfo == null) {
            throw new CloudRuntimeException("commvault version must not be null.");
        }
        String v = csVersionInfo.trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
            v = v.substring(1, v.length() - 1);
        }
        Matcher m = VERSION_PATTERN.matcher(v);
        if (!m.matches()) {
            throw new CloudRuntimeException("Unexpected commvault version format: " + csVersionInfo);
        }
        int major = Integer.parseInt(m.group(1));
        int fr = Integer.parseInt(m.group(2));
        int mt = Integer.parseInt(m.group(3));
        if (major < BASE_MAJOR) {
            throw new CloudRuntimeException("The major version of the commvault you are trying to connect to is low. Supports versions 11.32.89 and higher.");
        } else if (major == BASE_MAJOR && fr < BASE_FR) {
            throw new CloudRuntimeException("The feature release version of the commvault you are trying to connect to is low. Supports versions 11.32.89 and higher.");
        } else if (major == BASE_MAJOR && fr == BASE_FR && mt < BASE_MT) {
            throw new CloudRuntimeException("The maintenance version of the commvault you are trying to connect to is low. Supports versions 11.32.89 and higher.");
        }
        return true;
    }

    @Override
    public Pair<Boolean, String> restoreBackupToVM(Long backupId, String vmName) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'restoreBackupToVM'");
    }
}
