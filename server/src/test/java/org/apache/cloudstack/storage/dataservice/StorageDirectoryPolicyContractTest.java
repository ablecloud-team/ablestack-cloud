// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
package org.apache.cloudstack.storage.dataservice;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.command.user.storage.dataservice.DeleteStorageDirectoryPolicyCmd;
import org.apache.cloudstack.api.command.user.storage.dataservice.UpdateStorageDirectoryPolicyCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageDirectoryPolicyResponse;
import org.apache.cloudstack.api.response.StorageServiceConfigurationPreflightResponse;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StorageDirectoryPolicyContractTest {
    private static void set(Object target,String name,Object value)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    @Test public void actualCommandAndUiPreflightCarryOnlyChangedPosix()throws Exception {
        UpdateStorageDirectoryPolicyCmd cmd=new UpdateStorageDirectoryPolicyCmd();
        set(cmd,"id",9L);set(cmd,"instanceId",1L);set(cmd,"expectedPolicyRevision",3L);set(cmd,"mode","0775");
        JsonObject intent=StorageServiceConfigurationPreflight.commandIntent(cmd);
        assertEquals("updateStorageDirectoryPolicy",intent.get("api").getAsString());
        assertEquals(3,intent.get("expectedpolicyrevision").getAsInt());
        assertEquals(1,intent.getAsJsonObject("posixchanges").size());
        assertEquals("0775",intent.getAsJsonObject("posixchanges").get("mode").getAsString());
        JsonObject ui=StorageServiceConfigurationPreflight.parseIntent("{\"api\":\"updateStorageDirectoryPolicy\",\"resourceid\":\"policy\",\"expectedpolicyrevision\":3,\"posixchanges\":{\"mode\":\"0775\"}}");
        assertEquals(intent.get("posixchanges"),ui.get("posixchanges"));
        assertEquals("DirectoryPosixPolicy",StorageServiceConfigurationCoordinator.resourceType(cmd));
    }
    @Test public void actualSerializerPreservesDirectoryListAndPublicPreflightPreview()throws Exception {
        JsonObject row=new JsonObject();row.addProperty("status","OBSERVED");row.addProperty("owneruid",1001);row.addProperty("ownergid",1001);row.addProperty("mode","0770");
        JsonArray refs=new JsonArray();JsonObject ref=new JsonObject();ref.addProperty("uuid","nfs");ref.addProperty("name","team-nfs");ref.addProperty("protocol","NFS");refs.add(ref);
        StorageDirectoryPolicyResponse value=new StorageDirectoryPolicyResponse();
        value.setId("policy");value.setInstanceid("instance");value.setVolumeid("volume");value.setPath("/export/team");
        value.setOwneruid(1001L);value.setOwnergid(1001L);value.setMode("0770");value.setPolicyrevision(3L);
        value.setObservationstatus("APPLIED");value.setObservation(row);value.setReferences(refs);
        ListResponse<StorageDirectoryPolicyResponse> list=new ListResponse<>();list.setResponses(java.util.Collections.singletonList(value),1);
        list.setResponseName("liststoragedirectorypoliciesresponse");
        String listWire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(list,"json");
        JsonObject listObject=JsonParser.parseString(listWire).getAsJsonObject();
        assertEquals("policy",listObject.getAsJsonObject("liststoragedirectorypoliciesresponse").getAsJsonArray("directorypolicy").get(0).getAsJsonObject().get("id").getAsString());
        JsonObject report=new JsonObject();report.addProperty("status","READY");report.addProperty("revision",12);
        report.addProperty("observedat",3500000000L);report.addProperty("expiresat",3500000030L);report.addProperty("fingerprint","confirmed");
        report.add("checks",new JsonArray());JsonObject impact=new JsonObject();impact.addProperty("ackrequired",true);report.add("impact",impact);
        JsonObject preview=new JsonObject();preview.addProperty("entrycount",1);preview.add("references",refs);
        preview.add("before",row);JsonObject after=row.deepCopy();after.addProperty("mode","0775");preview.add("after",after);preview.add("units",new JsonArray());report.add("directorypreview",preview);
        StorageServiceConfigurationPreflightResponse response=new StorageServiceConfigurationPreflightResponse(report);
        response.setResponseName("preflightstorageserviceconfigurationresponse");
        String preflightWire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject preflight=JsonParser.parseString(preflightWire).getAsJsonObject();
        assertEquals("0775",preflight.getAsJsonObject("preflightstorageserviceconfigurationresponse").getAsJsonObject("configurationpreflight")
                .getAsJsonObject("directorypreview").getAsJsonObject("after").get("mode").getAsString());
        String target=System.getProperty("directory.wire.fixture");
        if(target!=null) {
            JsonObject fixture=new JsonObject();fixture.add("list",listObject);fixture.add("preflight",preflight);
            Files.writeString(Path.of(target),new GsonBuilder().setPrettyPrinting().create().toJson(fixture)+"\n");
        }
    }

    @Test public void expectedNativeConflictIsStructuredPreflightWithoutAnInternalError() {
        StorageServiceConfigurationPreflight gate=new StorageServiceConfigurationPreflight(
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceProtocolDao.class),
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class),
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageBlockTargetDao.class),
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class),
            org.mockito.Mockito.mock(com.cloud.storage.dao.VolumeDao.class),
            org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageServiceRuntimeUpgradeDao.class),
            org.mockito.Mockito.mock(StorageServiceGuestCommandDispatcher.class),volume->{});
        gate.directoryObserver=(instance,intent,owner)->{throw new com.cloud.utils.exception.CloudRuntimeException("DIRECTORY_ACL_MASK_CONFLICT");};
        StorageServiceInstanceVO instance=new StorageServiceInstanceVO("test",null,1,2,13,1L,"SYSTEM_VM");
        JsonObject input=new JsonObject();input.addProperty("api","updateStorageDirectoryPolicy");
        JsonObject report=gate.observe(instance,input,null);
        assertEquals("BLOCKED",report.get("status").getAsString());
        assertEquals("DIRECTORY_ACL_MASK_CONFLICT",report.getAsJsonArray("checks").get(0).getAsJsonObject().get("code").getAsString());
    }

    @Test public void differentClientAliasBindsSamePhysicalDirectoryWithoutRelaxingLegacyPathRules() throws Exception {
        var policies=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDao.class);
        var shares=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class);
        var rules=org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class);
        var volumes=org.mockito.Mockito.mock(com.cloud.storage.dao.VolumeDao.class);
        var guest=org.mockito.Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        var instance=new StorageServiceInstanceVO("test",null,1,2,13,1L,"SYSTEM_VM");instance.setVmId(174L);
        var policy=new StorageDirectoryPolicyVO();set(policy,"id",9L);policy.setInstanceId(instance.getId());policy.setVolumeId(77);
        policy.setCanonicalPath("export/team");policy.setCanonicalHash("a".repeat(64));policy.setOwnerUid(1001);policy.setOwnerGid(1001);policy.setMode("0770");
        policy.setMetadataJson("{\"filesystemuuid\":\"fs\",\"inode\":42}");
        org.mockito.Mockito.when(policies.listByInstanceId(instance.getId())).thenReturn(java.util.Collections.singletonList(policy));
        org.mockito.Mockito.when(policies.findById(9L)).thenReturn(policy);org.mockito.Mockito.when(policies.findByUuid(policy.getUuid())).thenReturn(policy);
        var volume=org.mockito.Mockito.mock(com.cloud.storage.VolumeVO.class);
        org.mockito.Mockito.when(volume.getUuid()).thenReturn("volume");org.mockito.Mockito.when(volume.getInstanceId()).thenReturn(174L);
        org.mockito.Mockito.when(volume.getDataCenterId()).thenReturn(13L);org.mockito.Mockito.when(volume.getState()).thenReturn(com.cloud.storage.Volume.State.Ready);
        org.mockito.Mockito.when(volumes.findById(77L)).thenReturn(volume);
        var candidate=new StorageFileShareVO(instance.getId(),StorageServiceInstance.Protocol.SMB,"other","/export/other",77L,"xfs",null,StorageServiceInstance.ResourceState.Creating,"{}");
        JsonObject observed=JsonParser.parseString("{\"status\":\"OBSERVED\",\"volumeuuid\":\"volume\",\"canonicalpath\":\"export/team\",\"filesystemuuid\":\"fs\",\"inode\":42,\"owneruid\":1001,\"ownergid\":1001,\"mode\":\"0770\",\"filesystem\":\"xfs\"}").getAsJsonObject();
        JsonObject ref=new JsonObject();ref.addProperty("uuid",candidate.getUuid());ref.addProperty("protocol","SMB");ref.addProperty("name","other");
        JsonArray refs=new JsonArray();refs.add(ref);observed.add("references",refs);
        JsonObject actual=new JsonObject();actual.addProperty("uuid",policy.getUuid());actual.addProperty("revision",1);observed.add("policy",actual);
        JsonArray directories=new JsonArray();directories.add(observed);JsonObject response=new JsonObject();response.add("directories",directories);
        org.mockito.Mockito.when(guest.dispatch(org.mockito.Mockito.any())).thenAnswer(call->{
            StorageServiceGuestCommand command=call.getArgument(0);assertEquals("directory policy inspect",command.getOperation());
            JsonObject query=JsonParser.parseString(command.getPayload()).getAsJsonObject();
            assertEquals("/srv/ablestack-storage/volumes/volume/export/team",query.getAsJsonArray("shares").get(0).getAsJsonObject().getAsJsonObject("config").get("backingPath").getAsString());
            return new StorageServiceGuestCommandResult(true,"observed",response.toString());
        });
        new StorageDirectoryPolicies(policies,shares,rules,volumes,guest,v->{}).guardShare(instance,candidate,9L,false);
        assertEquals("/export/other",candidate.getPath());
        JsonObject saved=JsonParser.parseString(candidate.getConfigJson()).getAsJsonObject();
        assertEquals(policy.getUuid(),saved.get("directoryPolicyUuid").getAsString());
        assertEquals("/srv/ablestack-storage/volumes/volume/export/team",saved.get("backingPath").getAsString());
        assertFalse(saved.get("createDirectory").getAsBoolean());
    }
    @Test public void realSerializerKeepsVerifiedPublicationStateAndListenerCountTyped() throws Exception {
        JsonObject report=JsonParser.parseString("{\"status\":\"READY\",\"revision\":30,\"observedat\":1793464664,\"expiresat\":1793464694,\"fingerprint\":\"publication-verified\",\"checks\":[],\"impact\":{\"ackrequired\":true},\"directorypreview\":{\"entrycount\":1,\"after\":{\"owneruid\":65534,\"ownergid\":65534,\"mode\":\"2775\"},\"smbpublication\":[{\"name\":\"s1455-child\",\"uuid\":\"share\",\"state\":\"UNPUBLISHED_WAITING_ACL\",\"listeners\":2}]}}").getAsJsonObject();
        StorageServiceConfigurationPreflightResponse response=new StorageServiceConfigurationPreflightResponse(report);
        response.setResponseName("preflightstorageserviceconfigurationresponse");
        String wire=com.cloud.api.response.ApiResponseSerializer.toSerializedString(response,"json");
        JsonObject decoded=JsonParser.parseString(wire).getAsJsonObject().getAsJsonObject("preflightstorageserviceconfigurationresponse")
                .getAsJsonObject("configurationpreflight").getAsJsonObject("directorypreview");
        JsonObject publication=decoded.getAsJsonArray("smbpublication").get(0).getAsJsonObject();
        assertEquals("UNPUBLISHED_WAITING_ACL",publication.get("state").getAsString());
        assertEquals(2,publication.get("listeners").getAsInt());
        assertEquals("2775",decoded.getAsJsonObject("after").get("mode").getAsString());
        String path=System.getProperty("storage.smb.publication.fixture");
        if(path!=null)Files.writeString(Path.of(path),wire+"\n");
    }

    @Test public void inspectionFailurePreservesOnlySafeCodesAndDoesNotChangeOtherRpcContracts() {
        var guest=org.mockito.Mockito.mock(StorageServiceGuestCommandDispatcher.class);
        var engine=new StorageDirectoryPolicies(
                org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageDirectoryPolicyDao.class),
                org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageFileShareDao.class),
                org.mockito.Mockito.mock(org.apache.cloudstack.storage.dataservice.dao.StorageAccessRuleDao.class),
                org.mockito.Mockito.mock(com.cloud.storage.dao.VolumeDao.class),guest,v->{});
        var instance=new StorageServiceInstanceVO("fixture",null,1,2,13,1L,"SYSTEM_VM");instance.setVmId(174L);
        for(String code:new String[]{"DIRECTORY_IDENTITY_CHANGED","PAYLOAD_LIMIT","secret token with spaces"}) {
            JsonObject failure=new JsonObject();failure.addProperty("error",true);failure.addProperty("code",code);
            org.mockito.Mockito.when(guest.dispatch(org.mockito.Mockito.any())).thenReturn(new StorageServiceGuestCommandResult(false,"private diagnostic text",failure.toString()));
            try {engine.observations(instance,java.util.Collections.emptyList());fail("Observation must reject");}
            catch(StorageDirectoryPolicies.ObservationFailure expected) {
                JsonObject d=expected.diagnostic();
                assertEquals(code.contains(" ")? "DIRECTORY_OBSERVATION_UNAVAILABLE":code,d.get("code").getAsString());
                assertEquals("GUEST_OBSERVATION_REJECTED",d.get("reason").getAsString());
                assertFalse(d.toString().contains("private diagnostic"));
                assertFalse(d.toString().contains("secret token"));
            }
        }
    }

    @Test public void actualAclCommandAndPublicIntentPreserveLongIdsAndExactDelta() throws Exception {
        UpdateStorageDirectoryPolicyCmd cmd=new UpdateStorageDirectoryPolicyCmd();
        set(cmd,"id",9L);set(cmd,"instanceId",1L);set(cmd,"expectedPolicyRevision",3L);set(cmd,"ownerGid",100001L);
        String patch="{\"version\":1,\"entries\":[{\"action\":\"UPSERT\",\"scope\":\"DEFAULT\",\"principalType\":\"UID\",\"principal\":\"100001\",\"permissions\":\"r-x\"}]}";
        set(cmd,"aclChanges",patch);
        JsonObject actual=StorageServiceConfigurationPreflight.commandIntent(cmd);
        JsonObject ui=new JsonObject();ui.addProperty("api","updateStorageDirectoryPolicy");ui.addProperty("resourceid",9L);
        ui.addProperty("expectedpolicyrevision",3L);ui.add("posixchanges",JsonParser.parseString("{\"ownerGid\":100001}"));
        ui.add("aclchanges",JsonParser.parseString(patch));
        JsonObject accepted=StorageServiceConfigurationPreflight.parseIntent(ui.toString());
        assertEquals(accepted.get("aclchanges"),actual.get("aclchanges"));
        assertEquals(accepted.get("posixchanges"),actual.get("posixchanges"));
        DeleteStorageDirectoryPolicyCmd deletion=new DeleteStorageDirectoryPolicyCmd();
        set(deletion,"id",9L);set(deletion,"instanceId",1L);set(deletion,"expectedPolicyRevision",3L);
        assertEquals("deleteStorageDirectoryPolicy",StorageServiceConfigurationPreflight.commandIntent(deletion).get("api").getAsString());
        assertEquals("DirectoryPosixPolicy",StorageServiceConfigurationCoordinator.resourceType(deletion));
        assertThrows(com.cloud.exception.InvalidParameterValueException.class,()->StorageDirectoryPolicies.aclChanges(
            JsonParser.parseString("{\"version\":1,\"entries\":[],\"force\":true}")));
    }

    @Test public void directoryAclRequiresActualNfsEnforcementNotOnlyHelperSchema() {
        JsonObject candidate=JsonParser.parseString("{\"action\":\"UPDATE\",\"policy\":{},\"aclchanges\":{\"version\":1,\"entries\":[]},\"units\":[],\"references\":[{\"protocol\":\"NFS\"}]}").getAsJsonObject();
        JsonObject oldHelper=JsonParser.parseString("{\"sharedDirectoryPolicyAclVersion\":1}").getAsJsonObject();
        var missing=assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->StorageDirectoryPolicies.requireNfsAclCapability(candidate,oldHelper));
        assertEquals("DIRECTORY_NFS_POSIX_ACL_UNAVAILABLE",missing.getMessage());
        JsonObject unsupported=JsonParser.parseString("{\"nfsposixacl\":{\"status\":\"BLOCKED\",\"supported\":false,\"code\":\"DIRECTORY_NFS_POSIX_ACL_UNSUPPORTED\"}}").getAsJsonObject();
        assertEquals("DIRECTORY_NFS_POSIX_ACL_UNSUPPORTED",assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,
                ()->StorageDirectoryPolicies.requireNfsAclCapability(candidate,unsupported)).getMessage());
        JsonObject valid=JsonParser.parseString("{\"nfsposixacl\":{\"status\":\"READY\",\"supported\":true,\"fingerprint\":\""+"a".repeat(64)+"\"}}").getAsJsonObject();
        StorageDirectoryPolicies.requireNfsAclCapability(candidate,valid);
        assertEquals("a".repeat(64),candidate.get("expectedNfsAclFingerprint").getAsString());
        JsonObject legacy=JsonParser.parseString("{\"action\":\"UPDATE\",\"policy\":{},\"units\":[],\"references\":[{\"protocol\":\"NFS\"}]}").getAsJsonObject();
        StorageDirectoryPolicies.requireNfsAclCapability(legacy,new JsonObject());
    }

    @Test public void actualImplicitProofIsBoundToInstanceAndCopiedIntoApplyApproval() throws Exception {
        com.google.gson.JsonObject proof;
        try(var in=getClass().getResourceAsStream("/storage-config/storage-1465-smb-readiness-native.json")) {
            org.junit.Assert.assertNotNull(in);proof=com.google.gson.JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        var candidate=new com.google.gson.JsonObject();candidate.add("instanceUuid",proof.getAsJsonObject("proof").get("instanceUuid"));
        var preview=new com.google.gson.JsonObject();preview.add("smbreadiness",proof);
        StorageDirectoryPolicies.bindSmbReadiness(candidate,preview);
        org.junit.Assert.assertEquals(proof,candidate.get("expectedSmbReadiness"));
        proof.getAsJsonObject("proof").addProperty("port",1445);
        org.junit.Assert.assertEquals(445,candidate.getAsJsonObject("expectedSmbReadiness").getAsJsonObject("proof").get("port").getAsInt());
        candidate.addProperty("instanceUuid","wrong-instance");
        org.junit.Assert.assertThrows(com.cloud.utils.exception.CloudRuntimeException.class,()->StorageDirectoryPolicies.bindSmbReadiness(candidate,preview));
    }


    private JsonObject guestReadinessWire(JsonObject observed, JsonObject payload) throws Exception {
        Path probe=Path.of("../systemvm/test/smb_readiness_wire_probe.py").toAbsolutePath().normalize();
        if(!Files.exists(probe)) probe=Path.of("systemvm/test/smb_readiness_wire_probe.py").toAbsolutePath().normalize();
        org.junit.Assume.assumeTrue("guest Python contract requires the source tree",Files.exists(probe));
        Process process=new ProcessBuilder("python3",probe.toString()).redirectErrorStream(true).start();
        JsonObject input=new JsonObject();input.add("observed",observed);input.add("payload",payload);
        try(var stream=process.getOutputStream()) {
            stream.write(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue("guest verifier timed out",process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));
        String reply=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(reply,0,process.exitValue());
        return JsonParser.parseString(reply).getAsJsonObject();
    }

    @Test public void actualCoordinatorGsonWirePassesGuestGateWithoutWeakeningListenerIdentity() throws Exception {
        JsonObject nativeReport;
        try(var in=getClass().getResourceAsStream("/storage-config/storage-1465-managed-readiness-native.json")) {
            assertNotNull(in);nativeReport=JsonParser.parseString(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Field field=StorageServiceConfigurationCoordinator.class.getDeclaredField("JSON");
        field.setAccessible(true);Gson coordinatorJson=(Gson)field.get(null);
        for(boolean covered:new boolean[]{false,true}) {
            JsonObject observed=nativeReport.deepCopy();
            if(covered) {
                JsonObject alias=observed.getAsJsonArray("listeners").get(0).getAsJsonObject().deepCopy();
                alias.addProperty("id","ffac5c28-ec95-482f-a2d6-c6a89f10e749");alias.addProperty("listenIp","10.10.13.221");
                alias.addProperty("state","COVERED");alias.add("coveredBy",observed.getAsJsonArray("listeners").get(0).getAsJsonObject().get("id"));
                alias.add("pid",JsonNull.INSTANCE);observed.getAsJsonArray("listeners").add(alias);
            }
            JsonObject command=new JsonObject();command.add("instanceUuid",observed.get("instanceUuid"));
            JsonObject preview=new JsonObject();preview.add("smbreadiness",observed);
            StorageDirectoryPolicies.bindSmbReadiness(command,preview);
            JsonObject payload=new JsonObject(),commands=new JsonObject();commands.add("directory policy apply",command);payload.add("candidate",commands);
            JsonObject wire=JsonParser.parseString(coordinatorJson.toJson(payload)).getAsJsonObject();
            JsonObject proof=wire.getAsJsonObject("candidate").getAsJsonObject("directory policy apply").getAsJsonObject("expectedSmbReadiness");
            assertFalse(proof.getAsJsonArray("listeners").get(0).getAsJsonObject().has("coveredBy"));
            assertEquals("ACCEPTED",guestReadinessWire(observed,wire).get("code").getAsString());
            // Old snapshots with explicit optional nulls remain equivalent.
            assertEquals("ACCEPTED",guestReadinessWire(observed,payload).get("code").getAsString());
            for(String key:new String[]{"pid","port","listenIp","coveredBy"}) {
                JsonObject changed=wire.deepCopy();
                JsonObject row=changed.getAsJsonObject("candidate").getAsJsonObject("directory policy apply")
                        .getAsJsonObject("expectedSmbReadiness").getAsJsonArray("listeners").get(0).getAsJsonObject();
                if(key.equals("pid") || key.equals("port")) row.addProperty(key,row.get(key).getAsInt()+1);
                else row.addProperty(key,"changed-identity");
                assertEquals(key,"DIRECTORY_SMB_IDENTITY_CHANGED",guestReadinessWire(observed,changed).get("code").getAsString());
            }
            if(covered) {
                JsonObject changed=wire.deepCopy();
                changed.getAsJsonObject("candidate").getAsJsonObject("directory policy apply").getAsJsonObject("expectedSmbReadiness")
                        .getAsJsonArray("listeners").get(2).getAsJsonObject().addProperty("coveredBy","wrong-owner");
                assertEquals("DIRECTORY_SMB_IDENTITY_CHANGED",guestReadinessWire(observed,changed).get("code").getAsString());
            }
        }
    }

}
