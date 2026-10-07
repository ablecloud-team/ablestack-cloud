// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
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
public class StorageConfigRuntimeCollectionTest {
    private JsonObject collectors(boolean inventory, boolean health, boolean sessions) {
        JsonObject all = new JsonObject();String[] names = {"inventory", "health", "sessions"};
        boolean[] values = {inventory, health, sessions};
        for (int i = 0; i < names.length; i++) { JsonObject value = new JsonObject();value.addProperty("success", values[i]);all.add(names[i], value); }
        return all;
    }
    @Test public void stoppedOrUnreachableRuntimeIsUnavailableRatherThanPartial() {
        Assert.assertEquals("UNAVAILABLE", StorageServiceConfiguration.runtimeCollectionStatus(true, collectors(false, false, false)));
        Assert.assertEquals("UNAVAILABLE", StorageServiceConfiguration.runtimeCollectionStatus(true, new JsonObject()));
    }
    @Test public void oneMissingCollectorCannotClaimCompleteRuntime() {
        Assert.assertEquals("PARTIAL", StorageServiceConfiguration.runtimeCollectionStatus(true, collectors(true, false, true)));
        JsonObject incomplete = collectors(true, true, true);incomplete.remove("sessions");
        Assert.assertEquals("PARTIAL", StorageServiceConfiguration.runtimeCollectionStatus(true, incomplete));
    }
    @Test public void explicitlySkippedRuntimeIsNotAnAvailabilityFailure() {
        Assert.assertEquals("NOT_REQUESTED", StorageServiceConfiguration.runtimeCollectionStatus(false, new JsonObject()));
        Assert.assertEquals("AVAILABLE", StorageServiceConfiguration.runtimeCollectionStatus(true, collectors(true, true, true)));
    }
}
