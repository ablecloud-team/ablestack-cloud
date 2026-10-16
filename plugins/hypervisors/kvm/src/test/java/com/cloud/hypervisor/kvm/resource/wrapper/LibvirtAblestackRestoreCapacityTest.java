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

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackNasRestoreBackupCommand;
import org.apache.cloudstack.backup.AblestackCommvaultRestoreBackupCommand;
import org.apache.cloudstack.backup.AblestackNetBackupRestoreBackupCommand;
import org.apache.cloudstack.backup.AblestackVeeamRestoreBackupCommand;
import org.apache.cloudstack.backup.AblestackRestoreBackupCommand;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.logging.log4j.Logger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class LibvirtAblestackRestoreCapacityTest {
    private static final long GIB = 1024L * 1024L * 1024L;
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void defaultAndConfiguredPercentagesKeepTheMinimumBufferAndLegacyRounding() {
        assertEquals(200 * GIB, LibvirtAblestackRestoreCapacity.bufferBytes(1000 * GIB));
        try (var policy = LibvirtAblestackRestoreCapacity.begin(15)) {
            assertEquals(150 * GIB, LibvirtAblestackRestoreCapacity.bufferBytes(1000 * GIB));
            assertEquals(150 * GIB, LibvirtAblestackRestoreCapacity.bufferBytes(1000 * GIB + 1));
            assertEquals(10 * GIB, LibvirtAblestackRestoreCapacity.bufferBytes(GIB));
        }
        try (var policy = LibvirtAblestackRestoreCapacity.begin(0)) {
            assertEquals(10 * GIB, LibvirtAblestackRestoreCapacity.bufferBytes(1000 * GIB));
        }
        assertEquals(20, LibvirtAblestackRestoreCapacity.bufferPercent());
    }

    @Test
    public void closingAWorkerScopeRestoresThePreviousPolicyEvenAfterFailure() {
        try (var outer = LibvirtAblestackRestoreCapacity.begin(15)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = LibvirtAblestackRestoreCapacity.begin(25)) {
                    assertEquals(25, LibvirtAblestackRestoreCapacity.bufferPercent());
                    throw new IllegalStateException("restore failed");
                }
            });
            assertEquals(15, LibvirtAblestackRestoreCapacity.bufferPercent());
        }
        assertEquals(20, LibvirtAblestackRestoreCapacity.bufferPercent());
    }

    @Test
    public void concurrentWorkersDoNotShareTheirPolicies() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var policy = LibvirtAblestackRestoreCapacity.begin(15)) {
            assertEquals(Integer.valueOf(20), worker.submit(LibvirtAblestackRestoreCapacity::bufferPercent).get(5, TimeUnit.SECONDS));
            assertEquals(15, LibvirtAblestackRestoreCapacity.bufferPercent());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void invalidPoliciesAndNegativeSizesCannotWeakenTheCheck() {
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackRestoreCapacity.begin(-1));
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackRestoreCapacity.begin(101));
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackRestoreCapacity.bufferBytes(-1));
        assertEquals(20, LibvirtAblestackRestoreCapacity.bufferPercent());
        try (var policy = LibvirtAblestackRestoreCapacity.begin(100)) {
            assertEquals(Long.MAX_VALUE, LibvirtAblestackRestoreCapacity.bufferBytes(Long.MAX_VALUE));
        }
    }

    @Test
    public void detachedCommandRetainsItsCapturedValueAndOlderCommandsDefaultToTwenty() {
        Gson gson = new Gson();
        for (Class<? extends AblestackRestoreBackupCommand> type : List.of(AblestackNasRestoreBackupCommand.class,
                AblestackCommvaultRestoreBackupCommand.class, AblestackNetBackupRestoreBackupCommand.class, AblestackVeeamRestoreBackupCommand.class)) {
            AblestackRestoreBackupCommand command = gson.fromJson("{}", type);
            assertEquals(20, command.getPrimaryStorageCapacityBufferPercent());
            command.setPrimaryStorageCapacityBufferPercent(15);
            AblestackRestoreBackupCommand saved = gson.fromJson(gson.toJson(command), type);
            command.setPrimaryStorageCapacityBufferPercent(30);
            assertEquals(15, saved.getPrimaryStorageCapacityBufferPercent());
            assertThrows(IllegalArgumentException.class, () -> command.setPrimaryStorageCapacityBufferPercent(101));
            assertThrows(IllegalArgumentException.class, () -> gson.fromJson("{\"primaryStorageCapacityBufferPercent\":-1}", type)
                    .getPrimaryStorageCapacityBufferPercent());
        }
    }

    @Test
    public void thirdPartyHostReservationsUseTheSavedPrimaryPercentageAndLegacyDefault() throws Exception {
        LibvirtComputingResource resource = Mockito.mock(LibvirtComputingResource.class);
        Mockito.when(resource.getAbleCvtBackupPath()).thenReturn("/scripts/commvault.sh");
        PrimaryDataStoreTO pool = Mockito.mock(PrimaryDataStoreTO.class);
        Mockito.when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        Path directory = folder.newFolder().toPath();
        ThirdPartyBackupRestore.Plan plan = new ThirdPartyBackupRestore.Plan();
        plan.jobId = "restore-job";
        plan.stageRoot = directory.toString();
        plan.primaryStorageCapacityBufferPercent = 15;
        try (MockedStatic<Script> script = Mockito.mockStatic(Script.class)) {
            script.when(() -> Script.runSimpleBashScriptWithFullResult(Mockito.anyString(), Mockito.eq(30000)))
                    .thenReturn("{\"storageKey\":\"filesystem:test\",\"availableBytes\":" + 1175 * GIB + "}");
            var configured = LibvirtAblestackPrimaryRestoreCapacity.prepare(resource, folder.newFolder().toPath(), plan,
                    List.of(pool), List.of(directory.resolve("volume").toString()), List.of(1000 * GIB));
            assertEquals(1150 * GIB, configured.primaryClaims.get(0).requiredBytes);
            plan.primaryStorageCapacityBufferPercent = null;
            var legacy = LibvirtAblestackPrimaryRestoreCapacity.prepare(resource, folder.newFolder().toPath(), plan,
                    List.of(pool), List.of(directory.resolve("volume").toString()), List.of(1000 * GIB));
            assertEquals(1200 * GIB, legacy.primaryClaims.get(0).requiredBytes);
        }
    }

    @Test
    public void rbdTargetAndTemporarySourcePoolChecksUseTheSameCapturedPercentage() throws Exception {
        KVMStoragePool pool = Mockito.mock(KVMStoragePool.class);
        Mockito.when(pool.getSourceDir()).thenReturn("pool");
        Class<?> sourceClass = Class.forName(LibvirtAblestackRbdRestoreHelper.class.getName() + "$RbdSourceImage");
        var constructor = sourceClass.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class);
        constructor.setAccessible(true);
        Object source = constructor.newInstance("pool/image", "", "", "", "");
        List<String> chain = List.of("/backup/full.raw", "/backup/incremental.rbdiff");
        try (MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) ->
                Mockito.when(mock.info(Mockito.any(QemuImgFile.class))).thenReturn(Map.of(QemuImg.VIRTUAL_SIZE, String.valueOf(1000 * GIB))));
             MockedStatic<Script> script = Mockito.mockStatic(Script.class)) {
            script.when(() -> Script.runSimpleBashScriptWithFullResult(Mockito.anyString(), Mockito.eq(5000)))
                    .thenReturn(1175 * GIB + "\n__CS_COMMAND_EXIT__=0\n");
            Runnable targetPool = () -> ReflectionTestUtils.invokeMethod(LibvirtAblestackRbdRestoreHelper.class,
                    "validateRbdStorageSpace", "trace", pool, chain, 5);
            Runnable sourcePool = () -> ReflectionTestUtils.invokeMethod(LibvirtAblestackRbdRestoreHelper.class,
                    "validateRbdStorageSpace", "trace", source, chain, 5);
            assertThrows(CloudRuntimeException.class, targetPool::run);
            assertThrows(CloudRuntimeException.class, sourcePool::run);
            try (var policy = LibvirtAblestackRestoreCapacity.begin(15)) {
                targetPool.run();
                sourcePool.run();
            }
            assertThrows(CloudRuntimeException.class, targetPool::run);
            assertThrows(CloudRuntimeException.class, sourcePool::run);
        }
    }

    @Test
    public void planAndIndividualVolumeChecksBothUseTheCapturedPercentage() throws Exception {
        Path directory = folder.newFolder().toPath();
        Path target = Files.writeString(directory.resolve("volume"), "original");
        String source = directory.resolve("backup.qcow2").toString();
        Logger logger = Mockito.mock(Logger.class);
        FileStore filesystem = Mockito.mock(FileStore.class);
        Mockito.when(filesystem.getUsableSpace()).thenReturn(1175 * GIB);
        PrimaryDataStoreTO pool = Mockito.mock(PrimaryDataStoreTO.class);
        Mockito.when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        try (MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) ->
                Mockito.when(mock.info(Mockito.any(QemuImgFile.class))).thenReturn(Map.of(QemuImg.VIRTUAL_SIZE, String.valueOf(1000 * GIB))));
             MockedStatic<Files> files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
            files.when(() -> Files.getFileStore(directory)).thenReturn(filesystem);
            Runnable plan = () -> LibvirtAblestackFileRestoreHelper.validatePrimaryStorageSpaceForFileRestorePlan(
                    "trace", logger, "NAS", List.of(target.toString()), List.of(List.of(source)), List.of(pool));
            Runnable volume = () -> ReflectionTestUtils.invokeMethod(LibvirtAblestackFileRestoreHelper.class,
                    "validatePrimaryStorageSpaceForFileRestore", "trace", logger, source, target.toString());
            assertThrows(CloudRuntimeException.class, plan::run);
            assertThrows(CloudRuntimeException.class, volume::run);
            try (var policy = LibvirtAblestackRestoreCapacity.begin(15)) {
                plan.run();
                volume.run();
            }
            assertThrows(CloudRuntimeException.class, plan::run);
        }
        assertEquals("original", Files.readString(target));
    }
}
