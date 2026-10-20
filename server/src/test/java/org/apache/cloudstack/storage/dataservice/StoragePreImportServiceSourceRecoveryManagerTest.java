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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/** Pure JSON/Mockito: QGA, daemon changes, native import/replay and SQL snapshot restore are forbidden. */
public class StoragePreImportServiceSourceRecoveryManagerTest {
    private static JsonObject object(String value){return StorageUnchangedVerifiedSourceRecoveryTest.object(value);}
    private static class Fixture extends StorageServiceManagerImpl {
        final StoragePreImportServiceSourceRecoveryTest.Fixture data=new StoragePreImportServiceSourceRecoveryTest.Fixture();
        final StorageServiceOperationVO operation=data.original,source=data.source;
        final StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao controls=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao artifacts=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao.class);
        final List<String> dbWrites=new ArrayList<>(),nativeReads=new ArrayList<>();
        final JsonObject health=object("{\"success\":true,\"status\":\"ok\"}"),inventory=health.deepCopy();
        final JsonObject rootHold=object("{\"success\":true,\"maintenanceSupported\":true,\"serviceMaintenanceSupported\":true,\"bootHeld\":false,\"scope\":null}");
        final JsonObject serviceHold=rootHold.deepCopy();
        final JsonObject rendered=object("{\"success\":true,\"renderedGenerationSupported\":true,\"bootHeld\":false,\"current\":null,\"activation\":null}");
        final JsonObject lease=object("{\"success\":true,\"reservationSupported\":true,\"logicalReservationOnly\":true,\"reservationAcquired\":false,\"expired\":false,\"scope\":null,\"requirements\":null,\"leaseBootId\":null}");
        final JsonObject physical=new JsonObject();
        final StorageConfigArtifactVO lkg=new StorageConfigArtifactVO();
        final byte[] archive;
        long caller=2;int healthReads,failHealthAt,physicalReads;
        boolean persist=true,driftAfterIntent,driftAfterPhysical;
        Fixture()throws Exception {
            Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn(data.instance());Mockito.when(instance.getVmId()).thenReturn(71L);
            ReflectionTestUtils.setField(this,"storageOperationDao",operations);ReflectionTestUtils.setField(this,"storageOperationControlDao",controls);
            ReflectionTestUtils.setField(this,"storageConfigArtifactDao",artifacts);
            ReflectionTestUtils.setField(this,"storageTemplateUpgradeDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao.class));
            ReflectionTestUtils.setField(this,"storageRuntimeUpgradeDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao.class));
            ReflectionTestUtils.setField(this,"volumeDao",Mockito.mock(com.cloud.storage.dao.VolumeDao.class));
            var accounts=Mockito.mock(com.cloud.user.AccountManager.class);Mockito.when(accounts.isRootAdmin(2L)).thenReturn(true);
            ReflectionTestUtils.setField(this,"storageAccountManager",accounts);
            Mockito.when(operations.findById(operation.getId())).thenReturn(operation);Mockito.when(operations.findById(source.getId())).thenReturn(source);
            StorageOperationHistoryFixtures.rows(operations,7L,List.of(operation,source));
            Mockito.when(operations.update(Mockito.eq(operation.getId()),Mockito.same(operation))).thenAnswer(call->{
                dbWrites.add("ROLLED_BACK".equals(operation.getState())?"finalize":"intent");
                if(driftAfterIntent)data.status.addProperty("bootId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
                return persist;
            });
            JsonObject desired=data.status.getAsJsonObject("configurationDesiredState");
            String nfsVolume=data.bindings.getAsJsonObject("dataVolumes").keySet().iterator().next();
            JsonObject nfs=desired.getAsJsonObject("desired-state/nfs-export-apply.json").getAsJsonArray("exports").get(0).getAsJsonObject().getAsJsonObject("config");
            nfs.add("lastInspection",object("{\"sizeBytes\":21474836480}"));
            JsonArray volumes=new JsonArray();volumes.add(disk(nfsVolume,"FILE_DATA",21474836480L,"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
            String smb="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",iscsi="dddddddd-dddd-4ddd-8ddd-dddddddddddd",nvme="eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
            desired.add("desired-state/smb-share-apply.json",object("{\"enabled\":true,\"shares\":[{\"config\":{\"attachedVolumeUuid\":\""+smb+"\",\"filesystemUuid\":\"cccccccc-cccc-4ccc-8ccc-cccccccccccc\",\"lastInspection\":{\"sizeBytes\":2147483648}}}]}"));
            desired.add("iscsi-targets.json",object("{\"enabled\":true,\"targets\":[{\"backstoreType\":\"BLOCK\",\"volumeUuid\":\""+iscsi+"\",\"volumeSizeBytes\":2147483648}]}"));
            desired.add("nvmeof-subsystems.json",object("{\"enabled\":true,\"subsystems\":[{\"namespaces\":[{\"backstoreType\":\"BLOCK\",\"volumeUuid\":\""+nvme+"\",\"volumeSizeBytes\":2147483648}]}]}"));
            volumes.add(disk(smb,"FILE_DATA",2147483648L,"cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
            volumes.add(disk(iscsi,"BLOCK_RAW",2147483648L,null));volumes.add(disk(nvme,"BLOCK_RAW",2147483648L,null));
            int device=3;
            for(String id:List.of(smb,iscsi,nvme)){
                JsonObject row=object("{\"sizeBytes\":2147483648,\"poolId\":1,\"provisioningType\":\"SPARSE\"}");
                row.addProperty("deviceId",device++);row.addProperty("path",id);data.bindings.getAsJsonObject("dataVolumes").add(id,row);
            }
            data.frozen.add("configurationDesiredState",desired.deepCopy());
            JsonObject sourceResult=object(source.getResultJson());sourceResult.add("_targetResourceBindings",data.bindings.deepCopy());source.setResultJson(sourceResult.toString());
            physical.add("bootId",data.status.get("bootId"));physical.add("volumes",volumes);
            lease.add("bootId",data.status.get("bootId"));
            JsonObject observed=object("{\"memoryAvailableBytes\":1024,\"stagingFreeBytes\":1024,\"loadPerCpu\":0}");
            observed.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);lease.add("observed",observed);
            long now=System.currentTimeMillis();
            JsonObject oldObservation=health.deepCopy();oldObservation.addProperty("generatedEpoch",now/1000.0);
            Map<String,byte[]> entries=new LinkedHashMap<>();entries.put("desired/instance.json","{}".getBytes(StandardCharsets.UTF_8));
            for(String key:List.of("health","inventory"))entries.put("runtime/"+key+".json",oldObservation.toString().getBytes(StandardCharsets.UTF_8));
            // Historical capture can be PENDING for that exact completed SOURCE.
            JsonObject collected=data.status.deepCopy();collected.addProperty("generationStatus","PENDING");collected.addProperty("pendingOperationUuid",source.getUuid());
            JsonObject evidence=StorageConfigRuntimeEvidence.create(data.instance(),source.getUuid(),14,source.getSnapshotJson(),collected,collected,entries,now);
            JsonObject metadata=new JsonObject();metadata.addProperty("verification","VERIFIED_SUCCESS");metadata.addProperty("sourceInstanceUuid",data.instance());
            metadata.addProperty("operationUuid",source.getUuid());metadata.addProperty("desiredRevision",14);metadata.add("nativeGeneration",data.status.get("generation").deepCopy());metadata.add("runtimeEvidence",evidence);
            archive=StorageConfigArchive.create(entries,metadata);
            lkg.setInstanceId(7);lkg.setKind("RESTORE_POINT");lkg.setState("ACTIVE_LKG");lkg.setDesiredRevision(14);lkg.setSourceOperationId(source.getId());
            lkg.setCreatedBy(2);lkg.setMetadataJson(metadata.toString());lkg.setSha256(StorageConfigArchive.sha256(archive));lkg.setSize(archive.length);
            Mockito.when(artifacts.listByInstance(7L)).thenReturn(List.of(lkg));
        }
        JsonObject disk(String uuid,String kind,long size,String filesystem){
            JsonObject row=new JsonObject();row.addProperty("volumeUuid",uuid);row.addProperty("kind",kind);row.addProperty("sizeBytes",size);
            row.addProperty("serial",uuid.replace("-",""));row.addProperty("mappingStatus","EXACT");row.addProperty("matchedBy","VOLUME_SERIAL");
            if(filesystem!=null){row.addProperty("filesystemUuid",filesystem);row.addProperty("filesystem","ext4");}
            return row;
        }
        @Override public Long getStorageServiceSyncId(org.apache.cloudstack.api.BaseCmd cmd){return 7L;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
        @Override protected String captureConfigurationSnapshot(long id){Assert.assertEquals(7L,id);return data.snapshot.toString();}
        @Override protected JsonObject operationResourceBindings(StorageServiceInstanceVO scope){return data.bindings.deepCopy();}
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO scope){ }
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO scope){nativeReads.add("verifyDesired");}
        @Override protected JsonObject currentSmbDataIdentity(StorageServiceInstanceVO scope,StorageServiceOperationVO writer){
            nativeReads.add("physical");physicalReads++;
            JsonObject value=physical.deepCopy();value.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
            if(driftAfterPhysical)data.snapshot.addProperty("schemaVersion",2.0);
            return value;
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO scope,StorageServiceOperationVO writer,String action){
            Assert.assertNull(writer);Assert.assertEquals("status",action);nativeReads.add("generation status");return data.status.deepCopy();
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO scope,String action,JsonObject request,int timeout){
            nativeReads.add(action);
            switch(action){
                case "operation writer-idle":return object("{\"success\":true,\"status\":\"WRITER_IDLE\",\"writerIdle\":true,\"writerLockSupported\":true,\"bootChildActive\":false,\"bootRecoveryRequired\":false}");
                case "operation generation frozen":Assert.assertEquals(data.status.get("generation"),request);return data.frozen.deepCopy();
                case "operation maintenance status":return rootHold.deepCopy();
                case "operation maintenance service-status":return serviceHold.deepCopy();
                case "operation generation render-status":return rendered.deepCopy();
                case "operation reservation status":return lease.deepCopy();
                case "operation verify":{
                    healthReads++;JsonObject value=health.deepCopy();value.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
                    if(healthReads==failHealthAt)value.addProperty("success",false);return value;
                }
                case "operation observe":{JsonObject value=inventory.deepCopy();value.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);return value;}
                default:throw new AssertionError("Native mutation or unexpected command is forbidden: "+action);
            }
        }
        void invoke(){
            var user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(caller);
            var account=Mockito.mock(com.cloud.user.Account.class);Mockito.when(account.getId()).thenReturn(2L);
            org.apache.cloudstack.context.CallContext.register(user,account);
            GlobalLock lock=Mockito.mock(GlobalLock.class);Mockito.when(lock.lock(120)).thenReturn(true);
            var cmd=Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class);Mockito.when(cmd.getOperationId()).thenReturn(operation.getId());
            try(MockedStatic<GlobalLock> locks=Mockito.mockStatic(GlobalLock.class);
                    MockedConstruction<StorageConfigArtifactStore> stores=Mockito.mockConstruction(StorageConfigArtifactStore.class,(mock,context)->
                        Mockito.when(mock.read(lkg.getUuid(),lkg.getSha256())).thenReturn(archive));
                    MockedConstruction<StorageServiceDesiredSnapshot> snapshots=Mockito.mockConstruction(StorageServiceDesiredSnapshot.class,(mock,context)->
                        Mockito.doAnswer(call->{throw new AssertionError("SQL snapshot restore is forbidden");}).when(mock).restore(Mockito.anyLong(),Mockito.anyString()))){
                locks.when(()->GlobalLock.getInternLock("StorageServiceWriter-7")).thenReturn(lock);
                reconcileStorageServiceOperation(cmd);
            }finally{org.apache.cloudstack.context.CallContext.unregister();}
        }
    }
    @Test public void explicitOriginalAdminClosesOnlyOriginalWriterAfterFourProtocolAndFileRawSourceProofs()throws Exception {
        Fixture f=new Fixture();String prior=f.operation.getPreviousSnapshotJson(),source=f.source.getResultJson();JsonObject nativeBefore=f.data.status.deepCopy();
        f.invoke();Assert.assertEquals(List.of("intent","finalize"),f.dbWrites);Assert.assertEquals("ROLLED_BACK",f.operation.getState());
        Assert.assertEquals(2,f.healthReads);Assert.assertEquals(2,f.physicalReads);Assert.assertEquals(4,f.physical.getAsJsonArray("volumes").size());
        Assert.assertEquals(prior,f.operation.getPreviousSnapshotJson());Assert.assertNull(f.operation.getSnapshotJson());Assert.assertEquals(source,f.source.getResultJson());
        Assert.assertEquals(nativeBefore,f.data.status);Assert.assertTrue(f.operation.getDiagnostic().startsWith("Original pre-import renderer failure"));
        Assert.assertTrue(f.operation.getDiagnostic().contains("no protocol replay or native mutation"));
        Assert.assertTrue(object(f.operation.getResultJson()).has(StorageUnchangedVerifiedSourceRecovery.SERVICE_IMPORT_RESULT_KEY));
        Assert.assertFalse(object(f.operation.getResultJson()).has("_targetConfigurationSha256"));
        Assert.assertNull(((ThreadLocal<?>)ReflectionTestUtils.getField(f,"explicitUnchangedSourceRecovery")).get());
    }
    @Test public void actorUnresolvedWriterControlsAndMissingGuardsRejectWithoutNativeMutationOrDatabaseFinalization()throws Exception {
        Fixture actor=new Fixture();actor.caller=3;Assert.assertThrows(CloudRuntimeException.class,actor::invoke);Assert.assertTrue(actor.dbWrites.isEmpty());Assert.assertTrue(actor.nativeReads.isEmpty());
        Fixture controls=new Fixture();Mockito.when(controls.controls.findByOperation(controls.operation.getId())).thenReturn(new StorageServiceOperationControlVO());
        Assert.assertThrows(CloudRuntimeException.class,controls::invoke);Assert.assertTrue(controls.dbWrites.isEmpty());
        Fixture guard=new Fixture();ReflectionTestUtils.setField(guard,"storageConfigArtifactDao",null);Assert.assertThrows(CloudRuntimeException.class,guard::invoke);Assert.assertTrue(guard.dbWrites.isEmpty());
        Fixture fresh=new Fixture();Mockito.when(fresh.operations.findById(fresh.operation.getId())).thenReturn(null);Assert.assertThrows(RuntimeException.class,fresh::invoke);Assert.assertTrue(fresh.dbWrites.isEmpty());
        for(String state:List.of("RUNNING","RECOVERY_REQUIRED","COMPLETE","ROLLED_BACK")){
            Fixture f=new Fixture();StorageServiceOperationVO other=new StorageServiceOperationVO();StorageUnchangedVerifiedSourceRecoveryTest.field(other,"id",999L);
            other.setInstanceId(7);other.setRevision(16);other.setState(state);StorageOperationHistoryFixtures.rows(f.operations,7L,List.of(f.operation,f.source,other));
            Assert.assertThrows(CloudRuntimeException.class,f::invoke);Assert.assertTrue(f.dbWrites.isEmpty());
        }
    }
    @Test public void pendingHoldsRenderedEffectsLeaseLkgOrFileAndRawIdentityMismatchFailBeforeAnyIntent()throws Exception {
        for(int mode=0;mode<12;mode++){
            Fixture f=new Fixture();
            if(mode==0)f.data.status.addProperty("pendingOperationUuid",f.operation.getUuid());
            if(mode==1)f.rootHold.addProperty("bootHeld",true);
            if(mode==2)f.serviceHold.addProperty("bootHeld",true);
            if(mode==3)f.rendered.add("current",new JsonObject());
            if(mode==4)f.rendered.add("activation",new JsonObject());
            if(mode==5)f.lease.addProperty("reservationAcquired",true);
            if(mode==6)f.lkg.setSourceOperationId(f.operation.getId());
            if(mode==7)f.physical.getAsJsonArray("volumes").get(0).getAsJsonObject().addProperty("filesystemUuid","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
            if(mode==8)f.physical.getAsJsonArray("volumes").get(2).getAsJsonObject().addProperty("serial","wrong");
            if(mode==9)f.physical.getAsJsonArray("volumes").get(3).getAsJsonObject().addProperty("sizeBytes",1);
            if(mode==10)f.physical.getAsJsonArray("volumes").remove(3);
            if(mode==11)f.driftAfterPhysical=true;
            Assert.assertThrows(RuntimeException.class,f::invoke);Assert.assertTrue("mode="+mode,f.dbWrites.isEmpty());Assert.assertEquals("RECOVERY_REQUIRED",f.operation.getState());
        }
    }
    @Test public void failedHealthIntentPersistenceAndPostIntentDriftNeverFinalizeAndExplicitRetryRevalidates()throws Exception {
        Fixture first=new Fixture();first.failHealthAt=1;Assert.assertThrows(RuntimeException.class,first::invoke);Assert.assertTrue(first.dbWrites.isEmpty());
        Fixture persist=new Fixture();persist.persist=false;Assert.assertThrows(CloudRuntimeException.class,persist::invoke);Assert.assertEquals(List.of("intent"),persist.dbWrites);Assert.assertEquals("RECOVERY_REQUIRED",persist.operation.getState());
        Fixture drift=new Fixture();drift.driftAfterIntent=true;Assert.assertThrows(CloudRuntimeException.class,drift::invoke);Assert.assertEquals(List.of("intent"),drift.dbWrites);
        Fixture retry=new Fixture();retry.failHealthAt=2;Assert.assertThrows(CloudRuntimeException.class,retry::invoke);Assert.assertEquals(List.of("intent"),retry.dbWrites);
        JsonElement proof=object(retry.operation.getResultJson()).get(StorageUnchangedVerifiedSourceRecovery.SERVICE_IMPORT_RESULT_KEY).deepCopy();
        retry.dbWrites.clear();int reads=retry.nativeReads.size();
        Assert.assertThrows(RuntimeException.class,()->retry.recoverInterruptedStorageWriter(retry.instance,retry.operation));
        Assert.assertTrue(retry.dbWrites.isEmpty());Assert.assertEquals(reads,retry.nativeReads.size());
        retry.failHealthAt=0;retry.invoke();Assert.assertEquals(List.of("finalize"),retry.dbWrites);
        Assert.assertEquals(proof,object(retry.operation.getResultJson()).get(StorageUnchangedVerifiedSourceRecovery.SERVICE_IMPORT_RESULT_KEY));
    }
    @Test public void optionalClosedImportDiagnosticIsPreservedButCannotAuthorizeAChangedSource()throws Exception {
        Fixture f=new Fixture();f.data.diagnostic(f.data.importReceipt());String original=object(f.operation.getResultJson()).get("_renderedFailureDiagnostic").toString();f.invoke();
        Assert.assertEquals(original,object(f.operation.getResultJson()).get("_renderedFailureDiagnostic").toString());
        Fixture changed=new Fixture();changed.data.diagnostic(changed.data.importReceipt());changed.data.status.addProperty("configurationSha256","b".repeat(64));
        Assert.assertThrows(CloudRuntimeException.class,changed::invoke);Assert.assertTrue(changed.dbWrites.isEmpty());
    }
    @Test public void closedNfsValidatorReceiptCanCloseOnlyTheExactUnchangedPreImportWriter()throws Exception {
        for(String fixed:List.of("protocol=NFS; stage=PARSER_STARTUP; runtimeValidationCode=NFS_VALIDATOR_PARSER_REJECTED",
                "protocol=NFS; stage=RUNTIME_SELECTION; runtimeValidationCode=NFS_VALIDATOR_RUNTIME_UNTRUSTED")){
            Fixture f=new Fixture();JsonObject receipt=f.data.importReceipt();receipt.addProperty("diagnostic",fixed);f.data.diagnostic(receipt);
            f.invoke();Assert.assertEquals(List.of("intent","finalize"),f.dbWrites);Assert.assertEquals("ROLLED_BACK",f.operation.getState());
            Assert.assertEquals(fixed,object(f.operation.getResultJson()).getAsJsonObject("_renderedFailureDiagnostic").get("diagnostic").getAsString());
        }
        for(String invalid:List.of("protocol=NFS; stage=WORKER_TRANSPORT; runtimeValidationCode=NFS_VALIDATOR_PARSER_REJECTED",
                "protocol=NFS; stage=PARSER_STARTUP; runtimeValidationCode=NFS_VALIDATOR_PARSER_REJECTED; password=private",
                "protocol=SMB; stage=PARSER_STARTUP; runtimeValidationCode=NFS_VALIDATOR_PARSER_REJECTED")){
            Fixture f=new Fixture();JsonObject receipt=f.data.importReceipt();receipt.addProperty("diagnostic",invalid);f.data.diagnostic(receipt);
            Assert.assertThrows(CloudRuntimeException.class,f::invoke);Assert.assertTrue(f.dbWrites.isEmpty());Assert.assertEquals("RECOVERY_REQUIRED",f.operation.getState());
        }
        Fixture held=new Fixture();JsonObject receipt=held.data.importReceipt();
        receipt.addProperty("diagnostic","protocol=NFS; stage=PARSER_STARTUP; runtimeValidationCode=NFS_VALIDATOR_PARSER_REJECTED");held.data.diagnostic(receipt);
        held.serviceHold.addProperty("bootHeld",true);Assert.assertThrows(CloudRuntimeException.class,held::invoke);Assert.assertTrue(held.dbWrites.isEmpty());
    }

    @Test public void transportAndVerifyReceiptsRemainDescriptiveAndCannotBypassSourceOrPhaseProtocolGuards()throws Exception {
        for(String fixed:List.of("runtimeSchemaVersion=1; protocol=GENERATION; phase=TRANSPORT; code=NATIVE_CHILD_INPUT_UNAVAILABLE",
                "runtimeSchemaVersion=1; protocol=ISCSI; phase=VERIFY; code=RUNTIME_VERIFY_FAILED")){
            Fixture f=new Fixture();JsonObject receipt=f.data.importReceipt();receipt.addProperty("diagnostic",fixed);f.data.diagnostic(receipt);
            f.invoke();Assert.assertEquals(List.of("intent","finalize"),f.dbWrites);Assert.assertEquals("ROLLED_BACK",f.operation.getState());
            Assert.assertEquals(fixed,object(f.operation.getResultJson()).getAsJsonObject("_renderedFailureDiagnostic").get("diagnostic").getAsString());
        }
        for(String invalid:List.of("runtimeSchemaVersion=1; protocol=SMB; phase=TRANSPORT; code=NATIVE_CHILD_INPUT_UNAVAILABLE",
                "runtimeSchemaVersion=1; protocol=ISCSI; phase=APPLY; code=RUNTIME_VERIFY_FAILED",
                "runtimeSchemaVersion=1; protocol=ISCSI; phase=VERIFY; code=RUNTIME_VERIFY_FAILED_PRIVATE",
                "runtimeSchemaVersion=1; protocol=GENERATION; phase=STAGE; code=STAGE_RENDER_FAILED")){
            Fixture f=new Fixture();JsonObject receipt=f.data.importReceipt();receipt.addProperty("diagnostic",invalid);f.data.diagnostic(receipt);
            Assert.assertThrows(CloudRuntimeException.class,f::invoke);Assert.assertTrue(f.dbWrites.isEmpty());
        }
        Fixture changed=new Fixture();JsonObject receipt=changed.data.importReceipt();
        receipt.addProperty("diagnostic","runtimeSchemaVersion=1; protocol=ISCSI; phase=VERIFY; code=RUNTIME_VERIFY_FAILED");changed.data.diagnostic(receipt);
        changed.data.status.addProperty("configurationSha256","b".repeat(64));Assert.assertThrows(CloudRuntimeException.class,changed::invoke);Assert.assertTrue(changed.dbWrites.isEmpty());
    }

}
