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
package com.cloud.configuration;
import java.util.Map;
import org.junit.Test;
import org.junit.Assert;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.exception.InvalidParameterValueException;

public class OfferingAllocationCloneTest {
    private Map<String, String> intent(String policy, String format) { return VolumeAllocationPolicy.request(policy, format, null); }
    @Test public void storedBytesAndRequestedGiBRemainDistinct() {
        Assert.assertEquals(Long.valueOf(1), OfferingAllocationClone.sizeGiB(null, 1073741824L));
        Assert.assertEquals(Long.valueOf(7), OfferingAllocationClone.sizeGiB(7L, 1073741824L));
        Assert.assertNull(OfferingAllocationClone.sizeGiB(null, 0L));
        Assert.assertEquals(Long.valueOf(0), OfferingAllocationClone.sizeGiB(0L, 0L));
        Assert.assertNull(OfferingAllocationClone.sizeGiB(null, null));
    }
    @Test(expected=InvalidParameterValueException.class) public void malformedStoredSizeIsNotRoundedDown() {
        OfferingAllocationClone.sizeGiB(null, 1073741825L);
    }
    @Test public void nameOnlyExplicitCloneKeepsOnlyRequestContract() {
        Map<String, String> source = new java.util.HashMap<>(intent("falloc", "qcow2"));
        source.put(VolumeAllocationPolicy.FIXED_POLICY, "full"); source.put(VolumeAllocationPolicy.OBSERVED_BYTES, "123");
        Map<String, String> result = OfferingAllocationClone.details(Map.of("custom", "keep"), source, Map.of(), Map.of("name", "clone"), false);
        Assert.assertEquals("falloc", result.get(VolumeAllocationPolicy.POLICY));
        Assert.assertEquals("keep", result.get("custom"));
        Assert.assertFalse(result.containsKey(VolumeAllocationPolicy.FIXED_POLICY));
        Assert.assertFalse(result.containsKey(VolumeAllocationPolicy.OBSERVED_BYTES));
        Assert.assertEquals("fat", OfferingAllocationClone.provisioning("thin", "fat", result, Map.of("name", "clone")));
    }
    @Test public void explicitCloneOverrideReplacesSourceIntent() {
        Map<String, String> result = OfferingAllocationClone.details(intent("full","raw"), intent("metadata","qcow2"), intent("full","raw"), Map.of("allocationpolicy","full"), false);
        Assert.assertEquals(intent("full","raw"), result);
    }
    @Test public void deliberateLegacyCloneDoesNotKeepExplicitRequest() {
        Map<String, String> result = OfferingAllocationClone.details(intent("full","raw"), intent("full","raw"), Map.of(), Map.of("provisioningtype","thin"), false);
        Assert.assertTrue(result.isEmpty());
        Assert.assertEquals("thin", OfferingAllocationClone.provisioning("thin","fat",result,Map.of("provisioningtype","thin")));
    }
    @Test public void omittedLegacyProvisioningKeepsFatInsteadOfGetterDefaultThin() {
        Assert.assertEquals("fat", OfferingAllocationClone.provisioning("thin","fat",Map.of(),Map.of("name","clone")));
    }
    @Test public void linkedOfferingOwnsIntent() {
        Assert.assertTrue(OfferingAllocationClone.details(intent("full","raw"),intent("full","raw"),Map.of(),Map.of(),true).isEmpty());
    }
    @Test(expected=InvalidParameterValueException.class) public void linkedOfferingRejectsCompetingExplicitRequest() {
        OfferingAllocationClone.details(Map.of(),Map.of(),intent("full","raw"),Map.of(),true);
    }
    @Test(expected=InvalidParameterValueException.class) public void malformedSourceCannotBecomeLegacySilently() {
        OfferingAllocationClone.details(Map.of(),Map.of(VolumeAllocationPolicy.FIXED_POLICY,"full"),Map.of(),Map.of(),false);
    }
}
