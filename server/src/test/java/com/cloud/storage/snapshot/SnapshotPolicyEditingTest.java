// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import java.util.Collections;
import java.util.List;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.server.TaggedResourceService;
import com.cloud.storage.SnapshotPolicyVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotPolicyDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.DateUtil.IntervalType;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcedetail.SnapshotPolicyDetailVO;
import org.apache.cloudstack.resourcedetail.dao.SnapshotPolicyDetailsDao;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SnapshotPolicyEditingTest {
    private void guardedEdit(Long actualId, Long expectedId) {
        try (MockedStatic<Transaction> tx = mockStatic(Transaction.class);
             MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class);
             MockedStatic<CallContext> contexts = mockStatic(CallContext.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallback.class)))
                    .thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
            GlobalLock lock = mock(GlobalLock.class);
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);
            when(lock.lock(5)).thenReturn(true);
            contexts.when(CallContext::current).thenReturn(mock(CallContext.class));
            SnapshotManagerImpl manager = new SnapshotManagerImpl();
            SnapshotPolicyDao policies = mock(SnapshotPolicyDao.class);
            manager._snapshotPolicyDao = policies;
            manager._volsDao = mock(VolumeDao.class);
            manager._snapSchedMgr = mock(SnapshotScheduler.class);
            manager.taggedResourceService = mock(TaggedResourceService.class);
            VolumeVO volume = mock(VolumeVO.class);
            when(volume.getId()).thenReturn(10L);when(volume.getState()).thenReturn(Volume.State.Ready);
            when(manager._volsDao.findById(10L)).thenReturn(volume);
            SnapshotPolicyVO policy = spy(new SnapshotPolicyVO(10, "5", "UTC", IntervalType.HOURLY, 3, 2, 1, true));
            if (actualId != null) {
                doReturn(actualId.longValue()).when(policy).getId();
                when(policies.findOneByVolumeInterval(10L, IntervalType.HOURLY)).thenReturn(policy);
                when(policies.lockRow(actualId, true)).thenReturn(policy);
            }
            when(policies.update(anyLong(), any())).thenReturn(true);
            try {
                SnapshotPolicyVO result = manager.persistSnapshotPolicy(volume, "15", "UTC", IntervalType.HOURLY,
                        3, true, true, null, null, null, expectedId);
                assertEquals(actualId.longValue(), result.getId());
                verify(policies, never()).persist(any());
                verify(policies).update(actualId, policy);
            } catch (InvalidParameterValueException error) {
                assertNotEquals(actualId, expectedId);
                verify(policies, never()).persist(any());verify(policies, never()).update(anyLong(), any());
                verifyNoInteractions(manager._snapSchedMgr, manager.taggedResourceService);
                throw error;
            } finally {
                verify(lock).unlock();verify(lock).releaseRef();
            }
        }
    }

    @Test public void expectedPolicyUpdatesSameLockedRow() { guardedEdit(20L, 20L); }
    @Test(expected = InvalidParameterValueException.class)
    public void removedPolicyNeverBecomesNewCreate() { guardedEdit(null, 20L); }
    @Test(expected = InvalidParameterValueException.class)
    public void replacementPolicyIsNotEditedUsingOldId() { guardedEdit(21L, 20L); }

    @Test public void clearingAdditionalZonesPreservesStorageAndOtherDetails() {
        SnapshotManagerImpl manager = new SnapshotManagerImpl();
        manager._snapshotPolicyDao = mock(SnapshotPolicyDao.class);
        manager.snapshotPolicyDetailsDao = mock(SnapshotPolicyDetailsDao.class);
        manager.taggedResourceService = mock(TaggedResourceService.class);
        manager._snapSchedMgr = mock(SnapshotScheduler.class);
        SnapshotPolicyVO policy = spy(new SnapshotPolicyVO(10, "5", "UTC", IntervalType.HOURLY, 3, 2, 1, true));
        doReturn(20L).when(policy).getId();when(manager._snapshotPolicyDao.update(anyLong(), any())).thenReturn(true);
        SnapshotPolicyDetailVO storage = new SnapshotPolicyDetailVO(20, ApiConstants.STORAGE_ID, "3");
        SnapshotPolicyDetailVO other = new SnapshotPolicyDetailVO(20, "other", "kept");
        when(manager.snapshotPolicyDetailsDao.listDetails(20L)).thenReturn(List.of(
                new SnapshotPolicyDetailVO(20, ApiConstants.ZONE_ID, "2"), storage, other));
        manager.updateSnapshotPolicy(policy, "5", "UTC", IntervalType.HOURLY, 3, true, true, Collections.emptyList(), null);
        verify(manager.snapshotPolicyDetailsDao).removeDetail(20L, ApiConstants.ZONE_ID);
        verify(manager.snapshotPolicyDetailsDao, never()).removeDetails(anyLong());
        verify(manager.snapshotPolicyDetailsDao, never()).saveDetails(anyList());
        verify(manager.snapshotPolicyDetailsDao, never()).persist(any());
        verifyNoInteractions(manager._snapSchedMgr);
    }

    @Test public void omittedZonesDoNotRewriteExistingDetails() {
        SnapshotManagerImpl manager = new SnapshotManagerImpl();
        manager._snapshotPolicyDao = mock(SnapshotPolicyDao.class);manager.snapshotPolicyDetailsDao = mock(SnapshotPolicyDetailsDao.class);
        manager.taggedResourceService = mock(TaggedResourceService.class);manager._snapSchedMgr = mock(SnapshotScheduler.class);
        SnapshotPolicyVO policy = spy(new SnapshotPolicyVO(10, "5", "UTC", IntervalType.HOURLY, 3, 2, 1, true));
        doReturn(20L).when(policy).getId();when(manager._snapshotPolicyDao.update(anyLong(), any())).thenReturn(true);
        manager.updateSnapshotPolicy(policy, "5", "UTC", IntervalType.HOURLY, 4, true, true, null, null);
        verifyNoInteractions(manager.snapshotPolicyDetailsDao, manager._snapSchedMgr);
    }
}
