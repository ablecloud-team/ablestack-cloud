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

import java.util.concurrent.atomic.AtomicInteger;

import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.AblestackCommvaultTakeBackupCommand;
import org.apache.cloudstack.backup.AblestackDeleteBackupCommand;
import org.apache.cloudstack.backup.AblestackNetBackupTakeBackupCommand;
import org.apache.cloudstack.backup.AblestackVeeamTakeBackupCommand;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

public class LibvirtAblestackBackupTimeoutTest {
    @Test
    public void providerDeletionUsesTheCleanupScriptAndPreservesSourceMetadata() {
        var resource = Mockito.mock(LibvirtComputingResource.class);
        Mockito.when(resource.getThirdPartyVolumeBackupPath()).thenReturn("/scripts/thirdparty_volume_backup.py");
        for (String provider : new String[]{"ablestack-commvault", "ablestack-netbackup", "ablestack-veeam"}) {
            var command = new AblestackDeleteBackupCommand("/stage/backup", "NFS", "nas:/backup", "", false);
            command.setBackupProvider(provider);
            command.setVmName("vm");
            command.setCheckpointName("checkpoint");
            command.setCleanupCheckpointNames("retired");
            command.setDiskPaths("rbd:pool/image");
            command.setTimeout(43200);
            try (var cleanup = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class);
                    var scripts = Mockito.mockStatic(Script.class)) {
                scripts.when(() -> Script.executePipedCommands(anyList(), eq(43200000L))).thenAnswer(invocation -> {
                    java.util.List<String[]> calls = invocation.getArgument(0);
                    assertEquals(1, calls.size());
                    java.util.List<String> arguments = java.util.Arrays.asList(calls.get(0));
                    assertEquals(java.util.List.of("/scripts/thirdparty_backup_cleanup.sh", "--provider",
                            provider,
                            "-p", "/stage/backup", "-v", "vm", "-x", "false", "-c", "checkpoint", "-C", "retired",
                            "-d", "rbd:pool/image"), arguments);
                    return new Pair<>(0, "Deleted");
                });
                assertTrue(new LibvirtAblestackDeleteBackupCommandWrapper().execute(command, resource).getResult());
            }
        }
    }

    @Test
    public void cloudStackVeeamCannotUseAblestackCleanupOrFallBackToNas() {
        var resource = Mockito.mock(LibvirtComputingResource.class);
        for (String provider : new String[]{"veeam", "VEEAM", "other-provider"}) {
            var command = new AblestackDeleteBackupCommand("/stage/backup", "NFS", "nas:/backup", "", false);
            command.setBackupProvider(provider);
            command.setTimeout(43200);
            try (var cleanup = Mockito.mockStatic(LibvirtAblestackStagingCleanup.class);
                    var scripts = Mockito.mockStatic(Script.class)) {
                var answer = new LibvirtAblestackDeleteBackupCommandWrapper().execute(command, resource);
                assertFalse(answer.getResult());
                assertEquals("Unsupported ABLESTACK backup provider: " + provider, answer.getDetails());
                cleanup.verifyNoInteractions();
                scripts.verifyNoInteractions();
                Mockito.verifyNoInteractions(resource);
            }
        }
    }

    @Test
    public void stagingWorkersReceiveDataTimeoutInsteadOfResponseOverride() {
        var resource = Mockito.mock(LibvirtComputingResource.class);
        AtomicInteger built = new AtomicInteger();
        try (var workers = Mockito.mockStatic(LibvirtAblestackVolumeBackupHelper.class, invocation -> {
            if (invocation.getArguments().length == 2) {
                return invocation.callRealMethod();
            }
            assertEquals(43200, invocation.<Integer>getArgument(9).intValue());
            built.incrementAndGet();
            return new String[] {"worker"};
        })) {
            var commvault = new AblestackCommvaultTakeBackupCommand("vm", "backup");
            commvault.setVolumeStagingManifest("{}");
            commvault.setTimeout(43200);
            commvault.setWait(30);
            LibvirtAblestackVolumeBackupHelper.buildCommand(resource, commvault);

            var netbackup = new AblestackNetBackupTakeBackupCommand("vm", "backup");
            netbackup.setVolumeStagingManifest("{}");
            netbackup.setTimeout(43200);
            netbackup.setWait(30);
            LibvirtAblestackVolumeBackupHelper.buildCommand(resource, netbackup);

            var veeam = new AblestackVeeamTakeBackupCommand("vm", "backup");
            veeam.setVolumeStagingManifest("{}");
            veeam.setTimeout(43200);
            veeam.setWait(30);
            LibvirtAblestackVolumeBackupHelper.buildCommand(resource, veeam);
        }
        assertEquals(3, built.get());
    }

    @Test
    public void dataDeletionKeepsItsExecutionBudgetAndReportsTimeoutAsUnconfirmedFailure() {
        var resource = Mockito.mock(LibvirtComputingResource.class);
        var command = new AblestackDeleteBackupCommand("backup", "NFS", "nas:/backup", "", false);
        command.setTimeout(43200);
        command.setWait(30);
        try (var scripts = Mockito.mockStatic(Script.class)) {
            scripts.when(() -> Script.executePipedCommands(anyList(), eq(43200000L)))
                    .thenReturn(new Pair<>(0, "Deleted"));
            var wrapper = new LibvirtAblestackDeleteBackupCommandWrapper();
            assertTrue(wrapper.execute(command, resource).getResult());

            scripts.when(() -> Script.executePipedCommands(anyList(), eq(43200000L)))
                    .thenReturn(new Pair<>(-1, "timeout"));
            var timedOut = wrapper.execute(command, resource);
            assertFalse(timedOut.getResult());
            assertEquals("timeout", timedOut.getDetails());
        }
    }
}
