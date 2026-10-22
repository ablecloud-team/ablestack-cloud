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

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

public class Gfs2AllocationFailureEvidenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private String preserve(Path work, Path pool) {
        return Gfs2AllocationFailureEvidence.preserve(work, pool, "fingerprint", "uuid", "raw", "falloc", 1048576,
                "raw", null, new Gfs2AllocationFailureEvidence.ContentMismatch(new Gfs2AllocationCommandRunner.CommandFailure(1, "Content mismatch")));
    }
    private Path work() throws Exception {
        Path work = temporary.newFolder().toPath(); Files.writeString(work.resolve("image"), "failed-only");
        Files.writeString(work.resolve("zero-1.raw"), "reference"); return work;
    }
    @Test public void occupiedSlotPreservesExactFirstEvidence() throws Exception {
        Path pool = temporary.newFolder("pool").toPath();
        assertTrue(preserve(work(), pool).startsWith("evidence=retained:"));
        byte[] before = Files.readAllBytes(pool.resolve(Gfs2AllocationFailureEvidence.DIRECTORY).resolve("manifest.json"));
        Path second = work(); assertTrue(preserve(second, pool).startsWith("evidence=existing-slot-preserved:"));
        assertArrayEquals(before, Files.readAllBytes(pool.resolve(Gfs2AllocationFailureEvidence.DIRECTORY).resolve("manifest.json")));
        assertTrue(Files.isRegularFile(second.resolve("image")));
    }
    @Test public void oversizedImageDoesNotClaimEvidenceSlot() throws Exception {
        Path pool = temporary.newFolder("pool").toPath(); Path work = work();
        try (RandomAccessFile image = new RandomAccessFile(work.resolve("image").toFile(), "rw")) { image.setLength(Gfs2AllocationFailureEvidence.MAX_IMAGE_BYTES + 1); }
        assertTrue(preserve(work, pool).contains("skipped-image-limit"));
        assertFalse(Files.exists(pool.resolve(Gfs2AllocationFailureEvidence.DIRECTORY))); assertTrue(Files.exists(work.resolve("image")));
    }
    @Test public void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        Path pool = temporary.newFolder("pool").toPath(); Path first = work(), second = work();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> preserve(first, pool)); var b = executor.submit(() -> preserve(second, pool));
            var answers = java.util.List.of(a.get(), b.get());
            assertEquals(1, answers.stream().filter(s -> s.startsWith("evidence=retained:")).count());
            assertEquals(1, answers.stream().filter(s -> s.startsWith("evidence=existing-slot-preserved:")).count());
        } finally { executor.shutdownNow(); }
    }
    @Test public void unexpectedSymlinkInsideOwnedStageIsNotCaptured() throws Exception {
        Path pool = temporary.newFolder("pool").toPath(); Path work = work(); Path other = temporary.newFile("other").toPath();
        Files.createSymbolicLink(work.resolve("zero-external.raw"), other);
        assertTrue(preserve(work, pool).contains("unexpected-stage-entry"));
        assertFalse(Files.exists(pool.resolve(Gfs2AllocationFailureEvidence.DIRECTORY))); assertTrue(Files.exists(other));
    }
}
