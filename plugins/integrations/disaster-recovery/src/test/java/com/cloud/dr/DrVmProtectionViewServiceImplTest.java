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

import org.apache.cloudstack.api.response.dr.DrVmPlanAssociationResponse;
import org.apache.cloudstack.api.response.dr.DrVmProtectionViewResponse;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.dr.dao.DrReplicaDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class DrVmProtectionViewServiceImplTest {
    private static final long VM_ID = 42L;
    private static final long PLAN_ID = 71L;

    @Mock private UserVmDao userVmDao;
    @Mock private DrPlanDao drPlanDao;
    @Mock private DrReplicaDao drReplicaDao;
    @Mock private DrTestSessionDao drTestSessionDao;
    @Mock private DrPlanRuntimeDao drPlanRuntimeDao;
    @Mock private DrRunDao drRunDao;
    @Mock private DrSiteDao drSiteDao;
    @InjectMocks private DrVmProtectionViewServiceImpl service;

    @Test
    public void returnsNotManagedHereWithoutRemoteFallback() {
        UserVmVO vm = vm("vm-42", "unprotected");
        Mockito.when(userVmDao.findById(VM_ID)).thenReturn(vm);
        Mockito.when(drPlanDao.listActiveBySourceVmId(VM_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drReplicaDao.listActiveByTargetVmId(VM_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drTestSessionDao.listActiveByTargetVmId(VM_ID)).thenReturn(Collections.emptyList());

        DrVmProtectionViewResponse response = service.getView(VM_ID);

        Assert.assertFalse(response.isConfigured());
        Assert.assertEquals("NOT_MANAGED_HERE", response.getProjectionState());
        Assert.assertTrue(response.getAssociations().isEmpty());
    }

    @Test
    public void resolvesRecoveryTargetByPersistedTargetVmId() {
        UserVmVO viewedVm = vm("target-vm-uuid", "recovery-target");
        DrSiteVO sourceSite = site("source-site", "Source");
        DrSiteVO targetSite = site("target-site", "Target");
        DrPlanVO plan = plan("plan-71", VM_ID + 1, "SOURCE");
        Mockito.when(plan.getSourceExternalRef()).thenReturn("source-vm-uuid");
        Mockito.when(plan.getMappingJson()).thenReturn("{\"sourceVmName\":\"source-vm\"}");
        DrReplicaVO replica = Mockito.mock(DrReplicaVO.class);
        Mockito.when(replica.getPlanId()).thenReturn(PLAN_ID);
        Mockito.when(replica.getTargetVmId()).thenReturn(VM_ID);
        Mockito.when(replica.getState()).thenReturn("TARGET_READY");
        Mockito.when(replica.getPowerState()).thenReturn("STOPPED");

        Mockito.when(userVmDao.findById(VM_ID)).thenReturn(viewedVm);
        Mockito.when(drPlanDao.listActiveBySourceVmId(VM_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drReplicaDao.listActiveByTargetVmId(VM_ID)).thenReturn(Collections.singletonList(replica));
        Mockito.when(drTestSessionDao.listActiveByTargetVmId(VM_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drPlanDao.findById(PLAN_ID)).thenReturn(plan);
        Mockito.when(drPlanRuntimeDao.findByPlanId(PLAN_ID)).thenReturn(null);
        Mockito.when(drRunDao.findLatestByPlanId(PLAN_ID)).thenReturn(null);
        Mockito.when(drSiteDao.findById(10L)).thenReturn(sourceSite);
        Mockito.when(drSiteDao.findById(20L)).thenReturn(targetSite);

        DrVmProtectionViewResponse response = service.getView(VM_ID);

        Assert.assertTrue(response.isConfigured());
        Assert.assertFalse(response.isRelationConflict());
        Assert.assertEquals(1, response.getAssociations().size());
        DrVmPlanAssociationResponse association = response.getAssociations().get(0);
        Assert.assertEquals("RECOVERY_TARGET", association.getRelationshipRole());
        Assert.assertEquals("STANDBY", association.getAuthorityRole());
        Assert.assertEquals("source-vm-uuid", association.getSourceVmId());
        Assert.assertEquals("target-vm-uuid", association.getTargetVmId());
        Assert.assertEquals("UNKNOWN", association.getProjectionState());
        Mockito.verify(userVmDao, Mockito.never()).findById(VM_ID + 1);
    }

    @Test
    public void doesNotUseTargetVmNameAsPersistedSourceName() {
        UserVmVO viewedVm = vm("target-vm-uuid", "recovery-target");
        DrPlanVO plan = plan("plan-71", VM_ID + 1, "SOURCE");
        Mockito.when(plan.getSourceExternalRef()).thenReturn("source-vm-uuid");
        Mockito.when(plan.getMappingJson()).thenReturn("{\"targetVmName\":\"recovery-target\",\"target\":{\"vmName\":\"recovery-target\"}}");
        DrReplicaVO replica = Mockito.mock(DrReplicaVO.class);
        Mockito.when(replica.getPlanId()).thenReturn(PLAN_ID);
        Mockito.when(replica.getTargetVmId()).thenReturn(VM_ID);

        Mockito.when(userVmDao.findById(VM_ID)).thenReturn(viewedVm);
        Mockito.when(drPlanDao.listActiveBySourceVmId(VM_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drReplicaDao.listActiveByTargetVmId(VM_ID)).thenReturn(Collections.singletonList(replica));
        Mockito.when(drTestSessionDao.listActiveByTargetVmId(VM_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drPlanDao.findById(PLAN_ID)).thenReturn(plan);

        DrVmPlanAssociationResponse association = service.getView(VM_ID).getAssociations().get(0);

        Assert.assertEquals("source-vm-uuid", association.getSourceVmName());
        Assert.assertEquals("recovery-target", association.getTargetVmName());
        Mockito.verify(userVmDao, Mockito.never()).findById(VM_ID + 1);
    }

    @Test
    public void reportsConflictingPermanentRelationships() {
        UserVmVO vm = vm("vm-42", "conflicted");
        DrSiteVO sourceSite = site("source-site", "Source");
        DrSiteVO targetSite = site("target-site", "Target");
        DrPlanVO sourcePlan = plan("source-plan", VM_ID, "SOURCE");
        DrPlanVO targetPlan = plan("target-plan", VM_ID + 1, "SOURCE");
        DrReplicaVO targetReplica = Mockito.mock(DrReplicaVO.class);
        Mockito.when(targetReplica.getPlanId()).thenReturn(PLAN_ID);
        Mockito.when(targetReplica.getTargetVmId()).thenReturn(VM_ID);

        Mockito.when(userVmDao.findById(VM_ID)).thenReturn(vm);
        Mockito.when(drPlanDao.listActiveBySourceVmId(VM_ID)).thenReturn(Collections.singletonList(sourcePlan));
        Mockito.when(drReplicaDao.listActiveByTargetVmId(VM_ID)).thenReturn(Collections.singletonList(targetReplica));
        Mockito.when(drTestSessionDao.listActiveByTargetVmId(VM_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drReplicaDao.listActiveByPlanId(PLAN_ID)).thenReturn(Collections.emptyList());
        Mockito.when(drPlanDao.findById(PLAN_ID)).thenReturn(targetPlan);
        Mockito.when(drSiteDao.findById(10L)).thenReturn(sourceSite);
        Mockito.when(drSiteDao.findById(20L)).thenReturn(targetSite);

        DrVmProtectionViewResponse response = service.getView(VM_ID);

        Assert.assertTrue(response.isRelationConflict());
        Assert.assertEquals(2, response.getAssociations().size());
    }

    private UserVmVO vm(String uuid, String name) {
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getUuid()).thenReturn(uuid);
        Mockito.when(vm.getDisplayName()).thenReturn(name);
        return vm;
    }

    private DrPlanVO plan(String uuid, long sourceVmId, String activeSide) {
        DrPlanVO plan = Mockito.mock(DrPlanVO.class);
        Mockito.when(plan.getId()).thenReturn(PLAN_ID);
        Mockito.when(plan.getUuid()).thenReturn(uuid);
        Mockito.when(plan.getName()).thenReturn(uuid);
        Mockito.when(plan.getSourceSiteId()).thenReturn(10L);
        Mockito.when(plan.getTargetSiteId()).thenReturn(20L);
        Mockito.when(plan.getSourceVmId()).thenReturn(sourceVmId);
        Mockito.when(plan.getState()).thenReturn("READY");
        Mockito.when(plan.getActiveSide()).thenReturn(activeSide);
        return plan;
    }

    private DrSiteVO site(String uuid, String name) {
        DrSiteVO site = Mockito.mock(DrSiteVO.class);
        Mockito.when(site.getUuid()).thenReturn(uuid);
        Mockito.when(site.getName()).thenReturn(name);
        return site;
    }
}
