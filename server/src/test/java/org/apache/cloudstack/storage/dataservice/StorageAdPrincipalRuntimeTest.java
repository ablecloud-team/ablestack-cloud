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

import java.util.List;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageAdPrincipalRuntimeTest {
    private static class Manager extends StorageServiceManagerImpl {
        JsonObject identity,resolved,nativeBefore,nativeAfter;List<String> commands=new ArrayList<>();boolean denyFeature,freshScope;int generationCalls;StorageServiceInstanceVO currentInstance;
        @Override protected StorageServiceInstanceVO requireInstance(Long id){return currentInstance;}
        @Override protected void requireStorageAdIdentityFeatures(StorageServiceInstanceVO instance){if(denyFeature)throw new com.cloud.utils.exception.CloudRuntimeException("AD unsupported");}
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){Assert.assertEquals("status",action);Assert.assertNull(operation);return (++generationCalls==1?nativeBefore:nativeAfter).deepCopy();}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){commands.add(command);if(command.equals("identity domain inspect")){JsonObject observed=identity.deepCopy();if(freshScope){JsonObject scope=new JsonObject();for(String key:List.of("instanceUuid","operationUuid","revision"))scope.add(key,request.get(key).deepCopy());observed.add("scope",scope);}return observed;}Assert.assertEquals(identity.get("domainSid"),request.get("expectedDomainSid"));Assert.assertEquals(identity.get("realm"),request.get("expectedRealm"));return resolved.deepCopy();}
    }
    private static class Fixture {Manager manager;StorageServiceInstanceVO instance;StorageServiceOperationVO operation;org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao rules;}
    private Fixture fixture() {
        Fixture f=new Fixture();f.manager=new Manager();f.instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(f.instance.getId()).thenReturn(6L);Mockito.when(f.instance.getVmId()).thenReturn(7L);Mockito.when(f.instance.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");f.operation=new StorageServiceOperationVO();f.operation.setRevision(3);ThreadLocal<StorageServiceOperationVO> writer=(ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(f.manager,"storageWriterOperation");writer.set(f.operation);
        JsonObject scope=new JsonObject();
        scope.addProperty("instanceUuid",f.instance.getUuid());
        scope.addProperty("operationUuid",f.operation.getUuid());
        scope.addProperty("revision",3);
        JsonObject identity=new JsonObject();
        identity.addProperty("success",true);
        identity.addProperty("sideEffects",false);
        identity.add("scope",scope);
        identity.addProperty("joinState","JOINED");
        identity.addProperty("domain","example.test");
        identity.addProperty("realm","EXAMPLE.TEST");
        identity.addProperty("workgroup","EXAMPLE");
        identity.addProperty("netbiosName","ASTINSTANCE");
        identity.addProperty("domainSid","S-1-5-21-1-2-3");
        identity.addProperty("machineSid","S-1-5-21-4-5-6");
        identity.addProperty("machineAccountSid","S-1-5-21-1-2-3-1001");
        for(String field:List.of("trustVerified","identityVerified","dnsAliasesVerified","adSpnsVerified","adIdentity"))identity.addProperty(field,true);
        identity.addProperty("bootId","22222222-2222-2222-2222-222222222222");
        identity.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
        JsonObject idmap=com.google.gson.JsonParser.parseString("{\"default\":{\"backend\":\"tdb\",\"range\":[10000,60000]},\"domain\":{\"backend\":\"rid\",\"range\":[1000000,1999999],\"baseRid\":0}}").getAsJsonObject();
        identity.add("idmapPolicy",idmap);
        JsonArray spns=new JsonArray();
        spns.add("cifs/storage.example.test");spns.add("host/storage.example.test");
        identity.add("servicePrincipals",spns);
        JsonArray aliases=new JsonArray();
        JsonObject alias=new JsonObject();
        alias.addProperty("hostname","storage.example.test");
        JsonArray ips=new JsonArray();
        ips.add("10.10.13.240");
        alias.add("addresses",ips);
        aliases.add(alias);
        identity.add("dnsAliases",aliases);
        f.manager.identity=identity;

        JsonObject resolved=new JsonObject();for(String field:List.of("success","scope","sideEffects","bootId","generatedEpoch","domainSid","realm","workgroup"))resolved.add(field,identity.get(field).deepCopy());resolved.addProperty("qualifiedName","EXAMPLE\\alice");resolved.addProperty("sid","S-1-5-21-1-2-3-1201");resolved.addProperty("principalType","AD_USER");resolved.addProperty("sidType",1);resolved.addProperty("sidTypeName","SID_USER");resolved.addProperty("numericId",1001201);resolved.addProperty("kind","u");resolved.addProperty("mappingVerified",true);resolved.addProperty("reverseVerified",true);f.manager.resolved=resolved;
        JsonObject config=new JsonObject();config.add("identityReceipt",identity.deepCopy());StorageIdentityDomainVO domain=new StorageIdentityDomainVO(6,"example.test",null,null,StorageServiceInstance.DomainJoinState.JOINED,"OK",config.toString());org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao domains=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao.class);Mockito.when(domains.findByInstanceId(6L)).thenReturn(domain);ReflectionTestUtils.setField(f.manager,"storageIdentityDomainDao",domains);f.rules=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class);ReflectionTestUtils.setField(f.manager,"storageAccessRuleDao",f.rules);return f;
    }
    @Test public void freshNativeJoinedAndPrincipalReceiptsProduceBoundNonsecretAclConfig(){Fixture f=fixture();JsonObject resolved=f.manager.resolveStorageAdPrincipal(f.instance,StorageServiceInstance.PrincipalType.AD_USER,"EXAMPLE\\alice");Assert.assertEquals(f.manager.resolved,resolved);Assert.assertEquals(List.of("identity domain inspect","identity principal resolve"),f.manager.commands);JsonObject config=com.google.gson.JsonParser.parseString(f.manager.buildBoundSmbAclConfig(StorageServiceInstance.PrincipalType.AD_USER,null,resolved)).getAsJsonObject();Assert.assertEquals(resolved,config.get("adPrincipalReceipt"));Assert.assertFalse(config.toString().contains("password\":\""));Mockito.verifyNoInteractions(f.rules);}
    @Test public void changedMachineOrReverseMappingRejectsBeforeAnyAclPersistence(){Fixture f=fixture();f.manager.identity.addProperty("machineAccountSid","S-1-5-21-1-2-3-9999");Fixture changed=f;Assert.assertThrows(RuntimeException.class,()->changed.manager.resolveStorageAdPrincipal(changed.instance,StorageServiceInstance.PrincipalType.AD_USER,"EXAMPLE\\alice"));Assert.assertFalse(f.manager.commands.contains("identity principal resolve"));Mockito.verifyNoInteractions(f.rules);f=fixture();f.manager.resolved.addProperty("reverseVerified",false);Fixture wrong=f;Assert.assertThrows(RuntimeException.class,()->wrong.manager.resolveStorageAdPrincipal(wrong.instance,StorageServiceInstance.PrincipalType.AD_USER,"EXAMPLE\\alice"));Mockito.verifyNoInteractions(f.rules);}
    @Test public void unsupportedFeatureAndLocalPrincipalCannotInvokeAdOrMutateAcl(){Fixture f=fixture();f.manager.denyFeature=true;Assert.assertThrows(RuntimeException.class,()->f.manager.resolveStorageAdPrincipal(f.instance,StorageServiceInstance.PrincipalType.AD_USER,"EXAMPLE\\alice"));Assert.assertTrue(f.manager.commands.isEmpty());Assert.assertNull(f.manager.resolveStorageAdPrincipal(f.instance,StorageServiceInstance.PrincipalType.LOCAL_USER,"alice"));Assert.assertTrue(f.manager.commands.isEmpty());Mockito.verifyNoInteractions(f.rules);}
    @Test public void ordinaryProductionGateRejectsHandlerOnlyAndStringBooleanBeforeAdMutation() {
        for(String state:List.of("handler","string","production")){StorageServiceManagerImpl manager=new StorageServiceManagerImpl(){@Override public java.util.Set<String> scopedValidatedRuntimeFeatures(long id,java.util.Set<String> required){return required;
        }};
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getVmId()).thenReturn(7L);
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        JsonObject cap=new JsonObject();
        cap.addProperty("adIdentity",state.equals("production"));
        cap.addProperty("productionAdIdentityVerified",state.equals("production"));
        if(state.equals("string"))cap.addProperty("adIdentity","true");
        JsonArray features=new JsonArray();
        if(state.equals("production"))for(String feature:List.of("SMB_ACTIVE_DIRECTORY","SMB_AD_IDENTITY","POSIX_AD_PRINCIPALS"))features.add(feature);
        else features.add("SMB_AD_IDENTITY_HANDLER");
        cap.add("supportedFeatures",features);
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);
        Assert.assertEquals("",command.getPayload());
        Assert.assertEquals("identity domain capabilities",command.getOperation());
        return new StorageServiceGuestCommandResult(true,"cap",cap.toString());
        });
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
        if(state.equals("production"))manager.requireStorageAdIdentityFeatures(instance);
        else Assert.assertThrows(RuntimeException.class,()->manager.requireStorageAdIdentityFeatures(instance));
        }
    }
    @Test public void joinedMachineNameUsesExactReceiptAndNeverRegeneratesACloneName(){Fixture f=fixture();Assert.assertEquals("ASTINSTANCE",f.manager.buildSmbNetbiosName(f.instance));StorageIdentityDomainVO domain=new StorageIdentityDomainVO(6,"example.test",null,null,StorageServiceInstance.DomainJoinState.JOINED,"OK","{}");org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao domains=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao.class);Mockito.when(domains.findByInstanceId(6L)).thenReturn(domain);ReflectionTestUtils.setField(f.manager,"storageIdentityDomainDao",domains);Assert.assertThrows(RuntimeException.class,()->f.manager.buildSmbNetbiosName(f.instance));domain.setJoinState(StorageServiceInstance.DomainJoinState.NOT_JOINED);Assert.assertTrue(f.manager.buildSmbNetbiosName(f.instance).startsWith("STOR"));}
    private Fixture freshFixture(){
        Fixture f=fixture();f.manager.freshScope=true;
        ThreadLocal<StorageServiceOperationVO> writer=(ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(f.manager,"storageWriterOperation");writer.remove();
        JsonObject generation=new JsonObject();generation.addProperty("instanceUuid",f.instance.getUuid());generation.addProperty("revision",3);
        JsonObject status=new JsonObject();status.add("generation",generation);status.addProperty("generationStatus","IN_SYNC");status.addProperty("configurationSha256","a".repeat(64));status.add("bootId",f.manager.identity.get("bootId").deepCopy());
        f.manager.nativeBefore=status;f.manager.nativeAfter=status.deepCopy();f.manager.currentInstance=f.instance;return f;
    }
    private StorageIdentityDomainVO domain(Fixture f){return ((org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao)ReflectionTestUtils.getField(f.manager,"storageIdentityDomainDao")).findByInstanceId(6L);}
    @Test public void freshStandaloneReadReturnsNewObservationScopeWithoutWriterOrDbMutation(){
        Fixture f=freshFixture();JsonObject receipt=f.manager.freshStorageAdDomainReceipt(f.instance,domain(f));
        Assert.assertEquals(f.instance.getUuid(),receipt.getAsJsonObject("scope").get("instanceUuid").getAsString());
        Assert.assertNotEquals(f.operation.getUuid(),receipt.getAsJsonObject("scope").get("operationUuid").getAsString());
        Assert.assertEquals(f.manager.identity.get("bootId"),receipt.get("bootId"));Assert.assertEquals(2,f.manager.generationCalls);Assert.assertEquals(List.of("identity domain inspect"),f.manager.commands);
        org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao domains=(org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao)ReflectionTestUtils.getField(f.manager,"storageIdentityDomainDao");
        Mockito.verify(domains,Mockito.never()).update(Mockito.anyLong(),Mockito.any());Mockito.verifyNoInteractions(f.rules);
    }
    @Test public void freshReadRejectsPendingGenerationWrongInstanceAndUnavailableBootBeforeInspect(){
        for(String field:List.of("pendingOperationUuid","instanceUuid","bootId","revision")){
            Fixture f=freshFixture();
            if(field.equals("pendingOperationUuid"))f.manager.nativeBefore.addProperty(field,"44444444-4444-4444-4444-444444444444");
            else if(field.equals("instanceUuid"))f.manager.nativeBefore.getAsJsonObject("generation").addProperty(field,"44444444-4444-4444-4444-444444444444");
            else if(field.equals("revision"))f.manager.nativeBefore.getAsJsonObject("generation").addProperty(field,"3");
            else f.manager.nativeBefore.remove(field);
            Assert.assertThrows(field,RuntimeException.class,()->f.manager.freshStorageAdDomainReceipt(f.instance,domain(f)));Assert.assertTrue(f.manager.commands.isEmpty());Mockito.verifyNoInteractions(f.rules);
        }
    }
    @Test public void freshReadRejectsChangedGenerationBootOrSourceBindingsBeforePublicReceipt(){
        for(String field:List.of("revision","bootId","configurationSha256","machineSid","scope")){
            Fixture f=freshFixture();
            if(field.equals("revision"))f.manager.nativeAfter.getAsJsonObject("generation").addProperty(field,4);
            else if(field.equals("configurationSha256"))f.manager.nativeAfter.addProperty(field,"b".repeat(64));
            else if(field.equals("bootId"))f.manager.identity.addProperty(field,"44444444-4444-4444-4444-444444444444");
            else if(field.equals("machineSid"))f.manager.identity.addProperty(field,"S-1-5-21-4-5-7");
            else f.manager.freshScope=false;
            Assert.assertThrows(field,RuntimeException.class,()->f.manager.freshStorageAdDomainReceipt(f.instance,domain(f)));Mockito.verifyNoInteractions(f.rules);
        }
    }
    @Test public void freshReadRejectsStaleCoercedUnjoinedAndPartialGuestProof(){
        for(String field:List.of("generatedEpoch","success","joinState","idmapPolicy","sideEffects")){
            Fixture f=freshFixture();
            if(field.equals("generatedEpoch"))f.manager.identity.addProperty(field,System.currentTimeMillis()/1000.0-61);
            else if(field.equals("joinState"))f.manager.identity.addProperty(field,"NOT_JOINED");
            else if(field.equals("idmapPolicy"))f.manager.identity.remove(field);
            else f.manager.identity.addProperty(field,field.equals("success")?"true":"false");
            Assert.assertThrows(field,RuntimeException.class,()->f.manager.freshStorageAdDomainReceipt(f.instance,domain(f)));Mockito.verifyNoInteractions(f.rules);
        }
    }
    @Test public void freshApiDefaultStaysStoredAndTypedReceiptHasNoConfigFallback(){
        org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceDomainStatusCmd cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceDomainStatusCmd();
        Assert.assertNull(cmd.getFresh());ReflectionTestUtils.setField(cmd,"fresh",true);Assert.assertEquals(Boolean.TRUE,cmd.getFresh());
        org.apache.cloudstack.api.response.StorageIdentityDomainResponse response=new org.apache.cloudstack.api.response.StorageIdentityDomainResponse();
        Assert.assertFalse(new com.google.gson.Gson().toJsonTree(response).getAsJsonObject().has("identityreceipt"));
        Fixture f=freshFixture();f.manager.identity.addProperty("password","synthetic-private");f.manager.identity.addProperty("keytabBytes","synthetic-private");
        JsonObject receipt=f.manager.freshStorageAdDomainReceipt(f.instance,domain(f));response.setIdentityReceipt(receipt);receipt.addProperty("password","mutated-private-after-copy");response.setObjectName("storageidentitydomain");
        org.apache.cloudstack.api.response.ListResponse<org.apache.cloudstack.api.response.StorageIdentityDomainResponse> list=new org.apache.cloudstack.api.response.ListResponse<>();
        list.setResponseName("liststorageservicedomainstatusresponse");list.setResponses(java.util.List.of(response),1);
        StringBuilder log=new StringBuilder();String wire=com.cloud.api.response.ApiResponseSerializer.toJSONSerializedString(list,log);
        JsonObject serialized=com.google.gson.JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("liststorageservicedomainstatusresponse").getAsJsonArray("storageidentitydomain").get(0).getAsJsonObject();
        JsonObject typed=serialized.getAsJsonObject("identityreceipt");Assert.assertTrue(typed.get("success").getAsJsonPrimitive().isBoolean());Assert.assertTrue(typed.get("success").getAsBoolean());
        Assert.assertTrue(typed.get("sideEffects").getAsJsonPrimitive().isBoolean());Assert.assertFalse(typed.get("sideEffects").getAsBoolean());
        Assert.assertEquals(f.instance.getUuid(),typed.getAsJsonObject("scope").get("instanceUuid").getAsString());Assert.assertTrue(typed.getAsJsonObject("scope").get("revision").getAsJsonPrimitive().isNumber());
        Assert.assertFalse(serialized.has("config"));Assert.assertFalse(wire.contains("private"));Assert.assertFalse(wire.contains("keytabBytes"));Assert.assertFalse(log.toString().contains("private"));
        Assert.assertTrue(com.google.gson.JsonParser.parseString(log.toString()).getAsJsonObject().getAsJsonObject("liststorageservicedomainstatusresponse").getAsJsonArray("storageidentitydomain").get(0).getAsJsonObject().get("identityreceipt").isJsonObject());
    }

    @Test public void freshReadRejectsChangedVmOrTenantBeforeReturningIdentity(){
        for(String field:List.of("vmId","accountId","domainId")){
            Fixture f=freshFixture();StorageServiceInstanceVO after=Mockito.mock(StorageServiceInstanceVO.class);
            String expectedUuid=f.instance.getUuid();Mockito.when(after.getUuid()).thenReturn(expectedUuid);Mockito.when(after.getVmId()).thenReturn(7L);
            if(field.equals("vmId"))Mockito.when(after.getVmId()).thenReturn(8L);
            else if(field.equals("accountId"))Mockito.when(after.getAccountId()).thenReturn(8L);
            else Mockito.when(after.getDomainId()).thenReturn(8L);
            f.manager.currentInstance=after;
            Assert.assertThrows(field,RuntimeException.class,()->f.manager.freshStorageAdDomainReceipt(f.instance,domain(f)));Mockito.verifyNoInteractions(f.rules);
        }
    }

    @Test public void committedCliGenerationStatusProducesCurrentBootForFreshJavaConsumer() throws Exception {
        java.nio.file.Path root=java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!java.nio.file.Files.exists(root.resolve("systemvm/debian/usr/local/lib/ablestack-storage/config_generation.py")))root=root.getParent();
        String script=String.join("\n",
                "import sys,json,os,tempfile,subprocess,uuid",
                "from pathlib import Path",
                "root=Path(sys.argv[1]);sys.path.insert(0,str(root/'systemvm/debian/usr/local/lib/ablestack-storage'))",
                "import config_generation",
                "with tempfile.TemporaryDirectory() as directory:",
                " base=Path(directory);config=base/'config';config.mkdir(mode=0o700);state=base/'generation';state.mkdir(mode=0o700)",
                " producer=config_generation.Generation(state,config);digest=producer.digest()",
                " current={'instanceUuid':'11111111-1111-1111-1111-111111111111','operationUuid':str(uuid.uuid4()),'revision':3,'configurationSha256':digest}",
                " currentPath=state/'current.json';currentPath.write_text(json.dumps(current));currentPath.chmod(0o600)",
                " before={str(p):p.read_bytes() for p in base.rglob('*') if p.is_file()}",
                " env=dict(os.environ,ABLESTACK_STORAGE_GENERATION_DIR=str(state),ABLESTACK_STORAGE_CONFIGURATION_ROOT=str(config),ABLESTACK_STORAGE_LOG_FILE='/dev/null')",
                " replies=[]",
                " for unused in range(2):",
                "  reply=subprocess.run(['bash',str(root/'systemvm/debian/usr/local/bin/ablestack-storagectl'),'operation','generation','status'],capture_output=True,text=True,timeout=15,env=env)",
                "  assert reply.returncode==0,reply.stderr;replies.append(json.loads(reply.stdout))",
                " assert replies[0]==replies[1] and replies[0]['bootId']==str(uuid.UUID(Path('/proc/sys/kernel/random/boot_id').read_text().strip()))",
                " assert replies[0]['generation']==current and 'bootId' not in current and replies[0]['generationStatus']=='IN_SYNC'",
                " assert before=={str(p):p.read_bytes() for p in base.rglob('*') if p.is_file()}",
                " print(json.dumps(replies[0]))");
        Process process=new ProcessBuilder("python3","-c",script,root.toString()).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);Assert.assertEquals(output,0,process.waitFor());
        JsonObject status=com.google.gson.JsonParser.parseString(output).getAsJsonObject();Fixture f=freshFixture();f.manager.nativeBefore=status;f.manager.nativeAfter=status.deepCopy();f.manager.identity.add("bootId",status.get("bootId").deepCopy());
        JsonObject receipt=f.manager.freshStorageAdDomainReceipt(f.instance,domain(f));Assert.assertEquals(status.get("bootId"),receipt.get("bootId"));Mockito.verifyNoInteractions(f.rules);
    }

}
