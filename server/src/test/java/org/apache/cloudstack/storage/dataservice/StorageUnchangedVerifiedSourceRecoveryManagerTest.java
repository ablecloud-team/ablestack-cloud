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
import java.util.List;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageUnchangedVerifiedSourceRecoveryManagerTest {
    private static class Fixture extends StorageServiceManagerImpl {
        final StorageUnchangedVerifiedSourceRecoveryTest.Fixture data=new StorageUnchangedVerifiedSourceRecoveryTest.Fixture();
        final StorageServiceOperationVO operation=data.original,source=data.source;
        final StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=
                Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao controls=
                Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class);
        final List<String> effects=new ArrayList<>();
        final JsonObject lease,physical=new JsonObject();
        long caller=2;
        int healthReads,failHealthAt,unknownHealthAt;
        boolean persist=true,driftAfterIntent,driftAfterPhysical,idle=true;
        JsonElement weakHealthSuccess;
        Fixture()throws Exception {
            Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn(data.instance());
            Mockito.when(instance.getVmId()).thenReturn(71L);
            var vms=Mockito.mock(com.cloud.vm.dao.VMInstanceDao.class);var vm=Mockito.mock(com.cloud.vm.VMInstanceVO.class);
            Mockito.when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Running);Mockito.when(vms.findById(71L)).thenReturn(vm);
            ReflectionTestUtils.setField(this,"vmInstanceDao",vms);
            ReflectionTestUtils.setField(this,"storageOperationDao",operations);
            ReflectionTestUtils.setField(this,"storageOperationControlDao",controls);
            ReflectionTestUtils.setField(this,"storageTemplateUpgradeDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao.class));
            ReflectionTestUtils.setField(this,"storageRuntimeUpgradeDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao.class));
            var accounts=Mockito.mock(com.cloud.user.AccountManager.class);Mockito.when(accounts.isRootAdmin(2L)).thenReturn(true);
            ReflectionTestUtils.setField(this,"storageAccountManager",accounts);
            Mockito.when(operations.findById(operation.getId())).thenReturn(operation);Mockito.when(operations.findById(source.getId())).thenReturn(source);
            org.apache.cloudstack.storage.dataservice.StorageOperationHistoryFixtures.rows(operations,7L,List.of(operation,source));
            Mockito.when(operations.update(Mockito.eq(operation.getId()),Mockito.same(operation))).thenAnswer(call->{
                effects.add("ROLLED_BACK".equals(operation.getState())?"finalize":"intent");
                if(driftAfterIntent)data.status.addProperty("bootId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
                return persist;
            });
            var volumes=Mockito.mock(com.cloud.storage.dao.VolumeDao.class);var volume=Mockito.mock(com.cloud.storage.VolumeVO.class);
            String uuid=data.bindings.getAsJsonObject("dataVolumes").keySet().iterator().next();
            Mockito.when(volume.getUuid()).thenReturn(uuid);Mockito.when(volume.getInstanceId()).thenReturn(71L);Mockito.when(volume.getSize()).thenReturn(21474836480L);
            Mockito.when(volumes.findByUuid(uuid)).thenReturn(volume);ReflectionTestUtils.setField(this,"volumeDao",volumes);
            lease=StorageUnchangedVerifiedSourceRecoveryTest.object("{\"success\":true,\"reservationSupported\":true,\"logicalReservationOnly\":true,\"reservationAcquired\":false,\"expired\":false,\"scope\":null,\"requirements\":null,\"leaseBootId\":null}");
            lease.add("bootId",data.status.get("bootId").deepCopy());JsonObject observed=new JsonObject();
            observed.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);observed.addProperty("memoryAvailableBytes",1024);
            observed.addProperty("stagingFreeBytes",1024);observed.addProperty("loadPerCpu",0);lease.add("observed",observed);
            physical.addProperty("volumeUuid",uuid);physical.addProperty("filesystemUuid","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            physical.addProperty("sizeBytes",21474836480L);
            ReflectionTestUtils.setField(this,"guestCommandDispatcher",(StorageServiceGuestCommandDispatcher)command->{
                if("operation writer-idle".equals(command.getOperation()))return new StorageServiceGuestCommandResult(true,"","{\"success\":true,\"writerLockSupported\":true,\"status\":\"WRITER_IDLE\"}");
                Assert.assertEquals("operation verify",command.getOperation());effects.add("runtime");healthReads++;
                if(healthReads==unknownHealthAt)return new StorageServiceGuestCommandResult(true,"","{}");
                JsonObject reply=new JsonObject();reply.addProperty("success",healthReads!=failHealthAt);reply.addProperty("status",healthReads==failHealthAt?"error":"ok");
                if(weakHealthSuccess!=null)reply.add("success",weakHealthSuccess);
                return new StorageServiceGuestCommandResult(true,"",reply.toString());
            });
        }
        @Override public Long getStorageServiceSyncId(org.apache.cloudstack.api.BaseCmd cmd){return 7L;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
        @Override protected String captureConfigurationSnapshot(long id){Assert.assertEquals(7L,id);return data.snapshot.toString();}
        @Override protected JsonObject operationResourceBindings(StorageServiceInstanceVO scope){return data.bindings.deepCopy();}
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO scope){ }
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO scope){
            Assert.assertTrue(StorageUnchangedVerifiedSourceRecovery.same(data.snapshot,StorageUnchangedVerifiedSourceRecoveryTest.object(source.getSnapshotJson())));
        }
        @Override protected JsonObject completedVolumePreparationObservation(StorageServiceInstanceVO scope,com.cloud.storage.VolumeVO volume){
            JsonObject result=new JsonObject();result.add("bootId",data.status.get("bootId").deepCopy());result.add("currentIdentity",physical.deepCopy());
            if(driftAfterPhysical)data.snapshot.addProperty("schemaVersion",2.0);return result;
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO scope,String action,JsonObject request,int timeout){
            if("operation writer-idle".equals(action)){
                JsonObject v=StorageUnchangedVerifiedSourceRecoveryTest.object("{\"success\":true,\"status\":\"WRITER_IDLE\",\"writerIdle\":true,\"writerLockSupported\":true,\"bootChildActive\":false,\"bootRecoveryRequired\":false}");
                v.addProperty("writerIdle",idle);return v;
            }
            if("operation reservation status".equals(action))return lease.deepCopy();
            Assert.assertEquals("operation generation frozen",action);Assert.assertTrue(StorageUnchangedVerifiedSourceRecovery.same(request,data.status.get("generation")));
            return data.frozen.deepCopy();
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO scope,StorageServiceOperationVO op,String action){
            Assert.assertNull(op);Assert.assertEquals("status",action);return data.status.deepCopy();
        }
        @Override protected boolean recoverOperationFinalization(StorageServiceInstanceVO scope,StorageServiceOperationVO op){return false;}
        void invoke(){
            var user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(caller);
            var account=Mockito.mock(com.cloud.user.Account.class);Mockito.when(account.getId()).thenReturn(2L);
            org.apache.cloudstack.context.CallContext.register(user,account);
            GlobalLock lock=Mockito.mock(GlobalLock.class);Mockito.when(lock.lock(120)).thenReturn(true);
            var cmd=Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class);Mockito.when(cmd.getOperationId()).thenReturn(operation.getId());
            try(MockedStatic<GlobalLock> locks=Mockito.mockStatic(GlobalLock.class);
                    MockedConstruction<StorageServiceDesiredSnapshot> snapshots=Mockito.mockConstruction(StorageServiceDesiredSnapshot.class,(mock,context)->
                            Mockito.doAnswer(call->{effects.add("FORBIDDEN_SOURCE_RESTORE");throw new AssertionError("SOURCE restore/replay is forbidden");})
                                    .when(mock).restore(Mockito.anyLong(),Mockito.anyString()))){
                locks.when(()->GlobalLock.getInternLock("StorageServiceWriter-7")).thenReturn(lock);reconcileStorageServiceOperation(cmd);
            }finally{org.apache.cloudstack.context.CallContext.unregister();}
        }
    }

    @Test public void explicitOriginalAdminConfirmsCurrentSourceWithoutDatabaseRestoreProtocolReplayOrNativeMutation()throws Exception {
        Fixture f=new Fixture();String priorSource=f.source.getResultJson(),previous=f.operation.getPreviousSnapshotJson();
        f.invoke();Assert.assertEquals(List.of("runtime","intent","runtime","finalize"),f.effects);
        Assert.assertEquals("ROLLED_BACK",f.operation.getState());Assert.assertTrue(f.operation.getDiagnostic().startsWith("Original generation operation scope changed"));
        Assert.assertTrue(f.operation.getDiagnostic().contains("no protocol replay"));Assert.assertEquals(previous,f.operation.getPreviousSnapshotJson());
        Assert.assertEquals(priorSource,f.source.getResultJson());Assert.assertEquals("COMPLETE",f.source.getState());Assert.assertNull(f.operation.getSnapshotJson());
        JsonObject result=StorageUnchangedVerifiedSourceRecoveryTest.object(f.operation.getResultJson());
        Assert.assertTrue(result.has(StorageUnchangedVerifiedSourceRecovery.RESULT_KEY));Assert.assertFalse(result.has("_targetNativeBootId"));
        Assert.assertNull(((ThreadLocal<?>)ReflectionTestUtils.getField(f,"explicitUnchangedSourceRecovery")).get());
    }

    @Test public void otherActorMissingWitnessUnavailableGuardsAndAnyLaterWriterNeverFallBackToReplay()throws Exception {
        Fixture actor=new Fixture();actor.caller=3;Assert.assertThrows(CloudRuntimeException.class,actor::invoke);Assert.assertTrue(actor.effects.isEmpty());
        Fixture witness=new Fixture();JsonObject result=StorageUnchangedVerifiedSourceRecoveryTest.object(witness.source.getResultJson());result.remove("_targetNativeBootId");
        witness.source.setResultJson(result.toString());Assert.assertThrows(CloudRuntimeException.class,witness::invoke);Assert.assertTrue(witness.effects.isEmpty());
        Fixture unavailable=new Fixture();ReflectionTestUtils.setField(unavailable,"storageOperationControlDao",null);
        Assert.assertThrows(CloudRuntimeException.class,unavailable::invoke);Assert.assertTrue(unavailable.effects.isEmpty());
        for(String state:List.of("RUNNING","RECOVERY_REQUIRED","COMPLETE")){
            Fixture f=new Fixture();StorageServiceOperationVO other=new StorageServiceOperationVO();other.setInstanceId(7);other.setRevision(f.operation.getRevision()+1);other.setState(state);
            org.apache.cloudstack.storage.dataservice.StorageOperationHistoryFixtures.rows(f.operations,7L,List.of(f.operation,f.source,other));
            Assert.assertThrows(CloudRuntimeException.class,f::invoke);Assert.assertTrue(f.effects.isEmpty());
        }
    }

    @Test public void pendingBootLeasePhysicalIdentityAndSnapshotDriftFailBeforeIntent()throws Exception {
        for(int mode=0;mode<6;mode++){
            Fixture f=new Fixture();
            if(mode==0)f.data.status.addProperty("pendingOperationUuid",f.operation.getUuid());
            if(mode==1)f.data.status.addProperty("bootId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
            if(mode==2)f.lease.add("requirements",new JsonObject());
            if(mode==3)f.physical.addProperty("filesystemUuid","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
            if(mode==4)f.driftAfterPhysical=true;
            if(mode==5)f.idle=false;
            Assert.assertThrows(CloudRuntimeException.class,f::invoke);Assert.assertTrue(f.effects.isEmpty());
            Assert.assertEquals("RECOVERY_REQUIRED",f.operation.getState());
        }
    }

    @Test public void runtimeFalseUnknownAndWeakBooleanNeverFinalizeAndPreserveAnyExistingIntent()throws Exception {
        for(int at:List.of(1,2)){
            Fixture f=new Fixture();f.failHealthAt=at;Assert.assertThrows(CloudRuntimeException.class,f::invoke);
            Assert.assertEquals("RECOVERY_REQUIRED",f.operation.getState());Assert.assertFalse(f.effects.contains("finalize"));
            Fixture unknown=new Fixture();unknown.unknownHealthAt=at;Assert.assertThrows(CloudRuntimeException.class,unknown::invoke);
            Assert.assertFalse(unknown.effects.contains("finalize"));
        }
        for(JsonElement value:List.of(JsonNull.INSTANCE,new com.google.gson.JsonPrimitive("true"),new com.google.gson.JsonPrimitive(1))){
            Fixture f=new Fixture();f.weakHealthSuccess=value;Assert.assertThrows(CloudRuntimeException.class,f::invoke);
            Assert.assertEquals(List.of("runtime"),f.effects);
        }
    }

    @Test public void intentFailureAndPostIntentDriftCannotFinalizeOrFallBack()throws Exception {
        Fixture persist=new Fixture();persist.persist=false;Assert.assertThrows(CloudRuntimeException.class,persist::invoke);
        Assert.assertEquals(List.of("runtime","intent"),persist.effects);Assert.assertEquals("RECOVERY_REQUIRED",persist.operation.getState());
        Fixture drift=new Fixture();drift.driftAfterIntent=true;Assert.assertThrows(CloudRuntimeException.class,drift::invoke);
        Assert.assertEquals(List.of("runtime","intent","runtime"),drift.effects);Assert.assertEquals("RECOVERY_REQUIRED",drift.operation.getState());
    }

    @Test public void explicitRetryRevalidatesSameIntentAndBackgroundCanNeverReplayIt()throws Exception {
        Fixture f=new Fixture();f.failHealthAt=2;Assert.assertThrows(CloudRuntimeException.class,f::invoke);
        JsonElement intent=StorageUnchangedVerifiedSourceRecoveryTest.object(f.operation.getResultJson()).get(StorageUnchangedVerifiedSourceRecovery.RESULT_KEY).deepCopy();
        f.effects.clear();Assert.assertThrows(CloudRuntimeException.class,()->f.recoverInterruptedStorageWriter(f.instance,f.operation));
        Assert.assertTrue(f.effects.isEmpty());f.failHealthAt=0;f.invoke();
        Assert.assertEquals(List.of("runtime","runtime","finalize"),f.effects);
        Assert.assertEquals(intent,StorageUnchangedVerifiedSourceRecoveryTest.object(f.operation.getResultJson()).get(StorageUnchangedVerifiedSourceRecovery.RESULT_KEY));
    }
}
