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
}
