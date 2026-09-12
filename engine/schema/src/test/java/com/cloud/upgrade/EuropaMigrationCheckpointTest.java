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
package com.cloud.upgrade;

import java.sql.Connection;
import java.sql.SQLException;

import com.cloud.upgrade.dao.EuropaSchemaUpgrade;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class EuropaMigrationCheckpointTest {
    @Test
    public void completedPhaseIsNotExecutedAgain() throws SQLException {
        Connection connection = mock(Connection.class);
        Runnable migration = mock(Runnable.class);
        try (MockedStatic<EuropaSchemaUpgrade> journal = Mockito.mockStatic(EuropaSchemaUpgrade.class)) {
            journal.when(() -> EuropaSchemaUpgrade.hasState(connection, "phase", "Complete")).thenReturn(true);
            new DatabaseUpgradeChecker().runEuropaPhase(connection, "phase", migration);
            verify(migration, never()).run();
            journal.verify(() -> EuropaSchemaUpgrade.begin(connection, "phase"), never());
        }
    }

    @Test
    public void failedPhaseRemainsPendingAndRetryCanComplete() throws SQLException {
        Connection connection = mock(Connection.class);
        Runnable migration = mock(Runnable.class);
        RuntimeException failure = new CloudRuntimeException("injected SQL failure");
        Mockito.doThrow(failure).doNothing().when(migration).run();
        try (MockedStatic<EuropaSchemaUpgrade> journal = Mockito.mockStatic(EuropaSchemaUpgrade.class)) {
            DatabaseUpgradeChecker checker = new DatabaseUpgradeChecker();
            try {
                checker.runEuropaPhase(connection, "phase", migration);
                fail("Expected migration failure");
            } catch (RuntimeException actual) {
                assertSame(failure, actual);
            }
            journal.verify(() -> EuropaSchemaUpgrade.begin(connection, "phase"));
            journal.verify(() -> EuropaSchemaUpgrade.complete(connection, "phase"), never());
            checker.runEuropaPhase(connection, "phase", migration);
            journal.verify(() -> EuropaSchemaUpgrade.complete(connection, "phase"));
        }
    }

    @Test
    public void upgradeLockTimeoutReleasesReferenceWithoutUnlockingAnotherOwner() {
        GlobalLock lock = mock(GlobalLock.class);
        try (MockedStatic<GlobalLock> locks = Mockito.mockStatic(GlobalLock.class)) {
            locks.when(() -> GlobalLock.getInternLock(Mockito.anyString())).thenReturn(lock);
            try {
                new DatabaseUpgradeChecker().check();
                fail("Expected lock timeout");
            } catch (CloudRuntimeException expected) {
                verify(lock).releaseRef();
                verify(lock, never()).unlock();
            }
        }
    }
}
