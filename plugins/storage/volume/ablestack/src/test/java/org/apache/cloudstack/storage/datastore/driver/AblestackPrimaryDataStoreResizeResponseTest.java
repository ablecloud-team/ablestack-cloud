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
package org.apache.cloudstack.storage.datastore.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import org.apache.cloudstack.engine.subsystem.api.storage.CreateCmdResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.volume.VolumeObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.ResizeVolumeAnswer;
import com.cloud.agent.api.storage.ResizeVolumeCommand;
import com.cloud.storage.ResizeVolumePayload;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

@RunWith(MockitoJUnitRunner.Silent.class)
public class AblestackPrimaryDataStoreResizeResponseTest {
    @InjectMocks private AblestackPrimaryDataStoreDriverImpl driver;
    @Mock private StorageManager storageMgr;
    @Mock private VolumeDao volumeDao;
    @Mock private PrimaryDataStoreDao primaryStoreDao;
    @Mock private VolumeObject volume;
    @Mock private AsyncCompletionCallback<CreateCmdResult> callback;
    private StoragePool pool;
    private VolumeVO record;

    @Before
    public void setUp() {
        pool = mock(StoragePool.class, withSettings().extraInterfaces(DataStore.class));
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.SharedMountPoint);
        when(pool.getPath()).thenReturn("/test-only-pool");
        when(volume.getDataStore()).thenReturn((DataStore) pool);
        when(volume.getUuid()).thenReturn("test-volume");
        when(volume.getId()).thenReturn(7L);
        when(volume.getPath()).thenReturn("original-path");
        when(volume.getSize()).thenReturn(1073741824L);
        when(volume.getpayload()).thenReturn(new ResizeVolumePayload(2147483648L, null, null, null, false, "test-vm", new long[]{1L}, false));
        record = new VolumeVO(Volume.Type.DATADISK, "test", 1L, 1L, 2L, 3L, Storage.ProvisioningType.THIN, 1073741824L, null, null, null);
        record.setPath("original-path");
        record.setChainInfo("original-chain");
        record.setPoolId(4L);
        when(volumeDao.findById(7L)).thenReturn(record);
    }

    private void respond(Answer answer) throws Exception {
        when(storageMgr.sendToPool(eq(pool), any(long[].class), any(ResizeVolumeCommand.class))).thenReturn(answer);
        driver.resize(volume, callback);
    }

    private CreateCmdResult result() {
        ArgumentCaptor<CreateCmdResult> capture = ArgumentCaptor.forClass(CreateCmdResult.class);
        verify(callback).complete(capture.capture());
        return capture.getValue();
    }

    private void assertFailedWithoutMutation(String details) {
        CreateCmdResult result = result();
        assertTrue(result.isFailed());
        assertTrue(result.getResult(), result.getResult().contains(details));
        assertFalse(result.getResult().contains("ClassCastException"));
        verify(volume, never()).setSize(any());
        verify(volume, never()).update();
        verify(volumeDao, never()).update(anyLong(), any());
        assertEquals("original-path", record.getPath());
        assertEquals("original-chain", record.getChainInfo());
        assertEquals(Long.valueOf(4L), record.getPoolId());
    }

    @Test
    public void genericFailurePreservesAgentCause() throws Exception {
        Answer answer = new Answer(null, false, "RBD image does not exist");
        respond(answer);
        assertSame(answer, result().getAnswer());
        assertFailedWithoutMutation("RBD image does not exist");
    }

    @Test
    public void nullAnswerFailsWithoutMutation() throws Exception {
        respond(null);
        assertFailedWithoutMutation("No answer received");
    }

    @Test
    public void genericSuccessIsNotAResizeSuccess() throws Exception {
        respond(new Answer(null, true, "ok"));
        assertFailedWithoutMutation("Unexpected resize response type Answer");
    }

    @Test
    public void failedAnswerWithNullDetailsStillFails() throws Exception {
        respond(new Answer(null, false, null));
        assertFailedWithoutMutation("Agent rejected resize");
    }

    @Test
    public void failedAnswerWithBlankDetailsStillFails() throws Exception {
        respond(new Answer(null, false, "  "));
        assertFailedWithoutMutation("Agent rejected resize");
    }

    @Test
    public void typedFailurePreservesDetails() throws Exception {
        respond(new ResizeVolumeAnswer(null, false, "Insufficient storage"));
        assertFailedWithoutMutation("Insufficient storage");
    }

    @Test
    public void typedFailureWithNoDetailsStillFails() throws Exception {
        respond(new ResizeVolumeAnswer(null, false, null));
        assertFailedWithoutMutation("Agent rejected resize");
    }

    @Test
    public void sendExceptionCompletesFailureOnce() throws Exception {
        when(storageMgr.sendToPool(eq(pool), any(long[].class), any(ResizeVolumeCommand.class))).thenThrow(new RuntimeException("Transport unavailable"));
        driver.resize(volume, callback);
        assertFailedWithoutMutation("Transport unavailable");
    }

    @Test
    public void typedSuccessUpdatesReturnedSizeAndPathDetails() throws Exception {
        ResizeVolumeAnswer answer = new ResizeVolumeAnswer(null, true, "resized", 2147483648L);
        answer.setContextParam("volumePath", "verified-new-path");
        answer.setContextParam("chainInfo", "verified-new-chain");
        answer.setContextParam("datastoreUUID", "new-datastore");
        StoragePoolVO destination = mock(StoragePoolVO.class);
        when(destination.getId()).thenReturn(12L);
        when(primaryStoreDao.findByUuid("new-datastore")).thenReturn(destination);
        respond(answer);
        assertFalse(result().isFailed());
        assertSame(answer, result().getAnswer());
        verify(volume).setSize(2147483648L);
        verify(volume).update();
        verify(volumeDao).update(record.getId(), record);
        assertEquals("verified-new-path", record.getPath());
        assertEquals("verified-new-chain", record.getChainInfo());
        assertEquals(Long.valueOf(12L), record.getPoolId());
    }
}
