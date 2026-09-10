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

import org.apache.commons.lang3.StringUtils;

import com.cloud.utils.component.ManagerBase;

public class DrFailbackDataGateServiceImpl extends ManagerBase implements DrFailbackDataGateService {
    @Override
    public DrFailbackDataGateResult validate(DrPlanVO plan, DrRunVO run, DrFailbackSessionVO session) {
        if (plan == null || run == null || session == null) {
            return DrFailbackDataGateResult.blocked("DR_FAILBACK_DATA_EVIDENCE_MISSING",
                    "Failback replication evidence is unavailable");
        }
        DrFailbackDataEvidence evidence = DrFailbackDataEvidence.from(session);
        if (!evidence.isComplete()) {
            return DrFailbackDataGateResult.blocked("DR_FAILBACK_DATA_EVIDENCE_INCOMPLETE",
                    "Durable reverse-data evidence is still being published: "
                            + StringUtils.join(evidence.getMissingFields(), ','));
        }
        DrFailbackRouteContract expectedRoute = DrFailbackRouteContract.forPlan(plan);
        DrFailbackRouteContract actualRoute = DrFailbackRouteContract.normalize(
                session.getReplicationDirection(), session.getProviderPair(), null);
        if (!actualRoute.directionMatches(expectedRoute)) {
            return DrFailbackDataGateResult.blocked("DR_FAILBACK_ROUTE_DIRECTION_INVALID",
                    "Failback reverse checkpoint direction does not match the DR plan");
        }
        if (!actualRoute.providerPairMatches(expectedRoute)) {
            return DrFailbackDataGateResult.blocked("DR_FAILBACK_ROUTE_PROVIDER_INVALID",
                    "Failback reverse checkpoint provider path does not match the DR plan");
        }
        if (session.getBaselineGeneration() < 1
                || !StringUtils.equals(session.getBaselineState(), "LOCAL_DURABLE")
                || !StringUtils.equals(session.getTrackerState(), "LOCAL_DURABLE")) {
            return DrFailbackDataGateResult.blocked("DR_FAILBACK_BASELINE_NOT_DURABLE",
                    "The reverse replication baseline is not durable");
        }
        if (!StringUtils.equals(session.getWriterState(), "DURABLE")
                || !Boolean.TRUE.equals(session.getTargetWritten())
                || !Boolean.TRUE.equals(session.getWriteVerified())) {
            return DrFailbackDataGateResult.blocked("DR_FAILBACK_TARGET_WRITE_UNVERIFIED",
                    "Failback target writes were not durably verified");
        }
        if (!isGuestCompatibilityReady(expectedRoute, session.getGuestCompatibilityState())) {
            return DrFailbackDataGateResult.blocked("DR_FAILBACK_GUEST_COMPATIBILITY_NOT_READY",
                    guestCompatibilityError(expectedRoute));
        }
        return DrFailbackDataGateResult.ready();
    }

    private boolean isGuestCompatibilityReady(DrFailbackRouteContract expectedRoute, String state) {
        if (StringUtils.equals(expectedRoute.getProviderPair(), DrConstants.PROVIDER_PAIR_ABLESTACK_TO_ABLESTACK)) {
            return StringUtils.equals(state, "NATIVE_COMPATIBILITY_PRESERVED");
        }
        if (StringUtils.equals(expectedRoute.getProviderPair(), DrConstants.PROVIDER_PAIR_ABLESTACK_TO_VMWARE)) {
            return StringUtils.equalsAny(state, "ORIGINAL_VMWARE_COMPATIBILITY_PRESERVED", "READY");
        }
        return StringUtils.equals(state, "READY");
    }

    private String guestCompatibilityError(DrFailbackRouteContract expectedRoute) {
        if (StringUtils.equals(expectedRoute.getProviderPair(), DrConstants.PROVIDER_PAIR_ABLESTACK_TO_ABLESTACK)) {
            return "The guest native compatibility with ABLESTACK KVM was not preserved";
        }
        if (StringUtils.equals(expectedRoute.getProviderPair(), DrConstants.PROVIDER_PAIR_ABLESTACK_TO_VMWARE)) {
            return "The guest is not prepared to boot on VMware";
        }
        return "The guest is not prepared for the Failback target provider";
    }
}
