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
package com.cloud.hypervisor.kvm.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import com.cloud.agent.api.VolumeStatsEntry;

public class KvmVolumeStatsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void slowVolumeDoesNotDiscardOtherVolumesOrInventZeroSamples() throws Exception {
        try (MockedStatic<KvmVmOperationGuard> probes = mockStatic(KvmVmOperationGuard.class)) {
            probes.when(() -> KvmVmOperationGuard.probe(anyLong(), any(String[].class)))
                    .thenThrow(new IOException("Probe timeout"))
                    .thenReturn("{\"actual-size\":0,\"virtual-size\":4096}");
            HashMap<String, VolumeStatsEntry> result = KvmVolumeStats.collect("/gfs", List.of("busy", "ready"));
            assertFalse(result.containsKey("busy"));
            assertEquals(1, result.size());
            assertEquals(0, result.get("ready").getPhysicalSize());
            assertEquals(4096, result.get("ready").getVirtualSize());
            probes.verify(() -> KvmVmOperationGuard.probe(1000L, "python3", "-c", KvmVolumeStats.FILE_PROBE, "/gfs", "busy"));
            probes.verify(() -> KvmVmOperationGuard.probe(1000L, "python3", "-c", KvmVolumeStats.FILE_PROBE, "/gfs", "ready"));
        }
    }

    @Test
    public void incompleteAndInvalidSizesAreOmitted() throws Exception {
        try (MockedStatic<KvmVmOperationGuard> probes = mockStatic(KvmVmOperationGuard.class)) {
            probes.when(() -> KvmVmOperationGuard.probe(anyLong(), any(String[].class)))
                    .thenReturn("{\"virtual-size\":4096}", "{\"actual-size\":-1,\"virtual-size\":4096}",
                            "{\"actual-size\":1.5,\"virtual-size\":4096}",
                            "{\"actual-size\":9223372036854775808,\"virtual-size\":4096}");
            assertTrue(KvmVolumeStats.collect("/gfs", List.of("missing", "negative", "fraction", "overflow")).isEmpty());
        }
    }

    @Test
    public void interruptedCollectionPreservesInterruptAndStopsRequestingVolumes() throws Exception {
        try (MockedStatic<KvmVmOperationGuard> probes = mockStatic(KvmVmOperationGuard.class)) {
            probes.when(() -> KvmVmOperationGuard.probe(anyLong(), any(String[].class)))
                    .thenThrow(new InterruptedException("stop collection"));
            assertTrue(KvmVolumeStats.collect("/gfs", List.of("first", "second")).isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
            probes.verify(() -> KvmVmOperationGuard.probe(1000L, "python3", "-c", KvmVolumeStats.FILE_PROBE, "/gfs", "first"));
            probes.verifyNoMoreInteractions();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void fileProbeResolvesNestedVolumeNamesAndUsesReadOnlySharedInspection() throws Exception {
        Path pool = temporary.newFolder("pool").toPath();
        Path nested = Files.createDirectory(pool.resolve("clones"));
        Files.writeString(nested.resolve("disk with spaces"), "volume");
        Process process = probe(pool, "clones/disk with spaces");
        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue());
        assertTrue(output.contains("QEMU_CALLED"));
    }

    @Test
    public void fileProbeRejectsTraversalAndSymlinksOutsideThePool() throws Exception {
        Path pool = temporary.newFolder("pool").toPath();
        Path outside = temporary.newFile("outside").toPath();
        Files.createSymbolicLink(pool.resolve("redirect"), outside);
        for (String locator : List.of("../outside", outside.toString(), "redirect")) {
            Process process = probe(pool, locator);
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(output, process.exitValue() != 0);
            assertFalse(output.contains("QEMU_CALLED"));
        }
    }

    private Process probe(Path pool, String locator) throws IOException {
        Path bin = Files.createTempDirectory(temporary.getRoot().toPath(), "bin");
        Path qemu = bin.resolve("qemu-img");
        Files.writeString(qemu, "#!/bin/sh\n[ \"$1\" = info ] && [ \"$2\" = --force-share ] && [ \"$3\" = --output=json ] || exit 2\nprintf 'QEMU_CALLED\\n'\n");
        Files.setPosixFilePermissions(qemu, PosixFilePermissions.fromString("rwx------"));
        ProcessBuilder builder = new ProcessBuilder("python3", "-c", KvmVolumeStats.FILE_PROBE, pool.toString(), locator)
                .redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        return builder.start();
    }
}
