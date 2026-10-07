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
package org.apache.cloudstack.storage.datastore.driver;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.StorageAction;
import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CreateObjectCommand;
import org.apache.cloudstack.storage.to.SnapshotObjectTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.storage.CreateSnapshotPayload;

public class PrimarySnapshotDestinationTest {
    @Test public void selectedSecondaryAndSnapshotOptionsReachTheAgent() {
        CloudStackPrimaryDataStoreDriverImpl driver = new CloudStackPrimaryDataStoreDriverImpl();
        driver.epSelector = mock(EndPointSelector.class);
        EndPoint ep = mock(EndPoint.class); when(driver.epSelector.select(any(), eq(StorageAction.TAKESNAPSHOT), eq(false))).thenReturn(ep);
        SnapshotInfo snapshot = mock(SnapshotInfo.class); VolumeInfo base = mock(VolumeInfo.class); when(snapshot.getBaseVolume()).thenReturn(base); when(base.getPassphraseId()).thenReturn(null); when(base.getKmsWrappedKeyId()).thenReturn(null); when(base.getKmsKeyId()).thenReturn(null);
        SnapshotObjectTO to = new SnapshotObjectTO(); to.setVolume(new VolumeObjectTO()); when(snapshot.getTO()).thenReturn(to);
        DataStore image = mock(DataStore.class); NfsTO nfs = new NfsTO("nfs://server/secondary", null); when(image.getTO()).thenReturn(nfs); when(snapshot.getImageStore()).thenReturn(image);
        CreateSnapshotPayload payload = new CreateSnapshotPayload(); payload.setQuiescevm(true); payload.setKvmIncrementalSnapshot(true); payload.setSnapshotPath("snapshots/owned"); when(snapshot.getPayload()).thenReturn(payload);
        when(ep.sendMessage(any())).thenAnswer(call -> new Answer((Command)call.getArgument(0), true, null));
        AsyncCompletionCallback<CreateCmdResult> callback = mock(AsyncCompletionCallback.class);
        driver.takeSnapshot(snapshot, callback);
        ArgumentCaptor<CreateCmdResult> result = ArgumentCaptor.forClass(CreateCmdResult.class); verify(callback).complete(result.capture()); assertTrue(result.getValue().isSuccess());
        ArgumentCaptor<Command> cmd = ArgumentCaptor.forClass(Command.class); verify(ep).sendMessage(cmd.capture());
        SnapshotObjectTO actual = (SnapshotObjectTO)((CreateObjectCommand)cmd.getValue()).getData();
        assertSame(nfs, actual.getImageStore()); assertTrue(actual.isKvmIncrementalSnapshot()); assertTrue(actual.getquiescevm()); assertEquals("snapshots/owned", actual.getPath());
        verify(callback).complete(any());
    }
}
