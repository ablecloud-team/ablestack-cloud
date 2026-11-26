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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.cloud.agent.api.ImportVolumeAllocationCommand;
import com.cloud.agent.api.to.StorageFilerTO;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeAllocationImport;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real qemu-img data and allocation checks on disposable images; no libvirt VM is used. */
public class Gfs2VolumeImportAllocationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path root;
    private KVMStoragePool pool;
    private StorageFilerTO filer;
    private Gfs2VolumeImportAllocation adapter;
    private static final long SIZE = 8 * 1024 * 1024;
    @Before public void setup() throws Exception {
        try { Assume.assumeTrue(new ProcessBuilder("qemu-img","--version").redirectErrorStream(true).start().waitFor() == 0); }
        catch (java.io.IOException missing) { Assume.assumeNoException(missing); }
        root = temporary.newFolder("pool").toPath(); pool = mock(KVMStoragePool.class); filer = mock(StorageFilerTO.class);
        when(pool.getUuid()).thenReturn("pool"); when(pool.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(pool.getLocalPath()).thenReturn(root.toString()); when(pool.getAvailable()).thenReturn(Long.MAX_VALUE);
        when(filer.getUuid()).thenReturn("pool"); when(filer.getType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        adapter = new Gfs2VolumeImportAllocation() {
            @Override protected Path root(KVMStoragePool ignored) { return root; }
            @Override protected Gfs2VolumeAllocation copyAdapter() {
                return new Gfs2VolumeAllocation() { @Override protected Path root(KVMStoragePool ignored) { return root; } };
            }
        };
    }
    @Test public void sevenModesPreserveDataAndKeepAdoptionSeparateFromVerifiedCopy() throws Exception {
        for (String format : List.of("qcow2", "raw")) {
            for (String policy : VolumeAllocationImport.methods(format).keySet()) {
                String id = UUID.randomUUID().toString(); Path source = source(format, policy, id);
                byte[] before = Files.readAllBytes(source);
                ImportVolumeAllocationCommand request = command("IMPORT", id, source.getFileName().toString(), null, policy, format, true, Map.of());
                Gfs2VolumeImportAllocation.Result result = adapter.execute(request, pool);
                Path target = root.resolve(result.volume.getPath());
                run("qemu-img","compare","-f",format,"-F",format,source.toString(),target.toString());
                assertArrayEquals(before, Files.readAllBytes(source));
                assertEquals(policy, result.allocation.get(VolumeAllocationPolicy.FIXED_POLICY));
                assertEquals(VolumeAllocationImport.requiresCopy(policy) ? "copy" : "adopt", result.allocation.get(VolumeAllocationImport.STRATEGY));
                assertEquals(!VolumeAllocationImport.requiresCopy(policy), source.equals(target));
                if (List.of("falloc","full").contains(policy)) assertTrue(Long.parseLong(result.allocation.get(VolumeAllocationPolicy.OBSERVED_BYTES)) >= SIZE);
                Object inode = Files.getAttribute(target,"unix:ino");
                Gfs2VolumeImportAllocation.Result verified = adapter.execute(command("VERIFY",id,source.getFileName().toString(),result.volume.getPath(),policy,format,true,result.allocation),pool);
                assertEquals(result.allocation.get(VolumeAllocationImport.IDENTITY),verified.allocation.get(VolumeAllocationImport.IDENTITY));
                assertEquals(inode,Files.getAttribute(target,"unix:ino"));
            }
        }
    }
    @Test public void fullAndMetadataNeverSilentlyCopyOrZeroUserData() throws Exception {
        for (String policy : List.of("metadata","full")) {
            String id=UUID.randomUUID().toString();Path source=source("qcow2",policy,id);byte[] data=Files.readAllBytes(source);
            reject(() -> adapter.execute(command("IMPORT",id,source.getFileName().toString(),null,policy,"qcow2",false,Map.of()),pool));
            assertArrayEquals(data,Files.readAllBytes(source));assertFalse(Files.exists(root.resolve(id)));
        }
    }
    @Test public void verificationOfMissingCopyCannotCreateOrRepairAnImage() throws Exception {
        String id=UUID.randomUUID().toString();Path source=source("raw","full",id);
        Gfs2VolumeImportAllocation.Result result=adapter.execute(command("IMPORT",id,source.getFileName().toString(),null,"full","raw",true,Map.of()),pool);
        Files.delete(root.resolve(result.volume.getPath()));
        reject(() -> adapter.execute(command("VERIFY",id,source.getFileName().toString(),result.volume.getPath(),"full","raw",true,result.allocation),pool));
        assertFalse(Files.exists(root.resolve(result.volume.getPath())));
    }
    @Test public void sparseImageCannotMasqueradeAsFallocAndSymlinksCannotEscapePool() throws Exception {
        String id=UUID.randomUUID().toString();Path sparse=source("raw","off",id);
        reject(() -> adapter.execute(command("IMPORT",id,sparse.getFileName().toString(),null,"falloc","raw",false,Map.of()),pool));
        Path outside=temporary.newFile("outside").toPath();Path link=root.resolve("linked");Files.createSymbolicLink(link,outside);
        reject(() -> adapter.execute(command("IMPORT",id,"linked",null,"off","raw",false,Map.of()),pool));
    }
    @Test public void requestedRawDriverCannotHideAnActualQcow2Header() throws Exception {
        String id=UUID.randomUUID().toString();Path image=source("qcow2","off",id);
        try (java.io.RandomAccessFile file=new java.io.RandomAccessFile(image.toFile(),"rw")) { file.setLength(SIZE); }
        reject(() -> adapter.execute(command("IMPORT",id,image.getFileName().toString(),null,"off","raw",false,Map.of()),pool));
    }
    @Test public void productionCapabilityDoesNotDeclareExt4AsGfs2() {
        reject(() -> new Gfs2VolumeImportAllocation().importCapabilities(pool,"qcow2"));
    }
    @Test public void readOnlyCapabilityHelpWorksWithRootWorkingDirectory() throws Exception {
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(javaBinary, "-Duser.dir=/", "-cp", System.getProperty("java.class.path"), RootProbe.class.getName())
                .redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(output, 0, child.waitFor());
    }
    public static class RootProbe {
        public static void main(String[] arguments) {
            if (Path.of("--help").toAbsolutePath().getParent().getFileName() != null) throw new AssertionError("root probe precondition");
            String help = Gfs2AllocationCommandRunner.run(List.of("qemu-img", "convert", "--help"), 10000);
            if (!help.contains("-n")) throw new AssertionError("qemu help was not executed");
        }
    }

    private Path source(String format,String policy,String id) throws Exception {
        Path source=root.resolve("source-"+id);
        run("qemu-img","create","-f",format,"-o","preallocation="+("falloc".equals(policy)?"falloc":"off"),source.toString(),Long.toString(SIZE));
        run("qemu-io","-f",format,"-c","write -P 0x5a 1048576 4096",source.toString());
        return source;
    }
    private ImportVolumeAllocationCommand command(String action,String id,String source,String target,String policy,String format,boolean copy,Map<String,String> evidence) {
        Map<String,String> intent=new java.util.LinkedHashMap<>(VolumeAllocationPolicy.request(policy,format,null));intent.putAll(evidence);
        return new ImportVolumeAllocationCommand(action,filer,source,target,id,2L,SIZE,copy,intent);
    }
    private void run(String... args) throws Exception {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(output,0,process.waitFor());
    }
    private void reject(Runnable call) {
        try { call.run(); fail("Expected import rejection"); } catch (CloudRuntimeException expected) { assertNotNull(expected.getMessage()); }
    }
}
