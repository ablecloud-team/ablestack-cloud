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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
public class StorageCanonicalRecoveryRuntimeTest {
    private static final class Manager extends StorageServiceManagerImpl {
        JsonObject frozen,live;boolean healthFailure,foreignMac,badReceipt;List<String> commands=new ArrayList<>();
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){commands.add(action);return live.deepCopy();}
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO instance){commands.add("verify-runtime");if(healthFailure)throw new CloudRuntimeException("Runtime differs from restored DB desired state");}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){commands.add(command);if(command.endsWith("frozen") || command.endsWith("frozen-initial"))return frozen.deepCopy();JsonObject result=new JsonObject();result.addProperty("success",true);
            if(command.endsWith("restore")){Assert.assertEquals(frozen.get("generation"),request.get("previousGeneration"));Assert.assertEquals(frozen.get("configurationDesiredState"),request.get("configurationDesiredState"));result.addProperty("canonicalRestored",true);result.add("configurationSha256",frozen.get("configurationSha256"));result.add("pendingOperationUuid",request.get("operationUuid"));live.add("configurationDesiredState",frozen.get("configurationDesiredState").deepCopy());live.add("configurationSha256",frozen.get("configurationSha256"));}
            if(command.equals("network endpoints reconcile")){JsonArray endpoints=request.getAsJsonArray("expectedBindings").deepCopy();for(JsonElement value:endpoints){value.getAsJsonObject().addProperty("active",true);if(foreignMac)value.getAsJsonObject().addProperty("macAddress","foreign");}result.add("endpoints",endpoints);result.addProperty("bindingReceiptVerified",!badReceipt);result.addProperty("desiredStatePresent",true);result.addProperty("bindingReceiptDesiredStatePresent",true);result.addProperty("desiredStateSha256","a".repeat(64));result.addProperty("bindingReceiptDesiredStateSha256",badReceipt?"b".repeat(64):"a".repeat(64));}return result;}
    }
    private Manager manager;private StorageServiceInstanceVO instance;private StorageServiceOperationVO operation;private JsonObject generation;
    @Before public void setup(){manager=new Manager();instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instance.getVmId()).thenReturn(7L);Mockito.when(instance.getUuid()).thenReturn(UUID.randomUUID().toString());operation=new StorageServiceOperationVO();operation.setInstanceId(6);operation.setRevision(7);
        generation=new JsonObject();generation.addProperty("instanceUuid",instance.getUuid());generation.addProperty("operationUuid",UUID.randomUUID().toString());generation.addProperty("revision",6);generation.addProperty("configurationSha256","verified-checksum");generation.addProperty("verifiedAt",12345);
        JsonObject desired=new JsonObject();for(String path:new String[]{"desired-state/nfs-export-apply.json","desired-state/smb-share-apply.json","iscsi-targets.json","nvmeof-subsystems.json","posix-directory-policies.json","network-endpoints.json","sharedfs-network.json"})desired.add(path,JsonNull.INSTANCE);
        JsonObject smb=new JsonObject();JsonArray shares=new JsonArray();JsonObject share=new JsonObject();share.addProperty("state","Updating");share.addProperty("uuid","share-original");shares.add(share);smb.add("shares",shares);desired.add("desired-state/smb-share-apply.json",smb);
        manager.frozen=new JsonObject();manager.frozen.addProperty("frozen",true);manager.frozen.add("generation",generation);manager.frozen.add("configurationSha256",generation.get("configurationSha256"));manager.frozen.add("configurationDesiredState",desired);
        manager.live=new JsonObject();manager.live.addProperty("pendingOperationUuid",operation.getUuid());manager.live.add("generation",generation);manager.live.add("configurationDesiredState",desired.deepCopy());manager.live.getAsJsonObject("configurationDesiredState").getAsJsonObject("desired-state/smb-share-apply.json").getAsJsonArray("shares").get(0).getAsJsonObject().addProperty("state","Ready");
        JsonObject snapshot=new JsonObject();JsonObject nativeState=new JsonObject();nativeState.add("previous",generation);snapshot.add("nativeGeneration",nativeState);operation.setPreviousSnapshotJson(snapshot.toString());
        StorageFileShareDao sharesDao=Mockito.mock(StorageFileShareDao.class);Mockito.when(sharesDao.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storageFileShareDao",sharesDao);
        StorageBlockTargetDao blocks=Mockito.mock(StorageBlockTargetDao.class);Mockito.when(blocks.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenReturn(List.of());ReflectionTestUtils.setField(manager,"storageBlockTargetDao",blocks);
        StorageServiceGuestCommandDispatcher guest=Mockito.mock(StorageServiceGuestCommandDispatcher.class);Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"verified","{\"success\":true,\"status\":\"ok\"}"));ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
        ((ThreadLocal<JsonObject>)ReflectionTestUtils.getField(manager,"configurationRecoverySource")).set(manager.frozen);
    }
    @Test public void legacyPreviousArtifactIsExactAndCanonicalRestorePrecedesSeparateRollback() {
        Assert.assertEquals(manager.frozen,manager.frozenRecoveryConfiguration(instance,operation));ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation);
        Assert.assertTrue(manager.commands.indexOf("verify-runtime")<manager.commands.indexOf("operation generation restore"));Assert.assertTrue(manager.commands.indexOf("operation generation restore")<manager.commands.indexOf("rollback"));Assert.assertEquals(2,Collections.frequency(manager.commands,"verify-runtime"));
    }
    @Test public void changedSemanticTargetCannotRestoreOrReleasePendingGeneration(){manager.live.getAsJsonObject("configurationDesiredState").getAsJsonObject("desired-state/smb-share-apply.json").getAsJsonArray("shares").get(0).getAsJsonObject().addProperty("uuid","other-share");Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation));Assert.assertFalse(manager.commands.contains("operation generation restore"));Assert.assertFalse(manager.commands.contains("rollback"));}
    @Test public void runtimeMustBeVerifiedBeforeCanonicalBytesCanBeRestored(){manager.healthFailure=true;Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation));Assert.assertFalse(manager.commands.contains("operation generation restore"));}
    @Test public void historicalEmptyDefaultsRequireNoDatabaseResourcesAndNoPayloadResources(){JsonObject empty=new JsonObject();empty.addProperty("enabled",true);empty.add("exports",new JsonArray());manager.live.getAsJsonObject("configurationDesiredState").add("desired-state/nfs-export-apply.json",empty);ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation);Assert.assertTrue(manager.commands.contains("rollback"));manager.commands.clear();empty.getAsJsonArray("exports").add(new JsonObject());manager.live.getAsJsonObject("configurationDesiredState").add("desired-state/nfs-export-apply.json",empty);Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation));Assert.assertFalse(manager.commands.contains("operation generation restore"));}
    @Test public void artifactScopeAndDurableDesiredSourceCannotBeSubstituted(){manager.frozen.getAsJsonObject("generation").addProperty("revision",5);Assert.assertThrows(CloudRuntimeException.class,()->manager.frozenRecoveryConfiguration(instance,operation));}
    private void staticNetwork(){
        SharedFSDao sharedDao=Mockito.mock(SharedFSDao.class);SharedFSVO shared=Mockito.mock(SharedFSVO.class);Mockito.when(sharedDao.findByVm(7L)).thenReturn(shared);Mockito.when(shared.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);Mockito.when(shared.getIpAddress()).thenReturn("10.10.13.240");ReflectionTestUtils.setField(manager,"sharedFSDao",sharedDao);
        NicVO nic=Mockito.mock(NicVO.class);Mockito.when(nic.getId()).thenReturn(79L);Mockito.when(nic.isDefaultNic()).thenReturn(true);Mockito.when(nic.getIPv4Address()).thenReturn("10.10.13.241");Mockito.when(nic.getMacAddress()).thenReturn("02:00:00:00:00:79");NicDao nics=Mockito.mock(NicDao.class);Mockito.when(nics.listByVmId(7L)).thenReturn(List.of(nic));ReflectionTestUtils.setField(manager,"nicDao",nics);NicSecondaryIpDao aliases=Mockito.mock(NicSecondaryIpDao.class);NicSecondaryIpVO alias=Mockito.mock(NicSecondaryIpVO.class);Mockito.when(alias.getNicId()).thenReturn(79L);Mockito.when(alias.getIp4Address()).thenReturn("10.10.13.241");Mockito.when(aliases.listByVmId(7L)).thenReturn(List.of(alias));ReflectionTestUtils.setField(manager,"nicSecondaryIpDao",aliases);
        JsonObject cached=new JsonObject();JsonArray endpoints=new JsonArray();JsonObject endpoint=new JsonObject();endpoint.addProperty("listenIp","10.10.13.241");endpoint.addProperty("primaryIp","10.10.13.240");endpoint.addProperty("prefixlen",16);endpoints.add(endpoint);cached.add("endpoints",endpoints);manager.frozen.getAsJsonObject("configurationDesiredState").add("network-endpoints.json",cached);
    }
    @Test public void staticDefaultPrimaryOverridesStaleDatabaseWhileExtraNicAndForeignMacAreProtected(){
        staticNetwork();NicDao nics=(NicDao)ReflectionTestUtils.getField(manager,"nicDao");NicVO nic=nics.listByVmId(7L).get(0);
        manager.reconcileRecoveryNetwork(instance,operation);Mockito.verify(nics,Mockito.never()).updatePrimaryIpAddress(Mockito.anyLong(),Mockito.anyString(),Mockito.any());Assert.assertEquals("10.10.13.240",manager.declaredProtocolPrimary(instance,nic));NicVO extra=Mockito.mock(NicVO.class);Mockito.when(extra.getIPv4Address()).thenReturn("192.168.1.1");Assert.assertEquals("192.168.1.1",manager.declaredProtocolPrimary(instance,extra));manager.foreignMac=true;Assert.assertThrows(CloudRuntimeException.class,()->manager.reconcileRecoveryNetwork(instance,operation));
    }
    private void staleCachePrimary(String primary) {
        staticNetwork();JsonObject cache=manager.frozen.getAsJsonObject("configurationDesiredState").getAsJsonObject("network-endpoints.json").deepCopy();cache.getAsJsonArray("endpoints").get(0).getAsJsonObject().addProperty("primaryIp",primary);manager.live.getAsJsonObject("configurationDesiredState").add("network-endpoints.json",cache);
    }
    @Test public void provenStaticPrimaryDriftRestoresCanonicalCacheAndRefreshesReceiptBeforeRollback(){
        staleCachePrimary("10.10.13.241");ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation);
        Assert.assertEquals(2,Collections.frequency(manager.commands,"network endpoints reconcile"));Assert.assertTrue(manager.commands.indexOf("network endpoints reconcile")<manager.commands.indexOf("operation generation restore"));Assert.assertTrue(manager.commands.lastIndexOf("network endpoints reconcile")>manager.commands.indexOf("operation generation restore"));Assert.assertTrue(manager.commands.lastIndexOf("network endpoints reconcile")<manager.commands.indexOf("rollback"));Assert.assertEquals(manager.frozen.get("configurationDesiredState"),manager.live.get("configurationDesiredState"));
    }
    @Test public void foreignCachedPrimaryIsRejectedEvenWithAValidGuestBinding(){staleCachePrimary("10.10.13.242");Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation));Assert.assertFalse(manager.commands.contains("operation generation restore"));}
    @Test public void foreignEndpointOrPrefixCannotBeHiddenByPrimaryNormalization(){for(String field:new String[]{"listenIp","prefixlen"}){manager.commands.clear();staleCachePrimary("10.10.13.241");JsonObject endpoint=manager.live.getAsJsonObject("configurationDesiredState").getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(0).getAsJsonObject();if(field.equals("listenIp"))endpoint.addProperty(field,"10.10.13.245");else endpoint.addProperty(field,24);Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation));Assert.assertFalse(manager.commands.contains("operation generation restore"));}}
    @Test public void changedGuestMacCannotNormalizeOrRestoreCache(){staleCachePrimary("10.10.13.241");manager.foreignMac=true;Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation));Assert.assertFalse(manager.commands.contains("operation generation restore"));}
    @Test public void unverifiedNewCacheReceiptKeepsNativePendingAndNeverRollsBack(){staleCachePrimary("10.10.13.241");manager.badReceipt=true;Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation));Assert.assertTrue(manager.commands.contains("operation generation restore"));Assert.assertFalse(manager.commands.contains("rollback"));}

    private void initialPendingSource() {
        JsonObject snapshot=new JsonObject(),nativeState=new JsonObject();
        nativeState.add("previous",new JsonObject());snapshot.add("nativeGeneration",nativeState);
        snapshot.add("nativeDesiredState",manager.frozen.get("configurationDesiredState").deepCopy());
        operation.setPreviousSnapshotJson(snapshot.toString());
        manager.frozen.add("generation",new JsonObject());manager.frozen.add("scope",manager.operationReservationScope(instance,operation));
        manager.frozen.addProperty("success",true);manager.frozen.addProperty("generationSupported",true);
        manager.frozen.addProperty("initialSourceVerified",true);manager.frozen.addProperty("configurationSha256","a".repeat(64));
        manager.live.add("generation",new JsonObject());
    }
    @Test public void firstPendingWriterRestoresAbsentNfsOnlyAfterSourceAndRuntimeVerification() {
        initialPendingSource();JsonObject empty=new JsonObject();empty.addProperty("enabled",true);empty.add("exports",new JsonArray());
        manager.live.getAsJsonObject("configurationDesiredState").add("desired-state/nfs-export-apply.json",empty);
        manager.live.addProperty("configurationSha256","b".repeat(64));
        JsonObject source=manager.frozenRecoveryConfiguration(instance,operation);
        Assert.assertEquals(new JsonObject(),source.get("generation"));Assert.assertTrue(source.get("initialSourceVerified").getAsBoolean());
        ((ThreadLocal<JsonObject>)ReflectionTestUtils.getField(manager,"configurationRecoverySource")).set(source);
        ReflectionTestUtils.invokeMethod(manager,"rollbackNativeConfigurationGeneration",instance,operation);
        Assert.assertTrue(manager.commands.indexOf("operation generation frozen-initial")<manager.commands.indexOf("verify-runtime"));
        Assert.assertTrue(manager.commands.indexOf("verify-runtime")<manager.commands.indexOf("operation generation restore"));
        Assert.assertTrue(manager.commands.indexOf("operation generation restore")<manager.commands.indexOf("rollback"));
        Assert.assertTrue(manager.live.getAsJsonObject("configurationDesiredState").get("desired-state/nfs-export-apply.json").isJsonNull());
        Assert.assertEquals("a".repeat(64),manager.live.get("configurationSha256").getAsString());
    }
    @Test public void initialSourceProofCannotInventScopeGenerationOrSourceBytes() {
        for(String field:List.of("initialSourceVerified","success","scope","generation","configurationDesiredState","configurationSha256")) {
            initialPendingSource();manager.commands.clear();JsonObject valid=manager.frozen.deepCopy();
            if(field.equals("initialSourceVerified") || field.equals("success"))manager.frozen.addProperty(field,"true");
            else if(field.equals("scope"))manager.frozen.getAsJsonObject(field).addProperty("operationUuid",UUID.randomUUID().toString());
            else if(field.equals("generation"))manager.frozen.getAsJsonObject(field).addProperty("revision",1);
            else if(field.equals("configurationDesiredState"))manager.frozen.getAsJsonObject(field).add("unknown.json",new JsonObject());
            else manager.frozen.addProperty(field,"unverified");
            Assert.assertThrows(field,CloudRuntimeException.class,()->manager.frozenRecoveryConfiguration(instance,operation));
            Assert.assertFalse(manager.commands.contains("operation generation restore"));Assert.assertFalse(manager.commands.contains("rollback"));
            manager.frozen=valid;
        }
    }
    @Test public void incompleteOrUnknownInitialSourceFilesCannotReachTheNativeRestore() {
        initialPendingSource();JsonObject valid=com.google.gson.JsonParser.parseString(operation.getPreviousSnapshotJson()).getAsJsonObject();
        for(String mode:List.of("missing","partial","unknown")) {
            JsonObject snapshot=valid.deepCopy();
            if(mode.equals("missing"))snapshot.remove("nativeDesiredState");
            else if(mode.equals("partial"))snapshot.getAsJsonObject("nativeDesiredState").remove("network-endpoints.json");
            else snapshot.getAsJsonObject("nativeDesiredState").add("foreign.json",new JsonObject());
            operation.setPreviousSnapshotJson(snapshot.toString());manager.commands.clear();
            Assert.assertThrows(mode,CloudRuntimeException.class,()->manager.frozenRecoveryConfiguration(instance,operation));
            Assert.assertTrue(manager.commands.isEmpty());
        }
    }

    private static final class RestoredManager extends StorageServiceManagerImpl implements AutoCloseable {
        final StorageServiceInstanceVO instance=new StorageServiceInstanceVO();final StorageServiceOperationVO operation=new StorageServiceOperationVO();
        final JsonObject fixture=StorageCanonicalRecoveryTest.nfsSourceFixture(),before=StorageSmbIdentityRepairRuntimeTest.localSourceBefore();
        JsonObject live,local;boolean databaseDrift;final List<StorageServiceInstance.Protocol> applied=new ArrayList<>();
        RestoredManager() {
            com.cloud.user.User user=Mockito.mock(com.cloud.user.User.class);Mockito.when(user.getId()).thenReturn(3L);
            org.apache.cloudstack.context.CallContext.register(user,Mockito.mock(com.cloud.user.Account.class));
            ReflectionTestUtils.setField(instance,"id",29L);ReflectionTestUtils.setField(instance,"uuid",StorageSmbIdentityRepairRuntimeTest.currentContext().get("instanceUuid").getAsString());instance.setVmId(78L);
            ReflectionTestUtils.setField(operation,"id",41L);ReflectionTestUtils.setField(operation,"uuid",StorageSmbIdentityRepairRuntimeTest.currentContext().get("operationUuid").getAsString());
            operation.setInstanceId(29);operation.setRevision(4);operation.setCreatedBy(3);operation.setAction("restorestorageservicelastknowngoodresponse");operation.setState("RECOVERY_REQUIRED");operation.setPhase("RECOVERING_INTERRUPTED_WRITER");
            JsonObject source=fixture.getAsJsonObject("source");JsonObject smb=new JsonObject();smb.addProperty("enabled",true);smb.add("shares",new JsonArray());source.add("desired-state/smb-share-apply.json",smb);
            JsonObject nvme=new JsonObject();nvme.addProperty("enabled",true);nvme.add("subsystems",new JsonArray());source.add("nvmeof-subsystems.json",nvme);
            before.add("configurationDesiredState",source.deepCopy());
            JsonObject writer=operationReservationScope(instance,operation),context=StorageLocalSourceIdentityProof.context(writer,before);
            JsonObject exported=StorageSmbIdentityRepairRuntimeTest.localSourceExport(context);
            JsonObject reference=new JsonObject();reference.addProperty("operationUuid",operation.getUuid());reference.add("sourceLocalScope",StorageLocalSourceIdentityProof.scope(context));
            reference.add("sourceLocalIdentityCheckpoint",exported.get("localSourceIdentityCheckpoint").deepCopy());
            JsonObject intent=new JsonObject();intent.add("context",context);intent.add("source",before.deepCopy());intent.addProperty("sourceProtocol","MIXED");JsonArray domains=localSourceAuthReplayDomains(null);intent.add("authReplayDomains",domains);
            JsonObject imported=exported.deepCopy();imported.addProperty("sourceIdentityRestored",true);intent.add("restored",imported);
            JsonObject replay=new JsonObject();replay.addProperty("success",true);replay.add("scope",StorageLocalSourceIdentityProof.scope(context));replay.addProperty("sourceAuthReplayed",true);replay.addProperty("canonicalDesiredStateChanged",false);replay.addProperty("smbIdentityChanged",false);replay.add("replayedDomains",domains.deepCopy());replay.add("sourceConfigurationSha256",context.get("sourceConfigurationSha256").deepCopy());replay.addProperty("nvmeRestored",true);
            replay.add("localSourceCheckpointReference",StorageLocalSourceIdentityProof.reference(exported.getAsJsonObject("localSourceIdentityCheckpoint")));intent.add("authReplayed",replay);
            JsonObject snapshot=com.google.gson.JsonParser.parseString(captureConfigurationSnapshot(29)).getAsJsonObject();snapshot.add("localCheckpointIntent",intent);snapshot.add("nativeIdentityCapsule",reference);snapshot.add("nativeDesiredState",source.deepCopy());JsonObject generation=new JsonObject();generation.add("previous",before.get("generation").deepCopy());snapshot.add("nativeGeneration",generation);operation.setPreviousSnapshotJson(snapshot.toString());
            local=new JsonObject();local.addProperty("success",true);local.addProperty("checkpointSupported",true);local.add("scope",StorageLocalSourceIdentityProof.scope(context));local.add("sourceGeneration",context.get("sourceGeneration").deepCopy());local.add("sourceConfigurationSha256",context.get("sourceConfigurationSha256").deepCopy());local.add("bootId",context.get("expectedBootId").deepCopy());local.addProperty("journalPresent",true);local.addProperty("phase","RESUMED");
            for(String key:List.of("sourceSmbResumed","sourceRuntimeVerified","sourceIdentityRestoreSupported","currentRuntimeOwnershipVerified","currentSessionsVerifiedEmpty"))local.addProperty(key,true);
            local.addProperty("canonicalDesiredStateChanged",false);local.addProperty("currentConfigurationSha256","a".repeat(64));local.addProperty("currentSmbConfigurationSha256","d".repeat(64));local.add("localSourceCheckpointReference",replay.get("localSourceCheckpointReference").deepCopy());
            JsonObject frozen=before.deepCopy();frozen.addProperty("frozen",true);
            ((ThreadLocal<JsonObject>)ReflectionTestUtils.getField(this,"configurationRecoverySource")).set(frozen);
            ((ThreadLocal<Long>)ReflectionTestUtils.getField(this,"explicitUnchangedSourceRecovery")).set(41L);
            ((ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(this,"storageWriterOperation")).set(operation);
            ((ThreadLocal<Boolean>)ReflectionTestUtils.getField(this,"configurationNativeNvmeReplayed")).set(true);
            live=before.deepCopy();live.addProperty("generationStatus","PENDING");live.addProperty("pendingOperationUuid",operation.getUuid());
        }
        @Override protected void requireConfigurationAdministrator() { }
        @Override protected String captureConfigurationSnapshot(long id){return "{\"schemaVersion\":2,\"instanceId\":29,\"tables\":"+(databaseDrift?"[\"foreign\"]":"[]")+"}";}
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){return live.deepCopy();}
        @Override protected JsonObject localSourceGuest(StorageServiceInstanceVO instance,String action,JsonObject request){Assert.assertEquals("local-source-status",action);return local.deepCopy();}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){if(command.equals("operation verify"))return fixture.getAsJsonObject("health").deepCopy();Assert.assertEquals("operation observe",command);return fixture.getAsJsonObject("inventory").deepCopy();}
        @Override protected JsonObject currentSmbDataIdentity(StorageServiceInstanceVO instance,StorageServiceOperationVO operation){return fixture.getAsJsonObject("data").deepCopy();}
        @Override protected JsonObject operationResourceBindings(StorageServiceInstanceVO instance){JsonObject result=new JsonObject();result.addProperty("vmId",78);result.addProperty("rootVolumeUuid","same-root");return result;}
        @Override protected void applyStorageServiceProtocolDesiredState(StorageServiceInstanceVO instance,StorageServiceInstance.Protocol protocol){applied.add(protocol);}
        public void close(){org.apache.cloudstack.context.CallContext.unregister();}
    }
    @Test public void explicitRestoredSourceOmitsOnlyNfsAndPreservesSmbIscsiAndExistingNvmeAuthHandling() {
        try(RestoredManager m=new RestoredManager()){
            JsonObject proof=m.applyRestoredSourceProtocols(m.instance,m.operation);Assert.assertNotNull(proof);
            Assert.assertEquals(List.of(StorageServiceInstance.Protocol.SMB,StorageServiceInstance.Protocol.ISCSI),m.applied);
            m.requireRestoredNfsSourceUnchanged(m.instance,m.operation,proof);
        }
    }
    @Test public void wrongActorDifferentActionAndUnrestoredPrivateSourceNeverOmitNfsReplay() {
        for(String change:List.of("actor","action","private")){
            try(RestoredManager m=new RestoredManager()){
                if(change.equals("actor"))m.operation.setCreatedBy(4);
                else if(change.equals("action"))m.operation.setAction("createstoragenfsexportresponse");
                else m.local.addProperty("sourceRuntimeVerified",false);
                Assert.assertNull(m.applyRestoredSourceProtocols(m.instance,m.operation));Assert.assertTrue(m.applied.contains(StorageServiceInstance.Protocol.NFS));
            }
        }
    }
    @Test public void targetDatabaseCanonicalAclOrStaleObservationFallsBackToNormalNfsReplay() {
        for(String change:List.of("database","canonical","acl","stale")){
            try(RestoredManager m=new RestoredManager()){
                if(change.equals("database"))m.databaseDrift=true;
                else if(change.equals("canonical"))m.live.getAsJsonObject("configurationDesiredState").getAsJsonObject("desired-state/nfs-export-apply.json").addProperty("port",2050);
                else if(change.equals("stale"))m.fixture.getAsJsonObject("health").addProperty("generatedEpoch",System.currentTimeMillis()/1000.0-60);
                else m.fixture.getAsJsonObject("inventory").getAsJsonArray("nfsGaneshaExports").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonArray("clients").get(0).getAsJsonObject().addProperty("access","RO");
                Assert.assertNull(m.applyRestoredSourceProtocols(m.instance,m.operation));Assert.assertTrue(m.applied.contains(StorageServiceInstance.Protocol.NFS));
            }
        }
    }
    @Test public void configProcessOrDataDriftAfterNfsOmissionCannotReachNativeRollback() {
        for(String change:List.of("config","pid","data")){
            try(RestoredManager m=new RestoredManager()){
                JsonObject proof=m.applyRestoredSourceProtocols(m.instance,m.operation);Assert.assertNotNull(proof);
                JsonObject endpoint=m.fixture.getAsJsonObject("health").getAsJsonObject("nfsGanesha").getAsJsonArray("endpoints").get(0).getAsJsonObject();
                if(change.equals("config"))endpoint.addProperty("configurationSha256","b".repeat(64));
                else if(change.equals("pid"))endpoint.addProperty("pid",999);
                else m.fixture.getAsJsonObject("data").getAsJsonArray("volumes").get(1).getAsJsonObject().addProperty("serial","foreign");
                Assert.assertThrows(CloudRuntimeException.class,()->m.requireRestoredNfsSourceUnchanged(m.instance,m.operation,proof));
                Assert.assertEquals(List.of(StorageServiceInstance.Protocol.SMB,StorageServiceInstance.Protocol.ISCSI),m.applied);
            }
        }
    }
}
