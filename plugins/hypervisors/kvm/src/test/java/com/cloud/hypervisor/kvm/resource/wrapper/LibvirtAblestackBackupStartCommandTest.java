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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.nio.file.Path;

import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Test;

import static org.junit.Assert.assertThrows;

public class LibvirtAblestackBackupStartCommandTest {
    private final Path script = Path.of("/scripts/thirdparty_volume_backup.py");
    private final Path directory = Path.of("/jobs/backup-1");
    private final Path plan = directory.resolve("volume-plan.json");

    @Test
    public void generatedProgressSupervisorCommandPassesThePreparedStartFence() {
        LibvirtAblestackBackupStartHelper.validateVolumeCommand(
                LibvirtAblestackVolumeBackupHelper.supervisedCommand(script, directory), plan);
    }

    @Test
    public void legacyDirectEngineCommandKeepsItsExactPlanFence() {
        LibvirtAblestackBackupStartHelper.validateVolumeCommand(
                new String[]{"python3", script.toString(), "--plan-file", plan.toString()}, plan);
    }

    @Test
    public void anotherJobsPlanIsRejectedWithAndWithoutTheSupervisor() {
        String[] supervised = LibvirtAblestackVolumeBackupHelper.supervisedCommand(script, directory);
        supervised[8] = "/jobs/backup-2/volume-plan.json";
        assertThrows(CloudRuntimeException.class,
                () -> LibvirtAblestackBackupStartHelper.validateVolumeCommand(supervised, plan));
        assertThrows(CloudRuntimeException.class,
                () -> LibvirtAblestackBackupStartHelper.validateVolumeCommand(
                        new String[]{"python3", script.toString(), "--plan-file", supervised[8]}, plan));
    }

    @Test
    public void supervisorCannotObserveAnotherJobWhileExecutingThisPlan() {
        String[] command = LibvirtAblestackVolumeBackupHelper.supervisedCommand(script, directory);
        command[3] = "/jobs/backup-2";
        assertThrows(CloudRuntimeException.class,
                () -> LibvirtAblestackBackupStartHelper.validateVolumeCommand(command, plan));
    }

    @Test
    public void supervisorAndWorkerMustBelongToTheSameScriptInstallation() {
        String[] command = LibvirtAblestackVolumeBackupHelper.supervisedCommand(script, directory);
        command[1] = "/other/ablestack_backup_progress.py";
        assertThrows(CloudRuntimeException.class,
                () -> LibvirtAblestackBackupStartHelper.validateVolumeCommand(command, plan));
    }

    @Test
    public void replacingTheEngineOrItsInterpreterCannotBypassTheFence() {
        for (int index : new int[]{0, 1, 2, 4, 5, 6, 7}) {
            String[] command = LibvirtAblestackVolumeBackupHelper.supervisedCommand(script, directory);
            command[index] = "unexpected";
            assertThrows(CloudRuntimeException.class,
                    () -> LibvirtAblestackBackupStartHelper.validateVolumeCommand(command, plan));
        }
    }

    @Test
    public void malformedCommandsAreRejectedBeforeDispatch() {
        for (String[] command : new String[][]{null, {}, {"python3"},
                {"python3", null, "--plan-file", plan.toString()},
                {"python3", "/", "--plan-file", plan.toString()},
                {"python3", "thirdparty_volume_backup.py", "--plan-file", plan.toString()}}) {
            assertThrows(CloudRuntimeException.class,
                    () -> LibvirtAblestackBackupStartHelper.validateVolumeCommand(command, plan));
        }
    }
}
