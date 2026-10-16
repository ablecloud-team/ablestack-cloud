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
import java.util.Map;
import java.util.UUID;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageVolumePreparationResumeTest {
    private static final class Manager extends StorageServiceManagerImpl {
        VolumeVO volume;StorageServiceInstanceVO instance;JsonObject bindings=new JsonObject();
        @Override protected VolumeVO requireVolume(Long id){return volume;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
        @Override protected JsonObject volumePreparationResourceBindings(StorageServiceInstanceVO value){return bindings.deepCopy();}
    }
    private Manager manager;private StorageServiceInstanceVO instance;private VolumeVO volume;
    private StorageServiceOperationVO operation,original,committed;
    private StorageServiceOperationDao operations;private StorageServiceOperationControlDao controls;
    private JsonObject history,current,generation,idle,reservation;
    private StorageServiceOperationControlVO control;
    private final List<String> commands=new ArrayList<>();
    private final List<StorageServiceOperationVO> rows=new ArrayList<>();
    private int mutations;private boolean wrongFilesystem,noFormat=true,partial,wrongResumeOwner,changedAfter,foreignPending,mountedExisting;
    private JsonObject dispatchedResume;
    private String boot="33333333-3333-4333-8333-333333333333";
    @Before public void setup() {
        commands.clear();rows.clear();mutations=0;wrongFilesystem=false;noFormat=true;partial=false;wrongResumeOwner=false;changedAfter=false;foreignPending=false;mountedExisting=false;dispatchedResume=null;
        manager=new Manager();volume=Mockito.mock(VolumeVO.class);manager.volume=volume;
        Mockito.when(volume.getId()).thenReturn(3L);Mockito.when(volume.getUuid()).thenReturn(UUID.randomUUID().toString());
        Mockito.when(volume.getVolumeType()).thenReturn(com.cloud.storage.Volume.Type.DATADISK);
        Mockito.when(volume.getState()).thenReturn(com.cloud.storage.Volume.State.Ready);Mockito.when(volume.getInstanceId()).thenReturn(54L);
        Mockito.when(volume.getAccountId()).thenReturn(2L);Mockito.when(volume.getDataCenterId()).thenReturn(1L);
        Mockito.when(volume.getSize()).thenReturn(1024L);Mockito.when(volume.getProvisioningType()).thenReturn(ProvisioningType.SPARSE);
        instance=Mockito.mock(StorageServiceInstanceVO.class);manager.instance=instance;
        Mockito.when(instance.getId()).thenReturn(9L);Mockito.when(instance.getVmId()).thenReturn(54L);
        Mockito.when(instance.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
        Mockito.when(instance.getAccountId()).thenReturn(2L);Mockito.when(instance.getDataCenterId()).thenReturn(1L);
        manager.bindings.addProperty("vmId",54);manager.bindings.addProperty("volumeUuid",volume.getUuid());
        original=new StorageServiceOperationVO();ReflectionTestUtils.setField(original,"id",11L);
        original.setInstanceId(9);original.setCreatedBy(2);original.setRevision(8);original.setAction("createstoragenfsexportresponse");
        original.setState("RECOVERY_REQUIRED");original.setPhase("RECOVERY_REQUIRED");
        committed=new StorageServiceOperationVO();committed.setInstanceId(9);committed.setRevision(7);committed.setState("COMPLETE");rows.add(committed);
        generation=new JsonObject();generation.addProperty("instanceUuid",instance.getUuid());generation.addProperty("operationUuid",committed.getUuid());
        generation.addProperty("revision",7);generation.addProperty("configurationSha256","a".repeat(64));
        JsonObject checkpoint=new JsonObject();checkpoint.add("previous",generation.deepCopy());JsonObject source=new JsonObject();source.add("nativeGeneration",checkpoint);
        source.addProperty("schemaVersion",2);source.addProperty("instanceId",9);JsonArray tables=new JsonArray();
        for(String name:new String[]{"storage_service_protocol","storage_file_share","storage_block_target","storage_identity_domain","storage_access_rule","storage_posix_directory_policy"}){
            JsonObject table=new JsonObject();table.addProperty("table",name);table.add("columns",new JsonArray());table.add("rows",new JsonArray());tables.add(table);
        }
        source.add("tables",tables);original.setPreviousSnapshotJson(source.toString());
        history=StorageVolumePreparationProofTest.history();current=StorageVolumePreparationProofTest.current();
        history.addProperty("status","FILESYSTEM_VERIFIED");JsonObject journal=history.getAsJsonObject("operation"),receipt=journal.getAsJsonObject("formatterSuccessReceipt");
        journal.addProperty("volumeUuid",volume.getUuid());receipt.addProperty("volumeUuid",volume.getUuid());current.addProperty("volumeUuid",volume.getUuid());
        JsonObject owner=journal.getAsJsonObject("originalFormatOwner");owner.addProperty("managerOperationUuid",original.getUuid());receipt.add("originalFormatOwner",owner.deepCopy());
        journal.addProperty("operationId","volume-"+volume.getUuid());history.add("operationId",journal.get("operationId"));
        operation=new StorageServiceOperationVO();ReflectionTestUtils.setField(operation,"id",12L);
        operation.setInstanceId(9);operation.setCreatedBy(2);operation.setAction("VOLUME_PREPARATION_RESUME");operation.setRevision(8);operation.setState("RECOVERY_REQUIRED");
        JsonObject scope=new JsonObject();scope.addProperty("volumeId",3);scope.addProperty("volumeUuid",volume.getUuid());scope.addProperty("volumeSizeBytes",1024);
        scope.addProperty("provisioningType","SPARSE");scope.addProperty("operationId","volume-"+volume.getUuid());scope.addProperty("expectedFilesystemUuid","same-filesystem");
        scope.addProperty("vmId",54);scope.add("resourceBindings",manager.bindings.deepCopy());scope.add("originalFormatOwner",owner.deepCopy());
        operation.setPreviousSnapshotJson(scope.toString());operation.setResultJson("{\"baseDesiredRevision\":7,\"desiredStateChanged\":false}");
        rows.add(operation);
        operations=Mockito.mock(StorageServiceOperationDao.class);controls=Mockito.mock(StorageServiceOperationControlDao.class);
        Mockito.when(operations.listByInstance(9L)).thenAnswer(call->rows);
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        ReflectionTestUtils.setField(manager,"storageOperationDao",operations);ReflectionTestUtils.setField(manager,"storageOperationControlDao",controls);
        ReflectionTestUtils.setField(manager,"storageAccountManager",Mockito.mock(com.cloud.user.AccountManager.class));
        ReflectionTestUtils.setField(manager,"storageTemplateUpgradeDao",Mockito.mock(StorageServiceTemplateUpgradeDao.class));
        ReflectionTestUtils.setField(manager,"storageRuntimeUpgradeDao",Mockito.mock(StorageServiceRuntimeUpgradeDao.class));
        ReflectionTestUtils.setField(manager,"configurationVolumeDetailsDao",Mockito.mock(com.cloud.storage.dao.VolumeDetailsDao.class));
        idle=new JsonObject();idle.addProperty("success",true);idle.addProperty("status","WRITER_IDLE");idle.addProperty("writerIdle",true);
        idle.addProperty("writerLockSupported",true);idle.addProperty("bootChildActive",false);idle.addProperty("bootRecoveryRequired",false);
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{
            StorageServiceGuestCommand command=call.getArgument(0);commands.add(command.getOperation());
            JsonObject request=JsonParser.parseString(command.getPayload().isEmpty()?"{}":command.getPayload()).getAsJsonObject(),reply;
            Assert.assertEquals(54L,command.getVmId());
            if("operation writer-idle".equals(command.getOperation()))reply=idle.deepCopy();
            else if("volume operation status".equals(command.getOperation())){
                reply=history.deepCopy();if(partial)reply.getAsJsonObject("operation").remove("formatterSuccessReceipt");
            }else if("operation root-data inspect".equals(command.getOperation())){
                reply=new JsonObject();JsonArray disks=request.getAsJsonArray("volumes").deepCopy();
                for(JsonElement disk:disks)for(Map.Entry<String,JsonElement> field:current.entrySet())disk.getAsJsonObject().add(field.getKey(),field.getValue());
                if(changedAfter&&mutations>0)disks.get(0).getAsJsonObject().addProperty("serial","changed");
                reply.add("volumes",disks);reply.addProperty("bootId",boot);reply.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);reply.addProperty("success",true);
            }else if("operation generation status".equals(command.getOperation())){
                reply=new JsonObject();reply.addProperty("success",true);reply.addProperty("generationSupported",true);reply.addProperty("bootId",boot);
                reply.add("generation",generation.deepCopy());
                if(foreignPending)reply.addProperty("pendingOperationUuid",UUID.randomUUID().toString());
                else if(rows.contains(original)&&JsonParser.parseString(original.getPreviousSnapshotJson()).getAsJsonObject().has("nativeGeneration"))reply.addProperty("pendingOperationUuid",original.getUuid());
                else reply.add("pendingOperationUuid",JsonNull.INSTANCE);
            }else if("operation reservation status".equals(command.getOperation())){
                Assert.assertEquals(rows.contains(original)?original.getUuid():operation.getUuid(),request.get("operationUuid").getAsString());reply=reservation.deepCopy();
            }else{
                Assert.assertEquals("volume operation resume",command.getOperation());Assert.assertEquals("MOUNT_EXISTING",request.get("importMode").getAsString());
                dispatchedResume=request.deepCopy();
                if(mountedExisting&&(!request.has("mountPath")
                        ||!("/srv/ablestack-storage/volumes/"+volume.getUuid()).equals(request.get("mountPath").getAsString())))
                    throw new CloudRuntimeException("Mounted-volume identity resolution requires the canonical mount root");
                Assert.assertTrue(request.get("resumeOnly").getAsBoolean());Assert.assertFalse(request.has("devicePath"));
                Assert.assertEquals(boot,request.get("expectedBootId").getAsString());Assert.assertEquals(owner,request.get("originalFormatOwner"));mutations++;
                reply=current.deepCopy();reply.addProperty("success",true);reply.addProperty("resumed",true);reply.addProperty("resumeOnly",true);
                reply.addProperty("formatInvoked",!noFormat);reply.addProperty("formatterActive",false);reply.addProperty("formatterObservationComplete",true);reply.addProperty("terminationPending",false);
                reply.add("instanceUuid",request.get("instanceUuid"));reply.add("managerOperationUuid",request.get("managerOperationUuid"));reply.add("revision",request.get("revision"));
                reply.add("operationId",request.get("operationId"));reply.addProperty("filesystemUuid",wrongFilesystem?"foreign":"same-filesystem");
                JsonObject resumed=new JsonObject();resumed.addProperty("schemaVersion",1);resumed.addProperty("instanceUuid",instance.getUuid());
                resumed.addProperty("managerOperationUuid",wrongResumeOwner?original.getUuid():operation.getUuid());resumed.addProperty("revision",operation.getRevision());
                resumed.add("originalFormatOwner",owner.deepCopy());resumed.addProperty("volumeUuid",volume.getUuid());resumed.add("operationId",request.get("operationId"));
                resumed.addProperty("mountPath","/srv/ablestack-storage/volumes/"+volume.getUuid());resumed.addProperty("bootId",boot);resumed.addProperty("formatInvoked",false);
                for(String field:new String[]{"serial","sizeBytes","filesystemUuid","filesystem"})resumed.add(field,reply.get(field));
                resumed.addProperty("completedEpoch",System.currentTimeMillis()/1000.0);
                JsonObject next=journal.deepCopy();next.addProperty("phase","COMPLETE");next.add("resumeReceipt",resumed);reply.add("operation",next);
            }
            return new StorageServiceGuestCommandResult(true,"public",reply.toString());
        });
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
        reservation=new JsonObject();reservation.addProperty("success",true);reservation.addProperty("reservationSupported",true);reservation.addProperty("logicalReservationOnly",true);
        reservation.addProperty("bootId",boot);JsonObject observed=new JsonObject();observed.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
        observed.addProperty("memoryAvailableBytes",1L<<30);observed.addProperty("stagingFreeBytes",1L<<30);observed.addProperty("loadPerCpu",0.1);reservation.add("observed",observed);absentLease();
    }
    private void parked(boolean expired) {
        rows.add(original);control=new StorageServiceOperationControlVO();control.setInstanceId(9);control.setOperationId(11);
        JsonObject requirements=new JsonObject();requirements.addProperty("minimumMemoryAvailableBytes",268435456);
        requirements.addProperty("stagingRequiredBytes",104857600);requirements.addProperty("maxLoadPerCpu",1.5);requirements.addProperty("requireSessionDrain",false);
        JsonObject policy=new JsonObject();policy.add("requirements",requirements);control.setPolicyJson(policy.toString());
        Mockito.when(controls.findByOperation(11L)).thenReturn(control);
        reservation=new JsonObject();reservation.addProperty("success",true);reservation.addProperty("reservationSupported",true);reservation.addProperty("logicalReservationOnly",true);
        reservation.addProperty("reservationAcquired",!expired);reservation.addProperty("expired",expired);
        reservation.add("scope",manager.operationReservationScope(instance,original));reservation.add("requirements",requirements.deepCopy());
        reservation.addProperty("leaseBootId","33333333-3333-4333-8333-333333333333");reservation.addProperty("bootId",boot);
        reservation.addProperty("leaseExpiresAt",System.currentTimeMillis()+(expired?-10000:300000));
        JsonObject observed=new JsonObject();observed.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
        observed.addProperty("memoryAvailableBytes",1L<<30);observed.addProperty("stagingFreeBytes",1L<<30);observed.addProperty("loadPerCpu",0.1);reservation.add("observed",observed);
    }
    @Test public void interruptedPreparationResumesForwardWithoutDesiredCommitThroughRealDispatcher() {
        manager.recoverVolumePreparation(instance,operation);Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());
        Assert.assertEquals(8,operation.getRevision());Assert.assertEquals(1,mutations);Assert.assertNull(operation.getSnapshotJson());
        Assert.assertEquals(7,manager.rootDesiredRevision(9));Assert.assertFalse(commands.stream().anyMatch(c->c.contains("generation commit")||c.contains("attach inspect")));
    }
    @Test public void mountedExistingExt4ResumeUsesCanonicalRootAndPreservesOriginalLease() {
        for(boolean expired:new boolean[]{false,true}) {
            setup();parked(expired);mountedExisting=true;
            String root="/srv/ablestack-storage/volumes/"+volume.getUuid();
            JsonObject journal=history.getAsJsonObject("operation"),receipt=journal.getAsJsonObject("formatterSuccessReceipt");
            journal.addProperty("phase","PERSISTING_MOUNT");journal.addProperty("mountPath",root);
            journal.addProperty("filesystem","ext4");receipt.addProperty("filesystem","ext4");current.addProperty("filesystem","ext4");
            JsonArray options=new JsonArray();options.add("-F");options.add("-E");options.add("nodiscard,lazy_itable_init=1,lazy_journal_init=1");
            journal.add("formatterOptions",options);receipt.add("formatterOptions",options.deepCopy());
            JsonObject scope=JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject();
            scope.addProperty("mountPath","/caller-supplied-path-is-not-authority");operation.setPreviousSnapshotJson(scope.toString());
            String lease=reservation.toString(),originalSource=original.getPreviousSnapshotJson();
            manager.recoverVolumePreparation(instance,operation);
            Assert.assertEquals(root,dispatchedResume.get("mountPath").getAsString());
            Assert.assertEquals(journal.get("originalFormatOwner"),dispatchedResume.get("originalFormatOwner"));
            Assert.assertEquals("MOUNT_EXISTING",dispatchedResume.get("importMode").getAsString());
            Assert.assertTrue(dispatchedResume.get("resumeOnly").getAsBoolean());
            Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());Assert.assertEquals(7,manager.rootDesiredRevision(9));
            Assert.assertEquals("RECOVERY_REQUIRED",original.getState());Assert.assertEquals(originalSource,original.getPreviousSnapshotJson());
            Assert.assertEquals(lease,reservation.toString());Assert.assertEquals(1,mutations);
            Assert.assertFalse(JsonParser.parseString(operation.getResultJson()).getAsJsonObject().getAsJsonObject("native").get("formatInvoked").getAsBoolean());
            Assert.assertFalse(commands.stream().anyMatch(c->c.contains("attach inspect")||c.endsWith("acquire")||c.endsWith("renew")||c.endsWith("release")||c.endsWith("commit")));
            Mockito.verify(operations,Mockito.never()).update(Mockito.eq(11L),Mockito.any());
        }
    }
    @Test public void activeFormatterAndChangedPinnedSizeAreRejectedBeforeMutation() {
        idle.addProperty("writerIdle",false);Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));
        idle.addProperty("writerIdle",true);Mockito.when(volume.getSize()).thenReturn(2048L);
        Assert.assertThrows(InvalidParameterValueException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
    }
    @Test public void filesystemMismatchNoFormatEvidenceOrForeignResumeReceiptKeepsRecoveryRequired() {
        wrongFilesystem=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));
        wrongFilesystem=false;noFormat=false;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));
        noFormat=true;wrongResumeOwner=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));
        Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());
    }
    @Test public void failedDurableIntentCannotDispatchNativeMutation() {
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(false);
        Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
    }
    @Test public void partialHeaderWithoutSuccessfulFormatterProofNeverDispatchesMount() {
        partial=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
    }
    @Test public void uniqueParkedOriginalAndItsActiveOrExpiredLeaseRemainUntouched() {
        for(boolean expired:new boolean[]{false,true}){
            setup();parked(expired);String source=original.getPreviousSnapshotJson(),policy=control.getPolicyJson();
            manager.recoverVolumePreparation(instance,operation);Assert.assertEquals(1,mutations);
            Assert.assertEquals("RECOVERY_REQUIRED",original.getState());Assert.assertEquals(source,original.getPreviousSnapshotJson());Assert.assertEquals(policy,control.getPolicyJson());
            Assert.assertFalse(commands.stream().anyMatch(c->c.endsWith("acquire")||c.endsWith("renew")||c.endsWith("release")||c.endsWith("commit")));
            Mockito.verify(operations,Mockito.never()).update(Mockito.eq(11L),Mockito.any());
        }
    }
    @Test public void foreignMultipleUnboundAndActiveOriginalCannotAuthorizeAuxiliaryMount() {
        for(int mode=0;mode<5;mode++){
            setup();parked(false);
            if(mode==0)original.setState("RUNNING");
            if(mode==1)original.setCreatedBy(99);
            if(mode==2){StorageServiceOperationVO other=new StorageServiceOperationVO();other.setState("RECOVERY_REQUIRED");other.setRevision(9);rows.add(other);}
            if(mode==3)history.getAsJsonObject("operation").remove("originalFormatOwner");
            if(mode==4)generation.addProperty("operationUuid",original.getUuid());
            Assert.assertThrows(RuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
        }
    }
    @Test public void foreignMissingMalformedOrChangedLeaseNeverEnablesAuxiliaryMount() {
        for(int mode=0;mode<7;mode++){
            setup();parked(false);
            if(mode==0)reservation.getAsJsonObject("scope").addProperty("operationUuid",UUID.randomUUID().toString());
            if(mode==1)reservation.remove("requirements");
            if(mode==2)reservation.getAsJsonObject("requirements").addProperty("maxLoadPerCpu","1.5");
            if(mode==3)reservation.addProperty("bootId",UUID.randomUUID().toString());
            if(mode==4)reservation.addProperty("leaseBootId",UUID.randomUUID().toString());
            if(mode==5)reservation.addProperty("reservationAcquired","true");
            if(mode==6)reservation.addProperty("leaseExpiresAt",new java.math.BigInteger("18446744073709551616"));
            Assert.assertThrows(RuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
        }
    }
    @Test public void bootUnknownAndFreshPostResumeIdentityChangeAreFailClosed() {
        idle.remove("bootChildActive");Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
        idle.addProperty("bootChildActive",false);changedAfter=true;
        Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals("RECOVERY_REQUIRED",operation.getState());
    }
    @Test public void completedSameKeyApiReplayReturnsDurableResultWithoutAnotherNativeDispatch() {
        manager.recoverVolumePreparation(instance,operation);commands.clear();
        org.apache.cloudstack.api.command.user.storage.dataservice.ResumeStorageServiceVolumePreparationCmd cmd=
                new org.apache.cloudstack.api.command.user.storage.dataservice.ResumeStorageServiceVolumePreparationCmd();
        ReflectionTestUtils.setField(cmd,"instanceId",9L);ReflectionTestUtils.setField(cmd,"volumeId",3L);
        ReflectionTestUtils.setField(cmd,"operationId","volume-"+volume.getUuid());ReflectionTestUtils.setField(cmd,"idempotencyKey","same-resume");
        ReflectionTestUtils.setField(cmd,"expectedFilesystemUuid","same-filesystem");
        JsonObject result=JsonParser.parseString(operation.getResultJson()).getAsJsonObject();
        Mockito.when(operations.findByRequest(9L,"VOLUME_PREPARATION_RESUME:same-resume")).thenReturn(operation);
        com.cloud.user.User user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(2L);
        org.apache.cloudstack.context.CallContext.register(user,Mockito.mock(com.cloud.user.Account.class));
        try(org.mockito.MockedStatic<com.cloud.utils.db.GlobalLock> locks=Mockito.mockStatic(com.cloud.utils.db.GlobalLock.class);
                org.mockito.MockedStatic<com.cloud.utils.db.DbProperties> properties=Mockito.mockStatic(com.cloud.utils.db.DbProperties.class)){
            java.util.Properties protectedRuntime=new java.util.Properties();protectedRuntime.setProperty("db.cloud.encrypt.secret","offline-fingerprint-key-not-a-credential");
            properties.when(com.cloud.utils.db.DbProperties::getDbProperties).thenReturn(protectedRuntime);
            result.addProperty("_requestFingerprint",StorageServiceRequestFingerprint.of(cmd));operation.setResultJson(result.toString());
            com.cloud.utils.db.GlobalLock lock=Mockito.mock(com.cloud.utils.db.GlobalLock.class);Mockito.when(lock.lock(120)).thenReturn(true);
            locks.when(()->com.cloud.utils.db.GlobalLock.getInternLock("StorageServiceWriter-9")).thenReturn(lock);
            manager.resumeStorageServiceVolumePreparation(cmd);manager.resumeStorageServiceVolumePreparation(cmd);
            Assert.assertTrue(commands.isEmpty());Assert.assertEquals(1,mutations);
        }finally{org.apache.cloudstack.context.CallContext.unregister();}
    }
    @Test public void guestRebootUsesFreshBootWhilePreservingOldSuccessfulFormatterIdentity() {
        parked(true);boot="44444444-4444-4444-8444-444444444444";reservation.addProperty("bootId",boot);
        manager.recoverVolumePreparation(instance,operation);Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());
        Assert.assertEquals("33333333-3333-4333-8333-333333333333",history.getAsJsonObject("operation").get("formatterBootId").getAsString());
        Assert.assertEquals("RECOVERY_REQUIRED",original.getState());Assert.assertEquals(1,mutations);
    }
    @Test public void defaultOffWithoutNativeCheckpointOnlyAllowsUniqueOwnedMountAndNoNativeLease() {
        parked(false);Mockito.when(controls.findByOperation(11L)).thenReturn(null);
        JsonObject source=JsonParser.parseString(original.getPreviousSnapshotJson()).getAsJsonObject();source.remove("nativeGeneration");
        original.setPreviousSnapshotJson(source.toString());absentLease();
        manager.recoverVolumePreparation(instance,operation);Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",operation.getState());
        Assert.assertEquals("RECOVERY_REQUIRED",original.getState());Assert.assertEquals(1,mutations);
    }
    private void absentLease() {
        reservation.addProperty("reservationAcquired",false);reservation.addProperty("expired",false);
        reservation.add("scope",JsonNull.INSTANCE);reservation.add("requirements",JsonNull.INSTANCE);reservation.add("leaseBootId",JsonNull.INSTANCE);
    }
    @Test public void defaultOffCannotAdoptForeignReservationMissingSnapshotOrCommittedOriginal() {
        for(int mode=0;mode<4;mode++){
            setup();parked(false);Mockito.when(controls.findByOperation(11L)).thenReturn(null);
            JsonObject source=JsonParser.parseString(original.getPreviousSnapshotJson()).getAsJsonObject();source.remove("nativeGeneration");
            if(mode==1)source.remove("tables");original.setPreviousSnapshotJson(source.toString());
            if(mode>0)absentLease();if(mode==2)generation.addProperty("operationUuid",original.getUuid());if(mode==3)foreignPending=true;
            Assert.assertThrows(RuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
        }
    }
    @Test public void unparkedResumeCannotAdoptForeignPendingOrAnyLogicalReservation() {
        foreignPending=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
        foreignPending=false;reservation.addProperty("reservationAcquired",true);reservation.add("scope",manager.operationReservationScope(instance,original));
        Assert.assertThrows(CloudRuntimeException.class,()->manager.recoverVolumePreparation(instance,operation));Assert.assertEquals(0,mutations);
    }
}
