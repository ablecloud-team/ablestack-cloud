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

import java.util.Set;
import java.util.HashSet;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.vm.UserVmVO;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Read-only catalog reused by list, preflight and post-boot template verification. */
public final class StorageServiceSystemVmTemplateCatalog {
    private final VMTemplateDao templates;
    public StorageServiceSystemVmTemplateCatalog(VMTemplateDao templates) { this.templates=templates; }
    public JsonObject list(UserVmVO vm,String managerVersion,String agentVersion,boolean requiresNvmeAuth) {
        VMTemplateVO source=templates.findById(vm.getTemplateId());
        Set<Long> downloaded=new HashSet<>();
        for (VMTemplateVO row:templates.listAllReadySystemVMTemplates(vm.getDataCenterId())) downloaded.add(row.getId());
        JsonArray rows=new JsonArray();
        for (VMTemplateVO target:templates.listAllSystemVMTemplates()) {
            templates.loadDetails(target);
            JsonObject result=StorageTemplateCompatibility.evaluate(target,source,target.getDetails(),downloaded.contains(target.getId()),
                    managerVersion,agentVersion,requiresNvmeAuth);
            result.addProperty("current",target.getId()==vm.getTemplateId());rows.add(result);
        }
        JsonObject result=new JsonObject();result.add("templates",rows);result.addProperty("count",rows.size());return result;
    }
    public JsonObject preflight(UserVmVO vm,long templateId,String managerVersion,String agentVersion,boolean requiresNvmeAuth) {
        VMTemplateVO target=templates.findById(templateId);
        if (target!=null) templates.loadDetails(target);
        boolean downloaded=templates.listAllReadySystemVMTemplates(vm.getDataCenterId()).stream().anyMatch(row->row.getId()==templateId);
        return StorageTemplateCompatibility.evaluate(target,templates.findById(vm.getTemplateId()),target==null?null:target.getDetails(),
                downloaded,managerVersion,agentVersion,requiresNvmeAuth);
    }
}
