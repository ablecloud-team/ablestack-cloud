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

package org.apache.cloudstack.storage.dataservice;

import java.util.Map;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageConfigurationBatchTest {
    private StorageServiceManagerImpl manager() {
        StorageServiceManagerImpl manager = Mockito.spy(new StorageServiceManagerImpl());
        Mockito.doNothing().when(manager).requireConfigurationAdministrator();return manager;
    }
    @SuppressWarnings("unchecked")
    private ThreadLocal<StorageServiceManagerImpl.ConfigurationBatch> batches(StorageServiceManagerImpl manager) {
        return (ThreadLocal<StorageServiceManagerImpl.ConfigurationBatch>) ReflectionTestUtils.getField(manager, "configurationBatch");
    }
    @Test public void batchDefersGuestWritesAndHoldsCredentialsOnlyUntilFlush() {
        StorageServiceManagerImpl manager = manager();StorageServiceGuestCommandDispatcher guest = Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        ReflectionTestUtils.setField(manager, "guestCommandDispatcher", guest);
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
        manager.beginConfigurationBatch(7);
        manager.applyNfsDesiredState(instance);manager.applySmbDesiredState(instance, Map.of(11L, "synthetic"));
        manager.applyIscsiDesiredState(instance, Collections.emptyMap());manager.applyNvmeOfDesiredState(instance);
        Assert.assertEquals("synthetic", batches(manager).get().smbCredentials.get(11L));
        Mockito.verifyNoInteractions(guest);
        manager.abortConfigurationBatch();Assert.assertNull(batches(manager).get());
    }
    @Test public void crossInstanceFlushIsBlockedAndDoesNotLoseRecoveryContext() {
        StorageServiceManagerImpl manager = manager();StorageServiceInstanceVO other = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(other.getId()).thenReturn(8L);manager.beginConfigurationBatch(7);
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.finishConfigurationBatch(other));
        Assert.assertEquals(7, batches(manager).get().instanceId);manager.abortConfigurationBatch();
    }
    @Test public void finishAppliesEnabledProtocolsOnceAndClearsContextBeforeGuestWrites() {
        StorageServiceManagerImpl manager = manager();StorageServiceProtocolDao protocols = Mockito.mock(StorageServiceProtocolDao.class);
        ReflectionTestUtils.setField(manager, "storageServiceProtocolDao", protocols);
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
        StorageServiceProtocolVO nfs = Mockito.mock(StorageServiceProtocolVO.class);Mockito.when(nfs.isEnabled()).thenReturn(true);
        Mockito.when(protocols.listByInstanceIdAndProtocol(7, StorageServiceInstance.Protocol.NFS)).thenReturn(java.util.List.of(nfs));
        Mockito.when(protocols.listByInstanceIdAndProtocol(7, StorageServiceInstance.Protocol.SMB)).thenReturn(Collections.emptyList());
        Mockito.when(protocols.listByInstanceIdAndProtocol(7, StorageServiceInstance.Protocol.ISCSI)).thenReturn(Collections.emptyList());
        Mockito.when(protocols.listByInstanceIdAndProtocol(7, StorageServiceInstance.Protocol.NVME_OF)).thenReturn(Collections.emptyList());
        Mockito.doAnswer(call -> { Assert.assertNull(batches(manager).get());return null; }).when(manager).applyNfsDesiredState(instance);
        Mockito.doNothing().when(manager).verifyReconciledStorageDesiredState(instance);
        manager.beginConfigurationBatch(7);manager.finishConfigurationBatch(instance);
        Mockito.verify(manager, Mockito.times(1)).applyNfsDesiredState(instance);
        Mockito.verify(manager, Mockito.never()).applySmbDesiredState(Mockito.any(), Mockito.anyMap());
        Assert.assertNull(batches(manager).get());
    }
}
