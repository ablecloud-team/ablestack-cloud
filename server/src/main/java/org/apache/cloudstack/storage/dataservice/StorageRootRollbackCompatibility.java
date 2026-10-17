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
package org.apache.cloudstack.storage.dataservice;

import java.util.Map;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class StorageRootRollbackCompatibility {
    private StorageRootRollbackCompatibility() { }

    public static JsonObject evaluate(boolean requiresAuth, boolean requiresControllerAuth,
            JsonObject actual, Map<String, String> declared) {
        JsonObject result = new JsonObject();
        JsonArray blockers = new JsonArray();
        result.addProperty("requiresNvmeAuth", requiresAuth);
        result.addProperty("requiresNvmeControllerAuth", requiresControllerAuth);
        if (actual != null && actual.has("kernel")) result.add("previousKernel", actual.get("kernel"));
        if (requiresAuth || requiresControllerAuth) {
            if (actual != null && actual.has("dhChapSupported")) {
                if (!actual.get("dhChapSupported").getAsBoolean()) blockers.add("PREVIOUS_KERNEL_NVME_DHCHAP_UNSUPPORTED");
                if (requiresControllerAuth && (!actual.has("dhChapCtrlSupported") || !actual.get("dhChapCtrlSupported").getAsBoolean())) {
                    blockers.add("PREVIOUS_KERNEL_NVME_CONTROLLER_AUTH_UNSUPPORTED");
                }
            } else if (declared == null || !"true".equalsIgnoreCase(declared.get("storage.service.nvme.target.auth"))
                    || !"1".equals(declared.get("storage.service.runtime.abi"))
                    || declared.get("storage.service.template.version") == null) {
                blockers.add("PREVIOUS_ROOT_NVME_AUTH_CAPABILITY_UNVERIFIED");
            }
        }
        result.add("blockers", blockers);
        result.addProperty("compatible", blockers.size() == 0);
        return result;
    }
}
