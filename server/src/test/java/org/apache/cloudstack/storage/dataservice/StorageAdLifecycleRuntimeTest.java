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
import java.util.Set;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageAdLifecycleRuntimeTest {
    private static class Manager extends StorageServiceManagerImpl {
        List<String> phases=new ArrayList<>();JsonObject scope,post;boolean invalidSource;
        @Override protected JsonObject requiredAdServiceSourceIdentity(StorageServiceInstanceVO instance){if(invalidSource)throw new com.cloud.utils.exception.CloudRuntimeException("Invalid stopped source");JsonObject source=new JsonObject();source.addProperty("publicLocalMachineSid","S-1-5-21-4-5-6");source.addProperty("bootId","33333333-3333-3333-3333-333333333333");return source;}
        @Override protected void requireStorageAdIdentityFeatures(StorageServiceInstanceVO instance){ }
        @Override protected JsonObject ownedAdServiceScope(StorageServiceInstanceVO instance){return scope.deepCopy();}
        @Override protected JsonArray adOwnedEndpointAddresses(StorageServiceInstanceVO instance){JsonArray addresses=new JsonArray();addresses.add("10.10.13.240");return addresses;}
        @Override protected void requireProtectedIdentityTransport(StorageServiceInstanceVO instance,String op){phases.add("PROTECTED_STDIN");}
        @Override protected void markAdIdentityEffect(String phase){phases.add(phase);}
        @Override protected String buildSmbNetbiosName(StorageServiceInstanceVO instance){return "STOR1111111111";}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){return post.deepCopy();}
    }
    private Manager manager(){Manager m=new Manager();m.scope=new JsonObject();m.scope.addProperty("instanceUuid","11111111-1111-1111-1111-111111111111");m.scope.addProperty("operationUuid","22222222-2222-2222-2222-222222222222");m.scope.addProperty("maintenanceUuid","22222222-2222-2222-2222-222222222222");m.scope.addProperty("revision",3);return m;}
    private StorageServiceInstanceVO instance(){StorageServiceInstanceVO i=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(i.getVmId()).thenReturn(7L);Mockito.when(i.getUuid()).thenReturn("11111111-1111-1111-1111-111111111111");return i;}
    private JsonObject identity(Manager m){JsonObject p=new JsonObject(),scope=m.scope.deepCopy();
        scope.remove("maintenanceUuid");
        p.add("scope",scope);
        p.addProperty("success",true);
        p.addProperty("sideEffects",false);
        p.addProperty("joinState","JOINED");
        p.addProperty("domain","example.test");
        p.addProperty("realm","EXAMPLE.TEST");
        p.addProperty("workgroup","EXAMPLE");
        p.addProperty("netbiosName","STOR1111111111");
        p.addProperty("domainSid","S-1-5-21-1-2-3");
        p.addProperty("machineSid","S-1-5-21-4-5-6");
        p.addProperty("machineAccountSid","S-1-5-21-1-2-3-1001");
        p.addProperty("bootId","33333333-3333-3333-3333-333333333333");
        p.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
        for(String key:List.of("trustVerified","identityVerified","dnsAliasesVerified","adSpnsVerified","adIdentity"))p.addProperty(key,true);
        JsonObject desired=StorageAdLifecycleRequest.publicJoin("example.test","EXAMPLE","STOR1111111111","10.10.13.79",m.adOwnedEndpointAddresses(null),"JOIN_EXISTING",null);
        for(String field:List.of("dnsAliases","servicePrincipals","idmapPolicy"))p.add(field,desired.get(field).deepCopy());
        return p;
        }
    @Test public void joinProducesFullValidatedPublicReceiptAndCredentialsOnlyInProtectedServiceInput(){Manager m=manager();
        StorageServiceInstanceVO instance=instance();
        StorageIdentityDomainVO domain=new StorageIdentityDomainVO(6,"example.test",null,"10.10.13.79",StorageServiceInstance.DomainJoinState.JOINING,"UNKNOWN","{\"workgroup\":\"EXAMPLE\",\"identityMode\":\"JOIN_EXISTING\"}");
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);
        Assert.assertEquals("identity domain join",command.getOperation());
        Assert.assertEquals(Set.of("username","password"),command.getMaskedFields());
        JsonObject payload=com.google.gson.JsonParser.parseString(command.getPayload()).getAsJsonObject();
        Assert.assertEquals(m.scope.get("maintenanceUuid"),payload.get("maintenanceUuid"));
        Assert.assertEquals("JOIN_STARTED",m.phases.get(m.phases.size()-1));
        JsonObject response=new JsonObject();
        response.addProperty("success",true);
        JsonObject common=m.scope.deepCopy();
        common.remove("maintenanceUuid");
        response.add("scope",common);
        response.addProperty("canonicalDesiredStateChanged",false);
        response.add("identity",identity(m));
        return new StorageServiceGuestCommandResult(true,"joined",response.toString());
        });
        ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
        m.applyAdJoin(instance,domain,"operator","synthetic-test-password");
        JsonObject config=com.google.gson.JsonParser.parseString(domain.getConfigJson()).getAsJsonObject();
        Assert.assertEquals("S-1-5-21-1-2-3",config.getAsJsonObject("identityReceipt").get("domainSid").getAsString());
        Assert.assertFalse(config.toString().contains("synthetic-test-password"));
        Assert.assertEquals(List.of("PROTECTED_STDIN","JOIN_STARTED","JOIN_VERIFIED"),m.phases);
        }
    @Test public void legacyLeaveSuccessWithoutCleanupReceiptCannotBecomeVerifiedNotJoined(){Manager m=manager();StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);JsonObject response=new JsonObject();response.addProperty("success",true);response.addProperty("left",true);response.addProperty("canonicalDesiredStateChanged",false);JsonObject common=m.scope.deepCopy();common.remove("maintenanceUuid");response.add("scope",common);Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"left",response.toString()));ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);Assert.assertThrows(RuntimeException.class,()->m.applyAdLeave(instance(),null,null));Assert.assertFalse(m.phases.contains("LEAVE_VERIFIED"));}
    @Test public void leaveCompletesOnlyAfterOwnedCleanupAndFreshNotJoined(){Manager m=manager();
        JsonObject common=m.scope.deepCopy();
        common.remove("maintenanceUuid");
        JsonObject response=new JsonObject();
        response.addProperty("success",true);
        response.addProperty("left",true);
        response.addProperty("canonicalDesiredStateChanged",false);
        response.addProperty("localMachineSidPreserved",true);
        response.addProperty("adOwnedArtifactsRemoved",true);
        response.add("scope",common);addClosedLeaveProof(response,m);
        m.post=new JsonObject();
        m.post.addProperty("success",true);
        m.post.addProperty("sideEffects",false);
        m.post.addProperty("joinState","NOT_JOINED");
        for(String field:List.of("trustVerified","identityVerified","adIdentity"))m.post.addProperty(field,false);
        m.post.addProperty("machineSid","S-1-5-21-4-5-6");
        m.post.addProperty("bootId","33333333-3333-3333-3333-333333333333");
        m.post.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);
        m.post.add("scope",common.deepCopy());
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"left",response.toString()));
        ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
        m.applyAdLeave(instance(),null,null);
        Assert.assertEquals(List.of("PROTECTED_STDIN","LEAVE_STARTED","LEAVE_VERIFIED"),m.phases);
        }
    private void addClosedLeaveProof(JsonObject response,Manager manager){
        for(String field:List.of("publicConfigurationRestored","computerAliasSpnAbsent","dnsAliasesAbsent"))response.addProperty(field,true);
        JsonArray cleanup=new JsonArray();for(String name:List.of("ad-machine.conf","krb5.keytab","winbindd_idmap.tdb")){JsonObject artifact=new JsonObject();artifact.addProperty("name",name);artifact.addProperty("absent",true);cleanup.add(artifact);}response.add("ownedArtifactCleanup",cleanup);response.add("identity",localObservation(manager));
    }
    private JsonObject leftReceipt(Manager m){JsonObject response=new JsonObject();response.addProperty("success",true);response.addProperty("left",true);response.addProperty("canonicalDesiredStateChanged",false);response.addProperty("localMachineSidPreserved",true);response.addProperty("adOwnedArtifactsRemoved",true);JsonObject scope=m.scope.deepCopy();scope.remove("maintenanceUuid");response.add("scope",scope);addClosedLeaveProof(response,m);return response;}
    private JsonObject localObservation(Manager m){JsonObject response=new JsonObject();response.addProperty("success",true);response.addProperty("sideEffects",false);response.addProperty("joinState","NOT_JOINED");for(String field:List.of("trustVerified","identityVerified","adIdentity"))response.addProperty(field,false);response.addProperty("machineSid","S-1-5-21-4-5-6");response.addProperty("bootId","33333333-3333-3333-3333-333333333333");response.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);JsonObject scope=m.scope.deepCopy();scope.remove("maintenanceUuid");response.add("scope",scope);return response;}
    @Test public void leaveStaleCrossBootOrChangedSamCannotRecordVerifiedExternalEffect(){
        for(String field:List.of("generatedEpoch","bootId","machineSid")){
            Manager m=manager();m.post=localObservation(m);
            if(field.equals("generatedEpoch"))m.post.addProperty(field,System.currentTimeMillis()/1000.0-61);
            else if(field.equals("bootId"))m.post.addProperty(field,"44444444-4444-4444-4444-444444444444");
            else m.post.addProperty(field,"S-1-5-21-4-5-7");
            StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
            Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"left",leftReceipt(m).toString()));
            ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
            Assert.assertThrows(field,RuntimeException.class,()->m.applyAdLeave(instance(),null,null));
            Assert.assertEquals(List.of("PROTECTED_STDIN","LEAVE_STARTED"),m.phases);
        }
    }
    @Test public void joinedCrossBootOrChangedLocalSamCannotPublishJoinedIdentity(){
        for(String field:List.of("bootId","machineSid")){
            Manager m=manager();JsonObject identity=identity(m);
            identity.addProperty(field,field.equals("bootId")?"44444444-4444-4444-4444-444444444444":"S-1-5-21-4-5-7");
            JsonObject response=new JsonObject();response.addProperty("success",true);response.addProperty("canonicalDesiredStateChanged",false);response.add("scope",identity.get("scope").deepCopy());response.add("identity",identity);
            StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
            Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"joined",response.toString()));
            ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
            StorageIdentityDomainVO domain=new StorageIdentityDomainVO(6,"example.test",null,"10.10.13.79",StorageServiceInstance.DomainJoinState.JOINING,"UNKNOWN",new com.google.gson.Gson().toJson(java.util.Map.of("workgroup","EXAMPLE","identityMode","JOIN_EXISTING")));
            Assert.assertThrows(field,RuntimeException.class,()->m.applyAdJoin(instance(),domain,"operator","synthetic-test-password"));
            Assert.assertEquals(List.of("PROTECTED_STDIN","JOIN_STARTED"),m.phases);
            Assert.assertFalse(domain.getConfigJson().contains("identityReceipt"));
        }
    }

    @Test public void unavailableStoppedSourceRejectsBeforeAnyExternalJoinOrLeaveRpc(){
        Manager m=manager();m.invalidSource=true;
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
        StorageIdentityDomainVO domain=new StorageIdentityDomainVO(6,"example.test",null,"10.10.13.79",StorageServiceInstance.DomainJoinState.JOINING,"UNKNOWN","{}");
        Assert.assertThrows(RuntimeException.class,()->m.applyAdJoin(instance(),domain,"operator","synthetic-test-password"));
        Assert.assertThrows(RuntimeException.class,()->m.applyAdLeave(instance(),null,null));
        Assert.assertTrue(m.phases.isEmpty());Mockito.verifyNoInteractions(guest);
    }

    @Test public void incompleteRemoteOrPublicRestorationCannotPublishLeftState(){
        for(String field:List.of("publicConfigurationRestored","computerAliasSpnAbsent","dnsAliasesAbsent","identity")){
            Manager m=manager();JsonObject response=leftReceipt(m);m.post=localObservation(m);
            if(field.equals("identity"))response.getAsJsonObject(field).addProperty("machineSid","S-1-5-21-4-5-7");else response.addProperty(field,"true");
            StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"left",response.toString()));ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
            Assert.assertThrows(field,RuntimeException.class,()->m.applyAdLeave(instance(),null,null));Assert.assertFalse(m.phases.contains("LEAVE_VERIFIED"));
        }
    }
    @Test public void leftCleanupRejectsMissingChangedOrUnnormalizedOwnedArtifacts(){
        for(String wrong:List.of("missing","path","duplicate","absent","extra")){
            Manager m=manager();JsonObject response=leftReceipt(m);m.post=localObservation(m);JsonArray cleanup=response.getAsJsonArray("ownedArtifactCleanup");
            if(wrong.equals("missing"))cleanup.remove(2);
            else if(wrong.equals("path"))cleanup.get(0).getAsJsonObject().addProperty("name","../ad-machine.conf");
            else if(wrong.equals("duplicate"))cleanup.get(2).getAsJsonObject().addProperty("name","krb5.keytab");
            else if(wrong.equals("absent"))cleanup.get(1).getAsJsonObject().addProperty("absent","true");
            else cleanup.get(0).getAsJsonObject().addProperty("keytabBytes","synthetic-private");
            StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"left",response.toString()));ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
            Assert.assertThrows(wrong,RuntimeException.class,()->m.applyAdLeave(instance(),null,null));Assert.assertEquals(List.of("PROTECTED_STDIN","LEAVE_STARTED"),m.phases);
        }
    }

    private StorageServiceOperationVO inverseOperation(Manager manager,boolean joined,String phase){
        StorageServiceOperationVO op=new StorageServiceOperationVO();op.setRevision(3);manager.scope.addProperty("operationUuid",op.getUuid());manager.scope.addProperty("maintenanceUuid",op.getUuid());JsonObject snapshot=new JsonObject(),source=new JsonObject();source.add("scope",manager.scope.deepCopy());source.addProperty("publicAdPreStopCaptured",joined);source.addProperty("publicLocalMachineSid","S-1-5-21-4-5-6");source.addProperty("bootId","33333333-3333-3333-3333-333333333333");snapshot.add("adServiceSource",source);snapshot.addProperty("adIdentityEffectPhase",phase);op.setPreviousSnapshotJson(snapshot.toString());
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao operations=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);Mockito.when(operations.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(manager,"storageOperationDao",operations);return op;
    }
    private JsonObject inverseReply(Manager manager){
        JsonObject reply=new JsonObject(),common=manager.scope.deepCopy();common.remove("maintenanceUuid");reply.add("scope",common);for(String field:List.of("success","externalJoinInverted","localMachineSidPreserved","adOwnedArtifactsRemoved","computerAliasSpnAbsent","dnsAliasesAbsent","publicConfigurationRestored"))reply.addProperty(field,true);reply.addProperty("canonicalDesiredStateChanged",false);reply.addProperty("replayed",false);reply.add("identity",localObservation(manager));return reply;
    }
    @Test public void unjoinedSourceInverseUsesExactServiceScopeAndOnlyRamSealedCredentials(){
        Manager m=manager();StorageServiceOperationVO operation=inverseOperation(m,false,"SEMANTIC_LOCAL_VERIFIED");StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);Assert.assertEquals("identity domain inverse-join",command.getOperation());Assert.assertEquals(Set.of("username","password"),command.getMaskedFields());JsonObject request=com.google.gson.JsonParser.parseString(command.getPayload()).getAsJsonObject();Assert.assertEquals(Set.of("instanceUuid","maintenanceUuid","operationUuid","revision","username","password"),request.keySet());Assert.assertEquals(m.scope.get("operationUuid"),request.get("operationUuid"));Assert.assertTrue(com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject().get("adJoinInverseAttempted").getAsBoolean());return new StorageServiceGuestCommandResult(true,"inverted",inverseReply(m).toString());});ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
        m.prepareAdIdentityRollback(instance(),operation,"operator","synthetic-test-password");m.requireAdIdentityRollbackAllowed(instance(),operation);Assert.assertFalse(operation.getPreviousSnapshotJson().contains("synthetic-test-password"));Assert.assertFalse(operation.getPreviousSnapshotJson().contains("operator"));
        m.post=localObservation(m);m.prepareAdIdentityRollback(instance(),operation,null,null);Mockito.verify(guest,Mockito.times(1)).dispatch(Mockito.any());
    }
    @Test public void missingCredentialsOrIncompleteInverseCannotPermitTerminalRollback(){
        for(String fault:List.of("missingCredentials","missingFlag","stringTrue","wrongScope","wrongSam","wrongBoot","stale","stillJoined","missingReplay")){
            Manager m=manager();StorageServiceOperationVO operation=inverseOperation(m,false,"JOIN_STARTED");StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);JsonObject reply=inverseReply(m);
            if(fault.equals("missingFlag"))reply.remove("externalJoinInverted");if(fault.equals("stringTrue"))reply.addProperty("externalJoinInverted","true");if(fault.equals("wrongScope"))reply.getAsJsonObject("scope").addProperty("operationUuid","44444444-4444-4444-4444-444444444444");if(fault.equals("wrongSam"))reply.getAsJsonObject("identity").addProperty("machineSid","S-1-5-21-7-8-9");if(fault.equals("wrongBoot"))reply.getAsJsonObject("identity").addProperty("bootId","44444444-4444-4444-4444-444444444444");if(fault.equals("stale"))reply.getAsJsonObject("identity").addProperty("generatedEpoch",System.currentTimeMillis()/1000.0-61);if(fault.equals("stillJoined"))reply.getAsJsonObject("identity").addProperty("joinState","JOINED");if(fault.equals("missingReplay"))reply.remove("replayed");
            Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"incomplete",reply.toString()));ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
            Assert.assertThrows(fault,RuntimeException.class,()->m.prepareAdIdentityRollback(instance(),operation,fault.equals("missingCredentials")?null:"operator",fault.equals("missingCredentials")?null:"synthetic-test-password"));Assert.assertFalse(com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject().has("adJoinInverseReceipt"));Assert.assertThrows(fault,RuntimeException.class,()->m.requireAdIdentityRollbackAllowed(instance(),operation));if(fault.equals("missingCredentials"))Mockito.verifyNoInteractions(guest);
        }
    }
    @Test public void originallyJoinedSourceUsesNativeSourceRetainAndNeverExternalJoinInverse(){
        Manager m=manager();StorageServiceOperationVO operation=inverseOperation(m,true,"JOIN_STARTED");StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);m.prepareAdIdentityRollback(instance(),operation,null,null);m.requireAdIdentityRollbackAllowed(instance(),operation);Mockito.verifyNoInteractions(guest);Assert.assertFalse(operation.getPreviousSnapshotJson().contains("adJoinInverseReceipt"));
        JsonObject snapshot=com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject();snapshot.getAsJsonObject("adServiceSource").addProperty("publicAdPreStopCaptured","true");operation.setPreviousSnapshotJson(snapshot.toString());Assert.assertThrows(RuntimeException.class,()->m.requireAdIdentityRollbackAllowed(instance(),operation));
    }
    private static class RetainManager extends StorageServiceManagerImpl {
        List<String> events=new ArrayList<>();JsonObject response;boolean wrongStop;
        @Override protected void restoreAdServiceConfigurationIdentity(StorageServiceInstanceVO instance,JsonObject reference){events.add("RAW_IMPORT_AFTER_OWNED_STOP");}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){events.add(command);if(command.equals("operation quiesce")){JsonObject proof=new JsonObject();proof.addProperty("quiesced",!wrongStop);JsonObject scope=request.deepCopy();scope.remove("domains");proof.add("scope",scope);proof.add("domainsQuiesced",request.get("domains").deepCopy());return proof;}Assert.assertEquals("identity domain retain",command);Assert.assertTrue(request.has("maintenanceUuid"));Assert.assertFalse(request.has("templateUpgradeUuid"));Assert.assertTrue(request.getAsJsonObject("expectedIdentity").has("machineConfigurationSha256"));return response.deepCopy();}
    }
    @Test public void serviceJoinedSourceStopsImportsThenRetainsUnderActualServiceScopeWithoutJoiningAgain(){
        Manager fixture=manager();StorageServiceOperationVO operation=inverseOperation(fixture,true,"JOIN_STARTED");RetainManager m=new RetainManager();JsonObject sourceIdentity=identity(fixture);sourceIdentity.addProperty("machineConfigurationSha256","a".repeat(64));JsonObject snapshot=com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject();snapshot.add("adServiceSourceAdIdentity",sourceIdentity);snapshot.add("nativeIdentityCapsule",new JsonObject());operation.setPreviousSnapshotJson(snapshot.toString());ReflectionTestUtils.setField(m,"storageOperationDao",ReflectionTestUtils.getField(fixture,"storageOperationDao"));
        m.response=new JsonObject();m.response.addProperty("success",true);JsonObject common=fixture.scope.deepCopy();common.remove("maintenanceUuid");m.response.add("scope",common);m.response.addProperty("identityPreserved",true);m.response.addProperty("rejoined",false);m.response.add("identity",identity(fixture));
        m.retainAdServiceSourceIdentity(instance(),operation,true);Assert.assertEquals(List.of("operation quiesce","RAW_IMPORT_AFTER_OWNED_STOP","identity domain retain"),m.events);Assert.assertTrue(com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject().has("adServiceRollbackRetained"));
        m.events.clear();m.retainAdServiceSourceIdentity(instance(),operation,false);Assert.assertEquals(List.of("identity domain retain"),m.events);
    }
    @Test public void foreignStopOrChangedFreshJoinedSourceCannotRecordCompletedRetention(){
        for(String fault:List.of("wrongStop","wrongMachine","wrongComputer","stringTrust","foreignScope")){
            Manager fixture=manager();StorageServiceOperationVO operation=inverseOperation(fixture,true,"JOIN_VERIFIED");RetainManager m=new RetainManager();JsonObject sourceIdentity=identity(fixture);sourceIdentity.addProperty("machineConfigurationSha256","a".repeat(64));JsonObject snapshot=com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject();snapshot.add("adServiceSourceAdIdentity",sourceIdentity);snapshot.add("nativeIdentityCapsule",new JsonObject());operation.setPreviousSnapshotJson(snapshot.toString());ReflectionTestUtils.setField(m,"storageOperationDao",ReflectionTestUtils.getField(fixture,"storageOperationDao"));
            m.response=new JsonObject();m.response.addProperty("success",true);JsonObject common=fixture.scope.deepCopy();common.remove("maintenanceUuid");m.response.add("scope",common);m.response.addProperty("identityPreserved",true);m.response.addProperty("rejoined",false);JsonObject observed=identity(fixture);m.response.add("identity",observed);
            if(fault.equals("wrongStop"))m.wrongStop=true;if(fault.equals("wrongMachine"))observed.addProperty("machineSid","S-1-5-21-7-8-9");if(fault.equals("wrongComputer"))observed.addProperty("machineAccountSid","S-1-5-21-1-2-3-1999");if(fault.equals("stringTrust"))observed.addProperty("trustVerified","true");if(fault.equals("foreignScope"))m.response.getAsJsonObject("scope").addProperty("operationUuid","44444444-4444-4444-4444-444444444444");
            Assert.assertThrows(fault,RuntimeException.class,()->m.retainAdServiceSourceIdentity(instance(),operation,true));Assert.assertFalse(com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject().has("adServiceRollbackRetained"));if(m.wrongStop)Assert.assertFalse(m.events.contains("RAW_IMPORT_AFTER_OWNED_STOP"));
        }
    }
    @Test public void earlySourceRollbackImportsOnlySmbAndDefersUnrelatedNvmeReplay() throws Exception {
        java.nio.file.Path path=java.nio.file.Files.createTempDirectory("service-source-import-");java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));String previous=System.getProperty("cloudstack.storage.identity.path");System.setProperty("cloudstack.storage.identity.path",path.toString());
        try(org.mockito.MockedStatic<com.cloud.utils.crypt.DBEncryptionUtil> crypto=Mockito.mockStatic(com.cloud.utils.crypt.DBEncryptionUtil.class)) {
            crypto.when(()->com.cloud.utils.crypt.DBEncryptionUtil.decrypt("encrypted-test-key")).thenReturn("RAM-ONLY-PRIVATE-KEY");Manager m=manager();JsonObject capsule=new JsonObject();capsule.addProperty("ciphertext","encrypted-test-envelope");byte[] bytes=capsule.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),key="encrypted-test-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);String op=m.scope.get("operationUuid").getAsString(),keyId="55555555-5555-5555-5555-555555555555";StorageConfigArtifactStore store=new StorageConfigArtifactStore(path);store.write(op,bytes);store.write(keyId,key);
            JsonObject reference=new JsonObject();reference.addProperty("operationUuid",op);reference.addProperty("keyId",keyId);reference.addProperty("capsuleSha256",StorageConfigArchive.sha256(bytes));reference.addProperty("keySha256",StorageConfigArchive.sha256(key));StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);
            Mockito.when(guest.dispatch(Mockito.any())).thenAnswer(call->{StorageServiceGuestCommand command=call.getArgument(0);
            Assert.assertEquals("identity capsule import",command.getOperation());
            Assert.assertEquals(Set.of("capsule","credentialPrivateKey"),command.getMaskedFields());
            JsonObject request=com.google.gson.JsonParser.parseString(command.getPayload()).getAsJsonObject();
            Assert.assertEquals(Set.of("instanceUuid","operationUuid","capsule","credentialPrivateKey","deferNvmeReplay","restoreDomains"),request.keySet());
            Assert.assertTrue(request.get("deferNvmeReplay").getAsBoolean());
            Assert.assertEquals("SMB",request.getAsJsonArray("restoreDomains").get(0).getAsString());
            Assert.assertEquals(1,request.getAsJsonArray("restoreDomains").size());
            Assert.assertFalse(request.has("nvmeDesired"));
            return new StorageServiceGuestCommandResult(true,"imported","{\"success\":true}");
            });
            ReflectionTestUtils.setField(m,"guestCommandDispatcher",guest);
            m.restoreAdServiceConfigurationIdentity(instance(),reference);
            Assert.assertEquals(List.of("PROTECTED_STDIN"),m.phases);
        }finally {if(previous==null)System.clearProperty("cloudstack.storage.identity.path");else System.setProperty("cloudstack.storage.identity.path",previous);}
    }
}
