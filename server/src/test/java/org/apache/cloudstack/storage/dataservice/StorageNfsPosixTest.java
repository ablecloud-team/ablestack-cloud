// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageNfsExportResponse;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StorageNfsPosixTest {
    private final StorageServiceManagerImpl manager = new StorageServiceManagerImpl();
    @Test public void creationDefaultsRespectAllThreePoliciesAndReadOnly() {
        for (boolean ro : new boolean[]{false,true}) {
            for (String policy : new String[]{"ROOT","NONE","ALL"}) {
                JsonObject c=new JsonObject();c.addProperty("readOnly",ro);c.addProperty("rootSquash",policy.equals("ROOT"));
                c.addProperty("allSquash",policy.equals("ALL"));
                manager.applyNfsWritableRootSquashDefaults(c,true);
                boolean mapped=!ro && !policy.equals("NONE");
                assertEquals(mapped?65534:0,c.get("ownerUid").getAsInt());
                assertEquals(mapped?"0775":"0755",c.get("mode").getAsString());
                assertFalse(c.get("recursivePermission").getAsBoolean());
            }
        }
    }
    @Test public void omittedEditPosixNeverCreatesAnOwner() {
        String raw=manager.buildNfsConfigJson("{\"rootSquash\":true}", true,null,null,null,null,
                null,null,null,null,null,null,null,null,null,"V4_ONLY",false);
        JsonObject c=JsonParser.parseString(raw).getAsJsonObject();
        assertTrue(c.get("readOnly").getAsBoolean());
        for(String key:new String[]{"ownerUid","ownerGid","mode","recursivePermission"}) assertFalse(c.has(key));
    }
    @Test public void explicitZeroAndExistingUnchangedFieldsArePreserved() {
        String raw=manager.buildNfsConfigJson("{\"ownerUid\":1234,\"ownerGid\":2345,\"mode\":\"0750\"}", null,false,null,null,null,
                0,null,null,null,null,null,null,null,null,null,false);
        JsonObject c=JsonParser.parseString(raw).getAsJsonObject();
        assertEquals(0,c.get("ownerUid").getAsInt());assertEquals(2345,c.get("ownerGid").getAsInt());
        assertEquals("0750",c.get("mode").getAsString());
    }
    @Test public void initialImportedPreservationRemainsAboveRecommendations() {
        JsonObject c=JsonParser.parseString("{\"rootSquash\":true,\"posixPolicy\":\"PRESERVE_EXISTING_INITIAL\"}").getAsJsonObject();
        manager.applyNfsWritableRootSquashDefaults(c,true);
        assertTrue(c.has("anonUid"));assertFalse(c.has("ownerUid"));assertFalse(c.has("mode"));
    }
    @Test public void aclInheritanceRemovesOnlyMappingOverrides() {
        JsonObject c=JsonParser.parseString(manager.inheritedNfsPolicy("{\"rootSquash\":false,\"allSquash\":true,\"anonUid\":0,\"anonGid\":2345,\"sync\":true,\"secure\":false}")).getAsJsonObject();
        assertEquals(2,c.size());assertTrue(c.get("sync").getAsBoolean());assertFalse(c.get("secure").getAsBoolean());
    }
    @Test public void newAclHasNoSyntheticMappingOverrides() {
        JsonObject c=JsonParser.parseString(manager.buildNfsConfigJson(null,null,null,null,null,null,
                null,null,null,null,true,false,null,null,null,null,false)).getAsJsonObject();
        assertFalse(c.has("rootSquash"));assertFalse(c.has("allSquash"));assertFalse(c.has("ownerUid"));
    }
    @Test public void intentContainsOnlyExplicitPosixAndKeepsZero() {
        JsonObject c=StorageNfsPosix.fields(Map.of("owneruid",0,"anonuid",2345,"mode","0770","recursivepermission",false));
        assertEquals(2,c.size());assertEquals(0,c.get("ownerUid").getAsInt());assertFalse(c.has("recursivePermission"));
    }
    @Test public void publicPreflightIntentAcceptsBoundedPermissionChanges() {
        JsonObject c=StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageNfsExport\",\"resourceid\":\"uuid\",\"posixchanges\":{\"ownerUid\":0,\"mode\":\"0755\"}}");
        assertEquals(0,c.getAsJsonObject("posixchanges").get("ownerUid").getAsInt());
    }
    @Test public void realResponseSerializerKeepsObservedZeroAndPreviewContract() throws Exception {
        JsonObject observed=JsonParser.parseString("{\"status\":\"OBSERVED\",\"owneruid\":0,\"ownergid\":2345,\"mode\":\"0750\",\"fingerprint\":\"observed-root\"}").getAsJsonObject();
        StorageNfsExportResponse row=new StorageNfsExportResponse();row.setId("export");row.setName("fixture");row.setPath("/export/fixture");
        row.setConfig("{\"rootSquash\":false}");row.setPosixObservation(observed);row.setObjectName("storagenfsexport");
        ListResponse<StorageNfsExportResponse> list=new ListResponse<>();list.setResponses(java.util.List.of(row));list.setResponseName("liststoragenfsexportsresponse");
        String json=com.cloud.api.response.ApiResponseSerializer.toSerializedString(list,"json");
        JsonObject wire=JsonParser.parseString(json).getAsJsonObject();
        assertEquals(0,wire.getAsJsonObject("liststoragenfsexportsresponse").getAsJsonArray("storagenfsexport").get(0).getAsJsonObject().getAsJsonObject("posixobservation").get("owneruid").getAsInt());
        JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"revision\":13,\"observedat\":1793000000,\"expiresat\":1793000030,\"fingerprint\":\"preview\",\"checks\":[],\"impact\":{\"ackrequired\":true}}").getAsJsonObject();
        report.add("posixobservation",observed);report.add("posixchanges",StorageNfsPosix.fields(Map.of("owneruid",1666)));
        StorageServiceConfigurationPreflightResponse response=new StorageServiceConfigurationPreflightResponse(report);
        response.setResponseName("preflightstorageserviceconfigurationresponse");
        JsonObject preflight=JsonParser.parseString(com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json")).getAsJsonObject();
        wire.add("preflightstorageserviceconfigurationresponse",preflight.get("preflightstorageserviceconfigurationresponse"));
        assertTrue(wire.toString().contains("posixchanges"));
        String path=System.getProperty("storage.nfs.fixture");
        if(path!=null) Files.writeString(Path.of(path),wire.toString()+"\n");
    }
    @Test public void deferredPayloadContainsOnlyOneUnpublishedTargetWithInspectionReceipt() {
        StorageServiceInstanceVO instance = new StorageServiceInstanceVO();
        StorageFileShareVO share = new StorageFileShareVO();
        share.setPath("/export/deferred");
        share.setConfigJson("{\"ownerUid\":65534,\"mode\":\"0775\",\"lastInspection\":{\"directoryCreated\":true,\"configurationOperationId\":\"original-create\"}}");
        JsonObject payload=StorageNfsPosix.deferredPayload(instance,share);
        assertTrue(payload.get("posixOnly").getAsBoolean());
        assertEquals(1,payload.getAsJsonArray("exports").size());
        JsonObject row=payload.getAsJsonArray("exports").get(0).getAsJsonObject();
        assertEquals(share.getUuid(),row.get("uuid").getAsString());
        assertEquals("Allocated",row.get("state").getAsString());
        assertFalse(payload.has("listeners"));assertFalse(row.has("acls"));
        assertEquals("original-create",row.getAsJsonObject("config").getAsJsonObject("lastInspection").get("configurationOperationId").getAsString());
    }
    @Test(expected=com.cloud.utils.exception.CloudRuntimeException.class)
    public void deferredCreationRejectsAnOldGuestBeforeCandidatePreparation() {
        org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd cmd =
            org.mockito.Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd.class);
        org.mockito.Mockito.when(cmd.getDeferApply()).thenReturn(true);
        StorageNfsPosix.requireDeferredCapability(cmd,new JsonObject());
    }
    @Test public void ordinaryCreationNeedsNoNewCapabilityAndDeferredV1IsAccepted() {
        org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd cmd =
            org.mockito.Mockito.mock(org.apache.cloudstack.api.command.user.storage.dataservice.CreateStorageNfsExportCmd.class);
        StorageNfsPosix.requireDeferredCapability(cmd,new JsonObject());
        org.mockito.Mockito.when(cmd.getDeferApply()).thenReturn(true);
        JsonObject caps=new JsonObject();caps.addProperty("nfsDeferredPosixVersion",1);
        StorageNfsPosix.requireDeferredCapability(cmd,caps);
    }

    @Test public void realSerializerKeepsLocalProbeIncompleteSeparateFromPolicyAndReadyState() throws Exception {
        JsonObject probe=JsonParser.parseString("{\"externalclientrequired\":true,\"ports\":[{\"port\":2049,\"status\":\"EXTERNAL_CLIENT_REQUIRED\",\"reason\":\"LOCAL_ADDRESS_EXCLUDED_BY_ACL\"}]}").getAsJsonObject();
        StorageNfsExportResponse row=new StorageNfsExportResponse();row.setId("share");row.setName("nested");
        row.setState("Ready");row.setConfig("{\"rootSquash\":true}");row.setLocalProbe(probe);row.setObjectName("storagenfsexport");
        ListResponse<StorageNfsExportResponse> list=new ListResponse<>();list.setResponses(java.util.List.of(row));list.setResponseName("liststoragenfsexportsresponse");
        String json=com.cloud.api.response.ApiResponseSerializer.toSerializedString(list,"json");
        JsonObject wire=JsonParser.parseString(json).getAsJsonObject();
        JsonObject actual=wire.getAsJsonObject("liststoragenfsexportsresponse").getAsJsonArray("storagenfsexport").get(0).getAsJsonObject();
        assertTrue(actual.get("config").isJsonPrimitive());assertEquals("Ready",actual.get("state").getAsString());
        assertTrue(actual.getAsJsonObject("localprobe").get("externalclientrequired").getAsBoolean());
        assertEquals(2049,actual.getAsJsonObject("localprobe").getAsJsonArray("ports").get(0).getAsJsonObject().get("port").getAsInt());
        String fixture=System.getProperty("storage.nfs.localprobe.fixture");if(fixture!=null)Files.writeString(Path.of(fixture),json+"\n");
    }

}
