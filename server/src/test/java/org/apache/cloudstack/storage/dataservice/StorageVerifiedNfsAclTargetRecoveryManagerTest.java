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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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

public class StorageVerifiedNfsAclTargetRecoveryManagerTest {
    private static Object value(Object source,String name)throws Exception {
        Field field=source.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(source);
    }
    private static JsonObject object(String text) {return com.google.gson.JsonParser.parseString(text).getAsJsonObject();}
    private static class Fixture extends StorageServiceManagerImpl {
        final Object data;
        final StorageServiceOperationVO operation;
        final StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=
                Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao controls=
                Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class);
        final List<String> effects=new ArrayList<>();
        final JsonObject bindings,sourceFrozen,targetFrozen,status,lease,physical;
        String snapshot;
        boolean persist=true,failVerify,committed,failRestore,changeAfterIntent,driftAfterPhysical;
        int healthReads,failHealthAt,unknownHealthAt;
        JsonElement malformedHealthSuccess;
        long caller=2L;
        Fixture()throws Exception {
            Class<?> type=Class.forName(StorageVerifiedNfsAclTargetRecoveryTest.class.getName()+"$Fixture");
            Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);data=constructor.newInstance();
            operation=(StorageServiceOperationVO)value(data,"operation");
            bindings=(JsonObject)value(data,"bindings");sourceFrozen=(JsonObject)value(data,"sourceFrozen");
            targetFrozen=(JsonObject)value(data,"targetFrozen");status=(JsonObject)value(data,"status");
            snapshot=((JsonObject)value(data,"source")).toString();
            Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn(status.getAsJsonObject("generation").get("instanceUuid").getAsString());
            Mockito.when(instance.getVmId()).thenReturn(71L);
            var vms=Mockito.mock(com.cloud.vm.dao.VMInstanceDao.class);var vm=Mockito.mock(com.cloud.vm.VMInstanceVO.class);
            Mockito.when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Running);
            Mockito.when(vms.findById(71L)).thenReturn(vm);ReflectionTestUtils.setField(this,"vmInstanceDao",vms);
            ReflectionTestUtils.setField(this,"guestCommandDispatcher",(StorageServiceGuestCommandDispatcher)command->{
                if("operation writer-idle".equals(command.getOperation()))
                    return new StorageServiceGuestCommandResult(true,"","{\"success\":true,\"writerLockSupported\":true,\"status\":\"WRITER_IDLE\"}");
                Assert.assertEquals("operation verify",command.getOperation());healthReads++;effects.add("runtime");
                boolean success=healthReads!=failHealthAt;
                if(healthReads==unknownHealthAt)return new StorageServiceGuestCommandResult(true,"","{}");
                if(malformedHealthSuccess!=null) {
                    JsonObject invalid=new JsonObject();invalid.add("success",malformedHealthSuccess);invalid.addProperty("status","ok");
                    return new StorageServiceGuestCommandResult(true,"",invalid.toString());
                }
                return new StorageServiceGuestCommandResult(success,"",success?"{\"success\":true,\"status\":\"ok\"}":"{\"success\":false,\"status\":\"error\"}");
            });
            Mockito.when(operations.findById(99L)).thenReturn(operation);
            org.apache.cloudstack.storage.dataservice.StorageOperationHistoryFixtures.rows(operations,7L,List.of(operation));
            Mockito.when(operations.update(Mockito.eq(99L),Mockito.same(operation))).thenAnswer(call->{
                effects.add("intent");
                if(changeAfterIntent)bindings.addProperty("vmUuid","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
                return persist;
            });
            ReflectionTestUtils.setField(this,"storageOperationDao",operations);
            ReflectionTestUtils.setField(this,"storageOperationControlDao",controls);
            ReflectionTestUtils.setField(this,"storageTemplateUpgradeDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao.class));
            ReflectionTestUtils.setField(this,"storageRuntimeUpgradeDao",Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao.class));
            var accounts=Mockito.mock(com.cloud.user.AccountManager.class);
            Mockito.when(accounts.isRootAdmin(2L)).thenReturn(true);ReflectionTestUtils.setField(this,"storageAccountManager",accounts);
            var volumes=Mockito.mock(com.cloud.storage.dao.VolumeDao.class);
            var volume=Mockito.mock(com.cloud.storage.VolumeVO.class);
            String uuid=bindings.getAsJsonObject("dataVolumes").keySet().iterator().next();
            Mockito.when(volume.getUuid()).thenReturn(uuid);Mockito.when(volume.getInstanceId()).thenReturn(71L);
            Mockito.when(volume.getSize()).thenReturn(21474836480L);Mockito.when(volumes.findByUuid(uuid)).thenReturn(volume);
            ReflectionTestUtils.setField(this,"volumeDao",volumes);
            lease=object("{\"success\":true,\"reservationSupported\":true,\"logicalReservationOnly\":true,\"reservationAcquired\":false,\"expired\":false,\"scope\":null,\"requirements\":null,\"leaseBootId\":null}");
            lease.add("bootId",status.get("bootId").deepCopy());JsonObject observed=new JsonObject();
            observed.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);observed.addProperty("memoryAvailableBytes",1024);
            observed.addProperty("stagingFreeBytes",1024);observed.addProperty("loadPerCpu",0);lease.add("observed",observed);
            physical=new JsonObject();physical.addProperty("volumeUuid",uuid);physical.addProperty("sizeBytes",21474836480L);
            physical.addProperty("filesystemUuid","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        }
        @Override public Long getStorageServiceSyncId(org.apache.cloudstack.api.BaseCmd cmd){return 7L;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id){Assert.assertEquals(Long.valueOf(7),id);return instance;}
        @Override protected String captureConfigurationSnapshot(long id){Assert.assertEquals(7L,id);return snapshot;}
        @Override protected JsonObject operationResourceBindings(StorageServiceInstanceVO scope){return bindings.deepCopy();}
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO scope){ }
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO scope) {
            Assert.assertEquals(operation.getSnapshotJson(),snapshot);
        }
        @Override protected JsonObject completedVolumePreparationObservation(StorageServiceInstanceVO scope,com.cloud.storage.VolumeVO volume) {
            JsonObject result=new JsonObject();result.add("bootId",status.get("bootId").deepCopy());
            result.add("currentIdentity",physical.deepCopy());
            if(driftAfterPhysical)snapshot="{}";return result;
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO scope,String command,JsonObject request,int timeout) {
            Assert.assertSame(instance,scope);
            if("operation writer-idle".equals(command))
                return object("{\"success\":true,\"status\":\"WRITER_IDLE\",\"writerIdle\":true,\"writerLockSupported\":true,\"bootChildActive\":false,\"bootRecoveryRequired\":false}");
            if("operation reservation status".equals(command))return lease.deepCopy();
            Assert.assertEquals("operation generation frozen",command);
            return operation.getUuid().equals(request.get("operationUuid").getAsString())?targetFrozen.deepCopy():sourceFrozen.deepCopy();
        }
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO scope,StorageServiceOperationVO op,String action) {
            if("status".equals(action))return status.deepCopy();
            Assert.assertSame(operation,op);effects.add(action);
            if("verify".equals(action)) {
                targetFrozen.getAsJsonObject("generation").addProperty("verifiedAt",2000.75);
                if(failVerify)throw new CloudRuntimeException("Interrupted after Native verify");
            } else {
                Assert.assertEquals("commit",action);committed=true;
                status.add("generation",targetFrozen.get("generation").deepCopy());
                status.addProperty("runtimeRevision",operation.getRevision());status.add("pendingOperationUuid",JsonNull.INSTANCE);
                status.addProperty("generationStatus","IN_SYNC");
            }
            return object("{\"success\":true,\"generationSupported\":true}");
        }
        @Override protected boolean recoverOperationFinalization(StorageServiceInstanceVO scope,StorageServiceOperationVO op) {
            if(!committed)return false;
            StorageOperationReconciliation.requireTarget(instance.getUuid(),operation,snapshot,bindings,status);
            effects.add("finalize");operation.setState("COMPLETE");return true;
        }
        void restore(long id,String target) {
            Assert.assertEquals(7L,id);Assert.assertEquals(operation.getSnapshotJson(),target);effects.add("restore");
            if(failRestore)throw new CloudRuntimeException("Interrupted after durable intent");
            snapshot=target;
        }
        MockedConstruction<StorageServiceDesiredSnapshot> snapshots() {
            return Mockito.mockConstruction(StorageServiceDesiredSnapshot.class,(mock,context)->
                Mockito.doAnswer(call->{restore(call.getArgument(0),call.getArgument(1));return null;})
                    .when(mock).restore(Mockito.anyLong(),Mockito.anyString()));
        }
        void invoke() {
            var user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(caller);
            var account=Mockito.mock(com.cloud.user.Account.class);Mockito.when(account.getId()).thenReturn(2L);
            org.apache.cloudstack.context.CallContext.register(user,account);
            GlobalLock lock=Mockito.mock(GlobalLock.class);Mockito.when(lock.lock(120)).thenReturn(true);
            var command=Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.ReconcileStorageServiceOperationCmd.class);
            Mockito.when(command.getOperationId()).thenReturn(99L);
            try(MockedStatic<GlobalLock> locks=Mockito.mockStatic(GlobalLock.class);MockedConstruction<StorageServiceDesiredSnapshot> ignored=snapshots()) {
                locks.when(()->GlobalLock.getInternLock("StorageServiceWriter-7")).thenReturn(lock);
                reconcileStorageServiceOperation(command);
            } finally {org.apache.cloudstack.context.CallContext.unregister();}
        }
        void noEffects(){Assert.assertTrue(effects.toString(),effects.isEmpty());}
    }
    @Test public void explicitOriginalAdministratorUsesDurableIntentRestoreVerifyCommitAndUnchangedFinalGuard()throws Exception {
        Fixture f=new Fixture();f.invoke();
        Assert.assertEquals(List.of("intent","restore","runtime","verify","runtime","commit","finalize"),f.effects);
        Assert.assertEquals("COMPLETE",f.operation.getState());
        Assert.assertTrue(object(f.operation.getResultJson()).has("_verifiedPendingNfsAclTargetRecovery"));
        Assert.assertNull(((ThreadLocal<?>)ReflectionTestUtils.getField(f,"explicitVerifiedNfsAclRecovery")).get());
    }
    @Test public void intentPersistenceFailurePreventsRestoreAndAllNativeEffects()throws Exception {
        Fixture f=new Fixture();f.persist=false;Assert.assertThrows(CloudRuntimeException.class,f::invoke);
        Assert.assertEquals(List.of("intent"),f.effects);Assert.assertFalse(f.committed);
    }
    @Test public void interruptedRestoreResumesSameAuthorityWithoutSourceReplay()throws Exception {
        Fixture f=new Fixture();f.failRestore=true;Assert.assertThrows(CloudRuntimeException.class,f::invoke);
        JsonElement intent=object(f.operation.getResultJson()).get("_verifiedPendingNfsAclTargetRecovery").deepCopy();
        Assert.assertEquals(List.of("intent","restore"),f.effects);f.failRestore=false;f.effects.clear();f.invoke();
        Assert.assertEquals(List.of("restore","runtime","verify","runtime","commit","finalize"),f.effects);
        Assert.assertEquals(intent,object(f.operation.getResultJson()).get("_verifiedPendingNfsAclTargetRecovery"));
    }
    @Test public void crashAfterVerifyReusesStableTargetAuthorityAndDoesNotRestoreTargetAgain()throws Exception {
        Fixture f=new Fixture();f.failVerify=true;Assert.assertThrows(CloudRuntimeException.class,f::invoke);
        JsonElement intent=object(f.operation.getResultJson()).get("_verifiedPendingNfsAclTargetRecovery").deepCopy();
        Assert.assertEquals(List.of("intent","restore","runtime","verify"),f.effects);f.failVerify=false;f.effects.clear();f.invoke();
        Assert.assertEquals(List.of("runtime","verify","runtime","commit","finalize"),f.effects);
        Assert.assertEquals(intent,object(f.operation.getResultJson()).get("_verifiedPendingNfsAclTargetRecovery"));
    }
    @Test public void nonOriginalActorCancellationAndUnavailableControlFailBeforeIntent()throws Exception {
        Fixture f=new Fixture();f.caller=3;Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.noEffects();
        f=new Fixture();var control=Mockito.mock(StorageServiceOperationControlVO.class);
        Mockito.when(control.getInstanceId()).thenReturn(7L);Mockito.when(control.getOperationId()).thenReturn(99L);
        Mockito.when(control.isCancelRequested()).thenReturn(true);
        Mockito.when(f.controls.findByOperation(99L)).thenReturn(control);
        Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.noEffects();
        f=new Fixture();ReflectionTestUtils.setField(f,"storageOperationControlDao",null);
        Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.noEffects();
    }
    @Test public void foreignWriterAndRenderedIdentityRecoveryNeverGainTargetAuthority()throws Exception {
        Fixture f=new Fixture();StorageServiceOperationVO other=new StorageServiceOperationVO();other.setInstanceId(7);other.setState("RUNNING");
        org.apache.cloudstack.storage.dataservice.StorageOperationHistoryFixtures.rows(f.operations,7L,List.of(f.operation,other));
        Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.noEffects();
        f=new Fixture();JsonObject previous=object(f.operation.getPreviousSnapshotJson());previous.add("localCheckpointIntent",new JsonObject());
        f.operation.setPreviousSnapshotJson(previous.toString());Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.noEffects();
    }
    @Test public void leaseAndPhysicalIdentityOrCurrentSnapshotDriftRejectBeforePublication()throws Exception {
        Fixture f=new Fixture();f.lease.add("requirements",new JsonObject());Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.noEffects();
        f=new Fixture();f.physical.addProperty("filesystemUuid","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.noEffects();
        f=new Fixture();f.driftAfterPhysical=true;Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.noEffects();
    }
    @Test public void driftAfterIntentPreventsRestoreAndEveryNativeEffect()throws Exception {
        Fixture f=new Fixture();f.changeAfterIntent=true;Assert.assertThrows(CloudRuntimeException.class,f::invoke);
        Assert.assertEquals(List.of("intent"),f.effects);
    }
    @Test public void failedLiveRuntimeBeforeVerifyOrCommitPreservesIntentAndNeverCommits()throws Exception {
        Fixture first=new Fixture();first.failHealthAt=1;Assert.assertThrows(CloudRuntimeException.class,first::invoke);
        Assert.assertEquals(List.of("intent","restore","runtime"),first.effects);
        Assert.assertFalse(first.committed);Assert.assertEquals("RECOVERY_REQUIRED",first.operation.getState());
        Fixture second=new Fixture();second.failHealthAt=2;Assert.assertThrows(CloudRuntimeException.class,second::invoke);
        Assert.assertEquals(List.of("intent","restore","runtime","verify","runtime"),second.effects);
        Assert.assertFalse(second.committed);Assert.assertTrue(object(second.operation.getResultJson()).has("_verifiedPendingNfsAclTargetRecovery"));
        for(int stage:List.of(1,2)) {
            Fixture unknown=new Fixture();unknown.unknownHealthAt=stage;
            Assert.assertThrows(CloudRuntimeException.class,unknown::invoke);
            Assert.assertFalse(unknown.committed);
            Assert.assertEquals("RECOVERY_REQUIRED",unknown.operation.getState());
            Assert.assertEquals(unknown.operation.getSnapshotJson(),unknown.snapshot);
            Assert.assertTrue(object(unknown.operation.getResultJson()).has("_verifiedPendingNfsAclTargetRecovery"));
        }
    }
    @Test public void nativeRuntimeHealthSuccessMustBeTypedBoolean()throws Exception {
        for(JsonElement bad:List.of(new com.google.gson.JsonPrimitive("true"),new com.google.gson.JsonPrimitive(1),JsonNull.INSTANCE)) {
            Fixture f=new Fixture();f.malformedHealthSuccess=bad;
            Assert.assertThrows(CloudRuntimeException.class,f::invoke);
            Assert.assertEquals(List.of("intent","restore","runtime"),f.effects);Assert.assertFalse(f.committed);
            Assert.assertEquals("RECOVERY_REQUIRED",f.operation.getState());
        }
    }
    @Test public void backgroundInvocationCannotConsumeExplicitTargetIntent()throws Exception {
        Fixture f=new Fixture();f.failRestore=true;Assert.assertThrows(CloudRuntimeException.class,f::invoke);f.effects.clear();
        Assert.assertThrows(CloudRuntimeException.class,()->f.recoverInterruptedStorageWriter(f.instance,f.operation));f.noEffects();
    }
}
