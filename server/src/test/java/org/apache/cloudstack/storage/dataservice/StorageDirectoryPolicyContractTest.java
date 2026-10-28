// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.lang.reflect.Field;
import java.nio.file.*;
import com.google.gson.*;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import org.apache.cloudstack.api.response.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StorageDirectoryPolicyContractTest {
    private static void set(Object target,String name,Object value)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @Test public void actualCommandAndUiPreflightCarryOnlyChangedPosix()throws Exception {
        UpdateStorageDirectoryPolicyCmd cmd=new UpdateStorageDirectoryPolicyCmd();
        set(cmd,"id",9L);set(cmd,"instanceId",1L);set(cmd,"expectedPolicyRevision",3L);set(cmd,"mode","0775");
        JsonObject intent=StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertEquals("updateStorageDirectoryPolicy",intent.get("api").getAsString());
        assertEquals(3,intent.get("expectedpolicyrevision").getAsInt());
        assertEquals(1,intent.getAsJsonObject("posixchanges").size());
        assertEquals("0775",intent.getAsJsonObject("posixchanges").get("mode").getAsString());
        JsonObject ui=StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageDirectoryPolicy\",\"resourceid\":\"policy\",\"expectedpolicyrevision\":3,\"posixchanges\":{\"mode\":\"0775\"}}");
        assertEquals(intent.get("posixchanges"),ui.get("posixchanges"));
        assertEquals("DirectoryPosixPolicy",StorageServiceConfigurationCoordinator.resourceType(cmd));
    }
    @Test public void actualSerializerPreservesDirectoryListAndPublicPreflightPreview()throws Exception {
        JsonObject row=new JsonObject();row.addProperty("status","OBSERVED");row.addProperty("owneruid",1001);row.addProperty("ownergid",1001);row.addProperty("mode","0770");
        JsonArray refs=new JsonArray();JsonObject ref=new JsonObject();ref.addProperty("uuid","nfs");ref.addProperty("name","team-nfs");ref.addProperty("protocol","NFS");refs.add(ref);
        StorageDirectoryPolicyResponse value=new StorageDirectoryPolicyResponse();
        value.setId("policy");value.setInstanceid("instance");value.setVolumeid("volume");value.setPath("/export/team");
        value.setOwneruid(1001L);value.setOwnergid(1001L);value.setMode("0770");value.setPolicyrevision(3L);
        value.setObservationstatus("APPLIED");value.setObservation(row);value.setReferences(refs);
        ListResponse<StorageDirectoryPolicyResponse> list=new ListResponse<>();list.setResponses(java.util.Collections.singletonList(value),1);
        list.setResponseName("liststoragedirectorypoliciesresponse");
        String listWire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(list,"json");
        JsonObject listObject=JsonParser.parseString(listWire).getAsJsonObject();
        assertEquals("policy",listObject.getAsJsonObject("liststoragedirectorypoliciesresponse").getAsJsonArray("directorypolicy").get(0).getAsJsonObject().get("id").getAsString());
        JsonObject report=new JsonObject();report.addProperty("status","READY");report.addProperty("revision",12);
        report.addProperty("observedat",3500000000L);report.addProperty("expiresat",3500000030L);report.addProperty("fingerprint","confirmed");
        report.add("checks",new JsonArray());JsonObject impact=new JsonObject();impact.addProperty("ackrequired",true);report.add("impact",impact);
        JsonObject preview=new JsonObject();preview.addProperty("entrycount",1);preview.add("references",refs);
        preview.add("before",row);JsonObject after=row.deepCopy();after.addProperty("mode","0775");preview.add("after",after);preview.add("units",new JsonArray());report.add("directorypreview",preview);
        StorageServiceConfigurationPreflightResponse response=new StorageServiceConfigurationPreflightResponse(report);
        response.setResponseName("preflightstorageserviceconfigurationresponse");
        String preflightWire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject preflight=JsonParser.parseString(preflightWire).getAsJsonObject();
        assertEquals("0775",preflight.getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight")
                .getAsJsonObject("directorypreview").getAsJsonObject("after").get("mode").getAsString());
        String target=System.getProperty("directory.wire.fixture");
        if(target!=null) {
            JsonObject fixture=new JsonObject();fixture.add("list",listObject);fixture.add("preflight",preflight);
            Files.writeString(Path.of(target),new GsonBuilder().setPrettyPrinting().create().toJson(fixture)+"\n");
        }
    }

    @Test public void expectedNativeConflictIsStructuredPreflightWithoutAnInternalError() {
        StorageServiceConfigurationPreflight gate=new StorageServiceConfigurationPreflight(
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao.class),
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class),
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class),
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class),
            org.mockito.Mockito.mock(com.cloud.storage.dao.VolumeDao.class),
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao.class),
            org.mockito.Mockito.mock(StorageServiceGuestCommandDispatcher.class),volume->{});
        gate.directoryObserver=(instance,intent,owner)->{throw new com.cloud.utils.exception.CloudRuntimeException("DIRECTORY_ACL_MASK_CONFLICT");};
        StorageServiceInstanceVO instance=new StorageServiceInstanceVO("test",null,1,2,13,1L,"SYSTEM_VM");
        JsonObject input=new JsonObject();input.addProperty("api","updateStorageDirectoryPolicy");
        JsonObject report=gate.observe(instance,input,null);
        assertEquals("BLOCKED",report.get("status").getAsString());
        assertEquals("DIRECTORY_ACL_MASK_CONFLICT",report.getAsJsonArray("checks").get(0).getAsJsonObject().get("code").getAsString());
    }

    @Test public void differentClientAliasBindsSamePhysicalDirectoryWithoutRelaxingLegacyPathRules() throws Exception {
        var policies=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDao.class);
        var shares=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        var rules=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class);
        var volumes=org.mockito.Mockito.mock(com.cloud.storage.dao.VolumeDao.class);
        var guest=org.mockito.Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        var instance=new StorageServiceInstanceVO("test",null,1,2,13,1L,"SYSTEM_VM");instance.setVmId(174L);
        var policy=new StorageDirectoryPolicyVO();set(policy,"id",9L);policy.setInstanceId(instance.getId());policy.setVolumeId(77);
        policy.setCanonicalPath("export/team");policy.setCanonicalHash("a".repeat(64));policy.setOwnerUid(1001);policy.setOwnerGid(1001);policy.setMode("0770");
        policy.setMetadataJson("{\"filesystemuuid\":\"fs\",\"inode\":42}");
        org.mockito.Mockito.when(policies.listByInstanceId(instance.getId())).thenReturn(java.util.Collections.singletonList(policy));
        org.mockito.Mockito.when(policies.findById(9L)).thenReturn(policy);org.mockito.Mockito.when(policies.findByUuid(policy.getUuid())).thenReturn(policy);
        var volume=org.mockito.Mockito.mock(com.cloud.storage.VolumeVO.class);
        org.mockito.Mockito.when(volume.getUuid()).thenReturn("volume");org.mockito.Mockito.when(volume.getInstanceId()).thenReturn(174L);
        org.mockito.Mockito.when(volume.getDataCenterId()).thenReturn(13L);org.mockito.Mockito.when(volume.getState()).thenReturn(com.cloud.storage.Volume.State.Ready);
        org.mockito.Mockito.when(volumes.findById(77L)).thenReturn(volume);
        var candidate=new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,"other","/export/other",77L,"xfs",null,StorageServiceInstance.ResourceState.Creating,"{}");
        JsonObject observed=JsonParser.parseString("{\"status\":\"OBSERVED\",\"volumeuuid\":\"volume\",\"canonicalpath\":\"export/team\",\"filesystemuuid\":\"fs\",\"inode\":42,\"owneruid\":1001,\"ownergid\":1001,\"mode\":\"0770\",\"filesystem\":\"xfs\"}").getAsJsonObject();
        JsonObject ref=new JsonObject();ref.addProperty("uuid",candidate.getUuid());ref.addProperty("protocol","SMB");ref.addProperty("name","other");
        JsonArray refs=new JsonArray();refs.add(ref);observed.add("references",refs);
        JsonObject actual=new JsonObject();actual.addProperty("uuid",policy.getUuid());actual.addProperty("revision",1);observed.add("policy",actual);
        JsonArray directories=new JsonArray();directories.add(observed);JsonObject response=new JsonObject();response.add("directories",directories);
        org.mockito.Mockito.when(guest.dispatch(org.mockito.Mockito.any())).thenAnswer(call->{
            StorageServiceGuestCommand command=call.getArgument(0);assertEquals("directory policy inspect",command.getOperation());
            JsonObject query=JsonParser.parseString(command.getPayload()).getAsJsonObject();
            assertEquals("/srv/ablestack-storage/volumes/volume/export/team",query.getAsJsonArray("shares").get(0).getAsJsonObject().getAsJsonObject("config").get("backingPath").getAsString());
            return new StorageServiceGuestCommandResult(true,"observed",response.toString());
        });
        new StorageDirectoryPolicies(policies,shares,rules,volumes,guest,v->{}).guardShare(instance,candidate,9L,false);
        assertEquals("/export/other",candidate.getPath());
        JsonObject saved=JsonParser.parseString(candidate.getConfigJson()).getAsJsonObject();
        assertEquals(policy.getUuid(),saved.get("directoryPolicyUuid").getAsString());
        assertEquals("/srv/ablestack-storage/volumes/volume/export/team",saved.get("backingPath").getAsString());
        assertFalse(saved.get("createDirectory").getAsBoolean());
    }
}
