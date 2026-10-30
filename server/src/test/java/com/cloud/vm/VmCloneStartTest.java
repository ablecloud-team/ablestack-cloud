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

import java.util.HashMap;
import java.util.Map;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class VmCloneStartTest {
    private UserVmManagerImpl manager;
    private UserVmVO source, target;
    private Map<VirtualMachineProfile.Param,Object> params;
    @Before public void setup() {
        manager=spy(new UserVmManagerImpl());source=mock(UserVmVO.class);target=mock(UserVmVO.class);
        when(target.getId()).thenReturn(40L);when(source.getPodIdToDeployIn()).thenReturn(1L);
        params=new HashMap<>();doReturn(4L).when(manager).getFastCloneHostId(source);doReturn(params).when(manager).getCloneVmAdditionalParams(source);
    }
    @Test public void completedCloneUsesOrdinaryStartAndReturnsSameVm() throws Exception {
        doReturn(new Pair<UserVmVO,Map<VirtualMachineProfile.Param,Object>>(target,params)).when(manager).startVirtualMachine(40L,1L,null,4L,params,null,true,false);
        assertSame(target,manager.startCreatedClone(source,target));
        verify(manager).startVirtualMachine(40L,1L,null,4L,params,null,true,false);
    }
    @Test public void startFailureDoesNotEnterInitialDeployRollback() throws Exception {
        CloudRuntimeException failure=new CloudRuntimeException("injected start failure");
        doThrow(failure).when(manager).startVirtualMachine(40L,1L,null,4L,params,null,true,false);
        assertSame(failure,assertThrows(CloudRuntimeException.class,()->manager.startCreatedClone(source,target)));
        // The deployment wrapper accesses VM/volume DAOs in its finally block;
        // these deliberately remain unset so a rollback regression fails this test.
        verify(manager).startVirtualMachine(40L,1L,null,4L,params,null,true,false);
    }
}
