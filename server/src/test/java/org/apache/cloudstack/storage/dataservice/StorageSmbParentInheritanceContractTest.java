// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.*;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import org.apache.cloudstack.api.response.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StorageSmbParentInheritanceContractTest {
    private static void set(Object target,String name,Object value)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @Test public void optionalBooleanMergePreservesUnrelatedConfigurationAndExplicitFalse() {
        String original="{\"keep\":\"원본\",\"directoryMode\":\"2770\"}";
        JsonObject legacy=JsonParser.parseString(StorageSmbParentInheritance.merge(original,null)).getAsJsonObject();
        assertFalse(legacy.has("inheritOwner"));assertEquals("2770",legacy.get("directoryMode").getAsString());
        String enabled=StorageSmbParentInheritance.merge(original,true);
        assertTrue(JsonParser.parseString(enabled).getAsJsonObject().get("inheritOwner").getAsBoolean());
        JsonObject disabled=JsonParser.parseString(StorageSmbParentInheritance.merge(enabled,false)).getAsJsonObject();
        assertFalse(disabled.get("inheritOwner").getAsBoolean());assertEquals("원본",disabled.get("keep").getAsString());
    }
    @Test public void actualCommandIntentAndStrictPublicInputShareOneBooleanContract()throws Exception {
        UpdateStorageSmbShareCmd cmd=new UpdateStorageSmbShareCmd();set(cmd,"id",7L);
        assertFalse(StorageServiceConfigurationPreflight.commandIntent(cmd).has("smbinheritance"));
        set(cmd,"inheritOwner",true);set(cmd,"directoryPolicyId",9L);
        JsonObject command=StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertTrue(command.getAsJsonObject("smbinheritance").get("inheritOwner").getAsBoolean());
        assertEquals("9",command.getAsJsonObject("smbinheritance").get("directoryPolicyId").getAsString());
        StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageSmbShare\",\"resourceid\":\"share\",\"smbinheritance\":{\"inheritOwner\":false,\"directoryPolicyId\":\"policy\"}}");
        for(String raw:new String[] {
            "{\"api\":\"updateStorageSmbShare\",\"smbinheritance\":{\"inheritOwner\":\"true\"}}",
            "{\"api\":\"updateStorageSmbShare\",\"smbinheritance\":{\"unexpected\":true}}",
            "{\"api\":\"updateStorageNfsExport\",\"smbinheritance\":{\"inheritOwner\":true}}"
        }) {
            try {StorageServiceConfigurationPreflight.parseIntent(raw);fail("invalid inheritance intent accepted");}
            catch(com.cloud.exception.InvalidParameterValueException expected) { }
        }
    }
    @Test public void uuidAndInternalPolicyIdProduceExactlyTheSameNormalizedParentQuery()throws Exception {
        var policies=mock(org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDao.class);
        var shares=mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        var volumes=mock(com.cloud.storage.dao.VolumeDao.class);
        var instance=new StorageServiceInstanceVO("test",null,1,2,13,1L,"SYSTEM_VM");instance.setVmId(174L);
        var policy=new StorageDirectoryPolicyVO();set(policy,"id",9L);policy.setInstanceId(instance.getId());policy.setVolumeId(77L);
        policy.setCanonicalPath("export/team");policy.setOwnerUid(100001);policy.setOwnerGid(200001);policy.setMode("2770");
        policy.setMetadataJson("{\"filesystemuuid\":\"fs\",\"inode\":42}");
        when(policies.findById(9L)).thenReturn(policy);when(policies.findByUuid(policy.getUuid())).thenReturn(policy);
        var volume=mock(com.cloud.storage.VolumeVO.class);when(volume.getUuid()).thenReturn("volume");
        when(volume.getDataCenterId()).thenReturn(13L);when(volume.getInstanceId()).thenReturn(174L);when(volume.getState()).thenReturn(com.cloud.storage.Volume.State.Ready);
        when(volumes.findById(77L)).thenReturn(volume);
        var resolver=new StorageDirectoryPolicies(policies,shares,mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class),volumes,mock(StorageServiceGuestCommandDispatcher.class),v->{});
        JsonObject publicIntent=new JsonObject();publicIntent.addProperty("api","createStorageSmbShare");
        JsonObject a=new JsonObject();a.addProperty("inheritOwner",true);a.addProperty("directoryPolicyId",policy.getUuid());publicIntent.add("smbinheritance",a);
        JsonObject internal=publicIntent.deepCopy();internal.getAsJsonObject("smbinheritance").addProperty("directoryPolicyId","9");
        assertEquals(StorageSmbParentInheritance.query(instance,null,publicIntent,resolver),StorageSmbParentInheritance.query(instance,null,internal,resolver));
    }
    @Test public void actualApiSerializationSuppliesTypedInheritanceAndModeOnlyUiFixture()throws Exception {
        JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"revision\":20,\"observedat\":3500000000,\"expiresat\":3500000030,\"fingerprint\":\"confirmed\",\"checks\":[],\"impact\":{\"ackrequired\":true},\"inheritancepreview\":{\"inheritowner\":true,\"setgid\":true,\"futureowner\":\"PARENT\",\"futuregroup\":\"PARENT\",\"existingobjectschange\":false,\"parent\":{\"owneruid\":100001,\"ownergid\":200001,\"mode\":\"2770\",\"inode\":42}}}").getAsJsonObject();
        var response=new StorageServiceConfigurationPreflightResponse(report);response.setResponseName("preflightstorageserviceconfigurationresponse");
        JsonObject wire=JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json")).getAsJsonObject();
        JsonObject value=wire.getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight").getAsJsonObject("inheritancepreview");
        assertTrue(value.get("inheritowner").getAsBoolean());assertEquals(100001,value.getAsJsonObject("parent").get("owneruid").getAsInt());
        var policy=new StorageDirectoryPolicyResponse();policy.setId("policy");policy.setInstanceid("instance");policy.setVolumeid("volume");policy.setPath("/export/team");
        policy.setOwneruid(100001L);policy.setOwnergid(200001L);policy.setMode("1770");policy.setPolicyrevision(4L);policy.setObservationstatus("APPLIED");
        JsonObject observed=value.getAsJsonObject("parent").deepCopy();observed.addProperty("mode","1770");observed.addProperty("status","OBSERVED");policy.setObservation(observed);
        ListResponse<StorageDirectoryPolicyResponse> list=new ListResponse<>();list.setResponses(java.util.Collections.singletonList(policy),1);list.setResponseName("liststoragedirectorypoliciesresponse");
        JsonObject fixture=new JsonObject();fixture.add("preflight",wire);
        fixture.add("list",JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(list,"json")));
        String target=System.getProperty("inheritance.wire.fixture");
        if(target!=null) Files.writeString(Path.of(target),new GsonBuilder().setPrettyPrinting().create().toJson(fixture)+"\n");
    }
}
