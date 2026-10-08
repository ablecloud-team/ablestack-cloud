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

import java.util.Set;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class StorageRuntimeFixtureFeatureGateTest {
    private static final String CLI = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final Set<String> ORIGINAL = Set.of("SMB_ACTIVE_DIRECTORY", "SMB_AD_IDENTITY", "POSIX_AD_PRINCIPALS", "NFS_NUMERIC_IDENTITY");
    private static final Set<String> SCOPED = Set.of("SMB_AD_IDENTITY_HANDLER", "NFS_NUMERIC_IDENTITY");
    private static class Manager extends StorageServiceRuntimeUpgradeManagerImpl {
        StorageService service = Mockito.mock(StorageService.class);
        JsonObject proof;
        int adProbes, proofReads, packageChecks;
        boolean foreignPin, wrongCli;
        @Override protected Set<String> requiredRuntimeFeatures(StorageServiceInstanceVO instance) { return ORIGINAL; }
        @Override protected StorageService runtimeDependencyService() { return service; }
        @Override protected void requireNativeAdFeatures(StorageServiceInstanceVO instance, Set<String> required) {
            adProbes++;throw new CloudRuntimeException("Production AD capability remains unsupported");
        }
        @Override protected void requireRuntimePackageFeatures(StorageServiceInstanceVO instance) { packageChecks++; }
        @Override public JsonObject freshSignedRuntimeValidationProof(long instanceId, String expectedCli) {
            proofReads++;JsonObject result = proof.deepCopy();
            if (foreignPin) result.getAsJsonObject("runtimePin").addProperty("manifestSha256", "foreign");
            result.addProperty("actualCliSha256", wrongCli ? "wrong" : CLI);return result;
        }
    }
    private Manager manager;
    private StorageServiceInstanceVO instance;
    private StorageServiceRuntimeBundleVO candidate;

    @Before public void setup() {
        manager = new Manager();instance = Mockito.mock(StorageServiceInstanceVO.class);candidate = Mockito.mock(StorageServiceRuntimeBundleVO.class);
        Mockito.when(instance.getId()).thenReturn(17L);Mockito.when(instance.getCurrentRuntimeBundleId()).thenReturn(42L);
        Mockito.when(candidate.getId()).thenReturn(42L);Mockito.when(candidate.getUuid()).thenReturn("approved-bundle");
        Mockito.when(candidate.getVersion()).thenReturn("approved-version");Mockito.when(candidate.getSha256()).thenReturn("archive");
        Mockito.when(candidate.getManifestSha256()).thenReturn("manifest");Mockito.when(candidate.getSigningKeyId()).thenReturn("approved-key");
        Mockito.when(candidate.getRuntimeAbiVersion()).thenReturn("1");Mockito.when(candidate.getDesiredStateSchemaVersion()).thenReturn("1");
        manager.proof = new JsonObject();manager.proof.add("runtimePin", manager.runtimePin(candidate));
        Mockito.when(manager.service.scopedValidatedRuntimeFeatures(17L, ORIGINAL)).thenReturn(SCOPED);
    }
    private JsonObject declared(Set<String> features) {
        JsonObject value = new JsonObject();JsonArray array = new JsonArray();features.forEach(array::add);value.add("supportedFeatures", array);
        JsonArray files = new JsonArray();JsonObject file = new JsonObject();file.addProperty("path", "ablestack-storagectl");file.addProperty("sha256", CLI);files.add(file);value.add("files", files);
        return value;
    }
    @Test public void approvedNormalSamePinCanRequireHandlerWithoutClaimingProductionAd() {
        manager.requireSignedRuntimeFeatures(instance, declared(SCOPED), candidate);
        Assert.assertEquals(0, manager.adProbes);Assert.assertEquals(1, manager.proofReads);Assert.assertEquals(1, manager.packageChecks);
    }
    @Test public void rootReplayNeverUsesNormalFixtureException() {
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, declared(SCOPED)));
        Mockito.verifyNoInteractions(manager.service);Assert.assertEquals(1, manager.adProbes);Assert.assertEquals(0, manager.proofReads);
    }
    @Test public void foreignCandidateUsesProductionRequirementsWithoutFixtureLookup() {
        Mockito.when(candidate.getId()).thenReturn(99L);
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, declared(SCOPED), candidate));
        Mockito.verifyNoInteractions(manager.service);Assert.assertEquals(1, manager.adProbes);Assert.assertEquals(0, manager.proofReads);
    }
    @Test public void absentProtectedFixtureCannotWaiveProductionAd() {
        Mockito.when(manager.service.scopedValidatedRuntimeFeatures(17L, ORIGINAL)).thenReturn(ORIGINAL);
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, declared(SCOPED), candidate));
        Assert.assertEquals(1, manager.adProbes);Assert.assertEquals(0, manager.proofReads);
    }
    @Test public void candidateMissingSignedHandlerStillFailsFeatureLoss() {
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, declared(Set.of("NFS_NUMERIC_IDENTITY")), candidate));
        Assert.assertEquals(0, manager.adProbes);Assert.assertEquals(0, manager.packageChecks);
    }
    @Test public void currentApprovedPinChangeCannotAuthorizeSameDatabaseId() {
        manager.foreignPin = true;
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, declared(SCOPED), candidate));
        Assert.assertEquals(0, manager.packageChecks);
    }
    @Test public void currentCliMismatchCannotBePromotedByHandlerDeclaration() {
        manager.wrongCli = true;
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, declared(SCOPED), candidate));
        Assert.assertEquals(0, manager.packageChecks);
    }
    @Test public void fixtureCannotDiscardNumericOrOtherRequiredFeatures() {
        Mockito.when(manager.service.scopedValidatedRuntimeFeatures(17L, ORIGINAL)).thenReturn(Set.of("SMB_AD_IDENTITY_HANDLER"));
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, declared(SCOPED), candidate));
        Assert.assertEquals(0, manager.proofReads);Assert.assertEquals(0, manager.packageChecks);
    }
}
