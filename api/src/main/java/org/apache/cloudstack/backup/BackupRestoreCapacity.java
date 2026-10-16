// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.backup;

import java.math.BigInteger;

/** Shared primary storage overhead for ABLESTACK restore admission and execution. */
public final class BackupRestoreCapacity {
    public static final int DEFAULT_BUFFER_PERCENT = 20;
    private static final long MINIMUM_BUFFER_BYTES = 10L * 1024 * 1024 * 1024;

    private BackupRestoreCapacity() { }

    public static int bufferPercent(Integer percent) {
        int value = percent == null ? DEFAULT_BUFFER_PERCENT : percent;
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("Restore primary storage capacity buffer percent must be between 0 and 100");
        }
        return value;
    }

    public static long bufferBytes(long requiredBytes, Integer percent) {
        if (requiredBytes < 0) { throw new IllegalArgumentException("Restore capacity must be non-negative"); }
        // Whole-byte truncation preserves the existing fixed 20% checks for saved legacy plans.
        long percentageBytes = BigInteger.valueOf(requiredBytes).multiply(BigInteger.valueOf(bufferPercent(percent)))
                .divide(BigInteger.valueOf(100)).longValueExact();
        return Math.max(MINIMUM_BUFFER_BYTES, percentageBytes);
    }

    public static long requiredBytes(long volumeBytes, Integer percent) {
        return Math.addExact(volumeBytes, bufferBytes(volumeBytes, percent));
    }
}
