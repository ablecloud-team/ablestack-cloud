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
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.veeam.VeeamClient;
import org.apache.commons.lang3.StringUtils;

import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;

/**
 * Ablestack Veeam client: extends the stock Veeam REST/SSH client with NAS seed export helpers.
 */
public class AblestackVeeamClient extends VeeamClient {

    // Set by the parent before its constructor invokes PowerShell version detection.
    private String sshHost;
    private String sshUsername;
    private String sshPassword;

    public AblestackVeeamClient(final String url, final Integer version, final String username, final String password,
            final boolean validateCertificate, final int timeout, final int restoreTimeout, final int taskPollInterval,
            final int taskPollMaxRetry) throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        super(url, version, username, password, validateCertificate, timeout, restoreTimeout, taskPollInterval, taskPollMaxRetry);
    }

    @Override
    protected void setVeeamSshCredentials(String hostIp, String username, String password) {
        super.setVeeamSshCredentials(hostIp, username, password);
        sshHost = hostIp;
        sshUsername = username;
        sshPassword = password;
    }

    @Override
    protected Pair<Boolean, String> executePowerShellCommands(final List<String> cmds) {
        try {
            Pair<Boolean, String> response = VeeamPowerShell.execute(cmds,
                    command -> SshHelper.sshExecute(sshHost, 22, sshUsername, null, sshPassword,
                            command, 120000, 120000, 3600000));
            if (response == null || !response.first()) {
                logger.error("Veeam PowerShell command failed: [{}]", response != null ? response.second() : "no output returned");
            }
            return response;
        } catch (Exception e) {
            throw new CloudRuntimeException("Error while executing Veeam PowerShell command: " + e.getMessage(), e);
        }
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
