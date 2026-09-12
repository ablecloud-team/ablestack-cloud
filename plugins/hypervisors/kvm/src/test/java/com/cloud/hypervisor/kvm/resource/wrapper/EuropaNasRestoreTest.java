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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.cloudstack.utils.qemu.QemuImgException;

public class EuropaNasRestoreTest {
    private boolean restore(String source, String destination) throws Exception {
        PrimaryDataStoreTO store = Mockito.mock(PrimaryDataStoreTO.class);
        Mockito.when(store.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        Method method = LibvirtRestoreBackupCommandWrapper.class.getDeclaredMethod("replaceVolumeWithBackup",
                KVMStoragePoolManager.class, PrimaryDataStoreTO.class, String.class, String.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(new LibvirtRestoreBackupCommandWrapper(), null, store, destination, source, 37);
    }

    @Test
    public void incrementalRestoreFlattensWithLiteralArgumentsAndBoundedTimeout() throws Exception {
        String source = "/nas/backup $(not-a-command).qcow2";
        String destination = "/primary/volume with spaces";
        try (MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) -> {
            Assert.assertEquals(37L, ((Number) context.arguments().get(0)).longValue());
            Mockito.when(mock.info(Mockito.any(QemuImgFile.class))).thenReturn(Map.of(QemuImg.BACKING_FILE, "../parent.qcow2"));
        }); MockedStatic<Script> script = Mockito.mockStatic(Script.class)) {
            script.when(() -> Script.getExecutableAbsolutePath("qemu-img")).thenReturn("/usr/bin/qemu-img");
            Assert.assertTrue(restore(source, destination));
            script.verify(() -> Script.executeCommandForExitValue(37, new String[]{"/usr/bin/qemu-img", "convert", "-O", "qcow2", source, destination}));
            Assert.assertEquals(1, qemu.constructed().size());
        }
    }

    @Test
    public void fullRestoreUsesRsyncWithSameTimeout() throws Exception {
        try (MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) ->
                Mockito.when(mock.info(Mockito.any(QemuImgFile.class))).thenReturn(Map.of()));
             MockedStatic<Script> script = Mockito.mockStatic(Script.class)) {
            script.when(() -> Script.getExecutableAbsolutePath("rsync")).thenReturn("/usr/bin/rsync");
            Assert.assertTrue(restore("/nas/full.qcow2", "/primary/volume"));
            script.verify(() -> Script.executeCommandForExitValue(37, new String[]{"/usr/bin/rsync", "-az", "/nas/full.qcow2", "/primary/volume"}));
        }
    }

    @Test
    public void failedMetadataReadDoesNotCopyAnUnresolvedChain() throws Exception {
        try (MockedConstruction<QemuImg> qemu = Mockito.mockConstruction(QemuImg.class, (mock, context) ->
                Mockito.when(mock.info(Mockito.any(QemuImgFile.class))).thenThrow(new QemuImgException("metadata read failed")));
             MockedStatic<Script> script = Mockito.mockStatic(Script.class)) {
            try {
                restore("/nas/broken.qcow2", "/primary/volume");
                Assert.fail("Must not copy a backup with unknown parent metadata");
            } catch (InvocationTargetException expected) {
                Assert.assertTrue(expected.getCause() instanceof CloudRuntimeException);
            }
            script.verifyNoInteractions();
        }
    }
}
