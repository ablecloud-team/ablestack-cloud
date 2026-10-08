-- Existing rows remain NULL/disabled; no instance is implicitly opted in.
CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.storage_service_instance', 'operation_control_policy_json', 'LONGTEXT DEFAULT NULL');
