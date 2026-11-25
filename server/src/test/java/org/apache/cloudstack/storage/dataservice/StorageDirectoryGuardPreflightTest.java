// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
import org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/** Real proposal, RPC rejection, preflight classification and API serialization share one path. */
public class StorageDirectoryGuardPreflightTest {
    private JsonObject wire(String failure) throws Exception {
        StorageServiceInstanceVO instance=mock(StorageServiceInstanceVO.class);
        when(instance.getId()).thenReturn(91L);when(instance.getVmId()).thenReturn(195L);
        when(instance.getDataCenterId()).thenReturn(1L);when(instance.getUuid()).thenReturn("instance");
        when(instance.getConfigurationRevision()).thenReturn(47L);
        StorageDirectoryPolicyDao policies=mock(StorageDirectoryPolicyDao.class);
        StorageFileShareDao shares=mock(StorageFileShareDao.class);
        StorageAccessRuleDao rules=mock(StorageAccessRuleDao.class);
        VolumeDao volumes=mock(VolumeDao.class);
        StorageServiceGuestCommandDispatcher guest=mock(StorageServiceGuestCommandDispatcher.class);
        StorageDirectoryPolicyVO policy=mock(StorageDirectoryPolicyVO.class);
        when(policy.getId()).thenReturn(9L);when(policy.getUuid()).thenReturn("policy");
        when(policy.getInstanceId()).thenReturn(91L);when(policy.getVolumeId()).thenReturn(355L);
        when(policy.getRevision()).thenReturn(1L);when(policy.getCanonicalPath()).thenReturn("epic1456/projects/team-a");
        when(policy.getOwnerUid()).thenReturn(7001L);when(policy.getOwnerGid()).thenReturn(7002L);
        when(policy.getMode()).thenReturn("0770");when(policy.getMetadataJson()).thenReturn("{\"filesystemuuid\":\"fs\",\"inode\":42}");
        when(policies.findByUuid("policy")).thenReturn(policy);
        VolumeVO volume=mock(VolumeVO.class);
        when(volume.getId()).thenReturn(355L);when(volume.getUuid()).thenReturn("volume");
        when(volume.getInstanceId()).thenReturn(195L);when(volume.getDataCenterId()).thenReturn(1L);
        when(volume.getState()).thenReturn(Volume.State.Ready);when(volumes.findById(355L)).thenReturn(volume);
        StorageFileShareVO row=new StorageFileShareVO(91,StorageServiceInstance.Protocol.SMB,"fixed","/export/fixed",355L,"xfs",null,StorageServiceInstance.ResourceState.Ready,
            "{\"fileIdentity\":{\"version\":1,\"mode\":\"FIXED\",\"uid\":7001,\"gid\":7002}}");
        when(shares.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.SMB)).thenReturn(List.of(row));
        when(shares.listByInstanceIdAndProtocol(91L,StorageServiceInstance.Protocol.NFS)).thenReturn(Collections.emptyList());
        JsonObject observed=JsonParser.parseString("{\"status\":\"OBSERVED\",\"volumeuuid\":\"volume\",\"canonicalpath\":\"epic1456/projects/team-a\",\"filesystemuuid\":\"fs\",\"inode\":42,\"owneruid\":7001,\"ownergid\":7002,\"mode\":\"0770\",\"fingerprint\":\"observed\",\"references\":[],\"units\":[]}").getAsJsonObject();
        JsonArray directories=new JsonArray();directories.add(observed);
        JsonObject inspect=new JsonObject();inspect.add("directories",directories);
        List<String> operations=new ArrayList<>();
        when(guest.dispatch(any())).thenAnswer(call-> {
            StorageServiceGuestCommand command=call.getArgument(0);operations.add(command.getOperation());
            if(command.getOperation().equals("directory policy inspect"))
                return new StorageServiceGuestCommandResult(true,"observed",inspect.toString());
            assertEquals("directory policy preview",command.getOperation());
            JsonObject proposal=JsonParser.parseString(command.getPayload()).getAsJsonObject();
            assertEquals("2770",proposal.getAsJsonObject("policy").get("mode").getAsString());
            assertEquals(1,proposal.getAsJsonArray("smbShares").size());
            if(failure.equals("TRANSPORT"))throw new CloudRuntimeException("QGA disconnected");
            if(failure.equals("NULL_RESULT"))return null;
            return new StorageServiceGuestCommandResult(false,"rejected",failure.equals("NO_CODE")?"{}":"{\"code\":\""+failure+"\"}");
        });
        StorageDirectoryPolicies directory=new StorageDirectoryPolicies(policies,shares,rules,volumes,guest,v->{});
        StorageServiceConfigurationPreflight gate=new StorageServiceConfigurationPreflight(
            mock(StorageServiceProtocolDao.class),shares,mock(StorageBlockTargetDao.class),rules,volumes,mock(StorageServiceRuntimeUpgradeDao.class),guest,v->{});
        gate.directoryObserver=directory::preflight;
        JsonObject intent=StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageDirectoryPolicy\",\"resourceid\":\"policy\",\"expectedpolicyrevision\":1,\"posixchanges\":{\"mode\":\"2770\"}}");
        JsonObject report=gate.observe(instance,intent,null);
        assertEquals(List.of("directory policy inspect","directory policy preview"),operations);
        assertTrue(report.getAsJsonArray("checks").get(0).getAsJsonObject().get("blocking").getAsBoolean());
        try {StorageServiceConfigurationPreflight.requireReady(report,"anything");fail("rejection acknowledged");}
        catch(StorageServiceConfigurationPreflight.Rejected expected) { }
        for(Object dao:List.of(policies,shares,rules,volumes))
            assertTrue(mockingDetails(dao).getInvocations().stream().noneMatch(i->i.getMethod().getName().matches("persist|update|remove|expunge")));
        StorageServiceConfigurationPreflightResponse response=new StorageServiceConfigurationPreflightResponse(report);
        response.setResponseName("preflightstorageserviceconfigurationresponse");
        return JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json")).getAsJsonObject();
    }
    @Test public void knownGuardErrorsStayBlockedThroughActualRpcAndSerializer() throws Exception {
        JsonObject fixtures=new JsonObject();
        for(String code:List.of("SMB_PARENT_IDENTITY_CONFLICT","SMB_FIXED_IDENTITY_PERMISSION_DENIED","DIRECTORY_ACL_MASK_CONFLICT")) {
            JsonObject json=wire(code),report=json.getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight");
            assertEquals("BLOCKED",report.get("status").getAsString());
            assertEquals(code,report.getAsJsonArray("checks").get(0).getAsJsonObject().get("code").getAsString());
            assertEquals("",report.get("fingerprint").getAsString());fixtures.add(code,json);
        }
        for(String error:List.of("TRANSPORT","NULL_RESULT","NO_CODE","SMB_UNRECOGNIZED_GUARD")) {
            JsonObject json=wire(error),report=json.getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight");
            assertEquals("UNKNOWN",report.get("status").getAsString());
            assertEquals("DIRECTORY_OBSERVATION_UNAVAILABLE",report.getAsJsonArray("checks").get(0).getAsJsonObject().get("code").getAsString());
            fixtures.add(error,json);
        }
        String target=System.getProperty("directory.guard.fixture");
        if(target!=null)Files.writeString(Path.of(target),new GsonBuilder().setPrettyPrinting().create().toJson(fixtures)+"\n");
    }
}
