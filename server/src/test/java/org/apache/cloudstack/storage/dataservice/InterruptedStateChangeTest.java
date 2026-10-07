// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;
import java.util.Date;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
public class InterruptedStateChangeTest {
    private StorageServiceOperationVO orphan() {
        StorageServiceOperationVO row = new StorageServiceOperationVO();row.setInstanceId(7);row.setState("RUNNING");
        row.setRevision(5);row.setHeartbeat(new Date(1));row.setPreviousSnapshotJson("previous");return row;
    }
    private StorageServiceOperationDao dao() {
        StorageServiceOperationDao dao = Mockito.mock(StorageServiceOperationDao.class);
        Mockito.when(dao.update(Mockito.anyLong(), Mockito.any())).thenReturn(true);return dao;
    }
    @Test public void liveHeartbeatCannotTriggerRecovery() {
        StorageServiceOperationVO row = orphan();
        Assert.assertFalse(InterruptedStateChange.stale(row, 10000));Assert.assertTrue(InterruptedStateChange.stale(row, 120001));
        row.setState("COMPLETE");Assert.assertFalse(InterruptedStateChange.stale(row, 200000));
    }
    @Test public void busyGuestWriterKeepsCheckpointAndDoesNotRestoreOrMarkTheOperation() {
        StorageServiceOperationVO row=orphan();StorageServiceOperationDao dao=dao();
        StorageServiceDesiredSnapshot snapshots=Mockito.mock(StorageServiceDesiredSnapshot.class);
        InterruptedStateChange.Runtime runtime=Mockito.mock(InterruptedStateChange.Runtime.class);
        Mockito.doThrow(new CloudRuntimeException("STORAGE_WRITER_BUSY")).when(runtime).idle();
        Assert.assertThrows(CloudRuntimeException.class,()->new InterruptedStateChange(dao,snapshots).recover(row,List.of(),runtime,200000));
        Assert.assertEquals("RUNNING",row.getState());Assert.assertEquals("previous",row.getPreviousSnapshotJson());
        Mockito.verifyNoInteractions(dao,snapshots);Mockito.verify(runtime,Mockito.never()).started(Mockito.any());
    }
    @Test public void interruptedMutationRestoresDesiredAndNativeStateBeforeMarkingRollback() {
        StorageServiceOperationVO row=orphan();StorageServiceDesiredSnapshot snapshots=Mockito.mock(StorageServiceDesiredSnapshot.class);
        InterruptedStateChange.Runtime runtime=Mockito.mock(InterruptedStateChange.Runtime.class);
        new InterruptedStateChange(dao(),snapshots).recover(row,List.of(),runtime,200000);
        org.mockito.InOrder order=Mockito.inOrder(snapshots,runtime);
        order.verify(runtime).idle();order.verify(runtime).started(row);order.verify(snapshots).restore(7L,"previous");
        order.verify(runtime).applyPrevious();order.verify(runtime).verify();order.verify(runtime).finished(row);
        Assert.assertEquals("ROLLED_BACK",row.getState());Assert.assertEquals(100,row.getProgress());
    }
    @Test public void laterVerifiedRevisionIsNeverReplacedByAnOlderSnapshot() {
        StorageServiceOperationVO row=orphan(),latest=new StorageServiceOperationVO();
        latest.setInstanceId(7);latest.setRevision(6);latest.setState("COMPLETE");
        StorageServiceDesiredSnapshot snapshots=Mockito.mock(StorageServiceDesiredSnapshot.class);
        InterruptedStateChange.Runtime runtime=Mockito.mock(InterruptedStateChange.Runtime.class);
        new InterruptedStateChange(dao(),snapshots).recover(row,List.of(latest),runtime,200000);
        Mockito.verifyNoInteractions(snapshots);Mockito.verify(runtime).verifyCurrent(latest);Mockito.verify(runtime,Mockito.never()).applyPrevious();
        Assert.assertEquals("RECONCILED_SUPERSEDED",row.getState());
    }
    @Test public void missingPrepareCheckpointIsBlockedWithoutChangingGuestOrDesiredState() {
        StorageServiceOperationVO row=orphan();row.setPreviousSnapshotJson(null);
        StorageServiceDesiredSnapshot snapshots=Mockito.mock(StorageServiceDesiredSnapshot.class);
        InterruptedStateChange.Runtime runtime=Mockito.mock(InterruptedStateChange.Runtime.class);
        new InterruptedStateChange(dao(),snapshots).recover(row,List.of(),runtime,200000);
        Mockito.verifyNoInteractions(snapshots);Mockito.verify(runtime,Mockito.never()).applyPrevious();
        Assert.assertEquals("BLOCKED",row.getState());Assert.assertEquals("INTERRUPTED_BEFORE_PREPARE",row.getPhase());
    }
    @Test public void failedNativeRecoveryKeepsTheOperationRecoverableAndOriginalDiagnostic() {
        StorageServiceOperationVO row=orphan();row.setDiagnostic("original failure");
        InterruptedStateChange.Runtime runtime=Mockito.mock(InterruptedStateChange.Runtime.class);
        Mockito.doThrow(new CloudRuntimeException("native restore failed")).when(runtime).applyPrevious();
        Assert.assertThrows(CloudRuntimeException.class,()->new InterruptedStateChange(dao(),Mockito.mock(StorageServiceDesiredSnapshot.class))
                .recover(row,List.of(),runtime,200000));
        Assert.assertEquals("RECOVERY_REQUIRED",row.getState());Assert.assertTrue(row.getDiagnostic().startsWith("original failure"));
        Mockito.verify(runtime).finished(row);
    }
    @Test public void explicitRetryOfFailedRecoveryStillRequiresAnIdleGuestAndRestoresPreviousState() {
        StorageServiceOperationVO row=orphan();row.setState("RECOVERY_REQUIRED");row.setHeartbeat(new Date(200000));
        StorageServiceDesiredSnapshot snapshots=Mockito.mock(StorageServiceDesiredSnapshot.class);
        InterruptedStateChange.Runtime runtime=Mockito.mock(InterruptedStateChange.Runtime.class);
        new InterruptedStateChange(dao(),snapshots).recover(row,List.of(),runtime,200000);
        Mockito.verify(runtime).idle();Mockito.verify(snapshots).restore(7L,"previous");Mockito.verify(runtime).verify();
        Assert.assertEquals("ROLLED_BACK",row.getState());
    }

}
