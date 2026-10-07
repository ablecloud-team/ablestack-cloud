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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Signed capabilities prevent runtime changes that discard active desired-state semantics. */
public final class StorageRuntimeFeatureCompatibility {
    private StorageRuntimeFeatureCompatibility() { }
    public static Set<String> advertised(final JsonObject manifest) {
        if (!manifest.has("supportedFeatures")) return Collections.emptySet();
        if (!manifest.get("supportedFeatures").isJsonArray()) throw new CloudRuntimeException("Runtime supportedFeatures must be a signed string array");
        final Set<String> features = new HashSet<>();
        for (JsonElement value : manifest.getAsJsonArray("supportedFeatures")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !value.getAsString().matches("[A-Z0-9_]{1,64}") || !features.add(value.getAsString())) {
                throw new CloudRuntimeException("Invalid or duplicate runtime feature capability");
            }
        }
        return features;
    }
    public static void require(final JsonObject manifest, final Set<String> required) {
        final Set<String> missing = new java.util.TreeSet<>(required);missing.removeAll(advertised(manifest));
        if (!missing.isEmpty()) throw new CloudRuntimeException("Runtime bundle lacks active service features: " + String.join(", ", missing));
    }
    public static Set<String> shareFeatures(final JsonObject config, final StorageServiceInstance.Protocol protocol) {
        final Set<String> result = new HashSet<>();
        if (config.has("relativeSharePath")) result.add("NESTED_FILE_SHARE");
        if (config.has("posixPolicyUuid")) result.add("POSIX_DIRECTORY_POLICY");
        if (protocol == StorageServiceInstance.Protocol.SMB) {
            if (config.has("ownershipInheritance") && "INHERIT_PARENT_OWNER".equals(config.get("ownershipInheritance").getAsString())) result.add("SMB_PARENT_OWNER");
            if (config.has("posixOwnershipMode") && "FORCED_UID_GID".equals(config.get("posixOwnershipMode").getAsString())) result.add("SMB_FORCED_IDENTITY");
            if (config.has("forceCreateMode") || config.has("forceDirectoryMode") || config.has("inheritPermissions")) result.add("SMB_CREATION_MODE");
        }
        return result;
    }
}
