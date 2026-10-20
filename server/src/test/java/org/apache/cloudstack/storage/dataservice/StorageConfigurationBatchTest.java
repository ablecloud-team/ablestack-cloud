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
        Mockito.doReturn(new com.google.gson.JsonObject()).when(manager).observeConfigurationRuntime(instance, "inventory");
        manager.beginConfigurationBatch(7);manager.finishConfigurationBatch(instance);
        Mockito.verify(manager, Mockito.times(1)).applyNfsDesiredState(instance);
        Mockito.verify(manager, Mockito.never()).applySmbDesiredState(Mockito.any(), Mockito.anyMap());
        Assert.assertNull(batches(manager).get());
    }
    @Test public void restoreReusesSpecificBlockAndSmbListenersWithoutImplicitWildcardRows() {
        for (StorageServiceInstance.Protocol type : java.util.List.of(StorageServiceInstance.Protocol.ISCSI,
                StorageServiceInstance.Protocol.NVME_OF, StorageServiceInstance.Protocol.SMB)) {
            StorageServiceManagerImpl manager = manager();StorageServiceProtocolDao dao = Mockito.mock(StorageServiceProtocolDao.class);
            ReflectionTestUtils.setField(manager, "storageServiceProtocolDao", dao);
            StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
            StorageServiceProtocolVO endpoint = new StorageServiceProtocolVO(7, type, true, "10.10.13.227", type == StorageServiceInstance.Protocol.ISCSI ? 3260 : type == StorageServiceInstance.Protocol.SMB ? 445 : 4420);
            endpoint.setState(StorageServiceInstance.ResourceState.Ready);
            Mockito.when(dao.listByInstanceIdAndProtocol(7, type)).thenReturn(java.util.List.of(endpoint));
            manager.beginConfigurationBatch(7);manager.ensureProtocol(instance, type);
            Mockito.verify(dao).listByInstanceIdAndProtocol(7, type);Mockito.verifyNoMoreInteractions(dao);
            manager.abortConfigurationBatch();
        }
    }
    @Test public void restoreNeverCreatesOrEnablesMissingDisabledOrErrorListeners() {
        for (StorageServiceInstance.ResourceState state : java.util.List.of(StorageServiceInstance.ResourceState.Ready,
                StorageServiceInstance.ResourceState.Error)) {
            StorageServiceManagerImpl manager = manager();StorageServiceProtocolDao dao = Mockito.mock(StorageServiceProtocolDao.class);
            ReflectionTestUtils.setField(manager, "storageServiceProtocolDao", dao);
            StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
            StorageServiceProtocolVO endpoint = new StorageServiceProtocolVO(7, StorageServiceInstance.Protocol.ISCSI, state == StorageServiceInstance.ResourceState.Error, "10.10.13.227", 3260);
            endpoint.setState(state);Mockito.when(dao.listByInstanceIdAndProtocol(7, StorageServiceInstance.Protocol.ISCSI)).thenReturn(java.util.List.of(endpoint));
            manager.beginConfigurationBatch(7);
            Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, () -> manager.ensureProtocol(instance, StorageServiceInstance.Protocol.ISCSI));
            Mockito.verify(dao).listByInstanceIdAndProtocol(7, StorageServiceInstance.Protocol.ISCSI);Mockito.verifyNoMoreInteractions(dao);
            manager.abortConfigurationBatch();
        }
    }
    @Test public void listenerScopeCannotBorrowAnotherInstanceBatch() {
        StorageServiceManagerImpl manager = manager();StorageServiceProtocolDao dao = Mockito.mock(StorageServiceProtocolDao.class);
        ReflectionTestUtils.setField(manager, "storageServiceProtocolDao", dao);
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(8L);
        manager.beginConfigurationBatch(7);
        Assert.assertThrows(com.cloud.exception.InvalidParameterValueException.class, () -> manager.ensureProtocol(instance, StorageServiceInstance.Protocol.ISCSI));
        Mockito.verifyNoInteractions(dao);manager.abortConfigurationBatch();
    }

    @Test public void initialNfsCannotBeReusedWithoutTheOwnedCreateNewWriter() {
        StorageServiceManagerImpl manager=manager();StorageServiceProtocolDao dao=Mockito.mock(StorageServiceProtocolDao.class);
        ReflectionTestUtils.setField(manager,"storageServiceProtocolDao",dao);
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
        manager.beginConfigurationBatch(7);
        com.google.gson.JsonObject plan=new com.google.gson.JsonObject();plan.addProperty("targetMode","CREATE_NEW");
        CloudRuntimeException error=Assert.assertThrows(CloudRuntimeException.class,()->manager.prepareConfigurationInitialNfsReuse(instance,plan));
        Assert.assertTrue(error.getMessage().contains("exact reviewed CREATE_NEW writer"));
        Assert.assertNull(batches(manager).get().initialNfsReuse);Mockito.verifyNoInteractions(dao);manager.abortConfigurationBatch();
    }

    @Test public void ordinaryRestoreNeverAcquiresInitialNfsReuseAuthority() {
        StorageServiceManagerImpl manager=manager();StorageServiceProtocolDao dao=Mockito.mock(StorageServiceProtocolDao.class);
        ReflectionTestUtils.setField(manager,"storageServiceProtocolDao",dao);
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getId()).thenReturn(7L);
        manager.beginConfigurationBatch(7);
        com.google.gson.JsonObject plan=new com.google.gson.JsonObject();plan.addProperty("targetMode","RESTORE_EXISTING");
        manager.prepareConfigurationInitialNfsReuse(instance,plan);
        Assert.assertNull(batches(manager).get().initialNfsReuse);Mockito.verifyNoInteractions(dao);manager.abortConfigurationBatch();
    }

    @Test public void unexpectedEndpointStillFailsWithPublicIdentityDiagnostics() {
        StorageServiceManagerImpl manager=manager();StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);
        com.google.gson.JsonObject desired=com.google.gson.JsonParser.parseString(
            "{\"protocol\":\"SMB\",\"enabled\":true,\"listen_ip\":\"10.10.13.227\",\"port\":445,\"config\":{}}").getAsJsonObject();
        com.google.gson.JsonObject actual=com.google.gson.JsonParser.parseString(
            "{\"11111111-1111-4111-8111-111111111111\":{\"id\":11,\"protocol\":\"SMB\",\"enabled\":true,\"listenIp\":\"10.10.13.227\",\"port\":445,\"settings\":{}},"
            +"\"22222222-2222-4222-8222-222222222222\":{\"id\":12,\"protocol\":\"ISCSI\",\"enabled\":true,\"listenIp\":\"0.0.0.0\",\"port\":3260,\"settings\":{}}}").getAsJsonObject();
        Mockito.doReturn(actual).when(manager).configurationProtocolIntentSnapshot(instance);
        com.google.gson.JsonObject change=new com.google.gson.JsonObject();change.addProperty("kind","protocols");
        change.addProperty("sourceUuid","33333333-3333-4333-8333-333333333333");change.add("desired",desired);
        com.google.gson.JsonObject plan=new com.google.gson.JsonObject();
        for(String key:java.util.List.of("keep","update","create","preserve"))plan.add(key,new com.google.gson.JsonArray());
        plan.getAsJsonArray("create").add(change);
        CloudRuntimeException error=Assert.assertThrows(CloudRuntimeException.class,()->manager.requireConfigurationProtocolIntent(instance,plan,
            Map.of("33333333-3333-4333-8333-333333333333",11L),new com.google.gson.JsonObject()));
        Assert.assertTrue(error.getMessage(),error.getMessage().contains("expectedCount=1, actualCount=2"));
        Assert.assertTrue(error.getMessage().contains("22222222-2222-4222-8222-222222222222"));
        Assert.assertTrue(error.getMessage().contains("ISCSI"));Assert.assertTrue(error.getMessage().contains("3260"));
    }

}
