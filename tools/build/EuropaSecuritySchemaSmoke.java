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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.cloud.upgrade.dao.EuropaSecuritySchemaUpgrade;
import com.cloud.utils.exception.CloudRuntimeException;

/** Runs only against the disposable S3 database fixture, never the development database. */
public class EuropaSecuritySchemaSmoke {
    private static String value(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) { throw new AssertionError("Missing fixture query result"); }
            return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
    }

    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) { throw new AssertionError("Fixture invariant failed"); }
    }

    private static void preservesKeys(Connection connection) throws SQLException {
        equal("1", value(connection, "SELECT COUNT(*) FROM cloud.api_keypair"));
        equal("fixture-existing-api-key", value(connection, "SELECT api_key FROM cloud.api_keypair WHERE uuid='fixture-key'"));
        equal("fixture-encrypted-secret", value(connection, "SELECT secret_key FROM cloud.api_keypair WHERE uuid='fixture-key'"));
        equal("1", value(connection, "SELECT COUNT(*) FROM cloud.user"));
        equal("existing-provider-secret", value(connection, "SELECT secret_key FROM cloud.oauth_provider WHERE uuid='fixture-oauth'"));
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "baseline" : args[0];
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://epic991-auth-db:3306/cloud?useSSL=false&allowPublicKeyRetrieval=true",
                "root", "epic991-disposable-fixture")) {
            equal("epic991-disposable", value(connection, "SELECT marker FROM cloud.epic991_fixture_guard"));
            connection.setAutoCommit(false);
            if ("partial".equals(mode)) {
                execute(connection, "ALTER TABLE cloud.oauth_provider ADD domain_id BIGINT UNSIGNED DEFAULT NULL");
                execute(connection, "UPDATE cloud.oauth_provider SET domain_id=999 WHERE uuid='fixture-oauth'");
                connection.commit();
                try {
                    EuropaSecuritySchemaUpgrade.migrate(connection);
                    throw new AssertionError("Expected invalid foreign key to fail migration");
                } catch (CloudRuntimeException expected) {
                    connection.rollback();
                    preservesKeys(connection);
                }
                execute(connection, "UPDATE cloud.oauth_provider SET domain_id=NULL WHERE uuid='fixture-oauth'");
                connection.commit();
            }
            EuropaSecuritySchemaUpgrade.migrate(connection);
            connection.commit();
            preservesKeys(connection);
            String permissions = value(connection, "SELECT COUNT(*) FROM cloud.role_permissions");
            String order = value(connection, "SELECT SUM(sort_order) FROM cloud.role_permissions");
            EuropaSecuritySchemaUpgrade.migrate(connection);
            connection.commit();
            preservesKeys(connection);
            equal(permissions, value(connection, "SELECT COUNT(*) FROM cloud.role_permissions"));
            equal(order, value(connection, "SELECT SUM(sort_order) FROM cloud.role_permissions"));
            equal("1024", value(connection, "SELECT character_maximum_length FROM information_schema.columns WHERE table_schema='cloud' AND table_name='api_keypair' AND column_name='description'"));
            execute(connection, "UPDATE cloud.api_keypair SET description=REPEAT('x',1024) WHERE uuid='fixture-key'");
            equal("1024", value(connection, "SELECT LENGTH(description) FROM cloud.api_keypair WHERE uuid='fixture-key'"));
            equal("DENY", value(connection, "SELECT permission FROM cloud.role_permissions WHERE role_id=2 AND rule='listUserKeyRules'"));
            equal("0", value(connection, "SELECT COUNT(*) FROM cloud.role_permissions WHERE role_id=4 AND rule='listUserKeyRules'"));
            equal("2", value(connection, "SELECT COUNT(*) FROM cloud.role_permissions WHERE role_id IN (1,3) AND rule='listUserKeyRules' AND permission='ALLOW'"));
            for (long domain : new long[] {2, 3}) {
                execute(connection, "INSERT INTO cloud.oauth_provider(uuid,provider,client_id,secret_key,redirect_uri,created,domain_id) VALUES (UUID(),'google','domain-client','fixture','https://cloud.example/redirect',NOW()," + domain + ")");
            }
            connection.commit();
            try {
                execute(connection, "INSERT INTO cloud.oauth_provider(uuid,provider,client_id,secret_key,redirect_uri,created,domain_id) VALUES (UUID(),'google','duplicate','fixture','https://cloud.example/redirect',NOW(),2)");
                throw new AssertionError("Duplicate domain provider accepted");
            } catch (SQLException expected) {
                equal("23000", expected.getSQLState());
            }
            equal("3", value(connection, "SELECT COUNT(*) FROM cloud.oauth_provider"));
            preservesKeys(connection);
            System.out.println("PASS " + mode + ": Connector/J " + connection.getMetaData().getDriverVersion()
                    + "; key preservation, 1024 description, domain schema, permission preservation, idempotence and constraints");
        }
    }
}
