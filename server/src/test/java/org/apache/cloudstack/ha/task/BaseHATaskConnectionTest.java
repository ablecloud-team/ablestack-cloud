// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package org.apache.cloudstack.ha.task;

import com.cloud.utils.db.TransactionLegacy;
import org.apache.cloudstack.ha.HAResource;
import org.apache.cloudstack.ha.provider.HAProvider;
import org.junit.Test;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BaseHATaskConnectionTest {
    private void borrow(Connection connection) {
        try {
            assertNotNull("Every HA executor must own a DB context", TransactionLegacy.currentTxn());
            Method setter = TransactionLegacy.class.getDeclaredMethod("setConnection", Connection.class);
            setter.setAccessible(true); setter.invoke(TransactionLegacy.currentTxn(), connection);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private BaseHATask task(Long timeout, Connection action, Connection result, boolean actionFailure, boolean resultFailure,
                            CountDownLatch actionClosed) {
        HAProvider provider = mock(HAProvider.class);
        HAResource resource = mock(HAResource.class);
        when(provider.getConfigValue(null, resource)).thenReturn(timeout);
        return new BaseHATask(resource, provider, null, null, null) {
            @Override public boolean performAction() {
                borrow(action);
                if (actionClosed != null) {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
                }
                if (actionFailure) throw new IllegalStateException("action");
                return true;
            }
            @Override public void processResult(boolean success, Throwable error) {
                borrow(result);
                if (resultFailure) throw new IllegalStateException("result");
            }
        };
    }
    private boolean execute(BaseHATask task) throws Exception {
        // Other server suites leave a caller-owned transaction on the JUnit
        // thread. Model the real HA executor using a fresh thread instead.
        FutureTask<Boolean> future = new FutureTask<>(task);
        Thread worker = new Thread(future, "ha-connection-test");
        worker.setDaemon(true); worker.start();
        try { return future.get(15, TimeUnit.SECONDS); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
            if (e.getCause() instanceof Error) throw (Error)e.getCause();
            throw e;
        }
    }
    @Test public void closesBothThreadsOnSuccess() throws Exception {
        Connection action=mock(Connection.class), result=mock(Connection.class);
        assertTrue(execute(task(10L, action, result, false, false, null)));
        verify(action).close(); verify(result).close();
    }
    @Test public void actionFailureStillClosesBothThreads() throws Exception {
        Connection action=mock(Connection.class), result=mock(Connection.class);
        assertFalse(execute(task(10L, action, result, true, false, null)));
        verify(action).close(); verify(result).close();
    }
    @Test public void resultFailureStillClosesBothThreads() throws Exception {
        Connection action=mock(Connection.class), result=mock(Connection.class);
        try { execute(task(10L, action, result, false, true, null)); fail("Expected result failure"); }
        catch (IllegalStateException expected) { assertEquals("result", expected.getMessage()); }
        verify(action).close(); verify(result).close();
    }
    @Test public void timeoutClosesInnerConnectionWhenCancelledTaskExits() throws Exception {
        Connection action=mock(Connection.class), result=mock(Connection.class);
        CountDownLatch closed=new CountDownLatch(1);
        doAnswer(i -> { closed.countDown(); return null; }).when(action).close();
        assertFalse(execute(task(1L, action, result, false, false, closed)));
        assertTrue("Cancelled inner action must release its own connection", closed.await(5, TimeUnit.SECONDS));
        verify(action).close(); verify(result).close();
    }
}
