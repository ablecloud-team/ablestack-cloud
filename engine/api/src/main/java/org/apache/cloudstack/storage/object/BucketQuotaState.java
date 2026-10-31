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
package org.apache.cloudstack.storage.object;

/** Exact provider quota and live usage, independent of Cloud's cached KiB usage. */
public final class BucketQuotaState {
    private final boolean exists;
    private final String externalId;
    private final boolean enabled;
    private final long maxSizeBytes;
    private final long maxObjects;
    private final long usedBytes;
    private final boolean checkOnRaw;
    public BucketQuotaState(boolean exists, String externalId, boolean enabled, long maxSizeBytes, long maxObjects, long usedBytes, boolean checkOnRaw) {
        this.exists = exists; this.externalId = externalId; this.enabled = enabled;
        this.maxSizeBytes = maxSizeBytes; this.maxObjects = maxObjects; this.usedBytes = usedBytes; this.checkOnRaw = checkOnRaw;
    }
    public static BucketQuotaState missing() { return new BucketQuotaState(false, null, false, -1, -1, 0, false); }
    public boolean exists() { return exists; }
    public String getExternalId() { return externalId; }
    public boolean isEnabled() { return enabled; }
    public long getMaxSizeBytes() { return maxSizeBytes; }
    public long getMaxObjects() { return maxObjects; }
    public boolean isCheckOnRaw() { return checkOnRaw; }
    public long getUsedBytes() { return usedBytes; }
    public long getEffectiveLimitBytes() { return !enabled || maxSizeBytes < 0 ? -1 : maxSizeBytes; }
    public boolean sameQuota(BucketQuotaState other) {
        return other != null && exists && other.exists && enabled == other.enabled
                && maxSizeBytes == other.maxSizeBytes && maxObjects == other.maxObjects && checkOnRaw == other.checkOnRaw;
    }
    public BucketQuotaState withLimitGiB(int quota) {
        return new BucketQuotaState(exists, externalId, true, Math.multiplyExact((long)quota, 1024L * 1024L * 1024L), maxObjects, usedBytes, checkOnRaw);
    }
}
