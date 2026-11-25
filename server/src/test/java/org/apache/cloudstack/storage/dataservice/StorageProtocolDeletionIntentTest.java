// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
package org.apache.cloudstack.storage.dataservice;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageServiceProtocolCmd;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
public class StorageProtocolDeletionIntentTest {
    @Test public void actualDeleteCommandPublicIntentsDefineTheUiContract() throws Exception {
        JsonArray cases=new JsonArray();
        String[][] input={{"protocolListener","ISCSI","0.0.0.0","3260"},{"nvmeListener","NVME_OF","0.0.0.0","4420"},
                {"protocol","NFS",null,null},{"protocolListener","SMB","192.0.2.2","445"}};
        for(String[] row:input) {
            DeleteStorageServiceProtocolCmd cmd=new DeleteStorageServiceProtocolCmd();
            for(String key:new String[]{"protocol","listenIp","port"}) {
                Field f=cmd.getClass().getDeclaredField(key);f.setAccessible(true);
                f.set(cmd,key.equals("protocol")?row[1]:key.equals("listenIp")?row[2]:row[3]==null?null:Integer.valueOf(row[3]));
            }
            JsonObject intent=StorageServiceConfigurationPreflight.commandIntent(cmd);
            assertEquals("deleteStorageServiceProtocol",intent.get("api").getAsString());
            assertEquals(row[1],intent.get("protocol").getAsString());
            assertFalse(intent.has("resourceid"));assertFalse(intent.has("id"));
            assertEquals(intent,StorageServiceConfigurationPreflight.parseIntent(intent.toString()));
            JsonObject form=new JsonObject();form.addProperty("protocol",row[1]);
            if(row[2]!=null)form.addProperty("listenip",row[2]);
            if(row[3]!=null)form.addProperty("port",Integer.valueOf(row[3]));
            JsonObject sample=new JsonObject();sample.addProperty("type",row[0]);sample.add("form",form);sample.add("intent",intent);cases.add(sample);
        }
        String output=System.getProperty("delete.intent.fixture");
        if(output!=null)Files.writeString(Path.of(output),new GsonBuilder().setPrettyPrinting().create().toJson(cases)+"\n");
    }
}
