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
package org.apache.cloudstack.engine.orchestration;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.network.Network;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.Nic;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;

public class NetworkDeletionLifecycleTest {
    private final NetworkOrchestrator orchestrator = new NetworkOrchestrator();
    private final ResourceLimitService limits = mock(ResourceLimitService.class);
    private final Network network = mock(Network.class);
    private final NetworkOffering offering = mock(NetworkOffering.class);

    @Before
    public void setup() {
        orchestrator._resourceLimitMgr = limits;
        orchestrator._nicDao = mock(NicDao.class);
        when(network.getAccountId()).thenReturn(9L);
        when(network.getDomainId()).thenReturn(8L);
        when(network.getAclType()).thenReturn(ACLType.Account);
        when(network.getDisplayNetwork()).thenReturn(true);
    }

    @Test
    public void countableAccountNetworkUsesOneDeltaPerCommittedMutation() {
        orchestrator.updateAccountNetworkResourceCount(network, offering, true);
        orchestrator.updateAccountNetworkResourceCount(network, offering, false);
        verify(limits).incrementResourceCount(9L, ResourceType.network, true);
        verify(limits).decrementResourceCount(9L, ResourceType.network, true);
    }

    @Test
    public void explicitlySpecifiedVlanMatchesTheOfficialAccountRecountPredicate() {
        when(offering.isSpecifyVlan()).thenReturn(true);
        assertFalse(orchestrator.isResourceCountUpdateNeeded(offering));
        orchestrator.updateAccountNetworkResourceCount(network, offering, true);
        orchestrator.updateAccountNetworkResourceCount(network, offering, false);
        verifyNoInteractions(limits);
    }

    @Test
    public void systemAndDomainSharedNetworksNeverApplyAccountDeltas() {
        when(offering.isSystemOnly()).thenReturn(true);
        orchestrator.updateAccountNetworkResourceCount(network, offering, true);
        when(offering.isSystemOnly()).thenReturn(false);
        when(network.getAclType()).thenReturn(ACLType.Domain);
        orchestrator.updateAccountNetworkResourceCount(network, offering, false);
        when(network.getAccountId()).thenReturn(Account.ACCOUNT_ID_SYSTEM);
        orchestrator.updateAccountNetworkResourceCount(network, offering, true);
        verifyNoInteractions(limits);
    }

    @Test
    public void domainSharedReconciliationIsSeparateFromAccountDeltas() {
        when(network.getAclType()).thenReturn(ACLType.Domain);
        when(network.getAccountId()).thenReturn(Account.ACCOUNT_ID_SYSTEM);
        orchestrator.reconcileDomainNetworkResourceCount(network);
        verify(limits).recalculateDomainResourceCount(8L, ResourceType.network, null);
    }

    @Test
    public void accountMutationDoesNotRunAnAccountWideRecount() {
        orchestrator.reconcileDomainNetworkResourceCount(network);
        verifyNoInteractions(limits);
    }

    @Test
    public void aCommittedMutationDoesNotBecomeAFailedReceiptOnRecountFailure() {
        when(network.getAclType()).thenReturn(ACLType.Domain);
        when(network.getAccountId()).thenReturn(Account.ACCOUNT_ID_SYSTEM);
        when(limits.recalculateDomainResourceCount(8L, ResourceType.network, null)).thenThrow(new CloudRuntimeException("recount failed"));
        orchestrator.reconcileDomainNetworkResourceCount(network);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void onlyOwnerlessPlaceholderNicsAreRemoved() {
        NicVO placeholder = mock(NicVO.class);
        SearchCriteria<NicVO> criteria = mock(SearchCriteria.class);
        when(orchestrator._nicDao.createSearchCriteria()).thenReturn(criteria);
        when(orchestrator._nicDao.search(criteria, null)).thenReturn(Arrays.asList(placeholder));
        when(placeholder.getId()).thenReturn(12L);
        when(orchestrator._nicDao.remove(12L)).thenReturn(true);
        orchestrator.removeNetworkPlaceholderNics(7L);
        verify(criteria).addAnd("networkId", SearchCriteria.Op.EQ, 7L);
        verify(criteria).addAnd("instanceId", SearchCriteria.Op.NULL);
        verify(criteria).addAnd("reservationStrategy", SearchCriteria.Op.EQ, Nic.ReservationStrategy.PlaceHolder);
        verify(orchestrator._nicDao).remove(12L);
    }

    @Test(expected = CloudRuntimeException.class)
    public void aPlaceholderCleanupFailureAbortsMetadataDeletion() {
        NicVO placeholder = mock(NicVO.class);
        SearchCriteria<NicVO> criteria = mock(SearchCriteria.class);
        when(orchestrator._nicDao.createSearchCriteria()).thenReturn(criteria);
        when(orchestrator._nicDao.search(criteria, null)).thenReturn(Arrays.asList(placeholder));
        orchestrator.removeNetworkPlaceholderNics(7L);
    }
}
