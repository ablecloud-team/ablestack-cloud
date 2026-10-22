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
import com.cloud.utils.exception.CloudRuntimeException;
/** Additive same-version migration, resumable after every committed DDL step. */
public final class EuropaSharedFSCreationUpgrade {
    public static final String KEY = "europa-sharedfs-initial-backing-v1";
    private EuropaSharedFSCreationUpgrade() { }
    public static void migrate(Connection conn) {
        try {
            column(conn, "creation_request_id", "VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
            column(conn, "creation_context", "TEXT NULL");
            try (PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='cloud' AND table_name='shared_filesystem' AND index_name='uk_sharedfs_creation_owner'");
                    ResultSet r = p.executeQuery()) {
                if (r.next() && r.getInt(1) == 0) {
                    try (Statement s = conn.createStatement()) {
                        s.execute("ALTER TABLE cloud.shared_filesystem ADD UNIQUE KEY uk_sharedfs_creation_owner (account_id, creation_request_id)");
                    }
                }
            }
        } catch (SQLException e) { throw new CloudRuntimeException("Unable to migrate SharedFS initial creation journal", e); }
    }
    private static void column(Connection conn, String name, String definition) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='shared_filesystem' AND column_name=?")) {
            p.setString(1, name);
            try (ResultSet r = p.executeQuery()) {
                if (r.next() && r.getInt(1) == 0) {
                    try (Statement s = conn.createStatement()) { s.execute("ALTER TABLE cloud.shared_filesystem ADD COLUMN " + name + " " + definition); }
                }
            }
        }
    }
}
