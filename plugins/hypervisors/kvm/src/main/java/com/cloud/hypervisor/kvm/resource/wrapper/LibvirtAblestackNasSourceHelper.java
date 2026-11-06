// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.AblestackNasTakeBackupCommand;

/** Private NAS runtime plan; source ownership itself contains no repository or Ceph credentials. */
final class LibvirtAblestackNasSourceHelper {
    private LibvirtAblestackNasSourceHelper() { }

    static Path file(String job) { return Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, job, "nas-source-plan.json"); }

    static Path prepare(AblestackNasTakeBackupCommand command, List<String> sources, String[] script) throws IOException {
        return prepare(file(command.getBackupJobId()), command, sources, script);
    }

    static Path prepare(Path file, AblestackNasTakeBackupCommand command, List<String> sources, String[] script) throws IOException {
        var plan = LibvirtAblestackBackupStartHelper.nasPlan(command);
        if (sources == null || sources.size() != plan.nasSource.volumeCount || sources.stream().distinct().count() != sources.size()) {
            throw new CloudRuntimeException("NAS runtime sources differ from their protected volume count");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("backupStartPlan", plan);
        payload.put("vmName", command.getVmName());
        payload.put("backupPath", command.getBackupPath());
        payload.put("checkpointName", command.getCheckpointName());
        payload.put("backupType", command.getBackupType());
        payload.put("rbd", plan.sourceIsRbd());
        payload.put("diskPaths", sources);
        payload.put("timeout", command.getWait() > 0 ? command.getWait() : 43200);
        if (plan.sourceIsRbd()) {
            if (script == null) { throw new CloudRuntimeException("Protected RBD NAS backup requires its snapshot script"); }
            payload.put("script", script);
        } else { payload.put("dummyName", "DUMMY-NAS-" + plan.jobId); }
        Files.createDirectories(file.getParent());
        Gson gson = new Gson();
        if (Files.exists(file)) {
            if (!gson.toJsonTree(payload).equals(com.google.gson.JsonParser.parseString(Files.readString(file)))) {
                throw new CloudRuntimeException("NAS runtime plan was already saved for different sources");
            }
            return file;
        }
        Path temporary = Files.createTempFile(file.getParent(), "nas-source-", ".json");
        try {
            Files.writeString(temporary, gson.toJson(payload));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel directory = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temporary); }
        return file;
    }

    static String[] runner(LibvirtComputingResource resource, Path file, String action) {
        Path helper = Path.of(resource.getAbleNasBackupPath()).getParent().resolve("ablestack_nas_source_guard.py");
        return new String[] {"python3", helper.toString(), "--plan-file", file.toString(), "--action", action};
    }

    static Pair<Integer, String> action(LibvirtComputingResource resource, String job, String action) {
        return Script.executePipedCommands(List.<String[]>of(runner(resource, file(job), action)), 300000);
    }
}
