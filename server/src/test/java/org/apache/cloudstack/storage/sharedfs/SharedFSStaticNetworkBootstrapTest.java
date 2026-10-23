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
package org.apache.cloudstack.storage.sharedfs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandDispatcher;
import org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandResult;
import org.junit.*;
import org.mockito.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SharedFSStaticNetworkBootstrapTest {
    @Mock NicDao nicDao;
    @Mock StorageServiceGuestCommandDispatcher guestCommandDispatcher;
    @Spy @InjectMocks SharedFSServiceImpl service;
    SharedFS fs;
    AtomicLong clock = new AtomicLong();
    List<Integer> timeouts = new ArrayList<>();
    AutoCloseable mocks;

    @Before public void setup() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        fs = mock(SharedFS.class);
        when(fs.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);
        when(fs.getVmId()).thenReturn(7L);
        when(fs.getIpAddress()).thenReturn("192.0.2.10");
        when(fs.getCidr()).thenReturn("192.0.2.0/24");
        NicVO nic = mock(NicVO.class);
        when(nic.getMacAddress()).thenReturn("02:00:00:00:00:01");
        when(nicDao.listByVmId(7L)).thenReturn(List.of(nic));
        doAnswer(x -> clock.get()).when(service).staticNetworkNanoTime();
        doAnswer(x -> { clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(x.getArgument(0))); return null; })
                .when(service).sleepStaticNetworkRetry(anyLong());
    }

    @After public void cleanup() throws Exception {
        Thread.interrupted();
        mocks.close();
    }

    private StorageServiceGuestCommandResult state(String state) {
        return new StorageServiceGuestCommandResult(false, "bootstrap diagnostic",
                "{\"sharedFsBootstrap\":{\"state\":\"" + state + "\",\"units\":{}}}");
    }

    @Test public void dhcpDoesNotInspectNicsOrDispatchOrSleep() {
        when(fs.getNetworkMode()).thenReturn(SharedFS.NetworkMode.DHCP);
        service.configureStaticNetwork(fs);
        verifyNoInteractions(nicDao, guestCommandDispatcher);
        assertEquals(0, clock.get());
    }

    @Test public void actual126SecondBootstrapOutlivesOldRetryCount() {
        when(guestCommandDispatcher.dispatch(any())).thenAnswer(x -> {
            int rpc = x.<org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommand>getArgument(0).getTimeoutSeconds();
            timeouts.add(rpc);
            boolean ready = clock.get() >= TimeUnit.SECONDS.toNanos(126);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
            return ready ? new StorageServiceGuestCommandResult(true, "", "{\"ipAddress\":\"192.0.2.10\"}") : state("PENDING");
        });
        service.configureStaticNetwork(fs);
        assertEquals(43, timeouts.size());
        assertEquals(TimeUnit.SECONDS.toNanos(127), clock.get());
    }

    @Test public void pendingDeadlinePreservesTypedFailureAndBoundsLastRpc() {
        when(guestCommandDispatcher.dispatch(any())).thenAnswer(x -> {
            int rpc = x.<org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommand>getArgument(0).getTimeoutSeconds();
            timeouts.add(rpc);
            clock.addAndGet(TimeUnit.SECONDS.toNanos(rpc));
            return state("PENDING");
        });
        try { service.configureStaticNetwork(fs); fail(); }
        catch (SharedFSServiceImpl.SharedFsBootstrapPendingTimeoutException e) {
            assertTrue(e.getMessage().contains("360 seconds"));
            assertTrue(e.getMessage().contains("preserve the same VM"));
        }
        assertEquals(List.of(60,60,60,60,60,50), timeouts);
        assertEquals(TimeUnit.SECONDS.toNanos(360), clock.get());
    }

    @Test public void terminalBootstrapFailsImmediately() {
        when(guestCommandDispatcher.dispatch(any())).thenReturn(state("FAILED"));
        try { service.configureStaticNetwork(fs); fail(); }
        catch (SharedFSServiceImpl.SharedFsBootstrapPendingTimeoutException wrong) { fail("terminal failure cannot become pending"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("guest bootstrap failed")); }
        verify(guestCommandDispatcher).dispatch(any());
        assertEquals(0, clock.get());
    }

    @Test public void malformedObservationRetainsOrdinaryThirtyAttemptProtection() {
        when(guestCommandDispatcher.dispatch(any())).thenReturn(new StorageServiceGuestCommandResult(false, "bad observation", "{broken"));
        try { service.configureStaticNetwork(fs); fail(); }
        catch (SharedFSServiceImpl.SharedFsBootstrapPendingTimeoutException wrong) { fail(); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("bad observation")); }
        verify(guestCommandDispatcher, times(30)).dispatch(any());
    }

    @Test public void previousDedicatedExit75GuardIsPendingDuringRollingDeploy() {
        when(guestCommandDispatcher.dispatch(any())).thenAnswer(x -> {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
            return new StorageServiceGuestCommandResult(false,
                    "Storage Service command failed with exit code 75: SHAREDFS_BOOTSTRAP_PENDING: waiting", null);
        });
        try { service.configureStaticNetwork(fs); fail(); }
        catch (SharedFSServiceImpl.SharedFsBootstrapPendingTimeoutException expected) { }
    }

    @Test public void interruptStopsWithoutRetryingOrClaimingReadiness() throws Exception {
        when(guestCommandDispatcher.dispatch(any())).thenReturn(state("PENDING"));
        doThrow(new InterruptedException("test interrupt")).when(service).sleepStaticNetworkRetry(anyLong());
        try { service.configureStaticNetwork(fs); fail(); }
        catch (CloudRuntimeException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        verify(guestCommandDispatcher).dispatch(any());
    }
}
