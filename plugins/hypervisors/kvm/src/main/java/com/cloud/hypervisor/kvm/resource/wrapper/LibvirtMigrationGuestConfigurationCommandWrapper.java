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

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.MigrationGuestConfigurationAnswer;
import com.cloud.agent.api.MigrationGuestConfigurationCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.MigrationGuestConfigurationExchange;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.MigrationDiskControllerPolicy;
import com.cloud.storage.MigrationGuestConfigurationPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

@ResourceWrapper(handles = MigrationGuestConfigurationCommand.class)
public class LibvirtMigrationGuestConfigurationCommandWrapper
        extends CommandWrapper<
                MigrationGuestConfigurationCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(
            MigrationGuestConfigurationCommand cmd, LibvirtComputingResource resource) {
        try {
            if (cmd.getContractVersion() != 1)
                throw new IllegalArgumentException("GUEST_CONFIG_CONTRACT_UNSUPPORTED");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("supported", true);
            result.put("fresh_observation", true);
            result.put("transport", "qga-file-v1");
            result.put("status", "SUPPORTED");
            if (cmd.getPlan() != null) {
                var p = MigrationGuestConfigurationPolicy.parse(cmd.getPlan());
                result.put("plan_hash", p.get("hash").getAsString());
            }
            if ("CAPABILITIES".equals(cmd.getAction()))
                return new MigrationGuestConfigurationAnswer(cmd, result);
            if (!"RECONCILE".equals(cmd.getAction())
                    || cmd.getVmName() == null
                    || !cmd.getVmName().matches("[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("GUEST_CONFIG_ACTION_INVALID");
            var conn = resource.getLibvirtUtilitiesHelper().getConnection();
            var domain = conn.domainLookupByName(cmd.getVmName());
            try {
                if (domain.isActive() != 1
                        || !domain.getUUIDString().equalsIgnoreCase(cmd.getVmUuid()))
                    throw new IllegalArgumentException("GUEST_VM_IDENTITY_MISMATCH");
                var plan = MigrationGuestConfigurationPolicy.parse(cmd.getPlan());
                var controller = MigrationDiskControllerPolicy.parse(cmd.getControllerPlan());
                if (!plan.get("controller_plan_hash").equals(controller.get("hash")))
                    throw new IllegalArgumentException("GUEST_CONTROLLER_PLAN_MISMATCH");
                var binding =
                        MigrationGuestConfigurationExchange.binding(
                                plan,
                                controller,
                                cmd.getVolumeBindings(),
                                domain.getXMLDesc(0),
                                cmd.getVmUuid(),
                                MigrationGuestConfigurationExchange.bootScope(
                                        cmd.getVmName(), cmd.getVmUuid()));
                if (cmd.getNicBindings() == null)
                    throw new IllegalArgumentException("GUEST_NIC_BINDING_REQUIRED");
                binding.add("nics", com.google.gson.JsonParser.parseString(cmd.getNicBindings()));
                var exchange =
                        new MigrationGuestConfigurationExchange(
                                value -> {
                                    try {
                                        return domain.qemuAgentCommand(value, 5, 0);
                                    } catch (Exception error) {
                                        throw new com.cloud.utils.exception.CloudRuntimeException(
                                                "GUEST_CHANNEL_UNAVAILABLE");
                                    }
                                });
                return new MigrationGuestConfigurationAnswer(
                        cmd, exchange.reconcile(plan, binding, cmd.getLastVerifiedBindingHash()));
            } finally {
                domain.free();
            }
        } catch (Exception error) {
            return new Answer(cmd, false, "GUEST_CONFIG_UNCONFIRMED: " + error.getMessage());
        }
    }
}
