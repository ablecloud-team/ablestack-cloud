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
package org.apache.cloudstack.storage.dataservice;

import java.util.Set;
import javax.inject.Provider;

import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageIdentityDomainDao;
import org.apache.cloudstack.storage.dataservice.dao.StoragePosixDirectoryPolicyDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class StorageRuntimeDependencyGateTest {
    private StorageServiceRuntimeUpgradeManagerImpl manager;
    private StorageService service;
    private StorageServiceInstanceVO instance;

    @Before
    public void setup() {
        manager = new StorageServiceRuntimeUpgradeManagerImpl();
        service = Mockito.mock(StorageService.class);
        instance = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getId()).thenReturn(17L);
        ReflectionTestUtils.setField(manager, "operationControlService", (Provider<StorageService>) () -> service);
        ReflectionTestUtils.setField(manager, "posixPolicyDao", Mockito.mock(StoragePosixDirectoryPolicyDao.class));
        ReflectionTestUtils.setField(manager, "runtimeIdentityDomainDao", Mockito.mock(StorageIdentityDomainDao.class));
        ReflectionTestUtils.setField(manager, "fileShareDao", Mockito.mock(StorageFileShareDao.class));
        ReflectionTestUtils.setField(manager, "accessRuleDao", Mockito.mock(StorageAccessRuleDao.class));
        ReflectionTestUtils.setField(manager, "protocolDao", Mockito.mock(StorageServiceProtocolDao.class));
        Mockito.when(service.requiredManagedOperationFeatures(17L)).thenReturn(Set.of());
    }

    private JsonObject manifest(String... features) {
        JsonObject value = new JsonObject();
        JsonArray array = new JsonArray();
        for (String feature : features) array.add(feature);
        value.add("supportedFeatures", array);
        return value;
    }

    @Test
    public void existingReservationRemainsRequiredAfterGlobalOptOut() {
        Mockito.when(service.requiredManagedOperationFeatures(17L)).thenReturn(Set.of("LOGICAL_RESOURCE_RESERVATION"));
        Assert.assertEquals(Set.of("LOGICAL_RESOURCE_RESERVATION"), manager.requiredRuntimeFeatures(instance));
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, manifest()));
        Mockito.verify(service, Mockito.never()).verifyStoragePackageFeatures(17L);
        Mockito.verify(service, Mockito.never()).beginRuntimeOperationControl(Mockito.anyLong(), Mockito.anyBoolean());
    }

    @Test
    public void maintenanceAndReservationMustBothRemainInCandidate() {
        Mockito.when(service.requiredManagedOperationFeatures(17L)).thenReturn(Set.of("LOGICAL_RESOURCE_RESERVATION", "SERVICE_MAINTENANCE"));
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, manifest("LOGICAL_RESOURCE_RESERVATION")));
        manager.requireSignedRuntimeFeatures(instance, manifest("LOGICAL_RESOURCE_RESERVATION", "SERVICE_MAINTENANCE"));
        Mockito.verify(service).verifyStoragePackageFeatures(17L);
    }

    @Test
    public void nullManagedRequirementsCannotSilentlyBecomeOptOut() {
        Mockito.when(service.requiredManagedOperationFeatures(17L)).thenReturn(null);
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, manifest()));
        Mockito.verify(service, Mockito.never()).verifyStoragePackageFeatures(17L);
    }

    @Test
    public void missingDependencyServiceCannotApproveActivation() {
        ReflectionTestUtils.setField(manager, "operationControlService", null);
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, manifest()));
    }

    @Test
    public void nfsPackageProofIsSeparateFromRuntimeBundleContents() {
        Mockito.when(service.requiredStoragePackageFeatures(17L)).thenReturn(Set.of("NFS_VFS_POSIX_ACL"));
        manager.requireSignedRuntimeFeatures(instance, manifest());
        Assert.assertFalse(manager.requiredRuntimeFeatures(instance).contains("NFS_VFS_POSIX_ACL"));
        Mockito.verify(service).verifyStoragePackageFeatures(17L);
    }

    @Test
    public void declaredRuntimeCannotOverrideMissingFreshVfsPackageProof() {
        Mockito.doThrow(new CloudRuntimeException("NFS_VFS_POSIX_ACL unavailable")).when(service).verifyStoragePackageFeatures(17L);
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireSignedRuntimeFeatures(instance, manifest("NFS_VFS_POSIX_ACL")));
        Mockito.verify(service, Mockito.never()).beginRuntimeOperationControl(Mockito.anyLong(), Mockito.anyBoolean());
    }

    @Test
    public void packageFailureAfterEffectPreservesControlledRecovery() {
        StorageServiceRuntimeUpgradeVO upgrade = new StorageServiceRuntimeUpgradeVO();
        ReflectionTestUtils.setField(upgrade, "id", 21L);
        upgrade.setProgress(85);
        StorageServiceRuntimeUpgradeDao upgrades = Mockito.mock(StorageServiceRuntimeUpgradeDao.class);
        Mockito.when(upgrades.update(Mockito.anyLong(), Mockito.any())).thenReturn(true);
        ReflectionTestUtils.setField(manager, "upgradeDao", upgrades);
        Mockito.when(service.beginRuntimeOperationControl(21L, false)).thenReturn("held-forward");
        var scope = manager.beginRuntimeResourceScope(upgrade, false);
        scope.beforeEffect();
        CloudRuntimeException failure = new CloudRuntimeException("VFS proof changed after activation");
        Mockito.doThrow(failure).when(service).verifyStoragePackageFeatures(17L);
        Assert.assertThrows(CloudRuntimeException.class, () -> manager.requireRuntimePackageFeatures(instance));
        manager.failRuntimeControlled(upgrade, scope, failure);
        Assert.assertEquals(StorageServiceRuntimeUpgradeVO.State.MANUAL_RECOVERY, upgrade.getState());
        Assert.assertNull(upgrade.getCompleted());
        Mockito.verify(service).finishManagedOperationControl("held-forward", "RECOVERY_REQUIRED");
        Mockito.verify(service, Mockito.never()).finishManagedOperationControl("held-forward", "COMPLETE");
    }
}
