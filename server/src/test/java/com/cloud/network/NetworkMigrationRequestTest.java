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
package com.cloud.network;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.Silent.class)
public class NetworkMigrationRequestTest {
    @Mock private NetworkDao networksDao;
    @Mock private NetworkOfferingDao offeringDao;
    @Mock private PhysicalNetworkDao physicalDao;
    @Mock private AccountManager accountMgr;
    @Mock private ConfigurationManager configMgr;
    @Mock private NetworkModel networkModel;
    @Mock private NetworkMigrationManager migrationManager;
    @Mock private NetworkOrchestrationService networkMgr;
    @Mock private IPAddressDao ipAddressDao;
    @Mock private NetworkOfferingServiceMapDao serviceMapDao;
    @Mock private VMInstanceDao vmDao;
    @Mock private RulesManager rulesMgr;
    @Mock private com.cloud.dc.dao.DataCenterDao dcDao;
    @InjectMocks private NetworkServiceImpl service;

    private NetworkVO network;
    private NetworkVO previous;
    private NetworkOfferingVO oldOffering;
    private NetworkOfferingVO offering;
    private PhysicalNetworkVO physical;
    private Account caller;
    private User user;

    @Before
    public void setup() {
        network = mock(NetworkVO.class);
        previous = mock(NetworkVO.class);
        oldOffering = mock(NetworkOfferingVO.class);
        offering = mock(NetworkOfferingVO.class);
        physical = mock(PhysicalNetworkVO.class);
        caller = mock(Account.class);
        user = mock(User.class);
        when(caller.getId()).thenReturn(2L);
        when(accountMgr.isRootAdmin(2L)).thenReturn(true);
        when(networksDao.findById(10L)).thenReturn(network);
        when(networksDao.findById(11L)).thenReturn(previous);
        when(network.getId()).thenReturn(10L);
        when(network.getRelated()).thenReturn(10L);
        when(network.getAccountId()).thenReturn(2L);
        when(previous.getRelated()).thenReturn(10L);
        when(previous.getAccountId()).thenReturn(2L);
        when(network.getPhysicalNetworkId()).thenReturn(1L);
        when(network.getDataCenterId()).thenReturn(1L);
        when(network.getNetworkOfferingId()).thenReturn(1L);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(network.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(network.getState()).thenReturn(Network.State.Implemented);
        when(network.getVpcId()).thenReturn(null);
        when(offeringDao.findById(2L)).thenReturn(offering);
        when(offeringDao.findByIdIncludingRemoved(anyLong())).thenReturn(oldOffering);
        when(oldOffering.getId()).thenReturn(1L);
        when(oldOffering.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(oldOffering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(offering.getId()).thenReturn(2L);
        when(offering.getState()).thenReturn(NetworkOffering.State.Enabled);
        when(offering.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(offering.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(offering.getTags()).thenReturn("target");
        when(networkModel.findPhysicalNetworkId(1L, "target", Networks.TrafficType.Guest)).thenReturn(20L);
        when(physicalDao.findById(20L)).thenReturn(physical);
        when(physical.getState()).thenReturn(PhysicalNetwork.State.Enabled);
    }

    private void rejected(boolean resume, Class<? extends RuntimeException> type, String text) {
        try {
            service.migrateGuestNetwork(10L, 2L, caller, user, resume);
            fail("Migration should be rejected");
        } catch (RuntimeException e) {
            assertTrue(e.toString(), type.isInstance(e));
            assertTrue(e.getMessage(), e.getMessage().contains(text));
        }
        verifyNoInteractions(migrationManager);
    }

    @Test public void rootAdminIsRequired() {
        when(accountMgr.isRootAdmin(2L)).thenReturn(false);
        rejected(false, PermissionDeniedException.class, "Root Admin");
    }

    @Test public void missingNetworkIsRejectedWithoutNullDereference() {
        when(networksDao.findById(10L)).thenReturn(null);
        rejected(false, InvalidParameterValueException.class, "Unable to find");
    }

    @Test public void missingOfferingIsRejectedWithoutNullDereference() {
        when(offeringDao.findById(2L)).thenReturn(null);
        rejected(false, InvalidParameterValueException.class, "Unable to find");
    }

    @Test public void disabledOfferingIsRejected() {
        when(offering.getState()).thenReturn(NetworkOffering.State.Disabled);
        rejected(false, InvalidParameterValueException.class, "enabled");
    }

    @Test public void sharedAndL2AreRejected() {
        for (Network.GuestType type : new Network.GuestType[] {Network.GuestType.Shared, Network.GuestType.L2}) {
            when(network.getGuestType()).thenReturn(type);
            rejected(false, InvalidParameterValueException.class, "Isolated");
        }
    }

    @Test public void vpcTierIsRejected() {
        when(network.getVpcId()).thenReturn(9L);
        rejected(false, InvalidParameterValueException.class, "migrateVpc");
    }

    @Test public void transientStateIsRejected() {
        when(network.getState()).thenReturn(Network.State.Implementing);
        rejected(false, com.cloud.utils.exception.CloudRuntimeException.class, "invalid state");
    }

    @Test public void resumeCannotStartANewMigration() {
        rejected(true, InvalidParameterValueException.class, "no previous");
    }

    @Test public void freshRequestCannotReplaceAnIncompleteMigration() {
        when(network.getRelated()).thenReturn(11L);
        rejected(false, InvalidParameterValueException.class, "incomplete");
    }

    @Test public void resumeCannotChangeDestinationOffering() {
        when(network.getRelated()).thenReturn(11L);
        rejected(true, InvalidParameterValueException.class, "must match");
    }

    @Test public void inconsistentCheckpointIsRejected() {
        when(network.getRelated()).thenReturn(11L);
        when(previous.getRelated()).thenReturn(99L);
        rejected(true, InvalidParameterValueException.class, "inconsistent");
    }

    @Test public void samePhysicalNetworkUsesTheUpdateApi() {
        when(network.getPhysicalNetworkId()).thenReturn(20L);
        rejected(false, InvalidParameterValueException.class, "updateNetwork");
    }

    @Test public void disabledPhysicalNetworkIsRejected() {
        when(physical.getState()).thenReturn(PhysicalNetwork.State.Disabled);
        rejected(false, InvalidParameterValueException.class, "physical network must be enabled");
    }

    @Test public void resumeCannotFollowEditedOfferingTagsToADifferentPhysicalNetwork() {
        when(network.getRelated()).thenReturn(11L);
        when(network.getNetworkOfferingId()).thenReturn(2L);
        rejected(true, InvalidParameterValueException.class, "no longer resolves");
    }

    @Test public void resumeChecksVmStatesOnThePreviousNetworkToo() {
        when(network.getRelated()).thenReturn(11L);
        when(network.getNetworkOfferingId()).thenReturn(2L);
        when(networkMgr.finalizeServicesAndProvidersForNetwork(eq(oldOffering), anyLong())).thenReturn(new HashMap<>());
        when(networkMgr.finalizeServicesAndProvidersForNetwork(eq(offering), anyLong())).thenReturn(new HashMap<>());
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(VirtualMachine.State.Starting);
        when(vmDao.listNonRemovedVmsByTypeAndNetwork(eq(11L), isNull())).thenReturn(Collections.singletonList(vm));
        try {
            ReflectionTestUtils.invokeMethod(service, "networkNeedsMigration", network, 20L, oldOffering, offering);
            fail("Transitional VM on previous network should block resume");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            assertTrue(expected.getMessage().contains("VM"));
        }
        verify(vmDao).listNonRemovedVmsByTypeAndNetwork(eq(11L), isNull());
    }

    @Test public void nullImplementationKeepsCheckpointAndDoesNotTouchNics() throws Exception {
        assertIncompleteImplementation(null);
    }

    @Test public void emptyImplementationKeepsCheckpointAndDoesNotTouchNics() throws Exception {
        assertIncompleteImplementation(new com.cloud.utils.Pair<>(null, null));
    }

    private void assertIncompleteImplementation(com.cloud.utils.Pair<com.cloud.network.guru.NetworkGuru, NetworkVO> result) throws Exception {
        when(network.getRelated()).thenReturn(11L);
        when(network.getNetworkOfferingId()).thenReturn(2L);
        when(network.getState()).thenReturn(Network.State.Allocated);
        when(previous.getState()).thenReturn(Network.State.Implemented);
        org.mockito.Mockito.doReturn(result).when(networkMgr).implementNetwork(eq(10L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        try {
            ReflectionTestUtils.invokeMethod(service, "migrateNetworkToPhysicalNetwork", network, oldOffering, offering, null, null, 20L, caller, user);
            fail("An unimplemented destination must retain the checkpoint");
        } catch (com.cloud.utils.exception.CloudRuntimeException expected) {
            assertTrue(expected.getMessage().contains("resume the same destination"));
        }
        verifyNoInteractions(migrationManager);
    }
}
