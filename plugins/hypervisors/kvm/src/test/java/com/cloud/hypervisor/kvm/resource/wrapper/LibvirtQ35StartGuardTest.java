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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.Host;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtQ35PcieTopology;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LibvirtQ35StartGuardTest {
    @Test public void unknownTopologyStopsBeforeDiskConnectionOrDomainCreation() throws Exception {
        VirtualMachineTO spec = mock(VirtualMachineTO.class);
        when(spec.getName()).thenReturn("q35-guard-test");
        StartCommand command = new StartCommand(spec, mock(Host.class), false);
        LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
        LibvirtVMDef vm = mock(LibvirtVMDef.class);
        KVMStoragePoolManager storage = mock(KVMStoragePoolManager.class);
        when(resource.getStoragePoolMgr()).thenReturn(storage);
        when(resource.getLibvirtUtilitiesHelper()).thenReturn(mock(LibvirtUtilitiesHelper.class));
        when(resource.createVMFromSpec(spec)).thenReturn(vm);
        try (MockedStatic<LibvirtQ35PcieTopology> policy = Mockito.mockStatic(LibvirtQ35PcieTopology.class);
                MockedStatic<LibvirtAblestackRestoreTransaction> transactions = Mockito.mockStatic(LibvirtAblestackRestoreTransaction.class)) {
            policy.when(() -> LibvirtQ35PcieTopology.prepare(spec, vm, null))
                    .thenThrow(new CloudRuntimeException(LibvirtQ35PcieTopology.UNKNOWN));
            Answer answer = new LibvirtStartCommandWrapper().execute(command, resource);
            assertFalse(answer.getResult());
            assertTrue(answer.getDetails().contains(LibvirtQ35PcieTopology.UNKNOWN));
            assertEquals("true", answer.getContextParam("stopRetry"));
            verify(resource, never()).createVbd(any(), eq(spec), any(), any());
            verify(storage, never()).connectPhysicalDisksViaVmSpec(eq(spec), eq(false));
            verify(resource, never()).startVM(any(), any(), any());
        }
    }
}
