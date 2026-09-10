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

package com.cloud.dr;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.dao.DrResourceLeaseDao;

@RunWith(MockitoJUnitRunner.class)
public class DrAdmissionControllerImplTest {
    @Mock
    private DrResourceLeaseDao drResourceLeaseDao;

    @InjectMocks
    private DrAdmissionControllerImpl controller;

    @Test
    public void classifiesFullSeedIncrementalAndTransitionWithoutChangingLegacySyncDefault() {
        DrRunVO legacySync = new DrRunVO(1L, DrConstants.RUN_TYPE_SYNC);
        DrRunVO fullReseed = new DrRunVO(1L, DrConstants.RUN_TYPE_SYNC);
        fullReseed.setRequestJson("{\"mode\":\"FULL_RESEED\"}");
        DrRunVO failover = new DrRunVO(1L, DrConstants.RUN_TYPE_FAILOVER);

        Assert.assertEquals("INCREMENTAL", controller.operationClass(legacySync));
        Assert.assertEquals("FULL_SEED", controller.operationClass(fullReseed));
        Assert.assertEquals("TRANSITION", controller.operationClass(failover));
    }

    @Test
    public void usesSeparateBoundedCapacityForEveryOperationClass() {
        Assert.assertEquals(2, controller.limit("FULL_SEED"));
        Assert.assertEquals(4, controller.limit("INCREMENTAL"));
        Assert.assertEquals(1, controller.limit("TRANSITION"));
    }
}
