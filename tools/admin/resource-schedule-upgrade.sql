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
-- KIND, either express or implied. See the License for the
-- specific language governing permissions and limitations
-- under the License.

-- Run only through resource-schedule-upgrade.py, with management stopped.
-- Feature source: 5d0a34b072cf51bfa4ccbb082075adb9d375c637.
ALTER TABLE vm_schedule
    DROP FOREIGN KEY fk_vm_schedule__vm_id,
    DROP INDEX i_vm_schedule__vm_id,
    DROP INDEX i_vm_schedule__enabled_end_date,
    CHANGE COLUMN vm_id resource_id bigint unsigned NOT NULL,
    ADD COLUMN resource_type varchar(64) NOT NULL DEFAULT 'VirtualMachine' AFTER uuid;
RENAME TABLE vm_schedule TO resource_schedule;
ALTER TABLE resource_schedule
    ADD INDEX i_resource_schedule__resource (resource_type, resource_id),
    ADD INDEX i_resource_schedule__enabled_end_date (enabled, end_date);
ALTER TABLE vm_scheduled_job
    DROP FOREIGN KEY fk_vm_scheduled_job__vm_id,
    DROP FOREIGN KEY fk_vm_scheduled_job__vm_schedule_id,
    DROP INDEX i_vm_scheduled_job__vm_id,
    DROP INDEX i_vm_scheduled_job__scheduled_timestamp,
    DROP INDEX vm_schedule_id,
    CHANGE COLUMN vm_id resource_id bigint unsigned NOT NULL,
    CHANGE COLUMN vm_schedule_id schedule_id bigint unsigned NOT NULL,
    ADD COLUMN resource_type varchar(64) NOT NULL DEFAULT 'VirtualMachine' AFTER uuid;
RENAME TABLE vm_scheduled_job TO resource_scheduled_job;
ALTER TABLE resource_scheduled_job
    ADD UNIQUE KEY uc_resource_scheduled_job__schedule_timestamp (schedule_id, scheduled_timestamp),
    ADD INDEX i_resource_scheduled_job__resource (resource_type, resource_id),
    ADD INDEX i_resource_scheduled_job__scheduled_timestamp (scheduled_timestamp),
    ADD CONSTRAINT fk_resource_scheduled_job__schedule_id FOREIGN KEY (schedule_id) REFERENCES resource_schedule(id) ON DELETE CASCADE;
CREATE TABLE resource_schedule_details (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    schedule_id bigint unsigned NOT NULL,
    name varchar(255) NOT NULL,
    value varchar(1024) NOT NULL,
    display tinyint(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    INDEX i_resource_schedule_details__schedule_id (schedule_id),
    CONSTRAINT fk_resource_schedule_details__schedule_id FOREIGN KEY (schedule_id) REFERENCES resource_schedule(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
UPDATE event SET type='SCHEDULE.CREATE' WHERE type='VM.SCHEDULE.CREATE';
UPDATE event SET type='SCHEDULE.UPDATE' WHERE type='VM.SCHEDULE.UPDATE';
UPDATE event SET type='SCHEDULE.DELETE' WHERE type='VM.SCHEDULE.DELETE';
UPDATE configuration SET name='scheduler.jobs.expire.interval' WHERE name='vmscheduler.jobs.expire.interval';
