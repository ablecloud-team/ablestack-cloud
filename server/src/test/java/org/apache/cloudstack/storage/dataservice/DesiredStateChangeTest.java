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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.Assert;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.cloud.user.User;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;

public class DesiredStateChangeTest {
    private StorageServiceOperationDao operations;
    private StorageServiceDesiredSnapshot snapshots;
    private DesiredStateChange.Runtime runtime;
    private DesiredStateChange engine;
    private AtomicReference<StorageServiceOperationVO> saved;

    @Before
    public void prepare() {
        User user = mock(User.class); Account account = mock(Account.class);
        when(user.getId()).thenReturn(1L); when(account.getId()).thenReturn(2L);
        CallContext.register(user, account);
        operations = mock(StorageServiceOperationDao.class);
        snapshots = mock(StorageServiceDesiredSnapshot.class);
        runtime = mock(DesiredStateChange.Runtime.class);
        saved = new AtomicReference<>();
        when(operations.listByInstance(7L)).thenReturn(Collections.emptyList());
        when(snapshots.capture(7L)).thenReturn("previous", "current");
        when(operations.persist(any())).thenAnswer(call -> {
            StorageServiceOperationVO operation = call.getArgument(0); saved.set(operation); return operation;
        });
        engine = new DesiredStateChange(operations, snapshots, id -> new DesiredStateChange.WriterLock() {
            public boolean lock(int seconds) { return true; }
            public void unlock() { }
            public void releaseRef() { }
        });
    }

    @After public void clearContext() { CallContext.unregister(); }

    @Test public void verifiesBeforePromotingLastKnownGoodAndReturnsIdempotentResult() {
        Assert.assertEquals("done", engine.execute(7L, "update", "request", 0L, String.class, () -> "done", runtime));
        Assert.assertEquals("COMPLETE", saved.get().getState());
        Assert.assertEquals("current", saved.get().getSnapshotJson());
        Assert.assertEquals("previous", saved.get().getPreviousSnapshotJson());
        verify(runtime).verify();
        when(operations.findByRequest(7L, "update:request")).thenReturn(saved.get());
        Assert.assertEquals("done", engine.execute(7L, "update", "request", 0L, String.class, () -> { throw new AssertionError("duplicate mutation"); }, runtime));
    }

    @Test public void failedApplyRestoresDatabaseAndRuntimeBeforeReturningRolledBack() {
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "update", "request", 0L, String.class,
                () -> { throw new CloudRuntimeException("apply failed"); }, runtime));
        verify(snapshots).restore(7L, "previous");
        verify(runtime).applyPrevious(); verify(runtime).verify();
        Assert.assertEquals("ROLLED_BACK", saved.get().getState());
        Assert.assertNull(saved.get().getSnapshotJson());
    }

    @Test public void failedRollbackRequiresRecoveryAndNeverPromotesLastKnownGood() {
        doThrow(new CloudRuntimeException("runtime restore failed")).when(runtime).applyPrevious();
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "update", "request", 0L, String.class,
                () -> { throw new CloudRuntimeException("apply failed"); }, runtime));
        Assert.assertEquals("RECOVERY_REQUIRED", saved.get().getState());
        Assert.assertNull(saved.get().getSnapshotJson());
    }

    @Test public void rejectedInputWithUnchangedDesiredStateDoesNotRestartHealthyServices() {
        when(snapshots.capture(7L)).thenReturn("previous");
        Assert.assertThrows(CloudRuntimeException.class,()->engine.execute(7L,"network","invalid",0L,String.class,
                ()->{ throw new com.cloud.exception.InvalidParameterValueException("old runtime capability"); },runtime));
        Assert.assertEquals("BLOCKED",saved.get().getState());verify(runtime,never()).applyPrevious();verify(snapshots,never()).restore(anyLong(),anyString());
    }
    @Test public void resourcePreflightFailureNeverMutatesOrRestoresResources() {
        doThrow(new CloudRuntimeException("resource pressure")).when(runtime).preflight();
        Assert.assertThrows(CloudRuntimeException.class, () -> engine.execute(7L, "update", "request", 0L, String.class,
                () -> { throw new AssertionError("unexpected mutation"); }, runtime));
        Assert.assertEquals("BLOCKED", saved.get().getState());
        verify(snapshots, never()).restore(anyLong(), anyString());
    }
}
