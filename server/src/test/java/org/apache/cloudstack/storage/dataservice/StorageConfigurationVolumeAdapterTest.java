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

import java.util.HashMap;
import java.util.Map;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.storage.dataservice.dao.StorageConfigArtifactDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class StorageConfigurationVolumeAdapterTest {
    private StorageServiceManagerImpl manager;
    private StorageConfigArtifactVO artifact;
    private StorageConfigArtifactDao artifacts;
    private StorageServiceInstanceVO target;
    private VolumeApiService api;
    private VolumeDetailsDao details;
    private VolumeDao volumes;
    private JsonObject allocation;
    private final Map<String,String> stored = new HashMap<>();
    private boolean inTransaction;

    @Before public void setup() throws Exception {
        manager = Mockito.spy(new StorageServiceManagerImpl());
        artifact = new StorageConfigArtifactVO();ReflectionTestUtils.setField(artifact,"id",7L);artifact.setMetadataJson("{}");
        artifacts = Mockito.mock(StorageConfigArtifactDao.class);
        Mockito.when(artifacts.lockRow(7L,true)).thenReturn(artifact);Mockito.when(artifacts.findById(7L)).thenReturn(artifact);
        Mockito.when(artifacts.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        ReflectionTestUtils.setField(manager,"storageConfigArtifactDao",artifacts);
        target = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(target.getAccountId()).thenReturn(2L);Mockito.when(target.getDataCenterId()).thenReturn(3L);
        api = Mockito.mock(VolumeApiService.class);ReflectionTestUtils.setField(manager,"volumeApiService",api);
        details = Mockito.mock(VolumeDetailsDao.class);ReflectionTestUtils.setField(manager,"configurationVolumeDetailsDao",details);
        volumes = Mockito.mock(VolumeDao.class);ReflectionTestUtils.setField(manager,"volumeDao",volumes);
        ReflectionTestUtils.setField(manager,"configurationProjectDao",Mockito.mock(com.cloud.projects.dao.ProjectDao.class));
        ReflectionTestUtils.setField(manager,"configurationStoragePoolDao",Mockito.mock(org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao.class));
        DiskOfferingDao offerings = Mockito.mock(DiskOfferingDao.class);DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(offering.getId()).thenReturn(4L);Mockito.when(offering.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);Mockito.when(offerings.lockRow(4L,true)).thenReturn(offering);Mockito.when(offerings.findByUuid(Mockito.anyString())).thenReturn(offering);ReflectionTestUtils.setField(manager,"configurationDiskOfferingDao",offerings);
        DataCenterDao zones = Mockito.mock(DataCenterDao.class);DataCenterVO zone = Mockito.mock(DataCenterVO.class);Mockito.when(zone.getUuid()).thenReturn("zone");Mockito.when(zones.findById(3L)).thenReturn(zone);ReflectionTestUtils.setField(manager,"dataCenterDao",zones);
        StorageConfigurationVolumePlanTest.Fixture fixture = new StorageConfigurationVolumePlanTest.Fixture(1);
        allocation = StorageConfigurationVolumeAllocationTest.entry(fixture.build(),0);
        Mockito.doReturn(fixture.offering()).when(manager).configurationOfferingCatalog(Mockito.anyString(),Mockito.any());
    }
    private JsonObject receipt(String phase) {
        JsonObject value=new JsonObject();value.addProperty("plannedUuid",allocation.get("plannedUuid").getAsString());
        value.addProperty("version",1);value.addProperty("phase",phase);value.addProperty("state",phase);value.addProperty("formatStarted",false);value.addProperty("dataPolicy","PRESERVE");value.add("provenance",allocation.deepCopy());return value;
    }
    private MockedStatic<Transaction> transaction() {
        MockedStatic<Transaction> mock=Mockito.mockStatic(Transaction.class);
        mock.when(()->Transaction.execute(Mockito.any(TransactionCallback.class))).thenAnswer(call->{
            Assert.assertFalse(inTransaction);inTransaction=true;
            try{return ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(null);}finally{inTransaction=false;}
        });return mock;
    }
    @Test public void receiptCompareAndSwapRejectsAConcurrentWinnerWithoutErasingItsEvidence() {
        JsonObject intent=receipt("INTENT");manager.configurationReceiptCompareAndSwap(artifact,null,intent);
        JsonObject allocated=receipt("ALLOCATED");allocated.addProperty("version",2);manager.configurationReceiptCompareAndSwap(artifact,intent,allocated);
        Assert.assertThrows(StorageConfigurationVolumeAllocation.ReceiptConflictException.class,()->manager.configurationReceiptCompareAndSwap(artifact,intent,receipt("READY")));
        Assert.assertEquals(allocated,JsonParser.parseString(artifact.getMetadataJson()).getAsJsonObject().getAsJsonObject("receipts").get(allocation.get("plannedUuid").getAsString()));
    }
    @Test public void standardAllocationFullProvenanceAndReceiptShareOneOuterTransaction() throws Exception {
        JsonObject intent=receipt("INTENT");manager.configurationReceiptCompareAndSwap(artifact,null,intent);
        VolumeVO volume=Mockito.mock(VolumeVO.class);Mockito.when(volume.getId()).thenReturn(9L);Mockito.when(volume.getUuid()).thenReturn(allocation.get("plannedUuid").getAsString());Mockito.when(volumes.findById(9L)).thenReturn(volume);
        Mockito.when(volume.getInstanceId()).thenReturn(null);Mockito.when(volume.getPoolId()).thenReturn(null);Mockito.when(volume.getVolumeType()).thenReturn(com.cloud.storage.Volume.Type.DATADISK);Mockito.when(volume.getState()).thenReturn(com.cloud.storage.Volume.State.Allocated);
        Mockito.when(volume.getAccountId()).thenReturn(2L);Mockito.when(volume.getDataCenterId()).thenReturn(3L);Mockito.when(volume.getProvisioningType()).thenReturn(com.cloud.storage.Storage.ProvisioningType.SPARSE);
        Mockito.when(details.listDetailsKeyPairs(9L)).thenReturn(stored);
        Mockito.doAnswer(call->{Assert.assertTrue(inTransaction);stored.put(call.getArgument(1),call.getArgument(2));return null;}).when(details).addDetail(Mockito.eq(9L),Mockito.anyString(),Mockito.anyString(),Mockito.eq(false));
        Mockito.when(api.allocVolume(Mockito.eq(2L),Mockito.eq(3L),Mockito.eq(4L),Mockito.isNull(),Mockito.isNull(),Mockito.anyString(),Mockito.any(),Mockito.eq(true),Mockito.isNull(),Mockito.isNull(),Mockito.eq(allocation.get("plannedUuid").getAsString()),Mockito.isNull())).thenAnswer(call->{Assert.assertTrue(inTransaction);return volume;});
        StorageConfigurationVolumeAllocation.Runtime runtime=manager.configurationVolumeAllocationRuntime(target,artifact);
        try(MockedStatic<Transaction> ignored=transaction()){runtime.allocateAndRecord(allocation,intent,receipt("ALLOCATED"));}
        Assert.assertFalse(inTransaction);Assert.assertEquals(allocation.size(),stored.size());
        for(Map.Entry<String,com.google.gson.JsonElement> field:allocation.entrySet())Assert.assertEquals(field.getValue(),JsonParser.parseString(stored.get("storage.config.allocation."+field.getKey())));
        Assert.assertEquals("ALLOCATED",runtime.loadReceipt(volume.getUuid()).get("phase").getAsString());
        Mockito.verify(api,Mockito.never()).createVolume(Mockito.anyLong(),Mockito.any(),Mockito.any(),Mockito.any(),Mockito.any());
        Mockito.verify(api,Mockito.never()).attachVolumeToVM(Mockito.any(),Mockito.any(),Mockito.any(),Mockito.any());
    }
    @Test public void foreignAllocationIntentCannotAllocateOrOverwriteItsReceipt() {
        manager.configurationReceiptCompareAndSwap(artifact,null,receipt("INTENT"));
        JsonObject changed=receipt("INTENT");changed.addProperty("version",99);
        StorageConfigurationVolumeAllocation.Runtime runtime=manager.configurationVolumeAllocationRuntime(target,artifact);
        try(MockedStatic<Transaction> ignored=transaction()) {
            Assert.assertThrows(StorageConfigurationVolumeAllocation.ReceiptConflictException.class,()->runtime.allocateAndRecord(allocation,changed,receipt("ALLOCATED")));
        }
        Mockito.verifyNoInteractions(api,details);
    }
    @Test public void restoreFailureCannotAuthorizeUnpublishedDataDeletion() {
        StorageConfigurationVolumeAllocation.Runtime runtime=manager.configurationVolumeAllocationRuntime(target,artifact);
        Assert.assertThrows(CloudRuntimeException.class,()->runtime.cleanupSafety(allocation));Assert.assertThrows(CloudRuntimeException.class,()->runtime.deleteUnpublished(allocation));
        Mockito.verifyNoInteractions(api);
    }
}
