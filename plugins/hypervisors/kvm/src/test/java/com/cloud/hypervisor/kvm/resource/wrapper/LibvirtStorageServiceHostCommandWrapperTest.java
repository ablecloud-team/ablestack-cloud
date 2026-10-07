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
    @Test public void identityImportUsesMemoryPipeAndNeverCreatesPayloadKeyFile() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("sharedfs-test",
                "identity capsule import", "{'credentialPrivateKey':'synthetic'}", 60, Collections.singleton("credentialPrivateKey"));
        String shell = wrapper.buildStorageCtlShell(command);
        Assert.assertTrue(shell.contains("identity capsule import /dev/stdin"));
        Assert.assertFalse(shell.contains("mktemp"));Assert.assertFalse(shell.contains(">"));
        Assert.assertFalse(shell.contains("synthetic"));
    }

    @Test public void identityWrappingCredentialUsesQgaStdinAndCannotAppearInGuestArguments() {
        String payload = "{\"credentialPrivateKey\":\"synthetic-private-material\"}";
        StorageServiceHostCommand command = new StorageServiceHostCommand("sharedfs-test", "identity capsule import", payload, 60, Collections.singleton("credentialPrivateKey"));
        com.google.gson.JsonObject request = new com.google.gson.JsonParser().parse(wrapper.buildGuestExecCommand(command)).getAsJsonObject();
        com.google.gson.JsonObject arguments = request.getAsJsonObject("arguments");
        Assert.assertEquals(payload, new String(java.util.Base64.getDecoder().decode(arguments.get("input-data").getAsString()), java.nio.charset.StandardCharsets.UTF_8));
        Assert.assertFalse(arguments.get("arg").toString().contains("synthetic"));
        Assert.assertFalse(arguments.get("arg").toString().contains(java.util.Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        Assert.assertFalse(wrapper.buildStorageCtlShell(command).contains("printf"));
    }

    @Test public void protectedTransportIsAdvertisedOnlyWithSuccessfulNativeCapabilityProbe() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("sharedfs-test", "identity capsule capabilities", "{}", 30, Collections.emptySet());
        Assert.assertTrue(wrapper.identityTransportObservation(command, "{\"success\":true}").contains("protectedStdinTransport"));
        Assert.assertFalse(wrapper.identityTransportObservation(command, "{\"success\":false}").contains("protectedStdinTransport"));
        Assert.assertFalse(wrapper.identityTransportObservation(command, "unavailable").contains("protectedStdinTransport"));
    }

}
