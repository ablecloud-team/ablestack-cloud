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
package com.cloud.vm.process;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Selects a registered Tools ISO by UUID and its Cloud ISO metadata. */
public final class VmProcessToolsIsoCatalog {
    private VmProcessToolsIsoCatalog() { }

    private static final Pattern REGISTERED_OS = Pattern.compile(
            "^(Rocky Linux|Red Hat Enterprise Linux(?: Server)?|Ubuntu|Debian(?: GNU/Linux)?|Windows(?: Server)?)\\s+(8|9|10|11|12|13|2019|2022|2025|22\\.04|24\\.04|26\\.04)(?=\\D|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOUD_ISO_NAME = Pattern.compile(
            "^ABLESTACK-Tools-Process-(rocky|ubuntu|debian|windows)-[0-9a-f]{7,40}$");
    private static final Pattern ARTIFACT_ISO_NAME = Pattern.compile(
            "^ABLESTACK-Tools-(rocky|ubuntu|debian|windows)-process-[0-9]+-[0-9a-f]{7,40}(?:\\.iso)?$");

    public static final class IsoMetadata {
        public final String name;
        public final String arch;
        public final String checksum;
        public final boolean iso;
        public final boolean active;
        public final boolean bootable;
        public final boolean inZone;

        public IsoMetadata(String name, String arch, String checksum, boolean iso, boolean active, boolean bootable, boolean inZone) {
            this.name = name;
            this.arch = arch;
            this.checksum = checksum;
            this.iso = iso;
            this.active = active;
            this.bootable = bootable;
            this.inZone = inZone;
        }
    }

    private static String canonicalUuid(String value) {
        if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Noncanonical UUID");
        return value;
    }

    private static String major(String version) {
        return version.split("\\.", 2)[0];
    }

    private static String familyFor(String id) {
        if ("rocky".equals(id) || "rhel".equals(id)) return "rocky";
        if ("ubuntu".equals(id)) return "ubuntu";
        if ("debian".equals(id)) return "debian";
        if ("mswindows".equals(id)) return "windows";
        return null;
    }

    private static boolean supported(String id, String product, String version) {
        if (id == null || product == null || version == null) return false;
        if ("rocky".equals(id) || "rhel".equals(id))
            return "none".equals(product) && version.matches("(8|9|10)(\\.[0-9]+)*");
        if ("debian".equals(id))
            return "none".equals(product) && version.matches("(12|13)(\\.[0-9]+)*");
        if ("ubuntu".equals(id))
            return "none".equals(product) && Set.of("22.04", "24.04", "26.04").contains(version);
        if ("mswindows".equals(id))
            return "client".equals(product) && "11".equals(version)
                    || "server".equals(product) && Set.of("2019", "2022", "2025").contains(version);
        return false;
    }

    private static boolean registeredMatches(String name, String id, String product, String version) {
        if (name == null) return false;
        Matcher matcher = REGISTERED_OS.matcher(name);
        if (!matcher.find()) return false;
        String label = matcher.group(1).toLowerCase(Locale.ROOT);
        String registeredId = label.startsWith("rocky") ? "rocky"
                : label.startsWith("red hat") ? "rhel"
                : label.startsWith("ubuntu") ? "ubuntu"
                : label.startsWith("debian") ? "debian" : "mswindows";
        String registeredProduct = "mswindows".equals(registeredId)
                ? label.contains("server") ? "server" : "client" : "none";
        String registeredVersion = matcher.group(2);
        return registeredId.equals(id) && registeredProduct.equals(product)
                && (Set.of("rocky", "rhel", "debian").contains(id)
                        ? registeredVersion.equals(major(version)) : registeredVersion.equals(version));
    }

    private static String isoFamily(String name) {
        if (name == null) return null;
        Matcher cloud = CLOUD_ISO_NAME.matcher(name);
        if (cloud.matches()) return cloud.group(1);
        Matcher artifact = ARTIFACT_ISO_NAME.matcher(name);
        return artifact.matches() ? artifact.group(1) : null;
    }

    /** Installation hint only: never changes the observed capability or grants process actions. */
    public static Map<String, String> resolveForInstallation(String raw, String zoneId, Map<?, ?> os,
            String registeredOsName, String templateArch, String readiness, Function<String, IsoMetadata> isoLookup) {
        Map<String, String> observed = resolve(raw, zoneId, os, registeredOsName, isoLookup);
        if (!"OS_UNKNOWN".equals(observed.get("status")) || !"QGA_UNREACHABLE".equals(readiness)
                || registeredOsName == null || !"x86_64".equals(templateArch)) return observed;
        Matcher registered = REGISTERED_OS.matcher(registeredOsName);
        if (!registered.find()) return observed;
        String label = registered.group(1).toLowerCase(Locale.ROOT);
        String id = label.startsWith("rocky") ? "rocky" : label.startsWith("red hat") ? "rhel"
                : label.startsWith("ubuntu") ? "ubuntu" : label.startsWith("debian") ? "debian" : "mswindows";
        Map<String, String> declared = Map.of("id", id, "version", registered.group(2), "arch", templateArch,
                "family", "mswindows".equals(id) ? "windows" : "linux",
                "productType", "mswindows".equals(id) ? label.contains("server") ? "server" : "client" : "none");
        Map<String, String> selected = new HashMap<>(resolve(raw, zoneId, declared, registeredOsName, isoLookup));
        if ("MATCHED".equals(selected.get("status"))) {
            selected.put("selectionSource", "REGISTERED_OS");
            selected.put("registeredOsName", registeredOsName);
        }
        return Map.copyOf(selected);
    }

    public static Map<String, String> resolve(String raw, String zoneId, Map<?, ?> os, String registeredOsName,
            Function<String, IsoMetadata> isoLookup) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) return Map.of("status", "NOT_CONFIGURED");
        if (zoneId == null || os == null) return Map.of("status", "OS_UNKNOWN");
        String id = os.get("id") instanceof String ? ((String) os.get("id")).toLowerCase(Locale.ROOT) : null;
        String version = os.get("version") instanceof String ? (String) os.get("version") : null;
        String arch = os.get("arch") instanceof String ? (String) os.get("arch") : null;
        String product = "mswindows".equals(id) && os.get("productType") instanceof String
                ? (String) os.get("productType") : "mswindows".equals(id) ? null : "none";
        String expectedFamily = "mswindows".equals(id) ? "windows" : "linux";
        if (id == null || version == null || "unknown".equals(version) || arch == null
                || !expectedFamily.equals(os.get("family")) || product == null)
            return Map.of("status", "OS_UNKNOWN");
        if (!"x86_64".equals(arch) || !supported(id, product, version))
            return Map.of("status", "UNSUPPORTED_OS");
        if (!registeredMatches(registeredOsName, id, product, version))
            return Map.of("status", "OS_MISMATCH");
        try {
            if (raw.length() > 65536) throw new IllegalArgumentException("Catalog too large");
            JsonArray ids = JsonParser.parseString(raw).getAsJsonArray();
            if (ids.size() > 128) throw new IllegalArgumentException("Too many catalog entries");
            boolean legacy = ids.size() > 0 && ids.get(0).isJsonObject();
            Map<String, String> match = null;
            Set<String> seenIds = new HashSet<>();
            Set<String> zoneFamilies = new HashSet<>();
            for (JsonElement element : ids) {
                String isoId;
                if (legacy) {
                    if (!element.isJsonObject()) throw new IllegalArgumentException("Mixed catalog formats");
                    JsonObject old = element.getAsJsonObject();
                    if (!old.has("isoId") || !old.get("isoId").isJsonPrimitive()
                            || !old.get("isoId").getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("Legacy catalog ISO UUID missing");
                    isoId = canonicalUuid(old.get("isoId").getAsString());
                    if (!seenIds.add(isoId)) continue;
                } else {
                    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("Catalog entries must be ISO UUIDs");
                    isoId = canonicalUuid(element.getAsString());
                    if (!seenIds.add(isoId)) throw new IllegalArgumentException("Duplicate ISO UUID");
                }
                IsoMetadata iso = isoLookup.apply(isoId);
                String isoFamily = iso == null ? null : isoFamily(iso.name);
                if (isoFamily == null || !iso.iso || !iso.active || iso.bootable || !"x86_64".equals(iso.arch))
                    throw new IllegalArgumentException("Invalid Tools ISO metadata");
                if (!iso.inZone) continue;
                if (!zoneFamilies.add(isoFamily)) throw new IllegalArgumentException("Ambiguous Tools ISO family in zone");
                if (!isoFamily.equals(familyFor(id))) continue;
                Map<String, String> selected = new HashMap<>(Map.of(
                        "status", "MATCHED", "isoId", isoId, "name", iso.name,
                        "zoneId", zoneId, "arch", iso.arch, "isoFamily", isoFamily));
                if (iso.checksum != null && !iso.checksum.isBlank()) selected.put("checksum", iso.checksum);
                match = Map.copyOf(selected);
            }
            return match == null ? Map.of("status", "NO_MATCH") : match;
        } catch (RuntimeException ex) {
            return Map.of("status", "INVALID_CONFIG");
        }
    }
}
