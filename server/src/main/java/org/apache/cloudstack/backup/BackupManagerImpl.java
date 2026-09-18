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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.cloud.host.Host;
import com.cloud.storage.VolumeApiService;
import com.cloud.utils.exception.BackupProviderException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachineManager;
import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.vm.VmDiskInfo;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.EnumUtils;
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
import org.apache.cloudstack.api.command.user.backup.CancelBackupCmd;
import org.apache.cloudstack.api.command.user.backup.CreateAblestackVeeamBackupCmd;
import org.apache.cloudstack.api.command.user.backup.UpdateAblestackVeeamBackupCmd;
import org.apache.cloudstack.api.command.user.backup.SyncAblestackVeeamBackupsCmd;
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
import org.apache.cloudstack.api.command.user.backup.DownloadValidationScreenshotCmd;
import org.apache.cloudstack.api.command.user.backup.FinishBackupChainCmd;
import org.apache.cloudstack.api.command.user.backup.ListBackupServiceJobsCmd;
import org.apache.cloudstack.api.command.user.backup.GetBackupJobStatusCmd;
import org.apache.cloudstack.api.command.user.backup.GetBackupRestoreJobStatusCmd;
import org.apache.cloudstack.api.command.user.backup.ListBackupOfferingsCmd;
import org.apache.cloudstack.api.command.user.backup.ListBackupScheduleCmd;
import org.apache.cloudstack.api.command.user.backup.ListBackupsCmd;
import org.apache.cloudstack.api.command.user.backup.ListNetBackupBackupCandidatesCmd;
import org.apache.cloudstack.api.command.user.backup.PrepareNetBackupRestoreCmd;
import org.apache.cloudstack.api.command.user.backup.RemoveVirtualMachineFromBackupOfferingCmd;
import org.apache.cloudstack.api.command.user.backup.RestoreBackupCmd;
import org.apache.cloudstack.api.command.user.backup.RestoreNetBackupCmd;
import org.apache.cloudstack.api.command.user.backup.RestoreVolumeFromBackupAndAttachToVMCmd;
import org.apache.cloudstack.api.command.user.backup.UpdateBackupJobBandwidthCmd;
import org.apache.cloudstack.api.command.user.backup.UpdateBackupScheduleCmd;
import org.apache.cloudstack.api.command.user.backup.CreateBackupOfferingCmd;
import org.apache.cloudstack.api.command.user.backup.repository.AddBackupRepositoryCmd;
import org.apache.cloudstack.api.command.user.backup.repository.DeleteBackupRepositoryCmd;
import org.apache.cloudstack.api.command.user.backup.repository.ListBackupRepositoriesCmd;
import org.apache.cloudstack.api.command.user.backup.repository.UpdateBackupRepositoryCmd;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBackupCmd;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBxBackupCmd;
import org.apache.cloudstack.api.response.BackupJobStatusResponse;
import org.apache.cloudstack.api.response.BackupResponse;
import org.apache.cloudstack.api.response.NetBackupBackupCandidateResponse;
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
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.jobs.JobInfo;
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

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
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
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
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
import com.cloud.vm.VMInstanceDetailVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.google.gson.Gson;

public class BackupManagerImpl extends ManagerBase implements BackupManager {

    private static final String BACKUP_ENGINE_DETAIL_SUFFIX = ".backup.engine";

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
    private VolumeDetailsDao volumeDetailsDao;
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
    private AgentManager agentManager;
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
    private static final int COMMVAULT_BACKUP_AGENT_INSTALL_RETRY_ATTEMPTS = 10;
    private static final long COMMVAULT_BACKUP_AGENT_INSTALL_RETRY_INTERVAL_MS = TimeUnit.SECONDS.toMillis(30);
    private static final String FAST_CLONE_FLATTEN_STATUS = "clone.fast.flatten.status";
    private static final String FAST_CLONE_FLATTEN_PENDING = "pending";
    private static final String FAST_CLONE_FLATTEN_RUNNING = "running";
    private static final String RETENTION_CLEANUP_FAILED = "retention.cleanup.failed";
    private static final String RETENTION_CLEANUP_FAILED_AT = "retention.cleanup.failed.at";
    private static final String RETENTION_CLEANUP_SCHEDULE_ID = "retention.cleanup.schedule.id";
    private static final String RETENTION_CLEANUP_REASON = "retention.cleanup.reason";
    private static final String VEEAM_RESTORE_SESSION_ID = "veeam.restore.session.id";
    private static final String VEEAM_RESTORE_VM_UUID = "veeam.restore.vm.uuid";
    private static final String VEEAM_RESTORE_PHASE = "veeam.restore.phase";
    private static final String VEEAM_RESTORE_UPDATED_AT = "veeam.restore.updated.at";
    private static final String VEEAM_RESTORE_FAILURE_REASON = "veeam.restore.failure.reason";
    private static final long MIN_ACTIVE_JOB_SYNC_INTERVAL_SECONDS = 5L;
    private static final long RESTORE_JOB_START_GRACE_PERIOD_MS = TimeUnit.SECONDS.toMillis(60);

    private static Map<String, BackupProvider> backupProvidersMap = new HashMap<>();
    private static final String ABLESTACK_NETBACKUP_PROVIDER_NAME = "ablestack-netbackup";
    private static final int NETBACKUP_RESTORE_PATH_DISCOVERY_WINDOW_SECONDS = 1800;
    private static final int NETBACKUP_PREPARE_RESTORE_PATH_DISCOVERY_WINDOW_SECONDS = 120;
    private List<BackupProvider> backupProviders;
    private final List<PostRestoreMaintenanceTask> postRestoreMaintenanceTasks = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean activeBackupJobReconcileRunning = new AtomicBoolean(false);

    private static final List<Backup.Status> INVALID_BACKUP_STATUS = List.of(Backup.Status.Expunged, Backup.Status.Removed);

    public static final String KBOSS_BACKUP_PROVIDER = "kboss";



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

        for (BackupProvider provider : providers) {
            final boolean nameMatch = provider.getName().equalsIgnoreCase(providerName);
            if (nameMatch) {
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

        String providerName = cmd.getProvider();
        if (StringUtils.isEmpty(providerName)) {
            throw new CloudRuntimeException("Provider name must be specified");
        }

        List<BackupProvider> zoneProviders = getBackupProvidersForZone(cmd.getZoneId());
        BackupProvider matchedProvider = null;
        for (BackupProvider p : zoneProviders) {
            if (p.getName().equalsIgnoreCase(cmd.getProvider())) {
                matchedProvider = p;
                break;
            }
        }
        if (matchedProvider == null) {
            throw new CloudRuntimeException("Provider " + cmd.getProvider() + " is not enabled for zone " + cmd.getZoneId());
        }
        // Persist / use the actually loaded plugin name (veeam vs ablestack-veeam).
        providerName = matchedProvider.getName();

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

        final BackupProvider provider = matchedProvider;
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
    public BackupOffering createBackupOffering(CreateBackupOfferingCmd cmd) {
        validateBackupForZone(cmd.getZoneId());
        if (backupOfferingDao.findByName(cmd.getName(), cmd.getZoneId()) != null) {
            throw new CloudRuntimeException("A backup offering with the same name already exists in this zone");
        }

        if (CollectionUtils.isNotEmpty(cmd.getDomainIds())) {
            for (final Long domainId: cmd.getDomainIds()) {
                if (domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain ID");
                }
            }
        }

        List<Long> filteredDomainIds = cmd.getDomainIds() == null ? new ArrayList<>() : new ArrayList<>(cmd.getDomainIds());
        if (filteredDomainIds.size() > 1) {
            filteredDomainIds = domainHelper.filterChildSubDomains(filteredDomainIds);
        }

        final BackupProvider provider = getBackupProvidersForZone(cmd.getZoneId()).stream()
                .filter(candidate -> KBOSS_BACKUP_PROVIDER.equals(candidate.getName())).findFirst()
                .orElseThrow(() -> new InvalidParameterValueException("KBOSS backup provider is not enabled for this zone."));
        if (!KBOSS_BACKUP_PROVIDER.equals(provider.getName())) {
            throw new InvalidParameterValueException("Only KBOSS supports this API currently.");
        }

        if (!provider.isValidProviderOffering(cmd.getZoneId(), null)) {
            throw new CloudRuntimeException(String.format("Backup offering is not valid for provider [%s] in zone [%s]", provider, cmd.getZoneId()));
        }

        final BackupOfferingVO offering = new BackupOfferingVO(cmd.getZoneId(), provider.getName(), cmd.getName(), cmd.getDescription(), cmd.getUserDrivenBackups());

        final BackupOfferingVO savedOffering = backupOfferingDao.persist(offering);
        if (savedOffering == null) {
            throw new CloudRuntimeException("Unable to create backup offering: " + cmd.getName());
        }
        List<BackupOfferingDetailsVO> detailsVOList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(filteredDomainIds)) {
            for (Long domainId : filteredDomainIds) {
                detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
            }
        }
        if (cmd.isCompress()) {
            detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.COMPRESS, "true", true));
        }
        if (cmd.isValidate()) {
            detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.VALIDATE, "true", true));
        }
        if (cmd.isAllowExtractFile()) {
            detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.ALLOW_EXTRACT_FILE, "true", true));
        }
        if (cmd.isAllowQuickRestore()) {
            detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.ALLOW_QUICK_RESTORE, "true", true));
        }
        if (cmd.getBackupChainSize() != null) {
            detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.BACKUP_CHAIN_SIZE, cmd.getBackupChainSize().toString(), true));
        }
        if (cmd.getCompressionLibrary() != null) {
            detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.COMPRESSION_LIBRARY, cmd.getCompressionLibrary().name(), true));
        }
        if (cmd.getValidationSteps() != null) {
            detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.VALIDATION_STEPS, cmd.getValidationSteps(), true));
        }

        if (!detailsVOList.isEmpty()) {
            backupOfferingDetailsDao.saveDetails(detailsVOList);
        }
        logger.debug("Successfully created backup offering [{}] mapped to backup provider offering [{}].",cmd.getName(), savedOffering.getUuid());
        return savedOffering;
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
        updateBackupOfferingDetails(savedOffering, sourceOffering, filteredDomainIds);

        logger.debug("Successfully cloned backup offering '" + sourceOffering.getName() + "' (ID: " + cmd.getSourceOfferingId() + ") to '" + cmd.getName() + "' (ID: " + savedOffering.getId() + ")");
        return savedOffering;
    }

    private void updateBackupOfferingDetails(BackupOfferingVO savedOffering, BackupOfferingVO sourceOffering, List<Long> filteredDomainIds) {
        if (filteredDomainIds.size() > 1) {
            filteredDomainIds = domainHelper.filterChildSubDomains(filteredDomainIds);
        }

        List<BackupOfferingDetailsVO> detailsVOList = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(filteredDomainIds)) {
            for (Long domainId : filteredDomainIds) {
                if (domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
                detailsVOList.add(new BackupOfferingDetailsVO(savedOffering.getId(), ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
            }
        }

        List<BackupOfferingDetailsVO> details = backupOfferingDetailsDao.listDetails(sourceOffering.getId());
        details.removeIf(backupOfferingDetailsVO -> ApiConstants.DOMAIN_ID.equals(backupOfferingDetailsVO.getName()));
        details.forEach(detail -> detail.setResourceId(savedOffering.getId()));
        detailsVOList.addAll(details);

        if (!detailsVOList.isEmpty()) {
            backupOfferingDetailsDao.saveDetails(detailsVOList);
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
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_OFFERING_ASSIGN, eventDescription = "assign Instance to Backup Offering", async = true)
    public boolean assignVMToBackupOffering(Long vmId, Long offeringId) {
        final VMInstanceVO vm = findVmById(vmId);

        if (!Arrays.asList(VirtualMachine.State.Running, VirtualMachine.State.Stopped, VirtualMachine.State.Shutdown).contains(vm.getState())) {
            throw new CloudRuntimeException("Instance is not in running or stopped state");
        }

        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        if (vm.getBackupOfferingId() != null) {
            throw new CloudRuntimeException("Instance already is assigned to a backup offering, please remove the Instance from its previous offering");
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
                        throw new CloudRuntimeException("Failed to assign the Instance to the Backup Offering, please try removing the assignment and try again.");
                    }

                    if (!vmInstanceDao.update(vmId, vm)) {
                        backupProvider.removeVMFromBackupOffering(vm);
                        throw new CloudRuntimeException("Failed to update Instance assignment to the Backup Offering in the DB, please try again.");
                    }

                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_BACKUP_OFFERING_ASSIGN, vm.getAccountId(), vm.getDataCenterId(), vmId,
                            "Backup-" + vm.getHostName() + "-" + vm.getUuid(), vm.getBackupOfferingId(), null, null, Backup.class.getSimpleName(), vm.getUuid());
                    logger.debug(String.format("VM [%s] successfully added to Backup Offering [%s].", ReflectionToStringBuilderUtils.reflectOnlySelectedFields(vm,
                            "uuid", "instanceName", "backupOfferingId", "backupVolumes"), ReflectionToStringBuilderUtils.reflectOnlySelectedFields(offering,
                                    "uuid", "name", "externalId", "provider")));
                } catch (Exception e) {
                    String msg = String.format("Failed to assign Instance [%s] to the Backup Offering [%s], using provider [name: %s, class: %s], due to: [%s].",
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
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_OFFERING_REMOVE, eventDescription = "remove Instance from Backup Offering", async = true)
    public boolean removeVMFromBackupOffering(final Long vmId, final boolean forced) {
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(vmId);
        if (vm == null) {
            throw new CloudRuntimeException(String.format("Can't find any Instance with ID: [%s].", vmId));
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
            String message = String.format("To remove Instance [id: %s, name: %s] from Backup Offering [id: %s, name: %s] using the provider [%s], please specify the "
                    + "forced:true option to allow the deletion of all jobs and backups for this Instance or remove the backups that this Instance has with the backup "
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
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_SCHEDULE_CONFIGURE, eventDescription = "configuring Instance Backup Schedule")
    public BackupSchedule configureBackupSchedule(CreateBackupScheduleCmd cmd) {
        final Long vmId = cmd.getVmId();
        final DateUtil.IntervalType intervalType = cmd.getIntervalType();
        final String scheduleString = cmd.getSchedule();
        final TimeZone timeZone = TimeZone.getTimeZone(cmd.getTimezone());
        boolean isolated = cmd.isIsolated();

        if (intervalType == null) {
            throw new CloudRuntimeException("Invalid interval type provided");
        }

        final VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        if (vm.getBackupOfferingId() == null) {
            throw new CloudRuntimeException("Cannot configure Backup Schedule for the Instance as it is not assigned any Backup Offering");
        }

        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null || !allowsUserDefinedBackupSchedule(offering)) {
            throw new CloudRuntimeException("The selected backup offering does not allow user-defined backup schedule");
        }

        final int maxBackups = validateAndGetDefaultBackupRetentionIfRequired(cmd.getMaxBackups(), offering, vm);

        if (isolated && !KBOSS_BACKUP_PROVIDER.equals(offering.getProvider())) {
            throw new InvalidParameterValueException("Isolated backups are only supported by KBOSS backup provider.");
        }

        if (!BackupProviderNameUtils.isNasFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isCommvaultFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isVeeamFamily(offering.getProvider()) &&
                !KBOSS_BACKUP_PROVIDER.equals(offering.getProvider()) &&
                cmd.getQuiesceVM() != null) {
            throw new InvalidParameterValueException("Quiesce VM option is supported only for NAS, Commvault, Ablestack Veeam, and KBOSS backup providers");
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
            return backupScheduleDao.persist(new BackupScheduleVO(vmId, intervalType, scheduleString, timezoneId, nextDateTime, maxBackups, cmd.getQuiesceVM(), vm.getAccountId(),
                    vm.getDomainId(), isolated));
        }

        schedule.setScheduleType((short) intervalType.ordinal());
        schedule.setSchedule(scheduleString);
        schedule.setTimezone(timezoneId);
        schedule.setScheduledTimestamp(nextDateTime);
        schedule.setMaxBackups(maxBackups);
        schedule.setQuiesceVM(cmd.getQuiesceVM());
        schedule.setIsolated(isolated);
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
        List<BackupSchedule> schedules = new ArrayList<>(result.first());
        schedules.sort(this::compareBackupSchedules);
        return schedules;
    }

    protected int compareBackupSchedules(BackupSchedule first, BackupSchedule second) {
        int comparison = Comparator.nullsLast(Comparator.<DateUtil.IntervalType>naturalOrder())
                .compare(first.getScheduleType(), second.getScheduleType());
        if (comparison != 0) {
            return comparison;
        }

        int[] firstSortValues = getScheduleSortValues(first);
        int[] secondSortValues = getScheduleSortValues(second);
        for (int index = 0; index < Math.min(firstSortValues.length, secondSortValues.length); index++) {
            comparison = Integer.compare(firstSortValues[index], secondSortValues[index]);
            if (comparison != 0) {
                return comparison;
            }
        }
        comparison = Integer.compare(firstSortValues.length, secondSortValues.length);
        return comparison != 0 ? comparison : Long.compare(first.getId(), second.getId());
    }

    protected int[] getScheduleSortValues(BackupSchedule backupSchedule) {
        String[] scheduleParts = StringUtils.split(backupSchedule.getSchedule(), ':');
        if (scheduleParts == null) {
            return new int[0];
        }

        try {
            int minute = Integer.parseInt(scheduleParts[0]);
            if (backupSchedule.getScheduleType() == DateUtil.IntervalType.HOURLY || scheduleParts.length == 1) {
                return new int[] { minute };
            }

            int hour = Integer.parseInt(scheduleParts[1]);
            if (backupSchedule.getScheduleType() == DateUtil.IntervalType.DAILY || scheduleParts.length == 2) {
                return new int[] { hour, minute };
            }

            int day = Integer.parseInt(scheduleParts[2]);
            return new int[] { day, hour, minute };
        } catch (NumberFormatException e) {
            logger.warn("Unable to sort backup schedule [{}] with value [{}]", backupSchedule.getId(), backupSchedule.getSchedule());
            return new int[0];
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_SCHEDULE_DELETE, eventDescription = "deleting Instance Backup Schedule")
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
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_CREATE, eventDescription = "creating Instance Backup", async = true)
    public boolean createBackup(CreateBackupCmd cmd, Object job) throws ResourceAllocationException {
        final long backupStartTime = System.currentTimeMillis();
        Long vmId = cmd.getVmId();
        Account caller = CallContext.current().getCallingAccount();
        final VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(caller, null, true, vm);

        if (vm.getBackupOfferingId() == null) {
            throw new CloudRuntimeException("Cannot create backup as the Instance doesn't have a Backup Offering assigned");
        }

        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("Instance Backup Offering not found");
        }

        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        if (backupProvider == null) {
            throw new CloudRuntimeException("Instance backup provider not found for the Offering");
        }

        Long backupScheduleId = getBackupScheduleId(job);
        boolean isScheduledBackup = backupScheduleId != null;

        if (!isScheduledBackup && isExternalPolicyScheduledProvider(offering.getProvider())) {
            throw new CloudRuntimeException(String.format("Ad-hoc user backup is not supported for %s backup provider", offering.getProvider()));
        }

        if (!offering.isUserDrivenBackupAllowed() && !allowsScheduledBackupExecution(offering, isScheduledBackup)) {
            throw new CloudRuntimeException("The assigned backup offering does not allow ad-hoc user backup");
        }

        if (!BackupProviderNameUtils.isNasFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isCommvaultFamily(offering.getProvider()) &&
                !BackupProviderNameUtils.isVeeamFamily(offering.getProvider()) &&
                !KBOSS_BACKUP_PROVIDER.equals(offering.getProvider()) &&
                cmd.getQuiesceVM() != null) {
            throw new InvalidParameterValueException("Quiesce VM option is supported only for NAS, Commvault, Ablestack Veeam, and KBOSS backup providers");
        }

        logger.info("Starting VM backup request [vmId: {}, vmUuid: {}, vmName: {}, provider: {}, offeringId: {}, scheduleId: {}, scheduled: {}]",
                vm.getId(), vm.getUuid(), vm.getInstanceName(), offering.getProvider(), offering.getId(), backupScheduleId, isScheduledBackup);
        checkNoActiveFastCloneFlattenForBackup(vmId);
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
            try {
                deleteOldestBackupFromScheduleIfRequired(vmId, backupScheduleId);
            } catch (RuntimeException e) {
                logger.warn("Failed to apply backup retention cleanup after creating scheduled backup for VM [ID: {}], schedule [ID: {}]. " +
                        "The backup creation flow will not be failed by this cleanup error.", vmId, backupScheduleId, e);
            }
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

        // Seed import must use standalone ablestack-veeam (not veeam/NAS-hybrid which requires a repository).
        final BackupProvider backupProvider = getAblestackVeeamBackupProvider(offering.getProvider());

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
        return backupProvider.listCatalogRestorePoints(vm);
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
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_CREATE, eventDescription = "creating Ablestack Veeam backup", async = true)
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
                cmd.getName(), cmd.getIntervalType(), cmd.getVeeamJobName());
        return true;
    }

    @Override
    public boolean updateAblestackVeeamBackup(final UpdateAblestackVeeamBackupCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        final BackupVO backup = backupDao.findById(cmd.getId());
        if (backup == null) {
            throw new CloudRuntimeException(String.format("Backup [%s] was not found", cmd.getId()));
        }
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm != null) {
            accountManager.checkAccess(caller, null, true, vm);
            validateBackupForZone(vm.getDataCenterId());
        }
        final BackupOfferingVO offering = backupOfferingDao.findById(backup.getBackupOfferingId());
        if (offering == null || !BackupProviderNameUtils.isVeeamFamily(offering.getProvider())) {
            throw new CloudRuntimeException(String.format("Backup [%s] is not an ablestack-veeam backup", backup.getUuid()));
        }
        if (StringUtils.isNotBlank(cmd.getVeeamRestorePointId())) {
            backupDetailsDao.removeDetail(backup.getId(), "ablestack.veeam.restore.point.id");
            backupDetailsDao.addDetail(backup.getId(), "ablestack.veeam.restore.point.id",
                    cmd.getVeeamRestorePointId().trim(), false);
        }
        if (StringUtils.isNotBlank(cmd.getVeeamJobName())) {
            backupDetailsDao.removeDetail(backup.getId(), "ablestack.veeam.job.name");
            backupDetailsDao.addDetail(backup.getId(), "ablestack.veeam.job.name",
                    cmd.getVeeamJobName().trim(), false);
        }
        logger.info("Updated Ablestack Veeam backup [{}] metadata restorePointId=[{}] jobName=[{}]",
                backup.getUuid(), cmd.getVeeamRestorePointId(), cmd.getVeeamJobName());
        return true;
    }

    @Override
    public boolean syncAblestackVeeamBackups(final SyncAblestackVeeamBackupsCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        final VMInstanceVO vm = vmInstanceDao.findById(cmd.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException(String.format("VM [%s] was not found", cmd.getVmId()));
        }
        accountManager.checkAccess(caller, null, true, vm);
        validateBackupForZone(vm.getDataCenterId());
        if (vm.getBackupOfferingId() == null) {
            throw new CloudRuntimeException(String.format("VM [%s] is not assigned to a backup offering", vm.getUuid()));
        }
        final BackupOfferingVO offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null || !BackupProviderNameUtils.isVeeamFamily(offering.getProvider())) {
            throw new CloudRuntimeException(String.format("VM [%s] is not assigned to an Ablestack Veeam backup offering",
                    vm.getUuid()));
        }
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        logger.info("Syncing Ablestack Veeam backups for VM [{}] via provider [{}]",
                vm.getInstanceName(), offering.getProvider());
        backupProvider.syncBackups(vm);
        return true;
    }

    private static final String ABLESTACK_VEEAM_INTERVAL_TYPE_DETAIL = "ablestack.veeam.interval.type";

    private void createCheckedBackupVeeam(final VMInstanceVO vm, final Long vmId, final BackupProvider backupProvider,
            final Boolean quiesceVM, final Long backupSize, final Account owner, final Long backupScheduleId,
            final String backupName, final String intervalType, final String veeamJobName)
            throws ResourceAllocationException {
        try (CheckedReservation backupReservation = new CheckedReservation(owner, Resource.ResourceType.backup,
                1L, reservationDao, resourceLimitMgr);
             CheckedReservation backupStorageReservation = new CheckedReservation(owner,
                     Resource.ResourceType.backup_storage, backupSize, reservationDao, resourceLimitMgr)) {

            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(),
                    EventTypes.EVENT_VM_BACKUP_CREATE, "creating Ablestack Veeam backup for VM ID:" + vm.getUuid(),
                    vmId, ApiCommandResourceType.VirtualMachine.toString(), true, 0);

            final Pair<Boolean, Backup> result = backupProvider.takeBackup(vm, quiesceVM, backupScheduleId, veeamJobName);
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
                // Veeam-server-triggered backups (no Mold schedule) always show EXTERNAL in UI.
                // Ignore host-script DAILY/HOURLY leftovers — those are Veeam Job schedules, not Mold schedules.
                final String normalizedInterval = backupScheduleId == null
                        ? "EXTERNAL"
                        : normalizeVeeamBackupIntervalType(intervalType);
                if (StringUtils.isNotBlank(normalizedInterval) && backupScheduleId == null) {
                    backupDetailsDao.removeDetail(vmBackup.getId(), ABLESTACK_VEEAM_INTERVAL_TYPE_DETAIL);
                    backupDetailsDao.addDetail(vmBackup.getId(), ABLESTACK_VEEAM_INTERVAL_TYPE_DETAIL, normalizedInterval, true);
                }
                if (StringUtils.isNotBlank(veeamJobName)) {
                    backupDetailsDao.removeDetail(vmBackup.getId(), "ablestack.veeam.job.name");
                    backupDetailsDao.addDetail(vmBackup.getId(), "ablestack.veeam.job.name", veeamJobName.trim(), false);
                }
                resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup);
                resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup_storage, backup.getSize());
            }
        }
    }

    private String normalizeVeeamBackupIntervalType(final String intervalType) {
        if (StringUtils.isBlank(intervalType)) {
            return null;
        }
        final String trimmed = intervalType.trim();
        if ("EXTERNAL".equalsIgnoreCase(trimmed)) {
            return "EXTERNAL";
        }
        if ("MANUAL".equalsIgnoreCase(trimmed)) {
            return "MANUAL";
        }
        final DateUtil.IntervalType parsed = DateUtil.IntervalType.getIntervalType(trimmed);
        if (parsed != null) {
            return parsed.name();
        }
        logger.warn("Ignoring unsupported Ablestack Veeam backup interval type [{}]", intervalType);
        return null;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_RESTORE, eventDescription = "restoring VM from Ablestack Veeam backup", async = true)
    public boolean restoreAblestackVeeamBackup(final Long backupId, final String sessionId) {
        BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            backup = backupDao.findByIdIncludingRemoved(backupId);
            if (backup != null && backup.getRemoved() != null) {
                throw new CloudRuntimeException(String.format(
                        "Backup %s (%s) was removed and cannot be restored. Use an active backup for this VM.",
                        backup.getUuid(), backupId));
            }
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }
        final BackupOffering backupOffering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (backupOffering == null || !BackupProviderNameUtils.isVeeamFamily(backupOffering.getProvider())) {
            throw new CloudRuntimeException("Backup is not from an ablestack-veeam offering");
        }
        if (StringUtils.isBlank(sessionId)) {
            return restoreBackup(backupId, false, null);
        }

        backupDao.loadDetails(backup);
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm == null || VirtualMachine.State.Expunging.equals(vm.getState())) {
            throw new CloudRuntimeException("The Instance from which the Veeam backup was taken could not be found.");
        }
        final String existingSessionId = backup.getDetail(VEEAM_RESTORE_SESSION_ID);
        final String existingPhase = backup.getDetail(VEEAM_RESTORE_PHASE);
        if (StringUtils.equals(sessionId, existingSessionId) && "COMPLETED".equalsIgnoreCase(existingPhase)) {
            logger.info("Skipping already completed Veeam restore session [{}] for backup [{}].", sessionId, backup.getUuid());
            return true;
        }
        final BackupVO activeRestore = findActiveVeeamRestoreForVm(vm);
        if (activeRestore != null) {
            backupDao.loadDetails(activeRestore);
            final String activeSessionId = activeRestore.getDetail(VEEAM_RESTORE_SESSION_ID);
            if (StringUtils.equals(sessionId, activeSessionId)) {
                logger.info("Skipping duplicate Veeam restore session [{}] for VM [{}]; phase=[{}].",
                        sessionId, vm.getInstanceName(), activeRestore.getDetail(VEEAM_RESTORE_PHASE));
                return true;
            }
            throw new CloudRuntimeException(String.format(
                    "Veeam restore session [%s] is already active for VM [%s] in phase [%s].",
                    activeSessionId, vm.getInstanceName(), activeRestore.getDetail(VEEAM_RESTORE_PHASE)));
        }

        persistVeeamRestoreState(backup, vm, sessionId, "CLAIMED", null);
        try {
            final boolean restored = restoreBackup(backupId, false, null);
            persistVeeamRestoreState(backup, vm, sessionId, restored ? "COMPLETED" : "FAILED",
                    restored ? null : "Veeam restore returned false");
            return restored;
        } catch (RuntimeException e) {
            persistVeeamRestoreState(backup, vm, sessionId, "FAILED", e.getMessage());
            throw e;
        }
    }

    private BackupVO findActiveVeeamRestoreForVm(final VMInstanceVO vm) {
        if (vm == null || StringUtils.isBlank(vm.getUuid())) {
            return null;
        }
        return backupDetailsDao.findDetails(VEEAM_RESTORE_VM_UUID, vm.getUuid(), false).stream()
                .map(BackupDetailVO::getResourceId)
                .map(backupDao::findByIdIncludingRemoved)
                .filter(Objects::nonNull)
                .peek(backupDao::loadDetails)
                .filter(candidate -> isActiveExternalRestorePhase(candidate.getDetail(VEEAM_RESTORE_PHASE)))
                .findFirst()
                .orElse(null);
    }

    private boolean isActiveExternalRestorePhase(final String phase) {
        return StringUtils.isNotBlank(phase) && !isTerminalExternalRestorePhase(phase);
    }

    private boolean isTerminalExternalRestorePhase(final String phase) {
        return "COMPLETED".equalsIgnoreCase(phase) || "FAILED".equalsIgnoreCase(phase);
    }

    private void persistVeeamRestoreState(final BackupVO backup, final VMInstanceVO vm, final String sessionId,
            final String phase, final String failureReason) {
        if (backup == null || vm == null || StringUtils.isBlank(sessionId) || StringUtils.isBlank(phase)) {
            return;
        }
        persistBackupDetail(backup.getId(), VEEAM_RESTORE_SESSION_ID, sessionId);
        persistBackupDetail(backup.getId(), VEEAM_RESTORE_VM_UUID, vm.getUuid());
        persistBackupDetail(backup.getId(), VEEAM_RESTORE_PHASE, phase);
        persistBackupDetail(backup.getId(), VEEAM_RESTORE_UPDATED_AT, String.valueOf(System.currentTimeMillis()));
        if (StringUtils.isNotBlank(failureReason)) {
            persistBackupDetail(backup.getId(), VEEAM_RESTORE_FAILURE_REASON,
                    StringUtils.abbreviate(failureReason, 1024));
        } else {
            backupDetailsDao.removeDetail(backup.getId(), VEEAM_RESTORE_FAILURE_REASON);
        }
        backupDao.loadDetails(backup);
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

            Pair<Boolean, Backup> result = backupProvider.takeBackup(vm, cmd.getQuiesceVM(), cmd.isIsolated(), backupScheduleId);
            if (!result.first()) {
                throw new CloudRuntimeException("Failed to create Instance Backup");
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
                if (Backup.Status.BackingUp.equals(vmBackup.getStatus())) {
                    markResourceCountPending(vmBackup.getId());
                    logger.info("Backup [{}] for VM [{}] is still BackingUp after provider start. Resource counts will be updated when sync finalizes it.",
                            vmBackup.getUuid(), vm.getInstanceName());
                    return;
                }
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

    protected void checkNoActiveFastCloneFlattenForBackup(final Long vmId) {
        final List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
        if (CollectionUtils.isEmpty(volumes)) {
            return;
        }

        for (VolumeVO volume : volumes) {
            final VolumeDetailVO fastCloneFlattenStatus = volumeDetailsDao.findDetail(volume.getId(), FAST_CLONE_FLATTEN_STATUS);
            if (fastCloneFlattenStatus == null || StringUtils.isBlank(fastCloneFlattenStatus.getValue())) {
                continue;
            }
            if (FAST_CLONE_FLATTEN_PENDING.equalsIgnoreCase(fastCloneFlattenStatus.getValue()) ||
                    FAST_CLONE_FLATTEN_RUNNING.equalsIgnoreCase(fastCloneFlattenStatus.getValue())) {
                throw new CloudRuntimeException(String.format(
                        "Unable to create VM backup while SharedMountPoint clone flatten is %s for volume [%s]. Please retry after flatten completes.",
                        fastCloneFlattenStatus.getValue(), volume.getUuid()));
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_CREATE, eventDescription = "creating VM backup for NetBackup", async = true)
    public boolean createNetBackup(final CreateNetBackupCmd cmd) throws ResourceAllocationException {
        final Long vmId = cmd.getVmId();
        final Long backupScheduleId = cmd.getScheduleId();
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

        checkNoActiveFastCloneFlattenForBackup(vm.getId());
        final VMInstanceVO assignedVm = transactionAssignVMToBackupOffering(vm, netBackupOffering, backupProvider);
        if (assignedVm == null) {
            throw new CloudRuntimeException(String.format("Failed to assign existing NetBackup offering [%s] to VM [%s].",
                    netBackupOffering.getName(), vm.getInstanceName()));
        }

        final Account owner = accountManager.getAccount(vm.getAccountId());
        final Long backupSize = calculateBackupSize(vm.getId());

        validateNetBackupScheduleForVm(backupScheduleId, vm.getId());
        createCheckedBackup(cmd, owner, backupScheduleId != null, backupSize, vm, vm.getId(), backupProvider, backupScheduleId);
        return true;
    }

    @Override
    public List<NetBackupBackupCandidateResponse> listNetBackupBackupCandidates(final ListNetBackupBackupCandidatesCmd cmd) {
        final GlobalLock claimLock = GlobalLock.getInternLock("backup.netbackup.candidates");
        try {
            if (!claimLock.lock(5)) {
                throw new CloudRuntimeException("Unable to acquire NetBackup backup candidate lock");
            }
            try {
                return listNetBackupBackupCandidatesInternal(cmd);
            } finally {
                claimLock.unlock();
            }
        } finally {
            claimLock.releaseRef();
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_CANCEL, eventDescription = "cancelling VM backup", async = true)
    public boolean cancelBackup(final Long backupId) {
        final BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }
        if (!Backup.Status.BackingUp.equals(backup.getStatus())) {
            throw new CloudRuntimeException("Only BackingUp backups can be cancelled");
        }
        final VMInstanceVO vm = vmInstanceDao.findById(backup.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException("Instance " + backup.getVmId() + " does not exist");
        }
        validateBackupForZone(backup.getZoneId());
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        final BackupOffering offering = backupOfferingDao.findById(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException(String.format("Backup offering with ID [%s] does not exist.", backup.getBackupOfferingId()));
        }
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        if (backupProvider == null) {
            throw new CloudRuntimeException(String.format("Backup provider [%s] is not available.", offering.getProvider()));
        }
        final boolean cancelled = backupProvider.cancelBackup(vm, backup);
        if (!cancelled) {
            throw new CloudRuntimeException("Failed to cancel running VM backup");
        }
        backup.setStatus(Backup.Status.Canceled);
        backupDao.update(backup.getId(), backup);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL);
        cleanupBackupJobFiles(backup.getHostId(), backup.getUuid(), offering.getProvider());
        logger.info("Cancelled BackingUp backup [{}] for VM [{}] using provider [{}].",
                backup.getUuid(), vm.getInstanceName(), offering.getProvider());
        return true;
    }

    private List<NetBackupBackupCandidateResponse> listNetBackupBackupCandidatesInternal(final ListNetBackupBackupCandidatesCmd cmd) {
        final HostVO requestedHost = resolveNetBackupCandidateHost(cmd);
        final Date now = currentTimestamp != null ? currentTimestamp : new Date();
        final List<NetBackupBackupCandidateResponse> responses = new ArrayList<>();
        final List<BackupScheduleVO> schedules = backupScheduleDao.getSchedulesToExecute(now);
        for (final BackupScheduleVO backupSchedule : schedules) {
            final VMInstanceVO vm = vmInstanceDao.findById(backupSchedule.getVmId());
            if (!isNetBackupScheduleCandidate(vm, backupSchedule, requestedHost, cmd.getPolicyId())) {
                continue;
            }
            final Date dueTimestamp = backupSchedule.getScheduledTimestamp();
            final Date nextTimestamp = cmd.isClaim() ? scheduleNextBackupJob(backupSchedule, now) :
                    DateUtil.getNextRunTime(backupSchedule.getScheduleType(), backupSchedule.getSchedule(),
                            backupSchedule.getTimezone(), now);
            responses.add(createNetBackupCandidateResponse(vm, backupSchedule, requestedHost, cmd.getPolicyId(), dueTimestamp, nextTimestamp));
        }
        logger.info("Listed [{}] NetBackup backup candidates for host [{}] policy [{}] claim [{}].",
                responses.size(), requestedHost != null ? requestedHost.getName() : cmd.getHostName(), cmd.getPolicyId(), cmd.isClaim());
        return responses;
    }

    private HostVO resolveNetBackupCandidateHost(final ListNetBackupBackupCandidatesCmd cmd) {
        HostVO host = null;
        if (cmd.getHostId() != null) {
            host = hostDao.findById(cmd.getHostId());
        } else if (StringUtils.isNotBlank(cmd.getHostName())) {
            host = hostDao.findByName(cmd.getHostName());
        }
        if (host == null) {
            throw new CloudRuntimeException("NetBackup candidate host was not found");
        }
        return host;
    }

    private boolean isNetBackupScheduleCandidate(final VMInstanceVO vm, final BackupScheduleVO backupSchedule,
            final HostVO requestedHost, final String policyId) {
        if (vm == null || vm.getBackupOfferingId() == null || !VirtualMachine.State.Running.equals(vm.getState())) {
            return false;
        }
        if (!Objects.equals(vm.getHostId(), requestedHost.getId())) {
            return false;
        }
        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        if (offering == null || !BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())) {
            return false;
        }
        if (StringUtils.isNotBlank(policyId) && !StringUtils.equalsIgnoreCase(policyId, offering.getExternalId())
                && !StringUtils.equalsIgnoreCase(policyId, offering.getName())) {
            return false;
        }
        final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        if (backupProvider == null || isDisabled(vm.getDataCenterId())) {
            return false;
        }
        return !hasActiveBackingUpBackup(vm);
    }

    private boolean hasActiveBackingUpBackup(final VMInstanceVO vm) {
        return backupDao.listByVmIdAndOffering(vm.getDataCenterId(), vm.getId(), vm.getBackupOfferingId()).stream()
                .anyMatch(backup -> Backup.Status.BackingUp.equals(backup.getStatus()));
    }

    private NetBackupBackupCandidateResponse createNetBackupCandidateResponse(final VMInstanceVO vm,
            final BackupScheduleVO backupSchedule, final HostVO host, final String requestedPolicyId,
            final Date dueTimestamp, final Date nextTimestamp) {
        final BackupOffering offering = backupOfferingDao.findById(vm.getBackupOfferingId());
        final NetBackupBackupCandidateResponse response = new NetBackupBackupCandidateResponse();
        response.setVmId(vm.getUuid());
        response.setVmName(vm.getName());
        response.setInstanceName(vm.getInstanceName());
        response.setScheduleId(backupSchedule.getUuid());
        response.setSchedule(backupSchedule.getSchedule());
        response.setIntervalType(backupSchedule.getScheduleType());
        response.setScheduledTimestamp(dueTimestamp);
        response.setNextScheduledTimestamp(nextTimestamp);
        response.setQuiesceVM(backupSchedule.getQuiesceVM());
        response.setPolicyId(StringUtils.defaultIfBlank(requestedPolicyId, offering != null ? offering.getExternalId() : null));
        response.setHostId(host.getUuid());
        response.setHostName(host.getName());
        response.setObjectName("netbackupbackupcandidate");
        return response;
    }

    private void validateNetBackupScheduleForVm(final Long backupScheduleId, final Long vmId) {
        if (backupScheduleId == null) {
            return;
        }
        final BackupScheduleVO schedule = backupScheduleDao.findById(backupScheduleId);
        if (schedule == null || !Objects.equals(schedule.getVmId(), vmId)) {
            throw new CloudRuntimeException("Invalid NetBackup backup schedule for VM");
        }
    }

    @Override
    public boolean updateNetBackup(final UpdateNetBackupCmd cmd) {
        final Long vmId = cmd.getVmId();
        final Account caller = CallContext.current().getCallingAccount();

        final VMInstanceVO vm = findVmById(vmId);
        validateBackupForZone(vm.getDataCenterId());
        accountManager.checkAccess(caller, null, true, vm);
        final BackupVO backup = netBackupRestoreCoordinator.updateBackupMetadata(cmd, vm);
        if (backup != null && Backup.Status.BackedUp.equals(backup.getStatus())) {
            final HostVO backupJobHost = findBackupJobHost(backup, vm);
            cleanupBackupJobFiles(backupJobHost != null ? backupJobHost.getId() : null, backup.getUuid(), "NetBackup");
        }
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

            Pair<Boolean, Backup> result = backupProvider.takeNetBackup(vm, cmd.getPolicyId(), backupScheduleId);
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
                if (Backup.Status.BackingUp.equals(vmBackup.getStatus())) {
                    markResourceCountPending(vmBackup.getId());
                    logger.info("NetBackup backup [{}] for VM [{}] is still BackingUp after provider start. Resource counts will be updated when sync finalizes it.",
                            vmBackup.getUuid(), vm.getInstanceName());
                    return;
                }
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

        if (StringUtils.isBlank(jobParamsRaw) || !jobParamsRaw.contains(ApiConstants.SCHEDULE_ID)) {
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
                String reason = "Could not find a deletable leaf while removing an obsolete backup chain";
                logger.warn("{} for {}.", reason, cleanupTarget);
                markRetentionCleanupFailure(remainingBackups, backupScheduleId, reason);
                return false;
            }

            for (BackupVO backup : leafBackups) {
                try {
                    if (!deleteBackup(backup.getId(), false)) {
                        String reason = "deleteBackup returned false";
                        logger.warn("Failed to delete backup [ID: {}, UUID: {}] while deleting a chain for {}.", backup.getId(), backup.getUuid(), cleanupTarget);
                        markRetentionCleanupFailure(remainingBackups, backupScheduleId, reason);
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
                    markRetentionCleanupFailure(remainingBackups, backupScheduleId, e.getMessage());
                    return false;
                }
            }
        }

        logger.info("Deleted [{}] backups from an obsolete backup chain for {}.", deletedBackups, cleanupTarget);
        return true;
    }

    private void markRetentionCleanupFailure(List<BackupVO> backups, long backupScheduleId, String reason) {
        if (CollectionUtils.isEmpty(backups)) {
            return;
        }

        String failedAt = DateUtil.displayDateInTimezone(DateUtil.GMT_TIMEZONE, new Date());
        String scheduleId = backupScheduleId > 0 ? String.valueOf(backupScheduleId) : "";
        String cleanupFailureReason = StringUtils.abbreviate(StringUtils.defaultString(reason, "unknown"), 1024);

        for (BackupVO backup : backups) {
            try {
                backupDetailsDao.removeDetail(backup.getId(), RETENTION_CLEANUP_FAILED);
                backupDetailsDao.addDetail(backup.getId(), RETENTION_CLEANUP_FAILED, Boolean.TRUE.toString(), false);
                backupDetailsDao.removeDetail(backup.getId(), RETENTION_CLEANUP_FAILED_AT);
                backupDetailsDao.addDetail(backup.getId(), RETENTION_CLEANUP_FAILED_AT, failedAt, false);
                backupDetailsDao.removeDetail(backup.getId(), RETENTION_CLEANUP_SCHEDULE_ID);
                backupDetailsDao.addDetail(backup.getId(), RETENTION_CLEANUP_SCHEDULE_ID, scheduleId, false);
                backupDetailsDao.removeDetail(backup.getId(), RETENTION_CLEANUP_REASON);
                backupDetailsDao.addDetail(backup.getId(), RETENTION_CLEANUP_REASON, cleanupFailureReason, false);
            } catch (RuntimeException e) {
                logger.warn("Failed to mark retention cleanup failure details for backup [ID: {}, UUID: {}].",
                        backup.getId(), backup.getUuid(), e);
            }
        }
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

    private Backup.Status validateBackupStatus(final String backupStatus) {
        if (backupStatus == null) {
            return null;
        }

        Backup.Status status = EnumUtils.getEnumIgnoreCase(Backup.Status.class, backupStatus);
        if (status == null || INVALID_BACKUP_STATUS.contains(status)) {
            throw new InvalidParameterValueException(String.format("Invalid backup status: %s. Valid values are: " +
                    "Allocated, Queued, BackingUp, BackedUp, Error, Failed, Restoring.", backupStatus));
        }

        return status;
    }

    @Override
    public Pair<List<Backup>, Integer> listBackups(final ListBackupsCmd cmd) {
        final Long id = cmd.getId();
        final Long vmId = cmd.getVmId();
        final String name = cmd.getName();
        final Long zoneId = cmd.getZoneId();
        final Long backupOfferingId = cmd.getBackupOfferingId();
        final String backupOfferingName = cmd.getBackupOfferingName();
        final Backup.Status backupStatus = validateBackupStatus(cmd.getBackupStatus());
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
        // Tombstoned chain backups (Status.Hidden) are never shown to users; they exist only so the
        // incremental chain GC can sweep them once their last descendant is deleted.
        sb.and("statusNeq", sb.entity().getStatus(), SearchCriteria.Op.NEQ);
        sb.and("backupStatus", sb.entity().getStatus(), SearchCriteria.Op.EQ);

        if (StringUtils.isNotBlank(backupOfferingName)) {
            SearchBuilder<BackupOfferingVO> backupOfferingSearch = backupOfferingDao.createSearchBuilder();
            sb.join("backupOfferingSearch", backupOfferingSearch, sb.entity().getBackupOfferingId(), backupOfferingSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            sb.and("backupOfferingSearch", "backupOfferingName", backupOfferingSearch.entity().getName(), SearchCriteria.Op.LIKE);
        }

        if (keyword != null) {
            sb.and().op("keywordName", sb.entity().getName(), SearchCriteria.Op.LIKE);
            SearchBuilder<VMInstanceVO> vmSearch = vmInstanceDao.createSearchBuilder();
            sb.join("vmSearch", vmSearch, sb.entity().getVmId(), vmSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            sb.or("vmSearch", "keywordVmName", vmSearch.entity().getHostName(), SearchCriteria.Op.LIKE);
            sb.cp();
        }

        SearchCriteria<BackupVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sc.setParameters("statusNeq", Backup.Status.Hidden);

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

        if (StringUtils.isNotBlank(backupOfferingName)) {
            sc.setParameters("backupOfferingName", "%" + backupOfferingName + "%");
        }
        sc.setParametersIfNotNull("backupStatus", backupStatus);

        if (keyword != null) {
            String keywordMatch = "%" + keyword + "%";
            sc.setParameters("keywordName", keywordMatch);
            sc.setParameters("keywordVmName", keywordMatch);
        }

        Pair<List<BackupVO>, Integer> result = backupDao.searchAndCount(sc, searchFilter);
        return new Pair<>(new ArrayList<>(result.first()), result.second());
    }

    public boolean importRestoredVM(long zoneId, long domainId, long accountId, long userId,
                                    String vmInternalName, Hypervisor.HypervisorType hypervisorType, Backup backup, BackupOffering offering) {
        VirtualMachine vm = null;
        HypervisorGuru guru = hypervisorGuruManager.getGuru(hypervisorType);
        try {
            vm = guru.importVirtualMachineFromBackup(zoneId, domainId, accountId, userId, vmInternalName, backup, getBackupProvider(offering.getProvider()));
        } catch (final Exception e) {
            logger.error(String.format("Failed to import VM [vmInternalName: %s] from backup restoration [%s] with hypervisor [type: %s] due to: [%s].", vmInternalName,
                    ReflectionToStringBuilderUtils.reflectOnlySelectedFields(backup, "id", "uuid", "vmId", "externalId", "type"), hypervisorType, e.getMessage()), e);
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_BACKUP_RESTORE,
                    String.format("Failed to import Instance %s from Backup %s with hypervisor [type: %s]", vmInternalName, backup.getUuid(), hypervisorType),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
            throw new CloudRuntimeException("Error during Instance Backup restoration and import: " + e.getMessage());
        }
        if (vm == null) {
            String message = String.format("Failed to import restored VM %s  with hypervisor type %s using backup of VM ID %s",
                    vmInternalName, hypervisorType, backup.getVmId());
            logger.error(message);
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_BACKUP_RESTORE,
                    message, vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
        } else {
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_VM_BACKUP_RESTORE,
                    String.format("Restored Instance %s from Backup %s", vm.getUuid(), backup.getUuid()),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
        }
        return vm != null;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_RESTORE, eventDescription = "restoring Instance from Backup", async = true)
    public boolean restoreBackup(final Long backupId, boolean quickRestore, Long hostId) {
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

        Account callerAccount = CallContext.current().getCallingAccount();
        accountManager.checkAccess(callerAccount, null, true, vm);
        validateHostIdParameter(hostId, callerAccount);

        if (vm.getRemoved() == null && !vm.getState().equals(VirtualMachine.State.Stopped) &&
                !vm.getState().equals(VirtualMachine.State.Destroyed)) {
            throw new CloudRuntimeException("Existing Instance should be stopped before being restored from Backup");
        }

        logger.debug("Attempting to get backup offering from VM backup");
        BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("Failed to find backup offering of the VM backup.");
        }
        validateBackupVolumes(backup, vm, offering);
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
                netBackupRestoreCoordinator.persistRestoreContext(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier,
                        phase, null, backup.getExternalId());
            }

            initializeRestoreOperation(backup.getId(), offering, AblestackBackupFrameworkUtils.RESTORE_OPERATION_VM,
                    vm.getId(), null, null);
            final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
            if (backupProvider.supportsDetachedRestoreOrchestration()) {
                startDetachedVmRestore(backup, vm, backupProvider, backupDetailsInMessage, quickRestore, hostId);
                persistRestoreOperationPhase(backup.getId(), "RUNNING", "QUEUED", 10);
                return true;
            }
            tryRestoreVM(backup, vm, offering, backupDetailsInMessage, quickRestore, hostId);
            persistRestoreOperationPhase(backup.getId(), "RUNNING", "FINALIZING", 95);
            updateStates(vm, backupProvider, quickRestore);

            final boolean imported = importRestoredVM(vm.getDataCenterId(), vm.getDomainId(), vm.getAccountId(), vm.getUserId(),
                    vm.getInstanceName(), vm.getHypervisorType(), backup, offering);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier,
                        imported ? RestorePhase.COMPLETED : RestorePhase.FAILED);
            }
            if (imported) {
                persistRestoreOperationPhase(backup.getId(), "COMPLETED", "COMPLETED", 100);
                cleanupTrackedRestoreJobFilesAndDetails(backup, vm, backupProvider.getName());
            } else {
                persistRestoreOperationPhase(backup.getId(), "FAILED", "FINALIZATION_FAILED", 95);
            }
            return imported;
        } catch (RuntimeException e) {
            persistRestoreOperationPhase(backup.getId(), "FAILED", "FAILED", null);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreState(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, RestorePhase.FAILED);
            }
            throw e;
        }
    }

    private void startDetachedVmRestore(final BackupVO backup, final VMInstanceVO vm,
            final BackupProvider backupProvider, final String backupDetailsInMessage, final boolean quickRestore,
            final Long hostId) {
        try {
            updateVmState(vm, VirtualMachine.Event.RestoringRequested, VirtualMachine.State.Restoring);
            updateVolumeState(vm, Volume.Event.RestoreRequested, Volume.State.Restoring);
            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventTypes.EVENT_VM_BACKUP_RESTORE,
                    String.format("Restoring Instance %s from Backup %s", vm.getUuid(), backup.getUuid()),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(), true, 0);
            if (!backupProvider.startRestoreVMFromBackup(vm, backup, quickRestore, hostId)) {
                throw new CloudRuntimeException("Host-side restore job was not accepted");
            }
        } catch (Exception e) {
            logger.error("Failed to start detached restore for backup [{}]: {}", backupDetailsInMessage, e.getMessage(), e);
            updateVolumeState(vm, Volume.Event.RestoreFailed, Volume.State.Ready);
            updateVmState(vm, VirtualMachine.Event.RestoringFailed, VirtualMachine.State.Stopped);
            throw e instanceof CloudRuntimeException ? (CloudRuntimeException) e
                    : new CloudRuntimeException(String.format("Error restoring Instance from Backup [%s].", backupDetailsInMessage));
        }
    }

    private void validateHostIdParameter(Long hostId, Account callerAccount) {
        if (hostId != null && !accountService.isRootAdmin(callerAccount.getId())) {
            throw new PermissionDeniedException(String.format("Parameter %s can only be specified by a Root Admin", ApiConstants.HOST_ID));
        }
    }

    /**
     * Updates the VM and volume states.
     * If using quick restore, the states should already be set (the VM should be running).
     * Only KBOSS supports this parameter for now; will do nothing if the backup provider is KBOSS and quickRestore is true.
     * */
    private void updateStates(VMInstanceVO vm, BackupProvider backupProvider, boolean quickRestore) {
        if (KBOSS_BACKUP_PROVIDER.equals(backupProvider.getName()) && quickRestore) {
            return;
        }
        updateVolumeState(vm, Volume.Event.RestoreSucceeded, Volume.State.Ready);
        updateVmState(vm, VirtualMachine.Event.RestoringSuccess, VirtualMachine.State.Stopped);
    }

    protected void validateBackupVolumes(BackupVO backup, VMInstanceVO vm, BackupOffering offering) {
        BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        if (KBOSS_BACKUP_PROVIDER.equals(backupProvider.getName())) {
            return;
        }
        // This is done to handle historic backups if any with Veeam / Networker plugins
        List<Backup.VolumeInfo> backupVolumes = CollectionUtils.isEmpty(backup.getBackedUpVolumes()) ?
                vm.getBackupVolumeList() : backup.getBackedUpVolumes();
        List<VolumeVO> vmVolumes = volumeDao.findByInstance(vm.getId());
        if (vmVolumes.size() != backupVolumes.size()) {
            throw new CloudRuntimeException("Unable to restore Instance with the current Backup as the Backup has different number of disks to the Instance");
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

            netBackupRestoreCoordinator.persistRestoreContext(backup, vm, resolution.getRequestIdentifier(),
                    RestorePhase.PREPARED_PATH_VALIDATING, resolution.getPreparedRestoreHostName(), backup.getExternalId());

            if (isNetBackupFullBackup(backup)) {
                logger.info("Resolved NetBackup FULL restore request for VM [{}] using external ID [{}] and backup [{}].",
                        vm.getInstanceName(), resolution.getRequestIdentifier(), backup.getUuid());
                final BackupOffering offering = getBackupOfferingForRestore(vm, backup);
                final String backupDetailsInMessage = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(
                        backup, "uuid", "externalId", "vmId", "name");
                netBackupRestoreCoordinator.persistRestoreContext(backup, vm, resolution.getRequestIdentifier(),
                        RestorePhase.HOST_RESTORE_IN_PROGRESS, resolution.getPreparedRestoreHostName(), backup.getExternalId());
                if (resolution.getPreparedRestoreHostName() != null) {
                    tryRestorePreparedNetBackupVM(
                            backup, vm, offering, backupDetailsInMessage, resolution.getPreparedRestoreHostName());
                } else {
                    tryRestoreVM(backup, vm, offering, backupDetailsInMessage, false, null);
                }
                updateVolumeState(vm, Volume.Event.RestoreSucceeded, Volume.State.Ready);
                updateVmState(vm, VirtualMachine.Event.RestoringSuccess, VirtualMachine.State.Stopped);
                netBackupRestoreCoordinator.persistRestoreContext(backup, vm, resolution.getRequestIdentifier(),
                        RestorePhase.IMPORT_IN_PROGRESS, resolution.getPreparedRestoreHostName(), backup.getExternalId());
                final boolean imported = importRestoredVM(vm.getDataCenterId(), vm.getDomainId(), vm.getAccountId(), vm.getUserId(),
                        vm.getInstanceName(), vm.getHypervisorType(), backup, offering);
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
                netBackupRestoreCoordinator.persistRestoreContext(backup, vm, resolution.getRequestIdentifier(),
                        RestorePhase.HOST_RESTORE_IN_PROGRESS, resolution.getPreparedRestoreHostName(), backup.getExternalId());
                if (resolution.getPreparedRestoreHostName() != null) {
                    tryRestorePreparedNetBackupVM(
                            backup, vm, offering, backupDetailsInMessage, resolution.getPreparedRestoreHostName());
                } else {
                    tryRestoreVM(backup, vm, offering, backupDetailsInMessage, false, null);
                }
                updateVolumeState(vm, Volume.Event.RestoreSucceeded, Volume.State.Ready);
                updateVmState(vm, VirtualMachine.Event.RestoringSuccess, VirtualMachine.State.Stopped);
                netBackupRestoreCoordinator.persistRestoreContext(backup, vm, resolution.getRequestIdentifier(),
                        RestorePhase.IMPORT_IN_PROGRESS, resolution.getPreparedRestoreHostName(), backup.getExternalId());
                final boolean imported = importRestoredVM(vm.getDataCenterId(), vm.getDomainId(), vm.getAccountId(), vm.getUserId(),
                        vm.getInstanceName(), vm.getHypervisorType(), backup, offering);
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
            netBackupRestoreCoordinator.persistRestoreFailure(backup, vm, resolution.getRequestIdentifier(), e.getMessage());
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

        netBackupRestoreCoordinator.persistRestoreContext(backup, vm, resolution.getRequestIdentifier(),
                RestorePhase.PREPARED_PATH_VALIDATING, resolution.getPreparedRestoreHostName(), backup.getExternalId());
        try {
            netBackupRestoreCoordinator.validatePreparedRestorePath(backup, resolution, NETBACKUP_PREPARE_RESTORE_PATH_DISCOVERY_WINDOW_SECONDS);
        } catch (RuntimeException e) {
            netBackupRestoreCoordinator.persistRestoreFailure(backup, vm, resolution.getRequestIdentifier(), e.getMessage());
            cleanupPreparedNetBackupRestoreOnFailure(backup, vm, resolution, e);
            throw e;
        }

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

        netBackupRestoreCoordinator.persistRestoreContext(backup, vm, resolution.getRequestIdentifier(),
                RestorePhase.CLAIMED, resolution.getPreparedRestoreHostName(), backup.getExternalId());

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
    protected void tryRestoreVM(BackupVO backup, VMInstanceVO vm, BackupOffering offering, String backupDetailsInMessage, boolean quickRestore, Long hostId) {
        try {
            updateVmState(vm, VirtualMachine.Event.RestoringRequested, VirtualMachine.State.Restoring);
            updateVolumeState(vm, Volume.Event.RestoreRequested, Volume.State.Restoring);
            ActionEventUtils.onStartedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventTypes.EVENT_VM_BACKUP_RESTORE,
                    String.format("Restoring Instance %s from Backup %s", vm.getUuid(), backup.getUuid()),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),
                    true, 0);

            final BackupProvider backupProvider = getBackupProvider(offering.getProvider());
            if (!backupProvider.restoreVMFromBackup(vm, backup, quickRestore, hostId)) {
                ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, EventTypes.EVENT_VM_BACKUP_RESTORE,
                        String.format("Failed to restore Instance %s from Backup %s", vm.getInstanceName(), backup.getUuid()),
                        vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
                throw new CloudRuntimeException("Error restoring Instance from Backup with uuid " + backup.getUuid());
            }
            runPostRestoreMaintenance(backupProvider, vm, backup, false);
        // The restore process is executed by a backup provider outside of ACS, I am using the catch-all (Exception) to
        // ensure that no provider-side exception is missed. Therefore, we have a proper handling of exceptions, and rollbacks if needed.
        } catch (Exception e) {
            logger.error(String.format("Failed to restore backup [%s] due to: [%s].", backupDetailsInMessage, e.getMessage()), e);
            updateVolumeState(vm, Volume.Event.RestoreFailed, Volume.State.Ready);
            updateVmState(vm, VirtualMachine.Event.RestoringFailed, VirtualMachine.State.Stopped);
            if (e instanceof BackupProviderException) {
                throw e;
            }
            throw new CloudRuntimeException(String.format("Error restoring Instance from Backup [%s].", backupDetailsInMessage));
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
                    throw new CloudRuntimeException(String.format("Unable to change state of Instance [%s] to [%s].", vm, next));
                }
            } catch (NoTransitionException e) {
                String errMsg = String.format("Failed to update state of Instance [%s] with event [%s] due to [%s].", vm, event, e.getMessage());
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
    public boolean restoreBackupToVM(final Long backupId, final Long vmId, boolean quickRestore) throws CloudRuntimeException {
        return restoreBackupToVMInternal(backupId, vmId, quickRestore, false, false)
                == BackupManager.RestoreRequestStatus.COMPLETED;
    }

    @Override
    public BackupManager.RestoreRequestStatus requestRestoreBackupToVM(final Long backupId, final Long vmId,
            final boolean quickRestore, final boolean startVmAfterRestore) throws CloudRuntimeException {
        return restoreBackupToVMInternal(backupId, vmId, quickRestore, true, startVmAfterRestore);
    }

    private BackupManager.RestoreRequestStatus restoreBackupToVMInternal(final Long backupId, final Long vmId,
            final boolean quickRestore, final boolean allowDetachedRestore, final boolean startVmAfterRestore) throws CloudRuntimeException {
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
            throw new CloudRuntimeException("The Instance should be in stopped state");
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

        if (quickRestore && !backupProvider.getName().equals(KBOSS_BACKUP_PROVIDER)) {
            throw new CloudRuntimeException("Quick restore is only supported by KBOSS.");
        }

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

            initializeRestoreOperation(backup.getId(), offering, AblestackBackupFrameworkUtils.RESTORE_OPERATION_CREATE_INSTANCE,
                    vm.getId(), null, null);
            final boolean detachedRestore = allowDetachedRestore && backupProvider.supportsDetachedRestoreOrchestration();
            if (detachedRestore) {
                persistBackupDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_START_VM_DETAIL,
                        String.valueOf(startVmAfterRestore));
                if (eventId != null) {
                    persistBackupDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_EVENT_ID_DETAIL,
                            String.valueOf(eventId));
                }
            }
            String host = null;
            String dataStore = null;
            if (!BackupProviderNameUtils.isNasFamily(offering.getProvider()) &&
                    !BackupProviderNameUtils.isCommvaultFamily(offering.getProvider()) &&
                    !BackupProviderNameUtils.isVeeamFamily(offering.getProvider()) &&
                    !KBOSS_BACKUP_PROVIDER.equals(offering.getProvider())) {
                Pair<HostVO, StoragePoolVO> restoreInfo = getRestoreVolumeHostAndDatastore(vm);
                host = restoreInfo.first().getPrivateIpAddress();
                dataStore = restoreInfo.second().getUuid();
            }
            result = detachedRestore
                    ? backupProvider.startRestoreBackupToVM(vm, backup, host, dataStore, quickRestore)
                    : backupProvider.restoreBackupToVM(vm, backup, host, dataStore, quickRestore);

        } catch (Exception e) {
            persistRestoreOperationPhase(backup.getId(), "FAILED", "FAILED", null);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreFailure(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, e.getMessage());
            }
            logger.error(String.format("Failed to create Instance [%s] from backup [%s] due to: [%s]", vm.getInstanceName(), backupDetailsInMessage, e.getMessage()), e);
            processRestoreBackupToVMFailure(vm, backup, eventId, restoreStateRequested);
            throw new CloudRuntimeException(String.format("Error while creating Instance [%s] from backup [%s].", vm.getUuid(), backupDetailsInMessage));
        }

        if (result != null && !result.first()) {
            persistRestoreOperationPhase(backup.getId(), "FAILED", "FAILED", null);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreFailure(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, result.second());
            }
            String error_msg = String.format("Failed to create Instance [%s] from backup [%s] due to: %s.", vm.getInstanceName(), backupDetailsInMessage, result.second());
            logger.error(error_msg);
            processRestoreBackupToVMFailure(vm, backup, eventId, restoreStateRequested);
            throw new CloudRuntimeException(error_msg);
        }

        if (allowDetachedRestore && backupProvider.supportsDetachedRestoreOrchestration()) {
            persistRestoreOperationPhase(backup.getId(), "RUNNING", "QUEUED", 10);
            return BackupManager.RestoreRequestStatus.ACCEPTED;
        }

        persistRestoreOperationPhase(backup.getId(), "RUNNING", "FINALIZING", 95);
        updateStates(vm, backupProvider, quickRestore);
        ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_VM_CREATE_FROM_BACKUP,
                String.format("Successfully created Instance %s from backup %s", vm.getInstanceName(), backup.getUuid()),
                vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),eventId);
        if (netBackupRestore) {
            netBackupRestoreCoordinator.persistRestoreContext(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier,
                    RestorePhase.COMPLETED, null, backup.getExternalId());
        }
        persistRestoreOperationPhase(backup.getId(), "COMPLETED", "COMPLETED", 100);
        cleanupTrackedRestoreJobFilesAndDetails(backup, vm, backupProvider.getName());
        return BackupManager.RestoreRequestStatus.COMPLETED;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_RESTORE_VOLUME_TO_VM, eventDescription = "restoring Volume from Backup to Instance", async = true)
    public boolean restoreBackupVolumeAndAttachToVM(final String backedUpVolumeUuid, final Long backupId, final Long vmId, boolean isQuickRestore,
            Long hostId) throws Exception {
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
        Account callerAccount = CallContext.current().getCallingAccount();
        accountManager.checkAccess(callerAccount, null, true, vm);
        validateHostIdParameter(hostId, callerAccount);

        if (vm.getBackupOfferingId() != null && !BackupEnableAttachDetachVolumes.value()) {
            throw new CloudRuntimeException("The selected VM is attached to a backup offering and, thus, it is not possible to restore and attach volumes from backups to the instance.");
        }

        if (backup.getZoneId() != vm.getDataCenterId()) {
            throw new CloudRuntimeException("Cross zone backup restoration of volume is not allowed");
        }

        List<Backup.VolumeInfo> volumeInfoList = backup.getBackedUpVolumes();
        final VMInstanceVO vmFromBackup = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (volumeInfoList == null) {
            if (vmFromBackup == null) {
                throw new CloudRuntimeException("Instance reference for the provided Instance backup not found");
            } else if (vmFromBackup.getBackupVolumeList() == null) {
                throw new CloudRuntimeException("Volumes metadata not found in the backup");
            }
            volumeInfoList = vmFromBackup.getBackupVolumeList();
        }
        Backup.VolumeInfo backupVolumeInfo = getVolumeInfo(volumeInfoList, backedUpVolumeUuid);
        if (backupVolumeInfo == null) {
            throw new CloudRuntimeException("Failed to find volume with Id " + backedUpVolumeUuid + " in the backed-up volumes metadata");
        }

        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vmFromBackup);
        final BackupOffering offering = backupOfferingDao.findByIdIncludingRemoved(backup.getBackupOfferingId());
        if (offering == null) {
            throw new CloudRuntimeException("Failed to find Instance Backup Offering");
        }

        if (!isLiveRestoreVolumeAttachSupported(offering) && !VirtualMachine.PowerState.PowerOff.equals(vm.getPowerState())) {
            throw new CloudRuntimeException(String.format("VM [%s] needs to be powered off to restore the volume [%s].", vm.getUuid(), backedUpVolumeUuid));
        }

        BackupProvider backupProvider = getBackupProvider(offering.getProvider());
        VolumeVO backedUpVolume = volumeDao.findByUuidIncludingRemoved(backedUpVolumeUuid);
        Pair<HostVO, StoragePoolVO> restoreInfo;
        if ((BackupProviderNameUtils.isNasFamily(offering.getProvider()) ||
                BackupProviderNameUtils.isCommvaultFamily(offering.getProvider())) && backedUpVolume != null) {
            restoreInfo = getRestoreVolumeHostAndDatastoreForNas(vm, backedUpVolume);
        } else if (KBOSS_BACKUP_PROVIDER.equals(offering.getProvider())) {
            restoreInfo = getRestoreVolumeHostAndDatastoreForKboss(vm, backedUpVolume, isQuickRestore, hostId);
        } else {
            restoreInfo = getRestoreVolumeHostAndDatastore(vm);
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
        final boolean singleRestoreTargetAttempt = isAblestackRestoreVolumeProvider(offering);
        String[] hostPossibleValues = singleRestoreTargetAttempt
                ? new String[]{host.getPrivateIpAddress()}
                : new String[]{host.getPrivateIpAddress(), host.getName()};
        String[] datastoresPossibleValues = singleRestoreTargetAttempt
                ? new String[]{datastore.getUuid()}
                : new String[]{datastore.getUuid(), datastore.getName()};
        logger.info("Restore volume target candidates resolved for provider [{}], backup [{}], VM [{}]. singleAttempt=[{}], hosts={}, datastores={}",
                offering.getProvider(), backup.getUuid(), vm.getInstanceName(), singleRestoreTargetAttempt,
                Arrays.toString(hostPossibleValues), Arrays.toString(datastoresPossibleValues));
        final String netBackupRestoreRequestIdentifier = netBackupRestore ? netBackupRestoreCoordinator.getMoldRestoreRequestIdentifier(backup) : null;
        final VMInstanceVO netBackupRestoreMarkerVm = netBackupRestore ? netBackupRestoreCoordinator.getRestoreMarkerVm(backup, vm) : null;
        if (netBackupRestore) {
            netBackupRestoreCoordinator.blockIfRestoreAlreadyActive(netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier);
            final RestorePhase phase = isNetBackupIncrementalBackup(backup)
                    ? RestorePhase.CHAIN_RESTORE_IN_PROGRESS
                    : RestorePhase.ROOT_RESTORE_IN_PROGRESS;
            netBackupRestoreCoordinator.persistRestoreContext(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier,
                    phase, host.getName(), backup.getExternalId());
        }

        if (netBackupRestore) {
            netBackupRestoreCoordinator.persistRestoreContext(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier,
                    RestorePhase.HOST_RESTORE_IN_PROGRESS, host.getName(), backup.getExternalId());
        }
        initializeRestoreOperation(backup.getId(), offering, AblestackBackupFrameworkUtils.RESTORE_OPERATION_VOLUME_ATTACH,
                vm.getId(), backedUpVolumeUuid, datastore.getUuid());
        final boolean detachedRestore = backupProvider.supportsDetachedRestoreOrchestration();
        Pair<Boolean, String> result = restoreBackedUpVolume(backupVolumeInfo, backup, backupProvider,
                hostPossibleValues, datastoresPossibleValues, vm, isQuickRestore, detachedRestore);

        if (BooleanUtils.isFalse(result.first())) {
            persistRestoreOperationPhase(backup.getId(), "FAILED", "FAILED", null);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreFailure(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, result.second());
            }
            throw new CloudRuntimeException(String.format("Error restoring Volume [%s] of Instance [%s] to host [%s] using backup provider [%s] due to: [%s].",
                    backedUpVolumeUuid, vm.getUuid(), host.getUuid(), backupProvider.getName(), result.second()));
        }
        if (detachedRestore) {
            persistBackupDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_TARGET_VOLUME_UUID_DETAIL, result.second());
            persistRestoreOperationPhase(backup.getId(), "RUNNING", "QUEUED", 10);
            return true;
        }
        try {
            persistRestoreOperationPhase(backup.getId(), "RUNNING", "ATTACH_VOLUME", 85);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreContext(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier,
                        RestorePhase.ATTACH_VOLUME_IN_PROGRESS, host.getName(), backup.getExternalId());
            }
            if (!attachVolumeToVM(vm.getDataCenterId(), result.second(), backupVolumeInfo,
                                backedUpVolumeUuid, vm, datastore.getUuid(), backup, backupProvider)) {
                throw new CloudRuntimeException(String.format("Error attaching Volume [%s] to Instance [%s].", backedUpVolumeUuid, vm.getUuid()));
            }
            runPostRestoreMaintenance(backupProvider, vm, backup, true);
            persistRestoreOperationPhase(backup.getId(), "RUNNING", "FINALIZING", 95);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreContext(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier,
                        RestorePhase.COMPLETED, host.getName(), backup.getExternalId());
            }
            persistRestoreOperationPhase(backup.getId(), "COMPLETED", "COMPLETED", 100);
            clearTrackedRestoreJobDetails(backup);
        } catch (Exception e) {
            persistRestoreOperationPhase(backup.getId(), "FAILED", "FINALIZATION_FAILED", 95);
            if (netBackupRestore) {
                netBackupRestoreCoordinator.persistRestoreFailure(backup, netBackupRestoreMarkerVm, netBackupRestoreRequestIdentifier, e.getMessage());
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

    private boolean isLiveRestoreVolumeAttachSupported(final BackupOffering offering) {
        if (offering == null) {
            return false;
        }
        final String provider = offering.getProvider();
        return StringUtils.equals(KBOSS_BACKUP_PROVIDER, provider) ||
                BackupProviderNameUtils.isNasFamily(provider) ||
                BackupProviderNameUtils.isCommvaultFamily(provider) ||
                BackupProviderNameUtils.isNetBackupFamily(provider) ||
                BackupProviderNameUtils.isVeeamFamily(provider);
    }

    private boolean isAblestackRestoreVolumeProvider(final BackupOffering offering) {
        if (offering == null) {
            return false;
        }
        final String provider = offering.getProvider();
        return BackupProviderNameUtils.isNasFamily(provider) ||
                BackupProviderNameUtils.isCommvaultFamily(provider) ||
                BackupProviderNameUtils.isNetBackupFamily(provider) ||
                BackupProviderNameUtils.isVeeamFamily(provider);
    }

    protected Pair<Boolean, String> restoreBackedUpVolume(final Backup.VolumeInfo backupVolumeInfo, final BackupVO backup,
            BackupProvider backupProvider, String[] hostPossibleValues, String[] datastoresPossibleValues, VMInstanceVO vm,
            boolean quickRestore) {
        return restoreBackedUpVolume(backupVolumeInfo, backup, backupProvider, hostPossibleValues,
                datastoresPossibleValues, vm, quickRestore, false);
    }

    protected Pair<Boolean, String> restoreBackedUpVolume(final Backup.VolumeInfo backupVolumeInfo, final BackupVO backup,
            BackupProvider backupProvider, String[] hostPossibleValues, String[] datastoresPossibleValues, VMInstanceVO vm,
            boolean quickRestore, boolean detachedRestore) {
        Pair<Boolean, String> result = new Pair<>(false, "");
        List<String> failureDetails = new ArrayList<>();
        for (String hostData : hostPossibleValues) {
            for (String datastoreData : datastoresPossibleValues) {
                logger.debug(String.format("Trying to restore volume [UUID: %s], using host [%s] and datastore [%s].",
                        backupVolumeInfo.getUuid(), hostData, datastoreData));

                try {
                    result = detachedRestore
                            ? backupProvider.startRestoreBackedUpVolume(backup, backupVolumeInfo, hostData, datastoreData,
                                    new Pair<>(vm.getName(), vm.getState()), vm, quickRestore)
                            : backupProvider.restoreBackedUpVolume(backup, backupVolumeInfo, hostData, datastoreData,
                                    new Pair<>(vm.getName(), vm.getState()), vm, quickRestore);
                    if (result != null && StringUtils.isNotBlank(result.second())) {
                        failureDetails.add(String.format("host [%s], datastore [%s]: %s", hostData, datastoreData, result.second()));
                    }
                    if (!detachedRestore || result == null || BooleanUtils.isFalse(result.first())) {
                        cleanupRestoreVolumeAttemptJobFiles(backup, vm, backupProvider.getName(), backupVolumeInfo.getUuid(),
                                result != null ? result.second() : null);
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
                    if (e instanceof BackupProviderException) {
                        throw e;
                    }
                    if (KBOSS_BACKUP_PROVIDER.equals(backupProvider.getName())) {
                        return result;
                    }
                }
            }
        }
        final String resultDetails = result != null ? result.second() : null;
        return new Pair<>(false, CollectionUtils.isNotEmpty(failureDetails) ? StringUtils.join(failureDetails, "; ") : resultDetails);
    }

    private void cleanupRestoreVolumeAttemptJobFiles(final BackupVO backup, final VMInstanceVO vm, final String provider,
            final String backedUpVolumeUuid, final String restoredVolumeUuid) {
        cleanupTrackedRestoreJobFiles(backup, vm, provider, backedUpVolumeUuid, restoredVolumeUuid);
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
    @ActionEvent(eventType = EventTypes.EVENT_VM_BACKUP_DELETE, eventDescription = "deleting Instance backup", async = true)
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
                // Chain-aware providers (e.g. NAS) physically remove several backups per call
                // (leaf + swept delete-pending ancestors) and decrement resource count/usage and
                // remove each DB row themselves, exactly once per removed backup. Decrementing or
                // removing again here would double-handle and destroy delete-pending tombstones,
                // so defer entirely to the provider for those.
                if (backupProvider.handlesChainDeleteResourceAccounting()) {
                    checkAndGenerateUsageForLastBackupDeletedAfterOfferingRemove(vm, backup);
                    return true;
                }
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

    private Pair<HostVO, StoragePoolVO> getRestoreVolumeHostAndDatastoreForKboss(VMInstanceVO vm, VolumeVO backedVolume, boolean quickRestore, Long hostId) {
        StoragePoolVO storagePool = primaryDataStoreDao.findById(backedVolume.getPoolId());
        if (vm.getHostId() != null) {
            hostId = vm.getHostId();
        } else if (hostId == null || !quickRestore) {
            if (vm.getLastHostId() != null) {
                hostId = vm.getLastHostId();
            } else {
                if (storagePool == null) {
                    throw new InvalidParameterValueException(String.format("Storage pool of volume [%s] was not found.", backedVolume.getUuid()));
                }
                List<HostVO> listHost =
                        hostDao.listAllUpAndEnabledNonHAHosts(Host.Type.Routing, storagePool.getClusterId(), storagePool.getPodId(), storagePool.getDataCenterId(), null);
                return new Pair<>(listHost.stream().findFirst().orElseThrow(() -> new CloudRuntimeException(String.format("Unable to find a host to restore backup for VM " +
                        "[%s].", vm.getUuid()))), null);
            }
        }
        if (hostId == null) {
            throw new InvalidParameterValueException(String.format("No host found to quick restore VM [%s]. Please check the logs.", vm.getUuid()));
        }

        return new Pair<>(hostDao.findById(hostId), storagePool);
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
                                     String volumeUuid, VMInstanceVO vm, String datastoreUuid, Backup backup, BackupProvider backupProvider) throws Exception {
        HypervisorGuru guru = hypervisorGuruManager.getGuru(vm.getHypervisorType());
        backupVolumeInfo.setType(Volume.Type.DATADISK);

        logger.info("Attaching the restored Volume {} to Instance {}.", () -> ReflectionToStringBuilder.toString(backupVolumeInfo, ToStringStyle.JSON_STYLE), () -> vm);
        StoragePoolVO pool = primaryDataStoreDao.findByUuid(datastoreUuid);
        try {
            return guru.attachRestoredVolumeToVirtualMachine(zoneId, restoredVolumeLocation, backupVolumeInfo, vm, pool.getId(), backup, backupProvider);
        } catch (Exception e) {
            throw new CloudRuntimeException("Error attach restored Volume to Instance " + vm.getUuid() + " due to: " + e.getMessage());
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
        backgroundPollManager.submitTask(new ActiveBackupJobSyncTask(this));
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
            if (seenProviders.add(provider.getName().toLowerCase())) {
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
                    boolean exists = providers.stream().anyMatch(p -> p.getName().equalsIgnoreCase(provider.getName()));
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

    @Override
    public BackupProvider getBackupProvider(final String name) {
        if (StringUtils.isEmpty(name)) {
            throw new CloudRuntimeException("Invalid backup provider name provided");
        }
        if (backupProvidersMap.containsKey(name)) {
            return backupProvidersMap.get(name);
        }
        throw new CloudRuntimeException("Failed to find backup provider by the name: " + name);
    }

    /**
     * Resolve the standalone Ablestack Veeam KVM provider for seed import APIs.
     * When both "ablestack-veeam" and display-name "veeam" (often NAS-hybrid) are loaded,
     * prefer ablestack-veeam so host staging import does not require a Mold backup repository.
     */
    private BackupProvider getAblestackVeeamBackupProvider(final String offeringProviderName) {
        if (backupProvidersMap.containsKey(BackupProviderNameUtils.ABLESTACK_VEEAM)) {
            return backupProvidersMap.get(BackupProviderNameUtils.ABLESTACK_VEEAM);
        }
        return getBackupProvider(offeringProviderName);
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
        cmdList.add(CancelBackupCmd.class);
        cmdList.add(GetBackupJobStatusCmd.class);
        cmdList.add(GetBackupRestoreJobStatusCmd.class);
        cmdList.add(UpdateBackupJobBandwidthCmd.class);
        cmdList.add(ListNetBackupBackupCandidatesCmd.class);
        cmdList.add(ImportAblestackVeeamBackupSeedCmd.class);
        cmdList.add(ListVeeamRestorePointsCmd.class);
        cmdList.add(CreateAblestackVeeamBackupCmd.class);
        cmdList.add(UpdateAblestackVeeamBackupCmd.class);
        cmdList.add(SyncAblestackVeeamBackupsCmd.class);
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
        cmdList.add(CreateBackupOfferingCmd.class);
        cmdList.add(DownloadValidationScreenshotCmd.class);
        cmdList.add(ListBackupServiceJobsCmd.class);
        cmdList.add(FinishBackupChainCmd.class);
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
                BackupActiveJobSyncPollingInterval,
                BackupDataOperationTimeout,
                BackupQosBandwidthLimitMbps,
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
        return scheduleNextBackupJob(backupSchedule, currentTimestamp != null ? currentTimestamp : new Date());
    }

    @DB
    private Date scheduleNextBackupJob(final BackupScheduleVO backupSchedule, final Date referenceTimestamp) {
        final Date nextTimestamp = DateUtil.getNextRunTime(backupSchedule.getScheduleType(), backupSchedule.getSchedule(),
                backupSchedule.getTimezone(), referenceTimestamp);
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
            if (offering == null || !allowsUserDefinedBackupSchedule(offering)) {
                continue;
            }
            if (isExternalPolicyScheduledProvider(offering.getProvider())) {
                logger.debug("Skipping Mold-driven backup schedule [id: {}, uuid: {}, vmId: {}, provider: {}] because schedules for this provider are executed by provider-side hooks.",
                        backupSchedule.getId(), backupSchedule.getUuid(), vmId, offering.getProvider());
                continue;
            }
            if (isDisabled(vm.getDataCenterId())) {
                continue;
            }

            final Account backupAccount = accountService.getAccount(vm.getAccountId());
            if (backupAccount == null || backupAccount.getState() == Account.State.DISABLED) {
                logger.debug("Skip backup for Instance ({}) since its account has been removed or disabled.", vm);
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
                        EventTypes.EVENT_VM_BACKUP_CREATE, "creating Backup for Instance ID:" + vm.getUuid(),
                        vmId, ApiCommandResourceType.VirtualMachine.toString(),
                        true, 0);
                final Map<String, String> params = new HashMap<String, String>();
                params.put(ApiConstants.VIRTUAL_MACHINE_ID, "" + vmId);
                params.put(ApiConstants.SCHEDULE_ID, String.valueOf(backupScheduleId));
                if (quiesceVm != null) {
                    params.put(ApiConstants.QUIESCE_VM, "" + quiesceVm.toString());
                }
                params.put(ApiConstants.ISOLATED, String.valueOf(backupSchedule.isIsolated()));
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

    private boolean allowsUserDefinedBackupSchedule(final BackupOffering offering) {
        return offering != null && (offering.isUserDrivenBackupAllowed() || isExternalPolicyScheduledProvider(offering.getProvider()));
    }

    private boolean allowsScheduledBackupExecution(final BackupOffering offering, final boolean isScheduledBackup) {
        return offering != null && isScheduledBackup && isExternalPolicyScheduledProvider(offering.getProvider());
    }

    private boolean isExternalPolicyScheduledProvider(final String provider) {
        return BackupProviderNameUtils.isNetBackupFamily(provider) || BackupProviderNameUtils.isVeeamFamily(provider);
    }

    @Override
    public boolean start() {
        initializeBackupProviderMap();
        startConfiguredCommvaultBackupAgentInstallTask();

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

    private void startConfiguredCommvaultBackupAgentInstallTask() {
        Thread installTask = new Thread(new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                for (int attempt = 1; attempt <= COMMVAULT_BACKUP_AGENT_INSTALL_RETRY_ATTEMPTS; attempt++) {
                    if (attempt > 1 && !waitBeforeNextCommvaultBackupAgentInstallAttempt()) {
                        return;
                    }
                    logger.info("Running Commvault backup agent auto-install attempt [{}/{}].",
                            attempt, COMMVAULT_BACKUP_AGENT_INSTALL_RETRY_ATTEMPTS);
                    if (installConfiguredCommvaultBackupAgents()) {
                        logger.info("Commvault backup agent auto-install finished on attempt [{}/{}].",
                                attempt, COMMVAULT_BACKUP_AGENT_INSTALL_RETRY_ATTEMPTS);
                        return;
                    }
                }
                logger.warn("Commvault backup agent auto-install did not complete after [{}] attempts.",
                        COMMVAULT_BACKUP_AGENT_INSTALL_RETRY_ATTEMPTS);
            }
        }, "CommvaultBackupAgentInstallTask");
        installTask.setDaemon(true);
        installTask.start();
    }

    private boolean waitBeforeNextCommvaultBackupAgentInstallAttempt() {
        try {
            Thread.sleep(COMMVAULT_BACKUP_AGENT_INSTALL_RETRY_INTERVAL_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting to retry Commvault backup agent auto-install.", e);
            return false;
        }
    }

    private boolean installConfiguredCommvaultBackupAgents() {
        if (!BackupFrameworkEnabled.value()) {
            logger.debug("Skipping Commvault backup agent auto-install because backup framework is disabled globally.");
            return true;
        }
        boolean completed = true;
        for (final DataCenter dataCenter : dataCenterDao.listAllZones()) {
            if (dataCenter == null || isDisabled(dataCenter.getId())) {
                logger.debug("Skipping Commvault backup agent auto-install because backup framework is disabled in zone [{}].",
                        dataCenter == null ? "NULL Zone!" : dataCenter);
                continue;
            }
            completed &= installConfiguredCommvaultBackupAgent(dataCenter.getId());
        }
        return completed;
    }

    private boolean installConfiguredCommvaultBackupAgent(final Long zoneId) {
        final String providersConfig = BackupProviderPlugin.valueIn(zoneId);
        if (StringUtils.isBlank(providersConfig) || Arrays.stream(providersConfig.split(","))
                .map(String::trim)
                .noneMatch(BackupProviderNameUtils::isCommvaultFamily)) {
            return true;
        }

        final GlobalLock installLock = GlobalLock.getInternLock("commvault.backup.agent.install." + zoneId);
        try {
            if (!installLock.lock(5)) {
                logger.debug("Skipping Commvault backup agent auto-install in zone [{}] because another management server is running it.", zoneId);
                return true;
            }
            try {
                BackupProvider provider = getBackupProvider(BackupProviderNameUtils.ABLESTACK_COMMVAULT);
                logger.info("Checking Commvault backup agent installation in zone [{}] during management server startup.", zoneId);
                if (!provider.checkBackupAgent(zoneId)) {
                    logger.info("Commvault backup agent is not ready in zone [{}]. Starting automatic installation.", zoneId);
                    if (!provider.installBackupAgent(zoneId)) {
                        logger.warn("Commvault backup agent automatic installation did not complete successfully in zone [{}].", zoneId);
                        return false;
                    }
                }
                return true;
            } finally {
                installLock.unlock();
            }
        } catch (Exception e) {
            if (isPermanentCommvaultBackupAgentInstallFailure(e)) {
                logger.error("Stopping Commvault backup agent auto-install retries in zone [{}] due to a permanent configuration failure: {}",
                        zoneId, e.getMessage());
                return true;
            }
            logger.warn("Failed to run Commvault backup agent automatic installation in zone [{}]: {}", zoneId, e.getMessage(), e);
            return false;
        } finally {
            installLock.releaseRef();
        }
    }

    private boolean isPermanentCommvaultBackupAgentInstallFailure(final Exception e) {
        String message = e == null ? null : e.getMessage();
        if (StringUtils.isBlank(message)) {
            return false;
        }
        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        return normalizedMessage.contains("commvault backup agent automatic installation cannot continue") ||
                (normalizedMessage.contains("software cache") &&
                        (normalizedMessage.contains("required install media") || normalizedMessage.contains("required media version") || normalizedMessage.contains("missing")));
    }

    private VMInstanceVO findVmById(final Long vmId) {
        final VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new CloudRuntimeException(String.format("Can't find any Instance with ID: [%s].", vmId));
        }
        return vm;
    }

    private void markResourceCountPending(final long backupId) {
        backupDetailsDao.removeDetail(backupId, AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL);
        backupDetailsDao.addDetail(backupId, AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL, Boolean.TRUE.toString(), false);
    }

    ////////////////////////////////////////////////////
    /////////////// Background Tasks ///////////////////
    ////////////////////////////////////////////////////

    /**
     * This background task syncs backups from providers side in CloudStack db
     * along with creation of usage records
     */
    protected class BackupSyncTask extends ManagedContextRunnable implements BackgroundPollTask {
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
                reconcileActiveBackupAndRestoreJobs("backup-sync");
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

        protected void reconcileActiveBackupAndRestoreJobs(final String source) {
            if (!activeBackupJobReconcileRunning.compareAndSet(false, true)) {
                logger.trace("Skipping active backup job reconciliation from [{}] because another reconciliation is already running.", source);
                return;
            }
            try {
                processPostRestoreMaintenanceTasks();
                reconcilePendingRestoreJobCleanups();
                for (final DataCenter dataCenter : dataCenterDao.listAllZones()) {
                    if (dataCenter == null || isDisabled(dataCenter.getId())) {
                        logger.trace("Active Backup Job Sync Task is not enabled in zone [{}]. Skipping this zone!",
                                dataCenter == null ? "NULL Zone!" : dataCenter);
                        continue;
                    }

                    netBackupRestoreCoordinator.reconcileActiveRestoreStates(dataCenter.getId());
                    final List<BackupProvider> providers = getBackupProvidersForZone(dataCenter.getId());
                    for (BackupProvider backupProvider : providers) {
                        try {
                            reconcileInterruptedRestoreJobs(backupProvider, dataCenter);
                            reconcileBackingUpBackups(backupProvider, dataCenter);
                        } catch (Exception e) {
                            logger.error("Failed to reconcile active backup/restore jobs for provider {} in zone {} from [{}]: {}",
                                    backupProvider.getName(), dataCenter.getId(), source, e.getMessage(), e);
                        }
                    }
                }
            } finally {
                activeBackupJobReconcileRunning.set(false);
            }
        }

        protected void reconcileInterruptedRestoreJobs(final BackupProvider backupProvider, final DataCenter dataCenter) {
            final List<BackupDetailVO> restoreJobDetails = backupDetailsDao.findDetails(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
            if (CollectionUtils.isEmpty(restoreJobDetails)) {
                return;
            }
            final Set<Long> checkedBackupIds = new HashSet<>();
            for (final BackupDetailVO restoreJobDetail : restoreJobDetails) {
                final Long backupId = restoreJobDetail.getResourceId();
                if (backupId == null || !checkedBackupIds.add(backupId)) {
                    continue;
                }
                try {
                    reconcileInterruptedRestoreJob(backupProvider, dataCenter, backupId);
                } catch (Exception e) {
                    logger.warn("Failed to reconcile restore job for backup [{}] and provider [{}] in zone [{}]: {}",
                            backupId, backupProvider.getName(), dataCenter.getId(), e.getMessage(), e);
                }
            }
        }

        protected void reconcileInterruptedRestoreJob(final BackupProvider backupProvider, final DataCenter dataCenter, final Long backupId) {
            final BackupVO backup = backupDao.findById(backupId);
            if (backup == null || dataCenter.getId() != backup.getZoneId()) {
                return;
            }
            final BackupOfferingVO offering = backupOfferingDao.findById(backup.getBackupOfferingId());
            if (offering == null || !isMatchingBackupProvider(backupProvider.getName(), offering.getProvider())) {
                return;
            }
            if (!isAblestackHostSideRestoreProvider(offering)) {
                return;
            }
            backupDao.loadDetails(backup);
            final String restoreJobId = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
            if (StringUtils.isBlank(restoreJobId)) {
                return;
            }
            final String operationType = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_OPERATION_TYPE_DETAIL);
            final long targetVmId = NumberUtils.toLong(
                    backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL), backup.getVmId());
            final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(targetVmId);
            final boolean volumeAttach = AblestackBackupFrameworkUtils.RESTORE_OPERATION_VOLUME_ATTACH.equals(operationType);
            final boolean createInstance = AblestackBackupFrameworkUtils.RESTORE_OPERATION_CREATE_INSTANCE.equals(operationType);
            final boolean cleanupRequired = createInstance && "CLEANUP_REQUIRED".equalsIgnoreCase(
                    backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL));
            final boolean finalizationRequired = "FINALIZATION_FAILED".equalsIgnoreCase(
                    backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL));
            if (vm == null || (!volumeAttach && !isRestoreStatePending(vm) && !cleanupRequired && !finalizationRequired)) {
                return;
            }
            final HostVO restoreHost = findRestoreJobHost(backup, vm);
            if (restoreHost == null) {
                logger.debug("Skipping restore job reconciliation for backup [{}] because restore host was not found. restoreJobId=[{}]",
                        backup.getUuid(), restoreJobId);
                return;
            }

            final Answer answer;
            try {
                answer = agentManager.send(restoreHost.getId(), new AblestackRestoreJobStatusCommand(restoreJobId, null, 5));
            } catch (Exception e) {
                logger.warn("Failed to query restore job [{}] on host [{}] for backup [{}]: {}",
                        restoreJobId, restoreHost.getName(), backup.getUuid(), e.getMessage(), e);
                return;
            }
            if (!(answer instanceof BackupAnswer)) {
                logger.warn("Unexpected restore job status response for backup [{}] from host [{}]. restoreJobId=[{}]",
                        backup.getUuid(), restoreHost.getName(), restoreJobId);
                return;
            }

            final BackupAnswer restoreAnswer = (BackupAnswer) answer;
            persistRestoreJobStatusDetails(backup.getId(), restoreAnswer);
            final String restoreState = StringUtils.defaultIfBlank(restoreAnswer.getState(), restoreAnswer.getDetails());
            logger.trace("Reconciling restore job [{}] for backup [{}], VM [{}] using provider [{}]. state=[{}], step=[{}], progress=[{}]",
                    restoreJobId, backup.getUuid(), vm.getInstanceName(), backupProvider.getName(), restoreState,
                    restoreAnswer.getStep(), restoreAnswer.getProgress());
            if (isAblestackHostSideRestoreProvider(offering) && isTrackedRestoreAsyncJobInProgress(backup)) {
                logger.trace("Skipping restore job terminal reconciliation for backup [{}], VM [{}] because its API async job is still in progress.",
                        backup.getUuid(), vm.getInstanceName());
                return;
            }
            if (isMissingUnstartedRestoreJob(backup, restoreState)) {
                markRestoreJobStartFailed(backup.getId());
                if (!volumeAttach) {
                    failInterruptedRestoreStates(vm);
                    cleanupFailedDetachedCreateInstance(backup, vm, operationType);
                }
                reconcileExternalRestoreTracking(offering, backup, vm, false, "Host-side restore job was not created");
                completeFailedRestoreTracking(backup, "Host-side restore job was not created");
                cleanupTerminalRestoreJobFiles(backup, restoreHost, restoreJobId, backupProvider.getName());
                logger.warn("Reconciled restore job [{}] for backup [{}], VM [{}] as failed because the host-side job was not created.",
                        restoreJobId, backup.getUuid(), vm.getInstanceName());
            } else if ("COMPLETED".equalsIgnoreCase(restoreState)) {
                persistRestoreOperationPhase(backup.getId(), "RUNNING", "FINALIZING", 95);
                try {
                    finalizeDetachedRestoreOperation(backupProvider, offering, backup, vm, operationType);
                } catch (Exception e) {
                    persistRestoreOperationPhase(backup.getId(), "FAILED", "FINALIZATION_FAILED", 95);
                    reconcileExternalRestoreTracking(offering, backup, vm, false, e.getMessage());
                    logger.warn("Failed to finalize completed restore job [{}] for backup [{}], VM [{}]: {}",
                            restoreJobId, backup.getUuid(), vm.getInstanceName(), e.getMessage(), e);
                    return;
                }
                reconcileExternalRestoreTracking(offering, backup, vm, true, null);
                persistRestoreOperationPhase(backup.getId(), "COMPLETED", "COMPLETED", 100);
                stopTrackingActiveRestoreJob(backup);
                cleanupTerminalRestoreJobFiles(backup, restoreHost, restoreJobId, backupProvider.getName());
                logger.info("Reconciled completed restore job [{}] for backup [{}], VM [{}].",
                        restoreJobId, backup.getUuid(), vm.getInstanceName());
            } else if (isTerminalRestoreFailureState(restoreState)) {
                persistRestoreOperationPhase(backup.getId(), "FAILED", "FAILED", null);
                if (!volumeAttach) {
                    failInterruptedRestoreStates(vm);
                    cleanupFailedDetachedCreateInstance(backup, vm, operationType);
                } else {
                    cleanupRestoredVolumeAfterAttachFailure(
                            backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_TARGET_VOLUME_UUID_DETAIL));
                }
                reconcileExternalRestoreTracking(offering, backup, vm, false,
                        StringUtils.defaultIfBlank(restoreAnswer.getDetails(), restoreState));
                completeFailedRestoreTracking(backup, StringUtils.defaultIfBlank(restoreAnswer.getDetails(), restoreState));
                cleanupTerminalRestoreJobFiles(backup, restoreHost, restoreJobId, backupProvider.getName());
                logger.warn("Reconciled failed restore job [{}] for backup [{}], VM [{}]. state=[{}]",
                        restoreJobId, backup.getUuid(), vm.getInstanceName(), restoreState);
            }
        }

        protected void finalizeDetachedRestoreOperation(final BackupProvider backupProvider,
                final BackupOffering offering, final BackupVO backup, final VMInstanceVO vm,
                final String operationType) throws Exception {
            if (AblestackBackupFrameworkUtils.RESTORE_OPERATION_VOLUME_ATTACH.equals(operationType)) {
                finalizeDetachedVolumeRestore(backupProvider, backup, vm);
                return;
            }

            runPostRestoreMaintenance(backupProvider, vm, backup, false);
            completeInterruptedRestoreStates(vm);
            if (AblestackBackupFrameworkUtils.RESTORE_OPERATION_VM.equals(operationType)) {
                final boolean imported = importRestoredVM(vm.getDataCenterId(), vm.getDomainId(), vm.getAccountId(),
                        vm.getUserId(), vm.getInstanceName(), vm.getHypervisorType(), backup, offering);
                if (!imported) {
                    throw new CloudRuntimeException(String.format(
                            "Failed to import Instance [%s] after host-side restore completed", vm.getInstanceName()));
                }
            } else if (AblestackBackupFrameworkUtils.RESTORE_OPERATION_CREATE_INSTANCE.equals(operationType)) {
                if (Boolean.parseBoolean(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_START_VM_DETAIL))
                        && !VirtualMachine.State.Running.equals(vm.getState())) {
                    persistRestoreOperationPhase(backup.getId(), "RUNNING", "START_VM", 98);
                    virtualMachineManager.start(vm.getUuid(), Collections.emptyMap());
                }
                ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_INFO,
                        EventTypes.EVENT_VM_CREATE_FROM_BACKUP,
                        String.format("Successfully created Instance %s from backup %s", vm.getInstanceName(), backup.getUuid()),
                        vm.getId(), ApiCommandResourceType.VirtualMachine.toString(), NumberUtils.toLong(
                                backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_EVENT_ID_DETAIL), 0));
            }
        }

        protected void cleanupFailedDetachedCreateInstance(final BackupVO backup, final VMInstanceVO vm,
                final String operationType) {
            if (!AblestackBackupFrameworkUtils.RESTORE_OPERATION_CREATE_INSTANCE.equals(operationType)) {
                return;
            }
            try {
                persistRestoreOperationPhase(backup.getId(), "FAILED", "CLEANUP", 0);
                virtualMachineManager.destroy(vm.getUuid(), true);
                persistRestoreOperationPhase(backup.getId(), "FAILED", "CLEANUP_COMPLETED", 0);
            } catch (Exception e) {
                persistRestoreOperationPhase(backup.getId(), "FAILED", "CLEANUP_REQUIRED", 0);
                logger.warn("Failed to clean up Instance [{}] after detached restore [{}] failed. Cleanup will be retried: {}",
                        vm.getInstanceName(), backup.getUuid(), e.getMessage(), e);
            }
        }

        protected void finalizeDetachedVolumeRestore(final BackupProvider backupProvider, final BackupVO backup,
                final VMInstanceVO vm) throws Exception {
            final String restoredVolumeUuid = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_TARGET_VOLUME_UUID_DETAIL);
            final String sourceVolumeUuid = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_SOURCE_VOLUME_UUID_DETAIL);
            final String datastoreUuid = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_TARGET_DATASTORE_UUID_DETAIL);
            if (StringUtils.isAnyBlank(restoredVolumeUuid, sourceVolumeUuid, datastoreUuid)) {
                throw new CloudRuntimeException("Detached volume restore context is incomplete");
            }
            final VolumeVO restoredVolume = volumeDao.findByUuid(restoredVolumeUuid);
            if (restoredVolume != null && Objects.equals(restoredVolume.getInstanceId(), vm.getId())) {
                runPostRestoreMaintenance(backupProvider, vm, backup, true);
                return;
            }
            final Backup.VolumeInfo backupVolumeInfo = getVolumeInfo(backup.getBackedUpVolumes(), sourceVolumeUuid);
            if (backupVolumeInfo == null || !attachVolumeToVM(vm.getDataCenterId(), restoredVolumeUuid,
                    backupVolumeInfo, sourceVolumeUuid, vm, datastoreUuid, backup, backupProvider)) {
                throw new CloudRuntimeException(String.format("Failed to attach restored volume [%s] to Instance [%s]",
                        restoredVolumeUuid, vm.getInstanceName()));
            }
            runPostRestoreMaintenance(backupProvider, vm, backup, true);
        }

        protected void reconcileExternalRestoreTracking(final BackupOffering offering, final BackupVO backup,
                final VMInstanceVO vm, final boolean completed, final String failureReason) {
            final String reason = StringUtils.defaultIfBlank(failureReason, "Host-side restore job failed");
            if (isAblestackNetBackupOffering(offering)) {
                final String requestIdentifier = netBackupRestoreCoordinator.getRestoreRequestId(backup);
                if (StringUtils.isNotBlank(requestIdentifier)) {
                    if (completed) {
                        netBackupRestoreCoordinator.persistRestoreState(backup, vm, requestIdentifier, RestorePhase.COMPLETED);
                        netBackupRestoreCoordinator.completeSession(vm.getId(), requestIdentifier);
                    } else {
                        netBackupRestoreCoordinator.persistRestoreFailure(backup, vm, requestIdentifier, reason);
                        netBackupRestoreCoordinator.failSession(vm.getId(), requestIdentifier, reason);
                    }
                }
            }
            if (BackupProviderNameUtils.isVeeamFamily(offering.getProvider())) {
                backupDao.loadDetails(backup);
                final String sessionId = backup.getDetail(VEEAM_RESTORE_SESSION_ID);
                if (StringUtils.isNotBlank(sessionId)) {
                    persistVeeamRestoreState(backup, vm, sessionId, completed ? "COMPLETED" : "FAILED",
                            completed ? null : reason);
                }
            }
        }

        protected boolean isTerminalRestoreFailureState(final String restoreState) {
            return isRestoreFailureState(restoreState);
        }

        protected void reconcilePendingRestoreJobCleanups() {
            final List<BackupDetailVO> cleanupDetails = backupDetailsDao.findDetails(
                    AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL);
            if (CollectionUtils.isEmpty(cleanupDetails)) {
                return;
            }
            for (final BackupDetailVO cleanupDetail : cleanupDetails) {
                final BackupVO backup = backupDao.findById(cleanupDetail.getResourceId());
                if (backup == null || StringUtils.isBlank(cleanupDetail.getValue())) {
                    continue;
                }
                backupDao.loadDetails(backup);
                final VMInstanceVO vm = findRestoreTargetVm(backup);
                final HostVO host = findRestoreJobHost(backup, vm);
                if (host != null && cleanupBackupJobFiles(host.getId(), cleanupDetail.getValue(), "restore")) {
                    completeRestoreJobFileCleanup(backup);
                }
            }
        }

        protected boolean isMissingUnstartedRestoreJob(final BackupVO backup, final String restoreState) {
            if (!"UNKNOWN".equalsIgnoreCase(restoreState)) {
                return false;
            }
            final String storedState = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
            final String storedStep = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL);
            if (!"STARTING".equalsIgnoreCase(storedState) && !"QUEUED".equalsIgnoreCase(storedStep)) {
                return false;
            }
            final String trackedAt = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_TRACKED_AT_DETAIL);
            if (StringUtils.isBlank(trackedAt)) {
                return true;
            }
            return NumberUtils.isDigits(trackedAt)
                    && Long.parseLong(trackedAt) <= System.currentTimeMillis() - RESTORE_JOB_START_GRACE_PERIOD_MS;
        }

        protected void markRestoreJobStartFailed(final Long backupId) {
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, "FAILED");
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL, "START_FAILED");
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL, "0");
        }

        protected boolean isRestoreStatePending(final VMInstanceVO vm) {
            return VirtualMachine.State.Restoring.equals(vm.getState()) || hasRestoringVolumes(vm);
        }

        protected boolean hasRestoringVolumes(final VMInstanceVO vm) {
            return volumeDao.findIncludingRemovedByInstanceAndType(vm.getId(), null).stream()
                    .anyMatch(volume -> Volume.State.Restoring.equals(volume.getState()));
        }

        protected void completeInterruptedRestoreStates(final VMInstanceVO vm) {
            updateRestoringVolumeStates(vm, Volume.Event.RestoreSucceeded, Volume.State.Ready);
            if (VirtualMachine.State.Restoring.equals(vm.getState())) {
                updateVmState(vm, VirtualMachine.Event.RestoringSuccess, VirtualMachine.State.Stopped);
            }
        }

        protected void failInterruptedRestoreStates(final VMInstanceVO vm) {
            updateRestoringVolumeStates(vm, Volume.Event.RestoreFailed, Volume.State.Ready);
            if (VirtualMachine.State.Restoring.equals(vm.getState())) {
                updateVmState(vm, VirtualMachine.Event.RestoringFailed, VirtualMachine.State.Stopped);
            }
        }

        protected void updateRestoringVolumeStates(final VMInstanceVO vm, final Volume.Event event, final Volume.State next) {
            Transaction.execute(TransactionLegacy.CLOUD_DB, (TransactionCallback<VolumeVO>) status -> {
                for (final VolumeVO volume : volumeDao.findIncludingRemovedByInstanceAndType(vm.getId(), null)) {
                    if (Volume.State.Restoring.equals(volume.getState())) {
                        tryToUpdateStateOfSpecifiedVolume(volume, event, next);
                    }
                }
                return null;
            });
        }

        protected void processPostRestoreMaintenanceTasks() {
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

        protected void reconcileBackingUpBackups(final BackupProvider backupProvider, final DataCenter dataCenter) {
            final List<BackupVO> backingUpBackups = backupDao.listByZoneAndStatus(dataCenter.getId(), Backup.Status.BackingUp);
            if (backingUpBackups == null || backingUpBackups.isEmpty()) {
                return;
            }
            logger.trace("Checking [{}] BackingUp backup records for provider [{}] in zone [{}].",
                    backingUpBackups.size(), backupProvider.getName(), dataCenter.getId());

            for (final BackupVO backup : backingUpBackups) {
                try {
                    final BackupOfferingVO offering = backupOfferingDao.findById(backup.getBackupOfferingId());
                    if (offering == null || !isMatchingBackupProvider(backupProvider.getName(), offering.getProvider())) {
                        continue;
                    }
                    final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
                    if (vm == null) {
                        logger.warn("Skipping BackingUp backup [{}] reconciliation because VM [{}] was not found.",
                                backup.getUuid(), backup.getVmId());
                        continue;
                    }
                    backupDao.loadDetails(backup);
                    logger.trace("Reconciling BackingUp backup [{}] for VM [{}] using backup provider [{}]. "
                                    + "backupId=[{}], vmId=[{}], externalId=[{}], date=[{}]",
                            backup.getUuid(), vm.getInstanceName(), backupProvider.getName(), backup.getId(), backup.getVmId(),
                            backup.getExternalId(), backup.getDate());
                    if (backupProvider.reconcileBackingUpBackup(vm, backup)) {
                        incrementResourceCountsIfBackupFinalized(backup, vm);
                        logger.info("Reconciled BackingUp backup [{}] for VM [{}] using backup provider [{}].",
                                backup.getUuid(), vm.getInstanceName(), backupProvider.getName());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to reconcile BackingUp backup [{}] for provider [{}] in zone [{}]: {}",
                            backup.getUuid(), backupProvider.getName(), dataCenter.getId(), e.getMessage(), e);
                }
            }
        }

        protected boolean isMatchingBackupProvider(final String activeProviderName, final String offeringProviderName) {
            return StringUtils.equalsIgnoreCase(activeProviderName, offeringProviderName);
        }

        protected void incrementResourceCountsIfBackupFinalized(final BackupVO originalBackup, final VirtualMachine vm) {
            final BackupVO updatedBackup = backupDao.findById(originalBackup.getId());
            if (updatedBackup == null || !Backup.Status.BackedUp.equals(updatedBackup.getStatus())) {
                return;
            }
            backupDao.loadDetails(updatedBackup);
            if (!Boolean.parseBoolean(updatedBackup.getDetail(AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL))) {
                return;
            }
            resourceLimitMgr.incrementResourceCount(updatedBackup.getAccountId(), Resource.ResourceType.backup);
            resourceLimitMgr.incrementResourceCount(updatedBackup.getAccountId(), Resource.ResourceType.backup_storage,
                    getBackupSizeForResourceCount(updatedBackup));
            backupDetailsDao.removeDetail(updatedBackup.getId(), AblestackBackupFrameworkUtils.RESOURCE_COUNT_PENDING_DETAIL);
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

                        resourceLimitMgr.decrementResourceCount(backupInDb.getAccountId(), Resource.ResourceType.backup_storage, getBackupSizeForResourceCount(backupInDb));
                        ((BackupVO) backupInDb).setSize(restorePoint.getBackupSize());
                        ((BackupVO) backupInDb).setProtectedSize(restorePoint.getDataSize());
                        resourceLimitMgr.incrementResourceCount(backupInDb.getAccountId(), Resource.ResourceType.backup_storage, getBackupSizeForResourceCount(backupInDb));

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
                final BackupOffering offering = backupOfferingDao.findById(backup.getBackupOfferingId());
                if (offering != null) {
                    final boolean netBackupFamily = BackupProviderNameUtils.isNetBackupFamily(offering.getProvider());
                    final boolean veeamFamily = BackupProviderNameUtils.isVeeamFamily(offering.getProvider());
                    if ((netBackupFamily || veeamFamily) && Backup.Status.BackingUp.equals(backup.getStatus())) {
                        logger.debug("Skipping removal of backup [{}] for VM [{}] because it is still BackingUp.",
                                backup.getId(), vm.getInstanceName());
                        continue;
                    }
                    if (netBackupFamily && (Backup.Status.Error.equals(backup.getStatus()) || Backup.Status.Failed.equals(backup.getStatus()))) {
                        logger.warn("Skipping removal of NetBackup backup [{}] for VM [{}] because it is in [{}] state and requires explicit delete.",
                                backup.getId(), vm.getInstanceName(), backup.getStatus());
                        continue;
                    }
                }
                logger.warn(String.format("Removing backup with ID: [%s].", backupIdToRemove));
                resourceLimitMgr.decrementResourceCount(backup.getAccountId(), Resource.ResourceType.backup);
                resourceLimitMgr.decrementResourceCount(backup.getAccountId(), Resource.ResourceType.backup_storage, getBackupSizeForResourceCount(backup));
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
                            resourceLimitMgr.incrementResourceCount(vm.getAccountId(), Resource.ResourceType.backup_storage, getBackupSizeForResourceCount(backup));

                            logger.debug(String.format("Creating a new entry in backups: [id: %s, uuid: %s, vm_id: %s, external_id: %s, type: %s, date: %s, backup_offering_id: %s, account_id: %s, "
                                            + "domain_id: %s, zone_id: %s].", backup.getId(), backup.getUuid(), backup.getVmId(), backup.getExternalId(), backup.getType(), backup.getDate(),
                                    backup.getBackupOfferingId(), backup.getAccountId(), backup.getDomainId(), backup.getZoneId()));

                            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_INFO, EventTypes.EVENT_VM_BACKUP_CREATE,
                                    String.format("Created Backup %s for Instance ID: %s", backup.getUuid(), vm.getUuid()),
                                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(),0);
                        }
                    }
                    processRemoveList(removeList, vm);
                }
            });
        }

        private long getBackupSizeForResourceCount(final Backup backup) {
            return backup != null && backup.getSize() != null ? backup.getSize() : 0L;
        }

        @Override
        public Long getDelay() {
            return BackupSyncPollingInterval.value() * 1000L;
        }
    }

    protected final class ActiveBackupJobSyncTask extends BackupSyncTask {
        public ActiveBackupJobSyncTask(final BackupManager backupManager) {
            super(backupManager);
        }

        @Override
        protected void runInContext() {
            try {
                if (logger.isTraceEnabled()) {
                    logger.trace("Active backup job sync background task is running...");
                }
                reconcileActiveBackupAndRestoreJobs("active-job-sync");
            } catch (final Throwable t) {
                logger.error(String.format("Error trying to run active backup job sync background task due to: [%s].", t.getMessage()), t);
            }
        }

        @Override
        public Long getDelay() {
            return Math.max(MIN_ACTIVE_JOB_SYNC_INTERVAL_SECONDS, BackupActiveJobSyncPollingInterval.value()) * 1000L;
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
    public boolean updateBackupJobBandwidth(final Long backupId, final Integer bandwidthLimitMbps) {
        if (bandwidthLimitMbps == null || bandwidthLimitMbps < 0) {
            throw new CloudRuntimeException("Backup bandwidth limit must be zero or greater");
        }

        final BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }
        if (!Backup.Status.BackingUp.equals(backup.getStatus())) {
            throw new CloudRuntimeException("Backup " + backup.getUuid() + " is not running");
        }

        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException("Instance " + backup.getVmId() + " does not exist");
        }
        accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);

        final HostVO host = findBackupJobHost(backup, vm);
        if (host == null) {
            throw new CloudRuntimeException("Unable to find host for running backup " + backup.getUuid());
        }

        try {
            final Answer answer = agentManager.send(host.getId(), new AblestackBackupJobBandwidthCommand(backup.getUuid(), bandwidthLimitMbps));
            if (answer == null) {
                throw new CloudRuntimeException("No response from host " + host.getName());
            }
            if (!answer.getResult()) {
                throw new CloudRuntimeException(answer.getDetails());
            }
            return true;
        } catch (final Exception e) {
            throw new CloudRuntimeException(String.format("Failed to update backup bandwidth for backup [%s] on host [%s]: %s",
                    backup.getUuid(), host.getName(), e.getMessage()), e);
        }
    }

    @Override
    public BackupJobStatusResponse getBackupJobStatus(final Long backupId, final Long eventsOffset, final Integer eventsLimit) {
        final BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }

        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm != null) {
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        } else {
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, backup);
        }

        final BackupJobStatusResponse response = createBackupJobStatusResponse(backup);
        response.setOperation(AblestackBackupFrameworkUtils.OPERATION_BACKUP);
        if (!Backup.Status.BackingUp.equals(backup.getStatus())) {
            response.setState(backup.getStatus() != null ? backup.getStatus().toString() : null);
            response.setProgress(getTerminalBackupProgress(backup.getStatus()));
            return response;
        }

        if (vm == null) {
            throw new CloudRuntimeException("Instance " + backup.getVmId() + " does not exist");
        }
        final HostVO host = findBackupJobHost(backup, vm);
        if (host == null) {
            throw new CloudRuntimeException("Unable to find host for running backup " + backup.getUuid());
        }

        try {
            final Answer answer = agentManager.send(host.getId(), new AblestackBackupJobStatusCommand(backup.getUuid(), eventsOffset, eventsLimit));
            if (!(answer instanceof BackupAnswer)) {
                throw new CloudRuntimeException("Unexpected backup job status response from host " + host.getName());
            }
            final BackupAnswer backupAnswer = (BackupAnswer) answer;
            response.setState(StringUtils.defaultIfBlank(backupAnswer.getState(), backup.getStatus().toString()));
            response.setStep(backupAnswer.getStep());
            response.setProgress(backupAnswer.getProgress());
            response.setEventsOffset(backupAnswer.getEventsOffset());
            response.setEvents(backupAnswer.getEventsJson());
            response.setLogPath(backupAnswer.getLogPath());
            response.setExitCode(backupAnswer.getExitCode());
            response.setOperation(backupAnswer.getOperation());
            response.setCapabilities(backupAnswer.getCapabilities());
            response.setBandwidthLimitMbps(backupAnswer.getBandwidthLimitMbps());
            response.setBandwidthStatus(backupAnswer.getBandwidthStatus());
            if (!backupAnswer.getResult()) {
                response.setStep(StringUtils.defaultIfBlank(backupAnswer.getDetails(), response.getStep()));
            }
            return response;
        } catch (final Exception e) {
            throw new CloudRuntimeException(String.format("Failed to query backup job status for backup [%s] on host [%s]: %s",
                    backup.getUuid(), host.getName(), e.getMessage()), e);
        }
    }

    private HostVO findBackupJobHost(final BackupVO backup, final VMInstanceVO vm) {
        final Long backupJobHostId = getBackupJobHostId(backup);
        if (backupJobHostId != null) {
            final HostVO host = hostDao.findById(backupJobHostId);
            if (host != null) {
                return host;
            }
        }

        if (vm == null) {
            return null;
        }
        final Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        return hostId != null ? hostDao.findById(hostId) : null;
    }

    private Long getBackupJobHostId(final BackupVO backup) {
        if (backup == null) {
            return null;
        }
        return backup.getHostId();
    }

    private boolean cleanupBackupJobFiles(final Long hostId, final String backupJobId, final String provider) {
        if (hostId == null) {
            logger.debug("Skipping {} backup job cleanup because host id is missing [jobId: {}]", provider, backupJobId);
            return false;
        }
        try {
            final Answer answer = agentManager.send(hostId, new AblestackBackupJobCleanupCommand(backupJobId));
            if (answer == null || !answer.getResult()) {
                logger.warn("Failed to cleanup {} backup job files [jobId: {}, hostId: {}]: {}",
                        provider, backupJobId, hostId, answer != null ? answer.getDetails() : null);
                return false;
            }
            logger.info("Cleaned up {} backup job files [jobId: {}, hostId: {}].", provider, backupJobId, hostId);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to send {} backup job cleanup command [jobId: {}, hostId: {}]",
                    provider, backupJobId, hostId, e);
            return false;
        }
    }

    private boolean cleanupTrackedRestoreJobFiles(final BackupVO backup, final VMInstanceVO vm, final String provider) {
        return cleanupTrackedRestoreJobFiles(backup, vm, provider, null, null);
    }

    private boolean cleanupTrackedRestoreJobFiles(final BackupVO backup, final VMInstanceVO vm, final String provider,
            final String backedUpVolumeUuid, final String restoredVolumeUuid) {
        backupDao.loadDetails(backup);
        final HostVO restoreHost = findRestoreJobHost(backup, vm);
        if (restoreHost == null) {
            logger.debug("Skipping {} restore job cleanup because restore host was not found [backup: {}]",
                    provider, backup.getUuid());
            return false;
        }
        final List<String> restoreJobIds = getRestoreJobIdsForCleanup(backup, vm, provider, backedUpVolumeUuid, restoredVolumeUuid);
        if (CollectionUtils.isEmpty(restoreJobIds)) {
            logger.debug("Skipping {} restore job cleanup because restore job id is missing [backup: {}, vm: {}]",
                    provider, backup.getUuid(), vm != null ? vm.getInstanceName() : null);
            return true;
        }
        boolean cleanedAny = false;
        for (final String restoreJobId : restoreJobIds) {
            cleanedAny |= cleanupBackupJobFiles(restoreHost.getId(), restoreJobId, provider + " restore");
        }
        return cleanedAny;
    }

    private List<String> getRestoreJobIdsForCleanup(final BackupVO backup, final VMInstanceVO vm, final String provider,
            final String backedUpVolumeUuid, final String restoredVolumeUuid) {
        final List<String> restoreJobIds = new ArrayList<>();
        final String trackedRestoreJobId = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
        if (StringUtils.isNotBlank(trackedRestoreJobId)) {
            restoreJobIds.add(trackedRestoreJobId);
        }
        addRestoreJobIdIfAbsent(restoreJobIds, getFallbackRestoreJobId(backup, vm, provider, restoredVolumeUuid));
        addRestoreJobIdIfAbsent(restoreJobIds, getFallbackRestoreJobId(backup, vm, provider, backedUpVolumeUuid));
        addRestoreJobIdIfAbsent(restoreJobIds, getFallbackRestoreJobId(backup, vm, provider, null));
        return restoreJobIds;
    }

    private void addRestoreJobIdIfAbsent(final List<String> restoreJobIds, final String restoreJobId) {
        if (StringUtils.isNotBlank(restoreJobId) && !restoreJobIds.contains(restoreJobId)) {
            restoreJobIds.add(restoreJobId);
        }
    }

    private String getFallbackRestoreJobId(final BackupVO backup, final VMInstanceVO vm, final String provider, final String volumeUuid) {
        if (!isAblestackHostSideRestoreProvider(provider) || backup == null || vm == null) {
            return null;
        }
        return AblestackBackupFrameworkUtils.createRestoreJobId(provider, backup.getUuid(), vm.getInstanceName(), volumeUuid);
    }

    private void persistCurrentRestoreAsyncJobDetail(final Long backupId, final BackupOffering offering) {
        if (backupId == null || !isAblestackHostSideRestoreProvider(offering)) {
            return;
        }
        try {
            final AsyncJobExecutionContext executionContext = AsyncJobExecutionContext.getCurrentExecutionContext();
            if (executionContext == null || executionContext.getJob() == null) {
                return;
            }
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_ASYNC_JOB_ID_DETAIL,
                    String.valueOf(executionContext.getJob().getId()));
        } catch (Exception e) {
            logger.debug("Skipping restore async job detail persistence for backup [{}]: {}", backupId, e.getMessage());
        }
    }

    private void initializeRestoreOperation(final Long backupId, final BackupOffering offering, final String operationType,
            final Long targetVmId, final String sourceVolumeUuid, final String targetDatastoreUuid) {
        if (backupId == null || !isAblestackHostSideRestoreProvider(offering)) {
            return;
        }
        final GlobalLock restoreLock = GlobalLock.getInternLock("backup.restore." + backupId);
        try {
            if (!restoreLock.lock(5)) {
                throw new CloudRuntimeException("Unable to acquire restore operation lock for backup " + backupId);
            }
            try {
                validateNoActiveRestoreOperation(backupId);
                clearPreviousRestoreResult(backupId);
                persistCurrentRestoreAsyncJobDetail(backupId, offering);
                persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_OPERATION_TYPE_DETAIL, operationType);
                if (targetVmId != null) {
                    persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL, String.valueOf(targetVmId));
                }
                if (StringUtils.isNotBlank(sourceVolumeUuid)) {
                    persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_SOURCE_VOLUME_UUID_DETAIL, sourceVolumeUuid);
                }
                if (StringUtils.isNotBlank(targetDatastoreUuid)) {
                    persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_TARGET_DATASTORE_UUID_DETAIL, targetDatastoreUuid);
                }
                persistRestoreOperationPhase(backupId, "STARTING", "REQUESTED", 5);
            } finally {
                restoreLock.unlock();
            }
        } finally {
            restoreLock.releaseRef();
        }
    }

    private void validateNoActiveRestoreOperation(final Long backupId) {
        final BackupDetailVO cleanupDetail = backupDetailsDao.findDetail(backupId,
                AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL);
        if (cleanupDetail != null && StringUtils.isNotBlank(cleanupDetail.getValue())) {
            throw new CloudRuntimeException("A previous restore job cleanup is still pending for backup " + backupId);
        }
        final BackupDetailVO stateDetail = backupDetailsDao.findDetail(backupId,
                AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        final String state = stateDetail != null ? stateDetail.getValue() : null;
        if ("STARTING".equalsIgnoreCase(state) || "RUNNING".equalsIgnoreCase(state)) {
            throw new CloudRuntimeException("A restore operation is already running for backup " + backupId);
        }
        final BackupDetailVO jobDetail = backupDetailsDao.findDetail(backupId,
                AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
        if (jobDetail == null || StringUtils.isBlank(jobDetail.getValue())) {
            return;
        }
        if (!isRestoreFailureState(state) && !"COMPLETED".equalsIgnoreCase(state)
                && !"CANCELED".equalsIgnoreCase(state) && !"CANCELLED".equalsIgnoreCase(state)) {
            throw new CloudRuntimeException("A restore operation is already running for backup " + backupId);
        }
        backupDetailsDao.removeDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
    }

    private void clearPreviousRestoreResult(final Long backupId) {
        backupDetailsDao.removeDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        backupDetailsDao.removeDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL);
        backupDetailsDao.removeDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL);
        backupDetailsDao.removeDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_FAILURE_REASON_DETAIL);
    }

    private void persistRestoreOperationPhase(final Long backupId, final String state, final String step, final Integer progress) {
        if (backupId == null || backupDetailsDao.findDetail(backupId,
                AblestackBackupFrameworkUtils.RESTORE_OPERATION_TYPE_DETAIL) == null) {
            return;
        }
        persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, state);
        persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL, step);
        if (progress != null) {
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL, String.valueOf(progress));
        }
    }

    private boolean isAblestackHostSideRestoreProvider(final BackupOffering offering) {
        if (offering == null) {
            return false;
        }
        return isAblestackHostSideRestoreProvider(offering.getProvider());
    }

    private boolean isAblestackHostSideRestoreProvider(final String provider) {
        return BackupProviderNameUtils.isNasFamily(provider) ||
                BackupProviderNameUtils.isCommvaultFamily(provider) ||
                BackupProviderNameUtils.isNetBackupFamily(provider) ||
                BackupProviderNameUtils.isVeeamFamily(provider);
    }

    private boolean isTrackedRestoreAsyncJobInProgress(final BackupVO backup) {
        if (backup == null) {
            return false;
        }
        backupDao.loadDetails(backup);
        final String asyncJobId = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_ASYNC_JOB_ID_DETAIL);
        if (!NumberUtils.isDigits(asyncJobId)) {
            return false;
        }
        final AsyncJobVO asyncJob = asyncJobManager.getAsyncJob(Long.parseLong(asyncJobId));
        return asyncJob != null && JobInfo.Status.IN_PROGRESS.equals(asyncJob.getStatus());
    }

    private void cleanupTrackedRestoreJobFilesAndDetails(final BackupVO backup, final VMInstanceVO vm, final String provider) {
        cleanupTrackedRestoreJobFilesAndDetails(backup, vm, provider, null, null);
    }

    private void cleanupTrackedRestoreJobFilesAndDetails(final BackupVO backup, final VMInstanceVO vm, final String provider,
            final String backedUpVolumeUuid, final String restoredVolumeUuid) {
        if (cleanupTrackedRestoreJobFiles(backup, vm, provider, backedUpVolumeUuid, restoredVolumeUuid)) {
            clearTrackedRestoreJobDetails(backup);
        }
    }

    private void clearTrackedRestoreJobDetails(final BackupVO backup) {
        if (backup == null) {
            return;
        }
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_HOST_NAME_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_FAILURE_REASON_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_TRACKED_AT_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_ASYNC_JOB_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_OPERATION_TYPE_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_SOURCE_VOLUME_UUID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_TARGET_DATASTORE_UUID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_TARGET_VOLUME_UUID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_EVENT_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_START_VM_DETAIL);
        backupDao.loadDetails(backup);
    }

    private void stopTrackingActiveRestoreJob(final BackupVO backup) {
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_ASYNC_JOB_ID_DETAIL);
        backupDao.loadDetails(backup);
    }

    private void completeFailedRestoreTracking(final BackupVO backup, final String failureReason) {
        final String reason = StringUtils.defaultIfBlank(failureReason, "Host-side restore job failed");
        persistBackupDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_FAILURE_REASON_DETAIL, reason);
        publishRestoreFailureEvent(backup, reason);
        stopTrackingActiveRestoreJob(backup);
    }

    private void cleanupTerminalRestoreJobFiles(final BackupVO backup, final HostVO restoreHost,
            final String restoreJobId, final String provider) {
        if (cleanupBackupJobFiles(restoreHost.getId(), restoreJobId, provider + " restore")) {
            completeRestoreJobFileCleanup(backup);
        } else {
            persistBackupDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL, restoreJobId);
        }
    }

    private void completeRestoreJobFileCleanup(final BackupVO backup) {
        final String restoreState = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        if (!isRestoreFailureState(restoreState)) {
            clearTrackedRestoreJobDetails(backup);
            return;
        }
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_CLEANUP_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_HOST_NAME_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_JOB_TRACKED_AT_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_OPERATION_TYPE_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_SOURCE_VOLUME_UUID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_TARGET_DATASTORE_UUID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_TARGET_VOLUME_UUID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_EVENT_ID_DETAIL);
        backupDetailsDao.removeDetail(backup.getId(), AblestackBackupFrameworkUtils.RESTORE_START_VM_DETAIL);
        backupDao.loadDetails(backup);
    }

    private void publishRestoreFailureEvent(final BackupVO backup, final String failureReason) {
        final VMInstanceVO vm = findRestoreTargetVm(backup);
        if (vm == null) {
            return;
        }
        final String operationType = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_OPERATION_TYPE_DETAIL);
        final String eventType;
        final String operationDescription;
        if (AblestackBackupFrameworkUtils.RESTORE_OPERATION_VOLUME_ATTACH.equals(operationType)) {
            eventType = EventTypes.EVENT_VM_BACKUP_RESTORE_VOLUME_TO_VM;
            operationDescription = "Backup volume restore and attach failed";
        } else if (AblestackBackupFrameworkUtils.RESTORE_OPERATION_CREATE_INSTANCE.equals(operationType)) {
            eventType = EventTypes.EVENT_VM_CREATE_FROM_BACKUP;
            operationDescription = "Instance creation from backup failed";
        } else {
            eventType = EventTypes.EVENT_VM_BACKUP_RESTORE;
            operationDescription = "Instance backup restore failed";
        }
        try {
            ActionEventUtils.onCompletedActionEvent(User.UID_SYSTEM, vm.getAccountId(), EventVO.LEVEL_ERROR, eventType,
                    String.format("%s for Instance %s: %s", operationDescription, vm.getInstanceName(), failureReason),
                    vm.getId(), ApiCommandResourceType.VirtualMachine.toString(), 0);
        } catch (Exception e) {
            logger.warn("Failed to publish restore failure event for backup [{}]: {}", backup.getUuid(), e.getMessage(), e);
        }
    }

    private VMInstanceVO findRestoreTargetVm(final BackupVO backup) {
        final long targetVmId = NumberUtils.toLong(
                backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_TARGET_VM_ID_DETAIL), backup.getVmId());
        return vmInstanceDao.findByIdIncludingRemoved(targetVmId);
    }

    private void persistBackupDetail(final Long backupId, final String key, final String value) {
        if (backupId == null || StringUtils.isBlank(key) || StringUtils.isBlank(value)) {
            return;
        }
        backupDetailsDao.removeDetail(backupId, key);
        backupDetailsDao.addDetail(backupId, key, value, false);
    }

    private void persistRestoreJobStatusDetails(final Long backupId, final BackupAnswer restoreAnswer) {
        if (restoreAnswer == null) {
            return;
        }
        final String state = restoreAnswer.getState();
        if (StringUtils.isNotBlank(state) && !"UNKNOWN".equalsIgnoreCase(state)) {
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL, state);
        }
        final String step = restoreAnswer.getStep();
        if (StringUtils.isNotBlank(step) && !"UNKNOWN".equalsIgnoreCase(step)) {
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL, step);
        }
        final boolean unknownState = StringUtils.isBlank(state) || "UNKNOWN".equalsIgnoreCase(state);
        final boolean unknownStep = StringUtils.isBlank(step) || "UNKNOWN".equalsIgnoreCase(step);
        if (!unknownState && !unknownStep && restoreAnswer.getProgress() != null && restoreAnswer.getProgress() > 0) {
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL,
                    String.valueOf(restoreAnswer.getProgress()));
        }
        if (isRestoreFailureState(state) && StringUtils.isNotBlank(restoreAnswer.getDetails())) {
            persistBackupDetail(backupId, AblestackBackupFrameworkUtils.RESTORE_JOB_FAILURE_REASON_DETAIL,
                    restoreAnswer.getDetails());
        }
    }

    private boolean applyStoredRestoreJobStatus(final BackupVO backup, final VMInstanceVO vm, final BackupJobStatusResponse response) {
        final String state = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        if (StringUtils.isBlank(state)) {
            return false;
        }
        if (isRestoreFailureState(state)) {
            response.setState(state);
            response.setStep(StringUtils.defaultIfBlank(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL), state));
            final String progress = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL);
            response.setProgress(NumberUtils.isDigits(progress) ? Integer.parseInt(progress) : 0);
            response.setOperation(AblestackBackupFrameworkUtils.OPERATION_RESTORE);
            response.setDetails(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_FAILURE_REASON_DETAIL));
            return true;
        }
        if ("STARTING".equalsIgnoreCase(state) || "RUNNING".equalsIgnoreCase(state)) {
            response.setState(state);
            final String step = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL);
            response.setStep(StringUtils.defaultIfBlank(step, state));
            final String progress = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL);
            response.setProgress(resolveRestoreProgressForResponse(state, step,
                    NumberUtils.isDigits(progress) ? Integer.parseInt(progress) : null));
            response.setOperation(AblestackBackupFrameworkUtils.OPERATION_RESTORE);
            return true;
        }
        if (!isVmRestoreStatePending(vm)) {
            response.setState("COMPLETED");
            response.setStep("COMPLETED");
            response.setProgress(100);
            response.setOperation(AblestackBackupFrameworkUtils.OPERATION_RESTORE);
            return true;
        }
        response.setState(state);
        response.setStep(StringUtils.defaultIfBlank(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL), state));
        final String progress = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_PROGRESS_DETAIL);
        final Integer storedProgress = NumberUtils.isDigits(progress) ? Integer.parseInt(progress) : null;
        response.setProgress(resolveRestoreProgressForResponse(response.getState(), response.getStep(), storedProgress));
        return true;
    }

    private boolean applyPendingRestoreJobStatus(final VMInstanceVO vm, final BackupJobStatusResponse response) {
        if (!isVmRestoreStatePending(vm)) {
            return false;
        }
        response.setState("RUNNING");
        response.setStep("STARTING");
        response.setProgress(null);
        response.setOperation(AblestackBackupFrameworkUtils.OPERATION_RESTORE);
        return true;
    }

    private Integer resolveRestoreProgressForResponse(final String state, final String step, final Integer progress) {
        if (progress != null && progress > 0) {
            return progress;
        }
        if ("COMPLETED".equalsIgnoreCase(state)) {
            return 100;
        }
        if (!"RUNNING".equalsIgnoreCase(state) && !"STARTING".equalsIgnoreCase(state)) {
            return progress;
        }
        switch (StringUtils.defaultString(step).toUpperCase()) {
            case "REQUESTED":
                return 5;
            case "QUEUED":
                return 10;
            case "PREPARE_SOURCE":
                return 15;
            case "VALIDATE_CHAIN":
                return 30;
            case "RESTORE_DATA":
                return 45;
            case "ATTACH_VOLUME":
                return 85;
            case "CLEANUP_SOURCE":
                return 95;
            case "FINALIZING":
                return 95;
            default:
                return null;
        }
    }

    @Override
    public BackupJobStatusResponse getBackupRestoreJobStatus(final Long backupId, final Long eventsOffset, final Integer eventsLimit) {
        final BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup " + backupId + " does not exist");
        }

        backupDao.loadDetails(backup);
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        if (vm != null) {
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, vm);
        } else {
            accountManager.checkAccess(CallContext.current().getCallingAccount(), null, true, backup);
        }

        final BackupJobStatusResponse response = createBackupJobStatusResponse(backup);
        response.setOperation(AblestackBackupFrameworkUtils.OPERATION_RESTORE);
        final String restoreJobId = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
        if (StringUtils.isBlank(restoreJobId)) {
            if (applyStoredRestoreJobStatus(backup, findRestoreTargetVm(backup), response)) {
                return response;
            }
            final String externalRestorePhase = netBackupRestoreCoordinator.getRestorePhase(backup);
            if (StringUtils.isNotBlank(externalRestorePhase)) {
                response.setState(externalRestorePhase);
                response.setStep(externalRestorePhase);
                response.setProgress(resolveRestoreProgressForResponse(externalRestorePhase, externalRestorePhase, null));
            } else if (Backup.Status.Restoring.equals(backup.getStatus())) {
                response.setState("STARTING");
                response.setStep("QUEUED");
                response.setProgress(null);
            } else {
                response.setState("COMPLETED");
                response.setStep("COMPLETED");
                response.setProgress(100);
            }
            return response;
        }

        final HostVO host = findRestoreJobHost(backup, vm);
        if (host == null) {
            if (applyStoredRestoreJobStatus(backup, vm, response)) {
                return response;
            }
            throw new CloudRuntimeException("Unable to find restore host for backup " + backup.getUuid());
        }

        try {
            final Answer answer = agentManager.send(host.getId(), new AblestackRestoreJobStatusCommand(restoreJobId, eventsOffset, eventsLimit));
            if (!(answer instanceof BackupAnswer)) {
                throw new CloudRuntimeException("Unexpected restore job status response from host " + host.getName());
            }
            final BackupAnswer restoreAnswer = (BackupAnswer) answer;
            persistRestoreJobStatusDetails(backup.getId(), restoreAnswer);
            response.setState(restoreAnswer.getState());
            response.setStep(StringUtils.defaultIfBlank(restoreAnswer.getStep(), restoreAnswer.getState()));
            response.setProgress(restoreAnswer.getProgress());
            response.setEventsOffset(restoreAnswer.getEventsOffset());
            response.setEvents(restoreAnswer.getEventsJson());
            response.setLogPath(restoreAnswer.getLogPath());
            response.setExitCode(restoreAnswer.getExitCode());
            response.setOperation(restoreAnswer.getOperation());
            response.setCapabilities(restoreAnswer.getCapabilities());
            response.setDetails(restoreAnswer.getDetails());
            if (!restoreAnswer.getResult()) {
                response.setStep(StringUtils.defaultIfBlank(restoreAnswer.getDetails(), response.getStep()));
            }
            if ("UNKNOWN".equalsIgnoreCase(response.getState()) && applyStoredRestoreJobStatus(backup, vm, response)) {
                response.setOperation(AblestackBackupFrameworkUtils.OPERATION_RESTORE);
            } else if ("UNKNOWN".equalsIgnoreCase(response.getState())) {
                applyPendingRestoreJobStatus(vm, response);
            }
            return response;
        } catch (final Exception e) {
            if (applyStoredRestoreJobStatus(backup, vm, response)) {
                return response;
            }
            if (applyPendingRestoreJobStatus(vm, response)) {
                return response;
            }
            throw new CloudRuntimeException(String.format("Failed to query restore job status for backup [%s] on host [%s]: %s",
                    backup.getUuid(), host.getName(), e.getMessage()), e);
        }
    }

    private HostVO findRestoreJobHost(final BackupVO backup, final VMInstanceVO vm) {
        final String restoreHostId = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_HOST_ID_DETAIL);
        if (NumberUtils.isDigits(restoreHostId)) {
            final HostVO host = hostDao.findById(Long.parseLong(restoreHostId));
            if (host != null) {
                return host;
            }
        }

        final String restoreHostName = StringUtils.defaultIfBlank(
                backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_HOST_NAME_DETAIL),
                netBackupRestoreCoordinator.getRestoreHostName(backup));
        if (StringUtils.isNotBlank(restoreHostName)) {
            final HostVO host = hostDao.findByName(restoreHostName);
            if (host != null) {
                return host;
            }
        }

        if (vm == null) {
            return null;
        }
        final Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        return hostId != null ? hostDao.findById(hostId) : null;
    }

    private BackupJobStatusResponse createBackupJobStatusResponse(final BackupVO backup) {
        final BackupJobStatusResponse response = new BackupJobStatusResponse();
        response.setId(backup.getUuid());
        response.setStatus(backup.getStatus());
        return response;
    }

    private Integer getTerminalBackupProgress(final Backup.Status status) {
        if (Backup.Status.BackedUp.equals(status) || Backup.Status.Canceled.equals(status)) {
            return 100;
        }
        return null;
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
        final boolean externalProvider = offering != null
                && (BackupProviderNameUtils.isNetBackupFamily(offering.getProvider())
                || BackupProviderNameUtils.isVeeamFamily(offering.getProvider()));
        response.setIntervalType(externalProvider ? "EXTERNAL" : "MANUAL");
        if (backup.getCompressionStatus() != null) {
            response.setCompressionStatus(backup.getCompressionStatus());
            if (backup.getUncompressedSize() != null && backup.getUncompressedSize() > 0) {
                response.setUncompressedSize(backup.getUncompressedSize());
            }
        }
        if (backup.getValidationStatus() != null) {
            response.setValidationStatus(backup.getValidationStatus());
        }
        if (backup.getBackupScheduleId() != null) {
            BackupScheduleVO scheduleVO = backupScheduleDao.findById(backup.getBackupScheduleId());
            if (scheduleVO != null) {
                response.setIntervalType(scheduleVO.getScheduleType().toString());
            }
        } else if (offering != null && BackupProviderNameUtils.isVeeamFamily(offering.getProvider())) {
            final Map<String, String> veeamDetails = getDetailsFromBackupDetails(backup.getId());
            final String veeamInterval = veeamDetails.get(ABLESTACK_VEEAM_INTERVAL_TYPE_DETAIL);
            if (StringUtils.isNotBlank(veeamInterval)) {
                response.setIntervalType(veeamInterval);
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
            response.setBackupEngine(getBackupEngineDetail(backup));
        } else {
            response.setVmOfferingRemoved(true);
        }
        populateRestoreJobResponseFields(backup, response);
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

        if (backup.getFromCheckpointId() != null) {
            response.setFromCheckpointId(backup.getFromCheckpointId());
        }
        if (backup.getToCheckpointId() != null) {
            response.setToCheckpointId(backup.getToCheckpointId());
        }

        response.setObjectName("backup");
        return response;
    }

    private String getBackupEngineDetail(final Backup backup) {
        if (backup == null || backup.getDetails() == null || backup.getDetails().isEmpty()) {
            return null;
        }
        return backup.getDetails().entrySet().stream()
                .filter(entry -> StringUtils.endsWith(entry.getKey(), BACKUP_ENGINE_DETAIL_SUFFIX))
                .map(Map.Entry::getValue)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    private void populateRestoreJobResponseFields(final Backup backup, final BackupResponse response) {
        if (backup == null) {
            return;
        }
        if (backup instanceof BackupVO) {
            backupDao.loadDetails((BackupVO) backup);
        }
        final String restoreJobId = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_ID_DETAIL);
        final String storedRestoreState = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        if (StringUtils.isBlank(restoreJobId) && StringUtils.isBlank(storedRestoreState)) {
            return;
        }
        if (StringUtils.isNotBlank(restoreJobId)) {
            response.setRestoreJobId(restoreJobId);
            response.setRestoreJobLogPath(AblestackBackupFrameworkUtils.getAsyncRestoreJobLogPath(restoreJobId));
        }
        final String restoreJobState = getRestoreJobStateForResponse(backup);
        if (StringUtils.isNotBlank(restoreJobState)) {
            response.setRestoreJobState(restoreJobState);
        }
        response.setRestoreJobDetails(backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_FAILURE_REASON_DETAIL));
    }

    private String getRestoreJobStateForResponse(final Backup backup) {
        final String storedState = backup.getDetail(AblestackBackupFrameworkUtils.RESTORE_JOB_STATE_DETAIL);
        if (StringUtils.isNotBlank(storedState)) {
            return storedState;
        }
        if (!isBackupRestoreStatePending(backup)) {
            return "COMPLETED";
        }
        if (backup instanceof BackupVO) {
            final String netBackupRestorePhase = netBackupRestoreCoordinator.getRestorePhase((BackupVO) backup);
            if (StringUtils.isNotBlank(netBackupRestorePhase)) {
                return netBackupRestorePhase;
            }
        }
        return isBackupRestoreStatePending(backup) ? null : "COMPLETED";
    }

    private boolean isBackupRestoreStatePending(final Backup backup) {
        final VMInstanceVO vm = vmInstanceDao.findByIdIncludingRemoved(backup.getVmId());
        return isVmRestoreStatePending(vm);
    }

    private boolean isVmRestoreStatePending(final VMInstanceVO vm) {
        return vm != null && (VirtualMachine.State.Restoring.equals(vm.getState()) ||
                volumeDao.findIncludingRemovedByInstanceAndType(vm.getId(), null).stream()
                        .anyMatch(volume -> Volume.State.Restoring.equals(volume.getState())));
    }

    private boolean isRestoreFailureState(final String restoreState) {
        return "FAILED".equalsIgnoreCase(restoreState)
                || "CANCELED".equalsIgnoreCase(restoreState)
                || "CANCELLED".equalsIgnoreCase(restoreState)
                || "INTERRUPTED".equalsIgnoreCase(restoreState);
    }

    @Override
    public CapacityVO getBackupStorageUsedStats(Long zoneId) {
        if (isDisabled(zoneId)) {
            return new CapacityVO(null, zoneId, null, null, 0L, 0L, Capacity.CAPACITY_TYPE_BACKUP_STORAGE);
        }
        try {
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
        } catch (CloudRuntimeException e) {
            logger.warn("Backup provider unavailable for zone {}: {}", zoneId, e.getMessage());
            return new CapacityVO(null, zoneId, null, null, 0L, 0L, Capacity.CAPACITY_TYPE_BACKUP_STORAGE);
        }
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
