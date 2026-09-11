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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs only on the task-owned disposable MySQL fixture. */
public class EuropaGuiThemeDdlReplay {
    static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
    static Connection faultAfter(Connection c, int failAt, AtomicInteger count) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(c, args);
                if (!method.getName().equals("createStatement")) return result;
                Statement statement = (Statement) result;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class[]{Statement.class}, (sp, sm, sa) -> {
                    try {
                        Object sr = sm.invoke(statement, sa);
                        if (sm.getName().equals("execute") && count.incrementAndGet() == failAt) {
                            throw new SQLException("Injected interruption AFTER committed DDL " + failAt);
                        }
                        return sr;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            } catch (InvocationTargetException e) { throw e.getCause(); }
        });
    }
    static long scalar(Connection c, String sql) throws SQLException {
        try (Statement st=c.createStatement(); ResultSet r=st.executeQuery(sql)) {r.next(); return r.getLong(1);}
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("epic998-mysql-ddl")) throw new IllegalArgumentException("Task-owned fixture only");
        try (Connection c = DriverManager.getConnection("jdbc:mysql://" + args[0] + ":3306/?allowPublicKeyRetrieval=true&useSSL=false", "root", "epic998-fixture-dummy")) {
            execute(c, "DROP DATABASE IF EXISTS cloud");
            execute(c, "CREATE DATABASE cloud");
            execute(c, "USE cloud");
            execute(c, "CREATE TABLE gui_themes(id BIGINT PRIMARY KEY, name VARCHAR(255), css TEXT)");
            execute(c, "INSERT INTO gui_themes VALUES(1,'Existing Europa theme','body {color: blue}')");
            execute(c, "CREATE TABLE ablestack_schema_migration(name VARCHAR(255) PRIMARY KEY,state VARCHAR(255),updated DATETIME)");
            execute(c, "INSERT INTO ablestack_schema_migration VALUES('europa-4.23-s6-v1','Complete','2026-01-01')");
            String procedure = Files.readString(Path.of("engine/schema/src/main/resources/META-INF/db/procedures/cloud.idempotent_add_column.sql"));
            execute(c, procedure.substring(procedure.indexOf("CREATE PROCEDURE"), procedure.indexOf("END$$") + 3));
            EuropaSchemaUpgrade.begin(c, EuropaSchemaUpgrade.S7);
            AtomicInteger count = new AtomicInteger();
            try { EuropaGuiThemeSchemaUpgrade.migrate(faultAfter(c, 1, count)); throw new AssertionError("Failure not injected"); }
            catch (com.cloud.utils.exception.CloudRuntimeException expected) {
                if (expected.getCause() == null || !expected.getCause().getMessage().startsWith("Injected")) throw expected;
            }
            if (count.get() != 1 || !EuropaSchemaUpgrade.hasState(c, EuropaSchemaUpgrade.S7, "Pending")) throw new AssertionError("False checkpoint completion");
            EuropaGuiThemeSchemaUpgrade.migrate(c);
            EuropaSchemaUpgrade.complete(c, EuropaSchemaUpgrade.S7);
            EuropaGuiThemeSchemaUpgrade.migrate(c);
            if (scalar(c, "SELECT COUNT(*) FROM gui_themes WHERE id=1 AND name='Existing Europa theme' AND css='body {color: blue}' AND login_base_domain IS NULL") != 1) throw new AssertionError("Existing theme changed");
            if (scalar(c, "SELECT COUNT(*) FROM ablestack_schema_migration WHERE name='europa-4.23-s6-v1' AND state='Complete' AND updated='2026-01-01'") != 1) throw new AssertionError("Earlier journal changed");
            execute(c, "UPDATE gui_themes SET login_base_domain='tenant/child' WHERE id=1");
            EuropaGuiThemeSchemaUpgrade.migrate(c);
            if (scalar(c, "SELECT COUNT(*) FROM gui_themes WHERE login_base_domain='tenant/child'") != 1) throw new AssertionError("Configured domain lost on repeat");
            System.out.println("PASS S7 committed-DDL interruption, Pending/replay/Complete/repeat, existing theme and S6 journal preservation");
        }
    }
}
