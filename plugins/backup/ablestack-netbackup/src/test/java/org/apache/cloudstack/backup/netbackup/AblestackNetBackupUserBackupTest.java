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
package org.apache.cloudstack.backup.netbackup;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.cloud.utils.exception.CloudRuntimeException;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.http.HttpHeaders;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;

public class AblestackNetBackupUserBackupTest {
    private static final String POLICY = "prod-host-4";
    private static final String CLIENT = "ablecube4";
    private static final String IMAGE = "ablecube4_1792636130";
    private static final String ROOT = "/mnt/glue-gfs/ablestack-netbackup/i-2-317-VM/2026.10.22.11.12.28.987";

    @Rule public WireMockRule server = new WireMockRule(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Test public void exactFileListSelectsTheCorrectJobAmongConcurrentSharedPolicyJobs() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        jobs(new JSONArray().put(job("41", "mold-transfer", "DONE", 0)).put(job("42", "mold-transfer", "DONE", 0))
                .put(job("43", "schedule", "DONE", 0)));
        files("41", ROOT + "/other-volume.raw");
        files("42", artifact.path);
        stubJob("42", "DONE", 0);
        image(200, imageData());

        client().pollUserBackupArtifact(POLICY, CLIENT, "192.0.2.4", artifact);
        assertEquals("42", artifact.jobId);
        assertEquals(IMAGE, artifact.externalId);
        assertEquals(CLIENT, artifact.sourceHost);
        assertTrue(artifact.completed);
        server.verify(0, postRequestedFor(urlEqualTo("/netbackup/config/policies")));
        server.verify(0, postRequestedFor(urlEqualTo("/netbackup/admin/manual-backup")));
        server.verify(0, getRequestedFor(urlPathEqualTo("/netbackup/catalog/images")));
    }

    @Test public void missingOrAmbiguousExactJobsNeverCompleteAnArtifact() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        jobs(new JSONArray().put(job("41", "mold-transfer", "DONE", 0)));
        files("41", ROOT + "/other-volume.raw");
        client().pollUserBackupArtifact(POLICY, CLIENT, null, artifact);
        assertFalse(artifact.completed);
        assertEquals(AblestackNetBackupClient.userBackupRequestId(artifact) + ":40", artifact.jobId);
        files("41", artifact.path);
        files("42", artifact.path);
        jobs(new JSONArray().put(job("41", "mold-transfer", "DONE", 0)).put(job("42", "mold-transfer", "DONE", 0)));
        assertThrows(CloudRuntimeException.class, () -> client().pollUserBackupArtifact(POLICY, CLIENT, null, artifact));
        assertFalse(artifact.completed);
    }

    @Test public void persistedJobCannotBeLinkedToAnotherVolume() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        artifact.jobId = "42";
        stubJob("42", "DONE", 0);
        files("42", ROOT + "/wrong.raw");
        assertThrows(CloudRuntimeException.class, () -> client().pollUserBackupArtifact(POLICY, CLIENT, null, artifact));
        assertFalse(artifact.completed);
    }

    @Test public void failedAndUnconfirmedJobsNeverAcknowledgeAStagedFile() throws Exception {
        for (Object status : new Object[]{1, JSONObject.NULL}) {
            ThirdPartyBackupManifest.Artifact artifact = artifact();
            artifact.jobId = "42";
            stubJob("42", "DONE", status);
            files("42", artifact.path);
            client().pollUserBackupArtifact(POLICY, CLIENT, null, artifact);
            assertFalse(artifact.completed);
            assertEquals(Integer.valueOf(1).equals(status), artifact.externalTerminal);
        }
    }

    @Test public void sharedHostHistoryUsesTheContinuationCursorBeforeResolvingAnArtifact() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        JSONObject first = new JSONObject().put("data", new JSONArray().put(job("41", "schedule", "DONE", 0)))
                .put("meta", new JSONObject().put("pagination", new JSONObject().put("next", "cursor-1")));
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withQueryParam("page%5Bafter%5D", absent()).willReturn(aResponse().withStatus(200).withBody(first.toString())));
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withQueryParam("page%5Bafter%5D", equalTo("cursor-1"))
                .willReturn(aResponse().withStatus(200).withBody(new JSONObject().put("data",
                        new JSONArray().put(job("42", "mold-transfer", "DONE", 0))).toString())));
        stubJob("42", "DONE", 0);
        files("42", artifact.path);
        image(200, imageData());
        client().pollUserBackupArtifact(POLICY, CLIENT, null, artifact);
        assertTrue(artifact.completed);
        server.verify(getRequestedFor(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withQueryParam("page%5Bafter%5D", equalTo("cursor-1")));
    }

    @Test public void activeTransferDoesNotBlockSourcePreparationButBootstrapDoes() throws Exception {
        jobs(new JSONArray().put(job("41", "schedule", "DONE", 0)).put(job("42", "mold-transfer", "ACTIVE", 0)));
        assertTrue(client().userBackupSourceReady(POLICY));
        jobs(new JSONArray().put(job("41", "schedule", "ACTIVE", 0)));
        assertFalse(client().userBackupSourceReady(POLICY));
    }

    @Test public void delayedRetryAndFailedDoneJobsDoNotKeepTheStagingSlotWaiting() throws Exception {
        jobs(new JSONArray().put(job("78", "schedule", "DONE", 73))
                .put(job("83", "schedule", "WAITING_FOR_RETRY", 74)));
        assertTrue(client().userBackupSourceReady(POLICY));
        assertEquals(null, client().userBackupSourceWaitReason(POLICY));
    }

    @Test public void sourceWaitReasonIdentifiesTheActualActiveBootstrapJob() throws Exception {
        jobs(new JSONArray().put(job("78", "schedule", "WAITING_FOR_RETRY", 73))
                .put(job("83", "schedule", "ACTIVE", 0)));
        String reason = client().userBackupSourceWaitReason(POLICY);
        assertTrue(reason.contains("Job 83 (ACTIVE)"));
        assertTrue(reason.contains(POLICY));
        assertFalse(reason.contains("Job 78"));
        jobs(new JSONArray().put(job("84", "schedule", "QUEUED", 0)));
        assertFalse(client().userBackupSourceReady(POLICY));
        jobs(new JSONArray().put(job("85", "schedule", "UNKNOWN", 0)));
        assertFalse(client().userBackupSourceReady(POLICY));
    }

    @Test public void submissionBoundaryIsReadBeforeTheRequestAndOldJobFileListsAreNotQueried() throws Exception {
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withQueryParam("sort", equalTo("-jobId")).withQueryParam("page%5Blimit%5D", equalTo("1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"40\"}]}")));
        assertEquals("40", client().lastJobId());
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        jobs(new JSONArray().put(job("39", "mold-transfer", "DONE", 0)));
        client().pollUserBackupArtifact(POLICY, CLIENT, null, artifact);
        assertFalse(artifact.completed);
        server.verify(0, getRequestedFor(urlEqualTo("/netbackup/admin/jobs/39/file-lists")));
        server.verify(getRequestedFor(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withQueryParam("filter", equalTo("policyName eq '" + POLICY + "' and jobId gt 40")));
    }

    @Test public void incompleteHistoryWithoutACursorCannotAcknowledgeAnArtifact() throws Exception {
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[],\"links\":{\"next\":{\"href\":\"next\"}}}")));
        assertThrows(CloudRuntimeException.class, () -> client().pollUserBackupArtifact(POLICY, CLIENT, null, artifact()));
    }

    @Test public void userBackupRequiresTheCorrectScheduleAndClientButAllowsSerialJobLimits() throws Exception {
        JSONObject policy = new JSONObject().put("policyType", "Standard")
                .put("clients", new JSONArray().put(new JSONObject().put("hostName", CLIENT)))
                .put("policyAttributes", new JSONObject().put("active", true).put("jobLimit", 1))
                .put("schedules", new JSONArray().put(new JSONObject().put("scheduleName", "mold-transfer").put("backupType", "User Backup")
                        .put("startWindow", new JSONArray().put(new JSONObject().put("dayOfWeek", 1).put("startSeconds", 0).put("durationSeconds", 604800)))));
        stubPolicy(policy);
        client().validateUserBackupPolicy(POLICY, CLIENT, null);
        policy.getJSONArray("schedules").getJSONObject(0).put("backupType", "Full Backup");
        stubPolicy(policy);
        assertThrows(CloudRuntimeException.class, () -> client().validateUserBackupPolicy(POLICY, CLIENT, null));
    }

    @Test public void deletionExpiresOnlyTheSavedImageAndNeverDeletesTheHostPolicy() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        artifact.jobId = "42";
        artifact.externalId = IMAGE;
        artifact.sourceHost = CLIENT;
        artifact.completed = true;
        artifact.externalTerminal = true;
        server.stubFor(get(urlEqualTo("/netbackup/admin/jobs/42")).willReturn(aResponse().withStatus(404)));
        image(200, imageData());
        server.stubFor(post(urlEqualTo("/netbackup/catalog/expire-images"))
                .withRequestBody(containing("\"backupId\":\"" + IMAGE + "\""))
                .willReturn(aResponse().withStatus(202).withHeader("X-NetBackup-MPA-Ticket", "ticket")));
        assertFalse(client().deleteUserBackupArtifact(POLICY, artifact, null, false).completed);
        server.verify(0, deleteRequestedFor(urlEqualTo("/netbackup/config/policies/" + POLICY)));
        server.verify(0, getRequestedFor(urlPathEqualTo("/netbackup/catalog/images")));
        image(404, "");
        assertTrue(client().deleteUserBackupArtifact(POLICY, artifact, "ticket", false).completed);
    }

    @Test public void foreignOrRetainedImagesCannotBeExpired() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        artifact.jobId = "42";
        artifact.externalId = IMAGE;
        artifact.sourceHost = CLIENT;
        artifact.completed = true;
        server.stubFor(get(urlEqualTo("/netbackup/admin/jobs/42")).willReturn(aResponse().withStatus(404)));
        image(200, imageData());
        assertThrows(CloudRuntimeException.class, () -> client().deleteUserBackupArtifact(POLICY, artifact, null, true));
        JSONObject foreign = new JSONObject(imageData());
        foreign.getJSONObject("data").getJSONObject("attributes").put("clientName", "another-host");
        image(200, foreign.toString());
        assertThrows(CloudRuntimeException.class, () -> client().deleteUserBackupArtifact(POLICY, artifact, null, false));
        server.verify(0, postRequestedFor(urlEqualTo("/netbackup/catalog/expire-images")));
    }

    @Test public void activeJobsBlockDeletionAndConfirmedNonSubmissionNeedsNoCatalogMutation() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        artifact.sourceHost = CLIENT;
        artifact.jobId = "42";
        artifact.externalId = IMAGE;
        artifact.completed = true;
        stubJob("42", "ACTIVE", 0);
        assertThrows(CloudRuntimeException.class, () -> client().deleteUserBackupArtifact(POLICY, artifact, null, false));
        artifact.jobId = "not-submitted:" + AblestackNetBackupClient.userBackupRequestId(artifact);
        artifact.externalId = null;
        artifact.completed = false;
        artifact.externalTerminal = true;
        artifact.externalFailure = "bpbackup preflight rejected the request";
        assertTrue(client().deleteUserBackupArtifact(POLICY, artifact, null, false).completed);
        server.verify(0, postRequestedFor(urlEqualTo("/netbackup/catalog/expire-images")));
    }

    private ThirdPartyBackupManifest.Artifact artifact() {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.backupUuid = "adc49c78-f92c-4339-9f6c-6a792ae34904";
        artifact.path = ROOT + "/root.raw";
        artifact.jobId = AblestackNetBackupClient.userBackupRequestId(artifact) + ":40";
        return artifact;
    }

    private JSONObject job(String id, String schedule, String state, Object status) {
        return new JSONObject().put("id", id).put("attributes", new JSONObject().put("policyName", POLICY)
                .put("scheduleName", schedule).put("clientName", CLIENT).put("jobType", "BACKUP")
                .put("state", state).put("status", status).put("backupId", IMAGE));
    }

    private void jobs(JSONArray jobs) {
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("synthetic-key"))
                .willReturn(aResponse().withStatus(200).withBody(new JSONObject().put("data", jobs).toString())));
    }

    private void files(String id, String path) {
        server.stubFor(get(urlEqualTo("/netbackup/admin/jobs/" + id + "/file-lists"))
                .willReturn(aResponse().withStatus(200).withBody(new JSONObject().put("data", new JSONObject()
                        .put("attributes", new JSONObject().put("fileList", new JSONArray().put(path)))).toString())));
    }

    private void stubJob(String id, String state, Object status) {
        server.stubFor(get(urlEqualTo("/netbackup/admin/jobs/" + id))
                .willReturn(aResponse().withStatus(200).withBody(new JSONObject().put("data", job(id, "mold-transfer", state, status)).toString())));
    }

    private String imageData() {
        return new JSONObject().put("data", new JSONObject().put("id", IMAGE).put("attributes", new JSONObject()
                .put("policyName", POLICY).put("clientName", CLIENT).put("backupTime", "2026-10-22T02:28:50.000Z")
                .put("numCopies", 1).put("primaryCopy", 1))).toString();
    }

    private void image(int status, String body) {
        server.stubFor(get(urlEqualTo("/netbackup/catalog/images/" + IMAGE)).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private void stubPolicy(JSONObject policy) {
        server.stubFor(get(urlEqualTo("/netbackup/config/policies/" + POLICY))
                .willReturn(aResponse().withStatus(200).withBody(new JSONObject().put("data", new JSONObject()
                        .put("attributes", new JSONObject().put("policy", policy))).toString())));
    }

    private AblestackNetBackupClient client() throws Exception {
        return new AblestackNetBackupClient("http://localhost:" + server.port() + "/netbackup", "synthetic-key", 5);
    }
}
