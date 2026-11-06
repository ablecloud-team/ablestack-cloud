// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackSharedRawBackupCommand;

@ResourceWrapper(handles = AblestackSharedRawBackupCommand.class)
public class LibvirtAblestackSharedRawBackupCommandWrapper extends CommandWrapper<AblestackSharedRawBackupCommand, Answer, LibvirtComputingResource> {
    @Override public Answer execute(AblestackSharedRawBackupCommand command, LibvirtComputingResource resource) {
        try {
            // RELEASE must obtain every cluster file lock and prove no orphan converter remains.
            // A delayed worker then fails its required owner check before any source mutation.
            LibvirtAblestackSharedRawGuard.control(resource, command.getAction(), command.getPlan());
            return new Answer(command, true, new Gson().toJson(command.getPlan()));
        } catch (Exception failure) { return new Answer(command, false, failure.getMessage()); }
    }
}
