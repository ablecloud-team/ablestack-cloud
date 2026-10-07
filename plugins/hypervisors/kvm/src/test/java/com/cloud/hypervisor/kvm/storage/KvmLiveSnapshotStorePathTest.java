/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.hypervisor.kvm.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;

public class KvmLiveSnapshotStorePathTest {
    @Test public void secondarySnapshotReceiptUsesRelativePathAndPhysicalSize() throws Exception {
        Path root = Files.createTempDirectory("secondary-snapshot-");
        Path file = root.resolve("snapshots/21/304/receipt");
        Files.createDirectories(file.getParent()); Files.write(file, new byte[123]);
        KVMStoragePool secondary = mock(KVMStoragePool.class);
        when(secondary.getLocalPath()).thenReturn(root.toString());
        SnapshotObjectTO receipt = new KVMStorageProcessor(null, null).createRunningSnapshotResult(secondary, file.toString());
        assertEquals("snapshots/21/304/receipt", receipt.getPath()); assertEquals(Long.valueOf(123), receipt.getPhysicalSize());
        Files.delete(file); Files.delete(file.getParent()); Files.delete(file.getParent().getParent()); Files.delete(root.resolve("snapshots")); Files.delete(root);
    }
    @Test public void primaryOnlySnapshotKeepsItsAbsoluteLocation() {
        assertEquals("/mnt/glue-gfs/snapshots/receipt", new KVMStorageProcessor(null, null).createRunningSnapshotResult(null, "/mnt/glue-gfs/snapshots/receipt").getPath());
    }
    @Test(expected=CloudRuntimeException.class) public void primaryPathCannotBeClaimedAsSecondarySnapshot() {
        KVMStoragePool secondary = mock(KVMStoragePool.class); when(secondary.getLocalPath()).thenReturn("/mnt/secondary");
        new KVMStorageProcessor(null, null).createRunningSnapshotResult(secondary, "/mnt/glue-gfs/snapshots/receipt");
    }
    @Test(expected=CloudRuntimeException.class) public void secondaryPathTraversalIsRejected() {
        KVMStoragePool secondary = mock(KVMStoragePool.class); when(secondary.getLocalPath()).thenReturn("/mnt/secondary");
        new KVMStorageProcessor(null, null).createRunningSnapshotResult(secondary, "/mnt/secondary/../glue-gfs/snapshots/receipt");
    }
}
