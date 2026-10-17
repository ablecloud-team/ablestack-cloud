// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.exception.InvalidParameterValueException;

public class StorageConfigVolumeBindingTest {
    @Test public void rootForeignAndUnreadyVolumesAreRejectedBeforeServiceReconciliation() {
        StorageServiceManagerImpl manager = new StorageServiceManagerImpl();
        VolumeDao dao = Mockito.mock(VolumeDao.class);ReflectionTestUtils.setField(manager, "volumeDao", dao);
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getVmId()).thenReturn(43L);Mockito.when(instance.getAccountId()).thenReturn(2L);Mockito.when(instance.getDataCenterId()).thenReturn(1L);
        VolumeVO volume = Mockito.mock(VolumeVO.class);Mockito.when(dao.findByUuid("selected")).thenReturn(volume);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.configurationVolumeId(instance, "selected"));
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volume.getAccountId()).thenReturn(3L);
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.configurationVolumeId(instance, "selected"));
        Mockito.when(volume.getAccountId()).thenReturn(2L);Mockito.when(volume.getDataCenterId()).thenReturn(1L);
        Mockito.when(volume.getInstanceId()).thenReturn(44L);
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.configurationVolumeId(instance, "selected"));
        Mockito.when(volume.getInstanceId()).thenReturn(43L);Mockito.when(volume.getState()).thenReturn(Volume.State.Allocated);
        Assert.assertThrows(InvalidParameterValueException.class, () -> manager.configurationVolumeId(instance, "selected"));
    }

    private static final class InventoryFixture {
        final StorageServiceManagerImpl manager=new StorageServiceManagerImpl();
        final VolumeDao volumes=Mockito.mock(VolumeDao.class);
        final org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao shared=Mockito.mock(org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao.class);
        final StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        final org.apache.cloudstack.storage.sharedfs.SharedFSVO fileSystem=Mockito.mock(org.apache.cloudstack.storage.sharedfs.SharedFSVO.class);
        final VolumeVO initial=volume(9,"11111111-1111-4111-8111-111111111111",20L*1024*1024*1024),extra=volume(18,"22222222-2222-4222-8222-222222222222",2L*1024*1024*1024);
        InventoryFixture() {
            ReflectionTestUtils.setField(manager,"volumeDao",volumes);ReflectionTestUtils.setField(manager,"sharedFSDao",shared);
            Mockito.when(instance.getVmId()).thenReturn(43L);Mockito.when(instance.getAccountId()).thenReturn(2L);Mockito.when(instance.getDomainId()).thenReturn(3L);Mockito.when(instance.getDataCenterId()).thenReturn(1L);
            Mockito.when(fileSystem.getVmId()).thenReturn(43L);Mockito.when(fileSystem.getAccountId()).thenReturn(2L);Mockito.when(fileSystem.getDomainId()).thenReturn(3L);Mockito.when(fileSystem.getDataCenterId()).thenReturn(1L);Mockito.when(fileSystem.getVolumeId()).thenReturn(9L);
            Mockito.when(shared.findByVm(43L)).thenReturn(fileSystem);Mockito.when(volumes.findById(9L)).thenReturn(initial);Mockito.when(volumes.findById(18L)).thenReturn(extra);
            Mockito.when(volumes.findByInstanceAndType(43L,Volume.Type.DATADISK)).thenReturn(java.util.List.of(initial,extra));
        }
        private static VolumeVO volume(long id,String uuid,long size) {
            VolumeVO volume=Mockito.mock(VolumeVO.class);Mockito.when(volume.getId()).thenReturn(id);Mockito.when(volume.getUuid()).thenReturn(uuid);
            Mockito.when(volume.getName()).thenReturn(uuid);Mockito.when(volume.getSize()).thenReturn(size);Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
            Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);Mockito.when(volume.getAccountId()).thenReturn(2L);Mockito.when(volume.getDomainId()).thenReturn(3L);Mockito.when(volume.getDataCenterId()).thenReturn(1L);Mockito.when(volume.getInstanceId()).thenReturn(43L);return volume;
        }
    }
    @Test public void manifestSourceLineageUsesOnlyExactOwnedSharedFsAndVmRowsAndOmitsUnknownRows() {
        for(int mode=0;mode<10;mode++) {
            InventoryFixture fixture=new InventoryFixture();
            ReflectionTestUtils.setField(fixture.manager,"dataCenterDao",Mockito.mock(com.cloud.dc.dao.DataCenterDao.class));
            ReflectionTestUtils.setField(fixture.manager,"serviceOfferingDao",Mockito.mock(com.cloud.service.dao.ServiceOfferingDao.class));
            com.cloud.vm.dao.VMInstanceDao vms=Mockito.mock(com.cloud.vm.dao.VMInstanceDao.class);
            ReflectionTestUtils.setField(fixture.manager,"vmInstanceDao",vms);
            com.cloud.vm.VMInstanceVO vm=Mockito.mock(com.cloud.vm.VMInstanceVO.class);
            Mockito.when(fixture.instance.getState()).thenReturn(StorageServiceInstance.State.Stopped);
            Mockito.when(vm.getId()).thenReturn(43L);Mockito.when(vm.getAccountId()).thenReturn(2L);
            Mockito.when(vm.getDomainId()).thenReturn(3L);Mockito.when(vm.getDataCenterId()).thenReturn(1L);
            Mockito.when(vm.getUuid()).thenReturn("33333333-3333-4333-8333-333333333333");
            Mockito.when(vm.getState()).thenReturn(com.cloud.vm.VirtualMachine.State.Stopped);
            Mockito.when(fixture.fileSystem.getUuid()).thenReturn("44444444-4444-4444-8444-444444444444");
            Mockito.when(vms.findById(43L)).thenReturn(vm);
            if(mode==1)Mockito.when(vms.findById(43L)).thenReturn(null);
            if(mode==2)Mockito.when(fixture.shared.findByVm(43L)).thenReturn(null);
            if(mode==3)Mockito.when(vm.getAccountId()).thenReturn(99L);
            if(mode==4)Mockito.when(vm.getDomainId()).thenReturn(99L);
            if(mode==5)Mockito.when(vm.getDataCenterId()).thenReturn(99L);
            if(mode==6)Mockito.when(fixture.fileSystem.getAccountId()).thenReturn(99L);
            if(mode==7)Mockito.when(fixture.fileSystem.getDomainId()).thenReturn(99L);
            if(mode==8)Mockito.when(fixture.fileSystem.getDataCenterId()).thenReturn(99L);
            if(mode==9)Mockito.when(fixture.fileSystem.getVmId()).thenReturn(99L);
            if(mode>=3)Assert.assertThrows(InvalidParameterValueException.class,()->fixture.manager.configurationInstanceMetadata(fixture.instance));
            else {
                com.google.gson.JsonObject result=fixture.manager.configurationInstanceMetadata(fixture.instance);
                Assert.assertEquals(mode!=1,result.has("sourceVMUuid"));Assert.assertEquals(mode!=1,result.has("sourceVMState"));
                Assert.assertEquals(mode!=2,result.has("sourceSharedFSUuid"));
                if(mode!=1){Assert.assertEquals(vm.getUuid(),result.get("sourceVMUuid").getAsString());Assert.assertEquals("Stopped",result.get("sourceVMState").getAsString());}
                if(mode!=2)Assert.assertEquals(fixture.fileSystem.getUuid(),result.get("sourceSharedFSUuid").getAsString());
            }
        }
    }
    @Test public void emptyServiceInventoryIncludesExactDefaultAndAttachedDataButNeverRootOrUnrelatedUnattached() {
        InventoryFixture fixture=new InventoryFixture();VolumeVO root=Mockito.mock(VolumeVO.class);Mockito.when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(fixture.volumes.findByInstanceAndType(43L,Volume.Type.DATADISK)).thenReturn(java.util.List.of(fixture.extra,fixture.initial,root));
        java.util.Map<Long,com.google.gson.JsonObject> inventory=fixture.manager.configurationVolumeMetadata(fixture.instance,"{\"tables\":[]}");
        Assert.assertEquals(java.util.Set.of(9L,18L),inventory.keySet());Assert.assertEquals(20L*1024*1024*1024,inventory.get(9L).get("size").getAsLong());
        Assert.assertEquals("DATADISK",inventory.get(18L).get("type").getAsString());Mockito.verify(fixture.volumes,Mockito.never()).listAll();
    }
    @Test public void referencedDataIsDeduplicatedWithoutErasingItsKnownFilesystemIdentity() {
        InventoryFixture fixture=new InventoryFixture();
        String snapshot="{\"tables\":[{\"rows\":[{\"volume_id\":18,\"config_json\":\"{\\\"filesystemUuid\\\":\\\"same-filesystem\\\"}\"}]}]}";
        java.util.Map<Long,com.google.gson.JsonObject> inventory=fixture.manager.configurationVolumeMetadata(fixture.instance,snapshot);
        Assert.assertEquals(2,inventory.size());Assert.assertEquals("same-filesystem",inventory.get(18L).get("filesystemUuid").getAsString());
        Mockito.when(fixture.fileSystem.getAccountId()).thenReturn(9L);
        Assert.assertThrows(InvalidParameterValueException.class,()->fixture.manager.configurationVolumeMetadata(fixture.instance,snapshot));
    }
    @Test public void foreignOwnerDomainZoneVmAndUnattachedDaoMemberCannotEnterPortableInventory() {
        for(int mode=0;mode<5;mode++) {
            InventoryFixture fixture=new InventoryFixture();
            if(mode==0)Mockito.when(fixture.extra.getAccountId()).thenReturn(9L);
            if(mode==1)Mockito.when(fixture.extra.getDomainId()).thenReturn(9L);
            if(mode==2)Mockito.when(fixture.extra.getDataCenterId()).thenReturn(9L);
            if(mode==3)Mockito.when(fixture.extra.getInstanceId()).thenReturn(99L);
            if(mode==4)Mockito.when(fixture.extra.getInstanceId()).thenReturn(null);
            Assert.assertThrows(InvalidParameterValueException.class,()->fixture.manager.configurationVolumeMetadata(fixture.instance,"{\"tables\":[]}"));
        }
    }

}
