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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import java.util.List;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.DataStoreRole;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State;
import org.apache.cloudstack.storage.command.QuerySnapshotZoneCopyAnswer;
import org.apache.cloudstack.storage.command.QuerySnapshotZoneCopyCommand;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SnapshotBackupPathTest {
    @InjectMocks private SnapshotServiceImpl service = new SnapshotServiceImpl();
    @Mock private EndPointSelector epSelector;
    @Mock private SnapshotInfo snapshot;
    @Mock private DataStore store;
    @Mock private EndPoint endpoint;
    @Before public void setup() {
        when(snapshot.getDataStore()).thenReturn(store); when(store.getRole()).thenReturn(DataStoreRole.Image);
        lenient().when(store.getTO()).thenReturn(mock(NfsTO.class));
        when(snapshot.getStatus()).thenReturn(State.Ready); when(snapshot.getPhysicalSize()).thenReturn(100L);
        when(snapshot.getTO()).thenReturn(new SnapshotObjectTO()); when(epSelector.select(snapshot)).thenReturn(endpoint);
    }
    private QuerySnapshotZoneCopyAnswer files(String path) {
        return new QuerySnapshotZoneCopyAnswer(mock(QuerySnapshotZoneCopyCommand.class), List.of(path));
    }
    @Test public void verifiedAbsoluteCopyMatchesOnlyItsExactRelativeFile() {
        when(snapshot.getPath()).thenReturn("/mnt/host-mount/snapshots/2/10/abc");
        when(endpoint.sendMessage(any(QuerySnapshotZoneCopyCommand.class))).thenReturn(files("snapshots/2/10/abc"), files("snapshots/2/10/another"));
        assertTrue(service.verifySnapshotBackup(snapshot)); assertFalse(service.verifySnapshotBackup(snapshot));
    }
    @Test public void exportRootAndLegacyAbsoluteResponsesRemainCompatible() {
        when(snapshot.getPath()).thenReturn("/snapshots/2/10/abc");
        when(endpoint.sendMessage(any(QuerySnapshotZoneCopyCommand.class))).thenReturn(files("snapshots/2/10/abc"), files("/snapshots/2/10/abc"));
        assertTrue(service.verifySnapshotBackup(snapshot)); assertTrue(service.verifySnapshotBackup(snapshot));
    }
    @Test public void missingOrFailedAgentResponseCannotVerifyBackup() {
        when(snapshot.getPath()).thenReturn("snapshots/2/10/abc");
        when(endpoint.sendMessage(any(QuerySnapshotZoneCopyCommand.class))).thenReturn(null,
                new QuerySnapshotZoneCopyAnswer(mock(QuerySnapshotZoneCopyCommand.class), "Storage unavailable"));
        assertFalse(service.verifySnapshotBackup(snapshot)); assertFalse(service.verifySnapshotBackup(snapshot));
    }
}
