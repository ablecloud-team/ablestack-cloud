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

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class VmProcessToolsIsoCatalogTest {
    private static final String ZONE = "11111111-1111-4111-8111-111111111111";
    private static final String ROCKY = "22222222-2222-4222-8222-222222222222";
    private static final String UBUNTU = "33333333-3333-4333-8333-333333333333";
    private static final String DEBIAN = "44444444-4444-4444-8444-444444444444";
    private static final String WINDOWS = "55555555-5555-4555-8555-555555555555";
    private static final String OTHER_ROCKY = "66666666-6666-4666-8666-666666666666";
    private static final String CHECKSUM = "b".repeat(128);
    private static final String ALL = "[\"" + ROCKY + "\",\"" + UBUNTU + "\",\"" + DEBIAN + "\",\"" + WINDOWS + "\"]";

    private static VmProcessToolsIsoCatalog.IsoMetadata iso(String name, boolean inZone) {
        return new VmProcessToolsIsoCatalog.IsoMetadata(name, "x86_64", CHECKSUM, true, true, false, inZone);
    }

    private static Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media() {
        Map<String, VmProcessToolsIsoCatalog.IsoMetadata> entries = new HashMap<>();
        entries.put(ROCKY, iso("ABLESTACK-Tools-Process-rocky-89806b1", true));
        entries.put(UBUNTU, iso("ABLESTACK-Tools-ubuntu-process-9-89806b1.iso", true));
        entries.put(DEBIAN, iso("ABLESTACK-Tools-Process-debian-89806b1", true));
        entries.put(WINDOWS, iso("ABLESTACK-Tools-Process-windows-89806b1", true));
        return entries;
    }

    private static Map<String, String> os(String id, String version, String product) {
        return Map.of("family", "mswindows".equals(id) ? "windows" : "linux", "id", id,
                "version", version, "productType", product, "arch", "x86_64");
    }

    private static Map<String, String> resolve(String catalog, String id, String version, String product,
            String registered, Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media) {
        return VmProcessToolsIsoCatalog.resolve(catalog, ZONE, os(id, version, product), registered, media::get);
    }

    private static String status(String catalog, String id, String version, String product, String registered,
            Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media) {
        return resolve(catalog, id, version, product, registered, media).get("status");
    }

    @Test public void uuidOnlyCatalogUsesCloudIsoFamilyForAllSupportedVersions() {
        Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media = media();
        for (String version : new String[] {"8.10", "9.7", "9.8", "10.2"}) {
            assertEquals(ROCKY, resolve(ALL, "rocky", version, "none",
                    "Rocky Linux " + version.split("\\.")[0], media).get("isoId"));
        }
        assertEquals(UBUNTU, resolve(ALL, "ubuntu", "26.04", "none", "Ubuntu 26.04 LTS", media).get("isoId"));
        assertEquals(DEBIAN, resolve(ALL, "debian", "13", "none", "Debian GNU/Linux 13 (64-bit)", media).get("isoId"));
        assertEquals(WINDOWS, resolve(ALL, "mswindows", "11", "client", "Windows 11 (64-bit)", media).get("isoId"));
        assertEquals(WINDOWS, resolve(ALL, "mswindows", "2025", "server", "Windows Server 2025 (64-bit)", media).get("isoId"));
        assertEquals(ROCKY, resolve(ALL, "rhel", "9.6", "none", "Red Hat Enterprise Linux 9", media).get("isoId"));
        assertEquals(CHECKSUM, resolve(ALL, "rocky", "9.8", "none", "Rocky Linux 9", media).get("checksum"));
        assertEquals("ABLESTACK-Tools-Process-rocky-89806b1",
                resolve(ALL, "rocky", "9.8", "none", "Rocky Linux 9", media).get("name"));
    }

    @Test public void unknownStaleAndUnsupportedGuestsDoNotRecommend() {
        Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media = media();
        assertEquals("NOT_CONFIGURED", status("[]", "rocky", "9.8", "none", "Rocky Linux 9", media));
        assertEquals("OS_MISMATCH", status(ALL, "rocky", "9.8", "none", "Rocky Linux 8", media));
        assertEquals("OS_MISMATCH", status(ALL, "rocky", "9.8", "none", null, media));
        assertEquals("UNSUPPORTED_OS", status(ALL, "rocky", "11.1", "none", "Rocky Linux 11", media));
        assertEquals("UNSUPPORTED_OS", status(ALL, "debian", "11", "none", "Debian GNU/Linux 11", media));
        assertEquals("OS_UNKNOWN", VmProcessToolsIsoCatalog.resolve(ALL, ZONE,
                Map.of("family", "unknown", "id", "unknown", "version", "unknown", "arch", "unsupported"),
                "Windows 11", media::get).get("status"));
    }

    @Test public void unreachableQgaCanOfferExplicitRegisteredOsInstallationHint() {
        Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media = media();
        Map<String, String> selected = VmProcessToolsIsoCatalog.resolveForInstallation(ALL, ZONE, Map.of(),
                "Windows Server 2022", "x86_64", "QGA_UNREACHABLE", media::get);
        assertEquals(WINDOWS, selected.get("isoId"));
        assertEquals("REGISTERED_OS", selected.get("selectionSource"));
        assertEquals("Windows Server 2022", selected.get("registeredOsName"));
        assertEquals(UBUNTU, VmProcessToolsIsoCatalog.resolveForInstallation(ALL, ZONE, null,
                "Ubuntu 24.04", "x86_64", "QGA_UNREACHABLE", media::get).get("isoId"));
    }

    @Test public void installationHintNeverMasksObservedMismatchOrHostFailure() {
        Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media = media();
        assertEquals("OS_MISMATCH", VmProcessToolsIsoCatalog.resolveForInstallation(ALL, ZONE,
                os("ubuntu", "22.04", "none"), "Ubuntu 24.04", "x86_64", "QGA_UNREACHABLE", media::get).get("status"));
        assertEquals("OS_UNKNOWN", VmProcessToolsIsoCatalog.resolveForInstallation(ALL, ZONE, null,
                "Ubuntu 24.04", "x86_64", "CHECK_FAILED", media::get).get("status"));
        assertEquals("OS_UNKNOWN", VmProcessToolsIsoCatalog.resolveForInstallation(ALL, ZONE, null,
                "Ubuntu 24.04", "aarch64", "QGA_UNREACHABLE", media::get).get("status"));
        assertEquals("UNSUPPORTED_OS", VmProcessToolsIsoCatalog.resolveForInstallation(ALL, ZONE, null,
                "Debian 11", "x86_64", "QGA_UNREACHABLE", media::get).get("status"));
        assertEquals("OS_UNKNOWN", VmProcessToolsIsoCatalog.resolveForInstallation(ALL, ZONE, null,
                "Other Linux", "x86_64", "QGA_UNREACHABLE", media::get).get("status"));
    }

    @Test public void onlyIsosInTheVmZoneCanMatch() {
        Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media = media();
        media.put(ROCKY, iso("ABLESTACK-Tools-Process-rocky-89806b1", false));
        assertEquals("NO_MATCH", status(ALL, "rocky", "9.8", "none", "Rocky Linux 9", media));
        media.put(OTHER_ROCKY, iso("ABLESTACK-Tools-Process-rocky-89806b1", true));
        assertEquals(OTHER_ROCKY, resolve(ALL.substring(0, ALL.length() - 1) + ",\"" + OTHER_ROCKY + "\"]",
                "rocky", "9.8", "none", "Rocky Linux 9", media).get("isoId"));
    }

    @Test public void legacyEntriesMigrateByIsoUuid() {
        Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media = media();
        String old = "[{\"isoId\":\"" + ROCKY + "\",\"osVersion\":\"9.8\"},"
                + "{\"isoId\":\"" + ROCKY + "\",\"osVersion\":\"10.2\"}]";
        assertEquals(ROCKY, resolve(old, "rocky", "8.10", "none", "Rocky Linux 8", media).get("isoId"));
        assertEquals("INVALID_CONFIG", status("[{\"isoId\":\"" + ROCKY + "\"},\"" + UBUNTU + "\"]",
                "rocky", "9.8", "none", "Rocky Linux 9", media));
    }

    @Test public void invalidOrAmbiguousCloudMediaFailsClosed() {
        Map<String, VmProcessToolsIsoCatalog.IsoMetadata> media = media();
        assertEquals("INVALID_CONFIG", status("bad", "rocky", "9.8", "none", "Rocky Linux 9", media));
        assertEquals("INVALID_CONFIG", status("[{}]", "rocky", "9.8", "none", "Rocky Linux 9", media));
        assertEquals("INVALID_CONFIG", status("[\"" + ROCKY + "\",\"" + ROCKY + "\"]",
                "rocky", "9.8", "none", "Rocky Linux 9", media));
        assertEquals("INVALID_CONFIG", status("[\"not-a-uuid\"]", "rocky", "9.8", "none", "Rocky Linux 9", media));
        media.put(OTHER_ROCKY, iso("ABLESTACK-Tools-Process-rocky-89806b1", true));
        assertEquals("INVALID_CONFIG", status(ALL.substring(0, ALL.length() - 1) + ",\"" + OTHER_ROCKY + "\"]",
                "rocky", "9.8", "none", "Rocky Linux 9", media));
        media.put(ROCKY, iso("Untrusted ISO", true));
        assertEquals("INVALID_CONFIG", status(ALL, "rocky", "9.8", "none", "Rocky Linux 9", media));
        media.put(ROCKY, new VmProcessToolsIsoCatalog.IsoMetadata(
                "ABLESTACK-Tools-Process-rocky-89806b1", "x86_64", null, true, true, true, true));
        assertEquals("INVALID_CONFIG", status(ALL, "rocky", "9.8", "none", "Rocky Linux 9", media));
        media.put(ROCKY, new VmProcessToolsIsoCatalog.IsoMetadata(
                "ABLESTACK-Tools-Process-rocky-89806b1", "aarch64", null, true, true, false, true));
        assertEquals("INVALID_CONFIG", status(ALL, "rocky", "9.8", "none", "Rocky Linux 9", media));
    }
}
