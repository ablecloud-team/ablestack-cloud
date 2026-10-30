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

package com.cloud.hypervisor.kvm.storage;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
/** An unavailable pool, process table, listing or mount can never prove absence. */
public class CloneVolumeAbsence {
    public void verify(KVMStoragePool pool,String uuid) throws IOException {
        if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException("Invalid clone volume UUID");
        synchronized ((pool.getUuid()+"/"+uuid).intern()) {
            requireNoWriter(uuid);
            if (pool.getType()==Storage.StoragePoolType.RBD) {
                new RbdVolumeAllocation().requireCloneVolumeAbsent(uuid,pool);
            } else if (pool.getType()==Storage.StoragePoolType.SharedMountPoint) {
                Path root=Paths.get(pool.getLocalPath()).toRealPath();
                if (!Files.getFileStore(root).type().equalsIgnoreCase("gfs2")) throw new IOException("GFS2 mount is not confirmed");
                requireNoFiles(root,uuid);
            } else throw new IOException("Clone cleanup supports qualified RBD/GFS2 only");
            requireNoWriter(uuid);
        }
    }
    static void requireNoFiles(Path root,String uuid) throws IOException {
        try (java.util.stream.Stream<Path> files=Files.walk(root,4)) {
            if(files.anyMatch(p->p.getFileName().toString().contains(uuid))) throw new IOException("Clone image or temporary file remains");
        }
    }
    protected void requireNoWriter(String uuid) throws IOException {
        try (DirectoryStream<Path> processes=Files.newDirectoryStream(Paths.get("/proc"),"[0-9]*")) {
            for(Path process:processes) {
                try {
                    String args=new String(Files.readAllBytes(process.resolve("cmdline")),java.nio.charset.StandardCharsets.UTF_8);
                    if(args.contains(uuid)) throw new IOException("A process still references this clone volume");
                } catch(NoSuchFileException ended) { /* Process exited between enumeration and read. */ }
            }
        }
    }
}
