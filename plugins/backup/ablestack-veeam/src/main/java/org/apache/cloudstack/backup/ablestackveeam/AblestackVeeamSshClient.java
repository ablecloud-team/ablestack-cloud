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

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;

/**
 * Standalone SSH/PowerShell client for the Veeam server that performs the operations
 * which have no Veeam REST API equivalent - notably exporting restore point disks to a
 * staging directory for NAS seed creation/restore.
 *
 * <p>Unlike {@link org.apache.cloudstack.backup.veeam.VeeamClient} (and its subclass
 * {@link AblestackVeeamClient}), this client does NOT authenticate against the Veeam
 * Enterprise Manager REST API (port 9398). It only opens an SSH session (port 22) and
 * runs PowerShell. That decouples disk export from Enterprise Manager so deployments
 * using the VBR REST API (port 9419) for queries do not need EM/9398 at all.</p>
 *
 * <p>PowerShell is delivered via {@code -EncodedCommand} (Base64 of UTF-16LE) so the
 * Windows SSH default shell (cmd.exe) cannot mangle PowerShell metacharacters such as
 * {@code |}, {@code { }} and {@code >}.</p>
 */
public class AblestackVeeamSshClient {

    protected Logger logger = LogManager.getLogger(getClass());

    /**
     * Override with -Dveeam.powershell.bin=pwsh if the Veeam (v12.1+/v13) module
     * requires PowerShell 7 instead of Windows PowerShell 5.1.
     */
    private static final String POWERSHELL_BIN = System.getProperty("veeam.powershell.bin", "powershell");

    private static final int SSH_PORT = 22;
    private static final int CONNECT_TIMEOUT_MS = 120000;
    private static final int KEX_TIMEOUT_MS = 120000;
    private static final int WAIT_TIMEOUT_MS = 3600000;

    private final String host;
    private final String username;
    private final String password;
    private final boolean legacy;

    public AblestackVeeamSshClient(final String host, final String username, final String password) {
        this(host, username, password, false);
    }

    public AblestackVeeamSshClient(final String host, final String username, final String password, final boolean legacy) {
        if (StringUtils.isBlank(host)) {
            throw new CloudRuntimeException("Veeam SSH host is required for disk export");
        }
        this.host = host;
        this.username = username;
        this.password = password;
        this.legacy = legacy;
    }

    /**
     * Export all hard disks from a Veeam restore point to a directory on the Veeam server.
     * The directory must be reachable from the KVM hypervisor (shared NFS recommended).
     *
     * @return absolute paths of exported disk files on the Veeam server
     */
    public List<String> exportRestorePointDisksToStaging(final String restorePointId, final String stagingPath) {
        logger.debug(String.format("Exporting Veeam restore point [%s] to staging [%s] via SSH", restorePointId, stagingPath));
        final String escapedStaging = stagingPath.replace("'", "''");
        final String escapedId = restorePointId.replace("'", "''");
        final List<String> cmds = Arrays.asList(
                String.format("$staging = '%s'", escapedStaging),
                "New-Item -ItemType Directory -Force -Path $staging | Out-Null",
                String.format("$restorePoint = Get-VBRRestorePoint | Where-Object { $_.Id -eq '%s' -or $_.Id.Guid -eq '%s' }", escapedId, escapedId),
                "if (-not $restorePoint) { Write-Output 'Failed: restore point not found'; Exit 1 }",
                "$session = Start-VBRFLRSession -RestorePoint $restorePoint",
                "$items = Get-VBRFLRItem -Session $session",
                "$exported = @()",
                "foreach ($item in $items) {",
                "  if ($item.Type -eq 'HardDisk') {",
                "    $target = Join-Path $staging ($item.Name + '.vmdk')",
                "    Copy-VBRFLRItem -FLRSession $session -Item $item -Destination $target",
                "    $exported += $target",
                "  }",
                "}",
                "Stop-VBRFLRSession -Session $session",
                "if ($exported.Count -eq 0) { Write-Output 'Failed: no disks exported'; Exit 1 }",
                "$exported -join ','"
        );
        final Pair<Boolean, String> result = executePowerShellCommands(cmds);
        if (result == null || !result.first() || StringUtils.isBlank(result.second())) {
            throw new CloudRuntimeException(String.format("Failed to export Veeam restore point [%s] to [%s]", restorePointId, stagingPath));
        }
        if (result.second().contains("Failed:")) {
            throw new CloudRuntimeException(result.second().trim());
        }
        return Arrays.stream(result.second().trim().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Live Veeam job names including Agent (computer) backup jobs.
     * Uses SSH/PowerShell only — does not require Enterprise Manager (9398).
     */
    public List<String> listBackupJobNames() {
        final List<String> cmds = Arrays.asList(
                "$names = @()",
                "Get-VBRJob -ErrorAction SilentlyContinue | ForEach-Object { if ($_.Name) { $names += $_.Name } }",
                "Get-VBRComputerBackupJob -ErrorAction SilentlyContinue | ForEach-Object { if ($_.Name) { $names += $_.Name } }",
                "if (-not $names -or $names.Count -eq 0) { Write-Output 'NO_JOBS'; Exit 0 }",
                "$names | Sort-Object -Unique | ForEach-Object { Write-Output $_ }"
        );
        final Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response == null || !response.first()) {
            throw new CloudRuntimeException("Failed to list Veeam backup jobs over SSH");
        }
        final String payload = StringUtils.trimToEmpty(response.second());
        if (StringUtils.isBlank(payload) || payload.startsWith("NO_JOBS")) {
            return new ArrayList<>();
        }
        return Arrays.stream(payload.split("\\r?\\n"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .filter(line -> !"NO_JOBS".equalsIgnoreCase(line))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * List restore points for a VM display/object name via SSH/PowerShell.
     */
    public List<Backup.RestorePoint> listRestorePointsForVmDisplayName(final String vmDisplayName) {
        final String escapedName = vmDisplayName.replace("'", "''");
        final List<String> cmds = Arrays.asList(
                "$jobMap = @{}",
                "Get-VBRJob -ErrorAction SilentlyContinue | ForEach-Object { $jobMap[$_.Id.ToString()] = $_.Name; if ($_.Id.Guid) { $jobMap[$_.Id.Guid] = $_.Name } }",
                "Get-VBRComputerBackupJob -ErrorAction SilentlyContinue | ForEach-Object { $jobMap[$_.Id.ToString()] = $_.Name; if ($_.Id.Guid) { $jobMap[$_.Id.Guid] = $_.Name } }",
                // Host Agent jobs register under computer/job name, not guest i-*-VM names.
                String.format("$needle = '%s'; $points = Get-VBRRestorePoint | Where-Object {", escapedName),
                "  $jn = [string]$_.JobName; $nm = [string]$_.Name; $vn = [string]$_.VmName;",
                "  ($vn -eq $needle) -or ($nm -like ('*'+$needle+'*')) -or ($jn -eq $needle) -or ($jn -like ('*'+$needle+'*'))",
                "}",
                "if (-not $points) { Write-Output 'NO_RESTORE_POINTS'; Exit 0 }",
                "$points | Sort-Object CreationTime -Descending | ForEach-Object {",
                "  Write-Output $_.Id.Guid",
                "  Write-Output $_.CreationTime.ToString('yyyy-MM-ddTHH:mm:ss')",
                "  Write-Output $_.Type",
                "  $jn = [string]$_.JobName",
                "  if ([string]::IsNullOrWhiteSpace($jn) -and $_.JobId) {",
                "    $key = [string]$_.JobId",
                "    if ($jobMap.ContainsKey($key)) { $jn = $jobMap[$key] }",
                "  }",
                "  if ([string]::IsNullOrWhiteSpace($jn)) { $jn = '' }",
                "  Write-Output $jn",
                "  Write-Output '-----'",
                "}"
        );
        final Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response == null || !response.first()) {
            throw new CloudRuntimeException(String.format("Failed to list Veeam restore points for [%s] over SSH", vmDisplayName));
        }
        final String payload = StringUtils.trimToEmpty(response.second());
        if (StringUtils.isBlank(payload) || payload.startsWith("NO_RESTORE_POINTS")) {
            return new ArrayList<>();
        }
        final List<Backup.RestorePoint> restorePoints = new ArrayList<>();
        for (final String block : payload.split("-----\r\n")) {
            final String[] parts = block.trim().split("\r\n");
            if (parts.length < 3) {
                continue;
            }
            try {
                final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                final Date created = fmt.parse(parts[1].trim());
                final Backup.RestorePoint restorePoint = new Backup.RestorePoint(parts[0].trim(), created, parts[2].trim(), null, null);
                if (parts.length >= 4) {
                    restorePoint.setJobName(StringUtils.trimToNull(parts[3]));
                }
                restorePoints.add(restorePoint);
            } catch (ParseException e) {
                logger.warn("Skipping unparseable Veeam restore point block: {}", block);
            }
        }
        return restorePoints;
    }

    /**
     * NetBackup-style existence check: true if this restore-point GUID is still in Veeam.
     * Checks Get-VBRRestorePoint and Get-VBRObjectRestorePoint (Agent OibId).
     */
    public boolean restorePointExists(final String restorePointId) {
        if (StringUtils.isBlank(restorePointId)) {
            return false;
        }
        final String escapedId = restorePointId.replace("'", "''").trim().replace("{", "").replace("}", "");
        final List<String> cmds = Arrays.asList(
                "$want = '" + escapedId + "'.ToLower()",
                "function Rp-Match($rp) {",
                "  if ($null -eq $rp) { return $false }",
                "  $id = $rp.Id; if ($id -is [guid]) { $id = $id.Guid }",
                "  return ([string]$id).Trim('{}').ToLower() -eq $want",
                "}",
                "$hit = $false",
                "foreach ($b in @(Get-VBRBackup -ErrorAction SilentlyContinue)) {",
                "  foreach ($cand in @($b | Get-VBRRestorePoint -ErrorAction SilentlyContinue)) {",
                "    if (Rp-Match $cand) { $hit = $true; break }",
                "  }",
                "  if ($hit) { break }",
                "}",
                "if (-not $hit) {",
                "  $hit = [bool](Get-VBRRestorePoint -ErrorAction SilentlyContinue | Where-Object { Rp-Match $_ } | Select-Object -First 1)",
                "}",
                "if (-not $hit) {",
                "  $hit = [bool](Get-VBRObjectRestorePoint -ErrorAction SilentlyContinue | Where-Object { Rp-Match $_ } | Select-Object -First 1)",
                "}",
                "if ($hit) { Write-Output 'EXISTS' } else { Write-Output 'MISSING' }"
        );
        final Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response == null || !response.first()) {
            // Probe failure must not delete (NetBackup would also keep on API error).
            throw new CloudRuntimeException(String.format(
                    "Failed to probe Veeam restore point [%s] over SSH", restorePointId));
        }
        final String payload = StringUtils.trimToEmpty(response.second());
        return payload.contains("EXISTS");
    }

    public boolean volumeSourceReady(String templateName) {
        JSONObject result = volumeResult(Arrays.asList(
                "$ErrorActionPreference='Stop'",
                "Get-Command Copy-VBRComputerBackupJob -ErrorAction Stop | Out-Null",
                "$jobs=@(Get-VBRComputerBackupJob -Name " + ps(templateName) + ")",
                "if ($jobs.Count -ne 1) { throw 'Volume staging requires one existing Linux Agent template job' }",
                "$busy=@(Get-VBRComputerBackupJobSession -Name " + ps(templateName) + " | Where-Object { [string]$_.State -ne 'Stopped' })",
                "Write-Output ('ABLESTACK_JSON:' + (@{ready=($busy.Count -eq 0)} | ConvertTo-Json -Compress))"));
        return result.getBoolean("ready");
    }

    /** One deterministic child job protects precisely one image (or the final metadata directory). */
    public ThirdPartyBackupManifest.Artifact backupVolumeArtifact(String templateName, String sourceIp,
            ThirdPartyBackupManifest.Artifact artifact, boolean metadata) {
        String name = "ABLESTACK-" + artifact.backupUuid + "-" + (metadata ? "metadata" :
                java.util.UUID.nameUUIDFromBytes(artifact.path.getBytes(StandardCharsets.UTF_8)).toString());
        List<String> commands = new ArrayList<>();
        commands.add("$ErrorActionPreference='Stop'");
        commands.add("$name=" + ps(name));
        if (StringUtils.isBlank(artifact.jobId)) {
            commands.add("$job=Get-VBRComputerBackupJob -Name $name -ErrorAction SilentlyContinue");
            commands.add("if (-not $job) {");
            commands.add("  $template=Get-VBRComputerBackupJob -Name " + ps(templateName));
            commands.add("  if (@($template).Count -ne 1) { throw 'Linux Agent template job is ambiguous or missing' }");
            commands.add("  $computer=@(Get-VBRDiscoveredComputer | Where-Object { $_.IPAddress -contains " + ps(sourceIp) + " })");
            commands.add("  if ($computer.Count -ne 1) { throw 'Source Host must resolve to one discovered Linux Agent computer' }");
            commands.add("  Copy-VBRComputerBackupJob -Job $template -Name $name -Description " + ps("Mold logical backup " + artifact.backupUuid) + " | Out-Null");
            commands.add("  $job=Get-VBRComputerBackupJob -Name $name");
            // Linux Agent scope accepts directories; include exactly this payload file within its directory.
            final java.nio.file.Path artifactPath = java.nio.file.Path.of(artifact.path);
            commands.add("  $files=New-VBRSelectedFilesBackupOptions -OSPlatform Linux -BackupSelectedFiles -SelectedFiles @(" +
                    ps(metadata ? artifact.path : artifactPath.getParent().toString()) + ")" +
                    (metadata ? "" : " -IncludeMask @(" + ps(artifactPath.getFileName().toString()) + ")"));
            commands.add("  $scripts=New-VBRJobScriptOptions");
            commands.add("  Set-VBRComputerBackupJob -Job $job -BackupObject $computer -BackupType SelectedFiles -SelectedFilesOptions $files -ScriptOptions $scripts -EnableSchedule:$false | Out-Null");
            commands.add("}");
            commands.add("$sessions=@(Get-VBRComputerBackupJobSession -Name $name)");
            commands.add("if ($sessions.Count -eq 0) { $sessions=@(Start-VBRComputerBackupJob -Job $job -FullBackup -RunAsync) }");
            commands.add("if ($sessions.Count -ne 1) { throw 'Child job must have exactly one session; automatic resubmission is unsafe' }");
            commands.add("$session=$sessions[0]");
        } else {
            commands.add("$session=Get-VBRComputerBackupJobSession -Id " + ps(artifact.jobId));
            commands.add("if (-not $session) { throw 'Saved child session is missing' }");
        }
        commands.add("$out=@{jobId=[string]$session.Id;completed=$false}");
        commands.add("if ([string]$session.State -eq 'Stopped') {");
        commands.add("  if ([string]$session.Result -ne 'Success') { throw ('Child backup failed: '+[string]$session.Result) }");
        commands.add("  $backup=Get-VBRBackup -Name $name");
        commands.add("  $points=@(Get-VBRRestorePoint -Backup $backup)");
        commands.add("  if ($points.Count -eq 1) { $out.completed=$true; $out.externalId=[string]$points[0].Id; $out.backupTime=$points[0].CreationTime.ToUniversalTime().ToString('o') }");
        commands.add("  elseif ($points.Count -gt 1) { throw 'Child restore point is ambiguous' }");
        commands.add("}");
        commands.add("Write-Output ('ABLESTACK_JSON:' + ($out | ConvertTo-Json -Compress))");
        JSONObject result = volumeResult(commands);
        artifact.jobId = result.getString("jobId");
        artifact.completed = result.getBoolean("completed");
        artifact.externalId = result.optString("externalId", null);
        artifact.backupTime = result.optString("backupTime", null);
        return artifact;
    }

    private JSONObject volumeResult(List<String> commands) {
        Pair<Boolean, String> response = executePowerShellCommands(commands);
        if (response == null || !response.first()) {
            throw new CloudRuntimeException("Veeam volume operation failed: " + (response == null ? "no response" : response.second()));
        }
        return Arrays.stream(response.second().split("\\r?\\n")).map(String::trim).filter(line -> line.startsWith("ABLESTACK_JSON:"))
                .map(line -> new JSONObject(line.substring("ABLESTACK_JSON:".length()))).findFirst()
                .orElseThrow(() -> new CloudRuntimeException("Veeam volume operation did not return a job reference"));
    }

    /** Agent file restore requires the FLR object to remain in the same PowerShell session. */
    public org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result restoreVolumeArtifact(
            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Request request, String destinationHost, String destinationIp) {
        String parent = java.nio.file.Path.of(request.artifact.path).getParent().toString();
        String name = java.nio.file.Path.of(request.artifact.path).getFileName().toString();
        String destination = java.nio.file.Path.of(request.destination).getParent().toString();
        JSONObject result = volumeResult(Arrays.asList(
                "$ErrorActionPreference='Stop'",
                "Get-Command Start-VBRLinuxGuestItemRestore -ErrorAction Stop | Out-Null",
                "$points=@(Get-VBRBackup | Get-VBRRestorePoint | Where-Object { ([string]$_.Id).Trim('{}') -eq " + ps(request.artifact.externalId) + " })",
                "if ($points.Count -ne 1) { throw 'Exact artifact restore point is missing or ambiguous' }",
                "$targets=@(Get-VBRDiscoveredComputer | Where-Object { $_.Name -eq " + ps(destinationHost)
                        + " -or @($_.IpAddresses) -contains " + ps(destinationIp) + " })",
                "if ($targets.Count -ne 1) { throw 'Restore Worker Host must identify one Veeam Agent computer' }",
                "$mounts=@(Get-VBRServer -Name " + ps(destinationHost) + ")",
                "if ($mounts.Count -ne 1) { throw 'Restore Worker Host must be registered as a Linux FLR mount server' }",
                "$flr=$null",
                "try {",
                "  $flr=Start-VBRLinuxFileRestore -RestorePoint $points[0] -MountServer $mounts[0]",
                "  $items=@(Get-VBRLinuxGuestItem -LinuxFlrObject $flr -Path " + ps(parent)
                        + " | Where-Object { $_.Name -eq " + ps(name) + " })",
                "  if ($items.Count -ne 1) { throw 'Exact artifact file or metadata directory is missing or ambiguous' }",
                "  $task=Start-VBRLinuxGuestItemRestore -LinuxFlrObject $flr -Item $items -TargetAgentMachine $targets[0] -TargetDirectory "
                        + ps(destination) + " -Overwrite",
                "  if ($null -eq $task) { throw 'Veeam file restore returned no task session' }",
                "  $result=[string]$task.Result; if ([string]::IsNullOrEmpty($result)) { $result=[string]$task.Info.Result }",
                "  if ($result -ne 'Success') { throw ('Veeam artifact restore did not confirm success: '+$result) }",
                "  Write-Output ('ABLESTACK_JSON:' + (@{jobId=[string]$task.Id;completed=$true} | ConvertTo-Json -Compress))",
                "} finally { if ($null -ne $flr) { Stop-VBRLinuxFileRestore -LinuxFlrObject $flr | Out-Null } }"));
        return new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result(result.getString("jobId"), result.getBoolean("completed"));
    }

    private static String ps(String value) {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new CloudRuntimeException("Invalid Veeam volume operation argument");
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private Pair<Boolean, String> executePowerShellCommands(final List<String> cmds) {
        final String command = transformPowerShellCommandList(cmds);
        try {
            final Pair<Boolean, String> response = SshHelper.sshExecute(host, SSH_PORT, username, null, password,
                    command, CONNECT_TIMEOUT_MS, KEX_TIMEOUT_MS, WAIT_TIMEOUT_MS);
            if (response == null || !response.first()) {
                logger.error(String.format("Veeam SSH PowerShell command failed: [%s]",
                        response != null ? response.second() : "no output returned"));
            }
            return response;
        } catch (Exception e) {
            throw new CloudRuntimeException("Error while executing Veeam SSH PowerShell command: " + e.getMessage(), e);
        }
    }

    /**
     * Build the single SSH command that runs the given PowerShell statements, passed via
     * {@code -EncodedCommand} so cmd.exe cannot intercept PowerShell metacharacters.
     */
    private String transformPowerShellCommandList(final List<String> cmds) {
        final StringJoiner script = new StringJoiner("\n");
        if (legacy) {
            script.add("Add-PSSnapin VeeamPSSnapin");
        } else {
            script.add("Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue");
            script.add("$ProgressPreference='SilentlyContinue'");
        }
        final List<String> all = new ArrayList<>(cmds);
        for (final String cmd : all) {
            script.add(normalizeToPowerShell(cmd));
        }
        final String encoded = Base64.getEncoder().encodeToString(script.toString().getBytes(StandardCharsets.UTF_16LE));
        return String.format("%s -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand %s", POWERSHELL_BIN, encoded);
    }

    private String normalizeToPowerShell(final String cmd) {
        if (cmd == null) {
            return "";
        }
        return cmd.replace("^|", "|").replace("\\\"", "\"");
    }
}
