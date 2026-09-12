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

import org.junit.Test;
import org.mockito.Mockito;

public class EuropaVmStartCompatibilityTest {
    @Test
    public void legacyFtctlStartDoesNotEnableQuickRestore() throws Exception {
        UserVmManager manager = Mockito.mock(UserVmManager.class, Mockito.CALLS_REAL_METHODS);
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        manager.startVirtualMachine(1L, 2L, params, null);
        Mockito.verify(manager).startVirtualMachine(1L, 2L, params, null, false);
    }

    @Test
    public void legacyPlacementStartKeepsExplicitHostAndNormalRestore() throws Exception {
        UserVmManager manager = Mockito.mock(UserVmManager.class, Mockito.CALLS_REAL_METHODS);
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        manager.startVirtualMachine(1L, 2L, 3L, 4L, params, null);
        Mockito.verify(manager).startVirtualMachine(1L, 2L, 3L, 4L, params, null, true, false);
    }

    @Test
    public void legacyExplicitHostBooleanKeepsItsMeaning() throws Exception {
        UserVmManagerImpl manager = Mockito.spy(new UserVmManagerImpl());
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        Mockito.doReturn(null).when(manager).startVirtualMachine(1L, 2L, 3L, 4L, params, null, false, false);
        manager.startVirtualMachine(1L, 2L, 3L, 4L, params, null, false);
        Mockito.verify(manager).startVirtualMachine(1L, 2L, 3L, 4L, params, null, false, false);
    }

    @Test
    public void kbossExplicitQuickRestoreIsForwardedSeparately() throws Exception {
        UserVmManagerImpl manager = Mockito.spy(new UserVmManagerImpl());
        Map<VirtualMachineProfile.Param, Object> params = new HashMap<>();
        Mockito.doReturn(null).when(manager).startVirtualMachine(1L, null, null, 2L, params, null, true, true);
        manager.startVirtualMachine(1L, 2L, params, null, true);
        Mockito.verify(manager).startVirtualMachine(1L, null, null, 2L, params, null, true, true);
    }
}
