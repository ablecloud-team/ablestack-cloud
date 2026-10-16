// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.storage.dataservice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public class StorageVerifiedNfsAclTargetRecoveryTest {
    private static final String INSTANCE="11111111-1111-4111-8111-111111111111";
    private static final String OLD="22222222-2222-4222-8222-222222222222";
    private static final String OP="33333333-3333-4333-8333-333333333333";
    private static final String ACL="44444444-4444-4444-8444-444444444444";
    private static final String SHARE="55555555-5555-4555-8555-555555555555";
    private static final String DATA="66666666-6666-4666-8666-666666666666";
    private static final String VM="77777777-7777-4777-8777-777777777777";
    private static final String ROOT="88888888-8888-4888-8888-888888888888";
    private static final String BOOT="99999999-9999-4999-8999-999999999999";
    private static final String SOURCE_SHA="627120f0c218d5d8a16b4aaee51d32f8122a8747613df59ba1c5b3c0e29fef8d";
    private static final String TARGET_SHA="1d27db76ef887835ef683e8332cbf5654cd07a0cd92f8f522d535f1a37ccbd9c";
    // Generated independently using the native Python JSON fingerprint contract.
    private static final String SOURCE_JSON="{\"desired-state/nfs-export-apply.json\":{\"enabled\":true,\"instanceUuid\":\"11111111-1111-4111-8111-111111111111\",\"instanceId\":7,\"protocolMode\":\"V4_ONLY\",\"port\":2049,\"exports\":[]},\"desired-state/smb-share-apply.json\":null,\"iscsi-targets.json\":null,\"nvmeof-subsystems.json\":null,\"posix-directory-policies.json\":null,\"network-endpoints.json\":null,\"sharedfs-network.json\":null}";
    private static final String TARGET_JSON="{\"desired-state/nfs-export-apply.json\":{\"enabled\":true,\"instanceUuid\":\"11111111-1111-4111-8111-111111111111\",\"instanceId\":7,\"protocolMode\":\"V4_ONLY\",\"port\":2049,\"exports\":[{\"id\":11,\"uuid\":\"55555555-5555-4555-8555-555555555555\",\"name\":\"issue914-nfs023\",\"path\":\"/export/issue914-nfs023\",\"volumeId\":42,\"filesystem\":\"ext4\",\"state\":\"Ready\",\"config\":{\"importMode\":\"MOUNT_EXISTING\",\"attachedVolumeUuid\":\"66666666-6666-4666-8666-666666666666\",\"filesystemUuid\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"relativeSharePath\":\"nfs023\","
            +"\"protocolMode\":\"V4_ONLY\"},\"acls\":[{\"id\":36,\"uuid\":\"44444444-4444-4444-8444-444444444444\",\"principalType\":\"CIDR\",\"principal\":\"10.10.0.0/16\",\"permission\":\"READ_WRITE\",\"state\":\"Ready\",\"config\":{\"readOnly\":false,\"rootSquash\":true,\"allSquash\":false,\"sync\":true,\"secure\":true,\"protocolMode\":\"V4_ONLY\",\"endpointMode\":\"LISTENER_GROUP\",\"listenerGroupPorts\":[2049]}}]}]},\"desired-state/smb-share-apply.json\":null,\"iscsi-targets.json\":null,\"nvmeof-subsystems.json\":null,\"posix-directory-policies.json\":null,\"network-endpoints.json\":null,\"sharedfs-network.json\":null}";
    private static final List<String> TABLES=List.of("storage_service_protocol","storage_file_share","storage_block_target",
            "storage_identity_domain","storage_access_rule","storage_posix_directory_policy");
    private static JsonObject obj(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static void field(Object object,String key,Object value)throws Exception{
        Field f=object.getClass().getDeclaredField(key);f.setAccessible(true);f.set(object,value);
    }
    private static JsonObject generation(String operation,long revision,String sha){
        JsonObject v=new JsonObject();v.addProperty("instanceUuid",INSTANCE);v.addProperty("operationUuid",operation);
        v.addProperty("revision",revision);v.addProperty("configurationSha256",sha);v.addProperty("verifiedAt",1000.5);return v;
    }
    private static JsonObject frozen(JsonObject desired,JsonObject gen){
        JsonObject v=obj("{\"success\":true,\"generationSupported\":true,\"frozen\":true}");
        v.add("generation",gen.deepCopy());v.addProperty("configurationSha256",gen.get("configurationSha256").getAsString());
        v.add("configurationDesiredState",desired.deepCopy());return v;
    }
    private static JsonObject table(JsonObject snapshot,int index){return snapshot.getAsJsonArray("tables").get(index).getAsJsonObject();}
    private static JsonObject row(JsonObject snapshot,int index){return table(snapshot,index).getAsJsonArray("rows").get(0).getAsJsonObject();}
    private static class Fixture {
        final StorageServiceOperationVO operation=new StorageServiceOperationVO();
        final JsonObject sourceDesired=obj(SOURCE_JSON),targetDesired=obj(TARGET_JSON);
        final JsonObject oldGeneration=generation(OLD,68,SOURCE_SHA),targetGeneration=generation(OP,69,TARGET_SHA);
        final JsonObject sourceFrozen=frozen(sourceDesired,oldGeneration),targetFrozen=frozen(targetDesired,targetGeneration);
        final JsonObject source,target,previous,result,bindings,status;
        String live;
        Fixture()throws Exception{
            operation.setInstanceId(7);operation.setRevision(69);operation.setCreatedBy(2);
            operation.setAction("createstoragenfsaclresponse");operation.setState("RECOVERY_REQUIRED");operation.setPhase("RECOVERY_REQUIRED");
            field(operation,"id",99L);field(operation,"uuid",OP);
            JsonObject share=obj("{\"id\":11,\"uuid\":\""+SHARE+"\",\"instance_id\":7,\"protocol\":\"NFS\",\"name\":\"issue914-nfs023\",\"path\":\"/export/nfs023\",\"volume_id\":42,\"filesystem\":\"ext4\",\"state\":\"Allocated\",\"created\":1000,\"quota_bytes\":null,\"posix_policy_id\":null}");
            JsonObject nativeExport=targetDesired.getAsJsonObject("desired-state/nfs-export-apply.json").getAsJsonArray("exports").get(0).getAsJsonObject();
            share.addProperty("config_json",nativeExport.getAsJsonObject("config").toString());
            JsonObject acl=obj("{\"id\":36,\"uuid\":\""+ACL+"\",\"resource_type\":\"FILE_SHARE\",\"resource_id\":11,\"principal_type\":\"CIDR\",\"principal\":\"10.10.0.0/16\",\"permission\":\"READ_WRITE\",\"secret_ref\":null,\"state\":\"Ready\",\"created\":1001}");
            JsonObject aclConfig=nativeExport.getAsJsonArray("acls").get(0).getAsJsonObject().getAsJsonObject("config");
            acl.addProperty("config_json",aclConfig.toString());
            source=obj("{\"schemaVersion\":2,\"instanceId\":7}");JsonArray tables=new JsonArray();
            for(String name:TABLES){
                JsonObject t=new JsonObject();t.addProperty("table",name);JsonArray columns=new JsonArray(),rows=new JsonArray();
                JsonObject sample="storage_file_share".equals(name)?share:("storage_access_rule".equals(name)?acl:obj("{\"id\":1,\"uuid\":\""+ROOT+"\",\"instance_id\":7}"));
                for(Map.Entry<String,JsonElement> entry:sample.entrySet()){
                    JsonObject column=new JsonObject();column.addProperty("name",entry.getKey());
                    column.addProperty("type",entry.getValue().isJsonPrimitive()&&entry.getValue().getAsJsonPrimitive().isNumber()?-5:12);columns.add(column);
                }
                if("storage_file_share".equals(name))rows.add(share.deepCopy());
                t.add("columns",columns);t.add("rows",rows);tables.add(t);
            }
            source.add("tables",tables);target=source.deepCopy();row(target,1).addProperty("state","Ready");table(target,4).getAsJsonArray("rows").add(acl);
            previous=source.deepCopy();JsonObject checkpoint=new JsonObject();checkpoint.add("previous",oldGeneration.deepCopy());
            previous.add("nativeGeneration",checkpoint);previous.add("nativeDesiredState",sourceDesired.deepCopy());
            bindings=obj("{\"vmId\":71,\"rootVolumeId\":41,\"templateId\":218}");
            bindings.addProperty("vmUuid",VM);bindings.addProperty("rootVolumeUuid",ROOT);JsonObject volumes=new JsonObject();
            JsonObject data=obj("{\"sizeBytes\":21474836480,\"poolId\":1,\"deviceId\":2,\"provisioningType\":\"SPARSE\"}");data.addProperty("path",DATA);volumes.add(DATA,data);bindings.add("dataVolumes",volumes);
            result=new JsonObject();JsonObject response=obj("{\"id\":\""+ACL+"\",\"resourcetype\":\"FILE_SHARE\",\"resourceid\":\""+SHARE+"\",\"principaltype\":\"CIDR\",\"principal\":\"10.10.0.0/16\",\"permission\":\"READ_WRITE\",\"state\":\"Ready\"}");
            response.addProperty("config",aclConfig.toString());result.add("_response",response);result.add("_targetResourceBindings",bindings.deepCopy());
            result.addProperty("_targetNativeBootId",BOOT);result.addProperty("_targetConfigurationSha256",TARGET_SHA);
            status=obj("{\"success\":true,\"generationSupported\":true,\"generationStatus\":\"PENDING\",\"runtimeRevision\":68}");
            status.add("generation",oldGeneration.deepCopy());status.addProperty("bootId",BOOT);status.addProperty("pendingOperationUuid",OP);
            status.addProperty("configurationSha256",TARGET_SHA);status.add("configurationDesiredState",targetDesired.deepCopy());
            live=source.toString();sync();
        }
        void sync(){operation.setPreviousSnapshotJson(previous.toString());operation.setSnapshotJson(target.toString());operation.setResultJson(result.toString());}
        JsonObject require(){sync();return StorageVerifiedNfsAclTargetRecovery.require(INSTANCE,operation,live,bindings,status,sourceFrozen,targetFrozen);}
    }
    @Test public void exactSourceDbAndCrashAfterTargetDbRestoreHaveSameAuthority()throws Exception{
        Fixture f=new Fixture();JsonObject before=f.require();Assert.assertEquals("SOURCE",before.get("databasePosition").getAsString());
        f.live=f.target.toString();JsonObject after=f.require();Assert.assertEquals("TARGET",after.get("databasePosition").getAsString());
        Assert.assertEquals(StorageVerifiedNfsAclTargetRecovery.authority(before),StorageVerifiedNfsAclTargetRecovery.authority(after));
        Assert.assertEquals(ACL,after.get("aclUuid").getAsString());Assert.assertEquals(SHARE,after.get("shareUuid").getAsString());
    }
    @Test public void repeatedNormalVerifyTimestampKeepsTargetAuthorityButSourceTimestampIsImmutable()throws Exception{
        Fixture f=new Fixture();JsonObject original=StorageVerifiedNfsAclTargetRecovery.authority(f.require());
        f.live=f.target.toString();
        f.targetFrozen.getAsJsonObject("generation").addProperty("verifiedAt",2000.75);
        Assert.assertEquals(original,StorageVerifiedNfsAclTargetRecovery.authority(f.require()));
        f.sourceFrozen.getAsJsonObject("generation").addProperty("verifiedAt",2000.75);
        Assert.assertThrows(CloudRuntimeException.class,f::require);
        Fixture malformed=new Fixture();malformed.targetFrozen.getAsJsonObject("generation").addProperty("verifiedAt",false);
        Assert.assertThrows(CloudRuntimeException.class,malformed::require);
    }
    @Test public void eligibilityNeverIncludesRunningOtherActionsOrMissingOperation()throws Exception{
        Fixture f=new Fixture();Assert.assertFalse(StorageVerifiedNfsAclTargetRecovery.eligible(null));
        for(String action:List.of("updateStorageNfsAcl","createstoragenfsexportresponse","CREATESTORAGENFSACLRESPONSE")){
            f.operation.setAction(action);Assert.assertFalse(StorageVerifiedNfsAclTargetRecovery.eligible(f.operation));
        }
        f.operation.setAction("createstoragenfsaclresponse");f.operation.setState("RUNNING");
        Assert.assertFalse(StorageVerifiedNfsAclTargetRecovery.eligible(f.operation));Assert.assertThrows(CloudRuntimeException.class,f::require);
    }
    @Test public void unchangedPublicCommittedTargetGuardStillRejectsPendingSourceCurrent()throws Exception{
        Fixture f=new Fixture();f.require();
        Assert.assertThrows(CloudRuntimeException.class,()->StorageOperationReconciliation.requireTarget(INSTANCE,f.operation,f.live,f.bindings,f.status));
    }
    private void deny(Consumer<Fixture> change)throws Exception{Fixture f=new Fixture();change.accept(f);Assert.assertThrows(CloudRuntimeException.class,f::require);}
    @Test public void rejectsForeignScopeRevisionBootBindingsAndPending()throws Exception{
        deny(f->f.status.addProperty("pendingOperationUuid",OLD));deny(f->f.status.addProperty("bootId",OLD));
        deny(f->f.operation.setRevision(70));deny(f->f.bindings.addProperty("vmUuid",OLD));
        deny(f->f.status.add("generation",f.targetGeneration.deepCopy()));deny(f->f.status.addProperty("generationStatus","IN_SYNC"));
        deny(f->f.status.addProperty("runtimeRevision",68.0));deny(f->f.status.addProperty("success","true"));
    }
    @Test public void rejectsMissingOrWeakDurableWitnessAndResponse()throws Exception{
        for(String key:List.of("_response","_targetResourceBindings","_targetNativeBootId","_targetConfigurationSha256"))deny(f->f.result.remove(key));
        deny(f->f.result.getAsJsonObject("_response").addProperty("resourceid",ROOT));
        deny(f->f.result.getAsJsonObject("_response").addProperty("principal","0.0.0.0/0"));
        deny(f->f.result.getAsJsonObject("_response").addProperty("password","never-authority"));
        deny(f->f.result.add("_targetResourceBindings",new JsonObject()));
    }
    @Test public void rejectsLiveDbDriftMixedSnapshotAndWeakSchema()throws Exception{
        deny(f->{JsonObject x=f.source.deepCopy();row(x,1).addProperty("state","Error");f.live=x.toString();});
        deny(f->{JsonObject x=f.target.deepCopy();table(x,4).getAsJsonArray("rows").remove(0);f.live=x.toString();});
        deny(f->f.previous.addProperty("schemaVersion",1));deny(f->f.target.addProperty("instanceId",8));
        deny(f->f.target.getAsJsonArray("tables").add(table(f.target,0).deepCopy()));
        deny(f->table(f.target,4).getAsJsonArray("rows").add(row(f.target,4).deepCopy()));
    }
    @Test public void rejectsAdditionalShareFieldsRowsAndOtherProtocolOrIdentityDelta()throws Exception{
        deny(f->row(f.target,1).addProperty("path","/foreign"));
        deny(f->row(f.target,1).addProperty("volume_id",999));
        deny(f->row(f.target,1).addProperty("protocol","SMB"));
        deny(f->row(f.target,1).addProperty("state","Allocated"));
        deny(f->table(f.target,1).getAsJsonArray("rows").add(row(f.target,1).deepCopy()));
        deny(f->table(f.target,3).getAsJsonArray("rows").add(obj("{\"id\":5,\"uuid\":\""+ROOT+"\",\"instance_id\":7}")));
    }
    @Test public void rejectsSecretAclDuplicateResourceAndWrongPrincipalTypes()throws Exception{
        deny(f->row(f.target,4).addProperty("secret_ref","foreign"));
        deny(f->row(f.target,4).addProperty("resource_type","BLOCK_TARGET"));
        deny(f->row(f.target,4).addProperty("resource_id",12));
        deny(f->row(f.target,4).addProperty("principal_type","LOCAL_USER"));
        deny(f->row(f.target,4).addProperty("principal","010.10.0.0/16"));
        deny(f->row(f.target,4).addProperty("principal","10.10.0.0/33"));
        deny(f->row(f.target,4).addProperty("created",true));
    }
    @Test public void rejectsTypedNumericBooleanAndJsonConfigChanges()throws Exception{
        deny(f->row(f.target,1).addProperty("volume_id",42.0));
        deny(f->row(f.target,4).addProperty("id","36"));
        deny(f->{JsonObject x=obj(row(f.target,4).get("config_json").getAsString());x.addProperty("rootSquash",1);row(f.target,4).addProperty("config_json",x.toString());});
        deny(f->{JsonObject x=obj(row(f.target,4).get("config_json").getAsString());x.addProperty("secret","foreign");row(f.target,4).addProperty("config_json",x.toString());});
    }
    @Test public void rejectsFrozenSourceTargetDriftAndFalseCapability()throws Exception{
        deny(f->f.sourceFrozen.addProperty("frozen",false));deny(f->f.targetFrozen.addProperty("frozen","true"));
        deny(f->f.targetFrozen.addProperty("configurationSha256","0".repeat(64)));
        deny(f->f.previous.getAsJsonObject("nativeGeneration").getAsJsonObject("previous").addProperty("revision",67));
        deny(f->f.targetFrozen.getAsJsonObject("configurationDesiredState").add("iscsi-targets.json",new JsonObject()));
        deny(f->f.status.getAsJsonObject("configurationDesiredState").add("network-endpoints.json",new JsonObject()));
    }
    @Test public void nativeAclExtraFieldsAreRejectedEvenAfterEveryTargetFingerprintIsRepinned()throws Exception{
        String[] fields={"unexpectedPrivilege","secret_ref"};
        String[] checksums={"a28bff8d8e449b7b558af6def0a6b540c951589fda01dee66f20205936623ad2","ecec37958f123bdd37a8c732a7ea9da8e1c5a5de3d0f2ab8d7a8cd7f39de126b"};
        for(int i=0;i<fields.length;i++){
            Fixture f=new Fixture();
            JsonObject changed=f.targetFrozen.getAsJsonObject("configurationDesiredState");
            JsonObject rule=changed.getAsJsonObject("desired-state/nfs-export-apply.json").getAsJsonArray("exports").get(0).getAsJsonObject().getAsJsonArray("acls").get(0).getAsJsonObject();
            if(i==0)rule.addProperty(fields[i],true);else rule.addProperty(fields[i],"opaque-recovery-source");
            f.targetFrozen.getAsJsonObject("generation").addProperty("configurationSha256",checksums[i]);
            f.targetFrozen.addProperty("configurationSha256",checksums[i]);
            f.status.add("configurationDesiredState",changed.deepCopy());f.status.addProperty("configurationSha256",checksums[i]);
            f.result.addProperty("_targetConfigurationSha256",checksums[i]);
            Assert.assertThrows(CloudRuntimeException.class,f::require);
        }
    }
    @Test public void strictJsonRejectsDuplicateMetadataBeforeAnyRestore()throws Exception{
        Fixture f=new Fixture();f.sync();String original=f.operation.getSnapshotJson();
        f.operation.setSnapshotJson(original.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2"));
        Assert.assertThrows(CloudRuntimeException.class,()->StorageVerifiedNfsAclTargetRecovery.require(INSTANCE,f.operation,f.live,f.bindings,f.status,f.sourceFrozen,f.targetFrozen));
    }
    @Test public void nativeFingerprintMatchesPythonAsciiAndPreservesIntegerFloatBooleanTypes()throws Exception{
        Method method=StorageVerifiedNfsAclTargetRecovery.class.getDeclaredMethod("digest",JsonElement.class);method.setAccessible(true);
        Assert.assertEquals(SOURCE_SHA,method.invoke(null,obj(SOURCE_JSON)));
        Assert.assertEquals(TARGET_SHA,method.invoke(null,obj(TARGET_JSON)));
        Assert.assertEquals("7581b48a02179ae0d1f5b2552c61f5f54595d6c22a6bfc2250b7816fd44a21eb",method.invoke(null,obj("{\"한글\":\"é😀\",\"a\":1,\"b\":1.0}")));
        Assert.assertNotEquals(method.invoke(null,obj("{\"a\":1}")),method.invoke(null,obj("{\"a\":1.0}")));
        Assert.assertNotEquals(method.invoke(null,obj("{\"a\":true}")),method.invoke(null,obj("{\"a\":1}")));
    }
}
