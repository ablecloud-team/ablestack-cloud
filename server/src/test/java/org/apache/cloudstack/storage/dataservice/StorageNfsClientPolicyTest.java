// Licensed under the Apache License, Version 2.0. See NOTICE.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.lang.reflect.Field;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
public class StorageNfsClientPolicyTest {
    private static void field(Object target,String name,Object value) throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @Test public void uiAndApiPatchAreIdenticalAndCannotSelectAnExecutionMode() throws Exception {
        var cmd=new org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageNfsAclCmd();
        field(cmd,"id",19L);field(cmd,"principal","10.10.254.251/32");field(cmd,"permission","READ_ONLY");
        field(cmd,"rootSquash",false);field(cmd,"allSquash",false);field(cmd,"anonUid",1001);
        JsonObject ui=StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageNfsAcl\",\"protocol\":\"NFS\",\"resourceid\":\"acl\",\"nfsclientpolicy\":{\"permission\":\"READ_ONLY\",\"principal\":\"10.10.254.251/32\",\"anonuid\":1001,\"rootsquash\":false,\"allsquash\":false}}");
        JsonObject actual=StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertEquals(ui.get("nfsclientpolicy"),actual.get("nfsclientpolicy"));
        for(String invalid:new String[]{"{\"owneruid\":0}","{\"mode\":\"0777\"}","{\"executionmode\":\"ONLINE\"}","{\"rootsquash\":\"false\"}","{\"anonuid\":-1}"}) {
            try{StorageNfsClientPolicy.patch(JsonParser.parseString(invalid).getAsJsonObject());fail("unsafe patch accepted");}
            catch(com.cloud.exception.InvalidParameterValueException expected){ }
        }
        assertEquals(0,StorageNfsClientPolicy.fields(Map.of()).size());
    }
    @Test public void coldAndNonAclResourcesKeepLegacyAdmission() {
        var share=new StorageFileShareVO(1,StorageServiceInstance.Protocol.NFS,"source","/export/source",77L,"xfs",null,
                StorageServiceInstance.ResourceState.Allocated,"{}");
        JsonObject intent=JsonParser.parseString("{\"nfsclientpolicy\": {}}").getAsJsonObject();
        assertNull(StorageNfsClientPolicy.query("createStorageNfsAcl",intent,share,null));
        share.setState(StorageServiceInstance.ResourceState.Ready);
        assertNotNull(StorageNfsClientPolicy.query("createStorageNfsAcl",intent,share,null));
        assertNull(StorageNfsClientPolicy.query("deleteStorageNfsExport",intent,share,null));
        intent.addProperty("recursive",true);assertNull(StorageNfsClientPolicy.query("createStorageNfsAcl",intent,share,null));
    }
    @Test public void actualNativeOnlineAdmissionSurvivesRealApiSerialization() throws Exception {
        JsonObject fixture;
        try(var input=getClass().getResourceAsStream("/storage-config/storage-1460-online-native.json")) {
            assertNotNull(input);fixture=JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject report=fixture.getAsJsonObject("preflight");
        var response=new org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse(report);
        response.setResponseName("preflightstorageserviceconfigurationresponse");
        String wire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject parsed=JsonParser.parseString(wire).getAsJsonObject();
        JsonObject result=parsed.getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight");
        assertEquals("READY",result.get("status").getAsString());
        assertEquals("NFS_CLIENT_RELOAD",result.getAsJsonObject("impact").get("method").getAsString());
        assertEquals("ONLINE_RELOAD",result.getAsJsonObject("impact").get("class").getAsString());
        assertFalse(result.getAsJsonObject("impact").get("drainrequired").getAsBoolean());
        assertTrue(result.getAsJsonObject("impact").get("connections").getAsInt()>0);
        String output=System.getProperty("nfs.client.wire");
        if(output!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(output),wire);
    }
}
