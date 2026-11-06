// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.backup;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupDetailsDao;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CommandResult;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.storage.Volume;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.UserVmDao;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AblestackRestoreInstanceCleanupTest {
    private final Map<String, String> details = new HashMap<>();
    private final BackupDao backups = mock(BackupDao.class);
    private final BackupDetailsDao detailDao = mock(BackupDetailsDao.class);
    private final BackupOfferingDao offerings = mock(BackupOfferingDao.class);
    private final UserVmDao vms = mock(UserVmDao.class);
    private final VolumeDao volumes = mock(VolumeDao.class);
    private final VirtualMachineManager vmManager = mock(VirtualMachineManager.class);
    private final UserVmManager userVms = mock(UserVmManager.class);
    private final VolumeApiService volumeApi = mock(VolumeApiService.class);
    private final VolumeDataFactory factory = mock(VolumeDataFactory.class);
    private final VolumeDataStoreDao volumeStores = mock(VolumeDataStoreDao.class);
    private final AccountDao accounts = mock(AccountDao.class);
    private final UserVmVO vm = mock(UserVmVO.class);
    private final VolumeVO root = mock(VolumeVO.class);
    private final VolumeVO data = mock(VolumeVO.class);
    private final BackupOfferingVO offering = mock(BackupOfferingVO.class);
    private MockedStatic<GlobalLock> locks;
    private MockedStatic<Transaction> transactions;
    private GlobalLock lock;
    private AblestackRestoreInstanceCleanup cleanup;

    @Before
    public void setup() throws Exception {
        lock = mock(GlobalLock.class);
        when(lock.lock(5)).thenReturn(true);
        locks = Mockito.mockStatic(GlobalLock.class);
        locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
        transactions = Mockito.mockStatic(Transaction.class);
        transactions.when(() -> Transaction.execute(Mockito.<TransactionCallback<Void>>any()))
                .thenAnswer(call -> call.<TransactionCallback<Void>>getArgument(0).doInTransaction(null));
        BackupVO backup = mock(BackupVO.class);
        when(backup.getVmId()).thenReturn(1L);
        when(backup.getAccountId()).thenReturn(5L);
        when(backup.getBackupOfferingId()).thenReturn(4L);
        when(backups.findById(3L)).thenReturn(backup);
        when(offerings.findByIdIncludingRemoved(4L)).thenReturn(offering);
        when(offering.getProvider()).thenReturn("ablestack-nas");
        when(detailDao.findDetail(eq(3L), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return details.containsKey(key) ? new BackupDetailVO(3L, key, details.get(key), false) : null;
        });
        doAnswer(call -> {
            details.put(call.getArgument(1), call.getArgument(2));
            return null;
        }).when(detailDao).addDetail(eq(3L), anyString(), anyString(), eq(false));
        doAnswer(call -> {
            details.remove(call.getArgument(1));
            return null;
        }).when(detailDao).removeDetail(eq(3L), anyString());
        when(vm.getId()).thenReturn(2L);
        when(vm.getUuid()).thenReturn("target-vm");
        when(vm.getAccountId()).thenReturn(5L);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vms.findById(2L)).thenReturn(vm);
        when(vms.findByIdIncludingRemoved(2L)).thenReturn(vm);
        setupVolume(root, 10L, "target-root");
        setupVolume(data, 11L, "target-data");
        when(volumes.findIncludingRemovedByInstanceAndType(2L, null)).thenReturn(List.of(root, data));
        when(userVms.expunge(vm)).thenAnswer(call -> {
            when(vm.getRemoved()).thenReturn(new Date());
            when(vms.findById(2L)).thenReturn(null);
            when(root.getState()).thenReturn(Volume.State.Expunged);
            when(root.getRemoved()).thenReturn(new Date());
            when(data.getInstanceId()).thenReturn(null);
            return true;
        });
        when(volumeApi.deleteVolume(eq(11L), any())).thenAnswer(call -> {
            when(data.getRemoved()).thenReturn(new Date());
            when(data.getState()).thenReturn(Volume.State.Expunged);
            return true;
        });
        cleanup = newService();
    }

    private void setupVolume(VolumeVO volume, long id, String uuid) {
        when(volume.getId()).thenReturn(id);
        when(volume.getUuid()).thenReturn(uuid);
        when(volume.getAccountId()).thenReturn(5L);
        when(volume.getInstanceId()).thenReturn(2L);
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volumes.findByIdIncludingRemoved(id)).thenReturn(volume);
    }

    private AblestackRestoreInstanceCleanup newService() {
        AblestackRestoreInstanceCleanup service = new AblestackRestoreInstanceCleanup();
        ReflectionTestUtils.setField(service, "backupDao", backups);
        ReflectionTestUtils.setField(service, "detailsDao", detailDao);
        ReflectionTestUtils.setField(service, "offeringDao", offerings);
        ReflectionTestUtils.setField(service, "vmDao", vms);
        ReflectionTestUtils.setField(service, "volumeDao", volumes);
        ReflectionTestUtils.setField(service, "vmManager", vmManager);
        ReflectionTestUtils.setField(service, "userVmManager", userVms);
        ReflectionTestUtils.setField(service, "volumeApiService", volumeApi);
        ReflectionTestUtils.setField(service, "volumeFactory", factory);
        ReflectionTestUtils.setField(service, "volumeStoreDao", volumeStores);
        ReflectionTestUtils.setField(service, "accountDao", accounts);
        return service;
    }

    @After
    public void close() {
        transactions.close();
        locks.close();
    }

    @Test
    public void removesDetachedDataVolumeAfterVmIsExpunged() throws Exception {
        cleanup.prepare(3L, 2L);
        assertTrue(cleanup.cleanup(3L, 2L));
        verify(vmManager).destroy("target-vm", true);
        verify(userVms).expunge(vm);
        verify(volumeApi).deleteVolume(eq(11L), any());
        assertFalse(cleanup.isPending(3L));
        assertEquals("CLEANUP_COMPLETED", details.get(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL));
        verify(vms, never()).findById(1L);
    }

    @Test
    public void dataDeleteFailureSurvivesVmRemovalAndManagementRestart() throws Exception {
        cleanup.prepare(3L, 2L);
        doReturn(false).when(volumeApi).deleteVolume(eq(11L), any());
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        assertEquals("CLEANUP_REQUIRED", details.get(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL));
        assertTrue(details.get(AblestackRestoreInstanceCleanup.CONTEXT_KEY).contains("target-data"));
        when(detailDao.findDetails(AblestackRestoreInstanceCleanup.PENDING_KEY)).thenReturn(List.of(
                new BackupDetailVO(3L, AblestackRestoreInstanceCleanup.PENDING_KEY, "2", false)));
        when(volumeApi.deleteVolume(eq(11L), any())).thenAnswer(call -> {
            when(data.getRemoved()).thenReturn(new Date());
            when(data.getState()).thenReturn(Volume.State.Expunged);
            return true;
        });
        newService().reconcilePending();
        assertFalse(cleanup.isPending(3L));
        verify(vmManager, Mockito.times(1)).destroy("target-vm", true);
    }

    @Test
    public void failedVmExpungeIsNotReportedAsCompleted() {
        cleanup.prepare(3L, 2L);
        doReturn(false).when(userVms).expunge(vm);
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        verify(volumeApi, never()).deleteVolume(eq(11L), any());
    }

    @Test
    public void deleteSuccessWithoutTerminalVolumeStateRemainsPending() {
        cleanup.prepare(3L, 2L);
        doReturn(true).when(volumeApi).deleteVolume(eq(11L), any());
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
    }

    @Test
    public void doesNotDeleteVolumeAttachedToAnotherVm() {
        cleanup.prepare(3L, 2L);
        when(vm.getRemoved()).thenReturn(new Date());
        when(data.getInstanceId()).thenReturn(99L);
        when(root.getRemoved()).thenReturn(new Date());
        when(root.getState()).thenReturn(Volume.State.Expunged);
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        verify(volumeApi, never()).deleteVolume(eq(11L), any());
    }

    @Test
    public void changedVolumeUuidIsProtected() {
        cleanup.prepare(3L, 2L);
        when(data.getUuid()).thenReturn("other-volume");
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        verify(volumeApi, never()).deleteVolume(eq(11L), any());
    }

    @Test
    public void originalVmIsNeverAcceptedAsCleanupTarget() {
        when(vms.findById(1L)).thenReturn(vm);
        assertThrows(CloudRuntimeException.class, () -> cleanup.prepare(3L, 1L));
        assertFalse(details.containsKey(AblestackRestoreInstanceCleanup.CONTEXT_KEY));
    }

    @Test
    public void nonAblestackProviderKeepsExistingCleanupPath() {
        when(offering.getProvider()).thenReturn("nas");
        cleanup.prepare(3L, 2L);
        assertFalse(cleanup.cleanup(3L, 2L));
        assertTrue(details.isEmpty());
    }

    @Test
    public void allFourAblestackProvidersRecordTargetResources() {
        for (String provider : List.of("ablestack-nas", "ablestack-commvault", "ablestack-netbackup", "ablestack-veeam")) {
            when(offering.getProvider()).thenReturn(provider);
            cleanup.prepare(3L, 2L);
            assertTrue(details.get(AblestackRestoreInstanceCleanup.CONTEXT_KEY).contains("target-data"));
            cleanup.complete(3L, 2L);
            assertFalse(details.containsKey(AblestackRestoreInstanceCleanup.CONTEXT_KEY));
        }
    }

    @Test
    public void rollbackAndRunningVmPreventDeletion() throws Exception {
        cleanup.prepare(3L, 2L);
        for (VirtualMachine.State state : List.of(VirtualMachine.State.Restoring, VirtualMachine.State.Running)) {
            when(vm.getState()).thenReturn(state);
            cleanup.cleanup(3L, 2L);
            assertTrue(cleanup.isPending(3L));
        }
        verify(vmManager, never()).destroy(anyString(), eq(true));
    }

    @Test
    public void pendingCleanupBlocksNewPreparationAndCannotBeForgotten() {
        cleanup.prepare(3L, 2L);
        doReturn(false).when(userVms).expunge(vm);
        cleanup.cleanup(3L, 2L);
        cleanup.complete(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        assertThrows(CloudRuntimeException.class, () -> cleanup.prepare(3L, 2L));
    }

    @Test
    public void includesVolumesCreatedDuringFailedPreparation() {
        when(volumes.findIncludingRemovedByInstanceAndType(2L, null)).thenReturn(Collections.emptyList());
        cleanup.prepare(3L, 2L);
        when(volumes.findIncludingRemovedByInstanceAndType(2L, null)).thenReturn(List.of(root, data));
        cleanup.cleanup(3L, 2L);
        verify(volumeApi).deleteVolume(eq(11L), any());
        assertFalse(cleanup.isPending(3L));
    }

    @Test
    public void removedExpungingVolumeIsRetriedAgainstStorage() {
        prepareRemovedData(true);
        cleanup.cleanup(3L, 2L);
        assertFalse(cleanup.isPending(3L));
        verify(volumeApi, never()).deleteVolume(eq(11L), any());
        verify(volumes).updateState(eq(Volume.State.Expunging), eq(Volume.Event.OperationSucceeded),
                eq(Volume.State.Expunged), eq(data), any());
    }

    @Test
    public void removedExpungingVolumeStorageFailureRemainsPending() {
        prepareRemovedData(false);
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        verify(volumes, never()).updateState(any(), any(), any(), any(), any());
    }

    @Test
    public void pendingRetryDoesNotAdoptNewlyAttachedVolumes() throws Exception {
        cleanup.prepare(3L, 2L);
        doReturn(false).when(userVms).expunge(vm);
        cleanup.cleanup(3L, 2L);
        VolumeVO other = mock(VolumeVO.class);
        when(other.getId()).thenReturn(99L);
        when(other.getUuid()).thenReturn("unrelated-data");
        when(volumes.findIncludingRemovedByInstanceAndType(2L, null)).thenReturn(List.of(root, data, other));
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        assertFalse(details.get(AblestackRestoreInstanceCleanup.CONTEXT_KEY).contains("unrelated-data"));
        verify(userVms, Mockito.times(1)).expunge(vm);
    }

    @Test
    public void missingStorageForRemovedRowIsNotDeletionProof() {
        cleanup.prepare(3L, 2L);
        when(vm.getRemoved()).thenReturn(new Date());
        when(root.getRemoved()).thenReturn(new Date());
        when(root.getState()).thenReturn(Volume.State.Expunged);
        when(data.getRemoved()).thenReturn(new Date());
        when(data.getState()).thenReturn(Volume.State.Expunging);
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        assertTrue(details.get(AblestackRestoreInstanceCleanup.REASON_KEY).contains("storage is unavailable"));
    }

    @Test
    public void removedDestroyedRootWithoutAnyStorageCompletesCleanup() throws Exception {
        prepareRemovedUnprovisionedRoot();
        cleanup.cleanup(3L, 2L);
        assertFalse(cleanup.isPending(3L));
        assertEquals("CLEANUP_COMPLETED", details.get(AblestackBackupFrameworkUtils.RESTORE_JOB_STEP_DETAIL));
        verify(factory, never()).getVolume(10L);
        verify(volumeApi, never()).deleteVolume(eq(10L), any());
        verify(vmManager, never()).destroy(anyString(), eq(true));
    }

    @Test
    public void removedDestroyedRootWithSecondaryStorageRemainsPending() {
        prepareRemovedUnprovisionedRoot();
        when(volumeStores.findByVolume(10L)).thenReturn(mock(VolumeDataStoreVO.class));
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
        assertTrue(details.get(AblestackRestoreInstanceCleanup.REASON_KEY).contains("storage is unavailable"));
    }

    @Test
    public void removedDestroyedRootWithPoolButNoPathRemainsPending() {
        prepareRemovedUnprovisionedRoot();
        when(root.getPoolId()).thenReturn(8L);
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
    }

    @Test
    public void removedDestroyedRootWithPathButNoPoolRemainsPending() {
        prepareRemovedUnprovisionedRoot();
        when(root.getPath()).thenReturn("restore-root.qcow2");
        cleanup.cleanup(3L, 2L);
        assertTrue(cleanup.isPending(3L));
    }

    private void prepareRemovedUnprovisionedRoot() {
        cleanup.prepare(3L, 2L);
        when(vm.getRemoved()).thenReturn(new Date());
        when(root.getRemoved()).thenReturn(new Date());
        when(root.getState()).thenReturn(Volume.State.Destroy);
        when(root.getPoolId()).thenReturn(null);
        when(data.getRemoved()).thenReturn(new Date());
        when(data.getState()).thenReturn(Volume.State.Expunged);
    }

    @Test
    public void lockContentionKeepsADurableCleanupRequest() {
        cleanup.prepare(3L, 2L);
        when(lock.lock(5)).thenReturn(false);
        assertThrows(CloudRuntimeException.class, () -> cleanup.cleanup(3L, 2L));
        assertTrue(cleanup.isPending(3L));
        when(lock.lock(5)).thenReturn(true);
        cleanup.cleanup(3L, 2L);
        assertFalse(cleanup.isPending(3L));
    }

    @Test
    public void repeatedCompletedCleanupDoesNotRecreateTheQueue() throws Exception {
        cleanup.prepare(3L, 2L);
        cleanup.cleanup(3L, 2L);
        cleanup.cleanup(3L, 2L);
        assertFalse(cleanup.isPending(3L));
        verify(vmManager, Mockito.times(1)).destroy("target-vm", true);
    }

    private void prepareRemovedData(boolean success) {
        cleanup.prepare(3L, 2L);
        when(vm.getRemoved()).thenReturn(new Date());
        when(root.getRemoved()).thenReturn(new Date());
        when(root.getState()).thenReturn(Volume.State.Expunged);
        when(data.getRemoved()).thenReturn(new Date());
        when(data.getState()).thenReturn(Volume.State.Expunging);
        when(data.getPath()).thenReturn("target-data");
        VolumeInfo info = mock(VolumeInfo.class);
        DataStore store = mock(DataStore.class);
        DataStoreDriver driver = mock(DataStoreDriver.class);
        when(factory.getVolume(11L)).thenReturn(info);
        when(info.getDataStore()).thenReturn(store);
        when(store.getRole()).thenReturn(DataStoreRole.Primary);
        when(store.getDriver()).thenReturn(driver);
        doAnswer(call -> {
            AsyncCompletionCallback<CommandResult> callback = call.getArgument(2);
            CommandResult result = new CommandResult();
            if (!success) { result.setResult("storage unavailable"); }
            callback.complete(result);
            return null;
        }).when(driver).deleteAsync(eq(store), eq(info), any());
        when(volumes.updateState(eq(Volume.State.Expunging), eq(Volume.Event.OperationSucceeded),
                eq(Volume.State.Expunged), eq(data), any())).thenAnswer(call -> {
                    when(data.getState()).thenReturn(Volume.State.Expunged);
                    return true;
                });
    }
}

