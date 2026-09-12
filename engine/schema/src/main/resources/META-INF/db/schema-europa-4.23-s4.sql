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


-- Same-version Europa S4 migration. Keep statements safe to retry after partial DDL.

-- Quota tariff/usage mapping
CREATE TABLE IF NOT EXISTS `cloud_usage`.`quota_tariff_usage` (
    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `tariff_id` bigint(20) unsigned NOT NULL COMMENT 'ID of the tariff of the Quota usage detail calculated, foreign key to quota_tariff table',
    `quota_usage_id` bigint(20) unsigned NOT NULL COMMENT 'ID of the aggregation of Quota usage details, foreign key to quota_usage table',
    `quota_used` decimal(20,8) NOT NULL COMMENT 'Amount of quota used',
    PRIMARY KEY (`id`),
    CONSTRAINT `fk_quota_tariff_usage__tariff_id` FOREIGN KEY (`tariff_id`) REFERENCES `cloud_usage`.`quota_tariff` (`id`),
    CONSTRAINT `fk_quota_tariff_usage__quota_usage_id` FOREIGN KEY (`quota_usage_id`) REFERENCES `cloud_usage`.`quota_usage` (`id`));

-- Quota resource statement
INSERT INTO cloud.role_permissions (uuid, role_id, rule, permission, sort_order)
SELECT uuid(), role_id, 'quotaResourceStatement', permission, sort_order
FROM cloud.role_permissions rp
WHERE rule = 'quotaStatement' AND NOT EXISTS(SELECT 1 FROM cloud.role_permissions rp_ WHERE rp.role_id = rp_.role_id AND rp_.rule = 'quotaResourceStatement');


CREATE TABLE IF NOT EXISTS `cloud`.`storage_service_instance` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(4096) DEFAULT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `data_center_id` bigint unsigned NOT NULL,
  `vm_id` bigint unsigned DEFAULT NULL,
  `service_offering_id` bigint unsigned DEFAULT NULL,
  `provider` varchar(255) NOT NULL,
  `state` varchar(32) NOT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated` datetime DEFAULT NULL,
  `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_service_instance__uuid` (`uuid`),
  KEY `idx_storage_service_instance__account_id` (`account_id`),
  KEY `idx_storage_service_instance__domain_id` (`domain_id`),
  KEY `idx_storage_service_instance__data_center_id` (`data_center_id`),
  KEY `idx_storage_service_instance__vm_id` (`vm_id`),
  KEY `idx_storage_service_instance__state` (`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

ALTER TABLE cloud.backups MODIFY COLUMN external_id varchar(4096) DEFAULT NULL COMMENT 'external ID';

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'current_runtime_bundle_id', 'bigint unsigned DEFAULT NULL COMMENT "Current verified Storage Service runtime bundle"');

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'previous_runtime_bundle_id', 'bigint unsigned DEFAULT NULL COMMENT "Previous verified Storage Service runtime bundle"');

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'runtime_state', 'varchar(32) DEFAULT NULL COMMENT "Current Storage Service runtime state"');

CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'runtime_verified_at', 'datetime DEFAULT NULL COMMENT "Last verified Storage Service runtime time"');

UPDATE cloud.guest_os_hypervisor SET guest_os_name = 'rhel9_64Guest' WHERE guest_os_name = 'rhel9_64Guest,';

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Ubuntu 20.04 LTS' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Ubuntu' AND removed IS NULL ORDER BY id LIMIT 1), 10), 'Ubuntu 20.04 LTS', 'KVM', 'default', 'Ubuntu 20.04 LTS');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Ubuntu 21.04' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Ubuntu' AND removed IS NULL ORDER BY id LIMIT 1), 10), 'Ubuntu 21.04', 'KVM', 'default', 'Ubuntu 21.04');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'pfSense 2.4' ORDER BY id LIMIT 1), 9), 'pfSense 2.4', 'KVM', 'default', 'pfSense 2.4');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'OpenBSD 6.7' ORDER BY id LIMIT 1), 9), 'OpenBSD 6.7', 'KVM', 'default', 'OpenBSD 6.7');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'OpenBSD 6.8' ORDER BY id LIMIT 1), 9), 'OpenBSD 6.8', 'KVM', 'default', 'OpenBSD 6.8');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'AlmaLinux 8.3' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'AlmaLinux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'AlmaLinux 8.3', 'KVM', 'default', 'AlmaLinux 8.3');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Debian GNU/Linux 11 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Debian' AND removed IS NULL ORDER BY id LIMIT 1), 2), 'Debian GNU/Linux 11 (64-bit)', 'XenServer', '8.2.1', 'Debian Bullseye 11');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Debian GNU/Linux 11 (32-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Debian' AND removed IS NULL ORDER BY id LIMIT 1), 2), 'Debian GNU/Linux 11 (32-bit)', 'XenServer', '8.2.1', 'Debian Bullseye 11');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'AlmaLinux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'AlmaLinux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'AlmaLinux 9', 'KVM', 'default', 'AlmaLinux 9');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'CentOS 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'CentOS' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'CentOS 9', 'KVM', 'default', 'CentOS 9');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Oracle Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Oracle' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Oracle Linux 9', 'KVM', 'default', 'Oracle Linux 9');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Red Hat Enterprise Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'RedHat' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Red Hat Enterprise Linux 9', 'KVM', 'default', 'Red Hat Enterprise Linux 9');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Rocky Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Rocky Linux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Rocky Linux 9', 'KVM', 'default', 'Rocky Linux 9');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'AlmaLinux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'AlmaLinux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'AlmaLinux 9', 'VMware', '7.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Oracle Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Oracle' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Oracle Linux 9', 'VMware', '7.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Red Hat Enterprise Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'RedHat' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Red Hat Enterprise Linux 9', 'VMware', '7.0', 'rhel9_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Rocky Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Rocky Linux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Rocky Linux 9', 'VMware', '7.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'AlmaLinux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'AlmaLinux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'AlmaLinux 9', 'VMware', '7.0.1.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Oracle Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Oracle' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Oracle Linux 9', 'VMware', '7.0.1.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Red Hat Enterprise Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'RedHat' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Red Hat Enterprise Linux 9', 'VMware', '7.0.1.0', 'rhel9_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Rocky Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Rocky Linux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Rocky Linux 9', 'VMware', '7.0.1.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'AlmaLinux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'AlmaLinux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'AlmaLinux 9', 'VMware', '7.0.2.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Oracle Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Oracle' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Oracle Linux 9', 'VMware', '7.0.2.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Red Hat Enterprise Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'RedHat' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Red Hat Enterprise Linux 9', 'VMware', '7.0.2.0', 'rhel9_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Rocky Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Rocky Linux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Rocky Linux 9', 'VMware', '7.0.2.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'AlmaLinux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'AlmaLinux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'AlmaLinux 9', 'VMware', '7.0.3.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Oracle Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Oracle' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Oracle Linux 9', 'VMware', '7.0.3.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Red Hat Enterprise Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'RedHat' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Red Hat Enterprise Linux 9', 'VMware', '7.0.3.0', 'rhel9_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Rocky Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Rocky Linux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Rocky Linux 9', 'VMware', '7.0.3.0', 'otherLinux64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6),'Windows Server 2022 (64-bit)','KVM','default','Windows Server 2022 (64-bit)');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6),'Windows Server 2022 (64-bit)','VMware','7.0','windows2019srvNext_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6),'Windows Server 2022 (64-bit)','VMware','7.0.1.0','windows2019srvNext_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6),'Windows Server 2022 (64-bit)','VMware','7.0.2.0','windows2019srvNext_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6),'Windows Server 2022 (64-bit)','VMware','7.0.3.0','windows2019srvNext_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6),'Windows Server 2022 (64-bit)','VMware','8.0','windows2019srvNext_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6),'Windows Server 2022 (64-bit)','VMware','8.0.0.1','windows2019srvNext_64Guest');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6),'Windows Server 2022 (64-bit)','Xenserver','8.2.0','Windows Server 2022 (64-bit)');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Debian GNU/Linux 10 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Debian' AND removed IS NULL ORDER BY id LIMIT 1), 2), 'Debian GNU/Linux 10 (64-bit)', 'XenServer', '8.2.1', 'Debian Buster 10');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'SUSE Linux Enterprise Server 15 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'SUSE' AND removed IS NULL ORDER BY id LIMIT 1), 5), 'SUSE Linux Enterprise Server 15 (64-bit)', 'XenServer', '8.2.1', 'SUSE Linux Enterprise 15 (64-bit)');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2022 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6), 'Windows Server 2022 (64-bit)', 'XenServer', '8.2.1', 'Windows Server 2022 (64-bit)');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows 11 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6), 'Windows 11 (64-bit)', 'XenServer', '8.2.1', 'Windows 11');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Ubuntu 20.04 LTS' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Ubuntu' AND removed IS NULL ORDER BY id LIMIT 1), 10), 'Ubuntu 20.04 LTS', 'XenServer', '8.2.1', 'Ubuntu Focal Fossa 20.04');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Rocky Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Rocky Linux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Rocky Linux 9', 'XenServer', '8.3.0', 'Rocky Linux 9');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Rocky Linux 8' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Rocky Linux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'Rocky Linux 8', 'XenServer', '8.3.0', 'Rocky Linux 8');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'AlmaLinux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'AlmaLinux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'AlmaLinux 9', 'XenServer', '8.3.0', 'AlmaLinux 9');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'AlmaLinux 8' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'AlmaLinux' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'AlmaLinux 8', 'XenServer', '8.3.0', 'AlmaLinux 8');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Debian GNU/Linux 12 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Debian' AND removed IS NULL ORDER BY id LIMIT 1), 2), 'Debian GNU/Linux 12 (64-bit)', 'XenServer', '8.3.0', 'Debian Bookworm 12');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Oracle Linux 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Oracle' AND removed IS NULL ORDER BY id LIMIT 1), 3), 'Oracle Linux 9', 'XenServer', '8.3.0', 'Oracle Linux 9');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Oracle Linux 8' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Oracle' AND removed IS NULL ORDER BY id LIMIT 1), 3), 'Oracle Linux 8', 'XenServer', '8.3.0', 'Oracle Linux 8');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Red Hat Enterprise Linux 8.0' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'RedHat' AND removed IS NULL ORDER BY id LIMIT 1), 4), 'Red Hat Enterprise Linux 8.0', 'XenServer', '8.3.0', 'Red Hat Enterprise Linux 8');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Red Hat Enterprise Linux 9.0' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'RedHat' AND removed IS NULL ORDER BY id LIMIT 1), 4), 'Red Hat Enterprise Linux 9.0', 'XenServer', '8.3.0', 'Red Hat Enterprise Linux 9');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Ubuntu 22.04 LTS' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Ubuntu' AND removed IS NULL ORDER BY id LIMIT 1), 10), 'Ubuntu 22.04 LTS', 'XenServer', '8.3.0', 'Ubuntu Jammy Jellyfish 22.04');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'SUSE Linux Enterprise Server 12 SP5 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'SUSE' AND removed IS NULL ORDER BY id LIMIT 1), 5), 'SUSE Linux Enterprise Server 12 SP5 (64-bit)', 'XenServer', '8.3.0', 'SUSE Linux Enterprise Server 12 SP5 (64-bit');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'NeoKylin Linux Server 7' ORDER BY id LIMIT 1), 4), 'NeoKylin Linux Server 7', 'XenServer', '8.3.0', 'NeoKylin Linux Server 7');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'CentOS Stream 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'CentOS' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'CentOS Stream 9', 'XenServer', '8.3.0', 'CentOS Stream 9');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Scientific Linux 7' ORDER BY id LIMIT 1), 4), 'Scientific Linux 7', 'XenServer', '8.3.0', 'Scientific Linux 7');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Generic Linux UEFI' ORDER BY id LIMIT 1), 7), 'Generic Linux UEFI', 'XenServer', '8.3.0', 'Generic Linux UEFI');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Generic Linux BIOS' ORDER BY id LIMIT 1), 7), 'Generic Linux BIOS', 'XenServer', '8.3.0', 'Generic Linux BIOS');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Gooroom Platform 2.0' ORDER BY id LIMIT 1), 2), 'Gooroom Platform 2.0', 'XenServer', '8.3.0', 'Gooroom Platform 2.0');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2025' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6), 'Windows Server 2025', 'XenServer', '8.4.0', 'Windows Server 2025');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Ubuntu 24.04 LTS' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Ubuntu' AND removed IS NULL ORDER BY id LIMIT 1), 10), 'Ubuntu 24.04 LTS', 'XenServer', '8.4.0', 'Ubuntu Noble Numbat 24.04');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Debian GNU/Linux 10 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Debian' AND removed IS NULL ORDER BY id LIMIT 1), 2), 'Debian GNU/Linux 10 (64-bit)', 'KVM', 'default', 'Debian GNU/Linux 10 (64-bit)');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Debian GNU/Linux 11 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Debian' AND removed IS NULL ORDER BY id LIMIT 1), 2), 'Debian GNU/Linux 11 (64-bit)', 'KVM', 'default', 'Debian GNU/Linux 11 (64-bit)');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Debian GNU/Linux 12 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Debian' AND removed IS NULL ORDER BY id LIMIT 1), 2), 'Debian GNU/Linux 12 (64-bit)', 'KVM', 'default', 'Debian GNU/Linux 12 (64-bit)');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows 11 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6), 'Windows 11 (64-bit)', 'KVM', 'default', 'Windows 11');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows Server 2025' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6), 'Windows Server 2025', 'KVM', 'default', 'Windows Server 2025');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Ubuntu 24.04 LTS' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Ubuntu' AND removed IS NULL ORDER BY id LIMIT 1), 10), 'Ubuntu 24.04 LTS', 'KVM', 'default', 'Ubuntu 24.04 LTS');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'CentOS Stream 10 (preview)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'CentOS' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'CentOS Stream 10 (preview)', 'XenServer', '8.4.0', 'CentOS Stream 10 (preview)');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'CentOS Stream 9' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'CentOS' AND removed IS NULL ORDER BY id LIMIT 1), 1), 'CentOS Stream 9', 'XenServer', '8.4.0', 'CentOS Stream 9');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Scientific Linux 7' ORDER BY id LIMIT 1), 4), 'Scientific Linux 7', 'XenServer', '8.4.0', 'Scientific Linux 7');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'NeoKylin Linux Server 7' ORDER BY id LIMIT 1), 4), 'NeoKylin Linux Server 7', 'XenServer', '8.4.0', 'NeoKylin Linux Server 7');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'SUSE Linux Enterprise Server 12 SP5 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'SUSE' AND removed IS NULL ORDER BY id LIMIT 1), 5), 'SUSE Linux Enterprise Server 12 SP5 (64-bit)', 'XenServer', '8.4.0', 'SUSE Linux Enterprise Server 12 SP5 (64-bit');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Gooroom Platform 2.0' ORDER BY id LIMIT 1), 2), 'Gooroom Platform 2.0', 'XenServer', '8.4.0', 'Gooroom Platform 2.0');

CALL `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Rocky Linux 10' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Rocky Linux' AND removed IS NULL ORDER BY id LIMIT 1), 13), 'Rocky Linux 10', 'KVM', 'default', 'Rocky Linux 10');

CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (COALESCE((SELECT category_id FROM cloud.guest_os WHERE display_name = 'Windows 11 (64-bit)' ORDER BY id LIMIT 1), (SELECT id FROM cloud.guest_os_category WHERE name = 'Windows' AND removed IS NULL ORDER BY id LIMIT 1), 6), 'Windows 11 (64-bit)', 'KVM', 'default', 'Windows 11 (64-bit)');

-- Required pre-checkpoint API dependency: Apache #9590 / 449d3c7cb1.
INSERT INTO cloud.role_permissions (uuid, role_id, rule, permission, sort_order)
SELECT uuid(), role_id, 'quotaCreditsList', permission, sort_order
FROM cloud.role_permissions rp
WHERE rp.rule = 'quotaStatement'
AND NOT EXISTS (SELECT 1 FROM cloud.role_permissions existing WHERE rp.role_id = existing.role_id AND existing.rule = 'quotaCreditsList');

-- Baseline created this DR table before the later CREATE IF NOT EXISTS added its index.
CALL `cloud`.`IDEMPOTENT_ADD_KEY`('i_dr_sync_cycle__plan_run_sequence', 'cloud.dr_sync_cycle', '(plan_id, engine_run_uuid, sequence)');
