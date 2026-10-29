// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to You under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package com.cloud.agent.api;

import java.util.List;
import com.google.gson.Gson;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Test;
import static org.junit.Assert.*;

/** The Agent wrapper and command must be deployed from the same contract. */
public class CheckVmBackupTrackingCommandCompatibilityTest {
    @Test public void stoppedPreflightRetainsOfflineDisksOnTheWire() {
        VolumeObjectTO disk = new VolumeObjectTO(); disk.setPath("owned-source");
        CheckVmBackupTrackingCommand input = new CheckVmBackupTrackingCommand("owned-vm", true, List.of(disk));
        Gson gson = new Gson();
        CheckVmBackupTrackingCommand decoded = gson.fromJson(gson.toJson(input), CheckVmBackupTrackingCommand.class);
        assertTrue(decoded.isStopped()); assertEquals("owned-source", decoded.getVolumes().get(0).getPath());
        assertEquals("owned-vm", decoded.getVmName()); assertTrue(decoded.executeInSequence());
    }
    @Test public void legacyRequestDoesNotPermitOfflineInspection() {
        CheckVmBackupTrackingCommand command = new Gson().fromJson("{\"vmName\":\"owned-vm\"}", CheckVmBackupTrackingCommand.class);
        assertFalse(command.isStopped()); assertNull(command.getVolumes());
    }
}
