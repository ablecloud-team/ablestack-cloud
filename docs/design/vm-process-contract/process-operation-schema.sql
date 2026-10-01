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
CREATE TABLE IF NOT EXISTS `vm_process_operation` (
 `operation_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `request_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 `account_id` BIGINT UNSIGNED NOT NULL,
 `vm_id` BIGINT UNSIGNED NOT NULL,
 `active_vm_id` BIGINT UNSIGNED DEFAULT NULL,
 `host_id` BIGINT UNSIGNED NOT NULL,
 `generation` BIGINT NOT NULL,
 `fingerprint` CHAR(64) CHARACTER SET ascii NOT NULL,
 `request_json` MEDIUMTEXT NOT NULL,
 `result_json` MEDIUMTEXT NOT NULL,
 `state` VARCHAR(16) NOT NULL,
 `created` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 `updated` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (`operation_id`),
 UNIQUE KEY `vm_process_request` (`account_id`,`request_id`),
 UNIQUE KEY `vm_process_active` (`active_vm_id`),
 KEY `vm_process_vm` (`vm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
