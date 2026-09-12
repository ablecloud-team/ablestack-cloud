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
-- Isolated fixture only. Identity stubs plus authentication DDL from Europa 014895d8f3.
CREATE DATABASE cloud CHARACTER SET utf8mb4;
CREATE TABLE cloud.domain (id BIGINT UNSIGNED PRIMARY KEY);
CREATE TABLE cloud.account (id BIGINT UNSIGNED PRIMARY KEY, domain_id BIGINT UNSIGNED NOT NULL);
CREATE TABLE cloud.user (id BIGINT UNSIGNED PRIMARY KEY, account_id BIGINT UNSIGNED NOT NULL);
INSERT INTO cloud.domain VALUES (1),(2),(3);
INSERT INTO cloud.account VALUES (2,2);
INSERT INTO cloud.user VALUES (2,2);
CREATE TABLE IF NOT EXISTS `cloud`.`api_keypair` (
    `id` bigint(20) unsigned NOT NULL auto_increment,
    `uuid` varchar(40) UNIQUE NOT NULL,
    `name` varchar(255) NOT NULL,
    `domain_id` bigint(20) unsigned NOT NULL,
    `account_id` bigint(20) unsigned NOT NULL,
    `user_id` bigint(20) unsigned NOT NULL,
    `start_date` datetime,
    `end_date` datetime,
    `description` varchar(100),
    `api_key` varchar(255) NOT NULL,
    `secret_key` varchar(255) NOT NULL,
    `created` datetime NOT NULL,
    `removed` datetime,
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_api_keypair__user_id` FOREIGN KEY(`user_id`) REFERENCES `cloud`.`user`(`id`),
    CONSTRAINT `fk_api_keypair__account_id` FOREIGN KEY(`account_id`) REFERENCES `cloud`.`account`(`id`),
    CONSTRAINT `fk_api_keypair__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `cloud`.`domain`(`id`)
);
CREATE TABLE IF NOT EXISTS  `cloud`.`oauth_provider` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40) NOT NULL COMMENT 'unique identifier',
  `description` varchar(1024) COMMENT 'description of the provider',
  `provider` varchar(40) NOT NULL COMMENT 'name of the provider',
  `client_id` varchar(255) NOT NULL COMMENT 'client id which is configured in the provider',
  `secret_key` varchar(255) NOT NULL COMMENT 'secret key which is configured in the provider',
  `redirect_uri` varchar(255) NOT NULL COMMENT 'redirect uri which is configured in the provider',
  `enabled` int(1) NOT NULL DEFAULT 1 COMMENT 'Enabled or disabled',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY (`id`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS `cloud`.`roles` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(255) UNIQUE,
  `name` varchar(255) COMMENT 'unique name of the dynamic role',
  `role_type` varchar(255) NOT NULL COMMENT 'the type of the role',
  `removed` datetime COMMENT 'date removed',
  `description` text COMMENT 'description of the role',
  PRIMARY KEY (`id`),
  KEY `i_roles__name` (`name`),
  KEY `i_roles__role_type` (`role_type`),
  UNIQUE KEY (`name`, `role_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS `cloud`.`role_permissions` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(255) UNIQUE,
  `role_id` bigint(20) unsigned NOT NULL COMMENT 'id of the role',
  `rule` varchar(255) NOT NULL COMMENT 'rule for the role, api name or wildcard',
  `permission` varchar(255) NOT NULL COMMENT 'access authority, allow or deny',
  `description` text COMMENT 'description of the rule',
  `sort_order` bigint(20) unsigned NOT NULL DEFAULT 0 COMMENT 'permission sort order',
  PRIMARY KEY (`id`),
  KEY `fk_role_permissions__role_id` (`role_id`),
  KEY `i_role_permissions__sort_order` (`sort_order`),
  UNIQUE KEY (`role_id`, `rule`),
  CONSTRAINT `fk_role_permissions__role_id` FOREIGN KEY (`role_id`) REFERENCES `roles` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
ALTER TABLE cloud.roles ADD is_default TINYINT NOT NULL DEFAULT 0;
INSERT INTO cloud.roles(id,uuid,name,role_type,is_default) VALUES (1,UUID(),'User','User',1),(2,UUID(),'Domain Admin','DomainAdmin',1),(3,UUID(),'Resource Admin','ResourceAdmin',1),(4,UUID(),'Custom role','User',0);
INSERT INTO cloud.role_permissions(uuid,role_id,rule,permission,sort_order) VALUES (UUID(),1,'*','DENY',10),(UUID(),2,'listUserKeyRules','DENY',5),(UUID(),3,'*','DENY',10),(UUID(),4,'*','DENY',10);
INSERT INTO cloud.api_keypair(uuid,name,domain_id,account_id,user_id,description,api_key,secret_key,created) VALUES ('fixture-key','Preserved legacy key',2,2,2,'original description','fixture-existing-api-key','fixture-encrypted-secret',NOW());
INSERT INTO cloud.oauth_provider(uuid,provider,client_id,secret_key,redirect_uri,created) VALUES ('fixture-oauth','google','existing-client','existing-provider-secret','https://cloud.example/redirect',NOW());

CREATE TABLE cloud.epic991_fixture_guard(marker VARCHAR(40));
INSERT INTO cloud.epic991_fixture_guard VALUES ('epic991-disposable');
