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

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Iterator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.utils.DomainHelper;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.InternalIdentity;
import org.apache.cloudstack.api.command.admin.backup.CloneBackupOfferingCmd;
import org.apache.cloudstack.api.command.admin.backup.DeleteBackupOfferingCmd;
import org.apache.cloudstack.api.command.admin.backup.ImportBackupOfferingCmd;
import org.apache.cloudstack.api.command.admin.backup.ListBackupProviderOfferingsCmd;
import org.apache.cloudstack.api.command.admin.backup.ListBackupProvidersForZoneCmd;
import org.apache.cloudstack.api.command.admin.backup.ListBackupProvidersCmd;
import org.apache.cloudstack.api.command.admin.backup.UpdateBackupOfferingCmd;
import org.apache.cloudstack.api.command.admin.backup.UpdateNetBackupCmd;
import org.apache.cloudstack.api.command.admin.vm.CreateVMFromBackupCmdByAdmin;
import org.apache.cloudstack.api.command.user.backup.AssignVirtualMachineToBackupOfferingCmd;
import org.apache.cloudstack.api.command.user.backup.CreateAblestackVeeamBackupCmd;
import org.apache.cloudstack.api.command.user.backup.CreateBackupCmd;
import org.apache.cloudstack.api.command.user.backup.CreateNetBackupCmd;
import org.apache.cloudstack.api.command.user.backup.ImportAblestackVeeamBackupSeedCmd;
import org.apache.cloudstack.api.command.user.backup.ListAblestackVeeamBackupsCmd;
import org.apache.cloudstack.api.command.user.backup.ListVeeamRestorePointsCmd;
import org.apache.cloudstack.api.command.user.backup.RestoreAblestackVeeamBackupCmd;
import org.apache.cloudstack.api.response.BackupRestorePointResponse;
import org.apache.cloudstack.api.command.user.backup.CreateBackupScheduleCmd;
import org.apache.cloudstack.api.command.user.backup.DeleteBackupCmd;
import org.apache.cloudstack.api.command.user.backup.DeleteBackupScheduleCmd;
import org.apache.cloudstack.api.command.user.backup.ListBackupOfferingsCmd;
import org.apache.cloudstack.api.command.user.backup.ListBackupScheduleCmd;
import org.apache.cloudstack.api.command.user.backup.ListBackupsCmd;
import org.apache.cloudstack.api.command.user.backup.PrepareNetBackupRestoreCmd;
import org.apache.cloudstack.api.command.user.backup.RemoveVirtualMachineFromBackupOfferingCmd;
import org.apache.cloudstack.api.command.user.backup.RestoreBackupCmd;
import org.apache.cloudstack.api.command.user.backup.RestoreNetBackupCmd;
import org.apache.cloudstack.api.command.user.backup.RestoreVolumeFromBackupAndAttachToVMCmd;
import org.apache.cloudstack.api.command.user.backup.UpdateBackupScheduleCmd;
import org.apache.cloudstack.api.command.user.backup.repository.AddBackupRepositoryCmd;
import org.apache.cloudstack.api.command.user.backup.repository.DeleteBackupRepositoryCmd;
import org.apache.cloudstack.api.command.user.backup.repository.ListBackupRepositoriesCmd;
import org.apache.cloudstack.api.command.user.backup.repository.UpdateBackupRepositoryCmd;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBackupCmd;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBxBackupCmd;
import org.apache.cloudstack.api.response.BackupResponse;
import org.apache.cloudstack.backup.NetBackupRestoreCoordinator.RestorePhase;
import org.apache.cloudstack.backup.NetBackupRestoreCoordinator.RestoreResolution;
import org.apache.cloudstack.backup.NetBackupRestoreCoordinator.RestoreSession;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDetailsDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.jobs.AsyncJobDispatcher;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.managed.context.ManagedContextTimerTask;
import org.apache.cloudstack.poll.BackgroundPollManager;
import org.apache.cloudstack.poll.BackgroundPollTask;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import com.cloud.alert.AlertManager;
import com.cloud.api.ApiDispatcher;
import com.cloud.api.ApiGsonHelper;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityVO;
import com.cloud.configuration.Resource;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.event.EventVO;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkService;
import com.cloud.network.dao.NetworkDao;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.projects.Project;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.serializer.GsonHelper;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.DomainManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.DateUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDiskInfo;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
public class BackupManagerImpl extends ManagerBase implements BackupManager {

    @Inject
    private BackupDao backupDao;
    @Inject
    private BackupDetailsDao backupDetailsDao;
    @Inject
    private BackupScheduleDao backupScheduleDao;
    @Inject
    private BackupOfferingDao backupOfferingDao;
    @Inject
    private BackupOfferingDetailsDao backupOfferingDetailsDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private AccountService accountService;
    @Inject
    private AccountManager accountManager;
    @Inject
    private DomainManager domainManager;
    @Inject
    private AccountDao accountDao;
    @Inject
    private DomainDao domainDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private BackgroundPollManager backgroundPollManager;
    @Inject
    private HostDao hostDao;
    @Inject
    private HypervisorGuruManager hypervisorGuruManager;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VMTemplateDao vmTemplateDao;
    @Inject
    private UserVmJoinDao userVmJoinDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private NetBackupRestoreCoordinator netBackupRestoreCoordinator;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NetworkService networkService;
    @Inject
    private ApiDispatcher apiDispatcher;
    @Inject
    private AsyncJobManager asyncJobManager;
    @Inject
    private VirtualMachineManager virtualMachineManager;
    @Inject
    private VolumeApiService volumeApiService;
    @Inject
    private ResourceLimitService resourceLimitMgr;
    @Inject
    private AlertManager alertManager;
    @Inject
    private GuestOSDao _guestOSDao;
    @Inject
    private DomainHelper domainHelper;
    @Inject
    ReservationDao reservationDao;

    private AsyncJobDispatcher asyncJobDispatcher;
    private Timer backupTimer;
    private Date currentTimestamp;
    private static final int POST_RESTORE_MAINTENANCE_MAX_RETRIES = 5;
    private static final long POST_RESTORE_MAINTENANCE_RETRY_INTERVAL_MS = 60_000L;

    private static Map<String, BackupProvider> backupProvidersMap = new HashMap<>();
    private static final String ABLESTACK_NETBACKUP_PROVIDER_NAME = "ablestack-netbackup";
    private static final int NETBACKUP_RESTORE_PATH_DISCOVERY_WINDOW_SECONDS = 1800;
    private static final int NETBACKUP_PREPARE_RESTORE_PATH_DISCOVERY_WINDOW_SECONDS = 120;
    private List<BackupProvider> backupProviders;
    private final List<PostRestoreMaintenanceTask> postRestoreMaintenanceTasks = Collections.synchronizedList(new ArrayList<>());

    public AsyncJobDispatcher getAsyncJobDispatcher() {
        return asyncJobDispatcher;
    }

    public void setAsyncJobDispatcher(final AsyncJobDispatcher dispatcher) {
        asyncJobDispatcher = dispatcher;
    }

    @Override
    public List<BackupOffering> listBackupProviderOfferings(final Long zoneId, final String providerName) {
        if (zoneId == null || zoneId < 1) {
            throw new CloudRuntimeException("Invalid zone ID passed");
        }
        validateBackupForZone(zoneId);
        final Account account = CallContext.current().getCallingAccount();
        if (!accountService.isRootAdmin(account.getId())) {
            throw new PermissionDeniedException("Parameter external can only be specified by a Root Admin, permission denied");
        }
        List<BackupOffering> allOfferings = new ArrayList<>();
        List<BackupProvider> providers = getBackupProvidersForZone(zoneId);
        final String canonicalProviderName = BackupProviderNameUtils.canonicalize(providerName);

        for (BackupProvider provider : providers) {
            if (provider.getName().equalsIgnoreCase(canonicalProviderName)) {
                try {
                    logger.debug("Listing external backup offerings for provider {} in zone {}", provider.getName(), zoneId);
                    List<BackupOffering> offerings = provider.listBackupOfferings(zoneId);
                    if (offerings != null && !offerings.isEmpty()) {
                        allOfferings.addAll(offerings);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to list offerings from provider {} in zone {}: {}", provider.getName(), zoneId, e.getMessage());
                }
            }
        }
        return allOfferings;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_IMPORT_OFFERING, eventDescription = "importing backup offering", async = true)
    public BackupOffering importBackupOffering(final ImportBackupOfferingCmd cmd) {
        validateBackupForZone(cmd.getZoneId());

        String providerName = BackupProviderNameUtils.canonicalize(cmd.getProvider());
        if (StringUtils.isEmpty(providerName)) {
            throw new CloudRuntimeException("Provider name must be specified");
        }

        List<BackupProvider> zoneProviders = getBackupProvidersForZone(cmd.getZoneId());
        boolean providerFound = zoneProviders.stream().anyMatch(p -> p.getName().equalsIgnoreCase(providerName));

        if (!providerFound) {
            throw new CloudRuntimeException("Provider " + providerName + " is not enabled for zone " + cmd.getZoneId());
        }

        final BackupOffering existingOffering = backupOfferingDao.findByExternalId(cmd.getExternalId(), cmd.getZoneId());
        if (existingOffering != null) {
            throw new CloudRuntimeException("A backup offering with external ID " + cmd.getExternalId() + " already exists");
        }
        if (backupOfferingDao.findByName(cmd.getName(), cmd.getZoneId()) != null) {
            throw new CloudRuntimeException("A backup offering with the same name already exists in this zone");
        }

        if (CollectionUtils.isNotEmpty(cmd.getDomainIds())) {
            for (final Long domainId: cmd.getDomainIds()) {
                if (domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }

        final Account caller = CallContext.current().getCallingAccount();
        List<Long> filteredDomainIds = cmd.getDomainIds() == null ? new ArrayList<>() : new ArrayList<>(cmd.getDomainIds());
        if (filteredDomainIds.size() > 1) {
            filteredDomainIds = domainHelper.filterChildSubDomains(filteredDomainIds);
        }

        final BackupProvider provider = getBackupProvider(providerName);
        if (!provider.isValidProviderOffering(cmd.getZoneId(), cmd.getExternalId())) {
            throw new CloudRuntimeException("Backup offering '" + cmd.getExternalId() + "' does not exist on provider " + provider.getName() + " on zone " + cmd.getZoneId());
        }

        if (!provider.checkBackupAgent(cmd.getZoneId())) {
            throw new CloudRuntimeException("The backup offering cannot be imported because the host does not have the agent properly installed on provider " + provider.getName() + "on zone" + cmd.getZoneId() + ". Please try again later.");
        }

        if (!provider.importBackupPlan(cmd.getZoneId(), cmd.getRetentionPeriod(), cmd.getExternalId())) {
            throw new CloudRuntimeException("The backup offering cannot be imported because failed setting on provider " + provider.getName() + "on zone" + cmd.getZoneId());
        }

        final BackupOfferingVO offering = new BackupOfferingVO(cmd.getZoneId(), cmd.getExternalId(), provider.getName(),
                cmd.getName(), cmd.getDescription(), cmd.getUserDrivenBackups(), cmd.getRetentionPeriod());

        final BackupOfferingVO savedOffering = backupOfferingDao.persist(offering);
        if (savedOffering == null) {
            throw new CloudRuntimeException("Unable to create backup offering: " + cmd.getExternalId() + ", name: " + cmd.getName());
        }
        if (CollectionUtils.isNotEmpty(filteredDomainIds)) {
            List<BackupOfferingDetailsVO> detailsVOList = new ArrayList<>();
            for (Long domainId : filteredDomainIds) {
                detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
            }
            if (!detailsVOList.isEmpty()) {
                backupOfferingDetailsDao.saveDetails(detailsVOList);
            }
        }
        logger.debug("Successfully created backup offering " + cmd.getName() + " mapped to backup provider offering " + cmd.getExternalId());
        return savedOffering;
    }

    public List<Long> getBackupOfferingDomains(Long offeringId) {
        final BackupOffering backupOffering = backupOfferingDao.findById(offeringId);
        if (backupOffering == null) {
            throw new InvalidParameterValueException("Unable to find backup offering for id: " + offeringId);
        }
        return backupOfferingDetailsDao.findDomainIds(offeringId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_OFFERING_CLONE, eventDescription = "cloning backup offering")
    public BackupOffering cloneBackupOffering(final CloneBackupOfferingCmd cmd) {
        final BackupOfferingVO sourceOffering = backupOfferingDao.findById(cmd.getSourceOfferingId());
        if (sourceOffering == null) {
            throw new InvalidParameterValueException("Unable to find backup offering with ID: " + cmd.getSourceOfferingId());
        }

        validateBackupForZone(sourceOffering.getZoneId());

        if (backupOfferingDao.findByName(cmd.getName(), sourceOffering.getZoneId()) != null) {
            throw new CloudRuntimeException("A backup offering with the name '" + cmd.getName() + "' already exists in this zone");
        }

        final String description = cmd.getDescription() != null ? cmd.getDescription() : sourceOffering.getDescription();
        final String externalId = cmd.getExternalId() != null ? cmd.getExternalId() : sourceOffering.getExternalId();
        final boolean userDrivenBackups = cmd.getUserDrivenBackups() != null ? cmd.getUserDrivenBackups() : sourceOffering.isUserDrivenBackupAllowed();
        final Long zoneId = cmd.getZoneId() != null ? cmd.getZoneId() : sourceOffering.getZoneId();

        if (!Objects.equals(sourceOffering.getExternalId(), externalId) || !Objects.equals(sourceOffering.getZoneId(), zoneId)) {
            final BackupProvider provider = getBackupProvider(sourceOffering.getProvider());
            if (!provider.isValidProviderOffering(zoneId, externalId)) {
                throw new CloudRuntimeException("Backup offering '" + externalId + "' does not exist on provider " + provider.getName() + " on zone " + zoneId);
            }
        }

        final BackupOffering existingOffering = backupOfferingDao.findByExternalId(externalId, zoneId);
        if (existingOffering != null) {
            throw new CloudRuntimeException("A backup offering with external ID '" + externalId + "' already exists in this zone");
        }

        final BackupOfferingVO clonedOffering = new BackupOfferingVO(
                zoneId,
                externalId,
                sourceOffering.getProvider(),
                cmd.getName(),
                description,
                userDrivenBackups,
                sourceOffering.getRetentionPeriod()
        );

        final BackupOfferingVO savedOffering = backupOfferingDao.persist(clonedOffering);
        if (savedOffering == null) {
            throw new CloudRuntimeException("Unable to clone backup offering from ID: " + cmd.getSourceOfferingId());
        }

        List<Long> filteredDomainIds = cmd.getDomainIds() == null ? new ArrayList<>() : new ArrayList<>(cmd.getDomainIds());
        Collections.sort(filteredDomainIds);
        updateBackupOfferingDomainDetail(savedOffering, filteredDomainIds);

        logger.debug("Successfully cloned backup offering '" + sourceOffering.getName() + "' (ID: " + cmd.getSourceOfferingId() + ") to '" + cmd.getName() + "' (ID: " + savedOffering.getId() + ")");
        return savedOffering;
    }

    private void updateBackupOfferingDomainDetail(BackupOfferingVO savedOffering, List<Long> filteredDomainIds) {
        if (filteredDomainIds.size() > 1) {
            filteredDomainIds = domainHelper.filterChildSubDomains(filteredDomainIds);
        }

        if (CollectionUtils.isNotEmpty(filteredDomainIds)) {
            List<BackupOfferingDetailsVO> detailsVOList = new ArrayList<>();
            for (Long domainId : filteredDomainIds) {
                if (domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
                detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
            }
            if (!detailsVOList.isEmpty()) {
                backupOfferingDetailsDao.saveDetails(detailsVOList);
            }
        }
    }

    @Override
    public Pair<List<BackupOffering>, Integer> listBackupOfferings(final ListBackupOfferingsCmd cmd) {
        final Long offeringId = cmd.getOfferingId();
        final Long zoneId = cmd.getZoneId();
        final String keyword = cmd.getKeyword();
        Long domainId = cmd.getDomainId();

        if (offeringId != null) {
            BackupOfferingVO offering = backupOfferingDao.findById(offeringId);
            if (offering == null) {
                throw new CloudRuntimeException("Offering ID " + offeringId + " does not exist");
            }
            return new Pair<>(Collections.singletonList(offering), 1);
        }

        final Filter searchFilter = new Filter(BackupOfferingVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<BackupOfferingVO> sb = backupOfferingDao.createSearchBuilder();
        sb.and("zone_id", sb.entity().getZoneId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.LIKE);

        CallContext ctx = CallContext.current();
        final Account caller = ctx.getCallingAccount();
        if (Account.Type.ADMIN != caller.getType() && domainId == null) {
            domainId = caller.getDomainId();
        }

        if (Account.Type.NORMAL == caller.getType()) {
            sb.and("user_backups_allowed", sb.entity().isUserDrivenBackupAllowed(), SearchCriteria.Op.EQ);
        }
        final SearchCriteria<BackupOfferingVO> sc = sb.create();

        if (zoneId != null) {
            sc.setParameters("zone_id", zoneId);
        }

        if (keyword != null) {
            sc.addOr("uuid", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.setParameters("name", "%" + keyword + "%");
        }

        if (Account.Type.NORMAL == caller.getType()) {
            sc.setParameters("user_backups_allowed", true);
        }

        Pair<List<BackupOfferingVO>, Integer> result = backupOfferingDao.searchAndCount(sc, searchFilter);

        if (domainId != null) {
            List<BackupOfferingVO> filteredOfferings = new ArrayList<>();
            for (BackupOfferingVO offering : result.first()) {
                List<Long> offeringDomains = backupOfferingDetailsDao.findDomainIds(offering.getId());
                if (offeringDomains.isEmpty() || offeringDomains.contains(domainId) || containsParentDomain(offeringDomains, domainId)) {
                    filteredOfferings.add(offering);
                }
            }
            return new Pair<>(new ArrayList<>(filteredOfferings), filteredOfferings.size());
        }

        return new Pair<>(new ArrayList<>(result.first()), result.second());
    }

    private boolean containsParentDomain(List<Long> offeringDomains, Long domainId) {
        for (Long offeringDomainId : offeringDomains) {
            if (domainDao.isChildDomain(offeringDomainId, domainId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean deleteBackupOffering(final Long offeringId) {
        final BackupOfferingVO offering = backupOfferingDao.findById(offeringId);
        if (offering == null) {
            throw new CloudRuntimeException("Could not find a backup offering with id: " + offeringId);
        }

        accountManager.checkAccess(CallContext.current().getCallingAccount(), offering);

        if (backupDao.listByOfferingId(offering.getId()).size() > 0) {
            throw new CloudRuntimeException("Backup Offering cannot be removed as it has backups associated with it.");
        }

        if (vmInstanceDao.listByZoneAndBackupOffering(offering.getZoneId(), offering.getId()).size() > 0) {
            throw new CloudRuntimeException("Backup offering is assigned to VMs, remove the assignment(s) in order to remove the offering.");
        }

        validateBackupForZone(offering.getZoneId());
        return backupOfferingDao.remove(offering.getId());
    }

    private String getNicDetailsAsJson(final Long vmId) {
        final List<UserVmJoinVO> userVmJoinVOs = userVmJoinDao.searchByIds(vmId);
        if (userVmJoinVOs != null && !userVmJoinVOs.isEmpty()) {
            final List<Map<String, String>> nics = new ArrayList<>();
            final Set<String> seen = new HashSet<>();
            for (UserVmJoinVO userVmJoinVO : userVmJoinVOs) {
                Map<String, String> nicInfo = new HashMap<>();
                String key = userVmJoinVO.getNetworkUuid();
                if (seen.add(key)) {
                    nicInfo.put(ApiConstants.NETWORK_ID, userVmJoinVO.getNetworkUuid());
                    nicInfo.put(ApiConstants.IP_ADDRESS, userVmJoinVO.getIpAddress());
                    nicInfo.put(ApiConstants.IP6_ADDRESS, userVmJoinVO.getIp6Address());
                    nicInfo.put(ApiConstants.MAC_ADDRESS, userVmJoinVO.getMacAddress());
                    nics.add(nicInfo);
                }
            }
            if (!nics.isEmpty()) {
                return new Gson().toJson(nics);
            }
        }
        return null;
    }

    @Override
    public Map<String, String> getBackupDetailsFromVM(VirtualMachine vm) {
        HashMap<String, String> details = new HashMap<>();

        ServiceOffering serviceOffering = serviceOfferingDao.findById(vm.getServiceOfferingId());
        details.put(ApiConstants.SERVICE_OFFERING_ID, serviceOffering.getUuid());
        VirtualMachineTemplate template = vmTemplateDao.findById(vm.getTemplateId());
        if (template != null) {
            long guestOSId = template.getGuestOSId();
            details.put(ApiConstants.TEMPLATE_ID, template.getUuid());
            GuestOSVO guestOS = _guestOSDao.findById(guestOSId);
            if (guestOS != null) {
                details.put(ApiConstants.OS_TYPE_ID, guestOS.getUuid());
                details.put(ApiConstants.OS_NAME, guestOS.getDisplayName());
            }
        }

        List<VMInstanceDetailVO> vmDetails = vmInstanceDetailsDao.listDetails(vm.getId());
        HashMap<String, String> settings = new HashMap<>();
        for (VMInstanceDetailVO detail : vmDetails) {
            settings.put(detail.getName(), detail.getValue());
        }
        if (!settings.isEmpty()) {
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            details.put(ApiConstants.VM_SETTINGS, gson.toJson(settings));
        }

        String nicsJson = getNicDetailsAsJson(vm.getId());
        if (nicsJson != null) {
            details.put(ApiConstants.NICS, nicsJson);
        }
        return details;
    }

    @Override
    public String getBackupNameFromVM(VirtualMachine vm) {
        String displayTime = DateUtil.displayDateInTimezone(DateUtil.GMT_TIMEZONE, new Date());
        return (vm.getHostName() + '-' + displayTime);
    }

    @Override
    public String createVolumeInfoFromVolumes(List<Volume> vmVolumes) {
        List<Backup.VolumeInfo> list = new ArrayList<>();
        vmVolumes.sort(Comparator.comparing(Volume::getDeviceId));
        for (Volume vol : vmVolumes) {
            DiskOfferingVO diskOffering = diskOfferingDao.findById(vol.getDiskOfferingId());
            Backup.VolumeInfo volumeInfo = new Backup.VolumeInfo(vol.getUuid(), vol.getPath(), vol.getVolumeType(), vol.getSize(),
                vol.getDeviceId(), diskOffering.getUuid(), vol.getMinIops(), vol.getMaxIops());
            list.add(volumeInfo);
        }
        return new Gson().toJson(list.toArray(), Backup.VolumeInfo[].class);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_OFFERING_ASSIGN, eventDescription = "assign VM to backup offering", async = true)
    public boolean assignVMToBackupOffering(Long vmId, Long offeringId) {
        final VMInstanceVO vm = findVmById(vmId);

        if (!Arrays.asList(VirtualMachine.State.Running, VirtualMachine.State.Stopped, VirtualMachine.State.Shutdown).contains(vm.getState())) {
            throw new CloudRuntimeException("VM is not in running or stopped state");
        }

        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        if (vm.getBackupOfferingId() != null) {
            throw new CloudRuntimeException("VM already is assigned to a backup offering, please remove the VM from its previous offering");
        }

        final BackupOfferingVO offering = backupOfferingDao.findById(offeringId);
        if (offering == null) {
            throw new CloudRuntimeException("Provided backup offering does not exist");
        }

        Account owner = accountManager.getAccount(vm.getAccountId());
        if (owner == null) {
            throw new CloudRuntimeException("Unable to find the owner of the VM");
        }
        accountManager.checkAccess(owner, offering);

        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        if (backupProvider == null) {
            throw new CloudRuntimeException("Failed to get the backup provider for the zone, please contact the administrator");
        }

        return transactionAssignVMToBackupOffering(vm, offering, backupProvider) != null;
    }

    private VMInstanceVO transactionAssignVMToBackupOffering(VMInstanceVO vm, BackupOfferingVO offering, BackupProvider backupProvider) {
        return Transaction.execute(TransactionLegacy.CLOUD_DB, new TransactionCallback<VMInstanceVO>() {
            @Override
            public VMInstanceVO doInTransaction(final TransactionStatus status) {
                try {
                    long vmId = vm.getId();
                    vm.setBackupOfferingId(offering.getId());
                    vm.setBackupVolumes(createVolumeInfoFromVolumes(new ArrayList<>(volumeDao.findByInstance(vmId))));

                    if (!backupProvider.assignVMToBackupOffering(vm, offering)) {
                        throw new CloudRuntimeException("Failed to assign the VM to the backup offering, please try removing the assignment and try again.");
                    }

                    if (!vmInstanceDao.update(vmId, vm)) {
                        backupProvider.removeVMFromBackupOffering(vm);
                        throw new CloudRuntimeException("Failed to update VM assignment to the backup offering in the DB, please try again.");
                    }

                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_BACKUP_OFFERING_ASSIGN, vm.getAccountId(), vm.getDataCenterId(), vmId,
                            "Backup-" + vm.getHostName() + "-" + vm.getUuid(), vm.getBackupOfferingId(), null, null, Backup.class.getSimpleName(), vm.getUuid());
                    logger.debug(String.format("VM [%s] successfully added to Backup Offering [%s].", ReflectionToStringBuilderUtils.reflectOnlySelectedFields(vm,
                            "uuid", "instanceName", "backupOfferingId", "backupVolumes"), ReflectionToStringBuilderUtils.reflectOnlySelectedFields(offering,
                                    "uuid", "name", "externalId", "provider")));
                } catch (Exception e) {
                    String msg = String.format("Failed to assign VM [%s] to the Backup Offering [%s], using provider [name: %s, class: %s], due to: [%s].",
                            ReflectionToStringBuilderUtils.reflectOnlySelectedFields(vm, "uuid", "instanceName", "backupOfferingId", "backupVolumes"),
                            ReflectionToStringBuilderUtils.reflectOnlySelectedFields(offering, "uuid", "name", "externalId", "provider"),
                            backupProvider.getName(), backupProvider.getClass().getSimpleName(), e.getMessage());
                    logger.error(msg);
                    logger.debug(msg, e);
                    return null;
                }
                return vm;
            }
        });
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_OFFERING_REMOVE, eventDescription = "remove VM from backup offering", async = true)
    public boolean removeVMFromBackupOffering(final Long vmId, final boolean forced) {
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
        if (vm == null) {
            throw new CloudRuntimeException(String.format("Can't find any VM with ID: [%s].", vmId));
        }

        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        final BackupOfferingVO offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("No previously configured backup offering found for the VM");
        }

        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        if (backupProvider == null) {
            throw new CloudRuntimeException("Failed to get the backup provider for the zone, please contact the administrator");
        }

        if (!forced && backupProvider.willDeleteBackupsOnOfferingRemoval()) {
            String message = String.format("To remove VM [id: %s, name: %s] from Backup Offering [id: %s, name: %s] using the provider [%s], please specify the "
                    + "forced:true option to allow the deletion of all jobs and backups for this VM or remove the backups that this VM has with the backup "
                    + "offering.", vm.getUuid(), vm.getInstanceName(), offering.getUuid(), offering.getName(), backupProvider.getClass().getSimpleName());
            throw new CloudRuntimeException(message);
        }

        boolean result = false;
        try {
            result = backupProvider.removeVMFromBackupOffering(vm);
            Long backupOfferingId = vm.getBackupOfferingId();
            vm.setBackupOfferingId(null);
            vm.setBackupVolumes(null);
            vm.setBackupExternalId(null);
            if (result && backupProvider.willDeleteBackupsOnOfferingRemoval()) {
                final List<Backup> backups = backupDao.listByVmId(null, vm.getId());
                for (final Backup backup : backups) {
                    backupDao.remove(backup.getId());
                }
            }
            if ((result || forced) && vmInstanceDao.update(vm.getId(), vm)) {
                final List<Backup> backups = backupDao.listByVmId(null, vm.getId());
                if (backups.size() == 0) {
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_BACKUP_OFFERING_REMOVED_AND_BACKUPS_DELETED, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                            "Backup-" + vm.getHostName() + "-" + vm.getUuid(), backupOfferingId, null, null,
                            Backup.class.getSimpleName(), vm.getUuid());
                }
                final List<BackupScheduleVO> backupSchedules = backupScheduleDao.listByVM(vm.getId());
                for(BackupSchedule backupSchedule: backupSchedules) {
                    backupScheduleDao.remove(backupSchedule.getId());
                }
                result = true;
            }
        } catch (Exception e) {
            logger.error("Exception caught when trying to remove VM [{}] from the backup offering [{}] due to: [{}].", vm, offering, e.getMessage(), e);
        }
        return result;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_SCHEDULE_CONFIGURE, eventDescription = "configuring VM backup schedule")
    public BackupSchedule configureBackupSchedule(CreateBackupScheduleCmd cmd) {
        final Long vmId = cmd.getVmId();
        final DateUtil.IntervalType intervalType = cmd.getIntervalType();
        final String scheduleString = cmd.getSchedule();
        final TimeZone timeZone = TimeZone.getTimeZone(cmd.getTimezone());

        if (intervalType == null) {
            throw new CloudRuntimeException("Invalid interval type provided");
        }

        final VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        if (vm.getBackupOfferingId() == null) {
            throw new CloudRuntimeException("Cannot configure backup schedule for the VM without having any backup offering");
        }

        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null || !offering.isUserDrivenBackupAllowed()) {
            throw new CloudRuntimeException("The selected backup offering does not allow user-defined backup schedule");
        }

        final int maxBackups = validateAndGetDefaultBackupRetentionIfRequired(cmd.getMaxBackups(), offering, vm);

        if (!BackupProviderNameUtils.isNasFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isCommvaultFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isVeeamFamily(offering.getProvider()) &&
                cmd.getQuiesceVM() != null) {
            throw new InvalidParameterValueException("Quiesce VM option is supported only for NAS, Commvault, and Ablestack Veeam backup providers");
        }

        final String timezoneId = timeZone.getID();
        if (!timezoneId.equals(cmd.getTimezone())) {
            logger.warn("Using timezone: " + timezoneId + " for running this snapshot policy as an equivalent of " + cmd.getTimezone());
        }

        Date nextDateTime = null;
        try {
            nextDateTime = DateUtil.getNextRunTime(intervalType, cmd.getSchedule(), timezoneId, null);
        } catch (Exception e) {
            throw new InvalidParameterValueException("Invalid schedule: " + cmd.getSchedule() + " for interval type: " + cmd.getIntervalType());
        }

        final BackupScheduleVO schedule = backupScheduleDao.findByVMAndIntervalType(vmId, intervalType);
        if (schedule == null) {
            return backupScheduleDao.persist(new BackupScheduleVO(vmId, intervalType, scheduleString, timezoneId, nextDateTime, maxBackups, cmd.getQuiesceVM(), vm.getAccountId(), vm.getDomainId()));
        }

        schedule.setScheduleType((short) intervalType.ordinal());
        schedule.setSchedule(scheduleString);
        schedule.setTimezone(timezoneId);
        schedule.setScheduledTimestamp(nextDateTime);
        schedule.setMaxBackups(maxBackups);
        schedule.setQuiesceVM(cmd.getQuiesceVM());
        backupScheduleDao.update(schedule.getId(), schedule);
        return backupScheduleDao.findById(schedule.getId());
    }

    /**
     * Validates the provided backup retention value and returns 0 as the default value if required.
     *
     * @param maxBackups The number of backups to retain, can be null
     * @param offering The backup offering
     * @param vm The VM associated with the backup schedule
     * @return The validated number of backups to retain. If maxBackups is null, returns 0 as the default value
     * @throws InvalidParameterValueException if the backup offering's provider is Veeam, or maxBackups is less than 0 or greater than the account and domain backup limits
     */
    protected int validateAndGetDefaultBackupRetentionIfRequired(Integer maxBackups, BackupOffering offering, VirtualMachine vm) {
        if (maxBackups == null) {
            return 0;
        }
        if ("veeam".equals(offering.getProvider())) {
            throw new InvalidParameterValueException("The maximum amount of backups to retain cannot be directly configured via Apache CloudStack for Veeam. " +
                    "Retention is managed directly in Veeam based on the settings specified when creating the backup job.");
        }
        if (maxBackups < 0) {
            throw new InvalidParameterValueException("maxbackups value for backup schedule must be a non-negative integer.");
        }

        Account owner = accountManager.getAccount(vm.getAccountId());
        long accountLimit = resourceLimitMgr.findCorrectResourceLimitForAccount(owner, Resource.ResourceType.backup, null);
        boolean exceededAccountLimit = accountLimit != -1 && maxBackups > accountLimit;

        long domainLimit = resourceLimitMgr.findCorrectResourceLimitForDomain(domainManager.getDomain(owner.getDomainId()), Resource.ResourceType.backup, null);
        boolean exceededDomainLimit = domainLimit != -1 && maxBackups > domainLimit;

        if (!accountManager.isRootAdmin(owner.getId()) && (exceededAccountLimit || exceededDomainLimit)) {
            throw new InvalidParameterValueException(
                    String.format("'maxbackups' should not exceed the domain/%s backup limit.", owner.getType() == Account.Type.PROJECT ? "project" : "account")
            );
        }

        return maxBackups;
    }

    public List<BackupSchedule> listBackupSchedules(ListBackupScheduleCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long id = cmd.getId();
        Long vmId = cmd.getVmId();
        List<Long> permittedAccounts = new ArrayList<>();
        Long domainId = null;
        Boolean isRecursive = null;
        String keyword = cmd.getKeyword();
        Project.ListProjectResourcesCriteria listProjectResourcesCriteria = null;

        if (vmId != null) {
            final VMInstanceVO vm = findVmById(vmId);
            validateBackupForZone(vm.getDataCenterId());
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        }

        Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject =
                new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, true, false);
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();
        listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        Filter searchFilter = new Filter(BackupScheduleVO.class, "id", false, null, null);
        SearchBuilder<BackupScheduleVO> searchBuilder = backupScheduleDao.createSearchBuilder();

        accountManager.buildACLSearchBuilder(searchBuilder, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        searchBuilder.and("id", searchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        if (vmId != null) {
            searchBuilder.and("vmId", searchBuilder.entity().getVmId(), SearchCriteria.Op.EQ);
        }
        if (keyword != null && !keyword.isEmpty()) {
            SearchBuilder<VMInstanceVO> vmSearch = vmInstanceDao.createSearchBuilder();
            vmSearch.and("hostName", vmSearch.entity().getHostName(), SearchCriteria.Op.LIKE);
            searchBuilder.join("vmJoin", vmSearch, searchBuilder.entity().getVmId(), vmSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        SearchCriteria<BackupScheduleVO> sc = searchBuilder.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.setParameters("id", id);
        }
        if (vmId != null) {
            sc.setParameters("vmId", vmId);
        }
        if (keyword != null && !keyword.isEmpty()) {
            sc.setJoinParameters("vmJoin", "hostName", "%" + keyword + "%");
        }

        Pair<List<BackupScheduleVO>, Integer> result = backupScheduleDao.searchAndCount(sc, searchFilter);
        return new ArrayList<>(result.first());
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_SCHEDULE_DELETE, eventDescription = "deleting VM backup schedule")
    public boolean deleteBackupSchedule(DeleteBackupScheduleCmd cmd) {
        Long vmId = cmd.getVmId();
        Long id = cmd.getId();
        if (ObjectUtils.allNull(vmId, id)) {
            throw new InvalidParameterValueException("Either instance ID or ID of backup schedule needs to be specified.");
        }

        if (Objects.nonNull(id)) {
            BackupSchedule schedule = backupScheduleDao.findById(id);
            if (schedule == null) {
                throw new InvalidParameterValueException("Could not find the requested backup schedule.");
            }
            checkCallerAccessToBackupScheduleVm(schedule.getVmId());
            return backupScheduleDao.remove(schedule.getId());
        }

        checkCallerAccessToBackupScheduleVm(vmId);
        return deleteAllVmBackupSchedules(vmId);
    }

    /**
     * Checks if the backup framework is enabled for the zone in which the VM with specified ID is allocated and
     * if the caller has access to the VM.
     *
     * @param vmId The ID of the virtual machine to check access for
     * @throws PermissionDeniedException if the caller doesn't have access to the VM
     * @throws CloudRuntimeException if the backup framework is disabled
     */
    protected void checkCallerAccessToBackupScheduleVm(long vmId) {
        VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
    }

    /**
     * Deletes all backup schedules associated with a specific VM.
     *
     * @param vmId The ID of the virtual machine whose backup schedules should be deleted
     * @return true if all backup schedules were successfully deleted, false if any deletion failed
     */
    protected boolean deleteAllVmBackupSchedules(long vmId) {
        List<BackupScheduleVO> vmBackupSchedules = backupScheduleDao.listByVM(vmId);
        boolean success = true;
        for (BackupScheduleVO vmBackupSchedule : vmBackupSchedules) {
            success = success && backupScheduleDao.remove(vmBackupSchedule.getId());
        }
        return success;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_CREATE, eventDescription = "creating VM backup", async = true)
    public boolean createBackup(CreateBackupCmd cmd, Object job) throws ResourceAllocationException {
        final long backupStartTime = System.currentTimeMillis();
        Long vmId = cmd.getVmId();
        Account caller = CallContext.current().getCallingAccount();

        final VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(caller, null, true, vm);

        if (vm.getBackupOfferingId() == null) {
            throw new CloudRuntimeException("VM has not backup offering configured, cannot create backup before assigning it to a backup offering");
        }

        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("VM backup offering not found");
        }

        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        if (backupProvider == null) {
            throw new CloudRuntimeException("VM backup provider not found for the offering");
        }

        if (!offering.isUserDrivenBackupAllowed()) {
            throw new CloudRuntimeException("The assigned backup offering does not allow ad-hoc user backup");
        }

        if (!BackupProviderNameUtils.isNasFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isCommvaultFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isVeeamFamily(offering.getProvider()) &&
                cmd.getQuiesceVM() != null) {
            throw new InvalidParameterValueException("Quiesce VM option is supported only for NAS, Commvault, and Ablestack Veeam backup providers");
        }

        Long backupScheduleId = getBackupScheduleId(job);
        boolean isScheduledBackup = backupScheduleId != null;
        logger.info("Starting VM backup request [vmId: {}, vmUuid: {}, vmName: {}, provider: {}, offeringId: {}, scheduleId: {}, scheduled: {}]",
                vm.getId(), vm.getUuid(), vm.getInstanceName(), offering.getProvider(), offering.getId(), backupScheduleId, isScheduledBackup);
        Account owner = accountManager.getAccount(vm.getAccountId());

        Long backupSize = 0L;
        for (final Volume volume: volumeDao.findByInstance(vmId)) {
            if (Volume.State.Ready.equals(volume.getState())) {
                Long volumeSize = volumeApiService.getVolumePhysicalSize(volume.getFormat(), volume.getPath(), volume.getChainInfo());
                if (volumeSize == null) {
                    volumeSize = volume.getSize();
                }
                backupSize += volumeSize;
            }
        }
        createCheckedBackup(cmd, owner, isScheduledBackup, backupSize, vm, vmId, backupProvider, backupScheduleId);
        if (isScheduledBackup) {
            deleteOldestBackupFromScheduleIfRequired(vmId, backupScheduleId);
        }
        logger.info("Completed VM backup request [vmId: {}, vmUuid: {}, vmName: {}, provider: {}, offeringId: {}, scheduleId: {}, elapsedMs: {}]",
                vm.getId(), vm.getUuid(), vm.getInstanceName(), offering.getProvider(), offering.getId(), backupScheduleId, System.currentTimeMillis() - backupStartTime);
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_CREATE, eventDescription = "importing Ablestack Veeam backup seed", async = true)
    public Backup importAblestackVeeamBackupSeed(ImportAblestackVeeamBackupSeedCmd cmd) throws ResourceAllocationException {
        final VMInstanceVO vm = findVmById(cmd.getVmId());
        validateBackupForZone(vm.getDataCenterId());
        final Account caller = CallContext.current().getCallingAccount();
        accountManager.checkAccess(caller, null, true, vm);

        if (vm.getBackupOfferingId() == null) {
            throw new CloudRuntimeException("VM must be assigned to an Ablestack Veeam backup offering before importing a seed");
        }

        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null || !BackupProviderNameUtils.isVeeamFamily(offering.getProvider())) {
            throw new CloudRuntimeException("VM backup offering must use the ablestack-veeam provider");
        }

        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());

        List<String> stagingPaths = null;
        if (org.apache.commons.lang3.StringUtils.isNotBlank(cmd.getStagingDiskPaths())) {
            stagingPaths = Arrays.stream(cmd.getStagingDiskPaths().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        final Pair<Boolean, Backup> result = backupProvider.importAblestackVeeamBackupSeed(
                vm, cmd.getVeeamRestorePointId(), stagingPaths, cmd.getSourceDiskFormat(), cmd.getBootstrapCheckpoint());

        if (!result.first() || result.second() == null) {
            throw new CloudRuntimeException("Failed to import Ablestack Veeam backup seed");
        }

        BackupVO backupVO = backupDao.findById(result.second().getId());
        if (cmd.getName() != null) {
            backupVO.setName(cmd.getName());
            backupDao.update(backupVO.getId(), backupVO);
        }

        resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup);
        if (result.second().getSize() != null) {
            resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup_storage, result.second().getSize());
        }
        return backupVO;
    }

    private BackupOffering validateVmAblestackVeeamOffering(final VMInstanceVO vm) {
        if (vm.getBackupOfferingId() == null) {
            throw new CloudRuntimeException("VM must be assigned to an Ablestack Veeam backup offering");
        }
        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null || !BackupProviderNameUtils.isVeeamFamily(offering.getProvider())) {
            throw new CloudRuntimeException("VM backup offering must use the ablestack-veeam provider");
        }
        return offering;
    }

    @Override
    public List<Backup.RestorePoint> listVeeamRestorePoints(final ListVeeamRestorePointsCmd cmd) {
        final VMInstanceVO vm = findVmById(cmd.getVmId());
        validateBackupForZone(vm.getDataCenterId());
        final Account caller = CallContext.current().getCallingAccount();
        accountManager.checkAccess(caller, null, true, vm);
        validateVmAblestackVeeamOffering(vm);
        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        return backupProvider.listRestorePoints(vm);
    }

    @Override
    public List<BackupRestorePointResponse> createVeeamRestorePointResponses(final List<Backup.RestorePoint> points) {
        final List<BackupRestorePointResponse> responses = new ArrayList<>();
        if (points == null) {
            return responses;
        }
        for (final Backup.RestorePoint point : points) {
            final BackupRestorePointResponse response = new BackupRestorePointResponse();
            response.setId(point.getId());
            response.setCreated(point.getCreated());
            response.setType(point.getType());
            response.setObjectName(point.getId());
            responses.add(response);
        }
        return responses;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_CREATE, eventDescription = "creating Ablestack Veeam NAS backup", async = true)
    public boolean createAblestackVeeamBackup(final CreateAblestackVeeamBackupCmd cmd, final Object job)
            throws ResourceAllocationException {
        final Long vmId = cmd.getVmId();
        final Account caller = CallContext.current().getCallingAccount();
        final VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(caller, null, true, vm);
        final BackupOffering offering = validateVmAblestackVeeamOffering(vm);
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        final Account owner = accountManager.getAccount(vm.getAccountId());
        Long backupSize = 0L;
        for (final Volume volume : volumeDao.findByInstance(vmId)) {
            if (Volume.State.Ready.equals(volume.getState())) {
                Long volumeSize = volumeApiService.getVolumePhysicalSize(volume.getFormat(), volume.getPath(), volume.getChainInfo());
                if (volumeSize == null) {
                    volumeSize = volume.getSize();
                }
                backupSize += volumeSize;
            }
        }
        createCheckedBackupVeeam(vm, vmId, backupProvider, cmd.getQuiesceVM(), backupSize, owner, getBackupScheduleId(job),
                cmd.getName());
        return true;
    }

    private void createCheckedBackupVeeam(final VMInstanceVO vm, final Long vmId, final BackupProvider backupProvider,
            final Boolean quiesceVM, final Long backupSize, final Account owner, final Long backupScheduleId,
            final String backupName)
            throws ResourceAllocationException {
        try (CheckedReservation backupReservation = new CheckedReservation(owner, Resource.ResourceType.backup,
                1L, reservationDao, resourceLimitMgr);
             CheckedReservation backupStorageReservation = new CheckedReservation(owner,
                     Resource.ResourceType.backup_storage, backupSize, reservationDao, resourceLimitMgr)) {

            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(),
                    EventTypes.EVENT_VM_BACKUP_CREATE, "creating Ablestack Veeam backup for VM ID:" + vm.getUuid(),
                    vmId, ApiCommandResourceType.VirtualMachine.toString(), true, 0);

            final Pair<Boolean, Backup> result = backupProvider.takeBackup(vm, quiesceVM);
            if (!result.first()) {
                throw new CloudRuntimeException("Failed to create Ablestack Veeam VM backup");
            }
            final Backup backup = result.second();
            if (backup != null) {
                final BackupVO vmBackup = backupDao.findById(backup.getId());
                vmBackup.setBackupScheduleId(backupScheduleId);
                if (backupName != null) {
                    vmBackup.setName(backupName);
                }
                backupDao.update(vmBackup.getId(), vmBackup);
                resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup);
                resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup_storage, backup.getSize());
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_RESTORE, eventDescription = "restoring VM from Ablestack Veeam backup", async = true)
    public boolean restoreAblestackVeeamBackup(final Long backupId) {
        final BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }
        final BackupOffering backupOffering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (backupOffering == null || !BackupProviderNameUtils.isVeeamFamily(backupOffering.getProvider())) {
            throw new CloudRuntimeException("Backup is not from an ablestack-veeam offering");
        }
        return restoreBackup(backupId);
    }

    @Override
    public Pair<List<Backup>, Integer> listAblestackVeeamBackups(final ListAblestackVeeamBackupsCmd cmd) {
        final VMInstanceVO vm = findVmById(cmd.getVmId());
        validateBackupForZone(vm.getDataCenterId());
        final Account caller = CallContext.current().getCallingAccount();
        accountManager.checkAccess(caller, null, true, vm);
        final BackupOffering offering = validateVmAblestackVeeamOffering(vm);
        final List<Backup> backups = backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), offering.getId());
        return new Pair<>(backups, backups.size());
    }

    private void createCheckedBackup(CreateBackupCmd cmd, Account owner, boolean isScheduledBackup, Long backupSize,
                 VMInstanceVO vm, Long vmId, BackupProvider backupProvider, Long backupScheduleId)
            throws ResourceAllocationException {
        try (CheckedReservation backupReservation = new CheckedReservation(owner, Resource.ResourceType.backup,
                1L, reservationDao, resourceLimitMgr);
             CheckedReservation backupStorageReservation = new CheckedReservation(owner,
                     Resource.ResourceType.backup_storage, backupSize, reservationDao, resourceLimitMgr)) {

            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(),
                    EventTypes.EVENT_VM_BACKUP_CREATE, "creating backup for VM ID:" + vm.getUuid(),
                    vmId, ApiCommandResourceType.VirtualMachine.toString(),
                    true, 0);

            Pair<Boolean, Backup> result = backupProvider.takeBackup(vm, cmd.getQuiesceVM(), backupScheduleId);
            if (!result.first()) {
                throw new CloudRuntimeException("Failed to create VM backup");
            }
            Backup backup = result.second();
            if (backup != null) {
                BackupVO vmBackup = backupDao.findById(result.second().getId());
                vmBackup.setBackupScheduleId(backupScheduleId);
                if (cmd.getName() != null) {
                    vmBackup.setName(cmd.getName());
                }
                vmBackup.setDescription(cmd.getDescription());
                backupDao.update(vmBackup.getId(), vmBackup);
                resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup);
                resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup_storage, backup.getSize());
            }
        } catch (ResourceAllocationException e) {
            if (isScheduledBackup && (Resource.ResourceType.backup.equals(e.getResourceType()) ||
                    Resource.ResourceType.backup_storage.equals(e.getResourceType()))) {
                sendExceededBackupLimitAlert(owner.getUuid(), e.getResourceType());
            }
            throw e;
        }
    }

    protected Long calculateBackupSize(final Long vmId) {
        Long backupSize = 0L;
        for (final Volume volume: volumeDao.findByInstance(vmId)) {
            if (Volume.State.Ready.equals(volume.getState())) {
                Long volumeSize = volumeApiService.getVolumePhysicalSize(volume.getFormat(), volume.getPath(), volume.getChainInfo());
                if (volumeSize == null) {
                    volumeSize = volume.getSize();
                }
                backupSize += volumeSize;
            }
        }
        return backupSize;
    }

        @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_CREATE, eventDescription = "creating VM backup for NetBackup", async = true)
    public boolean createNetBackup(final CreateNetBackupCmd cmd) throws ResourceAllocationException {
        final Long vmId = cmd.getVmId();
        final Account caller = CallContext.current().getCallingAccount();
        final String defaultExternalId = "netbackup";

        final VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(caller, null, true, vm);

        if (vm.getBackupOfferingId() != null) {
            final BackupOffering existingOffering = backupOfferingDao.findById(vm.getBackupOfferingId());
            if (existingOffering == null) {
                throw new CloudRuntimeException("VM backup offering not found");
            }
            if (!BackupProviderNameUtils.isNetBackupFamily(existingOffering.getProvider())) {
                throw new CloudRuntimeException(String.format("VM [%s] is already assigned to backup offering [%s] using provider [%s]. NetBackup backup cannot proceed.",
                        vm.getInstanceName(), existingOffering.getName(), existingOffering.getProvider()));
            }
        }

        final BackupOffering netBackupOfferingRef = backupOfferingDao.findByExternalId(defaultExternalId, vm.getDataCenterId());
        if (netBackupOfferingRef == null) {
            throw new CloudRuntimeException(String.format("No NetBackup backup offering is configured for zone [%s]. Please import a NetBackup backup offering before running NetBackup backups.",
                    vm.getDataCenterId()));
        }
        final BackupOfferingVO netBackupOffering = backupOfferingDao.findById(netBackupOfferingRef.getId());
        if (netBackupOffering == null) {
            throw new CloudRuntimeException("NetBackup backup offering record not found");
        }
        final BackupProvider backupProvider = getBackupProvider(netBackupOffering.getProvider());
        if (backupProvider == null) {
            throw new CloudRuntimeException("Failed to get NetBackup provider for existing offering assignment");
        }

        final VMInstanceVO assignedVm = transactionAssignVMToBackupOffering(vm, netBackupOffering, backupProvider);
        if (assignedVm == null) {
            throw new CloudRuntimeException(String.format("Failed to assign existing NetBackup offering [%s] to VM [%s].",
                    netBackupOffering.getName(), vm.getInstanceName()));
        }

        final Account owner = accountManager.getAccount(vm.getAccountId());
        final Long backupSize = calculateBackupSize(vm.getId());

        createCheckedBackup(cmd, owner, false, backupSize, vm, vm.getId(), backupProvider, null);
        return true;
    }

    @Override
    public boolean updateNetBackup(final UpdateNetBackupCmd cmd) {
        final Long vmId = cmd.getVmId();
        final Account caller = CallContext.current().getCallingAccount();

        final VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(caller, null, true, vm);
        netBackupRestoreCoordinator.updateBackupMetadata(cmd, vm);
        return true;
    }

    private String resolveNetBackupCatalogBackupTime(final BackupVO backup, final String backupId) {
        if (StringUtils.isBlank(backupId)) {
            return null;
        }

        try {
            final BackupOffering offering = backupOfferingDao.findById(backup.getBackupOfferingId());
            if (offering == null || !BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) {
                return null;
            }
            final BackupProvider provider = getBackupProvider(offering.getProvider());
            final String backupTime = provider != null ? provider.getCatalogBackupTime(backup.getZoneId(), backupId) : null;
            if (StringUtils.isNotBlank(backupTime)) {
                return backupTime;
            }
            logger.warn(String.format(
                    "NetBackup catalog backupTime was not found for backup ID [%s] in zone [%s].",
                    backupId, backup.getZoneId()));
        } catch (Exception e) {
            logger.warn(String.format(
                    "Failed to resolve NetBackup catalog backupTime for backup ID [%s] in zone [%s]: %s",
                    backupId, backup.getZoneId(), e.getMessage()), e);
        }
        return null;
    }

    private void createCheckedBackup(CreateNetBackupCmd cmd, Account owner, boolean isScheduledBackup, Long backupSize,
                VMInstanceVO vm, Long vmId, BackupProvider backupProvider, Long backupScheduleId)
            throws ResourceAllocationException {
        try (CheckedReservation backupReservation = new CheckedReservation(owner, Resource.ResourceType.backup,
                1L, reservationDao, resourceLimitMgr);
            CheckedReservation backupStorageReservation = new CheckedReservation(owner,
                     Resource.ResourceType.backup_storage, backupSize, reservationDao, resourceLimitMgr)) {

            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(),
                    EventTypes.EVENT_VM_BACKUP_CREATE, "creating backup for VM ID:" + vm.getUuid(),
                    vmId, ApiCommandResourceType.VirtualMachine.toString(),
                    true, 0);

            Pair<Boolean, Backup> result = backupProvider.takeNetBackup(vm, cmd.getPolicyId());
            if (!result.first()) {
                throw new CloudRuntimeException("Failed to create VM backup for NetBackup");
            }
            Backup backup = result.second();
            if (backup != null) {
                BackupVO vmBackup = backupDao.findById(result.second().getId());
                vmBackup.setBackupScheduleId(backupScheduleId);
                if (cmd.getName() != null) {
                    vmBackup.setName(cmd.getName());
                }
                vmBackup.setDescription(cmd.getDescription());
                backupDao.update(vmBackup.getId(), vmBackup);
                resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup);
                resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup_storage, backup.getSize());
            }
        } catch (ResourceAllocationException e) {
            if (isScheduledBackup && (Resource.ResourceType.backup.equals(e.getResourceType()) ||
                    Resource.ResourceType.backup_storage.equals(e.getResourceType()))) {
                sendExceededBackupLimitAlert(owner.getUuid(), e.getResourceType());
            }
            throw e;
        }
    }

    /**
     * Sends an alert when the backup limit has been exceeded for a given account.
     *
     * @param ownerUuid The UUID of the account owner that exceeded the limit
     * @param resourceType The type of resource limit that was exceeded (either {@link Resource.ResourceType#backup} or {@link Resource.ResourceType#backup_storage})
     *
     */
    protected void sendExceededBackupLimitAlert(String ownerUuid, Resource.ResourceType resourceType) {
        String message = String.format("Failed to create backup: backup %s limit exceeded for account with ID: %s.",
                resourceType == Resource.ResourceType.backup ? "resource" : "storage space resource" , ownerUuid);
        logger.warn(message);
        alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_UPDATE_RESOURCE_COUNT, 0L, 0L,
                message, message + " Please, use the 'updateResourceLimit' API to increase the backup limit.");
    }

    /**
     * Gets the backup schedule ID from the async job's payload.
     *
     * @param job The asynchronous job associated with the creation of the backup
     * @return The backup schedule ID. Returns null if the backup has been manually created
     */
    protected Long getBackupScheduleId(Object job) {
        if (!(job instanceof AsyncJobVO)) {
            return null;
        }

        AsyncJobVO asyncJob = (AsyncJobVO) job;
        logger.debug("Trying to retrieve [{}] parameter from the job [ID: {}] parameters.", ApiConstants.SCHEDULE_ID, asyncJob.getId());
        String jobParamsRaw = asyncJob.getCmdInfo();

        if (!jobParamsRaw.contains(ApiConstants.SCHEDULE_ID)) {
            logger.info("Job [ID: {}] parameters do not include the [{}] parameter. Thus, the current backup is a manual backup.", asyncJob.getId(), ApiConstants.SCHEDULE_ID);
            return null;
        }

        TypeToken<Map<String, String>> jobParamsType = new TypeToken<>(){};
        Map<String, String> jobParams = GsonHelper.getGson().fromJson(jobParamsRaw, jobParamsType.getType());
        long backupScheduleId = NumberUtils.toLong(jobParams.get(ApiConstants.SCHEDULE_ID));
        logger.info("Job [ID: {}] parameters include the [{}] parameter, whose value is equal to [{}]. Thus, the current backup is a scheduled backup.", asyncJob.getId(), ApiConstants.SCHEDULE_ID, backupScheduleId);
        return backupScheduleId == 0L ? null : backupScheduleId;
    }

    /**
     * Deletes the oldest backups from the schedule. If the backup schedule is not active, the schedule's retention is equal to 0,
     * or the number of backups to be deleted is lower than one, then no backups are deleted.
     *
     * @param vmId The ID of the VM associated with the backups
     * @param backupScheduleId Backup schedule ID of the backups
     */
    protected void deleteOldestBackupFromScheduleIfRequired(Long vmId, long backupScheduleId) throws ResourceAllocationException {
        BackupScheduleVO backupScheduleVO = backupScheduleDao.findById(backupScheduleId);
        if (backupScheduleVO == null || backupScheduleVO.getMaxBackups() == 0) {
            logger.info("The schedule does not have a retention specified and, hence, not deleting any backups from it.", vmId);
            return;
        }

        logger.debug("Checking if it is required to delete the oldest backup chains from the schedule with ID [{}], to meet its retention requirement of [{}] chains.", backupScheduleId, backupScheduleVO.getMaxBackups());
        List<BackupVO> backups = backupDao.listBySchedule(backupScheduleId);
        List<List<BackupVO>> backupChains = getBackupChainsForSchedule(backups);
        int amountOfChainsToDelete = backupChains.size() - backupScheduleVO.getMaxBackups();
        if (amountOfChainsToDelete > 0) {
            deleteExcessBackups(backupChains, amountOfChainsToDelete, backupScheduleId);
        } else {
            logger.debug("Not required to delete any backup chains from the schedule [ID: {}]: [chain count: {}] and [retention: {}].", backupScheduleId, backupChains.size(), backupScheduleVO.getMaxBackups());
        }
    }

    /**
     * Deletes a certain number of backups associated with a schedule.
     *
     * @param backupChains List of backup chains associated with a schedule
     * @param amountOfChainsToDelete Number of backup chains to be deleted from the list of chains
     * @param backupScheduleId ID of the backup schedule associated with the backups
     */
    protected void deleteExcessBackups(List<List<BackupVO>> backupChains, int amountOfChainsToDelete, long backupScheduleId) {
        String cleanupTarget = backupScheduleId > 0 ? String.format("schedule [ID: %s]", backupScheduleId) : "VM retention policy";
        logger.debug("Deleting up to [{}] oldest backup chains from {}.", amountOfChainsToDelete, cleanupTarget);

        int deletedChains = 0;
        for (int i = 0; i < amountOfChainsToDelete && i < backupChains.size(); i++) {
            if (deleteBackupChain(backupChains.get(i), backupScheduleId)) {
                deletedChains++;
            }
        }

        if (deletedChains < amountOfChainsToDelete) {
            logger.warn("Retention cleanup for {} deleted [{}] chains out of the requested [{}]. The remaining chains could not be deleted safely.",
                    cleanupTarget, deletedChains, amountOfChainsToDelete);
        }
    }

    private boolean deleteBackupChain(List<BackupVO> chain, long backupScheduleId) {
        if (CollectionUtils.isEmpty(chain)) {
            return true;
        }

        String cleanupTarget = backupScheduleId > 0 ? String.format("schedule [ID: %s]", backupScheduleId) : "VM retention policy";

        List<BackupVO> remainingBackups = chain.stream()
                .sorted(Comparator.comparing(BackupVO::getDate))
                .collect(Collectors.toCollection(ArrayList::new));
        int deletedBackups = 0;

        while (!remainingBackups.isEmpty()) {
            List<BackupVO> leafBackups = getLeafBackups(remainingBackups);
            if (CollectionUtils.isEmpty(leafBackups)) {
                logger.warn("Could not find a deletable leaf while removing an obsolete backup chain for {}.", cleanupTarget);
                return false;
            }

            for (BackupVO backup : leafBackups) {
                try {
                    if (!deleteBackup(backup.getId(), false)) {
                        logger.warn("Failed to delete backup [ID: {}, UUID: {}] while deleting a chain for {}.", backup.getId(), backup.getUuid(), cleanupTarget);
                        return false;
                    }
                    String eventDescription = backupScheduleId > 0
                            ? String.format("Successfully deleted backup for VM [ID: %s], suiting the retention specified in the backup schedule [ID: %s]", backup.getVmId(), backupScheduleId)
                            : String.format("Successfully deleted backup for VM [ID: %s], suiting the retention specified by the VM backup schedules", backup.getVmId());
                    logger.info(eventDescription);
                    ActionEventUtils.onCompletedActionEvent(
                            User.UID_SYSTEM, backup.getAccountId(), EventVO.LEVEL_INFO,
                            EventTypes.EVENT_VM_BACKUP_DELETE, eventDescription, backup.getId(), ApiCommandResourceType.Backup.toString(), 0
                    );
                    deletedBackups++;
                    remainingBackups.remove(backup);
                } catch (Exception e) {
                    logger.warn("Skipping retention deletion for backup [ID: {}, UUID: {}] on {} because it is not currently safe to remove: {}",
                            backup.getId(), backup.getUuid(), cleanupTarget, e.getMessage());
                    return false;
                }
            }
        }

        logger.info("Deleted [{}] backups from an obsolete backup chain for {}.", deletedBackups, cleanupTarget);
        return true;
    }

    private List<List<BackupVO>> getBackupChainsForSchedule(List<BackupVO> backups) {
        if (CollectionUtils.isEmpty(backups)) {
            return new ArrayList<>();
        }

        Map<String, BackupVO> backupsByUuid = backups.stream()
                .collect(Collectors.toMap(BackupVO::getUuid, backup -> backup, (left, right) -> left, LinkedHashMap::new));
        Map<String, List<BackupVO>> chainsByRootUuid = new LinkedHashMap<>();

        for (BackupVO backup : backups) {
            String rootUuid = getRootBackupUuid(backup, backupsByUuid);
            chainsByRootUuid.computeIfAbsent(rootUuid, ignored -> new ArrayList<>()).add(backup);
        }

        return chainsByRootUuid.values().stream()
                .map(chain -> chain.stream()
                        .sorted(Comparator.comparing(BackupVO::getDate))
                        .collect(Collectors.toCollection(ArrayList::new)))
                .sorted(Comparator.comparing(chain -> chain.get(0).getDate()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String getRootBackupUuid(BackupVO backup, Map<String, BackupVO> backupsByUuid) {
        BackupVO current = backup;
        Set<String> visitedBackups = new HashSet<>();

        while (current != null && visitedBackups.add(current.getUuid())) {
            String parentBackupUuid = getParentBackupUuid(current);
            if (StringUtils.isBlank(parentBackupUuid) || !backupsByUuid.containsKey(parentBackupUuid)) {
                return current.getUuid();
            }
            current = backupsByUuid.get(parentBackupUuid);
        }

        return backup.getUuid();
    }

    private List<BackupVO> getLeafBackups(List<BackupVO> backups) {
        Set<String> parentBackupUuids = backups.stream()
                .map(this::getParentBackupUuid)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());

        return backups.stream()
                .filter(backup -> !parentBackupUuids.contains(backup.getUuid()))
                .sorted(Comparator.comparing(BackupVO::getDate).reversed())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String getParentBackupUuid(BackupVO backup) {
        backupDao.loadDetails(backup);
        Map<String, String> details = backup.getDetails();
        if (details == null || details.isEmpty()) {
            return null;
        }

        return details.entrySet().stream()
                .filter(entry -> StringUtils.endsWith(entry.getKey(), ".parent.backup.uuid"))
                .map(Map.Entry::getValue)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    @Override
    public Pair<List<Backup>, Integer> listBackups(final ListBackupsCmd cmd) {
        final Long id = cmd.getId();
        final Long vmId = cmd.getVmId();
        final String name = cmd.getName();
        final Long zoneId = cmd.getZoneId();
        final Long backupOfferingId = cmd.getBackupOfferingId();
        final Account caller = CallContext.current().getCallingAccount();
        final String keyword = cmd.getKeyword();
        List<Long> permittedAccounts = new ArrayList<Long>();

        if (vmId != null) {
            VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
            if (vm != null) {
                accountManager.checkAccess(caller, null, true, vm);
            }
        }

        final Ternary<Long, Boolean, Project.ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean, Project.ListProjectResourcesCriteria>(cmd.getDomainId(),
                cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        final Long domainId = domainIdRecursiveListProject.first();
        final Boolean isRecursive = domainIdRecursiveListProject.second();
        final Project.ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        final Filter searchFilter = new Filter(BackupVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        SearchBuilder<BackupVO> sb = backupDao.createSearchBuilder();
        accountManager.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("idIN", sb.entity().getId(), SearchCriteria.Op.IN);
        sb.and("vmId", sb.entity().getVmId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("zoneId", sb.entity().getZoneId(), SearchCriteria.Op.EQ);
        sb.and("backupOfferingId", sb.entity().getBackupOfferingId(), SearchCriteria.Op.EQ);

        if (keyword != null) {
            sb.and().op("keywordName", sb.entity().getName(), SearchCriteria.Op.LIKE);
            SearchBuilder<VMInstanceVO> vmSearch = vmInstanceDao.createSearchBuilder();
            sb.join("vmSearch", vmSearch, sb.entity().getVmId(), vmSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            sb.or("vmSearch", "keywordVmName", vmSearch.entity().getHostName(), SearchCriteria.Op.LIKE);
            sb.cp();
        }

        SearchCriteria<BackupVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (vmId != null) {
            sc.setParameters("vmId", vmId);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (zoneId != null) {
            sc.setParameters("zoneId", zoneId);
        }

        if (backupOfferingId != null) {
            sc.setParameters("backupOfferingId", backupOfferingId);
        }

        if (keyword != null) {
            String keywordMatch = "%" + keyword + "%";
            sc.setParameters("keywordName", keywordMatch);
            sc.setParameters("keywordVmName", keywordMatch);
        }

        Pair<List<BackupVO>, Integer> result = backupDao.searchAndCount(sc, searchFilter);
        return new Pair<>(new ArrayList<>(result.first()), result.second());
    }

    public boolean importRestoredVM(long zoneId, long domainId, long accountId, long userId,
                                    String vmInternalName, Hypervisor.HypervisorType hypervisorType, Backup backup) {
        VirtualMachine vm = null;
        HypervisorGuru guru = hypervisorGuruManager.getGuru(hypervisorType);
        try {
            vm = guru.importVirtualMachineFromBackup(zoneId, domainId, accountId, userId, vmInternalName, backup);
        } catch (final Exception e) {
            logger.error(String.format("Failed to import VM [vmInternalName: %s] from backup restoration [%s] with hypervisor [type: %s] due to: [%s].", vmInternalName,
                    ReflectionToStringBuilderUtils.reflectOnlySelectedFields(backup, "id", "uuid", "vmId", "externalId", "backupType"), hypervisorType, e.getMessage()), e);
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_BACKUP_RESTORE,
                    String.format("Failed to import VM %s from backup %s with hypervisor [type: %s]", vmInternalName, backup.getUuid(), hypervisorType),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
            throw new CloudRuntimeException("Error during vm backup restoration and import: " + e.getMessage());
        }
        if (vm == null) {
            String message = String.format("Failed to import restored VM %s  with hypervisor type %s using backup of VM ID %s",
                    vmInternalName, hypervisorType, backup.getVmId());
            logger.error(message);
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_BACKUP_RESTORE,
                    message, vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
        } else {
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_VM_BACKUP_RESTORE,
                    String.format("Restored VM %s from backup %s", vm.getUuid(), backup.getUuid()),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
        }
        return vm != null;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_RESTORE, eventDescription = "restoring VM from backup", async = true)
    public boolean restoreBackup(final Long backupId) {
        final BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }
        if (backup.getStatus() != Backup.Status.BackedUp) {
            throw new CloudRuntimeException("Backup should be in BackedUp state");
        }
        validateBackupForZone(backup.getZoneId());

        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm == null || VirtualMachine.State.Expunging.equals(vm.getState())) {
            throw new CloudRuntimeException("The Instance from which the backup was taken could not be found.");
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        if (vm.getRemoved() == null && !vm.getState().equals(VirtualMachine.State.Stopped) &&
                !vm.getState().equals(VirtualMachine.State.Destroyed)) {
            throw new CloudRuntimeException("Existing VM should be stopped before being restored from backup");
        }

        // This is done to handle historic backups if any with Veeam / Networker plugins
        List<Backup.VolumeInfo> backupVolumes = CollectionUtils.isEmpty(backup.getBackedUpVolumes()) ?
                vm.getBackupVolumeList() : backup.getBackedUpVolumes();
        List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        if (vmVolumes.size() != backupVolumes.size()) {
            throw new CloudRuntimeException("Unable to restore VM with the current backup as the backup has different number of disks as the VM");
        }

        BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(vm.getBackupOfferingId());
        String errorMessage = "Failed to find backup offering of the VM backup.";
        if (offering == null) {
            logger.warn(errorMessage);
        }
        logger.debug("Attempting to get backup offering from VM backup");
        offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException(errorMessage);
        }
        String backupDetailsInMessage = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(backup, "uuid", "externalId", "vmId", "name");
        final boolean netBackupRestore = isAblestackNetBackupOffering(offering);
        final String netBackupRestoreRequestIdentifier = netBackupRestore ? netBackupRestoreCoordinator.getMoldRestoreRequestIdentifier(backup) : null;
        final VMInstanceVO netBackupRestoreMarkerVm = netBackupRestore ? netBackupRestoreCoordinator.getRestoreMarkerVm(backup, vm) : null;
        try {
            if (netBackupRestore) {
                netBackupRestoreCoordinator.blockIfRestoreAlreadyActive(netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier);
                final RestorePhase phase = isNetBackupIncrementalBackup(backup)
                        ? RestorePhase.CHAIN_RESTORE_IN_PROGRESS
                        : RestorePhase.ROOT_RESTORE_IN_PROGRESS;
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, phase);
            }

            tryRestoreVM(backup, vm, offering, backupDetailsInMessage);
            updateVolumeState(vm, Volume.Event.RestoreSucceeded, Volume.State.Ready);
            updateVmState(vm, VirtualMachine.Event.RestoringSuccess, VirtualMachine.State.Stopped);

            final boolean imported = importRestoredVM(vm.getDataCenterId(), vm.getDomainId(), vm.getAccountId(), vm.getUserId(),
                    vm.getInstanceName(), vm.getHypervisorType(), backup);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier,
                        imported ? RestorePhase.COMPLETED : RestorePhase.FAILED);
            }
            return imported;
        } catch (RuntimeException e) {
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, RestorePhase.FAILED);
            }
            throw e;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_RESTORE, eventDescription = "restoring VM from NetBackup external ID", async = true)
    public boolean restoreNetBackup(final RestoreNetBackupCmd cmd) {
        logger.info("NetBackup restore requested. externalId=[{}], backupId=[{}]",
                cmd.getExternalId(), cmd.getBackupId());
        if (StringUtils.isBlank(cmd.getExternalId()) && StringUtils.isBlank(cmd.getBackupId())) {
            throw new CloudRuntimeException("NetBackup backup external ID or backup ID is required");
        }
        final RestoreResolution resolution = netBackupRestoreCoordinator.resolveRestoreRequest(
                cmd.getExternalId(), cmd.getBackupId(), NETBACKUP_RESTORE_PATH_DISCOVERY_WINDOW_SECONDS, true);
        final BackupVO backup = resolution.getBackup();
        logger.info("NetBackup restore resolved. requestIdentifier=[{}], resolvedBackupUuid=[{}], resolvedExternalId=[{}], restoreHostName=[{}]",
                resolution.getRequestIdentifier(), backup.getUuid(), backup.getExternalId(), resolution.getPreparedRestoreHostName());

        VMInstanceVO vm = null;
        String restoreGuardToken = null;
        try {
            if (!Backup.Status.BackedUp.equals(backup.getStatus())) {
                throw new CloudRuntimeException(String.format(
                        "NetBackup external ID [%s] is mapped to backup [%s] in state [%s]. Only BackedUp backups can be restored.",
                        resolution.getRequestIdentifier(), backup.getUuid(), backup.getStatus()));
            }
            validateBackupForZone(backup.getZoneId());

            vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
            if (vm == null || VirtualMachine.State.Expunging.equals(vm.getState())) {
                throw new CloudRuntimeException("The Instance from which the NetBackup was taken could not be found.");
            }
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
            validateVmStoppedForNetBackupRestore(vm);

            final BackupVO restoreJobBackup = netBackupRestoreCoordinator.findRestoreBackupByJobId(cmd.getJobId());
            if (restoreJobBackup != null) {
                final String skipMessage = String.format(
                        "Skipping NetBackup restore request for VM [%s] using NetBackup job ID [%s] because it was created by an existing Mold restore flow on backup [%s].",
                        vm.getInstanceName(), cmd.getJobId(), restoreJobBackup.getUuid());
                logger.info(skipMessage);
                CallContext.current().setEventDetails(skipMessage);
                return true;
            }

            restoreGuardToken = netBackupRestoreCoordinator.acquireRestoreGuard(vm, resolution.getRequestIdentifier());
            if (restoreGuardToken == null) {
                final String skipMessage = String.format(
                        "Skipping duplicate NetBackup restore request for VM [%s] (vmId=[%s]) using request identifier [%s] because a restore is already in progress.",
                        vm.getInstanceName(), vm.getId(), resolution.getRequestIdentifier());
                logger.info(skipMessage);
                CallContext.current().setEventDetails(skipMessage);
                return true;
            }

            final RestoreSession activeSession = netBackupRestoreCoordinator.findSession(vm.getId());
            if (activeSession != null) {
                netBackupRestoreCoordinator.updateSessionPhase(vm.getId(), resolution.getRequestIdentifier(), RestorePhase.ROOT_RESTORE_IN_PROGRESS);
            }

            netBackupRestoreCoordinator.persistRestoreState(backup, vm, resolution.getRequestIdentifier(), RestorePhase.ROOT_RESTORE_IN_PROGRESS);

            if (isNetBackupFullBackup(backup)) {
                logger.info("Resolved NetBackup FULL restore request for VM [{}] using external ID [{}] and backup [{}].",
                        vm.getInstanceName(), resolution.getRequestIdentifier(), backup.getUuid());
                final BackupOffering offering = getBackupOfferingForRestore(vm, backup);
                final String backupDetailsInMessage = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(
                        backup, "uuid", "externalId", "vmId", "name");
                if (resolution.getPreparedRestoreHostName() != null) {
                    tryRestorePreparedNetBackupVM(
                            backup, vm, offering, backupDetailsInMessage, resolution.getPreparedRestoreHostName());
                } else {
                    tryRestoreVM(backup, vm, offering, backupDetailsInMessage);
                }
                updateVolumeState(vm, Volume.Event.RestoreSucceeded, Volume.State.Ready);
                updateVmState(vm, VirtualMachine.Event.RestoringSuccess, VirtualMachine.State.Stopped);
                final boolean imported = importRestoredVM(vm.getDataCenterId(), vm.getDomainId(), vm.getAccountId(), vm.getUserId(),
                        vm.getInstanceName(), vm.getHypervisorType(), backup);
                if (imported) {
                    netBackupRestoreCoordinator.persistRestoreState(backup, vm, resolution.getRequestIdentifier(), RestorePhase.COMPLETED);
                    netBackupRestoreCoordinator.completeSession(vm.getId(), resolution.getRequestIdentifier());
                } else {
                    netBackupRestoreCoordinator.persistRestoreState(backup, vm, resolution.getRequestIdentifier(), RestorePhase.FAILED);
                    netBackupRestoreCoordinator.failSession(vm.getId(), resolution.getRequestIdentifier(), "Imported VM result was false");
                }
                return imported;
            }

            if (isNetBackupIncrementalBackup(backup)) {
                netBackupRestoreCoordinator.updateSessionPhase(vm.getId(), resolution.getRequestIdentifier(),
                        RestorePhase.CHAIN_RESTORE_IN_PROGRESS);
                netBackupRestoreCoordinator.persistRestoreState(backup, vm, resolution.getRequestIdentifier(), RestorePhase.CHAIN_RESTORE_IN_PROGRESS);
                final List<BackupVO> restoreChain = netBackupRestoreCoordinator.getRestoreChain(backup);
                logger.info("Resolved NetBackup INCREMENTAL restore chain for VM [{}] using external ID [{}]: {}",
                        vm.getInstanceName(), resolution.getRequestIdentifier(),
                        restoreChain.stream().map(BackupVO::getUuid).collect(Collectors.joining(" -> ")));
                final BackupOffering offering = getBackupOfferingForRestore(vm, backup);
                final String backupDetailsInMessage = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(
                        backup, "uuid", "externalId", "vmId", "name");
                if (resolution.getPreparedRestoreHostName() != null) {
                    tryRestorePreparedNetBackupVM(
                            backup, vm, offering, backupDetailsInMessage, resolution.getPreparedRestoreHostName());
                } else {
                    tryRestoreVM(backup, vm, offering, backupDetailsInMessage);
                }
                updateVolumeState(vm, Volume.Event.RestoreSucceeded, Volume.State.Ready);
                updateVmState(vm, VirtualMachine.Event.RestoringSuccess, VirtualMachine.State.Stopped);
                final boolean imported = importRestoredVM(vm.getDataCenterId(), vm.getDomainId(), vm.getAccountId(), vm.getUserId(),
                        vm.getInstanceName(), vm.getHypervisorType(), backup);
                if (imported) {
                    netBackupRestoreCoordinator.persistRestoreState(backup, vm, resolution.getRequestIdentifier(), RestorePhase.COMPLETED);
                    netBackupRestoreCoordinator.completeSession(vm.getId(), resolution.getRequestIdentifier());
                } else {
                    netBackupRestoreCoordinator.persistRestoreState(backup, vm, resolution.getRequestIdentifier(), RestorePhase.FAILED);
                    netBackupRestoreCoordinator.failSession(vm.getId(), resolution.getRequestIdentifier(), "Imported VM result was false");
                }
                return imported;
            }

            throw new CloudRuntimeException(String.format(
                    "NetBackup external ID [%s] is mapped to backup [%s] with unsupported type [%s].",
                    resolution.getRequestIdentifier(), backup.getUuid(), backup.getType()));
        } catch (RuntimeException e) {
            netBackupRestoreCoordinator.persistRestoreState(backup, vm, resolution.getRequestIdentifier(), RestorePhase.FAILED);
            netBackupRestoreCoordinator.failSession(vm != null ? vm.getId() : null, resolution.getRequestIdentifier(), e.getMessage());
            cleanupPreparedNetBackupRestoreOnFailure(backup, vm, resolution, e);
            throw e;
        } finally {
            netBackupRestoreCoordinator.releaseRestoreGuard(vm != null ? vm.getId() : null, restoreGuardToken, resolution.getRequestIdentifier());
        }
    }

    @Override
    public NetBackupRestorePrecheckResult prepareNetBackupRestore(final PrepareNetBackupRestoreCmd cmd) {
        logger.info("NetBackup restore precheck requested. externalId=[{}], backupId=[{}]",
                cmd.getExternalId(), cmd.getBackupId());
        if (StringUtils.isBlank(cmd.getExternalId()) && StringUtils.isBlank(cmd.getBackupId())) {
            throw new CloudRuntimeException("NetBackup backup external ID or backup ID is required");
        }

        final BackupVO trackedRestoreJobBackup = netBackupRestoreCoordinator.findRestoreBackupByJobId(cmd.getJobId());
        if (trackedRestoreJobBackup != null) {
            final VMInstanceVO trackedVm = vmInstanceDao.findByIdIncludingRemoved(trackedRestoreJobBackup.getVmId());
            if (trackedVm == null || VirtualMachine.State.Expunging.equals(trackedVm.getState())) {
                throw new CloudRuntimeException("The Instance from which the NetBackup was taken could not be found.");
            }
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, trackedVm);
            final String skipReason = String.format(
                    "NetBackup restore job [%s] is already tracked by Mold restore flow on backup [%s]",
                    cmd.getJobId(), trackedRestoreJobBackup.getUuid());
            logger.info("NetBackup restore precheck skip by tracked restore job before path resolution. vm=[{}], vmId=[{}], jobId=[{}], trackedBackup=[{}]",
                    trackedVm.getInstanceName(), trackedVm.getId(), cmd.getJobId(), trackedRestoreJobBackup.getUuid());
            return new NetBackupRestorePrecheckResult(false, skipReason, trackedVm.getId(), trackedVm.getInstanceName(),
                    trackedRestoreJobBackup.getId(), trackedRestoreJobBackup.getUuid(),
                    StringUtils.defaultIfBlank(cmd.getExternalId(), cmd.getBackupId()), trackedRestoreJobBackup.getExternalId());
        }

        final RestoreResolution resolution = netBackupRestoreCoordinator.resolveRestoreRequest(
                cmd.getExternalId(), cmd.getBackupId(), NETBACKUP_PREPARE_RESTORE_PATH_DISCOVERY_WINDOW_SECONDS, true, true);
        final BackupVO backup = resolution.getBackup();
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm == null || VirtualMachine.State.Expunging.equals(vm.getState())) {
            throw new CloudRuntimeException("The Instance from which the NetBackup was taken could not be found.");
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        final BackupVO restoreJobBackup = netBackupRestoreCoordinator.findRestoreBackupByJobId(cmd.getJobId());
        if (restoreJobBackup != null) {
            final String skipReason = String.format(
                    "NetBackup restore job [%s] is already tracked by Mold restore flow on backup [%s]",
                    cmd.getJobId(), restoreJobBackup.getUuid());
            logger.info("NetBackup restore precheck skip by tracked restore job. vm=[{}], vmId=[{}], requestIdentifier=[{}], jobId=[{}], trackedBackup=[{}]",
                    vm.getInstanceName(), vm.getId(), resolution.getRequestIdentifier(), cmd.getJobId(), restoreJobBackup.getUuid());
            return new NetBackupRestorePrecheckResult(false, skipReason, vm.getId(), vm.getInstanceName(),
                    backup.getId(), backup.getUuid(), resolution.getRequestIdentifier(), backup.getExternalId());
        }

        final BackupVO activeRestoreBackup = netBackupRestoreCoordinator.findActiveRestoreBackupForVm(vm);
        if (activeRestoreBackup != null) {
            final String activePhase = netBackupRestoreCoordinator.getRestorePhase(activeRestoreBackup);
            final String activeRequestId = netBackupRestoreCoordinator.getRestoreRequestId(activeRestoreBackup);
            final String skipReason = String.format(
                    "NetBackup restore session for VM [%s] is already active on backup [%s] in phase [%s] for request [%s]",
                    vm.getInstanceName(), activeRestoreBackup.getUuid(), activePhase, activeRequestId);
            logger.info("NetBackup restore precheck skip by detail state. vm=[{}], vmId=[{}], requestIdentifier=[{}], activeBackup=[{}], phase=[{}], activeRequestIdentifier=[{}]",
                    vm.getInstanceName(), vm.getId(), resolution.getRequestIdentifier(),
                    activeRestoreBackup.getUuid(), activePhase, activeRequestId);
            return new NetBackupRestorePrecheckResult(false, skipReason, vm.getId(), vm.getInstanceName(),
                    backup.getId(), backup.getUuid(), resolution.getRequestIdentifier(), backup.getExternalId());
        }

        final RestoreSession existingSession = netBackupRestoreCoordinator.findSession(vm.getId());
        if (existingSession != null) {
            final String skipReason = String.format("NetBackup restore session for VM [%s] is already active for request [%s] in phase [%s]",
                    vm.getInstanceName(), existingSession.getRequestIdentifier(), existingSession.getPhase());
            logger.info("NetBackup restore precheck skip by active session. vm=[{}], vmId=[{}], requestIdentifier=[{}], activeRequestIdentifier=[{}], phase=[{}]",
                    vm.getInstanceName(), vm.getId(), resolution.getRequestIdentifier(), existingSession.getRequestIdentifier(), existingSession.getPhase());
            return new NetBackupRestorePrecheckResult(false, skipReason, vm.getId(), vm.getInstanceName(),
                    backup.getId(), backup.getUuid(), resolution.getRequestIdentifier(), backup.getExternalId());
        }

        if (!VirtualMachine.State.Stopped.equals(vm.getState())) {
            final String skipReason = String.format(
                    "VM [%s] must be in Stopped state before NetBackup restore. Current state: [%s].",
                    vm.getInstanceName(), vm.getState());
            logger.info("NetBackup restore precheck skip by VM state. vm=[{}], vmId=[{}], requestIdentifier=[{}], state=[{}]",
                    vm.getInstanceName(), vm.getId(), resolution.getRequestIdentifier(), vm.getState());
            cleanupPreparedNetBackupRestoreOnFailure(backup, vm, resolution, new CloudRuntimeException(skipReason));
            return new NetBackupRestorePrecheckResult(false, skipReason, vm.getId(), vm.getInstanceName(),
                    backup.getId(), backup.getUuid(), resolution.getRequestIdentifier(), backup.getExternalId());
        }

        netBackupRestoreCoordinator.validatePreparedRestorePath(backup, resolution, NETBACKUP_PREPARE_RESTORE_PATH_DISCOVERY_WINDOW_SECONDS);

        final RestoreSession session = netBackupRestoreCoordinator.claimSession(vm, resolution.getRequestIdentifier(), backup);
        if (session == null) {
            final RestoreSession existing = netBackupRestoreCoordinator.findSession(vm.getId());
            final String skipReason = existing == null
                    ? String.format("NetBackup restore session for VM [%s] is already active", vm.getInstanceName())
                    : String.format("NetBackup restore session for VM [%s] is already active for request [%s] in phase [%s]",
                    vm.getInstanceName(), existing.getRequestIdentifier(), existing.getPhase());
            logger.info("NetBackup restore precheck skip. vm=[{}], vmId=[{}], requestIdentifier=[{}], reason=[{}]",
                    vm.getInstanceName(), vm.getId(), resolution.getRequestIdentifier(), skipReason);
            return new NetBackupRestorePrecheckResult(false, skipReason, vm.getId(), vm.getInstanceName(),
                    backup.getId(), backup.getUuid(), resolution.getRequestIdentifier(), backup.getExternalId());
        }

        netBackupRestoreCoordinator.persistRestoreState(backup, vm, resolution.getRequestIdentifier(), RestorePhase.CLAIMED);

        logger.info("NetBackup restore precheck approved. vm=[{}], vmId=[{}], requestIdentifier=[{}], backupUuid=[{}], externalId=[{}]",
                vm.getInstanceName(), vm.getId(), resolution.getRequestIdentifier(), backup.getUuid(), backup.getExternalId());
        return new NetBackupRestorePrecheckResult(true, null, vm.getId(), vm.getInstanceName(),
                backup.getId(), backup.getUuid(), resolution.getRequestIdentifier(), backup.getExternalId());
    }

    private BackupOffering getBackupOfferingForRestore(final VMInstanceVO vm, final BackupVO backup) {
        BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(vm.getBackupOfferingId());
        if (offering != null) {
            return offering;
        }

        offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering != null) {
            return offering;
        }
        throw new CloudRuntimeException("Failed to find backup offering of the VM backup.");
    }

    private void validateVmStoppedForNetBackupRestore(final VMInstanceVO vm) {
        if (!VirtualMachine.State.Stopped.equals(vm.getState())) {
            throw new CloudRuntimeException(String.format(
                    "VM [%s] must be in Stopped state before NetBackup restore. Current state: [%s].",
                    vm.getInstanceName(), vm.getState()));
        }
    }

    private void cleanupPreparedNetBackupRestoreOnFailure(final BackupVO backup, final VMInstanceVO vm,
            final RestoreResolution resolution, final RuntimeException restoreFailure) {
        if (backup == null || resolution == null || StringUtils.isBlank(resolution.getPreparedRestoreHostName())) {
            return;
        }
        try {
            final BackupProvider provider = getBackupProviderForOffering(backup.getBackupOfferingId());
            provider.cleanupPreparedRestore(vm, backup, resolution.getPreparedRestoreHostName());
        } catch (Exception cleanupException) {
            logger.warn("Failed to cleanup prepared NetBackup restore for vm=[{}], backup=[{}], restoreHost=[{}] after restore failure: {}",
                    vm != null ? vm.getInstanceName() : null, backup.getUuid(), resolution.getPreparedRestoreHostName(), restoreFailure.getMessage(), cleanupException);
        }
    }

    private boolean isNetBackupFullBackup(final Backup backup) {
        return StringUtils.equalsIgnoreCase(backup.getType(), "FULL");
    }

    private boolean isNetBackupIncrementalBackup(final Backup backup) {
        return StringUtils.equalsIgnoreCase(backup.getType(), "INCREMENTAL");
    }

    private boolean isAblestackNetBackupOffering(final BackupOffering offering) {
        return offering != null && StringUtils.equalsIgnoreCase(ABLESTACK_NETBACKUP_PROVIDER_NAME, offering.getProvider());
    }


    /**
     * Tries to restore a VM from a backup. <br/>
     * First update the VM state to {@link VirtualMachine.Event#RestoringRequested} and its volume states to {@link Volume.Event#RestoreRequested}, <br/>
     * and then try to restore the backup. <br/>
     *
     * If restore fails, then update the VM state to {@link VirtualMachine.Event#RestoringFailed}, and its volumes to {@link Volume.Event#RestoreFailed} and throw an {@link CloudRuntimeException}.
     */
    protected void tryRestoreVM(BackupVO backup, VMInstanceVO vm, BackupOffering offering, String backupDetailsInMessage) {
        try {
            updateVmState(vm, VirtualMachine.Event.RestoringRequested, VirtualMachine.State.Restoring);
            updateVolumeState(vm, Volume.Event.RestoreRequested, Volume.State.Restoring);
            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventTypes.EVENT_VM_BACKUP_RESTORE,
                    String.format("Restoring VM %s from backup %s", vm.getUuid(), backup.getUuid()),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),
                    true, 0);

            final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
            if (!backupProvider.restoreVMFromBackup(vm, backup)) {
                ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_BACKUP_RESTORE,
                        String.format("Failed to restore VM %s from backup %s", vm.getInstanceName(), backup.getUuid()),
                        vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
                throw new CloudRuntimeException("Error restoring VM from backup with uuid " + backup.getUuid());
            }
            runPostRestoreMaintenance(backupProvider, vm, backup, false);
        // The restore process is executed by a backup provider outside of ACS, I am using the catch-all (Exception) to
        // ensure that no provider-side exception is missed. Therefore, we have a proper handling of exceptions, and rollbacks if needed.
        } catch (Exception e) {
            logger.error(String.format("Failed to restore backup [%s] due to: [%s].", backupDetailsInMessage, e.getMessage()), e);
            updateVolumeState(vm, Volume.Event.RestoreFailed, Volume.State.Ready);
            updateVmState(vm, VirtualMachine.Event.RestoringFailed, VirtualMachine.State.Stopped);
            throw new CloudRuntimeException(String.format("Error restoring VM from backup [%s].", backupDetailsInMessage));
        }
    }

    protected void tryRestorePreparedNetBackupVM(final BackupVO backup, final VMInstanceVO vm,
            final BackupOffering offering,
            final String backupDetailsInMessage, final String restoreHostName) {
        try {
            updateVmState(vm, VirtualMachine.Event.RestoringRequested, VirtualMachine.State.Restoring);
            updateVolumeState(vm, Volume.Event.RestoreRequested, Volume.State.Restoring);
            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventTypes.EVENT_VM_BACKUP_RESTORE,
                    String.format("Restoring VM %s from prepared NetBackup backup %s", vm.getUuid(), backup.getUuid()),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),
                    true, 0);

            final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
            if (!invokePreparedNetBackupRestore(backupProvider, vm, backup, restoreHostName)) {
                ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR,
                        EventTypes.EVENT_VM_BACKUP_RESTORE,
                        String.format("Failed to restore VM %s from prepared NetBackup backup %s",
                                vm.getInstanceName(), backup.getUuid()),
                        vm.getId(), ApiCommandResourceType.VirtualMachine.toString(), 0);
                throw new CloudRuntimeException(
                        "Error restoring VM from prepared NetBackup backup with uuid " + backup.getUuid());
            }
            runPostRestoreMaintenance(backupProvider, vm, backup, false);
        } catch (Exception e) {
            logger.error(String.format(
                    "Failed to restore prepared NetBackup backup [%s] due to: [%s].",
                    backupDetailsInMessage, e.getMessage()), e);
            updateVolumeState(vm, Volume.Event.RestoreFailed, Volume.State.Ready);
            updateVmState(vm, VirtualMachine.Event.RestoringFailed, VirtualMachine.State.Stopped);
            throw new CloudRuntimeException(String.format(
                    "Error restoring prepared NetBackup backup [%s].", backupDetailsInMessage));
        }
    }

    private boolean invokePreparedNetBackupRestore(final BackupProvider backupProvider, final VMInstanceVO vm,
            final BackupVO backup, final String restoreHostName) {
        if (backupProvider == null) {
            return false;
        }
        try {
            return (Boolean) backupProvider.getClass()
                    .getMethod("restoreVMFromPreparedBackup", VirtualMachine.class, Backup.class, String.class)
                    .invoke(backupProvider, vm, backup, restoreHostName);
        } catch (NoSuchMethodException e) {
            logger.warn("Backup provider [{}] does not support prepared NetBackup restore flow.",
                    backupProvider.getName());
            return false;
        } catch (Exception e) {
            final Throwable cause = e instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException)e).getTargetException()
                    : e;
            final String details = cause != null && StringUtils.isNotBlank(cause.getMessage()) ? cause.getMessage() : e.getMessage();
            throw new CloudRuntimeException(String.format(
                    "Failed to invoke prepared NetBackup restore on provider [%s]: %s",
                    backupProvider.getName(), details), e);
        }
    }


    /**
     * Tries to update the state of given VM, given specified event
     * @param vm The VM to update its state
     * @param event The event to update the VM state
     * @param next The desired state, just needed to add more context to the logs
     */
    private void updateVmState(VMInstanceVO vm, VirtualMachine.Event event, VirtualMachine.State next) {
        logger.debug(String.format("Trying to update state of VM [%s] with event [%s].", vm, event));
        Transaction.execute(TransactionLegacy.CLOUD_DB, (TransactionCallback<VMInstanceVO>) status -> {
            try {
                if (!virtualMachineManager.stateTransitTo(vm, event, vm.getHostId())) {
                    throw new CloudRuntimeException(String.format("Unable to change state of VM [%s] to [%s].", vm, next));
                }
            } catch (NoTransitionException e) {
                String errMsg = String.format("Failed to update state of VM [%s] with event [%s] due to [%s].", vm, event, e.getMessage());
                logger.error(errMsg, e);
                throw new RuntimeException(errMsg);
            }
            return null;
        });
    }

    /**
     * Tries to update all volume states of given VM, given specified event
     * @param vm The VM to which the volumes belong
     * @param event The event to update the volume states
     * @param next The desired state, just needed to add more context to the logs
     */
    private void updateVolumeState(VMInstanceVO vm, Volume.Event event, Volume.State next) {
        Transaction.execute(TransactionLegacy.CLOUD_DB, (TransactionCallback<VolumeVO>) status -> {
            for (VolumeVO volume : volumeDao.findIncludingRemovedByInstanceAndType(vm.getId(), null)) {
                tryToUpdateStateOfSpecifiedVolume(volume, event, next);
            }
            return null;
        });
    }

    /**
     * Tries to update the state of just one volume using any passed {@link Volume.Event}. Throws an {@link RuntimeException} when fails.
     * @param volume The volume to update it state
     * @param event The event to update the volume state
     * @param next The desired state, just needed to add more context to the logs
     *
     */
    private void tryToUpdateStateOfSpecifiedVolume(VolumeVO volume, Volume.Event event, Volume.State next) {
        logger.debug(String.format("Trying to update state of volume [%s] with event [%s].", volume, event));
        try {
            if (!volumeApiService.stateTransitTo(volume, event)) {
                throw new CloudRuntimeException(String.format("Unable to change state of volume [%s] to [%s].", volume, next));
            }
        } catch (NoTransitionException e) {
            String errMsg = String.format("Failed to update state of volume [%s] with event [%s] due to [%s].", volume, event, e.getMessage());
            logger.error(errMsg, e);
            throw new RuntimeException(errMsg);
        }
    }

    private Backup.VolumeInfo getVolumeInfo(List<Backup.VolumeInfo> backedUpVolumes, String volumeUuid) {
        for (Backup.VolumeInfo volInfo : backedUpVolumes) {
            if (volInfo.getUuid().equals(volumeUuid)) {
                return volInfo;
            }
        }
        return null;
    }

    @Override
    public void checkVmDisksSizeAgainstBackup(List<VmDiskInfo> vmDiskInfoList, Backup backup) {
        List<VmDiskInfo> vmDiskInfoListFromBackup = getDataDiskInfoListFromBackup(backup);
        int index = 0;
        if (vmDiskInfoList.size() != vmDiskInfoListFromBackup.size()) {
            throw new InvalidParameterValueException("Unable to create Instance from Backup " +
                    "as the backup has a different number of disks than the Instance.");
        }
        for (VmDiskInfo vmDiskInfo : vmDiskInfoList) {
            if (index < vmDiskInfoListFromBackup.size()) {
                if (vmDiskInfo.getSize() < vmDiskInfoListFromBackup.get(index).getSize()) {
                    throw new InvalidParameterValueException(
                            String.format("Instance volume size %d[GiB] cannot be less than the backed-up volume size %d[GiB].",
                            vmDiskInfo.getSize(), vmDiskInfoListFromBackup.get(index).getSize()));
                }
            }
            index++;
        }
    }

    @Override
    public VmDiskInfo getRootDiskInfoFromBackup(Backup backup) {
        List<Backup.VolumeInfo> volumes = backup.getBackedUpVolumes();
        VmDiskInfo rootDiskOffering = null;
        if (volumes == null || volumes.isEmpty()) {
            throw new CloudRuntimeException("Failed to get backed-up volumes info from backup");
        }
        for (Backup.VolumeInfo volume : volumes) {
            if (volume.getType() == Volume.Type.ROOT) {
                DiskOfferingVO diskOffering = diskOfferingDao.findByUuid(volume.getDiskOfferingId());
                if (diskOffering == null) {
                    throw new CloudRuntimeException(String.format("Unable to find the root disk offering with uuid (%s) " +
                            "stored in backup. Please specify a valid root disk offering id while creating the instance",
                            volume.getDiskOfferingId()));
                }
                Long size = volume.getSize() / (1024 * 1024 * 1024);
                rootDiskOffering = new VmDiskInfo(diskOffering, size, volume.getMinIops(), volume.getMaxIops());
            }
        }
        if (rootDiskOffering == null) {
            throw new CloudRuntimeException("Failed to get the root disk in backed-up volumes info from backup");
        }
        return rootDiskOffering;
    }

    @Override
    public List<VmDiskInfo> getDataDiskInfoListFromBackup(Backup backup) {
        List<VmDiskInfo> vmDiskInfoList = new ArrayList<>();
        List<Backup.VolumeInfo> volumes = backup.getBackedUpVolumes();
        if (volumes == null || volumes.isEmpty()) {
            throw new CloudRuntimeException("Failed to get backed-up Volumes info from backup");
        }
        for (Backup.VolumeInfo volume : volumes) {
            if (volume.getType() == Volume.Type.DATADISK) {
                DiskOfferingVO diskOffering = diskOfferingDao.findByUuid(volume.getDiskOfferingId());
                if (diskOffering == null || diskOffering.getState().equals(DiskOffering.State.Inactive)) {
                    throw new CloudRuntimeException("Unable to find the disk offering with uuid (" + volume.getDiskOfferingId() + ") stored in backup. " +
                            "Please specify a valid disk offering id while creating the instance");
                }
                Long size = volume.getSize() / (1024 * 1024 * 1024);
                vmDiskInfoList.add(new VmDiskInfo(diskOffering, size, volume.getMinIops(), volume.getMaxIops(), volume.getDeviceId()));
            }
        }
        return vmDiskInfoList;
    }

    @Override
    public Map<Long, Network.IpAddresses> getIpToNetworkMapFromBackup(Backup backup, boolean preserveIps, List<Long> networkIds)
    {
        Map<Long, Network.IpAddresses> ipToNetworkMap = new LinkedHashMap<Long, Network.IpAddresses>();

        String nicsJson = backup.getDetail(ApiConstants.NICS);
        if (nicsJson == null) {
            throw new CloudRuntimeException("Backup doesn't contain network information. " +
                    "Please specify at least one valid network while creating instance");
        }

        Type type = new TypeToken<List<Map<String, String>>>(){}.getType();
        List<Map<String, String>> nics = new Gson().fromJson(nicsJson, type);

        for (Map<String, String> nic : nics) {
            String networkUuid = nic.get(ApiConstants.NETWORK_ID);
            if (networkUuid == null) {
                throw new CloudRuntimeException("Backup doesn't contain network information. " +
                        "Please specify at least one valid network while creating instance");
            }

            Network network = networkDao.findByUuid(networkUuid);
            if (network == null) {
                throw new CloudRuntimeException("Unable to find network with the uuid " + networkUuid + " stored in backup. " +
                        "Please specify a valid network id while creating the instance");
            }

            Long networkId = network.getId();
            Network.IpAddresses ipAddresses = null;

            if (preserveIps) {
                String ip = nic.get(ApiConstants.IP_ADDRESS);
                String ipv6 = nic.get(ApiConstants.IP6_ADDRESS);
                String mac = nic.get(ApiConstants.MAC_ADDRESS);
                ipAddresses = networkService.getIpAddressesFromIps(ip, ipv6, mac);
            }

            ipToNetworkMap.put(networkId, ipAddresses);
            networkIds.add(networkId);
        }
        return ipToNetworkMap;
    }

    private void processRestoreBackupToVMFailure(VMInstanceVO vm, Backup backup, Long eventId, boolean restoreStateRequested) {
        if (restoreStateRequested) {
            updateVolumeState(vm, Volume.Event.RestoreFailed, Volume.State.Ready);
            updateVmState(vm, VirtualMachine.Event.RestoringFailed, VirtualMachine.State.Stopped);
        }
        if (eventId != null) {
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_CREATE_FROM_BACKUP,
                    String.format("Failed to create Instance %s from backup %s", vm.getInstanceName(), backup.getUuid()),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(), eventId);
        }
    }

    @Override
    public Boolean canCreateInstanceFromBackup(final Long backupId) {
        final BackupVO backup = backupDao.findById(backupId);
        BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("Failed to find backup offering");
        }
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        return backupProvider.supportsInstanceFromBackup();
    }

    @Override
    public Boolean canCreateInstanceFromBackupAcrossZones(final Long backupId) {
        final BackupVO backup = backupDao.findById(backupId);
        BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("Failed to find backup offering");
        }
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        return backupProvider.crossZoneInstanceCreationEnabled(offering);
    }

    @Override
    public boolean restoreBackupToVM(final Long backupId, final Long vmId) throws CloudRuntimeException {
        final BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }
        if (backup.getStatus() != Backup.Status.BackedUp) {
            throw new CloudRuntimeException("Backup should be in BackedUp state");
        }
        validateBackupForZone(backup.getZoneId());

        VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
        if (vm == null) {
            throw new CloudRuntimeException("Instance with ID " + backup.getVmId() + " couldn't be found.");
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        if (vm.getRemoved() != null) {
            throw new CloudRuntimeException("Instance with ID " + backup.getVmId() + " couldn't be found.");
        }
        if (!vm.getState().equals(VirtualMachine.State.Stopped)) {
            throw new CloudRuntimeException("The VM should be in stopped state");
        }

        List<Backup.VolumeInfo> backupVolumes = backup.getBackedUpVolumes();
        if (backupVolumes == null) {
            throw new CloudRuntimeException("Backed up volumes info not found in the backup");
        }

        List<VolumeVO> vmVolumes = volumeDao.findByInstance(vmId);
        if (vmVolumes.size() != backupVolumes.size()) {
            throw new CloudRuntimeException("Unable to create Instance from backup as the backup has a different number of disks than the Instance");
        }

        BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("Failed to find backup offering");
        }
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        if (!backupProvider.supportsInstanceFromBackup()) {
            throw new CloudRuntimeException("Create instance from backup is not supported by the " + offering.getProvider() + " provider.");
        }
        final boolean netBackupRestore = isAblestackNetBackupOffering(offering);
        final String netBackupRestoreRequestIdentifier = netBackupRestore ? netBackupRestoreCoordinator.getMoldRestoreRequestIdentifier(backup) : null;
        final VMInstanceVO netBackupRestoreMarkerVm = netBackupRestore ? netBackupRestoreCoordinator.getRestoreMarkerVm(backup, vm) : null;

        String backupDetailsInMessage = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(backup, "uuid", "externalId", "name");
        Pair<Boolean, String> result = null;
        Long eventId = null;
        boolean restoreStateRequested = false;
        try {
            if (netBackupRestore) {
                netBackupRestoreCoordinator.blockIfRestoreAlreadyActive(netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier);
                final RestorePhase phase = isNetBackupIncrementalBackup(backup)
                        ? RestorePhase.CHAIN_RESTORE_IN_PROGRESS
                        : RestorePhase.ROOT_RESTORE_IN_PROGRESS;
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, phase);
            }
            updateVmState(vm, VirtualMachine.Event.RestoringRequested, VirtualMachine.State.Restoring);
            updateVolumeState(vm, Volume.Event.RestoreRequested, Volume.State.Restoring);
            restoreStateRequested = true;
            eventId = ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventTypes.EVENT_VM_CREATE_FROM_BACKUP,
                    String.format("Creating Instance %s from backup %s", vm.getInstanceName(), backup.getUuid()),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),
                    true, 0);

            String host = null;
            String dataStore = null;
            if (!BackupProviderNameUtils.isNasFamily(offering.getProvider()) &&
                    !BackupProviderNameUtils.isCommvaultFamily(offering.getProvider())) {
                Pair<HostVO, StoragePoolVO> restoreInfo = getRestoreVolumeHostAndDatastore(vm);
                host = restoreInfo.first().getPrivateIpAddress();
                dataStore = restoreInfo.second().getUuid();
            }
            result = backupProvider.restoreBackupToVM(vm, backup, host, dataStore);

        } catch (Exception e) {
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, RestorePhase.FAILED);
            }
            logger.error(String.format("Failed to create Instance [%s] from backup [%s] due to: [%s]", vm.getInstanceName(), backupDetailsInMessage, e.getMessage()), e);
            processRestoreBackupToVMFailure(vm, backup, eventId, restoreStateRequested);
            throw new CloudRuntimeException(String.format("Error while creating Instance [%s] from backup [%s].", vm.getUuid(), backupDetailsInMessage));
        }

        if (result != null && !result.first()) {
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, RestorePhase.FAILED);
            }
            String error_msg = String.format("Failed to create Instance [%s] from backup [%s] due to: %s.", vm.getInstanceName(), backupDetailsInMessage, result.second());
            logger.error(error_msg);
            processRestoreBackupToVMFailure(vm, backup, eventId, restoreStateRequested);
            throw new CloudRuntimeException(error_msg);
        }

        updateVolumeState(vm, Volume.Event.RestoreSucceeded, Volume.State.Ready);
        updateVmState(vm, VirtualMachine.Event.RestoringSuccess, VirtualMachine.State.Stopped);
        ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_VM_CREATE_FROM_BACKUP,
                String.format("Successfully created Instance %s from backup %s", vm.getInstanceName(), backup.getUuid()),
                vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),eventId);
        if (netBackupRestore) {
            netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, RestorePhase.COMPLETED);
        }
        return true;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_RESTORE, eventDescription = "restoring VM from backup", async = true)
    public boolean restoreBackupVolumeAndAttachToVM(final String backedUpVolumeUuid, final Long backupId, final Long vmId) throws Exception {
        if (StringUtils.isEmpty(backedUpVolumeUuid)) {
            throw new CloudRuntimeException("Invalid volume ID passed");
        }
        final BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Provided backup not found");
        }
        if (backup.getStatus() != Backup.Status.BackedUp) {
            throw new CloudRuntimeException("Backup should be in BackedUp state");
        }
        validateBackupForZone(backup.getZoneId());

        final VMInstanceVO vm = findVmById(vmId);
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        if (vm.getBackupOfferingId() != null && !BackupEnableAttachDetachVolumes.value()) {
            throw new CloudRuntimeException("The selected VM has backups, cannot restore and attach volume to the VM.");
        }

        if (backup.getZoneId() != vm.getDataCenterId()) {
            throw new CloudRuntimeException("Cross zone backup restoration of volume is not allowed");
        }

        List<Backup.VolumeInfo> volumeInfoList = backup.getBackedUpVolumes();
        if (volumeInfoList == null) {
            final VMInstanceVO vmFromBackup = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
            if (vmFromBackup == null) {
                throw new CloudRuntimeException("VM reference for the provided VM backup not found");
            } else if (vmFromBackup.getBackupVolumeList() == null) {
                throw new CloudRuntimeException("Volumes metadata not found in the backup");
            }
            volumeInfoList = vmFromBackup.getBackupVolumeList();
        }
        Backup.VolumeInfo backupVolumeInfo = getVolumeInfo(volumeInfoList, backedUpVolumeUuid);
        if (backupVolumeInfo == null) {
            throw new CloudRuntimeException("Failed to find volume with Id " + backedUpVolumeUuid + " in the backed-up volumes metadata");
        }

        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        final BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("Failed to find VM backup offering");
        }

        BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        VolumeVO backedUpVolume = volumeDao.findByUuid(backedUpVolumeUuid);
        Pair<HostVO, StoragePoolVO> restoreInfo;
        if ((!BackupProviderNameUtils.isNasFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isCommvaultFamily(offering.getProvider())) || backedUpVolume == null) {
            restoreInfo = getRestoreVolumeHostAndDatastore(vm);
        } else {
            restoreInfo = getRestoreVolumeHostAndDatastoreForNas(vm, backedUpVolume);
        }

        HostVO host = restoreInfo.first();
        StoragePoolVO datastore = restoreInfo.second();

        logger.debug("Asking provider to restore volume {} from backup {} (with external" +
                " ID {}) and attach it to VM: {}", backedUpVolumeUuid, backup, backup.getExternalId(), vm);

        logger.debug(String.format("Trying to restore volume using host private IP address: [%s].", host.getPrivateIpAddress()));

        final boolean netBackupRestore = isAblestackNetBackupOffering(offering);
        final boolean snapshotSensitiveVolumeRestore = isSnapshotSensitiveVolumeRestoreOffering(offering);
        if (snapshotSensitiveVolumeRestore) {
            validateNoVmSnapshotsForRestoreVolumeAttach(vm);
        }
        String[] hostPossibleValues = netBackupRestore
                ? new String[]{host.getPrivateIpAddress()}
                : new String[]{host.getPrivateIpAddress(), host.getName()};
        String[] datastoresPossibleValues = netBackupRestore
                ? new String[]{datastore.getUuid()}
                : new String[]{datastore.getUuid(), datastore.getName()};
        final String netBackupRestoreRequestIdentifier = netBackupRestore ? netBackupRestoreCoordinator.getMoldRestoreRequestIdentifier(backup) : null;
        final VMInstanceVO netBackupRestoreMarkerVm = netBackupRestore ? netBackupRestoreCoordinator.getRestoreMarkerVm(backup, vm) : null;
        if (netBackupRestore) {
            netBackupRestoreCoordinator.blockIfRestoreAlreadyActive(netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier);
            final RestorePhase phase = isNetBackupIncrementalBackup(backup)
                    ? RestorePhase.CHAIN_RESTORE_IN_PROGRESS
                    : RestorePhase.ROOT_RESTORE_IN_PROGRESS;
            netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, phase);
        }

        Pair<Boolean, String> result = restoreBackedUpVolume(backupVolumeInfo, backup, backupProvider, hostPossibleValues, datastoresPossibleValues, vm);

        if (BooleanUtils.isFalse(result.first())) {
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, RestorePhase.FAILED);
            }
            throw new CloudRuntimeException(String.format("Error restoring volume [%s] of VM [%s] to host [%s] using backup provider [%s] due to: [%s].",
                    backedUpVolumeUuid, vm.getUuid(), host.getUuid(), backupProvider.getName(), result.second()));
        }
        try {
            if (!attachVolumeToVM(vm.getDataCenterId(), result.second(), backupVolumeInfo,
                                backedUpVolumeUuid, vm, datastore.getUuid(), backup)) {
                cleanupRestoredVolumeAfterAttachFailure(result.second());
                throw new CloudRuntimeException(String.format("Error attaching volume [%s] to VM [%s].", backedUpVolumeUuid, vm.getUuid()));
            }
            runPostRestoreMaintenance(backupProvider, vm, backup, true);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, RestorePhase.COMPLETED);
            }
        } catch (Exception e) {
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, RestorePhase.FAILED);
            }
            cleanupRestoredVolumeAfterAttachFailure(result.second());
            throw e;
        }
        return true;
    }

    private void validateNoVmSnapshotsForRestoreVolumeAttach(final VMInstanceVO vm) {
        final List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vm.getId());
        if (CollectionUtils.isNotEmpty(vmSnapshots)) {
            throw new CloudRuntimeException(String.format(
                    "Unable to restore and attach volume to VM [%s] while Instance snapshots exist. Remove Instance snapshots before restoring and attaching the volume.",
                    vm.getInstanceName()));
        }
    }

    private boolean isSnapshotSensitiveVolumeRestoreOffering(final BackupOffering offering) {
        return offering != null && (BackupProviderNameUtils.isNetBackupFamily(offering.getProvider()) ||
                BackupProviderNameUtils.isNasFamily(offering.getProvider()) ||
                BackupProviderNameUtils.isCommvaultFamily(offering.getProvider()));
    }

    protected Pair<Boolean, String> restoreBackedUpVolume(final Backup.VolumeInfo backupVolumeInfo, final BackupVO backup,
            BackupProvider backupProvider, String[] hostPossibleValues, String[] datastoresPossibleValues, VMInstanceVO vm) {
        Pair<Boolean, String> result = new Pair<>(false, "");
        List<String> failureDetails = new ArrayList<>();
        for (String hostData : hostPossibleValues) {
            for (String datastoreData : datastoresPossibleValues) {
                logger.debug(String.format("Trying to restore volume [UUID: %s], using host [%s] and datastore [%s].",
                        backupVolumeInfo.getUuid(), hostData, datastoreData));

                try {
                    result = backupProvider.restoreBackedUpVolume(backup, backupVolumeInfo, hostData, datastoreData, new Pair<>(vm.getName(), vm.getState()));
                    if (result != null && StringUtils.isNotBlank(result.second())) {
                        failureDetails.add(String.format("host [%s], datastore [%s]: %s", hostData, datastoreData, result.second()));
                    }

                    if (result != null && BooleanUtils.isTrue(result.first())) {
                        logger.info("Successfully restored volume [UUID: {}] using host [{}] and datastore [{}] through backup provider [{}]. Result details: [{}]",
                                backupVolumeInfo.getUuid(), hostData, datastoreData, backupProvider.getName(), result.second());
                        return result;
                    }
                } catch (Exception e) {
                    failureDetails.add(String.format("host [%s], datastore [%s]: %s", hostData, datastoreData, e.getMessage()));
                    logger.debug(String.format("Failed to restore volume [UUID: %s], using host [%s] and datastore [%s] due to: [%s].",
                            backupVolumeInfo.getUuid(), hostData, datastoreData, e.getMessage()), e);
                }
            }
        }
        final String resultDetails = result != null ? result.second() : null;
        return new Pair<>(false, CollectionUtils.isNotEmpty(failureDetails) ? StringUtils.join(failureDetails, "; ") : resultDetails);
    }

    private void runPostRestoreMaintenance(final BackupProvider backupProvider, final VirtualMachine vm, final Backup backup, final boolean volumeOnly) {
        if (!backupProvider.supportsPostRestoreMaintenance()) {
            return;
        }
        try {
            backupProvider.runPostRestoreMaintenance(vm, backup, volumeOnly);
        } catch (Exception e) {
            logger.warn("Post-restore maintenance failed for provider {} on VM {} and backup {}: {}", backupProvider.getName(),
                    vm != null ? vm.getUuid() : null, backup != null ? backup.getUuid() : null, e.getMessage(), e);
            schedulePostRestoreMaintenanceRetry(backupProvider, vm, backup, volumeOnly);
        }
    }

    private void schedulePostRestoreMaintenanceRetry(final BackupProvider backupProvider, final VirtualMachine vm, final Backup backup, final boolean volumeOnly) {
        if (backupProvider == null || vm == null || backup == null) {
            return;
        }
        synchronized (postRestoreMaintenanceTasks) {
            postRestoreMaintenanceTasks.add(new PostRestoreMaintenanceTask(backupProvider.getName(), vm.getId(), backup.getId(), volumeOnly, 1,
                    System.currentTimeMillis() + POST_RESTORE_MAINTENANCE_RETRY_INTERVAL_MS));
        }
    }

    private static final class PostRestoreMaintenanceTask {
        private final String providerName;
        private final long vmId;
        private final long backupId;
        private final boolean volumeOnly;
        private int retryCount;
        private long nextAttemptEpochMs;

        private PostRestoreMaintenanceTask(String providerName, long vmId, long backupId, boolean volumeOnly, int retryCount, long nextAttemptEpochMs) {
            this.providerName = providerName;
            this.vmId = vmId;
            this.backupId = backupId;
            this.volumeOnly = volumeOnly;
            this.retryCount = retryCount;
            this.nextAttemptEpochMs = nextAttemptEpochMs;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_DELETE, eventDescription = "deleting VM backup", async = true)
    public boolean deleteBackup(final Long backupId, final Boolean forced) throws ResourceAllocationException {
        final BackupVO backup = backupDao.findByIdIncludingRemoved(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }

        final Long vmId = backup.getVmId();
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
        if (vm == null) {
            logger.warn("Instance {} not found for backup {} during delete backup", vmId, backup.toString());
        }
        logger.debug("Deleting backup {} belonging to instance {}", backup.toString(), vmId);

        validateBackupForZone(backup.getZoneId());
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm == null ? backup : vm);

        checkForPendingBackupJobs(backup);
        final BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException(String.format("Backup offering with ID [%s] does not exist.", backup.getBackupOfferingId()));
        }
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        return deleteCheckedBackup(forced, backupProvider, backup, vm);
    }

    private boolean deleteCheckedBackup(Boolean forced, BackupProvider backupProvider, BackupVO backup, VMInstanceVO vm) throws ResourceAllocationException {
        Account owner = accountManager.getAccount(backup.getAccountId());
        long backupSize = backup.getSize() != null ? backup.getSize() : 0L;
        try (CheckedReservation backupReservation = new CheckedReservation(owner, Resource.ResourceType.backup,
                backup.getId(), null, -1L, reservationDao, resourceLimitMgr);
            CheckedReservation backupStorageReservation = new CheckedReservation(owner,
                     Resource.ResourceType.backup_storage, backup.getId(), null, -1 * backupSize,
                    reservationDao, resourceLimitMgr)) {
            boolean result = backupProvider.deleteBackup(backup, forced);
            if (result) {
                resourceLimitMgr.decrementResourceCount(backup.getAccountId(), Resource.ResourceType.backup);
                resourceLimitMgr.decrementResourceCount(backup.getAccountId(), Resource.ResourceType.backup_storage, backupSize);
                if (backupDao.remove(backup.getId())) {
                    backupDetailsDao.removeDetails(backup.getId());
                    checkAndGenerateUsageForLastBackupDeletedAfterOfferingRemove(vm, backup);
                    return true;
                } else {
                    return false;
                }
            }
            throw new CloudRuntimeException("Failed to delete the backup");
        }
    }

    private void checkForPendingBackupJobs(final BackupVO backup) {
        String backupUuid = backup.getUuid();
        long pendingJobs = asyncJobManager.countPendingJobs(backupUuid,
                CreateVMFromBackupCmd.class.getName(),
                CreateVMFromBackupCmdByAdmin.class.getName(),
                RestoreBackupCmd.class.getName(),
                RestoreVolumeFromBackupAndAttachToVMCmd.class.getName());
        if (pendingJobs > 0) {
            throw new CloudRuntimeException("Cannot delete Backup while a create Instance from Backup or restore Backup operation is in progress, please try again later.");
        }
    }

    /**
     * Get the pair: hostIp, datastoreUuid in which to restore the volume, based on the VM to be attached information
     */
    private Pair<HostVO, StoragePoolVO> getRestoreVolumeHostAndDatastore(VMInstanceVO vm) {
        List<VolumeVO> rootVmVolume = volumeDao.findIncludingRemovedByInstanceAndType(vm.getId(), Volume.Type.ROOT);
        Long poolId = rootVmVolume.get(0).getPoolId();
        StoragePoolVO storagePoolVO = primaryDataStoreDao.findById(poolId);
        if (storagePoolVO == null) {
            throw new CloudRuntimeException("The volume of the virtual machine to which you are trying to attach the backup volume is not in the Ready state, and the storage pool for the volume cannot be found.");
        }
        HostVO hostVO = vm.getHostId() == null ?
                            getFirstHostFromStoragePool(storagePoolVO) :
                            hostDao.findById(vm.getHostId());
        return new Pair<>(hostVO, storagePoolVO);
    }

    private Pair<HostVO, StoragePoolVO> getRestoreVolumeHostAndDatastoreForNas(VMInstanceVO vm, VolumeVO backedVolume) {
        Long poolId = backedVolume.getPoolId();
        StoragePoolVO storagePoolVO = primaryDataStoreDao.findById(poolId);
        HostVO hostVO = vm.getHostId() == null ?
                getFirstHostFromStoragePool(storagePoolVO) :
                hostDao.findById(vm.getHostId());
        return new Pair<>(hostVO, storagePoolVO);
    }

    /**
     * Find a host from storage pool access
     */
    private HostVO getFirstHostFromStoragePool(StoragePoolVO storagePoolVO) {
        List<HostVO> hosts = null;
        if (storagePoolVO.getScope().equals(ScopeType.CLUSTER)) {
            hosts = hostDao.findByClusterId(storagePoolVO.getClusterId());

        } else if (storagePoolVO.getScope().equals(ScopeType.ZONE)) {
            hosts = hostDao.findByDataCenterId(storagePoolVO.getDataCenterId());
        }
        return hosts.get(0);
    }


    /**
     * Attach volume to VM
     */
    private boolean attachVolumeToVM(Long zoneId, String restoredVolumeLocation, Backup.VolumeInfo backupVolumeInfo,
                                     String volumeUuid, VMInstanceVO vm, String datastoreUuid, Backup backup) throws Exception {
        HypervisorGuru guru = hypervisorGuruManager.getGuru(vm.getHypervisorType());
        backupVolumeInfo.setType(Volume.Type.DATADISK);

        logger.info("Attaching the restored volume {} to VM {}.", () -> ReflectionToStringBuilder.toString(backupVolumeInfo, ToStringStyle.JSON_STYLE), () -> vm);
        StoragePoolVO pool = primaryDataStoreDao.findByUuid(datastoreUuid);
        try {
            return guru.attachRestoredVolumeToVirtualMachine(zoneId, restoredVolumeLocation, backupVolumeInfo, vm, pool.getId(), backup);
        } catch (Exception e) {
            throw new CloudRuntimeException("Error attach restored volume to VM " + vm.getUuid() + " due to: " + e.getMessage());
        }
    }

    private void cleanupRestoredVolumeAfterAttachFailure(String restoredVolumeLocation) {
        if (StringUtils.isBlank(restoredVolumeLocation)) {
            return;
        }
        VolumeVO restoredVolume = volumeDao.findByUuid(restoredVolumeLocation);
        if (restoredVolume == null) {
            return;
        }
        try {
            Account caller = CallContext.current() != null ? CallContext.current().getCallingAccount() : accountDao.findById(restoredVolume.getAccountId());
            volumeApiService.deleteVolume(restoredVolume.getId(), caller);
        } catch (Exception e) {
            logger.warn("Failed to cleanup restored volume {} after attach failure", restoredVolumeLocation, e);
        }
    }

    private void checkAndGenerateUsageForLastBackupDeletedAfterOfferingRemove(VirtualMachine vm, Backup backup) {
        if (vm != null &&
                (vm.getBackupOfferingId() == null || vm.getBackupOfferingId() != backup.getBackupOfferingId())) {
            List<Backup> backups = backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), backup.getBackupOfferingId());
            if (backups.size() == 0) {
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_BACKUP_OFFERING_REMOVED_AND_BACKUPS_DELETED, vm.getAccountId(),
                        vm.getDataCenterId(), vm.getId(), "Backup-" + vm.getHostName() + "-" + vm.getUuid(),
                        backup.getBackupOfferingId(), null, null, Backup.class.getSimpleName(), vm.getUuid());
            }
        }
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        backgroundPollManager.submitTask(new BackupSyncTask(this));
        return true;
    }

    public boolean isDisabled(final Long zoneId) {
        return !(BackupFrameworkEnabled.value() && BackupFrameworkEnabled.valueIn(zoneId));
    }

    @Override
    public void validateBackupForZone(final Long zoneId) {
        if (zoneId == null || isDisabled(zoneId)) {
            throw new CloudRuntimeException("Backup and Recovery feature is disabled for the zone");
        }
    }

    @Override
    public List<BackupProvider> listBackupProviders() {
        final List<BackupProvider> providers = new ArrayList<>();
        final Set<String> seenProviders = new HashSet<>();
        for (final BackupProvider provider : backupProviders) {
            if (provider == null) {
                continue;
            }
            final String displayName = BackupProviderNameUtils.toDisplayName(provider.getName());
            if (seenProviders.add(displayName)) {
                providers.add(provider);
            }
        }
        return providers;
    }

    @Override
    public List<BackupProvider> listBackupProvidersForZone(final Long zoneId) {
        validateBackupForZone(zoneId);
        return getBackupProvidersForZone(zoneId);
    }

    @Override
    public BackupProvider getBackupProviderForOffering(final Long offeringId) {
        final BackupOfferingVO offering = backupOfferingDao.findById(offeringId);
        if (offering == null) {
            throw new CloudRuntimeException("Backup offering not found: " + offeringId);
        }
        return getBackupProvider(offering.getProvider());
    }

    public List<BackupProvider> getBackupProvidersForZone(final Long zoneId) {
        final String providersConfig = BackupProviderPlugin.valueIn(zoneId);
        if (StringUtils.isEmpty(providersConfig)) {
            throw new CloudRuntimeException("No backup providers configured for zone: " + zoneId);
        }
        List<BackupProvider> providers = new ArrayList<>();
        String[] providerNames = providersConfig.split(",");
        for (String name : providerNames) {
            String trimmedName = name.trim();
            if (!StringUtils.isEmpty(trimmedName)) {
                try {
                    BackupProvider provider = getBackupProvider(trimmedName);
                    boolean exists = providers.stream().anyMatch(p ->
                            BackupProviderNameUtils.toDisplayName(p.getName()).equalsIgnoreCase(
                                    BackupProviderNameUtils.toDisplayName(provider.getName())));
                    if (!exists) {
                        providers.add(provider);
                    }
                } catch (CloudRuntimeException e) {
                    logger.warn("Failed to load backup provider: " + trimmedName + " for zone: " + zoneId, e);
                }
            }
        }
        if (providers.isEmpty()) {
            throw new CloudRuntimeException("No valid backup providers found for zone: " + zoneId);
        }
        return providers;
    }

    public BackupProvider getBackupProvider(final String name) {
        if (StringUtils.isEmpty(name)) {
            throw new CloudRuntimeException("Invalid backup provider name provided");
        }
        final String canonicalName = BackupProviderNameUtils.canonicalize(name);
        if (!backupProvidersMap.containsKey(canonicalName)) {
            throw new CloudRuntimeException("Failed to find backup provider by the name: " + canonicalName);
        }
        return backupProvidersMap.get(canonicalName);
    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<Class<?>>();
        if (!BackupFrameworkEnabled.value()) {
            return cmdList;
        }

        // Offerings
        cmdList.add(ListBackupProvidersCmd.class);
        cmdList.add(ListBackupProvidersForZoneCmd.class);
        cmdList.add(ListBackupProviderOfferingsCmd.class);
        cmdList.add(ImportBackupOfferingCmd.class);
        cmdList.add(CloneBackupOfferingCmd.class);
        cmdList.add(ListBackupOfferingsCmd.class);
        cmdList.add(DeleteBackupOfferingCmd.class);
        cmdList.add(UpdateBackupOfferingCmd.class);
        cmdList.add(UpdateNetBackupCmd.class);
        // Assignment
        cmdList.add(AssignVirtualMachineToBackupOfferingCmd.class);
        cmdList.add(RemoveVirtualMachineFromBackupOfferingCmd.class);
        // Schedule
        cmdList.add(CreateBackupScheduleCmd.class);
        cmdList.add(UpdateBackupScheduleCmd.class);
        cmdList.add(ListBackupScheduleCmd.class);
        cmdList.add(DeleteBackupScheduleCmd.class);
        // Operations
        cmdList.add(CreateBackupCmd.class);
        cmdList.add(CreateNetBackupCmd.class);
        cmdList.add(ImportAblestackVeeamBackupSeedCmd.class);
        cmdList.add(ListVeeamRestorePointsCmd.class);
        cmdList.add(CreateAblestackVeeamBackupCmd.class);
        cmdList.add(RestoreAblestackVeeamBackupCmd.class);
        cmdList.add(ListAblestackVeeamBackupsCmd.class);
        cmdList.add(ListBackupsCmd.class);
        cmdList.add(RestoreBackupCmd.class);
        cmdList.add(PrepareNetBackupRestoreCmd.class);
        cmdList.add(RestoreNetBackupCmd.class);
        cmdList.add(DeleteBackupCmd.class);
        cmdList.add(RestoreVolumeFromBackupAndAttachToVMCmd.class);
        cmdList.add(AddBackupRepositoryCmd.class);
        cmdList.add(UpdateBackupRepositoryCmd.class);
        cmdList.add(DeleteBackupRepositoryCmd.class);
        cmdList.add(ListBackupRepositoriesCmd.class);
        cmdList.add(CreateVMFromBackupCmd.class);
        cmdList.add(CreateVMFromBxBackupCmd.class);
        cmdList.add(CreateVMFromBackupCmdByAdmin.class);
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return BackupService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey[]{
                BackupFrameworkEnabled,
                BackupProviderPlugin,
                BackupSyncPollingInterval,
                BackupCommandTimeout,
                BackupRestoreTimeout,
                BackupEnableAttachDetachVolumes,
                KvmIncrementalBackup,
                BackupChainSize,
                DefaultMaxAccountBackups,
                DefaultMaxAccountBackupStorage,
                DefaultMaxProjectBackups,
                DefaultMaxProjectBackupStorage,
                DefaultMaxDomainBackups,
                DefaultMaxDomainBackupStorage,
                BackupStorageCapacityThreshold
        };
    }

    public void setBackupProviders(final List<BackupProvider> backupProviders) {
        this.backupProviders = backupProviders;
    }

    private void initializeBackupProviderMap() {
        if (backupProviders != null) {
            for (final BackupProvider backupProvider : backupProviders) {
                backupProvidersMap.put(backupProvider.getName().toLowerCase(), backupProvider);
            }
        }
    }

    public void poll(final Date timestamp) {
        currentTimestamp = timestamp;
        GlobalLock scanLock = GlobalLock.getInternLock("backup.poll");
        try {
            if (scanLock.lock(5)) {
                try {
                    checkStatusOfCurrentlyExecutingBackups();
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }

        scanLock = GlobalLock.getInternLock("backup.poll");
        try {
            if (scanLock.lock(5)) {
                try {
                    scheduleBackups();
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }
    }

    @DB
    private Date scheduleNextBackupJob(final BackupScheduleVO backupSchedule) {
        final Date nextTimestamp = DateUtil.getNextRunTime(backupSchedule.getScheduleType(), backupSchedule.getSchedule(),
                backupSchedule.getTimezone(), currentTimestamp);
        return Transaction.execute(new TransactionCallback<Date>() {
            @Override
            public Date doInTransaction(TransactionStatus status) {
                backupSchedule.setScheduledTimestamp(nextTimestamp);
                backupSchedule.setAsyncJobId(null);
                backupScheduleDao.update(backupSchedule.getId(), backupSchedule);
                return nextTimestamp;
            }
        });
    }

    private void checkStatusOfCurrentlyExecutingBackups() {
        final SearchCriteria<BackupScheduleVO> sc = backupScheduleDao.createSearchCriteria();
        sc.addAnd("asyncJobId", SearchCriteria.Op.NNULL);
        final List<BackupScheduleVO> backupSchedules = backupScheduleDao.search(sc, null);
        for (final BackupScheduleVO backupSchedule : backupSchedules) {
            final Long asyncJobId = backupSchedule.getAsyncJobId();
            final AsyncJobVO asyncJob = asyncJobManager.getAsyncJob(asyncJobId);
            if (asyncJob == null) {
                logger.warn("Backup schedule [id: {}, uuid: {}, vmId: {}] references missing async job [id: {}]. Scheduling next backup run.",
                        backupSchedule.getId(), backupSchedule.getUuid(), backupSchedule.getVmId(), asyncJobId);
                scheduleNextBackupJob(backupSchedule);
                continue;
            }
            switch (asyncJob.getStatus()) {
                case SUCCEEDED:
                case FAILED:
                case CANCELLED:
                    final Date nextDateTime = scheduleNextBackupJob(backupSchedule);
                    final String nextScheduledTime = DateUtil.displayDateInTimezone(DateUtil.GMT_TIMEZONE, nextDateTime);
                    logger.info("Backup schedule [id: {}, uuid: {}, vmId: {}] completed async job [id: {}, uuid: {}, status: {}]. Next scheduled time is [{}].",
                            backupSchedule.getId(), backupSchedule.getUuid(), backupSchedule.getVmId(), asyncJob.getId(), asyncJob.getUuid(), asyncJob.getStatus(), nextScheduledTime);
                    break;
            default:
                logger.debug("Found async backup job [id: {}, uuid: {}, scheduleId: {}, vmId: {}] with " +
                        "status [{}] and cmd information: [cmd: {}, cmdInfo: {}].",
                        asyncJob.getId(), asyncJob.getUuid(), backupSchedule.getId(), backupSchedule.getVmId(),
                        asyncJob.getStatus(), asyncJob.getCmd(), asyncJob.getCmdInfo());
                break;
            }
        }
    }

    @DB
    public void scheduleBackups() {
        String displayTime = DateUtil.displayDateInTimezone(DateUtil.GMT_TIMEZONE, currentTimestamp);
        logger.debug("Backup backup.poll is being called at " + displayTime);

        final List<BackupScheduleVO> backupsToBeExecuted = backupScheduleDao.getSchedulesToExecute(currentTimestamp);
        logger.debug("Found [{}] backup schedules to execute at [{}].", backupsToBeExecuted.size(), displayTime);
        for (final BackupScheduleVO backupSchedule: backupsToBeExecuted) {
            final Long backupScheduleId = backupSchedule.getId();
            final Long vmId = backupSchedule.getVmId();
            final Boolean quiesceVm = backupSchedule.getQuiesceVM();

            final VMInstanceVO vm = vmInstanceDao.findById(vmId);
            if (vm == null || vm.getBackupOfferingId() == null) {
                backupScheduleDao.remove(backupScheduleId);
                continue;
            }

            final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
            if (offering == null || !offering.isUserDrivenBackupAllowed()) {
                continue;
            }

            if (isDisabled(vm.getDataCenterId())) {
                continue;
            }

            final Account backupAccount = accountService.getAccount(vm.getAccountId());
            if (backupAccount == null || backupAccount.getState() == Account.State.DISABLED) {
                logger.debug("Skip backup for VM ({}) since its account has been removed or disabled.", vm);
                continue;
            }

            if (logger.isDebugEnabled()) {
                final Date scheduledTimestamp = backupSchedule.getScheduledTimestamp();
                displayTime = DateUtil.displayDateInTimezone(DateUtil.GMT_TIMEZONE, scheduledTimestamp);
                logger.debug(String.format("Scheduling 1 backup for VM (%s) for backup schedule (%s) at [%s].",
                        vm, backupSchedule, displayTime));
            }

            BackupScheduleVO tmpBackupScheduleVO = null;

            try {
                tmpBackupScheduleVO = backupScheduleDao.acquireInLockTable(backupScheduleId);
                logger.info("Submitting scheduled backup [scheduleId: {}, scheduleUuid: {}, vmId: {}, vmUuid: {}, vmName: {}, provider: {}, offeringId: {}, scheduledTimestamp: {}, submitTime: {}].",
                        backupScheduleId, backupSchedule.getUuid(), vm.getId(), vm.getUuid(), vm.getInstanceName(), offering.getProvider(), offering.getId(),
                        backupSchedule.getScheduledTimestamp(), new Date());

                final Long eventId = ActionEventUtils.onScheduledActionEvent(User.UID_SYSTEM, vm.getAccountId(),
                        EventTypes.EVENT_VM_BACKUP_CREATE, "creating backup for VM ID:" + vm.getUuid(),
                        vmId, ApiCommandResourceType.VirtualMachine.toString(),
                        true, 0);
                final Map<String, String> params = new HashMap<String, String>();
                params.put(ApiConstants.VIRTUAL_MACHINE_ID, "" + vmId);
                params.put(ApiConstants.SCHEDULE_ID, String.valueOf(backupScheduleId));
                if (quiesceVm != null) {
                    params.put(ApiConstants.QUIESCE_VM, "" + quiesceVm.toString());
                }
                params.put("ctxUserId", "1");
                params.put("ctxAccountId", "" + vm.getAccountId());
                params.put("ctxStartEventId", String.valueOf(eventId));

                final CreateBackupCmd cmd = new CreateBackupCmd();
                ComponentContext.inject(cmd);
                apiDispatcher.dispatchCreateCmd(cmd, params);
                params.put("id", "" + vmId);
                params.put("ctxStartEventId", "1");

                AsyncJobVO job = new AsyncJobVO("", User.UID_SYSTEM, vm.getAccountId(), CreateBackupCmd.class.getName(),
                        ApiGsonHelper.getBuilder().create().toJson(params), vmId,
                        cmd.getApiResourceType() != null ? cmd.getApiResourceType().toString() : null, null);
                job.setDispatcher(asyncJobDispatcher.getName());

                final long jobId = asyncJobManager.submitAsyncJob(job);
                tmpBackupScheduleVO.setAsyncJobId(jobId);
                backupScheduleDao.update(backupScheduleId, tmpBackupScheduleVO);
                logger.info("Submitted scheduled backup [scheduleId: {}, scheduleUuid: {}, vmId: {}, vmUuid: {}, provider: {}, offeringId: {}, jobId: {}, jobUuid: {}].",
                        backupScheduleId, backupSchedule.getUuid(), vm.getId(), vm.getUuid(), offering.getProvider(), offering.getId(), jobId, job.getUuid());
            } catch (Exception e) {
                logger.error("Scheduling backup failed [scheduleId: {}, scheduleUuid: {}, vmId: {}, provider: {}, offeringId: {}] due to: [{}].",
                        backupScheduleId, backupSchedule.getUuid(), vmId, offering.getProvider(), offering.getId(), e.getMessage(), e);
            } finally {
                if (tmpBackupScheduleVO != null) {
                    backupScheduleDao.releaseFromLockTable(backupScheduleId);
                }
            }
        }
    }

    @Override
    public boolean start() {
        initializeBackupProviderMap();

        currentTimestamp = new Date();
        for (final BackupScheduleVO backupSchedule : backupScheduleDao.listAll()) {
            scheduleNextBackupJob(backupSchedule);
        }
        final TimerTask backupPollTask = new ManagedContextTimerTask() {
            @Override
            protected void runInContext() {
            try {
                poll(new Date());
            } catch (final Throwable t) {
                logger.warn("Catch throwable in backup scheduler ", t);
            }
            }
        };

        backupTimer = new Timer("BackupPollTask");
        backupTimer.schedule(backupPollTask, BackupSyncPollingInterval.value() * 1000L, BackupSyncPollingInterval.value() * 1000L);
        return true;
    }

    private VMInstanceVO findVmById(final Long vmId) {
        final VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new CloudRuntimeException(String.format("Can't find any VM with ID: [%s].", vmId));
        }
        return vm;
    }

    ////////////////////////////////////////////////////
    /////////////// Background Tasks ///////////////////
    ////////////////////////////////////////////////////

    /**
     * This background task syncs backups from providers side in CloudStack db
     * along with creation of usage records
     */
    protected final class BackupSyncTask extends ManagedContextRunnable implements BackgroundPollTask {
        private BackupManager backupManager;

        public BackupSyncTask(final BackupManager backupManager) {
            this.backupManager = backupManager;
        }

        @Override
        protected void runInContext() {
            try {
                if (logger.isTraceEnabled()) {
                    logger.trace("Backup sync background task is running...");
                }
                processPostRestoreMaintenanceTasks();
                for (final DataCenter dataCenter : dataCenterDao.listAllZones()) {
                    if (dataCenter == null || isDisabled(dataCenter.getId())) {
                        logger.debug("Backup Sync Task is not enabled in zone [{}]. Skipping this zone!", dataCenter == null ? "NULL Zone!" : dataCenter);
                        continue;
                    }

                    List<BackupProvider> providers = getBackupProvidersForZone(dataCenter.getId());
                    for (BackupProvider backupProvider : providers) {
                        try {
                            if (backupProvider.supportsBackgroundSync()) {
                                backupProvider.syncBackupStorageStats(dataCenter.getId());
                                syncOutOfBandBackups(backupProvider, dataCenter);
                            }
                            if (backupProvider.supportsBackgroundChainValidation()) {
                                backupProvider.validateChains(dataCenter.getId());
                            }
                            updateBackupUsageRecords(backupProvider, dataCenter);
                        } catch (Exception e) {
                            logger.error("Failed to sync backups for provider {} in zone {}: {}", backupProvider.getName(), dataCenter.getId(), e.getMessage(), e);
                        }
                    }
                }
            } catch (final Throwable t) {
                logger.error(String.format("Error trying to run backup-sync background task due to: [%s].", t.getMessage()), t);
            }
        }

        private void processPostRestoreMaintenanceTasks() {
            synchronized (postRestoreMaintenanceTasks) {
                if (postRestoreMaintenanceTasks.isEmpty()) {
                    return;
                }
                final long now = System.currentTimeMillis();
                final Iterator<PostRestoreMaintenanceTask> iterator = postRestoreMaintenanceTasks.iterator();
                while (iterator.hasNext()) {
                    final PostRestoreMaintenanceTask task = iterator.next();
                    if (task.nextAttemptEpochMs > now) {
                        continue;
                    }
                    try {
                        final BackupProvider backupProvider = getBackupProvider(task.providerName);
                        final BackupVO backup = backupDao.findByIdIncludingRemoved(task.backupId);
                        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(task.vmId);
                        if (backup == null || vm == null || !backupProvider.supportsPostRestoreMaintenance()) {
                            iterator.remove();
                            continue;
                        }
                        backupProvider.runPostRestoreMaintenance(vm, backup, task.volumeOnly);
                        iterator.remove();
                    } catch (Exception e) {
                        if (task.retryCount >= POST_RESTORE_MAINTENANCE_MAX_RETRIES) {
                            logger.warn("Exhausted post-restore maintenance retries for provider {}, VM {}, backup {} due to: {}",
                                    task.providerName, task.vmId, task.backupId, e.getMessage(), e);
                            iterator.remove();
                            continue;
                        }
                        task.retryCount++;
                        task.nextAttemptEpochMs = now + (POST_RESTORE_MAINTENANCE_RETRY_INTERVAL_MS * task.retryCount);
                        logger.warn("Post-restore maintenance retry {} scheduled for provider {}, VM {}, backup {} due to: {}",
                                task.retryCount, task.providerName, task.vmId, task.backupId, e.getMessage());
                    }
                }
            }
        }

        private void syncOutOfBandBackups(final BackupProvider backupProvider, DataCenter dataCenter) {
            if (!backupProvider.supportsOutOfBandBackupSync()) {
                return;
            }

            List<VMInstanceVO> vms = vmInstanceDao.listByZoneAndBackupOffering(dataCenter.getId(), null);
            if (vms == null || vms.isEmpty()) {
                logger.debug("Can't find any VM to sync backups in zone {}", dataCenter);
                return;
            }
            if (backupProvider.supportsBackupMetricsSync()) {
                backupProvider.syncBackupMetrics(dataCenter.getId());
            }
            int syncedVmCount = 0;
            for (final VMInstanceVO vm : vms) {
                try {
                    Long backupOfferingId = vm.getBackupOfferingId();
                    if (backupOfferingId == null) {
                        continue;
                    }
                    BackupOfferingVO offering = backupOfferingDao.findById(vm.getBackupOfferingId());
                    if (offering == null) {
                        logger.debug("Skipping VM [{}] because backup offering [{}] was not found.", vm, backupOfferingId);
                        continue;
                    }
                    if (!backupProvider.getName().equalsIgnoreCase(offering.getProvider())) {
                        continue;
                    }
                    syncedVmCount++;
                    logger.debug(String.format("Trying to sync backups of VM [%s] using backup provider [%s].", vm, backupProvider.getName()));
                    // Sync out-of-band backups
                    syncBackups(backupProvider, vm);
                    backupProvider.syncBackups(vm);
                } catch (final Exception e) {
                    logger.error("Failed to sync backup usage metrics and out-of-band backups of VM [{}] due to: [{}].", vm, e.getMessage(), e);
                }
            }
            if (syncedVmCount == 0) {
                logger.debug("No VMs assigned to backup provider [{}] in zone [{}] for out-of-band backup sync.",
                        backupProvider.getName(), dataCenter.getId());
            }
        }

        private void updateBackupUsageRecords(final BackupProvider backupProvider, DataCenter dataCenter) {
            List<Long> vmIdsWithBackups = backupDao.listVmIdsWithBackupsInZone(dataCenter.getId());
            List<VMInstanceVO> vmsWithBackups;
            if (vmIdsWithBackups.size() == 0) {
                vmsWithBackups = new ArrayList<>();
            } else {
                vmsWithBackups = vmInstanceDao.listByIdsIncludingRemoved(vmIdsWithBackups);
            }
            List<VMInstanceVO> vmsWithBackupOffering = vmInstanceDao.listByZoneAndBackupOffering(dataCenter.getId(), null); //should return including removed
            Set<VMInstanceVO> vms =  Stream.concat(vmsWithBackups.stream(), vmsWithBackupOffering.stream()) .collect(Collectors.toSet());

            for (final VirtualMachine vm : vms) {

                Map<Long, Pair<Long, Long>> backupOfferingToSizeMap = new HashMap<>();
                List<Backup> backups = backupDao.listByVmId(null, vm.getId());
                if (backups.isEmpty() && vm.getBackupOfferingId() != null) {
                    backupOfferingToSizeMap.put(vm.getBackupOfferingId(), new Pair<>(0L, 0L));
                }
                for (final Backup backup: backups) {
                    Long backupSize = 0L;
                    Long backupProtectedSize = 0L;
                    if (Objects.nonNull(backup.getSize())) {
                        backupSize = backup.getSize();
                    }
                    if (Objects.nonNull(backup.getProtectedSize())) {
                        backupProtectedSize = backup.getProtectedSize();
                    }
                    Long offeringId = backup.getBackupOfferingId();
                    if (backupOfferingToSizeMap.containsKey(offeringId)) {
                        Pair<Long, Long> sizes = backupOfferingToSizeMap.get(offeringId);
                        sizes.set(sizes.first() + backupSize, sizes.second() + backupProtectedSize);
                    } else {
                        backupOfferingToSizeMap.put(offeringId, new Pair<>(backupSize, backupProtectedSize));
                    }
                }

                for (final Map.Entry<Long, Pair<Long, Long>> entry : backupOfferingToSizeMap.entrySet()) {
                    Long offeringId = entry.getKey();
                    Pair<Long, Long> sizes = entry.getValue();
                    Long backupSize = sizes.first();
                    Long protectedSize = sizes.second();
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_BACKUP_USAGE_METRIC, vm.getAccountId(),
                            vm.getDataCenterId(), vm.getId(), "Backup-" + vm.getHostName() + "-" + vm.getUuid(),
                            offeringId, null, backupSize, protectedSize,
                            Backup.class.getSimpleName(), vm.getUuid());
                }
            }
        }

        private Backup checkAndUpdateIfBackupEntryExistsForRestorePoint(Backup.RestorePoint restorePoint, List<Backup> backupsInDb, VirtualMachine vm) {
            for (final Backup backupInDb : backupsInDb) {
                logger.debug(String.format("Checking if Backup %s with external ID %s for VM %s is valid", backupsInDb, backupInDb.getName(), vm));
                if (restorePoint.getId().equals(backupInDb.getExternalId())) {
                    logger.debug(String.format("Found Backup %s in both Database and Provider", backupInDb));
                    if (restorePoint.getDataSize() != null && restorePoint.getBackupSize() != null) {
                        logger.debug(String.format("Update backup [%s] from [size: %s, protected size: %s] to [size: %s, protected size: %s].",
                                backupInDb, backupInDb.getSize(), backupInDb.getProtectedSize(), restorePoint.getBackupSize(), restorePoint.getDataSize()));

                        resourceLimitMgr.decrementResourceCount(backupInDb.getAccountId(), Resource.ResourceType.backup_storage, backupInDb.getSize());
                        ((BackupVO) backupInDb).setSize(restorePoint.getBackupSize());
                        ((BackupVO) backupInDb).setProtectedSize(restorePoint.getDataSize());
                        resourceLimitMgr.incrementResourceCount(backupInDb.getAccountId(), Resource.ResourceType.backup_storage, backupInDb.getSize());

                        backupDao.update(backupInDb.getId(), ((BackupVO) backupInDb));
                    }
                    return backupInDb;
                }
            }
            return null;
        }

        private void processRemoveList(List<Long> removeList, VirtualMachine vm) {
            for (final Long backupIdToRemove : removeList) {
                Backup backup = backupDao.findById(backupIdToRemove);
                if (backup == null) {
                    logger.warn("Skipping removal of backup [ID: {}] during sync because it was not found.", backupIdToRemove);
                    continue;
                }
                if (backup != null && Backup.Status.BackingUp.equals(backup.getStatus())) {
                    final BackupOffering offering = backupOfferingDao.findById(backup.getBackupOfferingId());
                    if (offering != null && BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) {
                        logger.debug("Skipping removal of NetBackup backup [{}] for VM [{}] because it is still BackingUp.",
                                backup.getId(), vm.getInstanceName());
                        continue;
                    }
                }
                logger.warn(String.format("Removing backup with ID: [%s].", backupIdToRemove));
                resourceLimitMgr.decrementResourceCount(backup.getAccountId(), Resource.ResourceType.backup);
                resourceLimitMgr.decrementResourceCount(backup.getAccountId(), Resource.ResourceType.backup_storage, backup.getSize());
                boolean result = backupDao.remove(backupIdToRemove);
                if (result) {
                    checkAndGenerateUsageForLastBackupDeletedAfterOfferingRemove(vm, backup);
                } else {
                    logger.error("Failed to remove backup db entry ith ID: {} during sync backups", backupIdToRemove);
                }
            }
        }

        private void syncBackups(BackupProvider backupProvider, VirtualMachine vm) {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    final List<Backup> backupsInDb = backupDao.listByVmId(null, vm.getId());
                    List<Backup.RestorePoint> restorePoints = backupProvider.listRestorePoints(vm);
                    if (restorePoints == null) {
                        return;
                    }

                    final List<Long> removeList = backupsInDb.stream().map(InternalIdentity::getId).collect(Collectors.toList());
                    for (final Backup.RestorePoint restorePoint : restorePoints) {
                        if (!(restorePoint.getId() == null || restorePoint.getType() == null || restorePoint.getCreated() == null)) {
                            Backup existingBackupEntry = checkAndUpdateIfBackupEntryExistsForRestorePoint(restorePoint, backupsInDb, vm);
                            if (existingBackupEntry != null) {
                                removeList.remove(existingBackupEntry.getId());
                                continue;
                            }
                        }

                        Backup backup = backupProvider.createNewBackupEntryForRestorePoint(restorePoint, vm);
                        if (backup != null) {
                            logger.warn("Added backup found in provider [" + backup + "]");
                            resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup);
                            resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup_storage, backup.getSize());

                            logger.debug(String.format("Creating a new entry in backups: [id: %s, uuid: %s, vm_id: %s, external_id: %s, type: %s, date: %s, backup_offering_id: %s, account_id: %s, "
                                            + "domain_id: %s, zone_id: %s].", backup.getId(), backup.getUuid(), backup.getVmId(), backup.getExternalId(), backup.getType(), backup.getDate(),
                                    backup.getBackupOfferingId(), backup.getAccountId(), backup.getDomainId(), backup.getZoneId()));

                            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_VM_BACKUP_CREATE,
                                    String.format("Created backup %s for VM ID: %s", backup.getUuid(), vm.getUuid()),
                                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
                        }
                    }
                    processRemoveList(removeList, vm);
                }
            });
        }

        @Override
        public Long getDelay() {
            return BackupSyncPollingInterval.value() * 1000L;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_EDIT, eventDescription = "updating backup offering")
    public BackupOffering updateBackupOffering(UpdateBackupOfferingCmd updateBackupOfferingCmd) {
        Long id = updateBackupOfferingCmd.getId();
        String name = updateBackupOfferingCmd.getName();
        String description = updateBackupOfferingCmd.getDescription();
        Boolean allowUserDrivenBackups = updateBackupOfferingCmd.getAllowUserDrivenBackups();
        String retentionPeriod = updateBackupOfferingCmd.getRetentionPeriod();
        List<Long> domainIds = updateBackupOfferingCmd.getDomainIds();

        BackupOfferingVO backupOfferingVO = backupOfferingDao.findById(id);
        if (backupOfferingVO == null) {
            throw new InvalidParameterValueException(String.format("Unable to find Backup Offering with id: [%s].", id));
        }
        String externalId = backupOfferingVO.getExternalId();
        Long zoneId = backupOfferingVO.getZoneId();
        String providerName = backupOfferingVO.getProvider();

        accountManager.checkAccess(CallContext.current().getCallingAccount(), backupOfferingVO);

        logger.debug("Trying to update Backup Offering {} to {}.",
                ReflectionToStringBuilderUtils.reflectOnlySelectedFields(backupOfferingVO, "uuid", "name", "description", "userDrivenBackupAllowed", "retentionPeriod"),
                ReflectionToStringBuilderUtils.reflectOnlySelectedFields(updateBackupOfferingCmd, "name", "description", "allowUserDrivenBackups", "retentionPeriod"));

        BackupOfferingVO offering = backupOfferingDao.createForUpdate(id);
        List<String> fields = new ArrayList<>();
        if (name != null) {
            offering.setName(name);
            fields.add("name: " + name);
        }

        if (description != null) {
            offering.setDescription(description);
            fields.add("description: " + description);
        }

        if (allowUserDrivenBackups != null) {
            offering.setUserDrivenBackupAllowed(allowUserDrivenBackups);
            fields.add("allowUserDrivenBackups: " + allowUserDrivenBackups);
        }

        if (CollectionUtils.isNotEmpty(domainIds)) {
            for (final Long domainId: domainIds) {
                if (domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }
        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);
        Collections.sort(filteredDomainIds);

        if (retentionPeriod != null) {
            final BackupProvider provider = getBackupProvider(providerName);
            if (!provider.supportsRetentionPlanUpdate()) {
                throw new CloudRuntimeException("Failed to update backup offering, Because the backup offering provider is not set to commvault.");
            }
            boolean result = provider.updateBackupPlan(zoneId, retentionPeriod, externalId);
            if (!result) {
                throw new CloudRuntimeException("Failed to update plan retention period.");
            }
            offering.setRetentionPeriod(retentionPeriod);
            fields.add("retentionPeriod: " + retentionPeriod);
        }

        boolean success = backupOfferingDao.update(id, offering);
        if (!success) {
            logger.warn(String.format("Couldn't update Backup offering (%s) with [%s].", backupOfferingVO, String.join(", ", fields)));
        }

        if (success || fields.isEmpty()) {
            List<Long> existingDomainIds = backupOfferingDetailsDao.findDomainIds(id);
            Collections.sort(existingDomainIds);
            updateBackupOfferingDomainDetails(id, filteredDomainIds, existingDomainIds);
        }

        BackupOfferingVO response = backupOfferingDao.findById(id);
        CallContext.current().setEventDetails(String.format("Backup Offering updated [%s].",
                ReflectionToStringBuilderUtils.reflectOnlySelectedFields(response, "id", "name", "description", "userDrivenBackupAllowed", "externalId")));
        return response;
    }

    private void updateBackupOfferingDomainDetails(Long id, List<Long> filteredDomainIds, List<Long> existingDomainIds) {
        if (existingDomainIds == null) {
            existingDomainIds = new ArrayList<>();
        }

        if(!filteredDomainIds.equals(existingDomainIds)) {
            backupOfferingDetailsDao.updateBackupOfferingDomainIdsDetail(id, filteredDomainIds);
        }
    }

    Map<String, String> getDetailsFromBackupDetails(Long backupId) {
        Map<String, String> details = backupDetailsDao.listDetailsKeyPairs(backupId, true);
        if (details == null) {
            return null;
        }
        if (details.containsKey(ApiConstants.TEMPLATE_ID)) {
            VirtualMachineTemplate template = vmTemplateDao.findByUuid(details.get(ApiConstants.TEMPLATE_ID));
            if (template != null) {
                details.put(ApiConstants.TEMPLATE_NAME, template.getName());
                details.put(ApiConstants.IS_ISO, String.valueOf(template.getFormat().equals(Storage.ImageFormat.ISO)));
            }
        }
        if (details.containsKey(ApiConstants.SERVICE_OFFERING_ID)) {
            ServiceOffering serviceOffering = serviceOfferingDao.findByUuid(details.get(ApiConstants.SERVICE_OFFERING_ID));
            if (serviceOffering != null) {
                details.put(ApiConstants.SERVICE_OFFERING_NAME, serviceOffering.getName());
            }
        }
        if (details.containsKey(ApiConstants.NICS)) {
            Type type = new TypeToken<List<Map<String, String>>>() {}.getType();
            List<Map<String, String>> nics = new Gson().fromJson(details.get(ApiConstants.NICS), type);

            for (Map<String, String> nic : nics) {
                String networkUuid = nic.get(ApiConstants.NETWORK_ID);
                if (networkUuid != null) {
                    Network network = networkDao.findByUuid(networkUuid);
                    if (network != null) {
                        nic.put(ApiConstants.NETWORK_NAME, network.getName());
                    }
                }
            }
            details.put(ApiConstants.NICS, new Gson().toJson(nics));
        }
        return details;
    }

    @Override
    public BackupResponse createBackupResponse(Backup backup, Boolean listVmDetails) {
        VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        AccountVO account = accountDao.findByIdIncludingRemoved(backup.getAccountId());
        DomainVO domain = domainDao.findByIdIncludingRemoved(backup.getDomainId());
        DataCenterVO zone = dataCenterDao.findByIdIncludingRemoved(backup.getZoneId());
        Long offeringId = backup.getBackupOfferingId();
        BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(offeringId);

        BackupResponse response = new BackupResponse();
        response.setId(backup.getUuid());
        response.setName(backup.getName());
        response.setDescription(backup.getDescription());
        if (vm != null) {
            response.setVmName(vm.getHostName());
            response.setVmId(vm.getUuid());
            if (vm.getBackupOfferingId() == null || vm.getBackupOfferingId() != backup.getBackupOfferingId()) {
                response.setVmOfferingRemoved(true);
            }
        }
        if (vm == null || VirtualMachine.State.Expunging.equals(vm.getState())) {
            response.setVmExpunged(true);
        }
        response.setExternalId(backup.getExternalId());
        response.setType(backup.getType());
        response.setDate(backup.getDate());
        response.setSize(backup.getSize());
        response.setProtectedSize(backup.getProtectedSize());
        response.setStatus(backup.getStatus());
        response.setIntervalType(offering != null && BackupProviderNameUtils.isNetBackupFamily(offering.getProvider()) ? "EXTERNAL" : "MANUAL");
        if (backup.getBackupScheduleId() != null) {
            BackupScheduleVO scheduleVO = backupScheduleDao.findById(backup.getBackupScheduleId());
            if (scheduleVO != null) {
                response.setIntervalType(scheduleVO.getScheduleType().toString());
            }
        }
        // ACS 4.20: For backups taken prior this release the backup.backed_volumes column would be empty hence use vm_instance.backup_volumes
        String backedUpVolumes = "";
        if (Objects.isNull(backup.getBackedUpVolumes())) {
            if (vm != null) {
                backedUpVolumes = new Gson().toJson(vm.getBackupVolumeList().toArray(), Backup.VolumeInfo[].class);
            }
        } else {
            backedUpVolumes = new Gson().toJson(backup.getBackedUpVolumes().toArray(), Backup.VolumeInfo[].class);
        }
        response.setVolumes(backedUpVolumes);
        if (offering != null) {
            response.setBackupOfferingId(offering.getUuid());
            response.setBackupOffering(offering.getName());
            response.setProvider(offering.getProvider());
        } else {
            response.setVmOfferingRemoved(true);
        }
        if (account != null) {
            response.setAccountId(account.getUuid());
            response.setAccount(account.getAccountName());
        }
        if (domain != null) {
            response.setDomainId(domain.getUuid());
            response.setDomain(domain.getName());
        }
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZone(zone.getName());
        }

        if (Boolean.TRUE.equals(listVmDetails)) {
            Map<String, String> vmDetails = new HashMap<>();
            if (vm != null) {
                vmDetails.put(ApiConstants.HYPERVISOR, vm.getHypervisorType().toString());
            }
            Map<String, String> details = getDetailsFromBackupDetails(backup.getId());
            vmDetails.putAll(details);
            response.setVmDetails(vmDetails);
        }

        response.setObjectName("backup");
        return response;
    }

    @Override
    public CapacityVO getBackupStorageUsedStats(Long zoneId) {
        if (isDisabled(zoneId)) {
            return new CapacityVO(null, zoneId, null, null, 0L, 0L, Capacity.CAPACITY_TYPE_BACKUP_STORAGE);
        }
        long totalUsed = 0L;
        long totalCapacity = 0L;
        final List<BackupProvider> providers = getBackupProvidersForZone(zoneId);
        for (BackupProvider backupProvider : providers) {
            Pair<Long, Long> backupUsage = backupProvider.getBackupStorageStats(zoneId);
            if (backupUsage != null) {
                Long used = backupUsage.first();
                Long capacity = backupUsage.second();
                if (used != null) totalUsed += used;
                if (capacity != null) totalCapacity += capacity;
            }
        }
        return new CapacityVO(null, zoneId, null, null, totalUsed, totalCapacity, Capacity.CAPACITY_TYPE_BACKUP_STORAGE);
    }

    @Override
    public void checkAndRemoveBackupOfferingBeforeExpunge(VirtualMachine vm) {
        if (vm.getBackupOfferingId() == null) {
            return;
        }
        List<Backup> backupsForVm = backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), vm.getBackupOfferingId());
        if (CollectionUtils.isEmpty(backupsForVm)) {
            removeVMFromBackupOffering(vm.getId(), true);
        } else {
            throw new CloudRuntimeException(String.format("This Instance [uuid: %s, name: %s] has a "
                            + "Backup Offering [id: %s, external id: %s] with %s backups. Please, remove the backup offering "
                            + "before proceeding to VM exclusion!", vm.getUuid(), vm.getInstanceName(), vm.getBackupOfferingId(),
                    vm.getBackupExternalId(), backupsForVm.size()));
        }
    }
}
