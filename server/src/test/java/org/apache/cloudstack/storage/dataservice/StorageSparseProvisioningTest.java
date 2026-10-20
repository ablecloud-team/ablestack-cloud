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
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
public class StorageSparseProvisioningTest {
    private StorageServiceManagerImpl manager;
    private DiskOfferingDao offerings;
    private VolumeVO previous;
    private VMTemplateVO target;
    private DiskOfferingVO original,sparse;
    @Before public void setup(){manager=new StorageServiceManagerImpl();offerings=Mockito.mock(DiskOfferingDao.class);ReflectionTestUtils.setField(manager,"configurationDiskOfferingDao",offerings);previous=Mockito.mock(VolumeVO.class);Mockito.when(previous.getDiskOfferingId()).thenReturn(1L);Mockito.when(previous.getSize()).thenReturn(1024L);target=Mockito.mock(VMTemplateVO.class);Mockito.when(target.getSize()).thenReturn(1024L);original=offering(1,ProvisioningType.THIN);sparse=offering(2,ProvisioningType.SPARSE);Mockito.when(offerings.findById(1L)).thenReturn(original);Mockito.when(offerings.findById(2L)).thenReturn(sparse);Mockito.when(offerings.listAll()).thenReturn(List.of(original,sparse));}
    private DiskOfferingVO offering(long id,ProvisioningType type){DiskOfferingVO value=Mockito.mock(DiskOfferingVO.class);Mockito.when(value.getId()).thenReturn(id);Mockito.when(value.getUuid()).thenReturn("offering-"+id);Mockito.when(value.getState()).thenReturn(DiskOffering.State.Active);Mockito.when(value.getProvisioningType()).thenReturn(type);Mockito.when(value.isCustomized()).thenReturn(true);Mockito.when(value.getCacheMode()).thenReturn(DiskOffering.DiskCacheMode.WRITEBACK);return value;}
    @Test public void oneCompatibleSparseOfferingIsSelectedWithoutChangingExistingThinRoot(){JsonObject result=manager.rootProvisioningPlan(previous,target,null);Assert.assertEquals("SPARSE",result.get("selectedProvisioningType").getAsString());Assert.assertEquals(1,result.getAsJsonArray("choices").size());Assert.assertEquals(0,result.getAsJsonArray("blockers").size());Mockito.verify(previous,Mockito.never()).setProvisioningType(Mockito.any());}
    @Test public void thinUnknownWrongCacheAndNoCompatibleOfferingCannotStageNewRoot(){Assert.assertTrue(manager.rootProvisioningPlan(previous,target,1L).getAsJsonArray("blockers").size()>0);Mockito.when(sparse.getCacheMode()).thenReturn(DiskOffering.DiskCacheMode.NONE);Assert.assertTrue(manager.rootProvisioningPlan(previous,target,null).getAsJsonArray("blockers").size()>0);}
    @Test public void multipleCompatibleChoicesRequireExplicitSelection(){DiskOfferingVO fat=offering(3,ProvisioningType.FAT);Mockito.when(offerings.listAll()).thenReturn(List.of(sparse,fat));Assert.assertFalse(manager.rootProvisioningPlan(previous,target,null).has("selectedDiskOfferingUuid"));Assert.assertEquals("FAT",manager.rootProvisioningPlan(previous,target,3L).get("selectedProvisioningType").getAsString());}
    @Test public void newFormattingRequiresVolumeAndOfferingToAgreeOnSparseOrFat(){Mockito.when(previous.getProvisioningType()).thenReturn(ProvisioningType.THIN);Assert.assertThrows(InvalidParameterValueException.class,()->manager.requireSparseNewFilesystem(previous));Mockito.when(previous.getProvisioningType()).thenReturn(ProvisioningType.SPARSE);Assert.assertThrows(InvalidParameterValueException.class,()->manager.requireSparseNewFilesystem(previous));Mockito.when(previous.getDiskOfferingId()).thenReturn(2L);manager.requireSparseNewFilesystem(previous);}
}
