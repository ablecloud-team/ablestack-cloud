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
import java.util.Arrays;
import java.util.UUID;
import org.apache.commons.codec.digest.DigestUtils;

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
    static final int STDIN_CHUNK_BYTES = 48 * 1024;
    private static final String STAGING_WRITER = String.join("\n",
            "import os,stat,sys",
            "path,offset,count=sys.argv[1],int(sys.argv[2]),int(sys.argv[3])",
            "flags=os.O_WRONLY|os.O_NOFOLLOW",
            "flags |= (os.O_CREAT|os.O_EXCL) if offset == 0 else os.O_APPEND",
            "fd=os.open(path,flags,0o600)",
            "try:",
            " s=os.fstat(fd)",
            " assert stat.S_ISREG(s.st_mode) and s.st_uid==os.geteuid() and stat.S_IMODE(s.st_mode)==0o600 and s.st_nlink==1 and s.st_size==offset",
            " data=sys.stdin.buffer.read(49153)",
            " assert len(data)==count and count<=49152",
            " view=memoryview(data)",
            " while view:",
            "  written=os.write(fd,view);view=view[written:]",
            "finally: os.close(fd)");

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
                return notStarted(command, "QGA_VM_UNAVAILABLE");
            }
            if (domain.getInfo().state != DomainState.VIR_DOMAIN_RUNNING) {
                return notStarted(command, "QGA_VM_NOT_RUNNING");
            }

            final long pid = executeGuestCommand(domain, command);
            return waitForGuestCommand(command, domain, pid);
        } catch (final PayloadTransferException e) {
            return notStarted(command, "QGA_STDIN_TRANSFER_FAILED");
        } catch (final RuntimeException e) {
            return new StorageServiceHostAnswer(command, false, e.getMessage(), null);
        } catch (final LibvirtException e) {
            if (e.getMessage() != null && e.getMessage().contains("Failed to execute child process")) {
                return notStarted(command, e.getMessage().contains("Argument list too long")
                        ? "QGA_EXEC_ARGUMENT_TOO_LARGE" : "QGA_EXEC_REJECTED");
            }
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
        if (!CONFIGURE_SHAREDFS_STATIC_NETWORK.equals(command.getOperation()) && payloadBytes(command).length > STDIN_CHUNK_BYTES) {
            return executeStagedGuestCommand(domain, command);
        }
        return launch(domain, buildGuestExecCommand(command), command.getTimeoutSeconds());
    }

    private long launch(final Domain domain, final String qgaCommand, final int seconds) throws LibvirtException {
        final String result = domain.qemuAgentCommand(qgaCommand, seconds, 0);
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
        if (!CONFIGURE_SHAREDFS_STATIC_NETWORK.equals(command.getOperation())) {
            arguments.addProperty("input-data", Base64.getEncoder().encodeToString(payloadBytes(command)));
        }
        arguments.addProperty("capture-output", true);
        qgaCommand.add("arguments", arguments);
        return qgaCommand.toString();
    }

    protected String buildStorageCtlShell(final StorageServiceHostCommand command) {
        if (CONFIGURE_SHAREDFS_STATIC_NETWORK.equals(command.getOperation())) {
            return buildSharedFsStaticNetworkShell(command);
        }
        return "umask 077; payload=$(mktemp /tmp/ablestack-storage-XXXXXX.json) || exit; " +
                "trap 'rm -f \"$payload\"' EXIT; cat > \"$payload\" || exit; " +
                "/usr/local/bin/ablestack-storagectl " + command.getOperation() + " \"$payload\"";
    }

    private static byte[] payloadBytes(final StorageServiceHostCommand command) {
        return (command.getPayload() == null ? "" : command.getPayload()).getBytes(StandardCharsets.UTF_8);
    }

    private static StorageServiceHostAnswer notStarted(final StorageServiceHostCommand command, final String code) {
        JsonObject dispatch = new JsonObject();
        dispatch.addProperty("state", "NOT_STARTED");
        dispatch.addProperty("code", code);
        JsonObject result = new JsonObject();
        result.add("dispatch", dispatch);
        return new StorageServiceHostAnswer(command, false, code, result.toString());
    }

    static final class PayloadTransferException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        PayloadTransferException(Throwable cause) { super("QGA stdin staging failed before command start", cause); }
    }

    private long executeStagedGuestCommand(final Domain domain, final StorageServiceHostCommand command)
            throws LibvirtException {
        byte[] bytes = payloadBytes(command);
        String path = "/tmp/ablestack-storage-" + UUID.randomUUID() + ".json";
        long deadline = System.currentTimeMillis() + command.getTimeoutSeconds() * 1000L;
        try {
            for (int offset = 0; offset < bytes.length; offset += STDIN_CHUNK_BYTES) {
                int count = Math.min(STDIN_CHUNK_BYTES, bytes.length - offset);
                JsonObject request = new JsonObject();
                request.addProperty("execute", "guest-exec");
                JsonObject arguments = new JsonObject();
                arguments.addProperty("path", "/usr/bin/python3");
                JsonArray args = new JsonArray();
                for (String arg : new String[] {"-c", STAGING_WRITER, path, String.valueOf(offset), String.valueOf(count)}) args.add(arg);
                arguments.add("arg", args);
                arguments.addProperty("input-data", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, offset + count)));
                arguments.addProperty("capture-output", true);
                request.add("arguments", arguments);
                long pid = launch(domain, request.toString(), remainingSeconds(deadline));
                waitForStaging(domain, pid, deadline);
            }
            String verify = "import hashlib,os,stat,sys; f=os.open(sys.argv[1],os.O_RDONLY|os.O_NOFOLLOW); " +
                    "s=os.fstat(f); assert stat.S_ISREG(s.st_mode) and s.st_uid==os.geteuid() and stat.S_IMODE(s.st_mode)==0o600 and s.st_nlink==1; " +
                    "b=os.fdopen(f,'rb').read(); assert len(b)==int(sys.argv[2]) and hashlib.sha256(b).hexdigest()==sys.argv[3]";
            JsonObject check = new JsonObject(); check.addProperty("execute", "guest-exec");
            JsonObject checkArgs = new JsonObject(); checkArgs.addProperty("path", "/usr/bin/python3");
            JsonArray checkArgv = new JsonArray(); checkArgv.add("-c"); checkArgv.add(verify);
            checkArgv.add(path); checkArgv.add(String.valueOf(bytes.length)); checkArgv.add(DigestUtils.sha256Hex(bytes));
            checkArgs.add("arg", checkArgv); checkArgs.addProperty("capture-output", true); check.add("arguments", checkArgs);
            waitForStaging(domain, launch(domain, check.toString(), remainingSeconds(deadline)), deadline);
        } catch (Exception error) {
            // Only the private staging file can have changed; storagectl has not been launched.
            cleanupStaging(domain, path);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new PayloadTransferException(error);
        }
        // After this boundary a lost reply is ambiguous. The guest owns cleanup and the coordinator keeps its reservation.
        String shell = "payload='" + path + "'; trap 'rm -f \"$payload\"' EXIT; " +
                "/usr/local/bin/ablestack-storagectl " + command.getOperation() + " \"$payload\"";
        JsonObject request = new JsonObject();
        request.addProperty("execute", "guest-exec");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("path", "/bin/bash");
        JsonArray args = new JsonArray(); args.add("-lc"); args.add(shell);
        arguments.add("arg", args); arguments.addProperty("capture-output", true); request.add("arguments", arguments);
        try {
            return launch(domain, request.toString(), command.getTimeoutSeconds());
        } catch (LibvirtException error) {
            if (error.getMessage() != null && error.getMessage().contains("Failed to execute child process")) cleanupStaging(domain, path);
            throw error;
        }
    }

    private static int remainingSeconds(long deadline) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("QGA stdin staging deadline exceeded");
        return (int)Math.max(1, (remaining + 999) / 1000);
    }

    private void waitForStaging(Domain domain, long pid, long deadline) throws LibvirtException, InterruptedException {
        while (true) {
            JsonObject request = new JsonObject(); request.addProperty("execute", "guest-exec-status");
            JsonObject args = new JsonObject(); args.addProperty("pid", pid); request.add("arguments", args);
            JsonObject result = JsonParser.parseString(domain.qemuAgentCommand(request.toString(), remainingSeconds(deadline), 0))
                    .getAsJsonObject().getAsJsonObject("return");
            if (result != null && result.has("exited") && result.get("exited").getAsBoolean()) {
                if (!result.has("exitcode") || result.get("exitcode").getAsInt() != 0)
                    throw new IllegalStateException("QGA stdin staging writer failed");
                return;
            }
            Thread.sleep(50);
        }
    }

    private void cleanupStaging(Domain domain, String path) {
        try {
            JsonObject request = new JsonObject(); request.addProperty("execute", "guest-exec");
            JsonObject arguments = new JsonObject(); arguments.addProperty("path", "/usr/bin/python3");
            JsonArray args = new JsonArray(); args.add("-c");
            args.add("import pathlib,sys;pathlib.Path(sys.argv[1]).unlink(missing_ok=True)"); args.add(path);
            arguments.add("arg", args); arguments.addProperty("capture-output", true); request.add("arguments", arguments);
            long pid = launch(domain, request.toString(), 5);
            waitForStaging(domain, pid, System.currentTimeMillis() + 5000);
        } catch (Exception ignored) {
            // A lost QGA connection must not turn staging cleanup into an unsafe command retry.
            if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        }
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
