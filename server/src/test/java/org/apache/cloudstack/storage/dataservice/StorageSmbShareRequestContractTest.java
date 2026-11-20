// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.cloud.api.dispatch.DispatchTask;
import com.cloud.api.dispatch.ParamProcessWorker;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.BaseStorageServiceConfigurationCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageSmbShareCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mounted component requests pass the real API marshaller and both production guest-query paths. */
public class StorageSmbShareRequestContractTest {
    private static final String INSTANCE="c1eca707-a554-4c42-85cd-1e9e9744d804";
    private static final String VOLUME="e070a0aa-dbf5-48c0-83e0-09f874446277";
    private static final String POLICY="77262649-396b-35ca-85b2-ac72188c020d";
    private static final String SHARE="0fc08aa0-61e2-4a35-a23f-94eb4afcf7de";
    private static class InputWorker extends ParamProcessWorker {
        @Override protected void doAccessChecks(BaseCmd cmd,Map<Object,AccessType> entities) { }
    }
    @Test public void serializedComponentPreviewAndSubmitProduceIdenticalGuestIntents() throws Exception {
        String fixture=System.getProperty("smb.contract.requests","../ui/tests/fixtures/storage-1456-submit-contract.json");
        JsonArray cases=JsonParser.parseString(Files.readString(Path.of(fixture))).getAsJsonArray();
        assertEquals(4,cases.size());
        JsonArray proof=new JsonArray();
        CallContext.register(mock(User.class),mock(Account.class));
        try {
            for(JsonElement item:cases) {
                JsonObject value=item.getAsJsonObject(), ui=value.getAsJsonObject("intent"), request=value.getAsJsonObject("request");
                StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
                when(instance.getId()).thenReturn(91L);when(instance.getVmId()).thenReturn(195L);
                when(instance.getUuid()).thenReturn(INSTANCE);when(instance.getConfigurationRevision()).thenReturn(42L);
                when(instance.getDataCenterId()).thenReturn(1L);
                VolumeVO volume=mock(VolumeVO.class);
                when(volume.getId()).thenReturn(355L);when(volume.getUuid()).thenReturn(VOLUME);
                when(volume.getState()).thenReturn(Volume.State.Ready);when(volume.getDataCenterId()).thenReturn(1L);
                when(volume.getInstanceId()).thenReturn(195L);
                StorageDirectoryPolicyVO policy=mock(StorageDirectoryPolicyVO.class);
                when(policy.getId()).thenReturn(9L);when(policy.getUuid()).thenReturn(POLICY);
                String config="{\"relativeSharePath\":\"epic1456/projects/team-a\",\"volumeMountPath\":\"/srv/ablestack-storage/volumes/"+VOLUME+"\",\"backingPath\":\"/srv/ablestack-storage/volumes/"+VOLUME+"/epic1456/projects/team-a\",\"pathBindingVersion\":1,\"directoryPolicyUuid\":\""+POLICY+"\"}";
                StorageFileShareVO row=new StorageFileShareVO(91,StorageServiceInstance.Protocol.SMB,"contract1456","/export/contract1456",355L,"xfs",null,StorageServiceInstance.ResourceState.Ready,config);
                ReflectionTestUtils.setField(row,"id",77L);ReflectionTestUtils.setField(row,"uuid",SHARE);
                EntityManager entities=mock(EntityManager.class);
                when(entities.findByUuidIncludingRemoved(any(),anyString())).thenAnswer(a-> {
                    String id=a.getArgument(1);return INSTANCE.equals(id)?instance:VOLUME.equals(id)?volume:POLICY.equals(id)?policy:SHARE.equals(id)?row:null;
                });
                InputWorker worker=new InputWorker();ReflectionTestUtils.setField(worker,"_entityMgr",entities);
                Map<String,String> params=new HashMap<>();request.entrySet().forEach(e->params.put(e.getKey(),e.getValue().getAsString()));
                BaseStorageServiceConfigurationCmd cmd=value.get("api").getAsString().startsWith("create")?new CreateStorageSmbShareCmd():new UpdateStorageSmbShareCmd();
                worker.handle(new DispatchTask(cmd,params));
                JsonObject actual=StorageServiceConfigurationPreflight.commandIntent(cmd);
                JsonObject parsed=StorageServiceConfigurationPreflight.parseIntent(ui.toString());
                assertEquals(value.get("name").getAsString(),parsed.get("smbfileidentity"),actual.get("smbfileidentity"));
                assertEquals(parsed.get("smbinheritance"),canonicalPolicyRef(actual.getAsJsonObject("smbinheritance")));
                assertEquals(parsed.get("smbfullapply"),actual.get("smbfullapply"));

                StorageServiceProtocolDao protocols=mock(StorageServiceProtocolDao.class);
                StorageFileShareDao shares=mock(StorageFileShareDao.class);StorageBlockTargetDao blocks=mock(StorageBlockTargetDao.class);
                StorageAccessRuleDao acls=mock(StorageAccessRuleDao.class);VolumeDao volumes=mock(VolumeDao.class);
                StorageServiceRuntimeUpgradeDao upgrades=mock(StorageServiceRuntimeUpgradeDao.class);
                StorageServiceGuestCommandDispatcher guest=mock(StorageServiceGuestCommandDispatcher.class);
                when(volumes.findById(355L)).thenReturn(volume);when(volumes.findByUuid(VOLUME)).thenReturn(volume);
                when(shares.findById(77L)).thenReturn(row);when(shares.findByUuid(SHARE)).thenReturn(row);
                when(shares.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(row));
                when(shares.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.NFS)).thenReturn(Collections.emptyList());
                StorageServiceProtocolVO smb=mock(StorageServiceProtocolVO.class);
                when(smb.getProtocol()).thenReturn(StorageServiceInstance.Protocol.SMB);when(smb.getPort()).thenReturn(445);when(smb.isEnabled()).thenReturn(true);when(smb.getConfigJson()).thenReturn("{}");
                when(protocols.listByInstanceId(91L)).thenReturn(List.of(smb));when(protocols.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(smb));
                List<JsonObject> queries=new ArrayList<>();
                when(guest.dispatch(any())).thenAnswer(a-> {
                    StorageServiceGuestCommand command=a.getArgument(0);
                    JsonObject query=JsonParser.parseString(command.getPayload()).getAsJsonObject();queries.add(query);
                    return new StorageServiceGuestCommandResult(true,"captured","{\"status\":\"READY\",\"checks\":[],\"fingerprint\":\"captured\",\"impact\":{\"ackrequired\":false}}");
                });
                StorageServiceConfigurationPreflight service=new StorageServiceConfigurationPreflight(protocols,shares,blocks,acls,volumes,upgrades,guest,v->{});
                StorageDirectoryPolicies policyEngine=mock(StorageDirectoryPolicies.class);
                String saved;
                try(var in=getClass().getResourceAsStream("/storage/smb-1456-inheritance-context.json")) {
                    saved=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                }
                JsonObject inherited=JsonParser.parseString(saved).getAsJsonObject();
                when(policyEngine.inheritanceTarget(any(),any(),any())).thenAnswer(a->inherited.deepCopy());
                service.smbInheritanceObserver=(i,selected,intent)->StorageSmbParentInheritance.query(i,selected,intent,policyEngine);
                service.observe(instance,parsed,1L);service.observe(instance,actual,1L);
                assertEquals(value.get("name").getAsString(),queries.get(0),queries.get(1));
                JsonObject pair=new JsonObject();pair.add("name",value.get("name"));pair.add("ui",queries.get(0));pair.add("api",queries.get(1));proof.add(pair);
            }
        } finally {CallContext.unregister();}
        String output=System.getProperty("smb.contract.queries");
        if(output!=null)Files.writeString(Path.of(output),new GsonBuilder().setPrettyPrinting().create().toJson(proof)+"\n");
    }
    private JsonObject canonicalPolicyRef(JsonObject input) {
        if(input==null)return null;
        JsonObject result=input.deepCopy();if(result.has("directoryPolicyId"))result.addProperty("directoryPolicyId",POLICY);return result;
    }
}
