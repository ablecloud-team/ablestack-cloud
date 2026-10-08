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
import java.util.Set;
import java.util.UUID;
import com.google.gson.JsonObject;
import com.cloud.agent.api.StorageServiceRuntimeFileType;
import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
public class StoragePinnedRuntimeReplayTest {
    private static class Manager extends StorageServiceRuntimeUpgradeManagerImpl {
        List<String> events=new ArrayList<>();String phase="RECEIVING";boolean legacyManifest=false;int consumerReads;boolean formatterBlocked;String changeTemplateAfterFirstObservation;JsonObject consumer=new JsonObject(),binding=new JsonObject();
        Manager(){consumer.addProperty("managerVersion","4.23.0.0");consumer.addProperty("agentVersion","4.23.0.0");consumer.addProperty("templatePlatformVersion","4.23.0.0");consumer.addProperty("platformVersionKnown",true);consumer.addProperty("platformObservationRecorded",true);consumer.addProperty("updaterVerified",true);consumer.addProperty("observedAtMillis",1L);
            binding.addProperty("instanceUuid","instance-6");binding.addProperty("vmId",60L);binding.addProperty("rootVolumeId",70L);binding.addProperty("rootVolumeUuid","root-70");binding.addProperty("templateId",80L);binding.addProperty("accountId",2L);binding.addProperty("zoneId",1L);}
        @Override protected JsonObject freshConsumerObservation(StorageServiceInstanceVO instance){consumerReads++;JsonObject observed=consumer.deepCopy();if(changeTemplateAfterFirstObservation!=null && consumerReads>1)observed.addProperty("templatePlatformVersion",changeTemplateAfterFirstObservation);return observed;}
        @Override protected JsonObject sourceRootBinding(StorageServiceInstanceVO instance){return binding.deepCopy();}
        @Override protected byte[] resource(String path){return new byte[]{1};}
        @Override protected void requireRuntimeActivationSafety(StorageServiceInstanceVO instance){events.add("FORMATTER_SAFETY");if(formatterBlocked)throw new CloudRuntimeException("Incomplete formatter journal");}
        @Override protected String requireTemplateRuntimeHelper(StorageServiceInstanceVO instance){return "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a";}
        @Override protected Set<String> requiredRuntimeFeatures(StorageServiceInstanceVO instance){return Set.of();}
        @Override protected byte[] download(String location,int limit){events.add("DOWNLOAD");return new byte[]{1};}
        @Override protected byte[] trustedKey(String key){return new byte[]{1};}
        @Override protected void ensureBootstrap(StorageServiceInstanceVO instance,StorageServiceRuntimeBundleVO bundle,String transaction){events.add("BOOTSTRAP");}
        @Override protected void transfer(StorageServiceInstanceVO instance,String transaction,StorageServiceRuntimeFileType type,String key,byte[] bytes,int from,int to,StorageServiceRuntimeUpgradeVO upgrade){events.add(type.name());}
        @Override protected JsonObject invoke(StorageServiceInstanceVO instance,StorageServiceRuntimeOperation operation,String transaction,JsonObject request){
            events.add(operation.name());JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("phase",phase);
            if (operation==StorageServiceRuntimeOperation.READBACK) {for(String key:new String[]{"signedRuntimeVerified","installedFilesVerified","entrypointsVerified"}) result.addProperty(key,true);result.addProperty("currentVersion","source-code");result.addProperty("archiveSha256","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");result.addProperty("manifestSha256","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");result.addProperty("updaterSha256","4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a");}
            return result;
        }
    }
    private Manager manager;private String operationUuid;private StorageServiceTemplateUpgradeVO root;private StorageServiceTemplateUpgradeDao roots;private StorageServiceRuntimeBundleVO bundle;
    @Before public void setup(){
        manager=new Manager();
        StorageService controls=Mockito.mock(StorageService.class);ReflectionTestUtils.setField(manager,"operationControlService",(javax.inject.Provider<StorageService>)()->controls);
StorageServiceInstanceDao instances=Mockito.mock(StorageServiceInstanceDao.class);StorageServiceOperationDao operations=Mockito.mock(StorageServiceOperationDao.class);roots=Mockito.mock(StorageServiceTemplateUpgradeDao.class);StorageServiceRuntimeBundleDao bundles=Mockito.mock(StorageServiceRuntimeBundleDao.class);
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instance.getVmId()).thenReturn(60L);Mockito.when(instance.getPreviousRuntimeBundleId()).thenReturn(5L);Mockito.when(instance.getCurrentRuntimeBundleId()).thenReturn(5L);Mockito.when(instance.getRuntimeVerifiedAt()).thenReturn(new Date());Mockito.when(instances.findById(6L)).thenReturn(instance);
        StorageServiceOperationVO operation=Mockito.mock(StorageServiceOperationVO.class);operationUuid=UUID.randomUUID().toString();Mockito.when(operation.getId()).thenReturn(3L);Mockito.when(operation.getInstanceId()).thenReturn(6L);Mockito.when(operation.getState()).thenReturn("RUNNING");Mockito.when(operation.getAction()).thenReturn("ROOT_TEMPLATE_UPGRADE");Mockito.when(operations.findByUuid(operationUuid)).thenReturn(operation);
        root=new StorageServiceTemplateUpgradeVO();root.setOperationId(3L);root.setPreviousRootVolumeId(70L);root.setSourceTemplateId(80L);Mockito.when(roots.findActive(6L)).thenReturn(root);
        bundle=Mockito.mock(StorageServiceRuntimeBundleVO.class);Mockito.when(bundle.getId()).thenReturn(5L);Mockito.when(bundle.getUuid()).thenReturn("bundle-uuid");Mockito.when(bundle.getVersion()).thenReturn("source-code");Mockito.when(bundle.getSha256()).thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");Mockito.when(bundle.getManifestSha256()).thenReturn("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");Mockito.when(bundle.getSigningKeyId()).thenReturn("trusted-key");Mockito.when(bundle.getRuntimeAbiVersion()).thenReturn("1");Mockito.when(bundle.getDesiredStateSchemaVersion()).thenReturn("1");Mockito.when(bundle.getState()).thenReturn(StorageServiceRuntimeBundleVO.State.AVAILABLE);Mockito.when(bundle.getServiceImpact()).thenReturn(StorageServiceRuntimeBundleVO.ServiceImpact.NONE);Mockito.when(bundles.findById(5L)).thenReturn(bundle);Mockito.when(bundles.findByUuid("bundle-uuid")).thenReturn(bundle);
        Mockito.when(instances.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"instanceDao",instances);ReflectionTestUtils.setField(manager,"rootWriterDao",operations);ReflectionTestUtils.setField(manager,"rootUpgradeDao",roots);ReflectionTestUtils.setField(manager,"bundleDao",bundles);
    }
    private MockedConstruction<StorageServiceRuntimeBundleVerifier> verification(){return Mockito.mockConstruction(StorageServiceRuntimeBundleVerifier.class,(mock,context)->{
        JsonObject verified=new JsonObject();JsonObject manifest=new JsonObject();manifest.addProperty("bundleVersion","source-code");
        if(!manager.legacyManifest){JsonObject ranges=new JsonObject();ranges.addProperty("schemaVersion",1);for(String consumer:new String[]{"manager","agent","template"}){JsonObject range=new JsonObject();range.addProperty("minimumVersion","4.23.0.0");range.addProperty("maximumVersionExclusive","4.24.0.0");ranges.add(consumer,range);}manifest.add("compatibility",ranges);}verified.add("manifest",manifest);Mockito.when(mock.verify(Mockito.any(),Mockito.any(),Mockito.any(),Mockito.any(),Mockito.any())).thenAnswer(call->{manager.events.add("SIGNATURE_VERIFY");return verified;});
    });}
    @Test public void sourceIsOnlyReadBackAndNeverActivatedWhileItsExactSignedBundleIsPinned(){
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()) {
            JsonObject checkpoint=manager.checkpointTemplateRuntime(6,operationUuid);Assert.assertEquals("source-code",checkpoint.getAsJsonObject("pin").get("bundleVersion").getAsString());
            Assert.assertFalse(manager.events.contains("ACTIVATE"));Assert.assertTrue(manager.events.indexOf("SIGNATURE_VERIFY")<manager.events.indexOf("BOOTSTRAP"));Assert.assertEquals("READBACK",manager.events.get(manager.events.size()-1));
        }
    }
    @Test public void targetActivationRequiresServerSignatureAndNativeVerificationBeforeInstalledFileReadback(){
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()) {
            manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"target");
            Assert.assertTrue(manager.events.indexOf("SIGNATURE_VERIFY")<manager.events.indexOf("ACTIVATE"));Assert.assertTrue(manager.events.indexOf("VERIFY")<manager.events.indexOf("ACTIVATE"));Assert.assertTrue(manager.events.indexOf("PREFLIGHT")<manager.events.indexOf("ACTIVATE"));Assert.assertTrue(manager.events.indexOf("ACTIVATE")<manager.events.indexOf("READBACK"));
        }
    }
    @Test public void activationJournalResumeUsesTheSamePinnedTransactionAndReattestsCurrentFiles(){
        manager.phase="ACTIVATING";try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()) {
            manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"target");Assert.assertTrue(manager.events.contains("ACTIVATE"));Assert.assertFalse(manager.events.contains("BUNDLE"));Assert.assertEquals("READBACK",manager.events.get(manager.events.size()-1));
        }
    }
    @Test public void changedCatalogManifestOrAnotherRootWriterCannotDownloadOrActivate(){
        JsonObject pin=manager.runtimePin(bundle);pin.addProperty("manifestSha256","other-manifest");Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,pin,operationUuid,"target"));Assert.assertTrue(manager.events.isEmpty());
        root.setOperationId(4L);Assert.assertThrows(CloudRuntimeException.class,()->manager.checkpointTemplateRuntime(6,operationUuid));Assert.assertTrue(manager.events.isEmpty());
    }
    private void retainSource(JsonObject checkpoint){JsonObject snapshot=new JsonObject();snapshot.add("sourceSignedRuntime",checkpoint);root.setSnapshotJson(snapshot.toString());}
    @Test public void checkpointProducesActualRootConsumerAndApprovedInstalledLkgProofEvenForUnknownSource(){
        manager.consumer.add("templatePlatformVersion",com.google.gson.JsonNull.INSTANCE);manager.consumer.addProperty("platformVersionKnown",false);
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            JsonObject checkpoint=manager.checkpointTemplateRuntime(6,operationUuid);Assert.assertEquals(manager.binding,checkpoint.get("sourceRootBinding"));Assert.assertEquals(manager.consumer,checkpoint.get("consumerObservation"));
            Assert.assertTrue(checkpoint.getAsJsonObject("approvedInstalledLkg").get("verifiedAtMillis").getAsLong()>0);Assert.assertFalse(manager.events.contains("ACTIVATE"));
        }
    }
    @Test public void newTargetUnknownAndLegacyManifestAreBlockedBeforeBootstrapTransferOrActivation(){
        manager.consumer.add("templatePlatformVersion",com.google.gson.JsonNull.INSTANCE);manager.consumer.addProperty("platformVersionKnown",false);
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"target"));Assert.assertFalse(manager.events.contains("BOOTSTRAP"));Assert.assertFalse(manager.events.contains("BUNDLE"));Assert.assertFalse(manager.events.contains("ACTIVATE"));
            manager.consumer.addProperty("templatePlatformVersion","4.23.0.0");manager.legacyManifest=true;manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"target"));Assert.assertFalse(manager.events.contains("BOOTSTRAP"));
        }
    }
    @Test public void originalSourceUnknownWithDeclaredRangesCanReturnOnlyThroughProtectedPreviousRootEvidence(){
        manager.consumer.add("templatePlatformVersion",com.google.gson.JsonNull.INSTANCE);manager.consumer.addProperty("platformVersionKnown",false);
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            retainSource(manager.checkpointTemplateRuntime(6,operationUuid));manager.events.clear();JsonObject restored=manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"previous");
            Assert.assertTrue(manager.events.contains("ACTIVATE"));Assert.assertTrue(restored.getAsJsonObject("consumerCompatibility").get("legacyExceptionApplied").getAsBoolean());Assert.assertFalse(restored.getAsJsonObject("consumerCompatibility").get("rangeCompatibilityVerified").getAsBoolean());
        }
    }
    @Test public void legacyPreviousMustStillHaveExactSignedPinInstalledLkgAndOriginalRoot(){
        manager.legacyManifest=true;
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            JsonObject checkpoint=manager.checkpointTemplateRuntime(6,operationUuid);retainSource(checkpoint);manager.events.clear();Assert.assertTrue(manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"previous").getAsJsonObject("consumerCompatibility").get("legacyExceptionApplied").getAsBoolean());
            checkpoint.getAsJsonObject("pin").addProperty("signingKeyId","foreign-key");retainSource(checkpoint);manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"previous"));Assert.assertFalse(manager.events.contains("ACTIVATE"));
        }
    }
    @Test public void foreignPreviousRootOrUnverifiedInstalledReceiptCannotUseUnknownException(){
        manager.consumer.add("templatePlatformVersion",com.google.gson.JsonNull.INSTANCE);manager.consumer.addProperty("platformVersionKnown",false);
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            JsonObject checkpoint=manager.checkpointTemplateRuntime(6,operationUuid);checkpoint.getAsJsonObject("sourceRootBinding").addProperty("rootVolumeId",71L);retainSource(checkpoint);manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"previous"));Assert.assertFalse(manager.events.contains("ACTIVATE"));
            checkpoint=manager.checkpointTemplateRuntime(6,operationUuid);checkpoint.getAsJsonObject("verification").addProperty("installedFilesVerified",false);retainSource(checkpoint);manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"previous"));Assert.assertFalse(manager.events.contains("ACTIVATE"));
        }
    }
    @Test public void missingOriginalUnknownObservationOrKnownIncompatibleCurrentConsumerCannotBeExcepted(){
        manager.consumer.add("templatePlatformVersion",com.google.gson.JsonNull.INSTANCE);manager.consumer.addProperty("platformVersionKnown",false);
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            JsonObject checkpoint=manager.checkpointTemplateRuntime(6,operationUuid);checkpoint.getAsJsonObject("consumerObservation").remove("platformObservationRecorded");retainSource(checkpoint);manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"previous"));Assert.assertFalse(manager.events.contains("ACTIVATE"));
            retainSource(manager.checkpointTemplateRuntime(6,operationUuid));manager.consumer.addProperty("managerVersion","4.24.0.0");manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"previous"));Assert.assertFalse(manager.events.contains("ACTIVATE"));
        }
    }
    @Test public void previousSelectorKeepsOriginalSourceAndNeverPromotesCurrentTargetManualRollbackCheckpoint(){
        manager.consumer.add("templatePlatformVersion",com.google.gson.JsonNull.INSTANCE);manager.consumer.addProperty("platformVersionKnown",false);
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            JsonObject checkpoint=manager.checkpointTemplateRuntime(6,operationUuid);JsonObject snapshot=new JsonObject();snapshot.add("sourceSignedRuntime",checkpoint);JsonObject target=checkpoint.deepCopy();target.getAsJsonObject("sourceRootBinding").addProperty("rootVolumeId",999L);snapshot.add("manualRollbackRuntime",target);root.setSnapshotJson(snapshot.toString());manager.events.clear();manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"previous");Assert.assertTrue(manager.events.contains("ACTIVATE"));
        }
    }

    @Test public void consumerChangingAfterTransferCannotReachRootActivate(){
        manager.changeTemplateAfterFirstObservation="4.24.0.0";
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"target"));Assert.assertTrue(manager.events.contains("BUNDLE"));Assert.assertFalse(manager.events.contains("ACTIVATE"));
        }
    }
    private StorageServiceRuntimeUpgradeVO genericUpgrade(JsonObject sourceCheckpoint){
        StorageServiceRuntimeUpgradeVO upgrade=new StorageServiceRuntimeUpgradeVO(6L,5L,5L,"runtime-unit",1L);upgrade.setState(StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY);
        JsonObject preflight=new JsonObject();preflight.add("targetRuntimePin",manager.runtimePin(bundle));preflight.add("sourceSignedRuntime",sourceCheckpoint);upgrade.setPreflightJson(preflight.toString());
        StorageServiceRuntimeUpgradeDao upgrades=Mockito.mock(StorageServiceRuntimeUpgradeDao.class);Mockito.when(upgrades.findById(7L)).thenReturn(upgrade);Mockito.when(upgrades.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"upgradeDao",upgrades);
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"ok","{\"success\":true,\"status\":\"ok\"}"));ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);return upgrade;
    }
    private void genericActivate(){org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd cmd=Mockito.mock(org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd.class);Mockito.when(cmd.getUpgradeId()).thenReturn(7L);ReflectionTestUtils.invokeMethod(manager,"doUpgrade",cmd);}
    private void genericRollback(){org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceRuntimeUpgradeCmd cmd=Mockito.mock(org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceRuntimeUpgradeCmd.class);Mockito.when(cmd.getUpgradeId()).thenReturn(7L);ReflectionTestUtils.invokeMethod(manager,"doRollback",cmd);}
    @Test public void genericActivationReverifiesSignedManifestAndFreshVersionsBeforeActivate(){
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            StorageServiceRuntimeUpgradeVO upgrade=genericUpgrade(manager.checkpointTemplateRuntime(6,operationUuid));manager.events.clear();genericActivate();Assert.assertEquals(StorageServiceRuntimeUpgradeVO.State.COMPLETE,upgrade.getState());Assert.assertTrue(manager.events.indexOf("SIGNATURE_VERIFY")<manager.events.indexOf("ACTIVATE"));Assert.assertTrue(com.google.gson.JsonParser.parseString(upgrade.getVerificationJson()).getAsJsonObject().has("consumerCompatibility"));
        }
    }
    @Test public void changedPreflightPinOrFreshAgentVersionBlocksGenericActivation(){
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            StorageServiceRuntimeUpgradeVO upgrade=genericUpgrade(manager.checkpointTemplateRuntime(6,operationUuid));JsonObject preflight=com.google.gson.JsonParser.parseString(upgrade.getPreflightJson()).getAsJsonObject();preflight.getAsJsonObject("targetRuntimePin").addProperty("signingKeyId","foreign-key");upgrade.setPreflightJson(preflight.toString());manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->genericActivate());Assert.assertFalse(manager.events.contains("ACTIVATE"));
            upgrade=genericUpgrade(manager.checkpointTemplateRuntime(6,operationUuid));manager.consumer.addProperty("agentVersion","4.24.0.0");manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->genericActivate());Assert.assertFalse(manager.events.contains("ACTIVATE"));
        }
    }
    @Test public void genericPreviousUnknownRequiresOriginalProtectedBindingAndInstalledReadback(){
        manager.consumer.add("templatePlatformVersion",com.google.gson.JsonNull.INSTANCE);manager.consumer.addProperty("platformVersionKnown",false);
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            StorageServiceRuntimeUpgradeVO upgrade=genericUpgrade(manager.checkpointTemplateRuntime(6,operationUuid));upgrade.setState(StorageServiceRuntimeUpgradeVO.State.COMPLETE);manager.events.clear();genericRollback();Assert.assertEquals(StorageServiceRuntimeUpgradeVO.State.ROLLED_BACK,upgrade.getState());Assert.assertTrue(manager.events.indexOf("ROLLBACK")<manager.events.indexOf("READBACK"));Assert.assertTrue(com.google.gson.JsonParser.parseString(upgrade.getRollbackResultJson()).getAsJsonObject().has("installedPreviousReadback"));
        }
    }
    @Test public void genericRollbackCannotBorrowForeignRootSourceCheckpoint(){
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            JsonObject checkpoint=manager.checkpointTemplateRuntime(6,operationUuid);checkpoint.getAsJsonObject("sourceRootBinding").addProperty("rootVolumeId",999L);StorageServiceRuntimeUpgradeVO upgrade=genericUpgrade(checkpoint);upgrade.setState(StorageServiceRuntimeUpgradeVO.State.COMPLETE);manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->genericRollback());Assert.assertFalse(manager.events.contains("ROLLBACK"));
        }
    }

    @Test public void incompleteFormatterBlocksRootStagingAndGenericActivationWithoutMutation(){
        try(MockedConstruction<StorageServiceRuntimeBundleVerifier> verified=verification()){
            StorageServiceRuntimeUpgradeVO upgrade=genericUpgrade(manager.checkpointTemplateRuntime(6,operationUuid));manager.formatterBlocked=true;manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreTemplateRuntime(6,manager.runtimePin(bundle),operationUuid,"target"));Assert.assertFalse(manager.events.contains("BOOTSTRAP"));Assert.assertFalse(manager.events.contains("ACTIVATE"));manager.events.clear();Assert.assertThrows(CloudRuntimeException.class,()->genericActivate());Assert.assertFalse(manager.events.contains("ACTIVATE"));
        }
    }

}
