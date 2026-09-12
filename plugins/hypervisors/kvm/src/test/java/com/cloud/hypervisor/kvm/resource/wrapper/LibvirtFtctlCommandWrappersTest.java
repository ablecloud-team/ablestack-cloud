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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlActionAnswer;
import com.cloud.agent.api.FtctlActionCommand;
import com.cloud.agent.api.FtctlCheckAnswer;
import com.cloud.agent.api.FtctlCheckCommand;
import com.cloud.agent.api.FtctlEventsAnswer;
import com.cloud.agent.api.FtctlEventsCommand;
import com.cloud.agent.api.FtctlDrActionAnswer;
import com.cloud.agent.api.FtctlDrActionCommand;
import com.cloud.agent.api.FtctlDrStatusAnswer;
import com.cloud.agent.api.FtctlDrStatusCommand;
import com.cloud.agent.api.FtctlHealthAnswer;
import com.cloud.agent.api.FtctlHealthCommand;
import com.cloud.agent.api.FtctlRemotePreflightCommand;
import com.cloud.agent.api.FtctlStatusAnswer;
import com.cloud.agent.api.FtctlStatusCommand;
import com.cloud.agent.api.FtctlSyncAnswer;
import com.cloud.agent.api.FtctlSyncClusterCommand;
import com.cloud.agent.api.FtctlSyncProfileCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.script.Script;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtFtctlCommandWrappersTest {

    @Mock
    private LibvirtComputingResource resource;

    @Test
    public void testStatusWrapperBuildsCommandAndParsesJson() {
        LibvirtFtctlStatusCommandWrapper wrapper = new LibvirtFtctlStatusCommandWrapper();
        FtctlStatusCommand command = new FtctlStatusCommand("vm-a");

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn("{\"result\":\"ok\",\"vm\":\"vm-a\",\"mode\":\"dr\",\"protection_state\":\"protected\",\"transport_state\":\"replicating\",\"active_side\":\"primary\",\"admin_state\":\"running\",\"fencing_state\":\"clear\",\"last_error\":\"\",\"last_reconcile_ts\":\"2026-04-18T21:00:00+09:00\",\"rearm_count\":1,\"failover_count\":0}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlStatusAnswer);
            FtctlStatusAnswer statusAnswer = (FtctlStatusAnswer) answer;
            Assert.assertTrue(statusAnswer.getResult());
            Assert.assertEquals("ok", statusAnswer.getFtctlResult());
            Assert.assertEquals("vm-a", statusAnswer.getVmName());
            Assert.assertEquals("dr", statusAnswer.getMode());
            Assert.assertEquals("protected", statusAnswer.getProtectionState());
            Assert.assertEquals("replicating", statusAnswer.getTransportState());
            Assert.assertEquals("primary", statusAnswer.getActiveSide());
            Assert.assertEquals("running", statusAnswer.getAdminState());
            Assert.assertEquals("clear", statusAnswer.getFencingState());
            Assert.assertEquals(Integer.valueOf(1), statusAnswer.getRearmCount());
            Assert.assertEquals(Integer.valueOf(0), statusAnswer.getFailoverCount());

            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("status");
            Mockito.verify(script).add("--vm", "vm-a");
            Mockito.verify(script).add("--json");
        }
    }

    @Test
    public void testActionWrapperHandlesLockedResult() {
        LibvirtFtctlActionCommandWrapper wrapper = new LibvirtFtctlActionCommandWrapper();
        FtctlActionCommand command = new FtctlActionCommand(FtctlActionCommand.Action.FAILOVER, "vm-a");
        command.setMode("dr");
        command.setPeerUri("qemu+ssh://peer/system");
        command.setProfileName("vm-uuid");
        command.setForce(true);

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn("{\"result\":\"locked\",\"lock_file\":\"/run/lock/ftctl.lock\"}");
            Mockito.when(mock.getExitValue()).thenReturn(20);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlActionAnswer);
            FtctlActionAnswer actionAnswer = (FtctlActionAnswer) answer;
            Assert.assertFalse(actionAnswer.getResult());
            Assert.assertEquals(FtctlActionCommand.Action.FAILOVER, actionAnswer.getAction());
            Assert.assertEquals("locked", actionAnswer.getFtctlResult());
            Assert.assertEquals(Integer.valueOf(20), actionAnswer.getExitCode());
            Assert.assertTrue(actionAnswer.getOutput().contains("locked"));

            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("failover");
            Mockito.verify(script).add("--vm", "vm-a");
            Mockito.verify(script).add("--mode", "dr");
            Mockito.verify(script).add("--peer", "qemu+ssh://peer/system");
            Mockito.verify(script).add("--profile", "vm-uuid");
            Mockito.verify(script).add("--force");
            Mockito.verify(script).add("--json");
        }
    }

    @Test
    public void testDrFailbackCommitVerifiesDurableAcknowledgementAfterNonZeroExit() {
        LibvirtFtctlDrActionCommandWrapper wrapper = new LibvirtFtctlDrActionCommandWrapper();
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILBACK_COMMIT, "plan-a", "run-a");
        command.setRunType("FAILBACK");
        command.setActionIntent("FAILBACK");
        command.setFailbackCommitContractVersion("DR_FAILBACK_COMMIT_V1");
        command.setFailbackSessionId("session-a");
        command.setFailbackCheckpointSequence(7L);
        command.setFailbackAuthorityGeneration(9L);
        command.setFailbackBaselineGeneration(8L);
        command.setFailbackEvidenceRunUuid("run-a");
        command.setFailbackCommitAttemptId("attempt-a");
        command.setFailbackCommitEnvelopeSha256(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        command.setFailbackTargetPowerState("POWERED_OFF");
        command.setFailbackSourcePowerState("POWERED_ON");
        command.setFailbackBootValidationState("POWER_STATE_VALIDATED");
        command.setAuthoritySequenceFloor(153L);

        AtomicInteger index = new AtomicInteger();
        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            int current = index.getAndIncrement();
            if (current == 0) {
                Mockito.when(mock.execute(Mockito.any())).thenReturn(
                        "{\"result\":\"unknown\",\"error_code\":\"DR_FAILBACK_COMMIT_ACK_TIMEOUT\"}");
                Mockito.when(mock.getExitValue()).thenReturn(21);
            } else {
                Mockito.when(mock.execute(Mockito.any())).thenReturn(
                        "{\"result\":\"ok\",\"state\":\"SYNCING\",\"step\":\"protection-resuming\","
                                + "\"failback_commit_outcome\":\"ACKNOWLEDGED\"}");
                Mockito.when(mock.getExitValue()).thenReturn(0);
            }
        })) {
            FtctlDrActionAnswer answer = (FtctlDrActionAnswer) wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Assert.assertTrue(answer.getStatusJson().contains("\"failback_commit_outcome\":\"ACKNOWLEDGED\""));
            Assert.assertEquals(2, scripts.constructed().size());
            Mockito.verify(scripts.constructed().get(0)).add("dr-failback-commit");
            Mockito.verify(scripts.constructed().get(0)).add("--authority-sequence-floor");
            Mockito.verify(scripts.constructed().get(0)).add("153");
            Mockito.verify(scripts.constructed().get(1)).add("dr-failback-commit-status");
            Mockito.verify(scripts.constructed().get(1)).add("--session-id");
            Mockito.verify(scripts.constructed().get(1)).add("session-a");
        }
    }

    @Test
    public void testDrCutoverCommitPassesTypedEnvelopeAndVerifiesDurableAcknowledgement() {
        LibvirtFtctlDrActionCommandWrapper wrapper = new LibvirtFtctlDrActionCommandWrapper();
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.CUTOVER_COMMIT, "plan-a", "run-a");
        command.setCutoverCommitContractVersion("DR_CUTOVER_COMMIT_V2");
        command.setCutoverEngineSessionId("plan-a:run-a");
        command.setCutoverCloudSessionId("cloud-session-a");
        command.setCutoverCheckpointSequence(43L);
        command.setCutoverManifestSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        command.setCutoverAuthorityGeneration(43L);
        command.setCutoverCommitAttemptId("attempt-a");
        command.setCutoverCommitEnvelopeSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        command.setCutoverTargetVmId(266L);
        command.setCutoverTargetExternalRef("target-uuid-a");
        command.setCutoverTargetPowerState("POWERED_ON");
        command.setCutoverBootValidationState("POWER_STATE_VALIDATED");
        command.setCutoverSourceFenceState("ACKNOWLEDGED");
        command.setCutoverSourcePowerState("POWERED_OFF");

        AtomicInteger index = new AtomicInteger();
        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            if (index.getAndIncrement() == 0) {
                Mockito.when(mock.execute(Mockito.any())).thenReturn("timed out waiting for FTCTL cutover commit");
                Mockito.when(mock.getExitValue()).thenReturn(21);
            } else {
                Mockito.when(mock.execute(Mockito.any())).thenReturn(
                        "{\"result\":\"ok\",\"state\":\"FAILED_OVER\",\"active_side\":\"TARGET\","
                                + "\"commit_state\":\"ACKNOWLEDGED\",\"commit_outcome\":\"ACKNOWLEDGED\"}");
                Mockito.when(mock.getExitValue()).thenReturn(0);
            }
        })) {
            FtctlDrActionAnswer answer = (FtctlDrActionAnswer) wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals(2, scripts.constructed().size());
            Script commit = scripts.constructed().get(0);
            Mockito.verify(commit).add("dr-cutover-commit");
            Mockito.verify(commit).add("--engine-session-id");
            Mockito.verify(commit).add("plan-a:run-a");
            Mockito.verify(commit).add("--cloud-session-id");
            Mockito.verify(commit).add("cloud-session-a");
            Mockito.verify(commit).add("--checkpoint-sequence");
            Mockito.verify(commit, Mockito.times(2)).add("43");
            Mockito.verify(commit).add("--target-vm-id");
            Mockito.verify(commit).add("266");
            Mockito.verify(commit).add("--source-fence-state");
            Mockito.verify(commit).add("ACKNOWLEDGED");
            Script status = scripts.constructed().get(1);
            Mockito.verify(status).add("dr-cutover-commit-status");
            Mockito.verify(status).add("--commit-attempt-id");
            Mockito.verify(status).add("attempt-a");
        }
    }

    @Test
    public void testDrFailbackAbortPassesRollbackPhaseAndPowerEvidence() {
        LibvirtFtctlDrActionCommandWrapper wrapper = new LibvirtFtctlDrActionCommandWrapper();
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILBACK_ABORT, "plan-a", "run-a");
        command.setContextParam("failbackSessionId", "session-a");
        command.setContextParam("rollbackPhase", "prepare");
        command.setFailbackTargetPowerState("POWERED_OFF");
        command.setFailbackSourcePowerState("POWERED_ON");

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(
                    "{\"result\":\"ok\",\"rollback_state\":\"FENCED\"}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrActionAnswer answer = (FtctlDrActionAnswer) wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("--phase");
            Mockito.verify(script).add("prepare");
            Mockito.verify(script).add("--target-power-state");
            Mockito.verify(script).add("POWERED_OFF");
            Mockito.verify(script).add("--source-power-state");
            Mockito.verify(script).add("POWERED_ON");
        }
    }

    @Test
    public void testCanceledRunCanAcknowledgeFailbackAbortPrepareFence() {
        LibvirtFtctlDrActionCommandWrapper wrapper = new LibvirtFtctlDrActionCommandWrapper();
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILBACK_ABORT, "plan-a", "run-a");
        command.setContextParam("rollbackPhase", "prepare");

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(
                    "{\"result\":\"ok\",\"accepted\":true,\"state\":\"CANCELED\","
                            + "\"rollback_state\":\"FENCED\"}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrActionAnswer answer = (FtctlDrActionAnswer) wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("CANCELED", answer.getState());
        }
    }

    @Test
    public void testCanceledRunCanAcknowledgeCompletedFailbackAbortCommit() {
        LibvirtFtctlDrActionCommandWrapper wrapper = new LibvirtFtctlDrActionCommandWrapper();
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILBACK_ABORT, "plan-a", "run-a");
        command.setContextParam("rollbackPhase", "commit");

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(
                    "{\"result\":\"ok\",\"accepted\":false,\"state\":\"FAILED_OVER\","
                            + "\"rollback_state\":\"COMPLETED\",\"cloud_lifecycle_state\":\"ABORTED\","
                            + "\"failback_commit_outcome\":\"ROLLED_BACK\",\"active_side\":\"TARGET\","
                            + "\"source_power_state\":\"POWERED_OFF\",\"target_power_state\":\"POWERED_ON\"}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrActionAnswer answer = (FtctlDrActionAnswer) wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("FAILED_OVER", answer.getState());
        }
    }

    @Test
    public void testCanceledRunCanAcknowledgeCompletedFailbackAbortDuringPrepareRetry() {
        LibvirtFtctlDrActionCommandWrapper wrapper = new LibvirtFtctlDrActionCommandWrapper();
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILBACK_ABORT, "plan-a", "run-a");
        command.setContextParam("rollbackPhase", "prepare");

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(
                    "{\"result\":\"ok\",\"accepted\":false,\"state\":\"FAILED_OVER\","
                            + "\"rollback_state\":\"COMPLETED\",\"cloud_lifecycle_state\":\"ABORTED\","
                            + "\"failback_commit_outcome\":\"ROLLED_BACK\",\"active_side\":\"TARGET\","
                            + "\"source_power_state\":\"POWERED_OFF\",\"target_power_state\":\"POWERED_ON\"}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrActionAnswer answer = (FtctlDrActionAnswer) wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("FAILED_OVER", answer.getState());
        }
    }

    @Test
    public void testIncompleteFailbackAbortCommitRemainsRejected() {
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILBACK_ABORT, "plan-a", "run-a");
        command.setContextParam("rollbackPhase", "commit");
        JsonObject payload = new JsonObject();
        payload.addProperty("result", "ok");
        payload.addProperty("accepted", false);
        payload.addProperty("state", "FAILED_OVER");
        payload.addProperty("rollback_state", "FENCED");

        Assert.assertFalse(LibvirtFtctlDrActionCommandWrapper.isCompletedFailbackAbort(command, payload));
    }

    @Test
    public void testCanceledRunCanAcknowledgeCompletedFailoverAbort() {
        LibvirtFtctlDrActionCommandWrapper wrapper = new LibvirtFtctlDrActionCommandWrapper();
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILOVER_ABORT, "plan-a", "run-a");

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(
                    "{\"result\":\"ok\",\"accepted\":false,\"state\":\"ABORTED\","
                            + "\"step\":\"failover-preparation-aborted\",\"active_side\":\"SOURCE\","
                            + "\"target_power_state\":\"POWERED_OFF\"}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrActionAnswer answer = (FtctlDrActionAnswer) wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("ABORTED", answer.getState());
        }
    }

    @Test
    public void testFailoverAbortWithPoweredOnTargetRemainsRejected() {
        FtctlDrActionCommand command = new FtctlDrActionCommand(
                FtctlDrActionCommand.Action.FAILOVER_ABORT, "plan-a", "run-a");
        JsonObject payload = new JsonObject();
        payload.addProperty("result", "ok");
        payload.addProperty("accepted", false);
        payload.addProperty("state", "ABORTED");
        payload.addProperty("step", "failover-preparation-aborted");
        payload.addProperty("active_side", "SOURCE");
        payload.addProperty("target_power_state", "POWERED_ON");

        Assert.assertFalse(LibvirtFtctlDrActionCommandWrapper.isCompletedFailoverAbort(command, payload));
    }

    @Test
    public void testCheckWrapperBuildsCommandAndParsesJson() {
        LibvirtFtctlCheckCommandWrapper wrapper = new LibvirtFtctlCheckCommandWrapper();
        FtctlCheckCommand command = new FtctlCheckCommand("vm-a", "i-2-309-VM", "secondary", "cloud-managed");

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn("{\"result\":\"ok\",\"inventory_result\":\"healthy\",\"vm\":\"vm-a\",\"primary_rc\":1,\"peer_rc\":0,\"peer_domain_expected\":true,\"standby_domain_state\":\"running\",\"provisioning_backend\":\"cloud-managed\"}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlCheckAnswer);
            FtctlCheckAnswer checkAnswer = (FtctlCheckAnswer) answer;
            Assert.assertTrue(checkAnswer.getResult());
            Assert.assertEquals("ok", checkAnswer.getFtctlResult());
            Assert.assertEquals("healthy", checkAnswer.getInventoryResult());
            Assert.assertEquals("vm-a", checkAnswer.getVmName());
            Assert.assertEquals(Integer.valueOf(1), checkAnswer.getPrimaryRc());
            Assert.assertEquals(Integer.valueOf(0), checkAnswer.getPeerRc());
            Assert.assertEquals(Boolean.TRUE, checkAnswer.getPeerDomainExpected());
            Assert.assertEquals("running", checkAnswer.getStandbyDomainState());
            Assert.assertEquals("cloud-managed", checkAnswer.getProvisioningBackend());

            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("check");
            Mockito.verify(script).add("--vm", "vm-a");
            Mockito.verify(script).add("--secondary-vm-name", "i-2-309-VM");
            Mockito.verify(script).add("--active-side", "secondary");
            Mockito.verify(script).add("--provisioning-backend", "cloud-managed");
            Mockito.verify(script).add("--json");
        }
    }

    @Test
    public void testHealthWrapperBuildsCommandAndParsesJson() {
        LibvirtFtctlHealthCommandWrapper wrapper = new LibvirtFtctlHealthCommandWrapper();
        FtctlHealthCommand command = new FtctlHealthCommand();

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn("{\"result\":\"ok\",\"uri\":\"qemu+ssh://10.0.0.11/system\",\"rc\":0}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlHealthAnswer);
            FtctlHealthAnswer healthAnswer = (FtctlHealthAnswer) answer;
            Assert.assertTrue(healthAnswer.getResult());
            Assert.assertEquals("ok", healthAnswer.getFtctlResult());
            Assert.assertEquals("qemu+ssh://10.0.0.11/system", healthAnswer.getUri());
            Assert.assertEquals(Integer.valueOf(0), healthAnswer.getRc());

            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("health");
            Mockito.verify(script).add("--json");
        }
    }

    @Test
    public void testEventsWrapperBuildsCommandAndParsesJson() {
        LibvirtFtctlEventsCommandWrapper wrapper = new LibvirtFtctlEventsCommandWrapper();
        FtctlEventsCommand command = new FtctlEventsCommand("vm-a", 5);

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn("{\"result\":\"ok\",\"vm\":\"vm-a\",\"count\":2,\"items\":[{\"ts\":\"2026-04-18T21:30:00+09:00\",\"event\":\"tick\"},{\"ts\":\"2026-04-18T21:31:00+09:00\",\"event\":\"rearm\"}]}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlEventsAnswer);
            FtctlEventsAnswer eventsAnswer = (FtctlEventsAnswer) answer;
            Assert.assertTrue(eventsAnswer.getResult());
            Assert.assertEquals("ok", eventsAnswer.getFtctlResult());
            Assert.assertEquals("vm-a", eventsAnswer.getVmName());
            Assert.assertEquals(Integer.valueOf(2), eventsAnswer.getCount());
            Assert.assertTrue(eventsAnswer.getItemsJson().contains("\"event\":\"tick\""));
            Assert.assertTrue(eventsAnswer.getItemsJson().contains("\"event\":\"rearm\""));

            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("events");
            Mockito.verify(script).add("--vm", "vm-a");
            Mockito.verify(script).add("--limit", "5");
            Mockito.verify(script).add("--json");
        }
    }

    @Test
    public void testSyncClusterWrapperBuildsInitAndUpsertCommands() {
        LibvirtFtctlSyncClusterCommandWrapper wrapper = new LibvirtFtctlSyncClusterCommandWrapper();
        FtctlSyncClusterCommand command = new FtctlSyncClusterCommand();
        command.setClusterName("cluster-301");
        command.setLocalHostId("201");
        command.setLocalRole("primary");
        command.setLocalManagementIp("192.168.0.11");
        command.setLocalLibvirtUri("qemu+ssh://10.0.0.11/system");
        command.setLocalBlockcopyIp("10.0.0.11");
        command.setLocalXcoloControlIp("10.0.1.11");
        command.setLocalXcoloDataIp("10.0.2.11");
        command.setPeerHostId("202");
        command.setPeerRole("secondary");
        command.setPeerManagementIp("192.168.0.12");
        command.setPeerLibvirtUri("qemu+ssh://10.0.0.12/system");
        command.setPeerBlockcopyIp("10.0.0.12");
        command.setPeerXcoloControlIp("10.0.1.12");
        command.setPeerXcoloDataIp("10.0.2.12");

        AtomicInteger index = new AtomicInteger();
        List<String> outputs = List.of("init-ok", "local-upsert-ok", "peer-upsert-ok");
        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            int current = index.getAndIncrement();
            Mockito.when(mock.execute(Mockito.any())).thenReturn(outputs.get(current));
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlSyncAnswer);
            FtctlSyncAnswer syncAnswer = (FtctlSyncAnswer) answer;
            Assert.assertTrue(syncAnswer.getResult());
            Assert.assertEquals("ok", syncAnswer.getFtctlResult());
            Assert.assertEquals(Integer.valueOf(0), syncAnswer.getExitCode());
            Assert.assertTrue(syncAnswer.getOutput().contains("local-upsert-ok"));
            Assert.assertTrue(syncAnswer.getOutput().contains("peer-upsert-ok"));

            Assert.assertEquals(3, scripts.constructed().size());
            Script initScript = scripts.constructed().get(0);
            Script localUpsert = scripts.constructed().get(1);
            Script peerUpsert = scripts.constructed().get(2);

            Mockito.verify(initScript).add("config");
            Mockito.verify(initScript).add("init-cluster");
            Mockito.verify(initScript).add("--cluster-name", "cluster-301");
            Mockito.verify(initScript).add("--local-host-id", "201");
            Mockito.verify(initScript).add("--json");

            Mockito.verify(localUpsert).add("config");
            Mockito.verify(localUpsert).add("host-upsert");
            Mockito.verify(localUpsert).add("--host-id", "201");
            Mockito.verify(localUpsert).add("--role", "primary");
            Mockito.verify(localUpsert).add("--management-ip", "192.168.0.11");
            Mockito.verify(localUpsert).add("--libvirt-uri", "qemu+ssh://10.0.0.11/system");
            Mockito.verify(localUpsert).add("--blockcopy-ip", "10.0.0.11");
            Mockito.verify(localUpsert).add("--xcolo-control-ip", "10.0.1.11");
            Mockito.verify(localUpsert).add("--xcolo-data-ip", "10.0.2.11");
            Mockito.verify(localUpsert).add("--json");

            Mockito.verify(peerUpsert).add("--host-id", "202");
            Mockito.verify(peerUpsert).add("--role", "secondary");
            Mockito.verify(peerUpsert).add("--management-ip", "192.168.0.12");
            Mockito.verify(peerUpsert).add("--libvirt-uri", "qemu+ssh://10.0.0.12/system");
        }
    }

    @Test
    public void testSyncProfileWrapperBuildsCommandWithOptionalFields() {
        LibvirtFtctlSyncProfileCommandWrapper wrapper = new LibvirtFtctlSyncProfileCommandWrapper();
        FtctlSyncProfileCommand command = new FtctlSyncProfileCommand("vm-a", "ft", "qemu+ssh://peer/system");
        command.setProfileName("vm-uuid");
        command.setDiskMap("vda=rbd:rbd/vm-a-secondary-disk0");
        command.setBackendMode("remote-nbd");
        command.setProvisioningBackend("cloud-managed");
        command.setTargetStorageScope("host");
        command.setSecondaryVmName("vm-a-secondary");
        command.setFencingPolicy("manual-block");
        command.setSecondaryTargetDir("/data/secondary");
        command.setRemoteNbdExportAddr("10.0.0.12:10809");
        command.setXcoloProxyEndpoint("10.0.10.12:7000");
        command.setXcoloNbdEndpoint("10.0.10.12:7001");
        command.setXcoloMigrateUri("tcp:10.0.10.12:4444");
        command.setFencingIpmiPrimaryHost("10.10.10.201");
        command.setFencingIpmiPrimaryPort("623");
        command.setFencingIpmiPrimaryUser("admin-a");
        command.setFencingIpmiPrimaryPassword("password-a");
        command.setFencingIpmiPrimaryInterface("lanplus");
        command.setFencingIpmiSecondaryHost("10.10.10.202");
        command.setFencingIpmiSecondaryPort("624");
        command.setFencingIpmiSecondaryUser("admin-b");
        command.setFencingIpmiSecondaryPassword("password-b");
        command.setFencingIpmiSecondaryInterface("lanplus");
        command.setSecondarySshKeyFile("/root/.ssh/ftctl-dr/vm-a/id_ed25519");

        AtomicInteger constructed = new AtomicInteger();
        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            int index = constructed.getAndIncrement();
            Mockito.when(mock.execute(Mockito.any())).thenReturn(index == 0 ? "{\"result\":\"ok\"}" : "vda=rbd:rbd/vm-a-secondary-disk0");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlSyncAnswer);
            FtctlSyncAnswer syncAnswer = (FtctlSyncAnswer) answer;
            Assert.assertTrue(syncAnswer.getResult());
            Assert.assertEquals("ok", syncAnswer.getFtctlResult());
            Assert.assertEquals(Integer.valueOf(0), syncAnswer.getExitCode());

            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("config");
            Mockito.verify(script).add("profile-upsert");
            Mockito.verify(script).add("--vm", "vm-a");
            Mockito.verify(script).add("--mode", "ft");
            Mockito.verify(script).add("--peer", "qemu+ssh://peer/system");
            Mockito.verify(script).add("--profile", "vm-uuid");
            Mockito.verify(script).add("--disk-map", "vda=rbd:rbd/vm-a-secondary-disk0");
            Mockito.verify(script).add("--backend-mode", "remote-nbd");
            Mockito.verify(script).add("--provisioning-backend", "cloud-managed");
            Mockito.verify(script).add("--target-storage-scope", "host");
            Mockito.verify(script).add("--secondary-vm-name", "vm-a-secondary");
            Mockito.verify(script).add("--fencing-policy", "manual-block");
            Mockito.verify(script).add("--fencing-ipmi-primary-host", "10.10.10.201");
            Mockito.verify(script).add("--fencing-ipmi-primary-port", "623");
            Mockito.verify(script).add("--fencing-ipmi-primary-user", "admin-a");
            Mockito.verify(script).add("--fencing-ipmi-primary-password", "password-a");
            Mockito.verify(script).add("--fencing-ipmi-primary-interface", "lanplus");
            Mockito.verify(script).add("--fencing-ipmi-secondary-host", "10.10.10.202");
            Mockito.verify(script).add("--fencing-ipmi-secondary-port", "624");
            Mockito.verify(script).add("--fencing-ipmi-secondary-user", "admin-b");
            Mockito.verify(script).add("--fencing-ipmi-secondary-password", "password-b");
            Mockito.verify(script).add("--fencing-ipmi-secondary-interface", "lanplus");
            Mockito.verify(script).add("--secondary-target-dir", "/data/secondary");
            Mockito.verify(script).add("--secondary-ssh-key-file", "/root/.ssh/ftctl-dr/vm-a/id_ed25519");
            Mockito.verify(script).add("--remote-nbd-export-addr", "10.0.0.12:10809");
            Mockito.verify(script).add("--xcolo-proxy-endpoint", "10.0.10.12:7000");
            Mockito.verify(script).add("--xcolo-nbd-endpoint", "10.0.10.12:7001");
            Mockito.verify(script).add("--xcolo-migrate-uri", "tcp:10.0.10.12:4444");
            Mockito.verify(script).add("--json");
        }
    }

    @Test
    public void testRemotePreflightWrapperBuildsCommandWithSshKeyFile() {
        LibvirtFtctlRemotePreflightCommandWrapper wrapper = new LibvirtFtctlRemotePreflightCommandWrapper();
        FtctlRemotePreflightCommand command = new FtctlRemotePreflightCommand("vm-a", "dr", "qemu+ssh://root@10.0.0.12:22/system");
        command.setSecondaryTargetDir("rbd");
        command.setSecondarySshKeyFile("/root/.ssh/ftctl-dr/vm-a/id_ed25519");
        command.setRemoteNbdExportAddr("10.0.0.12:10809");

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn("{\"result\":\"ok\"}");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlSyncAnswer);
            Assert.assertTrue(answer.getResult());

            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("preflight-remote");
            Mockito.verify(script).add("--vm", "vm-a");
            Mockito.verify(script).add("--mode", "dr");
            Mockito.verify(script).add("--peer", "qemu+ssh://root@10.0.0.12:22/system");
            Mockito.verify(script).add("--secondary-target-dir", "rbd");
            Mockito.verify(script).add("--secondary-ssh-key-file", "/root/.ssh/ftctl-dr/vm-a/id_ed25519");
            Mockito.verify(script).add("--remote-nbd-export-addr", "10.0.0.12:10809");
            Mockito.verify(script).add("--json");
        }
    }

    @Test
    public void testDrStatusWrapperParsesStrictPlanOwnedJson() {
        LibvirtFtctlDrStatusCommandWrapper wrapper = new LibvirtFtctlDrStatusCommandWrapper();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-a", "cleanup-run");
        String mixedOutput = "{\"command\":\"dr-status\",\"result\":\"ok\",\"plan_uuid\":\"plan-a\",\"run_uuid\":\"cleanup-run\",\"status_scope\":\"OPERATION\","
                + "\"state\":\"ERROR\",\"step\":\"replication-cycle-failed\","
                + "\"progress\":100,\"worker_state\":\"FAILED\",\"worker_pid\":3981802,"
                + "\"worker_start_ticks\":177777,\"worker_pid_alive\":false,\"worker_exit_code\":68,"
                + "\"driver_exit_code\":83,\"failure_phase\":\"REVERSE_TRANSFER\","
                + "\"terminal_source\":\"ENGINE_TERMINAL\",\"terminal_version\":1,"
                + "\"worker_identity_state\":\"MATCHED\",\"worker_liveness_state\":\"ALIVE\","
                + "\"worker_launch_nonce\":\"launch-a\",\"worker_generation\":4,"
                + "\"transfer_activity_state\":\"COPYING\",\"transfer_payload_bytes\":1048576,"
                + "\"owned_process_count\":3,\"reconciliation_required\":false,"
                + "\"runtime_endpoints_drained\":false,\"terminal_authoritative\":true,"
                + "\"terminal_publication_pending\":false,\"terminal_publication_pending_since\":\"2026-08-05T01:02:03+0900\","
                + "\"baseline_file_state\":\"MISSING_EXPECTED\",\"source_disk_probe_state\":\"READY\","
                + "\"target_writer_probe_state\":\"FAILED\","
                + "\"error_code\":\"DR_CBT_METRICS_INVALID\","
                + "\"error_message\":\"Disk data copied, but cycle metadata validation failed\","
                + "\"failed_component\":\"vmware-mover\",\"data_commit_state\":\"DATA_COPIED_METADATA_FAILED\","
                + "\"data_copied\":true,\"metadata_committed\":false,\"target_durable\":false,"
                + "\"cycle_retry_mode\":\"RESEED_REQUIRED\",\"target_vm_present\":false,"
                + "\"current_checkpoint_sequence\":7,\"current_checkpoint_state\":\"FAILED\","
                + "\"active_worker_run_uuid\":\"sync-run\","
                + "\"latest_completed_checkpoint_sequence\":6,\"latest_completed_checkpoint_ref\":\"ftctl:plan-a:sync-run:6\","
                + "\"latest_completed_producer_run_uuid\":\"sync-run\","
                + "\"latest_completed_checkpoint_state\":\"READY\",\"latest_completed_target_ready_rpo_seconds\":18,"
                + "\"latest_completed_nbd_teardown_state\":\"DRAINED\","
                + "\"latest_completed_nbd_teardown_started_at_ms\":1782867901000,"
                + "\"latest_completed_nbd_teardown_completed_at_ms\":1782867902000,"
                + "\"latest_completed_nbd_teardown_duration_ms\":1000,"
                + "\"latest_completed_nbd_source_device_count\":1,"
                + "\"latest_completed_nbd_target_device_count\":1,"
                + "\"latest_completed_nbd_quarantined_device_count\":0,"
                + "\"control_protocol_version\":2,\"control_generation\":9,\"control_ack_generation\":9,"
                + "\"control_state\":\"RUNNING\",\"cycle_state\":\"IDLE\",\"transition_state\":\"COMPLETED\","
                + "\"checkpoint_lease_state\":\"RELEASED\","
                + "\"guest_prep_state\":\"READY\",\"guest_family\":\"windows\","
                + "\"manifest_schema_version\":\"FTCTL_GUESTPREP_MANIFEST_V2\","
                + "\"manifest_sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\","
                + "\"guestprep_checkpoint_sequence\":6}\n";

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(mixedOutput);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof FtctlDrStatusAnswer);
            FtctlDrStatusAnswer statusAnswer = (FtctlDrStatusAnswer) answer;
            Assert.assertTrue(statusAnswer.getResult());
            Assert.assertEquals("OPERATION", statusAnswer.getStatusScope());
            Assert.assertEquals("ERROR", statusAnswer.getState());
            Assert.assertEquals("replication-cycle-failed", statusAnswer.getStep());
            Assert.assertEquals("FAILED", statusAnswer.getWorkerState());
            Assert.assertEquals(Long.valueOf(177777), statusAnswer.getWorkerStartTicks());
            Assert.assertEquals(Boolean.FALSE, statusAnswer.getWorkerPidAlive());
            Assert.assertEquals(Integer.valueOf(68), statusAnswer.getWorkerExitCode());
            Assert.assertEquals(Integer.valueOf(83), statusAnswer.getDriverExitCode());
            Assert.assertEquals("REVERSE_TRANSFER", statusAnswer.getFailurePhase());
            Assert.assertEquals("ENGINE_TERMINAL", statusAnswer.getTerminalSource());
            Assert.assertEquals(Integer.valueOf(1), statusAnswer.getTerminalVersion());
            Assert.assertEquals("MATCHED", statusAnswer.getWorkerIdentityState());
            Assert.assertEquals("ALIVE", statusAnswer.getWorkerLivenessState());
            Assert.assertEquals("launch-a", statusAnswer.getWorkerLaunchNonce());
            Assert.assertEquals(Long.valueOf(4), statusAnswer.getWorkerGeneration());
            Assert.assertEquals("COPYING", statusAnswer.getTransferActivityState());
            Assert.assertEquals(Long.valueOf(1048576), statusAnswer.getTransferPayloadBytes());
            Assert.assertEquals(Integer.valueOf(3), statusAnswer.getOwnedProcessCount());
            Assert.assertEquals(Boolean.FALSE, statusAnswer.getReconciliationRequired());
            Assert.assertEquals(Boolean.FALSE, statusAnswer.getRuntimeEndpointsDrained());
            Assert.assertEquals(Boolean.TRUE, statusAnswer.getTerminalAuthoritative());
            Assert.assertEquals(Boolean.FALSE, statusAnswer.getTerminalPublicationPending());
            Assert.assertEquals("2026-08-05T01:02:03+0900", statusAnswer.getTerminalPublicationPendingSince());
            Assert.assertEquals("MISSING_EXPECTED", statusAnswer.getBaselineFileState());
            Assert.assertEquals("READY", statusAnswer.getSourceDiskProbeState());
            Assert.assertEquals("FAILED", statusAnswer.getTargetWriterProbeState());
            Assert.assertEquals("DR_CBT_METRICS_INVALID", statusAnswer.getErrorCode());
            Assert.assertEquals("Disk data copied, but cycle metadata validation failed", statusAnswer.getErrorMessage());
            Assert.assertEquals("vmware-mover", statusAnswer.getFailedComponent());
            Assert.assertEquals("DATA_COPIED_METADATA_FAILED", statusAnswer.getDataCommitState());
            Assert.assertEquals(Boolean.TRUE, statusAnswer.getDataCopied());
            Assert.assertEquals(Boolean.FALSE, statusAnswer.getMetadataCommitted());
            Assert.assertEquals(Boolean.FALSE, statusAnswer.getTargetDurable());
            Assert.assertEquals("RESEED_REQUIRED", statusAnswer.getCycleRetryMode());
            Assert.assertEquals("Disk data copied, but cycle metadata validation failed", statusAnswer.getDetails());
            Assert.assertEquals(Boolean.FALSE, statusAnswer.getTargetVmPresent());
            Assert.assertEquals(Long.valueOf(7), statusAnswer.getCurrentCheckpointSequence());
            Assert.assertEquals("FAILED", statusAnswer.getCurrentCheckpointState());
            Assert.assertEquals(Long.valueOf(6), statusAnswer.getLatestCompletedCheckpointSequence());
            Assert.assertEquals("ftctl:plan-a:sync-run:6", statusAnswer.getLatestCompletedCheckpointRef());
            Assert.assertEquals("sync-run", statusAnswer.getLatestCompletedProducerRunUuid());
            Assert.assertEquals("READY", statusAnswer.getLatestCompletedCheckpointState());
            Assert.assertEquals(Integer.valueOf(18), statusAnswer.getLatestCompletedTargetReadyRpoSeconds());
            Assert.assertEquals("DRAINED", statusAnswer.getLatestCompletedNbdTeardownState());
            Assert.assertEquals(Long.valueOf(1000), statusAnswer.getLatestCompletedNbdTeardownDurationMs());
            Assert.assertEquals(Integer.valueOf(1), statusAnswer.getLatestCompletedNbdSourceDeviceCount());
            Assert.assertEquals(Integer.valueOf(1), statusAnswer.getLatestCompletedNbdTargetDeviceCount());
            Assert.assertEquals(Integer.valueOf(0), statusAnswer.getLatestCompletedNbdQuarantinedDeviceCount());
            Assert.assertEquals(Integer.valueOf(2), statusAnswer.getControlProtocolVersion());
            Assert.assertEquals(Long.valueOf(9), statusAnswer.getControlGeneration());
            Assert.assertEquals(Long.valueOf(9), statusAnswer.getControlAckGeneration());
            Assert.assertEquals("RUNNING", statusAnswer.getControlState());
            Assert.assertEquals("IDLE", statusAnswer.getCycleState());
            Assert.assertEquals("COMPLETED", statusAnswer.getTransitionState());
            Assert.assertEquals("RELEASED", statusAnswer.getCheckpointLeaseState());
            Assert.assertEquals("FTCTL_GUESTPREP_MANIFEST_V2", statusAnswer.getManifestSchemaVersion());
            Assert.assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    statusAnswer.getManifestSha256());
            Assert.assertEquals(Long.valueOf(6), statusAnswer.getGuestPreparationCheckpointSequence());
            Assert.assertEquals(Integer.valueOf(1), statusAnswer.getCycleContractVersion());
            Assert.assertNotNull(statusAnswer.getLatestCompletedCycle());
            Assert.assertEquals("sync-run", statusAnswer.getLatestCompletedCycle().getRunUuid());
            Assert.assertEquals(Long.valueOf(6), statusAnswer.getLatestCompletedCycle().getSequence());
            Assert.assertEquals("plan-a:6", statusAnswer.getLatestCompletedCycle().getCycleToken());
            Assert.assertTrue(statusAnswer.getStatusJson().contains("\"worker_state\":\"FAILED\""));
            Mockito.verify(scripts.constructed().get(0)).add("--events-limit");
            Mockito.verify(scripts.constructed().get(0)).add("0");
            Mockito.verify(scripts.constructed().get(0)).add("--run");
            Mockito.verify(scripts.constructed().get(0)).add("cleanup-run");
        }
    }

    @Test
    public void testDrStatusWrapperOmitsRunForPlanAuthorityScope() {
        LibvirtFtctlDrStatusCommandWrapper wrapper = new LibvirtFtctlDrStatusCommandWrapper();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-a", null,
                FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
        String output = "{\"command\":\"dr-status\",\"result\":\"ok\",\"plan_uuid\":\"plan-a\"," +
                "\"status_scope\":\"PLAN_AUTHORITY\",\"state\":\"FAILED_OVER\",\"active_side\":\"TARGET\"," +
                "\"step\":\"cloud-promotion-committed\",\"progress\":100,\"scheduler_state\":\"STOPPED\"," +
                "\"scheduler_desired_state\":\"STOPPED\",\"scheduler_health\":\"SUPPRESSED\"," +
                "\"replication_activity\":\"STOPPED\",\"engine_ack_state\":\"ACKNOWLEDGED\"}";

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(output);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrStatusAnswer answer = (FtctlDrStatusAnswer) wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Assert.assertEquals("PLAN_AUTHORITY", answer.getStatusScope());
            Assert.assertEquals("FAILED_OVER", answer.getState());
            Assert.assertEquals("STOPPED", answer.getSchedulerDesiredState());
            Assert.assertEquals("SUPPRESSED", answer.getSchedulerHealth());
            Assert.assertEquals("STOPPED", answer.getReplicationActivity());
            Mockito.verify(scripts.constructed().get(0), Mockito.never()).add("--run");
        }
    }

    @Test
    public void testDrTransitionPreflightWrapperAcceptsOnlyV2TypedContract() {
        LibvirtFtctlDrStatusCommandWrapper wrapper = new LibvirtFtctlDrStatusCommandWrapper();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-a", null,
                FtctlDrStatusCommand.StatusScope.TRANSITION_PREFLIGHT);
        command.setTransitionOperation("reprotect");
        command.setExpectedAuthoritySide("TARGET");
        command.setExpectedAuthorityGeneration(7L);
        String output = "{\"command\":\"dr-transition-preflight\",\"schema_version\":2,"
                + "\"contract_version\":\"dr-transition-preflight-v2\","
                + "\"status_scope\":\"TRANSITION_PREFLIGHT\",\"result\":\"ok\","
                + "\"ready\":true,\"retryable\":false,\"plan_uuid\":\"plan-a\","
                + "\"operation\":\"reprotect\",\"expected_authority\":\"TARGET\","
                + "\"active_side\":\"TARGET\",\"expected_generation\":7,"
                + "\"authority_generation\":7,\"target_power_state\":\"POWERED_ON\","
                + "\"source_fence_state\":\"ACKNOWLEDGED\","
                + "\"source_power_state\":\"POWERED_OFF\","
                + "\"checked_at_epoch_ms\":1777777777000,\"exit_code\":0}";

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(output);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrStatusAnswer answer = (FtctlDrStatusAnswer) wrapper.execute(command, resource);
            Assert.assertTrue(answer.getResult());
            Assert.assertTrue(answer.getTransitionReady());
            Assert.assertEquals("dr-transition-preflight-v2", answer.getTransitionContractVersion());
            Assert.assertEquals("TARGET", answer.getTransitionActiveSide());
            Assert.assertEquals(Long.valueOf(7L), answer.getTransitionAuthorityGeneration());
        }
    }

    @Test
    public void testDrTransitionPreflightWrapperRejectsGenericStatusPayload() {
        LibvirtFtctlDrStatusCommandWrapper wrapper = new LibvirtFtctlDrStatusCommandWrapper();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-a", null,
                FtctlDrStatusCommand.StatusScope.TRANSITION_PREFLIGHT);
        command.setTransitionOperation("failback");
        command.setExpectedAuthoritySide("TARGET");
        command.setExpectedAuthorityGeneration(7L);
        String output = "{\"command\":\"dr-status\",\"result\":\"ok\","
                + "\"plan_uuid\":\"plan-a\",\"state\":\"FAILED_OVER\"}";

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(output);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrStatusAnswer answer = (FtctlDrStatusAnswer) wrapper.execute(command, resource);
            Assert.assertFalse(answer.getResult());
            Assert.assertEquals("DR_AGENT_TRANSITION_PREFLIGHT_CONTRACT_MISMATCH", answer.getErrorCode());
        }
    }

    @Test
    public void testDamagedHistoryPreservesOnlyIndependentPublicationEvidence() {
        for (String variant : new String[] {"valid", "incomplete", "wrong-plan", "target", "exit-failure",
                "empty-disks", "bad-identity", "bad-type", "operation", "legacy-side", "terminal"}) {
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("plan_uuid", "bad-identity".equals(variant) ? "another-plan" : "plan-a");
            payload.addProperty("run_uuid", "run-a");
            payload.addProperty("state", "terminal".equals(variant) ? "FAILED_OVER" : "READY");
            payload.addProperty("active_side", "target".equals(variant) ? "TARGET" : "legacy-side".equals(variant) ? "" : "SOURCE");
            payload.addProperty("latest_completed_checkpoint_sequence", 7);
            payload.addProperty("latest_completed_checkpoint_state", "READY");
            payload.addProperty("latest_completed_cycle_token", "incomplete".equals(variant) ? "plan-a:7" : "REDACTED");
            payload.addProperty("latest_completed_effective_mode", "CBT_INCREMENTAL");
            payload.addProperty("latest_completed_incremental_verified", true);
            if ("bad-type".equals(variant)) payload.addProperty("latest_completed_incremental_verified", "invalid");
            com.google.gson.JsonObject request = new com.google.gson.JsonObject();
            request.addProperty("planUuid", "wrong-plan".equals(variant) ? "another-plan" : "plan-a");
            request.addProperty("producerRunUuid", "new-producer");
            request.addProperty("checkpointSequence", 8);
            com.google.gson.JsonArray disks = new com.google.gson.JsonArray();
            if (!"empty-disks".equals(variant)) disks.add(new com.google.gson.JsonObject());
            request.add("disks", disks);
            com.google.gson.JsonObject pending = new com.google.gson.JsonObject();
            pending.add("request", request);
            payload.addProperty("checkpoint_publication_pending", pending.toString());
            FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-a", "run-a",
                    "operation".equals(variant) ? FtctlDrStatusCommand.StatusScope.OPERATION
                            : FtctlDrStatusCommand.StatusScope.PLAN_AUTHORITY);
            try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
                Mockito.when(mock.execute(Mockito.any())).thenReturn(payload.toString());
                Mockito.when(mock.getExitValue()).thenReturn("exit-failure".equals(variant) ? 1 : 0);
            })) {
                FtctlDrStatusAnswer answer = (FtctlDrStatusAnswer) new LibvirtFtctlDrStatusCommandWrapper().execute(command, resource);
                Assert.assertFalse(variant, answer.getResult());
                com.google.gson.JsonObject raw = new com.google.gson.JsonParser().parse(answer.getStatusJson()).getAsJsonObject();
                boolean allowed = "valid".equals(variant) || "incomplete".equals(variant) || "legacy-side".equals(variant);
                Assert.assertEquals(variant, allowed, raw.has("checkpoint_publication_recovery_only"));
                Assert.assertEquals(variant, allowed, raw.has("checkpoint_publication_pending"));
                Assert.assertFalse(raw.has("latest_completed_cycle_token"));
                Assert.assertNull(answer.getLatestCompletedCycle());
                if (allowed) Assert.assertEquals(pending.toString(), raw.get("checkpoint_publication_pending").getAsString());
            }
        }
    }

    @Test
    public void testDrStatusWrapperRejectsMixedCompletedCycleGeneration() {
        LibvirtFtctlDrStatusCommandWrapper wrapper = new LibvirtFtctlDrStatusCommandWrapper();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-a", "run-a");
        String output = "{\"command\":\"dr-status\",\"result\":\"ok\",\"plan_uuid\":\"plan-a\","
                + "\"run_uuid\":\"run-a\",\"state\":\"READY\","
                + "\"latest_completed_checkpoint_sequence\":7,\"latest_completed_checkpoint_state\":\"READY\","
                + "\"latest_completed_cycle_token\":\"plan-a:7\","
                + "\"latest_completed_baseline_generation\":8,"
                + "\"latest_completed_changed_bytes\":0,\"latest_completed_target_written_bytes\":0,"
                + "\"latest_completed_effective_mode\":\"NO_CHANGE\"}";

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(output);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrStatusAnswer answer = (FtctlDrStatusAnswer) wrapper.execute(command, resource);
            Assert.assertFalse(answer.getResult());
            Assert.assertEquals("DR_STATUS_CYCLE_EVIDENCE_CONFLICT", answer.getErrorCode());
            Assert.assertEquals("CONFLICT", answer.getCycleEvidenceState());
            Assert.assertFalse(Boolean.TRUE.equals(answer.getRetryable()));
        }
    }

    @Test
    public void testDrStatusWrapperRejectsIncrementalCycleWithoutNbdDrainEvidence() {
        LibvirtFtctlDrStatusCommandWrapper wrapper = new LibvirtFtctlDrStatusCommandWrapper();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-a", "run-a");
        String output = "{\"command\":\"dr-status\",\"result\":\"ok\",\"plan_uuid\":\"plan-a\","
                + "\"run_uuid\":\"run-a\",\"state\":\"READY\","
                + "\"latest_completed_checkpoint_sequence\":7,\"latest_completed_checkpoint_state\":\"READY\","
                + "\"latest_completed_cycle_token\":\"plan-a:7\","
                + "\"latest_completed_baseline_generation\":7,"
                + "\"latest_completed_changed_bytes\":4096,\"latest_completed_target_written_bytes\":4096,"
                + "\"latest_completed_effective_mode\":\"CBT_INCREMENTAL\","
                + "\"latest_completed_incremental_verified\":true}";

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(output);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrStatusAnswer answer = (FtctlDrStatusAnswer) wrapper.execute(command, resource);
            Assert.assertFalse(answer.getResult());
            Assert.assertEquals("DR_STATUS_CYCLE_EVIDENCE_INCOMPLETE", answer.getErrorCode());
            Assert.assertEquals("INCOMPLETE", answer.getCycleEvidenceState());
            Assert.assertTrue(Boolean.TRUE.equals(answer.getRetryable()));
        }
    }

    @Test
    public void testDrStatusWrapperRejectsProgressPrefixedJson() {
        LibvirtFtctlDrStatusCommandWrapper wrapper = new LibvirtFtctlDrStatusCommandWrapper();
        FtctlDrStatusCommand command = new FtctlDrStatusCommand("plan-a", "run-a");
        String output = "100% | transferring disk\n"
                + "{\"command\":\"dr-status\",\"result\":\"ok\",\"plan_uuid\":\"plan-a\",\"run_uuid\":\"run-a\"}";

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            Mockito.when(mock.execute(Mockito.any())).thenReturn(output);
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            FtctlDrStatusAnswer answer = (FtctlDrStatusAnswer) wrapper.execute(command, resource);
            Assert.assertFalse(answer.getResult());
            Assert.assertEquals("DR_STATUS_INVALID_JSON", answer.getErrorCode());
        }
    }

    @Test
    public void testSyncProfileWrapperNormalizesCloudManagedDiskMapToRuntimeTargets() {
        LibvirtFtctlSyncProfileCommandWrapper wrapper = new LibvirtFtctlSyncProfileCommandWrapper();
        FtctlSyncProfileCommand command = new FtctlSyncProfileCommand("i-2-333-VM", "ha", "qemu+ssh://peer/system");
        command.setDiskMap("vda=/var/lib/libvirt/images/root;vdb=/var/lib/libvirt/images/data");
        command.setBackendMode("remote-nbd");
        command.setProvisioningBackend("cloud-managed");

        AtomicInteger constructed = new AtomicInteger();
        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> {
            int index = constructed.getAndIncrement();
            Mockito.when(mock.execute(Mockito.any())).thenReturn(index == 0
                    ? "{\"result\":\"ok\"}"
                    : "sda=/var/lib/libvirt/images/root;sdb=/var/lib/libvirt/images/data");
            Mockito.when(mock.getExitValue()).thenReturn(0);
        })) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            Script script = scripts.constructed().get(0);
            Mockito.verify(script).add("--disk-map", "sda=/var/lib/libvirt/images/root;sdb=/var/lib/libvirt/images/data");
        }
    }
}
