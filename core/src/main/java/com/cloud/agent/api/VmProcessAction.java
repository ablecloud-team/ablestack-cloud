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

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict response projection. Never trust an agent's boolean success alone. */
public final class VmProcessAction {
    private VmProcessAction() {}

    public static Map<String, Object> parse(String json) throws IOException {
        if (json == null || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw new IOException("Action output bound");
        try (JsonReader r = new JsonReader(new StringReader(json))) {
            r.setLenient(false);
            Map<String, Object> v = VmProcessSnapshot.map(VmProcessSnapshot.read(r, 0));
            if (r.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON");
            return v;
        } catch (RuntimeException e) {
            throw new IOException("Invalid action JSON", e);
        }
    }

    public static Map<String, Object> unknown(Map<String, Object> request) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (String k :
                List.of(
                        "schemaVersion",
                        "requestId",
                        "authority",
                        "operationId",
                        "action",
                        "identity",
                        "service")) r.put(k, request.get(k));
        if ("1.1".equals(request.get("schemaVersion"))) {
            r.put("profile", request.get("profile"));
            Map<String,Object> progress=new LinkedHashMap<>();
            progress.put("oldProcess","NOT_CHECKED");progress.put("newProcess","UNKNOWN");progress.put("newIdentity",null);
            r.put("progress",progress);
        }
        r.put("kind", "actionResult");
        r.put("state", "UNKNOWN");
        r.put("effect", "MAY_HAVE_RUN");
        r.put("submittedAt", Instant.now().toString());
        r.put("completedAt", null);
        r.put("guestExecPid", null);
        r.put("guestExitCode", null);
        r.put("postcondition", "NOT_CHECKED");
        r.put(
                "error",
                Map.of(
                        "code",
                        "RESULT_UNKNOWN",
                        "message",
                        "Query operation result; mutation will not be replayed",
                        "retryMode",
                        "READ_ONLY"));
        return r;
    }

    public static Map<String, Object> rejected(Map<String, Object> request, String code) {
        Map<String, Object> r = unknown(request);
        r.put("state", "FAILED");
        r.put("effect", "NOT_STARTED");
        if ("1.1".equals(request.get("schemaVersion"))) {
            Map<String,Object> progress=new LinkedHashMap<>();progress.put("oldProcess","NOT_CHECKED");progress.put("newProcess","NOT_ATTEMPTED");progress.put("newIdentity",null);r.put("progress",progress);
        }
        r.put("completedAt", Instant.now().toString());
        r.put(
                "error",
                Map.of(
                        "code",
                        code,
                        "message",
                        "Action rejected before dispatch",
                        "retryMode",
                        "NONE"));
        return r;
    }

    public static Map<String, Object> decode(String json, Map<String, Object> request)
            throws IOException {
        Map<String, Object> r = parse(json);
        if ("1.1".equals(request.get("schemaVersion"))) return VmProcessProfile.decode(r,request);
        try {
            if (!"1.0".equals(r.get("schemaVersion"))) throw new IllegalArgumentException();
            if ("failure".equals(r.get("kind"))
                    && r.keySet()
                            .equals(
                                    Set.of(
                                            "schemaVersion",
                                            "kind",
                                            "requestId",
                                            "authority",
                                            "error"))
                    && Objects.equals(request.get("requestId"), r.get("requestId"))
                    && Objects.equals(request.get("authority"), r.get("authority"))) {
                String code = (String) VmProcessSnapshot.map(r.get("error")).get("code");
                if (Set.of(
                                "PERMISSION_DENIED",
                                "STALE_AUTHORITY",
                                "REQUEST_CONFLICT",
                                "BUSY",
                                "STALE_SNAPSHOT",
                                "CHECK_FAILED",
                                "TOOLS_REQUIRED",
                                "HOST_TOOL_MISSING")
                        .contains(code)) return rejected(request, code);
            }
            if (!r.keySet()
                    .equals(
                            Set.of(
                                    "schemaVersion",
                                    "kind",
                                    "requestId",
                                    "authority",
                                    "operationId",
                                    "action",
                                    "identity",
                                    "service",
                                    "state",
                                    "effect",
                                    "submittedAt",
                                    "completedAt",
                                    "guestExecPid",
                                    "guestExitCode",
                                    "postcondition",
                                    "error"))) throw new IllegalArgumentException();
            for (String k :
                    List.of(
                            "schemaVersion",
                            "requestId",
                            "authority",
                            "operationId",
                            "action",
                            "identity",
                            "service"))
                if (!Objects.equals(request.get(k), r.get(k))) throw new IllegalArgumentException();
            if (!"actionResult".equals(r.get("kind"))) throw new IllegalArgumentException();
            Instant.parse((String) r.get("submittedAt"));
            String state = (String) r.get("state");
            if ("SUCCEEDED".equals(state)) {
                String post =
                        "service.restart".equals(r.get("action"))
                                ? "SERVICE_RESTART_VERIFIED"
                                : "TARGET_EXITED";
                if (!"VERIFIED".equals(r.get("effect"))
                        || !post.equals(r.get("postcondition"))
                        || r.get("error") != null
                        || r.get("guestExitCode") != null
                                && new java.math.BigDecimal(r.get("guestExitCode").toString())
                                                .signum()
                                        != 0) throw new IllegalArgumentException();
                Instant.parse((String) r.get("completedAt"));
            } else if ("FAILED".equals(state)) {
                if (!"NOT_STARTED".equals(r.get("effect"))
                        || !"NOT_CHECKED".equals(r.get("postcondition"))
                        || !(r.get("error") instanceof Map)) throw new IllegalArgumentException();
                Instant.parse((String) r.get("completedAt"));
            } else if ("UNKNOWN".equals(state)) {
                if (!"MAY_HAVE_RUN".equals(r.get("effect"))
                        || r.get("completedAt") != null
                        || !"NOT_CHECKED".equals(r.get("postcondition"))
                        || !"READ_ONLY"
                                .equals(VmProcessSnapshot.map(r.get("error")).get("retryMode")))
                    throw new IllegalArgumentException();
            } else throw new IllegalArgumentException();
            for (String k : List.of("guestExecPid", "guestExitCode"))
                if (r.get(k) != null) {
                    long n = new java.math.BigDecimal(r.get(k).toString()).longValueExact();
                    if (n < 0 || n > 4294967295L) throw new IllegalArgumentException();
                }
            if (r.get("error") != null) {
                Map<String, Object> error = VmProcessSnapshot.map(r.get("error"));
                if (!error.keySet().equals(Set.of("code", "message", "retryMode"))
                        || !Set.of(
                                        "RESULT_UNKNOWN",
                                        "DEADLINE_EXCEEDED",
                                        "STALE_IDENTITY",
                                        "PROTECTED_TARGET",
                                        "UNSUPPORTED_ACTION",
                                        "CHECK_FAILED",
                                        "EXEC_FAILED",
                                        "PERMISSION_DENIED",
                                        "STALE_AUTHORITY",
                                        "BUSY",
                                        "REQUEST_CONFLICT",
                                        "STALE_SNAPSHOT",
                                        "TOOLS_REQUIRED",
                                        "HOST_TOOL_MISSING")
                                .contains(error.get("code"))
                        || !Set.of("NONE", "READ_ONLY").contains(error.get("retryMode")))
                    throw new IllegalArgumentException();
                r.put(
                        "error",
                        Map.of(
                                "code",
                                error.get("code"),
                                "message",
                                "Process action could not be verified",
                                "retryMode",
                                error.get("retryMode")));
            }
            return r;
        } catch (RuntimeException e) {
            throw new IOException("Action identity or postcondition mismatch", e);
        }
    }
}
