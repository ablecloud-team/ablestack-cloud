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
package org.apache.cloudstack.backup;

import com.cloud.agent.api.Answer;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.vm.VirtualMachine;

public final class AblestackBackupRecoveryHelper {
    private AblestackBackupRecoveryHelper() { }

    @FunctionalInterface
    public interface SourceStatusQuery {
        Answer send(long hostId, AblestackBackupJobStatusCommand command) throws AgentUnavailableException, OperationTimedoutException;
    }

    public static boolean isSourceStopped(SourceStatusQuery agent, Host host, Backup backup, VirtualMachine vm) {
        if (host == null || host.getStatus() != Status.Up || backup == null) { return false; }
        try {
            AblestackBackupJobStatusCommand command = new AblestackBackupJobStatusCommand(backup.getUuid());
            command.setVerifyTermination(true);
            command.setVmName(vm != null ? vm.getInstanceName() : null);
            Answer answer = agent.send(host.getId(), command);
            // Older Agents can answer status, but cannot certify termination. Never send a delete command as a probe.
            return answer instanceof BackupAnswer && answer.getResult()
                    && Boolean.TRUE.equals(((BackupAnswer) answer).getSourceTerminationConfirmed());
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            return false;
        }
    }

    public static boolean canRetry(Host host, VirtualMachine vm) {
        return host != null && host.getStatus() == Status.Up && vm != null
                && (vm.getState() == VirtualMachine.State.Running || vm.getState() == VirtualMachine.State.Stopped);
    }
}
