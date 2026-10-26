// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.user.storage.dataservice.ListStorageServiceProtocolsCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageServiceProtocolResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StorageServiceProtocolListContractTest {
    private static final long INSTANCE = 8L;
    private static final String OWNER = "11111111-1111-4111-8111-111111111111";
    private static final String CHILD = "22222222-2222-4222-8222-222222222222";

    private StorageServiceProtocolVO row(StorageServiceInstance.Protocol protocol, String ip, int port) {
        StorageServiceProtocolVO row = new StorageServiceProtocolVO(INSTANCE, protocol, true, ip, port);
        row.setState(StorageServiceInstance.ResourceState.Ready);
        return row;
    }

    private String listWire(List<StorageServiceProtocolVO> rows, String filter) {
        StorageServiceManagerImpl manager = Mockito.spy(new StorageServiceManagerImpl());
        StorageServiceProtocolDao protocols = Mockito.mock(StorageServiceProtocolDao.class);
        StorageServiceInstanceDao instances = Mockito.mock(StorageServiceInstanceDao.class);
        StorageFileShareDao shares = Mockito.mock(StorageFileShareDao.class);
        StorageServiceInstanceVO instance = Mockito.mock(StorageServiceInstanceVO.class);
        Mockito.when(instance.getId()).thenReturn(INSTANCE);
        Mockito.when(instance.getUuid()).thenReturn("33333333-3333-4333-8333-333333333333");
        Mockito.when(instances.findById(INSTANCE)).thenReturn(instance);
        Mockito.when(protocols.listByInstanceId(INSTANCE)).thenReturn(rows);
        for (StorageServiceInstance.Protocol protocol : StorageServiceInstance.Protocol.values()) {
            List<StorageServiceProtocolVO> selected = new ArrayList<>();
            for (StorageServiceProtocolVO row : rows) if (row.getProtocol() == protocol) selected.add(row);
            Mockito.when(protocols.listByInstanceIdAndProtocol(INSTANCE, protocol)).thenReturn(selected);
        }
        Mockito.when(shares.listByInstanceIdAndProtocol(INSTANCE, StorageServiceInstance.Protocol.SMB))
                .thenReturn(Collections.singletonList(Mockito.mock(StorageFileShareVO.class)));
        ReflectionTestUtils.setField(manager, "storageServiceProtocolDao", protocols);
        ReflectionTestUtils.setField(manager, "storageServiceInstanceDao", instances);
        ReflectionTestUtils.setField(manager, "storageFileShareDao", shares);
        Mockito.doReturn(instance).when(manager).requireInstance(INSTANCE);

        StorageServiceManagerImpl.ProtocolResponseContext context = new StorageServiceManagerImpl.ProtocolResponseContext();
        ReflectionTestUtils.setField(context, "primaryIp", "192.0.2.10");
        ReflectionTestUtils.setField(context, "runtimePrimaryIp", "192.0.2.10");
        ReflectionTestUtils.setField(context, "identityStatus", "CONSISTENT");
        ReflectionTestUtils.setField(context, "serviceIps", Arrays.asList("192.0.2.10", "192.0.2.11"));
        ReflectionTestUtils.setField(context, "smbRuntime", JsonParser.parseString("{\"version\":1,\"pending\":false,\"observedEpoch\":1793000000,\"listeners\":["
                + "{\"id\":\"" + OWNER + "\",\"listenIp\":\"0.0.0.0\",\"port\":445,\"state\":\"READY\"},"
                + "{\"id\":\"" + CHILD + "\",\"listenIp\":\"192.0.2.11\",\"port\":445,\"state\":\"COVERED\",\"coveredBy\":\"" + OWNER + "\"}]}").getAsJsonObject());
        Mockito.doReturn(context).when(manager).buildProtocolResponseContext(INSTANCE);
        ListStorageServiceProtocolsCmd cmd = Mockito.mock(ListStorageServiceProtocolsCmd.class);
        Mockito.when(cmd.getInstanceId()).thenReturn(INSTANCE);
        Mockito.when(cmd.getProtocol()).thenReturn(filter);

        // Exercise the actual service method, including canonicalization and DTO creation.
        ListResponse<StorageServiceProtocolResponse> response = manager.listStorageServiceProtocols(cmd);
        response.setResponseName("liststorageserviceprotocolsresponse");
        return com.cloud.api.response.ApiResponseSerializer.toSerializedString(response, "json");
    }

    private JsonObject root(String wire) {
        return JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("liststorageserviceprotocolsresponse");
    }

    @Test public void realServiceListAndSerializerKeepCoveredSmbUuidWithoutChangingOtherProtocols() throws Exception {
        List<StorageServiceProtocolVO> rows = new ArrayList<>();
        for (StorageServiceInstance.Protocol protocol : Arrays.asList(StorageServiceInstance.Protocol.NFS,
                StorageServiceInstance.Protocol.SMB, StorageServiceInstance.Protocol.ISCSI, StorageServiceInstance.Protocol.NVME_OF)) {
            int port = protocol == StorageServiceInstance.Protocol.NFS ? 2049 : protocol == StorageServiceInstance.Protocol.SMB ? 445 :
                    protocol == StorageServiceInstance.Protocol.ISCSI ? 3260 : 4420;
            StorageServiceProtocolVO owner = row(protocol, null, port);
            StorageServiceProtocolVO child = row(protocol, "192.0.2.11", port);
            if (protocol == StorageServiceInstance.Protocol.SMB) {
                ReflectionTestUtils.setField(owner, "uuid", OWNER);
                ReflectionTestUtils.setField(child, "uuid", CHILD);
            }
            rows.add(owner); rows.add(child);
        }
        String wire = listWire(rows, null);
        JsonObject root = root(wire);
        assertEquals(5, root.get("count").getAsInt());
        JsonArray protocols = root.getAsJsonArray("storageserviceprotocol");
        int smb = 0;
        for (int i = 0; i < protocols.size(); i++) {
            JsonObject protocol = protocols.get(i).getAsJsonObject();
            if (!"SMB".equals(protocol.get("protocol").getAsString())) continue;
            smb++;
            if (CHILD.equals(protocol.get("id").getAsString())) {
                assertEquals("COVERED", protocol.get("runtimestate").getAsString());
                assertEquals(OWNER, protocol.get("coveredbyid").getAsString());
                assertEquals("192.0.2.11", protocol.get("listenip").getAsString());
                assertEquals(445, protocol.get("port").getAsInt());
            }
        }
        assertEquals(2, smb);
        if (System.getProperty("storage.smb.service.fixture") != null)
            Files.writeString(Path.of(System.getProperty("storage.smb.service.fixture")), wire);
    }

    @Test public void smbOnlyFilterStillReturnsBothAddressableUuids() {
        StorageServiceProtocolVO owner = row(StorageServiceInstance.Protocol.SMB, "0.0.0.0", 445);
        StorageServiceProtocolVO child = row(StorageServiceInstance.Protocol.SMB, "192.0.2.11", 445);
        ReflectionTestUtils.setField(owner, "uuid", OWNER);
        ReflectionTestUtils.setField(child, "uuid", CHILD);
        JsonArray rows = root(listWire(Arrays.asList(owner, child), "SMB")).getAsJsonArray("storageserviceprotocol");
        assertEquals(2, rows.size());
        assertTrue(rows.toString().contains(CHILD));
    }

    @Test public void otherProtocolsRetainTheirExistingStateSensitiveCanonicalization() {
        for (StorageServiceInstance.Protocol protocol : Arrays.asList(StorageServiceInstance.Protocol.NFS,
                StorageServiceInstance.Protocol.ISCSI, StorageServiceInstance.Protocol.NVME_OF)) {
            StorageServiceProtocolVO owner = row(protocol, null, 10445);
            StorageServiceProtocolVO child = row(protocol, "192.0.2.11", 10445);
            assertEquals(1, root(listWire(Arrays.asList(owner, child), protocol.name())).get("count").getAsInt());
            child.setEnabled(false);
            assertEquals(2, root(listWire(Arrays.asList(owner, child), protocol.name())).get("count").getAsInt());
        }
    }
}
