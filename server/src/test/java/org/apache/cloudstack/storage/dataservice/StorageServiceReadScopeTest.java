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

import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageNfsExportsCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageSmbSharesCmd;

public class StorageServiceReadScopeTest {
    @Test public void foreignFileSharesAreFilteredBeforeAnyGuestRuntimeRead() {
        StorageServiceManagerImpl manager = spy(new StorageServiceManagerImpl());
        StorageFileShareDao shares = mock(StorageFileShareDao.class);
        StorageServiceInstanceDao instances = mock(StorageServiceInstanceDao.class);
        StorageServiceGuestCommandDispatcher guest = mock(StorageServiceGuestCommandDispatcher.class);
        ReflectionTestUtils.setField(manager,"storageFileShareDao",shares);
        ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);
        ReflectionTestUtils.setField(manager,"guestCommandDispatcher",guest);
        StorageServiceInstanceVO foreign = mock(StorageServiceInstanceVO.class);
        doReturn(false).when(manager).canReadStorageInstance(foreign);
        when(instances.findById(7L)).thenReturn(foreign);
        for (StorageServiceInstance.Protocol protocol : new StorageServiceInstance.Protocol[] {StorageServiceInstance.Protocol.NFS,StorageServiceInstance.Protocol.SMB}) {
            StorageFileShareVO share = mock(StorageFileShareVO.class);
            when(share.getInstanceId()).thenReturn(7L); when(share.getProtocol()).thenReturn(protocol);
            when(shares.listAll()).thenReturn(java.util.Collections.singletonList(share));
            if (protocol == StorageServiceInstance.Protocol.NFS) Assert.assertTrue(manager.listStorageNfsExports(mock(ListStorageNfsExportsCmd.class)).getResponses().isEmpty());
            else Assert.assertTrue(manager.listStorageSmbShares(mock(ListStorageSmbSharesCmd.class)).getResponses().isEmpty());
        }
        verifyNoInteractions(guest);
    }
    @Test public void foreignBlockTargetsAreFilteredEvenWhenAnExactIdIsRequested() {
        StorageServiceManagerImpl manager = spy(new StorageServiceManagerImpl());
        StorageBlockTargetDao targets = mock(StorageBlockTargetDao.class);
        StorageServiceInstanceDao instances = mock(StorageServiceInstanceDao.class);
        ReflectionTestUtils.setField(manager,"storageBlockTargetDao",targets);
        ReflectionTestUtils.setField(manager,"storageServiceInstanceDao",instances);
        StorageServiceInstanceVO foreign = mock(StorageServiceInstanceVO.class);
        StorageBlockTargetVO target = mock(StorageBlockTargetVO.class);
        when(target.getProtocol()).thenReturn(StorageServiceInstance.Protocol.ISCSI); when(target.getInstanceId()).thenReturn(7L);
        when(targets.findById(8L)).thenReturn(target); when(instances.findById(7L)).thenReturn(foreign);
        doReturn(false).when(manager).canReadStorageInstance(foreign);
        Assert.assertTrue(manager.listBlockTargets(8L,null,StorageServiceInstance.Protocol.ISCSI).isEmpty());
    }
}
