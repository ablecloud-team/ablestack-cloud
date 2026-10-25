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

package org.apache.cloudstack.storage.sharedfs;

import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSInitialFormatMaxSeconds;
import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSCleanupDelay;
import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSCleanupInterval;
import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSFeatureEnabled;
import static org.apache.cloudstack.storage.sharedfs.SharedFS.SharedFSExpungeWorkers;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.ServiceOfferingVO;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.api.response.SharedFSServiceOfferingResponse;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ListSharedFSServiceOfferingsCmd;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.org.Grouping;
import com.cloud.projects.Project;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.PluggableService;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.utils.net.NetUtils;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ChangeSharedFSDiskOfferingCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ChangeSharedFSServiceOfferingCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ExpungeSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ListSharedFSProvidersCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.ListSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.DestroySharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.RecoverSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.RestartSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.StartSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.StopSharedFSCmd;
import org.apache.cloudstack.api.command.user.storage.sharedfs.UpdateSharedFSCmd;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.dataservice.StorageAccessRuleVO;
import org.apache.cloudstack.storage.dataservice.StorageFileShareVO;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstance;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommand;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandDispatcher;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandResult;
import org.apache.cloudstack.storage.dataservice.StorageServiceProtocolVO;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.sharedfs.SharedFS.Event;
import org.apache.cloudstack.storage.sharedfs.SharedFS.State;
import org.apache.cloudstack.storage.sharedfs.query.dao.SharedFSJoinDao;
import org.apache.cloudstack.storage.sharedfs.query.vo.SharedFSJoinVO;
import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonObject;

import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

public class SharedFSServiceImpl extends ManagerBase implements SharedFSService, Configurable, PluggableService {
    private static final String SHAREDFS_COMPAT_PROVIDER = "SHAREDFS_COMPATIBILITY";
    private static final String CONFIGURE_SHAREDFS_STATIC_NETWORK = "configure-sharedfs-static-network";
    private static final int STATIC_NETWORK_QGA_ATTEMPTS = 30;
    private static final int STATIC_NETWORK_QGA_RETRY_MILLIS = 2000;
    private static final int STATIC_NETWORK_BOOTSTRAP_TIMEOUT_SECONDS = 360;

    protected static class StaticNetworkConfiguration {
        final String ipAddress;
        final String networkCidr;

        StaticNetworkConfiguration(String ipAddress, String networkCidr) {
            this.ipAddress = ipAddress;
            this.networkCidr = networkCidr;
        }
    }

    @Inject private com.cloud.host.dao.HostDao hostDao;
    @Inject private com.cloud.storage.dao.StoragePoolHostDao storagePoolHostDao;
    @Inject private com.cloud.vm.dao.UserVmDao userVmDao;
    @Inject private org.apache.cloudstack.storage.dataservice.StorageService storageService;
    @Inject private org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao storageBlockTargetDao;

    @Inject
    private AccountManager accountMgr;

    @Inject
    private DataCenterDao dataCenterDao;

    @Inject
    private ServiceOfferingDao serviceOfferingDao;

    @Inject
    private ConfigurationManager configMgr;

    @Inject
    private VolumeApiService volumeApiService;

    @Inject
    private SharedFSDao sharedFSDao;

    @Inject
    private SharedFSJoinDao sharedFSJoinDao;

    @Inject
    private DiskOfferingDao diskOfferingDao;

    @Inject
    ConfigurationDao configDao;

    @Inject
    VolumeDao volumeDao;

    @Inject
    PrimaryDataStoreDao storagePoolDao;

    @Inject
    NetworkDao networkDao;

    @Inject
    NetworkModel networkModel;

    @Inject
    NicDao nicDao;

    @Inject
    StorageServiceInstanceDao storageServiceInstanceDao;

    @Inject
    StorageServiceProtocolDao storageServiceProtocolDao;

    @Inject
    StorageFileShareDao storageFileShareDao;

    @Inject
    StorageAccessRuleDao storageAccessRuleDao;

    @Inject
    StorageServiceGuestCommandDispatcher guestCommandDispatcher;

    protected List<SharedFSProvider> sharedFSProviders;

    private Map<String, SharedFSProvider> sharedFSProviderMap = new HashMap<>();

    protected final StateMachine2<State, Event, SharedFS> sharedFSStateMachine;

    ScheduledExecutorService _executor = null;

    public SharedFSServiceImpl() {
        this.sharedFSStateMachine = State.getStateMachine();
    }

    @Override
    public boolean start() {
        sharedFSProviderMap.clear();
        for (final SharedFSProvider provider : sharedFSProviders) {
            sharedFSProviderMap.put(provider.getName(), provider);
            provider.configure();
        }
        reconcileSharedFSToStorageService();
        _executor.scheduleWithFixedDelay(new SharedFSGarbageCollector(), SharedFSCleanupInterval.value(), SharedFSCleanupInterval.value(), TimeUnit.SECONDS);
        return true;
    }

    public boolean stop() {
        _executor.shutdown();
        return true;
    }

    @Override
    public List<SharedFSProvider> getSharedFSProviders() {
        return sharedFSProviders;
    }

    @Override
    public boolean stateTransitTo(SharedFS sharedFS, Event event) {
        try {
            return sharedFSStateMachine.transitTo(sharedFS, event, null, sharedFSDao);
        } catch (NoTransitionException e) {
            String message = String.format("State transit error for Shared FileSystem %s due to exception: %s.",
                    sharedFS, e.getMessage());
            logger.error(message, e);
            throw new CloudRuntimeException(message, e);
        }
    }

    @Override
    public void setSharedFSProviders(List<SharedFSProvider> sharedFSProviders) {
        this.sharedFSProviders = sharedFSProviders;
    }

    @Override
    public SharedFSProvider getSharedFSProvider(String sharedFSProviderName) {
        if (sharedFSProviderMap.containsKey(sharedFSProviderName)) {
            return sharedFSProviderMap.get(sharedFSProviderName);
        }
        throw new CloudRuntimeException("Invalid Shared FileSystem provider name!");
    }

    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        int wrks = SharedFSExpungeWorkers.value();
        _executor = Executors.newScheduledThreadPool(wrks, new NamedThreadFactory("SharedFS-Scavenger"));
        return true;
    }

    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        if (SharedFSFeatureEnabled.value()) {
            cmdList.add(org.apache.cloudstack.api.command.user.storage.sharedfs.ResumeSharedFSCreationCmd.class);
            cmdList.add(org.apache.cloudstack.api.command.user.storage.sharedfs.GetSharedFSPreparationCmd.class);
            cmdList.add(ListSharedFSProvidersCmd.class);
            cmdList.add(CreateSharedFSCmd.class);
            cmdList.add(ListSharedFSCmd.class);
            cmdList.add(ListSharedFSServiceOfferingsCmd.class);
            cmdList.add(UpdateSharedFSCmd.class);
            cmdList.add(DestroySharedFSCmd.class);
            cmdList.add(RestartSharedFSCmd.class);
            cmdList.add(StartSharedFSCmd.class);
            cmdList.add(StopSharedFSCmd.class);
            cmdList.add(ChangeSharedFSDiskOfferingCmd.class);
            cmdList.add(ChangeSharedFSServiceOfferingCmd.class);
            cmdList.add(RecoverSharedFSCmd.class);
            cmdList.add(ExpungeSharedFSCmd.class);
        }
        return cmdList;
    }

    @Override
    public ListResponse<SharedFSServiceOfferingResponse> listCreationServiceOfferings(Long zoneId,
            ListResponse<ServiceOfferingResponse> visible) {
        DataCenter zone = validateAndGetZone(zoneId);
        SharedFSLifeCycle lifeCycle = getSharedFSProvider(SharedFSProvider.SharedFSProviderType.SHAREDFSVM.toString()).getSharedFSLifeCycle();
        List<SharedFSServiceOfferingResponse> rows = new ArrayList<>();
        if (visible.getResponses() != null) {
            for (ServiceOfferingResponse offering : visible.getResponses()) {
                ServiceOfferingVO current = serviceOfferingDao.findByUuid(offering.getId());
                if (current != null) {
                    rows.add(new SharedFSServiceOfferingResponse(offering,
                            lifeCycle.getCreationOfferingEligibility(zone, current.getId())));
                }
            }
        }
        ListResponse<SharedFSServiceOfferingResponse> response = new ListResponse<>();
        response.setResponses(rows, visible.getCount());
        return response;
    }

    private DataCenter validateAndGetZone(Long zoneId) {
        DataCenter zone = dataCenterDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by ID: " + zoneId);
        }
        if (zone.getAllocationState() == Grouping.AllocationState.Disabled) {
            throw new PermissionDeniedException(String.format("Cannot perform this operation, zone ID: %s is currently disabled", zone.getUuid()));
        }
        if (zone.getNetworkType() == DataCenter.NetworkType.Basic ||
            zone.isSecurityGroupEnabled()) {
            throw new PermissionDeniedException("This feature is supported only on Advanced Zone without security groups");
        }
        return zone;
    }

    private void validateDiskOffering(Long diskOfferingId, Long size, Long minIops, Long maxIops, DataCenter zone) {
        Account caller = CallContext.current().getCallingAccount();
        DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
        configMgr.checkDiskOfferingAccess(caller, diskOffering, zone);

        if (!diskOffering.isCustomized() && size != null) {
            throw new InvalidParameterValueException("Size provided with a non-custom disk offering");
        }
        if ((diskOffering.isCustomizedIops() == null || diskOffering.isCustomizedIops() == false) && (minIops != null || maxIops != null)) {
            throw new InvalidParameterValueException("Iops provided with a non-custom-iops disk offering");
        }
        if ((minIops == null) != (maxIops == null)) {
            throw new InvalidParameterValueException("Either 'miniops' and 'maxiops' must both be provided or neither must be provided.");
        }
        if (minIops != null && (minIops <= 0 || maxIops <= 0)) {
            throw new InvalidParameterValueException("The 'miniops' and 'maxiops' parameters must be greater than zero.");
        }
        if (minIops != null && minIops > maxIops) {
            throw new InvalidParameterValueException("The 'miniops' parameter must be less than or equal to the 'maxiops' parameter.");
        }
    }

    private void validateInitialBackingStorage(Long diskOfferingId, Long storageId, DataCenter zone) {
        if (storageId == null) {
            throw new InvalidParameterValueException("Primary storage is required for the initial Shared FileSystem backing volume");
        }
        final DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
        final StoragePoolVO storagePool = storagePoolDao.findById(storageId);
        if (storagePool == null) {
            throw new InvalidParameterValueException("Unable to find primary storage with id " + storageId);
        }
        if (storagePool.getDataCenterId() != zone.getId()) {
            throw new InvalidParameterValueException("Selected primary storage does not belong to zone " + zone.getUuid());
        }
        if (!volumeApiService.doesStoragePoolSupportDiskOffering(storagePool, diskOffering)) {
            throw new InvalidParameterValueException("Selected primary storage does not satisfy the disk offering storage tags");
        }
    }

    protected StaticNetworkConfiguration validateStaticNetworkConfiguration(CreateSharedFSCmd cmd, NetworkVO network) {
        if (cmd.getNetworkMode() == SharedFS.NetworkMode.DHCP) {
            if (!networkModel.areServicesSupportedInNetwork(network.getId(), Network.Service.UserData)) {
                throw new InvalidParameterValueException(String.format("Network %s does not support UserData or ConfigDrive. Select STATIC network mode and provide ipcidr for this L2 SharedFS network.",
                        network.getUuid()));
            }
            return null;
        }
        if (network.getGuestType() != Network.GuestType.L2) {
            throw new InvalidParameterValueException("Static SharedFS network configuration is supported only for L2 networks");
        }
        StaticNetworkConfiguration configuration = parseStaticIpCidr(cmd.getIpCidr());
        if ((StringUtils.isNotBlank(cmd.getGateway()) && !NetUtils.isValidIp4(cmd.getGateway())) ||
                (StringUtils.isNotBlank(cmd.getDns1()) && !NetUtils.isValidIp4(cmd.getDns1())) ||
                (StringUtils.isNotBlank(cmd.getDns2()) && !NetUtils.isValidIp4(cmd.getDns2()))) {
            throw new InvalidParameterValueException("Static SharedFS network configuration must contain valid IPv4 values");
        }
        for (String dns : new String[]{cmd.getDns1(), cmd.getDns2()}) {
            if (StringUtils.isNotBlank(dns) && (NetUtils.ip2Long(dns) == 0L || NetUtils.ip2Long(dns) >= 0xe0000000L)) {
                throw new InvalidParameterValueException("Static SharedFS DNS must be an IPv4 unicast address");
            }
        }
        final String[] cidrParts = configuration.networkCidr.split("/");
        final int prefix = Integer.parseInt(cidrParts[1]);
        if (StringUtils.isNotBlank(cmd.getGateway()) && !NetUtils.sameSubnetCIDR(cmd.getGateway(), cidrParts[0], prefix)) {
            throw new InvalidParameterValueException("Static SharedFS IP address and gateway must belong to the selected CIDR");
        }
        final long address = NetUtils.ip2Long(configuration.ipAddress);
        final long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        final long networkAddress = NetUtils.ip2Long(cidrParts[0]);
        final long broadcastAddress = networkAddress | (~mask & 0xffffffffL);
        if (prefix <= 30 && (address == networkAddress || address == broadcastAddress)) {
            throw new InvalidParameterValueException("Static SharedFS IP address cannot be the network or broadcast address");
        }
        if (address == 0L || address >= 0xe0000000L) {
            throw new InvalidParameterValueException("Static SharedFS IP address must be an IPv4 unicast address");
        }
        if (StringUtils.isNotBlank(cmd.getGateway())) {
            final long gateway = NetUtils.ip2Long(cmd.getGateway());
            if (gateway == address || gateway == 0L || gateway >= 0xe0000000L ||
                    (prefix <= 30 && (gateway == networkAddress || gateway == broadcastAddress))) {
                throw new InvalidParameterValueException("Static SharedFS gateway must be a usable unicast address other than the primary IP");
            }
        }
        NicVO existingNic = nicDao.findByIp4AddressAndNetworkId(configuration.ipAddress, network.getId());
        if (existingNic != null) {
            throw new InvalidParameterValueException("Static SharedFS IP address is already allocated on the selected network");
        }
        return configuration;
    }

    protected StaticNetworkConfiguration parseStaticIpCidr(String ipCidr) {
        if (StringUtils.isBlank(ipCidr)) {
            throw new InvalidParameterValueException("ipcidr is required for static SharedFS network configuration");
        }
        final String[] parts = ipCidr.trim().split("/", -1);
        if (parts.length != 2 || !NetUtils.isValidIp4(parts[0])) {
            throw new InvalidParameterValueException("ipcidr must use IPv4/prefix format, for example 10.10.1.211/24");
        }
        final int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new InvalidParameterValueException("ipcidr prefix must be a number between 0 and 32");
        }
        if (prefix < 0 || prefix > 32) {
            throw new InvalidParameterValueException("ipcidr prefix must be a number between 0 and 32");
        }
        final long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        final String networkCidr = String.format("%s/%d", NetUtils.long2Ip(NetUtils.ip2Long(parts[0]) & mask), prefix);
        return new StaticNetworkConfiguration(parts[0], networkCidr);
    }

    protected void configureStaticNetwork(SharedFS sharedFS) {
        if (sharedFS.getNetworkMode() != SharedFS.NetworkMode.STATIC) {
            return;
        }
        if (sharedFS.getVmId() == null) {
            throw new CloudRuntimeException("Unable to configure static SharedFS network before the Storage Service VM is deployed");
        }
        List<NicVO> nics = nicDao.listByVmId(sharedFS.getVmId());
        if (nics.size() != 1) {
            throw new CloudRuntimeException("Static SharedFS network configuration requires exactly one Storage Service VM NIC");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("macAddress", nics.get(0).getMacAddress());
        payload.addProperty("ipAddress", sharedFS.getIpAddress());
        payload.addProperty("cidr", sharedFS.getCidr());
        if (StringUtils.isNotBlank(sharedFS.getGateway())) {
            payload.addProperty("gateway", sharedFS.getGateway());
        }
        if (StringUtils.isNotBlank(sharedFS.getDns1())) {
            payload.addProperty("dns1", sharedFS.getDns1());
        }
        if (StringUtils.isNotBlank(sharedFS.getDns2())) {
            payload.addProperty("dns2", sharedFS.getDns2());
        }

        final long started = staticNetworkNanoTime();
        final long deadline = started + TimeUnit.SECONDS.toNanos(STATIC_NETWORK_BOOTSTRAP_TIMEOUT_SECONDS);
        String lastError = null;
        String lastObservation = null;
        boolean pending = false;
        int ordinaryFailures = 0;
        while (deadline - staticNetworkNanoTime() >= TimeUnit.SECONDS.toNanos(1)) {
            int timeout = (int) Math.min(60, TimeUnit.NANOSECONDS.toSeconds(deadline - staticNetworkNanoTime()));
            if (timeout < 1) {
                break;
            }
            StorageServiceGuestCommandResult result = guestCommandDispatcher.dispatch(new StorageServiceGuestCommand(
                    sharedFS.getVmId(), CONFIGURE_SHAREDFS_STATIC_NETWORK, payload.toString(), timeout, Set.of()));
            if (result.isSuccess()) {
                return;
            }
            lastError = boundedStaticDiagnostic(result.getDetails(), 2048);
            String bootstrapState = staticBootstrapState(result);
            lastObservation = boundedStaticDiagnostic(result.getResultJson(), 4096);
            if ("FAILED".equals(bootstrapState)) {
                throw new CloudRuntimeException("SharedFS guest bootstrap failed: " + lastError + " " + lastObservation);
            }
            pending = "PENDING".equals(bootstrapState);
            // Ordinary command/transport failures retain the existing retry count.
            if (!pending && ++ordinaryFailures >= STATIC_NETWORK_QGA_ATTEMPTS) {
                break;
            }
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - staticNetworkNanoTime());
            if (remainingMillis <= 0) {
                break;
            }
            try {
                sleepStaticNetworkRetry(Math.min(STATIC_NETWORK_QGA_RETRY_MILLIS, remainingMillis));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudRuntimeException("Interrupted while waiting for SharedFS QGA static network configuration", e);
            }
        }
        if (pending) {
            long elapsed = TimeUnit.NANOSECONDS.toSeconds(staticNetworkNanoTime() - started);
            throw new SharedFsBootstrapPendingTimeoutException("SharedFS STATIC bootstrap is still pending after "
                    + elapsed + " seconds; preserve the same VM and request: " + lastError + " " + lastObservation);
        }
        throw new CloudRuntimeException("Failed to configure static SharedFS network through QGA: " + lastError);
    }

    protected long staticNetworkNanoTime() {
        return System.nanoTime();
    }

    protected void sleepStaticNetworkRetry(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private static String boundedStaticDiagnostic(String value, int limit) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), limit));
    }

    private String staticBootstrapState(StorageServiceGuestCommandResult result) {
        String json = result.getResultJson();
        if (StringUtils.isNotBlank(json) && json.length() <= 8192) {
            try {
                JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                JsonObject observation = root.getAsJsonObject("sharedFsBootstrap");
                if (observation != null && observation.has("state") && observation.get("state").isJsonPrimitive()
                        && observation.get("state").getAsJsonPrimitive().isString()) {
                    String state = observation.get("state").getAsString();
                    if ("PENDING".equals(state) || "FAILED".equals(state)) {
                        return state;
                    }
                }
            } catch (RuntimeException ignored) {
                // An invalid observation is never readiness or permission to extend ordinary retries.
            }
        }
        // Rolling deployment compatibility with the previous dedicated guard only.
        String details = result.getDetails();
        return details != null && details.contains("exit code 75:") && details.contains("SHAREDFS_BOOTSTRAP_PENDING:")
                ? "PENDING" : null;
    }

    protected static class SharedFsBootstrapPendingTimeoutException extends CloudRuntimeException {
        private static final long serialVersionUID = 1L;

        SharedFsBootstrapPendingTimeoutException(String message) {
            super(message);
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_CREATE, eventDescription = "Allocating Shared FileSystem", create = true)
    public SharedFS allocSharedFS(CreateSharedFSCmd cmd) {
        if (cmd.getRequestId() == null) return allocateSharedFSRequest(cmd);
        String key = SharedFSCreationContext.normalizeKey(cmd.getRequestId());
        GlobalLock lock = GlobalLock.getInternLock("sharedfs-request-" + cmd.getEntityOwnerId() + "-" + key);
        boolean acquired = false;
        try {
            acquired = lock.lock(10);
            if (!acquired) throw new com.cloud.utils.exception.CloudRuntimeException("The initial request is busy; observe the same request");
            return allocateSharedFSRequest(cmd);
        } finally { if (acquired) lock.unlock(); lock.releaseRef(); }
    }

    protected SharedFS allocateSharedFSRequest(CreateSharedFSCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();

        long ownerId = cmd.getEntityOwnerId();
        Account owner = accountMgr.getActiveAccountById(ownerId);
        accountMgr.checkAccess(caller, null, true, owner);
        SharedFSVO replay = findCreationReplay(ownerId, cmd);
        if (replay != null) return replay;
        DataCenter zone = validateAndGetZone(cmd.getZoneId());

        Long diskOfferingId = cmd.getDiskOfferingId();
        Long size = cmd.getSize();
        Long minIops = cmd.getMinIops();
        Long maxIops = cmd.getMaxIops();
        if (cmd.getVolumeId() == null) {
            if (diskOfferingId == null) throw new InvalidParameterValueException("A new backing volume requires a disk offering");
            validateDiskOffering(diskOfferingId, size, minIops, maxIops, zone);
            validateInitialBackingStorage(diskOfferingId, cmd.getStorageId(), zone);
        } else {
            SharedFSCreationContext.from(cmd);
            validateExistingBacking(null, cmd.getVolumeId(), ownerId, zone.getId());
        }

        SharedFSProvider provider = getSharedFSProvider(cmd.getSharedFSProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        lifeCycle.checkCreationPrerequisites(zone, cmd.getServiceOfferingId(), cmd.getCustomComputeParameters());

        NetworkVO networkVO = networkDao.findById(cmd.getNetworkId());
        if (networkVO == null) {
            throw new InvalidParameterValueException("Unable to find a network with Network ID " + cmd.getNetworkId());
        }
        StaticNetworkConfiguration staticNetwork = validateStaticNetworkConfiguration(cmd, networkVO);
        if (networkVO.getGuestType() == Network.GuestType.Shared) {
            if ((networkVO.getAclType() != ControlledEntity.ACLType.Account) ||
                    (cmd.getDomainId() != null && (networkVO.getDomainId() != cmd.getDomainId())) ||
                    (networkVO.getAccountId() != owner.getAccountId())) {
                throw new InvalidParameterValueException("Shared network which is not Account scoped and not belonging to the same account can not be used to create a Shared FileSystem");
            }
        }

        SharedFS.FileSystemType fsType;
        try {
            String expected = cmd.getFsFormat() == null && cmd.getVolumeId() != null ? "AUTO" : cmd.getFsFormat();
            if (expected == null) throw new IllegalArgumentException();
            fsType = SharedFS.FileSystemType.valueOf(expected.toUpperCase());
            if (fsType == SharedFS.FileSystemType.AUTO && cmd.getVolumeId() == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException ex) {
            throw new InvalidParameterValueException("Invalid File system format specified. Supported formats are EXT4 and XFS");
        }

        if (sharedFSDao.findSharedFSByNameAccountDomain(cmd.getName(), owner.getAccountId(), cmd.getDomainId()) != null) {
            throw new InvalidParameterValueException("There already exists a Shared FileSystem with this name for the given account and domain.");
        }

        SharedFSVO sharedFS = new SharedFSVO(cmd.getName(), cmd.getDescription(), owner.getDomainId(),
                ownerId, cmd.getZoneId(), cmd.getSharedFSProviderName(), SharedFS.Protocol.NFS,
                fsType, cmd.getServiceOfferingId());
        sharedFS.setNetworkMode(cmd.getNetworkMode());
        if (cmd.getNetworkMode() == SharedFS.NetworkMode.STATIC) {
            sharedFS.setIpAddress(staticNetwork.ipAddress);
            sharedFS.setCidr(staticNetwork.networkCidr);
            sharedFS.setGateway(cmd.getGateway());
            sharedFS.setDns1(cmd.getDns1());
            sharedFS.setDns2(cmd.getDns2());
        }

        return persistCreation(sharedFS, cmd, ownerId);
    }

    protected SharedFSVO findCreationReplay(long ownerId, CreateSharedFSCmd cmd) {
        String key = SharedFSCreationContext.normalizeKey(cmd.getRequestId());
        if (key == null) return null;
        SharedFSVO existing = sharedFSDao.findByCreationRequest(ownerId, key);
        if (existing == null) return null;
        SharedFSCreationContext incoming = SharedFSCreationContext.from(cmd);
        SharedFSCreationContext saved;
        try { saved = SharedFSCreationContext.read(existing.getCreationContext()); }
        catch (SharedFSCreationContext.InvalidJournalException e) {
            throw new InvalidParameterValueException(SharedFSCreationContext.INVALID_JOURNAL + ": observe and cancel the unallocated preparation");
        }
        if (saved == null || !incoming.fingerprint.equals(saved.fingerprint)
                || (saved.prepareNewFileBacking != null && !java.util.Objects.equals(saved.prepareNewFileBacking,incoming.prepareNewFileBacking)))
            throw new InvalidParameterValueException("Creation request UUID was already used with different parameters");
        return existing;
    }

    protected VolumeVO validateExistingBacking(SharedFSVO fs, Long volumeId, long ownerId, long zoneId) {
        VolumeVO v = volumeDao.findById(volumeId);
        if (v == null || v.getRemoved() != null || v.getAccountId() != ownerId || v.getDataCenterId() != zoneId
                || v.getVolumeType() != com.cloud.storage.Volume.Type.DATADISK)
            throw new InvalidParameterValueException("Existing backing must be an owned DATA volume in the selected zone");
        if (v.getState() != com.cloud.storage.Volume.State.Ready)
            throw new InvalidParameterValueException("Existing backing DATA volume must be Ready");
        if (v.getInstanceId() != null && (fs == null || !v.getInstanceId().equals(fs.getVmId())))
            throw new InvalidParameterValueException("Existing backing DATA volume is attached to another VM");
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool = v.getPoolId() == null ? null : storagePoolDao.findById(v.getPoolId());
        if (pool == null || pool.getStatus() != com.cloud.storage.StoragePoolStatus.Up || pool.getDataCenterId() != zoneId)
            throw new InvalidParameterValueException("Existing backing DATA has no Up primary storage in the selected zone");
        for (SharedFSVO claim : sharedFSDao.listByBackingVolume(volumeId)) {
            if (fs == null || claim.getId() != fs.getId())
                throw new InvalidParameterValueException("Existing backing DATA is reserved by another SharedFS");
        }
        ensureBackingUnreferenced(volumeId);
        return v;
    }

    protected void ensureBackingUnreferenced(long volumeId) {
        com.cloud.utils.db.SearchCriteria<StorageFileShareVO> files = storageFileShareDao.createSearchCriteria();
        files.addAnd("volumeId", SearchCriteria.Op.EQ, volumeId);
        if (!storageFileShareDao.search(files, null).isEmpty())
            throw new InvalidParameterValueException("Existing DATA is already referenced by a file share");
        com.cloud.utils.db.SearchCriteria<org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO> blocks = storageBlockTargetDao.createSearchCriteria();
        blocks.addAnd("volumeId", SearchCriteria.Op.EQ, volumeId);
        if (!storageBlockTargetDao.search(blocks, null).isEmpty())
            throw new InvalidParameterValueException("Existing DATA is already referenced by a block target");
    }

    protected void validateBackingLocation(com.cloud.vm.UserVmVO vm, VolumeVO volume) {
        org.apache.cloudstack.storage.datastore.db.StoragePoolVO pool = storagePoolDao.findById(volume.getPoolId());
        if (pool == null || pool.getStatus() != com.cloud.storage.StoragePoolStatus.Up)
            throw new InvalidParameterValueException("Existing backing primary storage is not Up");
        if ("ZONE".equals(String.valueOf(pool.getScope()))) return;
        if (vm.getHostId() == null) throw new InvalidParameterValueException("Backing attachment requires an observed running VM host");
        com.cloud.host.HostVO host = hostDao.findById(vm.getHostId());
        if (host == null) throw new InvalidParameterValueException("Backing attachment host is unavailable");
        if ("CLUSTER".equals(String.valueOf(pool.getScope())) && !java.util.Objects.equals(pool.getClusterId(), host.getClusterId()))
            throw new InvalidParameterValueException("Existing backing pool is not accessible from the selected VM cluster");
        if ("HOST".equals(String.valueOf(pool.getScope())) && storagePoolHostDao.findByPoolHost(pool.getId(), host.getId()) == null)
            throw new InvalidParameterValueException("Existing local backing pool belongs to a different host");
    }

    private void validateOriginalBacking(SharedFSCreationContext c, VolumeVO volume) {
        if (!java.util.Objects.equals(volume.getPoolId(), c.originalPoolId)
                || !java.util.Objects.equals(volume.getPath(), c.originalPath)
                || !java.util.Objects.equals(volume.getSize(), c.originalSize)
                || !java.util.Objects.equals(volume.getDiskOfferingId(), c.originalDiskOfferingId))
            throw new InvalidParameterValueException("Original backing volume metadata changed since reservation");
    }

    private SharedFS persistCreation(SharedFSVO fs, CreateSharedFSCmd cmd, long ownerId) {
        if (cmd.getRequestId() == null && cmd.getVolumeId() == null) return sharedFSDao.persist(fs);
        SharedFSCreationContext context = SharedFSCreationContext.from(cmd);
        context.planVm(fs.getUuid());
        fs.setCreationRequestId(cmd.getRequestId() == null ? java.util.UUID.randomUUID().toString()
                : SharedFSCreationContext.normalizeKey(cmd.getRequestId()));
        fs.setCreationContext(context.json());
        if (!context.existing) return sharedFSDao.persist(fs);
        return com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<SharedFS>) status -> {
            volumeDao.lockRow(context.volumeId, true);
            VolumeVO original = validateExistingBacking(null, context.volumeId, ownerId, fs.getDataCenterId());
            context.originalPoolId = original.getPoolId(); context.originalPath = original.getPath();
            context.originalSize = original.getSize(); context.originalDiskOfferingId = original.getDiskOfferingId();
            fs.setCreationContext(context.json());
            fs.setVolumeId(context.volumeId);
            return sharedFSDao.persist(fs);
        });
    }

    private void saveCreation(SharedFSVO fs, SharedFSCreationContext c, String phase) {
        c.phase = phase; fs.setCreationContext(c.json()); sharedFSDao.update(fs.getId(), fs);
    }

    @Override
    public SharedFS resumeSharedFSCreation(Long id, String requestId, String action) throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        SharedFSVO fs = sharedFSDao.findById(id);
        if (fs == null) throw new InvalidParameterValueException("SharedFS does not exist");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, true, fs);
        if (!java.util.Objects.equals(fs.getCreationRequestId(), SharedFSCreationContext.normalizeKey(requestId)))
            throw new InvalidParameterValueException("Original creation request UUID is required");
        if (!java.util.Set.of("CONTINUE", "MOUNT_EXISTING", "CANCEL_PREPARATION").contains(action.toUpperCase()))
            throw new InvalidParameterValueException("Unknown initial preparation action");
        return continueCreation(id, action.toUpperCase());
    }

    @Override
    public String getSharedFSPreparation(Long id, String requestId) {
        SharedFSVO fs = sharedFSDao.findById(id);
        if (fs == null) throw new InvalidParameterValueException("SharedFS does not exist");
        accountMgr.checkAccess(CallContext.current().getCallingAccount(), null, true, fs);
        if (!java.util.Objects.equals(fs.getCreationRequestId(), SharedFSCreationContext.normalizeKey(requestId)))
            throw new InvalidParameterValueException("Original creation request UUID is required");
        SharedFSCreationContext c = SharedFSCreationContext.read(fs.getCreationContext());
        if (c == null || c.preparation == null || c.existing) return "{}";
        // This path deliberately performs no journal update, launch, mount or format.
        return storageService.operateInitialBackingPreparation(fs, "status");
    }

    protected SharedFS continueCreation(long id, String action) throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        String next = action;
        for (;;) {
            SharedFS result = continueCreationStep(id, next);
            SharedFSCreationContext c = SharedFSCreationContext.read(result.getCreationContext());
            if (c == null || c.existing || c.preparation == null || result.getState() == SharedFS.State.Ready
                    || !"RUNNING".equals(jsonString(c.preparation,"status"))) return result;
            // Resource/global locks and transactions have been released by the step.
            try { Thread.sleep(5000); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result; // guest work is independent and remains observable
            }
            next = "OBSERVE_PREPARATION";
        }
    }

    protected boolean prepareInitialNewData(SharedFSVO fs, SharedFSCreationContext c, String action) {
        boolean first = c.preparation == null;
        if (first) {
            List<VolumeVO> roots = volumeDao.findByInstance(fs.getVmId()).stream()
                .filter(v -> v.getVolumeType() == com.cloud.storage.Volume.Type.ROOT).collect(Collectors.toList());
            if (roots.size() != 1 || !c.ownedVolumeIds.contains(roots.get(0).getId()))
                throw new InvalidParameterValueException("Initial ROOT ownership is not verified");
            VolumeVO data = volumeDao.findById(c.volumeId);
            if (data == null || !c.ownedVolumeIds.contains(data.getId()) || !fs.getVmId().equals(data.getInstanceId()))
                throw new InvalidParameterValueException("Initial DATA ownership is not verified");
            com.google.gson.JsonObject p = new com.google.gson.JsonObject();
            p.addProperty("operationId", java.util.UUID.nameUUIDFromBytes(
                ("sharedfs-initial-data:" + fs.getUuid() + ":" + data.getUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            p.addProperty("vmUuid", userVmDao.findById(fs.getVmId()).getUuid());
            p.addProperty("rootVolumeUuid", roots.get(0).getUuid());
            p.addProperty("volumeUuid", data.getUuid()); p.addProperty("volumeName", data.getName());
            p.addProperty("volumeSizeBytes", data.getSize()); p.addProperty("filesystem", c.expectedFilesystem.toLowerCase());
            long remaining = org.apache.cloudstack.framework.jobs.impl.AsyncJobManagerImpl.JobCancelThresholdMinutes.value() * 60L;
            org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext job = org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext.getCurrentExecutionContext();
            if (job != null && job.getJob() != null && job.getJob().getCreated() != null)
                remaining -= Math.max(0, (System.currentTimeMillis() - job.getJob().getCreated().getTime()) / 1000);
            p.addProperty("formatTimeoutSeconds", SharedFSCreationContext.initialFormatBudget(data.getSize(),
                SharedFSInitialFormatMaxSeconds.value(), remaining));
            p.addProperty("status", "REQUESTED"); p.addProperty("phase", "QUEUED");
            c.preparation = p;
            saveCreation(fs,c,"PREPARING"); // intent is durable before the first guest launch
        }
        syncSharedFSToStorageService(fs);
        com.google.gson.JsonObject observed = com.google.gson.JsonParser.parseString(storageService.operateInitialBackingPreparation(
            fs, first ? "start" : ("OBSERVE_PREPARATION".equals(action) ? "status" : "resume"))).getAsJsonObject();
        for (String key : java.util.List.of("status","phase","formatEverStarted","formatCount","formatStartedAt","formatDeadlineAt",
                "startedAt","deadlineAt","phaseStartedAt","phaseTimeoutSeconds","heartbeatAt","lastObservedAt","createdAt","finishedAt","filesystemUuid","observedFilesystem","bootId","currentBootId",
                "mountPath","errorCode","reason","canResume","blockedReason","observationAvailable","capabilityVersion"))
            if (observed.has(key)) c.preparation.add(key,observed.get(key));
        String status = jsonString(c.preparation,"status");
        if (!"PREPARED".equals(status)) {
            c.errorCode = jsonString(c.preparation,"errorCode");
            saveCreation(fs,c,"RUNNING".equals(status) ? "PREPARING" :
                ("RECOVERY_REQUIRED".equals(status) ? "RECOVERY_REQUIRED" : "OBSERVATION_PENDING"));
            return false;
        }
        String uuid = jsonString(c.preparation,"filesystemUuid");
        String mount = jsonString(c.preparation,"mountPath");
        VolumeVO data = volumeDao.findById(c.volumeId);
        if (uuid == null || uuid.isBlank() || !("/srv/ablestack-storage/volumes/" + data.getUuid()).equals(mount)
                || !c.expectedFilesystem.equalsIgnoreCase(jsonString(c.preparation,"observedFilesystem")))
            throw new InvalidParameterValueException("Initial filesystem/mount result is not verified");
        c.filesystemUuid = uuid; c.mountPath = mount; c.bootId = jsonString(c.preparation,"currentBootId");
        c.observedFilesystem = jsonString(c.preparation,"observedFilesystem");
        return true;
    }

    protected SharedFS continueCreationStep(long id, String action) throws ResourceUnavailableException,
            InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        GlobalLock lock = GlobalLock.getInternLock("sharedfs-initial-" + id);
        if (lock == null) throw new com.cloud.utils.exception.CloudRuntimeException("Initial SharedFS lock is unavailable");
        if (!lock.lock(10)) {
            lock.releaseRef();
            throw new com.cloud.utils.exception.CloudRuntimeException("Initial SharedFS allocation is busy; observe the same request");
        }
        try {
            SharedFSVO fs = sharedFSDao.findById(id);
            SharedFSCreationContext c;
            try {
                c = SharedFSCreationContext.read(fs.getCreationContext());
            } catch (SharedFSCreationContext.InvalidJournalException e) {
                if ("CANCEL_PREPARATION".equals(action)) return cancelUnallocatedCorruptCreation(fs);
                throw new InvalidParameterValueException(SharedFSCreationContext.INVALID_JOURNAL
                        + ": creation is blocked; an unallocated preparation may be cancelled");
            }
            if (c == null) throw new InvalidParameterValueException("This resource has no resumable creation journal");
            SharedFSLifeCycle life = getSharedFSProvider(fs.getFsProviderName()).getSharedFSLifeCycle();
            if (fs.getState() == SharedFS.State.Ready) {
                if ("CANCEL_PREPARATION".equals(action)) throw new InvalidParameterValueException("A Ready service cannot be cancelled as initial preparation");
                return fs;
            }
            if ("CANCEL_PREPARATION".equals(action)) {
                if (c.preparation != null) throw new InvalidParameterValueException("Initial DATA preparation must be observed; active or uncertain work cannot be cancelled");
                if (c.cancelled) return fs;
                cancelCreation(fs, c, life);
                return sharedFSDao.findById(id);
            }
            if (c.cancelled) throw new InvalidParameterValueException("Initial allocation was cancelled; use a new request");
            org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext jobContext = org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext.getCurrentExecutionContext();
            if (jobContext != null && jobContext.getJob() != null) {
                c.lastJobUuid = jobContext.getJob().getUuid();
                saveCreation(fs, c, c.phase);
            }
            if (fs.getState() == SharedFS.State.Error) stateTransitTo(fs, Event.CreationResumeRequested);
            if (!c.existing && c.preparation != null) {
                com.cloud.vm.UserVmVO allocated = userVmDao.findById(fs.getVmId());
                if (allocated == null || !c.plannedVmUuid.equals(allocated.getUuid()) || allocated.getAccountId() != fs.getAccountId())
                    throw new InvalidParameterValueException("Initial preparation VM identity changed");
                if (!"OBSERVE_PREPARATION".equals(action) && allocated.getState() == com.cloud.vm.VirtualMachine.State.Stopped)
                    life.startSharedFS(fs);
                if (!prepareInitialNewData(fs,c,action)) return sharedFSDao.findById(id);
                c.errorCode = null; saveCreation(fs,c,"READY");
                if (fs.getState() != SharedFS.State.Ready) stateTransitTo(fs,Event.OperationSucceeded);
                syncSharedFSToStorageService(sharedFSDao.findById(id));
                return sharedFSDao.findById(id);
            }
            try {
                Map<String, String> compute = life.checkCreationPrerequisites(validateAndGetZone(fs.getDataCenterId()),
                        fs.getServiceOfferingId(), c.compute);
                if (c.existing) validateExistingBacking(fs, c.volumeId, fs.getAccountId(), fs.getDataCenterId());
                if (fs.getVmId() == null) {
                    saveCreation(fs, c, "ALLOCATION_PENDING");
                    fs.setVmId(life.allocateInitialSharedFSVm(fs, c.networkId, compute, c.plannedVmUuid));
                    saveCreation(fs, c, "VM_ALLOCATED");
                }
                if (c.ownedVolumeIds.isEmpty()) {
                    c.ownedVolumeIds = volumeDao.findByInstance(fs.getVmId()).stream()
                            .filter(v -> v.getVolumeType() == com.cloud.storage.Volume.Type.ROOT
                                || (!c.existing && java.util.Objects.equals(v.getDeviceId(), 1L)
                                    && java.util.Objects.equals(v.getDiskOfferingId(), c.diskOfferingId)))
                            .map(VolumeVO::getId).collect(Collectors.toList());
                    saveCreation(fs, c, "VM_ALLOCATED");
                }
                com.cloud.vm.UserVmVO vm = userVmDao.findById(fs.getVmId());
                if (vm == null || !c.plannedVmUuid.equals(vm.getUuid()) || vm.getAccountId() != fs.getAccountId()
                        || !com.cloud.vm.UserVmManager.SHAREDFSVM.equals(vm.getUserVmType()))
                    throw new InvalidParameterValueException("Planned VM identity does not match the allocation");
                if (vm.getState() != com.cloud.vm.VirtualMachine.State.Running) life.startSharedFS(fs);
                vm = userVmDao.findById(fs.getVmId());
                configureStaticNetwork(sharedFSDao.findById(id));
                if (!c.existing) {
                    List<VolumeVO> data = volumeDao.findByInstance(fs.getVmId()).stream()
                            .filter(v -> v.getVolumeType() == com.cloud.storage.Volume.Type.DATADISK).collect(Collectors.toList());
                    if (data.size() != 1) throw new InvalidParameterValueException("Initial allocation must have exactly one owned DATA volume");
                    VolumeVO backing = data.get(0);
                    if (c.storageId != null && !c.storageId.equals(backing.getPoolId()))
                        throw new InvalidParameterValueException("Backing volume was allocated on a different primary storage");
                    fs.setVolumeId(backing.getId()); c.volumeId = backing.getId();
                    for (VolumeVO owned : volumeDao.findByInstance(fs.getVmId())) {
                        if (!owned.getName().startsWith("sharedfs-")) {
                            owned.setName("sharedfs-" + owned.getName()); volumeDao.update(owned.getId(), owned);
                        }
                    }
                } else {
                    VolumeVO backing = validateExistingBacking(fs, c.volumeId, fs.getAccountId(), fs.getDataCenterId());
                    validateOriginalBacking(c, backing);
                    validateBackingLocation(vm, backing);
                    if (backing.getInstanceId() == null) {
                        saveCreation(fs, c, "ATTACH_PENDING");
                        volumeApiService.attachVolumeToVM(fs.getVmId(), backing.getId(), null, true);
                        backing = volumeDao.findById(backing.getId());
                    }
                    validateOriginalBacking(c, backing);
                    if (!fs.getVmId().equals(backing.getInstanceId()))
                        throw new InvalidParameterValueException("Backing volume attachment could not be verified");
                    fs.setVolumeId(backing.getId()); saveCreation(fs, c, "ATTACHED");
                    syncSharedFSToStorageService(fs);
                    com.google.gson.JsonObject inspection = com.google.gson.JsonParser.parseString(
                            storageService.prepareInitialImportedBackingVolume(fs, "INSPECT_ONLY")).getAsJsonObject();
                    c.acceptInspection(jsonString(inspection, "filesystem"), jsonString(inspection, "filesystemUuid"));
                    c.bootId = jsonString(inspection, "bootId"); c.mountPath = jsonString(inspection, "volumeMountPath");
                    fs.setFsType(SharedFS.FileSystemType.valueOf(c.observedFilesystem.toUpperCase()));
                    saveCreation(fs, c, "INSPECTED");
                    if ("INSPECT_ONLY".equals(c.importMode) && !"MOUNT_EXISTING".equals(action)) return fs;
                    saveCreation(fs, c, "MOUNT_PENDING");
                    com.google.gson.JsonObject mounted = com.google.gson.JsonParser.parseString(
                            storageService.prepareInitialImportedBackingVolume(fs, "MOUNT_EXISTING")).getAsJsonObject();
                    if (!c.filesystemUuid.equals(jsonString(mounted, "filesystemUuid"))
                            || !c.mountPath.equals(jsonString(mounted, "volumeMountPath"))
                            || !mounted.has("persistentMount") || !mounted.get("persistentMount").getAsBoolean())
                        throw new InvalidParameterValueException("Imported filesystem mount identity was not verified");
                }
                if (!c.existing && Boolean.TRUE.equals(c.prepareNewFileBacking) && !prepareInitialNewData(fs,c,action)) return sharedFSDao.findById(id);
                c.errorCode = null; saveCreation(fs, c, "READY");
                stateTransitTo(fs, Event.OperationSucceeded);
                syncSharedFSToStorageService(sharedFSDao.findById(id));
                return sharedFSDao.findById(id);
            } catch (Exception e) {
                c.errorCode = e.getClass().getSimpleName();
                if (c.preparation != null) {
                    saveCreation(fs, c, "OBSERVATION_PENDING");
                    throw e; // never clean DATA while guest work may still be active
                }
                if (e instanceof OperationTimedoutException || e instanceof SharedFsBootstrapPendingTimeoutException
                        || "ALLOCATION_PENDING".equals(c.phase)
                        || "ATTACH_PENDING".equals(c.phase)) {
                    saveCreation(fs, c, "OBSERVATION_PENDING");
                } else {
                    try { cancelCreation(fs, c, life); }
                    catch (RuntimeException cleanup) { saveCreation(fs, c, "CLEANUP_PENDING"); e.addSuppressed(cleanup); }
                }
                if (fs.getState() == SharedFS.State.Allocated) stateTransitTo(fs, Event.OperationFailed);
                throw e;
            }
        } finally { lock.unlock(); lock.releaseRef(); }
    }

    private static String jsonString(com.google.gson.JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }

    protected SharedFS cancelUnallocatedCorruptCreation(SharedFSVO fs) {
        if (fs.getVmId() != null || (fs.getState() != SharedFS.State.Allocated && fs.getState() != SharedFS.State.Error))
            throw new InvalidParameterValueException("Corrupt preparation with a VM or operational state requires investigation");
        SharedFSCreationContext cancelled = new SharedFSCreationContext();
        cancelled.planVm(fs.getUuid());
        if (userVmDao.findByUuid(cancelled.plannedVmUuid) != null)
            throw new InvalidParameterValueException("A planned VM exists; corrupt preparation cannot be cancelled automatically");
        if (fs.getVolumeId() != null) {
            VolumeVO data = volumeDao.findById(fs.getVolumeId());
            if (data == null || data.getRemoved() != null || data.getAccountId() != fs.getAccountId()
                    || data.getDataCenterId() != fs.getDataCenterId()
                    || data.getVolumeType() != com.cloud.storage.Volume.Type.DATADISK
                    || data.getState() != com.cloud.storage.Volume.State.Ready || data.getInstanceId() != null)
                throw new InvalidParameterValueException("Backing DATA must be owned, Ready and unattached before releasing a corrupt reservation");
            for (SharedFSVO claim : sharedFSDao.listByBackingVolume(data.getId())) {
                if (claim.getId() != fs.getId()) throw new InvalidParameterValueException("Another SharedFS claims the backing DATA");
            }
            ensureBackingUnreferenced(data.getId());
            cancelled.existing = true; cancelled.volumeId = data.getId();
        }
        cancelled.cancelled = true; cancelled.errorCode = SharedFSCreationContext.INVALID_JOURNAL;
        fs.setVolumeId(null);
        if (fs.getState() == SharedFS.State.Allocated) stateTransitTo(fs, Event.OperationFailed);
        saveCreation(fs, cancelled, "CANCELLED");
        return sharedFSDao.findById(fs.getId());
    }

    private void cancelCreation(SharedFSVO fs, SharedFSCreationContext c, SharedFSLifeCycle life) {
        if (c.existing) {
            ensureBackingUnreferenced(c.volumeId);
            VolumeVO data = volumeDao.findById(c.volumeId);
            if (data != null && fs.getVmId() != null && fs.getVmId().equals(data.getInstanceId())) {
                storageService.releaseInitialImportedBackingVolume(fs);
                volumeApiService.detachVolumeViaDestroyVM(fs.getVmId(), data.getId());
                if (volumeDao.findById(data.getId()).getInstanceId() != null)
                    throw new com.cloud.utils.exception.CloudRuntimeException("Existing DATA is still attached; owned VM cleanup is blocked");
            }
            if (fs.getVmId() != null) life.cleanupImportedSharedFSVm(fs);
        } else if (fs.getVmId() != null) {
            if (volumeDao.findByInstance(fs.getVmId()).stream().anyMatch(v -> !c.ownedVolumeIds.contains(v.getId())))
                throw new com.cloud.utils.exception.CloudRuntimeException("Unowned volumes block initial VM cleanup");
            life.deleteSharedFS(fs);
        }
        deleteStorageServiceCompatibility(fs);
        fs.setVmId(null); fs.setVolumeId(null); c.cancelled = true;
        saveCreation(fs, c, "CANCELLED");
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_CREATE, eventDescription = "Deploying Shared FileSystem", async = true)
    public SharedFS deploySharedFS(CreateSharedFSCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException, OperationTimedoutException {
        SharedFSVO sharedFS = sharedFSDao.findById(cmd.getEntityId());
        if (sharedFS.getCreationContext() != null) return continueCreation(sharedFS.getId(), "CONTINUE");
        Long diskOfferingId = cmd.getDiskOfferingId();
        Long size = cmd.getSize();
        Long minIops = cmd.getMinIops();
        Long maxIops = cmd.getMaxIops();
        SharedFSProvider provider = getSharedFSProvider(cmd.getSharedFSProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        Pair<Long, Long> result;
        try {
            Map<String, String> compute = lifeCycle.checkCreationPrerequisites(validateAndGetZone(cmd.getZoneId()),
                    cmd.getServiceOfferingId(), cmd.getCustomComputeParameters());
            result = lifeCycle.deploySharedFS(sharedFS, cmd.getNetworkId(), diskOfferingId, cmd.getStorageId(), size, minIops, maxIops, compute);
            sharedFS.setVolumeId(result.first());
            sharedFS.setVmId(result.second());
            sharedFSDao.update(sharedFS.getId(), sharedFS);
            configureStaticNetwork(sharedFSDao.findById(sharedFS.getId()));
        } catch (Exception ex) {
            stateTransitTo(sharedFS, Event.OperationFailed);
            throw ex;
        }
        stateTransitTo(sharedFS, Event.OperationSucceeded);
        syncSharedFSToStorageService(sharedFSDao.findById(sharedFS.getId()));
        return sharedFS;
    }

    private SharedFS startSharedFS(SharedFS sharedFS) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException {
        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();

        try {
            stateTransitTo(sharedFS, Event.StartRequested);
            lifeCycle.startSharedFS(sharedFS);
            configureStaticNetwork(sharedFS);
        } catch (Exception ex) {
            stateTransitTo(sharedFS, Event.OperationFailed);
            throw ex;
        }
        stateTransitTo(sharedFS, Event.OperationSucceeded);
        sharedFS = sharedFSDao.findById(sharedFS.getId());
        syncSharedFSToStorageService(sharedFS);
        return sharedFS;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_START, eventDescription = "Starting Shared FileSystem")
    public SharedFS startSharedFS(Long sharedFSId) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);

        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        Set<State> validStates = new HashSet<>(List.of(State.Stopped));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Shared FileSystem can be started only if it is in the " + validStates.toString() + " state");
        }
        return startSharedFS(sharedFS);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_STOP, eventDescription = "Stopping Shared FileSystem")
    public SharedFS stopSharedFS(Long sharedFSId, Boolean forced) {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        Set<State> validStates = new HashSet<>(List.of(State.Ready));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Shared FileSystem can be stopped only if it is in the " + State.Ready + " state");
        }

        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        try {
            stateTransitTo(sharedFS, Event.StopRequested);
            lifeCycle.stopSharedFS(sharedFS, forced);
        } catch (Exception e) {
            stateTransitTo(sharedFS, Event.OperationFailed);
            throw e;
        }
        stateTransitTo(sharedFS, Event.OperationSucceeded);
        syncSharedFSToStorageService(sharedFSDao.findById(sharedFS.getId()));
        return sharedFS;
    }

    private SharedFSVO reDeploySharedFS(SharedFSVO sharedFS) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        boolean result = lifeCycle.reDeploySharedFS(sharedFS);
        if (result) {
            configureStaticNetwork(sharedFSDao.findById(sharedFS.getId()));
        }
        return (result ? sharedFS : null);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_RESTART, eventDescription = "Restarting Shared FileSystem", async = true)
    public SharedFS restartSharedFS(Long sharedFSId, boolean cleanup) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);

        Set<State> validStates = new HashSet<>(List.of(State.Ready, State.Stopped));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Restart Shared FileSystem can be done only if the shared filesystem is in " + validStates.toString() + " states");
        }

        if (!cleanup) {
            if (!sharedFS.getState().equals(State.Stopped)) {
                stopSharedFS(sharedFS.getId(), false);
            }
            return startSharedFS(sharedFS.getId());
        } else {
            return reDeploySharedFS(sharedFS);
        }
    }

    private Pair<List<Long>, Integer> searchForSharedFSIdsAndCount(ListSharedFSCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        Long id = cmd.getId();
        String name = cmd.getName();
        Long networkId = cmd.getNetworkId();
        Long diskOfferingId = cmd.getDiskOfferingId();
        Long serviceOfferingId = cmd.getServiceOfferingId();
        String keyword = cmd.getKeyword();
        Long startIndex = cmd.getStartIndex();
        Long pageSize = cmd.getPageSizeVal();
        Long zoneId = cmd.getZoneId();
        String accountName = cmd.getAccountName();
        Long domainId = cmd.getDomainId();
        Long projectId = cmd.getProjectId();

        Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, accountName, projectId, permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        Project.ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(SharedFSVO.class, "created", false, startIndex, pageSize);

        SearchBuilder<SharedFSVO> sharedFSSearchBuilder = sharedFSDao.createSearchBuilder();
        sharedFSSearchBuilder.select(null, SearchCriteria.Func.DISTINCT, sharedFSSearchBuilder.entity().getId()); // select distinct
        accountMgr.buildACLSearchBuilder(sharedFSSearchBuilder, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sharedFSSearchBuilder.and("requestId", sharedFSSearchBuilder.entity().getCreationRequestId(), SearchCriteria.Op.EQ);
        sharedFSSearchBuilder.and("id", sharedFSSearchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        sharedFSSearchBuilder.and("name", sharedFSSearchBuilder.entity().getName(), SearchCriteria.Op.EQ);
        sharedFSSearchBuilder.and("dataCenterId", sharedFSSearchBuilder.entity().getDataCenterId(), SearchCriteria.Op.EQ);

        if (keyword != null) {
            sharedFSSearchBuilder.and("keywordName", sharedFSSearchBuilder.entity().getName(), SearchCriteria.Op.LIKE);
        }

        sharedFSSearchBuilder.and("serviceOfferingId", sharedFSSearchBuilder.entity().getServiceOfferingId(), SearchCriteria.Op.EQ);

        if (diskOfferingId != null) {
            SearchBuilder<VolumeVO> volSearch = volumeDao.createSearchBuilder();
            volSearch.and("diskOfferingId", volSearch.entity().getDiskOfferingId(), SearchCriteria.Op.EQ);
            sharedFSSearchBuilder.join("volSearch", volSearch, volSearch.entity().getId(), sharedFSSearchBuilder.entity().getVolumeId(), JoinBuilder.JoinType.INNER);
        }

        if (networkId != null) {
            SearchBuilder<NicVO> nicSearch = nicDao.createSearchBuilder();
            nicSearch.and("networkId", nicSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
            sharedFSSearchBuilder.join("nicSearch", nicSearch, nicSearch.entity().getInstanceId(), sharedFSSearchBuilder.entity().getVmId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<SharedFSVO> sc = sharedFSSearchBuilder.create();
        accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (cmd.getRequestId() != null) sc.setParameters("requestId", SharedFSCreationContext.normalizeKey(cmd.getRequestId()));
        if (keyword != null) {
            sc.setParameters("keywordName", "%" + keyword + "%");
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        if (serviceOfferingId != null) {
            sc.setParameters("serviceOfferingId", serviceOfferingId);
        }

        if (diskOfferingId != null) {
            sc.setJoinParameters("volSearch", "diskOfferingId", diskOfferingId);
        }

        if (networkId != null) {
            sc.setJoinParameters("nicSearch", "networkId", networkId);
        }

       Pair<List<SharedFSVO>, Integer> result = sharedFSDao.searchAndCount(sc, searchFilter);
        List<Long> idsArray = result.first().stream().map(SharedFSVO::getId).collect(Collectors.toList());
        return new Pair<List<Long>, Integer>(idsArray, result.second());
    }

    private Pair<List<SharedFSJoinVO>, Integer> searchForSharedFSInternal(ListSharedFSCmd cmd) {
        Pair<List<Long>, Integer> sharedFSIds = searchForSharedFSIdsAndCount(cmd);
        if (sharedFSIds.second() == 0) {
            return new Pair<List<SharedFSJoinVO>, Integer>(null, 0);
        }

        List<SharedFSJoinVO> sharedFSs = sharedFSJoinDao.searchByIds(sharedFSIds.first().toArray(new Long[0]));
        return new Pair<List<SharedFSJoinVO>, Integer>(sharedFSs, sharedFSIds.second());
    }

    @Override
    public ListResponse<SharedFSResponse> searchForSharedFS(ResponseObject.ResponseView respView, ListSharedFSCmd cmd) {
        Pair<List<SharedFSJoinVO>, Integer> result = searchForSharedFSInternal(cmd);
        ListResponse<SharedFSResponse> response = new ListResponse<>();

        if (cmd.getRetrieveOnlyResourceCount()) {
            response.setResponses(new ArrayList<>(), result.second());
            return response;
        }

        Account caller = CallContext.current().getCallingAccount();
        if (accountMgr.isRootAdmin(caller.getId())) {
            respView = ResponseObject.ResponseView.Full;
        }

        List<SharedFSResponse> sharedFSRespons = null;
        if (result.second() > 0) {
            sharedFSRespons = sharedFSJoinDao.createSharedFSResponses(respView, result.first().toArray(new SharedFSJoinVO[result.first().size()]));
        }

        if (sharedFSRespons != null) for (SharedFSResponse item : sharedFSRespons)
            item.setCreation(sharedFSDao.findByUuid(item.getId()));
        response.setResponses(sharedFSRespons, result.second());
        return response;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_UPDATE, eventDescription = "Updating Shared FileSystem")
    public SharedFS updateSharedFS(UpdateSharedFSCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getName();
        String description = cmd.getDescription();

        SharedFSVO sharedFS = sharedFSDao.findById(id);
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);

        if (name != null) {
            sharedFS.setName(name);
        }
        if (description != null) {
            sharedFS.setDescription(description);
        }

        sharedFSDao.update(sharedFS.getId(), sharedFS);
        syncSharedFSToStorageService(sharedFS);
        return sharedFS;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_CHANGE_DISK_OFFERING, eventDescription = "Change Shared FileSystem disk offering")
    public SharedFS changeSharedFSDiskOffering(ChangeSharedFSDiskOfferingCmd cmd) throws ResourceAllocationException {
        SharedFSVO sharedFS = sharedFSDao.findById(cmd.getId());
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        Set<State> validStates = new HashSet<>(List.of(State.Ready, State.Stopped));

        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Disk offering of the Shared FileSystem can be changed only if it is in " + validStates.toString() + " states");
        }

        Long diskOfferingId = cmd.getDiskOfferingId();
        Long newSize = cmd.getSize();
        Long newMinIops = cmd.getMinIops();
        Long newMaxIops = cmd.getMaxIops();
        DataCenter zone = validateAndGetZone(sharedFS.getDataCenterId());
        validateDiskOffering(diskOfferingId, newSize, newMinIops, newMaxIops, zone);
        volumeApiService.changeDiskOfferingForVolumeInternal(sharedFS.getVolumeId(), diskOfferingId, newSize, newMinIops, newMaxIops, true, false);
        syncSharedFSToStorageService(sharedFS);
        return sharedFS;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_CHANGE_SERVICE_OFFERING, eventDescription = "Change Shared FileSystem service offering")
    public SharedFS changeSharedFSServiceOffering(ChangeSharedFSServiceOfferingCmd cmd) throws OperationTimedoutException, ResourceUnavailableException, InsufficientCapacityException, ManagementServerException, VirtualMachineMigrationException {
        SharedFSVO sharedFS = sharedFSDao.findById(cmd.getId());
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        Set<State> validStates = new HashSet<>(List.of(State.Stopped));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Service offering of the Shared FileSystem can be changed only if it is in " + validStates.toString() + " state");
        }

        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        DataCenter zone = validateAndGetZone(sharedFS.getDataCenterId());
        lifeCycle.checkPrerequisites(zone, cmd.getServiceOfferingId());

        sharedFS = sharedFSDao.findById(cmd.getId());

        if (lifeCycle.changeSharedFSServiceOffering(sharedFS, cmd.getServiceOfferingId())) {
            sharedFS.setServiceOfferingId(cmd.getServiceOfferingId());
            sharedFSDao.update(sharedFS.getId(), sharedFS);
            syncSharedFSToStorageService(sharedFS);
            return sharedFS;
        } else {
            return null;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_DESTROY, eventDescription = "Destroy Shared FileSystem")
    public Boolean destroySharedFS(DestroySharedFSCmd cmd) {
        Long sharedFSId = cmd.getId();
        Boolean expunge = cmd.isExpunge();
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);

        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);

        if (sharedFS.getState().equals(State.Ready) && cmd.isForced()) {
            stopSharedFS(sharedFS.getId(), false);
        }

        sharedFS = sharedFSDao.findById(sharedFSId);
        Set<State> validStates = new HashSet<>(List.of(State.Stopped, State.Error));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Shared FileSystem can be destroyed only if it is in the " + validStates.toString() + " states");
        }

        stateTransitTo(sharedFS, Event.DestroyRequested);
        syncSharedFSToStorageService(sharedFSDao.findById(sharedFSId));
        if (expunge || sharedFS.getState().equals(State.Error)) {
            deleteSharedFS(sharedFSId);
        }
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_RECOVER, eventDescription = "Recover Shared FileSystem")
    public SharedFS recoverSharedFS(Long sharedFSId) {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);
        if (!State.Destroyed.equals(sharedFS.getState())) {
            throw new InvalidParameterValueException("The Shared FileSystem should be in the Destroyed state to be recovered");
        }
        stateTransitTo(sharedFS, Event.RecoveryRequested);
        sharedFS = sharedFSDao.findById(sharedFSId);
        syncSharedFSToStorageService(sharedFS);
        return sharedFS;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SHAREDFS_EXPUNGE, eventDescription = "Expunge Shared FileSystem")
    public void deleteSharedFS(Long sharedFSId) {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFSId);
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, false, sharedFS);

        Set<State> validStates = new HashSet<>(List.of(State.Destroyed, State.Expunging, State.Error));
        if (!validStates.contains(sharedFS.getState())) {
            throw new InvalidParameterValueException("Shared FileSystem can be expunged only if it is in the " + validStates.toString() + " states");
        }
        SharedFSProvider provider = getSharedFSProvider(sharedFS.getFsProviderName());
        SharedFSLifeCycle lifeCycle = provider.getSharedFSLifeCycle();
        stateTransitTo(sharedFS, Event.ExpungeOperation);
        lifeCycle.deleteSharedFS(sharedFS);
        deleteStorageServiceCompatibility(sharedFS);
        stateTransitTo(sharedFS, Event.OperationSucceeded);
        sharedFSDao.remove(sharedFS.getId());
    }

    protected void syncSharedFSToStorageService(SharedFS sharedFS) {
        if (sharedFS == null || !SharedFSFeatureEnabled.value()) {
            return;
        }
        if (sharedFS.getVmId() == null || sharedFS.getVolumeId() == null) {
            logger.debug("Skipping Storage Service compatibility sync for SharedFS [{}] without VM or volume binding", sharedFS);
            return;
        }

        try {
            StorageServiceInstanceVO instance = storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
            if (instance == null) {
                instance = new StorageServiceInstanceVO(sharedFS.getName(), sharedFS.getDescription(), sharedFS.getDomainId(),
                        sharedFS.getAccountId(), sharedFS.getDataCenterId(), sharedFS.getServiceOfferingId(), SHAREDFS_COMPAT_PROVIDER);
                instance.setVmId(sharedFS.getVmId());
                instance.setState(toStorageServiceState(sharedFS.getState()));
                instance = storageServiceInstanceDao.persist(instance);
            } else {
                instance.setName(sharedFS.getName());
                instance.setDescription(sharedFS.getDescription());
                instance.setServiceOfferingId(sharedFS.getServiceOfferingId());
                instance.setProvider(SHAREDFS_COMPAT_PROVIDER);
                instance.setState(toStorageServiceState(sharedFS.getState()));
                storageServiceInstanceDao.update(instance.getId(), instance);
            }

            StorageServiceProtocolVO protocol = null;
            for (StorageServiceProtocolVO candidate : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
                if (candidate.getPort() == null || candidate.getPort() == 2049) {
                    protocol = candidate;
                    break;
                }
                if (protocol == null) {
                    protocol = candidate;
                }
            }
            if (protocol == null) {
                protocol = new StorageServiceProtocolVO(instance.getId(), StorageServiceInstance.Protocol.NFS, isSharedFSReady(sharedFS.getState()), null, 2049);
                protocol.setState(toStorageResourceState(sharedFS.getState()));
                storageServiceProtocolDao.persist(protocol);
            } else {
                protocol.setEnabled(isSharedFSReady(sharedFS.getState()));
                protocol.setPort(protocol.getPort() == null ? 2049 : protocol.getPort());
                protocol.setState(toStorageResourceState(sharedFS.getState()));
                storageServiceProtocolDao.update(protocol.getId(), protocol);
            }

            removeLegacySharedFSRootExport(instance);
        } catch (RuntimeException e) {
            logger.warn("Unable to sync SharedFS [{}] to Storage Service compatibility model. Existing SharedFS API behavior is preserved.",
                    sharedFS, e);
        }
    }

    protected void reconcileSharedFSToStorageService() {
        if (!SharedFSFeatureEnabled.value()) {
            return;
        }
        for (SharedFSVO sharedFS : sharedFSDao.listAll()) {
            if (!shouldReconcileSharedFSToStorageService(sharedFS)) {
                continue;
            }
            syncSharedFSToStorageService(sharedFS);
        }
    }

    protected boolean shouldReconcileSharedFSToStorageService(SharedFS sharedFS) {
        return sharedFS != null && sharedFS.getVmId() != null && sharedFS.getVolumeId() != null &&
                !State.Destroyed.equals(sharedFS.getState()) && !State.Expunging.equals(sharedFS.getState()) &&
                !State.Expunged.equals(sharedFS.getState());
    }

    protected void removeLegacySharedFSRootExport(StorageServiceInstanceVO instance) {
        for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
            if (SharedFS.SharedFSPath.equals(StringUtils.removeEnd(share.getPath(), "/"))) {
                for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                    storageAccessRuleDao.remove(rule.getId());
                }
                storageFileShareDao.remove(share.getId());
            }
        }
    }

    protected void deleteStorageServiceCompatibility(SharedFS sharedFS) {
        if (sharedFS == null || !SharedFSFeatureEnabled.value() || sharedFS.getVmId() == null) {
            return;
        }
        try {
            StorageServiceInstanceVO instance = storageServiceInstanceDao.findByVmId(sharedFS.getVmId());
            if (instance == null) {
                return;
            }
            for (StorageFileShareVO share : storageFileShareDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
                for (StorageAccessRuleVO rule : storageAccessRuleDao.listByResource(StorageServiceInstance.AccessResourceType.FILE_SHARE, share.getId())) {
                    storageAccessRuleDao.remove(rule.getId());
                }
                storageFileShareDao.remove(share.getId());
            }
            for (StorageServiceProtocolVO protocol : storageServiceProtocolDao.listByInstanceIdAndProtocol(instance.getId(), StorageServiceInstance.Protocol.NFS)) {
                storageServiceProtocolDao.remove(protocol.getId());
            }
            storageServiceInstanceDao.remove(instance.getId());
        } catch (RuntimeException e) {
            logger.warn("Unable to remove Storage Service compatibility model for SharedFS [{}]", sharedFS, e);
        }
    }

    protected StorageServiceInstance.State toStorageServiceState(State sharedFSState) {
        if (State.Ready.equals(sharedFSState)) {
            return StorageServiceInstance.State.Running;
        }
        if (State.Stopped.equals(sharedFSState)) {
            return StorageServiceInstance.State.Stopped;
        }
        if (State.Destroyed.equals(sharedFSState) || State.Expunged.equals(sharedFSState)) {
            return StorageServiceInstance.State.Destroyed;
        }
        if (State.Starting.equals(sharedFSState)) {
            return StorageServiceInstance.State.Starting;
        }
        if (State.Stopping.equals(sharedFSState)) {
            return StorageServiceInstance.State.Stopping;
        }
        if (State.Error.equals(sharedFSState)) {
            return StorageServiceInstance.State.Error;
        }
        return StorageServiceInstance.State.Allocated;
    }

    protected StorageServiceInstance.ResourceState toStorageResourceState(State sharedFSState) {
        if (State.Ready.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Ready;
        }
        if (State.Destroyed.equals(sharedFSState) || State.Expunged.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Destroyed;
        }
        if (State.Error.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Error;
        }
        if (State.Starting.equals(sharedFSState) || State.Stopping.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Updating;
        }
        if (State.Stopped.equals(sharedFSState)) {
            return StorageServiceInstance.ResourceState.Disabled;
        }
        return StorageServiceInstance.ResourceState.Allocated;
    }

    protected boolean isSharedFSReady(State sharedFSState) {
        return State.Ready.equals(sharedFSState);
    }

    protected Long getSharedFSVolumeSize(SharedFS sharedFS) {
        if (sharedFS.getVolumeId() == null) {
            return null;
        }
        VolumeVO volume = volumeDao.findById(sharedFS.getVolumeId());
        return volume == null ? null : volume.getSize();
    }

    @Override
    public SharedFS getSharedFSByUuid(String uuid) {
        return sharedFSDao.findByUuid(uuid);
    }

    @Override
    public SharedFS getSharedFSForVmId(long vmId) {
        return sharedFSDao.findByVm(vmId);
    }

    public SharedFS updateSharedFSPostRestore(long sharedFsId, long volumeId) {
        SharedFSVO sharedFS = sharedFSDao.findById(sharedFsId);
        if (sharedFS == null) {
            throw new CloudRuntimeException("Unable to find the Shared FileSystem");
        }
        VolumeVO volume = volumeDao.findById(volumeId);
        if (volume == null) {
            throw new CloudRuntimeException("Unable to find the Volume");
        }
        if (volume.getInstanceId() == null) {
            throw new CloudRuntimeException("Volume is not attached to any Instance");
        }
        if (sharedFS.getAccountId() != volume.getAccountId() || sharedFS.getDomainId() != volume.getDomainId()) {
            throw new CloudRuntimeException("Shared FileSystem and the Volume do not belong to the same account");
        }
        sharedFS.setVolumeId(volume.getId());
        sharedFS.setVmId(volume.getInstanceId());
        if (!sharedFSDao.update(sharedFS.getId(), sharedFS)) {
            throw new CloudRuntimeException("Failed to update Shared FileSystem with the restored Volume information");
        }
        return sharedFS;
    }

    @Override
    public String getConfigComponentName() {
        return SharedFSService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                SharedFSCleanupInterval,
                SharedFSCleanupDelay,
                SharedFSFeatureEnabled,
                SharedFSExpungeWorkers,
                SharedFSInitialFormatMaxSeconds
        };
    }
    protected class SharedFSGarbageCollector extends ManagedContextRunnable {

        public SharedFSGarbageCollector() {
        }

        @Override
        protected void runInContext() {
            try {
                logger.trace("Shared FileSystem Garbage Collection Thread is running.");

                cleanupSharedFS(true);

            } catch (Exception e) {
                logger.error("Caught the following Exception", e);
            }
        }
    }

    public void cleanupSharedFS(boolean recurring) {
        GlobalLock scanLock = GlobalLock.getInternLock("sharedfsservice.cleanup");

        try {
            if (scanLock.lock(30)) {
                try {

                    List<SharedFSVO> sharedFSs = sharedFSDao.listSharedFSToBeDestroyed(new Date(System.currentTimeMillis() - ((long)SharedFSCleanupDelay.value() << 10)));
                    for (SharedFSVO sharedFS : sharedFSs) {
                        try {
                            stateTransitTo(sharedFS, Event.ExpungeOperation);
                            deleteSharedFS(sharedFS.getId());
                        } catch (Exception e) {
                            stateTransitTo(sharedFS, Event.OperationFailed);
                            logger.error("Unable to expunge Shared FileSystem {} due to: [{}].", sharedFS, e.getMessage());
                        }
                    }
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }
    }
}
