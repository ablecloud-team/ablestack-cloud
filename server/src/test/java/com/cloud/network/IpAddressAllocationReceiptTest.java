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

package com.cloud.network;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.user.Account;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class IpAddressAllocationReceiptTest {
    private void rejectedReceiptPreservesTheCurrentAllocation(String currentGeneration) {
        IpAddressManagerImpl manager = Mockito.spy(new IpAddressManagerImpl());
        IPAddressDao dao = Mockito.mock(IPAddressDao.class);
        manager._ipAddressDao = dao;
        IPAddressVO previous = Mockito.mock(IPAddressVO.class);
        IPAddressVO current = Mockito.mock(IPAddressVO.class);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(previous.getId()).thenReturn(7L);
        Mockito.when(dao.acquireInLockTable(7L)).thenReturn(current);
        Mockito.when(current.getAllocationGeneration()).thenReturn(currentGeneration);
        try {
            manager.disassociatePublicIpAddress(previous, 1L, caller, "old-allocation");
            Assert.fail("An unverified or replaced allocation must not be released");
        } catch (InvalidParameterValueException expected) {
            Mockito.verify(manager, Mockito.never()).cleanupIpResources(Mockito.any(), Mockito.anyLong(), Mockito.any());
            Mockito.verify(dao).releaseFromLockTable(7L);
        }
    }

    @Test
    public void aReusedPoolUuidDoesNotAuthorizeDeletingItsNewAllocation() {
        rejectedReceiptPreservesTheCurrentAllocation("new-allocation");
    }

    @Test
    public void aMissingAllocationGenerationCannotAuthorizeCleanup() {
        rejectedReceiptPreservesTheCurrentAllocation(null);
    }

    @Test
    public void matchingReceiptUsesTheFreshRowReadUnderTheAllocationLock() {
        IpAddressManagerImpl manager = Mockito.spy(new IpAddressManagerImpl());
        IPAddressDao dao = Mockito.mock(IPAddressDao.class);
        manager._ipAddressDao = dao;
        IPAddressVO previous = Mockito.mock(IPAddressVO.class);
        IPAddressVO current = Mockito.mock(IPAddressVO.class);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(previous.getId()).thenReturn(7L);
        Mockito.when(dao.acquireInLockTable(7L)).thenReturn(current);
        Mockito.when(current.getAllocationGeneration()).thenReturn("same-allocation");
        IllegalStateException reachedCleanup = new IllegalStateException("verified boundary");
        Mockito.doThrow(reachedCleanup).when(manager).cleanupIpResources(current, 1L, caller);
        try {
            manager.disassociatePublicIpAddress(previous, 1L, caller, "same-allocation");
            Assert.fail("Expected to reach the guarded cleanup boundary");
        } catch (IllegalStateException expected) {
            Assert.assertSame(reachedCleanup, expected);
            Mockito.verify(dao).releaseFromLockTable(7L);
        }
    }
}
