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
package org.apache.cloudstack.backup;

import java.util.List;

import com.cloud.storage.Storage;
import com.cloud.storage.Volume;
import com.google.gson.Gson;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class AblestackRestoreProvisioningCommandTest {
    private Volume volume(Storage.ProvisioningType type) {
        Volume volume = Mockito.mock(Volume.class);
        Mockito.when(volume.getProvisioningType()).thenReturn(type);
        return volume;
    }

    @Test
    public void allProvidersRetainMixedDestinationPoliciesWhenDetachedCommandsAreSerialized() {
        for (AblestackRestoreBackupCommand command : List.of(new AblestackNasRestoreBackupCommand(),
                new AblestackCommvaultRestoreBackupCommand(), new AblestackNetBackupRestoreBackupCommand(), new AblestackVeeamRestoreBackupCommand())) {
            command.setRestoreVolumeProvisioningTypes(List.of(volume(Storage.ProvisioningType.THIN), volume(Storage.ProvisioningType.SPARSE)),
                    List.of("/primary/root", "/primary/data"));
            AblestackRestoreBackupCommand restored = new Gson().fromJson(new Gson().toJson(command), command.getClass());
            assertEquals(Storage.ProvisioningType.THIN, restored.getRestoreVolumeProvisioningTypes().get("/primary/root"));
            assertEquals(Storage.ProvisioningType.SPARSE, restored.getRestoreVolumeProvisioningTypes().get("/primary/data"));
        }
    }

    @Test
    public void destinationPolicyIsIndependentOfTheSourceVolumeIdentity() {
        AblestackNasRestoreBackupCommand command = new AblestackNasRestoreBackupCommand();
        command.setBackupVolumesUUIDs(List.of("old-source-volume"));
        command.setRestoreVolumeProvisioningTypes(List.of(volume(Storage.ProvisioningType.SPARSE)), List.of("/new-primary/new-volume"));
        assertEquals(Storage.ProvisioningType.SPARSE, command.getRestoreVolumeProvisioningTypes().get("/new-primary/new-volume"));
        assertNull(command.getRestoreVolumeProvisioningTypes().get("old-source-volume"));
    }

    @Test
    public void ambiguousDestinationMappingsAreRejectedBeforeDispatch() {
        AblestackNasRestoreBackupCommand command = new AblestackNasRestoreBackupCommand();
        List<Volume> volumes = List.of(volume(Storage.ProvisioningType.THIN), volume(Storage.ProvisioningType.SPARSE));
        assertThrows(IllegalArgumentException.class, () -> command.setRestoreVolumeProvisioningTypes(volumes, List.of("/primary/root")));
        assertThrows(IllegalArgumentException.class, () -> command.setRestoreVolumeProvisioningTypes(volumes, List.of("/primary/root", "/primary/root")));
    }

    @Test
    public void legacyCommandsKeepAnAbsentPolicyRatherThanPretendingItWasThin() {
        assertNull(new Gson().fromJson("{}", AblestackNasRestoreBackupCommand.class).getRestoreVolumeProvisioningTypes());
    }
}
