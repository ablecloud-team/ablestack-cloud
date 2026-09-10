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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

public class DrPlanActionAvailabilityEvaluatorTest {
    private final DrPlanActionAvailabilityEvaluator evaluator =
            new DrPlanActionAvailabilityEvaluator();

    @Test
    public void readySourceHidesOppositeStateActionsAndExplainsTargetReadiness() {
        DrPlanActionAvailabilityContext context = baseContext();
        context.sourceAuthority = true;
        context.targetReady = false;
        context.normalCutoverReady = false;
        Map<String, Boolean> eligibility = eligibility();
        eligibility.put("sync", true);
        eligibility.put("testFailover", false);
        eligibility.put("failover", false);

        Map<String, DrActionAvailability> result = evaluator.evaluate(eligibility, context);

        Assert.assertTrue(result.get("sync").isApplicable());
        Assert.assertTrue(result.get("sync").isEnabled());
        Assert.assertTrue(result.get("testFailover").isApplicable());
        Assert.assertFalse(result.get("testFailover").isEnabled());
        Assert.assertEquals(DrPlanActionAvailabilityEvaluator.TARGET_NOT_READY,
                result.get("testFailover").getReasonCode());
        Assert.assertFalse(result.get("failback").isApplicable());
        Assert.assertFalse(result.get("stopTestFailover").isApplicable());
    }

    @Test
    public void targetAuthorityShowsFailbackAndReprotectOnlyForCommittedTarget() {
        DrPlanActionAvailabilityContext context = baseContext();
        context.sourceAuthority = false;
        context.targetAuthority = true;
        context.failedOver = true;
        context.committedTargetAuthority = false;
        Map<String, Boolean> eligibility = eligibility();
        eligibility.put("failback", true);
        eligibility.put("reprotect", false);

        Map<String, DrActionAvailability> result = evaluator.evaluate(eligibility, context);

        Assert.assertFalse(result.get("sync").isApplicable());
        Assert.assertTrue(result.get("failback").isApplicable());
        Assert.assertTrue(result.get("failback").isEnabled());
        Assert.assertTrue(result.get("reprotect").isApplicable());
        Assert.assertEquals(DrPlanActionAvailabilityEvaluator.COMMITTED_TARGET_REQUIRED,
                result.get("reprotect").getReasonCode());
    }

    @Test
    public void activeRunShowsCancelAndDisablesOtherApplicableActions() {
        DrPlanActionAvailabilityContext context = baseContext();
        context.sourceAuthority = true;
        context.activeRun = true;
        Map<String, Boolean> eligibility = eligibility();
        eligibility.put("cancelRun", true);

        Map<String, DrActionAvailability> result = evaluator.evaluate(eligibility, context);

        Assert.assertTrue(result.get("cancelRun").isApplicable());
        Assert.assertTrue(result.get("cancelRun").isEnabled());
        Assert.assertEquals(DrPlanActionAvailabilityEvaluator.ACTIVE_RUN,
                result.get("sync").getReasonCode());
    }

    @Test
    public void runtimeReconciliationBlocksMutationsWithTypedReason() {
        DrPlanActionAvailabilityContext context = baseContext();
        context.sourceAuthority = true;
        context.runtimeReconciliationRequired = true;
        Map<String, Boolean> eligibility = eligibility();

        Map<String, DrActionAvailability> result = evaluator.evaluate(eligibility, context);

        Assert.assertEquals(DrPlanActionAvailabilityEvaluator.RUNTIME_RECONCILIATION_REQUIRED,
                result.get("sync").getReasonCode());
        Assert.assertEquals(DrPlanActionAvailabilityEvaluator.RUNTIME_RECONCILIATION_REQUIRED,
                result.get("update").getReasonCode());
    }

    @Test
    public void capabilityMismatchBlocksApplicableActionBeforeExecution() {
        DrPlanActionAvailabilityContext context = baseContext();
        context.targetAuthority = true;
        context.failedOver = true;
        context.committedTargetAuthority = true;
        context.capabilityBlockingReasons = Collections.singletonMap("reprotect",
                DrFtctlActionCapabilityServiceImpl.REPROTECT_CONTRACT_UNSUPPORTED);
        context.capabilityReasonArgs = Collections.singletonMap("reprotect",
                Collections.singletonMap("requiredVersion", "current"));
        Map<String, Boolean> eligibility = eligibility();
        eligibility.put("reprotect", false);

        DrActionAvailability availability = evaluator.evaluate(eligibility, context).get("reprotect");

        Assert.assertTrue(availability.isApplicable());
        Assert.assertFalse(availability.isEnabled());
        Assert.assertEquals(DrFtctlActionCapabilityServiceImpl.REPROTECT_CONTRACT_UNSUPPORTED,
                availability.getReasonCode());
        Assert.assertEquals("current", availability.getReasonArgs().get("requiredVersion"));
    }

    private DrPlanActionAvailabilityContext baseContext() {
        DrPlanActionAvailabilityContext context = new DrPlanActionAvailabilityContext();
        context.planEnabled = true;
        context.hasEngine = true;
        context.ftctlDrPlan = true;
        context.ftctlControlReady = true;
        context.syncPausable = true;
        context.ftctlReleaseReady = false;
        return context;
    }

    private Map<String, Boolean> eligibility() {
        Map<String, Boolean> eligibility = new LinkedHashMap<String, Boolean>();
        for (String action : new String[] {
                "update", "delete", "sync", "recoverSync", "pauseSync", "resumeSync",
                "testFailover", "stopTestFailover", "failover", "confirmFenceClear",
                "failback", "reprotect", "adoptReplica", "releaseProtection", "cancelRun" }) {
            eligibility.put(action, false);
        }
        return eligibility;
    }
}
