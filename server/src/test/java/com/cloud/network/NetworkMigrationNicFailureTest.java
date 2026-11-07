// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.network;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import com.cloud.dc.DataCenter;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicDetailsDao;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.UserVmDao;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class NetworkMigrationNicFailureTest {
    @InjectMocks private NetworkMigrationManagerImpl manager;
    @Mock private UserVmDao _vmDao;
    @Mock private NicDao _nicDao;
    @Mock private HostDao _hostDao;
    @Mock private NetworkModel _networkModel;
    @Mock private VirtualMachineManager _itMgr;
    @Mock private NetworkOrchestrationService _networkMgr;
    @Mock private NicDetailsDao _nicDetailsDao;
    @Mock private NicSecondaryIpDao _nicSecondaryIpDao;
    @Mock private NicIpAliasDao _nicIpAliasDao;

    @Test
    public void negativeReplugDoesNotRemoveOldNicOrTransferItsIdentity() throws Exception {
        try (AutoCloseable mocks = MockitoAnnotations.openMocks(this)) {
            NicVO oldNic = mock(NicVO.class);
            NicVO newNic = mock(NicVO.class);
            Network network = mock(Network.class);
            UserVmVO vm = mock(UserVmVO.class);
            HostVO host = mock(HostVO.class);
            NicProfile allocated = mock(NicProfile.class);
            when(oldNic.getState()).thenReturn(Nic.State.Reserved);
            when(oldNic.getInstanceId()).thenReturn(1L);
            when(oldNic.getDeviceId()).thenReturn(0);
            when(oldNic.getId()).thenReturn(10L);
            when(newNic.getId()).thenReturn(20L);
            when(allocated.getId()).thenReturn(20L);
            when(vm.getHostId()).thenReturn(30L);
            when(_vmDao.findById(1L)).thenReturn(vm);
            when(_hostDao.findById(30L)).thenReturn(host);
            when(_networkMgr.allocateNic(any(), any(), anyBoolean(), anyInt(), any())).thenReturn(new Pair<>(allocated, null));
            when(_nicDao.findById(20L)).thenReturn(newNic);
            when(_networkMgr.prepareNic(any(), any(), any(), anyLong(), any())).thenReturn(allocated);
            when(_itMgr.replugNic(any(), any(), any(), any())).thenReturn(false);
            Method method = NetworkMigrationManagerImpl.class.getDeclaredMethod("migrateNicsInDB", NicVO.class, Network.class, DataCenter.class, ReservationContext.class);
            method.setAccessible(true);
            try {
                method.invoke(manager, oldNic, network, mock(DataCenter.class), mock(ReservationContext.class));
                fail("A negative hypervisor answer must fail migration");
            } catch (InvocationTargetException e) {
                assertTrue(e.getCause() instanceof CloudRuntimeException);
                assertTrue(e.getCause().getMessage().contains("retaining the migration checkpoint"));
            }
            verify(_networkMgr, never()).removeNic(any(), any());
            verify(oldNic, never()).setDefaultNic(false);
            verify(oldNic, never()).setUuid(any());
            verifyNoInteractions(_nicDetailsDao, _nicSecondaryIpDao, _nicIpAliasDao);
        }
    }
}
