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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import org.apache.cloudstack.backup.AblestackCommvaultTakeBackupCommand;
import org.apache.cloudstack.backup.AblestackNetBackupTakeBackupCommand;
import org.apache.cloudstack.backup.AblestackVeeamTakeBackupCommand;
import org.apache.cloudstack.backup.BackupAnswer;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackVolumeBackupDispatchTest {
    @Test
    public void preparedPlansForAllProvidersUseTheCommonStartFenceAndVolumeWorker() {
        var resource = Mockito.mock(LibvirtComputingResource.class);
        var built = new java.util.concurrent.atomic.AtomicInteger();
        try (var plans = Mockito.mockStatic(LibvirtAblestackVolumeBackupHelper.class, invocation -> {
            built.incrementAndGet();
            return new String[]{"volume-worker"};
        }); var starts = Mockito.mockStatic(LibvirtAblestackBackupStartHelper.class, invocation -> {
            assertEquals("prepared-plan", invocation.getArgument(8));
            java.util.function.Supplier<String[]> worker = invocation.getArgument(9);
            assertEquals("volume-worker", worker.get()[0]);
            return new BackupAnswer(invocation.getArgument(0), true, "accepted");
        }); var runner = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class)) {
            var commvault = new AblestackCommvaultTakeBackupCommand("vm", "/backup");
            commvault.setWaitForCompletion(false);
            commvault.setVolumeStagingManifest("{}");
            commvault.setVolumeBackupStartPlan("prepared-plan");
            assertTrue(new LibvirtAblestackCommvaultTakeBackupCommandWrapper().execute(commvault, resource).getResult());

            var netbackup = new AblestackNetBackupTakeBackupCommand("vm", "/backup");
            netbackup.setWaitForCompletion(false);
            netbackup.setVolumeStagingManifest("{}");
            netbackup.setVolumeBackupStartPlan("prepared-plan");
            assertTrue(new LibvirtAblestackNetBackupTakeBackupCommandWrapper().execute(netbackup, resource).getResult());

            var veeam = new AblestackVeeamTakeBackupCommand("vm", "/backup");
            veeam.setWaitForCompletion(false);
            veeam.setVolumeStagingManifest("{}");
            veeam.setVolumeBackupStartPlan("prepared-plan");
            assertTrue(new LibvirtAblestackVeeamTakeBackupCommandWrapper().execute(veeam, resource).getResult());

            assertEquals(3, built.get());
            runner.verifyNoInteractions();
            Mockito.verifyNoInteractions(resource);
        }
    }

    @Test
    public void missingManifestCannotStartAnyProviderOrFallBackToLibvirt() {
        assertRejected(false);
    }

    @Test
    public void synchronousCommandsCannotStartTheRemovedProviderWorkers() {
        assertRejected(true);
    }

    private void assertRejected(boolean synchronous) {
        var resource = Mockito.mock(LibvirtComputingResource.class);
        try (var plans = Mockito.mockStatic(LibvirtAblestackVolumeBackupHelper.class);
                var runner = Mockito.mockStatic(LibvirtAblestackAsyncBackupRunner.class)) {
            var commvault = new AblestackCommvaultTakeBackupCommand("vm", "/backup");
            commvault.setWaitForCompletion(synchronous);
            commvault.setVolumeStagingManifest(synchronous ? "{}" : null);
            check(new LibvirtAblestackCommvaultTakeBackupCommandWrapper().execute(commvault, resource), synchronous);

            var netbackup = new AblestackNetBackupTakeBackupCommand("vm", "/backup");
            netbackup.setWaitForCompletion(synchronous);
            netbackup.setVolumeStagingManifest(synchronous ? "{}" : null);
            check(new LibvirtAblestackNetBackupTakeBackupCommandWrapper().execute(netbackup, resource), synchronous);

            var veeam = new AblestackVeeamTakeBackupCommand("vm", "/backup");
            veeam.setWaitForCompletion(synchronous);
            veeam.setVolumeStagingManifest(synchronous ? "{}" : null);
            check(new LibvirtAblestackVeeamTakeBackupCommandWrapper().execute(veeam, resource), synchronous);

            plans.verifyNoInteractions();
            runner.verifyNoInteractions();
            Mockito.verifyNoInteractions(resource);
        }
    }

    private void check(Answer answer, boolean synchronous) {
        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().contains(synchronous ? "asynchronous" : "manifest"));
    }
}
