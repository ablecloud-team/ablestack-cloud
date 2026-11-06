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

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.util.Collections;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.io.ByteArrayOutputStream;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.libvirt.Domain;
import org.libvirt.DomainInfo;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.Connect;
import org.libvirt.LibvirtException;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StorageServiceHostAnswer;
import static org.mockito.Mockito.*;


import org.junit.Assert;
import org.junit.Test;

import com.cloud.agent.api.StorageServiceHostCommand;

public class LibvirtStorageServiceHostCommandWrapperTest {

    private final LibvirtStorageServiceHostCommandWrapper wrapper = new LibvirtStorageServiceHostCommandWrapper();

    @Test
    public void testStaticSharedFSNetworkUsesDedicatedGuestHelper() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("sharedfs-test",
                "configure-sharedfs-static-network", "{\"ipAddress\":\"10.10.1.201\"}", 60, Collections.emptySet());

        String shell = wrapper.buildStorageCtlShell(command);

        Assert.assertTrue(shell.contains("ablestack-sharedfs-network.service"));
        Assert.assertTrue(shell.contains("/usr/local/sbin/ablestack-sharedfs-network"));
        Assert.assertFalse(shell.contains("/usr/local/bin/ablestack-storagectl"));
    }

    @Test
    public void testGenericStorageOperationStillUsesStorageCtl() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("sharedfs-test",
                "apply-nfs-desired-state", "{}", 60, Collections.emptySet());

        String shell = wrapper.buildStorageCtlShell(command);

        Assert.assertTrue(shell.contains("/usr/local/bin/ablestack-storagectl"));
        Assert.assertFalse(shell.contains("ablestack-sharedfs-network.service"));
    }

    @Test
    public void testStaticRestoreUnitFollowsLateBootstrap() throws Exception {
        java.lang.reflect.Field field=LibvirtStorageServiceHostCommandWrapper.class.getDeclaredField("SHAREDFS_NETWORK_UNIT_CONTENT");
        field.setAccessible(true);String unit=(String)field.get(null);
        Assert.assertTrue(unit.contains("After=local-fs.target cloud-early-config.service"));
        Assert.assertFalse(unit.contains("Before=network-pre.target"));
    }


    private String properties(String load, String active, String sub, String result, String job) {
        return "Id=test.service\nLoadState="+load+"\nActiveState="+active+"\nSubState="+sub+"\nResult="+result+"\nJob="+job+"\n";
    }

    private String[] runGuard(String early, String local, String network) throws Exception {
        java.nio.file.Path dir=java.nio.file.Files.createTempDirectory("sharedfs-guard-test-");
        try {
            java.nio.file.Path fake=dir.resolve("systemctl");
            java.nio.file.Files.writeString(fake,"#!/usr/bin/python3\nimport os,sys\nfrom pathlib import Path\nprint(Path(os.environ['GUARD_FIXTURES'],sys.argv[2]).read_text(),end='')\n");
            fake.toFile().setExecutable(true);
            java.nio.file.Files.writeString(dir.resolve("cloud-early-config.service"),early);
            java.nio.file.Files.writeString(dir.resolve("cloud-init-local.service"),local);
            java.nio.file.Files.writeString(dir.resolve("cloud-init.service"),network);
            ProcessBuilder builder=new ProcessBuilder("bash","-c","set -e; "+wrapper.buildSharedFsBootstrapGuard()+"printf NETWORK_READY");
            builder.environment().put("PATH",dir+":"+System.getenv("PATH"));
            builder.environment().put("GUARD_FIXTURES",dir.toString());
            Process process=builder.start();
            Assert.assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
            return new String[]{String.valueOf(process.exitValue()),
                new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)};
        } finally {
            try(java.util.stream.Stream<java.nio.file.Path> files=java.nio.file.Files.walk(dir)) {
                for(java.nio.file.Path f:files.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))java.nio.file.Files.deleteIfExists(f);
            }
        }
    }

    @Test public void testFinishedGuardDoesNotPolluteExistingHelperJson() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        String[] result=runGuard(ready,ready,ready);
        Assert.assertEquals("0",result[0]);Assert.assertEquals("NETWORK_READY",result[1]);Assert.assertEquals("",result[2]);
    }

    @Test public void testActualGuardWaitsForFinishedSuccessfulBootstrap() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        String[] result=runGuard(properties("loaded","activating","start","success","23"),ready,ready);
        Assert.assertEquals("75",result[0]);Assert.assertTrue(result[2].contains("SHAREDFS_BOOTSTRAP_PENDING"));
        Assert.assertEquals("PENDING",com.google.gson.JsonParser.parseString(result[1]).getAsJsonObject().getAsJsonObject("sharedFsBootstrap").get("state").getAsString());
        Assert.assertFalse(result[1].contains("NETWORK_READY"));
    }

    @Test public void testQueuedNetworkJobCannotBeMistakenForInactiveFinishedStage() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        String[] result=runGuard(ready,ready,properties("loaded","inactive","dead","success","341"));
        Assert.assertEquals("75",result[0]);Assert.assertTrue(result[2].contains("NETWORK_STAGE_RUNNING"));
    }

    @Test public void testRunningLocalNetworkStageBlocksStaticApply() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        Assert.assertEquals("75",runGuard(ready,properties("loaded","activating","start","success","10"),ready)[0]);
    }

    @Test public void testQueuedEarlyRestartCannotReusePreviousSuccess() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        Assert.assertEquals("75",runGuard(properties("loaded","active","exited","success","27"),ready,ready)[0]);
    }

    @Test public void testNoMetadataFailureIsFinishedForStaticMode() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        Assert.assertEquals("0",runGuard(ready,ready,properties("loaded","failed","failed","exit-code",""))[0]);
    }

    @Test public void testMissingOptionalCloudInitPreservesLegacyCompatibility() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        String missing=properties("not-found","inactive","dead","success","");
        Assert.assertEquals("0",runGuard(ready,missing,missing)[0]);
        Assert.assertEquals("76",runGuard(missing,ready,ready)[0]);
    }

    @Test public void testFailedRequiredBootstrapIsTerminalWithActualState() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        String[] result=runGuard(properties("loaded","failed","failed","exit-code",""),ready,ready);
        Assert.assertEquals("76",result[0]);Assert.assertTrue(result[2].contains("EARLY_CONFIG_FAILED"));
        Assert.assertTrue(result[2].contains("exit-code"));
    }

    @Test public void testMalformedSystemctlOutputIsFailClosed() throws Exception {
        String ready=properties("loaded","active","exited","success","");
        Assert.assertEquals("76",runGuard("unparseable",ready,ready)[0]);
    }

    @Test public void testGuardRunsAfterIntentAndBeforeNetworkSuccess() {
        StorageServiceHostCommand command=new StorageServiceHostCommand("test","configure-sharedfs-static-network","{}",60,Collections.emptySet());
        String shell=wrapper.buildStorageCtlShell(command);
        int guard=shell.indexOf(wrapper.buildSharedFsBootstrapGuard());
        Assert.assertTrue(guard>shell.indexOf("mv "));
        Assert.assertTrue(guard<shell.indexOf("systemctl restart ablestack-sharedfs-network.service"));
    }
    @Test public void testCanonicalHelperIsPackagedWithoutInlineDivergence() throws Exception {
        java.lang.reflect.Field field = LibvirtStorageServiceHostCommandWrapper.class.getDeclaredField("SHAREDFS_NETWORK_HELPER_CONTENT");
        field.setAccessible(true);
        String helper = (String) field.get(null);
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("../../../systemvm/debian/usr/local/sbin/ablestack-sharedfs-network"));
        Assert.assertEquals(source, helper);
        Assert.assertFalse(helper.contains("'addr', 'flush'"));
        StorageServiceHostCommand command = new StorageServiceHostCommand("test", "configure-sharedfs-static-network", "{}", 60, Collections.emptySet());
        String shell = wrapper.buildStorageCtlShell(command);
        Assert.assertTrue(shell.indexOf("--validate") < shell.indexOf("mv /etc/ablestack-storage/sharedfs-network.json.tmp"));
        Assert.assertTrue(shell.indexOf("mv /etc/ablestack-storage/sharedfs-network.json.tmp") < shell.indexOf(wrapper.buildSharedFsBootstrapGuard()));
        Assert.assertTrue(shell.contains("dhclient-enter-hooks.d/ablestack-sharedfs"));
    }


    private StorageServiceHostCommand command(String payload) {
        return new StorageServiceHostCommand("test", "configuration capability show", payload, 20, Collections.emptySet());
    }

    private Domain recordingDomain(List<JsonObject> executions) throws Exception {
        Domain domain = mock(Domain.class);
        AtomicLong pid = new AtomicLong(10);
        when(domain.qemuAgentCommand(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
            JsonObject request = JsonParser.parseString(call.getArgument(0)).getAsJsonObject();
            if ("guest-exec".equals(request.get("execute").getAsString())) {
                executions.add(request);
                return "{\"return\":{\"pid\":" + pid.incrementAndGet() + "}}";
            }
            return "{\"return\":{\"exited\":true,\"exitcode\":0}}";
        });
        return domain;
    }

    @Test public void smallAndEmptyPayloadUseStdinAndPreserveBytesWithoutShellInterpolation() throws Exception {
        List<JsonObject> requests = new ArrayList<>();
        for (String payload : new String[] {"", "{\"label\":\"한글😀 $(touch never) ' \\u003d\"}"}) {
            JsonObject request = JsonParser.parseString(wrapper.buildGuestExecCommand(command(payload))).getAsJsonObject();
            requests.add(request);
            JsonObject args = request.getAsJsonObject("arguments");
            Assert.assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8),
                    Base64.getDecoder().decode(args.get("input-data").getAsString()));
            String shell = args.getAsJsonArray("arg").get(1).getAsString();
            Assert.assertTrue(shell.length() < 1024);
            Assert.assertTrue(shell.contains("cat >"));
            Assert.assertTrue(shell.contains("trap"));
            Assert.assertFalse(shell.contains("touch never"));
        }
        String output = System.getProperty("sharedfs.stdin.requests.output");
        if (output != null) Files.writeString(Path.of(output + ".small.json"), requests.toString());
    }

    @Test public void staticAndDhcpNetworkDispatchKeepTheExistingDedicatedContract() {
        JsonObject args = JsonParser.parseString(wrapper.buildGuestExecCommand(
                new StorageServiceHostCommand("test", "configure-sharedfs-static-network",
                        "{\"mode\":\"DHCP\"}", 60, Collections.emptySet())))
                .getAsJsonObject().getAsJsonObject("arguments");
        Assert.assertFalse(args.has("input-data"));
        Assert.assertTrue(args.getAsJsonArray("arg").get(1).getAsString().contains("sharedfs-network.json"));
        Assert.assertFalse(args.getAsJsonArray("arg").get(1).getAsString().contains("cat >"));
    }

    @Test public void actualFailed49ExportPayloadAndOneMiBHaveBoundedArgumentsAndExactStdinChunks() throws Exception {
        String actual = Files.readString(Path.of("src/test/resources/sharedfs/configuration-49-stdin.json"));
        Assert.assertEquals(98346, actual.getBytes(StandardCharsets.UTF_8).length);
        for (String payload : new String[] {actual, "{\"x\":\"" + "x".repeat(1024 * 1024 - 8) + "\"}",
                "{\"unicode\":\"" + "한글😀".repeat(18000) + "\"}"}) {
            List<JsonObject> executions = new ArrayList<>();
            wrapper.executeGuestCommand(recordingDomain(executions), command(payload));
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            for (JsonObject execution : executions) {
                JsonObject args = execution.getAsJsonObject("arguments");
                for (var arg : args.getAsJsonArray("arg")) Assert.assertTrue(arg.getAsString().getBytes(StandardCharsets.UTF_8).length < 4096);
                if (args.has("input-data")) {
                    byte[] chunk = Base64.getDecoder().decode(args.get("input-data").getAsString());
                    Assert.assertTrue(chunk.length <= LibvirtStorageServiceHostCommandWrapper.STDIN_CHUNK_BYTES);
                    received.write(chunk);
                }
            }
            Assert.assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), received.toByteArray());
            JsonObject last = executions.get(executions.size() - 1).getAsJsonObject("arguments");
            Assert.assertEquals("/bin/bash", last.get("path").getAsString());
            Assert.assertTrue(last.getAsJsonArray("arg").get(1).getAsString().contains("configuration capability show"));
            Assert.assertTrue(last.getAsJsonArray("arg").get(1).getAsString().contains("trap"));
            Assert.assertFalse(last.has("input-data"));
            String export = System.getProperty("sharedfs.stdin.requests.output");
            if (export != null && payload.equals(actual)) Files.writeString(Path.of(export), executions.toString());
            if (export != null && payload.getBytes(StandardCharsets.UTF_8).length == 1024 * 1024)
                Files.writeString(Path.of(export + ".1m.json"), executions.toString());
        }
    }

    @Test public void stagingFailureCleansOnlyItsPrivateFileAndNeverStartsStorageCtl() throws Exception {
        List<JsonObject> executions = new ArrayList<>();
        Domain domain = mock(Domain.class);
        when(domain.qemuAgentCommand(anyString(), anyInt(), anyInt())).thenAnswer(call -> {
            JsonObject request = JsonParser.parseString(call.getArgument(0)).getAsJsonObject();
            if ("guest-exec".equals(request.get("execute").getAsString())) {
                executions.add(request);
                if (request.getAsJsonObject("arguments").has("input-data")) throw libvirtFailure("transport failed while staging");
                return "{\"return\":{\"pid\":42}}";
            }
            return "{\"return\":{\"exited\":true,\"exitcode\":0}}";
        });
        try { wrapper.executeGuestCommand(domain, command("x".repeat(98346))); Assert.fail(); }
        catch (LibvirtStorageServiceHostCommandWrapper.PayloadTransferException expected) { }
        Assert.assertEquals(2, executions.size());
        Assert.assertTrue(executions.get(1).toString().contains("unlink"));
        Assert.assertFalse(executions.stream().anyMatch(x -> x.toString().contains("/usr/local/bin/ablestack-storagectl")));
    }

    @Test public void actualSpawnFailureAnswerIsTypedButLostReplyRemainsUnconfirmed() throws Exception {
        for (String message : new String[] {"guest agent command failed: Failed to execute child process /bin/bash (Argument list too long)",
                "guest agent command timed out"}) {
            Domain domain = mock(Domain.class); DomainInfo info = new DomainInfo(); info.state = DomainState.VIR_DOMAIN_RUNNING;
            when(domain.getInfo()).thenReturn(info);
            LibvirtException failure = libvirtFailure(message);
            when(domain.qemuAgentCommand(anyString(), anyInt(), anyInt())).thenThrow(failure);
            LibvirtComputingResource resource = mock(LibvirtComputingResource.class);
            LibvirtUtilitiesHelper helper = mock(LibvirtUtilitiesHelper.class); Connect connection = mock(Connect.class);
            when(resource.getLibvirtUtilitiesHelper()).thenReturn(helper); when(helper.getConnection()).thenReturn(connection);
            when(resource.getDomain(connection, "test")).thenReturn(domain);
            StorageServiceHostAnswer answer = (StorageServiceHostAnswer)wrapper.execute(command("{}"), resource);
            Assert.assertFalse(answer.getResult());
            if (message.contains("Argument list too long")) {
                JsonObject dispatch = JsonParser.parseString(answer.getResultJson()).getAsJsonObject().getAsJsonObject("dispatch");
                Assert.assertEquals("NOT_STARTED", dispatch.get("state").getAsString());
                Assert.assertEquals("QGA_EXEC_ARGUMENT_TOO_LARGE", dispatch.get("code").getAsString());
            } else Assert.assertNull(answer.getResultJson());
        }
    }

    private LibvirtException libvirtFailure(String message) {
        LibvirtException failure = mock(LibvirtException.class);
        when(failure.getMessage()).thenReturn(message);
        return failure;
    }

}
