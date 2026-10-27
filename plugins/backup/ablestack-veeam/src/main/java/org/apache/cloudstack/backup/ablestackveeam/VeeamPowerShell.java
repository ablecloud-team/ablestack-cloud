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

import com.cloud.utils.Pair;

/** Veeam 13 commands require PowerShell 7, independently of management JVM options. */
final class VeeamPowerShell {
    private static final List<String> EXECUTABLES = List.of("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\"", "pwsh.exe", "pwsh");
    private static final String READY = "ABLESTACK_POWERSHELL7_READY:";

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
                return executor.execute(command(executable, script(commands)));
            }
            failures.add(executable + ": " + (response == null || response.second() == null || response.second().isBlank()
                    ? "PowerShell/module probe did not confirm readiness" : response.second().trim()));
        }
        return new Pair<>(false, "Veeam 13 requires PowerShell 7 with the Veeam.Backup.PowerShell module. "
                + "Windows PowerShell 5.1 is not supported. " + String.join(" | ", failures));
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
