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

import java.util.List;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageAdServiceCheckpointTest {
    private static class Manager extends StorageServiceManagerImpl {
        List<String> events=new ArrayList<>();JsonObject before;boolean wrongCapture,wrongStopped,wrongBoot;
        @Override protected JsonObject requiredRenderedValidationProfile(StorageServiceInstanceVO instance){return new JsonObject();}
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO instance){ }
        @Override protected void prepareRenderedBatch(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,JsonObject nativeBefore){events.add("IMPORT_READONLY_SOURCE");JsonObject manifest=new JsonObject();manifest.addProperty("manifestSha256","b".repeat(64));ThreadLocal<RenderedBatch> batches=(ThreadLocal<RenderedBatch>)ReflectionTestUtils.getField(this,"renderedBatch");batches.set(new RenderedBatch(operation,nativeBefore,manifest,StorageIdentityCapsule.wrappingKey()));}
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){events.add("GEN_"+action);return before.deepCopy();}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject scope,int timeout){events.add(command);
        JsonObject result=new JsonObject();
        result.addProperty("success",true);
        result.add("scope",scope.deepCopy());
        result.add("sourceGeneration",before.get("generation").deepCopy());
        result.addProperty("sourceConfigurationSha256","a".repeat(64));
        result.add("publicAdPreStopSha256",JsonNull.INSTANCE);
        result.addProperty("publicLocalMachineSid","S-1-5-21-1-2-3");
        result.addProperty("bootId","33333333-3333-3333-3333-333333333333");
        if(command.endsWith("capture-source")){result.addProperty("sourceCaptured",true);
        result.addProperty("canonicalDesiredStateChanged",false);
        result.addProperty("sourceRenderedManifestSha256","b".repeat(64));
        result.addProperty("publicAdPreStopCaptured",false);
        if(wrongCapture)result.addProperty("publicAdPreStopCaptured","false");
        }else {result.addProperty("bootHeld",true);
        result.addProperty("serviceSourceStoppedVerified",!wrongStopped);
        result.addProperty("maintenanceKind","SERVICE");
        result.addProperty("stoppedReceiptSha256","c".repeat(64));
        if(wrongBoot)result.addProperty("bootId","44444444-4444-4444-4444-444444444444");
        }return result;
        }
        @Override protected void checkpointConfigurationIdentity(StorageServiceInstanceVO instance,JsonObject scope,String sha){events.add("RAW_IDENTITY_AFTER_STOP");Assert.assertTrue(scope.has("maintenanceUuid"));Assert.assertFalse(scope.has("templateUpgradeUuid"));Assert.assertEquals("a".repeat(64),sha);}
    }
    private static class Fixture {Manager manager;StorageServiceInstanceVO instance;StorageServiceOperationVO operation;}
    private Fixture fixture(){Fixture f=new Fixture();
        f.manager=new Manager();
        f.instance=Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(f.instance.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");
        f.operation=new StorageServiceOperationVO();
        f.operation.setRevision(4);
        f.operation.setPreviousSnapshotJson("{}");
        JsonObject gen=new JsonObject();
        gen.addProperty("instanceUuid",f.instance.getUuid());
        gen.addProperty("revision",3);
        f.manager.before=new JsonObject();
        f.manager.before.add("generation",gen);
        f.manager.before.addProperty("generationStatus","IN_SYNC");
        f.manager.before.addProperty("configurationSha256","a".repeat(64));
        f.manager.before.add("configurationDesiredState",new JsonObject());
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        ReflectionTestUtils.setField(f.manager,"storageOperationDao",operations);
        return f;
        }
    @Test public void serviceSourceImportCapturePendingStopAndRawIdentityHaveStrictOrder(){Fixture f=fixture();f.manager.prepareAdServiceCheckpoint(f.instance,f.operation);List<String> events=f.manager.events;Assert.assertTrue(events.indexOf("IMPORT_READONLY_SOURCE")<events.indexOf("operation generation render-service-capture-source"));Assert.assertTrue(events.indexOf("operation generation render-service-capture-source")<events.indexOf("GEN_begin"));Assert.assertTrue(events.indexOf("GEN_begin")<events.indexOf("operation maintenance service-enter"));Assert.assertTrue(events.indexOf("operation maintenance service-enter")<events.indexOf("RAW_IDENTITY_AFTER_STOP"));JsonObject saved=com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject();Assert.assertTrue(saved.has("adServiceSource"));Assert.assertTrue(saved.has("adServiceStopped"));}
    @Test public void coercedPrestopProofRejectsBeforePendingMarkerStopOrRawRead(){Fixture f=fixture();f.manager.wrongCapture=true;Assert.assertThrows(RuntimeException.class,()->f.manager.prepareAdServiceCheckpoint(f.instance,f.operation));Assert.assertFalse(f.manager.events.contains("GEN_begin"));Assert.assertFalse(f.manager.events.contains("operation maintenance service-enter"));Assert.assertFalse(f.manager.events.contains("RAW_IDENTITY_AFTER_STOP"));}
    @Test public void missingStoppedReceiptPreservesDurableEnterIntentAndCannotReadRawTdb(){Fixture f=fixture();f.manager.wrongStopped=true;Assert.assertThrows(RuntimeException.class,()->f.manager.prepareAdServiceCheckpoint(f.instance,f.operation));Assert.assertFalse(f.manager.events.contains("RAW_IDENTITY_AFTER_STOP"));Assert.assertTrue(com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject().get("adServiceEnterAttempted").getAsBoolean());}
    @Test public void restartedServiceSourceCannotReadRawIdentityAfterOldBootStop(){
        Fixture f=fixture();f.manager.wrongBoot=true;
        Assert.assertThrows(RuntimeException.class,()->f.manager.prepareAdServiceCheckpoint(f.instance,f.operation));
        Assert.assertFalse(f.manager.events.contains("RAW_IDENTITY_AFTER_STOP"));
        Assert.assertTrue(com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject().get("adServiceEnterAttempted").getAsBoolean());
    }
    @Test public void durableServiceSourceBindingRejectsChangedStoppedSamAndBoot(){
        Fixture f=fixture();f.manager.prepareAdServiceCheckpoint(f.instance,f.operation);
        ThreadLocal<StorageServiceOperationVO> writers=(ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(f.manager,"storageWriterOperation");writers.set(f.operation);
        JsonObject snapshot=com.google.gson.JsonParser.parseString(f.operation.getPreviousSnapshotJson()).getAsJsonObject();
        Assert.assertEquals(snapshot.get("adServiceSource"),f.manager.requiredAdServiceSourceIdentity(f.instance));
        for(String field:List.of("bootId","publicLocalMachineSid")){
            JsonObject wrong=snapshot.deepCopy();wrong.getAsJsonObject("adServiceStopped").addProperty(field,field.equals("bootId")?"44444444-4444-4444-4444-444444444444":"S-1-5-21-1-2-4");
            f.operation.setPreviousSnapshotJson(wrong.toString());
            Assert.assertThrows(field,RuntimeException.class,()->f.manager.requiredAdServiceSourceIdentity(f.instance));
        }
    }

}
