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

package com.cloud.dr;

import org.junit.Test;
import org.junit.Assert;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.cloud.service.ServiceOfferingVO;

public class DrPlanTargetPlacementResolverImplTest {
    @Test public void fixedOfferingOverridesStaleDynamicRequest() {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.getCpu()).thenReturn(4);
        when(offering.getSpeed()).thenReturn(2000);
        when(offering.getRamSize()).thenReturn(8192);
        DrPlanGuidedSpec spec = new DrPlanGuidedSpec();
        spec.setTargetCpuNumber(16); spec.setTargetCpuSpeed(3000); spec.setTargetMemory(32768);
        DrResolvedTargetPlacement result = new DrResolvedTargetPlacement();
        new DrPlanTargetPlacementResolverImpl().resolveComputeSizing(offering, spec, result);
        Assert.assertEquals(Integer.valueOf(4), result.getTargetCpuNumber());
        Assert.assertEquals(Integer.valueOf(2000), result.getTargetCpuSpeed());
        Assert.assertEquals(Integer.valueOf(8192), result.getTargetMemory());
    }
    @Test public void dynamicOfferingRequiresExplicitSpeedWithoutHostFallback() {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(true);
        when(offering.getCpu()).thenReturn(null);
        when(offering.getSpeed()).thenReturn(null);
        when(offering.getRamSize()).thenReturn(null);
        DrPlanGuidedSpec spec = new DrPlanGuidedSpec();
        spec.setTargetCpuNumber(2); spec.setTargetMemory(1024);
        DrResolvedTargetPlacement result = new DrResolvedTargetPlacement();
        new DrPlanTargetPlacementResolverImpl().resolveComputeSizing(offering, spec, result);
        Assert.assertNull(result.getTargetCpuSpeed());
        Assert.assertTrue(result.getBlockingReasons().toString().contains("cpuSpeed"));
        spec.setTargetCpuSpeed(2000);
        result = new DrResolvedTargetPlacement();
        new DrPlanTargetPlacementResolverImpl().resolveComputeSizing(offering, spec, result);
        Assert.assertTrue(result.getBlockingReasons().isEmpty());
        Assert.assertEquals(Integer.valueOf(2), result.getTargetCpuNumber());
    }
    @Test public void partialOfferingUsesOnlyVariableRequestFields() {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(true);
        when(offering.getCpu()).thenReturn(null);
        when(offering.getSpeed()).thenReturn(null);
        when(offering.getRamSize()).thenReturn(null);
        when(offering.getCpu()).thenReturn(4);
        when(offering.getSpeed()).thenReturn(2000);
        DrPlanGuidedSpec spec = new DrPlanGuidedSpec();
        spec.setTargetCpuNumber(16); spec.setTargetMemory(4096);
        DrResolvedTargetPlacement result = new DrResolvedTargetPlacement();
        new DrPlanTargetPlacementResolverImpl().resolveComputeSizing(offering, spec, result);
        Assert.assertEquals(Integer.valueOf(4), result.getTargetCpuNumber());
        Assert.assertEquals(Integer.valueOf(4096), result.getTargetMemory());
        Assert.assertTrue(result.getBlockingReasons().isEmpty());
    }
    @Test public void invalidExplicitDynamicSizeIsNotAValidDraft() {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(true);
        when(offering.getCpu()).thenReturn(null);
        DrPlanGuidedSpec spec = new DrPlanGuidedSpec();
        spec.setTargetCpuNumber(-1);
        DrResolvedTargetPlacement result = new DrResolvedTargetPlacement();
        new DrPlanTargetPlacementResolverImpl().resolveComputeSizing(offering, spec, result);
        Assert.assertTrue(result.getBlockingReasons().toString().contains(DrPlanReadinessValidator.REASON_TARGET_COMPUTE_SIZE_INVALID));
    }
    @Test public void partialSizingEditPreservesOfferingSpeedDisksAndPolicies() {
        DrPlanVO current = new DrPlanVO();
        current.setMappingJson("{\"target\":{\"serviceOfferingId\":\"dynamic\",\"cpuNumber\":1,\"cpuSpeed\":2000,\"memory\":2048,\"networks\":[{\"networkId\":\"net-1\"}]},\"disks\":[{\"sourceDiskId\":\"2000\"}]}");
        current.setScheduleJson("{\"intervalSeconds\":600,\"retentionCount\":12}");
        current.setPolicyJson("{\"testBootTimeoutSeconds\":240,\"failover\":{\"powerOn\":false}}");
        DrPlanGuidedSpec changes = new DrPlanGuidedSpec();
        changes.setTargetCpuNumber(4); changes.setTargetMemory(8192);
        DrPlanGuidedSpec merged = new DrPlanGuidedSpecBuilder().mergeForUpdate(current, changes);
        Assert.assertEquals(Integer.valueOf(4), merged.getTargetCpuNumber());
        Assert.assertEquals(Integer.valueOf(8192), merged.getTargetMemory());
        Assert.assertEquals(Integer.valueOf(2000), merged.getTargetCpuSpeed());
        Assert.assertEquals("dynamic", merged.getTargetComputeRef());
        Assert.assertEquals("net-1", merged.getTargetNetworkRef());
        Assert.assertTrue(merged.getDiskMappingsJson().contains("2000"));
        Assert.assertEquals(Integer.valueOf(600), merged.getSyncIntervalSeconds());
        Assert.assertEquals(Boolean.FALSE, merged.getFailoverPowerOn());
        String json = new DrPlanGuidedSpecBuilder().preserveUnchangedJson(
                "{\"target\":{\"cpuNumber\":1,\"customEvidence\":\"keep\"}}", "{\"target\":{\"cpuNumber\":4}}");
        Assert.assertTrue(json.contains("customEvidence"));
        Assert.assertTrue(json.contains("\"cpuNumber\":4"));
    }
}
