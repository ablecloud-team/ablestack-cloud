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
}
