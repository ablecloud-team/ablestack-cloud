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

-- PR#4699 Drop the procedure `ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` if it already exist.
DROP PROCEDURE IF EXISTS `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING`;

-- PR#4699 Create the procedure `ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` to add guest_os and guest_os_hypervisor mapping.
CREATE PROCEDURE `cloud`.`ADD_GUEST_OS_AND_HYPERVISOR_MAPPING` (
    IN guest_os_category_id bigint(20) unsigned,
    IN guest_os_display_name VARCHAR(255),
    IN guest_os_hypervisor_hypervisor_type VARCHAR(32),
    IN guest_os_hypervisor_hypervisor_version VARCHAR(32),
    IN guest_os_hypervisor_guest_os_name VARCHAR(255)
        )
BEGIN
    DECLARE existing_guest_os_id BIGINT(20) UNSIGNED DEFAULT NULL;

    SELECT id INTO existing_guest_os_id
    FROM cloud.guest_os
    WHERE category_id = guest_os_category_id
      AND display_name = guest_os_display_name
    ORDER BY id
    LIMIT 1;

    IF existing_guest_os_id IS NULL THEN
        INSERT INTO cloud.guest_os (uuid, category_id, display_name, created)
        VALUES (UUID(), guest_os_category_id, guest_os_display_name, now());
        SET existing_guest_os_id = LAST_INSERT_ID();
    END IF;

    IF NOT EXISTS (SELECT 1
                   FROM cloud.guest_os_hypervisor AS hypervisor
                   WHERE hypervisor.hypervisor_type = guest_os_hypervisor_hypervisor_type
                     AND hypervisor.hypervisor_version = guest_os_hypervisor_hypervisor_version
                     AND hypervisor.guest_os_id = existing_guest_os_id
                     AND hypervisor.guest_os_name = guest_os_hypervisor_guest_os_name) THEN
        INSERT INTO cloud.guest_os_hypervisor (uuid, hypervisor_type, hypervisor_version, guest_os_name, guest_os_id, created)
        VALUES (UUID(), guest_os_hypervisor_hypervisor_type, guest_os_hypervisor_hypervisor_version,
                guest_os_hypervisor_guest_os_name, existing_guest_os_id, now());
    END IF;
END;
