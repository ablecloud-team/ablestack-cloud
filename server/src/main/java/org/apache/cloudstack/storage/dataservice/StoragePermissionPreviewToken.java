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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** HA-safe, short-lived approval binds an actor, exact request and native inode identity. */
final class StoragePermissionPreviewToken {
    private static final long TTL_MILLIS = 600_000;
    private StoragePermissionPreviewToken() { }

    static String issue(JsonObject intent, JsonObject directoryIdentity, long actor, long now, String key) {
        JsonObject approval = new JsonObject();
        approval.addProperty("schemaVersion", 1);
        approval.addProperty("actor", actor);
        approval.addProperty("expiresAt", now + TTL_MILLIS);
        approval.add("intent", intent.deepCopy());
        approval.add("directoryIdentity", directoryIdentity.deepCopy());
        byte[] payload = approval.toString().getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload, key));
    }

    static JsonObject verify(String token, JsonObject intent, JsonObject directoryIdentity, long actor, long now, String key) {
        try {
            if (token == null || token.length() > 131_072) throw new IllegalArgumentException();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            if (!MessageDigest.isEqual(sign(payload, key), Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
            JsonObject approval = new JsonParser().parse(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
            long expires = approval.get("expiresAt").getAsLong();
            if (approval.get("schemaVersion").getAsInt() != 1 || approval.get("actor").getAsLong() != actor
                    || expires <= now || expires > now + TTL_MILLIS || !intent.equals(approval.get("intent"))
                    || !directoryIdentity.equals(approval.get("directoryIdentity"))) throw new IllegalArgumentException();
            return approval;
        } catch (RuntimeException invalid) {
            throw new InvalidParameterValueException("Permission preview changed, expired or belongs to another actor; refresh before applying");
        }
    }

    static String key() {
        return com.cloud.utils.db.DbProperties.getDbProperties().getProperty("db.cloud.encrypt.secret");
    }

    private static byte[] sign(byte[] payload, String key) {
        if (key == null || key.isBlank()) throw new CloudRuntimeException("Protected management key is required for permission preview approval");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update("StoragePermissionPreviewV1\0".getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException failure) {
            throw new CloudRuntimeException("Unable to authenticate permission preview", failure);
        }
    }
}
