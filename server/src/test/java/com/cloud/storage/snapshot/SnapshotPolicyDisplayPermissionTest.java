// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for ownership information.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import java.util.Collections;
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
import org.apache.cloudstack.context.CallContext;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SnapshotPolicyDisplayPermissionTest {
    private void edit(boolean preserve, boolean previous, boolean requested, boolean expected, boolean exists) {
        try (MockedStatic<Transaction> tx = mockStatic(Transaction.class);
             MockedStatic<GlobalLock> locks = mockStatic(GlobalLock.class);
             MockedStatic<CallContext> contexts = mockStatic(CallContext.class)) {
            tx.when(() -> Transaction.execute(any(TransactionCallback.class))).thenAnswer(i -> ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
            GlobalLock lock = mock(GlobalLock.class);
            locks.when(() -> GlobalLock.getInternLock(anyString())).thenReturn(lock);when(lock.lock(5)).thenReturn(true);
            contexts.when(CallContext::current).thenReturn(mock(CallContext.class));
            SnapshotManagerImpl manager = spy(new SnapshotManagerImpl());
            manager._snapshotPolicyDao = mock(SnapshotPolicyDao.class);manager._volsDao = mock(VolumeDao.class);
            manager._snapSchedMgr = mock(SnapshotScheduler.class);manager.taggedResourceService = mock(TaggedResourceService.class);
            VolumeVO volume = mock(VolumeVO.class);when(volume.getId()).thenReturn(10L);when(volume.getState()).thenReturn(Volume.State.Ready);
            when(manager._volsDao.findById(10L)).thenReturn(volume);
            SnapshotPolicyVO policy = spy(new SnapshotPolicyVO(10, "5", "UTC", IntervalType.HOURLY, 2, 2, 1, previous));doReturn(20L).when(policy).getId();
            if (exists) {
                when(manager._snapshotPolicyDao.findOneByVolumeInterval(10L, IntervalType.HOURLY)).thenReturn(policy);
                when(manager._snapshotPolicyDao.lockRow(20L, true)).thenReturn(policy);
            } else {
                policy.setDisplay(requested);
                doReturn(policy).when(manager).createSnapshotPolicy(10L, "5", "UTC", IntervalType.HOURLY, 3, requested, null, null);
            }
            when(manager._snapshotPolicyDao.update(anyLong(), any())).thenReturn(true);
            SnapshotPolicyVO result = manager.persistSnapshotPolicy(volume, "5", "UTC", IntervalType.HOURLY, 3,
                    requested, true, Collections.emptyMap(), null, null, exists ? 20L : null, preserve);
            assertSame(policy, result);assertEquals(expected, policy.isDisplay());
            if (exists && expected == previous) verifyNoInteractions(manager._snapSchedMgr);
            if (exists && expected != previous) verify(manager._snapSchedMgr).scheduleNextSnapshotJob(policy);
            verify(lock).unlock();verify(lock).releaseRef();
        }
    }
    @Test public void ownerEditCannotResumeAdministratorPausedPolicy() { edit(true, false, true, false, true); }
    @Test public void ownerEditCannotPauseEnabledPolicy() { edit(true, true, false, true, true); }
    @Test public void rootCanExplicitlyResumePolicy() { edit(false, false, true, true, true); }
    @Test public void rootCanExplicitlyPausePolicy() { edit(false, true, false, false, true); }
    @Test public void newOwnerPolicyRetainsNormalEnabledCreateDefault() { edit(true, false, true, true, false); }
}
