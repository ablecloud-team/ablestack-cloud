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
import java.sql.SQLException;

import com.cloud.utils.exception.CloudRuntimeException;

/** Remove obsolete provider-specific and unsupported common staging settings. */
public final class EuropaThirdPartyStagingConfigUpgrade {
    public static final String PHASE = "europa-4.23-s13-thirdparty-staging-config-v2";

    private EuropaThirdPartyStagingConfigUpgrade() {
    }

    public static void migrate(Connection conn) {
        try (PreparedStatement disableUnsupported = conn.prepareStatement(
                "UPDATE cloud.configuration enabled JOIN cloud.configuration storage_type " +
                        "ON storage_type.name = 'backup.thirdparty.staging.storage.type' " +
                        "SET enabled.value = 'false' WHERE enabled.name = 'backup.thirdparty.staging.enable' " +
                        "AND UPPER(TRIM(storage_type.value)) = 'GLUEFS'");
                PreparedStatement statement = conn.prepareStatement(
                        "DELETE FROM cloud.configuration WHERE name IN (?, ?, ?, ?, ?)")) {
            disableUnsupported.executeUpdate();
            statement.setString(1, "backup.plugin.commvault.stage.root.path");
            statement.setString(2, "backup.plugin.netbackup.stage.root.path");
            statement.setString(3, "backup.plugin.ablestack-veeam.stage.root.path");
            statement.setString(4, "backup.thirdparty.staging.gluefs.name");
            statement.setString(5, "backup.thirdparty.staging.gluefs.path");
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to remove obsolete third-party host staging settings", e);
        }
    }
}
