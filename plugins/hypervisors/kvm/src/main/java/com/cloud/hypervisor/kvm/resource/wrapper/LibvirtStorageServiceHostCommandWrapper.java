//
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
// specific language govening permissions and limitations
// under the License.
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StorageServiceHostAnswer;
import com.cloud.agent.api.StorageServiceHostCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

@ResourceWrapper(handles = StorageServiceHostCommand.class)
public final class LibvirtStorageServiceHostCommandWrapper extends CommandWrapper<StorageServiceHostCommand, Answer, LibvirtComputingResource> {
    private static final int QGA_POLL_INTERVAL_MILLIS = 1000;
    private static final String CONFIGURE_SHAREDFS_STATIC_NETWORK = "configure-sharedfs-static-network";
    private static final String SHAREDFS_NETWORK_STATE = "/etc/ablestack-storage/sharedfs-network.json";
    private static final String SHAREDFS_NETWORK_HELPER = "/usr/local/sbin/ablestack-sharedfs-network";
    private static final String SHAREDFS_NETWORK_UNIT = "/etc/systemd/system/ablestack-sharedfs-network.service";
    private static final String SHAREDFS_NETWORK_HELPER_CONTENT = networkResource("usr/local/sbin/ablestack-sharedfs-network");
    private static final String SHAREDFS_NETWORK_BOOTSTRAP_CONTENT = networkResource("usr/local/lib/ablestack-storage/sharedfs_bootstrap_network.py");
    private static final String SHAREDFS_NETWORK_DHCP_HOOK_CONTENT = networkResource("etc/dhcp/dhclient-enter-hooks.d/ablestack-sharedfs");

    private static String networkResource(String path) {
        try (InputStream stream = LibvirtStorageServiceHostCommandWrapper.class.getResourceAsStream("/sharedfs-network/" + path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing packaged SharedFS network resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read packaged SharedFS network resource: " + path, error);
        }
    }

    private static final String SHAREDFS_NETWORK_SETUP_CONTENT = networkResource("opt/cloud/bin/setup/sharedfsvm.sh");
    private static final String SHAREDFS_NETWORK_RECONCILE_UNIT_CONTENT = networkResource("etc/systemd/system/ablestack-storage-reconcile.service");
    private static final String SHAREDFS_NETWORK_UNIT_CONTENT = networkResource("etc/systemd/system/ablestack-sharedfs-network.service");

    private static final String SHAREDFS_BOOTSTRAP_GUARD_CONTENT = String.join("\n",
            "import json",
            "import subprocess",
            "import sys",
            "",
            "UNITS = ('cloud-early-config.service', 'cloud-init-local.service', 'cloud-init.service')",
            "PROPERTIES = ('Id', 'LoadState', 'ActiveState', 'SubState', 'Result', 'Job')",
            "observations = {}",
            "def finish(state, reason):",
            "    snapshot = {'state': state, 'reason': reason, 'units': observations}",
            "    if state == 'READY':",
            "        return",
            "    print(json.dumps({'sharedFsBootstrap': snapshot}, separators=(',', ':')))",
            "    print('SHAREDFS_BOOTSTRAP_' + state + ': ' + json.dumps(snapshot, separators=(',', ':')), file=sys.stderr)",
            "    sys.exit(75 if state == 'PENDING' else 76)",
            "def inspect(unit):",
            "    args = ['systemctl', 'show', unit]",
            "    for prop in PROPERTIES:",
            "        args.extend(['-p', prop])",
            "    result = subprocess.run(args, text=True, capture_output=True, timeout=5)",
            "    values = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)",
            "    required = PROPERTIES",
            "    if any(key not in values for key in required):",
            "        raise ValueError('Missing bootstrap unit properties: ' + unit)",
            "    if result.returncode and values['LoadState'] != 'not-found':",
            "        raise ValueError('Unable to inspect bootstrap unit: ' + unit)",
            "    if any(len(values.get(key, '')) > 128 for key in PROPERTIES):",
            "        raise ValueError('Invalid bootstrap unit property length: ' + unit)",
            "    observations[unit] = {key: values.get(key, '') for key in PROPERTIES}",
            "    return observations[unit]",
            "try:",
            "    for unit in UNITS:",
            "        inspect(unit)",
            "except subprocess.TimeoutExpired:",
            "    finish('PENDING', 'UNIT_INSPECTION_TIMEOUT')",
            "except (OSError, ValueError):",
            "    finish('FAILED', 'UNIT_INSPECTION_FAILED')",
            "early = observations[UNITS[0]]",
            "if early['LoadState'] != 'loaded' or early['ActiveState'] == 'failed':",
            "    finish('FAILED', 'EARLY_CONFIG_FAILED')",
            "if early['Job'] not in ('', '0') or (early['ActiveState'], early['SubState'], early['Result']) != ('active', 'exited', 'success'):",
            "    finish('PENDING', 'EARLY_CONFIG_RUNNING')",
            "for unit in UNITS[1:]:",
            "    stage = observations[unit]",
            "    if stage['LoadState'] == 'not-found':",
            "        continue",
            "    if stage['LoadState'] != 'loaded':",
            "        finish('FAILED', 'NETWORK_STAGE_UNAVAILABLE')",
            "    if stage['Job'] not in ('', '0') or stage['ActiveState'] in ('activating', 'deactivating') or stage['SubState'] in ('running', 'start', 'start-pre', 'start-post'):",
            "        finish('PENDING', 'NETWORK_STAGE_RUNNING')",
            "finish('READY', 'NETWORK_INITIALIZATION_FINISHED')",
            "");

    @Override
    public Answer execute(final StorageServiceHostCommand command, final LibvirtComputingResource libvirtComputingResource) {
        Domain domain = null;
        try {
            validateOperation(command.getOperation());
            final LibvirtUtilitiesHelper libvirtUtilitiesHelper = libvirtComputingResource.getLibvirtUtilitiesHelper();
            final Connect connect = libvirtUtilitiesHelper.getConnection();
            domain = libvirtComputingResource.getDomain(connect, command.getVmName());
            if (domain == null) {
                return new StorageServiceHostAnswer(command, false, "Storage Service System VM was not found: " + command.getVmName(), null);
            }
            if (domain.getInfo().state != DomainState.VIR_DOMAIN_RUNNING) {
                return new StorageServiceHostAnswer(command, false, "Storage Service System VM is not running: " + command.getVmName(), null);
            }

            final long pid = executeGuestCommand(domain, command);
            return waitForGuestCommand(command, domain, pid);
        } catch (final RuntimeException e) {
            return new StorageServiceHostAnswer(command, false, e.getMessage(), null);
        } catch (final LibvirtException e) {
            return new StorageServiceHostAnswer(command, false, "Failed to execute Storage Service QGA command: " + e.getMessage(), null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StorageServiceHostAnswer(command, false, "Interrupted while waiting for Storage Service QGA command", null);
        } finally {
            if (domain != null) {
                try {
                    domain.free();
                } catch (final LibvirtException e) {
                    logger.trace("Ignoring libvirt domain free error", e);
                }
            }
        }
    }

    protected long executeGuestCommand(final Domain domain, final StorageServiceHostCommand command) throws LibvirtException {
        final String qgaCommand = buildGuestExecCommand(command);
        final String result = domain.qemuAgentCommand(qgaCommand, command.getTimeoutSeconds(), 0);
        final JsonObject response = new JsonParser().parse(result).getAsJsonObject();
        if (!response.has("return") || !response.getAsJsonObject("return").has("pid")) {
            throw new IllegalStateException("QGA guest-exec did not return a pid: " + result);
        }
        return response.getAsJsonObject("return").get("pid").getAsLong();
    }

    protected Answer waitForGuestCommand(final StorageServiceHostCommand command, final Domain domain, final long pid)
            throws LibvirtException, InterruptedException {
        final long deadline = System.currentTimeMillis() + command.getTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            final JsonObject arguments = new JsonObject();
            arguments.addProperty("pid", pid);
            final JsonObject statusCommand = new JsonObject();
            statusCommand.addProperty("execute", "guest-exec-status");
            statusCommand.add("arguments", arguments);
            final String result = domain.qemuAgentCommand(statusCommand.toString(), Math.max(command.getTimeoutSeconds(), 1), 0);
            final JsonObject response = new JsonParser().parse(result).getAsJsonObject().getAsJsonObject("return");
            if (response != null && response.has("exited") && response.get("exited").getAsBoolean()) {
                final int exitCode = response.has("exitcode") ? response.get("exitcode").getAsInt() : 1;
                final String stdout = decodeGuestData(response, "out-data");
                final String stderr = decodeGuestData(response, "err-data");
                final String details = exitCode == 0 ? "Storage Service command completed" :
                        String.format("Storage Service command failed with exit code %s: %s", exitCode, stderr);
                return new StorageServiceHostAnswer(command, exitCode == 0, details, stdout);
            }
            Thread.sleep(QGA_POLL_INTERVAL_MILLIS);
        }
        return new StorageServiceHostAnswer(command, false, "Timed out waiting for Storage Service QGA command", null);
    }

    protected String buildGuestExecCommand(final StorageServiceHostCommand command) {
        final JsonObject qgaCommand = new JsonObject();
        qgaCommand.addProperty("execute", "guest-exec");
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("path", "/bin/bash");
        final JsonArray args = new JsonArray();
        args.add(new JsonPrimitive("-lc"));
        args.add(new JsonPrimitive(buildStorageCtlShell(command)));
        arguments.add("arg", args);
        arguments.addProperty("capture-output", true);
        qgaCommand.add("arguments", arguments);
        return qgaCommand.toString();
    }

    protected String buildStorageCtlShell(final StorageServiceHostCommand command) {
        if (CONFIGURE_SHAREDFS_STATIC_NETWORK.equals(command.getOperation())) {
            return buildSharedFsStaticNetworkShell(command);
        }
        final String payload = command.getPayload() == null ? "" : command.getPayload();
        final String encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return "payload=$(mktemp /tmp/ablestack-storage-XXXXXX.json); " +
                "printf '%s' '" + encodedPayload + "' | base64 -d > \"$payload\"; " +
                "/usr/local/bin/ablestack-storagectl " + command.getOperation() + " \"$payload\"; " +
                "rc=$?; rm -f \"$payload\"; exit $rc";
    }

    protected String buildSharedFsStaticNetworkShell(final StorageServiceHostCommand command) {
        final String payload = command.getPayload() == null ? "" : command.getPayload();
        final String encodedPayload = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        final String encodedHelper = Base64.getEncoder().encodeToString(SHAREDFS_NETWORK_HELPER_CONTENT.getBytes(StandardCharsets.UTF_8));
        final String encodedUnit = Base64.getEncoder().encodeToString(SHAREDFS_NETWORK_UNIT_CONTENT.getBytes(StandardCharsets.UTF_8));
        return "set -e; umask 077; install -d -m 0755 /etc/ablestack-storage /etc/dhcp/dhclient-enter-hooks.d; " +
                installNetworkResource(SHAREDFS_NETWORK_HELPER, encodedHelper, "0755") +
                installNetworkResource("/usr/local/lib/ablestack-storage/sharedfs_bootstrap_network.py",
                        Base64.getEncoder().encodeToString(SHAREDFS_NETWORK_BOOTSTRAP_CONTENT.getBytes(StandardCharsets.UTF_8)), "0644") +
                installNetworkResource("/etc/dhcp/dhclient-enter-hooks.d/ablestack-sharedfs",
                        Base64.getEncoder().encodeToString(SHAREDFS_NETWORK_DHCP_HOOK_CONTENT.getBytes(StandardCharsets.UTF_8)), "0644") +
                "printf '%s' '" + encodedPayload + "' | base64 -d > " + SHAREDFS_NETWORK_STATE + ".tmp; chmod 0600 " + SHAREDFS_NETWORK_STATE + ".tmp; " +
                SHAREDFS_NETWORK_HELPER + " --validate " + SHAREDFS_NETWORK_STATE + ".tmp >/dev/null; " +
                "mv " + SHAREDFS_NETWORK_STATE + ".tmp " + SHAREDFS_NETWORK_STATE + "; " +
                installNetworkResource(SHAREDFS_NETWORK_UNIT, encodedUnit, "0644") +
                installNetworkResource("/etc/systemd/system/ablestack-storage-reconcile.service",
                        Base64.getEncoder().encodeToString(SHAREDFS_NETWORK_RECONCILE_UNIT_CONTENT.getBytes(StandardCharsets.UTF_8)), "0644") +
                installNetworkResource("/opt/cloud/bin/setup/sharedfsvm.sh",
                        Base64.getEncoder().encodeToString(SHAREDFS_NETWORK_SETUP_CONTENT.getBytes(StandardCharsets.UTF_8)), "0755") +
                "systemctl daemon-reload; systemctl enable ablestack-sharedfs-network.service >/dev/null; " +
                buildSharedFsBootstrapGuard() +
                "systemctl restart ablestack-sharedfs-network.service; systemctl --no-pager --full status ablestack-sharedfs-network.service >/dev/null; " +
                SHAREDFS_NETWORK_HELPER + " --observe";

    }

    private String installNetworkResource(String path, String encoded, String mode) {
        return "printf '%s' '" + encoded + "' | base64 -d > " + path + ".tmp; chmod " + mode + " " + path + ".tmp; mv " + path + ".tmp " + path + "; ";
    }

    protected String buildSharedFsBootstrapGuard() {
        // Preserve bootstrap ordering; do not apply STATIC while cloud-init can still change the NIC.
        final String encoded = Base64.getEncoder().encodeToString(SHAREDFS_BOOTSTRAP_GUARD_CONTENT.getBytes(StandardCharsets.UTF_8));
        return "python3 -c \"import base64;exec(base64.b64decode('" + encoded + "'))\"; ";
    }

    protected String decodeGuestData(final JsonObject response, final String field) {
        if (!response.has(field) || response.get(field).isJsonNull()) {
            return null;
        }
        return new String(Base64.getDecoder().decode(response.get(field).getAsString()), StandardCharsets.UTF_8);
    }

    protected void validateOperation(final String operation) {
        if (operation == null || !operation.matches("[A-Za-z0-9 ._-]+")) {
            throw new IllegalArgumentException("Invalid Storage Service operation: " + operation);
        }
    }
}
