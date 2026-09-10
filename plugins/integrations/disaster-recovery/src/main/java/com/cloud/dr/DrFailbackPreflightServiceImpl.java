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
import javax.inject.Provider;

import org.apache.commons.lang3.StringUtils;

import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRestorePointDao;
import com.cloud.dr.dao.DrSiteDao;
import com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter;
import com.cloud.agent.api.FtctlDrReversePreflightAnswer;
import com.cloud.utils.component.ManagerBase;

public class DrFailbackPreflightServiceImpl extends ManagerBase implements DrFailbackPreflightService {
    @Inject
    private DrPlanDao drPlanDao;
    @Inject
    private DrSiteDao drSiteDao;
    @Inject
    private DrRestorePointDao drRestorePointDao;
    @Inject
    private DrSiteCredentialService drSiteCredentialService;
    @Inject
    private DrSourceIsolationPreflightService drSourceIsolationPreflightService;
    @Inject
    private DrCurrentAuthorityResolver drCurrentAuthorityResolver;
    @Inject
    private Provider<FtctlDrUnifiedActionAdapter> ftctlDrUnifiedActionAdapterProvider;

    @Override
    public DrFailbackPreflightResult validate(long planId) {
        return validate(drPlanDao.findById(planId));
    }

    @Override
    public DrFailbackPreflightResult validate(DrPlanVO plan) {
        return validate(plan, null);
    }

    @Override
    public DrFailbackPreflightResult validate(DrPlanVO plan, DrRunVO run) {
        if (plan == null || plan.getRemoved() != null) {
            return failure(DrConstants.ERROR_PLAN_NOT_FOUND, "DR plan was not found", null, null, null);
        }

        DrSiteVO activeSite = drSiteDao.findById(plan.getTargetSiteId());
        DrSiteVO destinationSite = drSiteDao.findById(plan.getSourceSiteId());
        DrRestorePointVO checkpoint = drRestorePointDao.findLatestTargetReadyByPlanId(plan.getId());

        DrCurrentAuthorityProjection authority = drCurrentAuthorityResolver.resolve(plan);
        if (authority == null || !authority.isConsistent()
                || !StringUtils.equalsIgnoreCase(DrConstants.AUTHORITY_SIDE_TARGET,
                        authority.getAuthoritySide())) {
            return failure(DrConstants.ERROR_FAILBACK_REQUIRES_TARGET_ACTIVE,
                    "Failback requires committed TARGET authority", activeSite, destinationSite, checkpoint);
        }
        if (!siteReady(activeSite) || !siteReady(destinationSite)) {
            return failure(DrConstants.ERROR_FAILBACK_SITE_NOT_READY,
                    "Both registered DR sites must be connected before failback", activeSite, destinationSite, checkpoint);
        }
        if (!drSiteCredentialService.hasUsableCredential(activeSite)
                || !drSiteCredentialService.hasUsableCredential(destinationSite)) {
            return failure(DrConstants.ERROR_FAILBACK_CREDENTIAL_NOT_READY,
                    "Both registered DR sites require configured credentials", activeSite, destinationSite, checkpoint);
        }
        if (checkpoint == null || checkpoint.getTargetReadyAt() == null) {
            return failure(DrConstants.ERROR_FAILBACK_CHECKPOINT_NOT_READY,
                    "A durable target-ready checkpoint is required before failback",
                    activeSite, destinationSite, checkpoint);
        }
        DrSourceIsolationPreflightResult transitionPreflight =
                drSourceIsolationPreflightService.validate(plan, run, DrConstants.RUN_TYPE_FAILBACK);
        if (!transitionPreflight.isReady()) {
            return failure(transitionPreflight.getErrorCode(), transitionPreflight.getMessage(),
                    activeSite, destinationSite, checkpoint)
                    .withTransitionPreflight(transitionPreflight)
                    .appendReverseNotRun();
        }
        DrFailbackPreflightResult result = DrFailbackPreflightResult.success(
                activeSite, destinationSite, checkpoint).withTransitionPreflight(transitionPreflight);
        FtctlDrUnifiedActionAdapter adapter = ftctlDrUnifiedActionAdapterProvider != null
                ? ftctlDrUnifiedActionAdapterProvider.get() : null;
        FtctlDrReversePreflightAnswer reverse = adapter != null
                ? adapter.probeReversePreflight(plan, run) : null;
        return result.withReversePreflight(reverse);
    }

    private boolean siteReady(DrSiteVO site) {
        return site != null && site.getRemoved() == null
                && StringUtils.equals(DrConstants.HEALTH_CONNECTED, site.getHealthState());
    }

    private DrFailbackPreflightResult failure(String errorCode, String message,
            DrSiteVO activeSite, DrSiteVO destinationSite, DrRestorePointVO checkpoint) {
        return DrFailbackPreflightResult.failure(errorCode, message, activeSite, destinationSite,
                credentialState(activeSite), credentialState(destinationSite), checkpoint);
    }

    private String credentialState(DrSiteVO site) {
        return site != null && drSiteCredentialService.hasUsableCredential(site)
                ? DrConstants.CREDENTIAL_STATE_CONFIGURED : "MISSING";
    }
}
