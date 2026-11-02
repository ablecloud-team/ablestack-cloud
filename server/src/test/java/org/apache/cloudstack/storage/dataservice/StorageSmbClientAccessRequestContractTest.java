// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.nio.file.*;
import java.util.*;
import com.cloud.api.dispatch.*;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.*;
import com.cloud.utils.db.EntityManager;
import com.google.gson.*;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.*;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StorageSmbClientAccessRequestContractTest {
    private static final String SHARE="0fc08aa0-61e2-4a35-a23f-94eb4afcf7de";
    private static class InputWorker extends ParamProcessWorker {
        @Override protected void doAccessChecks(BaseCmd cmd,Map<Object,AccessType> entities) { }
    }
    @Test public void actualMarshallerAndBothGuestQueriesMatchUiGeneratedSourceRequests() throws Exception {
        JsonArray cases=JsonParser.parseString(Files.readString(Path.of("../ui/tests/fixtures/storage-1457-client-contract.json"))).getAsJsonArray();
        assertEquals(4,cases.size());
        CallContext.register(mock(User.class),mock(Account.class));
        try {
            for(JsonElement element:cases) {
                JsonObject sample=element.getAsJsonObject();
                JsonObject config=new JsonObject();config.addProperty("keep","보존");
                if(sample.getAsJsonArray("before").size()>0) {
                    JsonObject policy=new JsonObject();policy.addProperty("version",1);policy.add("cidrs",sample.get("before"));config.add("clientAccess",policy);
                }
                StorageFileShareVO row=new StorageFileShareVO(91,StorageServiceInstance.Protocol.SMB,"source-policy","/export/source-policy",355L,"xfs",null,StorageServiceInstance.ResourceState.Ready,config.toString());
                ReflectionTestUtils.setField(row,"id",77L);ReflectionTestUtils.setField(row,"uuid",SHARE);
                InputWorker worker=new InputWorker();EntityManager entities=mock(EntityManager.class);
                when(entities.findByUuidIncludingRemoved(any(),eq(SHARE))).thenReturn(row);
                ReflectionTestUtils.setField(worker,"_entityMgr",entities);
                Map<String,String> params=new HashMap<>();sample.getAsJsonObject("request").entrySet().forEach(e->params.put(e.getKey(),e.getValue().getAsString()));
                UpdateStorageSmbShareCmd cmd=new UpdateStorageSmbShareCmd();worker.handle(new DispatchTask(cmd,params));
                assertTrue(StorageSmbClientAccessPolicy.only(cmd));
                JsonObject actual=StorageServiceConfigurationPreflight.commandIntent(cmd);
                JsonObject ui=StorageServiceConfigurationPreflight.parseIntent(sample.get("intent").toString());
                assertEquals(ui.get("smbclientaccess"),actual.get("smbclientaccess"));
                assertFalse(actual.has("smbfullapply"));
                StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
                when(instance.getId()).thenReturn(91L);when(instance.getVmId()).thenReturn(195L);when(instance.getUuid()).thenReturn("instance");
                when(instance.getConfigurationRevision()).thenReturn(47L);when(instance.getDataCenterId()).thenReturn(1L);
                StorageFileShareDao shares=mock(StorageFileShareDao.class);when(shares.findById(77L)).thenReturn(row);when(shares.findByUuid(SHARE)).thenReturn(row);
                when(shares.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(row));
                VolumeDao volumes=mock(VolumeDao.class);VolumeVO volume=mock(VolumeVO.class);
                when(volume.getId()).thenReturn(355L);when(volume.getUuid()).thenReturn("volume");when(volume.getState()).thenReturn(Volume.State.Ready);
                when(volume.getInstanceId()).thenReturn(195L);when(volume.getDataCenterId()).thenReturn(1L);when(volumes.findById(355L)).thenReturn(volume);
                StorageServiceProtocolDao protocols=mock(StorageServiceProtocolDao.class);
                StorageServiceProtocolVO protocol=mock(StorageServiceProtocolVO.class);when(protocol.getProtocol()).thenReturn(StorageServiceInstance.Protocol.SMB);
                when(protocol.isEnabled()).thenReturn(true);when(protocol.getPort()).thenReturn(445);when(protocol.getConfigJson()).thenReturn("{}");
                when(protocols.listByInstanceId(91L)).thenReturn(List.of(protocol));
                when(protocols.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(protocol));
                StorageServiceGuestCommandDispatcher guest=mock(StorageServiceGuestCommandDispatcher.class);
                List<JsonObject> queries=new ArrayList<>();
                when(guest.dispatch(any())).thenAnswer(call->{
                    StorageServiceGuestCommand command=call.getArgument(0);queries.add(JsonParser.parseString(command.getPayload()).getAsJsonObject());
                    return new StorageServiceGuestCommandResult(true,"captured","{\"status\":\"READY\",\"checks\":[],\"fingerprint\":\"captured\",\"impact\":{\"ackrequired\":true}}");
                });
                StorageServiceConfigurationPreflight service=new StorageServiceConfigurationPreflight(protocols,shares,mock(StorageBlockTargetDao.class),
                        mock(StorageAccessRuleDao.class),volumes,mock(StorageServiceRuntimeUpgradeDao.class),guest,v->{});
                service.observe(instance,ui,1L);service.observe(instance,actual,1L);
                assertEquals(sample.get("name").getAsString(),queries.get(0),queries.get(1));
            }
        } finally {CallContext.unregister();}
    }
    @Test public void actualPreflightSerializationCarriesSourcePreviewWithoutChangingSharedContract() throws Exception {
        JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"revision\":47,\"observedat\":1793580000,\"expiresat\":1793580030,\"fingerprint\":\"wire-fixture\",\"checks\":[],\"impact\":{\"ackrequired\":true},\"profile\":{},\"clientaccesspreview\":{\"before\":[],\"after\":[\"10.10.254.224/32\"],\"current\":{\"status\":\"CONFIG_VERIFIED\",\"profiles\":[{\"port\":\"445\",\"status\":\"CONFIG_VERIFIED\"},{\"port\":\"1445\",\"status\":\"CONFIG_VERIFIED\"}]},\"applyscope\":\"NEW_CONNECTIONS\"}}").getAsJsonObject();
        StorageServiceConfigurationPreflightResponse response=new StorageServiceConfigurationPreflightResponse(report);
        response.setResponseName("preflightstorageserviceconfigurationresponse");
        String wire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject row=JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight");
        assertTrue(row.has("clientaccesspreview"));assertEquals("CONFIG_VERIFIED",row.getAsJsonObject("clientaccesspreview").getAsJsonObject("current").get("status").getAsString());
        String target=System.getProperty("smb.client.preflight.wire");
        if(target!=null)Files.writeString(Path.of(target),wire+"\n");
    }
}
