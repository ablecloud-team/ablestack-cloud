-- #1452 idempotent addition for already deployed Europa databases.
CREATE TABLE IF NOT EXISTS storage_directory_policy (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  uuid VARCHAR(40) NOT NULL,
  instance_id BIGINT UNSIGNED NOT NULL,
  volume_id BIGINT UNSIGNED NOT NULL,
  canonical_path VARCHAR(4096) NOT NULL,
  canonical_hash CHAR(64) NOT NULL,
  owner_uid BIGINT UNSIGNED NOT NULL,
  owner_gid BIGINT UNSIGNED NOT NULL,
  mode CHAR(4) NOT NULL,
  revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  last_recursive TINYINT(1) NOT NULL DEFAULT 0,
  metadata_json MEDIUMTEXT NOT NULL,
  created DATETIME NOT NULL,
  updated DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_directory_policy_uuid (uuid),
  UNIQUE KEY uk_directory_policy_path (instance_id,volume_id,canonical_hash),
  KEY i_directory_policy_volume (volume_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
