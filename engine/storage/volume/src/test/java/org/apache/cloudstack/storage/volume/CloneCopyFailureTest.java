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
package org.apache.cloudstack.storage.volume;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
public class CloneCopyFailureTest {
    @Test public void failedAnswerWithoutDetailsIsNeverSuccess() {
        CopyCmdAnswer answer=mock(CopyCmdAnswer.class);when(answer.getResult()).thenReturn(false);
        assertNotNull(VolumeServiceImpl.snapshotCopyFailure(new CopyCommandResult(null,answer)));
    }
    @Test public void originalStorageFailureIsPreserved() {
        CopyCmdAnswer answer=mock(CopyCmdAnswer.class);when(answer.getDetails()).thenReturn("original RBD parent not found");
        assertEquals("original RBD parent not found",VolumeServiceImpl.snapshotCopyFailure(new CopyCommandResult(null,answer)));
        VolumeObject volume=mock(VolumeObject.class,CALLS_REAL_METHODS);
        try { volume.handleProcessEventAnswer(answer);fail("failed answer accepted"); }
        catch(com.cloud.utils.exception.CloudRuntimeException expected){assertEquals("original RBD parent not found",expected.getMessage());}
    }
    @Test public void successWithoutVolumeReceiptFailsWithoutNpe() {
        CopyCmdAnswer answer=mock(CopyCmdAnswer.class);when(answer.getResult()).thenReturn(true);
        assertNotNull(VolumeServiceImpl.snapshotCopyFailure(new CopyCommandResult(null,answer)));
    }
    @Test public void validReceiptRemainsSuccessful() {
        CopyCmdAnswer answer=mock(CopyCmdAnswer.class);when(answer.getResult()).thenReturn(true);when(answer.getNewData()).thenReturn(new VolumeObjectTO());
        assertNull(VolumeServiceImpl.snapshotCopyFailure(new CopyCommandResult(null,answer)));
    }
    @Test public void missingTransportResultFailsClosed() {assertNotNull(VolumeServiceImpl.snapshotCopyFailure(null));}
}
