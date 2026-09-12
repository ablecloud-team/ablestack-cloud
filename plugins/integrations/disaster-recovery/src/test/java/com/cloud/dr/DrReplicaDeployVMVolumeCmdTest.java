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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import org.junit.Assert;
import org.junit.Test;

public class DrReplicaDeployVMVolumeCmdTest {
    @Test
    public void disabledAdaptersArePresentAndDownBeforeInitialBoot() {
        Map<String, String> details = new HashMap<>();
        details.put("dr.test.nic.disabled", "true");
        DrReplicaDeployVMVolumeCmd cmd = new DrReplicaDeployVMVolumeCmd(2L, "admin", 1L, 1L,
                1L, "test", "test", Arrays.asList(7L, 8L), 1L, HypervisorType.KVM, 123L, details);
        Assert.assertEquals(2, cmd.getNetworkIds().size());
        Assert.assertEquals(2, cmd.getIpToNetworkMap().size());
        Assert.assertFalse(cmd.getIpToNetworkMap().get(7L).getLinkState());
        Assert.assertFalse(cmd.getIpToNetworkMap().get(8L).getLinkState());
        Assert.assertFalse(cmd.getStartVm());
    }
}
