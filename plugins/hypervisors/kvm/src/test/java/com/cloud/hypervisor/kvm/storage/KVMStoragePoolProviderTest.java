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
package com.cloud.hypervisor.kvm.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.cloud.hypervisor.kvm.resource.KVMHAMonitor;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class KVMStoragePoolProviderTest {
    @Test
    public void uuidLookupUsesTheRegisteredProviderForEveryPool() {
        for (StoragePoolType type : new StoragePoolType[]{StoragePoolType.RBD, StoragePoolType.NetworkFilesystem,
                StoragePoolType.SharedMountPoint, StoragePoolType.CLVM}) {
            KVMStoragePoolManager manager = Mockito.mock(KVMStoragePoolManager.class, Mockito.CALLS_REAL_METHODS);
            StorageAdaptor adaptor = Mockito.mock(StorageAdaptor.class);
            KVMStoragePool pool = Mockito.mock(KVMStoragePool.class);
            Mockito.when(pool.getUuid()).thenReturn("fixture-pool");
            ReflectionTestUtils.setField(manager, "_storagePools", new ConcurrentHashMap<>());
            ReflectionTestUtils.setField(manager, "_storageMapper", Map.of(type.toString(), adaptor));
            ReflectionTestUtils.setField(manager, "_haMonitor", Mockito.mock(KVMHAMonitor.class));
            Mockito.when(adaptor.createStoragePool("fixture-pool", "fixture-host", 1, "/fixture", null, type, Map.of(), true)).thenReturn(pool);
            manager.createStoragePool("fixture-pool", "fixture-host", 1, "/fixture", null, type, Map.of());
            Mockito.when(adaptor.getStoragePool("fixture-pool", false)).thenReturn(pool);
            Assert.assertSame(pool, manager.getStoragePoolByUuid("fixture-pool"));
            Mockito.verify(adaptor).getStoragePool("fixture-pool", false);
            try {
                manager.getStoragePoolByUuid("unregistered");
                Assert.fail("Missing registration must not select a guessed provider");
            } catch (CloudRuntimeException expected) {
                Mockito.verify(adaptor, Mockito.never()).getStoragePool("unregistered", false);
            }
        }
    }
}
