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
package com.cloud.upgrade.dao;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

public class EuropaSharedFSOperationControlUpgradeTest {
    @Test public void newIndependentPhaseAddsOnlyTheIdempotentControlTable() throws Exception {
        Connection connection=Mockito.mock(Connection.class);Statement statement=Mockito.mock(Statement.class);Mockito.when(connection.createStatement()).thenReturn(statement);
        EuropaSharedFSOperationControlUpgrade.migrate(connection);
        ArgumentCaptor<String> sql=ArgumentCaptor.forClass(String.class);Mockito.verify(statement).execute(sql.capture());
        Assert.assertTrue(sql.getValue().startsWith("CREATE TABLE IF NOT EXISTS cloud.storage_service_operation_control"));
        Assert.assertTrue(sql.getValue().contains("UNIQUE KEY uk_storage_operation_control_operation(operation_id)"));
        Assert.assertTrue(sql.getValue().contains("cancel_requested"));Assert.assertTrue(sql.getValue().contains("lease_json LONGTEXT"));Assert.assertFalse(sql.getValue().contains("ALTER TABLE"));
    }
    @Test public void failedControlMigrationCannotSilentlyAllowVoQueries() throws Exception {
        Connection connection=Mockito.mock(Connection.class);Mockito.when(connection.createStatement()).thenThrow(new SQLException("read-only database"));
        Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->EuropaSharedFSOperationControlUpgrade.migrate(connection));
    }
}
