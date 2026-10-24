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
package org.apache.cloudstack.storage.snapshot;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.cloud.agent.api.Answer;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Snapshot;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.engine.subsystem.api.storage.*;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.*;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.mockito.junit.MockitoJUnitRunner;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event;

@RunWith(MockitoJUnitRunner.class)
public class SnapshotArchiveServiceTest {
    @Spy @InjectMocks private SnapshotServiceImpl service = new SnapshotServiceImpl();
    @Mock private SnapshotDataFactory _snapshotFactory;
    @Mock private DataMotionService motionSrv;
    @Mock private SnapshotDao _snapshotDao;
    @Mock private EndPointSelector epSelector;
    private SnapshotObject source;
    private SnapshotInfo destination;
    private DataStore store;
    private AtomicReference<State> destinationState;

    @Before public void setup() throws Exception {
        source = mock(SnapshotObject.class);
        destination = mock(SnapshotInfo.class);
        store = mock(DataStore.class);
        destinationState = new AtomicReference<>(State.Allocated);
        lenient().when(source.getId()).thenReturn(7L);
        lenient().when(source.getState()).thenReturn(Snapshot.State.BackedUp);
        lenient().when(destination.getId()).thenReturn(7L);
        lenient().when(destination.getStatus()).thenAnswer(call -> destinationState.get());
        lenient().when(destination.getDataStoreRefId()).thenReturn(11L);
        lenient().when(destination.getDataStore()).thenReturn(store);
        lenient().when(store.getRole()).thenReturn(DataStoreRole.Image);
        lenient().when(destination.getPath()).thenReturn("snapshots/2/9/exact-file");
        lenient().when(destination.getPhysicalSize()).thenReturn(4096L);
        lenient().when(destination.getTO()).thenReturn(mock(SnapshotObjectTO.class));
        lenient().doAnswer(call -> {
            Event event = call.getArgument(0);
            if (event == Event.CreateOnlyRequested) destinationState.set(State.Creating);
            if (event == Event.OperationFailed) destinationState.set(State.Allocated);
            return true;
        }).when(destination).processEvent(any(Event.class));
        lenient().doAnswer(call -> { destinationState.set(State.Ready); return true; }).when(destination).processEvent(eq(Event.OperationSucceeded), any(Answer.class));
        lenient().when(_snapshotFactory.getSnapshots(7L, null)).thenReturn(List.of(destination));
        lenient().when(_snapshotFactory.getSnapshot(7L, store)).thenReturn(destination);
    }
    private void copyResult(boolean failed) {
        doAnswer(call -> {
            CopyCmdAnswer answer = failed ? new CopyCmdAnswer("storage write failed") : mock(CopyCmdAnswer.class);
            CopyCommandResult result = new CopyCommandResult(null, answer);
            if (failed) result.setResult("storage write failed");
            AsyncCompletionCallback callback = call.getArgument(2);
            callback.complete(result);
            return null;
        }).when(motionSrv).copyAsync(eq(source), eq(destination), any());
    }
    @Test public void archiveCopyFailurePreservesPrimaryAndGlobalSnapshotAndRetryReference() throws Exception {
        copyResult(true);
        assertThrows(CloudRuntimeException.class, () -> service.backupSnapshotForArchive(source));
        assertEquals(State.Allocated, destinationState.get());
        verify(source).processEvent(Snapshot.Event.OperationNotPerformed);
        verify(source, never()).processEvent(Event.DestroyRequested);
        verify(source, never()).processEvent(Snapshot.Event.OperationFailed);
        verify(service, never()).cleanupOnSnapshotBackupFailure(any());
        verify(_snapshotDao, never()).remove(anyLong());
        verify(store, never()).create(any());
    }
    @Test public void archiveRetryCopiesIntoTheSameAllocatedReference() {
        copyResult(true);
        assertThrows(CloudRuntimeException.class, () -> service.backupSnapshotForArchive(source));
        copyResult(false);
        assertSame(destination, service.backupSnapshotForArchive(source));
        assertEquals(State.Ready, destinationState.get());
        verify(motionSrv, times(2)).copyAsync(eq(source), eq(destination), any());
        verify(store, never()).create(any());
    }
    @Test public void synchronousSubmissionFailureAlsoResetsDestinationForRetry() throws Exception {
        doThrow(new CloudRuntimeException("endpoint unavailable")).when(motionSrv).copyAsync(eq(source), eq(destination), any());
        assertThrows(CloudRuntimeException.class, () -> service.backupSnapshotForArchive(source));
        assertEquals(State.Allocated, destinationState.get());
        verify(source).processEvent(Snapshot.Event.OperationNotPerformed);
        verify(service, never()).cleanupOnSnapshotBackupFailure(any());
    }
    @Test public void existingReadyCopyCannotAllocateAnotherBackup() {
        destinationState.set(State.Ready);
        assertThrows(CloudRuntimeException.class, () -> service.archiveDestination(source));
        verifyNoInteractions(motionSrv);
        verify(store, never()).create(any());
    }
    @Test public void newlyAllocatedDestinationMustBeReadBackWithExactReferenceId() {
        when(_snapshotFactory.getSnapshots(7L, null)).thenReturn(Collections.emptyList(), List.of(destination));
        doReturn(store).when(service).findSnapshotImageStore(source);
        assertSame(destination, service.archiveDestination(source));
        assertSame(destination, service.archiveDestination(source));
        verify(store, times(1)).create(source);
    }
    private EndPoint verificationEndpoint(List<String> files) {
        destinationState.set(State.Ready);
        EndPoint endpoint = mock(EndPoint.class);
        when(epSelector.select(destination)).thenReturn(endpoint);
        when(endpoint.sendMessage(any(QuerySnapshotZoneCopyCommand.class))).thenReturn(new QuerySnapshotZoneCopyAnswer(null, files));
        return endpoint;
    }
    @Test public void exactPhysicalFileIsRequiredForBackupVerification() {
        verificationEndpoint(List.of("snapshots/2/9/exact-file"));
        assertTrue(service.verifySnapshotBackup(destination));
    }
    @Test public void siblingFileListingIsNotEvidenceOfTheMissingSnapshot() {
        verificationEndpoint(List.of("snapshots/2/9/another-file"));
        assertFalse(service.verifySnapshotBackup(destination));
    }
    @Test public void noEndpointOrInvalidMetadataCannotAuthorizePrimaryDeletion() {
        destinationState.set(State.Ready);
        assertFalse(service.verifySnapshotBackup(destination));
        when(destination.getPhysicalSize()).thenReturn(0L);
        assertFalse(service.verifySnapshotBackup(destination));
        verify(epSelector, times(1)).select(destination);
    }
}
