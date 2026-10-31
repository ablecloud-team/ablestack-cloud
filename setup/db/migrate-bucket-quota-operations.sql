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
CREATE TABLE IF NOT EXISTS `cloud`.`bucket_quota_operation` (
 `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 `uuid` VARCHAR(40) NOT NULL,
 `bucket_id` BIGINT UNSIGNED NOT NULL,
 `active_bucket_id` BIGINT UNSIGNED DEFAULT NULL,
 `account_id` BIGINT UNSIGNED NOT NULL,
 `domain_id` BIGINT UNSIGNED NOT NULL,
 `object_store_id` BIGINT UNSIGNED NOT NULL,
 `operation_type` VARCHAR(16) NOT NULL,
 `previous_quota` INT DEFAULT NULL,
 `requested_quota` INT DEFAULT NULL,
 `previous_snapshot` VARCHAR(1024) DEFAULT NULL,
 `store_url` VARCHAR(2048) NOT NULL,
 `external_id` VARCHAR(255) DEFAULT NULL,
 `external_created` TINYINT(1) NOT NULL DEFAULT 0,
 `state` VARCHAR(32) NOT NULL DEFAULT 'Prepared',
 `account_hold_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0,
 `reserved_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0,
 `attempts` INT NOT NULL DEFAULT 0,
 `error_code` VARCHAR(64) DEFAULT NULL,
 `created` DATETIME NOT NULL,
 `updated_at` DATETIME NOT NULL,
 PRIMARY KEY (`id`), UNIQUE KEY `uk_bucket_quota_operation_uuid` (`uuid`),
 UNIQUE KEY `uk_bucket_quota_operation_active_bucket` (`active_bucket_id`),
 KEY `idx_bucket_quota_operation_pending_store` (`object_store_id`,`state`),
 KEY `idx_bucket_quota_operation_pending_account` (`account_id`,`state`),
 CONSTRAINT `fk_bucket_quota_operation_account` FOREIGN KEY (`account_id`) REFERENCES `cloud`.`account` (`id`) ON DELETE CASCADE,
 CONSTRAINT `fk_bucket_quota_operation_store` FOREIGN KEY (`object_store_id`) REFERENCES `cloud`.`object_store` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
