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
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.cloud.agent.api.StorageServiceRuntimeFileType;
import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageRetainedLatestRuntimeReplayTest {
    private static final String ARCHIVE = "a".repeat(64);
    private static final String MANIFEST = "b".repeat(64);
    private static final String HELPER = "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a";
    private static class Manager extends StorageServiceRuntimeUpgradeManagerImpl {
        final List<String> events = new ArrayList<>();
        final Set<String> features = new HashSet<>();
        JsonObject target = new JsonObject();
        JsonObject consumer = new JsonObject();
        Runnable duringDownload;
        Runnable duringPreflight;
        boolean legacyManifest;
        boolean packageBlocked;
        String lastTransaction;
        @Override protected JsonObject sourceRootBinding(StorageServiceInstanceVO instance) { return target.deepCopy(); }
        @Override protected JsonObject freshConsumerObservation(StorageServiceInstanceVO instance) { return consumer.deepCopy(); }
        @Override protected byte[] resource(String path) { return new byte[]{1}; }
        @Override protected String requireTemplateRuntimeHelper(StorageServiceInstanceVO instance) { return HELPER; }
        @Override protected void requireRuntimePackageFeatures(StorageServiceInstanceVO instance) {
            events.add("PACKAGE_GUARD"); if (packageBlocked) throw new CloudRuntimeException("Package capability unavailable");
        }
        @Override protected Set<String> requiredRuntimeFeatures(StorageServiceInstanceVO instance) { return features; }
        @Override protected void requireRuntimeActivationSafety(StorageServiceInstanceVO instance) { events.add("FORMATTER_SAFETY"); }
        @Override protected byte[] download(String location, int limit) {
            events.add("DOWNLOAD"); if (duringDownload != null) { Runnable change = duringDownload; duringDownload = null; change.run(); }
            return new byte[]{1};
        }
        @Override protected byte[] trustedKey(String key) { return new byte[]{1}; }
        @Override protected void ensureBootstrap(StorageServiceInstanceVO instance, StorageServiceRuntimeBundleVO bundle, String transaction) { events.add("BOOTSTRAP"); }
        @Override protected void transfer(StorageServiceInstanceVO instance, String transaction, StorageServiceRuntimeFileType type,
                String key, byte[] bytes, int from, int to, StorageServiceRuntimeUpgradeVO upgrade) { events.add(type.name()); }
        @Override public JsonObject freshSignedRuntimeValidationProof(long instanceId, String expectedCliSha256) {
            throw new AssertionError("Ordinary after-swap validation proof must never authorize this replay");
        }
        @Override protected JsonObject invoke(StorageServiceInstanceVO instance, StorageServiceRuntimeOperation operation,
                String transaction, JsonObject request) {
            events.add(operation.name()); lastTransaction = transaction;
            if (operation == StorageServiceRuntimeOperation.PREFLIGHT && duringPreflight != null) duringPreflight.run();
            JsonObject result = new JsonObject(); result.addProperty("success", true); result.addProperty("phase", "RECEIVING");
            if (operation == StorageServiceRuntimeOperation.READBACK) return readback();
            return result;
        }
    }
    private Manager manager;
    private StorageServiceTemplateUpgradeVO row;
    private StorageServiceOperationVO operation;
    private StorageServiceRuntimeBundleVO bundle;
    private StorageServiceInstanceVO instance;
    private VolumeVO sourceVolume;
    private VolumeVO targetVolume;
    private VMInstanceVO vm;
    private JsonObject snapshot;
    private JsonObject approval;
    private String operationUuid;
    private String instanceUuid;

    private static JsonObject readback() {
        JsonObject value = new JsonObject();
        for (String key : new String[]{"success", "signedRuntimeVerified", "installedFilesVerified", "entrypointsVerified"}) value.addProperty(key, true);
        value.addProperty("currentVersion", "latest-code"); value.addProperty("archiveSha256", ARCHIVE);
        value.addProperty("manifestSha256", MANIFEST); value.addProperty("updaterSha256", HELPER); return value;
    }
    private JsonObject binding(long volumeId, String uuid, long templateId) {
        JsonObject value = new JsonObject(); value.addProperty("instanceUuid", instanceUuid); value.addProperty("vmId", 60L);
        value.addProperty("rootVolumeId", volumeId); value.addProperty("rootVolumeUuid", uuid); value.addProperty("templateId", templateId);
        value.addProperty("accountId", 2L); value.addProperty("zoneId", 1L); return value;
    }
    private void persist() { row.setSnapshotJson(snapshot.toString()); }
    @Before public void setup() {
        manager = new Manager(); instanceUuid = UUID.randomUUID().toString(); operationUuid = UUID.randomUUID().toString();
        instance = Mockito.mock(StorageServiceInstanceVO.class); Mockito.when(instance.getId()).thenReturn(6L);
        Mockito.when(instance.getUuid()).thenReturn(instanceUuid); Mockito.when(instance.getVmId()).thenReturn(60L);
        Mockito.when(instance.getAccountId()).thenReturn(2L); Mockito.when(instance.getDomainId()).thenReturn(7L);
        Mockito.when(instance.getDataCenterId()).thenReturn(1L);
        StorageServiceInstanceDao instances = Mockito.mock(StorageServiceInstanceDao.class); Mockito.when(instances.findById(6L)).thenReturn(instance);
        operation = Mockito.mock(StorageServiceOperationVO.class); Mockito.when(operation.getId()).thenReturn(3L);
        Mockito.when(operation.getUuid()).thenReturn(operationUuid); Mockito.when(operation.getInstanceId()).thenReturn(6L);
        Mockito.when(operation.getState()).thenReturn("RUNNING"); Mockito.when(operation.getAction()).thenReturn("ROOT_TEMPLATE_ROLLBACK");
        Mockito.when(operation.getRevision()).thenReturn(11L);
        StorageServiceOperationDao operations = Mockito.mock(StorageServiceOperationDao.class); Mockito.when(operations.findByUuid(operationUuid)).thenReturn(operation);
        row = new StorageServiceTemplateUpgradeVO(); row.setInstanceId(6L); row.setSharedFilesystemId(9L); row.setOperationId(3L);
        row.setPreviousRootVolumeId(10L); row.setTargetRootVolumeId(20L); row.setSourceTemplateId(41L); row.setTargetTemplateId(99L);
        StorageServiceTemplateUpgradeDao roots = Mockito.mock(StorageServiceTemplateUpgradeDao.class); Mockito.when(roots.findActive(6L)).thenReturn(row);
        SharedFSVO shared = Mockito.mock(SharedFSVO.class); Mockito.when(shared.getVmId()).thenReturn(60L);
        Mockito.when(shared.getAccountId()).thenReturn(2L); Mockito.when(shared.getDomainId()).thenReturn(7L); Mockito.when(shared.getDataCenterId()).thenReturn(1L);
        SharedFSDao sharedDao = Mockito.mock(SharedFSDao.class); Mockito.when(sharedDao.findById(9L)).thenReturn(shared);
        vm = Mockito.mock(VMInstanceVO.class); Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(vm.getHostId()).thenReturn(4L); Mockito.when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM); Mockito.when(vm.getTemplateId()).thenReturn(41L);
        VMInstanceDao vms = Mockito.mock(VMInstanceDao.class); Mockito.when(vms.findById(60L)).thenReturn(vm);
        com.cloud.vm.UserVmVO user = Mockito.mock(com.cloud.vm.UserVmVO.class);
        Mockito.when(user.getUserVmType()).thenReturn(com.cloud.vm.UserVmManager.SHAREDFSVM);
        com.cloud.vm.dao.UserVmDao users = Mockito.mock(com.cloud.vm.dao.UserVmDao.class); Mockito.when(users.findById(60L)).thenReturn(user);
        ReflectionTestUtils.setField(manager, "runtimeUserVmDao", users);
        sourceVolume = Mockito.mock(VolumeVO.class); targetVolume = Mockito.mock(VolumeVO.class);
        String sourceUuid = UUID.randomUUID().toString(), targetUuid = UUID.randomUUID().toString();
        for (VolumeVO volume : new VolumeVO[]{sourceVolume, targetVolume}) {
            Mockito.when(volume.getState()).thenReturn(Volume.State.Ready); Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
            Mockito.when(volume.getPoolId()).thenReturn(2L); Mockito.when(volume.getAccountId()).thenReturn(2L); Mockito.when(volume.getDataCenterId()).thenReturn(1L);
        }
        Mockito.when(sourceVolume.getUuid()).thenReturn(sourceUuid); Mockito.when(sourceVolume.getTemplateId()).thenReturn(99L);
        Mockito.when(targetVolume.getUuid()).thenReturn(targetUuid); Mockito.when(targetVolume.getTemplateId()).thenReturn(41L); Mockito.when(targetVolume.getInstanceId()).thenReturn(60L);
        VolumeDao volumes = Mockito.mock(VolumeDao.class); Mockito.when(volumes.findById(20L)).thenReturn(sourceVolume); Mockito.when(volumes.findById(10L)).thenReturn(targetVolume);
        manager.target = binding(10, targetUuid, 41);
        for (String key : new String[]{"managerVersion", "agentVersion", "templatePlatformVersion"}) manager.consumer.addProperty(key, "4.23.0.0");
        manager.consumer.addProperty("platformVersionKnown", true); manager.consumer.addProperty("updaterVerified", true);
        manager.consumer.addProperty("platformObservationRecorded", true); manager.consumer.addProperty("observedAtMillis", 1L);
        bundle = Mockito.mock(StorageServiceRuntimeBundleVO.class); Mockito.when(bundle.getId()).thenReturn(5L); Mockito.when(bundle.getUuid()).thenReturn("bundle-uuid");
        Mockito.when(bundle.getVersion()).thenReturn("latest-code"); Mockito.when(bundle.getSha256()).thenReturn(ARCHIVE); Mockito.when(bundle.getManifestSha256()).thenReturn(MANIFEST);
        Mockito.when(bundle.getSigningKeyId()).thenReturn("trusted-key"); Mockito.when(bundle.getRuntimeAbiVersion()).thenReturn("1"); Mockito.when(bundle.getDesiredStateSchemaVersion()).thenReturn("1");
        Mockito.when(bundle.getState()).thenReturn(StorageServiceRuntimeBundleVO.State.AVAILABLE); Mockito.when(bundle.getServiceImpact()).thenReturn(StorageServiceRuntimeBundleVO.ServiceImpact.NONE);
        StorageServiceRuntimeBundleDao bundles = Mockito.mock(StorageServiceRuntimeBundleDao.class); Mockito.when(bundles.findByUuid("bundle-uuid")).thenReturn(bundle);
        JsonObject scope = new JsonObject(); scope.addProperty("instanceUuid", instanceUuid); scope.addProperty("templateUpgradeUuid", row.getUuid()); scope.addProperty("operationUuid", operationUuid); scope.addProperty("revision", 11L);
        JsonObject source = new JsonObject(); source.add("pin", manager.runtimePin(bundle)); source.add("sourceRootBinding", binding(20, sourceUuid, 99));
        JsonObject approved = manager.runtimePin(bundle); approved.addProperty("verifiedAtMillis", new Date().getTime()); source.add("approvedInstalledLkg", approved);
        source.addProperty("updaterSha256", HELPER); source.add("verification", readback()); source.add("consumerObservation", manager.consumer.deepCopy());
        snapshot = new JsonObject(); snapshot.addProperty("manualSourcePrepared", true); snapshot.addProperty("manualSourceRootVolumeId", 20L); snapshot.addProperty("manualSourceTemplateId", 99L);
        snapshot.add("manualSourceSignedRuntime", source); JsonObject profile = new JsonObject(); profile.addProperty("profileArtifactUuid", UUID.randomUUID().toString()); snapshot.add("manualSourceValidationProfile", profile);
        snapshot.add("manualRollbackGeneration", new JsonObject()); JsonObject captured = new JsonObject(); captured.add("scope", scope.deepCopy()); snapshot.add("sourceCapture", captured);
        JsonObject identity = new JsonObject(); identity.add("sourceRootScope", scope.deepCopy()); snapshot.add("identity", identity);
        approval = new JsonObject(); approval.add("rootScope", scope); approval.add("sourceRuntime", source.deepCopy()); approval.add("sourceValidationProfile", profile.deepCopy());
        approval.addProperty("sourceRootVolumeId", 20L); approval.addProperty("sourceTemplateId", 99L); approval.addProperty("targetRootVolumeId", 10L); approval.addProperty("targetTemplateId", 41L); persist();
        ReflectionTestUtils.setField(manager, "instanceDao", instances); ReflectionTestUtils.setField(manager, "rootWriterDao", operations);
        ReflectionTestUtils.setField(manager, "rootUpgradeDao", roots); ReflectionTestUtils.setField(manager, "sharedFSDao", sharedDao);
        ReflectionTestUtils.setField(manager, "runtimeVolumeDao", volumes); ReflectionTestUtils.setField(manager, "vmInstanceDao", vms); ReflectionTestUtils.setField(manager, "bundleDao", bundles);
    }
    private MockedConstruction<StorageServiceRuntimeBundleVerifier> signatures() {
        return Mockito.mockConstruction(StorageServiceRuntimeBundleVerifier.class, (mock, context) -> {
            JsonObject verified = new JsonObject(), manifest = new JsonObject();
            if (!manager.legacyManifest) {
                JsonObject ranges = new JsonObject(); ranges.addProperty("schemaVersion", 1);
                for (String key : new String[]{"manager", "agent", "template"}) {
                    JsonObject range = new JsonObject(); range.addProperty("minimumVersion", "4.23.0.0"); range.addProperty("maximumVersionExclusive", "4.24.0.0"); ranges.add(key, range);
                }
                manifest.add("compatibility", ranges);
            }
            verified.add("manifest", manifest); Mockito.when(mock.verify(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(verified);
        });
    }
    private void restore() { manager.restoreRetainedLatestTemplateRuntime(6, manager.runtimePin(bundle), operationUuid, approval); }
    private void blocked() { Assert.assertThrows(CloudRuntimeException.class, this::restore); Assert.assertFalse(manager.events.contains("BOOTSTRAP")); Assert.assertFalse(manager.events.contains("ACTIVATE")); }
    @Test public void approvedLatestSourceCanActivateOnExactRetainedRootAsNewActivation() {
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> verifier = signatures()) {
            JsonObject result = manager.restoreRetainedLatestTemplateRuntime(6, manager.runtimePin(bundle), operationUuid, approval);
            Assert.assertTrue(manager.events.contains("ACTIVATE")); Assert.assertEquals("root-retained-latest-" + operationUuid, manager.lastTransaction);
            Assert.assertFalse(result.getAsJsonObject("consumerCompatibility").get("legacyExceptionApplied").getAsBoolean());
            Assert.assertTrue(result.getAsJsonObject("consumerCompatibility").get("rangeCompatibilityVerified").getAsBoolean());
        }
    }
    @Test public void dedicatedVerificationUsesSameTransactionAndFreshStrictConsumer() {
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> verifier = signatures()) {
            manager.verifyRetainedLatestTemplateRuntime(6, manager.runtimePin(bundle), operationUuid, approval);
            Assert.assertFalse(manager.events.contains("ACTIVATE")); Assert.assertEquals("root-retained-latest-" + operationUuid, manager.lastTransaction);
            Assert.assertEquals("READBACK", manager.events.get(manager.events.size() - 1));
        }
    }
    @Test public void unknownCurrentConsumerCannotBorrowOriginalUnknownRollbackException() {
        snapshot.add("sourceSignedRuntime", snapshot.get("manualSourceSignedRuntime").deepCopy()); persist();
        manager.consumer.add("templatePlatformVersion", JsonNull.INSTANCE); manager.consumer.addProperty("platformVersionKnown", false);
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> verifier = signatures()) { blocked(); }
    }
    @Test public void legacyManifestCannotActivateLatestOnRetainedRoot() {
        manager.legacyManifest = true; try (MockedConstruction<StorageServiceRuntimeBundleVerifier> verifier = signatures()) { blocked(); }
    }
    @Test public void foreignOperationOrRevisionCannotReachEffects() {
        Mockito.when(operation.getAction()).thenReturn("ROOT_TEMPLATE_UPGRADE"); blocked();
        Mockito.when(operation.getAction()).thenReturn("ROOT_TEMPLATE_ROLLBACK"); Mockito.when(operation.getRevision()).thenReturn(12L); blocked();
    }
    @Test public void callerCannotModifyFrozenSourceProfileOrAddAField() {
        approval.getAsJsonObject("sourceValidationProfile").addProperty("callerApproved", true); blocked();
        approval.getAsJsonObject("sourceValidationProfile").remove("callerApproved"); approval.addProperty("waiver", true); blocked();
    }
    @Test public void staleSourceSnapshotIsRejectedBeforeBootstrap() {
        snapshot.getAsJsonObject("manualSourceValidationProfile").addProperty("revision", 2L); persist(); blocked();
    }
    @Test public void sourceRootIdentityOrTenantReplacementIsRejected() {
        Mockito.when(sourceVolume.getUuid()).thenReturn(UUID.randomUUID().toString()); blocked();
    }
    @Test public void actualTargetRootAndTemplateMustMatchTheApprovedRetainedRoot() {
        manager.target.addProperty("rootVolumeId", 20L); blocked();
        manager.target.addProperty("rootVolumeId", 10L); Mockito.when(vm.getTemplateId()).thenReturn(99L); blocked();
    }
    @Test public void changedSignedPinCannotBorrowSourceApproval() {
        JsonObject different = manager.runtimePin(bundle); different.addProperty("manifestSha256", "c".repeat(64));
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.restoreRetainedLatestTemplateRuntime(6, different, operationUuid, approval));
        Assert.assertTrue(manager.events.isEmpty());
    }
    @Test public void sourceApprovalWithoutVerifiedLkgReadbackIsRejected() {
        snapshot.getAsJsonObject("manualSourceSignedRuntime").getAsJsonObject("verification").addProperty("installedFilesVerified", false);
        approval.add("sourceRuntime", snapshot.get("manualSourceSignedRuntime").deepCopy()); persist(); blocked();
    }
    @Test public void typedApprovalIdsCannotBeStringsOrFractions() {
        snapshot.addProperty("manualSourceRootVolumeId", "20"); persist(); blocked();
        snapshot.addProperty("manualSourceRootVolumeId", 20.5); persist(); blocked();
    }
    @Test public void signedFeatureLossCannotBeWaivedByFrozenValidationProfile() {
        manager.features.add("SERVICE_MAINTENANCE"); try (MockedConstruction<StorageServiceRuntimeBundleVerifier> verifier = signatures()) { blocked(); }
    }
    @Test public void packageFeatureLossIsRejectedBeforeAnyTransfer() {
        manager.packageBlocked = true; blocked(); Assert.assertTrue(manager.events.contains("PACKAGE_GUARD"));
    }
    @Test public void snapshotChangeDuringDownloadIsRejectedBeforeBootstrap() {
        manager.duringDownload = () -> { snapshot.addProperty("manualSourcePrepared", false); persist(); };
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> verifier = signatures()) { blocked(); }
    }
    @Test public void snapshotChangeAfterPreflightCannotActivateCode() {
        manager.duringPreflight = () -> { snapshot.getAsJsonObject("manualSourceValidationProfile").addProperty("revision", 2L); persist(); };
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> verifier = signatures()) {
            Assert.assertThrows(CloudRuntimeException.class, this::restore); Assert.assertTrue(manager.events.contains("PREFLIGHT")); Assert.assertFalse(manager.events.contains("ACTIVATE"));
        }
    }
    @Test public void legacyDeployedFamilyRejectsBothNewPurposes() {
        StorageServiceRuntimeUpgradeManager legacy = Mockito.mock(StorageServiceRuntimeUpgradeManager.class, Mockito.CALLS_REAL_METHODS);
        Assert.assertThrows(CloudRuntimeException.class, () -> legacy.restoreRetainedLatestTemplateRuntime(6, approval, operationUuid, approval));
        Assert.assertThrows(CloudRuntimeException.class, () -> legacy.verifyRetainedLatestTemplateRuntime(6, approval, operationUuid, approval));
    }
}
