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

package com.cloud.dr;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class DrVmDetailReplicationPolicyTest {
    @Test
    public void kvmToKvmCopiesSourceVmDetailsAndRejectsOnlyTargetBoundState() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put("UEFI", "LEGACY");
        source.put("tpmversion", "NONE");
        source.put("io.policy", "io_uring");
        source.put("iothreads", "true");
        source.put("cpuNumber", "4");
        source.put("cpuSpeed", "2200");
        source.put("memory", "8192");
        source.put("clone.fast.status", "running");
        source.put("ftctl.enabled", "true");
        source.put("dr.plan.id", "99");
        source.put("volumeId", "77");
        source.put("deployvm", "true");
        source.put("boot.mode", "LEGACY");

        Map<String, String> copied = DrVmDetailReplicationPolicy.copyableSourceDetails(
                DrConstants.DIRECTION_KVM_TO_KVM, source);

        Assert.assertEquals("LEGACY", copied.get("UEFI"));
        Assert.assertEquals("NONE", copied.get("tpmversion"));
        Assert.assertEquals("io_uring", copied.get("io.policy"));
        Assert.assertEquals("true", copied.get("iothreads"));
        Assert.assertFalse(copied.containsKey("cpuNumber"));
        Assert.assertFalse(copied.containsKey("cpuSpeed"));
        Assert.assertFalse(copied.containsKey("memory"));
        Assert.assertFalse(copied.containsKey("clone.fast.status"));
        Assert.assertFalse(copied.containsKey("ftctl.enabled"));
        Assert.assertFalse(copied.containsKey("dr.plan.id"));
        Assert.assertFalse(copied.containsKey("volumeId"));
        Assert.assertFalse(copied.containsKey("deployvm"));
        Assert.assertFalse(copied.containsKey("boot.mode"));
    }

    @Test
    public void vmwarePathDoesNotAdoptKvmVmDetailsContract() {
        Map<String, String> source = new LinkedHashMap<String, String>();
        source.put("UEFI", "SECURE");

        Assert.assertTrue(DrVmDetailReplicationPolicy.copyableSourceDetails(
                DrConstants.DIRECTION_VMWARE_TO_KVM, source).isEmpty());
    }
}
