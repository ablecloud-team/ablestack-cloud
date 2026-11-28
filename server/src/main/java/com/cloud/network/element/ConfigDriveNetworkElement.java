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
package com.cloud.network.element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.cloud.host.Host;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.configdrive.ConfigDrive;
import org.apache.cloudstack.storage.configdrive.ConfigDriveBuilder;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.HandleConfigDriveIsoAnswer;
import com.cloud.agent.api.HandleConfigDriveIsoCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkMigrationResponder;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.offering.NetworkOffering;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.StateListener;
import com.cloud.utils.fsm.StateMachine2;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

public class ConfigDriveNetworkElement extends AdapterBase implements NetworkElement,
        UserDataServiceProvider, DhcpServiceProvider, DnsServiceProvider,
        StateListener<VirtualMachine.State, VirtualMachine.Event, VirtualMachine>, NetworkMigrationResponder {

    private static final Map<Service, Map<Capability, String>> capabilities = setCapabilities();

    @Inject
    NetworkModel _networkMgr;
    @Inject
    UserVmDao _userVmDao;
    @Inject
    VMInstanceDetailsDao _vmInstanceDetailsDao;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    ServiceOfferingDao _serviceOfferingDao;
    @Inject
    NetworkModel _networkModel;
    @Inject
    NetworkOrchestrationService _networkOrchestrationService;
    @Inject
    GuestOSCategoryDao _guestOSCategoryDao;
    @Inject
    GuestOSDao _guestOSDao;
    @Inject
    VolumeDao _volumeDao;
    @Inject
    HostDao _hostDao;
    @Inject
    HostPodDao _podDao;
    @Inject
    ClusterDao _clusterDao;
    @Inject
    AgentManager agentManager;
    @Inject
    DataStoreManager _dataStoreMgr;
    @Inject
    EndPointSelector _ep;
    @Inject
    EntityManager _entityMgr;
    @Inject
    ServiceOfferingDao _offeringDao;
    @Inject
    ImageStoreDao imgstore;
    @Inject
    private HypervisorGuruManager _hvGuruMgr;

    private final static Integer CONFIGDRIVEDISKSEQ = 4;

    private boolean canHandle(TrafficType trafficType) {
        return trafficType.equals(TrafficType.Guest);
    }

    @Override
    public boolean start() {
        VirtualMachine.State.getStateMachine().registerListener(this);
        return super.start();
    }

    @Override
    public boolean implement(Network network, NetworkOffering offering, DeployDestination dest, ReservationContext context) throws ResourceUnavailableException, ConcurrentOperationException,
            InsufficientCapacityException {
        return canHandle(offering.getTrafficType());
    }

    @Override
    public boolean prepare(Network network, NicProfile nic, VirtualMachineProfile vmProfile, DeployDestination dest, ReservationContext context) throws ConcurrentOperationException,
            InsufficientCapacityException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean release(Network network, NicProfile nic, VirtualMachineProfile vm, ReservationContext context) {
        if (!nic.isDefaultNic()) {
            return true;
        }

        try {
            return deleteConfigDriveIso(vm.getVirtualMachine());
        } catch (ResourceUnavailableException e) {
            logger.error("Failed to delete config drive due to: ", e);
            return false;
        }
    }

    @Override
    public boolean shutdown(Network network, ReservationContext context, boolean cleanup) throws ConcurrentOperationException, ResourceUnavailableException {
        return true; // assume that the agent will remove userdata etc
    }

    @Override
    public boolean destroy(Network config, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true; // assume that the agent will remove userdata etc
    }

    @Override
    public Provider getProvider() {
        return Provider.ConfigDrive;
    }

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
    }

    private static Map<Service, Map<Capability, String>> setCapabilities() {
        Map<Service, Map<Capability, String>> capabilities = new HashMap<>();
        capabilities.put(Service.UserData, null);
        capabilities.put(Service.Dhcp, Map.of(Network.Capability.DhcpAccrossMultipleSubnets, "true"));
        capabilities.put(Service.Dns, Map.of(Capability.AllowDnsSuffixModification, "true"));
        return capabilities;
    }

    @Override
    public boolean isReady(PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(PhysicalNetworkServiceProvider provider, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return true;
    }

    private String getSshKey(VirtualMachineProfile profile) {
        final VMInstanceDetailVO vmDetailSshKey = _vmInstanceDetailsDao.findDetail(profile.getId(), VmDetailConstants.SSH_PUBLIC_KEY);
        return (vmDetailSshKey!=null ? vmDetailSshKey.getValue() : null);
    }

    @Override
    public boolean addPasswordAndUserdata(Network network, NicProfile nic, VirtualMachineProfile profile, DeployDestination dest, ReservationContext context)
            throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        return (canHandle(network.getTrafficType())
                && configureConfigDriveData(profile, nic, dest));
    }

    @Override
    public boolean savePassword(final Network network, final NicProfile nic, final VirtualMachineProfile vm) throws ResourceUnavailableException {
        // savePassword is called by resetPasswordForVirtualMachine API which requires VM to be shutdown
        // Upper layers should save password in db, we do not need to update/create config drive iso at this point
        // Config drive will be created with updated password when VM starts in future
        if (vm != null && vm.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            throw new CloudRuntimeException("Instance should to stopped to reset password");
        }

        final boolean canHandle = canHandle(network.getTrafficType());

        if (canHandle) {
            storePasswordInVmDetails(vm);
        }

        return canHandle;
    }

    @Override
    public boolean saveSSHKey(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final String sshPublicKey) throws ResourceUnavailableException {
        // saveSSHKey is called by resetSSHKeyForVirtualMachine API which requires VM to be shutdown
        // Upper layers should save ssh public key in db, we do not need to update/create config drive iso at this point
        // Config drive will be created with updated password when VM starts in future
        if (vm != null && vm.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            throw new CloudRuntimeException("Instance should to stopped to reset password");
        }

        final boolean canHandle = canHandle(network.getTrafficType());

        return canHandle;
    }

    @Override
    public boolean saveHypervisorHostname(NicProfile nic, Network network, VirtualMachineProfile vm, DeployDestination dest) throws ResourceUnavailableException {
        if (vm.getVirtualMachine().getType() == VirtualMachine.Type.User) {
            try {
                recreateConfigDriveIso(nic, network, vm, dest);
            } catch (ResourceUnavailableException e) {
                logger.error("Failed to add config disk drive due to: ", e);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean saveUserData(final Network network, final NicProfile nic, final VirtualMachineProfile vm) throws ResourceUnavailableException {
        // saveUserData is called by updateVirtualMachine API which requires VM to be shutdown
        // Upper layers should save userdata in db, we do not need to update/create config drive iso at this point
        // Config drive will be created with updated password when VM starts in future
        if (vm != null && vm.getVirtualMachine().getState().equals(VirtualMachine.State.Running)) {
            throw new CloudRuntimeException("Instance should to stopped to reset password");
        }
        return canHandle(network.getTrafficType());
    }

    /**
     * Store password in vm details so it can be picked up during VM start.
     */
    private void storePasswordInVmDetails(VirtualMachineProfile vm) {
        final String password = (String) vm.getParameter(VirtualMachineProfile.Param.VmPassword);
        final String password_encrypted = DBEncryptionUtil.encrypt(password);
        final UserVmVO userVmVO = _userVmDao.findById(vm.getId());

        _vmInstanceDetailsDao.addDetail(vm.getId(), VmDetailConstants.PASSWORD,  password_encrypted, false);

        userVmVO.setUpdateParameters(true);
        _userVmDao.update(userVmVO.getId(), userVmVO);
    }

    @Override
    public boolean verifyServicesCombination(Set<Service> services) {
        return true;
    }

    @Override
    public boolean preStateTransitionEvent(VirtualMachine.State oldState, VirtualMachine.Event event, VirtualMachine.State newState, VirtualMachine vo, boolean status, Object opaque) {
        return true;
    }

    @Override
    public boolean postStateTransitionEvent(StateMachine2.Transition<VirtualMachine.State, VirtualMachine.Event> transition, VirtualMachine vm, boolean status, Object opaque) {
        if (transition.getToState().equals(VirtualMachine.State.Expunging) && transition.getEvent().equals(VirtualMachine.Event.ExpungeOperation)) {
            Nic nic = _networkModel.getDefaultNic(vm.getId());
            if (nic == null) {
                return true;
            }
            try {
                final Network network = _networkMgr.getNetwork(nic.getNetworkId());
                final UserDataServiceProvider userDataUpdateProvider = _networkModel.getUserDataUpdateProvider(network);
                if (userDataUpdateProvider == null) {
                    logger.warn("Failed to get user data provider");
                    return false;
                }
                final Provider provider = userDataUpdateProvider.getProvider();
                if (Provider.ConfigDrive.equals(provider)) {
                    try {
                        return deleteConfigDriveIso(vm);
                    } catch (ResourceUnavailableException e) {
                        logger.error("Failed to delete config drive due to: ", e);
                        return false;
                    }
                }
            } catch (UnsupportedServiceException usse) {}
        }
        return true;
    }

    @Override
    public boolean prepareMigration(NicProfile nic, Network network, VirtualMachineProfile vm, DeployDestination dest, ReservationContext context) {
        // ConfigDrive creation/recreation is owned by the default NIC. A secondary
        // ConfigDrive-enabled network must not introduce media during migration.
        if (!nic.isDefaultNic()) {
            return true;
        }
        final UserDataServiceProvider userDataUpdateProvider = _networkModel.getUserDataUpdateProvider(network);
        if (userDataUpdateProvider == null) {
            logger.warn("Failed to prepare for migration, can't get user data provider");
            return false;
        }
        if (Provider.ConfigDrive.equals(userDataUpdateProvider.getProvider())) {
            logger.trace(String.format("[prepareMigration] for Instance: %s", vm.getInstanceName()));
            try {
                if (isConfigDriveIsoOnHostCache(vm.getId())) {
                    vm.setConfigDriveLocation(Location.HOST);
                    if (configureConfigDriveData(vm, nic, dest)) {
                        // Create the config drive on dest host cache
                        createConfigDriveIsoOnHostCache(nic, vm, dest.getHost());
                    }
                } else {
                    vm.setConfigDriveLocation(getConfigDriveLocation(vm.getId()));
                    boolean result = addPasswordAndUserdata(network, nic, vm, dest, context);
                    if (result) {
                        createConfigDriveIso(nic, vm, dest, null);
                    }
                }
            } catch (InsufficientCapacityException | ResourceUnavailableException e) {
                logger.error("Failed to add config disk drive due to: ", e);
                return false;
            }
        }
        return  true;
    }

    @Override
    public void rollbackMigration(NicProfile nic, Network network, VirtualMachineProfile vm, ReservationContext src, ReservationContext dst) {
        try {
            if (isConfigDriveIsoOnHostCache(vm.getId())) {
                vm.setConfigDriveLocation(Location.HOST);
                // Delete the config drive on dest host cache
                deleteConfigDriveIsoOnHostCache(vm.getVirtualMachine(), vm.getHostId());
            }
        } catch (ConcurrentOperationException | ResourceUnavailableException e) {
            logger.error("rollbackMigration failed.", e);
        }
    }

    @Override
    public void commitMigration(NicProfile nic, Network network, VirtualMachineProfile vm, ReservationContext src, ReservationContext dst) {
        try {
            if (isLastConfigDriveIsoOnHostCache(vm.getId())) {
                vm.setConfigDriveLocation(Location.HOST);
                // Delete the config drive on src host cache
                deleteConfigDriveIsoOnHostCache(vm.getVirtualMachine(), vm.getHostId());
            }
        } catch (ConcurrentOperationException | ResourceUnavailableException e) {
            logger.error("commitMigration failed.", e);
        }
    }

    private void recreateConfigDriveIso(NicProfile nic, Network network, VirtualMachineProfile vm, DeployDestination dest) throws ResourceUnavailableException {
        final UserDataServiceProvider userDataUpdateProvider = _networkModel.getUserDataUpdateProvider(network);
        if (userDataUpdateProvider == null) {
            return;
        }
        if (nic.isDefaultNic() && Provider.ConfigDrive.equals(userDataUpdateProvider.getProvider())) {
            DiskTO diskToUse = null;
            for (DiskTO disk : vm.getDisks()) {
                if (disk.getType() == Volume.Type.ISO && disk.getPath() != null && disk.getPath().contains("configdrive")) {
                    diskToUse = disk;
                    break;
                }
            }
            final UserVmVO userVm = _userVmDao.findById(vm.getId());

            if (userVm != null) {
                final boolean isWindows = isWindows(userVm.getGuestOSId());
                List<String[]> vmData = _networkModel.generateVmData(userVm.getUserData(), userVm.getUserDataDetails(), _serviceOfferingDao.findById(userVm.getServiceOfferingId()).getName(), userVm.getDataCenterId(), userVm.getInstanceName(), vm.getHostName(), vm.getId(),
                        vm.getUuid(), nic.getMacAddress(), userVm.getDetail("SSH.PublicKey"), (String) vm.getParameter(VirtualMachineProfile.Param.VmPassword), isWindows, VirtualMachineManager.getHypervisorHostname(dest.getHost() != null ? dest.getHost().getName() : ""));
                vm.setVmData(vmData);
                vm.setConfigDriveLabel(VirtualMachineManager.VmConfigDriveLabel.value());
                createConfigDriveIso(nic, vm, dest, diskToUse);
            }
        }
    }

    private boolean isWindows(long guestOSId) {
        return _guestOSCategoryDao.findById(_guestOSDao.findById(guestOSId).getCategoryId()).getName().equalsIgnoreCase("Windows");
    }

    private DataStore findPrimaryDataStore(VirtualMachineProfile profile, DeployDestination dest) {
        DataStore dataStore = MapUtils.isNotEmpty(dest.getStorageForDisks()) ? getPlannedDataStore(dest, null) : null;
        return dataStore == null ? pickExistingRootVolumeFromDataStore(profile, null) : dataStore;
    }

    private DataStore findSecondaryDataStore(long zoneId) throws ResourceUnavailableException {
        // The storage allocator can remove stores at the capacity threshold. Read the writable inventory before applying that filter.
        List<ImageStoreVO> stores = imgstore.findByZone(new ZoneScope(zoneId), Boolean.FALSE);
        if (stores.isEmpty()) {
            throw configDriveCreationFailure("no writable secondary image store is available in zone " + zoneId, zoneId);
        }
        List<DataStore> nfsStores = stores.stream()
                .map(store -> _dataStoreMgr.getDataStore(store.getId(), DataStoreRole.Image))
                .filter(store -> store != null && store.getTO() instanceof NfsTO)
                .collect(Collectors.toList());
        if (nfsStores.isEmpty()) {
            throw configDriveCreationFailure("writable secondary image stores in zone " + zoneId + " do not support ConfigDrive ISO storage (NFS required)", zoneId);
        }
        DataStore store = _dataStoreMgr.getImageStoreWithFreeCapacity(nfsStores);
        if (store == null) {
            throw configDriveCreationFailure("no writable secondary NFS image store in zone " + zoneId + " passed the configured capacity threshold", zoneId);
        }
        return store;
    }

    private DataStore getPlannedDataStore(DeployDestination dest, DataStore dataStore) {
        for (final Volume volume : dest.getStorageForDisks().keySet()) {
            if (volume.getVolumeType() == Volume.Type.ROOT) {
                final StoragePool primaryPool = dest.getStorageForDisks().get(volume);
                dataStore = _dataStoreMgr.getDataStore(primaryPool.getId(), DataStoreRole.Primary);
                break;
            }
        }
        return dataStore;
    }

    private DataStore pickExistingRootVolumeFromDataStore(VirtualMachineProfile profile, DataStore dataStore) {
        final List<VolumeVO> volumes = _volumeDao.findByInstanceAndType(profile.getVirtualMachine().getId(), Volume.Type.ROOT);
        if (CollectionUtils.isNotEmpty(volumes)) {
            dataStore = pickDataStoreFromVolumes(volumes);
        }
        return dataStore;
    }

    private DataStore pickDataStoreFromVolumes(List<VolumeVO> volumes) {
        DataStore dataStore = null;
        for (Volume vol : volumes) {
            if (vol.getPoolId() != null && doesVolumeStateCheckout(vol)) {
                dataStore = _dataStoreMgr.getDataStore(vol.getPoolId(), DataStoreRole.Primary);
                if (dataStore != null) {
                    return dataStore;
                }
            }
        }
        return dataStore;
    }

    private boolean doesVolumeStateCheckout(Volume vol) {
        switch (vol.getState()) {
        case Allocated:
        case Creating:
        case Ready:
        case Snapshotting:
        case RevertSnapshotting:
        case Resizing:
        case Copying:
        case Attaching:
            return true;
        case Migrating:
        case Expunging:
        case Expunged:
        case Destroy:
        case Destroying:
        case UploadOp:
        case Uploaded:
        case NotUploaded:
        case UploadInProgress:
        case UploadError:
        case UploadAbandoned:
            return false;
        default:
            throw new IllegalArgumentException("volume has a state that does not compute: " +vol.getState());
        }
    }

    private Long findAgentIdForImageStore(final DataStore dataStore) throws ResourceUnavailableException {
        EndPoint endpoint = _ep.select(dataStore);
        if (endpoint == null) {
            throw new ResourceUnavailableException("Config drive ISO operation failed: no secondary storage agent is available for image store " + dataStore.getId(),
                    dataStore.getClass(), dataStore.getId());
        }
        return endpoint.getId();
    }

    private Long findConfigDriveHost(VirtualMachineProfile profile, DeployDestination dest) {
        if (dest.getHost() != null) {
            return dest.getHost().getId();
        }
        VirtualMachine vm = profile.getVirtualMachine();
        return vm.getHostId() == null ? vm.getLastHostId() : vm.getHostId();
    }

    private Location getConfigDriveLocation(long vmId) {
        final VMInstanceDetailVO vmDetailConfigDriveLocation = _vmInstanceDetailsDao.findDetail(vmId, VmDetailConstants.CONFIG_DRIVE_LOCATION);
        if (vmDetailConfigDriveLocation != null) {
            if (Location.HOST.toString().equalsIgnoreCase(vmDetailConfigDriveLocation.getValue())) {
                return Location.HOST;
            } else if (Location.PRIMARY.toString().equalsIgnoreCase(vmDetailConfigDriveLocation.getValue())) {
                return Location.PRIMARY;
            } else {
                return Location.SECONDARY;
            }
        }
        return Location.SECONDARY;
    }

    private boolean isConfigDriveIsoOnHostCache(long vmId) {
        final VMInstanceDetailVO vmDetailConfigDriveLocation = _vmInstanceDetailsDao.findDetail(vmId, VmDetailConstants.CONFIG_DRIVE_LOCATION);
        if (vmDetailConfigDriveLocation != null && Location.HOST.toString().equalsIgnoreCase(vmDetailConfigDriveLocation.getValue())) {
            return true;
        }
        return false;
    }

    private boolean isLastConfigDriveIsoOnHostCache(long vmId) {
        final VMInstanceDetailVO vmDetailLastConfigDriveLocation = _vmInstanceDetailsDao.findDetail(vmId, VmDetailConstants.LAST_CONFIG_DRIVE_LOCATION);
        if (vmDetailLastConfigDriveLocation == null) {
            return isConfigDriveIsoOnHostCache(vmId);
        }
        if (Location.HOST.toString().equalsIgnoreCase(vmDetailLastConfigDriveLocation.getValue())) {
            return true;
        }
        return false;
    }

    private boolean createConfigDriveIsoOnHostCache(NicProfile nic, VirtualMachineProfile profile, Host host) throws ResourceUnavailableException {
        if (host == null) {
            throw new ResourceUnavailableException("Config drive iso creation failed, dest host not available",
                    ConfigDriveNetworkElement.class, 0L);
        }

        validateConfigDriveDiskSlot(profile);
        logger.debug("Creating config drive ISO for instance {} on host {}", profile.getId(), host.getId());

        Map<String, String> customUserdataParamMap = getVMCustomUserdataParamMap(profile.getId());

        final String isoFileName = ConfigDrive.configIsoFileName(profile.getInstanceName());
        final String isoPath = ConfigDrive.createConfigDrivePath(profile.getInstanceName());
        List<NicProfile> nicProfiles = _networkOrchestrationService.getNicProfiles(profile.getVirtualMachine().getId(), profile.getHypervisorType());
        final Map<Long, List<Service>> supportedServices = getSupportedServicesByElementForNetwork(nicProfiles);
        final String isoData = ConfigDriveBuilder.buildConfigDrive(nicProfiles, profile.getVmData(), isoFileName, profile.getConfigDriveLabel(), customUserdataParamMap, supportedServices);
        final HandleConfigDriveIsoCommand configDriveIsoCommand = new HandleConfigDriveIsoCommand(isoPath, isoData, null, false, true, true);

        final Location location = sendConfigDriveCreation(host.getId(), configDriveIsoCommand, "KVM host cache", profile.getId());
        profile.setConfigDriveLocation(location);
        updateConfigDriveLocationInVMDetails(profile.getId(), location);
        addConfigDriveDisk(profile, null);
        return true;
    }

    private boolean deleteConfigDriveIsoOnHostCache(final VirtualMachine vm, final Long hostId) throws ResourceUnavailableException {
        if (hostId == null) {
            throw new ResourceUnavailableException("Config drive ISO deletion failed, host not available",
                    ConfigDriveNetworkElement.class, 0L);
        }

        final String isoPath = ConfigDrive.createConfigDrivePath(vm.getInstanceName());
        final HandleConfigDriveIsoCommand configDriveIsoCommand = new HandleConfigDriveIsoCommand(isoPath, null, null, false, true, false);
        HostVO hostVO = _hostDao.findById(hostId);
        logger.debug("Deleting config drive ISO for Instance: {} on host: {}({})", vm, hostId, hostVO);
        if (hostVO == null) {
            logger.warn(String.format("Host %s appears to be unavailable, skipping deletion of config-drive ISO on host cache", hostId));
            return false;
        }
        if (!Arrays.asList(Status.Up, Status.Connecting).contains(hostVO.getStatus())) {
            logger.warn("Host status {} is not Up or Connecting, skipping deletion of config-drive ISO on host cache", hostVO);
            return false;
        }

        final HandleConfigDriveIsoAnswer answer = (HandleConfigDriveIsoAnswer) agentManager.easySend(hostId, configDriveIsoCommand);
        if (answer == null) {
            throw new CloudRuntimeException(String.format("Unable to get an answer to handle config drive deletion for vm: %s on host: %s", vm, hostVO));
        }

        if (!answer.getResult()) {
            logger.error("Failed to remove config drive for instance: {}", vm);
            return false;
        }
        return true;
    }

    private Map<Long, List<Network.Service>> getSupportedServicesByElementForNetwork(List<NicProfile> nics) {

        Map<Long, List<Network.Service>> supportedServices = new HashMap<>();
        for (NicProfile nic: nics) {
            ArrayList<Network.Service> serviceList = new ArrayList<>();
            if (_networkModel.isProviderSupportServiceInNetwork(nic.getNetworkId(), Service.Dns, getProvider())) {
                serviceList.add(Service.Dns);
            }
            if (_networkModel.isProviderSupportServiceInNetwork(nic.getNetworkId(), Service.UserData, getProvider())) {
                serviceList.add(Service.UserData);
            }
            if (_networkModel.isProviderSupportServiceInNetwork(nic.getNetworkId(), Service.Dhcp, getProvider())) {
                serviceList.add(Service.Dhcp);
            }
            supportedServices.put(nic.getId(), serviceList);
        }

        return supportedServices;
    }

    public boolean createConfigDriveIso(NicProfile nic, VirtualMachineProfile profile, DeployDestination dest, DiskTO disk) throws ResourceUnavailableException {
        long zoneId = dest.getDataCenter().getId();
        boolean preferHostCache = VirtualMachineManager.VmConfigDriveForceHostCacheUse.valueIn(zoneId);
        boolean preferPrimary = VirtualMachineManager.VmConfigDriveOnPrimaryPool.valueIn(zoneId);
        if ((preferHostCache || preferPrimary) && profile.getHypervisorType() != HypervisorType.KVM) {
            throw configDriveCreationFailure("the configured host cache or primary storage mode requires KVM", profile.getId());
        }
        DataStore dataStore = preferHostCache ? null : getDatastoreForConfigDriveIso(disk, profile, dest, preferPrimary);
        if (!preferHostCache && dataStore == null) {
            throw configDriveCreationFailure("the requested primary pool or existing ISO store is unavailable in zone " + zoneId, profile.getId());
        }
        DataStoreTO store = dataStore == null ? null : dataStore.getTO();
        if (!preferHostCache && !(store instanceof NfsTO || store instanceof PrimaryDataStoreTO)) {
            throw configDriveCreationFailure("the selected store does not support ConfigDrive ISO storage", profile.getId());
        }

        boolean onPrimary = store instanceof PrimaryDataStoreTO;
        Long agentId = preferHostCache || onPrimary ? findConfigDriveHost(profile, dest) : findAgentIdForImageStore(dataStore);
        String target = preferHostCache ? "KVM host cache" : onPrimary ? "primary storage" : "secondary NFS";
        if (agentId == null) {
            throw configDriveCreationFailure("no destination agent is available for " + target, profile.getId());
        }

        validateConfigDriveDiskSlot(profile);
        logger.debug("Creating config drive ISO for instance {} in zone {} on {} through agent {}", profile.getId(), zoneId, target, agentId);

        Map<String, String> customUserdataParamMap = getVMCustomUserdataParamMap(profile.getId());

        final String isoFileName = ConfigDrive.configIsoFileName(profile.getInstanceName());
        final String isoPath = ConfigDrive.createConfigDrivePath(profile.getInstanceName());
        List<NicProfile> nicProfiles = _networkOrchestrationService.getNicProfiles(nic.getVirtualMachineId(), profile.getHypervisorType());
        final Map<Long, List<Service>> supportedServices = getSupportedServicesByElementForNetwork(nicProfiles);
        final String isoData = ConfigDriveBuilder.buildConfigDrive(
                nicProfiles, profile.getVmData(), isoFileName, profile.getConfigDriveLabel(), customUserdataParamMap, supportedServices);
        boolean useHostCacheOnUnsupportedPool = onPrimary && VirtualMachineManager.VmConfigDriveUseHostCacheOnUnsupportedPool.valueIn(zoneId);
        HandleConfigDriveIsoCommand command = new HandleConfigDriveIsoCommand(isoPath, isoData, store, useHostCacheOnUnsupportedPool, preferHostCache, true);
        Location location = sendConfigDriveCreation(agentId, command, target, profile.getId());
        profile.setConfigDriveLocation(location);
        updateConfigDriveLocationInVMDetails(profile.getId(), location);
        addConfigDriveDisk(profile, location == Location.HOST ? null : dataStore);
        return true;
    }

    private ResourceUnavailableException configDriveCreationFailure(String reason, long resourceId) {
        return new ResourceUnavailableException("Config drive ISO creation failed: " + reason, ConfigDriveNetworkElement.class, resourceId);
    }

    private Location sendConfigDriveCreation(long agentId, HandleConfigDriveIsoCommand command, String target, long vmId) throws ResourceUnavailableException {
        Answer reply = agentManager.easySend(agentId, command);
        if (reply == null) {
            throw configDriveCreationFailure("agent " + agentId + " did not respond for " + target, vmId);
        }
        if (!(reply instanceof HandleConfigDriveIsoAnswer)) {
            throw configDriveCreationFailure("agent " + agentId + " did not return a ConfigDrive result for " + target, vmId);
        }
        HandleConfigDriveIsoAnswer answer = (HandleConfigDriveIsoAnswer) reply;
        if (!answer.getResult()) {
            // Only report a known agent outcome. The free-form reply could contain storage credentials or VM data.
            String details = answer.getDetails();
            if (details != null && details.startsWith("Config drive for KVM is not supported for pool type:") && command.getDestStore() instanceof PrimaryDataStoreTO) {
                PrimaryDataStoreTO store = (PrimaryDataStoreTO) command.getDestStore();
                throw configDriveCreationFailure("primary pool type " + store.getPoolType() + " does not support ConfigDrive and host cache fallback was not used", vmId);
            }
            throw configDriveCreationFailure("agent " + agentId + " could not write the ISO on " + target, vmId);
        }
        Location location = answer.getConfigDriveLocation();
        if (location == null || (command.isHostCachePreferred() && location != Location.HOST)) {
            throw configDriveCreationFailure("agent " + agentId + " returned an invalid ISO location for " + target, vmId);
        }
        return location;
    }

    private void updateConfigDriveLocationInVMDetails(long vmId, NetworkElement.Location configDriveLocation) {
        final VMInstanceDetailVO vmDetailConfigDriveLocation = _vmInstanceDetailsDao.findDetail(vmId, VmDetailConstants.CONFIG_DRIVE_LOCATION);
        if (vmDetailConfigDriveLocation != null) {
            if (!configDriveLocation.toString().equalsIgnoreCase(vmDetailConfigDriveLocation.getValue())) {
                _vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.LAST_CONFIG_DRIVE_LOCATION, vmDetailConfigDriveLocation.getValue(), false);
            } else {
                _vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.LAST_CONFIG_DRIVE_LOCATION);
            }
        }
        _vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.CONFIG_DRIVE_LOCATION, configDriveLocation.toString(), false);
    }

    private Map<String, String> getVMCustomUserdataParamMap(long vmId) {
        UserVmVO userVm = _userVmDao.findById(vmId);
        String userDataDetails = userVm.getUserDataDetails();
        Map<String,String> customUserdataParamMap = new HashMap<>();
        if(userDataDetails != null && !userDataDetails.isEmpty()) {
            userDataDetails = userDataDetails.substring(1, userDataDetails.length()-1);
            String[] keyValuePairs = userDataDetails.split(",");
            for(String pair : keyValuePairs)
            {
                final Pair<String, String> keyValue = StringUtils.getKeyValuePairWithSeparator(pair, "=");
                customUserdataParamMap.put(keyValue.first(), keyValue.second());
            }
        }

        return customUserdataParamMap;
    }

    private DataStore getDatastoreForConfigDriveIso(DiskTO disk, VirtualMachineProfile profile, DeployDestination dest, boolean preferPrimary)
            throws ResourceUnavailableException {
        DataStoreTO existingStore = disk == null || disk.getData() == null ? null : disk.getData().getDataStore();
        if (existingStore == null) {
            return preferPrimary ? findPrimaryDataStore(profile, dest) : findSecondaryDataStore(dest.getDataCenter().getId());
        }
        if (existingStore.getRole() == DataStoreRole.Primary) {
            return _dataStoreMgr.getDataStore(existingStore.getUuid(), DataStoreRole.Primary);
        }
        String storeUrl = existingStore.getUrl();
        if (org.apache.commons.lang3.StringUtils.isBlank(storeUrl)) {
            return null;
        }
        for (DataStore store : _dataStoreMgr.listImageStores()) {
            if (DataStoreRole.Image.equals(store.getRole()) && storeUrl.equals(store.getUri())) {
                return store;
            }
        }
        return null;
    }

    private boolean deleteConfigDriveIso(final VirtualMachine vm) throws ResourceUnavailableException {
        Long hostId  = (vm.getHostId() != null) ? vm.getHostId() : vm.getLastHostId();
        Location location = getConfigDriveLocation(vm.getId());
        if (hostId == null) {
            logger.info("The Instance was never booted; no config-drive ISO created for Instance {}", vm);
            return true;
        }
        if (location == Location.HOST) {
            return deleteConfigDriveIsoOnHostCache(vm, hostId);
        }

        Long agentId = null;
        DataStore dataStore = null;

        if (location == Location.SECONDARY) {
            dataStore = _dataStoreMgr.getImageStoreWithFreeCapacity(vm.getDataCenterId());
            if (dataStore != null) {
                agentId = findAgentIdForImageStore(dataStore);
            }
        } else if (location == Location.PRIMARY) {
            List<VolumeVO> volumes = _volumeDao.findByInstanceAndType(vm.getId(), Volume.Type.ROOT);
            if (volumes != null && volumes.size() > 0) {
                dataStore = _dataStoreMgr.getDataStore(volumes.get(0).getPoolId(), DataStoreRole.Primary);
            }
            agentId = hostId;
        }

        if (agentId == null || dataStore == null) {
            throw new ResourceUnavailableException("Config drive ISO deletion failed, agent or datastore not available",
                    ConfigDriveNetworkElement.class, 0L);
        }

        logger.debug("Deleting config drive ISO for Instance: {}", vm);

        final String isoPath = ConfigDrive.createConfigDrivePath(vm.getInstanceName());
        final HandleConfigDriveIsoCommand configDriveIsoCommand = new HandleConfigDriveIsoCommand(isoPath, null, dataStore.getTO(), false, false, false);

        final HandleConfigDriveIsoAnswer answer = (HandleConfigDriveIsoAnswer) agentManager.easySend(agentId, configDriveIsoCommand);
        if (!answer.getResult()) {
            logger.error("Failed to remove config drive for instance: {}", vm);
            return false;
        }
        return true;
    }

    void addConfigDriveDisk(final VirtualMachineProfile profile, final DataStore dataStore) throws ResourceUnavailableException {
        final String isoPath = ConfigDrive.createConfigDrivePath(profile.getInstanceName());
        if (dataStore == null && !isConfigDriveIsoOnHostCache(profile.getId())) {
            throw new ResourceUnavailableException("Config drive disk add failed, datastore not available",
                    ConfigDriveNetworkElement.class, 0L);
        }
        validateConfigDriveDiskSlot(profile);
        TemplateObjectTO dataTO = new TemplateObjectTO();
        if (dataStore != null) {
            dataTO.setDataStore(dataStore.getTO());
        }
        dataTO.setUuid(profile.getUuid());
        dataTO.setPath(isoPath);
        dataTO.setFormat(Storage.ImageFormat.ISO);
        // Replace stale ConfigDrive data and its empty reserved slot, preserving user ISO media.
        profile.getDisks().removeIf(disk -> disk != null && (isoPath.equals(disk.getPath())
                || (disk.getType() == Volume.Type.ISO && Long.valueOf(CONFIGDRIVEDISKSEQ).equals(disk.getDiskSeq()))));
        profile.addDisk(new DiskTO(dataTO, CONFIGDRIVEDISKSEQ.longValue(), isoPath, Volume.Type.ISO));
    }

    private void validateConfigDriveDiskSlot(VirtualMachineProfile profile) throws ResourceUnavailableException {
        String isoPath = ConfigDrive.createConfigDrivePath(profile.getInstanceName());
        for (DiskTO disk : profile.getDisks()) {
            if (disk != null && Long.valueOf(CONFIGDRIVEDISKSEQ).equals(disk.getDiskSeq())
                    && !isoPath.equals(disk.getPath())
                    && (disk.getType() != Volume.Type.ISO || org.apache.commons.lang3.StringUtils.isNotBlank(disk.getPath())
                        || (disk.getData() != null && org.apache.commons.lang3.StringUtils.isNotBlank(disk.getData().getPath())))) {
                throw new ResourceUnavailableException("Config drive CD-ROM slot is occupied by another disk",
                        ConfigDriveNetworkElement.class, profile.getId());
            }
        }
    }

    private boolean configureConfigDriveData(final VirtualMachineProfile profile, final NicProfile nic, final DeployDestination dest) {
        final UserVmVO vm = _userVmDao.findById(profile.getId());
        if (vm == null || vm.getType() != VirtualMachine.Type.User) {
            return false;
        }
        final Nic defaultNic = _networkModel.getDefaultNic(vm.getId());
        if (defaultNic != null) {
            final String sshPublicKey = getSshKey(profile);
            final String serviceOffering = _serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId()).getDisplayText();
            boolean isWindows = _guestOSCategoryDao.findById(_guestOSDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");
            String hostname = _hostDao.findById(vm.getHostId()).getName();
            String destHostname = null;
            if (dest.getHost() == null ) {
                destHostname = VirtualMachineManager.getHypervisorHostname(hostname);
            } else {
                destHostname = VirtualMachineManager.getHypervisorHostname(dest.getHost().getName());
            }
            final List<String[]> vmData = _networkModel.generateVmData(vm.getUserData(), vm.getUserDataDetails(), serviceOffering, vm.getDataCenterId(), vm.getInstanceName(), vm.getHostName(), vm.getId(),
                    vm.getUuid(), nic.getIPv4Address(), sshPublicKey, (String) profile.getParameter(VirtualMachineProfile.Param.VmPassword), isWindows, destHostname);
            profile.setVmData(vmData);
            profile.setConfigDriveLabel(VirtualMachineManager.VmConfigDriveLabel.value());
        }
        return true;
    }

    @Override
    public boolean addDhcpEntry(Network network, NicProfile nic, VirtualMachineProfile vm, DeployDestination dest,
            ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        // Update nic profile with required information.
        // Add network checks
        return true;
    }

    @Override
    public boolean configDhcpSupportForSubnet(Network network, NicProfile nic, VirtualMachineProfile vm,
            DeployDestination dest,
            ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean removeDhcpSupportForSubnet(Network network) throws ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean setExtraDhcpOptions(Network network, long nicId, Map<Integer, String> dhcpOptions) {
        return false;
    }

    @Override
    public boolean removeDhcpEntry(Network network, NicProfile nic,
            VirtualMachineProfile vmProfile) throws ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean addDnsEntry(Network network, NicProfile nic, VirtualMachineProfile vm, DeployDestination dest,
            ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean configDnsSupportForSubnet(Network network, NicProfile nic, VirtualMachineProfile vm,
            DeployDestination dest,
            ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean removeDnsSupportForSubnet(Network network) throws ResourceUnavailableException {
        return true;
    }
}
