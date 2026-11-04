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
import javax.sql.DataSource;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.upgrade.dao.EuropaStorageConfigurationUpgrade;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsIdentityMappingCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceConfigurationOperationDaoImpl;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

    private java.util.concurrent.atomic.AtomicReference<StorageServiceConfigurationPlan> prepareDispatch() {
        StorageServiceConfigurationPreflight preflight = mock(StorageServiceConfigurationPreflight.class);
        com.google.gson.JsonObject report = new com.google.gson.JsonObject();
        report.addProperty("status", "READY"); report.addProperty("fingerprint", "fixture");
        report.add("checks", new com.google.gson.JsonArray()); report.add("intent", new com.google.gson.JsonObject());
        com.google.gson.JsonObject profile = new com.google.gson.JsonObject(); profile.addProperty("observationseconds", 600);
        report.add("profile", profile);
        org.mockito.Mockito.when(preflight.observe(any(), any(), any())).thenReturn(report);
        coordinator = new StorageServiceConfigurationCoordinator(instances, operations,
                mock(StorageServiceProtocolDao.class), mock(StorageFileShareDao.class),
                mock(StorageBlockTargetDao.class), mock(StorageAccessRuleDao.class), guest, preflight,
                mock(StorageServiceRuntimeUpgradeDao.class));
        return new java.util.concurrent.atomic.AtomicReference<>();
    }

    private void runDispatch(UpdateStorageNfsIdentityMappingCmd cmd,
            java.util.concurrent.atomic.AtomicReference<StorageServiceConfigurationPlan> plan) {
        coordinator.execute(cmd, instance, StorageServiceInstance.Protocol.NFS, String.class,
                () -> { plan.get().commands.put("nfs identity apply", new com.google.gson.JsonObject()); return "candidate"; },
                plan::set, () -> plan.get().commands.put("nfs identity apply", new com.google.gson.JsonObject()), "vm-fixture");
    }

    private StorageServiceGuestCommandResult capability() {
        return new StorageServiceGuestCommandResult(true, "ready",
                "{\"nfsPosixIntentVersion\":1,\"nfsIdentityMappingVersion\":1,\"configurationPreflightVersion\":1,\"configurationTransactionVersion\":1}");
    }

    @Test public void actualCoordinatorDaoPersistsPreExecFailureWithoutPollingOrRevisionAdvance() throws Exception {
        for (boolean legacy : new boolean[] {false, true}) {
            UpdateStorageNfsIdentityMappingCmd cmd = command();
            var plan = prepareDispatch();
            doAnswer(call -> {
                StorageServiceGuestCommand command = call.getArgument(0);
                if ("configuration capability show".equals(command.getOperation())) return capability();
                assertEquals("configuration operation apply", command.getOperation());
                return legacy ? new StorageServiceGuestCommandResult(false,
                        "Failed to execute Storage Service QGA command: guest-exec: Failed to execute child process /bin/bash (Argument list too long)", null)
                        : new StorageServiceGuestCommandResult(false, "QGA_EXEC_ARGUMENT_TOO_LARGE",
                        "{\"dispatch\":{\"state\":\"NOT_STARTED\",\"code\":\"QGA_EXEC_ARGUMENT_TOO_LARGE\"}}");
            }).when(guest).dispatch(any());
            try { runDispatch(cmd, plan); fail("Dispatch must fail without a guest apply"); }
            catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("ROLLED_BACK")); }
            var stored = operations.findByRequest(instance.getId(), cmd.getConfigurationRequestId());
            assertEquals("ROLLED_BACK", stored.getState());
            assertEquals("COMPLETED", stored.getPhase());
            assertEquals(3, instances.findById(instance.getId()).getConfigurationRevision());
            assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
            var failure = StorageServiceConfigurationCoordinator.parsed(stored.getOriginalFailureJson());
            assertEquals("QGA_EXEC_ARGUMENT_TOO_LARGE", failure.get("code").getAsString());
            assertEquals("DISPATCH", failure.get("phase").getAsString()); assertTrue(!failure.get("started").getAsBoolean());
            assertEquals("NOT_NEEDED", StorageServiceConfigurationCoordinator.parsed(stored.getRollbackResultJson()).get("state").getAsString());
            verify(guest, times(legacy ? 4 : 2)).dispatch(any());
            String id = stored.getUuid();
            try { runDispatch(cmd, plan); fail("The same failed request may not send another apply"); }
            catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("no duplicate apply")); }
            assertEquals(id, operations.findByRequest(instance.getId(), cmd.getConfigurationRequestId()).getUuid());
            var response = new org.apache.cloudstack.api.response.StorageServiceConfigurationOperationResponse();
            response.setResponseName("getstorageserviceconfigurationoperationresponse");
            response.setObjectName("configurationoperation"); response.setId(stored.getUuid()); response.setInstanceid(instance.getUuid());
            response.setRequestid(stored.getRequestId()); response.setState(stored.getState()); response.setPhase(stored.getPhase());
            response.setOriginalfailure(failure); response.setRollback(StorageServiceConfigurationCoordinator.parsed(stored.getRollbackResultJson()));
            response.setCanresume(false);
            String wire = com.cloud.api.response.ApiResponseSerializer.toSerializedString(response, "json");
            assertTrue(com.google.gson.JsonParser.parseString(wire).getAsJsonObject().has("getstorageserviceconfigurationoperationresponse"));
            String output = System.getProperty("sharedfs.dispatch.wire.output");
            if (output != null && !legacy) java.nio.file.Files.writeString(java.nio.file.Path.of(output), wire);
        }
    }

    @Test public void lostReplyRetainsReservationAndDoesNotBecomeNeverStarted() throws Exception {
        var cmd = command(); var plan = prepareDispatch();
        doAnswer(call -> {
            StorageServiceGuestCommand command = call.getArgument(0);
            if ("configuration capability show".equals(command.getOperation())) return capability();
            if ("configuration operation apply".equals(command.getOperation()))
                return new StorageServiceGuestCommandResult(false, "Timed out waiting for Storage Service QGA command", null);
            assertEquals("configuration operation show", command.getOperation());
            throw new CloudRuntimeException("temporary observation unavailable");
        }).when(guest).dispatch(any());
        try { runDispatch(cmd, plan); fail("Unknown reply must keep recovery protection"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("unconfirmed")); }
        var stored = operations.findByRequest(instance.getId(), cmd.getConfigurationRequestId());
        assertEquals("RECOVERY_REQUIRED", stored.getState());
        assertEquals(Long.valueOf(stored.getId()), instances.findById(instance.getId()).getActiveConfigurationOperationId());
        assertEquals(3, instances.findById(instance.getId()).getConfigurationRevision());
        assertNull(stored.getOriginalFailureJson());
        assertEquals("RESPONSE_UNCONFIRMED", StorageServiceConfigurationCoordinator.parsed(stored.getGuestObservationJson()).get("code").getAsString());
        String id = stored.getUuid();
        try { runDispatch(cmd, plan); fail(); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("no duplicate apply")); }
        assertEquals(id, operations.findByRequest(instance.getId(), cmd.getConfigurationRequestId()).getUuid());
        verify(guest, times(5)).dispatch(any());
    }

    @Test public void configurationByteLimitAndUnknownDispatchCodesKeepTheirProtection() {
        try { StorageServiceConfigurationPlan.bounded("x".repeat(1024 * 1024 + 1)); fail(); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("1 MiB")); }
        var op = new StorageServiceConfigurationOperationVO();
        op.setInstanceId(instance.getId());
        var value = StorageServiceConfigurationCoordinator.dispatchObservation(op, instance,
                new StorageServiceGuestCommandResult(false, "unknown",
                "{\"dispatch\":{\"state\":\"NOT_STARTED\",\"code\":\"UNTRUSTED_CODE\"}}"));
        assertTrue(!value.has("state"));
        assertTrue(!value.has("rollback"));
    }

    @Test public void directoryInspectionTimeoutKeepsOriginalCauseThroughCoordinatorDaoAndApi() throws Exception {
        var cmd = new org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd();
        field(cmd, cmd.getClass(), "instanceId", instance.getId());
        field(cmd, cmd.getClass(), "name", "boot095");
        field(cmd, cmd.getClass(), "path", "/export/boot095");
        field(cmd, BaseStorageServiceConfigurationCmd.class, "expectedRevision", 3L);
        var plan = prepareDispatch();
        var inspection = new StorageDirectoryPolicies(mock(org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDao.class),
                mock(StorageFileShareDao.class), mock(StorageAccessRuleDao.class),
                mock(com.cloud.storage.dao.VolumeDao.class), guest, volume -> { });
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            StorageServiceGuestCommand command = call.getArgument(0);
            if ("configuration capability show".equals(command.getOperation())) return capability();
            assertEquals("directory policy inspect", command.getOperation());
            assertEquals(20, command.getTimeoutSeconds());
            assertEquals(95, com.google.gson.JsonParser.parseString(command.getPayload()).getAsJsonObject().getAsJsonArray("shares").size());
            calls.incrementAndGet();
            return new StorageServiceGuestCommandResult(false, "Timed out waiting for Storage Service QGA command", null);
        }).when(guest).dispatch(any());
        java.util.function.Supplier<String> candidate = () -> {
            var rows = new java.util.ArrayList<StorageFileShareVO>();
            for (int n = 1; n <= 95; n++) rows.add(new StorageFileShareVO(instance.getId(), StorageServiceInstance.Protocol.NFS,
                    String.format("boot%03d", n), String.format("/export/boot%03d", n), 77L, "xfs", null,
                    StorageServiceInstance.ResourceState.Ready,
                    "{\"volumeMountPath\":\"/srv/ablestack-storage/volumes/volume\",\"backingPath\":\"/srv/ablestack-storage/volumes/volume/export/team\"}"));
            inspection.observations(instance, rows);
            throw new AssertionError("Failed observation must not return a candidate");
        };
        try { coordinator.execute(cmd, instance, StorageServiceInstance.Protocol.NFS, String.class, candidate,
                plan::set, () -> { }, "vm-fixture"); fail("Inspection must reject"); }
        catch (StorageDirectoryPolicies.ObservationFailure expected) { assertEquals("DIRECTORY_OBSERVATION_UNAVAILABLE", expected.getMessage()); }
        var stored = operations.findByRequest(instance.getId(), cmd.getConfigurationRequestId());
        var failure = StorageServiceConfigurationCoordinator.parsed(stored.getOriginalFailureJson());
        assertEquals("ROLLED_BACK", stored.getState()); assertEquals("COMPLETED", stored.getPhase());
        assertEquals("DIRECTORY_OBSERVATION_UNAVAILABLE", failure.get("code").getAsString());
        assertEquals("VALIDATING", failure.get("phase").getAsString());
        assertEquals("DIRECTORY_POLICY_INSPECT", failure.get("stage").getAsString());
        assertEquals("QGA_COMMAND_TIMEOUT", failure.get("reason").getAsString());
        assertTrue(!failure.has("started")); // a read-only command did start; no false never-started claim
        assertEquals(3, instances.findById(instance.getId()).getConfigurationRevision());
        assertNull(instances.findById(instance.getId()).getActiveConfigurationOperationId());
        assertEquals("NOT_NEEDED", StorageServiceConfigurationCoordinator.parsed(stored.getRollbackResultJson()).get("state").getAsString());
        try { coordinator.execute(cmd, instance, StorageServiceInstance.Protocol.NFS, String.class, candidate,
                plan::set, () -> { }, "vm-fixture"); fail("Duplicate failed request may not run the inspection again"); }
        catch (CloudRuntimeException expected) { assertTrue(expected.getMessage().contains("no duplicate apply")); }
        assertEquals(1, calls.get());
        var response = new org.apache.cloudstack.api.response.StorageServiceConfigurationOperationResponse();
        response.setResponseName("getstorageserviceconfigurationoperationresponse"); response.setObjectName("configurationoperation");
        response.setId(stored.getUuid()); response.setInstanceid(instance.getUuid()); response.setRequestid(stored.getRequestId());
        response.setState(stored.getState()); response.setPhase(stored.getPhase()); response.setCanresume(false);
        response.setOriginalfailure(failure); response.setRollback(StorageServiceConfigurationCoordinator.parsed(stored.getRollbackResultJson()));
        String wire = com.cloud.api.response.ApiResponseSerializer.toSerializedString(response, "json");
        assertTrue(wire.contains("DIRECTORY_OBSERVATION_UNAVAILABLE")); assertTrue(wire.contains("QGA_COMMAND_TIMEOUT"));
        String output = System.getProperty("sharedfs.preparation.wire.output");
        if (output != null) java.nio.file.Files.writeString(java.nio.file.Path.of(output), wire);
    }

}
