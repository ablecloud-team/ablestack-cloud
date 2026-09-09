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

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Date;
import javax.inject.Provider;
import org.junit.Before;
import org.junit.Test;
import org.apache.commons.lang3.reflect.FieldUtils;
import com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrTestSessionDao;

public class DrTestCleanupRecoveryServiceTest {
    private DrTestCleanupRecoveryService service;
    private DrTestCleanupRecoveryStore store;
    private DrPlanDao plans;
    private DrRunDao runs;
    private DrTestSessionDao sessions;
    private FtctlDrUnifiedActionAdapter adapter;
    private DrTestCleanupRecoveryStore.Intent intent;
    private DrPlanVO plan;
    private DrRunVO cleanup;
    @Before public void setup() throws Exception {
        service=new DrTestCleanupRecoveryService(); store=mock(DrTestCleanupRecoveryStore.class);
        plans=mock(DrPlanDao.class); runs=mock(DrRunDao.class); sessions=mock(DrTestSessionDao.class);
        adapter=mock(FtctlDrUnifiedActionAdapter.class);
        FieldUtils.writeField(service,"store",store,true); FieldUtils.writeField(service,"plans",plans,true);
        FieldUtils.writeField(service,"runs",runs,true); FieldUtils.writeField(service,"sessions",sessions,true);
        Provider<FtctlDrUnifiedActionAdapter> provider=()->adapter;
        FieldUtils.writeField(service,"adapter",provider,true);
        intent=new DrTestCleanupRecoveryStore.Intent();intent.planId=1;intent.testRunId=5;intent.cleanupRunId=6L;intent.desiredState="RUNNING";
        plan=mock(DrPlanVO.class);when(plan.getId()).thenReturn(1L);when(plan.getActiveSide()).thenReturn("SOURCE");when(plan.getState()).thenReturn("READY");
        cleanup=mock(DrRunVO.class);when(cleanup.getId()).thenReturn(6L);when(cleanup.getCompleted()).thenReturn(new Date());
        when(plans.findById(1L)).thenReturn(plan);when(runs.findById(6L)).thenReturn(cleanup);when(runs.findLatestByPlanId(1L)).thenReturn(cleanup);
        when(store.claim(eq(5L),anyString())).thenReturn(true);
    }
    @Test public void runningRestoresAfterCleanupRunCompleted() {
        service.restore(intent);verify(adapter).restoreTestCheckpointProtection(plan,cleanup);
        verify(store).finish(eq(5L),anyString(),eq("RESTORED"),isNull());
    }
    @Test public void originallyPausedNeverStartsExportOrResumes() {
        intent.desiredState="PAUSED";service.restore(intent);verifyNoInteractions(adapter);
        verify(store).finish(eq(5L),anyString(),eq("RESTORED"),isNull());
    }
    @Test public void unavailableSourceRemainsRetryable() {
        doThrow(new IllegalStateException("source unavailable")).when(adapter).restoreTestCheckpointProtection(plan,cleanup);
        service.restore(intent);verify(store).finish(eq(5L),anyString(),eq("PENDING"),eq("source unavailable"));
    }
    @Test public void testWriterStillPresentBlocksRestore() {
        DrTestSessionVO session=mock(DrTestSessionVO.class);when(session.isCleanupRequired()).thenReturn(true);
        when(sessions.findActiveByPlanId(1L)).thenReturn(session);service.restore(intent);verifyNoInteractions(adapter);
        verify(store).finish(eq(5L),anyString(),eq("PENDING"),isNull());
    }
    @Test public void acceptedCleanupIsNotEnough() {
        when(cleanup.getCompleted()).thenReturn(null);service.restore(intent);verifyNoInteractions(adapter);
    }
    @Test public void newerOperatorPauseWins() {
        DrRunVO newer=mock(DrRunVO.class);when(newer.getId()).thenReturn(7L);when(newer.getRunType()).thenReturn("PAUSE_SYNC");
        when(runs.findLatestByPlanId(1L)).thenReturn(newer);service.restore(intent);verifyNoInteractions(adapter);
        verify(store).finish(eq(5L),anyString(),eq("SUPERSEDED"),isNull());
    }
    @Test public void targetAuthorityNeverResumesSource() {
        when(plan.getActiveSide()).thenReturn("TARGET");service.restore(intent);verifyNoInteractions(adapter);
        verify(store).finish(eq(5L),anyString(),eq("SUPERSEDED"),isNull());
    }
    @Test public void leaseBlocksDuplicateWorker() {
        when(store.claim(eq(5L),anyString())).thenReturn(false);service.restore(intent);verifyNoInteractions(adapter,plans,runs);
    }
}
