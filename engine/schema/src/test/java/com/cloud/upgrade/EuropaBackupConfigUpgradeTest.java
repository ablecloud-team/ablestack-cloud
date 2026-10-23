// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.upgrade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.cloud.upgrade.dao.EuropaBackupConfigUpgrade;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercise migration retries and setting precedence against mutable JDBC test rows. */
public class EuropaBackupConfigUpgradeTest {
    private static final String CURRENT = "backup.data.operation.timeout";
    private static final String COMMAND = "backup.command.timeout";
    private static final String RESTORE = "backup.restore.timeout";
    private static final String NAS = "nas.backup.restore.timeout";
    private static final String NETBACKUP = "netbackup.restore.timeout";
    private static final String CATALOG = "backup.plugin.ablestack-veeam.sync.delete.missing.catalog";
    private static final String RBD_DIFF = "backup.plugin.ablestack-veeam.sync.delete.rbd.diff";
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement select = mock(PreparedStatement.class);
    private final PreparedStatement insert = mock(PreparedStatement.class);
    private final PreparedStatement delete = mock(PreparedStatement.class);
    private final PreparedStatement deleteZoneSetting = mock(PreparedStatement.class);
    private final Map<String, String[]> rows = new LinkedHashMap<>();
    private final Map<Integer, String> selectArgs = new HashMap<>();
    private final Map<Integer, String> insertArgs = new HashMap<>();
    private final Map<Integer, String> deleteArgs = new HashMap<>();

    @Before
    public void setup() throws Exception {
        when(connection.prepareStatement(startsWith("SELECT name, value, default_value"))).thenReturn(select);
        when(connection.prepareStatement(startsWith("INSERT INTO cloud.configuration"))).thenReturn(insert);
        when(connection.prepareStatement(startsWith("DELETE FROM cloud.configuration"))).thenReturn(delete);
        when(connection.prepareStatement("DELETE FROM cloud.data_center_details WHERE name = ?")).thenReturn(deleteZoneSetting);
        capture(select, selectArgs);
        capture(insert, insertArgs);
        capture(delete, deleteArgs);
        when(select.executeQuery()).thenAnswer(call -> {
            List<String[]> snapshot = new ArrayList<>();
            rows.forEach((key, value) -> {
                if (selectArgs.containsValue(key)) { snapshot.add(new String[]{key, value[0], value[1]}); }
            });
            ResultSet result = mock(ResultSet.class);
            AtomicInteger cursor = new AtomicInteger(-1);
            when(result.next()).thenAnswer(next -> cursor.incrementAndGet() < snapshot.size());
            when(result.getString(anyInt())).thenAnswer(get -> snapshot.get(cursor.get())[get.<Integer>getArgument(0) - 1]);
            return result;
        });
        when(insert.executeUpdate()).thenAnswer(call -> {
            rows.putIfAbsent(insertArgs.get(1), new String[]{insertArgs.get(2), "43200"});
            return 1;
        });
        when(delete.executeUpdate()).thenAnswer(call -> removeObsolete());
    }

    private void capture(PreparedStatement statement, Map<Integer, String> arguments) throws SQLException {
        doAnswer(call -> {
            arguments.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(statement).setString(anyInt(), anyString());
    }

    private int removeObsolete() {
        deleteArgs.values().forEach(rows::remove);
        return 1;
    }

    private void put(String name, String value, String defaultValue) {
        rows.put(name, new String[]{value, defaultValue});
    }

    @Test
    public void onlyExplicitlyRetiredSettingsAreRemoved() {
        put(CURRENT, "50000", "43200");
        for (String name : List.of(COMMAND, RESTORE, NAS, NETBACKUP, CATALOG, RBD_DIFF, "backup.plugin.ablestack-veeam.staging.path")) { put(name, "1", "0"); }
        for (String name : List.of("backup.chain.size", "kvm.backup.chain.size", "nas.backup.incremental.enabled",
                "backup.plugin.ablestack-veeam.restore.timeout", "backup.plugin.veeam.request.timeout",
                "netbackup.prepared.restore.path.ready.timeout")) {
            put(name, "8", "8");
        }
        EuropaBackupConfigUpgrade.migrate(connection);
        for (String name : List.of(COMMAND, RESTORE, NAS, NETBACKUP, CATALOG, RBD_DIFF, "backup.plugin.ablestack-veeam.staging.path")) { assertFalse(rows.containsKey(name)); }
        assertEquals(7, rows.size());
        assertEquals("50000", rows.get(CURRENT)[0]);
    }

    @Test
    public void retiredVeeamExportPathIsRemovedFromZoneOverrides() throws Exception {
        put(CURRENT, "50000", "43200");
        EuropaBackupConfigUpgrade.migrate(connection);
        verify(deleteZoneSetting).setString(1, "backup.plugin.ablestack-veeam.staging.path");
        verify(deleteZoneSetting).executeUpdate();
        assertEquals("50000", rows.get(CURRENT)[0]);
    }

    @Test
    public void existingUnifiedTimeoutTakesPrecedenceOverAllLegacyValues() {
        put(CURRENT, "12000", "43200");
        put(COMMAND, "86400", "7200");
        EuropaBackupConfigUpgrade.migrate(connection);
        assertEquals("12000", rows.get(CURRENT)[0]);
    }

    @Test
    public void customizedNetBackupTimeoutIsMigratedWhenUnifiedSettingIsAbsent() {
        put(NETBACKUP, "60000", "7200");
        EuropaBackupConfigUpgrade.migrate(connection);
        assertEquals("60000", rows.get(CURRENT)[0]);
        assertFalse(rows.containsKey(NETBACKUP));
    }

    @Test
    public void followUpRemovesNetBackupTimeoutWithoutOverwritingAnAlreadyMigratedValue() {
        put(CURRENT, "50000", "43200");
        put(NETBACKUP, "86400", "7200");
        EuropaBackupConfigUpgrade.migrate(connection);
        assertEquals("50000", rows.get(CURRENT)[0]);
        assertFalse(rows.containsKey(NETBACKUP));
    }

    @Test
    public void largestCustomizedLegacyTimeoutIsPreserved() {
        put(COMMAND, "86400", "7200");
        put(RESTORE, "50000", "43200");
        put(NAS, "7200", "7200");
        EuropaBackupConfigUpgrade.migrate(connection);
        assertEquals("86400", rows.get(CURRENT)[0]);
    }

    @Test
    public void oldDefaultsDoNotOverrideTheNewTwelveHourDefault() {
        put(COMMAND, "7200", "7200");
        put(RESTORE, "43200", "43200");
        EuropaBackupConfigUpgrade.migrate(connection);
        assertEquals("43200", rows.get(CURRENT)[0]);
    }

    @Test
    public void legacyValueWithoutDefaultMetadataIsPreserved() {
        put(NAS, "50000", null);
        EuropaBackupConfigUpgrade.migrate(connection);
        assertEquals("50000", rows.get(CURRENT)[0]);
    }

    @Test
    public void invalidOrFallbackLegacyTimeoutsUseTheCurrentDefault() {
        for (String value : new String[]{null, "", "0", "-1", "invalid", "2147483648"}) {
            rows.clear();
            put(COMMAND, value, "7200");
            EuropaBackupConfigUpgrade.migrate(connection);
            assertEquals("43200", rows.get(CURRENT)[0]);
        }
    }

    @Test
    public void repeatedMigrationKeepsSubsequentAdministratorChanges() {
        put(COMMAND, "50000", "7200");
        EuropaBackupConfigUpgrade.migrate(connection);
        put(CURRENT, "60000", "43200");
        EuropaBackupConfigUpgrade.migrate(connection);
        assertEquals("60000", rows.get(CURRENT)[0]);
    }

    @Test
    public void failedValueMigrationDoesNotDeleteLegacySettings() throws Exception {
        put(COMMAND, "50000", "7200");
        when(insert.executeUpdate()).thenThrow(new SQLException("injected insert failure"));
        assertThrows(CloudRuntimeException.class, () -> EuropaBackupConfigUpgrade.migrate(connection));
        assertTrue(rows.containsKey(COMMAND));
        verify(delete, never()).executeUpdate();
    }

    @Test
    public void cleanupFailureCanBeRetriedAfterTheNewValueWasSaved() throws Exception {
        put(COMMAND, "50000", "7200");
        when(delete.executeUpdate()).thenThrow(new SQLException("injected delete failure")).thenAnswer(call -> removeObsolete());
        assertThrows(CloudRuntimeException.class, () -> EuropaBackupConfigUpgrade.migrate(connection));
        assertEquals("50000", rows.get(CURRENT)[0]);
        EuropaBackupConfigUpgrade.migrate(connection);
        assertEquals("50000", rows.get(CURRENT)[0]);
        assertFalse(rows.containsKey(COMMAND));
    }
}
