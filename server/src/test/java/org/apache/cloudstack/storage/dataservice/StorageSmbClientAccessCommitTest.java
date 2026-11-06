// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.*;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageSmbShareCmd;
import org.apache.cloudstack.storage.dataservice.dao.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StorageSmbClientAccessCommitTest {
    private static final Gson JSON=new Gson();
    private StorageServiceConfigurationPlan plan;
    private StorageServiceConfigurationOperationVO operation;
    private StorageFileShareVO original;
    private JsonObject saved;
    private void setup() throws Exception {
        JsonObject fixture;
        try(var in=getClass().getResourceAsStream("/storage-config/storage-1457-source-verified.json")) {
            assertNotNull(in);fixture=JsonParser.parseString(new String(in.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
        original=new StorageFileShareVO(1,StorageServiceInstance.Protocol.SMB,"source","/export/source",77L,"xfs",null,
                StorageServiceInstance.ResourceState.Ready,fixture.getAsJsonObject("beforeShare").get("config").toString());
        field(original,"id",101L);
        var peer=new StorageFileShareVO(1,StorageServiceInstance.Protocol.SMB,"peer","/export/peer",77L,"xfs",null,
                StorageServiceInstance.ResourceState.Ready,"{\"keep\":true}");field(peer,"id",102L);
        var shares=mock(StorageFileShareDao.class);
        when(shares.listByInstanceIdAndProtocol(1L,StorageServiceInstance.Protocol.SMB)).thenReturn(Arrays.asList(original,peer));
        plan=new StorageServiceConfigurationPlan(1,mock(StorageServiceProtocolDao.class),shares,
                mock(StorageBlockTargetDao.class),mock(StorageAccessRuleDao.class),value->{});
        operation=new StorageServiceConfigurationOperationVO();operation.setProtocol("SMB");operation.setAction("UPDATE");
        operation.setResourceType("UpdateStorageSmbShareCmd");operation.setResourceUuid(original.getUuid());
        operation.setBeforeSnapshot(plan.snapshot());
        var cmd=new UpdateStorageSmbShareCmd();field(cmd,"id",101L);field(cmd,"clientCidrs","[\"10.10.254.0/24\"]");
        saved=new JsonObject();saved.addProperty("onlineSmbClientAccess",true);saved.addProperty("onlineSmbOptions",false);
        saved.add("publicRequest",JSON.toJsonTree(StorageServiceConfigurationCoordinator.publicRequest(cmd)));
        var candidate=plan.dao(StorageFileShareDao.class).findById(101L);
        candidate.setConfigJson(fixture.getAsJsonObject("afterShare").get("config").toString());
        plan.dao(StorageFileShareDao.class).update(101L,candidate);render();
    }
    private static void field(Object value,String name,Object content)throws Exception {
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,content);
    }
    private void render() {
        JsonObject command=new JsonObject();JsonArray rows=new JsonArray();
        for(var row:plan.dao(StorageFileShareDao.class).listByInstanceIdAndProtocol(1L,StorageServiceInstance.Protocol.SMB)) {
            JsonObject value=new JsonObject();value.addProperty("uuid",row.getUuid());value.addProperty("state",row.getState().name());
            value.add("config",JsonParser.parseString(row.getConfigJson()));rows.add(value);
        }
        command.add("shares",rows);plan.commands.put("smb share apply",command);operation.setCandidateSnapshot(saved.toString());
    }
    @Test public void verifiedPolicyOnlyDeltaMayOmitPathReceipt()throws Exception {
        setup();StorageFileSharePaths.applyBindings(plan,operation,new JsonObject());
        assertEquals(JsonParser.parseString(original.getConfigJson()).getAsJsonObject().get("pathIdentity"),
                JsonParser.parseString(plan.dao(StorageFileShareDao.class).findById(101L).getConfigJson()).getAsJsonObject().get("pathIdentity"));
    }
    @Test public void sourceFlagCannotAuthorizePathIdentityVolumePeerOrMixedRequestChanges()throws Exception {
        for(String change:Arrays.asList("relativeSharePath","pathIdentity","volume","peer","mixed","differentPolicy","bothFlags")) {
            setup();var target=plan.dao(StorageFileShareDao.class).findById(101L);
            JsonObject config=JsonParser.parseString(target.getConfigJson()).getAsJsonObject();
            if(change.equals("relativeSharePath"))config.addProperty(change,"other/path");
            if(change.equals("pathIdentity"))config.getAsJsonObject(change).addProperty("inode",42);
            if(change.equals("volume"))target.setVolumeId(88L);
            if(change.equals("peer")) {var peer=plan.dao(StorageFileShareDao.class).findById(102L);peer.setConfigJson("{\"keep\":false}");plan.dao(StorageFileShareDao.class).update(102L,peer);}
            if(change.equals("mixed"))saved.getAsJsonObject("publicRequest").addProperty("relativepath","other/path");
            if(change.equals("differentPolicy"))saved.getAsJsonObject("publicRequest").addProperty("clientcidrs","[\"10.20.0.0/16\"]");
            if(change.equals("bothFlags"))saved.addProperty("onlineSmbOptions",true);
            target.setConfigJson(config.toString());plan.dao(StorageFileShareDao.class).update(101L,target);render();
            try {StorageFileSharePaths.applyBindings(plan,operation,new JsonObject());fail(change+" bypassed path protection");}
            catch(InvalidParameterValueException expected) {assertTrue(change,expected.getMessage().startsWith("SMB_CLIENT_ACCESS_"));}
        }
    }
    @Test public void unvalidatedFlagAndSuppliedWrongReceiptKeepExistingProtection()throws Exception {
        setup();saved.addProperty("onlineSmbClientAccess",false);render();
        try {StorageFileSharePaths.applyBindings(plan,operation,new JsonObject());fail("missing receipt accepted");}
        catch(InvalidParameterValueException expected) {assertEquals("DIRECTORY_BINDING_RECEIPT_MISSING",expected.getMessage());}
        setup();JsonObject observation=new JsonObject(),receipt=new JsonObject();receipt.addProperty("operationid","wrong");
        observation.add("pathbindings",receipt);
        try {StorageFileSharePaths.applyBindings(plan,operation,observation);fail("wrong receipt accepted");}
        catch(InvalidParameterValueException expected) {assertEquals("DIRECTORY_BINDING_RECEIPT_MISMATCH",expected.getMessage());}
    }
}
