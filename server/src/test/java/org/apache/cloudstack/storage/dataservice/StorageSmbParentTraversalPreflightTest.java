// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StorageSmbParentTraversalPreflightTest {
    private JsonObject exercise(String code) throws Exception { return exercise(code, true); }
    private JsonObject exercise(String code, boolean runtimeCapability) throws Exception {
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(1L);when(instance.getVmId()).thenReturn(2L);
        when(instance.getUuid()).thenReturn("11111111-2222-4333-8444-555555555555");
        when(instance.getConfigurationRevision()).thenReturn(4L);
        when(instance.getActiveConfigurationOperationId()).thenReturn(null);
        StorageFileShareDao shares=mock(StorageFileShareDao.class);
        StorageAccessRuleDao rules=mock(StorageAccessRuleDao.class);
        VolumeDao volumes=mock(VolumeDao.class);
        StorageServiceProtocolDao protocols=mock(StorageServiceProtocolDao.class);
        StorageServiceGuestCommandDispatcher guest=mock(StorageServiceGuestCommandDispatcher.class);
        String volumeUuid="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        JsonObject config=JsonParser.parseString("{\"pathBindingVersion\":1,\"relativeSharePath\":\"export/share\",\"filesystemUuid\":\"fs\",\"pathIdentity\":{\"filesystemUuid\":\"fs\",\"inode\":42,\"volumeRootInode\":128}}").getAsJsonObject();
        StorageFileShareVO share=new StorageFileShareVO(1L,StorageServiceInstance.Protocol.SMB,"share","/export/share",3L,"xfs",null,StorageServiceInstance.ResourceState.Ready,config.toString());
        when(shares.findByUuid(share.getUuid())).thenReturn(share);
        when(shares.listByInstanceIdAndProtocol(1L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(share));
        VolumeVO volume=mock(VolumeVO.class);when(volume.getId()).thenReturn(3L);when(volume.getUuid()).thenReturn(volumeUuid);
        when(volumes.findById(3L)).thenReturn(volume);
        when(guest.dispatch(any())).thenAnswer(call -> {
            StorageServiceGuestCommand command=call.getArgument(0);
            if (command.getOperation().equals("configuration capability show"))
                return new StorageServiceGuestCommandResult(true,"", "{\"configurationPreflightVersion\":1,\"nestedFileSharePathVersion\":1,\"nestedFileSharePathProtectionVersion\":1,\"smbRuntimeBindingTraversalVersion\":"+(runtimeCapability?1:0)+"}");
            assertEquals("configuration preflight show",command.getOperation());
            JsonObject query=JsonParser.parseString(command.getPayload()).getAsJsonObject();
            JsonObject target=query.getAsJsonObject("smbparenttarget");
            assertNotNull(target);
            assertEquals(share.getUuid(),target.get("uuid").getAsString());
            JsonObject c=target.getAsJsonObject("config");
            assertEquals(1,c.get("pathBindingVersion").getAsInt());
            assertEquals("export/share",c.get("relativeSharePath").getAsString());
            assertEquals(config.get("pathIdentity"),c.get("pathIdentity"));
            assertEquals("/srv/ablestack-storage/volumes/"+volumeUuid+"/export/share",c.get("backingPath").getAsString());
            assertEquals("newlocal",target.getAsJsonArray("acls").get(0).getAsJsonObject().get("principal").getAsString());
            JsonObject report=new JsonObject();
            report.addProperty("status",code.endsWith("DENIED")?"BLOCKED":"READY");
            report.addProperty("revision",4);
            report.addProperty("observedat",System.currentTimeMillis()/1000.0);
            report.addProperty("expiresat",System.currentTimeMillis()/1000.0+30);
            report.addProperty("fingerprint","proof");
            JsonObject check=new JsonObject();check.addProperty("code",code);
            check.addProperty("blocking",code.endsWith("DENIED"));
            check.addProperty("path","/srv/ablestack-storage/volumes/"+volumeUuid+"/export");
            JsonArray checks=new JsonArray();checks.add(check);report.add("checks",checks);
            return new StorageServiceGuestCommandResult(true,"",report.toString());
        });
        StorageServiceConfigurationPreflight gate=new StorageServiceConfigurationPreflight(protocols,shares,
            mock(StorageBlockTargetDao.class),rules,volumes,mock(StorageServiceRuntimeUpgradeDao.class),guest,v->{});
        JsonObject intent=new JsonObject();intent.addProperty("api","createStorageSmbAcl");
        intent.addProperty("shareid",share.getUuid());intent.addProperty("smbprincipal","newlocal");
        intent.addProperty("smbprincipaltype","LOCAL_USER");
        JsonObject report=gate.observe(instance,intent,null);
        assertEquals(!runtimeCapability?"UNKNOWN":code.endsWith("DENIED")?"BLOCKED":code.endsWith("NOT_APPLICABLE")?"UNKNOWN":"READY",report.get("status").getAsString());
        verify(guest,times(runtimeCapability?2:1)).dispatch(any());
        StorageServiceConfigurationPreflightResponse wire=new StorageServiceConfigurationPreflightResponse(report);
        wire.setResponseName("preflightstorageserviceconfigurationresponse");
        return JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(wire,"json")).getAsJsonObject();
    }
    @Test public void protectedPathAndProspectivePrincipalReachTheActualProbeAndSerializer() throws Exception {
        JsonObject fixture=exercise("SMB_PARENT_TRAVERSAL_DENIED");
        JsonObject report=fixture.getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight");
        assertEquals("SMB_PARENT_TRAVERSAL_DENIED",report.getAsJsonArray("checks").get(0).getAsJsonObject().get("code").getAsString());
        String file=System.getProperty("parent.traversal.fixture");
        if(file!=null)Files.writeString(Path.of(file),new GsonBuilder().setPrettyPrinting().create().toJson(fixture)+"\n");
    }
    @Test public void actualRuntimeTraversalBlockReachesApiAndUiFixture() throws Exception {
        JsonObject fixture=exercise("SMB_RUNTIME_BINDING_TRAVERSAL_DENIED");
        String file=System.getProperty("runtime.traversal.fixture");
        if(file!=null)Files.writeString(Path.of(file),new GsonBuilder().setPrettyPrinting().create().toJson(fixture)+"\n");
        JsonObject unavailable=exercise("SMB_PARENT_TRAVERSAL_OK",false);
        assertEquals("SMB_RUNTIME_BINDING_CAPABILITY_REQUIRED",unavailable.getAsJsonObject("preflightstorageserviceconfigurationresponse")
            .getAsJsonObject("configurationpreflight").getAsJsonArray("checks").get(0).getAsJsonObject().get("code").getAsString());
    }
    @Test public void protectedPathCannotBeApprovedByAnOlderProbeThatSkipsIt() throws Exception {
        exercise("SMB_PARENT_TRAVERSAL_NOT_APPLICABLE");
        exercise("SMB_PARENT_TRAVERSAL_OK");
    }
}
