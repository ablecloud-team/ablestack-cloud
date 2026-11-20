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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;
import com.google.gson.JsonParser;
import org.apache.cloudstack.backup.AblestackNasTakeBackupCommand;
import org.apache.cloudstack.backup.AblestackSharedRawBackup;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;

public class LibvirtAblestackNasDataOperationTimeoutTest {
    @Test public void synchronousRbdWorkerAllowsMultipleExportsWithoutExpandingEachExportsTimeout() {
        var resource = Mockito.mock(LibvirtComputingResource.class);
        Mockito.when(resource.getAbleNasBackupPath()).thenReturn("/scripts/ablestack_nasbackup.sh");
        var helper = Mockito.spy(new LibvirtAblestackNasBackupHelper(resource));
        Mockito.doReturn(List.of("rbd:pool/root", "rbd:pool/data")).when(helper).resolveDiskPaths(anyList(), anyList());
        var pool = Mockito.mock(PrimaryDataStoreTO.class);
        Mockito.when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        var command = new AblestackNasTakeBackupCommand("vm", "vm/point");
        command.setTimeout(43200);
        command.setWait(30);
        command.setMountTimeout(1800);
        command.setVolumePools(List.of(pool, pool));
        command.setVolumePaths(List.of("root", "data"));
        try (var scripts = Mockito.mockStatic(Script.class)) {
            scripts.when(() -> Script.executePipedCommands(anyList(), anyLong())).thenAnswer(invocation -> {
                List<String[]> commands = invocation.getArgument(0);
                List<String> args = Arrays.asList(commands.get(0));
                assertEquals("43200", args.get(args.indexOf("--data-operation-timeout-seconds") + 1));
                long workerTimeout = invocation.getArgument(1);
                assertTrue(workerTimeout > TimeUnit.SECONDS.toMillis(2L * 43200 + 1800));
                return new Pair<>(0, "42");
            });
            assertEquals(Integer.valueOf(0), helper.executeBackup(command).first());
            scripts.verify(() -> Script.executePipedCommands(anyList(), anyLong()));
        }
    }

    @Test public void offlineRawWorkerAllowsEachVolumesFullBudgetAndPreservesTheCapturedDataLimit() throws Exception {
        var resource = Mockito.mock(LibvirtComputingResource.class);
        Mockito.when(resource.getThirdPartyVolumeBackupPath()).thenReturn("/scripts/thirdparty_volume_backup.py");
        var helper = Mockito.spy(new LibvirtAblestackNasBackupHelper(resource));
        var paths = List.of("/primary/root.raw", "/primary/data.raw");
        Mockito.doReturn(paths).when(helper).resolveDiskPaths(anyList(), anyList());
        var command = new AblestackNasTakeBackupCommand("vm", "vm/point");
        command.setBackupJobId("backup-1");
        command.setBackupType("FULL");
        command.setBackupRepoType("NFS");
        command.setBackupRepoAddress("nas:/backup");
        command.setVolumePools(List.of());
        command.setVolumePaths(paths);
        command.setBackupFiles(List.of("root.raw", "data.raw"));
        command.setTimeout(43200);
        command.setWait(30);
        command.setMountTimeout(1800);
        var plan = new AblestackSharedRawBackup.Plan();
        plan.jobId = command.getBackupJobId();
        command.setOfflineGuard(plan);
        try (var scripts = Mockito.mockStatic(Script.class);
                var guards = Mockito.mockStatic(LibvirtAblestackSharedRawGuard.class)) {
            guards.when(() -> LibvirtAblestackSharedRawGuard.paths(resource, plan)).thenReturn(paths);
            scripts.when(() -> Script.executePipedCommands(anyList(), anyLong())).thenAnswer(invocation -> {
                List<String[]> commands = invocation.getArgument(0);
                String[] args = commands.get(0);
                var payload = JsonParser.parseString(Files.readString(Path.of(args[args.length - 1]))).getAsJsonObject();
                assertEquals(43200, payload.get("timeout").getAsInt());
                long workerTimeout = invocation.getArgument(1);
                assertTrue(workerTimeout > TimeUnit.SECONDS.toMillis(2L * 43200 + 1800));
                return new Pair<>(0, "42");
            });
            assertEquals(Integer.valueOf(0), helper.executeBackup(command).first());
            scripts.verify(() -> Script.executePipedCommands(anyList(), anyLong()));
        }
    }

    @Test public void largeBudgetsDoNotOverflowIntoAnEarlyWorkerTimeout() {
        assertTrue(LibvirtAblestackNasBackupHelper.workerTimeoutMillis(Integer.MAX_VALUE, 2, 300)
                > TimeUnit.SECONDS.toMillis(2L * Integer.MAX_VALUE));
    }
}
