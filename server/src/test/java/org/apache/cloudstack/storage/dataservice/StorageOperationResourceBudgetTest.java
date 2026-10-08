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
package org.apache.cloudstack.storage.dataservice;

import org.junit.Assert;
import org.junit.Test;
import com.google.gson.JsonObject;

public class StorageOperationResourceBudgetTest {
    private static final long MIB = 1024L * 1024;

    @Test
    public void materializedPayloadAndRetainedArtifactsIncreaseBudget() {
        JsonObject small = StorageOperationResourceBudget.estimate(StorageOperationResourceBudget.Work.RESTORE,
                MIB, 2 * MIB, MIB, 1, false).request();
        JsonObject larger = StorageOperationResourceBudget.estimate(StorageOperationResourceBudget.Work.RESTORE,
                2 * MIB, 4 * MIB, 2 * MIB, 2, false).request();
        Assert.assertTrue(larger.get("minimumMemoryAvailableBytes").getAsLong() > small.get("minimumMemoryAvailableBytes").getAsLong());
        Assert.assertTrue(larger.get("stagingRequiredBytes").getAsLong() > small.get("stagingRequiredBytes").getAsLong());
    }

    @Test
    public void filesystemFormattingUsesBoundedHeadroomWithoutVirtualDiskSize() {
        JsonObject result = StorageOperationResourceBudget.estimate(StorageOperationResourceBudget.Work.FILESYSTEM_FORMAT,
                0, 0, 0, 0, false).request();
        Assert.assertEquals(1024 * MIB, result.get("minimumMemoryAvailableBytes").getAsLong());
        Assert.assertEquals(128 * MIB, result.get("stagingRequiredBytes").getAsLong());
    }

    @Test
    public void unknownObservationsDoNotReportReadinessOrAReservation() {
        JsonObject result = StorageOperationResourceBudget.estimate(StorageOperationResourceBudget.Work.ROOT_UPGRADE,
                0, 0, 0, 0, false).assess(null, null, null, null, null);
        Assert.assertFalse(result.get("compatible").getAsBoolean());
        Assert.assertFalse(result.get("reservationAcquired").getAsBoolean());
        Assert.assertEquals(4, result.getAsJsonArray("blockers").size());
    }

    @Test
    public void resourceAndSessionPressureMustBeResolvedBeforeRestart() {
        StorageOperationResourceBudget budget = StorageOperationResourceBudget.estimate(
                StorageOperationResourceBudget.Work.RUNTIME_UPGRADE, 0, 0, 0, 0, true);
        JsonObject result = budget.assess(1L, 1L, 5.0, 2, 3L);
        Assert.assertEquals(4, result.getAsJsonArray("blockers").size());
        Assert.assertTrue(budget.assess(4 * 1024 * MIB, 4 * 1024 * MIB, 0.5, 2, 0L)
                .get("compatible").getAsBoolean());
    }

    @Test
    public void nonRestartReadinessDoesNotGuessUnknownSessions() {
        JsonObject result = StorageOperationResourceBudget.estimate(StorageOperationResourceBudget.Work.BACKUP,
                0, 0, 0, 0, false).assess(4 * 1024 * MIB, 4 * 1024 * MIB, 0.0, 2, null);
        Assert.assertTrue(result.get("compatible").getAsBoolean());
    }

    @Test
    public void unboundedOrMalformedWorkloadIsRejected() {
        Assert.assertThrows(IllegalArgumentException.class, () -> StorageOperationResourceBudget.estimate(
                null, 0, 0, 0, 0, false));
        Assert.assertThrows(IllegalArgumentException.class, () -> StorageOperationResourceBudget.estimate(
                StorageOperationResourceBudget.Work.RESTORE, Long.MAX_VALUE, 0, 0, 0, false));
        Assert.assertThrows(IllegalArgumentException.class, () -> StorageOperationResourceBudget.estimate(
                StorageOperationResourceBudget.Work.RESTORE, 0, 0, 0, -1, false));
    }
}
