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
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.cloud.exception.InvalidParameterValueException;

public class StorageConfigDomainRestoreTest {
    private JsonObject plan(JsonObject config) {
        JsonObject row = new JsonObject();row.addProperty("uuid", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");row.addProperty("protocol", "SMB");row.addProperty("name", "reviewed");row.add("config", config);
        JsonObject change = new JsonObject();change.addProperty("kind", "file-shares");change.addProperty("sourceUuid", row.get("uuid").getAsString());change.add("desired", row);
        JsonArray creates = new JsonArray();creates.add(change);JsonObject plan = new JsonObject();plan.add("create", creates);plan.add("update", new JsonArray());plan.add("keep", new JsonArray());return plan;
    }
    @Test public void unknownOrWrongTypedLateResourceIsRejectedWithoutAnyManagerMutation() {
        StorageServiceManagerImpl manager = Mockito.mock(StorageServiceManagerImpl.class);StorageConfigDomainRestore restore = new StorageConfigDomainRestore(manager);
        JsonObject config = new JsonObject();config.addProperty("executeScript", "untrusted");
        Assert.assertThrows(InvalidParameterValueException.class, () -> restore.validateBindings(plan(config)));
        config.remove("executeScript");config.addProperty("guestOk", "true");
        Assert.assertThrows(InvalidParameterValueException.class, () -> restore.validateBindings(plan(config)));Mockito.verifyNoInteractions(manager);
    }
    @Test public void safeRestoreBindingsNeverNeedAFormatOrCleanupFlag() {
        StorageServiceManagerImpl manager = Mockito.mock(StorageServiceManagerImpl.class);
        JsonObject config = new JsonObject();config.addProperty("guestOk", false);config.addProperty("relativeSharePath", "smb/reviewed");
        new StorageConfigDomainRestore(manager).validateBindings(plan(config));Mockito.verifyNoInteractions(manager);
    }
    @Test public void legacyDirectoryBindingMatchesExistingRendererAndRejectsUntrustedRoots() {
        JsonObject desired=new JsonObject();desired.addProperty("path","/export/legacy-share");desired.add("config",new JsonObject());
        Assert.assertEquals("export/legacy-share",StorageConfigDomainRestore.directoryRelativePath("file-shares",desired));
        desired.getAsJsonObject("config").addProperty("relativeSharePath","smb/current");
        Assert.assertEquals("smb/current",StorageConfigDomainRestore.directoryRelativePath("file-shares",desired));
        desired.getAsJsonObject("config").remove("relativeSharePath");
        for(String path:new String[]{"/etc/shadow","/export/../outside","/export//ambiguous"}) {
            desired.addProperty("path",path);
            Assert.assertThrows(InvalidParameterValueException.class,()->StorageConfigDomainRestore.directoryRelativePath("file-shares",desired));
        }
    }

    private JsonObject authenticatedPlan(){
        JsonObject plan=new JsonObject();for(String action:new String[]{"create","update","keep","blockers"})plan.add(action,new JsonArray());plan.addProperty("targetInstanceUuid","11111111-1111-1111-1111-111111111111");plan.addProperty("targetMode","RESTORE_EXISTING");plan.add("volumeMappings",new JsonObject());plan.add("adIdentitySourceDescriptor",new JsonObject());
        JsonObject domain=new JsonObject();domain.addProperty("uuid","22222222-2222-2222-2222-222222222222");domain.addProperty("domain_name","example.test");domain.add("config",new JsonObject());
        JsonObject domainChange=new JsonObject();domainChange.addProperty("kind","identity-domain");domainChange.addProperty("action","KEEP");domainChange.addProperty("sourceUuid",domain.get("uuid").getAsString());domainChange.addProperty("targetUuid",domain.get("uuid").getAsString());domainChange.add("desired",domain);plan.getAsJsonArray("keep").add(domainChange);
        JsonObject policy=new JsonObject();policy.addProperty("uuid","33333333-3333-3333-3333-333333333333");policy.addProperty("relative_path","data");policy.add("config",new JsonObject());
        JsonObject policyChange=new JsonObject();policyChange.addProperty("kind","posix-directory-policies");policyChange.addProperty("action","UPDATE");policyChange.addProperty("sourceUuid",policy.get("uuid").getAsString());policyChange.addProperty("targetUuid",policy.get("uuid").getAsString());policyChange.add("desired",policy);plan.getAsJsonArray("update").add(policyChange);
        return plan;
    }
    @Test public void realRestoreEntryAppliesVerifiedDomainBeforeAnyPosixDomainCommand(){
        StorageServiceManagerImpl manager=Mockito.mock(StorageServiceManagerImpl.class);StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");
        Mockito.when(manager.configurationResourceIds(Mockito.anyLong())).thenReturn(java.util.Map.of("22222222-2222-2222-2222-222222222222",2L,"33333333-3333-3333-3333-333333333333",3L));
        new StorageConfigDomainRestore(manager).apply(instance,authenticatedPlan(),new JsonObject());
        org.mockito.InOrder order=Mockito.inOrder(manager);order.verify(manager).beginConfigurationBatch(Mockito.anyLong());order.verify(manager).restoreAdSemanticDomain(Mockito.eq(instance),Mockito.any(),Mockito.any(),Mockito.any());order.verify(manager).invokeConfigurationDomainCommand(Mockito.any(),Mockito.eq("executeStoragePosixDirectoryPolicy"));order.verify(manager).finishConfigurationBatch(instance);
    }
    @Test public void domainRestoreFaultCannotReachPosixOrShareApplication(){
        StorageServiceManagerImpl manager=Mockito.mock(StorageServiceManagerImpl.class);StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");
        Mockito.when(manager.configurationResourceIds(Mockito.anyLong())).thenReturn(java.util.Map.of("22222222-2222-2222-2222-222222222222",2L,"33333333-3333-3333-3333-333333333333",3L));
        Mockito.doThrow(new InvalidParameterValueException("Unverified domain identity")).when(manager).restoreAdSemanticDomain(Mockito.any(),Mockito.any(),Mockito.any(),Mockito.any());
        Assert.assertThrows(RuntimeException.class,()->new StorageConfigDomainRestore(manager).apply(instance,authenticatedPlan(),new JsonObject()));Mockito.verify(manager,Mockito.never()).invokeConfigurationDomainCommand(Mockito.any(),Mockito.anyString());Mockito.verify(manager).abortConfigurationBatch();
    }

    private static final class LocalAclManager extends StorageServiceManagerImpl {
        StorageFileShareVO share;StorageServiceInstanceVO instance;java.util.Map<Long,String> passwords;
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected StorageFileShareVO requireSmbShare(Long id) {return share;}
        @Override protected StorageServiceInstanceVO requireInstance(Long id) {return instance;}
        @Override protected JsonObject resolveStorageAdPrincipal(StorageServiceInstanceVO value,StorageServiceInstance.PrincipalType type,String name) {return null;}
        @Override protected void applySmbDesiredState(StorageServiceInstanceVO value,java.util.Map<Long,String> secrets) {passwords=secrets;}
        @Override protected org.apache.cloudstack.api.response.StorageAccessRuleResponse createAclResponse(StorageAccessRuleVO rule) {return new org.apache.cloudstack.api.response.StorageAccessRuleResponse();}
    }
    @SuppressWarnings("unchecked")
    @Test public void actualLocalAclCreateConsumesImportedAccountWithoutPasswordRegeneration() throws Exception {
        LocalAclManager manager=new LocalAclManager();manager.instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(manager.instance.getId()).thenReturn(7L);manager.share=Mockito.mock(StorageFileShareVO.class);Mockito.when(manager.share.getId()).thenReturn(8L);Mockito.when(manager.share.getInstanceId()).thenReturn(7L);Mockito.when(manager.share.getConfigJson()).thenReturn("{}");
        org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao rules=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class);org.springframework.test.util.ReflectionTestUtils.setField(manager,"storageAccessRuleDao",rules);java.util.List<StorageAccessRuleVO> persisted=new java.util.ArrayList<>();Mockito.when(rules.persist(Mockito.any())).thenAnswer(call->{StorageAccessRuleVO rule=call.getArgument(0);persisted.add(rule);return rule;});
        JsonObject authority=new JsonObject(),descriptor=new JsonObject();descriptor.addProperty("ownerArtifactUuid","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");authority.add("descriptor",descriptor);((ThreadLocal<JsonObject>)org.springframework.test.util.ReflectionTestUtils.getField(manager,"protectedAdSemanticSource")).set(authority);manager.beginConfigurationBatch(7L);
        StorageServiceManagerImpl.ConfigurationBatch batch=((ThreadLocal<StorageServiceManagerImpl.ConfigurationBatch>)org.springframework.test.util.ReflectionTestUtils.getField(manager,"configurationBatch")).get();batch.semanticLocalReceipt=new JsonObject();batch.semanticLocalReceipt.add("scope",new JsonObject());batch.semanticLocalReceipt.add("publicMappings",com.google.gson.JsonParser.parseString("[{\"name\":\"alice\",\"uid\":1001,\"gid\":1002,\"rid\":1003,\"userSid\":\"S-1-5-21-4-5-6-1003\"}]").getAsJsonArray());batch.semanticLocalReceipt.add("publicGroupMappings",new JsonArray());
        JsonObject parameters=new JsonObject();parameters.addProperty("shareid",8);parameters.addProperty("principaltype","LOCAL_USER");parameters.addProperty("principal","alice");parameters.addProperty("permission","READ_WRITE");org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd command=(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd.class,parameters);
        java.lang.reflect.Method create=StorageServiceManagerImpl.class.getDeclaredMethod("doCreateStorageSmbAcl",org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd.class);create.setAccessible(true);create.invoke(manager,command);
        Assert.assertEquals(1,persisted.size());Assert.assertTrue(manager.passwords.isEmpty());JsonObject config=com.google.gson.JsonParser.parseString(persisted.get(0).getConfigJson()).getAsJsonObject();Assert.assertFalse(config.get("passwordSupplied").getAsBoolean());Assert.assertTrue(config.getAsJsonObject("localSemanticIdentityReceipt").get("identityRestored").getAsBoolean());Assert.assertEquals("alice",config.getAsJsonObject("localSemanticIdentityReceipt").getAsJsonObject("mapping").get("name").getAsString());
        parameters.addProperty("password","synthetic-replacement-forbidden");org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd replacement=(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd.class,parameters);Assert.assertThrows(java.lang.reflect.InvocationTargetException.class,()->create.invoke(manager,replacement));Assert.assertEquals(1,persisted.size());manager.abortConfigurationBatch();
    }
    @Test public void localCredentialReplacementIsRejectedAtRestoreEntryBeforeAnyManagerEffect() {
        StorageServiceManagerImpl manager=Mockito.mock(StorageServiceManagerImpl.class);StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");JsonObject plan=authenticatedPlan(),acl=new JsonObject(),change=new JsonObject();acl.addProperty("uuid","44444444-4444-4444-4444-444444444444");acl.addProperty("principal_type","LOCAL_USER");acl.addProperty("principal","alice");change.addProperty("kind","access-rules");change.add("desired",acl);plan.getAsJsonArray("create").add(change);JsonObject credentials=new JsonObject(),password=new JsonObject();password.addProperty("password","synthetic-forbidden");credentials.add(acl.get("uuid").getAsString(),password);
        Assert.assertThrows(InvalidParameterValueException.class,()->new StorageConfigDomainRestore(manager).apply(instance,plan,credentials));Mockito.verifyNoInteractions(manager);
    }
    private static class ConsentManager extends StorageServiceManagerImpl {
        @Override protected void requireStorageAdIdentityFeatures(StorageServiceInstanceVO instance) { }
    }
    @Test public void commonJoinedWriterConsentRequiresExactInstanceAndCannotUseDeleteConfirmation() {
        ConsentManager manager=new ConsentManager();StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getName()).thenReturn("fixture");JsonObject parameters=new JsonObject();parameters.addProperty("shareid",8);parameters.addProperty("principal","alice");parameters.addProperty("principaltype","LOCAL_USER");parameters.addProperty("permission","READ_WRITE");org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd missing=(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd.class,parameters);Assert.assertThrows(InvalidParameterValueException.class,()->manager.requireJoinedAdWriterApproval(instance,missing));
        parameters.addProperty("admaintenancewindow",true);parameters.addProperty("adconfirmation","fixture");org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd approved=(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd.class,parameters);manager.requireJoinedAdWriterApproval(instance,approved);Assert.assertEquals(Boolean.TRUE,approved.getAdMaintenanceWindow());parameters.addProperty("adconfirmation","another-fixture");org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd wrong=(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd)StorageConfigCommandBinding.bind(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbAclCmd.class,parameters);Assert.assertThrows(InvalidParameterValueException.class,()->manager.requireJoinedAdWriterApproval(instance,wrong));
    }
    @Test public void actualBundleManifestProducerBuildCommitAndCliHashBecomeSealedClonePin() throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(!java.nio.file.Files.exists(root.resolve("tools/build/build-storage-runtime-bundle.sh")))root=root.getParent();
        java.nio.file.Path stage=java.nio.file.Files.createTempDirectory("runtime-pin-producer-");
        java.nio.file.Files.writeString(stage.resolve("ablestack-storagectl"),"public-cli-fixture");
        java.nio.file.Path output=stage.resolveSibling(stage.getFileName()+"-manifest.json");
        String body=java.nio.file.Files.readString(root.resolve("tools/build/build-storage-runtime-bundle.sh")).split("<<'PY'\n",2)[1].split("\nPY",2)[0];
        Process process=new ProcessBuilder("python3","-c",body,stage.toString(),output.toString(),"fixture","1","1","NONE","synthetic-key","a".repeat(40),"fixture-time").start();
        Assert.assertEquals(0,process.waitFor());
        JsonObject manifest=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(output)).getAsJsonObject();
        Assert.assertEquals("a".repeat(40),manifest.get("buildCommit").getAsString());
        Assert.assertFalse(manifest.has("sourceCommit"));
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao bundles=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao.class);
        StorageServiceRuntimeUpgradeManager runtime=Mockito.mock(StorageServiceRuntimeUpgradeManager.class);
        StorageServiceRuntimeBundleVO bundle=Mockito.mock(StorageServiceRuntimeBundleVO.class);
        Mockito.when(bundle.getId()).thenReturn(9L);
        Mockito.when(bundle.getUuid()).thenReturn("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        Mockito.when(bundle.getSha256()).thenReturn("b".repeat(64));
        Mockito.when(bundle.getManifestSha256()).thenReturn("c".repeat(64));
        Mockito.when(bundles.findByUuid(bundle.getUuid())).thenReturn(bundle);
        JsonObject verified=new JsonObject();
        verified.addProperty("verified",true);
        verified.add("manifest",manifest);
        Mockito.when(runtime.verifyAvailableBundle(9L)).thenReturn(verified);
        org.springframework.test.util.ReflectionTestUtils.setField(manager,"storageRuntimeBundleDao",bundles);
        org.springframework.test.util.ReflectionTestUtils.setField(manager,"runtimeUpgradeManager",runtime);
        JsonObject pin=manager.configurationCloneRuntimePin(bundle.getUuid());
        Assert.assertEquals("a".repeat(40),pin.get("sourceCommit").getAsString());
        Assert.assertEquals(StorageConfigArchive.sha256("public-cli-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)),pin.get("expectedCliSha256").getAsString());
        manifest.addProperty("buildCommit","unverified");
        Assert.assertThrows(RuntimeException.class,()->manager.configurationCloneRuntimePin(bundle.getUuid()));
        java.nio.file.Files.delete(output);
        java.nio.file.Files.delete(stage.resolve("ablestack-storagectl"));
        java.nio.file.Files.delete(stage);
    }
    private static final class FoundationManager extends StorageServiceManagerImpl {
        java.util.List<String> events=new java.util.ArrayList<>();boolean fail;
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected void requireNoPendingVolumeFormatter(StorageServiceInstanceVO instance) { }
        @Override protected long rootDesiredRevision(long instanceId) {return 0;}
        @Override protected void beginStorageWriterHeartbeat(StorageServiceOperationVO operation) {events.add("writer");}
        @Override protected void endStorageWriterHeartbeat() {events.add("end");}
        @Override protected StorageServiceOperationControlVO startOperationControl(StorageServiceInstanceVO instance,StorageServiceOperationVO operation) {return null;}
        @Override protected boolean operationControlEnabled(StorageServiceInstanceVO instance) {return false;}
        @Override protected void prepareConfigurationInitialVolume(StorageServiceInstanceVO instance,JsonObject blueprint) {events.add("initial");}
        @Override protected JsonObject prepareConfigurationVolumeAllocations(StorageServiceInstanceVO instance,StorageConfigArtifactVO artifact,JsonObject plan) {events.add("additional");if(fail)throw new InvalidParameterValueException("New allocation response lost");return new JsonObject();}
        @Override protected void releaseOperationResourceReservation(StorageServiceInstanceVO instance,StorageServiceOperationVO operation) {events.add("release");}
    }
    @Test public void foundationPreparesOnlyReviewedNewTargetAndCannotAutomaticallyRepeatFailedPhysicalWork() {
        FoundationManager manager=new FoundationManager();StorageServiceInstanceVO target=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(target.getId()).thenReturn(11L);Mockito.when(target.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");Mockito.when(target.getCurrentRuntimeBundleId()).thenReturn(9L);StorageConfigArtifactVO artifact=new StorageConfigArtifactVO();artifact.setInstanceId(7L);artifact.setSha256("a".repeat(64));JsonObject metadata=new JsonObject();metadata.addProperty("createdTargetInstanceUuid",target.getUuid());metadata.addProperty("runtimePreparedBundleUuid","22222222-2222-4222-8222-222222222222");artifact.setMetadataJson(metadata.toString());JsonObject plan=new JsonObject();plan.addProperty("targetMode","CREATE_NEW");plan.add("runtimeBundleUuid",metadata.get("runtimePreparedBundleUuid").deepCopy());plan.add("volumeAllocationPlan",new JsonObject());plan.add("createNew",new JsonObject());
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao bundles=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao.class);
        StorageServiceRuntimeBundleVO bundle=Mockito.mock(StorageServiceRuntimeBundleVO.class);
        Mockito.when(bundle.getId()).thenReturn(9L);
        Mockito.when(bundles.findByUuid(metadata.get("runtimePreparedBundleUuid").getAsString())).thenReturn(bundle);
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
        org.springframework.test.util.ReflectionTestUtils.setField(manager,"storageRuntimeBundleDao",bundles);
        org.springframework.test.util.ReflectionTestUtils.setField(manager,"storageOperationDao",operations);
        java.util.List<StorageServiceOperationVO> rows=new java.util.ArrayList<>();
        Mockito.when(operations.persist(Mockito.any())).thenAnswer(call->{StorageServiceOperationVO row=call.getArgument(0);rows.add(row);return row;});
        Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        com.cloud.user.User user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(3L);org.apache.cloudstack.context.CallContext.register(user,Mockito.mock(com.cloud.user.Account.class));try{
            manager.fail=true;Assert.assertThrows(RuntimeException.class,()->manager.prepareConfigurationCloneFoundation(target,artifact,plan));Assert.assertEquals("RECOVERY_REQUIRED",rows.get(0).getState());Assert.assertEquals(java.util.List.of("writer","initial","additional","end"),manager.events);Mockito.when(operations.findByRequest(Mockito.eq(11L),Mockito.anyString())).thenReturn(rows.get(0));manager.events.clear();Assert.assertThrows(RuntimeException.class,()->manager.prepareConfigurationCloneFoundation(target,artifact,plan));Assert.assertTrue(manager.events.isEmpty());
            metadata.addProperty("createdTargetInstanceUuid","33333333-3333-4333-8333-333333333333");artifact.setMetadataJson(metadata.toString());Assert.assertThrows(RuntimeException.class,()->manager.prepareConfigurationCloneFoundation(target,artifact,plan));Assert.assertEquals(1,rows.size());
        }finally{org.apache.cloudstack.context.CallContext.unregister();}
    }
    @Test public void cloneProfileCannotAuthorizeDifferentRuntimeOrMissingNormalTransaction() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();JsonObject pin=new JsonObject();pin.addProperty("bundleUuid","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");pin.addProperty("bundleSha256","a".repeat(64));pin.addProperty("manifestSha256","b".repeat(64));pin.addProperty("expectedCliSha256","c".repeat(64));JsonObject actual=new JsonObject();actual.addProperty("bundleUuid","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");actual.addProperty("archiveSha256","a".repeat(64));actual.addProperty("manifestSha256","b".repeat(64));JsonObject verified=new JsonObject();verified.add("runtimePin",actual);verified.addProperty("actualCliSha256","c".repeat(64));verified.addProperty("transactionId","normal-transaction");for(String field:java.util.Set.of("readOnly","signedRuntimeVerified","nativeFileHashesVerified"))verified.addProperty(field,true);manager.requireCloneRuntimeReadback(verified,pin);
        for(String field:java.util.Set.of("readOnly","signedRuntimeVerified","nativeFileHashesVerified","transactionId","actualCliSha256")){JsonObject wrong=verified.deepCopy();if(field.equals("transactionId"))wrong.remove(field);else if(field.equals("actualCliSha256"))wrong.addProperty(field,"d".repeat(64));else wrong.addProperty(field,"true");Assert.assertThrows(RuntimeException.class,()->manager.requireCloneRuntimeReadback(wrong,pin));}JsonObject wrong=verified.deepCopy();wrong.getAsJsonObject("runtimePin").addProperty("manifestSha256","d".repeat(64));Assert.assertThrows(RuntimeException.class,()->manager.requireCloneRuntimeReadback(wrong,pin));
    }
    @Test public void cloneProfileRequiresExactRealizedDataUnionAndRejectsForeignOrExistingMembers() {
        StorageServiceManagerImpl manager=new StorageServiceManagerImpl();
        String target="11111111-1111-4111-8111-111111111111",initial="22222222-2222-4222-8222-222222222222",additional="33333333-3333-4333-8333-333333333333";
        JsonObject plan=new JsonObject(),allocation=new JsonObject(),scope=new JsonObject(),mappings=new JsonObject(),blueprint=new JsonObject();
        scope.addProperty("targetInstanceUuid",target);
        allocation.add("scope",scope);
        mappings.addProperty("source-initial",initial);
        mappings.addProperty("source-additional",additional);
        allocation.add("volumeMappings",mappings);
        JsonArray rows=new JsonArray();
        JsonObject row=new JsonObject();
        row.addProperty("mode","NEW");
        row.addProperty("plannedUuid",additional);
        rows.add(row);
        allocation.add("allocations",rows);
        allocation.addProperty("planSha256",StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(allocation)));
        plan.add("volumeAllocationPlan",allocation);
        blueprint.addProperty("backingvolumemode","NEW");
        plan.add("createNew",blueprint);
        JsonObject binding=new JsonObject(),volumes=new JsonObject();
        for(String id:new String[]{initial,additional}){JsonObject disk=new JsonObject();
        disk.addProperty("type","DATADISK");
        volumes.add(id,disk);
        }binding.add("volumes",volumes);
        manager.requireCloneProfileDataSet(target,plan,binding,initial);
        volumes.remove(additional);Assert.assertThrows(RuntimeException.class,()->manager.requireCloneProfileDataSet(target,plan,binding,initial));volumes.add(additional,new JsonObject());volumes.getAsJsonObject(additional).addProperty("type","DATADISK");blueprint.addProperty("backingvolumemode","EXISTING");Assert.assertThrows(RuntimeException.class,()->manager.requireCloneProfileDataSet(target,plan,binding,initial));blueprint.addProperty("backingvolumemode","NEW");Assert.assertThrows(RuntimeException.class,()->manager.requireCloneProfileDataSet("foreign-target",plan,binding,initial));
    }
}
