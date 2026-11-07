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

import com.cloud.dc.VlanVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;

public class GuestNetworkIpRangesTest {
    private final ConfigurationManagerImpl manager = new ConfigurationManagerImpl();
    private final NetworkVO network = mock(NetworkVO.class);
    private final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);

    @Before
    public void setUp() {
        manager._networkOfferingDao = mock(NetworkOfferingDao.class);
        manager._ntwkOffServiceMapDao = mock(NetworkOfferingServiceMapDao.class);
        manager._networkDao = mock(NetworkDao.class);
        manager._vlanDao = mock(VlanDao.class);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(network.getSpecifyIpRanges()).thenReturn(true);
        when(network.getVpcId()).thenReturn(null);
        when(network.getNetworkOfferingId()).thenReturn(10L);
        when(network.getGateway()).thenReturn("172.26.31.1");
        when(network.getCidr()).thenReturn("172.26.31.0/24");
        when(manager._networkOfferingDao.findById(10L)).thenReturn(offering);
    }

    @Test
    public void overlapCheckSkipsLegacyIpv6OnlyRowsWithInheritedIpv4Gateway() {
        final DataCenterVO zone = mock(DataCenterVO.class);
        final VlanVO range = mock(VlanVO.class);
        when(zone.getId()).thenReturn(1L);
        when(range.getVlanGateway()).thenReturn("172.27.31.1");
        when(range.getVlanNetmask()).thenReturn("255.255.255.0");
        when(range.getNetworkId()).thenReturn(10L);
        when(manager._vlanDao.listByZone(1L)).thenReturn(Collections.singletonList(range));
        ReflectionTestUtils.invokeMethod(manager, "checkZoneVlanIpOverlap", zone, network, "172.27.31.0/24", "651",
                "172.27.31.1", "255.255.255.0", "172.27.31.30", "172.27.31.34");
    }

    @Test
    public void allowsStandaloneIsolatedWithoutSourceNat() {
        assertTrue(manager.supportsIsolatedGuestIpRanges(network));
    }

    @Test
    public void requiresExplicitRangeSupportAndOffering() {
        when(network.getSpecifyIpRanges()).thenReturn(false);
        assertFalse(manager.supportsIsolatedGuestIpRanges(network));
        when(network.getSpecifyIpRanges()).thenReturn(true);
        when(manager._networkOfferingDao.findById(10L)).thenReturn(null);
        assertFalse(manager.supportsIsolatedGuestIpRanges(network));
    }

    @Test
    public void excludesSourceNatVpcAndRouted() {
        when(manager._ntwkOffServiceMapDao.areServicesSupportedByNetworkOffering(10L, Service.SourceNat)).thenReturn(true);
        assertFalse(manager.supportsIsolatedGuestIpRanges(network));
        when(manager._ntwkOffServiceMapDao.areServicesSupportedByNetworkOffering(10L, Service.SourceNat)).thenReturn(false);
        when(network.getVpcId()).thenReturn(3L);
        assertFalse(manager.supportsIsolatedGuestIpRanges(network));
        when(network.getVpcId()).thenReturn(null);
        when(offering.getNetworkMode()).thenReturn(NetworkOffering.NetworkMode.ROUTED);
        assertFalse(manager.supportsIsolatedGuestIpRanges(network));
    }

    @Test
    public void doesNotTreatL2SharedOrPublicAsIsolated() {
        for (Network.GuestType type : new Network.GuestType[] {Network.GuestType.L2, Network.GuestType.Shared, null}) {
            when(network.getGuestType()).thenReturn(type);
            assertFalse(manager.supportsIsolatedGuestIpRanges(network));
        }
    }

    @Test
    public void permitsOnlyTheExistingIpv4GatewayAndSubnet() {
        manager.validateIsolatedGuestRangeSubnet(network, null, null);
        manager.validateIsolatedGuestRangeSubnet(network, "172.26.31.1", "255.255.255.0");
        assertThrows(InvalidParameterValueException.class,
                () -> manager.validateIsolatedGuestRangeSubnet(network, "172.26.32.1", "255.255.255.0"));
        assertThrows(InvalidParameterValueException.class,
                () -> manager.validateIsolatedGuestRangeSubnet(network, "172.26.31.2", "255.255.255.0"));
        assertThrows(InvalidParameterValueException.class,
                () -> manager.validateIsolatedGuestRangeSubnet(network, "172.26.31.1", "255.255.0.0"));
    }

    @Test
    public void refusesAnUnknownIpv4Subnet() {
        when(network.getCidr()).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> manager.validateIsolatedGuestRangeSubnet(network, null, null));
    }

    @Test
    public void updateEntryPointCannotMoveAnIsolatedRangeToAnotherSubnet() {
        final VlanVO range = mock(VlanVO.class);
        when(manager._vlanDao.findById(1L)).thenReturn(range);
        when(range.getNetworkId()).thenReturn(10L);
        when(range.getVlanGateway()).thenReturn("172.26.31.1");
        when(range.getIpRange()).thenReturn("172.26.31.10-172.26.31.20");
        when(manager._networkDao.findById(10L)).thenReturn(network);
        final InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class, () -> manager.updateVlanAndPublicIpRange(
                1L, "172.26.32.10", "172.26.32.20", "172.26.32.1", "255.255.255.0", null, null, null, null, null));
        assertTrue(error.getMessage().contains("gateway and subnet"));
    }

    @Test
    public void anIpv6OnlyRangeWithLegacyIpv4GatewayDoesNotAcceptIpv4Changes() {
        final VlanVO range = mock(VlanVO.class);
        when(manager._vlanDao.findById(1L)).thenReturn(range);
        when(range.getVlanGateway()).thenReturn("172.27.31.1");
        when(range.getIpRange()).thenReturn(null);
        when(range.getIp6Gateway()).thenReturn("fd31:1557::1");
        final InvalidParameterValueException error = assertThrows(InvalidParameterValueException.class, () -> manager.updateVlanAndPublicIpRange(
                1L, "172.27.31.30", "172.27.31.35", null, null, null, null, null, null, null));
        assertTrue(error.getMessage().contains("IPv4 is not support"));
    }

    private VlanVO range(final long id, final String gateway) {
        final VlanVO range = mock(VlanVO.class);
        when(range.getId()).thenReturn(id);
        when(range.getIpRange()).thenReturn(gateway.replace(".1", ".10") + "-" + gateway.replace(".1", ".20"));
        when(range.getVlanGateway()).thenReturn(gateway);
        when(range.getVlanNetmask()).thenReturn("255.255.255.0");
        return range;
    }

    private void guestNetwork(final Network.GuestType type) {
        when(network.getId()).thenReturn(10L);
        when(network.getGuestType()).thenReturn(type);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(manager._networkDao.findById(10L)).thenReturn(network);
    }

    @Test
    public void retainsBothSharedFamiliesWhenOtherRangesStillUseTheSubnet() {
        guestNetwork(Network.GuestType.Shared);
        final VlanVO first = range(1L, "172.27.31.1");
        when(first.getIp6Cidr()).thenReturn("fd31:1557::/64");
        when(first.getIp6Gateway()).thenReturn("fd31:1557::1");
        when(manager._vlanDao.listVlansByNetworkId(10L)).thenReturn(Collections.singletonList(first));
        manager.reconcileGuestRangeSubnets(10L);
        verify(network).setCidr("172.27.31.0/24");
        verify(network).setGateway("172.27.31.1");
        verify(network).setIp6Cidr("fd31:1557::/64");
        verify(network).setIp6Gateway("fd31:1557::1");
    }

    @Test
    public void reconcilesMultipleSharedSubnetsAndIgnoresLegacyIpv4FieldsOnIpv6Rows() {
        guestNetwork(Network.GuestType.Shared);
        final VlanVO ipv6 = mock(VlanVO.class);
        when(ipv6.getId()).thenReturn(3L);
        when(ipv6.getVlanGateway()).thenReturn("192.0.2.1");
        when(ipv6.getVlanNetmask()).thenReturn("255.255.255.0");
        when(ipv6.getIp6Cidr()).thenReturn("fd31:1557::/64");
        when(ipv6.getIp6Gateway()).thenReturn("fd31:1557::1");
        final VlanVO second = range(2L, "172.28.31.1");
        final VlanVO first = range(1L, "172.27.31.1");
        when(manager._vlanDao.listVlansByNetworkId(10L)).thenReturn(Arrays.asList(ipv6, second, first));
        manager.reconcileGuestRangeSubnets(10L);
        verify(network).setCidr("172.27.31.0/24,172.28.31.0/24");
        verify(network).setGateway("172.27.31.1,172.28.31.1");
        verify(network).setIp6Cidr("fd31:1557::/64");
    }

    @Test
    public void removesSharedSubnetMetadataOnlyAfterTheLastRangeIsGone() {
        guestNetwork(Network.GuestType.Shared);
        when(manager._vlanDao.listVlansByNetworkId(10L)).thenReturn(Collections.emptyList());
        manager.reconcileGuestRangeSubnets(10L);
        verify(network).setCidr(null);
        verify(network).setGateway(null);
        verify(network).setIp6Cidr(null);
        verify(network).setIp6Gateway(null);
    }

    @Test
    public void keepsAnIsolatedSubnetWhenItsLastAllocationRangeIsRemoved() {
        guestNetwork(Network.GuestType.Isolated);
        when(manager._vlanDao.listVlansByNetworkId(10L)).thenReturn(Collections.emptyList());
        manager.reconcileGuestRangeSubnets(10L);
        verify(network, never()).setCidr(anyString());
        verify(network, never()).setGateway(anyString());
    }

    @Test
    public void recoversMissingIsolatedMetadataOnlyFromAnUnambiguousExistingSubnet() {
        guestNetwork(Network.GuestType.Isolated);
        when(network.getCidr()).thenReturn(null);
        when(network.getGateway()).thenReturn(null);
        final VlanVO first = range(1L, "172.26.31.1");
        final VlanVO second = range(2L, "172.28.31.1");
        when(manager._vlanDao.listVlansByNetworkId(10L)).thenReturn(Collections.singletonList(first));
        manager.validateIsolatedGuestRangeSubnet(network, "172.26.31.1", "255.255.255.0");
        manager.reconcileGuestRangeSubnets(10L);
        verify(network).setCidr("172.26.31.0/24");
        verify(network).setGateway("172.26.31.1");
        when(manager._vlanDao.listVlansByNetworkId(10L)).thenReturn(Arrays.asList(first, second));
        assertThrows(InvalidParameterValueException.class, () -> manager.validateIsolatedGuestRangeSubnet(network, "172.26.31.1", "255.255.255.0"));
    }

    @Test
    public void guestRangeReconciliationDoesNotRewritePublicNetworks() {
        guestNetwork(Network.GuestType.Shared);
        when(network.getTrafficType()).thenReturn(TrafficType.Public);
        manager.reconcileGuestRangeSubnets(10L);
        verify(network, never()).setCidr(anyString());
        verify(network, never()).setGateway(anyString());
    }
}
