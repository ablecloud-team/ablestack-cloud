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

    @Test public void renderedPrivateKeysAndTransientCredentialsUseOnlyQgaStdinWithoutDiskOrArgvCopies() {
        String payload="{\"checkpointPrivateKey\":\"synthetic-key-only\",\"transientCredentials\":{\"password\":\"synthetic-password-only\"}}";
        for(String operation:new String[]{"operation generation render-stage","operation generation render-activate","operation generation render-rollback"}) {
            StorageServiceHostCommand command=new StorageServiceHostCommand("same-vm",operation,payload,60,Collections.emptySet());
            com.google.gson.JsonObject arguments=com.google.gson.JsonParser.parseString(wrapper.buildGuestExecCommand(command)).getAsJsonObject().getAsJsonObject("arguments");
            Assert.assertEquals(payload,new String(java.util.Base64.getDecoder().decode(arguments.get("input-data").getAsString()),java.nio.charset.StandardCharsets.UTF_8));
            String shell=wrapper.buildStorageCtlShell(command);Assert.assertEquals("/usr/local/bin/ablestack-storagectl "+operation+" /dev/stdin",shell);
            Assert.assertFalse(shell.contains("mktemp"));Assert.assertFalse(arguments.get("arg").toString().contains("synthetic"));
            Assert.assertFalse(arguments.get("arg").toString().contains(java.util.Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            if (!operation.endsWith("render-stage")) {Assert.assertFalse(wrapper.commandFailureDetails(command,1,payload,payload).contains("synthetic"));Assert.assertFalse(wrapper.commandExceptionDetails(command,payload).contains("synthetic"));}
        }
    }
    @Test public void allExplicitlyMaskedGuestCredentialsAvoidPayloadTempfilesAndProcessArguments() {
        String payload="{\"password\":\"synthetic-only\"}";
        StorageServiceHostCommand command=new StorageServiceHostCommand("same-vm","smb share apply",payload,60,Collections.singleton("password"));
        com.google.gson.JsonObject arguments=com.google.gson.JsonParser.parseString(wrapper.buildGuestExecCommand(command)).getAsJsonObject().getAsJsonObject("arguments");
        Assert.assertTrue(arguments.has("input-data"));Assert.assertFalse(wrapper.buildStorageCtlShell(command).contains("mktemp"));Assert.assertFalse(arguments.get("arg").toString().contains("synthetic"));
    }

    private static org.libvirt.LibvirtException libvirtFailure(org.libvirt.jna.virError captured) throws Exception {
        java.lang.reflect.Constructor<org.libvirt.LibvirtException> constructor = org.libvirt.LibvirtException.class.getDeclaredConstructor(org.libvirt.Error.class);
        constructor.setAccessible(true);return constructor.newInstance(new org.libvirt.Error(captured));
    }

    @Test public void safeTransportDiagnosticsContainOnlyFixedClassificationAndNeverSecretErrorFields() {
        String secret = "PRIVATE_SENTINEL_NEVER_LOG";
        IllegalArgumentException failure = new IllegalArgumentException(secret);
        failure.setStackTrace(new StackTraceElement[]{new StackTraceElement(LibvirtStorageServiceHostCommandWrapper.class.getName(), "decodeGuestData", secret, 1)});
        com.google.gson.JsonObject value = wrapper.safeTransportFailure("STATUS", failure, 120000, 17);
        Assert.assertEquals(java.util.Set.of("kind", "stage", "exceptionClass", "elapsedMillis", "connectionToken", "libvirtCode", "libvirtDomain"), value.keySet());
        Assert.assertEquals("DECODE", value.get("stage").getAsString());Assert.assertEquals("ARGUMENT", value.get("exceptionClass").getAsString());
        Assert.assertEquals(17, value.get("connectionToken").getAsInt());Assert.assertFalse(value.toString().contains(secret));
        Assert.assertTrue(value.get("libvirtCode").isJsonNull());Assert.assertTrue(value.get("libvirtDomain").isJsonNull());
        Assert.assertEquals("UNKNOWN", wrapper.safeTransportFailure(secret, new RuntimeException(secret), -1, 0).get("stage").getAsString());
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule export-local-source", secret, 120, Collections.singleton("capsule"));
        Assert.assertEquals("Sensitive Storage Service host command failed; secret-bearing diagnostic omitted", wrapper.commandExceptionDetails(command, secret));
    }

    @Test public void nativeLibvirtNumericDiagnosticsUseCapturedKnownAbiIndicesAndUnknownStaysNull() throws Exception {
        org.libvirt.jna.virError nativeError = new org.libvirt.jna.virError();
        nativeError.code = org.libvirt.Error.ErrorNumber.VIR_ERR_OPERATION_TIMEOUT.ordinal();
        nativeError.domain = org.libvirt.Error.ErrorDomain.VIR_FROM_QEMU.ordinal();
        nativeError.message = "PRIVATE_MESSAGE";nativeError.str1 = "PRIVATE_STR1";nativeError.str2 = "PRIVATE_STR2";nativeError.str3 = "PRIVATE_STR3";
        org.libvirt.LibvirtException failure = libvirtFailure(nativeError);
        com.google.gson.JsonObject value = wrapper.safeTransportFailure("STATUS", failure, 120001, 31);
        Assert.assertEquals(nativeError.code, value.get("libvirtCode").getAsInt());Assert.assertEquals(nativeError.domain, value.get("libvirtDomain").getAsInt());
        Assert.assertEquals("LIBVIRT", value.get("exceptionClass").getAsString());Assert.assertFalse(value.toString().contains("PRIVATE"));
        nativeError.code = 9999;nativeError.domain = 9999;
        value = wrapper.safeTransportFailure("STATUS", libvirtFailure(nativeError), 1, 31);
        Assert.assertTrue(value.get("libvirtCode").isJsonNull());Assert.assertTrue(value.get("libvirtDomain").isJsonNull());
    }

    @Test public void realWrapperMaskedExceptionAnswerRemainsUnchangedAndNoStructuredPayloadIsReturned() throws Exception {
        com.cloud.hypervisor.kvm.resource.LibvirtComputingResource resource = org.mockito.Mockito.mock(com.cloud.hypervisor.kvm.resource.LibvirtComputingResource.class);
        LibvirtUtilitiesHelper utility = org.mockito.Mockito.mock(LibvirtUtilitiesHelper.class);
        org.mockito.Mockito.when(resource.getLibvirtUtilitiesHelper()).thenReturn(utility);
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule export-local-source", "PRIVATE_REQUEST", 120, Collections.singleton("capsule"));
        org.apache.logging.log4j.Logger capturedLogger = org.mockito.Mockito.mock(org.apache.logging.log4j.Logger.class);
        java.lang.reflect.Field loggerField = com.cloud.resource.CommandWrapper.class.getDeclaredField("logger");
        loggerField.setAccessible(true);loggerField.set(wrapper, capturedLogger);
        for (Exception failure : new Exception[]{new IllegalStateException("PRIVATE_RUNTIME_MESSAGE"), libvirtFailure(new org.libvirt.jna.virError())}) {
            org.mockito.Mockito.reset(utility, capturedLogger);
            org.mockito.Mockito.when(utility.getConnection()).thenThrow(failure);
            com.cloud.agent.api.StorageServiceHostAnswer answer = (com.cloud.agent.api.StorageServiceHostAnswer) wrapper.execute(command, resource);
            Assert.assertFalse(answer.getResult());
            Assert.assertEquals("Sensitive Storage Service host command failed; secret-bearing diagnostic omitted", answer.getDetails());
            Assert.assertNull(answer.getResultJson());
            org.mockito.ArgumentCaptor<Object> logged = org.mockito.ArgumentCaptor.forClass(Object.class);
            org.mockito.Mockito.verify(capturedLogger).warn(org.mockito.Mockito.eq("Storage Service safe transport failure {}"), logged.capture());
            Assert.assertTrue(logged.getValue() instanceof String);Assert.assertFalse(logged.getValue().toString().contains("PRIVATE"));
            com.google.gson.JsonObject diagnostic = com.google.gson.JsonParser.parseString(logged.getValue().toString()).getAsJsonObject();
            Assert.assertEquals("CONNECT", diagnostic.get("stage").getAsString());
            Assert.assertEquals(failure instanceof org.libvirt.LibvirtException ? "LIBVIRT" : "STATE", diagnostic.get("exceptionClass").getAsString());
        }
        org.mockito.Mockito.reset(capturedLogger);
        org.mockito.Mockito.doThrow(new IllegalStateException("PRIVATE_LOGGER_FAILURE")).when(capturedLogger).warn(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(Object.class));
        com.cloud.agent.api.StorageServiceHostAnswer original = (com.cloud.agent.api.StorageServiceHostAnswer) wrapper.execute(command, resource);
        Assert.assertFalse(original.getResult());Assert.assertNull(original.getResultJson());
        Assert.assertEquals("Sensitive Storage Service host command failed; secret-bearing diagnostic omitted", original.getDetails());
    }

    @Test public void successfulAndExpiredWaitKeepOriginalResultJsonAndDeadlineBehavior() throws Exception {
        org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule export-local-source", "{}", 120, Collections.singleton("capsule"));
        String stdout = "{\"success\":true,\"PUBLIC_RESULT\":1}";
        com.google.gson.JsonObject response = new com.google.gson.JsonObject();response.addProperty("exited", true);response.addProperty("exitcode", 0);
        response.addProperty("out-data", java.util.Base64.getEncoder().encodeToString(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        com.google.gson.JsonObject reply = new com.google.gson.JsonObject();reply.add("return", response);
        org.mockito.Mockito.when(domain.qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(120), org.mockito.Mockito.eq(0))).thenReturn(reply.toString());
        com.cloud.agent.api.StorageServiceHostAnswer answer = (com.cloud.agent.api.StorageServiceHostAnswer) wrapper.waitForGuestCommand(command, domain, 1);
        Assert.assertTrue(answer.getResult());Assert.assertEquals(stdout, answer.getResultJson());Assert.assertEquals("Storage Service command completed", answer.getDetails());
        StorageServiceHostCommand expired = new StorageServiceHostCommand("same-vm", "identity capsule export-local-source", "{}", 0, Collections.singleton("capsule"));
        answer = (com.cloud.agent.api.StorageServiceHostAnswer) wrapper.waitForGuestCommand(expired, domain, 1);
        Assert.assertFalse(answer.getResult());Assert.assertEquals("Timed out waiting for Storage Service QGA command", answer.getDetails());Assert.assertNull(answer.getResultJson());
    }

}
