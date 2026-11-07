// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.command.admin.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.junit.Test;

public class MigrateNetworkCmdTest {
    @Test
    public void jobAndSynchronizationUseTheSameNetwork() throws Exception {
        MigrateNetworkCmd cmd = new MigrateNetworkCmd();
        cmd.id = 42L;
        assertEquals(Long.valueOf(42), cmd.getApiResourceId());
        assertEquals(cmd.getSyncObjId(), cmd.getApiResourceId());
        assertEquals(ApiCommandResourceType.Network, cmd.getApiResourceType());
    }

    @Test
    public void resumeIsExplicitAndDefaultsToFalse() throws Exception {
        MigrateNetworkCmd cmd = new MigrateNetworkCmd();
        assertFalse(cmd.getResume());
        Field field = MigrateNetworkCmd.class.getDeclaredField("resume");
        field.setAccessible(true);
        field.set(cmd, true);
        assertTrue(cmd.getResume());
    }
}
