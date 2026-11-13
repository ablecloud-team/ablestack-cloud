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

package org.apache.cloudstack.storage.resource;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.apache.cloudstack.storage.template.UploadEntity;
import com.cloud.utils.script.Script;

public class PostUploadChecksumTest {
    private Path file;
    private static class FakeScript extends Script {
        int executions;
        final List<String> arguments = new ArrayList<>();
        FakeScript() { super("not-executed"); }
        @Override public void add(String... values) { if (arguments != null) arguments.addAll(Arrays.asList(values)); }
        @Override public String execute() { executions++; return null; }
    }
    @Before public void prepare() throws Exception { file = Files.createTempFile("t03-checksum-", ".qcow2"); Files.write(file, "abc".getBytes(StandardCharsets.UTF_8)); }
    @After public void cleanup() throws Exception { Files.deleteIfExists(file); }
    private UploadEntity entity(UploadEntity.ResourceType type, String checksum) { UploadEntity e = new UploadEntity(); e.setResourceType(type); e.setChksum(checksum); return e; }
    @Test public void md5AndUppercaseDigestsAreAccepted() {
        for (String value : new String[] {"900150983cd24fb0d6963f7d28e17f72", "900150983CD24FB0D6963F7D28E17F72"}) {
            FakeScript script = new FakeScript(); assertNull(PostUploadChecksum.execute(script, entity(UploadEntity.ResourceType.TEMPLATE, value), file.toString())); assertEquals(1, script.executions);
        }
    }
    @Test public void sha256AndSha512PrefixesAreAccepted() {
        for (String value : new String[] {"{SHA-256}ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "{SHA-512}ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"}) {
            FakeScript script = new FakeScript(); assertNull(PostUploadChecksum.execute(script, entity(UploadEntity.ResourceType.TEMPLATE, value), file.toString())); assertEquals(1, script.executions);
        }
    }
    @Test public void mismatchNeverExecutesTheMovingScriptAndRetainsBytes() throws Exception {
        FakeScript script = new FakeScript(); assertTrue(PostUploadChecksum.execute(script, entity(UploadEntity.ResourceType.TEMPLATE, "00000000000000000000000000000000"), file.toString()).contains("does not match")); assertEquals(0, script.executions); assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
    }
    @Test public void unknownAlgorithmAndMissingFileFailBeforeScript() {
        FakeScript script = new FakeScript(); assertTrue(PostUploadChecksum.execute(script, entity(UploadEntity.ResourceType.TEMPLATE, "{NO-SUCH-ALGORITHM}abc"), file.toString()).contains("Unsupported")); assertTrue(PostUploadChecksum.execute(script, entity(UploadEntity.ResourceType.TEMPLATE, "900150983cd24fb0d6963f7d28e17f72"), file + ".missing").contains("Unable")); assertEquals(0, script.executions);
    }
    @Test public void templatesDoNotPassChecksumAsTheCloudFlag() {
        FakeScript script = new FakeScript(); PostUploadChecksum.addChecksumArgument(script, entity(UploadEntity.ResourceType.TEMPLATE, "900150983cd24fb0d6963f7d28e17f72"), "900150983cd24fb0d6963f7d28e17f72"); assertTrue(script.arguments.isEmpty());
    }
    @Test public void legacyVolumeScriptAndAbsentTemplateChecksumArePreserved() {
        FakeScript volume = new FakeScript(); UploadEntity e = entity(UploadEntity.ResourceType.VOLUME, "900150983cd24fb0d6963f7d28e17f72"); PostUploadChecksum.addChecksumArgument(volume, e, "900150983cd24fb0d6963f7d28e17f72"); assertEquals(Arrays.asList("-c", "900150983cd24fb0d6963f7d28e17f72"), volume.arguments); assertNull(PostUploadChecksum.execute(volume, e, file + ".missing")); assertEquals(1, volume.executions);
        FakeScript template = new FakeScript(); assertNull(PostUploadChecksum.execute(template, entity(UploadEntity.ResourceType.TEMPLATE, null), file.toString())); assertEquals(1, template.executions);
    }
}
