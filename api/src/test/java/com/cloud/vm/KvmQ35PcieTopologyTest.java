// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.vm;

import java.util.Map;
import org.junit.Test;
import com.cloud.exception.InvalidParameterValueException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class KvmQ35PcieTopologyTest {
    private Map<String, String> policy(String value) { return Map.of(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY, value); }

    @Test public void absentOldRecordsKeepLegacyDefaults() {
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.resolve(null));
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.resolve(Map.of()));
    }
    @Test public void unrecordedImageTemplatesKeepLegacyForNewVms() {
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.forCreation(Map.of(), Map.of(), false));
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.forCreation(null, null, false, false));
    }
    @Test public void isoInstallationsReservePortsWhenNoPolicyIsRecorded() {
        assertEquals(KvmQ35PcieTopology.RESERVED_32, KvmQ35PcieTopology.forCreation(Map.of(), Map.of(), false, true));
        assertEquals(KvmQ35PcieTopology.RESERVED_32, KvmQ35PcieTopology.forCreation(null, null, false, true));
    }
    @Test public void imageTemplatesPreserveTheirRecordedReservedPorts() {
        assertEquals(KvmQ35PcieTopology.RESERVED_32, KvmQ35PcieTopology.forCreation(policy("reserved32"), Map.of(), false, false));
    }
    @Test public void unrecordedImageTemplatesCanExplicitlyRequestReservedPorts() {
        assertEquals(KvmQ35PcieTopology.RESERVED_32, KvmQ35PcieTopology.forCreation(Map.of(), policy("reserved32"), false, false));
    }
    @Test public void existingOsDisksDoNotInheritUnrelatedTemplateHardware() {
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.forCreation(policy("reserved32"), Map.of(), true));
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.forCreation(policy("reserved32"), Map.of(), true, true));
    }
    @Test public void capturedSourcePolicySurvivesNewVmCreation() {
        assertEquals(KvmQ35PcieTopology.RESERVED_32, KvmQ35PcieTopology.forCreation(Map.of(), policy("reserved32"), true));
    }
    @Test public void explicitLegacyOverridesNewVmAndTemplateDefaults() {
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.forCreation(policy("reserved32"), policy("legacy"), false));
    }
    @Test public void templatesCanSelectLegacyForNewVms() {
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.forCreation(policy("legacy"), Map.of(), false));
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.forCreation(policy("legacy"), Map.of(), false, true));
    }
    @Test public void explicitLegacyOverridesIsoDefaults() {
        assertEquals(KvmQ35PcieTopology.LEGACY, KvmQ35PcieTopology.forCreation(policy("reserved32"), policy("legacy"), false, true));
    }
    @Test public void invalidPoliciesFailInsteadOfChangingHardware() {
        assertThrows(InvalidParameterValueException.class, () -> KvmQ35PcieTopology.resolve(policy("automatic")));
    }
}

