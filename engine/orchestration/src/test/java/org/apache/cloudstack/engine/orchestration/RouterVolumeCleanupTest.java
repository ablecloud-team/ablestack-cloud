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
package org.apache.cloudstack.engine.orchestration;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;

import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.junit.Before;
import org.junit.Test;

import com.cloud.storage.VolumeVO;
import com.cloud.utils.exception.CloudRuntimeException;

public class RouterVolumeCleanupTest {
    private final VolumeOrchestrator orchestrator = new VolumeOrchestrator();
    private final VolumeVO volume = mock(VolumeVO.class);
    private final VolumeInfo info = mock(VolumeInfo.class);
    private AsyncCallFuture<VolumeApiResult> future;

    @Before
    @SuppressWarnings("unchecked")
    public void setup() {
        orchestrator.volService = mock(VolumeService.class);
        orchestrator.volFactory = mock(VolumeDataFactory.class);
        future = mock(AsyncCallFuture.class);
        when(volume.getId()).thenReturn(12L);
        when(volume.getUuid()).thenReturn("router-root-volume");
        when(orchestrator.volFactory.getVolume(12L)).thenReturn(info);
        when(orchestrator.volService.expungeVolumeAsync(info)).thenReturn(future);
    }

    @Test
    public void successfulRouterExpungeCompletesNormally() throws Exception {
        when(future.get()).thenReturn(new VolumeApiResult(info));
        orchestrator.awaitVolumeExpunge(volume, true);
    }

    @Test(expected = CloudRuntimeException.class)
    public void aCompletedFailedStorageFutureCannotHideTheRouter() throws Exception {
        VolumeApiResult failed = new VolumeApiResult(info);
        failed.setResult("RBD image still has a watcher");
        when(future.get()).thenReturn(failed);
        orchestrator.awaitVolumeExpunge(volume, true);
    }

    @Test(expected = CloudRuntimeException.class)
    public void anExceptionalStorageFutureCannotHideTheRouter() throws Exception {
        when(future.get()).thenThrow(new ExecutionException(new IllegalStateException("storage unavailable")));
        orchestrator.awaitVolumeExpunge(volume, true);
    }

    @Test
    public void interruptIsPreservedWhenRouterCleanupCannotComplete() throws Exception {
        when(future.get()).thenThrow(new InterruptedException("interrupted"));
        try {
            orchestrator.awaitVolumeExpunge(volume, true);
            throw new AssertionError("Incomplete router cleanup must fail");
        } catch (CloudRuntimeException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void otherVmTypesRetainTheExistingCleanupContract() throws Exception {
        VolumeApiResult failed = new VolumeApiResult(info);
        failed.setResult("not a router");
        when(future.get()).thenReturn(failed);
        orchestrator.awaitVolumeExpunge(volume, false);
    }
}
