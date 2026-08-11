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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

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
