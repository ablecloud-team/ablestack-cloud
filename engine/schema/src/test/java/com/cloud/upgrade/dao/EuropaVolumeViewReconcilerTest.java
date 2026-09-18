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
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EuropaVolumeViewReconcilerTest {
    private Connection connection;
    private Statement statement;
    private DatabaseMetaData database;

    @Before
    public void setup() throws Exception {
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        database = mock(DatabaseMetaData.class);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(database);
        ResultSet projection = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(statement.executeQuery(anyString())).thenReturn(projection);
        when(projection.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnLabel(2)).thenReturn("kms_key_id");
    }

    private ResultSet columns(boolean current) throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, current, false);
        when(result.getString("COLUMN_NAME")).thenReturn("id", "kms_key_id");
        return result;
    }

    private void setColumns(ResultSet first, ResultSet... rest) throws Exception {
        when(database.getColumns("cloud", null, "volume_view", null)).thenReturn(first, rest);
    }

    @Test
    public void completeSchemaDoesNotRewriteView() throws Exception {
        setColumns(columns(true));
        EuropaVolumeViewReconciler.reconcile(connection);
        verify(statement, never()).execute(anyString());
        verify(statement).executeQuery(contains("LIMIT 0"));
    }

    @Test
    public void staleViewIsRepairedAndSecondRunIsNoOp() throws Exception {
        setColumns(columns(false), columns(true), columns(true));
        EuropaVolumeViewReconciler.reconcile(connection);
        EuropaVolumeViewReconciler.reconcile(connection);
        verify(statement, times(1)).execute(startsWith("CREATE OR REPLACE VIEW"));
        verify(statement, never()).execute(startsWith("DROP"));
    }

    @Test
    public void missingViewIsCreated() throws Exception {
        ResultSet absent = mock(ResultSet.class);
        setColumns(absent, columns(true));
        EuropaVolumeViewReconciler.reconcile(connection);
        verify(statement).execute(startsWith("CREATE OR REPLACE VIEW"));
    }

    @Test
    public void missingDependencyFailsBeforeDdl() throws Exception {
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("Missing KMS table"));
        assertThrows(SQLException.class, () -> EuropaVolumeViewReconciler.reconcile(connection));
        verify(statement, never()).execute(anyString());
    }

    @Test
    public void failedReplacementCanBeRetried() throws Exception {
        setColumns(columns(false), columns(false), columns(true));
        when(statement.execute(anyString())).thenThrow(new SQLException("DDL failed")).thenReturn(false);
        assertThrows(SQLException.class, () -> EuropaVolumeViewReconciler.reconcile(connection));
        EuropaVolumeViewReconciler.reconcile(connection);
        verify(statement, times(2)).execute(startsWith("CREATE OR REPLACE VIEW"));
    }

    @Test
    public void unsuccessfulPostCheckFails() throws Exception {
        setColumns(columns(false), columns(false));
        assertThrows(SQLException.class, () -> EuropaVolumeViewReconciler.reconcile(connection));
    }
}
