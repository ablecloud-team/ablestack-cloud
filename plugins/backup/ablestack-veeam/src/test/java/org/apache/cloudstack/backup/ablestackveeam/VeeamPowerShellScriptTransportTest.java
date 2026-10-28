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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.cloud.utils.Pair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/** Run the actual transport scripts in PowerShell 7, with only the Veeam module stubbed. */
public class VeeamPowerShellScriptTransportTest {
    private static final String POWERSHELL = System.getProperty("veeam.powershell", "pwsh");
    private static final String PADDING = "\n# quoted 'text' 한글 & | $value\n".repeat(150);
    private static final String SESSION = "22222222-2222-4222-8222-222222222222";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void requirePowerShell() throws Exception {
        try {
            Process process = new ProcessBuilder(POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                    .directory(Path.of(System.getProperty("java.io.tmpdir")).toFile()).start();
            boolean exited = process.waitFor(20, TimeUnit.SECONDS);
            if (!exited) { process.destroyForcibly(); }
            assumeTrue("PowerShell 7 is required to exercise script transport", exited && process.exitValue() == 0);
        } catch (IOException e) {
            assumeNoException("PowerShell 7 is not installed", e);
        }
    }

    private class LocalTransport implements VeeamPowerShell.Executor {
        private final Path root = temporary.newFolder().toPath();
        private final Path remote = Files.createDirectory(root.resolve("remote temp ' 한글"));
        private final Path effects = root.resolve("actions.txt");
        private final List<String> commands = new ArrayList<>();
        private String setup = "";
        private String loseUpload;
        private String loseInvocation;
        private boolean corrupt;
        private boolean failCleanup;
        private int invocations;

        LocalTransport() throws IOException { }

        String effect(String value) {
            return "Add-Content -LiteralPath '" + effects.toString().replace("'", "''") + "' -Value '" + value + "' -Encoding UTF8\n";
        }

        void assertClean() throws IOException {
            try (Stream<Path> files = Files.list(remote)) {
                assertEquals("Temporary script must be removed", 0, files.count());
            }
        }

        @Override
        public Pair<Boolean, String> execute(String command) throws Exception {
            commands.add(command);
            assertTrue("SSH command exceeds the Windows-safe transport limit: " + command.length(), command.length() <= 6000);
            String script = new String(Base64.getDecoder().decode(command.substring(command.lastIndexOf(' ') + 1)), StandardCharsets.UTF_16LE);
            boolean invocation = script.contains("[ScriptBlock]::Create");
            if (invocation) {
                invocations++;
                if (corrupt) {
                    try (Stream<Path> files = Files.list(remote)) {
                        Path staged = files.findFirst().orElseThrow();
                        byte[] bytes = Files.readAllBytes(staged);
                        bytes[0] ^= 1;
                        Files.write(staged, bytes);
                    }
                }
            }
            if (failCleanup && script.contains("if (Test-Path -LiteralPath $path)")) {
                throw new IOException("Cleanup SSH response lost");
            }
            String stub = "function Import-Module { [CmdletBinding()] param([string]$Name) }\n";
            String encoded = Base64.getEncoder().encodeToString((stub + setup + script).getBytes(StandardCharsets.UTF_16LE));
            Path output = root.resolve("output.txt");
            ProcessBuilder builder = new ProcessBuilder(POWERSHELL, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                    .directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("TMPDIR", remote.toString());
            builder.environment().put("TEMP", remote.toString());
            builder.environment().put("TMP", remote.toString());
            Process process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("PowerShell transport test timed out");
            }
            Pair<Boolean, String> result = new Pair<>(process.exitValue() == 0, Files.readString(output));
            if (script.contains("ABLESTACK_SCRIPT_STAGED:") && loseUpload != null) {
                if ("null".equals(loseUpload)) { return null; }
                if ("ack".equals(loseUpload)) { return new Pair<>(true, "Unrelated successful response"); }
                if ("throw".equals(loseUpload)) { throw new IOException("Upload SSH response lost"); }
                return new Pair<>(false, "Upload response unconfirmed");
            }
            if (invocation && loseInvocation != null) {
                if ("throw".equals(loseInvocation)) { throw new IOException("Invocation SSH response lost"); }
                return null;
            }
            return result;
        }
    }

    @Test
    public void longUnicodeScriptExecutesOnceAndPreservesOutput() throws Exception {
        LocalTransport transport = new LocalTransport();
        Pair<Boolean, String> result = VeeamPowerShell.execute(List.of(transport.effect("ONCE")
                + "Write-Output '한글 경로 & | $literal'" + PADDING), transport);
        assertTrue(result.second(), result.first());
        assertTrue(result.second().contains("한글 경로 & | $literal"));
        assertEquals(List.of("ONCE"), Files.readAllLines(transport.effects));
        assertEquals(1, transport.invocations);
        transport.assertClean();
    }

    @Test
    public void businessFailurePreservesNonzeroStatusWithoutResubmission() throws Exception {
        LocalTransport transport = new LocalTransport();
        Pair<Boolean, String> result = VeeamPowerShell.execute(List.of(transport.effect("ONCE")
                + "throw 'Child submission failed'" + PADDING), transport);
        assertFalse(result.first());
        assertTrue(result.second().contains("Child submission failed"));
        assertEquals(List.of("ONCE"), Files.readAllLines(transport.effects));
        assertEquals(1, transport.invocations);
        transport.assertClean();
    }

    @Test
    public void missingUploadConfirmationNeverInvokesTheAction() throws Exception {
        for (String failure : List.of("null", "ack", "false", "throw")) {
            LocalTransport transport = new LocalTransport();
            transport.loseUpload = failure;
            if ("throw".equals(failure)) {
                assertThrows(IOException.class, () -> VeeamPowerShell.execute(List.of(transport.effect("UNSAFE") + PADDING), transport));
            } else {
                Pair<Boolean, String> result = VeeamPowerShell.execute(List.of(transport.effect("UNSAFE") + PADDING), transport);
                assertFalse(result.first());
                assertTrue(result.second().contains("the action was not executed"));
            }
            assertEquals(0, transport.invocations);
            assertFalse(Files.exists(transport.effects));
            transport.assertClean();
        }
    }

    @Test
    public void corruptScriptCannotExecuteEvenWhenAllWritesWereAcknowledged() throws Exception {
        LocalTransport transport = new LocalTransport();
        transport.corrupt = true;
        Pair<Boolean, String> result = VeeamPowerShell.execute(List.of(transport.effect("UNSAFE") + PADDING), transport);
        assertFalse(result.first());
        assertTrue(result.second().contains("verification failed"));
        assertFalse(Files.exists(transport.effects));
        transport.assertClean();
    }

    @Test
    public void lostInvocationResponseCannotSubmitTheActionTwice() throws Exception {
        for (String failure : List.of("null", "throw")) {
            LocalTransport transport = new LocalTransport();
            transport.loseInvocation = failure;
            if ("throw".equals(failure)) {
                assertThrows(IOException.class, () -> VeeamPowerShell.execute(List.of(transport.effect("ONCE") + PADDING), transport));
            } else {
                assertNull(VeeamPowerShell.execute(List.of(transport.effect("ONCE") + PADDING), transport));
            }
            assertEquals(List.of("ONCE"), Files.readAllLines(transport.effects));
            assertEquals(1, transport.invocations);
            transport.assertClean();
        }
    }

    @Test
    public void cleanupFailureCannotReplaceTheAcceptedActionResult() throws Exception {
        LocalTransport transport = new LocalTransport();
        transport.failCleanup = true;
        Pair<Boolean, String> result = VeeamPowerShell.execute(List.of(transport.effect("ONCE") + "Write-Output 'accepted'" + PADDING), transport);
        assertTrue(result.second(), result.first());
        assertTrue(result.second().contains("accepted"));
        assertEquals(List.of("ONCE"), Files.readAllLines(transport.effects));
        assertEquals(1, transport.invocations);
        transport.assertClean();
    }

    @Test
    public void reportedRootArtifactCreatesConfiguresAndStartsOneChildJob() throws Exception {
        LocalTransport transport = new LocalTransport();
        transport.setup = "function Get-VBRComputerBackupJob { [CmdletBinding()] param($Name); "
                + "if ($Name -eq 'veeam-ablecube1') { [pscustomobject]@{Id='parent'} } else { $script:child } }\n"
                + "function Get-VBRDiscoveredComputer { [CmdletBinding()] param(); "
                + "$computer=[pscustomobject]@{ObjectID='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';IPAddress=@('192.168.1.21')}; $computer; $computer }\n"
                + "function New-VBRSelectedFilesBackupOptions { param($OSPlatform,[switch]$BackupSelectedFiles,$SelectedFiles,$IncludeMask); @{} }\n"
                + "function New-VBRJobScriptOptions { @{} }\n"
                + "function Copy-VBRComputerBackupJob { param($Job,$Name,$Description); " + transport.effect("COPY")
                + "$script:child=[pscustomobject]@{Id='child';Name=$Name} }\n"
                + "function Set-VBRComputerBackupJob { param($Job,$BackupObject,$BackupType,$SelectedFilesOptions,$ScriptOptions,"
                + "[switch]$EnableSchedule,[switch]$UseSnapshotlessFileLevelBackup); " + transport.effect("SET") + "}\n"
                + "function Get-VBRComputerBackupJobSession { [CmdletBinding()] param(); $script:sessions }\n"
                + "function Start-VBRComputerBackupJob { param($Job,[switch]$FullBackup,[switch]$RunAsync); " + transport.effect("START")
                + "$script:sessions=[pscustomobject]@{Id='" + SESSION + "';JobId='child';State='Working'}; $script:sessions }\n";
        AblestackVeeamSshClient client = new AblestackVeeamSshClient("test", "test", "test") {
            @Override protected Pair<Boolean, String> executePowerShellCommands(List<String> commands) {
                try { return VeeamPowerShell.execute(commands, transport); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }
        };
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.backupUuid = "cbdd161d-2f89-4888-9691-6ef5ca2cd86b";
        artifact.path = "/mnt/backup-staging/ablestack-veeam/i-2-319-VM/2026.10.28.10.17.44.609/"
                + "root.98e97366-3548-4f6e-a03f-8482776f1987.raw";
        ThirdPartyBackupManifest.Artifact accepted = client.backupVolumeArtifact("veeam-ablecube1", "192.168.1.21", artifact, false);
        assertEquals(SESSION, accepted.jobId);
        assertFalse(accepted.completed);
        assertEquals(List.of("COPY", "SET", "START"), Files.readAllLines(transport.effects));
        assertEquals(1, transport.invocations);
        transport.assertClean();
    }
}
