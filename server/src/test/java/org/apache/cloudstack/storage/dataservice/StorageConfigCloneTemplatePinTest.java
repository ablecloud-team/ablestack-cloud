// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageConfigCloneTemplatePinTest {
    private static String id(int value) { return String.format("00000000-0000-4000-8000-%012d", value); }
    private static String sha(String value) { return StorageConfigArchive.sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static final String BUNDLE = id(610);
    private static final String ARTIFACT = id(900);
    private static final long VM_ID = 55L;

    private static class BoundaryStop extends RuntimeException { }

    private final class Manager extends StorageServiceManagerImpl {
        JsonObject runtimePin = new JsonObject();
        int cloudCreates;
        int runtimeCalls;
        int bindCalls;
        @Override protected void requireConfigurationAdministrator() { }
        @Override public String captureConfigurationSnapshot(long instanceId) { return "{}"; }
        @Override protected void preflightConfigurationRuntimeBundle(String bundleUuid) { }
        @Override protected JsonObject configurationCloneRuntimePin(String bundleUuid) { return runtimePin.deepCopy(); }
        @Override protected CreateSharedFSCmd configurationCreateCommand(JsonObject blueprint) {
            // Only bypass Spring command injection; the production template resolver/pin methods are exercised.
            JsonObject numeric = blueprint.deepCopy();
            numeric.addProperty("zoneid", 1);
            if (blueprint.has("templateid")) {
                VMTemplateVO selected = templates.findByUuid(blueprint.get("templateid").getAsString());
                numeric.addProperty("templateid", selected.getId());
            }
            return (CreateSharedFSCmd) StorageConfigCommandBinding.bind(CreateSharedFSCmd.class, numeric);
        }
        @Override protected StorageServiceInstanceVO createConfigurationNewService(JsonObject blueprint, JsonObject plan, StorageConfigArtifactVO artifact) {
            cloudCreates++;
            JsonObject metadata = com.google.gson.JsonParser.parseString(artifact.getMetadataJson()).getAsJsonObject();
            metadata.add("cloneAllocation", new JsonObject());artifact.setMetadataJson(metadata.toString());
            return target;
        }
        @Override protected void upgradeConfigurationNewServiceRuntime(StorageServiceInstanceVO instance, String bundleUuid) {
            runtimeCalls++;
            throw new BoundaryStop();
        }
        @Override protected StorageServiceInstanceVO configurationInstanceByUuid(String uuid) {
            Assert.assertEquals(target.getUuid(), uuid);
            return target;
        }
        @Override protected JsonObject bindConfigurationVolumeExecution(StorageServiceInstanceVO instance,
                StorageConfigArtifactVO artifact, JsonObject plan) {
            bindCalls++;
            throw new BoundaryStop();
        }
    }

    private Manager manager;
    private VMTemplateDao templates;
    private SharedFSService service;
    private VMTemplateVO a;
    private VMTemplateVO b;
    private VMTemplateVO userTemplate;
    private JsonObject blueprint;
    private JsonObject privatePermit;
    private JsonObject privateRequest;
    private Set<String> exclusions;
    private StorageServiceInstanceVO source;
    private StorageServiceInstanceVO target;
    private UserVmVO vm;
    private VolumeVO root;
    private StorageConfigArtifactDao artifacts;
    private StorageServiceOperationDao operations;
    private StorageConfigArtifactVO artifact;
    private StorageConfigRequest request;
    private StorageServiceConfiguration configuration;
    private Path directory;
    private StorageConfigArtifactStore store;
    private AtomicReference<String> savedMetadata;
    private JsonObject plan;
    private String archiveSha;

    private VMTemplateVO template(int number, Storage.TemplateType type) {
        VMTemplateVO value = Mockito.mock(VMTemplateVO.class);
        Mockito.when(value.getId()).thenReturn((long) number);
        Mockito.when(value.getUuid()).thenReturn(id(number));
        Mockito.when(value.getAccountId()).thenReturn(2L);
        Mockito.when(value.getTemplateType()).thenReturn(type);
        Mockito.when(value.getState()).thenReturn(VirtualMachineTemplate.State.Active);
        Mockito.when(value.isDynamicallyScalable()).thenReturn(true);
        Mockito.when(value.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        Mockito.when(value.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(value.getArch()).thenReturn(com.cloud.cpu.CPU.CPUArch.amd64);
        Mockito.when(value.getChecksum()).thenReturn("{SHA-256}" + "a".repeat(64));
        Mockito.when(value.getDetails()).thenReturn(Map.of("storage.service.source.commit", "b".repeat(40), "test", "one"));
        Mockito.when(templates.findByUuid(id(number))).thenReturn(value);
        return value;
    }

    @Before public void setup() throws Exception {
        User actor = Mockito.mock(User.class);
        Mockito.when(actor.getId()).thenReturn(11L);
        CallContext.register(actor, Mockito.mock(Account.class));
        templates = Mockito.mock(VMTemplateDao.class);
        service = Mockito.mock(SharedFSService.class);
        manager = new Manager();
        ReflectionTestUtils.setField(manager, "rootUpgradeTemplateDao", templates);
        ReflectionTestUtils.setField(manager, "configurationSharedFsService", service);
        DataCenterDao zones = Mockito.mock(DataCenterDao.class);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(1L);
        Mockito.when(zone.getUuid()).thenReturn(id(903));
        Mockito.when(zones.findByUuid(id(903))).thenReturn(zone);
        ReflectionTestUtils.setField(manager, "dataCenterDao", zones);
        a = template(601, Storage.TemplateType.SYSTEM);
        b = template(602, Storage.TemplateType.SYSTEM);
        userTemplate = template(603, Storage.TemplateType.USER);
        Mockito.when(templates.findSystemVMReadyTemplate(Mockito.eq(1L), Mockito.eq(HypervisorType.KVM), Mockito.anyString())).thenReturn(a);
        Mockito.when(service.preflightSharedFS(Mockito.any())).thenAnswer(call -> {
            CreateSharedFSCmd cmd = call.getArgument(0);
            if (cmd.getTemplateId().equals(userTemplate.getId())) {
                StorageTemplateFixturePermit.verify(privatePermit, privateRequest, exclusions, System.currentTimeMillis());
            }
            return Mockito.mock(SharedFSVO.class);
        });
        blueprint = new JsonObject();
        blueprint.addProperty("name", "clone-fixture");
        blueprint.addProperty("zoneid", id(903));
        blueprint.addProperty("size", 20);
        blueprint.addProperty("filesystem", "XFS");
        blueprint.addProperty("backingvolumemode", "NEW");
        exclusions = Set.of(id(101), id(102), id(103), id(104), id(105), id(106), id(107));
        privateRequest = new JsonObject();
        privateRequest.addProperty("rootProvisioningType", "SPARSE");
        privateRequest.addProperty("dataProvisioningType", "SPARSE");
        privateRequest.addProperty("backingVolumeMode", "NEW");
        privatePermit = new JsonObject();
        privatePermit.addProperty("schemaVersion", 1);
        privatePermit.addProperty("kind", "NEW_SPARSE_PRECREATE");
        privatePermit.addProperty("newDisposableFixture", true);
        privatePermit.addProperty("originalDataExcluded", true);
        privatePermit.addProperty("newDataWithoutBacking", true);
        privatePermit.addProperty("sourceCommit", "b".repeat(40));
        privatePermit.addProperty("expectedCliSha256", "c".repeat(64));
        privatePermit.addProperty("expiresAtMillis", System.currentTimeMillis() + 3600000L);
        privatePermit.add("request", privateRequest.deepCopy());
        JsonArray excluded = new JsonArray();
        exclusions.forEach(excluded::add);
        privatePermit.add("excludedInstanceUuids", excluded);
        source = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(source.getId()).thenReturn(7L);
        Mockito.when(source.getUuid()).thenReturn(id(700));
        target = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(target.getId()).thenReturn(77L);
        Mockito.when(target.getUuid()).thenReturn(id(902));
        Mockito.when(target.getVmId()).thenReturn(VM_ID);
        Mockito.when(target.getAccountId()).thenReturn(2L);
        Mockito.when(target.getDataCenterId()).thenReturn(1L);
        UserVmDao vms = Mockito.mock(UserVmDao.class);
        vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getId()).thenReturn(VM_ID);
        Mockito.when(vm.getUuid()).thenReturn(id(55));
        Mockito.when(vm.getTemplateId()).thenReturn(601L);
        Mockito.when(vm.getAccountId()).thenReturn(2L);
        Mockito.when(vm.getDataCenterId()).thenReturn(1L);
        Mockito.when(vms.findById(VM_ID)).thenReturn(vm);
        ReflectionTestUtils.setField(manager, "rootUpgradeVmDao", vms);
        SharedFSDao shared = Mockito.mock(SharedFSDao.class);
        SharedFSVO share = Mockito.mock(SharedFSVO.class);
        Mockito.when(share.getVmId()).thenReturn(VM_ID);
        Mockito.when(share.getAccountId()).thenReturn(2L);
        Mockito.when(share.getDataCenterId()).thenReturn(1L);
        Mockito.when(shared.findByVm(VM_ID)).thenReturn(share);
        ReflectionTestUtils.setField(manager, "sharedFSDao", shared);
        VolumeDao volumes = Mockito.mock(VolumeDao.class);
        root = Mockito.mock(VolumeVO.class);
        Mockito.when(root.getUuid()).thenReturn(id(56));
        Mockito.when(root.getInstanceId()).thenReturn(VM_ID);
        Mockito.when(root.getTemplateId()).thenReturn(601L);
        Mockito.when(root.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(root.getAccountId()).thenReturn(2L);
        Mockito.when(root.getDataCenterId()).thenReturn(1L);
        Mockito.when(volumes.findByInstanceAndType(VM_ID, Volume.Type.ROOT)).thenReturn(List.of(root));
        ReflectionTestUtils.setField(manager, "volumeDao", volumes);
        artifacts = Mockito.mock(StorageConfigArtifactDao.class);
        operations = Mockito.mock(StorageServiceOperationDao.class);
        directory = Files.createTempDirectory("clone-template-pin-");
        store = new StorageConfigArtifactStore(directory);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("desired/instance.json", "{\"uuid\":\"synthetic-source\"}".getBytes(StandardCharsets.UTF_8));
        byte[] archive = StorageConfigArchive.create(entries, new JsonObject());
        archiveSha = StorageConfigArchive.sha256(archive);
        store.write(ARTIFACT, archive);
        artifact = Mockito.mock(StorageConfigArtifactVO.class);
        Mockito.when(artifact.getId()).thenReturn(8L);
        Mockito.when(artifact.getUuid()).thenReturn(ARTIFACT);
        Mockito.when(artifact.getInstanceId()).thenReturn(7L);
        Mockito.when(artifact.getState()).thenReturn("ARCHIVE_VALIDATED");
        Mockito.when(artifact.getSha256()).thenReturn(archiveSha);
        savedMetadata = new AtomicReference<>();
        Mockito.when(artifact.getMetadataJson()).thenAnswer(call -> savedMetadata.get());
        Mockito.doAnswer(call -> { savedMetadata.set(call.getArgument(0)); return null; }).when(artifact).setMetadataJson(Mockito.anyString());
        Mockito.when(artifacts.findById(8L)).thenReturn(artifact);
        Mockito.when(artifacts.lockRow(8L, true)).thenReturn(artifact);
        Mockito.when(artifacts.update(Mockito.eq(8L), Mockito.any())).thenReturn(true);
        configuration = new StorageServiceConfiguration(manager, artifacts, operations, store);
        request = Mockito.mock(StorageConfigRequest.class);
        Mockito.when(request.getArtifactId()).thenReturn(8L);
        Mockito.when(request.getPlanToken()).thenReturn("synthetic-token");
        Mockito.when(request.getConfirmation()).thenReturn("clone-fixture");
        manager.runtimePin.addProperty("bundleUuid", BUNDLE);
        manager.runtimePin.addProperty("bundleSha256", "d".repeat(64));
    }

    @After public void cleanup() throws Exception {
        CallContext.unregister();
        if (store != null) store.remove(ARTIFACT);
        if (directory != null) Files.delete(directory);
    }

    private void reviewed(JsonObject pin) {
        plan = new JsonObject();
        plan.addProperty("targetMode", "CREATE_NEW");
        plan.addProperty("targetName", "clone-fixture");
        plan.addProperty("targetInstanceUuid", id(902));
        plan.addProperty("expectedRevision", 0);
        plan.addProperty("artifactSha256", archiveSha);
        plan.add("requiredCredentials", new JsonArray());
        plan.add("createNew", blueprint.deepCopy());
        plan.addProperty("runtimeBundleUuid", BUNDLE);
        plan.add("cloneRuntimePin", manager.runtimePin.deepCopy());
        plan.add("cloneTemplatePin", pin.deepCopy());
        StorageConfigurationVolumePlanTest.Fixture volumes = new StorageConfigurationVolumePlanTest.Fixture(1);
        volumes.scope.addProperty("artifactSha256", archiveSha);
        plan.add("volumeAllocationPlan", volumes.build());
        JsonObject capability = new JsonObject();
        capability.addProperty("user", 11);
        capability.addProperty("expires", System.currentTimeMillis() + 300000L);
        capability.addProperty("hash", sha("synthetic-token"));
        capability.addProperty("planSha256", sha(plan.toString()));
        capability.addProperty("baselineSha256", sha("{}"));
        JsonObject metadata = new JsonObject();
        metadata.addProperty("planState", "PLANNED");
        metadata.add("plan", plan.deepCopy());
        metadata.add("planToken", capability);
        savedMetadata.set(metadata.toString());
    }

    private void applyReviewed() {
        // Exercise the production capability-consumption/prepare body; only its outer distributed lock is bypassed.
        ReflectionTestUtils.invokeMethod(configuration, "applyLocked", source, request);
    }

    private JsonObject saved() { return com.google.gson.JsonParser.parseString(savedMetadata.get()).getAsJsonObject(); }

    @Test public void omittedDefaultIsResolvedPinnedAndNormalizedForCreation() {
        JsonObject caller = blueprint.deepCopy();
        JsonObject normalized = caller.deepCopy();
        JsonObject pin = manager.pinConfigurationCloneTemplate(normalized);
        Assert.assertFalse(caller.has("templateid"));
        Assert.assertEquals(a.getUuid(), normalized.get("templateid").getAsString());
        Assert.assertEquals("DEFAULT_SYSTEM", pin.get("selectionMode").getAsString());
        Assert.assertEquals(a.getChecksum(), pin.get("templateChecksum").getAsString());
        ArgumentCaptor<CreateSharedFSCmd> commands = ArgumentCaptor.forClass(CreateSharedFSCmd.class);
        Mockito.verify(service).preflightSharedFS(commands.capture());
        Assert.assertEquals(Long.valueOf(a.getId()), commands.getValue().getTemplateId());
        manager.requireConfigurationCloneTemplatePin(normalized, pin, null);
    }

    @Test public void changedDefaultRejectsProductionApplyBeforeCapabilityConsumptionOrAllocation() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        reviewed(pin);
        Mockito.when(templates.findSystemVMReadyTemplate(Mockito.eq(1L), Mockito.eq(HypervisorType.KVM), Mockito.anyString())).thenReturn(b);
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertTrue(saved().has("planToken"));
        Assert.assertEquals("PLANNED", saved().get("planState").getAsString());
        Assert.assertEquals(0, manager.cloudCreates);
        Assert.assertEquals(0, manager.runtimeCalls);
        Mockito.verify(artifacts, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
    }

    @Test public void stableDefaultReachesOneCreationAndPersistsRealizedBindingBeforeRuntimeEffect() {
        reviewed(manager.pinConfigurationCloneTemplate(blueprint));
        Assert.assertThrows(BoundaryStop.class, this::applyReviewed);
        Assert.assertEquals(1, manager.cloudCreates);
        Assert.assertEquals(1, manager.runtimeCalls);
        Assert.assertFalse(saved().has("planToken"));
        Assert.assertEquals(target.getUuid(), saved().get("createdTargetInstanceUuid").getAsString());
        Assert.assertEquals(root.getUuid(), saved().getAsJsonObject("createdTargetTemplateBinding").get("rootVolumeUuid").getAsString());
    }

    @Test public void explicitSystemDoesNotAcquireUnrelatedDefaultSemantics() {
        blueprint.addProperty("templateid", a.getUuid());
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        Assert.assertEquals("EXPLICIT_SYSTEM", pin.get("selectionMode").getAsString());
        Mockito.clearInvocations(templates);
        Mockito.when(templates.findSystemVMReadyTemplate(Mockito.eq(1L), Mockito.eq(HypervisorType.KVM), Mockito.anyString())).thenReturn(b);
        manager.requireConfigurationCloneTemplatePin(blueprint, pin, null);
        Mockito.verify(templates, Mockito.never()).findSystemVMReadyTemplate(Mockito.anyLong(), Mockito.any(), Mockito.anyString());
    }

    @Test public void checksumMetadataAndMissingLegacyPinRejectBeforeApplyMutation() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        reviewed(pin);
        Mockito.when(a.getChecksum()).thenReturn("{SHA-256}" + "e".repeat(64));
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Mockito.when(a.getChecksum()).thenReturn(pin.get("templateChecksum").getAsString());
        Mockito.when(a.getDetails()).thenReturn(Map.of("test", "changed"));
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        JsonObject metadata = saved();
        metadata.getAsJsonObject("plan").remove("cloneTemplatePin");
        metadata.getAsJsonObject("planToken").addProperty("planSha256", sha(metadata.getAsJsonObject("plan").toString()));
        savedMetadata.set(metadata.toString());
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertTrue(saved().has("planToken"));
        Assert.assertEquals(0, manager.cloudCreates);
    }

    @Test public void metadataKeyOrderDoesNotChangeCanonicalPin() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("test", "one");
        reversed.put("storage.service.source.commit", "b".repeat(40));
        Mockito.when(a.getDetails()).thenReturn(reversed);
        manager.requireConfigurationCloneTemplatePin(blueprint, pin, null);
    }

    @Test public void inactiveOrForeignSelectionModeCannotAllocate() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        reviewed(pin);
        Mockito.when(a.getState()).thenReturn(VirtualMachineTemplate.State.Inactive);
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Mockito.when(a.getState()).thenReturn(VirtualMachineTemplate.State.Active);
        Mockito.when(a.getTemplateType()).thenReturn(Storage.TemplateType.USER);
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertEquals(0, manager.cloudCreates);
    }

    @Test public void privateUserReusesExistingPermitValidationAndRejectsExpiryOrScopeChange() {
        blueprint.addProperty("templateid", userTemplate.getUuid());
        blueprint.addProperty("validationartifactuuid", id(720));
        blueprint.addProperty("validationartifactsha256", "f".repeat(64));
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        Assert.assertEquals("PRIVATE_USER", pin.get("selectionMode").getAsString());
        reviewed(pin);
        privatePermit.addProperty("expiresAtMillis", 1);
        Assert.assertThrows(CloudRuntimeException.class, this::applyReviewed);
        privatePermit.addProperty("expiresAtMillis", System.currentTimeMillis() + 3600000L);
        privateRequest.addProperty("foreignScope", true);
        Assert.assertThrows(CloudRuntimeException.class, this::applyReviewed);
        privateRequest.remove("foreignScope");
        exclusions = Set.of(id(101), id(102), id(103), id(104), id(105), id(106), id(107), id(108));
        Assert.assertThrows(CloudRuntimeException.class, this::applyReviewed);
        Assert.assertTrue(saved().has("planToken"));
        Assert.assertEquals(0, manager.cloudCreates);
    }

    @Test public void createdTargetResumeKeepsRealizedRootAndNeverSelectsOrCreatesNewDefault() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        reviewed(pin);
        JsonObject metadata = saved();
        metadata.addProperty("createdTargetInstanceUuid", target.getUuid());
        metadata.addProperty("runtimePreparedBundleUuid", BUNDLE);
        metadata.add("createdTargetTemplateBinding", manager.configurationCloneCreatedTemplateBinding(target, pin));
        savedMetadata.set(metadata.toString());
        Mockito.when(templates.findSystemVMReadyTemplate(Mockito.eq(1L), Mockito.eq(HypervisorType.KVM), Mockito.anyString())).thenReturn(b);
        Mockito.clearInvocations(templates, service);
        Assert.assertThrows(BoundaryStop.class, this::applyReviewed);
        Assert.assertEquals(0, manager.cloudCreates);
        Assert.assertEquals(0, manager.runtimeCalls);
        Assert.assertEquals(1, manager.bindCalls);
        Mockito.verify(templates, Mockito.never()).findSystemVMReadyTemplate(Mockito.anyLong(), Mockito.any(), Mockito.anyString());
        Mockito.verifyNoInteractions(service);
    }

    @Test public void changedRealizedRootOrVmIsRejectedWithoutNewAllocation() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        reviewed(pin);
        JsonObject metadata = saved();
        metadata.addProperty("createdTargetInstanceUuid", target.getUuid());
        metadata.add("createdTargetTemplateBinding", manager.configurationCloneCreatedTemplateBinding(target, pin));
        savedMetadata.set(metadata.toString());
        Mockito.when(root.getTemplateId()).thenReturn(602L);
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertTrue(saved().has("planToken"));
        Mockito.when(root.getTemplateId()).thenReturn(601L);
        Mockito.when(root.getUuid()).thenReturn(id(999));
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Mockito.when(root.getUuid()).thenReturn(id(56));
        Mockito.when(vm.getAccountId()).thenReturn(3L);
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertEquals(0, manager.cloudCreates);
    }

    private void explicitlyReviewFailedBinding(JsonObject pin) {
        JsonObject metadata = saved();
        JsonObject binding = manager.reviewConfigurationCloneFailedTemplateBinding(target, pin,
                metadata.getAsJsonObject("createdTargetTemplateBindingFailure"), ARTIFACT);
        JsonObject reviewed = metadata.getAsJsonObject("plan");
        reviewed.add("reviewedCreatedTargetTemplateBinding", binding.deepCopy());
        JsonObject capability = new JsonObject();
        capability.addProperty("user", 11);
        capability.addProperty("expires", System.currentTimeMillis() + 300000L);
        capability.addProperty("hash", sha("synthetic-token"));
        capability.addProperty("planSha256", sha(reviewed.toString()));
        capability.addProperty("baselineSha256", sha("{}"));
        metadata.add("planToken", capability);
        metadata.addProperty("planState", "PLANNED");
        savedMetadata.set(metadata.toString());
    }

    @Test public void firstDurableTargetRecordIncludesVerifiedBindingEvenWhenResponseIsLost() {
        reviewed(manager.pinConfigurationCloneTemplate(blueprint));
        AtomicBoolean interrupted = new AtomicBoolean();
        Mockito.when(artifacts.update(Mockito.eq(8L), Mockito.any())).thenAnswer(call -> {
            JsonObject persisted = saved();
            if (persisted.has("createdTargetInstanceUuid") && interrupted.compareAndSet(false, true)) {
                Assert.assertTrue(persisted.has("createdTargetTemplateBinding"));
                throw new BoundaryStop();
            }
            return true;
        });
        Assert.assertThrows(BoundaryStop.class, this::applyReviewed);
        Assert.assertTrue(interrupted.get());
        Assert.assertTrue(saved().has("createdTargetTemplateBinding"));
        Assert.assertEquals(1, manager.cloudCreates);
        Assert.assertEquals(0, manager.runtimeCalls);
    }

    @Test public void failedBindingPreservesTargetAndRequiresExplicitReviewBeforeSameRootResume() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        reviewed(pin);
        Mockito.when(root.getState()).thenReturn(Volume.State.Creating);
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertEquals(1, manager.cloudCreates);
        Assert.assertEquals(0, manager.runtimeCalls);
        Assert.assertEquals(target.getUuid(), saved().get("createdTargetInstanceUuid").getAsString());
        Assert.assertFalse(saved().has("createdTargetTemplateBinding"));
        JsonObject failure = saved().getAsJsonObject("createdTargetTemplateBindingFailure");
        Assert.assertTrue(failure.get("rootIdentityCaptured").getAsBoolean());
        Assert.assertEquals(root.getUuid(), failure.get("rootVolumeUuid").getAsString());
        Assert.assertEquals("TARGET_TEMPLATE_BINDING_RECOVERY_REQUIRED", saved().get("restoreState").getAsString());
        Mockito.when(root.getState()).thenReturn(Volume.State.Ready);
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertEquals(1, manager.cloudCreates);
        explicitlyReviewFailedBinding(pin);
        Mockito.when(templates.findSystemVMReadyTemplate(Mockito.eq(1L), Mockito.eq(HypervisorType.KVM), Mockito.anyString())).thenReturn(b);
        Mockito.clearInvocations(templates);
        Assert.assertThrows(BoundaryStop.class, this::applyReviewed);
        Assert.assertEquals(1, manager.cloudCreates);
        Assert.assertEquals(1, manager.runtimeCalls);
        Assert.assertEquals(root.getUuid(), saved().getAsJsonObject("createdTargetTemplateBinding").get("rootVolumeUuid").getAsString());
        Assert.assertTrue(saved().get("createdTargetTemplateBindingRecoveredByReviewedPlan").getAsBoolean());
        Mockito.verify(templates, Mockito.never()).findSystemVMReadyTemplate(Mockito.anyLong(), Mockito.any(), Mockito.anyString());
    }

    @Test public void missingBindingWithoutKnownCreationProvenanceIsNeverAdopted() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        reviewed(pin);
        JsonObject metadata = saved();
        metadata.addProperty("createdTargetInstanceUuid", target.getUuid());
        savedMetadata.set(metadata.toString());
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertTrue(saved().has("planToken"));
        Assert.assertFalse(saved().has("createdTargetTemplateBinding"));
        Assert.assertEquals(0, manager.cloudCreates);
        JsonObject uncaptured = manager.configurationCloneFailedTemplateProvenance(target, pin, ARTIFACT);
        uncaptured.addProperty("rootIdentityCaptured", false);
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> manager.reviewConfigurationCloneFailedTemplateBinding(target, pin, uncaptured, ARTIFACT));
    }

    @Test public void recoveryReviewCannotApproveReplacementRootBetweenReviewAndApply() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        reviewed(pin);
        Mockito.when(root.getState()).thenReturn(Volume.State.Creating);
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Mockito.when(root.getState()).thenReturn(Volume.State.Ready);
        explicitlyReviewFailedBinding(pin);
        Mockito.when(root.getUuid()).thenReturn(id(999));
        Assert.assertThrows(InvalidParameterValueException.class, this::applyReviewed);
        Assert.assertTrue(saved().has("planToken"));
        Assert.assertFalse(saved().has("createdTargetTemplateBinding"));
        Assert.assertEquals(1, manager.cloudCreates);
        Assert.assertEquals(0, manager.runtimeCalls);
    }

    @Test public void strictPinRejectsExtraFieldsAndNonServerIntegerOrMalformedTypes() {
        JsonObject pin = manager.pinConfigurationCloneTemplate(blueprint);
        JsonObject extra = pin.deepCopy();
        extra.addProperty("extra", "untrusted");
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> manager.requireConfigurationCloneTemplatePin(blueprint, extra, null));
        JsonObject textSchema = pin.deepCopy();
        textSchema.addProperty("schemaVersion", "1");
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> manager.requireConfigurationCloneTemplatePin(blueprint, textSchema, null));
        JsonObject decimalSchema = pin.deepCopy();
        decimalSchema.addProperty("schemaVersion", 1.0);
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> manager.requireConfigurationCloneTemplatePin(blueprint, decimalSchema, null));
        JsonObject badDigest = pin.deepCopy();
        badDigest.addProperty("templateDetailsSha256", true);
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> manager.requireConfigurationCloneTemplatePin(blueprint, badDigest, null));
        JsonObject badUuid = pin.deepCopy();
        badUuid.add("templateUuid", new JsonArray());
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> manager.requireConfigurationCloneTemplatePin(blueprint, badUuid, null));
        Assert.assertEquals(0, manager.cloudCreates);
    }

    @Test public void adSourceCannotNormalizeOmittedOrNullTemplateIntoDefault() {
        JsonObject omitted = blueprint.deepCopy();
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> ReflectionTestUtils.invokeMethod(configuration, "pinRequestedConfigurationCloneTemplate", omitted, true));
        JsonObject missing = blueprint.deepCopy();
        missing.add("templateid", com.google.gson.JsonNull.INSTANCE);
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> ReflectionTestUtils.invokeMethod(configuration, "pinRequestedConfigurationCloneTemplate", missing, true));
        Assert.assertFalse(omitted.has("templateid"));
        Mockito.verify(templates, Mockito.never()).findSystemVMReadyTemplate(Mockito.anyLong(), Mockito.any(), Mockito.anyString());
        Mockito.verifyNoInteractions(service);
        Assert.assertEquals(0, manager.cloudCreates);
    }

    @Test public void adSourceRequiresSeedAbsentBeforeTemplatePinAndUsesExplicitMode() {
        blueprint.addProperty("templateid", a.getUuid());
        Mockito.when(a.getDetails()).thenReturn(Map.of("storage.service.local.identity.seed.absent", "false"));
        Assert.assertThrows(InvalidParameterValueException.class,
                () -> ReflectionTestUtils.invokeMethod(configuration, "pinRequestedConfigurationCloneTemplate", blueprint, true));
        Mockito.verifyNoInteractions(service);
        Mockito.when(a.getDetails()).thenReturn(Map.of("storage.service.local.identity.seed.absent", "true"));
        JsonObject pin = ReflectionTestUtils.invokeMethod(configuration, "pinRequestedConfigurationCloneTemplate", blueprint, true);
        Assert.assertEquals("EXPLICIT_SYSTEM", pin.get("selectionMode").getAsString());
        Mockito.verify(templates, Mockito.never()).findSystemVMReadyTemplate(Mockito.anyLong(), Mockito.any(), Mockito.anyString());
        Mockito.verify(service).preflightSharedFS(Mockito.any());
    }
}
