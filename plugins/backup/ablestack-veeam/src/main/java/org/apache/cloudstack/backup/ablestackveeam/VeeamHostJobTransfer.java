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

package org.apache.cloudstack.backup.ablestackveeam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupSubmissionException;
import org.apache.cloudstack.backup.ThirdPartyBackupTransferBusyException;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.Gson;

/** Durable, Host-scoped transfer Job reuse. Artifact jobId is always a session, never the shared Job. */
final class VeeamHostJobTransfer {
    static final String PROTOCOL = "VEEAM_HOST_JOB_V1";
    private static final String SCRIPT = readScript();

    private VeeamHostJobTransfer() { }

    static String jobName(String template, String host) {
        if (StringUtils.isAnyBlank(template, host)) { throw new CloudRuntimeException("Veeam Host Job requires its source Host and scheduling Job"); }
        return "ABLESTACK-H-" + UUID.nameUUIDFromBytes((template + "|" + host).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    static void prepare(ThirdPartyBackupManifest.Artifact artifact, String template) {
        String name = jobName(template, artifact.sourceHost);
        if (artifact.transferProtocol != null && !PROTOCOL.equals(artifact.transferProtocol)) {
            throw new CloudRuntimeException("Cannot change an artifact's transfer protocol");
        }
        if (artifact.externalJobName != null && !name.equals(artifact.externalJobName)) {
            throw new CloudRuntimeException("Cannot move an issued artifact to another Host Job");
        }
        artifact.transferProtocol = PROTOCOL;
        artifact.externalJobName = name;
    }

    static List<String> commands(String operation, String template, String sourceIp,
            ThirdPartyBackupManifest.Artifact artifact, boolean metadata, boolean expiration) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("operation", operation);
        plan.put("template", template);
        plan.put("sourceIp", sourceIp);
        plan.put("protocol", PROTOCOL);
        plan.put("metadata", metadata);
        plan.put("expiration", expiration);
        plan.put("artifact", artifact);
        if (artifact != null) {
            String identity = artifact.backupUuid + "|" + artifact.path + "|" + artifact.sourceHost + "|" + metadata + "|" + artifact.externalJobName;
            plan.put("binding", identity);
            plan.put("key", UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString());
            plan.put("jobName", artifact.externalJobName);
        }
        return List.of("$moldPlan='" + new Gson().toJson(plan).replace("'", "''") + "' | ConvertFrom-Json", SCRIPT);
    }

    static List<String> readyCommands(String template, String sourceHost) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("operation", "READY");
        plan.put("template", template);
        plan.put("jobName", jobName(template, sourceHost));
        plan.put("protocol", PROTOCOL);
        return List.of("$moldPlan='" + new Gson().toJson(plan).replace("'", "''") + "' | ConvertFrom-Json", SCRIPT);
    }

    static ThirdPartyBackupManifest.Artifact result(JSONObject response, ThirdPartyBackupManifest.Artifact artifact) {
        if (response.optBoolean("busy", false) && response.optBoolean("notSubmitted", false)) {
            throw new ThirdPartyBackupTransferBusyException(response.getString("failure"));
        }
        if (response.optBoolean("notSubmitted", false)) {
            throw new ThirdPartyBackupSubmissionException(response.getString("failure"));
        }
        if (response.isNull("jobId")) { return null; }
        ThirdPartyBackupManifest.Artifact result = new Gson().fromJson(new Gson().toJson(artifact), ThirdPartyBackupManifest.Artifact.class);
        result.jobId = response.getString("jobId");
        result.externalId = response.optString("externalId", null);
        result.externalBackupId = response.optString("backupId", null);
        result.externalJobId = response.getString("hostJobId");
        result.backupTime = response.optString("backupTime", null);
        result.completed = response.getBoolean("completed");
        result.externalTerminal = response.optBoolean("terminal", false);
        result.externalFailure = response.optString("failure", null);
        for (String id : new String[]{result.jobId, result.externalJobId}) { UUID.fromString(id); }
        if (result.completed) { UUID.fromString(result.externalId); UUID.fromString(result.externalBackupId); }
        if ((StringUtils.isNotBlank(artifact.jobId) && !artifact.jobId.equals(result.jobId))
                || (StringUtils.isNotBlank(artifact.externalJobId) && !artifact.externalJobId.equals(result.externalJobId))
                || (StringUtils.isNotBlank(artifact.externalBackupId) && !artifact.externalBackupId.equals(result.externalBackupId))) {
            throw new CloudRuntimeException("Recovered Host Job result differs from the persisted artifact");
        }
        return result;
    }

    private static String readScript() {
        try (InputStream input = VeeamHostJobTransfer.class.getResourceAsStream("host-job-transfer.ps1")) {
            if (input == null) { throw new CloudRuntimeException("Missing Veeam Host Job transfer script"); }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new CloudRuntimeException("Unable to read Veeam Host Job transfer script", e); }
    }
}
