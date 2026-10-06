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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackBackupFrameworkUtils;
import org.apache.cloudstack.backup.AblestackVolumeStagingCommand;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;

@ResourceWrapper(handles = AblestackVolumeStagingCommand.class)
public class LibvirtAblestackVolumeStagingCommandWrapper
        extends CommandWrapper<AblestackVolumeStagingCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(AblestackVolumeStagingCommand command, LibvirtComputingResource resource) {
        try {
            String id = command.getJobId();
            if (id == null || !id.matches("[A-Za-z0-9_.-]+") || id.equals(".") || id.equals("..")) {
                return new Answer(command, false, "Invalid volume staging job ID");
            }
            Path directory = Path.of(AblestackBackupFrameworkUtils.ASYNC_BACKUP_JOB_ROOT, id);
            if (!Files.isDirectory(directory)) {
                return new Answer(command, false, "Volume staging job does not exist");
            }
            Path request = directory.resolve("volume-request.json");
            if ("CANCEL".equals(command.getAction())) {
                Files.writeString(directory.resolve("volume-cancel"), "cancel\n");
                return new Answer(command, true, "Volume pipeline cancellation requested");
            }
            if ("STATUS".equals(command.getAction())) {
                return new Answer(command, true, Files.isRegularFile(request) ? Files.readString(request) : "");
            }
            if ("START".equals(command.getAction())) {
                if (!Files.isRegularFile(request) || !Boolean.TRUE.equals(new Gson().fromJson(Files.readString(request), Map.class).get("gate"))) {
                    return new Answer(command, false, "Source preparation is not pending");
                }
                Files.writeString(directory.resolve("volume-start"), "start\n");
                return new Answer(command, true, "Source preparation acknowledged");
            }
            if (!"ACK".equals(command.getAction()) || command.getIndex() < 0 || !Files.isRegularFile(request)) {
                return new Answer(command, false, "Invalid volume staging acknowledgment");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> current = new Gson().fromJson(Files.readString(request), Map.class);
            if (((Number) current.get("index")).intValue() != command.getIndex()) {
                return new Answer(command, false, "Volume staging request changed");
            }
            ThirdPartyBackupManifest manifest = ThirdPartyBackupManifest.fromJson(command.getManifest());
            if (!id.equals(manifest.getBackupUuid())) {
                return new Answer(command, false, "Acknowledgment belongs to another backup");
            }
            Path acknowledgment = directory.resolve("volume-ack-" + command.getIndex() + ".json");
            Path temporary = directory.resolve("volume-ack-" + command.getIndex() + ".tmp");
            Files.writeString(temporary, manifest.toJson(), StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, acknowledgment, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return new Answer(command, true, "Artifact transfer acknowledged");
        } catch (Exception e) {
            logger.warn("Volume staging control failed for job [{}]", command.getJobId(), e);
            return new Answer(command, false, e.getMessage());
        }
    }
}
