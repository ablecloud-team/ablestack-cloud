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
        JsonObject identity,resolved;List<String> commands=new ArrayList<>();boolean denyFeature;
        @Override protected void requireStorageAdIdentityFeatures(StorageServiceInstanceVO instance){if(denyFeature)throw new com.cloud.utils.exception.CloudRuntimeException("AD unsupported");}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){commands.add(command);if(command.equals("identity domain inspect"))return identity.deepCopy();Assert.assertEquals(identity.get("domainSid"),request.get("expectedDomainSid"));Assert.assertEquals(identity.get("realm"),request.get("expectedRealm"));return resolved.deepCopy();}
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
        JsonObject idmap=new JsonObject();
        idmap.addProperty("backend","rid");
        identity.add("idmapPolicy",idmap);
        JsonArray spns=new JsonArray();
        spns.add("cifs/storage.example.test@EXAMPLE.TEST");spns.add("host/storage.example.test@EXAMPLE.TEST");
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
}
