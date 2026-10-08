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
    private final org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao downloads;
    private final java.util.function.Predicate<VMTemplateVO> privateFixture;
    public StorageServiceSystemVmTemplateCatalog(VMTemplateDao templates) { this(templates,null,target->false); }
    public StorageServiceSystemVmTemplateCatalog(VMTemplateDao templates,org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao downloads,
            java.util.function.Predicate<VMTemplateVO> privateFixture) { this.templates=templates;this.downloads=downloads;this.privateFixture=privateFixture; }
    private boolean protectedFixture(VMTemplateVO target) {return target!=null&&target.getTemplateType()==com.cloud.storage.Storage.TemplateType.USER&&privateFixture.test(target);}
    private boolean privateDownloaded(VMTemplateVO target,long zoneId) {
        if(downloads==null)return false;
        org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO row=downloads.findByTemplateZoneReady(target.getId(),zoneId);
        return row!=null&&row.getDownloadState()==com.cloud.storage.VMTemplateStorageResourceAssoc.Status.DOWNLOADED&&row.getState()==org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.State.Ready;
    }
    public JsonObject list(UserVmVO vm,String managerVersion,String agentVersion,boolean requiresNvmeAuth) {
        VMTemplateVO source=templates.findById(vm.getTemplateId());
        Set<Long> downloaded=new HashSet<>();
        for (VMTemplateVO row:templates.listAllReadySystemVMTemplates(vm.getDataCenterId())) downloaded.add(row.getId());
        JsonArray rows=new JsonArray();
        java.util.List<VMTemplateVO> candidates=new java.util.ArrayList<>(templates.listAllSystemVMTemplates());
        if(downloads!=null)for(VMTemplateVO row:templates.listByAccountId(vm.getAccountId())) {templates.loadDetails(row);if(protectedFixture(row))candidates.add(row);}
        for (VMTemplateVO target:candidates) {
            templates.loadDetails(target);
            JsonObject result=StorageTemplateCompatibility.evaluate(target,source,target.getDetails(),downloaded.contains(target.getId())||protectedFixture(target)&&privateDownloaded(target,vm.getDataCenterId()),
                    managerVersion,agentVersion,requiresNvmeAuth,protectedFixture(target));
            result.addProperty("current",target.getId()==vm.getTemplateId());rows.add(result);
        }
        JsonObject result=new JsonObject();result.add("templates",rows);result.addProperty("count",rows.size());return result;
    }
    public JsonObject preflight(UserVmVO vm,long templateId,String managerVersion,String agentVersion,boolean requiresNvmeAuth) {
        VMTemplateVO target=templates.findById(templateId);
        if (target!=null) templates.loadDetails(target);
        boolean fixture=protectedFixture(target);
        boolean downloaded=templates.listAllReadySystemVMTemplates(vm.getDataCenterId()).stream().anyMatch(row->row.getId()==templateId)||fixture&&privateDownloaded(target,vm.getDataCenterId());
        return StorageTemplateCompatibility.evaluate(target,templates.findById(vm.getTemplateId()),target==null?null:target.getDetails(),
                downloaded,managerVersion,agentVersion,requiresNvmeAuth,fixture);
    }
}
