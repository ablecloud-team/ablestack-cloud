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
package com.cloud.storage;

import java.util.ArrayList;
import java.util.List;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.event.UsageEventUtils;
import org.apache.cloudstack.context.CallContext;
import org.junit.Before;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

public class VolumeMetadataPathPreservationTest {
    private VolumeApiServiceImpl service;
    private VolumeDao volumes;
    private DiskOfferingDao offerings;
    private VolumeVO volume;
    private final List<String> persistedPaths=new ArrayList<>();
    @Before public void setup() {
        service=new VolumeApiServiceImpl();volumes=Mockito.mock(VolumeDao.class);offerings=Mockito.mock(DiskOfferingDao.class);
        Account account=Mockito.mock(Account.class);Mockito.when(account.getId()).thenReturn(3L);User user=Mockito.mock(User.class);Mockito.when(user.getId()).thenReturn(4L);CallContext.register(user,account);
        AccountManager accounts=Mockito.mock(AccountManager.class);Mockito.when(accounts.isRootAdmin(3L)).thenReturn(true);
        ReflectionTestUtils.setField(service,"_accountMgr",accounts);ReflectionTestUtils.setField(service,"_volsDao",volumes);ReflectionTestUtils.setField(service,"_diskOfferingDao",offerings);ReflectionTestUtils.setField(service,"_resourceLimitMgr",Mockito.mock(ResourceLimitService.class));
        Mockito.when(volumes.findById(1000L)).thenAnswer(call->volume);
        Mockito.when(volumes.update(Mockito.eq(1000L),Mockito.any())).thenAnswer(call->{persistedPaths.add(((VolumeVO)call.getArgument(1)).getPath());return true;});
        Mockito.when(offerings.findById(9L)).thenReturn(Mockito.mock(DiskOfferingVO.class));
    }
    @After public void cleanup() {CallContext.unregister();}
    private void backing(Volume.Type type,boolean attached) {
        volume=new VolumeVO(type,"before",5L,2L,3L,9L,Storage.ProvisioningType.SPARSE,20L*1024*1024*1024,null,null,null);
        ReflectionTestUtils.setField(volume,"id",1000L);volume.setPath("original-storage-path");volume.setPoolId(11L);volume.setFormat(Storage.ImageFormat.QCOW2);volume.setState(Volume.State.Ready);volume.setDisplayVolume(false);volume.setInstanceId(attached?12L:null);persistedPaths.clear();
    }
    @Test public void visibilityRenameAndProtectionPreserveEveryPersistedPathForRootAttachedDataAndUnattachedClone() {
        try(MockedStatic<UsageEventUtils> ignored=Mockito.mockStatic(UsageEventUtils.class)) {
            for(Volume.Type type:List.of(Volume.Type.ROOT,Volume.Type.DATADISK))for(boolean attached:List.of(false,true)) {
                backing(type,attached);String uuid=volume.getUuid();Long instance=volume.getInstanceId();
                service.updateVolume(1000L,null,null,null,true,null,null,3L,null,null,null);
                service.updateVolume(1000L,null,null,null,null,null,null,3L,null,"renamed",null);
                service.updateVolume(1000L,null,null,null,null,true,null,3L,null,null,null);
                Assert.assertEquals("original-storage-path",volume.getPath());Assert.assertTrue(persistedPaths.size()>=3);for(String path:persistedPaths)Assert.assertEquals("original-storage-path",path);
                Assert.assertEquals(uuid,volume.getUuid());Assert.assertEquals(instance,volume.getInstanceId());Assert.assertEquals(Long.valueOf(11L),volume.getPoolId());Assert.assertEquals(Storage.ImageFormat.QCOW2,volume.getFormat());Assert.assertEquals(Storage.ProvisioningType.SPARSE,volume.getProvisioningType());Assert.assertEquals(Volume.State.Ready,volume.getState());Assert.assertEquals("renamed",volume.getName());Assert.assertTrue(volume.isDisplayVolume());Assert.assertTrue(volume.isDeleteProtection());
            }
        }
    }
    @Test public void explicitAdministrativePathRepairStillPersistsTheRequestedPath() {
        backing(Volume.Type.DATADISK,false);service.updateVolume(1000L,"explicit-recovered-path",null,null,null,null,null,3L,null,null,null);
        Assert.assertEquals("explicit-recovered-path",volume.getPath());Assert.assertEquals(List.of("explicit-recovered-path"),persistedPaths);
    }
}
