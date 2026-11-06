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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackRestoreFileMoveTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private Path source;
    private Path target;
    private FileStore store;
    private MockedStatic<Files> files;

    @Before public void setup() throws Exception {
        source = Files.writeString(folder.getRoot().toPath().resolve("volume"), "retained disk contents");
        target = source.resolveSibling("volume-csrestore-original-attempt");
        store = Mockito.mock(FileStore.class);
        Mockito.when(store.type()).thenReturn("gfs2");
        files = Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS);
        files.when(() -> Files.getFileStore(source.getParent())).thenReturn(store);
    }

    @After public void cleanup() {
        Thread.interrupted();
        files.close();
    }

    private FileSystemException stale() {
        return new FileSystemException(source.toString(), target.toString(), "Stale file handle");
    }

    @Test public void transientGfs2StaleFailureIsRetriedWithoutCopyingTheDisk() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        files.when(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) { throw stale(); }
            return call.callRealMethod();
        });
        Object identity = Files.readAttributes(source, "basic:fileKey").get("fileKey");
        LibvirtAblestackRestoreFileMove.move(source, target);
        assertFalse(Files.exists(source));
        assertEquals("retained disk contents", Files.readString(target));
        assertEquals(identity, Files.readAttributes(target, "basic:fileKey").get("fileKey"));
        assertEquals(2, calls.get());
    }

    @Test public void persistentStaleFailureStopsWithTheSourceIntact() throws Exception {
        files.when(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE))).thenThrow(stale());
        assertThrows(FileSystemException.class, () -> LibvirtAblestackRestoreFileMove.move(source, target));
        assertEquals("retained disk contents", Files.readString(source));
        assertFalse(Files.exists(target));
        files.verify(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE)), Mockito.times(4));
    }

    @Test public void ioErrorsAndStaleFailuresOnOtherFilesystemsAreNotRetried() throws Exception {
        for (String type : new String[]{"gfs2", "nfs4", "xfs"}) {
            Mockito.when(store.type()).thenReturn(type);
            files.clearInvocations();
            FileSystemException error = "gfs2".equals(type)
                    ? new FileSystemException(source.toString(), target.toString(), "Input/output error") : stale();
            files.when(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE))).thenThrow(error);
            assertEquals(error, assertThrows(FileSystemException.class, () -> LibvirtAblestackRestoreFileMove.move(source, target)));
            files.verify(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE)), Mockito.times(1));
        }
        assertEquals("retained disk contents", Files.readString(source));
        assertFalse(Files.exists(target));
    }

    @Test public void aDestinationCreatedAfterTheFailedRenameIsNeverOverwritten() throws Exception {
        files.when(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
            Files.writeString(target, "another operation owns this file");
            throw stale();
        });
        assertThrows(IOException.class, () -> LibvirtAblestackRestoreFileMove.move(source, target));
        assertEquals("retained disk contents", Files.readString(source));
        assertEquals("another operation owns this file", Files.readString(target));
        files.verify(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE)), Mockito.times(1));
    }

    @Test public void aReplacedSourceIsNeverRenamedByTheRetry() throws Exception {
        Path retained = source.resolveSibling("retained-original");
        files.when(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
            Files.move(source, retained);
            Files.writeString(source, "another disk");
            throw stale();
        });
        assertThrows(IOException.class, () -> LibvirtAblestackRestoreFileMove.move(source, target));
        assertEquals("retained disk contents", Files.readString(retained));
        assertEquals("another disk", Files.readString(source));
        assertFalse(Files.exists(target));
        files.verify(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE)), Mockito.times(1));
    }

    @Test public void aChangedSourceWithTheSameInodeIsNotRetried() throws Exception {
        files.when(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
            Files.writeString(source, "changed");
            throw stale();
        });
        assertThrows(IOException.class, () -> LibvirtAblestackRestoreFileMove.move(source, target));
        assertEquals("changed", Files.readString(source));
        assertFalse(Files.exists(target));
        files.verify(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE)), Mockito.times(1));
    }

    @Test public void anAmbiguousCompletedRenameIsLeftForTransactionRollback() throws Exception {
        files.when(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
            call.callRealMethod();
            throw stale();
        });
        assertThrows(IOException.class, () -> LibvirtAblestackRestoreFileMove.move(source, target));
        assertFalse(Files.exists(source));
        assertEquals("retained disk contents", Files.readString(target));
        files.verify(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE)), Mockito.times(1));
    }

    @Test public void interruptionDuringBackoffStopsWithoutAnotherRename() throws Exception {
        files.when(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            throw stale();
        });
        assertThrows(InterruptedIOException.class, () -> LibvirtAblestackRestoreFileMove.move(source, target));
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
        assertEquals("retained disk contents", Files.readString(source));
        assertFalse(Files.exists(target));
        files.verify(() -> Files.move(Mockito.eq(source), Mockito.eq(target), Mockito.eq(StandardCopyOption.ATOMIC_MOVE)), Mockito.times(1));
    }
}
