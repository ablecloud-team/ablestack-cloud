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
import com.cloud.dc.dao.ClusterDao;
import com.cloud.domain.Domain;
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
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.User;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.backup.commvault.CommvaultClient;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDaoImpl;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.xml.utils.URI;
import org.json.JSONObject;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;

import static org.apache.cloudstack.backup.BackupManager.BackupFrameworkEnabled;

public class CommvaultBackupProvider extends AdapterBase implements BackupProvider, Configurable {

    private static final Logger LOG = LogManager.getLogger(CommvaultBackupProvider.class);
    private static final String RM_COMMAND = "rm -rf %s";
    private static final int BASE_MAJOR = 11;
    private static final int BASE_FR = 32;
    private static final int BASE_MT = 89;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\s*SP\\s*(\\d+)(?:\\.(\\d+))?$", Pattern.CASE_INSENSITIVE);
    private static final String COMMVAULT_DIRECTORY = "/tmp/mold/backup";

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

    private static ConfigKey<Integer> CommvaultRestoreTimeout = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.commvault.restore.timeout", "600",
            "Commvault B&R API restore backup timeout in seconds.", true, ConfigKey.Scope.Zone);

    private static ConfigKey<Integer> CommvaultTaskPollInterval = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.commvault.task.poll.interval", "5",
            "The time interval in seconds when the management server polls for Commvault task status.", true, ConfigKey.Scope.Zone);

    private static ConfigKey<Integer> CommvaultTaskPollMaxRetry = new ConfigKey<>("Advanced", Integer.class,
            "backup.plugin.commvault.task.poll.max.retry", "120",
            "The max number of retrying times when the management server polls for Commvault task status.", true, ConfigKey.Scope.Zone);

    private ConfigKey<Boolean> CommvaultClientVerboseLogs = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.commvault.client.verbosity", "false",
            "Produce Verbose logs in Hypervisor", true, ConfigKey.Scope.Zone);

    private ConfigKey<Integer> CommvaultBackupRestoreTimeout = new ConfigKey<>("Advanced", Integer.class,
            "commvault.backup.restore.timeout",
            "30",
            "Timeout in seconds after which qemu-img execute when restoring",
            true,
            BackupFrameworkEnabled.key());

    @Inject
    private BackupDao backupDao;

    @Inject
    private BackupOfferingDao backupOfferingDao;

    @Inject
    private HostDao hostDao;

    @Inject
    private ClusterDao clusterDao;

    @Inject
    private VolumeDao volumeDao;

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
    private PrimaryDataStoreDao primaryDataStoreDao;

    @Inject
    private ConfigurationDao configDao;

    @Inject
    private BackupManager backupManager;

    @Inject
    ResourceManager resourceManager;

    @Inject
    private DiskOfferingDao diskOfferingDao;

    private Long getClusterIdFromRootVolume(VirtualMachine vm) {
        VolumeVO rootVolume = volumeDao.getInstanceRootVolume(vm.getId());
        StoragePoolVO rootDiskPool = primaryDataStoreDao.findById(rootVolume.getPoolId());
        if (rootDiskPool == null) {
            return null;
        }
        return rootDiskPool.getClusterId();
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
        final Host vmHost = getVMHypervisorHostForBackup(vm);
        final HostVO vmHostVO = hostDao.findById(vmHost.getId());
        if (CollectionUtils.isNotEmpty(vmSnapshotDao.findByVmAndByType(vm.getId(), VMSnapshot.Type.DiskAndMemory))) {
            LOG.debug("Commvault backup provider cannot take backups of a VM [{}] with disk-and-memory VM snapshots. Restoring the backup will corrupt any newer disk-and-memory " +
                    "VM snapshots.", vm);
            throw new CloudRuntimeException(String.format("Cannot take backup of VM [%s] as it has disk-and-memory VM snapshots.", vm.getUuid()));
        }

        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        // 백업 중인 작업 조회
        final CommvaultClient client = getClient(vm.getDataCenterId());
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

        final Date creationDate = new Date();
        final String backupPath = String.format("%s/%s/%s", COMMVAULT_DIRECTORY, vm.getInstanceName(),
                new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(creationDate));

        BackupVO backupVO = createBackupObject(vm, backupPath);
        CommvaultTakeBackupCommand command = new CommvaultTakeBackupCommand(vm.getInstanceName(), backupPath);
        command.setQuiesce(quiesceVM);

        if (VirtualMachine.State.Stopped.equals(vm.getState())) {
            List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
            vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
            Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(vmVolumes);
            command.setVolumePools(volumePoolsAndPaths.first());
            command.setVolumePaths(volumePoolsAndPaths.second());
        }

        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(vmHost.getId(), command);
        } catch (AgentUnavailableException e) {
            LOG.error("Unable to contact backend control plane to initiate backup for VM {}", vm.getInstanceName());
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.remove(backupVO.getId());
            throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
        } catch (OperationTimedoutException e) {
            LOG.error("Operation to initiate backup timed out for VM {}", vm.getInstanceName());
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.remove(backupVO.getId());
            throw new CloudRuntimeException("Operation to initiate backup timed out, please try again");
        }

        if (answer != null && answer.getResult()) {
            int sshPort = NumbersUtil.parseInt(configDao.getValue("kvm.ssh.port"), 22);
            Ternary<String, String, String> credentials = getKVMHyperisorCredentials(vmHostVO);
            String cmd = String.format(RM_COMMAND, backupPath);
            // 생성된 백업 폴더 경로로 해당 백업 세트의 백업 콘텐츠 경로 업데이트
            String clientId = client.getClientId(vmHost.getName());
            String subClientEntity = client.getSubclient(clientId, vm.getInstanceName());
            if (subClientEntity == null) {
                LOG.error("Failed to take backup for VM " + vm.getInstanceName() + " to get subclient info commvault api");
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
                boolean upResult = client.updateBackupSet(backupPath, subclientId, clientId, planId, applicationId, backupsetId, instanceId, subclientName, backupsetName);
                if (upResult) {
                    String planName = client.getPlanName(planId);
                    String storagePolicyId = client.getStoragePolicyId(planName);
                    if (planName == null || storagePolicyId == null) {
                        LOG.error("Failed to take backup for VM " + vm.getInstanceName() + " to get storage policy id commvault api");
                    } else {
                        // 백업 실행
                        String jobId = client.createBackup(subclientId, storagePolicyId, displayName, commCellName, clientId, companyId, companyName, instanceName, appName, applicationId, clientName, backupsetId, instanceId, subclientGUID, subclientName, csGUID, backupsetName);
                        if (jobId != null) {
                            String jobStatus = client.getJobStatus(jobId);
                            String externalId = backupPath + "," + jobId;
                            if (jobStatus.equalsIgnoreCase("Completed")) {
                                String jobDetails = client.getJobDetails(jobId);
                                if (jobDetails != null) {
                                    JSONObject jsonObject2 = new JSONObject(jobDetails);
                                    String endTime = String.valueOf(jsonObject2.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").get("endTime"));
                                    long timestamp = Long.parseLong(endTime) * 1000L;
                                    Date endDate = new Date(timestamp);
                                    SimpleDateFormat formatterDateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                                    String formattedString = formatterDateTime.format(endDate);
                                    String size = String.valueOf(jsonObject2.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").get("sizeOfApplication"));
                                    String type = String.valueOf(jsonObject2.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").get("backupType"));
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
                                    List<Volume> vols = new ArrayList<>(volumeDao.findByInstance(vm.getId()));
                                    backupVO.setBackedUpVolumes(backupManager.createVolumeInfoFromVolumes(vols));
                                    if (backupDao.update(backupVO.getId(), backupVO)) {
                                        executeDeleteBackupPathCommand(vmHostVO, credentials.first(), credentials.second(), sshPort, cmd);
                                        return new Pair<>(true, backupVO);
                                    } else {
                                        executeDeleteBackupPathCommand(vmHostVO, credentials.first(), credentials.second(), sshPort, cmd);
                                        throw new CloudRuntimeException("Failed to update backup");
                                    }
                                } else {
                                    backupVO.setExternalId(externalId);
                                    LOG.error("Failed to take backup for VM " + vm.getInstanceName() + " to get details job commvault api");
                                }
                            } else {
                                backupVO.setExternalId(externalId);
                                LOG.error("Failed to take backup for VM " + vm.getInstanceName() + " to create backup job status is " + jobStatus);
                            }
                        } else {
                            LOG.error("Failed to take backup for VM " + vm.getInstanceName() + " to create backup job commvault api");
                        }
                    }
                } else {
                    LOG.error("Failed to take backup for VM " + vm.getInstanceName() + " to update backupset content path commvault api");
                }
            }
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.remove(backupVO.getId());
            executeDeleteBackupPathCommand(vmHostVO, credentials.first(), credentials.second(), sshPort, cmd);
            return new Pair<>(false, null);
        } else {
            LOG.error("Failed to take backup for VM {}: {}", vm.getInstanceName(), answer != null ? answer.getDetails() : "No answer received");
            if (answer.getNeedsCleanup()) {
                LOG.error("Backup cleanup failed for VM {}. Leaving the backup in Error state.", vm.getInstanceName());
                backupVO.setStatus(Backup.Status.Error);
                backupDao.update(backupVO.getId(), backupVO);
            } else {
                backupVO.setStatus(Backup.Status.Failed);
                backupDao.remove(backupVO.getId());
            }
            return new Pair<>(false, null);
        }
    }

    private BackupVO createBackupObject(VirtualMachine vm, String backupPath) {
        BackupVO backup = new BackupVO();
        backup.setVmId(vm.getId());
        backup.setExternalId(backupPath);
        backup.setType("FULL");
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
        Map<String, String> details = backupManager.getBackupDetailsFromVM(vm);
        backup.setDetails(details);

        return backupDao.persist(backup);
    }

    // 백업에서 새 인스턴스 생성
    @Override
    public Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid) {
        return restoreVMBackup(vm, backup);
    }

    // 가상머신 백업 복원
    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup) {
        return restoreVMBackup(vm, backup).first();
    }

    private Pair<Boolean, String> restoreVMBackup(VirtualMachine vm, Backup backup) {
        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        final CommvaultClient client = getClient(vm.getDataCenterId());
        final String externalId = backup.getExternalId();
        String jobId = externalId.substring(externalId.lastIndexOf(',') + 1).trim();
        final String path = externalId.substring(0, externalId.lastIndexOf(','));
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
        LOG.info(String.format("Restoring vm %s from backup %s on the Commvault Backup Provider", vm, backup));
        // 복원 실행
        String jobId2 = client.restoreFullVM(subclientId, displayName, backupsetGUID, clientId, companyId, companyName, instanceName, appName, applicationId, clientName, backupsetId, instanceId, backupsetName, commCellId, endTime, path);
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
                // 가상머신이 실행중인 호스트 정의
                final Host vmHost = getVMHypervisorHost(vm);
                final HostVO vmHostVO = hostDao.findById(vmHost.getId());
                CommvaultRestoreBackupCommand restoreCommand = new CommvaultRestoreBackupCommand();
                LOG.info(path);
                restoreCommand.setBackupPath(path);
                restoreCommand.setVmName(vm.getName());
                restoreCommand.setBackupVolumesUUIDs(backedVolumesUUIDs);
                Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(restoreVolumes);
                restoreCommand.setRestoreVolumePools(volumePoolsAndPaths.first());
                restoreCommand.setRestoreVolumePaths(volumePoolsAndPaths.second());
                restoreCommand.setVmExists(vm.getRemoved() == null);
                restoreCommand.setVmState(vm.getState());
                restoreCommand.setTimeout(CommvaultBackupRestoreTimeout.value());
                // 복원된 호스트와 가상머신이 실행중인 호스트가 같은 경우 null, 다른 경우 추가
                restoreCommand.setHostName(restoreHost.getId() == vmHost.getId() ? null : restoreHost.getName());

                BackupAnswer answer;
                try {
                    answer = (BackupAnswer) agentManager.send(vmHost.getId(), restoreCommand);
                } catch (AgentUnavailableException e) {
                    throw new CloudRuntimeException("Unable to contact backend control plane to initiate backup");
                } catch (OperationTimedoutException e) {
                    throw new CloudRuntimeException("Operation to restore backup timed out, please try again");
                }
                if (!answer.getResult()) {
                    int sshPort = NumbersUtil.parseInt(configDao.getValue("kvm.ssh.port"), 22);
                    Ternary<String, String, String> credentials = getKVMHyperisorCredentials(vmHostVO);
                    String command = String.format(RM_COMMAND, path);
                    executeDeleteBackupPathCommand(vmHostVO, credentials.first(), credentials.second(), sshPort, command);
                    if (restoreHost.getId() != vmHost.getId()) {
                        credentials = getKVMHyperisorCredentials(restoreHostVO);
                        command = String.format(RM_COMMAND, path);
                        executeDeleteBackupPathCommand(restoreHostVO, credentials.first(), credentials.second(), sshPort, command);
                    }
                }
                return new Pair<>(answer.getResult(), answer.getDetails());
            } else {
                throw new CloudRuntimeException("Failed to restore Full VM commvault api resulted in " + jobStatus);
            }
        } else {
            throw new CloudRuntimeException("Failed to restore Full VM commvault api");
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
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid, Pair<String, VirtualMachine.State> vmNameAndState) {
        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        final String externalId = backup.getExternalId();
        final Long zoneId = backup.getZoneId();
        final CommvaultClient client = getClient(zoneId);
        String jobId = externalId.substring(externalId.lastIndexOf(',') + 1).trim();
        final String path = externalId.substring(0, externalId.lastIndexOf(','));
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
        // 복원 실행
        String jobId2 = client.restoreFullVM(subclientId, displayName, backupsetGUID, clientId, companyId, companyName, instanceName, appName, applicationId, clientName, backupsetId, instanceId, backupsetName, commCellId, endTime, path);
        if (jobId2 != null) {
            String jobStatus = client.getJobStatus(jobId2);
            if (jobStatus.equalsIgnoreCase("Completed")) {
                final int sshPort = NumbersUtil.parseInt(configDao.getValue("kvm.ssh.port"), 22);
                final VolumeVO volume = volumeDao.findByUuid(backupVolumeInfo.getUuid());
                final DiskOffering diskOffering = diskOfferingDao.findByUuid(backupVolumeInfo.getDiskOfferingId());
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
                final StoragePoolVO pool = primaryDataStoreDao.findByUuid(dataStoreUuid);
                // 백업 볼륨 복원 및 연결 시 연결할 가상머신이 실행중인 경우 해당 호스트, 정지중인 경우 랜덤 호스트 정의백업
                final HostVO vmHost = hostDao.findByIp(hostIp);
                final HostVO vmHostVO = hostDao.findById(vmHost.getId());
                // 복원된 호스트 정의
                final HostVO restoreHost = hostDao.findByName(clientName);
                final HostVO restoreHostVO = hostDao.findById(restoreHost.getId());
                LOG.info(String.format("Restoring volume %s from backup %s on the Commvault Backup Provider", volume.getUuid(), backup));
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

                CommvaultRestoreBackupCommand restoreCommand = new CommvaultRestoreBackupCommand();
                restoreCommand.setBackupPath(path);
                restoreCommand.setVmName(vmNameAndState.first());
                restoreCommand.setRestoreVolumePaths(Collections.singletonList(String.format("%s/%s", getVolumePathPrefix(pool), volumeUUID)));
                DataStore dataStore = dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
                restoreCommand.setRestoreVolumePools(Collections.singletonList(dataStore != null ? (PrimaryDataStoreTO)dataStore.getTO() : null));
                restoreCommand.setDiskType(backupVolumeInfo.getType().name().toLowerCase(Locale.ROOT));
                restoreCommand.setVmExists(null);
                restoreCommand.setVmState(vmNameAndState.second());
                restoreCommand.setRestoreVolumeUUID(backupVolumeInfo.getUuid());
                restoreCommand.setTimeout(CommvaultBackupRestoreTimeout.value());
                restoreCommand.setCacheMode(cacheMode);
                // 복원된 호스트와 가상머신이 실행중인 호스트가 같은 경우 null, 다른 경우 추가
                restoreCommand.setHostName(restoreHost.getId() == vmHost.getId() ? null : restoreHost.getName());

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
                    if (restoreHost.getId() != vmHost.getId()) {
                        Ternary<String, String, String> credentials = getKVMHyperisorCredentials(restoreHostVO);
                        String command = String.format(RM_COMMAND, path);
                        executeDeleteBackupPathCommand(restoreHostVO, credentials.first(), credentials.second(), sshPort, command);
                    }
                    return new Pair<>(answer.getResult(), answer.getDetails());
                } else {
                    Ternary<String, String, String> credentials = getKVMHyperisorCredentials(vmHostVO);
                    String command = String.format(RM_COMMAND, path);
                    executeDeleteBackupPathCommand(vmHostVO, credentials.first(), credentials.second(), sshPort, command);
                    if (restoreHost.getId() != vmHost.getId()) {
                        credentials = getKVMHyperisorCredentials(restoreHostVO);
                        command = String.format(RM_COMMAND, path);
                        executeDeleteBackupPathCommand(restoreHostVO, credentials.first(), credentials.second(), sshPort, command);
                    }
                }
            } else {
                LOG.error("Failed to restore backup for VM " + vmNameAndState.first() + " to restore backup job status is " + jobStatus);
            }
        } else {
            LOG.error("Failed to restore backup for VM " + vmNameAndState.first() + " to restore backup job commvault api");
        }
        return new Pair<>(false, null);
    }

    private Optional<Backup.VolumeInfo> getBackedUpVolumeInfo(List<Backup.VolumeInfo> backedUpVolumes, String volumeUuid) {
        return backedUpVolumes.stream()
                .filter(v -> v.getUuid().equals(volumeUuid))
                .findFirst();
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        final Long zoneId = backup.getZoneId();
        final String externalId = backup.getExternalId();
        String jobId = externalId.substring(externalId.lastIndexOf(',') + 1).trim();
        String path = externalId.substring(0, externalId.lastIndexOf(','));
        final CommvaultClient client = getClient(zoneId);
        String jobDetails = client.getJobDetails(jobId);
        if (jobDetails != null) {
            JSONObject jsonObject = new JSONObject(jobDetails);
            String subclientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("subclientId"));
            String applicationId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("applicationId"));
            String instanceId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("instanceId"));
            String clientId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientId"));
            String clientName = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("clientName"));
            String backupsetId = String.valueOf(jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").get("backupsetId"));
            return client.deleteBackup(subclientId, applicationId, applicationId, clientId, clientName, backupsetId, path);
        } else {
            throw new CloudRuntimeException("Failed to request backup job detail commvault api");
        }
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
        final CommvaultClient client = getClient(vm.getDataCenterId());
        final Host host = getVMHypervisorHostForBackup(vm);
        String clientId = client.getClientId(host.getName());
        String applicationId = client.getApplicationId(clientId);
        return client.createBackupSet(vm.getInstanceName(), applicationId, clientId, backupOffering.getExternalId());
    }

    @Override
    public boolean removeVMFromBackupOffering(VirtualMachine vm) {
        final CommvaultClient client = getClient(vm.getDataCenterId());
        List<HostVO> Hosts = hostDao.findByDataCenterId(vm.getDataCenterId());
        boolean allDeleted = true;
        for (final HostVO host : Hosts) {
            if (host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                String backupSetId = client.getVmBackupSetId(host.getName(), vm.getInstanceName());
                if (backupSetId != null) {
                    boolean deleted = client.deleteBackupSet(backupSetId);
                    if (!deleted) {
                        allDeleted = false;
                        LOG.error("Failed to delete backupSetId: " + backupSetId +" for VM: " + vm.getInstanceName());
                    }
                }
            }
        }
        return allDeleted;
    }

    // 하위 클라이언트 삭제 시 백업본 데이터는 그대로 남아있지만, 해당 하위 클라이언트가 삭제되었기 때문에 스케줄도 삭제시켜야하며
    // 남아있는 백업본 데이터는 mold에서 관리하지 않고, commvault 의 plan 보존기간에 따라 데이터 에이징 됨.
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
                CommvaultClientVerboseLogs
        };
    }

    @Override
    public String getName() {
        return "commvault";
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
        final CommvaultClient client = getClient(vm.getDataCenterId());
        for (final Backup backup: backupDao.listByVmId(vm.getDataCenterId(), vm.getId())) {
            String externalId = backup.getExternalId();
            String jobId = externalId.substring(externalId.lastIndexOf(',') + 1).trim();
            String path = externalId.substring(0, externalId.lastIndexOf(','));
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
                        backupDao.remove(backup.getId());
                    }
                }
            }
        }
        return;
    }

    @Override
    public boolean checkBackupAgent(final Long zoneId) {
        Map<String, String> checkResult = new HashMap<>();
        final CommvaultClient client = getClient(zoneId);
        String csVersionInfo = client.getCvtVersion();
        boolean version = versionCheck(csVersionInfo);
        if (version) {
            List<HostVO> Hosts = hostDao.findByDataCenterId(zoneId);
            for (final HostVO host : Hosts) {
                if (host.getStatus() == Status.Up && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                    String checkHost = client.getClientId(host.getName());
                    if (checkHost == null) {
                        return false;
                    } else {
                        boolean installJob = client.getInstallActiveJob(host.getPrivateIpAddress());
                        boolean checkInstall = client.getClientProps(checkHost);
                        if (installJob || !checkInstall) {
                            if (!checkInstall) {
                                LOG.error("The host is registered with the client, but the readiness status is not normal and you must manually check the client status.");
                            }
                            return false;
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean installBackupAgent(final Long zoneId) {
        Map<String, String> failResult = new HashMap<>();
        final CommvaultClient client = getClient(zoneId);
        List<HostVO> Hosts = hostDao.findByDataCenterId(zoneId);
        for (final HostVO host : Hosts) {
            if (host.getStatus() == Status.Up && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                String commCell = client.getCommcell();
                JSONObject jsonObject = new JSONObject(commCell);
                String commCellId = String.valueOf(jsonObject.get("commCellId"));
                String commServeHostName = String.valueOf(jsonObject.get("commCellName"));
                Ternary<String, String, String> credentials = getKVMHyperisorCredentials(host);
                boolean installJob = true;
                LOG.info("checking for install agent on the Commvault Backup Provider in host " + host.getPrivateIpAddress());
                // 설치가 진행중인 호스트가 있는지 확인
                while (installJob) {
                    installJob = client.getInstallActiveJob(host.getName());
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        LOG.error("checkBackupAgent get install active job result sleep interrupted error");
                    }
                }
                String checkHost = client.getClientId(host.getName());
                // 호스트가 클라이언트에 등록되지 않은 경우
                if (checkHost == null) {
                    String jobId = client.installAgent(host.getPrivateIpAddress(), commCellId, commServeHostName, credentials.first(), credentials.second());
                    if (jobId != null) {
                        String jobStatus = client.getJobStatus(jobId);
                        if (!jobStatus.equalsIgnoreCase("Completed")) {
                            LOG.error("installing agent on the Commvault Backup Provider failed jogId : " + jobId + " , jobStatus : " + jobStatus);
                            ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM, Domain.ROOT_DOMAIN, EventTypes.EVENT_HOST_AGENT_INSTALL,
                                "Failed install the commvault client agent on the host : " + host.getPrivateIpAddress(), User.UID_SYSTEM, ApiCommandResourceType.Host.toString());
                            failResult.put(host.getPrivateIpAddress(), jobId);
                        }
                    } else {
                        return false;
                    }
                } else {
                    // 호스트가 클라이언트에는 등록되었지만 구성이 정상적으로 되지 않은 경우 준비 상태 체크
                    boolean checkInstall = client.getClientCheckReadiness(checkHost);
                    if (!checkInstall) {
                        LOG.error("The host is registered with the client, but the readiness status is not normal and you must manually check the client status.");
                        ActionEventUtils.onActionEvent(User.UID_SYSTEM, Account.ACCOUNT_ID_SYSTEM, Domain.ROOT_DOMAIN, EventTypes.EVENT_HOST_AGENT_INSTALL,
                            "Failed check readiness the commvault client agent on the host : " + host.getPrivateIpAddress(), User.UID_SYSTEM, ApiCommandResourceType.Host.toString());
                        return false;
                    }
                }
            }
        }
        if (!failResult.isEmpty()) {
            return false;
        }
        return true;
    }

    @Override
    public boolean importBackupPlan(final Long zoneId, final String retentionPeriod, final String externalId) {
        final CommvaultClient client = getClient(zoneId);
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
        final CommvaultClient client = getClient(zoneId);
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

    private CommvaultClient getClient(final Long zoneId) {
        try {
            return new CommvaultClient(CommvaultUrl.valueIn(zoneId), CommvaultUsername.valueIn(zoneId), CommvaultPassword.valueIn(zoneId),
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

    private boolean executeDeleteBackupPathCommand(HostVO host, String username, String password, int port, String command) {
        try {
            Pair<Boolean, String> response = SshHelper.sshExecute(host.getPrivateIpAddress(), port,
                    username, null, password, command, 120000, 120000, 3600000);

            if (!response.first()) {
                LOG.error(String.format("failed on HYPERVISOR %s due to: %s", host, response.second()));
            } else {
                return true;
            }
        } catch (final Exception e) {
            throw new CloudRuntimeException(String.format("Failed to delete backup path on host %s due to: %s", host.getName(), e.getMessage()));
        }
        return false;
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

}