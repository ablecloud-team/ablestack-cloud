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

-- Same-version Europa S9 repair. Preserve existing tables and their runtime records.
-- Each CREATE is safe to repeat after a partial failure; the phase is completed only
-- after the entire script succeeds. Keep these definitions aligned with the normal upgrade.

-- DR test guest-agent validation survives runtime projection and process restart.
CREATE TABLE IF NOT EXISTS `cloud`.`dr_test_boot_validation` (
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
CREATE TABLE IF NOT EXISTS `cloud`.`dr_test_cleanup_recovery` (
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

-- Plan-owned export fencing and historical revocation obligations (#968).
CREATE TABLE IF NOT EXISTS `cloud`.`dr_export_transition` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `plan_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS `cloud`.`dr_export_host_history` (
  `plan_id` BIGINT UNSIGNED NOT NULL,
  `host_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`plan_id`,`host_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- #970: remote site journal keyed by globally unique owning Plan, not local plan id.
CREATE TABLE IF NOT EXISTS `cloud`.`ftctl_dr_reverse_export` (
  `plan_uuid` varchar(40) NOT NULL,
  `journal_json` mediumtext NOT NULL,
  PRIMARY KEY (`plan_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- #980: durable cleanup export phase, not a VM placement binding.
CREATE TABLE IF NOT EXISTS `cloud`.`dr_cleanup_export_resume` (
  `cleanup_run_id` BIGINT UNSIGNED NOT NULL,
  `plan_id` BIGINT UNSIGNED NOT NULL,
  `revoke_generation` BIGINT UNSIGNED NOT NULL,
  `observed_worker_uuid` VARCHAR(40) NOT NULL,
  `disk_fingerprint` CHAR(64) NOT NULL,
  `drained` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`cleanup_run_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

