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
import org.junit.Before;
import org.junit.Test;

public class DrFailbackDataGateServiceImplTest {
    private DrFailbackDataGateServiceImpl service;
    private DrPlanVO plan;
    private DrRunVO run;
    private DrFailbackSessionVO session;

    @Before
    public void setUp() {
        service = new DrFailbackDataGateServiceImpl();
        plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_VMWARE_TO_KVM);
        run = new DrRunVO(plan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        session = new DrFailbackSessionVO(plan.getId(), 1L, "engine-session", "DATA_READY");
        session.setReplicationDirection(DrConstants.DIRECTION_KVM_TO_VMWARE);
        session.setProviderPair(DrConstants.PROVIDER_PAIR_ABLESTACK_TO_VMWARE);
        session.setBaselineGeneration(2L);
        session.setBaselineState("LOCAL_DURABLE");
        session.setTrackerState("LOCAL_DURABLE");
        session.setWriterState("DURABLE");
        session.setTargetWritten(true);
        session.setWriteVerified(true);
        session.setGuestCompatibilityState("ORIGINAL_VMWARE_COMPATIBILITY_PRESERVED");
    }

    @Test
    public void acceptsDurableVerifiedReverseCheckpoint() {
        Assert.assertTrue(service.validate(plan, run, session).isReady());
    }

    @Test
    public void blocksUnverifiedVmwareWriter() {
        session.setWriteVerified(false);
        DrFailbackDataGateResult result = service.validate(plan, run, session);
        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_FAILBACK_TARGET_WRITE_UNVERIFIED", result.getErrorCode());
    }

    @Test
    public void blocksForwardDirectionEvidence() {
        session.setReplicationDirection(DrConstants.DIRECTION_VMWARE_TO_KVM);
        DrFailbackDataGateResult result = service.validate(plan, run, session);
        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_FAILBACK_ROUTE_DIRECTION_INVALID", result.getErrorCode());
    }

    @Test
    public void blocksForwardProviderEvidence() {
        session.setProviderPair(DrConstants.PROVIDER_PAIR_VMWARE_TO_ABLESTACK);
        DrFailbackDataGateResult result = service.validate(plan, run, session);
        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_FAILBACK_ROUTE_PROVIDER_INVALID", result.getErrorCode());
    }

    @Test
    public void acceptsLegacyProviderStyleDirection() {
        session.setReplicationDirection(DrConstants.PROVIDER_PAIR_ABLESTACK_TO_VMWARE);
        Assert.assertTrue(service.validate(plan, run, session).isReady());
    }

    @Test
    public void blocksMissingGuestCompatibilityEvidence() {
        session.setGuestCompatibilityState(null);
        DrFailbackDataGateResult result = service.validate(plan, run, session);
        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_FAILBACK_DATA_EVIDENCE_INCOMPLETE", result.getErrorCode());
    }

    @Test
    public void acceptsNativeCompatibilityForAblestackToAblestackFailback() {
        DrPlanVO nativePlan = new DrPlanVO("native-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO nativeRun = new DrRunVO(nativePlan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        DrFailbackSessionVO nativeSession = durableSession(nativePlan.getId());
        nativeSession.setReplicationDirection(DrConstants.DIRECTION_KVM_TO_KVM);
        nativeSession.setProviderPair(DrConstants.PROVIDER_PAIR_ABLESTACK_TO_ABLESTACK);
        nativeSession.setGuestCompatibilityState("NATIVE_COMPATIBILITY_PRESERVED");

        Assert.assertTrue(service.validate(nativePlan, nativeRun, nativeSession).isReady());
    }

    @Test
    public void rejectsValidationRequiredForAblestackToAblestackFailback() {
        DrPlanVO nativePlan = new DrPlanVO("native-plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        DrRunVO nativeRun = new DrRunVO(nativePlan.getId(), DrConstants.RUN_TYPE_FAILBACK);
        DrFailbackSessionVO nativeSession = durableSession(nativePlan.getId());
        nativeSession.setReplicationDirection(DrConstants.DIRECTION_KVM_TO_KVM);
        nativeSession.setProviderPair(DrConstants.PROVIDER_PAIR_ABLESTACK_TO_ABLESTACK);
        nativeSession.setGuestCompatibilityState("VALIDATION_REQUIRED");

        DrFailbackDataGateResult result = service.validate(nativePlan, nativeRun, nativeSession);
        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_FAILBACK_GUEST_COMPATIBILITY_NOT_READY", result.getErrorCode());
    }

    @Test
    public void rejectsNativeKvmCompatibilityForVmwareFailback() {
        session.setGuestCompatibilityState("NATIVE_COMPATIBILITY_PRESERVED");
        DrFailbackDataGateResult result = service.validate(plan, run, session);
        Assert.assertFalse(result.isReady());
        Assert.assertEquals("DR_FAILBACK_GUEST_COMPATIBILITY_NOT_READY", result.getErrorCode());
    }

    @Test
    public void distinguishesMissingBaselineFromNonDurableBaseline() {
        session.setBaselineGeneration(null);
        Assert.assertEquals("DR_FAILBACK_DATA_EVIDENCE_INCOMPLETE",
                service.validate(plan, run, session).getErrorCode());

        session.setBaselineGeneration(2L);
        session.setBaselineState("INVALID");
        Assert.assertEquals("DR_FAILBACK_BASELINE_NOT_DURABLE",
                service.validate(plan, run, session).getErrorCode());
    }

    private DrFailbackSessionVO durableSession(Long planId) {
        DrFailbackSessionVO result = new DrFailbackSessionVO(planId, 1L, "engine-session-native", "DATA_READY");
        result.setBaselineGeneration(2L);
        result.setBaselineState("LOCAL_DURABLE");
        result.setTrackerState("LOCAL_DURABLE");
        result.setWriterState("DURABLE");
        result.setTargetWritten(true);
        result.setWriteVerified(true);
        return result;
    }
}
