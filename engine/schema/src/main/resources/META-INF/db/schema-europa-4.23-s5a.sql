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



-- Same-version Europa S5A migration. Schedule table renames run in EuropaComputeSchemaUpgrade.

-- Add Windows Server 2025 guest OS and mappings
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '7.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '7.0.1.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '7.0.2.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '7.0.3.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.0.1', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.0.2', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.0.3', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.1', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.2', 'windows2022srvNext_64Guest');
CALL ADD_GUEST_OS_AND_HYPERVISOR_MAPPING (6, 'Windows Server 2025 (64-bit)', 'VMware', '8.0.3', 'windows2022srvNext_64Guest');


--- Per-VM ISO attachments. user_vm.iso_id remains as the primary/bootable ISO pointer.
CREATE TABLE IF NOT EXISTS `cloud`.`vm_iso_map` (
    `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
    `vm_id` bigint(20) unsigned NOT NULL COMMENT 'foreign key to user_vm',
    `iso_id` bigint(20) unsigned NOT NULL COMMENT 'foreign key to vm_template (ISOs are templates of format ISO)',
    `device_seq` int(10) unsigned NOT NULL COMMENT 'cdrom slot index used to derive the libvirt device label (3=hdc, 4=hdd)',
    `created` datetime NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uc_vm_iso_map__vm_iso` (`vm_id`, `iso_id`),
    UNIQUE KEY `uc_vm_iso_map__vm_seq` (`vm_id`, `device_seq`),
    CONSTRAINT `fk_vm_iso_map__vm_id` FOREIGN KEY (`vm_id`) REFERENCES `cloud`.`user_vm` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_vm_iso_map__iso_id` FOREIGN KEY (`iso_id`) REFERENCES `cloud`.`vm_template` (`id`)
);



-- Creates the 'kvm.memory.dynamic.scaling.capacity' and, for already active ACS environments,
-- initializes it with the value of the setting 'vm.serviceoffering.ram.size.max'
INSERT INTO `cloud`.`configuration` (`category`, `instance`, `component`, `name`, `value`, `default_value`, `updated`, `scope`, `is_dynamic`, `group_id`, `subgroup_id`, `display_text`, `description`)
SELECT 'Advanced', 'DEFAULT', 'CapacityManager', 'kvm.memory.dynamic.scaling.capacity', `cfg`.`value`, 0, NULL, 4, 1, 6, 27,
       'KVM memory dynamic scaling capacity', 'Defines the maximum memory capacity in MiB for which VMs can be dynamically scaled to with KVM. The ''kvm.memory.dynamic.scaling.capacity'' setting''s value will be used to define the value of the ''<maxMemory />'' element of domain XMLs. If it is set to a value less than or equal to ''0'', then the host''s memory capacity will be considered.'
FROM `cloud`.`configuration` `cfg`
WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`configuration` WHERE `name` = 'kvm.memory.dynamic.scaling.capacity')
  AND `cfg`.`name` = 'vm.serviceoffering.ram.size.max';

-- Creates the 'kvm.cpu.dynamic.scaling.capacity' and, for already active ACS environments,
-- initializes it with the value of the setting 'vm.serviceoffering.cpu.cores.max'
INSERT INTO `cloud`.`configuration` (`category`, `instance`, `component`, `name`, `value`, `default_value`, `updated`, `scope`, `is_dynamic`, `group_id`, `subgroup_id`, `display_text`, `description`)
SELECT 'Advanced', 'DEFAULT', 'CapacityManager', 'kvm.cpu.dynamic.scaling.capacity', `cfg`.`value`, 0, NULL, 4, 1, 6, 27,
       'KVM CPU dynamic scaling capacity', 'Defines the maximum vCPU capacity for which VMs can be dynamically scaled to with KVM. The ''kvm.cpu.dynamic.scaling.capacity'' setting''s value will be used to define the value of the ''<vcpu />'' element of domain XMLs. If it is set to a value less than or equal to ''0'', then the host''s CPU cores capacity will be considered.'
FROM `cloud`.`configuration` `cfg`
WHERE NOT EXISTS (SELECT 1 FROM `cloud`.`configuration` WHERE `name` = 'kvm.cpu.dynamic.scaling.capacity')
  AND `cfg`.`name` = 'vm.serviceoffering.cpu.cores.max';


-- Step 3: details table for action-specific parameters (used by the generic resource schedule API in Commit 2).
CREATE TABLE IF NOT EXISTS `cloud`.`resource_schedule_details` (
    `id` bigint unsigned NOT NULL auto_increment,
    `schedule_id` bigint unsigned NOT NULL COMMENT 'id of the resource_schedule row',
    `name` varchar(255) NOT NULL,
    `value` varchar(1024) NOT NULL,
    `display` tinyint(1) NOT NULL DEFAULT 1 COMMENT 'should this detail be visible to the end user',
    PRIMARY KEY (`id`),
    INDEX `i_resource_schedule_details__schedule_id` (`schedule_id`),
    CONSTRAINT `fk_resource_schedule_details__schedule_id` FOREIGN KEY (`schedule_id`) REFERENCES `resource_schedule`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


-- Step 4: rename CRUD event types from VM.SCHEDULE.{CREATE,UPDATE,DELETE} to the new generic SCHEDULE.{CREATE,UPDATE,DELETE}.
-- Action-execution events (VM.SCHEDULE.START, .STOP, .REBOOT, .FORCE_STOP, .FORCE_REBOOT) keep their existing names.
UPDATE `cloud`.`event` SET `type` = 'SCHEDULE.CREATE' WHERE `type` = 'VM.SCHEDULE.CREATE';
UPDATE `cloud`.`event` SET `type` = 'SCHEDULE.UPDATE' WHERE `type` = 'VM.SCHEDULE.UPDATE';
UPDATE `cloud`.`event` SET `type` = 'SCHEDULE.DELETE' WHERE `type` = 'VM.SCHEDULE.DELETE';


-- Preserve the configured scheduler retention interval on upgrade.
UPDATE cloud.configuration old_config
LEFT JOIN cloud.configuration new_config ON new_config.name='scheduler.jobs.expire.interval'
SET old_config.name='scheduler.jobs.expire.interval'
WHERE old_config.name='vmscheduler.jobs.expire.interval' AND new_config.name IS NULL;
