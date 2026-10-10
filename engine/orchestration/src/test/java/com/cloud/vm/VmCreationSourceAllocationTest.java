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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.deploy.DeploymentPlan;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOfferingInfo;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.dao.VMInstanceDao;

public class VmCreationSourceAllocationTest {
    private final VirtualMachineManagerImpl manager = new VirtualMachineManagerImpl();
    private final VolumeOrchestrationService volumes = mock(VolumeOrchestrationService.class);
    private final VolumeDao volumeDao = mock(VolumeDao.class);
    private final VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
    private final VMInstanceVO vm = mock(VMInstanceVO.class);
    private final Account owner = mock(Account.class);
    private final DeploymentPlan plan = mock(DeploymentPlan.class);
    private final DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
    private final DiskOfferingInfo diskInfo = new DiskOfferingInfo(diskOffering);
    private Object previousContextEntities;

    @Before
    public void setup() {
        VMInstanceDao vmDao = mock(VMInstanceDao.class);
        EntityManager entities = mock(EntityManager.class);
        ReflectionTestUtils.setField(manager, "_vmDao", vmDao);
        ReflectionTestUtils.setField(manager, "_entityMgr", entities);
        ReflectionTestUtils.setField(manager, "_volsDao", volumeDao);
        ReflectionTestUtils.setField(manager, "volumeMgr", volumes);
        ReflectionTestUtils.setField(manager, "_networkMgr", mock(NetworkOrchestrationService.class));
        when(vmDao.findVMByInstanceName("source-vm")).thenReturn(vm);
        when(vmDao.persist(vm)).thenReturn(vm);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getId()).thenReturn(34L);
        when(vm.getAccountId()).thenReturn(2L);
        when(owner.getId()).thenReturn(2L);
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(entities.findById(Account.class, 2L)).thenReturn(owner);
        when(entities.findById(User.class, 1L)).thenReturn(user);
        previousContextEntities = ReflectionTestUtils.getField(CallContext.class, "s_entityMgr");
        ReflectionTestUtils.setField(CallContext.class, "s_entityMgr", entities);
        when(plan.getClusterId()).thenReturn(null);
        when(plan.getPoolId()).thenReturn(null);
        when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        CallContext.register(user, owner);
    }

    @After
    public void cleanup() {
        CallContext.unregister();
        ReflectionTestUtils.setField(CallContext.class, "s_entityMgr", previousContextEntities);
    }

    private void allocate(Map<String, String> parameters, Volume volume, Snapshot snapshot) throws Exception {
        manager.allocate("source-vm", template, null, diskInfo, Collections.emptyList(), Collections.emptyList(),
                new LinkedHashMap<>(), plan, HypervisorType.KVM, null, null, parameters, volume, snapshot);
    }

    @Test
    public void volumeSourceWithCustomVolumeIdUsesRootAdoption() throws Exception {
        Volume volume = mock(Volume.class);
        allocate(Map.of("volumeId", "36", "vm.creation.source", "true"), volume, null);
        verify(volumes).allocateTemplatedVolumes(eq(Volume.Type.ROOT), eq("ROOT-34"), eq(diskOffering),
                any(), any(), any(), eq(template), eq(vm), eq(owner), any(), eq(volume), eq(null));
        verify(volumeDao, never()).findById(36L);
        verify(volumeDao, never()).update(any(Long.class), any(VolumeVO.class));
    }

    @Test
    public void snapshotSourceUsesRootRestore() throws Exception {
        Snapshot snapshot = mock(Snapshot.class);
        allocate(Collections.emptyMap(), null, snapshot);
        verify(volumes).allocateTemplatedVolumes(eq(Volume.Type.ROOT), eq("ROOT-34"), eq(diskOffering),
                any(), any(), any(), eq(template), eq(vm), eq(owner), any(), eq(null), eq(snapshot));
    }

    @Test
    public void legacyCustomVolumeIdKeepsExistingImportPath() throws Exception {
        VolumeVO legacy = mock(VolumeVO.class);
        when(volumeDao.findById(36L)).thenReturn(legacy);
        when(legacy.getId()).thenReturn(36L);
        allocate(Collections.singletonMap("volumeId", "36"), null, null);
        verify(legacy).setInstanceId(34L);
        verify(volumeDao).update(36L, legacy);
        verify(volumes, never()).allocateTemplatedVolumes(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void templateCreationStillAllocatesRootVolume() throws Exception {
        allocate(Collections.emptyMap(), null, null);
        verify(volumes).allocateTemplatedVolumes(eq(Volume.Type.ROOT), eq("ROOT-34"), eq(diskOffering),
                any(), any(), any(), eq(template), eq(vm), eq(owner), any(), eq(null), eq(null));
    }
}
