// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.dr;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrReplicaDiskDao;
import com.cloud.dr.dao.DrTestDiskDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class DrTargetMaterializationServiceImplTest {
    @Mock private DrPlanDao drPlanDao;
    @Mock private DrReplicaDao drReplicaDao;
    @Mock private DrReplicaDiskDao drReplicaDiskDao;
    @Mock private DrTestSessionDao drTestSessionDao;
    @Mock private DrTestDiskDao drTestDiskDao;
    @Mock private UserVmDao userVmDao;
    @Mock private UserVmService userVmService;
    @Mock private UserVmManager userVmManager;
    @Mock private VolumeDao volumeDao;
    @Mock private VolumeApiService volumeApiService;
    @Mock private AccountDao accountDao;
    @Mock private PrimaryDataStoreDao primaryDataStoreDao;
    @Mock private DrTargetResourceOwnershipService targetResourceOwnershipService;
    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;
    @InjectMocks private DrTargetMaterializationServiceImpl service;

    @Test
    public void retainOperationalVmDoesNotMutateCloudResources() {
        service.validateReleaseDisposition(42L, DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM);

        Assert.assertTrue(service.cleanupReleasedStandbyTarget(42L, 7L,
                DrConstants.RELEASE_DISPOSITION_RETAIN_OPERATIONAL_VM));
        Mockito.verifyNoInteractions(userVmService, userVmManager, volumeApiService);
    }

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void targetAuthorityCannotDeleteReplicaResources() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getActiveSide()).thenReturn(DrConstants.AUTHORITY_SIDE_TARGET);
        Mockito.when(drPlanDao.findById(42L)).thenReturn(plan);

        service.validateReleaseDisposition(42L, DrConstants.RELEASE_DISPOSITION_DELETE_STANDBY_REPLICA);
    }

    @Test
    public void deleteStandbyReplicaRemovesOnlyOwnedStoppedTargetResources() throws Exception {
        long planId = 42L;
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        DrReplicaVO replica = Mockito.mock(DrReplicaVO.class);
        DrReplicaDiskVO disk = Mockito.mock(DrReplicaDiskVO.class);
        UserVmVO sourceVm = Mockito.mock(UserVmVO.class);
        UserVmVO targetVm = Mockito.mock(UserVmVO.class);
        AccountVO owner = Mockito.mock(AccountVO.class);
        VolumeVO volume = Mockito.mock(VolumeVO.class);

        Mockito.when(plan.getUuid()).thenReturn("release-plan");
        Mockito.when(plan.getActiveSide()).thenReturn(DrConstants.AUTHORITY_SIDE_SOURCE);
        Mockito.when(plan.getState()).thenReturn(DrConstants.PLAN_STATE_READY);
        Mockito.when(plan.getSourceVmId()).thenReturn(101L);
        Mockito.when(drPlanDao.findById(planId)).thenReturn(plan);
        Mockito.when(replica.getId()).thenReturn(11L);
        Mockito.when(replica.getTargetVmId()).thenReturn(91L);
        Mockito.when(drReplicaDao.listActiveByPlanId(planId)).thenReturn(Collections.singletonList(replica));
        Mockito.when(drReplicaDiskDao.listActiveByReplicaId(11L)).thenReturn(Collections.singletonList(disk));
        Mockito.when(userVmDao.findById(101L)).thenReturn(sourceVm);
        Mockito.when(sourceVm.getAccountId()).thenReturn(1L);
        Mockito.when(accountDao.findById(1L)).thenReturn(owner);
        Mockito.when(userVmDao.findById(91L)).thenReturn(targetVm);
        Mockito.when(targetVm.getId()).thenReturn(91L);
        Mockito.when(targetVm.getState()).thenReturn(VirtualMachine.State.Stopped);
        Mockito.when(userVmManager.expunge(targetVm)).thenReturn(true);
        Mockito.when(disk.getId()).thenReturn(12L);
        Mockito.when(disk.getTargetVolumeId()).thenReturn(92L);
        Mockito.when(disk.getTargetDiskRef()).thenReturn("rbd/release-volume");
        Mockito.when(volumeDao.findById(92L)).thenReturn(volume);
        Mockito.when(volume.getId()).thenReturn(92L);

        Assert.assertTrue(service.cleanupReleasedStandbyTarget(planId, 7L,
                DrConstants.RELEASE_DISPOSITION_DELETE_STANDBY_REPLICA));

        InOrder order = Mockito.inOrder(userVmService, userVmManager, volumeApiService);
        order.verify(userVmService).destroyVm(91L, true);
        order.verify(userVmManager).expunge(targetVm);
        order.verify(volumeApiService).destroyVolume(92L, owner, true, true);
        Mockito.verify(targetResourceOwnershipService, Mockito.atLeastOnce()).assertVmOwnedBy(plan, replica, targetVm);
    }

    @Test
    public void ensureTargetPoweredOnReturnsDurableEvidenceForRunningReplica() throws Exception {
        long planId = 42L;
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        DrReplicaVO replica = Mockito.mock(DrReplicaVO.class);
        UserVmVO targetVm = Mockito.mock(UserVmVO.class);
        Mockito.when(drPlanDao.findById(planId)).thenReturn(plan);
        Mockito.when(replica.getTargetVmId()).thenReturn(91L);
        Mockito.when(drReplicaDao.listActiveByPlanId(planId)).thenReturn(Collections.singletonList(replica));
        Mockito.when(userVmDao.findById(91L)).thenReturn(targetVm);
        Mockito.when(targetVm.getId()).thenReturn(91L);
        Mockito.when(targetVm.getUuid()).thenReturn("target-uuid");
        Mockito.when(targetVm.getState()).thenReturn(VirtualMachine.State.Running);

        DrTargetPowerOnResult result = service.ensureTargetPoweredOn(planId);

        Assert.assertTrue(result.isReady());
        Assert.assertTrue(result.isAlreadyRunning());
        Assert.assertEquals("POWERED_ON", result.getPowerState());
        Assert.assertEquals("POWER_STATE_VALIDATED", result.getBootValidationState());
        Assert.assertNotNull(result.getPowerOnAt());
        Assert.assertNotNull(result.getBootValidatedAt());
        Mockito.verify(userVmManager, Mockito.never()).startVirtualMachine(Mockito.anyLong(), Mockito.any(),
                Mockito.anyMap(), Mockito.any());
    }

    @Test
    public void ensureTargetPoweredOffStopsRunningReplicaAndVerifiesState() throws Exception {
        long planId = 43L;
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        DrReplicaVO replica = Mockito.mock(DrReplicaVO.class);
        UserVmVO targetVm = Mockito.mock(UserVmVO.class);
        Mockito.when(drPlanDao.findById(planId)).thenReturn(plan);
        Mockito.when(replica.getTargetVmId()).thenReturn(92L);
        Mockito.when(drReplicaDao.listActiveByPlanId(planId)).thenReturn(Collections.singletonList(replica));
        Mockito.when(userVmDao.findById(92L)).thenReturn(targetVm);
        Mockito.when(targetVm.getId()).thenReturn(92L);
        Mockito.when(targetVm.getState()).thenReturn(VirtualMachine.State.Running,
                VirtualMachine.State.Running, VirtualMachine.State.Stopped);

        service.ensureTargetPoweredOff(planId);

        Mockito.verify(userVmManager).stopVirtualMachine(92L, true);
    }

    @Test
    public void cleanupExpungesTestVmBeforeDestroyingItsVolume() throws Exception {
        long planId = 35L;
        long cleanupRunId = 65L;
        DrTestSessionVO session = Mockito.mock(DrTestSessionVO.class);
        DrTestDiskVO disk = Mockito.mock(DrTestDiskVO.class);
        UserVmVO testVm = Mockito.mock(UserVmVO.class);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        AccountVO owner = Mockito.mock(AccountVO.class);
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);

        Mockito.when(session.getId()).thenReturn(1L);
        Mockito.when(session.getState()).thenReturn(DrTestSessionState.ACTIVE);
        Mockito.when(session.getTargetVmId()).thenReturn(254L);
        Mockito.when(drTestSessionDao.findActiveByPlanId(planId)).thenReturn(session);
        Mockito.when(userVmDao.findById(254L)).thenReturn(testVm);
        Mockito.when(testVm.getId()).thenReturn(254L);
        Mockito.when(userVmManager.expunge(testVm)).thenReturn(true);
        Mockito.when(drPlanDao.findById(planId)).thenReturn(plan);
        Mockito.when(accountDao.findById(1L)).thenReturn(owner);
        Mockito.when(disk.getId()).thenReturn(2L);
        Mockito.when(disk.getProvider()).thenReturn("RBD");
        Mockito.when(disk.getArtifactRef()).thenReturn("rbd:rbd/test-clone");
        Mockito.when(disk.getTargetVolumeId()).thenReturn(483L);
        Mockito.when(drTestDiskDao.listActiveBySessionId(1L)).thenReturn(Collections.singletonList(disk));
        Mockito.when(volumeDao.findById(483L)).thenReturn(volume);
        Mockito.when(volume.getId()).thenReturn(483L);
        Mockito.when(volume.getPoolId()).thenReturn(1L);
        Mockito.when(volume.getPath()).thenReturn("rbd/test-clone");
        Mockito.when(primaryDataStoreDao.findById(1L)).thenReturn(pool);
        Mockito.when(pool.getPoolType()).thenReturn(com.cloud.storage.Storage.StoragePoolType.RBD);
        Mockito.when(pool.getPath()).thenReturn("rbd");

        Assert.assertTrue(service.cleanupTestTarget(planId, cleanupRunId));

        InOrder order = Mockito.inOrder(userVmService, userVmManager, volumeApiService);
        order.verify(userVmService).destroyVm(254L, true);
        order.verify(userVmManager).expunge(testVm);
        order.verify(volumeApiService).destroyVolume(483L, owner, true, true);
        Mockito.verify(volume).setPath("test-clone");
        Mockito.verify(volumeDao).update(483L, volume);
    }

    @Test
    public void testFailoverUsesPersistedDefaultNetworkWhenGroupRequestOmitsNetworkId() {
        DrResolvedTargetPlacement placement = new DrResolvedTargetPlacement();
        DrResolvedNetworkMapping mapped = new DrResolvedNetworkMapping();
        mapped.setNetworkId("network-uuid");
        mapped.setNetworkLocalId("204");
        mapped.setRole("default");
        mapped.setName("L2-Network");
        placement.addNetwork(mapped);

        Long resolved = service.applyTestNetwork(placement, "ISOLATED", null);

        Assert.assertEquals(Long.valueOf(204L), resolved);
        Assert.assertEquals(1, placement.getNetworks().size());
        Assert.assertEquals("network-uuid", placement.getNetworks().get(0).getNetworkId());
        Assert.assertEquals("204", placement.getNetworks().get(0).getNetworkLocalId());
    }

    @Test
    public void explicitTestNetworkOverridesPersistedPlanNetwork() {
        DrResolvedTargetPlacement placement = new DrResolvedTargetPlacement();
        DrResolvedNetworkMapping mapped = new DrResolvedNetworkMapping();
        mapped.setNetworkLocalId("204");
        placement.addNetwork(mapped);

        Long resolved = service.applyTestNetwork(placement, "ISOLATED", 205L);

        Assert.assertEquals(Long.valueOf(205L), resolved);
        Assert.assertEquals(1, placement.getNetworks().size());
        Assert.assertEquals("205", placement.getNetworks().get(0).getNetworkLocalId());
    }

    @Test
    public void failbackRunCannotOwnTargetMaterialization() {
        DrPlanVO plan = new DrPlanVO("failback-owner-guard", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_TARGET);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);

        Assert.assertFalse(service.isMaterializationOwnerRun(plan, run));
    }

    @Test
    public void completedProtectionProducerCanOwnSourceSideReconciliation() {
        DrPlanVO plan = new DrPlanVO("sync-owner", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide(DrConstants.AUTHORITY_SIDE_SOURCE);
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_RECOVER_SYNC);

        Assert.assertTrue(service.isMaterializationOwnerRun(plan, run));
    }

    @Test
    public void kvmTargetUsesExactSourceVmDetailsInsteadOfGuestOrGuidedBootInference() {
        DrPlanVO plan = new DrPlanVO("details", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"fingerprint\":\"current-fingerprint\",\"vmDetails\":{"
                + "\"UEFI\":\"LEGACY\",\"tpmversion\":\"NONE\",\"io.policy\":\"io_uring\","
                + "\"clone.fast.status\":\"running\"}}}}");
        DrResolvedTargetHardware hardware = new DrResolvedTargetHardware();
        hardware.setBootType(ApiConstants.BootType.UEFI);
        hardware.setBootMode(ApiConstants.BootMode.SECURE);
        VolumeVO root = Mockito.mock(VolumeVO.class);
        Mockito.when(root.getSize()).thenReturn(100L * 1024L * 1024L * 1024L);

        Map<String, String> details = service.buildTargetVmDetails(plan, null,
                new DrResolvedTargetPlacement(), null, root, hardware);

        Assert.assertEquals("LEGACY", details.get("UEFI"));
        Assert.assertEquals("NONE", details.get("tpmversion"));
        Assert.assertEquals("io_uring", details.get("io.policy"));
        Assert.assertFalse(details.containsKey("clone.fast.status"));
        Assert.assertFalse(details.containsKey("boot.mode"));
        Assert.assertTrue(details.get(DrVmDetailReplicationPolicy.REPLICATED_KEYS_DETAIL).contains("tpmversion"));
    }

    @Test
    public void targetVmDetailsOnlyContainComputeValuesCustomizableBySelectedOffering() {
        DrPlanVO plan = new DrPlanVO("compute-details", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"vmDetails\":{"
                + "\"cpuNumber\":\"1\",\"cpuSpeed\":\"1000\",\"memory\":\"1024\","
                + "\"UEFI\":\"LEGACY\"}}}}");
        DrResolvedTargetPlacement placement = new DrResolvedTargetPlacement();
        placement.setTargetCpuNumber(2);
        placement.setTargetCpuSpeed(2000);
        placement.setTargetMemory(4096);
        ServiceOfferingVO offering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(offering.getCpu()).thenReturn(null);
        Mockito.when(offering.getSpeed()).thenReturn(2000);
        Mockito.when(offering.getRamSize()).thenReturn(null);
        VolumeVO root = Mockito.mock(VolumeVO.class);
        Mockito.when(root.getSize()).thenReturn(100L * 1024L * 1024L * 1024L);

        Map<String, String> details = service.buildTargetVmDetails(plan, null, placement, offering, root,
                new DrResolvedTargetHardware());

        Assert.assertEquals("2", details.get("cpuNumber"));
        Assert.assertFalse(details.containsKey("cpuSpeed"));
        Assert.assertEquals("4096", details.get("memory"));
        Assert.assertEquals("LEGACY", details.get("UEFI"));
        Assert.assertFalse(details.get(DrVmDetailReplicationPolicy.REPLICATED_KEYS_DETAIL).contains("cpuSpeed"));
    }

    @Test
    public void staticTargetOfferingReceivesNoCustomComputeParameters() {
        DrPlanVO plan = new DrPlanVO("static-compute-details", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"vmDetails\":{"
                + "\"cpuNumber\":\"1\",\"cpuSpeed\":\"1000\",\"memory\":\"1024\"}}}}");
        DrResolvedTargetPlacement placement = new DrResolvedTargetPlacement();
        placement.setTargetCpuNumber(2);
        placement.setTargetCpuSpeed(2000);
        placement.setTargetMemory(4096);
        ServiceOfferingVO offering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(offering.getCpu()).thenReturn(2);
        Mockito.when(offering.getSpeed()).thenReturn(2000);
        Mockito.when(offering.getRamSize()).thenReturn(4096);
        VolumeVO root = Mockito.mock(VolumeVO.class);
        Mockito.when(root.getSize()).thenReturn(100L * 1024L * 1024L * 1024L);

        Map<String, String> details = service.buildTargetVmDetails(plan, null, placement, offering, root,
                new DrResolvedTargetHardware());

        Assert.assertFalse(details.containsKey("cpuNumber"));
        Assert.assertFalse(details.containsKey("cpuSpeed"));
        Assert.assertFalse(details.containsKey("memory"));
    }

    @Test
    public void existingReplicaPreservesTargetDetailsAndOnlyRefreshesDiagnosticFingerprint() {
        DrPlanVO plan = new DrPlanVO("details", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"fingerprint\":\"current-fingerprint\",\"vmDetails\":{"
                + "\"UEFI\":\"LEGACY\",\"tpmversion\":\"NONE\"}}}}");
        UserVmVO target = Mockito.mock(UserVmVO.class);
        Mockito.when(target.getId()).thenReturn(165L);
        Map<String, String> actual = new HashMap<String, String>();
        actual.put("UEFI", "LEGACY");
        actual.put("boot.mode", "LEGACY");
        actual.put("dr.source.hardware.fingerprint", "stale-fingerprint");
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(165L)).thenReturn(actual);

        service.reconcileSourceVmDetails(plan, target);

        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(165L, "boot.mode");
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).addDetail(165L, "tpmversion", "NONE", true);
        Mockito.verify(vmInstanceDetailsDao).removeDetail(165L, "dr.source.hardware.fingerprint");
        Mockito.verify(vmInstanceDetailsDao).addDetail(165L, "dr.source.hardware.fingerprint",
                "current-fingerprint", false);
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(165L,
                DrVmDetailReplicationPolicy.REPLICATED_KEYS_DETAIL);
    }
    @Test
    public void explicitFalseOmitsAgentKeyEvenWhenSourceThreadsAreTrue() {
        DrPlanVO plan = new DrPlanVO("tuning", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"vmDetails\":{\"iothreads\":\"true\",\"io.policy\":\"native\"}}}}");
        DrResolvedTargetHardware hardware = new DrResolvedTargetHardware();
        hardware.setIoThreadsEnabled(false);
        hardware.setIoPolicy(ApiConstants.IoDriverPolicy.IO_URING);
        VolumeVO root = Mockito.mock(VolumeVO.class);
        Mockito.when(root.getSize()).thenReturn(1024L * 1024L * 1024L);
        Map<String, String> details = service.buildTargetVmDetails(plan, null,
                new DrResolvedTargetPlacement(), null, root, hardware);
        Assert.assertFalse(details.containsKey("iothreads"));
        Assert.assertEquals("io_uring", details.get("io.policy"));
    }

    @Test
    public void reuseAllowsMissingThreadsAndDifferentPolicyWithoutChangingTarget() {
        DrPlanVO plan = new DrPlanVO("tuning", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"vmDetails\":{\"UEFI\":\"LEGACY\",\"iothreads\":\"true\"}}},"
                + "\"target\":{\"hardware\":{\"ioThreadsEnabled\":true,\"ioPolicy\":\"io_uring\"}}}");
        UserVmVO target = Mockito.mock(UserVmVO.class);
        Mockito.when(target.getId()).thenReturn(165L);
        Map<String, String> actual = new HashMap<String, String>();
        actual.put("UEFI", "LEGACY"); actual.put("io.policy", "threads");
        actual.put(DrVmDetailReplicationPolicy.REPLICATED_KEYS_DETAIL, "iothreads,io.policy,old.user.value");
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(165L)).thenReturn(actual);
        service.reconcileSourceVmDetails(plan, target);
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void reuseRejectsBootMismatchBeforeAnyDetailWrite() {
        DrPlanVO plan = new DrPlanVO("boot", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{\"vmDetails\":{\"UEFI\":\"SECURE\"}}}}");
        UserVmVO target = Mockito.mock(UserVmVO.class);
        Mockito.when(target.getId()).thenReturn(165L);
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(165L)).thenReturn(new HashMap<String, String>());
        try {
            service.reconcileSourceVmDetails(plan, target);
            Assert.fail("boot mismatch must fail");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("TARGET_BOOT_CONTRACT_MISMATCH"));
        }
        Mockito.verify(vmInstanceDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }

}
