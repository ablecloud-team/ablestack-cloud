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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assert;
import org.junit.Test;

public class StorageUnchangedVerifiedSourceRecoveryTest {
    static Object value(Object object,String name)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    static void field(Object object,String name,Object value)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    static JsonObject object(String text){return JsonParser.parseString(text).getAsJsonObject();}
    static class Fixture {
        final StorageServiceOperationVO original=new StorageServiceOperationVO(),source;
        final JsonObject snapshot,bindings,status,frozen;
        Fixture()throws Exception {
            Class<?> type=Class.forName(StorageVerifiedNfsAclTargetRecoveryTest.class.getName()+"$Fixture");
            Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object data=constructor.newInstance();
            source=(StorageServiceOperationVO)value(data,"operation");snapshot=((JsonObject)value(data,"target")).deepCopy();
            bindings=((JsonObject)value(data,"bindings")).deepCopy();frozen=((JsonObject)value(data,"targetFrozen")).deepCopy();
            status=((JsonObject)value(data,"status")).deepCopy();
            source.setState("COMPLETE");source.setPhase("COMPLETE");source.setSnapshotJson(snapshot.toString());
            original.setInstanceId(source.getInstanceId());original.setCreatedBy(source.getCreatedBy());
            original.setRevision(source.getRevision()+1);original.setAction("createstoragenfsexportresponse");
            original.setState("RECOVERY_REQUIRED");original.setPhase("INTERRUPTED_RECOVERY_REQUIRED");
            original.setPreviousSnapshotJson(snapshot.toString());original.setResultJson("{\"_requestFingerprint\":\"test-protected-hmac-v1\"}");
            original.setDiagnostic("Original generation operation scope changed");field(original,"id",100L);
            field(original,"uuid","aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
            status.add("generation",frozen.get("generation").deepCopy());status.addProperty("runtimeRevision",source.getRevision());
            status.addProperty("configurationSha256",frozen.get("configurationSha256").getAsString());
            status.add("configurationDesiredState",frozen.get("configurationDesiredState").deepCopy());
            status.add("pendingOperationUuid",JsonNull.INSTANCE);status.addProperty("generationStatus","IN_SYNC");
        }
        String instance(){return status.getAsJsonObject("generation").get("instanceUuid").getAsString();}
        JsonObject proof(){return StorageUnchangedVerifiedSourceRecovery.require(instance(),original,source,snapshot.toString(),bindings,status,frozen);}
    }

    @Test public void actualSchema13SourceTargetCurrentAndExistingCompletedWitnessProduceOnlySourceAuthority()throws Exception {
        Fixture f=new Fixture();JsonObject proof=f.proof();
        Assert.assertEquals("UNCHANGED_VERIFIED_SOURCE",proof.get("kind").getAsString());
        Assert.assertEquals(f.original.getUuid(),proof.get("operationUuid").getAsString());
        Assert.assertEquals(f.source.getUuid(),proof.get("sourceOperationUuid").getAsString());
        Assert.assertEquals(f.source.getRevision(),proof.get("sourceRevision").getAsLong());
        Assert.assertNull(f.original.getSnapshotJson());Assert.assertFalse(object(f.original.getResultJson()).has("_targetNativeBootId"));
        Assert.assertFalse(proof.has("configurationDesiredState"));Assert.assertFalse(proof.has("tables"));
    }

    @Test public void missingOrWeakCompletedWitnessNeverAuthorizesUnchangedSource()throws Exception {
        for(String key:List.of("_response","_targetNativeBootId","_targetConfigurationSha256","_targetResourceBindings")) {
            Fixture f=new Fixture();JsonObject result=object(f.source.getResultJson());result.remove(key);f.source.setResultJson(result.toString());
            Assert.assertThrows(CloudRuntimeException.class,f::proof);
        }
        for(JsonElement weak:List.of(JsonNull.INSTANCE,new com.google.gson.JsonPrimitive("true"),new com.google.gson.JsonPrimitive(1))) {
            Fixture f=new Fixture();JsonObject result=object(f.source.getResultJson());result.add("_response",weak);f.source.setResultJson(result.toString());
            Assert.assertThrows(CloudRuntimeException.class,f::proof);
        }
    }

    @Test public void sourceSnapshotCurrentAndNumericTypesMustMatchWithoutCoercion()throws Exception {
        Fixture f=new Fixture();f.snapshot.addProperty("schemaVersion",2.0);
        Assert.assertThrows(CloudRuntimeException.class,f::proof);
        f=new Fixture();JsonObject previous=object(f.original.getPreviousSnapshotJson());
        previous.getAsJsonArray("tables").get(1).getAsJsonObject().getAsJsonArray("rows").get(0).getAsJsonObject().addProperty("state","Allocated");
        f.original.setPreviousSnapshotJson(previous.toString());Assert.assertThrows(CloudRuntimeException.class,f::proof);
        f=new Fixture();f.source.setSnapshotJson("{}");Assert.assertThrows(CloudRuntimeException.class,f::proof);
    }

    @Test public void ownPendingBootConfigOrProtectedSourceDriftFailsClosed()throws Exception {
        for(int mode=0;mode<8;mode++) {
            Fixture f=new Fixture();
            if(mode==0)f.status.addProperty("pendingOperationUuid",f.original.getUuid());
            if(mode==1)f.status.remove("pendingOperationUuid");
            if(mode==2)f.status.addProperty("bootId","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
            if(mode==3)f.status.addProperty("configurationSha256","b".repeat(64));
            if(mode==4)f.status.getAsJsonObject("generation").addProperty("revision",f.original.getRevision());
            if(mode==5)f.frozen.getAsJsonObject("generation").addProperty("verifiedAt",2000.0);
            if(mode==6)f.frozen.getAsJsonObject("configurationDesiredState").add("network-endpoints.json",new JsonObject());
            if(mode==7)f.status.addProperty("success","true");
            Assert.assertThrows(CloudRuntimeException.class,f::proof);
        }
    }

    @Test public void sourceOwnerBindingsAndOriginalIntentRemainImmutable()throws Exception {
        Fixture f=new Fixture();f.source.setCreatedBy(3);Assert.assertThrows(CloudRuntimeException.class,f::proof);
        f=new Fixture();f.bindings.addProperty("vmUuid","bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        Assert.assertThrows(CloudRuntimeException.class,f::proof);
        f=new Fixture();JsonObject prior=f.proof();f.original.setResultJson("{\"_requestFingerprint\":\"different-hmac\"}");
        Assert.assertFalse(StorageUnchangedVerifiedSourceRecovery.same(prior,f.proof()));
        f=new Fixture();f.source.setRevision(f.original.getRevision());Assert.assertThrows(CloudRuntimeException.class,f::proof);
    }

    @Test public void existingCheckpointPathsAreNotAdoptedAndAnExistingIntentCannotEscapeValidation()throws Exception {
        for(String key:List.of("nativeGeneration","nativeDesiredState","localCheckpointIntent","renderedGeneration")) {
            Fixture f=new Fixture();JsonObject previous=object(f.original.getPreviousSnapshotJson());previous.add(key,JsonNull.INSTANCE);
            f.original.setPreviousSnapshotJson(previous.toString());Assert.assertFalse(StorageUnchangedVerifiedSourceRecovery.eligible(f.original));
            JsonObject result=object(f.original.getResultJson());result.add(StorageUnchangedVerifiedSourceRecovery.RESULT_KEY,new JsonObject());
            f.original.setResultJson(result.toString());Assert.assertTrue(StorageUnchangedVerifiedSourceRecovery.eligible(f.original));
            Assert.assertThrows(CloudRuntimeException.class,f::proof);
        }
        Fixture other=new Fixture();other.original.setAction("createstoragenfsaclresponse");
        Assert.assertFalse(StorageUnchangedVerifiedSourceRecovery.eligible(other.original));
    }
}
