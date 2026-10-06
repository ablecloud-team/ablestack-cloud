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

package org.apache.cloudstack.storage.sharedfs;

import java.util.ArrayList;
import java.util.List;
import com.cloud.offering.ServiceOffering;

/** One constraint contract for offering discovery and final SharedFS creation. */
public final class SharedFSOfferingValidator {
    private SharedFSOfferingValidator() { }
    public static List<String> reasons(ServiceOffering offering, int minimumCpu, int minimumMemory,
            boolean zoneScaling, boolean scalableTemplate, boolean supportedHypervisor) {
        List<String> reasons=new ArrayList<>();
        if (offering == null) { reasons.add("OFFERING_NOT_FOUND"); return reasons; }
        if (offering.getCpu() == null) reasons.add("FIXED_CPU_REQUIRED");
        else if (offering.getCpu() < minimumCpu) reasons.add("MINIMUM_CPU_REQUIRED");
        if (offering.getRamSize() == null) reasons.add("FIXED_MEMORY_REQUIRED");
        else if (offering.getRamSize() < minimumMemory) reasons.add("MINIMUM_MEMORY_REQUIRED");
        if (!offering.isOfferHA()) reasons.add("HA_REQUIRED");
        if (!offering.isDynamicScalingEnabled()) reasons.add("OFFERING_DYNAMIC_SCALE_REQUIRED");
        if (!zoneScaling) reasons.add("ZONE_DYNAMIC_SCALE_DISABLED");
        if (!scalableTemplate) reasons.add("SCALABLE_SYSTEMVM_TEMPLATE_REQUIRED");
        if (!supportedHypervisor) reasons.add("ONLINE_RESIZE_HYPERVISOR_REQUIRED");
        return reasons;
    }
}
