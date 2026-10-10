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

import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageRootRenderedBaselineTest {
    private static JsonObject empty(){JsonObject value=new JsonObject();for(String path:StorageRenderedDesiredState.PATHS)value.add(path,JsonNull.INSTANCE);return value;}
    private static class Manager extends StorageServiceManagerImpl {
        final List<String> events=new ArrayList<>();JsonObject rootScope;boolean existingNative,unexpectedPolicy,foreignBootstrap,foreignImport;int keys;
        @Override protected JsonObject rootPrimaryBinding(StorageServiceInstanceVO instance,com.google.gson.JsonElement source){JsonObject binding=new JsonObject();binding.addProperty("macAddress","02:01:00:00:00:01");binding.addProperty("primaryIp","10.10.13.250");binding.addProperty("prefixlen",16);binding.add("gateway",JsonNull.INSTANCE);return binding;}
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){events.add("native-status");JsonObject status=new JsonObject(),generation=new JsonObject();if(existingNative)generation.addProperty("operationUuid","foreign");status.add("generation",generation);status.add("pendingOperationUuid",JsonNull.INSTANCE);status.addProperty("configurationSha256","a".repeat(64));JsonObject desired=empty();if(unexpectedPolicy)desired.add("posix-directory-policies.json",new JsonObject());status.add("configurationDesiredState",desired);return status;}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String action,JsonObject request,int timeout){events.add(action);JsonObject reply=new JsonObject();reply.addProperty("success",true);
            if(action.endsWith("render-status")){reply.addProperty("schemaVersion",1);reply.addProperty("renderedGenerationSupported",true);reply.addProperty("fullFourProtocolActivationSupported",false);JsonArray features=new JsonArray();features.add("RENDERED_CONFIG_GENERATION_HANDLER");reply.add("supportedFeatures",features);reply.add("current",JsonNull.INSTANCE);}
            if(action.equals("operation root-network bootstrap")){Assert.assertEquals(rootScope.get("templateUpgradeUuid"),request.get("templateUpgradeUuid"));reply.add("scope",rootScope.deepCopy());if(foreignBootstrap)reply.getAsJsonObject("scope").addProperty("operationUuid","foreign");reply.addProperty("primaryBindingVerified",true);reply.addProperty("networkChanged",false);reply.addProperty("protocolDesiredStateChanged",false);reply.addProperty("posixDesiredStateChanged",false);reply.add("sharedfsNetwork",request.get("sharedfsNetwork"));}
            if(action.endsWith("render-import")){Assert.assertTrue(request.get("initialRootBaseline").getAsBoolean());Assert.assertEquals(empty(),request.get("configurationDesiredState"));Assert.assertEquals(0,request.getAsJsonArray("fileVolumeBindings").size());JsonObject current=new JsonObject();current.addProperty("configurationSha256","a".repeat(64));current.addProperty("manifestSha256","b".repeat(64));reply.add("current",current);JsonObject activation=new JsonObject();activation.add("initialRootScope",rootScope.deepCopy());if(foreignImport)activation.getAsJsonObject("initialRootScope").addProperty("templateUpgradeUuid","foreign");reply.add("activation",activation);}
            return reply;}
        @Override protected RenderedBatch createRenderedBatch(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,JsonObject before,JsonObject manifest,JsonObject scope){events.add("persist-encrypted-recovery-reference");keys++;return new RenderedBatch(operation,before,manifest,StorageIdentityCapsule.wrappingKey());}
    }
    private Manager manager(){Manager manager=new Manager();manager.rootScope=new JsonObject();manager.rootScope.addProperty("instanceUuid","instance");manager.rootScope.addProperty("templateUpgradeUuid","template-row");manager.rootScope.addProperty("operationUuid","operation");manager.rootScope.addProperty("revision",2);return manager;}
    private void prepare(Manager manager){StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("instance");StorageServiceOperationVO operation=new StorageServiceOperationVO();operation.setRevision(2);operation.setPreviousSnapshotJson("{}");manager.rootScope.addProperty("operationUuid",operation.getUuid());manager.prepareRootRenderedBaseline(instance,operation,manager.rootScope,empty());}
    @Test public void exactRootNetworkObservationAndEmptyImportPrecedeIdentityOrAnyProtocolAndRecoveryKeyPersistence(){Manager manager=manager();prepare(manager);Assert.assertTrue(manager.events.indexOf("operation root-network bootstrap")<manager.events.indexOf("operation generation render-import"));Assert.assertTrue(manager.events.indexOf("operation generation render-import")<manager.events.indexOf("persist-encrypted-recovery-reference"));Assert.assertEquals(1,manager.keys);Assert.assertFalse(manager.events.stream().anyMatch(e->e.contains("seed")||e.contains("apply")||e.contains("identity")));}
    @Test public void existingNativeGenerationOrPermissionDesiredStateRejectsBeforeBootstrapAndImport(){for(boolean existing:List.of(true,false)){Manager manager=manager();manager.existingNative=existing;manager.unexpectedPolicy=!existing;Assert.assertThrows(CloudRuntimeException.class,()->prepare(manager));Assert.assertFalse(manager.events.contains("operation root-network bootstrap"));Assert.assertFalse(manager.events.contains("operation generation render-import"));Assert.assertEquals(0,manager.keys);}}
    @Test public void foreignBootstrapScopeCannotImportAnyBaselineOrPersistRecoveryKey(){Manager manager=manager();manager.foreignBootstrap=true;Assert.assertThrows(CloudRuntimeException.class,()->prepare(manager));Assert.assertFalse(manager.events.contains("operation generation render-import"));Assert.assertEquals(0,manager.keys);}
    @Test public void foreignImportedRootMarkerCannotBecomeAUsableRenderedRecoveryReference(){Manager manager=manager();manager.foreignImport=true;Assert.assertThrows(CloudRuntimeException.class,()->prepare(manager));Assert.assertEquals(0,manager.keys);}
    @Test public void sourceOnlyRollbackDoesNotReadMissingOrCorruptTargetCredentialArtifact() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("instance");StorageServiceOperationVO operation=new StorageServiceOperationVO();operation.setRevision(2);operation.setPreviousSnapshotJson("{\"renderedGeneration\":{\"targetInputRef\":{\"kind\":\"TARGET_CREDENTIAL_CAPSULE\",\"artifactUuid\":\"missing\",\"artifactSha256\":\"corrupt\"}}}");
        JsonObject source=new JsonObject(),manifest=new JsonObject();StorageServiceManagerImpl.RenderedBatch batch=new StorageServiceManagerImpl.RenderedBatch(operation,source,manifest,StorageIdentityCapsule.wrappingKey());batch.receipt=new JsonObject();JsonObject staged=new JsonObject();staged.addProperty("renderedManifestSha256","a".repeat(64));JsonObject checkpoint=new JsonObject();checkpoint.addProperty("operationUuid",operation.getUuid());checkpoint.addProperty("sha256","b".repeat(64));staged.add("identityCheckpointRef",checkpoint);batch.receipt.add("staged",staged);JsonObject request=manager.renderedRollbackRequest(instance,batch);
        Assert.assertTrue(request.has("checkpointPrivateKey"));Assert.assertEquals(checkpoint,request.get("identityCheckpointRef"));Assert.assertFalse(request.has("targetCredentialArtifact"));Assert.assertFalse(request.has("transientCredentials"));Assert.assertEquals(operation.getUuid(),request.get("operationUuid").getAsString());
    }
}
