// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd;
import org.apache.cloudstack.api.response.StorageSmbShareResponse;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StorageSmbClientAccessPolicyTest {
    @Test public void canonicalNetworkAndDuplicateContracts() {
        assertEquals("[\"10.10.13.0/24\",\"10.10.254.224/32\"]",
            StorageSmbClientAccessPolicy.normalize(JsonParser.parseString("[\"10.10.254.224\",\"10.10.13.22/24\"]")).toString());
        for(String raw:List.of("null","{}","[null]","[true]","[\"host.local\"]","[\"*\"]","[\"10.01.2.3\"]",
                "[\"10.0.0.1/0\"]","[\"::1\"]","[\"224.0.0.1\"]","[\"0.0.0.0\"]","[\"255.255.255.255\"]",
                "[\"10.0.0.1\",\"10.0.0.1/32\"]","[\"10.0.0.1/24\",\"10.0.0.0/24\"]")) {
            try {StorageSmbClientAccessPolicy.normalize(JsonParser.parseString(raw));fail(raw);}
            catch(InvalidParameterValueException expected) { }
        }
    }
    @Test public void omittedPreservesEmptyClearsOnlyWithConfirmationAndMetadataSurvives() {
        String original="{\"keep\":\"보존\",\"fileIdentity\":{\"version\":1,\"mode\":\"FIXED\",\"uid\":7001,\"gid\":7002}}";
        JsonObject add=StorageSmbClientAccessPolicy.fields(Map.of("clientcidrs","[\"10.0.0.1\"]"));
        String restricted=StorageSmbClientAccessPolicy.merge(original,add);
        assertEquals(restricted,StorageSmbClientAccessPolicy.merge(restricted,new JsonObject()));
        JsonObject clear=StorageSmbClientAccessPolicy.fields(Map.of("clientcidrs","[]"));
        try {StorageSmbClientAccessPolicy.merge(restricted,clear);fail();}catch(InvalidParameterValueException expected) { }
        clear.addProperty("confirmAnySource",true);
        assertEquals(JsonParser.parseString(original),JsonParser.parseString(StorageSmbClientAccessPolicy.merge(restricted,clear)));
    }
    @Test public void actualCommandUsesTheSameTypedPreflightAndSuppressesFullApply() {
        UpdateStorageSmbShareCmd cmd=new UpdateStorageSmbShareCmd();
        ReflectionTestUtils.setField(cmd,"id",77L);ReflectionTestUtils.setField(cmd,"clientCidrs","[\"10.0.0.27/24\"]");
        assertTrue(StorageSmbClientAccessPolicy.only(cmd));
        JsonObject actual=StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertFalse(actual.has("smbfullapply"));
        assertEquals("[\"10.0.0.0/24\"]",actual.getAsJsonObject("smbclientaccess").get("cidrs").toString());
        JsonObject publicIntent=actual.deepCopy();publicIntent.add("resourceid",publicIntent.remove("id"));
        assertEquals(publicIntent,StorageServiceConfigurationPreflight.parseIntent(publicIntent.toString()));
        ReflectionTestUtils.setField(cmd,"readOnly",true);
        try {StorageSmbClientAccessPolicy.only(cmd);fail();}catch(InvalidParameterValueException expected) { }
    }
    @Test public void actualApiSerializerKeepsConfigStringAndOptionalObservationObject() throws Exception {
        StorageSmbShareResponse response=new StorageSmbShareResponse();response.setId("share");
        response.setConfig("{\"clientAccess\":{\"version\":1,\"cidrs\":[\"10.0.0.1/32\"]}}");
        JsonObject observation=JsonParser.parseString("{\"status\":\"CONFIG_VERIFIED\",\"mode\":\"RESTRICTED\",\"cidrs\":[\"10.0.0.1/32\"],\"applyscope\":\"NEW_CONNECTIONS\"}").getAsJsonObject();
        response.setObjectName("storagesmbshare");response.setClientAccess(observation);response.setResponseName("updatestoragesmbshareresponse");response.setJobId("job");
        String wire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject row=JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("updatestoragesmbshareresponse").getAsJsonObject("storagesmbshare");
        assertTrue(row.get("config").isJsonPrimitive());assertEquals(observation,row.get("clientaccess"));
        String target=System.getProperty("smb.client.wire");
        if(target!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(target),wire+"\n");
    }
}
