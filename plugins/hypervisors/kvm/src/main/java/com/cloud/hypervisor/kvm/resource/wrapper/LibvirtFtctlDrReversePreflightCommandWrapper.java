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

import java.io.File;
import java.io.IOException;

import org.apache.commons.lang3.StringUtils;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FtctlDrReversePreflightAnswer;
import com.cloud.agent.api.FtctlDrReversePreflightCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.OutputInterpreter;
import com.cloud.utils.script.Script;
import com.google.gson.JsonObject;

@ResourceWrapper(handles = FtctlDrReversePreflightCommand.class)
public class LibvirtFtctlDrReversePreflightCommandWrapper
        extends CommandWrapper<FtctlDrReversePreflightCommand, Answer, LibvirtComputingResource> {
    private static final int DEFAULT_TIMEOUT_SECONDS = 45;

    @Override
    public Answer execute(FtctlDrReversePreflightCommand command, LibvirtComputingResource serverResource) {
        if (StringUtils.isBlank(command.getPlanUuid()) || StringUtils.isBlank(command.getProfileJson())) {
            return new FtctlDrReversePreflightAnswer(command, false, "Missing reverse preflight plan/profile");
        }
        File profileFile = null;
        try {
            profileFile = LibvirtFtctlDrCommandHelper.writeProfileJson(command.getPlanUuid(), command.getProfileJson());
            Script script = new Script("ablestack_vm_ftctl", DEFAULT_TIMEOUT_SECONDS * 1000L, logger);
            script.add("dr-reverse-preflight");
            LibvirtFtctlDrCommandHelper.addPlanRunArgs(script, command.getPlanUuid(), null);
            LibvirtFtctlDrCommandHelper.addProfileJsonArg(script, profileFile);
            script.add("--operation-intent");
            script.add(StringUtils.defaultIfBlank(command.getOperationIntent(), "FAILBACK_FINAL"));
            script.add("--requested-mode");
            script.add(StringUtils.defaultIfBlank(command.getRequestedMode(), "AUTO"));
            script.add("--json");
            OutputInterpreter.AllLinesParser parser = new OutputInterpreter.AllLinesParser();
            String result = script.execute(parser);
            String output = LibvirtFtctlWrapperHelper.getOutput(result, parser);
            JsonObject payload = LibvirtFtctlWrapperHelper.parseJsonObject(output);
            int exitValue = script.getExitValue();
            boolean ready = Boolean.TRUE.equals(LibvirtFtctlDrCommandHelper.getBoolean(payload, "ready"));
            return new FtctlDrReversePreflightAnswer(command, exitValue == 0 && ready,
                    StringUtils.defaultIfBlank(output, ready ? "OK" : "Reverse preflight failed"), ready,
                    LibvirtFtctlDrCommandHelper.getString(payload, "operation_intent"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "requested_mode"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "effective_mode"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "mode_decision_code"),
                    LibvirtFtctlDrCommandHelper.getBoolean(payload, "initial_seed_required"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "baseline_file_state"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "source_domain_probe_state"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "source_disk_probe_state"),
                    LibvirtFtctlDrCommandHelper.getInteger(payload, "source_disk_count"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "target_writer_probe_state"),
                    LibvirtFtctlDrCommandHelper.getLong(payload, "estimated_virtual_bytes"),
                    LibvirtFtctlDrCommandHelper.getInteger(payload, "status_evidence_contract_version"),
                    LibvirtFtctlDrCommandHelper.getBoolean(payload, "status_evidence_publication_ready"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "status_evidence_error_code"),
                    LibvirtFtctlDrCommandHelper.getString(payload, "error_code"), exitValue,
                    payload != null ? payload.toString() : null);
        } catch (IOException e) {
            return new FtctlDrReversePreflightAnswer(command, false,
                    "Unable to write reverse preflight profile: " + e.getMessage());
        } finally {
            LibvirtFtctlDrCommandHelper.deleteQuietly(profileFile);
        }
    }
}
