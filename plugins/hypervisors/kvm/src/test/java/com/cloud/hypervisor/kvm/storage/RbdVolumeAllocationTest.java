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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RbdVolumeAllocationTest {
    private static final long SIZE = 64L * 1024 * 1024;
    private static final String ID = "00000000-0000-0000-0000-000000000142";
    private KVMStoragePool pool;
    private VolumeObjectTO request;
    private Fake runner;
    private RbdVolumeAllocation adapter;

    @Before public void setup() {
        pool = mock(KVMStoragePool.class);
        when(pool.getType()).thenReturn(Storage.StoragePoolType.RBD);
        when(pool.getUuid()).thenReturn("pool");
        when(pool.getSourceDir()).thenReturn("rbd");
        when(pool.getSourceHost()).thenReturn("10.0.0.1,10.0.0.2");
        when(pool.getSourcePort()).thenReturn(6789);
        when(pool.getAuthUserName()).thenReturn("admin");
        when(pool.getAuthSecret()).thenReturn("AQABCDEF=");
        when(pool.getAvailable()).thenReturn(10 * SIZE);
        request = new VolumeObjectTO();
        request.setUuid(ID); request.setSize(SIZE);
        request.setAllocation(VolumeAllocationPolicy.request("full", "raw", null));
        runner = new Fake(); adapter = new RbdVolumeAllocation(runner);
    }

    private VolumeObjectTO create(KVMPhysicalDisk source) { return adapter.create(request, pool, source, 60_000); }
    private KVMPhysicalDisk source() {
        KVMPhysicalDisk disk = new KVMPhysicalDisk("rbd/source", "source", pool);
        disk.setFormat(PhysicalDiskFormat.RAW); disk.setVirtualSize(SIZE); return disk;
    }
    private void fails(String reason, KVMPhysicalDisk source) {
        try { create(source); fail("Expected failure"); } catch (RuntimeException e) { assertTrue(e.toString(), e.toString().contains(reason)); }
    }
    private void noFinalDelete() { assertFalse(runner.commands.stream().anyMatch(c -> c.contains("rm") && c.contains("rbd/" + ID))); }
    private boolean command(String... parts) { return runner.commands.stream().anyMatch(c -> c.containsAll(List.of(parts))); }

    @Test public void fullBlankVerifiesZeroAndExactAllocationBeforePublish() {
        VolumeObjectTO result = create(null);
        assertEquals(ID, result.getPath()); assertEquals(Storage.ImageFormat.RAW, result.getFormat());
        assertEquals("full", result.getAllocation().get(VolumeAllocationPolicy.FIXED_POLICY));
        assertEquals("rbd-thick-create", result.getAllocation().get(VolumeAllocationPolicy.METHOD));
        assertTrue(command("create", "--thick-provision")); assertTrue(command("compare"));
        assertTrue(runner.index("du") < runner.index("rename")); assertFalse(command("rm"));
    }
    @Test public void thinBlankUsesOrdinaryCreateAndReportsObservedZero() {
        request.setAllocation(VolumeAllocationPolicy.request("off", "raw", null)); runner.used = 0;
        VolumeObjectTO result = create(null);
        assertFalse(command("--thick-provision")); assertEquals("0", result.getAllocation().get(VolumeAllocationPolicy.OBSERVED_BYTES));
    }
    @Test public void fullCopyDisablesSparseOptimizationAndNeverClones() {
        VolumeObjectTO result = create(source());
        assertTrue(command("convert", "-n", "-S", "0", "-O", "raw")); assertFalse(command("clone"));
        assertEquals("rbd-thick-convert-n-S0", result.getAllocation().get(VolumeAllocationPolicy.METHOD));
    }
    @Test public void thinCopyRemainsSparse() {
        request.setAllocation(VolumeAllocationPolicy.request("off", "raw", null)); runner.used = 4096;
        create(source()); assertTrue(command("convert", "-S", "4096")); assertFalse(command("--thick-provision"));
    }
    @Test public void unsupportedFallocNeverWrites() {
        request.setAllocation(VolumeAllocationPolicy.request("falloc", "raw", null)); fails("REQUEST_UNSUPPORTED", null);
        assertTrue(runner.commands.isEmpty());
    }
    @Test public void otherBackendNeverWrites() { when(pool.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint); fails("REQUEST_UNSUPPORTED", null); assertTrue(runner.commands.isEmpty()); }
    @Test public void missingIntentNeverWrites() { request.setAllocation(Map.of()); fails("REQUEST_UNSUPPORTED", null); assertTrue(runner.commands.isEmpty()); }
    @Test public void fullCapacityShortageNeverCreates() { when(pool.getAvailable()).thenReturn(SIZE - 1); fails("INSUFFICIENT_CAPACITY", null); assertFalse(command("create")); }
    @Test public void createFailureCleansOnlyOwnedPartialStage() { runner.fail = "create"; fails("injected-create", null); assertTrue(command("rm")); noFinalDelete(); }
    @Test public void convertFailurePreservesSourceAndCleansOnlyStage() { runner.fail = "convert"; fails("injected-convert", source()); assertTrue(command("rm")); noFinalDelete(); assertFalse(command("rm", "rbd/source")); }
    @Test public void comparisonFailureNeverPublishes() { runner.fail = "compare"; fails("injected-compare", source()); assertTrue(command("rm")); assertFalse(command("rename")); }
    @Test public void incompleteFullAllocationNeverPublishes() { runner.used = SIZE - 1; fails("not fully allocated", null); assertTrue(command("rm")); assertFalse(command("rename")); }
    @Test public void wrongVirtualSizeNeverPublishes() { runner.infoSize = SIZE - 1; fails("size mismatch", null); assertFalse(command("rename")); }
    @Test public void cowParentNeverPublishes() { runner.parent = true; fails("COW parent", null); assertFalse(command("rename")); }
    @Test public void publishCollisionNeverOverwritesOrDeletesWinner() { runner.fail = "rename"; fails("injected-rename", null); assertTrue(command("rm")); noFinalDelete(); }
    @Test public void unknownExistingImageIsNeverAdoptedOrDeleted() { runner.exists = true; runner.receipt = "foreign"; fails("TARGET_EXISTS", null); assertFalse(command("create")); assertFalse(command("rm")); }
    @Test public void lostAnswerRetryReverifiesReceiptDataAndAllocationWithoutCreate() {
        create(source()); runner.commands.clear();
        VolumeObjectTO retry = create(source()); assertEquals(ID, retry.getPath());
        assertTrue(command("compare")); assertTrue(command("du")); assertFalse(command("create")); assertFalse(command("rm"));
    }
    @Test public void duplicateRetryAfterDiscardDoesNotClaimFull() { create(null); runner.commands.clear(); runner.used = 0; fails("not fully allocated", null); assertFalse(command("create")); assertFalse(command("rm")); }
    @Test public void retryDataMismatchPreservesPublishedImage() { create(null); runner.commands.clear(); runner.fail = "compare"; fails("injected-compare", null); assertFalse(command("rm")); }
    @Test public void failedCleanupNamesOnlyOwnedStage() { runner.fail = "create"; runner.cleanupFail = true; fails("owned temporary image requires cleanup: rbd/" + ID + ".allocation-", null); noFinalDelete(); }
    @Test public void sourceTooLargeNeverCreates() { runner.sourceSize = SIZE + 1; fails("TARGET_TOO_SMALL", source()); assertFalse(command("create")); }
    @Test public void crossPoolSourceNeverWrites() {
        KVMStoragePool other = mock(KVMStoragePool.class); when(other.getType()).thenReturn(Storage.StoragePoolType.RBD); when(other.getUuid()).thenReturn("other");
        KVMPhysicalDisk disk = new KVMPhysicalDisk("other/source", "source", other); disk.setFormat(PhysicalDiskFormat.RAW);
        fails("SOURCE_UNSUPPORTED", disk); assertTrue(runner.commands.isEmpty());
    }
    @Test public void malformedPoolCannotInjectConfigurationOrCommands() { when(pool.getAuthUserName()).thenReturn("admin\n[global]"); fails("POOL_UNSUPPORTED", null); assertTrue(runner.commands.isEmpty()); }
    @Test public void capabilityProbeIsReadOnlyAndOperationScoped() {
        assertEquals("rbd-thick-create", adapter.capabilities(pool, "raw", "create").get("full"));
        assertEquals("rbd-thick-convert-n-S0", adapter.capabilities(pool, "raw", "copy").get("full"));
        assertTrue(adapter.capabilities(pool, "raw", "resize").isEmpty());
        assertFalse(command("--thick-provision")); assertFalse(command("rm"));
    }
    @Test public void noSecretAppearsInArguments() { create(source()); assertFalse(runner.commands.toString().contains("AQABCDEF=")); }

    @Test public void differentVolumeRecordCannotAdoptAnOldReceipt() {
        request.setVolumeId(1426L); create(null); runner.commands.clear(); request.setVolumeId(1427L);
        fails("TARGET_EXISTS", null); assertFalse(command("create")); assertFalse(command("rm"));
    }
    @Test public void differentOwnerCannotAdoptAnOldReceipt() {
        create(null); runner.commands.clear(); request.setAccountId(2L);
        fails("TARGET_EXISTS", null); assertFalse(command("create")); assertFalse(command("rm"));
    }

    @Test public void rawClusterFreeCannotOverrideSmallerPoolMaxAvail() {
        runner.available = SIZE - 1; fails("INSUFFICIENT_CAPACITY", null); assertFalse(command("create"));
    }
    @Test public void unknownPoolCapacityCannotPublishFull() {
        runner.missingCapacity = true; fails("CAPACITY_UNVERIFIED", null); assertFalse(command("create"));
    }
    @Test public void fullCapabilityIsWithheldWithoutAuthenticatedPoolCapacity() {
        runner.missingCapacity = true;
        Map<String,String> methods = adapter.capabilities(pool, "raw", "create");
        assertFalse(methods.containsKey("full")); assertEquals("rbd-thin", methods.get("off"));
    }

    private static class Fake implements RbdVolumeAllocation.Runner {
        List<List<String>> commands = new ArrayList<>();
        String stage; String fail; boolean cleanupFail; boolean exists; boolean parent; String receipt;
        long used = SIZE; long infoSize = SIZE; long sourceSize = SIZE; long available = SIZE * 10; boolean missingCapacity;
        int index(String op) { for (int i = 0; i < commands.size(); i++) if (commands.get(i).contains(op)) return i; return -1; }
        @Override public String run(List<String> args, long timeout) {
            assertTrue(timeout > 0); commands.add(args);
            if (args.contains("--help") || args.contains("help")) return "--thick-provision --exact -n -S compare -F";
            String op = (args.get(0).equals("rbd") || args.get(0).equals("ceph")) && args.contains("--conf") ? args.get(5) : args.get(1);
            if (op.equals("create")) stage = args.get(args.size() - 1).substring(4);
            if (op.equals(fail) || (cleanupFail && op.equals("rm"))) throw new CloudRuntimeException("injected-" + op);
            if (op.equals("help") || op.equals("--help")) return "--thick-provision --exact -n -S compare";
            if (op.equals("ls")) return exists ? "[\"" + ID + "\"]" : (cleanupFail && stage != null ? "[\"" + stage + "\"]" : "[]");
            if (op.equals("rename")) exists = true;
            if (op.equals("image-meta")) { if (args.contains("get")) return receipt; receipt = args.get(args.size() - 1); }
            if (op.equals("info")) {
                if (args.get(0).equals("qemu-img")) return "{\"virtual-size\":" + sourceSize + "}";
                return "{\"size\":" + infoSize + (parent ? ",\"parent\":{}" : "") + "}";
            }
            if (op.equals("df")) return missingCapacity ? "{}" : "{\"pools\":[{\"name\":\"rbd\",\"stats\":{\"max_avail\":" + available + "}}]}";
            if (op.equals("du")) return "{\"images\":[{\"provisioned_size\":" + SIZE + ",\"used_size\":" + used + "}]}";
            return "{}";
        }
    }
}
