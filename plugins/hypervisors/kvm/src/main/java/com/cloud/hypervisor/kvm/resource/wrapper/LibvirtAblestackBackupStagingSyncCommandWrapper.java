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

import org.apache.cloudstack.backup.AblestackBackupStagingSyncCommand;
import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = AblestackBackupStagingSyncCommand.class)
public class LibvirtAblestackBackupStagingSyncCommandWrapper
        extends CommandWrapper<AblestackBackupStagingSyncCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(AblestackBackupStagingSyncCommand command, LibvirtComputingResource resource) {
        if (StringUtils.isAnyBlank(resource.getAbleCvtBackupPath(), command.getRequestJson())) {
            return new Answer(command, false, "Missing staging synchronization command or backup script directory");
        }
        Path helper = Path.of(resource.getAbleCvtBackupPath()).resolveSibling("thirdparty_staging_sync.py");
        if (!Files.isRegularFile(helper)) {
            return new Answer(command, false, "Staging synchronization helper is missing; update the KVM Agent and host scripts");
        }
        Script script = new Script(true, "timeout", 80000, logger);
        script.setAvoidLoggingCommand(true);
        script.add("-k", "5", "70", "python3", helper.toString(), "--request", command.getRequestJson());
        OutputInterpreter.AllLinesParser output = new OutputInterpreter.AllLinesParser();
        String error = script.executeIgnoreExitValue(output, 1);
        String details = StringUtils.trimToEmpty(output.getLines());
        return new Answer(command, error == null && script.getExitValue() == 0,
                StringUtils.defaultIfBlank(details, StringUtils.defaultIfBlank(error, "No staging synchronization response")));
    }
}
