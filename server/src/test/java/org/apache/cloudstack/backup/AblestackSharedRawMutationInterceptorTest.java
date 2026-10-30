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

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

public class AblestackSharedRawMutationInterceptorTest {
    private final AblestackSharedRawMutationInterceptor interceptor = new AblestackSharedRawMutationInterceptor();
    private final BeanFactory factory = mock(BeanFactory.class);
    private final AblestackSharedRawBackupService guards = mock(AblestackSharedRawBackupService.class);
    @Before public void setup() {
        interceptor.setBeanFactory(factory);
        when(factory.getBean(AblestackSharedRawBackupService.class)).thenReturn(guards);
    }
    private Object proxy(Object target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvisor(interceptor);
        return factory.getProxy();
    }
    @Test public void attachmentIsGuardedBeforeAnyApiSideEffectAndLeaseClosesAfterward() {
        VolumeApiService original = mock(VolumeApiService.class);
        VolumeApiService proxy = (VolumeApiService) proxy(original);
        AtomicBoolean held = new AtomicBoolean();
        when(guards.guardVolume(20L, 10L, null, null)).thenAnswer(call -> {
            held.set(true); return (AblestackSharedRawBackupService.Lease) () -> held.set(false);
        });
        when(original.attachVolumeToVM(20L, 10L, 1L, false, false)).thenAnswer(call -> {
            assertTrue(held.get()); return null;
        });
        proxy.attachVolumeToVM(20L, 10L, 1L, false, false);
        assertFalse(held.get());
    }
    @Test public void protectedDestinationRejectsAttachmentBeforeTheNativeApiIsCalled() {
        VolumeApiService original = mock(VolumeApiService.class);
        VolumeApiService proxy = (VolumeApiService) proxy(original);
        when(guards.guardVolume(20L, 10L, null, null)).thenThrow(new CloudRuntimeException("protected"));
        assertThrows(CloudRuntimeException.class, () -> proxy.attachVolumeToVM(20L, 10L, 1L, false, false));
        verify(original, never()).attachVolumeToVM(anyLong(), anyLong(), anyLong(), anyBoolean(), anyBoolean());
    }
    @Test public void unchangedCloudStackCommandsKeepTheirOriginalWireMessageAndAnswer() throws Exception {
        AgentManager original = mock(AgentManager.class);
        AgentManager proxy = (AgentManager) proxy(original);
        Command command = mock(Command.class); Answer answer = new Answer(command, true, "ok");
        when(original.send(1L, command)).thenReturn(answer);
        assertSame(answer, proxy.send(1L, command)); verifyNoInteractions(guards);
    }
    @Test public void sharedFileStartUsesEnvelopeAndKeepsOriginalStartAnswer() throws Exception {
        AgentManager original = mock(AgentManager.class);
        AgentManager proxy = (AgentManager) proxy(original);
        StartCommand start = mock(StartCommand.class); VirtualMachineTO vm = mock(VirtualMachineTO.class);
        DiskTO disk = mock(DiskTO.class); VolumeObjectTO volume = mock(VolumeObjectTO.class);
        PrimaryDataStoreTO pool = mock(PrimaryDataStoreTO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(volume.getDataStore()).thenReturn(pool); when(disk.getData()).thenReturn(volume);
        when(vm.getDisks()).thenReturn(new DiskTO[]{disk}); when(vm.getName()).thenReturn("vm"); when(start.getVirtualMachine()).thenReturn(vm);
        VMInstanceDao vms = mock(VMInstanceDao.class); VMInstanceVO record = mock(VMInstanceVO.class);
        when(factory.getBean(VMInstanceDao.class)).thenReturn(vms); when(vms.findVMByInstanceName("vm")).thenReturn(record);
        when(record.getId()).thenReturn(20L); when(guards.guardStart(20L)).thenReturn(() -> {});
        StartAnswer answer = mock(StartAnswer.class);
        when(original.send(eq(1L), any(AblestackSharedRawDiskCommand.class))).thenAnswer(call -> {
            assertSame(start, ((AblestackSharedRawDiskCommand) call.getArgument(1)).getStart()); return answer;
        });
        assertSame(answer, proxy.send(1L, start)); verify(guards).guardStart(20L);
    }
    @Test public void persistenceAttachCannotBypassTheGuard() {
        VolumeDao original = mock(VolumeDao.class);
        VolumeDao proxy = (VolumeDao) proxy(original);
        when(guards.guardVolume(20L, 10L, null, null)).thenThrow(new CloudRuntimeException("protected"));
        assertThrows(CloudRuntimeException.class, () -> proxy.attachVolume(10L, 20L, 1L));
        verify(original, never()).attachVolume(anyLong(), anyLong(), anyLong());
    }
}
