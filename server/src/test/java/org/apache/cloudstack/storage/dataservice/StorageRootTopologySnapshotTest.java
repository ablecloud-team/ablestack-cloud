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
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.Volume;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;

public class StorageRootTopologySnapshotTest {
    private UserVmVO vm(){
        UserVmVO vm=Mockito.mock(UserVmVO.class);Mockito.when(vm.getId()).thenReturn(7L);Mockito.when(vm.getUuid()).thenReturn("vm");
        return vm;
    }
    private VolumeVO volume(long id,Volume.Type type){
        VolumeVO volume=Mockito.mock(VolumeVO.class);
        Mockito.when(volume.getId()).thenReturn(id);Mockito.when(volume.getUuid()).thenReturn("volume-"+id);
        Mockito.when(volume.getInstanceId()).thenReturn(7L);Mockito.when(volume.getVolumeType()).thenReturn(type);
        return volume;
    }
    @Test public void exchangingOnlyTheRootDoesNotChangeAnyDataDiskIdentity(){
        UserVmVO vm=vm();VolumeVO data=volume(22,Volume.Type.DATADISK);
        JsonObject before=StorageRootTopologySnapshot.capture(vm,List.of(),List.of(),List.of(volume(10,Volume.Type.ROOT),data));
        JsonObject after=StorageRootTopologySnapshot.capture(vm,List.of(),List.of(),List.of(volume(11,Volume.Type.ROOT),data));
        StorageRootTopologySnapshot.requireSame(before,after);
        Mockito.when(data.getDeviceId()).thenReturn(3L);
        Assert.assertThrows(CloudRuntimeException.class,()->StorageRootTopologySnapshot.requireSame(before,
                StorageRootTopologySnapshot.capture(vm,List.of(),List.of(),List.of(data))));
    }
    @Test public void additionalDataVolumesCannotDisappearDuringCutover(){
        UserVmVO vm=vm();VolumeVO first=volume(22,Volume.Type.DATADISK),second=volume(23,Volume.Type.DATADISK);
        JsonObject before=StorageRootTopologySnapshot.capture(vm,List.of(),List.of(),List.of(first,second));
        Assert.assertThrows(CloudRuntimeException.class,()->StorageRootTopologySnapshot.requireSame(before,
                StorageRootTopologySnapshot.capture(vm,List.of(),List.of(),List.of(first))));
    }
    @Test public void secondaryIpAndMacChangesAreRejectedIndependentlyOfRoot(){
        UserVmVO vm=vm();NicVO nic=Mockito.mock(NicVO.class);
        Mockito.when(nic.getId()).thenReturn(33L);Mockito.when(nic.getInstanceId()).thenReturn(7L);Mockito.when(nic.getMacAddress()).thenReturn("00:00:00:00:00:01");
        NicSecondaryIpVO alias=Mockito.mock(NicSecondaryIpVO.class);Mockito.when(alias.getNicId()).thenReturn(33L);Mockito.when(alias.getIp4Address()).thenReturn("10.1.1.70");
        JsonObject before=StorageRootTopologySnapshot.capture(vm,List.of(nic),List.of(alias),List.of());
        Mockito.when(alias.getIp4Address()).thenReturn("10.1.1.71");
        Assert.assertThrows(CloudRuntimeException.class,()->StorageRootTopologySnapshot.requireSame(before,
                StorageRootTopologySnapshot.capture(vm,List.of(nic),List.of(alias),List.of())));
        Mockito.when(alias.getIp4Address()).thenReturn("10.1.1.70");Mockito.when(nic.getMacAddress()).thenReturn("00:00:00:00:00:02");
        Assert.assertThrows(CloudRuntimeException.class,()->StorageRootTopologySnapshot.requireSame(before,
                StorageRootTopologySnapshot.capture(vm,List.of(nic),List.of(alias),List.of())));
    }
}
