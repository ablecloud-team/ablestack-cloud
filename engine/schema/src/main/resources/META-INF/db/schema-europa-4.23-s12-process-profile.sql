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

CREATE TABLE IF NOT EXISTS `cloud`.`vm_process_profile` (
 `vm_id` BIGINT UNSIGNED NOT NULL,
 `profile_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `version` INT UNSIGNED NOT NULL,
 `definition_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `metadata_json` TEXT NOT NULL,
 `state` VARCHAR(16) NOT NULL,
 `registered_by` BIGINT UNSIGNED NOT NULL,
 `approved_by` BIGINT UNSIGNED DEFAULT NULL,
 `retired_by` BIGINT UNSIGNED DEFAULT NULL,
 `created` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 `updated` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (`vm_id`,`profile_id`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
