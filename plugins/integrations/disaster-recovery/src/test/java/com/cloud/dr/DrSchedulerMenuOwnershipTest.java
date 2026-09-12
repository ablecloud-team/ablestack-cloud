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

public class DrSchedulerMenuOwnershipTest {
    private DrPlanRuntimeVO runtime() {
        DrPlanRuntimeVO runtime = new DrPlanRuntimeVO();
        runtime.setOwnedProcessCount(1);
        runtime.setReconciliationState("LIVE");
        runtime.setSchedulerState("RUNNING");
        runtime.setSchedulerUnitActiveState("active");
        runtime.setWorkerIdentityState("MATCHED");
        runtime.setWorkerLivenessState("ALIVE");
        runtime.setSchedulerUnitMainPid(100L);
        runtime.setActiveWorkerPid(100L);
        runtime.setStatusJson("{\"worker_pid\":100,\"reconciliation_required\":false}");
        return runtime;
    }
    @Test public void liveSchedulerIsNotAnOrphan() {
        Assert.assertTrue(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(runtime()));
    }
    @Test public void projectedStatusWithoutRawReconciliationFlagUsesLiveState() {
        DrPlanRuntimeVO r = runtime(); r.setStatusJson("{\"worker_pid\":100}");
        Assert.assertTrue(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
        r.setReconciliationState("DEAD_CONFIRMING");
        Assert.assertFalse(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
    }
    @Test public void otherOwnedProcessRemainsBlocked() {
        DrPlanRuntimeVO r = runtime(); r.setOwnedProcessCount(2);
        Assert.assertFalse(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
        r = runtime(); r.setStatusJson("{\"worker_pid\":101,\"reconciliation_required\":false}");
        Assert.assertFalse(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
    }
    @Test public void staleOrUncertainIdentityRemainsBlocked() {
        DrPlanRuntimeVO r = runtime(); r.setActiveWorkerPid(99L);
        Assert.assertFalse(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
        r = runtime(); r.setWorkerIdentityState("CONFLICT");
        Assert.assertFalse(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
        r = runtime(); r.setStatusJson("{\"worker_pid\":100,\"reconciliation_required\":true}");
        Assert.assertFalse(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
    }
    @Test public void missingEvidenceRemainsBlocked() {
        DrPlanRuntimeVO r = runtime(); r.setStatusJson("{}");
        Assert.assertFalse(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
        r.setStatusJson("invalid");
        Assert.assertFalse(DrPlanServiceImpl.isOnlyLiveReplicationScheduler(r));
    }
}
