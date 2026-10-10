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

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.vm.dao.UserVmDao;

public class VmCreationSourceStartFailureTest {
    @Test
    public void failedSourceStartKeepsSameStoppedVmAndDisks() {
        UserVmManagerImpl manager = new UserVmManagerImpl();
        UserVmDao vms = Mockito.mock(UserVmDao.class);
        VolumeOrchestrationService volumes = Mockito.mock(VolumeOrchestrationService.class);
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vms.findById(60L)).thenReturn(vm);
        Mockito.when(vm.getDetail("vm.creation.source")).thenReturn("true");
        ReflectionTestUtils.setField(manager, "_vmDao", vms);
        ReflectionTestUtils.setField(manager, "volumeMgr", volumes);
        ReflectionTestUtils.invokeMethod(manager, "updateVmStateForFailedVmCreation", 60L, 3L);
        Mockito.verify(vms).loadDetails(vm);
        Mockito.verifyNoInteractions(volumes);
        Mockito.verify(vm, Mockito.never()).setState(Mockito.any());
    }
}
