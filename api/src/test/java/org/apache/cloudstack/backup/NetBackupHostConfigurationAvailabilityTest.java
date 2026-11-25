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

package org.apache.cloudstack.backup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;

public class NetBackupHostConfigurationAvailabilityTest {
    @Test public void onlyAnExplicitAblestackNetBackupProviderEnablesKvmHostConfiguration() {
        for (String value : new String[] {null, "", "netbackup", "ablestack-veeam", "ablestack-nas", "other-ablestack-netbackup"}) {
            assertFalse(NetBackupHostConfigurationService.isAvailable(Host.Type.Routing, HypervisorType.KVM, value));
        }
        for (String value : new String[] {"ablestack-netbackup", "ablestack-nas, ablestack-netbackup", " ABLESTACK-NETBACKUP "}) {
            assertTrue(NetBackupHostConfigurationService.isAvailable(Host.Type.Routing, HypervisorType.KVM, value));
        }
    }
    @Test public void otherHypervisorsAndSystemVmHostsCannotRunHostConfiguration() {
        assertFalse(NetBackupHostConfigurationService.isAvailable(Host.Type.Routing, HypervisorType.VMware, "ablestack-netbackup"));
        assertFalse(NetBackupHostConfigurationService.isAvailable(Host.Type.ConsoleProxy, HypervisorType.KVM, "ablestack-netbackup"));
        assertFalse(NetBackupHostConfigurationService.isAvailable(Host.Type.Routing, null, "ablestack-netbackup"));
    }
}
