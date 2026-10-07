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
import java.util.List;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.cpu.CPU;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.vm.UserVmVO;
import com.cloud.hypervisor.Hypervisor;
import com.google.gson.JsonObject;

public class StorageServiceSystemVmTemplateCatalogTest {
    private VMTemplateVO template(long id,boolean declared) {
        VMTemplateVO row=Mockito.mock(VMTemplateVO.class);
        Mockito.when(row.getId()).thenReturn(id);Mockito.when(row.getUuid()).thenReturn("template-"+id);
        Mockito.when(row.getTemplateType()).thenReturn(Storage.TemplateType.SYSTEM);
        Mockito.when(row.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        Mockito.when(row.getArch()).thenReturn(CPU.CPUArch.amd64);
        Mockito.when(row.getDetails()).thenReturn(declared?Map.of("storage.service.template","true","storage.service.template.version","verified-1",
                "storage.service.runtime.abi","1","storage.service.desired.state.schema","1","storage.service.identity.capsule.schema","1",
                "storage.service.upgrade.min.manager.version","4.23.0","storage.service.upgrade.min.agent.version","4.23.0"):Map.of());
        return row;
    }
    @Test public void newerDownloadedTemplateIsVisibleButCannotReplaceAnOlderCompatibleSelection() {
        VMTemplateDao dao=Mockito.mock(VMTemplateDao.class);
        VMTemplateVO source=template(10,false),older=template(11,true),newest=template(12,false);
        Mockito.when(dao.findById(10L)).thenReturn(source);
        Mockito.when(dao.listAllSystemVMTemplates()).thenReturn(List.of(older,newest));
        Mockito.when(dao.listAllReadySystemVMTemplates(5L)).thenReturn(List.of(older,newest));
        UserVmVO vm=Mockito.mock(UserVmVO.class);Mockito.when(vm.getTemplateId()).thenReturn(10L);Mockito.when(vm.getDataCenterId()).thenReturn(5L);
        JsonObject result=new StorageServiceSystemVmTemplateCatalog(dao).list(vm,"4.23.0","4.23.0",false);
        Assert.assertTrue(result.getAsJsonArray("templates").get(0).getAsJsonObject().get("compatible").getAsBoolean());
        Assert.assertFalse(result.getAsJsonArray("templates").get(1).getAsJsonObject().get("compatible").getAsBoolean());
        Mockito.verify(dao).loadDetails(older);Mockito.verify(dao).loadDetails(newest);
    }
    @Test public void explicitPreflightRejectsMissingZoneDownloadAndDoesNotCreateAnyVolume() {
        VMTemplateDao dao=Mockito.mock(VMTemplateDao.class);
        VMTemplateVO source=template(10,false),target=template(11,true);
        Mockito.when(dao.findById(10L)).thenReturn(source);Mockito.when(dao.findById(11L)).thenReturn(target);
        Mockito.when(dao.listAllReadySystemVMTemplates(5L)).thenReturn(List.of());
        UserVmVO vm=Mockito.mock(UserVmVO.class);Mockito.when(vm.getTemplateId()).thenReturn(10L);Mockito.when(vm.getDataCenterId()).thenReturn(5L);
        JsonObject result=new StorageServiceSystemVmTemplateCatalog(dao).preflight(vm,11,"4.23.0","4.23.0",false);
        Assert.assertFalse(result.get("compatible").getAsBoolean());Assert.assertTrue(result.toString().contains("TARGET_NOT_DOWNLOADED_IN_ZONE"));
    }
}
