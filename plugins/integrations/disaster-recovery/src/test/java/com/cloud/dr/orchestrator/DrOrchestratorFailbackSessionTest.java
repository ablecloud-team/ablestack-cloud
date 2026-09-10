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

package com.cloud.dr.orchestrator;

import java.util.Date;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dr.DrConstants;
import com.cloud.dr.DrFailbackSessionVO;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.DrRunVO;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.exception.InvalidParameterValueException;

@RunWith(MockitoJUnitRunner.class)
public class DrOrchestratorFailbackSessionTest {
    @Mock
    private DrRunDao drRunDao;
    @Mock
    private DrFailbackSessionDao drFailbackSessionDao;
    @InjectMocks
    private DrOrchestratorImpl orchestrator;

    @Test
    public void reconcilesLegacyArtifactFreeFailureBeforeCreatingNextSession() {
        DrPlanVO plan = plan(2L);
        DrRunVO failedRun = run(72L, DrConstants.RUN_STATE_FAILED, new Date());
        failedRun.setErrorCode(DrConstants.ERROR_ENGINE_ACTION_FAILED);
        failedRun.setErrorMessage("Mold API returned HTTP 401");
        DrFailbackSessionVO previous = requestedSession(plan, failedRun, 5L);
        DrRunVO nextRun = run(76L, DrConstants.RUN_STATE_QUEUED, null);
        Mockito.when(drFailbackSessionDao.findActiveByRunId(nextRun.getId())).thenReturn(null);
        Mockito.when(drFailbackSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(previous);
        Mockito.when(drRunDao.findById(failedRun.getId())).thenReturn(failedRun);

        ReflectionTestUtils.invokeMethod(orchestrator, "createRequestedFailbackSession",
                plan, nextRun, "{\"force\":false}");

        Assert.assertEquals("FAILED", previous.getState());
        Assert.assertEquals("REJECTED", previous.getAcceptanceState());
        Assert.assertNotNull(previous.getRemoved());
        Mockito.verify(drFailbackSessionDao).update(previous.getId(), previous);
        Mockito.verify(drFailbackSessionDao).persist(Mockito.argThat(session ->
                session.getRunId() == nextRun.getId() && "REQUESTED".equals(session.getState())));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void preservesCleanupGateForEngineAcceptedSession() {
        DrPlanVO plan = plan(2L);
        DrRunVO activeRun = run(72L, DrConstants.RUN_STATE_RUNNING, null);
        DrFailbackSessionVO previous = requestedSession(plan, activeRun, 5L);
        previous.setState("ENGINE_ACCEPTED");
        previous.setAcceptanceState("ACCEPTED");
        previous.setEngineAckState("ACKNOWLEDGED");
        DrRunVO nextRun = run(76L, DrConstants.RUN_STATE_QUEUED, null);
        Mockito.when(drFailbackSessionDao.findActiveByRunId(nextRun.getId())).thenReturn(null);
        Mockito.when(drFailbackSessionDao.findLatestActiveByPlanId(plan.getId())).thenReturn(previous);
        Mockito.when(drRunDao.findById(activeRun.getId())).thenReturn(activeRun);

        ReflectionTestUtils.invokeMethod(orchestrator, "createRequestedFailbackSession",
                plan, nextRun, "{\"force\":false}");
    }

    private DrPlanVO plan(long id) {
        DrPlanVO plan = new DrPlanVO("failback-plan", 13L, 31L, DrConstants.DIRECTION_KVM_TO_KVM);
        ReflectionTestUtils.setField(plan, "id", id);
        return plan;
    }

    private DrRunVO run(long id, String state, Date completed) {
        DrRunVO run = new DrRunVO(2L, DrConstants.RUN_TYPE_FAILBACK);
        ReflectionTestUtils.setField(run, "id", id);
        run.setState(state);
        run.setCompleted(completed);
        return run;
    }

    private DrFailbackSessionVO requestedSession(DrPlanVO plan, DrRunVO run, long id) {
        DrFailbackSessionVO session = new DrFailbackSessionVO(plan.getId(), run.getId(),
                plan.getUuid() + ":" + run.getUuid(), "REQUESTED");
        ReflectionTestUtils.setField(session, "id", id);
        session.setAcceptanceState("SUBMITTED");
        session.setEngineAckState("PENDING");
        session.setCommitOutcome("PENDING");
        session.setRollbackState("NONE");
        return session;
    }
}
