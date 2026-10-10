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

import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao;
import com.cloud.utils.exception.CloudRuntimeException;

public class StorageServiceReconcileObservationTest {
    private StorageServiceManagerImpl manager(String payload, boolean success) {
        StorageServiceManagerImpl manager = new StorageServiceManagerImpl();
        StorageServiceGuestCommandDispatcher guest = Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        StorageFileShareDao shares = Mockito.mock(StorageFileShareDao.class);
        StorageBlockTargetDao targets = Mockito.mock(StorageBlockTargetDao.class);
        StoragePosixDirectoryPolicyDao policies = Mockito.mock(StoragePosixDirectoryPolicyDao.class);
        ReflectionTestUtils.setField(manager, "guestCommandDispatcher", guest);
        ReflectionTestUtils.setField(manager, "storageFileShareDao", shares);
        ReflectionTestUtils.setField(manager, "storageBlockTargetDao", targets);
        ReflectionTestUtils.setField(manager, "storagePosixPolicyDao", policies);
        Mockito.when(guest.dispatch(Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(success, "unavailable", payload));
        Mockito.when(shares.listByInstanceIdAndProtocol(Mockito.anyLong(), Mockito.any())).thenReturn(Collections.emptyList());
        Mockito.when(targets.listByInstanceIdAndProtocol(Mockito.anyLong(), Mockito.any())).thenReturn(Collections.emptyList());
        Mockito.when(policies.listByInstance(Mockito.anyLong())).thenReturn(Collections.emptyList());
        return manager;
    }
    private StorageServiceInstanceVO instance() {
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getId()).thenReturn(7L);Mockito.when(instance.getVmId()).thenReturn(43L);return instance;
    }
    @Test public void cannotReconcileWhenCurrentGuestObservationFails() {
        Assert.assertThrows(CloudRuntimeException.class, () -> manager("{}", false).verifyReconciledStorageDesiredState(instance()));
    }
    @Test public void staleAndUnstampedInventoryCannotReconcileEvenIfTransportSucceeds() {
        for (String payload : new String[] {"{}", "{'generatedEpoch':1}"}) {
            Assert.assertThrows(CloudRuntimeException.class, () -> manager(payload, true).verifyReconciledStorageDesiredState(instance()));
        }
    }
    @Test public void inaccessibleNfsEndpointBlocksRecoveryBeforeChangingHistory() {
        String payload = "{'generatedEpoch':" + System.currentTimeMillis() / 1000.0 + ",'nfsGaneshaExports':[{'listening':false,'entries':[]}]}";
        Assert.assertThrows(CloudRuntimeException.class, () -> manager(payload, true).verifyReconciledStorageDesiredState(instance()));
    }
    @Test public void emptyHealthyServiceDoesNotRequireAbsentProtocolDaemons() {
        manager("{'generatedEpoch':" + System.currentTimeMillis() / 1000.0 + "}", true).verifyReconciledStorageDesiredState(instance());
    }
}
