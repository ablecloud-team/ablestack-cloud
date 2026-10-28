// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package com.cloud.hypervisor.kvm.storage;

import java.nio.file.*;
import java.util.*;
import org.apache.cloudstack.storage.command.PrepareSharedMountPointCloneCommand;
import org.apache.cloudstack.storage.command.PrepareSharedMountPointCloneCommand.VolumeCloneSpec;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class AllocatedCloneRollbackTest {
    @Test public void uncertainLiveOverlayAndForeignTargetArePreserved() throws Exception {
        Path directory = Files.createTempDirectory("clone-rollback-test");
        try {
            for (String p : List.of("source", "overlay", "owned", "foreign")) Files.writeString(directory.resolve(p), "keep-" + p);
            KVMStoragePool pool = mock(KVMStoragePool.class); when(pool.getLocalPath()).thenReturn(directory.toString());
            KVMStorageProcessor processor = mock(KVMStorageProcessor.class, CALLS_REAL_METHODS);
            PrepareSharedMountPointCloneCommand command = new PrepareSharedMountPointCloneCommand(null, "i-2-1-VM", true, "operation", List.of());
            List<VolumeCloneSpec> specs = List.of(new VolumeCloneSpec(1,"source","overlay",2,"owned",1024),new VolumeCloneSpec(1,"source","overlay",3,"foreign",1024));
            processor.rollbackPreparedSharedMountPointClone(command,pool,null,Map.of(),specs,new HashSet<>(Set.of("owned")));
            assertTrue(Files.exists(directory.resolve("source"))); assertTrue(Files.exists(directory.resolve("overlay")));
            assertEquals("keep-foreign", Files.readString(directory.resolve("foreign"))); assertFalse(Files.exists(directory.resolve("owned")));
        } finally {
            try (var files = Files.list(directory)) { for (Path p : files.collect(java.util.stream.Collectors.toList())) Files.delete(p); }
            Files.delete(directory);
        }
    }
}
