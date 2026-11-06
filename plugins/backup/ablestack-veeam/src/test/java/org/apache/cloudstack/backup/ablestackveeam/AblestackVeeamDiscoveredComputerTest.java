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
import java.util.concurrent.TimeUnit;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/** Execute the generated query with synthetic Veeam inventory objects, without contacting VBR. */
public class AblestackVeeamDiscoveredComputerTest {
    private static final String OBJECT_ID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String SOURCE = "(@($_.IPAddress)+@($_.IpAddresses)) -contains '192.168.1.21'";
    private static final String RESTORE = " $_.Name -eq 'host1' -or " + SOURCE;
    private static final String POWERSHELL = System.getProperty("veeam.powershell", "pwsh");

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void requirePowerShell() throws Exception {
        try {
            Process process = new ProcessBuilder(POWERSHELL, "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                    .directory(Path.of(System.getProperty("java.io.tmpdir")).toFile()).start();
            boolean exited = process.waitFor(20, TimeUnit.SECONDS);
            if (!exited) { process.destroyForcibly(); }
            assumeTrue("PowerShell 7 is required to exercise Veeam inventory queries", exited && process.exitValue() == 0);
        } catch (IOException e) {
            assumeNoException("PowerShell 7 is not installed", e);
        }
    }

    @Test
    public void repeatedIdentityRetainsOneOriginalComputerForBackupAndRestore() throws Exception {
        String records = computer("[guid]'" + OBJECT_ID + "'", "host1", "192.168.1.21") + ","
                + computer("'{AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE}'", "host1-alias", "192.168.1.21");
        for (String condition : new String[]{SOURCE, RESTORE}) {
            run(inventory(records), "$selected=" + AblestackVeeamSshClient.discoveredComputers(condition) + "; "
                    + "if ($selected.Count -ne 1) { throw 'Duplicate identity was not collapsed' }; "
                    + "if (![object]::ReferenceEquals($selected[0],$script:computers[0])) { throw 'Original Veeam object was replaced' }");
        }
    }

    @Test
    public void differentIdentitiesAtTheSameAddressRemainAmbiguous() throws Exception {
        String records = computer("'" + OBJECT_ID + "'", "host1", "192.168.1.21") + ","
                + computer("'11111111-2222-4333-8444-555555555555'", "host1", "192.168.1.21");
        for (String condition : new String[]{SOURCE, RESTORE}) {
            run(inventory(records), "$selected=" + AblestackVeeamSshClient.discoveredComputers(condition) + "; "
                    + "if ($selected.Count -ne 2) { throw 'Distinct computers were incorrectly merged' }");
        }
    }

    @Test
    public void missingInvalidOrEmptyIdentitiesCannotBeMergedIntoAValidTarget() throws Exception {
        for (String identity : new String[]{"$null", "''", "'not-a-guid'", "[guid]::Empty"}) {
            String records = computer("'" + OBJECT_ID + "'", "host1", "192.168.1.21") + ","
                    + computer(identity, "host1", "192.168.1.21");
            run(inventory(records), "try { $selected=" + AblestackVeeamSshClient.discoveredComputers(SOURCE)
                    + "; throw 'Invalid identity was accepted' } catch { "
                    + "if ($_.Exception.Message -notlike '*missing or invalid ObjectID*') { throw } }");
        }
        run(inventory("[pscustomobject]@{Name='host1';IPAddress=@('192.168.1.21')}"),
                "try { $selected=" + AblestackVeeamSshClient.discoveredComputers(SOURCE)
                        + "; throw 'Missing property was accepted' } catch { "
                        + "if ($_.Exception.Message -notlike '*missing or invalid ObjectID*') { throw } }");
    }

    @Test
    public void bothAddressPropertiesMatchWithoutDuplicatingTheComputer() throws Exception {
        for (String addresses : new String[]{"IPAddress=@('192.168.1.21')", "IpAddresses=@('192.168.1.21')",
                "IPAddress=@('192.168.1.21');IpAddresses=@('192.168.1.21')"}) {
            run(inventory("[pscustomobject]@{ObjectID='" + OBJECT_ID + "';Name='different-host-name';" + addresses + "}"),
                    "$selected=" + AblestackVeeamSshClient.discoveredComputers(RESTORE)
                            + "; if ($selected.Count -ne 1) { throw 'Restore IP address matching failed' }");
        }
    }

    @Test
    public void unrelatedInvalidRecordsDoNotBreakSelectionAndAbsentHostsStayEmpty() throws Exception {
        run(inventory(computer("$null", "host2", "192.168.1.22")),
                "$selected=" + AblestackVeeamSshClient.discoveredComputers(SOURCE)
                        + "; if ($selected.Count -ne 0) { throw 'An unrelated computer was selected' }");
    }

    @Test
    public void discoveryErrorsCannotBecomeAnEmptySuccessfulLookup() throws Exception {
        run("function Get-VBRDiscoveredComputer { [CmdletBinding()] param(); Write-Error 'Discovery unavailable' }\n",
                "try { $selected=" + AblestackVeeamSshClient.discoveredComputers(SOURCE)
                        + "; throw 'Discovery error was ignored' } catch { "
                        + "if ($_.Exception.Message -notlike '*Discovery unavailable*') { throw } }");
    }

    @Test
    public void identityTrackingIsLocalToEachLookup() throws Exception {
        run(inventory(computer("'" + OBJECT_ID + "'", "host1", "192.168.1.21")),
                "$first=" + AblestackVeeamSshClient.discoveredComputers(SOURCE)
                        + "; $second=" + AblestackVeeamSshClient.discoveredComputers(RESTORE)
                        + "; if ($first.Count -ne 1 -or $second.Count -ne 1) { throw 'Identity state leaked across queries' }");
    }

    private static String computer(String identity, String name, String address) {
        return "[pscustomobject]@{ObjectID=" + identity + ";Name='" + name + "';IPAddress=@('" + address + "')}";
    }

    private static String inventory(String records) {
        return "$script:computers=@(" + records + ")\n"
                + "function Get-VBRDiscoveredComputer { [CmdletBinding()] param(); $script:computers }\n";
    }

    private void run(String setup, String assertions) throws Exception {
        Path script = temporary.newFile().toPath();
        Path output = temporary.newFile().toPath();
        Files.writeString(script, "$ErrorActionPreference='Stop'\n" + setup
                + "try { " + assertions + "; Write-Output 'PASS' } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }\n",
                StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(POWERSHELL, "-NoProfile", "-NonInteractive", "-File", script.toString())
                .directory(temporary.getRoot()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) { process.destroyForcibly(); }
        assertTrue("Veeam inventory query timed out", exited);
        String result = Files.readString(output, StandardCharsets.UTF_8).trim();
        assertEquals(result, 0, process.exitValue());
        assertEquals("PASS", result);
    }
}
