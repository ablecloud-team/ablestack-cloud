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
package com.cloud.agent.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.gson.GsonBuilder;

import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class VmProcessActionTest {
    private Map<String, Object> request() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schemaVersion", "1.0");
        r.put("requestId", "x");
        r.put("operationId", "y");
        r.put("authority", Map.of("vmUuid", "v"));
        r.put("identity", Map.of("startTicks", "18446744073709551615"));
        r.put("action", "process.kill");
        r.put("service", null);
        return r;
    }

    private String json(Object r) {
        return new GsonBuilder().serializeNulls().create().toJson(r);
    }

    @Test
    public void strictDuplicateAndTruncatedJsonFail() {
        assertThrows(IOException.class, () -> VmProcessAction.parse("{\"x\":1,\"x\":2}"));
        assertThrows(IOException.class, () -> VmProcessAction.parse("{}{}"));
    }

    @Test
    public void unknownIsNotSuccess() throws Exception {
        Map<String, Object> r = request();
        assertEquals(
                "UNKNOWN",
                VmProcessAction.decode(json(VmProcessAction.unknown(r)), r).get("state"));
    }

    @Test
    public void successRequiresPostcondition() {
        Map<String, Object> r = request(), v = VmProcessAction.unknown(r);
        v.put("state", "SUCCEEDED");
        assertThrows(IOException.class, () -> VmProcessAction.decode(json(v), r));
    }

    @Test
    public void identityMismatchRejected() {
        Map<String, Object> r = request(), v = VmProcessAction.unknown(r);
        v.put("identity", Map.of("startTicks", "1"));
        assertThrows(IOException.class, () -> VmProcessAction.decode(json(v), r));
    }

    @Test
    public void boundedOutput() {
        assertThrows(IOException.class, () -> VmProcessAction.parse(" ".repeat(65537)));
    }

    @Test
    public void ambiguousFailureCannotReleaseReservation() {
        Map<String, Object> r = request(), v = VmProcessAction.unknown(r);
        v.put("state", "FAILED");
        v.put("completedAt", java.time.Instant.now().toString());
        assertThrows(IOException.class, () -> VmProcessAction.decode(json(v), r));
    }
}
