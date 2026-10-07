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
package com.cloud.network.dao;

import java.util.Date;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

public class IPAddressAllocationGenerationTest {
    @Test
    public void partialDaoUpdatesPersistAndClearTheAllocationGeneration() {
        IPAddressVO address = new IPAddressDaoImpl().createForUpdate();
        address.setAllocatedTime(new Date());
        Assert.assertTrue(com.cloud.utils.db.GenericDaoBase.getUpdateBuilder(address).has("allocationGeneration"));
        String allocated = address.getAllocationGeneration();
        Assert.assertNotNull(allocated);
        address.setAllocatedTime(null);
        Assert.assertNull(address.getAllocationGeneration());
        Assert.assertTrue(com.cloud.utils.db.GenericDaoBase.getUpdateBuilder(address).has("allocationGeneration"));
    }

    @Test
    public void poolIdentityAndSameTimestampCannotReuseAnAllocationReceipt() {
        IPAddressVO address = new IPAddressVO();
        Date sameTimestamp = new Date(1000);
        address.setAllocatedTime(sameTimestamp);
        String first = address.getAllocationGeneration();
        Assert.assertEquals(first, UUID.fromString(first).toString());
        address.setAllocatedTime(null);
        Assert.assertNull(address.getAllocationGeneration());
        address.setAllocatedTime(sameTimestamp);
        Assert.assertNotEquals(first, address.getAllocationGeneration());
    }

    @Test
    public void unrelatedNetworkUpdatesPreserveAnAllocationReceipt() {
        IPAddressVO address = new IPAddressVO();
        address.setAllocatedTime(new Date());
        String receipt = address.getAllocationGeneration();
        address.setSourceNat(false);
        address.setOneToOneNat(false);
        address.setDisplay(true);
        Assert.assertEquals(receipt, address.getAllocationGeneration());
    }
}
