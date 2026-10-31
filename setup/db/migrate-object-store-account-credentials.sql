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

-- Additive hot-upgrade for an already deployed 4.23/Europa management server.
CREATE TABLE IF NOT EXISTS `cloud`.`object_store_account_credential` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `account_id` BIGINT UNSIGNED NOT NULL,
  `object_store_id` BIGINT UNSIGNED NOT NULL,
  `rgw_user_id` VARCHAR(255) NOT NULL,
  `access_key` VARCHAR(1024) DEFAULT NULL,
  `secret_key` VARCHAR(2048) DEFAULT NULL,
  `state` VARCHAR(32) NOT NULL DEFAULT 'NeedsVerification',
  `revision` BIGINT UNSIGNED NOT NULL DEFAULT 0,
  `checked_at` DATETIME DEFAULT NULL,
  `provisioning_source` VARCHAR(32) NOT NULL DEFAULT 'Existing',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_object_store_account_credential_pair` (`account_id`,`object_store_id`),
  CONSTRAINT `fk_object_store_account_credential_account` FOREIGN KEY (`account_id`) REFERENCES `cloud`.`account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_object_store_account_credential_store` FOREIGN KEY (`object_store_id`) REFERENCES `cloud`.`object_store` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
