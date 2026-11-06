// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership. The ASF licenses this file
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
package org.apache.cloudstack.backup.netbackup;

import java.util.function.Supplier;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;
import org.apache.cloudstack.backup.AblestackVolumeStagingCommand;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.backup.ThirdPartyBackupVolumeService;
import org.apache.commons.lang3.StringUtils;

/** Primary-directed file recovery; already-issued Client requests retain their read-only recovery path. */
public final class AblestackNetBackupRestoreTransfer implements ThirdPartyBackupVolumeService.RestoreTransfer {
    public static final String PRIMARY_REST = "netbackup-primary-rest-v12";
    private final Supplier<AblestackNetBackupClient> clients;
    private final AgentManager agentManager;
    private final long hostId;
    private final String destinationClient;

    public AblestackNetBackupRestoreTransfer(Supplier<AblestackNetBackupClient> clients, AgentManager agentManager,
            long hostId, String destinationClient) {
        this.clients = clients;
        this.agentManager = agentManager;
        this.hostId = hostId;
        this.destinationClient = destinationClient;
    }

    @Override
    public String submissionProtocol() { return PRIMARY_REST; }

    @Override
    public ThirdPartyBackupRestore.Result restore(ThirdPartyBackupRestore.Request request, ThirdPartyBackupRestore.Result saved) {
        AblestackNetBackupClient client = clients.get();
        if (StringUtils.isNotBlank(saved.jobId)) { return client.pollVolumeRestore(saved.jobId); }
        if (PRIMARY_REST.equals(saved.submissionProtocol)) {
            if (saved.recoveryOnly) {
                // There is no verified server-side idempotency key. A missing response cannot authorize another POST
                // or a switch to bprestore, even after a management server restart or Host timeout.
                throw new CloudRuntimeException("NetBackup Primary REST restore Job ID is unconfirmed for staging job " + request.jobId
                        + ", sequence " + request.sequence + "; submission will not be repeated and staging is retained");
            }
            return client.submitVolumeRestore(request, destinationClient);
        }
        if (!saved.recoveryOnly || saved.submissionProtocol != null) {
            throw new CloudRuntimeException("NetBackup restore submission protocol is missing or unsupported");
        }
        return recoverClientReceipt(request, client);
    }

    private ThirdPartyBackupRestore.Result recoverClientReceipt(ThirdPartyBackupRestore.Request request,
            AblestackNetBackupClient client) {
        final java.util.Map<String, String> options = new java.util.HashMap<>();
        options.put("server", client.getPrimaryServerName());
        options.put("destinationClient", destinationClient);
        options.put("request", new Gson().toJson(request));
        try {
            final Answer answer = agentManager.send(hostId, new AblestackVolumeStagingCommand(request.jobId,
                    "RESTORE_NETBACKUP_RECOVER", request.sequence, new Gson().toJson(options)));
            if (answer != null && !answer.getResult() && StringUtils.startsWith(answer.getDetails(), "ABLESTACK_NOT_SUBMITTED=")) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> rejected = new Gson().fromJson(
                        answer.getDetails().substring("ABLESTACK_NOT_SUBMITTED=".length()), java.util.Map.class);
                if (Boolean.TRUE.equals(rejected.get("notSubmitted"))) {
                    throw new org.apache.cloudstack.backup.ThirdPartyBackupSubmissionException(String.valueOf(rejected.get("failure")));
                }
            }
            if (answer == null || !answer.getResult() || StringUtils.isBlank(answer.getDetails())) {
                throw new CloudRuntimeException("NetBackup artifact restore submission was not confirmed");
            }
            return new ThirdPartyBackupRestore.Result(answer.getDetails(), false);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Unable to recover the existing NetBackup restore receipt on the Worker Host", e);
        }
    }
}
