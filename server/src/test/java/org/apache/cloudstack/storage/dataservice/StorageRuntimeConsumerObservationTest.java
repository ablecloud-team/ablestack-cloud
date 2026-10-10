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
import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageRuntimeConsumerObservationTest {
    private static final String HELPER="4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a";
    private static class Manager extends StorageServiceRuntimeUpgradeManagerImpl {
        JsonObject caps=new JsonObject();int capabilityReads;
        Manager(){caps.addProperty("signedRuntimeReadback",true);caps.addProperty("updaterSha256",HELPER);caps.addProperty("platformVersionKnown",true);caps.addProperty("platformVersion","4.23.0.0");caps.addProperty("productVersion","4.23.0.0");caps.addProperty("templateManifestSha256","b".repeat(64));}
        @Override protected byte[] resource(String path){return new byte[]{1};}
        @Override protected JsonObject invoke(StorageServiceInstanceVO instance,StorageServiceRuntimeOperation operation,String transaction,JsonObject request){Assert.assertEquals(StorageServiceRuntimeOperation.CAPABILITIES,operation);capabilityReads++;return caps.deepCopy();}
    }
    private Manager manager;private StorageServiceInstanceVO instance;private VMInstanceDao vms;private VolumeDao volumes;private VMInstanceVO vm;private VolumeVO root;
    @Before public void setup(){
        manager=new Manager();instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(6L);Mockito.when(instance.getVmId()).thenReturn(60L);Mockito.when(instance.getUuid()).thenReturn("instance-6");Mockito.when(instance.getAccountId()).thenReturn(2L);Mockito.when(instance.getDataCenterId()).thenReturn(1L);
        vm=Mockito.mock(VMInstanceVO.class);Mockito.when(vm.getId()).thenReturn(60L);Mockito.when(vm.getHostId()).thenReturn(99L);Mockito.when(vm.getTemplateId()).thenReturn(80L);
        vms=Mockito.mock(VMInstanceDao.class);Mockito.when(vms.findById(60L)).thenReturn(vm);HostVO host=Mockito.mock(HostVO.class);Mockito.when(host.getVersion()).thenReturn("4.23.0.0-Mold.Europa-202610011115");HostDao hosts=Mockito.mock(HostDao.class);Mockito.when(hosts.findById(99L)).thenReturn(host);
        root=Mockito.mock(VolumeVO.class);Mockito.when(root.getId()).thenReturn(70L);Mockito.when(root.getUuid()).thenReturn("root-70");Mockito.when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);Mockito.when(root.getState()).thenReturn(Volume.State.Ready);Mockito.when(root.getInstanceId()).thenReturn(60L);Mockito.when(root.getTemplateId()).thenReturn(80L);Mockito.when(root.getAccountId()).thenReturn(2L);Mockito.when(root.getDataCenterId()).thenReturn(1L);
        volumes=Mockito.mock(VolumeDao.class);Mockito.when(volumes.findByInstanceAndType(60L,Volume.Type.ROOT)).thenReturn(List.of(root));ReflectionTestUtils.setField(manager,"vmInstanceDao",vms);ReflectionTestUtils.setField(manager,"runtimeHostDao",hosts);ReflectionTestUtils.setField(manager,"runtimeVolumeDao",volumes);
    }
    @Test public void freshGuestManifestAndCurrentHostPlatformAreReadOnEveryObservation(){
        JsonObject first=manager.freshConsumerObservation(instance);Assert.assertEquals("4.23.0.0-Mold.Europa-202610011115",first.get("agentVersion").getAsString());Assert.assertEquals("4.23.0.0",first.get("templatePlatformVersion").getAsString());Assert.assertTrue(first.get("platformVersionKnown").getAsBoolean());Assert.assertTrue(first.get("updaterVerified").getAsBoolean());
        manager.caps.addProperty("platformVersion","4.24.0.0");manager.caps.addProperty("productVersion","4.24.0.0");Assert.assertEquals("4.24.0.0",manager.freshConsumerObservation(instance).get("templatePlatformVersion").getAsString());Assert.assertEquals(2,manager.capabilityReads);
    }
    @Test public void cachedManagerProductProjectionNeverBecomesGuestVersion(){
        manager.caps.remove("platformVersionKnown");manager.caps.remove("platformVersion");manager.caps.addProperty("productVersion","4.23.0.0");JsonObject observed=manager.freshConsumerObservation(instance);Assert.assertTrue(observed.get("templatePlatformVersion").isJsonNull());Assert.assertFalse(observed.get("platformObservationRecorded").getAsBoolean());
    }
    @Test public void wrongUpdaterProvenanceCannotAttestGuestPlatform(){
        manager.caps.addProperty("updaterSha256","c".repeat(64));JsonObject observed=manager.freshConsumerObservation(instance);Assert.assertFalse(observed.get("updaterVerified").getAsBoolean());Assert.assertFalse(observed.get("platformObservationRecorded").getAsBoolean());Assert.assertTrue(observed.get("templatePlatformVersion").isJsonNull());
    }
    @Test public void explicitProtectedUnknownIsRecordedButMalformedKnownAttestationIsNot(){
        manager.caps.addProperty("platformVersionKnown",false);manager.caps.add("platformVersion",JsonNull.INSTANCE);manager.caps.add("productVersion",JsonNull.INSTANCE);JsonObject unknown=manager.freshConsumerObservation(instance);Assert.assertTrue(unknown.get("platformObservationRecorded").getAsBoolean());Assert.assertFalse(unknown.get("platformVersionKnown").getAsBoolean());
        manager.caps.addProperty("platformVersionKnown",true);manager.caps.addProperty("platformVersion","4.23.0.0");manager.caps.addProperty("productVersion","4.23.0.0");manager.caps.remove("templateManifestSha256");JsonObject malformed=manager.freshConsumerObservation(instance);Assert.assertFalse(malformed.get("platformObservationRecorded").getAsBoolean());Assert.assertTrue(malformed.get("templatePlatformVersion").isJsonNull());
    }
    @Test public void vmHostMigrationDuringCapabilitiesInvalidatesObservation(){
        VMInstanceVO changed=Mockito.mock(VMInstanceVO.class);Mockito.when(changed.getHostId()).thenReturn(100L);Mockito.when(vms.findById(60L)).thenReturn(vm,changed);Assert.assertThrows(CloudRuntimeException.class,()->manager.freshConsumerObservation(instance));
    }
    @Test public void actualRootBindingPinsUuidOwnerZoneVmAndTemplate(){
        JsonObject binding=manager.sourceRootBinding(instance);Assert.assertEquals(70L,binding.get("rootVolumeId").getAsLong());Assert.assertEquals("root-70",binding.get("rootVolumeUuid").getAsString());Assert.assertEquals(60L,binding.get("vmId").getAsLong());Assert.assertEquals(80L,binding.get("templateId").getAsLong());Assert.assertEquals(2L,binding.get("accountId").getAsLong());Assert.assertEquals(1L,binding.get("zoneId").getAsLong());
    }
    @Test public void foreignOwnerDetachedOrDifferentTemplateRootCannotProduceLkgBinding(){
        Mockito.when(root.getAccountId()).thenReturn(3L);Assert.assertThrows(CloudRuntimeException.class,()->manager.sourceRootBinding(instance));Mockito.when(root.getAccountId()).thenReturn(2L);Mockito.when(root.getInstanceId()).thenReturn(null);Assert.assertThrows(CloudRuntimeException.class,()->manager.sourceRootBinding(instance));Mockito.when(root.getInstanceId()).thenReturn(60L);Mockito.when(root.getTemplateId()).thenReturn(81L);Assert.assertThrows(CloudRuntimeException.class,()->manager.sourceRootBinding(instance));
    }
    @Test public void zeroOrMultipleRootRowsNeverProduceAmbiguousApproval(){
        Mockito.when(volumes.findByInstanceAndType(60L,Volume.Type.ROOT)).thenReturn(List.of());Assert.assertThrows(CloudRuntimeException.class,()->manager.sourceRootBinding(instance));Mockito.when(volumes.findByInstanceAndType(60L,Volume.Type.ROOT)).thenReturn(List.of(root,root));Assert.assertThrows(CloudRuntimeException.class,()->manager.sourceRootBinding(instance));
    }
    @Test public void publicCapabilitiesCarriesFreshConsumerObservationJsonForSignedCatalogUi(){
        org.apache.cloudstack.storage.sharedfs.SharedFSVO shared=Mockito.mock(org.apache.cloudstack.storage.sharedfs.SharedFSVO.class);Mockito.when(shared.getVmId()).thenReturn(60L);org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao sharedDao=Mockito.mock(org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao.class);Mockito.when(sharedDao.findById(3L)).thenReturn(shared);
        org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao instances=Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao.class);Mockito.when(instances.findByVmId(60L)).thenReturn(instance);ReflectionTestUtils.setField(manager,"sharedFSDao",sharedDao);ReflectionTestUtils.setField(manager,"instanceDao",instances);
        org.apache.cloudstack.api.command.admin.storage.dataservice.GetStorageServiceRuntimeUpgradeCapabilitiesCmd cmd=Mockito.mock(org.apache.cloudstack.api.command.admin.storage.dataservice.GetStorageServiceRuntimeUpgradeCapabilitiesCmd.class);Mockito.when(cmd.getSharedFileSystemId()).thenReturn(3L);
        org.apache.cloudstack.api.response.StorageServiceRuntimeCapabilityResponse response=manager.capabilities(cmd);String serialized=(String)ReflectionTestUtils.getField(response,"consumerObservation");Assert.assertNotNull(serialized);JsonObject observed=com.google.gson.JsonParser.parseString(serialized).getAsJsonObject();Assert.assertEquals("4.23.0.0",observed.get("templatePlatformVersion").getAsString());Assert.assertTrue(observed.get("platformObservationRecorded").getAsBoolean());Assert.assertEquals(2,manager.capabilityReads);
    }

    private StorageServiceGuestCommandDispatcher attachedDataJournal(String nativeJson){
        VolumeVO data=Mockito.mock(VolumeVO.class);Mockito.when(data.getUuid()).thenReturn("11111111-2222-4333-8444-555555555555");Mockito.when(data.getVolumeType()).thenReturn(Volume.Type.DATADISK);Mockito.when(data.getState()).thenReturn(Volume.State.Ready);Mockito.when(data.getInstanceId()).thenReturn(60L);Mockito.when(data.getAccountId()).thenReturn(2L);Mockito.when(data.getDataCenterId()).thenReturn(1L);Mockito.when(volumes.findByInstanceAndType(60L,Volume.Type.DATADISK)).thenReturn(List.of(data));
        StorageServiceGuestCommandDispatcher dispatcher=Mockito.mock(StorageServiceGuestCommandDispatcher.class);Mockito.when(dispatcher.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(true,"ok",nativeJson));ReflectionTestUtils.setField(manager,"guestCommandDispatcher",dispatcher);return dispatcher;
    }
    @Test public void inactiveChildWithPartialJournalStillBlocksRuntimeMutation(){
        StorageServiceGuestCommandDispatcher dispatcher=attachedDataJournal("{\"success\":true,\"status\":\"FOUND\",\"formatterActive\":false,\"operation\":{\"formatStarted\":true,\"phase\":\"TIMED_OUT_PENDING_RECONCILE\"}}");Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRuntimeActivationSafety(instance));Mockito.verify(dispatcher).dispatch(Mockito.any());
    }
    @Test public void completedJournalPermitsMutationButMissingObservationDoesNot(){
        attachedDataJournal("{\"success\":true,\"status\":\"FOUND\",\"formatterActive\":false,\"operation\":{\"formatStarted\":true,\"phase\":\"COMPLETE\"}}");manager.requireRuntimeActivationSafety(instance);attachedDataJournal("{\"success\":false}");Assert.assertThrows(CloudRuntimeException.class,()->manager.requireRuntimeActivationSafety(instance));
    }
    @Test public void capabilitiesRemainsReadableWhenFormatterJournalWouldBlockMutation(){
        attachedDataJournal("{\"success\":true,\"status\":\"FOUND\",\"formatterActive\":false,\"operation\":{\"formatStarted\":true,\"phase\":\"TIMED_OUT_PENDING_RECONCILE\"}}");Assert.assertTrue(manager.freshConsumerObservation(instance).get("platformVersionKnown").getAsBoolean());
    }

}
