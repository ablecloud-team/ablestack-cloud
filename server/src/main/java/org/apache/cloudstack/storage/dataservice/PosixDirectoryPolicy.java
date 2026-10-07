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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Protocol-neutral directory identity and structured POSIX ACL validation. */
public final class PosixDirectoryPolicy {
    private PosixDirectoryPolicy() { }

    public static String relativePath(final String input) {
        if (input == null || input.isEmpty() || input.length() > 1024 || input.startsWith("/") || input.contains("\\")) {
            throw new InvalidParameterValueException("POSIX policy needs a non-empty relative directory path");
        }
        for (int index = 0; index < input.length(); index++) {
            if (Character.isISOControl(input.charAt(index))) throw new InvalidParameterValueException("Control characters are forbidden in directory paths");
        }
        for (String component : input.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new InvalidParameterValueException("Directory paths cannot contain empty, dot or parent components");
            }
        }
        return input;
    }

    public static String pathKey(final String volumeUuid, final String relativePath) {
        if (volumeUuid == null) throw new InvalidParameterValueException("Backing volume identity is required");
        final String identity;
        try { identity = UUID.fromString(volumeUuid).toString() + ":" + relativePath(relativePath); }
        catch (IllegalArgumentException invalid) { throw new InvalidParameterValueException("Invalid backing volume identity"); }
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            final StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static String directoryMode(final String input) {
        if (input == null || !input.matches("0?[0-7]{3,4}") || Integer.parseInt(input, 8) > 07777) {
            throw new InvalidParameterValueException("Directory mode must be octal 0000 through 7777");
        }
        return String.format(Locale.ROOT, "%04o", Integer.parseInt(input, 8));
    }

    public static void numericId(final Long value) {
        if (value == null || value < 0 || value > Integer.MAX_VALUE) {
            throw new InvalidParameterValueException("POSIX UID/GID must be in range 0 through 2147483647");
        }
    }

    public static JsonArray aclEntries(final JsonArray input) {
        final JsonArray result = new JsonArray();
        final Set<String> keys = new HashSet<>();
        if (input == null) return result;
        if (input.size() > 128) throw new InvalidParameterValueException("Too many POSIX ACL entries");
        for (JsonElement element : input) {
            if (!element.isJsonObject()) throw new InvalidParameterValueException("POSIX ACL entries must be structured objects");
            final JsonObject entry = element.getAsJsonObject();
            final String type = string(entry, "principalType");
            final String principal = string(entry, "principal");
            final String permission = string(entry, "permission");
            if ("NUMERIC_UID".equals(type) || "NUMERIC_GID".equals(type)) {
                try {
                    if (!principal.matches("[0-9]{1,10}")) throw new NumberFormatException();
                    numericId(Long.valueOf(principal));
                } catch (NumberFormatException invalid) { throw new InvalidParameterValueException("Invalid numeric POSIX principal"); }
            } else if ("LOCAL_USER".equals(type) || "LOCAL_GROUP".equals(type)) {
                if (!principal.matches("[A-Za-z_][A-Za-z0-9_.-]{0,63}")) throw new InvalidParameterValueException("Invalid local POSIX principal");
            } else {
                throw new InvalidParameterValueException("Only numeric and local POSIX principals are currently supported; AD awaits domain setup");
            }
            if (!Set.of("READ_ONLY", "READ_WRITE", "FULL_CONTROL").contains(permission)) {
                throw new InvalidParameterValueException("Invalid POSIX ACL permission");
            }
            final String normalized = type.startsWith("NUMERIC_") ? Long.toString(Long.parseLong(principal)) : principal;
            if (!keys.add(type + ":" + normalized)) throw new InvalidParameterValueException("Duplicate POSIX ACL principal");
            final JsonObject row = new JsonObject();row.addProperty("principalType", type);row.addProperty("principal", normalized);row.addProperty("permission", permission);
            result.add(row);
        }
        return result;
    }

    private static String string(final JsonObject entry, final String key) {
        if (!entry.has(key) || !entry.get(key).isJsonPrimitive() || !entry.get(key).getAsJsonPrimitive().isString()) {
            throw new InvalidParameterValueException("POSIX ACL entry needs " + key);
        }
        return entry.get(key).getAsString();
    }
}
