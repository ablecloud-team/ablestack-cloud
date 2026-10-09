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


    private org.libvirt.Domain chunkDomain(java.util.List<String> events, java.io.ByteArrayOutputStream captured, String failure) throws Exception {
        org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        final int[] observer = {0};
        org.mockito.Mockito.when(domain.qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.eq(0)))
                .thenAnswer(invocation -> {
                    com.google.gson.JsonObject qga = com.google.gson.JsonParser.parseString(invocation.getArgument(0)).getAsJsonObject();
                    String method = qga.get("execute").getAsString();com.google.gson.JsonObject arguments = qga.getAsJsonObject("arguments");
                    events.add(method);
                    com.google.gson.JsonObject response = new com.google.gson.JsonObject();
                    if ("guest-info".equals(method)) {
                        com.google.gson.JsonArray capabilities = new com.google.gson.JsonArray();
                        for (String name : new String[]{"guest-file-open", "guest-file-write", "guest-file-flush", "guest-file-close", "guest-exec", "guest-exec-status"}) {
                            if ("unsupported".equals(failure) && "guest-file-write".equals(name)) continue;
                            com.google.gson.JsonObject row = new com.google.gson.JsonObject();row.addProperty("name", name);row.addProperty("enabled", true);capabilities.add(row);
                        }
                        com.google.gson.JsonObject info = new com.google.gson.JsonObject();info.add("supported_commands", capabilities);response.add("return", info);
                    } else if ("guest-exec".equals(method)) {
                        Assert.assertFalse(arguments.toString().contains("PRIVATE_SENTINEL"));
                        Assert.assertFalse(arguments.has("input-data"));
                        if (observer[0] == 0) Assert.assertTrue(arguments.getAsJsonArray("arg").get(4).getAsInt() <= 300);
                        com.google.gson.JsonObject pid = new com.google.gson.JsonObject();pid.addProperty("pid", observer[0]++ == 0 ? 17 : 20);response.add("return", pid);
                    } else if ("guest-exec-status".equals(method)) {
                        com.google.gson.JsonObject owner = new com.google.gson.JsonObject();owner.addProperty("ready", true);owner.addProperty("rootOwned", true);
                        owner.addProperty("receiverScriptVerified", true);owner.addProperty("pid", 17);owner.addProperty("startTicks", 99);
                        owner.addProperty("dataInode", "ownership".equals(failure) && observer[0] > 2 ? 44 : 33);owner.addProperty("controlInode", 34);
                        com.google.gson.JsonObject status = new com.google.gson.JsonObject();status.addProperty("exited", true);status.addProperty("exitcode", 0);
                        status.addProperty("out-data", java.util.Base64.getEncoder().encodeToString(owner.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        response.add("return", status);
                    } else if ("guest-file-open".equals(method)) {
                        response.addProperty("return", arguments.get("path").getAsString().endsWith("/16") ? 1 : 2);
                    } else if ("guest-file-write".equals(method)) {
                        byte[] bytes = java.util.Base64.getDecoder().decode(arguments.get("buf-b64").getAsString());
                        int handle = arguments.get("handle").getAsInt();
                        if (handle == 1) {
                            Assert.assertTrue(bytes.length <= 32768);captured.write(bytes);
                        } else {Assert.assertArrayEquals(new byte[]{1}, bytes);events.add("COMMIT");}
                        com.google.gson.JsonObject count = new com.google.gson.JsonObject();count.addProperty("count", bytes.length - ("short".equals(failure) && handle == 1 ? 1 : 0));
                        response.add("return", count);
                    } else if ("guest-file-close".equals(method)) {
                        int handle = arguments.get("handle").getAsInt();events.add("CLOSE" + handle);
                        if ("close".equals(failure) && handle == 1 || "commit-close".equals(failure) && handle == 2) response.add("return", com.google.gson.JsonNull.INSTANCE);
                        else response.add("return", new com.google.gson.JsonObject());
                    } else if ("guest-file-flush".equals(method)) response.add("return", new com.google.gson.JsonObject());
                    else throw new AssertionError(method);
                    return response.toString();
                });
        return domain;
    }

    @Test public void largeProtectedInputUsesActualDefaultSenderAndObserverWithBoundedChunksAndCloseBeforeCommit() throws Exception {
        java.util.List<String> events = new java.util.ArrayList<>();java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        org.libvirt.Domain domain = chunkDomain(events, captured, "");
        String payload = "PRIVATE_SENTINEL" + "X".repeat(1518638 - "PRIVATE_SENTINEL".length());
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", payload, 120, Collections.singleton("credentialPrivateKey"));
        Assert.assertEquals(17, wrapper.executeGuestCommand(domain, command));
        Assert.assertArrayEquals(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), captured.toByteArray());
        Assert.assertEquals(48, events.stream().filter("guest-file-write"::equals).count());
        Assert.assertTrue(events.indexOf("CLOSE1") < events.indexOf("COMMIT"));Assert.assertTrue(events.contains("CLOSE2"));
    }

    @Test public void shortUploadAndUnverifiedDataCloseNeverSendCommitAndCloseDataOnce() throws Exception {
        for (String failure : new String[]{"short", "close"}) {
            java.util.List<String> events = new java.util.ArrayList<>();
            org.libvirt.Domain domain = chunkDomain(events, new java.io.ByteArrayOutputStream(), failure);
            StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", "X".repeat(40000), 120, Collections.singleton("capsule"));
            try {wrapper.executeGuestCommand(domain, command);Assert.fail();}
            catch (IllegalStateException expected) {Assert.assertFalse(expected.getMessage().contains("PRIVATE"));}
            Assert.assertFalse(events.contains("COMMIT"));Assert.assertEquals(1, events.stream().filter("CLOSE1"::equals).count());
        }
    }

    @Test public void receiverOwnershipChangeRejectsBeforeCommitAndDoesNotReplayProducer() throws Exception {
        java.util.List<String> events = new java.util.ArrayList<>();
        org.libvirt.Domain domain = chunkDomain(events, new java.io.ByteArrayOutputStream(), "ownership");
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", "X".repeat(40000), 120, Collections.singleton("capsule"));
        try {wrapper.executeGuestCommand(domain, command);Assert.fail();}
        catch (IllegalStateException expected) {Assert.assertTrue(expected.getMessage().contains("identity changed"));}
        Assert.assertFalse(events.contains("COMMIT"));Assert.assertEquals(3, events.stream().filter("guest-exec"::equals).count());
    }

    @Test public void afterCommitCloseFailureIsUnknownAndNeverClaimsNoCliOrRetries() throws Exception {
        java.util.List<String> events = new java.util.ArrayList<>();
        org.libvirt.Domain domain = chunkDomain(events, new java.io.ByteArrayOutputStream(), "commit-close");
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", "X".repeat(40000), 120, Collections.singleton("capsule"));
        try {wrapper.executeGuestCommand(domain, command);Assert.fail();}
        catch (IllegalStateException expected) {Assert.assertTrue(expected.getMessage().contains("unknown"));}
        Assert.assertEquals(1, events.stream().filter("COMMIT"::equals).count());Assert.assertEquals(1, events.stream().filter("CLOSE2"::equals).count());
    }

    @Test public void unsupportedChunkApisOrInsufficientBudgetRejectBeforeReceiverOrPrivateUpload() throws Exception {
        java.util.List<String> events = new java.util.ArrayList<>();
        org.libvirt.Domain domain = chunkDomain(events, new java.io.ByteArrayOutputStream(), "unsupported");
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", "X".repeat(40000), 120, Collections.singleton("capsule"));
        try {wrapper.executeGuestCommand(domain, command);Assert.fail();} catch (IllegalStateException expected) { }
        Assert.assertEquals(Collections.singletonList("guest-info"), events);
        org.mockito.Mockito.clearInvocations(domain);events.clear();
        command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", "X".repeat(40000), 2, Collections.singleton("capsule"));
        try {wrapper.executeGuestCommand(domain, command);Assert.fail();} catch (IllegalArgumentException expected) { }
        org.mockito.Mockito.verifyNoInteractions(domain);
        java.util.List<String> longEvents = new java.util.ArrayList<>();
        org.libvirt.Domain longDomain = chunkDomain(longEvents, new java.io.ByteArrayOutputStream(), "");
        command = new StorageServiceHostCommand("same-vm", "operation generation render-stage", "X".repeat(40000), 600, Collections.singleton("capsule"));
        Assert.assertEquals(17, wrapper.executeGuestCommand(longDomain, command));
        Assert.assertTrue(longEvents.contains("COMMIT"));
    }

    @Test public void largeProtectedStatusUsesRemainingBudgetAndStopsBeforeSubsecondRpc() throws Exception {
        java.lang.reflect.Field field = LibvirtStorageServiceHostCommandWrapper.class.getDeclaredField("protectedStdinDeadline");field.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<Long> deadline = (ThreadLocal<Long>) field.get(wrapper);
        org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        org.mockito.Mockito.when(domain.qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.eq(0)))
                .thenReturn("{\"return\":{\"exited\":true,\"exitcode\":0,\"out-data\":\"e30=\"}}");
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", "{}", 120, Collections.singleton("capsule"));
        try {
            deadline.set(System.currentTimeMillis() + 3500);
            Assert.assertTrue(wrapper.waitForGuestCommand(command, domain, 17).getResult());
            org.mockito.ArgumentCaptor<Integer> timeout = org.mockito.ArgumentCaptor.forClass(Integer.class);
            org.mockito.Mockito.verify(domain).qemuAgentCommand(org.mockito.Mockito.anyString(), timeout.capture(), org.mockito.Mockito.eq(0));
            Assert.assertTrue(timeout.getValue() >= 1 && timeout.getValue() <= 3);
            org.mockito.Mockito.clearInvocations(domain);deadline.set(System.currentTimeMillis() + 500);
            Assert.assertFalse(wrapper.waitForGuestCommand(command, domain, 17).getResult());org.mockito.Mockito.verifyNoInteractions(domain);
        } finally {deadline.remove();}
    }

    @Test public void smallProtectedInputKeepsOriginalSingleGuestExecStdinShape() throws Exception {
        org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        org.mockito.Mockito.when(domain.qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.eq(120), org.mockito.Mockito.eq(0)))
                .thenReturn("{\"return\":{\"pid\":21}}");
        String payload = "{\"credentialPrivateKey\":\"PRIVATE_SENTINEL\"}";
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", payload, 120, Collections.singleton("credentialPrivateKey"));
        Assert.assertEquals(21, wrapper.executeGuestCommand(domain, command));
        org.mockito.ArgumentCaptor<String> request = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(domain).qemuAgentCommand(request.capture(), org.mockito.Mockito.eq(120), org.mockito.Mockito.eq(0));
        com.google.gson.JsonObject arguments = com.google.gson.JsonParser.parseString(request.getValue()).getAsJsonObject().getAsJsonObject("arguments");
        Assert.assertEquals(payload, new String(java.util.Base64.getDecoder().decode(arguments.get("input-data").getAsString()), java.nio.charset.StandardCharsets.UTF_8));
        Assert.assertFalse(arguments.get("arg").toString().contains("PRIVATE_SENTINEL"));
    }

    @Test public void chunkByteBudgetsAreExplicitCapabilityMetadataAndNoDiskFallbackIsAdvertised() {
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule capabilities", "{}", 30, Collections.emptySet());
        com.google.gson.JsonObject capability = com.google.gson.JsonParser.parseString(wrapper.identityTransportObservation(command, "{\"success\":true}")).getAsJsonObject();
        Assert.assertEquals(32768, LibvirtStorageServiceHostCommandWrapper.DIRECT_PROTECTED_STDIN_MAX_BYTES);
        Assert.assertEquals(32768, LibvirtStorageServiceHostCommandWrapper.PROTECTED_STDIN_CHUNK_BYTES);
        Assert.assertEquals(64 * 1024 * 1024, LibvirtStorageServiceHostCommandWrapper.PROTECTED_STDIN_MAX_BYTES);
        Assert.assertFalse(capability.has("protectedStdinChunkBytes"));Assert.assertFalse(capability.has("protectedStdinMaxBytes"));
        Assert.assertFalse(capability.toString().contains("disk"));
    }

}
