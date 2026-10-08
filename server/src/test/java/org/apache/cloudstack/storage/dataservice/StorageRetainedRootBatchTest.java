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

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;

public class StorageRetainedRootBatchTest {
    private static class Manager extends StorageServiceManagerImpl {
        JsonObject oldBaseline,oldManifest,rootScope,authorization;String identitySha;
        @Override protected RenderedBatch createRenderedBatch(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,JsonObject before,JsonObject manifest,JsonObject scope,JsonObject auth,String identity){oldBaseline=before.deepCopy();oldManifest=manifest.deepCopy();rootScope=scope.deepCopy();authorization=auth.deepCopy();identitySha=identity;RenderedBatch batch=new RenderedBatch(operation,before,manifest,StorageIdentityCapsule.wrappingKey());batch.retainedRootAuthorization=auth.deepCopy();batch.identityCheckpointSourceConfigurationSha256=identity;return batch;}
    }
    private JsonObject ref(){JsonObject r=new JsonObject();r.addProperty("authorizationUuid","11111111-1111-1111-1111-111111111111");r.addProperty("sha256","a".repeat(64));return r;}
    private StorageServiceManagerImpl.RenderedBatch batch(Manager m){StorageServiceOperationVO operation=new StorageServiceOperationVO();
        operation.setPreviousSnapshotJson("{}");
        operation.setRevision(5);
        JsonObject nativeState=new JsonObject();
        JsonObject generation=new JsonObject();
        generation.addProperty("revision",4);
        generation.addProperty("instanceUuid","33333333-3333-3333-3333-333333333333");
        nativeState.add("generation",generation);
        nativeState.addProperty("configurationSha256","b".repeat(64));
        JsonObject previous=new JsonObject();
        previous.addProperty("manifestSha256","c".repeat(64));
        JsonObject baseline=new JsonObject();
        baseline.add("nativeState",nativeState);
        baseline.add("generation",generation.deepCopy());
        baseline.add("rendered",previous);
        JsonObject scope=new JsonObject();
        scope.add("instanceUuid",generation.get("instanceUuid"));
        scope.addProperty("operationUuid",operation.getUuid());
        scope.addProperty("templateUpgradeUuid","44444444-4444-4444-4444-444444444444");
        scope.addProperty("revision",operation.getRevision());
        return m.prepareRetainedRenderedBatch(Mockito.mock(StorageServiceInstanceVO.class),operation,scope,baseline,ref(),"d".repeat(64));
        }
    @Test public void retainedBatchKeepsActualOldGenerationSeparateFromAuthenticatedLatestIdentity(){Manager m=new Manager();StorageServiceManagerImpl.RenderedBatch batch=batch(m);Assert.assertEquals("b".repeat(64),batch.source.get("configurationSha256").getAsString());Assert.assertEquals("d".repeat(64),batch.identityCheckpointSourceConfigurationSha256);Assert.assertEquals(4,batch.source.getAsJsonObject("generation").get("revision").getAsInt());Assert.assertEquals(ref(),batch.retainedRootAuthorization);}
    @Test public void checkpointCredentialRefsUseLatestHashAndNeverTheOldHistoricalConfiguration(){Manager m=new Manager();StorageServiceManagerImpl.RenderedBatch batch=batch(m);JsonObject canonical=new JsonObject();for(String path:StorageRenderedDesiredState.PATHS)canonical.add(path,JsonNull.INSTANCE);JsonObject smb=new JsonObject(),share=new JsonObject();share.addProperty("uuid","22222222-2222-2222-2222-222222222222");share.add("acls",new JsonArray());JsonArray shares=new JsonArray();shares.add(share);smb.add("shares",shares);canonical.add("desired-state/smb-share-apply.json",smb);JsonObject refs=m.renderedCredentialReferences(batch,canonical);JsonObject checkpoint=refs.getAsJsonObject("SMB").getAsJsonObject(share.get("uuid").getAsString());Assert.assertEquals("d".repeat(64),checkpoint.get("sourceConfigurationSha256").getAsString());Assert.assertEquals(batch.operation.getUuid(),checkpoint.get("operationUuid").getAsString());}
    @Test public void historicalRenderRollbackIsRejectedButProtectedForwardActivationRemainsAvailable(){Manager m=new Manager();StorageServiceManagerImpl.RenderedBatch batch=batch(m);JsonObject staged=new JsonObject();staged.addProperty("renderedManifestSha256","e".repeat(64));JsonObject checkpoint=new JsonObject();checkpoint.addProperty("operationUuid",batch.operation.getUuid());checkpoint.addProperty("sha256","a".repeat(64));staged.add("identityCheckpointRef",checkpoint);batch.receipt=new JsonObject();batch.receipt.add("staged",staged);Assert.assertThrows(RuntimeException.class,()->m.renderedRollbackRequest(Mockito.mock(StorageServiceInstanceVO.class),batch));StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("33333333-3333-3333-3333-333333333333");Assert.assertTrue(m.renderedActivationRequest(instance,batch).has("checkpointPrivateKey"));}
    @Test public void malformedRetainedFrameCannotReachKeyPersistenceOrRenderEffects(){Manager m=new Manager();StorageServiceOperationVO operation=new StorageServiceOperationVO();operation.setPreviousSnapshotJson("{}");JsonObject baseline=new JsonObject(),nativeState=new JsonObject(),generation=new JsonObject(),manifest=new JsonObject();generation.addProperty("instanceUuid","33333333-3333-3333-3333-333333333333");nativeState.add("generation",generation);baseline.add("generation",generation.deepCopy());baseline.add("nativeState",nativeState);manifest.addProperty("manifestSha256","c".repeat(64));baseline.add("rendered",manifest);Assert.assertThrows(RuntimeException.class,()->m.prepareRetainedRenderedBatch(Mockito.mock(StorageServiceInstanceVO.class),operation,new JsonObject(),baseline,ref(),"d".repeat(64)));Assert.assertNull(m.oldBaseline);Assert.assertEquals("{}",operation.getPreviousSnapshotJson());}
}
