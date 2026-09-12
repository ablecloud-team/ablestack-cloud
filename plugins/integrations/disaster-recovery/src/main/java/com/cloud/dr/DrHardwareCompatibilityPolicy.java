/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information.
package com.cloud.dr;

import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.cloud.utils.exception.CloudRuntimeException;

/** Boot requirements are independent of target performance settings and snapshot identity. */
public final class DrHardwareCompatibilityPolicy {
    private DrHardwareCompatibilityPolicy() { }

    public static boolean bootDetail(String key) {
        return key != null && key.toLowerCase(Locale.ROOT).matches(
                "uefi|rootdiskcontroller|datadiskcontroller|bootorder|boot\\.order|tpmversion|tpmmodel|machinetype");
    }

    private static String normalized(String key, String value) {
        String result = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (key.equals("tpmversion") && (result.isEmpty() || result.equals("none"))) {
            return "none";
        }
        return result;
    }

    public static Map<String, String> bootDetails(Map<String, String> details) {
        Map<String, String> result = new TreeMap<>();
        if (details != null) {
            details.forEach((key, value) -> {
                if (bootDetail(key)) {
                    String name = key.toLowerCase(Locale.ROOT);
                    result.put(name, normalized(name, value));
                }
            });
        }
        result.putIfAbsent("tpmversion", "none");
        return result;
    }

    public static void verifyDetails(Map<String, String> source, Map<String, String> target) {
        Map<String, String> expected = bootDetails(source);
        Map<String, String> actual = bootDetails(target);
        // An absent UEFI key in a complete Cloud details snapshot means BIOS.
        expected.putIfAbsent("uefi", "");
        actual.putIfAbsent("uefi", "");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            if (!entry.getValue().equals(actual.get(entry.getKey()))) {
                throw new CloudRuntimeException("TARGET_BOOT_CONTRACT_MISMATCH: field=" + entry.getKey()
                        + " expected=" + entry.getValue() + " actual=" + actual.get(entry.getKey())
                        + "; verify target boot configuration before retry");
            }
        }
    }

    /** Profile evidence only; this does not claim the target runtime XML was inspected. */
    public static JsonObject bootSnapshot(JsonObject hardware) {
        JsonObject result = new JsonObject();
        for (String key : new String[] {"sourceVmRef", "firmware", "UEFI", "secureBoot",
                "rootDiskController", "dataDiskController"}) {
            if (hardware.has(key)) {
                JsonElement value = hardware.get(key);
                if (value.isJsonPrimitive() && !key.equals("sourceVmRef")) {
                    result.addProperty(key, value.getAsString().trim().toLowerCase(Locale.ROOT));
                } else {
                    result.add(key, value.deepCopy());
                }
            }
        }
        if (hardware.has("vmDetails") && hardware.get("vmDetails").isJsonObject()) {
            JsonObject details = new JsonObject();
            hardware.getAsJsonObject("vmDetails").entrySet().forEach(entry -> {
                if (bootDetail(entry.getKey())) {
                    String key = entry.getKey().toLowerCase(Locale.ROOT);
                    details.addProperty(key, normalized(key, entry.getValue().getAsString()));
                }
            });
            result.add("vmDetails", details);
        }
        return result;
    }
}
