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

    @Test
    public void testActualGuardWaitsForFinishedSuccessfulBootstrap() throws Exception {
        LibvirtStorageServiceHostCommandWrapper wrapper=new LibvirtStorageServiceHostCommandWrapper();
        String[] states={"ActiveState=activating\nSubState=start\nResult=success",
            "ActiveState=failed\nSubState=failed\nResult=exit-code",
            "ActiveState=active\nSubState=running\nResult=success",
            "ActiveState=active\nSubState=exited\nResult=success"};
        for(int i=0;i<states.length;i++){
            String script="systemctl(){ printf '%s' '"+states[i]+"'; }; "+wrapper.buildSharedFsBootstrapGuard()+"printf NETWORK_READY";
            Process process=new ProcessBuilder("bash","-c",script).start();
            Assert.assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
            String out=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            String err=new String(process.getErrorStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            Assert.assertEquals(i==3?0:75,process.exitValue());
            Assert.assertEquals(i==3,out.contains("NETWORK_READY"));
            if(i!=3)Assert.assertTrue(err.contains("SHAREDFS_BOOTSTRAP_PENDING"));
        }
    }

    @Test
    public void testGuardRunsAfterIntentAndBeforeNetworkSuccess() {
        LibvirtStorageServiceHostCommandWrapper wrapper=new LibvirtStorageServiceHostCommandWrapper();
        StorageServiceHostCommand command=new StorageServiceHostCommand("test","configure-sharedfs-static-network","{}",60,Collections.emptySet());
        String shell=wrapper.buildStorageCtlShell(command);
        Assert.assertTrue(shell.indexOf("SHAREDFS_BOOTSTRAP_PENDING")>shell.indexOf("mv "));
        Assert.assertTrue(shell.indexOf("SHAREDFS_BOOTSTRAP_PENDING")<shell.indexOf("systemctl restart ablestack-sharedfs-network.service"));
    }
}
