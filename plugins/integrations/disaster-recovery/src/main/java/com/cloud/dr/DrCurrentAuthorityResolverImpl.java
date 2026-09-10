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

import com.cloud.dr.dao.DrCutoverSessionDao;
import com.cloud.dr.dao.DrFailbackSessionDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.utils.component.ManagerBase;

public class DrCurrentAuthorityResolverImpl extends ManagerBase implements DrCurrentAuthorityResolver {
    @Inject private DrCutoverSessionDao drCutoverSessionDao;
    @Inject private DrFailbackSessionDao drFailbackSessionDao;
    @Inject private DrRunDao drRunDao;
    @Inject private DrProtectionAuthorityService drProtectionAuthorityService;

    @Override
    public DrCurrentAuthorityProjection resolve(DrPlanVO plan) {
        String side = StringUtils.equalsIgnoreCase(plan.getActiveSide(), DrConstants.AUTHORITY_SIDE_TARGET)
                ? DrConstants.AUTHORITY_SIDE_TARGET : DrConstants.AUTHORITY_SIDE_SOURCE;
        DrCutoverSessionVO current = drCutoverSessionDao != null
                ? drCutoverSessionDao.findCurrentAuthorityByPlanId(plan.getId()) : null;
        DrProtectionAuthoritySnapshot runtimeAuthority = drProtectionAuthorityService != null
                ? drProtectionAuthorityService.getAuthority(plan.getId()) : null;
        Long sequence = current != null && current.getCloudAuthorityGeneration() != null
                ? current.getCloudAuthorityGeneration()
                : runtimeAuthority != null ? runtimeAuthority.getAuthoritySequence() : null;

        if (StringUtils.equals(side, DrConstants.AUTHORITY_SIDE_SOURCE)) {
            boolean staleTargetAuthority = current != null
                    && StringUtils.equalsIgnoreCase(current.getCloudPromotionState(), "PROMOTED");
            DrFailbackSessionVO failback = drFailbackSessionDao != null
                    ? drFailbackSessionDao.findLatestActiveByPlanId(plan.getId()) : null;
            boolean convergingFailback = failback != null
                    && StringUtils.equalsAnyIgnoreCase(failback.getState(),
                            "AUTHORITY_COMMITTING", "COMMIT_VERIFYING", "PROTECTION_RESUMING");
            DrRunVO failbackRun = convergingFailback && drRunDao != null
                    ? drRunDao.findById(failback.getRunId()) : null;
            boolean recognizedTransition = staleTargetAuthority && convergingFailback
                    && failbackRun != null && failbackRun.getCompleted() == null
                    && StringUtils.equalsIgnoreCase(failbackRun.getRunType(), DrConstants.RUN_TYPE_FAILBACK);
            return new DrCurrentAuthorityProjection(side,
                    recognizedTransition ? "FAILBACK_" + failback.getState()
                            : StringUtils.defaultIfBlank(plan.getState(), DrConstants.PLAN_STATE_NEW),
                    sequence, !staleTargetAuthority || recognizedTransition,
                    staleTargetAuthority && !recognizedTransition
                            ? DrConstants.AUTHORITY_INCONSISTENT_STALE_CUTOVER : null,
                    staleTargetAuthority && !recognizedTransition
                            ? "A completed target-authority session was not terminalized after failback" : null,
                    null,
                    recognizedTransition ? DrConstants.RUN_TYPE_FAILBACK : null,
                    recognizedTransition ? failback.getState() : null,
                    recognizedTransition ? failbackRun.getUuid() : null,
                    recognizedTransition ? failback.getRequiredPostFailbackCheckpointSequence() : null);
        }

        boolean committed = current != null
                && StringUtils.equalsIgnoreCase(current.getCloudPromotionState(), "PROMOTED")
                && StringUtils.equalsIgnoreCase(current.getEngineAckState(), "ACKNOWLEDGED");
        boolean targetProtected = committed && isTargetProtected(runtimeAuthority);
        return new DrCurrentAuthorityProjection(side,
                targetProtected ? "TARGET_PROTECTED"
                        : committed ? "FAILED_OVER_UNPROTECTED" : "TARGET_PROMOTED_ENGINE_PENDING",
                sequence, committed,
                committed ? null : DrConstants.AUTHORITY_INCONSISTENT_TARGET_SESSION_MISSING,
                committed ? null : "Target authority is active without a committed current cutover session",
                current);
    }

    private boolean isTargetProtected(DrProtectionAuthoritySnapshot authority) {
        return authority != null
                && StringUtils.equalsIgnoreCase(authority.getProtectionState(), DrConstants.PLAN_STATE_READY)
                && StringUtils.equalsIgnoreCase(authority.getSchedulerState(), "RUNNING")
                && StringUtils.equalsIgnoreCase(authority.getSchedulerHealthState(), "HEALTHY")
                && authority.isSchedulerPidAlive()
                && authority.isOwnerMatched()
                && StringUtils.equalsAnyIgnoreCase(authority.getBaselineState(),
                        "LOCAL_DURABLE", "DURABLE", "COMMITTED")
                && StringUtils.isBlank(authority.getErrorCode());
    }
}
