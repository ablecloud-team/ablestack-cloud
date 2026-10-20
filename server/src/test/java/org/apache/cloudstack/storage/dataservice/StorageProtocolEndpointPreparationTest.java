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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.apache.cloudstack.api.response.StorageServiceProtocolResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageProtocolEndpointPreparationTest {
    private static final String IP="10.10.13.228", ALIAS="10.10.13.229", MAC="02:00:00:00:00:78";
    private static final String BOOT="33333333-3333-4333-8333-333333333333";
    private static JsonObject json(String value) {return JsonParser.parseString(value).getAsJsonObject();}
    private static String checksum(JsonObject desired) {return StorageConfigurationVolumePlan.sha256(StorageConfigurationVolumePlan.canonical(desired));}

    static final class Manager extends StorageServiceManagerImpl {
        final StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        final StorageServiceOperationVO operation=new StorageServiceOperationVO();
        final NicVO nic=Mockito.mock(NicVO.class);
        final NicDao nics=Mockito.mock(NicDao.class);
        final NicSecondaryIpDao aliases=Mockito.mock(NicSecondaryIpDao.class);
        final StorageServiceProtocolDao protocols=Mockito.mock(StorageServiceProtocolDao.class);
        final StorageFileShareDao shares=Mockito.mock(StorageFileShareDao.class);
        final List<StorageServiceProtocolVO> rows=new ArrayList<>();
        final List<String> events=new ArrayList<>();
        final JsonArray requests=new JsonArray();
        final JsonObject source=new JsonObject(),generation=new JsonObject(),db=json("{\"schemaVersion\":2,\"instanceId\":29,\"tables\":[]}");
        JsonObject live, target=json("{\"enabled\":true,\"port\":2049,\"listeners\":[{\"listenIp\":\"10.10.13.228\",\"port\":2049}],\"exports\":[]}");
        String receiptFault,failCommand; boolean newAlias,apiFailure,foreignAlias,foreignMac;
        NicSecondaryIpVO alias; long nextId=5;

        @SuppressWarnings("unchecked")
        Manager() {
            Mockito.when(instance.getId()).thenReturn(29L);Mockito.when(instance.getVmId()).thenReturn(78L);
            Mockito.when(instance.getUuid()).thenReturn("11111111-1111-4111-8111-111111111111");
            Mockito.when(nic.getId()).thenReturn(79L);Mockito.when(nic.getIPv4Address()).thenReturn(IP);Mockito.when(nic.getMacAddress()).thenAnswer(call->foreignMac?"02:00:00:00:00:99":MAC);
            Mockito.when(nic.isDefaultNic()).thenReturn(true);
            Mockito.when(nics.listByVmId(78L)).thenReturn(List.of(nic));
            ReflectionTestUtils.setField(this,"nicDao",nics);ReflectionTestUtils.setField(this,"nicSecondaryIpDao",aliases);
            ReflectionTestUtils.setField(this,"storageServiceProtocolDao",protocols);ReflectionTestUtils.setField(this,"storageFileShareDao",shares);
            Mockito.when(shares.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenReturn(List.of());
            Mockito.when(protocols.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenAnswer(call->new ArrayList<>(rows));
            Mockito.when(protocols.persist(Mockito.any())).thenAnswer(call->{StorageServiceProtocolVO row=call.getArgument(0);ReflectionTestUtils.setField(row,"id",nextId++);rows.add(row);return row;});
            Mockito.when(protocols.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
            org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao ops=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao.class);
            Mockito.when(ops.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);ReflectionTestUtils.setField(this,"storageOperationDao",ops);
            Mockito.when(aliases.findByIp4AddressAndNicId(Mockito.anyString(),Mockito.eq(79L))).thenAnswer(call->alias);
            Mockito.when(aliases.remove(Mockito.anyLong())).thenAnswer(call->{Assert.assertEquals(501L,((Number)call.getArgument(0)).longValue());events.add("DB_ALIAS_REMOVE");alias=null;return true;});
            for(String path:StorageRenderedDesiredState.PATHS)source.add(path,JsonNull.INSTANCE);
            source.add("network-endpoints.json",json("{\"endpoints\":[{\"listenIp\":\""+IP+"\",\"primaryIp\":\""+IP+"\",\"prefixlen\":16,\"port\":3260}]}"));
            source.add("iscsi-targets.json",json("{\"enabled\":true,\"targets\":[]}"));
            source.add("sharedfs-network.json",json("{\"ipAddress\":\""+IP+"\",\"cidr\":\"10.10.0.0/16\",\"macAddress\":\""+MAC+"\"}"));
            generation.addProperty("instanceUuid",instance.getUuid());generation.addProperty("operationUuid","22222222-2222-4222-8222-222222222222");
            generation.addProperty("revision",4);generation.addProperty("configurationSha256",checksum(source));generation.addProperty("verifiedAt",1000);
            live=source.deepCopy();
            ReflectionTestUtils.setField(operation,"id",41L);operation.setInstanceId(29);operation.setRevision(5);operation.setCreatedBy(3);
            operation.setAction("enablestorageserviceprotocolresponse");operation.setState("RUNNING");operation.setPhase("APPLYING");
            snapshot();
            ((ThreadLocal<StorageServiceOperationVO>)ReflectionTestUtils.getField(this,"storageWriterOperation")).set(operation);
        }
        private void snapshot() {
            JsonObject snapshot=db.deepCopy(),nativeState=new JsonObject();nativeState.add("previous",generation.deepCopy());
            snapshot.add("nativeGeneration",nativeState);snapshot.add("nativeDesiredState",source.deepCopy());operation.setPreviousSnapshotJson(snapshot.toString());
        }
        void addAlias() {
            newAlias=true;alias=Mockito.mock(NicSecondaryIpVO.class);Mockito.when(alias.getId()).thenAnswer(call->foreignAlias?502L:501L);Mockito.when(alias.getVmId()).thenReturn(78L);
        }
        @SuppressWarnings("unchecked") void recovery() {
            operation.setState("RECOVERY_REQUIRED");operation.setPhase("RECOVERING_INTERRUPTED_WRITER");
            JsonObject frozen=new JsonObject();frozen.add("generation",generation.deepCopy());frozen.add("configurationDesiredState",source.deepCopy());frozen.addProperty("configurationSha256",checksum(source));
            ((ThreadLocal<JsonObject>)ReflectionTestUtils.getField(this,"configurationRecoverySource")).set(frozen);
        }
        boolean supported=true,replaceRuntimeBeforeWriter;int capabilityChecks,writerStarts;
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO owner,String action){
            Assert.assertNull(owner);Assert.assertEquals("status",action);capabilityChecks++;JsonObject out=new JsonObject();out.addProperty("endpointPreparationSupported",supported);return out;
        }
        @Override protected <T> T executeDesiredChange(BaseCmd cmd,Class<T> type,Supplier<T> change){
            writerStarts++;if(replaceRuntimeBeforeWriter)supported=false;
            // Keep the production read-only preflight in this domain fixture;
            // DB snapshot/native checkpoint orchestration is tested separately.
            requireProtocolEndpointPreparationSupport(instance);
            return change.get();
        }
        @Override protected StorageServiceInstanceVO requireInstance(Long id){return instance;}
        @Override protected NicVO resolveProtocolListenAddress(StorageServiceInstanceVO instance,String ip){return nic;}
        @Override protected NicVO reconcileProtocolListenNicIdentity(StorageServiceInstanceVO instance,NicVO nic){return nic;}
        @Override protected boolean registerProtocolListenAddress(StorageServiceInstanceVO instance,String ip,NicVO nic){return newAlias;}
        @Override protected String declaredProtocolPrimary(StorageServiceInstanceVO instance,NicVO nic){return IP;}
        @Override protected JsonObject protocolListenAddressPayload(StorageServiceInstanceVO instance,String ip,NicVO nic,Integer port){
            return json("{\"listenIp\":\""+ip+"\",\"primaryIp\":\""+IP+"\",\"prefixlen\":16}");
        }
        @Override protected String captureConfigurationSnapshot(long id){return db.toString();}
        @Override protected StorageServiceProtocolResponse createProtocolResponse(StorageServiceProtocolVO value){return new StorageServiceProtocolResponse();}
        @Override protected void requireNfsNamedPolicyCapability(StorageServiceInstanceVO instance){}
        private void capture(String command,JsonObject request){events.add(command);JsonObject row=new JsonObject();row.addProperty("operation",command);row.add("payload",request.deepCopy());requests.add(row);}
        @Override protected StorageServiceGuestCommandResult dispatchPlainProtocolApply(StorageServiceInstanceVO instance,StorageServiceGuestCommand command){
            Assert.assertEquals("nfs export apply",command.getOperation());JsonObject submitted=json(command.getPayload());capture(command.getOperation(),submitted);
            Assert.assertEquals(source.get("network-endpoints.json"),live.get("network-endpoints.json"));
            if(apiFailure)return new StorageServiceGuestCommandResult(false,"not public", "{\"success\":false,\"lastFailure\":{\"code\":\"NFS_SOURCE_CHANGED\",\"phase\":\"VERIFY_SOURCE\"}}");
            submitted.remove("operationScope");target=submitted.deepCopy();live.add("desired-state/nfs-export-apply.json",submitted);
            return new StorageServiceGuestCommandResult(true,"","{\"success\":true,\"runtimeReady\":true,\"exports\":0,\"endpoints\":0}");
        }
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){
            capture(command,request);
            if(command.equals(failCommand))throw new CloudRuntimeException("ENDPOINT_PREPARATION_FAILED");
            JsonObject scope=operationReservationScope(instance,operation),receipt=new JsonObject();
            receipt.addProperty("success",true);receipt.add("scope",scope);receipt.addProperty("bootId",BOOT);receipt.addProperty("sourceConfigurationSha256",checksum(source));receipt.addProperty("canonicalChanged",false);
            JsonObject intent=json(operation.getPreviousSnapshotJson()).getAsJsonObject("protocolEndpointPreparation");
            JsonArray endpoints=new JsonArray();JsonObject address=intent.getAsJsonObject("address").deepCopy();address.addProperty("active",true);endpoints.add(address);receipt.add("endpoints",endpoints);
            if(command.equals("network endpoint apply")) {
                Assert.assertTrue(request.get("deferCanonical").getAsBoolean());Assert.assertFalse(request.has("port"));Assert.assertEquals(scope,request.get("operationScope"));receipt.addProperty("prepared",true);
            } else if(command.equals("network endpoint publish")) {
                Assert.assertEquals(target,request.get("expectedNfsDesiredState"));Assert.assertEquals(target,live.get("desired-state/nfs-export-apply.json"));
                receipt.addProperty("published",true);
                if(newAlias){JsonObject entry=intent.getAsJsonObject("address").deepCopy();live.getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").add(entry);receipt.addProperty("canonicalChanged",true);}
                receipt.addProperty("targetConfigurationSha256",checksum(live));
            } else {
                Assert.assertEquals("network endpoints source-restore",command);Assert.assertEquals(source,request.get("configurationDesiredState"));Assert.assertEquals(generation,request.get("previousGeneration"));
                live.add("network-endpoints.json",source.get("network-endpoints.json").deepCopy());receipt.addProperty("sourceRestored",true);
            }
            if("boot".equals(receiptFault))receipt.addProperty("bootId","44444444-4444-4444-8444-444444444444");
            if("scope".equals(receiptFault))receipt.getAsJsonObject("scope").addProperty("operationUuid","55555555-5555-4555-8555-555555555555");
            if("success".equals(receiptFault))receipt.addProperty("success","true");
            if("prefix".equals(receiptFault))address.addProperty("prefixlen",24);
            if("mac".equals(receiptFault))address.addProperty("macAddress","02:00:00:00:00:99");
            if("hash".equals(receiptFault))receipt.addProperty("sourceConfigurationSha256","a".repeat(64));
            return receipt;
        }
        void enable(String ip) {
            EnableStorageServiceProtocolCmd cmd=(EnableStorageServiceProtocolCmd)StorageConfigCommandBinding.bind(EnableStorageServiceProtocolCmd.class,
                    json("{\"instanceid\":29,\"protocol\":\"NFS\",\"listenip\":\""+ip+"\",\"port\":2049}"));
            enableStorageServiceProtocol(cmd);
        }
    }

    @Test public void sameIpIscsiThenNfsPreservesOriginalSharedAddressBytesAndAppliesBeforePublish() {
        Manager m=new Manager();m.enable(IP);
        Assert.assertEquals(List.of("network endpoint apply","nfs export apply","network endpoint publish"),m.events);
        Assert.assertEquals(m.source.get("network-endpoints.json"),m.live.get("network-endpoints.json"));
        Assert.assertEquals(3260,m.live.getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").get(0).getAsJsonObject().get("port").getAsInt());
        Assert.assertEquals(m.target,json(m.operation.getPreviousSnapshotJson()).getAsJsonObject("protocolEndpointPreparation").get("expectedNfsDesiredState"));
        Assert.assertFalse(m.target.has("operationScope"));
    }

    @Test public void newAliasIsPublishedOnlyAfterNfsAndOnlyItsExactDatabaseRecordIsReleasedAfterGuestRestore() {
        Manager m=new Manager();m.addAlias();m.enable(ALIAS);Assert.assertEquals(2,m.live.getAsJsonObject("network-endpoints.json").getAsJsonArray("endpoints").size());
        m.recovery();m.restorePreparedProtocolEndpoint(m.instance,m.operation);
        Assert.assertEquals(m.source.get("network-endpoints.json"),m.live.get("network-endpoints.json"));
        Assert.assertTrue(m.events.indexOf("network endpoints source-restore")<m.events.indexOf("DB_ALIAS_REMOVE"));
        m.restorePreparedProtocolEndpoint(m.instance,m.operation);Mockito.verify(m.aliases,Mockito.times(1)).remove(501L);
        Mockito.verify(m.nics,Mockito.never()).updatePrimaryIpAddress(Mockito.anyLong(),Mockito.anyString(),Mockito.any());
    }

    @Test public void failedNfsDoesNotPublishOrEagerlyDropAliasOwnershipAndCanRestorePreparedAddress() {
        Manager m=new Manager();m.addAlias();m.apiFailure=true;
        Assert.assertTrue(Assert.assertThrows(CloudRuntimeException.class,()->m.enable(ALIAS)).getMessage().contains("NFS_SOURCE_CHANGED"));
        Assert.assertFalse(m.events.contains("network endpoint publish"));Mockito.verify(m.aliases,Mockito.never()).remove(Mockito.anyLong());
        m.recovery();m.restorePreparedProtocolEndpoint(m.instance,m.operation);
        Assert.assertEquals(m.source,m.live);Mockito.verify(m.aliases).remove(501L);
    }

    @Test public void successfulNfsFollowedByPublishFailureRetainsOnlyTheExactSubmittedTargetForNormalCanonicalRollback() {
        Manager m=new Manager();m.addAlias();m.failCommand="network endpoint publish";Assert.assertThrows(CloudRuntimeException.class,()->m.enable(ALIAS));
        m.recovery();m.restorePreparedProtocolEndpoint(m.instance,m.operation);
        JsonObject comparable=ReflectionTestUtils.invokeMethod(m,"recoveryComparableConfiguration",m.instance,m.source,m.live);
        Assert.assertEquals(m.source,comparable);
        m.live.getAsJsonObject("desired-state/nfs-export-apply.json").addProperty("unexpected",true);
        Assert.assertThrows(CloudRuntimeException.class,()->ReflectionTestUtils.invokeMethod(m,"recoveryComparableConfiguration",m.instance,m.source,m.live));
    }

    @Test public void foreignMacPrefixScopeSuccessTypeOrSourceHashNeverAuthorizeProtocolApplication() {
        for(String fault:List.of("prefix","mac","scope","success","hash")) {
            Manager m=new Manager();m.receiptFault=fault;Assert.assertThrows(fault,CloudRuntimeException.class,()->m.enable(IP));
            Assert.assertFalse(m.events.contains("nfs export apply"));Assert.assertFalse(m.events.contains("network endpoint publish"));
        }
    }

    @Test public void bootChangeBetweenPrepareAndPublishFailsInsteadOfInventingAReadyWitness() {
        Manager m=new Manager();m.prepareProtocolEndpoint(m.instance,StorageServiceInstance.Protocol.NFS,IP,m.nic,2049,false);
        m.applyNfsDesiredState(m.instance);m.receiptFault="boot";Assert.assertThrows(CloudRuntimeException.class,()->m.publishPreparedProtocolEndpoint(m.instance));
    }

    @Test public void changedAliasOrNicOwnershipCannotRunGuestRestoreOrDeleteDatabaseAliases() {
        for(boolean mac:List.of(false,true)) {
            Manager m=new Manager();m.addAlias();m.enable(ALIAS);m.recovery();m.foreignMac=mac;m.foreignAlias=!mac;
            Assert.assertThrows(CloudRuntimeException.class,()->m.restorePreparedProtocolEndpoint(m.instance,m.operation));
            Assert.assertFalse(m.events.contains("network endpoints source-restore"));Mockito.verify(m.aliases,Mockito.never()).remove(Mockito.anyLong());
        }
    }

    @Test public void oldGuestWriterWithoutPreparationMarkerNeverReceivesNewCommands() {
        Manager m=new Manager();m.recovery();m.restorePreparedProtocolEndpoint(m.instance,m.operation);Assert.assertTrue(m.events.isEmpty());
    }

    @Test public void initialUnappliedNullPlaceholderIsReusedAndSubsequentResourcesNeverSynthesizeAWildcard() {
        Manager m=new Manager();StorageServiceProtocolVO placeholder=new StorageServiceProtocolVO(29,StorageServiceInstance.Protocol.NFS,true,null,2049);
        ReflectionTestUtils.setField(placeholder,"id",4L);placeholder.setState(StorageServiceInstance.ResourceState.Ready);m.rows.add(placeholder);
        JsonObject row=new JsonObject();row.addProperty("id",4);row.addProperty("uuid",placeholder.getUuid());
        JsonObject table=new JsonObject();table.addProperty("table","storage_service_protocol");JsonArray stored=new JsonArray();stored.add(row);table.add("rows",stored);
        m.db.getAsJsonArray("tables").add(table);m.snapshot();
        m.enable(IP);Assert.assertEquals(1,m.rows.size());Assert.assertEquals(IP,placeholder.getListenIp());Assert.assertEquals(4,placeholder.getId());
        m.ensureProtocol(m.instance,StorageServiceInstance.Protocol.NFS);Assert.assertEquals(1,m.rows.size());
        Assert.assertTrue(json(m.operation.getPreviousSnapshotJson()).has("ordinaryInitialNfsReuse"));
    }

    @Test public void operatingWildcardOrConfiguredPlaceholderIsNeverSilentlyReused() {
        for(String mode:List.of("source","config","share","database")) {
            Manager m=new Manager();StorageServiceProtocolVO placeholder=new StorageServiceProtocolVO(29,StorageServiceInstance.Protocol.NFS,true,null,2049);
            ReflectionTestUtils.setField(placeholder,"id",4L);placeholder.setState(StorageServiceInstance.ResourceState.Ready);m.rows.add(placeholder);
            if(mode.equals("source")){m.source.add("desired-state/nfs-export-apply.json",m.target.deepCopy());m.snapshot();}
            if(mode.equals("config"))placeholder.setConfigJson("{\"protocolMode\":\"V4_ONLY\"}");
            if(mode.equals("share"))Mockito.when(m.shares.listByInstanceIdAndProtocol(Mockito.anyLong(),Mockito.any())).thenReturn(List.of(Mockito.mock(StorageFileShareVO.class)));
            EnableStorageServiceProtocolCmd cmd=(EnableStorageServiceProtocolCmd)StorageConfigCommandBinding.bind(EnableStorageServiceProtocolCmd.class,json("{\"instanceid\":29,\"protocol\":\"NFS\",\"listenip\":\""+IP+"\",\"port\":2049}"));
            if(mode.equals("database")){m.db.addProperty("changed",true);Assert.assertThrows(CloudRuntimeException.class,()->m.ordinaryInitialNfsEndpoint(m.instance,cmd,StorageServiceInstance.Protocol.NFS,2049,null));}
            else Assert.assertNull(m.ordinaryInitialNfsEndpoint(m.instance,cmd,StorageServiceInstance.Protocol.NFS,2049,null));
            Assert.assertNull(placeholder.getListenIp());
        }
    }

    @Test public void oldRuntimeWithoutExplicitCapabilityIsBlockedBeforeWriterProtocolRowsOrAddressMutation() {
        Manager m=new Manager();m.supported=false;
        Assert.assertTrue(Assert.assertThrows(CloudRuntimeException.class,()->m.enable(IP)).getMessage().contains("ENDPOINT_PREPARATION_UNSUPPORTED"));
        Assert.assertTrue(m.rows.isEmpty());Assert.assertTrue(m.events.isEmpty());
        Assert.assertFalse(json(m.operation.getPreviousSnapshotJson()).has("protocolEndpointPreparation"));
    }

    @Test public void runtimeReplacementBetweenPublicCheckAndWriterAcquisitionCannotSendDeferredFlagsOrChangeProtocolRows() {
        Manager m=new Manager();m.replaceRuntimeBeforeWriter=true;
        Assert.assertTrue(Assert.assertThrows(CloudRuntimeException.class,()->m.enable(IP)).getMessage().contains("ENDPOINT_PREPARATION_UNSUPPORTED"));
        Assert.assertEquals(2,m.capabilityChecks);Assert.assertEquals(1,m.writerStarts);
        Assert.assertTrue(m.rows.isEmpty());Assert.assertTrue(m.events.isEmpty());
        Mockito.verify(m.protocols,Mockito.never()).persist(Mockito.any());
        Mockito.verify(m.protocols,Mockito.never()).update(Mockito.anyLong(),Mockito.any());
        Mockito.verify(m.aliases,Mockito.never()).persist(Mockito.any());
        Assert.assertFalse(json(m.operation.getPreviousSnapshotJson()).has("protocolEndpointPreparation"));
    }

    @Test public void closedPublicPreparationDiagnosticsDoNotPromoteRawExceptionsOrNativePayloads() {
        StorageServiceManagerImpl m=new StorageServiceManagerImpl();
        for(String code:List.of("NFS_SOURCE_CHANGED","ENDPOINT_PREPARATION_FAILED","ENDPOINT_SOURCE_RESTORE_FAILED"))
            Assert.assertEquals(code,m.protocolPreparationFailureDiagnostic("{\"success\":false,\"lastFailure\":{\"code\":\""+code+"\"}}"));
        Assert.assertNull(m.protocolPreparationFailureDiagnostic("{\"success\":false,\"lastFailure\":{\"code\":\"raw-secret-or-traceback\"}}"));
        Assert.assertNull(m.protocolPreparationFailureDiagnostic("not-json"));
    }
}
