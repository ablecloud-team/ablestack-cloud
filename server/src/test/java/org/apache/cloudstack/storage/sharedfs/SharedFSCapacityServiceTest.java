// Licensed under the Apache License, Version 2.0. See NOTICE.
package org.apache.cloudstack.storage.sharedfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.Field;
import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.api.response.ApiResponseSerializer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SharedFSCapacityResponse;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.storage.sharedfs.query.vo.SharedFSJoinVO;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class SharedFSCapacityServiceTest {
    static final String DATA = "e070a0aa-dbf5-48c0-83e0-09f874446277";
    static final long VM = 9, OWNER = 2, GIB = 1024L * 1024 * 1024;
    JsonObject nativeWire;
    Instant now;
    @Before public void readNative() throws Exception {
        try (var in = getClass().getResourceAsStream("/sharedfs-capacity-native.json")) {
            nativeWire = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        now = Instant.parse(nativeWire.get("observedAt").getAsString()).plusSeconds(5);
    }
    static VolumeVO volume(long id, String uuid, long size, Long vm, boolean visible, Volume.Type type) throws Exception {
        VolumeVO v = new VolumeVO(type, "data-" + id, 1, 1, OWNER, 1, Storage.ProvisioningType.SPARSE, size, null, null, null);
        Field key = VolumeVO.class.getDeclaredField("id"); key.setAccessible(true); key.set(v, id);
        v.setUuid(uuid); v.setInstanceId(vm); v.setDeviceId(id); v.setState(Volume.State.Ready); v.setDisplayVolume(visible);
        return v;
    }
    static List<VolumeVO> data() throws Exception {
        return new ArrayList<>(List.of(volume(1, DATA, 10*GIB, VM, false, Volume.Type.DATADISK),
            volume(2, "raw", 4*GIB, VM, true, Volume.Type.DATADISK),
            volume(3, "unreferenced", 8*GIB, VM, true, Volume.Type.DATADISK)));
    }
    SharedFSCapacityService.Snapshot snapshot(List<VolumeVO> rows, JsonObject json) {
        return new SharedFSCapacityService.Snapshot(SharedFSCapacityService.fingerprint(rows, VM), json, now);
    }
    SharedFSCapacityResponse capacity(List<VolumeVO> rows, JsonObject json, boolean details) {
        return SharedFSCapacityService.aggregate(VM, OWNER, 1, 10*GIB, rows, json == null ? null : snapshot(rows,json), details, now, 180);
    }
    JsonObject partial() {
        JsonObject copy = nativeWire.deepCopy();
        JsonObject raw = new JsonObject(); raw.addProperty("volumeUuid","raw"); raw.addProperty("mappingStatus","EXACT"); raw.addProperty("status","UNSUPPORTED");
        raw.add("filesystems",new JsonArray()); copy.getAsJsonArray("observations").add(raw);
        return copy;
    }
    @Test public void includesHiddenAndUnreferencedAttachedDataExcludesRootDetachedAndDestroyed() throws Exception {
        List<VolumeVO> rows=data();
        rows.add(volume(4,"root",5*GIB,VM,true,Volume.Type.ROOT));
        rows.add(volume(5,"detached",20*GIB,null,true,Volume.Type.DATADISK));
        VolumeVO removed=volume(6,"destroyed",30*GIB,VM,true,Volume.Type.DATADISK); removed.setState(Volume.State.Destroy); rows.add(removed);
        rows.add(rows.get(0));
        var c=capacity(rows,partial(),true);
        assertEquals(Long.valueOf(22*GIB),c.provisionedBytes); assertEquals(Integer.valueOf(3),c.volumeCount);
        assertEquals("PARTIAL",c.usageStatus); assertEquals(1,c.filesystemCount); assertEquals(1,c.observedVolumeCount);
        assertEquals(1,c.unsupportedVolumeCount); assertEquals(1,c.unobservedVolumeCount); assertEquals(3,c.volumes.size());
        assertEquals(Long.valueOf(108703744),c.filesystemUsedBytes);
        assertTrue(c.volumes.stream().anyMatch(v->v.hidden));
    }
    @Test public void commonFilesystemFromTwoVolumeObservationsCountsUsageOnce() throws Exception {
        List<VolumeVO> rows=data(); JsonObject wire=partial();
        JsonObject alias=nativeWire.getAsJsonArray("observations").get(0).getAsJsonObject().deepCopy();alias.addProperty("volumeUuid","unreferenced");
        wire.getAsJsonArray("observations").add(alias);
        var c=capacity(rows,wire,false);
        assertEquals(1,c.filesystemCount);assertEquals(2,c.observedVolumeCount);assertEquals(Long.valueOf(108703744),c.filesystemUsedBytes);
        assertNull(c.volumes);
    }
    @Test public void coldStaleZeroAndMembershipChangeAreDistinct() throws Exception {
        List<VolumeVO> rows=data();assertNull(capacity(rows,null,false).filesystemUsedBytes);
        JsonObject zero=partial();zero.getAsJsonArray("observations").get(0).getAsJsonObject().getAsJsonArray("filesystems").get(0).getAsJsonObject().addProperty("usedBytes",0);
        assertEquals(Long.valueOf(0),capacity(rows,zero,false).filesystemUsedBytes);
        JsonObject stale=partial();stale.addProperty("status","STALE");
        assertEquals("STALE",capacity(rows,stale,false).usageStatus);
        var old=snapshot(rows,partial());rows.get(0).setSize(11*GIB);
        var changed=SharedFSCapacityService.aggregate(VM,OWNER,1,10*GIB,rows,old,false,now,180);
        assertEquals("TRANSITION",changed.usageStatus);assertNull(changed.filesystemUsedBytes);
    }
    @Test public void staleTimeAndUnsupportedRuntimeNeverInventZero() throws Exception {
        List<VolumeVO> rows=data();JsonObject old=partial();old.addProperty("observedAt",now.minusSeconds(181).toString());
        assertEquals("STALE",capacity(rows,old,false).usageStatus);
        var unsupported=capacity(rows,new JsonObject(),false);
        assertEquals("UNSUPPORTED",unsupported.usageStatus);assertNull(unsupported.filesystemUsedBytes);
        JsonObject missing=partial();missing.remove("observedAt");
        assertEquals("UNAVAILABLE",capacity(rows,missing,false).usageStatus);
    }
    @Test public void crossAccountDataAndArithmeticOverflowAreNotPublished() throws Exception {
        List<VolumeVO> rows=data();rows.get(1).setAccountId(99);
        assertNull(capacity(rows,partial(),true).provisionedBytes);assertNull(capacity(rows,partial(),true).volumes);
        rows=data();rows.get(0).setSize(Long.MAX_VALUE);
        assertEquals("UNAVAILABLE",capacity(rows,null,false).provisioningStatus);assertNull(capacity(rows,null,false).provisionedBytes);
    }
    @Test public void listingUsesOneBatchAndNoDispatcherEvenFor100Services() throws Exception {
        var service=new SharedFSCapacityService();service.volumeDao=mock(VolumeDao.class);
        service.dispatcher=mock(org.apache.cloudstack.storage.dataservice.StorageServiceGuestCommandDispatcher.class);
        List<SharedFSResponse> responses=new ArrayList<>();List<SharedFSJoinVO> views=new ArrayList<>();
        for(int i=0;i<100;i++){var view=mock(SharedFSJoinVO.class);when(view.getUuid()).thenReturn("fs-"+i);when(view.getInstanceId()).thenReturn(100L+i);when(view.getAccountId()).thenReturn(OWNER);views.add(view);var response=new SharedFSResponse();response.setId("fs-"+i);responses.add(response);}
        when(service.volumeDao.listCurrentDataByVmIds(anyCollection())).thenReturn(Collections.emptyList());
        service.enrich(responses,views,false);
        verify(service.volumeDao,times(1)).listCurrentDataByVmIds(argThat(ids->ids.size()==100));verifyNoInteractions(service.dispatcher);
    }
    @Test public void actualApiSerializerPreservesLegacyFieldsAndCapacityShape() throws Exception {
        SharedFSResponse response=new SharedFSResponse();response.setId("sharedfs-capacity-wire");response.setObjectName("sharedfs");
        response.setSize(10*GIB);response.setSizeGB(10*GIB);response.setCapacity(capacity(data(),partial(),true));
        ListResponse<SharedFSResponse> list=new ListResponse<>();list.setResponseName("listsharedfilesystemsresponse");list.setResponses(List.of(response),1);
        String json=ApiResponseSerializer.toSerializedString(list,"json");
        JsonObject wire=JsonParser.parseString(json).getAsJsonObject();
        var row=wire.getAsJsonObject("listsharedfilesystemsresponse").getAsJsonArray("sharedfs").get(0).getAsJsonObject();
        assertEquals(10*GIB,row.get("size").getAsLong());assertEquals("10.00 GiB",row.get("sizegb").getAsString());
        assertTrue(row.get("capacity").isJsonObject());assertEquals(3,row.getAsJsonObject("capacity").get("volumecount").getAsInt());
        String output=System.getProperty("sharedfs.capacity.wire");
        if(output!=null)Files.writeString(Path.of(output),new GsonBuilder().setPrettyPrinting().create().toJson(wire)+"\n");
    }
}
