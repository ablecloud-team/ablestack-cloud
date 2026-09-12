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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import com.cloud.dr.adapter.ftctl.FtctlDrUnifiedActionAdapter;
import com.cloud.dr.dao.DrEventDao;
import com.cloud.dr.dao.DrPlanDao;
import com.cloud.dr.dao.DrRunDao;
import com.cloud.dr.dao.DrTestSessionDao;
import com.cloud.exception.InvalidParameterValueException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DrCheckpointCleanupServiceTest {
    private DrCheckpointCleanupService service;
    private DrEventDao events;
    private DrPlanVO plan;
    private DrRunDao runs;

    private void inject(String name, Object value) throws Exception {
        Field field = DrCheckpointCleanupService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Before public void setup() throws Exception {
        service = new DrCheckpointCleanupService();
        events = mock(DrEventDao.class);
        runs = mock(DrRunDao.class);
        plan = new DrPlanVO("cleanup", 1L, 2L, "VMWARE_TO_KVM");
        DrPlanDao plans = mock(DrPlanDao.class);
        when(plans.findByUuid(plan.getUuid())).thenReturn(plan);
        inject("plans", plans);
        inject("events", events);
        inject("runs", runs);
        inject("tests", mock(DrTestSessionDao.class));
        FtctlDrUnifiedActionAdapter adapter = mock(FtctlDrUnifiedActionAdapter.class);
        when(adapter.buildCheckpointInventorySpec(plan)).thenReturn("{\"disks\": [{\"device\": \"sda\", \"provider\": \"FILE\", \"canonicalLocator\": \"file:/shared/volume\"}]}");
        inject("adapter", adapter);
        when(events.persist(any(DrEventVO.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test public void unregisterSnapshotIsIndependentAndHasNoCredentialPayload() {
        service.preserveUnregistered(plan);
        ArgumentCaptor<DrEventVO> event = ArgumentCaptor.forClass(DrEventVO.class);
        verify(events).persist(event.capture());
        assertEquals(null, event.getValue().getPlanId());
        JsonObject value = JsonParser.parseString(event.getValue().getDetailsJson()).getAsJsonObject();
        assertEquals(plan.getUuid(), value.get("planUuid").getAsString());
        assertEquals("REMOTE_CLEANUP_UNVERIFIED", value.get("state").getAsString());
        assertTrue(value.has("disks"));
        assertFalse(value.has("mappingJson"));
        assertFalse(value.has("credentials"));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void activeRunRejectsCleanup() {
        when(runs.findActiveByPlanId(plan.getId())).thenReturn(new DrRunVO(plan.getId(), "SYNC"));
        service.manage(plan.getUuid(), 2, 0, "[{}]", "cleanup", false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void policyCannotRemoveLastRecoveryPoints() {
        service.manage(plan.getUuid(), 0, 0, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void cleanupRequiresReason() {
        service.manage(plan.getUuid(), 2, 0, "[{}]", "", false);
    }
}
