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
import java.util.LinkedHashMap;
import java.util.Map;

public class DrProtectionGroupPlanPreflight {
    private final long planId;
    private final String planUuid;
    private final String planName;
    private final String planState;
    private final String adminState;
    private final boolean eligible;
    private final String reasonCode;
    private final Map<String, String> reasonArgs;

    public DrProtectionGroupPlanPreflight(DrPlanVO plan, boolean eligible, String reasonCode,
            Map<String, String> reasonArgs) {
        this.planId = plan.getId();
        this.planUuid = plan.getUuid();
        this.planName = plan.getName();
        this.planState = plan.getState();
        this.adminState = plan.getAdminState();
        this.eligible = eligible;
        this.reasonCode = reasonCode;
        this.reasonArgs = reasonArgs == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(reasonArgs));
    }

    public long getPlanId() { return planId; }
    public String getPlanUuid() { return planUuid; }
    public String getPlanName() { return planName; }
    public String getPlanState() { return planState; }
    public String getAdminState() { return adminState; }
    public boolean isEligible() { return eligible; }
    public String getReasonCode() { return reasonCode; }
    public Map<String, String> getReasonArgs() { return reasonArgs; }
}
