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
package com.cloud.dr.cluster;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InvalidParameterValueException;

public class DisasterRecoveryClusterDeprecationTest {
    private final DisasterRecoveryClusterServiceImpl service = new DisasterRecoveryClusterServiceImpl();
    private Object originalValue;

    @Before
    public void saveConfig() {
        originalValue = ReflectionTestUtils.getField(
                DisasterRecoveryClusterService.DisasterRecoveryServiceEnabled, "_value");
    }

    @After
    public void restoreConfig() {
        ReflectionTestUtils.setField(
                DisasterRecoveryClusterService.DisasterRecoveryServiceEnabled, "_value", originalValue);
    }

    private void enabled(boolean enabled) {
        ReflectionTestUtils.setField(
                DisasterRecoveryClusterService.DisasterRecoveryServiceEnabled, "_value", enabled);
    }

    @Test
    public void disabledServiceRegistersNoCommands() {
        enabled(false);
        Assert.assertTrue(service.getCommands().isEmpty());
    }

    @Test
    public void enabledServiceRegistersOnlyCurrentDrCommands() {
        enabled(true);
        List<String> commands = service.getCommands().stream()
                .map(Class::getSimpleName).collect(Collectors.toList());
        for (String expected : List.of("CreateDrSiteCmd", "ListDrSitesCmd", "CreateDrPlanCmd",
                "ListDrPlansCmd", "StartDrSyncCmd", "StartDrFailoverCmd", "StartDrFailbackCmd",
                "ReleaseDrProtectionCmd", "DeleteDrPlanCmd", "StartDrProtectionGroupActionCmd")) {
            Assert.assertTrue(expected, commands.contains(expected));
        }
        Assert.assertFalse(commands.contains("ListScvmIpAddressCmd"));
        Assert.assertTrue(commands.stream().noneMatch(name -> name.contains("DisasterRecoveryCluster")));
    }

    @Test
    public void allLegacyEntrypointsRejectBeforeAccessingDependencies() throws Exception {
        // Deliberately no DAOs, clients or command objects: touching them would fail this test.
        for (boolean enabled : new boolean[] {false, true}) {
            enabled(enabled);
            for (Method method : DisasterRecoveryClusterService.class.getDeclaredMethods()) {
                Object argument = method.getParameterTypes()[0] == long.class ? 1L : null;
                try {
                    method.invoke(service, argument);
                    Assert.fail("Legacy operation accepted: " + method.getName());
                } catch (InvocationTargetException e) {
                    Assert.assertTrue(method.getName(), e.getCause() instanceof InvalidParameterValueException);
                    Assert.assertTrue(e.getCause().getMessage().startsWith("LEGACY_DR_CLUSTER_DEPRECATED:"));
                }
            }
        }
    }
}
