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

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.junit.Assert;
import org.junit.Test;

public class StorageInitialPrimaryEndpointRecoveryTest {
    private static JsonObject json(String value) {return JsonParser.parseString(value).getAsJsonObject();}
    private static BaseCmd enable(long instance,String ip,int port) {
        return StorageConfigCommandBinding.bind(EnableStorageServiceProtocolCmd.class,json("{\"instanceid\":"+instance+",\"protocol\":\"NFS\",\"listenip\":\""+ip+"\",\"port\":"+port+",\"protocolmode\":\"V3V4_DUAL\"}"));
    }
    @Test public void onlyOneExactOriginalAuthenticatedEnableCanAuthorizeItsPrimaryDefaultPort() {
        String fingerprint=StorageServiceRequestFingerprint.of(enable(29,"10.10.13.223",2049),"test-protected-key");
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        JsonObject match=StorageInitialPrimaryEndpointRecovery.authenticate(29,"10.10.13.223",fingerprint,cmd->{calls.incrementAndGet();return StorageServiceRequestFingerprint.of(cmd,"test-protected-key");});
        Assert.assertEquals(18,calls.get());Assert.assertEquals("V3V4_DUAL",match.get("protocolmode").getAsString());
        Assert.assertThrows(CloudRuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.authenticate(30,"10.10.13.223",fingerprint,cmd->StorageServiceRequestFingerprint.of(cmd,"test-protected-key")));
        Assert.assertThrows(CloudRuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.authenticate(29,"10.10.13.224",fingerprint,cmd->StorageServiceRequestFingerprint.of(cmd,"test-protected-key")));
        String other=StorageServiceRequestFingerprint.of(enable(29,"10.10.13.223",2050),"test-protected-key");
        Assert.assertThrows(CloudRuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.authenticate(29,"10.10.13.223",other,cmd->StorageServiceRequestFingerprint.of(cmd,"test-protected-key")));
        Assert.assertThrows(CloudRuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.authenticate(29,"10.10.13.223",fingerprint,cmd->fingerprint));
    }

    @Test public void realAsyncParameterBoundaryExcludesBaseResponseAndAuthenticatesWorkerNull() throws Exception {
        com.cloud.user.User user=org.mockito.Mockito.mock(com.cloud.user.User.class);
        org.apache.cloudstack.context.CallContext.register(user,org.mockito.Mockito.mock(com.cloud.user.Account.class));
        try {
            EnableStorageServiceProtocolCmd worker=new EnableStorageServiceProtocolCmd();
            JsonObject http=json("{\"response\":\"json\",\"instanceid\":29,\"protocol\":\"NFS\",\"listenip\":\"10.10.13.223\",\"port\":2049,\"protocolmode\":\"V4_ONLY\"}");
            JsonObject dispatched=new JsonObject();
            for(java.lang.reflect.Field field:worker.getParamFields()) {
                org.apache.cloudstack.api.Parameter parameter=field.getAnnotation(org.apache.cloudstack.api.Parameter.class);
                if(http.has(parameter.name()))dispatched.add(parameter.name(),http.get(parameter.name()).deepCopy());
            }
            Assert.assertFalse(dispatched.has("response"));
            BaseCmd command=StorageConfigCommandBinding.bind(EnableStorageServiceProtocolCmd.class,dispatched);
            Assert.assertNull(org.springframework.test.util.ReflectionTestUtils.getField(command,"responseType"));
            String fingerprint=StorageServiceRequestFingerprint.of(command,"test-protected-key");
            JsonObject matched=StorageInitialPrimaryEndpointRecovery.authenticate(29,"10.10.13.223",fingerprint,candidate->StorageServiceRequestFingerprint.of(candidate,"test-protected-key"));
            Assert.assertEquals("V4_ONLY",matched.get("protocolmode").getAsString());Assert.assertFalse(matched.has("response"));
        } finally {org.apache.cloudstack.context.CallContext.unregister();}
    }

    @Test public void responseContaminatedFingerprintIsRejectedWithoutExpandingEighteenCandidates() {
        BaseCmd command=enable(29,"10.10.13.223",2049);command.setResponseType("json");
        String fingerprint=StorageServiceRequestFingerprint.of(command,"test-protected-key");
        java.util.concurrent.atomic.AtomicInteger compared=new java.util.concurrent.atomic.AtomicInteger();
        Assert.assertThrows(CloudRuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.authenticate(29,"10.10.13.223",fingerprint,candidate->{
            compared.incrementAndGet();return StorageServiceRequestFingerprint.of(candidate,"test-protected-key");
        }));
        Assert.assertEquals(18,compared.get());
    }

    private static JsonObject source() {
        JsonObject source=new JsonObject();for(String path:StorageRenderedDesiredState.PATHS)source.add(path,com.google.gson.JsonNull.INSTANCE);
        source.add("desired-state/nfs-export-apply.json",json("{\"enabled\":false,\"exports\":[]}"));return source;
    }
    private static JsonObject frozen() {
        JsonObject value=json("{\"generation\":{\"instanceUuid\":\"11111111-1111-4111-8111-111111111111\",\"operationUuid\":\"22222222-2222-4222-8222-222222222222\",\"revision\":1}}");
        value.add("configurationDesiredState",source());return value;
    }
    private static JsonObject current(JsonObject frozen) {
        JsonObject result=json("{\"success\":true,\"generationSupported\":true,\"generationStatus\":\"PENDING\",\"configurationSha256\":\""+"a".repeat(64)+"\",\"bootId\":\"33333333-3333-4333-8333-333333333333\"}");
        result.add("generation",frozen.get("generation").deepCopy());JsonObject desired=source();
        desired.add("network-endpoints.json",json("{\"endpoints\":[{\"listenIp\":\"10.10.13.223\",\"primaryIp\":\"10.10.13.223\",\"port\":2049,\"prefixlen\":16}]}"));
        result.add("configurationDesiredState",desired);return result;
    }
    @Test public void onlyAbsentSourceEndpointAndExactOtherSixCanBeNormalized() {
        JsonObject frozen=frozen();StorageInitialPrimaryEndpointRecovery.requireCanonical(frozen,current(frozen),"10.10.13.223");
        for(int mode=0;mode<6;mode++) {
            JsonObject old=frozen(),now=current(old);
            if(mode==0)now.getAsJsonObject("configurationDesiredState").add("iscsi-targets.json",json("{\"targets\":[]}"));
            if(mode==1)now.getAsJsonObject("configurationDesiredState").getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(0).getAsJsonObject().addProperty("listenIp","10.10.13.224");
            if(mode==2)now.getAsJsonObject("configurationDesiredState").getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(0).getAsJsonObject().addProperty("port",2050);
            if(mode==3){old.getAsJsonObject("configurationDesiredState").getAsJsonObject("desired-state/nfs-export-apply.json").addProperty("enabled",true);now.getAsJsonObject("configurationDesiredState").getAsJsonObject("desired-state/nfs-export-apply.json").addProperty("enabled",true);}
            if(mode==4)now.addProperty("generationStatus","IN_SYNC");
            if(mode==5)now.getAsJsonObject("generation").addProperty("revision",2);
            Assert.assertThrows(RuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.requireCanonical(old,now,"10.10.13.223"));
        }
    }

    @Test public void onlyEmptyEnabledReplayFlagWithAbsentEndpointCanNormalizeAgain() {
        JsonObject frozen=frozen(),now=current(frozen);
        now.getAsJsonObject("configurationDesiredState").add("network-endpoints.json",com.google.gson.JsonNull.INSTANCE);
        now.getAsJsonObject("configurationDesiredState").getAsJsonObject("desired-state/nfs-export-apply.json").addProperty("enabled",true);
        Assert.assertTrue(StorageInitialPrimaryEndpointRecovery.emptyEnabledReplayDrift(frozen,now));
        StorageInitialPrimaryEndpointRecovery.requireCanonical(frozen,now,"10.10.13.223");
        for(int mode=0;mode<6;mode++) {
            JsonObject changed=now.deepCopy(),desired=changed.getAsJsonObject("configurationDesiredState");
            JsonObject nfs=desired.getAsJsonObject("desired-state/nfs-export-apply.json");
            if(mode==0)nfs.getAsJsonArray("exports").add(json("{\"name\":\"active-foreign\"}"));
            if(mode==1)nfs.addProperty("protocolMode","V3V4_DUAL");
            if(mode==2)nfs.addProperty("port",2050);
            if(mode==3)nfs.addProperty("instanceUuid","foreign-owner");
            if(mode==4)desired.add("sharedfs-network.json",json("{\"unknown\":true}"));
            if(mode==5)desired.add("network-endpoints.json",json("{\"endpoints\":[{\"listenIp\":\"10.10.13.223\",\"primaryIp\":\"10.10.13.223\",\"port\":2049,\"prefixlen\":16}]}"));
            Assert.assertThrows(RuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.requireCanonical(frozen,changed,"10.10.13.223"));
        }
    }

    private static JsonObject health() {
        return json("{\"success\":true,\"status\":\"ok\",\"generatedEpoch\":1000,\"nfsGanesha\":{\"active\":0,\"configured\":0,\"listening\":false,\"endpoints\":[]},\"listenPorts\":{\"nfs\":false},\"services\":{\"nfs\":\"inactive\"}}");
    }
    private static JsonObject inventory() {return json("{\"success\":true,\"status\":\"ok\",\"generatedEpoch\":1000,\"nfsExports\":[],\"nfsGaneshaExports\":[]}");}
    @Test public void NfsUnknownCachedStaleActiveOrConfiguredNeverAuthorizeNormalization() {
        StorageInitialPrimaryEndpointRecovery.requireNfsAbsent(health(),inventory(),1000);
        for(int mode=0;mode<7;mode++) {
            JsonObject health=health(),inventory=inventory();
            if(mode==0)health.getAsJsonObject("nfsGanesha").addProperty("active",1);
            if(mode==1)health.getAsJsonObject("nfsGanesha").addProperty("configured",1);
            if(mode==2)health.getAsJsonObject("listenPorts").addProperty("nfs","false");
            if(mode==3)inventory.remove("nfsExports");
            if(mode==4)health.addProperty("cached",true);
            if(mode==5)health.addProperty("generatedEpoch",900);
            if(mode==6)health.getAsJsonObject("services").addProperty("nfs","unknown");
            Assert.assertThrows(RuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.requireNfsAbsent(health,inventory,1000));
        }
        JsonObject unrelated=inventory();unrelated.add("nvmeofSubsystems",json("{\"unobservedOrphan\":\"not-claimed-absent\"}"));
        StorageInitialPrimaryEndpointRecovery.requireNfsAbsent(health(),unrelated,1000);
        Assert.assertTrue(unrelated.getAsJsonObject("nvmeofSubsystems").has("unobservedOrphan"));
    }
    private static final class Manager extends StorageServiceManagerImpl implements AutoCloseable {
        final StorageServiceInstanceVO instance=new StorageServiceInstanceVO();final StorageServiceOperationVO operation=new StorageServiceOperationVO();
        final JsonObject frozen=frozen(),observed=current(frozen);int restores,observations;boolean race,unknownNfs;final java.util.List<String> commands=new java.util.ArrayList<>();
        Manager() {
            com.cloud.user.User user=org.mockito.Mockito.mock(com.cloud.user.User.class);org.mockito.Mockito.when(user.getId()).thenReturn(3L);
            org.apache.cloudstack.context.CallContext.register(user,org.mockito.Mockito.mock(com.cloud.user.Account.class));
            org.springframework.test.util.ReflectionTestUtils.setField(instance,"id",29L);instance.setVmId(78L);
            org.springframework.test.util.ReflectionTestUtils.setField(operation,"id",41L);operation.setInstanceId(29);operation.setRevision(2);operation.setCreatedBy(3);
            operation.setAction("enablestorageserviceprotocolresponse");operation.setState("RECOVERY_REQUIRED");
            operation.setPreviousSnapshotJson(captureConfigurationSnapshot(29));
            JsonObject result=new JsonObject();result.addProperty("_requestFingerprint",StorageServiceRequestFingerprint.of(enable(29,"10.10.13.223",2049),"test-protected-key"));operation.setResultJson(result.toString());
            observed.addProperty("pendingOperationUuid",operation.getUuid());
            JsonObject network=json("{\"ipAddress\":\"10.10.13.223\",\"cidr\":\"10.10.0.0/16\",\"macAddress\":\"02:00:00:00:00:78\"}");
            frozen.getAsJsonObject("configurationDesiredState").add("sharedfs-network.json",network);
            observed.getAsJsonObject("configurationDesiredState").add("sharedfs-network.json",network.deepCopy());
            java.lang.ThreadLocal<JsonObject> source=(java.lang.ThreadLocal<JsonObject>)org.springframework.test.util.ReflectionTestUtils.getField(this,"configurationRecoverySource");source.set(frozen);
            java.lang.ThreadLocal<Long> explicit=(java.lang.ThreadLocal<Long>)org.springframework.test.util.ReflectionTestUtils.getField(this,"explicitUnchangedSourceRecovery");explicit.set(41L);
        }
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO instance) { }
        @Override protected String configurationPrimaryAddress(StorageServiceInstanceVO instance) {return "10.10.13.223";}
        @Override protected String captureConfigurationSnapshot(long id) {return "{\"schemaVersion\":2,\"instanceId\":29,\"tables\":[]}";}
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action) {
            Assert.assertNull(operation);Assert.assertEquals("status",action);observations++;
            JsonObject result=observed.deepCopy();if(race && observations>1)result.addProperty("bootId","44444444-4444-4444-8444-444444444444");return result;
        }
        @Override protected com.google.gson.JsonArray rootNetworkBindings(StorageServiceInstanceVO instance,JsonObject endpoint) {
            com.google.gson.JsonArray values=new com.google.gson.JsonArray();values.add(json("{\"listenIp\":\"10.10.13.223\",\"primaryIp\":\"10.10.13.223\",\"macAddress\":\"02:00:00:00:00:78\",\"prefixlen\":16}"));return values;
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject payload,int timeout) {
            commands.add(command);
            if("network endpoints inspect".equals(command)) {
                Assert.assertEquals(16,payload.getAsJsonArray("endpoints").get(0).getAsJsonObject().get("prefixlen").getAsInt());
                JsonObject result=json("{\"success\":true,\"bindingsValidated\":true,\"sideEffects\":false}");com.google.gson.JsonArray rows=payload.getAsJsonArray("expectedBindings").deepCopy();
                rows.get(0).getAsJsonObject().addProperty("active",true);result.add("endpoints",rows);return result;
            }
            if("operation verify".equals(command)){JsonObject result=health();if(unknownNfs)result.getAsJsonObject("nfsGanesha").remove("active");result.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);return result;}
            if("operation observe".equals(command)){JsonObject result=inventory();result.addProperty("generatedEpoch",System.currentTimeMillis()/1000.0);return result;}
            Assert.assertEquals("operation generation restore",command);restores++;Assert.assertEquals(frozen.get("configurationDesiredState"),payload.get("configurationDesiredState"));
            JsonObject result=new JsonObject();result.addProperty("canonicalRestored",true);result.addProperty("generationAdvanced",false);result.addProperty("configurationSha256",frozen.has("configurationSha256")?frozen.get("configurationSha256").getAsString():"b".repeat(64));result.addProperty("pendingOperationUuid",operation.getUuid());return result;
        }
        public void close(){org.apache.cloudstack.context.CallContext.unregister();}
    }
    private static JsonObject existingPortSource() {
        JsonObject frozen=frozen(),source=frozen.getAsJsonObject("configurationDesiredState");
        source.add("desired-state/nfs-export-apply.json",com.google.gson.JsonNull.INSTANCE);
        source.add("network-endpoints.json",json("{\"endpoints\":[{\"listenIp\":\"10.10.13.223\",\"primaryIp\":\"10.10.13.223\",\"prefixlen\":16,\"port\":3260}]}"));
        source.add("sharedfs-network.json",json("{\"ipAddress\":\"10.10.13.223\",\"cidr\":\"10.10.0.0/16\",\"macAddress\":\"02:00:00:00:00:78\"}"));
        return frozen;
    }

    private static JsonObject existingPortCurrent(JsonObject frozen) {
        JsonObject current=current(frozen);
        current.add("configurationDesiredState",frozen.get("configurationDesiredState").deepCopy());
        current.getAsJsonObject("configurationDesiredState").getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(0).getAsJsonObject().addProperty("port",2049);
        return current;
    }

    @Test public void historicalPortCompatibilityAcceptsOnlyOnePrimaryNfs2049DifferenceAndNoOtherFileChange() {
        JsonObject frozen=existingPortSource();
        StorageInitialPrimaryEndpointRecovery.requireExistingPrimaryPortDrift(frozen,existingPortCurrent(frozen),"10.10.13.223");
        for(String fault:java.util.List.of("port","prefix","ip","extra","nfs","iscsi","same","generation")) {
            JsonObject current=existingPortCurrent(frozen),source=current.getAsJsonObject("configurationDesiredState");
            JsonObject row=source.getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(0).getAsJsonObject();
            if(fault.equals("port"))row.addProperty("port",2050);
            if(fault.equals("prefix"))row.addProperty("prefixlen",24);
            if(fault.equals("ip"))row.addProperty("listenIp","10.10.13.224");
            if(fault.equals("extra"))row.addProperty("extra",true);
            if(fault.equals("same"))row.addProperty("port",3260);
            if(fault.equals("nfs"))source.add("desired-state/nfs-export-apply.json",json("{\"exports\":[]}"));
            if(fault.equals("iscsi"))source.add("iscsi-targets.json",json("{\"targets\":[]}"));
            if(fault.equals("generation"))current.add("generation",new JsonObject());
            Assert.assertThrows(fault,RuntimeException.class,()->StorageInitialPrimaryEndpointRecovery.requireExistingPrimaryPortDrift(frozen,current,"10.10.13.223"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void legacyPortManager(Manager manager) {
        JsonObject frozen=existingPortSource();manager.frozen.add("configurationDesiredState",frozen.get("configurationDesiredState").deepCopy());
        manager.frozen.addProperty("configurationSha256",StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(manager.frozen.getAsJsonObject("configurationDesiredState"))));
        manager.observed.add("configurationDesiredState",existingPortCurrent(manager.frozen).get("configurationDesiredState").deepCopy());
        ((ThreadLocal<StorageServiceOperationVO>)org.springframework.test.util.ReflectionTestUtils.getField(manager,"storageWriterOperation")).set(manager.operation);
        StorageServiceGuestCommandDispatcher guest=org.mockito.Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        org.mockito.Mockito.when(guest.dispatch(org.mockito.Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"","{\"success\":true,\"status\":\"ok\"}"));
        org.springframework.test.util.ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
    }

    @Test public void historicalPrimaryPortRecoveryUsesOriginalHmacAndOnlyCommandsAvailableOnTheOldGuest() {
        try(org.mockito.MockedStatic<StorageServiceRequestFingerprint> auth=org.mockito.Mockito.mockStatic(StorageServiceRequestFingerprint.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            auth.when(()->StorageServiceRequestFingerprint.of(org.mockito.Mockito.any(BaseCmd.class))).thenAnswer(call->StorageServiceRequestFingerprint.of(call.getArgument(0),"test-protected-key"));
            try(Manager manager=new Manager()) {
                legacyPortManager(manager);manager.restoreFailedInitialPrimaryEndpoint(manager.instance,manager.operation);
                Assert.assertEquals(1,manager.restores);
                Assert.assertEquals(java.util.List.of("network endpoints inspect","operation verify","operation observe","operation generation restore"),manager.commands);
            }
            for(String fault:java.util.List.of("boot","actor","fingerprint","runtime","database"))try(Manager manager=new Manager()) {
                legacyPortManager(manager);
                if(fault.equals("boot"))manager.race=true;
                if(fault.equals("actor"))manager.operation.setCreatedBy(4);
                if(fault.equals("fingerprint"))manager.operation.setResultJson("{\"_requestFingerprint\":\"API_INTENT_HMAC_V1:"+"0".repeat(64)+"\"}");
                if(fault.equals("runtime"))manager.unknownNfs=true;
                if(fault.equals("database"))manager.operation.setPreviousSnapshotJson("{\"schemaVersion\":2,\"instanceId\":29,\"tables\":[\"foreign\"]}");
                Assert.assertThrows(fault,RuntimeException.class,()->manager.restoreFailedInitialPrimaryEndpoint(manager.instance,manager.operation));Assert.assertEquals(0,manager.restores);
            }
        }
    }

    @Test public void explicitNormalRecoveryRestoresOnlyCanonicalSourceAndRaceNeverWrites() {
        try(org.mockito.MockedStatic<StorageServiceRequestFingerprint> auth=org.mockito.Mockito.mockStatic(StorageServiceRequestFingerprint.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            auth.when(()->StorageServiceRequestFingerprint.of(org.mockito.Mockito.any(BaseCmd.class))).thenAnswer(call->StorageServiceRequestFingerprint.of(call.getArgument(0),"test-protected-key"));
            try(Manager manager=new Manager()) {
                manager.frozen.addProperty("configurationSha256","b".repeat(64));manager.restoreFailedInitialPrimaryEndpoint(manager.instance,manager.operation);
                Assert.assertEquals(1,manager.restores);Assert.assertEquals(java.util.List.of("network endpoints inspect","operation verify","operation observe","operation generation restore"),manager.commands);
            }
            try(Manager manager=new Manager()) {
                manager.frozen.addProperty("configurationSha256","b".repeat(64));manager.race=true;
                Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreFailedInitialPrimaryEndpoint(manager.instance,manager.operation));Assert.assertEquals(0,manager.restores);
            }
        }
    }

    @Test public void sameOwnedEnabledReplayReentryUsesOnlyReadonlyPrimaryProofAndCanonicalRestore() {
        try(org.mockito.MockedStatic<StorageServiceRequestFingerprint> auth=org.mockito.Mockito.mockStatic(StorageServiceRequestFingerprint.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            auth.when(()->StorageServiceRequestFingerprint.of(org.mockito.Mockito.any(BaseCmd.class))).thenAnswer(call->StorageServiceRequestFingerprint.of(call.getArgument(0),"test-protected-key"));
            for(int mode=0;mode<5;mode++)try(Manager manager=new Manager()) {
                manager.frozen.addProperty("configurationSha256","b".repeat(64));
                manager.observed.getAsJsonObject("configurationDesiredState").add("network-endpoints.json",com.google.gson.JsonNull.INSTANCE);
                manager.observed.getAsJsonObject("configurationDesiredState").getAsJsonObject("desired-state/nfs-export-apply.json").addProperty("enabled",true);
                if(mode==1)manager.unknownNfs=true;
                if(mode==2)manager.operation.setCreatedBy(4);
                if(mode==3 || mode==4) {
                    JsonObject network=manager.frozen.getAsJsonObject("configurationDesiredState").getAsJsonObject("sharedfs-network.json");
                    if(mode==3)network.remove("cidr");else network.addProperty("macAddress","02:00:00:00:00:79");
                    manager.observed.getAsJsonObject("configurationDesiredState").add("sharedfs-network.json",network.deepCopy());
                }
                if(mode==0) {
                    manager.restoreFailedInitialPrimaryEndpoint(manager.instance,manager.operation);Assert.assertEquals(1,manager.restores);
                    Assert.assertEquals(java.util.List.of("network endpoints inspect","operation verify","operation observe","operation generation restore"),manager.commands);
                } else {
                    Assert.assertThrows(CloudRuntimeException.class,()->manager.restoreFailedInitialPrimaryEndpoint(manager.instance,manager.operation));Assert.assertEquals(0,manager.restores);
                }
            }
        }
    }

}
