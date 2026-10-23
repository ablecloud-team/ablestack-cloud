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

public class AblestackVeeamJobRetirementTest {
    private static final String BACKUP = "11111111-1111-4111-8111-111111111111";
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";
    private static final String POINT = "33333333-3333-4333-8333-333333333333";

    private static class Client extends AblestackVeeamSshClient {
        private String script;
        private Pair<Boolean, String> response = new Pair<>(true,
                "ABLESTACK_JSON:{\"retired\":true,\"externalId\":\"" + POINT + "\"}");

        Client() { super("veeam-test", "test", "test"); }

        @Override protected Pair<Boolean, String> executePowerShellCommands(List<String> commands) {
            script = String.join("\n", commands);
            return response;
        }
    }

    private ThirdPartyBackupManifest.Artifact artifact() {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.backupUuid = BACKUP;
        artifact.path = "/mnt/staging/ablestack-veeam/i-2-319-VM/time/root.raw";
        artifact.jobId = SESSION;
        artifact.externalId = POINT;
        artifact.completed = true;
        return artifact;
    }

    @Test public void unfinishedOrUnconfirmedArtifactsCannotRemoveJobs() {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        artifact.completed = false;
        assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(artifact, false));
        artifact.completed = true;
        artifact.submissionPending = true;
        assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(artifact, false));
        artifact.submissionPending = false;
        artifact.externalFailure = "unconfirmed transfer";
        assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(artifact, false));
        assertNull(client.script);
    }

    @Test public void missingOrMalformedIdentityNeverContactsVeeam() {
        for (String field : List.of("backup", "session", "point")) {
            Client client = new Client();
            ThirdPartyBackupManifest.Artifact artifact = artifact();
            if ("backup".equals(field)) { artifact.backupUuid = "parent-policy"; }
            if ("session".equals(field)) { artifact.jobId = null; }
            if ("point".equals(field)) { artifact.externalId = "latest"; }
            assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(artifact, false));
            assertNull(client.script);
        }
    }

    @Test public void retirementRemovesOnlyConfigurationAndVerifiesThePointBeforeAndAfter() {
        Client client = new Client();
        client.retireVolumeArtifactJob(artifact(), false);
        String script = client.script;
        assertTrue(script.contains("Remove-VBRComputerBackupJob -Job $job"));
        assertFalse(script.contains("Remove-VBRBackup "));
        assertFalse(script.contains("Remove-VBRRestorePoint"));
        assertFalse(script.contains("Remove-Item"));
        assertTrue(script.contains("$before=Read-OwnedBackup"));
        assertTrue(script.contains("$after=Read-OwnedBackup"));
        assertTrue(script.indexOf("$before=Read-OwnedBackup") < script.indexOf("Remove-VBRComputerBackupJob"));
        assertTrue(script.indexOf("$after=Read-OwnedBackup") > script.indexOf("Remove-VBRComputerBackupJob"));
        assertTrue(script.contains("$points.Count -ne 1"));
        assertTrue(script.contains("$sessions.Count -ne 1"));
        assertTrue(script.contains(SESSION));
        assertTrue(script.contains("'Stopped'"));
        assertTrue(script.contains("'Success'"));
        assertTrue(script.contains("Mold logical backup " + BACKUP));
        assertTrue(script.contains("Retained backup belongs to another Job"));
    }

    @Test public void metadataRetiresItsOwnConfigurationWithoutRemovingTheHostJob() {
        Client client = new Client();
        client.retireVolumeArtifactJob(artifact(), true);
        assertTrue(client.script.contains("$name='ABLESTACK-" + BACKUP + "-metadata'"));
        assertFalse(client.script.contains("Copy-VBRComputerBackupJob"));
        assertFalse(client.script.contains("Start-VBRComputerBackupJob"));
    }

    @Test public void wrongMissingAndNegativeServerProofCannotAuthorizeAcknowledgment() {
        for (String payload : List.of("{}", "{\"retired\":false}",
                "{\"retired\":true,\"externalId\":\"other-point\"}")) {
            Client client = new Client();
            client.response = new Pair<>(true, "ABLESTACK_JSON:" + payload);
            assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(artifact(), false));
        }
        Client client = new Client();
        client.response = new Pair<>(false, "Retained backup belongs to another Job");
        assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(artifact(), false));
    }

    @Test public void repeatedRetirementChecksPreservedCatalogWithoutSubmittingBackup() {
        Client client = new Client();
        client.retireVolumeArtifactJob(artifact(), false);
        String first = client.script;
        client.retireVolumeArtifactJob(artifact(), false);
        assertEquals(first, client.script);
        assertTrue(client.script.contains("if ($jobs.Count -eq 1) {"));
        assertFalse(client.script.contains("Start-VBRComputerBackupJob"));
    }
}
