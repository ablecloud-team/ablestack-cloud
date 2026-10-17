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
import java.util.ArrayList;
import java.util.List;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.hypervisor.Hypervisor;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

/** Explicit template capabilities are required; the newest SYSTEM template ID is never sufficient. */
public final class StorageTemplateCompatibility {
    private StorageTemplateCompatibility() { }
    public static JsonObject evaluate(VMTemplateVO target,VMTemplateVO source,Map<String,String> details,
            boolean downloaded,String managerVersion,String agentVersion,boolean requiresNvmeAuth) {
        return evaluate(target, source, details, downloaded, managerVersion, agentVersion, requiresNvmeAuth, false);
    }
    public static JsonObject evaluate(VMTemplateVO target,VMTemplateVO source,Map<String,String> details,
            boolean downloaded,String managerVersion,String agentVersion,boolean requiresNvmeAuth,boolean protectedPrivateFixture) {
        List<String> blockers=new ArrayList<>();
        if (target==null || source==null) {
            blockers.add("TEMPLATE_UNAVAILABLE");
        } else {
            if (target.getTemplateType()!=Storage.TemplateType.SYSTEM && !(protectedPrivateFixture && target.getTemplateType()==Storage.TemplateType.USER && !target.isPublicTemplate())) blockers.add("TARGET_IS_NOT_SYSTEM");
            if (target.getHypervisorType()!=Hypervisor.HypervisorType.KVM) blockers.add("TARGET_IS_NOT_KVM");
            if (target.getArch()==null || source.getArch()==null || target.getArch()!=source.getArch()) blockers.add("ARCHITECTURE_MISMATCH");
            if (!downloaded) blockers.add("TARGET_NOT_DOWNLOADED_IN_ZONE");
        }
        Map<String,String> capability=details==null?java.util.Collections.emptyMap():details;
        if (!"true".equalsIgnoreCase(capability.get("storage.service.template"))) blockers.add("STORAGE_TEMPLATE_CAPABILITY_MISSING");
        if (capability.get("storage.service.template.version")==null || capability.get("storage.service.template.version").isBlank()) blockers.add("TEMPLATE_VERSION_MISSING");
        require(capability,"storage.service.runtime.abi","1",blockers);
        require(capability,"storage.service.desired.state.schema","1",blockers);
        require(capability,"storage.service.identity.capsule.schema","1",blockers);
        minimum(capability.get("storage.service.upgrade.min.manager.version"),managerVersion,"MANAGER_VERSION_INCOMPATIBLE",blockers);
        minimum(capability.get("storage.service.upgrade.min.agent.version"),agentVersion,"AGENT_VERSION_INCOMPATIBLE",blockers);
        if (!"true".equalsIgnoreCase(capability.get("storage.service.runtime.signed.readback"))) blockers.add("SIGNED_RUNTIME_READBACK_CAPABILITY_MISSING");
        if (!"true".equalsIgnoreCase(capability.get("storage.service.template.maintenance.gate"))) blockers.add("TEMPLATE_BOOT_MAINTENANCE_CAPABILITY_MISSING");
        if (!"true".equalsIgnoreCase(capability.get("storage.service.data.identity.inspect"))) blockers.add("DATA_IDENTITY_INSPECT_CAPABILITY_MISSING");
        if (requiresNvmeAuth && !"true".equalsIgnoreCase(capability.get("storage.service.nvme.target.auth"))) blockers.add("NVME_AUTH_UNAVAILABLE");
        JsonObject result=new JsonObject();JsonArray errors=new JsonArray();blockers.forEach(errors::add);
        result.addProperty("compatible",blockers.isEmpty());result.add("blockers",errors);
        if (target!=null) {
            result.addProperty("templateUuid",target.getUuid());result.addProperty("templateName",target.getName());
        }
        result.addProperty("templateVersion",capability.get("storage.service.template.version"));
        result.addProperty("requiresNvmeAuth",requiresNvmeAuth);return result;
    }
    private static void require(Map<String,String> details,String key,String expected,List<String> errors) {
        if (!expected.equals(details.get(key))) errors.add("UNSUPPORTED_"+key.toUpperCase(java.util.Locale.ROOT).replace('.','_'));
    }
    private static void minimum(String lower,String actual,String code,List<String> errors) {
        try {
            int[] low=version(lower);int[] current=version(actual);
            for (int i=0;i<4;i++) {
                if (current[i]<low[i]) {errors.add(code);return;}
                if (current[i]>low[i]) return;
            }
        } catch (IllegalArgumentException unverified) {errors.add(code);}
    }
    private static int[] version(String value) {
        if (value==null) throw new IllegalArgumentException("Version unavailable");
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^([0-9]+)\\.([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?(?:-[A-Za-z][A-Za-z0-9_.-]*)?$").matcher(value);
        if (!match.matches()) throw new IllegalArgumentException("Version unverifiable");
        return new int[]{Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3)),
                match.group(4)==null?0:Integer.parseInt(match.group(4))};
    }
}
