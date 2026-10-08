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

import java.util.Map;
import java.util.Set;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** File presence is exact; accepted resource transitions have the same applied meaning. */
final class StorageCanonicalRecovery {
    private StorageCanonicalRecovery() { }

    static String protocolPath(StorageServiceInstance.Protocol protocol) {
        switch (protocol) {
            case NFS: return "desired-state/nfs-export-apply.json";
            case SMB: return "desired-state/smb-share-apply.json";
            case ISCSI: return "iscsi-targets.json";
            default: return "nvmeof-subsystems.json";
        }
    }

    static boolean emptyProtocolFile(StorageServiceInstance.Protocol protocol, JsonElement value) {
        if (value == null || !value.isJsonObject()) return false;
        String collection = protocol == StorageServiceInstance.Protocol.NFS ? "exports"
                : protocol == StorageServiceInstance.Protocol.SMB ? "shares"
                : protocol == StorageServiceInstance.Protocol.ISCSI ? "targets" : "subsystems";
        JsonElement items = value.getAsJsonObject().get(collection);
        return items != null && items.isJsonArray() && items.getAsJsonArray().size() == 0;
    }

    static void requireSameMeaning(JsonObject source, JsonObject observed) {
        if (source == null || observed == null || !normalize(source).equals(normalize(observed))) {
            throw new CloudRuntimeException("Reconciled runtime declarative semantics differ from the verified recovery source");
        }
    }

    private static JsonElement normalize(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                JsonElement field = entry.getValue();
                if ("state".equals(entry.getKey()) && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()
                        && Set.of("Ready", "Creating", "Updating").contains(field.getAsString())) {
                    result.addProperty(entry.getKey(), "Ready");
                } else {
                    result.add(entry.getKey(), normalize(field));
                }
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement entry : value.getAsJsonArray()) result.add(normalize(entry));
            return result;
        }
        return value.deepCopy();
    }
}
