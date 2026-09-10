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

import java.util.Collections;
import java.util.HashMap;

import org.apache.cloudstack.api.response.dr.DrRunResponse;
import org.apache.cloudstack.api.response.dr.DrPlanResponse;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanViewCacheDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrRunStepDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrSyncCycleDao;
import com.cloud.dr.response.DrResponseGenerator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@RunWith(MockitoJUnitRunner.class)
public class DrProtectionViewServiceImplTest {
    private static final long PLAN_ID = 38L;

    @Mock private DrPlanDao drPlanDao;
    @Mock private DrPlanViewCacheDao drPlanViewCacheDao;
    @Mock private DrSiteDao drSiteDao;
    @Mock private DrRunDao drRunDao;
    @Mock private DrRunStepDao drRunStepDao;
    @Mock private DrReplicaDao drReplicaDao;
    @Mock private DrRestorePointDao drRestorePointDao;
    @Mock private DrEventDao drEventDao;
    @Mock private DrSyncCycleDao drSyncCycleDao;
    @Mock private DrFailbackSessionDao drFailbackSessionDao;
    @Mock private DrProjectionService drProjectionService;
    @Mock private DrProtectionAuthorityService drProtectionAuthorityService;
    @Mock private DrResponseGenerator drResponseGenerator;
    @Mock private DrPlanService drPlanService;

    @InjectMocks private DrProtectionViewServiceImpl service;

    @Test
    public void completedCleanupRemainsHistoryWhileProtectionRuntimeIsCurrent() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getSourceSiteId()).thenReturn(1L);
        Mockito.when(plan.getTargetSiteId()).thenReturn(2L);
        Mockito.when(drPlanDao.findById(PLAN_ID)).thenReturn(plan);
        HashMap<String, Boolean> eligibility = new HashMap<>();
        eligibility.put("testFailover", true);
        HashMap<String, DrActionAvailability> availability = new HashMap<>();
        availability.put("testFailover", new DrActionAvailability(true, true, null, Collections.emptyMap()));
        DrPlanActionEvaluation actionEvaluation = new DrPlanActionEvaluation(eligibility, availability);
        Mockito.when(drPlanService.getDatabaseActionEvaluation(PLAN_ID)).thenReturn(actionEvaluation);
        DrPlanResponse planResponse = new DrPlanResponse();
        planResponse.setId("plan-38");
        planResponse.setActionEligibility(eligibility);
        Mockito.when(drResponseGenerator.createPlanResponse(plan, actionEvaluation)).thenReturn(planResponse);

        DrRunVO cleanup = Mockito.mock(DrRunVO.class);
        Mockito.when(cleanup.getId()).thenReturn(84L);
        Mockito.when(cleanup.isEngineAccepted()).thenReturn(true);
        Mockito.when(drRunDao.findActiveByPlanId(PLAN_ID)).thenReturn(null);
        Mockito.when(drRunDao.findLatestByPlanId(PLAN_ID)).thenReturn(cleanup);
        Mockito.when(drRunStepDao.listActiveByRunId(84L)).thenReturn(Collections.emptyList());

        DrRunResponse cleanupResponse = new DrRunResponse();
        cleanupResponse.setId("c52eb0e1-65c0-4989-8067-b4eef6711d5d");
        cleanupResponse.setRunType("TEST_CLEANUP");
        cleanupResponse.setState("SUCCEEDED");
        Mockito.when(drResponseGenerator.createRunResponse(cleanup, Collections.emptyList(), true))
                .thenReturn(cleanupResponse);

        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(PLAN_ID);
        runtime.setRuntimeGeneration(380L);
        runtime.setAuthoritySequence(380L);
        runtime.setProtectionState("READY");
        runtime.setFreshnessState("WITHIN_RPO");
        runtime.setSchedulerState("RUNNING");
        runtime.setSchedulerHealthState("HEALTHY");
        runtime.setSchedulerPidAlive(true);
        runtime.setOwnerMatched(true);
        runtime.setReplicationActivityState("IDLE");
        runtime.setTransferCycleSequence(154L);
        runtime.setTransferPayloadBytes(1024L);
        runtime.setCurrentCycleSequence(189L);
        runtime.setCurrentCycleState("COMPLETED");
        runtime.setLatestCompletedCycleSequence(380L);
        runtime.setStatusJson("{\"control_protocol_version\":2,\"control_generation\":7,"
                + "\"control_ack_generation\":7,\"control_state\":\"ACKNOWLEDGED\"}");
        Mockito.when(drProtectionAuthorityService.getAuthority(PLAN_ID))
                .thenReturn(new DrProtectionAuthoritySnapshot(runtime, true));

        DrSyncCycleVO completedCycle = new DrSyncCycleVO(PLAN_ID, "producer-run", 380L);
        completedCycle.setCycleToken("plan-38:189");
        completedCycle.setState("READY");
        completedCycle.setRequestedMode("CBT_INCREMENTAL");
        completedCycle.setEffectiveMode("NO_CHANGE");
        completedCycle.setCommitState("LOCAL_DURABLE");
        completedCycle.setChangedBytes(0L);
        completedCycle.setSourceReadBytes(4096L);
        completedCycle.setTargetWrittenBytes(4096L);
        completedCycle.setTransferPayloadBytes(4096L);
        completedCycle.setIncrementalVerified(true);
        completedCycle.setNbdTeardownState("DRAINED");
        completedCycle.setNbdTeardownDurationMs(85L);
        runtime.setNbdTeardownState("DRAINED");
        DrSyncCycleVO staleActiveCycle = new DrSyncCycleVO(PLAN_ID, "producer-run", 154L);
        staleActiveCycle.setState("TRANSFERRING");
        Mockito.when(drSyncCycleDao.findActiveByPlanId(PLAN_ID)).thenReturn(staleActiveCycle);
        Mockito.when(drSyncCycleDao.findLatestCompletedByPlanId(PLAN_ID)).thenReturn(completedCycle);
        DrFailbackSessionVO completedFailback = new DrFailbackSessionVO(PLAN_ID, 84L,
                "failback-session", "COMPLETED");
        completedFailback.setFailurePhase("REVERSE_SYNC");
        completedFailback.setFailedComponent("ftctl");
        Mockito.when(drFailbackSessionDao.findLatestActiveByPlanId(PLAN_ID)).thenReturn(completedFailback);
        Mockito.when(drReplicaDao.listActiveByPlanId(PLAN_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drEventDao.listRecentByPlanId(PLAN_ID, 20, false)).thenReturn(Collections.emptyList());
        Mockito.when(drPlanViewCacheDao.persist(ArgumentMatchers.any(DrPlanViewCacheVO.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DrPlanViewCacheVO cache = service.rebuildProtectionView(PLAN_ID);
        JsonObject snapshot = JsonParser.parseString(cache.getSnapshotJson()).getAsJsonObject();

        Assert.assertEquals(11, cache.getSnapshotVersion());
        Assert.assertTrue(snapshot.has("planProjection"));
        Assert.assertTrue(snapshot.getAsJsonObject("planProjection")
                .getAsJsonObject("actioneligibility").get("testFailover").getAsBoolean());
        Assert.assertTrue(snapshot.get("activeRun").isJsonNull());
        Assert.assertEquals("TEST_CLEANUP",
                snapshot.getAsJsonObject("latestOperationRun").get("runtype").getAsString());
        Assert.assertEquals("RUNNING",
                snapshot.getAsJsonObject("currentProtectionRuntime").get("schedulerState").getAsString());
        Assert.assertEquals(380L,
                snapshot.getAsJsonObject("currentProtectionRuntime").get("authoritySequence").getAsLong());
        Assert.assertEquals(380L,
                snapshot.getAsJsonObject("currentProtectionRuntime").get("transferCycleSequence").getAsLong());
        Assert.assertEquals(4096L,
                snapshot.getAsJsonObject("currentProtectionRuntime").get("transferPayloadBytes").getAsLong());
        Assert.assertTrue(snapshot.getAsJsonObject("currentProtectionRuntime")
                .get("completedCycleProjected").getAsBoolean());
        Assert.assertEquals(189L,
                snapshot.getAsJsonObject("latestCompletedSyncCycle").get("sequence").getAsLong());
        Assert.assertEquals(380L,
                snapshot.getAsJsonObject("latestCompletedSyncCycle").get("canonicalSequence").getAsLong());
        Assert.assertTrue(snapshot.get("currentSyncCycle").isJsonNull());
        Assert.assertEquals(189L,
                snapshot.getAsJsonObject("latestCompletedSyncCycle").get("sequence").getAsLong());
        Assert.assertEquals("DRAINED",
                snapshot.getAsJsonObject("latestCompletedSyncCycle").get("nbdTeardownState").getAsString());
        Assert.assertEquals(85L,
                snapshot.getAsJsonObject("latestCompletedSyncCycle").get("nbdTeardownDurationMs").getAsLong());
        Assert.assertEquals("DRAINED",
                snapshot.getAsJsonObject("currentProtectionRuntime").get("nbdTeardownState").getAsString());
        Assert.assertFalse(snapshot.getAsJsonObject("failbackSession").has("failurePhase"));
        Assert.assertFalse(snapshot.getAsJsonObject("failbackSession").has("failedComponent"));
        Assert.assertFalse(snapshot.getAsJsonObject("failbackSession").has("errorCode"));
        Assert.assertFalse(snapshot.getAsJsonObject("failbackSession").has("errorMessage"));
        Assert.assertEquals(snapshot.get("latestOperationRun"), snapshot.get("latestRun"));
    }
}
