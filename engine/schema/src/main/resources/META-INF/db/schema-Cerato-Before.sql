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

-- Adding automation controller version table
CREATE TABLE IF NOT EXISTS `automation_controller_template_version` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) DEFAULT NULL,
  `name` varchar(255) NOT NULL COMMENT 'the name of this Automation Controller Template Version',
  `description` varchar(255) NOT NULL COMMENT 'the description of this Automation Controller Template Version',
  `version` varchar(32) NOT NULL COMMENT 'the version for this Automation Controller Template Version',
  `zone_id` bigint unsigned NOT NULL COMMENT 'the ID of the zone in which this Automation Controller VM is deployed',
  `template_id` bigint unsigned NOT NULL COMMENT 'the ID of the Automation Controller Version Template',
  `state` char(32) NOT NULL COMMENT 'the enabled or disabled state for this Automation Controller Template Version',
  `upload_type` char(32) DEFAULT NULL COMMENT 'Automation Controller Template upload type',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime DEFAULT NULL COMMENT 'date removed or null, if still present',
  PRIMARY KEY (`id`),
  KEY `fk_automation_controller_template_version_template_id` (`template_id`),
  KEY `fk_automation_controller_template_version_zone_id` (`zone_id`),
  CONSTRAINT `fk_automation_controller_template_version_template_id` FOREIGN KEY (`template_id`) REFERENCES `vm_template` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_automation_controller_template_version_zone_id` FOREIGN KEY (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- Adding automation controller table
CREATE TABLE IF NOT EXISTS `automation_controller_service_vm` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) DEFAULT NULL,
  `name` varchar(255) NOT NULL COMMENT 'the name of this Automation Controller Instance',
  `description` varchar(255) NOT NULL COMMENT 'the description of this Automation Controller VM',
  `zone_id` bigint unsigned NOT NULL COMMENT 'the ID of the zone in which this Automation Controller VM is deployed',
  `automation_template_id` bigint unsigned NOT NULL COMMENT 'the ID of the Automation Controller Template id',
  `service_offering_id` bigint unsigned NOT NULL COMMENT 'service offering id for the Automation Controller VM',
  `network_id` bigint unsigned NOT NULL COMMENT 'the ID of the network used by this Automation Controller VM',
  `network_name` varchar(255) NOT NULL COMMENT 'the Name of the network used by this Automation Controller VM',
  `account_id` bigint unsigned NOT NULL COMMENT 'the ID of owner account of this Automation Controller VM',
  `domain_id` bigint unsigned NOT NULL COMMENT 'the ID of the domain of this Automation Controller VM',
  `state` char(32) NOT NULL COMMENT 'the current state of this Automation Controller VM',
  `automation_controller_ip` varchar(255) DEFAULT NULL COMMENT 'the IP Address of this Automation Controller VM',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime DEFAULT NULL COMMENT 'date removed or null, if still present',
  PRIMARY KEY (`id`),
  KEY `fk_automation_controller_service_vm_automation_template_id` (`automation_template_id`),
  KEY `fk_automation_controller_service_vm_network_id` (`service_offering_id`),
  KEY `fk_automation_controller_service_vm_service_offering_id` (`network_id`),
  KEY `fk_automation_controller_service_vm_zone_id` (`zone_id`),
  CONSTRAINT `fk_automation_controller_service_vm_automation_template_id` FOREIGN KEY (`automation_template_id`) REFERENCES `automation_controller_template_version` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_automation_controller_service_vm_network_id` FOREIGN KEY (`service_offering_id`) REFERENCES `service_offering` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_automation_controller_service_vm_service_offering_id` FOREIGN KEY (`network_id`) REFERENCES `networks` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_automation_controller_service_vm_zone_id` FOREIGN KEY (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- Adding automation controller vm map table
CREATE TABLE IF NOT EXISTS `automation_vm_map` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `automation_controller_id` bigint unsigned NOT NULL COMMENT 'the ID of the Automation Controller',
  `vm_id` bigint unsigned NOT NULL COMMENT 'the ID of the VM',
  PRIMARY KEY (`id`),
  KEY `fk_automation_vm_map_automation_controller_id` (`automation_controller_id`),
  CONSTRAINT `fk_automation_vm_map_automation_controller_id` FOREIGN KEY (`automation_controller_id`) REFERENCES `automation_controller_service_vm` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- Adding automation controller resource group table
CREATE TABLE IF NOT EXISTS `automation_deployed_resources_group` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) DEFAULT NULL,
  `account_id` bigint unsigned NOT NULL COMMENT 'the ID of owner account of this Automation Controller VM',
  `domain_id` bigint unsigned NOT NULL,
  `zone_id` bigint unsigned NOT NULL COMMENT 'the ID of the zone in which this Automation Controller VM is deployed',
  `controller_id` bigint unsigned NOT NULL COMMENT 'the ID of the Automation Controller',
  `name` varchar(255) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'the name of deployed service',
  `description` varchar(255) NOT NULL COMMENT 'the description of the running service',
  `access_info` varchar(255) DEFAULT NULL COMMENT 'the description of how to access the service',
  `state` char(32) NOT NULL COMMENT 'the current state of this running service',
  `created` datetime NOT NULL COMMENT 'date created',
  `last_updated` datetime NOT NULL COMMENT 'Last update time',
  PRIMARY KEY (`id`),
  KEY `fk_automation_deployed_resources_group_account_id` (`account_id`),
  KEY `fk_automation_deployed_resources_group_controller_id` (`controller_id`),
  CONSTRAINT `fk_automation_deployed_resources_group_account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_automation_deployed_resources_group_controller_id` FOREIGN KEY (`controller_id`) REFERENCES `automation_controller_service_vm` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- Adding automation controller resource group detail table
CREATE TABLE IF NOT EXISTS `automation_deployed_resources_group_details` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) DEFAULT NULL,
  `deployed_group_id` bigint unsigned NOT NULL COMMENT 'the ID of the deployed service',
  `deployed_vm_id` bigint unsigned NOT NULL COMMENT 'the ID of the instance the service is running on',
  `service_unit_name` varchar(255) NOT NULL COMMENT 'the name of deployed unit service',
  `state` char(32) NOT NULL COMMENT 'the current state of this running service',
  `created` datetime NOT NULL COMMENT 'date created',
  PRIMARY KEY (`id`),
  KEY `fk_automation_deployed_resources_group_details_deployed_group_id` (`deployed_group_id`),
  KEY `fk_automation_deployed_resources_group_details_instance_id` (`deployed_vm_id`),
  CONSTRAINT `fk_automation_deployed_resources_group_details_deployed_group_id` FOREIGN KEY (`deployed_group_id`) REFERENCES `automation_deployed_resources_group` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_automation_deployed_resources_group_details_instance_id` FOREIGN KEY (`deployed_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

-- Event that changes the service state by checking the automation controller state
DELIMITER $$
CREATE EVENT IF NOT EXISTS `cloud`.`automation_service_checker`
ON SCHEDULE EVERY 1 MINUTE
COMMENT 'Check the status of the automation controller in 1 minute'
DO
BEGIN
	-- If the status of the Automation Controller is not Running, change the service group status to Disconnected
	UPDATE automation_deployed_resources_group adrg
	SET adrg.state = 'Disconnected'
	WHERE adrg.controller_id IN (
		SELECT id FROM automation_controller_service_vm WHERE removed IS NULL AND state != 'Running'
	)
	-- If the unit detail status check time differs from the now() time by more than 5 minutes, the service group status is changed to Disconnected.
; UPDATE automation_deployed_resources_group adrg
	SET adrg.state = 'Disconnected'
	WHERE TIMESTAMPDIFF(MINUTE , adrg.last_updated , UTC_TIMESTAMP()) >= 5
	-- Delete all unit details whose service group status is Disconnected
; DELETE FROM automation_deployed_resources_group_details adrgd
	WHERE adrgd.deployed_group_id IN (
		SELECT id FROM automation_deployed_resources_group WHERE state = 'Disconnected'
	)
;END$$
DELIMITER ;

DROP PROCEDURE IF EXISTS `cloud`.`ADD_COL`;

DELIMITER $$
CREATE PROCEDURE `cloud`.`ADD_COL`(
  IN tb_name VarChar(200),
  IN col_name VarChar(200),
  IN col_type VarChar(1000)
)
BEGIN

    IF NOT EXISTS ( SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = tb_name AND TABLE_SCHEMA = 'cloud' AND COLUMN_NAME = col_name ) THEN SET @ddl = CONCAT(' ALTER TABLE ', tb_name,' ADD COLUMN ', col_name, ' ', col_type); PREPARE STMT FROM @ddl; EXECUTE STMT; END IF; END$$
DELIMITER ;

CALL `cloud`.`ADD_COL`('storage_pool', 'krbd_path', 'VARCHAR(255) DEFAULT null');

-- Setting values ​​for favicon status
INSERT IGNORE INTO `cloud`.`configuration` (category, instance, component, name, value, description) VALUES ('Advanced', 'DEFAULT', 'FaviconState', 'favicon.state.interval', '60', 'Interval (in seconds) to favicon statistics.');
INSERT IGNORE INTO `cloud`.`configuration` (category, instance, component, name, value, description) VALUES ('Advanced', 'DEFAULT', 'FaviconState', 'favicon.stats.red.capacity', '0.40', 'Percentage (as a value between 0 and 1) of the infrastructure.');
INSERT IGNORE INTO `cloud`.`configuration` (category, instance, component, name, value, description) VALUES ('Advanced', 'DEFAULT', 'FaviconState', 'favicon.stats.yellow.capacity', '0.70', 'Percentage (as a value between 0 and 1) of the infrastructure.');
