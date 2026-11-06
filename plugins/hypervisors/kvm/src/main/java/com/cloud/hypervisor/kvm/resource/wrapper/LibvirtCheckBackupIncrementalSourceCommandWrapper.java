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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.CheckBackupIncrementalSourceCommand;

@ResourceWrapper(handles = CheckBackupIncrementalSourceCommand.class)
public class LibvirtCheckBackupIncrementalSourceCommandWrapper
        extends CommandWrapper<CheckBackupIncrementalSourceCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(CheckBackupIncrementalSourceCommand cmd, LibvirtComputingResource resource) {
        Path file = null;
        try {
            Path script = Path.of(resource.getAbleCvtBackupPath()).getParent().resolve("thirdparty_volume_backup.py");
            if (!Files.isRegularFile(script) || cmd.getPools() == null || cmd.getPaths() == null
                    || cmd.getPaths().isEmpty() || cmd.getPools().size() != cmd.getPaths().size()) {
                return new Answer(cmd, false, "Incremental source inspection is unavailable");
            }
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("vmName", cmd.getVmName());
            plan.put("stopped", cmd.isStopped());
            plan.put("engine", cmd.getEngine());
            plan.put("checkpointName", cmd.getCheckpointName());
            plan.put("checkpointXml", cmd.getCheckpointXml());
            plan.put("diskPaths", LibvirtAblestackTakeBackupCommandHelper.resolveDiskPaths(resource, cmd.getPools(), cmd.getPaths()));
            file = Files.createTempFile("ablestack-incremental-source-", ".json",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, new Gson().toJson(plan));
            Script inspection = new Script("python3", 120000, logger);
            inspection.add(script.toString());
            inspection.add("--plan-file", file.toString());
            inspection.add("--inspect-incremental");
            OutputInterpreter.AllLinesParser output = new OutputInterpreter.AllLinesParser();
            String error = inspection.execute(output);
            if (error != null || inspection.getExitValue() != 0) {
                return new Answer(cmd, false, "BACKUP_INCREMENTAL_SOURCE_UNCONFIRMED: Baseline inspection failed");
            }
            return new Answer(cmd, true, output.getLines());
        } catch (Exception e) {
            return new Answer(cmd, false, "BACKUP_INCREMENTAL_SOURCE_UNCONFIRMED: Cannot verify the source baseline");
        } finally {
            if (file != null) try { Files.deleteIfExists(file); } catch (java.io.IOException ignored) { }
        }
    }
}
