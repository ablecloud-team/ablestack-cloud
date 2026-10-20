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

import java.util.Map;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

public class StorageConfigFoundationRecoveryTest {
    private static final String TARGET="11111111-1111-4111-8111-111111111111";
    private static final String BOOT="33333333-3333-4333-8333-333333333333";
    private static JsonObject frozen() {
        JsonObject result=new JsonObject();result.add("scope",new JsonObject());result.add("allocations",new JsonArray());result.add("volumeMappings",new JsonObject());
        result.addProperty("planSha256",StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(result)));return result;
    }
    private static class Fixture extends StorageServiceManagerImpl implements AutoCloseable {
        final StorageServiceInstanceVO target=new StorageServiceInstanceVO();
        final StorageServiceOperationVO operation=new StorageServiceOperationVO();
        final StorageConfigArtifactVO artifact=new StorageConfigArtifactVO();
        final StorageConfigArtifactDao artifacts=Mockito.mock(StorageConfigArtifactDao.class);
        final StorageServiceOperationDao operations=Mockito.mock(StorageServiceOperationDao.class);
        final JsonObject allocation=frozen(),plan=new JsonObject(),metadata=new JsonObject(),source=new JsonObject(),pin=new JsonObject(),binding=new JsonObject();
        int starts,ends,initial,additional,acquired,released;boolean failAdditional,pendingFormatter,changedBoot;
        Fixture() {
            com.cloud.user.User user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(2L);
            org.apache.cloudstack.context.CallContext.register(user,Mockito.mock(com.cloud.user.Account.class));
            ReflectionTestUtils.setField(target,"id",7L);ReflectionTestUtils.setField(target,"uuid",TARGET);target.setVmId(48L);
            ReflectionTestUtils.setField(operation,"id",5L);operation.setInstanceId(7);operation.setRevision(1);operation.setCreatedBy(2);
            operation.setAction("CLONE_FOUNDATION_PREPARE");operation.setState("RECOVERY_REQUIRED");
            ReflectionTestUtils.setField(artifact,"id",11L);artifact.setInstanceId(9);artifact.setKind("IMPORT");artifact.setState("ARCHIVE_VALIDATED");artifact.setSha256("a".repeat(64));
            plan.addProperty("targetMode","CREATE_NEW");plan.add("createNew",new JsonObject());plan.add("cloneTemplatePin",new JsonObject());
            plan.add("volumeAllocationPlan",allocation.deepCopy());plan.addProperty("runtimeBundleUuid","bundle");
            pin.addProperty("expectedCliSha256","b".repeat(64));plan.add("cloneRuntimePin",pin.deepCopy());binding.addProperty("rootUuid","same-root");
            metadata.add("plan",plan);metadata.addProperty("createdTargetInstanceUuid",TARGET);metadata.add("createdTargetTemplateBinding",binding);
            metadata.add("volumeExecutionPlan",allocation);metadata.addProperty("runtimePreparedBundleUuid","bundle");artifact.setMetadataJson(metadata.toString());
            source.addProperty("bootId",BOOT);JsonObject intent=new JsonObject();intent.addProperty("artifactUuid",artifact.getUuid());intent.addProperty("artifactSha256",artifact.getSha256());
            intent.addProperty("targetInstanceUuid",TARGET);intent.add("volumeAllocationPlan",allocation);intent.add("nativeSource",source);
            intent.addProperty("reviewedPlanSha256",StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(plan)));
            operation.setPreviousSnapshotJson(intent.toString());operation.setRequestKey("CLONE_FOUNDATION:"+artifact.getUuid());
            Mockito.when(artifacts.findByUuid(artifact.getUuid())).thenReturn(artifact);Mockito.when(operations.update(Mockito.eq(5L),Mockito.any())).thenReturn(true);
            ReflectionTestUtils.setField(this,"storageConfigArtifactDao",artifacts);ReflectionTestUtils.setField(this,"storageOperationDao",operations);
            StorageServiceRuntimeUpgradeManager runtimes=Mockito.mock(StorageServiceRuntimeUpgradeManager.class);
            Mockito.when(runtimes.freshSignedRuntimeValidationProof(7,"b".repeat(64))).thenReturn(new JsonObject());ReflectionTestUtils.setField(this,"runtimeUpgradeManager",runtimes);
        }
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) {Assert.assertEquals(Long.valueOf(9),id);return target;}
        @Override protected void requireConfigurationCloneTemplatePin(JsonObject blueprint,JsonObject template,StorageServiceInstanceVO target) { }
        @Override protected JsonObject configurationCloneCreatedTemplateBinding(StorageServiceInstanceVO target,JsonObject template) {return binding;}
        @Override protected JsonObject bindConfigurationVolumeExecution(StorageServiceInstanceVO target,StorageConfigArtifactVO artifact,JsonObject reviewed) {return allocation;}
        @Override protected JsonObject configurationCloneRuntimePin(String bundle) {Assert.assertEquals("bundle",bundle);return pin;}
        @Override protected void requireCloneRuntimeReadback(JsonObject proof,JsonObject expected) {Assert.assertEquals(pin,expected);Assert.assertNotNull(proof);}
        @Override protected JsonObject configurationFoundationNativeSource(StorageServiceInstanceVO target,StorageServiceOperationVO owner) {
            Assert.assertSame(operation,owner);JsonObject result=source.deepCopy();if(changedBoot)result.addProperty("bootId","changed");return result;
        }
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO target) {if(pendingFormatter)throw new CloudRuntimeException("unobserved formatter");}
        @Override protected void requireConfigurationFoundationIdle(StorageServiceInstanceVO target,StorageServiceOperationVO owner,JsonObject plan) {requireNoPendingVolumeFormatter(target);}
        @Override protected void requireRollbackNativeWriterIdle(StorageServiceInstanceVO target) { }
        @Override protected void beginStorageWriterHeartbeat(StorageServiceOperationVO owner) {Assert.assertSame(operation,owner);starts++;}
        @Override protected void endStorageWriterHeartbeat() {ends++;}
        @Override protected StorageServiceOperationControlVO startOperationControl(StorageServiceInstanceVO target,StorageServiceOperationVO owner) {Assert.assertSame(operation,owner);return null;}
        @Override protected boolean operationControlEnabled(StorageServiceInstanceVO target) {return true;}
        @Override protected void acquireOperationResourceReservation(StorageServiceInstanceVO target,StorageServiceOperationVO owner) {Assert.assertSame(operation,owner);acquired++;}
        @Override protected void releaseOperationResourceReservation(StorageServiceInstanceVO target,StorageServiceOperationVO owner) {Assert.assertSame(operation,owner);released++;}
        @Override protected void recoverConfigurationFoundationInitialVolume(StorageServiceInstanceVO target,StorageServiceOperationVO owner,JsonObject plan) {Assert.assertSame(operation,owner);initial++;}
        @Override protected JsonObject prepareConfigurationVolumeAllocations(StorageServiceInstanceVO target,StorageConfigArtifactVO artifact,JsonObject frozen) {
            Assert.assertEquals(allocation,frozen);additional++;if(failAdditional)throw new CloudRuntimeException("same additional DATA needs recovery");return new JsonObject();
        }
        public void close(){org.apache.cloudstack.context.CallContext.unregister();}
    }
    @Test public void sameFoundationResumesAndRepeatedCompletionDoesNotPrepareOrAllocateAgain() {
        try(Fixture fixture=new Fixture()) {
            fixture.recoverConfigurationCloneFoundation(fixture.target,fixture.operation);
            Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",fixture.operation.getState());Assert.assertEquals("FOUNDATION_PREPARED",fixture.operation.getPhase());
            fixture.recoverConfigurationCloneFoundation(fixture.target,fixture.operation);
            Assert.assertEquals(1,fixture.initial);Assert.assertEquals(1,fixture.additional);Assert.assertEquals(1,fixture.starts);Assert.assertEquals(1,fixture.ends);
            Assert.assertEquals(1,fixture.acquired);Assert.assertEquals(1,fixture.released);Assert.assertEquals(5,fixture.operation.getId());
            Mockito.verify(fixture.operations,Mockito.never()).persist(Mockito.any());
        }
    }
    @Test public void partialRecoveryRetainsSameOperationAndFrozenPlanForExplicitRetry() {
        try(Fixture fixture=new Fixture()) {
            String source=fixture.operation.getPreviousSnapshotJson();fixture.failAdditional=true;
            Assert.assertThrows(RuntimeException.class,()->fixture.recoverConfigurationCloneFoundation(fixture.target,fixture.operation));
            Assert.assertEquals("RECOVERY_REQUIRED",fixture.operation.getState());Assert.assertEquals(0,fixture.released);
            fixture.failAdditional=false;fixture.recoverConfigurationCloneFoundation(fixture.target,fixture.operation);
            Assert.assertEquals("COMPLETE_NO_CONFIG_CHANGE",fixture.operation.getState());Assert.assertEquals(source,fixture.operation.getPreviousSnapshotJson());
            Assert.assertEquals(2,fixture.initial);Assert.assertEquals(2,fixture.additional);Assert.assertEquals(2,fixture.starts);Assert.assertEquals(2,fixture.ends);
            Mockito.verify(fixture.operations,Mockito.never()).persist(Mockito.any());
        }
    }
    @Test public void foreignMissingChangedPlanBootAndPendingFormatterRejectBeforeAnyDataEffect() {
        for(int mode=0;mode<8;mode++)try(Fixture fixture=new Fixture()) {
            if(mode==0)fixture.operation.setCreatedBy(3);
            if(mode==1)fixture.artifact.setSha256("f".repeat(64));
            if(mode==2){fixture.metadata.addProperty("createdTargetInstanceUuid","foreign");fixture.artifact.setMetadataJson(fixture.metadata.toString());}
            if(mode==3){fixture.plan.addProperty("unexpectedChange",true);fixture.artifact.setMetadataJson(fixture.metadata.toString());}
            if(mode==4){JsonObject intent=com.google.gson.JsonParser.parseString(fixture.operation.getPreviousSnapshotJson()).getAsJsonObject();intent.remove("nativeSource");fixture.operation.setPreviousSnapshotJson(intent.toString());}
            if(mode==5)fixture.changedBoot=true;
            if(mode==6)fixture.pendingFormatter=true;
            if(mode==7)fixture.artifact.setExpires(new java.util.Date(1));
            Assert.assertThrows(RuntimeException.class,()->fixture.recoverConfigurationCloneFoundation(fixture.target,fixture.operation));
            Assert.assertEquals(0,fixture.initial);Assert.assertEquals(0,fixture.additional);Assert.assertEquals(0,fixture.starts);Assert.assertEquals("RECOVERY_REQUIRED",fixture.operation.getState());
        }
    }

    private static class Initial extends StorageServiceManagerImpl {
        final StorageServiceInstanceVO target=Mockito.mock(StorageServiceInstanceVO.class);
        final StorageServiceOperationVO owner=new StorageServiceOperationVO();
        final VolumeVO volume=Mockito.mock(VolumeVO.class);final JsonObject history=new JsonObject(),proof=new JsonObject(),plan=new JsonObject();
        final VolumeDetailsDao details=Mockito.mock(VolumeDetailsDao.class);JsonObject applied;
        Initial() {
            Mockito.when(target.getUuid()).thenReturn(TARGET);Mockito.when(target.getVmId()).thenReturn(48L);Mockito.when(volume.getUuid()).thenReturn("44444444-4444-4444-8444-444444444444");Mockito.when(volume.getId()).thenReturn(9L);
            owner.setRevision(1);JsonObject source=new JsonObject();source.addProperty("bootId",BOOT);JsonObject intent=new JsonObject();intent.add("nativeSource",source);JsonObject allocation=new JsonObject();allocation.add("allocations",new JsonArray());intent.add("volumeAllocationPlan",allocation);owner.setPreviousSnapshotJson(intent.toString());
            JsonObject blueprint=new JsonObject();blueprint.addProperty("backingvolumemode","NEW");plan.add("createNew",blueprint);
            history.addProperty("success",true);history.addProperty("status","COMPLETE");history.addProperty("formatterActive",false);
            JsonObject journal=new JsonObject();journal.addProperty("phase","COMPLETE");journal.addProperty("formatStarted",true);history.add("operation",journal);
            JsonObject formatOwner=new JsonObject();formatOwner.addProperty("instanceUuid",TARGET);formatOwner.addProperty("managerOperationUuid",owner.getUuid());formatOwner.addProperty("revision",1);
            JsonObject original=new JsonObject();original.add("originalFormatOwner",formatOwner);JsonObject observedHistory=new JsonObject();observedHistory.add("operation",original);
            proof.add("history",observedHistory);proof.addProperty("bootId",BOOT);JsonObject identity=new JsonObject();identity.addProperty("filesystemUuid","same-filesystem");proof.add("currentIdentity",identity);
            com.cloud.storage.dao.VolumeDao volumes=Mockito.mock(com.cloud.storage.dao.VolumeDao.class);Mockito.when(volumes.findByInstanceAndType(48L,com.cloud.storage.Volume.Type.DATADISK)).thenReturn(java.util.List.of(volume));ReflectionTestUtils.setField(this,"volumeDao",volumes);
            ReflectionTestUtils.setField(this,"configurationVolumeDetailsDao",details);Mockito.when(details.listDetailsKeyPairs(9)).thenReturn(Map.of());
        }
        @Override protected VolumeVO configurationInitialVolume(StorageServiceInstanceVO instance) {return volume;}
        @Override protected Long configurationVolumeId(StorageServiceInstanceVO instance,String uuid) {return 9L;}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout) {Assert.assertEquals("volume operation status",command);return history;}
        @Override protected JsonObject completedVolumePreparationObservation(StorageServiceInstanceVO instance,VolumeVO volume) {return proof;}
        @Override protected void prepareConfigurationInitialVolume(StorageServiceInstanceVO instance,JsonObject blueprint) {Assert.assertNull(applied);applied=blueprint.deepCopy();}
    }
    @Test public void completedInitialFormattingUsesOnlyExistingFilesystemAndExactOriginalOwner() {
        Initial fixture=new Initial();fixture.recoverConfigurationFoundationInitialVolume(fixture.target,fixture.owner,fixture.plan);
        Assert.assertEquals("EXISTING",fixture.applied.get("backingvolumemode").getAsString());Assert.assertEquals("same-filesystem",fixture.applied.get("expectedFilesystemUuid").getAsString());
        for(boolean boot:new boolean[]{false,true}) {
            Initial changed=new Initial();
            if(boot)changed.proof.addProperty("bootId","different");else changed.proof.getAsJsonObject("history").getAsJsonObject("operation").getAsJsonObject("originalFormatOwner").addProperty("managerOperationUuid",TARGET);
            Assert.assertThrows(RuntimeException.class,()->changed.recoverConfigurationFoundationInitialVolume(changed.target,changed.owner,changed.plan));Assert.assertNull(changed.applied);
        }
    }
    @Test public void onlyGenuineUnstartedNewDataCanReachOriginalBlankFormattingGate() {
        Initial fixture=new Initial();fixture.history.addProperty("status","NOT_STARTED");fixture.history.remove("operation");
        fixture.recoverConfigurationFoundationInitialVolume(fixture.target,fixture.owner,fixture.plan);
        Assert.assertEquals("NEW",fixture.applied.get("backingvolumemode").getAsString());
        Initial partial=new Initial();partial.history.addProperty("status","NOT_STARTED");partial.history.remove("operation");
        Mockito.when(partial.details.listDetailsKeyPairs(9)).thenReturn(Map.of(StorageVolumeLifecycleProtection.STATE,"UNVERIFIED_FORMAT_INTENT"));
        Assert.assertThrows(RuntimeException.class,()->partial.recoverConfigurationFoundationInitialVolume(partial.target,partial.owner,partial.plan));Assert.assertNull(partial.applied);
    }

    @Test public void originalCompletedFormatterCanFinishMountButUnprovenOrForeignCannot() {
        Initial fixture=new Initial();fixture.history.addProperty("status","RECOVERY_REQUIRED");fixture.history.getAsJsonObject("operation").addProperty("phase","RECOVERY_REQUIRED");
        fixture.requireConfigurationFoundationIdle(fixture.target,fixture.owner,fixture.plan);
        fixture.recoverConfigurationFoundationInitialVolume(fixture.target,fixture.owner,fixture.plan);
        Assert.assertEquals("EXISTING",fixture.applied.get("backingvolumemode").getAsString());
        Initial foreign=new Initial();foreign.history.addProperty("status","RECOVERY_REQUIRED");foreign.history.getAsJsonObject("operation").addProperty("phase","RECOVERY_REQUIRED");
        foreign.proof.getAsJsonObject("history").getAsJsonObject("operation").getAsJsonObject("originalFormatOwner").addProperty("managerOperationUuid",TARGET);
        Assert.assertThrows(RuntimeException.class,()->foreign.requireConfigurationFoundationIdle(foreign.target,foreign.owner,foreign.plan));Assert.assertNull(foreign.applied);
    }

    @Test public void existingFoundationRecoveryUsesArtifactUuidAndRejectsChangedJournalBeforeMount() {
        for(boolean changed:new boolean[]{false,true}) {
            Initial fixture=new Initial();fixture.plan.getAsJsonObject("createNew").addProperty("backingvolumemode","EXISTING");
            fixture.plan.getAsJsonObject("createNew").addProperty("filesystem","EXT4");
            String uuid="77777777-7777-4777-8777-777777777777";
            Mockito.when(fixture.volume.getVolumeType()).thenReturn(com.cloud.storage.Volume.Type.DATADISK);
            String safetyReceipt=StorageVolumeLifecycleProtection.identity(fixture.volume).toString();
            Mockito.when(fixture.details.listDetailsKeyPairs(9)).thenReturn(Map.of(
                    StorageVolumeLifecycleProtection.STATE,"VERIFIED",StorageVolumeLifecycleProtection.RECEIPT,safetyReceipt));
            JsonObject observed=new JsonObject();observed.addProperty("volumeUuid",fixture.volume.getUuid());observed.addProperty("filesystem","EXT4");observed.addProperty("filesystemUuid",uuid);
            JsonObject metadata=new JsonObject();metadata.add("cloneExistingFilesystem",observed);StorageConfigArtifactVO artifact=new StorageConfigArtifactVO();artifact.setMetadataJson(metadata.toString());
            StorageConfigArtifactDao artifacts=Mockito.mock(StorageConfigArtifactDao.class);Mockito.when(artifacts.findByUuid(Mockito.anyString())).thenReturn(artifact);
            ReflectionTestUtils.setField(fixture,"storageConfigArtifactDao",artifacts);
            JsonObject intent=com.google.gson.JsonParser.parseString(fixture.owner.getPreviousSnapshotJson()).getAsJsonObject();intent.addProperty("artifactUuid",artifact.getUuid());fixture.owner.setPreviousSnapshotJson(intent.toString());
            JsonObject journal=fixture.history.getAsJsonObject("operation");journal.addProperty("formatStarted",false);journal.addProperty("volumeUuid",fixture.volume.getUuid());
            journal.addProperty("filesystemUuid",changed?"88888888-8888-4888-8888-888888888888":uuid);
            if(changed) {
                Assert.assertThrows(CloudRuntimeException.class,()->fixture.recoverConfigurationFoundationInitialVolume(fixture.target,fixture.owner,fixture.plan));Assert.assertNull(fixture.applied);
            } else {
                fixture.recoverConfigurationFoundationInitialVolume(fixture.target,fixture.owner,fixture.plan);
                Assert.assertEquals(uuid,fixture.applied.get("expectedFilesystemUuid").getAsString());
                Assert.assertEquals("EXISTING",fixture.applied.get("backingvolumemode").getAsString());Assert.assertEquals("EXT4",fixture.applied.get("filesystem").getAsString());
            }
        }
    }

}
