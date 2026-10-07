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

package org.apache.cloudstack.storage.sharedfs;

import com.google.gson.JsonObject;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;

/** Online scale-up contract with guest verification and explicit cold rollback on partial hardware changes. */
public final class SharedFSOnlineScale {
    private SharedFSOnlineScale() { }
    public interface Runtime {
        void health();
        JsonObject resources();
        void prepare(int targetCpus);
        void resize();
        void restore();
        void pause();
        void phase(String value);
    }
    public static void validate(int beforeCpu, long beforeMemoryMiB, int targetCpu, long targetMemoryMiB) {
        if (targetCpu<beforeCpu || targetMemoryMiB<beforeMemoryMiB) throw new InvalidParameterValueException("SharedFS online scale-down is not supported");
        if (targetCpu==beforeCpu && targetMemoryMiB==beforeMemoryMiB) throw new InvalidParameterValueException("Select an offering with increased CPU or memory");
    }
    public static boolean reflected(JsonObject before, JsonObject after, int targetCpu, long memoryIncreaseBytes) {
        try {
            if (!after.get("success").getAsBoolean() || after.get("onlineCpuCount").getAsInt()<targetCpu) return false;
            long observedIncrease=after.get("memoryTotalBytes").getAsLong()-before.get("memoryTotalBytes").getAsLong();
            return memoryIncreaseBytes==0 || observedIncrease>=memoryIncreaseBytes-Math.min(64L*1024*1024,memoryIncreaseBytes/20);
        } catch (RuntimeException missingEvidence) { return false; }
    }
    public static JsonObject execute(Runtime runtime, int targetCpu, long memoryIncreaseBytes) {
        runtime.phase("PREFLIGHT");runtime.health();JsonObject before=runtime.resources();runtime.prepare(targetCpu);
        boolean resizing=false;
        try {
            runtime.phase("RESIZING");resizing=true;runtime.resize();runtime.phase("VERIFYING");
            for (int attempt=0;attempt<12;attempt++) {
                JsonObject after=runtime.resources();
                if (reflected(before,after,targetCpu,memoryIncreaseBytes)) { runtime.health();runtime.phase("COMPLETE");return after; }
                runtime.pause();
            }
            throw new CloudRuntimeException("Guest CPU/memory does not match the requested scale-up");
        } catch (RuntimeException failure) {
            if (resizing) {
                try {
                    runtime.phase("ROLLING_BACK");runtime.restore();runtime.health();
                    JsonObject restored=runtime.resources();
                    if (restored.get("onlineCpuCount").getAsInt()!=before.get("onlineCpuCount").getAsInt()
                            || Math.abs(restored.get("memoryTotalBytes").getAsLong()-before.get("memoryTotalBytes").getAsLong())>64L*1024*1024) {
                        throw new CloudRuntimeException("Original guest resources were not restored");
                    }
                    runtime.phase("ROLLED_BACK");
                }
                catch (RuntimeException recovery) { runtime.phase("RECOVERY_REQUIRED");failure.addSuppressed(recovery); }
            }
            throw failure;
        }
    }
}
