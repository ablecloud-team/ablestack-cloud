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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.apache.cloudstack.backup.BackupVolumeAllocation;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.logging.log4j.LogManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.cloud.hypervisor.kvm.storage.Gfs2VolumeAllocation;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.RbdVolumeAllocation;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;

public class LibvirtAblestackRestoreAllocationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private BackupVolumeAllocation policy(String backend, String policy) {
        var result = new BackupVolumeAllocation();
        result.volumeUuid = "volume"; result.volumeId = 1; result.accountId = 2; result.bytes = 4096;
        result.backend = backend; result.format = backend.equals("RBD") ? "raw" : "qcow2"; result.poolUuid = "pool";
        result.intent = VolumeAllocationPolicy.request(policy, result.format, null); return result;
    }
    private VolumeObjectTO result(BackupVolumeAllocation policy) {
        var result = new VolumeObjectTO();
        String method = policy.backend.equals("RBD") ? ("full".equals(policy.intent.get(VolumeAllocationPolicy.POLICY))
                ? "rbd-thick-convert-n-S0" : "rbd-thin") : "qemu-preallocation-" + policy.intent.get(VolumeAllocationPolicy.POLICY);
        result.setAllocation(VolumeAllocationPolicy.evidence(policy.intent, policy.backend, "copy", method, 4096L, Instant.now(), "test-observation"));
        return result;
    }
    private void fileRestore(String allocation, boolean failAfterCopy, boolean failPromote) throws Exception {
        fileRestore(allocation, failAfterCopy, failPromote, "qcow2");
    }
    private void fileRestore(String allocation, boolean failAfterCopy, boolean failPromote, String format) throws Exception {
        Path primary = folder.newFolder().toPath(); Path target = Files.writeString(primary.resolve("root"), "original");
        Path journal = folder.newFolder().toPath(); Path receipts = folder.newFolder().toPath();
        var policy = new BackupVolumeAllocation();
        policy.volumeUuid = "volume"; policy.volumeId = 1; policy.accountId = 2; policy.bytes = 4096;
        policy.backend = "GFS2"; policy.poolUuid = "pool"; policy.format = format;
        policy.intent = VolumeAllocationPolicy.request(allocation, format, null);
        KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class); KVMStoragePool pool = mock(KVMStoragePool.class);
        when(manager.getStoragePool(Storage.StoragePoolType.SharedMountPoint, "pool")).thenReturn(pool);
        when(pool.getUuid()).thenReturn("pool"); when(pool.getLocalPath()).thenReturn(primary.toString());
        PrimaryDataStoreTO to = mock(PrimaryDataStoreTO.class); when(to.getUuid()).thenReturn("pool");
        when(to.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        try (var transactions = mockStatic(LibvirtAblestackRestoreTransaction.class, CALLS_REAL_METHODS);
                var allocations = mockStatic(LibvirtAblestackRestoreAllocation.class, CALLS_REAL_METHODS);
                var formats = mockStatic(LibvirtAblestackFileRestoreHelper.class, CALLS_REAL_METHODS);
                var files = mockStatic(Files.class, CALLS_REAL_METHODS);
                var adapter = mockConstruction(Gfs2VolumeAllocation.class, (mock, context) ->
                    when(mock.create(any(VolumeObjectTO.class), eq(pool), any(KVMPhysicalDisk.class), anyLong())).thenAnswer(call -> {
                        VolumeObjectTO request = call.getArgument(0); KVMPhysicalDisk source = call.getArgument(2);
                        assertEquals(policy.intent, request.getAllocation());
                        assertEquals(Storage.ImageFormat.valueOf(format.toUpperCase(Locale.ROOT)), request.getFormat());
                        assertEquals("original", Files.readString(target));
                        Files.copy(Path.of(source.getPath()), primary.resolve(request.getUuid()));
                        if (failAfterCopy) {
                            // Qualified adapters clean their own unpublished copy on verification failure.
                            Files.delete(primary.resolve(request.getUuid()));
                            throw new CloudRuntimeException("Injected verification failure");
                        }
                        return result(policy);
                    }));
                var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of(target.toString(), Storage.ProvisioningType.FAT),
                        Map.of(target.toString(), policy), "restore-file")) {
            transactions.when(() -> LibvirtAblestackRestoreTransaction.vmDirectory("vm")).thenReturn(journal);
            allocations.when(LibvirtAblestackRestoreAllocation::resultDirectory).thenReturn(receipts);
            formats.when(() -> LibvirtAblestackFileRestoreHelper.getFileVolumeFormat(any(), anyString())).thenReturn(QemuImg.PhysicalDiskFormat.QCOW2);
            if (failPromote) {
                files.when(() -> Files.move(any(Path.class), eq(target), eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
                    Path source = call.getArgument(0);
                    if (source.toString().contains("-prepared-")) throw new IOException("Injected switch failure");
                    return call.callRealMethod();
                });
            }
            Runnable restore = () -> LibvirtAblestackRestoreTransaction.restorePrepared(LogManager.getLogger(), "test", "vm", manager,
                    List.of(to), List.of(target.toString()), 60, () -> {}, (index, prepared) -> {
                        try { Files.writeString(Path.of(prepared), "restored-chain"); return true; }
                        catch (IOException failure) { throw new RuntimeException(failure); }
                    });
            if (failAfterCopy || failPromote) {
                assertThrows(CloudRuntimeException.class, restore::run);
                assertEquals("original", Files.readString(target));
                assertThrows(IOException.class, () -> LibvirtAblestackRestoreAllocation.read("restore-file"));
            } else {
                restore.run(); assertEquals("restored-chain", Files.readString(target));
                var receipt = LibvirtAblestackRestoreAllocation.read("restore-file");
                assertEquals(policy.intent, receipt.policies.get(target.toString()).intent);
                assertEquals("qemu-preallocation-" + allocation, receipt.evidence.get(target.toString()).get(VolumeAllocationPolicy.METHOD));
            }
            try (var paths = Files.list(primary)) { assertEquals(1, paths.count()); }
        }
    }
    @Test public void allThreeQcow2PoliciesPublishOnlyVerifiedReplacementVolumes() throws Exception {
        for (String policy : List.of("metadata", "falloc", "full")) fileRestore(policy, false, false);
    }
    @Test public void allThreeRawPoliciesRestoreQcow2ExportToTheOriginalRawFormat() throws Exception {
        for (String allocation : List.of("off", "falloc", "full")) fileRestore(allocation, false, false, "raw");
    }
    @Test public void rawVerificationFailureKeepsOriginalSharedFile() throws Exception { fileRestore("falloc", true, false, "raw"); }
    @Test public void failedVerificationRemovesOnlyOwnedCopiesAndRetainsTheOriginal() throws Exception { fileRestore("falloc", true, false); }
    @Test public void failedSwitchRestoresTheOriginalAndDoesNotPublishEvidence() throws Exception { fileRestore("full", false, true); }

    @Test public void rbdOffAndFullApplyAfterTheWholeDiffChainIsReconstructed() throws Exception {
        for (String allocation : List.of("off", "full")) {
            var policy = policy("RBD", allocation); var images = new HashMap<String, String>(); images.put("root", "original");
            Path journal = folder.newFolder().toPath(); Path receipts = folder.newFolder().toPath();
            KVMStoragePoolManager manager = mock(KVMStoragePoolManager.class); KVMStoragePool pool = mock(KVMStoragePool.class);
            when(manager.getStoragePool(Storage.StoragePoolType.RBD, "pool")).thenReturn(pool);
            when(pool.getUuid()).thenReturn("pool"); when(pool.getSourceDir()).thenReturn("ceph");
            PrimaryDataStoreTO to = mock(PrimaryDataStoreTO.class); when(to.getUuid()).thenReturn("pool"); when(to.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
            try (var transactions = mockStatic(LibvirtAblestackRestoreTransaction.class, CALLS_REAL_METHODS);
                    var allocations = mockStatic(LibvirtAblestackRestoreAllocation.class, CALLS_REAL_METHODS);
                    var backend = mockStatic(LibvirtAblestackRbdRestoreHelper.class);
                    var adapter = mockConstruction(RbdVolumeAllocation.class, (mock, context) ->
                        when(mock.create(any(VolumeObjectTO.class), eq(pool), any(KVMPhysicalDisk.class), anyLong())).thenAnswer(call -> {
                            VolumeObjectTO request = call.getArgument(0); KVMPhysicalDisk source = call.getArgument(2);
                            assertEquals(QemuImg.PhysicalDiskFormat.RAW, source.getFormat());
                            assertTrue(source.getPath().startsWith("ceph/"));
                            String sourceImage = source.getPath().substring("ceph/".length());
                            assertEquals("full-plus-all-diffs", images.get(sourceImage));
                            assertEquals("original", images.get("root"));
                            images.put(request.getUuid(), images.get(sourceImage)); return result(policy);
                        }));
                    var scope = LibvirtAblestackRestoreProvisioning.begin(Map.of("root", Storage.ProvisioningType.THIN), Map.of("root", policy), "restore-rbd")) {
                transactions.when(() -> LibvirtAblestackRestoreTransaction.vmDirectory("vm")).thenReturn(journal);
                allocations.when(LibvirtAblestackRestoreAllocation::resultDirectory).thenReturn(receipts);
                backend.when(() -> LibvirtAblestackRbdRestoreHelper.rbdImageExists(eq(pool), anyString(), anyInt())).thenAnswer(call -> images.containsKey(call.getArgument(1)));
                backend.when(() -> LibvirtAblestackRbdRestoreHelper.deleteRbdImageIfPresent(eq(pool), anyString(), anyInt())).thenAnswer(call -> { images.remove(call.getArgument(1)); return true; });
                backend.when(() -> LibvirtAblestackRbdRestoreHelper.renameRbdImage(eq(pool), anyString(), anyString(), anyInt())).thenAnswer(call -> {
                    String from = call.getArgument(1), target = call.getArgument(2);
                    if (!images.containsKey(from) || images.containsKey(target)) return false;
                    images.put(target, images.remove(from)); return true;
                });
                LibvirtAblestackRestoreTransaction.restorePrepared(LogManager.getLogger(), "test", "vm", manager, List.of(to), List.of("root"), 60,
                        () -> {}, (index, prepared) -> { images.put(prepared, "full-plus-all-diffs"); return true; });
                assertEquals(Map.of("root", "full-plus-all-diffs"), images);
                var receipt = LibvirtAblestackRestoreAllocation.read("restore-rbd");
                assertEquals(allocation, receipt.evidence.get("root").get(VolumeAllocationPolicy.FIXED_POLICY));
            }
        }
    }

    @Test public void uncommittedOrUnverifiedTransactionsCannotPublishEvidence() throws Exception {
        Properties state = new Properties(); state.setProperty("allocation.job", "job"); state.setProperty("phase", "PREPARING");
        Path receipts = folder.newFolder().toPath();
        try (var allocation = mockStatic(LibvirtAblestackRestoreAllocation.class, CALLS_REAL_METHODS)) {
            allocation.when(LibvirtAblestackRestoreAllocation::resultDirectory).thenReturn(receipts);
            LibvirtAblestackRestoreAllocation.publish(state);
            assertFalse(Files.exists(receipts.resolve("job.json")));
            state.setProperty("phase", "COMMITTED"); state.setProperty("count", "1");
            state.setProperty("0.allocation.request", "{}");
            assertThrows(IOException.class, () -> LibvirtAblestackRestoreAllocation.publish(state));
        }
    }
}
