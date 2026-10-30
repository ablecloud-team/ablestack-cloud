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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackRestoreSourceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private List<String> chain() throws Exception {
        Path directory = folder.newFolder().toPath();
        return List.of(Files.writeString(directory.resolve("full.qcow2"), "full").toString(),
                Files.writeString(directory.resolve("inc1.qcow2"), "inc1").toString(),
                Files.writeString(directory.resolve("inc2.qcow2"), "inc2").toString());
    }

    @Test
    public void completeChainSelectsExactlyTheRequestedPoint() throws Exception {
        List<String> paths = chain();
        assertEquals(paths.get(2), LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(paths));
        assertEquals(paths.get(1), LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(paths.subList(0, 2)));
        assertEquals(paths.get(0), LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(paths.subList(0, 1)));
    }

    @Test
    public void missingSelectedPointNeverFallsBackToAnEarlierBackup() throws Exception {
        List<String> paths = chain();
        Files.delete(Path.of(paths.get(2)));
        CloudRuntimeException failure = assertThrows(CloudRuntimeException.class,
                () -> LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(paths));
        assertTrue(failure.getMessage().contains(paths.get(2)));
    }

    @Test
    public void missingFullOrMiddleIncrementalCannotBeSkipped() throws Exception {
        for (int index : List.of(0, 1)) {
            List<String> paths = chain();
            String missing = paths.get(index);
            Files.delete(Path.of(missing));
            CloudRuntimeException failure = assertThrows(CloudRuntimeException.class,
                    () -> LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(paths));
            assertTrue(failure.getMessage().contains(missing));
        }
    }

    @Test
    public void directoryCannotReplaceARequiredBackupFile() throws Exception {
        List<String> paths = chain();
        Path selected = Path.of(paths.get(2));
        Files.delete(selected);
        Files.createDirectory(selected);
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(paths));
    }

    @Test
    public void emptyOrBlankChainEntriesAreRejected() throws Exception {
        List<String> paths = chain();
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(null));
        assertThrows(CloudRuntimeException.class, () -> LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(List.of()));
        for (String invalid : Arrays.asList(null, "", " ")) {
            assertThrows(CloudRuntimeException.class,
                    () -> LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(Arrays.asList(paths.get(0), invalid)));
            assertThrows(CloudRuntimeException.class,
                    () -> LibvirtAblestackFileRestoreHelper.getRestorableFileBackupPath(Arrays.asList(invalid, paths.get(2))));
        }
    }
}
