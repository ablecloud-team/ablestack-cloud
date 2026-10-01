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
// specific language governing permissions and limitations
// under the License.
//

package com.cloud.hypervisor.kvm.resource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.libvirt.Connect;
import org.libvirt.Domain;
import com.google.gson.Gson;

/** Cross-process flock contract with hangctl. Never unlink lock files. */
public final class KvmVmOperationGuard implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(KvmVmOperationGuard.class);
    private static final Path ROOT = Paths.get(System.getProperty("cloud.vm.operation.root", "/run/ablestack-vm-operations"));
    private static final ScheduledThreadPoolExecutor RENEWER = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "vm-operation-lease"); t.setDaemon(true); return t;
    });
    static { RENEWER.setRemoveOnCancelPolicy(true); }
    // A killed child can remain in uninterruptible kernel I/O. Never replace it
    // with unbounded new workers: retain its admission until it actually exits.
    private static final Set<Process> CHILDREN = ConcurrentHashMap.newKeySet();
    private static final Semaphore PROBES = new Semaphore(8);
    private static synchronized boolean admitProbe() {
        CHILDREN.removeIf(child -> {
            if (child.isAlive()) return false;
            PROBES.release();
            return true;
        });
        return PROBES.tryAcquire();
    }
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private final Process lock;
    private final Path lease;
    private final Map<String, Object> record = new LinkedHashMap<>();
    private ScheduledFuture<?> renewal;
    private volatile boolean uncertain;
    private boolean closed;

    private static void directory(Path path) throws IOException {
        try { Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        catch (FileAlreadyExistsException ignored) { }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path).equals(Files.getOwner(Paths.get("/proc/self")))) throw new IOException("Unsafe operation directory");
        if (Files.getPosixFilePermissions(path).stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IOException("Operation directory must be private");
    }

    KvmVmOperationGuard(Path root, String uuid, String kind, boolean monitoring) throws IOException {
        this(root, uuid, kind, monitoring, 0);
    }

    KvmVmOperationGuard(Path root, String uuid, String kind, boolean monitoring, long monitorWaitMs) throws IOException {
        if (monitorWaitMs < 0 || monitorWaitMs > 1000) throw new IOException("Invalid monitoring lock budget");
        if (!UUID.fromString(uuid).toString().equals(uuid)) throw new IOException("Non-canonical VM UUID");
        directory(root);
        Path locks = root.resolve("locks"); directory(locks);
        Path lockFile = locks.resolve(uuid + ".lock");
        if (Files.isSymbolicLink(lockFile)) throw new IOException("Symlink lock refused");
        // flock, not java.nio FileLock (POSIX record locks are a different lock namespace).
        lock = new ProcessBuilder("flock", "-x", "-w", monitoring ? Double.toString(monitorWaitMs / 1000.0) : "5", lockFile.toString(),
                "sh", "-c", "printf 'READY\n'; cat >/dev/null").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long readyDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(monitoring ? monitorWaitMs + 1000 : 6000);
            while (lock.isAlive() && lock.getInputStream().available() == 0 && System.nanoTime() < readyDeadline) {
                Thread.sleep(10);
            }
            if (lock.getInputStream().available() == 0) throw new IOException("VM operation lock busy or readiness timeout");
            String ready = new BufferedReader(new InputStreamReader(lock.getInputStream())).readLine();
            if (!"READY".equals(ready)) throw new IOException("VM operation lock busy");
            Path vmDir = root.resolve(uuid); directory(vmDir);
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(vmDir)) {
                if (entries.iterator().hasNext()) throw new IOException("Unreconciled operation lease; observation unknown");
            }
            lease = monitoring ? null : vmDir.resolve(UUID.randomUUID() + ".json");
            if (lease != null) {
                record.put("schemaVersion", 1); record.put("vmUuid", uuid);
                record.put("operationId", lease.getFileName().toString().replace(".json", ""));
                record.put("generation", UUID.randomUUID().toString()); record.put("operationKind", kind);
                record.put("cloudJobId", org.apache.logging.log4j.ThreadContext.get("jobid"));
                record.put("ownerPid", ProcessHandle.current().pid());
                record.put("ownerStartTime", ProcessHandle.current().info().startInstant().map(Object::toString).orElse("unknown"));
                record.put("bootId", Files.readString(Paths.get("/proc/sys/kernel/random/boot_id")).trim());
                record.put("startedAt", System.currentTimeMillis());
                writeLease();
                renewal = RENEWER.scheduleWithFixedDelay(() -> {
                    try { writeLease(); } catch (IOException e) { uncertain = true; LOG.error("Lease renewal failed for {}", uuid, e); }
                }, 5, 5, TimeUnit.SECONDS);
                LOG.info("VM operation acquire vmUuid={} kind={} operationId={}", uuid, kind, record.get("operationId"));
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            releaseProcess(lock);
            throw new IOException("Cannot protect VM " + uuid + ": " + e.getMessage(), e);
        }
    }

    private synchronized void writeLease() throws IOException {
        if (closed) return;
        record.put("renewedAt", System.currentTimeMillis()); record.put("expiresAt", System.currentTimeMillis() + 30000);
        Path tmp = lease.resolveSibling(lease.getFileName() + ".tmp");
        Files.writeString(tmp, new Gson().toJson(record), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, lease, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static KvmVmOperationGuard begin(Domain domain, String kind) {
        try { return new KvmVmOperationGuard(ROOT, domain.getUUIDString(), kind, false); }
        catch (Exception e) { throw new com.cloud.utils.exception.CloudRuntimeException("VM protection unavailable", e); }
    }

    /** Keep an unresolved lease after ambiguous native operation failure. */
    public void uncertain() { uncertain = true; }

    @Override public synchronized void close() {
        closed = true;
        if (renewal != null) renewal.cancel(false);
        try {
            if (lease != null && !uncertain) Files.deleteIfExists(lease);
            if (lease != null) LOG.info("VM operation release vmUuid={} kind={} uncertain={}", record.get("vmUuid"), record.get("operationKind"), uncertain);
        } catch (IOException e) { LOG.error("Operation lease cleanup failed; protection retained", e); }
        finally { releaseProcess(lock); }
    }

    private static void releaseProcess(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            if (!process.waitFor(200, TimeUnit.MILLISECONDS)) terminate(process);
        } catch (InterruptedException e) {
            interrupted = true;
            terminate(process);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (process.isAlive() && System.nanoTime() < end) {
                try { process.waitFor(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (process.isAlive()) LOG.error("Child process did not exit after SIGKILL pid={}", process.pid());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Synchronous bounded child, no executor queue, stream reader thread, or abandoned Future. */
    static String probe(long timeoutMs, String... command) throws IOException, InterruptedException {
        Long deadline = DEADLINE.get();
        if (deadline != null) timeoutMs = Math.min(timeoutMs, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        if (timeoutMs <= 0) throw new IOException("Collection budget exceeded");
        if (!admitProbe()) throw new IOException("Monitoring process capacity exhausted");
        Path output = null;
        Process process = null;
        try {
            output = Files.createTempFile("cloud-monitor-", ".out");
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().put("LC_ALL", "C");
            process = builder.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) throw new IOException("Probe timeout");
            if (process.exitValue() != 0 || Files.size(output) > 1024 * 1024) throw new IOException("Probe failed or output too large");
            return Files.readString(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) {
                terminate(process);
            }
            if (process != null && process.isAlive()) CHILDREN.add(process);
            else PROBES.release();
            if (output != null) Files.deleteIfExists(output);
        }
    }

    /** Agent-owned read context: one inherited flock, live stdin pipe and shared monitoring admission. */
    public static String processSnapshot(String uuid, String requestJson) throws IOException, InterruptedException {
        if (!UUID.fromString(uuid).toString().equals(uuid) || requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw new IOException("Invalid process request");
        if (!admitProbe()) throw new IOException("Monitoring capacity exhausted");
        Process child = null; Path request = null;
        try {
            directory(ROOT); Path locks = ROOT.resolve("locks"); directory(locks);
            Path lockFile = locks.resolve(uuid + ".lock");
            try { Files.createFile(lockFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
            catch (FileAlreadyExistsException ignored) { }
            if (Files.isSymbolicLink(lockFile) || !Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getOwner(lockFile).equals(Files.getOwner(Paths.get("/proc/self")))
                    || Files.getPosixFilePermissions(lockFile).stream().anyMatch(p -> p.name().equals("GROUP_WRITE") || p.name().equals("OTHERS_WRITE")))
                throw new IOException("Unsafe process guard lock");
            request = Files.createTempFile(ROOT, "process-read-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(request, requestJson, java.nio.charset.StandardCharsets.UTF_8);
            // Fixed shell program; validated paths are positional argv, never interpolated as shell source.
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c",
                    "exec 9<>\"$1\"; flock -n 9 || exit 3; exec /usr/bin/vm_exec --process-protocol 1.0 --request-json \"$2\" --cloud-read-guard-fd 9",
                    "process-read", lockFile.toString(), request.toString());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD); builder.environment().put("LC_ALL", "C");
            child = builder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            java.io.InputStream stream = child.getInputStream(); byte[] buffer = new byte[8192];
            while (child.isAlive() || stream.available() > 0) {
                if (System.nanoTime() > deadline) throw new IOException("Process snapshot deadline exceeded");
                int available = stream.available();
                if (available > 0) {
                    int count = stream.read(buffer, 0, Math.min(buffer.length, available));
                    if (count > 0) { if (output.size() + count > 1048576) throw new IOException("Process output limit"); output.write(buffer, 0, count); }
                } else Thread.sleep(5);
            }
            if (child.exitValue() != 0) throw new IOException("Process helper failed");
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString();
        } finally {
            if (child != null && child.isAlive()) terminate(child);
            if (child != null && child.isAlive()) CHILDREN.add(child); else PROBES.release();
            if (child != null) try { child.getOutputStream().close(); } catch (IOException ignored) { }
            if (request != null) Files.deleteIfExists(request);
        }
    }

    /** C5 row fence is held by management; inherit a host flock and live Agent channel. */
    public static String processAction(String uuid,String requestJson,boolean query) throws IOException,InterruptedException {
        return processAction(ROOT, uuid, requestJson, query);
    }

    static String processAction(Path root,String uuid,String requestJson,boolean query) throws IOException,InterruptedException {
        Map<String,Object> request=com.cloud.agent.api.VmProcessAction.parse(requestJson);
        if(!UUID.fromString(uuid).toString().equals(uuid) || !uuid.equals(com.cloud.agent.api.VmProcessSnapshot.map(request.get("authority")).get("vmUuid")))throw new IOException("Action authority");
        if(!admitProbe())throw new IOException("Process capacity exhausted");
        Process child=null;Path input=null,context=null;
        try {
            directory(root);Path locks=root.resolve("locks");directory(locks);Path lockFile=locks.resolve(uuid+".lock");
            try {Files.createFile(lockFile,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));}catch(FileAlreadyExistsException ignored){}
            if(Files.isSymbolicLink(lockFile) || !Files.isRegularFile(lockFile,LinkOption.NOFOLLOW_LINKS) || !Files.getOwner(lockFile).equals(Files.getOwner(Paths.get("/proc/self"))))throw new IOException("Unsafe lock");
            input=Files.createTempFile(root,"process-action-",".json",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(input,requestJson);
            long budget=query?12000:90000;
            ProcessBuilder builder;
            if(query)builder=new ProcessBuilder("/usr/bin/vm_exec","--process-protocol","1.0","--request-json",input.toString());
            else {
                Map<String,Object> reservation=new LinkedHashMap<>();
                for(String k:java.util.List.of("schemaVersion","authority","requestId","operationId"))reservation.put(k,request.get(k));
                reservation.put("lifecycleFenceHeld",true);reservation.put("ownerPid",ProcessHandle.current().pid());
                String stat=Files.readString(Paths.get("/proc/self/stat"));reservation.put("ownerStartTicks",stat.substring(stat.lastIndexOf(')')+2).split(" ")[19]);
                reservation.put("hostBootId",Files.readString(Paths.get("/proc/sys/kernel/random/boot_id")).trim());
                reservation.put("expiresMonotonicNs",Long.toString(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget)));
                context=Files.createTempFile(root,"process-reservation-",".json",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(context,new com.google.gson.GsonBuilder().serializeNulls().create().toJson(reservation));
                builder=new ProcessBuilder("/bin/sh","-c",
                    "exec 9<>\"$1\"; flock -n 9 || { printf '%s' \"$CLOUD_PROCESS_REJECTED\"; exit 0; }; exec /usr/bin/vm_exec --process-protocol 1.0 --request-json \"$2\" --cloud-action-context \"$3\" --cloud-action-guard-fd 9",
                    "process-action",lockFile.toString(),input.toString(),context.toString());
                // This response is emitted only before exec: no guest mutation has started.
                // A helper error, timeout or lost response still remains UNKNOWN.
                Map<String,Object> rejected=new LinkedHashMap<>();
                for(String k:java.util.List.of("schemaVersion","requestId","authority"))rejected.put(k,request.get(k));
                rejected.put("kind","failure");
                rejected.put("error",Map.of("code","BUSY","message","Host VM operation lock unavailable before dispatch","retryMode","NONE"));
                builder.environment().put("CLOUD_PROCESS_REJECTED",new com.google.gson.GsonBuilder().serializeNulls().create().toJson(rejected));
            }
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);builder.environment().put("LC_ALL","C");child=builder.start();
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(budget+1000);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
            java.io.InputStream stream=child.getInputStream();byte[] buffer=new byte[4096];
            while(child.isAlive() || stream.available()>0){
                if(System.nanoTime()>deadline)throw new IOException("Action transport deadline");
                int available=stream.available();
                if(available>0){int count=stream.read(buffer,0,Math.min(buffer.length,available));if(count>0){if(output.size()+count>65536)throw new IOException("Action output limit");output.write(buffer,0,count);}}
                else Thread.sleep(5);
            }
            if(child.exitValue()!=0)throw new IOException("Action helper unavailable: exit="+child.exitValue());
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString();
        } finally {
            if(child!=null && child.isAlive())terminate(child);
            if(child!=null && child.isAlive())CHILDREN.add(child);else PROBES.release();
            if(child!=null)try{child.getOutputStream().close();}catch(IOException ignored){}
            if(input!=null)Files.deleteIfExists(input);if(context!=null)Files.deleteIfExists(context);
        }
    }

    public static String guestCommand(Domain domain, String command, int seconds) {
        try { return probe(Math.max(1, seconds) * 1000L, "virsh", "-c", "qemu:///system", "qemu-agent-command",
                domain.getUUIDString(), "--timeout", Integer.toString(Math.max(1, seconds)), command); }
        catch (Exception e) { throw new com.cloud.utils.exception.CloudRuntimeException("Guest observation unavailable", e); }
    }

    public static <T> T collect(Connect conn, String name, Callable<T> task) {
        return collect(conn, name, task, Long.getLong("cloud.vm.monitor.budget.ms", 5000L));
    }

    /** Readiness also loads Windows native adapters; retain a bounded budget without changing ordinary monitoring. */
    public static <T> T collect(Connect conn, String name, Callable<T> task, long budgetMs) {
        return collect(conn, name, task, budgetMs, 0);
    }

    static <T> T collect(Connect conn, String name, Callable<T> task, long budgetMs, long monitorWaitMs) {
        try {
            if (budgetMs < 1) return null;
            DEADLINE.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs));
                String safeUuid = probe(1000, "virsh", "-c", "qemu:///system", "domuuid", name).trim();
                try (KvmVmOperationGuard guard = new KvmVmOperationGuard(ROOT, safeUuid, "monitoring", true, Math.min(monitorWaitMs, budgetMs))) {
                    // External virsh jobs do not have Cloud leases. Failure is UNKNOWN, never IDLE.
                    String job = probe(1000, "virsh", "-c", "qemu:///system", "domjobinfo", safeUuid);
                    if (job == null || !job.trim().matches("Job type:\\s+None")) {
                        LOG.info("VM monitoring skipped vm={} reason=DOMAIN_JOB_ACTIVE_OR_UNKNOWN", name); return null;
                    }
                    if (!probe(1000, "virsh", "-c", "qemu:///system", "domstate", safeUuid).trim().equals("running")) return null;
                    LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
                    parser.parseDomainXML(probe(1000, "virsh", "-c", "qemu:///system", "dumpxml", safeUuid));
                    for (LibvirtVMDef.DiskDef disk : parser.getDisks()) {
                        if (disk.getDeviceType() != LibvirtVMDef.DiskDef.DeviceType.DISK) continue;
                        String block = probe(1000, "virsh", "-c", "qemu:///system", "blockjob", safeUuid, disk.getDiskLabel(), "--info");
                        if (!block.trim().equals("No current block job for " + disk.getDiskLabel())) {
                            LOG.info("VM monitoring skipped vm={} reason=BLOCK_JOB_ACTIVE_OR_UNKNOWN disk={}", name, disk.getDiskLabel()); return null;
                        }
                    }
                    return task.call();
                }

        } catch (Exception e) {
            LOG.info("VM monitoring skipped vm={} reason=BUSY_UNKNOWN_OR_BUDGET detail={}", name, e.toString());
            return null;
        } finally { DEADLINE.remove(); KvmBoundedStats.clear(); }
    }
}
