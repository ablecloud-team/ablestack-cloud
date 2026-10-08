// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.util.Set;
import java.util.Map;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.cloud.utils.exception.CloudRuntimeException;

/** The retained native baseline and authenticated latest identity are distinct authorities. */
public final class StorageRetainedRootAuthorization {
    private StorageRetainedRootAuthorization() { }
    private static String string(JsonObject object,String key) {
        JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new CloudRuntimeException("Retained ROOT string field is invalid: "+key);return value.getAsString();
    }
    private static void hash(String value) {if(value==null||!value.matches("[a-f0-9]{64}"))throw new CloudRuntimeException("Retained ROOT immutable hash is invalid");}
    private static void literal(JsonObject object,String key,boolean expected) {
        JsonElement value=object.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean()||value.getAsBoolean()!=expected)throw new CloudRuntimeException("Retained ROOT literal proof is unavailable: "+key);
    }
    private static void scope(JsonObject scope) {
        if(scope==null||!scope.keySet().equals(Set.of("instanceUuid","operationUuid","templateUpgradeUuid","revision")))throw new CloudRuntimeException("Retained ROOT exact four-field scope is required");
        for(String key:Set.of("instanceUuid","operationUuid","templateUpgradeUuid"))if(!string(scope,key).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("Retained ROOT scope UUID is invalid");
        JsonElement revision=scope.get("revision");if(!revision.isJsonPrimitive()||!revision.getAsJsonPrimitive().isNumber()||!revision.getAsString().matches("[0-9]+"))throw new CloudRuntimeException("Retained ROOT scope revision is invalid");
    }
    private static JsonObject reference(JsonObject value) {
        if(value==null||!value.keySet().equals(Set.of("authorizationUuid","sha256"))||!string(value,"authorizationUuid").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new CloudRuntimeException("Retained ROOT protected reference is invalid");hash(string(value,"sha256"));return value.deepCopy();
    }
    public static JsonObject captureRequest(JsonObject scope,JsonObject retainedGeneration,String retainedRenderedSha256) {
        scope(scope);hash(retainedRenderedSha256);if(retainedGeneration==null||!string(scope,"instanceUuid").equals(string(retainedGeneration,"instanceUuid")))throw new CloudRuntimeException("Retained ROOT baseline generation belongs to another instance");
        JsonObject request=scope.deepCopy();request.add("expectedRetainedGeneration",retainedGeneration.deepCopy());request.addProperty("expectedRetainedRenderedSha256",retainedRenderedSha256);return request;
    }
    public static JsonObject requireCaptured(JsonObject scope,JsonObject generation,String renderedSha,JsonObject response) {
        captureRequest(scope,generation,renderedSha);literal(response,"success",true);literal(response,"retainedBaselineCaptured",true);literal(response,"canonicalDesiredStateChanged",false);literal(response,"dataPermissionsChanged",false);
        if(!scope.equals(response.get("scope"))||!generation.equals(response.get("retainedGeneration"))||!renderedSha.equals(string(response,"retainedRenderedSha256")))throw new CloudRuntimeException("Retained ROOT captured baseline changed");return reference(response.getAsJsonObject("baselineRef"));
    }
    public static JsonObject authorizeRequest(JsonObject scope,JsonObject baselineRef,JsonObject capsule,byte[] protectedPrivateKey,
            JsonObject originalSourceScope,String latestConfigurationSha,JsonArray fileVolumeBindings) {
        scope(scope);scope(originalSourceScope);hash(latestConfigurationSha);
        if(!string(scope,"instanceUuid").equals(string(originalSourceScope,"instanceUuid"))||!string(scope,"templateUpgradeUuid").equals(string(originalSourceScope,"templateUpgradeUuid")))throw new CloudRuntimeException("Retained ROOT latest capsule belongs to another instance or upgrade");
        JsonObject request=StorageIdentityCapsule.importRequest(string(scope,"instanceUuid"),string(originalSourceScope,"operationUuid"),capsule,protectedPrivateKey);
        for(Map.Entry<String,JsonElement> field:scope.entrySet())request.add(field.getKey(),field.getValue().deepCopy());
        request.add("baselineRef",reference(baselineRef));request.add("originalSourceScope",originalSourceScope.deepCopy());request.addProperty("sourceConfigurationSha256",latestConfigurationSha);request.add("fileVolumeBindings",fileVolumeBindings.deepCopy());return request;
    }
    public static JsonObject requireAuthorized(JsonObject scope,JsonObject generation,String renderedSha,String latestSha,JsonObject response) {
        literal(response,"success",true);literal(response,"retainedRootAuthorized",true);literal(response,"canonicalDesiredStateChanged",false);literal(response,"dataPermissionsChanged",false);hash(latestSha);
        if(!scope.equals(response.get("scope"))||!generation.equals(response.get("retainedGeneration"))||!renderedSha.equals(string(response,"retainedRenderedSha256"))||!latestSha.equals(string(response,"latestConfigurationSha256")))throw new CloudRuntimeException("Retained ROOT latest authorization changed its baseline or source");
        JsonObject ref=reference(response.getAsJsonObject("retainedRootAuthorization"));if(!string(ref,"authorizationUuid").equals(string(response,"authorizationUuid"))||!string(ref,"sha256").equals(string(response,"sha256")))throw new CloudRuntimeException("Retained ROOT nested authorization differs");return ref;
    }
}
