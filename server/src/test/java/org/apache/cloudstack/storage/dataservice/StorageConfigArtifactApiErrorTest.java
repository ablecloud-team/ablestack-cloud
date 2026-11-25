// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
package org.apache.cloudstack.storage.dataservice;

import com.cloud.api.ApiServer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.storage.dataservice.ManageStorageConfigArtifactCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.ApplyStorageConfigRestoreCmd;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

public class StorageConfigArtifactApiErrorTest {
    @Test public void actualApiServerErrorSerializationRetainsCommandEnvelopeAndBlockingReason() throws Exception {
        ApiServer api=spy(new ApiServer());
        doReturn(ManageStorageConfigArtifactCmd.class).when(api).getCmdClass("manageStorageConfigArtifact");
        doReturn(ApplyStorageConfigRestoreCmd.class).when(api).getCmdClass("applyStorageConfigRestore");
        JsonObject fixture=new JsonObject();
        for (String command : new String[] {"manageStorageConfigArtifact","applyStorageConfigRestore"}) {
            String reason=command.startsWith("manage")?"RESTORE_EXPLICIT_LISTENER_REQUIRED":"DRAIN_REQUIRED";
            String raw=api.getSerializedApiError(new ServerApiException(ApiErrorCode.PARAM_ERROR,reason),
                Map.<String,Object[]>of(ApiConstants.COMMAND,new String[]{command}),"json");
            assertNotNull(raw);
            JsonObject wire=JsonParser.parseString(raw).getAsJsonObject();
            JsonObject body=wire.getAsJsonObject(command.toLowerCase()+"response");
            assertEquals(431,body.get("errorcode").getAsInt());
            assertEquals(reason,body.get("errortext").getAsString());
            assertFalse(wire.has("errorresponse"));
            fixture.add(command,wire);
        }
        String output=System.getProperty("config.restore.error.fixture");
        if(output!=null)Files.writeString(Path.of(output),new GsonBuilder().setPrettyPrinting().create().toJson(fixture)+"\n");
    }
}
