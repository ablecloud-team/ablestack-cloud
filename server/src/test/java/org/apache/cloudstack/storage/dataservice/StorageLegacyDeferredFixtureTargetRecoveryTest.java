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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.response.StorageFileShareResponse;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageLegacyDeferredFixtureTargetRecoveryTest {
    private static final List<String> TABLES=List.of("storage_service_protocol","storage_file_share","storage_block_target",
            "storage_identity_domain","storage_access_rule","storage_posix_directory_policy");
    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    static class Fixture {
        final String instance=UUID.randomUUID().toString(),vm=UUID.randomUUID().toString(),shared=UUID.randomUUID().toString();
        final String root=UUID.randomUUID().toString(),data=UUID.randomUUID().toString(),share=UUID.randomUUID().toString();
        final String boot=UUID.randomUUID().toString(),filesystem=UUID.randomUUID().toString();
        final StorageServiceOperationVO operation=new StorageServiceOperationVO();
        final com.cloud.storage.VMTemplateVO template=new com.cloud.storage.VMTemplateVO();
        final JsonObject binding=new JsonObject(),status=new JsonObject(),history=new JsonObject(),identity=new JsonObject();
        final StorageTemplateFixturePermit permits;
        final Set<String> excluded=new java.util.HashSet<>();
        JsonObject creation;
        String snapshot;
        Fixture() throws Exception {
            operation.setInstanceId(7);operation.setCreatedBy(2);operation.setRevision(2);operation.setState("RECOVERY_REQUIRED");
            operation.setAction("createstoragenfsexportresponse");ReflectionTestUtils.setField(operation,"id",99L);
            ReflectionTestUtils.setField(template,"id",218L);ReflectionTestUtils.setField(template,"uuid",UUID.randomUUID().toString());
            ReflectionTestUtils.setField(template,"accountId",2L);template.setChecksum("{SHA-256}"+"b".repeat(64));
            template.setDetails(Map.of("storage.service.source.commit","a".repeat(40)));
            JsonObject request=object("{\"accountId\":2,\"zoneId\":1,\"rootProvisioningType\":\"SPARSE\",\"dataProvisioningType\":\"SPARSE\",\"backingVolumeMode\":\"NEW\",\"sizeGiB\":20}");
            request.addProperty("templateUuid",template.getUuid());request.addProperty("templateChecksum",template.getChecksum());
            request.addProperty("templateDetailsSha256",StorageTemplateFixturePermit.detailsSha256(template));
            binding.addProperty("vmId",71);binding.addProperty("vmUuid",vm);binding.addProperty("rootVolumeUuid",root);binding.addProperty("dataVolumeUuid",data);
            binding.add("rootDisk",disk(root,"ROOT",5242880000L,218L));binding.add("dataDisk",disk(data,"DATADISK",20L<<30,null));
            JsonObject receipt=object("{\"schemaVersion\":2,\"formatterPid\":100,\"formatterStartTicks\":500,\"formatterExitCode\":0,\"formatDiscardPolicy\":\"SKIP_DISCARD\"}");
            receipt.addProperty("volumeUuid",data);receipt.addProperty("serial",data.replace("-","").substring(0,20));receipt.addProperty("sizeBytes",20L<<30);
            receipt.addProperty("filesystem","ext4");receipt.addProperty("filesystemUuid",filesystem);receipt.addProperty("formatterBootId",boot);
            receipt.addProperty("verifiedEpoch",System.currentTimeMillis()/1000.0-20);
            JsonArray options=new JsonArray();List.of("-F","-E","nodiscard,lazy_itable_init=1,lazy_journal_init=1").forEach(options::add);receipt.add("formatterOptions",options);
            JsonObject owner=new JsonObject();owner.addProperty("instanceUuid",instance);owner.addProperty("managerOperationUuid",operation.getUuid());owner.addProperty("revision",2);receipt.add("originalFormatOwner",owner);
            JsonObject journal=receipt.deepCopy();journal.addProperty("phase","COMPLETE");journal.addProperty("formatStarted",true);journal.addProperty("terminationPending",false);journal.addProperty("volumeSizeBytes",20L<<30);journal.add("formatterSuccessReceipt",receipt);
            history.addProperty("success",true);history.addProperty("status","COMPLETE");history.addProperty("formatterActive",false);
            history.addProperty("formatterObservationComplete",true);history.addProperty("terminationPending",false);history.addProperty("bootId",boot);history.add("operation",journal);
            for(String field:List.of("volumeUuid","serial","sizeBytes","filesystem","filesystemUuid"))identity.add(field,receipt.get(field).deepCopy());
            identity.addProperty("mappingStatus","EXACT");identity.addProperty("matchedBy","VOLUME_SERIAL");
            JsonObject config=new JsonObject();config.addProperty("importMode","FORMAT_EMPTY");config.add("lastInspection",history.deepCopy());
            JsonObject row=new JsonObject();row.addProperty("uuid",share);row.addProperty("state","Allocated");row.addProperty("volume_id",82);row.addProperty("protocol","NFS");row.addProperty("instance_id",7);row.addProperty("config_json",config.toString());
            JsonObject target=snapshot(row);snapshot=target.toString();operation.setSnapshotJson(snapshot);
            JsonObject source=snapshot(null);source.add("nativeGeneration",new JsonObject());operation.setPreviousSnapshotJson(source.toString());
            StorageFileShareResponse dto=new StorageFileShareResponse();dto.setId(share);dto.setState("Allocated");dto.setProtocol("NFS");dto.setInstanceId(instance);dto.setVolumeId(data);
            JsonObject result=new JsonObject();result.add("_response",new Gson().toJsonTree(dto));result.addProperty("_requestFingerprint","f".repeat(64));operation.setResultJson(result.toString());
            status.addProperty("success",true);status.addProperty("generationSupported",true);status.addProperty("runtimeRevision",2);
            status.addProperty("configurationSha256","c".repeat(64));status.addProperty("bootId",boot);status.addProperty("pendingOperationUuid",operation.getUuid());
            JsonObject current=new JsonObject();current.addProperty("instanceUuid",instance);current.addProperty("operationUuid",operation.getUuid());
            current.addProperty("revision",2);current.addProperty("configurationSha256","c".repeat(64));current.addProperty("verifiedAt",System.currentTimeMillis()/1000.0-10);status.add("generation",current);
            for(int i=0;i<7;i++)excluded.add(UUID.randomUUID().toString());
            JsonObject artifact=object("{\"schemaVersion\":1,\"kind\":\"NEW_SPARSE_PRECREATE\",\"newDisposableFixture\":true,\"originalDataExcluded\":true,\"newDataWithoutBacking\":true}");
            artifact.addProperty("sourceCommit","a".repeat(40));artifact.addProperty("expectedCliSha256","d".repeat(64));artifact.addProperty("expiresAtMillis",System.currentTimeMillis()+3600000);
            artifact.add("request",request);JsonArray ex=new JsonArray();excluded.forEach(ex::add);artifact.add("excludedInstanceUuids",ex);
            Path path=Files.createTempDirectory("legacy-fixture-recovery-");Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
            permits=new StorageTemplateFixturePermit(path);String artifactId=UUID.randomUUID().toString();
            byte[] bytes=artifact.toString().getBytes(StandardCharsets.UTF_8);String hash=StorageConfigArchive.sha256(bytes);
            new StorageConfigArtifactStore(path).write(artifactId,bytes);permits.complete(artifactId,hash,shared,request,binding);
            creation=permits.requireCreated(shared,71,vm,2,1,template,excluded);
        }
        JsonObject snapshot(JsonObject shareRow) {
            JsonObject value=object("{\"schemaVersion\":2,\"instanceId\":7}");JsonArray tables=new JsonArray();
            for(String name:TABLES) {
                JsonObject entry=new JsonObject();entry.addProperty("table",name);entry.add("columns",new JsonArray());JsonArray rows=new JsonArray();
                if("storage_service_protocol".equals(name))rows.add(object("{\"protocol\":\"NFS\",\"instance_id\":7,\"enabled\":true}"));
                if("storage_file_share".equals(name)&&shareRow!=null)rows.add(shareRow);
                entry.add("rows",rows);tables.add(entry);
            }value.add("tables",tables);return value;
        }
        JsonObject disk(String uuid,String type,long size,Long templateId) {
            JsonObject value=object("{\"poolId\":1,\"accountId\":2,\"zoneId\":1,\"provisioningType\":\"SPARSE\",\"state\":\"Ready\",\"attachedToFixture\":true,\"notRemoved\":true}");
            value.addProperty("volumeUuid",uuid);value.addProperty("path",uuid);value.addProperty("type",type);value.addProperty("sizeBytes",size);
            value.addProperty("templateId",templateId);value.addProperty("newDataWithoutBacking",templateId==null);return value;
        }
        JsonObject require() {
            return StorageLegacyDeferredFixtureTargetRecovery.require(instance,operation,snapshot,binding,creation,status,history,identity,82);
        }
    }
    private void rejected(Consumer<Fixture> mutation) throws Exception {
        Fixture fixture=new Fixture();mutation.accept(fixture);Assert.assertThrows(CloudRuntimeException.class,fixture::require);
    }
    @Test public void realProtectedCreationProducerAndRealResponseDtoQualifyWithoutHistoricalBackfill() throws Exception {
        Fixture fixture=new Fixture();String before=fixture.operation.getResultJson();JsonObject receipt=fixture.require();
        Assert.assertEquals("LEGACY_DEFERRED_FIXTURE_TARGET_RECOVERY",receipt.get("kind").getAsString());
        Assert.assertFalse(receipt.get("historicalTargetWitnessesBackfilled").getAsBoolean());Assert.assertEquals(before,fixture.operation.getResultJson());
        Assert.assertFalse(object(before).has("_targetResourceBindings"));new Fixture().require();
    }
    @Test public void genericTargetGuardStillRejectsMissingWitnesses() throws Exception {
        Fixture f=new Fixture();Assert.assertThrows(CloudRuntimeException.class,()->StorageOperationReconciliation.requireTarget(f.instance,f.operation,f.snapshot,new JsonObject(),f.status));
    }
    @Test public void anyPartialOriginalWitnessUsesNoAlternateAuthority() throws Exception {
        for(String field:List.of("_targetResourceBindings","_targetNativeBootId","_targetConfigurationSha256")) {
            Fixture f=new Fixture();JsonObject result=object(f.operation.getResultJson());result.add(field,JsonNull.INSTANCE);f.operation.setResultJson(result.toString());
            Assert.assertFalse(StorageLegacyDeferredFixtureTargetRecovery.eligible(f.operation));Assert.assertThrows(CloudRuntimeException.class,f::require);
        }
    }
    @Test public void missingResponseOrChangedSnapshotCannotQualify() throws Exception {
        rejected(f->f.operation.setResultJson("{}"));rejected(f->f.operation.setSnapshotJson("{}"));
    }
    @Test public void originalRootDataPathPoolSizeAndVmMustMatchCreation() throws Exception {
        for(String field:List.of("path","poolId","sizeBytes","provisioningType"))rejected(f->f.binding.getAsJsonObject("dataDisk").addProperty(field,"foreign"));
        rejected(f->f.binding.addProperty("vmUuid",UUID.randomUUID().toString()));rejected(f->f.binding.addProperty("rootVolumeUuid",UUID.randomUUID().toString()));
    }
    @Test public void reusedOrRemovedOrThinDataCannotQualify() throws Exception {
        rejected(f->f.binding.getAsJsonObject("dataDisk").addProperty("newDataWithoutBacking",false));
        rejected(f->f.binding.getAsJsonObject("dataDisk").addProperty("notRemoved",false));
    }
    @Test public void foreignNativeScopePendingOrRevisionCannotQualify() throws Exception {
        rejected(f->f.status.addProperty("pendingOperationUuid",UUID.randomUUID().toString()));
        rejected(f->f.status.getAsJsonObject("generation").addProperty("operationUuid",UUID.randomUUID().toString()));
        rejected(f->f.status.addProperty("runtimeRevision",3));
    }
    @Test public void foreignBootReceiptOrPhysicalSerialCannotQualify() throws Exception {
        rejected(f->f.status.addProperty("bootId",UUID.randomUUID().toString()));
        rejected(f->f.history.getAsJsonObject("operation").getAsJsonObject("formatterSuccessReceipt").addProperty("filesystemUuid",UUID.randomUUID().toString()));
        rejected(f->f.identity.addProperty("serial","foreign"));rejected(f->f.identity.addProperty("mappingStatus","UNAVAILABLE"));
    }
    @Test public void partialUnobservedActiveOrLegacyFormatterCannotQualify() throws Exception {
        rejected(f->f.history.addProperty("formatterObservationComplete",false));rejected(f->f.history.addProperty("formatterActive",true));
        rejected(f->f.history.getAsJsonObject("operation").getAsJsonObject("formatterSuccessReceipt").addProperty("schemaVersion",1));
    }
    @Test public void exposedReadyOrDifferentDataDeclarationCannotQualify() throws Exception {
        rejected(f->{JsonObject r=object(f.operation.getResultJson());r.getAsJsonObject("_response").addProperty("state","Ready");f.operation.setResultJson(r.toString());});
        Fixture f=new Fixture();Assert.assertThrows(CloudRuntimeException.class,()->StorageLegacyDeferredFixtureTargetRecovery.require(f.instance,f.operation,f.snapshot,f.binding,f.creation,f.status,f.history,f.identity,83));
    }
    @Test public void existingSourceUserDataAndOtherProtocolCannotQualify() throws Exception {
        rejected(f->{JsonObject source=object(f.operation.getPreviousSnapshotJson());source.getAsJsonArray("tables").get(1).getAsJsonObject().getAsJsonArray("rows").add(new JsonObject());f.operation.setPreviousSnapshotJson(source.toString());});
        rejected(f->{JsonObject target=object(f.snapshot);target.getAsJsonArray("tables").get(0).getAsJsonObject().getAsJsonArray("rows").get(0).getAsJsonObject().addProperty("protocol","SMB");f.snapshot=target.toString();f.operation.setSnapshotJson(f.snapshot);});
    }
    @Test public void formatterMustPrecedeCommitAndCannotChangeItsOwner() throws Exception {
        rejected(f->f.status.getAsJsonObject("generation").addProperty("verifiedAt",1));
        rejected(f->f.history.getAsJsonObject("operation").getAsJsonObject("formatterSuccessReceipt").getAsJsonObject("originalFormatOwner").addProperty("managerOperationUuid",UUID.randomUUID().toString()));
    }
    private static class ManagerFixture extends StorageServiceManagerImpl {
        final Fixture fixture;
        final StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        final org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao controls=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationControlDao.class);
        JsonObject lease;
        int idleChecks,formatterChecks,creationChecks,nativeReads,snapshotReads;
        boolean nativeDrift,snapshotDrift;
        com.cloud.storage.VolumeVO rootVolume;
        ManagerFixture(Fixture value) {
            fixture=value;
            Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn(value.instance);
            Mockito.when(instance.getVmId()).thenReturn(71L);Mockito.when(instance.getAccountId()).thenReturn(2L);Mockito.when(instance.getDataCenterId()).thenReturn(1L);
            var vms=Mockito.mock(com.cloud.vm.dao.VMInstanceDao.class);var vm=Mockito.mock(com.cloud.vm.VMInstanceVO.class);
            Mockito.when(vm.getId()).thenReturn(71L);Mockito.when(vm.getUuid()).thenReturn(value.vm);Mockito.when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Running);
            Mockito.when(vms.findById(71L)).thenReturn(vm);ReflectionTestUtils.setField(this,"vmInstanceDao",vms);
            var shares=Mockito.mock(org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao.class);var shared=Mockito.mock(org.apache.cloudstack.storage.sharedfs.SharedFSVO.class);
            Mockito.when(shared.getUuid()).thenReturn(value.shared);Mockito.when(shared.getAccountId()).thenReturn(2L);Mockito.when(shared.getDataCenterId()).thenReturn(1L);
            Mockito.when(shared.getVolumeId()).thenReturn(82L);Mockito.when(shares.findByVm(71L)).thenReturn(shared);ReflectionTestUtils.setField(this,"sharedFSDao",shares);
            var volumes=Mockito.mock(com.cloud.storage.dao.VolumeDao.class);
            rootVolume=volume(value.binding.getAsJsonObject("rootDisk"),81);
            var dataVolume=volume(value.binding.getAsJsonObject("dataDisk"),82);
            Mockito.when(volumes.findByInstanceAndType(71L,com.cloud.storage.Volume.Type.ROOT)).thenReturn(List.of(rootVolume));
            Mockito.when(volumes.findByInstanceAndType(71L,com.cloud.storage.Volume.Type.DATADISK)).thenReturn(List.of(dataVolume));
            ReflectionTestUtils.setField(this,"volumeDao",volumes);
            var templates=Mockito.mock(com.cloud.storage.dao.VMTemplateDao.class);Mockito.when(templates.findByUuid(value.template.getUuid())).thenReturn(value.template);
            ReflectionTestUtils.setField(this,"rootUpgradeTemplateDao",templates);
            ReflectionTestUtils.setField(value.template,"templateType",com.cloud.storage.Storage.TemplateType.USER);
            ReflectionTestUtils.setField(value.template,"publicTemplate",false);ReflectionTestUtils.setField(value.template,"state",com.cloud.template.VirtualMachineTemplate.State.Active);
            ReflectionTestUtils.setField(value.template,"format",com.cloud.storage.Storage.ImageFormat.QCOW2);
            ReflectionTestUtils.setField(value.template,"hypervisorType",com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);
            var instances=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class);
            java.util.ArrayList<StorageServiceInstanceVO> others=new java.util.ArrayList<>();
            for(String uuid:value.excluded) {var other=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(other.getId()).thenReturn(8L);Mockito.when(other.getUuid()).thenReturn(uuid);others.add(other);}
            Mockito.when(instances.listAll()).thenReturn(others);ReflectionTestUtils.setField(this,"storageServiceInstanceDao",instances);
            Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
            ReflectionTestUtils.setField(this,"storageOperationDao",operations);ReflectionTestUtils.setField(this,"storageOperationControlDao",controls);
            var accounts=Mockito.mock(com.cloud.user.AccountManager.class);Mockito.when(accounts.isRootAdmin(2L)).thenReturn(true);ReflectionTestUtils.setField(this,"storageAccountManager",accounts);
            lease=object("{\"success\":true,\"reservationSupported\":true,\"logicalReservationOnly\":true,\"reservationAcquired\":false,\"expired\":false,\"scope\":null}");
            lease.add("requirements",JsonNull.INSTANCE);lease.add("leaseBootId",JsonNull.INSTANCE);
            lease.addProperty("bootId",value.boot);JsonObject observed=object("{\"memoryAvailableBytes\":1024,\"stagingFreeBytes\":1024,\"loadPerCpu\":0}");
            observed.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);lease.add("observed",observed);
        }
        private com.cloud.storage.VolumeVO volume(JsonObject row,long id) {
            var value=Mockito.mock(com.cloud.storage.VolumeVO.class);Mockito.when(value.getId()).thenReturn(id);
            Mockito.when(value.getUuid()).thenReturn(row.get("volumeUuid").getAsString());Mockito.when(value.getPath()).thenReturn(row.get("path").getAsString());
            Mockito.when(value.getPoolId()).thenReturn(1L);Mockito.when(value.getAccountId()).thenReturn(2L);Mockito.when(value.getDataCenterId()).thenReturn(1L);
            Mockito.when(value.getVolumeType()).thenReturn(com.cloud.storage.Volume.Type.valueOf(row.get("type").getAsString()));
            Mockito.when(value.getSize()).thenReturn(row.get("sizeBytes").getAsLong());Mockito.when(value.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);
            Mockito.when(value.getTemplateId()).thenReturn(row.get("templateId").isJsonNull()?null:row.get("templateId").getAsLong());
            Mockito.when(value.getState()).thenReturn(com.cloud.storage.Volume.State.Ready);Mockito.when(value.getInstanceId()).thenReturn(71L);return value;
        }
        @Override protected StorageTemplateFixturePermit legacyDeferredFixturePermits() {creationChecks++;return fixture.permits;}
        @Override protected String captureConfigurationSnapshot(long instanceId) {
            snapshotReads++;return snapshotDrift && snapshotReads%2==0?"{}":fixture.snapshot;
        }
        @Override protected JsonObject operationResourceBindings(StorageServiceInstanceVO scope) {return new JsonObject();}
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO scope,StorageServiceOperationVO op,String action) {
            Assert.assertEquals("status",action);nativeReads++;JsonObject value=fixture.status.deepCopy();
            if(nativeDrift&&nativeReads%2==0)value.addProperty("configurationSha256","d".repeat(64));return value;
        }
        @Override protected void requireRollbackNativeWriterIdle(StorageServiceInstanceVO scope) {idleChecks++;}
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO scope) {formatterChecks++;}
        @Override protected JsonObject completedVolumePreparationObservation(StorageServiceInstanceVO scope,com.cloud.storage.VolumeVO volume) {
            Assert.assertEquals(fixture.data,volume.getUuid());JsonObject value=new JsonObject();value.add("history",fixture.history);value.add("currentIdentity",fixture.identity);return value;
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO scope,String command,JsonObject request,int timeout) {
            Assert.assertEquals("operation reservation status",command);
            Assert.assertEquals(fixture.operation.getUuid(),request.get("operationUuid").getAsString());return lease;
        }
        void invoke() {
            var user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(2L);
            var account=Mockito.mock(com.cloud.user.Account.class);Mockito.when(account.getId()).thenReturn(2L);
            org.apache.cloudstack.context.CallContext.register(user,account);
            try {requireOperationFinalizationWitness(instance,fixture.operation);}
            finally {org.apache.cloudstack.context.CallContext.unregister();}
        }
    }
    @Test public void actualManagerUsesProtectedProducerAndRepeatsFreshGuardsWithoutBackfill() throws Exception {
        Fixture f=new Fixture();ManagerFixture manager=new ManagerFixture(f);manager.invoke();
        JsonObject first=object(f.operation.getResultJson());Assert.assertTrue(first.has(StorageLegacyDeferredFixtureTargetRecovery.RESULT_KEY));
        Assert.assertFalse(first.has("_targetResourceBindings"));Assert.assertFalse(first.has("_targetNativeBootId"));Assert.assertFalse(first.has("_targetConfigurationSha256"));
        f.status.add("pendingOperationUuid",JsonNull.INSTANCE);manager.invoke();
        Assert.assertEquals(first,object(f.operation.getResultJson()));Assert.assertEquals(2,manager.idleChecks);
        Assert.assertEquals(2,manager.formatterChecks);Assert.assertEquals(2,manager.creationChecks);
        Mockito.verify(manager.operations,Mockito.times(1)).update(Mockito.anyLong(),Mockito.any());
        manager.lease.addProperty("reservationAcquired",true);Assert.assertThrows(CloudRuntimeException.class,manager::invoke);
    }
    @Test public void actualManagerRejectsLiveForeignLeaseDriftAndChangedCreation() throws Exception {
        Fixture f=new Fixture();ManagerFixture manager=new ManagerFixture(f);manager.nativeDrift=true;
        Assert.assertThrows(CloudRuntimeException.class,manager::invoke);Mockito.verify(manager.operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        manager.nativeDrift=false;manager.lease.add("scope",new JsonObject());Assert.assertThrows(CloudRuntimeException.class,manager::invoke);
        manager.lease.add("scope",JsonNull.INSTANCE);Mockito.when(manager.rootVolume.getPath()).thenReturn("changed");
        Assert.assertThrows(CloudRuntimeException.class,manager::invoke);
    }
    @Test public void actualManagerControlRowDoesNotUseFixtureFallback() throws Exception {
        Fixture f=new Fixture();ManagerFixture manager=new ManagerFixture(f);
        Mockito.when(manager.controls.findByOperation(99L)).thenReturn(Mockito.mock(StorageServiceOperationControlVO.class));
        Assert.assertThrows(CloudRuntimeException.class,manager::invoke);Assert.assertEquals(0,manager.creationChecks);
    }

    @Test public void targetShareProtocolAndSnapshotScopeMustBeExact() throws Exception {
        for(int mode=0;mode<4;mode++) {
            final int selected=mode;
            rejected(f->{JsonObject target=object(f.snapshot);JsonObject row=target.getAsJsonArray("tables").get(1).getAsJsonObject().getAsJsonArray("rows").get(0).getAsJsonObject();
                if(selected==0)row.addProperty("protocol","SMB");
                if(selected==1)row.addProperty("instance_id",8);
                if(selected==2)row.addProperty("removed","removed");
                if(selected==3)target.getAsJsonArray("tables").get(0).getAsJsonObject().getAsJsonArray("rows").add(object("{\"protocol\":\"NFS\",\"instance_id\":7,\"enabled\":true}"));
                f.snapshot=target.toString();f.operation.setSnapshotJson(f.snapshot);});
        }
    }
    @Test public void wrongResponseProtocolOrUnexpectedResponseTypeCannotQualify() throws Exception {
        rejected(f->{JsonObject result=object(f.operation.getResultJson());result.getAsJsonObject("_response").addProperty("protocol","SMB");f.operation.setResultJson(result.toString());});
        rejected(f->{JsonObject result=object(f.operation.getResultJson());result.getAsJsonObject("_response").addProperty("type","NFS");f.operation.setResultJson(result.toString());});
    }
    @Test public void actualManagerRequiresLeaseAbsenceFieldsAndLateSnapshotStabilityBeforePublication() throws Exception {
        for(String field:List.of("requirements","leaseBootId")) {
            Fixture f=new Fixture();ManagerFixture manager=new ManagerFixture(f);manager.lease.remove(field);
            Assert.assertThrows(CloudRuntimeException.class,manager::invoke);
            Assert.assertFalse(object(f.operation.getResultJson()).has(StorageLegacyDeferredFixtureTargetRecovery.RESULT_KEY));
            Mockito.verify(manager.operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        }
        Fixture f=new Fixture();ManagerFixture manager=new ManagerFixture(f);manager.snapshotDrift=true;
        Assert.assertThrows(CloudRuntimeException.class,manager::invoke);
        Assert.assertFalse(object(f.operation.getResultJson()).has(StorageLegacyDeferredFixtureTargetRecovery.RESULT_KEY));
        Mockito.verify(manager.operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
    }

    @Test public void responseInstanceAndVolumeMustMatchTheProtectedCreation() throws Exception {
        for(String field:List.of("instanceid","volumeid","volumeuuid")) {
            rejected(f->{JsonObject result=object(f.operation.getResultJson());result.getAsJsonObject("_response").addProperty(field,UUID.randomUUID().toString());f.operation.setResultJson(result.toString());});
        }
    }
    @Test public void actualManagerRejectsPublicTemplateAndPersistenceFailureWithoutHistoricalFields() throws Exception {
        Fixture f=new Fixture();ManagerFixture manager=new ManagerFixture(f);
        ReflectionTestUtils.setField(f.template,"publicTemplate",true);Assert.assertThrows(CloudRuntimeException.class,manager::invoke);
        Assert.assertFalse(object(f.operation.getResultJson()).has(StorageLegacyDeferredFixtureTargetRecovery.RESULT_KEY));
        Mockito.verify(manager.operations,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        ReflectionTestUtils.setField(f.template,"publicTemplate",false);Mockito.when(manager.operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(false);
        Assert.assertThrows(CloudRuntimeException.class,manager::invoke);
        for(String key:List.of("_targetResourceBindings","_targetNativeBootId","_targetConfigurationSha256"))Assert.assertFalse(object(f.operation.getResultJson()).has(key));
    }

}
