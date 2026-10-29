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
import java.util.ArrayList;
import java.util.List;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.storage.to.VolumeExport;
import com.cloud.storage.Storage;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class VerifiedVolumeExportTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private VolumeObjectTO source;
    private Path directory;
    private List<List<String>> calls;
    private String failAt;
    private String artifactInfo;
    private boolean changed;
    private int sourceReads;
    @Before public void setup() throws Exception {
        directory = folder.newFolder("image-store").toPath(); calls = new ArrayList<>();
        source = new VolumeObjectTO(); source.setUuid("source"); source.setPath("source-image"); source.setSize(1048576L); source.setFormat(Storage.ImageFormat.RAW);
        source.setAllocation(java.util.Map.of("allocation.requested.policy", "full"));
        artifactInfo = "{\"format\":\"qcow2\",\"virtual-size\":1048576}";
    }
    private String run(List<String> c, long timeout) {
        calls.add(c); assertTrue(timeout > 0);
        if (c.get(1).equals(failAt)) { throw new CloudRuntimeException("injected " + failAt); }
        if (c.get(1).equals("info")) {
            if (c.get(c.size()-1).equals("source")) {
                sourceReads++; return "{\"format\":\"raw\",\"virtual-size\":" + (changed && sourceReads > 1 ? "2097152" : "1048576") + "}";
            }
            return artifactInfo;
        }
        if (c.get(1).equals("measure")) { return "{\"required\":1048576}"; }
        if (c.get(1).equals("convert")) {
            try { Files.writeString(Path.of(c.get(c.size()-1)), "independent image"); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        return "";
    }
    private VolumeObjectTO export() throws Exception { return new VerifiedVolumeExport(this::run).export(source, "pool", "source", directory, 60000); }
    private void empty() throws Exception { try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); } }
    private void reject(String expected) throws Exception {
        try { export(); fail("must reject"); }
        catch (CloudRuntimeException e) { assertTrue(e.toString(), e.toString().contains(expected)); }
        empty();
    }
    @Test public void fullSourceProducesIndependentSparseQcowWithoutAllocationEvidence() throws Exception {
        VolumeObjectTO result = export();
        assertEquals(Storage.ImageFormat.QCOW2, result.getFormat()); assertEquals(source.getSize(), result.getSize());
        assertTrue(result.getPath().endsWith(VolumeExport.SUFFIX)); assertTrue(result.getAllocation().isEmpty());
        assertEquals("source-image", result.getExportVerification().get("sourcePath"));
        assertTrue(Files.isRegularFile(directory.resolve(result.getPath())));
        assertTrue(calls.stream().anyMatch(c -> c.get(1).equals("compare")));
        assertTrue(calls.stream().filter(c -> c.get(1).equals("convert")).findFirst().get().contains("preallocation=off"));
        assertEquals("source-image", source.getPath()); assertFalse(source.getAllocation().isEmpty());
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }
    @Test public void convertFailureRemovesOnlyOwnedStageAndRetrySucceeds() throws Exception { failAt="convert"; reject("injected"); failAt=null; export(); }
    @Test public void compareFailureNeverPublishes() throws Exception { failAt="compare"; reject("injected"); }
    @Test public void corruptImageNeverPublishes() throws Exception { failAt="check"; reject("injected"); }
    @Test public void commandTimeoutNeverPublishes() throws Exception { failAt="info"; reject("injected"); }
    @Test public void sourceMutationNeverPublishes() throws Exception { changed=true; reject("SOURCE_CHANGED"); }
    @Test public void unexpectedRawOutputNeverPublishes() throws Exception { artifactInfo="{\"format\":\"raw\",\"virtual-size\":1048576}"; reject("FORMAT_OR_SIZE"); }
    @Test public void backingChainOutputNeverPublishes() throws Exception { artifactInfo="{\"format\":\"qcow2\",\"virtual-size\":1048576,\"backing-filename\":\"parent\"}"; reject("FORMAT_OR_SIZE"); }
    @Test public void encryptedInputNeverStartsCommands() throws Exception { source.setEncryptFormat("luks"); reject("UNSUPPORTED"); assertTrue(calls.isEmpty()); }
    @Test public void interruptedThreadNeverStartsCommands() throws Exception { Thread.currentThread().interrupt(); try { reject("INTERRUPTED"); } finally { Thread.interrupted(); } assertTrue(calls.isEmpty()); }
    @Test public void capacityFailureCreatesNoStage() throws Exception {
        VerifiedVolumeExport adapter = new VerifiedVolumeExport((c,t) -> c.get(1).equals("measure") ? "{\"required\":9223372036787666943}" : run(c,t));
        try { adapter.export(source,"pool","source",directory,60000); fail("capacity"); } catch (CloudRuntimeException e) { assertTrue(e.toString().contains("CAPACITY")); }
        empty();
    }
}
