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
SET foreign_key_checks = 0;
use cloud;

DROP VIEW IF EXISTS `cloud`.`port_forwarding_rules_view`;
DROP TABLE IF EXISTS `cloud`.`dr_test_disk`;
DROP TABLE IF EXISTS `cloud`.`dr_test_session`;
DROP TABLE IF EXISTS `cloud`.`dr_target_resource_claim`;
DROP TABLE IF EXISTS `cloud`.`dr_group_run`;
DROP TABLE IF EXISTS `cloud`.`dr_resource_lease`;
DROP TABLE IF EXISTS `cloud`.`dr_event`;
DROP TABLE IF EXISTS `cloud`.`dr_run_step`;
DROP TABLE IF EXISTS `cloud`.`dr_run`;
DROP TABLE IF EXISTS `cloud`.`dr_replica_disk`;
DROP TABLE IF EXISTS `cloud`.`dr_replica`;
DROP TABLE IF EXISTS `cloud`.`dr_restore_point_artifact`;
DROP TABLE IF EXISTS `cloud`.`dr_restore_point`;
DROP TABLE IF EXISTS `cloud`.`dr_plan`;
DROP TABLE IF EXISTS `cloud`.`dr_site_pair`;
DROP TABLE IF EXISTS `cloud`.`dr_site_health_check`;
DROP TABLE IF EXISTS `cloud`.`dr_site_credential`;
DROP TABLE IF EXISTS `cloud`.`dr_site`;
DROP TABLE IF EXISTS `cloud`.`ftctl_protection_volume`;
DROP TABLE IF EXISTS `cloud`.`ftctl_protection`;
DROP TABLE IF EXISTS `cloud`.`configuration`;
DROP TABLE IF EXISTS `cloud`.`ip_forwarding`;
DROP TABLE IF EXISTS `cloud`.`management_agent`;
DROP TABLE IF EXISTS `cloud`.`host`;
DROP TABLE IF EXISTS `cloud`.`mshost`;
DROP TABLE IF EXISTS `cloud`.`service_offering`;
DROP TABLE IF EXISTS `cloud`.`user`;
DROP TABLE IF EXISTS `cloud`.`user_ip_address`;
DROP TABLE IF EXISTS `cloud`.`user_statistics`;
DROP TABLE IF EXISTS `cloud`.`vm_template`;
DROP TABLE IF EXISTS `cloud`.`vm_instance`;
DROP TABLE IF EXISTS `cloud`.`domain_router`;
DROP TABLE IF EXISTS `cloud`.`event`;
DROP TABLE IF EXISTS `cloud`.`host_details`;
DROP TABLE IF EXISTS `cloud`.`host_pod_ref`;
DROP TABLE IF EXISTS `cloud`.`host_zone_ref`;
DROP TABLE IF EXISTS `cloud`.`data_ceneter`;
DROP TABLE IF EXISTS `cloud`.`volumes`;
DROP TABLE IF EXISTS `cloud`.`storage`;
DROP TABLE IF EXISTS `cloud`.`data_center`;
DROP TABLE IF EXISTS `cloud`.`pricing`;
DROP TABLE IF EXISTS `cloud`.`sequence`;
DROP TABLE IF EXISTS `cloud`.`user_vm`;
DROP TABLE IF EXISTS `cloud`.`vm_guest_network_section_state`;
DROP TABLE IF EXISTS `cloud`.`vm_guest_network_state`;
DROP TABLE IF EXISTS `cloud`.`template_host_ref`;
DROP TABLE IF EXISTS `cloud`.`volume_host_ref`;
DROP TABLE IF EXISTS `cloud`.`upload`;
DROP TABLE IF EXISTS `cloud`.`template_zone_ref`;
DROP TABLE IF EXISTS `cloud`.`dc_vnet_alloc`;
DROP TABLE IF EXISTS `cloud`.`dc_ip_address_alloc`;
DROP TABLE IF EXISTS `cloud`.`vlan`;
DROP TABLE IF EXISTS `cloud`.`host_vlan_map`;
DROP TABLE IF EXISTS `cloud`.`pod_vlan_map`;
DROP TABLE IF EXISTS `cloud`.`vm_host`;
DROP TABLE IF EXISTS `cloud`.`op_ha_work`;
DROP TABLE IF EXISTS `cloud`.`op_dc_vnet_alloc`;
DROP TABLE IF EXISTS `cloud`.`op_dc_ip_address_alloc`;
DROP TABLE IF EXISTS `cloud`.`op_vm_host`;
DROP TABLE IF EXISTS `cloud`.`op_host_queue`;
DROP TABLE IF EXISTS `cloud`.`console_proxy`;
DROP TABLE IF EXISTS `cloud`.`secondary_storage_vm`;
DROP TABLE IF EXISTS `cloud`.`domain`;
DROP TABLE IF EXISTS `cloud`.`account`;
DROP TABLE IF EXISTS `cloud`.`limit`;
DROP TABLE IF EXISTS `cloud`.`op_host_capacity`;
DROP TABLE IF EXISTS `cloud`.`alert`;
DROP TABLE IF EXISTS `cloud`.`op_lock`;
DROP TABLE IF EXISTS `cloud`.`op_host_upgrade`;
DROP TABLE IF EXISTS `cloud`.`snapshots`;
DROP TABLE IF EXISTS `cloud`.`scheduled_volume_backups`;
DROP TABLE IF EXISTS `cloud`.`disk_offering`;
DROP TABLE IF EXISTS `cloud`.`security_group`;
DROP TABLE IF EXISTS `cloud`.`network_rule_config`;
DROP TABLE IF EXISTS `cloud`.`host_details`;
DROP TABLE IF EXISTS `cloud`.`launch_permission`;
DROP TABLE IF EXISTS `cloud`.`resource_limit`;
DROP TABLE IF EXISTS `cloud`.`async_job`;
DROP TABLE IF EXISTS `cloud`.`sync_queue`;
DROP TABLE IF EXISTS `cloud`.`sync_queue_item`;
DROP TABLE IF EXISTS `cloud`.`security_group_vm_map`;
DROP TABLE IF EXISTS `cloud`.`load_balancer_vm_map`;
DROP TABLE IF EXISTS `cloud`.`load_balancer_stickiness_policies`;
DROP TABLE IF EXISTS `cloud`.`load_balancer_inline_ip_map`;
DROP TABLE IF EXISTS `cloud`.`storage_pool`;
DROP TABLE IF EXISTS `cloud`.`storage_pool_host_ref`;
DROP TABLE IF EXISTS `cloud`.`template_spool_ref`;
DROP TABLE IF EXISTS `cloud`.`guest_os`;
DROP TABLE IF EXISTS `cloud`.`snapshot_policy`;
DROP TABLE IF EXISTS `cloud`.`snapshot_policy_ref`;
DROP TABLE IF EXISTS `cloud`.`snapshot_schedule`;
DROP TABLE IF EXISTS `cloud`.`op_pod_vlan_alloc`;
DROP TABLE IF EXISTS `cloud`.`storage_pool_details`;
DROP TABLE IF EXISTS `cloud`.`cluster`;
DROP TABLE IF EXISTS `cloud`.`nics`;
DROP TABLE IF EXISTS `cloud`.`networks`;
DROP TABLE IF EXISTS `cloud`.`op_networks`;
DROP TABLE IF EXISTS `cloud`.`network_offerings`;
DROP TABLE IF EXISTS `cloud`.`account_network_ref`;
DROP TABLE IF EXISTS `cloud`.`domain_network_ref`;
DROP TABLE IF EXISTS `cloud`.`instance_group`;
DROP TABLE IF EXISTS `cloud`.`instance_group_vm_map`;
DROP TABLE IF EXISTS `cloud`.`op_it_work`;
DROP TABLE IF EXISTS `cloud`.`load_balancing_ip_map`;
DROP TABLE IF EXISTS `cloud`.`load_balancing_rules`;
DROP TABLE IF EXISTS `cloud`.`port_forwarding_rules`;
DROP TABLE IF EXISTS `cloud`.`firewall_rules`;
DROP TABLE IF EXISTS `cloud`.`firewall_rules_cidrs`;
DROP TABLE IF EXISTS `cloud`.`ssh_keypairs`;
DROP TABLE IF EXISTS `cloud`.`usage_event`;
DROP TABLE IF EXISTS `cloud`.`host_tags`;
DROP TABLE IF EXISTS `cloud`.`version`;
DROP TABLE IF EXISTS `cloud`.`account_vlan_map`;
DROP TABLE IF EXISTS `cloud`.`cluster_details`;
DROP TABLE IF EXISTS `cloud`.`guest_os_category`;
DROP TABLE IF EXISTS `cloud`.`guest_os_hypervisor`;
DROP TABLE IF EXISTS `cloud`.`op_dc_link_local_ip_address_alloc`;
DROP TABLE IF EXISTS `cloud`.`op_host`;
DROP TABLE IF EXISTS `cloud`.`op_nwgrp_work`;
DROP TABLE IF EXISTS `cloud`.`op_vm_ruleset_log`;
DROP TABLE IF EXISTS `cloud`.`ovs_tunnel_network`;
DROP TABLE IF EXISTS `cloud`.`ovs_tunnel_interface`;
DROP TABLE IF EXISTS `cloud`.`remote_access_vpn`;
DROP TABLE IF EXISTS `cloud`.`resource_count`;
DROP TABLE IF EXISTS `cloud`.`security_ingress_rule`;
DROP TABLE IF EXISTS `cloud`.`security_group_rule`;
DROP TABLE IF EXISTS `cloud`.`stack_maid`;
DROP TABLE IF EXISTS `cloud`.`storage_pool_work`;
DROP TABLE IF EXISTS `cloud`.`user_vm_details`;
DROP TABLE IF EXISTS `cloud`.`vpn_users`;
DROP TABLE IF EXISTS `cloud`.`data_center_details`;
DROP TABLE IF EXISTS `cloud`.`network_tags`;
DROP TABLE IF EXISTS `cloud`.`op_host_transfer`;
DROP TABLE IF EXISTS `cloud`.`projects`;
DROP TABLE IF EXISTS `cloud`.`physical_network`;
DROP TABLE IF EXISTS `cloud`.`physical_network_tags`;
DROP TABLE IF EXISTS `cloud`.`physical_network_isolation_methods`;
DROP TABLE IF EXISTS `cloud`.`physical_network_traffic_types`;
DROP TABLE IF EXISTS `cloud`.`physical_network_service_providers`;
DROP TABLE IF EXISTS `cloud`.`virtual_router_elements`;
DROP TABLE IF EXISTS `cloud`.`dc_storage_network_ip_range`;
DROP TABLE IF EXISTS `cloud`.`op_dc_storage_network_ip_address`;
DROP TABLE IF EXISTS `cloud`.`cluster_vsm_map`;
DROP TABLE IF EXISTS `cloud`.`virtual_supervisor_module`;
DROP TABLE IF EXISTS `cloud`.`port_profile`;
DROP TABLE IF EXISTS `cloud`.`region`;
DROP TABLE IF EXISTS `cloud`.`s2s_customer_gateway`;
DROP TABLE IF EXISTS `cloud`.`s2s_vpn_gateway`;
DROP TABLE IF EXISTS `cloud`.`s2s_vpn_connection`;
DROP TABLE IF EXISTS `cloud`.`external_nicira_nvp_devices`;
DROP TABLE IF EXISTS `cloud`.`nicira_nvp_nic_map`;
DROP TABLE IF EXISTS `cloud`.`s3`;
DROP TABLE IF EXISTS `cloud`.`template_s3_ref`;
DROP TABLE IF EXISTS `cloud`.`nicira_nvp_router_map`;
DROP TABLE IF EXISTS `cloud`.`external_bigswitch_bcf_devices`;
DROP TABLE IF EXISTS `cloud`.`external_bigswitch_vns_devices`;
DROP TABLE IF EXISTS `cloud`.`autoscale_vmgroup_policy_map`;
DROP TABLE IF EXISTS `cloud`.`autoscale_vmgroup_vm_map`;
DROP TABLE IF EXISTS `cloud`.`autoscale_policy_condition_map`;
DROP TABLE IF EXISTS `cloud`.`autoscale_vmgroups`;
DROP TABLE IF EXISTS `cloud`.`autoscale_vmprofiles`;
DROP TABLE IF EXISTS `cloud`.`autoscale_policies`;
DROP TABLE IF EXISTS `cloud`.`counter`;
DROP TABLE IF EXISTS `cloud`.`conditions`;
DROP TABLE IF EXISTS `cloud`.`inline_load_balancer_nic_map`;
DROP TABLE IF EXISTS `cloud`.`cmd_exec_log`;
DROP TABLE IF EXISTS `cloud`.`keystore`;
DROP TABLE IF EXISTS `cloud`.`swift`;
DROP TABLE IF EXISTS `cloud`.`project_account`;
DROP TABLE IF EXISTS `cloud`.`project_invitations`;
DROP TABLE IF EXISTS `cloud`.`elastic_lb_vm_map`;
DROP TABLE IF EXISTS `cloud`.`ntwk_offering_service_map`;
DROP TABLE IF EXISTS `cloud`.`ntwk_service_map`;
DROP TABLE IF EXISTS `cloud`.`external_load_balancer_devices`;
DROP TABLE IF EXISTS `cloud`.`external_firewall_devices`;
DROP TABLE IF EXISTS `cloud`.`network_external_lb_device_map`;
DROP TABLE IF EXISTS `cloud`.`network_external_firewall_device_map`;
DROP TABLE IF EXISTS `cloud`.`virtual_router_providers`;
DROP TABLE IF EXISTS `cloud`.`op_user_stats_log`;
DROP TABLE IF EXISTS `cloud`.`netscaler_pod_ref`;
DROP TABLE IF EXISTS `cloud`.`mshost_peer`;
DROP TABLE IF EXISTS `cloud`.`vm_template_details`;
DROP TABLE IF EXISTS `cloud`.`hypervisor_capabilities`;
DROP TABLE IF EXISTS `cloud`.`template_swift_ref`;
DROP TABLE IF EXISTS `cloud`.`account_details`;
DROP TABLE IF EXISTS `cloud`.`vpc`;
DROP TABLE IF EXISTS `cloud`.`vpc_offerings`;
DROP TABLE IF EXISTS `cloud`.`vpc_offering_service_map`;
DROP TABLE IF EXISTS `cloud`.`vpc_gateways`;
DROP TABLE IF EXISTS `cloud`.`router_network_ref`;
DROP TABLE IF EXISTS `cloud`.`private_ip_address`;
DROP TABLE IF EXISTS `cloud`.`static_routes`;
DROP TABLE IF EXISTS `cloud`.`resource_tags`;
DROP TABLE IF EXISTS `cloud`.`primary_data_store_provider`;
DROP TABLE IF EXISTS `cloud`.`image_data_store_provider`;
DROP TABLE IF EXISTS `cloud`.`image_data_store`;
DROP TABLE IF EXISTS `cloud`.`vm_compute_tags`;
DROP TABLE IF EXISTS `cloud`.`vm_root_disk_tags`;
DROP TABLE IF EXISTS `cloud`.`vm_network_map`;

CREATE TABLE `cloud`.`version` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT COMMENT 'id',
  `version` char(40) NOT NULL UNIQUE COMMENT 'version',
  `updated` datetime NOT NULL COMMENT 'Date this version table was updated',
  `step` char(32) NOT NULL COMMENT 'Step in the upgrade to this version',
  PRIMARY KEY (`id`),
  INDEX `i_version__version`(`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT INTO `version` (`version`, `updated`, `step`) VALUES('4.0.0', now(), 'Complete');

CREATE TABLE `cloud`.`op_it_work` (
  `id` char(40) COMMENT 'reservation id',
  `mgmt_server_id` bigint unsigned COMMENT 'management server id',
  `created_at` bigint unsigned NOT NULL COMMENT 'when was this work detail created',
  `thread` varchar(255) NOT NULL COMMENT 'thread name',
  `type` char(32) NOT NULL COMMENT 'type of work',
  `vm_type` char(32) NOT NULL COMMENT 'type of vm',
  `step` char(32) NOT NULL COMMENT 'state',
  `updated_at` bigint unsigned NOT NULL COMMENT 'time it was taken over',
  `instance_id` bigint unsigned NOT NULL COMMENT 'vm instance',
  `resource_type` char(32) COMMENT 'type of resource being worked on',
  `resource_id` bigint unsigned COMMENT 'resource id being worked on',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_op_it_work__mgmt_server_id` FOREIGN KEY (`mgmt_server_id`) REFERENCES `mshost`(`msid`),
  CONSTRAINT `fk_op_it_work__instance_id` FOREIGN KEY (`instance_id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE,
  INDEX `i_op_it_work__step`(`step`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_networks`(
  `id` bigint unsigned NOT NULL UNIQUE KEY,
  `mac_address_seq` bigint unsigned NOT NULL DEFAULT 1 COMMENT 'mac address',
  `nics_count` int unsigned NOT NULL DEFAULT 0 COMMENT '# of nics',
  `gc` tinyint unsigned NOT NULL DEFAULT 1 COMMENT 'gc this network or not',
  `check_for_gc` tinyint unsigned NOT NULL DEFAULT 1 COMMENT 'check this network for gc or not',
  PRIMARY KEY(`id`),
  CONSTRAINT `fk_op_networks__id` FOREIGN KEY (`id`) REFERENCES `networks`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`networks` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `name` varchar(255) COMMENT 'name for this network',
  `uuid` varchar(40),
  `display_text` varchar(255) COMMENT 'display text for this network',
  `traffic_type` varchar(32) NOT NULL COMMENT 'type of traffic going through this network',
  `broadcast_domain_type` varchar(32) NOT NULL COMMENT 'type of broadcast domain used',
  `broadcast_uri` varchar(255) COMMENT 'broadcast domain specifier',
  `gateway` varchar(15) COMMENT 'gateway for this network configuration',
  `cidr` varchar(18) COMMENT 'network cidr',
  `mode` varchar(32) COMMENT 'How to retrieve ip address in this network',
  `network_offering_id` bigint unsigned NOT NULL COMMENT 'network offering id that this configuration is created from',
  `physical_network_id` bigint unsigned COMMENT 'physical network id that this configuration is based on',
  `data_center_id` bigint unsigned NOT NULL COMMENT 'data center id that this configuration is used in',
  `guru_name` varchar(255) NOT NULL COMMENT 'who is responsible for this type of network configuration',
  `state` varchar(32) NOT NULL COMMENT 'what state is this configuration in',
  `related` bigint unsigned NOT NULL COMMENT 'related to what other network configuration',
  `domain_id` bigint unsigned NOT NULL COMMENT 'foreign key to domain id',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner of this network',
  `dns1` varchar(255) COMMENT 'comma separated DNS list',
  `dns2` varchar(255) COMMENT 'comma separated DNS list',
  `guru_data` varchar(1024) COMMENT 'data stored by the network guru that setup this network',
  `set_fields` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'which fields are set already',
  `acl_type` varchar(15) COMMENT 'ACL access type. Null for system networks, can be Account/Domain for Guest networks',
  `network_domain` varchar(255) COMMENT 'domain',
  `reservation_id` char(40) COMMENT 'reservation id',
  `guest_type` char(32) COMMENT 'type of guest network that can be shared or isolated',
  `restart_required` int(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 if restart is required for the network',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime COMMENT 'date removed if not null',
  `specify_ip_ranges` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'true if the network provides an ability to define ip ranges',
  `vpc_id` bigint unsigned COMMENT 'vpc this network belongs to',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_networks__network_offering_id` FOREIGN KEY (`network_offering_id`) REFERENCES `network_offerings`(`id`),
  CONSTRAINT `fk_networks__data_center_id` FOREIGN KEY (`data_center_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_networks__related` FOREIGN KEY(`related`) REFERENCES `networks`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_networks__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`),
  CONSTRAINT `fk_networks__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`),
  CONSTRAINT `fk_networks__vpc_id` FOREIGN KEY(`vpc_id`) REFERENCES `vpc`(`id`),
  CONSTRAINT `uc_networks__uuid` UNIQUE (`uuid`),
  INDEX `i_networks__removed`(`removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`account_network_ref` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `account_id` bigint unsigned NOT NULL COMMENT 'account id',
  `network_id` bigint unsigned NOT NULL COMMENT 'network id',
  `is_owner` smallint(1) NOT NULL COMMENT 'is the owner of the network',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_account_network_ref__account_id` FOREIGN KEY (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_account_network_ref__networks_id` FOREIGN KEY (`network_id`) REFERENCES `networks`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`nics` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(40),
  `instance_id` bigint unsigned COMMENT 'vm instance id',
  `mac_address` varchar(17) COMMENT 'mac address',
  `ip4_address` char(40) COMMENT 'ip4 address',
  `netmask` varchar(15) COMMENT 'netmask for ip4 address',
  `gateway` varchar(15) COMMENT 'gateway',
  `ip_type` varchar(32) COMMENT 'type of ip',
  `broadcast_uri` varchar(255) COMMENT 'broadcast uri',
  `network_id` bigint unsigned NOT NULL COMMENT 'network configuration id',
  `mode` varchar(32) COMMENT 'mode of getting ip address',
  `state` varchar(32) NOT NULL COMMENT 'state of the creation',
  `strategy` varchar(32) NOT NULL COMMENT 'reservation strategy',
  `reserver_name` varchar(255) COMMENT 'Name of the component that reserved the ip address',
  `reservation_id` varchar(64) COMMENT 'id for the reservation',
  `device_id` int(10) COMMENT 'device id for the network when plugged into the virtual machine',
  `update_time` timestamp NOT NULL COMMENT 'time the state was changed',
  `isolation_uri` varchar(255) COMMENT 'id for isolation',
  `ip6_address` char(40) COMMENT 'ip6 address',
  `default_nic` tinyint NOT NULL COMMENT 'None',
  `vm_type` varchar(32) COMMENT 'type of vm: System or User vm',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_nics__instance_id` FOREIGN KEY `fk_nics__instance_id`(`instance_id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_nics__networks_id` FOREIGN KEY `fk_nics__networks_id`(`network_id`) REFERENCES `networks`(`id`),
  CONSTRAINT `uc_nics__uuid` UNIQUE (`uuid`),
  INDEX `i_nics__removed`(`removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`network_offerings` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT COMMENT 'id',
  `name` varchar(64) COMMENT 'name of the network offering',
  `uuid` varchar(40),
  `unique_name` varchar(64) UNIQUE COMMENT 'unique name of the network offering',
  `display_text` varchar(255) NOT NULL COMMENT 'text to display to users',
  `nw_rate` smallint unsigned COMMENT 'network rate throttle mbits/s',
  `mc_rate` smallint unsigned COMMENT 'mcast rate throttle mbits/s',
  `traffic_type` varchar(32) NOT NULL COMMENT 'traffic type carried on this network',
  `tags` varchar(4096) COMMENT 'tags supported by this offering',
  `system_only` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is this network offering for system use only',
  `specify_vlan` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Should the user specify vlan',
  `service_offering_id` bigint unsigned COMMENT 'service offering id that virtual router is tied to',
  `conserve_mode` int(1) unsigned NOT NULL DEFAULT 1 COMMENT 'Is this network offering is IP conserve mode enabled',
  `created` datetime NOT NULL COMMENT 'time the entry was created',
  `removed` datetime DEFAULT NULL COMMENT 'time the entry was removed',
  `default` int(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 if network offering is default',
  `availability` varchar(255) NOT NULL COMMENT 'availability of the network',
  `dedicated_lb_service` int(1) unsigned NOT NULL DEFAULT 1 COMMENT 'true if the network offering provides a dedicated load balancer for each network',
  `shared_source_nat_service` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'true if the network offering provides the shared source nat service',
  `sort_key` int(32) NOT NULL default 0 COMMENT 'sort key used for customising sort method',
  `redundant_router_service` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'true if the network offering provides the redundant router service',
  `state` char(32) COMMENT 'state of the network offering that has Disabled value by default',
  `guest_type` char(32) COMMENT 'type of guest network that can be shared or isolated',
  `elastic_ip_service` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'true if the network offering provides elastic ip service',
  `elastic_lb_service` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'true if the network offering provides elastic lb service',
  `specify_ip_ranges` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'true if the network offering provides an ability to define ip ranges',
  PRIMARY KEY (`id`),
  INDEX `i_network_offerings__system_only`(`system_only`),
  INDEX `i_network_offerings__removed`(`removed`),
  CONSTRAINT `uc_network_offerings__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`cluster` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT COMMENT 'id',
  `name` varchar(255) COMMENT 'name for the cluster',
  `uuid` varchar(40) COMMENT 'uuid is different with following guid, while the later one is generated by hypervisor resource',
  `guid` varchar(255) UNIQUE DEFAULT NULL COMMENT 'guid for the cluster',
  `pod_id` bigint unsigned NOT NULL COMMENT 'pod id',
  `data_center_id` bigint unsigned NOT NULL COMMENT 'data center id',
  `hypervisor_type` varchar(32),
  `cluster_type` varchar(64) DEFAULT 'CloudManaged',
  `allocation_state` varchar(32) NOT NULL DEFAULT 'Enabled' COMMENT 'Is this cluster enabled for allocation for new resources',
  `managed_state` varchar(32) NOT NULL DEFAULT 'Managed' COMMENT 'Is this cluster managed by cloudstack',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_cluster__data_center_id` FOREIGN KEY (`data_center_id`) REFERENCES `cloud`.`data_center`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_cluster__pod_id` FOREIGN KEY (`pod_id`) REFERENCES `cloud`.`host_pod_ref`(`id`),
  UNIQUE `i_cluster__pod_id__name`(`pod_id`, `name`),
  INDEX `i_cluster__allocation_state`(`allocation_state`),
  INDEX `i_cluster__removed`(`removed`),
  CONSTRAINT `uc_cluster__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`cluster_details` (
  `id` bigint unsigned NOT NULL auto_increment,
  `cluster_id` bigint unsigned NOT NULL COMMENT 'cluster id',
  `name` varchar(255) NOT NULL,
  `value` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_cluster_details__cluster_id` FOREIGN KEY (`cluster_id`) REFERENCES `cluster`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_host_upgrade` (
  `host_id` bigint unsigned NOT NULL UNIQUE COMMENT 'host id',
  `version` varchar(20) NOT NULL COMMENT 'version',
  `state` varchar(20) NOT NULL COMMENT 'state',
  PRIMARY KEY (`host_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_lock` (
  `key` varchar(128) NOT NULL UNIQUE COMMENT 'primary key of the table',
  `mac` varchar(17) NOT NULL COMMENT 'management server id of the server that holds this lock',
  `ip` char(40) NOT NULL COMMENT 'name of the thread that holds this lock',
  `thread` varchar(255) NOT NULL COMMENT 'Thread id that acquired this lock',
  `acquired_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Time acquired',
  `waiters` int NOT NULL DEFAULT 0 COMMENT 'How many have the thread acquired this lock (reentrant)',
  PRIMARY KEY (`key`),
  INDEX `i_op_lock__mac_ip_thread`(`mac`, `ip`, `thread`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`configuration` (
  `category` varchar(255) NOT NULL DEFAULT 'Advanced',
  `instance` varchar(255) NOT NULL,
  `component` varchar(255) NOT NULL DEFAULT 'management-server',
  `name` varchar(255) NOT NULL,
  `value` varchar(4095),
  `description` varchar(1024),
  PRIMARY KEY (`name`),
  INDEX `i_configuration__instance`(`instance`),
  INDEX `i_configuration__name`(`name`),
  INDEX `i_configuration__category`(`category`),
  INDEX `i_configuration__component`(`component`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_ha_work` (
  `id` bigint unsigned UNIQUE NOT NULL AUTO_INCREMENT COMMENT 'id',
  `instance_id` bigint unsigned NOT NULL COMMENT 'vm instance that needs to be ha.',
  `type` varchar(32) NOT NULL COMMENT 'type of work',
  `vm_type` varchar(32) NOT NULL COMMENT 'VM type',
  `state` varchar(32) NOT NULL COMMENT 'state of the vm instance when this happened.',
  `mgmt_server_id` bigint unsigned COMMENT 'management server that has taken up the work of doing ha',
  `host_id` bigint unsigned COMMENT 'host that the vm is suppose to be on',
  `created` datetime NOT NULL COMMENT 'time the entry was requested',
  `tried` int(10) unsigned COMMENT '# of times tried',
  `taken` datetime COMMENT 'time it was taken by the management server',
  `step` varchar(32) NOT NULL COMMENT 'Step in the work',
  `time_to_try` bigint COMMENT 'time to try do this work',
  `updated` bigint unsigned NOT NULL COMMENT 'time the VM state was updated when it was stored into work queue',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_op_ha_work__instance_id` FOREIGN KEY `fk_op_ha_work__instance_id` (`instance_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE,
  INDEX `i_op_ha_work__instance_id`(`instance_id`),
  CONSTRAINT `fk_op_ha_work__host_id` FOREIGN KEY `fk_op_ha_work__host_id` (`host_id`) REFERENCES `host` (`id`),
  INDEX `i_op_ha_work__host_id`(`host_id`),
  INDEX `i_op_ha_work__step`(`step`),
  INDEX `i_op_ha_work__type`(`type`),
  CONSTRAINT `fk_op_ha_work__mgmt_server_id` FOREIGN KEY `fk_op_ha_work__mgmt_server_id`(`mgmt_server_id`) REFERENCES `mshost`(`msid`),
  INDEX `i_op_ha_work__mgmt_server_id`(`mgmt_server_id`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`sequence` (
  `name` varchar(64) UNIQUE NOT NULL COMMENT 'name of the sequence',
  `value` bigint unsigned NOT NULL COMMENT 'sequence value',
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT INTO `cloud`.`sequence` (name, value) VALUES ('vm_instance_seq', 1);
INSERT INTO `cloud`.`sequence` (name, value) VALUES ('vm_template_seq', 200);
INSERT INTO `cloud`.`sequence` (name, value) VALUES ('public_mac_address_seq', 1);
INSERT INTO `cloud`.`sequence` (name, value) VALUES ('private_mac_address_seq', 1);
INSERT INTO `cloud`.`sequence` (name, value) VALUES ('storage_pool_seq', 200);
INSERT INTO `cloud`.`sequence` (name, value) VALUES ('volume_seq', 1);
INSERT INTO `cloud`.`sequence` (name, value) VALUES ('networks_seq', 200);
INSERT INTO `cloud`.`sequence` (name, value) VALUES ('checkpoint_seq', 1);
INSERT INTO `cloud`.`sequence` (name, value) VALUES ('physical_networks_seq', 200);

CREATE TABLE `cloud`.`volumes` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'Primary Key',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner.  foreign key to account table',
  `domain_id` bigint unsigned NOT NULL COMMENT 'the domain that the owner belongs to',
  `pool_id` bigint unsigned  COMMENT 'pool it belongs to. foreign key to storage_pool table',
  `last_pool_id` bigint unsigned  COMMENT 'last pool it belongs to.',
  `instance_id` bigint unsigned NULL COMMENT 'vm instance it belongs to. foreign key to vm_instance table',
  `device_id` bigint unsigned NULL COMMENT 'which device inside vm instance it is ',
  `name` varchar(255) COMMENT 'A user specified name for the volume',
  `uuid` varchar(40),
  `size` bigint unsigned NOT NULL COMMENT 'total size',
  `folder` varchar(255)  COMMENT 'The folder where the volume is saved',
  `path` varchar(255) COMMENT 'Path',
  `pod_id` bigint unsigned COMMENT 'pod this volume belongs to',
  `data_center_id` bigint unsigned NOT NULL COMMENT 'data center this volume belongs to',
  `iscsi_name` varchar(255) COMMENT 'iscsi target name',
  `host_ip` char(40)  COMMENT 'host ip address for convenience',
  `volume_type` varchar(64) NOT NULL COMMENT 'root, swap or data',
  `pool_type` varchar(64) COMMENT 'type of the pool',
  `disk_offering_id` bigint unsigned NOT NULL COMMENT 'can be null for system VMs',
  `template_id` bigint unsigned COMMENT 'fk to vm_template.id',
  `first_snapshot_backup_uuid` varchar (255) COMMENT 'The first snapshot that was ever taken for this volume',
  `recreatable` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is this volume recreatable?',
  `created` datetime COMMENT 'Date Created',
  `attached` datetime COMMENT 'Date Attached',
  `updated` datetime COMMENT 'Date updated for attach/detach',
  `removed` datetime COMMENT 'Date removed.  not null if removed',
  `state` varchar(32) COMMENT 'State machine',
  `chain_info` text COMMENT 'save possible disk chain info in primary storage',
  `update_count` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'date state was updated',
  PRIMARY KEY (`id`),
  INDEX `i_volumes__removed`(`removed`),
  INDEX `i_volumes__pod_id`(`pod_id`),
  INDEX `i_volumes__data_center_id`(`data_center_id`),
  CONSTRAINT `fk_volumes__account_id` FOREIGN KEY `fk_volumes__account_id` (`account_id`) REFERENCES `account` (`id`),
  INDEX `i_volumes__account_id`(`account_id`),
  CONSTRAINT `fk_volumes__pool_id` FOREIGN KEY `fk_volumes__pool_id` (`pool_id`) REFERENCES `storage_pool` (`id`),
  INDEX `i_volumes__pool_id`(`pool_id`),
  INDEX `i_volumes__last_pool_id`(`last_pool_id`),
  CONSTRAINT `fk_volumes__instance_id` FOREIGN KEY `fk_volumes__instance_id` (`instance_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE,
  INDEX `i_volumes__instance_id`(`instance_id`),
  INDEX `i_volumes__state`(`state`),
  INDEX `i_volumes__update_count`(`update_count`),
  CONSTRAINT `uc_volumes__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`snapshots` (
  `id` bigint unsigned UNIQUE NOT NULL AUTO_INCREMENT COMMENT 'Primary Key',
  `data_center_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL COMMENT 'owner.  foreign key to account table',
  `domain_id` bigint unsigned NOT NULL COMMENT 'the domain that the owner belongs to',
  `volume_id` bigint unsigned NOT NULL COMMENT 'volume it belongs to. foreign key to volume table',
  `disk_offering_id` bigint unsigned NOT NULL COMMENT 'from original volume',
  `status` varchar(32) COMMENT 'snapshot creation status',
  `path` varchar(255) COMMENT 'Path',
  `name` varchar(255) NOT NULL COMMENT 'snapshot name',
  `uuid` varchar(40),
  `snapshot_type` int(4) NOT NULL COMMENT 'type of snapshot, e.g. manual, recurring',
  `type_description` varchar(25) COMMENT 'description of the type of snapshot, e.g. manual, recurring',
  `size` bigint unsigned NOT NULL COMMENT 'original disk size of snapshot',
  `created` datetime COMMENT 'Date Created',
  `removed` datetime COMMENT 'Date removed.  not null if removed',
  `backup_snap_id` varchar(255) COMMENT 'Back up uuid of the snapshot',
  `swift_id` bigint unsigned COMMENT 'which swift',
  `sechost_id` bigint unsigned COMMENT 'secondary storage host id',
  `prev_snap_id` bigint unsigned COMMENT 'Id of the most recent snapshot',
  `hypervisor_type` varchar(32) NOT NULL COMMENT 'hypervisor that the snapshot was taken under',
  `version` varchar(32) COMMENT 'snapshot version',
  PRIMARY KEY (`id`),
  CONSTRAINT `uc_snapshots__uuid` UNIQUE (`uuid`),
  CONSTRAINT `fk_snapshots__account_id` FOREIGN KEY(`account_id`) REFERENCES `account` (`id`),
  INDEX `i_snapshots__account_id`(`account_id`),
  INDEX `i_snapshots__volume_id`(`volume_id`),
  INDEX `i_snapshots__name`(`name`),
  INDEX `i_snapshots__snapshot_type`(`snapshot_type`),
  INDEX `i_snapshots__prev_snap_id`(`prev_snap_id`),
  INDEX `i_snapshots__removed`(`removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`vlan` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT,
  `uuid` varchar(40),
  `vlan_id` varchar(255),
  `vlan_gateway` varchar(255),
  `vlan_netmask` varchar(255),
  `description` varchar(255),
  `vlan_type` varchar(255),
  `data_center_id` bigint unsigned NOT NULL,
  `network_id` bigint unsigned NOT NULL COMMENT 'id of corresponding network offering',
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'physical network id that this configuration is based on',
  PRIMARY KEY (`id`),
  #CONSTRAINT `fk_vlan__network_id` FOREIGN KEY (`network_id`) REFERENCES `networks`(`id`),
  CONSTRAINT `fk_vlan__data_center_id` FOREIGN KEY (`data_center_id`) REFERENCES `data_center`(`id`),
  CONSTRAINT `uc_vlan__uuid` UNIQUE (`uuid`),
  CONSTRAINT `fk_vlan__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`pod_vlan_map` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT,
  `pod_id` bigint unsigned NOT NULL COMMENT 'pod id. foreign key to pod table',
  `vlan_db_id` bigint unsigned NOT NULL COMMENT 'database id of vlan. foreign key to vlan table',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_pod_vlan_map__pod_id` FOREIGN KEY (`pod_id`) REFERENCES `host_pod_ref` (`id`) ON DELETE CASCADE,
  INDEX `i_pod_vlan_map__pod_id`(`pod_id`),
  CONSTRAINT `fk_pod_vlan_map__vlan_id` FOREIGN KEY (`vlan_db_id`) REFERENCES `vlan` (`id`) ON DELETE CASCADE,
  INDEX `i_pod_vlan_map__vlan_id`(`vlan_db_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`account_vlan_map` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT,
  `account_id` bigint unsigned NOT NULL COMMENT 'account id. foreign key to account table',
  `vlan_db_id` bigint unsigned NOT NULL COMMENT 'database id of vlan. foreign key to vlan table',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_account_vlan_map__account_id` FOREIGN KEY (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  INDEX `i_account_vlan_map__account_id`(`account_id`),
  CONSTRAINT `fk_account_vlan_map__vlan_id` FOREIGN KEY (`vlan_db_id`) REFERENCES `vlan` (`id`) ON DELETE CASCADE,
  INDEX `i_account_vlan_map__vlan_id`(`vlan_db_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`data_center` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT,
  `name` varchar(255),
  `uuid` varchar(40),
  `description` varchar(255),
  `dns1` varchar(255) NOT NULL,
  `dns2` varchar(255),
  `internal_dns1` varchar(255) NOT NULL,
  `internal_dns2` varchar(255),
  `gateway` varchar(15),
  `netmask` varchar(15),
  `router_mac_address` varchar(17) NOT NULL DEFAULT '02:00:00:00:00:01' COMMENT 'mac address for the router within the domain',
  `mac_address` bigint unsigned NOT NULL DEFAULT '1' COMMENT 'Next available mac address for the ethernet card interacting with public internet',
  `guest_network_cidr` varchar(18),
  `domain` varchar(100) COMMENT 'Network domain name of the Vms of the zone',
  `domain_id` bigint unsigned COMMENT 'domain id for the parent domain to this zone (null signifies public zone)',
  `networktype` varchar(255) NOT NULL DEFAULT 'Basic' COMMENT 'Network type of the zone',
  `dns_provider` char(64) DEFAULT 'VirtualRouter',
  `gateway_provider` char(64) DEFAULT 'VirtualRouter',
  `firewall_provider` char(64) DEFAULT 'VirtualRouter',
  `dhcp_provider` char(64) DEFAULT 'VirtualRouter',
  `lb_provider` char(64) DEFAULT 'VirtualRouter',
  `vpn_provider` char(64) DEFAULT 'VirtualRouter',
  `userdata_provider` char(64) DEFAULT 'VirtualRouter',
  `allocation_state` varchar(32) NOT NULL DEFAULT 'Enabled' COMMENT 'Is this data center enabled for allocation for new resources',
  `zone_token` varchar(255),
  `is_security_group_enabled` tinyint NOT NULL DEFAULT 0 COMMENT '1: enabled, 0: not',
  `is_local_storage_enabled` tinyint NOT NULL DEFAULT 0 COMMENT 'Is local storage offering enabled for this data center; 1: enabled, 0: not',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_data_center__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain`(`id`),
  INDEX `i_data_center__domain_id`(`domain_id`),
  INDEX `i_data_center__allocation_state`(`allocation_state`),
  INDEX `i_data_center__zone_token`(`zone_token`),
  INDEX `i_data_center__removed`(`removed`),
  CONSTRAINT `uc_data_center__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_dc_ip_address_alloc` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `ip_address` char(40) NOT NULL COMMENT 'ip address',
  `data_center_id` bigint unsigned NOT NULL COMMENT 'data center it belongs to',
  `pod_id` bigint unsigned NOT NULL COMMENT 'pod it belongs to',
  `nic_id` bigint unsigned NULL COMMENT 'nic id',
  `reservation_id` char(40) NULL COMMENT 'reservation id',
  `taken` datetime COMMENT 'Date taken',
  `mac_address` bigint unsigned NOT NULL COMMENT 'mac address for management ips',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_op_dc_ip_address_alloc__data_center_id` FOREIGN KEY (`data_center_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE,
  INDEX `i_op_dc_ip_address_alloc__pod_id__data_center_id__taken` (`pod_id`, `data_center_id`, `taken`, `nic_id`),
  UNIQUE `i_op_dc_ip_address_alloc__ip_address__data_center_id`(`ip_address`, `data_center_id`),
  CONSTRAINT `fk_op_dc_ip_address_alloc__pod_id` FOREIGN KEY (`pod_id`) REFERENCES `host_pod_ref` (`id`) ON DELETE CASCADE,
  INDEX `i_op_dc_ip_address_alloc__pod_id`(`pod_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_dc_link_local_ip_address_alloc` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `ip_address` char(40) NOT NULL COMMENT 'ip address',
  `data_center_id` bigint unsigned NOT NULL COMMENT 'data center it belongs to',
  `pod_id` bigint unsigned NOT NULL COMMENT 'pod it belongs to',
  `nic_id` bigint unsigned NULL COMMENT 'instance id',
  `reservation_id` char(40) NULL COMMENT 'reservation id used to reserve this network',
  `taken` datetime COMMENT 'Date taken',
  PRIMARY KEY (`id`),
  INDEX `i_op_dc_link_local_ip_address_alloc__pod_id`(`pod_id`),
  INDEX `i_op_dc_link_local_ip_address_alloc__data_center_id`(`data_center_id`),
  INDEX `i_op_dc_link_local_ip_address_alloc__nic_id_reservation_id`(`nic_id`,`reservation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dc_storage_network_ip_range` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT,
  `uuid` varchar(40),
  `start_ip` char(40) NOT NULL COMMENT 'start ip address',
  `end_ip` char(40) NOT NULL COMMENT 'end ip address',
  `gateway` varchar(15) NOT NULL COMMENT 'gateway ip address',
  `vlan` int unsigned DEFAULT NULL COMMENT 'vlan the storage network on',
  `netmask` varchar(15) NOT NULL COMMENT 'netmask for storage network',
  `data_center_id` bigint unsigned NOT NULL,
  `pod_id` bigint unsigned NOT NULL COMMENT 'pod it belongs to',
  `network_id` bigint unsigned NOT NULL COMMENT 'id of corresponding network offering',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_storage_ip_range__network_id` FOREIGN KEY (`network_id`) REFERENCES `networks`(`id`),
  CONSTRAINT `fk_storage_ip_range__data_center_id` FOREIGN KEY (`data_center_id`) REFERENCES `data_center`(`id`),
  CONSTRAINT `fk_storage_ip_range__pod_id` FOREIGN KEY (`pod_id`) REFERENCES `host_pod_ref`(`id`),
  CONSTRAINT `uc_storage_ip_range__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_dc_storage_network_ip_address` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `range_id` bigint unsigned NOT NULL COMMENT 'id of ip range in dc_storage_network_ip_range',
  `ip_address` char(40) NOT NULL COMMENT 'ip address',
  `mac_address` bigint unsigned NOT NULL COMMENT 'mac address for storage ips',
  `taken` datetime COMMENT 'Date taken',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_storage_ip_address__range_id` FOREIGN KEY (`range_id`) REFERENCES `dc_storage_network_ip_range`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`host_pod_ref` (
  `id` bigint unsigned NOT NULL UNIQUE auto_increment,
  `name` varchar(255),
  `uuid` varchar(40),
  `data_center_id` bigint unsigned NOT NULL,
  `gateway` varchar(255) NOT NULL COMMENT 'gateway for the pod',
  `cidr_address` varchar(15) NOT NULL COMMENT 'CIDR address for the pod',
  `cidr_size` bigint unsigned NOT NULL COMMENT 'CIDR size for the pod',
  `description` varchar(255) COMMENT 'store private ip range in startIP-endIP format',
  `allocation_state` varchar(32) NOT NULL DEFAULT 'Enabled' COMMENT 'Is this Pod enabled for allocation for new resources',
  `external_dhcp` tinyint NOT NULL DEFAULT 0 COMMENT 'Is this Pod using external DHCP',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY  (`id`),
  UNIQUE KEY (`name`, `data_center_id`),
  INDEX `i_host_pod_ref__data_center_id`(`data_center_id`),
  INDEX `i_host_pod_ref__allocation_state`(`allocation_state`),
  INDEX `i_host_pod_ref__removed`(`removed`),
  CONSTRAINT `uc_host_pod_ref__uuid` UNIQUE (`uuid`)

) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_dc_vnet_alloc` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary id',
    `vnet` varchar(18) NOT NULL COMMENT 'vnet',
	`physical_network_id` bigint unsigned NOT NULL COMMENT 'physical network the vnet belongs to',
    `data_center_id` bigint unsigned NOT NULL COMMENT 'data center the vnet belongs to',
    `reservation_id` char(40) NULL COMMENT 'reservation id',
    `account_id` bigint unsigned NULL COMMENT 'account the vnet belongs to right now',
    `taken` datetime COMMENT 'Date taken',
    PRIMARY KEY (`id`),
    UNIQUE `i_op_dc_vnet_alloc__vnet__data_center_id__account_id`(`vnet`, `data_center_id`, `account_id`),
    INDEX `i_op_dc_vnet_alloc__dc_taken`(`data_center_id`, `taken`),
    UNIQUE `i_op_dc_vnet_alloc__vnet__data_center_id`(`vnet`, `data_center_id`),
	CONSTRAINT `fk_op_dc_vnet_alloc__data_center_id` FOREIGN KEY (`data_center_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE,
	CONSTRAINT `fk_op_dc_vnet_alloc__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`firewall_rules` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40),
  `ip_address_id` bigint unsigned COMMENT 'id of the corresponding ip address',
  `start_port` int(10) COMMENT 'starting port of a port range',
  `end_port` int(10) COMMENT 'end port of a port range',
  `state` char(32) NOT NULL COMMENT 'current state of this rule',
  `protocol` char(16) NOT NULL default 'TCP' COMMENT 'protocol to open these ports for',
  `purpose` char(32) NOT NULL COMMENT 'why are these ports opened?',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner id',
  `domain_id` bigint unsigned NOT NULL COMMENT 'domain id',
  `network_id` bigint unsigned NOT NULL COMMENT 'network id',
  `xid` char(40) NOT NULL COMMENT 'external id',
  `created` datetime COMMENT 'Date created',
  `icmp_code` int(10) COMMENT 'The ICMP code (if protocol=ICMP). A value of -1 means all codes for the given ICMP type.',
  `icmp_type` int(10) COMMENT 'The ICMP type (if protocol=ICMP). A value of -1 means all types.',
  `related` bigint unsigned COMMENT 'related to what other firewall rule',
  `type` varchar(10) NOT NULL DEFAULT 'USER',
  `vpc_id` bigint unsigned COMMENT 'vpc the firewall rule is associated with',
  `traffic_type` char(32) COMMENT 'the traffic type of the rule, can be Ingress or Egress',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_firewall_rules__ip_address_id` FOREIGN KEY(`ip_address_id`) REFERENCES `user_ip_address`(`id`),
  CONSTRAINT `fk_firewall_rules__network_id` FOREIGN KEY(`network_id`) REFERENCES `networks`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_firewall_rules__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_firewall_rules__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_firewall_rules__related` FOREIGN KEY(`related`) REFERENCES `firewall_rules`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_firewall_rules__vpc_id` FOREIGN KEY (`vpc_id`) REFERENCES `vpc`(`id`) ON DELETE CASCADE,
  INDEX `i_firewall_rules__purpose`(`purpose`),
  CONSTRAINT `uc_firewall_rules__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`firewall_rules_cidrs` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `firewall_rule_id` bigint(20) unsigned NOT NULL COMMENT 'firewall rule id',
  `source_cidr` varchar(18) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_firewall_cidrs_firewall_rules` (`firewall_rule_id`),
  UNIQUE INDEX  `unique_rule_cidrs` (`firewall_rule_id`, `source_cidr`),
  CONSTRAINT `fk_firewall_cidrs_firewall_rules` FOREIGN KEY (`firewall_rule_id`) REFERENCES `firewall_rules` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`load_balancing_rules` (
  `id` bigint unsigned NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(4096) NULL COMMENT 'description',
  `default_port_start` int(10) NOT NULL COMMENT 'default private port range start',
  `default_port_end` int(10) NOT NULL COMMENT 'default destination port range end',
  `algorithm` varchar(255) NOT NULL,
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_load_balancing_rules__id` FOREIGN KEY(`id`) REFERENCES `firewall_rules`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`load_balancer_vm_map` (
  `id` bigint unsigned NOT NULL auto_increment,
  `load_balancer_id` bigint unsigned NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  `revoke` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 is when rule is set for Revoke',
  PRIMARY KEY  (`id`),
  UNIQUE KEY (`load_balancer_id`, `instance_id`),
  CONSTRAINT `fk_load_balancer_vm_map__load_balancer_id` FOREIGN KEY(`load_balancer_id`) REFERENCES `load_balancing_rules`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_load_balancer_vm_map__instance_id` FOREIGN KEY(`instance_id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`load_balancer_stickiness_policies` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `load_balancer_id` bigint unsigned NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(4096) NULL COMMENT 'description',
  `method_name` varchar(255) NOT NULL,
  `params` varchar(4096) NOT NULL,
  `revoke` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 is when rule is set for Revoke',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_load_balancer_stickiness_policies__load_balancer_id` FOREIGN KEY(`load_balancer_id`) REFERENCES `load_balancing_rules`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`inline_load_balancer_nic_map` (
  `id` bigint unsigned NOT NULL auto_increment,
  `load_balancer_id` bigint unsigned NOT NULL,
  `public_ip_address` char(40) NOT NULL,
  `nic_id` bigint unsigned NULL COMMENT 'nic id',
  PRIMARY KEY  (`id`),
  UNIQUE KEY (`nic_id`),
  CONSTRAINT `fk_inline_load_balancer_nic_map__load_balancer_id` FOREIGN KEY(`load_balancer_id`) REFERENCES `load_balancing_rules`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_inline_load_balancer_nic_map__nic_id` FOREIGN KEY(`nic_id`) REFERENCES `nics`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`port_forwarding_rules` (
  `id` bigint unsigned NOT NULL COMMENT 'id',
  `instance_id` bigint unsigned NOT NULL COMMENT 'vm instance id',
  `dest_ip_address` char(40) NOT NULL COMMENT 'id_address',
  `dest_port_start` int(10) NOT NULL COMMENT 'starting port of the port range to map to',
  `dest_port_end` int(10) NOT NULL COMMENT 'end port of the port range to map to',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_port_forwarding_rules__id` FOREIGN KEY(`id`) REFERENCES `firewall_rules`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_port_forwarding_rules__instance_id` FOREIGN KEY `fk_port_forwarding_rules__instance_id` (`instance_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`host` (
  `id` bigint unsigned NOT NULL auto_increment,
  `name` varchar(255) NOT NULL,
  `uuid` varchar(40) COMMENT 'this uuid is different with guid below, the later one is used by hypervisor resource',
  `status` varchar(32) NOT NULL,
  `type` varchar(32) NOT NULL,
  `private_ip_address` char(40) NOT NULL,
  `private_netmask` varchar(15),
  `private_mac_address` varchar(17),
  `storage_ip_address` char(40),
  `storage_netmask` varchar(15),
  `storage_mac_address` varchar(17),
  `storage_ip_address_2` char(40),
  `storage_mac_address_2` varchar(17),
  `storage_netmask_2` varchar(15),
  `cluster_id` bigint unsigned COMMENT 'foreign key to cluster',
  `public_ip_address` char(40),
  `public_netmask` varchar(15),
  `public_mac_address` varchar(17),
  `proxy_port` int(10) unsigned,
  `data_center_id` bigint unsigned NOT NULL,
  `pod_id` bigint unsigned,
  `cpus` int(10) unsigned,
  `speed` int(10) unsigned,
  `url` varchar(255) COMMENT 'iqn for the servers',
  `fs_type` varchar(32),
  `hypervisor_type` varchar(32) COMMENT 'hypervisor type, can be NONE for storage',
  `hypervisor_version` varchar(32) COMMENT 'hypervisor version',
  `ram` bigint unsigned,
  `resource` varchar(255) DEFAULT NULL COMMENT 'If it is a local resource, this is the class name',
  `version` varchar(40) NOT NULL,
  `parent` varchar(255) COMMENT 'parent path for the storage server',
  `total_size` bigint unsigned COMMENT 'TotalSize',
  `capabilities` varchar(255) COMMENT 'host capabilities in comma separated list',
  `guid` varchar(255) UNIQUE,
  `available` int(1) unsigned NOT NULL DEFAULT 1 COMMENT 'Is this host ready for more resources?',
  `setup` int(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is this host already setup?',
  `dom0_memory` bigint unsigned NOT NULL COMMENT 'memory used by dom0 for computing and routing servers',
  `last_ping` int(10) unsigned NOT NULL COMMENT 'time in seconds from the start of machine of the last ping',
  `mgmt_server_id` bigint unsigned COMMENT 'ManagementServer this host is connected to.',
  `disconnected` datetime COMMENT 'Time this was disconnected',
  `created` datetime COMMENT 'date the host first signed on',
  `removed` datetime COMMENT 'date removed if not null',
  `update_count` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'atomic increase count making status update operation atomical',
  `resource_state` varchar(32) NOT NULL DEFAULT 'Enabled' COMMENT 'Is this host enabled for allocation for new resources',
  PRIMARY KEY  (`id`),
  INDEX `i_host__removed`(`removed`),
  INDEX `i_host__last_ping`(`last_ping`),
  INDEX `i_host__status`(`status`),
  INDEX `i_host__data_center_id`(`data_center_id`),
  CONSTRAINT `fk_host__pod_id` FOREIGN KEY (`pod_id`) REFERENCES `host_pod_ref` (`id`) ON DELETE CASCADE,
  INDEX `i_host__pod_id`(`pod_id`),
  CONSTRAINT `fk_host__cluster_id` FOREIGN KEY (`cluster_id`) REFERENCES `cloud`.`cluster`(`id`),
  CONSTRAINT `uc_host__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_host` (
  `id` bigint unsigned NOT NULL UNIQUE COMMENT 'host id',
  `sequence` bigint unsigned DEFAULT 1 NOT NULL COMMENT 'sequence for the host communication',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_op_host__id` FOREIGN KEY (`id`) REFERENCES `host`(`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`account_details` (
  `id` bigint unsigned NOT NULL auto_increment,
  `account_id` bigint unsigned NOT NULL COMMENT 'account id',
  `name` varchar(255) NOT NULL,
  `value` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_account_details__account_id` FOREIGN KEY (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE
)ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`host_details` (
  `id` bigint unsigned NOT NULL auto_increment,
  `host_id` bigint unsigned NOT NULL COMMENT 'host id',
  `name` varchar(255) NOT NULL,
  `value` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_host_details__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
  CONSTRAINT UNIQUE KEY `uk_host_id_name` (`host_id`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`mshost` (
  `id` bigint unsigned NOT NULL auto_increment,
  `msid` bigint unsigned NOT NULL UNIQUE COMMENT 'management server id derived from MAC address',
  `runid` bigint NOT NULL DEFAULT 0 COMMENT 'run id, combined with msid to form a cluster session',
  `name` varchar(255),
  `state` varchar(10) NOT NULL DEFAULT 'Down',
  `version` varchar(255),
  `service_ip` char(40) NOT NULL,
  `service_port` integer NOT NULL,
  `last_update` DATETIME NULL COMMENT 'Last record update time',
  `removed` datetime COMMENT 'date removed if not null',
  `alert_count` integer NOT NULL DEFAULT 0,
  PRIMARY KEY  (`id`),
  INDEX `i_mshost__removed`(`removed`),
  INDEX `i_mshost__last_update`(`last_update`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`mshost_peer` (
  `id` bigint unsigned NOT NULL auto_increment,
  `owner_mshost` bigint unsigned NOT NULL,
  `peer_mshost` bigint unsigned NOT NULL,
  `peer_runid` bigint NOT NULL,
  `peer_state` varchar(10) NOT NULL DEFAULT 'Down',
  `last_update` DATETIME NULL COMMENT 'Last record update time',

  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_mshost_peer__owner_mshost` FOREIGN KEY (`owner_mshost`) REFERENCES `mshost`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_mshost_peer__peer_mshost` FOREIGN KEY (`peer_mshost`) REFERENCES `mshost`(`id`),
  UNIQUE `i_mshost_peer__owner_peer_runid`(`owner_mshost`, `peer_mshost`, `peer_runid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`host_tags` (
  `id` bigint unsigned NOT NULL auto_increment,
  `host_id` bigint unsigned NOT NULL COMMENT 'host id',
  `tag` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_host_tags__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`user` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `username` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `firstname` varchar(255) default NULL,
  `lastname` varchar(255) default NULL,
  `email` varchar(255) default NULL,
  `state` varchar(10) NOT NULL default 'enabled',
  `api_key` varchar(255) default NULL,
  `secret_key` varchar(255) default NULL,
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime COMMENT 'date removed',
  `timezone` varchar(30) default NULL,
  `registration_token` varchar(255) default NULL,
  `is_registered` tinyint NOT NULL DEFAULT 0 COMMENT '1: yes, 0: no',
  `incorrect_login_attempts` integer unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY  (`id`),
  INDEX `i_user__removed`(`removed`),
  INDEX `i_user__secret_key_removed`(`secret_key`, `removed`),
  UNIQUE `i_user__api_key`(`api_key`),
  CONSTRAINT `fk_user__account_id` FOREIGN KEY `fk_user__account_id` (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  INDEX `i_user__account_id`(`account_id`),
  CONSTRAINT `uc_user__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`event` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `type` varchar(32) NOT NULL,
  `state` varchar(32) NOT NULL DEFAULT 'Completed',
  `description` varchar(1024) NOT NULL,
  `user_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `created` datetime NOT NULL,
  `level` varchar(16) NOT NULL,
  `start_id` bigint unsigned NOT NULL DEFAULT 0,
  `parameters` varchar(1024) NULL,
  PRIMARY KEY  (`id`),
  INDEX `i_event__created`(`created`),
  INDEX `i_event__user_id`(`user_id`),
  INDEX `i_event__account_id` (`account_id`),
  INDEX `i_event__level_id`(`level`),
  INDEX `i_event__type_id`(`type`),
  CONSTRAINT `uc_event__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`user_ip_address` (
  `id` bigint unsigned NOT NULL UNIQUE auto_increment,
  `uuid` varchar(40),
  `account_id` bigint unsigned NULL,
  `domain_id` bigint unsigned NULL,
  `public_ip_address` char(40) NOT NULL,
  `data_center_id` bigint unsigned NOT NULL COMMENT 'zone that it belongs to',
  `source_nat` int(1) unsigned NOT NULL default '0',
  `allocated` datetime NULL COMMENT 'Date this ip was allocated to someone',
  `vlan_db_id` bigint unsigned NOT NULL,
  `one_to_one_nat` int(1) unsigned NOT NULL default '0',
  `vm_id` bigint unsigned COMMENT 'vm id the one_to_one nat ip is assigned to',
  `state` char(32) NOT NULL default 'Free' COMMENT 'state of the ip address',
  `mac_address` bigint unsigned NOT NULL COMMENT 'mac address of this ip',
  `source_network_id` bigint unsigned NOT NULL COMMENT 'network id ip belongs to',
  `network_id` bigint unsigned COMMENT 'network this public ip address is associated with',
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'physical network id that this configuration is based on',
  `is_system` int(1) unsigned NOT NULL default '0',
  `vpc_id` bigint unsigned COMMENT 'vpc the ip address is associated with',
  PRIMARY KEY (`id`),
  UNIQUE (`public_ip_address`, `source_network_id`),
  CONSTRAINT `fk_user_ip_address__source_network_id` FOREIGN KEY (`source_network_id`) REFERENCES `networks`(`id`),
  CONSTRAINT `fk_user_ip_address__network_id` FOREIGN KEY (`network_id`) REFERENCES `networks`(`id`),
  CONSTRAINT `fk_user_ip_address__account_id` FOREIGN KEY (`account_id`) REFERENCES `account`(`id`),
  CONSTRAINT `fk_user_ip_address__vm_id` FOREIGN KEY (`vm_id`) REFERENCES `vm_instance`(`id`),
  CONSTRAINT `fk_user_ip_address__vlan_db_id` FOREIGN KEY (`vlan_db_id`) REFERENCES `vlan`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_ip_address__data_center_id` FOREIGN KEY (`data_center_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_user_ip_address__uuid` UNIQUE (`uuid`),
  CONSTRAINT `fk_user_ip_address__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_ip_address__vpc_id` FOREIGN KEY (`vpc_id`) REFERENCES `vpc`(`id`) ON DELETE CASCADE,
  INDEX `i_user_ip_address__allocated`(`allocated`),
  INDEX `i_user_ip_address__source_nat`(`source_nat`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE  `cloud`.`user_statistics` (
  `id` bigint unsigned UNIQUE NOT NULL AUTO_INCREMENT,
  `data_center_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `public_ip_address` char(40),
  `device_id` bigint unsigned NOT NULL,
  `device_type` varchar(32) NOT NULL,
  `network_id` bigint unsigned,
  `net_bytes_received` bigint unsigned NOT NULL default '0',
  `net_bytes_sent` bigint unsigned NOT NULL default '0',
  `current_bytes_received` bigint unsigned NOT NULL default '0',
  `current_bytes_sent` bigint unsigned NOT NULL default '0',
  `agg_bytes_received` bigint unsigned NOT NULL default '0',
  `agg_bytes_sent` bigint unsigned NOT NULL default '0',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_user_statistics__account_id` FOREIGN KEY `fk_user_statistics__account_id` (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  INDEX `i_user_statistics__account_id`(`account_id`),
  INDEX `i_user_statistics__account_id_data_center_id`(`account_id`, `data_center_id`),
  UNIQUE KEY (`account_id`, `data_center_id`, `public_ip_address`, `device_id`, `device_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`vm_template` (
  `id` bigint unsigned NOT NULL auto_increment,
  `unique_name` varchar(255) NOT NULL,
  `name` varchar(255) NOT NULL,
  `uuid` varchar(40),
  `public` int(1) unsigned NOT NULL,
  `featured` int(1) unsigned NOT NULL,
  `type` varchar(32) NULL,
  `hvm`  int(1) unsigned NOT NULL COMMENT 'requires HVM',
  `bits` int(6) unsigned NOT NULL COMMENT '32 bit or 64 bit',
  `url` varchar(255) NULL COMMENT 'the url where the template exists externally',
  `format` varchar(32) NOT NULL COMMENT 'format for the template',
  `created` datetime NOT NULL COMMENT 'Date created',
  `removed` datetime COMMENT 'Date removed if not null',
  `account_id` bigint unsigned NOT NULL COMMENT 'id of the account that created this template',
  `checksum` varchar(255) COMMENT 'checksum for the template root disk',
  `display_text` varchar(4096) NULL COMMENT 'Description text set by the admin for display purpose only',
  `enable_password` int(1) unsigned NOT NULL default 1 COMMENT 'true if this template supports password reset',
  `enable_sshkey` int(1) unsigned NOT NULL default 0 COMMENT 'true if this template supports sshkey reset',
  `guest_os_id` bigint unsigned NOT NULL COMMENT 'the OS of the template',
  `bootable` int(1) unsigned NOT NULL default 1 COMMENT 'true if this template represents a bootable ISO',
  `prepopulate` int(1) unsigned NOT NULL default 0 COMMENT 'prepopulate this template to primary storage',
  `cross_zones` int(1) unsigned NOT NULL default 0 COMMENT 'Make this template available in all zones',
  `extractable` int(1) unsigned NOT NULL default 0 COMMENT 'Is this template extractable',
  `hypervisor_type` varchar(32) COMMENT 'hypervisor that the template belongs to',
  `source_template_id` bigint unsigned COMMENT 'Id of the original template, if this template is created from snapshot',
  `template_tag` varchar(255) COMMENT 'template tag',
  `sort_key` int(32) NOT NULL default 0 COMMENT 'sort key used for customising sort method',
  PRIMARY KEY  (`id`),
  INDEX `i_vm_template__removed`(`removed`),
  INDEX `i_vm_template__public`(`public`),
  CONSTRAINT `uc_vm_template__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`vm_template_details` (
  `id` bigint unsigned NOT NULL auto_increment,
  `template_id` bigint unsigned NOT NULL COMMENT 'template id',
  `name` varchar(255) NOT NULL,
  `value` varchar(1024) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_vm_template_details__template_id` FOREIGN KEY `fk_vm_template_details__template_id`(`template_id`) REFERENCES `vm_template`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`vm_instance` (
  `id` bigint unsigned UNIQUE NOT NULL,
  `name` varchar(255) NOT NULL,
  `uuid` varchar(40),
  `instance_name` varchar(255) NOT NULL COMMENT 'name of the vm instance running on the hosts',
  `state` varchar(32) NOT NULL,
  `vm_template_id` bigint unsigned,
  `guest_os_id` bigint unsigned NOT NULL,
  `private_mac_address` varchar(17),
  `private_ip_address` char(40),
  `pod_id` bigint unsigned,
  `data_center_id` bigint unsigned NOT NULL COMMENT 'Data Center the instance belongs to',
  `host_id` bigint unsigned,
  `last_host_id` bigint unsigned COMMENT 'tentative host for first run or last host that it has been running on',
  `proxy_id` bigint unsigned NULL COMMENT 'console proxy allocated in previous session',
  `proxy_assign_time` DATETIME NULL COMMENT 'time when console proxy was assigned',
  `vnc_password` varchar(255) NOT NULL COMMENT 'vnc password',
  `ha_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'Should HA be enabled for this VM',
  `limit_cpu_use` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Limit the cpu usage to service offering',
  `update_count` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'date state was updated',
  `update_time` datetime COMMENT 'date the destroy was requested',
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime COMMENT 'date removed if not null',
  `type` varchar(32) NOT NULL COMMENT 'type of vm it is',
  `vm_type` varchar(32) NOT NULL COMMENT 'vm type',
  `account_id` bigint unsigned NOT NULL COMMENT 'user id of owner',
  `domain_id` bigint unsigned NOT NULL,
  `service_offering_id` bigint unsigned NOT NULL COMMENT 'service offering id',
  `reservation_id` char(40) COMMENT 'reservation id',
  `hypervisor_type` char(32) COMMENT 'hypervisor type',
  PRIMARY KEY  (`id`),
  INDEX `i_vm_instance__removed`(`removed`),
  INDEX `i_vm_instance__type`(`type`),
  INDEX `i_vm_instance__pod_id`(`pod_id`),
  INDEX `i_vm_instance__update_time`(`update_time`),
  INDEX `i_vm_instance__update_count`(`update_count`),
  INDEX `i_vm_instance__state`(`state`),
  INDEX `i_vm_instance__data_center_id`(`data_center_id`),
  CONSTRAINT `fk_vm_instance__host_id` FOREIGN KEY `fk_vm_instance__host_id` (`host_id`) REFERENCES `host` (`id`),
  CONSTRAINT `fk_vm_instance__last_host_id` FOREIGN KEY `fk_vm_instance__last_host_id` (`last_host_id`) REFERENCES `host`(`id`),
  CONSTRAINT `fk_vm_instance__template_id` FOREIGN KEY `fk_vm_instance__template_id` (`vm_template_id`) REFERENCES `vm_template` (`id`),
  INDEX `i_vm_instance__template_id`(`vm_template_id`),
  CONSTRAINT `fk_vm_instance__account_id` FOREIGN KEY `fk_vm_instance__account_id` (`account_id`) REFERENCES `account` (`id`),
  CONSTRAINT `fk_vm_instance__service_offering_id` FOREIGN KEY `fk_vm_instance__service_offering_id` (`service_offering_id`) REFERENCES `service_offering` (`id`),
  CONSTRAINT `uc_vm_instance_uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`vm_guest_network_state` (
  `id` bigint unsigned NOT NULL auto_increment,
  `vm_id` bigint unsigned NOT NULL COMMENT 'VM instance id',
  `schema_version` smallint unsigned NOT NULL DEFAULT 1 COMMENT 'Guest network payload schema version',
  `status` varchar(32) NOT NULL COMMENT 'Current collection status',
    `qga_version` varchar(64) COMMENT 'Last observed QEMU guest agent version',
    `collector_build_id` varchar(128) COMMENT 'Collector build or manifest identifier',
    `collector_host_id` bigint unsigned COMMENT 'Host that produced the snapshot',
    `capability_hash` char(64) COMMENT 'SHA-256 of enabled QGA capabilities',
    `guest_tools_version` varchar(64) COMMENT 'ABLESTACK guest tools version',
    `qga_policy_mode` varchar(16) COMMENT 'Observed QGA RPC policy mode',
    `readiness_status` varchar(32) COMMENT 'Guest tools readiness status',
    `readiness_checked_at` datetime COMMENT 'Last readiness check time',
  `observed_at` datetime NOT NULL COMMENT 'Last collection attempt time',
  `last_success_at` datetime COMMENT 'Last successful collection time',
  `payload_hash` char(64) COMMENT 'SHA-256 of canonical payload',
  `payload` mediumtext COMMENT 'Canonical guest network state JSON',
  `error_code` varchar(64) COMMENT 'Structured collection error code',
  `error_message` varchar(255) COMMENT 'Bounded collection error message',
  `created` datetime NOT NULL COMMENT 'Creation time',
  `updated` datetime NOT NULL COMMENT 'Last update time',
  PRIMARY KEY (`id`),
  CONSTRAINT `uc_vm_guest_network_state__vm_id` UNIQUE (`vm_id`),
  CONSTRAINT `fk_vm_guest_network_state__vm_id` FOREIGN KEY `fk_vm_guest_network_state__vm_id` (`vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE,
  INDEX `i_vm_guest_network_state__status_observed_at` (`status`, `observed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`vm_guest_network_section_state` (
  `id` bigint unsigned NOT NULL auto_increment,
  `vm_id` bigint unsigned NOT NULL,
  `section` varchar(32) NOT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'NOT_COLLECTED',
  `source` varchar(64),
  `observed_at` datetime,
  `last_success_at` datetime,
  `next_due_at` datetime NOT NULL,
  `failure_count` smallint unsigned NOT NULL DEFAULT 0,
  `error_code` varchar(64),
  `error_message` varchar(255),
  `payload_hash` char(64),
  `payload` mediumtext,
  `lease_owner` varchar(128),
  `lease_until` datetime,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uc_vm_guest_network_section__vm_section` (`vm_id`, `section`),
  KEY `i_vm_guest_network_section__due_lease` (`next_due_at`, `lease_until`),
  KEY `i_vm_guest_network_section__vm_status` (`vm_id`, `status`),
  CONSTRAINT `fk_vm_guest_network_section__vm_id`
    FOREIGN KEY (`vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`user_vm` (
  `id` bigint unsigned UNIQUE NOT NULL,
  `iso_id` bigint unsigned,
  `display_name` varchar(255),
  `user_data` varchar(2048),
  `update_parameters` tinyint(1) NOT NULL DEFAULT 1 COMMENT 'Defines if the parameters have been updated for the vm',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_user_vm__id` FOREIGN KEY `fk_user_vm__id` (`id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- note, uer_vm_details is now used for all VMs (not just for user vms)
CREATE TABLE `cloud`.`user_vm_details` (
  `id` bigint unsigned NOT NULL auto_increment,
  `vm_id` bigint unsigned NOT NULL COMMENT 'vm id',
  `name` varchar(255) NOT NULL,
  `value` varchar(5120) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_user_vm_details__vm_id` FOREIGN KEY `fk_user_vm_details__vm_id`(`vm_id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE `cloud`.`domain_router` (
  `id` bigint unsigned UNIQUE NOT NULL COMMENT 'Primary Key',
  `element_id` bigint unsigned NOT NULL COMMENT 'correlated virtual router provider ID',
  `public_mac_address` varchar(17)   COMMENT 'mac address of the public facing network card',
  `public_ip_address` char(40)  COMMENT 'public ip address used for source net',
  `public_netmask` varchar(15)  COMMENT 'netmask used for the domR',
  `guest_netmask` varchar(15) COMMENT 'netmask used for the guest network',
  `guest_ip_address` char(40) COMMENT ' ip address in the guest network',
  `is_redundant_router` int(1) unsigned NOT NULL COMMENT 'if in redundant router mode',
  `priority` int(4) unsigned COMMENT 'priority of router in the redundant router mode',
  `is_priority_bumpup` int(1) unsigned NOT NULL COMMENT 'if the priority has been bumped up',
  `redundant_state` varchar(64) NOT NULL COMMENT 'the state of redundant virtual router',
  `stop_pending` int(1) unsigned NOT NULL COMMENT 'if this router would be stopped after we can connect to it',
  `role` varchar(64) NOT NULL COMMENT 'type of role played by this router',
  `template_version` varchar(100) COMMENT 'template version',
  `scripts_version` varchar(100) COMMENT 'scripts version',
 `vpc_id` bigint unsigned COMMENT 'correlated virtual router vpc ID',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_domain_router__id` FOREIGN KEY `fk_domain_router__id` (`id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_domain_router__element_id` FOREIGN KEY `fk_domain_router__element_id`(`element_id`) REFERENCES `virtual_router_providers`(`id`),
  CONSTRAINT `fk_domain_router__vpc_id` FOREIGN KEY `fk_domain_router__vpc_id`(`vpc_id`) REFERENCES `vpc`(`id`)
) ENGINE = InnoDB DEFAULT CHARSET=utf8 COMMENT = 'information about the domR instance';

CREATE TABLE  `cloud`.`upload` (
  `id` bigint unsigned NOT NULL auto_increment,
  `host_id` bigint unsigned NOT NULL,
  `type_id` bigint unsigned NOT NULL,
  `type` varchar(255),
  `mode` varchar(255),
  `created` DATETIME NOT NULL,
  `last_updated` DATETIME,
  `job_id` varchar(255),
  `upload_pct` int(10) unsigned,
  `upload_state` varchar(255),
  `error_str` varchar(255),
  `url` varchar(255),
  `install_path` varchar(255),
  CONSTRAINT `fk_upload__host_id` FOREIGN KEY(`host_id`) REFERENCES `host` (`id`) ON DELETE CASCADE,
  INDEX `i_upload__host_id`(`host_id`),
  INDEX `i_upload__type_id`(`type_id`),
  PRIMARY KEY  (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`template_host_ref` (
  `id` bigint unsigned NOT NULL auto_increment,
  `host_id` bigint unsigned NOT NULL,
  `template_id` bigint unsigned NOT NULL,
  `created` DATETIME NOT NULL,
  `last_updated` DATETIME,
  `job_id` varchar(255),
  `download_pct` int(10) unsigned,
  `size` bigint unsigned,
  `physical_size` bigint unsigned DEFAULT 0,
  `download_state` varchar(255),
  `error_str` varchar(255),
  `local_path` varchar(255),
  `install_path` varchar(255),
  `url` varchar(255),
  `destroyed` tinyint(1) COMMENT 'indicates whether the template_host entry was destroyed by the user or not',
  `is_copy` tinyint(1) NOT NULL DEFAULT 0 COMMENT 'indicates whether this was copied ',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_template_host_ref__host_id` FOREIGN KEY `fk_template_host_ref__host_id` (`host_id`) REFERENCES `host` (`id`) ON DELETE CASCADE,
  INDEX `i_template_host_ref__host_id`(`host_id`),
  CONSTRAINT `fk_template_host_ref__template_id` FOREIGN KEY `fk_template_host_ref__template_id` (`template_id`) REFERENCES `vm_template` (`id`),
  INDEX `i_template_host_ref__template_id`(`template_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`volume_host_ref` (
  `id` bigint unsigned NOT NULL auto_increment,
  `host_id` bigint unsigned NOT NULL,
  `volume_id` bigint unsigned NOT NULL,
  `zone_id` bigint unsigned NOT NULL,
  `created` DATETIME NOT NULL,
  `last_updated` DATETIME,
  `job_id` varchar(255),
  `download_pct` int(10) unsigned,
  `size` bigint unsigned,
  `physical_size` bigint unsigned DEFAULT 0,
  `download_state` varchar(255),
  `checksum` varchar(255) COMMENT 'checksum for the data disk',
  `error_str` varchar(255),
  `local_path` varchar(255),
  `install_path` varchar(255),
  `url` varchar(255),
  `format` varchar(32) NOT NULL COMMENT 'format for the volume',
  `destroyed` tinyint(1) COMMENT 'indicates whether the volume_host entry was destroyed by the user or not',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_volume_host_ref__host_id` FOREIGN KEY `fk_volume_host_ref__host_id` (`host_id`) REFERENCES `host` (`id`) ON DELETE CASCADE,
  INDEX `i_volume_host_ref__host_id`(`host_id`),
  CONSTRAINT `fk_volume_host_ref__volume_id` FOREIGN KEY `fk_volume_host_ref__volume_id` (`volume_id`) REFERENCES `volumes` (`id`),
  INDEX `i_volume_host_ref__volume_id`(`volume_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`template_swift_ref` (
  `id` bigint unsigned NOT NULL auto_increment,
  `swift_id` bigint unsigned NOT NULL,
  `template_id` bigint unsigned NOT NULL,
  `created` DATETIME NOT NULL,
  `path` varchar(255),
  `size` bigint unsigned,
  `physical_size` bigint unsigned DEFAULT 0,
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_template_swift_ref__swift_id` FOREIGN KEY `fk_template_swift_ref__swift_id` (`swift_id`) REFERENCES `swift` (`id`) ON DELETE CASCADE,
  INDEX `i_template_swift_ref__swift_id`(`swift_id`),
  CONSTRAINT `fk_template_swift_ref__template_id` FOREIGN KEY `fk_template_swift_ref__template_id` (`template_id`) REFERENCES `vm_template` (`id`),
  INDEX `i_template_swift_ref__template_id`(`template_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`template_zone_ref` (
  `id` bigint unsigned NOT NULL auto_increment,
  `zone_id` bigint unsigned NOT NULL,
  `template_id` bigint unsigned NOT NULL,
  `created` DATETIME NOT NULL,
  `last_updated` DATETIME,
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_template_zone_ref__zone_id` FOREIGN KEY `fk_template_zone_ref__zone_id` (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE CASCADE,
  INDEX `i_template_zone_ref__zone_id`(`zone_id`),
  CONSTRAINT `fk_template_zone_ref__template_id` FOREIGN KEY `fk_template_zone_ref__template_id` (`template_id`) REFERENCES `vm_template` (`id`) ON DELETE CASCADE,
  INDEX `i_template_zone_ref__template_id`(`template_id`),
  INDEX `i_template_zone_ref__removed`(`removed`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`console_proxy` (
  `id` bigint unsigned NOT NULL auto_increment,
  `public_mac_address` varchar(17) unique COMMENT 'mac address of the public facing network card',
  `public_ip_address` char(40) COMMENT 'public ip address for the console proxy',
  `public_netmask` varchar(15)  COMMENT 'public netmask used for the console proxy',
  `active_session` int(10) NOT NULL DEFAULT 0 COMMENT 'active session number',
  `last_update` DATETIME NULL COMMENT 'Last session update time',
  `session_details` BLOB NULL COMMENT 'session detail info',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_console_proxy__id` FOREIGN KEY `fk_console_proxy__id`(`id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`secondary_storage_vm` (
  `id` bigint unsigned NOT NULL auto_increment,
  `public_mac_address` varchar(17)  unique COMMENT 'mac address of the public facing network card',
  `public_ip_address` char(40) COMMENT 'public ip address for the sec storage vm',
  `public_netmask` varchar(15)  COMMENT 'public netmask used for the sec storage vm',
  `guid` varchar(255)  COMMENT 'copied from guid of secondary storage host',
  `nfs_share` varchar(255)  COMMENT 'server and path exported by the nfs server ',
  `last_update` DATETIME NULL COMMENT 'Last session update time',
  `role` varchar(64) NOT NULL DEFAULT 'templateProcessor' COMMENT 'work role of secondary storage host(templateProcessor | commandExecutor)',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_secondary_storage_vm__id` FOREIGN KEY `fk_secondary_storage_vm__id`(`id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`domain` (
  `id` bigint unsigned NOT NULL auto_increment,
  `parent` bigint unsigned,
  `name` varchar(255),
  `uuid` varchar(40),
  `owner` bigint unsigned NOT NULL,
  `path` varchar(255) NOT NULL,
  `level` int(10) NOT NULL DEFAULT 0,
  `child_count` int(10) NOT NULL DEFAULT 0,
  `next_child_seq` bigint unsigned NOT NULL DEFAULT 1,
  `removed` datetime COMMENT 'date removed',
  `state` char(32) NOT NULL default 'Active' COMMENT 'state of the domain',
  `network_domain` varchar(255),
  `type` varchar(255) NOT NULL DEFAULT 'Normal' COMMENT 'type of the domain - can be Normal or Project',
  PRIMARY KEY  (`id`),
  UNIQUE (parent, name, removed),
  INDEX `i_domain__path`(`path`),
  INDEX `i_domain__removed`(`removed`),
  CONSTRAINT `uc_domain__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`account` (
  `id` bigint unsigned NOT NULL auto_increment,
  `account_name` varchar(100) COMMENT 'an account name set by the creator of the account, defaults to username for single accounts',
  `uuid` varchar(40),
  `type` int(1) unsigned NOT NULL,
  `domain_id` bigint unsigned,
  `state` varchar(10) NOT NULL default 'enabled',
  `removed` datetime COMMENT 'date removed',
  `cleanup_needed` tinyint(1) NOT NULL default '0',
  `network_domain` varchar(255),
  `default_zone_id` bigint unsigned,
  PRIMARY KEY  (`id`),
  INDEX i_account__removed(`removed`),
  CONSTRAINT `fk_account__default_zone_id` FOREIGN KEY `fk_account__default_zone_id`(`default_zone_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE,
  INDEX `i_account__cleanup_needed`(`cleanup_needed`),
  INDEX `i_account__account_name__domain_id__removed`(`account_name`, `domain_id`, `removed`),
  CONSTRAINT `fk_account__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain` (`id`),
  INDEX `i_account__domain_id`(`domain_id`),
  CONSTRAINT `uc_account__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`resource_limit` (
  `id` bigint unsigned NOT NULL auto_increment,
  `domain_id` bigint unsigned,
  `account_id` bigint unsigned,
  `type` varchar(255),
  `max` bigint NOT NULL default '-1',
  CONSTRAINT `fk_resource_limit__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain` (`id`) ON DELETE CASCADE,
  INDEX `i_resource_limit__domain_id`(`domain_id`),
  CONSTRAINT `fk_resource_limit__account_id` FOREIGN KEY(`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  INDEX `i_resource_limit__account_id`(`account_id`),
  PRIMARY KEY  (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`resource_count` (
  `id` bigint unsigned NOT NULL auto_increment,
  `account_id` bigint unsigned,
  `domain_id` bigint unsigned,
  `type` varchar(255),
  `count` bigint NOT NULL default '0',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_resource_count__account_id` FOREIGN KEY `fk_resource_count__account_id`(`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_resource_count__domain_id` FOREIGN KEY `fk_resource_count__domain_id`(`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  INDEX `i_resource_count__type`(`type`),
  UNIQUE `i_resource_count__type_accountId`(`type`, `account_id`),
  UNIQUE `i_resource_count__type_domaintId`(`type`, `domain_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_host_capacity` (
  `id` bigint unsigned NOT NULL auto_increment,
  `host_id` bigint unsigned,
  `data_center_id` bigint unsigned NOT NULL,
  `pod_id` bigint unsigned,
  `cluster_id` bigint unsigned COMMENT 'foreign key to cluster',
  `used_capacity` bigint signed NOT NULL,
  `reserved_capacity` bigint signed NOT NULL,
  `total_capacity` bigint signed NOT NULL,
  `capacity_type` int(1) unsigned NOT NULL,
  `capacity_state` varchar(32) NOT NULL DEFAULT 'Enabled' COMMENT 'Is this capacity enabled for allocation for new resources',
  `update_time` datetime COMMENT 'time the capacity was last updated',
  `created` datetime COMMENT  'date created',
  PRIMARY KEY  (`id`),
  INDEX `i_op_host_capacity__host_type`(`host_id`, `capacity_type`),
  INDEX `i_op_host_capacity__pod_id`(`pod_id`),
  INDEX `i_op_host_capacity__data_center_id`(`data_center_id`),
  INDEX `i_op_host_capacity__cluster_id`(`cluster_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`alert` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `type` int(1) unsigned NOT NULL,
  `cluster_id` bigint unsigned,
  `pod_id` bigint unsigned,
  `data_center_id` bigint unsigned NOT NULL,
  `subject` varchar(999) COMMENT 'according to SMTP spec, max subject length is 1000 including the CRLF character, so allow enough space to fit long pod/zone/host names',
  `sent_count` int(3) unsigned NOT NULL,
  `created` DATETIME NULL COMMENT 'when this alert type was created',
  `last_sent` DATETIME NULL COMMENT 'Last time the alert was sent',
  `resolved` DATETIME NULL COMMENT 'when the alert status was resolved (available memory no longer at critical level, etc.)',
  PRIMARY KEY  (`id`),
  CONSTRAINT `uc_alert__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`async_job` (
  `id` bigint unsigned NOT NULL auto_increment,
  `user_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `session_key` varchar(64) COMMENT 'all async-job manage to apply session based security enforcement',
  `instance_type` varchar(64) COMMENT 'instance_type and instance_id work together to allow attaching an instance object to a job',
  `instance_id` bigint unsigned,
  `job_cmd` varchar(64) NOT NULL COMMENT 'command name',
  `job_cmd_originator` varchar(64) COMMENT 'command originator',
  `job_cmd_info` text COMMENT 'command parameter info',
  `job_cmd_ver` int(1) COMMENT 'command version',
  `callback_type` int(1) COMMENT 'call back type, 0 : polling, 1 : email',
  `callback_address` varchar(128) COMMENT 'call back address by callback_type',
  `job_status` int(1) COMMENT 'general job execution status',
  `job_process_status` int(1) COMMENT 'job specific process status for asynchronize progress update',
  `job_result_code` int(1) COMMENT 'job result code, specify error code corresponding to result message',
  `job_result` text COMMENT 'job result info',
  `job_init_msid` bigint COMMENT 'the initiating msid',
  `job_complete_msid` bigint  COMMENT 'completing msid',
  `created` datetime COMMENT 'date created',
  `last_updated` datetime COMMENT 'date created',
  `last_polled` datetime COMMENT 'date polled',
  `removed` datetime COMMENT 'date removed',
  `uuid` varchar(40),
  PRIMARY KEY (`id`),
  INDEX `i_async_job__removed`(`removed`),
  INDEX `i_async__user_id`(`user_id`),
  INDEX `i_async__account_id`(`account_id`),
  INDEX `i_async__instance_type_id`(`instance_type`,`instance_id`),
  INDEX `i_async__job_cmd`(`job_cmd`),
  INDEX `i_async__created`(`created`),
  INDEX `i_async__last_updated`(`last_updated`),
  INDEX `i_async__last_poll`(`last_polled`),
  CONSTRAINT `uc_async__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`sync_queue` (
  `id` bigint unsigned NOT NULL auto_increment,
  `sync_objtype` varchar(64) NOT NULL,
  `sync_objid` bigint unsigned NOT NULL,
  `queue_proc_msid` bigint,
  `queue_proc_number` bigint COMMENT 'process number, increase 1 for each iteration',
  `queue_proc_time` datetime COMMENT 'last time to process the queue',
  `created` datetime COMMENT 'date created',
  `last_updated` datetime COMMENT 'date created',
  PRIMARY KEY  (`id`),
  UNIQUE `i_sync_queue__objtype__objid`(`sync_objtype`, `sync_objid`),
  INDEX `i_sync_queue__created`(`created`),
  INDEX `i_sync_queue__last_updated`(`last_updated`),
  INDEX `i_sync_queue__queue_proc_time`(`queue_proc_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`stack_maid` (
  `id` bigint unsigned NOT NULL auto_increment,
  `msid` bigint unsigned NOT NULL,
  `thread_id` bigint unsigned NOT NULL,
  `seq` int unsigned NOT NULL,
  `cleanup_delegate` varchar(128),
  `cleanup_context` text,
  `created` datetime,
  PRIMARY KEY  (`id`),
  INDEX `i_stack_maid_msid_thread_id`(`msid`, `thread_id`),
  INDEX `i_stack_maid_seq`(`msid`, `seq`),
  INDEX `i_stack_maid_created`(`created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`sync_queue_item` (
  `id` bigint unsigned NOT NULL auto_increment,
  `queue_id` bigint unsigned NOT NULL,
  `content_type` varchar(64),
  `content_id` bigint,
  `queue_proc_msid` bigint COMMENT 'owner msid when the queue item is being processed',
  `queue_proc_number` bigint COMMENT 'used to distinguish raw items and items being in process',
  `created` datetime COMMENT 'time created',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_sync_queue_item__queue_id` FOREIGN KEY `fk_sync_queue_item__queue_id` (`queue_id`) REFERENCES `sync_queue` (`id`) ON DELETE CASCADE,
  INDEX `i_sync_queue_item__queue_id`(`queue_id`),
  INDEX `i_sync_queue_item__created`(`created`),
  INDEX `i_sync_queue_item__queue_proc_number`(`queue_proc_number`),
  INDEX `i_sync_queue_item__queue_proc_msid`(`queue_proc_msid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`disk_offering` (
  `id` bigint unsigned NOT NULL auto_increment,
  `domain_id` bigint unsigned,
  `name` varchar(255) NOT NULL,
  `uuid` varchar(40),
  `display_text` varchar(4096) NULL COMMENT 'Descrianaption text set by the admin for display purpose only',
  `disk_size` bigint unsigned NOT NULL COMMENT 'disk space in byte',
  `type` varchar(32) COMMENT 'inheritted by who?',
  `tags` varchar(4096) COMMENT 'comma separated tags about the disk_offering',
  `recreatable` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'The root disk is always recreatable',
  `use_local_storage` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Indicates whether local storage pools should be used',
  `unique_name` varchar(32) UNIQUE COMMENT 'unique name',
  `system_use` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'is this offering for system used only',
  `customized` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT '0 implies not customized by default',
  `removed` datetime COMMENT 'date removed',
  `created` datetime COMMENT 'date the disk offering was created',
  `sort_key` int(32) NOT NULL default 0 COMMENT 'sort key used for customising sort method',
  PRIMARY KEY  (`id`),
  INDEX `i_disk_offering__removed`(`removed`),
  CONSTRAINT `uc_disk_offering__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`service_offering` (
  `id` bigint unsigned NOT NULL,
  `cpu` int(10) unsigned NOT NULL COMMENT '# of cores',
  `speed` int(10) unsigned NOT NULL COMMENT 'speed per core in mhz',
  `ram_size` bigint unsigned NOT NULL,
  `nw_rate` smallint unsigned default 200 COMMENT 'network rate throttle mbits/s',
  `mc_rate` smallint unsigned default 10 COMMENT 'mcast rate throttle mbits/s',
  `ha_enabled` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Enable HA',
  `limit_cpu_use` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Limit the CPU usage to service offering',
  `host_tag` varchar(255) COMMENT 'host tag specified by the service_offering',
  `default_use` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'is this offering a default system offering',
  `vm_type` varchar(32) COMMENT 'type of offering specified for system offerings',
  `sort_key` int(32) NOT NULL default 0 COMMENT 'sort key used for customising sort method',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_service_offering__id` FOREIGN KEY `fk_service_offering__id`(`id`) REFERENCES `disk_offering`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`network_rule_config` (
  `id` bigint unsigned NOT NULL auto_increment,
  `security_group_id` bigint unsigned NOT NULL,
  `public_port` varchar(10) default NULL,
  `private_port` varchar(10) default NULL,
  `protocol` varchar(16) NOT NULL default 'TCP',
  `create_status` varchar(32) COMMENT 'rule creation status',
  PRIMARY KEY  (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`remote_access_vpn` (
  `vpn_server_addr_id` bigint unsigned UNIQUE NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `network_id` bigint unsigned NOT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `local_ip` char(40) NOT NULL,
  `ip_range` varchar(32) NOT NULL,
  `ipsec_psk` varchar(256) NOT NULL,
  `state` char(32) NOT NULL,
  PRIMARY KEY  (`vpn_server_addr_id`),
  CONSTRAINT `fk_remote_access_vpn__account_id` FOREIGN KEY `fk_remote_access_vpn__account_id`(`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_remote_access_vpn__domain_id` FOREIGN KEY `fk_remote_access_vpn__domain_id`(`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_remote_access_vpn__network_id` FOREIGN KEY `fk_remote_access_vpn__network_id` (`network_id`) REFERENCES `networks` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_remote_access_vpn__vpn_server_addr_id` FOREIGN KEY `fk_remote_access_vpn__vpn_server_addr_id` (`vpn_server_addr_id`) REFERENCES `user_ip_address` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`vpn_users` (
  `id` bigint unsigned NOT NULL UNIQUE auto_increment,
  `uuid` varchar(40),
  `owner_id` bigint unsigned NOT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `username` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `state` char(32) NOT NULL COMMENT 'What state is this vpn user in',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_vpn_users__owner_id` FOREIGN KEY (`owner_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_vpn_users__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  INDEX `i_vpn_users_username`(`username`),
  UNIQUE `i_vpn_users__account_id__username`(`owner_id`, `username`),
  CONSTRAINT `uc_vpn_users__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`storage_pool` (
  `id` bigint unsigned UNIQUE NOT NULL,
  `name` varchar(255) COMMENT 'should be NOT NULL',
  `uuid` varchar(255) UNIQUE,
  `pool_type` varchar(32) NOT NULL,
  `port` int unsigned NOT NULL,
  `data_center_id` bigint unsigned NOT NULL,
  `pod_id` bigint unsigned,
  `cluster_id` bigint unsigned COMMENT 'foreign key to cluster',
  `available_bytes` bigint unsigned,
  `capacity_bytes` bigint unsigned,
  `host_address` varchar(255) NOT NULL COMMENT 'FQDN or IP of storage server',
  `user_info` varchar(255) NULL COMMENT 'Authorization information for the storage pool. Used by network filesystems',
  `path` varchar(255) NOT NULL COMMENT 'Filesystem path that is shared',
  `created` datetime COMMENT 'date the pool created',
  `removed` datetime COMMENT 'date removed if not null',
  `update_time` DATETIME,
  `status` varchar(32),
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_storage_pool__pod_id` FOREIGN KEY `fk_storage_pool__pod_id` (`pod_id`) REFERENCES `host_pod_ref` (`id`) ON DELETE CASCADE,
  INDEX `i_storage_pool__pod_id`(`pod_id`),
  CONSTRAINT `fk_storage_pool__cluster_id` FOREIGN KEY `fk_storage_pool__cluster_id`(`cluster_id`) REFERENCES `cloud`.`cluster`(`id`),
  INDEX `i_storage_pool__removed`(`removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`storage_pool_details` (
  `id` bigint unsigned UNIQUE NOT NULL AUTO_INCREMENT COMMENT 'id',
  `pool_id` bigint unsigned NOT NULL COMMENT 'pool the detail is related to',
  `name` varchar(255) NOT NULL COMMENT 'name of the detail',
  `value` varchar(255) NOT NULL COMMENT 'value of the detail',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_storage_pool_details__pool_id` FOREIGN KEY `fk_storage_pool_details__pool_id`(`pool_id`) REFERENCES `storage_pool`(`id`) ON DELETE CASCADE,
  INDEX `i_storage_pool_details__name__value`(`name`(128), `value`(128))
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`storage_pool_host_ref` (
  `id` bigint unsigned NOT NULL auto_increment,
  `host_id` bigint unsigned NOT NULL,
  `pool_id` bigint unsigned NOT NULL,
  `created` DATETIME NOT NULL,
  `last_updated` DATETIME,
  `local_path` varchar(255),
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_storage_pool_host_ref__host_id` FOREIGN KEY `fk_storage_pool_host_ref__host_id`(`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_storage_pool_host_ref__pool_id` FOREIGN KEY `fk_storage_pool_host_ref__pool_id`(`pool_id`) REFERENCES `storage_pool`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`template_spool_ref` (
  `id` bigint unsigned NOT NULL auto_increment,
  `pool_id` bigint unsigned NOT NULL,
  `template_id` bigint unsigned NOT NULL,
  `created` DATETIME NOT NULL,
  `last_updated` DATETIME,
  `job_id` varchar(255),
  `download_pct` int(10) unsigned,
  `download_state` varchar(255),
  `error_str` varchar(255),
  `local_path` varchar(255),
  `install_path` varchar(255),
  `template_size` bigint unsigned NOT NULL COMMENT 'the size of the template on the pool',
  `marked_for_gc` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'if true, the garbage collector will evict the template from this pool.',
  PRIMARY KEY  (`id`),
  UNIQUE `i_template_spool_ref__template_id__pool_id`(`template_id`, `pool_id`),
  CONSTRAINT `fk_template_spool_ref__template_id` FOREIGN KEY (`template_id`) REFERENCES `vm_template`(`id`),
  CONSTRAINT `fk_template_spool_ref__pool_id` FOREIGN KEY (`pool_id`) REFERENCES `storage_pool`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`guest_os` (
  `id` bigint unsigned NOT NULL auto_increment,
  `category_id` bigint unsigned NOT NULL,
  `name` varchar(255),
  `uuid` varchar(40),
  `display_name` varchar(255) NOT NULL,
  PRIMARY KEY  (`id`),
  CONSTRAINT `uc_guest_os__uuid` UNIQUE (`uuid`),
  CONSTRAINT `fk_guest_os__category_id` FOREIGN KEY(`category_id`) REFERENCES `guest_os_category` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`guest_os_hypervisor` (
  `id` bigint unsigned NOT NULL auto_increment,
  `hypervisor_type` varchar(32) NOT NULL,
  `guest_os_name` varchar(255) NOT NULL,
  `guest_os_id` bigint unsigned NOT NULL,
  PRIMARY KEY  (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`guest_os_category` (
  `id` bigint unsigned NOT NULL auto_increment,
  `name` varchar(255) NOT NULL,
  `uuid` varchar(40),
  PRIMARY KEY  (`id`),
  CONSTRAINT `uc_guest_os_category__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`hypervisor_capabilities` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `hypervisor_type` varchar(32) NOT NULL,
  `hypervisor_version` varchar(32),
  `max_guests_limit` bigint unsigned DEFAULT 50,
  `security_group_enabled` int(1) unsigned DEFAULT 1 COMMENT 'Is security group supported',
  `max_data_volumes_limit` int unsigned DEFAULT 6 COMMENT 'Max. data volumes per VM supported by hypervisor',
  PRIMARY KEY  (`id`),
  CONSTRAINT `uc_hypervisor_capabilities__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled) VALUES ('XenServer', 'default', 50, 1);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled) VALUES ('XenServer', 'XCP 1.0', 50, 1);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled) VALUES ('XenServer', '5.6', 50, 1);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled) VALUES ('XenServer', '5.6 FP1', 50, 1);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled) VALUES ('XenServer', '5.6 SP2', 50, 1);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled, max_data_volumes_limit) VALUES ('XenServer', '6.0', 50, 1, 13);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled, max_data_volumes_limit) VALUES ('XenServer', '6.0.2', 50, 1, 13);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled, max_data_volumes_limit) VALUES ('VMware', 'default', 128, 0, 13);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled, max_data_volumes_limit) VALUES ('VMware', '4.0', 128, 0, 13);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled, max_data_volumes_limit) VALUES ('VMware', '4.1', 128, 0, 13);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled, max_data_volumes_limit) VALUES ('VMware', '5.0', 128, 0, 13);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled, max_data_volumes_limit) VALUES ('VMware', '5.1', 128, 0, 13);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled) VALUES ('KVM', 'default', 50, 1);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled) VALUES ('Ovm', 'default', 25, 1);
INSERT IGNORE INTO `cloud`.`hypervisor_capabilities`(hypervisor_type, hypervisor_version, max_guests_limit, security_group_enabled) VALUES ('Ovm', '2.3', 25, 1);

CREATE TABLE  `cloud`.`launch_permission` (
  `id` bigint unsigned NOT NULL auto_increment,
  `template_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  PRIMARY KEY  (`id`),
  INDEX `i_launch_permission_template_id`(`template_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`snapshot_policy` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `volume_id` bigint unsigned NOT NULL,
  `schedule` varchar(100) NOT NULL COMMENT 'schedule time of execution',
  `timezone` varchar(100) NOT NULL COMMENT 'the timezone in which the schedule time is specified',
  `interval` int(4) NOT NULL default 4 COMMENT 'backup schedule, e.g. hourly, daily, etc.',
  `max_snaps` int(8) NOT NULL default 0 COMMENT 'maximum number of snapshots to maintain',
  `active` tinyint(1) unsigned NOT NULL COMMENT 'Is the policy active',
  PRIMARY KEY  (`id`),
  INDEX `i_snapshot_policy__volume_id`(`volume_id`),
  CONSTRAINT `uc_snapshot_policy__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`snapshot_schedule` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `volume_id` bigint unsigned NOT NULL COMMENT 'The volume for which this snapshot is being taken',
  `policy_id` bigint unsigned NOT NULL COMMENT 'One of the policyIds for which this snapshot was taken',
  `scheduled_timestamp` datetime NOT NULL COMMENT 'Time at which the snapshot was scheduled for execution',
  `async_job_id` bigint unsigned COMMENT 'If this schedule is being executed, it is the id of the create aysnc_job. Before that it is null',
  `snapshot_id` bigint unsigned COMMENT 'If this schedule is being executed, then the corresponding snapshot has this id. Before that it is null',
  UNIQUE (volume_id, policy_id),
  CONSTRAINT `uc_snapshot_schedule__uuid` UNIQUE (`uuid`),
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk__snapshot_schedule_volume_id` FOREIGN KEY(`volume_id`) REFERENCES `volumes` (`id`) ON DELETE CASCADE,
  INDEX `i_snapshot_schedule__volume_id`(`volume_id`),
  CONSTRAINT `fk__snapshot_schedule_policy_id` FOREIGN KEY(`policy_id`) REFERENCES `snapshot_policy` (`id`) ON DELETE CASCADE,
  INDEX `i_snapshot_schedule__policy_id`(`policy_id`),
  CONSTRAINT `fk__snapshot_schedule_async_job_id` FOREIGN KEY(`async_job_id`) REFERENCES `async_job` (`id`) ON DELETE CASCADE,
  INDEX `i_snapshot_schedule__async_job_id`(`async_job_id`),
  CONSTRAINT `fk__snapshot_schedule_snapshot_id` FOREIGN KEY(`snapshot_id`) REFERENCES `snapshots` (`id`) ON DELETE CASCADE,
  INDEX `i_snapshot_schedule__snapshot_id`(`snapshot_id`),
  INDEX `i_snapshot_schedule__scheduled_timestamp`(`scheduled_timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_pod_vlan_alloc` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary id',
    `vlan` varchar(18) NOT NULL COMMENT 'vlan id',
    `data_center_id` bigint unsigned NOT NULL COMMENT 'data center the pod belongs to',
    `pod_id` bigint unsigned NOT NULL COMMENT 'pod the vlan belongs to',
    `account_id` bigint unsigned NULL COMMENT 'account the vlan belongs to right now',
    `taken` datetime COMMENT 'Date taken',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`security_group` (
  `id` bigint unsigned NOT NULL auto_increment,
  `name` varchar(255) NOT NULL,
  `uuid` varchar(40),
  `description` varchar(4096) NULL,
  `domain_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  UNIQUE (`name`, `account_id`),
  PRIMARY KEY  (`id`),
  CONSTRAINT `uc_security_group__uuid` UNIQUE (`uuid`),
  CONSTRAINT `fk_security_group__account_id` FOREIGN KEY `fk_security_group__account_id` (`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_security_group__domain_id` FOREIGN KEY `fk_security_group__domain_id` (`domain_id`) REFERENCES `domain` (`id`),
  INDEX `i_security_group_name`(`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`security_group_rule` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `security_group_id` bigint unsigned NOT NULL,
  `type` varchar(10) default 'ingress',
  `start_port` varchar(10) default NULL,
  `end_port` varchar(10) default NULL,
  `protocol` varchar(16) NOT NULL default 'TCP',
  `allowed_network_id` bigint unsigned,
  `allowed_ip_cidr`  varchar(44),
  `create_status` varchar(32) COMMENT 'rule creation status',
  PRIMARY KEY  (`id`),
  CONSTRAINT `uc_security_group_rule__uuid` UNIQUE (`uuid`),
  CONSTRAINT `fk_security_group_rule___security_group_id` FOREIGN KEY(`security_group_id`) REFERENCES `security_group` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_security_group_rule___allowed_network_id` FOREIGN KEY(`allowed_network_id`) REFERENCES `security_group` (`id`) ON DELETE CASCADE,
  INDEX `i_security_group_rule_network_id`(`security_group_id`),
  INDEX `i_security_group_rule_allowed_network`(`allowed_network_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`security_group_vm_map` (
  `id` bigint unsigned NOT NULL auto_increment,
  `security_group_id` bigint unsigned NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_security_group_vm_map___security_group_id` FOREIGN KEY(`security_group_id`) REFERENCES `security_group` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_security_group_vm_map___instance_id` FOREIGN KEY(`instance_id`) REFERENCES `user_vm` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_nwgrp_work` (
  `id` bigint unsigned UNIQUE NOT NULL AUTO_INCREMENT COMMENT 'id',
  `instance_id` bigint unsigned NOT NULL COMMENT 'vm instance that needs rules to be synced.',
  `mgmt_server_id` bigint unsigned COMMENT 'management server that has taken up the work of doing rule sync',
  `created` datetime NOT NULL COMMENT 'time the entry was requested',
  `taken` datetime COMMENT 'time it was taken by the management server',
  `step` varchar(32) NOT NULL COMMENT 'Step in the work',
  `seq_no` bigint unsigned  COMMENT 'seq number to be sent to agent, uniquely identifies ruleset update',
  PRIMARY KEY (`id`),
  INDEX `i_op_nwgrp_work__instance_id`(`instance_id`),
  INDEX `i_op_nwgrp_work__mgmt_server_id`(`mgmt_server_id`),
  INDEX `i_op_nwgrp_work__taken`(`taken`),
  INDEX `i_op_nwgrp_work__step`(`step`),
  INDEX `i_op_nwgrp_work__seq_no`(`seq_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_vm_ruleset_log` (
  `id` bigint unsigned UNIQUE NOT NULL AUTO_INCREMENT COMMENT 'id',
  `instance_id` bigint unsigned NOT NULL COMMENT 'vm instance that needs rules to be synced.',
  `created` datetime NOT NULL COMMENT 'time the entry was requested',
  `logsequence` bigint unsigned  COMMENT 'seq number to be sent to agent, uniquely identifies ruleset update',
  PRIMARY KEY (`id`),
  UNIQUE `u_op_vm_ruleset_log__instance_id`(`instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`instance_group` (
  `id` bigint unsigned NOT NULL UNIQUE auto_increment,
  `account_id` bigint unsigned NOT NULL COMMENT 'owner.  foreign key to account table',
  `name` varchar(255) NOT NULL,
  `uuid` varchar(40),
  `removed` datetime COMMENT 'date the group was removed',
  `created` datetime COMMENT 'date the group was created',
  PRIMARY KEY  (`id`),
  INDEX `i_instance_group__removed`(`removed`),
  CONSTRAINT `uc_instance_group__uuid` UNIQUE (`uuid`),
  CONSTRAINT `fk_instance_group__account_id` FOREIGN KEY(`account_id`) REFERENCES `account` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1 DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`instance_group_vm_map` (
  `id` bigint unsigned NOT NULL auto_increment,
  `group_id` bigint unsigned NOT NULL,
  `instance_id` bigint unsigned NOT NULL,
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_instance_group_vm_map___group_id` FOREIGN KEY(`group_id`) REFERENCES `instance_group` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_instance_group_vm_map___instance_id` FOREIGN KEY(`instance_id`) REFERENCES `user_vm` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`ssh_keypairs` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner, foreign key to account table',
  `domain_id` bigint unsigned NOT NULL COMMENT 'domain, foreign key to domain table',
  `keypair_name` varchar(256) NOT NULL COMMENT 'name of the key pair',
  `fingerprint` varchar(128) NOT NULL COMMENT 'fingerprint for the ssh public key',
  `public_key` varchar(5120) NOT NULL COMMENT 'public key of the ssh key pair',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_ssh_keypairs__account_id` FOREIGN KEY(`account_id`) REFERENCES `account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ssh_keypairs__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`usage_event` (
  `id` bigint unsigned NOT NULL auto_increment,
  `type` varchar(32) NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `created` datetime NOT NULL,
  `zone_id` bigint unsigned NOT NULL,
  `resource_id` bigint unsigned,
  `resource_name` varchar(255),
  `offering_id` bigint unsigned,
  `template_id` bigint unsigned,
  `size` bigint unsigned,
  `resource_type` varchar(32),
  `processed` tinyint NOT NULL default '0',
  PRIMARY KEY  (`id`),
  INDEX `i_usage_event__created`(`created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`ovs_tunnel_interface` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ip` varchar(16) DEFAULT NULL,
  `netmask` varchar(16) DEFAULT NULL,
  `mac` varchar(18) DEFAULT NULL,
  `host_id` bigint DEFAULT NULL,
  `label` varchar(45) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT INTO `cloud`.`ovs_tunnel_interface` (`ip`, `netmask`, `mac`, `host_id`, `label`) VALUES ('0', '0', '0', 0, 'lock');

CREATE TABLE `cloud`.`ovs_tunnel_network`(
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT,
  `from` bigint unsigned COMMENT 'from host id',
  `to` bigint unsigned COMMENT 'to host id',
  `network_id` bigint unsigned COMMENT 'network identifier',
  `key` int unsigned COMMENT 'gre key',
  `port_name` varchar(32) COMMENT 'in port on open vswitch',
  `state` varchar(16) default 'FAILED' COMMENT 'result of tunnel creatation',
  PRIMARY KEY(`from`, `to`, `network_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT INTO `cloud`.`ovs_tunnel_network` (`from`, `to`, `network_id`, `key`, `port_name`, `state`) VALUES (0, 0, 0, 0, 'lock', 'SUCCESS');

CREATE TABLE `cloud`.`storage_pool_work` (
  `id` bigint unsigned UNIQUE NOT NULL AUTO_INCREMENT COMMENT 'id',
  `pool_id` bigint unsigned NOT NULL COMMENT 'storage pool associated with the vm',
  `vm_id` bigint unsigned NOT NULL COMMENT 'vm identifier',
  `stopped_for_maintenance` tinyint unsigned NOT NULL DEFAULT 0 COMMENT 'this flag denoted whether the vm was stopped during maintenance',
  `started_after_maintenance` tinyint unsigned NOT NULL DEFAULT 0 COMMENT 'this flag denoted whether the vm was started after maintenance',
  `mgmt_server_id` bigint unsigned NOT NULL COMMENT 'management server id',
  PRIMARY KEY (`id`),
 UNIQUE (pool_id,vm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`data_center_details` (
  `id` bigint unsigned NOT NULL auto_increment,
  `dc_id` bigint unsigned NOT NULL COMMENT 'dc id',
  `name` varchar(255) NOT NULL,
  `value` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_dc_details__dc_id` FOREIGN KEY (`dc_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`domain_network_ref` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `domain_id` bigint unsigned NOT NULL COMMENT 'domain id',
  `network_id` bigint unsigned NOT NULL COMMENT 'network id',
  `subdomain_access` int(1) unsigned COMMENT '1 if network can be accessible from the subdomain',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_domain_network_ref__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_domain_network_ref__networks_id` FOREIGN KEY (`network_id`) REFERENCES `networks`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`cmd_exec_log` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `host_id` bigint unsigned NOT NULL COMMENT 'host id of the system VM agent that command is sent to',
  `instance_id` bigint unsigned NOT NULL COMMENT 'instance id of the system VM that command is executed on',
  `command_name` varchar(255) NOT NULL COMMENT 'command name',
  `weight` integer NOT NULL DEFAULT 1 COMMENT 'command weight in consideration of the load factor added to host that is executing the command',
  `created` datetime NOT NULL COMMENT 'date created',
  PRIMARY KEY (`id`),
  INDEX `i_cmd_exec_log__host_id`(`host_id`),
  INDEX `i_cmd_exec_log__instance_id`(`instance_id`),
  CONSTRAINT `fk_cmd_exec_log_ref__inst_id` FOREIGN KEY (`instance_id`) REFERENCES `vm_instance`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`keystore` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `name` varchar(64) NOT NULL COMMENT 'unique name for the certifiation',
  `certificate` text NOT NULL COMMENT 'the actual certificate being stored in the db',
  `key` text COMMENT 'private key associated with the certificate',
  `domain_suffix` varchar(256) NOT NULL COMMENT 'DNS domain suffix associated with the certificate',
  `seq` int,
  PRIMARY KEY (`id`),
  UNIQUE(name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`swift` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40),
  `url` varchar(255) NOT NULL,
  `account` varchar(255) NOT NULL COMMENT ' account in swift',
  `username` varchar(255) NOT NULL COMMENT ' username in swift',
  `key` varchar(255) NOT NULL COMMENT 'token for this user',
  `created` datetime COMMENT 'date the swift first signed on',
  PRIMARY KEY (`id`),
  CONSTRAINT `uc_swift__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`op_host_transfer` (
  `id` bigint unsigned UNIQUE NOT NULL COMMENT 'Id of the host',
  `initial_mgmt_server_id` bigint unsigned COMMENT 'management server the host is transferred from',
  `future_mgmt_server_id` bigint unsigned COMMENT 'management server the host is transferred to',
  `state` varchar(32) NOT NULL COMMENT 'the transfer state of the host',
  `created` datetime NOT NULL COMMENT 'date created',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_op_host_transfer__id` FOREIGN KEY `fk_op_host_transfer__id` (`id`) REFERENCES `host` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_op_host_transfer__initial_mgmt_server_id` FOREIGN KEY `fk_op_host_transfer__initial_mgmt_server_id`(`initial_mgmt_server_id`) REFERENCES `mshost`(`msid`),
  CONSTRAINT `fk_op_host_transfer__future_mgmt_server_id` FOREIGN KEY `fk_op_host_transfer__future_mgmt_server_id`(`future_mgmt_server_id`) REFERENCES `mshost`(`msid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE  `cloud`.`projects` (
  `id` bigint unsigned NOT NULL auto_increment,
  `name` varchar(255) COMMENT 'project name',
  `uuid` varchar(40),
  `display_text` varchar(255) COMMENT 'project name',
  `project_account_id` bigint unsigned NOT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `created` datetime COMMENT 'date created',
  `removed` datetime COMMENT 'date removed',
  `state` varchar(255) NOT NULL COMMENT 'state of the project (Active/Inactive/Suspended)',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_projects__project_account_id` FOREIGN KEY(`project_account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_projects__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  INDEX `i_projects__removed`(`removed`),
  CONSTRAINT `uc_projects__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE  `cloud`.`project_account` (
  `id` bigint unsigned NOT NULL auto_increment,
  `account_id` bigint unsigned NOT NULL COMMENT'account id',
  `account_role` varchar(255) NOT NULL DEFAULT 'Regular' COMMENT 'Account role in the project (Owner or Regular)',
  `project_id` bigint unsigned NOT NULL COMMENT 'project id',
  `project_account_id` bigint unsigned NOT NULL,
  `created` datetime COMMENT 'date created',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_project_account__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_account__project_id` FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_account__project_account_id` FOREIGN KEY(`project_account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  UNIQUE (`account_id`, `project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE  `cloud`.`project_invitations` (
  `id` bigint unsigned NOT NULL auto_increment,
  `uuid` varchar(40),
  `project_id` bigint unsigned NOT NULL COMMENT 'project id',
  `account_id` bigint unsigned COMMENT 'account id',
  `domain_id` bigint unsigned COMMENT 'domain id',
  `email` varchar(255) COMMENT 'email',
  `token` varchar(255) COMMENT 'token',
  `state` varchar(255) NOT NULL DEFAULT 'Pending' COMMENT 'the state of the invitation',
  `created` datetime COMMENT 'date created',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_project_invitations__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_invitations__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_project_invitations__project_id` FOREIGN KEY(`project_id`) REFERENCES `projects`(`id`) ON DELETE CASCADE,
  UNIQUE (`project_id`, `account_id`),
  UNIQUE (`project_id`, `email`),
  UNIQUE (`project_id`, `token`),
  CONSTRAINT `uc_project_invitations__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE `cloud`.`elastic_lb_vm_map` (
  `id` bigint unsigned NOT NULL auto_increment,
  `ip_addr_id` bigint unsigned NOT NULL,
  `elb_vm_id` bigint unsigned NOT NULL,
  `lb_id` bigint unsigned,
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_elastic_lb_vm_map__ip_id` FOREIGN KEY `fk_elastic_lb_vm_map__ip_id` (`ip_addr_id`) REFERENCES `user_ip_address` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_elastic_lb_vm_map__elb_vm_id` FOREIGN KEY `fk_elastic_lb_vm_map__elb_vm_id` (`elb_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_elastic_lb_vm_map__lb_id` FOREIGN KEY `fk_elastic_lb_vm_map__lb_id` (`lb_id`) REFERENCES `load_balancing_rules` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `ntwk_offering_service_map` (
  `id` bigint unsigned NOT NULL auto_increment,
  `network_offering_id` bigint unsigned NOT NULL COMMENT 'network_offering_id',
  `service` varchar(255) NOT NULL COMMENT 'service',
  `provider` varchar(255) COMMENT 'service provider',
  `created` datetime COMMENT 'date created',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_ntwk_offering_service_map__network_offering_id` FOREIGN KEY(`network_offering_id`) REFERENCES `network_offerings`(`id`) ON DELETE CASCADE,
  UNIQUE (`network_offering_id`, `service`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `ntwk_service_map` (
  `id` bigint unsigned NOT NULL auto_increment,
  `network_id` bigint unsigned NOT NULL COMMENT 'network_id',
  `service` varchar(255) NOT NULL COMMENT 'service',
  `provider` varchar(255) COMMENT 'service provider',
  `created` datetime COMMENT 'date created',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_ntwk_service_map__network_id` FOREIGN KEY(`network_id`) REFERENCES `networks`(`id`) ON DELETE CASCADE,
  UNIQUE (`network_id`, `service`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE `cloud`.`physical_network` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(40),
  `name` varchar(255) NOT NULL,
  `data_center_id` bigint unsigned NOT NULL COMMENT 'data center id that this physical network belongs to',
  `vnet` varchar(255),
  `speed` varchar(32),
  `domain_id` bigint unsigned COMMENT 'foreign key to domain id',
  `broadcast_domain_range` varchar(32) NOT NULL DEFAULT 'POD' COMMENT 'range of broadcast domain : POD/ZONE',
  `state` varchar(32) NOT NULL DEFAULT 'Disabled' COMMENT 'what state is this configuration in',
  `created` datetime COMMENT 'date created',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_physical_network__data_center_id` FOREIGN KEY (`data_center_id`) REFERENCES `data_center`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_physical_network__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`),
  CONSTRAINT `uc_physical_networks__uuid` UNIQUE (`uuid`),
  INDEX `i_physical_network__removed`(`removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`physical_network_tags` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'id of the physical network',
  `tag` varchar(255) NOT NULL COMMENT 'tag',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_physical_network_tags__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE,
  UNIQUE KEY(`physical_network_id`, `tag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`physical_network_isolation_methods` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'id of the physical network',
  `isolation_method` varchar(255) NOT NULL COMMENT 'isolation method(VLAN, L3 or GRE)',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_physical_network_imethods__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE,
  UNIQUE KEY(`physical_network_id`, `isolation_method`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`physical_network_traffic_types` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(40),
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'id of the physical network',
  `traffic_type` varchar(32) NOT NULL COMMENT 'type of traffic going through this network',
  `xen_network_label` varchar(255) COMMENT 'The network name label of the physical device dedicated to this traffic on a XenServer host',
  `kvm_network_label` varchar(255) DEFAULT 'cloudbr0' COMMENT 'The network name label of the physical device dedicated to this traffic on a KVM host',
  `vmware_network_label` varchar(255) DEFAULT 'vSwitch0' COMMENT 'The network name label of the physical device dedicated to this traffic on a VMware host',
  `simulator_network_label` varchar(255) COMMENT 'The name labels needed for identifying the simulator',
  `ovm_network_label` varchar(255) COMMENT 'The network name label of the physical device dedicated to this traffic on a Ovm host',
  `vlan` varchar(255) COMMENT 'The vlan tag to be sent down to a VMware host',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_physical_network_traffic_types__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_traffic_types__uuid` UNIQUE (`uuid`),
  UNIQUE KEY(`physical_network_id`, `traffic_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`physical_network_service_providers` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(40),
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'id of the physical network',
  `provider_name` varchar(255) NOT NULL COMMENT 'Service Provider name',
  `state` varchar(32) NOT NULL DEFAULT 'Disabled' COMMENT 'provider state',
  `destination_physical_network_id` bigint unsigned COMMENT 'id of the physical network to bridge to',
  `vpn_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is VPN service provided',
  `dhcp_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is DHCP service provided',
  `dns_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is DNS service provided',
  `gateway_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is Gateway service provided',
  `firewall_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is Firewall service provided',
  `source_nat_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is Source NAT service provided',
  `load_balance_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is LB service provided',
  `static_nat_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is Static NAT service provided',
  `port_forwarding_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is Port Forwarding service provided',
  `user_data_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is UserData service provided',
  `security_group_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is SG service provided',
  `networkacl_service_provided` tinyint(1) unsigned NOT NULL DEFAULT 0 COMMENT 'Is Network ACL service provided',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_pnetwork_service_providers__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_service_providers__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`external_load_balancer_devices` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(255) UNIQUE,
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'id of the physical network in to which load balancer device is added',
  `provider_name` varchar(255) NOT NULL COMMENT 'Service Provider name corresponding to this load balancer device',
  `device_name` varchar(255) NOT NULL COMMENT 'name of the load balancer device',
  `capacity` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Capacity of the load balancer device',
  `device_state` varchar(32) NOT NULL DEFAULT 'Disabled' COMMENT 'state (enabled/disabled/shutdown) of the device',
  `allocation_state` varchar(32) NOT NULL DEFAULT 'Free' COMMENT 'Allocation state (Free/Shared/Dedicated/Provider) of the device',
  `is_dedicated` int(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 if device/appliance is provisioned for dedicated use only',
  `is_inline` int(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 if load balancer will be used in in-line configuration with firewall',
  `is_managed` int(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 if load balancer appliance is provisioned and its life cycle is managed by cloudstack',
  `host_id` bigint unsigned NOT NULL COMMENT 'host id corresponding to the external load balancer device',
  `parent_host_id` bigint unsigned COMMENT 'if the load balancer appliance is cloudstack managed, then host id on which this appliance is provisioned',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_external_lb_devices_host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_external_lb_devices_parent_host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_external_lb_devices_physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`external_firewall_devices` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(255) UNIQUE,
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'id of the physical network in to which firewall device is added',
  `provider_name` varchar(255) NOT NULL COMMENT 'Service Provider name corresponding to this firewall device',
  `device_name` varchar(255) NOT NULL COMMENT 'name of the firewall device',
  `device_state` varchar(32) NOT NULL DEFAULT 'Disabled' COMMENT 'state (enabled/disabled/shutdown) of the device',
  `is_dedicated` int(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 if device/appliance meant for dedicated use only',
  `allocation_state` varchar(32) NOT NULL DEFAULT 'Free' COMMENT 'Allocation state (Free/Allocated) of the device',
  `host_id` bigint unsigned NOT NULL COMMENT 'host id corresponding to the external firewall device',
  `capacity` bigint unsigned NOT NULL DEFAULT 0 COMMENT 'Capacity of the external firewall device',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_external_firewall_devices__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_external_firewall_devices__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`network_external_lb_device_map` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(255) UNIQUE,
  `network_id` bigint unsigned NOT NULL COMMENT ' guest network id',
  `external_load_balancer_device_id` bigint unsigned NOT NULL COMMENT 'id of external load balancer device assigned for this network',
  `created` datetime COMMENT 'Date from when network started using the device',
  `removed` datetime COMMENT 'Date till the network stopped using the device ',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_network_external_lb_devices_network_id` FOREIGN KEY (`network_id`) REFERENCES `networks`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_network_external_lb_devices_device_id` FOREIGN KEY (`external_load_balancer_device_id`) REFERENCES `external_load_balancer_devices`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`network_external_firewall_device_map` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(255) UNIQUE,
  `network_id` bigint unsigned NOT NULL COMMENT ' guest network id',
  `external_firewall_device_id` bigint unsigned NOT NULL COMMENT 'id of external firewall device assigned for this device',
  `created` datetime COMMENT 'Date from when network started using the device',
  `removed` datetime COMMENT 'Date till the network stopped using the device ',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_network_external_firewall_devices_network_id` FOREIGN KEY (`network_id`) REFERENCES `networks`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_network_external_firewall_devices_device_id` FOREIGN KEY (`external_firewall_device_id`) REFERENCES `external_firewall_devices`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`virtual_router_providers` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `nsp_id` bigint unsigned NOT NULL COMMENT 'Network Service Provider ID',
  `uuid` varchar(40),
  `type` varchar(255) NOT NULL COMMENT 'Virtual router, or ElbVM',
  `enabled` int(1) NOT NULL COMMENT 'Enabled or disabled',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_virtual_router_providers__nsp_id` FOREIGN KEY (`nsp_id`) REFERENCES `physical_network_service_providers` (`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_virtual_router_providers__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`op_user_stats_log` (
  `user_stats_id` bigint unsigned NOT NULL,
  `net_bytes_received` bigint unsigned NOT NULL default '0',
  `net_bytes_sent` bigint unsigned NOT NULL default '0',
  `current_bytes_received` bigint unsigned NOT NULL default '0',
  `current_bytes_sent` bigint unsigned NOT NULL default '0',
  `agg_bytes_received` bigint unsigned NOT NULL default '0',
  `agg_bytes_sent` bigint unsigned NOT NULL default '0',
  `updated` datetime COMMENT 'stats update timestamp',
  UNIQUE KEY (`user_stats_id`, `updated`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`cluster_vsm_map` (
  `cluster_id` bigint unsigned NOT NULL,
  `vsm_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`cluster_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`virtual_supervisor_module` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40),
  `host_id` bigint NOT NULL,
  `vsm_name` varchar(255),
  `username` varchar(255) NOT NULL,
  `password` varchar(255) NOT NULL,
  `ipaddr` varchar(80) NOT NULL,
  `management_vlan` int(32),
  `control_vlan` int(32),
  `packet_vlan` int(32),
  `storage_vlan` int(32),
  `vsm_domain_id` bigint unsigned,
  `config_mode` varchar(20),
  `config_state` varchar(20),
  `vsm_device_state` varchar(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`port_profile` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40),
  `port_profile_name` varchar(255),
  `port_mode` varchar(10),
  `vsm_id` bigint unsigned NOT NULL,
  `trunk_low_vlan_id` int,
  `trunk_high_vlan_id` int,
  `access_vlan_id` int,
  `port_type` varchar(20) NOT NULL,
  `port_binding` varchar(20),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`s2s_vpn_gateway` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40),
  `addr_id` bigint unsigned NOT NULL,
  `vpc_id` bigint unsigned NOT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_s2s_vpn_gateway__addr_id` FOREIGN KEY (`addr_id`) REFERENCES `user_ip_address` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_s2s_vpn_gateway__vpc_id` FOREIGN KEY (`vpc_id`) REFERENCES `vpc` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_s2s_vpn_gateway__account_id` FOREIGN KEY (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_s2s_vpn_gateway__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_s2s_vpn_gateway__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`s2s_customer_gateway` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40),
  `name` varchar(255) NOT NULL,
  `gateway_ip` char(40) NOT NULL,
  `guest_cidr_list` varchar(200) NOT NULL,
  `ipsec_psk` varchar(256),
  `ike_policy` varchar(30) NOT NULL,
  `esp_policy` varchar(30) NOT NULL,
  `ike_lifetime` int NOT NULL DEFAULT 86400,
  `esp_lifetime` int NOT NULL DEFAULT 3600,
  `dpd` int(1) NOT NULL DEFAULT 0,
  `domain_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_s2s_customer_gateway__account_id` FOREIGN KEY (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_s2s_customer_gateway__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_s2s_customer_gateway__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`s2s_vpn_connection` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40),
  `vpn_gateway_id` bigint unsigned NULL,
  `customer_gateway_id` bigint unsigned NULL,
  `state` varchar(32) NOT NULL,
  `domain_id` bigint unsigned NOT NULL,
  `account_id` bigint unsigned NOT NULL,
  `created` datetime NOT NULL COMMENT 'date created',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_s2s_vpn_connection__vpn_gateway_id` FOREIGN KEY (`vpn_gateway_id`) REFERENCES `s2s_vpn_gateway` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_s2s_vpn_connection__customer_gateway_id` FOREIGN KEY (`customer_gateway_id`) REFERENCES `s2s_customer_gateway` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_s2s_vpn_connection__account_id` FOREIGN KEY (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_s2s_vpn_connection__domain_id` FOREIGN KEY (`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_s2s_vpn_connection__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`netscaler_pod_ref` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `external_load_balancer_device_id` bigint unsigned NOT NULL COMMENT 'id of external load balancer device',
  `pod_id` bigint unsigned NOT NULL COMMENT 'pod id',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_ns_pod_ref__pod_id` FOREIGN KEY (`pod_id`) REFERENCES `cloud`.`host_pod_ref`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ns_pod_ref__device_id` FOREIGN KEY (`external_load_balancer_device_id`) REFERENCES `external_load_balancer_devices`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`vpc` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) COMMENT 'vpc name',
  `display_text` varchar(255) COMMENT 'vpc display text',
  `cidr` varchar(18) COMMENT 'vpc cidr',
  `vpc_offering_id` bigint unsigned NOT NULL COMMENT 'vpc offering id that this vpc is created from',
  `zone_id` bigint unsigned NOT NULL COMMENT 'the id of the zone this Vpc belongs to',
  `state` varchar(32) NOT NULL COMMENT 'state of the VP (can be Enabled and Disabled)',
  `domain_id` bigint unsigned NOT NULL COMMENT 'domain the vpc belongs to',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner of this vpc',
  `network_domain` varchar(255) COMMENT 'network domain',
  `removed` datetime COMMENT 'date removed if not null',
  `created` datetime NOT NULL COMMENT 'date created',
  `restart_required` int(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 if restart is required for the VPC',
  PRIMARY KEY  (`id`),
  INDEX `i_vpc__removed`(`removed`),
  CONSTRAINT `fk_vpc__zone_id` FOREIGN KEY `fk_vpc__zone_id` (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_vpc__vpc_offering_id` FOREIGN KEY (`vpc_offering_id`) REFERENCES `vpc_offerings`(`id`),
  CONSTRAINT `fk_vpc__account_id` FOREIGN KEY `fk_vpc__account_id` (`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_vpc__domain_id` FOREIGN KEY `fk_vpc__domain_id` (`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE `cloud`.`vpc_offerings` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40) NOT NULL,
  `unique_name` varchar(64) UNIQUE COMMENT 'unique name of the vpc offering',
  `name` varchar(255) COMMENT 'vpc name',
  `display_text` varchar(255) COMMENT 'display text',
  `state` char(32) COMMENT 'state of the vpc offering that has Disabled value by default',
  `default` int(1) unsigned NOT NULL DEFAULT 0 COMMENT '1 if vpc offering is default',
  `removed` datetime COMMENT 'date removed if not null',
  `created` datetime NOT NULL COMMENT 'date created',
  `service_offering_id` bigint unsigned COMMENT 'service offering id that virtual router is tied to',
  PRIMARY KEY  (`id`),
  INDEX `i_vpc__removed`(`removed`),
  CONSTRAINT `fk_vpc_offerings__service_offering_id` FOREIGN KEY `fk_vpc_offerings__service_offering_id` (`service_offering_id`) REFERENCES `service_offering`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE  `cloud`.`vpc_offering_service_map` (
  `id` bigint unsigned NOT NULL auto_increment,
  `vpc_offering_id` bigint unsigned NOT NULL COMMENT 'vpc_offering_id',
  `service` varchar(255) NOT NULL COMMENT 'service',
  `provider` varchar(255) COMMENT 'service provider',
  `created` datetime COMMENT 'date created',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_vpc_offering_service_map__vpc_offering_id` FOREIGN KEY(`vpc_offering_id`) REFERENCES `vpc_offerings`(`id`) ON DELETE CASCADE,
  UNIQUE (`vpc_offering_id`, `service`, `provider`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE `cloud`.`router_network_ref` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `router_id` bigint unsigned NOT NULL COMMENT 'router id',
  `network_id` bigint unsigned NOT NULL COMMENT 'network id',
  `guest_type` char(32) COMMENT 'type of guest network that can be shared or isolated',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_router_network_ref__router_id` FOREIGN KEY (`router_id`) REFERENCES `domain_router`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_router_network_ref__networks_id` FOREIGN KEY (`network_id`) REFERENCES `networks`(`id`) ON DELETE CASCADE,
  UNIQUE `i_router_network_ref__router_id__network_id`(`router_id`, `network_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE `cloud`.`vpc_gateways` (
  `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(40),
  `ip4_address` char(40) COMMENT 'ip4 address of the gateway',
  `netmask` varchar(15) COMMENT 'netmask of the gateway',
  `gateway` varchar(15) COMMENT 'gateway',
  `vlan_tag` varchar(255),
  `type` varchar(32) COMMENT 'type of gateway; can be Public/Private/Vpn',
  `network_id` bigint unsigned NOT NULL COMMENT 'network id vpc gateway belongs to',
  `vpc_id` bigint unsigned NOT NULL COMMENT 'id of the vpc the gateway belongs to',
  `zone_id` bigint unsigned NOT NULL COMMENT 'id of the zone the gateway belongs to',
  `created` datetime COMMENT 'date created',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner id',
  `domain_id` bigint unsigned NOT NULL COMMENT 'domain id',
  `state` varchar(32) NOT NULL COMMENT 'what state the vpc gateway in',
  `removed` datetime COMMENT 'date removed if not null',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_vpc_gateways__network_id` FOREIGN KEY `fk_vpc_gateways__network_id`(`network_id`) REFERENCES `networks`(`id`),
  CONSTRAINT `fk_vpc_gateways__vpc_id` FOREIGN KEY `fk_vpc_gateways__vpc_id`(`vpc_id`) REFERENCES `vpc`(`id`),
  CONSTRAINT `fk_vpc_gateways__zone_id` FOREIGN KEY `fk_vpc_gateways__zone_id`(`zone_id`) REFERENCES `data_center`(`id`),
  CONSTRAINT `fk_vpc_gateways__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_vpc_gateways__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_vpc_gateways__uuid` UNIQUE (`uuid`),
  INDEX `i_vpc_gateways__removed`(`removed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`private_ip_address` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `ip_address` char(40) NOT NULL COMMENT 'ip address',
  `network_id` bigint unsigned NOT NULL COMMENT 'id of the network ip belongs to',
  `reservation_id` char(40) COMMENT 'reservation id',
  `mac_address` varchar(17) COMMENT 'mac address',
  `vpc_id` bigint unsigned COMMENT 'vpc this ip belongs to',
  `taken` datetime COMMENT 'Date taken',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_private_ip_address__vpc_id` FOREIGN KEY `fk_private_ip_address__vpc_id`(`vpc_id`) REFERENCES `vpc`(`id`),
  CONSTRAINT `fk_private_ip_address__network_id` FOREIGN KEY (`network_id`) REFERENCES `networks` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


CREATE TABLE `cloud`.`static_routes` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40),
  `vpc_gateway_id` bigint unsigned COMMENT 'id of the corresponding ip address',
  `cidr` varchar(18) COMMENT 'cidr for the static route',
  `state` char(32) NOT NULL COMMENT 'current state of this rule',
  `vpc_id` bigint unsigned COMMENT 'vpc the firewall rule is associated with',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner id',
  `domain_id` bigint unsigned NOT NULL COMMENT 'domain id',
  `created` datetime COMMENT 'Date created',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_static_routes__vpc_gateway_id` FOREIGN KEY(`vpc_gateway_id`) REFERENCES `vpc_gateways`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_static_routes__vpc_id` FOREIGN KEY (`vpc_id`) REFERENCES `vpc`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_static_routes__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_static_routes__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`) ON DELETE CASCADE,
  CONSTRAINT `uc_static_routes__uuid` UNIQUE (`uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`resource_tags` (
  `id` bigint unsigned NOT NULL auto_increment COMMENT 'id',
  `uuid` varchar(40),
  `key` varchar(255),
  `value` varchar(255),
  `resource_id` bigint unsigned NOT NULL,
  `resource_uuid` varchar(40),
  `resource_type` varchar(255),
  `customer` varchar(255),
  `domain_id` bigint unsigned NOT NULL COMMENT 'foreign key to domain id',
  `account_id` bigint unsigned NOT NULL COMMENT 'owner of this network',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_tags__account_id` FOREIGN KEY(`account_id`) REFERENCES `account`(`id`),
  CONSTRAINT `fk_tags__domain_id` FOREIGN KEY(`domain_id`) REFERENCES `domain`(`id`),
  UNIQUE `i_tags__resource_id__resource_type__key`(`resource_id`, `resource_type`, `key`),
  CONSTRAINT `uc_resource_tags__uuid` UNIQUE (`uuid`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`external_nicira_nvp_devices` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `uuid` varchar(255) UNIQUE,
  `physical_network_id` bigint unsigned NOT NULL COMMENT 'id of the physical network in to which nicira nvp device is added',
  `provider_name` varchar(255) NOT NULL COMMENT 'Service Provider name corresponding to this nicira nvp device',
  `device_name` varchar(255) NOT NULL COMMENT 'name of the nicira nvp device',
  `host_id` bigint unsigned NOT NULL COMMENT 'host id corresponding to the external nicira nvp device',
  PRIMARY KEY  (`id`),
  CONSTRAINT `fk_external_nicira_nvp_devices__host_id` FOREIGN KEY (`host_id`) REFERENCES `host`(`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_external_nicira_nvp_devices__physical_network_id` FOREIGN KEY (`physical_network_id`) REFERENCES `physical_network`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`nicira_nvp_nic_map` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'id',
  `logicalswitch` varchar(255) NOT NULL COMMENT 'nicira uuid of logical switch this port is provisioned on',
  `logicalswitchport` varchar(255) UNIQUE COMMENT 'nicira uuid of this logical switch port',
  `nic` varchar(255) UNIQUE COMMENT 'cloudstack uuid of the nic connected to this logical switch port',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_nicira_nvp_nic_map__nic` FOREIGN KEY(`nic`) REFERENCES `nics`(`uuid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`ftctl_protection` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `primary_vm_id` bigint unsigned NOT NULL COMMENT 'Primary VM managed by FTCTL protection',
  `secondary_vm_id` bigint unsigned NULL COMMENT 'Cloud-provisioned standby VM, when available',
  `secondary_vm_name` varchar(255) NULL COMMENT 'FTCTL standby VM name',
  `peer_host_id` bigint unsigned NULL COMMENT 'Peer KVM host used by FTCTL',
  `target_storage_pool_id` bigint unsigned NULL COMMENT 'Target primary storage pool for standby volumes',
  `mode` varchar(32) NOT NULL COMMENT 'FTCTL protection mode',
  `backend_mode` varchar(64) NULL COMMENT 'FTCTL backend mode',
  `provisioning_backend` varchar(64) NOT NULL DEFAULT 'libvirt-managed' COMMENT 'Protection resource provisioning owner',
  `fencing_policy` varchar(64) NULL COMMENT 'FTCTL fencing policy',
  `xcolo_port_allocation_mode` varchar(16) NULL COMMENT 'FTCTL XCOLO port allocation mode',
  `xcolo_port_slot` int NULL COMMENT 'FTCTL XCOLO automatic port allocation slot',
  `admin_state` varchar(64) NULL,
  `provisioning_state` varchar(64) NULL,
  `protection_state` varchar(64) NULL,
  `transport_state` varchar(64) NULL,
  `active_side` varchar(64) NULL,
  `fencing_state` varchar(64) NULL,
  `last_error` varchar(1024) NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ftctl_protection__uuid` (`uuid`),
  KEY `i_ftctl_protection__primary_vm_id` (`primary_vm_id`),
  KEY `i_ftctl_protection__secondary_vm_id` (`secondary_vm_id`),
  KEY `i_ftctl_protection__peer_host_id` (`peer_host_id`),
  KEY `i_ftctl_protection__target_storage_pool_id` (`target_storage_pool_id`),
  CONSTRAINT `fk_ftctl_protection__primary_vm_id` FOREIGN KEY (`primary_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ftctl_protection__secondary_vm_id` FOREIGN KEY (`secondary_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_ftctl_protection__peer_host_id` FOREIGN KEY (`peer_host_id`) REFERENCES `host` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_ftctl_protection__target_storage_pool_id` FOREIGN KEY (`target_storage_pool_id`) REFERENCES `storage_pool` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`ftctl_protection_volume` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `protection_id` bigint unsigned NOT NULL COMMENT 'FTCTL protection relationship id',
  `primary_volume_id` bigint unsigned NOT NULL COMMENT 'Primary VM volume',
  `secondary_volume_id` bigint unsigned NULL COMMENT 'Cloud-provisioned standby volume, when available',
  `primary_disk_path` varchar(2048) NULL COMMENT 'Primary disk path used by FTCTL',
  `secondary_disk_path` varchar(2048) NULL COMMENT 'Secondary disk path used by FTCTL',
  `disk_label` varchar(255) NULL COMMENT 'Stable disk label for FTCTL disk map',
  `replication_state` varchar(64) NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  KEY `i_ftctl_protection_volume__protection_id` (`protection_id`),
  KEY `i_ftctl_protection_volume__primary_volume_id` (`primary_volume_id`),
  KEY `i_ftctl_protection_volume__secondary_volume_id` (`secondary_volume_id`),
  CONSTRAINT `fk_ftctl_protection_volume__protection_id` FOREIGN KEY (`protection_id`) REFERENCES `ftctl_protection` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ftctl_protection_volume__primary_volume_id` FOREIGN KEY (`primary_volume_id`) REFERENCES `volumes` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ftctl_protection_volume__secondary_volume_id` FOREIGN KEY (`secondary_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_site` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(1024) NULL,
  `site_type` varchar(64) NOT NULL,
  `hypervisor_type` varchar(64) NOT NULL,
  `endpoint` varchar(2048) NULL,
  `credential_ref` varchar(255) NULL,
  `credential_id` bigint unsigned NULL,
  `zone_id` bigint unsigned NULL,
  `zone_external_id` varchar(255) NULL,
  `zone_name` varchar(255) NULL,
  `vmware_datacenter_id` bigint unsigned NULL,
  `vmware_datacenter_external_id` varchar(255) NULL,
  `vmware_datacenter_name` varchar(255) NULL,
  `state` varchar(64) NULL,
  `effective_mode` varchar(32) NULL,
  `incremental_verified` tinyint(1) NULL,
  `metrics_estimated` tinyint(1) NULL,
  `virtual_bytes` bigint unsigned NULL,
  `changed_bytes` bigint unsigned NULL,
  `source_read_bytes` bigint unsigned NULL,
  `target_written_bytes` bigint unsigned NULL,
  `transfer_payload_bytes` bigint unsigned NULL,
  `changed_extent_count` bigint unsigned NULL,
  `duration_ms` bigint unsigned NULL,
  `throughput_bps` bigint unsigned NULL,
  `baseline_generation` bigint unsigned NULL,
  `cycle_token` varchar(255) NULL,
  `health_state` varchar(64) NULL,
  `capabilities_json` text NULL,
  `last_checked` datetime NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_site__uuid` (`uuid`),
  KEY `i_dr_site__name` (`name`),
  KEY `i_dr_site__credential_id` (`credential_id`),
  KEY `i_dr_site__zone_id` (`zone_id`),
  CONSTRAINT `fk_dr_site__zone_id` FOREIGN KEY (`zone_id`) REFERENCES `data_center` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_site_pair` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) NOT NULL,
  `source_site_id` bigint unsigned NOT NULL,
  `target_site_id` bigint unsigned NOT NULL,
  `direction` varchar(64) NOT NULL,
  `state` varchar(64) NULL,
  `legacy_dr_cluster_id` bigint unsigned NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_site_pair__uuid` (`uuid`),
  KEY `i_dr_site_pair__source_site_id` (`source_site_id`),
  KEY `i_dr_site_pair__target_site_id` (`target_site_id`),
  KEY `i_dr_site_pair__legacy_dr_cluster_id` (`legacy_dr_cluster_id`),
  CONSTRAINT `fk_dr_site_pair__source_site_id` FOREIGN KEY (`source_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_site_pair__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_plan` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `name` varchar(255) NOT NULL,
  `description` varchar(1024) NULL,
  `source_site_id` bigint unsigned NOT NULL,
  `target_site_id` bigint unsigned NOT NULL,
  `source_vm_id` bigint unsigned NULL,
  `source_external_ref` varchar(2048) NULL,
  `direction` varchar(64) NOT NULL,
  `engine_type` varchar(64) NULL,
  `engine_binding_type` varchar(64) NULL,
  `engine_binding_id` bigint unsigned NULL,
  `state` varchar(64) NULL,
  `admin_state` varchar(64) NULL,
  `active_side` varchar(16) NULL,
  `rpo_seconds` int NULL,
  `rto_seconds` int NULL,
  `schedule_json` text NULL,
  `policy_json` text NULL,
  `mapping_json` text NULL,
  `quiesce_policy_json` text NULL,
  `source_worker_host_id` bigint unsigned NULL,
  `target_worker_host_id` bigint unsigned NULL,
  `coordinator_worker_host_id` bigint unsigned NULL,
  `protection_group_uuid` varchar(40) NULL,
  `protection_group_name` varchar(255) NULL,
  `protection_group_order` int NULL,
  `protection_group_max_parallel` int NULL,
  `protection_group_quiesce_required` tinyint(1) NULL,
  `last_source_checkpoint_at` datetime NULL,
  `last_target_durable_at` datetime NULL,
  `target_ready_at` datetime NULL,
  `target_ready_rpo_seconds` int NULL,
  `last_run_id` bigint unsigned NULL,
  `last_error_code` varchar(128) NULL,
  `last_error_message` text NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_plan__uuid` (`uuid`),
  KEY `i_dr_plan__source_site_id` (`source_site_id`),
  KEY `i_dr_plan__target_site_id` (`target_site_id`),
  KEY `i_dr_plan__source_vm_id` (`source_vm_id`),
  KEY `i_dr_plan__source_site_external_ref_removed` (`source_site_id`, `source_external_ref`(255), `removed`),
  KEY `i_dr_plan__engine_binding` (`engine_binding_type`, `engine_binding_id`),
  KEY `i_dr_plan__state` (`state`),
  CONSTRAINT `fk_dr_plan__source_site_id` FOREIGN KEY (`source_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_plan__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_plan__source_vm_id` FOREIGN KEY (`source_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_restore_point` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `run_id` bigint unsigned NULL,
  `checkpoint_sequence` bigint unsigned NULL,
  `checkpoint_cycle_type` varchar(32) NULL,
  `checkpoint_ref_hash` binary(32) NULL,
  `source_snapshot_ref` varchar(2048) NULL,
  `source_created` datetime NULL,
  `target_ready_at` datetime NULL,
  `source_rpo_seconds` int NULL,
  `target_ready_rpo_seconds` int NULL,
  `consistency_level` varchar(64) NULL,
  `restore_point_type` varchar(64) NULL,
  `state` varchar(64) NULL,
  `requested_mode` varchar(32) NULL,
  `effective_mode` varchar(32) NULL,
  `automatic_reseed` tinyint(1) NULL,
  `mode_decision_code` varchar(128) NULL,
  `reseed_reason` varchar(128) NULL,
  `invalid_baseline_disk_count` int unsigned NULL,
  `incremental_verified` tinyint(1) NULL,
  `metrics_estimated` tinyint(1) NULL,
  `virtual_bytes` bigint unsigned NULL,
  `changed_bytes` bigint unsigned NULL,
  `source_read_bytes` bigint unsigned NULL,
  `target_written_bytes` bigint unsigned NULL,
  `transfer_payload_bytes` bigint unsigned NULL,
  `changed_extent_count` bigint unsigned NULL,
  `duration_ms` bigint unsigned NULL,
  `throughput_bps` bigint unsigned NULL,
  `baseline_generation` bigint unsigned NULL,
  `cycle_token` varchar(255) NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_restore_point__uuid` (`uuid`),
  UNIQUE KEY `uk_dr_restore_point__plan_checkpoint_hash` (`plan_id`, `checkpoint_ref_hash`),
  KEY `i_dr_restore_point__plan_id` (`plan_id`),
  KEY `i_dr_restore_point__target_ready_at` (`target_ready_at`),
  KEY `i_dr_restore_point__plan_ready_removed` (`plan_id`, `target_ready_at`, `removed`),
  CONSTRAINT `fk_dr_restore_point__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_restore_point_artifact` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `restore_point_id` bigint unsigned NOT NULL,
  `artifact_type` varchar(64) NOT NULL,
  `artifact_ref` varchar(2048) NOT NULL,
  `storage_pool_id` bigint unsigned NULL,
  `datastore_ref` varchar(2048) NULL,
  `format` varchar(64) NULL,
  `size_bytes` bigint NULL,
  `checksum` varchar(255) NULL,
  `parent_artifact_id` bigint unsigned NULL,
  `state` varchar(64) NULL,
  `details_json` text NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_restore_point_artifact__uuid` (`uuid`),
  KEY `i_dr_restore_point_artifact__restore_point_id` (`restore_point_id`),
  KEY `i_dr_restore_point_artifact__storage_pool_id` (`storage_pool_id`),
  KEY `i_dr_restore_point_artifact__parent_artifact_id` (`parent_artifact_id`),
  CONSTRAINT `fk_dr_rp_artifact__restore_point_id` FOREIGN KEY (`restore_point_id`) REFERENCES `dr_restore_point` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_rp_artifact__storage_pool_id` FOREIGN KEY (`storage_pool_id`) REFERENCES `storage_pool` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_dr_rp_artifact__parent_artifact_id` FOREIGN KEY (`parent_artifact_id`) REFERENCES `dr_restore_point_artifact` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_replica` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `target_site_id` bigint unsigned NOT NULL,
  `target_vm_id` bigint unsigned NULL,
  `target_external_ref` varchar(2048) NULL,
  `target_vm_name` varchar(255) NULL,
  `state` varchar(64) NULL,
  `power_state` varchar(64) NULL,
  `hypervisor_type` varchar(64) NULL,
  `active_side` varchar(64) NULL,
  `runtime_state_json` text NULL,
  `ownership_generation` bigint unsigned NOT NULL DEFAULT 1,
  `ownership_state` varchar(32) NULL,
  `materialization_digest` char(64) NULL,
  `power_state_observed_at` datetime NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_replica__uuid` (`uuid`),
  KEY `i_dr_replica__plan_id` (`plan_id`),
  KEY `i_dr_replica__target_site_id` (`target_site_id`),
  KEY `i_dr_replica__target_vm_id` (`target_vm_id`),
  CONSTRAINT `fk_dr_replica__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_replica__target_site_id` FOREIGN KEY (`target_site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_replica__target_vm_id` FOREIGN KEY (`target_vm_id`) REFERENCES `vm_instance` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_replica_disk` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `replica_id` bigint unsigned NOT NULL,
  `source_volume_id` bigint unsigned NULL,
  `target_volume_id` bigint unsigned NULL,
  `source_disk_ref` varchar(2048) NULL,
  `target_disk_ref` varchar(2048) NULL,
  `disk_label` varchar(255) NULL,
  `format` varchar(64) NULL,
  `state` varchar(64) NULL,
  `size_bytes` bigint NULL,
  `details_json` text NULL,
  `target_claim_id` bigint unsigned NULL,
  `artifact_uuid` varchar(40) NULL,
  `locator_hash` char(64) NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_replica_disk__uuid` (`uuid`),
  KEY `i_dr_replica_disk__replica_id` (`replica_id`),
  KEY `i_dr_replica_disk__source_volume_id` (`source_volume_id`),
  KEY `i_dr_replica_disk__target_volume_id` (`target_volume_id`),
  CONSTRAINT `fk_dr_replica_disk__replica_id` FOREIGN KEY (`replica_id`) REFERENCES `dr_replica` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_replica_disk__source_volume_id` FOREIGN KEY (`source_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_dr_replica_disk__target_volume_id` FOREIGN KEY (`target_volume_id`) REFERENCES `volumes` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_run` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `run_type` varchar(64) NOT NULL,
  `state` varchar(64) NULL,
  `idempotency_key` varchar(255) NULL,
  `request_json` text NULL,
  `requested_by_user_id` bigint unsigned NULL,
  `async_job_id` bigint unsigned NULL,
  `external_job_ref` varchar(2048) NULL,
  `engine_accepted` tinyint(1) NOT NULL DEFAULT 0,
  `accepted_at` datetime NULL,
  `accepted_cycle_sequence` bigint unsigned NULL,
  `accepted_cycle_token` varchar(255) NULL,
  `dispatch_started` datetime NULL,
  `dispatch_completed` datetime NULL,
  `projection_state` varchar(64) NULL,
  `projection_checked` datetime NULL,
  `retryable` tinyint(1) NOT NULL DEFAULT 0,
  `retry_count` int NOT NULL DEFAULT 0,
  `retry_after_seconds` int NULL,
  `next_retry_at` datetime NULL,
  `last_status_json` mediumtext NULL,
  `current_step_name` varchar(255) NULL,
  `error_code` varchar(128) NULL,
  `error_message` text NULL,
  `terminal_source` varchar(32) NULL,
  `terminal_version` int unsigned NULL,
  `terminal_authoritative` tinyint(1) NOT NULL DEFAULT 0,
  `started` datetime NULL,
  `completed` datetime NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_run__uuid` (`uuid`),
  KEY `i_dr_run__plan_id` (`plan_id`),
  KEY `i_dr_run__plan_created` (`plan_id`, `created`),
  KEY `i_dr_run__plan_state_completed` (`plan_id`, `state`, `completed`),
  KEY `i_dr_run__idempotency` (`plan_id`, `idempotency_key`),
  KEY `i_dr_run__async_job_id` (`async_job_id`),
  CONSTRAINT `fk_dr_run__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_dr_run__requested_by_user_id` FOREIGN KEY (`requested_by_user_id`) REFERENCES `user` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_dr_run__async_job_id` FOREIGN KEY (`async_job_id`) REFERENCES `async_job` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_run_step` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `run_id` bigint unsigned NOT NULL,
  `step_name` varchar(255) NOT NULL,
  `step_order` int NOT NULL DEFAULT 0,
  `state` varchar(64) NULL,
  `progress` int NULL,
  `started` datetime NULL,
  `completed` datetime NULL,
  `details_json` text NULL,
  `error_code` varchar(128) NULL,
  `error_message` text NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_run_step__uuid` (`uuid`),
  KEY `i_dr_run_step__run_id` (`run_id`),
  KEY `i_dr_run_step__run_order` (`run_id`, `step_order`),
  KEY `i_dr_run_step__run_name_removed` (`run_id`, `step_name`, `removed`),
  CONSTRAINT `fk_dr_run_step__run_id` FOREIGN KEY (`run_id`) REFERENCES `dr_run` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_event` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NULL,
  `run_id` bigint unsigned NULL,
  `event_type` varchar(128) NOT NULL,
  `severity` varchar(32) NOT NULL,
  `source` varchar(64) NOT NULL,
  `message` text NULL,
  `details_json` text NULL,
  `created` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_event__uuid` (`uuid`),
  KEY `i_dr_event__plan_id` (`plan_id`),
  KEY `i_dr_event__plan_created_id` (`plan_id`, `created`, `id`),
  KEY `i_dr_event__run_id` (`run_id`),
  KEY `i_dr_event__created` (`created`),
  CONSTRAINT `fk_dr_event__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_dr_event__run_id` FOREIGN KEY (`run_id`) REFERENCES `dr_run` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_plan_view_cache` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `plan_id` bigint unsigned NOT NULL,
  `snapshot_version` int unsigned NOT NULL DEFAULT 1,
  `snapshot_json` mediumtext NOT NULL,
  `projection_state` varchar(32) NOT NULL DEFAULT 'READY',
  `last_error` varchar(4096) DEFAULT NULL,
  `last_refresh_error_code` varchar(255) DEFAULT NULL,
  `last_refresh_error_message` varchar(4096) DEFAULT NULL,
  `last_refresh_failed_at` datetime DEFAULT NULL,
  `generated_at` datetime NOT NULL,
  `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_plan_view_cache__plan_id` (`plan_id`),
  KEY `i_dr_plan_view_cache__generated_at` (`generated_at`),
  CONSTRAINT `fk_dr_plan_view_cache__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`dr_plan_runtime` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `plan_id` bigint unsigned NOT NULL,
    `engine_run_uuid` varchar(40) DEFAULT NULL,
    `runtime_generation` bigint unsigned NOT NULL DEFAULT 0,
    `scheduler_state` varchar(32) DEFAULT NULL,
    `scheduler_desired_state` varchar(32) NOT NULL DEFAULT 'STOPPED',
    `scheduler_service_unit` varchar(255) DEFAULT NULL,
    `scheduler_unit_active_state` varchar(32) DEFAULT NULL,
    `scheduler_unit_sub_state` varchar(32) DEFAULT NULL,
    `scheduler_unit_main_pid` bigint unsigned DEFAULT NULL,
    `scheduler_cgroup` varchar(512) DEFAULT NULL,
    `scheduler_recovery_state` varchar(32) NOT NULL DEFAULT 'NONE',
    `scheduler_recovery_trigger` varchar(64) DEFAULT NULL,
    `scheduler_recovery_attempts` int unsigned NOT NULL DEFAULT 0,
    `scheduler_recovery_error_code` varchar(128) DEFAULT NULL,
    `scheduler_recovery_error_message` varchar(4096) DEFAULT NULL,
    `scheduler_recovered_at` datetime DEFAULT NULL,
    `scheduler_pid_alive` tinyint(1) NOT NULL DEFAULT 0,
    `scheduler_session_uuid` varchar(40) DEFAULT NULL,
    `scheduler_lease_epoch` bigint unsigned NOT NULL DEFAULT 0,
    `authority_sequence` bigint unsigned NOT NULL DEFAULT 0,
    `plan_cycle_sequence` bigint unsigned DEFAULT NULL,
    `scheduler_health_state` varchar(32) DEFAULT NULL,
    `replication_activity_state` varchar(32) DEFAULT NULL,
    `active_worker_run_uuid` varchar(40) DEFAULT NULL,
    `active_worker_pid` bigint unsigned DEFAULT NULL,
    `active_worker_start_ticks` bigint unsigned DEFAULT NULL,
    `worker_heartbeat_at` datetime DEFAULT NULL,
    `control_request_run_uuid` varchar(40) DEFAULT NULL,
    `owner_matched` tinyint(1) NOT NULL DEFAULT 0,
    `worker_state` varchar(32) DEFAULT NULL,
    `worker_identity_state` varchar(32) DEFAULT NULL,
    `worker_liveness_state` varchar(32) DEFAULT NULL,
    `worker_launch_nonce` varchar(64) DEFAULT NULL,
    `worker_generation` bigint unsigned DEFAULT NULL,
    `current_cycle_sequence` bigint unsigned DEFAULT NULL,
    `current_cycle_state` varchar(32) DEFAULT NULL,
    `current_cycle_mode` varchar(32) DEFAULT NULL,
    `baseline_state` varchar(32) DEFAULT NULL,
    `reseed_reason` varchar(128) DEFAULT NULL,
    `last_mode_decision_code` varchar(128) DEFAULT NULL,
    `consecutive_automatic_reseed_count` int unsigned NOT NULL DEFAULT 0,
    `latest_completed_cycle_sequence` bigint unsigned DEFAULT NULL,
    `latest_completed_incremental_verified` tinyint(1) DEFAULT NULL,
    `projection_integrity_state` varchar(32) DEFAULT NULL,
    `projection_integrity_code` varchar(128) DEFAULT NULL,
    `projection_integrity_sequence` bigint unsigned DEFAULT NULL,
    `transfer_activity_state` varchar(32) DEFAULT NULL,
    `transfer_payload_bytes` bigint unsigned DEFAULT NULL,
    `owned_process_count` int unsigned NOT NULL DEFAULT 0,
    `runtime_endpoints_drained` tinyint(1) NOT NULL DEFAULT 0,
    `reconciliation_state` varchar(32) DEFAULT NULL,
    `reconciliation_run_uuid` varchar(40) DEFAULT NULL,
    `reconciliation_checks` int unsigned NOT NULL DEFAULT 0,
    `terminal_source` varchar(32) DEFAULT NULL,
    `terminal_version` int unsigned DEFAULT NULL,
    `terminal_authoritative` tinyint(1) NOT NULL DEFAULT 0,
    `transfer_progress_schema_version` int unsigned DEFAULT NULL,
    `transfer_cycle_sequence` bigint unsigned DEFAULT NULL,
    `transfer_sample_sequence` bigint unsigned DEFAULT NULL,
    `transfer_phase` varchar(32) DEFAULT NULL,
    `transfer_mode` varchar(32) DEFAULT NULL,
    `transfer_bytes_total` bigint unsigned DEFAULT NULL,
    `transfer_bytes_processed` bigint unsigned DEFAULT NULL,
    `transfer_source_read_bytes` bigint unsigned DEFAULT NULL,
    `transfer_target_written_bytes` bigint unsigned DEFAULT NULL,
    `transfer_verified_bytes` bigint unsigned DEFAULT NULL,
    `transfer_percent` decimal(5,2) DEFAULT NULL,
    `transfer_throughput_bps` bigint unsigned DEFAULT NULL,
    `transfer_eta_seconds` bigint unsigned DEFAULT NULL,
    `transfer_current_disk_index` int unsigned DEFAULT NULL,
    `transfer_disk_count` int unsigned DEFAULT NULL,
    `transfer_progress_estimated` tinyint(1) DEFAULT NULL,
    `transfer_progress_sampled_at` datetime DEFAULT NULL,
    `transfer_progress_stale` tinyint(1) DEFAULT NULL,
    `nbd_teardown_state` varchar(32) DEFAULT NULL,
    `nbd_quarantined_device_count` int unsigned NOT NULL DEFAULT 0,
    `nbd_teardown_error_code` varchar(128) DEFAULT NULL,
    `nbd_teardown_error_message` varchar(4096) DEFAULT NULL,
    `protection_state` varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    `freshness_state` varchar(32) NOT NULL DEFAULT 'UNKNOWN',
    `scheduler_next_run_at` datetime DEFAULT NULL,
    `scheduler_execution_budget_seconds` int unsigned DEFAULT NULL,
    `scheduler_cycle_wall_duration_seconds` int unsigned DEFAULT NULL,
    `last_status_at` datetime DEFAULT NULL,
    `last_source_checkpoint_at` datetime DEFAULT NULL,
    `last_target_durable_at` datetime DEFAULT NULL,
    `rpo_age_seconds` bigint unsigned DEFAULT NULL,
    `rpo_overdue` tinyint(1) NOT NULL DEFAULT 0,
    `error_code` varchar(128) DEFAULT NULL,
    `error_message` varchar(4096) DEFAULT NULL,
    `status_json` mediumtext,
    `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated` datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_plan_runtime__plan_id` (`plan_id`),
    KEY `i_dr_plan_runtime__state_updated` (`protection_state`, `freshness_state`, `updated`),
    KEY `i_dr_plan_runtime__nbd_teardown_state` (`nbd_teardown_state`, `updated`),
    CONSTRAINT `fk_dr_plan_runtime__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`dr_sync_cycle` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `uuid` varchar(40) NOT NULL,
    `plan_id` bigint unsigned NOT NULL,
    `run_id` bigint unsigned DEFAULT NULL,
    `engine_run_uuid` varchar(40) NOT NULL,
    `scheduler_session_uuid` varchar(40) DEFAULT NULL,
    `scheduler_lease_epoch` bigint unsigned DEFAULT NULL,
    `authority_sequence` bigint unsigned DEFAULT NULL,
    `sequence` bigint unsigned NOT NULL,
    `cycle_token` varchar(255) DEFAULT NULL,
    `requested_mode` varchar(32) DEFAULT NULL,
    `effective_mode` varchar(32) DEFAULT NULL,
    `state` varchar(32) NOT NULL,
    `commit_state` varchar(32) DEFAULT NULL,
    `baseline_generation` bigint unsigned DEFAULT NULL,
    `baseline_state` varchar(32) DEFAULT NULL,
    `reseed_reason` varchar(128) DEFAULT NULL,
    `automatic_reseed` tinyint(1) DEFAULT NULL,
    `mode_decision_code` varchar(128) DEFAULT NULL,
    `invalid_baseline_disk_count` int unsigned DEFAULT NULL,
    `incremental_verified` tinyint(1) DEFAULT NULL,
    `metrics_estimated` tinyint(1) DEFAULT NULL,
    `virtual_bytes` bigint unsigned DEFAULT NULL,
    `changed_bytes` bigint unsigned DEFAULT NULL,
    `source_read_bytes` bigint unsigned DEFAULT NULL,
    `target_written_bytes` bigint unsigned DEFAULT NULL,
    `transfer_payload_bytes` bigint unsigned DEFAULT NULL,
    `changed_extent_count` bigint unsigned DEFAULT NULL,
    `duration_ms` bigint unsigned DEFAULT NULL,
    `throughput_bps` bigint unsigned DEFAULT NULL,
    `nbd_teardown_state` varchar(32) DEFAULT NULL,
    `nbd_teardown_started_at` datetime DEFAULT NULL,
    `nbd_teardown_completed_at` datetime DEFAULT NULL,
    `nbd_teardown_duration_ms` bigint unsigned DEFAULT NULL,
    `nbd_source_device_count` int unsigned DEFAULT NULL,
    `nbd_target_device_count` int unsigned DEFAULT NULL,
    `nbd_quarantined_device_count` int unsigned NOT NULL DEFAULT 0,
    `nbd_teardown_error_code` varchar(128) DEFAULT NULL,
    `nbd_teardown_error_message` varchar(4096) DEFAULT NULL,
    `source_checkpoint_at` datetime DEFAULT NULL,
    `target_durable_at` datetime DEFAULT NULL,
    `error_code` varchar(128) DEFAULT NULL,
    `error_message` varchar(4096) DEFAULT NULL,
    `started` datetime DEFAULT NULL,
    `completed` datetime DEFAULT NULL,
    `created` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated` datetime DEFAULT NULL,
    `removed` datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dr_sync_cycle__uuid` (`uuid`),
    UNIQUE KEY `uk_dr_sync_cycle__plan_sequence` (`plan_id`, `sequence`),
    KEY `i_dr_sync_cycle__plan_state_updated` (`plan_id`, `state`, `updated`),
    CONSTRAINT `fk_dr_sync_cycle__plan_id` FOREIGN KEY (`plan_id`) REFERENCES `dr_plan` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_dr_sync_cycle__run_id` FOREIGN KEY (`run_id`) REFERENCES `dr_run` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


SET foreign_key_checks = 1;
CREATE TABLE IF NOT EXISTS `cloud`.`dr_cutover_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `uuid` varchar(40) NOT NULL, `plan_id` bigint unsigned NOT NULL,
  `run_id` bigint unsigned NOT NULL, `mode` varchar(16) NOT NULL, `checkpoint_sequence` bigint unsigned,
  `state` varchar(64) NOT NULL, `guest_os_family` varchar(32), `guest_preparation_state` varchar(64),
  `virtio_state` varchar(32), `secure_boot_state` varchar(32), `domain_name` varchar(255),
  `boot_validation_state` varchar(64), `source_fence_state` varchar(32), `source_power_state` varchar(32),
  `manifest_schema_version` varchar(64), `manifest_sha256` varchar(64),
  `target_disk_count` int unsigned, `scheduler_recovery_state` varchar(32),
  `cloud_promotion_state` varchar(32), `target_power_state` varchar(32),
  `target_power_on_at` datetime, `boot_validated_at` datetime,
  `engine_ack_state` varchar(32), `engine_ack_at` datetime,
  `cloud_authority_generation` bigint unsigned,
  `commit_contract_version` varchar(64), `engine_session_id` varchar(255),
  `commit_attempt_id` varchar(64), `commit_envelope_sha256` varchar(64),
  `commit_state` varchar(32), `completed_at` datetime,
  `authority_ended_at` datetime, `authority_ended_by_run_id` bigint unsigned,
  `cleanup_required` tinyint(1) NOT NULL DEFAULT 0,
  `details_json` mediumtext, `error_code` varchar(128), `error_message` varchar(1024),
  `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_cutover_session_uuid` (`uuid`),
  KEY `idx_dr_cutover_session_plan_active` (`plan_id`,`removed`), KEY `idx_dr_cutover_session_run` (`run_id`),
  KEY `idx_dr_cutover_session_plan_state_active` (`plan_id`,`state`,`removed`,`id`),
  KEY `idx_dr_cutover_session_authority_end_run` (`authority_ended_by_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `cloud`.`dr_cutover_disk` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `session_id` bigint unsigned NOT NULL, `disk_index` int unsigned NOT NULL,
  `provider` varchar(32) NOT NULL, `checkpoint_ref` varchar(1024) NOT NULL, `writable_ref` varchar(1024),
  `rollback_ref` varchar(1024), `state` varchar(64) NOT NULL,
  `target_volume_id` bigint unsigned DEFAULT NULL, `target_volume_uuid` varchar(40) DEFAULT NULL,
  `checkpoint_sequence` bigint unsigned DEFAULT NULL, `manifest_sha256` varchar(64) DEFAULT NULL,
  `details_json` mediumtext,
  `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_cutover_disk_session_index` (`session_id`,`disk_index`),
  KEY `idx_dr_cutover_disk_session_active` (`session_id`,`removed`),
  KEY `idx_dr_cutover_disk_target_volume` (`target_volume_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `cloud`.`dr_failback_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL, `run_id` bigint unsigned NOT NULL,
  `engine_session_id` varchar(255) NOT NULL, `checkpoint_sequence` bigint unsigned,
  `authority_generation` bigint unsigned, `state` varchar(64) NOT NULL,
  `acceptance_state` varchar(32), `failure_phase` varchar(64), `failed_component` varchar(128),
  `driver_exit_code` int, `baseline_file_state` varchar(32),
  `operation_intent` varchar(32), `requested_mode` varchar(32), `effective_mode` varchar(32),
  `mode_decision_code` varchar(64), `initial_seed_required` tinyint(1),
  `source_disk_probe_state` varchar(32), `source_disk_count` int,
  `target_writer_probe_state` varchar(32), `estimated_virtual_bytes` bigint unsigned,
  `worker_pid_alive` tinyint(1),
  `target_power_state` varchar(32), `source_power_state` varchar(32),
  `boot_validation_state` varchar(64), `engine_ack_state` varchar(32),
  `commit_attempt_id` varchar(64), `commit_outcome` varchar(32),
  `commit_contract_version` varchar(32), `commit_envelope_sha256` char(64),
  `commit_dispatch_state` varchar(32), `commit_probe_count` int,
  `scheduler_generation` bigint unsigned, `scheduler_ack_generation` bigint unsigned,
  `scheduler_state` varchar(32), `rollback_state` varchar(32),
  `rollback_generation` bigint unsigned, `lifecycle_version` bigint unsigned NOT NULL DEFAULT 0,
  `resume_baseline_checkpoint_sequence` bigint unsigned,
  `required_post_failback_checkpoint_sequence` bigint unsigned,
  `post_failback_checkpoint_sequence` bigint unsigned,
  `replication_direction` varchar(32),
  `provider_pair` varchar(64),
  `baseline_generation` bigint unsigned,
  `baseline_state` varchar(32),
  `tracker_state` varchar(32),
  `writer_state` varchar(32),
  `target_written` tinyint(1),
  `write_verified` tinyint(1),
  `guest_compatibility_state` varchar(64),
  `data_ready_at` datetime, `target_stopped_at` datetime, `source_powered_on_at` datetime,
  `boot_validated_at` datetime, `engine_ack_at` datetime,
  `commit_requested_at` datetime, `commit_verified_at` datetime,
  `commit_dispatched_at` datetime, `commit_probe_deadline_at` datetime,
  `protection_resume_requested_at` datetime, `protection_resume_verified_at` datetime,
  `rollback_requested_at` datetime, `rollback_verified_at` datetime, `last_probe_at` datetime,
  `completed_at` datetime,
  `details_json` mediumtext, `error_code` varchar(128), `error_message` varchar(1024),
  `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_failback_session_uuid` (`uuid`),
  UNIQUE KEY `uk_dr_failback_session_run` (`run_id`),
  KEY `idx_dr_failback_session_plan_active` (`plan_id`,`removed`),
  KEY `idx_dr_failback_session_reconcile` (`state`,`last_probe_at`,`removed`,`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`dr_site_credential` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `site_id` bigint unsigned NOT NULL,
  `credential_type` varchar(64) NOT NULL,
  `endpoint` varchar(2048) NULL,
  `principal` varchar(255) NULL,
  `secret_payload` text NULL,
  `tls_verify` tinyint(1) NOT NULL DEFAULT 1,
  `state` varchar(64) NULL,
  `last_validated` datetime NULL,
  `created` datetime NOT NULL,
  `updated` datetime NULL,
  `removed` datetime NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_site_credential__uuid` (`uuid`),
  KEY `i_dr_site_credential__site_id` (`site_id`),
  KEY `i_dr_site_credential__state` (`state`),
  CONSTRAINT `fk_dr_site_credential__site_id` FOREIGN KEY (`site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_site_health_check` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `site_id` bigint unsigned NOT NULL,
  `site_uuid` varchar(40) NOT NULL,
  `site_name` varchar(255) NOT NULL,
  `site_type` varchar(64) NOT NULL,
  `hypervisor_type` varchar(64) NOT NULL,
  `endpoint` varchar(2048) NULL,
  `credential_id` bigint unsigned NULL,
  `credential_state` varchar(64) NULL,
  `trigger_type` varchar(64) NOT NULL,
  `health_state` varchar(64) NOT NULL,
  `reason_code` varchar(128) NULL,
  `message` text NULL,
  `latency_ms` bigint NULL,
  `checked_at` datetime NOT NULL,
  `management_server_id` bigint unsigned NULL,
  `job_id` varchar(255) NULL,
  `details_json` text NULL,
  `created` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_site_health_check__uuid` (`uuid`),
  KEY `i_dr_site_health_check__site_checked` (`site_id`, `checked_at`),
  KEY `i_dr_site_health_check__state_checked` (`health_state`, `checked_at`),
  KEY `i_dr_site_health_check__trigger_checked` (`trigger_type`, `checked_at`),
  CONSTRAINT `fk_dr_site_health_check__site_id` FOREIGN KEY (`site_id`) REFERENCES `dr_site` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `cloud`.`dr_target_resource_claim` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `replica_id` bigint unsigned NOT NULL,
  `replica_disk_id` bigint unsigned DEFAULT NULL,
  `claim_run_id` bigint unsigned DEFAULT NULL,
  `resource_type` varchar(32) NOT NULL,
  `resource_id` bigint unsigned NOT NULL,
  `resource_uuid` varchar(64) DEFAULT NULL,
  `resource_locator_hash` char(64) DEFAULT NULL,
  `ownership_generation` bigint unsigned NOT NULL DEFAULT 1,
  `claim_state` varchar(32) NOT NULL,
  `active_resource_key` varchar(160) DEFAULT NULL,
  `active_role_key` varchar(160) DEFAULT NULL,
  `manifest_sha256` char(64) DEFAULT NULL,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  `released` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_target_claim_uuid` (`uuid`),
  UNIQUE KEY `uk_dr_target_claim_active_resource` (`active_resource_key`),
  UNIQUE KEY `uk_dr_target_claim_active_role` (`active_role_key`),
  KEY `idx_dr_target_claim_plan` (`plan_id`, `claim_state`),
  KEY `idx_dr_target_claim_replica` (`replica_id`, `claim_state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`dr_test_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `uuid` varchar(40) NOT NULL,
  `plan_id` bigint unsigned NOT NULL, `run_id` bigint unsigned NOT NULL, `cleanup_run_id` bigint unsigned DEFAULT NULL,
  `state` varchar(64) NOT NULL, `network_mode` varchar(32) DEFAULT NULL, `network_id` bigint unsigned DEFAULT NULL,
  `target_vm_id` bigint unsigned DEFAULT NULL, `target_vm_uuid` varchar(40) DEFAULT NULL, `target_vm_name` varchar(255) DEFAULT NULL,
  `checkpoint_sequence` bigint unsigned DEFAULT NULL, `restore_point_ref` varchar(1024) DEFAULT NULL,
  `validation_mode` varchar(32) DEFAULT NULL, `boot_timeout_seconds` int unsigned DEFAULT NULL,
  `artifact_contract_version` varchar(16) DEFAULT NULL, `artifact_manifest` mediumtext, `boot_validation_state` varchar(64) DEFAULT NULL,
  `cleanup_required` tinyint(1) NOT NULL DEFAULT 0, `error_code` varchar(128) DEFAULT NULL, `error_message` varchar(1024) DEFAULT NULL,
  `details_json` mediumtext, `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_test_session_uuid` (`uuid`), KEY `idx_dr_test_session_plan_active` (`plan_id`, `removed`),
  KEY `idx_dr_test_session_run` (`run_id`), KEY `idx_dr_test_session_vm` (`target_vm_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`dr_test_disk` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT, `session_id` bigint unsigned NOT NULL, `disk_index` int NOT NULL,
  `provider` varchar(32) DEFAULT NULL, `artifact_ref` varchar(1024) DEFAULT NULL, `target_volume_id` bigint unsigned DEFAULT NULL,
  `target_volume_uuid` varchar(40) DEFAULT NULL, `state` varchar(64) NOT NULL, `details_json` mediumtext,
  `created` datetime NOT NULL, `updated` datetime NOT NULL, `removed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY `uk_dr_test_disk_session_index` (`session_id`, `disk_index`), KEY `idx_dr_test_disk_volume` (`target_volume_id`),
  CONSTRAINT `fk_dr_test_disk_session` FOREIGN KEY (`session_id`) REFERENCES `dr_test_session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`dr_resource_lease` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `resource_key` varchar(160) NOT NULL,
  `operation_class` varchar(32) NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `run_id` bigint unsigned NOT NULL,
  `state` varchar(32) NOT NULL,
  `expires_at` datetime NOT NULL,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_resource_lease_uuid` (`uuid`),
  KEY `idx_dr_resource_lease_capacity` (`resource_key`, `state`, `expires_at`),
  KEY `idx_dr_resource_lease_run` (`run_id`, `state`, `expires_at`),
  KEY `idx_dr_resource_lease_plan` (`plan_id`, `created`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `cloud`.`dr_group_run` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `uuid` varchar(40) NOT NULL,
  `group_uuid` varchar(40) NOT NULL,
  `group_name` varchar(255) NOT NULL,
  `action` varchar(32) NOT NULL,
  `state` varchar(32) NOT NULL,
  `plan_ids_json` text NOT NULL,
  `progress_json` mediumtext DEFAULT NULL,
  `max_parallel` int NOT NULL DEFAULT 1,
  `quiesce_required` tinyint(1) NOT NULL DEFAULT 0,
  `total_count` int NOT NULL DEFAULT 0,
  `succeeded_count` int NOT NULL DEFAULT 0,
  `failed_count` int NOT NULL DEFAULT 0,
  `created` datetime NOT NULL,
  `updated` datetime NOT NULL,
  `completed` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dr_group_run_uuid` (`uuid`),
  KEY `idx_dr_group_run_group` (`group_uuid`, `created`),
  KEY `idx_dr_group_run_state` (`state`, `updated`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- DR test guest-agent validation survives runtime projection and process restart.
CREATE TABLE IF NOT EXISTS `dr_test_boot_validation` (
 `session_id` bigint unsigned NOT NULL,
 `run_id` bigint unsigned NOT NULL,
 `vm_id` bigint unsigned NOT NULL,
 `state` varchar(32) NOT NULL,
 `started_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 `deadline_at` datetime NOT NULL,
 `next_attempt_at` datetime NOT NULL,
 `attempt_count` int unsigned NOT NULL DEFAULT 0,
 `lease_token` varchar(40) DEFAULT NULL,
 `lease_until` datetime DEFAULT NULL,
 `validated_at` datetime DEFAULT NULL,
 `evidence_json` text,
 PRIMARY KEY (`session_id`), KEY `i_dr_boot_due` (`state`,`next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Durable test cleanup protection restoration; separate from removed test resources.
CREATE TABLE IF NOT EXISTS `dr_test_cleanup_recovery` (
  `test_run_id` bigint unsigned NOT NULL,
  `plan_id` bigint unsigned NOT NULL,
  `cleanup_run_id` bigint unsigned DEFAULT NULL,
  `desired_state` varchar(32) NOT NULL,
  `state` varchar(32) NOT NULL,
  `next_attempt_at` datetime NOT NULL,
  `lease_token` varchar(40) DEFAULT NULL,
  `lease_until` datetime DEFAULT NULL,
  `attempt_count` int NOT NULL DEFAULT 0,
  `last_error` varchar(1024) DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`test_run_id`),
  KEY `i_dr_test_cleanup_due` (`state`,`next_attempt_at`),
  KEY `i_dr_test_cleanup_plan` (`plan_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
