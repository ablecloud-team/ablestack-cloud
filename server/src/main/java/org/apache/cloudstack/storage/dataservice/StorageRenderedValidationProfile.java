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

import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.cloud.utils.exception.CloudRuntimeException;

/** Protected operator artifacts authorize fixture identity, never a production capability override. */
public final class StorageRenderedValidationProfile {
    private StorageRenderedValidationProfile() { }
    private static String text(JsonObject value,String key) {return value.has(key)&&value.get(key).isJsonPrimitive()?value.get(key).getAsString():null;}
    private static boolean yes(JsonObject value,String key) {return value.has(key)&&value.get(key).isJsonPrimitive()&&value.get(key).getAsJsonPrimitive().isBoolean()&&value.get(key).getAsBoolean();}
    public static void requireHandler(JsonObject observed) {
        boolean declared=false;if(observed.has("supportedFeatures")&&observed.get("supportedFeatures").isJsonArray())for(JsonElement feature:observed.getAsJsonArray("supportedFeatures"))if(feature.isJsonPrimitive()&&feature.getAsJsonPrimitive().isString()&&"RENDERED_CONFIG_GENERATION_HANDLER".equals(feature.getAsString()))declared=true;
        if(!yes(observed,"success")||!yes(observed,"renderedGenerationSupported")||!observed.has("schemaVersion")||!observed.get("schemaVersion").isJsonPrimitive()||!observed.get("schemaVersion").getAsJsonPrimitive().isNumber()||!"1".equals(observed.get("schemaVersion").getAsString())||!declared)throw new CloudRuntimeException("Fresh installed rendered generation handler proof is unavailable");
    }
    public static boolean retainedNewData(JsonObject current,JsonObject approved) {
        if(approved==null||!yes(approved,"newDataWithoutBacking")||!"DATADISK".equals(text(current,"type")))return false;
        JsonObject fresh=current.deepCopy(),previous=approved.deepCopy();fresh.remove("newDataWithoutBacking");previous.remove("newDataWithoutBacking");return fresh.equals(previous);
    }
    public static boolean owned(JsonObject artifact) {
        return "OWNED_SPARSE_ALL4_VALIDATION".equals(text(artifact,"kind"));
    }
    public static JsonObject next(JsonObject previous,boolean enabled,long revision) {
        if(!enabled&&yes(previous,"baselineImported"))throw new CloudRuntimeException("Imported rendered fixture remains guarded until verified retirement");
        JsonObject profile=new JsonObject();profile.addProperty("enabled",enabled);profile.addProperty("revision",revision);profile.addProperty("kind",previous.has("kind")?text(previous,"kind"):"NEW_SPARSE_ALL4_VALIDATION");
        for(String field:List.of("baselineImported","baselineManifest","fixtureProvenance"))if(previous.has(field))profile.add(field,previous.get(field).deepCopy());return profile;
    }
    public static void verify(JsonObject artifact,String instanceUuid,String name,JsonObject actualBindings,long now) {
        boolean owned=owned(artifact);
        if((!owned&&!"NEW_SPARSE_ALL4_VALIDATION".equals(text(artifact,"kind"))) || !instanceUuid.equals(text(artifact,"instanceUuid")) || !name.equals(text(artifact,"instanceName"))
                || !artifact.has("schemaVersion") || artifact.get("schemaVersion").getAsInt()!=1 || !yes(artifact,owned?"ownedDisposableFixture":"newDisposableFixture") || !yes(artifact,"originalDataExcluded"))throw new CloudRuntimeException("Rendered validation artifact does not authorize this disposable fixture");
        if(!artifact.has("expiresAtMillis") || artifact.get("expiresAtMillis").getAsLong()<=now || artifact.get("expiresAtMillis").getAsLong()>now+24L*60*60*1000)throw new CloudRuntimeException("Rendered validation artifact expired or exceeds its one-day scope");
        for(String field:List.of("expectedCliSha256","sourceCommit")) {
            String value=text(artifact,field);if(value==null || !value.matches(field.equals("sourceCommit")?"[a-f0-9]{40}":"[a-f0-9]{64}"))throw new CloudRuntimeException("Rendered validation source pin is unavailable");
        }
        if(!artifact.has("bindings") || !actualBindings.equals(artifact.get("bindings")))throw new CloudRuntimeException("Rendered validation VM/ROOT/DATA binding changed");
        if(!artifact.has("excludedInstanceUuids") || !artifact.get("excludedInstanceUuids").isJsonArray())throw new CloudRuntimeException("Rendered validation original-instance exclusion proof is unavailable");
        for(JsonElement excluded:artifact.getAsJsonArray("excludedInstanceUuids"))if(instanceUuid.equals(excluded.getAsString()))throw new CloudRuntimeException("Original SharedFS instance cannot use the disposable validation profile");
        if(artifact.getAsJsonArray("excludedInstanceUuids").size()<7)throw new CloudRuntimeException("Rendered validation artifact lacks the protected original fixture exclusion set");
        JsonObject volumes=actualBindings.getAsJsonObject("volumes");if(volumes==null || volumes.size()<2)throw new CloudRuntimeException("Rendered validation needs one fresh ROOT and disposable DATA bindings");
        int roots=0;
        for(java.util.Map.Entry<String,JsonElement> entry:volumes.entrySet()) {
            JsonObject volume=entry.getValue().getAsJsonObject();
            if(!("SPARSE".equals(text(volume,"provisioningType"))||"FAT".equals(text(volume,"provisioningType"))) || !yes(volume,"attachedToFixture") || !yes(volume,"ownerAndZoneVerified"))throw new CloudRuntimeException("Rendered validation requires SPARSE/FAT owned fixture disks");
            if("ROOT".equals(text(volume,"type")))roots++;else if(!"DATADISK".equals(text(volume,"type")) || (!owned&&!yes(volume,"newDataWithoutBacking")))throw new CloudRuntimeException("Rendered validation rejects reused, cloned or unknown DATA backing");
        }
        if(roots!=1)throw new CloudRuntimeException("Rendered validation ROOT identity is ambiguous");
    }
}
