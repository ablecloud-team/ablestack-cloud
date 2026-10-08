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
import java.util.*;
import com.google.gson.JsonObject;
import com.cloud.agent.api.*;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.junit.*;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
public class StoragePinnedRuntimeReplayTest {
    private static class Manager extends StorageServiceRuntimeUpgradeManagerImpl {
        List<String> events=new ArrayList<>();String phase="RECEIVING";
        @Override protected String requireTemplateRuntimeHelper(StorageServiceInstanceVO instance){return "helper-sha";}
        @Override protected Set<String> requiredRuntimeFeatures(StorageServiceInstanceVO instance){return Set.of();}
        @Override protected byte[] download(String location,int limit){events.add("DOWNLOAD");return new byte[]{1};}
        @Override protected byte[] trustedKey(String key){return new byte[]{1};}
        @Override protected void ensureBootstrap(StorageServiceInstanceVO instance,StorageServiceRuntimeBundleVO bundle,String transaction){events.add("BOOTSTRAP");}
        @Override protected void transfer(StorageServiceInstanceVO instance,String transaction,StorageServiceRuntimeFileType type,String key,byte[] bytes,int from,int to,StorageServiceRuntimeUpgradeVO upgrade){events.add(type.name());}
        @Override protected JsonObject invoke(StorageServiceInstanceVO instance,StorageServiceRuntimeOperation operation,String transaction,JsonObject request){
            events.add(operation.name());JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("phase",phase);
            if (operation==StorageServiceRuntimeOperation.READBACK) {for(String key:new String[]{"signedRuntimeVerified","installedFilesVerified","entrypointsVerified"}) result.addProperty(key,true);result.addProperty("currentVersion","source-code");result.addProperty("archiveSha256","archive-sha");result.addProperty("manifestSha256","manifest-sha");result.addProperty("updaterSha256","helper-sha");}
            return result;
        }
    }
    private Manager manager;private String operationUuid;private StorageServiceTemplateUpgradeVO root;private StorageServiceTemplateUpgradeDao roots;private StorageServiceRuntimeBundleVO bundle;
    @Before public void setup(){
        manager=new Manager();StorageServiceInstanceDao instances=Mockito.mock(StorageServiceInstanceDao.class);StorageServiceOperationDao operations=Mockito.mock(StorageServiceOperationDao.class);roots=Mockito.mock(StorageServiceTemplateUpgradeDao.class);StorageServiceRuntimeBundleDao bundles=Mockito.mock(StorageServiceRuntimeBundleDao.class);
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instance.getCurrentRuntimeBundleId()).thenReturn(5L);Mockito.when(instance.getRuntimeVerifiedAt()).thenReturn(new Date());Mockito.when(instances.findById(6L)).thenReturn(instance);
        StorageServiceOperationVO operation=Mockito.mock(StorageServiceOperationVO.class);operationUuid=UUID.randomUUID().toString();Mockito.when(operation.getId()).thenReturn(3L);Mockito.when(operation.getInstanceId()).thenReturn(6L);Mockito.when(operation.getState()).thenReturn("RUNNING");Mockito.when(operation.getAction()).thenReturn("ROOT_TEMPLATE_UPGRADE");Mockito.when(operations.findByUuid(operationUuid)).thenReturn(operation);
        root=new StorageServiceTemplateUpgradeVO();root.setOperationId(3L);Mockito.when(roots.findActive(6L)).thenReturn(root);
        bundle=Mockito.mock(StorageServiceRuntimeBundleVO.class);Mockito.when(bundle.getUuid()).thenReturn("bundle-uuid");Mockito.when(bundle.getVersion()).thenReturn("source-code");Mockito.when(bundle.getSha256()).thenReturn("archive-sha");Mockito.when(bundle.getManifestSha256()).thenReturn("manifest-sha");Mockito.when(bundle.getSigningKeyId()).thenReturn("trusted-key");Mockito.when(bundle.getRuntimeAbiVersion()).thenReturn("1");Mockito.when(bundle.getDesiredStateSchemaVersion()).thenReturn("1");Mockito.when(bundle.getState()).thenReturn(StorageServiceRuntimeBundleVO.State.AVAILABLE);Mockito.when(bundle.getServiceImpact()).thenReturn(StorageServiceRuntimeBundleVO.ServiceImpact.NONE);Mockito.when(bundles.findById(5L)).thenReturn(bundle);Mockito.when(bundles.findByUuid("bundle-uuid")).thenReturn(bundle);
        ReflectionTestUtils.setField(manager,"instanceDao",instances);ReflectionTestUtils.setField(manager,"rootWriterDao",operations);ReflectionTestUtils.setField(manager,"rootUpgradeDao",roots);ReflectionTestUtils.setField(manager,"bundleDao",bundles);
    }
    private MockedConstruction<StorageServiceRuntimeBundleVerifier> verification(){return Mockito.mockConstruction(StorageServiceRuntimeBundleVerifier.class,(mock,context)->{
        JsonObject verified=new JsonObject();verified.add("manifest",new JsonObject());Mockito.when(mock.verify(Mockito.any(),Mockito.any(),Mockito.any(),Mockito.any(),Mockito.any())).thenAnswer(call->{manager.events.add("SIGNATURE_VERIFY");return verified;});
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
}
