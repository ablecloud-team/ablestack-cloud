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

import java.util.HashSet;
import java.util.Set;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.cloud.utils.exception.CloudRuntimeException;

/** A signed code handler may run only in a separately authorized disposable fixture; public production AD remains unchanged. */
public final class StorageAdValidationFeaturePolicy {
    public static final String HANDLER="SMB_AD_IDENTITY_HANDLER";
    private static final Set<String> PRODUCTION=Set.of("SMB_ACTIVE_DIRECTORY","SMB_AD_IDENTITY","POSIX_AD_PRINCIPALS");
    private StorageAdValidationFeaturePolicy(){ }
    private static Boolean flag(JsonObject value,String key){return value.has(key)&&value.get(key).isJsonPrimitive()&&value.get(key).getAsJsonPrimitive().isBoolean()?value.get(key).getAsBoolean():null;}
    private static boolean contains(JsonObject value,String field,String feature){if(!value.has(field)||!value.get(field).isJsonArray())return false;for(JsonElement item:value.getAsJsonArray(field))if(item.isJsonPrimitive()&&item.getAsJsonPrimitive().isString()&&feature.equals(item.getAsString()))return true;return false;}
    public static boolean hasProductionAd(Set<String> declared){return declared!=null&&declared.stream().anyMatch(PRODUCTION::contains);}
    public static Set<String> scoped(Set<String> declared,JsonObject signedProof,JsonObject availability) {
        if(declared==null)throw new CloudRuntimeException("Declared runtime feature set is unavailable");
        if(!hasProductionAd(declared))return java.util.Collections.unmodifiableSet(new HashSet<>(declared));
        if(!Boolean.TRUE.equals(flag(signedProof,"readOnly"))||!Boolean.TRUE.equals(flag(signedProof,"signedRuntimeVerified"))||!Boolean.TRUE.equals(flag(signedProof,"nativeFileHashesVerified"))||!contains(signedProof,"signedSupportedFeatures",HANDLER))throw new CloudRuntimeException("AD fixture handler is not part of the exact installed signed runtime");
        if(!Boolean.TRUE.equals(flag(availability,"success"))||!Boolean.FALSE.equals(flag(availability,"sideEffects"))||!Boolean.TRUE.equals(flag(availability,"adIdentityHandlerSupported"))||!Boolean.TRUE.equals(flag(availability,"dependencyAvailable"))||!availability.has("schemaVersion")||!availability.get("schemaVersion").isJsonPrimitive()||!availability.get("schemaVersion").getAsJsonPrimitive().isNumber()||!"1".equals(availability.get("schemaVersion").getAsString())||!contains(availability,"supportedFeatures",HANDLER)||!availability.has("missingExecutables")||!availability.get("missingExecutables").isJsonArray()||availability.getAsJsonArray("missingExecutables").size()!=0)throw new CloudRuntimeException("Fresh native AD handler or its exact dependencies are unavailable");
        Set<String> result=new HashSet<>(declared);boolean ad=result.removeAll(PRODUCTION);if(ad)result.add(HANDLER);return java.util.Collections.unmodifiableSet(result);
    }
}
