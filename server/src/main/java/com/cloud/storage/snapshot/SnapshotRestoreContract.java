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
package com.cloud.storage.snapshot;

import java.util.LinkedHashMap;
import java.util.Map;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.DiskOfferingVO;

/** Snapshot restore preserves the captured capacity; resizing is a separate operation. */
public final class SnapshotRestoreContract {
    private SnapshotRestoreContract() { }

    public static void validate(long snapshotSize, Long requestedGiB, Long resolvedSize,
            boolean inherited, Long minIops, Long maxIops, Long kmsKeyId) {
        if (snapshotSize <= 0) {
            throw new InvalidParameterValueException("Snapshot restore size is unavailable");
        }
        if (requestedGiB != null && (requestedGiB <= 0 || requestedGiB > Long.MAX_VALUE / (1L << 30)
                || requestedGiB * (1L << 30) != snapshotSize)) {
            throw new InvalidParameterValueException("Snapshot restore preserves snapshot size; resize the restored volume separately");
        }
        if (resolvedSize != null && resolvedSize != snapshotSize) {
            throw new InvalidParameterValueException("Disk offering size must equal snapshot size for restore; resize the restored volume separately");
        }
        if (inherited && (minIops != null || maxIops != null)) {
            throw new InvalidParameterValueException("Snapshot restore inherits IOPS; do not specify IOPS without a disk offering");
        }
        if (kmsKeyId != null) {
            throw new InvalidParameterValueException("Snapshot restore with a KMS key is not supported");
        }
    }

    public static Map<String, Object> describe(long snapshotSize, DiskOfferingVO offering) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size", snapshotSize);
        result.put("diskofferingid", offering == null ? null : offering.getUuid());
        result.put("diskofferingname", offering == null ? null : offering.getName());
        result.put("available", snapshotSize > 0 && offering != null);
        return result;
    }
}
