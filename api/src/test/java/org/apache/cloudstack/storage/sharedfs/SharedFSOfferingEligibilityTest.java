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
package org.apache.cloudstack.storage.sharedfs;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.ServiceOffering;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.apache.cloudstack.api.command.user.storage.sharedfs.CreateSharedFSCmd;

public class SharedFSOfferingEligibilityTest {
    private ServiceOffering offering(Integer cpu, Integer ram, Integer speed) {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getCpu()).thenReturn(cpu);
        when(offering.getRamSize()).thenReturn(ram);
        when(offering.getSpeed()).thenReturn(speed);
        when(offering.isOfferHA()).thenReturn(true);
        return offering;
    }

    private SharedFSOfferingEligibility eligibility(ServiceOffering offering, Map<String, String> details) {
        return new SharedFSOfferingEligibility(offering, details, 2, 1024, 0, 0);
    }

    @Test
    public void fixedRequestPreservesCompatibilityAndUnlimitedSpeed() {
        SharedFSOfferingEligibility fixed = eligibility(offering(2, 1024, 0), Collections.emptyMap());
        assertTrue(fixed.isSelectable());
        assertEquals("fixed", fixed.getMode());
        assertFalse(fixed.isInputRequired());
        assertTrue(fixed.validateCustomValues(Collections.emptyMap()).isEmpty());
    }

    @Test
    public void freeCatalogSelectableBeforeInputButRequestNeedsAllMissingFields() {
        ServiceOffering free = offering(null, null, null);
        SharedFSOfferingEligibility catalog = eligibility(free, Collections.emptyMap());
        assertTrue(catalog.isSelectable());
        assertEquals("free", catalog.getMode());
        assertTrue(catalog.isInputRequired());
        assertEquals(2, catalog.getConstraints().get("cpunumber").getMin());
        assertEquals(1024, catalog.getConstraints().get("memory").getMin());
        assertThrows(InvalidParameterValueException.class, () -> catalog.validateCustomValues(Collections.emptyMap()));
        Map<String, String> values = Map.of("cpuNumber", "4", "cpuSpeed", "2000", "memory", "8192");
        assertEquals(values, eligibility(free, Collections.emptyMap()).validateCustomValues(values));
    }

    @Test
    public void boundedAndPartialValuesOnlyForwardCustomFields() {
        ServiceOffering bounded = offering(null, null, 2000);
        Map<String, String> limits = Map.of("mincpunumber", "1", "maxcpunumber", "8",
                "minmemory", "32", "maxmemory", "16384");
        SharedFSOfferingEligibility catalog = eligibility(bounded, limits);
        assertEquals("ranged", catalog.getMode());
        assertEquals(2, catalog.getConstraints().get("cpunumber").getMin());
        assertEquals(1024, catalog.getConstraints().get("memory").getMin());
        assertEquals(Map.of("cpuNumber", "8", "memory", "16384"),
                catalog.validateCustomValues(Map.of("cpuNumber", "8", "memory", "16384")));
        SharedFSOfferingEligibility partial = eligibility(offering(4, null, 2000), Collections.emptyMap());
        assertEquals(Map.of("memory", "4096"), partial.validateCustomValues(Map.of("memory", "4096")));
    }

    @Test
    public void rejectsBoundsFractionsMissingFieldsAndFixedOverrides() {
        Map<String, String> limits = Map.of("mincpunumber", "2", "maxcpunumber", "8",
                "minmemory", "2048", "maxmemory", "16384");
        for (Map<String, String> values : new Map[] {
            Map.of("cpuNumber", "9", "memory", "2048"), Map.of("cpuNumber", "2", "memory", "16385"),
            Map.of("cpuNumber", "2.5", "memory", "2048"), Map.of("cpuNumber", "2"),
            Map.of("cpuNumber", "2", "memory", "2048", "cpuSpeed", "2000"),
            Map.of("cpuNumber", "2", "memory", "2048", "untrusted", "1")}) {
            assertThrows(InvalidParameterValueException.class,
                    () -> eligibility(offering(null, null, 2000), limits).validateCustomValues(values));
        }
    }

    @Test
    public void invalidOrEmptyIntersectionNeverBecomesUnlimited() {
        for (Map<String, String> limits : new Map[] {
            Map.of("maxcpunumber", "1"), Map.of("mincpunumber", "8", "maxcpunumber", "2"),
            Map.of("maxmemory", "not-a-number"), Map.of("maxmemory", "2147483648"),
            Map.of("minmemory", "-1")}) {
            assertFalse(eligibility(offering(null, null, 2000), limits).isSelectable());
        }
        assertFalse(new SharedFSOfferingEligibility(offering(null, null, 2000), Collections.emptyMap(),
                2, 1024, 1, 512).isSelectable());
    }

    @Test
    public void keepsHaAndGlobalCapsButDoesNotRequireDynamicScaling() {
        ServiceOffering fixed = offering(4, 8192, 2000);
        assertFalse(new SharedFSOfferingEligibility(fixed, Collections.emptyMap(), 2, 1024, 2, 4096).isSelectable());
        when(fixed.isOfferHA()).thenReturn(false);
        assertFalse(eligibility(fixed, Collections.emptyMap()).isSelectable());
        assertTrue(eligibility(offering(2, 1024, 2000), Collections.emptyMap()).isSelectable());
    }

    @Test
    public void structuredReasonsAndCatalogFieldsAreSerialized() {
        SharedFSOfferingEligibility result = eligibility(offering(null, null, 2000), Collections.emptyMap());
        result.reject("ROOT_POLICY_THIN", "serviceofferingid");
        InvalidParameterValueException failure = assertThrows(InvalidParameterValueException.class, result::requireSelectable);
        assertTrue(failure.getMessage().startsWith(SharedFSOfferingEligibility.ERROR_PREFIX));
        assertEquals("ROOT_POLICY_THIN", JsonParser.parseString(failure.getMessage()
                .substring(SharedFSOfferingEligibility.ERROR_PREFIX.length())).getAsJsonArray().get(0)
                .getAsJsonObject().get("code").getAsString());
        String json = new Gson().toJson(result);
        assertTrue(json.contains("\"inputrequired\":true"));
        assertTrue(json.contains("\"custom\":true"));
        assertTrue(json.contains("\"dynamicscalingrequired\":false"));
    }

    @Test
    public void architectureAliasesAreLocalAndUnknownIsNotDefaulted() {
        for (String value : new String[] {"amd64", "x86_64", "x86-64", " AMD64 "}) {
            assertEquals("x86_64", SharedFSOfferingEligibility.normalizeArchitecture(value));
        }
        assertEquals("aarch64", SharedFSOfferingEligibility.normalizeArchitecture("arm64"));
        assertNull(SharedFSOfferingEligibility.normalizeArchitecture("unknown"));
        assertNull(SharedFSOfferingEligibility.normalizeArchitecture(null));
    }

    @Test
    public void createCommandOnlyExposesThreeWhitelistedComputeKeys() throws Exception {
        CreateSharedFSCmd command = new CreateSharedFSCmd();
        assertTrue(command.getCustomComputeParameters().isEmpty());
        for (String field : new String[] {"cpuNumber", "cpuSpeed", "memory"}) {
            Field value = CreateSharedFSCmd.class.getDeclaredField(field);
            value.setAccessible(true);
            value.set(command, 4);
        }
        assertEquals(Map.of("cpuNumber", "4", "cpuSpeed", "4", "memory", "4"),
                command.getCustomComputeParameters());
    }
}
