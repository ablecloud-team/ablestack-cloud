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
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import com.cloud.storage.Storage;
public class CloneVolumeAbsenceTest {
    static final String ID="11111111-1111-4111-8111-111111111111";
    KVMStoragePool pool() { KVMStoragePool p=mock(KVMStoragePool.class);when(p.getSourceDir()).thenReturn("rbd");when(p.getAuthUserName()).thenReturn("admin");when(p.getAuthSecret()).thenReturn("YWJjZA==");when(p.getSourceHost()).thenReturn("scvm1,scvm2");return p; }
    void probe(String images,String trash,String maps) throws Exception {
        new RbdVolumeAllocation((args,timeout)->{if(args.contains("device"))return maps;if(args.contains("trash"))return trash;return images;}).requireCloneVolumeAbsent(ID,pool());
    }
    void blocked(String images,String trash,String maps) throws Exception {
        try {probe(images,trash,maps);fail("absence was incorrectly confirmed");}catch(com.cloud.utils.exception.CloudRuntimeException expected) { }
    }
    @Test public void emptyListingsConfirmAbsence() throws Exception {probe("[]","[]","[]");}
    @Test public void finalImageBlocks() throws Exception {blocked(new com.google.gson.Gson().toJson(List.of(ID)),"[]","[]");}
    @Test public void temporaryImageBlocks() throws Exception {blocked(new com.google.gson.Gson().toJson(List.of(ID+".allocation-stage")),"[]","[]");}
    @Test public void trashBlocks() throws Exception {blocked("[]",new com.google.gson.Gson().toJson(List.of(Map.of("name",ID))),"[]");}
    @Test public void mappingBlocks() throws Exception {blocked("[]","[]",new com.google.gson.Gson().toJson(List.of(Map.of("name",ID))));}
    @Test public void failedListingCannotBecomeAbsence() throws Exception {
        try {new RbdVolumeAllocation((args,timeout)->{throw new com.cloud.utils.exception.CloudRuntimeException("pool unavailable");}).requireCloneVolumeAbsent(ID,pool());fail();}
        catch(com.cloud.utils.exception.CloudRuntimeException expected){assertEquals("pool unavailable",expected.getMessage());}
    }
    @Test public void gfsTemporaryFileBlocks() throws Exception {
        Path root=Files.createTempDirectory("clone-absence-test");Path directory=Files.createDirectories(root.resolve("a/b/c/d/e"));Path file=directory.resolve(ID+".allocation-stage");
        try {Files.writeString(file,"owned fixture");try{CloneVolumeAbsence.requireNoFiles(root,ID);fail();}catch(java.io.IOException expected){}Files.delete(file);CloneVolumeAbsence.requireNoFiles(root,ID);}
        finally {try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.deleteIfExists(p);}}
    }
}
