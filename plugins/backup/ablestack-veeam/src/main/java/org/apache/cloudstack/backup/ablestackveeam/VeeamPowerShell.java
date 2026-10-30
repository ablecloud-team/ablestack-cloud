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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.Pair;

/** Veeam 13 commands require PowerShell 7, independently of management JVM options. */
final class VeeamPowerShell {
    private static final Logger logger = LogManager.getLogger(VeeamPowerShell.class);
    private static final List<String> EXECUTABLES = List.of("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\"", "pwsh.exe", "pwsh");
    private static final String READY = "ABLESTACK_POWERSHELL7_READY:";
    // Windows OpenSSH may launch through cmd.exe, whose command line limit is 8191 characters.
    private static final int MAX_COMMAND_LENGTH = 6000;
    private static final int SCRIPT_CHUNK_BYTES = 768;

    private VeeamPowerShell() { }

    @FunctionalInterface
    interface Executor {
        Pair<Boolean, String> execute(String command) throws Exception;
    }

    static Pair<Boolean, String> execute(List<String> commands, Executor executor) throws Exception {
        String probe = script(List.of("Write-Output ('" + READY + "' + $PSVersionTable.PSVersion.ToString())"));
        List<String> failures = new ArrayList<>();
        for (String executable : EXECUTABLES) {
            Pair<Boolean, String> response = executor.execute(command(executable, probe));
            if (response != null && Boolean.TRUE.equals(response.first()) && response.second() != null
                    && Arrays.stream(response.second().split("\\r?\\n")).anyMatch(line -> line.trim().startsWith(READY))) {
                // Only the read-only probe may try another executable. A failed or
                // unconfirmed backup request must never be submitted a second time.
                return executeScript(executable, script(commands), executor);
            }
            failures.add(executable + ": " + (response == null || response.second() == null || response.second().isBlank()
                    ? "PowerShell/module probe did not confirm readiness" : response.second().trim()));
        }
        return new Pair<>(false, "Veeam 13 requires PowerShell 7 with the Veeam.Backup.PowerShell module. "
                + "Windows PowerShell 5.1 is not supported. " + String.join(" | ", failures));
    }

    private static Pair<Boolean, String> executeScript(String executable, String script, Executor executor) throws Exception {
        String direct = command(executable, script);
        if (direct.length() <= MAX_COMMAND_LENGTH) {
            return executor.execute(direct);
        }

        // Use the existing SSH executor for bounded file writes; SCP support is not required.
        // No staged content is evaluated until all chunks and its SHA-256 have been verified.
        String fileName = "ablestack-" + UUID.randomUUID() + ".ps1";
        String path = "$path=Join-Path ([IO.Path]::GetTempPath()) '" + fileName + "'\n";
        byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
        String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        try {
            for (int offset = 0; offset < bytes.length; offset += SCRIPT_CHUNK_BYTES) {
                int end = Math.min(offset + SCRIPT_CHUNK_BYTES, bytes.length);
                String chunk = Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, end));
                String accepted = "ABLESTACK_SCRIPT_STAGED:" + fileName + ":" + end;
                String write = path
                        + "$stream=[IO.File]::Open($path,[IO.FileMode]::" + (offset == 0 ? "CreateNew" : "Open")
                        + ",[IO.FileAccess]::Write,[IO.FileShare]::None)\n"
                        + "try { if ($stream.Length -ne " + offset + ") { throw 'PowerShell staging offset mismatch' }; "
                        + "$stream.Position=" + offset + "; $chunk=[Convert]::FromBase64String('" + chunk + "'); "
                        + "$stream.Write($chunk,0,$chunk.Length) } finally { $stream.Dispose() }\n"
                        + "Write-Output '" + accepted + "'";
                Pair<Boolean, String> response = executor.execute(command(executable, transportScript(write)));
                if (response == null || !Boolean.TRUE.equals(response.first()) || response.second() == null
                        || Arrays.stream(response.second().split("\\r?\\n")).noneMatch(line -> accepted.equals(line.trim()))) {
                    return new Pair<>(false, "PowerShell script staging is unconfirmed; the action was not executed. "
                            + (response == null || response.second() == null ? "No staging response" : response.second()));
                }
            }
            String invoke = path
                    + "$bytes=[IO.File]::ReadAllBytes($path)\n"
                    + "$hash=[Security.Cryptography.SHA256]::Create()\n"
                    + "try { $digest=[Convert]::ToBase64String($hash.ComputeHash($bytes)) } finally { $hash.Dispose() }\n"
                    + "if ($bytes.Length -ne " + bytes.length + " -or $digest -cne '" + digest
                    + "') { throw 'PowerShell staged script verification failed' }\n"
                    + "$script=[Text.Encoding]::UTF8.GetString($bytes)\n"
                    + "Remove-Item -LiteralPath $path -Force -ErrorAction Stop\n"
                    + "& ([ScriptBlock]::Create($script))";
            // Once invocation is attempted, a lost response cannot justify resubmission.
            return executor.execute(command(executable, transportScript(invoke)));
        } finally {
            try {
                Pair<Boolean, String> cleanup = executor.execute(command(executable, transportScript(path
                        + "if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force -ErrorAction Stop }")));
                if (cleanup == null || !Boolean.TRUE.equals(cleanup.first())) {
                    logger.warn("Unable to confirm Veeam PowerShell temporary script cleanup [{}]", fileName);
                }
            } catch (Exception e) {
                logger.warn("Unable to clean up Veeam PowerShell temporary script [{}]: {}", fileName, e.getMessage());
            }
        }
    }

    private static String transportScript(String body) {
        return "$ErrorActionPreference='Stop'\ntry {\n" + body
                + "\n} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }";
    }

    private static String command(String executable, String script) {
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return executable + " -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + encoded;
    }

    private static String script(List<String> commands) {
        StringJoiner script = new StringJoiner("\n");
        script.add("$ErrorActionPreference='Stop'");
        script.add("$ProgressPreference='SilentlyContinue'");
        script.add("try {");
        script.add("if ($PSVersionTable.PSVersion.Major -ne 7) { throw 'Veeam 13 requires PowerShell 7' }");
        script.add("Import-Module Veeam.Backup.PowerShell -WarningAction SilentlyContinue -ErrorAction Stop");
        for (String command : commands) {
            script.add(command == null ? "" : command.replace("^|", "|").replace("\\\"", "\""));
        }
        script.add("} catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }");
        return script.toString();
    }
}
