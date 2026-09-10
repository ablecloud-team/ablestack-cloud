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

public final class DrFailbackRouteContract {
    private final String replicationDirection;
    private final String providerPair;

    private DrFailbackRouteContract(String replicationDirection, String providerPair) {
        this.replicationDirection = replicationDirection;
        this.providerPair = providerPair;
    }

    public static DrFailbackRouteContract forPlan(DrPlanVO plan) {
        if (plan == null) {
            return new DrFailbackRouteContract(null, null);
        }
        if (StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_VMWARE_TO_KVM)) {
            return new DrFailbackRouteContract(DrConstants.DIRECTION_KVM_TO_VMWARE,
                    DrConstants.PROVIDER_PAIR_ABLESTACK_TO_VMWARE);
        }
        if (StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_VMWARE)) {
            return new DrFailbackRouteContract(DrConstants.DIRECTION_VMWARE_TO_KVM,
                    DrConstants.PROVIDER_PAIR_VMWARE_TO_ABLESTACK);
        }
        if (StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_KVM_TO_KVM)) {
            return new DrFailbackRouteContract(DrConstants.DIRECTION_KVM_TO_KVM,
                    DrConstants.PROVIDER_PAIR_ABLESTACK_TO_ABLESTACK);
        }
        if (StringUtils.equalsIgnoreCase(plan.getDirection(), DrConstants.DIRECTION_VMWARE_TO_VMWARE)) {
            return new DrFailbackRouteContract(DrConstants.DIRECTION_VMWARE_TO_VMWARE,
                    DrConstants.PROVIDER_PAIR_VMWARE_TO_VMWARE);
        }
        return new DrFailbackRouteContract(null, null);
    }

    public static DrFailbackRouteContract normalize(String replicationDirection, String providerPair,
            String legacyReverseDirection) {
        String normalizedDirection = normalizeReplicationDirection(replicationDirection);
        String normalizedPair = normalizeProviderPair(providerPair);
        if (StringUtils.isBlank(normalizedDirection)) {
            normalizedDirection = normalizeReplicationDirection(legacyReverseDirection);
        }
        if (StringUtils.isBlank(normalizedPair)) {
            normalizedPair = normalizeProviderPair(legacyReverseDirection);
        }
        return new DrFailbackRouteContract(normalizedDirection, normalizedPair);
    }

    public String getReplicationDirection() {
        return replicationDirection;
    }

    public String getProviderPair() {
        return providerPair;
    }

    public boolean hasDirection() {
        return StringUtils.isNotBlank(replicationDirection);
    }

    public boolean hasProviderPair() {
        return StringUtils.isNotBlank(providerPair);
    }

    public boolean directionMatches(DrFailbackRouteContract expected) {
        return expected != null && StringUtils.equals(replicationDirection, expected.replicationDirection);
    }

    public boolean providerPairMatches(DrFailbackRouteContract expected) {
        return expected != null && StringUtils.equals(providerPair, expected.providerPair);
    }

    private static String normalizeReplicationDirection(String value) {
        String normalized = StringUtils.upperCase(StringUtils.trimToNull(value));
        if (StringUtils.equalsAny(normalized, DrConstants.DIRECTION_KVM_TO_KVM,
                DrConstants.DIRECTION_KVM_TO_VMWARE, DrConstants.DIRECTION_VMWARE_TO_KVM,
                DrConstants.DIRECTION_VMWARE_TO_VMWARE)) {
            return normalized;
        }
        if (StringUtils.equals(normalized, DrConstants.PROVIDER_PAIR_ABLESTACK_TO_VMWARE)) {
            return DrConstants.DIRECTION_KVM_TO_VMWARE;
        }
        if (StringUtils.equals(normalized, DrConstants.PROVIDER_PAIR_VMWARE_TO_ABLESTACK)) {
            return DrConstants.DIRECTION_VMWARE_TO_KVM;
        }
        if (StringUtils.equals(normalized, DrConstants.PROVIDER_PAIR_ABLESTACK_TO_ABLESTACK)) {
            return DrConstants.DIRECTION_KVM_TO_KVM;
        }
        return null;
    }

    private static String normalizeProviderPair(String value) {
        String normalized = StringUtils.upperCase(StringUtils.trimToNull(value));
        if (StringUtils.equalsAny(normalized, DrConstants.PROVIDER_PAIR_ABLESTACK_TO_ABLESTACK,
                DrConstants.PROVIDER_PAIR_ABLESTACK_TO_VMWARE,
                DrConstants.PROVIDER_PAIR_VMWARE_TO_ABLESTACK,
                DrConstants.PROVIDER_PAIR_VMWARE_TO_VMWARE)) {
            return normalized;
        }
        if (StringUtils.equals(normalized, DrConstants.DIRECTION_KVM_TO_VMWARE)) {
            return DrConstants.PROVIDER_PAIR_ABLESTACK_TO_VMWARE;
        }
        if (StringUtils.equals(normalized, DrConstants.DIRECTION_VMWARE_TO_KVM)) {
            return DrConstants.PROVIDER_PAIR_VMWARE_TO_ABLESTACK;
        }
        if (StringUtils.equals(normalized, DrConstants.DIRECTION_KVM_TO_KVM)) {
            return DrConstants.PROVIDER_PAIR_ABLESTACK_TO_ABLESTACK;
        }
        return null;
    }
}
