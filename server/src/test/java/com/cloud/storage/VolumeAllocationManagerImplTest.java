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

import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.api.command.admin.storage.GetVolumeAllocationCapabilitiesCmd;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVolumeAllocationCapabilitiesCommand;
import com.cloud.agent.api.GetVolumeAllocationCapabilitiesAnswer;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.dao.StoragePoolHostDao;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeAllocationManagerImplTest {
    @InjectMocks private VolumeAllocationManagerImpl manager;
    @Mock private AgentManager agentManager;
    @Mock private HostDao hostDao;
    @Mock private PrimaryDataStoreDao poolDao;
    @Mock private StoragePoolHostDao poolHostDao;
    @Mock private HostVO host;
    @Mock private StoragePoolVO pool;
    @Mock private GetVolumeAllocationCapabilitiesCmd cmd;
    @Before public void setUp() {
        when(cmd.getStorageId()).thenReturn(1L); when(cmd.getHostId()).thenReturn(2L);
        when(cmd.getAllocationFormat()).thenReturn("raw"); when(cmd.getOperation()).thenReturn("create");
        when(poolDao.findById(1L)).thenReturn(pool); when(hostDao.findById(2L)).thenReturn(host);
        when(pool.getId()).thenReturn(1L); when(host.getId()).thenReturn(2L);
        when(pool.getUuid()).thenReturn("pool"); when(host.getUuid()).thenReturn("host");
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        when(poolHostDao.findByPoolHost(1L, 2L)).thenReturn(mock(StoragePoolHostVO.class));
        when(host.getStatus()).thenReturn(Status.Up); when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
    }
    private void reason(String expected) {
        var response = manager.getCapabilities(cmd);
        assertFalse(response.isSupported()); assertEquals(expected, response.getReason());
    }
    @Test public void oldAgentIsNotQueriedAndNeverAssumedSupported() {
        reason("AGENT_CAPABILITY_NOT_ADVERTISED"); verifyNoInteractions(agentManager);
    }
    @Test public void futureAgentProtocolIsNotAssumedCompatible() {
        when(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION)).thenReturn("2");
        reason("AGENT_CAPABILITY_NOT_ADVERTISED"); verifyNoInteractions(agentManager);
    }
    @Test public void unavailableHostIsNotQueried() {
        when(host.getStatus()).thenReturn(Status.Disconnected);
        reason("HOST_NOT_AVAILABLE"); verifyNoInteractions(agentManager);
    }
    @Test public void failedAdvertisementLookupIsNotSupport() {
        doThrow(new IllegalStateException("lookup failed")).when(hostDao).loadDetails(host);
        reason("CAPABILITY_QUERY_FAILED"); verifyNoInteractions(agentManager);
    }
    @Test public void nullAnswerIsNotSupport() throws Exception {
        when(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION)).thenReturn("1");
        reason("CAPABILITY_QUERY_FAILED");
    }
    @Test public void genericSuccessfulAnswerIsNotACapability() throws Exception {
        when(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION)).thenReturn("1");
        when(agentManager.send(eq(2L), any(GetVolumeAllocationCapabilitiesCommand.class))).thenReturn(new Answer(null, true, null));
        reason("CAPABILITY_QUERY_FAILED");
    }
    @Test public void agentTransportFailureIsNotSupport() throws Exception {
        when(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION)).thenReturn("1");
        when(agentManager.send(eq(2L), any(GetVolumeAllocationCapabilitiesCommand.class))).thenThrow(new IllegalStateException("transport failed"));
        reason("CAPABILITY_QUERY_FAILED");
    }
    private void answer(String uuid, String backend, String operation, Map<String, String> policies) throws Exception {
        when(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION)).thenReturn("1");
        when(agentManager.send(eq(2L), any(GetVolumeAllocationCapabilitiesCommand.class))).thenReturn(
                new GetVolumeAllocationCapabilitiesAnswer(new GetVolumeAllocationCapabilitiesCommand(uuid, "raw", operation), backend, policies));
    }
    @Test public void wrongPoolAnswerIsRejected() throws Exception {
        answer("foreign", "RBD", "create", Map.of("off", "rbd-thin")); reason("INVALID_AGENT_CAPABILITY");
    }
    @Test public void wrongOperationAnswerIsRejected() throws Exception {
        answer("pool", "RBD", "copy", Map.of("off", "rbd-thin")); reason("INVALID_AGENT_CAPABILITY");
    }
    @Test public void backendCannotBeInferredFromPoolName() throws Exception {
        answer("pool", "GFS2", "create", Map.of("full", "qemu-preallocation-full")); reason("INVALID_AGENT_CAPABILITY");
    }
    @Test public void rbdLazyThickAdvertisementIsRejected() throws Exception {
        answer("pool", "RBD", "create", Map.of("falloc", "invented-lazy")); reason("INVALID_AGENT_CAPABILITY");
    }
    @Test public void qualifiedRbdDeclarationEnablesOnlyImplementedOperation() throws Exception {
        answer("pool", "RBD", "create", Map.of("off", "rbd-thin", "full", "rbd-thick-create"));
        org.junit.Assert.assertTrue(manager.getCapabilities(cmd).isSupported());
        assertEquals(null, manager.getCapabilities(cmd).getReason());
        assertEquals(2, manager.getCapabilities(cmd).getAgentPolicies().size());
    }
    @Test(expected = InvalidParameterValueException.class) public void disconnectedPoolHostIsInvalid() {
        when(poolHostDao.findByPoolHost(1L, 2L)).thenReturn(null); manager.getCapabilities(cmd);
    }
    @Test(expected = InvalidParameterValueException.class) public void badOperationIsParameterError() {
        when(cmd.getOperation()).thenReturn("bad"); manager.getCapabilities(cmd);
    }
    @Test(expected = InvalidParameterValueException.class) public void rbdFallocRequestIsInvalidEvenWithOldAgent() {
        when(cmd.getAllocationPolicy()).thenReturn("falloc"); manager.getCapabilities(cmd);
    }
    @Test(expected = InvalidParameterValueException.class) public void rbdFullResizeRequestIsInvalid() {
        when(cmd.getAllocationPolicy()).thenReturn("full"); when(cmd.getOperation()).thenReturn("resize"); manager.getCapabilities(cmd);
    }
    @Test(expected = InvalidParameterValueException.class) public void rawMetadataRequestIsInvalid() {
        when(cmd.getAllocationPolicy()).thenReturn("metadata"); manager.getCapabilities(cmd);
    }

    private void qualifiedPool() throws Exception {
        when(pool.getDataCenterId()).thenReturn(10L); when(pool.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(poolDao.listByDataCenterId(10L)).thenReturn(java.util.List.of(pool));
        when(poolDao.searchForStoragePoolTags(1L)).thenReturn(java.util.List.of("rbd"));
        StoragePoolHostVO connection = mock(StoragePoolHostVO.class); when(connection.getHostId()).thenReturn(2L);
        when(poolHostDao.listByPoolId(1L)).thenReturn(java.util.List.of(connection));
        when(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION)).thenReturn("1");
        when(agentManager.send(eq(2L), any(GetVolumeAllocationCapabilitiesCommand.class))).thenAnswer(invocation -> {
            GetVolumeAllocationCapabilitiesCommand command = invocation.getArgument(1);
            return new GetVolumeAllocationCapabilitiesAnswer(command, "RBD", Map.of("off", "rbd-thin", "full",
                    "create".equals(command.getOperation()) ? "rbd-thick-create" : "rbd-thick-convert-n-S0"));
        });
    }
    @Test public void matchingPoolRequiresBothCreateAndCopy() throws Exception {
        qualifiedPool(); manager.validateCreate(VolumeAllocationPolicy.request("full", "raw", null), Storage.ProvisioningType.FAT, 10, 1L, new String[]{"rbd"});
    }
    @Test public void selectedRbdQcow2NeverQueriesRawCapability() throws Exception {
        qualifiedPool();
        try {
            manager.validateCreate(VolumeAllocationPolicy.request("full", "qcow2", null), Storage.ProvisioningType.FAT, 10, 1L, null);
            org.junit.Assert.fail("RBD must reject QCOW2 before allocating a volume");
        } catch (InvalidParameterValueException e) {
            org.junit.Assert.assertTrue(e.getMessage().contains("ALLOCATION_RBD_FORMAT_UNSUPPORTED"));
        }
        verifyNoInteractions(agentManager);
    }
    @Test public void automaticRbdSelectionNeverSubstitutesRawForQcow2() throws Exception {
        qualifiedPool();
        try {
            manager.validateCreate(VolumeAllocationPolicy.request("full", "qcow2", null), Storage.ProvisioningType.FAT, 10, null, null);
            org.junit.Assert.fail("RBD cannot satisfy a QCOW2 request");
        } catch (InvalidParameterValueException e) {
            org.junit.Assert.assertTrue(e.getMessage().contains("ALLOCATION_AGENT_UNAVAILABLE"));
        }
        verifyNoInteractions(agentManager);
    }
    @Test(expected = InvalidParameterValueException.class) public void oldAgentCannotExecuteNewPolicy() throws Exception {
        qualifiedPool(); when(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION)).thenReturn(null);
        manager.validateCreate(VolumeAllocationPolicy.request("off", "raw", null), Storage.ProvisioningType.THIN, 10, 1L, null);
    }
    @Test(expected = InvalidParameterValueException.class) public void anyEligibleOldHostBlocksPool() throws Exception {
        qualifiedPool(); HostVO old = mock(HostVO.class); when(old.getStatus()).thenReturn(Status.Up); when(old.getHypervisorType()).thenReturn(HypervisorType.KVM); when(old.getUuid()).thenReturn("old");
        when(hostDao.findById(3L)).thenReturn(old); StoragePoolHostVO second = mock(StoragePoolHostVO.class); when(second.getHostId()).thenReturn(3L);
        StoragePoolHostVO first = poolHostDao.listByPoolId(1L).get(0);
        when(poolHostDao.listByPoolId(1L)).thenReturn(java.util.List.of(first, second));
        manager.validateCreate(VolumeAllocationPolicy.request("full", "raw", null), Storage.ProvisioningType.FAT, 10, 1L, null);
    }
    @Test(expected = InvalidParameterValueException.class) public void explicitOtherBackendCannotFallback() throws Exception {
        qualifiedPool(); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        manager.validateCreate(VolumeAllocationPolicy.request("full", "raw", null), Storage.ProvisioningType.FAT, 10, 1L, null);
    }
    @Test(expected = InvalidParameterValueException.class) public void tagMismatchCannotFallback() throws Exception {
        qualifiedPool(); manager.validateCreate(VolumeAllocationPolicy.request("full", "raw", null), Storage.ProvisioningType.FAT, 10, null, new String[]{"other"});
    }
    @Test(expected = InvalidParameterValueException.class) public void legacyFatCannotClaimRbdAllocation() throws Exception {
        qualifiedPool(); manager.validateCreate(Map.of(), Storage.ProvisioningType.FAT, 10, 1L, null);
    }
    @Test public void legacyThinRetainsCompatibilityWithoutNewAgentProtocol() throws Exception {
        qualifiedPool(); when(host.getDetail(VolumeAllocationPolicy.AGENT_VERSION)).thenReturn(null);
        manager.validateCreate(Map.of(), Storage.ProvisioningType.THIN, 10, 1L, null); verifyNoInteractions(agentManager);
    }

    @Test public void unsupportedRbdLifecycleHasAnExplicitReasonWithoutAgentExecution() {
        when(cmd.getOperation()).thenReturn("resize");
        reason("ALLOCATION_OPERATION_UNSUPPORTED"); verifyNoInteractions(agentManager);
    }

    @Test public void gfs2CapabilityUsesRequestedFormatAndPolicy() throws Exception {
        qualifiedPool(); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(agentManager.send(eq(2L), any(GetVolumeAllocationCapabilitiesCommand.class))).thenAnswer(invocation -> {
            GetVolumeAllocationCapabilitiesCommand c = invocation.getArgument(1);
            return new GetVolumeAllocationCapabilitiesAnswer(c, "GFS2", Map.of("metadata", "qemu-preallocation-metadata", "full", "qemu-preallocation-full", "falloc", "qemu-preallocation-falloc"));
        });
        manager.validateCreate(VolumeAllocationPolicy.request("metadata", "qcow2", null), Storage.ProvisioningType.SPARSE, 10, 1L, null);
        manager.validateCreate(VolumeAllocationPolicy.request("falloc", "qcow2", null), Storage.ProvisioningType.FAT, 10, 1L, null);
    }
    @Test public void gfs2LifecycleIsRejectedWithoutAgentWrites() {
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(cmd.getOperation()).thenReturn("resize"); reason("ALLOCATION_OPERATION_UNSUPPORTED"); verifyNoInteractions(agentManager);
    }
    @Test(expected = InvalidParameterValueException.class) public void gfs2RequestedPolicyCannotBeInferredFromOtherMethods() throws Exception {
        qualifiedPool(); when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(agentManager.send(eq(2L), any(GetVolumeAllocationCapabilitiesCommand.class))).thenAnswer(i ->
                new GetVolumeAllocationCapabilitiesAnswer(i.getArgument(1), "GFS2", Map.of("full", "qemu-preallocation-full")));
        manager.validateCreate(VolumeAllocationPolicy.request("metadata", "qcow2", null), Storage.ProvisioningType.SPARSE, 10, 1L, null);
    }

}
