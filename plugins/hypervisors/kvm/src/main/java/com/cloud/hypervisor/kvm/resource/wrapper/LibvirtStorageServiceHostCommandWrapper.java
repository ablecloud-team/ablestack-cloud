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
            "        os.fchmod(data,0o600);os.fchmod(control_write,0o600)",
            "        for fd in originals:safe.append(fcntl.fcntl(fd,fcntl.F_DUPFD_CLOEXEC,19))",
            "        for fd in originals:os.close(fd)",
            "        originals=[]",
            "        for source,target in zip(safe,(DATA_FD,CONTROL_READ_FD,CONTROL_WRITE_FD)):",
            "            os.dup2(source,target,inheritable=False)",
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
            "def run(expected_bytes,expected_sha256,seconds,operation,nonce):",
            "    require(type(nonce)is str and re.fullmatch(r'[0-9a-f]{32}',nonce)is not None)",
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
            "        require(len(sys.argv)==6)",
            "        expected_bytes=int(sys.argv[1]);expected_sha256=sys.argv[2];seconds=float(sys.argv[3]);operation=sys.argv[4];nonce=sys.argv[5]",
            "        require(type(operation)is str and re.fullmatch(r'[A-Za-z0-9 ._-]+',operation) is not None)",
            "        require(0<expected_bytes<=MAX_BYTES and 0<seconds<=300)",
            "        stage='receive-seal-exec';run(expected_bytes,expected_sha256,seconds,operation,nonce)",
            "    except BaseException:",
            "        # Never emit exception message, arguments, digest, payload or traceback.",
            "        sys.stderr.write('PROTECTED_RAM_INPUT_REJECTED\\n');return 1",
            "    return 0",
            "if __name__=='__main__':raise SystemExit(main())");

    private static final String PROTECTED_STDIN_OBSERVER = String.join("\n",
            "import hashlib,os,re,stat",
            "def observe(pid,expected_argv,expected_start=None,expected_nonce=None,expected_data_inode=None,expected_control_inode=None,expected_executable=None,expected_binary_device=None,expected_binary_inode=None):",
            "    if type(pid)is not int or pid<=0 or not isinstance(expected_argv,list)or not expected_argv:raise ValueError()",
            "    nonce=expected_argv[-1]if expected_nonce is None else expected_nonce",
            "    if type(nonce)is not str or re.fullmatch('[0-9a-f]{32}',nonce)is None or expected_argv[-1]!=nonce:raise ValueError()",
            "    if any(type(value)is not int or value<=0 for value in(expected_start,expected_data_inode,expected_control_inode)if value is not None):raise ValueError()",
            "    if expected_binary_device is not None and(type(expected_binary_device)is not int or expected_binary_device<0):raise ValueError()",
            "    if expected_binary_inode is not None and(type(expected_binary_inode)is not int or expected_binary_inode<=0):raise ValueError()",
            "    root=f'/proc/{pid}'",
            "    def identity():",
            "        before=os.stat(root)",
            "        if before.st_uid!=0:raise ValueError()",
            "        with open(root+'/stat','rb')as stream:record=stream.read(4096)",
            "        fields=record[record.rfind(b')')+2:].split()",
            "        if fields[0]in(b'Z',b'X',b'x'):raise ValueError()",
            "        start=int(fields[19])",
            "        if start<=0 or expected_start is not None and start!=expected_start:raise ValueError()",
            "        with open(root+'/cmdline','rb')as stream:raw=stream.read(32769)",
            "        if len(raw)>32768:raise ValueError()",
            "        argv=raw.rstrip(b'\\0').decode().split('\\0')",
            "        if argv!=expected_argv:raise ValueError()",
            "        executable=os.readlink(root+'/exe')",
            "        if executable not in('/usr/bin/python3.9','/usr/bin/python3.11','/usr/bin/python3.12'):raise ValueError()",
            "        if expected_executable is not None and executable!=expected_executable:raise ValueError()",
            "        binary=os.stat(root+'/exe')",
            "        if not stat.S_ISREG(binary.st_mode)or binary.st_uid!=0 or binary.st_mode&0o022:raise ValueError()",
            "        if expected_binary_device is not None and binary.st_dev!=expected_binary_device:raise ValueError()",
            "        if expected_binary_inode is not None and binary.st_ino!=expected_binary_inode:raise ValueError()",
            "        return start,executable,binary.st_dev,binary.st_ino",
            "    before=identity()",
            "    def descriptor(number,expected_inode):",
            "        path=root+'/fd/'+str(number)",
            "        try:meta=os.stat(path)",
            "        except FileNotFoundError:",
            "            if expected_inode is not None:raise ValueError()",
            "            return None",
            "        if meta.st_uid!=0 or stat.S_IMODE(meta.st_mode)!=0o600 or meta.st_ino<=0:raise ValueError()",
            "        target=os.readlink(path)",
            "        if number==16:",
            "            if not stat.S_ISREG(meta.st_mode)or target!='/memfd:ablestack-protected-input (deleted)':raise ValueError()",
            "        elif not stat.S_ISFIFO(meta.st_mode)or re.fullmatch(r'pipe:\\[\\d+\\]',target)is None:raise ValueError()",
            "        if expected_inode is not None and meta.st_ino!=expected_inode:raise ValueError()",
            "        return meta.st_ino",
            "    data_inode=descriptor(16,expected_data_inode);control_inode=descriptor(18,expected_control_inode)",
            "    if identity()!=before:raise ValueError()",
            "    ready=data_inode is not None and control_inode is not None",
            "    return {'ready':ready,'status':'READY'if ready else'NOT_READY','rootOwned':True,'receiverScriptVerified':True,",
            "        'pid':pid,'startTicks':before[0],'launchNonce':nonce,'dataInode':data_inode,'controlInode':control_inode,",
            "        'executablePath':before[1],'binaryDevice':before[2],'binaryInode':before[3]}",
            "",
            "import base64,json,sys",
            "try:",
            "    if len(sys.argv)!=10:raise ValueError()",
            "    value=observe(int(sys.argv[1]),json.loads(base64.b64decode(sys.argv[2])),int(sys.argv[3])or None,",
            "        sys.argv[4],int(sys.argv[5])or None,int(sys.argv[6])or None,sys.argv[7]or None,",
            "        int(sys.argv[8])if sys.argv[8]else None,int(sys.argv[9])if sys.argv[9]else None)",
            "    print(json.dumps(value,separators=(',',':')))",
            "except BaseException:",
            "    sys.stderr.write('PROTECTED_RAM_RECEIVER_REJECTED\\n');raise SystemExit(1)");

    private static final String CONFIGURE_SHAREDFS_STATIC_NETWORK = "configure-sharedfs-static-network";
    private static final String SHAREDFS_NETWORK_STATE = "/etc/ablestack-storage/sharedfs-network.json";
    private static final String SHAREDFS_NETWORK_HELPER = "/usr/local/sbin/ablestack-sharedfs-network";
    private static final String SHAREDFS_NETWORK_UNIT = "/etc/systemd/system/ablestack-sharedfs-network.service";
    private static final String SHAREDFS_RECONCILE_UNIT = "/etc/systemd/system/ablestack-storage-reconcile.service";
    private static final String SHAREDFS_DHCP_UNIT = "/etc/systemd/system/cloud-dhclient@.service";
    private static final String SHAREDFS_NETWORK_RESOURCE_ROOT = "/META-INF/ablestack/sharedfs-network/";


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
        final java.util.Set<String> guardStages = java.util.Set.of("INPUT", "VAULT_STATE", "DEPENDENCY", "LISTENER", "DEVICE", "AUTH", "READINESS");
        final java.util.Set<String> guardCategories = java.util.Set.of("VALUE_ERROR", "TYPE_ERROR", "RUNTIME_ERROR", "OS_ERROR", "TIMEOUT", "SYSTEM_EXIT", "LOOKUP_ERROR", "ATTRIBUTE_ERROR", "ASSERTION_ERROR");
        final java.util.Set<String> seen = new java.util.HashSet<>();
        String kind = null;
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
                        if (reader.peek() != JsonToken.STRING) return null;
                        kind = reader.nextString();
                        if (!java.util.Set.of("ISCSI_TARGETCLI_COMMAND_FAILED", "ISCSI_APPLY_GUARD_FAILED").contains(kind)) return null;
                        break;
                    case "stage":
                        if (reader.peek() != JsonToken.STRING) return null;
                        stage = reader.nextString();
                        break;
                    case "category":
                        if (reader.peek() != JsonToken.STRING) return null;
                        category = reader.nextString();
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
            if ("ISCSI_APPLY_GUARD_FAILED".equals(kind)) {
                if (!nullCode || !guardStages.contains(stage) || !guardCategories.contains(category)) return null;
                return "ISCSI guard=" + stage + "; returnCode=UNAVAILABLE; category=" + category;
            }
            if (!stages.contains(stage) || !categories.contains(category)
                    || nullCode != java.util.Set.of("TIMEOUT", "SPAWN_FAILURE").contains(category)) return null;
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
        final String encodedLegacyPatch = Base64.getEncoder().encodeToString(
                buildSharedFsLegacyMonitorPatch().getBytes(StandardCharsets.UTF_8));
        return "set -e; umask 077; install -d -m 0755 /etc/ablestack-storage; " +
                "printf '%s' '" + encodedLegacyPatch + "' | base64 -d | python3; " +
                installSharedFsNetworkFile(SHAREDFS_NETWORK_HELPER,
                        sharedFsNetworkResource("usr/local/sbin/ablestack-sharedfs-network"), "0755") +
                installSharedFsNetworkFile(SHAREDFS_NETWORK_UNIT,
                        sharedFsNetworkResource("etc/systemd/system/ablestack-sharedfs-network.service"), "0644") +
                installSharedFsNetworkFile(SHAREDFS_RECONCILE_UNIT,
                        sharedFsNetworkResource("etc/systemd/system/ablestack-storage-reconcile.service"), "0644") +
                installSharedFsNetworkFile(SHAREDFS_DHCP_UNIT,
                        sharedFsNetworkResource("etc/systemd/system/cloud-dhclient@.service"), "0644") +
                installSharedFsNetworkFile(SHAREDFS_NETWORK_STATE, payload.getBytes(StandardCharsets.UTF_8), "0600") +
                "systemctl daemon-reload; systemctl enable ablestack-sharedfs-network.service >/dev/null; " +
                "systemctl restart ablestack-sharedfs-network.service; systemctl --no-pager --full status ablestack-sharedfs-network.service >/dev/null; " +
                SHAREDFS_NETWORK_HELPER + " " + SHAREDFS_NETWORK_STATE;
    }

    private byte[] sharedFsNetworkResource(String path) {
        try (java.io.InputStream resource = getClass().getResourceAsStream(SHAREDFS_NETWORK_RESOURCE_ROOT + path)) {
            if (resource == null) {
                throw new IllegalStateException("Canonical SharedFS network resource is unavailable: " + path);
            }
            return resource.readAllBytes();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Unable to read canonical SharedFS network resource: " + path, failure);
        }
    }

    private String installSharedFsNetworkFile(String path, byte[] content, String mode) {
        String encoded = Base64.getEncoder().encodeToString(content);
        String validate = SHAREDFS_NETWORK_STATE.equals(path)
                ? "python3 -m json.tool " + path + ".tmp >/dev/null; " : "";
        return "printf '%s' '" + encoded + "' | base64 -d > " + path + ".tmp; " +
                validate + "chmod " + mode + " " + path + ".tmp; mv " + path + ".tmp " + path + "; ";
    }

    protected String buildSharedFsLegacyMonitorPatch() {
        return String.join("\n",
                "import os, stat, tempfile",
                "from pathlib import Path",
                "def rewrite_monitor_startup(raw):",
                "    old = 'systemctl enable --now ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "    new = 'systemctl enable ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "    unmask = 'systemctl unmask ablestack-storage-monitor.service >/dev/null 2>&1 || true'",
                "    lines = raw.decode('utf-8').splitlines(keepends=True)",
                "    if sum(line.strip() == unmask for line in lines) > 1:",
                "        raise ValueError('Duplicate SharedFS legacy monitor unmask')",
                "    matches = [index for index, line in enumerate(lines)",
                "               if not line.lstrip().startswith('#') and 'systemctl' in line",
                "               and 'ablestack-storage-monitor.service' in line and line.strip() != unmask]",
                "    if len(matches) != 1 or lines[matches[0]].strip() not in (old, new):",
                "        raise ValueError('SharedFS legacy monitor startup is unknown')",
                "    if lines[matches[0]].strip() == new:",
                "        return None",
                "    lines[matches[0]] = lines[matches[0]].replace(old, new)",
                "    return ''.join(lines).encode('utf-8')",
                "def rewrite_network_startup(raw):",
                "    old = b'    if [[ -s /etc/ablestack-storage/sharedfs-network.json ]]; then\\n      /usr/local/sbin/ablestack-sharedfs-network /etc/ablestack-storage/sharedfs-network.json\\n    else\\n      dhclient -4 -v -pf /run/dhclient.eth0.pid -lf /var/lib/dhcp/dhclient.eth0.leases eth0 || true\\n    fi\\n'",
                "    new = b'    # QGA remains available when a new L2 VM has no DHCP server yet.\\n    systemctl enable --now qemu-guest-agent >/dev/null 2>&1 || true\\n    /usr/local/sbin/ablestack-sharedfs-network --prepare || exit 1\\n'",
                "    if raw.count(old) == 1 and raw.count(new) == 0:",
                "        replacement = raw.replace(old, new, 1)",
                "    elif raw.count(old) == 0 and raw.count(new) == 1:",
                "        replacement = raw",
                "    else:",
                "        raise ValueError('SharedFS legacy network startup is unknown')",
                "    lines = [line.strip() for line in replacement.splitlines()",
                "             if line.strip() and not line.lstrip().startswith(b'#')]",
                "    helper = b'/usr/local/sbin/ablestack-sharedfs-network --prepare || exit 1'",
                "    if any(b'dhclient' in line or b'sharedfs-network.json' in line for line in lines):",
                "        raise ValueError('SharedFS legacy network writer remains')",
                "    if [line for line in lines if b'ablestack-sharedfs-network' in line] != [helper]:",
                "        raise ValueError('SharedFS network preparation is ambiguous')",
                "    return None if replacement == raw else replacement",
                "def rewrite_sharedfsvm_startup(raw):",
                "    network = rewrite_network_startup(raw)",
                "    prepared = raw if network is None else network",
                "    monitor = rewrite_monitor_startup(prepared)",
                "    replacement = prepared if monitor is None else monitor",
                "    return None if replacement == raw else replacement",
                "def rewrite_sharedfs_init(raw):",
                "    old = b'  elif [ \"$TYPE\" == \"sharedfsvm\" ]; then\\n    setup_common eth0\\n  fi\\n'",
                "    guarded = b'  elif [ \"$TYPE\" == \"sharedfsvm\" ]; then\\n    # The persisted STATIC declaration, including malformed state, never falls\\n    # back to the legacy cmdline gateway/DNS or DHCP configuration.\\n    if [ ! -e /etc/ablestack-storage/sharedfs-network.json ] && [ ! -L /etc/ablestack-storage/sharedfs-network.json ]; then\\n      setup_common eth0\\n    fi\\n  fi\\n'",
                "    new = b'  elif [ \"$TYPE\" == \"sharedfsvm\" ]; then\\n    # SharedFS networking has one MAC-selected owner. Preinit claims ifupdown\\n    # before network-pre.target without waiting for DHCP or STATIC injection.\\n    if [ ! -e /etc/ablestack-storage/sharedfs-network.json ] && [ ! -L /etc/ablestack-storage/sharedfs-network.json ]; then\\n      echo \"$NAME\" > /etc/hostname\\n      echo \\'AVAHI_DAEMON_DETECT_LOCAL=0\\' > /etc/default/avahi-daemon\\n      hostnamectl set-hostname \"$NAME\"\\n    fi\\n    /usr/local/sbin/ablestack-sharedfs-network --bootstrap-only || exit 1\\n  fi\\n'",
                "    marker = b'elif [ \"$TYPE\" == \"sharedfsvm\" ]; then'",
                "    if sum(line.strip() == marker for line in raw.splitlines()) != 1:",
                "        raise ValueError('SharedFS legacy init role is ambiguous')",
                "    matches = [block for block in (old, guarded, new) if raw.count(block)]",
                "    if len(matches) != 1 or raw.count(matches[0]) != 1:",
                "        raise ValueError('SharedFS legacy init role is unknown')",
                "    return None if matches[0] == new else raw.replace(matches[0], new, 1)",
                "def signature(value):",
                "    return (value.st_dev, value.st_ino, value.st_mode, value.st_uid, value.st_gid,",
                "            value.st_size, value.st_mtime_ns, value.st_ctime_ns)",
                "prepared = []",
                "for path, rewrite in (",
                "        (Path('/opt/cloud/bin/setup/sharedfsvm.sh'), rewrite_sharedfsvm_startup),",
                "        (Path('/opt/cloud/bin/setup/init.sh'), rewrite_sharedfs_init)):",
                "    before = path.lstat()",
                "    if not stat.S_ISREG(before.st_mode) or before.st_uid != 0 or before.st_mode & 0o022:",
                "        raise ValueError('SharedFS legacy setup identity rejected')",
                "    with path.open('rb') as stream:",
                "        if signature(os.fstat(stream.fileno())) != signature(before):",
                "            raise ValueError('SharedFS legacy setup identity changed')",
                "        raw = stream.read(1048577)",
                "        if len(raw) > 1048576 or signature(os.fstat(stream.fileno())) != signature(before):",
                "            raise ValueError('SharedFS legacy setup content rejected')",
                "    if signature(path.lstat()) != signature(before):",
                "        raise ValueError('SharedFS legacy setup identity changed')",
                "    prepared.append((path, before, rewrite(raw)))",
                "for path, before, replacement in prepared:",
                "    if signature(path.lstat()) != signature(before):",
                "        raise ValueError('SharedFS legacy setup identity changed')",
                "    if replacement is None:",
                "        continue",
                "    descriptor, temporary = tempfile.mkstemp(prefix='.sharedfs-network-', dir=str(path.parent))",
                "    try:",
                "        with os.fdopen(descriptor, 'wb') as stream:",
                "            os.fchmod(stream.fileno(), stat.S_IMODE(before.st_mode))",
                "            os.fchown(stream.fileno(), before.st_uid, before.st_gid)",
                "            stream.write(replacement)",
                "            stream.flush()",
                "            os.fsync(stream.fileno())",
                "        if signature(path.lstat()) != signature(before):",
                "            raise ValueError('SharedFS legacy setup identity changed')",
                "        os.replace(temporary, path)",
                "    finally:",
                "        if os.path.lexists(temporary):",
                "            os.unlink(temporary)",
                "");
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
        return observeProtectedInputReceiver(domain, pid, expectedArgv, deadline, null);
    }

    private JsonObject observeProtectedInputReceiver(Domain domain, long pid, JsonArray expectedArgv, long deadline, JsonObject previous)
            throws LibvirtException, InterruptedException {
        if (System.currentTimeMillis() >= deadline) throw new IllegalStateException("Protected stdin receiver ownership deadline expired");
        if (expectedArgv == null || expectedArgv.size() == 0) throw new IllegalStateException("Protected stdin receiver nonce is unavailable");
        String nonce = expectedArgv.get(expectedArgv.size() - 1).getAsString();
        if (!nonce.matches("[0-9a-f]{32}")) throw new IllegalStateException("Protected stdin receiver nonce is unavailable");
        JsonObject pins = new JsonObject();
        if (previous != null) {
            requireProtectedInputOwner(previous, pid);
            for (String name : new String[]{"startTicks", "dataInode", "controlInode", "executablePath", "binaryDevice", "binaryInode"}) pins.add(name, previous.get(name));
        }
        while (System.currentTimeMillis() < deadline) {
            JsonArray arguments = new JsonArray();arguments.add(Long.toString(pid));
            arguments.add(Base64.getEncoder().encodeToString(expectedArgv.toString().getBytes(StandardCharsets.UTF_8)));
            arguments.add(pins.has("startTicks") ? pins.get("startTicks").getAsString() : "0");arguments.add(nonce);
            arguments.add(pins.has("dataInode") ? pins.get("dataInode").getAsString() : "0");
            arguments.add(pins.has("controlInode") ? pins.get("controlInode").getAsString() : "0");
            arguments.add(pins.has("executablePath") ? pins.get("executablePath").getAsString() : "");
            arguments.add(pins.has("binaryDevice") ? pins.get("binaryDevice").getAsString() : "");
            arguments.add(pins.has("binaryInode") ? pins.get("binaryInode").getAsString() : "");
            long observerPid = protectedInputInteger(protectedInputQga(domain, protectedInputExec(PROTECTED_STDIN_OBSERVER, arguments), deadline)
                    .getAsJsonObject("return"), "pid");
            JsonObject observation = null;
            while (System.currentTimeMillis() < deadline) {
                JsonObject statusArgs = new JsonObject();statusArgs.addProperty("pid", observerPid);
                JsonObject status = protectedInputQga(domain, protectedInputCommand("guest-exec-status", statusArgs), deadline).getAsJsonObject("return");
                if (System.currentTimeMillis() >= deadline) throw new IllegalStateException("Protected stdin receiver ownership deadline expired");
                if (status.has("exited") && status.getAsJsonPrimitive("exited").isBoolean() && status.get("exited").getAsBoolean()) {
                    if (protectedInputInteger(status, "exitcode") != 0 || status.has("out-truncated") && status.get("out-truncated").getAsBoolean())
                        throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
                    observation = protectedInputObservation(decodeGuestData(status, "out-data"));
                    if (protectedInputInteger(observation, "pid") != pid || !nonce.equals(observation.get("launchNonce").getAsString()))
                        throw new IllegalStateException("Protected stdin receiver identity changed");
                    for (String name : new String[]{"startTicks", "dataInode", "controlInode", "executablePath", "binaryDevice", "binaryInode"}) {
                        if (pins.has(name) && (observation.get(name).isJsonNull() || !pins.get(name).equals(observation.get(name))))
                            throw new IllegalStateException("Protected stdin receiver identity changed");
                        if (!observation.get(name).isJsonNull()) pins.add(name, observation.get(name));
                    }
                    if (observation.get("ready").getAsBoolean()) {
                        if (System.currentTimeMillis() >= deadline) throw new IllegalStateException("Protected stdin receiver ownership deadline expired");
                        return observation;
                    }
                    break;
                }
                long innerWait = deadline - System.currentTimeMillis();
                if (innerWait <= 0) break;
                Thread.sleep(Math.min(QGA_POLL_INTERVAL_MILLIS, innerWait));
            }
            if (observation == null) break;
            // Only a successful closed NOT_READY proof reaches another observation.
            long retryWait = deadline - System.currentTimeMillis();
            if (retryWait <= 0) break;
            Thread.sleep(Math.min(QGA_POLL_INTERVAL_MILLIS, retryWait));
        }
        throw new IllegalStateException("Protected stdin receiver ownership deadline expired");
    }

    private JsonObject protectedInputObservation(String value) {
        if (value == null || value.length() > 4096) throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
        for (String field : new String[]{"ready", "rootOwned", "receiverScriptVerified"}) {
            if (!java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*(true|false)\\s*(?=[,}])").matcher(value).find())
                throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
        }
        java.util.Set<String> fields = java.util.Set.of("ready", "status", "rootOwned", "receiverScriptVerified", "pid", "startTicks",
                "launchNonce", "dataInode", "controlInode", "executablePath", "binaryDevice", "binaryInode");
        JsonObject result = new JsonObject();
        try (JsonReader reader = new JsonReader(new StringReader(value))) {
            reader.setLenient(false);reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.contains(name) || result.has(name)) throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
                if (java.util.Set.of("ready", "rootOwned", "receiverScriptVerified").contains(name)) {
                    if (reader.peek() != JsonToken.BOOLEAN) throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
                    result.addProperty(name, reader.nextBoolean());
                } else if ("status".equals(name) || "launchNonce".equals(name) || "executablePath".equals(name)) {
                    if (reader.peek() != JsonToken.STRING) throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
                    result.addProperty(name, reader.nextString());
                } else if (reader.peek() == JsonToken.NULL && ("dataInode".equals(name) || "controlInode".equals(name))) {
                    reader.nextNull();result.add(name, com.google.gson.JsonNull.INSTANCE);
                } else {
                    if (reader.peek() != JsonToken.NUMBER) throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
                    long number = new java.math.BigDecimal(reader.nextString()).longValueExact();
                    if (number < 0 || number == 0 && !"binaryDevice".equals(name)) throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
                    result.addProperty(name, number);
                }
            }
            reader.endObject();
            if (!result.keySet().equals(fields) || reader.peek() != JsonToken.END_DOCUMENT)
                throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
        } catch (java.io.IOException | NumberFormatException | ArithmeticException invalid) {
            throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
        }
        boolean ready = result.get("ready").getAsBoolean();
        if (!result.get("rootOwned").getAsBoolean() || !result.get("receiverScriptVerified").getAsBoolean()
                || !result.get("launchNonce").getAsString().matches("[0-9a-f]{32}")
                || !java.util.Set.of("/usr/bin/python3.9", "/usr/bin/python3.11", "/usr/bin/python3.12").contains(result.get("executablePath").getAsString())
                || !(ready ? "READY" : "NOT_READY").equals(result.get("status").getAsString())
                || ready != (!result.get("dataInode").isJsonNull() && !result.get("controlInode").isJsonNull()))
            throw new IllegalStateException("Protected stdin receiver ownership is unavailable");
        return result;
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
        metadata.add(java.util.UUID.randomUUID().toString().replace("-", ""));
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
            JsonObject after = observeProtectedInputReceiver(domain, pid, expectedArgv, transferDeadline, owner);requireProtectedInputOwner(after, pid);
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
