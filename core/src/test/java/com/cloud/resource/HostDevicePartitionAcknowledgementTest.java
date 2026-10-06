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
package com.cloud.resource;

import com.cloud.agent.api.UpdateHostLunDeviceCommand;
import com.cloud.agent.api.UpdateHostScsiDeviceCommand;
import com.cloud.agent.api.UpdateHostHbaDeviceCommand;
import com.cloud.agent.api.UpdateHostVhbaDeviceCommand;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HostDevicePartitionAcknowledgementTest {
    @Test public void commandsDefaultToDeniedAndPreserveExplicitAcknowledgementOnWire() throws Exception {
        Gson gson = new Gson();
        for (Class<?> type : new Class<?>[] { UpdateHostLunDeviceCommand.class, UpdateHostScsiDeviceCommand.class,
                UpdateHostHbaDeviceCommand.class, UpdateHostVhbaDeviceCommand.class }) {
            Object legacy = gson.fromJson("{}", type);
            assertFalse((Boolean) type.getMethod("isPartitionRiskAcknowledged").invoke(legacy));
            type.getMethod("setAcknowledgePartitionRisk", boolean.class).invoke(legacy, true);
            Object decoded = gson.fromJson(gson.toJson(legacy), type);
            assertTrue((Boolean) type.getMethod("isPartitionRiskAcknowledged").invoke(decoded));
        }
    }
}
