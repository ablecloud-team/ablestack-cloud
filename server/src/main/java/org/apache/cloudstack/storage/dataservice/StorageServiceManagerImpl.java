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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RepairStorageServiceNicIdentityCmd;
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
import com.google.gson.JsonPrimitive;

public class StorageServiceManagerImpl extends ManagerBase implements StorageService, PluggableService, Configurable {
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
        return commands;
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
            if (cmd.getName() != null && !cmd.getName().equals(instance.getName())) {
                continue;
            }
            responses.add(createInstanceResponse(instance));
        }
        final ListResponse<StorageServiceInstanceResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
    }

    @Override
    public StorageServiceProtocolResponse enableStorageServiceProtocol(final EnableStorageServiceProtocolCmd cmd) {
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final StorageServiceInstance.Protocol protocol = parseProtocol(cmd.getProtocol());
        final Integer port = normalizeStorageServiceProtocolPort(protocol, cmd.getPort());
        StorageServiceProtocolVO protocolVO = isEndpointProtocol(protocol) ?
                findProtocolEndpoint(instance.getId(), protocol, cmd.getListenIp(), port) :
                storageServiceProtocolDao.findByInstanceIdAndProtocol(instance.getId(), protocol);
        final StorageServiceProtocolVO modeProtocol = protocol == StorageServiceInstance.Protocol.NFS ?
                selectNfsModeProtocol(storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocol)) : protocolVO;
        final String protocolMode = resolveProtocolModeForEnable(protocol, modeProtocol, cmd.getProtocolMode());
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
            storageServiceInstanceDao.listAll().forEach(instance ->
                    protocols.addAll(storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), protocol)));
        } else {
            storageServiceInstanceDao.listAll().forEach(instance ->
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
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        final String protocolMode = resolveNfsServiceProtocolMode(instance);
        validateNfsRequestedProtocolMode(cmd.getProtocolMode(), protocolMode);
        validateNfsEndpointPolicyForMode(protocolMode, cmd.getEndpointMode(), cmd.getListenIps(), cmd.getListenerPorts());
        validateNfsListenerPortsExist(instance, protocolMode, cmd.getListenerPorts());
        validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "NFS export");
        final VolumeVO requestedVolume = cmd.getVolumeId() == null ? null : requireVolume(cmd.getVolumeId());
        validateFileShareFilesystem(cmd.getFilesystem(), cmd.getImportMode());
        final String path = resolveNfsExportPath(cmd.getPath(), cmd.getName());
        validateNfsExportName(cmd.getName());
        validateNfsExportPath(path, cmd.getName());
        validateFileSharePathAvailable(instance, path, null, cmd.getVolumeId(), "NFS export");
        String configJson = buildNfsConfigJson(null, cmd.getReadOnly(), cmd.getRootSquash(), cmd.getAllSquash(), cmd.getAnonUid(), cmd.getAnonGid(),
                cmd.getOwnerUid(), cmd.getOwnerGid(), cmd.getMode(), cmd.getRecursivePermission(), cmd.getSync(), cmd.getSecure(),
                cmd.getEndpointMode(), cmd.getListenIps(), cmd.getListenerPorts(), protocolMode, true);
        configJson = buildFileShareDirectoryConfigJson(configJson, requestedVolume, cmd.getImportMode(), cmd.getCreateDirectory());
        validateJsonObjectConfigOrThrow(configJson, "NFS export " + cmd.getName());
        StorageFileShareVO share = new StorageFileShareVO(instance.getId(), StorageServiceInstance.Protocol.NFS, cmd.getName(), path,
                cmd.getVolumeId(), cmd.getFilesystem(), cmd.getQuotaBytes(), StorageServiceInstance.ResourceState.Creating,
                configJson);
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
        final StorageFileShareVO share = requireNfsExport(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        final String protocolMode = resolveNfsServiceProtocolMode(instance);
        validateNfsRequestedProtocolMode(cmd.getProtocolMode(), protocolMode);
        validateNfsEndpointPolicyForMode(protocolMode, cmd.getEndpointMode(), cmd.getListenIps(), cmd.getListenerPorts());
        validateNfsListenerPortsExist(instance, protocolMode, cmd.getListenerPorts());
        if (cmd.getName() != null) {
            validateNfsExportName(cmd.getName());
            share.setName(cmd.getName());
        }
        if (cmd.getName() != null || cmd.getPath() != null || cmd.getRelativePath() != null || cmd.getVolumeId() != null) {
            final Long effectiveVolumeId = cmd.getVolumeId() == null ? share.getVolumeId() : cmd.getVolumeId();
            final String path = resolveNfsExportPath(cmd.getPath(), share.getName());
            validateNfsExportPath(path, share.getName());
            validateFileSharePathAvailable(instance, path, share.getId(), effectiveVolumeId, "NFS export");
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
        validateJsonObjectConfigOrThrow(share.getConfigJson(), "NFS export " + share.getUuid());
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
        final StorageFileShareVO share = requireNfsExport(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
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
            if (share == null || share.getProtocol() != StorageServiceInstance.Protocol.NFS) {
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
        final StorageServiceInstanceVO instance = requireInstance(cmd.getInstanceId());
        validateSmbShareName(cmd.getName());
        final String path = resolveSmbSharePath(cmd.getPath(), cmd.getName());
        validateSmbSharePath(path, cmd.getName());
        validateStorageServiceBackingVolume(instance, cmd.getVolumeId(), "SMB share");
        validateFileSharePathAvailable(instance, path, null, cmd.getVolumeId(), "SMB share", Boolean.TRUE.equals(cmd.getCrossProtocol()));
        validateFileShareFilesystem(cmd.getFilesystem(), cmd.getImportMode());
        final String importMode = StringUtils.defaultIfBlank(cmd.getImportMode(), "MOUNT_EXISTING");
        final VolumeVO backingVolume = cmd.getVolumeId() == null ? null : requireVolume(cmd.getVolumeId());
        String configJson = buildSmbConfigJson(null, cmd.getReadOnly(), cmd.getBrowseable(), cmd.getGuestOk(),
                cmd.getCreateDirectory(), cmd.getCrossProtocol(), cmd.getDirectoryMode());
        configJson = buildFileShareDirectoryConfigJson(configJson, backingVolume, importMode, cmd.getCreateDirectory());
        validateJsonObjectConfigOrThrow(configJson, "SMB share " + cmd.getName());
        StorageFileShareVO share = new StorageFileShareVO(instance.getId(), StorageServiceInstance.Protocol.SMB, cmd.getName(), path,
                cmd.getVolumeId(), cmd.getFilesystem(), cmd.getQuotaBytes(), StorageServiceInstance.ResourceState.Creating,
                configJson);
        share = storageFileShareDao.persist(share);
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
        final StorageFileShareVO share = requireSmbShare(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        if (cmd.getName() != null) {
            validateSmbShareName(cmd.getName());
            share.setName(cmd.getName());
        }
        if (cmd.getPath() != null) {
            final String path = resolveSmbSharePath(cmd.getPath(), StringUtils.defaultIfBlank(share.getName(), cmd.getName()));
            validateSmbSharePath(path, StringUtils.defaultIfBlank(share.getName(), cmd.getName()));
            validateFileSharePathAvailable(instance, path, share.getId(), cmd.getVolumeId() == null ? share.getVolumeId() : cmd.getVolumeId(),
                    "SMB share", Boolean.TRUE.equals(cmd.getCrossProtocol()));
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
        configJson = buildFileShareDirectoryConfigJson(configJson, backingVolume, importMode, cmd.getCreateDirectory());
        validateJsonObjectConfigOrThrow(configJson, "SMB share " + share.getUuid());
        share.setConfigJson(configJson);
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
        final StorageFileShareVO share = requireSmbShare(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
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
        final StorageFileShareVO share = requireSmbShare(cmd.getShareId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        final StorageServiceInstance.PrincipalType principalType = parseSmbPrincipalType(cmd.getPrincipalType());
        final StorageServiceInstance.Permission permission = parseSmbPermission(cmd.getPermission());
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
        final StorageAccessRuleVO rule = requireSmbAcl(cmd.getId());
        final StorageFileShareVO share = requireSmbShare(rule.getResourceId());
        final StorageServiceInstanceVO instance = requireInstance(share.getInstanceId());
        if (cmd.getPrincipal() != null) {
            rule.setPrincipal(cmd.getPrincipal());
        }
        if (cmd.getPermission() != null) {
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
            if (share == null || share.getProtocol() != StorageServiceInstance.Protocol.SMB) {
                continue;
            }
            responses.add(createAclResponse(rule));
        }
        final ListResponse<StorageAccessRuleResponse> response = new ListResponse<>();
        response.setResponses(responses, responses.size());
        return response;
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
            cleanupCreatedBackingVolume(instance, volumeId, mountPath);
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
            cleanupCreatedBackingVolume(instance, volumeId, resolveFileShareGuestMountPath(share));
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
            final StorageBlockTargetVO existing = subsystemByNqn.get(target.getTargetName());
            if (existing == null || !isActiveStorageServiceResource(existing.getState()) && isActiveStorageServiceResource(target.getState())) {
                subsystemByNqn.put(target.getTargetName(), target);
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
        final StorageBlockTargetVO namespace = requireNvmeOfNamespace(cmd.getId());
        final StorageServiceInstanceVO instance = requireInstance(namespace.getInstanceId());
        storageBlockTargetDao.remove(namespace.getId());
        applyNvmeOfDesiredState(instance);
        return true;
    }

    @Override
    public StorageAccessRuleResponse createStorageNvmeOfHostAcl(final CreateStorageNvmeOfHostAclCmd cmd) {
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
            export.addProperty("path", share.getPath());
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
            for (final StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
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
            final String relative = normalizeRelativeSharePath(share.getPath());
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
        if (instance.getVmId() == null) {
            logger.debug("Storage Service instance [{}] has no System VM yet; NVMe-oF state is stored but not applied", instance.getUuid());
            return;
        }

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
            listenIps.add(new JsonPrimitive(endpoint));
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
        payload.addProperty("importMode", StringUtils.defaultIfBlank(importMode, "MOUNT_EXISTING").toUpperCase());
        final StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(instance.getVmId(),
                "volume attach inspect", GSON.toJson(payload), StorageServiceInstance.StorageServiceCommandTimeout.value(), Collections.emptySet()));
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
        final JsonObject config = parseJsonObject(GSON.toJson(parseJsonObject(share.getConfigJson())));
        if (!config.has("volumeMountPath") || config.get("volumeMountPath").isJsonNull()) {
            config.addProperty("volumeMountPath", resolveFileShareVolumeMountRoot(instance, volume, share.getPath()));
        }
        config.remove("devicePath");
        final JsonObject inspection = getJsonObject(config, "lastInspection");
        if (inspection != null) {
            final JsonObject commandInspection = parseJsonObject(GSON.toJson(inspection));
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
                throw new CloudRuntimeException("ABLESTACK volume resize returned no volume for id " + volumeId);
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
                if (instance != null && isRuntimeInstanceActive(instance)) {
                    instances.add(instance);
                }
            }
            return instances;
        }
        storageServiceInstanceDao.listAll().forEach(instance -> {
            if (isRuntimeInstanceActive(instance)) {
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
            if (target == null || target.getProtocol() != protocol) {
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
        final String normalized = StringUtils.defaultString(relativePath).trim().replace('\\', '/').replaceAll("/+", "/")
                .replaceAll("^/+", "").replaceAll("/+$", "");
        if (StringUtils.isBlank(normalized) || ".".equals(normalized) || normalized.contains("../") || normalized.endsWith("/..") || normalized.startsWith("..")) {
            throw new InvalidParameterValueException("File share relative path must be a non-empty relative path without traversal segments");
        }
        return normalized;
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
        if (StringUtils.isBlank(path)) {
            return;
        }
        final String normalizedPath = normalizeFileSharePath(path);
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
            final String existingPath = normalizeFileSharePath(existing.getPath());
            if (normalizedPath.equals(existingPath)) {
                if (allowCrossProtocolReuse && existing.getProtocol() != StorageServiceInstance.Protocol.SMB) {
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
            return StorageServiceInstance.Permission.valueOf(permission.toUpperCase());
        } catch (final IllegalArgumentException e) {
            throw new InvalidParameterValueException("Invalid SMB ACL permission: " + permission);
        }
    }

    protected StorageServiceInstance.Permission parseBlockPermission(final String permission) {
        final String value = StringUtils.isBlank(permission) ? StorageServiceInstance.Permission.READ_WRITE.name() : permission.toUpperCase();
        try {
            final StorageServiceInstance.Permission parsed = StorageServiceInstance.Permission.valueOf(value);
            if (parsed == StorageServiceInstance.Permission.ADMIN) {
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
        ports.add(new JsonPrimitive(port));
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
        ips.add(new JsonPrimitive(listenIp));
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
            result.add(new JsonPrimitive(value));
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
                result.add(new JsonPrimitive(port));
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
                next.add(new JsonPrimitive(value));
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
            final JsonObject normalizedInspection = parseJsonObject(GSON.toJson(inspection));
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
        final String relativeSharePath = normalizeRelativeSharePath(sharePath);
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
        config.remove("relativeSharePath");
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
                result.add(new JsonPrimitive(port));
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
        StorageServiceProtocolVO protocol = protocolType == StorageServiceInstance.Protocol.NFS ?
                findNfsProtocolEndpoint(instance.getId(), null, 2049) :
                storageServiceProtocolDao.findByInstanceIdAndProtocol(instance.getId(), protocolType);
        if (protocol == null) {
            protocol = new StorageServiceProtocolVO(instance.getId(), protocolType, true, null, protocolType == StorageServiceInstance.Protocol.NFS ? 2049 : null);
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
            context.runtimePrimaryIp = observeRuntimePrimaryIp(instance);
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
            if (!inventory.has("fileShareVolumes") || !inventory.get("fileShareVolumes").isJsonArray()) {
                snapshot.error = "Runtime inventory does not contain fileShareVolumes";
                return snapshot;
            }
            populateRuntimeObservationMetadata(snapshot, inventory, "fileShareVolumes");
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
        return volume == null ? null : snapshot.observation(normalizeVolumeIdentity(volume.getUuid()));
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

    protected void populateFileShareVolumeResponse(final StorageNfsExportResponse response, final VolumeVO volume,
            final JsonObject config, final JsonObject runtime) {
        response.setVolumeUuid(volume == null ? getJsonString(config, "attachedVolumeUuid") : volume.getUuid());
        response.setFilesystemUuid(fileShareFilesystemUuid(config));
        response.setVolumeMountPath(getJsonString(config, "volumeMountPath"));
        populateFileShareRuntimeFields(response, runtime);
    }

    protected void populateFileShareVolumeResponse(final StorageSmbShareResponse response, final VolumeVO volume,
            final JsonObject config, final JsonObject runtime) {
        response.setVolumeUuid(volume == null ? getJsonString(config, "attachedVolumeUuid") : volume.getUuid());
        response.setFilesystemUuid(fileShareFilesystemUuid(config));
        response.setVolumeMountPath(getJsonString(config, "volumeMountPath"));
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
                StorageServiceInstance.StorageServiceCommandTimeout
        };
    }
}
