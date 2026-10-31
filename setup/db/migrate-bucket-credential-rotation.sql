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
-- #1533 same-version additive migration; apply before management JAR.
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='pending_access_key') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN pending_access_key VARCHAR(1024) DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='pending_secret_key') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN pending_secret_key VARCHAR(1024) DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='previous_access_key') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN previous_access_key VARCHAR(1024) DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='previous_secret_key') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN previous_secret_key VARCHAR(1024) DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='rotation_uuid') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN rotation_uuid VARCHAR(40) DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='rotation_phase') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN rotation_phase VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='rotation_error') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN rotation_error VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='retire_after') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN retire_after DATETIME DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
SET @bkt_credential_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='cloud' AND table_name='object_store_account_credential' AND column_name='rotation_grace_seconds') = 0, 'ALTER TABLE cloud.object_store_account_credential ADD COLUMN rotation_grace_seconds INT DEFAULT NULL', 'SELECT 1');
PREPARE bkt_credential_stmt FROM @bkt_credential_ddl;
EXECUTE bkt_credential_stmt;
DEALLOCATE PREPARE bkt_credential_stmt;
