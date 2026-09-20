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
package com.cloud.network.element;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.Test;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.storage.Volume;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfileImpl;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.storage.to.TemplateObjectTO;

public class ConfigDriveDiskProfileTest {
    private VirtualMachineProfileImpl profile() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getInstanceName()).thenReturn("i-2-5-VM");
        return new VirtualMachineProfileImpl(vm);
    }
    @Test public void secondaryNicDoesNotCreateConfigDriveDuringMigration() {
        com.cloud.vm.NicProfile nic = mock(com.cloud.vm.NicProfile.class);
        assertTrue(new ConfigDriveNetworkElement().prepareMigration(nic, null, profile(), null, null));
    }
    @Test public void replacesEmptySlotAndDeduplicatesWithoutLosingUserIso() throws Exception {
        VirtualMachineProfileImpl profile = profile();
        DiskTO userIso = new DiskTO(new TemplateObjectTO(), 3L, "template/user.iso", Volume.Type.ISO);
        profile.addDisk(userIso);
        profile.addDisk(new DiskTO(new TemplateObjectTO(), 4L, null, Volume.Type.ISO));
        ConfigDriveNetworkElement element = new ConfigDriveNetworkElement();
        DataStore store = mock(DataStore.class);
        element.addConfigDriveDisk(profile, store);
        element.addConfigDriveDisk(profile, store);
        assertEquals(2, profile.getDisks().size());
        assertSame(userIso, profile.getDisks().get(0));
        assertEquals("configdrive/i-2-5-VM.iso", profile.getDisks().get(1).getPath());
    }
    @Test public void occupiedSlotFailsWithoutMutatingProfile() throws Exception {
        VirtualMachineProfileImpl profile = profile();
        DiskTO userIso = new DiskTO(new TemplateObjectTO(), 4L, "template/user.iso", Volume.Type.ISO);
        profile.addDisk(userIso);
        try {
            new ConfigDriveNetworkElement().addConfigDriveDisk(profile, mock(DataStore.class));
            fail("A user ISO must never be replaced by ConfigDrive");
        } catch (ResourceUnavailableException expected) {
            assertSame(userIso, profile.getDisks().get(0));
        }
    }
}
