// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.upgrade;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class DrSchemaContractTest {
    private static final String[] REQUIRED_TABLES = {
        "dr_site_credential",
        "dr_site_health_check",
        "dr_target_resource_claim",
        "dr_test_session",
        "dr_test_disk",
        "dr_resource_lease",
        "dr_group_run"
    };

    private static final String[] REQUIRED_FRESH_COLUMNS = {
        "`credential_id` bigint unsigned NULL",
        "`protection_group_uuid` varchar(40) NULL",
        "`accepted_cycle_sequence` bigint unsigned NULL",
        "`accepted_cycle_token` varchar(255) NULL",
        "`terminal_authoritative` tinyint(1) NOT NULL DEFAULT 0",
        "`worker_identity_state` varchar(32) DEFAULT NULL",
        "`ownership_generation` bigint unsigned NOT NULL DEFAULT 1",
        "`target_claim_id` bigint unsigned NULL",
        "`commit_envelope_sha256` varchar(64)",
        "`commit_dispatch_state` varchar(32)"
    };

    @Test
    public void testFreshAndUpgradeSchemasProvideCompleteDrContract() throws IOException {
        Path root = findRepositoryRoot();
        String fresh = read(root.resolve("setup/db/create-schema.sql"));
        String releaseUpgrade = read(root.resolve(
                "engine/schema/src/main/resources/META-INF/db/schema-42210to42300.sql"));
        String europaUpgrade = read(root.resolve(
                "engine/schema/src/main/resources/META-INF/db/schema-Europa-After.sql"));

        for (String table : REQUIRED_TABLES) {
            assertContainsTable(fresh, table);
            assertContainsTable(releaseUpgrade, table);
            assertContainsTable(europaUpgrade, table);
        }
        for (String column : REQUIRED_FRESH_COLUMNS) {
            assertTrue("Fresh schema is missing " + column, fresh.contains(column));
        }
        assertTrue(fresh.contains("UNIQUE KEY `uk_dr_sync_cycle__plan_sequence` (`plan_id`, `sequence`)"));
        assertFalse(fresh.contains("uk_dr_sync_cycle__plan_run_sequence"));
        assertTrue(releaseUpgrade.contains("uk_dr_sync_cycle__plan_sequence"));
        assertTrue(europaUpgrade.contains("uk_dr_sync_cycle__plan_sequence"));
    }

    @Test
    public void testRestorePointBackfillFollowsRunSchemaCreation() throws IOException {
        Path directory = findRepositoryRoot().resolve("engine/schema/src/main/resources/META-INF/db");
        for (String script : new String[]{"schema-42200to42210.sql", "schema-42210to42300.sql",
                "schema-Europa-After.sql"}) {
            String schema = read(directory.resolve(script));
            int backfill = schema.indexOf("JOIN (SELECT `plan_id`, MAX(`id`) AS `run_id` FROM `cloud`.`dr_run`");
            assertTrue(script + " is missing the restore point backfill", backfill >= 0);
            for (String table : new String[]{"dr_restore_point", "dr_run"}) {
                int create = schema.indexOf("CREATE TABLE IF NOT EXISTS `cloud`.`" + table + "`");
                int end = schema.indexOf(";", create);
                assertTrue(script + " must create " + table + " before backfilling restore points",
                        create >= 0 && end > create && end < backfill);
            }
            int runColumn = schema.indexOf("CALL `cloud`.`IDEMPOTENT_ADD_COLUMN`('cloud.dr_restore_point', 'run_id'");
            assertTrue(script + " must add run_id before backfilling legacy restore points",
                    runColumn >= 0 && runColumn < backfill);
        }
    }

    private void assertContainsTable(String schema, String table) {
        assertTrue("Schema is missing table " + table,
                schema.contains("CREATE TABLE `cloud`.`" + table + "`")
                || schema.contains("CREATE TABLE IF NOT EXISTS `cloud`.`" + table + "`"));
    }

    private Path findRepositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("setup/db/create-schema.sql"))
                    && Files.isDirectory(current.resolve("engine/schema"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate the repository root");
    }

    private String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
