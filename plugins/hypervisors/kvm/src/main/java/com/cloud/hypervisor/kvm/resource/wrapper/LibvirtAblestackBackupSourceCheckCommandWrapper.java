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

import org.apache.cloudstack.backup.AblestackBackupSourceCheckCommand;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = AblestackBackupSourceCheckCommand.class)
public class LibvirtAblestackBackupSourceCheckCommandWrapper
        extends CommandWrapper<AblestackBackupSourceCheckCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(AblestackBackupSourceCheckCommand command, LibvirtComputingResource resource) {
        if (StringUtils.isBlank(resource.getAbleCvtBackupPath()) || command.getTimeoutSeconds() <= 0
                || command.getTimeoutSeconds() > 60 || command.getPaths() == null || command.getPaths().isEmpty()
                || command.getPaths().stream().anyMatch(path -> StringUtils.isBlank(path) || !path.startsWith("/"))) {
            return new Answer(command, false, "Invalid read-only backup source check command.");
        }
        Path scriptPath = Path.of(resource.getAbleCvtBackupPath()).resolveSibling("thirdparty_backup_source_support.py");
        if (!Files.isRegularFile(scriptPath)) {
            return new Answer(command, false, "Backup source check script is missing; update the KVM agent scripts.");
        }
        Script script = new Script(true, "timeout", (command.getTimeoutSeconds() + 20L) * 1000L, logger);
        script.setAvoidLoggingCommand(true);
        script.add("-k", "5", String.valueOf(command.getTimeoutSeconds()), "python3", scriptPath.toString(), "--health-paths");
        command.getPaths().forEach(script::add);
        OutputInterpreter.AllLinesParser output = new OutputInterpreter.AllLinesParser();
        String error = script.executeIgnoreExitValue(output, 1);
        boolean successful = error == null && script.getExitValue() == 0;
        return new Answer(command, successful, successful ? "Backup file sources are accessible."
                : StringUtils.defaultIfBlank(StringUtils.trimToEmpty(output.getLines()),
                        StringUtils.defaultIfBlank(error, "Backup source check failed or timed out.")));
    }
}
