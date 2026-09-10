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

package com.cloud.dr.response;

import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.api.response.dr.DrPlanResponse;
import org.apache.cloudstack.api.response.dr.DrRunResponse;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.DrConstants;
import com.cloud.dr.DrActionAvailability;
import com.cloud.dr.DrPlanActionEvaluation;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.DrPlanRuntimeVO;
import com.cloud.dr.DrProtectionAuthorityService;
import com.cloud.dr.DrProtectionAuthoritySnapshot;
import com.cloud.dr.DrRunVO;
import com.cloud.dr.DrRunStepVO;
import com.cloud.dr.DrSyncCycleVO;
import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@RunWith(MockitoJUnitRunner.class)
public class DrResponseGeneratorTest {
    private static final Gson GSON = new Gson();

    @Mock private DrSiteDao drSiteDao;
    @Mock private DrPlanDao drPlanDao;
    @Mock private DrRunDao drRunDao;
    @Mock private DrTestSessionDao drTestSessionDao;
    @Mock private DrCutoverSessionDao drCutoverSessionDao;
    @Mock private DrSyncCycleDao drSyncCycleDao;
    @Mock private DrProtectionAuthorityService drProtectionAuthorityService;

    @InjectMocks
    private DrResponseGenerator generator;

    @Test
    public void failedReprotectRemainsInRunHistoryWithoutBecomingCurrentPlanError() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        DrRunVO run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_REPROTECT);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setErrorCode(DrConstants.ERROR_REPROTECT_TARGET_RUNTIME_NOT_RUNNING);
        run.setErrorMessage("Target VM was not running");
        run.setLastStatusJson("{\"state\":\"ERROR\",\"error_code\":\""
                + DrConstants.ERROR_REPROTECT_TARGET_RUNTIME_NOT_RUNNING + "\"}");

        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(run);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);

        DrPlanResponse response = generator.createPlanResponse(plan, Collections.emptyMap());
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();

        Assert.assertFalse(json.has("lasterrorcode"));
        Assert.assertFalse(json.has("lasterrormessage"));
        Assert.assertTrue(json.has("lastrun"));
        Assert.assertEquals(DrConstants.ERROR_REPROTECT_TARGET_RUNTIME_NOT_RUNNING,
                json.getAsJsonObject("lastrun").get("errorcode").getAsString());
    }

    @Test
    public void historicalFailedRunDoesNotBecomeCurrentRuntimeErrorWhenAuthorityIsReady() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("SOURCE");
        DrRunVO historicalRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_TEST_FAILOVER);
        historicalRun.setState(DrConstants.RUN_STATE_FAILED);
        historicalRun.setErrorCode("DR_GUEST_OS_UNSUPPORTED");
        historicalRun.setErrorMessage("Guest OS was unsupported");
        historicalRun.setLastStatusJson("{\"state\":\"ERROR\",\"error_code\":\"DR_GUEST_OS_UNSUPPORTED\"}");

        DrPlanRuntimeVO runtime = readyRuntime(plan.getId());
        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(historicalRun);
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(null);
        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId()))
                .thenReturn(new DrProtectionAuthoritySnapshot(runtime, true));
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);

        DrPlanResponse response = generator.createPlanResponse(plan, Collections.emptyMap());
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();

        Assert.assertEquals(DrConstants.PLAN_STATE_READY, json.get("effectivestate").getAsString());
        Assert.assertEquals(DrConstants.PLAN_STATE_READY, json.get("protectionstate").getAsString());
        Assert.assertFalse(json.has("runtimeerrorcode"));
        Assert.assertFalse(json.has("runtimeprojectionmessage"));
        Assert.assertFalse(json.has("lasterrorcode"));
        Assert.assertFalse(json.has("lasterrormessage"));
        Assert.assertEquals(DrConstants.RUN_STATE_FAILED,
                json.getAsJsonObject("lastrun").get("state").getAsString());
        Assert.assertEquals("DR_GUEST_OS_UNSUPPORTED",
                json.getAsJsonObject("lastrun").get("errorcode").getAsString());
    }

    @Test
    public void currentAuthorityFailureRemainsVisibleAsCurrentError() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_ERROR);
        plan.setActiveSide("SOURCE");
        DrPlanRuntimeVO runtime = readyRuntime(plan.getId());
        runtime.setProtectionState(DrConstants.PLAN_STATE_ERROR);
        runtime.setErrorCode("DR_CURRENT_RUNTIME_FAILED");
        runtime.setErrorMessage("Current runtime failed");
        runtime.setStatusJson("{\"state\":\"ERROR\",\"error_code\":\"DR_CURRENT_RUNTIME_FAILED\","
                + "\"error_message\":\"Current runtime failed\"}");

        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId()))
                .thenReturn(new DrProtectionAuthoritySnapshot(runtime, false));

        DrPlanResponse response = generator.createPlanResponse(plan, Collections.emptyMap());
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();

        Assert.assertEquals(DrConstants.PLAN_STATE_ERROR, json.get("effectivestate").getAsString());
        Assert.assertEquals("DR_CURRENT_RUNTIME_FAILED", json.get("runtimeerrorcode").getAsString());
        Assert.assertEquals("Current runtime failed", json.get("runtimeprojectionmessage").getAsString());
        Assert.assertEquals("DR_CURRENT_RUNTIME_FAILED", json.get("lasterrorcode").getAsString());
        Assert.assertEquals("Current runtime failed", json.get("lasterrormessage").getAsString());
    }

    @Test
    public void activeRunProvidesCurrentRuntimeWhenAuthorityIsUnavailable() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_SYNCING);
        DrRunVO activeRun = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_SYNC);
        activeRun.setState(DrConstants.RUN_STATE_RUNNING);
        activeRun.setLastStatusJson("{\"state\":\"SYNCING\",\"step\":\"incremental-transfer\","
                + "\"control_protocol_version\":4,\"control_generation\":8,\"control_ack_generation\":8,"
                + "\"cbt_status\":{\"enabled\":false,\"lifecycleState\":\"CONFIGURED_PENDING_ACTIVATION\","
                + "\"vmConfigSignal\":\"FALSE\",\"disks\":[{\"cbtDiskId\":\"scsi0:0\"}]}}" );

        Mockito.when(drRunDao.findLatestByPlanId(plan.getId())).thenReturn(activeRun);
        Mockito.when(drRunDao.findActiveByPlanId(plan.getId())).thenReturn(activeRun);
        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId())).thenReturn(null);
        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);

        DrPlanResponse response = generator.createPlanResponse(plan, Collections.emptyMap());
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();

        Assert.assertEquals("SYNCING", json.get("runtimestate").getAsString());
        Assert.assertEquals("incremental-transfer", json.get("runtimestep").getAsString());
        Assert.assertTrue(json.get("runtimecontrolready").getAsBoolean());
        Assert.assertTrue(json.get("initialsyncinprogress").getAsBoolean());
        Assert.assertEquals("CONFIGURED_PENDING_ACTIVATION", json.get("runtimecbtlifecyclestate").getAsString());
        Assert.assertEquals("FALSE", json.get("runtimecbtvmconfigsignal").getAsString());
        Assert.assertEquals("scsi0:0", json.get("runtimecbtdiskid").getAsString());
        Assert.assertFalse(json.get("runtimecbtenabled").getAsBoolean());
        Assert.assertFalse(json.has("runtimeerrorcode"));
    }

    @Test
    public void remoteSourceWorkerAuthorityIsProjectedFromMapping() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setMappingJson("{\"source\":{\"hardware\":{"
                + "\"sourceHostUuid\":\"source-host-uuid\","
                + "\"sourceHostName\":\"ablecube13-1\"}}}");

        DrPlanResponse response = generator.createPlanResponse(plan, Collections.emptyMap());
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();

        Assert.assertFalse(json.has("sourceworkerhostid"));
        Assert.assertEquals("source-host-uuid", json.get("sourceworkerhostuuid").getAsString());
        Assert.assertEquals("ablecube13-1", json.get("sourceworkerhostname").getAsString());
    }

    @Test
    public void typedActionAvailabilityIsSerializedWithLegacyEligibility() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_READY);
        plan.setActiveSide("SOURCE");
        Map<String, Boolean> eligibility = new LinkedHashMap<String, Boolean>();
        eligibility.put("testFailover", false);
        Map<String, DrActionAvailability> availability =
                new LinkedHashMap<String, DrActionAvailability>();
        availability.put("testFailover", new DrActionAvailability(true, false,
                "DR_ACTION_TARGET_NOT_READY", Collections.emptyMap()));

        DrPlanResponse response = generator.createPlanResponse(plan,
                new DrPlanActionEvaluation(eligibility, availability));
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();

        Assert.assertFalse(json.getAsJsonObject("actioneligibility")
                .get("testFailover").getAsBoolean());
        JsonObject testFailover = json.getAsJsonObject("actionavailability")
                .getAsJsonObject("testFailover");
        Assert.assertTrue(testFailover.get("applicable").getAsBoolean());
        Assert.assertFalse(testFailover.get("enabled").getAsBoolean());
        Assert.assertEquals("DR_ACTION_TARGET_NOT_READY",
                testFailover.get("reasoncode").getAsString());
    }

    @Test
    public void liveTransferRaisesWholeOperationProgressAbovePhaseLocalValue() {
        DrRunVO run = new DrRunVO(42L, DrConstants.RUN_TYPE_SYNC);
        run.setState(DrConstants.RUN_STATE_RUNNING);
        run.setLastStatusJson("{\"state\":\"SYNCING\",\"step\":\"full-reseed-transfer\"," +
                "\"transfer_progress_schema_version\":2,\"transfer_percent\":22," +
                "\"transfer_bytes_processed\":220,\"transfer_bytes_total\":1000}");
        DrRunStepVO runtimeStep = new DrRunStepVO(run.getId(), "runtime-projection", 30);
        runtimeStep.setState(DrConstants.STEP_STATE_RUNNING);
        runtimeStep.setProgress(1);

        DrRunResponse response = generator.createRunResponse(run, Collections.singletonList(runtimeStep), true);
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();

        Assert.assertEquals(76, json.get("progresspercent").getAsInt());
        Assert.assertEquals(22D, json.get("transferpercent").getAsDouble(), 0D);
    }

    @Test
    public void malformedVerboseRuntimeEvidenceCannotInvalidateRunListJson() {
        DrRunVO run = new DrRunVO(42L, DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_FAILED);
        run.setErrorCode(DrConstants.ERROR_TEST_CHECKPOINT_GUEST_FS_INCONSISTENT);
        run.setErrorMessage("All checkpoint disks could not be inspected together <?xml version=\\\"1.0\\\"?> <operatingsystems>");
        DrRunStepVO runtimeStep = new DrRunStepVO(run.getId(), "runtime-projection", 30);
        runtimeStep.setState(DrConstants.STEP_STATE_FAILED);
        runtimeStep.setErrorCode(DrConstants.ERROR_TEST_CHECKPOINT_GUEST_FS_INCONSISTENT);
        runtimeStep.setErrorMessage(run.getErrorMessage());
        runtimeStep.setDetailsJson("{\\\"error_message\\\":\\\"mount failed \\\\<?xml version=\\\\\\\"1.0\\\\\\\"?>\\\"}");

        DrRunResponse response = generator.createRunResponse(run, Collections.singletonList(runtimeStep), false);
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();
        JsonObject step = json.getAsJsonArray("steps").get(0).getAsJsonObject();
        JsonObject safeDetails = JsonParser.parseString(step.get("details").getAsString()).getAsJsonObject();

        Assert.assertEquals("All checkpoint disks could not be inspected together",
                json.get("errormessage").getAsString());
        Assert.assertEquals("All checkpoint disks could not be inspected together",
                step.get("errormessage").getAsString());
        Assert.assertTrue(safeDetails.get("rawDetailsRedacted").getAsBoolean());
        Assert.assertTrue(safeDetails.get("parseError").getAsBoolean());
        Assert.assertFalse(step.get("details").getAsString().contains("operatingsystems"));
    }

    @Test
    public void idlePlanListUsesMatchingLatestCompletedCycleInsteadOfStaleRuntimeSample() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_READY);
        DrPlanRuntimeVO runtime = readyRuntime(plan.getId());
        runtime.setReplicationActivityState("IDLE");
        runtime.setLatestCompletedCycleSequence(477L);
        runtime.setTransferCycleSequence(352L);
        runtime.setTransferMode("FULL_RESEED");
        runtime.setTransferBytesTotal(107374182400L);
        runtime.setTransferPayloadBytes(107374182400L);
        runtime.setSchedulerNextRunAt(new Date(1776113000000L));
        runtime.setSchedulerExecutionBudgetSeconds(40);
        runtime.setSchedulerCycleWallDurationSeconds(32);

        DrSyncCycleVO cycle = new DrSyncCycleVO(plan.getId(), "run-477", 688L);
        cycle.setCycleToken(plan.getUuid() + ":477");
        cycle.setEffectiveMode("CBT_INCREMENTAL");
        cycle.setVirtualBytes(107374182400L);
        cycle.setSourceReadBytes(8949399552L);
        cycle.setTargetWrittenBytes(8949399552L);
        cycle.setTransferPayloadBytes(8949399552L);
        cycle.setThroughputBps(208246644L);
        cycle.setMetricsEstimated(false);
        cycle.setCompleted(new Date(1776112929000L));

        Mockito.when(drProtectionAuthorityService.getAuthority(plan.getId()))
                .thenReturn(new DrProtectionAuthoritySnapshot(runtime, true));
        Mockito.when(drSyncCycleDao.findLatestCompletedByPlanId(plan.getId())).thenReturn(cycle);

        DrPlanResponse response = generator.createPlanResponse(plan, Collections.emptyMap());
        JsonObject json = JsonParser.parseString(GSON.toJson(response)).getAsJsonObject();

        Assert.assertEquals(477L, json.get("transfercyclesequence").getAsLong());
        Assert.assertEquals("CBT_INCREMENTAL", json.get("transfermode").getAsString());
        Assert.assertEquals(8949399552L, json.get("transferpayloadbytes").getAsLong());
        Assert.assertEquals(8949399552L, json.get("transfersourcereadbytes").getAsLong());
        Assert.assertEquals(100D, json.get("transferpercent").getAsDouble(), 0D);
        Assert.assertEquals("COMPLETED", json.get("transferphase").getAsString());
        Assert.assertTrue(json.has("schedulernextrunat"));
        Assert.assertEquals(40, json.get("schedulerexecutionbudgetseconds").getAsInt());
        Assert.assertEquals(32, json.get("schedulercyclewalldurationseconds").getAsInt());
    }

    private DrPlanRuntimeVO readyRuntime(long planId) {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(planId);
        runtime.setProtectionState(DrConstants.PLAN_STATE_READY);
        runtime.setFreshnessState("WITHIN_RPO");
        runtime.setSchedulerState("RUNNING");
        runtime.setSchedulerHealthState("HEALTHY");
        runtime.setStatusJson("{\"state\":\"READY\",\"step\":\"target-checkpoint-ready\","
                + "\"control_protocol_version\":4,\"control_generation\":31,\"control_ack_generation\":31}");
        return runtime;
    }
}
