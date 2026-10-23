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
}
