// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
package org.apache.cloudstack.storage.dataservice;

import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.NicVO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSVO;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The removal callback, apply method, payload, target and ACL renderers are real. Only I/O boundaries are mocked. */
public class StorageBlockProtocolStateTest {
    static class Harness extends StorageServiceManagerImpl {
        final JsonObject source;
        final StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        final StorageServiceProtocolDao protocolDao=mock(StorageServiceProtocolDao.class);
        final List<StorageServiceProtocolVO> rows=new ArrayList<>();
        final StorageServiceGuestCommandDispatcher guest=mock(StorageServiceGuestCommandDispatcher.class);
        final StorageBlockTargetVO target;
        final NicVO nic=mock(NicVO.class);
        JsonObject rendered;
        Harness() throws Exception {
            try(var in=getClass().getResourceAsStream("/storage-config/storage-1462-block-source.json")) {
                source=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            }
            long instanceId=source.get("instanceId").getAsLong();
            when(instance.getId()).thenReturn(instanceId);
            when(instance.getUuid()).thenReturn(source.get("instanceUuid").getAsString());
            when(instance.getVmId()).thenReturn(225L);
            when(protocolDao.listByInstanceIdAndProtocol(eq(instanceId),any())).thenAnswer(i->new ArrayList<>(rows));
            when(protocolDao.findByInstanceIdAndProtocol(eq(instanceId),any())).thenAnswer(i->rows.isEmpty()?null:rows.get(0));
            when(protocolDao.remove(anyLong())).thenAnswer(i->rows.removeIf(row->row.getId()==(Long)i.getArgument(0)));
            ReflectionTestUtils.setField(this,"storageServiceProtocolDao",protocolDao);
            JsonObject dto=source.getAsJsonArray("targets").get(0).getAsJsonObject();
            JsonObject model=dto.deepCopy();model.addProperty("instanceId",instanceId);
            model.addProperty("configJson",dto.getAsJsonObject("config").toString());
            target=new Gson().fromJson(model,StorageBlockTargetVO.class);
            var targetDao=mock(StorageBlockTargetDao.class);
            when(targetDao.listByInstanceIdAndProtocol(instanceId,StorageServiceInstance.Protocol.ISCSI)).thenReturn(List.of(target));
            ReflectionTestUtils.setField(this,"storageBlockTargetDao",targetDao);
            var volumeDao=mock(VolumeDao.class);var volume=mock(VolumeVO.class);
            when(volumeDao.findById(target.getVolumeId())).thenReturn(volume);
            when(volume.getUuid()).thenReturn(dto.get("volumeUuid").getAsString());
            when(volume.getName()).thenReturn(dto.get("volumeName").getAsString());
            when(volume.getSize()).thenReturn(dto.get("volumeSizeBytes").getAsLong());
            if(dto.has("volumePath"))when(volume.getPath()).thenReturn(dto.get("volumePath").getAsString());
            if(dto.has("volumeDeviceId"))when(volume.getDeviceId()).thenReturn(dto.get("volumeDeviceId").getAsLong());
            ReflectionTestUtils.setField(this,"volumeDao",volumeDao);
            var ruleDao=mock(StorageAccessRuleDao.class);List<StorageAccessRuleVO> rules=new ArrayList<>();
            for(JsonElement item:dto.getAsJsonArray("acls")) {
                JsonObject value=item.getAsJsonObject();JsonObject rule=value.deepCopy();
                rule.addProperty("resourceType","BLOCK_TARGET");rule.addProperty("resourceId",target.getId());
                rule.addProperty("configJson",value.getAsJsonObject("config").toString());
                rules.add(new Gson().fromJson(rule,StorageAccessRuleVO.class));
            }
            when(ruleDao.listByResource(StorageServiceInstance.AccessResourceType.BLOCK_TARGET,target.getId())).thenReturn(rules);
            ReflectionTestUtils.setField(this,"storageAccessRuleDao",ruleDao);
            JsonObject network=source.getAsJsonArray("listeners").get(0).getAsJsonObject();
            when(nic.getId()).thenReturn(network.get("nicId").getAsLong());
            when(nic.getUuid()).thenReturn(network.get("nicUuid").getAsString());
            when(nic.getMacAddress()).thenReturn(network.get("macAddress").getAsString());
            when(nic.getNetworkId()).thenReturn(network.get("networkId").getAsLong());
            when(nic.getIPv4Address()).thenReturn(network.get("primaryIp").getAsString());
            var fsDao=mock(SharedFSDao.class);var fs=mock(SharedFSVO.class);
            when(fsDao.findByVm(225L)).thenReturn(fs);when(fs.getNetworkMode()).thenReturn(SharedFS.NetworkMode.STATIC);
            when(fs.getIpAddress()).thenReturn(network.get("primaryIp").getAsString());when(fs.getCidr()).thenReturn(network.get("networkCidr").getAsString());
            ReflectionTestUtils.setField(this,"sharedFSDao",fsDao);
            when(guest.dispatch(any())).thenAnswer(call->{
                StorageServiceGuestCommand command=call.getArgument(0);
                assertEquals("iscsi target apply",command.getOperation());
                rendered=JsonParser.parseString(command.getPayload()).getAsJsonObject();
                return new StorageServiceGuestCommandResult(true,"captured actual renderer","{\"success\":true,\"listening\":true}");
            });
            ReflectionTestUtils.setField(this,"guestCommandDispatcher",guest);
        }
        @Override protected NicVO resolveProtocolListenAddress(StorageServiceInstanceVO instance,String ip) { return nic; }
        StorageServiceProtocolVO row(StorageServiceInstance.Protocol protocol,String ip,Integer port,boolean enabled) {
            var row=new StorageServiceProtocolVO(instance.getId(),protocol,enabled,ip,port);
            ReflectionTestUtils.setField(row,"id",(long)rows.size()+1);
            row.setState(StorageServiceInstance.ResourceState.Ready);rows.add(row);return row;
        }
        JsonObject payload(StorageServiceInstance.Protocol type) { return buildBlockProtocolPayload(instance,type); }
        boolean remove() { return deleteStorageServiceEndpoint(instance,StorageServiceInstance.Protocol.ISCSI,"0.0.0.0",3260); }
    }

    @Test public void actualRemovalCallbackRendersRemainingActiveListenerBeforeDeletingOldFirstRow() throws Exception {
        Harness h=new Harness();
        h.row(StorageServiceInstance.Protocol.ISCSI,null,null,true);
        h.row(StorageServiceInstance.Protocol.ISCSI,"10.10.13.227",3261,true);
        assertTrue(h.remove());
        assertTrue(h.rendered.get("enabled").getAsBoolean());
        assertEquals("10.10.13.227",h.rendered.get("listenIp").getAsString());
        assertEquals(3261,h.rendered.get("port").getAsInt());
        assertEquals(h.source.get("targets"),h.rendered.get("targets"));
        assertEquals(h.source.get("listeners"),h.rendered.get("listeners"));
        assertEquals(1,h.rows.size());assertTrue(h.rows.get(0).isEnabled());
        verify(h.protocolDao,never()).findByInstanceIdAndProtocol(anyLong(),any());
        String output=System.getProperty("block.render.fixture");
        if(output!=null)Files.writeString(Path.of(output),new GsonBuilder().setPrettyPrinting().create().toJson(h.rendered)+"\n");
    }
    @Test public void iscsiAndNvmeUseAllRowsForEveryOrderingAndRetainLegacyEmptyAndDisabledSemantics() throws Exception {
        for(var type:List.of(StorageServiceInstance.Protocol.ISCSI,StorageServiceInstance.Protocol.NVME_OF)) {
            int activePort=type==StorageServiceInstance.Protocol.ISCSI?3261:4421;
            for(boolean reverse:List.of(false,true)) {
                Harness h=new Harness();
                var old=h.row(type,null,null,false);
                var active=h.row(type,"10.10.13.227",activePort,true);
                if(reverse)Collections.reverse(h.rows);
                JsonObject value=h.payload(type);
                assertTrue(value.get("enabled").getAsBoolean());assertEquals(activePort,value.get("port").getAsInt());
                active.setEnabled(false);
                assertFalse(h.payload(type).get("enabled").getAsBoolean());
                h.rows.clear();
                value=h.payload(type);assertTrue(value.get("enabled").getAsBoolean());
                assertEquals(1,value.getAsJsonArray("listeners").size());
                assertEquals(type==StorageServiceInstance.Protocol.ISCSI?3260:4420,value.getAsJsonArray("listeners").get(0).getAsJsonObject().get("port").getAsInt());
            }
        }
    }
}
