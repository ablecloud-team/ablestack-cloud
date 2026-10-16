// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations
// under the License.
package com.cloud.network.rules;

import com.cloud.network.Network;
import com.cloud.network.router.VirtualRouter;
import com.cloud.uservm.UserVm;
import com.cloud.vm.NicProfile;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.NicDao;
import org.apache.cloudstack.network.topology.NetworkTopologyVisitor;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UserdataToRouterRulesTest {
    @Test public void deliversCandidateRatherThanOldDatabaseBodyWhileKeepingLoadedDetails() throws Exception {
        NetworkTopologyVisitor visitor = mock(NetworkTopologyVisitor.class);
        VirtualNetworkApplianceFactory factory = mock(VirtualNetworkApplianceFactory.class);
        UserVmDao dao = mock(UserVmDao.class);
        UserVm candidate = mock(UserVm.class);
        UserVmVO stored = mock(UserVmVO.class);
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        NicProfile nic = mock(NicProfile.class);
        when(visitor.getVirtualNetworkApplianceFactory()).thenReturn(factory);
        when(factory.getUserVmDao()).thenReturn(dao);
        when(factory.getNicDao()).thenReturn(mock(NicDao.class));
        when(profile.getVirtualMachine()).thenReturn(candidate);
        when(profile.getParameter(UserdataToRouterRules.RESET_CANDIDATE)).thenReturn(Boolean.TRUE);
        when(candidate.getId()).thenReturn(7L);
        when(candidate.getUserData()).thenReturn("candidate-body");
        when(candidate.getUserDataId()).thenReturn(22L);
        when(candidate.getUserDataDetails()).thenReturn("{TAG=new}");
        when(dao.findById(7L)).thenReturn(stored);
        UserdataToRouterRules rules = new UserdataToRouterRules(mock(Network.class), nic, profile);
        when(visitor.visit(rules)).thenReturn(true);
        assertTrue(rules.accept(visitor, mock(VirtualRouter.class)));
        verify(dao).loadDetails(stored);
        verify(stored).setUserData("candidate-body");
        verify(stored).setUserDataId(22L);
        verify(stored).setUserDataDetails("{TAG=new}");
    }

    @Test public void legacyUpdateUsesFreshDatabaseBodyRatherThanAnOldProfile() throws Exception {
        NetworkTopologyVisitor visitor = mock(NetworkTopologyVisitor.class);
        VirtualNetworkApplianceFactory factory = mock(VirtualNetworkApplianceFactory.class);
        UserVmDao dao = mock(UserVmDao.class);
        UserVm candidate = mock(UserVm.class);
        UserVmVO stored = mock(UserVmVO.class);
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        when(visitor.getVirtualNetworkApplianceFactory()).thenReturn(factory);
        when(factory.getUserVmDao()).thenReturn(dao);
        when(factory.getNicDao()).thenReturn(mock(NicDao.class));
        when(profile.getVirtualMachine()).thenReturn(candidate);
        when(candidate.getId()).thenReturn(7L);
        when(dao.findById(7L)).thenReturn(stored);
        UserdataToRouterRules rules = new UserdataToRouterRules(mock(Network.class), mock(NicProfile.class), profile);
        when(visitor.visit(rules)).thenReturn(true);
        assertTrue(rules.accept(visitor, mock(VirtualRouter.class)));
        verify(dao).loadDetails(stored);
        verify(stored, never()).setUserData(org.mockito.ArgumentMatchers.any());
        verify(stored, never()).setUserDataId(org.mockito.ArgumentMatchers.any());
        verify(stored, never()).setUserDataDetails(org.mockito.ArgumentMatchers.any());
    }
}
