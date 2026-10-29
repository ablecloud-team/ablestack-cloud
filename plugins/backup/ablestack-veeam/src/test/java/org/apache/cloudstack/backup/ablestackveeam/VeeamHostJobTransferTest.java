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
package org.apache.cloudstack.backup.ablestackveeam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupSubmissionException;
import org.apache.cloudstack.backup.ThirdPartyBackupTransferBusyException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Execute the production lifecycle script in PowerShell; Veeam cmdlets alone are simulated. */
public class VeeamHostJobTransferTest {
    private static final String POWERSHELL = System.getProperty("veeam.powershell", "pwsh");
    private static final String TEMPLATE = "scheduled-host-job";
    private static final String HOST_JOB_ID = "22222222-2222-4222-8222-222222222222";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass public static void requirePowerShell() throws Exception {
        VeeamPowerShellScriptTransportTest.requirePowerShell();
    }

    private class Client extends AblestackVeeamSshClient {
        private final Path root = temporary.newFolder().toPath();
        private final Path statePath = root.resolve("veeam.json");

        Client() throws Exception {
            super("test", "test", "test");
            JSONObject template = new JSONObject().put("Name", TEMPLATE).put("Id", "11111111-1111-4111-8111-111111111111")
                    .put("Description", "original schedule").put("RetentionType", "RestoreDays").put("EnableSchedule", true)
                    .put("ScriptOptions", "original-hooks").put("SelectedFiles", "bootstrap");
            writeState(new JSONObject().put("jobs", new JSONArray().put(template)).put("sessions", new JSONArray())
                    .put("backups", new JSONArray()).put("starts", 0).put("copies", 0).put("detaches", 0));
        }

        JSONObject state() throws Exception { return new JSONObject(Files.readString(statePath)); }
        void writeState(JSONObject value) throws Exception { Files.writeString(statePath, value.toString()); }
        void flag(String name, boolean value) throws Exception { writeState(state().put(name, value)); }

        @Override protected Pair<Boolean, String> executePowerShellCommands(List<String> commands) {
            try {
                String stub = "$env:ProgramData='" + root.toString().replace("'", "''") + "'\n"
                        + "$statePath='" + statePath.toString().replace("'", "''") + "'\n"
                        + "$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json -AsHashtable\n"
                        + "function Save-State { $state | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $statePath }\n"
                        + "function Get-VBRComputerBackupJob { [CmdletBinding()] param() $state.jobs }\n"
                        + "function Get-VBRComputerBackupJobSession { [CmdletBinding()] param()\n"
                        + "  foreach($s in $state.sessions) { $s.CreationTime=[DateTime]::Parse([string]$s.CreationTime); $s } }\n"
                        + "function Get-VBRBackup { [CmdletBinding()] param() if($state.failQuery){throw 'catalog unavailable'}; $state.backups }\n"
                        + "function Get-VBRRestorePoint { [CmdletBinding()] param([Parameter(ValueFromPipeline=$true)]$Backup)\n"
                        + "  process { if($Backup) { @{Id=$Backup.pointId;CreationTime=[DateTime]::Parse($Backup.time)} } } }\n"
                        + "function Get-VBRDiscoveredComputer { [CmdletBinding()] param()\n"
                        + "  @{ObjectID='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';IPAddress='192.0.2.1'} }\n"
                        + "function New-VBRSelectedFilesBackupOptions { [CmdletBinding()] param($OSPlatform,[switch]$BackupSelectedFiles,$SelectedFiles,$IncludeMask)\n"
                        + "  @{files=$SelectedFiles;mask=$IncludeMask} }\n"
                        + "function New-VBRJobScriptOptions { @{} }\n"
                        + "function Copy-VBRComputerBackupJob { [CmdletBinding()] param($Job,$Name,$Description)\n"
                        + "  $copy=@{};foreach($key in $Job.Keys){$copy[$key]=$Job[$key]};$copy.Name=$Name;$copy.Description=$Description\n"
                        + "  $copy.Id='" + HOST_JOB_ID + "';$state.jobs+=@($copy);$state.copies++;Save-State }\n"
                        + "function Set-VBRComputerBackupJob { [CmdletBinding()] param($Job,$BackupObject,$BackupType,$SelectedFilesOptions,$ScriptOptions,[switch]$EnableSchedule,[switch]$UseSnapshotlessFileLevelBackup)\n"
                        + "  $Job.EnableSchedule=[bool]$EnableSchedule;$Job.ScriptOptions=$ScriptOptions\n"
                        + "  if($SelectedFilesOptions){$Job.SelectedFiles=$SelectedFilesOptions};Save-State }\n"
                        + "function Start-VBRComputerBackupJob { [CmdletBinding()] param($Job,[switch]$FullBackup,[switch]$RunAsync)\n"
                        + "  if($state.rejectStart){throw 'Start acceptance is unconfirmed'}\n"
                        + "  if(!$FullBackup -or !$RunAsync -or $Job.EnableSchedule -or $Job.ScriptOptions.Count){throw 'unsafe transfer configuration'}\n"
                        + "  $s=@{Id=[guid]::NewGuid().ToString();JobId=$Job.Id;State='Stopped';Result='Success';CreationTime=[DateTime]::UtcNow}\n"
                        + "  if($state.failBackup){$s.Result='Failed'}\n"
                        + "  $state.sessions+=@($s);$state.starts++\n"
                        + "  $state.backups+=@(@{Id=[guid]::NewGuid().ToString();Name=$Job.Name;JobId=$Job.Id;pointId=[guid]::NewGuid().ToString();time=[DateTime]::UtcNow.ToString('o')})\n"
                        + "  Save-State;if($state.loseStartReply){throw 'lost Start reply'};$s }\n"
                        + "function Detach-VBRBackup { [CmdletBinding(SupportsShouldProcess=$true,ConfirmImpact='High')] param($Backup,[switch]$Force)\n"
                        + "  if(!$Force -and !$PSCmdlet.ShouldContinue('Detach warning','Detach backup')){return}\n"
                        + "  if(!$PSCmdlet.ShouldProcess([string]$Backup.Id,'Detach backup')){return}\n"
                        + "  if($state.failDetach){throw 'Detach rejected'};if(!$state.noopDetach){$Backup.JobId=[guid]::Empty.ToString()}\n"
                        + "  $state.detaches++;Save-State;if($state.loseDetachReply){throw 'lost Detach reply'} }\n"
                        + "function Remove-VBRBackup { [CmdletBinding(SupportsShouldProcess=$true)] param($Backup,[switch]$FromDisk)\n"
                        + "  $state.backups=@($state.backups | Where-Object {$_.Id -ne $Backup.Id});Save-State }\n"
                        + "function Remove-VBRComputerBackupJob { throw 'Shared Job must never be removed' }\n";
                Path script = root.resolve("operation.ps1");
                Path output = root.resolve("output.txt");
                Files.writeString(script, "$ErrorActionPreference='Stop'\n" + stub + String.join("\n", commands));
                Process process = new ProcessBuilder(POWERSHELL, "-NoProfile", "-NonInteractive", "-File", script.toString())
                        .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
                if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("PowerShell timed out"); }
                return new Pair<>(process.exitValue() == 0, Files.readString(output));
            } catch (Exception e) { throw new CloudRuntimeException("PowerShell lifecycle test failed", e); }
        }
    }

    private ThirdPartyBackupManifest.Artifact artifact(Client client, String name) {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.backupUuid = "33333333-3333-4333-8333-333333333333";
        artifact.sourceHost = "ablecube1";
        artifact.path = "/staging/vm/time/" + name;
        client.prepareVolumeArtifact(TEMPLATE, artifact);
        return artifact;
    }

    private ThirdPartyBackupManifest.Artifact submit(Client client, ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
        return client.backupVolumeArtifact(TEMPLATE, "192.0.2.1", artifact, metadata);
    }

    @Test public void oneJobBacksUpAndDetachesAAndBThenDeletesOnlyA() throws Exception {
        Client client = new Client();
        assertTrue(client.volumeSourceReady(TEMPLATE, "ablecube1"));
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "root.raw"), false);
        assertTrue(a.completed);
        assertTrue(client.volumeSourceReady(TEMPLATE, "ablecube1"));
        client.retireVolumeArtifactJob(a, false);
        ThirdPartyBackupManifest.Artifact b = submit(client, artifact(client, "metadata"), true);
        client.retireVolumeArtifactJob(b, true);
        client.retireVolumeArtifactJob(a, false);
        assertEquals(a.externalJobId, b.externalJobId);
        assertEquals(HOST_JOB_ID, a.externalJobId);
        assertNotEquals(a.jobId, b.jobId);
        assertNotEquals(a.externalBackupId, b.externalBackupId);
        assertTrue(client.deleteVolumeArtifact(a, false));
        assertTrue(client.deleteVolumeArtifact(a, false));
        assertFalse(client.volumeArtifactExists(a));
        assertTrue(client.volumeArtifactExists(b));
        JSONObject state = client.state();
        assertEquals(1, state.getInt("copies"));
        assertEquals(2, state.getInt("starts"));
        assertEquals(2, state.getInt("detaches"));
        assertEquals(2, state.getJSONArray("jobs").length());
        assertEquals(b.externalBackupId, state.getJSONArray("backups").getJSONObject(0).getString("Id"));
        JSONObject schedule = state.getJSONArray("jobs").getJSONObject(0);
        assertTrue(schedule.getBoolean("EnableSchedule"));
        assertEquals("original-hooks", schedule.getString("ScriptOptions"));
        assertEquals("bootstrap", schedule.getString("SelectedFiles"));
    }

    @Test public void savedArtifactDetachSuppressesBothWarningAndConfirmationPrompts() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "a.raw"), false);
        // Confirm only governs ShouldProcess. A separate warning still needs Force.
        List<String> legacyCommit = new ArrayList<>(VeeamHostJobTransfer.commands("COMMIT", null, null, a, false, false));
        legacyCommit.replaceAll(command -> command.replace(" -Force -Confirm:$false", " -Confirm:$false"));
        Pair<Boolean, String> warning = client.executePowerShellCommands(legacyCommit);
        assertFalse(warning.second(), warning.first());
        assertTrue(warning.second(), warning.second().contains("NonInteractive"));
        try (var files = Files.list(client.root.resolve("ABLESTACK/Mold/host-job-transfer"))) {
            Path receipt = files.filter(path -> path.toString().endsWith(".json") && !path.toString().endsWith(".owner.json"))
                    .findFirst().orElseThrow();
            JSONObject saved = new JSONObject(Files.readString(receipt));
            assertEquals("DETACHING", saved.getString("phase"));
            assertEquals(a.jobId, saved.getString("jobId"));
            assertEquals(a.externalBackupId, saved.getString("backupId"));
        }
        Pair<Boolean, String> confirmation = client.executePowerShellCommands(List.of(
                "Detach-VBRBackup -Backup $state.backups[0] -Force"));
        assertFalse(confirmation.second(), confirmation.first());
        assertTrue(confirmation.second(), confirmation.second().contains("NonInteractive"));
        assertEquals(0, client.state().getInt("detaches"));

        client.retireVolumeArtifactJob(a, false);
        client.retireVolumeArtifactJob(a, false);
        assertEquals(1, client.state().getInt("starts"));
        assertEquals(1, client.state().getInt("detaches"));
        assertEquals(HOST_JOB_ID, client.state().getJSONArray("jobs").getJSONObject(1).getString("Id"));
        assertTrue(client.volumeArtifactExists(a));
    }

    @Test public void sharedJobRemainsOwnedUntilDetachIsConfirmed() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "a.raw"), false);
        ThirdPartyBackupManifest.Artifact b = artifact(client, "b.raw");
        assertThrows(ThirdPartyBackupTransferBusyException.class, () -> submit(client, b, false));
        assertThrows(ThirdPartyBackupTransferBusyException.class, () -> client.recoverVolumeArtifact(b, false));
        client.flag("noopDetach", true);
        assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(a, false));
        assertThrows(ThirdPartyBackupTransferBusyException.class, () -> submit(client, b, false));
        assertEquals(1, client.state().getInt("starts"));
        client.flag("noopDetach", false);
        client.retireVolumeArtifactJob(a, false);
        assertTrue(submit(client, b, false).completed);
        // Replaying A's commit while B owns the Job must not release B.
        client.retireVolumeArtifactJob(a, false);
        assertThrows(ThirdPartyBackupTransferBusyException.class, () -> submit(client, artifact(client, "c.raw"), false));
    }

    @Test public void lostStartReplyRecoversTheExactSessionWithoutStartingAgain() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact planned = artifact(client, "root.raw");
        client.flag("loseStartReply", true);
        assertThrows(CloudRuntimeException.class, () -> submit(client, planned, false));
        ThirdPartyBackupManifest.Artifact recovered = client.recoverVolumeArtifact(planned, false);
        assertTrue(recovered.completed);
        assertEquals(1, client.state().getInt("starts"));
        client.retireVolumeArtifactJob(recovered, false);
        assertTrue(client.volumeArtifactExists(recovered));
    }

    @Test public void lostDetachReplyRetriesWithoutDetachingAnotherBackup() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "a.raw"), false);
        client.flag("loseDetachReply", true);
        assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(a, false));
        client.retireVolumeArtifactJob(a, false);
        assertTrue(client.volumeSourceReady(TEMPLATE, "ablecube1"));
        assertEquals(1, client.state().getInt("detaches"));
    }

    @Test public void zeroOrMultipleRecoverySessionsNeverCauseAnotherSubmission() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact planned = artifact(client, "root.raw");
        client.flag("rejectStart", true);
        assertThrows(CloudRuntimeException.class, () -> submit(client, planned, false));
        assertNull(client.recoverVolumeArtifact(planned, false));
        assertNull(submit(client, planned, false));
        assertEquals(0, client.state().getInt("starts"));
        assertThrows(ThirdPartyBackupTransferBusyException.class, () -> submit(client, artifact(client, "b.raw"), false));

        Client ambiguous = new Client();
        ThirdPartyBackupManifest.Artifact other = artifact(ambiguous, "root.raw");
        ambiguous.flag("loseStartReply", true);
        assertThrows(CloudRuntimeException.class, () -> submit(ambiguous, other, false));
        JSONObject state = ambiguous.state();
        JSONObject extra = new JSONObject(state.getJSONArray("sessions").getJSONObject(0).toString())
                .put("Id", "55555555-5555-4555-8555-555555555555");
        state.getJSONArray("sessions").put(extra);
        ambiguous.writeState(state);
        assertThrows(CloudRuntimeException.class, () -> ambiguous.recoverVolumeArtifact(other, false));
        assertEquals(1, ambiguous.state().getInt("starts"));
    }

    @Test public void attachedBackupsAndUnexpiredArtifactsCannotBeErased() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "a.raw"), false);
        assertThrows(CloudRuntimeException.class, () -> client.deleteVolumeArtifact(a, false));
        client.retireVolumeArtifactJob(a, false);
        assertThrows(CloudRuntimeException.class, () -> client.deleteVolumeArtifact(a, false, true));
        client.flag("failQuery", true);
        assertThrows(CloudRuntimeException.class, () -> client.volumeArtifactExists(a));
        assertEquals(1, client.state().getJSONArray("backups").length());
    }

    @Test public void wrongContainerOrPointCannotDetachOrDeleteData() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "a.raw"), false);
        String point = a.externalId;
        a.externalId = "44444444-4444-4444-8444-444444444444";
        assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(a, false));
        a.externalId = point;
        client.retireVolumeArtifactJob(a, false);
        a.externalBackupId = "44444444-4444-4444-8444-444444444444";
        assertThrows(CloudRuntimeException.class, () -> client.deleteVolumeArtifact(a, false));
        assertEquals(1, client.state().getJSONArray("backups").length());
    }

    @Test public void failedSessionReleasesOnlyItsOwnDetachedContainer() throws Exception {
        Client client = new Client();
        client.flag("failBackup", true);
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "a.raw"), false);
        assertFalse(a.completed);
        assertTrue(a.externalTerminal);
        client.abortVolumeArtifact(a, false);
        client.abortVolumeArtifact(a, false);
        assertTrue(client.volumeSourceReady(TEMPLATE, "ablecube1"));
        assertEquals(1, client.state().getJSONArray("backups").length());
        assertEquals(1, client.state().getInt("detaches"));
        assertTrue(client.deleteVolumeArtifact(a, false));
        assertEquals(0, client.state().getJSONArray("backups").length());
    }

    @Test public void detachedCatalogAndDeletionRemainIndependentOfJobConfiguration() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "a.raw"), false);
        client.retireVolumeArtifactJob(a, false);
        JSONObject state = client.state();
        state.getJSONArray("jobs").getJSONObject(1).put("Id", "66666666-6666-4666-8666-666666666666");
        client.writeState(state);
        assertTrue(client.volumeArtifactExists(a));
        assertTrue(client.deleteVolumeArtifact(a, false));
        assertEquals(2, client.state().getJSONArray("jobs").length());
        assertEquals("66666666-6666-4666-8666-666666666666", client.state().getJSONArray("jobs").getJSONObject(1).getString("Id"));
    }

    @Test public void pointCountRetentionIsRejectedBeforeStart() throws Exception {
        Client client = new Client();
        JSONObject state = client.state();
        state.getJSONArray("jobs").getJSONObject(0).put("RetentionType", "RestorePoints");
        client.writeState(state);
        ThirdPartyBackupManifest.Artifact a = artifact(client, "a.raw");
        assertThrows(ThirdPartyBackupSubmissionException.class, () -> submit(client, a, false));
        assertThrows(ThirdPartyBackupSubmissionException.class, () -> client.recoverVolumeArtifact(a, false));
        assertEquals(0, client.state().getInt("starts"));
        assertTrue(client.volumeSourceReady(TEMPLATE, "ablecube1"));
    }
}
