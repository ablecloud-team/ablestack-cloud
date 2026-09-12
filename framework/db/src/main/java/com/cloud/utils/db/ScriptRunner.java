/*
 * Slightly modified version of the com.ibatis.common.jdbc.ScriptRunner class
 * from the iBATIS Apache project. Only removed dependency on Resource class
 * and a constructor
 */
/*
 *  Copyright 2004 Clinton Begin
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.cloud.utils.db;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Reader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/**
 * Tool to run database scripts
 */
public class ScriptRunner {
    private static Logger LOGGER = LogManager.getLogger(ScriptRunner.class);

    private static final String DEFAULT_DELIMITER = ";";

    private Connection connection;

    private boolean stopOnError;
    private boolean autoCommit;
    private boolean verbosity = true;

    private String delimiter = DEFAULT_DELIMITER;
    private boolean fullLineDelimiter = false;

    private StringBuffer _logBuffer = new StringBuffer();

    /**
     * Default constructor
     */
    public ScriptRunner(Connection connection, boolean autoCommit, boolean stopOnError) {
        this.connection = connection;
        this.autoCommit = autoCommit;
        this.stopOnError = stopOnError;
    }

    public ScriptRunner(Connection connection, boolean autoCommit, boolean stopOnError, boolean verbosity) {
        this.connection = connection;
        this.autoCommit = autoCommit;
        this.stopOnError = stopOnError;
        this.verbosity = verbosity;
    }

    public void setDelimiter(String delimiter, boolean fullLineDelimiter) {
        this.delimiter = delimiter;
        this.fullLineDelimiter = fullLineDelimiter;
    }

    /**
     * Runs an SQL script (read in using the Reader parameter)
     *
     * @param reader
     *            - the source of the script
     */
    public void runScript(Reader reader) throws IOException, SQLException {
        try {
            boolean originalAutoCommit = connection.getAutoCommit();
            try {
                if (originalAutoCommit != this.autoCommit) {
                    connection.setAutoCommit(this.autoCommit);
                }
                runScript(connection, reader);
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (IOException e) {
            throw e;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error running script.  Cause: " + e, e);
        }
    }

    /**
     * Runs an SQL script (read in using the Reader parameter) using the
     * connection passed in
     *
     * @param conn
     *            - the connection to use for the script
     * @param reader
     *            - the source of the script
     * @throws SQLException
     *             if any SQL errors occur
     * @throws IOException
     *             if there is an error reading from the Reader
     */
    private void runScript(Connection conn, Reader reader) throws IOException, SQLException {
        try {
            for (String command : parseStatements(reader)) {
                try (Statement statement = conn.createStatement()) {
                    println(command);
                    boolean hasResults;
                    try {
                        hasResults = statement.execute(command);
                    } catch (SQLException e) {
                        printlnError("Error executing: " + command);
                        if (stopOnError) {
                            throw e;
                        }
                        printlnError(e);
                        continue;
                    }
                    if (hasResults) {
                        try (ResultSet rs = statement.getResultSet()) {
                            if (rs != null) {
                                ResultSetMetaData md = rs.getMetaData();
                                int columns = md.getColumnCount();
                                for (int i = 1; i <= columns; i++) {
                                    print(md.getColumnLabel(i) + "\t");
                                }
                                println("");
                                while (rs.next()) {
                                    for (int i = 1; i <= columns; i++) {
                                        print(rs.getString(i) + "\t");
                                    }
                                    println("");
                                }
                            }
                        }
                    }
                }
            }
            if (!autoCommit) {
                conn.commit();
            }
        } catch (SQLException | IOException e) {
            if (!conn.getAutoCommit()) {
                conn.rollback();
            }
            throw e;
        } finally {
            flush();
        }
    }

    /** Split SQL outside quoted values and comments, honoring MySQL DELIMITER directives. */
    List<String> parseStatements(Reader reader) throws IOException, SQLException {
        List<String> statements = new ArrayList<>();
        StringBuilder command = new StringBuilder();
        LineNumberReader lines = new LineNumberReader(reader);
        String activeDelimiter = getDelimiter();
        char quote = 0;
        boolean blockComment = false;
        String line;
        while ((line = lines.readLine()) != null) {
            String trimmed = line.trim();
            if (quote == 0 && !blockComment) {
                if (trimmed.startsWith("//") || trimmed.startsWith("--") || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
                    if (!command.toString().trim().isEmpty()) {
                        throw new SQLException("DELIMITER inside an unfinished statement at line " + lines.getLineNumber());
                    }
                    activeDelimiter = trimmed.substring("DELIMITER ".length()).trim();
                    if (activeDelimiter.isEmpty()) {
                        throw new SQLException("Empty SQL delimiter");
                    }
                    continue;
                }
                if (fullLineDelimiter && trimmed.equals(activeDelimiter)) {
                    addStatement(statements, command);
                    continue;
                }
            }
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
                if (blockComment) {
                    if (c == '*' && next == '/') {
                        blockComment = false;
                        i++;
                        command.append(' ');
                    }
                    continue;
                }
                if (quote != 0) {
                    command.append(c);
                    if (c == '\\' && next != 0) {
                        command.append(next);
                        i++;
                    } else if (c == quote) {
                        if (next == quote) {
                            command.append(next);
                            i++;
                        } else {
                            quote = 0;
                        }
                    }
                    continue;
                }
                if (c == '-' && next == '-' || c == '#') {
                    break;
                }
                if (c == '/' && next == '*') {
                    // Versioned MySQL comments contain executable SQL and must not be discarded.
                    if (i + 2 < line.length() && line.charAt(i + 2) == '!') {
                        int end = line.indexOf("*/", i + 3);
                        if (end < 0) {
                            throw new SQLException("Unterminated executable SQL comment at line " + lines.getLineNumber());
                        }
                        command.append(line, i, end + 2);
                        i = end + 1;
                    } else {
                        blockComment = true;
                        i++;
                    }
                    continue;
                }
                if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                    command.append(c);
                } else if (!fullLineDelimiter && line.startsWith(activeDelimiter, i)) {
                    addStatement(statements, command);
                    i += activeDelimiter.length() - 1;
                } else {
                    command.append(c);
                }
            }
            command.append('\n');
        }
        if (quote != 0 || blockComment) {
            throw new SQLException("Unterminated SQL quote or comment at line " + lines.getLineNumber());
        }
        addStatement(statements, command);
        return statements;
    }

    private void addStatement(List<String> statements, StringBuilder command) {
        String sql = command.toString().trim();
        if (!sql.isEmpty()) {
            statements.add(sql);
        }
        command.setLength(0);
    }

    private String getDelimiter() {
        return delimiter;
    }

    private void print(Object o) {
        _logBuffer.append(o);
    }

    private void println(Object o) {
        _logBuffer.append(o);
        if (verbosity)
            LOGGER.debug(_logBuffer.toString());
        _logBuffer = new StringBuffer();
    }

    private void printlnError(Object o) {
        LOGGER.error("" + o);
    }

    private void flush() {
        if (_logBuffer.length() > 0) {
            LOGGER.debug(_logBuffer.toString());
            _logBuffer = new StringBuffer();
        }
    }
}
