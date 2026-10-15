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
    @Test public void frozenPlanIncludesIncomingAndUpperBoundWithoutClaimingRenderedActualBytes() {
        JsonObject source = StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.CONFIGURATION,1024,0,0,0,2,false,256);
        JsonObject proposed = StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.CONFIGURATION,1024,4096,0,0,2,false,256);
        Assert.assertTrue(proposed.getAsJsonObject("requirements").get("minimumMemoryAvailableBytes").getAsLong()
                > source.getAsJsonObject("requirements").get("minimumMemoryAvailableBytes").getAsLong());
        Assert.assertEquals(4096,proposed.get("incomingBytes").getAsLong());
        Assert.assertTrue(proposed.get("renderedUpperBoundBytes").getAsLong()>0);
        Assert.assertEquals(0,proposed.get("renderedBytes").getAsLong());Assert.assertTrue(proposed.get("renderedPending").getAsBoolean());
        Assert.assertFalse(proposed.get("logicalDiskCapacityIncluded").getAsBoolean());
    }

    @Test public void configuredMinimumIsFrozenAndNeverLowersTheWorkEstimate() {
        JsonObject high = StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.CONFIGURATION,0,0,0,0,0,false,16384);
        Assert.assertEquals(16384*MIB,high.getAsJsonObject("requirements").get("minimumMemoryAvailableBytes").getAsLong());
        JsonObject format = StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.FILESYSTEM_FORMAT,0,0,0,0,0,false,256);
        Assert.assertTrue(format.getAsJsonObject("requirements").get("minimumMemoryAvailableBytes").getAsLong()>=1024*MIB);
        JsonObject frozen=high.deepCopy();
        StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.CONFIGURATION,0,0,0,0,0,false,256);
        StorageOperationResourceBudget.requirePreparedWithin(high,1024,1024,1);
        Assert.assertEquals(frozen,high);
    }

    @Test public void preparedTargetCannotExpandItsExistingLeaseOrHideUnknownRenderedSize() {
        JsonObject frozen=StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.CONFIGURATION,1024,512,0,0,2,false,256);
        JsonObject before=frozen.deepCopy();
        StorageOperationResourceBudget.requirePreparedWithin(frozen,2048,2048,3);
        Assert.assertEquals(before,frozen);
        Assert.assertThrows(IllegalArgumentException.class,()->StorageOperationResourceBudget.requirePreparedWithin(frozen,
                frozen.get("declarationBytes").getAsLong()+1,0,1));
        Assert.assertThrows(IllegalArgumentException.class,()->StorageOperationResourceBudget.requirePreparedWithin(frozen,
                0,frozen.get("renderedUpperBoundBytes").getAsLong()+1,1));
        Assert.assertThrows(IllegalArgumentException.class,()->StorageOperationResourceBudget.requirePreparedWithin(frozen,0,0,100000));
    }

    @Test public void malformedFloorAndOverflowCannotReachResourceAcquisition() {
        for(long floor:new long[]{-1,0,255,1048577,Long.MAX_VALUE})Assert.assertThrows(IllegalArgumentException.class,
                ()->StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.CONFIGURATION,0,0,0,0,0,false,floor));
        Assert.assertThrows(IllegalArgumentException.class,()->StorageOperationResourceBudget.plan(
                StorageOperationResourceBudget.Work.CONFIGURATION,Long.MAX_VALUE,1,0,0,1,false,256));
        JsonObject bad=StorageOperationResourceBudget.plan(StorageOperationResourceBudget.Work.CONFIGURATION,1,1,0,0,0,false,256);
        bad.addProperty("resourcesCount","NaN");
        Assert.assertThrows(IllegalArgumentException.class,()->StorageOperationResourceBudget.requirePreparedWithin(bad,0,0,0));
        bad.addProperty("resourcesCount",new java.math.BigInteger("18446744073709551617"));
        Assert.assertThrows(IllegalArgumentException.class,()->StorageOperationResourceBudget.requirePreparedWithin(bad,0,0,0));
    }

}
