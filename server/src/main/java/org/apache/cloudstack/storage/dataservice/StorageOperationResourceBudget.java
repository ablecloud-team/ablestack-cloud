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


    /** Count only annotated input values in RAM; no credentials or request bodies leave this method. */
    public static long incomingParameterBytes(org.apache.cloudstack.api.BaseCmd command) {
        if (command == null) return 0;
        long total = 0;
        com.google.gson.Gson gson = new com.google.gson.Gson();
        try {
            for (Class<?> type = command.getClass(); type != null; type = type.getSuperclass()) {
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    org.apache.cloudstack.api.Parameter parameter = field.getAnnotation(org.apache.cloudstack.api.Parameter.class);
                    if (parameter == null || java.util.Set.of("idempotencykey", "expectedrevision").contains(parameter.name().toLowerCase(java.util.Locale.ROOT))) continue;
                    field.setAccessible(true);Object value = field.get(command);
                    if (value != null) total = Math.addExact(total, gson.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                    boundedBytes(total);
                }
            }
            return total;
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalArgumentException("Materialized Storage Service request size is unavailable", unavailable);
        }
    }

    private static long boundedBytes(long value) {
        if (value < 0 || value > MAX_INPUT_BYTES) throw new IllegalArgumentException("Materialized Storage Service byte bound exceeded");
        return value;
    }

    /** Conservative numeric upper bounds are frozen before the first acquisition, not physical allocations. */
    public static JsonObject plan(Work work, long sourceBytes, long incomingBytes, long artifactBytes,
            long expandedArtifactBytes, int sourceResources, boolean drain, long floorMiB) {
        if (sourceResources < 0 || sourceResources > MAX_RESOURCES || floorMiB < 256 || floorMiB > 1_048_576) {
            throw new IllegalArgumentException("Invalid Storage Service resource count or memory floor");
        }
        boundedBytes(sourceBytes);boundedBytes(incomingBytes);boundedBytes(artifactBytes);boundedBytes(expandedArtifactBytes);
        long materialized = boundedBytes(Math.addExact(Math.addExact(sourceBytes, incomingBytes), expandedArtifactBytes));
        long declarationUpper = boundedBytes(Math.addExact(Math.multiplyExact(materialized, 2), 64 * 1024L));
        long rows = Math.addExact(Math.addExact((long) sourceResources, 32), (expandedArtifactBytes + 1023) / 1024);
        if (rows > MAX_RESOURCES) throw new IllegalArgumentException("Materialized Storage Service resource bound exceeded");
        long renderedUpper = boundedBytes(Math.addExact(Math.multiplyExact(declarationUpper, 8),
                Math.addExact(Math.multiplyExact(rows, 4096), 256 * 1024L)));
        JsonObject requirements = estimate(work, declarationUpper, artifactBytes, renderedUpper, (int) rows, drain).request();
        requirements.addProperty("minimumMemoryAvailableBytes", Math.max(requirements.get("minimumMemoryAvailableBytes").getAsLong(), Math.multiplyExact(floorMiB, MIB)));
        JsonObject result = new JsonObject();
        result.addProperty("work", work.name());result.addProperty("sourceDeclarationBytes", sourceBytes);
        result.addProperty("incomingBytes", incomingBytes);result.addProperty("artifactBytes", artifactBytes);result.addProperty("expandedArtifactUpperBoundBytes", expandedArtifactBytes);
        result.addProperty("declarationBytes", declarationUpper);result.addProperty("sourceResourcesCount", sourceResources);result.addProperty("resourcesCount", rows);
        result.addProperty("renderedBytes", 0);result.addProperty("renderedPending", true);result.addProperty("renderedUpperBoundBytes", renderedUpper);
        result.addProperty("minimumMemoryFloorMiB", floorMiB);result.addProperty("logicalDiskCapacityIncluded", false);
        result.add("requirements", requirements);return result;
    }

    private static long whole(JsonObject value, String field) {
        com.google.gson.JsonElement element = value.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
                || !element.getAsString().matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Invalid frozen Storage Service budget number");
        try { return Long.parseLong(element.getAsString()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Frozen Storage Service budget number exceeds range", invalid); }
    }

    /** Actual target validation cannot expand or mutate the immutable native lease requirements. */
    public static void requirePreparedWithin(JsonObject frozen, long declarationBytes, long targetBytes, int resources) {
        boundedBytes(declarationBytes);boundedBytes(targetBytes);
        if (resources < 0 || resources > whole(frozen, "resourcesCount") || declarationBytes > whole(frozen, "declarationBytes")
                || targetBytes > whole(frozen, "renderedUpperBoundBytes")) {
            throw new IllegalArgumentException("Prepared Storage Service target exceeds its frozen resource estimate");
        }
        Work work = Work.valueOf(frozen.get("work").getAsString());
        JsonObject limits = frozen.getAsJsonObject("requirements");
        JsonObject actual = estimate(work, declarationBytes, whole(frozen, "artifactBytes"),
                whole(frozen, "renderedUpperBoundBytes"), resources, limits.get("requireSessionDrain").getAsBoolean()).request();
        if (actual.get("minimumMemoryAvailableBytes").getAsLong() > whole(limits, "minimumMemoryAvailableBytes")
                || actual.get("stagingRequiredBytes").getAsLong() > whole(limits, "stagingRequiredBytes")) {
            throw new IllegalArgumentException("Prepared Storage Service target exceeds its frozen resource headroom");
        }
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
