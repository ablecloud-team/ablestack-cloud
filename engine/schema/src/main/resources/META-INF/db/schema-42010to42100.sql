-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--   http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.

--;
-- Schema upgrade from 4.20.1.0 to 4.21.0.0
--;

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backup_schedule', 'max_backups', 'INT(8) UNSIGNED NOT NULL DEFAULT 0 COMMENT ''Maximum number of backups to be retained''');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'backup_schedule_id', 'BIGINT(20) UNSIGNED');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backup_schedule', 'quiescevm', 'tinyint(1) default NULL COMMENT "Quiesce VM before taking backup"');

-- Add console_endpoint_creator_address column to cloud.console_session table
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.console_session', 'console_endpoint_creator_address', 'VARCHAR(45)');

-- Add client_address column to cloud.console_session table
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.console_session', 'client_address', 'VARCHAR(45)');

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backup_schedule', 'uuid', 'VARCHAR(40) NOT NULL');
UPDATE `cloud`.`backup_schedule` SET uuid = UUID() WHERE uuid IS NULL;

-- Add columns name, description and backup_interval_type to backup table
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'name', 'VARCHAR(255) NULL COMMENT "name of the backup"');
UPDATE `cloud`.`backups` backup INNER JOIN `cloud`.`vm_instance` vm ON backup.vm_id = vm.id SET backup.name = vm.name;
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.backups','name','name','VARCHAR(255) NOT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'description', 'VARCHAR(1024) COMMENT "description for the backup"');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.backups', 'backup_interval_type', 'int(5) COMMENT "type of backup, e.g. manual, recurring - hourly, daily, weekly or monthly"');

-- Add diskOfferingId, deviceId, minIops and maxIops to backed_volumes in backups table
UPDATE `cloud`.`backups` b
INNER JOIN `cloud`.`vm_instance` vm ON b.vm_id = vm.id
SET b.backed_volumes = (
    SELECT CONCAT("[",
        GROUP_CONCAT(
            CONCAT(
                "{\"uuid\":\"", v.uuid, "\",",
                "\"type\":\"", v.volume_type, "\",",
                "\"size\":", v.`size`, ",",
                "\"path\":\"", IFNULL(v.path, 'null'), "\",",
                "\"deviceId\":", IFNULL(v.device_id, 'null'), ",",
                "\"diskOfferingId\":\"", doff.uuid, "\",",
                "\"minIops\":", IFNULL(v.min_iops, 'null'), ",",
                "\"maxIops\":", IFNULL(v.max_iops, 'null'),
                "}"
            )
            SEPARATOR ","
        ),
    "]")
    FROM `cloud`.`volumes` v
    LEFT JOIN `cloud`.`disk_offering` doff ON v.disk_offering_id = doff.id
    WHERE v.instance_id = vm.id
);

-- Add diskOfferingId, deviceId, minIops and maxIops to backup_volumes in vm_instance table
UPDATE `cloud`.`vm_instance` vm
SET vm.backup_volumes = (
    SELECT CONCAT("[",
        GROUP_CONCAT(
            CONCAT(
                "{\"uuid\":\"", v.uuid, "\",",
                "\"type\":\"", v.volume_type, "\",",
                "\"size\":", v.`size`, ",",
                "\"path\":\"", IFNULL(v.path, 'null'), "\",",
                "\"deviceId\":", IFNULL(v.device_id, 'null'), ",",
                "\"diskOfferingId\":\"", doff.uuid, "\",",
                "\"minIops\":", IFNULL(v.min_iops, 'null'), ",",
                "\"maxIops\":", IFNULL(v.max_iops, 'null'),
                "}"
            )
            SEPARATOR ","
        ),
    "]")
    FROM `cloud`.`volumes` v
    LEFT JOIN `cloud`.`disk_offering` doff ON v.disk_offering_id = doff.id
    WHERE v.instance_id = vm.id
)
WHERE vm.backup_offering_id IS NOT NULL;

-- Add column allocated_size to object_store table. Rename column 'used_bytes' to 'used_size'
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.object_store', 'allocated_size', 'bigint unsigned COMMENT "allocated size in bytes"');
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.object_store','used_bytes','used_size','BIGINT UNSIGNED COMMENT ''used size in bytes''');
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.object_store','total_size','total_size','BIGINT UNSIGNED COMMENT ''total size in bytes''');

UPDATE `cloud`.`object_store`
JOIN (
    SELECT object_store_id, SUM(quota) AS total_quota
    FROM `cloud`.`bucket`
    WHERE removed IS NULL
    GROUP BY object_store_id
) buckets_quota_sum_view ON `object_store`.id = buckets_quota_sum_view.object_store_id
SET `object_store`.allocated_size = buckets_quota_sum_view.total_quota;