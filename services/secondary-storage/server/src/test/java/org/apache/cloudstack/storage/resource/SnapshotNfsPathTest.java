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
package org.apache.cloudstack.storage.resource;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.any;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Comparator;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.DataStoreRole;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.QuerySnapshotZoneCopyCommand;
import org.apache.cloudstack.storage.command.QuerySnapshotZoneCopyAnswer;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;

public class SnapshotNfsPathTest {
    private Path mount;
    private NfsSecondaryStorageResource resource;
    private SnapshotObjectTO snapshot;
    @Before public void setup() throws Exception {
        mount = Files.createTempDirectory("snapshot-delete-");
        resource = spy(new NfsSecondaryStorageResource());
        doReturn(mount.toString()).when(resource).getRootDir(any(), any());
        NfsTO store = mock(NfsTO.class);
        org.mockito.Mockito.lenient().when(store.getRole()).thenReturn(DataStoreRole.Image);
        org.mockito.Mockito.lenient().when(store.getUrl()).thenReturn("nfs://10.0.0.1/export");
        snapshot = mock(SnapshotObjectTO.class);
        when(snapshot.getDataStore()).thenReturn(store);
    }
    @After public void cleanup() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(mount)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }
    private Path file(String relative) throws Exception {
        Path path = mount.resolve(relative); Files.createDirectories(path.getParent());
        return Files.writeString(path, "dedicated test data");
    }
    private Answer delete(String path) {
        when(snapshot.getPath()).thenReturn(path);
        DeleteCommand command = mock(DeleteCommand.class); when(command.getData()).thenReturn(snapshot);
        return resource.deleteSnapshot(command);
    }
    @Test public void absoluteMountPathDeletesRealFileAndPreservesNeighbor() throws Exception {
        Path target = file("snapshots/2/10/abc"), neighbor = file("snapshots/2/10/abc-other");
        assertTrue(delete(target.toString()).getResult());
        assertFalse(Files.exists(target)); assertTrue(Files.exists(neighbor));
        assertTrue(delete(target.toString()).getResult());
    }
    @Test public void kvmAbsoluteAliasMustMatchTheExactStoreUrl() throws Exception {
        Path target = file("snapshots/2/10/abc");
        String uuid = java.util.UUID.nameUUIDFromBytes("10.0.0.1/export".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertTrue(delete("/mnt/" + uuid + "/snapshots/2/10/abc").getResult()); assertFalse(Files.exists(target));
        target = file("snapshots/2/10/abc");
        String other = java.util.UUID.nameUUIDFromBytes("10.0.0.1/another-export".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertFalse(delete("/mnt/" + other + "/snapshots/2/10/abc").getResult()); assertTrue(Files.exists(target));
    }
    @Test public void relativeAndLegacyExportPathsDeleteTheSameTarget() throws Exception {
        Path target = file("snapshots/2/10/abc");
        assertTrue(delete("snapshots/2/10/abc").getResult()); assertFalse(Files.exists(target));
        target = file("snapshots/2/10/abc");
        assertTrue(delete("/snapshots/2/10/abc").getResult()); assertFalse(Files.exists(target));
    }
    @Test public void unknownAbsoluteTraversalWildcardAndSharedDirectoryFailClosed() throws Exception {
        Path target = file("snapshots/2/10/abc");
        for (String invalid : new String[]{"/mnt/another/snapshots/2/10/abc", "snapshots/2/10/../10/abc", "snapshots/2/10/*abc*", "snapshots/2/10"}) {
            assertFalse(invalid, delete(invalid).getResult()); assertTrue(Files.exists(target));
        }
        Files.createDirectory(mount.resolve("snapshots/2/10/dir"));
        assertFalse(delete("snapshots/2/10/dir").getResult());
    }
    @Test public void symbolicLinkCannotDeleteAnotherSnapshotsFile() throws Exception {
        Path target = file("snapshots/2/11/other");
        Files.createSymbolicLink(mount.resolve("snapshots/2/11/alias"), target);
        assertFalse(delete("snapshots/2/11/alias").getResult()); assertTrue(Files.exists(target));
    }
    @Test public void invalidCheckpointPreventsPartialDeleteAndKnownCheckpointIsDeleted() throws Exception {
        Path target = file("snapshots/2/10/abc"), checkpoint = file("checkpoints/10/abc.xml");
        when(snapshot.getCheckpointPath()).thenReturn("/unknown/checkpoints/10/abc.xml");
        assertFalse(delete(target.toString()).getResult()); assertTrue(Files.exists(target));
        when(snapshot.getCheckpointPath()).thenReturn(checkpoint.toString());
        assertTrue(delete(target.toString()).getResult()); assertFalse(Files.exists(checkpoint));
    }
    @Test public void descriptorQueryAndDeletionAreRestrictedToTheExactBasename() throws Exception {
        Path first = file("snapshots/2/10/abc.vmdk"), second = file("snapshots/2/10/abc.ovf"), neighbor = file("snapshots/2/10/another.vmdk");
        when(snapshot.getPath()).thenReturn("snapshots/2/10/abc");
        QuerySnapshotZoneCopyCommand command = mock(QuerySnapshotZoneCopyCommand.class); when(command.getSnapshot()).thenReturn(snapshot);
        QuerySnapshotZoneCopyAnswer result = (QuerySnapshotZoneCopyAnswer)resource.execute(command);
        assertTrue(result.getResult()); assertEquals(2, result.getFiles().size());
        assertTrue(delete("snapshots/2/10/abc").getResult());
        assertFalse(Files.exists(first)); assertFalse(Files.exists(second)); assertTrue(Files.exists(neighbor));
    }
    @Test public void copyQueryResolvesAbsolutePathAndRejectsWrongMount() throws Exception {
        Path target = file("snapshots/2/10/abc");
        QuerySnapshotZoneCopyCommand command = mock(QuerySnapshotZoneCopyCommand.class); when(command.getSnapshot()).thenReturn(snapshot);
        when(snapshot.getPath()).thenReturn(target.toString());
        QuerySnapshotZoneCopyAnswer result = (QuerySnapshotZoneCopyAnswer)resource.execute(command);
        assertTrue(result.getResult()); assertEquals(java.util.List.of("snapshots/2/10/abc"), result.getFiles());
        when(snapshot.getPath()).thenReturn("/mnt/unknown/snapshots/2/10/abc");
        assertFalse(resource.execute(command).getResult());
    }
}
