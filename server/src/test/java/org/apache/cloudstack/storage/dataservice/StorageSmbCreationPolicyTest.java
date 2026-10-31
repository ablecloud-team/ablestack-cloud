// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.*;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import org.apache.cloudstack.api.response.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StorageSmbCreationPolicyTest {
    static void set(Object o,String name,Object v)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    static JsonObject json(String value) {return JsonParser.parseString(value).getAsJsonObject();}
    @Test public void strictOctalMergePreservesLegacyRawMissingAndPosix() {
        String original="{\"keep\":\"원본\",\"directoryMode\":\"2770\",\"ownerUid\":100001}";
        JsonObject unchanged=json(StorageSmbCreationPolicy.merge(original,new JsonObject()));
        assertFalse(unchanged.has("createMask"));assertEquals("2770",unchanged.get("directoryMode").getAsString());
        JsonObject changed=json(StorageSmbCreationPolicy.merge(original,json("{\"createMask\":\"640\"}")));
        assertEquals("0640",changed.get("createMask").getAsString());assertFalse(changed.has("forceCreateMode"));
        assertEquals(100001,changed.get("ownerUid").getAsInt());assertEquals("0770",StorageSmbCreationPolicy.effective(changed).get("directoryMask").getAsString());
        for(String raw:new String[]{"{\"createMask\":660}","{\"createMask\":\"0888\"}","{\"createMask\":\"1777\"}","{\"createMask\":\"\"}","{\"createMask\":\"00660\"}","{\"extra\":\"0660\"}","{\"createMask\":\"0600\",\"forceCreateMode\":\"0040\"}"}) {
            try {StorageSmbCreationPolicy.merge(original,json(raw));fail("invalid mode accepted");}
            catch(com.cloud.exception.InvalidParameterValueException expected) { }
        }
    }
    @Test public void realApiInputWorkerPreservesOctalAndEnforcesFourCharacters()throws Exception {
        var worker=new com.cloud.api.dispatch.ParamProcessWorker();
        var method=worker.getClass().getDeclaredMethod("setFieldValue",Field.class,BaseCmd.class,Object.class,Parameter.class);method.setAccessible(true);
        for(BaseStorageServiceConfigurationCmd cmd:new BaseStorageServiceConfigurationCmd[]{new CreateStorageSmbShareCmd(),new UpdateStorageSmbShareCmd()})
            for(String name:StorageSmbCreationPolicy.DEFAULTS.keySet()) {
                Field field=cmd.getClass().getDeclaredField(name);field.setAccessible(true);
                Parameter annotation=field.getAnnotation(Parameter.class);assertEquals(4,annotation.length());
                method.invoke(worker,field,cmd,"0660",annotation);assertEquals("0660",field.get(cmd));
                try {method.invoke(worker,field,cmd,"00660",annotation);fail("actual input worker truncated/accepted mode");}
                catch(InvocationTargetException error) {assertTrue(error.getCause() instanceof com.cloud.exception.InvalidParameterValueException);}
            }
    }
    @Test public void publicPreflightAndActualCommandIntentHaveSamePatch()throws Exception {
        var cmd=new UpdateStorageSmbShareCmd();set(cmd,"id",7L);set(cmd,"createMask","0640");
        assertTrue(StorageSmbCreationPolicy.optionsOnly(cmd));
        JsonObject intent=StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertEquals(json("{\"createMask\":\"0640\"}"),intent.getAsJsonObject("smbcreation"));
        StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageSmbShare\",\"resourceid\":\"share\",\"smbcreation\":{\"createMask\":\"0640\"}}");
        set(cmd,"path","/export/other");assertFalse(StorageSmbCreationPolicy.optionsOnly(cmd));
        assertTrue(StorageServiceConfigurationPreflight.commandIntent(cmd).get("smbfullapply").getAsBoolean());
        for(String raw:new String[]{"{\"api\":\"updateStorageNfsExport\",\"smbcreation\":{\"createMask\":\"0640\"}}","{\"api\":\"updateStorageSmbShare\",\"smbcreation\":{\"createMask\":640}}","{\"api\":\"updateStorageSmbShare\",\"smboptions\":{\"readOnly\":\"true\"}}"}) {
            try {StorageServiceConfigurationPreflight.parseIntent(raw);fail("invalid public intent accepted");}
            catch(com.cloud.exception.InvalidParameterValueException expected) { }
        }
    }
    @Test public void actualApiSerializerRetainsConfigStringAndTypedCreationPreview()throws Exception {
        JsonObject report=json("{\"status\":\"READY\",\"revision\":20,\"observedat\":3500000000,\"expiresat\":3500000030,\"fingerprint\":\"creation-confirmed\",\"checks\":[],\"impact\":{\"class\":\"ONLINE_RELOAD\",\"ackrequired\":true},\"creationpreview\":{\"before\":{\"createMask\":\"0660\",\"directoryMask\":\"0770\",\"forceCreateMode\":\"0000\",\"forceDirectoryMode\":\"0000\"},\"after\":{\"createMask\":\"0640\",\"directoryMask\":\"0770\",\"forceCreateMode\":\"0000\",\"forceDirectoryMode\":\"0000\"},\"existingobjectschange\":false,\"parent\":{\"owneruid\":100001,\"ownergid\":200001,\"mode\":\"2770\"}}}");
        var response=new StorageServiceConfigurationPreflightResponse(report);response.setResponseName("preflightstorageserviceconfigurationresponse");
        var share=new StorageSmbShareResponse();share.setObjectName("storagesmbshare");share.setId("share");share.setName("team");share.setConfig("{\"directoryMode\":\"2770\",\"inheritOwner\":true}");
        var list=new ListResponse<StorageSmbShareResponse>();list.setResponses(java.util.Collections.singletonList(share),1);list.setResponseName("liststoragesmbsharesresponse");
        JsonObject fixture=new JsonObject();
        fixture.add("preflight",JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json")));
        fixture.add("list",JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(list,"json")));
        assertTrue(fixture.getAsJsonObject("list").getAsJsonObject("liststoragesmbsharesresponse").getAsJsonArray("storagesmbshare").get(0).getAsJsonObject().get("config").getAsJsonPrimitive().isString());
        assertTrue(fixture.getAsJsonObject("preflight").getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight").has("creationpreview"));
        String target=System.getProperty("creation.wire.fixture");
        if(target!=null)Files.writeString(Path.of(target),new GsonBuilder().setPrettyPrinting().create().toJson(fixture)+"\n");
    }
}
