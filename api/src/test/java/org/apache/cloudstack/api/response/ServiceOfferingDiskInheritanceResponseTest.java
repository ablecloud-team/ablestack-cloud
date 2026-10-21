// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.response;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceOfferingDiskInheritanceResponseTest {
    private JsonObject json(ServiceOfferingResponse response) {
        return new JsonParser().parse(new Gson().toJson(response)).getAsJsonObject();
    }

    @Test
    public void dedicatedAndGeneralDisksHaveExplicitBooleanContract() {
        ServiceOfferingResponse response = new ServiceOfferingResponse();
        response.setDiskOfferingComputeOnly(true);
        assertTrue(json(response).get("diskofferingcomputeonly").getAsBoolean());
        response.setDiskOfferingComputeOnly(false);
        assertTrue(json(response).has("diskofferingcomputeonly"));
        assertFalse(json(response).get("diskofferingcomputeonly").getAsBoolean());
    }

    @Test
    public void unknownDiskKindIsNotInferredFromItsName() {
        ServiceOfferingResponse response = new ServiceOfferingResponse();
        response.setDiskOfferingName("same-name-as-compute");
        assertFalse(json(response).has("diskofferingcomputeonly"));
        assertEquals("same-name-as-compute", json(response).get("diskofferingname").getAsString());
    }
}
