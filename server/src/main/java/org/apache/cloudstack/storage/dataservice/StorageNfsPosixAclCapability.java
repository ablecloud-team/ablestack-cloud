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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.cloud.exception.InvalidParameterValueException;

/** Only fresh native build, binary and real access self-test attestation can authorize NFS named ACLs. */
public final class StorageNfsPosixAclCapability {
    private StorageNfsPosixAclCapability() { }
    private static boolean exactTrue(JsonObject value,String key) {
        return value.has(key) && value.get(key).isJsonPrimitive() && value.get(key).getAsJsonPrimitive().isBoolean() && value.get(key).getAsBoolean();
    }
    public static boolean supported(JsonObject value,long now) {
        if (!exactTrue(value,"success") || !exactTrue(value,"nfsVfsPosixAclSupported") || !exactTrue(value,"posixAclBuildEnabled") || !exactTrue(value,"posixAclSelfTestVerified")) return false;
        try {
            if(!value.has("generatedEpoch") || !value.get("generatedEpoch").isJsonPrimitive() || !value.get("generatedEpoch").getAsJsonPrimitive().isNumber())return false;
            if(!value.has("ganeshaVersion") || !value.get("ganeshaVersion").isJsonPrimitive() || !value.get("ganeshaVersion").getAsJsonPrimitive().isString())return false;
            double epoch=value.get("generatedEpoch").getAsDouble();if(!Double.isFinite(epoch) || Math.abs(now/1000.0-epoch)>30)return false;
            for(String field:List.of("ganeshaBuildManifestSha256","vfsLibrarySha256")) {
                JsonElement hash=value.get(field);if(hash==null || !hash.isJsonPrimitive() || !hash.getAsJsonPrimitive().isString() || !hash.getAsString().matches("[a-f0-9]{64}"))return false;
            }
            String version=value.get("ganeshaVersion").getAsString();java.util.regex.Matcher parsed=java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matcher(version);
            if(!parsed.matches())return false;int major=Integer.parseInt(parsed.group(1)),minor=Integer.parseInt(parsed.group(2)),patch=Integer.parseInt(parsed.group(3));
            if(major<5 || major==5 && (minor<5 || minor==5 && patch<3))return false;
            if(!value.has("supportedFeatures") || !value.get("supportedFeatures").isJsonArray())return false;
            for(JsonElement feature:value.getAsJsonArray("supportedFeatures"))if(feature.isJsonPrimitive() && feature.getAsJsonPrimitive().isString() && "NFS_VFS_POSIX_ACL".equals(feature.getAsString()))return true;
        } catch(RuntimeException unavailable) {return false;}
        return false;
    }
    public static boolean named(JsonObject config,JsonObject effective) {
        for(String key:List.of("accessEntries","defaultEntries"))if(config.has(key) && config.get(key).isJsonArray() && config.getAsJsonArray(key).size()>0)return true;
        if(effective.has("acl") && effective.get("acl").isJsonArray())for(JsonElement line:effective.getAsJsonArray("acl"))if(line.isJsonPrimitive() && line.getAsJsonPrimitive().isString() && line.getAsString().matches("(?:default:)?(?:user|group):[0-9]+:[rwx-]{3}"))return true;
        return false;
    }
    public static void require(JsonObject value,long now) {
        if(!supported(value,now))throw new InvalidParameterValueException("NFS_VFS_POSIX_ACL_UNAVAILABLE: installed NFS build and named-ACL access self-test are not verified");
    }
}
