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
import com.cloud.agent.api.MigrationDiskControllerAnswer;
import com.cloud.agent.api.MigrationDiskControllerCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.MigrationDiskControllerGuard;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.MigrationDiskControllerPolicy;

import java.util.Map;

@ResourceWrapper(handles = MigrationDiskControllerCommand.class)
public class LibvirtMigrationDiskControllerCommandWrapper
        extends CommandWrapper<MigrationDiskControllerCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(MigrationDiskControllerCommand cmd, LibvirtComputingResource resource) {
        try {
            if (cmd.getContractVersion() != 1)
                throw new IllegalArgumentException("DISK_CONTROLLER_CONTRACT_UNSUPPORTED");
            var guard = new MigrationDiskControllerGuard();
            Map<String, Object> result;
            if ("CAPABILITIES".equals(cmd.getAction())) {
                result = guard.capabilities(cmd.getMachine(), cmd.getFirmware());
                if (cmd.getPlan() != null) {
                    var plan = MigrationDiskControllerPolicy.parse(cmd.getPlan());
                    guard.qualify(plan);
                    result.put("planhash", plan.get("hash").getAsString());
                }
            } else if ("VERIFY".equals(cmd.getAction())) {
                if (cmd.getVmName() == null || !cmd.getVmName().matches("[A-Za-z0-9_.-]+"))
                    throw new IllegalArgumentException("DISK_CONTROLLER_VM_IDENTITY_INVALID");
                var conn = resource.getLibvirtUtilitiesHelper().getConnection();
                var domain = conn.domainLookupByName(cmd.getVmName());
                try {
                    if (domain.isActive() != 1)
                        throw new IllegalArgumentException("DISK_CONTROLLER_VM_NOT_RUNNING");
                    result =
                            guard.validate(
                                    cmd.getPlan(), cmd.getBindings(), domain.getXMLDesc(0), true);
                } finally {
                    domain.free();
                }
            } else throw new IllegalArgumentException("DISK_CONTROLLER_ACTION_UNSUPPORTED");
            return new MigrationDiskControllerAnswer(cmd, result);
        } catch (Exception error) {
            return new Answer(cmd, false, "DISK_CONTROLLER_UNCONFIRMED: " + error.getMessage());
        }
    }
}
