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

/**
 * Maps live synchronization facts into monotonic whole-operation progress.
 */
public final class DrSyncWorkflowProgress {
    public static final int AGENT_ACCEPTED = 70;
    public static final int TRANSFER_COMPLETE = 95;
    public static final int TARGET_MATERIALIZING = 97;

    private DrSyncWorkflowProgress() {
    }

    public static int resolve(Integer currentProgress, Double transferPercent,
            Long bytesProcessed, Long bytesTotal, boolean targetMaterializing) {
        int current = clamp(currentProgress != null ? currentProgress : 0, 0, 99);
        if (targetMaterializing) {
            return Math.max(current, TARGET_MATERIALIZING);
        }
        Double effectiveTransferPercent = validPercent(transferPercent)
                ? transferPercent : percentFromBytes(bytesProcessed, bytesTotal);
        if (effectiveTransferPercent == null) {
            return Math.max(current, AGENT_ACCEPTED);
        }
        int transferRange = TRANSFER_COMPLETE - AGENT_ACCEPTED;
        int mapped = AGENT_ACCEPTED + (int) Math.round(effectiveTransferPercent * transferRange / 100D);
        return Math.max(current, clamp(mapped, AGENT_ACCEPTED, TRANSFER_COMPLETE));
    }

    private static boolean validPercent(Double value) {
        return value != null && Double.isFinite(value) && value >= 0D && value <= 100D;
    }

    private static Double percentFromBytes(Long processed, Long total) {
        if (processed == null || total == null || processed < 0L || total <= 0L) {
            return null;
        }
        return Math.min(100D, processed.doubleValue() * 100D / total.doubleValue());
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
