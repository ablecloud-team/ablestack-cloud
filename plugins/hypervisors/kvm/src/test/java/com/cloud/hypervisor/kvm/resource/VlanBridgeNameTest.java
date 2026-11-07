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
package com.cloud.hypervisor.kvm.resource;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

public class VlanBridgeNameTest {
    @Test
    public void preserveExistingNamesAndUseSameMappingInBothDrivers() throws Exception {
        Assert.assertEquals("brens1f0np0-631", VlanBridgeName.forVlan("ens1f0np0", "631"));
        Assert.assertEquals("breth0-4094", VlanBridgeName.forVlan("eth0", "4094"));
        Assert.assertEquals("brbond0-1000", VlanBridgeName.forVlan("bond0", "1000"));
        for (Object driver : new Object[] {new BridgeVifDriver(), new IvsVifDriver()}) {
            Method method = driver.getClass().getDeclaredMethod("generateVnetBrName", String.class, String.class);
            method.setAccessible(true);
            Assert.assertEquals(VlanBridgeName.forVlan("ens1f0np0", "3832"), method.invoke(driver, "ens1f0np0", "3832"));
        }
    }

    @Test
    public void allVlansAndLongParentsRemainDistinctValidAndStable() {
        Set<String> names = new HashSet<>();
        for (String pif : new String[] {"eth0", "bond0", "ens1f0np0", "bond12345678901", "ens123456789012"}) {
            for (int vid = 1; vid <= 4094; vid++) {
                String name = VlanBridgeName.forVlan(pif, String.valueOf(vid));
                Assert.assertTrue(name, name.length() <= 15 && name.matches("[a-zA-Z0-9_.-]+"));
                Assert.assertTrue(name, name.endsWith("-" + vid));
                Assert.assertEquals(name, VlanBridgeName.forVlan(pif, String.valueOf(vid)));
                Assert.assertTrue("Unexpected collision: " + name, names.add(name));
            }
        }
    }

    @Test
    public void persistenceLookupUsesTheLastSeparator() {
        LibvirtComputingResource resource = new LibvirtComputingResource();
        Assert.assertEquals("3832", resource.getVlanIdFromBridgeName("brlhfblgjc-3832"));
        Assert.assertEquals("1000", resource.getVlanIdFromBridgeName("brbond-x-1000"));
        Assert.assertNull(resource.getVlanIdFromBridgeName("cloud0"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingParentDoesNotBecomeBrNull() {
        VlanBridgeName.forVlan(null, "3832");
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidParentIsRejected() {
        VlanBridgeName.forVlan("eth0;touch x", "3832");
    }
}
