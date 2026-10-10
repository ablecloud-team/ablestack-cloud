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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ConfigureStorageRenderedValidationProfileCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageRenderedValidationAdmissionTest {
    private static class Manager extends StorageServiceManagerImpl {
        StorageServiceInstanceVO instance;int guestCalls;Runnable afterStatus=()->{};
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) {return instance;}
        @Override protected void requireVolumeResumeIdle(StorageServiceInstanceVO value,StorageServiceOperationVO own) { }
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO value) { }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO value,String command,JsonObject body,int seconds) {
            guestCalls++;Assert.assertEquals("operation generation render-status",command);afterStatus.run();
            JsonObject result=new JsonObject();result.addProperty("success",true);result.addProperty("schemaVersion",1);result.addProperty("renderedGenerationSupported",true);
            JsonArray features=new JsonArray();features.add("RENDERED_CONFIG_GENERATION_HANDLER");result.add("supportedFeatures",features);return result;
        }
    }
    private Manager manager;
    private VolumeDao volumes;
    private VolumeVO root,data;
    private StorageServiceInstanceDao instances;
    private StorageServiceRuntimeUpgradeManager runtime;
    private AtomicReference<String> policy;
    private Path store;
    private String oldStore,artifactUuid,artifactSha;
    private MockedStatic<GlobalLock> locks;
    private MockedStatic<Transaction> transactions;

    @Before public void setup() throws Exception {
        manager=new Manager();manager.instance=Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(manager.instance.getId()).thenReturn(9L);Mockito.when(manager.instance.getVmId()).thenReturn(54L);
        Mockito.when(manager.instance.getUuid()).thenReturn("owned-fixture");Mockito.when(manager.instance.getName()).thenReturn("fixture-name");
        Mockito.when(manager.instance.getAccountId()).thenReturn(2L);Mockito.when(manager.instance.getDataCenterId()).thenReturn(1L);
        policy=new AtomicReference<>("{}");Mockito.when(manager.instance.getOperationControlPolicyJson()).thenAnswer(call->policy.get());
        Mockito.doAnswer(call->{policy.set(call.getArgument(0));return null;}).when(manager.instance).setOperationControlPolicyJson(Mockito.anyString());
        root=disk("root",65L,Volume.Type.ROOT);Mockito.when(root.getTemplateId()).thenReturn(214L);
        data=disk("old-data",67L,Volume.Type.DATADISK);
        volumes=Mockito.mock(VolumeDao.class);Mockito.when(volumes.findByInstanceAndType(54L,Volume.Type.ROOT)).thenReturn(List.of(root));
        Mockito.when(volumes.findByInstanceAndType(54L,Volume.Type.DATADISK)).thenReturn(List.of(data));ReflectionTestUtils.setField(manager,"volumeDao",volumes);
        instances=Mockito.mock(StorageServiceInstanceDao.class);Mockito.when(instances.listAll()).thenReturn(List.of(manager.instance));
        Mockito.when(instances.lockRow(9L,true)).thenReturn(manager.instance);Mockito.when(instances.update(Mockito.eq(9L),Mockito.any())).thenReturn(true);
        ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);
        runtime=Mockito.mock(StorageServiceRuntimeUpgradeManager.class);Mockito.when(runtime.freshSignedRuntimeValidationProof(Mockito.eq(9L),Mockito.anyString())).thenReturn(new JsonObject());
        ReflectionTestUtils.setField(manager,"runtimeUpgradeManager",runtime);
        GlobalLock lock=Mockito.mock(GlobalLock.class);Mockito.when(lock.lock(120)).thenReturn(true);
        locks=Mockito.mockStatic(GlobalLock.class);locks.when(()->GlobalLock.getInternLock("StorageServiceWriter-9")).thenReturn(lock);
        transactions=Mockito.mockStatic(Transaction.class);
        transactions.when(()->Transaction.execute(Mockito.<TransactionCallback<Void>>any())).thenAnswer(call->((TransactionCallback<Void>)call.getArgument(0)).doInTransaction(null));
        store=Files.createTempDirectory("owned-rendered-profile-");oldStore=System.getProperty("cloudstack.storage.rendered.validation.path");
        System.setProperty("cloudstack.storage.rendered.validation.path",store.toString());writeArtifact("OWNED_SPARSE_ALL4_VALIDATION");
    }

    private VolumeVO disk(String uuid,long id,Volume.Type type) {
        VolumeVO disk=Mockito.mock(VolumeVO.class);Mockito.when(disk.getUuid()).thenReturn(uuid);Mockito.when(disk.getId()).thenReturn(id);
        Mockito.when(disk.getVolumeType()).thenReturn(type);Mockito.when(disk.getInstanceId()).thenReturn(54L);
        Mockito.when(disk.getAccountId()).thenReturn(2L);Mockito.when(disk.getDataCenterId()).thenReturn(1L);
        Mockito.when(disk.getSize()).thenReturn(20L<<30);Mockito.when(disk.getPoolId()).thenReturn(1L);Mockito.when(disk.getPath()).thenReturn(uuid+"-path");
        Mockito.when(disk.getProvisioningType()).thenReturn(ProvisioningType.SPARSE);Mockito.when(disk.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(disk.getTemplateId()).thenReturn(null);
        Mockito.when(disk.getCreated()).thenReturn(new Date(System.currentTimeMillis()-48L*60*60*1000));return disk;
    }

    private void writeArtifact(String kind) {
        JsonObject artifact=new JsonObject();artifact.addProperty("schemaVersion",1);artifact.addProperty("kind",kind);
        artifact.addProperty("instanceUuid","owned-fixture");artifact.addProperty("instanceName","fixture-name");
        artifact.addProperty("ownedDisposableFixture",true);artifact.addProperty("newDisposableFixture",true);artifact.addProperty("originalDataExcluded",true);
        artifact.addProperty("expiresAtMillis",System.currentTimeMillis()+3600000);artifact.addProperty("expectedCliSha256","a".repeat(64));artifact.addProperty("sourceCommit","b".repeat(40));
        artifact.add("bindings",manager.renderedValidationBindings(manager.instance));
        JsonArray exclusions=new JsonArray();for(int id=0;id<8;id++){exclusions.add("original-"+id);}artifact.add("excludedInstanceUuids",exclusions);
        artifactUuid=UUID.randomUUID().toString();byte[] bytes=artifact.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        new StorageConfigArtifactStore(store).write(artifactUuid,bytes);artifactSha=StorageConfigArchive.sha256(bytes);
    }

    private ConfigureStorageRenderedValidationProfileCmd command(long revision,boolean enabled) {
        ConfigureStorageRenderedValidationProfileCmd cmd=Mockito.mock(ConfigureStorageRenderedValidationProfileCmd.class);
        Mockito.when(cmd.getInstanceId()).thenReturn(9L);Mockito.when(cmd.getEnabled()).thenReturn(enabled);
        Mockito.when(cmd.getExpectedProfileRevision()).thenReturn(revision);Mockito.when(cmd.getConfirmation()).thenReturn("fixture-name");
        Mockito.when(cmd.getArtifactUuid()).thenReturn(artifactUuid);Mockito.when(cmd.getArtifactSha256()).thenReturn(artifactSha);return cmd;
    }
    private JsonObject profile() {return JsonParser.parseString(policy.get()).getAsJsonObject().getAsJsonObject("renderedValidationProfile");}
    private void rejectedConfigure() {
        Assert.assertThrows(CloudRuntimeException.class,()->manager.configureStorageRenderedValidationProfile(command(0,true)));
        Mockito.verify(instances,Mockito.never()).update(Mockito.anyLong(),Mockito.any());Assert.assertEquals(0,manager.guestCalls);
    }

    @After public void cleanup() throws Exception {
        if(transactions!=null){transactions.close();}if(locks!=null){locks.close();}
        if(oldStore==null){System.clearProperty("cloudstack.storage.rendered.validation.path");}else{System.setProperty("cloudstack.storage.rendered.validation.path",oldStore);}
        if(store!=null){try(java.util.stream.Stream<Path> paths=Files.walk(store)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toArray(Path[]::new)){Files.delete(path);}}}
    }

    @Test public void actualConfigureOldDataUsesOwnedKindAndLeavesNewFlagFalse() {
        manager.configureStorageRenderedValidationProfile(command(0,true));
        Assert.assertEquals("OWNED_SPARSE_ALL4_VALIDATION",profile().get("kind").getAsString());
        Assert.assertFalse(profile().get("productionCapability").getAsBoolean());
        Assert.assertFalse(profile().getAsJsonObject("fixtureProvenance").getAsJsonObject("volumes").getAsJsonObject("old-data").get("newDataWithoutBacking").getAsBoolean());
        Assert.assertEquals(8,profile().getAsJsonObject("fixtureProvenance").getAsJsonObject("volumes").getAsJsonObject("old-data").size());
        manager.requiredRenderedValidationProfile(manager.instance);Assert.assertEquals(2,manager.guestCalls);
    }
    @Test public void existingNewPolicyStillRejectsOldData() {
        writeArtifact("NEW_SPARSE_ALL4_VALIDATION");rejectedConfigure();
    }
    @Test public void templateOrChainChangesCannotHideBehindUnchangedEightFieldBinding() {
        manager.configureStorageRenderedValidationProfile(command(0,true));
        Mockito.when(data.getTemplateId()).thenReturn(214L);
        Assert.assertThrows(CloudRuntimeException.class,()->manager.requiredRenderedValidationProfile(manager.instance));
        Mockito.when(data.getTemplateId()).thenReturn(null);Mockito.when(data.getChainInfo()).thenReturn("backing-chain");
        Assert.assertThrows(CloudRuntimeException.class,()->manager.requiredRenderedValidationProfile(manager.instance));
        Assert.assertEquals(1,manager.guestCalls);
    }
    @Test public void foreignDetachedThinAndMissingDataAreRejectedBeforeRuntime() {
        Mockito.when(data.getAccountId()).thenReturn(99L);rejectedConfigure();Mockito.when(data.getAccountId()).thenReturn(2L);
        Mockito.when(data.getInstanceId()).thenReturn(99L);rejectedConfigure();Mockito.when(data.getInstanceId()).thenReturn(54L);
        Mockito.when(data.getProvisioningType()).thenReturn(null);rejectedConfigure();
        Mockito.when(data.getProvisioningType()).thenReturn(ProvisioningType.THIN);rejectedConfigure();Mockito.when(data.getProvisioningType()).thenReturn(ProvisioningType.SPARSE);
        Mockito.when(volumes.findByInstanceAndType(54L,Volume.Type.DATADISK)).thenReturn(List.of());rejectedConfigure();
    }
    @Test public void everyOtherOriginalInstanceMustBeExcludedBeforeRuntime() {
        StorageServiceInstanceVO original=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(original.getId()).thenReturn(40L);Mockito.when(original.getUuid()).thenReturn("missing-original");
        Mockito.when(instances.listAll()).thenReturn(List.of(manager.instance,original));rejectedConfigure();
    }
    @Test public void changedBackingOrPathDuringRuntimeProofCannotReachPersistence() {
        manager.afterStatus=()->Mockito.when(data.getTemplateId()).thenReturn(214L);
        Assert.assertThrows(CloudRuntimeException.class,()->manager.configureStorageRenderedValidationProfile(command(0,true)));
        Mockito.verify(instances,Mockito.never()).update(Mockito.anyLong(),Mockito.any());Assert.assertEquals(1,manager.guestCalls);
        Mockito.when(data.getTemplateId()).thenReturn(null);manager.afterStatus=()->Mockito.when(data.getPath()).thenReturn("replaced-path");
        Assert.assertThrows(CloudRuntimeException.class,()->manager.configureStorageRenderedValidationProfile(command(0,true)));
        Mockito.verify(instances,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
    }
    @Test public void sameRevisionProfileMutationAndRevisionRaceCannotOverwritePolicy() {
        manager.afterStatus=()->{JsonObject base=JsonParser.parseString(policy.get()).getAsJsonObject();JsonObject previous=new JsonObject();previous.addProperty("revision",0);previous.addProperty("baselineImported",true);base.addProperty("schemaVersion",1);base.addProperty("instanceUuid","owned-fixture");base.addProperty("enabled",false);base.add("renderedValidationProfile",previous);policy.set(base.toString());};
        Assert.assertThrows(CloudRuntimeException.class,()->manager.configureStorageRenderedValidationProfile(command(0,true)));
        Mockito.verify(instances,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        manager.afterStatus=()->{};policy.set("{}");
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class,()->manager.configureStorageRenderedValidationProfile(command(2,true)));
    }
    @Test public void disableKeepsOwnedKindAndImportedBaselineCannotBeDisabled() {
        manager.configureStorageRenderedValidationProfile(command(0,true));manager.configureStorageRenderedValidationProfile(command(1,false));
        Assert.assertEquals("OWNED_SPARSE_ALL4_VALIDATION",profile().get("kind").getAsString());Assert.assertFalse(profile().get("enabled").getAsBoolean());
        JsonObject base=JsonParser.parseString(policy.get()).getAsJsonObject();base.getAsJsonObject("renderedValidationProfile").addProperty("baselineImported",true);policy.set(base.toString());
        Assert.assertThrows(CloudRuntimeException.class,()->manager.configureStorageRenderedValidationProfile(command(2,false)));
    }
    @Test public void storedUnknownOrDifferentKindCannotReachRuntimeOnReuse() {
        manager.configureStorageRenderedValidationProfile(command(0,true));
        for (String kind:new String[]{"UNKNOWN_VALIDATION","NEW_SPARSE_ALL4_VALIDATION"}) {
            JsonObject base=JsonParser.parseString(policy.get()).getAsJsonObject();base.getAsJsonObject("renderedValidationProfile").addProperty("kind",kind);policy.set(base.toString());
            Assert.assertThrows(CloudRuntimeException.class,()->manager.requiredRenderedValidationProfile(manager.instance));
        }
        Assert.assertEquals(1,manager.guestCalls);
    }

    @Test public void recentNewPolicyKeepsOriginalAgeAdmissionAndKind() {
        Mockito.when(data.getCreated()).thenReturn(new Date());
        writeArtifact("NEW_SPARSE_ALL4_VALIDATION");manager.configureStorageRenderedValidationProfile(command(0,true));
        Assert.assertEquals("NEW_SPARSE_ALL4_VALIDATION",profile().get("kind").getAsString());
        Assert.assertTrue(profile().getAsJsonObject("fixtureProvenance").getAsJsonObject("volumes").getAsJsonObject("old-data").get("newDataWithoutBacking").getAsBoolean());
        manager.requiredRenderedValidationProfile(manager.instance);Assert.assertEquals(2,manager.guestCalls);
    }

}
