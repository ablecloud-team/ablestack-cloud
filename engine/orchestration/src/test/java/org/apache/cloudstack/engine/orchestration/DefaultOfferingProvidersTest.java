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
package org.apache.cloudstack.engine.orchestration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;

public class DefaultOfferingProvidersTest {
    @Test
    public void vpcTemplateMutationDoesNotChangeStandaloneOrSharedTemplates() {
        Map<Service, Set<Provider>> shared = NetworkOrchestrator.defaultOfferingProviders(GuestType.Shared, false, false);
        Map<Service, Set<Provider>> isolated = NetworkOrchestrator.defaultOfferingProviders(GuestType.Isolated, false, false);
        Map<Service, Set<Provider>> nat = NetworkOrchestrator.defaultOfferingProviders(GuestType.Isolated, true, false);
        Map<Service, Set<Provider>> vpc = NetworkOrchestrator.defaultOfferingProviders(GuestType.Isolated, true, true);
        vpc.get(Service.Dhcp).clear();
        vpc.remove(Service.Lb);
        for (Map<Service, Set<Provider>> template : java.util.List.of(shared, isolated, nat)) {
            assertEquals(Set.of(Provider.VirtualRouter), template.get(Service.Dhcp));
        }
        assertTrue(isolated.containsKey(Service.Lb));
        assertTrue(nat.containsKey(Service.Lb));
        assertEquals(Set.of(Provider.VPCVirtualRouter), vpc.get(Service.Dns));
    }

    @Test
    public void sharedHasOnlyDhcpDnsAndUserData() {
        Map<Service, Set<Provider>> shared = NetworkOrchestrator.defaultOfferingProviders(GuestType.Shared, false, false);
        assertEquals(Set.of(Service.Dhcp, Service.Dns, Service.UserData), shared.keySet());
    }

    @Test
    public void noSourceNatOfferingUsesVirtualRouterWithoutVpcServices() {
        Map<Service, Set<Provider>> isolated = NetworkOrchestrator.defaultOfferingProviders(GuestType.Isolated, false, false);
        assertFalse(isolated.containsKey(Service.SourceNat));
        assertFalse(isolated.containsKey(Service.NetworkACL));
        assertEquals(Set.of(Provider.VirtualRouter), isolated.get(Service.Firewall));
        assertEquals(9, isolated.size());
    }

    @Test
    public void sourceNatAndVpcServiceContractsRemainSeparate() {
        Map<Service, Set<Provider>> nat = NetworkOrchestrator.defaultOfferingProviders(GuestType.Isolated, true, false);
        Map<Service, Set<Provider>> vpc = NetworkOrchestrator.defaultOfferingProviders(GuestType.Isolated, true, true);
        assertEquals(Set.of(Provider.VirtualRouter), nat.get(Service.SourceNat));
        assertFalse(nat.containsKey(Service.NetworkACL));
        assertEquals(Set.of(Provider.VPCVirtualRouter), vpc.get(Service.SourceNat));
        assertEquals(Set.of(Provider.VPCVirtualRouter), vpc.get(Service.NetworkACL));
    }
}
