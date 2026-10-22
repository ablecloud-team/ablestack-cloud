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
package org.apache.cloudstack.storage.sharedfs;

import org.junit.Test;
import static org.junit.Assert.*;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import org.apache.cloudstack.api.response.SharedFSPreparationResponse;

public class SharedFSPreparationContractTest {
    @Test public void sizeBudgetsAreSeparateAndBounded() {
        assertEquals(480,SharedFSCreationContext.initialFormatBudget(2L<<40,3600,3600));
        assertEquals(1920,SharedFSCreationContext.initialFormatBudget(10L<<40,3600,3600));
        assertEquals(1800,SharedFSCreationContext.initialFormatBudget(10L<<40,1800,3600));
        assertEquals(300,SharedFSCreationContext.initialFormatBudget(10L<<40,3600,1140));
    }
    @Test(expected=InvalidParameterValueException.class) public void insufficientJobBudgetCannotStart() {
        SharedFSCreationContext.initialFormatBudget(10L<<40,3600,1139);
    }
    @Test(expected=InvalidParameterValueException.class) public void unsafeConfiguredCapCannotStart() {
        SharedFSCreationContext.initialFormatBudget(10L<<40,0,3600);
    }
    @Test public void oldJournalDoesNotOptIntoFormatting() {
        SharedFSCreationContext c=SharedFSCreationContext.read("{\"version\":1,\"existing\":false,\"phase\":\"READY\"}");
        assertNull(c.preparation);assertNull(c.prepareNewFileBacking);
    }
    @Test public void newSnapshotRoundTripsWithoutBroadeningTextLimit() {
        SharedFSCreationContext c=new SharedFSCreationContext();c.prepareNewFileBacking=true;
        c.preparation=new JsonObject();c.preparation.addProperty("operationId","fixture");
        c.preparation.addProperty("formatEverStarted",true);c.preparation.addProperty("formatCount",1);
        SharedFSCreationContext r=SharedFSCreationContext.read(c.json());
        assertEquals(1,r.preparation.get("formatCount").getAsInt());assertTrue(r.preparation.get("formatEverStarted").getAsBoolean());
    }
    @Test(expected=InvalidParameterValueException.class) public void preparationPayloadHasOwnBound() {
        SharedFSCreationContext c=new SharedFSCreationContext();c.preparation=new JsonObject();
        c.preparation.addProperty("reason","x".repeat(8192));c.json();
    }
    @Test public void responseCarriesStructuredState() {
        SharedFSPreparationResponse r=new SharedFSPreparationResponse();
        r.setPreparationJson("{\"status\":\"RUNNING\",\"formatCount\":1,\"canResume\":false}");
        JsonObject p=JsonParser.parseString(new Gson().toJson(r)).getAsJsonObject().getAsJsonObject("preparation");
        assertEquals("RUNNING",p.get("status").getAsString());assertFalse(p.get("canResume").getAsBoolean());
    }
}
