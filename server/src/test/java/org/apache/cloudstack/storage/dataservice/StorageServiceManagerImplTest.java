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
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.storage.sharedfs.SharedFS;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.google.gson.JsonObject;

public class StorageServiceManagerImplTest {
    private final StorageServiceManagerImpl manager = new StorageServiceManagerImpl();

    @Test
    public void blockRuntimeKeyNormalizesIqnAndDefaultsLun() {
        Assert.assertEquals("iqn.2026-07.local.storage:test|0",
                manager.blockRuntimeKey(" IQN.2026-07.LOCAL.STORAGE:TEST ", null));
        Assert.assertEquals("iqn.2026-07.local.storage:test|2",
                manager.blockRuntimeKey("iqn.2026-07.local.storage:test", " 2 "));
    }

    @Test
    public void unavailableObservationIsNotReportedAsUnmapped() {
        final StorageServiceManagerImpl.RuntimeObservationSnapshot snapshot = new StorageServiceManagerImpl.RuntimeObservationSnapshot();

        final JsonObject observation = manager.unavailableRuntimeObservation(snapshot);

        Assert.assertEquals("UNAVAILABLE", observation.get("mappingStatus").getAsString());
    }

    @Test
    public void storageServiceTimeoutUsesSharedFSFeatureGate() {
        Assert.assertEquals(SharedFS.SharedFSFeatureEnabled.key(), StorageServiceInstance.StorageServiceCommandTimeout.parent());
    }

    @Test
    public void reconcileProtocolListenNicIdentityPersistsObservedPrimaryIp() {
        final StorageServiceManagerImpl managerSpy = Mockito.spy(new StorageServiceManagerImpl());
        final NicDao nicDao = Mockito.mock(NicDao.class);
        final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        final NicVO targetNic = Mockito.mock(NicVO.class);
        final NicVO reconciledNic = Mockito.mock(NicVO.class);
        ReflectionTestUtils.setField(managerSpy, "nicDao", nicDao);
        Mockito.when(targetNic.getId()).thenReturn(38L);
        Mockito.when(targetNic.getIPv4Address()).thenReturn(null);
        Mockito.when(reconciledNic.getIPv4Address()).thenReturn("10.10.254.71");
        Mockito.when(nicDao.updatePrimaryIpAddress(38L, "10.10.254.71", null)).thenReturn(true);
        Mockito.when(nicDao.findById(38L)).thenReturn(reconciledNic);
        Mockito.doReturn("10.10.254.71").when(managerSpy).observeRuntimePrimaryIp(instance);

        Assert.assertSame(reconciledNic, managerSpy.reconcileProtocolListenNicIdentity(instance, targetNic));
        Mockito.verify(nicDao).updatePrimaryIpAddress(38L, "10.10.254.71", null);
    }

    @Test
    public void reconcileProtocolListenNicIdentityKeepsPersistedPrimaryIp() {
        final StorageServiceManagerImpl managerSpy = Mockito.spy(new StorageServiceManagerImpl());
        final NicDao nicDao = Mockito.mock(NicDao.class);
        final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        final NicVO targetNic = Mockito.mock(NicVO.class);
        ReflectionTestUtils.setField(managerSpy, "nicDao", nicDao);
        Mockito.when(targetNic.getIPv4Address()).thenReturn("10.10.254.71");

        Assert.assertSame(targetNic, managerSpy.reconcileProtocolListenNicIdentity(instance, targetNic));
        Mockito.verifyNoInteractions(nicDao);
        Mockito.verify(managerSpy, Mockito.never()).observeRuntimePrimaryIp(Mockito.any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void reconcileProtocolListenNicIdentityRejectsUnavailableRuntimePrimaryIp() {
        final StorageServiceManagerImpl managerSpy = Mockito.spy(new StorageServiceManagerImpl());
        final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        final NicVO targetNic = Mockito.mock(NicVO.class);
        Mockito.when(targetNic.getIPv4Address()).thenReturn(null);
        Mockito.doReturn(null).when(managerSpy).observeRuntimePrimaryIp(instance);

        managerSpy.reconcileProtocolListenNicIdentity(instance, targetNic);
    }
}
