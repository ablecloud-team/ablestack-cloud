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
import java.util.Base64;
import java.util.List;

import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.junit.Test;
import org.mockito.MockedStatic;

import com.cloud.utils.Pair;
import com.cloud.utils.ssh.SshHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class VeeamPowerShellTest {
    private static final String READY = "ABLESTACK_POWERSHELL7_READY:7.4.0";
    private static final String ACTION = "Start-VBRComputerBackupJob -Job $job -RunAsync";

    private static String script(String command) {
        return new String(Base64.getDecoder().decode(command.substring(command.lastIndexOf(' ') + 1)), StandardCharsets.UTF_16LE);
    }

    private static class Transport implements VeeamPowerShell.Executor {
        private final List<String> commands = new ArrayList<>();
        private int unavailable;
        private Pair<Boolean, String> result = new Pair<>(true, "accepted");

        @Override public Pair<Boolean, String> execute(String command) {
            commands.add(command);
            if (script(command).contains("ABLESTACK_POWERSHELL7_READY:")) {
                return unavailable-- > 0 ? new Pair<>(false, "executable or module unavailable") : new Pair<>(true, READY);
            }
            return result;
        }
    }

    @Test public void standardPowerShell7PathIsProbedBeforeOneEncodedRequest() throws Exception {
        Transport transport = new Transport();
        assertTrue(VeeamPowerShell.execute(List.of(ACTION), transport).first());
        assertEquals(2, transport.commands.size());
        assertTrue(transport.commands.get(0).startsWith("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" "));
        assertFalse(script(transport.commands.get(0)).contains(ACTION));
        String action = script(transport.commands.get(1));
        assertTrue(action.contains("$PSVersionTable.PSVersion.Major -ne 7"));
        assertTrue(action.indexOf("$ErrorActionPreference='Stop'") < action.indexOf("Import-Module"));
        assertTrue(action.indexOf("-ErrorAction Stop") < action.indexOf(ACTION));
        assertTrue(action.contains("[Console]::Error.WriteLine($_.Exception.Message); exit 1"));
    }

    @Test public void onlyReadOnlyProbesCanFallBackToPathExecutables() throws Exception {
        for (int missing : new int[]{1, 2}) {
            Transport transport = new Transport();
            transport.unavailable = missing;
            VeeamPowerShell.execute(List.of(ACTION), transport);
            assertEquals(missing + 2, transport.commands.size());
            assertTrue(transport.commands.get(missing + 1).startsWith(missing == 1 ? "pwsh.exe " : "pwsh "));
            assertEquals(1, transport.commands.stream().filter(command -> script(command).contains(ACTION)).count());
        }
    }

    @Test public void missingPowerShellOrModuleNeverRunsTheActionOrWindowsPowerShell() throws Exception {
        Transport transport = new Transport();
        transport.unavailable = 3;
        Pair<Boolean, String> response = VeeamPowerShell.execute(List.of(ACTION), transport);
        assertFalse(response.first());
        assertTrue(response.second().contains("PowerShell 7"));
        assertTrue(response.second().contains("module unavailable"));
        assertEquals(3, transport.commands.size());
        for (String command : transport.commands) {
            assertFalse(command.startsWith("powershell"));
            assertFalse(script(command).contains(ACTION));
        }
    }

    @Test public void failedOrMissingActionResponseNeverRetriesSubmission() throws Exception {
        for (Pair<Boolean, String> result : java.util.Arrays.asList(new Pair<>(false, "request unconfirmed"), null)) {
            Transport transport = new Transport();
            transport.result = result;
            assertSame(result, VeeamPowerShell.execute(List.of(ACTION), transport));
            assertEquals(2, transport.commands.size());
        }
    }

    @Test public void zeroExitWithoutConfirmedModuleProbeCannotStartTheAction() throws Exception {
        List<String> commands = new ArrayList<>();
        Pair<Boolean, String> result = VeeamPowerShell.execute(List.of(ACTION), command -> {
            commands.add(command);
            return new Pair<>(true, "no compatible module loaded");
        });
        assertFalse(result.first());
        assertEquals(3, commands.size());
        assertTrue(commands.stream().noneMatch(command -> script(command).contains(ACTION)));
    }

    @Test public void inheritedCmdEscapesAreNormalizedInsideTheEncodedScript() throws Exception {
        Transport transport = new Transport();
        VeeamPowerShell.execute(List.of("Get-VBRJob ^| Where-Object { $_.Name -eq \\\"job\\\" }"), transport);
        String script = script(transport.commands.get(1));
        assertTrue(script.contains("Get-VBRJob | Where-Object { $_.Name -eq \"job\" }"));
    }

    @Test public void oldJvmOverrideCannotSelectPowerShell5() throws Exception {
        String previous = System.getProperty("veeam.powershell.bin");
        try {
            System.setProperty("veeam.powershell.bin", "powershell.exe");
            Transport transport = new Transport();
            VeeamPowerShell.execute(List.of(ACTION), transport);
            assertTrue(transport.commands.get(1).startsWith("\"C:\\Program Files\\PowerShell\\7\\pwsh.exe\" "));
        } finally {
            if (previous == null) { System.clearProperty("veeam.powershell.bin"); }
            else { System.setProperty("veeam.powershell.bin", previous); }
        }
    }

    @Test public void transportFailureDuringSubmissionIsPropagatedWithoutRetry() {
        List<String> commands = new ArrayList<>();
        assertThrows(java.io.IOException.class, () -> VeeamPowerShell.execute(List.of(ACTION), command -> {
            commands.add(command);
            if (commands.size() == 1) { return new Pair<>(true, READY); }
            throw new java.io.IOException("SSH response lost after submission");
        }));
        assertEquals(2, commands.size());
    }

    @Test public void bothJavaClientsUseTheSamePowerShell7Selection() throws Exception {
        List<String> commands = new ArrayList<>();
        try (MockedStatic<SshHelper> ssh = mockStatic(SshHelper.class)) {
            ssh.when(() -> SshHelper.sshExecute(eq("vbr-test"), eq(22), eq("test"), isNull(), eq("password"),
                    anyString(), anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
                        String command = invocation.getArgument(5);
                        commands.add(command);
                        return new Pair<>(true, script(command).contains("ABLESTACK_POWERSHELL7_READY:") ? READY : "accepted");
                    });
            AblestackVeeamSshClient standalone = new AblestackVeeamSshClient("vbr-test", "test", "password");
            assertEquals("accepted", standalone.executePowerShellCommands(List.of(ACTION)).second());
            AblestackVeeamClient inherited = mock(AblestackVeeamClient.class, CALLS_REAL_METHODS);
            inherited.setVeeamSshCredentials("vbr-test", "test", "password");
            assertEquals("accepted", inherited.executePowerShellCommands(List.of(ACTION)).second());
        }
        assertEquals(4, commands.size());
        assertEquals(commands.get(0), commands.get(2));
        assertEquals(commands.get(1), commands.get(3));
    }

    @Test public void restClientConstructorCanDetectVeeamVersionUsingTheSharedExecutor() throws Exception {
        HttpClientBuilder builder = mock(HttpClientBuilder.class, RETURNS_SELF);
        CloseableHttpClient http = mock(CloseableHttpClient.class);
        when(builder.build()).thenReturn(http);
        CloseableHttpResponse auth = mock(CloseableHttpResponse.class);
        when(http.execute(any(HttpUriRequest.class))).thenReturn(auth);
        when(auth.getFirstHeader("X-RestSvcSessionId")).thenReturn(new BasicHeader("X-RestSvcSessionId", "session"));
        StatusLine status = mock(StatusLine.class);
        when(status.getStatusCode()).thenReturn(HttpStatus.SC_CREATED);
        when(auth.getStatusLine()).thenReturn(status);
        List<String> commands = new ArrayList<>();
        try (MockedStatic<HttpClientBuilder> builders = mockStatic(HttpClientBuilder.class);
                MockedStatic<SshHelper> ssh = mockStatic(SshHelper.class)) {
            builders.when(HttpClientBuilder::create).thenReturn(builder);
            ssh.when(() -> SshHelper.sshExecute(eq("vbr-test"), eq(22), eq("test"), isNull(), eq("password"),
                    anyString(), anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
                        String command = invocation.getArgument(5);
                        commands.add(command);
                        return new Pair<>(true, script(command).contains("ABLESTACK_POWERSHELL7_READY:") ? READY : "13.0.0");
                    });
            new AblestackVeeamClient("https://vbr-test:9398/api", 0, "test", "password", true, 30, 30, 1, 1);
        }
        assertEquals(2, commands.size());
        assertTrue(script(commands.get(1)).contains("$ProductData.ProductVersion"));
    }
}
