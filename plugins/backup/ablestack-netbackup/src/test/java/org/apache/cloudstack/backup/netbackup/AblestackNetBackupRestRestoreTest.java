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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.cloudstack.backup.ThirdPartyBackupSubmissionException;
import org.apache.http.HttpHeaders;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;

import com.cloud.utils.exception.CloudRuntimeException;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.github.tomakehurst.wiremock.junit.WireMockRule;

public class AblestackNetBackupRestRestoreTest {
    private static final String RECOVER = "/netbackup/recovery/workloads/physical/scenarios/granular-files-folders/recover";
    private static final String MULTIPART = "multipart/vnd.netbackup+form-data;version=12.0";
    private static final String API_KEY = "synthetic-restore-api-key";
    private static final String TIME = "2026-10-26T02:01:40Z";
    private static final String ACCEPTED = "{\"relationships\":{\"recoveryJob\":{\"data\":{\"type\":\"job\",\"id\":\"392\"}}}}";

    @Rule
    public WireMockRule server = new WireMockRule(WireMockConfiguration.wireMockConfig().dynamicPort());

    private AblestackNetBackupClient client() throws Exception {
        return new AblestackNetBackupClient("http://localhost:" + server.port() + "/netbackup", API_KEY, 1);
    }

    private ThirdPartyBackupRestore.Request selection(String root, boolean metadata) {
        ThirdPartyBackupRestore.Request request = new ThirdPartyBackupRestore.Request();
        request.jobId = "restore-attempt-1";
        request.sequence = metadata ? 0 : 1;
        request.metadata = metadata;
        request.artifact = new ThirdPartyBackupManifest.Artifact();
        request.artifact.sourceHost = "ablecube4";
        request.artifact.externalId = "ablecube4_1792980100";
        request.artifact.backupTime = TIME;
        String basename = metadata ? "2026.10.26.11.00.04.498" : "root.volume.raw";
        request.artifact.path = root + "/ablestack-netbackup/i-2-317-VM/" + (metadata ? basename : "2026.10.26.11.00.04.498/" + basename);
        request.destination = root + "/ablestack-netbackup/restore/" + request.jobId + (metadata ? "/metadata/" : "/payload/") + basename;
        return request;
    }

    private Map<String, String> parts(LoggedRequest request) {
        String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        assertTrue(contentType.startsWith(MULTIPART + "; boundary="));
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
        String body = request.getBodyAsString();
        assertTrue(body.endsWith("--" + boundary + "--\r\n"));
        Map<String, String> parts = new HashMap<>();
        for (String part : body.split(Pattern.quote("--" + boundary))) {
            if (!part.startsWith("\r\nContent-Disposition")) { continue; }
            int start = part.indexOf("name=\"") + 6;
            String name = part.substring(start, part.indexOf('"', start));
            assertTrue(part.contains("filename=\"blob\""));
            assertTrue(part.contains("Content-Type: " + MULTIPART + "\r\n"));
            parts.put(name, part.substring(part.indexOf("\r\n\r\n") + 4).trim());
        }
        assertEquals(3, parts.size());
        return parts;
    }

    @Test
    public void sharedAndLocalStagingRestoreExactFilesAndMetadataToTheRecordedDestinationClient() throws Exception {
        server.stubFor(post(urlEqualTo(RECOVER)).withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                .withHeader(HttpHeaders.ACCEPT, equalTo("application/vnd.netbackup+json;version=12.0"))
                .willReturn(aResponse().withStatus(202).withBody(ACCEPTED)));
        for (String root : new String[]{"/mnt/glue-gfs", "/mnt/nfs", "/var/lib/backup local"}) {
            for (boolean metadata : new boolean[]{false, true}) {
                server.resetRequests();
                ThirdPartyBackupRestore.Request selection = selection(root, metadata);
                ThirdPartyBackupRestore.Result result = client().submitVolumeRestore(selection, "ablecube1");
                assertEquals("392", result.jobId);
                assertFalse(result.completed);
                assertFalse(result.externalTerminal);
                LoggedRequest posted = server.findAll(postRequestedFor(urlEqualTo(RECOVER))).get(0);
                Map<String, String> parts = parts(posted);
                JSONObject data = new JSONObject(parts.get("recoveryRequest")).getJSONObject("data");
                assertEquals("physicalFilesFoldersRecoveryRequest", data.getString("type"));
                JSONObject point = data.getJSONObject("attributes").getJSONObject("recoveryPoint");
                assertEquals("ablecube4", point.getString("client"));
                assertEquals("STANDARD", point.getString("policyType"));
                assertEquals(TIME, point.getString("startDate"));
                assertEquals(TIME, point.getString("endDate"));
                JSONObject options = data.getJSONObject("attributes").getJSONObject("recoveryOptions");
                assertEquals("ablecube1", options.getString("destinationClient"));
                assertEquals("localhost", options.getString("server"));
                assertFalse(options.getBoolean("overwriteExistingFiles"));
                JSONArray files = new JSONArray(parts.get("selectionsFile"));
                assertEquals(1, files.length());
                assertEquals(selection.artifact.path + (metadata ? "/" : ""), files.getJSONObject(0).getString("path"));
                assertEquals(TIME, files.getJSONObject(0).getString("backupTime"));
                JSONArray rename = new JSONArray(parts.get("renameFile"));
                assertEquals(1, rename.length());
                assertEquals(selection.artifact.path.substring(0, selection.artifact.path.lastIndexOf('/') + 1), rename.getJSONObject(0).getString("from"));
                assertEquals(selection.destination.substring(0, selection.destination.lastIndexOf('/') + 1), rename.getJSONObject(0).getString("to"));
                server.verify(1, postRequestedFor(urlEqualTo(RECOVER)));
                server.verify(0, getRequestedFor(urlEqualTo("/netbackup/admin/jobs/392")));
            }
        }
    }

    @Test
    public void acceptedStatusAndLocationResponsesReturnWithoutWaitingForTheJob() throws Exception {
        for (int status : new int[]{200, 201, 202}) {
            server.stubFor(post(urlEqualTo(RECOVER)).willReturn(aResponse().withStatus(status).withBody(ACCEPTED)));
            assertEquals("392", client().submitVolumeRestore(selection("/local", false), "ablecube4").jobId);
        }
        server.stubFor(post(urlEqualTo(RECOVER)).willReturn(aResponse().withStatus(202).withHeader("Location", "/admin/jobs/393")));
        assertEquals("393", client().submitVolumeRestore(selection("/local", false), "ablecube1").jobId);
    }

    @Test
    public void olderArtifactsWithoutBackupTimeUseTheirExactImageEpoch() throws Exception {
        server.stubFor(post(urlEqualTo(RECOVER)).willReturn(aResponse().withStatus(202).withBody(ACCEPTED)));
        ThirdPartyBackupRestore.Request request = selection("/local", false);
        request.artifact.backupTime = null;
        client().submitVolumeRestore(request, "ablecube1");
        Map<String, String> parts = parts(server.findAll(postRequestedFor(urlEqualTo(RECOVER))).get(0));
        assertEquals(TIME, new JSONArray(parts.get("selectionsFile")).getJSONObject(0).getString("backupTime"));
    }

    @Test
    public void validationAndExplicitRejectionsAreConfirmedNotSubmitted() throws Exception {
        ThirdPartyBackupRestore.Request request = selection("/local", false);
        request.destination += ".different";
        assertThrows(ThirdPartyBackupSubmissionException.class, () -> client().submitVolumeRestore(request, "ablecube1"));
        request.destination = "/local/../payload/root.volume.raw";
        assertThrows(ThirdPartyBackupSubmissionException.class, () -> client().submitVolumeRestore(request, "ablecube1"));
        server.verify(0, postRequestedFor(urlEqualTo(RECOVER)));
        for (int status : new int[]{400, 401, 403, 404, 415, 422}) {
            server.stubFor(post(urlEqualTo(RECOVER)).willReturn(aResponse().withStatus(status).withBody("{\"errorMessage\":\"denied\"}")));
            assertTrue(assertThrows(ThirdPartyBackupSubmissionException.class,
                    () -> client().submitVolumeRestore(selection("/local", false), "ablecube1")).getMessage().contains(String.valueOf(status)));
        }
    }

    @Test
    public void unknownAcceptanceIsNeverReportedAsNotSubmitted() throws Exception {
        for (String body : new String[]{"", "{}", "not-json", "{\"relationships\":{\"recoveryJob\":{\"data\":{\"id\":\"../other\"}}}}"}) {
            server.stubFor(post(urlEqualTo(RECOVER)).willReturn(aResponse().withStatus(202).withBody(body)));
            CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                    () -> client().submitVolumeRestore(selection("/local", false), "ablecube1"));
            assertFalse(error instanceof ThirdPartyBackupSubmissionException);
        }
        server.stubFor(post(urlEqualTo(RECOVER)).willReturn(aResponse().withStatus(500)));
        assertFalse(assertThrows(CloudRuntimeException.class,
                () -> client().submitVolumeRestore(selection("/local", false), "ablecube1")) instanceof ThirdPartyBackupSubmissionException);
    }

    @Test
    public void responseTimeoutDoesNotAutomaticallyReplayThePost() throws Exception {
        server.stubFor(post(urlEqualTo(RECOVER)).willReturn(aResponse().withStatus(202).withBody(ACCEPTED).withFixedDelay(1500)));
        assertFalse(assertThrows(CloudRuntimeException.class,
                () -> client().submitVolumeRestore(selection("/local", false), "ablecube1")) instanceof ThirdPartyBackupSubmissionException);
        server.verify(1, postRequestedFor(urlEqualTo(RECOVER)));
    }
}
