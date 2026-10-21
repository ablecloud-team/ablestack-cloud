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
package com.cloud.configuration;

import java.util.HashMap;
import java.util.Map;
import com.cloud.storage.VolumeAllocationLifecycle;
import com.cloud.storage.VolumeAllocationPolicy;
import com.cloud.exception.InvalidParameterValueException;

/** Offering metadata cloning is independent from physical volume clone/restore operations. */
public final class OfferingAllocationClone {
    private OfferingAllocationClone() { }

    public static Map<String, String> details(Map<String, String> merged, Map<String, String> source,
            Map<String, String> requested, Map<String, String> parameters, boolean linked) {
        Map<String, String> oldIntent = VolumeAllocationLifecycle.intent(source);
        Map<String, String> newIntent = VolumeAllocationLifecycle.intent(requested);
        Map<String, String> result = new HashMap<>(merged);
        result.keySet().removeIf(k -> k.startsWith("allocation."));
        if (linked) {
            if (!newIntent.isEmpty()) {
                throw new InvalidParameterValueException("Allocation policy belongs to the linked disk offering");
            }
            return result;
        }
        if (!newIntent.isEmpty()) {
            result.putAll(newIntent);
        } else if (parameters == null || !parameters.containsKey("provisioningtype")) {
            result.putAll(oldIntent);
        }
        return result;
    }

    public static Long sizeGiB(Long requestedGiB, Long inheritedBytes) {
        if (requestedGiB != null || inheritedBytes == null) { return requestedGiB; }
        // Zero is the stored sentinel for a custom disk or a template-sized ROOT disk.
        if (inheritedBytes == 0) { return null; }
        if (inheritedBytes < 0 || inheritedBytes % 1073741824L != 0) {
            throw new InvalidParameterValueException("Source offering size is not an exact GiB value");
        }
        return inheritedBytes / 1073741824L;
    }

    public static String provisioning(String requested, String inherited, Map<String, String> details, Map<String, String> parameters) {
        Map<String, String> intent = VolumeAllocationLifecycle.intent(details);
        if (!intent.isEmpty()) {
            return VolumeAllocationPolicy.legacyColumn(intent.get(VolumeAllocationPolicy.POLICY), intent.get(VolumeAllocationPolicy.FORMAT), null);
        }
        return parameters != null && parameters.containsKey("provisioningtype") ? requested : inherited;
    }
}
