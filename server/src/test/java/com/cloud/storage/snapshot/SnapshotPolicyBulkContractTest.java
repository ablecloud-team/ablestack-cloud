// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE for copyright ownership.
// Licensed under the Apache License, Version 2.0 (the "License");
// http://www.apache.org/licenses/LICENSE-2.0 . Distributed AS IS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
package com.cloud.storage.snapshot;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.SnapshotPolicyVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotPolicyDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import org.apache.cloudstack.api.command.user.snapshot.DeleteSnapshotPoliciesCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.util.Arrays;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SnapshotPolicyBulkContractTest {
    private void run(String mode) {
        SnapshotManagerImpl manager = spy(new SnapshotManagerImpl());
        manager._snapshotPolicyDao = mock(SnapshotPolicyDao.class); manager._volsDao = mock(VolumeDao.class); manager._accountMgr = mock(AccountManager.class);
        Account caller = mock(Account.class); CallContext context = mock(CallContext.class); when(context.getCallingAccount()).thenReturn(caller);
        DeleteSnapshotPoliciesCmd cmd = mock(DeleteSnapshotPoliciesCmd.class); when(cmd.getIds()).thenReturn(Arrays.asList(1L, 2L, 3L));
        for (long id = 1; id <= 3; id++) {
            SnapshotPolicyVO policy = mock(SnapshotPolicyVO.class); when(policy.getVolumeId()).thenReturn(id + 10);
            when(manager._snapshotPolicyDao.findById(id)).thenReturn(policy);
            VolumeVO volume = mock(VolumeVO.class); when(manager._volsDao.findByIdIncludingRemoved(id + 10)).thenReturn(volume);
            if (id == 2 && mode.equals("acl")) doThrow(new PermissionDeniedException("denied")).when(manager._accountMgr).checkAccess(caller, null, true, volume);
        }
        doReturn(true).when(manager).deletePolicy(anyLong());
        if (mode.equals("missing")) when(manager._snapshotPolicyDao.findById(2L)).thenReturn(null);
        if (mode.equals("runtime")) doThrow(new InvalidParameterValueException("snapshot job in progress")).when(manager).deletePolicy(2L);
        if (mode.equals("false")) doReturn(false).when(manager).deletePolicy(2L);
        try (MockedStatic<CallContext> current = mockStatic(CallContext.class)) {
            current.when(CallContext::current).thenReturn(context);
            if (mode.equals("missing") || mode.equals("runtime")) assertThrows(InvalidParameterValueException.class, () -> manager.deleteSnapshotPolicies(cmd));
            else if (mode.equals("acl")) assertThrows(PermissionDeniedException.class, () -> manager.deleteSnapshotPolicies(cmd));
            else assertEquals(mode.equals("success"), manager.deleteSnapshotPolicies(cmd));
        }
        if (mode.equals("acl") || mode.equals("missing")) verify(manager, never()).deletePolicy(anyLong());
        else {
            org.mockito.InOrder order = inOrder(manager._accountMgr, manager);
            order.verify(manager._accountMgr, times(3)).checkAccess(eq(caller), isNull(), eq(true), any(VolumeVO.class));
            order.verify(manager).deletePolicy(1L); order.verify(manager).deletePolicy(2L);
            if (mode.equals("success")) order.verify(manager).deletePolicy(3L); else verify(manager, never()).deletePolicy(3L);
        }
    }
    @Test public void missingIdRejectsBeforeAnyDelete() { run("missing"); }
    @Test public void foreignOwnerRejectsBeforeAnyDelete() { run("acl"); }
    @Test public void laterRuntimeExceptionDoesNotUndoEarlierDelete() { run("runtime"); }
    @Test public void laterFalseStopsWithoutDeletingRemaining() { run("false"); }
    @Test public void successValidatesEveryTargetThenDeletesCapturedOrder() { run("success"); }
}
