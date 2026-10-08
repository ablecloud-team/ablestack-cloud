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
import java.util.List;
import javax.inject.Provider;

import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class StorageRuntimeValidationProofTest {
    private static final String CLI = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String ARCHIVE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String MANIFEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String UPDATER = "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a";
    private static class Manager extends StorageServiceRuntimeUpgradeManagerImpl {
        List<StorageServiceRuntimeOperation> operations = new ArrayList<>();
        boolean badFileHash, badUpdater, rootChanged, hostChanged, stringFileBoolean;
        int roots, hosts;
        @Override protected byte[] resource(String name) { return new byte[] {1}; }
        @Override protected byte[] trustedKey(String name) { return new byte[] {1}; }
        @Override protected byte[] download(String name, int size) { return new byte[] {1}; }
        @Override protected JsonObject sourceRootBinding(StorageServiceInstanceVO instance) {
            JsonObject value = new JsonObject();value.addProperty("rootVolumeUuid", rootChanged && ++roots > 1 ? "foreign" : "root");return value;
        }
        @Override protected JsonObject runtimeValidationHostBinding(StorageServiceInstanceVO instance) {
            JsonObject value = new JsonObject();value.addProperty("hostId", hostChanged && ++hosts > 1 ? 99L : 9L);return value;
        }
        @Override protected JsonObject freshConsumerObservation(StorageServiceInstanceVO instance) {
            JsonObject value = new JsonObject();value.addProperty("updaterVerified", true);value.addProperty("platformVersionKnown", false);return value;
        }
        @Override protected JsonObject invoke(StorageServiceInstanceVO instance, StorageServiceRuntimeOperation operation, String transaction, JsonObject request) {
            operations.add(operation);
            Assert.assertEquals(StorageServiceRuntimeOperation.READBACK, operation);
            Assert.assertEquals("approved-transaction", transaction);
            JsonObject value = new JsonObject();value.addProperty("success", true);value.addProperty("signedRuntimeVerified", true);
            value.addProperty("installedFilesVerified", !badFileHash);if (stringFileBoolean) value.addProperty("installedFilesVerified", "true");value.addProperty("entrypointsVerified", true);
            value.addProperty("currentVersion", "profile-runtime");value.addProperty("archiveSha256", ARCHIVE);value.addProperty("manifestSha256", MANIFEST);
            value.addProperty("updaterSha256", badUpdater ? CLI : UPDATER);value.addProperty("password", "must-not-be-returned");return value;
        }
    }
    private Manager manager;
    private StorageServiceInstanceVO instance;
    private StorageServiceInstanceDao instances;
    private StorageServiceRuntimeUpgradeDao upgrades;
    private StorageServiceRuntimeUpgradeVO completed;
    private JsonObject manifest;

    @Before public void setup() {
        manager = new Manager();instance = Mockito.mock(StorageServiceInstanceVO.class);instances = Mockito.mock(StorageServiceInstanceDao.class);
        Mockito.when(instance.getId()).thenReturn(17L);Mockito.when(instance.getVmId()).thenReturn(170L);Mockito.when(instance.getUuid()).thenReturn("instance");
        Mockito.when(instance.getCurrentRuntimeBundleId()).thenReturn(42L);Mockito.when(instance.getRuntimeVerifiedAt()).thenReturn(new Date(5000));
        Mockito.when(instances.findById(17L)).thenReturn(instance);ReflectionTestUtils.setField(manager, "instanceDao", instances);
        StorageServiceRuntimeBundleDao bundles = Mockito.mock(StorageServiceRuntimeBundleDao.class);
        StorageServiceRuntimeBundleVO bundle = Mockito.mock(StorageServiceRuntimeBundleVO.class);
        Mockito.when(bundle.getId()).thenReturn(42L);Mockito.when(bundle.getUuid()).thenReturn("bundle");Mockito.when(bundle.getVersion()).thenReturn("profile-runtime");
        Mockito.when(bundle.getState()).thenReturn(StorageServiceRuntimeBundleVO.State.AVAILABLE);Mockito.when(bundle.getSha256()).thenReturn(ARCHIVE);
        Mockito.when(bundle.getManifestSha256()).thenReturn(MANIFEST);Mockito.when(bundle.getSigningKeyId()).thenReturn("key");
        Mockito.when(bundle.getRuntimeAbiVersion()).thenReturn("1");Mockito.when(bundle.getDesiredStateSchemaVersion()).thenReturn("1");
        Mockito.when(bundle.getServiceImpact()).thenReturn(StorageServiceRuntimeBundleVO.ServiceImpact.NONE);
        Mockito.when(bundles.findById(42L)).thenReturn(bundle);Mockito.when(bundles.findByUuid("bundle")).thenReturn(bundle);
        ReflectionTestUtils.setField(manager, "bundleDao", bundles);upgrades = Mockito.mock(StorageServiceRuntimeUpgradeDao.class);
        completed = Mockito.mock(StorageServiceRuntimeUpgradeVO.class);Mockito.when(completed.getInstanceId()).thenReturn(17L);Mockito.when(completed.getBundleId()).thenReturn(42L);
        Mockito.when(completed.getState()).thenReturn(StorageServiceRuntimeUpgradeVO.State.COMPLETE);Mockito.when(completed.getCompleted()).thenReturn(new Date(6000));
        Mockito.when(completed.getTransactionId()).thenReturn("approved-transaction");Mockito.when(upgrades.listByInstanceId(17L)).thenReturn(List.of(completed));
        ReflectionTestUtils.setField(manager, "upgradeDao", upgrades);manifest = new JsonObject();JsonArray files = new JsonArray();JsonObject cli = new JsonObject();
        cli.addProperty("path", "ablestack-storagectl");cli.addProperty("sha256", CLI);files.add(cli);manifest.add("files", files);
        ReflectionTestUtils.setField(manager, "operationControlService", (Provider<StorageService>) () -> { throw new AssertionError("Readonly proof must not acquire or verify operation control"); });
    }
    private MockedConstruction<StorageServiceRuntimeBundleVerifier> verifier() {
        return Mockito.mockConstruction(StorageServiceRuntimeBundleVerifier.class, (mock, context) -> {
            JsonObject value = new JsonObject();value.add("manifest", manifest);
            Mockito.when(mock.verify(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(value);
        });
    }
    @Test public void proofUsesExistingApprovedTransactionAndNeverActivatesOrCreatesStaging() {
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) {
            JsonObject result = manager.freshSignedRuntimeValidationProof(17L, CLI);
            Assert.assertEquals(CLI, result.get("actualCliSha256").getAsString());Assert.assertTrue(result.get("readOnly").getAsBoolean());
            Assert.assertTrue(result.get("nativeFileHashesVerified").getAsBoolean());Assert.assertFalse(result.toString().contains("must-not-be-returned"));
            Assert.assertFalse(result.getAsJsonObject("consumerObservation").get("platformVersionKnown").getAsBoolean());
            Assert.assertEquals(List.of(StorageServiceRuntimeOperation.READBACK), manager.operations);
        }
    }
    @Test public void wrongProfileCliHashBlocksBeforeNativeReadback() {
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) {
            Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, ARCHIVE));Assert.assertTrue(manager.operations.isEmpty());
        }
    }
    @Test public void noCompletedTransactionDoesNotBootstrapOrFabricateReadbackState() {
        Mockito.when(upgrades.listByInstanceId(17L)).thenReturn(List.of());
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) {
            Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI));Assert.assertTrue(manager.operations.isEmpty());
        }
    }
    @Test public void anotherInstanceReceiptCannotBeAdopted() {
        Mockito.when(completed.getInstanceId()).thenReturn(99L);
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) { Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI)); }
    }
    @Test public void nativeInstalledByteMismatchCannotBeMaskedBySignedManifest() {
        manager.badFileHash = true;
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) { Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI)); }
    }
    @Test public void unpinnedReadbackHelperCannotAttestInstalledByteHashes() {
        manager.badUpdater = true;
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) { Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI)); }
    }
    @Test public void rootSwapDuringReadbackInvalidatesProof() {
        manager.rootChanged = true;
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) { Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI)); }
    }
    @Test public void hostMoveDuringReadbackInvalidatesProof() {
        manager.hostChanged = true;
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) { Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI)); }
    }
    @Test public void currentCatalogBindingChangeInvalidatesProof() {
        StorageServiceInstanceVO other = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(other.getCurrentRuntimeBundleId()).thenReturn(99L);
        Mockito.when(instances.findById(17L)).thenReturn(instance, other);
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) { Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI)); }
    }
    @Test public void duplicateCliManifestEntryCannotProduceAmbiguousProof() {
        manifest.getAsJsonArray("files").add(manifest.getAsJsonArray("files").get(0).deepCopy());
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) { Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI)); }
    }
    @Test public void oldRuntimeManagerInterfaceFailsClosed() {
        StorageServiceRuntimeUpgradeManager legacy = Mockito.mock(StorageServiceRuntimeUpgradeManager.class, Mockito.CALLS_REAL_METHODS);
        Assert.assertThrows(CloudRuntimeException.class, () -> legacy.freshSignedRuntimeValidationProof(17L, CLI));
    }
    @Test public void malformedExpectedShaFailsBeforeDaoOrGuestAccess() {
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, "not-a-sha"));Mockito.verifyNoInteractions(instances);
    }
    @Test public void realHostBindingRejectsVmFromAnotherOwner() {
        StorageServiceRuntimeUpgradeManagerImpl real = new StorageServiceRuntimeUpgradeManagerImpl();VMInstanceDao vms = Mockito.mock(VMInstanceDao.class);HostDao hosts = Mockito.mock(HostDao.class);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(instance.getAccountId()).thenReturn(2L);Mockito.when(instance.getDataCenterId()).thenReturn(1L);
        Mockito.when(vm.getAccountId()).thenReturn(3L);Mockito.when(vm.getHostId()).thenReturn(9L);Mockito.when(vms.findById(170L)).thenReturn(vm);Mockito.when(hosts.findById(9L)).thenReturn(host);
        ReflectionTestUtils.setField(real, "vmInstanceDao", vms);ReflectionTestUtils.setField(real, "runtimeHostDao", hosts);
        Assert.assertThrows(CloudRuntimeException.class, () -> real.runtimeValidationHostBinding(instance));
    }
    @Test public void stringTrueIsNotNativeFileHashAttestation() {
        manager.stringFileBoolean = true;
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) { Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI)); }
    }

    @Test public void proofReturnsOnlyExactSignedFeaturesAndDetachedArray() {
        JsonArray declared = new JsonArray();declared.add("NESTED_FILE_SHARE");declared.add("SMB_AD_IDENTITY_HANDLER");
        manifest.add("supportedFeatures", declared);
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) {
            JsonObject result = manager.freshSignedRuntimeValidationProof(17L, CLI);
            Assert.assertEquals(declared, result.getAsJsonArray("signedSupportedFeatures"));
            result.getAsJsonArray("signedSupportedFeatures").remove(0);
            Assert.assertEquals(2, manifest.getAsJsonArray("supportedFeatures").size());
            Assert.assertEquals(List.of(StorageServiceRuntimeOperation.READBACK), manager.operations);
        }
    }
    @Test public void legacyManifestDoesNotInventHandlerFeatures() {
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) {
            JsonObject result = manager.freshSignedRuntimeValidationProof(17L, CLI);
            Assert.assertEquals(0, result.getAsJsonArray("signedSupportedFeatures").size());
        }
    }
    @Test public void malformedSignedFeatureShapeBlocksBeforeNativeReadback() {
        manifest.addProperty("supportedFeatures", "SMB_AD_IDENTITY_HANDLER");
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) {
            Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI));
            Assert.assertTrue(manager.operations.isEmpty());
        }
    }
    @Test public void duplicateSignedHandlerFeatureCannotBecomeProof() {
        JsonArray declared = new JsonArray();declared.add("SMB_AD_IDENTITY_HANDLER");declared.add("SMB_AD_IDENTITY_HANDLER");
        manifest.add("supportedFeatures", declared);
        try (MockedConstruction<StorageServiceRuntimeBundleVerifier> ignored = verifier()) {
            Assert.assertThrows(CloudRuntimeException.class, () -> manager.freshSignedRuntimeValidationProof(17L, CLI));
            Assert.assertTrue(manager.operations.isEmpty());
        }
    }
}
