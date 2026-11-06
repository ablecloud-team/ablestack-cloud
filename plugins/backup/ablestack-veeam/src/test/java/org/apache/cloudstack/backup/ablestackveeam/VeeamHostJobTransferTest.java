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
                    .put("ScheduleEnabled", true).put("UseSnapshotlessFileLevelBackup", true).put("BackupType", "SelectedFiles")
                    .put("ScriptOptions", "original-hooks").put("SelectedFiles", "bootstrap")
                    .put("SelectedFilesOptions", new JSONObject().put("BackupSelectedFiles", true).put("BackupOS", false)
                            .put("BackupPersonalFiles", false).put("SelectedFiles", new JSONArray().put("bootstrap"))
                            .put("IncludeMask", new JSONArray()).put("ExcludeMask", new JSONArray()));
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
                        + "function Get-VBRComputerBackupJob { [CmdletBinding()] param() "
                        + "if($state.snapshotJobs){foreach($j in @($state.jobs)){ $j | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable }}else{$state.jobs} }\n"
                        + "function Get-VBRComputerBackupJobSession { [CmdletBinding()] param()\n"
                        + "  foreach($s in $state.sessions) { $s.CreationTime=[DateTime]::Parse([string]$s.CreationTime); $s } }\n"
                        + "function Get-VBRBackup { [CmdletBinding()] param() if($state.failQuery){throw 'catalog unavailable'}; $state.backups }\n"
                        + "function Get-VBRRestorePoint { [CmdletBinding()] param([Parameter(ValueFromPipeline=$true)]$Backup)\n"
                        + "  process { if($Backup) { @{Id=$Backup.pointId;CreationTime=[DateTime]::Parse($Backup.time)} } } }\n"
                        + "function Get-VBRDiscoveredComputer { [CmdletBinding()] param()\n"
                        + "  @{ObjectID='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';IPAddress='192.0.2.1'} }\n"
                        + "function New-VBRSelectedFilesBackupOptions { [CmdletBinding()] param($OSPlatform,[switch]$BackupSelectedFiles,$SelectedFiles,$IncludeMask)\n"
                        + "  @{BackupSelectedFiles=[bool]$BackupSelectedFiles;BackupOS=$false;BackupPersonalFiles=$false;SelectedFiles=@($SelectedFiles);IncludeMask=@($IncludeMask | Where-Object {$null -ne $_});ExcludeMask=@()} }\n"
                        + "function New-VBRJobScriptOptions { @{} }\n"
                        + "function Copy-VBRComputerBackupJob { [CmdletBinding()] param($Job,$Name,$Description)\n"
                        + "  $copy=@{};foreach($key in $Job.Keys){$copy[$key]=$Job[$key]};$copy.Name=$Name;$copy.Description=$Description\n"
                        + "  $copy.Id='" + HOST_JOB_ID + "';$state.jobs+=@($copy);$state.copies++;Save-State }\n"
                        + "function Set-VBRComputerBackupJob { [CmdletBinding()] param($Job,$Name,$BackupObject,$BackupType,$SelectedFilesOptions,$ScriptOptions,[switch]$EnableSchedule,[switch]$UseSnapshotlessFileLevelBackup)\n"
                        + "  if($state.snapshotJobs){$Job=$Job | ConvertTo-Json -Depth 30 | ConvertFrom-Json -AsHashtable}\n"
                        + "  if($PSBoundParameters.ContainsKey('Name')){if($Name -like 'ABLESTACK-H-*' -and $state.failReset){throw 'name reset rejected'};$Job.Name=$Name}\n"
                        + "  if($PSBoundParameters.ContainsKey('EnableSchedule')){$Job.EnableSchedule=[bool]$EnableSchedule;$Job.ScheduleEnabled=[bool]$EnableSchedule}\n"
                        + "  if($PSBoundParameters.ContainsKey('ScriptOptions')){$Job.ScriptOptions=$ScriptOptions}\n"
                        + "  if($PSBoundParameters.ContainsKey('BackupType')){$Job.BackupType=$BackupType}\n"
                        + "  if($PSBoundParameters.ContainsKey('UseSnapshotlessFileLevelBackup')){$Job.UseSnapshotlessFileLevelBackup=[bool]$UseSnapshotlessFileLevelBackup}\n"
                        + "  if($SelectedFilesOptions -and !$state.noopScope){$Job.SelectedFiles=$SelectedFilesOptions;$Job.SelectedFilesOptions=$SelectedFilesOptions}\n"
                        + "  if($SelectedFilesOptions -and $state.extraScope){$Job.SelectedFilesOptions.SelectedFiles+=@('/unexpected/bootstrap')}\n"
                        + "  if($SelectedFilesOptions -and $state.wrongMask){$Job.SelectedFilesOptions.IncludeMask=@('*')}\n"
                        + "  if($SelectedFilesOptions -and $state.excludePayload){$Job.SelectedFilesOptions.ExcludeMask=@('*.raw')}\n"
                        + "  if($SelectedFilesOptions -and $state.scopeSchedule){$Job.ScheduleEnabled=$true}\n"
                        + "  if($state.snapshotJobs){for($i=0;$i -lt $state.jobs.Count;$i++){if($state.jobs[$i].Id -eq $Job.Id){$state.jobs[$i]=$Job}}};Save-State\n"
                        + "  if($Name -like 'ABLESTACK-H-*' -and $state.loseResetReply){throw 'lost name reset reply'} }\n"
                        + "function Start-VBRComputerBackupJob { [CmdletBinding()] param($Job,[switch]$FullBackup,[switch]$RunAsync)\n"
                        + "  if($state.rejectStart){throw 'Start acceptance is unconfirmed'}\n"
                        + "  if(!$FullBackup -or !$RunAsync -or $Job.EnableSchedule -or $Job.ScriptOptions.Count){throw 'unsafe transfer configuration'}\n"
                        + "  $s=@{Id=[guid]::NewGuid().ToString();JobId=$Job.Id;State='Stopped';Result='Success';CreationTime=[DateTime]::UtcNow}\n"
                        + "  if($state.failBackup){$s.Result='Failed'}\n"
                        + "  $state.sessions+=@($s);$state.starts++\n"
                        + "  $state.backups+=@(@{Id=[guid]::NewGuid().ToString();Name=$Job.Name;JobId=$Job.Id;pointId=[guid]::NewGuid().ToString();time=[DateTime]::UtcNow.ToString('o');scope=$Job.SelectedFilesOptions})\n"
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
        artifact.path = "/staging/vm/2026.10.29.15.18.00.000" + ("metadata".equals(name) ? "" : "/" + name);
        client.prepareVolumeArtifact(TEMPLATE, artifact);
        return artifact;
    }

    private ThirdPartyBackupManifest.Artifact submit(Client client, ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
        return client.backupVolumeArtifact(TEMPLATE, "192.0.2.1", artifact, metadata);
    }

    @Test public void labelsIdentifyVmCheckpointAndRoleWithinAgentNameLimit() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact root = artifact(client, "root.raw");
        root.path = root.path.replace("/vm/", "/i-2-319-VM/");
        ThirdPartyBackupManifest.Artifact data = artifact(client, "datadisk.a.raw");
        data.path = data.path.replace("/vm/", "/i-2-319-VM/");
        ThirdPartyBackupManifest.Artifact metadata = artifact(client, "metadata");
        metadata.path = metadata.path.replace("/vm/", "/i-2-319-VM/");
        String rootName = VeeamHostJobTransfer.displayName(root, false);
        String dataName = VeeamHostJobTransfer.displayName(data, false);
        String metadataName = VeeamHostJobTransfer.displayName(metadata, true);
        assertTrue(rootName, rootName.startsWith("MOLD-DATA-i-2-319-VM-ROOT-20261029-151800-"));
        assertTrue(dataName, dataName.startsWith("MOLD-DATA-i-2-319-VM-DISK-20261029-151800-"));
        assertTrue(metadataName, metadataName.startsWith("MOLD-RESTORE-i-2-319-VM-20261029-151800-"));
        assertTrue(rootName.length() <= 50);
        assertTrue(dataName.length() <= 50);
        assertTrue(metadataName.length() <= 50);
        assertEquals(metadataName, VeeamHostJobTransfer.displayName(metadata, true));
        data.path = data.path.replace("datadisk.a.raw", "datadisk.b.raw");
        assertNotEquals(dataName, VeeamHostJobTransfer.displayName(data, false));
        metadata.path = metadata.path.replace("i-2-319-VM", "a-long-VM-name-한글-'quoted'-".repeat(6));
        String longName = VeeamHostJobTransfer.displayName(metadata, true);
        assertEquals(50, longName.length());
        assertTrue(longName, longName.matches("MOLD-RESTORE-[A-Za-z0-9_-]+-[a-f0-9]{8}"));
        metadata.backupUuid = "44444444-4444-4444-8444-444444444444";
        assertNotEquals(longName, VeeamHostJobTransfer.displayName(metadata, true));
        for (ThirdPartyBackupManifest.Artifact target : List.of(root, data, metadata)) {
            boolean isMetadata = target == metadata;
            String original = isMetadata ? "/staging/%s/2026.10.29.15.18.00.000" :
                    "/staging/%s/2026.10.29.15.18.00.000/" + (target == root ? "root.raw" : "datadisk.a.raw");
            for (String vm : List.of("a".repeat(200), "한글 공백:'\"\\?*<>|;$[]()", "CON")) {
                target.path = String.format(original, vm);
                String name = VeeamHostJobTransfer.displayName(target, isMetadata);
                assertTrue(name, name.length() <= 50);
                assertTrue(name, name.matches("[A-Za-z0-9_-]+"));
                assertTrue(name, name.contains("20261029-151800"));
                assertTrue(name, name.matches(".*-[a-f0-9]{8}"));
            }
            target.path = String.format(original, "a".repeat(200) + "-first");
            String first = VeeamHostJobTransfer.displayName(target, isMetadata);
            target.path = String.format(original, "a".repeat(200) + "-second");
            assertNotEquals(first, VeeamHostJobTransfer.displayName(target, isMetadata));
        }
    }

    @Test public void renamedTransfersKeepDistinctBackupNamesAndTheSameJobId() throws Exception {
        Client client = new Client();
        String stableName = VeeamHostJobTransfer.jobName(TEMPLATE, "ablecube1");
        List<ThirdPartyBackupManifest.Artifact> results = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String file : List.of("root.raw", "datadisk.a.raw", "metadata")) {
            boolean metadata = "metadata".equals(file);
            ThirdPartyBackupManifest.Artifact result = submit(client, artifact(client, file), metadata);
            String expected = VeeamHostJobTransfer.displayName(result, metadata);
            assertEquals(expected, client.state().getJSONArray("jobs").getJSONObject(1).getString("Name"));
            assertEquals(expected, client.state().getJSONArray("backups").getJSONObject(results.size()).getString("Name"));
            client.retireVolumeArtifactJob(result, metadata);
            assertEquals(stableName, client.state().getJSONArray("jobs").getJSONObject(1).getString("Name"));
            assertEquals(HOST_JOB_ID, result.externalJobId);
            assertTrue(client.volumeArtifactExists(result));
            results.add(result);
            names.add(expected);
        }
        JSONObject state = client.state();
        assertEquals(1, state.getInt("copies"));
        assertEquals(3, state.getInt("starts"));
        assertEquals(2, state.getJSONArray("jobs").length());
        for (int i = 0; i < names.size(); i++) {
            JSONObject backup = state.getJSONArray("backups").getJSONObject(i);
            assertEquals(names.get(i), backup.getString("Name"));
            assertEquals(results.get(i).externalBackupId, backup.getString("Id"));
            assertEquals(results.get(i).externalId, backup.getString("pointId"));
        }
        assertTrue(client.deleteVolumeArtifact(results.get(0), false));
        assertTrue(client.volumeArtifactExists(results.get(1)));
        assertTrue(client.volumeArtifactExists(results.get(2)));
    }

    @Test public void refreshedJobSnapshotsBackUpEachPayloadInsteadOfTheParentBootstrap() throws Exception {
        Client client = new Client();
        client.flag("snapshotJobs", true);
        int index = 0;
        for (String file : List.of("root.raw", "datadisk.a.raw", "metadata")) {
            boolean metadata = "metadata".equals(file);
            ThirdPartyBackupManifest.Artifact source = artifact(client, file);
            ThirdPartyBackupManifest.Artifact result = submit(client, source, metadata);
            JSONObject saved = client.state().getJSONArray("backups").getJSONObject(index++);
            JSONObject scope = saved.getJSONObject("scope");
            assertEquals(metadata ? source.path : Path.of(source.path).getParent().toString(), scope.getJSONArray("SelectedFiles").getString(0));
            if (!metadata) { assertEquals(file, scope.getJSONArray("IncludeMask").getString(0)); }
            assertFalse(scope.toString().contains("bootstrap"));
            client.retireVolumeArtifactJob(result, metadata);
        }
        assertEquals("bootstrap", client.state().getJSONArray("jobs").getJSONObject(0).getString("SelectedFiles"));
    }

    @Test public void staleBootstrapSettingsAreRejectedBeforeAnyBackupStarts() throws Exception {
        for (boolean metadata : List.of(false, true)) {
            Client client = new Client();
            client.flag("snapshotJobs", true);
            client.flag("noopScope", true);
            CloudRuntimeException failure = assertThrows(CloudRuntimeException.class,
                    () -> submit(client, artifact(client, metadata ? "metadata" : "root.raw"), metadata));
            assertTrue(failure.getMessage(), failure.getMessage().contains("SelectedFiles mismatch"));
            assertEquals(0, client.state().getInt("starts"));
            assertEquals(0, client.state().getJSONArray("backups").length());
            assertFalse(client.state().getJSONArray("jobs").getJSONObject(1).getBoolean("ScheduleEnabled"));
        }
    }

    @Test public void extraPathsWrongMasksExclusionsAndSchedulesNeverStartPayloadBackups() throws Exception {
        for (String flag : List.of("extraScope", "wrongMask", "excludePayload", "scopeSchedule")) {
            Client client = new Client();
            client.flag("snapshotJobs", true);
            client.flag(flag, true);
            assertThrows(CloudRuntimeException.class, () -> submit(client, artifact(client, "root.raw"), false));
            assertEquals(flag, 0, client.state().getInt("starts"));
            assertFalse(flag, client.state().getJSONArray("jobs").getJSONObject(1).getBoolean("ScheduleEnabled"));
        }
    }

    @Test public void renamedJobLookupRejectsChangedIdsAndDuplicateOwnership() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "root.raw"), false);
        JSONObject state = client.state();
        state.getJSONArray("jobs").getJSONObject(1).put("Id", "66666666-6666-4666-8666-666666666666");
        client.writeState(state);
        assertThrows(CloudRuntimeException.class, () -> client.recoverVolumeArtifact(a, false));
        assertEquals(1, client.state().getInt("starts"));
        state.getJSONArray("jobs").getJSONObject(1).put("Id", HOST_JOB_ID);
        JSONObject duplicate = new JSONObject(state.getJSONArray("jobs").getJSONObject(1).toString()).put("Name", "duplicate");
        state.getJSONArray("jobs").put(duplicate);
        client.writeState(state);
        assertThrows(CloudRuntimeException.class, () -> client.recoverVolumeArtifact(a, false));
        assertEquals(1, client.state().getInt("starts"));
        assertEquals(1, client.state().getInt("copies"));
    }

    @Test public void legacyHostReceiptWithoutDisplayNameFinishesWithoutResubmission() throws Exception {
        Client client = new Client();
        ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "root.raw"), false);
        JSONObject state = client.state();
        state.getJSONArray("jobs").getJSONObject(1).put("Name", a.externalJobName);
        state.getJSONArray("backups").getJSONObject(0).put("Name", a.externalJobName);
        client.writeState(state);
        try (var files = Files.list(client.root.resolve("ABLESTACK/Mold/host-job-transfer"))) {
            Path receipt = files.filter(path -> path.toString().endsWith(".json") && !path.toString().endsWith(".owner.json"))
                    .findFirst().orElseThrow();
            JSONObject saved = new JSONObject(Files.readString(receipt));
            saved.remove("displayName");
            saved.remove("backupName");
            Files.writeString(receipt, saved.toString());
        }
        ThirdPartyBackupManifest.Artifact recovered = client.recoverVolumeArtifact(a, false);
        client.retireVolumeArtifactJob(recovered, false);
        assertEquals(a.externalBackupId, recovered.externalBackupId);
        assertEquals(1, client.state().getInt("starts"));
        assertEquals(1, client.state().getInt("copies"));
        assertEquals(a.externalJobName, client.state().getJSONArray("backups").getJSONObject(0).getString("Name"));
    }

    @Test public void failedOrLostNameResetRetainsOwnershipUntilTheSameJobIsConfirmed() throws Exception {
        for (String failure : List.of("failReset", "loseResetReply")) {
            Client client = new Client();
            ThirdPartyBackupManifest.Artifact a = submit(client, artifact(client, "root.raw"), false);
            ThirdPartyBackupManifest.Artifact b = artifact(client, "datadisk.a.raw");
            client.flag(failure, true);
            assertThrows(CloudRuntimeException.class, () -> client.retireVolumeArtifactJob(a, false));
            assertThrows(ThirdPartyBackupTransferBusyException.class, () -> submit(client, b, false));
            assertEquals(1, client.state().getInt("starts"));
            assertEquals(1, client.state().getInt("detaches"));
            assertTrue(client.volumeArtifactExists(a));
            client.flag(failure, false);
            client.retireVolumeArtifactJob(a, false);
            assertEquals(a.externalJobName, client.state().getJSONArray("jobs").getJSONObject(1).getString("Name"));
            ThirdPartyBackupManifest.Artifact next = submit(client, b, false);
            String currentName = client.state().getJSONArray("jobs").getJSONObject(1).getString("Name");
            client.retireVolumeArtifactJob(a, false);
            assertEquals(currentName, client.state().getJSONArray("jobs").getJSONObject(1).getString("Name"));
            assertEquals(a.externalJobId, next.externalJobId);
            assertEquals(1, client.state().getInt("detaches"));
            assertEquals(1, client.state().getInt("copies"));
        }
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
