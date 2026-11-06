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
package org.apache.cloudstack.api.command.user.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.CreateNetBackupResponse;
import org.apache.cloudstack.backup.BackupManager;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class CreateNetBackupCmdTest {
    @Test
    public void acceptedBackupReturnsItsExactUuidAndRetainsTheSuccessResponse() throws Exception {
        CreateNetBackupCmd command = new CreateNetBackupCmd();
        BackupManager manager = mock(BackupManager.class);
        ReflectionTestUtils.setField(command, "backupManager", manager);
        when(manager.createNetBackup(command)).thenAnswer(call -> {
            command.setCreatedBackupId("07d03010-a77d-4e72-82c7-1c6c02d767db");
            return true;
        });
        command.execute();
        CreateNetBackupResponse response = (CreateNetBackupResponse) command.getResponseObject();
        assertTrue(response.getSuccess());
        assertEquals("07d03010-a77d-4e72-82c7-1c6c02d767db", response.getBackupId());
        JsonObject json = new Gson().toJsonTree(response).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean());
        assertEquals(response.getBackupId(), json.get("backupid").getAsString());
    }

    @Test
    public void rejectionDoesNotPublishAnAcceptedBackup() throws Exception {
        CreateNetBackupCmd command = new CreateNetBackupCmd();
        BackupManager manager = mock(BackupManager.class);
        ReflectionTestUtils.setField(command, "backupManager", manager);
        when(manager.createNetBackup(command)).thenThrow(new CloudRuntimeException("source unavailable"));
        assertThrows(ServerApiException.class, command::execute);
        assertNull(command.getResponseObject());
    }
}
