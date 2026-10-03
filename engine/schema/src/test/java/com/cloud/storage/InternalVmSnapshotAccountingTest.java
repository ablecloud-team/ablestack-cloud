// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License"); you may obtain
// a copy at http://www.apache.org/licenses/LICENSE-2.0 . Distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class InternalVmSnapshotAccountingTest {
    @Test
    public void onlyUnmanagedSharedQcowKvmUsesInternalPolicy() {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(StoragePoolType.SharedMountPoint);
        assertTrue(InternalVmSnapshotAccounting.applies(pool, HypervisorType.KVM, ImageFormat.QCOW2));
        assertFalse(InternalVmSnapshotAccounting.applies(pool, HypervisorType.VMware, ImageFormat.QCOW2));
        assertFalse(InternalVmSnapshotAccounting.applies(pool, HypervisorType.KVM, ImageFormat.RAW));
        when(pool.isManaged()).thenReturn(true);
        assertFalse(InternalVmSnapshotAccounting.applies(pool, HypervisorType.KVM, ImageFormat.QCOW2));
        when(pool.isManaged()).thenReturn(false);
        for (StoragePoolType type : new StoragePoolType[] { StoragePoolType.CLVM, StoragePoolType.CLVM_NG, StoragePoolType.NetworkFilesystem, StoragePoolType.RBD }) {
            when(pool.getPoolType()).thenReturn(type);
            assertFalse(InternalVmSnapshotAccounting.applies(pool, HypervisorType.KVM, ImageFormat.QCOW2));
        }
        assertFalse(InternalVmSnapshotAccounting.applies(null, HypervisorType.KVM, ImageFormat.QCOW2));
    }

    @Test
    public void memoryAndMetadataReserveUsesPhysicalThresholdWithoutOverprovisioning() {
        long gib = 1024L * 1024 * 1024;
        long required = InternalVmSnapshotAccounting.requiredFreeBytes(4 * gib);
        assertTrue(required > 5 * gib);
        assertTrue(InternalVmSnapshotAccounting.hasPhysicalSpace(100 * gib, 80 * gib, required, .9));
        assertFalse(InternalVmSnapshotAccounting.hasPhysicalSpace(100 * gib, 89 * gib, required, .9));
        assertFalse(InternalVmSnapshotAccounting.hasPhysicalSpace(0, 0, required, 1));
        assertFalse(InternalVmSnapshotAccounting.hasPhysicalSpace(100, -1, 1, 1));
        assertFalse(InternalVmSnapshotAccounting.hasPhysicalSpace(100, 101, 1, 1));
        assertFalse(InternalVmSnapshotAccounting.hasPhysicalSpace(100, 10, 1, Double.NaN));
        assertFalse(InternalVmSnapshotAccounting.hasPhysicalSpace(100, 10, 1, 2));
    }
}
