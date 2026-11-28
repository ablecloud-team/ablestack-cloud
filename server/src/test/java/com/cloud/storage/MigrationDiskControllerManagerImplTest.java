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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrationDiskControllerAnswer;
import com.cloud.agent.api.MigrationDiskControllerCommand;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MigrationDiskControllerManagerImplTest {
    private static final String PLAN =
            "{\"version\":1,\"policy\":\"auto\",\"machine\":\"q35\",\"firmware\":\"bios\",\"root_selection\":\"source_disk_label\",\"source_vm_uuid\":\"source-uuid\",\"disks\":[{\"index\":0,\"disk_id\":\"ide0:0\",\"device_key\":\"2000\",\"kind\":\"ide\",\"bus\":0,\"unit\":0,\"size_bytes\":1073741824,\"role\":\"root\",\"target\":{\"bus\":\"sata\",\"model\":\"ahci\",\"device\":\"sda\"}},{\"index\":1,\"disk_id\":\"scsi0:0\",\"device_key\":\"2001\",\"kind\":\"scsi\",\"bus\":0,\"unit\":0,\"size_bytes\":1073741824,\"role\":\"data\",\"target\":{\"bus\":\"scsi\",\"model\":\"virtio-scsi\",\"device\":\"sdb\"}}],\"hash\":\"143c43746c7017fd9c613629c42061fb6b3b06dbcab44d65a8ae5b9ca2e26e1f\"}";
    @Mock private AgentManager agentManager;
    @Mock private HostDao hostDao;
    @Mock private PrimaryDataStoreDao poolDao;
    @Mock private StoragePoolHostDao poolHostDao;
    @Mock private VolumeDao volumeDao;
    @Mock private VMInstanceDao vmDao;
    @Mock private VMInstanceDetailsDao detailsDao;
    @InjectMocks private MigrationDiskControllerManagerImpl service;
    private Map<String, String> details;
    private VolumeVO root, data;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        details =
                new HashMap<>(
                        MigrationDiskControllerPolicy.chunks(
                                MigrationDiskControllerPolicy.PLAN, PLAN));
        when(detailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        var vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getAccountId()).thenReturn(2L);
        when(vm.getInstanceName()).thenReturn("i-2-1-VM");
        when(vmDao.findById(1L)).thenReturn(vm);
        root = volume(10L, 0L, Volume.Type.ROOT, "root");
        data = volume(11L, 1L, Volume.Type.DATADISK, "data");
        when(volumeDao.findByInstance(1L)).thenReturn(List.of(root, data));
        var pool = mock(StoragePoolVO.class);
        when(pool.getUuid()).thenReturn("pool");
        when(pool.getPath()).thenReturn("/mnt/pool");
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(poolDao.findById(9L)).thenReturn(pool);
        var link = mock(StoragePoolHostVO.class);
        when(link.getHostId()).thenReturn(20L);
        when(poolHostDao.listByPoolId(9L)).thenReturn(List.of(link));
        when(poolHostDao.findByPoolHost(9L, 20L)).thenReturn(link);
        var host = mock(HostVO.class);
        when(host.getId()).thenReturn(20L);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hostDao.findById(20L)).thenReturn(host);
        when(agentManager.easySend(eq(20L), any(MigrationDiskControllerCommand.class)))
                .thenAnswer(
                        call -> {
                            var cmd = (MigrationDiskControllerCommand) call.getArgument(1);
                            return new MigrationDiskControllerAnswer(
                                    cmd,
                                    new HashMap<>(
                                            Map.of(
                                                    "supported",
                                                    true,
                                                    "machine",
                                                    "pc-q35-9.0",
                                                    "firmware",
                                                    "bios",
                                                    "planhash",
                                                    MigrationDiskControllerPolicy.parse(PLAN)
                                                            .get("hash")
                                                            .getAsString(),
                                                    "buses",
                                                    List.of("sata", "scsi"))));
                        });
    }

    private VolumeVO volume(long id, long index, Volume.Type type, String name) {
        var v = mock(VolumeVO.class);
        when(v.getId()).thenReturn(id);
        when(v.getDeviceId()).thenReturn(index);
        when(v.getVolumeType()).thenReturn(type);
        when(v.getUuid()).thenReturn(name);
        when(v.getPath()).thenReturn(name);
        when(v.getState()).thenReturn(Volume.State.Ready);
        when(v.getAccountId()).thenReturn(2L);
        when(v.getPoolId()).thenReturn(9L);
        when(v.getSize()).thenReturn(1073741824L);
        when(v.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        return v;
    }

    private void bind() {
        String b =
                "[{\"id\":\"root\",\"index\":0,\"storage\":\"file\",\"pool\":\"pool\",\"path\":\"/mnt/pool/root\",\"format\":\"qcow2\"},{\"id\":\"data\",\"index\":1,\"storage\":\"file\",\"pool\":\"pool\",\"path\":\"/mnt/pool/data\",\"format\":\"qcow2\"}]";
        details.putAll(
                MigrationDiskControllerPolicy.chunks(
                        MigrationDiskControllerPolicy.BINDINGS,
                        MigrationDiskControllerPolicy.canonical(
                                MigrationDiskControllerPolicy.bindingArray(b))));
    }

    @Test
    public void firstStartRequiresBindingAndNeverCreatesItImplicitly() {
        reject(() -> service.beforeStart(1L));
        verify(detailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    public void currentVolumesMustMatchFrozenBindings() {
        bind();
        service.beforeStart(1L);
        when(data.getPath()).thenReturn("replaced");
        reject(() -> service.beforeStart(1L));
    }

    @Test
    public void rootRoleAndGuestSlotsCannotChange() {
        bind();
        when(root.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        reject(() -> service.beforeStart(1L));
    }

    @Test
    public void anOldAgentCannotSatisfyTheAdmissionContract() {
        bind();
        when(agentManager.easySend(eq(20L), any(MigrationDiskControllerCommand.class)))
                .thenReturn(new Answer(null, true, null));
        reject(() -> service.beforeStart(1L));
    }

    @Test
    public void orphanBindingsCannotBypassStartAdmission() {
        bind();
        details.remove(MigrationDiskControllerPolicy.PLAN);
        reject(() -> service.beforeStart(1L));
        verifyNoInteractions(agentManager);
    }

    @Test
    public void legacyVmDoesNotAcquireAControllerContract() {
        details.clear();
        service.beforeStart(1L);
        verifyNoInteractions(agentManager);
    }

    private void reject(Runnable r) {
        try {
            r.run();
            fail("Expected admission rejection");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
