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
    public void staticInstallerUsesCanonicalBootOrderingAndReconcileUnit() {
        String shell = wrapper.buildStorageCtlShell(new StorageServiceHostCommand("sharedfs-test",
                "configure-sharedfs-static-network", "{}", 60, Collections.emptySet()));
        String unit = installedContent(shell, "/etc/systemd/system/ablestack-sharedfs-network.service");
        Assert.assertTrue(unit.contains("cloud-preinit.service"));
        Assert.assertTrue(unit.contains("cloud-early-config.service"));
        Assert.assertTrue(unit.contains("Before=networking.service "));
        String reconcile = installedContent(shell, "/etc/systemd/system/ablestack-storage-reconcile.service");
        Assert.assertTrue(reconcile.contains("Requires="));
        Assert.assertTrue(reconcile.contains("ablestack-sharedfs-network.service"));
        Assert.assertFalse(reconcile.contains("cloud-dhclient@eth0.service"));
        Assert.assertFalse(reconcile.contains("network-online.target"));
    }

    @Test
    public void networkInstallerPublishesTheExactCanonicalTemplateBytes() throws Exception {
        String shell = wrapper.buildStorageCtlShell(new StorageServiceHostCommand("sharedfs-test",
                "configure-sharedfs-static-network", "{}", 60, Collections.emptySet()));
        for (String path : new String[] {
                "usr/local/sbin/ablestack-sharedfs-network",
                "etc/systemd/system/ablestack-sharedfs-network.service",
                "etc/systemd/system/ablestack-storage-reconcile.service",
                "etc/systemd/system/cloud-dhclient@.service"}) {
            byte[] canonical = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                    System.getProperty("basedir"), "../../../systemvm/debian", path).normalize());
            try (java.io.InputStream resource = getClass().getResourceAsStream(
                    "/META-INF/ablestack/sharedfs-network/" + path)) {
                Assert.assertNotNull(path, resource);
                Assert.assertArrayEquals(canonical, resource.readAllBytes());
            }
            Assert.assertEquals(new String(canonical, java.nio.charset.StandardCharsets.UTF_8),
                    installedContent(shell, "/" + path));
        }
        Assert.assertFalse(shell.contains("enable cloud-dhclient@"));
    }

    @Test
    public void legacyMonitorPatchOnlyChangesItsKnownStartupLineAndIsIdempotent() throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                wrapper.buildSharedFsLegacyMonitorPatch().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fixture = String.join("\n",
                "import ast,base64",
                "source=base64.b64decode('" + encoded + "').decode('utf-8')",
                "nodes=[n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='rewrite_monitor_startup']",
                "assert len(nodes)==1",
                "exec(compile(ast.Module(body=nodes,type_ignores=[]),'<actual-installer-policy>','exec'))",
                "old=b'systemctl enable --now ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "new=b'systemctl enable ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "before=b'#!/bin/bash\\n# retained\\n  '+old+b'\\nkeep_other_setup\\n'",
                "after=b'#!/bin/bash\\n# retained\\n  '+new+b'\\nkeep_other_setup\\n'",
                "assert rewrite_monitor_startup(before)==after",
                "assert rewrite_monitor_startup(after) is None",
                "for rejected in (b'',old+b'\\n'+old,old+b'\\n'+new,b'systemctl start ablestack-storage-monitor.service'):",
                "    try: rewrite_monitor_startup(rejected)",
                "    except ValueError: continue",
                "    raise AssertionError('unknown or duplicate startup accepted')");
        Process process = new ProcessBuilder("python3", "-c", fixture).redirectErrorStream(true).start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            Assert.fail("Installer policy fixture exceeded its deadline");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertEquals(output, 0, process.exitValue());
    }

    @Test
    public void legacyMonitorPatchAcceptsActualLegacyAndCanonicalWholeScripts() throws Exception {
        String legacy;
        try (java.io.InputStream input = getClass().getResourceAsStream("/sharedfs-network/legacy-sharedfsvm.sh")) {
            Assert.assertNotNull(input);
            legacy = java.util.Base64.getEncoder().encodeToString(input.readAllBytes());
        }
        String canonical = java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(System.getProperty("basedir"),
                        "../../../systemvm/debian/opt/cloud/bin/setup/sharedfsvm.sh").normalize()));
        String encoded = java.util.Base64.getEncoder().encodeToString(
                wrapper.buildSharedFsLegacyMonitorPatch().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fixture = String.join("\n",
                "import ast,base64",
                "source=base64.b64decode('" + encoded + "').decode('utf-8')",
                "nodes=[n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='rewrite_monitor_startup']",
                "exec(compile(ast.Module(body=nodes,type_ignores=[]),'<actual-installer-policy>','exec'))",
                "legacy=base64.b64decode('" + legacy + "')",
                "canonical=base64.b64decode('" + canonical + "')",
                "old=b'systemctl enable --now ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "new=b'systemctl enable ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "unmask=b'systemctl unmask ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "assert legacy.count(old)==1 and legacy.count(unmask)==1",
                "assert canonical.count(new)==1 and canonical.count(unmask)==1",
                "assert rewrite_monitor_startup(legacy)==legacy.replace(old,new)",
                "assert rewrite_monitor_startup(canonical) is None",
                "for rejected in (legacy+unmask+b'\\n',canonical+b'systemctl restart ablestack-storage-monitor.service\\n',",
                "                 legacy.replace(unmask,b'systemctl unmask --now ablestack-storage-monitor.service')):",
                "    try: rewrite_monitor_startup(rejected)",
                "    except ValueError: continue",
                "    raise AssertionError('unknown or duplicate monitor command accepted')");
        Process process = new ProcessBuilder("python3", "-c", fixture).redirectErrorStream(true).start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            Assert.fail("Whole-script installer fixture exceeded its deadline");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertEquals(output, 0, process.exitValue());
    }

    @Test
    public void legacyInitPatchOnlyChangesTheActualUniqueSharedFsRoleBranch() throws Exception {
        String legacy;
        try (java.io.InputStream input = getClass().getResourceAsStream("/sharedfs-network/legacy-init.sh")) {
            Assert.assertNotNull(input);
            legacy = java.util.Base64.getEncoder().encodeToString(input.readAllBytes());
        }
        String canonical = java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(System.getProperty("basedir"),
                        "../../../systemvm/debian/opt/cloud/bin/setup/init.sh").normalize()));
        String encoded = java.util.Base64.getEncoder().encodeToString(
                wrapper.buildSharedFsLegacyMonitorPatch().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fixture = String.join("\n",
                "import ast,base64",
                "source=base64.b64decode('" + encoded + "').decode('utf-8')",
                "nodes=[n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name=='rewrite_sharedfs_init']",
                "assert len(nodes)==1,'Production installer omits its SharedFS init policy'",
                "exec(compile(ast.Module(body=nodes,type_ignores=[]),'<actual-init-policy>','exec'))",
                "legacy=base64.b64decode('" + legacy + "')",
                "canonical=base64.b64decode('" + canonical + "')",
                "assert rewrite_sharedfs_init(legacy)==canonical",
                "assert rewrite_sharedfs_init(canonical) is None",
                "marker=b'  elif [ \"$TYPE\" == \"sharedfsvm\" ]; then\\n'",
                "old=marker+b'    setup_common eth0\\n  fi\\n'",
                "assert legacy.count(old)==1",
                "guarded=legacy.replace(old,b'  elif [ \"$TYPE\" == \"sharedfsvm\" ]; then\\n    # The persisted STATIC declaration, including malformed state, never falls\\n    # back to the legacy cmdline gateway/DNS or DHCP configuration.\\n    if [ ! -e /etc/ablestack-storage/sharedfs-network.json ] && [ ! -L /etc/ablestack-storage/sharedfs-network.json ]; then\\n      setup_common eth0\\n    fi\\n  fi\\n')",
                "assert rewrite_sharedfs_init(guarded)==canonical",
                "bad_body=legacy.replace(old,marker+b'    setup_common eth0 eth1\\n  fi\\n')",
                "weak=canonical.replace(b' && [ ! -L /etc/ablestack-storage/sharedfs-network.json ]',b'')",
                "weak_boot=canonical.replace(b'--bootstrap-only || exit 1',b'--bootstrap-only || true')",
                "extra_setup=canonical.replace(b'--bootstrap-only || exit 1',b'--bootstrap-only || exit 1\\n    setup_common eth0')",
                "for rejected in (b'',bad_body,legacy+old,canonical+old,weak,weak_boot,extra_setup):",
                "    try: rewrite_sharedfs_init(rejected)",
                "    except ValueError: continue",
                "    raise AssertionError('Malformed or ambiguous SharedFS init branch accepted')");
        Process process = new ProcessBuilder("python3", "-c", fixture).redirectErrorStream(true).start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            Assert.fail("Whole-init installer fixture exceeded its deadline");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertEquals(output, 0, process.exitValue());
    }

    @Test
    public void legacyNetworkStartupBecomesTheExactFailClosedCanonicalWholeScript() throws Exception {
        String legacy;
        try (java.io.InputStream input = getClass().getResourceAsStream("/sharedfs-network/legacy-sharedfsvm.sh")) {
            Assert.assertNotNull(input);
            legacy = java.util.Base64.getEncoder().encodeToString(input.readAllBytes());
        }
        String canonical = java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(System.getProperty("basedir"),
                        "../../../systemvm/debian/opt/cloud/bin/setup/sharedfsvm.sh").normalize()));
        String encoded = java.util.Base64.getEncoder().encodeToString(
                wrapper.buildSharedFsLegacyMonitorPatch().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String fixture = String.join("\n",
                "import ast,base64",
                "source=base64.b64decode('" + encoded + "').decode('utf-8')",
                "required={'rewrite_monitor_startup','rewrite_network_startup','rewrite_sharedfsvm_startup'}",
                "nodes=[n for n in ast.parse(source).body if isinstance(n,ast.FunctionDef) and n.name in required]",
                "assert {n.name for n in nodes}==required,'Production installer omits legacy network startup correction'",
                "exec(compile(ast.Module(body=nodes,type_ignores=[]),'<actual-network-startup-policy>','exec'))",
                "legacy=base64.b64decode('" + legacy + "')",
                "canonical=base64.b64decode('" + canonical + "')",
                "old_monitor=b'systemctl enable --now ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "new_monitor=b'systemctl enable ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "assert rewrite_sharedfsvm_startup(legacy)==canonical",
                "assert rewrite_sharedfsvm_startup(legacy.replace(old_monitor,new_monitor))==canonical",
                "assert rewrite_sharedfsvm_startup(canonical) is None",
                "weak=canonical.replace(b'--prepare || exit 1',b'--prepare || true')",
                "changed_old=legacy.replace(b'if [[ -s /etc/ablestack-storage/sharedfs-network.json ]]',b'if [[ -e /etc/ablestack-storage/sharedfs-network.json ]]')",
                "for rejected in (legacy+legacy,weak,changed_old,canonical+b'dhclient eth0\\n',",
                "                 canonical+b'/usr/local/sbin/ablestack-sharedfs-network --prepare || exit 1\\n'):",
                "    try: rewrite_sharedfsvm_startup(rejected)",
                "    except ValueError: continue",
                "    raise AssertionError('Unknown/multiple startup or direct DHCP remainder accepted')");
        Process process = new ProcessBuilder("python3", "-c", fixture).redirectErrorStream(true).start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            Assert.fail("Whole-network-startup fixture exceeded its deadline");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        Assert.assertEquals(output, 0, process.exitValue());
    }

    private String installedContent(String shell, String path) {
        java.util.regex.Matcher encoded = java.util.regex.Pattern.compile(
                "printf '%s' '([A-Za-z0-9+/=]+)' \\| base64 -d > "
                + java.util.regex.Pattern.quote(path + ".tmp")).matcher(shell);
        Assert.assertTrue("Installer must publish " + path, encoded.find());
        return new String(java.util.Base64.getDecoder().decode(encoded.group(1)),
                java.nio.charset.StandardCharsets.UTF_8);
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


    private static final String PROTECTED_STARTUP_PYTHON_FIXTURE = String.join("\n",
            "import ast,contextlib,io,json,os,stat,sys,types",
            "from unittest import mock",
            "request=json.load(sys.stdin)",
            "def definitions(text):",
            " tree=ast.parse(text);nodes=[n for n in tree.body if isinstance(n,(ast.FunctionDef,ast.Import,ast.ImportFrom,ast.Assign))]",
            " ns={'__name__':'portable_actual_transport_fixture'}",
            " exec(compile(ast.Module(body=nodes,type_ignores=[]),'<actual-protected-transport>','exec'),ns)",
            " return ns",
            "mode=request['mode'];receiver=definitions(request['receiver']);observer=definitions(request['observer'])",
            "if mode=='setup-mode':",
            " original=os.dup2;published=[]",
            " def publish(source,target,**kwargs):",
            "  original(source,target,**kwargs)",
            "  if target in (16,18):",
            "   assert stat.S_IMODE(os.fstat(target).st_mode)==0o600",
            "   published.append(target)",
            " try:",
            "  with mock.patch.object(os,'geteuid',return_value=0),mock.patch.object(os,'dup2',side_effect=publish):receiver['setup']()",
            "  assert published==[16,18]",
            " finally:receiver['close_owned']()",
            "else:",
            " nonce='a'*32;args=['/usr/bin/python3','-c','PUBLIC_FIXED_RECEIVER_SCRIPT',nonce]",
            " state={'fds':False,'uid':0,'start':7,'alive':True,'nonce':nonce,'script':'PUBLIC_FIXED_RECEIVER_SCRIPT','fdMode':0o600,'fdName':'/memfd:ablestack-protected-input (deleted)','exe':'/usr/bin/python3.11','binaryDevice':1,'binaryInode':2}",
            " if mode=='foreign-uid':state['uid']=1",
            " if mode=='changed-start':state['start']=8",
            " if mode=='nonce':state['nonce']='b'*32",
            " if mode=='argv':state['script']='FOREIGN_SCRIPT'",
            " if mode=='dead':state['alive']=False",
            " if mode=='fd-mode':state['fds']=True;state['fdMode']=0o777",
            " if mode=='fd-name':state['fds']=True;state['fdName']='/memfd:foreign (deleted)'",
            " if mode in ('permission','io'):state['fds']=True",
            " def fake_stat(path,*unused,**kwargs):",
            "  path=str(path)",
            "  if not state['alive']:raise FileNotFoundError()",
            "  if path=='/proc/200':return types.SimpleNamespace(st_uid=state['uid'])",
            "  if path=='/proc/200/exe':return types.SimpleNamespace(st_uid=0,st_mode=stat.S_IFREG|0o755,st_dev=state['binaryDevice'],st_ino=state['binaryInode'])",
            "  if path in('/proc/200/fd/16','/proc/200/fd/18'):",
            "   partial=(mode=='missing16-foreign18'and path.endswith('/18'))or(mode=='missing18-foreign16'and path.endswith('/16'))",
            "   if not state['fds']and not partial:raise FileNotFoundError()",
            "   if mode=='permission':raise PermissionError()",
            "   if mode=='io':raise OSError(5,'SYNTHETIC_PRIVATE_ERROR')",
            "   return types.SimpleNamespace(st_uid=0,st_mode=(stat.S_IFREG if path.endswith('/16')else stat.S_IFIFO)|(0o777 if partial else state['fdMode']),st_ino=33 if path.endswith('/16')else 34)",
            "  raise AssertionError('Unexpected fixture path')",
            " def fake_link(path):",
            "  if path=='/proc/200/exe':return '/bin/sh'if mode=='exe'else state['exe']",
            "  if path=='/proc/200/fd/16':return state['fdName']",
            "  return 'pipe:[34]'",
            " def fake_open(path,*unused,**kwargs):",
            "  if path=='/proc/200/stat':return io.BytesIO(('200 (python3) '+' '.join(['S']+['0']*18+[str(state['start'])])).encode())",
            "  if path=='/proc/200/cmdline':return io.BytesIO(('\\0'.join(args[:2]+[state['script'],state['nonce']])+'\\0').encode())",
            "  raise AssertionError('Unexpected fixture open')",
            " with mock.patch.object(os,'stat',side_effect=fake_stat),mock.patch.object(os,'readlink',side_effect=fake_link),mock.patch('builtins.open',side_effect=fake_open):",
            "  if mode in('pinned-exe','pinned-device','pinned-binary','device-zero'):",
            "   if mode=='device-zero':state['binaryDevice']=0",
            "   first=observer['observe'](200,args,7);state['fds']=True",
            "   if mode=='pinned-exe':state['exe']='/usr/bin/python3.12'",
            "   if mode=='pinned-device':state['binaryDevice']=2",
            "   if mode=='pinned-binary':state['binaryInode']=3",
            "   try:",
            "    second=observer['observe'](200,args,7,nonce,None,None,first['executablePath'],first['binaryDevice'],first['binaryInode'])",
            "   except ValueError:",
            "    assert mode!='device-zero'",
            "   else:",
            "    assert mode=='device-zero'and second['ready']is True and second['binaryDevice']==0",
            "  elif mode=='missing':",
            "   first=observer['observe'](200,args,7);assert first['ready']is False and first['status']=='NOT_READY'",
            "   state['fds']=True;second=observer['observe'](200,args,7);assert second['ready']is True and second['status']=='READY'",
            "   assert first['startTicks']==second['startTicks']==7 and first['launchNonce']==second['launchNonce']==nonce",
            "  else:",
            "   try:observer['observe'](200,args,7)",
            "   except (ValueError,FileNotFoundError,PermissionError,OSError):pass",
            "   else:raise AssertionError('Invalid owner must remain rejected')",
            "print(json.dumps({'success':True,'mode':mode,'privateValuesExported':False}))");

    private void actualProtectedPython(String mode) throws Exception {
        com.google.gson.JsonObject request = new com.google.gson.JsonObject();request.addProperty("mode", mode);
        for (String name : new String[]{"RECEIVER", "OBSERVER"}) {
            java.lang.reflect.Field field = LibvirtStorageServiceHostCommandWrapper.class.getDeclaredField("PROTECTED_STDIN_" + name);
            field.setAccessible(true);request.addProperty(name.toLowerCase(java.util.Locale.ROOT), (String) field.get(null));
        }
        Process process = new ProcessBuilder("python3", "-c", PROTECTED_STARTUP_PYTHON_FIXTURE).start();
        try (java.io.OutputStream input = process.getOutputStream()) {
            input.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();Assert.fail("Protected startup fixture exceeded its bounded deadline");
        }
        Assert.assertEquals("Actual transport fixture failed: " + mode, 0, process.exitValue());
        Assert.assertEquals(0, process.getErrorStream().readAllBytes().length);
        com.google.gson.JsonObject result = com.google.gson.JsonParser.parseString(new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        Assert.assertTrue(result.get("success").getAsBoolean());Assert.assertFalse(result.get("privateValuesExported").getAsBoolean());
    }

    @Test public void actualReceiverPublishesOnlyAlreadyPrivateFixedDescriptors() throws Exception {
        actualProtectedPython("setup-mode");
    }

    @Test public void actualObserverReturnsNotReadyOnlyForMissingOwnedDescriptors() throws Exception {
        actualProtectedPython("missing");
        for (String mode : new String[]{"foreign-uid", "changed-start", "nonce", "argv", "dead", "fd-mode", "fd-name", "permission", "io", "exe", "missing16-foreign18", "missing18-foreign16", "pinned-exe", "pinned-device", "pinned-binary", "device-zero"})
            actualProtectedPython(mode);
    }

    @Test public void delayedOwnedReceiverReobservesBeforeUploadAndCommit() throws Exception {
        for (String failure : new String[]{"not-ready", "not-ready-zero-device"}) {
            java.util.List<String> events = new java.util.ArrayList<>();java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            org.libvirt.Domain domain = chunkDomain(events, bytes, failure);
            StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source",
                    "X".repeat(40000), 120, Collections.singleton("capsule"));
            Assert.assertEquals(17, wrapper.executeGuestCommand(domain, command));
            Assert.assertEquals(40000, bytes.size());Assert.assertEquals(4, events.stream().filter("guest-exec"::equals).count());
            Assert.assertTrue(events.contains("COMMIT"));
        }
    }


    @Test public void startupOwnershipViolationsAndObserverFailureNeverRetryOrUpload() throws Exception {
        for (String failure : new String[]{"not-ready-nonce", "not-ready-pid", "not-ready-extra", "not-ready-bool", "not-ready-duplicate", "not-ready-uppercase", "observer-exit"}) {
            java.util.List<String> events = new java.util.ArrayList<>();java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            org.libvirt.Domain domain = chunkDomain(events, bytes, failure);
            StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source",
                    "X".repeat(40000), 120, Collections.singleton("capsule"));
            try {wrapper.executeGuestCommand(domain, command);Assert.fail("Invalid readiness must fail");}
            catch (IllegalStateException expected) {Assert.assertFalse(expected.getMessage().contains("PRIVATE"));}
            Assert.assertEquals(0, bytes.size());Assert.assertFalse(events.contains("COMMIT"));
            Assert.assertEquals(2, events.stream().filter("guest-exec"::equals).count());
        }
    }

    @Test public void notReadyPinsStartAndAnyPublishedInodeAndCannotExtendDeadline() throws Exception {
        for (String failure : new String[]{"not-ready-start", "not-ready-inode", "not-ready-disappeared", "not-ready-exe", "not-ready-device", "not-ready-binary"}) {
            java.util.List<String> events = new java.util.ArrayList<>();java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            org.libvirt.Domain domain = chunkDomain(events, bytes, failure);
            StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source",
                    "X".repeat(40000), 120, Collections.singleton("capsule"));
            try {wrapper.executeGuestCommand(domain, command);Assert.fail("Changed startup identity must fail");}
            catch (IllegalStateException expected) { }
            Assert.assertEquals(0, bytes.size());Assert.assertFalse(events.contains("COMMIT"));
            Assert.assertEquals(3, events.stream().filter("guest-exec"::equals).count());
        }
        org.libvirt.Domain expired = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        try {wrapper.observeProtectedInputReceiver(expired, 17, new com.google.gson.JsonArray(), System.currentTimeMillis() - 1);Assert.fail();}
        catch (IllegalStateException expected) { }
        org.mockito.Mockito.verifyNoInteractions(expired);
    }

    @Test public void observerReadyAfterOriginalDeadlineCannotAuthorizeUpload() throws Exception {
        org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        String nonce = "0".repeat(32);
        org.mockito.Mockito.when(domain.qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.eq(0)))
                .thenAnswer(call -> {
                    String action = com.google.gson.JsonParser.parseString(call.getArgument(0)).getAsJsonObject().get("execute").getAsString();
                    if ("guest-exec".equals(action)) return "{\"return\":{\"pid\":20}}";
                    Assert.assertEquals("guest-exec-status", action);Thread.sleep(1100);
                    String owner = "{\"ready\":true,\"status\":\"READY\",\"rootOwned\":true,\"receiverScriptVerified\":true,"
                            + "\"pid\":17,\"startTicks\":99,\"launchNonce\":\"" + nonce + "\",\"dataInode\":33,\"controlInode\":34}";
                    return "{\"return\":{\"exited\":true,\"exitcode\":0,\"out-data\":\""
                            + java.util.Base64.getEncoder().encodeToString(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\"}}";
                });
        com.google.gson.JsonArray expected = new com.google.gson.JsonArray();expected.add(nonce);
        try {wrapper.observeProtectedInputReceiver(domain, 17, expected, System.currentTimeMillis() + 1000);Assert.fail("Expired READY must not authorize upload");}
        catch (IllegalStateException failure) {Assert.assertTrue(failure.getMessage().contains("deadline"));}
        org.mockito.Mockito.verify(domain, org.mockito.Mockito.times(2))
                .qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.eq(0));
    }

    private org.libvirt.Domain chunkDomain(java.util.List<String> events, java.io.ByteArrayOutputStream captured, String failure) throws Exception {
        org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        final int[] observer = {0};final int[] replies = {0};final String[] nonce = {"0".repeat(32)};
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
                        if ("not-ready-zero-device".equals(failure) && observer[0] > 1)
                            Assert.assertEquals("0", arguments.getAsJsonArray("arg").get(9).getAsString());
                        if (observer[0] == 0) {
                            Assert.assertTrue(arguments.getAsJsonArray("arg").get(4).getAsInt() <= 300);
                            if (arguments.getAsJsonArray("arg").size() > 6) nonce[0] = arguments.getAsJsonArray("arg").get(6).getAsString();
                        }
                        com.google.gson.JsonObject pid = new com.google.gson.JsonObject();pid.addProperty("pid", observer[0]++ == 0 ? 17 : 20);response.add("return", pid);
                    } else if ("guest-exec-status".equals(method)) {
                        com.google.gson.JsonObject owner = new com.google.gson.JsonObject();owner.addProperty("ready", true);owner.addProperty("rootOwned", true);
                        owner.addProperty("receiverScriptVerified", true);owner.addProperty("pid", 17);owner.addProperty("startTicks", 99);
                        owner.addProperty("executablePath", "/usr/bin/python3.11");owner.addProperty("binaryDevice", "not-ready-zero-device".equals(failure) ? 0 : 1);owner.addProperty("binaryInode", 2);
                        owner.addProperty("dataInode", "ownership".equals(failure) && observer[0] > 2 ? 44 : 33);owner.addProperty("controlInode", 34);
                        int ordinal = replies[0]++;
                        boolean waiting = failure.startsWith("not-ready") && ordinal == 0;
                        owner.addProperty("ready", !waiting);owner.addProperty("status", waiting ? "NOT_READY" : "READY");owner.addProperty("launchNonce", nonce[0]);
                        if (waiting) {owner.add("dataInode", com.google.gson.JsonNull.INSTANCE);owner.add("controlInode", com.google.gson.JsonNull.INSTANCE);}
                        if ("not-ready-nonce".equals(failure)) owner.addProperty("launchNonce", "f".repeat(32));
                        if ("not-ready-pid".equals(failure)) owner.addProperty("pid", 18);
                        if ("not-ready-extra".equals(failure)) owner.addProperty("PRIVATE_UNKNOWN_FIELD", "PRIVATE_SENTINEL");
                        if ("not-ready-start".equals(failure) && ordinal > 0) owner.addProperty("startTicks", 100);
                        if ("not-ready-inode".equals(failure)) owner.addProperty("dataInode", ordinal == 0 ? 33 : 44);
                        if ("not-ready-disappeared".equals(failure)) {
                            owner.addProperty("ready", false);owner.addProperty("status", "NOT_READY");
                            if (ordinal == 0) owner.addProperty("dataInode", 33);
                            else {owner.add("dataInode", com.google.gson.JsonNull.INSTANCE);owner.addProperty("controlInode", 34);}
                        }
                        if ("not-ready-bool".equals(failure)) owner.addProperty("pid", true);
                        if ("not-ready-exe".equals(failure) && ordinal > 0) owner.addProperty("executablePath", "/usr/bin/python3.12");
                        if ("not-ready-device".equals(failure) && ordinal > 0) owner.addProperty("binaryDevice", 2);
                        if ("not-ready-binary".equals(failure) && ordinal > 0) owner.addProperty("binaryInode", 3);
                        if ("post-upload-exe".equals(failure) && ordinal > 0) owner.addProperty("executablePath", "/usr/bin/python3.12");
                        if ("post-upload-device".equals(failure) && ordinal > 0) owner.addProperty("binaryDevice", 2);
                        if ("post-upload-binary".equals(failure) && ordinal > 0) owner.addProperty("binaryInode", 3);
                        com.google.gson.JsonObject status = new com.google.gson.JsonObject();status.addProperty("exited", true);status.addProperty("exitcode", "observer-exit".equals(failure) ? 1 : 0);
                        String output = owner.toString();
                        if ("not-ready-duplicate".equals(failure)) output = output.replace("{", "{\"pid\":17,");
                        if ("not-ready-uppercase".equals(failure)) output = output.replace("false", "FALSE");
                        status.addProperty("out-data", java.util.Base64.getEncoder().encodeToString(output.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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


    @Test public void largeUnmaskedNfsDesiredStateUsesBoundedRamInputWithoutPayloadArguments() throws Exception {
        com.google.gson.JsonObject desired = new com.google.gson.JsonObject();
        com.google.gson.JsonArray exports = new com.google.gson.JsonArray();
        for (int index = 0; index < 13; index++) {
            com.google.gson.JsonObject export = new com.google.gson.JsonObject();
            export.addProperty("name", "nfs-" + index);
            com.google.gson.JsonObject inspection = new com.google.gson.JsonObject();
            inspection.addProperty("createdDirectoryReceipts", "PUBLIC_RECEIPT".repeat(1300));
            export.add("lastInspection", inspection);exports.add(export);
        }
        desired.add("exports", exports);String payload = desired.toString();
        Assert.assertTrue(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072);
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "nfs export apply", payload, 120, Collections.emptySet());
        java.util.List<String> events = new java.util.ArrayList<>();java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        org.libvirt.Domain domain = chunkDomain(events, captured, "");
        Assert.assertEquals(17, wrapper.executeGuestCommand(domain, command));
        Assert.assertArrayEquals(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), captured.toByteArray());
        Assert.assertTrue(events.indexOf("CLOSE1") < events.indexOf("COMMIT"));
        org.mockito.ArgumentCaptor<String> requests = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(domain, org.mockito.Mockito.atLeastOnce()).qemuAgentCommand(requests.capture(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.eq(0));
        for (String request : requests.getAllValues()) {
            com.google.gson.JsonObject value = com.google.gson.JsonParser.parseString(request).getAsJsonObject();
            if ("guest-exec".equals(value.get("execute").getAsString())) {
                String argv = value.getAsJsonObject("arguments").getAsJsonArray("arg").toString();
                Assert.assertFalse(argv.contains("PUBLIC_RECEIPT"));Assert.assertTrue(argv.length() < 32768);
            }
        }
    }

    @Test public void largeUnmaskedInputSelectsUtf8ByteBudgetAndPreservesSmallCommandShape() throws Exception {
        StorageServiceHostCommand small = new StorageServiceHostCommand("same-vm", "nfs export apply", "X".repeat(32768), 120, Collections.emptySet());
        com.google.gson.JsonObject smallArgs = com.google.gson.JsonParser.parseString(wrapper.buildGuestExecCommand(small)).getAsJsonObject().getAsJsonObject("arguments");
        Assert.assertFalse(smallArgs.has("input-data"));Assert.assertTrue(smallArgs.get("arg").toString().contains("mktemp"));
        String payload = "가".repeat(12000);
        Assert.assertTrue(payload.length() < 32768);Assert.assertTrue(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768);
        StorageServiceHostCommand large = new StorageServiceHostCommand("same-vm", "nfs export apply", payload, 120, Collections.emptySet());
        java.util.List<String> events = new java.util.ArrayList<>();java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        Assert.assertEquals(17, wrapper.executeGuestCommand(chunkDomain(events, captured, ""), large));
        Assert.assertArrayEquals(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), captured.toByteArray());
        Assert.assertEquals("/usr/local/bin/ablestack-storagectl nfs export apply /dev/stdin", wrapper.buildStorageCtlShell(large));
    }

    @Test public void largeUnmaskedNativeInputRejectsExistingEightMiBLimitBeforeAnyQgaCall() throws Exception {
        org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "nfs export apply", "X".repeat(8 * 1024 * 1024 + 1), 120, Collections.emptySet());
        try {wrapper.executeGuestCommand(domain, command);Assert.fail("Existing native stdin limit must be checked before launch");}
        catch (IllegalArgumentException expected) {Assert.assertFalse(expected.getMessage().contains("XXXXXXXX"));}
        org.mockito.Mockito.verifyNoInteractions(domain);
    }

    @Test public void largeUnmaskedUnknownCommitIsNotRetriedOrReplacedByDiskPayload() throws Exception {
        java.util.List<String> events = new java.util.ArrayList<>();
        org.libvirt.Domain domain = chunkDomain(events, new java.io.ByteArrayOutputStream(), "commit-close");
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "nfs export apply", "X".repeat(150000), 120, Collections.emptySet());
        try {wrapper.executeGuestCommand(domain, command);Assert.fail();}
        catch (IllegalStateException expected) {Assert.assertTrue(expected.getMessage().contains("unknown"));}
        Assert.assertEquals(1, events.stream().filter("COMMIT"::equals).count());
        Assert.assertEquals(1, events.stream().filter("CLOSE2"::equals).count());
    }

    @Test public void largeUnmaskedInputRequiresChunkCapabilitiesWithoutArgumentFallback() throws Exception {
        java.util.List<String> events = new java.util.ArrayList<>();
        org.libvirt.Domain domain = chunkDomain(events, new java.io.ByteArrayOutputStream(), "unsupported");
        StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "nfs export apply", "X".repeat(150000), 120, Collections.emptySet());
        try {wrapper.executeGuestCommand(domain, command);Assert.fail();}
        catch (IllegalStateException expected) {Assert.assertTrue(expected.getMessage().contains("unavailable"));}
        Assert.assertEquals(Collections.singletonList("guest-info"), events);
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
        for (String failure : new String[]{"ownership", "post-upload-exe", "post-upload-device", "post-upload-binary"}) {
            java.util.List<String> events = new java.util.ArrayList<>();
            org.libvirt.Domain domain = chunkDomain(events, new java.io.ByteArrayOutputStream(), failure);
            StorageServiceHostCommand command = new StorageServiceHostCommand("same-vm", "identity capsule import-local-source", "X".repeat(40000), 120, Collections.singleton("capsule"));
            try {wrapper.executeGuestCommand(domain, command);Assert.fail();}
            catch (IllegalStateException expected) {Assert.assertTrue(expected.getMessage().contains("identity changed"));}
            Assert.assertFalse(events.contains("COMMIT"));Assert.assertEquals(3, events.stream().filter("guest-exec"::equals).count());
        }
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

    private StorageServiceHostCommand iscsiSensitiveCommand(String operation) {
        return new StorageServiceHostCommand("public-test-vm", operation, "{}", 120, java.util.Set.of("chapSecret", "mutualChapSecret"));
    }


    private String actualNfsPublicFailure() throws Exception {
        java.nio.file.Path source = java.nio.file.Paths.get(System.getProperty("basedir"),
                "../../../systemvm/debian/usr/local/lib/ablestack-storage/nfs_readiness.py").normalize();
        String program = String.join("\n",
                "import ast,json,sys",
                "tree=ast.parse(open(sys.argv[1]).read())",
                "cls=next(n for n in tree.body if isinstance(n,ast.ClassDef)and n.name=='NfsProbeRunner')",
                "method=next(n for n in cls.body if isinstance(n,ast.FunctionDef)and n.name=='failure')",
                "cls.body=[method];scope={}",
                "exec(compile(ast.Module(body=[cls],type_ignores=[]),'<actual-native-public-failure>','exec'),scope)",
                "failure=scope['NfsProbeRunner'].__new__(scope['NfsProbeRunner']).failure('PROBE_TIMEOUT',",
                " {'listenIp':'192.0.2.7','port':2049},{'uuid':'11111111-1111-4111-8111-111111111111','name':'SYNTH_PRIVATE_EXPORT_NAME'},'NFSv4',cleanup=True,active=True)",
                "print(json.dumps({'success':False,'runtimeReady':True,'completedProbeCount':2,'totalProbeCount':15,'probePending':1,'skippedAclCount':0,'lastFailure':failure,'privateBody':'SYNTH_PRIVATE_BODY'},sort_keys=True,separators=(',',':')))");
        Process process = new ProcessBuilder("python3", "-c", program, source.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();Assert.fail("Pure native failure producer exceeded local deadline");
        }
        String value = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        Assert.assertEquals(value, 0, process.exitValue());return value;
    }

    private StorageServiceHostCommand nfsDiagnosticCommand() {
        return new StorageServiceHostCommand("same-vm", "nfs export apply", "{}", 30, Collections.emptySet());
    }

    @Test public void actualNativeNfsFailureProducesOnlyClosedContextAndCleanupDiagnostic() throws Exception {
        String detail = wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, actualNfsPublicFailure(), "");
        Assert.assertTrue(detail.contains("NFS phase=PROBE_EXPORTS; code=PROBE_TIMEOUT"));
        Assert.assertTrue(detail.contains("timedOut=true"));Assert.assertTrue(detail.contains("retryable=true"));
        Assert.assertTrue(detail.contains("cleanupComplete=false"));Assert.assertTrue(detail.contains("childActive=true"));
        Assert.assertTrue(detail.contains("mountPresent=true"));Assert.assertTrue(detail.contains("endpointIp=192.0.2.7"));
        Assert.assertTrue(detail.contains("endpointPort=2049"));Assert.assertTrue(detail.contains("exportUuid=11111111-1111-4111-8111-111111111111"));
        Assert.assertTrue(detail.contains("protocolMode=NFS4"));Assert.assertTrue(detail.contains("completedProbes=2"));
        Assert.assertTrue(detail.contains("totalProbes=15"));Assert.assertTrue(detail.contains("pendingProbes=1"));
        Assert.assertFalse(detail.contains("SYNTH_PRIVATE"));Assert.assertFalse(detail.contains("lastFailure"));
    }

    @Test public void actualNativeEndpointListenerAndExportBindingFailuresRemainClosedAndVisible() throws Exception {
        java.nio.file.Path source = java.nio.file.Paths.get(System.getProperty("basedir"),
                "../../../systemvm/debian/usr/local/lib/ablestack-storage/nfs_readiness.py").normalize();
        for (String code : new String[]{"ENDPOINT_LISTENER_NOT_READY", "EXPORT_BINDING_MISMATCH"}) {
            String program = String.join("\n", "import ast,json,sys", "tree=ast.parse(open(sys.argv[1]).read())",
                    "cls=next(n for n in tree.body if isinstance(n,ast.ClassDef)and n.name=='NfsReadinessError');scope={}",
                    "exec(compile(ast.Module(body=[cls],type_ignores=[]),'<actual-native-readiness-failure>','exec'),scope)",
                    "failure=scope['NfsReadinessError'](sys.argv[2],{'listenIp':'0.0.0.0','port':2049}).public",
                    "print(json.dumps({'success':False,'lastFailure':failure,'privateBody':'SYNTH_PRIVATE_BODY'}))");
            Process process = new ProcessBuilder("python3", "-c", program, source.toString(), code).redirectErrorStream(true).start();
            Assert.assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            String value = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Assert.assertEquals(value, 0, process.exitValue());
            String detail = wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, value, "");
            Assert.assertTrue(detail, detail.contains("code=" + code));
            Assert.assertTrue(detail.contains("phase=WAIT_RUNTIME_READY"));
            Assert.assertFalse(detail.contains("SYNTH_PRIVATE"));Assert.assertFalse(detail.contains("exportName"));
        }
    }

    @Test public void nullableNativeNfsFailureContextKeepsUnknownContextUninvented() throws Exception {
        com.google.gson.JsonObject value = com.google.gson.JsonParser.parseString(actualNfsPublicFailure()).getAsJsonObject();
        com.google.gson.JsonObject failure = value.getAsJsonObject("lastFailure");
        for (String key : new String[]{"endpointIp", "endpointPort", "exportUuid", "exportName", "protocolMode"}) failure.add(key, com.google.gson.JsonNull.INSTANCE);
        failure.addProperty("code", "NFS_RUNTIME_NOT_READY");failure.addProperty("phase", "WAIT_RUNTIME_READY");
        String detail = wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, value.toString(), "");
        Assert.assertTrue(detail.contains("code=NFS_RUNTIME_NOT_READY"));Assert.assertFalse(detail.contains("endpointIp="));
        Assert.assertFalse(detail.contains("endpointPort="));Assert.assertFalse(detail.contains("exportUuid="));
        Assert.assertFalse(detail.contains("protocolMode="));Assert.assertFalse(detail.contains("SYNTH_PRIVATE"));
    }

    @Test public void malformedNfsPublicFailureValuesNeverLeakOrClaimValidatedContext() throws Exception {
        String original = actualNfsPublicFailure();
        for (String[] edit : new String[][]{{"code","SYNTH_PRIVATE_CODE"},{"protocol","SMB"},{"phase","SYNTH_PRIVATE_PHASE"},
                {"endpointIp","192.0.2.7;SYNTH_PRIVATE_IP"},{"endpointIp","256.1.2.3"},{"exportUuid","SYNTH_PRIVATE_UUID"},{"protocolMode","SYNTH_PRIVATE_MODE"}}) {
            com.google.gson.JsonObject value = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
            value.getAsJsonObject("lastFailure").addProperty(edit[0], edit[1]);
            String detail = wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, value.toString(), "");
            Assert.assertTrue(detail.endsWith("No guest diagnostic"));Assert.assertFalse(detail.contains("SYNTH_PRIVATE"));
        }
        for (String[] edit : new String[][]{{"endpointPort","\"2049\""},{"endpointPort","65536"},{"endpointPort","-1"},
                {"endpointPort","2049.5"},{"timedOut","\"true\""},{"retryable","1"}}) {
            com.google.gson.JsonObject value = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
            value.getAsJsonObject("lastFailure").add(edit[0], com.google.gson.JsonParser.parseString(edit[1]));
            Assert.assertTrue(wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, value.toString(), "").endsWith("No guest diagnostic"));
        }
    }

    @Test public void unknownDuplicateOrNonObjectNfsFailureIsClosedWithoutBodyFallback() throws Exception {
        String original = actualNfsPublicFailure();
        com.google.gson.JsonObject extra = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
        extra.getAsJsonObject("lastFailure").addProperty("privateBody", "SYNTH_PRIVATE_BODY");
        com.google.gson.JsonObject malformed = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
        malformed.addProperty("lastFailure", "SYNTH_PRIVATE_BODY");
        for (String value : new String[]{extra.toString(), malformed.toString(),
                original.replace("\"code\":\"PROBE_TIMEOUT\"", "\"code\":\"PROBE_TIMEOUT\",\"code\":\"PROBE_TIMEOUT\""),
                original.replace("\"complete\":false", "\"complete\":false,\"complete\":false")}) {
            String detail = wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, value, "");
            Assert.assertTrue(detail.endsWith("No guest diagnostic"));Assert.assertFalse(detail.contains("SYNTH_PRIVATE"));
        }
    }

    @Test public void sensitiveNfsPayloadRetainsOriginalMaskEvenWithValidPublicFailure() throws Exception {
        StorageServiceHostCommand sensitive = new StorageServiceHostCommand("same-vm", "nfs export apply", "SYNTH_PRIVATE_REQUEST", 30, Collections.singleton("secret"));
        String detail = wrapper.commandFailureDetails(sensitive, 1, actualNfsPublicFailure(), "SYNTH_PRIVATE_STDERR");
        Assert.assertEquals("Sensitive Storage Service command failed with exit code 1; secret-bearing output omitted", detail);
    }

    @Test public void legacyGuestMessageErrorCodeStderrAndNonNfsSemanticsRemainUnchanged() throws Exception {
        com.google.gson.JsonObject value = com.google.gson.JsonParser.parseString(actualNfsPublicFailure()).getAsJsonObject();
        value.addProperty("message", "legacy-message");
        Assert.assertEquals("Storage Service command failed with exit code 1: legacy-message", wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, value.toString(), ""));
        value.addProperty("errorCode", "LEGACY_CODE");
        Assert.assertEquals("Storage Service command failed with exit code 1: LEGACY_CODE: legacy-message", wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, value.toString(), ""));
        Assert.assertEquals("Storage Service command failed with exit code 1: legacy-stderr", wrapper.commandFailureDetails(nfsDiagnosticCommand(), 1, value.toString(), "legacy-stderr"));
        StorageServiceHostCommand smb = new StorageServiceHostCommand("same-vm", "smb share apply", "{}", 30, Collections.emptySet());
        Assert.assertTrue(wrapper.commandFailureDetails(smb, 1, actualNfsPublicFailure(), "").endsWith("No guest diagnostic"));
    }

    @Test public void actualGuestExitRetainsStructuredResultJsonAndSurfacesOnlyClosedNfsSummary() throws Exception {
        String stdout = actualNfsPublicFailure();
        org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        com.google.gson.JsonObject returned = new com.google.gson.JsonObject();returned.addProperty("exited", true);returned.addProperty("exitcode", 1);
        returned.addProperty("out-data", java.util.Base64.getEncoder().encodeToString(stdout.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        com.google.gson.JsonObject response = new com.google.gson.JsonObject();response.add("return", returned);
        org.mockito.Mockito.when(domain.qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.eq(0))).thenReturn(response.toString());
        com.cloud.agent.api.StorageServiceHostAnswer answer = (com.cloud.agent.api.StorageServiceHostAnswer) wrapper.waitForGuestCommand(nfsDiagnosticCommand(), domain, 51);
        Assert.assertFalse(answer.getResult());Assert.assertEquals(stdout, answer.getResultJson());
        Assert.assertTrue(answer.getDetails().contains("code=PROBE_TIMEOUT"));Assert.assertFalse(answer.getDetails().contains("SYNTH_PRIVATE"));
    }

    private String fixedIscsiFailure() {
        return "{\"success\":false,\"kind\":\"ISCSI_TARGETCLI_COMMAND_FAILED\",\"stage\":\"TARGET_CREATE\",\"returnCode\":1,\"category\":\"TYPE_ERROR\"}";
    }

    @Test
    public void testClosedIscsiFailureAddsOnlyFixedPublicDetails() {
        final String secret = "SYNTHETIC_PRIVATE_CREDENTIAL";
        final StorageServiceHostCommand command = iscsiSensitiveCommand("iscsi target apply");
        final String masked = "Sensitive Storage Service command failed with exit code 1; secret-bearing output omitted";
        Assert.assertEquals(masked + " [ISCSI stage=TARGET_CREATE; returnCode=1; category=TYPE_ERROR]",
                wrapper.commandFailureDetails(command, 1, fixedIscsiFailure(), secret));
        Assert.assertEquals(masked, wrapper.commandFailureDetails(iscsiSensitiveCommand("smb share apply"), 1, fixedIscsiFailure(), secret));
        Assert.assertFalse(wrapper.commandFailureDetails(command, 1, fixedIscsiFailure(), secret).contains(secret));
        Assert.assertEquals(masked + " [ISCSI stage=TARGET_CREATE; returnCode=UNAVAILABLE; category=TIMEOUT]",
                wrapper.commandFailureDetails(command, 1, fixedIscsiFailure().replace("\"returnCode\":1", "\"returnCode\":null").replace("\"TYPE_ERROR\"", "\"TIMEOUT\""), secret));
    }

    @Test
    public void testInvalidIscsiDiagnosticKeepsOriginalMaskedFailure() {
        final StorageServiceHostCommand command = iscsiSensitiveCommand("iscsi target apply");
        final String fixed = fixedIscsiFailure();
        final String masked = "Sensitive Storage Service command failed with exit code 1; secret-bearing output omitted";
        final java.util.List<String> rejected = new java.util.ArrayList<>();
        rejected.add(fixed.replace("\"success\":false", "\"success\":true"));
        rejected.add(fixed.replace("\"success\":false", "\"success\":\"false\""));
        rejected.add(fixed.replace("\"TARGET_CREATE\"", "\"PRIVATE_STAGE\""));
        rejected.add(fixed.replace("\"TYPE_ERROR\"", "\"PRIVATE_CATEGORY\""));
        rejected.add(fixed.replace("\"TYPE_ERROR\"", "\"TIMEOUT\""));
        rejected.add(fixed.replace("\"TYPE_ERROR\"", "\"SPAWN_FAILURE\""));
        rejected.add(fixed.replace("\"returnCode\":1", "\"returnCode\":null"));
        for (String code : new String[]{"0", "-65", "256", "1.0", "1e0", "\"1\"", "true"}) {
            rejected.add(fixed.replace("\"returnCode\":1", "\"returnCode\":" + code));
        }
        rejected.add(fixed.replace("\"success\":false", "\"success\":false,\"success\":false"));
        rejected.add(fixed.replace("\"success\":false", "\"success\":false,\"\\u0073uccess\":false"));
        rejected.add(fixed.substring(0, fixed.length() - 1) + ",\"message\":\"SYNTHETIC_PRIVATE_CREDENTIAL\"}");
        rejected.add(fixed + "\n" + fixed);
        rejected.add(fixed + fixed);
        rejected.add(fixed + " trailing");
        rejected.add(fixed.replace(",\"stage\"", ",\n\"stage\""));
        rejected.add("{\"success\":false}");
        rejected.add("SYNTHETIC_PRIVATE_CREDENTIAL");
        for (String value : rejected) {
            Assert.assertEquals(masked, wrapper.commandFailureDetails(command, 1, value, "SYNTHETIC_PRIVATE_CREDENTIAL"));
        }
    }

    @Test
    public void testIscsiNonzeroGuestAnswerKeepsFailureAndCarriesPublicResult() throws Exception {
        final StorageServiceHostCommand command = iscsiSensitiveCommand("iscsi target apply");
        final org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
        final com.google.gson.JsonObject status = new com.google.gson.JsonObject();
        status.addProperty("exited", true);
        status.addProperty("exitcode", 1);
        status.addProperty("out-data", java.util.Base64.getEncoder().encodeToString(fixedIscsiFailure().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        status.addProperty("err-data", java.util.Base64.getEncoder().encodeToString("SYNTHETIC_PRIVATE_CREDENTIAL".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        final com.google.gson.JsonObject response = new com.google.gson.JsonObject();
        response.add("return", status);
        org.mockito.Mockito.when(domain.qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.eq(0)))
                .thenReturn(response.toString());
        final com.cloud.agent.api.StorageServiceHostAnswer answer =
                (com.cloud.agent.api.StorageServiceHostAnswer) wrapper.waitForGuestCommand(command, domain, 17);
        Assert.assertFalse(answer.getResult());
        Assert.assertEquals(fixedIscsiFailure(), answer.getResultJson());
        Assert.assertTrue(answer.getDetails().contains("ISCSI stage=TARGET_CREATE; returnCode=1; category=TYPE_ERROR"));
        Assert.assertFalse(answer.getDetails().contains("SYNTHETIC_PRIVATE_CREDENTIAL"));
    }


    private String fixedIscsiGuardFailure() {
        return "{\"success\":false,\"kind\":\"ISCSI_APPLY_GUARD_FAILED\",\"stage\":\"DEVICE\",\"returnCode\":null,\"category\":\"SYSTEM_EXIT\"}";
    }

    @Test
    public void testClosedGuardPhasesAndCategoriesKeepExactNullCodeContract() {
        final StorageServiceHostCommand command = iscsiSensitiveCommand("iscsi target apply");
        for (String phase : new String[]{"INPUT", "VAULT_STATE", "DEPENDENCY", "LISTENER", "DEVICE", "AUTH", "READINESS"}) {
            for (String category : new String[]{"TARGET_IQN_INVALID", "INITIATOR_IQN_INVALID", "OWNERSHIP_INVALID", "OWNERSHIP_MISMATCH", "AUTH_VALUE_INVALID", "VALUE_ERROR", "TYPE_ERROR", "RUNTIME_ERROR", "OS_ERROR", "TIMEOUT", "SYSTEM_EXIT", "LOOKUP_ERROR", "ATTRIBUTE_ERROR", "ASSERTION_ERROR"}) {
                String value = fixedIscsiGuardFailure().replace("\"DEVICE\"", "\"" + phase + "\"")
                        .replace("\"SYSTEM_EXIT\"", "\"" + category + "\"");
                Assert.assertTrue(wrapper.commandFailureDetails(command, 1, value, "SYNTHETIC_PRIVATE_CREDENTIAL")
                        .contains("ISCSI guard=" + phase + "; returnCode=UNAVAILABLE; category=" + category));
            }
        }
        String reordered = "{\"stage\":\"DEVICE\",\"returnCode\":null,\"category\":\"SYSTEM_EXIT\",\"success\":false,\"kind\":\"ISCSI_APPLY_GUARD_FAILED\"}";
        Assert.assertTrue(wrapper.commandFailureDetails(command, 1, reordered, null).contains("ISCSI guard=DEVICE"));
    }

    @Test
    public void testMalformedGuardShapeNeverExposesSyntheticPrivateOutput() {
        final StorageServiceHostCommand command = iscsiSensitiveCommand("iscsi target apply");
        final String fixed = fixedIscsiGuardFailure();
        final String masked = "Sensitive Storage Service command failed with exit code 1; secret-bearing output omitted";
        final java.util.List<String> rejected = new java.util.ArrayList<>();
        rejected.add(fixed.replace("\"success\":false", "\"success\":true"));
        rejected.add(fixed.replace("\"success\":false", "\"success\":\"false\""));
        rejected.add(fixed.replace("\"success\":false", "\"success\":{\"value\":false}"));
        rejected.add(fixed.replace("\"success\":false", "\"success\":false,\"success\":false"));
        rejected.add(fixed.replace("\"success\":false", "\"success\":false,\"\\u0073uccess\":false"));
        rejected.add(fixed.replace("\"DEVICE\"", "\"TARGET_CREATE\""));
        rejected.add(fixed.replace("\"SYSTEM_EXIT\"", "\"SPAWN_FAILURE\""));
        rejected.add(fixed.replace("\"ISCSI_APPLY_GUARD_FAILED\"", "\"OTHER_GUARD\""));
        for (String code : new String[]{"0", "1", "-1", "1.0", "\"null\"", "false"}) {
            rejected.add(fixed.replace("\"returnCode\":null", "\"returnCode\":" + code));
        }
        rejected.add(fixed.replace(",\"category\":\"SYSTEM_EXIT\"", ""));
        rejected.add(fixed.substring(0, fixed.length() - 1) + ",\"message\":\"SYNTHETIC_PRIVATE_CREDENTIAL\"}");
        rejected.add("[" + fixed + "]");
        rejected.add(fixed + "\n" + fixed);
        rejected.add(fixed + fixed);
        rejected.add(fixed + " trailing");
        rejected.add(fixed.replace(",\"stage\"", ",\n\"stage\""));
        rejected.add("{\"success\":false,\"errorCode\":\"LOCAL_SOURCE_CHECKPOINT_REJECTED\",\"reason\":\"ValueError\"}");
        for (String value : rejected) {
            Assert.assertEquals(masked, wrapper.commandFailureDetails(command, 1, value, "SYNTHETIC_PRIVATE_CREDENTIAL"));
        }
        Assert.assertEquals(masked, wrapper.commandFailureDetails(iscsiSensitiveCommand("smb share apply"), 1, fixed, null));
    }

    private java.nio.file.Path iscsiGuardProducerFixture() {
        String explicit = System.getProperty("cloudstack.storage.iscsi.guard.fixture");
        if (explicit != null) return java.nio.file.Paths.get(explicit);
        java.nio.file.Path directory = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            java.nio.file.Path candidate = directory.resolve("systemvm/test/run-preparation-health-wire-isolated.py");
            if (java.nio.file.Files.isRegularFile(candidate)) return candidate;
            directory = directory.getParent();
        }
        throw new AssertionError("Actual native guard fixture is unavailable");
    }

    @Test
    public void testActualPythonProducerFlowsThroughCompiledGuestAnswerConsumer() throws Exception {
        final Process process = new ProcessBuilder("python3", iscsiGuardProducerFixture().toString(), "--suite", "iscsi-guard-consumer").start();
        Assert.assertTrue("Actual producer fixture exceeded its bounded deadline", process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        final byte[] bytes = process.getInputStream().readAllBytes();
        final byte[] errors = process.getErrorStream().readAllBytes();
        Assert.assertEquals("Actual producer fixture failed", 0, process.exitValue());
        Assert.assertEquals("Actual producer fixture emitted unexpected stderr", 0, errors.length);
        final com.google.gson.JsonArray fixtures = new com.google.gson.JsonParser()
                .parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
        Assert.assertEquals(16, fixtures.size());
        final java.util.Set<String> observed = new java.util.HashSet<>();
        for (com.google.gson.JsonElement element : fixtures) {
            final com.google.gson.JsonObject fixture = element.getAsJsonObject();
            final String nativeOutput = fixture.get("stdout").getAsString();
            final String scenario = fixture.get("scenario").getAsString();
            observed.add(scenario);
            final org.libvirt.Domain domain = org.mockito.Mockito.mock(org.libvirt.Domain.class);
            final com.google.gson.JsonObject status = new com.google.gson.JsonObject();
            status.addProperty("exited", true);status.addProperty("exitcode", fixture.get("exitCode").getAsInt());
            status.addProperty("out-data", java.util.Base64.getEncoder().encodeToString(nativeOutput.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            status.addProperty("err-data", java.util.Base64.getEncoder().encodeToString("SYNTHETIC_PRIVATE_CREDENTIAL".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            final com.google.gson.JsonObject response = new com.google.gson.JsonObject();response.add("return", status);
            org.mockito.Mockito.when(domain.qemuAgentCommand(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt(), org.mockito.Mockito.eq(0)))
                    .thenReturn(response.toString());
            final com.cloud.agent.api.StorageServiceHostAnswer answer = (com.cloud.agent.api.StorageServiceHostAnswer)
                    wrapper.waitForGuestCommand(iscsiSensitiveCommand("iscsi target apply"), domain, 17);
            Assert.assertFalse(answer.getResult());
            Assert.assertEquals(nativeOutput, answer.getResultJson());
            Assert.assertFalse(answer.getDetails().contains("SYNTHETIC_PRIVATE"));
            if (scenario.startsWith("unknown")) {
                Assert.assertEquals("", nativeOutput);
                Assert.assertEquals("Sensitive Storage Service command failed with exit code 1; secret-bearing output omitted", answer.getDetails());
            } else {
                final com.google.gson.JsonObject nativeRecord = new com.google.gson.JsonParser().parse(nativeOutput).getAsJsonObject();
                Assert.assertFalse(nativeRecord.get("success").getAsBoolean());
                Assert.assertTrue(answer.getDetails().contains(nativeRecord.get("stage").getAsString()));
                Assert.assertTrue(answer.getDetails().contains(nativeRecord.get("category").getAsString()));
                Assert.assertTrue(answer.getDetails().contains("targetcli-first".equals(scenario) ? "ISCSI stage=LUN_CREATE" : "ISCSI guard="));
            }
        }
        Assert.assertEquals(java.util.Set.of("target-iqn-invalid", "initiator-iqn-invalid", "ownership-invalid", "ownership-mismatch", "auth-value-invalid", "early-input", "early-vault", "mid-device-cleanup", "readiness", "auth", "lookup", "attribute", "assertion", "targetcli-first", "unknown", "unknown-mid"), observed);
    }

}
