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
-- Schema upgrade from ablestack-cerato to ablestack-diplo
--;

-- BEGIN TABLE vbmc_port
CREATE TABLE IF NOT EXISTS `vbmc_port` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `vm_id` bigint unsigned NOT NULL DEFAULT '0' COMMENT 'vbmc port assigned vm id',
  `port` int NOT NULL COMMENT 'vbmc port number',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8mb3;

INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (1, 6230);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (2, 6231);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (3, 6232);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (4, 6233);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (5, 6234);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (6, 6235);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (7, 6236);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (8, 6237);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (9, 6238);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (10, 6239);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (11, 6240);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (12, 6241);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (13, 6242);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (14, 6243);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (15, 6244);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (16, 6245);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (17, 6246);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (18, 6247);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (19, 6248);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (20, 6249);
INSERT IGNORE INTO `cloud`.`vbmc_port` (id, port) VALUES (21, 6250);

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.host', 'migration_ip', 'VARCHAR(45)');

-- backup offering table update
CALL `cloud`.`ADD_COL`('backup_offering', 'retention_period', 'VARCHAR(255) DEFAULT null');
CALL `cloud`.`ADD_COL`('backups', 'snapshot_id', 'VARCHAR(255) DEFAULT null');