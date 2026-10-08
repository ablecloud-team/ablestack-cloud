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
import java.sql.*;
import org.junit.*;
import org.mockito.Mockito;
public class EuropaSharedFSTemplateUpgradeTest {
    private Connection connection;private Statement statement;private DatabaseMetaData metadata;private ResultSet table;private ResultSet columns;
    @Before public void setup() throws Exception {
        connection=Mockito.mock(Connection.class);statement=Mockito.mock(Statement.class);metadata=Mockito.mock(DatabaseMetaData.class);table=Mockito.mock(ResultSet.class);columns=Mockito.mock(ResultSet.class);
        Mockito.when(connection.createStatement()).thenReturn(statement);Mockito.when(connection.getMetaData()).thenReturn(metadata);Mockito.when(metadata.getTables(Mockito.eq("cloud"),Mockito.isNull(),Mockito.eq("storage_service_instance"),Mockito.any())).thenReturn(table);Mockito.when(metadata.getColumns("cloud",null,"storage_service_instance",null)).thenReturn(columns);
    }
    @Test public void existingEuropaTableGetsEveryMissingProjectionBeforeVoQueries() throws Exception {
        Mockito.when(table.next()).thenReturn(true);Mockito.when(columns.next()).thenReturn(false);EuropaSharedFSTemplateUpgrade.migrate(connection);
        for(String name:new String[]{"current_template_id","previous_template_id","template_upgrade_state","last_template_upgrade_id","template_verified_at"}) Mockito.verify(statement).execute(Mockito.startsWith("ALTER TABLE cloud.storage_service_instance ADD COLUMN "+name+" "));
    }
    @Test public void alreadyInstalledProjectionIsIdempotent() throws Exception {
        Mockito.when(table.next()).thenReturn(true);Mockito.when(columns.next()).thenReturn(true,true,true,true,true,false);Mockito.when(columns.getString("COLUMN_NAME")).thenReturn("current_template_id","previous_template_id","template_upgrade_state","last_template_upgrade_id","template_verified_at");
        EuropaSharedFSTemplateUpgrade.migrate(connection);Mockito.verify(statement,Mockito.never()).execute(Mockito.startsWith("ALTER TABLE"));
    }
    @Test public void freshInstallationDefersProjectionUntilTheInstanceTableExists() throws Exception {
        Mockito.when(table.next()).thenReturn(false);EuropaSharedFSTemplateUpgrade.migrate(connection);Mockito.verify(statement,Mockito.never()).execute(Mockito.startsWith("ALTER TABLE"));Mockito.verify(metadata,Mockito.never()).getColumns(Mockito.anyString(),Mockito.any(),Mockito.anyString(),Mockito.any());
    }
    @Test public void concurrentManagementNodeColumnInstallIsReobservedAndAccepted() throws Exception {
        Mockito.when(table.next()).thenReturn(true);Mockito.when(columns.next()).thenReturn(false);
        Mockito.when(statement.execute(Mockito.startsWith("ALTER TABLE cloud.storage_service_instance ADD COLUMN current_template_id "))).thenThrow(new SQLException("already added", "42S21",1060));
        ResultSet raced=Mockito.mock(ResultSet.class);Mockito.when(raced.next()).thenReturn(true);Mockito.when(metadata.getColumns("cloud",null,"storage_service_instance","current_template_id")).thenReturn(raced);
        EuropaSharedFSTemplateUpgrade.migrate(connection);Mockito.verify(statement).execute(Mockito.startsWith("ALTER TABLE cloud.storage_service_instance ADD COLUMN template_verified_at "));
    }
}
