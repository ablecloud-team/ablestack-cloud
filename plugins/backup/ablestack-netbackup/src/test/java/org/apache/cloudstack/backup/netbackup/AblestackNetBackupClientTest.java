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
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupRestore;
import org.apache.http.HttpHeaders;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;

import com.cloud.utils.exception.CloudRuntimeException;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit.WireMockRule;

import java.util.UUID;

public class AblestackNetBackupClientTest {
    private static final String API_KEY = "synthetic-netbackup-api-key";
    private static final String CONTENT_TYPE = "application/vnd.netbackup+json;version=12.0";
    private static final String BACKUP_UUID = "d723cbdc-4c7e-49c4-abe1-949b09c4196c";
    // WireMock 2.x retains percent encoding in query parameter names.
    private static final String PAGE_LIMIT_PARAM = "page%5Blimit%5D";

    @Rule
    public WireMockRule server = new WireMockRule(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Test
    public void sourceGateUsesTheApiKeyWithoutABearerPrefix() throws Exception {
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withQueryParam("filter", equalTo("policyName eq 'ablecube4'"))
                .withQueryParam(PAGE_LIMIT_PARAM, equalTo("1000"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                .withHeader(HttpHeaders.ACCEPT, equalTo(CONTENT_TYPE))
                .withHeader("X-API-Key", absent())
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        assertTrue(client().volumeSourceReady("ablecube4"));
        server.verify(1, getRequestedFor(urlPathEqualTo("/netbackup/admin/jobs/")));
    }

    @Test
    public void authenticationFailureDoesNotReleaseTheSourceGate() throws Exception {
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .willReturn(aResponse().withStatus(401)));

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                () -> client().volumeSourceReady("ablecube4"));
        assertTrue(error.getMessage().contains("401"));
    }

    @Test
    public void volumeAndMetadataPoliciesSubmitAndConfirmTheExactArtifactWithRawAuthentication() throws Exception {
        String template = "{\"data\":{\"attributes\":{\"policy\":{\"policyType\":\"Standard\","
                + "\"clients\":[{\"hostName\":\"ablecube4\"}],\"policyAttributes\":{\"active\":true},"
                + "\"schedules\":[{\"scheduleName\":\"full\",\"backupType\":\"Full Backup\"}],"
                + "\"backupSelections\":{\"selections\":[\"/mnt/glue-gfs/ablestack-netbackup/bootstrap/ablecube4\"]}}}}}";
        server.stubFor(get(urlEqualTo("/netbackup/config/policies/ablecube4"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withBody(template)));
        server.stubFor(post(urlEqualTo("/netbackup/config/policies"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(CONTENT_TYPE))
                .willReturn(aResponse().withStatus(201)));
        server.stubFor(post(urlEqualTo("/netbackup/admin/manual-backup"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                .withHeader(HttpHeaders.CONTENT_TYPE, equalTo(CONTENT_TYPE))
                .willReturn(aResponse().withStatus(202)));

        for (boolean metadata : new boolean[]{false, true}) {
            ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
            artifact.backupUuid = BACKUP_UUID;
            artifact.path = "/mnt/glue-gfs/ablestack-netbackup/i-2-317-VM/2026.10.22.09.55.37.495"
                    + (metadata ? "" : "/root.raw");
            AblestackNetBackupClient client = client();
            client.backupVolumeArtifact("ablecube4", "ablecube4", "192.0.2.4", artifact, metadata);
            String policy = artifact.jobId.substring("policy:".length());
            assertTrue(policy.startsWith("ABLESTACK-" + BACKUP_UUID + "-"));
            if (metadata) { assertEquals("ABLESTACK-" + BACKUP_UUID + "-metadata", policy); }
            assertFalse(artifact.completed);
            server.verify(postRequestedFor(urlEqualTo("/netbackup/config/policies"))
                    .withRequestBody(matchingJsonPath("$.data.attributes.policy.backupSelections.selections[0]", equalTo(artifact.path)))
                    .withRequestBody(matchingJsonPath("$.data.attributes.policy.clients[0].hostName", equalTo("ablecube4"))));
            server.verify(postRequestedFor(urlEqualTo("/netbackup/admin/manual-backup"))
                    .withRequestBody(matchingJsonPath("$.data.attributes.policyName", equalTo(policy)))
                    .withRequestBody(matchingJsonPath("$.data.attributes.scheduleName", equalTo("full"))));

            server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                    .withQueryParam("filter", equalTo("policyName eq '" + policy + "'"))
                    .withQueryParam(PAGE_LIMIT_PARAM, equalTo("1000"))
                    .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                    .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"42\"}]}")));
            client.backupVolumeArtifact("ablecube4", "ablecube4", "192.0.2.4", artifact, metadata);
            assertEquals("42", artifact.jobId);

            JSONObject attributes = new JSONObject().put("policyName", policy).put("state", "DONE")
                    .put("status", metadata ? (Object) "0" : 0);
            server.stubFor(get(urlEqualTo("/netbackup/admin/jobs/42"))
                    .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                    .willReturn(aResponse().withStatus(200).withBody(new JSONObject()
                            .put("data", new JSONObject().put("attributes", attributes)).toString())));
            server.stubFor(get(urlPathEqualTo("/netbackup/catalog/images"))
                    .withQueryParam("filter", equalTo("policyName eq '" + policy + "'"))
                    .withQueryParam(PAGE_LIMIT_PARAM, equalTo("99"))
                    .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                    .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"ablecube4_1792630519\","
                            + "\"attributes\":{\"backupTime\":\"2026-10-22T00:55:19Z\"}}]}")));
            client.backupVolumeArtifact("ablecube4", "ablecube4", "192.0.2.4", artifact, metadata);
            assertTrue(artifact.completed);
            assertEquals("ablecube4_1792630519", artifact.externalId);
        }
    }

    @Test
    public void catalogQueryErrorsIncludeTheEndpointAndServerDetailsForBackupAndDeletion() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifactWithCompletedJob();
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withQueryParam(PAGE_LIMIT_PARAM, equalTo("1000"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        server.stubFor(get(urlPathEqualTo("/netbackup/catalog/images"))
                .withQueryParam(PAGE_LIMIT_PARAM, equalTo("99"))
                .willReturn(aResponse().withStatus(400).withBody("{\"errorCode\":8961,"
                        + "\"errorMessage\":\"An invalid API request is encountered.\","
                        + "\"errorDetails\":[\"Limit must be less than 100.\"]}")));

        AblestackNetBackupClient client = client();
        CloudRuntimeException backupError = assertThrows(CloudRuntimeException.class,
                () -> client.backupVolumeArtifact("ablecube4", "ablecube4", "192.0.2.4", artifact, false));
        CloudRuntimeException deletionError = assertThrows(CloudRuntimeException.class,
                () -> client.deleteVolumeArtifact(artifact, false, null, false));
        for (CloudRuntimeException error : new CloudRuntimeException[]{backupError, deletionError}) {
            assertTrue(error.getMessage().contains("400"));
            assertTrue(error.getMessage().contains("/catalog/images"));
            assertTrue(error.getMessage().contains("8961"));
            assertTrue(error.getMessage().contains("Limit must be less than 100."));
        }
        assertFalse(artifact.completed);
        server.verify(0, postRequestedFor(urlEqualTo("/netbackup/catalog/expire-images")));
    }

    @Test
    public void incompleteCatalogPagesCannotCompleteAnArtifactOrPermitDeletion() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifactWithCompletedJob();
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        JSONArray images = new JSONArray();
        for (int i = 0; i < 99; i++) { images.put(new JSONObject().put("id", "image-" + i)); }
        JSONObject fullPage = new JSONObject().put("data", images);
        JSONObject nextPage = new JSONObject().put("data", new JSONArray().put(new JSONObject().put("id", "image-1")))
                .put("links", new JSONObject().put("next", new JSONObject().put("href", "/catalog/images?page%5Boffset%5D=1")));

        for (JSONObject response : new JSONObject[]{fullPage, nextPage}) {
            server.stubFor(get(urlPathEqualTo("/netbackup/catalog/images"))
                    .withQueryParam(PAGE_LIMIT_PARAM, equalTo("99"))
                    .willReturn(aResponse().withStatus(200).withBody(response.toString())));
            AblestackNetBackupClient client = client();
            assertThrows(CloudRuntimeException.class,
                    () -> client.backupVolumeArtifact("ablecube4", "ablecube4", "192.0.2.4", artifact, false));
            assertThrows(CloudRuntimeException.class,
                    () -> client.deleteVolumeArtifact(artifact, false, null, false));
            assertFalse(artifact.completed);
        }
        server.verify(0, postRequestedFor(urlEqualTo("/netbackup/catalog/expire-images")));
    }

    @Test
    public void confirmedEmptyCatalogUsesTheCatalogPageLimitBeforeCompletingDeletion() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifactWithCompletedJob();
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .withQueryParam(PAGE_LIMIT_PARAM, equalTo("1000"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));
        server.stubFor(get(urlPathEqualTo("/netbackup/catalog/images"))
                .withQueryParam(PAGE_LIMIT_PARAM, equalTo("99"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        assertTrue(client().deleteVolumeArtifact(artifact, false, null, false).completed);
        server.verify(getRequestedFor(urlPathEqualTo("/netbackup/catalog/images"))
                .withQueryParam(PAGE_LIMIT_PARAM, equalTo("99")));
    }

    @Test
    public void sourceGateRejectsAJobListWithAnotherPage() throws Exception {
        server.stubFor(get(urlPathEqualTo("/netbackup/admin/jobs/"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[],"
                        + "\"links\":{\"next\":{\"href\":\"/admin/jobs/?page%5Bafter%5D=42\"}}}")));

        assertThrows(CloudRuntimeException.class, () -> client().volumeSourceReady("ablecube4"));
    }

    @Test
    public void catalogLookupResumesTheSavedJobWithoutSubmittingAnotherBackup() throws Exception {
        ThirdPartyBackupManifest.Artifact artifact = artifactWithCompletedJob();
        server.stubFor(get(urlPathEqualTo("/netbackup/catalog/images"))
                .withQueryParam(PAGE_LIMIT_PARAM, equalTo("99"))
                .willReturn(aResponse().withStatus(400)));
        assertThrows(CloudRuntimeException.class,
                () -> client().backupVolumeArtifact("ablecube4", "ablecube4", "192.0.2.4", artifact, false));
        assertFalse(artifact.completed);

        ThirdPartyBackupManifest.Artifact saved = new ThirdPartyBackupManifest.Artifact();
        saved.backupUuid = artifact.backupUuid;
        saved.path = artifact.path;
        saved.jobId = artifact.jobId;
        server.stubFor(get(urlPathEqualTo("/netbackup/catalog/images"))
                .withQueryParam(PAGE_LIMIT_PARAM, equalTo("99"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[{\"id\":\"ablecube4_1792636130\","
                        + "\"attributes\":{\"backupTime\":\"2026-10-22T02:28:50.000Z\"}}]}")));

        client().recoverVolumeArtifact(saved, false);
        assertTrue(saved.completed);
        assertEquals("42", saved.jobId);
        assertEquals("ablecube4_1792636130", saved.externalId);
        assertEquals("2026-10-22T02:28:50.000Z", saved.backupTime);
        server.verify(0, postRequestedFor(urlEqualTo("/netbackup/config/policies")));
        server.verify(0, postRequestedFor(urlEqualTo("/netbackup/admin/manual-backup")));
    }

    @Test
    public void restorePollingRequiresACompletedJobAndAnExplicitZeroStatus() throws Exception {
        for (Object code : new Object[]{0, "0", 1, "1", JSONObject.NULL}) {
            for (String state : new String[]{"ACTIVE", "DONE", "SUSPENDED"}) {
                JSONObject attributes = new JSONObject().put("state", state).put("status", code);
                server.stubFor(get(urlEqualTo("/netbackup/admin/jobs/42"))
                        .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                        .willReturn(aResponse().withStatus(200).withBody(new JSONObject()
                                .put("data", new JSONObject().put("attributes", attributes)).toString())));

                ThirdPartyBackupRestore.Result result = client().pollVolumeRestore("42");
                assertEquals("DONE".equals(state) && "0".equals(String.valueOf(code)), result.completed);
            }
        }
    }

    @Test
    public void imageExpirationUsesRawAuthenticationAndKeepsMpaApprovalPending() throws Exception {
        for (int status : new int[]{200, 204, 202}) {
            server.stubFor(post(urlEqualTo("/netbackup/catalog/expire-images"))
                    .withHeader(HttpHeaders.AUTHORIZATION, equalTo(API_KEY))
                    .withRequestBody(containing("\"backupId\":\"ablecube4_1792630519\""))
                    .withRequestBody(containing("\"copyNumber\":1"))
                    .willReturn(aResponse().withStatus(status).withHeader("X-NetBackup-MPA-Ticket", "ticket-1")));

            AblestackNetBackupClient.ExpireImageResult result = client().expireBackupImage("ablecube4_1792630519", 1);
            assertEquals(status != 202, result.isCompleted());
            if (status == 202) { assertEquals("ticket-1", result.getMpaTicket()); }
        }
    }

    private AblestackNetBackupClient client() throws Exception {
        return new AblestackNetBackupClient("http://localhost:" + server.port() + "/netbackup", API_KEY, 5);
    }

    private ThirdPartyBackupManifest.Artifact artifactWithCompletedJob() {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.backupUuid = BACKUP_UUID;
        artifact.path = "/mnt/glue-gfs/ablestack-netbackup/i-2-317-VM/2026.10.22.09.55.37.495/root.raw";
        artifact.jobId = "42";
        String policy = "ABLESTACK-" + BACKUP_UUID + "-" + UUID.nameUUIDFromBytes(
                artifact.path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.stubFor(get(urlEqualTo("/netbackup/config/policies/" + policy))
                .willReturn(aResponse().withStatus(404)));
        JSONObject attributes = new JSONObject().put("policyName", policy).put("state", "DONE").put("status", 0);
        server.stubFor(get(urlEqualTo("/netbackup/admin/jobs/42"))
                .willReturn(aResponse().withStatus(200).withBody(new JSONObject()
                        .put("data", new JSONObject().put("attributes", attributes)).toString())));
        return artifact;
    }
}
