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
package com.cloud.api.query.dao;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.vo.VpcOfferingJoinVO;
import com.cloud.network.vpc.VpcOffering;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class VpcOfferingJoinDaoImplTest {
    private void verifyRouterMode(boolean redundant, boolean joined) {
        VpcOfferingJoinDaoImpl dao = Mockito.mock(VpcOfferingJoinDaoImpl.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(dao, "vpcOfferingServiceMapDao", Mockito.mock(VpcOfferingServiceMapDao.class));
        VpcOffering offering = joined ? Mockito.mock(VpcOfferingJoinVO.class) : Mockito.mock(VpcOffering.class);
        Mockito.when(offering.getState()).thenReturn(VpcOffering.State.Enabled);
        Mockito.when(offering.getUuid()).thenReturn("fixture-offering");
        Mockito.when(offering.isRedundantRouter()).thenReturn(redundant);

        JsonObject json = new Gson().toJsonTree(dao.newVpcOfferingResponse(offering)).getAsJsonObject();
        Assert.assertEquals("fixture-offering", json.get("id").getAsString());
        Assert.assertTrue("Explicit false must be serialized for safe migration candidates", json.has("redundantvpcrouter"));
        Assert.assertEquals(redundant, json.get("redundantvpcrouter").getAsBoolean());
    }

    @Test
    public void serializesSingleRouterOffering() {
        verifyRouterMode(false, false);
    }

    @Test
    public void serializesRedundantRouterOffering() {
        verifyRouterMode(true, false);
    }

    @Test
    public void serializesSingleRouterJoinedOffering() {
        verifyRouterMode(false, true);
    }

    @Test
    public void serializesRedundantRouterJoinedOffering() {
        verifyRouterMode(true, true);
    }
}
