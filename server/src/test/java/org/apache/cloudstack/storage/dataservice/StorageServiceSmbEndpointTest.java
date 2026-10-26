// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.command.user.storage.dataservice.EnableStorageServiceProtocolCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageServiceProtocolResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.vm.NicVO;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.Assert.*;

public class StorageServiceSmbEndpointTest {
    static class Harness extends StorageServiceManagerImpl {
        final List<StorageServiceProtocolVO> rows = new ArrayList<>();
        final StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        final NicVO nic = Mockito.mock(NicVO.class);
        boolean fail;
        int deletedGuard;
        Harness() {
            Mockito.when(instance.getId()).thenReturn(8L);
            Mockito.when(nic.getIPv4Address()).thenReturn("192.0.2.1");
            StorageServiceProtocolDao dao = Mockito.mock(StorageServiceProtocolDao.class);
            Mockito.when(dao.listByInstanceIdAndProtocol(8L, StorageServiceInstance.Protocol.SMB)).thenAnswer(i -> new ArrayList<>(rows));
            Mockito.when(dao.findByUuid(Mockito.anyString())).thenAnswer(i -> rows.stream().filter(row -> row.getUuid().equals(i.getArgument(0))).findFirst().orElse(null));
            Mockito.when(dao.persist(Mockito.any(StorageServiceProtocolVO.class))).thenAnswer(i -> {
                StorageServiceProtocolVO row = i.getArgument(0);
                ReflectionTestUtils.setField(row, "id", 16000L + rows.size());
                rows.add(row); return row;
            });
            Mockito.when(dao.remove(Mockito.anyLong())).thenAnswer(i -> rows.removeIf(v -> v.getId() == (Long)i.getArgument(0)));
            ReflectionTestUtils.setField(this, "storageServiceProtocolDao", dao);
        }
        @Override protected StorageServiceInstanceVO requireInstance(Long id) { return instance; }
        @Override protected NicVO resolveProtocolListenAddress(StorageServiceInstanceVO i, String ip) { return nic; }
        @Override protected boolean registerProtocolListenAddress(StorageServiceInstanceVO i, String ip, NicVO n) { return false; }
        @Override protected void ensureGuestProtocolListenAddress(StorageServiceInstanceVO i, String ip, NicVO n, Integer port) { }
        @Override protected void prepareSmbAddressCleanup(StorageServiceInstanceVO i, String ip) { }
        @Override protected void validateProtocolCanBeDeleted(StorageServiceInstanceVO i, StorageServiceInstance.Protocol p) {
            deletedGuard++;
            throw new InvalidParameterValueException("LAST_ENDPOINT_IN_USE");
        }
        @Override protected void applyStorageServiceProtocolDesiredState(StorageServiceInstanceVO i, StorageServiceInstance.Protocol p) {
            if (fail) throw new IllegalStateException("CONTROLLED_CANDIDATE_FAILURE");
        }
        @Override protected StorageServiceProtocolResponse createProtocolResponse(StorageServiceProtocolVO row) {
            StorageServiceProtocolResponse response = new StorageServiceProtocolResponse();
            response.setId(row.getUuid()); return response;
        }
        void activate(String id, String ip, Integer port) throws Exception {
            EnableStorageServiceProtocolCmd cmd = Mockito.mock(EnableStorageServiceProtocolCmd.class);
            Mockito.when(cmd.getInstanceId()).thenReturn(8L); Mockito.when(cmd.getProtocol()).thenReturn("SMB");
            Mockito.when(cmd.getEndpointId()).thenReturn(id); Mockito.when(cmd.getListenIp()).thenReturn(ip); Mockito.when(cmd.getPort()).thenReturn(port);
            invokeCandidate("enableStorageServiceProtocolCandidate", EnableStorageServiceProtocolCmd.class, cmd);
        }
        void remove(String id, String ip, Integer port) throws Exception {
            DeleteStorageServiceProtocolCmd cmd = Mockito.mock(DeleteStorageServiceProtocolCmd.class);
            Mockito.when(cmd.getInstanceId()).thenReturn(8L); Mockito.when(cmd.getProtocol()).thenReturn("SMB");
            Mockito.when(cmd.getEndpointId()).thenReturn(id); Mockito.when(cmd.getListenIp()).thenReturn(ip); Mockito.when(cmd.getPort()).thenReturn(port);
            invokeCandidate("deleteStorageServiceProtocolCandidate", DeleteStorageServiceProtocolCmd.class, cmd);
        }
        void invokeCandidate(String name, Class<?> type, Object cmd) throws Exception {
            Method m = StorageServiceManagerImpl.class.getDeclaredMethod(name, type); m.setAccessible(true);
            try { m.invoke(this, cmd); }
            catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
                throw e;
            }
        }
    }

    @Test public void differentTupleAppendsSameTupleReusesAndExplicitEditKeepsUuid() throws Exception {
        Harness h = new Harness(); h.activate(null, "192.0.2.10", null);
        String first = h.rows.get(0).getUuid(); h.activate(null, "192.0.2.11", 445);
        String second = h.rows.get(1).getUuid(); h.activate(null, "192.0.2.11", null);
        assertEquals(2, h.rows.size()); assertEquals(first, h.rows.get(0).getUuid()); assertEquals(second, h.rows.get(1).getUuid());
        h.activate(second, "192.0.2.12", 1445);
        assertEquals(second, h.rows.get(1).getUuid()); assertEquals("192.0.2.10", h.rows.get(0).getListenIp());
        assertEquals(Integer.valueOf(1445), h.rows.get(1).getPort());
    }

    @Test public void foreignUuidAndDuplicateEditAreRejectedWithoutChangingRows() throws Exception {
        Harness h = new Harness(); h.activate(null, "192.0.2.10", 445); h.activate(null, "192.0.2.11", 445);
        String second = h.rows.get(1).getUuid();
        try { h.activate(second, "192.0.2.10", 445); fail(); } catch (InvalidParameterValueException expected) { }
        try { h.activate(UUID.randomUUID().toString(), "192.0.2.12", 445); fail(); } catch (InvalidParameterValueException expected) { }
        assertEquals(2, h.rows.size()); assertEquals("192.0.2.11", h.rows.get(1).getListenIp());
    }

    @Test public void controlledApplyFailureRestoresPreviousTupleStateAndConfig() throws Exception {
        Harness h = new Harness(); h.activate(null, "192.0.2.10", 445); h.activate(null, "192.0.2.11", 445);
        StorageServiceProtocolVO row = h.rows.get(1); String config = row.getConfigJson(); h.fail = true;
        try { h.activate(row.getUuid(), "192.0.2.12", 1445); fail(); } catch (IllegalStateException expected) { }
        assertEquals("192.0.2.11", row.getListenIp()); assertEquals(Integer.valueOf(445), row.getPort()); assertEquals(config, row.getConfigJson());
        try { h.activate(null, "192.0.2.13", 445); fail(); } catch (IllegalStateException expected) { }
        assertEquals(2, h.rows.size());
        try { h.remove(row.getUuid(), row.getListenIp(), 445); fail(); } catch (IllegalStateException expected) { }
        assertTrue(row.isEnabled()); assertEquals(StorageServiceInstance.ResourceState.Ready, row.getState());
    }

    @Test public void individualDeleteProtectsLastAndRejectsStaleTuple() throws Exception {
        Harness h = new Harness(); h.activate(null, "192.0.2.10", 445); h.activate(null, "192.0.2.11", 445);
        StorageServiceProtocolVO row = h.rows.get(1);
        try { h.remove(row.getUuid(), "192.0.2.99", 445); fail(); } catch (InvalidParameterValueException expected) { }
        assertEquals(2, h.rows.size()); h.remove(row.getUuid(), row.getListenIp(), 445);
        assertEquals(1, h.rows.size()); assertEquals(0, h.deletedGuard);
        row = h.rows.get(0);
        try { h.remove(row.getUuid(), row.getListenIp(), 445); fail(); } catch (InvalidParameterValueException expected) { }
        assertEquals(1, h.rows.size()); assertEquals(1, h.deletedGuard);
    }

    @Test public void wireUsesActualApiSerializerForListAndOptionalRuntimeFields() throws Exception {
        StorageServiceProtocolResponse first = new StorageServiceProtocolResponse();
        first.setId("11111111-1111-4111-8111-111111111111"); first.setProtocol("SMB"); first.setEnabled(true);
        first.setListenIp("192.0.2.10"); first.setPort(445); first.setRuntimeState("READY"); first.setLinkedResourceCount(1);
        first.setObservedAt("2026-10-26T00:00:00Z"); first.setObjectName("storageserviceprotocol");
        StorageServiceProtocolResponse second = new StorageServiceProtocolResponse();
        second.setId("22222222-2222-4222-8222-222222222222"); second.setProtocol("SMB"); second.setEnabled(true);
        second.setListenIp("192.0.2.11"); second.setPort(1445); second.setRuntimeState("PENDING"); second.setLinkedResourceCount(1);
        second.setCoveredById(first.getId()); second.setObjectName("storageserviceprotocol");
        ListResponse<StorageServiceProtocolResponse> list = new ListResponse<>();
        list.setResponses(Arrays.asList(first, second)); list.setResponseName("liststorageserviceprotocolsresponse");
        String wire = com.cloud.api.response.ApiResponseSerializer.toSerializedString(list, "json");
        JsonObject parsed = JsonParser.parseString(wire).getAsJsonObject();
        JsonObject root = parsed.getAsJsonObject("liststorageserviceprotocolsresponse");
        assertEquals(2, root.get("count").getAsInt());
        assertEquals("READY", root.getAsJsonArray("storageserviceprotocol").get(0).getAsJsonObject().get("runtimestate").getAsString());
        assertEquals(first.getId(), root.getAsJsonArray("storageserviceprotocol").get(1).getAsJsonObject().get("coveredbyid").getAsString());
        if (System.getProperty("storage.smb.fixture") != null) Files.writeString(Path.of(System.getProperty("storage.smb.fixture")), wire);
    }

    @Test public void preflightIncludesImmutableEndpointUuidAndExactTuple() throws Exception {
        EnableStorageServiceProtocolCmd cmd = new EnableStorageServiceProtocolCmd();
        for (String name : new String[]{"protocol", "listenIp", "endpointId", "port"}) {
            Field field = EnableStorageServiceProtocolCmd.class.getDeclaredField(name); field.setAccessible(true);
            field.set(cmd, name.equals("protocol") ? "SMB" : name.equals("listenIp") ? "192.0.2.11" : name.equals("port") ? Integer.valueOf(1445) : "22222222-2222-4222-8222-222222222222");
        }
        JsonObject intent = StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertEquals(1445, intent.get("port").getAsInt()); assertTrue(intent.has("endpointid")); assertTrue(intent.has("listenip"));
        StorageServiceConfigurationPreflight.parseIntent(intent.toString());
    }
}
