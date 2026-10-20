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

import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

public class StorageConfigInitialBaselineTest {
    private static String id(int value) {return String.format("00000000-0000-4000-8000-%012d",value);}
    private static JsonObject canonical() {
        JsonObject result=new JsonObject();
        for(String path:StorageRenderedDesiredState.PATHS)result.add(path,JsonNull.INSTANCE);
        result.add("sharedfs-network.json",new JsonObject());return result;
    }
    private static class Fixture {
        final JsonObject request=new JsonObject(),reply=new JsonObject(),data=new JsonObject();
        Fixture(int count,int boundary) {
            JsonObject scope=new JsonObject();scope.addProperty("instanceUuid",id(1));scope.addProperty("operationUuid",id(2));scope.addProperty("revision",1);
            for(String field:scope.keySet())request.add(field,scope.get(field).deepCopy());
            request.addProperty("expectedBootId",id(3));request.addProperty("expectedConfigurationSha256","a".repeat(64));
            JsonObject generation=new JsonObject();
            if(boundary==2){for(String field:scope.keySet())generation.add(field,scope.get(field).deepCopy());generation.addProperty("configurationSha256","a".repeat(64));generation.addProperty("verifiedAt",100.0);}
            request.add("expectedCurrentGeneration",generation);
            request.add("expectedPendingOperationUuid",boundary==1?scope.get("operationUuid").deepCopy():JsonNull.INSTANCE);
            request.addProperty("expectedVmUuid",id(4));request.addProperty("rootVolumeUuid",id(5));request.addProperty("rootSizeBytes",5368709120L);
            for(String field:new String[]{"success","readOnly","cloneInitialBaselineV1Supported","globalRuntimeEmpty","observationComplete"})reply.addProperty(field,true);
            reply.addProperty("initialSourceVerified",false);reply.addProperty("generatedEpoch",1000.0);
            reply.add("scope",scope);reply.add("bootId",request.get("expectedBootId").deepCopy());reply.add("configurationSha256",request.get("expectedConfigurationSha256").deepCopy());
            reply.add("generation",generation.deepCopy());reply.add("pendingOperationUuid",request.get("expectedPendingOperationUuid").deepCopy());
            reply.addProperty("runtimeRevision",boundary==2?1:0);reply.addProperty("generationStatus",boundary==1?"PENDING":boundary==2?"IN_SYNC":"UNVERIFIED");
            reply.addProperty("witnessBoundary",boundary==1?"OWN_PENDING":boundary==2?"COMMITTED_BASELINE":"INITIAL_IDLE");
            reply.add("configurationDesiredState",canonical());
            JsonObject root=new JsonObject();root.addProperty("volumeUuid",id(5));root.addProperty("serial",id(5).replace("-","").substring(0,20));
            root.addProperty("sizeBytes",5368709120L);root.addProperty("devicePath","/dev/vda");root.addProperty("mappingStatus","EXACT");root.addProperty("matchedBy","VOLUME_SERIAL");root.addProperty("vmUuid",id(4));reply.add("rootIdentity",root);
            JsonArray disks=new JsonArray();JsonObject disk=root.deepCopy();disk.addProperty("isRoot",true);disks.add(disk);
            JsonObject volumes=new JsonObject();
            for(int n=0;n<count;n++){
                String uuid=id(100+n),serial=uuid.replace("-","");
                // Use the complete serial here; a separate positive case covers virtio truncation with a nonambiguous prefix.
                JsonObject row=new JsonObject();row.addProperty("serial",serial);row.addProperty("sizeBytes",2147483648L);
                row.addProperty("observedDevicePath","/dev/vd"+(char)('b'+n));row.add("filesystem",JsonNull.INSTANCE);row.add("filesystemUuid",JsonNull.INSTANCE);row.add("mounts",new JsonArray());row.addProperty("kind","BLOCK_RAW");volumes.add(uuid,row);
                JsonObject actual=row.deepCopy();actual.add("devicePath",actual.remove("observedDevicePath"));actual.addProperty("isRoot",false);actual.add("volumeUuid",JsonNull.INSTANCE);disks.add(actual);
            }
            data.addProperty("bootId",id(3));data.add("volumes",volumes);reply.add("diskInventory",disks);
        }
        void verify(){StorageConfigInitialBaseline.requireWitness(reply,request,data,1000000);}
    }
    @Test public void realNativeBoundariesRemainDistinctWithoutFabricatingVerifiedInitialSource() {
        for(int boundary=0;boundary<3;boundary++)for(int count:new int[]{1,4})new Fixture(count,boundary).verify();
    }
    @Test public void uniqueVirtioTwentyCharacterSerialIsMatchedOnlyToTheFrozenFullUuid() {
        Fixture f=new Fixture(1,0);JsonObject data=f.data.getAsJsonObject("volumes").remove(id(100)).getAsJsonObject();
        String unique="bd507e8b-6e03-46af-88a9-0eaffce2a595";f.data.getAsJsonObject("volumes").add(unique,data);
        String serial=unique.replace("-","").substring(0,20);data.addProperty("serial",serial);f.reply.getAsJsonArray("diskInventory").get(1).getAsJsonObject().addProperty("serial",serial);f.verify();
    }
    @Test public void foreignOrUnknownRuntimeCannotBeInterpretedAsEmpty() {
        for(int mode=0;mode<4;mode++){
            Fixture f=new Fixture(1,0);
            if(mode==0)f.reply.addProperty("globalRuntimeEmpty",false);
            if(mode==1)f.reply.addProperty("observationComplete",false);
            if(mode==2)f.reply.addProperty("globalRuntimeEmpty","true");
            if(mode==3)f.reply.remove("globalRuntimeEmpty");
            Assert.assertThrows(CloudRuntimeException.class,f::verify);
        }
    }
    @Test public void unexpectedPendingScopeBootOrGenerationIsRejected() {
        for(int mode=0;mode<4;mode++){
            Fixture f=new Fixture(1,mode==3?2:1);
            if(mode==0)f.reply.addProperty("pendingOperationUuid",id(99));
            if(mode==1)f.reply.addProperty("bootId",id(99));
            if(mode==2)f.reply.getAsJsonObject("scope").addProperty("revision",2);
            if(mode==3)f.reply.getAsJsonObject("generation").addProperty("configurationSha256","f".repeat(64));
            Assert.assertThrows(CloudRuntimeException.class,f::verify);
        }
    }
    @Test public void missingAdditionalOrForeignExtraDataCannotPassTheFullDiskSet() {
        for(int mode=0;mode<3;mode++){
            Fixture f=new Fixture(4,0);JsonArray disks=f.reply.getAsJsonArray("diskInventory");
            if(mode==0)disks.remove(4);
            if(mode==1)disks.add(disks.get(1).deepCopy());
            if(mode==2)disks.get(4).getAsJsonObject().addProperty("serial",id(999).replace("-",""));
            Assert.assertThrows(CloudRuntimeException.class,f::verify);
        }
    }
    @Test public void filesystemAndMountIdentityCannotChangeBetweenTheTwoReadOnlyObservers() {
        for(int mode=0;mode<3;mode++){
            Fixture f=new Fixture(1,0);JsonObject disk=f.reply.getAsJsonArray("diskInventory").get(1).getAsJsonObject();
            if(mode==0)disk.addProperty("filesystemUuid",id(55));
            if(mode==1)disk.addProperty("devicePath","/dev/foreign");
            if(mode==2){JsonObject mount=new JsonObject();mount.addProperty("target","/srv/foreign");disk.getAsJsonArray("mounts").add(mount);}
            Assert.assertThrows(CloudRuntimeException.class,f::verify);
        }
    }
    @Test public void staleFactsAndReadOnlyWitnessClaimingAnAdoptedGenerationAreRejected() {
        Fixture stale=new Fixture(1,0);stale.reply.addProperty("generatedEpoch",969.0);Assert.assertThrows(CloudRuntimeException.class,stale::verify);
        Fixture adopted=new Fixture(1,0);adopted.reply.addProperty("initialSourceVerified",true);Assert.assertThrows(CloudRuntimeException.class,adopted::verify);
    }
    @Test public void configuredOrUnknownCanonicalFilesAreNotAnInitialEmptyBaseline() {
        Fixture f=new Fixture(1,0);JsonObject configured=new JsonObject();configured.addProperty("enabled",false);
        f.reply.getAsJsonObject("configurationDesiredState").add("desired-state/nfs-export-apply.json",configured);
        Assert.assertThrows(CloudRuntimeException.class,f::verify);
    }
}
