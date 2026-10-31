
-- #1447 Global runtime catalogue. Idempotent for existing Europa/Diplo and new installations.
CREATE TABLE IF NOT EXISTS storage_service_runtime_bundle_validation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  uuid VARCHAR(40) NOT NULL,
  bundle_id BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  observed_revision BIGINT UNSIGNED NOT NULL,
  state VARCHAR(32) NOT NULL,
  phase VARCHAR(64) NOT NULL,
  original_job_id BIGINT UNSIGNED NULL,
  heartbeat DATETIME NULL,
  started DATETIME NULL,
  completed DATETIME NULL,
  metadata_digest CHAR(64) NULL,
  trust_fingerprint CHAR(64) NULL,
  verifier_digest CHAR(64) NULL,
  profile_id VARCHAR(128) NULL,
  compatibility VARCHAR(32) NULL,
  result_json MEDIUMTEXT NULL,
  error_code VARCHAR(128) NULL,
  error_message VARCHAR(1024) NULL,
  created DATETIME NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uuid (uuid), UNIQUE KEY bundle_request (bundle_id,request_id), KEY bundle_created (bundle_id,created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE TABLE IF NOT EXISTS storage_service_runtime_bundle_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  uuid VARCHAR(40) NOT NULL,
  bundle_id BIGINT UNSIGNED NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  actor_user_id BIGINT UNSIGNED NULL,
  account_id BIGINT UNSIGNED NULL,
  action VARCHAR(32) NOT NULL,
  from_state VARCHAR(32) NULL,
  to_state VARCHAR(32) NULL,
  from_revision BIGINT UNSIGNED NOT NULL,
  to_revision BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(1024) NULL,
  result_code VARCHAR(128) NULL,
  validation_id BIGINT UNSIGNED NULL,
  job_id BIGINT UNSIGNED NULL,
  created DATETIME NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uuid (uuid), UNIQUE KEY bundle_request (bundle_id,request_id), KEY bundle_created (bundle_id,created)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
DELIMITER $$
DROP PROCEDURE IF EXISTS add_runtime_catalog_column$$
CREATE PROCEDURE add_runtime_catalog_column(IN t VARCHAR(128), IN c VARCHAR(128), IN definition TEXT)
BEGIN
 IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=t AND COLUMN_NAME=c) THEN
  SET @catalog_ddl=CONCAT('ALTER TABLE ',t,' ADD COLUMN ',c,' ',definition);
  PREPARE catalog_stmt FROM @catalog_ddl; EXECUTE catalog_stmt; DEALLOCATE PREPARE catalog_stmt;
 END IF;
END$$
DELIMITER ;
CALL add_runtime_catalog_column('storage_service_runtime_bundle','state_revision','BIGINT UNSIGNED NOT NULL DEFAULT 0');
CALL add_runtime_catalog_column('storage_service_runtime_bundle','validation_status','VARCHAR(32) NOT NULL DEFAULT ''UNKNOWN''');
CALL add_runtime_catalog_column('storage_service_runtime_bundle','current_validation_id','BIGINT UNSIGNED NULL');
CALL add_runtime_catalog_column('storage_service_runtime_upgrade','catalog_policy_json','MEDIUMTEXT NULL');
DROP PROCEDURE add_runtime_catalog_column;
UPDATE storage_service_runtime_bundle SET state='DRAFT',validation_status='UNKNOWN' WHERE state='AVAILABLE';
UPDATE storage_service_runtime_bundle SET state='SUSPENDED',validation_status='UNKNOWN' WHERE state='DISABLED';
