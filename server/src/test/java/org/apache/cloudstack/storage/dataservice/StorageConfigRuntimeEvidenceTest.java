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
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

public class StorageConfigRuntimeEvidenceTest {
    private static final String INSTANCE = "11111111-1111-4111-8111-111111111111";
    private static final String OPERATION = "22222222-2222-4222-8222-222222222222";
    static JsonObject status(String instance, String operation, long revision) {
        JsonObject generation = new JsonObject();generation.addProperty("instanceUuid", instance);generation.addProperty("operationUuid", operation);
        generation.addProperty("revision", revision);generation.addProperty("configurationSha256", "a".repeat(64));generation.addProperty("verifiedAt", System.currentTimeMillis()/1000.0);
        JsonObject result = new JsonObject();result.addProperty("success", true);result.addProperty("generationSupported", true);result.add("generation", generation);
        result.addProperty("runtimeRevision", revision);result.addProperty("configurationSha256", "a".repeat(64));
        result.addProperty("bootId", "33333333-3333-4333-8333-333333333333");result.addProperty("generationStatus", "IN_SYNC");
        result.add("pendingOperationUuid", JsonNull.INSTANCE);return result;
    }
    private static Map<String, byte[]> entries(long now) {
        JsonObject observation=new JsonObject();observation.addProperty("success",true);observation.addProperty("status","ok");
        observation.addProperty("generatedEpoch",now/1000.0);
        Map<String,byte[]> result=new LinkedHashMap<>();result.put("desired/instance.json",("{}").getBytes(StandardCharsets.UTF_8));
        for(String command:List.of("health","inventory"))result.put("runtime/"+command+".json",observation.toString().getBytes(StandardCharsets.UTF_8));
        return result;
    }
    @Test public void verifiedPairIsBoundToWholeGenerationAndOldCacheIsOnlyDiagnostic() {
        long now=System.currentTimeMillis();JsonObject status=status(INSTANCE,OPERATION,7);Map<String,byte[]> entries=entries(now);
        JsonObject evidence=StorageConfigRuntimeEvidence.create(INSTANCE,OPERATION,7,"snapshot",status,status,entries,now);
        StorageConfigRuntimeEvidence.requireStored(evidence,INSTANCE,OPERATION,7,entries);
        JsonObject stale=StorageConfigRuntimeEvidence.stale("health",INSTANCE,evidence,entries);
        Assert.assertFalse(stale.get("success").getAsBoolean());Assert.assertEquals("STALE",stale.get("status").getAsString());
        Assert.assertEquals(now,stale.get("observedAtMillis").getAsLong());Assert.assertEquals(7,stale.get("desiredRevision").getAsLong());
        Assert.assertNull(StorageConfigRuntimeEvidence.stale("sessions",INSTANCE,evidence,entries));
        Assert.assertThrows(RuntimeException.class,()->StorageConfigRuntimeEvidence.requireFresh(evidence,entries,now+46000));
    }
    @Test public void onlyThisCommittedPendingWriterCanSupplyEvidence() {
        long now=System.currentTimeMillis();JsonObject status=status(INSTANCE,OPERATION,7);status.addProperty("generationStatus","PENDING");
        status.addProperty("pendingOperationUuid",OPERATION);
        StorageConfigRuntimeEvidence.create(INSTANCE,OPERATION,7,"snapshot",status,status,entries(now),now);
        status.addProperty("pendingOperationUuid",INSTANCE);
        Assert.assertThrows(RuntimeException.class,()->StorageConfigRuntimeEvidence.create(INSTANCE,OPERATION,7,"snapshot",status,status,entries(now),now));
    }
    @Test public void bootRevisionConfigForeignScopeAndTypedUnknownsRejectBeforeCachePublication() {
        long now=System.currentTimeMillis();
        for(int mode=0;mode<10;mode++) {
            JsonObject before=status(INSTANCE,OPERATION,7),after=before.deepCopy();
            if(mode==0)after.addProperty("bootId","44444444-4444-4444-8444-444444444444");
            if(mode==1)after.addProperty("runtimeRevision",8);
            if(mode==2)after.addProperty("configurationSha256","b".repeat(64));
            if(mode==3)after.getAsJsonObject("generation").addProperty("instanceUuid",OPERATION);
            if(mode==4)after.getAsJsonObject("generation").addProperty("operationUuid",INSTANCE);
            if(mode==5)after.addProperty("success","true");
            if(mode==6)after.addProperty("generationSupported",false);
            if(mode==7)after.addProperty("generationStatus","UNKNOWN");
            if(mode==8)after.remove("pendingOperationUuid");
            if(mode==9)after.getAsJsonObject("generation").addProperty("verifiedAt","1");
            Assert.assertThrows(RuntimeException.class,()->StorageConfigRuntimeEvidence.create(INSTANCE,OPERATION,7,"snapshot",before,after,entries(now),now));
        }
    }
    @Test public void partialDegradedStaleFutureAndChangedStoredBytesReject() {
        long now=System.currentTimeMillis();JsonObject nativeStatus=status(INSTANCE,OPERATION,7);
        for(int mode=0;mode<8;mode++) {
            Map<String,byte[]> entries=entries(now);JsonObject health=StorageConfigArchive.json(entries.get("runtime/health.json")).getAsJsonObject();
            if(mode==0)health.addProperty("success",false);
            if(mode==1)health.addProperty("success","true");
            if(mode==2)health.addProperty("status","degraded");
            if(mode==3)health.addProperty("generatedEpoch",(now-46000)/1000.0);
            if(mode==4)health.addProperty("generatedEpoch",(now+6000)/1000.0);
            if(mode==5)health.addProperty("generatedEpoch",String.valueOf(now/1000.0));
            if(mode==6)health.addProperty("cached",true);
            if(mode==7)health.addProperty("cached","false");
            entries.put("runtime/health.json",health.toString().getBytes(StandardCharsets.UTF_8));
            Assert.assertThrows(RuntimeException.class,()->StorageConfigRuntimeEvidence.create(INSTANCE,OPERATION,7,"snapshot",nativeStatus,nativeStatus,entries,now));
        }
        Map<String,byte[]> entries=entries(now);JsonObject evidence=StorageConfigRuntimeEvidence.create(INSTANCE,OPERATION,7,"snapshot",nativeStatus,nativeStatus,entries,now);
        entries.put("runtime/health.json",entries(now-1000).get("runtime/health.json"));
        Assert.assertThrows(RuntimeException.class,()->StorageConfigRuntimeEvidence.requireStored(evidence,INSTANCE,OPERATION,7,entries));
    }

    private static final class PromotionFixture implements AutoCloseable {
        final StorageServiceInstanceVO instance=new StorageServiceInstanceVO();
        final StorageServiceOperationVO operation=new StorageServiceOperationVO();
        final StorageConfigArtifactVO previous=new StorageConfigArtifactVO();
        final StorageConfigArtifactDao artifacts=Mockito.mock(StorageConfigArtifactDao.class);
        final Path directory;final StorageConfigArtifactStore store;final StorageServiceConfiguration service;
        final JsonObject nativeStatus;boolean failedHealth;boolean changedBeforePromotion;int statuses;
        PromotionFixture() throws Exception {
            ReflectionTestUtils.setField(instance,"id",7L);ReflectionTestUtils.setField(instance,"uuid",INSTANCE);
            operation.setInstanceId(7);operation.setRevision(7);ReflectionTestUtils.setField(operation,"uuid",OPERATION);
            operation.setSnapshotJson("{\"schemaVersion\":2,\"instanceId\":7,\"tables\":[]}");
            previous.setInstanceId(7);previous.setKind("RESTORE_POINT");previous.setState("ACTIVE_LKG");previous.setDesiredRevision(6);
            ReflectionTestUtils.setField(previous,"id",6L);Mockito.when(artifacts.listByInstance(7)).thenReturn(List.of(previous));
            Mockito.when(artifacts.persist(Mockito.any())).thenAnswer(call->{StorageConfigArtifactVO row=call.getArgument(0);ReflectionTestUtils.setField(row,"id",8L);return row;});
            Mockito.when(artifacts.promoteVerified(Mockito.eq(7L),Mockito.eq(8L),Mockito.eq(6L),Mockito.eq(6L),Mockito.any())).thenReturn(true);
            directory=Files.createTempDirectory("config-lkg-evidence-",java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            store=new StorageConfigArtifactStore(directory);nativeStatus=status(INSTANCE,OPERATION,7);
            StorageServiceManagerImpl manager=new StorageServiceManagerImpl() {
                @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO value) { }
                @Override protected String captureConfigurationSnapshot(long id) {return operation.getSnapshotJson();}
                @Override protected JsonObject configurationInstanceMetadata(StorageServiceInstanceVO value) {JsonObject result=new JsonObject();result.addProperty("uuid",INSTANCE);result.addProperty("productVersion","4.23.0");return result;}
                @Override protected Map<Long,JsonObject> configurationVolumeMetadata(String snapshot) {return Map.of();}
                @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO value,StorageServiceOperationVO owner,String action) {
                    statuses++;JsonObject observed=nativeStatus.deepCopy();if(changedBeforePromotion&&statuses>=4)observed.addProperty("bootId","44444444-4444-4444-8444-444444444444");return observed;
                }
                @Override protected JsonObject observeConfigurationRuntime(StorageServiceInstanceVO value,String command) {
                    JsonObject observed=StorageConfigArchive.json(entries(System.currentTimeMillis()).get("runtime/"+command+".json")).getAsJsonObject();
                    if(failedHealth&&"health".equals(command))observed.addProperty("success",false);return observed;
                }
                @Override protected boolean hasJoinedStorageAdDomain(StorageServiceInstanceVO value) {return false;}
            };
            service=new StorageServiceConfiguration(manager,artifacts,Mockito.mock(StorageServiceOperationDao.class),store);
        }
        public void close() throws Exception {try(java.util.stream.Stream<Path> files=Files.list(directory)){for(Path path:(Iterable<Path>)files::iterator)Files.delete(path);}Files.delete(directory);}
    }
    @Test public void actualPromotionProducerPublishesOnlyBoundArchiveBeforeAtomicPointerCas() throws Exception {
        try(PromotionFixture fixture=new PromotionFixture()) {
            fixture.service.promoteVerified(fixture.instance,fixture.operation);
            org.mockito.ArgumentCaptor<StorageConfigArtifactVO> candidate=org.mockito.ArgumentCaptor.forClass(StorageConfigArtifactVO.class);
            Mockito.verify(fixture.artifacts).persist(candidate.capture());StorageConfigArtifactVO point=candidate.getValue();
            Map<String,byte[]> archive=StorageConfigArchive.validate(fixture.store.read(point.getUuid(),point.getSha256()));
            JsonObject metadata=StorageConfigArchive.json(archive.get("manifest.json")).getAsJsonObject();
            StorageConfigRuntimeEvidence.requireStored(metadata.getAsJsonObject("runtimeEvidence"),INSTANCE,OPERATION,7,archive);
            Mockito.verify(fixture.artifacts).promoteVerified(Mockito.eq(7L),Mockito.eq(8L),Mockito.eq(6L),Mockito.eq(6L),Mockito.any());
        }
    }
    @Test public void failedOrChangedNativeObservationCannotReplacePreviousActivePoint() throws Exception {
        for(int mode=0;mode<2;mode++)try(PromotionFixture fixture=new PromotionFixture()) {
            fixture.failedHealth=mode==0;fixture.changedBeforePromotion=mode==1;
            Assert.assertThrows(RuntimeException.class,()->fixture.service.promoteVerified(fixture.instance,fixture.operation));
            Mockito.verify(fixture.artifacts,Mockito.never()).promoteVerified(Mockito.anyLong(),Mockito.anyLong(),Mockito.any(),Mockito.anyLong(),Mockito.any());
            Assert.assertEquals("ACTIVE_LKG",fixture.previous.getState());
            if(mode==1)try(java.util.stream.Stream<Path> files=Files.list(fixture.directory)){Assert.assertEquals(0,files.count());}
        }
    }
}
