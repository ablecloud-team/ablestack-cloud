// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.utils.db;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScriptRunnerTest {
    @Test
    public void preservesQuotedDelimitersAndCommentsAndSplitsMultipleStatements() throws Exception {
        ScriptRunner runner = new ScriptRunner(null, false, true);
        List<String> statements = runner.parseStatements(new StringReader(
                "-- header\nINSERT INTO t VALUES ('a;--b', 'it''s', '한글', `a;b`); SELECT 2; -- tail\n" +
                        "/* multiple\nline comment */ SELECT '/* literal */'; SELECT 4"));
        assertEquals(4, statements.size());
        assertEquals("INSERT INTO t VALUES ('a;--b', 'it''s', '한글', `a;b`)", statements.get(0));
        assertEquals("SELECT 2", statements.get(1));
        assertEquals("SELECT '/* literal */'", statements.get(2));
        assertEquals("SELECT 4", statements.get(3));
    }

    @Test
    public void procedureBodyIsOneStatement() throws Exception {
        List<String> statements = new ScriptRunner(null, false, true).parseStatements(new StringReader(
                "DROP PROCEDURE IF EXISTS p;\nDELIMITER $$\nCREATE PROCEDURE p()\nBEGIN\n" +
                        "DECLARE n INT DEFAULT 1;\nIF n = 1 THEN SELECT 'x;$$'; END IF;\nEND$$\nDELIMITER ;\nCALL p();"));
        assertEquals(3, statements.size());
        assertTrue(statements.get(1).contains("END IF;"));
        assertEquals("CALL p()", statements.get(2));
    }

    @Test
    public void supportsFullLineDelimiter() throws Exception {
        ScriptRunner runner = new ScriptRunner(null, false, true);
        runner.setDelimiter("GO", true);
        assertEquals(List.of("SELECT 'GO';", "SELECT 2;"),
                runner.parseStatements(new StringReader("SELECT 'GO';\nGO\nSELECT 2;\nGO")));
    }

    @Test
    public void preservesExecutableMysqlComment() throws Exception {
        assertEquals(List.of("/*!40101 SET NAMES utf8mb4 */"), new ScriptRunner(null, false, true)
                .parseStatements(new StringReader("/*!40101 SET NAMES utf8mb4 */;")));
    }

    @Test(expected = SQLException.class)
    public void rejectsUnterminatedQuote() throws Exception {
        new ScriptRunner(null, false, true).parseStatements(new StringReader("SELECT 'unfinished"));
    }

    @Test(expected = SQLException.class)
    public void rejectsUnterminatedComment() throws Exception {
        new ScriptRunner(null, false, true).parseStatements(new StringReader("SELECT 1; /* unfinished"));
    }

    @Test
    public void failureRollsBackAndDoesNotExecuteFollowingStatement() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("SELECT broken")).thenThrow(new SQLException("fixture failure"));
        try {
            new ScriptRunner(connection, false, true).runScript(new StringReader("SELECT broken; SELECT 2;"));
            fail("SQL failure must escape to the migration caller");
        } catch (SQLException expected) {
            assertEquals("fixture failure", expected.getMessage());
        }
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(statement, never()).execute("SELECT 2");
    }

    @Test
    public void successfulRunCommitsWithoutRollbackAndRestoresAutoCommit() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(false);
        new ScriptRunner(connection, false, true).runScript(new StringReader("SELECT 1; SELECT 2;"));
        verify(statement).execute("SELECT 1");
        verify(statement).execute("SELECT 2");
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(connection).setAutoCommit(false);
        verify(connection).setAutoCommit(true);
    }
}
