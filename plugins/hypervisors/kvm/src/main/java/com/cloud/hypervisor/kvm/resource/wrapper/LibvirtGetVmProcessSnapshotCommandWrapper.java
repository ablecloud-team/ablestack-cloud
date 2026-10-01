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
import com.cloud.agent.api.GetVmProcessSnapshotCommand;
import com.cloud.agent.api.GetVmProcessSnapshotAnswer;
import com.cloud.agent.api.VmProcessSnapshot;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.KvmVmOperationGuard;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.google.gson.GsonBuilder;
@ResourceWrapper(handles = GetVmProcessSnapshotCommand.class)
public final class LibvirtGetVmProcessSnapshotCommandWrapper
        extends CommandWrapper<GetVmProcessSnapshotCommand, Answer, LibvirtComputingResource> {
    @Override public Answer execute(GetVmProcessSnapshotCommand command, LibvirtComputingResource resource) {
        com.google.gson.Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
        try {
            String result = KvmVmOperationGuard.processSnapshot(command.getVmUuid(), gson.toJson(VmProcessSnapshot.request(command)));
            VmProcessSnapshot.decode(result, command);
            return new GetVmProcessSnapshotAnswer(command, result);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new GetVmProcessSnapshotAnswer(command, gson.toJson(VmProcessSnapshot.failure(command, "CHECK_FAILED")));
        }
    }
}
