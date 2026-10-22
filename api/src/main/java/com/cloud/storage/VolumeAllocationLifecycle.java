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
package com.cloud.storage;

import java.util.Map;
import java.util.Objects;

import com.cloud.exception.InvalidParameterValueException;

/** Guards allocation intent independently of offering identity and historical observations. */
public final class VolumeAllocationLifecycle {
    private VolumeAllocationLifecycle() { }

    public static Map<String, String> intent(Map<String, String> details) {
        Map<String, String> result = VolumeAllocationPolicy.validateStored(details);
        if (result.isEmpty() && details != null && details.keySet().stream().anyMatch(k -> k.startsWith("allocation."))) {
            throw new InvalidParameterValueException("ALLOCATION_CONTRACT_INVALID: allocation details without complete immutable intent");
        }
        return result;
    }

    /** Changing an offering never performs an allocation conversion. */
    public static void requireCompatibleOffering(Map<String, String> sourceDetails, Map<String, String> targetDetails,
            Storage.ProvisioningType sourceLegacy, Storage.ProvisioningType targetLegacy) {
        Map<String, String> source = intent(sourceDetails);
        Map<String, String> target = intent(targetDetails);
        if (!source.equals(target) || (source.isEmpty() && !Objects.equals(sourceLegacy, targetLegacy))) {
            throw new InvalidParameterValueException("ALLOCATION_CONVERSION_UNSUPPORTED: changing an offering cannot convert allocation policy or image format; source and evidence are preserved");
        }
    }

    /** Until a backend resize adapter is qualified, explicit contracts allow only unchanged logical size. */
    public static void requireMetadataOnly(Map<String, String> details, long currentSize, long newSize, String actualFormat) {
        if (intent(details).isEmpty()) {
            return; // Preserve the established legacy resize path, including ordinary Thin expansion.
        }
        if (currentSize != newSize) {
            VolumeAllocationPolicy.requireUnqualifiedLifecycle(details, "physical resize");
        }
        if (actualFormat == null || !"verified".equals(VolumeAllocationPolicy.describe(details, null, actualFormat).get("evidence"))) {
            throw new InvalidParameterValueException("ALLOCATION_EVIDENCE_REQUIRED: metadata-only changes require verified creation/copy evidence");
        }
    }
}
