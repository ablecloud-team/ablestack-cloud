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

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.logging.log4j.LogManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/** Exercise the NAS coordinator with real files and journals; only QEMU and storage accounting are mocked. */
public class LibvirtAblestackNasRestoreTransactionTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final String VM = "i-2-nas-VM";
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Path primary;
    private Path nas;
    private Path journals;
    private Path root;
    private Path data;
    private PrimaryDataStoreTO pool;
    private FileStore filesystem;
    private final List<String> commands = new ArrayList<>();
    private MockedStatic<Files> files;
    private MockedStatic<Script> scripts;
    private MockedStatic<LibvirtAblestackRestoreTransaction> transactions;
    private MockedConstruction<QemuImg> qemu;
    private LibvirtAblestackRestoreProvisioning provisioning;
    private LibvirtAblestackRestoreCapacity capacity;

    @Before
    public void setup() throws Exception {
        primary = folder.newFolder("primary").toPath();
        nas = folder.newFolder("nas").toPath();
        journals = folder.getRoot().toPath().resolve("transactions");
        root = Files.writeString(primary.resolve("root"), "old-root");
        data = Files.writeString(primary.resolve("data"), "old-data");
        for (String point : List.of("full", "inc")) {
            Path directory = Files.createDirectories(nas.resolve("vm/" + point));
            for (String name : List.of("root", "datadisk")) {
                Files.writeString(directory.resolve(name + ".qcow2"), name + "-" + point);
            }
        }
        pool = Mockito.mock(PrimaryDataStoreTO.class);
        Mockito.when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        Mockito.when(pool.getUuid()).thenReturn("gfs-pool");
        filesystem = Mockito.mock(FileStore.class);
        Mockito.when(filesystem.toString()).thenReturn("gfs-filesystem");
        Mockito.when(filesystem.getUsableSpace()).thenReturn(2000 * GIB);
        files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS);
        files.when(() -> Files.getFileStore(primary)).thenReturn(filesystem);
        transactions = Mockito.mockStatic(LibvirtAblestackRestoreTransaction.class, Mockito.CALLS_REAL_METHODS);
        transactions.when(() -> LibvirtAblestackRestoreTransaction.vmDirectory(VM)).thenReturn(journals);
        qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) -> Mockito.when(mock.info(any(QemuImgFile.class))).thenAnswer(call -> {
            QemuImgFile image = call.getArgument(0);
            return Map.of(QemuImg.VIRTUAL_SIZE, String.valueOf((image.getFileName().contains("root") ? 100 : 1000) * GIB), "file_format", "qcow2");
        }));
        scripts = Mockito.mockStatic(Script.class);
        scripts.when(() -> Script.runSimpleBashScriptWithFullResult(anyString(), anyInt())).thenAnswer(call -> {
            String command = call.getArgument(0);
            commands.add(command);
            if (command.contains("qemu-img convert")) {
                var target = Pattern.compile("'([^']+)' 2>&1;").matcher(command);
                assertTrue(command, target.find());
                Path prepared = Path.of(target.group(1));
                // Preparing DATA must still leave ROOT at its original contents.
                if (Files.exists(root)) { assertEquals("old-root", Files.readString(root)); }
                Files.writeString(prepared, prepared.getFileName().toString().startsWith("root-") ? "new-root" : "new-data");
            }
            return "__CMD_EXIT__=0";
        });
        provisioning = LibvirtAblestackRestoreProvisioning.begin(Map.of(root.toString(), Storage.ProvisioningType.THIN,
                data.toString(), Storage.ProvisioningType.SPARSE));
        capacity = LibvirtAblestackRestoreCapacity.begin(20);
    }

    @After
    public void close() {
        capacity.close();
        provisioning.close();
        scripts.close();
        qemu.close();
        transactions.close();
        files.close();
    }

    private void restore(boolean incremental) {
        String point = incremental ? "inc" : "full";
        List<String> chains = new ArrayList<>();
        for (String name : List.of("root", "datadisk")) {
            chains.add("vm/full/" + name + ".qcow2" + (incremental ? ";vm/inc/" + name + ".qcow2" : ""));
        }
        ReflectionTestUtils.invokeMethod(new LibvirtAblestackNasRestoreBackupCommandWrapper(), "restoreVmVolumes", VM,
                List.of(root.toString(), data.toString()), "vm/" + point, List.of("root.qcow2", "datadisk.qcow2"), chains, null,
                nas.toString(), 60, null, List.of(pool, pool));
    }

    private Properties journal() throws IOException {
        Path journal;
        try (var paths = Files.list(journals)) {
            journal = paths.filter(path -> path.toString().endsWith(".properties")).findFirst().orElseThrow();
        }
        Properties state = new Properties();
        try (var input = Files.newInputStream(journal)) { state.load(input); }
        return state;
    }

    private void originalsRemain() throws IOException {
        assertEquals("old-root", Files.readString(root));
        assertEquals("old-data", Files.readString(data));
    }

    private void transactionFilesAreGone() throws IOException {
        try (var paths = Files.list(primary)) { assertEquals(2, paths.count()); }
        try (var paths = Files.list(journals)) {
            assertFalse(paths.anyMatch(path -> path.toString().endsWith(".properties")));
        }
    }

    @Test
    public void dataPreparationFailureLeavesBothOriginalsAndCleansPreparedRoot() throws Exception {
        scripts.when(() -> Script.runSimpleBashScriptWithFullResult(Mockito.contains("datadisk.qcow2"), anyInt())).thenReturn("__CMD_EXIT__=1");
        assertThrows(CloudRuntimeException.class, () -> restore(true));
        originalsRemain();
        transactionFilesAreGone();
    }

    @Test
    public void newVmPreparationFailureDoesNotLeaveAPartiallyRestoredRoot() throws Exception {
        Files.delete(root);
        Files.delete(data);
        scripts.when(() -> Script.runSimpleBashScriptWithFullResult(Mockito.contains("datadisk.qcow2"), anyInt())).thenReturn("__CMD_EXIT__=1");
        assertThrows(CloudRuntimeException.class, () -> restore(true));
        assertFalse(Files.exists(root));
        assertFalse(Files.exists(data));
        try (var paths = Files.list(primary)) { assertEquals(0, paths.count()); }
    }

    @Test
    public void secondSwitchFailureRollsRootBackAsWell() throws Exception {
        files.when(() -> Files.move(eq(data), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
            assertEquals("new-root", Files.readString(root));
            throw new IOException("Injected DATA switch failure");
        });
        assertThrows(CloudRuntimeException.class, () -> restore(true));
        originalsRemain();
        transactionFilesAreGone();
    }

    @Test
    public void fullRestoreRetainsAllOriginalsUntilTheWholeVmSwitches() throws Exception {
        AtomicBoolean checked = new AtomicBoolean();
        files.when(() -> Files.move(any(Path.class), eq(data), eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
            Properties state = journal();
            assertEquals("COMMITTING", state.getProperty("phase"));
            assertEquals("old-root", Files.readString(Path.of(state.getProperty("0.original"))));
            assertEquals("old-data", Files.readString(Path.of(state.getProperty("1.original"))));
            checked.set(true);
            return call.callRealMethod();
        });
        restore(false);
        assertTrue(checked.get());
        assertEquals("new-root", Files.readString(root));
        assertEquals("new-data", Files.readString(data));
        transactionFilesAreGone();
    }

    @Test
    public void incrementalRestoreConvertsTheSelectedBackingChainOncePerVolume() throws Exception {
        // Exactly 1,100 GiB + 20%; NAS does not copy another Full/diff overlay onto primary storage.
        Mockito.when(filesystem.getUsableSpace()).thenReturn(1320 * GIB);
        restore(true);
        assertEquals(2, commands.size());
        assertTrue(commands.get(0).contains("vm/inc/root.qcow2"));
        assertTrue(commands.get(0).contains("-S 4k -f qcow2 -O qcow2 -o preallocation=off"));
        assertTrue(commands.get(1).contains("vm/inc/datadisk.qcow2"));
        assertTrue(commands.get(1).contains("preallocation=metadata"));
        assertEquals("root-full", Files.readString(nas.resolve("vm/full/root.qcow2")));
        assertEquals("root-inc", Files.readString(nas.resolve("vm/inc/root.qcow2")));
        transactionFilesAreGone();
    }

    private void assertMissingDataBackupStopsBeforePreparingVolumes(String point) throws Exception {
        Path missing = nas.resolve("vm/" + point + "/datadisk.qcow2");
        Files.delete(missing);
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> restore(true));
        assertTrue(failure.getMessage().contains(missing.toString()));
        assertTrue(commands.isEmpty());
        originalsRemain();
        assertFalse(Files.exists(journals));
    }

    @Test
    public void missingSelectedDataBackupFailsBeforePreparingRoot() throws Exception {
        assertMissingDataBackupStopsBeforePreparingVolumes("inc");
    }

    @Test
    public void missingFullDataBackupFailsBeforePreparingRoot() throws Exception {
        assertMissingDataBackupStopsBeforePreparingVolumes("full");
    }

    @Test
    public void insufficientSpaceForAllPreparedVolumesFailsBeforeAnyConversion() throws Exception {
        Mockito.when(filesystem.getUsableSpace()).thenReturn(1274 * GIB);
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class, () -> restore(true));
        assertTrue(failure.getMessage(), failure.getMessage().contains("retaining all original VM volumes"));
        assertTrue(commands.isEmpty());
        originalsRemain();
    }

    private static final class SimulatedCrash extends Error { }

    @Test
    public void interruptedSwitchBlocksBootUntilJournalRecoveryRestoresBothVolumes() throws Exception {
        files.when(() -> Files.move(eq(data), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE))).thenThrow(new SimulatedCrash());
        assertThrows(SimulatedCrash.class, () -> restore(true));
        assertEquals("new-root", Files.readString(root));
        assertEquals("COMMITTING", journal().getProperty("phase"));
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackRestoreTransaction.assertStartAllowed(VM));
        LibvirtAblestackRestoreTransaction.recoverForVm(LogManager.getLogger(), null, VM, 60);
        originalsRemain();
        transactionFilesAreGone();
        LibvirtAblestackRestoreTransaction.assertStartAllowed(VM);
    }

    @Test
    public void cleanupFailureAfterCommitKeepsNewVolumesAndARetryableJournal() throws Exception {
        AtomicBoolean failOnce = new AtomicBoolean(true);
        files.when(() -> Files.deleteIfExists(any(Path.class))).thenAnswer(call -> {
            Path path = call.getArgument(0);
            if (path.toString().contains("-csrestore-original-") && failOnce.getAndSet(false)) {
                throw new IOException("Injected original cleanup failure");
            }
            return call.callRealMethod();
        });
        restore(true);
        assertEquals("COMMITTED", journal().getProperty("phase"));
        assertEquals("new-root", Files.readString(root));
        assertEquals("new-data", Files.readString(data));
        LibvirtAblestackRestoreTransaction.recoverForVm(LogManager.getLogger(), null, VM, 60);
        assertEquals("new-root", Files.readString(root));
        assertEquals("new-data", Files.readString(data));
        transactionFilesAreGone();
    }

    @Test
    public void nativeRbdVolumesArePreparedBeforeAnyOriginalImageIsRenamed() throws Exception {
        Files.writeString(nas.resolve("vm/full/root.raw"), "root-full");
        Files.writeString(nas.resolve("vm/full/datadisk.raw"), "datadisk-full");
        KVMStoragePoolManager manager = Mockito.mock(KVMStoragePoolManager.class);
        KVMStoragePool ceph = Mockito.mock(KVMStoragePool.class);
        Mockito.when(ceph.getSourceDir()).thenReturn("ceph-pool");
        Mockito.when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        Mockito.when(manager.getStoragePool(Storage.StoragePoolType.RBD, "gfs-pool")).thenReturn(ceph);
        Map<String, String> images = new HashMap<>(Map.of("root", "old-root", "data", "old-data"));
        try (var rbd = Mockito.mockStatic(LibvirtAblestackRbdRestoreHelper.class)) {
            rbd.when(() -> LibvirtAblestackRbdRestoreHelper.getCephPoolAvailableBytes(ceph, 60)).thenReturn(2000 * GIB);
            rbd.when(() -> LibvirtAblestackRbdRestoreHelper.rbdImageExists(eq(ceph), anyString(), eq(60)))
                    .thenAnswer(call -> images.containsKey(call.getArgument(1)));
            rbd.when(() -> LibvirtAblestackRbdRestoreHelper.restoreRbdBackup(anyString(), eq(ceph), anyString(), any(), eq(60), eq(true)))
                    .thenAnswer(call -> {
                        assertEquals("old-root", images.get("root"));
                        assertEquals("old-data", images.get("data"));
                        String name = call.getArgument(2);
                        images.put(name, name.startsWith("root-") ? "new-root" : "new-data");
                        return true;
                    });
            rbd.when(() -> LibvirtAblestackRbdRestoreHelper.renameRbdImage(eq(ceph), anyString(), anyString(), eq(60)))
                    .thenAnswer(call -> {
                        images.put(call.getArgument(2), images.remove(call.getArgument(1)));
                        return true;
                    });
            rbd.when(() -> LibvirtAblestackRbdRestoreHelper.deleteRbdImageIfPresent(eq(ceph), anyString(), eq(60)))
                    .thenAnswer(call -> { images.remove(call.getArgument(1)); return true; });
            ReflectionTestUtils.invokeMethod(new LibvirtAblestackNasRestoreBackupCommandWrapper(), "restoreVmVolumes", VM,
                    List.of("ceph-pool/root", "ceph-pool/data"), "vm/full", List.of("root.raw", "datadisk.raw"), null, null,
                    nas.toString(), 60, manager, List.of(pool, pool));
        }
        assertEquals(Map.of("root", "new-root", "data", "new-data"), images);
    }
}
