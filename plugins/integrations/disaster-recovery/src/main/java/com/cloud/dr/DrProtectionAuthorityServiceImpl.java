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

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.dao.DrPlanRuntimeDao;
import com.cloud.utils.component.ManagerBase;

public class DrProtectionAuthorityServiceImpl extends ManagerBase implements DrProtectionAuthorityService {
    @Inject
    private DrPlanRuntimeDao drPlanRuntimeDao;

    @Override
    public DrProtectionAuthoritySnapshot getAuthority(long planId) {
        DrPlanRuntimeVO runtime = drPlanRuntimeDao.findByPlanId(planId);
        boolean ready = runtime != null
                && StringUtils.equals(runtime.getProtectionState(), DrConstants.PLAN_STATE_READY)
                && isWithinCutoverRpo(runtime)
                && runtime.isSchedulerPidAlive()
                && runtime.isOwnerMatched()
                && StringUtils.equals(runtime.getSchedulerHealthState(), "HEALTHY")
                && !runtime.isRpoOverdue()
                && Boolean.TRUE.equals(runtime.getLatestCompletedIncrementalVerified())
                && runtime.getConsecutiveAutomaticReseedCount() == 0
                && !StringUtils.equalsAnyIgnoreCase(runtime.getCurrentCycleState(), "ERROR", "FAILED")
                && StringUtils.isBlank(runtime.getErrorCode());
        return new DrProtectionAuthoritySnapshot(runtime, ready, resolveBlockingReason(runtime, ready));
    }

    private String resolveBlockingReason(DrPlanRuntimeVO runtime, boolean ready) {
        if (ready) {
            return null;
        }
        if (runtime == null) {
            return "DR_AUTHORITY_NOT_AVAILABLE";
        }
        if (!StringUtils.equals(runtime.getProtectionState(), DrConstants.PLAN_STATE_READY)) {
            return "DR_PROTECTION_NOT_READY";
        }
        if (!isWithinCutoverRpo(runtime)) {
            return "DR_RPO_OVERDUE";
        }
        if (!runtime.isSchedulerPidAlive()) {
            return "DR_SCHEDULER_NOT_RUNNING";
        }
        if (!runtime.isOwnerMatched()) {
            return "DR_SCHEDULER_OWNER_MISMATCH";
        }
        if (!StringUtils.equals(runtime.getSchedulerHealthState(), "HEALTHY")) {
            return "DR_SCHEDULER_UNHEALTHY";
        }
        if (!Boolean.TRUE.equals(runtime.getLatestCompletedIncrementalVerified())) {
            return "DR_INCREMENTAL_CHECKPOINT_UNVERIFIED";
        }
        if (runtime.getConsecutiveAutomaticReseedCount() > 0) {
            return "DR_AUTOMATIC_RESEED_PENDING";
        }
        if (StringUtils.equalsAnyIgnoreCase(runtime.getCurrentCycleState(), "ERROR", "FAILED")) {
            return "DR_REPLICATION_CYCLE_FAILED";
        }
        if (StringUtils.isNotBlank(runtime.getErrorCode())) {
            return runtime.getErrorCode();
        }
        return "DR_AUTHORITY_NOT_READY";
    }

    private boolean isWithinCutoverRpo(DrPlanRuntimeVO runtime) {
        return runtime != null
                && !runtime.isRpoOverdue()
                && StringUtils.equalsAny(runtime.getFreshnessState(), "WITHIN_RPO", "RPO_DUE_SOON");
    }
}
