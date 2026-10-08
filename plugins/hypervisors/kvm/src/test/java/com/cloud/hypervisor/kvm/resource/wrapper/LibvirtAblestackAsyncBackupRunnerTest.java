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

import com.cloud.utils.Pair;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackAsyncBackupRunnerTest {
    @Test
    public void hungControlCommandReturnsUnconfirmed() {
        long started = System.nanoTime();
        Pair<Integer, String> result = LibvirtAblestackAsyncBackupRunner.executeAndCapture(150, "/bin/sh", "-c", "sleep 5");
        assertEquals(Integer.valueOf(124), result.first());
        assertTrue(result.second().contains("unconfirmed"));
        assertTrue("Control call must not wait for the data-operation budget", System.nanoTime() - started < 3000000000L);
    }

    @Test
    public void exitedCommandDoesNotWaitForChildToCloseOutput() {
        Pair<Integer, String> result = LibvirtAblestackAsyncBackupRunner.executeAndCapture(2000, "/bin/sh", "-c", "sleep 1 & printf accepted");
        assertEquals(Integer.valueOf(0), result.first());
        assertEquals("accepted", result.second());
    }

    @Test
    public void controlErrorsRetainExitCodeAndDiagnostics() {
        Pair<Integer, String> result = LibvirtAblestackAsyncBackupRunner.executeAndCapture(2000, "/bin/sh", "-c", "printf unavailable >&2; exit 7");
        assertEquals(Integer.valueOf(7), result.first());
        assertEquals("unavailable", result.second());
    }

    @Test
    public void successfulAbortIsNotProofThatDomainJobStopped() {
        assertFalse(LibvirtAblestackAsyncBackupRunner.hasNoDomainJob(new Pair<>(0, "Job type: Unbounded\nOperation: Backup")));
        assertFalse(LibvirtAblestackAsyncBackupRunner.hasNoDomainJob(new Pair<>(124, "Job type: None")));
        assertFalse(LibvirtAblestackAsyncBackupRunner.hasNoDomainJob(new Pair<>(0, "")));
        assertTrue(LibvirtAblestackAsyncBackupRunner.hasNoDomainJob(new Pair<>(0, "Job type: None\n")));
    }
}
