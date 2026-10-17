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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;

import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.DomainInfo.DomainState;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StorageServiceRuntimeFileType;
import com.cloud.agent.api.StorageServiceRuntimeHostAnswer;
import com.cloud.agent.api.StorageServiceRuntimeHostCommand;
import com.cloud.agent.api.StorageServiceRuntimeOperation;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

@ResourceWrapper(handles = StorageServiceRuntimeHostCommand.class)
public final class LibvirtStorageServiceRuntimeHostCommandWrapper extends CommandWrapper<StorageServiceRuntimeHostCommand, Answer, LibvirtComputingResource> {
    static final String UPDATER = "/usr/local/bin/ablestack-storage-runtime-updater";
    static final String UPDATER_MODULE = "/usr/local/lib/ablestack-storage/runtime_updater.py";
    static final String RUNTIME_STATE_ROOT = "/var/lib/ablestack-storage/runtime-updates";
    static final String TRUSTED_KEY_ROOT = "/opt/ablestack/storage-runtime/trusted-keys";
    private static final int QGA_POLL_INTERVAL_MILLIS = 250;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final String IDENTIFIER_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    @Override
    public Answer execute(final StorageServiceRuntimeHostCommand command, final LibvirtComputingResource resource) {
        Domain domain = null;
        try {
            validateCommand(command);
            final Connect connect = resource.getLibvirtUtilitiesHelper().getConnection();
            domain = resource.getDomain(connect, command.getVmName());
            if (domain == null) {
                return answer(command, false, "Storage Service System VM was not found: " + command.getVmName(), null);
            }
            if (domain.getInfo().state != DomainState.VIR_DOMAIN_RUNNING) {
                return answer(command, false, "Storage Service System VM is not running: " + command.getVmName(), null);
            }
            if (command.getOperation() == StorageServiceRuntimeOperation.WRITE_CHUNK) {
                return writeChunk(command, domain);
            }
            return executeUpdater(command, domain);
        } catch (final RuntimeException e) {
            return answer(command, false, e.getMessage(), null);
        } catch (final LibvirtException e) {
            return answer(command, false, "Failed to execute Storage Service runtime QGA command: " + e.getMessage(), null);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return answer(command, false, "Interrupted while waiting for Storage Service runtime QGA command", null);
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

    protected Answer writeChunk(final StorageServiceRuntimeHostCommand command, final Domain domain)
            throws LibvirtException, InterruptedException {
        final byte[] chunk = validateChunk(command);
        final String target = resolveGuestFile(command);
        final boolean bootstrap = command.getFileType()==StorageServiceRuntimeFileType.UPDATER_MODULE || command.getFileType()==StorageServiceRuntimeFileType.UPDATER_ENTRY || command.getFileType()==StorageServiceRuntimeFileType.TRUSTED_KEY;
        if (bootstrap) requireIdentifier(command.getTransactionId(),"transactionId");
        final String path = bootstrap ? target+"."+command.getTransactionId()+".incoming" : target;
        ensureDirectory(domain, parentPath(path), command.getTimeoutSeconds());
        if (bootstrap) {
            GuestExecResult prepared=executeGuest(domain,"/usr/bin/python3",bootstrapPrepareArguments(command,path),command.getTimeoutSeconds());
            if (prepared.exitCode!=0) throw new IllegalStateException("Protected runtime incoming file is not a regular root-owned staging file");
        }
        final long handle = openGuestFile(domain, path, command.isTruncate() ? "w+b" : "r+b", command.getTimeoutSeconds());
        try {
            seekGuestFile(domain, handle, command.getOffset(), command.getTimeoutSeconds());
            final int count = writeGuestFile(domain, handle, command.getChunkBase64(), command.getTimeoutSeconds());
            if (count != chunk.length) {
                throw new IllegalStateException("QGA guest-file-write returned an unexpected byte count");
            }
            flushGuestFile(domain, handle, command.getTimeoutSeconds());
        } finally {
            closeGuestFile(domain, handle, command.getTimeoutSeconds());
        }
        if (command.isFinalChunk()) {
            if (bootstrap) {
                final GuestExecResult installed=executeGuest(domain,"/usr/bin/python3",bootstrapInstallArguments(command,path,target),command.getTimeoutSeconds());
                if (installed.exitCode!=0) throw new IllegalStateException("Protected runtime bootstrap file did not pass full checksum and atomic installation");
            } else chmodFile(domain, path, fileMode(command.getFileType()), command.getTimeoutSeconds());
        }
        final JsonObject result = new JsonObject();
        result.addProperty("transactionId", command.getTransactionId());
        result.addProperty("fileType", command.getFileType().name());
        result.addProperty("offset", command.getOffset());
        result.addProperty("bytesWritten", chunk.length);
        result.addProperty("nextOffset", command.getOffset() + chunk.length);
        result.addProperty("chunkSha256", command.getChunkSha256().toLowerCase(Locale.ROOT));
        result.addProperty("complete", command.isFinalChunk());
        return answer(command, true, "Storage Service runtime chunk written", result.toString());
    }

    protected JsonArray bootstrapPrepareArguments(StorageServiceRuntimeHostCommand command,String staged) {
        JsonArray args=new JsonArray();args.add(new JsonPrimitive("-c"));
        args.add(new JsonPrimitive(String.join("\n","import os,sys,stat","p,tr=sys.argv[1:]",
                "flags=os.O_RDWR|os.O_NOFOLLOW","if tr=='true': flags|=os.O_CREAT|os.O_TRUNC",
                "f=os.open(p,flags,0o600)","try:"," s=os.fstat(f)",
                " if not stat.S_ISREG(s.st_mode) or s.st_uid!=0 or s.st_mode&0o022: raise ValueError('unprotected incoming file')",
                " os.fchmod(f,0o600)","finally: os.close(f)")));
        args.add(new JsonPrimitive(staged));args.add(new JsonPrimitive(Boolean.toString(command.isTruncate())));return args;
    }

    protected JsonArray bootstrapInstallArguments(StorageServiceRuntimeHostCommand command,String staged,String target) {
        hexToBytes(command.getFileSha256());
        JsonArray args=new JsonArray();args.add(new JsonPrimitive("-c"));
        args.add(new JsonPrimitive(String.join("\n",
                "import os,sys,stat,hashlib",
                "p,t,h,m=sys.argv[1:]",
                "f=os.open(p,os.O_RDONLY|os.O_NOFOLLOW)",
                "try:",
                " s=os.fstat(f)",
                " if not stat.S_ISREG(s.st_mode) or s.st_uid!=0: raise ValueError('unprotected bootstrap file')",
                " d=hashlib.sha256(); total=0",
                " while True:",
                "  data=os.read(f,65536)",
                "  if not data: break",
                "  total+=len(data)",
                "  if total>64*1024*1024: raise ValueError('bootstrap too large')",
                "  d.update(data)",
                " if d.hexdigest()!=h: raise ValueError('bootstrap checksum mismatch')",
                " z=os.lstat(p)",
                " if (z.st_dev,z.st_ino)!=(s.st_dev,s.st_ino): raise ValueError('bootstrap identity changed')",
                " os.fchmod(f,int(m,8)); os.fsync(f)",
                "finally: os.close(f)",
                "os.replace(p,t)",
                "q=os.open(os.path.dirname(t),os.O_RDONLY|os.O_DIRECTORY)",
                "try: os.fsync(q)",
                "finally: os.close(q)")));
        args.add(new JsonPrimitive(staged));args.add(new JsonPrimitive(target));args.add(new JsonPrimitive(command.getFileSha256().toLowerCase(Locale.ROOT)));args.add(new JsonPrimitive(fileMode(command.getFileType())));return args;
    }

    protected Answer executeUpdater(final StorageServiceRuntimeHostCommand command, final Domain domain)
            throws LibvirtException, InterruptedException {
        String requestPath = null;
        if (command.getRequestJson() != null && !command.getRequestJson().trim().isEmpty()) {
            final byte[] request = command.getRequestJson().getBytes(StandardCharsets.UTF_8);
            if (request.length > MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("Storage Service runtime updater request is too large");
            }
            requireIdentifier(command.getTransactionId(), "transactionId");
            requestPath = "/tmp/ablestack-storage-runtime-" + command.getTransactionId() + "-" +
                    command.getOperation().name().toLowerCase(Locale.ROOT) + ".json";
            writeSmallGuestFile(domain, requestPath, request, command.getTimeoutSeconds());
        }
        try {
            final JsonArray args = new JsonArray();
            args.add(new JsonPrimitive(command.getOperation().name().toLowerCase(Locale.ROOT)));
            if (requestPath != null) {
                args.add(new JsonPrimitive("--request"));
                args.add(new JsonPrimitive(requestPath));
            }
            final GuestExecResult result = executeGuest(domain, UPDATER, args, command.getTimeoutSeconds());
            final String details = result.exitCode == 0 ? "Storage Service runtime updater completed" :
                    "Storage Service runtime updater failed with exit code " + result.exitCode + ": " + result.stderr;
            return answer(command, result.exitCode == 0, details, result.stdout);
        } finally {
            if (requestPath != null) {
                removeFile(domain, requestPath, command.getTimeoutSeconds());
            }
        }
    }

    protected byte[] validateChunk(final StorageServiceRuntimeHostCommand command) {
        if (command.getFileType() == null) {
            throw new IllegalArgumentException("Storage Service runtime file type is required");
        }
        if (command.getOffset() < 0 || command.getRawLength() <= 0 ||
                command.getRawLength() > StorageServiceRuntimeHostCommand.MAX_RAW_CHUNK_BYTES) {
            throw new IllegalArgumentException("Storage Service runtime chunk offset or length is invalid");
        }
        if (command.isTruncate() && command.getOffset() != 0) {
            throw new IllegalArgumentException("Storage Service runtime truncate is allowed only at offset zero");
        }
        final byte[] chunk;
        try {
            chunk = Base64.getDecoder().decode(command.getChunkBase64());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Storage Service runtime chunk is not valid Base64", e);
        }
        if (chunk.length != command.getRawLength()) {
            throw new IllegalArgumentException("Storage Service runtime chunk length differs from the declared length");
        }
        final byte[] declared;
        try {
            declared = hexToBytes(command.getChunkSha256());
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Storage Service runtime chunk SHA-256 is invalid", e);
        }
        if (!MessageDigest.isEqual(declared, sha256(chunk))) {
            throw new IllegalArgumentException("Storage Service runtime chunk SHA-256 does not match");
        }
        return chunk;
    }

    protected String resolveGuestFile(final StorageServiceRuntimeHostCommand command) {
        switch (command.getFileType()) {
            case BUNDLE:
                return transactionPath(command, "bundle.tar.gz");
            case MANIFEST:
                return transactionPath(command, "manifest.json");
            case SIGNATURE:
                return transactionPath(command, "manifest.sig");
            case UPDATER_ENTRY:
                return UPDATER;
            case UPDATER_MODULE:
                return UPDATER_MODULE;
            case TRUSTED_KEY:
                requireIdentifier(command.getKeyId(), "keyId");
                return TRUSTED_KEY_ROOT + "/" + command.getKeyId() + ".pem";
            default:
                throw new IllegalArgumentException("Unsupported Storage Service runtime file type");
        }
    }

    protected String transactionPath(final StorageServiceRuntimeHostCommand command, final String name) {
        requireIdentifier(command.getTransactionId(), "transactionId");
        return RUNTIME_STATE_ROOT + "/" + command.getTransactionId() + "/" + name;
    }

    protected JsonObject buildGuestFileOpenCommand(final String path, final String mode) {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("path", path);
        arguments.addProperty("mode", mode);
        return qgaCommand("guest-file-open", arguments);
    }

    protected JsonObject buildGuestFileSeekCommand(final long handle, final long offset) {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("handle", handle);
        arguments.addProperty("offset", offset);
        arguments.addProperty("whence", 0);
        return qgaCommand("guest-file-seek", arguments);
    }

    protected JsonObject buildGuestFileWriteCommand(final long handle, final String chunkBase64) {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("handle", handle);
        arguments.addProperty("buf-b64", chunkBase64);
        return qgaCommand("guest-file-write", arguments);
    }

    protected JsonObject buildUpdaterGuestExecCommand(final StorageServiceRuntimeHostCommand command, final String requestPath) {
        final JsonArray args = new JsonArray();
        args.add(new JsonPrimitive(command.getOperation().name().toLowerCase(Locale.ROOT)));
        if (requestPath != null) {
            args.add(new JsonPrimitive("--request"));
            args.add(new JsonPrimitive(requestPath));
        }
        return buildGuestExecCommand(UPDATER, args);
    }

    private void validateCommand(final StorageServiceRuntimeHostCommand command) {
        if (command.getOperation() == null) {
            throw new IllegalArgumentException("Storage Service runtime operation is required");
        }
        if (command.getTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException("Storage Service runtime timeout must be positive");
        }
    }

    private void requireIdentifier(final String value, final String field) {
        if (value == null || !value.matches(IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException("Invalid Storage Service runtime " + field);
        }
    }

    private String parentPath(final String path) {
        final int separator = path.lastIndexOf('/');
        return separator <= 0 ? "/" : path.substring(0, separator);
    }

    private String fileMode(final StorageServiceRuntimeFileType type) {
        return type == StorageServiceRuntimeFileType.UPDATER_ENTRY ? "0755" : "0600";
    }

    private void ensureDirectory(final Domain domain, final String path, final int timeout)
            throws LibvirtException, InterruptedException {
        final JsonArray args = new JsonArray();
        args.add(new JsonPrimitive("-d"));
        args.add(new JsonPrimitive("-m"));
        args.add(new JsonPrimitive("0700"));
        args.add(new JsonPrimitive(path));
        final GuestExecResult result = executeGuest(domain, "/usr/bin/install", args, timeout);
        if (result.exitCode != 0) {
            throw new IllegalStateException("Unable to create Storage Service runtime guest directory: " + result.stderr);
        }
    }

    private void chmodFile(final Domain domain, final String path, final String mode, final int timeout)
            throws LibvirtException, InterruptedException {
        final JsonArray args = new JsonArray();
        args.add(new JsonPrimitive(mode));
        args.add(new JsonPrimitive(path));
        final GuestExecResult result = executeGuest(domain, "/bin/chmod", args, timeout);
        if (result.exitCode != 0) {
            throw new IllegalStateException("Unable to set Storage Service runtime guest file mode: " + result.stderr);
        }
    }

    private void removeFile(final Domain domain, final String path, final int timeout)
            throws LibvirtException, InterruptedException {
        final JsonArray args = new JsonArray();
        args.add(new JsonPrimitive("-f"));
        args.add(new JsonPrimitive(path));
        executeGuest(domain, "/bin/rm", args, timeout);
    }

    private void writeSmallGuestFile(final Domain domain, final String path, final byte[] value, final int timeout)
            throws LibvirtException {
        final long handle = openGuestFile(domain, path, "w+b", timeout);
        try {
            final int count = writeGuestFile(domain, handle, Base64.getEncoder().encodeToString(value), timeout);
            if (count != value.length) {
                throw new IllegalStateException("QGA request file write returned an unexpected byte count");
            }
            flushGuestFile(domain, handle, timeout);
        } finally {
            closeGuestFile(domain, handle, timeout);
        }
    }

    private long openGuestFile(final Domain domain, final String path, final String mode, final int timeout) throws LibvirtException {
        final JsonObject response = qga(domain, buildGuestFileOpenCommand(path, mode), timeout);
        return response.get("return").getAsLong();
    }

    private void seekGuestFile(final Domain domain, final long handle, final long offset, final int timeout) throws LibvirtException {
        qga(domain, buildGuestFileSeekCommand(handle, offset), timeout);
    }

    private int writeGuestFile(final Domain domain, final long handle, final String data, final int timeout) throws LibvirtException {
        final JsonObject response = qga(domain, buildGuestFileWriteCommand(handle, data), timeout);
        final JsonObject result = response.getAsJsonObject("return");
        return result != null && result.has("count") ? result.get("count").getAsInt() : 0;
    }

    private void flushGuestFile(final Domain domain, final long handle, final int timeout) throws LibvirtException {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("handle", handle);
        qga(domain, qgaCommand("guest-file-flush", arguments), timeout);
    }

    private void closeGuestFile(final Domain domain, final long handle, final int timeout) throws LibvirtException {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("handle", handle);
        qga(domain, qgaCommand("guest-file-close", arguments), timeout);
    }

    private JsonObject qga(final Domain domain, final JsonObject command, final int timeout) throws LibvirtException {
        final String raw = domain.qemuAgentCommand(command.toString(), Math.max(timeout, 1), 0);
        final JsonObject response = new JsonParser().parse(raw).getAsJsonObject();
        if (response.has("error")) {
            throw new IllegalStateException("QGA command failed: " + response.get("error"));
        }
        if (!response.has("return")) {
            throw new IllegalStateException("QGA command returned no result: " + raw);
        }
        return response;
    }

    private GuestExecResult executeGuest(final Domain domain, final String path, final JsonArray args, final int timeout)
            throws LibvirtException, InterruptedException {
        final JsonObject response = qga(domain, buildGuestExecCommand(path, args), timeout);
        final JsonObject result = response.getAsJsonObject("return");
        if (result == null || !result.has("pid")) {
            throw new IllegalStateException("QGA guest-exec did not return a pid");
        }
        return waitForGuestExec(domain, result.get("pid").getAsLong(), timeout);
    }

    private JsonObject buildGuestExecCommand(final String path, final JsonArray args) {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("path", path);
        arguments.add("arg", args);
        arguments.addProperty("capture-output", true);
        return qgaCommand("guest-exec", arguments);
    }

    private GuestExecResult waitForGuestExec(final Domain domain, final long pid, final int timeout)
            throws LibvirtException, InterruptedException {
        final long deadline = System.currentTimeMillis() + timeout * 1000L;
        while (System.currentTimeMillis() < deadline) {
            final JsonObject arguments = new JsonObject();
            arguments.addProperty("pid", pid);
            final JsonObject response = qga(domain, qgaCommand("guest-exec-status", arguments), timeout);
            final JsonObject result = response.getAsJsonObject("return");
            if (result != null && result.has("exited") && result.get("exited").getAsBoolean()) {
                final int exitCode = result.has("exitcode") ? result.get("exitcode").getAsInt() : 1;
                return new GuestExecResult(exitCode, decodeGuestData(result, "out-data"), decodeGuestData(result, "err-data"));
            }
            Thread.sleep(QGA_POLL_INTERVAL_MILLIS);
        }
        throw new IllegalStateException("Timed out waiting for Storage Service runtime guest command");
    }

    private JsonObject qgaCommand(final String operation, final JsonObject arguments) {
        final JsonObject command = new JsonObject();
        command.addProperty("execute", operation);
        command.add("arguments", arguments);
        return command;
    }

    private String decodeGuestData(final JsonObject response, final String field) {
        if (!response.has(field) || response.get(field).isJsonNull()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(response.get(field).getAsString()), StandardCharsets.UTF_8);
    }

    private byte[] sha256(final byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private byte[] hexToBytes(final String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("invalid SHA-256");
        }
        final byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private StorageServiceRuntimeHostAnswer answer(final StorageServiceRuntimeHostCommand command,
            final boolean result, final String details, final String resultJson) {
        return new StorageServiceRuntimeHostAnswer(command, result, details, resultJson);
    }

    protected static final class GuestExecResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        private GuestExecResult(final int exitCode, final String stdout, final String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
