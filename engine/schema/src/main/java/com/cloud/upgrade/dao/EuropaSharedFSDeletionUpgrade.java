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
public final class EuropaSharedFSDeletionUpgrade {
    public static final String KEY="europa-sharedfs-deletion-v1";
    private EuropaSharedFSDeletionUpgrade(){}
    public static void migrate(Connection c){
        try {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS cloud.sharedfs_deletion_operation (\n"
                        + " id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,\n"
                        + " uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,\n"
                        + " sharedfs_id BIGINT UNSIGNED NOT NULL, sharedfs_uuid CHAR(36) NOT NULL,\n"
                        + " instance_uuid CHAR(36) NULL, vm_uuid CHAR(36) NULL,\n"
                        + " account_id BIGINT UNSIGNED NOT NULL, domain_id BIGINT UNSIGNED NOT NULL,\n"
                        + " request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,\n"
                        + " request_hash CHAR(64) NOT NULL, plan_fingerprint CHAR(64) NOT NULL,\n"
                        + " state VARCHAR(32) NOT NULL, phase VARCHAR(32) NOT NULL,\n"
                        + " plan_json MEDIUMTEXT NOT NULL, result_json MEDIUMTEXT,\n"
                        + " original_failure_json TEXT, job_id BIGINT UNSIGNED NULL,\n"
                        + " created DATETIME NOT NULL, updated DATETIME NOT NULL, completed DATETIME NULL,\n"
                        + " UNIQUE KEY uk_sharedfs_del_uuid(uuid),\n"
                        + " UNIQUE KEY uk_sharedfs_del_request(account_id,sharedfs_id,request_id),\n"
                        + " KEY idx_sharedfs_del_state(sharedfs_id,state)\n"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            }
            try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='storage_service_instance' AND column_name='active_lifecycle_operation_uuid'");ResultSet r=p.executeQuery()){
                if(r.next()&&r.getInt(1)==0)try(Statement s=c.createStatement()){s.execute("ALTER TABLE cloud.storage_service_instance ADD COLUMN active_lifecycle_operation_uuid CHAR(36) NULL");}
            }
        }catch(SQLException e){throw new CloudRuntimeException("Unable to migrate SharedFS deletion journal",e);}
    }
}
