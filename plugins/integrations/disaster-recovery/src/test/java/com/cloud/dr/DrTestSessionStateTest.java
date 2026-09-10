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

package com.cloud.dr;

import java.util.Date;

import org.junit.Assert;
import org.junit.Test;

public class DrTestSessionStateTest {

    @Test
    public void failedSessionWithoutCleanupOrTargetDoesNotBlockNewTest() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.FAILED);
        session.setCleanupRequired(false);

        Assert.assertFalse(DrTestSessionState.blocksNewTest(session));
        Assert.assertTrue(DrTestSessionState.canSoftCloseFailedSession(session, true));
    }

    @Test
    public void failedSessionWithCleanupObligationBlocksNewTest() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.FAILED);
        session.setCleanupRequired(true);

        Assert.assertTrue(DrTestSessionState.blocksNewTest(session));
        Assert.assertFalse(DrTestSessionState.canSoftCloseFailedSession(session, true));
    }

    @Test
    public void failedSessionWithTargetVmBlocksNewTest() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.FAILED);
        session.setCleanupRequired(false);
        session.setTargetVmId(259L);

        Assert.assertTrue(DrTestSessionState.blocksNewTest(session));
        Assert.assertFalse(DrTestSessionState.canSoftCloseFailedSession(session, true));
    }

    @Test
    public void artifactFreePreMaterializationFailureCanBeRecovered() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.FAILED);
        session.setCleanupRequired(false);

        Assert.assertTrue(DrTestSessionState.canRecoverArtifactFreePreMaterializationFailure(session));

        session.setArtifactManifest("[{\"path\":\"/mnt/glue-gfs/test.qcow2\"}]");
        Assert.assertFalse(DrTestSessionState.canRecoverArtifactFreePreMaterializationFailure(session));
    }

    @Test
    public void partiallyRestoredSoftClosedSessionCanResumeMaterialization() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.ARTIFACTS_READY);
        session.setCleanupRequired(true);
        session.setRemoved(new Date());

        Assert.assertTrue(DrTestSessionState.canRestoreSoftClosedPreMaterializationFailure(session));

        session.setTargetVmId(259L);
        Assert.assertFalse(DrTestSessionState.canRestoreSoftClosedPreMaterializationFailure(session));
    }

    @Test
    public void cleanupFailureAlwaysBlocksNewTest() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.CLEANUP_FAILED);
        session.setCleanupRequired(false);

        Assert.assertTrue(DrTestSessionState.blocksNewTest(session));
    }

    @Test
    public void terminalFailedRunWithoutArtifactsCanBeSoftClosed() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.REQUESTED);
        DrRunVO run = new DrRunVO(38L, DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_FAILED);

        Assert.assertTrue(DrTestSessionState.isTerminalRunFailureWithoutArtifacts(session, run));
    }

    @Test
    public void terminalFailedRunWithArtifactManifestCannotBeSoftClosed() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.PREPARING);
        session.setArtifactManifest("[{\"path\":\"/mnt/glue-gfs/test.qcow2\"}]");
        DrRunVO run = new DrRunVO(38L, DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_FAILED);

        Assert.assertFalse(DrTestSessionState.isTerminalRunFailureWithoutArtifacts(session, run));
    }

    @Test
    public void terminalFailedRunWithTargetVmCannotBeSoftClosed() {
        DrTestSessionVO session = new DrTestSessionVO(38L, 104L, DrTestSessionState.PREPARING);
        session.setTargetVmId(259L);
        DrRunVO run = new DrRunVO(38L, DrConstants.RUN_TYPE_TEST_FAILOVER);
        run.setState(DrConstants.RUN_STATE_FAILED);

        Assert.assertFalse(DrTestSessionState.isTerminalRunFailureWithoutArtifacts(session, run));
    }
}
