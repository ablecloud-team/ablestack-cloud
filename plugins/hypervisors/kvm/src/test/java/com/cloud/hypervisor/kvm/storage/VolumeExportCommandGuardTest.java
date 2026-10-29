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
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import com.cloud.utils.exception.CloudRuntimeException;

public class VolumeExportCommandGuardTest {
    @Test public void shrinkingFreeSpaceTerminatesActualSubprocess() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        try { Gfs2AllocationCommandRunner.run(List.of("sleep","30"),5000,() -> polls.incrementAndGet() < 2); fail("must abort"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("CAPACITY_INSUFFICIENT")); }
        assertEquals(2,polls.get());
    }
    @Test public void actualDeadlineTerminatesCommand() throws Exception {
        try { Gfs2AllocationCommandRunner.run(List.of("sleep","30"),100,() -> true); fail("must timeout"); }
        catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("TIMEOUT")); }
    }
    @Test public void existingUnrelatedFileSurvivesFailedCommand() throws Exception {
        Path sentinel = Files.createTempFile("export-sentinel-", ".txt"); Files.writeString(sentinel,"keep");
        try {
            try { Gfs2AllocationCommandRunner.run(List.of("false"),5000); fail("must fail"); }
            catch (CloudRuntimeException e) { assertTrue(e.getMessage().contains("COMMAND_FAILED")); }
            assertEquals("keep",Files.readString(sentinel));
        } finally { Files.deleteIfExists(sentinel); }
    }
}
