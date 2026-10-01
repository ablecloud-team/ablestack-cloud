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
package com.cloud.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import com.cloud.upgrade.dao.EuropaVmProcessConfigUpgrade;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class EuropaVmProcessConfigUpgradeTest {
    @Test
    public void removesOnlyObsoleteProcessSettings() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement("DELETE FROM cloud.configuration WHERE name IN (?, ?)"))
                .thenReturn(statement);

        EuropaVmProcessConfigUpgrade.migrate(connection);

        verify(statement).setString(1, "vm.process.capability.enabled");
        verify(statement).setString(2, "vm.process.capability.test.vm.uuids");
        verify(statement).executeUpdate();
        verify(statement).close();
        verifyNoMoreInteractions(statement);
    }
}
