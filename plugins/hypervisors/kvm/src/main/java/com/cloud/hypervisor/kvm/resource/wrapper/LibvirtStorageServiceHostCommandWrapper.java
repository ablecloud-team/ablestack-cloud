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

import java.io.StringReader;
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
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

@ResourceWrapper(handles = StorageServiceHostCommand.class)
public final class LibvirtStorageServiceHostCommandWrapper extends CommandWrapper<StorageServiceHostCommand, Answer, LibvirtComputingResource> {
    private static final int QGA_POLL_INTERVAL_MILLIS = 1000;
    static final int DIRECT_PROTECTED_STDIN_MAX_BYTES = 32768;
    static final int PROTECTED_STDIN_CHUNK_BYTES = 32768;
    static final int PROTECTED_STDIN_MAX_BYTES = 64 * 1024 * 1024;
    private static final int PROTECTED_STDIN_CLEANUP_SECONDS = 2;
    private final ThreadLocal<Long> protectedStdinDeadline = new ThreadLocal<>();
    private static final String PROTECTED_STDIN_RECEIVER = String.join("\n",
            "\"\"\"Protected input stays in anonymous RAM; no body or key is printed.\"\"\"",
            "import fcntl,hashlib,os,re,select,stat,sys,time",
            "DATA_FD,CONTROL_READ_FD,CONTROL_WRITE_FD=16,17,18",
            "MAX_BYTES=64*1024*1024",
            "SEALS=fcntl.F_SEAL_SEAL|fcntl.F_SEAL_SHRINK|fcntl.F_SEAL_GROW|fcntl.F_SEAL_WRITE",
            "",
            "NAME='ablestack-protected-input'",
            "def require(value):",
            "    if not value:raise ValueError()",
            "def setup():",
            "    require(os.geteuid()==0)",
            "    for fd in (DATA_FD,CONTROL_READ_FD,CONTROL_WRITE_FD):",
            "        try:fcntl.fcntl(fd,fcntl.F_GETFD)",
            "        except OSError as error:require(error.errno==9)",
            "        else:raise ValueError()",
            "    originals=[];safe=[]",
            "    try:",
            "        data=os.memfd_create(NAME,os.MFD_CLOEXEC|os.MFD_ALLOW_SEALING);originals.append(data)",
            "        control_read,control_write=os.pipe2(os.O_CLOEXEC);originals.extend((control_read,control_write))",
            "        for fd in originals:safe.append(fcntl.fcntl(fd,fcntl.F_DUPFD_CLOEXEC,19))",
            "        for fd in originals:os.close(fd)",
            "        originals=[]",
            "        for source,target in zip(safe,(DATA_FD,CONTROL_READ_FD,CONTROL_WRITE_FD)):",
            "            os.dup2(source,target,inheritable=False)",
            "        os.fchmod(DATA_FD,0o600);os.fchmod(CONTROL_WRITE_FD,0o600)",
            "    finally:",
            "        for fd in originals+safe:os.close(fd)",
            "def close_owned():",
            "    for fd in (DATA_FD,CONTROL_READ_FD,CONTROL_WRITE_FD):",
            "        try:os.close(fd)",
            "        except OSError:pass",
            "def seal_and_verify(fd,expected_bytes,expected_sha256):",
            "    require(type(expected_bytes)is int and 0<expected_bytes<=MAX_BYTES)",
            "    require(type(expected_sha256)is str and len(expected_sha256)==64 and all(x in '0123456789abcdef'for x in expected_sha256))",
            "    before=os.fstat(fd)",
            "    require(stat.S_ISREG(before.st_mode)and before.st_uid==0 and stat.S_IMODE(before.st_mode)==0o600 and before.st_size==expected_bytes)",
            "    fcntl.fcntl(fd,fcntl.F_ADD_SEALS,SEALS)",
            "    require(fcntl.fcntl(fd,fcntl.F_GET_SEALS)==SEALS)",
            "    digest=hashlib.sha256();offset=0",
            "    while offset<expected_bytes:",
            "        raw=os.pread(fd,min(65536,expected_bytes-offset),offset)",
            "        require(bool(raw));digest.update(raw);offset+=len(raw)",
            "    require(digest.hexdigest()==expected_sha256)",
            "    after=os.fstat(fd);require((before.st_dev,before.st_ino,before.st_size)==(after.st_dev,after.st_ino,after.st_size))",
            "    os.fchmod(fd,0o400);os.lseek(fd,0,os.SEEK_SET)",
            "def wait_commit(seconds):",
            "    require(type(seconds)in(int,float)and 0<seconds<=300)",
            "    ready,_,_=select.select([CONTROL_READ_FD],[],[],seconds)",
            "    require(ready and os.read(CONTROL_READ_FD,1)==b'\\x01')",
            "def run(expected_bytes,expected_sha256,seconds,operation):",
            "    require(type(operation)is str and re.fullmatch(r'[A-Za-z0-9 ._-]+',operation) is not None)",
            "    setup()",
            "    try:",
            "        wait_commit(seconds)",
            "        seal_and_verify(DATA_FD,expected_bytes,expected_sha256)",
            "        os.dup2(DATA_FD,0,inheritable=True)",
            "        close_owned()",
            "        # Original CLI receives the same bytes on /dev/stdin; no native authority changes.",
            "        os.execv('/usr/local/bin/ablestack-storagectl',['/usr/local/bin/ablestack-storagectl',*operation.split(),'/dev/stdin'])",
            "    finally:close_owned()",
            "def main():",
            "    stage='arguments'",
            "    try:",
            "        require(len(sys.argv)==5)",
            "        expected_bytes=int(sys.argv[1]);expected_sha256=sys.argv[2];seconds=float(sys.argv[3]);operation=sys.argv[4]",
            "        require(type(operation)is str and re.fullmatch(r'[A-Za-z0-9 ._-]+',operation) is not None)",
            "        require(0<expected_bytes<=MAX_BYTES and 0<seconds<=300)",
            "        stage='receive-seal-exec';run(expected_bytes,expected_sha256,seconds,operation)",
            "    except BaseException:",
            "        # Never emit exception message, arguments, digest, payload or traceback.",
            "        sys.stderr.write('PROTECTED_RAM_INPUT_REJECTED\\n');return 1",
            "    return 0",
            "if __name__=='__main__':raise SystemExit(main())",
            "");
    private static final String PROTECTED_STDIN_OBSERVER = String.join("\n",
            "",
            "import hashlib,os,stat",
            "def observe(pid,expected_argv,expected_start=None):",
            "    if type(pid)is not int or pid<=0:raise ValueError()",
            "    root=f'/proc/{pid}'",
            "    before=os.stat(root)",
            "    if before.st_uid!=0:raise ValueError()",
            "    with open(root+'/stat','rb')as stream:record=stream.read(4096)",
            "    start=int(record[record.rfind(b')')+2:].split()[19])",
            "    with open(root+'/cmdline','rb')as stream:argv=stream.read(32769).rstrip(b'\\0').decode().split('\\0')",
            "    if argv!=expected_argv or expected_start is not None and start!=expected_start:raise ValueError()",
            "    executable=os.readlink(root+'/exe')",
            "    if executable not in ('/usr/bin/python3.9','/usr/bin/python3.11','/usr/bin/python3.12'):raise ValueError()",
            "    binary=os.stat(root+'/exe')",
            "    if not stat.S_ISREG(binary.st_mode)or binary.st_uid!=0 or binary.st_mode&0o022:raise ValueError()",
            "    a=os.stat(root+'/fd/16');b=os.stat(root+'/fd/18')",
            "    if os.readlink(root+'/fd/16')!='/memfd:ablestack-protected-input (deleted)'or not stat.S_ISREG(a.st_mode)or a.st_uid!=0 or stat.S_IMODE(a.st_mode)!=0o600:raise ValueError()",
            "    if not stat.S_ISFIFO(b.st_mode)or b.st_uid!=0 or stat.S_IMODE(b.st_mode)!=0o600:raise ValueError()",
            "    with open(root+'/stat','rb')as stream:after=stream.read(4096)",
            "    if int(after[after.rfind(b')')+2:].split()[19])!=start:raise ValueError()",
            "    return {'ready':True,'rootOwned':True,'receiverScriptVerified':True,'pid':pid,'startTicks':start,'dataInode':a.st_ino,'controlInode':b.st_ino}",
            "",
            "import base64,json,sys",
            "try:",
            "    value=observe(int(sys.argv[1]),json.loads(base64.b64decode(sys.argv[2])))",
            "    print(json.dumps(value,separators=(',',':')))",
            "except BaseException:",
            "    sys.stderr.write('PROTECTED_RAM_RECEIVER_REJECTED\\n');raise SystemExit(1)",
            "");

    private static final String CONFIGURE_SHAREDFS_STATIC_NETWORK = "configure-sharedfs-static-network";
    private static final String SHAREDFS_NETWORK_STATE = "/etc/ablestack-storage/sharedfs-network.json";
    private static final String SHAREDFS_NETWORK_HELPER = "/usr/local/sbin/ablestack-sharedfs-network";
    private static final String SHAREDFS_NETWORK_UNIT = "/etc/systemd/system/ablestack-sharedfs-network.service";
    private static final String SHAREDFS_NETWORK_HELPER_CONTENT = String.join("\n",
            "#!/usr/bin/env python3",
            "import ipaddress",
            "import json",
            "import os",
            "import signal",
            "import time",
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
            "if gateway and (gateway == address or gateway in (network.network_address, network.broadcast_address) or gateway.is_unspecified or gateway.is_multicast or gateway.is_loopback or gateway.is_link_local):",
            "    raise ValueError('SharedFS gateway must be a unicast router address in the selected CIDR')",
            "mac = state['macAddress'].lower()",
            "interfaces = [path for path in Path('/sys/class/net').iterdir() if path.name != 'lo']",
            "interface = next((path.name for path in interfaces if (path / 'address').read_text().strip().lower() == mac), None)",
            "if not interface:",
            "    raise RuntimeError('unable to find SharedFS NIC by MAC address ' + mac)",
            "subprocess.run(['systemctl', 'disable', '--now', 'cloud-dhclient@' + interface + '.service'], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)",
            "run('systemctl', 'mask', '--now', 'cloud-dhclient@' + interface + '.service')",
            "for proc in Path('/proc').iterdir():",
            "    if not proc.name.isdigit(): continue",
            "    try:",
            "        argv = (proc / 'cmdline').read_bytes().split(bytes([0]))",
            "        if not argv or Path(os.fsdecode(argv[0])).name != 'dhclient' or interface.encode() not in argv: continue",
            "        os.kill(int(proc.name), signal.SIGTERM)",
            "        deadline = time.monotonic() + 5",
            "        while proc.exists() and time.monotonic() < deadline: time.sleep(0.1)",
            "        if proc.exists(): raise RuntimeError('DHCP client did not stop for ' + interface)",
            "    except (FileNotFoundError, ProcessLookupError): pass",
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
            "Before=network-pre.target network.target ablestack-storage-reconcile.service",
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
        final long started = System.nanoTime();
        int connectionToken = 0;
        String stage = "VALIDATE";
        try {
            validateOperation(command.getOperation());
            stage = "CONNECT";
            final LibvirtUtilitiesHelper libvirtUtilitiesHelper = libvirtComputingResource.getLibvirtUtilitiesHelper();
            final Connect connect = libvirtUtilitiesHelper.getConnection();
            connectionToken = System.identityHashCode(connect);
            stage = "DOMAIN";
            domain = libvirtComputingResource.getDomain(connect, command.getVmName());
            if (domain == null) {
                return new StorageServiceHostAnswer(command, false, "Storage Service System VM was not found: " + command.getVmName(), null);
            }
            if (domain.getInfo().state != DomainState.VIR_DOMAIN_RUNNING) {
                return new StorageServiceHostAnswer(command, false, "Storage Service System VM is not running: " + command.getVmName(), null);
            }

            stage = "LAUNCH";
            final long pid = executeGuestCommand(domain, command);
            stage = "STATUS";
            return waitForGuestCommand(command, domain, pid);
        } catch (final RuntimeException e) {
            logSafeTransportFailure(stage, e, started, connectionToken);
            return new StorageServiceHostAnswer(command, false, commandExceptionDetails(command,e.getMessage()), null);
        } catch (final LibvirtException e) {
            logSafeTransportFailure(stage, e, started, connectionToken);
            return new StorageServiceHostAnswer(command, false, commandExceptionDetails(command,"Failed to execute Storage Service QGA command: " + e.getMessage()), null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return new StorageServiceHostAnswer(command, false, "Interrupted while waiting for Storage Service QGA command", null);
        } finally {
            protectedStdinDeadline.remove();
            if (domain != null) {
                try {
                    domain.free();
                } catch (final LibvirtException e) {
                    logger.trace("Ignoring libvirt domain free error");
                }
            }
        }
    }

    private void logSafeTransportFailure(String stage, Exception failure, long started, int connectionToken) {
        try {
            logger.warn("Storage Service safe transport failure {}", safeTransportFailure(stage, failure,
                    Math.max(0, (System.nanoTime() - started) / 1_000_000L), connectionToken).toString());
        } catch (RuntimeException diagnosticUnavailable) {
            // Optional diagnostics must never replace the original masked answer.
        }
    }

    protected JsonObject safeTransportFailure(String stage, Exception failure, long elapsedMillis, int connectionToken) {
        String fixedStage = stage != null && java.util.Set.of("VALIDATE", "CONNECT", "DOMAIN", "LAUNCH", "STATUS", "DECODE").contains(stage) ? stage : "UNKNOWN";
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (!LibvirtStorageServiceHostCommandWrapper.class.getName().equals(frame.getClassName())) continue;
            if ("decodeGuestData".equals(frame.getMethodName())) {fixedStage = "DECODE";break;}
            if ("executeGuestCommand".equals(frame.getMethodName())) {fixedStage = "LAUNCH";break;}
            if ("waitForGuestCommand".equals(frame.getMethodName())) {fixedStage = "STATUS";break;}
        }
        String category = failure instanceof LibvirtException ? "LIBVIRT"
                : failure instanceof com.google.gson.JsonParseException ? "JSON"
                : failure instanceof NullPointerException ? "NULL_REFERENCE"
                : failure instanceof IllegalArgumentException ? "ARGUMENT"
                : failure instanceof IllegalStateException ? "STATE" : "RUNTIME_OTHER";
        JsonObject value = new JsonObject();
        value.addProperty("kind", "STORAGE_GUEST_TRANSPORT_EXCEPTION");value.addProperty("stage", fixedStage);
        value.addProperty("exceptionClass", category);value.addProperty("elapsedMillis", Math.max(0, elapsedMillis));
        value.addProperty("connectionToken", connectionToken);
        value.add("libvirtCode", com.google.gson.JsonNull.INSTANCE);value.add("libvirtDomain", com.google.gson.JsonNull.INSTANCE);
        if (failure instanceof LibvirtException) {
            // libvirt-java maps native code/domain by enum array index; UNKNOWN
            // is a fallback and cannot be reported as the original numeric ABI.
            org.libvirt.Error error = ((LibvirtException) failure).getError();
            if (error != null) {
                org.libvirt.Error.ErrorNumber code = error.getCode();
                org.libvirt.Error.ErrorDomain domain = error.getDomain();
                if (code != null && code != org.libvirt.Error.ErrorNumber.VIR_ERR_UNKNOWN) value.addProperty("libvirtCode", code.ordinal());
                if (domain != null && domain != org.libvirt.Error.ErrorDomain.VIR_FROM_UNKNOWN) value.addProperty("libvirtDomain", domain.ordinal());
            }
        }
        return value;
    }

    protected long executeGuestCommand(final Domain domain, final StorageServiceHostCommand command) throws LibvirtException, InterruptedException {
        final byte[] protectedPayload = usesProtectedStdin(command) && command.getPayload() != null
                ? command.getPayload().getBytes(StandardCharsets.UTF_8) : null;
        try {
            if (protectedPayload != null && protectedPayload.length > DIRECT_PROTECTED_STDIN_MAX_BYTES)
                return executeLargeProtectedInput(domain, command, protectedPayload);
            final String qgaCommand = buildGuestExecCommand(command);
            final String result = domain.qemuAgentCommand(qgaCommand, command.getTimeoutSeconds(), 0);
            final JsonObject response = new JsonParser().parse(result).getAsJsonObject();
            if (!response.has("return") || !response.getAsJsonObject("return").has("pid"))
                throw new IllegalStateException("QGA guest-exec did not return a pid: " + result);
            return response.getAsJsonObject("return").get("pid").getAsLong();
        } finally {
            if (protectedPayload != null) java.util.Arrays.fill(protectedPayload, (byte) 0);
        }
    }

    protected Answer waitForGuestCommand(final StorageServiceHostCommand command, final Domain domain, final long pid)
            throws LibvirtException, InterruptedException {
        final Long protectedDeadline = protectedStdinDeadline.get();
        final long deadline = protectedDeadline == null ? System.currentTimeMillis() + command.getTimeoutSeconds() * 1000L : protectedDeadline;
        while (System.currentTimeMillis() < deadline) {
            final JsonObject arguments = new JsonObject();
            arguments.addProperty("pid", pid);
            final JsonObject statusCommand = new JsonObject();
            statusCommand.addProperty("execute", "guest-exec-status");
            statusCommand.add("arguments", arguments);
            final long remaining = deadline - System.currentTimeMillis();
            if (protectedDeadline != null && remaining < 1000) break;
            final int statusTimeout = protectedDeadline == null ? Math.max(command.getTimeoutSeconds(), 1)
                    : (int) Math.max(1, remaining / 1000);
            final String result = domain.qemuAgentCommand(statusCommand.toString(), statusTimeout, 0);
            final JsonObject response = new JsonParser().parse(result).getAsJsonObject().getAsJsonObject("return");
            if (response != null && response.has("exited") && response.get("exited").getAsBoolean()) {
                final int exitCode = response.has("exitcode") ? response.get("exitcode").getAsInt() : 1;
                final String stdout = decodeGuestData(response, "out-data");
                final String stderr = decodeGuestData(response, "err-data");
                final String details = exitCode == 0 ? "Storage Service command completed" :
                        commandFailureDetails(command, exitCode, stdout, stderr);
                return new StorageServiceHostAnswer(command, exitCode == 0, details, identityTransportObservation(command, stdout));
            }
            Thread.sleep(protectedDeadline == null ? QGA_POLL_INTERVAL_MILLIS
                    : Math.min(QGA_POLL_INTERVAL_MILLIS, Math.max(1, deadline - System.currentTimeMillis())));
        }
        return new StorageServiceHostAnswer(command, false, "Timed out waiting for Storage Service QGA command", null);
    }

    protected String identityTransportObservation(StorageServiceHostCommand command, String stdout) {
        if (!"identity capsule capabilities".equals(command.getOperation())) return stdout;
        try {
            JsonObject capability = new JsonParser().parse(stdout).getAsJsonObject();
            if (capability.has("success") && capability.get("success").getAsBoolean()) {
                capability.addProperty("protectedStdinTransport", true);
                return capability.toString();
            }
        } catch (RuntimeException unavailable) { /* Do not advertise transport when the native probe failed. */ }
        return stdout;
    }

    private boolean hasSensitivePayload(StorageServiceHostCommand command) {
        return (command.getMaskedFields() != null && !command.getMaskedFields().isEmpty())
                || (command.getOperation() != null && java.util.Set.of("operation generation render-activate", "operation generation render-rollback").contains(command.getOperation()));
    }

    protected String commandExceptionDetails(StorageServiceHostCommand command,String diagnostic) {
        if (hasSensitivePayload(command)) return "Sensitive Storage Service host command failed; secret-bearing diagnostic omitted";
        return diagnostic;
    }

    protected String commandFailureDetails(StorageServiceHostCommand command, int exitCode, String stdout, String stderr) {
        if (hasSensitivePayload(command)) {
            final String masked = "Sensitive Storage Service command failed with exit code " + exitCode + "; secret-bearing output omitted";
            final String fixed = "iscsi target apply".equals(command.getOperation()) ? fixedIscsiFailureDiagnostic(stdout) : null;
            return fixed == null ? masked : masked + " [" + fixed + "]";
        }
        String diagnostic=stderr;
        if (diagnostic == null || diagnostic.trim().isEmpty()) {
            try {
                JsonObject result=new JsonParser().parse(stdout).getAsJsonObject();
                if (result.has("message") && result.get("message").isJsonPrimitive()) diagnostic=result.get("message").getAsString();
                if (result.has("errorCode") && result.get("errorCode").isJsonPrimitive()) diagnostic=result.get("errorCode").getAsString()+": "+diagnostic;
            } catch (RuntimeException unavailable) { diagnostic="No structured guest diagnostic"; }
        }
        if (diagnostic == null || diagnostic.trim().isEmpty()) diagnostic="No guest diagnostic";
        return "Storage Service command failed with exit code " + exitCode + ": " + diagnostic.substring(0,Math.min(diagnostic.length(),2048));
    }

    private String fixedIscsiFailureDiagnostic(String stdout) {
        if (stdout == null || stdout.length() > 4096) return null;
        final String value = stdout.trim();
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) return null;
        final java.util.Set<String> fields = java.util.Set.of("success", "kind", "stage", "returnCode", "category");
        final java.util.Set<String> stages = java.util.Set.of("CLEANUP", "TARGET_CREATE", "PORTAL_CREATE", "BACKSTORE_CREATE", "LUN_CREATE", "ACL_CREATE", "AUTH_POLICY");
        final java.util.Set<String> categories = java.util.Set.of("UNCLASSIFIED", "ATTRIBUTE_ERROR", "TYPE_ERROR", "IMPORT_ERROR", "PERMISSION_ERROR", "WWN_REJECTED", "CONFIGFS_ERROR", "TIMEOUT", "SPAWN_FAILURE");
        final java.util.Set<String> seen = new java.util.HashSet<>();
        String stage = null;
        String category = null;
        Integer code = null;
        boolean nullCode = false;
        try (JsonReader reader = new JsonReader(new StringReader(value))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                final String name = reader.nextName();
                if (!fields.contains(name) || !seen.add(name)) return null;
                switch (name) {
                    case "success":
                        if (reader.peek() != JsonToken.BOOLEAN || reader.nextBoolean()) return null;
                        break;
                    case "kind":
                        if (reader.peek() != JsonToken.STRING || !"ISCSI_TARGETCLI_COMMAND_FAILED".equals(reader.nextString())) return null;
                        break;
                    case "stage":
                        if (reader.peek() != JsonToken.STRING) return null;
                        stage = reader.nextString();
                        if (!stages.contains(stage)) return null;
                        break;
                    case "category":
                        if (reader.peek() != JsonToken.STRING) return null;
                        category = reader.nextString();
                        if (!categories.contains(category)) return null;
                        break;
                    case "returnCode":
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull();
                            nullCode = true;
                        } else {
                            if (reader.peek() != JsonToken.NUMBER) return null;
                            final String rawCode = reader.nextString();
                            if (!rawCode.matches("-?(?:0|[1-9][0-9]{0,2})")) return null;
                            code = Integer.valueOf(rawCode);
                            if (code == 0 || code < -64 || code > 255) return null;
                        }
                        break;
                    default:
                        return null;
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !seen.equals(fields)) return null;
            if (nullCode != java.util.Set.of("TIMEOUT", "SPAWN_FAILURE").contains(category)) return null;
            return "ISCSI stage=" + stage + "; returnCode=" + (nullCode ? "UNAVAILABLE" : code) + "; category=" + category;
        } catch (java.io.IOException | RuntimeException unavailable) {
            return null;
        }
    }

    private boolean usesProtectedStdin(StorageServiceHostCommand command) {
        return (command.getOperation() != null && command.getOperation().startsWith("identity capsule ")) || (command.getMaskedFields() != null && !command.getMaskedFields().isEmpty())
                || (command.getOperation() != null && java.util.Set.of("operation generation render-stage", "operation generation render-activate", "operation generation render-rollback").contains(command.getOperation()));
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
        if (usesProtectedStdin(command)) {
            // QGA stdin keeps wrapping credentials out of the guest process argument list.
            arguments.addProperty("input-data", Base64.getEncoder().encodeToString(
                    (command.getPayload() == null ? "" : command.getPayload()).getBytes(StandardCharsets.UTF_8)));
        }
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
        if (usesProtectedStdin(command)) {
            return "/usr/local/bin/ablestack-storagectl " + command.getOperation() + " /dev/stdin";
        }
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


    protected JsonObject protectedInputQga(Domain domain, JsonObject request, long deadline) throws LibvirtException {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("Protected stdin deadline expired");
        int timeout = (int) Math.max(1, Math.min(Integer.MAX_VALUE, remaining / 1000));
        JsonObject response = JsonParser.parseString(domain.qemuAgentCommand(request.toString(), timeout, 0)).getAsJsonObject();
        if (response.has("error") || !response.has("return")) throw new IllegalStateException("Protected stdin QGA operation failed");
        return response;
    }

    private JsonObject protectedInputCommand(String action, JsonObject arguments) {
        JsonObject request = new JsonObject();request.addProperty("execute", action);request.add("arguments", arguments);return request;
    }

    private long protectedInputInteger(JsonObject value, String field) {
        if (!value.has(field) || !value.get(field).isJsonPrimitive() || !value.getAsJsonPrimitive(field).isNumber())
            throw new IllegalStateException("Protected stdin numeric observation is missing");
        try {
            long number = new java.math.BigDecimal(value.get(field).getAsString()).longValueExact();
            if (number < 0) throw new IllegalStateException("Protected stdin numeric observation is invalid");
            return number;
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw new IllegalStateException("Protected stdin numeric observation is invalid");
        }
    }

    private JsonObject protectedInputExec(String program, JsonArray arguments) {
        JsonArray argv = new JsonArray();argv.add("-c");argv.add(program);
        for (com.google.gson.JsonElement argument : arguments) argv.add(argument.deepCopy());
        JsonObject request = new JsonObject();request.addProperty("path", "/usr/bin/python3");
        request.add("arg", argv);request.addProperty("capture-output", true);
        return protectedInputCommand("guest-exec", request);
    }

    protected JsonObject observeProtectedInputReceiver(Domain domain, long pid, JsonArray expectedArgv, long deadline)
            throws LibvirtException, InterruptedException {
        JsonArray arguments = new JsonArray();arguments.add(Long.toString(pid));
        arguments.add(Base64.getEncoder().encodeToString(expectedArgv.toString().getBytes(StandardCharsets.UTF_8)));
        long observerPid = protectedInputInteger(protectedInputQga(domain, protectedInputExec(PROTECTED_STDIN_OBSERVER, arguments), deadline)
                .getAsJsonObject("return"), "pid");
        while (System.currentTimeMillis() < deadline) {
            JsonObject statusArgs = new JsonObject();statusArgs.addProperty("pid", observerPid);
            JsonObject status = protectedInputQga(domain, protectedInputCommand("guest-exec-status", statusArgs), deadline).getAsJsonObject("return");
            if (status.has("exited") && status.getAsJsonPrimitive("exited").isBoolean() && status.get("exited").getAsBoolean()) {
                if (protectedInputInteger(status, "exitcode") != 0 || status.has("out-truncated") && status.get("out-truncated").getAsBoolean())
                    throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
                return JsonParser.parseString(decodeGuestData(status, "out-data")).getAsJsonObject();
            }
            Thread.sleep(Math.min(QGA_POLL_INTERVAL_MILLIS, Math.max(1, deadline - System.currentTimeMillis())));
        }
        throw new IllegalStateException("Protected stdin receiver ownership deadline expired");
    }

    private void requireProtectedInputOwner(JsonObject value, long pid) {
        for (String field : new String[]{"ready", "rootOwned", "receiverScriptVerified"}) {
            if (!value.has(field) || !value.get(field).isJsonPrimitive() || !value.getAsJsonPrimitive(field).isBoolean()
                    || !value.get(field).getAsBoolean()) throw new IllegalStateException("Protected stdin receiver ownership changed");
        }
        if (protectedInputInteger(value, "pid") != pid || protectedInputInteger(value, "startTicks") == 0
                || protectedInputInteger(value, "dataInode") == 0 || protectedInputInteger(value, "controlInode") == 0)
            throw new IllegalStateException("Protected stdin receiver ownership changed");
    }

    private long openProtectedInputHandle(Domain domain, long pid, int fd, long deadline) throws LibvirtException {
        JsonObject arguments = new JsonObject();arguments.addProperty("path", "/proc/" + pid + "/fd/" + fd);arguments.addProperty("mode", "w");
        JsonObject response = protectedInputQga(domain, protectedInputCommand("guest-file-open", arguments), deadline);
        if (!response.get("return").isJsonPrimitive() || !response.getAsJsonPrimitive("return").isNumber())
            throw new IllegalStateException("Protected stdin handle is unavailable");
        try {
            long handle = new java.math.BigDecimal(response.get("return").getAsString()).longValueExact();
            if (handle < 0) throw new IllegalStateException("Protected stdin handle is invalid");return handle;
        } catch (NumberFormatException | ArithmeticException invalid) {throw new IllegalStateException("Protected stdin handle is invalid");}
    }

    private void writeProtectedInputChunk(Domain domain, long handle, byte[] bytes, long deadline) throws LibvirtException {
        JsonObject arguments = new JsonObject();arguments.addProperty("handle", handle);
        arguments.addProperty("buf-b64", Base64.getEncoder().encodeToString(bytes));arguments.addProperty("count", bytes.length);
        JsonObject response = protectedInputQga(domain, protectedInputCommand("guest-file-write", arguments), deadline).getAsJsonObject("return");
        if (protectedInputInteger(response, "count") != bytes.length) throw new IllegalStateException("Protected stdin upload was partial");
    }

    private void flushProtectedInputHandle(Domain domain, long handle, long deadline) throws LibvirtException {
        JsonObject arguments = new JsonObject();arguments.addProperty("handle", handle);
        protectedInputQga(domain, protectedInputCommand("guest-file-flush", arguments), deadline);
    }

    private boolean closeProtectedInputHandle(Domain domain, long handle, long deadline) {
        try {
            JsonObject arguments = new JsonObject();arguments.addProperty("handle", handle);
            JsonObject response = protectedInputQga(domain, protectedInputCommand("guest-file-close", arguments),
                    Math.max(deadline, System.currentTimeMillis() + 1000));
            return response.get("return").isJsonObject() && response.getAsJsonObject("return").size() == 0;
        } catch (RuntimeException | LibvirtException unknown) {return false;}
    }

    protected long executeLargeProtectedInput(Domain domain, StorageServiceHostCommand command, byte[] payload)
            throws LibvirtException, InterruptedException {
        if (payload.length > PROTECTED_STDIN_MAX_BYTES || command.getTimeoutSeconds() <= PROTECTED_STDIN_CLEANUP_SECONDS)
            throw new IllegalArgumentException("Protected stdin payload or deadline exceeds budget");
        validateOperation(command.getOperation());
        final long started = System.currentTimeMillis();
        final long deadline = started + command.getTimeoutSeconds() * 1000L;
        final int receiverBudget = Math.min(command.getTimeoutSeconds(), 300);
        final long cleanupDeadline = Math.min(deadline, started + receiverBudget * 1000L);
        final long transferDeadline = cleanupDeadline - PROTECTED_STDIN_CLEANUP_SECONDS * 1000L;
        protectedStdinDeadline.set(deadline);
        JsonObject information = protectedInputQga(domain, protectedInputCommand("guest-info", new JsonObject()), transferDeadline).getAsJsonObject("return");
        java.util.Set<String> available = new java.util.HashSet<>();
        if (information.has("supported_commands") && information.get("supported_commands").isJsonArray()) {
            for (com.google.gson.JsonElement row : information.getAsJsonArray("supported_commands")) {
                if (row.isJsonObject()) {
                    JsonObject capability = row.getAsJsonObject();
                    if (capability.has("name") && capability.get("name").isJsonPrimitive() && capability.getAsJsonPrimitive("name").isString()
                            && capability.has("enabled") && capability.get("enabled").isJsonPrimitive()
                            && capability.getAsJsonPrimitive("enabled").isBoolean() && capability.get("enabled").getAsBoolean())
                        available.add(capability.get("name").getAsString());
                }
            }
        }
        if (!available.containsAll(java.util.Set.of("guest-file-open", "guest-file-write", "guest-file-flush", "guest-file-close",
                "guest-exec", "guest-exec-status"))) throw new IllegalStateException("Protected stdin chunk transport is unavailable");
        String checksum;
        try {
            StringBuilder hex = new StringBuilder();
            for (byte part : java.security.MessageDigest.getInstance("SHA-256").digest(payload)) hex.append(String.format("%02x", part));
            checksum = hex.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) {throw new IllegalStateException("Protected stdin checksum is unavailable");}
        JsonArray metadata = new JsonArray();metadata.add(Integer.toString(payload.length));metadata.add(checksum);
        metadata.add(Integer.toString(receiverBudget));metadata.add(command.getOperation());
        long pid = protectedInputInteger(protectedInputQga(domain, protectedInputExec(PROTECTED_STDIN_RECEIVER, metadata), transferDeadline)
                .getAsJsonObject("return"), "pid");
        if (pid == 0) throw new IllegalStateException("Protected stdin receiver PID is unavailable");
        JsonArray expectedArgv = new JsonArray();expectedArgv.add("/usr/bin/python3");expectedArgv.add("-c");expectedArgv.add(PROTECTED_STDIN_RECEIVER);
        for (com.google.gson.JsonElement value : metadata) expectedArgv.add(value.deepCopy());
        JsonObject owner = observeProtectedInputReceiver(domain, pid, expectedArgv, transferDeadline);requireProtectedInputOwner(owner, pid);
        Long handle = null;Long control = null;
        try {
            handle = openProtectedInputHandle(domain, pid, 16, transferDeadline);
            for (int offset = 0; offset < payload.length; offset += PROTECTED_STDIN_CHUNK_BYTES) {
                byte[] chunk = java.util.Arrays.copyOfRange(payload, offset, Math.min(payload.length, offset + PROTECTED_STDIN_CHUNK_BYTES));
                try {writeProtectedInputChunk(domain, handle, chunk, transferDeadline);}
                finally {java.util.Arrays.fill(chunk, (byte) 0);}
            }
            flushProtectedInputHandle(domain, handle, transferDeadline);
            boolean closed = closeProtectedInputHandle(domain, handle, cleanupDeadline);handle = null;
            if (!closed) throw new IllegalStateException("Protected stdin data close is unverified");
            JsonObject after = observeProtectedInputReceiver(domain, pid, expectedArgv, transferDeadline);requireProtectedInputOwner(after, pid);
            if (!owner.equals(after)) throw new IllegalStateException("Protected stdin receiver identity changed");
            control = openProtectedInputHandle(domain, pid, 18, transferDeadline);
            writeProtectedInputChunk(domain, control, new byte[]{1}, transferDeadline);
            flushProtectedInputHandle(domain, control, transferDeadline);
            boolean controlClosed = closeProtectedInputHandle(domain, control, cleanupDeadline);control = null;
            if (!controlClosed) throw new IllegalStateException("Protected stdin commit completion is unknown");
            return pid;
        } finally {
            if (handle != null) closeProtectedInputHandle(domain, handle, cleanupDeadline);
            if (control != null) closeProtectedInputHandle(domain, control, cleanupDeadline);
        }
    }

    protected void validateOperation(final String operation) {
        if (operation == null || !operation.matches("[A-Za-z0-9 ._-]+")) {
            throw new IllegalArgumentException("Invalid Storage Service operation: " + operation);
        }
    }
}
