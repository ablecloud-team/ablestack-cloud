// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements. See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License. You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied. See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.backup.netbackup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.backup.AblestackVolumeStagingCommand;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.backup.ThirdPartyBackupSubmissionException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

public class AblestackNetBackupRestoreTransferTest {
    private final AblestackNetBackupClient client = mock(AblestackNetBackupClient.class);
    private final AgentManager agent = mock(AgentManager.class);
    private final AblestackNetBackupRestoreTransfer transfer = new AblestackNetBackupRestoreTransfer(() -> client, agent, 1L, "ablecube1");
    private final ThirdPartyBackupRestore.Request request = new ThirdPartyBackupRestore.Request();

    private ThirdPartyBackupRestore.Result intent() {
        ThirdPartyBackupRestore.Result saved = new ThirdPartyBackupRestore.Result();
        saved.submissionProtocol = transfer.submissionProtocol();
        saved.submissionPending = true;
        saved.submittedAt = 1000;
        return saved;
    }

    @Test
    public void newRequestsGoToPrimaryRestWithTheExactWorkerClientAndPollAfterRestart() {
        ThirdPartyBackupRestore.Result pending = new ThirdPartyBackupRestore.Result("392", false);
        when(client.submitVolumeRestore(request, "ablecube1")).thenReturn(pending);
        assertEquals("392", transfer.restore(request, intent()).jobId);
        ThirdPartyBackupRestore.Result completed = new ThirdPartyBackupRestore.Result("392", true);
        when(client.pollVolumeRestore("392")).thenReturn(completed);
        ThirdPartyBackupRestore.Result receipt = new Gson().fromJson(new Gson().toJson(intent()), ThirdPartyBackupRestore.Result.class);
        receipt.jobId = "392";
        AblestackNetBackupRestoreTransfer restarted = new AblestackNetBackupRestoreTransfer(() -> client, agent, 1L, "ablecube1");
        assertTrue(restarted.recover(request, receipt).completed);
        assertFalse(receipt.recoveryOnly);
        verify(client).submitVolumeRestore(request, "ablecube1");
        verifyNoInteractions(agent);
    }

    @Test
    public void unknownRestAcceptanceNeverFallsBackToClientOrSubmitsAgain() {
        ThirdPartyBackupRestore.Result saved = intent();
        assertThrows(CloudRuntimeException.class, () -> transfer.recover(request, saved));
        assertThrows(CloudRuntimeException.class, () -> transfer.recover(request, saved));
        assertThrows(RuntimeException.class, () -> transfer.link(request, saved, "392"));
        verifyNoInteractions(client, agent);
    }

    @Test
    public void rejectedRestDoesNotFallBackToBprestore() {
        when(client.submitVolumeRestore(request, "ablecube1")).thenThrow(new ThirdPartyBackupSubmissionException("HTTP 403"));
        assertThrows(ThirdPartyBackupSubmissionException.class, () -> transfer.restore(request, intent()));
        verifyNoInteractions(agent);
    }

    @Test
    public void legacyPendingClientRequestsOnlyRecoverTheirHostReceipt() throws Exception {
        when(client.getPrimaryServerName()).thenReturn("netbackup");
        when(agent.send(eq(1L), any(AblestackVolumeStagingCommand.class))).thenReturn(new Answer(null, true, "391"));
        ThirdPartyBackupRestore.Result saved = new ThirdPartyBackupRestore.Result();
        saved.submissionPending = true;
        saved.submittedAt = 1234;
        assertEquals("391", transfer.recover(request, saved).jobId);
        ArgumentCaptor<AblestackVolumeStagingCommand> command = ArgumentCaptor.forClass(AblestackVolumeStagingCommand.class);
        verify(agent).send(eq(1L), command.capture());
        assertEquals("RESTORE_NETBACKUP_RECOVER", command.getValue().getAction());
        verify(client, never()).submitVolumeRestore(any(), any());
    }

    @Test
    public void legacyJobIdsArePolledWithoutReissuingTheRestore() {
        ThirdPartyBackupRestore.Result saved = new ThirdPartyBackupRestore.Result("386", false);
        when(client.pollVolumeRestore("386")).thenReturn(ThirdPartyBackupRestore.Result.failed("386", "DONE, 135"));
        ThirdPartyBackupRestore.Result result = transfer.recover(request, saved);
        assertTrue(result.externalTerminal);
        assertFalse(result.completed);
        assertTrue(result.failure.contains("135"));
        verifyNoInteractions(agent);
        verify(client, never()).submitVolumeRestore(any(), any());
    }

    @Test
    public void newSubmissionsRequireADurableProtocolAndCannotUseAnUnknownTransport() {
        assertThrows(CloudRuntimeException.class, () -> transfer.restore(request, new ThirdPartyBackupRestore.Result()));
        ThirdPartyBackupRestore.Result saved = intent();
        saved.submissionProtocol = "future-protocol";
        assertThrows(CloudRuntimeException.class, () -> transfer.recover(request, saved));
        verifyNoInteractions(client, agent);
    }
}
