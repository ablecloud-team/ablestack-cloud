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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

final class DrFailbackCommitEnvelope {
    static final String CONTRACT_VERSION = "DR_FAILBACK_COMMIT_V1";
    private static final Gson GSON = new Gson();

    private DrFailbackCommitEnvelope() {
    }

    static String sha256(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session,
            String targetPowerState, String sourcePowerState, String bootValidationState) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("authorityGeneration", session.getAuthorityGeneration());
        fields.put("baselineGeneration", session.getBaselineGeneration());
        fields.put("bootValidationState", bootValidationState);
        fields.put("checkpointSequence", session.getCheckpointSequence());
        fields.put("commitAttemptId", session.getCommitAttemptId());
        fields.put("contractVersion", CONTRACT_VERSION);
        fields.put("evidenceRunUuid", run.getUuid());
        fields.put("failbackSessionId", session.getEngineSessionId());
        fields.put("planUuid", plan.getUuid());
        fields.put("runUuid", run.getUuid());
        fields.put("sourcePowerState", sourcePowerState);
        fields.put("targetPowerState", targetPowerState);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(GSON.toJson(fields).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new CloudRuntimeException("SHA-256 is unavailable", e);
        }
    }
}
