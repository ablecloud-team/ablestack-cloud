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
import java.sql.Statement;
import com.cloud.utils.exception.CloudRuntimeException;

/** Additive retention metadata; never modifies volume content or ownership. */
public final class EuropaSharedFSRetentionUpgrade {
    private EuropaSharedFSRetentionUpgrade() { }
    public static void migrate(Connection connection) {
        for (String[] column : new String[][] {{"data_volume_policy", "VARCHAR(32) DEFAULT NULL"}, {"deletion_plan_json", "LONGTEXT DEFAULT NULL"}}) {
            try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='shared_filesystem' AND column_name=?")) {
                query.setString(1,column[0]);
                try (ResultSet rows=query.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1)==0) try (Statement statement=connection.createStatement()) {
                        statement.execute("ALTER TABLE cloud.shared_filesystem ADD COLUMN "+column[0]+" "+column[1]);
                    }
                }
            } catch (Exception e) { throw new CloudRuntimeException("Unable to apply SharedFS retention metadata migration",e); }
        }
        try (Statement statement=connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS cloud.storage_service_deletion_audit (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, sharedfs_id BIGINT UNSIGNED NOT NULL, sharedfs_uuid VARCHAR(40) NOT NULL, account_id BIGINT UNSIGNED NOT NULL, actor_id BIGINT UNSIGNED NOT NULL, policy VARCHAR(32) NOT NULL, phase VARCHAR(32) NOT NULL, plan_json LONGTEXT NOT NULL, created DATETIME NOT NULL, KEY idx_sharedfs_deletion_audit(sharedfs_id,created)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        } catch (Exception e) { throw new CloudRuntimeException("Unable to apply SharedFS deletion audit migration",e); }

    }
}
