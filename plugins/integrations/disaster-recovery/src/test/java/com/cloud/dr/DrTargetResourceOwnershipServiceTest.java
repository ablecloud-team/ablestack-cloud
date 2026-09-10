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

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrReplicaDiskDao;
import com.cloud.dr.dao.DrTargetResourceClaimDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.storage.VolumeVO;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class DrTargetResourceOwnershipServiceTest {
    @Mock private DrTargetResourceClaimDao claimDao;
    @Mock private DrReplicaDao replicaDao;
    @Mock private DrReplicaDiskDao replicaDiskDao;
    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;
    @InjectMocks private DrTargetResourceOwnershipService service;

    @Test
    public void rejectsVmOwnedByAnotherPlanEvenWhenItsNameMatches() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        DrReplicaVO replica = Mockito.mock(DrReplicaVO.class);
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(plan.getId()).thenReturn(40L);
        Mockito.when(vm.getId()).thenReturn(256L);
        Map<String, String> details = new HashMap<String, String>();
        details.put("dr.plan.id", "38");
        details.put("dr.plan.uuid", "plan-38");
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(256L)).thenReturn(details);

        try {
            service.assertVmOwnedBy(plan, replica, vm);
            Assert.fail("Expected target ownership conflict");
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().startsWith(DrConstants.ERROR_TARGET_OWNERSHIP_CONFLICT));
            Assert.assertTrue(e.getMessage().contains("ownerPlanId=38"));
        }
    }

    @Test
    public void acceptsVmOnlyWhenPlanAndReplicaOwnershipMatch() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        DrReplicaVO replica = Mockito.mock(DrReplicaVO.class);
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(plan.getId()).thenReturn(40L);
        Mockito.when(replica.getId()).thenReturn(41L);
        Mockito.when(vm.getId()).thenReturn(300L);
        Map<String, String> details = new HashMap<String, String>();
        details.put("dr.plan.id", "40");
        details.put("dr.plan.uuid", "plan-40");
        details.put("dr.replica.id", "41");
        Mockito.when(vmInstanceDetailsDao.listDetailsKeyPairs(300L)).thenReturn(details);

        service.assertVmOwnedBy(plan, replica, vm);
    }

    @Test
    public void removedReplicaDiskHistoryDoesNotBlockAReleasedVolume() {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        DrReplicaVO replica = Mockito.mock(DrReplicaVO.class);
        DrReplicaDiskVO disk = Mockito.mock(DrReplicaDiskVO.class);
        DrReplicaDiskVO removedDisk = Mockito.mock(DrReplicaDiskVO.class);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        Mockito.when(disk.getId()).thenReturn(60L);
        Mockito.when(removedDisk.getId()).thenReturn(56L);
        Mockito.when(removedDisk.getRemoved()).thenReturn(new Date());
        Mockito.when(volume.getId()).thenReturn(520L);
        Mockito.when(replicaDiskDao.listByTargetVolumeId(520L))
                .thenReturn(Collections.singletonList(removedDisk));

        service.assertVolumeOwnedBy(plan, replica, disk, volume);
    }

    @Test
    public void releasesEveryActiveClaimForThePlan() {
        DrTargetResourceClaimVO claim = Mockito.mock(DrTargetResourceClaimVO.class);
        Mockito.when(claim.getId()).thenReturn(185L);
        Mockito.when(claim.getClaimState()).thenReturn("CLAIMED");
        Mockito.when(claimDao.listActiveByPlanId(47L)).thenReturn(Collections.singletonList(claim));

        service.releasePlanClaims(47L);

        Mockito.verify(claim).release();
        Mockito.verify(claimDao).update(185L, claim);
    }
}
