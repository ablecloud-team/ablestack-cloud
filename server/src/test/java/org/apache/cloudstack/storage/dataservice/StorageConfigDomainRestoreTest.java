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
}
