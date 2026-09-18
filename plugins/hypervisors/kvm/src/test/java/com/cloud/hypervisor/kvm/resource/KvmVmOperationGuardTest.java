//
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
//

package com.cloud.hypervisor.kvm.resource;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.io.IOException;

public class KvmVmOperationGuardTest {
    @Test public void operationBlocksShellAndMonitoringAndReleases() throws Exception {
        Path root = Files.createTempDirectory("vm-guard"); String uuid = UUID.randomUUID().toString();
        try (KvmVmOperationGuard operation = new KvmVmOperationGuard(root, uuid, "snapshot", false)) {
            Process shell = new ProcessBuilder("flock", "-n", root.resolve("locks/" + uuid + ".lock").toString(), "true").start();
            assertEquals(1, shell.waitFor());
            try { new KvmVmOperationGuard(root, uuid, "stats", true); fail("monitoring admitted during snapshot"); }
            catch (IOException expected) { }
            assertEquals(1, entryCount(root.resolve(uuid)));
        }
        assertEquals(0, entryCount(root.resolve(uuid)));
        try (KvmVmOperationGuard monitor = new KvmVmOperationGuard(root, uuid, "stats", true)) {
            assertEquals(0, entryCount(root.resolve(uuid)));
        }
    }
    @Test public void unknownLeaseIsNotExpiredAway() throws Exception {
        Path root = Files.createTempDirectory("vm-guard"); String uuid = UUID.randomUUID().toString();
        try (KvmVmOperationGuard operation = new KvmVmOperationGuard(root, uuid, "restore", false)) { operation.uncertain(); }
        try { new KvmVmOperationGuard(root, uuid, "stats", true); fail("orphan admitted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unreconciled")); }
    }
    @Test public void symlinkRootIsRefused() throws Exception {
        Path parent = Files.createTempDirectory("vm-guard"); Path link = parent.resolve("link");
        Files.createSymbolicLink(link, parent);
        try { new KvmVmOperationGuard(link, UUID.randomUUID().toString(), "stats", true); fail("symlink accepted"); }
        catch (IOException expected) { }
    }
    @Test public void repeatedTimeoutsReapChildrenAndDoNotSubmitBackgroundTasks() throws Exception {
        for (int i = 0; i < 12; i++) {
            Path pidFile = Files.createTempFile("monitor-child", ".pid");
            try {
                KvmVmOperationGuard.probe(200, "sh", "-c", "echo $$ > \"$1\"; exec sleep 10", "sh", pidFile.toString());
                fail("timeout expected");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("timeout")); }
            String pid = Files.readString(pidFile).trim();
            if (!pid.isEmpty()) assertFalse("probe child still alive: " + pid,
                    ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false));
            Files.delete(pidFile);
        }
        assertEquals("recovered", KvmVmOperationGuard.probe(1000, "printf", "recovered"));
    }

    private long entryCount(Path path) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(path)) { return entries.count(); }
    }

    @Test public void renewalAdvancesAndCloseCancelsScheduledJob() throws Exception {
        Path root = Files.createTempDirectory("vm-guard"); String uuid = UUID.randomUUID().toString();
        java.lang.reflect.Field field = KvmVmOperationGuard.class.getDeclaredField("RENEWER");
        field.setAccessible(true);
        java.util.concurrent.ScheduledThreadPoolExecutor scheduler =
                (java.util.concurrent.ScheduledThreadPoolExecutor) field.get(null);
        int before = scheduler.getQueue().size();
        Path lease;
        try (KvmVmOperationGuard operation = new KvmVmOperationGuard(root, uuid, "snapshot", false)) {
            try (java.util.stream.Stream<Path> files = Files.list(root.resolve(uuid))) { lease = files.findFirst().get(); }
            String initial = Files.readString(lease);
            Thread.sleep(5200);
            assertNotEquals(initial, Files.readString(lease));
            assertEquals(before + 1, scheduler.getQueue().size());
        }
        assertEquals(before, scheduler.getQueue().size());
        assertFalse(Files.exists(lease));
    }

    @Test public void interruptedProbeReapsAndPreservesInterrupt() throws Exception {
        long children = ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count();
        Thread.currentThread().interrupt();
        try { KvmVmOperationGuard.probe(5000, "sleep", "10"); fail("interrupt expected"); }
        catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        assertEquals(children, ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());
    }

    @Test public void unsafeIdentityAndPartialLeaseFailClosed() throws Exception {
        Path root = Files.createTempDirectory("vm-guard");
        try { new KvmVmOperationGuard(root, "../escape", "stats", true); fail("identity accepted"); }
        catch (IllegalArgumentException | IOException expected) { }
        String uuid = UUID.randomUUID().toString();
        try (KvmVmOperationGuard operation = new KvmVmOperationGuard(root, uuid, "stats", true)) { }
        Files.writeString(root.resolve(uuid).resolve("partial.tmp"), "{");
        try { new KvmVmOperationGuard(root, uuid, "stats", true); fail("partial lease accepted"); }
        catch (IOException expected) { }
    }

}
