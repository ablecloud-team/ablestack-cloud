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

import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Resource estimates use materialized request bytes, never virtual DATA disk capacity. */
public final class StorageOperationResourceBudget {
    private static final long MIB = 1024L * 1024;
    private static final long MAX_INPUT_BYTES = 1024L * MIB;
    private static final int MAX_RESOURCES = 100_000;

    public enum Work {
        CONFIGURATION, BACKUP, RESTORE, RUNTIME_UPGRADE, ROOT_UPGRADE, FILESYSTEM_FORMAT, SCALE
    }

    private final long minimumMemoryAvailableBytes;
    private final long stagingRequiredBytes;
    private final double maxLoadPerCpu;
    private final boolean requireSessionDrain;

    private StorageOperationResourceBudget(long memory, long staging, double load, boolean drain) {
        minimumMemoryAvailableBytes = memory;
        stagingRequiredBytes = staging;
        maxLoadPerCpu = load;
        requireSessionDrain = drain;
    }

    public static StorageOperationResourceBudget estimate(Work work, long declarationBytes,
            long artifactBytes, long renderedBytes, int resources, boolean protocolRestart) {
        if (work == null || declarationBytes < 0 || artifactBytes < 0 || renderedBytes < 0
                || declarationBytes > MAX_INPUT_BYTES || artifactBytes > MAX_INPUT_BYTES
                || renderedBytes > MAX_INPUT_BYTES || resources < 0 || resources > MAX_RESOURCES) {
            throw new IllegalArgumentException("Invalid materialized Storage Service workload");
        }
        long baseMemory = work == Work.ROOT_UPGRADE || work == Work.FILESYSTEM_FORMAT ? 1024 * MIB
                : work == Work.BACKUP || work == Work.RESTORE ? 512 * MIB : 256 * MIB;
        // Parse, normalize and checkpoint coexist; rendered validation adds its own working copy.
        long memory = Math.addExact(baseMemory, Math.addExact(Math.multiplyExact(declarationBytes, 6),
                Math.addExact(Math.multiplyExact(renderedBytes, 2), Math.multiplyExact((long) resources, 64 * 1024))));
        // Keep the input, staged output and retained previous artifact until verification completes.
        long staging = Math.addExact(128 * MIB, Math.addExact(Math.multiplyExact(artifactBytes, 3),
                Math.addExact(Math.multiplyExact(renderedBytes, 2), Math.multiplyExact(declarationBytes, 2))));
        boolean drain = protocolRestart || work == Work.ROOT_UPGRADE;
        return new StorageOperationResourceBudget(memory, staging, 1.0, drain);
    }

    public JsonObject request() {
        JsonObject result = new JsonObject();
        result.addProperty("minimumMemoryAvailableBytes", minimumMemoryAvailableBytes);
        result.addProperty("stagingRequiredBytes", stagingRequiredBytes);
        result.addProperty("maxLoadPerCpu", maxLoadPerCpu);
        result.addProperty("requireSessionDrain", requireSessionDrain);
        return result;
    }

    public JsonObject assess(Long memoryAvailableBytes, Long stagingFreeBytes,
            Double loadOneMinute, Integer onlineCpuCount, Long activeSessions) {
        List<String> blockers = new ArrayList<>();
        if (memoryAvailableBytes == null || memoryAvailableBytes < 0) blockers.add("MEMORY_OBSERVATION_UNAVAILABLE");
        else if (memoryAvailableBytes < minimumMemoryAvailableBytes) blockers.add("INSUFFICIENT_MEMORY_HEADROOM");
        if (stagingFreeBytes == null || stagingFreeBytes < 0) blockers.add("STAGING_OBSERVATION_UNAVAILABLE");
        else if (stagingFreeBytes < stagingRequiredBytes) blockers.add("INSUFFICIENT_STAGING_SPACE");
        if (loadOneMinute == null || !Double.isFinite(loadOneMinute) || loadOneMinute < 0
                || onlineCpuCount == null || onlineCpuCount < 1) blockers.add("LOAD_OBSERVATION_UNAVAILABLE");
        else if (loadOneMinute / onlineCpuCount > maxLoadPerCpu) blockers.add("CPU_LOAD_PRESSURE");
        if (requireSessionDrain) {
            if (activeSessions == null || activeSessions < 0) blockers.add("SESSION_OBSERVATION_UNAVAILABLE");
            else if (activeSessions > 0) blockers.add("ACTIVE_SESSIONS_REQUIRE_DRAIN");
        }
        JsonObject result = new JsonObject();
        JsonArray reasons = new JsonArray();
        blockers.forEach(reasons::add);
        result.addProperty("compatible", blockers.isEmpty());
        result.addProperty("reservationAcquired", false);
        result.add("requirements", request());
        result.add("blockers", reasons);
        return result;
    }
}
