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
package org.apache.cloudstack.api.command.user.vm;

import java.util.Map;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.vm.VmDetailConstants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CreateVMFromBackupCmdTest {
    @Test
    public void testExplicitFalseIsPreservedOnlyForBackupCreation() {
        CreateVMFromBackupCmd backup = new CreateVMFromBackupCmd();
        DeployVMCmd deploy = new DeployVMCmd();
        for (BaseDeployVMCmd cmd : new BaseDeployVMCmd[] {backup, deploy}) {
            ReflectionTestUtils.setField(cmd, "iothreadsEnabled", false);
            ReflectionTestUtils.setField(cmd, "nicPackedVirtQueues", false);
        }
        assertEquals("false", backup.getDetails().get(VmDetailConstants.IOTHREADS));
        assertEquals("false", backup.getDetails().get(VmDetailConstants.NIC_PACKED_VIRTQUEUES_ENABLED));
        assertFalse(deploy.getDetails().containsKey(VmDetailConstants.IOTHREADS));
        assertFalse(deploy.getDetails().containsKey(VmDetailConstants.NIC_PACKED_VIRTQUEUES_ENABLED));
    }

    @Test
    public void testUnspecifiedFlagsRemainAbsent() {
        Map<String, String> details = new CreateVMFromBackupCmd().getDetails();
        assertFalse(details.containsKey(VmDetailConstants.IOTHREADS));
        assertFalse(details.containsKey(VmDetailConstants.NIC_PACKED_VIRTQUEUES_ENABLED));
    }

    @Test
    public void testRootDiskDefaultsSurviveRepeatedReadsAndExplicitValuesWin() {
        CreateVMFromBackupCmd cmd = new CreateVMFromBackupCmd();
        cmd.setBackupRootDiskDetails(Map.of(VmDetailConstants.ROOT_DISK_SIZE, "100"));
        assertEquals("100", cmd.getDetails().get(VmDetailConstants.ROOT_DISK_SIZE));
        assertEquals("100", cmd.getDetails().get(VmDetailConstants.ROOT_DISK_SIZE));
        ReflectionTestUtils.setField(cmd, "rootdisksize", 200L);
        assertEquals("200", cmd.getDetails().get(VmDetailConstants.ROOT_DISK_SIZE));
    }
}
