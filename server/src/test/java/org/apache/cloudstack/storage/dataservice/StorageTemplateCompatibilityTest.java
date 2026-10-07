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
import java.util.HashMap;
import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import com.cloud.cpu.CPU;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.hypervisor.Hypervisor;
import com.google.gson.JsonObject;

public class StorageTemplateCompatibilityTest {
    private Map<String,String> metadata() {
        Map<String,String> details=new HashMap<>();
        details.put("storage.service.template","true");details.put("storage.service.template.version","test-1");
        details.put("storage.service.runtime.abi","1");details.put("storage.service.desired.state.schema","1");
        details.put("storage.service.identity.capsule.schema","1");
        details.put("storage.service.upgrade.min.manager.version","4.23.0");details.put("storage.service.upgrade.min.agent.version","4.23.0");
        return details;
    }
    private VMTemplateVO template() {
        VMTemplateVO template=Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.getTemplateType()).thenReturn(Storage.TemplateType.SYSTEM);
        Mockito.when(template.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        Mockito.when(template.getArch()).thenReturn(CPU.CPUArch.amd64);
        return template;
    }
    @Test public void explicitCompatibleSystemTemplateDoesNotRequireANewerNumericId() {
        Assert.assertTrue(StorageTemplateCompatibility.evaluate(template(),template(),metadata(),true,
                "4.23.0.0-Mold.Europa-test","4.23.1",false).get("compatible").getAsBoolean());
    }
    @Test public void newestTemplateWithoutCapabilitiesIsNeverAutomaticallyAccepted() {
        JsonObject result=StorageTemplateCompatibility.evaluate(template(),template(),Map.of(),true,"4.23.0","4.23.0",false);
        Assert.assertFalse(result.get("compatible").getAsBoolean());Assert.assertTrue(result.toString().contains("STORAGE_TEMPLATE_CAPABILITY_MISSING"));
    }
    @Test public void zoneDownloadAndArchitectureMustBothBeVerified() {
        VMTemplateVO target=template();Mockito.when(target.getArch()).thenReturn(CPU.CPUArch.arm64);
        JsonObject result=StorageTemplateCompatibility.evaluate(target,template(),metadata(),false,"4.23.0","4.23.0",false);
        Assert.assertTrue(result.toString().contains("ARCHITECTURE_MISMATCH"));Assert.assertTrue(result.toString().contains("TARGET_NOT_DOWNLOADED_IN_ZONE"));
    }
    @Test public void unknownAgentOrOlderManagerCannotPrepareARoot() {
        JsonObject result=StorageTemplateCompatibility.evaluate(template(),template(),metadata(),true,"4.22.9","unknown",false);
        Assert.assertTrue(result.toString().contains("MANAGER_VERSION_INCOMPATIBLE"));Assert.assertTrue(result.toString().contains("AGENT_VERSION_INCOMPATIBLE"));
    }
    @Test public void changedRuntimeOrIdentitySchemaIsRejectedBeforeTransfer() {
        Map<String,String> details=metadata();details.put("storage.service.runtime.abi","2");details.put("storage.service.identity.capsule.schema","2");
        Assert.assertFalse(StorageTemplateCompatibility.evaluate(template(),template(),details,true,"4.23.0","4.23.0",false).get("compatible").getAsBoolean());
    }
    @Test public void authEnabledServiceRequiresAnExplicitCapableTarget() {
        Map<String,String> details=metadata();
        Assert.assertFalse(StorageTemplateCompatibility.evaluate(template(),template(),details,true,"4.23.0","4.23.0",true).get("compatible").getAsBoolean());
        details.put("storage.service.nvme.target.auth","true");
        Assert.assertTrue(StorageTemplateCompatibility.evaluate(template(),template(),details,true,"4.23.0","4.23.0",true).get("compatible").getAsBoolean());
    }
}
