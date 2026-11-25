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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.apache.cloudstack.backup.NetBackupHostConfigurationCommand;
import org.apache.commons.lang3.StringUtils;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@ResourceWrapper(handles = NetBackupHostConfigurationCommand.class)
public class LibvirtNetBackupHostConfigurationCommandWrapper
        extends CommandWrapper<NetBackupHostConfigurationCommand, Answer, LibvirtComputingResource> {
    @Override public Answer execute(NetBackupHostConfigurationCommand command, LibvirtComputingResource resource) {
        Path request = null;
        boolean accepted = false;
        try {
            if (!Set.of("STATUS", "CHECK", "APPLY", "HOOK_TIMEOUT_CHECK", "HOOK_TIMEOUT_APPLY", "HOOK_TIMEOUT_RECOVER",
                    "HOOK_TIMEOUT_ROLLBACK", "HOOK_TIMEOUT_COMMIT").contains(command.getAction()) || StringUtils.isBlank(resource.getThirdPartyVolumeBackupPath())) {
                return new Answer(command, false, "Invalid NetBackup configuration action or missing script directory");
            }
            Path helper = Path.of(resource.getThirdPartyVolumeBackupPath()).resolveSibling("netbackup_host_configuration.py");
            if (!Files.isRegularFile(helper)) { return new Answer(command, false, "NetBackup Host configuration helper is not installed"); }
            Script script = new Script(true, "/usr/bin/python3", command.getAction().startsWith("HOOK_TIMEOUT_") ? 180000 : 30000, logger);
            script.setAvoidLoggingCommand(true);
            script.add(helper.toString());
            if ("STATUS".equals(command.getAction())) {
                script.add("--status");
            } else {
                JsonObject payload = JsonParser.parseString(command.getPayload()).getAsJsonObject();
                if (!command.getAction().equals(payload.get("action").getAsString())) {
                    return new Answer(command, false, "Host configuration payload action mismatch");
                }
                Path directory = Path.of("/var/lib/ablestack/netbackup/configuration");
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    return new Answer(command, false, "Invalid NetBackup configuration directory");
                }
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
                request = Files.createTempFile(directory, ".request-", ".json",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.writeString(request, command.getPayload(), StandardCharsets.UTF_8);
                script.add("--request", request.toString());
            }
            OutputInterpreter.AllLinesParser output = new OutputInterpreter.AllLinesParser();
            String error = script.executeIgnoreExitValue(output, 1);
            if (error != null || script.getExitValue() != 0) {
                return new Answer(command, false, "Host configuration response is unconfirmed; refresh Host configuration status");
            }
            JsonObject receipt = JsonParser.parseString(StringUtils.trimToEmpty(output.getLines())).getAsJsonObject();
            accepted = receipt.has("state") && "RUNNING".equals(receipt.get("state").getAsString());
            return new Answer(command, receipt.has("state"), receipt.toString());
        } catch (Exception e) {
            return new Answer(command, false, "Unable to inspect or start NetBackup Host configuration: " + e.getClass().getSimpleName());
        } finally {
            if (request != null && !accepted) {
                try { Files.deleteIfExists(request); } catch (java.io.IOException ignored) { }
            }
        }
    }
}
