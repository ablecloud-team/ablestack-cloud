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

import com.cloud.host.Host;
import com.cloud.host.Status;
import com.cloud.vm.VirtualMachine;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class AblestackBackupRecoveryHelperTest {
    @Test
    public void unavailableHostDoesNotAuthorizeCleanupOrRetry() {
        AblestackBackupRecoveryHelper.SourceStatusQuery agent = mock(AblestackBackupRecoveryHelper.SourceStatusQuery.class);
        Host host = mock(Host.class);
        when(host.getStatus()).thenReturn(Status.Down);
        assertFalse(AblestackBackupRecoveryHelper.isSourceStopped(agent, host, mock(Backup.class), mock(VirtualMachine.class)));
        assertFalse(AblestackBackupRecoveryHelper.canRetry(host, mock(VirtualMachine.class)));
        verifyNoInteractions(agent);
    }

    @Test
    public void cleanupRequiresPositiveNonMutatingHostConfirmation() throws Exception {
        AblestackBackupRecoveryHelper.SourceStatusQuery agent = mock(AblestackBackupRecoveryHelper.SourceStatusQuery.class);
        Host host = mock(Host.class);
        Backup backup = mock(Backup.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(host.getId()).thenReturn(42L);
        when(backup.getUuid()).thenReturn("job-uuid");
        when(vm.getInstanceName()).thenReturn("i-2-45-VM");
        when(agent.send(eq(42L), any(AblestackBackupJobStatusCommand.class))).thenAnswer(call ->
                new BackupAnswer(call.getArgument(1), true, "QEMU job still active"));
        assertFalse(AblestackBackupRecoveryHelper.isSourceStopped(agent, host, backup, vm));
        ArgumentCaptor<AblestackBackupJobStatusCommand> command = ArgumentCaptor.forClass(AblestackBackupJobStatusCommand.class);
        verify(agent).send(eq(42L), command.capture());
        assertTrue(command.getValue().isVerifyTermination());
        assertEquals("i-2-45-VM", command.getValue().getVmName());
        assertEquals("job-uuid", command.getValue().getBackupJobId());
        when(agent.send(eq(42L), any(AblestackBackupJobStatusCommand.class))).thenAnswer(call -> {
            BackupAnswer answer = new BackupAnswer(call.getArgument(1), true, "Stopped");
            answer.setSourceTerminationConfirmed(true);
            return answer;
        });
        assertTrue(AblestackBackupRecoveryHelper.isSourceStopped(agent, host, backup, vm));
    }

    @Test
    public void pausedOrStoppingVmDoesNotStartFullFallback() {
        Host host = mock(Host.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopping);
        assertFalse(AblestackBackupRecoveryHelper.canRetry(host, vm));
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        assertTrue(AblestackBackupRecoveryHelper.canRetry(host, vm));
    }
    @Test
    public void legacyCanceledStatusWithoutTerminationProofCannotAuthorizeDeletion() throws Exception {
        AblestackBackupRecoveryHelper.SourceStatusQuery agent = mock(AblestackBackupRecoveryHelper.SourceStatusQuery.class);
        Host host = mock(Host.class);
        when(host.getStatus()).thenReturn(Status.Up);
        when(agent.send(eq(0L), any(AblestackBackupJobStatusCommand.class))).thenAnswer(call -> {
            BackupAnswer answer = new BackupAnswer(call.getArgument(1), true, "Canceled");
            answer.setState("CANCELED");
            return answer;
        });
        assertFalse(AblestackBackupRecoveryHelper.isSourceStopped(agent, host, mock(Backup.class), mock(VirtualMachine.class)));
    }

}
