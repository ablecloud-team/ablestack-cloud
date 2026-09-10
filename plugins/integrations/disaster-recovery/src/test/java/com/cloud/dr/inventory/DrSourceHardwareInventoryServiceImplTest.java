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

package com.cloud.dr.inventory;

import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dr.DrConstants;
import com.cloud.dr.DrPlanVO;
import com.cloud.dr.DrResolvedSiteCredential;
import com.cloud.dr.DrSiteCredentialService;
import com.cloud.dr.DrSiteCredentialVO;
import com.cloud.dr.DrSiteVO;
import com.cloud.dr.dao.DrSiteDao;
import com.google.gson.JsonObject;

@RunWith(MockitoJUnitRunner.class)
public class DrSourceHardwareInventoryServiceImplTest {
    @Mock private DrSiteDao drSiteDao;
    @Mock private DrSiteCredentialService drSiteCredentialService;
    @Mock private DrMoldInventoryClient drMoldInventoryClient;

    @InjectMocks
    private DrSourceHardwareInventoryServiceImpl service;

    @Test
    public void remoteKvmSourceDefaultsMissingUefiMetadataToBiosAndPreservesHostAuthority() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("source-vm-uuid");
        DrSiteVO site = new DrSiteVO("source", "MOLD_KVM", "KVM");
        DrSiteCredentialVO credential = new DrSiteCredentialVO(1L, DrConstants.CREDENTIAL_TYPE_MOLD_API);
        JsonObject secrets = new JsonObject();
        secrets.addProperty("apiKey", "test-api-key");
        DrResolvedSiteCredential resolved = new DrResolvedSiteCredential(credential, secrets);
        Map<String, String> inventory = new HashMap<String, String>();
        inventory.put("sourceHostUuid", "source-host-uuid");
        inventory.put("sourceHostName", "ablecube13-1");
        inventory.put("instanceName", "i-2-13-VM");
        inventory.put("vmDetail.tpmversion", "NONE");
        inventory.put("vmDetail.io.policy", "io_uring");
        inventory.put("vmDetail.clone.fast.status", "running");
        inventory.put("vmDetail.dr.plan.id", "transient-plan-id");

        Mockito.when(drSiteDao.findById(1L)).thenReturn(site);
        Mockito.when(drSiteCredentialService.resolveCredential(site)).thenReturn(resolved);
        Mockito.when(drMoldInventoryClient.getVirtualMachineHardware(resolved, "source-vm-uuid"))
                .thenReturn(inventory);

        DrSourceVmHardware hardware = service.resolve(plan);
        JsonObject json = hardware.toJsonObject();

        Assert.assertTrue(hardware.isComplete());
        Assert.assertEquals("BIOS", json.get("firmware").getAsString());
        Assert.assertFalse(json.get("secureBoot").getAsBoolean());
        Assert.assertEquals("source-host-uuid", json.get("sourceHostUuid").getAsString());
        Assert.assertEquals("ablecube13-1", json.get("sourceHostName").getAsString());
        Assert.assertEquals("NONE", json.getAsJsonObject("vmDetails").get("tpmversion").getAsString());
        Assert.assertEquals("io_uring", json.getAsJsonObject("vmDetails").get("io.policy").getAsString());
        Assert.assertFalse(json.getAsJsonObject("vmDetails").has("clone.fast.status"));
        Assert.assertFalse(json.getAsJsonObject("vmDetails").has("dr.plan.id"));
        Assert.assertEquals(DrConstants.ERROR_SOURCE_CLONE_FLATTEN_ACTIVE,
                json.get("operationBlockerCode").getAsString());
        Assert.assertFalse(json.has("errorCode"));
    }

    @Test
    public void remoteKvmFingerprintIgnoresTransientLifecycleDetails() {
        DrPlanVO plan = new DrPlanVO("plan", 1L, 2L, DrConstants.DIRECTION_KVM_TO_KVM);
        plan.setSourceExternalRef("source-vm-uuid");
        DrSiteVO site = new DrSiteVO("source", "MOLD_KVM", "KVM");
        DrSiteCredentialVO credential = new DrSiteCredentialVO(1L, DrConstants.CREDENTIAL_TYPE_MOLD_API);
        JsonObject secrets = new JsonObject();
        secrets.addProperty("apiKey", "test-api-key");
        DrResolvedSiteCredential firstResolved = new DrResolvedSiteCredential(credential, secrets.deepCopy());
        DrResolvedSiteCredential secondResolved = new DrResolvedSiteCredential(credential, secrets.deepCopy());
        Map<String, String> first = new HashMap<String, String>();
        first.put("sourceHostUuid", "source-host-uuid");
        first.put("instanceName", "i-2-13-VM");
        first.put("vmDetail.UEFI", "LEGACY");
        first.put("vmDetail.io.policy", "io_uring");
        first.put("vmDetail.clone.fast.status", "pending");
        Map<String, String> second = new HashMap<String, String>(first);
        second.put("vmDetail.clone.fast.status", "running");
        second.put("vmDetail.clone.fast.flatten.progress", "42.00");

        Mockito.when(drSiteDao.findById(1L)).thenReturn(site);
        Mockito.when(drSiteCredentialService.resolveCredential(site)).thenReturn(firstResolved, secondResolved);
        Mockito.when(drMoldInventoryClient.getVirtualMachineHardware(firstResolved, "source-vm-uuid"))
                .thenReturn(first);
        Mockito.when(drMoldInventoryClient.getVirtualMachineHardware(secondResolved, "source-vm-uuid"))
                .thenReturn(second);

        String firstFingerprint = service.resolve(plan).getFingerprint();
        String secondFingerprint = service.resolve(plan).getFingerprint();

        Assert.assertEquals(firstFingerprint, secondFingerprint);
    }
}
