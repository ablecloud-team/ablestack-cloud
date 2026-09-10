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
package com.cloud.dr;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrCapabilitiesAnswer;
import com.cloud.agent.api.FtctlDrCapabilitiesCommand;
import com.cloud.domain.Domain;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrReplicaDiskDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrTestDiskDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.dr.inventory.DrSourceHardwareInventoryService;
import com.cloud.dr.inventory.DrSourceVmHardware;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrTargetMaterializationServiceImpl extends ManagerBase implements DrTargetMaterializationService {
    private static final Logger LOGGER = LogManager.getLogger(DrTargetMaterializationServiceImpl.class);
    private static final Gson GSON = new Gson();
    private static final int STEP_ORDER_RUNTIME_PROJECTION = 30;
    private static final int STEP_ORDER_TEST_ARTIFACTS_READY = 35;
    private static final int STEP_ORDER_TARGET_MATERIALIZATION = 40;
    private static final int STEP_ORDER_BOOT_VALIDATION = 50;
    private static final int STEP_ORDER_TEST_FAILOVER_ACTIVE = 60;
    private static final long GIB = 1024L * 1024L * 1024L;

    @Inject
    private DrPlanDao drPlanDao;
    @Inject
    private DrReplicaDao drReplicaDao;
    @Inject
    private DrReplicaDiskDao drReplicaDiskDao;
    @Inject
    private DrRunDao drRunDao;
    @Inject
    private DrRunStepDao drRunStepDao;
    @Inject
    private DrTestSessionDao drTestSessionDao;
    @Inject
    private DrTestDiskDao drTestDiskDao;
    @Inject
    private DrEventDao drEventDao;
    @Inject
    private DrTargetResourceOwnershipService targetResourceOwnershipService;
    @Inject
    private DrPlanTargetPlacementResolver targetPlacementResolver;
    @Inject
    private DrSourceHardwareInventoryService sourceHardwareInventoryService;
    @Inject
    private AccountDao accountDao;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VolumeOrchestrationService volumeManager;
    @Inject
    private VolumeApiService volumeApiService;
    @Inject
    private UserVmService userVmService;
    @Inject
    private UserVmManager userVmManager;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private AgentManager agentManager;
    @Inject
    private DrWorkerPlacementService drWorkerPlacementService;

    @Override
    public DrTargetPowerOnResult ensureTargetPoweredOn(long planId) {
        DrPlanVO plan = drPlanDao.findById(planId);
        if (plan == null) {
            throw new CloudRuntimeException("DR plan was removed before target power-on");
        }
        DrReplicaVO targetReplica = null;
        for (DrReplicaVO replica : drReplicaDao.listActiveByPlanId(planId)) {
            if (replica != null && replica.getTargetVmId() != null) {
                targetReplica = replica;
                break;
            }
        }
        if (targetReplica == null) {
            throw new CloudRuntimeException("DR target VM is not materialized");
        }
        UserVmVO targetVm = userVmDao.findById(targetReplica.getTargetVmId());
        if (targetVm == null || targetVm.getRemoved() != null) {
            throw new CloudRuntimeException("DR target VM no longer exists");
        }
        Date powerOnAt = new Date();
        boolean alreadyRunning = targetVm.getState() == VirtualMachine.State.Running;
        if (alreadyRunning) {
            return targetPowerOnResult(targetVm, powerOnAt, true);
        }
        if (targetVm.getState() != VirtualMachine.State.Stopped) {
            throw new CloudRuntimeException("DR target VM is not startable from state " + targetVm.getState());
        }
        ensureTargetComputeDetails(plan, targetVm);
        try {
            Long targetHostId = drWorkerPlacementService != null
                    ? drWorkerPlacementService.resolveWorkerHostId(plan, DrWorkerRole.TARGET) : null;
            userVmManager.startVirtualMachine(targetVm.getId(), targetHostId,
                    new HashMap<VirtualMachineProfile.Param, Object>(), null);
        } catch (ConcurrentOperationException | InsufficientCapacityException | ResourceAllocationException | ResourceUnavailableException e) {
            throw new CloudRuntimeException("Failed to start the prepared DR target VM: " + e.getMessage(), e);
        }
        UserVmVO refreshed = userVmDao.findById(targetVm.getId());
        if (refreshed == null || refreshed.getState() != VirtualMachine.State.Running) {
            throw new CloudRuntimeException("DR target VM did not reach Running state after start");
        }
        return targetPowerOnResult(refreshed, powerOnAt, false);
    }

    @Override
    public void ensureTargetPoweredOff(long planId) {
        DrPlanVO plan = drPlanDao.findById(planId);
        if (plan == null) {
            throw new CloudRuntimeException("DR plan was removed before target power-off");
        }
        DrReplicaVO targetReplica = null;
        for (DrReplicaVO replica : drReplicaDao.listActiveByPlanId(planId)) {
            if (replica != null && replica.getTargetVmId() != null) {
                targetReplica = replica;
                break;
            }
        }
        if (targetReplica == null) {
            return;
        }
        UserVmVO targetVm = userVmDao.findById(targetReplica.getTargetVmId());
        if (targetVm == null || targetVm.getRemoved() != null
                || targetVm.getState() == VirtualMachine.State.Stopped) {
            return;
        }
        if (targetVm.getState() != VirtualMachine.State.Running) {
            throw new CloudRuntimeException("DR target VM is not stoppable from state " + targetVm.getState());
        }
        try {
            userVmManager.stopVirtualMachine(targetVm.getId(), true);
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Failed to stop the DR target candidate VM: " + e.getMessage(), e);
        }
        UserVmVO refreshed = userVmDao.findById(targetVm.getId());
        if (refreshed == null || refreshed.getState() != VirtualMachine.State.Stopped) {
            throw new CloudRuntimeException("DR target candidate VM did not reach Stopped state after stop");
        }
    }

    private DrTargetPowerOnResult targetPowerOnResult(UserVmVO targetVm, Date powerOnAt, boolean alreadyRunning) {
        Date validatedAt = new Date();
        return new DrTargetPowerOnResult(targetVm.getId(), targetVm.getUuid(), "POWERED_ON",
                "POWER_STATE_VALIDATED", powerOnAt, validatedAt, alreadyRunning);
    }

    private final Set<Long> inFlightPlans = ConcurrentHashMap.newKeySet();
    private final Set<Long> inFlightTestRuns = ConcurrentHashMap.newKeySet();
    @Inject private DrTestBootValidationService bootValidation;
    private ExecutorService executor;

    @Override
    public boolean start() {
        executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("DrTargetMaterializer"));
        return true;
    }

    @Override
    public boolean stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        inFlightPlans.clear();
        inFlightTestRuns.clear();
        return true;
    }

    @Override
    public boolean enqueueTestMaterialization(final long planId, final long runId, final String runtimeStatusJson) {
        if (!inFlightTestRuns.add(runId)) {
            return false;
        }
        ExecutorService currentExecutor = executor;
        Runnable task = new ManagedContextRunnable() {
            @Override
            protected void runInContext() {
                try {
                    materializeTestTarget(planId, runId, runtimeStatusJson);
                } catch (RuntimeException e) {
                    LOGGER.warn("DR test target materialization task failed before terminal projection for plan {} run {}: {}",
                            planId, runId, e.getMessage(), e);
                } finally {
                    inFlightTestRuns.remove(runId);
                }
            }
        };
        if (currentExecutor == null) {
            task.run();
            return true;
        }
        try {
            currentExecutor.submit(task);
            return true;
        } catch (RejectedExecutionException e) {
            inFlightTestRuns.remove(runId);
            throw new CloudRuntimeException("DR test target materializer is not accepting new work", e);
        }
    }

    @Override
    public boolean isTestTargetActive(long runId) {
        DrTestSessionVO session = drTestSessionDao.findActiveByRunId(runId);
        if (session == null || !StringUtils.equals(session.getState(), DrTestSessionState.ACTIVE) || session.getTargetVmId() == null) {
            return false;
        }
        UserVmVO vm = userVmDao.findById(session.getTargetVmId());
        return vm != null && vm.getRemoved() == null && vm.getState() == VirtualMachine.State.Running
                && (DrTestBootValidationService.requiresQga(session) ? bootValidation.satisfied(session)
                    : "POWER_STATE_VALIDATED".equals(session.getBootValidationState()));
    }

    @Override
    public boolean isTestTargetCleaned(long planId) {
        DrTestSessionVO session = drTestSessionDao.findActiveByPlanId(planId);
        return session == null || StringUtils.equalsAny(session.getState(), "CLOUD_RESOURCES_REMOVED", "CLEANED");
    }

    @Override
    public void completeTestCleanup(long planId) {
        DrTestSessionVO session = drTestSessionDao.findActiveByPlanId(planId);
        if (session == null) {
            return;
        }
        Date removed = new Date();
        session.setState("CLEANED");
        session.setCleanupRequired(false);
        session.setRemoved(removed);
        session.markUpdated();
        drTestSessionDao.update(session.getId(), session);
        for (DrTestDiskVO disk : drTestDiskDao.listActiveBySessionId(session.getId())) {
            disk.setState("CLEANED");
            disk.setRemoved(removed);
            disk.markUpdated();
            drTestDiskDao.update(disk.getId(), disk);
        }
    }

    @Override
    public boolean cleanupTestTarget(long planId, long cleanupRunId) {
        DrTestSessionVO session = drTestSessionDao.findActiveByPlanId(planId);
        if (session == null || StringUtils.equals(session.getState(), "CLEANED")) {
            return true;
        }
        session.setCleanupRunId(cleanupRunId);
        session.setState("CLOUD_CLEANUP_RUNNING");
        session.markUpdated();
        drTestSessionDao.update(session.getId(), session);
        try {
            normalizeTestVolumePaths(session);
            if (session.getTargetVmId() != null) {
                UserVmVO testVm = userVmDao.findById(session.getTargetVmId());
                if (testVm != null && testVm.getRemoved() == null) {
                    userVmService.destroyVm(testVm.getId(), true);
                    UserVmVO destroyedTestVm = userVmDao.findById(testVm.getId());
                    if (destroyedTestVm != null && destroyedTestVm.getRemoved() == null
                            && !userVmManager.expunge(destroyedTestVm)) {
                        throw new CloudRuntimeException("Cloud-managed expunge returned false for DR test VM " + testVm.getUuid());
                    }
                }
            }
            AccountVO owner = resolveOwner(drPlanDao.findById(planId));
            for (DrTestDiskVO disk : drTestDiskDao.listActiveBySessionId(session.getId())) {
                if (disk.getTargetVolumeId() != null) {
                    VolumeVO volume = volumeDao.findById(disk.getTargetVolumeId());
                    if (volume != null && volume.getRemoved() == null) {
                        volumeApiService.destroyVolume(volume.getId(), owner, true, true);
                    }
                }
                disk.setState("CLOUD_VOLUME_REMOVED");
                disk.markUpdated();
                drTestDiskDao.update(disk.getId(), disk);
            }
            session.setState("CLOUD_RESOURCES_REMOVED");
            session.setCleanupRequired(true);
            session.setErrorCode(null);
            session.setErrorMessage(null);
            session.markUpdated();
            drTestSessionDao.update(session.getId(), session);
            return true;
        } catch (ConcurrentOperationException | ResourceUnavailableException e) {
            session.setState("CLEANUP_FAILED");
            session.setErrorCode("DR_TEST_CLOUD_CLEANUP_FAILED");
            session.setErrorMessage(e.getMessage());
            session.markUpdated();
            drTestSessionDao.update(session.getId(), session);
            throw new CloudRuntimeException("Failed to remove Cloud-managed DR test resources: " + e.getMessage(), e);
        }
    }

    @Override
    public void validateReleaseDisposition(long planId, String resourceDisposition) {
        String disposition = normalizeReleaseDisposition(resourceDisposition);
        if (StringUtils.equals(disposition, DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM)) {
            return;
        }
        DrPlanVO plan = drPlanDao.findById(planId);
        if (plan == null || plan.getRemoved() != null) {
            throw new CloudRuntimeException(DrConstants.ERROR_PLAN_NOT_FOUND + ": DR plan was not found");
        }
        if (StringUtils.equalsIgnoreCase(plan.getActiveSide(), DrConstants.AUTHORITY_SIDE_TARGET)
                || StringUtils.equalsIgnoreCase(plan.getState(), DrConstants.PLAN_STATE_FAILED_OVER)) {
            throw new CloudRuntimeException(DrConstants.ERROR_RELEASE_TARGET_AUTHORITY_ACTIVE
                    + ": the target VM currently owns production authority and must be retained");
        }
        for (DrReplicaVO replica : drReplicaDao.listActiveByPlanId(planId)) {
            if (replica == null || replica.getTargetVmId() == null) {
                continue;
            }
            if (plan.getSourceVmId() != null && plan.getSourceVmId().equals(replica.getTargetVmId())) {
                throw new CloudRuntimeException(DrConstants.ERROR_RELEASE_TARGET_NOT_DELETABLE
                        + ": the selected target is also the source VM");
            }
            UserVmVO targetVm = userVmDao.findById(replica.getTargetVmId());
            if (targetVm == null || targetVm.getRemoved() != null) {
                continue;
            }
            targetResourceOwnershipService.assertVmOwnedBy(plan, replica, targetVm);
            if (targetVm.getState() != VirtualMachine.State.Stopped) {
                throw new CloudRuntimeException(DrConstants.ERROR_RELEASE_TARGET_NOT_DELETABLE
                        + ": standby replica VM must be Stopped before deletion; current state=" + targetVm.getState());
            }
        }
    }

    @Override
    public boolean cleanupReleasedStandbyTarget(long planId, long releaseRunId, String resourceDisposition) {
        String disposition = normalizeReleaseDisposition(resourceDisposition);
        if (StringUtils.equals(disposition, DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM)) {
            return true;
        }
        try {
            validateReleaseDisposition(planId, disposition);
            DrPlanVO plan = drPlanDao.findById(planId);
            AccountVO owner = resolveOwner(plan);
            for (DrReplicaVO replica : drReplicaDao.listActiveByPlanId(planId)) {
                if (replica == null || replica.getRemoved() != null) {
                    continue;
                }
                java.util.List<DrReplicaDiskVO> disks = drReplicaDiskDao.listActiveByReplicaId(replica.getId());
                normalizeReplicaVolumePaths(disks);
                if (replica.getTargetVmId() != null) {
                    UserVmVO targetVm = userVmDao.findById(replica.getTargetVmId());
                    if (targetVm != null && targetVm.getRemoved() == null) {
                        targetResourceOwnershipService.assertVmOwnedBy(plan, replica, targetVm);
                        userVmService.destroyVm(targetVm.getId(), true);
                        UserVmVO destroyedTargetVm = userVmDao.findById(targetVm.getId());
                        if (destroyedTargetVm != null && destroyedTargetVm.getRemoved() == null
                                && !userVmManager.expunge(destroyedTargetVm)) {
                            throw new CloudRuntimeException(DrConstants.ERROR_RELEASE_TARGET_NOT_DELETABLE
                                    + ": Cloud-managed expunge returned false for DR standby VM " + targetVm.getUuid());
                        }
                    }
                }
                if (disks != null) {
                    for (DrReplicaDiskVO disk : disks) {
                        if (disk == null || disk.getTargetVolumeId() == null) {
                            continue;
                        }
                        VolumeVO volume = volumeDao.findById(disk.getTargetVolumeId());
                        if (volume != null && volume.getRemoved() == null) {
                            volumeApiService.destroyVolume(volume.getId(), owner, true, true);
                        }
                        disk.setState("CLOUD_VOLUME_REMOVED");
                        disk.markUpdated();
                        drReplicaDiskDao.update(disk.getId(), disk);
                    }
                }
                replica.setState("CLOUD_RESOURCES_REMOVED");
                replica.setOwnershipState("RELEASED_AND_DELETED");
                replica.markUpdated();
                drReplicaDao.update(replica.getId(), replica);
            }
            LOGGER.info("Deleted Cloud-managed standby replica resources for DR plan {} release run {}",
                    plan.getUuid(), releaseRunId);
            return true;
        } catch (ConcurrentOperationException | ResourceUnavailableException e) {
            throw new CloudRuntimeException(DrConstants.ERROR_RELEASE_TARGET_NOT_DELETABLE
                    + ": failed to remove Cloud-managed standby replica resources: " + e.getMessage(), e);
        }
    }

    private String normalizeReleaseDisposition(String resourceDisposition) {
        String disposition = StringUtils.upperCase(StringUtils.defaultIfBlank(resourceDisposition,
                DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM));
        if (!StringUtils.equalsAny(disposition,
                DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM,
                DrConstants.RELEASE_DISPOSITION_DELETE_STANDBY_REPLICA)) {
            throw new CloudRuntimeException(DrConstants.ERROR_RELEASE_DISPOSITION_INVALID
                    + ": unsupported resource disposition " + resourceDisposition);
        }
        return disposition;
    }

    private void normalizeReplicaVolumePaths(java.util.List<DrReplicaDiskVO> disks) {
        if (disks == null) {
            return;
        }
        for (DrReplicaDiskVO disk : disks) {
            if (disk == null || disk.getTargetVolumeId() == null) {
                continue;
            }
            VolumeVO volume = volumeDao.findById(disk.getTargetVolumeId());
            if (volume == null || volume.getRemoved() != null || volume.getPoolId() == null) {
                continue;
            }
            StoragePoolVO pool = primaryDataStoreDao.findById(volume.getPoolId());
            String normalizedPath = cloudVolumePath(firstNonBlank(disk.getTargetDiskRef(), volume.getPath()), pool);
            if (StringUtils.isNotBlank(normalizedPath) && !StringUtils.equals(normalizedPath, volume.getPath())) {
                volume.setPath(normalizedPath);
                volumeDao.update(volume.getId(), volume);
            }
        }
    }

    private void materializeTestTarget(long planId, long runId, String runtimeStatusJson) {
        DrPlanVO plan = drPlanDao.findById(planId);
        DrRunVO run = drRunDao.findById(runId);
        if (plan == null || plan.getRemoved() != null || run == null || run.getRemoved() != null || run.getCompleted() != null) {
            return;
        }
        DrTestSessionVO session = drTestSessionDao.findActiveByRunId(runId);
        if (session != null && session.getCleanupRunId() != null) { return; }
        if (session != null && StringUtils.equals(session.getState(), DrTestSessionState.FAILED)
                && StringUtils.startsWith(session.getErrorCode(), "DR_TEST_QGA_")) {
            failTestMaterializationRun(plan, run, runtimeStatusJson, session.getErrorCode(), session.getErrorMessage());
            return;
        }
        if (session != null && StringUtils.equals(session.getState(), DrTestSessionState.CLOUD_VM_VALIDATING)) {
            reconcileTestBootValidation(plan, run, session, runtimeStatusJson);
            return;
        }
        if (session != null && StringUtils.equals(session.getState(), DrTestSessionState.ACTIVE)) {
            completeTestFailoverRunIfReady(plan, run, session, runtimeStatusJson);
            return;
        }
        if (session == null) {
            throw new CloudRuntimeException("DR_TEST_SESSION_NOT_REQUESTED: test session must be persisted before Agent dispatch");
        }
        JsonObject runtime = parseObject(runtimeStatusJson);
        JsonObject request = parseObject(run.getRequestJson());
        String networkMode = normalizeTestNetworkMode(firstString(request, "networkMode"));
        Long networkId = parseLong(firstString(request, "networkId"));
        session.setNetworkMode(networkMode);
        session.setNetworkId(networkId);
        session.setCheckpointSequence(firstLong(runtime, "test_restore_point_sequence", "current_checkpoint_sequence"));
        session.setRestorePointRef(firstString(runtime, "test_restore_point_ref", "latest_completed_checkpoint_ref"));
        session.setArtifactContractVersion("3");
        session.setDetailsJson(runtimeStatusJson);
        session.setCleanupRequired(true);
        session.setState(DrTestSessionState.CLOUD_VOLUMES_IMPORTING);
        session.markUpdated();
        drTestSessionDao.update(session.getId(), session);
        upsertRunStep(run, "test-artifacts-ready", STEP_ORDER_TEST_ARTIFACTS_READY,
                DrConstants.STEP_STATE_SUCCEEDED, 80, runtimeStatusJson, null, null);
        upsertRunStep(run, "target-materialization", STEP_ORDER_TARGET_MATERIALIZATION,
                DrConstants.STEP_STATE_RUNNING, 85, runtimeStatusJson, null, null);

        try {
            refreshSourceHardwareSnapshot(plan);
            DrResolvedTargetPlacement placement = resolvePlacement(plan, runtime);
            if (placement == null || !placement.getBlockingReasons().isEmpty()) {
                throw new CloudRuntimeException("DR test target placement is not ready");
            }
            Long resolvedNetworkId = applyTestNetwork(placement, networkMode, networkId);
            session.setNetworkId(resolvedNetworkId);
            session.markUpdated();
            drTestSessionDao.update(session.getId(), session);
            JsonArray records = testArtifactRecords(runtime);
            if (records.size() == 0 || records.size() != placement.getDisks().size()) {
                throw new CloudRuntimeException("DR_TEST_ARTIFACT_MANIFEST_INVALID: artifact and mapped disk counts differ");
            }
            String testVmName = testVmName(placement, run);
            placement.setTargetVmName(testVmName);
            AccountVO owner = resolveOwner(plan);
            List<VolumeVO> volumes = new ArrayList<VolumeVO>();
            VolumeVO rootVolume = null;
            int rootIndex = 0;
            for (int index = 0; index < placement.getDisks().size(); index++) {
                DrResolvedDiskMapping disk = placement.getDisks().get(index);
                if (Boolean.TRUE.equals(disk.getBoot())) {
                    rootIndex = index;
                    break;
                }
            }
            for (int index = 0; index < placement.getDisks().size(); index++) {
                DrResolvedDiskMapping disk = placement.getDisks().get(index);
                JsonObject artifact = records.get(index).getAsJsonObject();
                String artifactRef = firstNonBlank(firstString(artifact, "path", "clone"), firstString(artifact, "backing"));
                if (StringUtils.isBlank(artifactRef)) {
                    throw new CloudRuntimeException("DR_TEST_ARTIFACT_MANIFEST_INVALID: missing disk artifact path at index " + index);
                }
                disk.setTargetRef(stripArtifactScheme(artifactRef));
                disk.setTargetName(testVmName + "-disk-" + index);
                if (StringUtils.startsWithIgnoreCase(artifactRef, "rbd:")) {
                    disk.setTargetType("rbd");
                    disk.setTargetFormat("raw");
                }
                boolean root = index == rootIndex;
                VolumeVO volume = ensureImportedVolume(owner, placement, disk, root, root ? 0L : (long) index);
                volumes.add(volume);
                if (root) {
                    rootVolume = volume;
                }
                upsertTestDisk(session, index, artifact, artifactRef, volume);
            }
            if (rootVolume == null) {
                throw new CloudRuntimeException("DR test root volume could not be resolved");
            }
            session.setState(DrTestSessionState.CLOUD_VM_CREATING);
            session.markUpdated();
            drTestSessionDao.update(session.getId(), session);
            UserVmVO testVm = ensureTestVm(plan, placement, owner, rootVolume, runtime, networkMode);
            int device = 1;
            for (int index = 0; index < volumes.size(); index++) {
                if (index == rootIndex) {
                    continue;
                }
                attachDataVolumeIfNeeded(testVm, volumes.get(index), (long) device++);
            }
            vmInstanceDetailsDao.addDetail(testVm.getId(), "dr.test.vm", "true", false);
            vmInstanceDetailsDao.addDetail(testVm.getId(), "dr.test.session.uuid", session.getUuid(), false);
            session.setTargetVmId(testVm.getId());
            session.setTargetVmUuid(testVm.getUuid());
            session.setTargetVmName(testVm.getDisplayName());
            session.setState(DrTestSessionState.CLOUD_VM_STARTING);
            session.markUpdated();
            drTestSessionDao.update(session.getId(), session);
            if (DrTestBootValidationService.requiresQga(session)) { bootValidation.begin(session); }
            if (testVm.getState() != VirtualMachine.State.Running) {
                userVmManager.startVirtualMachine(testVm.getId(), placement.getWorkerHostId(),
                        new HashMap<VirtualMachineProfile.Param, Object>(), null);
            }
            UserVmVO running = userVmDao.findById(testVm.getId());
            if (running == null || running.getState() != VirtualMachine.State.Running) {
                throw new CloudRuntimeException("DR_TEST_VM_BOOT_FAILED: Cloud test VM did not reach Running");
            }
            session.setState(DrTestBootValidationService.requiresQga(session)
                    ? DrTestSessionState.CLOUD_VM_VALIDATING : DrTestSessionState.ACTIVE);
            session.setBootValidationState(DrTestBootValidationService.requiresQga(session) ? "QGA_PENDING" : "POWER_STATE_VALIDATED");
            session.setArtifactManifest(GSON.toJson(records));
            session.setErrorCode(null);
            session.setErrorMessage(null);
            session.markUpdated();
            drTestSessionDao.update(session.getId(), session);
            completeTestFailoverRunIfReady(plan, run, session, runtimeStatusJson);
        } catch (Exception e) {
            String errorCode = testMaterializationErrorCode(e);
            session.setState("FAILED");
            session.setErrorCode(errorCode);
            session.setErrorMessage(e.getMessage());
            session.markUpdated();
            drTestSessionDao.update(session.getId(), session);
            failTestMaterializationRun(plan, run, runtimeStatusJson, errorCode, e.getMessage());
            LOGGER.warn("Failed to materialize Cloud-managed test VM for plan {} run {}: {}", plan.getUuid(), run.getUuid(), e.getMessage(), e);
        }
    }

    private void reconcileTestBootValidation(DrPlanVO plan, DrRunVO run, DrTestSessionVO session, String runtime) {
        String state = bootValidation.state(session);
        if ("PENDING".equals(state)) {
            upsertRunStep(run, "target-materialization", STEP_ORDER_TARGET_MATERIALIZATION,
                    DrConstants.STEP_STATE_SUCCEEDED, 100, runtime, null, null);
            upsertRunStep(run, "boot-validation", STEP_ORDER_BOOT_VALIDATION,
                    DrConstants.STEP_STATE_RUNNING, 90, runtime, null, null);
            return;
        }
        if ("QGA_VALIDATED".equals(state)) {
            if (bootValidation.transition(session, DrTestSessionState.ACTIVE, state, null)) {
                completeTestFailoverRunIfReady(plan, run, drTestSessionDao.findById(session.getId()), runtime);
            }
            return;
        }
        String error = "DR_TEST_" + ("MISSING".equals(state) ? "QGA_EVIDENCE_MISSING" : state);
        if (bootValidation.transition(session, DrTestSessionState.FAILED, state, error)) {
            upsertRunStep(run, "boot-validation", STEP_ORDER_BOOT_VALIDATION,
                    DrConstants.STEP_STATE_FAILED, 100, runtime, error, "Required guest-agent validation failed: " + state);
            failTestMaterializationRun(plan, run, runtime, error, "Required guest-agent validation failed: " + state);
        }
    }

    private void completeTestFailoverRunIfReady(DrPlanVO plan, DrRunVO run, DrTestSessionVO session, String runtimeStatusJson) {
        DrRunVO latestRun = drRunDao.findById(run.getId());
        if (latestRun == null || latestRun.getRemoved() != null || latestRun.getCompleted() != null
                || !StringUtils.equals(session.getState(), DrTestSessionState.ACTIVE)
                || session.getTargetVmId() == null
                || (DrTestBootValidationService.requiresQga(session) && !bootValidation.satisfied(session))) {
            return;
        }
        UserVmVO testVm = userVmDao.findById(session.getTargetVmId());
        if (testVm == null || testVm.getRemoved() != null || testVm.getState() != VirtualMachine.State.Running) {
            return;
        }
        try {
            Date now = new Date();
            upsertRunStep(latestRun, "target-materialization", STEP_ORDER_TARGET_MATERIALIZATION,
                    DrConstants.STEP_STATE_SUCCEEDED, 100, runtimeStatusJson, null, null);
            upsertRunStep(latestRun, "boot-validation", STEP_ORDER_BOOT_VALIDATION,
                    DrConstants.STEP_STATE_SUCCEEDED, 100, runtimeStatusJson, null, null);
            upsertRunStep(latestRun, "test-failover-active", STEP_ORDER_TEST_FAILOVER_ACTIVE,
                    DrConstants.STEP_STATE_SUCCEEDED, 100, runtimeStatusJson, null, null);
            latestRun.setState(DrConstants.RUN_STATE_SUCCEEDED);
            latestRun.setCompleted(now);
            latestRun.setCurrentStepName("test-failover-active");
            latestRun.setEngineAccepted(true);
            if (latestRun.getAcceptedAt() == null) {
                latestRun.setAcceptedAt(now);
            }
            latestRun.setProjectionState("succeeded");
            latestRun.setProjectionChecked(now);
            latestRun.setRetryable(false);
            latestRun.setRetryAfterSeconds(null);
            latestRun.setNextRetryAt(null);
            latestRun.setLastStatusJson(runtimeStatusJson);
            latestRun.setErrorCode(null);
            latestRun.setErrorMessage(null);
            latestRun.markUpdated();
            drRunDao.update(latestRun.getId(), latestRun);
            recordEvent(plan.getId(), latestRun.getId(), DrConstants.EVENT_TEST_VM_ACTIVE,
                    DrConstants.EVENT_SEVERITY_INFO, "Cloud-managed DR test VM is active", runtimeStatusJson);
            recordEvent(plan.getId(), latestRun.getId(), DrConstants.EVENT_RUN_SUCCEEDED,
                    DrConstants.EVENT_SEVERITY_INFO, "DR test failover completed; test environment remains active", runtimeStatusJson);
        } catch (RuntimeException e) {
            LOGGER.warn("Cloud-managed DR test VM is active, but run {} terminal convergence will retry: {}",
                    latestRun.getUuid(), e.getMessage(), e);
        }
    }

    private UserVmVO ensureTestVm(DrPlanVO plan, DrResolvedTargetPlacement placement, AccountVO owner,
            VolumeVO rootVolume, JsonObject runtime, String networkMode) {
        String vmName = placement.getTargetVmName();
        VMInstanceVO existing = vmInstanceDao.findVMByHostNameInZone(vmName, placement.getZoneId());
        if (existing != null && existing.getRemoved() == null) {
            return userVmDao.findById(existing.getId());
        }
        ServiceOfferingVO offering = serviceOfferingDao.findById(parseLong(placement.getServiceOfferingLocalId()));
        HostVO targetHost = hostDao.findById(placement.getWorkerHostId());
        if (offering == null || targetHost == null) {
            throw new CloudRuntimeException("DR test VM offering or target host is unresolved");
        }
        DrResolvedTargetHardware hardware = placement.getTargetHardware();
        if (hardware == null) {
            hardware = new DrTargetHardwareResolver().resolve(plan, guidedSpecFromMapping(plan), placement, runtime);
        }
        Map<String, String> details = buildTargetVmDetails(plan, placement, offering, rootVolume, hardware);
        details.put("dr.replica.vm", "false");
        details.put("dr.test.vm", "true");
        List<Long> networks = StringUtils.equals(networkMode, "NO_NIC") ? new ArrayList<Long>() : networkIds(placement);
        DrReplicaDeployVMVolumeCmd cmd = new DrReplicaDeployVMVolumeCmd(owner.getId(), owner.getAccountName(), owner.getDomainId(),
                placement.getZoneId(), offering.getId(), vmName, vmName, networks, targetHost.getId(), HypervisorType.KVM,
                rootVolume.getId(), details, hardware);
        try {
            CallContext.registerSystemCallContextOnceOnly();
            UserVm created = userVmService.createVirtualMachineVolume(cmd);
            UserVmVO result = created != null ? userVmDao.findById(created.getId()) : null;
            if (result == null) {
                throw new CloudRuntimeException("CloudStack returned no VM for DR test materialization");
            }
            return result;
        } catch (InsufficientCapacityException | ResourceUnavailableException | ConcurrentOperationException |
                ResourceAllocationException e) {
            throw new CloudRuntimeException("Failed to create Cloud-managed DR test VM: " + e.getMessage(), e);
        } finally {
            CallContext.unregister();
        }
    }

    Long applyTestNetwork(DrResolvedTargetPlacement placement, String networkMode, Long networkId) {
        if (StringUtils.equals(networkMode, "NO_NIC")) {
            placement.getNetworks().clear();
            return null;
        }
        DrResolvedNetworkMapping selected = null;
        if (networkId != null) {
            selected = new DrResolvedNetworkMapping();
            selected.setNetworkLocalId(String.valueOf(networkId));
            selected.setNetworkId(String.valueOf(networkId));
        } else {
            for (DrResolvedNetworkMapping mapped : placement.getNetworks()) {
                if (parseLong(mapped.getNetworkLocalId()) == null) {
                    continue;
                }
                if (selected == null || StringUtils.equalsIgnoreCase(mapped.getRole(), "default")) {
                    selected = mapped;
                }
                if (StringUtils.equalsIgnoreCase(mapped.getRole(), "default")) {
                    break;
                }
            }
        }
        if (selected == null) {
            throw new CloudRuntimeException("DR_TEST_NETWORK_REQUIRED: networkid is required for Cloud-managed Test Failover");
        }
        Long resolvedNetworkId = parseLong(selected.getNetworkLocalId());
        placement.getNetworks().clear();
        placement.addNetwork(selected);
        return resolvedNetworkId;
    }

    private String testMaterializationErrorCode(Exception error) {
        String message = error != null ? StringUtils.trimToEmpty(error.getMessage()) : "";
        int separator = message.indexOf(':');
        String candidate = separator > 0 ? message.substring(0, separator) : message;
        return StringUtils.startsWith(candidate, "DR_")
                ? candidate : "DR_TEST_CLOUD_MATERIALIZATION_FAILED";
    }

    private void failTestMaterializationRun(DrPlanVO plan, DrRunVO run, String runtimeStatusJson,
            String errorCode, String errorMessage) {
        DrRunVO latestRun = drRunDao.findById(run.getId());
        if (latestRun == null || latestRun.getRemoved() != null || latestRun.getCompleted() != null) {
            return;
        }
        String message = StringUtils.defaultIfBlank(errorMessage, "Cloud-managed DR test VM materialization failed");
        String details = failureDetailsJson(runtimeStatusJson, errorCode, message);
        boolean bootFailure = StringUtils.startsWith(errorCode, "DR_TEST_QGA_");
        String failedStep = bootFailure ? "boot-validation" : "target-materialization";
        upsertRunStep(latestRun, failedStep, bootFailure ? STEP_ORDER_BOOT_VALIDATION : STEP_ORDER_TARGET_MATERIALIZATION,
                DrConstants.STEP_STATE_FAILED, 100, details, errorCode, message);
        latestRun.setState(DrConstants.RUN_STATE_FAILED);
        latestRun.setCompleted(new Date());
        latestRun.setCurrentStepName(failedStep);
        latestRun.setProjectionState("failed");
        latestRun.setProjectionChecked(new Date());
        latestRun.setRetryable(false);
        latestRun.setRetryAfterSeconds(null);
        latestRun.setNextRetryAt(null);
        latestRun.setLastStatusJson(details);
        latestRun.setErrorCode(errorCode);
        latestRun.setErrorMessage(message);
        latestRun.markUpdated();
        drRunDao.update(latestRun.getId(), latestRun);
        closeOpenRunSteps(latestRun, errorCode, message);
        recordEvent(plan.getId(), latestRun.getId(), DrConstants.EVENT_RUN_FAILED,
                DrConstants.EVENT_SEVERITY_ERROR, message, details);
    }

    private JsonArray testArtifactRecords(JsonObject runtime) {
        JsonObject session = objectAt(runtime, "test_session");
        if (session.entrySet().isEmpty()) {
            session = objectAt(runtime, "testSession");
        }
        JsonObject artifacts = objectAt(session, "testArtifacts");
        if (artifacts.entrySet().isEmpty()) {
            artifacts = objectAt(runtime, "testArtifacts");
        }
        JsonElement records = artifacts.get("records");
        return records != null && records.isJsonArray() ? records.getAsJsonArray() : new JsonArray();
    }

    private void upsertTestDisk(DrTestSessionVO session, int index, JsonObject artifact, String artifactRef, VolumeVO volume) {
        DrTestDiskVO row = null;
        for (DrTestDiskVO candidate : drTestDiskDao.listActiveBySessionId(session.getId())) {
            if (candidate.getDiskIndex() == index) {
                row = candidate;
                break;
            }
        }
        boolean create = row == null;
        if (create) {
            row = new DrTestDiskVO(session.getId(), index);
        }
        row.setProvider(StringUtils.startsWithIgnoreCase(artifactRef, "rbd:") ? "RBD" : "QCOW2");
        row.setArtifactRef(artifactRef);
        row.setTargetVolumeId(volume.getId());
        row.setTargetVolumeUuid(volume.getUuid());
        row.setState("CLOUD_VOLUME_READY");
        row.setDetailsJson(GSON.toJson(artifact));
        row.markUpdated();
        if (create) {
            drTestDiskDao.persist(row);
        } else {
            drTestDiskDao.update(row.getId(), row);
        }
    }

    private String normalizeTestNetworkMode(String value) {
        String normalized = StringUtils.upperCase(StringUtils.defaultIfBlank(value, "ISOLATED_NETWORK"));
        if (StringUtils.equals(normalized, "ISOLATED")) return "ISOLATED_NETWORK";
        if (StringUtils.equals(normalized, "PRODUCTION")) return "PRODUCTION_NETWORK";
        if (!StringUtils.equalsAny(normalized, "ISOLATED_NETWORK", "PRODUCTION_NETWORK", "NO_NIC")) {
            throw new CloudRuntimeException("Unsupported DR test network mode: " + value);
        }
        return normalized;
    }

    private String stripArtifactScheme(String value) {
        return StringUtils.startsWithIgnoreCase(value, "rbd:") ? value.substring(4) : value;
    }

    private String testVmName(DrResolvedTargetPlacement placement, DrRunVO run) {
        String base = StringUtils.defaultIfBlank(placement.getTargetVmName(), "dr-test");
        String suffix = StringUtils.substring(StringUtils.defaultString(run.getUuid()).replace("-", ""), 0, 8);
        return StringUtils.substring(base + "-test-" + suffix, 0, 63);
    }

    @Override
    public boolean prepareSyncTarget(long planId, long runId) {
        DrPlanVO plan = drPlanDao.findById(planId);
        DrRunVO run = drRunDao.findById(runId);
        if (plan == null || plan.getRemoved() != null || run == null || run.getRemoved() != null) {
            return false;
        }
        if (!StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)
                || plan.getSourceVmId() != null || StringUtils.isBlank(plan.getSourceExternalRef())) {
            return true;
        }
        MaterializationResult result = materializeTarget(plan, run, new JsonObject(), false);
        return result != null && result.targetVmId > 0L && StringUtils.isNotBlank(result.targetVolumeMapJson);
    }

    @Override
    public boolean enqueueMaterialization(final long planId, final long runId, final String runtimeStatusJson) {
        return enqueueMaterialization(planId, runId, runtimeStatusJson, false);
    }

    @Override
    public boolean enqueueDurableReconciliation(final long planId, final long runId, final String runtimeStatusJson) {
        return enqueueMaterialization(planId, runId, runtimeStatusJson, true);
    }

    private boolean enqueueMaterialization(final long planId, final long runId, final String runtimeStatusJson,
            final boolean allowCompletedRun) {
        if (!inFlightPlans.add(planId)) {
            return false;
        }
        ExecutorService currentExecutor = executor;
        if (currentExecutor == null) {
            try {
                materialize(planId, runId, runtimeStatusJson, allowCompletedRun);
            } finally {
                inFlightPlans.remove(planId);
            }
            return true;
        }
        try {
            currentExecutor.submit(new ManagedContextRunnable() {
                @Override
                protected void runInContext() {
                    try {
                        materialize(planId, runId, runtimeStatusJson, allowCompletedRun);
                    } finally {
                        inFlightPlans.remove(planId);
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            inFlightPlans.remove(planId);
            throw new CloudRuntimeException("DR target materializer is not accepting new work", e);
        }
    }

    private void materialize(long planId, long runId, String runtimeStatusJson) {
        materialize(planId, runId, runtimeStatusJson, false);
    }

    private void materialize(long planId, long runId, String runtimeStatusJson, boolean allowCompletedRun) {
        DrPlanVO plan = drPlanDao.findById(planId);
        DrRunVO run = drRunDao.findById(runId);
        if (plan == null || plan.getRemoved() != null || run == null || run.getRemoved() != null
                || (!allowCompletedRun && run.getCompleted() != null)) {
            return;
        }
        if (!isMaterializationOwnerRun(plan, run)) {
            LOGGER.debug("Ignoring DR target materialization for plan {} run {} type {} active side {}",
                    plan.getUuid(), run.getUuid(), run.getRunType(), plan.getActiveSide());
            return;
        }
        if (!StringUtils.endsWithIgnoreCase(plan.getDirection(), "_KVM")) {
            return;
        }
        JsonObject runtime = parseObject(runtimeStatusJson);
        if (!hasDurableCheckpoint(plan, runtime)) {
            return;
        }
        try {
            upsertRunStep(run, "target-materialization", STEP_ORDER_TARGET_MATERIALIZATION, DrConstants.STEP_STATE_RUNNING, 96,
                    runtimeStatusJson, null, "Materializing target VM from durable FTCTL_DR checkpoint");
            MaterializationResult result = materializeTarget(plan, run, runtime, true);
            notifyFtctlTargetMaterialized(plan, run, result);
            completeMaterialization(plan.getId(), run.getId(), result, runtimeStatusJson);
            LOGGER.info("Materialized DR target VM {} for plan {}", result.targetVmId, plan.getUuid());
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to materialize DR target for plan {}: {}", plan.getUuid(), e.getMessage(), e);
            failMaterialization(plan, run, runtimeStatusJson, e);
        }
    }

    boolean isMaterializationOwnerRun(DrPlanVO plan, DrRunVO run) {
        return plan != null && run != null
                && StringUtils.equalsAnyIgnoreCase(run.getRunType(),
                        DrConstants.RUN_TYPE_SYNC, DrConstants.RUN_TYPE_RECOVER_SYNC)
                && StringUtils.equalsIgnoreCase(StringUtils.defaultIfBlank(plan.getActiveSide(),
                        DrConstants.AUTHORITY_SIDE_SOURCE), DrConstants.AUTHORITY_SIDE_SOURCE)
                && !StringUtils.equalsIgnoreCase(plan.getState(), DrConstants.PLAN_STATE_FAILED_OVER);
    }

    private MaterializationResult materializeTarget(DrPlanVO plan, DrRunVO run, JsonObject runtime) {
        return materializeTarget(plan, run, runtime, true);
    }

    private MaterializationResult materializeTarget(DrPlanVO plan, DrRunVO run, JsonObject runtime, boolean durable) {
        refreshSourceHardwareSnapshot(plan);
        DrReplicaVO replica = firstActiveReplica(plan);
        if (replica == null) {
            throw new CloudRuntimeException("DR target materialization requires a prepared replica row");
        }
        if (replica.getTargetVmId() != null) {
            UserVmVO existing = userVmDao.findById(replica.getTargetVmId());
            if (existing != null && existing.getRemoved() == null) {
                targetResourceOwnershipService.claimVm(plan, replica, run, existing);
                reconcileSourceVmDetails(plan, existing);
                verifyTargetVmHardware(plan, existing);
                recordTargetTuningDifferences(plan, run, existing);
                observeReplicaPowerState(replica, existing);
                List<DrReplicaDiskVO> existingDisks = drReplicaDiskDao.listActiveByReplicaId(replica.getId());
                for (DrReplicaDiskVO disk : existingDisks) {
                    disk.setState(durable ? DrConstants.REPLICA_STATE_READY : DrConstants.REPLICA_STATE_SKELETON_READY);
                    disk.markUpdated();
                    drReplicaDiskDao.update(disk.getId(), disk);
                }
                replica.setState(durable ? DrConstants.REPLICA_STATE_READY : DrConstants.REPLICA_STATE_SKELETON_READY);
                replica.setOwnershipState("VALID");
                replica.markUpdated();
                drReplicaDao.update(replica.getId(), replica);
                List<VolumeVO> existingVolumes = new ArrayList<VolumeVO>();
                for (DrReplicaDiskVO disk : existingDisks) {
                    if (disk.getTargetVolumeId() != null) {
                        VolumeVO volume = volumeDao.findById(disk.getTargetVolumeId());
                        if (volume != null && volume.getRemoved() == null) {
                            existingVolumes.add(volume);
                        }
                    }
                }
                updatePlanMapping(plan, existing, existingVolumes);
                return buildResult(plan, replica, existing, existingDisks);
            }
        }

        DrResolvedTargetPlacement placement = resolvePlacement(plan, runtime);
        if (placement == null) {
            throw new CloudRuntimeException("DR target placement is not available for plan " + plan.getUuid());
        }
        if (!placement.getBlockingReasons().isEmpty()) {
            throw new CloudRuntimeException("DR target placement is not ready: " + StringUtils.join(placement.getBlockingReasons(), ","));
        }
        AccountVO owner = resolveOwner(plan);
        DrResolvedDiskMapping rootDisk = resolveRootDisk(placement);
        List<DrReplicaDiskVO> replicaDisks = drReplicaDiskDao.listActiveByReplicaId(replica.getId());
        DrReplicaDiskVO rootReplicaDisk = requireReplicaDisk(replicaDisks, rootDisk);
        VolumeVO rootVolume = ensureImportedVolume(plan, replica, rootReplicaDisk, run, owner, placement, rootDisk, true, 0L);
        updateReplicaDisk(replicaDisks, rootDisk, rootVolume, DrConstants.REPLICA_STATE_SKELETON_READY);
        UserVmVO targetVm = ensureTargetVm(plan, replica, run, placement, owner, rootVolume, runtime);
        verifyTargetVmHardware(plan, targetVm);

        List<VolumeVO> importedVolumes = new ArrayList<VolumeVO>();
        importedVolumes.add(rootVolume);
        int device = 1;
        for (DrResolvedDiskMapping disk : placement.getDisks()) {
            if (disk == rootDisk) {
                updateReplicaDisk(replicaDisks, disk, rootVolume,
                        durable ? DrConstants.REPLICA_STATE_READY : DrConstants.REPLICA_STATE_SKELETON_READY);
                continue;
            }
            DrReplicaDiskVO dataReplicaDisk = requireReplicaDisk(replicaDisks, disk);
            VolumeVO dataVolume = ensureImportedVolume(plan, replica, dataReplicaDisk, run, owner, placement, disk, false, (long) device);
            attachDataVolumeIfNeeded(targetVm, dataVolume, (long) device);
            updateReplicaDisk(replicaDisks, disk, dataVolume,
                    durable ? DrConstants.REPLICA_STATE_READY : DrConstants.REPLICA_STATE_SKELETON_READY);
            importedVolumes.add(dataVolume);
            device++;
        }

        replica.setTargetVmId(targetVm.getId());
        replica.setTargetExternalRef(targetVm.getUuid());
        replica.setTargetVmName(targetVm.getDisplayName());
        replica.setState(durable ? DrConstants.REPLICA_STATE_READY : DrConstants.REPLICA_STATE_SKELETON_READY);
        observeReplicaPowerState(replica, targetVm);
        replica.setHypervisorType(DrConstants.HYPERVISOR_TYPE_KVM);
        replica.setActiveSide("TARGET");
        replica.setOwnershipState("VALID");
        replica.setRuntimeStateJson(buildReplicaRuntimeJson(plan, targetVm, importedVolumes, runtime));
        replica.markUpdated();
        drReplicaDao.update(replica.getId(), replica);

        updatePlanMapping(plan, targetVm, importedVolumes);
        return buildResult(plan, replica, targetVm, drReplicaDiskDao.listActiveByReplicaId(replica.getId()));
    }

    private DrReplicaVO firstActiveReplica(DrPlanVO plan) {
        List<DrReplicaVO> replicas = drReplicaDao.listActiveByPlanId(plan.getId());
        return replicas != null && !replicas.isEmpty() ? replicas.get(0) : null;
    }

    private DrResolvedTargetPlacement resolvePlacement(DrPlanVO plan, JsonObject runtime) {
        DrPlanGuidedSpec spec = guidedSpecFromMapping(plan);
        DrResolvedTargetPlacement placement = targetPlacementResolver != null ? targetPlacementResolver.resolve(plan, spec) : null;
        if (placement != null) {
            new DrTargetHardwareResolver().resolve(plan, spec, placement, runtime);
        }
        return placement;
    }

    private DrPlanGuidedSpec guidedSpecFromMapping(DrPlanVO plan) {
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject target = objectAt(mapping, "target");
        DrPlanGuidedSpec spec = new DrPlanGuidedSpec();
        spec.setGuidedPlan(true);
        spec.setTargetVmName(firstNonBlank(firstString(mapping, "targetVmName", "targetName"), firstString(target, "vmName", "name")));
        spec.setTargetZoneId(firstNonBlank(firstString(mapping, "targetZoneId"), firstString(target, "zoneId", "zone")));
        spec.setTargetStorageRef(firstNonBlank(firstString(mapping, "targetStorageRef", "targetDatastoreRef"),
                firstString(target, "storageRef", "storagePoolId", "targetStorageRef")));
        spec.setTargetComputeRef(firstNonBlank(firstString(mapping, "targetComputeRef", "serviceOfferingId"),
                firstString(target, "serviceOfferingId", "serviceOfferingRef", "computeOfferingId")));
        spec.setTargetCpuNumber(firstInteger(mapping, target, "targetCpuNumber", "cpuNumber"));
        spec.setTargetCpuSpeed(firstInteger(mapping, target, "targetCpuSpeed", "cpuSpeed"));
        spec.setTargetMemory(firstInteger(mapping, target, "targetMemory", "memory"));
        JsonObject targetHardware = objectAt(target, "hardware");
        spec.setTargetBootType(firstNonBlank(firstString(mapping, "targetBootType", "boottype"),
                firstString(targetHardware, "bootType", "boottype")));
        spec.setTargetBootMode(firstNonBlank(firstString(mapping, "targetBootMode", "bootmode"),
                firstString(targetHardware, "bootMode", "bootmode")));
        spec.setTargetRootDiskController(firstNonBlank(firstString(mapping, "targetRootDiskController"),
                firstString(targetHardware, "rootDiskController")));
        spec.setTargetDataDiskController(firstNonBlank(firstString(mapping, "targetDataDiskController"),
                firstString(targetHardware, "dataDiskController")));
        spec.setTargetIoThreadsEnabled(firstBoolean(mapping, "targetIoThreadsEnabled", "iothreadsEnabled"));
        if (spec.getTargetIoThreadsEnabled() == null) {
            spec.setTargetIoThreadsEnabled(firstBoolean(targetHardware, "ioThreadsEnabled", "iothreadsEnabled"));
        }
        spec.setTargetIoPolicy(firstNonBlank(firstString(mapping, "targetIoPolicy", "ioPolicy", "io.policy"),
                firstString(targetHardware, "ioPolicy", "io.policy")));
        spec.setTargetNetworkRef(firstNonBlank(firstString(mapping, "targetNetworkRef", "networkRef"), networkRefsFromTarget(target)));
        spec.setTargetFolderPath(firstNonBlank(firstString(mapping, "targetFolderPath", "folderPath"), firstString(target, "folderPath")));
        JsonArray disks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        if (disks.size() > 0) {
            spec.setDiskMappingsJson(disks.toString());
        }
        return spec;
    }

    private String networkRefsFromTarget(JsonObject target) {
        JsonArray networks = firstArray(target, "networks", "networkRefs", "networkMappings");
        if (networks.size() == 0) {
            return null;
        }
        List<String> refs = new ArrayList<String>();
        for (JsonElement element : networks) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            String ref = firstString(element.getAsJsonObject(), "networkId", "networkLocalId", "networkRef", "id", "uuid");
            if (StringUtils.isNotBlank(ref)) {
                refs.add(ref);
            }
        }
        return refs.isEmpty() ? null : StringUtils.join(refs, ',');
    }

    private AccountVO resolveOwner(DrPlanVO plan) {
        if (plan.getSourceVmId() != null) {
            UserVmVO sourceVm = userVmDao.findById(plan.getSourceVmId());
            if (sourceVm != null) {
                AccountVO owner = accountDao.findById(sourceVm.getAccountId());
                if (owner != null) {
                    return owner;
                }
            }
        }
        Account admin = accountDao.findActiveAccount("admin", Domain.ROOT_DOMAIN);
        if (admin != null) {
            AccountVO adminVo = accountDao.findById(admin.getId());
            if (adminVo != null) {
                return adminVo;
            }
        }
        AccountVO system = accountDao.findById(Account.ACCOUNT_ID_SYSTEM);
        if (system == null) {
            throw new CloudRuntimeException("Unable to resolve owner account for DR target VM materialization");
        }
        return system;
    }

    private DrResolvedDiskMapping resolveRootDisk(DrResolvedTargetPlacement placement) {
        if (placement.getDisks().isEmpty()) {
            throw new CloudRuntimeException("DR target materialization requires at least one disk mapping");
        }
        for (DrResolvedDiskMapping disk : placement.getDisks()) {
            if (Boolean.TRUE.equals(disk.getBoot())) {
                return disk;
            }
        }
        return placement.getDisks().get(0);
    }

    private VolumeVO ensureImportedVolume(DrPlanVO plan, DrReplicaVO replica, DrReplicaDiskVO replicaDisk, DrRunVO run,
            AccountVO owner, DrResolvedTargetPlacement placement, DrResolvedDiskMapping disk, boolean root, Long deviceId) {
        StoragePoolVO pool = storagePoolForDisk(placement, disk);
        DiskOfferingVO offering = diskOfferingForDisk(disk);
        String volumeName = volumeNameForDisk(placement, disk, root, deviceId);
        String path = cloudVolumePath(StringUtils.defaultIfBlank(disk.getTargetRef(), volumeName), pool);
        VolumeVO existing = findExistingVolume(pool, path, volumeName);
        if (existing != null) {
            if (plan != null && replica != null && replicaDisk != null) {
                targetResourceOwnershipService.claimVolume(plan, replica, replicaDisk, run, existing, path);
            }
            normalizeImportedVolume(existing, disk, root, deviceId, owner, pool, offering, path, disk.getCapacityBytes());
            return verifyImportedVolumeFormat(volumeDao.findById(existing.getId()), disk, pool);
        }
        Long sizeBytes = positiveLong(disk.getCapacityBytes());
        DiskProfile profile = volumeManager.importVolume(root ? Volume.Type.ROOT : Volume.Type.DATADISK,
                volumeName, offering, sizeBytes, null, null,
                placement.getZoneId(), HypervisorType.KVM, null, null, owner, deviceId,
                pool.getId(), pool.getPoolType(), path, buildChainInfo(disk, pool));
        VolumeVO volume = volumeDao.findById(profile.getVolumeId());
        if (volume == null) {
            throw new CloudRuntimeException("Imported DR target volume could not be reloaded: " + volumeName);
        }
        normalizeImportedVolume(volume, disk, root, deviceId, owner, pool, offering, path, disk.getCapacityBytes());
        if (plan != null && replica != null && replicaDisk != null) {
            targetResourceOwnershipService.claimVolume(plan, replica, replicaDisk, run, volume, path);
        }
        return verifyImportedVolumeFormat(volumeDao.findById(volume.getId()), disk, pool);
    }

    private VolumeVO ensureImportedVolume(AccountVO owner, DrResolvedTargetPlacement placement,
            DrResolvedDiskMapping disk, boolean root, Long deviceId) {
        return ensureImportedVolume(null, null, null, null, owner, placement, disk, root, deviceId);
    }

    private void normalizeTestVolumePaths(DrTestSessionVO session) {
        for (DrTestDiskVO disk : drTestDiskDao.listActiveBySessionId(session.getId())) {
            if (disk.getTargetVolumeId() == null || !StringUtils.equalsIgnoreCase(disk.getProvider(), "RBD")) {
                continue;
            }
            VolumeVO volume = volumeDao.findById(disk.getTargetVolumeId());
            if (volume == null || volume.getRemoved() != null || volume.getPoolId() == null) {
                continue;
            }
            StoragePoolVO pool = primaryDataStoreDao.findById(volume.getPoolId());
            String normalizedPath = cloudVolumePath(firstNonBlank(disk.getArtifactRef(), volume.getPath()), pool);
            if (StringUtils.isNotBlank(normalizedPath) && !StringUtils.equals(normalizedPath, volume.getPath())) {
                volume.setPath(normalizedPath);
                volumeDao.update(volume.getId(), volume);
            }
        }
    }

    private String cloudVolumePath(String artifactRef, StoragePoolVO pool) {
        String path = stripArtifactScheme(artifactRef);
        if (pool == null || pool.getPoolType() != Storage.StoragePoolType.RBD || StringUtils.isBlank(path)) {
            return path;
        }
        String poolPath = StringUtils.strip(pool.getPath(), "/");
        String prefix = StringUtils.isBlank(poolPath) ? null : poolPath + "/";
        return prefix != null && StringUtils.startsWith(path, prefix) ? StringUtils.removeStart(path, prefix) : path;
    }

    private StoragePoolVO storagePoolForDisk(DrResolvedTargetPlacement placement, DrResolvedDiskMapping disk) {
        Long poolId = parseLong(firstNonBlank(disk.getTargetStorageLocalId(), placement.getStorageLocalId()));
        if (poolId == null) {
            throw new CloudRuntimeException("Target storage pool is unresolved for disk " + disk.getLabel());
        }
        StoragePoolVO pool = primaryDataStoreDao.findById(poolId);
        if (pool == null || pool.getRemoved() != null) {
            throw new CloudRuntimeException("Target storage pool was not found: " + poolId);
        }
        return pool;
    }

    private DiskOfferingVO diskOfferingForDisk(DrResolvedDiskMapping disk) {
        Long offeringId = parseLong(disk.getTargetDiskOfferingLocalId());
        if (offeringId == null) {
            throw new CloudRuntimeException("Target disk offering is unresolved for disk " + disk.getLabel());
        }
        DiskOfferingVO offering = diskOfferingDao.findById(offeringId);
        if (offering == null || offering.getRemoved() != null) {
            throw new CloudRuntimeException("Target disk offering was not found: " + offeringId);
        }
        return offering;
    }

    private String volumeNameForDisk(DrResolvedTargetPlacement placement, DrResolvedDiskMapping disk, boolean root, Long deviceId) {
        String targetName = StringUtils.trimToNull(disk.getTargetName());
        if (targetName != null) {
            return targetName;
        }
        String targetRef = StringUtils.trimToNull(disk.getTargetRef());
        if (targetRef != null) {
            return targetRef;
        }
        String vmName = StringUtils.defaultIfBlank(placement.getTargetVmName(), "dr-target");
        return root ? vmName + "-root" : vmName + "-disk-" + deviceId;
    }

    private VolumeVO findExistingVolume(StoragePoolVO pool, String path, String name) {
        if (pool == null) {
            return null;
        }
        VolumeVO byPath = StringUtils.isNotBlank(path) ? volumeDao.findByPoolIdName(pool.getId(), path) : null;
        if (byPath != null && byPath.getRemoved() == null) {
            return byPath;
        }
        VolumeVO byName = StringUtils.isNotBlank(name) ? volumeDao.findByPoolIdName(pool.getId(), name) : null;
        return byName != null && byName.getRemoved() == null ? byName : null;
    }

    private void normalizeImportedVolume(VolumeVO volume, DrResolvedDiskMapping disk, boolean root, Long deviceId, AccountVO owner, StoragePoolVO pool,
            DiskOfferingVO offering, String path, String capacityBytes) {
        boolean changed = false;
        Volume.Type expectedType = root ? Volume.Type.ROOT : Volume.Type.DATADISK;
        if (volume.getVolumeType() != expectedType) {
            volume.setVolumeType(expectedType);
            changed = true;
        }
        Long expectedDeviceId = root ? 0L : deviceId;
        if (expectedDeviceId != null && !expectedDeviceId.equals(volume.getDeviceId())) {
            volume.setDeviceId(expectedDeviceId);
            changed = true;
        }
        if (!Volume.State.Ready.equals(volume.getState())) {
            volume.setState(Volume.State.Ready);
            changed = true;
        }
        Long sizeBytes = positiveLong(capacityBytes);
        if (sizeBytes != null && !sizeBytes.equals(volume.getSize())) {
            volume.setSize(sizeBytes);
            changed = true;
        }
        if (pool != null && !Long.valueOf(pool.getId()).equals(volume.getPoolId())) {
            volume.setPoolId(pool.getId());
            volume.setPoolType(pool.getPoolType());
            changed = true;
        }
        if (offering != null && !Long.valueOf(offering.getId()).equals(volume.getDiskOfferingId())) {
            volume.setDiskOfferingId(offering.getId());
            changed = true;
        }
        if (StringUtils.isNotBlank(path) && !StringUtils.equals(volume.getPath(), path)) {
            volume.setPath(path);
            changed = true;
        }
        if (owner != null && volume.getAccountId() != owner.getId()) {
            volume.setAccountId(owner.getId());
            volume.setDomainId(owner.getDomainId());
            changed = true;
        }
        Storage.ImageFormat expectedFormat = expectedTargetFormat(disk, pool);
        if (volume.getFormat() != expectedFormat) {
            volume.setFormat(expectedFormat);
            changed = true;
        }
        if (changed) {
            volumeDao.update(volume.getId(), volume);
        }
    }

    private Storage.ImageFormat expectedTargetFormat(DrResolvedDiskMapping disk, StoragePoolVO pool) {
        if ((pool != null && Storage.StoragePoolType.RBD.equals(pool.getPoolType()))
                || (disk != null && StringUtils.equalsIgnoreCase(disk.getTargetType(), "rbd"))) {
            return Storage.ImageFormat.RAW;
        }
        String requested = disk != null ? StringUtils.trimToNull(disk.getTargetFormat()) : null;
        try {
            return Storage.ImageFormat.valueOf(StringUtils.upperCase(StringUtils.defaultIfBlank(requested, "qcow2")));
        } catch (IllegalArgumentException e) {
            throw new CloudRuntimeException("Unsupported DR target volume format: " + requested, e);
        }
    }

    private VolumeVO verifyImportedVolumeFormat(VolumeVO volume, DrResolvedDiskMapping disk, StoragePoolVO pool) {
        if (volume == null) {
            throw new CloudRuntimeException("Imported DR target volume could not be reloaded after normalization");
        }
        Storage.ImageFormat expected = expectedTargetFormat(disk, pool);
        if (volume.getFormat() != expected) {
            throw new CloudRuntimeException("DR_TARGET_VOLUME_FORMAT_MISMATCH: volume=" + volume.getUuid()
                    + " expected=" + expected + " actual=" + volume.getFormat());
        }
        return volume;
    }

    private String buildChainInfo(DrResolvedDiskMapping disk, StoragePoolVO pool) {
        JsonObject chain = new JsonObject();
        addString(chain, "format", disk.getTargetFormat());
        addString(chain, "targetType", disk.getTargetType());
        addString(chain, "storagePoolType", pool.getPoolType() != null ? pool.getPoolType().toString() : null);
        addString(chain, "storagePath", disk.getStoragePath());
        addString(chain, "krbdPath", disk.getKrbdPath());
        return chain.entrySet().isEmpty() ? null : GSON.toJson(chain);
    }

    private UserVmVO ensureTargetVm(DrPlanVO plan, DrReplicaVO replica, DrRunVO run, DrResolvedTargetPlacement placement,
            AccountVO owner, VolumeVO rootVolume, JsonObject runtime) {
        String vmName = StringUtils.defaultIfBlank(placement.getTargetVmName(), StringUtils.defaultIfBlank(plan.getName(), "dr-target") + "-target");
        VMInstanceVO existing = vmInstanceDao.findVMByHostNameInZone(vmName, placement.getZoneId());
        if (existing != null && existing.getRemoved() == null) {
            UserVmVO existingUserVm = userVmDao.findById(existing.getId());
            if (existingUserVm == null) {
                throw new CloudRuntimeException("Existing target VM name is not a user VM: " + vmName);
            }
            targetResourceOwnershipService.claimVm(plan, replica, run, existingUserVm);
            return existingUserVm;
        }
        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(parseLong(placement.getServiceOfferingLocalId()));
        if (serviceOffering == null) {
            throw new CloudRuntimeException("Target service offering was not found: " + placement.getServiceOfferingLocalId());
        }
        HostVO targetHost = hostDao.findById(placement.getWorkerHostId());
        if (targetHost == null) {
            throw new CloudRuntimeException("Target worker host was not found: " + placement.getWorkerHostId());
        }
        DrResolvedTargetHardware hardware = placement.getTargetHardware();
        if (hardware == null) {
            hardware = new DrTargetHardwareResolver().resolve(plan, guidedSpecFromMapping(plan), placement, runtime);
        }
        if (!placement.getBlockingReasons().isEmpty()) {
            throw new CloudRuntimeException("DR target hardware is not ready: " + StringUtils.join(placement.getBlockingReasons(), ","));
        }
        Map<String, String> details = buildTargetVmDetails(plan, replica, placement, serviceOffering, rootVolume, hardware);
        DrReplicaDeployVMVolumeCmd deployCmd = new DrReplicaDeployVMVolumeCmd(
                owner.getId(),
                owner.getAccountName(),
                owner.getDomainId(),
                placement.getZoneId(),
                serviceOffering.getId(),
                vmName,
                vmName,
                networkIds(placement),
                targetHost.getId(),
                HypervisorType.KVM,
                rootVolume.getId(),
                details,
                hardware);
        try {
            CallContext.registerSystemCallContextOnceOnly();
            UserVm created = userVmService.createVirtualMachineVolume(deployCmd);
            if (created == null) {
                throw new CloudRuntimeException("CloudStack returned no VM for DR target materialization");
            }
            UserVmVO createdVm = userVmDao.findById(created.getId());
            if (createdVm == null) {
                throw new CloudRuntimeException("Created DR target VM could not be reloaded: " + created.getUuid());
            }
            targetResourceOwnershipService.claimVm(plan, replica, run, createdVm);
            return createdVm;
        } catch (InsufficientCapacityException | ResourceUnavailableException | ConcurrentOperationException |
                 ResourceAllocationException e) {
            throw new CloudRuntimeException("Failed to create DR target VM " + vmName + ": " + e.getMessage(), e);
        } finally {
            CallContext.unregister();
        }
    }

    Map<String, String> buildTargetVmDetails(DrPlanVO plan, DrReplicaVO replica, DrResolvedTargetPlacement placement,
            ServiceOfferingVO serviceOffering, VolumeVO rootVolume, DrResolvedTargetHardware hardware) {
        Map<String, String> details = new HashMap<String, String>();
        Map<String, String> replicatedSourceDetails = DrVmDetailReplicationPolicy.copyableSourceDetails(
                plan.getDirection(), sourceVmDetails(plan));
        details.putAll(replicatedSourceDetails);
        details.put(DrVmDetailReplicationPolicy.REPLICATED_KEYS_DETAIL,
                StringUtils.join(new TreeSet<String>(replicatedSourceDetails.keySet()), ","));
        details.put("dr.replica.vm", String.valueOf(replica != null));
        details.put("dr.plan.uuid", plan.getUuid());
        details.put("dr.plan.id", String.valueOf(plan.getId()));
        if (replica != null) {
            details.put("dr.replica.id", String.valueOf(replica.getId()));
            details.put("dr.ownership.generation", String.valueOf(
                    replica.getOwnershipGeneration() != null ? replica.getOwnershipGeneration() : 1L));
        }
        details.put("dr.direction", plan.getDirection());
        details.put("dr.source.external.ref", StringUtils.defaultString(plan.getSourceExternalRef()));
        details.put("dr.materialized.at", Instant.now().toString());
        JsonObject sourceHardware = objectAt(objectAt(parseObject(plan.getMappingJson()), "source"), "hardware");
        String sourceHardwareFingerprint = firstString(sourceHardware, "fingerprint");
        if (StringUtils.isNotBlank(sourceHardwareFingerprint)) {
            details.put("dr.source.hardware.fingerprint", sourceHardwareFingerprint);
        }
        details.putIfAbsent(VmDetailConstants.ROOT_DISK_SIZE, String.valueOf(bytesToGiBRoundedUp(rootVolume.getSize())));
        details.putIfAbsent(VmDetailConstants.ROOT_DISK_CONTROLLER, StringUtils.defaultIfBlank(
                hardware != null ? hardware.getRootDiskController() : null, "scsi"));
        details.putIfAbsent(VmDetailConstants.DATA_DISK_CONTROLLER, StringUtils.defaultIfBlank(
                hardware != null ? hardware.getDataDiskController() : null, "scsi"));
        boolean authoritativeKvmDetails = StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)
                && sourceHardware(plan).has("vmDetails");
        if (!authoritativeKvmDetails && hardware != null && hardware.getBootType() == ApiConstants.BootType.UEFI
                && hardware.getBootMode() != null) {
            details.put(ApiConstants.BootType.UEFI.toString(), hardware.getBootMode().toString());
        }
        if (hardware != null && hardware.getIoPolicy() != null) {
            details.put(VmDetailConstants.IO_POLICY, hardware.getIoPolicy().toString());
        }
        if (hardware != null && hardware.getIoThreadsEnabled() != null) {
            // The Agent currently enables iothreads by key presence, including the string "false".
            details.remove(VmDetailConstants.IOTHREADS);
            if (Boolean.TRUE.equals(hardware.getIoThreadsEnabled())) {
                details.put(VmDetailConstants.IOTHREADS, "true");
            }
        }
        putDynamicVmDetail(details, VmDetailConstants.CPU_NUMBER, serviceOffering != null ? serviceOffering.getCpu() : null,
                placement != null ? placement.getTargetCpuNumber() : null);
        putDynamicVmDetail(details, VmDetailConstants.CPU_SPEED, serviceOffering != null ? serviceOffering.getSpeed() : null,
                placement != null ? placement.getTargetCpuSpeed() : null);
        putDynamicVmDetail(details, VmDetailConstants.MEMORY, serviceOffering != null ? serviceOffering.getRamSize() : null,
                placement != null ? placement.getTargetMemory() : null);
        return details;
    }

    private Map<String, String> buildTargetVmDetails(DrPlanVO plan, DrResolvedTargetPlacement placement,
            ServiceOfferingVO serviceOffering, VolumeVO rootVolume, DrResolvedTargetHardware hardware) {
        return buildTargetVmDetails(plan, null, placement, serviceOffering, rootVolume, hardware);
    }

    private void verifyTargetVmHardware(DrPlanVO plan, UserVmVO targetVm) {
        if (plan == null || targetVm == null || vmInstanceDetailsDao == null) {
            return;
        }
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject sourceHardware = objectAt(objectAt(mapping, "source"), "hardware");
        JsonObject targetHardware = objectAt(objectAt(mapping, "target"), "hardware");
        Map<String, String> actual = vmInstanceDetailsDao.listDetailsKeyPairs(targetVm.getId());
        actual = actual != null ? actual : new HashMap<String, String>();
        Map<String, String> expectedSourceDetails = DrVmDetailReplicationPolicy.copyableSourceDetails(
                plan.getDirection(), sourceVmDetails(plan));
        if (sourceHardware.has("vmDetails")) {
            for (String key : new String[] {VmDetailConstants.ROOT_DISK_CONTROLLER, VmDetailConstants.DATA_DISK_CONTROLLER}) {
                String controller = firstString(sourceHardware, key);
                if (StringUtils.isNotBlank(controller)) {
                    expectedSourceDetails.putIfAbsent(key, controller);
                }
            }
            DrHardwareCompatibilityPolicy.verifyDetails(expectedSourceDetails, actual);
        } else if (StringUtils.isBlank(firstString(sourceHardware, "firmware"))) {
            throw new CloudRuntimeException("TARGET_BOOT_EVIDENCE_REQUIRED: source boot snapshot is unavailable");
        }
        String expectedUefiMode = expectedSourceDetails.get(ApiConstants.BootType.UEFI.toString());
        if (StringUtils.isBlank(expectedUefiMode)
                && !sourceHardware.has("vmDetails")
                && StringUtils.containsIgnoreCase(firstString(sourceHardware, "firmware"), "efi")) {
            expectedUefiMode = Boolean.TRUE.equals(firstBoolean(sourceHardware, "secureBoot", "secure_boot", "secure"))
                    ? ApiConstants.BootMode.SECURE.toString() : ApiConstants.BootMode.LEGACY.toString();
        }
        if (!StringUtils.equalsIgnoreCase(StringUtils.defaultString(expectedUefiMode),
                StringUtils.defaultString(actual.get(ApiConstants.BootType.UEFI.toString())))) {
            throw new CloudRuntimeException("TARGET_VM_HARDWARE_MISMATCH: expected UEFI detail="
                    + StringUtils.defaultString(expectedUefiMode, "<absent>") + " but target VM has "
                    + StringUtils.defaultString(actual.get(ApiConstants.BootType.UEFI.toString()), "<absent>"));
        }
        String actualPolicy = actual.get(VmDetailConstants.IO_POLICY);
        boolean supportedPolicy = StringUtils.isBlank(actualPolicy);
        for (ApiConstants.IoDriverPolicy policy : ApiConstants.IoDriverPolicy.values()) {
            supportedPolicy |= StringUtils.equalsIgnoreCase(policy.toString(), actualPolicy);
        }
        if (!supportedPolicy) {
            throw new CloudRuntimeException("TARGET_TUNING_INVALID: io.policy is unsupported");
        }
    }

    private void recordTargetTuningDifferences(DrPlanVO plan, DrRunVO run, UserVmVO targetVm) {
        JsonObject targetHardware = objectAt(objectAt(parseObject(plan.getMappingJson()), "target"), "hardware");
        Map<String, String> actual = vmInstanceDetailsDao.listDetailsKeyPairs(targetVm.getId());
        if (actual == null) {
            actual = new HashMap<String, String>();
        }
        Boolean requestedThreads = firstBoolean(targetHardware, "ioThreadsEnabled", "iothreadsEnabled");
        String requestedPolicy = firstString(targetHardware, "ioPolicy", "io.policy");
        // Agent key-presence semantics are intentional until the Agent contract changes.
        boolean effectiveThreads = actual.containsKey(VmDetailConstants.IOTHREADS);
        if ((requestedThreads != null && requestedThreads != effectiveThreads)
                || (StringUtils.isNotBlank(requestedPolicy)
                && !StringUtils.equalsIgnoreCase(requestedPolicy, actual.get(VmDetailConstants.IO_POLICY)))) {
            JsonObject finding = new JsonObject();
            finding.addProperty("code", "TARGET_TUNING_DIFFERENCE");
            finding.addProperty("targetVmId", targetVm.getUuid());
            finding.addProperty("requestedIoThreads", requestedThreads);
            finding.addProperty("effectiveIoThreadsByAgentContract", effectiveThreads);
            finding.addProperty("requestedIoPolicy", requestedPolicy);
            finding.addProperty("storedIoPolicy", actual.get(VmDetailConstants.IO_POLICY));
            finding.addProperty("valueSource", "EXISTING_TARGET");
            recordEvent(plan.getId(), run != null ? run.getId() : null, "TARGET_TUNING_DIFFERENCE", "WARN",
                    "Target performance settings differ; existing target settings were preserved", GSON.toJson(finding));
        }
    }

    private void refreshSourceHardwareSnapshot(DrPlanVO plan) {
        if (plan == null || sourceHardwareInventoryService == null
                || !StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)) {
            return;
        }
        DrSourceVmHardware hardware = sourceHardwareInventoryService.resolve(plan);
        if (hardware == null || !hardware.isComplete()) {
            throw new CloudRuntimeException("SOURCE_VM_DETAILS_UNAVAILABLE: "
                    + StringUtils.defaultIfBlank(hardware != null ? hardware.getMessage() : null,
                            "ABLESTACK source VM details could not be read"));
        }
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject source = objectAt(mapping, "source");
        source.add("hardware", hardware.toJsonObject());
        mapping.add("source", source);
        plan.setMappingJson(GSON.toJson(mapping));
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
    }

    private JsonObject sourceHardware(DrPlanVO plan) {
        return objectAt(objectAt(parseObject(plan != null ? plan.getMappingJson() : null), "source"), "hardware");
    }

    private Map<String, String> sourceVmDetails(DrPlanVO plan) {
        Map<String, String> details = new HashMap<String, String>();
        JsonObject object = objectAt(sourceHardware(plan), "vmDetails");
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (StringUtils.isNotBlank(entry.getKey()) && entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                details.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return details;
    }

    void reconcileSourceVmDetails(DrPlanVO plan, UserVmVO targetVm) {
        if (plan == null || targetVm == null || vmInstanceDetailsDao == null
                || !StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)) {
            return;
        }
        // SYNC must not rewrite boot configuration or target-owned performance details.
        // Validate before advancing diagnostic metadata; old copy manifests are not deletion authority.
        verifyTargetVmHardware(plan, targetVm);
        String fingerprint = firstString(sourceHardware(plan), "fingerprint");
        if (StringUtils.isNotBlank(fingerprint)) {
            vmInstanceDetailsDao.removeDetail(targetVm.getId(), "dr.source.hardware.fingerprint");
            vmInstanceDetailsDao.addDetail(targetVm.getId(), "dr.source.hardware.fingerprint", fingerprint, false);
        }
    }

    // #975: historical replicas can lack dynamic offering details even when the Plan has an explicit target spec.
    void ensureTargetComputeDetails(DrPlanVO plan, UserVmVO vm) {
        ServiceOfferingVO offering = serviceOfferingDao != null ? serviceOfferingDao.findById(vm.getServiceOfferingId()) : null;
        if (offering == null || vmInstanceDetailsDao == null) { return; }
        Map<String, String> actual = vmInstanceDetailsDao.listDetailsKeyPairs(vm.getId());
        actual = actual != null ? actual : new HashMap<>();
        DrPlanGuidedSpec spec = guidedSpecFromMapping(plan);
        String[] keys = {VmDetailConstants.CPU_NUMBER, VmDetailConstants.CPU_SPEED, VmDetailConstants.MEMORY};
        Integer[] fixed = {offering.getCpu(), offering.getSpeed(), offering.getRamSize()};
        Integer[] requested = {spec.getTargetCpuNumber(), spec.getTargetCpuSpeed(), spec.getTargetMemory()};
        Map<String, String> missing = new HashMap<>();
        for (int i = 0; i < keys.length; i++) {
            if (fixed[i] != null || StringUtils.isNotBlank(actual.get(keys[i]))) { continue; }
            if (requested[i] == null || requested[i] <= 0) {
                throw new CloudRuntimeException("TARGET_COMPUTE_SPEC_REQUIRED: missing explicit target " + keys[i]);
            }
            missing.put(keys[i], requested[i].toString());
        }
        // Validate the complete missing set before persisting; never overwrite an existing target value.
        for (Map.Entry<String, String> entry : missing.entrySet()) {
            vmInstanceDetailsDao.addDetail(vm.getId(), entry.getKey(), entry.getValue(), false);
        }
    }

    private void putDynamicVmDetail(Map<String, String> details, String key, Integer offeringValue, Integer resolvedValue) {
        if (details != null && offeringValue == null && resolvedValue != null && resolvedValue > 0) {
            details.put(key, String.valueOf(resolvedValue));
        }
    }

    private List<Long> networkIds(DrResolvedTargetPlacement placement) {
        List<Long> networkIds = new ArrayList<Long>();
        for (DrResolvedNetworkMapping network : placement.getNetworks()) {
            Long id = parseLong(network.getNetworkLocalId());
            if (id != null) {
                networkIds.add(id);
            }
        }
        if (networkIds.isEmpty()) {
            throw new CloudRuntimeException("Target network is unresolved for DR target VM materialization");
        }
        return networkIds;
    }

    private void attachDataVolumeIfNeeded(UserVmVO targetVm, VolumeVO volume, Long deviceId) {
        if (volume.getInstanceId() != null && volume.getInstanceId().equals(targetVm.getId())) {
            return;
        }
        volumeDao.attachVolume(volume.getId(), targetVm.getId(), deviceId);
    }

    private void updateReplicaDisk(List<DrReplicaDiskVO> replicaDisks, DrResolvedDiskMapping mapping, VolumeVO volume, String state) {
        DrReplicaDiskVO disk = findReplicaDisk(replicaDisks, mapping);
        if (disk == null) {
            return;
        }
        disk.setTargetVolumeId(volume.getId());
        disk.setTargetDiskRef(StringUtils.defaultIfBlank(volume.getPath(), volume.getUuid()));
        disk.setFormat(volume.getFormat() != null ? StringUtils.lowerCase(volume.getFormat().toString())
                : StringUtils.defaultIfBlank(mapping.getTargetFormat(), disk.getFormat()));
        disk.setState(state);
        disk.setDetailsJson(buildDiskRuntimeJson(mapping, volume));
        disk.markUpdated();
        drReplicaDiskDao.update(disk.getId(), disk);
    }

    private DrReplicaDiskVO requireReplicaDisk(List<DrReplicaDiskVO> replicaDisks, DrResolvedDiskMapping mapping) {
        DrReplicaDiskVO disk = findReplicaDisk(replicaDisks, mapping);
        if (disk == null) {
            throw new CloudRuntimeException("DR target materialization has no replica disk ownership row for "
                    + StringUtils.defaultIfBlank(mapping.getLabel(), mapping.getSourceRef()));
        }
        return disk;
    }

    private void observeReplicaPowerState(DrReplicaVO replica, UserVmVO targetVm) {
        String observed = "UNKNOWN";
        if (VirtualMachine.State.Running.equals(targetVm.getState())) {
            observed = "POWERED_ON";
        } else if (VirtualMachine.State.Stopped.equals(targetVm.getState())) {
            observed = DrConstants.REPLICA_POWER_STATE_POWERED_OFF;
        }
        replica.setPowerState(observed);
        replica.setPowerStateObservedAt(new Date());
    }

    private DrReplicaDiskVO findReplicaDisk(List<DrReplicaDiskVO> disks, DrResolvedDiskMapping mapping) {
        if (disks == null || disks.isEmpty()) {
            return null;
        }
        for (DrReplicaDiskVO disk : disks) {
            if (StringUtils.isNotBlank(mapping.getLabel()) && StringUtils.equals(mapping.getLabel(), disk.getDiskLabel())) {
                return disk;
            }
            if (StringUtils.isNotBlank(mapping.getSourceRef()) && StringUtils.equals(mapping.getSourceRef(), disk.getSourceDiskRef())) {
                return disk;
            }
            if (StringUtils.isNotBlank(mapping.getTargetRef()) && StringUtils.equals(mapping.getTargetRef(), disk.getTargetDiskRef())) {
                return disk;
            }
        }
        return null;
    }

    private String buildDiskRuntimeJson(DrResolvedDiskMapping mapping, VolumeVO volume) {
        JsonObject details = mapping.toJsonObject();
        JsonObject target = objectAt(details, "target");
        target.addProperty("volumeId", volume.getId());
        target.addProperty("volumeUuid", volume.getUuid());
        target.addProperty("path", volume.getPath());
        target.addProperty("state", volume.getState().toString());
        target.addProperty("format", volume.getFormat() != null ? StringUtils.lowerCase(volume.getFormat().toString()) : null);
        details.add("target", target);
        return GSON.toJson(details);
    }

    private String buildReplicaRuntimeJson(DrPlanVO plan, UserVmVO targetVm, List<VolumeVO> volumes, JsonObject runtime) {
        JsonObject details = runtime != null ? runtime.deepCopy() : new JsonObject();
        details.addProperty("planUuid", plan.getUuid());
        details.addProperty("targetVmId", targetVm.getId());
        details.addProperty("targetExternalRef", targetVm.getUuid());
        details.addProperty("targetVmName", targetVm.getDisplayName());
        details.addProperty("targetMaterialized", true);
        details.add("targetVolumes", targetVolumesJson(volumes));
        return GSON.toJson(details);
    }

    private JsonArray targetVolumesJson(List<VolumeVO> volumes) {
        JsonArray array = new JsonArray();
        for (VolumeVO volume : volumes) {
            JsonObject object = new JsonObject();
            object.addProperty("id", volume.getId());
            object.addProperty("uuid", volume.getUuid());
            object.addProperty("name", volume.getName());
            object.addProperty("path", volume.getPath());
            object.addProperty("type", volume.getVolumeType().toString());
            object.addProperty("deviceId", volume.getDeviceId());
            object.addProperty("format", volume.getFormat() != null ? StringUtils.lowerCase(volume.getFormat().toString()) : null);
            array.add(object);
        }
        return array;
    }

    private void updatePlanMapping(DrPlanVO plan, UserVmVO targetVm, List<VolumeVO> volumes) {
        JsonObject mapping = parseObject(plan.getMappingJson());
        JsonObject target = objectAt(mapping, "target");
        target.addProperty("vmId", targetVm.getId());
        target.addProperty("externalRef", targetVm.getUuid());
        target.addProperty("uuid", targetVm.getUuid());
        target.addProperty("vmName", targetVm.getDisplayName());
        mapping.add("target", target);
        mapping.addProperty("targetVmId", targetVm.getId());
        mapping.addProperty("targetExternalRef", targetVm.getUuid());
        JsonArray disks = firstArray(mapping, "disks", "diskMappings", "volumes", "volumeMappings");
        for (int i = 0; i < disks.size() && i < volumes.size(); i++) {
            JsonElement element = disks.get(i);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject disk = element.getAsJsonObject();
            VolumeVO volume = volumes.get(i);
            JsonObject diskTarget = objectAt(disk, "target");
            diskTarget.addProperty("volumeId", volume.getId());
            diskTarget.addProperty("volumeUuid", volume.getUuid());
            diskTarget.addProperty("diskRef", StringUtils.defaultIfBlank(volume.getPath(), volume.getUuid()));
            diskTarget.addProperty("path", volume.getPath());
            diskTarget.addProperty("format", volume.getFormat() != null ? StringUtils.lowerCase(volume.getFormat().toString()) : null);
            disk.add("target", diskTarget);
            disk.addProperty("targetVolumeId", volume.getId());
            disk.addProperty("targetRef", StringUtils.defaultIfBlank(volume.getPath(), volume.getUuid()));
            disk.addProperty("targetFormat", volume.getFormat() != null ? StringUtils.lowerCase(volume.getFormat().toString()) : null);
        }
        if (disks.size() > 0) {
            mapping.add("disks", disks);
        }
        plan.setMappingJson(GSON.toJson(mapping));
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
    }

    private MaterializationResult buildResult(DrPlanVO plan, DrReplicaVO replica, UserVmVO targetVm, List<DrReplicaDiskVO> replicaDisks) {
        MaterializationResult result = new MaterializationResult();
        result.planUuid = plan.getUuid();
        result.targetVmId = targetVm.getId();
        result.targetExternalRef = targetVm.getUuid();
        result.targetVmName = targetVm.getDisplayName();
        result.targetVolumeMapJson = targetVolumeMapJson(replicaDisks);
        result.targetReadyAt = plan.getLastTargetDurableAt() != null ? plan.getLastTargetDurableAt() : new Date();
        result.targetReadyRpoSeconds = computeRpoSeconds(plan.getLastSourceCheckpointAt(), result.targetReadyAt);
        result.replicaId = replica.getId();
        result.ownershipGeneration = replica.getOwnershipGeneration() != null ? replica.getOwnershipGeneration() : 1L;
        result.observedPowerState = replica.getPowerState();
        return result;
    }

    private String targetVolumeMapJson(List<DrReplicaDiskVO> replicaDisks) {
        JsonObject root = new JsonObject();
        JsonArray disks = new JsonArray();
        if (replicaDisks != null) {
            for (DrReplicaDiskVO disk : replicaDisks) {
                JsonObject object = new JsonObject();
                addString(object, "label", disk.getDiskLabel());
                if (disk.getTargetVolumeId() != null) {
                    object.addProperty("targetVolumeId", disk.getTargetVolumeId());
                }
                addString(object, "targetDiskRef", disk.getTargetDiskRef());
                addString(object, "sourceDiskRef", disk.getSourceDiskRef());
                disks.add(object);
            }
        }
        root.add("disks", disks);
        return GSON.toJson(root);
    }

    private void notifyFtctlTargetMaterialized(DrPlanVO plan, DrRunVO run, MaterializationResult result) {
        Long hostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, run, DrWorkerRole.TARGET) : null;
        if (hostId == null) {
            throw new CloudRuntimeException("Unable to notify FTCTL_DR because no worker host is bound");
        }
        validateFtctlTargetMaterializedCapability(plan, run, hostId);
        FtctlDrActionCommand command = new FtctlDrActionCommand(FtctlDrActionCommand.Action.TARGET_MATERIALIZED, plan.getUuid(), run.getUuid());
        command.setActionName(FtctlDrActionCommand.Action.TARGET_MATERIALIZED.name());
        command.setCliCommand(FtctlDrActionCommand.Action.TARGET_MATERIALIZED.getCliCommand());
        command.setWait(45);
        command.setWaitForCompletion(true);
        command.setContextParam("targetVmId", String.valueOf(result.targetVmId));
        command.setContextParam("targetExternalRef", result.targetExternalRef);
        command.setContextParam("targetVmName", result.targetVmName);
        command.setContextParam("targetVolumeMapJson", result.targetVolumeMapJson);
        result.materializationSpecJson = buildMaterializationSpec(plan, run, result);
        result.materializationSpecSha256 = DrTargetResourceOwnershipService.sha256(result.materializationSpecJson);
        command.setContextParam("materializationSpecJson", result.materializationSpecJson);
        command.setContextParam("materializationSpecSha256", result.materializationSpecSha256);
        if (result.targetReadyRpoSeconds != null) {
            command.setContextParam("targetReadyRpoSeconds", String.valueOf(result.targetReadyRpoSeconds));
        }
        Answer answer = agentManager.easySend(hostId, command);
        if (!(answer instanceof FtctlDrActionAnswer) || !answer.getResult()) {
            String message = answer != null ? answer.getDetails() : "Agent returned no FTCTL_DR target materialized answer";
            if (answer instanceof FtctlDrActionAnswer) {
                FtctlDrActionAnswer actionAnswer = (FtctlDrActionAnswer) answer;
                if (StringUtils.isNotBlank(actionAnswer.getErrorCode())) {
                    message = actionAnswer.getErrorCode() + ": " + message;
                }
            }
            throw new CloudRuntimeException("Failed to notify FTCTL_DR target materialization: " + message);
        }
        DrReplicaVO replica = drReplicaDao.findById(result.replicaId);
        if (replica != null) {
            replica.setMaterializationDigest(result.materializationSpecSha256);
            replica.setOwnershipState("VALID");
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
        }
    }

    private String buildMaterializationSpec(DrPlanVO plan, DrRunVO run, MaterializationResult result) {
        JsonObject spec = new JsonObject();
        spec.addProperty("contractVersion", 2);
        spec.addProperty("planUuid", plan.getUuid());
        spec.addProperty("runUuid", run.getUuid());
        spec.addProperty("replicaId", result.replicaId);
        spec.addProperty("ownershipGeneration", result.ownershipGeneration);
        JsonObject target = new JsonObject();
        target.addProperty("vmId", result.targetVmId);
        target.addProperty("externalRef", result.targetExternalRef);
        target.addProperty("name", result.targetVmName);
        target.addProperty("observedPowerState", result.observedPowerState);
        spec.add("targetVm", target);
        spec.add("targetVolumeMap", parseObject(result.targetVolumeMapJson));
        return GSON.toJson(spec);
    }

    private void validateFtctlTargetMaterializedCapability(DrPlanVO plan, DrRunVO run, Long hostId) {
        FtctlDrCapabilitiesCommand command = new FtctlDrCapabilitiesCommand(plan.getUuid(), run.getUuid());
        List<String> actions = new ArrayList<String>();
        actions.add(FtctlDrActionCommand.Action.TARGET_MATERIALIZED.name());
        command.setRequiredActions(actions);
        List<String> cliCommands = new ArrayList<String>();
        cliCommands.add(FtctlDrActionCommand.Action.TARGET_MATERIALIZED.getCliCommand());
        cliCommands.add("dr-status");
        command.setRequiredCliCommands(cliCommands);
        List<String> features = new ArrayList<String>();
        features.add("target-materialization-manifest-v2");
        features.add("target-resource-ownership-generation-v1");
        command.setRequiredFeatures(features);

        Answer answer = agentManager.easySend(hostId, command);
        if (!(answer instanceof FtctlDrCapabilitiesAnswer)) {
            String message = answer != null ? answer.getDetails() : "Agent returned no FTCTL_DR capability answer";
            throw new CloudRuntimeException("FTCTL_DR target materialization capability check failed: " + message);
        }
        FtctlDrCapabilitiesAnswer capabilities = (FtctlDrCapabilitiesAnswer) answer;
        if (!capabilities.getResult()) {
            JsonObject details = new JsonObject();
            details.addProperty("hostId", hostId);
            details.addProperty("planUuid", plan.getUuid());
            details.addProperty("runUuid", run.getUuid());
            details.add("missingActions", GSON.toJsonTree(capabilities.getMissingActions()));
            details.add("missingCliCommands", GSON.toJsonTree(capabilities.getMissingCliCommands()));
            String message = StringUtils.defaultIfBlank(capabilities.getDetails(), "FTCTL_DR target materialization capability mismatch");
            throw new CloudRuntimeException(message + " " + GSON.toJson(details));
        }
    }

    private void completeMaterialization(long planId, long runId, MaterializationResult result, String runtimeStatusJson) {
        DrPlanVO plan = drPlanDao.findById(planId);
        DrRunVO run = drRunDao.findById(runId);
        if (plan == null || run == null) {
            return;
        }
        Date now = new Date();
        Date readyAt = result.targetReadyAt != null ? result.targetReadyAt : now;
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setTargetReadyAt(readyAt);
        plan.setTargetReadyRpoSeconds(result.targetReadyRpoSeconds);
        plan.setLastErrorCode(null);
        plan.setLastErrorMessage(null);
        if (StringUtils.isBlank(plan.getActiveSide())) {
            plan.setActiveSide("SOURCE");
        }
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        if (run.getCompleted() != null) {
            recordEvent(plan.getId(), run.getId(), DrConstants.EVENT_TARGET_MATERIALIZED, DrConstants.EVENT_SEVERITY_INFO,
                    "Existing DR target VM was reconciled to the durable checkpoint", materializationDetailsJson(result, runtimeStatusJson));
            return;
        }
        String details = materializationDetailsJson(result, runtimeStatusJson);
        upsertRunStep(run, "runtime-projection", STEP_ORDER_RUNTIME_PROJECTION, DrConstants.STEP_STATE_SUCCEEDED, 100, details, null, null);
        upsertRunStep(run, "target-materialization", STEP_ORDER_TARGET_MATERIALIZATION, DrConstants.STEP_STATE_SUCCEEDED, 100, details, null, null);
        run.setState(DrConstants.RUN_STATE_SUCCEEDED);
        run.setCompleted(now);
        run.setCurrentStepName("target-materialization");
        run.setProjectionState("succeeded");
        run.setProjectionChecked(now);
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.setLastStatusJson(details);
        run.setErrorCode(null);
        run.setErrorMessage(null);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        recordEvent(plan.getId(), run.getId(), DrConstants.EVENT_TARGET_MATERIALIZED, DrConstants.EVENT_SEVERITY_INFO,
                "DR target VM was materialized and FTCTL_DR runtime was marked ready", details);
    }

    private void failMaterialization(DrPlanVO plan, DrRunVO run, String runtimeStatusJson, RuntimeException e) {
        String message = StringUtils.defaultIfBlank(e.getMessage(), "DR target materialization failed");
        String errorCode = StringUtils.startsWith(message, DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT)
                ? DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT : DrConstants.ERROR_TARGET_VM_MATERIALIZE_FAILED;
        String details = failureDetailsJson(runtimeStatusJson, errorCode, message);
        upsertRunStep(run, "target-materialization", STEP_ORDER_TARGET_MATERIALIZATION, DrConstants.STEP_STATE_FAILED, 100,
                details, errorCode, message);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setCompleted(new Date());
        run.setCurrentStepName("target-materialization");
        run.setProjectionState("failed");
        run.setProjectionChecked(new Date());
        run.setRetryable(false);
        run.setRetryAfterSeconds(null);
        run.setNextRetryAt(null);
        run.setLastStatusJson(details);
        run.setErrorCode(errorCode);
        run.setErrorMessage(message);
        run.markUpdated();
        drRunDao.update(run.getId(), run);
        closeOpenRunSteps(run, errorCode, message);

        plan.setState(DrConstants.PLAN_STATE_ERROR);
        plan.setLastErrorCode(errorCode);
        plan.setLastErrorMessage(message);
        plan.markUpdated();
        drPlanDao.update(plan.getId(), plan);
        DrReplicaVO replica = firstActiveReplica(plan);
        if (replica != null) {
            replica.setState(DrConstants.REPLICA_STATE_ERROR);
            if (DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT.equals(errorCode)) {
                replica.setOwnershipState("QUARANTINED");
            }
            replica.setRuntimeStateJson(details);
            replica.markUpdated();
            drReplicaDao.update(replica.getId(), replica);
        }
        recordEvent(plan.getId(), run.getId(), DrConstants.EVENT_RUN_FAILED, DrConstants.EVENT_SEVERITY_ERROR, message, details);
    }

    private void closeOpenRunSteps(DrRunVO run, String errorCode, String message) {
        if (run == null || drRunStepDao == null) {
            return;
        }
        for (DrRunStepVO step : drRunStepDao.listActiveByRunId(run.getId())) {
            if (step == null || step.getCompleted() != null) {
                continue;
            }
            step.setState(DrConstants.STEP_STATE_FAILED);
            step.setProgress(100);
            step.setErrorCode(errorCode);
            step.setErrorMessage(message);
            step.setCompleted(new Date());
            step.markUpdated();
            drRunStepDao.update(step.getId(), step);
        }
    }

    private void upsertRunStep(DrRunVO run, String name, int order, String state, Integer progress, String detailsJson,
            String errorCode, String errorMessage) {
        DrRunStepVO step = drRunStepDao.findActiveByRunIdAndStepOrder(run.getId(), order);
        if (step == null) {
            step = new DrRunStepVO(run.getId(), name, order);
        }
        step.setState(state);
        step.setProgress(progress);
        step.setDetailsJson(detailsJson);
        step.setErrorCode(errorCode);
        step.setErrorMessage(errorMessage);
        if (step.getStarted() == null) {
            step.setStarted(new Date());
        }
        if (StringUtils.equalsAny(state, DrConstants.STEP_STATE_SUCCEEDED, DrConstants.STEP_STATE_FAILED, DrConstants.STEP_STATE_CANCELED)) {
            step.setCompleted(new Date());
        }
        step.markUpdated();
        if (step.getId() > 0) {
            drRunStepDao.update(step.getId(), step);
        } else {
            drRunStepDao.persist(step);
        }
    }

    private void recordEvent(Long planId, Long runId, String eventType, String severity, String message, String detailsJson) {
        DrEventVO event = new DrEventVO(eventType, severity, DrConstants.EVENT_SOURCE_CLOUD);
        event.setPlanId(planId);
        event.setRunId(runId);
        event.setMessage(message);
        event.setDetailsJson(detailsJson);
        drEventDao.persist(event);
    }

    private boolean hasDurableCheckpoint(DrPlanVO plan, JsonObject runtime) {
        return plan.getLastTargetDurableAt() != null || StringUtils.isNotBlank(firstString(runtime, "last_target_durable_at"));
    }

    private String materializationDetailsJson(MaterializationResult result, String runtimeStatusJson) {
        JsonObject details = parseObject(runtimeStatusJson);
        details.addProperty("targetVmId", result.targetVmId);
        details.addProperty("targetExternalRef", result.targetExternalRef);
        details.addProperty("targetVmName", result.targetVmName);
        details.addProperty("targetMaterialized", true);
        details.addProperty("materializationContractVersion", 2);
        details.addProperty("ownershipGeneration", result.ownershipGeneration);
        details.addProperty("materializationSpecSha256", result.materializationSpecSha256);
        details.add("targetVolumeMap", parseObject(result.targetVolumeMapJson));
        return GSON.toJson(details);
    }

    private String failureDetailsJson(String runtimeStatusJson, String errorCode, String message) {
        JsonObject details = parseObject(runtimeStatusJson);
        details.addProperty("errorCode", errorCode);
        details.addProperty("errorMessage", message);
        details.addProperty("targetMaterialized", false);
        return GSON.toJson(details);
    }

    private JsonObject parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private JsonObject objectAt(JsonObject object, String key) {
        JsonElement element = object != null ? object.get(key) : null;
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return new JsonObject();
    }

    private JsonArray firstArray(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return new JsonArray();
        }
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && element.isJsonArray()) {
                return element.getAsJsonArray();
            }
        }
        return new JsonArray();
    }

    private String firstString(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && !element.isJsonNull() && element.isJsonPrimitive()) {
                String value = StringUtils.trimToNull(element.getAsString());
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private Long firstLong(JsonObject object, String... keys) {
        for (String key : keys) {
            Long value = parseLong(firstString(object, key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Integer firstInteger(JsonObject mapping, JsonObject target, String mappingKey, String targetKey) {
        Long parsed = positiveLong(firstNonBlank(firstString(mapping, mappingKey), firstString(target, targetKey)));
        return parsed != null && parsed <= Integer.MAX_VALUE ? parsed.intValue() : null;
    }

    private Boolean firstBoolean(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                continue;
            }
            try {
                return element.getAsBoolean();
            } catch (RuntimeException ignored) {
                String value = StringUtils.trimToNull(element.getAsString());
                if (StringUtils.equalsAnyIgnoreCase(value, "true", "yes", "enabled", "1")) {
                    return true;
                }
                if (StringUtils.equalsAnyIgnoreCase(value, "false", "no", "disabled", "0")) {
                    return false;
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : StringUtils.trimToNull(second);
    }

    private Long firstNonNull(Long first, Long second, Long third) {
        if (first != null) {
            return first;
        }
        return second != null ? second : third;
    }

    private Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.valueOf(StringUtils.trim(value));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Long positiveLong(String value) {
        Long parsed = parseLong(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private int bytesToGiBRoundedUp(long bytes) {
        return (int)Math.max(1L, (bytes + GIB - 1L) / GIB);
    }

    private Integer computeRpoSeconds(Date source, Date target) {
        if (source == null || target == null) {
            return null;
        }
        long seconds = Math.max(0L, (target.getTime() - source.getTime()) / 1000L);
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)seconds;
    }

    private void addString(JsonObject object, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            object.addProperty(key, value);
        }
    }

    private static final class MaterializationResult {
        private String planUuid;
        private long replicaId;
        private long targetVmId;
        private String targetExternalRef;
        private String targetVmName;
        private String targetVolumeMapJson;
        private Date targetReadyAt;
        private Integer targetReadyRpoSeconds;
        private long ownershipGeneration;
        private String observedPowerState;
        private String materializationSpecJson;
        private String materializationSpecSha256;
    }
}
