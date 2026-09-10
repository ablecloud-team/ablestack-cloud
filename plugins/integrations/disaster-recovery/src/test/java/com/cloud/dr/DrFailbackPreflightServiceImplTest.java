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

import java.util.Date;

import javax.inject.Provider;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter;
import com.cloud.agent.api.FtctlDrReversePreflightAnswer;

@RunWith(MockitoJUnitRunner.class)
public class DrFailbackPreflightServiceImplTest {
    @Mock private DrPlanDao drPlanDao;
    @Mock private DrSiteDao drSiteDao;
    @Mock private DrRestorePointDao drRestorePointDao;
    @Mock private DrSiteCredentialService drSiteCredentialService;
    @Mock private DrSourceIsolationPreflightService drSourceIsolationPreflightService;
    @Mock private DrCurrentAuthorityResolver drCurrentAuthorityResolver;
    @Mock private Provider<FtctlDrUnifiedActionAdapter> ftctlDrUnifiedActionAdapterProvider;
    @Mock private FtctlDrUnifiedActionAdapter ftctlDrUnifiedActionAdapter;
    @Mock private FtctlDrReversePreflightAnswer reversePreflightAnswer;

    @InjectMocks
    private DrFailbackPreflightServiceImpl service;

    private DrPlanVO plan;
    private DrSiteVO sourceSite;
    private DrSiteVO targetSite;
    private DrRestorePointVO checkpoint;

    @Before
    public void setUp() {
        plan = new DrPlanVO("failback-plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        plan.setState(DrConstants.PLAN_STATE_FAILED_OVER);
        plan.setActiveSide("TARGET");
        sourceSite = site("source-vcenter", "VMWARE_DIRECT", DrConstants.HYPERVISOR_TYPE_VMWARE);
        targetSite = site("target-mold", "MOLD_KVM", DrConstants.HYPERVISOR_TYPE_KVM);
        checkpoint = new DrRestorePointVO(plan.getId(), "FTCTL_DR_CHECKPOINT");
        checkpoint.setTargetReadyAt(new Date());

        Mockito.when(drPlanDao.findById(plan.getId())).thenReturn(plan);
        Mockito.when(drSiteDao.findById(1L)).thenReturn(sourceSite);
        Mockito.when(drSiteDao.findById(2L)).thenReturn(targetSite);
        Mockito.when(drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId())).thenReturn(checkpoint);
        Mockito.when(drSiteCredentialService.hasUsableCredential(sourceSite)).thenReturn(true);
        Mockito.when(drSiteCredentialService.hasUsableCredential(targetSite)).thenReturn(true);
        Mockito.when(drCurrentAuthorityResolver.resolve(plan)).thenReturn(authority("TARGET", true));
        Mockito.when(drSourceIsolationPreflightService.validate(
                Mockito.eq(plan), Mockito.isNull(), Mockito.eq(DrConstants.RUN_TYPE_FAILBACK)))
                .thenReturn(DrSourceIsolationPreflightResult.success(
                        DrConstants.RUN_TYPE_FAILBACK, 3L, "ACKNOWLEDGED",
                        "POWERED_OFF", "POWERED_ON", "{\"ready\":true}"));
        Mockito.when(ftctlDrUnifiedActionAdapterProvider.get()).thenReturn(ftctlDrUnifiedActionAdapter);
        Mockito.when(ftctlDrUnifiedActionAdapter.probeReversePreflight(plan, null))
                .thenReturn(reversePreflightAnswer);
        Mockito.when(reversePreflightAnswer.getResult()).thenReturn(true);
        Mockito.when(reversePreflightAnswer.getReady()).thenReturn(Boolean.TRUE);
        Mockito.when(reversePreflightAnswer.getOperationIntent()).thenReturn("FAILBACK_FINAL");
        Mockito.when(reversePreflightAnswer.getRequestedMode()).thenReturn("AUTO");
        Mockito.when(reversePreflightAnswer.getEffectiveMode()).thenReturn("FULL_REVERSE_SEED");
        Mockito.when(reversePreflightAnswer.getStatusEvidenceContractVersion()).thenReturn(1);
        Mockito.when(reversePreflightAnswer.getStatusEvidencePublicationReady()).thenReturn(Boolean.TRUE);
    }

    @Test
    public void derivesFailbackRouteFromRegisteredSites() {
        DrFailbackPreflightResult result = service.validate(plan.getId());

        Assert.assertTrue(result.isReady());
        Assert.assertSame(targetSite, result.getActiveSite());
        Assert.assertSame(sourceSite, result.getDestinationSite());
        Assert.assertSame(checkpoint, result.getCheckpoint());
    }

    @Test
    public void rejectsFailbackWhenTargetAuthorityIsNotCommitted() {
        plan.setActiveSide("SOURCE");
        Mockito.when(drCurrentAuthorityResolver.resolve(plan)).thenReturn(authority("SOURCE", true));

        DrFailbackPreflightResult result = service.validate(plan);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals(DrConstants.ERROR_FAILBACK_REQUIRES_TARGET_ACTIVE, result.getErrorCode());
    }

    @Test
    public void acceptsRetryWhenFailedOperationRetainsCommittedTargetAuthority() {
        plan.setState(DrConstants.PLAN_STATE_ERROR);

        DrFailbackPreflightResult result = service.validate(plan);

        Assert.assertTrue(result.isReady());
        Assert.assertSame(targetSite, result.getActiveSite());
    }

    @Test
    public void rejectsFailbackWhenDestinationCredentialIsMissing() {
        Mockito.when(drSiteCredentialService.hasUsableCredential(sourceSite)).thenReturn(false);

        DrFailbackPreflightResult result = service.validate(plan);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals(DrConstants.ERROR_FAILBACK_CREDENTIAL_NOT_READY, result.getErrorCode());
        Assert.assertEquals("MISSING", result.getDestinationCredentialState());
    }

    @Test
    public void propagatesInternalSourceIsolationFailure() {
        Mockito.when(drSourceIsolationPreflightService.validate(
                Mockito.eq(plan), Mockito.isNull(), Mockito.eq(DrConstants.RUN_TYPE_FAILBACK)))
                .thenReturn(DrSourceIsolationPreflightResult.failure(
                        DrConstants.ERROR_SOURCE_ISOLATION_NOT_READY,
                        "Source site is not isolated", DrConstants.RUN_TYPE_FAILBACK,
                        3L, "PENDING", "POWERED_ON", "POWERED_ON", null));

        DrFailbackPreflightResult result = service.validate(plan);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals(DrConstants.ERROR_SOURCE_ISOLATION_NOT_READY, result.getErrorCode());
        Assert.assertNotNull(result.getTransitionPreflight());
    }

    @Test
    public void blocksFailbackWhenHostCannotPublishDurableEvidence() {
        Mockito.when(reversePreflightAnswer.getStatusEvidencePublicationReady()).thenReturn(Boolean.FALSE);

        DrFailbackPreflightResult result = service.validate(plan);

        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_FAILBACK_DATA_EVIDENCE_CONTRACT_UNSUPPORTED", result.getErrorCode());
        Assert.assertEquals("REVERSE_DATA", result.getFailureStage());
    }

    private DrSiteVO site(String name, String siteType, String hypervisorType) {
        DrSiteVO site = new DrSiteVO(name, siteType, hypervisorType);
        site.setHealthState(DrConstants.HEALTH_CONNECTED);
        return site;
    }

    private DrCurrentAuthorityProjection authority(String side, boolean consistent) {
        return new DrCurrentAuthorityProjection(side, "FAILED_OVER_UNPROTECTED", 3L,
                consistent, null, null, null);
    }
}
