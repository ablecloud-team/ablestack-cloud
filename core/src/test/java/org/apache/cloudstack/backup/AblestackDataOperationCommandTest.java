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

import com.cloud.agent.api.ReadyCommand;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AblestackDataOperationCommandTest {
    private final Gson gson = new Gson();

    private List<AblestackDataOperationCommand> commands() {
        return List.of(new AblestackNasTakeBackupCommand("vm", "backup"),
                new AblestackCommvaultTakeBackupCommand("vm", "backup"),
                new AblestackNetBackupTakeBackupCommand("vm", "backup"),
                new AblestackVeeamTakeBackupCommand("vm", "backup"),
                new AblestackNasRestoreBackupCommand(), new AblestackCommvaultRestoreBackupCommand(),
                new AblestackNetBackupRestoreBackupCommand(), new AblestackVeeamRestoreBackupCommand());
    }

    @Test
    public void detachedStartUsesCommonResponseWaitWhilePreservingLegacyAgentPayload() {
        for (AblestackDataOperationCommand command : commands()) {
            command.setTimeout(43200);
            command.setWaitForCompletion(false);
            assertEquals(0, command.getResponseWait());
            var payload = gson.toJsonTree(command).getAsJsonObject();
            assertEquals(43200, payload.get("wait").getAsInt());
            assertEquals(43200, payload.get("timeout").getAsInt());
            assertFalse(payload.get("waitForCompletion").getAsBoolean());
            var recovered = gson.fromJson(payload, command.getClass());
            assertEquals(Integer.valueOf(43200), recovered.getTimeout());
            assertEquals(0, recovered.getResponseWait());
        }
    }

    @Test
    public void commandResponseOverrideCannotShortenSerializedDataExecutionBudget() {
        for (AblestackDataOperationCommand command : commands()) {
            command.setWaitForCompletion(false);
            command.setTimeout(43200);
            // AgentManager applies commands.timeout by changing the inherited wait field.
            command.setWait(30);
            var recovered = gson.fromJson(gson.toJson(command), command.getClass());
            assertEquals(Integer.valueOf(43200), recovered.getTimeout());
            assertEquals(30, recovered.getWait());
            assertEquals(0, recovered.getResponseWait());
            // The detached Java worker switches to synchronous execution after reloading the command.
            recovered.setWaitForCompletion(true);
            assertEquals(Integer.valueOf(43200), recovered.getTimeout());
        }
    }

    @Test
    public void legacyBackupAndRestorePayloadsKeepTheirOriginalTimeouts() {
        for (AblestackDataOperationCommand command : commands()) {
            var legacy = gson.fromJson("{\"wait\":21600,\"waitForCompletion\":false}", command.getClass());
            assertEquals(Integer.valueOf(21600), legacy.getTimeout());
            assertFalse(legacy.isWaitForCompletion());
            var legacyRestore = gson.fromJson("{\"timeout\":43200,\"wait\":30,\"waitForCompletion\":false}", command.getClass());
            assertEquals(Integer.valueOf(43200), legacyRestore.getTimeout());
        }
    }

    @Test
    public void nasSourceOwnershipBindsDataTimeoutRatherThanResponseWait() {
        var command = new AblestackNasTakeBackupCommand("vm", "backup");
        command.setWait(43200);
        String legacyDigest = command.sourceRequestDigest();
        command.setTimeout(43200);
        command.setWaitForCompletion(false);
        command.setWait(30);
        assertEquals(legacyDigest, command.sourceRequestDigest());
        var recovered = gson.fromJson(gson.toJson(command), AblestackNasTakeBackupCommand.class);
        assertEquals(legacyDigest, recovered.sourceRequestDigest());
    }

    @Test
    public void synchronousDataDeletionAndUnrelatedCommandsKeepTheirResponseWaits() {
        var delete = new AblestackDeleteBackupCommand("backup", null, null, null, false);
        delete.setTimeout(43200);
        assertTrue(delete.isWaitForCompletion());
        assertEquals(43200, delete.getResponseWait());
        delete.setWait(1800);
        assertEquals(Integer.valueOf(43200), delete.getTimeout());
        assertEquals(1800, delete.getResponseWait());
        var legacyDelete = gson.fromJson("{\"wait\":43200}", AblestackDeleteBackupCommand.class);
        assertTrue(legacyDelete.isWaitForCompletion());
        assertEquals(43200, legacyDelete.getResponseWait());
        var ready = new ReadyCommand();
        ready.setWait(60);
        assertEquals(60, ready.getResponseWait());
    }
}
