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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import com.cloud.utils.exception.CloudRuntimeException;

public final class BucketCredentialRotationSchemaUpgrade {
    private BucketCredentialRotationSchemaUpgrade() { }
    public static void migrate(Connection connection) {
        String[][] columns = {
            new String[]{"pending_access_key", "VARCHAR(1024)"},
            new String[]{"pending_secret_key", "VARCHAR(1024)"},
            new String[]{"previous_access_key", "VARCHAR(1024)"},
            new String[]{"previous_secret_key", "VARCHAR(1024)"},
            new String[]{"rotation_uuid", "VARCHAR(40)"},
            new String[]{"rotation_phase", "VARCHAR(32)"},
            new String[]{"rotation_error", "VARCHAR(32)"},
            new String[]{"retire_after", "DATETIME"},
            new String[]{"rotation_grace_seconds", "INT"}
        };
        try {
            for (String[] column : columns) {
                try (PreparedStatement check = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name=?")) {
                    check.setString(1, column[0]);
                    try (ResultSet result = check.executeQuery()) {
                        result.next();
                        if (result.getInt(1) == 0) {
                            try (Statement alter = connection.createStatement()) {
                                alter.executeUpdate("ALTER TABLE cloud.object_store_account_credential ADD COLUMN " + column[0] + " " + column[1] + " DEFAULT NULL");
                            }
                        }
                    }
                }
            }
        } catch (SQLException error) { throw new CloudRuntimeException("Unable to prepare bucket credential rotation schema"); }
    }
}
