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
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshot;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.backup.dao.BackupDao;
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
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.apache.logging.log4j.LogManager;

import javax.inject.Inject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.apache.cloudstack.backup.BackupManager.BackupFrameworkEnabled;

public class BXBackupProvider extends AdapterBase implements BackupProvider, Configurable {
    private static final Logger LOG = LogManager.getLogger(BXBackupProvider.class);
    private static final Pattern RESTORE_VM_NAME_PATTERN = Pattern.compile("^[a-z0-9-]+$"); // VM 이름은 소문자, 숫자, 하이픈만 허용
    private static final long BACKUP_PROCESS_STATUS_TIMEOUT_MS = 650000L;
    private static final long BACKUP_PROCESS_STATUS_RETRY_INTERVAL_MS = 5000L;
    private final Map<String, JSONObject> backupMonitoringCache = new ConcurrentHashMap<>();

    ConfigKey<String> BXUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.bx.url", "https://localhost:10222/bxweb",
            "BX Command Center API URL.", true, ConfigKey.Scope.Zone);

    ConfigKey<Integer> BXBackupRestoreMountTimeout = new ConfigKey<>("Advanced", Integer.class,
            "bx.backup.restore.mount.timeout",
            "30",
            "Timeout in seconds after which backup repository mount for restore fails.",
            true,
            BackupFrameworkEnabled.key());

    ConfigKey<String> BXBackupClient = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.bx.client", "ABLESTACK.Mold",
            "BX Command Client Name", true, ConfigKey.Scope.Zone);

    ConfigKey<String> BXBackupClientMaxjob = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.bx.client.maxjob", "1",
            "BX Client Max Jobs", true, ConfigKey.Scope.Zone);

    ConfigKey<String> BXBackupClientIp = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.bx.client.ip", "localhost",
            "BX Command Client IP", true, ConfigKey.Scope.Zone);

    ConfigKey<String> BXBackupClientId = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.bx.client.id", "admin",
            "BX Command Client ID", true, ConfigKey.Scope.Zone);

    ConfigKey<String> BXBackupClientPort = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.bx.client.port", "9440",
            "BX Command Client Port", true, ConfigKey.Scope.Zone);

    @Inject
    private BackupDao backupDao;

    @Inject
    private BackupOfferingDao backupOfferingDao;

    @Inject
    private BackupRepositoryDao backupRepositoryDao;

    @Inject
    private BackupRepositoryService backupRepositoryService;

    @Inject
    private HostDao hostDao;

    @Inject
    private VolumeDao volumeDao;

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

    // backup offering에서 policy(external_id) 가져오기
    private String getBackupPolicy(VirtualMachine vm) {
        BackupOfferingVO vmBackupOffering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (vmBackupOffering == null) {
            throw new CloudRuntimeException(String.format("Unable to find backup offering for VM [%s]", vm.getUuid()));
        }
        return vmBackupOffering.getExternalId();
    }

    private String getVmBackupTarget(VirtualMachine vm) {
        return String.format("AbleStack:VM-IMAGE|%s:%s", vm.getHostName(), vm.getUuid());
    }

    private JSONObject getFirstDataObject(JSONObject responseJson) {
        JSONObject dataObject = responseJson.optJSONObject("data");
        if (dataObject != null) {
            return dataObject;
        }

        JSONArray dataArray = responseJson.optJSONArray("data");
        if (dataArray != null && !dataArray.isEmpty()) {
            JSONObject firstDataObject = dataArray.optJSONObject(0);
            if (firstDataObject != null) {
                return firstDataObject;
            }
        }

        throw new CloudRuntimeException(String.format("BX API response does not contain a valid data object: %s", responseJson));
    }

    protected Host getVMHypervisorHostForBackup(VirtualMachine vm) {
        Long hostId = vm.getHostId();
        if (hostId == null && VirtualMachine.State.Running.equals(vm.getState())) {
            throw new CloudRuntimeException(String.format("Unable to find the hypervisor host for %s. Make sure the virtual machine is running", vm.getHostName()));
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
    public Pair<Boolean, Backup> takeBackup(final VirtualMachine vm, Boolean quiesceVM, boolean isolated, Long backupScheduleId) {
        if (CollectionUtils.isNotEmpty(vmSnapshotDao.findByVmAndByType(vm.getId(), VMSnapshot.Type.DiskAndMemory))) {
            logger.debug("BX backup provider cannot take backups of a VM [{}] with disk-and-memory VM snapshots. Restoring the backup will corrupt any newer disk-and-memory " +
                    "VM snapshots.", vm);
            throw new CloudRuntimeException(String.format("Cannot take backup of VM [%s] as it has disk-and-memory VM snapshots.", vm.getUuid()));
        }

        final Date creationDate = new Date();
        final String backupPath = String.format("%s/%s", vm.getInstanceName(),
                new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(creationDate));

        BackupVO backupVO = createBackupObject(vm, backupPath);

        // bx backup
        String jobStatus = createBackup(vm);
        if (jobStatus.equalsIgnoreCase("SUCCESS")) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                logger.debug("Failed to sleep while polling for BX task status due to: ", e);
            }

            // 백업 모니터링 목록 조회
            String monitoringStatus = getBackupProcessStatus(vm);
            if (monitoringStatus == null) {
                LOG.error("Failed to get backup monitoring status for VM {}: result is null", vm.getUuid());
                backupVO.setStatus(Backup.Status.Failed);
                backupDao.remove(backupVO.getId());
                return new Pair<>(false, null);
            }

            JSONObject monitoringStatusJson = new JSONObject(monitoringStatus);

            if (monitoringStatusJson.getString("STATUS").equalsIgnoreCase("SUCCESS")) {
                JSONObject monitoringDataJson = getFirstDataObject(monitoringStatusJson);
                final String externalId = backupPath + "," + monitoringDataJson.getString("BJOBRES_JOBID"); //JOBID
                final long backupSize = Long.parseLong(monitoringDataJson.getString("BJOBRES_BKSIZE").replaceAll("[^0-9]", ""));    //백업 크기
                BackupVO backupToUpdate = backupDao.findById(backupVO.getId());
                if (backupToUpdate == null) {
                    throw new CloudRuntimeException("Failed to find backup to update");
                }

                backupToUpdate.setDate(new Date());
                backupToUpdate.setSize(backupSize);
                backupToUpdate.setStatus(Backup.Status.BackedUp);
                backupToUpdate.setExternalId(externalId);
                // backupVO.setSize(Long.parseLong(monitoringDataJson.getString("BSIZE").replaceAll("[^0-9]", "")));
                // backupVO.setExternalId(backupPath + "," + monitoringStatusJson.getString("BJOBQ_JOBID"));

                if (backupDao.update(backupToUpdate.getId(), backupToUpdate)) {
                    LOG.info(">>>>> backup updated id: {}, externalId: {}, size: {}",
                            backupToUpdate.getId(), backupToUpdate.getExternalId(), backupToUpdate.getSize());
                    return new Pair<>(true, backupToUpdate);
                // if (backupDao.update(backupVO.getId(), backupVO)) {
                    // return new Pair<>(true, backupVO);
                } else {
                    throw new CloudRuntimeException("Failed to monitoring backup");
                }
            } else {
                logger.error("Failed to take backup for VM {}: {}", vm.getHostName(), monitoringStatus);
                backupVO.setStatus(Backup.Status.Failed);
                backupDao.remove(backupVO.getId());
                return new Pair<>(false, null);
            }
        } else {
            LOG.error("Failed to take backup for VM " + vm.getHostName() + " to create backup job commvault api");
            backupVO.setStatus(Backup.Status.Failed);
            backupDao.remove(backupVO.getId());
            return new Pair<>(false, null);
        }
    }

    // bx backup api(backup_manual_start : 수동백업 실행)
    private String createBackup(VirtualMachine vm) {
        try {
            backupMonitoringCache.remove(getBackupMonitoringCacheKey(vm));

            String paramString =
                "f_option=" + URLEncoder.encode("backup_manual_start", StandardCharsets.UTF_8) +
                "&POLICY=" + URLEncoder.encode(getBackupPolicy(vm), StandardCharsets.UTF_8) +
                "&CLIENT=" + URLEncoder.encode(BXBackupClient.value(), StandardCharsets.UTF_8) +
                "&OBJ=" + URLEncoder.encode(getVmBackupTarget(vm), StandardCharsets.UTF_8) +
                "&OBJTYPE=" + URLEncoder.encode("AbleStack", StandardCharsets.UTF_8) +
                "&KIND=" + URLEncoder.encode("2", StandardCharsets.UTF_8);

            String jsonResponse = executeRequest("backup_manual_start", "POST", paramString);
            if (jsonResponse != null) {
                return new JSONObject(jsonResponse).optString("STATUS", null);
            }
            return null;
        } catch (IOException e) {
            LOG.error("Failed to request BX backup api(backup_manual_start) due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 백업 모니터링 목록 조회(bx backup api : backup_process_list)
    private String getBackupProcessStatus(VirtualMachine vm) {
        final String cacheKey = getBackupMonitoringCacheKey(vm);
        JSONObject cachedProcessStatus = backupMonitoringCache.get(cacheKey);
        final long deadline = System.currentTimeMillis() + BACKUP_PROCESS_STATUS_TIMEOUT_MS;

        Date now = new Date();
        String currentTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(now.getTime() - 60 * 1000L));  // 1분 전부터 조회
        String nextDayTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(now.getTime() + 24 * 60 * 60 * 1000L));    // 24시간 후까지 조회
        while (System.currentTimeMillis() < deadline) {
            String paramString =
                "f_option=" + URLEncoder.encode("backup_process_list", StandardCharsets.UTF_8) +
                "&backup_target=" + URLEncoder.encode(getVmBackupTarget(vm), StandardCharsets.UTF_8) +
                "&backup_strttime=" + URLEncoder.encode(currentTime, StandardCharsets.UTF_8) +
                "&backup_endtime=" + URLEncoder.encode(nextDayTime, StandardCharsets.UTF_8);

            try {
                String backupProcessStatus = executeRequest("backup_process_list", "GET", paramString);
                /*
                    백업 진행 중인 경우
                    {
                        "STATUS": "SUCCESS",
                        "total": 1,
                        "data": [
                            {
                                "BJOBQ_BKOBJ": "AbleStack:VM-IMAGE|test11:cf12135e-7b8a-4d41-9fcc-42a26902fec4",
                                "ROWNUM": "1",
                                "BJOBQ_POLICYNAME": "ABLESTACK",
                                "BJOBQ_JOBID": "BF26040700013",
                                "BJOBQ_DBINFO": "test11:cf12135e-7b8a-4d41-9fcc-42a26902fec4",
                                "BJOBQ_SVRNAME": "UNISOFT-MasterVM-Rocky9",
                                "BJOBQ_STATUS": "진행",
                                "BJOBQ_CLNTNAME": "Diplo-202603231451"
                            },
                            {
                                "BJOBQ_BKOBJ": "AbleStack:DATADISK|a8b272bc-d51b-4c81-836b-3557ade1acf1",
                                "ROWNUM": "3",
                                "BJOBQ_POLICYNAME": "ABLESTACK",
                                "BJOBQ_JOBID": "OF26040700022",
                                "BJOBQ_DBINFO": "a8b272bc-d51b-4c81-836b-3557ade1acf1",
                                "BJOBQ_SVRNAME": "UNISOFT-MasterVM-Rocky9",
                                "BJOBQ_STATUS": "대기",
                                "BJOBQ_CLNTNAME": "Diplo-202603231451"
                            }
                        ],
                        "records": 2,
                        "page": "1"
                    },
                    백업 완료된 경우
                    {
                        "STATUS": "SUCCESS",
                        "total": 0,
                        "data": [],
                        "records": 0,
                        "page": "1"
                    }
                */
                if (backupProcessStatus == null) {
                    LOG.warn("backup_process_list returned null for VM {}", vm.getUuid());
                    continue;
                }

                JSONObject backupProcessStatusJson = new JSONObject(backupProcessStatus);
                JSONArray dataArray = backupProcessStatusJson.optJSONArray("data");
                if (!"SUCCESS".equalsIgnoreCase(backupProcessStatusJson.optString("STATUS"))) {
                    return backupProcessStatusJson.toString();
                }

                if (dataArray != null && dataArray.length() > 0) {
                    cachedProcessStatus = new JSONObject(backupProcessStatusJson.toString());
                    backupMonitoringCache.put(cacheKey, cachedProcessStatus);
                } else {
                    // 백업 완료
                    if (cachedProcessStatus != null) {
                        // 백업된 이미지 조회
                        String backupImageList = backupImageList(vm, currentTime, nextDayTime);
                        JSONObject backupImageResult = new JSONObject(backupImageList);
                        if (backupImageResult.optString("STATUS").equalsIgnoreCase("SUCCESS")) {
                            return backupImageResult.toString();
                        }
                    }
                    return backupProcessStatusJson.toString();
                }
            } catch (IOException e) {
                LOG.error("Failed to request BX backup api(backup_process_list) due to : ", e);
                checkResponseTimeOut(e);
            }

            try {
                Thread.sleep(BACKUP_PROCESS_STATUS_RETRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Interrupted while waiting for BX backup monitoring status", e);
                break;
            }
        }

        return cachedProcessStatus != null ? cachedProcessStatus.toString() : null;
    }

    private String getBackupMonitoringCacheKey(VirtualMachine vm) {
        return vm.getUuid() != null ? vm.getUuid() : String.valueOf(vm.getId());
    }

    // 백업 모니터링(bx backup api : backup_monitoring_list)
    private String getBackupMonitoringStatus(VirtualMachine vm) {
        final long deadline = System.currentTimeMillis() + BACKUP_PROCESS_STATUS_TIMEOUT_MS;
        int attempt = 1;

        while (System.currentTimeMillis() < deadline) {
            try {
                String paramString =
                    "f_option=" + URLEncoder.encode("backup_monitoring_list", StandardCharsets.UTF_8) +
                    "&f_policy=" + URLEncoder.encode(getBackupPolicy(vm), StandardCharsets.UTF_8) +
                    "&f_client=" + URLEncoder.encode(BXBackupClient.value(), StandardCharsets.UTF_8) +
                    "&f_target=" + URLEncoder.encode(getVmBackupTarget(vm), StandardCharsets.UTF_8);//임시

                String monitoringResponse = executeRequest("backup_monitoring_list", "GET", paramString);
                if (monitoringResponse == null) {
                    LOG.warn("backup_monitoring_list returned null for VM {} on attempt {}", vm.getUuid(), attempt);
                } else {
                    JSONObject monitoringResponseJson = new JSONObject(monitoringResponse);
                    if ("SUCCESS".equalsIgnoreCase(monitoringResponseJson.optString("STATUS"))) {
                        return monitoringResponseJson.toString();
                    }
                }
            } catch (IOException e) {
                LOG.error("Failed to request BX backup api(backup_monitoring_list) due to : ", e);
                checkResponseTimeOut(e);
            }

            if (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(BACKUP_PROCESS_STATUS_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("Interrupted while waiting for BX backup monitoring status", e);
                    break;
                }
            }

            attempt++;
        }

        return null;
    }

    // 백업대상 등록(bx backup api : backup_target_reg_ablestack)
    @Override
    public boolean assignVMToBackupOffering(VirtualMachine vm, BackupOffering backupOffering) {
        try {
            String paramString =
                "f_option=" + URLEncoder.encode("backup_target_reg_ablestack", StandardCharsets.UTF_8) +
                "&polname=" + URLEncoder.encode(getBackupPolicy(vm), StandardCharsets.UTF_8) +
                "&client=" + URLEncoder.encode(BXBackupClient.value(), StandardCharsets.UTF_8) +
                "&client_maxjob=" + URLEncoder.encode(BXBackupClientMaxjob.value(), StandardCharsets.UTF_8) +
                "&client_ip=" + URLEncoder.encode(BXBackupClientIp.value(), StandardCharsets.UTF_8) +
                "&client_id=" + URLEncoder.encode(BXBackupClientId.value(), StandardCharsets.UTF_8) +
                "&client_port=" + URLEncoder.encode(BXBackupClientPort.value(), StandardCharsets.UTF_8) +
                "&ablestackServerImage=" + URLEncoder.encode(vm.getHostName(), StandardCharsets.UTF_8) +
                "&ablestackServerId=" + URLEncoder.encode(vm.getUuid(), StandardCharsets.UTF_8) +
                "&contrabass_bx_backup=" + URLEncoder.encode("1", StandardCharsets.UTF_8) +
                "&devtype=" + URLEncoder.encode("DISK", StandardCharsets.UTF_8);

            String jsonResponse = executeRequest("backup_target_reg_ablestack", "POST", paramString);
            if (jsonResponse != null) {
                return "SUCCESS".equalsIgnoreCase(new JSONObject(jsonResponse).optString("STATUS", null));
            }
            return false;
        } catch (IOException e) {
            LOG.error("Failed to request BX backup api(backup_target_reg_ablestack) due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // 이미지 리스트 조회(bx backup api : backup_image_list)
    public String backupImageList(VirtualMachine vm, String currentTime, String nextDayTime) {
        try {
            String paramString =
                "f_option=" + URLEncoder.encode("backup_image_list", StandardCharsets.UTF_8) +
                "&backup_policy=" + URLEncoder.encode(getBackupPolicy(vm), StandardCharsets.UTF_8) +
                "&backup_client=" + URLEncoder.encode(BXBackupClient.value(), StandardCharsets.UTF_8) +
                "&backup_date_1=" + URLEncoder.encode(currentTime, StandardCharsets.UTF_8) +
                "&backup_date_2=" + URLEncoder.encode(nextDayTime, StandardCharsets.UTF_8) +
                "&backup_target=" + URLEncoder.encode(getVmBackupTarget(vm), StandardCharsets.UTF_8);

            return executeRequest("backup_image_list", "GET", paramString);
        } catch (IOException e) {
            LOG.error("Failed to request BX backup api(backup_image_list) due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 복원작업리스트 조회(bx backup api : backup_image_ablestack_list)
    public String restoreImageList(String jobId) {
        try {
            String paramString =
                "f_option=" + URLEncoder.encode("backup_image_ablestack_list", StandardCharsets.UTF_8) +
                "&f_jobid=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8);

            return executeRequest("backup_image_ablestack_list", "GET", paramString);
        } catch (IOException e) {
            LOG.error("Failed to request BX backup api(backup_image_ablestack_list) due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    public Pair<Boolean, String> restoreBackupToVM(VirtualMachine vm, Backup backup, String hostIp, String dataStoreUuid, boolean quickRestore) {
        return new Pair<>(false, null);
    }

    // 복원(bx backup api : restore_manual_ablestack)
    public Pair<Boolean, String> restoreBackupToVM(Long backupId, String vmName) {
        // vmName validation
        if (vmName == null || vmName.trim().isEmpty()) {
            LOG.warn("Failed to restore backup [{}] due to blank vmName", backupId);
            return new Pair<>(false, "vmName is required");
        }
        vmName = vmName.trim();

        if (!RESTORE_VM_NAME_PATTERN.matcher(vmName).matches()) {
            LOG.warn("Failed to restore backup [{}] due to invalid vmName format [{}]", backupId, vmName);
            return new Pair<>(false, "vmName can only contain lowercase letters, numbers, and hyphens");
        }

        final BackupVO backup = backupDao.findById(backupId);
        final String externalId = backup.getExternalId();
        String jobId = externalId.substring(externalId.lastIndexOf(',') + 1);

        // 복원작업리스트 조회
        String restoreImage = restoreImageList(jobId);
        JSONObject restoreImageJson = new JSONObject(restoreImage);

        // 복원 (OS, DATADISK)
        if ("SUCCESS".equalsIgnoreCase(restoreImageJson.optString("STATUS"))) {
            JSONArray dataArray = restoreImageJson.optJSONArray("data");
            if (dataArray != null && dataArray.length() > 0) {
                JSONArray responses = new JSONArray();
                boolean allSuccess = true;
                String lastErrorMessage = null;

                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject restoreImageDataJson = dataArray.getJSONObject(i);

                    try {
                        String paramString =
                            "f_option=" + URLEncoder.encode("restore_manual_ablestack", StandardCharsets.UTF_8) +
                            "&rename=" + URLEncoder.encode(vmName, StandardCharsets.UTF_8) +
                            "&bkclnt=" + URLEncoder.encode(BXBackupClient.value(), StandardCharsets.UTF_8) +
                            "&bkjobid=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8) +//임시
                            "&jobid=" + URLEncoder.encode(restoreImageDataJson.getString("JOBID"), StandardCharsets.UTF_8) +
                            "&bktype=" + URLEncoder.encode(restoreImageDataJson.getString("BKOBJ"), StandardCharsets.UTF_8) +
                            "&bkobj=" + URLEncoder.encode(restoreImageDataJson.getString("FILENAME"), StandardCharsets.UTF_8);

                        String jsonResponse = executeRequest("restore_manual_ablestack", "POST", paramString);
                        if (jsonResponse != null) {
                            JSONObject responseJson = new JSONObject(jsonResponse);
                            responses.put(responseJson);
                            if (!"SUCCESS".equalsIgnoreCase(responseJson.optString("STATUS", null))) {
                                allSuccess = false;
                                lastErrorMessage = responseJson.optString("ERROR_MESSAGE",
                                        String.format("Failed to restore item JOBID=%s", restoreImageDataJson.optString("JOBID")));
                            }
                        } else {
                            allSuccess = false;
                            lastErrorMessage = String.format("Empty response for restore item JOBID=%s",
                                    restoreImageDataJson.optString("JOBID"));
                        }
                    } catch (IOException e) {
                        LOG.error("Failed to request BX backup api(restore_manual_ablestack) due to : ", e);
                        checkResponseTimeOut(e);
                        allSuccess = false;
                        lastErrorMessage = e.getMessage();
                    }
                }

                if (allSuccess) {
                    JSONObject successResponse = responses.length() > 0
                            ? responses.getJSONObject(0)
                            : new JSONObject().put("STATUS", "SUCCESS").put("ERROR_MESSAGE", "");
                    return new Pair<>(true, successResponse.toString());
                }

                JSONObject resultJson = new JSONObject()
                        .put("STATUS", "FAILED")
                        .put("responses", lastErrorMessage);
                return new Pair<>(false, resultJson.toString());
            }
        } else {
            LOG.warn("Failed to retrieve restore image list for backup [{}]: {}", backupId, restoreImageJson);
            return new Pair<>(false, restoreImageJson.toString());
        }

        return new Pair<>(false, "No restore image data found");
    }

    // 백업대상 리스트(bx backup api : backup_target_list)
    public String backupTargetList(VirtualMachine vm) {
        try {
            String paramString =
                "f_option=" + URLEncoder.encode("backup_target_list", StandardCharsets.UTF_8) +
                "&backup_policy=" + URLEncoder.encode(getBackupPolicy(vm), StandardCharsets.UTF_8);

            return executeRequest("backup_target_list", "GET", paramString);
        } catch (IOException e) {
            LOG.error("Failed to request BX backup api(backup_target_list) due to : ", e);
            checkResponseTimeOut(e);
        }
        return null;
    }

    // 백업대상 삭제(bx backup api : backup_target_del)
    @Override
    public boolean removeVMFromBackupOffering(VirtualMachine vm) {
        // 백업대상 리스트 조회(전체조회됨)
        String backupTarget = backupTargetList(vm);
        JSONObject backupTargetJson = new JSONObject(backupTarget);
        JSONArray backupTargetDataArray = backupTargetJson.optJSONArray("data");
        if (backupTargetDataArray == null || backupTargetDataArray.length() == 0) {
            LOG.warn("No backup target found for VM {}: {}", vm.getUuid(), backupTargetJson);
            return false;
        }

        // 백업대상 리스트 중 VM과 매칭되는 데이터 찾기
        JSONObject backupTargetDataJson = null;
        for (int i = 0; i < backupTargetDataArray.length(); i++) {
            JSONObject candidate = backupTargetDataArray.optJSONObject(i);
            if (candidate == null) {
                continue;
            }

            String dbInfo = candidate.optString("POLICYIDX_DBINFO");
            int separatorIndex = dbInfo.lastIndexOf(':');
            String targetVmUuid = separatorIndex >= 0 ? dbInfo.substring(separatorIndex + 1) : dbInfo;

            if (vm.getUuid().equals(targetVmUuid)) {
                backupTargetDataJson = candidate;
                break;
            }
        }

        try {
            String paramString =
                "f_option=" + URLEncoder.encode("backup_target_del", StandardCharsets.UTF_8) +
                "&f_policy=" + URLEncoder.encode(getBackupPolicy(vm), StandardCharsets.UTF_8) +
                "&f_targetid=" + URLEncoder.encode(backupTargetDataJson.getString("POLICYIDX_ID"), StandardCharsets.UTF_8) +
                "&f_obj=" + URLEncoder.encode(backupTargetDataJson.getString("POLICYIDX_BKOBJECT"), StandardCharsets.UTF_8);

            String jsonResponse = executeRequest("backup_target_del", "POST", paramString);
            if (jsonResponse != null) {
                return "SUCCESS".equalsIgnoreCase(new JSONObject(jsonResponse).optString("STATUS", null));
            }
            return false;
        } catch (IOException e) {
            LOG.error("Failed to request BX backup api(backup_target_del) due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // 이미지 삭제(bx backup api : backup_image_del)
    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        try {
            final String externalId = backup.getExternalId();
            String jobId = externalId.substring(externalId.lastIndexOf(',') + 1);

            String paramString =
                "f_option=" + URLEncoder.encode("backup_image_del", StandardCharsets.UTF_8) +
                "&f_jobid=" + URLEncoder.encode(jobId, StandardCharsets.UTF_8);

            String jsonResponse = executeRequest("backup_image_del", "POST", paramString);
            if (jsonResponse != null) {
                return "SUCCESS".equalsIgnoreCase(new JSONObject(jsonResponse).optString("STATUS", null));
            }
            return false;
        } catch (IOException e) {
            LOG.error("Failed to request BX backup api(backup_image_del) due to : ", e);
            checkResponseTimeOut(e);
        }
        return false;
    }

    // BX API 요청 처리
    private String executeRequest(String apiName, String method, String paramString) throws IOException {
        HttpURLConnection connection = null;
        try {
            boolean isPost = "POST".equalsIgnoreCase(method);
            URL url = isPost ? new URL(BXUrl.value()) : new URL(BXUrl.value() + "?" + paramString);
            LOG.info("Calling BX backup API({}) with method={} url={} paramString={}", apiName, method, url, paramString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(isPost);

            if (isPost) {
                byte[] requestBody = paramString.getBytes(StandardCharsets.UTF_8);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setRequestProperty("Content-Length", String.valueOf(requestBody.length));
                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(requestBody);
                }
            }

            int responseCode = connection.getResponseCode();
            String responseBody = readHttpResponse(connection, responseCode).trim();
            if (responseCode == HttpURLConnection.HTTP_OK && responseBody.startsWith("{")) {
                return responseBody;
            }

            LOG.warn("BX backup API({}) returned unexpected response. code={}, body=[{}]", apiName, responseCode, responseBody);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // HTTP 응답 읽기 (성공/오류 모두 처리)
    private String readHttpResponse(HttpURLConnection connection, int responseCode) throws IOException {
        InputStream stream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
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

        return backupDao.persist(backup);
    }

    public static String extractJobIdsFromJsonString(String jsonString) {
        Pattern pattern = Pattern.compile("\"jobIds\"\\s*:\\s*\\[(.*?)\\]");
        Matcher matcher = pattern.matcher(jsonString);
        if (matcher.find()) {
            String jobIdsArray = matcher.group(1);
            String jobId = jobIdsArray.replaceAll("\"", "").replaceAll("\\s", "");
            return jobId.split(",")[0];
        }
        return null;
    }

    private void checkResponseTimeOut(final Exception e) {
        if (e instanceof ConnectTimeoutException || e instanceof SocketTimeoutException) {
            throw new ServerApiException(ApiErrorCode.RESOURCE_UNAVAILABLE_ERROR, "Commvault API operation timed out, please try again.");
        }
    }

    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup, boolean quickRestore, Long hostId) {
        return restoreVMBackup(vm, backup).first();
    }

    private Pair<Boolean, String> restoreVMBackup(VirtualMachine vm, Backup backup) {
        List<String> backedVolumesUUIDs = backup.getBackedUpVolumes().stream()
                .sorted(Comparator.comparingLong(Backup.VolumeInfo::getDeviceId))
                .map(Backup.VolumeInfo::getUuid)
                .collect(Collectors.toList());

        List<VolumeVO> restoreVolumes = volumeDao.findByInstance(vm.getId()).stream()
                .sorted(Comparator.comparingLong(VolumeVO::getDeviceId))
                .collect(Collectors.toList());

        LOG.debug("Restoring vm {} from backup {} on the BX Backup Provider", vm, backup);
        BackupRepository backupRepository = getBackupRepository(backup);

        final Host host = getVMHypervisorHost(vm);
        RestoreBackupCommand restoreCommand = new RestoreBackupCommand();
        restoreCommand.setBackupPath(backup.getExternalId());
        restoreCommand.setBackupRepoType(backupRepository.getType());
        restoreCommand.setBackupRepoAddress(backupRepository.getAddress());
        restoreCommand.setMountOptions(backupRepository.getMountOptions());
        restoreCommand.setVmName(vm.getName());
        restoreCommand.setBackupVolumesUUIDs(backedVolumesUUIDs);
        Pair<List<PrimaryDataStoreTO>, List<String>> volumePoolsAndPaths = getVolumePoolsAndPaths(restoreVolumes);
        restoreCommand.setRestoreVolumePools(volumePoolsAndPaths.first());
        restoreCommand.setRestoreVolumePaths(volumePoolsAndPaths.second());
        restoreCommand.setVmExists(vm.getRemoved() == null);
        restoreCommand.setVmState(vm.getState());
        restoreCommand.setMountTimeout(BXBackupRestoreMountTimeout.value());

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
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, Backup.VolumeInfo backupVolumeInfo, String hostIp, String dataStoreUuid, Pair<String, VirtualMachine.State> vmNameAndState, VirtualMachine targetVm, boolean quickRestore) {
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
        final HostVO hostVO = hostDao.findByIp(hostIp);

        LOG.debug("Restoring vm volume {} from backup {} on the BX Backup Provider", backupVolumeInfo, backup);
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

        RestoreBackupCommand restoreCommand = new RestoreBackupCommand();
        restoreCommand.setBackupPath(backup.getExternalId());
        restoreCommand.setBackupRepoType(backupRepository.getType());
        restoreCommand.setBackupRepoAddress(backupRepository.getAddress());
        restoreCommand.setVmName(vmNameAndState.first());
        restoreCommand.setRestoreVolumePaths(Collections.singletonList(String.format("%s/%s", getVolumePathPrefix(pool), volumeUUID)));
        DataStore dataStore = dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
        restoreCommand.setRestoreVolumePools(Collections.singletonList(dataStore != null ? (PrimaryDataStoreTO)dataStore.getTO() : null));
        restoreCommand.setDiskType(backupVolumeInfo.getType().name().toLowerCase(Locale.ROOT));
        restoreCommand.setMountOptions(backupRepository.getMountOptions());
        restoreCommand.setVmExists(null);
        restoreCommand.setVmState(vmNameAndState.second());
        // restoreCommand.setRestoreVolumeUUID(backupVolumeInfo.getUuid());
        restoreCommand.setMountTimeout(BXBackupRestoreMountTimeout.value());
        restoreCommand.setCacheMode(cacheMode);

        BackupAnswer answer;
        try {
            answer = (BackupAnswer) agentManager.send(hostVO.getId(), restoreCommand);
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

    private Optional<Backup.VolumeInfo> getBackedUpVolumeInfo(List<Backup.VolumeInfo> backedUpVolumes, String volumeUuid) {
        return backedUpVolumes.stream()
                .filter(v -> v.getUuid().equals(volumeUuid))
                .findFirst();
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
        final List<BackupRepository> repositories = backupRepositoryDao.listByZoneAndProvider(zoneId, getName());
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
        final List<BackupRepository> repositories = backupRepositoryDao.listByZoneAndProvider(zoneId, getName());
        final Host host = resourceManager.findOneRandomRunningHostByHypervisor(Hypervisor.HypervisorType.KVM, zoneId);
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
        final List<BackupOffering> offerings = new ArrayList<>();
        // for (final BackupRepository repository : repositories) {
        offerings.add(new BxBackupOffering("ABLESTACK", "ABLESTACK"));  // bx offering plan
        // }
        return offerings;
    }

    @Override
    public boolean isValidProviderOffering(Long zoneId, String uuid) {
        return true;
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                BXUrl,
                BXBackupRestoreMountTimeout,
                BXBackupClient,
                BXBackupClientMaxjob,
                BXBackupClientIp,
                BXBackupClientId,
                BXBackupClientPort
        };
    }

    @Override
    public String getName() {
        return "bx";
    }

    @Override
    public String getDescription() {
        return "BX Backup Plugin";
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    @Override
    public void syncBackups(VirtualMachine vm) {
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
    public Boolean crossZoneInstanceCreationEnabled(BackupOffering backupOffering) {
        final BackupRepository backupRepository = backupRepositoryDao.findByBackupOfferingId(backupOffering.getId());
        if (backupRepository == null) {
            throw new CloudRuntimeException("Backup repository not found for the backup offering" + backupOffering.getName());
        }
        return Boolean.TRUE.equals(backupRepository.crossZoneInstanceCreationEnabled());
    }
}
