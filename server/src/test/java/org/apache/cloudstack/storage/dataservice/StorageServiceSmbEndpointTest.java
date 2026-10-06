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

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;

public class StorageServiceSmbEndpointTest {
    @Test
    public void smbUsesEndpointIdentityAndShareCreationPreservesExistingSpecificListeners() {
        StorageServiceManagerImpl manager = new StorageServiceManagerImpl();
        StorageServiceProtocolDao protocols = mock(StorageServiceProtocolDao.class);
        ReflectionTestUtils.setField(manager, "storageServiceProtocolDao", protocols);
        StorageServiceInstanceVO instance = mock(StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(7L);
        StorageServiceProtocolVO first = new StorageServiceProtocolVO(7L, StorageServiceInstance.Protocol.SMB, true, "10.1.1.165", 445);
        StorageServiceProtocolVO second = new StorageServiceProtocolVO(7L, StorageServiceInstance.Protocol.SMB, true, "10.1.1.166", 445);
        when(protocols.listByInstanceIdAndProtocol(7L, StorageServiceInstance.Protocol.SMB)).thenReturn(Arrays.asList(first, second));
        Assert.assertTrue(manager.isEndpointProtocol(StorageServiceInstance.Protocol.SMB));
        manager.ensureProtocol(instance, StorageServiceInstance.Protocol.SMB);
        verify(protocols, never()).persist(any());
        Assert.assertSame(first, manager.findProtocolEndpoint(7L, StorageServiceInstance.Protocol.SMB, "10.1.1.165", 445));
        Assert.assertSame(second, manager.findProtocolEndpoint(7L, StorageServiceInstance.Protocol.SMB, "10.1.1.166", 445));
        Assert.assertNull(manager.findProtocolEndpoint(7L, StorageServiceInstance.Protocol.SMB, "10.1.1.167", 445));
    }
}
