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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.cloud.utils.db.DbProperties;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;

/** Exact API intent is stored only as a HMAC under the protected management key. */
public final class StorageServiceRequestFingerprint {
    private StorageServiceRequestFingerprint() { }

    public static String of(BaseCmd command) {
        return of(command, DbProperties.getDbProperties().getProperty("db.cloud.encrypt.secret"));
    }

    static String of(BaseCmd command, String protectedKey) {
        SortedMap<String, JsonElement> parameters = new TreeMap<>();
        Gson gson = new Gson();
        try {
            for (Class<?> current = command.getClass(); current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    Parameter parameter = field.getAnnotation(Parameter.class);
                    if (parameter == null) continue;
                    String name = parameter.name().toLowerCase(Locale.ROOT);
                    if (Set.of("idempotencykey", "expectedrevision").contains(name)) continue;
                    field.setAccessible(true);
                    Object value = field.get(command);
                    if (value != null) parameters.put(name, gson.toJsonTree(value));
                }
            }
            if (protectedKey == null || protectedKey.isBlank()) {
                throw new CloudRuntimeException("Protected management key is required for Storage Service request idempotency");
            }
            JsonObject intent = new JsonObject();
            intent.addProperty("command", command.getClass().getName());
            intent.add("parameters", canonical(gson.toJsonTree(parameters)));
            // Authenticate the whole body, including JSON strings and nested secret fields.
            // Neither plaintext credentials nor independently guessable digests are persisted.
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(protectedKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            hmac.update("StorageServiceIdempotencyV1\0".getBytes(StandardCharsets.UTF_8));
            return "API_INTENT_HMAC_V1:" + hex(hmac.doFinal(intent.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (ReflectiveOperationException | GeneralSecurityException failure) {
            throw new CloudRuntimeException("Unable to fingerprint Storage Service request intent", failure);
        }
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            SortedMap<String, JsonElement> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> field : value.getAsJsonObject().entrySet()) {
                sorted.put(field.getKey(), field.getValue());
            }
            for (Map.Entry<String, JsonElement> field : sorted.entrySet()) {
                result.add(field.getKey(), canonical(field.getValue()));
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) result.add(canonical(element));
            return result;
        }
        return value.deepCopy();
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte part : bytes) value.append(String.format(Locale.ROOT, "%02x", part & 0xff));
        return value.toString();
    }
}
