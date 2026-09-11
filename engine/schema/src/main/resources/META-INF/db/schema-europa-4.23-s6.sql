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

-- Add the 'keep_mac_address_on_public_nic' column to the 'cloud.networks' and 'cloud.vpc' tables
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.networks', 'keep_mac_address_on_public_nic', 'TINYINT(1) NOT NULL DEFAULT 1');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.vpc', 'keep_mac_address_on_public_nic', 'TINYINT(1) NOT NULL DEFAULT 1');

-- Increase length of value of extension details from 255 to 4096 to support longer details value
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.extension_details', 'value', 'value', 'VARCHAR(4096)');
CALL `cloud`.`IDEMPOTENT_CHANGE_COLUMN`('cloud.extension_resource_map_details', 'value', 'value', 'VARCHAR(4096)');

-- Add CustomAction service support to physical_network_service_providers
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.physical_network_service_providers', 'custom_action_service_provided', 'tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT "Is Custom Action service provided" AFTER `networkacl_service_provided`');


-- Add description for secondary IP addresses
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.nic_secondary_ips', 'description', 'VARCHAR(2048) DEFAULT NULL');

--- Change nw_rate and mc_rate column types from smallint unsigned to int unsigned to support larger rate values
ALTER TABLE `cloud`.`service_offering`
    MODIFY COLUMN `nw_rate` int unsigned DEFAULT 200 COMMENT 'network rate throttle mbits/s',
    MODIFY COLUMN `mc_rate` int unsigned DEFAULT 10 COMMENT 'mcast rate throttle mbits/s';

ALTER TABLE `cloud`.`network_offerings`
    MODIFY COLUMN `nw_rate` int unsigned COMMENT 'network rate throttle mbits/s',
    MODIFY COLUMN `mc_rate` int unsigned COMMENT 'mcast rate throttle mbits/s';

-- Soft delete port forwarding, load balancing and firewall rules
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.firewall_rules', 'removed', 'datetime DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.load_balancer_vm_map', 'removed', 'datetime DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.load_balancer_cert_map', 'removed', 'datetime DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.load_balancer_healthcheck_policies', 'removed', 'datetime DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.load_balancer_stickiness_policies', 'removed', 'datetime DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.global_load_balancer_lb_rule_map', 'removed', 'datetime DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.elastic_lb_vm_map', 'removed', 'datetime DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.tungsten_lb_health_monitor', 'removed', 'datetime DEFAULT NULL');
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.global_load_balancing_rules', 'removed', 'datetime DEFAULT NULL');

ALTER TABLE `cloud`.`load_balancer_vm_map`
DROP KEY `load_balancer_id`,
ADD UNIQUE KEY `load_balancer_id` (`load_balancer_id`, `instance_id`, `instance_ip`, `removed`);

ALTER TABLE `cloud`.`global_load_balancer_lb_rule_map`
DROP KEY `gslb_rule_id`,
ADD UNIQUE KEY `gslb_rule_id` (`gslb_rule_id`, `lb_rule_id`, `removed`);

-- ======================================================================
-- DNS Framework Schema
-- ======================================================================

-- DNS Server Table (Stores DNS Server Configurations)
CREATE TABLE IF NOT EXISTS `cloud`.`dns_server` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id of the dns server',
    `uuid` varchar(40) COMMENT 'uuid of the dns server',
    `name` varchar(255) NOT NULL COMMENT 'display name of the dns server',
    `provider_type` varchar(255) NOT NULL COMMENT 'Provider type such as PowerDns',
    `url` varchar(1024) NOT NULL COMMENT 'dns server url',
    `dns_username` varchar(255) COMMENT 'username or email for dns server credentials',
    `dns_api_key` varchar(255) NOT NULL COMMENT 'api key or token for the dns server ',
    `port` int(11) DEFAULT NULL COMMENT 'optional dns server port',
    `name_servers` varchar(1024) DEFAULT NULL COMMENT 'Comma separated list of name servers',
    `is_public` tinyint(1) NOT NULL DEFAULT '0',
    `public_domain_suffix` VARCHAR(255),
    `state` ENUM('Enabled', 'Disabled') NOT NULL DEFAULT 'Disabled',
    `domain_id` bigint unsigned COMMENT 'for domain-specific ownership',
    `account_id` bigint(20) unsigned NOT NULL,
    `created` datetime NOT NULL COMMENT 'date created',
    `removed` datetime DEFAULT NULL COMMENT 'Date removed (soft delete)',
    PRIMARY KEY (`id`),
    KEY `i_dns_server__account_id` (`account_id`),
    CONSTRAINT `fk_dns_server__account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- DNS Server Details Table
CREATE TABLE IF NOT EXISTS `cloud`.`dns_server_details` (
  `id` bigint unsigned UNIQUE NOT NULL AUTO_INCREMENT COMMENT 'id',
  `dns_server_id` bigint unsigned NOT NULL COMMENT 'dns_server the detail is related to',
  `name` varchar(255) NOT NULL COMMENT 'name of the detail',
  `value` varchar(255) NOT NULL COMMENT 'value of the detail',
  `display` tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Should detail be displayed to the end user',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_dns_server_details__dns_server_id` FOREIGN KEY (`dns_server_id`) REFERENCES `dns_server`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- DNS Zone Table (Stores DNS Zone Metadata)
CREATE TABLE IF NOT EXISTS `cloud`.`dns_zone` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id of the dns zone',
    `uuid` varchar(40) COMMENT 'uuid of the dns zone',
    `name` varchar(255) NOT NULL COMMENT 'dns zone name (e.g. example.com)',
    `dns_server_id` bigint unsigned NOT NULL COMMENT 'fk to dns_server.id',
    `external_reference` VARCHAR(255) COMMENT 'id of external provider resource',
    `domain_id` bigint unsigned COMMENT 'for domain-specific ownership',
    `account_id` bigint unsigned COMMENT 'account id. foreign key to account table',
    `description` varchar(1024) DEFAULT NULL,
    `type` ENUM('Private', 'Public') NOT NULL DEFAULT 'Public',
    `state` ENUM('Active', 'Inactive') NOT NULL DEFAULT 'Inactive',
    `created` datetime NOT NULL COMMENT 'date created',
    `removed` datetime DEFAULT NULL COMMENT 'Date removed (soft delete)',
    PRIMARY KEY (`id`),
    CONSTRAINT `uc_dns_zone__uuid` UNIQUE (`uuid`),
    KEY `i_dns_zone__dns_server` (`dns_server_id`),
    KEY `i_dns_zone__account_id` (`account_id`),
    CONSTRAINT `fk_dns_zone__dns_server_id` FOREIGN KEY (`dns_server_id`) REFERENCES `dns_server` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dns_zone__account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dns_zone__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- DNS Zone Network Map (One-to-Many Link)
CREATE TABLE IF NOT EXISTS `cloud`.`dns_zone_network_map` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id of the dns zone to network mapping',
  `uuid` varchar(40),
  `dns_zone_id` bigint(20) unsigned NOT NULL,
  `network_id` bigint(20) unsigned NOT NULL COMMENT 'network to which dns zone is associated to',
  `sub_domain` varchar(255) DEFAULT NULL COMMENT 'Subdomain for auto-registration',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime DEFAULT NULL COMMENT 'Date removed (soft delete)',
  PRIMARY KEY (`id`),
  CONSTRAINT `uc_dns_zone_network_map__uuid` UNIQUE (`uuid`),
  KEY `fk_dns_map__zone_id` (`dns_zone_id`),
  KEY `fk_dns_map__network_id` (`network_id`),
  CONSTRAINT `fk_dns_map__zone_id` FOREIGN KEY (`dns_zone_id`) REFERENCES `dns_zone` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dns_map__network_id` FOREIGN KEY (`network_id`) REFERENCES `networks` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- This is part of allowing firewall rules on public IP addresses in VPC network
ALTER TABLE `cloud`.`firewall_rules` MODIFY COLUMN `network_id` BIGINT UNSIGNED NULL;
