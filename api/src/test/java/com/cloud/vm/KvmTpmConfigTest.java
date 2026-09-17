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
package com.cloud.vm;

import java.util.Map;
import java.util.HashMap;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import com.cloud.exception.InvalidParameterValueException;

public class KvmTpmConfigTest {
    @Test public void disabledDoesNotRequireHostCapability() {
        for (Map<String, String> values : java.util.List.of(Map.<String, String>of(), Map.of("tpmversion", "NONE"), Map.of("tpmversion", ""))) {
            assertFalse(KvmTpmConfig.resolve(values, true).isEnabled());
            assertTrue(KvmTpmConfig.resolve(values, true).supportedBy(Map.of()));
        }
    }
    @Test public void legacyAndModernResolveToOneDevice() {
        Map<String, String> normalized = KvmTpmConfig.forCreation(Map.of(), Map.of("tpmversion", "V2_0"));
        assertEquals("tpm-tis", normalized.get(KvmTpmConfig.MODEL));
        assertEquals("2.0", normalized.get(KvmTpmConfig.VERSION));
        assertEquals(KvmTpmConfig.resolve(normalized, true), KvmTpmConfig.resolve(Map.of(KvmTpmConfig.MODEL, "tpm-tis"), true));
    }
    @Test public void existingAutomaticNoneDoesNotDisableModernDevice() {
        Map<String, String> mixed = Map.of("tpmversion", "NONE", KvmTpmConfig.MODEL, "tpm-crb");
        assertTrue(KvmTpmConfig.resolve(mixed, false).isEnabled());
        assertThrows(InvalidParameterValueException.class, () -> KvmTpmConfig.resolve(mixed, true));
    }
    @Test public void rejectsInvalidAndConflictingInputs() {
        for (Map<String, String> values : java.util.List.of(
                Map.of("tpmversion", "TPM"), Map.of("tpmversion", "3.0"),
                Map.of(KvmTpmConfig.VERSION, "2.0"),
                Map.of(KvmTpmConfig.MODEL, "tpm-crb", KvmTpmConfig.VERSION, "1.2"),
                Map.of(KvmTpmConfig.MODEL, "invalid"),
                Map.of("tpmversion", "1.2", KvmTpmConfig.MODEL, "tpm-tis", KvmTpmConfig.VERSION, "2.0"))) {
            assertThrows(InvalidParameterValueException.class, () -> KvmTpmConfig.resolve(values, true));
        }
    }
    @Test public void preservesTemplateAndRejectsOverride() {
        assertEquals("2.0", KvmTpmConfig.forCreation(Map.of("tpmversion", "V2_0"), Map.of()).get(KvmTpmConfig.VERSION));
        assertThrows(InvalidParameterValueException.class, () -> KvmTpmConfig.forCreation(Map.of("tpmversion", "V2_0"), Map.of("tpmversion", "NONE")));
    }
    @Test public void validatesActualHostVersionAndModel() {
        KvmTpmConfig tpm = KvmTpmConfig.resolve(Map.of("tpmversion", "1.2"), true);
        Map<String, String> host = new HashMap<>(Map.of("host.tpm.enable", "true", KvmTpmConfig.HOST_MODELS, "tpm-tis,tpm-crb",
                KvmTpmConfig.HOST_VERSIONS, "2.0"));
        assertFalse(tpm.supportedBy(host));
        host.put(KvmTpmConfig.HOST_VERSIONS, "1.2,2.0");
        assertTrue(tpm.supportedBy(host));
        host.put("host.tpm.enable", "false");
        assertFalse(tpm.supportedBy(host));
    }
    @Test public void protectsExistingIdentity() {
        Map<String, String> old = Map.of(KvmTpmConfig.MODEL, "tpm-tis", KvmTpmConfig.VERSION, "2.0");
        KvmTpmConfig.validateUpdate(old, Map.of("keyboard", "us"), false);
        KvmTpmConfig.validateUpdate(old, old, false);
        assertThrows(InvalidParameterValueException.class, () -> KvmTpmConfig.validateUpdate(old, Map.of(), true));
        assertThrows(InvalidParameterValueException.class, () -> KvmTpmConfig.validateUpdate(old, Map.of(KvmTpmConfig.MODEL, "tpm-crb"), false));
        assertThrows(InvalidParameterValueException.class, () -> KvmTpmConfig.validateUpdate(Map.of("tpmversion", "2.0"), old, false));
    }
}
