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
import java.util.*;
import com.google.gson.*;
import com.cloud.vm.*;
import com.cloud.vm.dao.*;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.apache.cloudstack.storage.sharedfs.*;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.*;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
public class StorageCanonicalRecoveryRuntimeTest {
    private static final class Manager extends StorageServiceManagerImpl {
        JsonObject frozen,live;boolean healthFailure,foreignMac,badReceipt;List<String> commands=new ArrayList<>();
        @Override protected JsonObject nativeConfigurationGeneration(StorageServiceInstanceVO instance,StorageServiceOperationVO operation,String action){commands.add(action);return live.deepCopy();}
        @Override protected void verifyReconciledStorageDesiredState(StorageServiceInstanceVO instance){commands.add("verify-runtime");if(healthFailure)throw new CloudRuntimeException("Runtime differs from restored DB desired state");}
        @Override protected JsonObject rootGuest(StorageServiceInstanceVO instance,String command,JsonObject request,int timeout){commands.add(command);if(command.endsWith("frozen"))return frozen.deepCopy();JsonObject result=new JsonObject();result.addProperty("success",true);
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

}
