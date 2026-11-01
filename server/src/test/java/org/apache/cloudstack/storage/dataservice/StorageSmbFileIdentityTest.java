// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import com.google.gson.*;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
public class StorageSmbFileIdentityTest {
    private static void set(Object value,String name,Object item)throws Exception {Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,item);}
    @Test public void legacyOmissionAndPartialFixedUpdatesPreserveUnrelatedConfiguration() {
        String legacy="{\"keep\":true,\"createMask\":\"0640\"}";
        assertEquals(legacy,StorageSmbFileIdentity.merge(legacy,new JsonObject()));
        JsonObject patch=JsonParser.parseString("{\"mode\":\"FIXED\",\"uid\":7001,\"gid\":7002}").getAsJsonObject();
        JsonObject config=JsonParser.parseString(StorageSmbFileIdentity.merge(legacy,patch)).getAsJsonObject();
        assertTrue(config.get("keep").getAsBoolean());assertEquals("0640",config.get("createMask").getAsString());
        JsonObject delta=new JsonObject();delta.addProperty("uid",7003);
        JsonObject changed=JsonParser.parseString(StorageSmbFileIdentity.merge(config.toString(),delta)).getAsJsonObject();
        assertEquals(7002,changed.getAsJsonObject("fileIdentity").get("gid").getAsInt());
        delta=new JsonObject();delta.addProperty("mode","AUTHENTICATED");
        changed=JsonParser.parseString(StorageSmbFileIdentity.merge(changed.toString(),delta)).getAsJsonObject();
        assertFalse(changed.getAsJsonObject("fileIdentity").has("uid"));assertTrue(changed.get("keep").getAsBoolean());
    }
    @Test public void rawNamesProtectedIdsAndConflictingFlagsAreRejected() {
        for(String value:new String[]{"{\"mode\":\"ROOT\"}","{\"mode\":\"FIXED\",\"uid\":0,\"gid\":7002}","{\"mode\":\"FIXED\",\"uid\":7001}","{\"mode\":\"FIXED\",\"uid\":\"7001\",\"gid\":7002}","{\"mode\":\"FIXED\",\"forceUser\":\"root\"}"}) {
            try{StorageSmbFileIdentity.merge("{}",JsonParser.parseString(value).getAsJsonObject());fail(value);}catch(com.cloud.exception.InvalidParameterValueException expected){}
        }
        for(String flag:new String[]{"guestOk","inheritOwner"}) {
            JsonObject p=JsonParser.parseString("{\"mode\":\"FIXED\",\"uid\":7001,\"gid\":7002}").getAsJsonObject();
            try{StorageSmbFileIdentity.merge("{\""+flag+"\":true}",p);fail();}catch(com.cloud.exception.InvalidParameterValueException expected){}
        }
    }
    @Test public void actualCommandsAndPublicIntentUseTheSameTypedDeltaAndFullApply()throws Exception {
        UpdateStorageSmbShareCmd cmd=new UpdateStorageSmbShareCmd();set(cmd,"id",7L);set(cmd,"fileIdentityMode","FIXED");set(cmd,"fixedUid",7001L);set(cmd,"fixedGid",7002L);
        JsonObject actual=StorageServiceConfigurationPreflight.commandIntent(cmd);
        JsonObject ui=StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageSmbShare\",\"resourceid\":\"share\",\"smbfileidentity\":{\"mode\":\"FIXED\",\"uid\":7001,\"gid\":7002},\"smbfullapply\":true}");
        assertEquals(actual.get("smbfileidentity"),ui.get("smbfileidentity"));assertTrue(actual.get("smbfullapply").getAsBoolean());
        assertFalse(StorageSmbCreationPolicy.optionsOnly(cmd));
        assertTrue(StorageServiceConfigurationCoordinator.resourceType(cmd).length()<=32);
        CreateStorageSmbShareCmd create=new CreateStorageSmbShareCmd();set(create,"instanceId",1L);set(create,"fileIdentityMode","FIXED");set(create,"fixedUid",7001L);set(create,"fixedGid",7002L);set(create,"aclPrincipal","allowed");
        assertTrue(StorageServiceConfigurationPreflight.commandIntent(create).get("publicationrequired").getAsBoolean());
    }
    @Test public void capabilityAndMalformedStoredPolicyFailClosed() {
        try{StorageSmbFileIdentity.requireCapability(new JsonObject());fail();}catch(com.cloud.exception.InvalidParameterValueException expected){}
        for(String raw:new String[]{"{\"fileIdentity\":true}","{\"fileIdentity\":{\"version\":1,\"mode\":\"INVALID\"}}","{\"fileIdentity\":{\"version\":true,\"mode\":\"AUTHENTICATED\"}}"}) {
            try{StorageSmbFileIdentity.effective(JsonParser.parseString(raw).getAsJsonObject());fail(raw);}catch(RuntimeException expected){}
        }
    }
    @Test public void actualSerializerPublishesTheIdentityPreviewAsAStableComponentFixture()throws Exception {
        JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"revision\":1,\"observedat\":3500000000,\"expiresat\":3500000030,\"fingerprint\":\"fixture\",\"checks\":[],\"impact\":{\"ackrequired\":true},\"fileidentitypreview\":{\"mode\":\"FIXED\",\"uid\":7001,\"gid\":7002,\"publicationrequired\":false,\"directory\":{\"uid\":7001,\"gid\":7002,\"mode\":\"0770\"},\"existingobjectschange\":false}}").getAsJsonObject();
        var response=new StorageServiceConfigurationPreflightResponse(report);response.setResponseName("preflightstorageserviceconfigurationresponse");
        String wire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject json=JsonParser.parseString(wire).getAsJsonObject();
        assertEquals(7001,json.getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight").getAsJsonObject("fileidentitypreview").get("uid").getAsInt());
        String target=System.getProperty("fileidentity.wire.fixture");
        if(target!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(target),new GsonBuilder().setPrettyPrinting().create().toJson(json)+"\n");
    }
    @Test public void previewRetainsOriginalPolicyForLiveDriftChecksAndCandidateIsSeparate() throws Exception {
        var row=new StorageFileShareVO(1,StorageServiceInstance.Protocol.SMB,"team","/export/team",77L,"xfs",null,
                StorageServiceInstance.ResourceState.Ready,"{\"fileIdentity\":{\"version\":1,\"mode\":\"FIXED\",\"uid\":7001,\"gid\":7002}}");
        JsonObject intent=JsonParser.parseString("{\"api\":\"updateStorageSmbShare\",\"smbfileidentity\":{\"uid\":7003}}").getAsJsonObject();
        JsonObject target=StorageSmbFileIdentity.query(row,intent,new JsonObject(),true).getAsJsonObject("fileidentitytarget");
        assertEquals(7001,target.getAsJsonObject("currentConfig").getAsJsonObject("fileIdentity").get("uid").getAsInt());
        assertEquals(7003,target.getAsJsonObject("config").getAsJsonObject("fileIdentity").get("uid").getAsInt());
        assertTrue(target.get("verifyCurrent").getAsBoolean());assertTrue(target.get("publicationRequired").getAsBoolean());
        assertEquals(7001,JsonParser.parseString(row.getConfigJson()).getAsJsonObject().getAsJsonObject("fileIdentity").get("uid").getAsInt());
    }
}
