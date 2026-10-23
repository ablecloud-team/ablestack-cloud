// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.configuration;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.Test;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.DiskOfferingVO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ConfigurationManagerDiskQosTest {
    private final ConfigurationManagerImpl manager = new ConfigurationManagerImpl();

    @Test
    public void validRatesAndBurstsAcrossAllFourGroups() {
        manager.validateDiskQos(null, 100L, 200L, 3L, 100L, 200L, 3L, 100L, 200L, 3L, 100L, 200L, 3L);
        manager.validateDiskQos(null, new Long[12]);
    }

    @Test
    public void negativeValueIsRejectedInEveryField() {
        for (int field = 0; field < 12; field++) {
            Long[] values = new Long[12];
            values[field] = -1L;
            rejected(null, values);
        }
    }

    @Test
    public void burstsRequirePositiveBaseAndDurationRequiresBurst() {
        for (int group = 0; group < 4; group++) {
            Long[] values = new Long[12];
            values[group * 3 + 1] = 100L;
            rejected(null, values);
            values[group * 3] = 200L;
            rejected(null, values);
            values[group * 3 + 1] = 0L;
            values[group * 3 + 2] = 1L;
            rejected(null, values);
        }
    }

    @Test
    public void partialUpdateUsesRetainedBaseAndBurstValues() {
        DiskOfferingVO existing = new DiskOfferingVO();
        existing.setBytesReadRate(100L);
        existing.setBytesReadRateMax(200L);
        existing.setBytesReadRateMaxLength(2L);
        Long[] patch = new Long[12];
        patch[0] = 300L;
        rejected(existing, patch);
        patch[0] = null;
        patch[1] = 50L;
        rejected(existing, patch);
        patch[1] = 0L;
        rejected(existing, patch);
        patch[2] = 0L;
        manager.validateDiskQos(existing, patch);
        patch[0] = 0L;
        manager.validateDiskQos(existing, patch);
    }

    @Test
    public void explicitZeroClearsEveryStoredOverrideAndOmissionPreservesIt() throws Exception {
        for (String methodName : Arrays.asList("setBytesRate", "setIopsRate")) {
            Method setter = ConfigurationManagerImpl.class.getDeclaredMethod(methodName, DiskOffering.class,
                    Long.class, Long.class, Long.class, Long.class, Long.class, Long.class);
            setter.setAccessible(true);
            DiskOfferingVO offering = new DiskOfferingVO();
            setter.invoke(manager, offering, 100L, 200L, 2L, 100L, 200L, 2L);
            setter.invoke(manager, offering, null, null, null, null, null, null);
            assertEquals(Long.valueOf(100), methodName.equals("setBytesRate") ? offering.getBytesReadRate() : offering.getIopsReadRate());
            setter.invoke(manager, offering, 0L, 0L, 0L, 0L, 0L, 0L);
            Long[] stored = methodName.equals("setBytesRate") ? new Long[] {
                    offering.getBytesReadRate(), offering.getBytesReadRateMax(), offering.getBytesReadRateMaxLength(),
                    offering.getBytesWriteRate(), offering.getBytesWriteRateMax(), offering.getBytesWriteRateMaxLength() } : new Long[] {
                    offering.getIopsReadRate(), offering.getIopsReadRateMax(), offering.getIopsReadRateMaxLength(),
                    offering.getIopsWriteRate(), offering.getIopsWriteRateMax(), offering.getIopsWriteRateMaxLength() };
            for (Long value : stored) {
                assertEquals(Long.valueOf(0), value);
            }
        }
    }

    private void rejected(DiskOffering existing, Long[] values) {
        try {
            manager.validateDiskQos(existing, values);
            fail("Invalid effective disk QoS must be rejected");
        } catch (InvalidParameterValueException expected) {
            // Expected before any offering persistence or agent command.
        }
    }
}
