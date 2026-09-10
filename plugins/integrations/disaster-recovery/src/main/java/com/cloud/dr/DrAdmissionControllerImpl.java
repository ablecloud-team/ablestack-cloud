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

import java.util.Date;
import java.util.List;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.cloud.dr.dao.DrResourceLeaseDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;

public class DrAdmissionControllerImpl extends ManagerBase implements DrAdmissionController {
    private static final int FULL_SEED_LIMIT = 2;
    private static final int INCREMENTAL_LIMIT = 4;
    private static final int TRANSITION_LIMIT = 1;
    private static final int LEASE_SECONDS = 120;

    @Inject private DrResourceLeaseDao drResourceLeaseDao;
    @Inject private DrWorkerPlacementService drWorkerPlacementService;

    @Override
    public boolean acquire(DrPlanVO plan, DrRunVO run) {
        if (plan == null || run == null) {
            return false;
        }
        Date now = new Date();
        DrResourceLeaseVO existing = drResourceLeaseDao.findActiveByRunId(run.getId(), now);
        if (existing != null) {
            return true;
        }
        String operationClass = operationClass(run);
        DrWorkerRole role = StringUtils.startsWithIgnoreCase(plan.getDirection(), "VMWARE_")
                ? DrWorkerRole.VDDK_DATA_PLANE : DrWorkerRole.COORDINATOR;
        Long workerHostId = drWorkerPlacementService != null
                ? drWorkerPlacementService.resolveWorkerHostId(plan, run, role) : null;
        if (workerHostId == null) {
            return false;
        }
        String resourceKey = "HOST:" + (workerHostId != null ? workerHostId : 0L) + ":" + operationClass;
        GlobalLock lock = GlobalLock.getInternLock("DrAdmission:" + resourceKey);
        boolean acquired = false;
        try {
            acquired = lock.lock(2);
            if (!acquired) {
                return false;
            }
            List<DrResourceLeaseVO> active = drResourceLeaseDao.listActiveByResourceKey(resourceKey, now);
            if (active != null && active.size() >= limit(operationClass)) {
                return false;
            }
            Date expiresAt = new Date(now.getTime() + LEASE_SECONDS * 1000L);
            return drResourceLeaseDao.persist(new DrResourceLeaseVO(resourceKey, operationClass,
                    plan.getId(), run.getId(), expiresAt)) != null;
        } finally {
            if (acquired) {
                lock.unlock();
            }
            lock.releaseRef();
        }
    }

    @Override
    public void renew(long runId) {
        DrResourceLeaseVO lease = drResourceLeaseDao.findByRunId(runId);
        if (lease == null) {
            return;
        }
        lease.setExpiresAt(new Date(System.currentTimeMillis() + LEASE_SECONDS * 1000L));
        drResourceLeaseDao.update(lease.getId(), lease);
    }

    @Override
    public void release(long runId) {
        DrResourceLeaseVO lease = drResourceLeaseDao.findByRunId(runId);
        if (lease == null) {
            return;
        }
        lease.setState("RELEASED");
        drResourceLeaseDao.update(lease.getId(), lease);
    }

    @Override
    public String operationClass(DrRunVO run) {
        String type = StringUtils.upperCase(run != null ? run.getRunType() : null);
        if (StringUtils.equals(type, DrConstants.RUN_TYPE_SYNC)) {
            return isFullSeedRequest(run) ? "FULL_SEED" : "INCREMENTAL";
        }
        if (StringUtils.equalsAny(type, DrConstants.RUN_TYPE_FAILOVER, DrConstants.RUN_TYPE_FAILBACK,
                DrConstants.RUN_TYPE_TEST_FAILOVER, DrConstants.RUN_TYPE_TEST_CLEANUP,
                DrConstants.RUN_TYPE_REPROTECT, DrConstants.RUN_TYPE_RELEASE)) {
            return "TRANSITION";
        }
        return "INCREMENTAL";
    }

    private boolean isFullSeedRequest(DrRunVO run) {
        if (run == null || StringUtils.isBlank(run.getRequestJson())) {
            return false;
        }
        try {
            JsonObject request = JsonParser.parseString(run.getRequestJson()).getAsJsonObject();
            return request.has("forceFullReseed") && request.get("forceFullReseed").getAsBoolean()
                    || request.has("mode") && StringUtils.equalsIgnoreCase("FULL_RESEED", request.get("mode").getAsString());
        } catch (RuntimeException e) {
            return false;
        }
    }

    int limit(String operationClass) {
        if ("FULL_SEED".equals(operationClass)) {
            return FULL_SEED_LIMIT;
        }
        if ("TRANSITION".equals(operationClass)) {
            return TRANSITION_LIMIT;
        }
        return INCREMENTAL_LIMIT;
    }
}
