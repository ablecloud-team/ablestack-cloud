// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package org.apache.cloudstack.backup;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

@RunWith(MockitoJUnitRunner.class)
public class CloudStackBackupProviderIsolationTest {
    private static final List<String> CLOUDSTACK_PROVIDERS = List.of("nas", "commvault", "netbackup", "veeam", "networker", "kboss", "dummy", "bx");
    @Mock private AblestackBackupOperationGuard ablestackBackupOperationGuard;
    @Mock private BackupVolumeGuard backupVolumeGuard;
    @Mock private VirtualMachineManager virtualMachineManager;
    @Mock private VolumeApiService volumeApiService;
    @Mock private VolumeDao volumeDao;
    @Mock private BackupDao backupDao;
    @InjectMocks private BackupManagerImpl manager;

    @Test public void cloudStackRestoreKeepsItsStateTransitionsWithoutAblestackAdmissionOrLocks() throws Exception {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getHostId()).thenReturn(2L);
        VolumeVO volume = mock(VolumeVO.class);
        when(volumeDao.findIncludingRemovedByInstanceAndType(1L, null)).thenReturn(List.of(volume));
        when(virtualMachineManager.stateTransitTo(vm, VirtualMachine.Event.RestoringRequested, 2L)).thenReturn(true);
        when(volumeApiService.stateTransitTo(volume, Volume.Event.RestoreRequested)).thenReturn(true);
        BackupOffering offering = mock(BackupOffering.class);
        BackupVO backup = mock(BackupVO.class);
        try (MockedStatic<Transaction> transactions = mockStatic(Transaction.class)) {
            transactions.when(() -> Transaction.execute(eq(TransactionLegacy.CLOUD_DB), any(TransactionCallback.class)))
                    .thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(1)).doInTransaction(null));
            for (String provider : CLOUDSTACK_PROVIDERS) {
                when(offering.getProvider()).thenReturn(provider);
                ReflectionTestUtils.invokeMethod(manager, "beginVmRestore", backup, vm, offering);
                ReflectionTestUtils.invokeMethod(manager, "initializeRestoreOperation", 7L, offering, "VM_RESTORE", 1L, null, null);
            }
        }
        verify(virtualMachineManager, times(CLOUDSTACK_PROVIDERS.size())).stateTransitTo(vm, VirtualMachine.Event.RestoringRequested, 2L);
        verify(volumeApiService, times(CLOUDSTACK_PROVIDERS.size())).stateTransitTo(volume, Volume.Event.RestoreRequested);
        verifyNoInteractions(ablestackBackupOperationGuard, backupVolumeGuard, backupDao);
    }

    @Test public void cloudStackBackupSkipsTheNewRecoveryChecks() {
        BackupProvider provider = mock(BackupProvider.class);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        for (String name : CLOUDSTACK_PROVIDERS) {
            when(provider.getName()).thenReturn(name);
            ReflectionTestUtils.invokeMethod(manager, "checkAblestackBackupRecoveryBeforeStart", vm, provider);
        }
        verifyNoInteractions(ablestackBackupOperationGuard, backupVolumeGuard, backupDao);
    }

    @Test public void ablestackRestoreIsStillRejectedBeforeAnyStateChangesAndReleasesItsLock() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(1L);
        BackupVO backup = mock(BackupVO.class);
        when(backup.getId()).thenReturn(7L);
        BackupOffering offering = mock(BackupOffering.class);
        when(offering.getProvider()).thenReturn("ablestack-veeam");
        BackupVolumeGuard.Lease lease = mock(BackupVolumeGuard.Lease.class);
        when(backupVolumeGuard.acquire(1L)).thenReturn(lease);
        doThrow(new CloudRuntimeException("BACKUP_VOLUME_STATE"))
                .when(ablestackBackupOperationGuard).checkRestore(1L, "ablestack-veeam", false, 7L);
        assertThrows(CloudRuntimeException.class, () -> ReflectionTestUtils.invokeMethod(manager, "beginVmRestore", backup, vm, offering));
        verify(lease).close();
        verifyNoInteractions(virtualMachineManager, volumeApiService, volumeDao);
    }
}
