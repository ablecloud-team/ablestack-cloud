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

package org.apache.cloudstack.storage.dataservice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.command.admin.storage.dataservice.GetStorageServiceRuntimeUpgradeCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceRuntimeBundlesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceRuntimeUpgradesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RegisterStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpdateStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.DeleteStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RepairStorageServiceNicIdentityCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageServiceVolumePreparationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.AttachStorageVolumeToFileShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageIscsiTargetCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfHostAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfNamespaceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNvmeOfSubsystemCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageServiceInstanceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageIscsiAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageIscsiTargetCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfHostAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfNamespaceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageNvmeOfSubsystemCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DetachStorageServiceBackingVolumeCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DisconnectStorageServiceSessionCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.JoinStorageServiceToAdDomainCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.LeaveStorageServiceFromAdDomainCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageIscsiAclsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageIscsiTargetsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNfsAclsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNfsExportsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNvmeOfHostAclsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNvmeOfNamespacesCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNvmeOfSubsystemsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceDomainStatusCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceHealthCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceInventoryCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceInstancesCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceProtocolsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceSessionsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbAclsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbSharesCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.PrepareStorageServiceNvmeOfVmCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ResizeStorageFileShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ResizeStorageServiceBackingVolumeCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageIscsiTargetCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsExportCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfHostAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfNamespaceCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNvmeOfSubsystemCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbAclCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageAccessRuleResponse;
import org.apache.cloudstack.api.response.StorageBlockTargetResponse;
import org.apache.cloudstack.api.response.StorageFileShareResponse;
import org.apache.cloudstack.api.response.StorageIdentityDomainResponse;
import org.apache.cloudstack.api.response.StorageNfsExportResponse;
import org.apache.cloudstack.api.response.StorageServiceInstanceResponse;
import org.apache.cloudstack.api.response.StorageServiceProtocolEndpointResponse;
import org.apache.cloudstack.api.response.StorageServiceProtocolResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeCapabilityResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeUpgradeResponse;
import org.apache.cloudstack.api.response.StorageSmbShareResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.commons.lang3.StringUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StorageServiceManagerImpl extends ManagerBase implements StorageService, PluggableService, Configurable {
    private java.util.concurrent.ScheduledExecutorService interruptedWriterExecutor;
    private java.util.concurrent.ScheduledExecutorService writerHeartbeatExecutor;
    private final ThreadLocal<StorageWriterHeartbeat> storageWriterHeartbeat = new ThreadLocal<>();

    @Override
    public boolean start() {
        writerHeartbeatExecutor = java.util.concurrent.Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "storage-service-writer-heartbeat");thread.setDaemon(true);return thread;
        });
        interruptedWriterExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "storage-service-interrupted-writer");thread.setDaemon(true);return thread;
        });
        interruptedWriterExecutor.scheduleWithFixedDelay(this::recoverStaleStorageWriters, 15, 30, java.util.concurrent.TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        if (interruptedWriterExecutor != null) interruptedWriterExecutor.shutdownNow();
        if (writerHeartbeatExecutor != null) writerHeartbeatExecutor.shutdownNow();
        return true;
    }

    protected void recoverStaleStorageWriters() {
        if (!StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value()) return;
        try {
            org.apache.cloudstack.context.CallContext.registerSystemCallContextOnceOnly();
            for (StorageServiceOperationVO row : storageOperationDao.listStaleRunning(new java.util.Date(System.currentTimeMillis() - 120000))) {
                com.cloud.utils.db.GlobalLock lock = com.cloud.utils.db.GlobalLock.getInternLock("StorageServiceWriter-" + row.getInstanceId());
                try {
                    if (!lock.lock(1)) continue;
                    try {
                        StorageServiceOperationVO current = storageOperationDao.findById(row.getId());
                        StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(row.getInstanceId());
                        if (current != null && instance != null && current.getAction().startsWith("ROOT_TEMPLATE_")) {
                            recoverInterruptedTemplateUpgrade(instance, current);
                        } else if (current != null && instance != null && InterruptedStateChange.stale(current, System.currentTimeMillis())) {
                            recoverInterruptedStorageWriter(instance, current);
                        }
                    } finally { lock.unlock(); }
                } catch (RuntimeException deferred) {
                    logger.warn("Interrupted writer recovery remains pending for operation {}", row.getUuid());
                } finally { lock.releaseRef(); }
            }
        } catch (RuntimeException unavailable) {
            logger.warn("Interrupted writer recovery scan is temporarily unavailable");
        } finally { org.apache.cloudstack.context.CallContext.unregister(); }
    }

    protected void recoverInterruptedStorageWriter(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {
        new InterruptedStateChange(storageOperationDao, new StorageServiceDesiredSnapshot()).recover(operation,
                storageOperationDao.listByInstance(instance.getId()), new InterruptedStateChange.Runtime() {
            public void idle() {
                VMInstanceVO vm = instance.getVmId() == null ? null : vmInstanceDao.findById(instance.getVmId());
                if (vm == null || vm.getState() != com.cloud.vm.VirtualMachine.State.Running) throw new CloudRuntimeException("Interrupted writer SystemVM is unavailable");
                StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                        "operation writer-idle", "", 15, Collections.emptySet()));
                JsonObject observed = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
                if (!result.isSuccess() || !Boolean.TRUE.equals(getJsonBoolean(observed, "success"))
                        || !Boolean.TRUE.equals(getJsonBoolean(observed, "writerLockSupported")) || !"WRITER_IDLE".equals(getJsonString(observed, "status"))) {
                    throw new CloudRuntimeException("Native writer is active or its recovery lock capability is unavailable");
                }
                if (storageRuntimeUpgradeDao.findActiveByInstanceId(instance.getId()) != null) throw new CloudRuntimeException("Runtime upgrade is active");
            }
            public void started(StorageServiceOperationVO row) { beginStorageWriterHeartbeat(row); }
            public void applyPrevious() {
                restoreNativePosixOperation(null, instance, true);
                for (StorageServiceInstance.Protocol protocol : StorageServiceInstance.Protocol.values()) {
                    if (protocol != StorageServiceInstance.Protocol.NVME_OF || !Boolean.TRUE.equals(configurationNativeNvmeReplayed.get())) {
                        applyStorageServiceProtocolDesiredState(instance, protocol);
                    }
                }
                restoreNativePosixOperation(null, instance, false);
            }
            public void verify() {
                verifyRecoveredStorageWriter(instance);
                rollbackNativeConfigurationGeneration(instance, operation);
            }
            public void verifyCurrent(StorageServiceOperationVO latest) {
                String current = new StorageServiceDesiredSnapshot().capture(instance.getId());
                if (latest.getSnapshotJson() == null || !parseJsonObject(current).equals(parseJsonObject(latest.getSnapshotJson()))) {
                    throw new CloudRuntimeException("Current desired state differs from the later verified revision");
                }
                verifyRecoveredStorageWriter(instance);
            }
            public void finished(StorageServiceOperationVO row) {
                try {
                    if (Set.of("ROLLED_BACK", "RECONCILED_SUPERSEDED", "BLOCKED").contains(row.getState())) {
                        new StorageServiceConfiguration(StorageServiceManagerImpl.this, storageConfigArtifactDao, storageOperationDao).failInterruptedCandidates(row);
                    }
                    cleanupConfigurationIdentityCheckpoint(row);
                } finally { endStorageWriterHeartbeat();configurationNativeNvmeReplayed.remove(); }
            }
        }, System.currentTimeMillis());
    }

    private void verifyRecoveredStorageWriter(StorageServiceInstanceVO instance) {
        StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "operation verify", "", 60, Collections.emptySet()));
        JsonObject health = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        if (!result.isSuccess() || !Boolean.TRUE.equals(getJsonBoolean(health, "success")) || !"ok".equalsIgnoreCase(getJsonString(health, "status"))) {
            throw new CloudRuntimeException("Recovered Storage Service runtime health is not verified");
        }
        verifyReconciledStorageDesiredState(instance);
    }

    private static final Gson GSON = new Gson();
    private static final Gson RUNTIME_RESULT_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int NFS_ANONYMOUS_UID = 65534;
    private static final int NFS_ANONYMOUS_GID = 65534;
    private static final String NFS_WRITABLE_ROOT_SQUASH_MODE = "0775";
    private static final int FILE_SHARE_VOLUME_READY_ATTEMPTS = 30;
    private static final long FILE_SHARE_VOLUME_READY_INTERVAL_MS = 2000L;
    private static final List<String> SUPPORTED_FILE_SHARE_FILESYSTEMS = Arrays.asList("xfs", "ext4");
    private static final Pattern NFS_ENDPOINT_MODE_PATTERN = Pattern.compile("\"endpointMode\"\\s*:\\s*\"(ALL|SELECTED|LISTENER_GROUP)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4_ADDRESS_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

    protected static class ProtocolResponseContext {
        private String primaryIp;
        private String runtimePrimaryIp;
        private String identityStatus = "UNKNOWN";
        private String identityWarning;
        private String nfsRuntimeIdMappingMode="UNKNOWN";
        private final List<String> serviceIps = new ArrayList<>();
        private final Set<String> aliasIps = new HashSet<>();
        private final Map<StorageServiceInstance.Protocol, Map<Integer, Integer>> linkedResourceCounts = new HashMap<>();
    }

    protected static class RuntimeObservationSnapshot {
        private boolean available;
        private String observedAt;
        private String bootId;
        private String error;
        private final Map<String, JsonObject> observations = new LinkedHashMap<>();
        private final Map<String, JsonObject> sharePolicies = new LinkedHashMap<>();

        protected JsonObject observation(final String key) {
            return StringUtils.isBlank(key) ? null : observations.get(key);
        }
    }

    @Inject
    private StorageServiceInstanceDao storageServiceInstanceDao;
    @Inject
    private StorageServiceProtocolDao storageServiceProtocolDao;
    @Inject
    private StorageFileShareDao storageFileShareDao;
    @Inject
    private StorageIdentityDomainDao storageIdentityDomainDao;
    @Inject
    private StorageBlockTargetDao storageBlockTargetDao;
    @Inject
    private StorageAccessRuleDao storageAccessRuleDao;
    @Inject
    private SharedFSDao sharedFSDao;
    @Inject
    private StorageServiceGuestCommandDispatcher guestCommandDispatcher;
    @Inject
    private StorageServiceRuntimeUpgradeManager runtimeUpgradeManager;
    @Inject
    private com.cloud.user.AccountManager storageAccountManager;
    @Inject
    private org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao storageOperationDao;
    @Inject
    private org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao storageRuntimeUpgradeDao;

    @Inject
    private AccountDao accountDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private NicSecondaryIpDao nicSecondaryIpDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VolumeApiService volumeApiService;

    @Inject
    private org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao storagePosixPolicyDao;
    private final ThreadLocal<StorageServiceOperationVO> storageWriterOperation = new ThreadLocal<>();
    private final ThreadLocal<Boolean> configurationNativeNvmeReplayed = new ThreadLocal<>();
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao storageConfigArtifactDao;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao storageRuntimeBundleDao;
    @Inject private org.apache.cloudstack.storage.sharedfs.SharedFSService configurationSharedFsService;
    @Inject private com.cloud.storage.dao.DiskOfferingDao configurationDiskOfferingDao;
    @Inject private org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao configurationStoragePoolDao;


    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao storageTemplateUpgradeDao;
    @Inject private com.cloud.vm.dao.UserVmDao rootUpgradeVmDao;
    @Inject private com.cloud.storage.dao.VMTemplateDao rootUpgradeTemplateDao;
    @Inject private com.cloud.host.dao.HostDao rootUpgradeHostDao;
    @Inject private com.cloud.storage.StorageManager rootUpgradeStorageManager;
    @Inject private com.cloud.vm.VirtualMachineManager rootUpgradeVmManager;
    @Inject private org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService rootUpgradeVolumeOrchestration;
    @Inject private org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory rootUpgradeVolumeFactory;
    @Inject private org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory rootUpgradeTemplateFactory;
    @Inject private org.apache.cloudstack.engine.subsystem.api.storage.VolumeService rootUpgradeVolumeService;

    protected static final class ConfigurationBatch {
        final long instanceId;
        final Map<Long, String> smbCredentials = new HashMap<>();
        final Map<Long, JsonObject> iscsiCredentials = new HashMap<>();
        final Map<Long, JsonObject> nvmeCredentials = new HashMap<>();
        final Map<Long, StorageServiceInstance.ResourceState> nvmeHostStates = new HashMap<>();
        ConfigurationBatch(long instanceId) { this.instanceId = instanceId; }
    }
    private final ThreadLocal<ConfigurationBatch> configurationBatch = new ThreadLocal<>();
    protected void beginConfigurationBatch(long instanceId) {
        if (configurationBatch.get() != null) throw new CloudRuntimeException("A configuration batch is already active");
        requireConfigurationAdministrator();configurationBatch.set(new ConfigurationBatch(instanceId));
    }
    protected void finishConfigurationBatch(StorageServiceInstanceVO instance) {
        ConfigurationBatch batch = configurationBatch.get();
        if (batch == null || batch.instanceId != instance.getId()) throw new CloudRuntimeException("Configuration batch scope changed");
        configurationBatch.remove();
        for (StorageServiceInstance.Protocol protocol : StorageServiceInstance.Protocol.values()) {
            if (storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocol).stream().noneMatch(StorageServiceProtocolVO::isEnabled)) continue;
            if (protocol == StorageServiceInstance.Protocol.NFS) {
                applyNfsDesiredState(instance);
                JsonObject observed = observeConfigurationRuntime(instance, "inventory");
                JsonElement exports = observed.get("nfsGaneshaExports");
                JsonArray pseudos = new JsonArray();
                if (exports != null && exports.isJsonArray()) for (JsonElement listener : exports.getAsJsonArray()) {
                    JsonObject endpoint = listener.getAsJsonObject();
                    if (endpoint.has("entries")) for (JsonElement entry : endpoint.getAsJsonArray("entries")) {
                        JsonObject resource = entry.getAsJsonObject();if (resource.has("pseudo")) pseudos.add(resource.get("pseudo"));
                    }
                }
                logger.info("Configuration NFS post-apply pseudos for {}: {}", instance.getUuid(), pseudos);
            }
            else if (protocol == StorageServiceInstance.Protocol.SMB) applySmbDesiredState(instance, batch.smbCredentials);
            else if (protocol == StorageServiceInstance.Protocol.ISCSI) applyIscsiDesiredState(instance, batch.iscsiCredentials);
            else applyNvmeOfDesiredState(instance, batch.nvmeCredentials, batch.nvmeHostStates);
        }
        verifyReconciledStorageDesiredState(instance);
    }
    protected void abortConfigurationBatch() { configurationBatch.remove(); }
    protected Object invokeConfigurationDomainCommand(org.apache.cloudstack.api.BaseCmd cmd, String methodName) {
        Set<String> allowed = Set.of("enableStorageServiceProtocol", "createStorageNfsExport", "updateStorageNfsExport", "createStorageSmbShare", "updateStorageSmbShare",
                "createStorageNfsAcl", "updateStorageNfsAcl", "createStorageSmbAcl", "updateStorageSmbAcl",
                "createStorageSmbNetworkAcl", "updateStorageSmbNetworkAcl", "createStorageIscsiTarget", "updateStorageIscsiTarget",
                "createStorageIscsiAcl", "updateStorageIscsiAcl", "createStorageNvmeOfSubsystem", "updateStorageNvmeOfSubsystem",
                "createStorageNvmeOfNamespace", "updateStorageNvmeOfNamespace", "createStorageNvmeOfHostAcl", "updateStorageNvmeOfHostAcl",
                "executeStoragePosixDirectoryPolicy");
        if (!allowed.contains(methodName) || configurationBatch.get() == null) throw new InvalidParameterValueException("Configuration command is outside the active domain batch");
        try {
            java.lang.reflect.Method method = java.util.Arrays.stream(getClass().getMethods())
                    .filter(item -> item.getName().equals(methodName) && item.getParameterCount() == 1 && item.getParameterTypes()[0].isInstance(cmd))
                    .findFirst().orElseThrow(() -> new NoSuchMethodException(methodName));
            return method.invoke(this, cmd);
        }
        catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException) throw (RuntimeException) failure.getCause();
            throw new CloudRuntimeException("Configuration domain validation failed", failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new InvalidParameterValueException("Current configuration API method is unavailable"); }
    }



    @Override
    public org.apache.cloudstack.api.response.StorageServiceTemplateUpgradeResponse storageServiceTemplateUpgrade(StorageTemplateUpgradeRequest request) {
        requireConfigurationAdministrator();
        SharedFSVO shared = sharedFSDao.findById(request.getSharedFileSystemId());
        if (shared == null || shared.getVmId() == null) throw new InvalidParameterValueException("SharedFS SystemVM is unavailable");
        StorageServiceInstanceVO instance = storageServiceInstanceDao.findByVmId(shared.getVmId());
        if (instance == null) throw new InvalidParameterValueException("Storage Service instance is unavailable");
        if (java.util.Set.of("TEMPLATES","CAPABILITIES","HISTORY").contains(request.getTemplateAction())) {
            JsonObject result;
            if ("TEMPLATES".equals(request.getTemplateAction())) result=rootTemplateCatalog(instance,null);
            else if ("CAPABILITIES".equals(request.getTemplateAction())) result=rootUpgradeCapabilities(instance);
            else {result=new JsonObject();JsonArray history=new JsonArray();storageTemplateUpgradeDao.listByInstance(instance.getId()).stream()
                    .sorted(java.util.Comparator.comparing(StorageServiceTemplateUpgradeVO::getCreated).reversed()).forEach(row->history.add(rootUpgradeJson(row)));
                result.add("upgrades",history);result.addProperty("count",history.size());}
            return rootUpgradeResponse(instance.getUuid(),result);
        }
        com.cloud.utils.db.GlobalLock lock = com.cloud.utils.db.GlobalLock.getInternLock("StorageServiceWriter-" + instance.getId());
        try {
            if (!lock.lock(120)) throw new CloudRuntimeException("Storage Service writer is busy");
            try {
                JsonObject result = new JsonObject();String action = request.getTemplateAction();
                if ("PREFLIGHT".equals(action)) {
                    if (request.getTemplateId() == null) throw new InvalidParameterValueException("Select an explicit Storage Service template");
                    requireRootWriterIdle(instance, null);
                    long revision = rootDesiredRevision(instance.getId());
                    if (request.getExpectedRevision() != null && request.getExpectedRevision() != revision) throw new InvalidParameterValueException("Desired revision changed");
                    JsonObject preflight = rootPreflight(instance, shared, request.getTemplateId(), true);
                    if (!preflight.get("compatible").getAsBoolean()) { result.add("preflight", preflight);return rootUpgradeResponse(instance.getUuid(), result); }
                    String key = request.getIdempotencyKey() == null ? java.util.UUID.randomUUID().toString() : request.getIdempotencyKey();
                    StorageServiceTemplateUpgradeVO row = new StorageTemplateUpgradePlanner(storageTemplateUpgradeDao).plan(instance, shared.getId(),
                            rootUpgradeVmDao.findById(instance.getVmId()), volumeDao.findByInstanceAndType(instance.getVmId(), com.cloud.storage.Volume.Type.ROOT),
                            rootUpgradeTemplateDao.findById(request.getTemplateId()), preflight, revision, key, org.apache.cloudstack.context.CallContext.current().getCallingUserId());
                    result.add("upgrade", rootUpgradeJson(row));result.add("preflight", parseJsonObject(row.getPreflightJson()));
                } else {
                    StorageServiceTemplateUpgradeVO row = request.getUpgradeId() == null ? null : storageTemplateUpgradeDao.findById(request.getUpgradeId());
                    if (row == null || row.getInstanceId() != instance.getId() || row.getSharedFilesystemId() != shared.getId()) throw new InvalidParameterValueException("Template upgrade scope changed");
                    if ("UPGRADE".equals(action) && "COMPLETE".equals(row.getState())) { result.add("upgrade", rootUpgradeJson(row));return rootUpgradeResponse(row.getUuid(), result); }
                    if (!java.util.Objects.equals(shared.getName(), request.getConfirmation()) || (java.util.Set.of("UPGRADE","ROLLBACK").contains(action) && !Boolean.TRUE.equals(request.getMaintenanceWindow()))) {
                        throw new InvalidParameterValueException("ROOT maintenance requires interrupted-session approval and the exact SharedFS name");
                    }
                    requireRootWriterIdle(instance, row);
                    if ("FINALIZE".equals(action)) finalizeTemplateUpgrade(instance, row);
                    else if ("UPGRADE".equals(action) || "ROLLBACK".equals(action)) {
                        long revision = rootDesiredRevision(instance.getId());
                        if (request.getExpectedRevision() != null && request.getExpectedRevision() != revision) throw new InvalidParameterValueException("Desired revision changed; refresh before maintenance");
                        StorageServiceOperationVO recorded=row.getOperationId()==null?null:storageOperationDao.findById(row.getOperationId());
                        boolean manualRollback = "ROLLBACK".equals(action) && "COMPLETE".equals(row.getState()) || recorded!=null && "ROOT_TEMPLATE_ROLLBACK".equals(recorded.getAction());
                        if (manualRollback && "COMPLETE".equals(row.getState()) && rootRequiresNvmeAuth(instance)) {
                            com.cloud.storage.VMTemplateVO previous=rootUpgradeTemplateDao.findById(row.getSourceTemplateId());rootUpgradeTemplateDao.loadDetails(previous);
                            if (!"true".equalsIgnoreCase(previous.getDetails().get("storage.service.nvme.target.auth"))) throw new InvalidParameterValueException("Retained ROOT cannot serve the current NVMe authentication configuration");
                        }
                        if (manualRollback && "COMPLETE".equals(row.getState()) && !rootRollbackAllowed(row)) throw new InvalidParameterValueException("Retained ROOT rollback is unavailable or expired");
                        if ("PLANNED".equals(row.getState())) StorageTemplateUpgradePlanner.approve(row, revision, shared.getName(), request.getConfirmation(), request.getMaintenanceWindow());
                        else if (!java.util.Set.of("RUNNING", "RECOVERY_REQUIRED", "COMPLETE").contains(row.getState())) throw new InvalidParameterValueException("Upgrade cannot run in its current state");
                        StorageServiceOperationVO operation = rootUpgradeOperation(instance, row, revision, manualRollback);
                        beginStorageWriterHeartbeat(operation);
                        try {
                            RootUpgradeRuntime runtime = new RootUpgradeRuntime(instance, shared, row, operation, manualRollback);
                            StorageServiceTemplateUpgradeEngine engine = new StorageServiceTemplateUpgradeEngine(storageTemplateUpgradeDao);
                            if ("ROLLBACK".equals(action)) engine.rollback(row, runtime);else engine.execute(row, runtime);
                        } finally { endStorageWriterHeartbeat();configurationNativeNvmeReplayed.remove(); }
                    } else throw new InvalidParameterValueException("Unknown template lifecycle action");
                    org.apache.cloudstack.context.CallContext.current().setEventResourceId(row.getId());
                    org.apache.cloudstack.context.CallContext.current().setEventDetails("SharedFS ROOT transaction "+row.getUuid()+" "+row.getState());
                    result.add("upgrade", rootUpgradeJson(row));
                }
                return rootUpgradeResponse(instance.getUuid(), result);
            } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }
    private org.apache.cloudstack.api.response.StorageServiceTemplateUpgradeResponse rootUpgradeResponse(String id, JsonObject result) {
        org.apache.cloudstack.api.response.StorageServiceTemplateUpgradeResponse response = new org.apache.cloudstack.api.response.StorageServiceTemplateUpgradeResponse();
        response.setId(id);response.setResult(StorageConfigSemantic.redact(result).toString());return response;
    }
    protected long rootDesiredRevision(long instanceId) {
        return storageOperationDao.listByInstance(instanceId).stream().filter(row -> "COMPLETE".equals(row.getState())).mapToLong(StorageServiceOperationVO::getRevision).max().orElse(0);
    }
    protected void requireRootWriterIdle(StorageServiceInstanceVO instance, StorageServiceTemplateUpgradeVO own) {
        if (storageRuntimeUpgradeDao.findActiveByInstanceId(instance.getId()) != null) throw new CloudRuntimeException("A runtime upgrade is active");
        StorageServiceTemplateUpgradeVO active = storageTemplateUpgradeDao.findActive(instance.getId());
        if (active != null && (own == null || active.getId() != own.getId())) throw new CloudRuntimeException("Another ROOT upgrade requires recovery");
        long committed = rootDesiredRevision(instance.getId());
        for (StorageServiceOperationVO operation : storageOperationDao.listByInstance(instance.getId())) {
            if (own != null && java.util.Objects.equals(own.getOperationId(), operation.getId())) continue;
            if ("RUNNING".equals(operation.getState()) || "RECOVERY_REQUIRED".equals(operation.getState()) && operation.getRevision() > committed) {
                throw new CloudRuntimeException("An unresolved Storage Service writer requires recovery");
            }
        }
    }
    private boolean rootRequiresNvmeAuth(StorageServiceInstanceVO instance) {
        for (StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, target.getId())) {
                JsonObject config = parseJsonObject(rule.getConfigJson());
                if (Boolean.TRUE.equals(getJsonBoolean(config,"dhChapEnabled")) || Boolean.TRUE.equals(getJsonBoolean(config,"dhChapCtrlEnabled"))) return true;
            }
        }
        return false;
    }
    protected JsonObject rootTemplateCatalog(StorageServiceInstanceVO instance, Long templateId) {
        com.cloud.vm.UserVmVO vm = rootUpgradeVmDao.findById(instance.getVmId());
        if (vm == null) throw new InvalidParameterValueException("SharedFS VM is unavailable");
        Long hostId = vm.getHostId() == null ? vm.getLastHostId() : vm.getHostId();
        com.cloud.host.HostVO host = hostId == null ? null : rootUpgradeHostDao.findById(hostId);
        String managerVersion = com.cloud.server.ManagementServer.class.getPackage().getImplementationVersion();
        StorageServiceSystemVmTemplateCatalog catalog = new StorageServiceSystemVmTemplateCatalog(rootUpgradeTemplateDao);
        return templateId == null ? catalog.list(vm, managerVersion, host == null ? null : host.getVersion(), rootRequiresNvmeAuth(instance))
                : catalog.preflight(vm, templateId, managerVersion, host == null ? null : host.getVersion(), rootRequiresNvmeAuth(instance));
    }
    protected JsonObject rootTopology(StorageServiceInstanceVO instance) {
        return StorageRootTopologySnapshot.capture(rootUpgradeVmDao.findById(instance.getVmId()), nicDao.listByVmId(instance.getVmId()),
                nicSecondaryIpDao.listByVmId(instance.getVmId()), volumeDao.findByInstance(instance.getVmId()));
    }
    private JsonObject rootUpgradeCapabilities(StorageServiceInstanceVO instance) {
        com.cloud.vm.UserVmVO vm = rootUpgradeVmDao.findById(instance.getVmId());JsonObject value = new JsonObject();
        com.cloud.storage.VMTemplateVO template = rootUpgradeTemplateDao.findById(vm.getTemplateId());
        List<VolumeVO> roots = volumeDao.findByInstanceAndType(vm.getId(), com.cloud.storage.Volume.Type.ROOT);
        value.addProperty("currentTemplateUuid", template == null ? null : template.getUuid());
        value.addProperty("currentRootVolumeUuid", roots.size() == 1 ? roots.get(0).getUuid() : null);
        value.addProperty("desiredRevision", rootDesiredRevision(instance.getId()));value.addProperty("maintenanceRequired", true);
        value.addProperty("templateUpgradeState",instance.getTemplateUpgradeState());value.addProperty("templateVerifiedAt",instance.getTemplateVerifiedAt()==null?null:instance.getTemplateVerifiedAt().toInstant().toString());
        if (instance.getPreviousTemplateId()!=null) {com.cloud.storage.VMTemplateVO previous=rootUpgradeTemplateDao.findById(instance.getPreviousTemplateId());value.addProperty("previousTemplateUuid",previous==null?null:previous.getUuid());}
        value.addProperty("automaticRollback", true);value.addProperty("dataVolumePolicy", "MOUNT_EXISTING");
        StorageServiceTemplateUpgradeVO active = storageTemplateUpgradeDao.findActive(instance.getId());
        if (active!=null) {JsonObject cached=parseJsonObject(active.getPreflightJson());value.add("activeSessions",cached.has("activeSessions")?cached.get("activeSessions"):new JsonObject());value.add("identityMigration",cached.has("identityMigration")?cached.get("identityMigration"):new JsonObject());}
        else {JsonObject sessions=observeConfigurationRuntime(instance,"sessions");value.add("sessions",sessions);value.add("activeSessions",sessions.has("count")?sessions.get("count"):com.google.gson.JsonNull.INSTANCE);value.add("identityMigration",vm.getState()==com.cloud.vm.VirtualMachine.State.Running?rootIdentityCapabilities(instance):new JsonObject());}
        if (active != null) value.add("activeUpgrade", rootUpgradeJson(active));return value;
    }
    private JsonObject rootIdentityCapabilities(StorageServiceInstanceVO instance) {
        JsonObject request = new JsonObject();request.addProperty("instanceUuid", instance.getUuid());request.addProperty("operationUuid", java.util.UUID.randomUUID().toString());
        try { return rootGuest(instance,"identity capsule capabilities",request,30); }
        catch (RuntimeException unavailable) { JsonObject result = new JsonObject();result.addProperty("success", false);result.addProperty("status", "UNAVAILABLE");return result; }
    }
    protected JsonObject rootGuest(StorageServiceInstanceVO instance, String command, JsonObject request, int timeout) {
        StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),command,request.toString(),timeout,Collections.emptySet()));
        JsonObject observed = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        if (!result.isSuccess() || !Boolean.TRUE.equals(getJsonBoolean(observed,"success"))) throw new CloudRuntimeException("ROOT runtime step failed: " + command);
        return observed;
    }

    private JsonObject rootPreflight(StorageServiceInstanceVO instance, SharedFSVO shared, long targetId, boolean allowStopped) {
        JsonObject result = rootTemplateCatalog(instance, targetId);JsonArray blockers = result.getAsJsonArray("blockers");
        com.cloud.vm.UserVmVO vm = rootUpgradeVmDao.findById(instance.getVmId());
        if (shared.getState() != SharedFS.State.Ready && shared.getState() != SharedFS.State.Stopped) blockers.add("SHAREDFS_TRANSITIONAL");
        if (!StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value()) blockers.add("VERIFIED_CONFIGURATION_DISABLED");
        StorageIdentityDomainVO domain=storageIdentityDomainDao.findByInstanceId(instance.getId());
        if (domain!=null && domain.getJoinState()!=StorageServiceInstance.DomainJoinState.NOT_JOINED) blockers.add("AD_IDENTITY_MIGRATION_REQUIRES_VALIDATION");
        List<VolumeVO> roots = volumeDao.findByInstanceAndType(vm.getId(), com.cloud.storage.Volume.Type.ROOT);
        if (roots.size() != 1 || roots.get(0).getState() != com.cloud.storage.Volume.State.Ready || roots.get(0).getPoolId() == null
                || !java.util.Objects.equals(roots.get(0).getTemplateId(),vm.getTemplateId())) blockers.add("CURRENT_ROOT_UNVERIFIED");
        else {
            org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool = configurationStoragePoolDao.findById(roots.get(0).getPoolId());
            com.cloud.storage.VMTemplateVO target = rootUpgradeTemplateDao.findById(targetId);
            if (pool == null || pool.getStatus() != com.cloud.storage.StoragePoolStatus.Up || target == null
                    || !rootUpgradeStorageManager.storagePoolHasEnoughSpace(Math.max(roots.get(0).getSize(), target.getSize() == null ? 0 : target.getSize()),pool)) blockers.add("ROOT_PRIMARY_STORAGE_CAPACITY_UNAVAILABLE");
        }
        Set<Long> devices = new HashSet<>();
        for (VolumeVO volume : volumeDao.findByInstance(vm.getId())) {
            if (volume.getDeviceId() == null || !devices.add(volume.getDeviceId()) || volume.getState() != com.cloud.storage.Volume.State.Ready) blockers.add("VOLUME_MAPPING_AMBIGUOUS");
        }
        result.add("topology", rootTopology(instance));result.add("dataVolumes",result.getAsJsonObject("topology").get("dataVolumes"));
        if (vm.getState() == com.cloud.vm.VirtualMachine.State.Running) {
            JsonObject identity = rootIdentityCapabilities(instance);result.add("identityMigration", identity);
            if (!Boolean.TRUE.equals(getJsonBoolean(identity,"localIdentity")) || !Boolean.TRUE.equals(getJsonBoolean(identity,"protectedStdinTransport"))) blockers.add("IDENTITY_CAPSULE_UNAVAILABLE");
            try {
                JsonObject generation = nativeConfigurationGeneration(instance,null,"status");result.add("nativeGeneration",generation);
                if (!generation.has("configurationDesiredState")) blockers.add("ROOT_DESIRED_SEED_CAPABILITY_UNAVAILABLE");
                if (!"IN_SYNC".equals(getJsonString(generation,"generationStatus")) || generation.get("runtimeRevision").getAsLong() != rootDesiredRevision(instance.getId())) blockers.add("NATIVE_GENERATION_DRIFT");
                verifyReconciledStorageDesiredState(instance);result.add("runtime", rootGuest(instance,"operation verify",new JsonObject(),60));
            } catch (RuntimeException drift) { blockers.add("CURRENT_RUNTIME_UNVERIFIED"); }
            JsonObject sessions=observeConfigurationRuntime(instance,"sessions");result.add("sessions",sessions);result.add("activeSessions",sessions.has("count")?sessions.get("count"):com.google.gson.JsonNull.INSTANCE);
        } else if (vm.getState() == com.cloud.vm.VirtualMachine.State.Stopped && allowStopped) result.addProperty("runtimePreflightPending",true);
        else blockers.add("SOURCE_VM_NOT_RUNNING");
        result.addProperty("compatible", blockers.size() == 0);result.addProperty("desiredRevision",rootDesiredRevision(instance.getId()));
        result.addProperty("rollbackAvailable",true);result.addProperty("estimatedDowntimeSeconds",300);return result;
    }
    private StorageServiceOperationVO rootUpgradeOperation(StorageServiceInstanceVO instance, StorageServiceTemplateUpgradeVO row, long revision, boolean manualRollback) {
        StorageServiceOperationVO existing = row.getOperationId() == null ? null : storageOperationDao.findById(row.getOperationId());
        if (existing != null && (!manualRollback || !"COMPLETE".equals(row.getState()))) return existing;
        StorageServiceOperationVO operation = new StorageServiceOperationVO();operation.setInstanceId(instance.getId());
        operation.setAction(manualRollback ? "ROOT_TEMPLATE_ROLLBACK" : "ROOT_TEMPLATE_UPGRADE");
        operation.setRequestKey(operation.getAction()+":"+row.getUuid());operation.setRevision(revision+1);
        operation.setCreatedBy(org.apache.cloudstack.context.CallContext.current().getCallingUserId());operation.setState("RUNNING");operation.setPhase("PREFLIGHT");
        operation.setPreviousSnapshotJson(captureConfigurationSnapshot(instance.getId()));
        return Transaction.execute((TransactionCallback<StorageServiceOperationVO>) status -> {
            StorageServiceOperationVO saved = storageOperationDao.persist(operation);row.setOperationId(saved.getId());
            row.setState("RUNNING");if (manualRollback) row.setPhase("ROLLING_BACK_ROOT");
            if (!storageTemplateUpgradeDao.update(row.getId(),row)) throw new CloudRuntimeException("Unable to reserve ROOT writer");return saved;
        });
    }
    protected void recoverInterruptedTemplateUpgrade(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {
        if (!InterruptedStateChange.stale(operation,System.currentTimeMillis())) return;
        StorageServiceTemplateUpgradeVO row = storageTemplateUpgradeDao.listByInstance(instance.getId()).stream()
                .filter(item -> java.util.Objects.equals(item.getOperationId(),operation.getId())).findFirst().orElse(null);
        if (row == null) throw new CloudRuntimeException("ROOT writer transaction is missing");
        if ("COMPLETE".equals(row.getState()) || "ROLLED_BACK".equals(row.getState()) || "BLOCKED".equals(row.getState())) return;
        SharedFSVO shared = sharedFSDao.findById(row.getSharedFilesystemId());
        beginStorageWriterHeartbeat(operation);
        try {
            RootUpgradeRuntime runtime = new RootUpgradeRuntime(instance,shared,row,operation,"ROOT_TEMPLATE_ROLLBACK".equals(operation.getAction()));
            new StorageServiceTemplateUpgradeEngine(storageTemplateUpgradeDao).execute(row,runtime);
        } finally { endStorageWriterHeartbeat();configurationNativeNvmeReplayed.remove(); }
    }
    protected boolean rootRollbackAllowed(StorageServiceTemplateUpgradeVO row) {
        StorageServiceInstanceVO owner=storageServiceInstanceDao.findById(row.getInstanceId());
        if (owner==null || owner.getVmId()==null || row.getTargetRootVolumeId()==null || volumeDao.findByInstanceAndType(owner.getVmId(),com.cloud.storage.Volume.Type.ROOT).stream().noneMatch(root->root.getId()==row.getTargetRootVolumeId())) return false;
        return "COMPLETE".equals(row.getState()) && row.getRollbackRetainUntil() != null && row.getRollbackRetainUntil().after(new java.util.Date())
                && volumeDao.findById(row.getPreviousRootVolumeId()) != null && row.getTargetRootVolumeId() != null;
    }
    protected void finalizeTemplateUpgrade(StorageServiceInstanceVO instance, StorageServiceTemplateUpgradeVO row) {
        if ("FINALIZED".equals(row.getState())) return;
        if (!java.util.Set.of("COMPLETE","ROLLED_BACK","BLOCKED").contains(row.getState())) throw new InvalidParameterValueException("ROOT recovery must finish before finalize");
        if (row.getRollbackRetainUntil() != null && row.getRollbackRetainUntil().after(new java.util.Date())) throw new InvalidParameterValueException("Rollback ROOT retention has not expired");
        long discarded = "COMPLETE".equals(row.getState()) ? row.getPreviousRootVolumeId() : row.getTargetRootVolumeId() == null ? 0 : row.getTargetRootVolumeId();
        if (discarded != 0) {
            VolumeVO volume = volumeDao.findById(discarded);
            if (volume != null && volume.getRemoved() == null) {
                if (volume.getVolumeType() != com.cloud.storage.Volume.Type.ROOT || volume.getInstanceId() != null || volume.getAccountId() != instance.getAccountId()
                        || volume.getDataCenterId() != instance.getDataCenterId()) throw new InvalidParameterValueException("Only the detached retained ROOT may be finalized");
                rootUpgradeVolumeService.destroyVolume(discarded);
                try {
                    org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult result = rootUpgradeVolumeService.expungeVolumeAsync(rootUpgradeVolumeFactory.getVolume(discarded)).get(15,java.util.concurrent.TimeUnit.MINUTES);
                    if (result == null || result.isFailed()) throw new CloudRuntimeException("Detached ROOT expunge requires retry");
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new CloudRuntimeException("ROOT finalize interrupted",interrupted); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException pending) { throw new CloudRuntimeException("ROOT finalize requires reconciliation",pending); }
            }
        }
        StorageServiceOperationVO operation = row.getOperationId() == null ? null : storageOperationDao.findById(row.getOperationId());
        cleanupConfigurationIdentityCheckpoint(operation);row.setState("FINALIZED");row.setPhase("FINALIZED");row.setHeartbeat(new java.util.Date());storageTemplateUpgradeDao.update(row.getId(),row);
    }
    private JsonObject rootUpgradeJson(StorageServiceTemplateUpgradeVO row) {
        JsonObject value = new JsonObject();value.addProperty("id",row.getUuid());value.addProperty("state",row.getState());value.addProperty("phase",row.getPhase());
        value.addProperty("progress",row.getProgress());value.addProperty("revision",row.getRevision());value.addProperty("errorCode",row.getErrorCode());value.addProperty("errorMessage",row.getErrorMessage());
        com.cloud.storage.VMTemplateVO source = rootUpgradeTemplateDao.findById(row.getSourceTemplateId());com.cloud.storage.VMTemplateVO target = rootUpgradeTemplateDao.findById(row.getTargetTemplateId());
        value.addProperty("sourceTemplateUuid",source == null ? null : source.getUuid());value.addProperty("targetTemplateUuid",target == null ? null : target.getUuid());
        VolumeVO previous = volumeDao.findById(row.getPreviousRootVolumeId());VolumeVO staged = row.getTargetRootVolumeId() == null ? null : volumeDao.findById(row.getTargetRootVolumeId());
        value.addProperty("previousRootVolumeUuid",previous == null ? null : previous.getUuid());value.addProperty("targetRootVolumeUuid",staged == null ? null : staged.getUuid());
        value.addProperty("rollbackRetainUntil",row.getRollbackRetainUntil() == null ? null : row.getRollbackRetainUntil().toInstant().toString());value.addProperty("rollbackAllowed",rootRollbackAllowed(row));
        value.addProperty("finalizeAllowed", java.util.Set.of("COMPLETE","ROLLED_BACK","BLOCKED").contains(row.getState()) && (row.getRollbackRetainUntil() == null || !row.getRollbackRetainUntil().after(new java.util.Date())));
        value.addProperty("started",row.getStarted() == null ? null : row.getStarted().toInstant().toString());value.addProperty("completed",row.getCompleted() == null ? null : row.getCompleted().toInstant().toString());
        if (row.getVerificationJson() != null) value.add("verification",parseJsonObject(row.getVerificationJson()));
        if (row.getRollbackResultJson() != null) value.add("rollbackResult",parseJsonObject(row.getRollbackResultJson()));
        if (row.getSnapshotJson() != null) { JsonObject snapshot = parseJsonObject(row.getSnapshotJson());
            if (snapshot.has("quiescedAt") && snapshot.has("serviceVerifiedAt")) value.addProperty("actualDowntimeSeconds",Math.max(0,(snapshot.get("serviceVerifiedAt").getAsLong()-snapshot.get("quiescedAt").getAsLong())/1000));
            if (snapshot.has("rollbackQuiescedAt") && snapshot.has("rollbackServiceVerifiedAt")) value.addProperty("rollbackDowntimeSeconds",Math.max(0,(snapshot.get("rollbackServiceVerifiedAt").getAsLong()-snapshot.get("rollbackQuiescedAt").getAsLong())/1000)); }
        return value;
    }

    protected StorageServiceRootVolumeSwap rootVolumeSwap() {
        return new StorageServiceRootVolumeSwap(volumeDao,rootUpgradeVmDao,rootUpgradeVolumeOrchestration,rootUpgradeVolumeFactory,rootUpgradeTemplateFactory,rootUpgradeVolumeService);
    }

    protected final class RootUpgradeRuntime implements StorageServiceTemplateUpgradeEngine.Runtime {
        private final StorageServiceInstanceVO instance;private final SharedFSVO shared;private final StorageServiceTemplateUpgradeVO row;
        private final StorageServiceOperationVO operation;private final boolean manualRollback;
        private final StorageServiceRootVolumeSwap swap = rootVolumeSwap();
        private final StorageRootVmLifecycle lifecycle = new StorageRootVmLifecycle(rootUpgradeVmManager,rootUpgradeVmDao,guestCommandDispatcher);
        RootUpgradeRuntime(StorageServiceInstanceVO instance, SharedFSVO shared, StorageServiceTemplateUpgradeVO row, StorageServiceOperationVO operation, boolean manualRollback) {
            this.instance=instance;this.shared=shared;this.row=row;this.operation=operation;this.manualRollback=manualRollback;
        }
        private JsonObject snapshot() { return parseJsonObject(row.getSnapshotJson()); }
        private void persist(JsonObject snapshot) { row.setSnapshotJson(snapshot.toString());row.setHeartbeat(new java.util.Date());if (!storageTemplateUpgradeDao.update(row.getId(),row)) throw new CloudRuntimeException("Unable to persist ROOT checkpoint"); }
        private long currentRoot() {
            List<VolumeVO> roots = volumeDao.findByInstanceAndType(instance.getVmId(),com.cloud.storage.Volume.Type.ROOT);
            if (roots.size() != 1) throw new CloudRuntimeException("Exactly one current ROOT is required");return roots.get(0).getId();
        }
        private void sameTopology() { StorageRootTopologySnapshot.requireSame(snapshot().getAsJsonObject("topology"),rootTopology(instance)); }
        private void requireBinding(long rootId,long templateId) {
            VolumeVO root=volumeDao.findById(rootId);com.cloud.vm.UserVmVO vm=rootUpgradeVmDao.findById(instance.getVmId());
            if (currentRoot()!=rootId || root==null || root.getState()!=com.cloud.storage.Volume.State.Ready || !java.util.Objects.equals(root.getTemplateId(),templateId)
                    || vm.getTemplateId()!=templateId) throw new CloudRuntimeException("Current ROOT and SystemVM template binding changed");
        }
        public void preflight() {
            requireRootWriterIdle(instance,row);
            if (row.getSnapshotJson() != null) { sameTopology();return; }
            lifecycle.startAndAwaitCapabilities(instance,operation.getUuid());
            JsonObject result = rootPreflight(instance,shared,row.getTargetTemplateId(),false);
            if (!result.get("compatible").getAsBoolean()) throw new InvalidParameterValueException("ROOT preflight blocked: "+result.get("blockers"));
        }
        public void stageRoot() {
            if (row.getTargetRootVolumeId() == null) { VolumeVO target = swap.allocate(instance.getVmId(),row.getPreviousRootVolumeId(),rootUpgradeTemplateDao.findById(row.getTargetTemplateId()),row.getUuid());row.setTargetRootVolumeId(target.getId());storageTemplateUpgradeDao.update(row.getId(),row); }
            if (currentRoot() == row.getTargetRootVolumeId()) return;
            swap.prepare(instance.getVmId(),row.getPreviousRootVolumeId(),row.getTargetRootVolumeId(),row.getTargetTemplateId());
        }
        public void checkpoint() {
            if (row.getSnapshotJson() != null) { sameTopology();return; }
            checkpointConfigurationIdentity(instance);
            JsonObject previous = parseJsonObject(operation.getPreviousSnapshotJson());
            JsonObject value = new JsonObject();value.add("topology",rootTopology(instance));value.add("identity",previous.getAsJsonObject("nativeIdentityCapsule"));
            JsonObject generation=nativeConfigurationGeneration(instance,null,"status");
            value.add("sourceGeneration",generation.getAsJsonObject("generation"));
            if (!generation.has("configurationDesiredState")) throw new CloudRuntimeException("ROOT desired-state seed capability is unavailable");
            value.add("sourceDesiredState",generation.getAsJsonObject("configurationDesiredState"));
            value.add("runtime",rootGuest(instance,"operation observe",new JsonObject(),60));persist(value);
        }
        public void quiesce() {
            sameTopology();if (currentRoot() == row.getTargetRootVolumeId()) return;
            if (rootUpgradeVmDao.findById(instance.getVmId()).getState() == com.cloud.vm.VirtualMachine.State.Running) rootGuest(instance,"operation quiesce",scope(),60);
            JsonObject value=snapshot();if (!value.has("quiescedAt")) {value.addProperty("quiescedAt",System.currentTimeMillis());persist(value);}
            lifecycle.stop(instance.getVmId());
        }
        private JsonObject scope() {JsonObject scope=new JsonObject();scope.addProperty("instanceUuid",instance.getUuid());scope.addProperty("operationUuid",operation.getUuid());scope.addProperty("revision",operation.getRevision());return scope;}
        public void swapRoot() {
            sameTopology();if (currentRoot() != row.getTargetRootVolumeId()) swap.swap(instance.getVmId(),row.getPreviousRootVolumeId(),row.getTargetRootVolumeId(),row.getSourceTemplateId(),row.getTargetTemplateId(),rootUpgradeTemplateDao.findById(row.getTargetTemplateId()).getGuestOSId());sameTopology();
        }
        public void bootTarget() { requireBinding(row.getTargetRootVolumeId(),row.getTargetTemplateId());lifecycle.startAndAwaitCapabilities(instance,operation.getUuid());sameTopology();requireBinding(row.getTargetRootVolumeId(),row.getTargetTemplateId()); }
        public void restoreIdentity() {requireBinding(row.getTargetRootVolumeId(),row.getTargetTemplateId());sameTopology();restoreConfigurationIdentity(instance,snapshot().getAsJsonObject("identity"));}
        private void restoreMounts() {
            for (StorageServiceInstance.Protocol protocol : new StorageServiceInstance.Protocol[]{StorageServiceInstance.Protocol.NFS,StorageServiceInstance.Protocol.SMB}) {
                for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(),protocol)) {
                    VolumeVO volume = requireVolume(share.getVolumeId());JsonObject payload=createFileShareVolumePayload(instance,share,volume);payload.addProperty("importMode","MOUNT_EXISTING");
                    JsonObject observed=rootGuest(instance,"volume attach inspect",payload,120);String expected=getJsonString(parseJsonObject(share.getConfigJson()),"filesystemUuid");
                    if (expected==null && snapshot().getAsJsonObject("runtime").has("fileShareVolumes")) {
                        for (JsonElement entry:snapshot().getAsJsonObject("runtime").getAsJsonArray("fileShareVolumes")) {
                            JsonObject previous=entry.getAsJsonObject();if (volume.getUuid().equals(getJsonString(previous,"volumeUuid"))) expected=getJsonString(previous,"filesystemUuid");
                        }
                    }
                    if (!volume.getUuid().equals(getJsonString(observed,"volumeUuid")) || expected == null || !expected.equals(getJsonString(observed,"filesystemUuid"))) throw new CloudRuntimeException("Existing backing filesystem identity changed");
                }
            }
        }
        private void applyAll() {
            sameTopology();
            if (shared.getNetworkMode() == SharedFS.NetworkMode.STATIC) {
                List<NicVO> nics=nicDao.listByVmId(instance.getVmId());
                if (nics.size()!=1) throw new CloudRuntimeException("Static ROOT recovery requires its preserved NIC");
                JsonObject network=new JsonObject();network.addProperty("macAddress",nics.get(0).getMacAddress());network.addProperty("ipAddress",shared.getIpAddress());network.addProperty("cidr",shared.getCidr());
                if (StringUtils.isNotBlank(shared.getGateway())) network.addProperty("gateway",shared.getGateway());
                if (StringUtils.isNotBlank(shared.getDns1())) network.addProperty("dns1",shared.getDns1());
                if (StringUtils.isNotBlank(shared.getDns2())) network.addProperty("dns2",shared.getDns2());
                StorageServiceGuestCommandResult configured=guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),"configure-sharedfs-static-network",network.toString(),60,Collections.emptySet()));
                if (!configured.isSuccess()) throw new CloudRuntimeException("Preserved static network recovery failed");
            }
            for (StorageServiceProtocolVO protocol : storageServiceProtocolDao.listByInstanceId(instance.getId())) {
                if (protocol.isEnabled()) ensureGuestProtocolListenAddress(instance,protocol.getListenIp(),resolveProtocolListenAddress(instance,protocol.getListenIp()),protocol.getPort());
            }
            restoreMounts();
            for (StoragePosixDirectoryPolicyVO policy : storagePosixPolicyDao.listByInstance(instance.getId())) {
                if (!"Ready".equals(policy.getState())) throw new CloudRuntimeException("Common directory policy is not ready");
                dispatchPosixDirectoryCommand(instance,"apply",posixPolicyPayload(instance,policy));
            }
            JsonObject source=snapshot().getAsJsonObject("sourceDesiredState");
            for (StorageServiceInstance.Protocol protocol : StorageServiceInstance.Protocol.values()) {
                String path=protocol==StorageServiceInstance.Protocol.NFS ? "desired-state/nfs-export-apply.json" : protocol==StorageServiceInstance.Protocol.SMB ? "desired-state/smb-share-apply.json" : protocol==StorageServiceInstance.Protocol.ISCSI ? "iscsi-targets.json" : "nvmeof-subsystems.json";
                if (source.get(path).isJsonNull()) continue;
                if (protocol != StorageServiceInstance.Protocol.NVME_OF || !Boolean.TRUE.equals(configurationNativeNvmeReplayed.get())) applyStorageServiceProtocolDesiredState(instance,protocol);
            }
            verifyReconciledStorageDesiredState(instance);sameTopology();
        }
        private void seedDesired() {
            JsonObject status=nativeConfigurationGeneration(instance,null,"status");
            if (getJsonString(status,"pendingOperationUuid")!=null) return;
            JsonObject value=snapshot();JsonObject request=scope();request.addProperty("sourceKind","INTERNAL_ROOT_GENERATION");
            request.add("previousGeneration",value.getAsJsonObject("sourceGeneration"));request.add("configurationDesiredState",value.getAsJsonObject("sourceDesiredState"));
            request.add("expectedPreviousGeneration",status.getAsJsonObject("generation"));rootGuest(instance,"operation generation seed",request,30);
        }
        public void reconcile() {requireBinding(row.getTargetRootVolumeId(),row.getTargetTemplateId());sameTopology();seedDesired();applyAll();transferGeneration(false);}
        private void transferGeneration(boolean previousRoot) {
            JsonObject status=nativeConfigurationGeneration(instance,null,"status");String pending=getJsonString(status,"pendingOperationUuid");
            if (pending != null) { if (!operation.getUuid().equals(pending)) throw new CloudRuntimeException("Another native generation requires recovery");return; }
            JsonObject generation=status.getAsJsonObject("generation");
            if (operation.getUuid().equals(getJsonString(generation,"operationUuid")) && generation.get("revision").getAsLong()==operation.getRevision()) return;
            JsonObject source=snapshot().getAsJsonObject("sourceGeneration");
            JsonObject request=scope();request.add("previousGeneration",source);request.add("expectedPreviousGeneration",generation);
            if (previousRoot && !manualRollback) {
                if (!source.equals(generation) || !"IN_SYNC".equals(getJsonString(status,"generationStatus"))) throw new CloudRuntimeException("Recovered previous ROOT generation differs");return;
            }
            rootGuest(instance,"operation generation "+(previousRoot ? "align" : "adopt"),request,30);
            nativeConfigurationGeneration(instance,operation,"begin");
        }
        public void verify() {
            requireBinding(row.getTargetRootVolumeId(),row.getTargetTemplateId());sameTopology();verifyReconciledStorageDesiredState(instance);JsonObject health=rootGuest(instance,"operation verify",new JsonObject(),60);
            if (!"ok".equalsIgnoreCase(getJsonString(health,"status"))) throw new CloudRuntimeException("ROOT runtime health is degraded");
            nativeConfigurationGeneration(instance,operation,"verify");nativeConfigurationGeneration(instance,operation,"commit");
            row.setVerificationJson(health.toString());JsonObject value=snapshot();value.addProperty("serviceVerifiedAt",System.currentTimeMillis());persist(value);
        }
        public void commit() { complete(true); }
        private void complete(boolean target) {
            requireBinding(target?row.getTargetRootVolumeId():row.getPreviousRootVolumeId(),target?row.getTargetTemplateId():row.getSourceTemplateId());sameTopology();
            operation.setSnapshotJson(captureConfigurationSnapshot(instance.getId()));operation.setResultJson(rootUpgradeJson(row).toString());
            new StorageServiceConfiguration(StorageServiceManagerImpl.this,storageConfigArtifactDao,storageOperationDao).promoteVerified(instance,operation,() -> {
                operation.setState("COMPLETE");operation.setPhase("COMPLETE");operation.setProgress(100);operation.setCompleted(new java.util.Date());operation.setHeartbeat(new java.util.Date());
                if (!storageOperationDao.update(operation.getId(),operation)) throw new CloudRuntimeException("Unable to commit ROOT writer");
                instance.setCurrentTemplateId(target ? row.getTargetTemplateId() : row.getSourceTemplateId());
                instance.setPreviousTemplateId(target ? row.getSourceTemplateId() : row.getTargetTemplateId());
                instance.setTemplateUpgradeState(target ? "COMPLETE" : "ROLLED_BACK");instance.setLastTemplateUpgradeId(row.getId());instance.setTemplateVerifiedAt(new java.util.Date());
                if (!storageServiceInstanceDao.update(instance.getId(),instance)) throw new CloudRuntimeException("Unable to commit template instance projection");
                row.setState(target ? "COMPLETE" : "ROLLED_BACK");row.setPhase(row.getState());row.setProgress(100);row.setCompleted(new java.util.Date());
                row.setRollbackRetainUntil(new java.util.Date(System.currentTimeMillis()+Math.min(1440,Math.max(1,StorageServiceInstance.StorageServiceTemplateRollbackRetentionHours.value()))*60L*60*1000));
                if (!storageTemplateUpgradeDao.update(row.getId(),row)) throw new CloudRuntimeException("Unable to commit retained ROOT transaction");
            });
        }
        public void restorePreviousRoot() {
            if (manualRollback && !snapshot().has("manualRollbackGeneration")) {
                lifecycle.startAndAwaitCapabilities(instance,operation.getUuid());verifyReconciledStorageDesiredState(instance);checkpointConfigurationIdentity(instance);
                JsonObject value=snapshot();value.add("identity",parseJsonObject(operation.getPreviousSnapshotJson()).getAsJsonObject("nativeIdentityCapsule"));
                JsonObject observed=nativeConfigurationGeneration(instance,null,"status");JsonObject source=observed.getAsJsonObject("generation");
                if (!observed.has("configurationDesiredState")) throw new CloudRuntimeException("Current ROOT seed capability is unavailable");
                value.add("sourceGeneration",source);value.add("sourceDesiredState",observed.getAsJsonObject("configurationDesiredState"));value.add("manualRollbackGeneration",source);persist(value);
            }
            if (row.getSnapshotJson()==null) return;
            sameTopology();long root=currentRoot();
            if (root == row.getPreviousRootVolumeId()) {requireBinding(row.getPreviousRootVolumeId(),row.getSourceTemplateId());return;}
            if (row.getTargetRootVolumeId()==null || root!=row.getTargetRootVolumeId()) throw new CloudRuntimeException("Rollback ROOT binding changed");
            JsonObject interrupted=snapshot();if (!interrupted.has("rollbackQuiescedAt")) {interrupted.addProperty("rollbackQuiescedAt",System.currentTimeMillis());persist(interrupted);}
            if (rootUpgradeVmDao.findById(instance.getVmId()).getState()==com.cloud.vm.VirtualMachine.State.Running) {
                try {rootGuest(instance,"operation quiesce",scope(),60);}
                catch (RuntimeException qgaUnavailable) {if (manualRollback) throw qgaUnavailable;logger.warn("Target ROOT QGA is unavailable; continuing graceful VM shutdown for rollback {}",row.getUuid());}
            }
            lifecycle.stop(instance.getVmId());swap.swap(instance.getVmId(),row.getTargetRootVolumeId(),row.getPreviousRootVolumeId(),row.getTargetTemplateId(),row.getSourceTemplateId(),row.getPreviousGuestOsId());sameTopology();
        }
        public void bootPrevious() {requireBinding(row.getPreviousRootVolumeId(),row.getSourceTemplateId());lifecycle.startAndAwaitCapabilities(instance,operation.getUuid());}
        public void reconcilePrevious() {
            if (row.getSnapshotJson()==null) return;
            restoreConfigurationIdentity(instance,snapshot().getAsJsonObject("identity"));
            if (manualRollback) seedDesired();applyAll();transferGeneration(true);
        }
        public void verifyPrevious() {
            requireBinding(row.getPreviousRootVolumeId(),row.getSourceTemplateId());verifyReconciledStorageDesiredState(instance);JsonObject health=rootGuest(instance,"operation verify",new JsonObject(),60);
            if (!"ok".equalsIgnoreCase(getJsonString(health,"status"))) throw new CloudRuntimeException("Previous ROOT runtime health is degraded");
            row.setRollbackResultJson(health.toString());
            if (row.getSnapshotJson()!=null) {JsonObject value=snapshot();value.addProperty("serviceVerifiedAt",System.currentTimeMillis());value.addProperty("rollbackServiceVerifiedAt",System.currentTimeMillis());persist(value);}
            if (manualRollback) {nativeConfigurationGeneration(instance,operation,"verify");nativeConfigurationGeneration(instance,operation,"commit");complete(false);}
        }
        public void finished(boolean success) {
            if (!success && !manualRollback) {
                if ("ROLLED_BACK".equals(row.getState())) {
                    instance.setCurrentTemplateId(row.getSourceTemplateId());instance.setPreviousTemplateId(row.getTargetTemplateId());instance.setTemplateUpgradeState("ROLLED_BACK");instance.setLastTemplateUpgradeId(row.getId());instance.setTemplateVerifiedAt(new java.util.Date());storageServiceInstanceDao.update(instance.getId(),instance);
                }
                operation.setState(row.getState());operation.setPhase(row.getPhase());operation.setProgress(100);operation.setCompleted(new java.util.Date());operation.setHeartbeat(new java.util.Date());operation.setDiagnostic(row.getErrorMessage());storageOperationDao.update(operation.getId(),operation);
            } else {nativeConfigurationGeneration(instance,operation,"finish");cleanupConfigurationIdentityCheckpoint(operation);}
            if ("Stopped".equals(row.getPreviousVmState())) lifecycle.stop(instance.getVmId());
        }
    }

    @Override
    public org.apache.cloudstack.api.response.StorageServiceConfigArtifactResponse storageServiceConfiguration(final StorageConfigRequest cmd) {
        if (!StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value()) throw new InvalidParameterValueException("Verified configuration service is disabled until compatible runtime preparation completes");
        return new StorageServiceConfiguration(this, storageConfigArtifactDao, storageOperationDao).execute(cmd);
    }

    protected void requireConfigurationAdministrator() {
        if (!storageAccountManager.isRootAdmin(org.apache.cloudstack.context.CallContext.current().getCallingAccount().getId())) {
            throw new com.cloud.exception.PermissionDeniedException("Only a root administrator may manage configuration artifacts");
        }
    }
    protected Map<String, Long> configurationResourceIds(long instanceId) {
        Map<String, Long> result = new HashMap<>();
        for (JsonElement table : parseJsonObject(captureConfigurationSnapshot(instanceId)).getAsJsonArray("tables")) {
            for (JsonElement value : table.getAsJsonObject().getAsJsonArray("rows")) {
                JsonObject row = value.getAsJsonObject();result.put(getJsonString(row, "uuid"), row.get("id").getAsLong());
            }
        }
        return result;
    }
    protected Long configurationVolumeId(StorageServiceInstanceVO instance, String uuid) {
        VolumeVO volume = volumeDao.findByUuid(uuid);
        if (volume == null || volume.getVolumeType() != com.cloud.storage.Volume.Type.DATADISK
                || volume.getState() != com.cloud.storage.Volume.State.Ready || volume.getAccountId() != instance.getAccountId()
                || volume.getDataCenterId() != instance.getDataCenterId() || instance.getVmId() == null
                || !instance.getVmId().equals(volume.getInstanceId())) {
            throw new InvalidParameterValueException("Mapped configuration volume must be a Ready data volume attached to the selected service in the same owner and zone");
        }
        validateStorageServiceBackingVolume(instance, volume.getId(), "configuration restore");return volume.getId();
    }
    protected void preflightConfigurationAdditionalVolumes(JsonObject blueprint, JsonObject mappings, String initialSource) {
        org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd create = configurationCreateCommand(blueprint);
        org.apache.cloudstack.storage.sharedfs.SharedFS proposed = configurationSharedFsService.preflightSharedFS(create);
        for (Map.Entry<String, JsonElement> mapping : mappings.entrySet()) {
            if (initialSource.equals(mapping.getKey())) continue;
            VolumeVO volume = volumeDao.findByUuid(mapping.getValue().getAsString());
            if (volume == null || volume.getVolumeType() != com.cloud.storage.Volume.Type.DATADISK
                    || volume.getState() != com.cloud.storage.Volume.State.Ready || volume.getInstanceId() != null
                    || volume.getAccountId() != proposed.getAccountId() || volume.getDataCenterId() != proposed.getDataCenterId()
                    || volume.getPoolId() == null || volume.getSize() == null || volume.getSize() <= 0) {
                throw new InvalidParameterValueException("Additional clone backing must be an explicitly selected Ready unattached data volume in the target owner and zone");
            }
            org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool = configurationStoragePoolDao.findById(volume.getPoolId());
            if (pool == null || pool.getStatus() != com.cloud.storage.StoragePoolStatus.Up) throw new InvalidParameterValueException("Additional clone storage is unavailable");
            com.cloud.utils.db.SearchCriteria<org.apache.cloudstack.storage.sharedfs.SharedFSVO> reservations = sharedFSDao.createSearchCriteria();
            reservations.addAnd("volumeId", com.cloud.utils.db.SearchCriteria.Op.EQ, volume.getId());
            if (!sharedFSDao.search(reservations, null).isEmpty()) throw new InvalidParameterValueException("Additional clone volume is reserved by another service");
        }
    }
    protected void prepareConfigurationAdditionalVolumes(StorageServiceInstanceVO instance, JsonObject mappings, String initialSource) {
        for (Map.Entry<String, JsonElement> mapping : mappings.entrySet()) {
            if (initialSource.equals(mapping.getKey())) continue;
            VolumeVO volume = volumeDao.findByUuid(mapping.getValue().getAsString());
            if (volume == null || volume.getAccountId() != instance.getAccountId() || volume.getDataCenterId() != instance.getDataCenterId()
                    || volume.getVolumeType() != com.cloud.storage.Volume.Type.DATADISK) {
                throw new InvalidParameterValueException("Additional clone volume binding changed before attachment");
            }
            validateStorageServiceBackingVolume(instance, volume.getId(), "configuration clone");
            if (volume.getInstanceId() == null) volumeApiService.attachVolumeToVM(instance.getVmId(), waitForFileShareVolumeAttachable(volume.getId()).getId(), null, true);
            configurationVolumeId(instance, volume.getUuid());
        }
    }
    protected void requireProtectedIdentityTransport(StorageServiceInstanceVO instance, String operationUuid) {
        JsonObject request = new JsonObject();request.addProperty("instanceUuid", instance.getUuid());request.addProperty("operationUuid", operationUuid);
        StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "identity capsule capabilities", request.toString(), 30, Collections.emptySet()));
        JsonObject capability = result.isSuccess() ? parseJsonObject(normalizeRuntimeResultJson(result.getResultJson())) : new JsonObject();
        if (!Boolean.TRUE.equals(getJsonBoolean(capability, "success")) || !Boolean.TRUE.equals(getJsonBoolean(capability, "protectedStdinTransport"))) {
            throw new InvalidParameterValueException("The current host agent does not support protected identity stdin transport");
        }
    }
    protected JsonObject exportConfigurationIdentity(StorageServiceInstanceVO instance, String operationUuid, java.security.KeyPair key) {
        requireProtectedIdentityTransport(instance, operationUuid);
        JsonArray names = new JsonArray();Set<String> unique = new HashSet<>();
        for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB)) {
            for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                if ((rule.getPrincipalType() == StorageServiceInstance.PrincipalType.LOCAL_USER || rule.getPrincipalType() == StorageServiceInstance.PrincipalType.LOCAL_GROUP)
                        && unique.add(rule.getPrincipal())) names.add(rule.getPrincipal());
            }
            if ("FORCED_UID_GID".equals(getJsonString(parseJsonObject(share.getConfigJson()), "posixOwnershipMode"))) {
                String suffix = share.getUuid().replace("-", "").substring(0, 20);
                for (String name : new String[] {"sf_u_" + suffix, "sf_g_" + suffix}) if (unique.add(name)) names.add(name);
            }
        }
        JsonObject request = StorageIdentityCapsule.exportRequest(instance.getUuid(), operationUuid, key, names);
        JsonArray nvmeHosts = new JsonArray();Set<String> hostNames = new HashSet<>();
        for (StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (!isNvmeOfSubsystem(target)) continue;
            for (StorageAccessRuleVO rule : listNvmeOfHostAclRules(target)) {
                JsonObject config = parseJsonObject(rule.getConfigJson());
                if ((Boolean.TRUE.equals(getJsonBoolean(config, "dhChapEnabled")) || Boolean.TRUE.equals(getJsonBoolean(config, "dhChapCtrlEnabled")))
                        && hostNames.add(rule.getPrincipal())) nvmeHosts.add(rule.getPrincipal());
            }
        }
        request.add("nvmeHosts", nvmeHosts);
        StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "identity capsule export", request.toString(), 60, Set.of("capsule")));
        if (!result.isSuccess()) throw new CloudRuntimeException("Protected local identity snapshot is unavailable");
        JsonObject observed = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        if (!Boolean.TRUE.equals(getJsonBoolean(observed, "success")) || !observed.has("capsule")) throw new CloudRuntimeException("Identity snapshot was not verified");
        return observed.getAsJsonObject("capsule");
    }
    protected void importConfigurationIdentity(StorageServiceInstanceVO instance, String operationUuid, JsonObject capsule, byte[] protectedKey) {
        requireProtectedIdentityTransport(instance, operationUuid);
        JsonObject request = StorageIdentityCapsule.importRequest(instance.getUuid(), operationUuid, capsule, protectedKey);
        if (storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF).stream().anyMatch(StorageServiceProtocolVO::isEnabled)
                || !storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF).isEmpty()) {
            request.add("nvmeDesired", buildNvmeOfDesiredPayload(instance, Collections.emptyMap(), Collections.emptyMap()));
        }
        StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "identity capsule import", request.toString(), 60, Set.of("credentialPrivateKey", "capsule")));
        if (!result.isSuccess()) throw new CloudRuntimeException("Protected local identity recovery failed");
        JsonObject observed = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        if (!Boolean.TRUE.equals(getJsonBoolean(observed, "success"))) throw new CloudRuntimeException("Identity recovery was not verified");
        if (Boolean.TRUE.equals(getJsonBoolean(observed, "nvmeRestored"))) configurationNativeNvmeReplayed.set(true);
    }
    protected void checkpointConfigurationIdentity(StorageServiceInstanceVO instance) {
        StorageServiceOperationVO operation = storageWriterOperation.get();
        if (operation == null || operation.getPreviousSnapshotJson() == null) throw new CloudRuntimeException("Configuration operation snapshot is unavailable");
        java.security.KeyPair key = StorageIdentityCapsule.wrappingKey();
        byte[] protectedKey = StorageIdentityCapsule.protectedPrivateKey(key);
        JsonObject capsule = exportConfigurationIdentity(instance, operation.getUuid(), key);
        String keyId = java.util.UUID.nameUUIDFromBytes(("identity-key:" + operation.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        StorageConfigArtifactStore store = new StorageConfigArtifactStore(java.nio.file.Path.of(System.getProperty("cloudstack.storage.identity.path",
                "/var/lib/cloudstack-management/storage-identity-capsules")));
        byte[] data = capsule.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        store.write(keyId, protectedKey);
        try { store.write(operation.getUuid(), data); }
        catch (RuntimeException failure) { store.remove(keyId);throw failure; }
        JsonObject reference = new JsonObject();reference.addProperty("operationUuid", operation.getUuid());reference.addProperty("keyId", keyId);
        reference.addProperty("capsuleSha256", StorageConfigArchive.sha256(data));reference.addProperty("keySha256", StorageConfigArchive.sha256(protectedKey));
        JsonObject snapshot = parseJsonObject(operation.getPreviousSnapshotJson());snapshot.add("nativeIdentityCapsule", reference);
        operation.setPreviousSnapshotJson(snapshot.toString());storageOperationDao.update(operation.getId(), operation);
    }
    protected void restoreConfigurationIdentity(StorageServiceInstanceVO instance, JsonObject reference) {
        String operationUuid = getJsonString(reference, "operationUuid");
        StorageConfigArtifactStore store = new StorageConfigArtifactStore(java.nio.file.Path.of(System.getProperty("cloudstack.storage.identity.path",
                "/var/lib/cloudstack-management/storage-identity-capsules")));
        byte[] capsule = store.read(operationUuid, getJsonString(reference, "capsuleSha256"));
        byte[] key = store.read(getJsonString(reference, "keyId"), getJsonString(reference, "keySha256"));
        importConfigurationIdentity(instance, operationUuid, parseJsonObject(new String(capsule, java.nio.charset.StandardCharsets.UTF_8)), key);
    }
    protected void cleanupConfigurationIdentityCheckpoint(StorageServiceOperationVO operation) {
        if (operation == null || !Set.of("COMPLETE", "BLOCKED", "ROLLED_BACK", "RECONCILED_SUPERSEDED").contains(operation.getState())
                || operation.getPreviousSnapshotJson() == null) return;
        JsonObject snapshot = parseJsonObject(operation.getPreviousSnapshotJson());
        if (!snapshot.has("nativeIdentityCapsule")) return;
        JsonObject reference = snapshot.getAsJsonObject("nativeIdentityCapsule");
        try {
            StorageConfigArtifactStore store = new StorageConfigArtifactStore(java.nio.file.Path.of(System.getProperty("cloudstack.storage.identity.path",
                    "/var/lib/cloudstack-management/storage-identity-capsules")));
            store.remove(getJsonString(reference, "operationUuid"));store.remove(getJsonString(reference, "keyId"));
            reference.addProperty("cleanupState", "CLEANED");
        } catch (RuntimeException retry) { reference.addProperty("cleanupState", "PENDING"); }
        operation.setPreviousSnapshotJson(snapshot.toString());
        try { storageOperationDao.update(operation.getId(), operation); }
        catch (RuntimeException pending) { logger.warn("Identity capsule cleanup audit update is pending for operation {}", operation.getUuid()); }
    }
    protected org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd configurationCreateCommand(JsonObject blueprint) {
        requireConfigurationAdministrator();JsonObject parameters = blueprint.deepCopy();
        for (String key : new String[] {"zoneid", "networkid", "serviceofferingid", "diskofferingid", "storageid", "existingvolumeid"}) {
            if (!parameters.has(key) || parameters.get(key).isJsonNull()) continue;
            String uuid = parameters.get(key).getAsString();Long id = null;
            if ("zoneid".equals(key)) { DataCenterVO row = dataCenterDao.findByUuid(uuid);if (row != null) id = row.getId(); }
            else if ("networkid".equals(key)) { NetworkVO row = networkDao.findByUuid(uuid);if (row != null) id = row.getId(); }
            else if ("serviceofferingid".equals(key)) { ServiceOfferingVO row = serviceOfferingDao.findByUuid(uuid);if (row != null) id = row.getId(); }
            else if ("diskofferingid".equals(key)) { com.cloud.storage.DiskOfferingVO row = configurationDiskOfferingDao.findByUuid(uuid);if (row != null) id = row.getId(); }
            else if ("storageid".equals(key)) { org.apache.cloudstack.storage.datastore.db.StoragePoolVO row = configurationStoragePoolDao.findByUuid(uuid);if (row != null) id = row.getId(); }
            else { VolumeVO row = volumeDao.findByUuid(uuid);if (row != null) id = row.getId(); }
            if (id == null) throw new InvalidParameterValueException("New-service blueprint resource is unavailable: " + key);
            parameters.addProperty(key, id);
        }
        org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd cmd =
                (org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd) StorageConfigCommandBinding.bind(
                        org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd.class, parameters);
        com.cloud.utils.component.ComponentContext.inject(cmd);return cmd;
    }
    protected void preflightConfigurationNewService(JsonObject blueprint) {
        configurationSharedFsService.preflightSharedFS(configurationCreateCommand(blueprint));
    }
    protected StorageServiceInstanceVO createConfigurationNewService(JsonObject blueprint) {
        org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd cmd = configurationCreateCommand(blueprint);
        org.apache.cloudstack.storage.sharedfs.SharedFS shared = configurationSharedFsService.allocSharedFS(cmd);
        cmd.setEntityId(shared.getId());cmd.setEntityUuid(shared.getUuid());
        try { shared = configurationSharedFsService.deploySharedFS(cmd); }
        catch (Exception failure) { throw new CloudRuntimeException("New configuration service deployment failed; preserved resources require recorded recovery", failure); }
        org.apache.cloudstack.storage.sharedfs.SharedFSVO current = sharedFSDao.findById(shared.getId());
        StorageServiceInstanceVO instance = current == null || current.getVmId() == null ? null : storageServiceInstanceDao.findByVmId(current.getVmId());
        if (instance == null) throw new CloudRuntimeException("New service instance reconciliation did not complete");return instance;
    }
    protected VolumeVO configurationInitialVolume(StorageServiceInstanceVO instance) {
        org.apache.cloudstack.storage.sharedfs.SharedFSVO shared = sharedFSDao.findByVm(instance.getVmId());
        if (shared == null || shared.getVolumeId() == null) throw new CloudRuntimeException("New service initial volume is unavailable");
        return requireVolume(shared.getVolumeId());
    }
    protected StorageServiceInstanceVO configurationInstanceByUuid(String uuid) {
        StorageServiceInstanceVO instance = storageServiceInstanceDao.findByUuid(uuid);
        if (instance == null) throw new InvalidParameterValueException("Configuration target instance is unavailable");
        return requireInstance(instance.getId());
    }
    public static final class ConfigurationTargetCommand extends org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd {
        private final long instanceId;private final org.apache.cloudstack.api.BaseCmd original;
        public ConfigurationTargetCommand(long instanceId, org.apache.cloudstack.api.BaseCmd original) { this.instanceId = instanceId;this.original = original; }
        public Long getInstanceId() { return instanceId; }
        public String getCommandName() { return original.getCommandName(); }
        public String getEventType() { return "STORAGE.CONFIG.RESTORE"; }
        public String getEventDescription() { return "Applying a reviewed service configuration"; }
        public long getEntityOwnerId() { return original.getEntityOwnerId(); }
        public void execute() { throw new UnsupportedOperationException("Internal configuration target command cannot execute as an API"); }
        public String getIdempotencyKey() {
            return original instanceof org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd
                    ? ((org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd) original).getIdempotencyKey() : null;
        }
    }
    protected org.apache.cloudstack.api.BaseCmd configurationTargetCommand(long instanceId, org.apache.cloudstack.api.BaseCmd original) {
        return new ConfigurationTargetCommand(instanceId, original);
    }
    protected void preflightConfigurationRuntimeBundle(String bundleUuid) {
        requireConfigurationAdministrator();
        StorageServiceRuntimeBundleVO bundle = storageRuntimeBundleDao.findByUuid(bundleUuid);
        if (bundle == null) throw new InvalidParameterValueException("New-service runtime bundle is unavailable");
        JsonObject verified = runtimeUpgradeManager.verifyAvailableBundle(bundle.getId());
        if (verified == null || !Boolean.TRUE.equals(getJsonBoolean(verified, "verified"))) {
            throw new InvalidParameterValueException("New-service runtime bundle has not passed signature verification");
        }
    }
    protected void upgradeConfigurationNewServiceRuntime(StorageServiceInstanceVO instance, String bundleUuid) {
        requireConfigurationAdministrator();
        StorageServiceRuntimeBundleVO bundle = storageRuntimeBundleDao.findByUuid(bundleUuid);
        org.apache.cloudstack.storage.sharedfs.SharedFSVO shared = sharedFSDao.findByVm(instance.getVmId());
        if (bundle == null || shared == null) throw new InvalidParameterValueException("New service runtime bundle or SharedFS is unavailable");
        if (Long.valueOf(bundle.getId()).equals(instance.getCurrentRuntimeBundleId())) {
            requireProtectedIdentityTransport(instance, java.util.UUID.randomUUID().toString());
            return;
        }
        JsonObject parameters = new JsonObject();parameters.addProperty("sharedfilesystemid", shared.getId());parameters.addProperty("bundleid", bundle.getId());
        org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd preflight =
                (org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd) StorageConfigCommandBinding.bind(
                        org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd.class, parameters);
        JsonObject response = parseJsonObject(GSON.toJson(runtimeUpgradeManager.preflight(preflight)));
        if (!"PREFLIGHT_READY".equals(getJsonString(response, "state"))) throw new CloudRuntimeException("New service runtime preflight did not pass");
        StorageServiceRuntimeUpgradeVO upgrade = storageRuntimeUpgradeDao.findByUuid(getJsonString(response, "id"));
        JsonObject execute = new JsonObject();execute.addProperty("upgradeid", upgrade.getId());
        org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd cmd =
                (org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd) StorageConfigCommandBinding.bind(
                        org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd.class, execute);
        JsonObject completed = parseJsonObject(GSON.toJson(runtimeUpgradeManager.upgrade(cmd)));
        if (!"COMPLETE".equals(getJsonString(completed, "state"))) throw new CloudRuntimeException("New service runtime upgrade did not complete");
    }
    protected void prepareConfigurationInitialVolume(StorageServiceInstanceVO instance, JsonObject blueprint) {
        VolumeVO volume = configurationInitialVolume(instance);
        configurationVolumeId(instance, volume.getUuid());
        boolean existing = "EXISTING".equals(getJsonString(blueprint, "backingvolumemode"));
        JsonObject payload = new JsonObject();payload.addProperty("shareUuid", instance.getUuid());
        payload.addProperty("volumeUuid", volume.getUuid());payload.addProperty("volumeName", volume.getName());
        payload.addProperty("volumeSizeBytes", volume.getSize());payload.addProperty("filesystem", getJsonString(blueprint, "filesystem"));
        payload.addProperty("mountPath", "/srv/ablestack-storage/volumes/" + volume.getUuid());
        payload.addProperty("importMode", existing ? "MOUNT_EXISTING" : "FORMAT_IF_EMPTY");
        int deadline = backingVolumeFormatDeadline(volume.getSize());payload.addProperty("formatDeadlineSeconds", deadline);
        payload.addProperty("operationId", "volume-" + volume.getUuid());
        StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "volume attach inspect", payload.toString(), Math.max(StorageServiceInstance.StorageServiceCommandTimeout.value(), deadline + 120), Collections.emptySet()));
        if (!result.isSuccess()) throw new CloudRuntimeException("New configuration service initial volume preparation failed: " + result.getDetails());
        JsonObject observed = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        if (!Boolean.TRUE.equals(getJsonBoolean(observed, "success")) || !volume.getUuid().equals(getJsonString(observed, "volumeUuid"))
                || StringUtils.isBlank(getJsonString(observed, "filesystemUuid"))) throw new CloudRuntimeException("New service initial filesystem identity was not verified");
    }
    protected void prepareConfigurationDirectory(StorageServiceInstanceVO instance, String volumeUuid, String relative) {
        Long volumeId = configurationVolumeId(instance, volumeUuid);VolumeVO volume = requireVolume(volumeId);
        JsonObject config = new JsonObject();config.addProperty("relativeSharePath", PosixDirectoryPolicy.relativePath(relative));
        config.addProperty("createDirectory", true);config.addProperty("importMode", "MOUNT_EXISTING");
        StorageFileShareVO preparation = new StorageFileShareVO(instance.getId(), StorageServiceInstance.Protocol.NFS, "configuration-directory",
                "/export/configuration-directory", volumeId, "XFS", null, StorageServiceInstance.ResourceState.Allocated, config.toString());
        JsonObject payload = createFileShareVolumePayload(instance, preparation, volume);
        payload.addProperty("importMode", "MOUNT_EXISTING");
        StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "volume attach inspect", payload.toString(), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
        if (!result.isSuccess()) throw new CloudRuntimeException("Configuration directory preparation failed: " + result.getDetails());
        JsonObject observed = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        if (!Boolean.TRUE.equals(getJsonBoolean(observed, "success")) || !volume.getUuid().equals(getJsonString(observed, "volumeUuid"))) {
            throw new CloudRuntimeException("Configuration directory backing identity was not verified");
        }
    }
    protected String captureConfigurationSnapshot(long instanceId) { return new StorageServiceDesiredSnapshot().capture(instanceId); }
    protected JsonObject observeConfigurationRuntime(StorageServiceInstanceVO instance, String command) {
        if (!Set.of("health", "inventory", "sessions").contains(command)) throw new InvalidParameterValueException("Unsupported configuration collector");
        final JsonObject unavailable = new JsonObject();unavailable.addProperty("status", "UNAVAILABLE");unavailable.addProperty("success", false);
        if (instance.getVmId() == null) return unavailable;
        VMInstanceVO vm = vmInstanceDao.findById(instance.getVmId());
        if (vm == null || vm.getState() != com.cloud.vm.VirtualMachine.State.Running) return unavailable;
        try {
            StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(), "inventory".equals(command) ? "operation observe" : "health".equals(command) ? "operation verify" : command, "", 15, Collections.emptySet()));
            if (!result.isSuccess()) return unavailable;
            return StorageConfigSemantic.redact(parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()))).getAsJsonObject();
        } catch (RuntimeException failure) { return unavailable; }
    }
    protected JsonObject configurationInstanceMetadata(StorageServiceInstanceVO instance) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("uuid", instance.getUuid());metadata.addProperty("name", instance.getName());
        metadata.addProperty("provider", instance.getProvider());metadata.addProperty("state", instance.getState().name());
        String productVersion = com.cloud.server.ManagementServer.class.getPackage().getImplementationVersion();
        metadata.addProperty("productVersion", productVersion == null ? "unknown" : productVersion);
        DataCenterVO zone = dataCenterDao.findById(instance.getDataCenterId());if (zone != null) metadata.addProperty("zoneUuid", zone.getUuid());
        ServiceOfferingVO offering = instance.getServiceOfferingId() == null ? null : serviceOfferingDao.findById(instance.getServiceOfferingId());
        if (offering != null) metadata.addProperty("serviceOfferingUuid", offering.getUuid());
        VMInstanceVO vm = instance.getVmId() == null ? null : vmInstanceDao.findById(instance.getVmId());
        if (vm != null) { metadata.addProperty("vmUuid", vm.getUuid());metadata.addProperty("vmState", vm.getState().name()); }
        return metadata;
    }
    protected Map<Long, JsonObject> configurationVolumeMetadata(String snapshot) {
        Map<Long, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement table : parseJsonObject(snapshot).getAsJsonArray("tables")) {
            for (JsonElement value : table.getAsJsonObject().getAsJsonArray("rows")) {
                JsonObject row = value.getAsJsonObject();
                if (!row.has("volume_id") || row.get("volume_id").isJsonNull()) continue;
                long id = row.get("volume_id").getAsLong();if (result.containsKey(id)) continue;
                VolumeVO volume = requireVolume(id);JsonObject item = new JsonObject();
                item.addProperty("uuid", volume.getUuid());item.addProperty("name", volume.getName());
                item.addProperty("size", volume.getSize());item.addProperty("type", volume.getVolumeType().name());item.addProperty("state", volume.getState().name());
                if (row.has("config_json") && !row.get("config_json").isJsonNull()) {
                    JsonObject config = parseJsonObject(row.get("config_json").getAsString());
                    String filesystem = getJsonString(config, "filesystemUuid");if (filesystem != null) item.addProperty("filesystemUuid", filesystem);
                }
                result.put(id, item);
            }
        }
        return result;
    }


    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> commands = new ArrayList<>();
        if (!SharedFS.SharedFSFeatureEnabled.value()) {
            return commands;
        }
        commands.add(CreateStorageServiceInstanceCmd.class);
        commands.add(ListStorageServiceInstancesCmd.class);
        commands.add(EnableStorageServiceProtocolCmd.class);
        commands.add(DeleteStorageServiceProtocolCmd.class);
        commands.add(CreateStorageNfsExportCmd.class);
        if (StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value()) {
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageServiceConfigBackupCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceConfigBackupsCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.DownloadStorageServiceConfigBackupCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.UploadStorageServiceConfigBackupCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ValidateStorageServiceConfigImportCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.PlanStorageServiceConfigRestoreCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageServiceConfigRestoreCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceConfigImportsCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceConfigBackupCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceConfigImportCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceRestorePointsCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageServiceLastKnownGoodCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.PlanStorageServiceLastKnownGoodRestoreCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.RestoreStorageServiceLastKnownGoodCmd.class);
            commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.VerifyStorageServiceConfigurationCmd.class);
        }
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStoragePosixDirectoryPolicyCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStoragePosixDirectoryPolicyCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStoragePosixDirectoryPolicyCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStoragePosixDirectoryPolicyCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ListStoragePosixDirectoryPoliciesCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageNfsServiceSettingsCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsServiceSettingsCmd.class);
        commands.add(UpdateStorageNfsExportCmd.class);
        commands.add(DeleteStorageNfsExportCmd.class);
        commands.add(ListStorageNfsExportsCmd.class);
        commands.add(CreateStorageNfsAclCmd.class);
        commands.add(UpdateStorageNfsAclCmd.class);
        commands.add(DeleteStorageNfsAclCmd.class);
        commands.add(ListStorageNfsAclsCmd.class);
        commands.add(CreateStorageSmbShareCmd.class);
        commands.add(UpdateStorageSmbShareCmd.class);
        commands.add(DeleteStorageSmbShareCmd.class);
        commands.add(ListStorageSmbSharesCmd.class);
        commands.add(CreateStorageSmbAclCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbNetworkAclCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbNetworkAclCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbNetworkAclCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbNetworkAclsCmd.class);
        commands.add(UpdateStorageSmbAclCmd.class);
        commands.add(DeleteStorageSmbAclCmd.class);
        commands.add(ListStorageSmbAclsCmd.class);
        commands.add(JoinStorageServiceToAdDomainCmd.class);
        commands.add(LeaveStorageServiceFromAdDomainCmd.class);
        commands.add(ListStorageServiceDomainStatusCmd.class);
        commands.add(ListStorageServiceHealthCmd.class);
        commands.add(ListStorageServiceInventoryCmd.class);
        commands.add(ListStorageServiceProtocolsCmd.class);
        commands.add(ListStorageServiceSessionsCmd.class);
        commands.add(DisconnectStorageServiceSessionCmd.class);
        commands.add(AttachStorageVolumeToFileShareCmd.class);
        commands.add(DetachStorageServiceBackingVolumeCmd.class);
        commands.add(GetStorageServiceVolumePreparationCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceOperationsCmd.class);
        commands.add(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class);
        commands.add(ResizeStorageFileShareCmd.class);
        commands.add(ResizeStorageServiceBackingVolumeCmd.class);
        commands.add(PrepareStorageServiceNvmeOfVmCmd.class);
        commands.add(CreateStorageIscsiTargetCmd.class);
        commands.add(UpdateStorageIscsiTargetCmd.class);
        commands.add(DeleteStorageIscsiTargetCmd.class);
        commands.add(ListStorageIscsiTargetsCmd.class);
        commands.add(CreateStorageIscsiAclCmd.class);
        commands.add(UpdateStorageIscsiAclCmd.class);
        commands.add(DeleteStorageIscsiAclCmd.class);
        commands.add(ListStorageIscsiAclsCmd.class);
        commands.add(CreateStorageNvmeOfSubsystemCmd.class);
        commands.add(UpdateStorageNvmeOfSubsystemCmd.class);
        commands.add(DeleteStorageNvmeOfSubsystemCmd.class);
        commands.add(ListStorageNvmeOfSubsystemsCmd.class);
        commands.add(ListStorageNvmeOfNamespacesCmd.class);
        commands.add(CreateStorageNvmeOfNamespaceCmd.class);
        commands.add(DeleteStorageNvmeOfNamespaceCmd.class);
        commands.add(UpdateStorageNvmeOfNamespaceCmd.class);
        commands.add(CreateStorageNvmeOfHostAclCmd.class);
        commands.add(UpdateStorageNvmeOfHostAclCmd.class);
        commands.add(DeleteStorageNvmeOfHostAclCmd.class);
        commands.add(ListStorageNvmeOfHostAclsCmd.class);
        commands.add(RepairStorageServiceNicIdentityCmd.class);
        commands.add(RegisterStorageServiceRuntimeBundleCmd.class);
        commands.add(UpdateStorageServiceRuntimeBundleCmd.class);
        commands.add(DeleteStorageServiceRuntimeBundleCmd.class);
        commands.add(ListStorageServiceRuntimeBundlesCmd.class);
        commands.add(GetStorageServiceRuntimeUpgradeCapabilitiesCmd.class);
        commands.add(PreflightStorageServiceRuntimeUpgradeCmd.class);
        commands.add(UpgradeStorageServiceRuntimeCmd.class);
        commands.add(ListStorageServiceRuntimeUpgradesCmd.class);
        commands.add(RollbackStorageServiceRuntimeUpgradeCmd.class);
        commands.add(org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceSystemVmTemplatesCmd.class);
        commands.add(org.apache.cloudstack.api.command.admin.storage.dataservice.GetStorageServiceTemplateUpgradeCapabilitiesCmd.class);
        commands.add(org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceSystemVmTemplateUpgradeCmd.class);
        commands.add(org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceSystemVmTemplateCmd.class);
        commands.add(org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceTemplateUpgradesCmd.class);
        commands.add(org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceSystemVmTemplateUpgradeCmd.class);
        commands.add(org.apache.cloudstack.api.command.admin.storage.dataservice.FinalizeStorageServiceSystemVmTemplateUpgradeCmd.class);
        return commands;
    }

    @Override
    public ListResponse<org.apache.cloudstack.api.response.StorageServiceOperationResponse> listStorageServiceOperations(
            org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceOperationsCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        List<StorageServiceOperationVO> rows = storageOperationDao.listByInstance(instance.getId());
        rows.sort(java.util.Comparator.comparing(StorageServiceOperationVO::getCreated).reversed());
        List<org.apache.cloudstack.api.response.StorageServiceOperationResponse> responses = new ArrayList<>();
        for (StorageServiceOperationVO row : rows) {
            org.apache.cloudstack.api.response.StorageServiceOperationResponse response = new org.apache.cloudstack.api.response.StorageServiceOperationResponse();
            response.setId(row.getUuid()); response.setInstanceid(instance.getUuid()); response.setAction(row.getAction());
            response.setState(row.getState()); response.setPhase(row.getPhase()); response.setRevision(row.getRevision()); response.setProgress(row.getProgress());
            response.setCreated(row.getCreated()); response.setHeartbeat(row.getHeartbeat()); response.setCompleted(row.getCompleted()); response.setDiagnostic(row.getDiagnostic());
            response.setObjectName("storageserviceoperation"); responses.add(response);
        }
        ListResponse<org.apache.cloudstack.api.response.StorageServiceOperationResponse> result = new ListResponse<>();
        result.setResponses(responses, responses.size()); return result;
    }

    @Override
    public org.apache.cloudstack.api.response.StorageServiceOperationResponse reconcileStorageServiceOperation(
            final org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd cmd) {
        final long instanceId = getStorageServiceSyncId(cmd);
        final StorageServiceInstanceVO instance = requireInstance(instanceId);
        final com.cloud.utils.db.GlobalLock lock = com.cloud.utils.db.GlobalLock.getInternLock("StorageServiceWriter-" + instanceId);
        try {
            if (!lock.lock(120)) throw new CloudRuntimeException("Storage Service writer is busy");
            try {
                final StorageServiceOperationVO operation = storageOperationDao.findById(cmd.getOperationId());
                if (operation == null || operation.getInstanceId() != instanceId) throw new InvalidParameterValueException("Operation scope changed");
                if ("RUNNING".equals(operation.getState()) || ("RECOVERY_REQUIRED".equals(operation.getState())
                        && !StorageOperationReconciliation.superseded(operation, storageOperationDao.listByInstance(instanceId)))) {
                    if (operation.getAction().startsWith("ROOT_TEMPLATE_")) recoverInterruptedTemplateUpgrade(instance, operation);
                    else recoverInterruptedStorageWriter(instance, operation);
                    final org.apache.cloudstack.api.response.StorageServiceOperationResponse recovered = new org.apache.cloudstack.api.response.StorageServiceOperationResponse();
                    recovered.setId(operation.getUuid());recovered.setInstanceid(instance.getUuid());recovered.setAction(operation.getAction());
                    recovered.setState(operation.getState());recovered.setPhase(operation.getPhase());recovered.setRevision(operation.getRevision());
                    recovered.setProgress(operation.getProgress());recovered.setCreated(operation.getCreated());recovered.setHeartbeat(operation.getHeartbeat());
                    recovered.setCompleted(operation.getCompleted());recovered.setDiagnostic(operation.getDiagnostic());recovered.setObjectName("storageserviceoperation");
                    return recovered;
                }
                final boolean superseded = StorageOperationReconciliation.superseded(operation, storageOperationDao.listByInstance(instanceId));
                if (!superseded) throw new InvalidParameterValueException("No later verified revision exists; explicit rollback recovery is required");
                final StorageServiceOperationVO latest = storageOperationDao.listByInstance(instanceId).stream()
                        .filter(row -> "COMPLETE".equals(row.getState())).max(java.util.Comparator.comparingLong(StorageServiceOperationVO::getRevision)
                                .thenComparing(StorageServiceOperationVO::getCreated))
                        .orElseThrow(() -> new CloudRuntimeException("No verified desired-state snapshot is available"));
                final String currentSnapshot = new StorageServiceDesiredSnapshot().capture(instanceId);
                if (latest.getSnapshotJson() == null || !parseJsonObject(currentSnapshot).equals(parseJsonObject(latest.getSnapshotJson()))) {
                    throw new CloudRuntimeException("Current desired state differs from its latest verified revision");
                }
                final StorageServiceGuestCommandResult runtime = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                        "operation verify", "", 60, Collections.emptySet()));
                if (!runtime.isSuccess()) throw new CloudRuntimeException("Current configuration runtime verification failed: " + runtime.getDetails());
                final JsonObject health = parseJsonObject(normalizeRuntimeResultJson(runtime.getResultJson()));
                if (!Boolean.TRUE.equals(getJsonBoolean(health, "success")) || !"ok".equalsIgnoreCase(getJsonString(health, "status"))) {
                    throw new CloudRuntimeException("Current runtime health is not verified");
                }
                verifyReconciledStorageDesiredState(instance);
                if (StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value() && storageConfigArtifactDao.listByInstance(instanceId).stream().noneMatch(point -> "ACTIVE_LKG".equals(point.getState()))) {
                    new StorageServiceConfiguration(this, storageConfigArtifactDao, storageOperationDao).promoteVerified(instance, latest);
                }
                final JsonObject evidence = new JsonObject();evidence.addProperty("reconciliation", "SUPERSEDED_BY_VERIFIED_REVISION");
                evidence.addProperty("verifiedAt", System.currentTimeMillis());evidence.add("runtime", health);
                evidence.addProperty("originalDiagnostic", operation.getDiagnostic());
                operation.setResultJson(GSON.toJson(evidence));operation.setState("RECONCILED_SUPERSEDED");operation.setPhase("CURRENT_CONFIG_VERIFIED");
                operation.setCompleted(new java.util.Date());operation.setHeartbeat(new java.util.Date());operation.setProgress(100);
                storageOperationDao.update(operation.getId(), operation);
                final org.apache.cloudstack.api.response.StorageServiceOperationResponse response = new org.apache.cloudstack.api.response.StorageServiceOperationResponse();
                response.setId(operation.getUuid());response.setInstanceid(instance.getUuid());response.setAction(operation.getAction());response.setState(operation.getState());
                response.setPhase(operation.getPhase());response.setRevision(operation.getRevision());response.setProgress(operation.getProgress());
                response.setCreated(operation.getCreated());response.setHeartbeat(operation.getHeartbeat());response.setCompleted(operation.getCompleted());response.setDiagnostic(operation.getDiagnostic());
                response.setObjectName("storageserviceoperation");return response;
            } finally { lock.unlock(); }
        } finally { lock.releaseRef(); }
    }

    protected void verifyReconciledStorageDesiredState(final StorageServiceInstanceVO instance) {
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "operation observe", "", 30, Collections.emptySet()));
        if (!result.isSuccess()) throw new CloudRuntimeException("Current desired-state observation is unavailable");
        final JsonObject inventory = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        StorageRecoveryObservation.requireFresh(inventory, System.currentTimeMillis() / 1000.0);
        final Map<String, JsonObject> volumeObservations = new HashMap<>();
        if (inventory.has("fileShareVolumes")) {
            for (JsonElement item : inventory.getAsJsonArray("fileShareVolumes")) {
                final JsonObject observed = item.getAsJsonObject();volumeObservations.put(getJsonString(observed, "volumeUuid"), observed);
            }
        }
        for (StorageServiceInstance.Protocol protocol : new StorageServiceInstance.Protocol[] {StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB}) {
            for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
                if (share.getVolumeId() == null) throw new CloudRuntimeException("File share backing identity is unknown");
                final VolumeVO volume = requireVolume(share.getVolumeId());final JsonObject observed = volumeObservations.get(volume.getUuid());
                final JsonObject config = parseJsonObject(share.getConfigJson());
                if (observed == null || !"EXACT".equals(getJsonString(observed, "mappingStatus"))) throw new CloudRuntimeException("Backing volume is not exactly mapped");
                final String expectedFilesystem = getJsonString(config, "filesystemUuid");
                if (expectedFilesystem != null && !expectedFilesystem.equals(getJsonString(observed, "filesystemUuid"))) throw new CloudRuntimeException("Backing filesystem identity changed");
            }
        }
        final JsonObject directoryPolicies = inventory.has("posixDirectoryPolicies") ? inventory.getAsJsonObject("posixDirectoryPolicies") : new JsonObject();
        for (StoragePosixDirectoryPolicyVO policy : storagePosixPolicyDao.listByInstance(instance.getId())) {
            if (!"Ready".equals(policy.getState()) || !directoryPolicies.has(policy.getUuid())
                    || !"CONSISTENT".equals(getJsonString(directoryPolicies.getAsJsonObject(policy.getUuid()), "driftStatus"))) {
                throw new CloudRuntimeException("Common directory desired/runtime policy is not consistent");
            }
        }
        final Map<String, List<JsonObject>> nfsPseudos = new HashMap<>();
        if (inventory.has("nfsGaneshaExports")) {
            for (JsonElement endpoint : inventory.getAsJsonArray("nfsGaneshaExports")) {
                final JsonObject listener = endpoint.getAsJsonObject();
                if (!Boolean.TRUE.equals(getJsonBoolean(listener, "listening"))) throw new CloudRuntimeException("NFS endpoint is not listening");
                for (JsonElement export : listener.getAsJsonArray("entries")) {
                    JsonObject value = export.getAsJsonObject();
                    nfsPseudos.computeIfAbsent(getJsonString(value, "pseudo"), key -> new ArrayList<>()).add(value);
                }
            }
        }
        for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            if (share.getState() != StorageServiceInstance.ResourceState.Ready || !nfsPseudos.containsKey("/" + share.getName())) {
                throw new CloudRuntimeException("NFS desired/runtime export is not ready: " + share.getName() + " state=" + share.getState() + " observedPseudos=" + nfsPseudos.keySet());
            }
            final JsonObject config = parseJsonObject(share.getConfigJson());final JsonArray expected = new JsonArray();
            for (StorageAccessRuleVO acl : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                if (acl.getState() != StorageServiceInstance.ResourceState.Ready) throw new CloudRuntimeException("NFS ACL is not ready");
                expected.add(StorageRecoveryObservation.nfsClient(acl.getPrincipal(),
                        acl.getPermission() == StorageServiceInstance.Permission.READ_WRITE, config, parseJsonObject(acl.getConfigJson())));
            }
            if (expected.size() == 0) expected.add(StorageRecoveryObservation.nfsClient("*", true, config, new JsonObject()));
            for (JsonObject observed : nfsPseudos.get("/" + share.getName())) StorageRecoveryObservation.requireNfsClients(expected, observed);
        }
        final JsonObject smb = inventory.has("smbAccess") ? inventory.getAsJsonObject("smbAccess") : new JsonObject();
        for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB)) {
            if (share.getState() != StorageServiceInstance.ResourceState.Ready || !smb.has(share.getUuid())) throw new CloudRuntimeException("SMB desired/runtime share is not ready: " + share.getName() + " uuid=" + share.getUuid() + " state=" + share.getState() + " observedShareUuids=" + smb.keySet());
            final JsonObject desired = parseJsonObject(share.getConfigJson());final JsonObject actual = smb.getAsJsonObject(share.getUuid());
            if (!StringUtils.defaultIfBlank(getJsonString(desired, "ownershipInheritance"), "AUTHENTICATED_USER")
                    .equals(StringUtils.defaultIfBlank(getJsonString(actual, "ownershipInheritance"), "AUTHENTICATED_USER"))) {
                throw new CloudRuntimeException("SMB ownership inheritance differs from desired state");
            }
            if (!StringUtils.defaultIfBlank(getJsonString(desired, "posixOwnershipMode"), "AUTHENTICATED_USER")
                    .equals(StringUtils.defaultIfBlank(getJsonString(actual, "posixOwnershipMode"), "AUTHENTICATED_USER"))) {
                throw new CloudRuntimeException("SMB fixed file-operation identity differs from desired state");
            }
            final JsonObject creation = SmbCreationPolicy.merge(desired, null, null, null, null, null, null);
            if (!actual.has("creationPolicy")) throw new CloudRuntimeException("SMB creation policy is unobserved");
            for (String key : new String[] {"createMask", "forceCreateMode", "directoryMask", "forceDirectoryMode", "inheritPermissions"}) {
                if (!creation.get(key).equals(actual.getAsJsonObject("creationPolicy").get(key))) throw new CloudRuntimeException("SMB creation policy differs from desired state");
            }
            final Set<String> configured = new HashSet<>();
            for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                if (isSmbNetworkRule(rule) && rule.getState() == StorageServiceInstance.ResourceState.Ready) configured.add(rule.getPrincipal());
            }
            final Set<String> observed = new HashSet<>();
            if (actual.has("allowedSources")) for (JsonElement source : actual.getAsJsonArray("allowedSources")) observed.add(source.getAsString());
            if (!configured.equals(observed)) throw new CloudRuntimeException("SMB source policy differs from desired state");
            JsonObject rendered = null;
            if (inventory.has("smbShares")) for (JsonElement value : inventory.getAsJsonArray("smbShares")) {
                JsonObject item = value.getAsJsonObject();if (share.getName().equals(getJsonString(item, "name"))) rendered = item;
            }
            if (rendered == null) throw new CloudRuntimeException("SMB rendered share is unobserved");
            List<String> users = new ArrayList<>();List<String> writers = new ArrayList<>();List<String> admins = new ArrayList<>();
            for (StorageAccessRuleVO acl : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                if (isSmbNetworkRule(acl)) continue;
                if (acl.getState() != StorageServiceInstance.ResourceState.Ready) throw new CloudRuntimeException("SMB account ACL is not ready");
                if (acl.getPrincipalType() == StorageServiceInstance.PrincipalType.AD_USER || acl.getPrincipalType() == StorageServiceInstance.PrincipalType.AD_GROUP) {
                    throw new CloudRuntimeException("SMB AD identity recovery requires domain verification");
                }
                String principal = acl.getPrincipal().replace('/', (char) 92);
                if (principal.indexOf((char) 92) < 0) principal = buildSmbNetbiosName(instance) + (char) 92 + principal;
                principal = (acl.getPrincipalType() == StorageServiceInstance.PrincipalType.LOCAL_GROUP ? "@" : "") + (char) 34 + principal + (char) 34;
                users.add(principal);
                if (!Boolean.TRUE.equals(getJsonBoolean(desired, "readOnly")) && (acl.getPermission() == StorageServiceInstance.Permission.READ_WRITE
                        || acl.getPermission() == StorageServiceInstance.Permission.ADMIN)) writers.add(principal);
                if (acl.getPermission() == StorageServiceInstance.Permission.ADMIN) admins.add(principal);
            }
            if (!String.join(" ", users).equals(StringUtils.defaultString(getJsonString(rendered, "valid_users")))
                    || !String.join(" ", writers).equals(StringUtils.defaultString(getJsonString(rendered, "write_list")))
                    || !String.join(" ", admins).equals(StringUtils.defaultString(getJsonString(rendered, "admin_users")))) {
                throw new CloudRuntimeException("SMB rendered account permissions differ from desired state");
            }
            boolean expectedReadOnly = Boolean.TRUE.equals(getJsonBoolean(desired, "readOnly")) || (!users.isEmpty() && !Boolean.TRUE.equals(getJsonBoolean(desired, "guestOk")));
            if (expectedReadOnly != "yes".equalsIgnoreCase(getJsonString(rendered, "read_only"))
                    || Boolean.TRUE.equals(getJsonBoolean(desired, "guestOk")) != "yes".equalsIgnoreCase(getJsonString(rendered, "guest_ok"))) {
                throw new CloudRuntimeException("SMB rendered read-only or guest policy differs from desired state");
            }
            if ("FORCED_UID_GID".equals(getJsonString(desired, "posixOwnershipMode")) &&
                    (!StringUtils.defaultString(getJsonString(actual, "managedUser")).equals(getJsonString(rendered, "force_user"))
                    || !StringUtils.defaultString(getJsonString(actual, "managedGroup")).equals(getJsonString(rendered, "force_group")))) {
                throw new CloudRuntimeException("SMB rendered forced identity differs from desired state");
            }
        }
        final JsonObject iscsi = inventory.has("iscsiTargets") ? inventory.getAsJsonObject("iscsiTargets") : new JsonObject();
        for (StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.ISCSI)) {
            if (target.getState() != StorageServiceInstance.ResourceState.Ready || !iscsi.has("targets")) throw new CloudRuntimeException("iSCSI desired/runtime target is unavailable");
            JsonObject observed = null;
            for (JsonElement value : iscsi.getAsJsonArray("targets")) {
                JsonObject item = value.getAsJsonObject();
                if (target.getTargetName().equals(getJsonString(item, "targetName"))
                        && StringUtils.defaultIfBlank(target.getLunOrNamespace(), "0").equals(StringUtils.defaultIfBlank(getJsonString(item, "lunOrNamespace"), "0"))) observed = item;
            }
            if (observed == null || !observed.has("runtime")) throw new CloudRuntimeException("iSCSI backing LUN is unobserved");
            final JsonObject config = parseJsonObject(target.getConfigJson());
            if (config.has("lunSizeBytes") && (!observed.has("effectiveSizeBytes") || config.get("lunSizeBytes").getAsLong() != observed.get("effectiveSizeBytes").getAsLong())) {
                throw new CloudRuntimeException("iSCSI effective backing size differs from desired state");
            }
            final Set<String> expected = new HashSet<>();
            for (StorageAccessRuleVO acl : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, target.getId())) {
                if (acl.getState() == StorageServiceInstance.ResourceState.Ready) expected.add(acl.getPrincipal());
            }
            final Set<String> actual = new HashSet<>();
            if (observed.getAsJsonObject("runtime").has("acls")) for (JsonElement acl : observed.getAsJsonObject("runtime").getAsJsonArray("acls")) actual.add(acl.getAsString());
            if (!expected.equals(actual)) throw new CloudRuntimeException("iSCSI initiator ACL differs from desired state");
        }
        final List<StorageBlockTargetVO> nvmeTargets = storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF);
        if (!nvmeTargets.isEmpty()) {
            if (!inventory.has("nvmeofRuntime") || !Boolean.TRUE.equals(getJsonBoolean(inventory.getAsJsonObject("nvmeofRuntime"), "listening"))) {
                throw new CloudRuntimeException("NVMe-oF runtime endpoints are unavailable");
            }
            final Map<String, JsonObject> observedSubsystems = new HashMap<>();
            if (inventory.has("nvmeofSubsystems") && inventory.getAsJsonObject("nvmeofSubsystems").has("subsystems")) {
                for (JsonElement value : inventory.getAsJsonObject("nvmeofSubsystems").getAsJsonArray("subsystems")) {
                    JsonObject item = value.getAsJsonObject();observedSubsystems.put(getJsonString(item, "targetName"), item.getAsJsonObject("runtime"));
                }
            }
            for (StorageBlockTargetVO target : nvmeTargets) {
                JsonObject observed = observedSubsystems.get(target.getTargetName());
                if (target.getState() != StorageServiceInstance.ResourceState.Ready || observed == null
                        || !Boolean.TRUE.equals(getJsonBoolean(observed, "configfsPresent"))) throw new CloudRuntimeException("NVMe-oF subsystem is not ready");
                JsonObject config = parseJsonObject(target.getConfigJson());
                if (isNvmeOfSubsystem(target)) {
                    final boolean allowAny = Boolean.TRUE.equals(getJsonBoolean(config, "allowAnyHost"));
                    if (allowAny != Boolean.TRUE.equals(getJsonBoolean(observed, "allowAnyHost"))) throw new CloudRuntimeException("NVMe-oF host access mode differs");
                    final Set<String> expected = new HashSet<>();
                    for (StorageAccessRuleVO acl : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, target.getId())) {
                        if (allowAny) continue;
                        if (acl.getState() != StorageServiceInstance.ResourceState.Ready) throw new CloudRuntimeException("NVMe-oF host ACL is not ready");
                        expected.add(acl.getPrincipal());
                        JsonObject host = observed.has("hostAuthentication") ? observed.getAsJsonObject("hostAuthentication").getAsJsonObject(acl.getPrincipal()) : null;
                        StorageRecoveryObservation.requireNvmeHost(parseJsonObject(acl.getConfigJson()), host);
                    }
                    if (!expected.equals(StorageRecoveryObservation.strings(observed.getAsJsonArray("allowedHosts")))) throw new CloudRuntimeException("NVMe-oF host ACL differs");
                } else if (isNvmeOfNamespace(target)) {
                    JsonObject namespace = null;
                    for (JsonElement value : observed.getAsJsonArray("namespaces")) {
                        JsonObject item = value.getAsJsonObject();
                        if (StringUtils.defaultIfBlank(target.getLunOrNamespace(), "1").equals(getJsonString(item, "namespaceId"))) namespace = item;
                    }
                    if (namespace == null || target.getVolumeId() == null) throw new CloudRuntimeException("NVMe-oF namespace backing identity is unknown");
                    StorageRecoveryObservation.requireNamespace(config, namespace, requireVolume(target.getVolumeId()).getUuid());
                }
            }
        }
    }

    @Override
    public Long getStorageServiceSyncId(final org.apache.cloudstack.api.BaseCmd cmd) {
        final Long sharedFileSystemId = storageCommandId(cmd, "getSharedFileSystemId");
        if (sharedFileSystemId != null) {
            final SharedFSVO sharedFS = sharedFSDao.findById(sharedFileSystemId);
            if (sharedFS == null) throw new InvalidParameterValueException("Shared file system is unavailable");
            storageAccountManager.checkAccess(org.apache.cloudstack.context.CallContext.current().getCallingAccount(),
                    org.apache.cloudstack.acl.SecurityChecker.AccessType.OperateEntry, false, sharedFS);
            final StorageServiceInstanceVO instance = sharedFS.getVmId() == null ? null : storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
            if (instance == null) return Math.addExact(4_000_000_000_000_000_000L, sharedFS.getId());
            return writableStorageInstanceId(instance.getId());
        }
        final Long operationId = storageCommandId(cmd, "getOperationId");
        if (operationId != null) {
            final StorageServiceOperationVO operation = storageOperationDao.findById(operationId);
            if (operation == null) throw new InvalidParameterValueException("Storage Service operation is unavailable");
            return writableStorageInstanceId(operation.getInstanceId());
        }
        final Long upgradeId = storageCommandId(cmd, "getUpgradeId");
        if (upgradeId != null) {
            final org.apache.cloudstack.storage.dataservice.StorageServiceRuntimeUpgradeVO upgrade = storageRuntimeUpgradeDao.findById(upgradeId);
            if (upgrade == null) throw new InvalidParameterValueException("Storage Service runtime transaction is unavailable");
            return writableStorageInstanceId(upgrade.getInstanceId());
        }
        final Long instanceId = storageCommandId(cmd, "getInstanceId");
        if (instanceId != null) return writableStorageInstanceId(instanceId);
        for (String getter : Arrays.asList("getFileShareId", "getShareId", "getExportId")) {
            final Long id = storageCommandId(cmd, getter);
            if (id != null) return writableStorageInstanceId(requireFileShare(id).getInstanceId());
        }
        for (String getter : Arrays.asList("getTargetId", "getSubsystemId")) {
            final Long id = storageCommandId(cmd, getter);
            if (id != null) {
                final StorageBlockTargetVO target = storageBlockTargetDao.findById(id);
                if (target == null) throw new InvalidParameterValueException("Storage Service block resource is unavailable");
                return writableStorageInstanceId(target.getInstanceId());
            }
        }
        final Long id = storageCommandId(cmd, "getId");
        final String type = cmd.getClass().getSimpleName();
        if (id != null && type.contains("PosixDirectoryPolicy")) {
            final StoragePosixDirectoryPolicyVO policy = requirePosixDirectoryPolicy(id);
            return writableStorageInstanceId(policy.getInstanceId());
        }
        if (id != null && type.contains("Acl")) {
            final StorageAccessRuleVO rule = requireAcl(id);
            if (rule.getResourceType() == StorageServiceInstance.AccessResourceType.FILE_SHARE) {
                return writableStorageInstanceId(requireFileShare(rule.getResourceId()).getInstanceId());
            }
            final StorageBlockTargetVO target = storageBlockTargetDao.findById(rule.getResourceId());
            if (target != null) return writableStorageInstanceId(target.getInstanceId());
        }
        if (id != null && (type.contains("NfsExport") || type.contains("SmbShare") || type.contains("FileShare"))) {
            return writableStorageInstanceId(requireFileShare(id).getInstanceId());
        }
        if (id != null && (type.contains("IscsiTarget") || type.contains("NvmeOf"))) {
            final StorageBlockTargetVO target = storageBlockTargetDao.findById(id);
            if (target != null) return writableStorageInstanceId(target.getInstanceId());
        }
        throw new InvalidParameterValueException("Unable to resolve the Storage Service writer scope");
    }

    private Long writableStorageInstanceId(Long id) {
        final StorageServiceInstanceVO instance = requireInstance(id);
        storageAccountManager.checkAccess(org.apache.cloudstack.context.CallContext.current().getCallingAccount(),
                org.apache.cloudstack.acl.SecurityChecker.AccessType.OperateEntry, false, instance);
        return instance.getId();
    }

    protected boolean canReadStorageInstance(StorageServiceInstanceVO instance) {
        if (instance == null) return false;
        try {
            storageAccountManager.checkAccess(org.apache.cloudstack.context.CallContext.current().getCallingAccount(),
                    org.apache.cloudstack.acl.SecurityChecker.AccessType.UseEntry, false, instance);
            return true;
        } catch (com.cloud.exception.PermissionDeniedException denied) {
            return false;
        }
    }

    private Long storageCommandId(org.apache.cloudstack.api.BaseCmd cmd, String getter) {
        try {
            Object value = cmd.getClass().getMethod(getter).invoke(cmd);
            return value instanceof Number ? ((Number) value).longValue() : null;
        } catch (NoSuchMethodException missing) {
            return null;
        } catch (ReflectiveOperationException failure) {
            throw new InvalidParameterValueException("Unable to read the Storage Service operation scope");
        }
    }

    protected <T> T executeDesiredChange(org.apache.cloudstack.api.BaseCmd cmd, Class<T> responseClass, java.util.function.Supplier<T> change) {
        final long instanceId = getStorageServiceSyncId(cmd);
        final StorageServiceInstanceVO instance = requireInstance(instanceId);
        final ConfigurationBatch batch = configurationBatch.get();
        if (batch != null) {
            if (batch.instanceId != instanceId) throw new InvalidParameterValueException("Configuration command targets a different service");
            return change.get();
        }
        String idempotency = null; Long revision = null;
        if (cmd instanceof org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd) {
            org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd scoped =
                    (org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceAsyncCmd) cmd;
            idempotency = scoped.getIdempotencyKey(); revision = scoped.getExpectedRevision();
        }
        final StorageServiceInstance.Protocol protocol = operationProtocol(cmd);
        return new DesiredStateChange(storageOperationDao, new StorageServiceDesiredSnapshot()).execute(
                instanceId, cmd.getCommandName(), idempotency, revision, responseClass, change, new DesiredStateChange.Runtime() {
                    public void started(StorageServiceOperationVO operation) { beginStorageWriterHeartbeat(operation); }
                    public void finished() {
                        try {
                            StorageServiceOperationVO operation = storageWriterOperation.get();
                            if (hasNativeConfigurationGeneration(operation) && "COMPLETE".equals(operation.getState())) {
                                try { nativeConfigurationGeneration(instance, operation, "finish"); }
                                catch (RuntimeException pending) { logger.warn("Native generation finalization remains pending for operation {}", operation.getUuid()); }
                            }
                            cleanupConfigurationIdentityCheckpoint(operation);
                        }
                        finally { endStorageWriterHeartbeat();configurationNativeNvmeReplayed.remove(); }
                    }
                    public void preflight() {
                        if (storageTemplateUpgradeDao.findActive(instanceId) != null) throw new CloudRuntimeException("A SystemVM ROOT template upgrade requires recovery or completion");
                        if (storageRuntimeUpgradeDao.findActiveByInstanceId(instanceId) != null) {
                            throw new CloudRuntimeException("A Storage Service runtime upgrade is active");
                        }
                        if (instance.getVmId() != null) {
                            if (StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value()) {
                                JsonObject generation = nativeConfigurationGeneration(instance, null, "status");
                                String pending = getJsonString(generation, "pendingOperationUuid");
                                if (pending != null) {
                                    StorageServiceOperationVO completed = storageOperationDao.listByInstance(instanceId).stream()
                                            .filter(row -> pending.equals(row.getUuid()) && "COMPLETE".equals(row.getState())).findFirst().orElse(null);
                                    if (completed == null) throw new CloudRuntimeException("A native generation requires recovery");
                                    nativeConfigurationGeneration(instance, completed, "finish");
                                }
                            }
                            StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                                    instance.getVmId(), "operation preflight", "", 30, Collections.emptySet()));
                            if (!result.isSuccess()) throw new CloudRuntimeException("Storage Service resource preflight failed: " + result.getDetails());
                        }
                    }
                    public void prepareNativeCheckpoint(StorageServiceOperationVO operation) {
                        if (StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value() && instance.getVmId() != null
                                && (protocol == StorageServiceInstance.Protocol.SMB || protocol == StorageServiceInstance.Protocol.ISCSI
                                    || protocol == StorageServiceInstance.Protocol.NVME_OF)) checkpointConfigurationIdentity(instance);
                        if (StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value() && instance.getVmId() != null) {
                            JsonObject snapshot = parseJsonObject(operation.getPreviousSnapshotJson());
                            // Persist the recovery scope before beginning native staging, including a crash during this RPC.
                            snapshot.add("nativeGeneration", new JsonObject());operation.setPreviousSnapshotJson(snapshot.toString());
                            if (!storageOperationDao.update(operation.getId(), operation)) throw new CloudRuntimeException("Unable to persist native generation scope");
                            JsonObject nativeState = nativeConfigurationGeneration(instance, operation, "begin");
                            snapshot.add("nativeGeneration", nativeState);operation.setPreviousSnapshotJson(snapshot.toString());
                            if (!storageOperationDao.update(operation.getId(), operation)) throw new CloudRuntimeException("Unable to persist previous native generation");
                        }
                    }
                    public void verifyNativeGeneration(StorageServiceOperationVO operation) {
                        if (hasNativeConfigurationGeneration(operation)) {
                            nativeConfigurationGeneration(instance, operation, "verify");
                            nativeConfigurationGeneration(instance, operation, "commit");
                        }
                    }
                    public void rollbackNativeGeneration(StorageServiceOperationVO operation) {
                        rollbackNativeConfigurationGeneration(instance, operation);
                    }
                    public void abortNativeCheckpoint(StorageServiceOperationVO operation) {
                        rollbackNativeConfigurationGeneration(instance, operation);
                    }
                    public void promoteVerifiedConfiguration(StorageServiceOperationVO operation) {
                        if (instance.getVmId() != null && StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled.value()) new StorageServiceConfiguration(StorageServiceManagerImpl.this, storageConfigArtifactDao, storageOperationDao)
                                .promoteVerified(instance, operation, () -> {
                                    operation.setState("COMPLETE");operation.setPhase("COMPLETE");operation.setProgress(100);
                                    operation.setCompleted(new java.util.Date());operation.setHeartbeat(new java.util.Date());
                                    if (!storageOperationDao.update(operation.getId(), operation)) throw new CloudRuntimeException("Unable to commit verified configuration operation");
                                });
                    }
                    public void verify() {
                        if (instance.getVmId() != null) {
                            StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                                    instance.getVmId(), "operation verify", "", 60, Collections.emptySet()));
                            if (!result.isSuccess()) throw new CloudRuntimeException("Storage Service live verification failed: " + result.getDetails());
                            final JsonObject health = parseJsonObject(result.getResultJson());
                            if (!Boolean.TRUE.equals(getJsonBoolean(health, "success")) || !"ok".equalsIgnoreCase(getJsonString(health, "status"))) {
                                throw new CloudRuntimeException("Storage Service live runtime reports degraded health; configuration is not promoted");
                            }
                        }
                    }
                    public void applyPrevious() {
                        restoreNativePosixOperation(cmd, instance, true);
                        if (protocol != null) {
                            if (protocol != StorageServiceInstance.Protocol.NVME_OF || !Boolean.TRUE.equals(configurationNativeNvmeReplayed.get())) applyStorageServiceProtocolDesiredState(instance, protocol);
                        } else for (StorageServiceInstance.Protocol item : StorageServiceInstance.Protocol.values()) {
                            if (item != StorageServiceInstance.Protocol.NVME_OF || !Boolean.TRUE.equals(configurationNativeNvmeReplayed.get())) applyStorageServiceProtocolDesiredState(instance, item);
                        }
                        restoreNativePosixOperation(cmd, instance, false);
                    }
                });
    }

    protected void beginStorageWriterHeartbeat(StorageServiceOperationVO operation) {
        storageWriterOperation.set(operation);
        if (writerHeartbeatExecutor != null) {
            storageWriterHeartbeat.set(new StorageWriterHeartbeat(operation, storageOperationDao, writerHeartbeatExecutor,
                    failure -> logger.warn("Writer heartbeat renewal is temporarily unavailable for operation {}", operation.getUuid())));
        }
    }
    protected void endStorageWriterHeartbeat() {
        StorageWriterHeartbeat heartbeat = storageWriterHeartbeat.get();
        try { if (heartbeat != null) heartbeat.close(); }
        finally { storageWriterHeartbeat.remove();storageWriterOperation.remove(); }
    }

    protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance, StorageServiceOperationVO operation, String action) {
        JsonObject request = new JsonObject();
        if (operation != null) {
            request.addProperty("instanceUuid", instance.getUuid());request.addProperty("operationUuid", operation.getUuid());
            request.addProperty("revision", operation.getRevision());
        }
        StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "operation generation " + action, operation == null ? "" : request.toString(), 30, Collections.emptySet()));
        JsonObject observed = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        if (!result.isSuccess() || !Boolean.TRUE.equals(getJsonBoolean(observed, "success"))
                || !Boolean.TRUE.equals(getJsonBoolean(observed, "generationSupported"))) {
            throw new CloudRuntimeException("Native configuration generation " + action + " failed");
        }
        return observed;
    }
    private void rollbackNativeConfigurationGeneration(StorageServiceInstanceVO instance, StorageServiceOperationVO operation) {
        if (!hasNativeConfigurationGeneration(operation)) return;
        JsonObject observed = nativeConfigurationGeneration(instance, null, "status");
        String pending = getJsonString(observed, "pendingOperationUuid");
        if (pending == null) return;
        if (!operation.getUuid().equals(pending)) throw new CloudRuntimeException("Another native generation is pending");
        nativeConfigurationGeneration(instance, operation, "rollback");
    }

    private boolean hasNativeConfigurationGeneration(StorageServiceOperationVO operation) {
        return operation != null && operation.getPreviousSnapshotJson() != null
                && parseJsonObject(operation.getPreviousSnapshotJson()).has("nativeGeneration");
    }

    private StorageServiceInstance.Protocol operationProtocol(org.apache.cloudstack.api.BaseCmd cmd) {
        String type = cmd.getClass().getSimpleName();
        if (type.contains("Nfs")) return StorageServiceInstance.Protocol.NFS;
        if (type.contains("Smb")) return StorageServiceInstance.Protocol.SMB;
        if (type.contains("Iscsi")) return StorageServiceInstance.Protocol.ISCSI;
        if (type.contains("Nvme")) return StorageServiceInstance.Protocol.NVME_OF;
        if (cmd instanceof EnableStorageServiceProtocolCmd) return parseProtocol(((EnableStorageServiceProtocolCmd) cmd).getProtocol());
        if (cmd instanceof DeleteStorageServiceProtocolCmd) return parseProtocol(((DeleteStorageServiceProtocolCmd) cmd).getProtocol());
        return null;
    }

    @Override
    public StorageServiceRuntimeBundleResponse updateStorageServiceRuntimeBundle(UpdateStorageServiceRuntimeBundleCmd cmd) {
        return runtimeUpgradeManager.updateBundle(cmd);
    }

    @Override
    public boolean deleteStorageServiceRuntimeBundle(DeleteStorageServiceRuntimeBundleCmd cmd) {
        return runtimeUpgradeManager.deleteBundle(cmd);
    }

    @Override
    public StorageServiceRuntimeBundleResponse registerStorageServiceRuntimeBundle(final RegisterStorageServiceRuntimeBundleCmd cmd) {
        return runtimeUpgradeManager.register(cmd);
    }

    @Override
    public ListResponse<StorageServiceRuntimeBundleResponse> listStorageServiceRuntimeBundles(final ListStorageServiceRuntimeBundlesCmd cmd) {
        return runtimeUpgradeManager.listBundles(cmd);
    }

    @Override
    public StorageServiceRuntimeCapabilityResponse getStorageServiceRuntimeUpgradeCapabilities(final GetStorageServiceRuntimeUpgradeCapabilitiesCmd cmd) {
        return runtimeUpgradeManager.capabilities(cmd);
    }

    @Override
    public StorageServiceRuntimeUpgradeResponse preflightStorageServiceRuntimeUpgrade(final PreflightStorageServiceRuntimeUpgradeCmd cmd) {
        return runtimeUpgradeManager.preflight(cmd);
    }

    @Override
    public StorageServiceRuntimeUpgradeResponse upgradeStorageServiceRuntime(final UpgradeStorageServiceRuntimeCmd cmd) {
        return runtimeUpgradeManager.upgrade(cmd);
    }

    @Override
    public ListResponse<StorageServiceRuntimeUpgradeResponse> listStorageServiceRuntimeUpgrades(final ListStorageServiceRuntimeUpgradesCmd cmd) {
        return runtimeUpgradeManager.listUpgrades(cmd);
    }

    @Override
    public StorageServiceRuntimeUpgradeResponse rollbackStorageServiceRuntimeUpgrade(final RollbackStorageServiceRuntimeUpgradeCmd cmd) {
        return runtimeUpgradeManager.rollback(cmd);
    }

    @Override
    public StorageServiceInstanceResponse createStorageServiceInstance(final CreateStorageServiceInstanceCmd cmd) {
        final long accountId = cmd.getEntityOwnerId();
        final Account account = accountDao.findById(accountId);
        if (account == null) {
            throw new InvalidParameterValueException("Unable to find account with id " + accountId);
        }
        final DataCenterVO zone = dataCenterDao.findById(cmd.getZoneId());
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone with id " + cmd.getZoneId());
        }
        if (cmd.getServiceOfferingId() != null && serviceOfferingDao.findById(cmd.getServiceOfferingId()) == null) {
            throw new InvalidParameterValueException("Unable to find service offering with id " + cmd.getServiceOfferingId());
        }
        if (cmd.getVirtualMachineId() != null && vmInstanceDao.findById(cmd.getVirtualMachineId()) == null) {
            throw new InvalidParameterValueException("Unable to find System VM with id " + cmd.getVirtualMachineId());
        }

        StorageServiceInstanceVO instance = new StorageServiceInstanceVO(cmd.getName(), cmd.getDescription(), account.getDomainId(),
                accountId, cmd.getZoneId(), cmd.getServiceOfferingId(), cmd.getProvider());
        instance.setVmId(cmd.getVirtualMachineId());
        instance.setState(cmd.getVirtualMachineId() == null ? StorageServiceInstance.State.Allocated : StorageServiceInstance.State.Running);
        instance = storageServiceInstanceDao.persist(instance);
        CallContext.current().setEventResourceId(instance.getId());
        CallContext.current().setEventResourceType(ApiCommandResourceType.None);
        return createInstanceResponse(instance);
    }

    @Override
    public ListResponse<StorageServiceInstanceResponse> listStorageServiceInstances(final ListStorageServiceInstancesCmd cmd) {
        final List<StorageServiceInstanceVO> instances = new ArrayList<>();
        if (cmd.getId() != null) {
            final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(cmd.getId());
            if (instance != null) {
                instances.add(instance);
            }
        } else if (cmd.getZoneId() != null) {
            instances.addAll(storageServiceInstanceDao.listByZoneId(cmd.getZoneId()));
        } else {
            instances.addAll(storageServiceInstanceDao.listAll());
        }

        final List<StorageServiceInstanceResponse> responses = new ArrayList<>();
        for (final StorageServiceInstanceVO instance : instances) {
            if (!canReadStorageInstance(instance)) continue;
            if (cmd.getName() != null && !cmd.getName().equals(instance.getName())) {
                continue;
            }
            responses.add(createInstanceResponse(instance));
        }
        final ListResponse<StorageServiceInstanceResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    protected String normalizeNfsIdMappingMode(String value) {
        String mode=StringUtils.isBlank(value) ? "NAME_DOMAIN" : value.trim().toUpperCase(Locale.ROOT);
        if (!"NAME_DOMAIN".equals(mode) && !"NUMERIC".equals(mode)) throw new InvalidParameterValueException("Unsupported NFS owner mapping mode");
        return mode;
    }

    protected String resolveNfsIdMappingMode(StorageServiceInstanceVO instance) {
        StorageServiceProtocolVO protocol=selectNfsModeProtocol(storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.NFS));
        return normalizeNfsIdMappingMode(protocol == null ? null : getJsonString(parseJsonObject(protocol.getConfigJson()),"idMappingMode"));
    }

    private void preflightNfsIdMapping(StorageServiceInstanceVO instance,String mode) {
        if ("NUMERIC".equals(mode)) for(StorageFileShareVO share:storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.NFS)) {
            JsonObject config=parseJsonObject(share.getConfigJson());
            String security=getJsonString(config,"securityType");if(StringUtils.isBlank(security))security=getJsonString(config,"secType");
            if(StringUtils.isNotBlank(security) && !"sys".equalsIgnoreCase(security)) throw new InvalidParameterValueException("NUMERIC NFS owner mapping requires AUTH_SYS");
        }
        if(instance.getVmId() != null) {
            JsonObject payload=new JsonObject();payload.addProperty("idMappingMode",mode);
            StorageServiceGuestCommandResult result=guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),"nfs idmapping preflight",payload.toString(),30,Collections.emptySet()));
            if(!result.isSuccess())throw new CloudRuntimeException("NFS owner mapping preflight failed: "+result.getDetails());
        }
    }

    private void persistNfsIdMapping(StorageServiceInstanceVO instance,String mode) {
        for(StorageServiceProtocolVO protocol:storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.NFS)) {
            JsonObject config=parseJsonObject(protocol.getConfigJson());config.addProperty("idMappingMode",mode);
            protocol.setConfigJson(config.toString());storageServiceProtocolDao.update(protocol.getId(),protocol);
        }
    }

    @Override public org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse getStorageNfsServiceSettings(
            org.apache.cloudstack.api.command.user.storage.dataservice.GetStorageNfsServiceSettingsCmd cmd) {
        return nfsServiceSettings(requireInstance(cmd.getInstanceId()),false);
    }

    @Override public org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse updateStorageNfsServiceSettings(
            org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsServiceSettingsCmd cmd) {
        return executeDesiredChange(cmd,org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse.class,()->{
            StorageServiceInstanceVO instance=requireInstance(cmd.getInstanceId());String mode=normalizeNfsIdMappingMode(cmd.getIdMappingMode());
            preflightNfsIdMapping(instance,mode);
            if(storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.NFS).isEmpty()) throw new InvalidParameterValueException("Enable an NFS listener before changing owner mapping");
            persistNfsIdMapping(instance,mode);applyNfsDesiredState(instance);
            org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse response=nfsServiceSettings(instance,true);
            if(instance.getVmId() != null && !storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.NFS).isEmpty() && !mode.equals(response.getRuntime())) throw new CloudRuntimeException("Not every NFS listener reports the requested owner mapping mode");
            return response;
        });
    }

    protected String observeNfsIdMapping(StorageServiceInstanceVO instance,boolean uncached) {
        if(instance.getVmId() == null)return "UNKNOWN";
        try {
            StorageServiceGuestCommandResult result=guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),uncached ? "operation verify" : "health","",30,Collections.emptySet()));
            if(!result.isSuccess())return "UNKNOWN";
            JsonObject health=parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
            JsonObject ganesha=getJsonObject(health,"nfsGanesha");
            return StringUtils.defaultIfBlank(getJsonString(ganesha,"idMappingMode"),"UNKNOWN");
        } catch(RuntimeException unavailable){return "UNKNOWN";}
    }

    private org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse nfsServiceSettings(StorageServiceInstanceVO instance,boolean uncached) {
        String desired=resolveNfsIdMappingMode(instance);String runtime=observeNfsIdMapping(instance,uncached);
        org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse response=new org.apache.cloudstack.api.response.StorageNfsServiceSettingsResponse();
        response.setInstanceId(instance.getUuid());response.setDesired(desired);response.setRuntime(runtime);
        response.setEffective("NUMERIC".equals(runtime) || "NAME_DOMAIN".equals(runtime) ? runtime : "UNKNOWN");
        response.setDrift("UNKNOWN".equals(runtime) ? "UNKNOWN" : desired.equals(runtime) ? "CONSISTENT" : "DRIFT");
        response.setEndpoints((int)storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.NFS).stream().filter(StorageServiceProtocolVO::isEnabled).count());
        response.setObjectName("storagenfsservicesettings");return response;
    }

    @Override
    public StorageServiceProtocolResponse enableStorageServiceProtocol(final EnableStorageServiceProtocolCmd cmd) {
        return executeDesiredChange(cmd, StorageServiceProtocolResponse.class, () -> doEnableStorageServiceProtocol(cmd));
    }

    private StorageServiceProtocolResponse doEnableStorageServiceProtocol(final EnableStorageServiceProtocolCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final StorageServiceInstance.Protocol protocol = parseProtocol(cmd.getProtocol());
        final Integer port = normalizeStorageServiceProtocolPort(protocol, cmd.getPort());
        StorageServiceProtocolVO protocolVO = isEndpointProtocol(protocol) ?
                findProtocolEndpoint(instance.getId(), protocol, cmd.getListenIp(), port) :
                storageServiceProtocolDao.findByInstanceIdAndProtocol(instance.getId(), protocol);
        final StorageServiceProtocolVO modeProtocol = protocol == StorageServiceInstance.Protocol.NFS ?
                selectNfsModeProtocol(storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) : protocolVO;
        final String protocolMode = resolveProtocolModeForEnable(protocol, modeProtocol, cmd.getProtocolMode());
        final String idMappingMode=protocol == StorageServiceInstance.Protocol.NFS ? normalizeNfsIdMappingMode(
                cmd.getIdMappingMode() == null ? resolveNfsIdMappingMode(instance) : cmd.getIdMappingMode()) : null;
        if(cmd.getIdMappingMode() != null && protocol != StorageServiceInstance.Protocol.NFS) throw new InvalidParameterValueException("idmappingmode is an NFS service setting");
        if(cmd.getIdMappingMode() != null)preflightNfsIdMapping(instance,idMappingMode);
        validateProtocolModeEndpointPolicy(protocol, modeProtocol, protocolMode, cmd.getListenIp(), port);
        validateBlockProtocolListenerConflict(instance, protocol, cmd.getListenIp(), port, protocolVO);
        NicVO listenNic = resolveProtocolListenAddress(instance, cmd.getListenIp());
        listenNic = reconcileProtocolListenNicIdentity(instance, listenNic);
        final String protocolConfigJson = buildProtocolConfigJson(protocol, null, protocolMode, port, cmd.getListenIp());
        final boolean dualModeServiceIpRegistration = protocol == StorageServiceInstance.Protocol.NFS
                && modeProtocol != null && "V3V4_DUAL".equals(protocolMode);
        final boolean created = protocolVO == null;
        final Boolean previousEnabled = created ? null : protocolVO.isEnabled();
        final String previousListenIp = created ? null : protocolVO.getListenIp();
        final Integer previousPort = created ? null : protocolVO.getPort();
        final StorageServiceInstance.ResourceState previousState = created ? null : protocolVO.getState();
        if (protocolVO == null) {
            protocolVO = new StorageServiceProtocolVO(instance.getId(), protocol, true, cmd.getListenIp(), port);
            protocolVO.setState(StorageServiceInstance.ResourceState.Ready);
            protocolVO.setConfigJson(protocolConfigJson);
            protocolVO = storageServiceProtocolDao.persist(protocolVO);
        } else {
            protocolVO.setEnabled(true);
            if (protocol == StorageServiceInstance.Protocol.NFS) {
                if (StringUtils.isBlank(protocolVO.getListenIp())) {
                    protocolVO.setListenIp(cmd.getListenIp());
                }
                protocolVO.setPort(dualModeServiceIpRegistration ? 2049 : (protocolVO.getPort() == null ? port : protocolVO.getPort()));
            } else if (isEndpointProtocol(protocol)) {
                if (StringUtils.isBlank(protocolVO.getListenIp())) {
                    protocolVO.setListenIp(cmd.getListenIp());
                }
                protocolVO.setPort(port);
            } else {
                protocolVO.setListenIp(cmd.getListenIp());
                protocolVO.setPort(port);
            }
            protocolVO.setConfigJson(buildProtocolConfigJson(protocol, protocolVO.getConfigJson(), protocolMode, port, cmd.getListenIp()));
            protocolVO.setState(StorageServiceInstance.ResourceState.Ready);
            storageServiceProtocolDao.update(protocolVO.getId(), protocolVO);
        }

        if(protocol == StorageServiceInstance.Protocol.NFS) {
            JsonObject currentConfig=parseJsonObject(protocolVO.getConfigJson());currentConfig.addProperty("idMappingMode",idMappingMode);
            protocolVO.setConfigJson(currentConfig.toString());storageServiceProtocolDao.update(protocolVO.getId(),protocolVO);
            persistNfsIdMapping(instance,idMappingMode);
        }
        boolean registeredListenAddress = false;
        try {
            registeredListenAddress = registerProtocolListenAddress(instance, cmd.getListenIp(), listenNic);
            ensureGuestProtocolListenAddress(instance, cmd.getListenIp(), listenNic, port);
            applyStorageServiceProtocolDesiredState(instance, protocol);
        } catch (final RuntimeException e) {
            if (registeredListenAddress) {
                removeSecondaryListenAddress(instance, cmd.getListenIp());
            }
            rollbackProtocolEnable(protocolVO, created, previousEnabled, previousListenIp, previousPort, previousState);
            throw e;
        }
        return createProtocolResponse(protocolVO);
    }

    @Override
    public boolean deleteStorageServiceProtocol(final DeleteStorageServiceProtocolCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageServiceProtocol(cmd));
    }

    private boolean doDeleteStorageServiceProtocol(final DeleteStorageServiceProtocolCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final StorageServiceInstance.Protocol protocol = parseProtocol(cmd.getProtocol());
        if (StringUtils.isNotBlank(cmd.getListenIp())) {
            return deleteStorageServiceEndpoint(instance, protocol, cmd.getListenIp(), cmd.getPort());
        }
        if (isEndpointProtocol(protocol)) {
            final List<StorageServiceProtocolVO> protocols = storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocol);
            if (protocols.isEmpty()) {
                return true;
            }
            validateProtocolCanBeDeleted(instance, protocol);
            final Map<Long, Boolean> previousEnabled = new HashMap<>();
            final Map<Long, StorageServiceInstance.ResourceState> previousState = new HashMap<>();
            for (final StorageServiceProtocolVO protocolVO : protocols) {
                previousEnabled.put(protocolVO.getId(), protocolVO.isEnabled());
                previousState.put(protocolVO.getId(), protocolVO.getState());
                protocolVO.setEnabled(false);
                protocolVO.setState(StorageServiceInstance.ResourceState.Updating);
                storageServiceProtocolDao.update(protocolVO.getId(), protocolVO);
            }
            try {
                applyStorageServiceProtocolDesiredState(instance, protocol);
                for (final StorageServiceProtocolVO protocolVO : protocols) {
                    storageServiceProtocolDao.remove(protocolVO.getId());
                }
            } catch (final RuntimeException e) {
                for (final StorageServiceProtocolVO protocolVO : protocols) {
                    protocolVO.setEnabled(Boolean.TRUE.equals(previousEnabled.get(protocolVO.getId())));
                    protocolVO.setState(previousState.get(protocolVO.getId()));
                    storageServiceProtocolDao.update(protocolVO.getId(), protocolVO);
                }
                throw e;
            }
            return true;
        }
        final StorageServiceProtocolVO protocolVO = storageServiceProtocolDao.findByInstanceIdAndProtocol(instance.getId(), protocol);
        if (protocolVO == null) {
            return true;
        }
        validateProtocolCanBeDeleted(instance, protocol);
        final Boolean previousEnabled = protocolVO.isEnabled();
        final StorageServiceInstance.ResourceState previousState = protocolVO.getState();
        protocolVO.setEnabled(false);
        protocolVO.setState(StorageServiceInstance.ResourceState.Updating);
        storageServiceProtocolDao.update(protocolVO.getId(), protocolVO);
        try {
            applyStorageServiceProtocolDesiredState(instance, protocol);
            storageServiceProtocolDao.remove(protocolVO.getId());
        } catch (final RuntimeException e) {
            protocolVO.setEnabled(Boolean.TRUE.equals(previousEnabled));
            protocolVO.setState(previousState);
            storageServiceProtocolDao.update(protocolVO.getId(), protocolVO);
            throw e;
        }
        return true;
    }

    @Override
    public ListResponse<StorageServiceProtocolResponse> listStorageServiceProtocols(final ListStorageServiceProtocolsCmd cmd) {
        final List<StorageServiceProtocolVO> protocols = new ArrayList<>();
        if (cmd.getInstanceId() != null) {
            final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
            if (StringUtils.isNotBlank(cmd.getProtocol())) {
                protocols.addAll(storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), parseProtocol(cmd.getProtocol())));
            } else {
                protocols.addAll(storageServiceProtocolDao.listByInstanceId(instance.getId()));
            }
        } else if (StringUtils.isNotBlank(cmd.getProtocol())) {
            final StorageServiceInstance.Protocol protocol = parseProtocol(cmd.getProtocol());
            storageServiceInstanceDao.listAll().stream().filter(this::canReadStorageInstance).forEach(instance ->
                    protocols.addAll(storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocol)));
        } else {
            storageServiceInstanceDao.listAll().stream().filter(this::canReadStorageInstance).forEach(instance ->
                    protocols.addAll(storageServiceProtocolDao.listByInstanceId(instance.getId())));
        }

        protocols.sort((left, right) -> {
            int comparison = Long.compare(left.getInstanceId(), right.getInstanceId());
            if (comparison == 0) {
                comparison = left.getProtocol().name().compareTo(right.getProtocol().name());
            }
            if (comparison == 0) {
                comparison = StringUtils.defaultString(left.getListenIp()).compareTo(StringUtils.defaultString(right.getListenIp()));
            }
            return comparison == 0 ? Integer.compare(left.getPort() == null ? 0 : left.getPort(), right.getPort() == null ? 0 : right.getPort()) : comparison;
        });
        final Map<Long, ProtocolResponseContext> contexts = new HashMap<>();
        for (final StorageServiceProtocolVO protocol : protocols) {
            contexts.computeIfAbsent(protocol.getInstanceId(), this::buildProtocolResponseContext);
        }
        final List<StorageServiceProtocolResponse> responses = new ArrayList<>();
        for (final StorageServiceProtocolVO protocol : canonicalizeProtocolListeners(protocols)) {
            final ProtocolResponseContext context = contexts.get(protocol.getInstanceId());
            responses.add(createProtocolResponse(protocol, context));
        }
        final ListResponse<StorageServiceProtocolResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    protected List<StorageServiceProtocolVO> canonicalizeProtocolListeners(final List<StorageServiceProtocolVO> protocols) {
        final List<StorageServiceProtocolVO> canonical = new ArrayList<>();
        for (final StorageServiceProtocolVO candidate : protocols) {
            final String candidateIp = StringUtils.defaultIfBlank(candidate.getListenIp(), "0.0.0.0");
            if ("0.0.0.0".equals(candidateIp)) {
                canonical.add(candidate);
                continue;
            }
            final int candidatePort = candidate.getPort() == null ? defaultProtocolPort(candidate.getProtocol()) : candidate.getPort();
            boolean coveredByEquivalentWildcard = false;
            for (final StorageServiceProtocolVO other : protocols) {
                if (candidate == other || candidate.getInstanceId() != other.getInstanceId() || candidate.getProtocol() != other.getProtocol()) {
                    continue;
                }
                final int otherPort = other.getPort() == null ? defaultProtocolPort(other.getProtocol()) : other.getPort();
                final String otherIp = StringUtils.defaultIfBlank(other.getListenIp(), "0.0.0.0");
                if (candidatePort == otherPort && "0.0.0.0".equals(otherIp) &&
                        candidate.isEnabled() == other.isEnabled() && candidate.getState() == other.getState()) {
                    coveredByEquivalentWildcard = true;
                    break;
                }
            }
            if (!coveredByEquivalentWildcard) {
                canonical.add(candidate);
            }
        }
        return canonical;
    }

    @Override
    public StorageNfsExportResponse createStorageNfsExport(final CreateStorageNfsExportCmd cmd) {
        return executeDesiredChange(cmd, StorageNfsExportResponse.class, () -> doCreateStorageNfsExport(cmd));
    }

    private StorageNfsExportResponse doCreateStorageNfsExport(final CreateStorageNfsExportCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final String protocolMode = resolveNfsServiceProtocolMode(instance);
        validateNfsRequestedProtocolMode(cmd.getProtocolMode(), protocolMode);
        validateNfsEndpointPolicyForMode(protocolMode, cmd.getEndpointMode(), cmd.getListenIps(), cmd.getListenerPorts());
        validateNfsListenerPortsExist(instance, protocolMode, cmd.getListenerPorts());
        validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "NFS export");
        final VolumeVO requestedVolume = cmd.getVolumeId() == null ? null : requireVolume(cmd.getVolumeId());
        validateFileShareFilesystem(cmd.getFilesystem(), cmd.getImportMode());
        final String path = resolveNestedSharePath(cmd.getPath(), cmd.getName(), cmd.getRelativePath(), cmd.getVolumeId(), true);
        validateNfsExportName(cmd.getName());
        validateVisibleShareName(instance, cmd.getName(), null, StorageServiceInstance.Protocol.NFS);
        validateSharePathForRelativeInput(path, cmd.getName(), cmd.getRelativePath(), true);
        validateFileSharePathAvailable(instance, path, null, cmd.getVolumeId(), "NFS export", cmd.getPosixPolicyId() != null, cmd.getRelativePath());
        String configJson = buildNfsConfigJson(null, cmd.getReadOnly(), cmd.getRootSquash(), cmd.getAllSquash(), cmd.getAnonUid(), cmd.getAnonGid(),
                cmd.getOwnerUid(), cmd.getOwnerGid(), cmd.getMode(), cmd.getRecursivePermission(), cmd.getSync(), cmd.getSecure(),
                cmd.getEndpointMode(), cmd.getListenIps(), cmd.getListenerPorts(), protocolMode, true);
        configJson = buildFileShareDirectoryConfigJson(configJson, requestedVolume, cmd.getImportMode(), cmd.getCreateDirectory());
        configJson = storeRelativeSharePath(configJson, cmd.getRelativePath());
        validateJsonObjectConfigOrThrow(configJson, "NFS export " + cmd.getName());
        StorageFileShareVO share = new StorageFileShareVO(instance.getId(), StorageServiceInstance.Protocol.NFS, cmd.getName(), path,
                cmd.getVolumeId(), cmd.getFilesystem(), cmd.getQuotaBytes(), StorageServiceInstance.ResourceState.Creating,
                configJson);
        inheritPosixDirectoryPolicy(instance, share, cmd.getPosixPolicyId(), cmd.getOwnerUid(), cmd.getOwnerGid(), cmd.getMode());
        share = storageFileShareDao.persist(share);
        share.setState(StorageServiceInstance.ResourceState.Updating);
        storageFileShareDao.update(share.getId(), share);
        try {
            prepareFileShareBackingVolume(instance, share, cmd.getImportMode());
            if (Boolean.TRUE.equals(cmd.getDeferApply())) {
                share.setState(StorageServiceInstance.ResourceState.Allocated);
            } else {
                applyNfsDesiredState(instance);
                share.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
            }
            storageFileShareDao.update(share.getId(), share);
        } catch (final RuntimeException e) {
            cleanupFailedFileShareCreate(instance, share, Boolean.TRUE.equals(cmd.getCleanupVolumeOnFailure()));
            throw e;
        }
        return createExportResponse(share);
    }

    @Override
    public StorageNfsExportResponse updateStorageNfsExport(final UpdateStorageNfsExportCmd cmd) {
        return executeDesiredChange(cmd, StorageNfsExportResponse.class, () -> doUpdateStorageNfsExport(cmd));
    }

    private StorageNfsExportResponse doUpdateStorageNfsExport(final UpdateStorageNfsExportCmd cmd) {
        final StorageFileShareVO share = requireNfsExport(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        final String protocolMode = resolveNfsServiceProtocolMode(instance);
        validateNfsRequestedProtocolMode(cmd.getProtocolMode(), protocolMode);
        validateNfsEndpointPolicyForMode(protocolMode, cmd.getEndpointMode(), cmd.getListenIps(), cmd.getListenerPorts());
        validateNfsListenerPortsExist(instance, protocolMode, cmd.getListenerPorts());
        if (cmd.getName() != null) {
            validateNfsExportName(cmd.getName());
            validateVisibleShareName(instance, cmd.getName(), share.getId(), StorageServiceInstance.Protocol.NFS);
            share.setName(cmd.getName());
        }
        if (cmd.getName() != null || cmd.getPath() != null || cmd.getRelativePath() != null || cmd.getVolumeId() != null) {
            final Long effectiveVolumeId = cmd.getVolumeId() == null ? share.getVolumeId() : cmd.getVolumeId();
            final String effectiveRelativePath = cmd.getRelativePath() == null ? getJsonString(parseJsonObject(share.getConfigJson()), "relativeSharePath") : cmd.getRelativePath();
            final String path = resolveNestedSharePath(cmd.getPath(), share.getName(), effectiveRelativePath, effectiveVolumeId, true);
            validateSharePathForRelativeInput(path, share.getName(), effectiveRelativePath, true);
            validateFileSharePathAvailable(instance, path, share.getId(), effectiveVolumeId, "NFS export", cmd.getPosixPolicyId() != null || share.getPosixPolicyId() != null, effectiveRelativePath);
            share.setPath(path);
        }
        if (cmd.getVolumeId() != null) {
            validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "NFS export");
            share.setVolumeId(cmd.getVolumeId());
        }
        if (cmd.getFilesystem() != null) {
            validateFileShareFilesystem(cmd.getFilesystem(), cmd.getImportMode());
            share.setFilesystem(cmd.getFilesystem());
        }
        if (cmd.getQuotaBytes() != null) {
            share.setQuotaBytes(cmd.getQuotaBytes());
        }
        share.setConfigJson(buildNfsConfigJson(share.getConfigJson(), cmd.getReadOnly(), cmd.getRootSquash(), cmd.getAllSquash(), cmd.getAnonUid(),
                cmd.getAnonGid(), cmd.getOwnerUid(), cmd.getOwnerGid(), cmd.getMode(), cmd.getRecursivePermission(), cmd.getSync(), cmd.getSecure(),
                cmd.getEndpointMode(), cmd.getListenIps(), cmd.getListenerPorts(), protocolMode, true));
        share.setConfigJson(buildFileShareDirectoryConfigJson(share.getConfigJson(), cmd.getVolumeId() == null ? null : requireVolume(cmd.getVolumeId()),
                cmd.getImportMode(), cmd.getCreateDirectory()));
        share.setConfigJson(storeRelativeSharePath(share.getConfigJson(), cmd.getRelativePath()));
        validateJsonObjectConfigOrThrow(share.getConfigJson(), "NFS export " + share.getUuid());
        inheritPosixDirectoryPolicy(instance, share, cmd.getPosixPolicyId(), cmd.getOwnerUid(), cmd.getOwnerGid(), cmd.getMode());
        share.setState(StorageServiceInstance.ResourceState.Updating);
        storageFileShareDao.update(share.getId(), share);
        try {
            prepareFileShareBackingVolume(instance, share, cmd.getImportMode());
            applyNfsDesiredState(instance);
            share.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
            storageFileShareDao.update(share.getId(), share);
        } catch (final RuntimeException e) {
            share.setState(StorageServiceInstance.ResourceState.Error);
            storageFileShareDao.update(share.getId(), share);
            throw e;
        }
        return createExportResponse(share);
    }

    @Override
    public boolean deleteStorageNfsExport(final DeleteStorageNfsExportCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageNfsExport(cmd));
    }

    private boolean doDeleteStorageNfsExport(final DeleteStorageNfsExportCmd cmd) {
        final StorageFileShareVO share = requireNfsExport(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        validateNoChildShares(instance, share);
        for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
            storageAccessRuleDao.remove(rule.getId());
        }
        storageFileShareDao.remove(share.getId());
        applyNfsDesiredState(instance);
        return true;
    }

    @Override
    public ListResponse<StorageNfsExportResponse> listStorageNfsExports(final ListStorageNfsExportsCmd cmd) {
        final List<StorageFileShareVO> shares = new ArrayList<>();
        if (cmd.getId() != null) {
            final StorageFileShareVO share = storageFileShareDao.findById(cmd.getId());
            if (share != null && share.getProtocol() == StorageServiceInstance.Protocol.NFS) {
                shares.add(share);
            }
        } else if (cmd.getInstanceId() != null) {
            shares.addAll(storageFileShareDao.listByInstanceIdAndProtocol(cmd.getInstanceId(), StorageServiceInstance.Protocol.NFS));
        } else {
            shares.addAll(storageFileShareDao.listAll());
        }

        final List<StorageNfsExportResponse> responses = new ArrayList<>();
        final Map<Long, RuntimeObservationSnapshot> runtimeByInstance = new HashMap<>();
        for (final StorageFileShareVO share : shares) {
            if (share.getProtocol() != StorageServiceInstance.Protocol.NFS) {
                continue;
            }
            if (cmd.getName() != null && !cmd.getName().equals(share.getName())) {
                continue;
            }
            final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(share.getInstanceId());
            if (!canReadStorageInstance(instance)) continue;
            final RuntimeObservationSnapshot observations = runtimeByInstance.computeIfAbsent(share.getInstanceId(), ignored ->
                    loadFileShareVolumeRuntimeObservations(instance));
            responses.add(createExportResponse(share, instance, fileShareVolumeRuntimeObservation(share, observations)));
        }
        final ListResponse<StorageNfsExportResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    @Override
    public StorageAccessRuleResponse createStorageNfsAcl(final CreateStorageNfsAclCmd cmd) {
        return executeDesiredChange(cmd, StorageAccessRuleResponse.class, () -> doCreateStorageNfsAcl(cmd));
    }

    private StorageAccessRuleResponse doCreateStorageNfsAcl(final CreateStorageNfsAclCmd cmd) {
        final StorageFileShareVO share = requireNfsExport(cmd.getExportId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        final StorageServiceInstance.PrincipalType principalType = parseNfsPrincipalType(cmd.getPrincipalType());
        final StorageServiceInstance.Permission permission = parseNfsPermission(cmd.getPermission());
        final List<String> principals = parseNfsPrincipals(cmd.getPrincipal(), cmd.getPrincipals());
        final List<StorageAccessRuleVO> persistedRules = new ArrayList<>();
        final List<StorageAccessRuleVO> existingRules = storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId());
        final StorageServiceInstance.ResourceState pendingState = instance.getVmId() == null ?
                StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Updating;
        for (final String principal : principals) {
            StorageAccessRuleVO rule = null;
            for (final StorageAccessRuleVO existingRule : existingRules) {
                if (existingRule.getPrincipalType() == principalType && principal.equals(existingRule.getPrincipal())) {
                    if (rule == null) {
                        rule = existingRule;
                    } else {
                        storageAccessRuleDao.remove(existingRule.getId());
                    }
                }
            }
            if (rule == null) {
                rule = new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId(),
                        principalType, principal, permission, StorageServiceInstance.ResourceState.Creating, null);
                rule = storageAccessRuleDao.persist(rule);
            }
            rule.setPermission(permission);
            rule.setConfigJson(buildNfsConfigJson(rule.getConfigJson(), null, cmd.getRootSquash(), cmd.getAllSquash(), cmd.getAnonUid(), cmd.getAnonGid(),
                    null, null, null, null, cmd.getSync(), cmd.getSecure(), null, null, null, null, false));
            rule.setState(pendingState);
            storageAccessRuleDao.update(rule.getId(), rule);
            persistedRules.add(rule);
        }
        try {
            applyNfsDesiredState(instance, true);
            final StorageServiceInstance.ResourceState finalState = instance.getVmId() == null ?
                    StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready;
            for (final StorageAccessRuleVO rule : persistedRules) {
                rule.setState(finalState);
                storageAccessRuleDao.update(rule.getId(), rule);
            }
            share.setState(finalState);
            storageFileShareDao.update(share.getId(), share);
        } catch (final RuntimeException e) {
            for (final StorageAccessRuleVO rule : persistedRules) {
                rule.setState(StorageServiceInstance.ResourceState.Error);
                storageAccessRuleDao.update(rule.getId(), rule);
            }
            share.setState(StorageServiceInstance.ResourceState.Error);
            storageFileShareDao.update(share.getId(), share);
            throw e;
        }
        return createAclResponse(persistedRules.get(0));
    }

    @Override
    public StorageAccessRuleResponse updateStorageNfsAcl(final UpdateStorageNfsAclCmd cmd) {
        return executeDesiredChange(cmd, StorageAccessRuleResponse.class, () -> doUpdateStorageNfsAcl(cmd));
    }

    private StorageAccessRuleResponse doUpdateStorageNfsAcl(final UpdateStorageNfsAclCmd cmd) {
        final StorageAccessRuleVO rule = requireAcl(cmd.getId());
        final StorageFileShareVO share = requireNfsExport(rule.getResourceId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        if (cmd.getPrincipal() != null) {
            rule.setPrincipal(cmd.getPrincipal());
        }
        if (cmd.getPermission() != null) {
            rule.setPermission(parseNfsPermission(cmd.getPermission()));
        }
        rule.setConfigJson(buildNfsConfigJson(rule.getConfigJson(), null, cmd.getRootSquash(), cmd.getAllSquash(), cmd.getAnonUid(), cmd.getAnonGid(),
                null, null, null, null, cmd.getSync(), cmd.getSecure(), null, null, null, null, false));
        rule.setState(StorageServiceInstance.ResourceState.Updating);
        storageAccessRuleDao.update(rule.getId(), rule);
        applyNfsDesiredState(instance);
        rule.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageAccessRuleDao.update(rule.getId(), rule);
        return createAclResponse(rule);
    }

    @Override
    public boolean deleteStorageNfsAcl(final DeleteStorageNfsAclCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageNfsAcl(cmd));
    }

    private boolean doDeleteStorageNfsAcl(final DeleteStorageNfsAclCmd cmd) {
        final StorageAccessRuleVO rule = requireAcl(cmd.getId());
        final StorageFileShareVO share = requireNfsExport(rule.getResourceId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        storageAccessRuleDao.remove(rule.getId());
        applyNfsDesiredState(instance);
        return true;
    }

    @Override
    public ListResponse<StorageAccessRuleResponse> listStorageNfsAcls(final ListStorageNfsAclsCmd cmd) {
        final List<StorageAccessRuleVO> rules = new ArrayList<>();
        final Long instanceId = resolveStorageServiceInstanceId(cmd.getInstanceId(), cmd.getFullUrlParams());
        if (cmd.getId() != null) {
            final StorageAccessRuleVO rule = storageAccessRuleDao.findById(cmd.getId());
            if (rule != null) {
                rules.add(rule);
            }
        } else if (cmd.getExportId() != null) {
            rules.addAll(storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, cmd.getExportId()));
        } else if (instanceId != null) {
            requireInstance(instanceId);
            for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instanceId, StorageServiceInstance.Protocol.NFS)) {
                rules.addAll(storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId()));
            }
        } else {
            rules.addAll(storageAccessRuleDao.listAll());
        }

        final List<StorageAccessRuleResponse> responses = new ArrayList<>();
        for (final StorageAccessRuleVO rule : rules) {
            if (rule.getResourceType() != StorageServiceInstance.AccessResourceType.FILE_SHARE) {
                continue;
            }
            if (rule.getPrincipalType() != StorageServiceInstance.PrincipalType.CIDR && rule.getPrincipalType() != StorageServiceInstance.PrincipalType.IP_ADDRESS) {
                continue;
            }
            final StorageFileShareVO share = storageFileShareDao.findById(rule.getResourceId());
            if (share == null || share.getProtocol() != StorageServiceInstance.Protocol.NFS
                    || !canReadStorageInstance(storageServiceInstanceDao.findById(share.getInstanceId()))) {
                continue;
            }
            responses.add(createAclResponse(rule));
        }
        final ListResponse<StorageAccessRuleResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    protected Long resolveStorageServiceInstanceId(final Long instanceId, final Map<String, String> params) {
        if (instanceId != null) {
            return instanceId;
        }
        final String instanceUuid = params == null ? null : params.get("instanceid");
        if (StringUtils.isBlank(instanceUuid)) {
            return null;
        }
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findByUuid(instanceUuid);
        if (instance == null) {
            throw new InvalidParameterValueException("Unable to find Storage Service instance with id " + instanceUuid);
        }
        return instance.getId();
    }

    @Override
    public StorageSmbShareResponse createStorageSmbShare(final CreateStorageSmbShareCmd cmd) {
        return executeDesiredChange(cmd, StorageSmbShareResponse.class, () -> doCreateStorageSmbShare(cmd));
    }

    private StorageSmbShareResponse doCreateStorageSmbShare(final CreateStorageSmbShareCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        validateSmbShareName(cmd.getName());
        validateVisibleShareName(instance, cmd.getName(), null, StorageServiceInstance.Protocol.SMB);
        final String path = resolveNestedSharePath(cmd.getPath(), cmd.getName(), cmd.getRelativePath(), cmd.getVolumeId(), false);
        validateSharePathForRelativeInput(path, cmd.getName(), cmd.getRelativePath(), false);
        validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "SMB share");
        validateFileSharePathAvailable(instance, path, null, cmd.getVolumeId(), "SMB share", Boolean.TRUE.equals(cmd.getCrossProtocol()) || cmd.getPosixPolicyId() != null, cmd.getRelativePath());
        validateFileShareFilesystem(cmd.getFilesystem(), cmd.getImportMode());
        final String importMode = StringUtils.defaultIfBlank(cmd.getImportMode(), "MOUNT_EXISTING");
        final VolumeVO backingVolume = cmd.getVolumeId() == null ? null : requireVolume(cmd.getVolumeId());
        String configJson = buildSmbConfigJson(null, cmd.getReadOnly(), cmd.getBrowseable(), cmd.getGuestOk(),
                cmd.getCreateDirectory(), cmd.getCrossProtocol(), cmd.getDirectoryMode());
        configJson = GSON.toJson(SmbCreationPolicy.merge(parseJsonObject(configJson), cmd.getCreateMask(), cmd.getForceCreateMode(),
                cmd.getDirectoryMask(), cmd.getForceDirectoryMode(), cmd.getInheritPermissions(), cmd.getConfirmFileExecute()));
        configJson = GSON.toJson(SmbOwnershipPolicy.inheritance(parseJsonObject(configJson), cmd.getOwnershipInheritance(), cmd.getInheritGroup()));
        configJson = GSON.toJson(SmbOwnershipPolicy.forced(parseJsonObject(configJson), cmd.getPosixOwnershipMode(), cmd.getOwnerUid(), cmd.getOwnerGid()));
        configJson = buildFileShareDirectoryConfigJson(configJson, backingVolume, importMode, cmd.getCreateDirectory());
        configJson = storeRelativeSharePath(configJson, cmd.getRelativePath());
        validateJsonObjectConfigOrThrow(configJson, "SMB share " + cmd.getName());
        List<SmbNetworkPolicy.Source> initialNetworkRules=StringUtils.isBlank(cmd.getNetworkPrincipals()) ? Collections.emptyList()
                : SmbNetworkPolicy.normalize(null,cmd.getNetworkPrincipals(),smbNetworkIpv6Capability(instance));
        StorageFileShareVO share = new StorageFileShareVO(instance.getId(), StorageServiceInstance.Protocol.SMB, cmd.getName(), path,
                cmd.getVolumeId(), cmd.getFilesystem(), cmd.getQuotaBytes(), StorageServiceInstance.ResourceState.Creating,
                configJson);
        inheritPosixDirectoryPolicy(instance, share, cmd.getPosixPolicyId(), null, null, cmd.getDirectoryMode());
        if (StringUtils.isNotBlank(cmd.getAclPrincipal())) validateSmbOwnershipAccountAcl(parseJsonObject(share.getConfigJson()),
                parseSmbPermission(StringUtils.defaultIfBlank(cmd.getAclPermission(), "READ_WRITE")));
        if ("FORCED_UID_GID".equals(getJsonString(parseJsonObject(share.getConfigJson()), "posixOwnershipMode")))
            preflightSmbCreationPolicy(instance, share, parseJsonObject(share.getConfigJson()));
        share = storageFileShareDao.persist(share);
        for (SmbNetworkPolicy.Source source:initialNetworkRules) {
            storageAccessRuleDao.persist(new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE,share.getId(),source.type,source.principal,
                    StorageServiceInstance.Permission.CONNECT,instance.getVmId()==null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready,"{}"));
        }
        StorageAccessRuleVO initialAcl = null;
        final String initialPrincipal = StringUtils.trimToNull(cmd.getAclPrincipal());
        if (initialPrincipal != null) {
            final StorageServiceInstance.PrincipalType principalType = parseSmbPrincipalType(cmd.getAclPrincipalType());
            final StorageServiceInstance.Permission permission = parseSmbPermission(StringUtils.defaultIfBlank(cmd.getAclPermission(), StorageServiceInstance.Permission.READ_WRITE.name()));
            initialAcl = new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId(),
                    principalType, initialPrincipal, permission, StorageServiceInstance.ResourceState.Creating, buildSmbAclConfigJson(principalType, cmd.getAclPassword()));
            initialAcl = storageAccessRuleDao.persist(initialAcl);
        }
        share.setState(StorageServiceInstance.ResourceState.Updating);
        storageFileShareDao.update(share.getId(), share);
        try {
            prepareFileShareBackingVolume(instance, share, importMode);
            applySmbDesiredState(instance, initialAcl == null ? Collections.emptyMap() : buildSecretMap(initialAcl.getId(), cmd.getAclPassword()));
            share.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
            storageFileShareDao.update(share.getId(), share);
            if (initialAcl != null) {
                initialAcl.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
                storageAccessRuleDao.update(initialAcl.getId(), initialAcl);
            }
        } catch (final RuntimeException e) {
            cleanupFailedFileShareCreate(instance, share, Boolean.TRUE.equals(cmd.getCleanupVolumeOnFailure()));
            throw e;
        }
        return createSmbShareResponse(share);
    }

    @Override
    public StorageSmbShareResponse updateStorageSmbShare(final UpdateStorageSmbShareCmd cmd) {
        return executeDesiredChange(cmd, StorageSmbShareResponse.class, () -> doUpdateStorageSmbShare(cmd));
    }

    private StorageSmbShareResponse doUpdateStorageSmbShare(final UpdateStorageSmbShareCmd cmd) {
        final StorageFileShareVO share = requireSmbShare(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        if (cmd.getName() != null) {
            validateSmbShareName(cmd.getName());
            validateVisibleShareName(instance, cmd.getName(), share.getId(), StorageServiceInstance.Protocol.SMB);
            share.setName(cmd.getName());
        }
        if (cmd.getPath() != null || cmd.getRelativePath() != null) {
            final String path = resolveNestedSharePath(cmd.getPath(), share.getName(), cmd.getRelativePath(),
                    cmd.getVolumeId() == null ? share.getVolumeId() : cmd.getVolumeId(), false);
            validateSharePathForRelativeInput(path, share.getName(), cmd.getRelativePath(), false);
            validateFileSharePathAvailable(instance, path, share.getId(), cmd.getVolumeId() == null ? share.getVolumeId() : cmd.getVolumeId(),
                    "SMB share", Boolean.TRUE.equals(cmd.getCrossProtocol()) || cmd.getPosixPolicyId() != null || share.getPosixPolicyId() != null, cmd.getRelativePath());
            share.setPath(path);
        }
        if (cmd.getVolumeId() != null) {
            validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "SMB share");
            share.setVolumeId(cmd.getVolumeId());
        }
        if (cmd.getFilesystem() != null) {
            validateFileShareFilesystem(cmd.getFilesystem(), cmd.getImportMode());
            share.setFilesystem(cmd.getFilesystem());
        }
        if (cmd.getQuotaBytes() != null) {
            share.setQuotaBytes(cmd.getQuotaBytes());
        }
        final String importMode = StringUtils.defaultIfBlank(cmd.getImportMode(), "MOUNT_EXISTING");
        final VolumeVO backingVolume = share.getVolumeId() == null ? null : requireVolume(share.getVolumeId());
        String configJson = buildSmbConfigJson(share.getConfigJson(), cmd.getReadOnly(), cmd.getBrowseable(), cmd.getGuestOk(),
                cmd.getCreateDirectory(), cmd.getCrossProtocol(), cmd.getDirectoryMode());
        configJson = GSON.toJson(SmbCreationPolicy.merge(parseJsonObject(configJson), cmd.getCreateMask(), cmd.getForceCreateMode(),
                cmd.getDirectoryMask(), cmd.getForceDirectoryMode(), cmd.getInheritPermissions(), cmd.getConfirmFileExecute()));
        configJson = GSON.toJson(SmbOwnershipPolicy.inheritance(parseJsonObject(configJson), cmd.getOwnershipInheritance(), cmd.getInheritGroup()));
        configJson = GSON.toJson(SmbOwnershipPolicy.forced(parseJsonObject(configJson), cmd.getPosixOwnershipMode(), cmd.getOwnerUid(), cmd.getOwnerGid()));
        configJson = buildFileShareDirectoryConfigJson(configJson, backingVolume, importMode, cmd.getCreateDirectory());
        configJson = storeRelativeSharePath(configJson, cmd.getRelativePath());
        validateJsonObjectConfigOrThrow(configJson, "SMB share " + share.getUuid());
        validateSmbOwnershipExistingAcls(share, parseJsonObject(configJson));
        preflightSmbCreationPolicy(instance, share, parseJsonObject(configJson));
        share.setConfigJson(configJson);
        inheritPosixDirectoryPolicy(instance, share, cmd.getPosixPolicyId(), null, null, cmd.getDirectoryMode());
        share.setState(StorageServiceInstance.ResourceState.Updating);
        storageFileShareDao.update(share.getId(), share);
        try {
            prepareFileShareBackingVolume(instance, share, importMode);
            applySmbDesiredState(instance);
            share.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
            storageFileShareDao.update(share.getId(), share);
        } catch (final RuntimeException e) {
            share.setState(StorageServiceInstance.ResourceState.Error);
            storageFileShareDao.update(share.getId(), share);
            throw e;
        }
        return createSmbShareResponse(share);
    }

    @Override
    public boolean deleteStorageSmbShare(final DeleteStorageSmbShareCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageSmbShare(cmd));
    }

    private boolean doDeleteStorageSmbShare(final DeleteStorageSmbShareCmd cmd) {
        final StorageFileShareVO share = requireSmbShare(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        validateNoChildShares(instance, share);
        for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
            storageAccessRuleDao.remove(rule.getId());
        }
        storageFileShareDao.remove(share.getId());
        applySmbDesiredState(instance);
        return true;
    }

    @Override
    public ListResponse<StorageSmbShareResponse> listStorageSmbShares(final ListStorageSmbSharesCmd cmd) {
        final List<StorageFileShareVO> shares = new ArrayList<>();
        if (cmd.getId() != null) {
            final StorageFileShareVO share = storageFileShareDao.findById(cmd.getId());
            if (share != null && share.getProtocol() == StorageServiceInstance.Protocol.SMB) {
                shares.add(share);
            }
        } else if (cmd.getInstanceId() != null) {
            shares.addAll(storageFileShareDao.listByInstanceIdAndProtocol(cmd.getInstanceId(), StorageServiceInstance.Protocol.SMB));
        } else {
            shares.addAll(storageFileShareDao.listAll());
        }

        final List<StorageSmbShareResponse> responses = new ArrayList<>();
        final Map<Long, RuntimeObservationSnapshot> runtimeByInstance = new HashMap<>();
        for (final StorageFileShareVO share : shares) {
            if (share.getProtocol() != StorageServiceInstance.Protocol.SMB) {
                continue;
            }
            if (cmd.getName() != null && !cmd.getName().equals(share.getName())) {
                continue;
            }
            final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(share.getInstanceId());
            if (!canReadStorageInstance(instance)) continue;
            final RuntimeObservationSnapshot observations = runtimeByInstance.computeIfAbsent(share.getInstanceId(), ignored ->
                    loadFileShareVolumeRuntimeObservations(instance));
            responses.add(createSmbShareResponse(share, instance, fileShareVolumeRuntimeObservation(share, observations)));
        }
        final ListResponse<StorageSmbShareResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    @Override
    public StorageAccessRuleResponse createStorageSmbAcl(final CreateStorageSmbAclCmd cmd) {
        return executeDesiredChange(cmd, StorageAccessRuleResponse.class, () -> doCreateStorageSmbAcl(cmd));
    }

    private StorageAccessRuleResponse doCreateStorageSmbAcl(final CreateStorageSmbAclCmd cmd) {
        final StorageFileShareVO share = requireSmbShare(cmd.getShareId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        final StorageServiceInstance.PrincipalType principalType = parseSmbPrincipalType(cmd.getPrincipalType());
        final StorageServiceInstance.Permission permission = parseSmbPermission(cmd.getPermission());
        validateSmbOwnershipAccountAcl(parseJsonObject(share.getConfigJson()), permission);
        StorageAccessRuleVO rule = new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId(),
                principalType, cmd.getPrincipal(), permission, StorageServiceInstance.ResourceState.Creating, buildSmbAclConfigJson(principalType, cmd.getPassword()));
        rule = storageAccessRuleDao.persist(rule);
        rule.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageAccessRuleDao.update(rule.getId(), rule);
        try {
            applySmbDesiredState(instance, buildSecretMap(rule.getId(), cmd.getPassword()));
        } catch (final RuntimeException e) {
            rule.setState(StorageServiceInstance.ResourceState.Error);
            storageAccessRuleDao.update(rule.getId(), rule);
            throw e;
        }
        return createAclResponse(rule);
    }

    @Override
    public StorageAccessRuleResponse updateStorageSmbAcl(final UpdateStorageSmbAclCmd cmd) {
        return executeDesiredChange(cmd, StorageAccessRuleResponse.class, () -> doUpdateStorageSmbAcl(cmd));
    }

    private StorageAccessRuleResponse doUpdateStorageSmbAcl(final UpdateStorageSmbAclCmd cmd) {
        final StorageAccessRuleVO rule = requireSmbAcl(cmd.getId());
        final StorageFileShareVO share = requireSmbShare(rule.getResourceId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        if (cmd.getPrincipal() != null) {
            rule.setPrincipal(cmd.getPrincipal());
        }
        if (cmd.getPermission() != null) {
            validateSmbOwnershipAccountAcl(parseJsonObject(share.getConfigJson()), parseSmbPermission(cmd.getPermission()));
            rule.setPermission(parseSmbPermission(cmd.getPermission()));
        }
        rule.setConfigJson(buildSmbAclConfigJson(rule.getPrincipalType(), cmd.getPassword()));
        rule.setState(StorageServiceInstance.ResourceState.Updating);
        storageAccessRuleDao.update(rule.getId(), rule);
        try {
            applySmbDesiredState(instance, buildSecretMap(rule.getId(), cmd.getPassword()));
            rule.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
            storageAccessRuleDao.update(rule.getId(), rule);
        } catch (final RuntimeException e) {
            rule.setState(StorageServiceInstance.ResourceState.Error);
            storageAccessRuleDao.update(rule.getId(), rule);
            throw e;
        }
        return createAclResponse(rule);
    }

    @Override
    public boolean deleteStorageSmbAcl(final DeleteStorageSmbAclCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageSmbAcl(cmd));
    }

    private boolean doDeleteStorageSmbAcl(final DeleteStorageSmbAclCmd cmd) {
        final StorageAccessRuleVO rule = requireSmbAcl(cmd.getId());
        final StorageFileShareVO share = requireSmbShare(rule.getResourceId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        storageAccessRuleDao.remove(rule.getId());
        applySmbDesiredState(instance);
        return true;
    }

    @Override
    public ListResponse<StorageAccessRuleResponse> listStorageSmbAcls(final ListStorageSmbAclsCmd cmd) {
        final List<StorageAccessRuleVO> rules = new ArrayList<>();
        if (cmd.getId() != null) {
            final StorageAccessRuleVO rule = storageAccessRuleDao.findById(cmd.getId());
            if (rule != null) {
                rules.add(rule);
            }
        } else if (cmd.getShareId() != null) {
            rules.addAll(storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, cmd.getShareId()));
        } else if (cmd.getInstanceId() != null) {
            final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
            for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB)) {
                rules.addAll(storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId()));
            }
        } else {
            rules.addAll(storageAccessRuleDao.listAll());
        }

        final List<StorageAccessRuleResponse> responses = new ArrayList<>();
        for (final StorageAccessRuleVO rule : rules) {
            if (rule.getResourceType() != StorageServiceInstance.AccessResourceType.FILE_SHARE || !isSmbPrincipalType(rule.getPrincipalType())) {
                continue;
            }
            final StorageFileShareVO share = storageFileShareDao.findById(rule.getResourceId());
            if (share == null || share.getProtocol() != StorageServiceInstance.Protocol.SMB
                    || !canReadStorageInstance(storageServiceInstanceDao.findById(share.getInstanceId()))) {
                continue;
            }
            responses.add(createAclResponse(rule));
        }
        final ListResponse<StorageAccessRuleResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    protected boolean isSmbNetworkRule(StorageAccessRuleVO rule) {
        return rule.getResourceType()==StorageServiceInstance.AccessResourceType.FILE_SHARE && rule.getPermission()==StorageServiceInstance.Permission.CONNECT
                && (rule.getPrincipalType()==StorageServiceInstance.PrincipalType.CIDR || rule.getPrincipalType()==StorageServiceInstance.PrincipalType.IP_ADDRESS);
    }
    protected StorageAccessRuleVO requireSmbNetworkRule(Long id) {
        StorageAccessRuleVO rule=id==null ? null : storageAccessRuleDao.findById(id);
        if (rule==null || !isSmbNetworkRule(rule)) throw new InvalidParameterValueException("SMB network allow rule is unavailable");
        requireSmbShare(rule.getResourceId());return rule;
    }
    protected boolean smbNetworkIpv6Capability(StorageServiceInstanceVO instance) {
        if (instance.getVmId()==null) return false;
        StorageServiceGuestCommandResult result=guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),"operation verify","{}",30,Collections.emptySet()));
        JsonObject health=parseJsonObject(result.getResultJson());JsonObject capabilities=getJsonObject(health,"capabilities");
        if (!result.isSuccess() || capabilities==null || !capabilities.has("smbNetworkAccessSupported") || !capabilities.get("smbNetworkAccessSupported").getAsBoolean()) {
            throw new InvalidParameterValueException("Upgrade the Storage Service runtime before SMB network-policy changes");
        }
        return capabilities.has("smbNetworkIpv6Supported") && capabilities.get("smbNetworkIpv6Supported").getAsBoolean();
    }
    @Override
    public ListResponse<StorageAccessRuleResponse> createStorageSmbNetworkAcl(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbNetworkAclCmd cmd) {
        return executeDesiredChange(cmd,ListResponse.class,()-> {
            StorageFileShareVO share=requireSmbShare(cmd.getShareId());StorageServiceInstanceVO instance=requireInstance(share.getInstanceId());
            if (StringUtils.isNotBlank(cmd.getPrincipal()) && StringUtils.isNotBlank(cmd.getPrincipals())) throw new InvalidParameterValueException("Use principal or principals, not both");
            List<SmbNetworkPolicy.Source> sources=SmbNetworkPolicy.normalize(cmd.getPrincipalType(),StringUtils.defaultIfBlank(cmd.getPrincipals(),cmd.getPrincipal()),smbNetworkIpv6Capability(instance));
            List<StorageAccessRuleResponse> responses=new ArrayList<>();
            for (SmbNetworkPolicy.Source source:sources) {
                StorageAccessRuleVO found=null;
                for (StorageAccessRuleVO row:storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,share.getId())) {
                    if (isSmbNetworkRule(row) && row.getPrincipalType()==source.type && source.principal.equals(row.getPrincipal())) { found=row;break; }
                }
                if (found==null) found=storageAccessRuleDao.persist(new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.FILE_SHARE,share.getId(),source.type,source.principal,
                        StorageServiceInstance.Permission.CONNECT,instance.getVmId()==null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready,"{}"));
                responses.add(createAclResponse(found));
            }
            applySmbDesiredState(instance);ListResponse<StorageAccessRuleResponse> response=new ListResponse<>();response.setResponses(responses,responses.size());return response;
        });
    }
    @Override
    public StorageAccessRuleResponse updateStorageSmbNetworkAcl(org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbNetworkAclCmd cmd) {
        return executeDesiredChange(cmd,StorageAccessRuleResponse.class,()-> {
            StorageAccessRuleVO rule=requireSmbNetworkRule(cmd.getId());StorageServiceInstanceVO instance=requireInstance(requireSmbShare(rule.getResourceId()).getInstanceId());
            String principal=StringUtils.defaultIfBlank(cmd.getPrincipal(),rule.getPrincipal());
            List<SmbNetworkPolicy.Source> replacements=SmbNetworkPolicy.normalize(StringUtils.defaultIfBlank(cmd.getPrincipalType(),rule.getPrincipalType().name()),principal,smbNetworkIpv6Capability(instance));
            if (replacements.size()!=1) throw new InvalidParameterValueException("Update one source rule at a time");
            SmbNetworkPolicy.Source source=replacements.get(0);
            for (StorageAccessRuleVO row:storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,rule.getResourceId())) {
                if (row.getId()!=rule.getId() && isSmbNetworkRule(row) && row.getPrincipalType()==source.type && source.principal.equals(row.getPrincipal())) throw new InvalidParameterValueException("The share already has this network allow rule");
            }
            rule.setPrincipalType(source.type);rule.setPrincipal(source.principal);storageAccessRuleDao.update(rule.getId(),rule);applySmbDesiredState(instance);return createAclResponse(rule);
        });
    }
    @Override
    public boolean deleteStorageSmbNetworkAcl(org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageSmbNetworkAclCmd cmd) {
        return executeDesiredChange(cmd,Boolean.class,()-> {
            StorageAccessRuleVO rule=requireSmbNetworkRule(cmd.getId());StorageServiceInstanceVO instance=requireInstance(requireSmbShare(rule.getResourceId()).getInstanceId());
            smbNetworkIpv6Capability(instance);storageAccessRuleDao.remove(rule.getId());applySmbDesiredState(instance);return true;
        });
    }
    @Override
    public ListResponse<StorageAccessRuleResponse> listStorageSmbNetworkAcls(org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbNetworkAclsCmd cmd) {
        List<StorageAccessRuleVO> candidates=new ArrayList<>();
        if (cmd.getId()!=null) { StorageAccessRuleVO rule=storageAccessRuleDao.findById(cmd.getId());if (rule!=null) candidates.add(rule); }
        else if (cmd.getShareId()!=null) candidates.addAll(storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,cmd.getShareId()));
        else if (cmd.getInstanceId()!=null) {
            StorageServiceInstanceVO instance=requireInstance(cmd.getInstanceId());
            for (StorageFileShareVO share:storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(),StorageServiceInstance.Protocol.SMB)) candidates.addAll(storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,share.getId()));
        } else candidates.addAll(storageAccessRuleDao.listAll());
        List<StorageAccessRuleResponse> responses=new ArrayList<>();
        for (StorageAccessRuleVO rule:candidates) {
            StorageFileShareVO share=storageFileShareDao.findById(rule.getResourceId());
            if (isSmbNetworkRule(rule) && share!=null && share.getProtocol()==StorageServiceInstance.Protocol.SMB && canReadStorageInstance(storageServiceInstanceDao.findById(share.getInstanceId()))) responses.add(createAclResponse(rule));
        }
        ListResponse<StorageAccessRuleResponse> response=new ListResponse<>();response.setResponses(responses,responses.size());return response;
    }

    @Override
    public StorageIdentityDomainResponse joinStorageServiceToAdDomain(final JoinStorageServiceToAdDomainCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        ensureSmbProtocol(instance);
        StorageIdentityDomainVO domain = storageIdentityDomainDao.findByInstanceId(instance.getId());
        if (domain == null) {
            domain = new StorageIdentityDomainVO(instance.getId(), cmd.getDomainName(), cmd.getOrganizationalUnit(), cmd.getDnsServers(),
                    StorageServiceInstance.DomainJoinState.JOINING, "UNKNOWN", buildIdentityDomainConfigJson(cmd.getDomainName(), cmd.getWorkgroup()));
            domain = storageIdentityDomainDao.persist(domain);
        } else {
            domain.setDomainName(cmd.getDomainName());
            domain.setOrganizationalUnit(cmd.getOrganizationalUnit());
            domain.setDnsServers(cmd.getDnsServers());
            domain.setJoinState(StorageServiceInstance.DomainJoinState.JOINING);
            domain.setHealthState("UNKNOWN");
            domain.setConfigJson(buildIdentityDomainConfigJson(cmd.getDomainName(), cmd.getWorkgroup()));
            storageIdentityDomainDao.update(domain.getId(), domain);
        }

        try {
            applyAdJoin(instance, domain, cmd.getUsername(), cmd.getPassword());
        } catch (final RuntimeException e) {
            domain.setJoinState(StorageServiceInstance.DomainJoinState.ERROR);
            domain.setHealthState("ERROR");
            storageIdentityDomainDao.update(domain.getId(), domain);
            throw e;
        }
        domain.setJoinState(StorageServiceInstance.DomainJoinState.JOINED);
        domain.setHealthState("OK");
        storageIdentityDomainDao.update(domain.getId(), domain);
        applySmbDesiredState(instance);
        return createIdentityDomainResponse(domain);
    }

    @Override
    public StorageIdentityDomainResponse leaveStorageServiceFromAdDomain(final LeaveStorageServiceFromAdDomainCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        StorageIdentityDomainVO domain = storageIdentityDomainDao.findByInstanceId(instance.getId());
        if (domain == null) {
            domain = new StorageIdentityDomainVO(instance.getId(), "", null, null,
                    StorageServiceInstance.DomainJoinState.NOT_JOINED, "UNKNOWN", null);
            domain = storageIdentityDomainDao.persist(domain);
        } else {
            domain.setJoinState(StorageServiceInstance.DomainJoinState.LEAVING);
            storageIdentityDomainDao.update(domain.getId(), domain);
        }

        try {
            applyAdLeave(instance, cmd.getUsername(), cmd.getPassword());
        } catch (final RuntimeException e) {
            domain.setJoinState(StorageServiceInstance.DomainJoinState.ERROR);
            domain.setHealthState("ERROR");
            storageIdentityDomainDao.update(domain.getId(), domain);
            throw e;
        }
        domain.setJoinState(StorageServiceInstance.DomainJoinState.NOT_JOINED);
        domain.setHealthState("NOT_JOINED");
        storageIdentityDomainDao.update(domain.getId(), domain);
        applySmbDesiredState(instance);
        return createIdentityDomainResponse(domain);
    }

    @Override
    public ListResponse<StorageIdentityDomainResponse> listStorageServiceDomainStatus(final ListStorageServiceDomainStatusCmd cmd) {
        final List<StorageIdentityDomainVO> domains = new ArrayList<>();
        if (cmd.getInstanceId() != null) {
            final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
            StorageIdentityDomainVO domain = storageIdentityDomainDao.findByInstanceId(instance.getId());
            if (domain != null) {
                domains.add(domain);
            }
        } else {
            domains.addAll(storageIdentityDomainDao.listAll());
        }
        final List<StorageIdentityDomainResponse> responses = new ArrayList<>();
        for (StorageIdentityDomainVO domain : domains) {
            responses.add(createIdentityDomainResponse(domain));
        }
        final ListResponse<StorageIdentityDomainResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    @Override
    public ListResponse<StorageServiceRuntimeResponse> listStorageServiceHealth(final ListStorageServiceHealthCmd cmd) {
        return listRuntimeOperation(cmd.getInstanceId(), "health");
    }

    @Override
    public ListResponse<StorageServiceRuntimeResponse> listStorageServiceInventory(final ListStorageServiceInventoryCmd cmd) {
        return listRuntimeOperation(cmd.getInstanceId(), "inventory");
    }

    @Override
    public ListResponse<StorageServiceRuntimeResponse> listStorageServiceSessions(final ListStorageServiceSessionsCmd cmd) {
        final JsonObject payload = new JsonObject();
        addStringProperty(payload, "protocol", cmd.getProtocol());
        addStringProperty(payload, "resourceId", cmd.getResourceId());
        addStringProperty(payload, "client", cmd.getClient());
        addStringProperty(payload, "state", cmd.getState());
        return listRuntimeOperation(cmd.getInstanceId(), cmd.getSharedFileSystemId(), "sessions", GSON.toJson(payload));
    }

    @Override
    public StorageServiceRuntimeResponse disconnectStorageServiceSession(final DisconnectStorageServiceSessionCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final JsonObject payload = new JsonObject();
        addStringProperty(payload, "protocol", cmd.getProtocol());
        addStringProperty(payload, "sessionId", cmd.getSessionId());
        addStringProperty(payload, "peer", cmd.getPeer());
        addStringProperty(payload, "local", cmd.getLocal());
        addStringProperty(payload, "resourceId", cmd.getResourceId());
        if (cmd.getForce() != null) {
            payload.addProperty("force", cmd.getForce());
        }
        return createRuntimeResponse(instance, "session disconnect", GSON.toJson(payload));
    }

    @Override
    public StorageFileShareResponse attachStorageVolumeToFileShare(final AttachStorageVolumeToFileShareCmd cmd) {
        final StorageFileShareVO share = requireFileShare(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        final VolumeVO volume = requireVolume(cmd.getVolumeId());
        if (volume.getInstanceId() != null && !volume.getInstanceId().equals(instance.getVmId())) {
            throw new InvalidParameterValueException("Volume " + volume.getUuid() + " is already attached to another VM");
        }

        share.setState(StorageServiceInstance.ResourceState.Updating);
        share.setVolumeId(volume.getId());
        if (cmd.getPath() != null) {
            share.setPath(cmd.getPath());
        }
        if (cmd.getFilesystem() != null) {
            share.setFilesystem(cmd.getFilesystem());
        }
        share.setConfigJson(buildFileShareAttachConfigJson(share.getConfigJson(), cmd.getImportMode(), volume, null));
        storageFileShareDao.update(share.getId(), share);

        if (instance.getVmId() != null && volume.getInstanceId() == null) {
            final VolumeVO attachableVolume = waitForFileShareVolumeAttachable(volume.getId());
            volumeApiService.attachVolumeToVM(instance.getVmId(), attachableVolume.getId(), null, true);
        }
        if (instance.getVmId() != null) {
            inspectAttachedFileShareVolume(instance, share, volume, cmd.getImportMode());
        }
        applyFileShareDesiredState(instance, share.getProtocol());

        share.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageFileShareDao.update(share.getId(), share);
        return createFileShareResponse(share);
    }

    @Override
    public StorageServiceRuntimeResponse getStorageServiceVolumePreparation(GetStorageServiceVolumePreparationCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final VolumeVO volume = requireVolume(cmd.getVolumeId());
        if (instance.getVmId() == null || !instance.getVmId().equals(volume.getInstanceId())) {
            throw new InvalidParameterValueException("Backing volume is not attached to this Storage Service instance");
        }
        final JsonObject payload = new JsonObject();
        payload.addProperty("volumeUuid", volume.getUuid());
        return createRuntimeResponse(instance, "volume operation status", payload.toString());
    }

    @Override
    public StorageServiceRuntimeResponse detachStorageServiceBackingVolume(final DetachStorageServiceBackingVolumeCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final VolumeVO volume = requireVolume(cmd.getVolumeId());
        if (instance.getVmId() == null) {
            throw new InvalidParameterValueException("Storage Service instance has no System VM");
        }
        if (!instance.getVmId().equals(volume.getInstanceId())) {
            throw new InvalidParameterValueException("Backing volume " + volume.getUuid() + " is not attached to this Storage Service System VM");
        }
        validateBackingVolumeUnused(instance, volume.getId());

        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("volumeId", volume.getId());
        payload.addProperty("volumeUuid", volume.getUuid());
        payload.addProperty("volumeName", volume.getName());
        final String knownMountRoot = findKnownFileShareVolumeMountRoot(instance, volume.getId());
        if (StringUtils.isNotBlank(knownMountRoot)) {
            payload.addProperty("mountPath", knownMountRoot);
        }
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "volume detach prepare", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to prepare Storage Service backing volume detach: " + result.getDetails());
        }
        volumeApiService.detachVolumeViaDestroyVM(instance.getVmId(), volume.getId());
        return createRuntimeResponse(instance, "volume detach prepare", true, extractRuntimeStatus(result), result.getDetails(), result.getResultJson());
    }

    @Override
    public StorageServiceRuntimeResponse resizeStorageServiceBackingVolume(final ResizeStorageServiceBackingVolumeCmd cmd) {
        if (cmd.getSize() == null || cmd.getSize() <= 0) {
            throw new InvalidParameterValueException("A positive backing volume size in GiB is required");
        }
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final VolumeVO volume = requireVolume(cmd.getVolumeId());
        if (instance.getVmId() == null) {
            throw new InvalidParameterValueException("Storage Service instance has no System VM");
        }
        if (!instance.getVmId().equals(volume.getInstanceId())) {
            throw new InvalidParameterValueException("Backing volume " + volume.getUuid() + " is not attached to this Storage Service System VM");
        }
        if (volume.getVolumeType() != Volume.Type.DATADISK) {
            throw new InvalidParameterValueException("Only Storage Service data backing volumes can be resized");
        }
        final long currentSizeGiB = bytesToGiBRoundUp(volume.getSize());
        if (cmd.getSize() <= currentSizeGiB) {
            throw new InvalidParameterValueException("New backing volume size must be greater than the current size (" + currentSizeGiB + " GiB)");
        }
        final Set<StorageServiceInstance.Protocol> affectedProtocols = findProtocolsForBackingVolume(instance, volume.getId());
        if (affectedProtocols.isEmpty()) {
            throw new InvalidParameterValueException("Backing volume " + volume.getUuid() + " is not assigned to a Storage Service resource");
        }
        final long expectedSizeBytes;
        try {
            expectedSizeBytes = Math.multiplyExact(cmd.getSize(), 1024L * 1024L * 1024L);
        } catch (final ArithmeticException e) {
            throw new InvalidParameterValueException("Requested backing volume size is too large");
        }

        resizeBackingVolume(volume.getId(), cmd.getSize());
        final VolumeVO resizedVolume = requireVolume(volume.getId());
        if (resizedVolume.getSize() == null || resizedVolume.getSize() < expectedSizeBytes) {
            throw new CloudRuntimeException("Backing volume resize did not persist the requested size for volume " + volume.getUuid());
        }
        final StorageServiceRuntimeResponse response = rescanStorageServiceBackingVolume(instance, resizedVolume);
        reapplyDesiredStateForBackingVolume(instance, resizedVolume.getId(), affectedProtocols);
        return response;
    }

    protected long bytesToGiBRoundUp(final Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0) {
            return 0L;
        }
        final long gib = 1024L * 1024L * 1024L;
        return (sizeBytes + gib - 1L) / gib;
    }

    protected StorageServiceRuntimeResponse rescanStorageServiceBackingVolume(final StorageServiceInstanceVO instance, final VolumeVO volume) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("volumeId", volume.getId());
        payload.addProperty("volumeUuid", volume.getUuid());
        payload.addProperty("volumeName", volume.getName());
        payload.addProperty("volumeSizeBytes", volume.getSize());
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "volume rescan", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to rescan Storage Service backing volume: " + result.getDetails());
        }
        return createRuntimeResponse(instance, "volume rescan", true, extractRuntimeStatus(result), result.getDetails(), result.getResultJson());
    }

    protected Set<StorageServiceInstance.Protocol> findProtocolsForBackingVolume(final StorageServiceInstanceVO instance, final Long volumeId) {
        final Set<StorageServiceInstance.Protocol> protocols = new HashSet<>();
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            if (volumeId.equals(share.getVolumeId())) {
                protocols.add(StorageServiceInstance.Protocol.NFS);
            }
        }
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB)) {
            if (volumeId.equals(share.getVolumeId())) {
                protocols.add(StorageServiceInstance.Protocol.SMB);
            }
        }
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.ISCSI)) {
            if (volumeId.equals(target.getVolumeId())) {
                protocols.add(StorageServiceInstance.Protocol.ISCSI);
                break;
            }
        }
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (volumeId.equals(target.getVolumeId())) {
                protocols.add(StorageServiceInstance.Protocol.NVME_OF);
                break;
            }
        }
        return protocols;
    }

    protected void reapplyDesiredStateForBackingVolume(final StorageServiceInstanceVO instance, final Long volumeId,
            final Set<StorageServiceInstance.Protocol> protocols) {
        if (protocols.contains(StorageServiceInstance.Protocol.NFS)) {
            for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
                if (volumeId.equals(share.getVolumeId())) {
                    growFileShareFilesystem(instance, share, null, null);
                }
            }
        }
        if (protocols.contains(StorageServiceInstance.Protocol.SMB)) {
            for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB)) {
                if (volumeId.equals(share.getVolumeId())) {
                    growFileShareFilesystem(instance, share, null, null);
                }
            }
        }
        if (protocols.contains(StorageServiceInstance.Protocol.NFS)) {
            applyFileShareDesiredState(instance, StorageServiceInstance.Protocol.NFS);
        }
        if (protocols.contains(StorageServiceInstance.Protocol.SMB)) {
            applyFileShareDesiredState(instance, StorageServiceInstance.Protocol.SMB);
        }
        if (protocols.contains(StorageServiceInstance.Protocol.ISCSI)) {
            applyIscsiDesiredState(instance);
        }
        if (protocols.contains(StorageServiceInstance.Protocol.NVME_OF)) {
            applyNvmeOfDesiredState(instance);
        }
    }

    protected void prepareFileShareBackingVolume(final StorageServiceInstanceVO instance, final StorageFileShareVO share, final String importMode) {
        if (share.getVolumeId() == null || StringUtils.isBlank(importMode)) {
            return;
        }
        VolumeVO volume = requireVolume(share.getVolumeId());
        final Long attachedVmId = volume.getInstanceId();
        if (attachedVmId != null && !attachedVmId.equals(instance.getVmId())) {
            throw new InvalidParameterValueException("Backing volume " + volume.getUuid() + " is already attached to another VM");
        }
        if (instance.getVmId() != null && attachedVmId == null) {
            volume = waitForFileShareVolumeAttachable(volume.getId());
            volumeApiService.attachVolumeToVM(instance.getVmId(), volume.getId(), null, true);
            volume = requireVolume(share.getVolumeId());
        }
        if (canReuseManagedAttachedFileShareVolume(instance, share, volume, attachedVmId)) {
            share.setConfigJson(buildManagedFileShareVolumeReuseConfigJson(share.getConfigJson(), importMode, volume, share.getPath()));
            storageFileShareDao.update(share.getId(), share);
            return;
        }
        if (instance.getVmId() != null) {
            inspectAttachedFileShareVolume(instance, share, volume, importMode);
        }
    }

    protected boolean canReuseManagedAttachedFileShareVolume(final StorageServiceInstanceVO instance, final StorageFileShareVO share,
            final VolumeVO volume, final Long attachedVmId) {
        if (instance == null || instance.getVmId() == null || share == null || volume == null || attachedVmId == null ||
                !attachedVmId.equals(instance.getVmId())) {
            return false;
        }
        for (final StorageServiceInstance.Protocol protocol : Arrays.asList(StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB)) {
            for (final StorageFileShareVO existingShare : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
                if (existingShare == null || existingShare.getVolumeId() == null || existingShare.getId() == share.getId()) {
                    continue;
                }
                if (!existingShare.getVolumeId().equals(volume.getId())) {
                    continue;
                }
                final String knownMountPath = resolveFileShareGuestMountPath(existingShare);
                if (StringUtils.isNotBlank(knownMountPath)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void cleanupFailedFileShareCreate(final StorageServiceInstanceVO instance, final StorageFileShareVO share, final boolean cleanupVolumeOnFailure) {
        if (share == null) {
            return;
        }
        final Long volumeId = share.getVolumeId();
        final String mountPath = resolveFileShareGuestMountPath(share);
        try {
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                storageAccessRuleDao.remove(rule.getId());
            }
            storageFileShareDao.remove(share.getId());
            applyFileShareDesiredState(instance, share.getProtocol());
        } catch (final RuntimeException cleanupError) {
            logger.warn("Failed to reconcile Storage Service file share [{}] after create failure", share.getUuid(), cleanupError);
        }
        if (cleanupVolumeOnFailure && volumeId != null) {
            logger.info("Preserving backing volume [{}] after failed share creation; explicit detach/delete is required", volumeId);
        }
    }

    protected void markFileShareCreateFailed(final StorageServiceInstanceVO instance, final StorageFileShareVO share, final RuntimeException failure,
            final boolean cleanupVolumeOnFailure) {
        if (share == null) {
            return;
        }
        final Long volumeId = share.getVolumeId();
        try {
            share.setState(StorageServiceInstance.ResourceState.Error);
            share.setConfigJson(buildFileShareErrorConfigJson(share.getConfigJson(), failure));
            storageFileShareDao.update(share.getId(), share);
            applyFileShareDesiredState(instance, share.getProtocol());
        } catch (final RuntimeException reconcileError) {
            logger.warn("Failed to preserve failed Storage Service file share [{}] after create failure", share.getUuid(), reconcileError);
        }
        if (cleanupVolumeOnFailure && volumeId != null) {
            logger.info("Preserving backing volume [{}] after failed preparation; explicit recovery is required", volumeId);
        }
    }

    protected void cleanupCreatedBackingVolume(final StorageServiceInstanceVO instance, final Long volumeId) {
        cleanupCreatedBackingVolume(instance, volumeId, null);
    }

    protected void cleanupCreatedBackingVolume(final StorageServiceInstanceVO instance, final Long volumeId, final String mountPath) {
        try {
            VolumeVO volume = volumeDao.findById(volumeId);
            if (volume == null) {
                return;
            }
            if (instance.getVmId() != null && instance.getVmId().equals(volume.getInstanceId())) {
                prepareGuestBackingVolumeDetach(instance, volume, mountPath);
                volumeApiService.detachVolumeViaDestroyVM(instance.getVmId(), volume.getId());
                volume = volumeDao.findById(volumeId);
            }
            if (volume != null && volume.getInstanceId() == null) {
                volumeApiService.destroyVolume(volume.getId(), CallContext.current().getCallingAccount(), true, true);
            } else if (volume != null) {
                logger.warn("Skipping cleanup of newly created backing volume [{}] because it is still attached to VM ID [{}]", volume.getUuid(), volume.getInstanceId());
            }
        } catch (final RuntimeException cleanupError) {
            logger.warn("Failed to cleanup newly created backing volume [{}] after Storage Service file share create failure", volumeId, cleanupError);
        }
    }

    protected String resolveFileShareGuestMountPath(final StorageFileShareVO share) {
        if (share == null) {
            return null;
        }
        final JsonObject config = parseJsonObject(share.getConfigJson());
        final String configuredMountPath = getJsonString(config, "volumeMountPath");
        if (StringUtils.isNotBlank(configuredMountPath)) {
            return configuredMountPath;
        }
        final JsonObject inspection = getJsonObject(config, "lastInspection");
        final String inspectedMountPath = getJsonString(inspection, "volumeMountPath");
        if (StringUtils.isNotBlank(inspectedMountPath)) {
            return inspectedMountPath;
        }
        return getJsonString(inspection, "mountPath");
    }

    protected void prepareGuestBackingVolumeDetach(final StorageServiceInstanceVO instance, final VolumeVO volume, final String mountPath) {
        if (instance == null || instance.getVmId() == null || volume == null) {
            return;
        }
        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("volumeId", volume.getId());
        payload.addProperty("volumeUuid", volume.getUuid());
        payload.addProperty("volumeName", volume.getName());
        if (StringUtils.isNotBlank(mountPath)) {
            payload.addProperty("mountPath", mountPath);
        }
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "volume detach prepare", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
        if (!result.isSuccess()) {
            logger.warn("Failed to prepare Storage Service backing volume [{}] detach after file share create failure: {}",
                    volume.getUuid(), result.getDetails());
        }
    }

    @Override
    public StorageFileShareResponse resizeStorageFileShare(final ResizeStorageFileShareCmd cmd) {
        if (cmd.getSize() == null && cmd.getQuotaBytes() == null) {
            throw new InvalidParameterValueException("Either size or quotabytes must be provided");
        }
        final StorageFileShareVO share = requireFileShare(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        if (share.getVolumeId() == null) {
            throw new InvalidParameterValueException("File share " + share.getUuid() + " has no backing volume to resize");
        }

        share.setState(StorageServiceInstance.ResourceState.Updating);
        storageFileShareDao.update(share.getId(), share);

        if (Boolean.TRUE.equals(cmd.getResizeVolume()) && cmd.getSize() != null) {
            resizeBackingVolume(share.getVolumeId(), cmd.getSize());
        }
        if (cmd.getQuotaBytes() != null) {
            share.setQuotaBytes(cmd.getQuotaBytes());
        }
        if (instance.getVmId() != null) {
            growFileShareFilesystem(instance, share, cmd.getSize(), cmd.getQuotaBytes());
        }
        applyFileShareDesiredState(instance, share.getProtocol());

        share.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageFileShareDao.update(share.getId(), share);
        return createFileShareResponse(share);
    }

    @Override
    public StorageServiceRuntimeResponse prepareStorageServiceNvmeOfVm(final PrepareStorageServiceNvmeOfVmCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final String engine = StringUtils.defaultIfBlank(cmd.getEngine(), "KERNEL_NVMET").toUpperCase();
        if ("SPDK".equals(engine)) {
            return createRuntimeResponse(instance, "nvmeof prepare", false, "PREPARATION_REQUIRED",
                    "SPDK NVMe-oF requires VM Runtime Capability support for HugePage, NUMA, CPU pinning, memlock, SR-IOV, or PCI passthrough. " +
                            "Storage Service keeps SPDK as a planned engine until that VM-level feature is available.",
                    buildNvmeOfPreparationResult(engine, cmd.getTransport(), cmd.getRuntimeCapabilityProfileId(), cmd.getValidateOnly()));
        }
        if (!"KERNEL_NVMET".equals(engine)) {
            throw new InvalidParameterValueException("Unsupported NVMe-oF engine: " + cmd.getEngine());
        }
        if (instance.getVmId() == null) {
            return createRuntimeResponse(instance, "nvmeof prepare", false, "NOT_ATTACHED", "Storage Service instance has no System VM",
                    buildNvmeOfPreparationResult(engine, cmd.getTransport(), cmd.getRuntimeCapabilityProfileId(), cmd.getValidateOnly()));
        }

        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("engine", engine);
        payload.addProperty("transport", StringUtils.defaultIfBlank(cmd.getTransport(), "tcp"));
        payload.addProperty("validateOnly", Boolean.TRUE.equals(cmd.getValidateOnly()));
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "nvmeof prepare", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
        final String status = extractRuntimeStatus(result);
        return createRuntimeResponse(instance, "nvmeof prepare", result.isSuccess(), status, result.getDetails(), result.getResultJson());
    }

    @Override
    public StorageBlockTargetResponse createStorageIscsiTarget(final CreateStorageIscsiTargetCmd cmd) {
        return executeDesiredChange(cmd, StorageBlockTargetResponse.class, () -> doCreateStorageIscsiTarget(cmd));
    }

    private StorageBlockTargetResponse doCreateStorageIscsiTarget(final CreateStorageIscsiTargetCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "iSCSI target");
        validateIscsiBlockOnlyBackstore(cmd.getBackstoreType());
        validateIscsiBackingVolumeAvailable(instance, cmd.getVolumeId(), null);
        validateIscsiEndpointPolicy(cmd.getEndpointMode(), cmd.getListenerPorts());
        validateIscsiListenerPortsExist(instance, cmd.getListenerPorts());
        ensureProtocol(instance, StorageServiceInstance.Protocol.ISCSI);
        prepareIscsiBackingVolume(instance, cmd.getVolumeId());
        StorageBlockTargetVO target = new StorageBlockTargetVO(instance.getId(), StorageServiceInstance.Protocol.ISCSI, cmd.getTargetName(),
                StringUtils.defaultIfBlank(cmd.getLun(), "0"), cmd.getVolumeId(), StorageServiceInstance.ResourceState.Creating,
                buildIscsiTargetConfigJson(null, cmd.getBackingPath(), cmd.getBackstoreType(), cmd.getLunSizeBytes(), cmd.getEndpointMode(), cmd.getListenerPorts()));
        target = storageBlockTargetDao.persist(target);
        try {
            target.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
            storageBlockTargetDao.update(target.getId(), target);
            applyIscsiDesiredState(instance);
            return createBlockTargetResponse(target, "storageiscsitarget");
        } catch (final RuntimeException e) {
            cleanupFailedBlockTargetCreate(instance, target, Boolean.TRUE.equals(cmd.getCleanupVolumeOnFailure()));
            throw e;
        }
    }

    @Override
    public StorageBlockTargetResponse updateStorageIscsiTarget(final UpdateStorageIscsiTargetCmd cmd) {
        return executeDesiredChange(cmd, StorageBlockTargetResponse.class, () -> doUpdateStorageIscsiTarget(cmd));
    }

    private StorageBlockTargetResponse doUpdateStorageIscsiTarget(final UpdateStorageIscsiTargetCmd cmd) {
        final StorageBlockTargetVO target = requireBlockTarget(cmd.getId(), StorageServiceInstance.Protocol.ISCSI);
        final StorageServiceInstanceVO instance = requireInstance(target.getInstanceId());
        if (cmd.getTargetName() != null) {
            target.setTargetName(cmd.getTargetName());
        }
        if (cmd.getLun() != null) {
            target.setLunOrNamespace(cmd.getLun());
        }
        if (cmd.getVolumeId() != null) {
            validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "iSCSI target");
            validateIscsiBackingVolumeAvailable(instance, cmd.getVolumeId(), target.getId());
            prepareIscsiBackingVolume(instance, cmd.getVolumeId());
            target.setVolumeId(cmd.getVolumeId());
        }
        validateIscsiBlockOnlyBackstore(cmd.getBackstoreType());
        validateIscsiEndpointPolicy(cmd.getEndpointMode(), cmd.getListenerPorts());
        validateIscsiListenerPortsExist(instance, cmd.getListenerPorts());
        target.setConfigJson(buildIscsiTargetConfigJson(target.getConfigJson(), cmd.getBackingPath(), cmd.getBackstoreType(), cmd.getLunSizeBytes(), cmd.getEndpointMode(), cmd.getListenerPorts()));
        target.setState(StorageServiceInstance.ResourceState.Updating);
        storageBlockTargetDao.update(target.getId(), target);
        applyIscsiDesiredState(instance);
        target.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageBlockTargetDao.update(target.getId(), target);
        return createBlockTargetResponse(target, "storageiscsitarget");
    }

    @Override
    public boolean deleteStorageIscsiTarget(final DeleteStorageIscsiTargetCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageIscsiTarget(cmd));
    }

    private boolean doDeleteStorageIscsiTarget(final DeleteStorageIscsiTargetCmd cmd) {
        final StorageBlockTargetVO target = requireBlockTarget(cmd.getId(), StorageServiceInstance.Protocol.ISCSI);
        final StorageServiceInstanceVO instance = requireInstance(target.getInstanceId());
        for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, target.getId())) {
            storageAccessRuleDao.remove(rule.getId());
        }
        storageBlockTargetDao.remove(target.getId());
        applyIscsiDesiredState(instance);
        return true;
    }

    @Override
    public ListResponse<StorageBlockTargetResponse> listStorageIscsiTargets(final ListStorageIscsiTargetsCmd cmd) {
        final List<StorageBlockTargetVO> targets = listBlockTargets(cmd.getId(), cmd.getInstanceId(), StorageServiceInstance.Protocol.ISCSI);
        final List<StorageBlockTargetResponse> responses = new ArrayList<>();
        final Map<Long, RuntimeObservationSnapshot> runtimeByInstance = new HashMap<>();
        for (final StorageBlockTargetVO target : targets) {
            if (cmd.getTargetName() != null && !cmd.getTargetName().equals(target.getTargetName())) {
                continue;
            }
            final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(target.getInstanceId());
            final RuntimeObservationSnapshot snapshot = runtimeByInstance.computeIfAbsent(target.getInstanceId(), ignored ->
                    loadIscsiTargetRuntimeObservations(instance));
            final JsonObject observation = iscsiTargetRuntimeObservation(target, snapshot);
            final String mappingStatus = snapshot.available ? (observation == null ? "UNMAPPED" : "EXACT") : "UNAVAILABLE";
            responses.add(createBlockTargetResponse(target, "storageiscsitarget", observation, mappingStatus));
        }
        final ListResponse<StorageBlockTargetResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    @Override
    public StorageAccessRuleResponse createStorageIscsiAcl(final CreateStorageIscsiAclCmd cmd) {
        return executeDesiredChange(cmd, StorageAccessRuleResponse.class, () -> doCreateStorageIscsiAcl(cmd));
    }

    private StorageAccessRuleResponse doCreateStorageIscsiAcl(final CreateStorageIscsiAclCmd cmd) {
        final StorageBlockTargetVO target = requireBlockTarget(cmd.getTargetId(), StorageServiceInstance.Protocol.ISCSI);
        final StorageServiceInstanceVO instance = requireInstance(target.getInstanceId());
        final StorageServiceInstance.Permission permission = parseBlockPermission(cmd.getPermission());
        validateIscsiChapCredentialRequest(cmd.getChapEnabled(), cmd.getChapUsername(), cmd.getChapSecret(), cmd.getMutualChapEnabled(), cmd.getMutualChapUsername(), cmd.getMutualChapSecret());
        final String configJson = buildIscsiAclConfigJson(null, cmd.getChapEnabled(), cmd.getChapUsername(), cmd.getMutualChapEnabled(), cmd.getMutualChapUsername(),
                cmd.getChapSecret(), cmd.getMutualChapSecret());
        validateIscsiAclTargetScope(target, null, cmd.getInitiatorIqn(), parseJsonObject(configJson));
        StorageAccessRuleVO rule = new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, target.getId(),
                StorageServiceInstance.PrincipalType.ISCSI_INITIATOR_IQN, cmd.getInitiatorIqn(), permission, StorageServiceInstance.ResourceState.Creating,
                configJson);
        rule = storageAccessRuleDao.persist(rule);
        rule.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageAccessRuleDao.update(rule.getId(), rule);
        applyIscsiDesiredState(instance, buildChapSecretMap(rule.getId(), cmd.getChapSecret(), cmd.getMutualChapSecret()));
        return createAclResponse(rule);
    }

    @Override
    public StorageAccessRuleResponse updateStorageIscsiAcl(final UpdateStorageIscsiAclCmd cmd) {
        return executeDesiredChange(cmd, StorageAccessRuleResponse.class, () -> doUpdateStorageIscsiAcl(cmd));
    }

    private StorageAccessRuleResponse doUpdateStorageIscsiAcl(final UpdateStorageIscsiAclCmd cmd) {
        final StorageAccessRuleVO rule = requireBlockAcl(cmd.getId(), StorageServiceInstance.Protocol.ISCSI);
        final StorageBlockTargetVO target = requireBlockTarget(rule.getResourceId(), StorageServiceInstance.Protocol.ISCSI);
        final StorageServiceInstanceVO instance = requireInstance(target.getInstanceId());
        if (cmd.getInitiatorIqn() != null) {
            rule.setPrincipal(cmd.getInitiatorIqn());
        }
        if (cmd.getPermission() != null) {
            rule.setPermission(parseBlockPermission(cmd.getPermission()));
        }
        validateIscsiChapCredentialRequest(cmd.getChapEnabled(), cmd.getChapUsername(), cmd.getChapSecret(), cmd.getMutualChapEnabled(), cmd.getMutualChapUsername(), cmd.getMutualChapSecret());
        final String configJson = buildIscsiAclConfigJson(rule.getConfigJson(), cmd.getChapEnabled(), cmd.getChapUsername(), cmd.getMutualChapEnabled(), cmd.getMutualChapUsername(),
                cmd.getChapSecret(), cmd.getMutualChapSecret());
        validateIscsiAclTargetScope(target, rule.getId(), rule.getPrincipal(), parseJsonObject(configJson));
        rule.setConfigJson(configJson);
        rule.setState(StorageServiceInstance.ResourceState.Updating);
        storageAccessRuleDao.update(rule.getId(), rule);
        applyIscsiDesiredState(instance, buildChapSecretMap(rule.getId(), cmd.getChapSecret(), cmd.getMutualChapSecret()));
        rule.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageAccessRuleDao.update(rule.getId(), rule);
        return createAclResponse(rule);
    }

    @Override
    public boolean deleteStorageIscsiAcl(final DeleteStorageIscsiAclCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageIscsiAcl(cmd));
    }

    private boolean doDeleteStorageIscsiAcl(final DeleteStorageIscsiAclCmd cmd) {
        final StorageAccessRuleVO rule = requireBlockAcl(cmd.getId(), StorageServiceInstance.Protocol.ISCSI);
        final StorageBlockTargetVO target = requireBlockTarget(rule.getResourceId(), StorageServiceInstance.Protocol.ISCSI);
        final StorageServiceInstanceVO instance = requireInstance(target.getInstanceId());
        storageAccessRuleDao.remove(rule.getId());
        applyIscsiDesiredState(instance);
        return true;
    }

    @Override
    public ListResponse<StorageAccessRuleResponse> listStorageIscsiAcls(final ListStorageIscsiAclsCmd cmd) {
        return listBlockAcls(cmd.getId(), cmd.getTargetId(), StorageServiceInstance.Protocol.ISCSI);
    }

    @Override
    public StorageBlockTargetResponse createStorageNvmeOfSubsystem(final CreateStorageNvmeOfSubsystemCmd cmd) {
        return executeDesiredChange(cmd, StorageBlockTargetResponse.class, () -> doCreateStorageNvmeOfSubsystem(cmd));
    }

    private StorageBlockTargetResponse doCreateStorageNvmeOfSubsystem(final CreateStorageNvmeOfSubsystemCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        ensureProtocol(instance, StorageServiceInstance.Protocol.NVME_OF);
        final StorageBlockTargetVO existingSubsystem = findNvmeOfSubsystemByNqn(instance.getId(), cmd.getSubsystemNqn());
        if (existingSubsystem != null) {
            logger.warn("NVMe-oF subsystem [{}] already exists for Storage Service instance [{}]; returning the existing subsystem [{}]",
                    cmd.getSubsystemNqn(), instance.getUuid(), existingSubsystem.getUuid());
            return createBlockTargetResponse(existingSubsystem, "storagenvmeofsubsystem");
        }
        StorageBlockTargetVO subsystem = new StorageBlockTargetVO(instance.getId(), StorageServiceInstance.Protocol.NVME_OF, cmd.getSubsystemNqn(),
                null, null, StorageServiceInstance.ResourceState.Creating,
                buildNvmeOfConfigJson(null, "subsystem", cmd.getAllowAnyHost(), null, cmd.getEngine(), cmd.getTransport(), null));
        subsystem = storageBlockTargetDao.persist(subsystem);
        subsystem.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageBlockTargetDao.update(subsystem.getId(), subsystem);
        applyNvmeOfDesiredState(instance);
        return createBlockTargetResponse(subsystem, "storagenvmeofsubsystem");
    }

    @Override
    public StorageBlockTargetResponse updateStorageNvmeOfSubsystem(final UpdateStorageNvmeOfSubsystemCmd cmd) {
        return executeDesiredChange(cmd, StorageBlockTargetResponse.class, () -> doUpdateStorageNvmeOfSubsystem(cmd));
    }

    private StorageBlockTargetResponse doUpdateStorageNvmeOfSubsystem(final UpdateStorageNvmeOfSubsystemCmd cmd) {
        final StorageBlockTargetVO subsystem = requireNvmeOfSubsystem(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(subsystem.getInstanceId());
        if (cmd.getSubsystemNqn() != null) {
            updateNvmeOfSubsystemName(subsystem, cmd.getSubsystemNqn());
        }
        subsystem.setConfigJson(buildNvmeOfConfigJson(subsystem.getConfigJson(), "subsystem", cmd.getAllowAnyHost(), null, cmd.getEngine(), cmd.getTransport(), null));
        subsystem.setState(StorageServiceInstance.ResourceState.Updating);
        storageBlockTargetDao.update(subsystem.getId(), subsystem);
        applyNvmeOfDesiredState(instance);
        subsystem.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageBlockTargetDao.update(subsystem.getId(), subsystem);
        return createBlockTargetResponse(subsystem, "storagenvmeofsubsystem");
    }

    @Override
    public boolean deleteStorageNvmeOfSubsystem(final DeleteStorageNvmeOfSubsystemCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageNvmeOfSubsystem(cmd));
    }

    private boolean doDeleteStorageNvmeOfSubsystem(final DeleteStorageNvmeOfSubsystemCmd cmd) {
        final StorageBlockTargetVO subsystem = requireNvmeOfSubsystem(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(subsystem.getInstanceId());
        validateNvmeOfSubsystemCanBeDeleted(subsystem);
        storageBlockTargetDao.remove(subsystem.getId());
        applyNvmeOfDesiredState(instance);
        return true;
    }

    @Override
    public ListResponse<StorageBlockTargetResponse> listStorageNvmeOfSubsystems(final ListStorageNvmeOfSubsystemsCmd cmd) {
        final List<StorageBlockTargetVO> targets = listBlockTargets(cmd.getId(), cmd.getInstanceId(), StorageServiceInstance.Protocol.NVME_OF);
        final List<StorageBlockTargetResponse> responses = new ArrayList<>();
        final Map<String, StorageBlockTargetVO> subsystemByNqn = new LinkedHashMap<>();
        for (final StorageBlockTargetVO target : targets) {
            if (!isNvmeOfSubsystem(target)) {
                continue;
            }
            if (cmd.getSubsystemNqn() != null && !cmd.getSubsystemNqn().equals(target.getTargetName())) {
                continue;
            }
            if (cmd.getId() != null) {
                responses.add(createBlockTargetResponse(target, "storagenvmeofsubsystem"));
                continue;
            }
            final StorageBlockTargetVO existing = subsystemByNqn.get(target.getInstanceId() + ":" + target.getTargetName());
            if (existing == null || !isActiveStorageServiceResource(existing.getState()) && isActiveStorageServiceResource(target.getState())) {
                subsystemByNqn.put(target.getInstanceId() + ":" + target.getTargetName(), target);
            }
        }
        for (final StorageBlockTargetVO subsystem : subsystemByNqn.values()) {
            responses.add(createBlockTargetResponse(subsystem, "storagenvmeofsubsystem"));
        }
        final ListResponse<StorageBlockTargetResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    @Override
    public ListResponse<StorageBlockTargetResponse> listStorageNvmeOfNamespaces(final ListStorageNvmeOfNamespacesCmd cmd) {
        final List<StorageBlockTargetVO> targets = listBlockTargets(cmd.getId(), cmd.getInstanceId(), StorageServiceInstance.Protocol.NVME_OF);
        final List<StorageBlockTargetResponse> responses = new ArrayList<>();
        final Map<Long, Map<String, List<JsonObject>>> runtimeObservationsByInstance = new HashMap<>();
        for (final StorageBlockTargetVO target : targets) {
            if (!isNvmeOfNamespace(target)) {
                continue;
            }
            if (cmd.getSubsystemNqn() != null && !cmd.getSubsystemNqn().equals(target.getTargetName())) {
                continue;
            }
            if (cmd.getNamespaceId() != null && !cmd.getNamespaceId().equals(StringUtils.defaultIfBlank(target.getLunOrNamespace(), "1"))) {
                continue;
            }
            final Map<String, List<JsonObject>> runtimeObservations = runtimeObservationsByInstance.computeIfAbsent(target.getInstanceId(), instanceId ->
                    loadNvmeNamespaceRuntimeObservations(storageServiceInstanceDao.findById(instanceId)));
            final List<JsonObject> matches = runtimeObservations.getOrDefault(nvmeNamespaceRuntimeKey(target.getTargetName(), target.getLunOrNamespace()), Collections.emptyList());
            final String mappingStatus = matches.size() == 1 ? "EXACT" : matches.size() > 1 ? "AMBIGUOUS" : "UNMAPPED";
            responses.add(createBlockTargetResponse(target, "storagenvmeofnamespace", matches.size() == 1 ? matches.get(0) : null, mappingStatus));
        }
        final ListResponse<StorageBlockTargetResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    @Override
    public StorageBlockTargetResponse createStorageNvmeOfNamespace(final CreateStorageNvmeOfNamespaceCmd cmd) {
        return executeDesiredChange(cmd, StorageBlockTargetResponse.class, () -> doCreateStorageNvmeOfNamespace(cmd));
    }

    private StorageBlockTargetResponse doCreateStorageNvmeOfNamespace(final CreateStorageNvmeOfNamespaceCmd cmd) {
        final StorageBlockTargetVO subsystem = requireNvmeOfSubsystem(cmd.getSubsystemId());
        final StorageServiceInstanceVO instance = requireInstance(subsystem.getInstanceId());
        validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "NVMe-oF namespace");
        validateNvmeOfBackingVolumeAvailable(instance, cmd.getVolumeId(), null);
        validateNvmeOfEndpointPolicy(cmd.getListenerPorts());
        validateNvmeOfListenerPortsExist(instance, cmd.getListenerPorts());
        validateNvmeOfNamespaceListenerPortsCompatible(instance, subsystem, cmd.getListenerPorts(), null);
        ensureProtocol(instance, StorageServiceInstance.Protocol.NVME_OF);
        prepareBlockBackingVolume(instance, cmd.getVolumeId());
        StorageBlockTargetVO namespace = new StorageBlockTargetVO(instance.getId(), StorageServiceInstance.Protocol.NVME_OF, subsystem.getTargetName(),
                StringUtils.defaultIfBlank(cmd.getNamespaceId(), "1"), cmd.getVolumeId(), StorageServiceInstance.ResourceState.Creating,
                buildNvmeOfConfigJson(null, "namespace", null, cmd.getBackingPath(), null, null, cmd.getNamespaceSizeBytes(), cmd.getListenerPorts()));
        namespace = storageBlockTargetDao.persist(namespace);
        try {
            namespace.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
            storageBlockTargetDao.update(namespace.getId(), namespace);
            applyNvmeOfDesiredState(instance);
            return createBlockTargetResponse(namespace, "storagenvmeofnamespace");
        } catch (final RuntimeException e) {
            cleanupFailedBlockTargetCreate(instance, namespace, StorageServiceInstance.Protocol.NVME_OF, Boolean.TRUE.equals(cmd.getCleanupVolumeOnFailure()));
            throw e;
        }
    }

    @Override
    public StorageBlockTargetResponse updateStorageNvmeOfNamespace(final UpdateStorageNvmeOfNamespaceCmd cmd) {
        return executeDesiredChange(cmd, StorageBlockTargetResponse.class, () -> doUpdateStorageNvmeOfNamespace(cmd));
    }

    private StorageBlockTargetResponse doUpdateStorageNvmeOfNamespace(final UpdateStorageNvmeOfNamespaceCmd cmd) {
        final StorageBlockTargetVO namespace = requireNvmeOfNamespace(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(namespace.getInstanceId());
        if (cmd.getNamespaceId() != null) {
            namespace.setLunOrNamespace(cmd.getNamespaceId());
        }
        if (cmd.getVolumeId() != null) {
            validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "NVMe-oF namespace");
            validateNvmeOfBackingVolumeAvailable(instance, cmd.getVolumeId(), namespace.getId());
            prepareBlockBackingVolume(instance, cmd.getVolumeId());
            namespace.setVolumeId(cmd.getVolumeId());
        }
        validateNvmeOfEndpointPolicy(cmd.getListenerPorts());
        validateNvmeOfListenerPortsExist(instance, cmd.getListenerPorts());
        final StorageBlockTargetVO subsystem = findNvmeOfSubsystemByNqn(instance.getId(), namespace.getTargetName());
        if (subsystem != null) {
            final String effectiveListenerPorts = cmd.getListenerPorts() != null ? cmd.getListenerPorts() : listenerPortsAsString(parseJsonObject(namespace.getConfigJson()));
            validateNvmeOfNamespaceListenerPortsCompatible(instance, subsystem, effectiveListenerPorts, namespace.getId());
        }
        namespace.setConfigJson(buildNvmeOfConfigJson(namespace.getConfigJson(), "namespace", null, cmd.getBackingPath(), null, null, cmd.getNamespaceSizeBytes(), cmd.getListenerPorts()));
        namespace.setState(StorageServiceInstance.ResourceState.Updating);
        storageBlockTargetDao.update(namespace.getId(), namespace);
        applyNvmeOfDesiredState(instance);
        namespace.setState(instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready);
        storageBlockTargetDao.update(namespace.getId(), namespace);
        return createBlockTargetResponse(namespace, "storagenvmeofnamespace");
    }

    @Override
    public boolean deleteStorageNvmeOfNamespace(final DeleteStorageNvmeOfNamespaceCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageNvmeOfNamespace(cmd));
    }

    private boolean doDeleteStorageNvmeOfNamespace(final DeleteStorageNvmeOfNamespaceCmd cmd) {
        final StorageBlockTargetVO namespace = requireNvmeOfNamespace(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(namespace.getInstanceId());
        storageBlockTargetDao.remove(namespace.getId());
        applyNvmeOfDesiredState(instance);
        return true;
    }

    @Override
    public StorageAccessRuleResponse createStorageNvmeOfHostAcl(final CreateStorageNvmeOfHostAclCmd cmd) {
        return executeDesiredChange(cmd, StorageAccessRuleResponse.class, () -> doCreateStorageNvmeOfHostAcl(cmd));
    }

    private StorageAccessRuleResponse doCreateStorageNvmeOfHostAcl(final CreateStorageNvmeOfHostAclCmd cmd) {
        final StorageBlockTargetVO subsystem = canonicalNvmeOfSubsystem(requireNvmeOfSubsystem(cmd.getSubsystemId()));
        final StorageServiceInstanceVO instance = requireInstance(subsystem.getInstanceId());
        validateNvmeOfHostAclScope(subsystem);
        final StorageAccessRuleVO existingRule = findNvmeOfHostAclByPrincipal(subsystem, cmd.getHostNqn());
        if (existingRule != null) {
            return createAclResponse(existingRule);
        }
        StorageAccessRuleVO rule = new StorageAccessRuleVO(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, subsystem.getId(),
                StorageServiceInstance.PrincipalType.NVME_HOST_NQN, cmd.getHostNqn(), StorageServiceInstance.Permission.READ_WRITE,
                StorageServiceInstance.ResourceState.Creating,
                buildNvmeOfHostAclConfigJson(null, cmd.getDhChapEnabled(), cmd.getDhChapCtrlEnabled(), cmd.getDhChapKey(), cmd.getDhChapCtrlKey()));
        rule = storageAccessRuleDao.persist(rule);
        final StorageServiceInstance.ResourceState finalState = getAppliedResourceState(instance);
        try {
            applyNvmeOfDesiredState(instance, buildNvmeOfSecretMap(rule.getId(), cmd.getDhChapKey(), cmd.getDhChapCtrlKey()),
                    Collections.singletonMap(rule.getId(), finalState));
            rule.setState(finalState);
            storageAccessRuleDao.update(rule.getId(), rule);
        } catch (final RuntimeException e) {
            rule.setState(StorageServiceInstance.ResourceState.Error);
            storageAccessRuleDao.update(rule.getId(), rule);
            throw e;
        }
        return createAclResponse(rule);
    }

    @Override
    public StorageAccessRuleResponse updateStorageNvmeOfHostAcl(final UpdateStorageNvmeOfHostAclCmd cmd) {
        return executeDesiredChange(cmd, StorageAccessRuleResponse.class, () -> doUpdateStorageNvmeOfHostAcl(cmd));
    }

    private StorageAccessRuleResponse doUpdateStorageNvmeOfHostAcl(final UpdateStorageNvmeOfHostAclCmd cmd) {
        final StorageAccessRuleVO rule = requireBlockAcl(cmd.getId(), StorageServiceInstance.Protocol.NVME_OF);
        final StorageBlockTargetVO target = canonicalNvmeOfSubsystem(requireBlockTarget(rule.getResourceId(), StorageServiceInstance.Protocol.NVME_OF));
        final StorageServiceInstanceVO instance = requireInstance(target.getInstanceId());
        validateNvmeOfHostAclScope(target);
        final String requestedHostNqn = StringUtils.defaultIfBlank(cmd.getHostNqn(), rule.getPrincipal());
        final StorageAccessRuleVO duplicateRule = findNvmeOfHostAclByPrincipal(target, requestedHostNqn);
        if (duplicateRule != null && duplicateRule.getId() != rule.getId()) {
            throw new InvalidParameterValueException("NVMe-oF host ACL already exists for host NQN " + requestedHostNqn);
        }
        final String previousPrincipal = rule.getPrincipal();
        final String previousConfigJson = rule.getConfigJson();
        final StorageServiceInstance.ResourceState previousState = rule.getState();
        if (cmd.getHostNqn() != null) {
            rule.setPrincipal(cmd.getHostNqn());
        }
        rule.setConfigJson(buildNvmeOfHostAclConfigJson(rule.getConfigJson(), cmd.getDhChapEnabled(), cmd.getDhChapCtrlEnabled(),
                cmd.getDhChapKey(), cmd.getDhChapCtrlKey()));
        rule.setState(StorageServiceInstance.ResourceState.Updating);
        storageAccessRuleDao.update(rule.getId(), rule);
        final StorageServiceInstance.ResourceState finalState = getAppliedResourceState(instance);
        try {
            applyNvmeOfDesiredState(instance, buildNvmeOfSecretMap(rule.getId(), cmd.getDhChapKey(), cmd.getDhChapCtrlKey()),
                    Collections.singletonMap(rule.getId(), finalState));
            rule.setState(finalState);
            storageAccessRuleDao.update(rule.getId(), rule);
        } catch (final RuntimeException e) {
            rule.setPrincipal(previousPrincipal);
            rule.setConfigJson(previousConfigJson);
            rule.setState(previousState);
            storageAccessRuleDao.update(rule.getId(), rule);
            throw e;
        }
        return createAclResponse(rule);
    }

    @Override
    public boolean deleteStorageNvmeOfHostAcl(final DeleteStorageNvmeOfHostAclCmd cmd) {
        return executeDesiredChange(cmd, Boolean.class, () -> doDeleteStorageNvmeOfHostAcl(cmd));
    }

    private boolean doDeleteStorageNvmeOfHostAcl(final DeleteStorageNvmeOfHostAclCmd cmd) {
        final StorageAccessRuleVO rule = requireBlockAcl(cmd.getId(), StorageServiceInstance.Protocol.NVME_OF);
        final StorageBlockTargetVO target = requireBlockTarget(rule.getResourceId(), StorageServiceInstance.Protocol.NVME_OF);
        final StorageServiceInstanceVO instance = requireInstance(target.getInstanceId());
        storageAccessRuleDao.remove(rule.getId());
        applyNvmeOfDesiredState(instance);
        return true;
    }

    @Override
    public ListResponse<StorageAccessRuleResponse> listStorageNvmeOfHostAcls(final ListStorageNvmeOfHostAclsCmd cmd) {
        if (cmd.getId() != null || cmd.getSubsystemId() == null) {
            return listBlockAcls(cmd.getId(), cmd.getSubsystemId(), StorageServiceInstance.Protocol.NVME_OF);
        }
        final StorageBlockTargetVO subsystem = canonicalNvmeOfSubsystem(requireNvmeOfSubsystem(cmd.getSubsystemId()));
        final ListResponse<StorageAccessRuleResponse> response = new ListResponse<>();
        final List<StorageAccessRuleResponse> responses = new ArrayList<>();
        for (final StorageAccessRuleVO rule : listNvmeOfHostAclRules(subsystem)) {
            responses.add(createAclResponse(rule));
        }
        response.setResponses(responses, responses.size());
        return response;
    }

    protected void applyNfsDesiredState(final StorageServiceInstanceVO instance) {
        applyNfsDesiredState(instance, null, false);
    }

    protected void applyNfsDesiredState(final StorageServiceInstanceVO instance, final boolean includeAllocatedResources) {
        applyNfsDesiredState(instance, null, includeAllocatedResources);
    }

    protected void applyNfsDesiredState(final StorageServiceInstanceVO instance, final String removeListenIp) {
        applyNfsDesiredState(instance, removeListenIp, false);
    }

    protected void applyNfsDesiredState(final StorageServiceInstanceVO instance, final String removeListenIp, final boolean includeAllocatedResources) {
        if (configurationBatch.get() != null) return;
        if (instance.getVmId() == null) {
            logger.debug("Storage Service instance [{}] has no System VM yet; NFS state is stored but not applied", instance.getUuid());
            return;
        }

        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("instanceId", instance.getId());
        final List<StorageServiceProtocolVO> nfsProtocols = storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS);
        final StorageServiceProtocolVO protocol = selectNfsDefaultProtocol(nfsProtocols);
        payload.addProperty("enabled", nfsProtocols.isEmpty() || nfsProtocols.stream().anyMatch(StorageServiceProtocolVO::isEnabled));
        final String serviceProtocolMode = resolveNfsServiceProtocolMode(instance);
        payload.addProperty("protocolMode", serviceProtocolMode);
        payload.addProperty("idMappingMode",resolveNfsIdMappingMode(instance));
        final Integer defaultNfsListenerPort = protocol == null || protocol.getPort() == null ? 2049 : protocol.getPort();
        final JsonArray listeners = new JsonArray();
        for (final StorageServiceProtocolVO listenerProtocol : nfsProtocols) {
            if (listenerProtocol == null || !listenerProtocol.isEnabled()) {
                continue;
            }
            final JsonObject listener = new JsonObject();
            if (StringUtils.isNotBlank(listenerProtocol.getListenIp())) {
                listener.addProperty("listenIp", listenerProtocol.getListenIp());
            }
            listener.addProperty("port", listenerProtocol.getPort() == null ? 2049 : listenerProtocol.getPort());
            listener.addProperty("state", StorageServiceInstance.ResourceState.Ready.name());
            listener.add("config", parseJsonObject(listenerProtocol.getConfigJson()));
            listeners.add(listener);
        }
        if (listeners.size() == 0) {
            final JsonObject listener = new JsonObject();
            listener.addProperty("port", 2049);
            listener.addProperty("state", StorageServiceInstance.ResourceState.Ready.name());
            listeners.add(listener);
        }
        payload.add("listeners", listeners);
        if (protocol != null) {
            payload.addProperty("listenIp", protocol.getListenIp());
            payload.addProperty("port", protocol.getPort() == null ? 2049 : protocol.getPort());
        } else {
            payload.addProperty("port", 2049);
        }
        if (StringUtils.isNotBlank(removeListenIp)) {
            payload.addProperty("removeListenIp", removeListenIp);
        }

        final JsonArray exports = new JsonArray();
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            if (!isApplicableFileShareState(share.getState(), includeAllocatedResources)) {
                logger.debug("Skipping NFS export [{}] in state [{}] while building desired state", share.getUuid(), share.getState());
                continue;
            }
            final JsonObject shareConfig = parseJsonObjectStrict(share.getConfigJson(), "NFS export " + share.getUuid());
            shareConfig.addProperty("protocolMode", serviceProtocolMode);
            if (ensureNfsExportListenerGroupPorts(shareConfig, serviceProtocolMode, defaultNfsListenerPort)) {
                share.setConfigJson(GSON.toJson(shareConfig));
                storageFileShareDao.update(share.getId(), share);
            }
            validateNfsExportBackingConfig(share, shareConfig);
            final JsonObject export = new JsonObject();
            export.addProperty("id", share.getId());
            export.addProperty("uuid", share.getUuid());
            export.addProperty("name", share.getName());
            export.addProperty("path", getJsonString(shareConfig, "relativeSharePath") == null
                    ? share.getPath() : SharedFS.SharedFSPath + "/" + share.getName());
            if (share.getVolumeId() != null) {
                export.addProperty("volumeId", share.getVolumeId());
            }
            export.addProperty("filesystem", share.getFilesystem());
            if (share.getQuotaBytes() != null) {
                export.addProperty("quotaBytes", share.getQuotaBytes());
            }
            export.addProperty("state", StorageServiceInstance.ResourceState.Ready.name());
            export.add("config", shareConfig);

            final JsonArray acls = new JsonArray();
            final HashSet<String> aclKeys = new HashSet<>();
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                if (!isApplicableResourceState(rule.getState(), includeAllocatedResources)) {
                    logger.debug("Skipping NFS ACL [{}] in state [{}] while building desired state", rule.getUuid(), rule.getState());
                    continue;
                }
                final String aclKey = StringUtils.defaultString(rule.getPrincipalType() == null ? null : rule.getPrincipalType().name()) + ":" +
                        StringUtils.defaultString(rule.getPrincipal());
                if (aclKeys.contains(aclKey)) {
                    logger.warn("Skipping duplicate NFS ACL [{}] for export [{}] while building desired state", aclKey, share.getUuid());
                    continue;
                }
                aclKeys.add(aclKey);
                final JsonObject acl = new JsonObject();
                acl.addProperty("id", rule.getId());
                acl.addProperty("uuid", rule.getUuid());
                acl.addProperty("principalType", rule.getPrincipalType().name());
                acl.addProperty("principal", rule.getPrincipal());
                acl.addProperty("permission", rule.getPermission().name());
                acl.addProperty("state", StorageServiceInstance.ResourceState.Ready.name());
                acl.add("config", parseJsonObjectStrict(rule.getConfigJson(), "NFS ACL " + rule.getUuid()));
                acls.add(acl);
            }
            export.add("acls", acls);
            exports.add(export);
        }
        payload.add("exports", exports);
        final int requestedExportCount = exports.size();

        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "nfs export apply", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to apply NFS desired state on Storage Service System VM: " + result.getDetails());
        }
        final JsonObject resultJson = parseJsonObject(result.getResultJson());
        if (storageWriterOperation.get() != null) logger.info("Configuration NFS apply result for {}: requested={} exports={} endpoints={} ready={}",
                instance.getUuid(), requestedExportCount, getJsonInt(resultJson, "exports", 0), getJsonInt(resultJson, "endpoints", 0), getJsonBoolean(resultJson, "runtimeReady"));
        if (resultJson.has("runtimeReady") && !resultJson.get("runtimeReady").getAsBoolean()) {
            throw new CloudRuntimeException("Failed to apply NFS desired state on Storage Service System VM: nfs-ganesha did not report a listening endpoint");
        }
        if (requestedExportCount > 0) {
            final int appliedExportCount = getJsonInt(resultJson, "exports", 0);
            final int appliedEndpointCount = getJsonInt(resultJson, "endpoints", 0);
            if (appliedExportCount <= 0 || appliedEndpointCount <= 0) {
                throw new CloudRuntimeException("Failed to apply NFS desired state on Storage Service System VM: expected " + requestedExportCount +
                        " export(s), but Ganesha runtime reported exports=" + appliedExportCount + ", endpoints=" + appliedEndpointCount);
            }
        }
        if (resultJson.has("runtimeEndpoints") && resultJson.get("runtimeEndpoints").isJsonArray()) {
            for (final JsonElement endpoint : resultJson.getAsJsonArray("runtimeEndpoints")) {
                if (endpoint != null && endpoint.isJsonObject()) {
                    final JsonObject endpointJson = endpoint.getAsJsonObject();
                    if (endpointJson.has("listening") && !endpointJson.get("listening").getAsBoolean()) {
                        final String endpointIp = endpointJson.has("listenIp") ? endpointJson.get("listenIp").getAsString() : "unknown";
                        final String endpointPort = endpointJson.has("port") ? endpointJson.get("port").getAsString() : "unknown";
                        throw new CloudRuntimeException("Failed to apply NFS desired state on Storage Service System VM: nfs-ganesha endpoint " +
                                endpointIp + ":" + endpointPort + " is not listening");
                    }
                }
            }
        }
    }

    protected void applySmbDesiredState(final StorageServiceInstanceVO instance) {
        applySmbDesiredState(instance, Collections.emptyMap());
    }

    protected void applySmbDesiredState(final StorageServiceInstanceVO instance, final Map<Long, String> rulePasswords) {
        if (configurationBatch.get() != null) {
            if (rulePasswords != null) configurationBatch.get().smbCredentials.putAll(rulePasswords);
            return;
        }
        if (instance.getVmId() == null) {
            logger.debug("Storage Service instance [{}] has no System VM yet; SMB state is stored but not applied", instance.getUuid());
            return;
        }

        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("instanceId", instance.getId());
        payload.addProperty("netbiosName", buildSmbNetbiosName(instance));
        final List<StorageServiceProtocolVO> protocols = storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB);
        final JsonArray listeners = new JsonArray();
        StorageServiceProtocolVO primaryProtocol = null;
        boolean enabled = protocols.isEmpty();
        for (final StorageServiceProtocolVO protocol : protocols) {
            if (!protocol.isEnabled()) {
                continue;
            }
            enabled = true;
            if (primaryProtocol == null) {
                primaryProtocol = protocol;
            }
            final JsonObject listener = new JsonObject();
            listener.addProperty("id", protocol.getUuid());
            listener.addProperty("listenIp", StringUtils.defaultIfBlank(protocol.getListenIp(), "0.0.0.0"));
            listener.addProperty("port", protocol.getPort() == null ? defaultProtocolPort(StorageServiceInstance.Protocol.SMB) : protocol.getPort());
            listener.addProperty("state", protocol.getState().name());
            listeners.add(listener);
        }
        payload.addProperty("enabled", enabled);
        payload.add("listeners", listeners);
        if (primaryProtocol != null) {
            payload.addProperty("listenIp", primaryProtocol.getListenIp());
            if (primaryProtocol.getPort() != null) {
                payload.addProperty("port", primaryProtocol.getPort());
            }
        }

        final StorageIdentityDomainVO domain = storageIdentityDomainDao.findByInstanceId(instance.getId());
        if (domain != null) {
            final JsonObject identity = new JsonObject();
            identity.addProperty("domainName", domain.getDomainName());
            identity.addProperty("organizationalUnit", domain.getOrganizationalUnit());
            identity.addProperty("dnsServers", domain.getDnsServers());
            identity.addProperty("joinState", domain.getJoinState().name());
            identity.addProperty("healthState", domain.getHealthState());
            identity.add("config", parseJsonObject(domain.getConfigJson()));
            payload.add("identityDomain", identity);
        }

        final JsonArray shares = new JsonArray();
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB)) {
            final JsonObject smbShare = new JsonObject();
            smbShare.addProperty("id", share.getId());
            smbShare.addProperty("uuid", share.getUuid());
            smbShare.addProperty("name", share.getName());
            smbShare.addProperty("displayPath", share.getPath());
            smbShare.addProperty("path", resolveSmbRuntimeBackingPath(instance, share));
            if (share.getVolumeId() != null) {
                smbShare.addProperty("volumeId", share.getVolumeId());
            }
            smbShare.addProperty("filesystem", share.getFilesystem());
            if (share.getQuotaBytes() != null) {
                smbShare.addProperty("quotaBytes", share.getQuotaBytes());
            }
            smbShare.addProperty("state", share.getState().name());
            smbShare.add("config", parseJsonObject(share.getConfigJson()));

            final JsonArray acls = new JsonArray();
            final JsonArray networkAcls = new JsonArray();
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                if (isSmbNetworkRule(rule)) {
                    JsonObject network=new JsonObject();network.addProperty("uuid",rule.getUuid());network.addProperty("principalType",rule.getPrincipalType().name());network.addProperty("principal",rule.getPrincipal());network.addProperty("permission","CONNECT");network.addProperty("state",rule.getState().name());networkAcls.add(network);continue;
                }
                if (!isSmbPrincipalType(rule.getPrincipalType())) {
                    continue;
                }
                final JsonObject acl = new JsonObject();
                acl.addProperty("id", rule.getId());
                acl.addProperty("uuid", rule.getUuid());
                acl.addProperty("principalType", rule.getPrincipalType().name());
                acl.addProperty("principal", rule.getPrincipal());
                acl.addProperty("permission", rule.getPermission().name());
                acl.addProperty("state", rule.getState().name());
                acl.add("config", parseJsonObject(rule.getConfigJson()));
                if (rulePasswords != null && rulePasswords.containsKey(rule.getId())) {
                    acl.addProperty("password", rulePasswords.get(rule.getId()));
                }
                acls.add(acl);
            }
            smbShare.add("acls", acls);
            smbShare.add("networkAcls", networkAcls);
            shares.add(smbShare);
        }
        payload.add("shares", shares);

        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "smb share apply", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.singleton("password")));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to apply SMB desired state on Storage Service System VM: " + result.getDetails());
        }
    }

    protected String resolveSmbRuntimeBackingPath(final StorageServiceInstanceVO instance, final StorageFileShareVO share) {
        final JsonObject config = parseJsonObject(share.getConfigJson());
        final String backingPath = getJsonString(config, "backingPath");
        if (StringUtils.isNotBlank(backingPath)) {
            return backingPath;
        }
        final JsonObject inspection = getJsonObject(config, "lastInspection");
        final String inspectedBackingPath = getJsonString(inspection, "backingPath");
        if (StringUtils.isNotBlank(inspectedBackingPath)) {
            return inspectedBackingPath;
        }
        final String volumeMountPath = getJsonString(config, "volumeMountPath");
        final String root = StringUtils.isNotBlank(volumeMountPath) ? volumeMountPath : findKnownFileShareVolumeMountRoot(instance, share.getVolumeId());
        if (StringUtils.isNotBlank(root)) {
            final String relative = normalizeRelativeSharePath(StringUtils.defaultIfBlank(getJsonString(config, "relativeSharePath"),
                    StringUtils.removeStart(share.getPath(), "/")));
            return root.replaceAll("/+$", "") + "/" + relative;
        }
        return share.getPath();
    }

    protected void applyAdJoin(final StorageServiceInstanceVO instance, final StorageIdentityDomainVO domain,
            final String username, final String password) {
        if (instance.getVmId() == null) {
            logger.debug("Storage Service instance [{}] has no System VM yet; AD join state is stored but not applied", instance.getUuid());
            return;
        }
        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("netbiosName", buildSmbNetbiosName(instance));
        payload.addProperty("domainName", domain.getDomainName());
        payload.addProperty("username", username);
        payload.addProperty("password", password);
        payload.addProperty("organizationalUnit", domain.getOrganizationalUnit());
        payload.addProperty("dnsServers", domain.getDnsServers());
        payload.add("config", parseJsonObject(domain.getConfigJson()));
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "smb domain join", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.singleton("password")));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to join SMB AD domain on Storage Service System VM: " + result.getDetails());
        }
    }

    protected void applyAdLeave(final StorageServiceInstanceVO instance, final String username, final String password) {
        if (instance.getVmId() == null) {
            logger.debug("Storage Service instance [{}] has no System VM yet; AD leave state is stored but not applied", instance.getUuid());
            return;
        }
        final StorageIdentityDomainVO domain = storageIdentityDomainDao.findByInstanceId(instance.getId());
        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("domainName", domain == null ? null : domain.getDomainName());
        payload.addProperty("username", username);
        payload.addProperty("password", password);
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "smb domain leave", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.singleton("password")));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to leave SMB AD domain on Storage Service System VM: " + result.getDetails());
        }
    }

    protected void cleanupFailedBlockTargetCreate(final StorageServiceInstanceVO instance, final StorageBlockTargetVO target, final boolean cleanupVolumeOnFailure) {
        cleanupFailedBlockTargetCreate(instance, target, StorageServiceInstance.Protocol.ISCSI, cleanupVolumeOnFailure);
    }

    protected void cleanupFailedBlockTargetCreate(final StorageServiceInstanceVO instance, final StorageBlockTargetVO target,
            final StorageServiceInstance.Protocol protocol, final boolean cleanupVolumeOnFailure) {
        if (target == null) {
            return;
        }
        final Long volumeId = target.getVolumeId();
        try {
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, target.getId())) {
                storageAccessRuleDao.remove(rule.getId());
            }
            storageBlockTargetDao.remove(target.getId());
            if (protocol == StorageServiceInstance.Protocol.NVME_OF) {
                applyNvmeOfDesiredState(instance);
            } else {
                applyIscsiDesiredState(instance);
            }
        } catch (final RuntimeException cleanupError) {
            logger.warn("Failed to reconcile Storage Service block target [{}] after create failure", target.getUuid(), cleanupError);
        }
        if (cleanupVolumeOnFailure && volumeId != null) {
            cleanupCreatedBackingVolume(instance, volumeId);
        }
    }

    protected VolumeVO prepareIscsiBackingVolume(final StorageServiceInstanceVO instance, final Long volumeId) {
        return prepareBlockBackingVolume(instance, volumeId);
    }

    protected VolumeVO prepareBlockBackingVolume(final StorageServiceInstanceVO instance, final Long volumeId) {
        VolumeVO volume = requireVolume(volumeId);
        if (instance.getVmId() == null) {
            return volume;
        }
        final Long attachedVmId = volume.getInstanceId();
        if (attachedVmId != null && !attachedVmId.equals(instance.getVmId())) {
            throw new InvalidParameterValueException("Backing volume " + volume.getUuid() + " is already attached to another VM");
        }
        if (attachedVmId == null) {
            volume = waitForFileShareVolumeAttachable(volume.getId());
            volumeApiService.attachVolumeToVM(instance.getVmId(), volume.getId(), null, true);
            volume = requireVolume(volumeId);
        }
        return volume;
    }

    protected void applyIscsiDesiredState(final StorageServiceInstanceVO instance) {
        applyIscsiDesiredState(instance, Collections.emptyMap());
    }

    protected void applyIscsiDesiredState(final StorageServiceInstanceVO instance, final Map<Long, JsonObject> chapSecrets) {
        if (configurationBatch.get() != null) {
            if (chapSecrets != null) configurationBatch.get().iscsiCredentials.putAll(chapSecrets);
            return;
        }
        if (instance.getVmId() == null) {
            logger.debug("Storage Service instance [{}] has no System VM yet; iSCSI state is stored but not applied", instance.getUuid());
            return;
        }

        final JsonObject payload = buildBlockProtocolPayload(instance, StorageServiceInstance.Protocol.ISCSI);
        final JsonArray targets = new JsonArray();
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.ISCSI)) {
            final JsonObject targetJson = createBlockTargetJson(target);
            targetJson.add("acls", createIscsiTargetAclJson(target, chapSecrets));
            targets.add(targetJson);
        }
        payload.add("targets", targets);

        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "iscsi target apply", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(),
                new HashSet<>(Arrays.asList("chapSecret", "mutualChapSecret"))));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to apply iSCSI desired state on Storage Service System VM: " + result.getDetails());
        }
    }

    protected void applyNvmeOfDesiredState(final StorageServiceInstanceVO instance) {
        applyNvmeOfDesiredState(instance, Collections.emptyMap());
    }

    protected void applyNvmeOfDesiredState(final StorageServiceInstanceVO instance, final Map<Long, JsonObject> nvmeSecrets) {
        applyNvmeOfDesiredState(instance, nvmeSecrets, Collections.emptyMap());
    }

    protected void applyNvmeOfDesiredState(final StorageServiceInstanceVO instance, final Map<Long, JsonObject> nvmeSecrets,
            final Map<Long, StorageServiceInstance.ResourceState> hostStateOverrides) {
        if (configurationBatch.get() != null) {
            if (nvmeSecrets != null) configurationBatch.get().nvmeCredentials.putAll(nvmeSecrets);
            if (hostStateOverrides != null) configurationBatch.get().nvmeHostStates.putAll(hostStateOverrides);
            return;
        }
        if (instance.getVmId() == null) {
            logger.debug("Storage Service instance [{}] has no System VM yet; NVMe-oF state is stored but not applied", instance.getUuid());
            return;
        }

        final JsonObject payload = buildNvmeOfDesiredPayload(instance, nvmeSecrets, hostStateOverrides);

        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "nvmeof subsystem apply", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(),
                new HashSet<>(Arrays.asList("dhChapKey", "dhChapCtrlKey"))));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to apply NVMe-oF desired state on Storage Service System VM: " + result.getDetails());
        }
    }

    protected StorageBlockTargetVO findNvmeOfSubsystemByNqn(final long instanceId, final String subsystemNqn) {
        if (StringUtils.isBlank(subsystemNqn)) {
            return null;
        }
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instanceId, StorageServiceInstance.Protocol.NVME_OF)) {
            if (target != null && subsystemNqn.equals(target.getTargetName()) && isNvmeOfSubsystem(target)) {
                if (isActiveStorageServiceResource(target.getState())) {
                    return target;
                }
            }
        }
        return null;
    }

    protected StorageBlockTargetVO canonicalNvmeOfSubsystem(final StorageBlockTargetVO subsystem) {
        if (subsystem == null || StringUtils.isBlank(subsystem.getTargetName())) {
            return subsystem;
        }
        final StorageBlockTargetVO canonical = findNvmeOfSubsystemByNqn(subsystem.getInstanceId(), subsystem.getTargetName());
        return canonical == null ? subsystem : canonical;
    }

    protected List<StorageBlockTargetVO> listNvmeOfSubsystemGroup(final StorageBlockTargetVO subsystem) {
        if (subsystem == null || StringUtils.isBlank(subsystem.getTargetName())) {
            return Collections.emptyList();
        }
        final List<StorageBlockTargetVO> group = new ArrayList<>();
        for (final StorageBlockTargetVO candidate : storageBlockTargetDao.listByInstanceIdAndProtocol(subsystem.getInstanceId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (candidate != null && subsystem.getTargetName().equals(candidate.getTargetName()) && isNvmeOfSubsystem(candidate)) {
                group.add(candidate);
            }
        }
        return group.isEmpty() ? Collections.singletonList(subsystem) : group;
    }

    protected JsonObject buildNvmeOfDesiredPayload(final StorageServiceInstanceVO instance, final Map<Long, JsonObject> nvmeSecrets,
            final Map<Long, StorageServiceInstance.ResourceState> hostStateOverrides) {
        final JsonObject payload = buildBlockProtocolPayload(instance, StorageServiceInstance.Protocol.NVME_OF);
        final List<StorageBlockTargetVO> targets = storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF);
        final JsonArray subsystems = new JsonArray();
        final Map<String, JsonObject> subsystemByNqn = new LinkedHashMap<>();
        final Map<String, Set<String>> namespaceIdsByNqn = new HashMap<>();

        for (final StorageBlockTargetVO target : targets) {
            if (!isNvmeOfSubsystem(target)) {
                continue;
            }
            final String nqn = StringUtils.trimToNull(target.getTargetName());
            if (nqn == null) {
                continue;
            }
            if (subsystemByNqn.containsKey(nqn)) {
                mergeNvmeOfSubsystemJson(subsystemByNqn.get(nqn), target);
                continue;
            }
            final JsonObject subsystem = createBlockTargetJson(target);
            subsystem.add("namespaces", new JsonArray());
            subsystem.add("hosts", new JsonArray());
            subsystemByNqn.put(nqn, subsystem);
            namespaceIdsByNqn.put(nqn, new HashSet<>());
        }

        for (final StorageBlockTargetVO namespace : targets) {
            if (!isNvmeOfNamespace(namespace)) {
                continue;
            }
            final String nqn = StringUtils.trimToNull(namespace.getTargetName());
            final JsonObject subsystem = nqn == null ? null : subsystemByNqn.get(nqn);
            if (subsystem == null) {
                logger.warn("Skipping NVMe-oF namespace [{}] because subsystem [{}] does not exist in Storage Service instance [{}]",
                        namespace.getUuid(), namespace.getTargetName(), instance.getUuid());
                continue;
            }
            final String namespaceId = StringUtils.defaultIfBlank(namespace.getLunOrNamespace(), "1");
            if (namespaceIdsByNqn.get(nqn).add(namespaceId)) {
                subsystem.getAsJsonArray("namespaces").add(createBlockTargetJson(namespace));
            }
        }

        for (final StorageBlockTargetVO target : targets) {
            if (!isNvmeOfSubsystem(target)) {
                continue;
            }
            final String nqn = StringUtils.trimToNull(target.getTargetName());
            final JsonObject subsystem = nqn == null ? null : subsystemByNqn.get(nqn);
            if (subsystem == null) {
                continue;
            }
            final JsonObject subsystemConfig = subsystem.has("config") && subsystem.get("config").isJsonObject() ? subsystem.getAsJsonObject("config") : new JsonObject();
            if (Boolean.TRUE.equals(getJsonBoolean(subsystemConfig, "allowAnyHost"))) {
                continue;
            }
            final JsonArray hosts = subsystem.getAsJsonArray("hosts");
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, target.getId())) {
                if (rule.getPrincipalType() == StorageServiceInstance.PrincipalType.NVME_HOST_NQN) {
                    final JsonObject host = createBlockAclJson(rule);
                    if (hostStateOverrides != null && hostStateOverrides.containsKey(rule.getId())) {
                        host.addProperty("state", hostStateOverrides.get(rule.getId()).name());
                    }
                    if (nvmeSecrets != null && nvmeSecrets.containsKey(rule.getId())) {
                        host.add("secrets", nvmeSecrets.get(rule.getId()));
                    }
                    hosts.add(host);
                }
            }
        }
        for (final JsonObject subsystem : subsystemByNqn.values()) {
            subsystems.add(subsystem);
        }
        payload.add("subsystems", subsystems);

        return payload;
    }

    protected List<StorageAccessRuleVO> listNvmeOfHostAclRules(final StorageBlockTargetVO subsystem) {
        final Map<String, StorageAccessRuleVO> rulesByPrincipal = new LinkedHashMap<>();
        for (final StorageBlockTargetVO candidate : listNvmeOfSubsystemGroup(subsystem)) {
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, candidate.getId())) {
                if (rule.getPrincipalType() != StorageServiceInstance.PrincipalType.NVME_HOST_NQN || StringUtils.isBlank(rule.getPrincipal())) {
                    continue;
                }
                rulesByPrincipal.putIfAbsent(rule.getPrincipal(), rule);
            }
        }
        return new ArrayList<>(rulesByPrincipal.values());
    }

    protected StorageAccessRuleVO findNvmeOfHostAclByPrincipal(final StorageBlockTargetVO subsystem, final String hostNqn) {
        if (StringUtils.isBlank(hostNqn)) {
            return null;
        }
        for (final StorageAccessRuleVO rule : listNvmeOfHostAclRules(subsystem)) {
            if (hostNqn.equals(rule.getPrincipal())) {
                return rule;
            }
        }
        return null;
    }

    protected void validateNvmeOfHostAclScope(final StorageBlockTargetVO subsystem) {
        final JsonObject config = parseJsonObject(subsystem.getConfigJson());
        if (Boolean.TRUE.equals(getJsonBoolean(config, "allowAnyHost"))) {
            throw new InvalidParameterValueException("NVMe-oF subsystem allows all hosts. Disable all-host access before creating explicit host ACLs.");
        }
    }

    protected boolean isActiveStorageServiceResource(final StorageServiceInstance.ResourceState state) {
        return state != StorageServiceInstance.ResourceState.Disabled &&
                state != StorageServiceInstance.ResourceState.Destroyed &&
                state != StorageServiceInstance.ResourceState.Error;
    }

    protected void mergeNvmeOfSubsystemJson(final JsonObject canonicalSubsystem, final StorageBlockTargetVO duplicateSubsystem) {
        final JsonObject canonicalConfig = canonicalSubsystem.has("config") && canonicalSubsystem.get("config").isJsonObject()
                ? canonicalSubsystem.getAsJsonObject("config") : new JsonObject();
        final JsonObject duplicateConfig = parseJsonObject(duplicateSubsystem.getConfigJson());
        if (!canonicalSubsystem.has("config") || !canonicalSubsystem.get("config").isJsonObject()) {
            canonicalSubsystem.add("config", canonicalConfig);
        }
        if (Boolean.TRUE.equals(getJsonBoolean(duplicateConfig, "allowAnyHost"))) {
            canonicalConfig.addProperty("allowAnyHost", true);
        }
        for (final String property : Arrays.asList("engine", "engineState", "transport")) {
            final String current = getJsonString(canonicalConfig, property);
            final String replacement = getJsonString(duplicateConfig, property);
            if (StringUtils.isBlank(current) && StringUtils.isNotBlank(replacement)) {
                canonicalConfig.addProperty(property, replacement);
            }
        }
        final String currentState = getJsonString(canonicalSubsystem, "state");
        if (StringUtils.isBlank(currentState) || "Error".equals(currentState) || "Disabled".equals(currentState) || "Destroyed".equals(currentState)) {
            canonicalSubsystem.addProperty("state", duplicateSubsystem.getState().name());
        }
    }

    protected boolean deleteStorageServiceEndpoint(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocol, final String listenIp, final Integer port) {
        if (protocol == StorageServiceInstance.Protocol.NVME_OF) {
            return deleteNvmeOfStorageServiceEndpoint(instance, listenIp, port);
        }
        if (protocol == StorageServiceInstance.Protocol.SMB) {
            return deleteSmbStorageServiceEndpoint(instance, listenIp, port);
        }
        if (protocol != StorageServiceInstance.Protocol.NFS) {
            throw new InvalidParameterValueException("Endpoint removal is currently supported for NFS protocol endpoints only");
        }
        final String endpoint = StringUtils.trim(listenIp);
        if (!isValidIpv4Address(endpoint)) {
            throw new InvalidParameterValueException("Invalid Storage Service endpoint IP: " + listenIp);
        }
        for (final NicVO nic : nicDao.listByVmId(instance.getVmId())) {
            if (endpoint.equals(nic.getIPv4Address())) {
                throw new InvalidParameterValueException("Primary Storage Service NIC IP cannot be removed as an endpoint: " + endpoint);
            }
        }

        boolean changed = false;
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            final JsonObject config = parseJsonObject(share.getConfigJson());
            if (removeListenIpFromConfig(config, endpoint)) {
                share.setConfigJson(GSON.toJson(config));
                storageFileShareDao.update(share.getId(), share);
                changed = true;
            }
        }

        for (final StorageServiceProtocolVO protocolVO : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
            if (endpoint.equals(protocolVO.getListenIp())) {
                storageServiceProtocolDao.remove(protocolVO.getId());
                changed = true;
            }
        }
        removeSecondaryListenAddress(instance, endpoint);
        applyNfsDesiredState(instance, endpoint);
        return changed;
    }

    protected boolean deleteSmbStorageServiceEndpoint(StorageServiceInstanceVO instance, String listenIp, Integer port) {
        final String ip = StringUtils.trim(listenIp);
        final int expectedPort = port == null ? 445 : port;
        final List<StorageServiceProtocolVO> listeners = storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB);
        final List<StorageServiceProtocolVO> matches = new ArrayList<>();
        for (StorageServiceProtocolVO listener : listeners) {
            if (StringUtils.equals(StringUtils.trim(listener.getListenIp()), ip) &&
                    (listener.getPort() == null ? 445 : listener.getPort()) == expectedPort) matches.add(listener);
        }
        if (matches.isEmpty()) return true;
        boolean other = listeners.stream().anyMatch(listener -> listener.isEnabled() && !matches.contains(listener));
        if (!other && !storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB).isEmpty()) {
            throw new InvalidParameterValueException("The last SMB endpoint is still used by shares");
        }
        for (StorageServiceProtocolVO listener : matches) storageServiceProtocolDao.remove(listener.getId());
        applySmbDesiredState(instance);
        boolean used = storageServiceProtocolDao.listByInstanceId(instance.getId()).stream()
                .anyMatch(listener -> listener.isEnabled() && StringUtils.equals(listener.getListenIp(), ip));
        boolean primary = nicDao.listByVmId(instance.getVmId()).stream().anyMatch(nic -> StringUtils.equals(nic.getIPv4Address(), ip));
        if (!used && !primary && !isWildcardListenIp(ip)) removeSecondaryListenAddress(instance, ip);
        return true;
    }

    protected boolean deleteNvmeOfStorageServiceEndpoint(final StorageServiceInstanceVO instance, final String listenIp, final Integer port) {
        final String endpoint = StringUtils.trim(listenIp);
        if (!isWildcardListenIp(endpoint) && !isValidIpv4Address(endpoint)) {
            throw new InvalidParameterValueException("Invalid Storage Service endpoint IP: " + listenIp);
        }
        if (port == null || port < 1 || port > 65535) {
            throw new InvalidParameterValueException("NVMe-oF endpoint removal requires a valid listener port.");
        }
        if (!isWildcardListenIp(endpoint)) {
            for (final NicVO nic : nicDao.listByVmId(instance.getVmId())) {
                if (endpoint.equals(nic.getIPv4Address())) {
                    throw new InvalidParameterValueException("Primary Storage Service NIC IP cannot be removed as an endpoint: " + endpoint);
                }
            }
        }

        StorageServiceProtocolVO protocolToDelete = null;
        for (final StorageServiceProtocolVO protocolVO : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            final String candidateIp = StringUtils.defaultIfBlank(protocolVO.getListenIp(), "0.0.0.0");
            final int candidatePort = protocolVO.getPort() == null ? 4420 : protocolVO.getPort();
            if (endpoint.equals(candidateIp) && port == candidatePort) {
                protocolToDelete = protocolVO;
                break;
            }
        }
        if (protocolToDelete == null) {
            throw new InvalidParameterValueException("NVMe-oF listener endpoint does not exist: " + endpoint + ":" + port);
        }

        validateNvmeOfListenerCanBeDeleted(instance, protocolToDelete, port);
        final Boolean previousEnabled = protocolToDelete.isEnabled();
        final StorageServiceInstance.ResourceState previousState = protocolToDelete.getState();
        protocolToDelete.setEnabled(false);
        protocolToDelete.setState(StorageServiceInstance.ResourceState.Updating);
        storageServiceProtocolDao.update(protocolToDelete.getId(), protocolToDelete);
        try {
            applyNvmeOfDesiredState(instance);
            storageServiceProtocolDao.remove(protocolToDelete.getId());
            if (!isWildcardListenIp(endpoint)) {
                removeSecondaryListenAddress(instance, endpoint);
            }
        } catch (final RuntimeException e) {
            protocolToDelete.setEnabled(Boolean.TRUE.equals(previousEnabled));
            protocolToDelete.setState(previousState);
            storageServiceProtocolDao.update(protocolToDelete.getId(), protocolToDelete);
            throw e;
        }
        return true;
    }

    protected void validateNvmeOfListenerCanBeDeleted(final StorageServiceInstanceVO instance, final StorageServiceProtocolVO protocolToDelete, final int port) {
        boolean portInUse = false;
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (target == null || !isNvmeOfNamespace(target) || !isActiveStorageServiceResource(target.getState())) {
                continue;
            }
            if (nvmeOfListenerPortSet(parseJsonObject(target.getConfigJson())).contains(port)) {
                portInUse = true;
                break;
            }
        }
        if (!portInUse) {
            return;
        }
        for (final StorageServiceProtocolVO protocolVO : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (protocolVO == null || !protocolVO.isEnabled() || protocolVO.getId() == protocolToDelete.getId()) {
                continue;
            }
            final int candidatePort = protocolVO.getPort() == null ? 4420 : protocolVO.getPort();
            if (candidatePort == port) {
                return;
            }
        }
        throw new InvalidParameterValueException("NVMe-oF listener port group is used by namespaces and cannot be removed while it is the last listener for port " + port);
    }

    protected void validateNvmeOfSubsystemCanBeDeleted(final StorageBlockTargetVO subsystem) {
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(subsystem.getInstanceId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (target == null || target.getId() == subsystem.getId() || !StringUtils.equals(subsystem.getTargetName(), target.getTargetName())) {
                continue;
            }
            if (isNvmeOfNamespace(target) && isActiveStorageServiceResource(target.getState())) {
                throw new InvalidParameterValueException("NVMe-oF subsystem has namespaces. Delete namespaces before deleting the subsystem.");
            }
        }
        if (!storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, subsystem.getId()).isEmpty()) {
            throw new InvalidParameterValueException("NVMe-oF subsystem has host ACLs. Delete host ACLs before deleting the subsystem.");
        }
    }

    protected void freezeNfsImplicitAllEndpointExports(final StorageServiceInstanceVO instance, final String previousListenIp) {
        final String endpoint = StringUtils.trim(previousListenIp);
        if (StringUtils.isBlank(endpoint)) {
            return;
        }
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            final JsonObject config = parseJsonObject(share.getConfigJson());
            final String endpointMode = nfsEndpointModeAsString(config);
            if (!"ALL".equals(endpointMode) || StringUtils.isNotBlank(nfsListenIpsAsString(config))) {
                continue;
            }
            final JsonArray listenIps = new JsonArray();
            listenIps.add(endpoint);
            config.addProperty("endpointMode", "SELECTED");
            config.add("listenIps", listenIps);
            share.setConfigJson(GSON.toJson(config));
            storageFileShareDao.update(share.getId(), share);
        }
    }

    protected StorageServiceInstance.ResourceState getAppliedResourceState(final StorageServiceInstanceVO instance) {
        return instance.getVmId() == null ? StorageServiceInstance.ResourceState.Allocated : StorageServiceInstance.ResourceState.Ready;
    }

    protected void applyStorageServiceProtocolDesiredState(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocol) {
        if (protocol == StorageServiceInstance.Protocol.NFS) {
            applyNfsDesiredState(instance);
        } else if (protocol == StorageServiceInstance.Protocol.SMB) {
            applySmbDesiredState(instance);
        } else if (protocol == StorageServiceInstance.Protocol.ISCSI) {
            applyIscsiDesiredState(instance);
        } else if (protocol == StorageServiceInstance.Protocol.NVME_OF) {
            applyNvmeOfDesiredState(instance);
        }
    }

    protected void applyFileShareDesiredState(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocol) {
        if (protocol == StorageServiceInstance.Protocol.NFS) {
            applyNfsDesiredState(instance);
        } else if (protocol == StorageServiceInstance.Protocol.SMB) {
            applySmbDesiredState(instance);
        } else {
            throw new InvalidParameterValueException("Protocol " + protocol + " is not a file service protocol");
        }
    }

    protected void inspectAttachedFileShareVolume(final StorageServiceInstanceVO instance, final StorageFileShareVO share,
            final VolumeVO volume, final String importMode) {
        final JsonObject payload = createFileShareVolumePayload(instance, share, volume);
        final String mode = StringUtils.defaultIfBlank(importMode, "MOUNT_EXISTING").toUpperCase(java.util.Locale.ROOT);
        payload.addProperty("importMode", mode);
        final boolean formatting = "FORMAT_EMPTY".equals(mode) || "FORMAT_IF_EMPTY".equals(mode);
        final int formatDeadline = backingVolumeFormatDeadline(volume.getSize() == null ? 0 : volume.getSize());
        payload.addProperty("formatDeadlineSeconds", formatDeadline);
        payload.addProperty("operationId", "volume-" + volume.getUuid());
        final int commandDeadline = formatting ? Math.max(StorageServiceInstance.StorageServiceCommandTimeout.value(), formatDeadline + 120)
                : StorageServiceInstance.StorageServiceCommandTimeout.value();
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "volume attach inspect", GSON.toJson(payload), commandDeadline, Collections.emptySet()));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to inspect attached Storage Service volume: " + result.getDetails());
        }
        final JsonObject resultJson = parseJsonObject(result.getResultJson());
        final String observedVolumeUuid = getJsonString(resultJson, "volumeUuid");
        if (StringUtils.isNotBlank(observedVolumeUuid)
                && !normalizeVolumeIdentity(volume.getUuid()).equals(normalizeVolumeIdentity(observedVolumeUuid))) {
            throw new CloudRuntimeException("Storage Service volume inspection returned a different backing volume identity");
        }
        resultJson.addProperty("volumeUuid", volume.getUuid());
        if (resultJson.has("filesystem") && !resultJson.get("filesystem").isJsonNull()) {
            share.setFilesystem(resultJson.get("filesystem").getAsString());
        }
        share.setConfigJson(buildFileShareAttachConfigJson(share.getConfigJson(), importMode, volume, resultJson));
        storageFileShareDao.update(share.getId(), share);
    }

    protected int backingVolumeFormatDeadline(long bytes) {
        final long minimum = Math.max(30, StorageServiceInstance.StorageServiceFormatMinimumTimeout.value());
        final long perTiB = Math.max(0, StorageServiceInstance.StorageServiceFormatSecondsPerTiB.value());
        final long maximum = Math.min(7200, Math.max(minimum, StorageServiceInstance.StorageServiceFormatMaximumTimeout.value()));
        final long tib = Math.max(1, (bytes + (1L << 40) - 1) / (1L << 40));
        return (int) Math.min(maximum, minimum + tib * perTiB);
    }

    protected void growFileShareFilesystem(final StorageServiceInstanceVO instance, final StorageFileShareVO share,
            final Long volumeSizeGb, final Long quotaBytes) {
        final VolumeVO volume = requireVolume(share.getVolumeId());
        final JsonObject payload = createFileShareVolumePayload(instance, share, volume);
        if (volumeSizeGb != null) {
            payload.addProperty("volumeSizeGb", volumeSizeGb);
        }
        if (quotaBytes != null) {
            payload.addProperty("quotaBytes", quotaBytes);
        }
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "filesystem resize", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
        if (!result.isSuccess()) {
            share.setState(StorageServiceInstance.ResourceState.Error);
            storageFileShareDao.update(share.getId(), share);
            throw new CloudRuntimeException("Failed to resize Storage Service file share filesystem: " + result.getDetails());
        }
        final JsonObject resultJson = parseJsonObject(result.getResultJson());
        share.setConfigJson(buildFileShareResizeConfigJson(share.getConfigJson(), resultJson, quotaBytes));
        storageFileShareDao.update(share.getId(), share);
    }

    protected JsonObject createFileShareVolumePayload(final StorageServiceInstanceVO instance, final StorageFileShareVO share, final VolumeVO volume) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("shareId", share.getId());
        payload.addProperty("shareUuid", share.getUuid());
        payload.addProperty("protocol", share.getProtocol().name());
        payload.addProperty("name", share.getName());
        payload.addProperty("path", share.getPath());
        payload.addProperty("filesystem", share.getFilesystem());
        payload.addProperty("quotaBytes", share.getQuotaBytes());
        payload.addProperty("volumeId", volume.getId());
        payload.addProperty("volumeUuid", volume.getUuid());
        payload.addProperty("volumeName", volume.getName());
        payload.addProperty("volumeSizeBytes", volume.getSize());
        final JsonObject config = parseJsonObject(share.getConfigJson()).deepCopy();
        if (!config.has("volumeMountPath") || config.get("volumeMountPath").isJsonNull()) {
            config.addProperty("volumeMountPath", resolveFileShareVolumeMountRoot(instance, volume, share.getPath()));
        }
        config.remove("devicePath");
        final JsonObject inspection = getJsonObject(config, "lastInspection");
        if (inspection != null) {
            final JsonObject commandInspection = inspection.deepCopy();
            commandInspection.remove("devicePath");
            commandInspection.remove("observedDevicePath");
            config.add("lastInspection", commandInspection);
        }
        payload.add("config", config);
        return payload;
    }

    protected void resizeBackingVolume(final Long volumeId, final Long sizeGb) {
        final ResizeVolumeCmd resizeVolumeCmd = new ResizeVolumeCmd();
        resizeVolumeCmd.setId(volumeId);
        resizeVolumeCmd.setSize(sizeGb);
        try {
            final Volume resizedVolume = volumeApiService.resizeVolume(resizeVolumeCmd);
            if (resizedVolume == null) {
                throw new CloudRuntimeException("CloudStack volume resize returned no volume for id " + volumeId);
            }
        } catch (final ResourceAllocationException e) {
            throw new CloudRuntimeException("Failed to resize backing volume " + volumeId + ": " + e.getMessage(), e);
        }
    }

    protected JsonObject buildBlockProtocolPayload(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocolType) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("instanceId", instance.getId());
        final StorageServiceProtocolVO protocol = storageServiceProtocolDao.findByInstanceIdAndProtocol(instance.getId(), protocolType);
        payload.addProperty("enabled", protocol == null || protocol.isEnabled());
        if (protocol != null) {
            payload.addProperty("listenIp", protocol.getListenIp());
            if (protocol.getPort() != null) {
                payload.addProperty("port", protocol.getPort());
            }
        }
        payload.add("listeners", buildBlockProtocolListeners(instance, protocolType));
        payload.add("endpointAliases", buildBlockProtocolEndpointAliases(instance, protocolType));
        return payload;
    }

    protected JsonArray buildBlockProtocolListeners(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocolType) {
        final JsonArray listeners = new JsonArray();
        final HashSet<String> seen = new HashSet<>();
        final List<StorageServiceProtocolVO> protocols = storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocolType);
        final HashSet<Integer> wildcardPorts = new HashSet<>();
        for (final StorageServiceProtocolVO protocol : protocols) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            final String listenIp = normalizeListenIp(protocol.getListenIp());
            if (isWildcardListenIp(listenIp)) {
                final int defaultPort = protocolType == StorageServiceInstance.Protocol.ISCSI ? 3260 : 4420;
                wildcardPorts.add(protocol.getPort() == null ? defaultPort : protocol.getPort());
            }
        }
        for (final StorageServiceProtocolVO protocol : protocols) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            final String listenIp = normalizeListenIp(protocol.getListenIp());
            final int defaultPort = protocolType == StorageServiceInstance.Protocol.ISCSI ? 3260 : 4420;
            final int port = protocol.getPort() == null ? defaultPort : protocol.getPort();
            if (wildcardPorts.contains(port) && !isWildcardListenIp(listenIp)) {
                continue;
            }
            final String key = listenIp + ":" + port;
            if (!seen.add(key)) {
                continue;
            }
            final JsonObject listener = new JsonObject();
            listener.addProperty("listenIp", listenIp);
            listener.addProperty("port", port);
            addProtocolEndpointNetworkMetadata(listener, instance, listenIp);
            listeners.add(listener);
        }
        if (listeners.size() == 0) {
            final JsonObject listener = new JsonObject();
            listener.addProperty("listenIp", "0.0.0.0");
            listener.addProperty("port", protocolType == StorageServiceInstance.Protocol.ISCSI ? 3260 : 4420);
            listeners.add(listener);
        }
        return listeners;
    }

    protected JsonArray buildBlockProtocolEndpointAliases(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocolType) {
        final JsonArray aliases = new JsonArray();
        final HashSet<String> seen = new HashSet<>();
        final List<StorageServiceProtocolVO> protocols = storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocolType);
        final Map<Integer, String> wildcardByPort = new HashMap<>();
        for (final StorageServiceProtocolVO protocol : protocols) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            final String listenIp = normalizeListenIp(protocol.getListenIp());
            final int port = protocol.getPort() == null ? defaultProtocolPort(protocolType) : protocol.getPort();
            if (isWildcardListenIp(listenIp)) {
                wildcardByPort.put(port, listenIp);
            }
        }
        for (final StorageServiceProtocolVO protocol : protocols) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            final String listenIp = normalizeListenIp(protocol.getListenIp());
            if (isWildcardListenIp(listenIp)) {
                continue;
            }
            final int port = protocol.getPort() == null ? defaultProtocolPort(protocolType) : protocol.getPort();
            final String key = listenIp + ":" + port;
            if (!seen.add(key)) {
                continue;
            }
            final JsonObject alias = new JsonObject();
            alias.addProperty("protocol", protocolType.name());
            alias.addProperty("listenIp", listenIp);
            alias.addProperty("port", port);
            addProtocolEndpointNetworkMetadata(alias, instance, listenIp);
            if (wildcardByPort.containsKey(port)) {
                alias.addProperty("coveredByWildcard", true);
                alias.addProperty("effectiveListenIp", wildcardByPort.get(port));
                alias.addProperty("effectivePort", port);
            }
            aliases.add(alias);
        }
        return aliases;
    }

    protected void addProtocolEndpointNetworkMetadata(final JsonObject endpoint, final StorageServiceInstanceVO instance, final String listenIp) {
        if (endpoint == null || instance == null || isWildcardListenIp(listenIp)) {
            return;
        }
        try {
            final NicVO targetNic = resolveProtocolListenAddress(instance, listenIp);
            if (targetNic == null) {
                return;
            }
            endpoint.addProperty("nicId", targetNic.getId());
            endpoint.addProperty("networkId", targetNic.getNetworkId());
            if (StringUtils.isNotBlank(targetNic.getUuid())) {
                endpoint.addProperty("nicUuid", targetNic.getUuid());
            }
            if (StringUtils.isNotBlank(targetNic.getIPv4Address())) {
                endpoint.addProperty("primaryIp", targetNic.getIPv4Address());
            }
            if (StringUtils.isNotBlank(targetNic.getIPv4Netmask())) {
                endpoint.addProperty("netmask", targetNic.getIPv4Netmask());
                endpoint.addProperty("prefixlen", ipv4NetmaskToPrefixLength(targetNic.getIPv4Netmask()));
            }
            final String cidr = findProtocolEndpointCidr(instance, targetNic, listenIp);
            if (StringUtils.isNotBlank(cidr)) {
                endpoint.addProperty("networkCidr", cidr);
                if (!endpoint.has("prefixlen")) {
                    endpoint.addProperty("prefixlen", cidrPrefixLength(cidr));
                }
            }
        } catch (final RuntimeException e) {
            logger.warn("Unable to enrich Storage Service endpoint [{}] with guest network metadata; System VM will validate it at apply time",
                    listenIp, e);
        }
    }

    protected String findProtocolEndpointCidr(final StorageServiceInstanceVO instance, final NicVO targetNic, final String listenIp) {
        if (targetNic != null) {
            final NetworkVO network = networkDao.findById(targetNic.getNetworkId());
            if (network != null) {
                if (isIpv4InCidr(listenIp, network.getNetworkCidr())) {
                    return network.getNetworkCidr();
                }
                if (isIpv4InCidr(listenIp, network.getCidr())) {
                    return network.getCidr();
                }
            }
        }
        final DataCenterVO zone = instance == null ? null : dataCenterDao.findById(instance.getDataCenterId());
        final String zoneGuestCidr = zone == null ? null : zone.getGuestNetworkCidr();
        if (isIpv4InCidr(listenIp, zoneGuestCidr)) {
            return zoneGuestCidr;
        }
        return null;
    }

    protected int cidrPrefixLength(final String cidr) {
        if (StringUtils.isBlank(cidr)) {
            return 24;
        }
        final String[] parts = StringUtils.split(cidr, '/');
        if (parts == null || parts.length != 2 || !StringUtils.isNumeric(parts[1])) {
            return 24;
        }
        final int prefix = Integer.parseInt(parts[1]);
        return prefix >= 0 && prefix <= 32 ? prefix : 24;
    }

    protected JsonObject createBlockTargetJson(final StorageBlockTargetVO target) {
        final JsonObject targetJson = new JsonObject();
        final JsonObject config = parseJsonObject(target.getConfigJson());
        final boolean iscsiBlockTarget = target.getProtocol() == StorageServiceInstance.Protocol.ISCSI
                && "BLOCK".equalsIgnoreCase(StringUtils.defaultIfBlank(getJsonString(config, "backstoreType"), "BLOCK"));
        Long configuredSize = iscsiBlockTarget ? null : getJsonLong(config, "lunSizeBytes");
        if (configuredSize == null && !iscsiBlockTarget) {
            configuredSize = getJsonLong(config, "namespaceSizeBytes");
        }
        Long volumeSize = null;
        targetJson.addProperty("id", target.getId());
        targetJson.addProperty("uuid", target.getUuid());
        targetJson.addProperty("protocol", target.getProtocol().name());
        targetJson.addProperty("targetName", target.getTargetName());
        targetJson.addProperty("lunOrNamespace", target.getLunOrNamespace());
        if (target.getVolumeId() != null) {
            targetJson.addProperty("volumeId", target.getVolumeId());
            final VolumeVO volume = volumeDao.findById(target.getVolumeId());
            if (volume != null) {
                targetJson.addProperty("volumeUuid", volume.getUuid());
                targetJson.addProperty("volumeName", volume.getName());
                if (StringUtils.isNotBlank(volume.getPath())) {
                    targetJson.addProperty("volumePath", volume.getPath());
                }
                if (volume.getDeviceId() != null) {
                    targetJson.addProperty("volumeDeviceId", volume.getDeviceId());
                }
                final String serialPrefix = compactVolumeIdentity(volume.getUuid());
                if (StringUtils.isNotBlank(serialPrefix)) {
                    targetJson.addProperty("expectedSerialPrefix", serialPrefix.length() > 20 ? serialPrefix.substring(0, 20) : serialPrefix);
                }
                volumeSize = volume.getSize();
                targetJson.addProperty("volumeSizeBytes", volumeSize);
            }
        }
        if (configuredSize != null) {
            targetJson.addProperty("lunSizeBytes", configuredSize);
        }
        if (configuredSize != null || volumeSize != null) {
            targetJson.addProperty("effectiveSizeBytes", configuredSize == null ? volumeSize : configuredSize);
        }
        final String backingPath = getJsonString(config, "backingPath");
        if (StringUtils.isNotBlank(backingPath)) {
            targetJson.addProperty("backingPath", backingPath);
        }
        final String backstoreType = getJsonString(config, "backstoreType");
        if (StringUtils.isNotBlank(backstoreType)) {
            targetJson.addProperty("backstoreType", backstoreType);
        }
        final String endpointMode = getJsonString(config, "endpointMode");
        if (StringUtils.isNotBlank(endpointMode)) {
            targetJson.addProperty("endpointMode", endpointMode);
        }
        final String listenerPorts = listenerPortsAsString(config);
        if (StringUtils.isNotBlank(listenerPorts)) {
            targetJson.addProperty("listenerPorts", listenerPorts);
        }
        StorageServiceInstance.ResourceState desiredState = target.getState();
        if (desiredState == StorageServiceInstance.ResourceState.Creating || desiredState == StorageServiceInstance.ResourceState.Updating) {
            desiredState = StorageServiceInstance.ResourceState.Ready;
        }
        targetJson.addProperty("state", desiredState.name());
        targetJson.add("config", config);
        return targetJson;
    }

    protected JsonObject createBlockAclJson(final StorageAccessRuleVO rule) {
        final JsonObject acl = new JsonObject();
        acl.addProperty("id", rule.getId());
        acl.addProperty("uuid", rule.getUuid());
        acl.addProperty("principalType", rule.getPrincipalType().name());
        acl.addProperty("principal", rule.getPrincipal());
        acl.addProperty("permission", rule.getPermission().name());
        acl.addProperty("state", rule.getState().name());
        acl.add("config", parseJsonObject(rule.getConfigJson()));
        return acl;
    }

    protected JsonArray createIscsiTargetAclJson(final StorageBlockTargetVO target, final Map<Long, JsonObject> chapSecrets) {
        final JsonArray acls = new JsonArray();
        final Map<String, JsonObject> aclByPrincipal = new HashMap<>();
        final Map<String, String> chapSignatureByPrincipal = new HashMap<>();
        for (final StorageBlockTargetVO candidate : listBlockTargetGroup(target)) {
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, candidate.getId())) {
                if (rule.getPrincipalType() != StorageServiceInstance.PrincipalType.ISCSI_INITIATOR_IQN || StringUtils.isBlank(rule.getPrincipal())) {
                    continue;
                }
                final JsonObject config = parseJsonObject(rule.getConfigJson());
                final String signature = iscsiAclChapSignature(config);
                final String previous = chapSignatureByPrincipal.putIfAbsent(rule.getPrincipal(), signature);
                if (previous != null && !previous.equals(signature)) {
                    throw new CloudRuntimeException("Conflicting iSCSI CHAP settings for target " + target.getTargetName()
                            + " and initiator " + rule.getPrincipal() + ". iSCSI CHAP is target-scoped; update the existing ACL instead of creating per-LUN variants.");
                }
                JsonObject acl = aclByPrincipal.get(rule.getPrincipal());
                if (acl == null) {
                    acl = createBlockAclJson(rule);
                    aclByPrincipal.put(rule.getPrincipal(), acl);
                }
                if (chapSecrets != null && chapSecrets.containsKey(rule.getId())) {
                    acl.add("secrets", chapSecrets.get(rule.getId()));
                }
            }
        }
        for (final JsonObject acl : aclByPrincipal.values()) {
            acls.add(acl);
        }
        return acls;
    }

    protected void validateIscsiAclTargetScope(final StorageBlockTargetVO target, final Long currentRuleId, final String principal, final JsonObject config) {
        if (target == null || target.getProtocol() != StorageServiceInstance.Protocol.ISCSI || StringUtils.isBlank(principal)) {
            return;
        }
        final String requestedSignature = iscsiAclChapSignature(config);
        for (final StorageBlockTargetVO candidate : listBlockTargetGroup(target)) {
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, candidate.getId())) {
                if (currentRuleId != null && currentRuleId.equals(rule.getId())) {
                    continue;
                }
                if (rule.getPrincipalType() != StorageServiceInstance.PrincipalType.ISCSI_INITIATOR_IQN || !principal.equals(rule.getPrincipal())) {
                    continue;
                }
                final String existingSignature = iscsiAclChapSignature(parseJsonObject(rule.getConfigJson()));
                if (!requestedSignature.equals(existingSignature)) {
                    throw new InvalidParameterValueException("iSCSI CHAP settings are target-scoped. The same initiator already has different CHAP settings on target "
                            + target.getTargetName() + "; update the existing ACL or use consistent CHAP settings across all LUNs.");
                }
            }
        }
    }

    protected String iscsiAclChapSignature(final JsonObject config) {
        final boolean chapEnabled = Boolean.TRUE.equals(getJsonBoolean(config, "chapEnabled"));
        final boolean mutualChapEnabled = Boolean.TRUE.equals(getJsonBoolean(config, "mutualChapEnabled"));
        return chapEnabled + "|" + StringUtils.defaultString(getJsonString(config, "chapUsername"))
                + "|" + mutualChapEnabled + "|" + StringUtils.defaultString(getJsonString(config, "mutualChapUsername"));
    }

    protected ListResponse<StorageServiceRuntimeResponse> listRuntimeOperation(final Long instanceId, final String operation) {
        return listRuntimeOperation(instanceId, operation, "");
    }

    protected ListResponse<StorageServiceRuntimeResponse> listRuntimeOperation(final Long instanceId, final String operation, final String payload) {
        return listRuntimeOperation(instanceId, null, operation, payload);
    }

    protected ListResponse<StorageServiceRuntimeResponse> listRuntimeOperation(final Long instanceId, final Long sharedFileSystemId, final String operation, final String payload) {
        final List<StorageServiceInstanceVO> instances = resolveRuntimeInstances(instanceId, sharedFileSystemId);

        final List<StorageServiceRuntimeResponse> responses = new ArrayList<>();
        for (final StorageServiceInstanceVO instance : instances) {
            responses.add(createRuntimeResponse(instance, operation, payload));
        }
        final ListResponse<StorageServiceRuntimeResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    protected List<StorageServiceInstanceVO> resolveRuntimeInstances(final Long instanceId, final Long sharedFileSystemId) {
        final List<StorageServiceInstanceVO> instances = new ArrayList<>();
        if (instanceId != null) {
            instances.add(requireInstance(instanceId));
            return instances;
        }
        if (sharedFileSystemId != null) {
            final SharedFSVO sharedFS = sharedFSDao.findById(sharedFileSystemId);
            if (sharedFS != null && sharedFS.getVmId() != null) {
                final StorageServiceInstanceVO instance = storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
                if (instance != null && isRuntimeInstanceActive(instance) && canReadStorageInstance(instance)) {
                    instances.add(instance);
                }
            }
            return instances;
        }
        storageServiceInstanceDao.listAll().forEach(instance -> {
            if (isRuntimeInstanceActive(instance) && canReadStorageInstance(instance)) {
                instances.add(instance);
            }
        });
        return instances;
    }

    protected boolean isRuntimeInstanceActive(final StorageServiceInstanceVO instance) {
        if (instance == null || instance.getRemoved() != null || instance.getVmId() == null || instance.getState() == null) {
            return false;
        }
        final String state = instance.getState().name();
        return !"Destroyed".equals(state) && !"Expunging".equals(state) && !"Expunged".equals(state);
    }

    protected StorageServiceRuntimeResponse createRuntimeResponse(final StorageServiceInstanceVO instance, final String operation) {
        return createRuntimeResponse(instance, operation, "");
    }

    protected StorageServiceRuntimeResponse createRuntimeResponse(final StorageServiceInstanceVO instance, final String operation, final String payload) {
        if (instance.getVmId() == null) {
            return createRuntimeResponse(instance, operation, false, "NOT_ATTACHED", "Storage Service instance has no System VM", "{}");
        }
        try {
            final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                    operation, payload == null ? "" : payload, StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
            final String status = extractRuntimeStatus(result);
            if (result.isSuccess() && ("health".equals(operation) || "inventory".equals(operation))) {
                org.apache.cloudstack.storage.sharedfs.query.dao.SharedFSCapacityCache.record(instance.getVmId(),parseJsonObject(normalizeRuntimeResultJson(result.getResultJson())));
            }
            return createRuntimeResponse(instance, operation, result.isSuccess(), status, result.getDetails(), result.getResultJson());
        } catch (final RuntimeException e) {
            logger.warn("Failed to query Storage Service runtime operation [{}] for instance [{}]", operation, instance.getUuid(), e);
            return createRuntimeResponse(instance, operation, false, "ERROR", e.getMessage(), "{}");
        }
    }

    protected StorageServiceRuntimeResponse createRuntimeResponse(final StorageServiceInstanceVO instance, final String operation,
            final boolean success, final String status, final String details, final String resultJson) {
        final StorageServiceRuntimeResponse response = new StorageServiceRuntimeResponse();
        response.setId(instance.getUuid());
        response.setOperation(operation);
        response.setSuccess(success);
        response.setStatus(status);
        response.setDetails(details);
        response.setResultJson(sanitizeRuntimeResultJson(resultJson));
        response.setObjectName("storageserviceruntime");
        return response;
    }

    protected String sanitizeRuntimeResultJson(final String resultJson) {
        if (StringUtils.isBlank(resultJson)) {
            return "{}";
        }
        try {
            final JsonElement parsed = new JsonParser().parse(normalizeRuntimeResultJson(resultJson));
            redactSensitiveRuntimeFields(parsed);
            return RUNTIME_RESULT_GSON.toJson(parsed);
        } catch (final RuntimeException e) {
            logger.warn("Ignoring invalid Storage Service runtime result JSON", e);
            return "{}";
        }
    }

    protected String normalizeRuntimeResultJson(final String resultJson) {
        String normalized = resultJson == null ? "{}" : resultJson;
        while (normalized.contains("\\=")) {
            normalized = normalized.replace("\\=", "=");
        }
        return normalized;
    }

    protected void redactSensitiveRuntimeFields(final JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) {
                redactSensitiveRuntimeFields(child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        final JsonObject object = element.getAsJsonObject();
        final List<String> keysToRemove = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
            final String key = entry.getKey();
            final String lowerKey = key.toLowerCase();
            if ("secrets".equals(lowerKey) || lowerKey.contains("secret") || lowerKey.contains("password") || lowerKey.endsWith("key")) {
                keysToRemove.add(key);
            } else {
                redactSensitiveRuntimeFields(entry.getValue());
            }
        }
        for (final String key : keysToRemove) {
            object.remove(key);
        }
    }

    protected void addStringProperty(final JsonObject object, final String key, final String value) {
        if (StringUtils.isNotBlank(value)) {
            object.addProperty(key, value);
        }
    }

    protected String extractRuntimeStatus(final StorageServiceGuestCommandResult result) {
        final JsonObject json = parseJsonObject(result.getResultJson());
        if (json.has("status")) {
            return json.get("status").getAsString();
        }
        return result.isSuccess() ? "OK" : "ERROR";
    }

    protected StorageServiceInstanceVO requireInstance(final Long id) {
        if (id == null) {
            throw new InvalidParameterValueException("Storage Service instance id is required");
        }
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(id);
        if (instance == null) {
            throw new InvalidParameterValueException("Unable to find Storage Service instance with id " + id);
        }
        storageAccountManager.checkAccess(org.apache.cloudstack.context.CallContext.current().getCallingAccount(),
                org.apache.cloudstack.acl.SecurityChecker.AccessType.UseEntry, false, instance);
        return instance;
    }

    protected StorageFileShareVO requireFileShare(final Long id) {
        if (id == null) {
            throw new InvalidParameterValueException("Storage Service file share id is required");
        }
        final StorageFileShareVO share = storageFileShareDao.findById(id);
        if (share == null || (share.getProtocol() != StorageServiceInstance.Protocol.NFS && share.getProtocol() != StorageServiceInstance.Protocol.SMB)) {
            throw new InvalidParameterValueException("Unable to find Storage Service file share with id " + id);
        }
        return share;
    }

    protected StorageFileShareVO requireNfsExport(final Long id) {
        if (id == null) {
            throw new InvalidParameterValueException("NFS export id is required");
        }
        final StorageFileShareVO share = storageFileShareDao.findById(id);
        if (share == null || share.getProtocol() != StorageServiceInstance.Protocol.NFS) {
            throw new InvalidParameterValueException("Unable to find NFS export with id " + id);
        }
        return share;
    }

    protected StorageFileShareVO requireSmbShare(final Long id) {
        if (id == null) {
            throw new InvalidParameterValueException("SMB share id is required");
        }
        final StorageFileShareVO share = storageFileShareDao.findById(id);
        if (share == null || share.getProtocol() != StorageServiceInstance.Protocol.SMB) {
            throw new InvalidParameterValueException("Unable to find SMB share with id " + id);
        }
        return share;
    }

    protected StorageAccessRuleVO requireAcl(final Long id) {
        if (id == null) {
            throw new InvalidParameterValueException("ACL id is required");
        }
        final StorageAccessRuleVO rule = storageAccessRuleDao.findById(id);
        if (rule == null || rule.getResourceType() != StorageServiceInstance.AccessResourceType.FILE_SHARE) {
            throw new InvalidParameterValueException("Unable to find NFS ACL with id " + id);
        }
        return rule;
    }

    protected StorageAccessRuleVO requireSmbAcl(final Long id) {
        if (id == null) {
            throw new InvalidParameterValueException("SMB ACL id is required");
        }
        final StorageAccessRuleVO rule = storageAccessRuleDao.findById(id);
        if (rule == null || rule.getResourceType() != StorageServiceInstance.AccessResourceType.FILE_SHARE || !isSmbPrincipalType(rule.getPrincipalType())) {
            throw new InvalidParameterValueException("Unable to find SMB ACL with id " + id);
        }
        return rule;
    }

    protected StorageBlockTargetVO requireBlockTarget(final Long id, final StorageServiceInstance.Protocol protocol) {
        if (id == null) {
            throw new InvalidParameterValueException("Block target id is required");
        }
        final StorageBlockTargetVO target = storageBlockTargetDao.findById(id);
        if (target == null || target.getProtocol() != protocol) {
            throw new InvalidParameterValueException("Unable to find " + protocol + " block target with id " + id);
        }
        return target;
    }

    protected StorageBlockTargetVO requireNvmeOfSubsystem(final Long id) {
        final StorageBlockTargetVO target = requireBlockTarget(id, StorageServiceInstance.Protocol.NVME_OF);
        if (!isNvmeOfSubsystem(target)) {
            throw new InvalidParameterValueException("Unable to find NVMe-oF subsystem with id " + id);
        }
        return target;
    }

    protected StorageBlockTargetVO requireNvmeOfNamespace(final Long id) {
        final StorageBlockTargetVO target = requireBlockTarget(id, StorageServiceInstance.Protocol.NVME_OF);
        if (!isNvmeOfNamespace(target)) {
            throw new InvalidParameterValueException("Unable to find NVMe-oF namespace with id " + id);
        }
        return target;
    }

    protected StorageAccessRuleVO requireBlockAcl(final Long id, final StorageServiceInstance.Protocol protocol) {
        if (id == null) {
            throw new InvalidParameterValueException("Block ACL id is required");
        }
        final StorageAccessRuleVO rule = storageAccessRuleDao.findById(id);
        if (rule == null || rule.getResourceType() != StorageServiceInstance.AccessResourceType.BLOCK_TARGET) {
            throw new InvalidParameterValueException("Unable to find block ACL with id " + id);
        }
        requireBlockTarget(rule.getResourceId(), protocol);
        return rule;
    }

    protected List<StorageBlockTargetVO> listBlockTargets(final Long id, final Long instanceId, final StorageServiceInstance.Protocol protocol) {
        final List<StorageBlockTargetVO> targets = new ArrayList<>();
        if (id != null) {
            final StorageBlockTargetVO target = storageBlockTargetDao.findById(id);
            if (target != null && target.getProtocol() == protocol) {
                targets.add(target);
            }
        } else if (instanceId != null) {
            targets.addAll(storageBlockTargetDao.listByInstanceIdAndProtocol(instanceId, protocol));
        } else {
            targets.addAll(storageBlockTargetDao.listByProtocol(protocol));
        }
        targets.removeIf(target -> !canReadStorageInstance(storageServiceInstanceDao.findById(target.getInstanceId())));
        return targets;
    }

    protected ListResponse<StorageAccessRuleResponse> listBlockAcls(final Long id, final Long targetId, final StorageServiceInstance.Protocol protocol) {
        final List<StorageAccessRuleVO> rules = new ArrayList<>();
        if (id != null) {
            final StorageAccessRuleVO rule = storageAccessRuleDao.findById(id);
            if (rule != null) {
                rules.add(rule);
            }
        } else if (targetId != null) {
            final StorageBlockTargetVO target = storageBlockTargetDao.findById(targetId);
            if (target != null && target.getProtocol() == protocol && protocol == StorageServiceInstance.Protocol.ISCSI) {
                for (final StorageBlockTargetVO candidate : listBlockTargetGroup(target)) {
                    rules.addAll(storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, candidate.getId()));
                }
            } else {
                rules.addAll(storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, targetId));
            }
        } else {
            rules.addAll(storageAccessRuleDao.listAll());
        }

        final List<StorageAccessRuleResponse> responses = new ArrayList<>();
        for (final StorageAccessRuleVO rule : rules) {
            if (rule.getResourceType() != StorageServiceInstance.AccessResourceType.BLOCK_TARGET) {
                continue;
            }
            final StorageBlockTargetVO target = storageBlockTargetDao.findById(rule.getResourceId());
            if (target == null || target.getProtocol() != protocol
                    || !canReadStorageInstance(storageServiceInstanceDao.findById(target.getInstanceId()))) {
                continue;
            }
            responses.add(createAclResponse(rule));
        }
        final ListResponse<StorageAccessRuleResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    protected void validateVolume(final Long volumeId) {
        if (volumeId != null) {
            requireVolume(volumeId);
        }
    }

    protected void validateStorageServiceBackingVolume(final StorageServiceInstanceVO instance, final Long volumeId, final String resourceName) {
        if (volumeId == null) {
            return;
        }
        final VolumeVO volume = requireVolume(volumeId);
        final Long attachedVmId = volume.getInstanceId();
        if (attachedVmId != null && !attachedVmId.equals(instance.getVmId())) {
            throw new InvalidParameterValueException(resourceName + " backing volume " + volume.getUuid() +
                    " is attached to another VM. Select the current Storage Service backing volume or attach/import an existing volume " +
                    "into this Storage Service first.");
        }
    }

    protected void validateBackingVolumeUnused(final StorageServiceInstanceVO instance, final Long volumeId) {
        validateBackingVolumeUnused(instance, volumeId, null);
    }

    protected void validateBackingVolumeUnused(final StorageServiceInstanceVO instance, final Long volumeId, final Long excludedBlockTargetId) {
        final List<String> users = new ArrayList<>();
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            if (volumeId.equals(share.getVolumeId())) {
                users.add("NFS export " + share.getName());
            }
        }
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB)) {
            if (volumeId.equals(share.getVolumeId())) {
                users.add("SMB share " + share.getName());
            }
        }
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.ISCSI)) {
            if (volumeId.equals(target.getVolumeId()) && (excludedBlockTargetId == null || target.getId() != excludedBlockTargetId)) {
                users.add("iSCSI target " + target.getTargetName());
            }
        }
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (volumeId.equals(target.getVolumeId()) && (excludedBlockTargetId == null || target.getId() != excludedBlockTargetId)) {
                users.add("NVMe-oF subsystem " + target.getTargetName());
            }
        }
        if (!users.isEmpty()) {
            throw new InvalidParameterValueException("Backing volume is still used by Storage Service resources: " + StringUtils.join(users, ", "));
        }
    }

    protected void validateIscsiBackingVolumeAvailable(final StorageServiceInstanceVO instance, final Long volumeId, final Long excludedBlockTargetId) {
        if (volumeId == null) {
            return;
        }
        validateBackingVolumeUnused(instance, volumeId, excludedBlockTargetId);
    }

    protected void validateNvmeOfBackingVolumeAvailable(final StorageServiceInstanceVO instance, final Long volumeId, final Long excludedBlockTargetId) {
        if (volumeId == null) {
            return;
        }
        validateBackingVolumeUnused(instance, volumeId, excludedBlockTargetId);
    }

    protected String compactVolumeIdentity(final String value) {
        return StringUtils.defaultString(value).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    protected String resolveFileSharePath(final String path, final String name) {
        if (StringUtils.isNotBlank(path)) {
            return path.trim();
        }
        final String trimmedName = StringUtils.defaultIfBlank(name, "share").trim();
        final String safeName = StringUtils.defaultIfBlank(trimmedName.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", ""), "share");
        return SharedFS.SharedFSPath + "/" + safeName;
    }

    protected String resolveNfsExportPath(final String path, final String name) {
        if (StringUtils.isNotBlank(path)) {
            return normalizeFileSharePath(path);
        }
        validateNfsExportName(name);
        return SharedFS.SharedFSPath + "/" + name.trim();
    }

    protected String resolveFileShareBackingPath(final StorageServiceInstanceVO instance, final VolumeVO volume, final String importMode,
            final String relativePath, final String path, final String name) {
        if (StringUtils.isBlank(relativePath)) {
            return resolveFileSharePath(path, name);
        }
        final String safeRelativePath = normalizeRelativeSharePath(relativePath);
        final String mountRoot = resolveFileShareVolumeMountRoot(instance, volume, path);
        return mountRoot + "/" + safeRelativePath;
    }

    protected String resolveFileShareVolumeMountRoot(final StorageServiceInstanceVO instance, final VolumeVO volume, final String path) {
        if (volume != null) {
            final String existingMountRoot = findKnownFileShareVolumeMountRoot(instance, volume.getId());
            if (StringUtils.isNotBlank(existingMountRoot)) {
                return existingMountRoot;
            }
            return "/srv/ablestack-storage/volumes/" + volume.getUuid();
        }
        return resolveFileSharePath(path, "share");
    }

    protected String findKnownFileShareVolumeMountRoot(final StorageServiceInstanceVO instance, final Long volumeId) {
        if (instance == null || volumeId == null) {
            return null;
        }
        final List<StorageFileShareVO> shares = new ArrayList<>();
        shares.addAll(storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS));
        shares.addAll(storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB));
        for (final StorageFileShareVO share : shares) {
            if (!volumeId.equals(share.getVolumeId())) {
                continue;
            }
            final JsonObject config = parseJsonObject(share.getConfigJson());
            final String mountRoot = getJsonString(config, "volumeMountPath");
            if (StringUtils.isNotBlank(mountRoot)) {
                return mountRoot;
            }
            final JsonObject inspection = getJsonObject(config, "lastInspection");
            final String inspectedMountRoot = getJsonString(inspection, "volumeMountPath");
            if (StringUtils.isNotBlank(inspectedMountRoot)) {
                return inspectedMountRoot;
            }
            final String mountPath = getJsonString(inspection, "mountPath");
            if (StringUtils.isNotBlank(mountPath)) {
                return mountPath;
            }
        }
        return null;
    }

    protected void validateNfsExportName(final String name) {
        final String value = StringUtils.trim(name);
        if (StringUtils.isBlank(value)) {
            throw new InvalidParameterValueException("NFS export name is required");
        }
        if (".".equals(value) || "..".equals(value) || value.contains("/") || value.contains("\\") || value.contains(" ")) {
            throw new InvalidParameterValueException("NFS export name must be a valid Linux directory name");
        }
        if (!value.matches("^[A-Za-z0-9._-]+$")) {
            throw new InvalidParameterValueException("NFS export name may contain only letters, numbers, dot, underscore, and hyphen");
        }
    }

    protected void validateNfsExportPath(final String path, final String name) {
        final String normalized = normalizeFileSharePath(path);
        validateFileSharePath(normalized, "NFS export");
        final String expected = SharedFS.SharedFSPath + "/" + StringUtils.trim(name);
        if (!expected.equals(normalized)) {
            throw new InvalidParameterValueException("NFS export internal backing path must be " + expected);
        }
        if (StringUtils.countMatches(normalized.substring(SharedFS.SharedFSPath.length()), "/") != 1) {
            throw new InvalidParameterValueException("NFS export internal backing path must be a direct child of " + SharedFS.SharedFSPath);
        }
    }

    protected void validateSmbShareName(final String name) {
        final String value = StringUtils.trim(name);
        if (StringUtils.isBlank(value)) {
            throw new InvalidParameterValueException("SMB share name is required");
        }
        if (".".equals(value) || "..".equals(value) || value.contains("/") || value.contains("\\") || value.contains(" ")) {
            throw new InvalidParameterValueException("SMB share name must be a valid Linux directory name");
        }
        if (!value.matches("^[A-Za-z0-9._-]+$")) {
            throw new InvalidParameterValueException("SMB share name may contain only letters, numbers, dot, underscore, and hyphen");
        }
    }

    protected String resolveSmbSharePath(final String path, final String name) {
        if (StringUtils.isNotBlank(path)) {
            return normalizeFileSharePath(path);
        }
        validateSmbShareName(name);
        return SharedFS.SharedFSPath + "/" + name.trim();
    }

    protected void validateSmbSharePath(final String path, final String name) {
        final String normalized = normalizeFileSharePath(path);
        validateFileSharePath(normalized, "SMB share");
        if (!normalized.startsWith(SharedFS.SharedFSPath + "/")) {
            throw new InvalidParameterValueException("SMB share internal backing path must be under " + SharedFS.SharedFSPath);
        }
        if (StringUtils.countMatches(normalized.substring(SharedFS.SharedFSPath.length()), "/") != 1) {
            throw new InvalidParameterValueException("SMB share internal backing path must be a direct child of " + SharedFS.SharedFSPath);
        }
        if (StringUtils.isNotBlank(name) && !normalized.equals(SharedFS.SharedFSPath + "/" + name.trim())) {
            throw new InvalidParameterValueException("SMB share internal backing path must be " + SharedFS.SharedFSPath + "/" + name.trim());
        }
    }

    protected String normalizeRelativeSharePath(final String relativePath) {
        final String value = StringUtils.defaultString(relativePath).trim();
        if (value.isEmpty() || value.startsWith("/") || value.contains("\\") || value.contains("\u0000")) {
            throw new InvalidParameterValueException("File share relative path must be a non-empty relative path");
        }
        final String normalized = StringUtils.removeEnd(value.replaceAll("/+", "/"), "/");
        for (final String segment : normalized.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment) || !segment.matches("[A-Za-z0-9._-]+")) {
                throw new InvalidParameterValueException("File share relative path contains an invalid or traversal segment");
            }
        }
        return normalized;
    }

    protected String resolveNestedSharePath(final String path, final String name, final String relativePath,
            final Long volumeId, final boolean nfs) {
        if (relativePath == null) {
            return nfs ? resolveNfsExportPath(path, name) : resolveSmbSharePath(path, name);
        }
        if (volumeId == null) {
            throw new InvalidParameterValueException("An explicit backing volume is required for a relative share path");
        }
        final String computed = SharedFS.SharedFSPath + "/" + normalizeRelativeSharePath(relativePath);
        if (StringUtils.isNotBlank(path) && !computed.equals(normalizeFileSharePath(path))) {
            throw new InvalidParameterValueException("Share path conflicts with the selected volume-relative path");
        }
        return computed;
    }

    protected void validateSharePathForRelativeInput(final String path, final String name, final String relativePath, final boolean nfs) {
        if (relativePath != null) {
            validateFileSharePath(path, "File share");
            return;
        }
        if (nfs) {
            validateNfsExportPath(path, name);
        } else {
            validateSmbSharePath(path, name);
        }
    }

    protected String storeRelativeSharePath(final String configJson, final String relativePath) {
        if (relativePath == null) {
            return configJson;
        }
        final JsonObject config = parseJsonObject(configJson);
        config.addProperty("relativeSharePath", normalizeRelativeSharePath(relativePath));
        // Old observations refer to the previous path and must not override the new desired path.
        config.remove("backingPath");
        config.remove("lastInspection");
        return GSON.toJson(config);
    }

    protected void validateVisibleShareName(final StorageServiceInstanceVO instance, final String name, final Long currentId,
            final StorageServiceInstance.Protocol protocol) {
        for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
            if (!Objects.equals(currentId, share.getId()) && StringUtils.equalsIgnoreCase(StringUtils.trim(name), share.getName())) {
                throw new InvalidParameterValueException("Share name is already exposed by this protocol: " + name);
            }
        }
    }

    protected String physicalRelativeSharePath(final StorageFileShareVO share) {
        final String explicit = getJsonString(parseJsonObject(share.getConfigJson()), "relativeSharePath");
        return StringUtils.isNotBlank(explicit) ? normalizeRelativeSharePath(explicit)
                : StringUtils.removeStart(normalizeFileSharePath(share.getPath()), "/");
    }

    protected void validateNoChildShares(final StorageServiceInstanceVO instance, final StorageFileShareVO parent) {
        final List<StorageFileShareVO> shares = new ArrayList<>();
        shares.addAll(storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS));
        shares.addAll(storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB));
        for (final StorageFileShareVO child : shares) {
            if (!Objects.equals(parent.getId(), child.getId()) && Objects.equals(parent.getVolumeId(), child.getVolumeId())
                    && isSubPath(physicalRelativeSharePath(child), physicalRelativeSharePath(parent))) {
                throw new InvalidParameterValueException("Remove child shares before deleting this parent share: " + child.getUuid());
            }
        }
    }

    protected void validateFileShareFilesystem(final String filesystem, final String importMode) {
        final String mode = StringUtils.defaultString(importMode);
        if (!"FORMAT_EMPTY".equalsIgnoreCase(mode) && !"FORMAT_IF_EMPTY".equalsIgnoreCase(mode)) {
            return;
        }
        final String value = StringUtils.defaultIfBlank(filesystem, "xfs").trim().toLowerCase();
        if (!SUPPORTED_FILE_SHARE_FILESYSTEMS.contains(value)) {
            throw new InvalidParameterValueException("Storage Service backing volume filesystem must be xfs or ext4");
        }
    }

    protected void validateFileSharePath(final String path, final String resourceName) {
        if (StringUtils.isBlank(path)) {
            return;
        }
        final String trimmed = path.trim();
        final String normalized = StringUtils.removeEnd(trimmed, "/");
        if (!trimmed.startsWith("/") || trimmed.contains("/../") || trimmed.endsWith("/..") || trimmed.contains("//")) {
            throw new InvalidParameterValueException(resourceName + " path must be an absolute normalized path without traversal segments");
        }
        if ("/".equals(normalized) || SharedFS.SharedFSPath.equals(normalized)) {
            throw new InvalidParameterValueException(resourceName + " path must be a child directory. The legacy /export root cannot be shared");
        }
    }

    protected void validateFileSharePathAvailable(final StorageServiceInstanceVO instance, final String path, final Long currentShareId,
            final Long requestedVolumeId, final String resourceName) {
        validateFileSharePathAvailable(instance, path, currentShareId, requestedVolumeId, resourceName, false);
    }

    protected void validateFileSharePathAvailable(final StorageServiceInstanceVO instance, final String path, final Long currentShareId,
            final Long requestedVolumeId, final String resourceName, final boolean allowCrossProtocolReuse) {
        validateFileSharePathAvailable(instance, path, currentShareId, requestedVolumeId, resourceName, allowCrossProtocolReuse, null);
    }

    protected void validateFileSharePathAvailable(final StorageServiceInstanceVO instance, final String path, final Long currentShareId,
            final Long requestedVolumeId, final String resourceName, final boolean allowCrossProtocolReuse, final String relativePath) {
        if (StringUtils.isBlank(path)) {
            return;
        }
        final String normalizedPath = relativePath == null ? StringUtils.removeStart(normalizeFileSharePath(path), "/")
                : normalizeRelativeSharePath(relativePath);
        final List<StorageFileShareVO> shares = new ArrayList<>();
        shares.addAll(storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS));
        shares.addAll(storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB));
        for (final StorageFileShareVO existing : shares) {
            if (currentShareId != null && currentShareId.equals(existing.getId())) {
                continue;
            }
            if (StringUtils.isBlank(existing.getPath())) {
                continue;
            }
            final String existingPath = physicalRelativeSharePath(existing);
            if (normalizedPath.equals(existingPath)) {
                if (allowCrossProtocolReuse && existing.getProtocol() != ("NFS export".equals(resourceName)
                        ? StorageServiceInstance.Protocol.NFS : StorageServiceInstance.Protocol.SMB)
                        && requestedVolumeId != null && requestedVolumeId.equals(existing.getVolumeId())) {
                    continue;
                }
                throw new InvalidParameterValueException(resourceName + " path is already used by another Storage Service share: " + path);
            }
            if (requestedVolumeId != null && existing.getVolumeId() != null && !requestedVolumeId.equals(existing.getVolumeId()) &&
                    (isSubPath(normalizedPath, existingPath) || isSubPath(existingPath, normalizedPath))) {
                throw new InvalidParameterValueException(resourceName + " path overlaps another mounted backing-volume path: " + path);
            }
        }
    }

    protected String normalizeFileSharePath(final String path) {
        return StringUtils.removeEnd(StringUtils.defaultString(path).trim(), "/");
    }

    protected boolean isSubPath(final String path, final String parent) {
        return StringUtils.isNotBlank(path) && StringUtils.isNotBlank(parent) && path.startsWith(parent + "/");
    }

    protected VolumeVO requireVolume(final Long volumeId) {
        if (volumeId == null) {
            throw new InvalidParameterValueException("Volume id is required");
        }
        final VolumeVO volume = volumeDao.findById(volumeId);
        if (volume == null) {
            throw new InvalidParameterValueException("Unable to find volume with id " + volumeId);
        }
        return volume;
    }

    protected VolumeVO waitForFileShareVolumeAttachable(final Long volumeId) {
        VolumeVO volume = requireVolume(volumeId);
        for (int attempt = 0; attempt < FILE_SHARE_VOLUME_READY_ATTEMPTS; attempt++) {
            final Volume.State state = volume.getState();
            if (state == Volume.State.Allocated || state == Volume.State.Ready || state == Volume.State.Uploaded) {
                return volume;
            }
            if (state == null || !state.isTransitional()) {
                throw new InvalidParameterValueException(String.format("Volume %s is not attachable. Current state: %s", volume.getUuid(), state));
            }
            try {
                Thread.sleep(FILE_SHARE_VOLUME_READY_INTERVAL_MS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("Interrupted while waiting for Storage Service backing volume to become attachable", e);
            }
            volume = requireVolume(volumeId);
        }
        throw new InvalidParameterValueException(String.format("Volume %s did not reach an attachable state before Storage Service attach", volume.getUuid()));
    }

    protected StorageServiceInstance.Protocol parseProtocol(final String protocol) {
        try {
            return StorageServiceInstance.Protocol.valueOf(protocol.toUpperCase());
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid Storage Service protocol: " + protocol);
        }
    }

    protected Integer normalizeStorageServiceProtocolPort(final StorageServiceInstance.Protocol protocol, final Integer port) {
        final int defaultPort = protocol == StorageServiceInstance.Protocol.NFS ? 2049 : 0;
        final Integer normalizedPort = port == null ? (defaultPort == 0 ? null : defaultPort) : port;
        if (normalizedPort != null && (normalizedPort < 1 || normalizedPort > 65535)) {
            throw new InvalidParameterValueException(String.format("Invalid %s port: %s", protocol, normalizedPort));
        }
        return normalizedPort;
    }

    protected void validateProtocolCanBeDeleted(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocol) {
        if (protocol == StorageServiceInstance.Protocol.NFS &&
                !storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS).isEmpty()) {
            throw new InvalidParameterValueException("Delete NFS exports before disabling the NFS protocol");
        }
        if (protocol == StorageServiceInstance.Protocol.SMB) {
            if (!storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.SMB).isEmpty()) {
                throw new InvalidParameterValueException("Delete SMB shares before disabling the SMB protocol");
            }
            final StorageIdentityDomainVO domain = storageIdentityDomainDao.findByInstanceId(instance.getId());
            if (domain != null && StringUtils.isNotBlank(domain.getDomainName())) {
                throw new InvalidParameterValueException("Leave the SMB AD domain before disabling the SMB protocol");
            }
        }
        if (protocol == StorageServiceInstance.Protocol.ISCSI &&
                !storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.ISCSI).isEmpty()) {
            throw new InvalidParameterValueException("Delete iSCSI targets before disabling the iSCSI protocol");
        }
        if (protocol == StorageServiceInstance.Protocol.NVME_OF &&
                !storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF).isEmpty()) {
            throw new InvalidParameterValueException("Delete NVMe-oF subsystems and namespaces before disabling the NVMe-oF protocol");
        }
    }

    protected List<String> parseNfsPrincipals(final String principal, final String principals) {
        final String rawValues = StringUtils.defaultIfBlank(principals, principal);
        if (StringUtils.isBlank(rawValues)) {
            throw new InvalidParameterValueException("NFS ACL principal is required");
        }
        final List<String> values = new ArrayList<>();
        final HashSet<String> seen = new HashSet<>();
        for (final String rawValue : StringUtils.split(rawValues, ',')) {
            final String value = StringUtils.trim(rawValue);
            if (StringUtils.isBlank(value) || seen.contains(value)) {
                continue;
            }
            if (StringUtils.containsWhitespace(value)) {
                throw new InvalidParameterValueException("NFS ACL principal must not contain whitespace: " + value);
            }
            seen.add(value);
            values.add(value);
        }
        if (values.isEmpty()) {
            throw new InvalidParameterValueException("NFS ACL principal is required");
        }
        return values;
    }

    protected StorageServiceInstance.PrincipalType parseNfsPrincipalType(final String principalType) {
        final String value = StringUtils.isBlank(principalType) ? StorageServiceInstance.PrincipalType.CIDR.name() : principalType.toUpperCase();
        try {
            final StorageServiceInstance.PrincipalType type = StorageServiceInstance.PrincipalType.valueOf(value);
            if (type != StorageServiceInstance.PrincipalType.CIDR && type != StorageServiceInstance.PrincipalType.IP_ADDRESS) {
                throw new InvalidParameterValueException("NFS ACL supports only CIDR or IP_ADDRESS principal types");
            }
            return type;
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid NFS ACL principal type: " + principalType);
        }
    }

    protected StorageServiceInstance.Permission parseNfsPermission(final String permission) {
        try {
            final StorageServiceInstance.Permission value = StorageServiceInstance.Permission.valueOf(permission.toUpperCase());
            if (value != StorageServiceInstance.Permission.READ_ONLY && value != StorageServiceInstance.Permission.READ_WRITE) {
                throw new InvalidParameterValueException("NFS ACL supports only READ_ONLY or READ_WRITE permissions");
            }
            return value;
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid NFS ACL permission: " + permission);
        }
    }

    protected StorageServiceInstance.PrincipalType parseSmbPrincipalType(final String principalType) {
        final String value = StringUtils.isBlank(principalType) ? StorageServiceInstance.PrincipalType.LOCAL_USER.name() : principalType.toUpperCase();
        try {
            final StorageServiceInstance.PrincipalType type = StorageServiceInstance.PrincipalType.valueOf(value);
            if (!isSmbPrincipalType(type)) {
                throw new InvalidParameterValueException("SMB ACL supports LOCAL_USER, LOCAL_GROUP, AD_USER, or AD_GROUP principal types");
            }
            return type;
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid SMB ACL principal type: " + principalType);
        }
    }

    protected boolean isSmbPrincipalType(final StorageServiceInstance.PrincipalType principalType) {
        return principalType == StorageServiceInstance.PrincipalType.LOCAL_USER ||
                principalType == StorageServiceInstance.PrincipalType.LOCAL_GROUP ||
                principalType == StorageServiceInstance.PrincipalType.AD_USER ||
                principalType == StorageServiceInstance.PrincipalType.AD_GROUP;
    }

    protected StorageServiceInstance.Permission parseSmbPermission(final String permission) {
        try {
            StorageServiceInstance.Permission parsed=StorageServiceInstance.Permission.valueOf(permission.toUpperCase());
            if (parsed==StorageServiceInstance.Permission.CONNECT) throw new InvalidParameterValueException("CONNECT is reserved for network allow rules");
            return parsed;
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid SMB ACL permission: " + permission);
        }
    }

    protected StorageServiceInstance.Permission parseBlockPermission(final String permission) {
        final String value = StringUtils.isBlank(permission) ? StorageServiceInstance.Permission.READ_WRITE.name() : permission.toUpperCase();
        try {
            final StorageServiceInstance.Permission parsed = StorageServiceInstance.Permission.valueOf(value);
            if (parsed != StorageServiceInstance.Permission.READ_ONLY && parsed != StorageServiceInstance.Permission.READ_WRITE) {
                throw new InvalidParameterValueException("Block ACL supports only READ_ONLY or READ_WRITE permissions");
            }
            return parsed;
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid block ACL permission: " + permission);
        }
    }

    protected String buildNfsConfigJson(final String currentConfig, final Boolean readOnly, final Boolean rootSquash,
            final Boolean allSquash, final Integer anonUid, final Integer anonGid, final Integer ownerUid, final Integer ownerGid,
            final String mode, final Boolean recursivePermission, final Boolean sync, final Boolean secure, final String endpointMode, final String listenIps, final String listenerPorts,
            final String protocolMode, final boolean applyWritableRootSquashDefaults) {
        final JsonObject config = parseJsonObject(currentConfig);
        if (!config.has("readOnly")) {
            config.addProperty("readOnly", false);
        }
        if (!config.has("rootSquash")) {
            config.addProperty("rootSquash", true);
        }
        if (!config.has("allSquash")) {
            config.addProperty("allSquash", false);
        }
        if (!config.has("sync")) {
            config.addProperty("sync", true);
        }
        if (!config.has("secure")) {
            config.addProperty("secure", true);
        }
        if (readOnly != null) {
            config.addProperty("readOnly", readOnly);
        }
        if (rootSquash != null) {
            config.addProperty("rootSquash", rootSquash);
        }
        if (allSquash != null) {
            config.addProperty("allSquash", allSquash);
        }
        if (anonUid != null) {
            validateNfsNumericPermissionValue("anonuid", anonUid);
            config.addProperty("anonUid", anonUid);
        }
        if (anonGid != null) {
            validateNfsNumericPermissionValue("anongid", anonGid);
            config.addProperty("anonGid", anonGid);
        }
        if (ownerUid != null) {
            validateNfsNumericPermissionValue("owneruid", ownerUid);
            config.addProperty("ownerUid", ownerUid);
        }
        if (ownerGid != null) {
            validateNfsNumericPermissionValue("ownergid", ownerGid);
            config.addProperty("ownerGid", ownerGid);
        }
        if (StringUtils.isNotBlank(mode)) {
            validateNfsMode(mode);
            config.addProperty("mode", mode);
        }
        if (recursivePermission != null) {
            config.addProperty("recursivePermission", recursivePermission);
        }
        if (sync != null) {
            config.addProperty("sync", sync);
        }
        if (secure != null) {
            config.addProperty("secure", secure);
        }
        if (StringUtils.isNotBlank(protocolMode)) {
            config.addProperty("protocolMode", normalizeNfsProtocolMode(protocolMode));
        } else if (!config.has("protocolMode") && !config.has("protocolmode")) {
            config.addProperty("protocolMode", "V4_ONLY");
        }
        if (listenerPorts != null) {
            final JsonArray parsedListenerPorts = parseNfsListenerPorts(listenerPorts);
            if ("V3V4_DUAL".equals(nfsProtocolModeAsString(config, currentConfig))) {
                config.add("listenerGroupPorts", singletonNfsListenerPortArray(2049));
                config.addProperty("endpointMode", "ALL");
            } else {
                config.add("listenerGroupPorts", parsedListenerPorts.size() > 0 ? parsedListenerPorts : singletonNfsListenerPortArray(2049));
                config.addProperty("endpointMode", "LISTENER_GROUP");
            }
            config.remove("listenIps");
        } else if (endpointMode != null || listenIps != null) {
            final JsonArray parsedListenIps = parseNfsListenIps(listenIps);
            final String normalizedEndpointMode = normalizeNfsEndpointMode(endpointMode, parsedListenIps, false);
            config.addProperty("endpointMode", normalizedEndpointMode);
            if ("SELECTED".equals(normalizedEndpointMode)) {
                config.add("listenIps", parsedListenIps);
            } else {
                config.remove("listenIps");
            }
            if (!config.has("listenerGroupPorts")) {
                config.add("listenerGroupPorts", singletonNfsListenerPortArray(2049));
            }
        } else if (!config.has("endpointMode")) {
            config.addProperty("endpointMode", "LISTENER_GROUP");
            config.add("listenerGroupPorts", singletonNfsListenerPortArray(2049));
        }
        applyNfsWritableRootSquashDefaults(config, applyWritableRootSquashDefaults);
        return GSON.toJson(config);
    }

    protected boolean ensureNfsExportListenerGroupPorts(final JsonObject config, final String protocolMode, final Integer defaultPort) {
        boolean changed = false;
        final int fallbackPort = defaultPort == null ? 2049 : defaultPort;
        if ("V3V4_DUAL".equals(protocolMode)) {
            final String currentMode = nfsEndpointModeAsString(config);
            if (!"ALL".equals(currentMode)) {
                config.addProperty("endpointMode", "ALL");
                changed = true;
            }
            if (config.has("listenIps")) {
                config.remove("listenIps");
                changed = true;
            }
            if (!config.has("listenerGroupPorts") || !config.get("listenerGroupPorts").isJsonArray() || config.getAsJsonArray("listenerGroupPorts").size() == 0) {
                config.add("listenerGroupPorts", singletonNfsListenerPortArray(2049));
                changed = true;
            }
            return changed;
        }

        final String currentMode = nfsEndpointModeAsString(config);
        if (!"LISTENER_GROUP".equals(currentMode)) {
            config.addProperty("endpointMode", "LISTENER_GROUP");
            changed = true;
        }
        if (config.has("listenIps")) {
            config.remove("listenIps");
            changed = true;
        }
        if (!config.has("listenerGroupPorts") || !config.get("listenerGroupPorts").isJsonArray() || config.getAsJsonArray("listenerGroupPorts").size() == 0) {
            config.add("listenerGroupPorts", singletonNfsListenerPortArray(fallbackPort));
            changed = true;
        }
        return changed;
    }

    protected String buildProtocolConfigJson(final StorageServiceInstance.Protocol protocol, final String currentConfig, final String protocolMode) {
        return buildProtocolConfigJson(protocol, currentConfig, protocolMode, null, null);
    }

    protected String buildProtocolConfigJson(final StorageServiceInstance.Protocol protocol, final String currentConfig, final String protocolMode, final Integer listenerPort, final String listenIp) {
        final JsonObject config = parseJsonObject(currentConfig);
        if (protocol == StorageServiceInstance.Protocol.NFS) {
            final String mode = StringUtils.isBlank(protocolMode) ? nfsProtocolModeAsString(config, currentConfig) : normalizeNfsProtocolMode(protocolMode);
            config.addProperty("protocolMode", mode);
            if ("V3V4_DUAL".equals(mode)) {
                config.add("listenerGroups", singletonNfsListenerGroupArray(2049));
            } else if (listenerPort != null) {
                addNfsListenerGroup(config, listenerPort);
            } else if (!config.has("listenerGroups")) {
                config.add("listenerGroups", singletonNfsListenerGroupArray(2049));
            }
            addNfsServiceIp(config, listenIp);
        }
        return config.entrySet().isEmpty() ? null : GSON.toJson(config);
    }

    protected JsonArray singletonNfsListenerPortArray(final int port) {
        final JsonArray ports = new JsonArray();
        ports.add(port);
        return ports;
    }

    protected JsonArray singletonNfsListenerGroupArray(final int port) {
        final JsonArray groups = new JsonArray();
        final JsonObject group = new JsonObject();
        group.addProperty("port", port);
        group.addProperty("state", "Ready");
        groups.add(group);
        return groups;
    }

    protected void addNfsListenerGroup(final JsonObject config, final Integer port) {
        if (port == null) {
            return;
        }
        validateNfsListenerPort(port);
        JsonArray groups = config.has("listenerGroups") && config.get("listenerGroups").isJsonArray() ? config.getAsJsonArray("listenerGroups") : new JsonArray();
        for (final JsonElement element : groups) {
            if (element != null && element.isJsonObject()) {
                final JsonElement existingPort = element.getAsJsonObject().get("port");
                if (existingPort != null && existingPort.getAsInt() == port) {
                    return;
                }
            }
        }
        final JsonObject group = new JsonObject();
        group.addProperty("port", port);
        group.addProperty("state", "Ready");
        groups.add(group);
        config.add("listenerGroups", groups);
    }

    protected void addNfsServiceIp(final JsonObject config, final String listenIp) {
        if (StringUtils.isBlank(listenIp) || "0.0.0.0".equals(listenIp) || "::".equals(listenIp)) {
            return;
        }
        JsonArray ips = config.has("serviceIps") && config.get("serviceIps").isJsonArray() ? config.getAsJsonArray("serviceIps") : new JsonArray();
        for (final JsonElement element : ips) {
            if (element != null && !element.isJsonNull() && listenIp.equals(element.getAsString())) {
                return;
            }
        }
        ips.add(listenIp);
        config.add("serviceIps", ips);
    }

    protected void applyNfsWritableRootSquashDefaults(final JsonObject config, final boolean enabled) {
        if (!enabled) {
            return;
        }
        final boolean readOnly = getBoolean(config, "readOnly", false);
        final boolean rootSquash = getBoolean(config, "rootSquash", true);
        if (readOnly || !rootSquash) {
            return;
        }
        if (!config.has("anonUid")) {
            config.addProperty("anonUid", NFS_ANONYMOUS_UID);
        }
        if (!config.has("anonGid")) {
            config.addProperty("anonGid", NFS_ANONYMOUS_GID);
        }
        if (!config.has("ownerUid")) {
            config.addProperty("ownerUid", getInt(config, "anonUid", NFS_ANONYMOUS_UID));
        }
        if (!config.has("ownerGid")) {
            config.addProperty("ownerGid", getInt(config, "anonGid", NFS_ANONYMOUS_GID));
        }
        if (!config.has("mode")) {
            config.addProperty("mode", NFS_WRITABLE_ROOT_SQUASH_MODE);
        }
        if (!config.has("recursivePermission")) {
            config.addProperty("recursivePermission", false);
        }
        if (!config.has("posixPolicy")) {
            config.addProperty("posixPolicy", "ANONYMOUS_WRITE");
        }
    }

    protected boolean getBoolean(final JsonObject object, final String key, final boolean defaultValue) {
        if (object == null || !object.has(key)) {
            return defaultValue;
        }
        final JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() ? value.getAsBoolean() : defaultValue;
    }

    protected int getInt(final JsonObject object, final String key, final int defaultValue) {
        if (object == null || !object.has(key)) {
            return defaultValue;
        }
        final JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() ? value.getAsInt() : defaultValue;
    }

    protected JsonArray parseNfsListenIps(final String listenIps) {
        final JsonArray result = new JsonArray();
        if (StringUtils.isBlank(listenIps)) {
            return result;
        }
        final HashSet<String> seen = new HashSet<>();
        for (final String rawValue : StringUtils.split(listenIps, ',')) {
            final String value = StringUtils.trim(rawValue);
            if (StringUtils.isBlank(value) || seen.contains(value)) {
                continue;
            }
            if (!isValidIpv4Address(value)) {
                throw new InvalidParameterValueException("Invalid NFS export listen IP: " + value);
            }
            seen.add(value);
            result.add(value);
        }
        return result;
    }

    protected void validateNfsListenerPort(final Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new InvalidParameterValueException("Invalid NFS listener group port: " + port);
        }
    }

    protected JsonArray parseNfsListenerPorts(final String listenerPorts) {
        final JsonArray result = new JsonArray();
        if (StringUtils.isBlank(listenerPorts)) {
            return result;
        }
        final HashSet<Integer> seen = new HashSet<>();
        for (final String rawValue : StringUtils.split(listenerPorts, ',')) {
            final String value = StringUtils.trim(rawValue);
            if (StringUtils.isBlank(value)) {
                continue;
            }
            final int port;
            try {
                port = Integer.parseInt(value);
            } catch (final NumberFormatException e) {
                throw new InvalidParameterValueException("Invalid NFS listener group port: " + value);
            }
            validateNfsListenerPort(port);
            if (seen.add(port)) {
                result.add(port);
            }
        }
        return result;
    }

    protected String normalizeNfsEndpointMode(final String endpointMode, final JsonArray listenIps, final boolean legacySelectedWhenBlank) {
        final String value = StringUtils.isBlank(endpointMode) ? null : StringUtils.trim(endpointMode).toUpperCase();
        final boolean hasListenIps = listenIps != null && listenIps.size() > 0;
        if (value == null) {
            return hasListenIps || legacySelectedWhenBlank ? "SELECTED" : "ALL";
        }
        if (!"ALL".equals(value) && !"SELECTED".equals(value) && !"LISTENER_GROUP".equals(value)) {
            throw new InvalidParameterValueException("Invalid NFS export endpoint mode: " + endpointMode);
        }
        if ("SELECTED".equals(value) && !hasListenIps) {
            throw new InvalidParameterValueException("NFS export endpoint mode SELECTED requires at least one listen IP");
        }
        if ("LISTENER_GROUP".equals(value)) {
            return value;
        }
        return value;
    }

    protected boolean removeListenIpFromConfig(final JsonObject config, final String listenIp) {
        if (config == null || !config.has("listenIps") || !config.get("listenIps").isJsonArray()) {
            return false;
        }
        final JsonArray next = new JsonArray();
        boolean removed = false;
        for (final JsonElement element : config.getAsJsonArray("listenIps")) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            final String value = StringUtils.trim(element.getAsString());
            if (StringUtils.equals(value, listenIp)) {
                removed = true;
                continue;
            }
            if (StringUtils.isNotBlank(value)) {
                next.add(value);
            }
        }
        if (removed) {
            config.add("listenIps", next);
        }
        return removed;
    }

    protected String nfsListenIpsAsString(final JsonObject config) {
        if (config == null || !config.has("listenIps") || !config.get("listenIps").isJsonArray()) {
            return null;
        }
        final List<String> values = new ArrayList<>();
        for (final JsonElement element : config.getAsJsonArray("listenIps")) {
            if (element != null && !element.isJsonNull() && StringUtils.isNotBlank(element.getAsString())) {
                values.add(element.getAsString());
            }
        }
        return values.isEmpty() ? null : StringUtils.join(values, ',');
    }

    protected String nfsListenerPortsAsString(final JsonObject config) {
        if (config == null || !config.has("listenerGroupPorts") || !config.get("listenerGroupPorts").isJsonArray()) {
            return null;
        }
        final List<String> values = new ArrayList<>();
        for (final JsonElement element : config.getAsJsonArray("listenerGroupPorts")) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            final String value = StringUtils.trim(element.getAsString());
            if (StringUtils.isNotBlank(value)) {
                values.add(value);
            }
        }
        return values.isEmpty() ? null : StringUtils.join(values, ',');
    }

    protected String nfsEndpointModeAsString(final JsonObject config) {
        return nfsEndpointModeAsString(config, null);
    }

    protected String nfsEndpointModeAsString(final JsonObject config, final String rawConfig) {
        if (config == null) {
            return nfsEndpointModeFromRawConfig(rawConfig);
        }
        final JsonElement endpointMode = config.get("endpointMode") == null ? config.get("endpointmode") : config.get("endpointMode");
        if (endpointMode != null && !endpointMode.isJsonNull() && StringUtils.isNotBlank(endpointMode.getAsString())) {
            final String value = StringUtils.trim(endpointMode.getAsString()).toUpperCase();
            if ("SELECTED".equals(value) || "ALL".equals(value) || "LISTENER_GROUP".equals(value)) {
                return value;
            }
        }
        final String rawEndpointMode = nfsEndpointModeFromRawConfig(rawConfig);
        if (StringUtils.isNotBlank(rawEndpointMode)) {
            return rawEndpointMode;
        }
        return StringUtils.isNotBlank(nfsListenIpsAsString(config)) ? "SELECTED" : "ALL";
    }

    protected String explicitNfsProtocolModeAsString(final JsonObject config, final String rawConfig) {
        if (config != null) {
            final JsonElement protocolMode = config.get("protocolMode") == null ? config.get("protocolmode") : config.get("protocolMode");
            if (protocolMode != null && !protocolMode.isJsonNull() && StringUtils.isNotBlank(protocolMode.getAsString())) {
                return normalizeNfsProtocolMode(protocolMode.getAsString());
            }
        }
        return nfsProtocolModeFromRawConfig(rawConfig);
    }

    protected String nfsProtocolModeAsString(final JsonObject config, final String rawConfig) {
        final String explicitProtocolMode = explicitNfsProtocolModeAsString(config, rawConfig);
        return StringUtils.isBlank(explicitProtocolMode) ? "V4_ONLY" : explicitProtocolMode;
    }

    protected String nfsProtocolModeFromRawConfig(final String rawConfig) {
        if (StringUtils.isBlank(rawConfig)) {
            return null;
        }
        final Matcher matcher = Pattern.compile("\\\"protocol[Mm]ode\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(rawConfig);
        return matcher.find() ? normalizeNfsProtocolMode(matcher.group(1)) : null;
    }

    protected String normalizeNfsProtocolMode(final String protocolMode) {
        final String normalized = StringUtils.defaultString(protocolMode, "V4_ONLY").trim().toUpperCase();
        if ("V3V4_DUAL".equals(normalized) || "V4_ONLY".equals(normalized)) {
            return normalized;
        }
        throw new InvalidParameterValueException("Unsupported NFS protocol mode: " + protocolMode);
    }

    protected boolean isEndpointProtocol(final StorageServiceInstance.Protocol protocol) {
        return protocol == StorageServiceInstance.Protocol.NFS ||
                protocol == StorageServiceInstance.Protocol.SMB ||
                protocol == StorageServiceInstance.Protocol.ISCSI ||
                protocol == StorageServiceInstance.Protocol.NVME_OF;
    }

    protected StorageServiceProtocolVO findNfsProtocolEndpoint(final long instanceId, final String listenIp, final Integer port) {
        return findProtocolEndpoint(instanceId, StorageServiceInstance.Protocol.NFS, listenIp, port);
    }

    protected StorageServiceProtocolVO findProtocolEndpoint(final long instanceId, final StorageServiceInstance.Protocol protocolType, final String listenIp, final Integer port) {
        final Integer normalizedPort = port == null ? defaultProtocolPort(protocolType) : port;
        for (final StorageServiceProtocolVO protocol : storageServiceProtocolDao.listByInstanceIdAndProtocol(instanceId, protocolType)) {
            final Integer existingPort = protocol.getPort() == null ? defaultProtocolPort(protocolType) : protocol.getPort();
            if (!normalizedPort.equals(existingPort)) {
                continue;
            }
            final String existingIp = StringUtils.defaultIfBlank(StringUtils.trimToNull(protocol.getListenIp()), "0.0.0.0");
            final String requestedIp = StringUtils.defaultIfBlank(StringUtils.trimToNull(listenIp), "0.0.0.0");
            if (StringUtils.equals(existingIp, requestedIp)) {
                return protocol;
            }
        }
        return null;
    }

    protected void validateBlockProtocolListenerConflict(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocol,
            final String listenIp, final Integer port, final StorageServiceProtocolVO currentProtocol) {
        if (protocol != StorageServiceInstance.Protocol.ISCSI && protocol != StorageServiceInstance.Protocol.NVME_OF) {
            return;
        }
        final int requestedPort = port == null ? defaultProtocolPort(protocol) : port;
        final String requestedIp = normalizeListenIp(listenIp);
        final boolean requestedWildcard = isWildcardListenIp(requestedIp);
        for (final StorageServiceProtocolVO existing : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
            if (existing == null || !existing.isEnabled()) {
                continue;
            }
            if (currentProtocol != null && existing.getId() == currentProtocol.getId()) {
                continue;
            }
            final int existingPort = existing.getPort() == null ? defaultProtocolPort(protocol) : existing.getPort();
            if (existingPort != requestedPort) {
                continue;
            }
            final String existingIp = normalizeListenIp(existing.getListenIp());
            if (StringUtils.equals(existingIp, requestedIp)) {
                continue;
            }
            final boolean existingWildcard = isWildcardListenIp(existingIp);
            if (requestedWildcard || existingWildcard) {
                final String protocolName = protocol == StorageServiceInstance.Protocol.NVME_OF ? "NVMe-oF" : "iSCSI";
                if (requestedWildcard) {
                    throw new InvalidParameterValueException(String.format(
                            "%s listener %s:%d cannot be added because specific listener %s:%d already exists. Delete or change the specific listener first.",
                            protocolName, requestedIp, requestedPort, existingIp, existingPort));
                }
                if (protocol == StorageServiceInstance.Protocol.NVME_OF && existingWildcard) {
                    continue;
                }
                throw new InvalidParameterValueException(String.format(
                        "%s listener %s:%d is already covered by wildcard listener %s:%d. Use the existing listener port group instead of adding a duplicate IP listener.",
                        protocolName, requestedIp, requestedPort, existingIp, existingPort));
            }
        }
    }

    protected String normalizeListenIp(final String listenIp) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(listenIp), "0.0.0.0");
    }

    protected boolean isWildcardListenIp(final String listenIp) {
        return StringUtils.isBlank(listenIp) || "0.0.0.0".equals(listenIp) || "::".equals(listenIp);
    }

    protected int defaultProtocolPort(final StorageServiceInstance.Protocol protocol) {
        if (protocol == StorageServiceInstance.Protocol.SMB) {
            return 445;
        }
        if (protocol == StorageServiceInstance.Protocol.ISCSI) {
            return 3260;
        }
        if (protocol == StorageServiceInstance.Protocol.NVME_OF) {
            return 4420;
        }
        return 2049;
    }

    protected StorageServiceProtocolVO selectNfsModeProtocol(final List<StorageServiceProtocolVO> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }
        StorageServiceProtocolVO fallback = null;
        for (final StorageServiceProtocolVO protocol : protocols) {
            if (protocol == null) {
                continue;
            }
            if (fallback == null) {
                fallback = protocol;
            }
            final JsonObject config = parseJsonObject(protocol.getConfigJson());
            if (StringUtils.isNotBlank(explicitNfsProtocolModeAsString(config, protocol.getConfigJson()))) {
                return protocol;
            }
        }
        return fallback;
    }

    protected StorageServiceProtocolVO selectNfsDefaultProtocol(final List<StorageServiceProtocolVO> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            return null;
        }
        StorageServiceProtocolVO fallback = null;
        for (final StorageServiceProtocolVO protocol : protocols) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            if (fallback == null) {
                fallback = protocol;
            }
            if (protocol.getPort() == null || protocol.getPort() == 2049) {
                return protocol;
            }
        }
        return fallback;
    }

    protected String resolveNfsServiceProtocolMode(final StorageServiceInstanceVO instance) {
        final StorageServiceProtocolVO protocol = selectNfsModeProtocol(storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS));
        if (protocol == null) {
            return "V4_ONLY";
        }
        return nfsProtocolModeAsString(parseJsonObject(protocol.getConfigJson()), protocol.getConfigJson());
    }

    protected String resolveProtocolModeForEnable(final StorageServiceInstance.Protocol protocol, final StorageServiceProtocolVO protocolVO, final String requestedMode) {
        if (protocol != StorageServiceInstance.Protocol.NFS) {
            return null;
        }
        final String existingMode = protocolVO == null ? null : explicitNfsProtocolModeAsString(parseJsonObject(protocolVO.getConfigJson()), protocolVO.getConfigJson());
        final String normalizedRequestedMode = StringUtils.isBlank(requestedMode) ? (StringUtils.isBlank(existingMode) ? "V4_ONLY" : existingMode) : normalizeNfsProtocolMode(requestedMode);
        if (StringUtils.isNotBlank(existingMode) && !existingMode.equals(normalizedRequestedMode)) {
            throw new InvalidParameterValueException("NFS protocol mode is fixed when the Storage Service is created. Existing mode is " + existingMode);
        }
        return normalizedRequestedMode;
    }

    protected void validateProtocolModeEndpointPolicy(final StorageServiceInstance.Protocol protocol, final StorageServiceProtocolVO protocolVO,
            final String protocolMode, final String listenIp, final Integer port) {
        if (protocol != StorageServiceInstance.Protocol.NFS || !"V3V4_DUAL".equals(protocolMode)) {
            return;
        }
        if (port != null && port != 2049) {
            throw new InvalidParameterValueException("NFSv3 + NFSv4 dual mode uses the service-wide NFS port 2049");
        }
        // Dual mode keeps NFS mode and port service-wide, but additional service IPs are allowed.
        // The extra IP is registered on the System VM and does not create a separate Ganesha endpoint.
    }

    protected void validateNfsRequestedProtocolMode(final String requestedMode, final String serviceMode) {
        if (StringUtils.isBlank(requestedMode)) {
            return;
        }
        final String normalizedRequestedMode = normalizeNfsProtocolMode(requestedMode);
        if (!normalizedRequestedMode.equals(serviceMode)) {
            throw new InvalidParameterValueException("NFS protocol mode is fixed when the Storage Service is created. Existing mode is " + serviceMode);
        }
    }

    protected void validateNfsEndpointPolicyForMode(final String protocolMode, final String endpointMode, final String listenIps, final String listenerPorts) {
        if ("V3V4_DUAL".equals(protocolMode)) {
            final JsonArray ports = parseNfsListenerPorts(listenerPorts);
            if ("ALL".equalsIgnoreCase(StringUtils.defaultString(endpointMode)) || StringUtils.isNotBlank(listenIps) || ports.size() > 0 && !(ports.size() == 1 && ports.get(0).getAsInt() == 2049)) {
                throw new InvalidParameterValueException("NFSv3 + NFSv4 dual mode exposes all NFS exports on the service-wide port 2049. Per-export endpoint or listener group selection is not supported.");
            }
            return;
        }
        if (StringUtils.isNotBlank(listenIps)) {
            throw new InvalidParameterValueException("NFSv4-only exports are assigned to listener group ports, not individual listen IPs.");
        }
        parseNfsListenerPorts(listenerPorts);
    }

    protected void validateNfsListenerPortsExist(final StorageServiceInstanceVO instance, final String protocolMode, final String listenerPorts) {
        if ("V3V4_DUAL".equals(protocolMode) || StringUtils.isBlank(listenerPorts)) {
            return;
        }
        final JsonArray requestedPorts = parseNfsListenerPorts(listenerPorts);
        if (requestedPorts.size() == 0) {
            throw new InvalidParameterValueException("NFSv4-only exports require at least one listener port group.");
        }
        final HashSet<Integer> enabledPorts = new HashSet<>();
        for (final StorageServiceProtocolVO protocol : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            enabledPorts.add(protocol.getPort() == null ? 2049 : protocol.getPort());
        }
        if (enabledPorts.isEmpty()) {
            enabledPorts.add(2049);
        }
        for (final JsonElement element : requestedPorts) {
            final int port = element.getAsInt();
            if (!enabledPorts.contains(port)) {
                throw new InvalidParameterValueException("NFS listener port group is not enabled for this Storage Service: " + port);
            }
        }
    }

    protected String nfsSelectedListenIpsAsString(final JsonObject config) {
        return nfsSelectedListenIpsAsString(config, null);
    }

    protected String nfsSelectedListenIpsAsString(final JsonObject config, final String rawConfig) {
        if (!"SELECTED".equals(nfsEndpointModeAsString(config, rawConfig))) {
            return null;
        }
        final String value = nfsListenIpsAsString(config);
        return StringUtils.isNotBlank(value) ? value : nfsListenIpsFromRawConfig(rawConfig);
    }

    protected String nfsEndpointModeFromRawConfig(final String rawConfig) {
        if (StringUtils.isBlank(rawConfig)) {
            return "ALL";
        }
        final Matcher matcher = NFS_ENDPOINT_MODE_PATTERN.matcher(rawConfig);
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        return StringUtils.isNotBlank(nfsListenIpsFromRawConfig(rawConfig)) ? "SELECTED" : "ALL";
    }

    protected String nfsListenIpsFromRawConfig(final String rawConfig) {
        if (StringUtils.isBlank(rawConfig)) {
            return null;
        }
        final String lower = rawConfig.toLowerCase();
        final int keyIndex = lower.indexOf("\"listenips\"");
        if (keyIndex < 0) {
            return null;
        }
        final int arrayStart = rawConfig.indexOf('[', keyIndex);
        if (arrayStart < 0) {
            return null;
        }
        final int arrayEnd = rawConfig.indexOf(']', arrayStart);
        final String candidate = arrayEnd > arrayStart ? rawConfig.substring(arrayStart, arrayEnd + 1) : rawConfig.substring(arrayStart);
        final List<String> values = new ArrayList<>();
        final HashSet<String> seen = new HashSet<>();
        final Matcher matcher = IPV4_ADDRESS_PATTERN.matcher(candidate);
        while (matcher.find()) {
            final String value = matcher.group();
            if (seen.add(value)) {
                values.add(value);
            }
        }
        return values.isEmpty() ? null : StringUtils.join(values, ',');
    }

    protected void removeSecondaryListenAddress(final StorageServiceInstanceVO instance, final String listenIp) {
        if (instance.getVmId() == null) {
            return;
        }
        for (final NicVO nic : nicDao.listByVmId(instance.getVmId())) {
            final NicSecondaryIpVO secondaryIp = nicSecondaryIpDao.findByIp4AddressAndNicId(listenIp, nic.getId());
            if (secondaryIp != null) {
                nicSecondaryIpDao.remove(secondaryIp.getId());
                logger.info("Removed Storage Service listen IP [{}] from NIC [{}] for instance [{}]", listenIp, nic.getUuid(), instance.getUuid());
                return;
            }
        }
    }

    protected void validateSmbOwnershipAccountAcl(final JsonObject config, final StorageServiceInstance.Permission permission) {
        if (permission == StorageServiceInstance.Permission.ADMIN && ("INHERIT_PARENT_OWNER".equals(getJsonString(config, "ownershipInheritance"))
                || "FORCED_UID_GID".equals(getJsonString(config, "posixOwnershipMode")))) {
            throw new InvalidParameterValueException("ADMIN account ACL cannot override managed POSIX ownership");
        }
    }

    protected void validateSmbOwnershipExistingAcls(final StorageFileShareVO share, final JsonObject config) {
        if (!"INHERIT_PARENT_OWNER".equals(getJsonString(config, "ownershipInheritance")) && !"FORCED_UID_GID".equals(getJsonString(config, "posixOwnershipMode"))) return;
        for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
            if (isSmbPrincipalType(rule.getPrincipalType()) && rule.getState() != StorageServiceInstance.ResourceState.Disabled
                    && rule.getState() != StorageServiceInstance.ResourceState.Destroyed && rule.getState() != StorageServiceInstance.ResourceState.Error) {
                validateSmbOwnershipAccountAcl(config, rule.getPermission());
            }
        }
    }

    protected void preflightSmbCreationPolicy(final StorageServiceInstanceVO instance, final StorageFileShareVO share, final JsonObject config) {
        if (instance.getVmId() == null || (!"FORCED_UID_GID".equals(getJsonString(config, "posixOwnershipMode"))
                && Integer.parseInt(config.get("forceCreateMode").getAsString(), 8) == 0
                && Integer.parseInt(config.get("forceDirectoryMode").getAsString(), 8) == 0)) return;
        final JsonObject payload = new JsonObject();final JsonArray shares = new JsonArray();final JsonObject item = new JsonObject();
        item.addProperty("uuid", share.getUuid());item.addProperty("path", resolveSmbRuntimeBackingPath(instance, share));item.add("config", config);
        final JsonArray acls = new JsonArray();
        for (StorageAccessRuleVO rule : share.getId() == 0 ? Collections.<StorageAccessRuleVO>emptyList() : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
            JsonObject acl = new JsonObject();acl.addProperty("permission", rule.getPermission().name());acl.addProperty("state", rule.getState().name());acls.add(acl);
        }
        item.add("acls", acls);shares.add(item);payload.add("shares", shares);
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "smb share preflight", GSON.toJson(payload), 30, Collections.emptySet()));
        if (!result.isSuccess()) throw new InvalidParameterValueException("SMB creation permissions failed POSIX ACL preflight: " + result.getDetails());
    }

    protected void restoreNativePosixOperation(final org.apache.cloudstack.api.BaseCmd cmd, final StorageServiceInstanceVO instance) {
        restoreNativePosixOperation(cmd, instance, true);
    }
    protected void restoreNativePosixOperation(final org.apache.cloudstack.api.BaseCmd cmd, final StorageServiceInstanceVO instance, final boolean includeIdentity) {
        final StorageServiceOperationVO operation = storageWriterOperation.get();
        if (operation != null && operation.getPreviousSnapshotJson() != null) {
            final JsonObject snapshot = parseJsonObject(operation.getPreviousSnapshotJson());
            if (includeIdentity && snapshot.has("nativeIdentityCapsule")) restoreConfigurationIdentity(instance, snapshot.getAsJsonObject("nativeIdentityCapsule"));
            if (snapshot.has("nativePosixDirectories")) for (JsonElement item : snapshot.getAsJsonArray("nativePosixDirectories")) {
                dispatchPosixDirectoryCommand(instance, "restore", item.getAsJsonObject());
            }
            if (snapshot.has("nativePosixDirectory")) dispatchPosixDirectoryCommand(instance, "restore", snapshot.getAsJsonObject("nativePosixDirectory"));
        }
    }

    protected StoragePosixDirectoryPolicyVO requirePosixDirectoryPolicy(final Long id) {
        final StoragePosixDirectoryPolicyVO policy = id == null ? null : storagePosixPolicyDao.findById(id);
        if (policy == null) throw new InvalidParameterValueException("POSIX directory policy is unavailable");
        requireInstance(policy.getInstanceId());
        return policy;
    }

    @Override
    public ListResponse<org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse> listStoragePosixDirectoryPolicies(
            final org.apache.cloudstack.api.command.user.storage.dataservice.ListStoragePosixDirectoryPoliciesCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        if (!canReadStorageInstance(instance)) throw new com.cloud.exception.PermissionDeniedException("Directory policy access denied");
        final List<org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse> responses = new ArrayList<>();
        JsonObject observations = new JsonObject();
        if (instance.getVmId() != null) {
            try {
                final StorageServiceGuestCommandResult runtime = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                        "inventory", "", 15, Collections.emptySet()));
                final JsonObject inventory = parseJsonObject(normalizeRuntimeResultJson(runtime.getResultJson()));
                if (runtime.isSuccess() && inventory.has("posixDirectoryPolicies")) observations = inventory.getAsJsonObject("posixDirectoryPolicies");
            } catch (RuntimeException unavailable) { logger.debug("POSIX policy runtime observation unavailable for instance {}", instance.getUuid()); }
        }
        for (StoragePosixDirectoryPolicyVO policy : storagePosixPolicyDao.listByInstance(instance.getId())) {
            final JsonObject effective = observations.has(policy.getUuid()) && observations.get(policy.getUuid()).isJsonObject()
                    ? observations.getAsJsonObject(policy.getUuid()) : parseJsonObject(policy.getEffectiveJson());
            if (!observations.has(policy.getUuid())) effective.addProperty("driftStatus", "UNOBSERVED");
            responses.add(createPosixPolicyResponse(instance, policy, effective));
        }
        final ListResponse<org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse> result = new ListResponse<>();
        result.setResponses(responses, responses.size());return result;
    }

    @Override
    public org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse executeStoragePosixDirectoryPolicy(
            final org.apache.cloudstack.api.command.user.storage.dataservice.BaseStoragePosixDirectoryPolicyCmd cmd) {
        if (Boolean.TRUE.equals(cmd.getPreview())) return doExecuteStoragePosixDirectoryPolicy(cmd);
        return executeDesiredChange(cmd, org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse.class,
                () -> doExecuteStoragePosixDirectoryPolicy(cmd));
    }

    private org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse doExecuteStoragePosixDirectoryPolicy(
            final org.apache.cloudstack.api.command.user.storage.dataservice.BaseStoragePosixDirectoryPolicyCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(getStorageServiceSyncId(cmd));
        final StoragePosixDirectoryPolicyVO current = cmd.getId() == null ? null : requirePosixDirectoryPolicy(cmd.getId());
        if (current != null && current.getInstanceId() != instance.getId()) throw new InvalidParameterValueException("Policy instance does not match the writer scope");
        final String action = cmd.getPolicyAction();
        if (!"CREATE".equals(action) && current == null) throw new InvalidParameterValueException("Directory policy ID is required");
        if (Boolean.TRUE.equals(cmd.getRecursive())) throw new InvalidParameterValueException("Recursive POSIX changes are unsupported; existing child data is preserved");
        if (current != null && cmd.getExpectedPolicyRevision() != null && cmd.getExpectedPolicyRevision() != current.getRevision()) {
            throw new InvalidParameterValueException("The common directory policy revision changed; refresh its impact preview");
        }
        final Long volumeId = current == null ? cmd.getVolumeId() : current.getVolumeId();
        if (volumeId == null) throw new InvalidParameterValueException("Backing volume is required");
        validateStorageServiceBackingVolume(instance, volumeId, "POSIX directory policy");
        final VolumeVO volume = requireVolume(volumeId);
        final String relative = current == null ? PosixDirectoryPolicy.relativePath(cmd.getRelativePath()) : current.getRelativePath();
        if (current != null && (cmd.getVolumeId() != null && !cmd.getVolumeId().equals(volumeId)
                || cmd.getRelativePath() != null && !cmd.getRelativePath().equals(relative))) {
            throw new InvalidParameterValueException("Directory policy path identity is immutable");
        }
        final JsonObject config = current == null ? new JsonObject() : parseJsonObjectStrict(current.getConfigJson(), "POSIX directory policy");
        if (cmd.getOwnerUid() != null) { PosixDirectoryPolicy.numericId(cmd.getOwnerUid());config.addProperty("ownerUid", cmd.getOwnerUid()); }
        if (cmd.getOwnerGid() != null) { PosixDirectoryPolicy.numericId(cmd.getOwnerGid());config.addProperty("ownerGid", cmd.getOwnerGid()); }
        if (cmd.getApplyOwner() != null || !config.has("applyOwner")) config.addProperty("applyOwner", Boolean.TRUE.equals(cmd.getApplyOwner()));
        if (Boolean.TRUE.equals(getJsonBoolean(config, "applyOwner")) && (!config.has("ownerUid") || !config.has("ownerGid"))) {
            throw new InvalidParameterValueException("Explicit owner application requires both owneruid and ownergid");
        }
        config.addProperty("directoryMode", PosixDirectoryPolicy.directoryMode(cmd.getDirectoryMode() == null
                ? StringUtils.defaultIfBlank(getJsonString(config, "directoryMode"), "0770") : cmd.getDirectoryMode()));
        config.addProperty("recursive", false);
        for (String key : new String[] {"accessEntries", "defaultEntries"}) {
            final String input = "accessEntries".equals(key) ? cmd.getAccessEntries() : cmd.getDefaultEntries();
            if (input != null) {
                try { config.add(key, PosixDirectoryPolicy.aclEntries(new JsonParser().parse(input).getAsJsonArray())); }
                catch (IllegalStateException | com.google.gson.JsonParseException invalid) { throw new InvalidParameterValueException("POSIX ACL entries must be a structured JSON array"); }
            } else if (!config.has(key)) config.add(key, new JsonArray());
        }
        StoragePosixDirectoryPolicyVO policy = current == null ? new StoragePosixDirectoryPolicyVO() : current;
        if (current == null) {
            policy.setInstanceId(instance.getId());policy.setVolumeId(volumeId);policy.setRelativePath(relative);
            policy.setPathKey(PosixDirectoryPolicy.pathKey(volume.getUuid(), relative));policy.setRevision(1);policy.setState("Allocated");
            if (storagePosixPolicyDao.findByPath(instance.getId(), policy.getPathKey()) != null) {
                throw new InvalidParameterValueException("The canonical directory already has a common POSIX policy; edit that policy");
            }
        }
        policy.setConfigJson(GSON.toJson(config));
        final JsonObject request = posixPolicyPayload(instance, policy);
        final JsonObject before = dispatchPosixDirectoryCommand(instance, "inspect", request);
        if (current == null && cmd.getDirectoryMode() == null) {
            config.add("directoryMode", before.get("effectiveMode"));policy.setConfigJson(GSON.toJson(config));
        }
        for (StorageFileShareVO affected : matchingPosixShares(instance, policy)) {
            if (affected.getPosixPolicyId() != null && !Long.valueOf(policy.getId()).equals(affected.getPosixPolicyId())) {
                throw new InvalidParameterValueException("The directory is referenced by another POSIX policy");
            }
        }
        if (Boolean.TRUE.equals(cmd.getPreview())) return createPosixPolicyResponse(instance, policy, before);
        final StorageServiceOperationVO operation = storageWriterOperation.get();
        if (operation != null) {
            final JsonObject previous = parseJsonObject(operation.getPreviousSnapshotJson());
            if (configurationBatch.get() != null) {
                final JsonArray directories = previous.has("nativePosixDirectories") ? previous.getAsJsonArray("nativePosixDirectories") : new JsonArray();
                boolean captured = false;
                for (JsonElement item : directories) {
                    if (getJsonString(before, "canonicalPath").equals(getJsonString(item.getAsJsonObject(), "canonicalPath"))) captured = true;
                }
                if (!captured) directories.add(before);previous.add("nativePosixDirectories", directories);
            } else previous.add("nativePosixDirectory", before);
            operation.setPreviousSnapshotJson(previous.toString());storageOperationDao.update(operation.getId(), operation);
        }
        if ("DELETE".equals(action)) {
            final List<StorageFileShareVO> shares = posixPolicyShares(instance, policy);
            if (!shares.isEmpty()) throw new InvalidParameterValueException("Directory policy is still referenced by NFS/SMB shares; unlink or replace it first");
            storagePosixPolicyDao.remove(policy.getId());
            policy.setState("Deleted");
            dispatchPosixDirectoryCommand(instance, "forget", request);
            return createPosixPolicyResponse(instance, policy, before);
        }
        if (current == null) policy = storagePosixPolicyDao.persist(policy);
        else { policy.setRevision(policy.getRevision() + 1);storagePosixPolicyDao.update(policy.getId(), policy); }
        policy.setState("Updating");storagePosixPolicyDao.update(policy.getId(), policy);
        try {
            final JsonObject effective = dispatchPosixDirectoryCommand(instance, "apply", posixPolicyPayload(instance, policy));
            policy.setEffectiveJson(GSON.toJson(effective));policy.setLastApplied(new java.util.Date());policy.setState("Ready");
            storagePosixPolicyDao.update(policy.getId(), policy);
            bindMatchingPosixShares(instance, policy, effective);
            return createPosixPolicyResponse(instance, policy, effective);
        } catch (RuntimeException failure) {
            try { dispatchPosixDirectoryCommand(instance, "restore", before); }
            catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        }
    }


    protected JsonObject posixPolicyPayload(final StorageServiceInstanceVO instance, final StoragePosixDirectoryPolicyVO policy) {
        final VolumeVO volume = requireVolume(policy.getVolumeId());
        final JsonObject payload = new JsonObject();payload.addProperty("instanceUuid", instance.getUuid());
        payload.addProperty("uuid", policy.getUuid());payload.addProperty("volumeUuid", volume.getUuid());
        payload.addProperty("volumeMountPath", resolveFileShareVolumeMountRoot(instance, volume, null));
        payload.addProperty("relativePath", policy.getRelativePath());payload.addProperty("revision", policy.getRevision());
        payload.add("config", parseJsonObjectStrict(policy.getConfigJson(), "POSIX directory policy"));return payload;
    }

    protected JsonObject dispatchPosixDirectoryCommand(final StorageServiceInstanceVO instance, final String action, final JsonObject payload) {
        if (instance.getVmId() == null) throw new InvalidParameterValueException("Directory policy preview/application requires a running Storage Service VM");
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "posix directory " + action, GSON.toJson(payload), 60, Collections.emptySet()));
        if (!result.isSuccess()) {
            if ("inspect".equals(action)) throw new InvalidParameterValueException("POSIX directory preflight failed: " + result.getDetails());
            throw new CloudRuntimeException("POSIX directory " + action + " failed: " + result.getDetails());
        }
        final JsonObject observed = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
        if (!Boolean.TRUE.equals(getJsonBoolean(observed, "success"))) throw new CloudRuntimeException("POSIX directory runtime did not confirm " + action);
        return observed;
    }

    protected void inheritPosixDirectoryPolicy(final StorageServiceInstanceVO instance, final StorageFileShareVO share,
            final Long requestedPolicyId, final Integer ownerUid, final Integer ownerGid, final String requestedMode) {
        final boolean fixedIdentity = "FORCED_UID_GID".equals(getJsonString(parseJsonObject(share.getConfigJson()), "posixOwnershipMode"));
        if (share.getVolumeId() == null) {
            if (requestedPolicyId != null || fixedIdentity) throw new InvalidParameterValueException("Common POSIX policy requires its selected backing volume");
            return;
        }
        final String relative = sharePosixRelativePath(instance, share);
        if (relative == null) {
            if (requestedPolicyId != null || fixedIdentity) throw new InvalidParameterValueException("Common POSIX policy needs a known canonical backing directory");
            return;
        }
        final StoragePosixDirectoryPolicyVO policy = requestedPolicyId == null
                ? storagePosixPolicyDao.findByPath(instance.getId(), PosixDirectoryPolicy.pathKey(requireVolume(share.getVolumeId()).getUuid(), relative))
                : requirePosixDirectoryPolicy(requestedPolicyId);
        if (policy == null) {
            if ("FORCED_UID_GID".equals(getJsonString(parseJsonObject(share.getConfigJson()), "posixOwnershipMode"))) {
                throw new InvalidParameterValueException("Create a common POSIX directory owner policy before enabling fixed SMB identity");
            }
            return;
        }
        if (policy.getInstanceId() != instance.getId() || policy.getVolumeId() != share.getVolumeId() || !relative.equals(policy.getRelativePath())) {
            throw new InvalidParameterValueException("Protocol share does not match the selected common POSIX directory");
        }
        if (!"Ready".equals(policy.getState())) throw new InvalidParameterValueException("Common POSIX directory policy is not ready");
        final JsonObject shareOptions = parseJsonObject(share.getConfigJson());
        if (Boolean.TRUE.equals(getJsonBoolean(shareOptions, "inheritGroup"))
                && (Integer.parseInt(getJsonString(parseJsonObject(policy.getEffectiveJson()), "effectiveMode"), 8) & 02000) == 0) {
            throw new InvalidParameterValueException("Enable setgid on the common directory policy before requesting parent group inheritance");
        }
        final JsonObject effective = parseJsonObjectStrict(policy.getEffectiveJson(), "Common POSIX directory observation");
        if ("FORCED_UID_GID".equals(getJsonString(shareOptions, "posixOwnershipMode"))
                && (shareOptions.get("ownerUid").getAsLong() != effective.get("effectiveUid").getAsLong()
                    || shareOptions.get("ownerGid").getAsLong() != effective.get("effectiveGid").getAsLong())) {
            throw new InvalidParameterValueException("Forced SMB identity must match the common POSIX directory owner UID/GID");
        }
        if (ownerUid != null && ownerUid.longValue() != effective.get("effectiveUid").getAsLong()
                || ownerGid != null && ownerGid.longValue() != effective.get("effectiveGid").getAsLong()
                || requestedMode != null && !PosixDirectoryPolicy.directoryMode(requestedMode).equals(getJsonString(effective, "effectiveMode"))) {
            throw new InvalidParameterValueException("Protocol-local owner/mode conflicts with the common POSIX directory policy; edit the common policy instead");
        }
        final JsonObject config = parseJsonObject(share.getConfigJson());
        config.addProperty("posixPolicyUuid", policy.getUuid());config.addProperty("posixPolicyRevision", policy.getRevision());
        config.addProperty("posixPolicyPath", getJsonString(effective, "canonicalPath"));
        if (share.getProtocol() == StorageServiceInstance.Protocol.NFS) {
            config.add("ownerUid", effective.get("effectiveUid"));config.add("ownerGid", effective.get("effectiveGid"));
            config.add("mode", effective.get("effectiveMode"));config.addProperty("recursivePermission", false);
        } else config.add("directoryMode", effective.get("effectiveMode"));
        share.setPosixPolicyId(policy.getId());share.setConfigJson(GSON.toJson(config));
    }

    protected String sharePosixRelativePath(final StorageServiceInstanceVO instance, final StorageFileShareVO share) {
        final JsonObject config = parseJsonObject(share.getConfigJson());
        final String relative = getJsonString(config, "relativeSharePath");
        if (StringUtils.isNotBlank(relative)) return PosixDirectoryPolicy.relativePath(relative);
        final String backing = getJsonString(config, "backingPath");
        if (share.getVolumeId() == null || StringUtils.isBlank(backing)) return null;
        final String root = resolveFileShareVolumeMountRoot(instance, requireVolume(share.getVolumeId()), share.getPath());
        return backing.startsWith(root + "/") ? PosixDirectoryPolicy.relativePath(backing.substring(root.length() + 1)) : null;
    }

    protected List<StorageFileShareVO> matchingPosixShares(final StorageServiceInstanceVO instance, final StoragePosixDirectoryPolicyVO policy) {
        final List<StorageFileShareVO> resources = new ArrayList<>();
        for (StorageServiceInstance.Protocol protocol : new StorageServiceInstance.Protocol[] {StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB}) {
            for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
                if (Long.valueOf(policy.getVolumeId()).equals(share.getVolumeId()) && policy.getRelativePath().equals(sharePosixRelativePath(instance, share))) resources.add(share);
            }
        }
        return resources;
    }

    protected void bindMatchingPosixShares(final StorageServiceInstanceVO instance, final StoragePosixDirectoryPolicyVO policy, final JsonObject effective) {
        final Set<StorageServiceInstance.Protocol> changed = new HashSet<>();
        for (StorageFileShareVO share : matchingPosixShares(instance, policy)) {
            if (share.getPosixPolicyId() != null && !Long.valueOf(policy.getId()).equals(share.getPosixPolicyId())) {
                throw new InvalidParameterValueException("The directory is referenced by another POSIX policy");
            }
            final JsonObject config = parseJsonObject(share.getConfigJson());
            config.addProperty("posixPolicyUuid", policy.getUuid());config.addProperty("posixPolicyRevision", policy.getRevision());
            config.addProperty("posixPolicyPath", getJsonString(effective, "canonicalPath"));
            if (share.getProtocol() == StorageServiceInstance.Protocol.NFS) {
                config.add("ownerUid", effective.get("effectiveUid"));config.add("ownerGid", effective.get("effectiveGid"));
                config.add("mode", effective.get("effectiveMode"));config.addProperty("recursivePermission", false);
            } else config.add("directoryMode", effective.get("effectiveMode"));
            share.setPosixPolicyId(policy.getId());share.setConfigJson(GSON.toJson(config));storageFileShareDao.update(share.getId(), share);
            changed.add(share.getProtocol());
        }
        for (StorageServiceInstance.Protocol protocol : changed) applyStorageServiceProtocolDesiredState(instance, protocol);
    }

    protected List<StorageFileShareVO> posixPolicyShares(final StorageServiceInstanceVO instance, final StoragePosixDirectoryPolicyVO policy) {
        final List<StorageFileShareVO> resources = new ArrayList<>();
        for (StorageServiceInstance.Protocol protocol : new StorageServiceInstance.Protocol[] {StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB}) {
            for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) {
                if (Long.valueOf(policy.getId()).equals(share.getPosixPolicyId())) resources.add(share);
            }
        }
        return resources;
    }

    protected org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse createPosixPolicyResponse(
            final StorageServiceInstanceVO instance, final StoragePosixDirectoryPolicyVO policy, final JsonObject effective) {
        final org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse response = new org.apache.cloudstack.api.response.StoragePosixDirectoryPolicyResponse();
        response.setId(policy.getUuid());response.setInstanceId(instance.getUuid());response.setVolumeId(requireVolume(policy.getVolumeId()).getUuid());
        response.setRelativePath(policy.getRelativePath());response.setRevision(policy.getRevision());response.setState(policy.getState());
        response.setConfig(policy.getConfigJson());response.setCanonicalPath(getJsonString(effective, "canonicalPath"));response.setEffective(GSON.toJson(effective));
        response.setDriftStatus(StringUtils.defaultIfBlank(getJsonString(effective, "driftStatus"), "UNOBSERVED"));
        final List<String> affected = new ArrayList<>();
        for (StorageFileShareVO share : matchingPosixShares(instance, policy)) affected.add(share.getProtocol().name() + ":" + share.getName());
        response.setAffectedShares(affected);response.setObjectName("storageposixdirectorypolicy");return response;
    }

    protected String buildSmbConfigJson(final String currentConfig, final Boolean readOnly, final Boolean browseable, final Boolean guestOk,
            final Boolean createDirectory, final Boolean crossProtocol, final String directoryMode) {
        final JsonObject config = parseJsonObject(currentConfig);
        if (!config.has("readOnly")) {
            config.addProperty("readOnly", false);
        }
        if (!config.has("browseable")) {
            config.addProperty("browseable", true);
        }
        if (!config.has("guestOk")) {
            config.addProperty("guestOk", false);
        }
        if (!config.has("createDirectory")) {
            config.addProperty("createDirectory", true);
        }
        if (!config.has("crossProtocol")) {
            config.addProperty("crossProtocol", false);
        }
        if (readOnly != null) {
            config.addProperty("readOnly", readOnly);
        }
        if (browseable != null) {
            config.addProperty("browseable", browseable);
        }
        if (guestOk != null) {
            config.addProperty("guestOk", guestOk);
        }
        if (createDirectory != null) {
            config.addProperty("createDirectory", createDirectory);
        }
        if (crossProtocol != null) {
            config.addProperty("crossProtocol", crossProtocol);
        }
        if (StringUtils.isNotBlank(directoryMode)) {
            final String value = directoryMode.trim();
            if (!value.matches("^0?[0-7]{3,4}$")) {
                throw new InvalidParameterValueException("SMB share directory mode must be an octal mode such as 0770");
            }
            config.addProperty("directoryMode", value.startsWith("0") ? value : "0" + value);
        } else if (!config.has("directoryMode")) {
            config.addProperty("directoryMode", "0770");
        }
        return GSON.toJson(config);
    }

    protected String buildFileShareAttachConfigJson(final String currentConfig, final String importMode, final VolumeVO volume,
            final JsonObject inspection) {
        final JsonObject config = parseJsonObject(currentConfig);
        config.addProperty("volumeMode", "EXISTING_VOLUME");
        config.addProperty("importMode", StringUtils.defaultIfBlank(importMode, "MOUNT_EXISTING").toUpperCase());
        config.addProperty("attachedVolumeUuid", volume.getUuid());
        config.addProperty("attachedVolumeName", volume.getName());
        if (inspection != null && inspection.entrySet() != null) {
            final JsonObject normalizedInspection = inspection.deepCopy();
            normalizedInspection.addProperty("schemaVersion", 2);
            normalizedInspection.addProperty("volumeUuid", volume.getUuid());
            final String observedDevicePath = firstJsonString(null, normalizedInspection, "observedDevicePath", "devicePath");
            final String filesystemUuid = firstJsonString(null, normalizedInspection, "filesystemUuid", "fsUuid");
            normalizedInspection.remove("devicePath");
            normalizedInspection.remove("fsUuid");
            if (StringUtils.isNotBlank(observedDevicePath)) {
                normalizedInspection.addProperty("observedDevicePath", observedDevicePath);
            }
            if (StringUtils.isNotBlank(filesystemUuid)) {
                normalizedInspection.addProperty("filesystemUuid", filesystemUuid);
                config.addProperty("filesystemUuid", filesystemUuid);
            }
            config.remove("devicePath");
            config.remove("fsUuid");
            config.add("lastInspection", normalizedInspection);
            final String volumeMountPath = getJsonString(inspection, "volumeMountPath");
            if (StringUtils.isNotBlank(volumeMountPath)) {
                config.addProperty("volumeMountPath", volumeMountPath);
            }
            final String backingPath = getJsonString(inspection, "backingPath");
            if (StringUtils.isNotBlank(backingPath)) {
                config.addProperty("backingPath", backingPath);
            }
        }
        return GSON.toJson(config);
    }

    protected String normalizeVolumeIdentity(final String value) {
        return StringUtils.defaultString(value).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    protected String buildManagedFileShareVolumeReuseConfigJson(final String currentConfig, final String importMode, final VolumeVO volume,
            final String sharePath) {
        final JsonObject config = parseJsonObject(currentConfig);
        final String volumeMountPath = "/srv/ablestack-storage/volumes/" + volume.getUuid();
        config.addProperty("volumeMode", "CURRENT_VOLUME");
        config.addProperty("importMode", StringUtils.defaultIfBlank(importMode, "REUSE_ATTACHED").toUpperCase());
        config.addProperty("attachedVolumeUuid", volume.getUuid());
        config.addProperty("attachedVolumeName", volume.getName());
        config.addProperty("volumeMountPath", volumeMountPath);
        final String relativeSharePath = getJsonString(config, "relativeSharePath") == null
                ? normalizeRelativeSharePath(StringUtils.removeStart(sharePath, "/")) : normalizeRelativeSharePath(getJsonString(config, "relativeSharePath"));
        if (StringUtils.isNotBlank(relativeSharePath)) {
            config.addProperty("backingPath", volumeMountPath.replaceAll("/+$", "") + "/" + relativeSharePath.replaceAll("^/+", ""));
        }
        return GSON.toJson(config);
    }

    protected String buildFileShareDirectoryConfigJson(final String currentConfig, final VolumeVO volume, final String importMode,
            final Boolean createDirectory) {
        final JsonObject config = parseJsonObject(currentConfig);
        if (volume != null && StringUtils.isNotBlank(importMode)) {
            config.addProperty("volumeMountPath", "/srv/ablestack-storage/volumes/" + volume.getUuid());
        }
        config.remove("relativesharepath");
        if (createDirectory != null || !config.has("createDirectory")) {
            config.addProperty("createDirectory", createDirectory == null || Boolean.TRUE.equals(createDirectory));
        }
        return GSON.toJson(config);
    }

    protected String buildFileShareResizeConfigJson(final String currentConfig, final JsonObject resizeResult, final Long quotaBytes) {
        final JsonObject config = parseJsonObject(currentConfig);
        if (quotaBytes != null) {
            config.addProperty("quotaBytes", quotaBytes);
        }
        if (resizeResult != null && resizeResult.entrySet() != null) {
            config.add("lastResize", resizeResult);
        }
        return GSON.toJson(config);
    }

    protected String buildNvmeOfPreparationResult(final String engine, final String transport, final String runtimeCapabilityProfileId,
            final Boolean validateOnly) {
        final JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("status", "PREPARATION_REQUIRED");
        result.addProperty("engine", engine);
        result.addProperty("transport", StringUtils.defaultIfBlank(transport, "tcp"));
        result.addProperty("validateOnly", Boolean.TRUE.equals(validateOnly));
        if (runtimeCapabilityProfileId != null) {
            result.addProperty("runtimeCapabilityProfileId", runtimeCapabilityProfileId);
        }
        result.addProperty("vmRuntimeCapabilityRequired", true);
        return GSON.toJson(result);
    }

    protected String buildSmbAclConfigJson(final StorageServiceInstance.PrincipalType principalType, final String password) {
        final JsonObject config = new JsonObject();
        config.addProperty("localAccount", principalType == StorageServiceInstance.PrincipalType.LOCAL_USER);
        config.addProperty("passwordSupplied", principalType == StorageServiceInstance.PrincipalType.LOCAL_USER && StringUtils.isNotBlank(password));
        return GSON.toJson(config);
    }

    protected String buildIdentityDomainConfigJson(final String domainName, final String workgroup) {
        final JsonObject config = new JsonObject();
        config.addProperty("identityProvider", "active_directory");
        config.addProperty("workgroup", resolveAdWorkgroup(domainName, workgroup));
        return GSON.toJson(config);
    }

    protected String resolveAdWorkgroup(final String domainName, final String workgroup) {
        if (StringUtils.isNotBlank(workgroup) && !"WORKGROUP".equalsIgnoreCase(workgroup.trim())) {
            return workgroup.trim().toUpperCase(Locale.ROOT);
        }
        final String normalizedDomain = StringUtils.trimToEmpty(domainName);
        if (StringUtils.isNotBlank(normalizedDomain)) {
            final String firstLabel = normalizedDomain.split("\\.", 2)[0];
            if (StringUtils.isNotBlank(firstLabel)) {
                return firstLabel.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
            }
        }
        return "WORKGROUP";
    }

    protected String buildSmbNetbiosName(final StorageServiceInstanceVO instance) {
        final String uuid = instance == null ? "" : StringUtils.defaultString(instance.getUuid());
        final String suffix = uuid.replaceAll("[^A-Fa-f0-9]", "");
        final String value = "STOR" + (suffix.length() >= 10 ? suffix.substring(0, 10) : StringUtils.rightPad(suffix, 10, "0"));
        return value.substring(0, Math.min(value.length(), 15)).toUpperCase(Locale.ROOT);
    }

    protected String buildIscsiTargetConfigJson(final String currentConfig, final String backingPath, final String backstoreType, final Long lunSizeBytes,
            final String endpointMode, final String listenerPorts) {
        final JsonObject config = parseJsonObject(currentConfig);
        config.addProperty("type", "target");
        if (backingPath != null) {
            config.addProperty("backingPath", backingPath);
        }
        final String normalizedBackstoreType = normalizeIscsiBackstoreType(StringUtils.defaultIfBlank(backstoreType, getJsonString(config, "backstoreType")));
        config.addProperty("backstoreType", normalizedBackstoreType);
        if ("BLOCK".equals(normalizedBackstoreType)) {
            config.remove("lunSizeBytes");
        } else if (lunSizeBytes != null) {
            config.addProperty("lunSizeBytes", lunSizeBytes);
        }
        if (listenerPorts != null) {
            final JsonArray parsedListenerPorts = parseIscsiListenerPorts(listenerPorts);
            config.add("listenerGroupPorts", parsedListenerPorts.size() > 0 ? parsedListenerPorts : singletonNfsListenerPortArray(3260));
            config.addProperty("endpointMode", "LISTENER_GROUP");
        } else if (endpointMode != null) {
            config.addProperty("endpointMode", normalizeIscsiEndpointMode(endpointMode));
            if (!config.has("listenerGroupPorts")) {
                config.add("listenerGroupPorts", singletonNfsListenerPortArray(3260));
            }
        } else if (!config.has("endpointMode")) {
            config.addProperty("endpointMode", "LISTENER_GROUP");
            config.add("listenerGroupPorts", singletonNfsListenerPortArray(3260));
        }
        return GSON.toJson(config);
    }

    protected String normalizeIscsiBackstoreType(final String backstoreType) {
        final String value = StringUtils.defaultIfBlank(backstoreType, "BLOCK").trim().toUpperCase(Locale.ROOT);
        if (!"BLOCK".equals(value)) {
            throw new InvalidParameterValueException("iSCSI targets support block backstores only. File-based LUNs are not supported.");
        }
        return value;
    }

    protected void validateIscsiBlockOnlyBackstore(final String backstoreType) {
        normalizeIscsiBackstoreType(backstoreType);
    }

    protected JsonArray parseIscsiListenerPorts(final String listenerPorts) {
        final JsonArray result = new JsonArray();
        if (StringUtils.isBlank(listenerPorts)) {
            return result;
        }
        final HashSet<Integer> seen = new HashSet<>();
        for (final String rawValue : StringUtils.split(listenerPorts, ',')) {
            final String value = StringUtils.trim(rawValue);
            if (StringUtils.isBlank(value)) {
                continue;
            }
            final int port;
            try {
                port = Integer.parseInt(value);
            } catch (final NumberFormatException e) {
                throw new InvalidParameterValueException("Invalid iSCSI listener port group: " + value);
            }
            if (port < 1 || port > 65535) {
                throw new InvalidParameterValueException("Invalid iSCSI listener port group: " + port);
            }
            if (seen.add(port)) {
                result.add(port);
            }
        }
        return result;
    }

    protected String normalizeIscsiEndpointMode(final String endpointMode) {
        final String value = StringUtils.isBlank(endpointMode) ? "LISTENER_GROUP" : StringUtils.trim(endpointMode).toUpperCase();
        if (!"ALL".equals(value) && !"LISTENER_GROUP".equals(value)) {
            throw new InvalidParameterValueException("Invalid iSCSI target endpoint mode: " + endpointMode);
        }
        return value;
    }

    protected void validateIscsiEndpointPolicy(final String endpointMode, final String listenerPorts) {
        final String normalized = normalizeIscsiEndpointMode(endpointMode);
        if ("LISTENER_GROUP".equals(normalized)) {
            parseIscsiListenerPorts(listenerPorts);
        }
    }

    protected void validateIscsiListenerPortsExist(final StorageServiceInstanceVO instance, final String listenerPorts) {
        if (StringUtils.isBlank(listenerPorts)) {
            return;
        }
        final JsonArray requestedPorts = parseIscsiListenerPorts(listenerPorts);
        if (requestedPorts.size() == 0) {
            throw new InvalidParameterValueException("iSCSI targets require at least one listener port group.");
        }
        final HashSet<Integer> enabledPorts = new HashSet<>();
        for (final StorageServiceProtocolVO protocol : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.ISCSI)) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            enabledPorts.add(protocol.getPort() == null ? 3260 : protocol.getPort());
        }
        if (enabledPorts.isEmpty()) {
            enabledPorts.add(3260);
        }
        for (final JsonElement element : requestedPorts) {
            final int port = element.getAsInt();
            if (!enabledPorts.contains(port)) {
                throw new InvalidParameterValueException("iSCSI listener port group is not enabled for this Storage Service: " + port);
            }
        }
    }

    protected void validateNvmeOfEndpointPolicy(final String listenerPorts) {
        parseIscsiListenerPorts(listenerPorts);
    }

    protected void validateNvmeOfListenerPortsExist(final StorageServiceInstanceVO instance, final String listenerPorts) {
        if (StringUtils.isBlank(listenerPorts)) {
            return;
        }
        final JsonArray requestedPorts = parseIscsiListenerPorts(listenerPorts);
        if (requestedPorts.size() == 0) {
            throw new InvalidParameterValueException("NVMe-oF namespaces require at least one listener port group.");
        }
        final HashSet<Integer> enabledPorts = new HashSet<>();
        for (final StorageServiceProtocolVO protocol : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            enabledPorts.add(protocol.getPort() == null ? 4420 : protocol.getPort());
        }
        if (enabledPorts.isEmpty()) {
            enabledPorts.add(4420);
        }
        for (final JsonElement element : requestedPorts) {
            final int port = element.getAsInt();
            if (!enabledPorts.contains(port)) {
                throw new InvalidParameterValueException("NVMe-oF listener port group is not enabled for this Storage Service: " + port);
            }
        }
    }

    protected void validateNvmeOfNamespaceListenerPortsCompatible(final StorageServiceInstanceVO instance, final StorageBlockTargetVO subsystem,
            final String listenerPorts, final Long ignoredNamespaceId) {
        if (instance == null || subsystem == null || StringUtils.isBlank(subsystem.getTargetName())) {
            return;
        }
        final Set<Integer> requestedPorts = nvmeOfListenerPortSet(listenerPorts);
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (target == null || !isNvmeOfNamespace(target) || !StringUtils.equals(target.getTargetName(), subsystem.getTargetName())) {
                continue;
            }
            if (ignoredNamespaceId != null && ignoredNamespaceId.equals(target.getId())) {
                continue;
            }
            if (!isActiveStorageServiceResource(target.getState())) {
                continue;
            }
            final Set<Integer> existingPorts = nvmeOfListenerPortSet(parseJsonObject(target.getConfigJson()));
            if (!existingPorts.equals(requestedPorts)) {
                throw new InvalidParameterValueException("NVMe-oF namespaces in the same subsystem must use the same listener port group. Create another subsystem for a different listener port group.");
            }
        }
    }

    protected Set<Integer> nvmeOfListenerPortSet(final String listenerPorts) {
        final Set<Integer> ports = new HashSet<>();
        final JsonArray parsedPorts = parseIscsiListenerPorts(listenerPorts);
        for (final JsonElement element : parsedPorts) {
            if (element != null && !element.isJsonNull()) {
                ports.add(element.getAsInt());
            }
        }
        if (ports.isEmpty()) {
            ports.add(4420);
        }
        return ports;
    }

    protected Set<Integer> nvmeOfListenerPortSet(final JsonObject config) {
        if (config == null || !config.has("listenerGroupPorts") || !config.get("listenerGroupPorts").isJsonArray()) {
            return nvmeOfListenerPortSet((String)null);
        }
        final List<String> ports = new ArrayList<>();
        for (final JsonElement element : config.getAsJsonArray("listenerGroupPorts")) {
            if (element != null && !element.isJsonNull()) {
                ports.add(element.getAsString());
            }
        }
        return nvmeOfListenerPortSet(StringUtils.join(ports, ','));
    }

    protected void validateNfsNumericPermissionValue(final String name, final Integer value) {
        if (value < 0 || value > 65535) {
            throw new InvalidParameterValueException(name + " must be between 0 and 65535");
        }
    }

    protected void validateNfsMode(final String mode) {
        if (!mode.matches("^0?[0-7]{3,4}$")) {
            throw new InvalidParameterValueException("NFS export mode must be an octal value such as 0775");
        }
    }

    protected Long getJsonLong(final JsonObject object, final String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsLong();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    protected Boolean getJsonBoolean(final JsonObject object, final String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    protected String getJsonString(final JsonObject object, final String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        try {
            return object.get(key).getAsString();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    protected JsonObject getJsonObject(final JsonObject object, final String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    protected void validateIscsiChapCredentialRequest(final Boolean chapEnabled, final String chapUsername, final String chapSecret,
            final Boolean mutualChapEnabled, final String mutualChapUsername, final String mutualChapSecret) {
        if (Boolean.TRUE.equals(chapEnabled)) {
            if (StringUtils.isBlank(chapUsername)) {
                throw new InvalidParameterValueException("CHAP username is required when iSCSI CHAP authentication is enabled");
            }
            if (StringUtils.isBlank(chapSecret)) {
                throw new InvalidParameterValueException("CHAP secret is required when iSCSI CHAP authentication is enabled because CHAP secrets are not stored");
            }
        }
        if (Boolean.TRUE.equals(mutualChapEnabled)) {
            if (!Boolean.TRUE.equals(chapEnabled)) {
                throw new InvalidParameterValueException("Mutual CHAP requires one-way CHAP authentication to be enabled");
            }
            if (StringUtils.isBlank(mutualChapUsername)) {
                throw new InvalidParameterValueException("Mutual CHAP username is required when mutual CHAP is enabled");
            }
            if (StringUtils.isBlank(mutualChapSecret)) {
                throw new InvalidParameterValueException("Mutual CHAP secret is required when mutual CHAP is enabled because CHAP secrets are not stored");
            }
        }
    }

    protected String buildIscsiAclConfigJson(final String currentConfig, final Boolean chapEnabled, final String chapUsername,
            final Boolean mutualChapEnabled, final String mutualChapUsername, final String chapSecret, final String mutualChapSecret) {
        final JsonObject config = parseJsonObject(currentConfig);
        if (chapEnabled != null) {
            config.addProperty("chapEnabled", chapEnabled);
            if (!chapEnabled) {
                config.remove("chapUsername");
            }
        }
        if (chapUsername != null) {
            config.addProperty("chapUsername", chapUsername);
        }
        if (mutualChapEnabled != null) {
            config.addProperty("mutualChapEnabled", mutualChapEnabled);
            if (!mutualChapEnabled) {
                config.remove("mutualChapUsername");
            }
        }
        if (mutualChapUsername != null) {
            config.addProperty("mutualChapUsername", mutualChapUsername);
        }
        if (chapEnabled == null) {
            config.addProperty("chapEnabled", config.has("chapUsername") || StringUtils.isNotBlank(chapSecret));
        }
        if (mutualChapEnabled == null) {
            config.addProperty("mutualChapEnabled", config.has("mutualChapUsername") || StringUtils.isNotBlank(mutualChapSecret));
        }
        return GSON.toJson(config);
    }

    protected String buildNvmeOfHostAclConfigJson(final String currentConfig, final Boolean dhChapEnabled, final Boolean dhChapCtrlEnabled,
            final String dhChapKey, final String dhChapCtrlKey) {
        final JsonObject config = parseJsonObject(currentConfig);
        if (dhChapEnabled != null) {
            config.addProperty("dhChapEnabled", dhChapEnabled);
        } else if (!config.has("dhChapEnabled")) {
            config.addProperty("dhChapEnabled", StringUtils.isNotBlank(dhChapKey));
        }
        if (dhChapCtrlEnabled != null) {
            config.addProperty("dhChapCtrlEnabled", dhChapCtrlEnabled);
        } else if (!config.has("dhChapCtrlEnabled")) {
            config.addProperty("dhChapCtrlEnabled", StringUtils.isNotBlank(dhChapCtrlKey));
        }
        return GSON.toJson(config);
    }

    protected String buildNvmeOfConfigJson(final String currentConfig, final String type, final Boolean allowAnyHost, final String backingPath,
            final String engine, final String transport, final Long namespaceSizeBytes) {
        return buildNvmeOfConfigJson(currentConfig, type, allowAnyHost, backingPath, engine, transport, namespaceSizeBytes, null);
    }

    protected String buildNvmeOfConfigJson(final String currentConfig, final String type, final Boolean allowAnyHost, final String backingPath,
            final String engine, final String transport, final Long namespaceSizeBytes, final String listenerPorts) {
        final JsonObject config = parseJsonObject(currentConfig);
        config.addProperty("type", type);
        if ("subsystem".equals(type) && !config.has("allowAnyHost")) {
            config.addProperty("allowAnyHost", false);
        }
        if (allowAnyHost != null) {
            config.addProperty("allowAnyHost", allowAnyHost);
        }
        if (backingPath != null) {
            config.addProperty("backingPath", backingPath);
        }
        if ("namespace".equals(type) && namespaceSizeBytes != null) {
            config.addProperty("namespaceSizeBytes", namespaceSizeBytes);
        }
        if ("namespace".equals(type)) {
            config.addProperty("backstoreType", "BLOCK");
            if (listenerPorts != null) {
                final JsonArray parsedListenerPorts = parseIscsiListenerPorts(listenerPorts);
                config.add("listenerGroupPorts", parsedListenerPorts.size() > 0 ? parsedListenerPorts : singletonNfsListenerPortArray(4420));
                config.addProperty("endpointMode", "LISTENER_GROUP");
            } else if (!config.has("endpointMode")) {
                config.addProperty("endpointMode", "LISTENER_GROUP");
                config.add("listenerGroupPorts", singletonNfsListenerPortArray(4420));
            }
        }
        if ("subsystem".equals(type)) {
            final String requestedEngine = StringUtils.defaultIfBlank(engine,
                    config.has("engine") ? config.get("engine").getAsString() : "KERNEL_NVMET").toUpperCase();
            if (!"KERNEL_NVMET".equals(requestedEngine) && !"SPDK".equals(requestedEngine)) {
                throw new InvalidParameterValueException("Unsupported NVMe-oF engine: " + engine);
            }
            config.addProperty("engine", requestedEngine);
            config.addProperty("engineState", "SPDK".equals(requestedEngine) ? "PREPARATION_REQUIRED" : "SUPPORTED");
            config.addProperty("transport", StringUtils.defaultIfBlank(transport,
                    config.has("transport") ? config.get("transport").getAsString() : "tcp"));
        }
        return GSON.toJson(config);
    }

    protected boolean isNvmeOfSubsystem(final StorageBlockTargetVO target) {
        final JsonObject config = parseJsonObject(target.getConfigJson());
        return "subsystem".equals(config.has("type") ? config.get("type").getAsString() : null);
    }

    protected boolean isNvmeOfNamespace(final StorageBlockTargetVO target) {
        final JsonObject config = parseJsonObject(target.getConfigJson());
        return "namespace".equals(config.has("type") ? config.get("type").getAsString() : null);
    }

    protected void updateNvmeOfSubsystemName(final StorageBlockTargetVO subsystem, final String newNqn) {
        final String oldNqn = subsystem.getTargetName();
        subsystem.setTargetName(newNqn);
        for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(subsystem.getInstanceId(), StorageServiceInstance.Protocol.NVME_OF)) {
            if (target.getId() != subsystem.getId() && oldNqn.equals(target.getTargetName())) {
                target.setTargetName(newNqn);
                storageBlockTargetDao.update(target.getId(), target);
            }
        }
    }

    protected Map<Long, String> buildSecretMap(final long ruleId, final String password) {
        if (StringUtils.isBlank(password)) {
            return Collections.emptyMap();
        }
        final Map<Long, String> secrets = new HashMap<>();
        secrets.put(ruleId, password);
        return secrets;
    }

    protected Map<Long, JsonObject> buildChapSecretMap(final long ruleId, final String chapSecret, final String mutualChapSecret) {
        if (StringUtils.isBlank(chapSecret) && StringUtils.isBlank(mutualChapSecret)) {
            return Collections.emptyMap();
        }
        final JsonObject secrets = new JsonObject();
        if (StringUtils.isNotBlank(chapSecret)) {
            secrets.addProperty("chapSecret", chapSecret);
        }
        if (StringUtils.isNotBlank(mutualChapSecret)) {
            secrets.addProperty("mutualChapSecret", mutualChapSecret);
        }
        final Map<Long, JsonObject> secretMap = new HashMap<>();
        secretMap.put(ruleId, secrets);
        return secretMap;
    }

    protected Map<Long, JsonObject> buildNvmeOfSecretMap(final long ruleId, final String dhChapKey, final String dhChapCtrlKey) {
        if (StringUtils.isBlank(dhChapKey) && StringUtils.isBlank(dhChapCtrlKey)) {
            return Collections.emptyMap();
        }
        final JsonObject secrets = new JsonObject();
        if (StringUtils.isNotBlank(dhChapKey)) {
            secrets.addProperty("dhChapKey", dhChapKey);
        }
        if (StringUtils.isNotBlank(dhChapCtrlKey)) {
            secrets.addProperty("dhChapCtrlKey", dhChapCtrlKey);
        }
        final Map<Long, JsonObject> secretMap = new HashMap<>();
        secretMap.put(ruleId, secrets);
        return secretMap;
    }

    protected void ensureSmbProtocol(final StorageServiceInstanceVO instance) {
        ensureProtocol(instance, StorageServiceInstance.Protocol.SMB);
    }

    protected NicVO resolveProtocolListenAddress(final StorageServiceInstanceVO instance, final String listenIp) {
        if (StringUtils.isBlank(listenIp) || "0.0.0.0".equals(listenIp) || "::".equals(listenIp) || instance.getVmId() == null) {
            return null;
        }
        if (!isValidIpv4Address(listenIp)) {
            throw new InvalidParameterValueException("Invalid Storage Service listen IP: " + listenIp);
        }
        final List<NicVO> nics = nicDao.listByVmId(instance.getVmId());
        for (final NicVO nic : nics) {
            if (listenIp.equals(nic.getIPv4Address())) {
                return nic;
            }
            final NicSecondaryIpVO existingForNic = nicSecondaryIpDao.findByIp4AddressAndNicId(listenIp, nic.getId());
            if (existingForNic != null) {
                return nic;
            }
        }

        final NicVO targetNic = findTargetNicForListenAddress(instance, listenIp, nics);
        if (targetNic == null) {
            throw new InvalidParameterValueException("Storage Service listen IP must be in the same CIDR as one of the System VM NICs: " + listenIp);
        }

        final NicVO primaryConflict = nicDao.findByIp4AddressAndNetworkId(listenIp, targetNic.getNetworkId());
        if (primaryConflict != null && primaryConflict.getInstanceId() != instance.getVmId()) {
            throw new InvalidParameterValueException("Storage Service listen IP is already used by another NIC: " + listenIp);
        }
        final NicSecondaryIpVO secondaryConflict = nicSecondaryIpDao.findByIp4AddressAndNetworkId(listenIp, targetNic.getNetworkId());
        if (secondaryConflict != null) {
            if (secondaryConflict.getVmId() == instance.getVmId()) {
                return targetNic;
            }
            throw new InvalidParameterValueException("Storage Service listen IP is already used as a secondary IP: " + listenIp);
        }
        return targetNic;
    }

    protected boolean registerProtocolListenAddress(final StorageServiceInstanceVO instance, final String listenIp, final NicVO targetNic) {
        if (targetNic == null || StringUtils.isBlank(listenIp) || "0.0.0.0".equals(listenIp) || "::".equals(listenIp) || instance.getVmId() == null) {
            return false;
        }
        if (listenIp.equals(targetNic.getIPv4Address())) {
            logger.info("Storage Service listen IP [{}] is already the primary IP on NIC [{}] for instance [{}]; skipping secondary IP registration",
                    listenIp, targetNic.getUuid(), instance.getUuid());
            return false;
        }
        if (nicSecondaryIpDao.findByIp4AddressAndNicId(listenIp, targetNic.getId()) != null) {
            return false;
        }
        final String originalPrimaryIp = targetNic.getIPv4Address();
        final boolean registered = Transaction.execute(new TransactionCallback<Boolean>() {
            @Override
            public Boolean doInTransaction(final TransactionStatus status) {
                final NicVO currentNic = nicDao.findById(targetNic.getId());
                if (currentNic == null || !StringUtils.equals(originalPrimaryIp, currentNic.getIPv4Address())) {
                    throw new CloudRuntimeException("Storage Service NIC primary IP changed while registering listener alias " + listenIp);
                }
                if (nicSecondaryIpDao.findByIp4AddressAndNicId(listenIp, currentNic.getId()) != null) {
                    return false;
                }
                if (!currentNic.getSecondaryIp()) {
                    if (!nicDao.updateSecondaryIpFlag(currentNic.getId(), true, originalPrimaryIp)) {
                        throw new CloudRuntimeException("Storage Service NIC changed while enabling secondary IP tracking");
                    }
                }
                nicSecondaryIpDao.persist(new NicSecondaryIpVO(currentNic.getId(), listenIp, instance.getVmId(), instance.getAccountId(), instance.getDomainId(), currentNic.getNetworkId()));
                final NicVO verifiedNic = nicDao.findById(currentNic.getId());
                if (verifiedNic == null || !StringUtils.equals(originalPrimaryIp, verifiedNic.getIPv4Address())) {
                    throw new CloudRuntimeException("Storage Service listener alias registration attempted to change NIC primary IP");
                }
                return true;
            }
        });
        if (!registered) {
            return false;
        }
        logger.info("Registered Storage Service listen IP [{}] as a secondary IP on NIC [{}] for instance [{}]", listenIp, targetNic.getUuid(), instance.getUuid());
        return true;
    }

    protected NicVO reconcileProtocolListenNicIdentity(final StorageServiceInstanceVO instance, final NicVO targetNic) {
        if (targetNic == null || StringUtils.isNotBlank(targetNic.getIPv4Address())) {
            return targetNic;
        }
        final String runtimePrimaryIp = observeRuntimePrimaryIp(instance);
        if (StringUtils.isBlank(runtimePrimaryIp)) {
            throw new CloudRuntimeException("Unable to determine the primary IPv4 address of the Storage Service System VM NIC before registering a listen IP");
        }
        if (!nicDao.updatePrimaryIpAddress(targetNic.getId(), runtimePrimaryIp, null)) {
            final NicVO concurrentNic = nicDao.findById(targetNic.getId());
            if (concurrentNic != null && StringUtils.equals(runtimePrimaryIp, concurrentNic.getIPv4Address())) {
                return concurrentNic;
            }
            throw new CloudRuntimeException("Storage Service NIC changed while synchronizing its runtime primary IPv4 address");
        }
        final NicVO reconciledNic = nicDao.findById(targetNic.getId());
        if (reconciledNic == null || !StringUtils.equals(runtimePrimaryIp, reconciledNic.getIPv4Address())) {
            throw new CloudRuntimeException("Storage Service NIC primary IPv4 synchronization did not persist the observed runtime address");
        }
        logger.info("Synchronized Storage Service NIC [{}] primary IPv4 address [{}] from the running System VM before registering a listen IP",
                reconciledNic.getUuid(), runtimePrimaryIp);
        return reconciledNic;
    }

    protected void ensureGuestProtocolListenAddress(final StorageServiceInstanceVO instance, final String listenIp, final NicVO targetNic, final Integer port) {
        if (targetNic == null || StringUtils.isBlank(listenIp) || "0.0.0.0".equals(listenIp) || "::".equals(listenIp) || instance.getVmId() == null) {
            return;
        }
        final JsonObject payload = new JsonObject();
        payload.addProperty("listenIp", listenIp);
        payload.addProperty("primaryIp", targetNic.getIPv4Address());
        payload.addProperty("netmask", targetNic.getIPv4Netmask());
        payload.addProperty("prefixlen", ipv4NetmaskToPrefixLength(targetNic.getIPv4Netmask()));
        final String networkCidr = findProtocolEndpointCidr(instance, targetNic, listenIp);
        if (StringUtils.isNotBlank(networkCidr)) {
            payload.addProperty("networkCidr", networkCidr);
            if (StringUtils.isBlank(targetNic.getIPv4Netmask())) {
                payload.addProperty("prefixlen", cidrPrefixLength(networkCidr));
            }
        }
        if (port != null) {
            payload.addProperty("port", port);
        }
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "network endpoint apply", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
        if (!result.isSuccess()) {
            throw new CloudRuntimeException("Failed to activate Storage Service listen IP inside System VM: " + result.getDetails());
        }
    }

    protected int ipv4NetmaskToPrefixLength(final String netmask) {
        if (StringUtils.isBlank(netmask)) {
            return 24;
        }
        final String[] parts = netmask.trim().split("\\.");
        if (parts.length != 4) {
            return 24;
        }
        int prefix = 0;
        for (final String part : parts) {
            final int value;
            try {
                value = Integer.parseInt(part);
            } catch (final NumberFormatException e) {
                return 24;
            }
            if (value < 0 || value > 255) {
                return 24;
            }
            prefix += Integer.bitCount(value);
        }
        return prefix;
    }

    protected void rollbackProtocolEnable(final StorageServiceProtocolVO protocolVO, final boolean created, final Boolean previousEnabled,
            final String previousListenIp, final Integer previousPort, final StorageServiceInstance.ResourceState previousState) {
        if (protocolVO == null) {
            return;
        }
        if (created) {
            storageServiceProtocolDao.remove(protocolVO.getId());
            return;
        }
        protocolVO.setEnabled(Boolean.TRUE.equals(previousEnabled));
        protocolVO.setListenIp(previousListenIp);
        protocolVO.setPort(previousPort);
        protocolVO.setState(previousState);
        storageServiceProtocolDao.update(protocolVO.getId(), protocolVO);
    }

    protected NicVO findTargetNicForListenAddress(final StorageServiceInstanceVO instance, final String listenIp, final List<NicVO> nics) {
        for (final NicVO nic : nics) {
            if (StringUtils.isNotBlank(nic.getIPv4Address()) && StringUtils.isNotBlank(nic.getIPv4Netmask()) &&
                    isSameIpv4Network(listenIp, nic.getIPv4Address(), nic.getIPv4Netmask())) {
                return nic;
            }
        }

        for (final NicVO nic : nics) {
            final NetworkVO network = networkDao.findById(nic.getNetworkId());
            if (network == null || StringUtils.isBlank(nic.getIPv4Address())) {
                continue;
            }
            if (isIpv4InCidr(listenIp, network.getNetworkCidr()) && isIpv4InCidr(nic.getIPv4Address(), network.getNetworkCidr())) {
                return nic;
            }
            if (isIpv4InCidr(listenIp, network.getCidr()) && isIpv4InCidr(nic.getIPv4Address(), network.getCidr())) {
                return nic;
            }
        }

        final DataCenterVO zone = dataCenterDao.findById(instance.getDataCenterId());
        final String zoneGuestCidr = zone == null ? null : zone.getGuestNetworkCidr();
        if (StringUtils.isNotBlank(zoneGuestCidr)) {
            for (final NicVO nic : nics) {
                if (StringUtils.isNotBlank(nic.getIPv4Address()) && isIpv4InCidr(listenIp, zoneGuestCidr) && isIpv4InCidr(nic.getIPv4Address(), zoneGuestCidr)) {
                    logger.debug("Resolved Storage Service listen IP [{}] against zone guest CIDR [{}] because NIC [{}] has no usable netmask",
                            listenIp, zoneGuestCidr, nic.getUuid());
                    return nic;
                }
            }
        }
        if (nics.size() == 1) {
            final NicVO nic = nics.get(0);
            logger.debug("Deferring Storage Service listen IP [{}] CIDR validation to the System VM guest because NIC [{}] has no DB CIDR evidence",
                    listenIp, nic.getUuid());
            return nic;
        }
        return null;
    }

    protected boolean isValidIpv4Address(final String value) {
        final String[] parts = StringUtils.split(value, '.');
        if (parts == null || parts.length != 4) {
            return false;
        }
        for (final String part : parts) {
            if (!StringUtils.isNumeric(part)) {
                return false;
            }
            final int number = Integer.parseInt(part);
            if (number < 0 || number > 255) {
                return false;
            }
        }
        return true;
    }

    protected boolean isSameIpv4Network(final String ip, final String baseIp, final String netmask) {
        if (!isValidIpv4Address(ip) || !isValidIpv4Address(baseIp) || !isValidIpv4Address(netmask)) {
            return false;
        }
        final long mask = ipv4ToLong(netmask);
        return (ipv4ToLong(ip) & mask) == (ipv4ToLong(baseIp) & mask);
    }

    protected boolean isIpv4InCidr(final String ip, final String cidr) {
        if (!isValidIpv4Address(ip) || StringUtils.isBlank(cidr)) {
            return false;
        }
        final String[] parts = StringUtils.split(cidr, '/');
        if (parts == null || parts.length != 2 || !isValidIpv4Address(parts[0]) || !StringUtils.isNumeric(parts[1])) {
            return false;
        }
        final int prefix = Integer.parseInt(parts[1]);
        if (prefix < 0 || prefix > 32) {
            return false;
        }
        final long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        return (ipv4ToLong(ip) & mask) == (ipv4ToLong(parts[0]) & mask);
    }

    protected long ipv4ToLong(final String value) {
        long result = 0;
        for (final String part : StringUtils.split(value, '.')) {
            result = (result << 8) + Integer.parseInt(part);
        }
        return result & 0xffffffffL;
    }

    protected void ensureProtocol(final StorageServiceInstanceVO instance, final StorageServiceInstance.Protocol protocolType) {
        if (protocolType == StorageServiceInstance.Protocol.SMB) {
            final List<StorageServiceProtocolVO> endpoints = storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocolType);
            if (endpoints.stream().anyMatch(StorageServiceProtocolVO::isEnabled)) return;
            if (!endpoints.isEmpty()) {
                StorageServiceProtocolVO endpoint = endpoints.stream().min(java.util.Comparator.comparingLong(StorageServiceProtocolVO::getId)).get();
                endpoint.setEnabled(true); endpoint.setState(StorageServiceInstance.ResourceState.Ready);
                storageServiceProtocolDao.update(endpoint.getId(), endpoint);
                return;
            }
        }

        StorageServiceProtocolVO protocol = isEndpointProtocol(protocolType) ?
                findProtocolEndpoint(instance.getId(), protocolType, null, defaultProtocolPort(protocolType)) :
                storageServiceProtocolDao.findByInstanceIdAndProtocol(instance.getId(), protocolType);
        if (protocol == null) {
            protocol = new StorageServiceProtocolVO(instance.getId(), protocolType, true, null, isEndpointProtocol(protocolType) ? 2049 : null);
            protocol.setState(StorageServiceInstance.ResourceState.Ready);
            storageServiceProtocolDao.persist(protocol);
        } else if (!protocol.isEnabled()) {
            protocol.setEnabled(true);
            protocol.setState(StorageServiceInstance.ResourceState.Ready);
            storageServiceProtocolDao.update(protocol.getId(), protocol);
        }
    }

    protected int getJsonInt(final JsonObject object, final String key, final int defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }
        try {
            return object.get(key).getAsInt();
        } catch (final RuntimeException e) {
            return defaultValue;
        }
    }

    protected JsonObject parseJsonObject(final String json) {
        if (StringUtils.isBlank(json)) {
            return new JsonObject();
        }
        try {
            return new JsonParser().parse(json).getAsJsonObject();
        } catch (final RuntimeException e) {
            logger.warn("Ignoring invalid Storage Service JSON config", e);
            return new JsonObject();
        }
    }

    protected JsonObject parseJsonObjectStrict(final String json, final String resourceName) {
        if (StringUtils.isBlank(json)) {
            return new JsonObject();
        }
        try {
            return new JsonParser().parse(json).getAsJsonObject();
        } catch (final RuntimeException e) {
            throw new CloudRuntimeException(resourceName + " has invalid Storage Service JSON config; refusing to apply desired state", e);
        }
    }

    protected void validateJsonObjectConfigOrThrow(final String json, final String resourceName) {
        if (!isJsonObjectConfigValid(json)) {
            throw new CloudRuntimeException(resourceName + " generated invalid Storage Service JSON config; refusing to store it");
        }
    }

    protected String buildFileShareErrorConfigJson(final String currentConfig, final RuntimeException failure) {
        final JsonObject config = isJsonObjectConfigValid(currentConfig) ? parseJsonObject(currentConfig) : new JsonObject();
        if (!isJsonObjectConfigValid(currentConfig)) {
            config.addProperty("invalidConfigDiscarded", true);
        }
        final JsonObject error = new JsonObject();
        error.addProperty("type", failure == null ? null : failure.getClass().getName());
        error.addProperty("message", failure == null ? null : failure.getMessage());
        error.addProperty("timestamp", System.currentTimeMillis());
        config.add("lastError", error);
        return GSON.toJson(config);
    }

    protected boolean isApplicableFileShareState(final StorageServiceInstance.ResourceState state) {
        return isApplicableFileShareState(state, false);
    }

    protected boolean isApplicableFileShareState(final StorageServiceInstance.ResourceState state, final boolean includeAllocatedResources) {
        return isApplicableResourceState(state, includeAllocatedResources);
    }

    protected boolean isApplicableResourceState(final StorageServiceInstance.ResourceState state) {
        return isApplicableResourceState(state, false);
    }

    protected boolean isApplicableResourceState(final StorageServiceInstance.ResourceState state, final boolean includeAllocatedResources) {
        return state == StorageServiceInstance.ResourceState.Ready || state == StorageServiceInstance.ResourceState.Updating ||
                (includeAllocatedResources && state == StorageServiceInstance.ResourceState.Allocated);
    }

    protected void validateNfsExportBackingConfig(final StorageFileShareVO share, final JsonObject config) {
        if (share == null || share.getState() == StorageServiceInstance.ResourceState.Disabled ||
                share.getState() == StorageServiceInstance.ResourceState.Destroyed ||
                share.getState() == StorageServiceInstance.ResourceState.Error) {
            return;
        }
        final String backingPath = getJsonString(config, "backingPath");
        final String volumeMountPath = getJsonString(config, "volumeMountPath");
        if (StringUtils.isBlank(backingPath) || StringUtils.isBlank(volumeMountPath)) {
            throw new CloudRuntimeException("NFS export " + share.getUuid() +
                    " has no resolved backing volume path; refusing to expose a root filesystem directory");
        }
        if (!StringUtils.startsWith(backingPath, "/srv/ablestack-storage/volumes/")) {
            throw new CloudRuntimeException("NFS export " + share.getUuid() +
                    " backing path is outside the managed Storage Service volume area: " + backingPath);
        }
        if (!StringUtils.startsWith(backingPath, StringUtils.removeEnd(volumeMountPath, "/") + "/") &&
                !StringUtils.equals(backingPath, StringUtils.removeEnd(volumeMountPath, "/"))) {
            throw new CloudRuntimeException("NFS export " + share.getUuid() +
                    " backing path is not under the selected backing volume mount");
        }
    }

    protected boolean isJsonObjectConfigValid(final String json) {
        if (StringUtils.isBlank(json)) {
            return true;
        }
        try {
            new JsonParser().parse(json).getAsJsonObject();
            return true;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    protected StorageServiceInstanceResponse createInstanceResponse(final StorageServiceInstanceVO instance) {
        final StorageServiceInstanceResponse response = new StorageServiceInstanceResponse();
        response.setId(instance.getUuid());
        response.setName(instance.getName());
        response.setDescription(instance.getDescription());
        final DataCenterVO zone = dataCenterDao.findById(instance.getDataCenterId());
        response.setZoneId(zone == null ? null : zone.getUuid());
        if (instance.getVmId() != null) {
            final VMInstanceVO vm = vmInstanceDao.findById(instance.getVmId());
            response.setVirtualMachineId(vm == null ? null : vm.getUuid());
        }
        if (instance.getServiceOfferingId() != null) {
            final ServiceOfferingVO offering = serviceOfferingDao.findById(instance.getServiceOfferingId());
            response.setServiceOfferingId(offering == null ? null : offering.getUuid());
        }
        response.setProvider(instance.getProvider());
        response.setState(instance.getState().name());
        response.setObjectName("storageserviceinstance");
        return response;
    }

    protected StorageServiceProtocolResponse createProtocolResponse(final StorageServiceProtocolVO protocol) {
        return createProtocolResponse(protocol, buildProtocolResponseContext(protocol.getInstanceId()));
    }

    protected StorageServiceProtocolResponse createProtocolResponse(final StorageServiceProtocolVO protocol, final ProtocolResponseContext context) {
        final StorageServiceProtocolResponse response = new StorageServiceProtocolResponse();
        response.setId(protocol.getUuid());
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(protocol.getInstanceId());
        response.setInstanceId(instance == null ? null : instance.getUuid());
        response.setProtocol(protocol.getProtocol().name());
        response.setEnabled(protocol.isEnabled());
        response.setListenIp(protocol.getListenIp());
        response.setPort(protocol.getPort());
        response.setState(protocol.getState().name());
        response.setConfig(protocol.getConfigJson());
        final int listenerPort = protocol.getPort() == null ? defaultProtocolPort(protocol.getProtocol()) : protocol.getPort();
        final boolean wildcard = StringUtils.isBlank(protocol.getListenIp()) || "0.0.0.0".equals(protocol.getListenIp());
        response.setListenerType(wildcard ? "WILDCARD" : "DEDICATED");
        response.setPrimaryIp(context == null ? null : context.primaryIp);
        response.setRuntimePrimaryIp(context == null ? null : context.runtimePrimaryIp);
        response.setIdentityStatus(context == null ? "UNKNOWN" : context.identityStatus);
        response.setIdentityWarning(context == null ? null : context.identityWarning);
        response.setEffectiveEndpoints(createEffectiveProtocolEndpoints(protocol, context, listenerPort, wildcard));
        response.setRuntimeState(!protocol.isEnabled() ? "DISABLED" : protocol.getState().name().toUpperCase(Locale.ROOT));
        final Map<Integer, Integer> linkedCounts = context == null ? null : context.linkedResourceCounts.get(protocol.getProtocol());
        response.setLinkedResourceCount(linkedCounts == null ? 0 : linkedCounts.getOrDefault(listenerPort, 0));
        if (protocol.getProtocol() == StorageServiceInstance.Protocol.NFS) {
            response.setProtocolMode(nfsProtocolModeAsString(parseJsonObject(protocol.getConfigJson()), protocol.getConfigJson()));
            final String desiredIdMode=normalizeNfsIdMappingMode(getJsonString(parseJsonObject(protocol.getConfigJson()),"idMappingMode"));
            final String runtimeIdMode=context == null ? "UNKNOWN" : context.nfsRuntimeIdMappingMode;
            response.setIdMappingMode(desiredIdMode);response.setRuntimeIdMappingMode(runtimeIdMode);
            response.setEffectiveIdMappingMode("NUMERIC".equals(runtimeIdMode) || "NAME_DOMAIN".equals(runtimeIdMode) ? runtimeIdMode : "UNKNOWN");
            response.setIdMappingDrift("UNKNOWN".equals(runtimeIdMode) ? "UNKNOWN" : desiredIdMode.equals(runtimeIdMode) ? "CONSISTENT" : "DRIFT");
        }
        response.setObjectName("storageserviceprotocol");
        return response;
    }

    protected ProtocolResponseContext buildProtocolResponseContext(final long instanceId) {
        final ProtocolResponseContext context = new ProtocolResponseContext();
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(instanceId);
        if (instance != null && instance.getVmId() != null) {
            final List<NicVO> nics = nicDao.listByVmIdOrderByDeviceId(instance.getVmId());
            for (final NicVO nic : nics) {
                if (nic == null || StringUtils.isBlank(nic.getIPv4Address())) {
                    continue;
                }
                if (context.primaryIp == null || nic.isDefaultNic()) {
                    context.primaryIp = nic.getIPv4Address();
                }
                addUniqueServiceIp(context.serviceIps, nic.getIPv4Address());
                for (final NicSecondaryIpVO secondaryIp : nicSecondaryIpDao.listByNicId(nic.getId())) {
                    if (secondaryIp != null) {
                        context.aliasIps.add(secondaryIp.getIp4Address());
                        addUniqueServiceIp(context.serviceIps, secondaryIp.getIp4Address());
                    }
                }
            }
            context.runtimePrimaryIp = observeRuntimePrimaryIp(instance,context);
            addUniqueServiceIp(context.serviceIps, context.runtimePrimaryIp);
            if (StringUtils.isBlank(context.runtimePrimaryIp)) {
                context.identityStatus = "UNKNOWN";
            } else if (StringUtils.equals(context.primaryIp, context.runtimePrimaryIp)) {
                context.identityStatus = "CONSISTENT";
            } else {
                context.identityStatus = "DRIFT";
                context.identityWarning = String.format("Persisted primary IPv4 %s differs from runtime primary IPv4 %s",
                        StringUtils.defaultIfBlank(context.primaryIp, "-"), context.runtimePrimaryIp);
            }
        }
        populateLinkedResourceCounts(context, instanceId);
        return context;
    }

    protected void addUniqueServiceIp(final List<String> serviceIps, final String ipAddress) {
        if (StringUtils.isNotBlank(ipAddress) && !serviceIps.contains(ipAddress)) {
            serviceIps.add(ipAddress);
        }
    }

    protected String observeRuntimePrimaryIp(final StorageServiceInstanceVO instance) {
        return observeRuntimePrimaryIp(instance,null);
    }

    protected String observeRuntimePrimaryIp(final StorageServiceInstanceVO instance,final ProtocolResponseContext context) {
        if (instance == null || instance.getVmId() == null) {
            return null;
        }
        try {
            final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                    "health", "", StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
            if (!result.isSuccess() || StringUtils.isBlank(result.getResultJson())) {
                return null;
            }
            final JsonElement root = new JsonParser().parse(normalizeRuntimeResultJson(result.getResultJson()));
            if(context != null && root.isJsonObject()) {
                context.nfsRuntimeIdMappingMode=StringUtils.defaultIfBlank(getJsonString(getJsonObject(root.getAsJsonObject(),"nfsGanesha"),"idMappingMode"),"UNKNOWN");
            }
            final List<String> candidates = new ArrayList<>();
            collectRuntimePrimaryIps(root, candidates);
            return candidates.stream().filter(StringUtils::isNotBlank).distinct().count() == 1
                    ? candidates.stream().filter(StringUtils::isNotBlank).distinct().findFirst().orElse(null)
                    : null;
        } catch (final RuntimeException e) {
            logger.debug("Unable to observe runtime primary IPv4 for Storage Service instance [{}]", instance.getUuid(), e);
            return null;
        }
    }

    protected void collectRuntimePrimaryIps(final JsonElement element, final List<String> candidates) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (final JsonElement child : element.getAsJsonArray()) {
                collectRuntimePrimaryIps(child, candidates);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        final JsonObject object = element.getAsJsonObject();
        final String address = firstNonBlankJsonString(object, "ipaddress", "ipAddress", "ip");
        final String role = getJsonString(object, "role");
        final Boolean primary = getJsonBoolean(object, "primary");
        final Boolean secondary = getJsonBoolean(object, "secondary");
        if (StringUtils.isNotBlank(address) && ("primary".equalsIgnoreCase(role) || Boolean.TRUE.equals(primary) || Boolean.FALSE.equals(secondary))) {
            addUniqueServiceIp(candidates, address);
        }
        for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
            collectRuntimePrimaryIps(entry.getValue(), candidates);
        }
    }

    protected String firstNonBlankJsonString(final JsonObject object, final String... names) {
        for (final String name : names) {
            final String value = getJsonString(object, name);
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    @Override
    public StorageServiceRuntimeResponse repairStorageServiceNicIdentity(final RepairStorageServiceNicIdentityCmd cmd) {
        final SharedFSVO sharedFileSystem = sharedFSDao.findById(cmd.getSharedFileSystemId());
        if (sharedFileSystem == null || sharedFileSystem.getVmId() == null) {
            throw new InvalidParameterValueException("Unable to resolve a Storage Service System VM for the Shared FileSystem");
        }
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findByVmId(sharedFileSystem.getVmId());
        if (instance == null) {
            throw new InvalidParameterValueException("Unable to resolve the Storage Service instance for the Shared FileSystem");
        }
        final NicVO defaultNic = nicDao.findDefaultNicForVM(instance.getVmId());
        final String runtimePrimary = observeRuntimePrimaryIp(instance);
        final String persistedPrimary = defaultNic == null ? null : defaultNic.getIPv4Address();
        final List<String> aliases = defaultNic == null ? Collections.emptyList() : nicSecondaryIpDao.listByNicId(defaultNic.getId()).stream()
                .map(NicSecondaryIpVO::getIp4Address)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        final boolean eligible = defaultNic != null && StringUtils.isNotBlank(runtimePrimary) && StringUtils.isNotBlank(persistedPrimary)
                && !StringUtils.equals(runtimePrimary, persistedPrimary) && aliases.contains(persistedPrimary);
        final String reason;
        if (defaultNic == null) {
            reason = "DEFAULT_NIC_NOT_FOUND";
        } else if (StringUtils.isBlank(runtimePrimary)) {
            reason = "RUNTIME_PRIMARY_AMBIGUOUS_OR_UNAVAILABLE";
        } else if (StringUtils.equals(runtimePrimary, persistedPrimary)) {
            reason = "ALREADY_CONSISTENT";
        } else if (!aliases.contains(persistedPrimary)) {
            reason = "PERSISTED_PRIMARY_IS_NOT_A_REGISTERED_ALIAS";
        } else {
            reason = "ELIGIBLE";
        }

        final JsonObject resultJson = new JsonObject();
        resultJson.addProperty("sharedFileSystemId", sharedFileSystem.getUuid());
        resultJson.addProperty("instanceId", instance.getUuid());
        resultJson.addProperty("nicId", defaultNic == null ? null : defaultNic.getUuid());
        resultJson.addProperty("persistedPrimaryIp", persistedPrimary);
        resultJson.addProperty("runtimePrimaryIp", runtimePrimary);
        resultJson.add("aliases", GSON.toJsonTree(aliases));
        resultJson.addProperty("eligible", eligible);
        resultJson.addProperty("reason", reason);
        resultJson.addProperty("dryRun", cmd.isDryRun());

        if (cmd.isDryRun()) {
            return createRuntimeResponse(instance, "repair nic identity", eligible, eligible ? "ELIGIBLE" : "INELIGIBLE", reason, GSON.toJson(resultJson));
        }
        if (!eligible) {
            throw new InvalidParameterValueException("Storage Service NIC identity repair is not eligible: " + reason);
        }
        if (!StringUtils.equals(runtimePrimary, cmd.getExpectedRuntimePrimary())) {
            throw new InvalidParameterValueException("expectedruntimeprimary must match the currently observed runtime primary IPv4");
        }
        if (!nicDao.updatePrimaryIpAddress(defaultNic.getId(), runtimePrimary, persistedPrimary)) {
            throw new CloudRuntimeException("Storage Service NIC identity changed while applying the guarded repair");
        }
        final NicVO repairedNic = nicDao.findById(defaultNic.getId());
        final List<String> repairedAliases = nicSecondaryIpDao.listByNicId(defaultNic.getId()).stream()
                .map(NicSecondaryIpVO::getIp4Address)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        if (repairedNic == null || !StringUtils.equals(runtimePrimary, repairedNic.getIPv4Address())) {
            throw new CloudRuntimeException("Storage Service NIC identity repair postcondition failed: persisted primary IPv4 was not updated");
        }
        if (!aliases.equals(repairedAliases)) {
            throw new CloudRuntimeException("Storage Service NIC identity repair postcondition failed: alias addresses changed unexpectedly");
        }
        CallContext.current().setEventResourceId(sharedFileSystem.getId());
        resultJson.addProperty("repaired", true);
        resultJson.addProperty("identityStatus", "CONSISTENT");
        resultJson.addProperty("persistedPrimaryIpAfter", repairedNic.getIPv4Address());
        resultJson.add("aliasesAfter", GSON.toJsonTree(repairedAliases));
        resultJson.addProperty("aliasesPreserved", true);
        return createRuntimeResponse(instance, "repair nic identity", true, "REPAIRED",
                "Updated only the persisted NIC primary IPv4; guest networking and aliases were preserved", GSON.toJson(resultJson));
    }

    protected List<StorageServiceProtocolEndpointResponse> createEffectiveProtocolEndpoints(final StorageServiceProtocolVO protocol,
            final ProtocolResponseContext context, final int port, final boolean wildcard) {
        final List<String> addresses = new ArrayList<>();
        if (wildcard && context != null) {
            addresses.addAll(context.serviceIps);
        } else {
            addUniqueServiceIp(addresses, protocol.getListenIp());
        }
        final List<StorageServiceProtocolEndpointResponse> endpoints = new ArrayList<>();
        for (final String address : addresses) {
            final StorageServiceProtocolEndpointResponse endpoint = new StorageServiceProtocolEndpointResponse();
            endpoint.setIpAddress(address);
            endpoint.setPort(port);
            endpoint.setRole(wildcard ? (address.equals(StringUtils.defaultIfBlank(context.runtimePrimaryIp, context.primaryIp)) ? "PRIMARY" : "ALIAS") : "DEDICATED");
            endpoint.setObjectName("storageserviceprotocolendpoint");
            endpoints.add(endpoint);
        }
        return endpoints;
    }

    protected void populateLinkedResourceCounts(final ProtocolResponseContext context, final long instanceId) {
        for (final StorageServiceInstance.Protocol protocol : Arrays.asList(StorageServiceInstance.Protocol.NFS, StorageServiceInstance.Protocol.SMB)) {
            for (final StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instanceId, protocol)) {
                addLinkedResourcePorts(context, protocol, share.getConfigJson());
            }
        }
        for (final StorageServiceInstance.Protocol protocol : Arrays.asList(StorageServiceInstance.Protocol.ISCSI, StorageServiceInstance.Protocol.NVME_OF)) {
            for (final StorageBlockTargetVO target : storageBlockTargetDao.listByInstanceIdAndProtocol(instanceId, protocol)) {
                addLinkedResourcePorts(context, protocol, target.getConfigJson());
            }
        }
    }

    protected void addLinkedResourcePorts(final ProtocolResponseContext context, final StorageServiceInstance.Protocol protocol, final String configJson) {
        final Map<Integer, Integer> counts = context.linkedResourceCounts.computeIfAbsent(protocol, ignored -> new HashMap<>());
        final JsonObject config = parseJsonObject(configJson);
        final String listenerPorts = listenerPortsAsString(config);
        if (StringUtils.isBlank(listenerPorts)) {
            counts.merge(defaultProtocolPort(protocol), 1, Integer::sum);
            return;
        }
        for (final String value : StringUtils.split(listenerPorts, ',')) {
            try {
                counts.merge(Integer.parseInt(StringUtils.trim(value)), 1, Integer::sum);
            } catch (final NumberFormatException ignored) {
                // Invalid resource config is surfaced by the resource API; it must not break listener inventory.
            }
        }
    }

    protected StorageNfsExportResponse createExportResponse(final StorageFileShareVO share) {
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(share.getInstanceId());
        return createExportResponse(share, instance,
                fileShareVolumeRuntimeObservation(share, loadFileShareVolumeRuntimeObservations(instance)));
    }

    protected StorageNfsExportResponse createExportResponse(final StorageFileShareVO share, final StorageServiceInstanceVO instance,
            final JsonObject runtimeObservation) {
        final StorageNfsExportResponse response = new StorageNfsExportResponse();
        response.setId(share.getUuid());
        response.setInstanceId(instance == null ? null : instance.getUuid());
        response.setName(share.getName());
        response.setPath(share.getPath());
        VolumeVO volume = null;
        if (share.getVolumeId() != null) {
            volume = volumeDao.findById(share.getVolumeId());
            response.setVolumeId(volume == null ? null : volume.getUuid());
        }
        response.setFilesystem(share.getFilesystem());
        response.setQuotaBytes(share.getQuotaBytes());
        response.setState(share.getState().name());
        response.setConfig(share.getConfigJson());
        final boolean configValid = isJsonObjectConfigValid(share.getConfigJson());
        response.setConfigValid(configValid);
        if (!configValid) {
            response.setConfigError("INVALID_JSON_CONFIG");
        }
        final JsonObject config = parseJsonObject(share.getConfigJson());
        populateFileShareVolumeResponse(response, volume, config, runtimeObservation);
        response.setEndpointMode(nfsEndpointModeAsString(config, share.getConfigJson()));
        response.setListenIps(nfsSelectedListenIpsAsString(config, share.getConfigJson()));
        response.setListenerPorts(nfsListenerPortsAsString(config));
        response.setProtocolMode(instance == null ? nfsProtocolModeAsString(config, share.getConfigJson()) : resolveNfsServiceProtocolMode(instance));
        if (share.getPosixPolicyId() != null) response.setPosixPolicyId(requirePosixDirectoryPolicy(share.getPosixPolicyId()).getUuid());
        response.setObjectName("storagenfsexport");
        return response;
    }

    protected StorageFileShareResponse createFileShareResponse(final StorageFileShareVO share) {
        final StorageFileShareResponse response = new StorageFileShareResponse();
        response.setId(share.getUuid());
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(share.getInstanceId());
        response.setInstanceId(instance == null ? null : instance.getUuid());
        response.setProtocol(share.getProtocol().name());
        response.setName(share.getName());
        response.setPath(share.getPath());
        VolumeVO volume = null;
        if (share.getVolumeId() != null) {
            volume = volumeDao.findById(share.getVolumeId());
            response.setVolumeId(volume == null ? null : volume.getUuid());
        }
        response.setFilesystem(share.getFilesystem());
        response.setQuotaBytes(share.getQuotaBytes());
        response.setState(share.getState().name());
        response.setConfig(share.getConfigJson());
        final JsonObject config = parseJsonObject(share.getConfigJson());
        populateFileShareVolumeResponse(response, volume, config,
                fileShareVolumeRuntimeObservation(share, loadFileShareVolumeRuntimeObservations(instance)));
        response.setObjectName("storagefileshare");
        return response;
    }

    protected StorageSmbShareResponse createSmbShareResponse(final StorageFileShareVO share) {
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(share.getInstanceId());
        return createSmbShareResponse(share, instance,
                fileShareVolumeRuntimeObservation(share, loadFileShareVolumeRuntimeObservations(instance)));
    }

    protected StorageSmbShareResponse createSmbShareResponse(final StorageFileShareVO share, final StorageServiceInstanceVO instance,
            final JsonObject runtimeObservation) {
        final StorageSmbShareResponse response = new StorageSmbShareResponse();
        response.setId(share.getUuid());
        response.setInstanceId(instance == null ? null : instance.getUuid());
        response.setName(share.getName());
        response.setPath(share.getPath());
        VolumeVO volume = null;
        if (share.getVolumeId() != null) {
            volume = volumeDao.findById(share.getVolumeId());
            response.setVolumeId(volume == null ? null : volume.getUuid());
        }
        response.setFilesystem(share.getFilesystem());
        response.setQuotaBytes(share.getQuotaBytes());
        response.setState(share.getState().name());
        response.setConfig(share.getConfigJson());
        populateFileShareVolumeResponse(response, volume, parseJsonObject(share.getConfigJson()), runtimeObservation);
        List<String> sources=new ArrayList<>();
        for (StorageAccessRuleVO rule:storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE,share.getId())) {
            if (isSmbNetworkRule(rule) && rule.getState()!=StorageServiceInstance.ResourceState.Disabled && rule.getState()!=StorageServiceInstance.ResourceState.Destroyed && rule.getState()!=StorageServiceInstance.ResourceState.Error) sources.add(rule.getPrincipal());
        }
        response.setAllowedSources(sources);response.setNetworkAccessMode(sources.isEmpty() ? "ANY_SOURCE" : "ALLOW_LIST");
        final JsonObject normalized = SmbCreationPolicy.merge(parseJsonObject(share.getConfigJson()), null, null, null, null, null, null);
        final JsonObject desiredCreation = new JsonObject();
        for (String key : new String[] {"createMask", "forceCreateMode", "directoryMask", "forceDirectoryMode", "inheritPermissions"}) {
            desiredCreation.add(key, normalized.get(key));
        }
        response.setCreationPolicy(GSON.toJson(desiredCreation));
        response.setPosixOwnershipMode(StringUtils.defaultIfBlank(getJsonString(normalized, "posixOwnershipMode"), "AUTHENTICATED_USER"));
        response.setOwnershipInheritance(StringUtils.defaultIfBlank(getJsonString(normalized, "ownershipInheritance"), "AUTHENTICATED_USER"));
        response.setInheritGroup(Boolean.TRUE.equals(getJsonBoolean(normalized, "inheritGroup")));
        response.setCreationPolicyDrift("UNOBSERVED");
        if (runtimeObservation != null && runtimeObservation.has("smbAccess")) {
            final JsonObject access = runtimeObservation.getAsJsonObject("smbAccess");
            response.setEffectiveOwnershipInheritance(getJsonString(access, "ownershipInheritance"));
            response.setEffectivePosixOwnershipMode(getJsonString(access, "posixOwnershipMode"));
            response.setManagedPosixUser(getJsonString(access, "managedUser"));response.setManagedPosixGroup(getJsonString(access, "managedGroup"));
            if (access.has("effectiveUid")) response.setEffectiveOwnerUid(access.get("effectiveUid").getAsLong());
            if (access.has("effectiveGid")) response.setEffectiveOwnerGid(access.get("effectiveGid").getAsLong());
            response.setEffectiveDirectoryMode(getJsonString(access, "effectiveDirectoryMode"));
            if (access.has("creationPolicy") && access.get("creationPolicy").isJsonObject()) {
                final JsonObject effective = access.getAsJsonObject("creationPolicy");
                response.setEffectiveCreationPolicy(GSON.toJson(effective));
                response.setCreationPolicyDrift(desiredCreation.equals(effective) ? "CONSISTENT" : "DRIFT");
            }
        }
        if (share.getPosixPolicyId() != null) response.setPosixPolicyId(requirePosixDirectoryPolicy(share.getPosixPolicyId()).getUuid());
        response.setObjectName("storagesmbshare");
        return response;
    }

    protected RuntimeObservationSnapshot loadFileShareVolumeRuntimeObservations(final StorageServiceInstanceVO instance) {
        final RuntimeObservationSnapshot snapshot = new RuntimeObservationSnapshot();
        if (instance == null || instance.getVmId() == null) {
            snapshot.error = "Storage Service VM is not available";
            return snapshot;
        }
        try {
            final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                    "inventory", "", StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
            if (!result.isSuccess()) {
                logger.warn("Unable to read file-share runtime inventory for Storage Service instance [{}]: {}", instance.getUuid(), result.getDetails());
                snapshot.error = result.getDetails();
                return snapshot;
            }
            final JsonObject inventory = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
            org.apache.cloudstack.storage.sharedfs.query.dao.SharedFSCapacityCache.record(instance.getVmId(),inventory);
            if (!inventory.has("fileShareVolumes") || !inventory.get("fileShareVolumes").isJsonArray()) {
                snapshot.error = "Runtime inventory does not contain fileShareVolumes";
                return snapshot;
            }
            populateRuntimeObservationMetadata(snapshot, inventory, "fileShareVolumes");
            if (inventory.has("smbAccess") && inventory.get("smbAccess").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : inventory.getAsJsonObject("smbAccess").entrySet()) {
                    if (entry.getValue().isJsonObject()) snapshot.sharePolicies.put(entry.getKey(), entry.getValue().getAsJsonObject());
                }
            }
            snapshot.available = true;
            for (final JsonElement element : inventory.getAsJsonArray("fileShareVolumes")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject observation = element.getAsJsonObject();
                final String volumeUuid = getJsonString(observation, "volumeUuid");
                if (StringUtils.isNotBlank(volumeUuid)) {
                    snapshot.observations.put(normalizeVolumeIdentity(volumeUuid), observation);
                }
            }
        } catch (final RuntimeException e) {
            logger.warn("Unable to merge file-share runtime inventory for Storage Service instance [{}]", instance.getUuid(), e);
            snapshot.error = e.getMessage();
        }
        return snapshot;
    }

    protected JsonObject fileShareVolumeRuntimeObservation(final StorageFileShareVO share, final RuntimeObservationSnapshot snapshot) {
        if (snapshot == null || !snapshot.available) {
            return unavailableRuntimeObservation(snapshot);
        }
        if (share == null || share.getVolumeId() == null) {
            return null;
        }
        final VolumeVO volume = volumeDao.findById(share.getVolumeId());
        final JsonObject volumeObservation = volume == null ? null : snapshot.observation(normalizeVolumeIdentity(volume.getUuid()));
        final JsonObject result = volumeObservation == null ? new JsonObject() : volumeObservation.deepCopy();
        if (snapshot.sharePolicies.containsKey(share.getUuid())) result.add("smbAccess", snapshot.sharePolicies.get(share.getUuid()).deepCopy());
        return result;
    }

    protected RuntimeObservationSnapshot loadIscsiTargetRuntimeObservations(final StorageServiceInstanceVO instance) {
        final RuntimeObservationSnapshot snapshot = new RuntimeObservationSnapshot();
        if (instance == null || instance.getVmId() == null) {
            snapshot.error = "Storage Service VM is not available";
            return snapshot;
        }
        try {
            final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                    "inventory", "", StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
            if (!result.isSuccess()) {
                snapshot.error = result.getDetails();
                return snapshot;
            }
            final JsonObject inventory = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
            final JsonObject iscsiInventory = getJsonObject(inventory, "iscsiTargets");
            if (iscsiInventory == null || !iscsiInventory.has("targets") || !iscsiInventory.get("targets").isJsonArray()) {
                snapshot.error = "Runtime inventory does not contain iscsiTargets.targets";
                return snapshot;
            }
            populateRuntimeObservationMetadata(snapshot, inventory, "iscsiTargets");
            snapshot.available = true;
            for (final JsonElement element : iscsiInventory.getAsJsonArray("targets")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject target = element.getAsJsonObject();
                final JsonObject runtime = getJsonObject(target, "runtime");
                if (runtime == null) {
                    continue;
                }
                if (StringUtils.isNotBlank(snapshot.observedAt) && !runtime.has("_observedAt")) {
                    runtime.addProperty("_observedAt", snapshot.observedAt);
                }
                if (StringUtils.isNotBlank(snapshot.bootId) && !runtime.has("_bootId")) {
                    runtime.addProperty("_bootId", snapshot.bootId);
                }
                final String targetName = firstJsonString(target, runtime, "targetName", "targetname");
                final String lun = firstJsonString(target, runtime, "lunOrNamespace", "lunornamespace", "lun");
                snapshot.observations.put(blockRuntimeKey(targetName, lun), target);
            }
        } catch (final RuntimeException e) {
            logger.warn("Unable to merge iSCSI runtime inventory for Storage Service instance [{}]", instance.getUuid(), e);
            snapshot.error = e.getMessage();
        }
        return snapshot;
    }

    protected JsonObject iscsiTargetRuntimeObservation(final StorageBlockTargetVO target, final RuntimeObservationSnapshot snapshot) {
        return target == null || snapshot == null ? null : snapshot.observation(blockRuntimeKey(target.getTargetName(), target.getLunOrNamespace()));
    }

    protected String blockRuntimeKey(final String targetName, final String lunOrNamespace) {
        return StringUtils.lowerCase(StringUtils.trimToEmpty(targetName), Locale.ROOT) + "|" + StringUtils.defaultIfBlank(StringUtils.trim(lunOrNamespace), "0");
    }

    protected void populateRuntimeObservationMetadata(final RuntimeObservationSnapshot snapshot, final JsonObject inventory, final String resourceName) {
        final JsonObject observability = getJsonObject(inventory, "runtimeObservability");
        final JsonObject resource = getJsonObject(observability, resourceName);
        snapshot.observedAt = firstJsonString(resource, inventory, "observedAt", "collectedAt", "generatedAt", "timestamp");
        snapshot.bootId = firstJsonString(resource, inventory, "bootId");
        snapshot.error = firstJsonString(resource, inventory, "error");
    }

    protected JsonObject unavailableRuntimeObservation(final RuntimeObservationSnapshot snapshot) {
        final JsonObject observation = new JsonObject();
        observation.addProperty("mappingStatus", "UNAVAILABLE");
        if (snapshot != null) {
            if (StringUtils.isNotBlank(snapshot.observedAt)) {
                observation.addProperty("observedAt", snapshot.observedAt);
            }
            if (StringUtils.isNotBlank(snapshot.bootId)) {
                observation.addProperty("bootId", snapshot.bootId);
            }
            if (StringUtils.isNotBlank(snapshot.error)) {
                observation.addProperty("mappingError", snapshot.error);
            }
        }
        return observation;
    }

    protected String fileShareFilesystemUuid(final JsonObject config) {
        final JsonObject inspection = getJsonObject(config, "lastInspection");
        return firstJsonString(inspection, config, "filesystemUuid", "fsUuid");
    }

    protected String observedFileShareRelativePath(final JsonObject config) {
        final String explicit = getJsonString(config, "relativeSharePath");
        if (StringUtils.isNotBlank(explicit)) {
            return explicit;
        }
        final String root = StringUtils.removeEnd(getJsonString(config, "volumeMountPath"), "/");
        final String backing = getJsonString(config, "backingPath");
        if (StringUtils.isNotBlank(root) && StringUtils.startsWith(backing, root + "/")) {
            return backing.substring(root.length() + 1);
        }
        return null;
    }

    protected void populateFileShareVolumeResponse(final StorageNfsExportResponse response, final VolumeVO volume,
            final JsonObject config, final JsonObject runtime) {
        response.setVolumeUuid(volume == null ? getJsonString(config, "attachedVolumeUuid") : volume.getUuid());
        response.setFilesystemUuid(fileShareFilesystemUuid(config));
        response.setVolumeMountPath(getJsonString(config, "volumeMountPath"));
        response.setVolumeRelativePath(observedFileShareRelativePath(config));
        response.setBackingPath(getJsonString(config, "backingPath"));
        populateFileShareRuntimeFields(response, runtime);
    }

    protected void populateFileShareVolumeResponse(final StorageSmbShareResponse response, final VolumeVO volume,
            final JsonObject config, final JsonObject runtime) {
        response.setVolumeUuid(volume == null ? getJsonString(config, "attachedVolumeUuid") : volume.getUuid());
        response.setFilesystemUuid(fileShareFilesystemUuid(config));
        response.setVolumeMountPath(getJsonString(config, "volumeMountPath"));
        response.setVolumeRelativePath(observedFileShareRelativePath(config));
        response.setBackingPath(getJsonString(config, "backingPath"));
        response.setRuntimeDevicePath(getJsonString(runtime, "observedDevicePath"));
        response.setRuntimeObservedAt(getJsonString(runtime, "observedAt"));
        response.setRuntimeBootId(getJsonString(runtime, "bootId"));
        response.setRuntimeMatchedBy(getJsonString(runtime, "matchedBy"));
        response.setMappingStatus(StringUtils.defaultIfBlank(getJsonString(runtime, "mappingStatus"), "UNMAPPED"));
    }

    protected void populateFileShareVolumeResponse(final StorageFileShareResponse response, final VolumeVO volume,
            final JsonObject config, final JsonObject runtime) {
        response.setVolumeUuid(volume == null ? getJsonString(config, "attachedVolumeUuid") : volume.getUuid());
        response.setFilesystemUuid(fileShareFilesystemUuid(config));
        response.setVolumeMountPath(getJsonString(config, "volumeMountPath"));
        response.setVolumeRelativePath(observedFileShareRelativePath(config));
        response.setBackingPath(getJsonString(config, "backingPath"));
        response.setRuntimeDevicePath(getJsonString(runtime, "observedDevicePath"));
        response.setRuntimeObservedAt(getJsonString(runtime, "observedAt"));
        response.setRuntimeBootId(getJsonString(runtime, "bootId"));
        response.setRuntimeMatchedBy(getJsonString(runtime, "matchedBy"));
        response.setMappingStatus(StringUtils.defaultIfBlank(getJsonString(runtime, "mappingStatus"), "UNMAPPED"));
    }

    protected void populateFileShareRuntimeFields(final StorageNfsExportResponse response, final JsonObject runtime) {
        response.setRuntimeDevicePath(getJsonString(runtime, "observedDevicePath"));
        response.setRuntimeObservedAt(getJsonString(runtime, "observedAt"));
        response.setRuntimeBootId(getJsonString(runtime, "bootId"));
        response.setRuntimeMatchedBy(getJsonString(runtime, "matchedBy"));
        response.setMappingStatus(StringUtils.defaultIfBlank(getJsonString(runtime, "mappingStatus"), "UNMAPPED"));
    }

    protected StorageIdentityDomainResponse createIdentityDomainResponse(final StorageIdentityDomainVO domain) {
        final StorageIdentityDomainResponse response = new StorageIdentityDomainResponse();
        response.setId(domain.getUuid());
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(domain.getInstanceId());
        response.setInstanceId(instance == null ? null : instance.getUuid());
        response.setDomainName(domain.getDomainName());
        response.setOrganizationalUnit(domain.getOrganizationalUnit());
        response.setDnsServers(domain.getDnsServers());
        response.setJoinState(domain.getJoinState().name());
        response.setHealthState(domain.getHealthState());
        response.setConfig(domain.getConfigJson());
        response.setObjectName("storageidentitydomain");
        return response;
    }

    protected StorageBlockTargetResponse createBlockTargetResponse(final StorageBlockTargetVO target, final String objectName) {
        return createBlockTargetResponse(target, objectName, null, null);
    }

    protected StorageBlockTargetResponse createBlockTargetResponse(final StorageBlockTargetVO target, final String objectName,
            final JsonObject runtimeObservation, final String runtimeMappingStatus) {
        final StorageBlockTargetResponse response = new StorageBlockTargetResponse();
        final JsonObject config = parseJsonObject(target.getConfigJson());
        final boolean iscsiBlockTarget = target.getProtocol() == StorageServiceInstance.Protocol.ISCSI
                && "BLOCK".equalsIgnoreCase(StringUtils.defaultIfBlank(getJsonString(config, "backstoreType"), "BLOCK"));
        final boolean nvmeNamespace = target.getProtocol() == StorageServiceInstance.Protocol.NVME_OF && isNvmeOfNamespace(target);
        final Long lunSize = iscsiBlockTarget || nvmeNamespace ? null : getJsonLong(config, "lunSizeBytes");
        final Long namespaceSize = nvmeNamespace ? getJsonLong(config, "namespaceSizeBytes") : null;
        Long volumeSize = null;
        response.setId(target.getUuid());
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(target.getInstanceId());
        response.setInstanceId(instance == null ? null : instance.getUuid());
        response.setProtocol(target.getProtocol().name());
        response.setTargetName(target.getTargetName());
        response.setLunOrNamespace(target.getLunOrNamespace());
        if (target.getVolumeId() != null) {
            final VolumeVO volume = volumeDao.findById(target.getVolumeId());
            if (volume != null) {
                response.setVolumeId(volume.getUuid());
                response.setVolumeName(volume.getName());
                volumeSize = volume.getSize();
                response.setVolumeSizeBytes(volumeSize);
            }
        }
        response.setLunSizeBytes(lunSize);
        response.setNamespaceSizeBytes(namespaceSize);
        response.setEffectiveSizeBytes(nvmeNamespace ? volumeSize : (lunSize == null ? volumeSize : lunSize));
        response.setBackingPath(getJsonString(config, "backingPath"));
        response.setEndpointMode(getJsonString(config, "endpointMode"));
        response.setListenerPorts(listenerPortsAsString(config));
        response.setBackstoreType(getJsonString(config, "backstoreType"));
        final String endpoints = blockTargetEndpointsAsString(target, config);
        response.setEndpoints(endpoints);
        response.setResolvedEndpoints(endpoints);
        response.setTargetGroupKey(blockTargetGroupKey(target));
        response.setTargetLuns(blockTargetGroupLuns(target));
        response.setTargetLunCount(blockTargetGroupLunCount(target));
        response.setAclCount(blockTargetGroupAclCount(target));
        response.setState(target.getState().name());
        response.setRuntimeState(target.getState().name());
        response.setRuntimeStatus(blockTargetRuntimeStatusJson(target, config, endpoints));
        if (iscsiBlockTarget || nvmeNamespace) {
            final JsonObject runtime = getJsonObject(runtimeObservation, "runtime");
            final String runtimeBackingPath = firstJsonString(runtime, runtimeObservation, "backingPath", "devicePath");
            final String observedState = firstJsonString(runtime, runtimeObservation, "runtimeState", "state");
            final Boolean runtimeEnabled = firstJsonBoolean(runtime, runtimeObservation, "enabled");
            final Long actualBackingSizeBytes = firstJsonLong(runtime, runtimeObservation, "actualSizeBytes", "deviceSizeBytes", "sizeBytes", "effectiveSizeBytes");
            final String runtimeObservedAt = firstJsonString(runtime, runtimeObservation, "_observedAt", "observedAt", "collectedAt");
            response.setRuntimeBackingPath(runtimeBackingPath);
            response.setRuntimeMappingStatus(StringUtils.defaultIfBlank(runtimeMappingStatus, "UNMAPPED"));
            response.setRuntimeEnabled(runtimeEnabled);
            response.setActualBackingSizeBytes(actualBackingSizeBytes);
            response.setRuntimeObservedAt(runtimeObservedAt);
            if (actualBackingSizeBytes != null) {
                response.setEffectiveSizeBytes(actualBackingSizeBytes);
            }
            if (StringUtils.isNotBlank(observedState)) {
                response.setRuntimeState(observedState);
            }
            if (!"EXACT".equals(runtimeMappingStatus)) {
                response.setRuntimeWarnings(target.getProtocol().name() + " runtime mapping is " + StringUtils.defaultIfBlank(runtimeMappingStatus, "UNMAPPED"));
            }
            response.setRuntimeStatus(blockTargetRuntimeStatusJson(target, config, endpoints, runtimeObservation, runtimeMappingStatus));
        }
        response.setConfig(target.getConfigJson());
        response.setObjectName(objectName);
        return response;
    }

    protected Map<String, List<JsonObject>> loadNvmeNamespaceRuntimeObservations(final StorageServiceInstanceVO instance) {
        final Map<String, List<JsonObject>> observations = new LinkedHashMap<>();
        if (instance == null || instance.getVmId() == null) {
            return observations;
        }
        try {
            final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                    "inventory", "", StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
            if (!result.isSuccess()) {
                logger.warn("Unable to read NVMe-oF runtime inventory for Storage Service instance [{}]: {}", instance.getUuid(), result.getDetails());
                return observations;
            }
            final JsonObject inventory = parseJsonObject(normalizeRuntimeResultJson(result.getResultJson()));
            final JsonObject nvmeInventory = getJsonObject(inventory, "nvmeofSubsystems") != null
                    ? getJsonObject(inventory, "nvmeofSubsystems") : getJsonObject(inventory, "nvmeOfSubsystems");
            if (nvmeInventory == null || !nvmeInventory.has("subsystems") || !nvmeInventory.get("subsystems").isJsonArray()) {
                return observations;
            }
            final String observedAt = firstJsonString(null, inventory, "collectedAt", "generatedAt", "timestamp");
            for (final JsonElement subsystemElement : nvmeInventory.getAsJsonArray("subsystems")) {
                if (!subsystemElement.isJsonObject()) {
                    continue;
                }
                final JsonObject subsystem = subsystemElement.getAsJsonObject();
                final String subsystemNqn = firstJsonString(null, subsystem, "targetName", "subsystemNqn", "nqn");
                if (!subsystem.has("namespaces") || !subsystem.get("namespaces").isJsonArray()) {
                    continue;
                }
                for (final JsonElement namespaceElement : subsystem.getAsJsonArray("namespaces")) {
                    if (!namespaceElement.isJsonObject()) {
                        continue;
                    }
                    final JsonObject namespace = namespaceElement.getAsJsonObject();
                    final String namespaceId = firstJsonString(null, namespace, "lunOrNamespace", "namespaceId", "nsid");
                    if (StringUtils.isBlank(subsystemNqn) || StringUtils.isBlank(namespaceId)) {
                        continue;
                    }
                    if (StringUtils.isNotBlank(observedAt)) {
                        namespace.addProperty("_observedAt", observedAt);
                    }
                    observations.computeIfAbsent(nvmeNamespaceRuntimeKey(subsystemNqn, namespaceId), key -> new ArrayList<>()).add(namespace);
                }
            }
        } catch (final RuntimeException e) {
            logger.warn("Unable to merge NVMe-oF runtime inventory for Storage Service instance [{}]", instance.getUuid(), e);
        }
        return observations;
    }

    protected String nvmeNamespaceRuntimeKey(final String subsystemNqn, final String namespaceId) {
        final String normalizedNqn = StringUtils.trimToEmpty(subsystemNqn).toLowerCase(Locale.ROOT);
        String normalizedNamespaceId = StringUtils.defaultIfBlank(StringUtils.trim(namespaceId), "1");
        if (normalizedNamespaceId.matches("^[0-9]+$")) {
            normalizedNamespaceId = normalizedNamespaceId.replaceFirst("^0+(?!$)", "");
        }
        return normalizedNqn + "|" + normalizedNamespaceId;
    }

    protected String firstJsonString(final JsonObject primary, final JsonObject secondary, final String... keys) {
        for (final String key : keys) {
            final String primaryValue = getJsonString(primary, key);
            if (StringUtils.isNotBlank(primaryValue)) {
                return primaryValue;
            }
            final String secondaryValue = getJsonString(secondary, key);
            if (StringUtils.isNotBlank(secondaryValue)) {
                return secondaryValue;
            }
        }
        return null;
    }

    protected Long firstJsonLong(final JsonObject primary, final JsonObject secondary, final String... keys) {
        for (final String key : keys) {
            final Long primaryValue = getJsonLong(primary, key);
            if (primaryValue != null) {
                return primaryValue;
            }
            final Long secondaryValue = getJsonLong(secondary, key);
            if (secondaryValue != null) {
                return secondaryValue;
            }
        }
        return null;
    }

    protected Boolean firstJsonBoolean(final JsonObject primary, final JsonObject secondary, final String... keys) {
        for (final String key : keys) {
            final Boolean primaryValue = getJsonBoolean(primary, key);
            if (primaryValue != null) {
                return primaryValue;
            }
            final Boolean secondaryValue = getJsonBoolean(secondary, key);
            if (secondaryValue != null) {
                return secondaryValue;
            }
        }
        return null;
    }

    protected String blockTargetRuntimeStatusJson(final StorageBlockTargetVO target, final JsonObject config, final String endpoints,
            final JsonObject runtimeObservation, final String runtimeMappingStatus) {
        final JsonObject status = parseJsonObject(blockTargetRuntimeStatusJson(target, config, endpoints));
        status.addProperty("mappingStatus", StringUtils.defaultIfBlank(runtimeMappingStatus, "UNMAPPED"));
        if (runtimeObservation != null) {
            status.add("observation", runtimeObservation);
        }
        return GSON.toJson(status);
    }

    protected String blockTargetRuntimeStatusJson(final StorageBlockTargetVO target, final JsonObject config, final String endpoints) {
        final JsonObject status = new JsonObject();
        status.addProperty("state", target.getState().name());
        if (StringUtils.isNotBlank(endpoints)) {
            status.addProperty("resolvedEndpoints", endpoints);
        }
        final String listenerPorts = listenerPortsAsString(config);
        if (StringUtils.isNotBlank(listenerPorts)) {
            status.addProperty("listenerPorts", listenerPorts);
        }
        if ((target.getProtocol() == StorageServiceInstance.Protocol.NVME_OF && isNvmeOfNamespace(target))
                || target.getProtocol() == StorageServiceInstance.Protocol.ISCSI) {
            status.addProperty("runtimeSource", "monitor-cache");
        }
        return GSON.toJson(status);
    }

    protected String blockTargetGroupKey(final StorageBlockTargetVO target) {
        if (target == null || target.getProtocol() == null) {
            return null;
        }
        if (target.getProtocol() == StorageServiceInstance.Protocol.ISCSI) {
            return target.getTargetName();
        }
        return target.getUuid();
    }

    protected List<StorageBlockTargetVO> listBlockTargetGroup(final StorageBlockTargetVO target) {
        if (target == null) {
            return Collections.emptyList();
        }
        if (target.getProtocol() != StorageServiceInstance.Protocol.ISCSI || StringUtils.isBlank(target.getTargetName())) {
            return Collections.singletonList(target);
        }
        final List<StorageBlockTargetVO> group = new ArrayList<>();
        for (final StorageBlockTargetVO candidate : storageBlockTargetDao.listByInstanceIdAndProtocol(target.getInstanceId(), target.getProtocol())) {
            if (candidate != null && target.getTargetName().equals(candidate.getTargetName())) {
                group.add(candidate);
            }
        }
        return group.isEmpty() ? Collections.singletonList(target) : group;
    }

    protected String blockTargetGroupLuns(final StorageBlockTargetVO target) {
        final List<String> luns = new ArrayList<>();
        for (final StorageBlockTargetVO candidate : listBlockTargetGroup(target)) {
            final String lun = StringUtils.defaultIfBlank(candidate.getLunOrNamespace(), "0");
            if (!luns.contains(lun)) {
                luns.add(lun);
            }
        }
        return luns.isEmpty() ? null : StringUtils.join(luns, ',');
    }

    protected Integer blockTargetGroupLunCount(final StorageBlockTargetVO target) {
        final String luns = blockTargetGroupLuns(target);
        return StringUtils.isBlank(luns) ? 0 : luns.split(",").length;
    }

    protected Integer blockTargetGroupAclCount(final StorageBlockTargetVO target) {
        int count = 0;
        for (final StorageBlockTargetVO candidate : listBlockTargetGroup(target)) {
            count += storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET, candidate.getId()).size();
        }
        return count;
    }

    protected String listenerPortsAsString(final JsonObject config) {
        if (config == null || !config.has("listenerGroupPorts") || !config.get("listenerGroupPorts").isJsonArray()) {
            return null;
        }
        final List<String> ports = new ArrayList<>();
        for (final JsonElement element : config.getAsJsonArray("listenerGroupPorts")) {
            if (element != null && !element.isJsonNull()) {
                ports.add(element.getAsString());
            }
        }
        return ports.isEmpty() ? null : StringUtils.join(ports, ',');
    }

    protected String blockTargetEndpointsAsString(final StorageBlockTargetVO target, final JsonObject config) {
        if (target == null || target.getProtocol() == null) {
            return null;
        }
        final StorageServiceInstanceVO instance = storageServiceInstanceDao.findById(target.getInstanceId());
        if (instance == null) {
            return null;
        }
        final HashSet<Integer> targetPorts = new HashSet<>();
        final JsonArray configuredPorts = config != null && config.has("listenerGroupPorts") && config.get("listenerGroupPorts").isJsonArray() ? config.getAsJsonArray("listenerGroupPorts") : new JsonArray();
        for (final JsonElement element : configuredPorts) {
            if (element != null && !element.isJsonNull()) {
                targetPorts.add(element.getAsInt());
            }
        }
        final int defaultPort = target.getProtocol() == StorageServiceInstance.Protocol.ISCSI ? 3260 : 4420;
        if (targetPorts.isEmpty()) {
            targetPorts.add(defaultPort);
        }
        final List<String> endpoints = new ArrayList<>();
        for (final StorageServiceProtocolVO protocol : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), target.getProtocol())) {
            if (protocol == null || !protocol.isEnabled()) {
                continue;
            }
            final int port = protocol.getPort() == null ? defaultPort : protocol.getPort();
            if (!targetPorts.contains(port)) {
                continue;
            }
            endpoints.add(StringUtils.defaultIfBlank(protocol.getListenIp(), "0.0.0.0") + ":" + port);
        }
        if (endpoints.isEmpty()) {
            endpoints.add("0.0.0.0:" + defaultPort);
        }
        return StringUtils.join(endpoints, ',');
    }

    protected StorageAccessRuleResponse createAclResponse(final StorageAccessRuleVO rule) {
        final StorageAccessRuleResponse response = new StorageAccessRuleResponse();
        response.setId(rule.getUuid());
        response.setResourceType(rule.getResourceType().name());
        if (rule.getResourceType() == StorageServiceInstance.AccessResourceType.FILE_SHARE) {
            final StorageFileShareVO share = storageFileShareDao.findById(rule.getResourceId());
            response.setResourceId(share == null ? String.valueOf(rule.getResourceId()) : share.getUuid());
        } else if (rule.getResourceType() == StorageServiceInstance.AccessResourceType.BLOCK_TARGET) {
            final StorageBlockTargetVO target = storageBlockTargetDao.findById(rule.getResourceId());
            response.setResourceId(target == null ? String.valueOf(rule.getResourceId()) : target.getUuid());
            if (target != null) {
                response.setTargetName(target.getTargetName());
                response.setTargetGroupKey(blockTargetGroupKey(target));
                response.setTargetLuns(blockTargetGroupLuns(target));
            }
        } else {
            response.setResourceId(String.valueOf(rule.getResourceId()));
        }
        response.setPrincipalType(rule.getPrincipalType().name());
        response.setPrincipal(rule.getPrincipal());
        response.setPermission(rule.getPermission().name());
        response.setState(rule.getState().name());
        response.setConfig(rule.getConfigJson());
        response.setObjectName("storageaccessrule");
        return response;
    }

    @Override
    public String getConfigComponentName() {
        return StorageServiceInstance.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
                StorageServiceInstance.StorageServiceCommandTimeout,
                StorageServiceInstance.StorageServiceVerifiedConfigurationEnabled,
                StorageServiceInstance.StorageServiceTemplateRollbackRetentionHours,
                StorageServiceInstance.StorageServiceFormatMinimumTimeout,
                StorageServiceInstance.StorageServiceFormatSecondsPerTiB,
                StorageServiceInstance.StorageServiceFormatMaximumTimeout,
                StorageServiceInstance.StorageServiceRuntimeTrustedKeyDirectory
        };
    }
}
