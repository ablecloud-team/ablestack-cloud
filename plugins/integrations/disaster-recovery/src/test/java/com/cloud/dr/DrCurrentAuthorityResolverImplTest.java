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

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrRunDao;

@RunWith(MockitoJUnitRunner.class)
public class DrCurrentAuthorityResolverImplTest {
    @Mock private DrCutoverSessionDao drCutoverSessionDao;
    @Mock private DrFailbackSessionDao drFailbackSessionDao;
    @Mock private DrRunDao drRunDao;
    @Mock private DrProtectionAuthorityService drProtectionAuthorityService;
    @InjectMocks private DrCurrentAuthorityResolverImpl resolver;

    @Test
    public void sourceAuthorityDoesNotExposeHistoricalPromotedCutover() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getId()).thenReturn(38L);
        Mockito.when(plan.getActiveSide()).thenReturn("SOURCE");
        Mockito.when(plan.getState()).thenReturn("READY");
        DrCutoverSessionVO cutover = Mockito.mock(DrCutoverSessionVO.class);
        Mockito.when(cutover.getCloudPromotionState()).thenReturn("PROMOTED");
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(38L)).thenReturn(cutover);

        DrCurrentAuthorityProjection projection = resolver.resolve(plan);

        Assert.assertEquals("SOURCE", projection.getAuthoritySide());
        Assert.assertEquals("READY", projection.getAuthorityPhase());
        Assert.assertNull(projection.getCurrentCutoverSession());
        Assert.assertFalse(projection.isConsistent());
        Assert.assertEquals(DrConstants.AUTHORITY_INCONSISTENT_STALE_CUTOVER,
                projection.getInconsistencyCode());
    }

    @Test
    public void targetAuthorityRequiresCommittedCurrentCutover() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getId()).thenReturn(38L);
        Mockito.when(plan.getActiveSide()).thenReturn("TARGET");
        DrCutoverSessionVO cutover = Mockito.mock(DrCutoverSessionVO.class);
        Mockito.when(cutover.getCloudPromotionState()).thenReturn("PROMOTED");
        Mockito.when(cutover.getEngineAckState()).thenReturn("ACKNOWLEDGED");
        Mockito.when(cutover.getCloudAuthorityGeneration()).thenReturn(27L);
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(38L)).thenReturn(cutover);

        DrCurrentAuthorityProjection projection = resolver.resolve(plan);

        Assert.assertTrue(projection.isConsistent());
        Assert.assertEquals("TARGET", projection.getAuthoritySide());
        Assert.assertEquals("FAILED_OVER_UNPROTECTED", projection.getAuthorityPhase());
        Assert.assertEquals(Long.valueOf(27L), projection.getAuthoritySequence());
        Assert.assertSame(cutover, projection.getCurrentCutoverSession());
    }

    @Test
    public void healthyReverseSchedulerProjectsTargetProtectedAuthority() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getId()).thenReturn(39L);
        Mockito.when(plan.getActiveSide()).thenReturn("TARGET");
        DrCutoverSessionVO cutover = Mockito.mock(DrCutoverSessionVO.class);
        Mockito.when(cutover.getCloudPromotionState()).thenReturn("PROMOTED");
        Mockito.when(cutover.getEngineAckState()).thenReturn("ACKNOWLEDGED");
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(39L)).thenReturn(cutover);
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO(39L);
        runtime.setProtectionState(DrConstants.PLAN_STATE_READY);
        runtime.setSchedulerState("RUNNING");
        runtime.setSchedulerHealthState("HEALTHY");
        runtime.setSchedulerPidAlive(true);
        runtime.setOwnerMatched(true);
        runtime.setBaselineState("LOCAL_DURABLE");
        Mockito.when(drProtectionAuthorityService.getAuthority(39L))
                .thenReturn(new DrProtectionAuthoritySnapshot(runtime, true));

        DrCurrentAuthorityProjection projection = resolver.resolve(plan);

        Assert.assertTrue(projection.isConsistent());
        Assert.assertEquals("TARGET", projection.getAuthoritySide());
        Assert.assertEquals("TARGET_PROTECTED", projection.getAuthorityPhase());
    }

    @Test
    public void sourceAuthorityRecognizesActiveFailbackConvergence() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getId()).thenReturn(38L);
        Mockito.when(plan.getActiveSide()).thenReturn("SOURCE");
        DrCutoverSessionVO cutover = Mockito.mock(DrCutoverSessionVO.class);
        Mockito.when(cutover.getCloudPromotionState()).thenReturn("PROMOTED");
        Mockito.when(drCutoverSessionDao.findCurrentAuthorityByPlanId(38L)).thenReturn(cutover);
        DrFailbackSessionVO failback = Mockito.mock(DrFailbackSessionVO.class);
        Mockito.when(failback.getState()).thenReturn("PROTECTION_RESUMING");
        Mockito.when(failback.getRunId()).thenReturn(106L);
        Mockito.when(failback.getRequiredPostFailbackCheckpointSequence()).thenReturn(1194L);
        Mockito.when(drFailbackSessionDao.findLatestActiveByPlanId(38L)).thenReturn(failback);
        DrRunVO run = Mockito.mock(DrRunVO.class);
        Mockito.when(run.getRunType()).thenReturn(DrConstants.RUN_TYPE_FAILBACK);
        Mockito.when(run.getUuid()).thenReturn("failback-run");
        Mockito.when(drRunDao.findById(106L)).thenReturn(run);

        DrCurrentAuthorityProjection projection = resolver.resolve(plan);

        Assert.assertTrue(projection.isConsistent());
        Assert.assertEquals("FAILBACK_PROTECTION_RESUMING", projection.getAuthorityPhase());
        Assert.assertEquals(DrConstants.RUN_TYPE_FAILBACK, projection.getTransitionType());
        Assert.assertEquals("failback-run", projection.getTransitionRunUuid());
        Assert.assertEquals(Long.valueOf(1194L), projection.getRequiredCheckpointSequence());
        Assert.assertNull(projection.getInconsistencyCode());
    }
}
