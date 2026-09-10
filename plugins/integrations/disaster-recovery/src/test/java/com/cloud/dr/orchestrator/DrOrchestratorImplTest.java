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

import org.junit.Assert;
import org.junit.Test;

import com.cloud.dr.DrConstants;
import com.cloud.dr.DrRunVO;
import com.cloud.exception.InvalidParameterValueException;

public class DrOrchestratorImplTest {
    private final DrOrchestratorImpl orchestrator = new DrOrchestratorImpl();

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsInlineFailbackCredentialsBeforePersistence() {
        orchestrator.validateRequestContainsNoSecrets(DrConstants.RUN_TYPE_FAILBACK,
                "{\"force\":true,\"remoteMoldSecretKey\":\"secret\"}");
    }

    @Test
    public void acceptsOperatorOnlyFailbackIntent() {
        orchestrator.validateRequestContainsNoSecrets(DrConstants.RUN_TYPE_FAILBACK,
                "{\"force\":true,\"reason\":\"planned return\"}");
    }

    @Test
    public void leavesLegacyFenceRequestValidationUnchanged() {
        orchestrator.validateRequestContainsNoSecrets(DrConstants.RUN_TYPE_FENCE_CONFIRM,
                "{\"remoteMoldSecretKey\":\"legacy-fence-secret\"}");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsIdempotencyKeyReusedForAnotherAction() {
        DrRunVO existing = new DrRunVO(1L, DrConstants.RUN_TYPE_TEST_FAILOVER);
        existing.setRequestJson("{\"actionIntent\":\"TEST_FAILOVER\"}");
        orchestrator.validateIdempotentRun(existing, DrConstants.RUN_TYPE_FAILOVER,
                "{\"actionIntent\":\"FAILOVER\"}");
    }

    @Test
    public void acceptsIdempotentRetryForSameAction() {
        DrRunVO existing = new DrRunVO(1L, DrConstants.RUN_TYPE_TEST_FAILOVER);
        existing.setRequestJson("{\"actionIntent\":\"TEST_FAILOVER\"}");
        orchestrator.validateIdempotentRun(existing, DrConstants.RUN_TYPE_TEST_FAILOVER,
                "{\"actionIntent\":\"TEST_FAILOVER\"}");
    }

    @Test
    public void cancellationRequestedRunIsRedispatchable() {
        Assert.assertTrue(orchestrator.isExecutorDispatchableState(DrConstants.RUN_STATE_CANCEL_REQUESTED));
        Assert.assertTrue(orchestrator.isExecutorDispatchableState(DrConstants.RUN_STATE_QUEUED));
        Assert.assertFalse(orchestrator.isExecutorDispatchableState(DrConstants.RUN_STATE_RUNNING));
    }
}
