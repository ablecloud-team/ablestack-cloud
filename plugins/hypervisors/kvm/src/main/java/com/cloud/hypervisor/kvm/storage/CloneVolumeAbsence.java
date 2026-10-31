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
    public void verifyExistingPool(com.cloud.agent.api.to.StorageFilerTO expected,String uuid) throws Exception {
        verify(readExistingPool(com.cloud.hypervisor.kvm.resource.LibvirtConnection.getConnection(),expected),uuid);
    }
    KVMStoragePool readExistingPool(org.libvirt.Connect connection,com.cloud.agent.api.to.StorageFilerTO expected) throws Exception {
        org.libvirt.StoragePool storage=connection.storagePoolLookupByUUIDString(expected.getUuid());
        try {
            if (!expected.getUuid().equals(storage.getUUIDString()) || storage.getInfo().state!=org.libvirt.StoragePoolInfo.StoragePoolState.VIR_STORAGE_POOL_RUNNING)
                throw new IOException("The original storage pool is not active");
            com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolDef definition=new com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolXMLParser().parseStoragePoolXML(storage.getXMLDesc(0));
            if(definition==null || !(expected.getType()==Storage.StoragePoolType.RBD && definition.getPoolType()==com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolDef.PoolType.RBD
                    || expected.getType()==Storage.StoragePoolType.SharedMountPoint && definition.getPoolType()==com.cloud.hypervisor.kvm.resource.LibvirtStoragePoolDef.PoolType.DIR))
                throw new IOException("Storage pool type cannot be confirmed");
            String path=expected.getType()==Storage.StoragePoolType.RBD ? definition.getSourceDir() : definition.getTargetPath();
            if(!Objects.equals(expected.getPath(),path)) throw new IOException("Storage pool path changed");
            // Do not use getStoragePool: its fallback can activate or recreate a
            // missing pool. This descriptor has no adaptor or mutable pool handle.
            LibvirtStoragePool pool=new LibvirtStoragePool(expected.getUuid(),storage.getName(),expected.getType(),null,null);
            pool.setLocalPath(definition.getTargetPath());pool.setSourceHost(definition.getSourceHost());pool.setSourcePort(definition.getSourcePort());pool.setSourceDir(definition.getSourceDir());
            if(expected.getType()==Storage.StoragePoolType.RBD && definition.getAuthUserName()!=null) {
                org.libvirt.Secret secret=connection.secretLookupByUUIDString(definition.getSecretUUID());
                try {pool.setAuthUsername(definition.getAuthUserName());pool.setAuthSecret(java.util.Base64.getEncoder().encodeToString(secret.getByteValue()));}
                finally {secret.free();}
            }
            return pool;
        } finally {storage.free();}
    }
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
        try (java.util.stream.Stream<Path> files=Files.walk(root)) {
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
