// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.server;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.NetworkModel;

/** Registration-only requirements; optional VM user data can still be cleared. */
final class UserDataRegistrationValidation {
    private UserDataRegistrationValidation() { }

    private static String strip(String value) {
        return value == null ? "" : value.replaceAll("(?U)^[\\s\\uFEFF]+|[\\s\\uFEFF]+$", "");
    }

    static String name(String value) { return name(value, "User data"); }

    static String name(String value, String label) {
        String name = strip(value);
        if (name.isEmpty() || name.length() > 255) {
            throw new InvalidParameterValueException(label + " name must contain 1 to 255 characters after trimming whitespace.");
        }
        return name;
    }

    static void requireContent(String canonicalBase64) { requireContent(canonicalBase64, "User data"); }

    static void requireContent(String canonicalBase64, String label) {
        if (canonicalBase64 == null || strip(new String(Base64.decodeBase64(canonicalBase64), StandardCharsets.UTF_8)).isEmpty()) {
            throw new InvalidParameterValueException(label + " content must not be empty or whitespace only.");
        }
    }

    static String params(String value) { return params(value, "User data"); }

    static String params(String value, String label) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String part : value.split(",", -1)) {
            String key = strip(part);
            if (!key.matches("[A-Za-z_][A-Za-z0-9_.-]*") || NetworkModel.metadataFileNames.contains(key)) {
                throw new InvalidParameterValueException(label + " variables must be nonempty identifiers beginning with a letter or underscore, "
                        + "containing only letters, digits, underscore, dot or hyphen, and must not use virtual router metadata names.");
            }
            if (!keys.add(key)) {
                throw new InvalidParameterValueException(label + " variables must not contain duplicate keys.");
            }
        }
        return String.join(",", keys);
    }
}
