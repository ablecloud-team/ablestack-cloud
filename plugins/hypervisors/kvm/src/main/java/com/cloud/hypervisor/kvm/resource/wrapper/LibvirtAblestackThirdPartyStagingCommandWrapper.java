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

import org.apache.cloudstack.backup.AblestackThirdPartyStagingCommand;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = AblestackThirdPartyStagingCommand.class)
public class LibvirtAblestackThirdPartyStagingCommandWrapper
        extends CommandWrapper<AblestackThirdPartyStagingCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(AblestackThirdPartyStagingCommand command, LibvirtComputingResource resource) {
        if (StringUtils.isBlank(resource.getAbleCvtBackupPath()) || command.getTimeoutSeconds() <= 0
                || command.getTimeoutSeconds() > Integer.MAX_VALUE - 30 || command.getRequiredBytes() < 0) {
            return new Answer(command, false, "Invalid staging preparation command or missing KVM backup script directory.");
        }
        Path scriptPath = Path.of(resource.getAbleCvtBackupPath()).resolveSibling("thirdparty_staging.sh");
        if (!Files.isRegularFile(scriptPath)) {
            return new Answer(command, false, "The staging preparation script is not installed on this KVM host: " + scriptPath);
        }
        Script script = new Script(true, "timeout", (command.getTimeoutSeconds() + 20L) * 1000L, logger);
        script.setAvoidLoggingCommand(true);
        script.add("-k", "5", String.valueOf(command.getTimeoutSeconds()), "/bin/bash", scriptPath.toString(),
                command.getStorageType(), command.getRootPath(), command.getMountPath(),
                StringUtils.defaultString(command.getNfsSource()), StringUtils.defaultString(command.getMountOptions()),
                command.getPath(), String.valueOf(command.getTimeoutSeconds()), String.valueOf(command.getRequiredBytes()));
        OutputInterpreter.AllLinesParser output = new OutputInterpreter.AllLinesParser();
        // Capture normal validation failures as well as successful output before checking the exit code.
        String error = script.executeIgnoreExitValue(output, 1);
        String details = StringUtils.trimToEmpty(output.getLines());
        if (error != null || script.getExitValue() != 0) {
            return new Answer(command, false, StringUtils.defaultIfBlank(details,
                    StringUtils.defaultIfBlank(error, "Staging preparation failed on this host.")));
        }
        // mount helpers can emit nonfatal notices; the script's final line is the available byte count.
        String[] lines = details.split("\\R");
        String capacity = lines[lines.length - 1];
        return new Answer(command, capacity.matches("[0-9]+"), capacity.matches("[0-9]+") ? capacity : "Invalid staging capacity response: " + details);
    }
}
