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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.cloud.agent.api.DeleteVMSnapshotCommand;
import com.cloud.agent.api.VMSnapshotTO;
import com.cloud.vm.snapshot.VMSnapshot;

public class KvmSnapshotRecoveryTest {
    private static final String UUID_VALUE = "ed54f859-87eb-48b8-842d-9f72dcb15805";
    private DeleteVMSnapshotCommand command() {
        DeleteVMSnapshotCommand command = new DeleteVMSnapshotCommand("vm", new VMSnapshotTO(1L, "failed", VMSnapshot.Type.DiskAndMemory, 1L, null, false, null, false), List.of(), null);
        command.setRecovery(UUID_VALUE, "ready", List.of("ready"), Map.of());
        return command;
    }
    static class Native implements KvmSnapshotRecovery.NativeAccess {
        String state = "running";
        boolean busy;
        boolean corrupt;
        boolean mismatch;
        boolean failDelete;
        boolean registeredTarget;
        final Map<String, Set<String>> tables = new HashMap<>(Map.of("/disk", new TreeSet<>(Set.of("ready")), "/nvram", new TreeSet<>(Set.of("ready"))));
        final List<String> deleted = new ArrayList<>();
        int writes;
        @Override public String virsh(String... args) throws Exception {
            switch (args[0]) {
            case "list": return UUID_VALUE + "\n";
            case "domstate": return state;
            case "domjobinfo": return "Job type: None\n";
            case "dumpxml": return "<domain><os><nvram>/nvram</nvram></os></domain>";
            case "snapshot-list": return registeredTarget ? "ready\nfailed\n" : "ready\n";
            case "snapshot-current": return mismatch ? "other" : "ready";
            case "suspend": state = "paused"; writes++; return "";
            case "resume": state = "running"; writes++; return "";
            case "snapshot-delete": registeredTarget = false; return "";
            case "qemu-monitor-command":
                if (corrupt) return "{\"error\":{\"class\":\"GenericError\"}}";
                Map<?, ?> request = new Gson().fromJson(args[2], Map.class);
                if (request.get("execute").equals("query-block-jobs") || request.get("execute").equals("query-jobs")) {
                    return busy ? "{\"return\":[{\"id\":\"live\"}]}" : "{\"return\":[]}";
                }
                if (request.get("execute").equals("blockdev-snapshot-delete-internal-sync")) {
                    writes++; if (failDelete) throw new IOException("native response lost");
                    Map<?, ?> arguments = (Map<?, ?>) request.get("arguments");
                    String path = "/" + arguments.get("device");
                    tables.get(path).remove(arguments.get("name")); deleted.add(path); return "{\"return\":{}}";
                }
                List<Object> blocks = new ArrayList<>();
                for (String path : new TreeSet<>(tables.keySet())) blocks.add(Map.of("inserted", Map.of("ro", false, "node-name", path.substring(1), "image", image(path))));
                return new Gson().toJson(Map.of("return", blocks));
            default: throw new IOException("Unexpected native call: " + args[0]);
            }
        }
        Map<String, Object> image(String path) {
            List<Object> snapshots = new ArrayList<>();
            for (String name : tables.get(path)) snapshots.add(Map.of("name", name, "id", name));
            return Map.of("filename", path, "format", "qcow2", "virtual-size", 1024, "snapshots", snapshots);
        }
        @Override public String imageInfo(String path) { return new Gson().toJson(List.of(image(path))); }
        @Override public void deleteImageSnapshot(String path, String name) { tables.get(path).remove(name); deleted.add(path); writes++; }
        @Override public void deleteArtifact(String path) { deleted.add(path); writes++; }
    }
    @Test public void confirmedAbsentSnapshotIsRemovedWithoutDiskWrites() throws Exception {
        Native nativeAccess = new Native();
        assertTrue(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), nativeAccess, Files.createTempDirectory("recover")).getResult());
        assertEquals(0, nativeAccess.writes);
        assertEquals(Set.of("ready"), nativeAccess.tables.get("/disk"));
    }
    @Test public void partialInternalSnapshotWithoutMetadataDeletesAllAffectedTablesAndResumes() throws Exception {
        Native access = new Native(); access.tables.values().forEach(names -> names.add("failed"));
        assertTrue(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), access, Files.createTempDirectory("recover")).getResult());
        assertEquals(Set.of("/disk", "/nvram"), new TreeSet<>(access.deleted));
        assertEquals(Set.of("ready"), access.tables.get("/disk")); assertEquals("running", access.state);
    }
    @Test public void busyUnknownAndMismatchedCurrentAreRefusedBeforeMutation() throws Exception {
        for (int condition = 0; condition < 3; condition++) {
            Native access = new Native(); access.busy = condition == 0; access.corrupt = condition == 1; access.mismatch = condition == 2;
            assertFalse(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), access, Files.createTempDirectory("recover")).getResult());
            assertEquals(0, access.writes);
        }
    }
    @Test public void liveBackingArtifactIsNeverDeleted() throws Exception {
        Native access = new Native();
        assertFalse(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(1L, "/disk"), access, Files.createTempDirectory("recover")).getResult());
        assertEquals(0, access.writes);
    }
    @Test public void partialRootDataAndNvramObjectsAreCleanedWithoutDeletingReadySnapshots() throws Exception {
        Native access = new Native(); access.tables.put("/data", new TreeSet<>(Set.of("ready", "failed")));
        access.tables.get("/disk").add("failed"); access.tables.get("/nvram").add("failed");
        assertTrue(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk", 2L, "/data"), Map.of(), access, Files.createTempDirectory("recover")).getResult());
        assertEquals(Set.of("/disk", "/data", "/nvram"), new TreeSet<>(access.deleted));
        access.tables.values().forEach(names -> assertEquals(Set.of("ready"), names)); assertEquals("running", access.state);
    }
    @Test public void unreferencedExternalArtifactIsCleanedWithoutChangingActiveImages() throws Exception {
        Native access = new Native();
        assertTrue(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(1L, "/unused"), access, Files.createTempDirectory("recover")).getResult());
        assertEquals(List.of("/unused"), access.deleted); assertEquals(Set.of("ready"), access.tables.get("/disk"));
    }
    @Test public void anotherVmBackingReferenceBlocksArtifactCleanup() throws Exception {
        Native access = new Native() {
            @Override public String virsh(String... args) throws Exception {
                if (args[0].equals("list")) return UUID_VALUE + "\nother-vm\n";
                if (args[0].equals("qemu-monitor-command") && args[1].equals("other-vm")) {
                    return "{\"return\":[{\"inserted\":{\"image\":{\"filename\":\"/other\",\"backing-image\":{\"filename\":\"/unused\"}}}}]}";
                }
                return super.virsh(args);
            }
        };
        assertFalse(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(1L, "/unused"), access, Files.createTempDirectory("recover")).getResult());
        assertEquals(0, access.writes);
    }
    @Test public void missingDataDiskAndIncompleteTablesKeepCloudRecoveryUnconfirmed() throws Exception {
        Native access = new Native();
        assertFalse(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk", 2L, "/missing"), Map.of(), access, Files.createTempDirectory("recover")).getResult());
        access.tables.get("/nvram").clear();
        assertFalse(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), access, Files.createTempDirectory("recover")).getResult());
    }
    @Test public void interruptedCleanupRetainsProtectionAndAllowsIdenticalRetry() throws Exception {
        Native access = new Native(); access.tables.get("/disk").add("failed"); access.failDelete = true;
        Path root = Files.createTempDirectory("recover");
        assertFalse(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), access, root).getResult());
        assertEquals("running", access.state);
        try { new KvmVmOperationGuard(root, UUID_VALUE, "stats", true); fail("uncertain cleanup was unprotected"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Unreconciled")); }
        access.failDelete = false;
        assertTrue(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), access, root).getResult());
        assertTrue(Files.exists(root.resolve("reconciled/" + UUID_VALUE)));
    }
    @Test public void offlineRecoveryUsesLockedImagesAndNeverLiveQemuImgMutation() throws Exception {
        Native access = new Native(); access.state = "shut off"; access.tables.get("/disk").add("failed");
        assertTrue(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), access, Files.createTempDirectory("recover")).getResult());
        assertEquals(List.of("/disk"), access.deleted); assertEquals("shut off", access.state);
    }
    @Test public void originalLiveOwnerWithoutCompletionProofIsNotReconciled() throws Exception {
        Path root = Files.createTempDirectory("recover"); Native access = new Native();
        try (KvmVmOperationGuard original = new KvmVmOperationGuard(root, UUID_VALUE, "restore-vm-snapshot", false)) { original.uncertain(); }
        Path marker;
        try (java.util.stream.Stream<Path> markers = Files.list(root.resolve(UUID_VALUE))) { marker = markers.findFirst().orElseThrow(); }
        com.google.gson.JsonObject lease = JsonParser.parseString(Files.readString(marker)).getAsJsonObject(); lease.remove("nativeCallCompleted"); Files.writeString(marker, lease.toString());
        assertFalse(KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), access, root).getResult());
        assertTrue(Files.exists(marker)); assertEquals(0, access.writes);
    }

    @Test public void restartBetweenAuditProofAndLeaseArchiveCanRetryTheSameObservation() throws Exception {
        Path root = Files.createTempDirectory("recover"); Native access = new Native();
        try (KvmVmOperationGuard original = new KvmVmOperationGuard(root, UUID_VALUE, "restore-vm-snapshot", false)) { original.uncertain(); }
        Path marker;
        try (java.util.stream.Stream<Path> markers = Files.list(root.resolve(UUID_VALUE))) { marker = markers.findFirst().orElseThrow(); }
        Path audit = root.resolve("reconciled/" + UUID_VALUE);
        Files.createDirectories(audit, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        Files.writeString(audit.resolve(marker.getFileName() + ".proof"), KvmSnapshotRecovery.inspect(command(), Map.of(1L, "/disk"), Map.of(), access).fingerprint());
        com.cloud.agent.api.DeleteVMSnapshotAnswer answer = KvmSnapshotRecovery.execute(command(), Map.of(1L, "/disk"), Map.of(), access, root);
        assertTrue(answer.getDetails(), answer.getResult());
        assertFalse(Files.exists(marker)); assertTrue(Files.exists(audit.resolve(marker.getFileName()))); assertEquals(0, access.writes);
    }
}
