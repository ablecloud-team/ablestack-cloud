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

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

import com.google.gson.JsonParser;

public class LibvirtFtctlDrCapabilitiesCommandWrapperTest {

    @Test
    public void parsesReprotectAuthorityContractsFromRuntimeCapabilities() {
        Assert.assertEquals(Arrays.asList("2026-07-23", "2026-08-26"),
                LibvirtFtctlDrCapabilitiesCommandWrapper.reprotectAuthorityContractVersions(
                        JsonParser.parseString("{\"reprotect_authority_contract_versions\":"
                                + "[\"2026-07-23\",\"2026-08-26\"]}").getAsJsonObject()));
    }

    @Test
    public void missingReprotectAuthorityContractsRemainEmpty() {
        Assert.assertTrue(LibvirtFtctlDrCapabilitiesCommandWrapper.reprotectAuthorityContractVersions(
                JsonParser.parseString("{}").getAsJsonObject()).isEmpty());
    }
}
