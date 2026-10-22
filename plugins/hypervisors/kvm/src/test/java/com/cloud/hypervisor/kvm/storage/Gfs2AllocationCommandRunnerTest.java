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
package com.cloud.hypervisor.kvm.storage;

import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Gfs2AllocationCommandRunnerTest {
    private long temporaryFiles() throws Exception {
        try (var paths=Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("cloudstack-allocation-command-")).count();
        }
    }
    @Test public void successfulOutputIsReadAfterExitWithoutReaderRace() throws Exception {
        long before=temporaryFiles();
        for (int i=0;i<20;i++) assertEquals("verified\n",Gfs2AllocationCommandRunner.run(List.of("/bin/sh","-c","printf 'verified\n'"),2000));
        assertEquals(before,temporaryFiles());
    }
    @Test public void nonzeroExitPreservesOriginalComparisonErrorAndCleansOutput() throws Exception {
        long before=temporaryFiles();
        try { Gfs2AllocationCommandRunner.run(List.of("/bin/sh","-c","printf 'original-compare-error' >&2; exit 7"),2000);fail("Expected failure"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("exit=7 original-compare-error")); }
        assertEquals(before,temporaryFiles());
    }
    @Test public void timeoutTerminatesOwnedCommandAndCleansOutput() throws Exception {
        long before=temporaryFiles();long start=System.nanoTime();
        try { Gfs2AllocationCommandRunner.run(List.of("/bin/sh","-c","exec sleep 10"),50);fail("Expected timeout"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("GFS2_ALLOCATION_TIMEOUT")); }
        assertTrue(System.nanoTime()-start<3_000_000_000L);assertEquals(before,temporaryFiles());
    }
    @Test public void missingCommandDoesNotLeaveTemporaryOutput() throws Exception {
        long before=temporaryFiles();
        try { Gfs2AllocationCommandRunner.run(List.of("/nonexistent/volume-allocation-command"),1000);fail("Expected failure"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("GFS2_ALLOCATION_COMMAND_FAILED")); }
        assertEquals(before,temporaryFiles());
    }
}
