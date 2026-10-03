// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;

/** Logical allocation policy, never an estimate of snapshot physical usage. */
public final class InternalVmSnapshotAccounting {
    public static final String VERSION_KEY = "vmsnapshot.accounting.version";
    public static final String LEGACY_KEY = "vmsnapshot.accounting.legacy-chain-bytes";
    public static final String VERSION = "kvm-internal-cow-v1";

    private InternalVmSnapshotAccounting() { }

    public static boolean applies(StoragePool pool, HypervisorType hypervisor, ImageFormat format) {
        return pool != null && !pool.isManaged() && pool.getPoolType() == StoragePoolType.SharedMountPoint
                && hypervisor == HypervisorType.KVM && format == ImageFormat.QCOW2;
    }

    public static long requiredFreeBytes(long memoryBytes) {
        // RAM state, metadata and a bounded safety margin. Subsequent COW growth
        // is covered by physical pool monitoring, not duplicated disk allocation.
        return Math.addExact(Math.addExact(memoryBytes, memoryBytes / 10), 1024L * 1024 * 1024);
    }

    public static boolean hasPhysicalSpace(long capacity, long used, long required, double threshold) {
        return capacity > 0 && used >= 0 && used <= capacity && required >= 0
                && threshold > 0 && threshold <= 1 && required <= capacity * threshold - used;
    }
}
