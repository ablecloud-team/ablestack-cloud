// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package com.cloud.api.dispatch;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.storage.dataservice.PreflightStorageServiceConfigurationCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstance;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Exercise real dispatch marshalling, not only the service JSON parser or response DTO. */
public class ParamProcessWorkerNfsPreflightTest {
    private ParamProcessWorker worker;
    private static final String INSTANCE = "3730ab80-bd6c-4e33-b2db-13b1a55ee691";
    @Before public void setup() {
        CallContext.register(mock(User.class), mock(Account.class));
        worker=spy(new ParamProcessWorker());
        EntityManager entities=mock(EntityManager.class);
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(13L);
        when(entities.findByUuidIncludingRemoved(StorageServiceInstance.class,INSTANCE)).thenReturn(instance);
        ReflectionTestUtils.setField(worker,"_entityMgr",entities);
        // Authorization is outside this test; parameter length/type validation stays real.
        doNothing().when(worker).doAccessChecks(any(),anyMap());
    }
    @After public void cleanup() { CallContext.unregister(); }
    private String fixture() throws Exception {
        try (java.io.InputStream stream=getClass().getResourceAsStream("/storage/nfs-preflight-264.json")) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private PreflightStorageServiceConfigurationCmd dispatch(String raw) {
        PreflightStorageServiceConfigurationCmd cmd=new PreflightStorageServiceConfigurationCmd();
        worker.handle(new DispatchTask(cmd,Map.of("instanceid",INSTANCE,"intent",raw)));
        assertEquals(Long.valueOf(13),cmd.getInstanceId());
        assertEquals(raw,cmd.getIntent());
        return cmd;
    }
    private JsonObject serviceParse(String raw) throws Exception {
        return ReflectionTestUtils.invokeMethod(Class.forName("org.apache.cloudstack.storage.dataservice.StorageServiceConfigurationPreflight"),"parseIntent",raw);
    }
    @Test public void actualRejected264CharacterUiRequestPassesRealDispatch() throws Exception {
        String raw=fixture();assertEquals(264,raw.length());
        assertEquals("updateStorageNfsExport",serviceParse(dispatch(raw).getIntent()).get("api").getAsString());
    }
    @Test public void explicitZeroPermissionChangeAlsoPassesDispatch() throws Exception {
        JsonObject intent=JsonParser.parseString(fixture()).getAsJsonObject();
        JsonObject changes=new JsonObject();changes.addProperty("ownerUid",0);changes.addProperty("ownerGid",2345);changes.addProperty("mode","0770");
        intent.add("posixchanges",changes);String raw=intent.toString();assertTrue(raw.length()>255);
        assertEquals(0,serviceParse(dispatch(raw).getIntent()).getAsJsonObject("posixchanges").get("ownerUid").getAsInt());
    }
    @Test public void exact4096AsciiBoundaryPassesBothLayers() throws Exception {
        String value=fixture();String raw=value+" ".repeat(4096-value.length());
        assertEquals(4096,raw.getBytes(StandardCharsets.UTF_8).length);
        serviceParse(dispatch(raw).getIntent());
    }
    @Test(expected=ServerApiException.class) public void character4097IsRejectedByRealDispatcher() throws Exception {
        String value=fixture();dispatch(value+" ".repeat(4097-value.length()));
    }
    @Test(expected=com.cloud.exception.InvalidParameterValueException.class)
    public void utf8Over4096BytesStillFailsServiceEvenWhenCharacterCountFits() throws Exception {
        String value=fixture().replace("e1450-root-rw","가");
        String raw=value+" ".repeat(4097-value.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(raw.length()<4096);assertEquals(4097,raw.getBytes(StandardCharsets.UTF_8).length);
        serviceParse(dispatch(raw).getIntent());
    }
    @Test public void globalStringDefaultRemains255() throws Exception {
        assertEquals(255,Parameter.class.getMethod("length").getDefaultValue());
    }
}
