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

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg.PhysicalDiskFormat;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class Gfs2VolumeAllocationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private KVMStoragePool pool;
    private VolumeObjectTO volume;
    private Fake runner;
    private Path root;
    private Gfs2VolumeAllocation adapter;
    private static final long SIZE = 1048576;
    @Before public void setup() throws Exception {
        root = temporary.newFolder("pool").toPath(); pool = mock(KVMStoragePool.class);
        when(pool.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint); when(pool.getUuid()).thenReturn("pool");
        when(pool.getLocalPath()).thenReturn(root.toString()); when(pool.getAvailable()).thenReturn(Long.MAX_VALUE);
        volume = new VolumeObjectTO(); volume.setUuid("00000000-0000-0000-0000-000000001425"); volume.setVolumeId(1425L); volume.setAccountId(2L); volume.setSize(SIZE);
        volume.setAllocation(VolumeAllocationPolicy.request("full", "raw", null)); runner = new Fake();
        adapter = new Gfs2VolumeAllocation(runner) { @Override protected Path root(KVMStoragePool ignored) { return root; } };
    }
    private Path target() { return root.resolve(volume.getUuid()); }
    private void cleanStage() throws Exception { try (var children = Files.list(root)) { assertFalse(children.anyMatch(p -> p.getFileName().toString().contains(".allocation-"))); } }
    private void reject(Runnable call, String text) {
        try { call.run(); fail("Expected rejection: "+text); }
        catch (CloudRuntimeException e) { assertTrue(e.toString(), e.toString().contains(text)); }
    }
    private KVMPhysicalDisk source() throws Exception {
        Path path = temporary.newFile("source.raw").toPath(); try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "rw")) { f.setLength(65536); }
        runner.register(path, "raw", 65536, 65536);
        KVMPhysicalDisk disk = mock(KVMPhysicalDisk.class); when(disk.getPath()).thenReturn(path.toString()); when(disk.getFormat()).thenReturn(PhysicalDiskFormat.RAW); return disk;
    }
    @Test public void exactSupportedMatrixKeepsRawMetadataAndQcowOffOut() {
        assertEquals(3, Gfs2VolumeAllocation.methods("qcow2", "create").size());
        assertEquals(3, Gfs2VolumeAllocation.methods("raw", "copy").size());
        assertFalse(Gfs2VolumeAllocation.methods("raw", "create").containsKey("metadata"));
        assertFalse(Gfs2VolumeAllocation.methods("qcow2", "copy").containsKey("off"));
        for (String op : List.of("resize", "clone", "restore", "migrate")) { assertTrue(Gfs2VolumeAllocation.methods("qcow2", op).isEmpty()); }
    }
    @Test public void sixBlankPoliciesPublishVerifiedInodeWithIndependentEvidence() throws Exception {
        for (String format : List.of("qcow2", "raw")) {
            for (String policy : Gfs2VolumeAllocation.methods(format, "create").keySet()) {
                volume.setUuid(java.util.UUID.randomUUID().toString()); volume.setAllocation(VolumeAllocationPolicy.request(policy, format, null));
                VolumeObjectTO answer = adapter.create(volume, pool, null, 60000);
                assertEquals(Storage.ImageFormat.valueOf(format.toUpperCase(java.util.Locale.ROOT)), answer.getFormat());
                assertEquals("GFS2", answer.getAllocation().get(VolumeAllocationPolicy.BACKEND));
                assertEquals(policy, answer.getAllocation().get(VolumeAllocationPolicy.FIXED_POLICY)); assertTrue(Files.isRegularFile(target())); cleanStage();
            }
        }
    }
    @Test public void fullAndFallocCopiesDisableSparseConversion() throws Exception {
        KVMPhysicalDisk source = source();
        for (String policy : List.of("full", "falloc")) {
            volume.setUuid(java.util.UUID.randomUUID().toString()); volume.setAllocation(VolumeAllocationPolicy.request(policy, "qcow2", null));
            assertEquals("copy", adapter.create(volume, pool, source, 60000).getAllocation().get(VolumeAllocationPolicy.OPERATION));
            List<String> convert = runner.calls.stream().filter(c -> c.size()>1 && "convert".equals(c.get(1))).reduce((a,b) -> b).get();
            assertEquals("0", convert.get(convert.indexOf("-S")+1)); assertTrue(convert.contains("-n")); cleanStage();
        }
    }
    @Test public void lostAnswerRetryReverifiesOwnedInode() throws Exception {
        adapter.create(volume, pool, null, 60000); long inode = ((Number) Files.getAttribute(target(), "unix:ino")).longValue();
        adapter.create(volume, pool, null, 60000); assertEquals(inode, ((Number) Files.getAttribute(target(), "unix:ino")).longValue());
        assertEquals(1, runner.calls.stream().filter(c -> c.size()>1 && "create".equals(c.get(1))).count()); cleanStage();
    }
    @Test public void existingUnownedFileIsNeverAdoptedOrDeleted() throws Exception {
        Files.writeString(target(), "original"); reject(() -> adapter.create(volume, pool, null, 60000), "TARGET_EXISTS"); assertEquals("original", Files.readString(target())); cleanStage();
    }
    @Test public void differentOwnerCannotAdoptSameVolumeUuid() throws Exception {
        adapter.create(volume, pool, null, 60000); volume.setAccountId(3L);
        reject(() -> adapter.create(volume, pool, null, 60000), "different request"); assertTrue(Files.exists(target())); cleanStage();
    }
    @Test public void symlinkTargetDoesNotTouchExternalFile() throws Exception {
        Path other = temporary.newFile("other").toPath(); Files.writeString(other, "keep"); Files.createSymbolicLink(target(), other);
        reject(() -> adapter.create(volume, pool, null, 60000), "TARGET_EXISTS"); assertEquals("keep", Files.readString(other)); cleanStage();
    }
    @Test public void capacityIncludesFormatOverheadBeforeAnyStage() throws Exception {
        volume.setAllocation(VolumeAllocationPolicy.request("full", "qcow2", null)); when(pool.getAvailable()).thenReturn(SIZE);
        reject(() -> adapter.create(volume, pool, null, 60000), "INSUFFICIENT_CAPACITY"); assertFalse(Files.exists(target())); cleanStage();
    }
    @Test public void actualFilesystemSpaceAlsoLimitsCapacity() throws Exception {
        adapter = new Gfs2VolumeAllocation(runner) { @Override protected Path root(KVMStoragePool ignored) { return root; } @Override protected long available(Path ignored) { return 0; } };
        reject(() -> adapter.create(volume, pool, null, 60000), "INSUFFICIENT_CAPACITY"); cleanStage();
    }
    @Test public void partialCreateFailureCleansOnlyItsOwnStage() throws Exception {
        Path unrelated = root.resolve("unrelated"); Files.writeString(unrelated, "keep"); runner.failCreate = true;
        reject(() -> adapter.create(volume, pool, null, 60000), "injected create failure"); assertEquals("keep", Files.readString(unrelated)); cleanStage();
    }
    @Test public void partialCopyFailurePreservesOriginalAndNoFinalFile() throws Exception {
        KVMPhysicalDisk source = source(); runner.failCopy = true; byte[] before = Files.readAllBytes(Path.of(source.getPath()));
        reject(() -> adapter.create(volume, pool, source, 60000), "injected copy failure"); assertArrayEquals(before, Files.readAllBytes(Path.of(source.getPath()))); assertFalse(Files.exists(target())); cleanStage();
    }
    @Test public void compareFailureCannotPublishFull() throws Exception {
        runner.failCompare = true; reject(() -> adapter.create(volume, pool, null, 60000), "compare failed"); assertFalse(Files.exists(target())); cleanStage();
    }
    @Test public void falseFullUsageCannotPublish() throws Exception {
        runner.falseUsage = true; reject(() -> adapter.create(volume, pool, null, 60000), "VERIFICATION_FAILED"); assertFalse(Files.exists(target())); cleanStage();
    }
    @Test public void wrongActualFormatCannotPublish() throws Exception {
        runner.wrongFormat = true; reject(() -> adapter.create(volume, pool, null, 60000), "VERIFICATION_FAILED"); cleanStage();
    }
    @Test public void backingTemplateIsRejectedBeforeStage() throws Exception {
        KVMPhysicalDisk source = source(); runner.backing = true; reject(() -> adapter.create(volume, pool, source, 60000), "VERIFICATION_FAILED"); cleanStage();
    }
    @Test public void tooLargeTemplateIsRejectedBeforeStage() throws Exception {
        KVMPhysicalDisk source = source(); volume.setSize(32768L); reject(() -> adapter.create(volume, pool, source, 60000), "TARGET_TOO_SMALL"); cleanStage();
    }
    @Test public void sourceSymlinkIsNotFollowed() throws Exception {
        KVMPhysicalDisk source = source(); Path link = temporary.getRoot().toPath().resolve("source-link"); Files.createSymbolicLink(link, Path.of(source.getPath())); when(source.getPath()).thenReturn(link.toString());
        reject(() -> adapter.create(volume, pool, source, 60000), "SOURCE_UNSUPPORTED"); cleanStage();
    }
    @Test public void immutableTemplateChangesBeforePublishAreRejected() throws Exception {
        KVMPhysicalDisk source = source(); runner.changeSource = Path.of(source.getPath());
        reject(() -> adapter.create(volume, pool, source, 60000), "SOURCE_CHANGED"); assertFalse(Files.exists(target())); cleanStage();
    }
    @Test public void discardedFullCannotBeAdoptedOnRetry() throws Exception {
        adapter.create(volume, pool, null, 60000); runner.falseUsage = true;
        reject(() -> adapter.create(volume, pool, null, 60000), "VERIFICATION_FAILED"); assertTrue(Files.exists(target())); cleanStage();
    }
    @Test public void timeoutCannotPublishOrDeleteAnotherFile() throws Exception {
        runner.failTimeout = true; reject(() -> adapter.create(volume, pool, null, 60000), "TIMEOUT"); assertFalse(Files.exists(target())); cleanStage();
    }
    @Test public void actualNonGfsMountDoesNotClaimCapability() {
        reject(() -> new Gfs2VolumeAllocation(runner).capabilities(pool, "raw", "create"), "BACKEND_UNSUPPORTED");
    }
    @Test public void rbdFallocNeverFallsBackToThinAfterCommonIntentExpansion() {
        when(pool.getType()).thenReturn(Storage.StoragePoolType.RBD); volume.setAllocation(VolumeAllocationPolicy.request("falloc", "raw", null));
        reject(() -> new RbdVolumeAllocation((a,t) -> { throw new AssertionError("No CLI call allowed"); }).create(volume, pool, null, 60000), "REQUEST_UNSUPPORTED");
    }
    @Test public void rbdQcow2NeverFallsBackToRawThin() {
        when(pool.getType()).thenReturn(Storage.StoragePoolType.RBD); volume.setAllocation(VolumeAllocationPolicy.request("metadata", "qcow2", null));
        reject(() -> new RbdVolumeAllocation((a,t) -> { throw new AssertionError("No CLI call allowed"); }).create(volume, pool, null, 60000), "REQUEST_UNSUPPORTED");
    }

    @Test public void fallocMismatchRetriesFreshInodesAndPreservesFirstFailure() throws Exception {
        volume.setAllocation(VolumeAllocationPolicy.request("falloc", "raw", null)); runner.mismatches = 2;
        VolumeObjectTO answer = adapter.create(volume, pool, null, 60000);
        assertEquals(3, runner.createCount); assertEquals(3, runner.createdPaths.stream().distinct().count());
        assertEquals("falloc", answer.getAllocation().get(VolumeAllocationPolicy.FIXED_POLICY));
        assertEquals("qemu-preallocation-falloc", answer.getAllocation().get(VolumeAllocationPolicy.METHOD));
        Path failed = root.resolve(Gfs2AllocationFailureEvidence.DIRECTORY);
        assertTrue(Files.isRegularFile(failed.resolve("image")));
        assertTrue(Files.readString(failed.resolve("manifest.json")).contains("unverified-content-mismatch"));
        try (var files = Files.list(failed)) { assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("zero-")).count()); }
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(failed));
        assertFalse(Files.getFileAttributeView(failed.resolve("image"), java.nio.file.attribute.UserDefinedFileAttributeView.class).list().contains(Gfs2VolumeAllocation.RECEIPT));
        for (int i = 1; i < runner.timeouts.size(); i++) { assertTrue(runner.timeouts.get(i) <= runner.timeouts.get(i - 1)); }
        cleanStage();
    }
    @Test public void threeFallocMismatchesNeverPublishOrFallbackToFull() throws Exception {
        volume.setAllocation(VolumeAllocationPolicy.request("falloc", "qcow2", null)); runner.mismatches = 100;
        reject(() -> adapter.create(volume, pool, null, 60000), "after 3 attempt(s)");
        assertEquals(3, runner.createCount); assertFalse(Files.exists(target()));
        assertTrue(runner.calls.stream().filter(c -> c.size()>1 && "create".equals(c.get(1))).allMatch(c -> c.contains("preallocation=falloc")));
        cleanStage();
    }
    @Test public void compareReadErrorDoesNotRetryOrKeepImage() throws Exception {
        volume.setAllocation(VolumeAllocationPolicy.request("falloc", "raw", null)); runner.compareExit = 4;
        reject(() -> adapter.create(volume, pool, null, 60000), "exit=4"); assertEquals(1, runner.createCount);
        assertFalse(Files.exists(root.resolve(Gfs2AllocationFailureEvidence.DIRECTORY))); cleanStage();
    }
    @Test public void fullMismatchDoesNotRetry() throws Exception {
        runner.mismatches = 1; reject(() -> adapter.create(volume, pool, null, 60000), "after 1 attempt(s)");
        assertEquals(1, runner.createCount); assertFalse(Files.exists(target())); cleanStage();
    }
    @Test public void copyMismatchRetriesWithoutMutatingSource() throws Exception {
        KVMPhysicalDisk source = source(); byte[] before = Files.readAllBytes(Path.of(source.getPath()));
        volume.setAllocation(VolumeAllocationPolicy.request("falloc", "qcow2", null)); runner.mismatches = 1;
        assertEquals("copy", adapter.create(volume, pool, source, 60000).getAllocation().get(VolumeAllocationPolicy.OPERATION));
        assertEquals(2, runner.createCount); assertArrayEquals(before, Files.readAllBytes(Path.of(source.getPath()))); cleanStage();
    }
    @Test public void changedSourceDoesNotRetryContentMismatch() throws Exception {
        KVMPhysicalDisk source = source(); runner.changeSource = Path.of(source.getPath()); runner.mismatches = 1;
        volume.setAllocation(VolumeAllocationPolicy.request("falloc", "qcow2", null));
        reject(() -> adapter.create(volume, pool, source, 60000), "SOURCE_CHANGED"); assertEquals(1, runner.createCount); cleanStage();
    }
    @Test public void preexistingEvidenceSymlinkIsNeverFollowedOrOverwritten() throws Exception {
        Path outside = temporary.newFolder("outside").toPath(); Files.writeString(outside.resolve("keep"), "unchanged");
        Files.createSymbolicLink(root.resolve(Gfs2AllocationFailureEvidence.DIRECTORY), outside);
        volume.setAllocation(VolumeAllocationPolicy.request("falloc", "raw", null)); runner.mismatches = 1;
        adapter.create(volume, pool, null, 60000); assertEquals("unchanged", Files.readString(outside.resolve("keep")));
        try (var files = Files.list(outside)) { assertEquals(1, files.count()); } cleanStage();
    }
    @Test public void preexistingTargetMismatchNeverRecreatesOrDeletesPublishedInode() throws Exception {
        volume.setAllocation(VolumeAllocationPolicy.request("falloc", "raw", null)); adapter.create(volume, pool, null, 60000);
        long inode = ((Number) Files.getAttribute(target(), "unix:ino")).longValue(); runner.mismatches = 1;
        reject(() -> adapter.create(volume, pool, null, 60000), "CONTENT_MISMATCH"); assertEquals(1, runner.createCount);
        assertEquals(inode, ((Number) Files.getAttribute(target(), "unix:ino")).longValue()); cleanStage();
    }

    private com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef rawDisk() throws Exception {
        Files.writeString(target(), "raw-test-file"); volume.setFormat(Storage.ImageFormat.RAW);
        org.apache.cloudstack.storage.to.PrimaryDataStoreTO store = mock(org.apache.cloudstack.storage.to.PrimaryDataStoreTO.class);
        when(store.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint); when(store.getUuid()).thenReturn("pool"); volume.setDataStore(store);
        com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef disk = new com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef();
        disk.defFileBasedDisk(target().toString(), "vda", com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskBus.VIRTIO,
                com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskFmtType.QCOW2);
        return disk;
    }
    private KVMPhysicalDisk cachedDisk() {
        KVMPhysicalDisk physical = org.mockito.Mockito.spy(new KVMPhysicalDisk(target().toString(), volume.getUuid(), pool)); physical.setFormat(PhysicalDiskFormat.QCOW2); return physical;
    }
    @Test public void rawFileOverridesCachedQcow2AndPreservesCacheAndSerial() throws Exception {
        var disk = rawDisk(); disk.setCacheMode(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskCacheMode.WRITEBACK); disk.setSerial("serial");
        Gfs2VolumeAllocation.configureFileDisk(disk, cachedDisk(), volume);
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskType.FILE, disk.getDiskType());
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskFmtType.RAW, disk.getDiskFormatType());
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskCacheMode.WRITEBACK, disk.getCacheMode());
        assertEquals("vda", disk.getDiskLabel()); assertTrue(disk.toString().contains("serial")); assertFalse(disk.toString().contains("metadata_cache"));
    }
    @Test public void rawFileCannotRemainABlockDevice() throws Exception {
        var disk = rawDisk(); disk.defBlockBasedDisk(target().toString(), 0, disk.getBusType());
        Gfs2VolumeAllocation.configureFileDisk(disk, cachedDisk(), volume);
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskType.FILE, disk.getDiskType()); assertTrue(disk.toString().contains("source file="));
    }
    @Test public void gfsQcow2DiskKeepsItsFormatAndMetadataPolicy() throws Exception {
        var disk = rawDisk(); volume.setAllocation(VolumeAllocationPolicy.request("metadata", "qcow2", null)); volume.setFormat(Storage.ImageFormat.QCOW2);
        Gfs2VolumeAllocation.configureFileDisk(disk, cachedDisk(), volume);
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskFmtType.QCOW2, disk.getDiskFormatType());
    }
    @Test public void legacyQcow2DiskDefinitionIsNotReinterpreted() throws Exception {
        var disk = rawDisk(); volume.setAllocation(Map.of()); volume.setFormat(Storage.ImageFormat.QCOW2); Gfs2VolumeAllocation.configureFileDisk(disk, cachedDisk(), volume);
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskFmtType.QCOW2, disk.getDiskFormatType());
    }
    @Test public void rbdRawDoesNotUseTheGfsFileDefinition() throws Exception {
        var disk = rawDisk(); when(((org.apache.cloudstack.storage.to.PrimaryDataStoreTO) volume.getDataStore()).getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        Gfs2VolumeAllocation.configureFileDisk(disk, cachedDisk(), volume); assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskFmtType.QCOW2, disk.getDiskFormatType());
    }
    @Test public void explicitRawCannotUseMissingPhysicalFile() throws Exception {
        var disk = rawDisk(); Files.delete(target()); reject(() -> Gfs2VolumeAllocation.configureFileDisk(disk, cachedDisk(), volume), "RAW_FILE_REQUIRED");
    }
    @Test public void explicitRawCannotUseWrongPoolUuid() throws Exception {
        var disk = rawDisk(); when(pool.getUuid()).thenReturn("foreign"); reject(() -> Gfs2VolumeAllocation.configureFileDisk(disk, cachedDisk(), volume), "RAW_FILE_REQUIRED");
    }
    @Test public void inconsistentCloudRawFormatCannotBeSilentlyCorrected() throws Exception {
        var disk = rawDisk(); volume.setFormat(Storage.ImageFormat.QCOW2); reject(() -> Gfs2VolumeAllocation.configureFileDisk(disk, cachedDisk(), volume), "RAW_FILE_REQUIRED");
    }

    @Test public void hotAttachRawFileUsesFileSourceAndPreservesWriteback() throws Exception {
        var disk = rawDisk(); disk.defBlockBasedDisk(target().toString(), 1, disk.getBusType()); disk.setCacheMode(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskCacheMode.WRITEBACK);
        KVMPhysicalDisk physical = cachedDisk(); when(physical.getFormat()).thenReturn(PhysicalDiskFormat.RAW);
        Gfs2VolumeAllocation.configurePhysicalFileDisk(disk, physical);
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskType.FILE, disk.getDiskType());
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskCacheMode.WRITEBACK, disk.getCacheMode());
    }
    @Test public void rawBlockDeviceIsNotReclassifiedAsFile() throws Exception {
        var disk = rawDisk(); disk.defBlockBasedDisk("/dev/block-test", 1, disk.getBusType());
        KVMPhysicalDisk physical = cachedDisk(); when(physical.getFormat()).thenReturn(PhysicalDiskFormat.RAW); when(physical.getPath()).thenReturn("/dev/block-test");
        Gfs2VolumeAllocation.configurePhysicalFileDisk(disk, physical); assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskType.BLOCK, disk.getDiskType());
    }
    @Test public void rawRbdBlockDefinitionIsPreserved() throws Exception {
        var disk = rawDisk(); disk.defBlockBasedDisk(target().toString(), 1, disk.getBusType());
        KVMPhysicalDisk physical = cachedDisk(); when(physical.getFormat()).thenReturn(PhysicalDiskFormat.RAW); when(pool.getType()).thenReturn(Storage.StoragePoolType.RBD);
        Gfs2VolumeAllocation.configurePhysicalFileDisk(disk, physical); assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskType.BLOCK, disk.getDiskType());
    }
    @Test public void qcow2FileDefinitionKeepsItsDriver() throws Exception {
        var disk = rawDisk(); Gfs2VolumeAllocation.configurePhysicalFileDisk(disk, cachedDisk());
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskFmtType.QCOW2, disk.getDiskFormatType());
    }

    @Test public void rawAttachTransportWithoutIntentKeepsDriverWithoutBackfillingPolicy() throws Exception {
        var disk = rawDisk(); volume.setAllocation(Map.of()); KVMPhysicalDisk physical = cachedDisk();
        assertTrue(Gfs2VolumeAllocation.prepareRawPhysicalFile(physical, volume));
        Gfs2VolumeAllocation.configurePhysicalFileDisk(disk, physical);
        assertEquals(com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef.DiskFmtType.RAW, disk.getDiskFormatType());
        assertTrue(volume.getAllocation().isEmpty());
    }

    private static final class Fake implements Gfs2VolumeAllocation.Runner {
        private final List<List<String>> calls = new ArrayList<>();
        private final Map<Object, Image> images = new ConcurrentHashMap<>();
        boolean failCreate, failCopy, failCompare, falseUsage, wrongFormat, backing, failTimeout;
        int mismatches, compareExit, createCount;
        final List<Path> createdPaths = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>();
        Path changeSource;
        private static final class Image {
            private final String format; private final long size; private final long used;
            Image(String format, long size, long used) { this.format = format; this.size = size; this.used = used; }
        }
        void register(Path p, String format, long size, long used) throws Exception { images.put(Files.getAttribute(p, "unix:ino"), new Image(format, size, used)); }
        @Override public String run(List<String> a, long timeout) {
            calls.add(a); timeouts.add(timeout);
            try {
                if (failTimeout) { throw new CloudRuntimeException("GFS2_ALLOCATION_TIMEOUT"); }
                String op = a.get(0).equals("stat") ? "stat" : a.get(1);
                if (op.equals("measure")) {
                    long size = Long.parseLong(a.get(a.indexOf("--size")+1)); long full = size + (a.contains("qcow2") ? 4096 : 0);
                    return "{\"required\":4096,\"fully-allocated\":"+full+"}";
                }
                if (op.equals("create")) {
                    createCount++; createdPaths.add(Path.of(a.get(a.size()-2)));
                    Path p = Path.of(a.get(a.size()-2)); long size = Long.parseLong(a.get(a.size()-1)); String format = a.get(a.indexOf("-f")+1);
                    long length = size + (format.equals("qcow2") ? 4096 : 0);
                    try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) { f.setLength(length); }
                    register(p, format, size, length); if (failCreate) { throw new CloudRuntimeException("injected create failure"); } return "";
                }
                if (op.equals("convert")) { if (failCopy) { throw new CloudRuntimeException("injected copy failure"); } return ""; }
                if (op.equals("compare")) {
                    if (changeSource != null) { Files.setLastModifiedTime(changeSource, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+10000)); }
                    if (mismatches-- > 0) { throw new Gfs2AllocationCommandRunner.CommandFailure(1, "Content mismatch at offset 4096!"); }
                    if (compareExit > 0) { throw new Gfs2AllocationCommandRunner.CommandFailure(compareExit, "injected compare error"); }
                    if (failCompare) { throw new CloudRuntimeException("compare failed"); } return "";
                }
                Path p = Path.of(a.get(a.size()-1)); Image i = images.get(Files.getAttribute(p, "unix:ino")); long used = falseUsage ? 0 : i.used;
                if (op.equals("stat")) { return (used/512)+":"+Files.size(p); }
                if (op.equals("info")) { return "{\"format\":\""+(wrongFormat?"qcow2":i.format)+"\",\"virtual-size\":"+i.size+",\"actual-size\":"+used+(backing?",\"backing-filename\":\"other\"":"")+"}"; }
                throw new AssertionError(a);
            } catch (CloudRuntimeException e) { throw e; }
            catch (Exception e) { throw new CloudRuntimeException(e.toString(), e); }
        }
    }
}
