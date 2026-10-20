CREATE TABLE IF NOT EXISTS cloud.storage_service_operation_control (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operation_id BIGINT UNSIGNED NOT NULL,
 instance_id BIGINT UNSIGNED NOT NULL,
 control_revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
 cancel_requested_by BIGINT UNSIGNED DEFAULT NULL,
 drain_state VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
 policy_json LONGTEXT,
 lease_json LONGTEXT,
 created_by BIGINT UNSIGNED NOT NULL,
 created DATETIME NOT NULL,
 updated DATETIME NOT NULL,
 UNIQUE KEY uk_storage_operation_control_operation(operation_id),
 KEY idx_storage_operation_control_instance(instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
