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
package org.apache.cloudstack.backup.ablestackveeam;

import java.util.List;

import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.junit.Test;

import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AblestackVeeamVolumeSessionTest {
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";

    private static class Client extends AblestackVeeamSshClient {
        private String script;
        private Pair<Boolean, String> response;

        Client(String json) {
            super("veeam-test", "test", "test");
            response = new Pair<>(true, "ABLESTACK_JSON:" + json);
        }

        @Override protected Pair<Boolean, String> executePowerShellCommands(List<String> commands) {
            script = String.join("\n", commands);
            return response;
        }
    }

    private ThirdPartyBackupManifest.Artifact artifact() {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.backupUuid = "11111111-1111-4111-8111-111111111111";
        artifact.path = "/mnt/staging/ablestack-veeam/vm/time/root.raw";
        return artifact;
    }

    private void assertExactJobSessionQuery(String script) {
        assertTrue(script.contains("Get-VBRComputerBackupJobSession -ErrorAction Stop"));
        assertTrue(script.contains("[string]$_.JobId -eq [string]$job"));
        assertFalse(script.contains("Get-VBRComputerBackupJobSession -Name"));
        assertFalse(script.contains("Get-VBRComputerBackupJobSession -ErrorAction SilentlyContinue"));
    }

    @Test public void templateWithoutPreviousSessionsCanBeReady() {
        Client client = new Client("{\"ready\":true}");
        assertTrue(client.volumeSourceReady("parent-template"));
        assertExactJobSessionQuery(client.script);
        assertTrue(client.script.contains("$busy.Count -eq 0"));
    }

    @Test public void firstSubmissionCanStartWhenTheNewChildHasNoSessions() {
        Client client = new Client("{\"jobId\":\"" + SESSION + "\",\"completed\":false}");
        ThirdPartyBackupManifest.Artifact accepted = client.backupVolumeArtifact("parent-template", "192.0.2.1", artifact(), false);
        assertEquals(SESSION, accepted.jobId);
        assertFalse(accepted.completed);
        assertExactJobSessionQuery(client.script);
        assertTrue(client.script.contains("if ($sessions.Count -eq 0) { $submissionAttempted=$true;"));
        assertTrue(client.script.contains("Start-VBRComputerBackupJob -Job $job -FullBackup -RunAsync"));
    }

    @Test public void recoveryWithNoSessionRemainsUnconfirmedAndNeverSubmitsAgain() {
        Client client = new Client("{\"jobId\":null}");
        assertNull(client.recoverVolumeArtifact(artifact(), false));
        assertExactJobSessionQuery(client.script);
        assertFalse(client.script.contains("Start-VBRComputerBackupJob"));
        assertFalse(client.script.contains("Copy-VBRComputerBackupJob"));
        assertTrue(client.script.contains("if ($sessions.Count -ne 1) { throw 'Artifact recovery session is ambiguous' }"));
    }

    @Test public void pollingRequiresBothTheJobAndTheSavedSessionIdentity() {
        Client client = new Client("{\"jobId\":\"" + SESSION + "\",\"completed\":false}");
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        artifact.jobId = SESSION;
        client.backupVolumeArtifact("parent-template", "192.0.2.1", artifact, false);
        assertExactJobSessionQuery(client.script);
        assertTrue(client.script.contains("[string]$_.Id -eq $id"));
        assertTrue(client.script.contains("External artifact backup job is missing"));
        assertFalse(client.script.contains("Start-VBRComputerBackupJob"));
    }

    @Test public void queryFailureDoesNotBecomeAnEmptySuccessfulRecovery() {
        Client client = new Client("{}");
        client.response = new Pair<>(false, "Veeam session query denied");
        assertThrows(CloudRuntimeException.class, () -> client.recoverVolumeArtifact(artifact(), false));
        assertFalse(client.script.contains("Start-VBRComputerBackupJob"));
    }
}
