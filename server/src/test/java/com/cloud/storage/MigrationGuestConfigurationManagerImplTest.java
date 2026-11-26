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

import static org.junit.Assert.assertTrue;
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
import com.cloud.agent.api.MigrationGuestConfigurationAnswer;
import com.cloud.agent.api.MigrationGuestConfigurationCommand;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

public class MigrationGuestConfigurationManagerImplTest {
    @Mock private AgentManager agentManager;
    @Mock private HostDao hostDao;
    @Mock private PrimaryDataStoreDao poolDao;
    @Mock private StoragePoolHostDao poolHostDao;
    @Mock private VMInstanceDao vmDao;
    @Mock private VMInstanceDetailsDao detailsDao;
    @Mock private NicDao nicDao;
    @Mock private MigrationDiskControllerService controllerService;
    @InjectMocks private MigrationGuestConfigurationManagerImpl service;
    private Map<String, String> details;
    private JsonObject guest;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        details = new HashMap<>();
        when(detailsDao.listDetailsKeyPairs(1L)).thenReturn(details);
        var vm = mock(VMInstanceVO.class);
        when(vm.getInstanceName()).thenReturn("i-2-1-VM");
        when(vm.getUuid()).thenReturn("00000000-0000-0000-0000-000000000003");
        when(vmDao.findById(1L)).thenReturn(vm);
        when(nicDao.listByVmId(1L)).thenReturn(List.of());
    }

    private void planned() {
        var controller =
                JsonParser.parseString(
                                "{\"version\":1,\"policy\":\"auto\",\"machine\":\"q35\",\"firmware\":\"bios\",\"root_selection\":\"source_disk_label\",\"source_vm_uuid\":\"00000000-0000-0000-0000-000000000002\",\"disks\":[{\"index\":0,\"disk_id\":\"ide0:0\",\"device_key\":\"3000\",\"kind\":\"ide\",\"bus\":0,\"unit\":0,\"role\":\"root\",\"size_bytes\":1073741824,\"target\":{\"bus\":\"sata\",\"model\":\"ahci\",\"device\":\"sda\"}}]}")
                        .getAsJsonObject();
        controller.addProperty("hash", MigrationDiskControllerPolicy.hash(controller));
        details.putAll(
                MigrationDiskControllerPolicy.chunks(
                        MigrationDiskControllerPolicy.PLAN, controller.toString()));
        guest =
                JsonParser.parseString(
                                "{\"version\":1,\"policy\":\"preserve\",\"os\":\"windows\",\"helper_version\":1,\"operation_id\":\"00000000-0000-0000-0000-000000000001\",\"source_vm_uuid\":\"00000000-0000-0000-0000-000000000002\",\"root_source_disk_id\":\"ide0:0\",\"root_identity\":\"gpt:root\",\"source_capture_id\":\"capture\",\"windows\":{\"san_policy\":\"OfflineShared\",\"disks\":[]}}")
                        .getAsJsonObject();
        guest.addProperty("helper_sha256", "b".repeat(64));
        guest.addProperty("controller_plan_hash", controller.get("hash").getAsString());
        saveGuest();
    }

    private void saveGuest() {
        guest.remove("hash");
        guest.addProperty("hash", MigrationDiskControllerPolicy.hash(guest));
        details.putAll(
                MigrationDiskControllerPolicy.chunks(
                        MigrationGuestConfigurationPolicy.PLAN, guest.toString()));
    }

    private void bound() {
        planned();
        details.putAll(
                MigrationDiskControllerPolicy.chunks(
                        MigrationDiskControllerPolicy.BINDINGS,
                        "[{\"id\":\"root\",\"index\":0,\"storage\":\"file\",\"pool\":\"pool\",\"path\":\"/mnt/pool/root\",\"format\":\"qcow2\"}]"));
        var pool = mock(StoragePoolVO.class);
        when(pool.getId()).thenReturn(9L);
        when(poolDao.findByUuid("pool")).thenReturn(pool);
        var link = mock(StoragePoolHostVO.class);
        when(link.getHostId()).thenReturn(20L);
        when(poolHostDao.listByPoolId(9L)).thenReturn(List.of(link));
        var host = mock(HostVO.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hostDao.findById(20L)).thenReturn(host);
    }

    private void reject() {
        try {
            service.beforeStart(1L);
            fail("Expected unconfirmed contract rejection");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().startsWith("GUEST_"));
        }
        verify(detailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    public void rootIdentityCannotBeReassignedToAnotherSourceDisk() {
        planned();
        guest.addProperty("root_source_disk_id", "ide0:1");
        saveGuest();
        reject();
        verifyNoInteractions(agentManager);
    }

    @Test
    public void legacyVmDoesNotRequireNewWorker() {
        service.beforeStart(1L);
        verifyNoInteractions(agentManager, nicDao, poolDao);
    }

    @Test
    public void missingVolumeBindingNeverStartsImplicitMutation() {
        planned();
        reject();
        verifyNoInteractions(agentManager);
    }

    @Test
    public void wrongControllerPlanCannotBind() {
        planned();
        guest.addProperty("controller_plan_hash", "a".repeat(64));
        saveGuest();
        reject();
        verifyNoInteractions(agentManager);
    }

    @Test
    public void untypedPositiveAgentReplyCannotQualify() {
        bound();
        when(agentManager.easySend(eq(20L), any(MigrationGuestConfigurationCommand.class)))
                .thenAnswer(call -> new Answer(call.getArgument(1), true, "unsupported"));
        reject();
    }

    @Test
    public void staleWorkerHashCannotQualify() {
        bound();
        when(agentManager.easySend(eq(20L), any(MigrationGuestConfigurationCommand.class)))
                .thenAnswer(
                        call ->
                                new MigrationGuestConfigurationAnswer(
                                        call.getArgument(1),
                                        Map.of(
                                                "supported",
                                                true,
                                                "fresh_observation",
                                                true,
                                                "plan_hash",
                                                "stale")));
        reject();
    }

    @Test
    public void qualifiedLiveWorkerDoesNotInventApplyEvidence() {
        bound();
        when(agentManager.easySend(eq(20L), any(MigrationGuestConfigurationCommand.class)))
                .thenAnswer(
                        call ->
                                new MigrationGuestConfigurationAnswer(
                                        call.getArgument(1),
                                        Map.of(
                                                "supported",
                                                true,
                                                "fresh_observation",
                                                true,
                                                "plan_hash",
                                                guest.get("hash").getAsString(),
                                                "transport",
                                                "qga-file-v1")));
        service.beforeStart(1L);
        verify(detailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }
}
