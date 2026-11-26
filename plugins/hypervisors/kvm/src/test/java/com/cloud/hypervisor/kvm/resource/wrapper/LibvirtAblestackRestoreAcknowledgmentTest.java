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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import com.cloud.agent.api.Answer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.cloudstack.backup.AblestackVolumeStagingCommand;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LibvirtAblestackRestoreAcknowledgmentTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final Gson gson = new Gson();
    private Path directory;
    private ThirdPartyBackupRestore.Request request;

    @Before public void setup() throws Exception {
        directory = folder.newFolder().toPath();
        request = new ThirdPartyBackupRestore.Request();
        request.jobId = "restore-ack-test";
        request.sequence = 0;
        request.volumeIndex = -1;
        request.chainIndex = -1;
        request.metadata = true;
        request.destination = "/stage/restore/restore-ack-test/metadata/point";
        request.artifact = new ThirdPartyBackupManifest.Artifact();
        request.artifact.backupUuid = "source-backup";
        request.artifact.path = "/stage/source-vm/point";
        request.artifact.externalId = "ablecube1_100";
        Files.writeString(directory.resolve("volume-restore-request.json"), gson.toJson(request));
    }

    private Answer acknowledge(String payload) throws Exception {
        return LibvirtAblestackVolumeStagingCommandWrapper.restoreAcknowledgment(
                new AblestackVolumeStagingCommand(request.jobId, "RESTORE_ACK", request.sequence, payload), directory);
    }

    private Answer abort(ThirdPartyBackupRestore.Request expected) throws Exception {
        return LibvirtAblestackVolumeStagingCommandWrapper.restoreAcknowledgment(
                new AblestackVolumeStagingCommand(request.jobId, ThirdPartyBackupRestore.ACK_ABORT_ACTION, expected.sequence,
                        gson.toJson(Map.of("request", expected, "message", "Receipt rejected; retain original volumes"))), directory);
    }

    @Test public void reorderedWireFieldsKeepTheWorkersExactReceipt() throws Exception {
        JsonObject original = gson.toJsonTree(request).getAsJsonObject();
        JsonObject reordered = new JsonObject();
        var entries = new ArrayList<>(original.entrySet());
        Collections.reverse(entries);
        for (var entry : entries) { reordered.add(entry.getKey(), entry.getValue()); }
        assertTrue(acknowledge(reordered.toString()).getResult());
        assertEquals(gson.toJson(request), Files.readString(directory.resolve("volume-restore-ack-0.json")));
    }

    @Test public void schemaDifferencesRejectAcknowledgmentWithAnActionableReason() throws Exception {
        JsonObject payload = gson.toJsonTree(request).getAsJsonObject();
        payload.getAsJsonObject("artifact").addProperty("futureRetentionField", 0);
        Answer result = acknowledge(payload.toString());
        assertFalse(result.getResult());
        assertTrue(result.getDetails().contains("Management/Host versions"));
        assertFalse(Files.exists(directory.resolve("volume-restore-ack-0.json")));
        assertFalse(Files.exists(directory.resolve("volume-restore-failure")));
        Answer stop = LibvirtAblestackVolumeStagingCommandWrapper.restoreAcknowledgment(
                new AblestackVolumeStagingCommand(request.jobId, ThirdPartyBackupRestore.ACK_ABORT_ACTION, request.sequence,
                        gson.toJson(Map.of("request", payload, "message", "Schema differs; stop this pending file"))), directory);
        assertTrue(stop.getResult());
        assertTrue(Files.exists(directory.resolve("volume-restore-failure")));
        assertFalse(Files.exists(directory.resolve("volume-restore-ack-0.json")));
    }

    @Test public void confirmedStopBlocksLateAcknowledgments() throws Exception {
        Answer result = abort(request);
        assertTrue(result.getResult());
        assertTrue(result.getDetails().contains("STOPPING"));
        var failure = JsonParser.parseString(Files.readString(directory.resolve("volume-restore-failure"))).getAsJsonObject();
        assertTrue(failure.get("terminal").getAsBoolean());
        assertTrue(failure.get("message").getAsString().contains("retain original volumes"));
        assertFalse(acknowledge(gson.toJson(request)).getResult());
        assertFalse(Files.exists(directory.resolve("volume-restore-ack-0.json")));
    }

    @Test public void lostReplyIsRecoveredWithoutStoppingTheNextRequest() throws Exception {
        assertTrue(acknowledge(gson.toJson(request)).getResult());
        var next = gson.fromJson(gson.toJson(request), ThirdPartyBackupRestore.Request.class);
        next.sequence = 1;
        next.metadata = false;
        Files.writeString(directory.resolve("volume-restore-request.json"), gson.toJson(next));
        Answer result = abort(request);
        assertTrue(result.getResult());
        assertTrue(result.getDetails().contains("ACKNOWLEDGED"));
        assertFalse(Files.exists(directory.resolve("volume-restore-failure")));
        assertEquals(gson.toJson(next), Files.readString(directory.resolve("volume-restore-request.json")));
    }

    @Test public void staleOrDifferentDestinationsCannotStopTheWorker() throws Exception {
        var other = gson.fromJson(gson.toJson(request), ThirdPartyBackupRestore.Request.class);
        other.destination = "/another/volume";
        assertFalse(abort(other).getResult());
        other = gson.fromJson(gson.toJson(request), ThirdPartyBackupRestore.Request.class);
        other.sequence = 1;
        assertFalse(abort(other).getResult());
        other = gson.fromJson(gson.toJson(request), ThirdPartyBackupRestore.Request.class);
        other.artifact.externalId = "another-image";
        assertFalse(abort(other).getResult());
        assertFalse(Files.exists(directory.resolve("volume-restore-failure")));
        assertFalse(Files.exists(directory.resolve("volume-restore-ack-0.json")));
    }

    @Test public void missingPendingRequestsNeverAcquireAFailureMarker() throws Exception {
        Files.delete(directory.resolve("volume-restore-request.json"));
        assertFalse(abort(request).getResult());
        assertFalse(Files.exists(directory.resolve("volume-restore-failure")));
    }
}
