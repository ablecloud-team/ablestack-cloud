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
-- Schema upgrade from ablestack-allo to ablestack-bronto
--;

-- Adding desktop controller version table
CREATE TABLE IF NOT EXISTS `cloud`.`desktop_controller_version` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) DEFAULT NULL,
  `name` varchar(255) NOT NULL COMMENT 'the name of this Desktop Controller Version',
  `description` varchar(255) NOT NULL COMMENT 'the description of this Desktop Controller Version',
  `version` varchar(32) NOT NULL COMMENT 'the version for thisDesktop Controller Version',
  `zone_id` bigint unsigned DEFAULT NULL COMMENT 'the ID of the zone for which this Desktop Controller Version is made available',
  `state` char(32) DEFAULT NULL COMMENT 'the enabled or disabled state for this Desktop Controller Version',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime DEFAULT NULL COMMENT 'date removed or null, if still present',
  PRIMARY KEY (`id`),
  KEY `fk_desktop_controller_version__zone_id` (`zone_id`),
  CONSTRAINT `fk_desktop_controller_version__zone_id` FOREIGN KEY (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Adding desktop cluster table
CREATE TABLE IF NOT EXISTS `desktop_cluster` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `zone_id` bigint unsigned NOT NULL COMMENT 'the ID of the zone in which this Desktop is deployed',
  `desktop_version_id` bigint unsigned NOT NULL COMMENT 'the ID of the Desktop Controller Version',
  `service_offering_id` bigint unsigned DEFAULT NULL COMMENT 'service offering id for the desktop VM',
  `ad_domain_name` varchar(255) NOT NULL COMMENT 'the name of the active directory domain of this Desktop',
  `network_id` bigint unsigned DEFAULT NULL COMMENT 'the ID of the network used by this Desktop',
  `access_type` varchar(40) NOT NULL COMMENT 'the access type of the network used by this Desktop Cluster',
  `account_id` bigint unsigned NOT NULL COMMENT 'the ID of owner account of this Desktop',
  `domain_id` bigint unsigned NOT NULL COMMENT 'the ID of the domain of this Desktop',
  `state` char(32) NOT NULL COMMENT 'the current state of this Desktop',
  `dc_ip` char(255) DEFAULT NULL COMMENT 'the IP Address of this Desktop ControlVM DC',
  `works_ip` char(255) DEFAULT NULL COMMENT 'the IP Address of this Desktop ControlVM Works',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime DEFAULT NULL COMMENT 'date removed or null, if still present',
  PRIMARY KEY (`id`),
  KEY `fk_cluster__desktop__zone_id` (`zone_id`),
  KEY `fk_cluster__desktop_version_id` (`desktop_version_id`),
  KEY `fk_cluster__desktop__service_offering_id` (`service_offering_id`),
  KEY `fk_cluster__desktop__network_id` (`network_id`),
  CONSTRAINT `fk_cluster__desktop_version_id` FOREIGN KEY (`desktop_version_id`) REFERENCES `desktop_controller_version` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_cluster__desktop__network_id` FOREIGN KEY (`network_id`) REFERENCES `networks` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_cluster__desktop__service_offering_id` FOREIGN KEY (`service_offering_id`) REFERENCES `service_offering` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_cluster__desktop__zone_id` FOREIGN KEY (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Adding desktop vm map table
CREATE TABLE IF NOT EXISTS `desktop_vm_map` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `desktop_cluster_id` bigint unsigned NOT NULL COMMENT 'the ID of the Desktop Cluster',
  `vm_id` bigint unsigned NOT NULL COMMENT 'the ID of the VM',
  `type` varchar(100) NOT NULL COMMENT 'vm type(control, desktop) in desktop cluster.',
  PRIMARY KEY (`id`),
  KEY `fk_desktop_vm_map__desktop_cluster_id` (`desktop_cluster_id`),
  CONSTRAINT `fk_desktop_vm_map__desktop_cluster_id` FOREIGN KEY (`desktop_cluster_id`) REFERENCES `desktop_cluster` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Adding desktop ip range table
CREATE TABLE IF NOT EXISTS `desktop_ip_range` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) DEFAULT NULL,
  `desktop_cluster_id` bigint unsigned NOT NULL COMMENT 'the ID of the Desktop',
  `gateway` varchar(255) DEFAULT NULL,
  `netmask` varchar(255) DEFAULT NULL,
  `start_ip` varchar(255) DEFAULT NULL,
  `end_ip` varchar(255) DEFAULT NULL,
  `removed` datetime DEFAULT NULL COMMENT 'date removed',
  `created` datetime DEFAULT NULL COMMENT 'date created',
  PRIMARY KEY (`id`),
  KEY `fk_desktop_ip_range__desktop_cluster_id` (`desktop_cluster_id`),
  CONSTRAINT `fk_desktop__ip_range__desktop_cluster_id` FOREIGN KEY (`desktop_cluster_id`) REFERENCES `desktop_cluster` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8;

-- Adding desktop template map table
CREATE TABLE IF NOT EXISTS `desktop_template_map` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `version_id` bigint unsigned NOT NULL COMMENT 'the ID of the Desktop Controller Version',
  `template_id` bigint unsigned NOT NULL COMMENT 'the ID of the VM',
  `type` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci DEFAULT NULL COMMENT 'vm type(works, dc) in desktop system vm template.',
  PRIMARY KEY (`id`),
  KEY `fk_desktop_template_map__version_id` (`version_id`),
  CONSTRAINT `fk_desktop_template_map__version_id` FOREIGN KEY (`version_id`) REFERENCES `desktop_controller_version` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Adding desktop master version table
CREATE TABLE IF NOT EXISTS `desktop_master_version` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) DEFAULT NULL,
  `name` varchar(255) NOT NULL COMMENT 'the name of this Desktop Master Version',
  `description` varchar(255) NOT NULL COMMENT 'the description of this Desktop Master Version',
  `template_id` bigint unsigned NOT NULL COMMENT 'the ID of the Desktop Master Version Template',
  `version` varchar(32) NOT NULL COMMENT 'the version for this Desktop Master Version',
  `zone_id` bigint unsigned DEFAULT NULL COMMENT 'the ID of the zone for which this Desktop Master Version is made available',
  `state` char(32) DEFAULT NULL COMMENT 'the enabled or disabled state for this Desktop Master Version',
  `upload_type` char(32) DEFAULT NULL COMMENT 'the template upload type for this Desktop Master Version',
  `type` varchar(100) CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL COMMENT 'window vm type(app or desktop)',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime DEFAULT NULL COMMENT 'date removed or null, if still present',
  PRIMARY KEY (`id`),
  KEY `fk_desktop_master_version__zone_id` (`zone_id`),
  CONSTRAINT `fk_desktop_master_version__zone_id` FOREIGN KEY (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;