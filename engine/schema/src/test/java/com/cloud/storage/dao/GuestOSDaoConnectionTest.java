/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.storage.dao;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class GuestOSDaoConnectionTest {
    private Field field;
    private Object original;
    private final DataSource ds = mock(DataSource.class);
    private final Connection conn = mock(Connection.class);
    private final PreparedStatement stmt = mock(PreparedStatement.class);
    private final ResultSet rs = mock(ResultSet.class);

    @Before public void setup() throws Exception {
        field = TransactionLegacy.class.getDeclaredField("s_ds");
        field.setAccessible(true); original = field.get(null); field.set(null, ds);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
    }
    @After public void cleanup() throws Exception { field.set(null, original); }

    @Test public void closesEveryResourceAfterRows() throws Exception {
        when(rs.next()).thenReturn(true, false); when(rs.getString(1)).thenReturn("Rocky Linux");
        assertTrue(new GuestOSDaoImpl().findDoubleNames().contains("Rocky Linux"));
        verify(rs).close(); verify(stmt).close(); verify(conn).close();
    }
    @Test public void closesConnectionWhenPrepareFails() throws Exception {
        when(conn.prepareStatement(anyString())).thenThrow(new SQLException("prepare"));
        expectFailure(); verify(conn).close();
    }
    @Test public void closesConnectionAndStatementWhenQueryFails() throws Exception {
        when(stmt.executeQuery()).thenThrow(new SQLException("query"));
        expectFailure(); verify(stmt).close(); verify(conn).close();
    }
    @Test public void closesEveryResourceWhenReadFails() throws Exception {
        when(rs.next()).thenThrow(new SQLException("read"));
        expectFailure(); verify(rs).close(); verify(stmt).close(); verify(conn).close();
    }
    @Test public void acquisitionFailureKeepsSqlCause() throws Exception {
        when(ds.getConnection()).thenThrow(new SQLException("pool unavailable"));
        expectFailure(); verify(conn, never()).close();
    }
    private void expectFailure() {
        try { new GuestOSDaoImpl().findDoubleNames(); fail("Expected database failure"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getCause() instanceof SQLException); }
    }
}
