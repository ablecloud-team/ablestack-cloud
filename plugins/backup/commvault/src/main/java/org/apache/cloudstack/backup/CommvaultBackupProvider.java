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

import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.UserAccount;
import com.cloud.user.AccountService;
import com.cloud.utils.PropertiesUtil;
import com.cloud.utils.server.ServerProperties;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.backup.commvault.CommvaultClient;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDaoImpl;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.apache.xml.utils.URI;
import org.json.JSONObject;
import org.json.XML;
import javax.inject.Inject;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.HttpsURLConnection;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Mac;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.StringTokenizer;
import java.util.StringJoiner;
import java.util.Properties;
import org.apache.commons.codec.binary.Base64;
import java.util.Collections;
import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;

public class CommvaultBackupProvider extends AdapterBase implements BackupProvider, Configurable {

    private static final Logger LOG = LogManager.getLogger(CommvaultBackupProvider.class);

    public ConfigKey<String> CommvaultUrl = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.commvault.url", "https://localhost/commandcenter/api",
            "Commvault Command Center API URL.", true, ConfigKey.Scope.Zone);

    private final ConfigKey<String> CommvaultUsername = new ConfigKey<>("Advanced", String.class,
            "backup.plugin.commvault.username", "admin",
            "Commvault Command Center API username.", true, ConfigKey.Scope.Zone);

    private final ConfigKey<String> CommvaultPassword = new ConfigKey<>("Secure", String.class,
            "backup.plugin.commvault.password", "password",
            "Commvault Command Center API password.", true, ConfigKey.Scope.Zone);

    private final ConfigKey<Boolean> CommvaultValidateSSLSecurity = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.commvault.validate.ssl", "false",
            "Validate the SSL certificate when connecting to Commvault Command Center API service.", true, ConfigKey.Scope.Zone);

    private final ConfigKey<Integer> CommvaultApiRequestTimeout = new ConfigKey<>("Advanced", Integer.class,
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

    private final ConfigKey<Boolean> CommvaultClientVerboseLogs = new ConfigKey<>("Advanced", Boolean.class,
            "backup.plugin.commvault.client.verbosity", "false",
            "Produce Verbose logs in Hypervisor", true, ConfigKey.Scope.Zone);

    @Inject
    private BackupDao backupDao;

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
    private ManagementServerHostDao msHostDao;

    @Inject
    private AccountService accountService;

    @Inject
    private UserVmJoinDao userVmJoinDao;

    @Inject
    private VolumeDao volsDao;

    private static String getUrlDomain(String url) throws URISyntaxException {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URI.MalformedURIException e) {
            throw new CloudRuntimeException("Failed to cast URI");
        }

        return uri.getHost();
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

    protected HostVO getLastVMHypervisorHost(VirtualMachine vm) {
        HostVO host;
        Long hostId = vm.getLastHostId();

        if (hostId == null) {
            LOG.debug("Cannot find last host for vm. This should never happen, please check your database.");
            return null;
        }
        host = hostDao.findById(hostId);

        if (host.getStatus() == Status.Up) {
            return host;
        } else {
            // Try to find a host in the same cluster
            List<HostVO> altClusterHosts = hostDao.findHypervisorHostInCluster(host.getClusterId());
            for (final HostVO candidateClusterHost : altClusterHosts) {
                if ( candidateClusterHost.getStatus() == Status.Up ) {
                    LOG.debug(String.format("Found Host %s", candidateClusterHost));
                    return candidateClusterHost;
                }
            }
        }
        // Try to find a Host in the zone
        List<HostVO> altZoneHosts = hostDao.findByDataCenterId(host.getDataCenterId());
        for (final HostVO candidateZoneHost : altZoneHosts) {
            if ( candidateZoneHost.getStatus() == Status.Up && candidateZoneHost.getHypervisorType() == Hypervisor.HypervisorType.KVM ) {
                LOG.debug("Found Host " + candidateZoneHost);
                return candidateZoneHost;
            }
        }
        return null;
    }

    protected HostVO getRunningVMHypervisorHost(VirtualMachine vm) {

        HostVO host;
        Long hostId = vm.getHostId();

        if (hostId == null) {
            throw new CloudRuntimeException("Unable to find the HYPERVISOR for " + vm.getName() + ". Make sure the virtual machine is running");
        }

        host = hostDao.findById(hostId);

        return host;
    }

    protected String getVMHypervisorCluster(HostVO host) {

        return clusterDao.findById(host.getClusterId()).getName();
    }

    protected Ternary<String, String, String> getKVMHyperisorCredentials(HostVO host) {

        String username = null;
        String password = null;

        if (host != null && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
            hostDao.loadDetails(host);
            password = host.getDetail("password");
            username = host.getDetail("username");
        }
        if ( password == null  || username == null) {
            throw new CloudRuntimeException("Cannot find login credentials for HYPERVISOR " + Objects.requireNonNull(host).getUuid());
        }

        return new Ternary<>(username, password, null);
    }

    private String executeBackupCommand(HostVO host, String username, String password, String command) {
        return null;
    }

    private boolean executeRestoreCommand(HostVO host, String username, String password, String command) {
        return false;
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

    @Override
    public boolean checkBackupAgent(final Long zoneId) {
        final CommvaultClient client = getClient(zoneId);
        List<HostVO> Hosts = hostDao.findByDataCenterId(zoneId);
        for (final HostVO host : Hosts) {
            if (host.getStatus() == Status.Up && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                String checkHost = client.getClientId(host.getName());
                // 하나라도 없다면 false 리턴하도록 추후 로직 변경해야함
                if (checkHost != null) {
                    return true;
                }
            }
        }
        return false;
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
        type = "updateRPO";
        String planEntity = client.getScheduleTaskId(type, externalId);
        JSONObject jsonObject = new JSONObject(planEntity);
        String planType = jsonObject.getString("planType");
        String planName = jsonObject.getString("planName");
        String planSubtype = jsonObject.getString("planSubtype");
        String planId = jsonObject.getString("planId");
        String companyId = jsonObject.getJSONObject("entityInfo").getString("companyId");
        String storagePoolId = client.getStoragePoolId(planId);
        LOG.info("updateRetentionPeriod:::::::::::::::::::::::");
        LOG.info(planId);
        LOG.info(storagePoolId);
        LOG.info(retentionPeriod);
        boolean result = client.updateRetentionPeriod(planId, storagePoolId, retentionPeriod);
        LOG.info("result:::::::::::::::::::::::");
        LOG.info(result);
        if (result) {
            // 호스트에 선택한 백업 정책 설정 Commvault API 호출
            String path = "/";
            List<HostVO> Hosts = hostDao.findByDataCenterId(zoneId);
            for (final HostVO host : Hosts) {
                if (host.getStatus() == Status.Up && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                    String backupSetId = client.getDefaultBackupSetId(host.getName());
                    LOG.info("backupSetId:::::::::::::::::::::::");
                    LOG.info(backupSetId);
                    if (!client.setBackupSet(path, planType, planName, planSubtype, planId, companyId, backupSetId)) {
                        throw new CloudRuntimeException("commvault client backup schedule rpo setting err");
                    }
                }
            }
            return true;
        } else {
            // 문구 변경 필요
            throw new CloudRuntimeException("commvault plan schedule rpo delete err");
        }
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
    public boolean assignVMToBackupOffering(VirtualMachine vm, BackupOffering backupOffering) {
        HostVO hostVO;
        final CommvaultClient client = getClient(vm.getDataCenterId());
        if (vm.getState() == VirtualMachine.State.Running) {
            hostVO = getRunningVMHypervisorHost(vm);
        } else {
            hostVO = getLastVMHypervisorHost(vm);
        }
        String clientId = client.getClientId(hostVO.getName());
        String applicationId = client.getApplicationId(clientId);
        return client.createBackupSet(vm.getInstanceName(), applicationId, backupOffering.getExternalId(), clientId);
    }

    @Override
    public boolean removeVMFromBackupOffering(VirtualMachine vm) {
        final CommvaultClient client = getClient(vm.getDataCenterId());
        List<HostVO> Hosts = hostDao.findByDataCenterId(vm.getDataCenterId());
        for (final HostVO host : Hosts) {
            if (host.getStatus() == Status.Up && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                String backupSetId = client.getVmBackupSetId(host.getName(), vm.getInstanceName());
                if (backupSetId != null) {
                    return client.deleteBackupSet(backupSetId);
                }
            }
        }
        return false;
    }

    @Override
    public boolean restoreVMFromBackup(VirtualMachine vm, Backup backup) {
        List<Backup.VolumeInfo> backedVolumes = backup.getBackedUpVolumes();
        List<VolumeVO> volumes = backedVolumes.stream().map(volume -> volumeDao.findByUuid(volume.getUuid())).collect(Collectors.toList());
        final Host host = getLastVMHypervisorHost(vm);
        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        if (vm.getState() != VirtualMachine.State.Stopped && vm.getState() != VirtualMachine.State.Shutdown) {
            throw new CloudRuntimeException("The VM the specified disk is attached to is not in the shutdown state.");
        }
        final CommvaultClient client = getClient(vm.getDataCenterId());
        final String externalId = backup.getExternalId();
        String[] external = externalId.split("/");
        String path = external[0];
        String jobId = external[1];
        String jobDetails = client.getJobDetails(jobId);
        JSONObject jsonObject = new JSONObject(jobDetails);
        String endTime = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").getString("endTime");
        String subclientId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("subclientId");
        String displayName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("displayName");
        String clientId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("clientId");
        String companyId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").getString("companyId");
        String companyName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").getString("companyName");
        String instanceName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("instanceName");
        String appName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("appName");
        String applicationId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("applicationId");
        String clientName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("clientName");
        String backupsetId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("backupsetId");
        String instanceId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("instanceId");
        String backupsetName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("backupsetName");
        String commCellId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("commcell").getString("commCellId");
        String backupsetGUID = client.getVmBackupSetGuid(clientName, backupsetName);
        LOG.info(String.format("Restoring vm %s from backup %s on the Commvault Backup Provider", vm, backup));
        String jobId2 = client.restoreFullVM(endTime, subclientId, displayName, backupsetGUID, clientId, companyId, companyName, instanceName, appName, applicationId, clientName, backupsetId, instanceId, backupsetName, commCellId, path);
        if (jobId2 != null) {
            //job 진행 체크 추가 필요
            String[] properties = getServerProperties();
            ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
            String moldUrl = properties[1] + "://" + msHost.getServiceIP() + ":" + properties[0] + "/client/api/";
            String moldMethod = "GET";
            String moldCommand = "revertSnapshot";
            UserAccount user = accountService.getActiveUserAccount("admin", 1L);
            String apiKey = user.getApiKey();
            String secretKey = user.getSecretKey();
            String snapshotId = backup.getSnapshotId();
            if (snapshotId != null || !snapshotId.isEmpty()) {
                String[] snapshots = snapshotId.split(",");
                for (int i=0; i < snapshots.length; i++) {
                    Map<String, String> snapshotParams = new HashMap<>();
                    snapshotParams.put("id", snapshots[i]);
                    moldRevertSnapshotAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapshotParams);
                    //결과에 따른 처리 추가 필요
                }
            }
        }
        return false;
    }

    @Override
    public Pair<Boolean, String> restoreBackedUpVolume(Backup backup, String volumeUuid, String hostIp, String dataStoreUuid, Pair<String, VirtualMachine.State> vmNameAndState) {
        List<Backup.VolumeInfo> backedVolumes = backup.getBackedUpVolumes();
        VolumeVO volume = volumeDao.findByUuid(volumeUuid);
        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        final String externalId = backup.getExternalId();
        final Long zoneId = backup.getZoneId();
        final CommvaultClient client = getClient(zoneId);
        String[] external = externalId.split("/");
        String path = external[0];
        String jobId = external[1];
        String jobDetails = client.getJobDetails(jobId);
        JSONObject jsonObject = new JSONObject(jobDetails);
        String endTime = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").getString("endTime");
        String subclientId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("subclientId");
        String displayName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("displayName");
        String clientId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("clientId");
        String companyId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").getString("companyId");
        String companyName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("company").getString("companyName");
        String instanceName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("instanceName");
        String appName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("appName");
        String applicationId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("applicationId");
        String clientName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("clientName");
        String backupsetId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("backupsetId");
        String instanceId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("instanceId");
        String backupsetName = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("subclient").getString("backupsetName");
        String commCellId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("commcell").getString("commCellId");
        String backupsetGUID = client.getVmBackupSetGuid(clientName, backupsetName);
        LOG.info(String.format("Restoring volume %s from backup %s on the Commvault Backup Provider", volumeUuid, backup));
        String result = client.restoreFullVM(endTime, subclientId, displayName, backupsetGUID, clientId, companyId, companyName, instanceName, appName, applicationId, clientName, backupsetId, instanceId, backupsetName, commCellId, path);
        if (result != null) {
            String[] properties = getServerProperties();
            ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
            String moldUrl = properties[1] + "://" + msHost.getServiceIP() + ":" + properties[0] + "/client/api/";
            String moldMethod = "GET";
            String moldCommand = "revertSnapshot";
            UserAccount user = accountService.getActiveUserAccount("admin", 1L);
            String apiKey = user.getApiKey();
            String secretKey = user.getSecretKey();
            String snapshotId = backup.getSnapshotId();
            if (snapshotId != null || !snapshotId.isEmpty()) {
                String[] snapshots = snapshotId.split(",");
                for (int i=0; i < snapshots.length; i++) {
                    Map<String, String> snapshotParams = new HashMap<>();
                    snapshotParams.put("id", snapshots[i]);
                    moldRevertSnapshotAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapshotParams);
                    //결과에 따른 처리 추가 필요
                }
            }
        } else {
            return null;
        }
        VMInstanceVO backupSourceVm = vmInstanceDao.findById(backup.getVmId());
        StoragePoolHostVO dataStore = storagePoolHostDao.findByUuid(dataStoreUuid);
        Long restoredVolumeDiskSize = 0L;
        // Find volume size  from backup vols
        for (Backup.VolumeInfo VMVolToRestore : backupSourceVm.getBackupVolumeList()) {
            if (VMVolToRestore.getUuid().equals(volumeUuid))
                restoredVolumeDiskSize = (VMVolToRestore.getSize());
        }
        VolumeVO restoredVolume = new VolumeVO(Volume.Type.DATADISK, null, backup.getZoneId(),
                backup.getDomainId(), backup.getAccountId(), 0, null,
                backup.getSize(), null, null, null);
        restoredVolume.setName("RV-"+volume.getName());
        restoredVolume.setProvisioningType(volume.getProvisioningType());
        restoredVolume.setUpdated(new Date());
        restoredVolume.setUuid(UUID.randomUUID().toString());
        restoredVolume.setRemoved(null);
        restoredVolume.setDisplayVolume(true);
        restoredVolume.setPoolId(dataStore.getPoolId());
        restoredVolume.setPath(restoredVolume.getUuid());
        restoredVolume.setState(Volume.State.Copying);
        restoredVolume.setSize(restoredVolumeDiskSize);
        restoredVolume.setDiskOfferingId(volume.getDiskOfferingId());
        try {
            volumeDao.persist(restoredVolume);
        } catch (Exception e) {
            throw new CloudRuntimeException("Unable to craft restored volume due to: "+e);
        }
        //복원 후 패스변경
        return null;
    }

    @Override
    public boolean takeBackup(VirtualMachine vm) {
        String hostName = null;
        try {
            String commvaultServer = getUrlDomain(CommvaultUrl.value());
        } catch (URISyntaxException e) {
            throw new CloudRuntimeException(String.format("Failed to convert API to HOST : %s", e));
        }
        // 클라이언트의 백업세트 조회하여 호스트 정의
        final CommvaultClient client = getClient(vm.getDataCenterId());
        String accessToken = client.getToken();
        List<HostVO> Hosts = hostDao.findByDataCenterId(vm.getDataCenterId());
        for (final HostVO host : Hosts) {
            if (host.getStatus() == Status.Up && host.getHypervisorType() == Hypervisor.HypervisorType.KVM) {
                String checkVm = client.getVmBackupSetId(host.getName(), vm.getInstanceName());
                if (checkVm != null) {
                    hostName = host.getName();
                }
            }
        }
        BackupOfferingVO vmBackupOffering = new BackupOfferingDaoImpl().findById(vm.getBackupOfferingId());
        String planName = vmBackupOffering.getExternalId();
        // 스냅샷 생성 mold-API 호출
        String[] properties = getServerProperties();
        ManagementServerHostVO msHost = msHostDao.findByMsid(ManagementServerNode.getManagementServerId());
        String moldUrl = properties[1] + "://" + msHost.getServiceIP() + ":" + properties[0] + "/client/api/";
        String moldMethod = "POST";
        String moldCommand = "createSnapshotBackup";
        UserAccount user = accountService.getActiveUserAccount("admin", 1L);
        String apiKey = user.getApiKey();
        String secretKey = user.getSecretKey();
        UserVmJoinVO userVM = userVmJoinDao.findById(vm.getId());
        List<VolumeVO> volumes = volsDao.findByInstance(userVM.getId());
        StringJoiner joiner = new StringJoiner(",");
        Map<Object, String> checkResult = new HashMap<>();
        for (VolumeVO vol : volumes) {
            Map<String, String> snapParams = new HashMap<>();
            snapParams.put("volumeid", Long.toString(vol.getId()));
            snapParams.put("quiescevm", "true");
            String createSnapResult = moldCreateSnapshotBackupAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapParams);
            if (createSnapResult == null) {
                // 스냅샷 생성 실패
                if (!checkResult.isEmpty()) {
                    for (String value : checkResult.values()) {
                        Map<String, String> snapshotParams = new HashMap<>();
                        snapshotParams.put("id", value);
                        moldMethod = "GET";
                        moldCommand = "deleteSnapshot";
                        moldDeleteSnapshotAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapshotParams);
                    }
                }
                LOG.error("Failed to request createSnapshot Mold-API.");
                return false;
            } else {
                JSONObject jsonObject = new JSONObject(createSnapResult);
                String jobId = jsonObject.get("jobid").toString();
                String snapId = jsonObject.get("id").toString();
                int jobStatus = getAsyncJobResult(moldUrl, apiKey, secretKey, jobId);
                if (jobStatus == 2) {
                    // 스냅샷 생성 실패
                    Map<String, String> snapshotParams = new HashMap<>();
                    snapshotParams.put("id", snapId);
                    moldMethod = "GET";
                    moldCommand = "deleteSnapshot";
                    moldDeleteSnapshotAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapshotParams);
                    if (!checkResult.isEmpty()) {
                        for (String value : checkResult.values()) {
                            snapshotParams = new HashMap<>();
                            snapshotParams.put("id", value);
                            moldMethod = "GET";
                            moldCommand = "deleteSnapshot";
                            moldDeleteSnapshotAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapshotParams);
                        }
                    }
                    LOG.error("createSnapshot Mold-API async job resulted in failure.");
                    return false;
                }
                checkResult.put(vol.getId(), snapId);
                List<SnapshotDataStoreVO> volSnap = snapshotStoreDao.findBySnapshotId(Long.parseLong(snapId));
                //수정 필요joiner.add(volSnap.getInstallPath());
            }
        }
        String path = joiner.toString();
        // 생성된 스냅샷의 경로로 해당 백업 세트의 백업 콘텐츠 경로 업데이트
        String clientId = client.getClientId(hostName);
        String subClientEntity = client.getSubclient(clientId, vm.getName());
        JSONObject jsonObject = new JSONObject(subClientEntity);
        String subclientId = jsonObject.getString("subclientId");
        String applicationId = jsonObject.getString("applicationId");
        String backupsetId = jsonObject.getString("backupsetId");
        String instanceId = jsonObject.getString("instanceId");
        String backupsetName = jsonObject.getString("backupsetName");
        String displayName = jsonObject.getString("displayName");
        String commCellName = jsonObject.getString("commCellName");
        String companyId = jsonObject.getJSONObject("entityInfo").getString("companyId");
        String companyName = jsonObject.getJSONObject("entityInfo").getString("companyName");
        String instanceName = jsonObject.getString("instanceName");
        String appName = jsonObject.getString("appName");
        String clientName = jsonObject.getString("clientName");
        String subclientGUID = jsonObject.getString("subclientGUID");
        String subclientName = jsonObject.getString("subclientName");
        String csGUID = jsonObject.getString("csGUID");
        boolean upResult = client.updateBackupSet(path, subclientId, clientId, planName, applicationId, backupsetId, instanceId, subclientName, backupsetName);
        String jobState = "Running";
        JSONObject jsonObject2 = new JSONObject();
        if (upResult) {
            String storagePolicyId = client.getStoragePolicyId(planName);
            String jobId = client.createBackup(subclientId, storagePolicyId, displayName, commCellName, clientId, companyId, companyName, instanceName, appName, applicationId, clientName, backupsetId, instanceId, subclientGUID, subclientName, csGUID, backupsetName);
            while (jobState == "Running") {
                String jobDetails = client.getJobDetails(jobId);
                jsonObject2 = new JSONObject(jobDetails);
                jobState = jsonObject2.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("progressInfo").getString("state");
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    LOG.error("create backup get asyncjob result sleep interrupted error");
                }
            }
            String endTime = jsonObject2.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").getString("endTime");
            String size = jsonObject2.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("detailInfo").getString("sizeOfApplication");
            String type = jsonObject2.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getString("backupType");
            SimpleDateFormat formatterDateTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            if (jobState == "Completed") {
                String externalId = path + "/" + jobId;
                BackupVO backup = new BackupVO();
                backup.setVmId(vm.getId());
                backup.setExternalId(externalId);
                backup.setType(type);
                try {
                    backup.setDate(formatterDateTime.parse(endTime));
                } catch (ParseException e) {
                    String msg = String.format("Unable to parse date [%s].", endTime);
                    LOG.error(msg, e);
                    throw new CloudRuntimeException(msg, e);
                }
                backup.setSize(Long.parseLong(size));
                long virtualSize = 0L;
                for (final Volume volume: volumeDao.findByInstance(vm.getId())) {
                    if (Volume.State.Ready.equals(volume.getState())) {
                        virtualSize += volume.getSize();
                    }
                }
                backup.setProtectedSize(Long.valueOf(virtualSize));
                backup.setStatus(org.apache.cloudstack.backup.Backup.Status.BackedUp);
                backup.setBackupOfferingId(vm.getBackupOfferingId());
                backup.setAccountId(vm.getAccountId());
                backup.setDomainId(vm.getDomainId());
                backup.setZoneId(vm.getDataCenterId());
                backup.setBackedUpVolumes(BackupManagerImpl.createVolumeInfoFromVolumes(volumeDao.findByInstance(vm.getId()), checkResult));
                StringJoiner snapshots = new StringJoiner(",");
                for (String value : checkResult.values()) {
                    snapshots.add(value);
                }
                backup.setSnapshotId(snapshots.toString());
                backupDao.persist(backup);
                // 백업 성공 후 스냅샷 삭제
                for (String value : checkResult.values()) {
                    Map<String, String> snapshotParams = new HashMap<>();
                    snapshotParams.put("id", value);
                    moldMethod = "GET";
                    moldCommand = "deleteSnapshot";
                    moldDeleteSnapshotAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapshotParams);
                }
                return true;
            } else {
                // 백업 실패
                if (!checkResult.isEmpty()) {
                    for (String value : checkResult.values()) {
                        Map<String, String> snapshotParams = new HashMap<>();
                        snapshotParams.put("id", value);
                        moldMethod = "GET";
                        moldCommand = "deleteSnapshot";
                        moldDeleteSnapshotAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapshotParams);
                    }
                }
                LOG.error("createBackup commvault api resulted in " + jobState);
                return false;
            }
        } else {
            // 백업 경로 업데이트 실패
            if (!checkResult.isEmpty()) {
                for (String value : checkResult.values()) {
                    Map<String, String> snapshotParams = new HashMap<>();
                    snapshotParams.put("id", value);
                    moldMethod = "GET";
                    moldCommand = "deleteSnapshot";
                    moldDeleteSnapshotAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, snapshotParams);
                }
            }
            LOG.error("updateBackupSet commvault api resulted in failure.");
            return false;
        }
    }

    @Override
    public boolean deleteBackup(Backup backup, boolean forced) {
        final Long zoneId = backup.getZoneId();
        final String externalId = backup.getExternalId();
        String[] external = externalId.split("/");
        String path = external[0];
        String jobId = external[1];
        final CommvaultClient client = getClient(zoneId);
        String jobDetails = client.getJobDetails(jobId);
        JSONObject jsonObject = new JSONObject(jobDetails);
        String commcellId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("commcell").getString("commCellId");
        String storagePolicyId = jsonObject.getJSONObject("job").getJSONObject("jobDetail").getJSONObject("generalInfo").getJSONObject("storagePolicy").getString("storagePolicyId");
        String copyId = client.getStoragePolicyDetails(storagePolicyId);
        if (client.deleteBackupForVM(jobId, commcellId, copyId, storagePolicyId)) {
            LOG.debug("Commvault successfully deleted backup with id " + externalId);
            return true;
        } else {
            LOG.debug("There was an error removing the backup with id " + externalId + " from Commvault");
        }
        return false;
    }

    @Override
    public Map<VirtualMachine, Backup.Metric> getBackupMetrics(Long zoneId, List<VirtualMachine> vms) {
        final Map<VirtualMachine, Backup.Metric> metrics = new HashMap<>();
        Long vmBackupSize=0L;
        Long vmBackupProtectedSize=0L;

        if (CollectionUtils.isEmpty(vms)) {
            LOG.warn("Unable to get VM Backup Metrics because the list of VMs is empty.");
            return metrics;
        }

        // for (final VirtualMachine vm : vms) {
        //     for (Backup.VolumeInfo thisVMVol : vm.getBackupVolumeList()) {
        //         vmBackupSize += (thisVMVol.getSize() / 1024L / 1024L);
        //     }
        //     final ArrayList<String> vmBackups = getClient(zoneId).getBackupsForVm(vm);
        //     for (String vmBackup : vmBackups) {
        //         NetworkerBackup vmNwBackup = getClient(zoneId).getNetworkerBackupInfo(vmBackup);
        //         vmBackupProtectedSize+= vmNwBackup.getSize().getValue() / 1024L;
        //     }
        //     Backup.Metric vmBackupMetric = new Backup.Metric(vmBackupSize,vmBackupProtectedSize);
        //     LOG.debug(String.format("Metrics for VM [%s] is [backup size: %s, data size: %s].", vm, vmBackupMetric.getBackupSize(), vmBackupMetric.getDataSize()));
        //     metrics.put(vm, vmBackupMetric);
        // }
        return metrics;
    }

    private Backup checkAndUpdateIfBackupEntryExistsForRestorePoint(List<Backup> backupsInDb, Backup.RestorePoint restorePoint, Backup.Metric metric) {
        for (final Backup backup : backupsInDb) {
            if (restorePoint.getId().equals(backup.getExternalId())) {
                if (metric != null) {
                    LOG.debug("Update backup with [id: {}, uuid: {}, name: {}, external id: {}] from [size: {}, protected size: {}] to [size: {}, protected size: {}].",
                            backup.getId(), backup.getUuid(), backup.getName(), backup.getExternalId(), backup.getSize(), backup.getProtectedSize(), metric.getBackupSize(), metric.getDataSize());

                    ((BackupVO) backup).setSize(metric.getBackupSize());
                    ((BackupVO) backup).setProtectedSize(metric.getDataSize());
                    backupDao.update(backup.getId(), ((BackupVO) backup));
                }
                return backup;
            }
        }
        return null;
    }

    @Override
    public void syncBackups(VirtualMachine vm, Backup.Metric metric) {
        // 복원 지점이 생긴 경우 sync 맞춰주는 로직 고민 필요
        // List<Backup.RestorePoint> restorePoints = listRestorePoints(vm);
        // if (CollectionUtils.isEmpty(restorePoints)) {
        //     LOG.debug("Can't find any restore point to VM: {}", vm);
        //     return;
        // }
        // Transaction.execute(new TransactionCallbackNoReturn() {
        //     @Override
        //     public void doInTransactionWithoutResult(TransactionStatus status) {
        //         final List<Backup> backupsInDb = backupDao.listByVmId(null, vm.getId());
        //         final List<Long> removeList = backupsInDb.stream().map(InternalIdentity::getId).collect(Collectors.toList());
        //         for (final Backup.RestorePoint restorePoint : restorePoints) {
        //             if (!(restorePoint.getId() == null || restorePoint.getType() == null || restorePoint.getCreated() == null)) {
        //                 Backup existingBackupEntry = checkAndUpdateIfBackupEntryExistsForRestorePoint(backupsInDb, restorePoint, metric);
        //                 if (existingBackupEntry != null) {
        //                     removeList.remove(existingBackupEntry.getId());
        //                     continue;
        //                 }

        //                 BackupVO backup = new BackupVO();
        //                 backup.setVmId(vm.getId());
        //                 backup.setExternalId(restorePoint.getId());
        //                 backup.setType(restorePoint.getType());
        //                 backup.setDate(restorePoint.getCreated());
        //                 backup.setStatus(Backup.Status.BackedUp);
        //                 if (metric != null) {
        //                     backup.setSize(metric.getBackupSize());
        //                     backup.setProtectedSize(metric.getDataSize());
        //                 }
        //                 backup.setBackupOfferingId(vm.getBackupOfferingId());
        //                 backup.setAccountId(vm.getAccountId());
        //                 backup.setDomainId(vm.getDomainId());
        //                 backup.setZoneId(vm.getDataCenterId());

        //                 LOG.debug("Creating a new entry in backups: [id: {}, uuid: {}, name: {}, vm_id: {}, external_id: {}, type: {}, date: {}, backup_offering_id: {}, account_id: {}, "
        //                         + "domain_id: {}, zone_id: {}].", backup.getId(), backup.getUuid(), backup.getName(), backup.getVmId(), backup.getExternalId(), backup.getType(), backup.getDate(), backup.getBackupOfferingId(), backup.getAccountId(), backup.getDomainId(), backup.getZoneId());
        //                 backupDao.persist(backup);

        //                 ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_VM_BACKUP_CREATE,
        //                         String.format("Created backup %s for VM ID: %s", backup.getUuid(), vm.getUuid()),
        //                         vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
        //             }
        //         }
        //         for (final Long backupIdToRemove : removeList) {
        //             LOG.warn(String.format("Removing backup with ID: [%s].", backupIdToRemove));
        //             backupDao.remove(backupIdToRemove);
        //         }
        //     }
        // });
        return;
    }

    @Override
    public boolean willDeleteBackupsOnOfferingRemoval() {
        return false;
    }

    protected static String moldCreateSnapshotBackupAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            if (region.contains("https")) {
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
            }
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
            if (connection.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                sb = new StringBuffer();
                while ((readLine = br.readLine()) != null) {
                    sb.append(readLine);
                }
            } else {
                String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                LOG.error(msg);
                return null;
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("createsnapshotbackupresponse");
            return response.toString();
        } catch (Exception e) {
            LOG.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    protected static String moldRevertSnapshotAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
            }
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
            if (connection.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                sb = new StringBuffer();
                while ((readLine = br.readLine()) != null) {
                    sb.append(readLine);
                }
            } else {
                String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                LOG.error(msg);
                return null;
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("revertsnapshotresponse");
            return response.toString();
        } catch (Exception e) {
            LOG.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    protected static String moldDeleteSnapshotAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
            }
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
            if (connection.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                sb = new StringBuffer();
                while ((readLine = br.readLine()) != null) {
                    sb.append(readLine);
                }
            } else {
                String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                LOG.error(msg);
                return null;
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("deletesnapshotresponse");
            return response.toString();
        } catch (Exception e) {
            LOG.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    protected static String moldQueryAsyncJobResultAPI(String region, String command, String method, String apiKey, String secretKey, Map<String, String> params) {
        try {
            String readLine = null;
            StringBuffer sb = null;
            String apiParams = buildParamsMold(command, params);
            String urlFinal = buildUrl(apiParams, region, apiKey, secretKey);
            URL url = new URL(urlFinal);
            HttpsURLConnection connection = (HttpsURLConnection)url.openConnection();
            if (region.contains("https")) {
                // SSL 인증서 에러 우회 처리
                final SSLContext sslContext = SSLUtils.getSSLContext();
                sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
            }
            connection.setDoOutput(true);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(180000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-type", "application/x-www-form-urlencoded");
            if (connection.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                sb = new StringBuffer();
                while ((readLine = br.readLine()) != null) {
                    sb.append(readLine);
                }
            } else {
                String msg = "Failed to request mold API. response code : " + connection.getResponseCode();
                LOG.error(msg);
                return null;
            }
            JSONObject jObject = XML.toJSONObject(sb.toString());
            JSONObject response = (JSONObject) jObject.get("queryasyncjobresultresponse");
            return response.get("jobstatus").toString();
        } catch (Exception e) {
            LOG.error(String.format("Mold API endpoint not available"), e);
            return null;
        }
    }

    protected static String buildParamsMold(String command, Map<String, String> params) {
        StringBuffer paramString = new StringBuffer("command=" + command);
        if (params != null) {
            try {
                for(Map.Entry<String, String> param : params.entrySet() ){
                    String key = param.getKey();
                    String value = param.getValue();
                    paramString.append("&" + param.getKey() + "=" + URLEncoder.encode(param.getValue(), "UTF-8"));
                }
            } catch (UnsupportedEncodingException e) {
                LOG.error(e.getMessage());
                return null;
            }
        }
        return paramString.toString();
    }

    private static String buildUrl(String apiParams, String region, String apiKey, String secretKey) {
        String encodedApiKey;
        try {
            encodedApiKey = URLEncoder.encode(apiKey, "UTF-8");
            List<String> sortedParams = new ArrayList<String>();
            sortedParams.add("apikey=" + encodedApiKey.toLowerCase());
            StringTokenizer st = new StringTokenizer(apiParams, "&");
            String url = null;
            boolean first = true;
            while (st.hasMoreTokens()) {
                String paramValue = st.nextToken();
                String param = paramValue.substring(0, paramValue.indexOf("="));
                String value = paramValue.substring(paramValue.indexOf("=") + 1, paramValue.length());
                if (first) {
                    url = param + "=" + value;
                    first = false;
                } else {
                    url = url + "&" + param + "=" + value;
                }
                sortedParams.add(param.toLowerCase() + "=" + value.toLowerCase());
            }
            Collections.sort(sortedParams);
            String sortedUrl = null;
            first = true;
            for (String param : sortedParams) {
                if (first) {
                    sortedUrl = param;
                    first = false;
                } else {
                    sortedUrl = sortedUrl + "&" + param;
                }
            }
            String encodedSignature = signRequest(sortedUrl, secretKey);
            String finalUrl = region + "?" + apiParams + "&apiKey=" + apiKey + "&signature=" + encodedSignature;
            return finalUrl;
        } catch (UnsupportedEncodingException e) {
            LOG.error(e.getMessage());
            return null;
        }
    }

    private static String signRequest(String request, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "HmacSHA256");
            mac.init(keySpec);
            mac.update(request.getBytes());
            byte[] encryptedBytes = mac.doFinal();
            return URLEncoder.encode(Base64.encodeBase64String(encryptedBytes), "UTF-8");
        } catch (Exception ex) {
            LOG.error(ex.getMessage());
            return null;
        }
    }

    private int getAsyncJobResult(String moldUrl, String apiKey, String secretKey, String jobId) throws CloudRuntimeException {
        int jobStatus = 0;
        String moldCommand = "queryAsyncJobResult";
        String moldMethod = "GET";
        Map<String, String> params = new HashMap<>();
        params.put("jobid", jobId);
        while (jobStatus == 0) {
            String result = moldQueryAsyncJobResultAPI(moldUrl, moldCommand, moldMethod, apiKey, secretKey, params);
            if (result != null) {
                jobStatus = Integer.parseInt(result);
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    LOG.error("create snapshot get asyncjob result sleep interrupted error");
                }
            } else {
                throw new CloudRuntimeException("Failed to request queryAsyncJobResult Mold-API.");
            }
        }
        return jobStatus;
    }

    private Optional<Backup.VolumeInfo> getBackedUpVolumeInfo(List<Backup.VolumeInfo> backedUpVolumes, String volumeUuid) {
        return backedUpVolumes.stream()
                .filter(v -> v.getUuid().equals(volumeUuid))
                .findFirst();
    }

    private String[] getServerProperties() {
        String[] serverInfo = null;
        final String HTTP_PORT = "http.port";
        final String HTTPS_ENABLE = "https.enable";
        final String HTTPS_PORT = "https.port";
        final File confFile = PropertiesUtil.findConfigFile("server.properties");
        try {
            InputStream is = new FileInputStream(confFile);
            String port = null;
            String protocol = null;
            final Properties properties = ServerProperties.getServerProperties(is);
            if (properties.getProperty(HTTPS_ENABLE).equals("true")){
                port = properties.getProperty(HTTPS_PORT);
                protocol = "https";
            } else {
                port = properties.getProperty(HTTP_PORT);
                protocol = "http";
            }
            serverInfo = new String[]{port, protocol};
        } catch (final IOException e) {
            LOG.debug("Failed to read configuration from server.properties file", e);
        }
        return serverInfo;
    }

}