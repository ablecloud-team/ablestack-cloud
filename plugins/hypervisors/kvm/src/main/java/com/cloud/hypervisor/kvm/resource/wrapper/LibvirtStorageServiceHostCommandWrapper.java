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
    private static final String SHAREDFS_NETWORK_HELPER_CONTENT = String.join("\n",
            "#!/usr/bin/env python3",
            "import ipaddress",
            "import json",
            "import subprocess",
            "import sys",
            "from pathlib import Path",
            "",
            "def run(*args):",
            "    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout",
            "",
            "state_path = Path(sys.argv[1])",
            "state = json.loads(state_path.read_text(encoding='utf-8'))",
            "network = ipaddress.ip_network(state['cidr'], strict=False)",
            "address = ipaddress.ip_address(state['ipAddress'])",
            "gateway = ipaddress.ip_address(state['gateway']) if state.get('gateway') else None",
            "dns = [ipaddress.ip_address(state[key]) for key in ('dns1', 'dns2') if state.get(key)]",
            "if network.version != 4 or address.version != 4 or address not in network or (gateway and (gateway.version != 4 or gateway not in network)):",
            "    raise ValueError('invalid SharedFS static IPv4 configuration')",
            "if address in (network.network_address, network.broadcast_address):",
            "    raise ValueError('SharedFS static IP cannot be the network or broadcast address')",
            "mac = state['macAddress'].lower()",
            "interfaces = [path for path in Path('/sys/class/net').iterdir() if path.name != 'lo']",
            "interface = next((path.name for path in interfaces if (path / 'address').read_text().strip().lower() == mac), None)",
            "if not interface:",
            "    raise RuntimeError('unable to find SharedFS NIC by MAC address ' + mac)",
            "subprocess.run(['systemctl', 'disable', '--now', 'cloud-dhclient@' + interface + '.service'], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)",
            "run('ip', 'link', 'set', 'dev', interface, 'up')",
            "run('ip', '-4', 'addr', 'flush', 'dev', interface, 'scope', 'global')",
            "run('ip', 'addr', 'replace', str(address) + '/' + str(network.prefixlen), 'dev', interface)",
            "subprocess.run(['ip', 'route', 'del', 'default', 'dev', interface], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)",
            "if gateway:",
            "    run('ip', 'route', 'replace', 'default', 'via', str(gateway), 'dev', interface)",
            "if dns:",
            "    Path('/etc/resolv.conf').write_text(''.join('nameserver ' + str(server) + '\\n' for server in dns), encoding='utf-8')",
            "addresses = json.loads(run('ip', '-j', '-4', 'addr', 'show', 'dev', interface))",
            "routes = json.loads(run('ip', '-j', '-4', 'route', 'show', 'default'))",
            "configured = any(info.get('local') == str(address) and info.get('prefixlen') == network.prefixlen for item in addresses for info in item.get('addr_info', []))",
            "routed = gateway is None or any(route.get('gateway') == str(gateway) and route.get('dev') == interface for route in routes)",
            "if not configured or not routed:",
            "    raise RuntimeError('SharedFS static network verification failed')",
            "print(json.dumps({'interface': interface, 'ipAddress': str(address), 'cidr': str(network), 'gateway': str(gateway) if gateway else None, 'dns': [str(server) for server in dns]}, separators=(',', ':')))",
            "");
    private static final String SHAREDFS_NETWORK_UNIT_CONTENT = String.join("\n",
            "[Unit]",
            "Description=ABLESTACK SharedFS static network restore",
            "After=local-fs.target",
            "Before=network-pre.target network.target",
            "Wants=network-pre.target",
            "ConditionPathExists=" + SHAREDFS_NETWORK_STATE,
            "",
            "[Service]",
            "Type=oneshot",
            "ExecStart=" + SHAREDFS_NETWORK_HELPER + " " + SHAREDFS_NETWORK_STATE,
            "RemainAfterExit=yes",
            "",
            "[Install]",
            "WantedBy=multi-user.target",
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
        return "set -e; install -d -m 0755 /etc/ablestack-storage; " +
                "printf '%s' '" + encodedPayload + "' | base64 -d > " + SHAREDFS_NETWORK_STATE + ".tmp; " +
                "python3 -m json.tool " + SHAREDFS_NETWORK_STATE + ".tmp >/dev/null; " +
                "mv " + SHAREDFS_NETWORK_STATE + ".tmp " + SHAREDFS_NETWORK_STATE + "; chmod 0600 " + SHAREDFS_NETWORK_STATE + "; " +
                "printf '%s' '" + encodedHelper + "' | base64 -d > " + SHAREDFS_NETWORK_HELPER + ".tmp; " +
                "mv " + SHAREDFS_NETWORK_HELPER + ".tmp " + SHAREDFS_NETWORK_HELPER + "; chmod 0755 " + SHAREDFS_NETWORK_HELPER + "; " +
                "printf '%s' '" + encodedUnit + "' | base64 -d > " + SHAREDFS_NETWORK_UNIT + ".tmp; " +
                "mv " + SHAREDFS_NETWORK_UNIT + ".tmp " + SHAREDFS_NETWORK_UNIT + "; chmod 0644 " + SHAREDFS_NETWORK_UNIT + "; " +
                "systemctl daemon-reload; systemctl enable ablestack-sharedfs-network.service >/dev/null; " +
                "systemctl restart ablestack-sharedfs-network.service; systemctl --no-pager --full status ablestack-sharedfs-network.service >/dev/null; " +
                SHAREDFS_NETWORK_HELPER + " " + SHAREDFS_NETWORK_STATE;
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
