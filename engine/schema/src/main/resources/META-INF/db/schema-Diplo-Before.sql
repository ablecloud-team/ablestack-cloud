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
-- Schema upgrade from ablestack-bronto to ablestack-cerato
--;

-- Adding security check table
CREATE TABLE `security_check` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `mshost_id` bigint unsigned NOT NULL COMMENT 'the ID of the mshost',
  `check_name` varchar(255) NOT NULL COMMENT 'name of the security check',
  `last_update` datetime DEFAULT NULL COMMENT 'last check update time',
  `check_result` tinyint(1) NOT NULL COMMENT 'check executions success or failure',
  `check_details` blob COMMENT 'check result detailed message',
  PRIMARY KEY (`id`),
  UNIQUE KEY `i_security_checks__mshost_id__check_name` (`mshost_id`,`check_name`),
  KEY `i_security_checks__mshost_id` (`mshost_id`),
  CONSTRAINT `fk_security_checks__mshost_id` FOREIGN KEY (`mshost_id`) REFERENCES `mshost` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB CHARSET=utf8mb3;