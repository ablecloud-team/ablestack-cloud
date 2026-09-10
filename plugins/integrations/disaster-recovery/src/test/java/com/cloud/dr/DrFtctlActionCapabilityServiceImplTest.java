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

package com.cloud.dr;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import com.cloud.agent.api.FtctlDrCapabilitiesAnswer;
import com.cloud.agent.api.FtctlDrCapabilitiesCommand;

public class DrFtctlActionCapabilityServiceImplTest {
    private final DrFtctlActionCapabilityServiceImpl service = new DrFtctlActionCapabilityServiceImpl();

    @Test
    public void advertisedWriterContractKeepsReprotectAvailable() {
        FtctlDrCapabilitiesAnswer answer = completeAnswer();
        answer.setReprotectAuthorityContractVersions(Arrays.asList("2026-07-23",
                DrReprotectAuthoritySpec.CONTRACT_VERSION));

        DrFtctlActionCapabilitySnapshot snapshot = service.evaluate(answer);

        Assert.assertNull(snapshot.getBlockingReason("reprotect"));
        Assert.assertNull(snapshot.getBlockingReason("failover"));
    }

    @Test
    public void missingWriterContractBlocksReprotectBeforeDispatch() {
        FtctlDrCapabilitiesAnswer answer = completeAnswer();
        answer.setReprotectAuthorityContractVersions(Arrays.asList("2026-07-23"));

        DrFtctlActionCapabilitySnapshot snapshot = service.evaluate(answer);

        Assert.assertEquals(DrFtctlActionCapabilityServiceImpl.REPROTECT_CONTRACT_UNSUPPORTED,
                snapshot.getBlockingReason("reprotect"));
        Assert.assertEquals(DrReprotectAuthoritySpec.CONTRACT_VERSION,
                snapshot.getReasonArgs("reprotect").get("requiredVersion"));
    }

    @Test
    public void missingCommandBlocksOnlyItsActionSurface() {
        FtctlDrCapabilitiesAnswer answer = completeAnswer();
        answer.setSupportedCliCommands(Arrays.asList("dr-sync-start", "dr-failover"));
        answer.setReprotectAuthorityContractVersions(Arrays.asList(DrReprotectAuthoritySpec.CONTRACT_VERSION));

        DrFtctlActionCapabilitySnapshot snapshot = service.evaluate(answer);

        Assert.assertEquals(DrFtctlActionCapabilityServiceImpl.CAPABILITY_MISMATCH,
                snapshot.getBlockingReason("reprotect"));
        Assert.assertNull(snapshot.getBlockingReason("failover"));
    }

    @Test
    public void sourceOutageDoesNotBlockTargetRecoveryCapabilities() throws Exception {
        com.cloud.agent.AgentManager agents = org.mockito.Mockito.mock(com.cloud.agent.AgentManager.class);
        com.cloud.dr.adapter.ftctl.DrRemoteAgentClient remote =
                org.mockito.Mockito.mock(com.cloud.dr.adapter.ftctl.DrRemoteAgentClient.class);
        DrWorkerPlacementService placement = org.mockito.Mockito.mock(DrWorkerPlacementService.class);
        org.apache.commons.lang3.reflect.FieldUtils.writeField(service, "agentManager", agents, true);
        org.apache.commons.lang3.reflect.FieldUtils.writeField(service, "drRemoteAgentClient", remote, true);
        org.apache.commons.lang3.reflect.FieldUtils.writeField(service, "drWorkerPlacementService", placement, true);
        DrPlanVO plan = org.mockito.Mockito.mock(DrPlanVO.class);
        org.mockito.Mockito.when(plan.getDirection()).thenReturn(DrConstants.DIRECTION_KVM_TO_KVM);
        org.mockito.Mockito.when(plan.getSourceVmId()).thenReturn(null);
        org.mockito.Mockito.when(plan.getSourceExternalRef()).thenReturn("remote-vm");
        org.mockito.Mockito.when(plan.getActiveSide()).thenReturn("SOURCE");
        org.mockito.Mockito.when(placement.resolveWorkerHostId(plan, DrWorkerRole.TARGET)).thenReturn(7L);
        org.mockito.Mockito.when(agents.easySend(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any(FtctlDrCapabilitiesCommand.class))).thenReturn(completeAnswer());
        DrFtctlActionCapabilitySnapshot snapshot = service.evaluate(plan);
        Assert.assertEquals(DrFtctlActionCapabilityServiceImpl.CAPABILITY_UNAVAILABLE,
                snapshot.getBlockingReason("sync"));
        Assert.assertNull(snapshot.getBlockingReason("testFailover"));
        Assert.assertNull(snapshot.getBlockingReason("stopTestFailover"));
        Assert.assertNull(snapshot.getBlockingReason("failover"));
        org.mockito.Mockito.verify(placement).resolveWorkerHostId(plan, DrWorkerRole.TARGET);
    }

    private FtctlDrCapabilitiesAnswer completeAnswer() {
        FtctlDrCapabilitiesCommand command = new FtctlDrCapabilitiesCommand("plan", "availability");
        FtctlDrCapabilitiesAnswer answer = new FtctlDrCapabilitiesAnswer(command, true, "ok", "plan",
                "availability", Arrays.asList("SYNC", "RECOVER_SYNC", "PAUSE_SYNC", "RESUME_SYNC",
                        "TEST_FAILOVER", "TEST_CLEANUP", "FAILOVER", "FAILBACK", "REPROTECT", "RELEASE"),
                Arrays.asList("dr-sync-start", "dr-sync-recover", "dr-sync-pause", "dr-sync-resume",
                        "dr-test-failover", "dr-test-cleanup", "dr-failover", "dr-failback", "dr-reprotect",
                        "dr-release"),
                Collections.emptyList(), Collections.emptyList(), "test", "test", "{}");
        answer.setSupportedFeatures(Arrays.asList("control-protocol-v2"));
        return answer;
    }
}
