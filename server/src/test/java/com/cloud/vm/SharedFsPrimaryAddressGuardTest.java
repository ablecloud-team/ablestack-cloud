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
package com.cloud.vm;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.NicDao;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import com.cloud.utils.exception.CloudRuntimeException;
public class SharedFsPrimaryAddressGuardTest {
    private UserVmManagerImpl manager;private UserVmDao vms;private NicDao nics;private SharedFSDao shared;private UserVmVO vm;private NicVO nic;private SharedFSVO fs;
    @Before public void setup(){
        manager=new UserVmManagerImpl();vms=Mockito.mock(UserVmDao.class);nics=Mockito.mock(NicDao.class);shared=Mockito.mock(SharedFSDao.class);vm=Mockito.mock(UserVmVO.class);nic=Mockito.mock(NicVO.class);fs=Mockito.mock(SharedFSVO.class);
        ReflectionTestUtils.setField(manager,"_vmDao",vms);ReflectionTestUtils.setField(manager,"_nicDao",nics);ReflectionTestUtils.setField(manager,"staticSharedFsDao",shared);
        Mockito.when(vms.findById(50L)).thenReturn(vm);Mockito.when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);Mockito.when(shared.findByVm(50L)).thenReturn(fs);
        Mockito.when(fs.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);Mockito.when(fs.getIpAddress()).thenReturn("10.10.13.240");Mockito.when(nic.getInstanceId()).thenReturn(50L);Mockito.when(nic.getId()).thenReturn(79L);Mockito.when(nic.isDefaultNic()).thenReturn(true);Mockito.when(nics.update(Mockito.eq(79L),Mockito.any())).thenReturn(true);
    }
    @Test public void declaredPrimaryWinsOverSecondaryGuestObservationAndRepairsPriorPollution(){
        Mockito.when(nic.getIPv4Address()).thenReturn("10.10.13.241");Assert.assertTrue(manager.preserveDeclaredSharedFsPrimary(50,nic));Mockito.verify(nic).setIPv4Address("10.10.13.240");Mockito.verify(nics).update(79L,nic);
    }
    @Test public void correctStaticPrimaryIsRetainedWithoutAWriteEvenWhenGuestHasNoReportedIp(){
        Mockito.when(nic.getIPv4Address()).thenReturn("10.10.13.240");Assert.assertTrue(manager.preserveDeclaredSharedFsPrimary(50,nic));Mockito.verify(nic,Mockito.never()).setIPv4Address(Mockito.any());Mockito.verifyNoInteractions(nics);
    }
    @Test public void dhcpAndOrdinaryVmsKeepTheirExistingDiscoveryBehavior(){
        Mockito.when(fs.getNetworkMode()).thenReturn(SharedFS.NetworkMode.DHCP);Assert.assertFalse(manager.preserveDeclaredSharedFsPrimary(50,nic));
        Mockito.when(vm.getUserVmType()).thenReturn("USER");Assert.assertFalse(manager.preserveDeclaredSharedFsPrimary(50,nic));Mockito.verifyNoInteractions(nics);
    }
    @Test public void missingOrCrossVmDeclaredPrimaryCannotAuthorizeNicMutation(){
        Mockito.when(fs.getIpAddress()).thenReturn(null);Assert.assertThrows(CloudRuntimeException.class,()->manager.preserveDeclaredSharedFsPrimary(50,nic));
        Mockito.when(fs.getIpAddress()).thenReturn("10.10.13.240");Mockito.when(nic.getInstanceId()).thenReturn(51L);Assert.assertThrows(CloudRuntimeException.class,()->manager.preserveDeclaredSharedFsPrimary(50,nic));Mockito.verifyNoInteractions(nics);
    }
    @Test public void secondaryNicOnAnotherNetworkCannotBeRewrittenToTheDeclaredPrimary(){
        Mockito.when(nic.isDefaultNic()).thenReturn(false);Mockito.when(nic.getIPv4Address()).thenReturn("172.16.1.5");
        Assert.assertFalse(manager.preserveDeclaredSharedFsPrimary(50,nic));Mockito.verify(nic,Mockito.never()).setIPv4Address(Mockito.any());Mockito.verifyNoInteractions(nics,shared);
    }
}
