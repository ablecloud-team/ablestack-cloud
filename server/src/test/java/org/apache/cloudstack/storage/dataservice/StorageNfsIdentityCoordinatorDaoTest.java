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
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.upgrade.dao.EuropaStorageConfigurationUpgrade;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real coordinator, real instance reservation and real operation DAO against the unchanged VARCHAR(32). */
public class StorageNfsIdentityCoordinatorDaoTest {
    private Field datasource;
    private DataSource previous;
    private TransactionLegacy transaction;
    private StorageServiceInstanceDaoImpl instances;
    private StorageServiceConfigurationOperationDaoImpl operations;
    private StorageServiceInstanceVO instance;
    private StorageServiceGuestCommandDispatcher guest;
    private StorageServiceConfigurationCoordinator coordinator;

    @Before public void open() throws Exception {
        String url = System.getProperty("configuration.dao.test.url");
        Assume.assumeTrue("Dedicated loopback database required", url != null);
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13344/cloud?"));
        MysqlDataSource ds = new MysqlDataSource(); ds.setURL(url); ds.setUser("root");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            String schema;
            try (java.io.InputStream in = getClass().getResourceAsStream("/META-INF/db/schema-Diplo-After.sql")) {
                assertNotNull(in); schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            char quote = 96;
            String marker = "CREATE TABLE IF NOT EXISTS " + quote + "cloud" + quote + "." + quote + "storage_service_instance" + quote;
            int start = schema.indexOf(marker); assertTrue(start >= 0);
            s.execute(schema.substring(start, schema.indexOf(';', start) + 1));
            // Existing runtime columns of the deployed VO, only in the dedicated test schema.
            for (String column : new String[] {"current_runtime_bundle_id BIGINT UNSIGNED NULL",
                    "previous_runtime_bundle_id BIGINT UNSIGNED NULL", "runtime_state VARCHAR(32) NULL", "runtime_verified_at DATETIME NULL"})
                s.execute("ALTER TABLE storage_service_instance ADD COLUMN IF NOT EXISTS " + column);
            EuropaStorageConfigurationUpgrade.migrate(c);
            s.execute("DELETE FROM storage_service_configuration_operation");
            s.execute("DELETE FROM storage_service_instance");
            try (java.sql.ResultSet rs = s.executeQuery("SELECT @@sql_mode")) {
                assertTrue(rs.next()); assertTrue(rs.getString(1).contains("STRICT"));
            }
        }
        datasource = TransactionLegacy.class.getDeclaredField("s_ds"); datasource.setAccessible(true);
        previous = (DataSource) datasource.get(null); datasource.set(null, ds);
        transaction = TransactionLegacy.open(TransactionLegacy.CLOUD_DB);
        instances = new StorageServiceInstanceDaoImpl();
        operations = new StorageServiceConfigurationOperationDaoImpl();
        instance = new StorageServiceInstanceVO("identity-coordinator-test", null, 1, 2, 13, 1L, "SYSTEM_VM");
        instance.setVmId(174L); instance.setConfigurationRevision(3);
        instance = instances.persist(instance);
        guest = mock(StorageServiceGuestCommandDispatcher.class);
        coordinator = new StorageServiceConfigurationCoordinator(instances, operations,
                mock(StorageServiceProtocolDao.class), mock(StorageFileShareDao.class),
                mock(StorageBlockTargetDao.class), mock(StorageAccessRuleDao.class), guest, null,
                mock(StorageServiceRuntimeUpgradeDao.class));
    }
    @After public void close() throws Exception {
        if (transaction != null) transaction.close();
        if (datasource != null) datasource.set(null, previous);
    }
    private static void field(Object target, Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private UpdateStorageNfsIdentityMappingCmd command() throws Exception {
        UpdateStorageNfsIdentityMappingCmd cmd = new UpdateStorageNfsIdentityMappingCmd();
        field(cmd, cmd.getClass(), "instanceId", instance.getId());
        field(cmd, cmd.getClass(), "idMappingMode", "NUMERIC");
        field(cmd, BaseStorageServiceConfigurationCmd.class, "expectedRevision", 3L);
        return cmd;
    }
    private void rejectAfterRealReservation(BaseStorageServiceConfigurationCmd cmd, String expectedType) {
        doAnswer(call -> {
            StorageServiceConfigurationOperationVO stored = operations.findByRequest(instance.getId(), cmd.getConfigurationRequestId());
            assertNotNull(stored); assertEquals(expectedType, stored.getResourceType());
            assertEquals(Long.valueOf(stored.getId()), instances.findById(instance.getId()).getActiveConfigurationOperationId());
            assertEquals(3, stored.getBaseRevision()); assertEquals(4, stored.getTargetRevision());
            throw new CloudRuntimeException("AFTER_REAL_RESERVATION");
        }).when(guest).dispatch(any());
    }
    private void execute(BaseStorageServiceConfigurationCmd cmd) {
        coordinator.execute(cmd, instance, StorageServiceInstance.Protocol.NFS, String.class,
                () -> { throw new AssertionError("Guest boundary must be reached after SQL admission and before candidate"); },
                plan -> { }, () -> { }, "vm-fixture");
    }
    @Test public void actual34CharacterFailureRemainsReproducibleInUnchangedSchema() {
        StorageServiceConfigurationOperationVO old = new StorageServiceConfigurationOperationVO();
        old.setInstanceId(instance.getId()); old.setAccountId(2); old.setDomainId(1);
        old.setRequestId(java.util.UUID.randomUUID().toString()); old.setRequestHash("a".repeat(64));
        old.setResourceType(UpdateStorageNfsIdentityMappingCmd.class.getSimpleName());
        old.setState("PENDING"); old.setPhase("PREPARING");
        assertEquals(34, old.getResourceType().length());
        try { operations.persist(old); fail("Actual old INSERT must exceed VARCHAR(32)"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("resource_type")); }
    }
    @Test public void actualCommandCoordinatorDaoStoresAndReloadsFixedTypeBeforeGuest() throws Exception {
        UpdateStorageNfsIdentityMappingCmd cmd = command();
        rejectAfterRealReservation(cmd, "NfsIdentityMapping");
        try { execute(cmd); fail("Expected deliberate guest boundary"); }
        catch (CloudRuntimeException expected) { assertEquals("AFTER_REAL_RESERVATION", expected.getMessage()); }
        StorageServiceConfigurationOperationVO stored = operations.findByRequest(instance.getId(), cmd.getConfigurationRequestId());
        assertEquals("NfsIdentityMapping", stored.getResourceType());
        assertEquals(18, stored.getResourceType().length()); assertEquals("UPDATE", stored.getAction());
        assertEquals(StorageServiceConfigurationCoordinator.requestHash(cmd), stored.getRequestHash());
        assertEquals("ROLLED_BACK", stored.getState());
        assertEquals(3, instances.findById(instance.getId()).getConfigurationRevision());
        assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
        String uuid = stored.getUuid();
        try { execute(cmd); fail("Observe the existing rejected request without another guest apply"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("no duplicate apply")); }
        assertEquals(uuid, operations.findByRequest(instance.getId(), cmd.getConfigurationRequestId()).getUuid());
        assertEquals(1, operations.listByInstance(instance.getId()).size());
        verify(guest, times(1)).dispatch(any());
        field(cmd, cmd.getClass(), "idMappingMode", "NAME_DOMAIN");
        try { execute(cmd); fail("Changed public target cannot reuse request ID"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("requestid conflicts")); }
        verify(guest, times(1)).dispatch(any());
    }
    @Test public void legacySmbEndpointTypeAndResumeContractAreUnchanged() {
        for (BaseStorageServiceConfigurationCmd cmd : new BaseStorageServiceConfigurationCmd[] {
                new EnableStorageServiceProtocolCmd(), new DeleteStorageServiceProtocolCmd()}) {
            assertEquals(cmd.getClass().getSimpleName(), StorageServiceConfigurationCoordinator.resourceType(cmd));
            StorageServiceConfigurationOperationVO op = new StorageServiceConfigurationOperationVO();
            op.setProtocol("SMB"); op.setResourceType(StorageServiceConfigurationCoordinator.resourceType(cmd));
            assertTrue(StorageServiceConfigurationCoordinator.smbEndpointOperation(op));
        }
    }
}
