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

import com.cloud.exception.InvalidParameterValueException;

/** Keep a VM's PCIe policy stable across starts and restores. Missing legacy records never opt into new hardware. */
public enum KvmQ35PcieTopology {
    LEGACY("legacy"), RESERVED_32("reserved32");

    private final String value;

    KvmQ35PcieTopology(String value) { this.value = value; }

    public String value() { return value; }

    public static KvmQ35PcieTopology resolve(Map<String, String> details) {
        String value = details == null ? null : details.get(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY);
        if (value == null || value.isBlank()) { return LEGACY; }
        for (KvmQ35PcieTopology topology : values()) {
            if (topology.value.equalsIgnoreCase(value.trim())) { return topology; }
        }
        throw new InvalidParameterValueException("Invalid Q35 PCIe topology. Use legacy or reserved32.");
    }

    /** Image templates without a recorded policy retain the original automatic topology. */
    public static KvmQ35PcieTopology forCreation(Map<String, String> template, Map<String, String> requested, boolean existingDisks) {
        return forCreation(template, requested, existingDisks, false);
    }

    /** An ISO installation can opt into new hardware; cloning an existing OS image cannot imply compatibility. */
    public static KvmQ35PcieTopology forCreation(Map<String, String> template, Map<String, String> requested,
            boolean existingDisks, boolean installingFromIso) {
        if (requested != null && requested.containsKey(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY)) { return resolve(requested); }
        if (existingDisks) { return LEGACY; }
        if (template != null && template.containsKey(VmDetailConstants.KVM_Q35_PCIE_TOPOLOGY)) { return resolve(template); }
        return installingFromIso ? RESERVED_32 : LEGACY;
    }
}
