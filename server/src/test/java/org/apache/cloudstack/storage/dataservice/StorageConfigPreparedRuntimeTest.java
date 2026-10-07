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
import org.junit.Test;
import org.mockito.Mockito;
import org.junit.Assert;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeBundleDao;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;

public class StorageConfigPreparedRuntimeTest {
    static class Manager extends StorageServiceManagerImpl {
        boolean protectedProbe;
        protected void requireConfigurationAdministrator() { }
        protected void requireProtectedIdentityTransport(StorageServiceInstanceVO instance,String operationUuid) { protectedProbe=true; }
    }
    @Test public void alreadyPreparedRuntimeChecksProtectedTransportWithoutReactivatingDegradedBootstrap() {
        Manager manager = new Manager();StorageServiceRuntimeBundleDao bundles = Mockito.mock(StorageServiceRuntimeBundleDao.class);
        SharedFSDao shared = Mockito.mock(SharedFSDao.class);StorageServiceRuntimeUpgradeManager upgrades=Mockito.mock(StorageServiceRuntimeUpgradeManager.class);
        ReflectionTestUtils.setField(manager,"storageRuntimeBundleDao",bundles);ReflectionTestUtils.setField(manager,"sharedFSDao",shared);ReflectionTestUtils.setField(manager,"runtimeUpgradeManager",upgrades);
        StorageServiceInstanceVO instance=Mockito.mock(StorageServiceInstanceVO.class);Mockito.when(instance.getVmId()).thenReturn(48L);Mockito.when(instance.getCurrentRuntimeBundleId()).thenReturn(19L);
        StorageServiceRuntimeBundleVO bundle=Mockito.mock(StorageServiceRuntimeBundleVO.class);Mockito.when(bundle.getId()).thenReturn(19L);Mockito.when(bundles.findByUuid("selected-runtime")).thenReturn(bundle);
        Mockito.when(shared.findByVm(48L)).thenReturn(Mockito.mock(SharedFSVO.class));
        manager.upgradeConfigurationNewServiceRuntime(instance,"selected-runtime");
        Assert.assertTrue(manager.protectedProbe);Mockito.verifyNoInteractions(upgrades);
    }
}
