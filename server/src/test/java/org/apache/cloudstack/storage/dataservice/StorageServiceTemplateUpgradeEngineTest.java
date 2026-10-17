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
package org.apache.cloudstack.storage.dataservice;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceTemplateUpgradeDao;

public class StorageServiceTemplateUpgradeEngineTest {
    private static final class Runtime implements StorageServiceTemplateUpgradeEngine.Runtime {
        private final StorageServiceTemplateUpgradeVO row;
        private final List<String> events=new ArrayList<>();
        private final String fail;
        private int root=1;
        private boolean completed;
        Runtime(StorageServiceTemplateUpgradeVO row,String fail){this.row=row;this.fail=fail;}
        private void step(String phase){
            Assert.assertEquals(phase,row.getPhase());events.add(phase);
            if (phase.equals(fail)) throw new CloudRuntimeException("Injected phase failure");
        }
        public void preflight(){step("PREFLIGHT");}
        public void stageRoot(){step("STAGING_ROOT");}
        public void checkpoint(){step("SNAPSHOTTING_CONFIG");}
        public void quiesce(){step("QUIESCING");}
        public void swapRoot(){step("SWAPPING_ROOT");root=2;}
        public void bootTarget(){step("BOOTING_TARGET");}
        public void restoreIdentity(){step("RESTORING_IDENTITY");}
        public void reconcile(){step("RECONCILING");}
        public void verify(){step("VERIFYING");}
        public void commit(){step("COMMITTING");}
        public void restorePreviousRoot(){step("ROLLING_BACK_ROOT");root=1;}
        public void bootPrevious(){step("BOOTING_PREVIOUS");}
        public void reconcilePrevious(){step("RECONCILING_PREVIOUS");}
        public void verifyPrevious(){Assert.assertEquals(1,root);}
        public void finished(boolean success){completed=success;}
    }
    private StorageServiceTemplateUpgradeEngine engine() {
        StorageServiceTemplateUpgradeDao dao=Mockito.mock(StorageServiceTemplateUpgradeDao.class);
        Mockito.when(dao.update(Mockito.anyLong(),Mockito.any())).thenReturn(true);
        return new StorageServiceTemplateUpgradeEngine(dao);
    }
    @Test public void verifiedTargetIsCommittedOnlyAfterEveryDurablePhaseAndKeepsRollbackRoot() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();
        Runtime runtime=new Runtime(row,"");
        engine().execute(row,runtime);
        Assert.assertEquals("COMPLETE",row.getState());Assert.assertEquals(2,runtime.root);Assert.assertTrue(runtime.completed);
        Assert.assertEquals(List.of("PREFLIGHT","STAGING_ROOT","SNAPSHOTTING_CONFIG","QUIESCING","SWAPPING_ROOT","BOOTING_TARGET",
                "RESTORING_IDENTITY","RECONCILING","VERIFYING","COMMITTING"),runtime.events);
    }
    @Test public void eachCutoverFailureCompensatesBeforeReturningRolledBack() {
        for (String failure:List.of("STAGING_ROOT","SNAPSHOTTING_CONFIG","QUIESCING","SWAPPING_ROOT","BOOTING_TARGET","RESTORING_IDENTITY","RECONCILING","VERIFYING","COMMITTING")) {
            StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();Runtime runtime=new Runtime(row,failure);
            Assert.assertThrows(CloudRuntimeException.class,()->engine().execute(row,runtime));
            Assert.assertEquals(failure,"ROLLED_BACK",row.getState());Assert.assertEquals(1,runtime.root);
            Assert.assertNotNull(row.getErrorMessage());Assert.assertFalse(runtime.completed);
        }
    }
    @Test public void preflightFailureCannotStageOrTouchAnyRoot() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();Runtime runtime=new Runtime(row,"PREFLIGHT");
        Assert.assertThrows(CloudRuntimeException.class,()->engine().execute(row,runtime));
        Assert.assertEquals("BLOCKED",row.getState());Assert.assertEquals(List.of("PREFLIGHT"),runtime.events);Assert.assertEquals(1,runtime.root);
    }
    @Test public void failedPreviousBootRetainsTheRecoveryCheckpointAndBlocksOtherUpgrades() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();
        StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);
        Mockito.doThrow(new CloudRuntimeException("target failed")).when(runtime).bootTarget();
        Mockito.doThrow(new CloudRuntimeException("previous failed")).when(runtime).bootPrevious();
        Assert.assertThrows(CloudRuntimeException.class,()->engine().execute(row,runtime));
        Assert.assertEquals("RECOVERY_REQUIRED",row.getState());Assert.assertEquals("TEMPLATE_UPGRADE_RECOVERY_REQUIRED",row.getErrorCode());
        Mockito.verify(runtime,Mockito.never()).commit();Mockito.verify(runtime,Mockito.never()).finished(Mockito.anyBoolean());
    }
    @Test public void cleanupFailureDoesNotRollbackAnAlreadyVerifiedCommittedRoot() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();
        StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);
        Mockito.doThrow(new CloudRuntimeException("cleanup pending")).when(runtime).finished(true);
        engine().execute(row,runtime);
        Assert.assertEquals("COMPLETE",row.getState());
        Assert.assertEquals("TEMPLATE_UPGRADE_CLEANUP_PENDING",row.getErrorCode());
        Mockito.verify(runtime,Mockito.never()).restorePreviousRoot();
    }

    @Test public void restartReplaysOnlyTheDurablePhaseAndLaterSteps() {
        List<String> phases=List.of("PREFLIGHT","STAGING_ROOT","SNAPSHOTTING_CONFIG","QUIESCING","SWAPPING_ROOT","BOOTING_TARGET","RESTORING_IDENTITY","RECONCILING","VERIFYING","COMMITTING");
        for (int first=0;first<phases.size();first++) {
            StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();row.setState("RUNNING");row.setPhase(phases.get(first));
            Runtime resumed=new Runtime(row,"");resumed.root=first>=5?2:1;
            engine().execute(row,resumed);
            Assert.assertEquals(phases.subList(first,phases.size()),resumed.events);Assert.assertEquals("COMPLETE",row.getState());Assert.assertEquals(2,resumed.root);
        }
    }
    @Test public void interruptedRollbackCannotRestartForwardCutover() {
        for (String phase:List.of("ROLLING_BACK_ROOT","BOOTING_PREVIOUS","RECONCILING_PREVIOUS","RECOVERY_REQUIRED")) {
            StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();row.setState("RECOVERY_REQUIRED");row.setPhase(phase);
            Runtime resumed=new Runtime(row,"");resumed.root=2;engine().execute(row,resumed);
            Assert.assertEquals(List.of("ROLLING_BACK_ROOT","BOOTING_PREVIOUS","RECONCILING_PREVIOUS"),resumed.events);
            Assert.assertEquals("ROLLED_BACK",row.getState());Assert.assertEquals(1,resumed.root);
        }
    }
    @Test public void unknownRecoveryPhaseCannotAllocateOrStopVm() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();row.setState("RUNNING");row.setPhase("UNRECOGNIZED");
        Runtime runtime=new Runtime(row,"");Assert.assertThrows(CloudRuntimeException.class,()->engine().execute(row,runtime));Assert.assertTrue(runtime.events.isEmpty());
    }
    @Test public void blockedPreflightReleasesTheDurableWriterReservation() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);
        Mockito.doThrow(new CloudRuntimeException("incompatible")).when(runtime).preflight();Assert.assertThrows(CloudRuntimeException.class,()->engine().execute(row,runtime));
        Mockito.verify(runtime).finished(false);Assert.assertEquals("BLOCKED",row.getState());
    }
    @Test public void nativeCommitBeforeProjectionFailureRetainsTargetAndRecoversForwardWithoutAnotherSwap() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);java.util.concurrent.atomic.AtomicBoolean committed=new java.util.concurrent.atomic.AtomicBoolean();
        Mockito.when(runtime.forwardRecoveryRequired()).thenAnswer(call->committed.get());Mockito.doAnswer(call->{committed.set(true);throw new CloudRuntimeException("projection lost after native commit");}).when(runtime).commit();
        Assert.assertThrows(CloudRuntimeException.class,()->engine().execute(row,runtime));Assert.assertEquals("RECOVERY_REQUIRED",row.getState());Mockito.verify(runtime,Mockito.never()).restorePreviousRoot();Mockito.doNothing().when(runtime).commit();engine().execute(row,runtime);Assert.assertEquals("COMPLETE",row.getState());Mockito.verify(runtime,Mockito.times(1)).swapRoot();Mockito.verify(runtime,Mockito.times(1)).bootTarget();Mockito.verify(runtime,Mockito.never()).restorePreviousRoot();Mockito.verify(runtime,Mockito.times(2)).verify();
    }
    @Test public void uncertainCommitObservationAfterFailureCannotFallThroughToDestructivePreviousRootSwap() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);Mockito.when(runtime.forwardRecoveryRequired()).thenReturn(false).thenThrow(new CloudRuntimeException("native readback unavailable"));Mockito.doThrow(new CloudRuntimeException("commit transport lost")).when(runtime).commit();Assert.assertThrows(CloudRuntimeException.class,()->engine().execute(row,runtime));Assert.assertEquals("RECOVERY_REQUIRED",row.getState());Mockito.verify(runtime,Mockito.never()).restorePreviousRoot();
    }
    @Test public void retainedPrecommitFailureRestoresLatestRootWithoutHistoricalReplay() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);
        Mockito.doThrow(new CloudRuntimeException("retained activation failed")).when(runtime).reconcilePrevious();Mockito.when(runtime.compensateLatestSourceRequired()).thenReturn(true);
        Assert.assertThrows(CloudRuntimeException.class,()->engine().rollback(row,runtime));Assert.assertEquals("BLOCKED",row.getState());Assert.assertEquals("RETAINED_ROOT_FAILED_LATEST_SOURCE_RESTORED",row.getErrorCode());Mockito.verify(runtime).compensateLatestSource();Mockito.verify(runtime,Mockito.never()).verifyPrevious();Mockito.verify(runtime).finished(false);
    }
    @Test public void retainedCommitReceiptGapForcesForwardRecoveryAndCannotSwapBackLatestRoot() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);
        Mockito.when(runtime.forwardRecoveryRequired()).thenReturn(false,true);Mockito.doThrow(new CloudRuntimeException("native commit completed but DAO receipt failed")).when(runtime).verifyPrevious();Mockito.when(runtime.compensateLatestSourceRequired()).thenReturn(true);
        Assert.assertThrows(CloudRuntimeException.class,()->engine().rollback(row,runtime));Assert.assertEquals("RECOVERY_REQUIRED",row.getState());Assert.assertEquals("COMMITTED_RETAINED_ROOT_FINALIZATION_REQUIRED",row.getErrorCode());Mockito.verify(runtime,Mockito.never()).compensateLatestSource();
    }
    @Test public void failedLatestRootCompensationRemainsHeldRecoveryRequired() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);
        Mockito.doThrow(new CloudRuntimeException("retained activation failed")).when(runtime).reconcilePrevious();Mockito.when(runtime.compensateLatestSourceRequired()).thenReturn(true);Mockito.doThrow(new CloudRuntimeException("source resume unavailable")).when(runtime).compensateLatestSource();
        Assert.assertThrows(CloudRuntimeException.class,()->engine().rollback(row,runtime));Assert.assertEquals("RECOVERY_REQUIRED",row.getState());Assert.assertNull(row.getCompleted());Mockito.verify(runtime,Mockito.never()).finished(Mockito.anyBoolean());
    }
    @Test public void committedRetainedRecoveryResumesOnlyVerificationAndFinalize() {
        StorageServiceTemplateUpgradeVO row=new StorageServiceTemplateUpgradeVO();StorageServiceTemplateUpgradeEngine.Runtime runtime=Mockito.mock(StorageServiceTemplateUpgradeEngine.Runtime.class);Mockito.when(runtime.forwardRecoveryRequired()).thenReturn(true);
        engine().rollback(row,runtime);Mockito.verify(runtime).verifyPrevious();Mockito.verify(runtime,Mockito.never()).restorePreviousRoot();Mockito.verify(runtime,Mockito.never()).bootPrevious();Mockito.verify(runtime,Mockito.never()).compensateLatestSource();
    }
}
