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

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
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
import org.apache.cloudstack.backup.veeam.VeeamClient;
import org.apache.commons.lang3.StringUtils;

import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Ablestack Veeam client: extends the stock Veeam REST/SSH client with NAS seed export helpers.
 */
public class AblestackVeeamClient extends VeeamClient {

    /**
     * PowerShell executable used on the Veeam server. Override with
     * -Dveeam.powershell.bin=pwsh if the Veeam (v12.1+/v13) module requires
     * PowerShell 7 instead of Windows PowerShell 5.1.
     */
    private static final String POWERSHELL_BIN = System.getProperty("veeam.powershell.bin", "powershell");

    public AblestackVeeamClient(final String url, final Integer version, final String username, final String password,
            final boolean validateCertificate, final int timeout, final int restoreTimeout, final int taskPollInterval,
            final int taskPollMaxRetry) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        super(url, version, username, password, validateCertificate, timeout, restoreTimeout, taskPollInterval, taskPollMaxRetry);
    }

    /**
     * Build the single SSH command that runs the given PowerShell statements on the
     * Veeam server.
     *
     * <p>The stock implementation joins commands with ';' and prefixes a bare
     * {@code PowerShell ...} token, relying on the Windows SSH default shell
     * (cmd.exe) to leave the rest untouched. That breaks for any statement that
     * contains cmd.exe metacharacters - most notably the pipeline operator
     * {@code |} (e.g. {@code Get-VBRRestorePoint | Where-Object ...}) and script
     * blocks {@code { }}. cmd.exe intercepts those, which is why the logs show
     * errors like {@code 'Where-Object' is not recognized as an internal or
     * external command}.</p>
     *
     * <p>Instead we assemble one PowerShell script and pass it via
     * {@code -EncodedCommand} (Base64 of UTF-16LE). The encoded payload contains
     * only Base64 characters, so cmd.exe cannot mangle it and the whole script
     * runs inside a single PowerShell process.</p>
     */
    @Override
    protected String transformPowerShellCommandList(final List<String> cmds) {
        // The Ablestack Veeam integration targets modern Veeam (v12+/v13) and uses
        // Veeam.Backup.PowerShell cmdlets, so always use the module import (non-legacy
        // PSSnapin) path. Keeping this independent of the parent's legacy detection.
        final StringJoiner script = new StringJoiner("\n");
        script.add("Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue");
        script.add("$ProgressPreference='SilentlyContinue'");
        for (final String cmd : cmds) {
            script.add(normalizeToPowerShell(cmd));
        }
        final String encoded = Base64.getEncoder().encodeToString(script.toString().getBytes(StandardCharsets.UTF_16LE));
        return String.format("%s -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand %s", POWERSHELL_BIN, encoded);
    }

    /**
     * Some inherited command strings are pre-escaped for cmd.exe passthrough
     * (e.g. {@code ^|} for the pipeline operator and {@code \"} for quotes).
     * When the script is run via -EncodedCommand it is pure PowerShell, so those
     * cmd escapes must be reverted to their native PowerShell form.
     */
    private String normalizeToPowerShell(final String cmd) {
        if (cmd == null) {
            return "";
        }
        return cmd.replace("^|", "|").replace("\\\"", "\"");
    }

    /**
     * Export all hard disks from a Veeam restore point to a directory on the Veeam server.
     * The directory must be reachable from the KVM hypervisor (shared NFS recommended).
     *
     * @return absolute paths of exported disk files on the Veeam server
     */
    public List<String> exportRestorePointDisksToStaging(final String restorePointId, final String stagingPath) {
        logger.debug(String.format("Exporting Veeam restore point [%s] to staging [%s]", restorePointId, stagingPath));
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
        Pair<Boolean, String> result = executePowerShellCommands(cmds);
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
     * List Veeam restore points for a VM display name (KVM migration; no vCenter hierarchy required).
     */
    public List<Backup.RestorePoint> listRestorePointsForVmDisplayName(final String vmDisplayName) {
        final String escapedName = vmDisplayName.replace("'", "''");
        final List<String> cmds = Arrays.asList(
                "$jobMap = @{}",
                "Get-VBRJob -ErrorAction SilentlyContinue | ForEach-Object { $jobMap[$_.Id.ToString()] = $_.Name; if ($_.Id.Guid) { $jobMap[$_.Id.Guid] = $_.Name } }",
                "Get-VBRComputerBackupJob -ErrorAction SilentlyContinue | ForEach-Object { $jobMap[$_.Id.ToString()] = $_.Name; if ($_.Id.Guid) { $jobMap[$_.Id.Guid] = $_.Name } }",
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
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response == null || !response.first()) {
            throw new CloudRuntimeException(String.format("Failed to list Veeam restore points for [%s]", vmDisplayName));
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
     * Live Veeam job names, including Agent (computer) backup jobs.
     * An empty list means the query succeeded and Veeam currently has no jobs.
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
            throw new CloudRuntimeException("Failed to list Veeam backup jobs");
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
}
