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

package org.apache.cloudstack.backup.commvault;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.cloudstack.backup.ThirdPartyBackupVolumeService;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.exception.CloudRuntimeException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

public class AblestackCommvaultClientTest {
    @Rule
    public WireMockRule wireMockRule = new WireMockRule(WireMockConfiguration.wireMockConfig().dynamicPort());

    @Test
    public void getJobRetentionInfoReadsAllCopiesWithOneAdvancedDetailsRequest() throws Exception {
        wireMockRule.stubFor(post(urlEqualTo("/api/Login"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"test-token\"}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Job/1055/AdvancedDetails?infoType=1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"jobRetention\":{\"storagePolicyRetentionInfoList\":["
                        + "{\"storagePolicyId\":4,\"copyRetentionInfoList\":["
                        + "{\"storagePolicyCopyId\":3,\"retentionDays\":1791590564},"
                        + "{\"storagePolicyCopyId\":5,\"retentionDays\":1791590564}]}]}}")));

        AblestackCommvaultClient client = new AblestackCommvaultClient(
                "http://localhost:" + wireMockRule.port() + "/api", "admin", "password", true, 5);
        List<AblestackCommvaultClient.JobRetentionInfo> retentionInfo = client.getJobRetentionInfo("1055");

        assertEquals(2, retentionInfo.size());
        assertEquals("4", retentionInfo.get(0).getStoragePolicyId());
        assertEquals("3", retentionInfo.get(0).getStoragePolicyCopyId());
        assertEquals("1791590564", retentionInfo.get(0).getRetainedUntil());
        assertEquals("5", retentionInfo.get(1).getStoragePolicyCopyId());
        wireMockRule.verify(1, getRequestedFor(urlEqualTo("/api/Job/1055/AdvancedDetails?infoType=1")));
    }

    @Test
    public void getRequestReauthenticatesAndRetriesOnce() throws Exception {
        stubTokenRenewal();
        String path = "/api/Job/1055/AdvancedDetails?infoType=1";
        wireMockRule.stubFor(get(urlEqualTo(path)).withHeader("Authtoken", equalTo("old-token"))
                .willReturn(aResponse().withStatus(401)));
        wireMockRule.stubFor(get(urlEqualTo(path)).withHeader("Authtoken", equalTo("new-token"))
                .willReturn(aResponse().withStatus(200).withBody("{\"jobRetention\":{\"storagePolicyRetentionInfoList\":[]}}")));

        assertTrue(newClient().getJobRetentionInfo("1055").isEmpty());
        wireMockRule.verify(2, getRequestedFor(urlEqualTo(path)));
        wireMockRule.verify(2, postRequestedFor(urlEqualTo("/api/Login")));
    }

    @Test
    public void jsonPostReauthenticatesAndRetriesOnce() throws Exception {
        stubTokenRenewal();
        String path = "/api/JobDetails";
        wireMockRule.stubFor(post(urlEqualTo(path)).withHeader("Authtoken", equalTo("old-token"))
                .willReturn(aResponse().withStatus(401)));
        wireMockRule.stubFor(post(urlEqualTo(path)).withHeader("Authtoken", equalTo("new-token"))
                .willReturn(aResponse().withStatus(200).withBody("{\"job\":{}}")));

        assertEquals("{\"job\":{}}", newClient().getJobDetails("1055"));
        wireMockRule.verify(2, postRequestedFor(urlEqualTo(path)));
        wireMockRule.verify(2, postRequestedFor(urlEqualTo("/api/Login")));
    }

    @Test
    public void poolWaitTimeoutIsDistinguishedFromACommvaultServerTimeout() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        HttpClient transport = Mockito.mock(HttpClient.class);
        Mockito.when(transport.execute(Mockito.any(HttpUriRequest.class), Mockito.<ResponseHandler<HttpResponse>>any()))
                .thenThrow(new ConnectionPoolTimeoutException("Timeout waiting for connection from pool"));
        ReflectionTestUtils.setField(client, "httpClient", transport);

        ServerApiException error = assertThrows(ServerApiException.class, () -> client.getClientId("ablecube4"));
        assertEquals("Commvault API connection pool wait timed out. No connection was available; please retry the operation.", error.getMessage());
    }

    @Test
    public void successfulDeleteResponsesReleaseConnectionsBeforeAnotherLookup() throws Exception {
        stubTokenRenewal();
        wireMockRule.stubFor(delete(urlEqualTo("/api/Backupset/9"))
                .willReturn(aResponse().withStatus(200).withBody("{\"errorCode\":0}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Client"))
                .willReturn(aResponse().withStatus(200).withBody("{\"clientProperties\":[]}")));

        AblestackCommvaultClient client = newClient();
        // More calls than HttpClient's default two connections per route; callers do not read DELETE bodies.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertTrue(client.deleteBackupSet("9"));
        }
        assertNull(client.getClientId("ablecube4"));
        wireMockRule.verify(5, deleteRequestedFor(urlEqualTo("/api/Backupset/9")));
        wireMockRule.verify(1, getRequestedFor(urlEqualTo("/api/Client")));
    }

    @Test
    public void unavailableClientResponsesReleaseConnectionsForLaterRequests() throws Exception {
        stubTokenRenewal();
        wireMockRule.stubFor(get(urlEqualTo("/api/Client"))
                .willReturn(aResponse().withStatus(200).withBody("{\"clientProperties\":["
                        + "{\"client\":{\"clientEntity\":{\"clientId\":\"42\",\"clientName\":\"ablecube4\"}}}]}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Client/42"))
                .willReturn(aResponse().withStatus(404).withBody("{\"errorCode\":4}")));

        AblestackCommvaultClient client = newClient();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertNull(client.getClientId("ablecube4"));
        }
        wireMockRule.stubFor(get(urlEqualTo("/api/Client/42"))
                .willReturn(aResponse().withStatus(200).withBody("{\"clientProperties\":[{}]}")));
        assertEquals("42", client.getClientId("ablecube4"));
        wireMockRule.verify(6, getRequestedFor(urlEqualTo("/api/Client/42")));
    }

    @Test
    public void deleteReauthenticatesAndRetriesOnce() throws Exception {
        stubTokenRenewal();
        String path = "/api/Backupset/9";
        wireMockRule.stubFor(delete(urlEqualTo(path)).withHeader("Authtoken", equalTo("old-token"))
                .willReturn(aResponse().withStatus(401)));
        wireMockRule.stubFor(delete(urlEqualTo(path)).withHeader("Authtoken", equalTo("new-token"))
                .willReturn(aResponse().withStatus(200)));

        assertTrue(newClient().deleteBackupSet("9"));
        wireMockRule.verify(2, deleteRequestedFor(urlEqualTo(path)));
        wireMockRule.verify(2, postRequestedFor(urlEqualTo("/api/Login")));
    }

    @Test
    public void secondUnauthorizedResponseDoesNotTriggerAnotherRetry() throws Exception {
        stubTokenRenewal();
        String path = "/api/Job/1055/AdvancedDetails?infoType=1";
        wireMockRule.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(401)));

        assertThrows(ServerApiException.class, () -> newClient().getJobRetentionInfo("1055"));
        wireMockRule.verify(2, getRequestedFor(urlEqualTo(path)));
        wireMockRule.verify(2, postRequestedFor(urlEqualTo("/api/Login")));
    }

    @Test
    public void catalogHttpFailureIncludesStatusAndServiceErrorInsteadOfReportingMissing() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = newInventory();
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"errorCode\":12,\"errorMessage\":\"Invalid browse\\nrequest\",\"other\":\"not included\"}")));

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> inventory.exists(artifact()));
        assertEquals("Commvault artifact catalog query failed (/DoBrowse, HTTP 400); errorCode=12; Invalid browse request", error.getMessage());
    }

    @Test
    public void emptyCatalogResponseRemainsAQueryFailure() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = newInventory();
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse")).willReturn(aResponse().withStatus(200)));

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> inventory.exists(artifact()));
        assertEquals("Commvault artifact catalog query failed (/DoBrowse, HTTP 200): empty response", error.getMessage());
    }

    @Test
    public void nonJsonCatalogHttpFailureKeepsStatusWithoutIncludingHtml() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = newInventory();
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse"))
                .willReturn(aResponse().withStatus(404).withBody("<html>Proxy error page</html>")));

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> inventory.exists(artifact()));
        assertEquals("Commvault artifact catalog query failed (/DoBrowse, HTTP 404)", error.getMessage());
    }

    @Test
    public void catalogAvailabilityRequiresTheSavedPathAndJob() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = newInventory();
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse")).atPriority(10)
                .willReturn(aResponse().withStatus(400).withBody("{\"errorMessage\":\"Invalid JSON browse types\"}")));
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse"))
                .atPriority(1)
                .withRequestBody(equalToJson("{\"opType\":0,\"options\":{\"showDeletedFiles\":true,\"restoreIndex\":true}}", true, true))
                .willReturn(aResponse().withStatus(200).withBody(catalogResult("1127"))));

        assertTrue(inventory.exists(artifact()));
        wireMockRule.verify(postRequestedFor(urlEqualTo("/api/DoBrowse"))
                .withRequestBody(matchingJsonPath("$.paths[0].path", equalTo("/mnt/staging/vm/recovery-point")))
                .withRequestBody(matchingJsonPath("$.timeRange.toTime", equalTo("1791791903"))));

        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse"))
                .atPriority(1)
                .withRequestBody(equalToJson("{\"opType\":0,\"options\":{\"showDeletedFiles\":true,\"restoreIndex\":true}}", true, true))
                .willReturn(aResponse().withStatus(200).withBody(catalogResult("9999"))));
        assertThrows(CloudRuntimeException.class, () -> newInventory().exists(artifact()));
    }

    @Test
    public void stagedDeltasAndMetadataUseFullJobsAndResumeTheSavedJobWithoutResubmission() throws Exception {
        stubTokenRenewal();
        stubVmClient();
        wireMockRule.stubFor(get(urlEqualTo("/api/Subclient?clientId=42"))
                .willReturn(aResponse().withStatus(200).withBody("{\"subClientProperties\":[{\"subClientEntity\":{"
                        + "\"subclientId\":2,\"applicationId\":29,\"backupsetId\":3,\"instanceId\":4,"
                        + "\"clientName\":\"ablecube4\",\"backupsetName\":\"i-2-220-VM\",\"subclientName\":\"default\","
                        + "\"entityInfo\":{\"companyId\":0}}}]}")));
        wireMockRule.stubFor(post(urlEqualTo("/api/Subclient/2"))
                .willReturn(aResponse().withStatus(200).withBody("{\"response\":[{\"errorCode\":0}]}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/V4/ServerPlan/7"))
                .willReturn(aResponse().withStatus(200).withBody("{\"plan\":{\"name\":\"staging-plan\"}}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/V2/StoragePolicy"))
                .willReturn(aResponse().withStatus(200).withBody("{\"policies\":[{\"storagePolicy\":{"
                        + "\"storagePolicyName\":\"staging-plan\",\"storagePolicyId\":8}}]}")));
        AblestackCommvaultClient client = newClient();
        int submissions = 0;
        for (String path : List.of("/staging/vm/point/root.rbdiff", "/staging/vm/point/root.qcow2", "/staging/vm/point")) {
            String jobId = String.valueOf(1200 + submissions);
            wireMockRule.stubFor(post(urlEqualTo("/api/CreateTask"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"jobIds\":[" + jobId + "]}")));
            ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
            artifact.path = path;
            artifact.backupUuid = "9474d3f1-450b-49b9-bd7b-4f714f403a8d";

            client.backupVolumeArtifact("ablecube4", "i-2-220-VM", "7", artifact);

            assertEquals(jobId, artifact.jobId);
            assertFalse(artifact.completed);
            wireMockRule.verify(postRequestedFor(urlEqualTo("/api/Subclient/2"))
                    .withRequestBody(matchingJsonPath("$.subClientProperties.content[0].path", equalTo(path))));
            wireMockRule.stubFor(post(urlEqualTo("/api/JobDetails"))
                    .withRequestBody(equalToJson("{\"jobId\":" + jobId + "}"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"job\":{\"jobDetail\":{"
                            + "\"progressInfo\":{\"state\":\"Running\"}}}}")));

            // A new client represents the next polling pass after a management restart.
            ThirdPartyBackupManifest.Artifact saved = new ObjectMapper().convertValue(artifact, ThirdPartyBackupManifest.Artifact.class);
            newClient().backupVolumeArtifact(null, null, null, saved);

            assertEquals(jobId, saved.jobId);
            wireMockRule.verify(++submissions, postRequestedFor(urlEqualTo("/api/CreateTask"))
                    .withRequestBody(matchingJsonPath("$.taskInfo.subTasks[0].options.backupOpts",
                            equalToJson("{\"backupLevel\":\"FULL\",\"runIncrementalBackup\":false,\"forceFullBackup\":true}"))));
            wireMockRule.verify(submissions, postRequestedFor(urlEqualTo("/api/CreateTask")));
        }
    }

    @Test
    public void completedBackupResponseSavesTheCapturedEndTime() throws Exception {
        capturedJobInventory();
        ThirdPartyBackupManifest.Artifact artifact = capturedJobArtifact();

        newClient().backupVolumeArtifact(null, null, null, artifact);

        assertTrue(artifact.completed);
        assertEquals("1186", artifact.externalId);
        assertEquals("1792122679", artifact.backupTime);
    }

    @Test
    public void missingCatalogTimeIsReadFromTheExactCompletedBackupJob() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = capturedJobInventory();
        ThirdPartyBackupManifest.Artifact artifact = capturedJobArtifact();

        assertTrue(inventory.exists(artifact));

        assertNull(artifact.backupTime);
        wireMockRule.verify(1, postRequestedFor(urlEqualTo("/api/JobDetails"))
                .withRequestBody(equalToJson("{\"jobId\":1186}")));
        wireMockRule.verify(postRequestedFor(urlEqualTo("/api/DoBrowse"))
                .withRequestBody(matchingJsonPath("$.timeRange.toTime", equalTo("1792122679"))));
    }

    @Test
    public void invalidSavedCatalogTimesAreRecheckedWithoutChangingTheSavedArtifact() throws Exception {
        for (String value : List.of("", "invalid", "0", "-1", "999999999999999999999999999")) {
            ThirdPartyBackupManifest.Artifact artifact = capturedJobArtifact();
            artifact.backupTime = value;

            assertTrue(capturedJobInventory().exists(artifact));
            assertEquals(value, artifact.backupTime);
        }
        wireMockRule.verify(5, postRequestedFor(urlEqualTo("/api/JobDetails")));
    }

    @Test
    public void validSavedCatalogTimeKeepsItsOriginalPointInTime() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = capturedJobInventory();
        ThirdPartyBackupManifest.Artifact artifact = capturedJobArtifact();
        artifact.backupTime = "1791791903";

        assertTrue(inventory.exists(artifact));

        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/JobDetails")));
        wireMockRule.verify(postRequestedFor(urlEqualTo("/api/DoBrowse"))
                .withRequestBody(matchingJsonPath("$.timeRange.toTime", equalTo("1791791903"))));
    }

    @Test
    public void anotherJobRestoreJobAndIncompleteJobCannotSupplyTheBackupTime() throws Exception {
        for (String changed : List.of("jobId", "operationType", "jobType", "state")) {
            ThirdPartyBackupVolumeService.ArtifactInventory inventory = capturedJobInventory();
            ObjectNode response = capturedBackupJobDetails();
            if ("state".equals(changed)) {
                ((ObjectNode) response.at("/job/jobDetail/progressInfo")).put("state", "Running");
            } else {
                ((ObjectNode) response.at("/job/jobDetail/generalInfo")).put(changed, "jobId".equals(changed) ? "1189" : "Restore");
            }
            stubBackupJobDetails(response);

            assertThrows(CloudRuntimeException.class, () -> inventory.exists(capturedJobArtifact()));
        }
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/DoBrowse")));
    }

    @Test
    public void anotherHostVmOrSubclientCannotSupplyTheBackupTime() throws Exception {
        for (String field : List.of("clientName", "clientId", "applicationId", "backupsetName", "backupsetId", "instanceId", "subclientId")) {
            ThirdPartyBackupVolumeService.ArtifactInventory inventory = capturedJobInventory();
            ObjectNode response = capturedBackupJobDetails();
            ((ObjectNode) response.at("/job/jobDetail/generalInfo/subclient")).put(field, "different-source");
            stubBackupJobDetails(response);

            assertThrows(CloudRuntimeException.class, () -> inventory.exists(capturedJobArtifact()));
        }
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/DoBrowse")));
    }

    @Test
    public void unavailableJobDetailsRemainAQueryFailure() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = capturedJobInventory();
        wireMockRule.stubFor(post(urlEqualTo("/api/JobDetails")).willReturn(aResponse().withStatus(503)));

        assertThrows(CloudRuntimeException.class, () -> inventory.exists(capturedJobArtifact()));

        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/DoBrowse")));
    }

    @Test
    public void anUnconfirmedEndTimeCannotUseTheCurrentTimeOrReportMissing() throws Exception {
        for (String value : List.of("", "0", "-1", "invalid", "999999999999999999999999999")) {
            ThirdPartyBackupVolumeService.ArtifactInventory inventory = capturedJobInventory();
            ObjectNode response = capturedBackupJobDetails();
            ((ObjectNode) response.at("/job/jobDetail/detailInfo")).put("endTime", value);
            stubBackupJobDetails(response);

            assertThrows(CloudRuntimeException.class, () -> inventory.exists(capturedJobArtifact()));
        }
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/DoBrowse")));
    }

    private ObjectNode capturedBackupJobDetails() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/commvault/job-1186-backup-detail.json")) {
            return (ObjectNode) new ObjectMapper().readTree(stream);
        }
    }

    private void stubBackupJobDetails(ObjectNode response) {
        wireMockRule.stubFor(post(urlEqualTo("/api/JobDetails"))
                .withRequestBody(equalToJson("{\"jobId\":1186}"))
                .willReturn(aResponse().withStatus(200).withBody(response.toString())));
    }

    private ThirdPartyBackupManifest.Artifact capturedJobArtifact() {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        artifact.sourceHost = "ablecube3";
        artifact.jobId = "1186";
        artifact.externalId = "1186";
        artifact.backupTime = null;
        return artifact;
    }

    private ThirdPartyBackupVolumeService.ArtifactInventory capturedJobInventory() throws Exception {
        wireMockRule.stubFor(post(urlEqualTo("/api/Login"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"test-token\"}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Client"))
                .willReturn(aResponse().withStatus(200).withBody("{\"clientProperties\":[{\"client\":{\"clientEntity\":{\"clientId\":12,\"clientName\":\"ablecube3\"}}}]}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Client/12"))
                .willReturn(aResponse().withStatus(200).withBody("{\"clientProperties\":[{}]}")));
        ObjectNode response = capturedBackupJobDetails();
        ObjectNode clients = new ObjectMapper().createObjectNode();
        clients.putArray("subClientProperties").addObject().set("subClientEntity", response.at("/job/jobDetail/generalInfo/subclient"));
        wireMockRule.stubFor(get(urlEqualTo("/api/Subclient?clientId=12"))
                .willReturn(aResponse().withStatus(200).withBody(clients.toString())));
        stubBackupJobDetails(response);
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse"))
                .willReturn(aResponse().withStatus(200).withBody(catalogResult("1186"))));
        return newClient().volumeArtifactInventory("i-2-217-VM");
    }

    @Test
    public void metadataCatalogBrowseChecksTheManifestWithJsonOperationTypes() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = newInventory();
        ThirdPartyBackupManifest.Artifact metadata = artifact();
        metadata.path = "/mnt/staging/vm/recovery-point";
        metadata.checkpointName = "recovery-point";
        metadata.jobId = "1129";
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse"))
                .withRequestBody(equalToJson("{\"opType\":0,\"options\":{\"showDeletedFiles\":true,\"restoreIndex\":true},"
                        + "\"paths\":[{\"path\":\"/mnt/staging/vm/recovery-point\"}]}", true, true))
                .willReturn(aResponse().withStatus(200).withBody(catalogResult("1129").replace("root.qcow2", "backup-manifest.json"))));

        assertTrue(inventory.exists(metadata));
    }

    @Test
    public void onlyACompleteEmptyCatalogResultReportsMissing() throws Exception {
        ThirdPartyBackupVolumeService.ArtifactInventory inventory = newInventory();
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse"))
                .willReturn(aResponse().withStatus(200).withBody("{\"browseResponses\":["
                        + "{\"respType\":0,\"browseResult\":{\"queryId\":\"dataQuery\",\"dataResultSet\":[]}},"
                        + "{\"respType\":0,\"browseResult\":{\"queryId\":\"countQuery\",\"aggrResultSet\":{\"result\":0}}}]}")));

        assertFalse(inventory.exists(artifact()));
    }

    @Test
    public void capturedJsonCatalogResponseConfirmsTheRootArtifact() throws Exception {
        stubCatalogResponse(capturedCatalogResult());

        assertTrue(newInventory().exists(capturedArtifact()));
        wireMockRule.verify(postRequestedFor(urlEqualTo("/api/DoBrowse"))
                .withRequestBody(matchingJsonPath("$.paths[0].path", equalTo(
                        "/mnt/glue-gfs/ablestack-commvault/i-2-220-VM/2026.10.12.16.55.52.320")))
                .withRequestBody(matchingJsonPath("$.timeRange.toTime", equalTo("1791791903"))));
    }

    @Test
    public void completeEmptyJsonCatalogWithNotificationReportsMissing() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ((ArrayNode) response.at("/browseResponses/0/browseResult/dataResultSet")).removeAll();
        ((ObjectNode) response.at("/browseResponses/1/browseResult/aggrResultSet/0"))
                .put("result", 0).put("count", 0);
        stubCatalogResponse(response);

        assertFalse(newInventory().exists(capturedArtifact()));
    }

    @Test
    public void jsonAggregateCountWithoutLegacyResultFieldIsSupported() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ((ObjectNode) response.at("/browseResponses/1/browseResult/aggrResultSet/0")).remove("result");
        stubCatalogResponse(response);

        assertTrue(newInventory().exists(capturedArtifact()));
    }

    @Test
    public void completionNotificationAloneCannotReportMissing() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ArrayNode responses = response.withArray("browseResponses");
        responses.remove(0);
        responses.remove(0);
        stubCatalogResponse(response);

        assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
    }

    @Test
    public void completionNotificationDoesNotHideServiceFailure() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ((ObjectNode) response.at("/browseResponses/2"))
                .put("errorCode", 7).put("errorMessage", "Browse session failed");
        stubCatalogResponse(response);

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
        assertTrue(error.getMessage().contains("respType=5"));
        assertTrue(error.getMessage().contains("errorCode=7"));
        assertTrue(error.getMessage().contains("Browse session failed"));
    }

    @Test
    public void browseServiceErrorsExposeTheNestedReason() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ObjectNode errorResponse = response.withArray("browseResponses").removeAll().addObject().put("respType", 2);
        errorResponse.putArray("messages").addObject().put("errorCode", 19)
                .put("errorMessage", "Index restore failed\nretry later").put("other", "not included");
        stubCatalogResponse(response);

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
        assertTrue(error.getMessage().contains("respType=2"));
        assertTrue(error.getMessage().contains("errorCode=19"));
        assertTrue(error.getMessage().contains("Index restore failed retry later"));
        assertFalse(error.getMessage().contains("not included"));
    }

    @Test
    public void completionNotificationCannotReplaceTheCountQuery() throws Exception {
        ObjectNode response = capturedCatalogResult();
        response.withArray("browseResponses").remove(1);
        stubCatalogResponse(response);

        assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
    }

    @Test
    public void browseWithAdditionalPagesCannotReportMissing() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ((ArrayNode) response.at("/browseResponses/0/browseResult/dataResultSet")).removeAll();
        ((ObjectNode) response.at("/browseResponses/0/browseResult")).put("hasMoreData", true);
        ((ObjectNode) response.at("/browseResponses/1/browseResult/aggrResultSet/0"))
                .put("result", 0).put("count", 0);
        stubCatalogResponse(response);

        assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
    }

    @Test
    public void arrayAggregatesRejectAmbiguousMalformedAndMismatchedCounts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String aggregate : List.of("[]", "[{\"result\":1},{\"result\":1}]", "[{\"result\":-1}]",
                "[{\"result\":1.5}]", "[{\"result\":\"invalid\"}]", "[{\"result\":0,\"count\":1}]",
                "[{\"result\":2,\"count\":2}]")) {
            ObjectNode response = capturedCatalogResult();
            ((ObjectNode) response.at("/browseResponses/1/browseResult")).set("aggrResultSet", mapper.readTree(aggregate));
            stubCatalogResponse(response);

            assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
        }
    }

    @Test
    public void capturedJsonCatalogStillRequiresTheSavedJob() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ((ObjectNode) response.at("/browseResponses/0/browseResult/dataResultSet/0/advancedData")).put("backupJobId", 9999);
        stubCatalogResponse(response);

        assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
    }

    @Test
    public void unknownNotificationTypesAreStillRejected() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ((ObjectNode) response.at("/browseResponses/2")).put("respType", 1);
        stubCatalogResponse(response);

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
        assertTrue(error.getMessage().contains("respType=1"));
    }

    @Test
    public void completionNotificationWithBrowseResultsIsRejected() throws Exception {
        ObjectNode response = capturedCatalogResult();
        ((ObjectNode) response.at("/browseResponses/2")).putObject("browseResult").put("queryId", "dataQuery");
        stubCatalogResponse(response);

        assertThrows(CloudRuntimeException.class, () -> newInventory().exists(capturedArtifact()));
    }

    private ObjectNode capturedCatalogResult() throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/commvault/catalog-browse-root.json")) {
            return (ObjectNode) new ObjectMapper().readTree(stream);
        }
    }

    private ThirdPartyBackupManifest.Artifact capturedArtifact() {
        ThirdPartyBackupManifest.Artifact artifact = artifact();
        artifact.path = "/mnt/glue-gfs/ablestack-commvault/i-2-220-VM/2026.10.12.16.55.52.320/"
                + "root.79ea9909-2abc-4941-b008-52a750654224.qcow2";
        return artifact;
    }

    private void stubCatalogResponse(JsonNode response) {
        wireMockRule.stubFor(post(urlEqualTo("/api/DoBrowse"))
                .willReturn(aResponse().withStatus(200).withBody(response.toString())));
    }

    private ThirdPartyBackupVolumeService.ArtifactInventory newInventory() throws Exception {
        wireMockRule.stubFor(post(urlEqualTo("/api/Login"))
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"test-token\"}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Client"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"clientProperties\":[{\"client\":{\"clientEntity\":{\"clientId\":1,\"clientName\":\"ablecube4\"}}}]}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Client/1"))
                .willReturn(aResponse().withStatus(200).withBody("{\"clientProperties\":[{}]}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Subclient?clientId=1"))
                .willReturn(aResponse().withStatus(200)
                        .withBody("{\"subClientProperties\":[{\"subClientEntity\":{\"backupsetName\":\"i-2-220-VM\","
                                + "\"applicationId\":29,\"subclientId\":2,\"backupsetId\":3,\"instanceId\":4}}]}")));
        return newClient().volumeArtifactInventory("i-2-220-VM");
    }

    private ThirdPartyBackupManifest.Artifact artifact() {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.path = "/mnt/staging/vm/recovery-point/root.qcow2";
        artifact.sourceHost = "ablecube4";
        artifact.jobId = "1127";
        artifact.backupTime = "1791791903";
        artifact.completed = true;
        return artifact;
    }

    private String catalogResult(String jobId) {
        return "{\"browseResponses\":[{\"respType\":0,\"browseResult\":{\"queryId\":\"dataQuery\",\"dataResultSet\":["
                + "{\"displayPath\":\"/mnt/staging/vm/recovery-point/root.qcow2\",\"advancedData\":{\"backupJobId\":\"" + jobId + "\"}}]}},"
                + "{\"respType\":0,\"browseResult\":{\"queryId\":\"countQuery\",\"aggrResultSet\":{\"result\":1}}}]}";
    }

    private void stubTokenRenewal() {
        wireMockRule.stubFor(post(urlEqualTo("/api/Login"))
                .inScenario("token renewal")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"old-token\"}"))
                .willSetStateTo("expired"));
        wireMockRule.stubFor(post(urlEqualTo("/api/Login"))
                .inScenario("token renewal")
                .whenScenarioStateIs("expired")
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"new-token\"}")));
    }

    private AblestackCommvaultClient newClient() throws Exception {
        return new AblestackCommvaultClient("http://localhost:" + wireMockRule.port() + "/api", "admin", "password", true, 5);
    }

    @Test
    public void offeringReassignmentReusesTheExistingBackupSetWithoutChangingContent() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet(vmBackupSet("7"));

        assertTrue(newClient().ensureVmBackupSet("ablecube4", "i-2-220-VM", "7"));

        wireMockRule.verify(1, getRequestedFor(urlEqualTo("/api/Backupset?clientName=ablecube4")));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset")));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset/9")));
    }

    @Test
    public void offeringReassignmentDoesNotChangeAMismatchedOrUnconfirmedPlan() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        for (String plan : List.of("8", "0", "")) {
            stubVmBackupSet(vmBackupSet(plan));
            stubVmBackupSetDetails("9", vmBackupSet(plan));
            CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                    () -> client.ensureVmBackupSet("ablecube4", "i-2-220-VM", "7"));
            assertTrue(error.getMessage().contains("8".equals(plan) ? "Plan does not match" : "Unable to confirm"));
        }
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset")));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset/9")));
    }

    @Test
    public void missingBackupSetIsCreatedOnTheRequestedHostAndVerified() throws Exception {
        stubTokenRenewal();
        stubVmClient();
        wireMockRule.stubFor(get(urlEqualTo("/api/Backupset?clientName=ablecube4"))
                .inScenario("backupset creation").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"backupsetProperties\":[]}")));
        wireMockRule.stubFor(post(urlEqualTo("/api/Backupset"))
                .inScenario("backupset creation").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"response\":[{\"errorCode\":0}]}"))
                .willSetStateTo("created"));
        wireMockRule.stubFor(get(urlEqualTo("/api/Backupset?clientName=ablecube4"))
                .inScenario("backupset creation").whenScenarioStateIs("created")
                .willReturn(aResponse().withStatus(200).withBody(vmBackupSet(""))));
        stubVmBackupSetDetails("9", vmBackupSet("7"));

        assertTrue(newClient().ensureVmBackupSet("ablecube4", "i-2-220-VM", "7"));

        wireMockRule.verify(1, postRequestedFor(urlEqualTo("/api/Backupset"))
                .withRequestBody(matchingJsonPath("$.backupSetInfo.backupSetEntity.clientId", equalTo("42")))
                .withRequestBody(matchingJsonPath("$.backupSetInfo.backupSetEntity.backupsetName", equalTo("i-2-220-VM")))
                .withRequestBody(matchingJsonPath("$.backupSetInfo.planEntity.planId", equalTo("7"))));
        wireMockRule.verify(2, getRequestedFor(urlEqualTo("/api/Backupset?clientName=ablecube4")));
        wireMockRule.verify(1, getRequestedFor(urlEqualTo("/api/Backupset/9")));
    }

    @Test
    public void capturedInventoryWithoutPlanReusesTheBackupSetUsingItsDetails() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet(capturedBackupSetResponse("backupsets-plan-omitted.json").toString());
        stubVmBackupSetDetails("30", capturedBackupSetResponse("backupset-30-detail.json").toString());

        assertEquals("30", newClient().prepareVmBackupSet("ablecube4", "i-2-220-VM", "1"));

        wireMockRule.verify(1, getRequestedFor(urlEqualTo("/api/Backupset/30")));
        wireMockRule.verify(0, getRequestedFor(urlEqualTo("/api/Backupset/25")));
        wireMockRule.verify(0, getRequestedFor(urlEqualTo("/api/Backupset/28")));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset")));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset/30")));
    }

    @Test
    public void detailedPlanMismatchReportsBothIdsWithoutChangingTheBackupSet() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet(capturedBackupSetResponse("backupsets-plan-omitted.json").toString());
        stubVmBackupSetDetails("30", capturedBackupSetResponse("backupset-30-detail.json").toString());

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                () -> newClient().prepareVmBackupSet("ablecube4", "i-2-220-VM", "2"));

        assertTrue(error.getMessage().contains("expectedPlanId=[2]"));
        assertTrue(error.getMessage().contains("actualPlanId=[1]"));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset")));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset/30")));
    }

    @Test
    public void missingDetailedPlanRemainsUnconfirmedAndNeverCreatesAReplacement() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet(vmBackupSet(""));
        stubVmBackupSetDetails("9", vmBackupSet(""));

        CloudRuntimeException error = assertThrows(CloudRuntimeException.class,
                () -> newClient().prepareVmBackupSet("ablecube4", "i-2-220-VM", "7"));

        assertTrue(error.getMessage().contains("Unable to confirm"));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset")));
    }

    @Test
    public void invalidOrFailedDetailsCannotSupplyTheSelectedBackupSetsPlan() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet(capturedBackupSetResponse("backupsets-plan-omitted.json").toString());
        AblestackCommvaultClient client = newClient();
        for (String field : List.of("backupsetId", "backupsetName", "clientName", "applicationId", "clientId", "instanceId")) {
            ObjectNode response = capturedBackupSetResponse("backupset-30-detail.json");
            ((ObjectNode) response.at("/backupsetProperties/0/backupSetEntity")).put(field, "unexpected");
            stubVmBackupSetDetails("30", response.toString());
            assertThrows(CloudRuntimeException.class, () -> client.prepareVmBackupSet("ablecube4", "i-2-220-VM", "1"));
        }
        for (String body : List.of("{}", "null", "invalid-json", "{\"backupsetProperties\":[]}",
                "{\"errorCode\":4,\"backupsetProperties\":[]}")) {
            stubVmBackupSetDetails("30", body);
            assertThrows(CloudRuntimeException.class, () -> client.prepareVmBackupSet("ablecube4", "i-2-220-VM", "1"));
        }
        wireMockRule.stubFor(get(urlEqualTo("/api/Backupset/30"))
                .willReturn(aResponse().withStatus(500).withBody("server unavailable")));
        assertThrows(ServerApiException.class, () -> client.prepareVmBackupSet("ablecube4", "i-2-220-VM", "1"));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset")));
    }

    @Test
    public void cleanupInventoryResolvesDetailedPlansAndSkipsTheDefaultBackupSet() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet(capturedBackupSetResponse("backupsets-plan-omitted.json").toString());
        ObjectNode otherVm = capturedBackupSetResponse("backupset-30-detail.json");
        ((ObjectNode) otherVm.at("/backupsetProperties/0/backupSetEntity"))
                .put("backupsetId", 28).put("backupsetName", "i-2-218-VM");
        stubVmBackupSetDetails("28", otherVm.toString());
        stubVmBackupSetDetails("30", capturedBackupSetResponse("backupset-30-detail.json").toString());

        List<AblestackCommvaultClient.VmBackupSet> inventory = newClient().listVmBackupSets("ablecube4");

        assertEquals(3, inventory.size());
        assertEquals("", inventory.get(0).planId);
        assertEquals("1", inventory.get(1).planId);
        assertEquals("1", inventory.get(2).planId);
        wireMockRule.verify(0, getRequestedFor(urlEqualTo("/api/Backupset/25")));
        wireMockRule.verify(1, getRequestedFor(urlEqualTo("/api/Backupset/28")));
        wireMockRule.verify(1, getRequestedFor(urlEqualTo("/api/Backupset/30")));
    }

    @Test
    public void failedOrMalformedInventoryNeverCreatesAReplacementBackupSet() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        for (String body : List.of("{}", "null", "invalid-json", "{\"backupsetProperties\":null}",
                "{\"backupsetProperties\":[{}]}", "{\"backupsetProperties\":[{\"backupSetEntity\":{\"backupsetName\":\"vm\"}}]}",
                "{\"errorCode\":4,\"backupsetProperties\":[]}")) {
            stubVmBackupSet(body);
            assertThrows(CloudRuntimeException.class, () -> client.ensureVmBackupSet("ablecube4", "i-2-220-VM", "7"));
        }
        wireMockRule.stubFor(get(urlEqualTo("/api/Backupset?clientName=ablecube4"))
                .willReturn(aResponse().withStatus(500).withBody("server unavailable")));
        assertThrows(ServerApiException.class, () -> client.ensureVmBackupSet("ablecube4", "i-2-220-VM", "7"));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset")));
    }

    @Test
    public void ambiguousOrWrongHostBackupSetsAreRejected() throws Exception {
        stubTokenRenewal();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode duplicate = (ObjectNode) mapper.readTree(vmBackupSet("7"));
        duplicate.withArray("backupsetProperties").add(duplicate.withArray("backupsetProperties").get(0).deepCopy());
        stubVmBackupSet(duplicate.toString());
        AblestackCommvaultClient client = newClient();
        assertThrows(CloudRuntimeException.class, () -> client.ensureVmBackupSet("ablecube4", "i-2-220-VM", "7"));
        stubVmBackupSet(vmBackupSet("7").replace("ablecube4", "ablecube1"));
        assertThrows(CloudRuntimeException.class, () -> client.ensureVmBackupSet("ablecube4", "i-2-220-VM", "7"));
        wireMockRule.verify(0, postRequestedFor(urlEqualTo("/api/Backupset")));
    }

    @Test
    public void aSuccessfulCreateResponseAloneDoesNotConfirmAssignment() throws Exception {
        stubTokenRenewal();
        stubVmClient();
        stubVmBackupSet("{\"backupsetProperties\":[]}");
        wireMockRule.stubFor(post(urlEqualTo("/api/Backupset"))
                .willReturn(aResponse().withStatus(200).withBody("{\"response\":[{\"errorCode\":0}]}")));

        assertFalse(newClient().ensureVmBackupSet("ablecube4", "i-2-220-VM", "7"));

        wireMockRule.verify(1, postRequestedFor(urlEqualTo("/api/Backupset")));
    }

    private void stubVmBackupSet(String body) {
        wireMockRule.stubFor(get(urlEqualTo("/api/Backupset?clientName=ablecube4"))
                .willReturn(aResponse().withStatus(200).withBody(body)));
    }

    private void stubVmBackupSetDetails(String id, String body) {
        wireMockRule.stubFor(get(urlEqualTo("/api/Backupset/" + id))
                .willReturn(aResponse().withStatus(200).withBody(body)));
    }

    private ObjectNode capturedBackupSetResponse(String name) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/commvault/" + name)) {
            return (ObjectNode) new ObjectMapper().readTree(stream);
        }
    }

    private String vmBackupSet(String planId) {
        return "{\"backupsetProperties\":[{\"planEntity\":{\"planId\":\"" + planId + "\"},"
                + "\"backupSetEntity\":{\"backupsetId\":9,\"backupsetName\":\"i-2-220-VM\","
                + "\"clientName\":\"ablecube4\",\"applicationId\":29}}]}";
    }

    private void stubVmClient() {
        wireMockRule.stubFor(get(urlEqualTo("/api/Client"))
                .willReturn(aResponse().withStatus(200).withBody("{\"clientProperties\":[{\"client\":{\"clientEntity\":{"
                        + "\"clientId\":42,\"clientName\":\"ablecube4\"}}}]}")));
        wireMockRule.stubFor(get(urlEqualTo("/api/Client/42"))
                .willReturn(aResponse().withStatus(200).withBody("{\"clientProperties\":[{\"client\":{"
                        + "\"idaList\":[{\"idaEntity\":{\"applicationId\":29}}]}}]}")));
    }

    @Test
    public void backupSetDeletionVerifiesIdentityAndConfirmsRemoval() throws Exception {
        stubTokenRenewal();
        wireMockRule.stubFor(get(urlEqualTo("/api/Backupset?clientName=ablecube4"))
                .inScenario("backupset deletion").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody(vmBackupSet(""))));
        stubVmBackupSetDetails("9", vmBackupSet("7"));
        wireMockRule.stubFor(delete(urlEqualTo("/api/Backupset/9"))
                .inScenario("backupset deletion").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withBody("{\"errorCode\":0}"))
                .willSetStateTo("deleted"));
        wireMockRule.stubFor(get(urlEqualTo("/api/Backupset?clientName=ablecube4"))
                .inScenario("backupset deletion").whenScenarioStateIs("deleted")
                .willReturn(aResponse().withStatus(200).withBody("{\"backupsetProperties\":[]}")));

        assertTrue(newClient().deleteVmBackupSet(new AblestackCommvaultClient.VmBackupSet("ablecube4", "i-2-220-VM", "9", "7")));

        wireMockRule.verify(1, deleteRequestedFor(urlEqualTo("/api/Backupset/9")));
        wireMockRule.verify(2, getRequestedFor(urlEqualTo("/api/Backupset?clientName=ablecube4")));
        wireMockRule.verify(1, getRequestedFor(urlEqualTo("/api/Backupset/9")));
    }

    @Test
    public void deleteAcknowledgementDoesNotReplaceTheRemovalCheck() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet(vmBackupSet("7"));
        wireMockRule.stubFor(delete(urlEqualTo("/api/Backupset/9"))
                .willReturn(aResponse().withStatus(200).withBody("{\"errorCode\":5}")));

        assertFalse(newClient().deleteVmBackupSet(new AblestackCommvaultClient.VmBackupSet("ablecube4", "i-2-220-VM", "9", "7")));
    }

    @Test
    public void backupSetDeletionRejectsChangedIdsPlansAndTheDefaultBackupSet() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet(vmBackupSet("7"));
        AblestackCommvaultClient client = newClient();
        assertThrows(CloudRuntimeException.class, () -> client.deleteVmBackupSet(
                new AblestackCommvaultClient.VmBackupSet("ablecube4", "i-2-220-VM", "10", "7")));
        assertThrows(CloudRuntimeException.class, () -> client.deleteVmBackupSet(
                new AblestackCommvaultClient.VmBackupSet("ablecube4", "i-2-220-VM", "9", "8")));
        assertThrows(CloudRuntimeException.class, () -> client.deleteVmBackupSet(
                new AblestackCommvaultClient.VmBackupSet("ablecube4", "defaultBackupSet", "9", "7")));
        wireMockRule.verify(0, deleteRequestedFor(urlEqualTo("/api/Backupset/9")));
        wireMockRule.verify(0, deleteRequestedFor(urlEqualTo("/api/Backupset/10")));
    }

    @Test
    public void backupSetCleanupCanBeRetriedWhenTheSetIsAlreadyGone() throws Exception {
        stubTokenRenewal();
        stubVmBackupSet("{\"backupsetProperties\":[]}");
        assertTrue(newClient().deleteVmBackupSet(new AblestackCommvaultClient.VmBackupSet("ablecube4", "i-2-220-VM", "9", "7")));
        wireMockRule.verify(0, deleteRequestedFor(urlEqualTo("/api/Backupset/9")));
    }

    @Test
    public void volumeRestoreAcceptsOnlyNormalCompletionAcrossStateSpellings() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        for (String state : List.of("Completed", "completed", " COMPLETED ")) {
            stubRestoreJobState(state, "", "");
            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result result = client.restoreVolumeArtifact(
                    null, "ablecube4", new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false));
            assertTrue(state, result.completed);
            assertTrue(result.externalTerminal);
            assertNull(result.failure);
        }
    }

    @Test
    public void volumeRestoreBlocksWarningAndPartialSuccessAliasesWithDetailedReasons() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        for (String state : List.of("Completed w/ one or more warnings", "Completed With Warning", "completed with warnings",
                "Completed w/ one or more errors", "Completed With One or More Errors")) {
            stubRestoreJobState(state, "\"errorMessage\":\"Cannot restore root.qcow2\",\"errorCode\":19", "");
            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result result = client.restoreVolumeArtifact(
                    null, "ablecube4", new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false));
            assertFalse(state, result.completed);
            assertTrue(state, result.externalTerminal);
            assertTrue(result.failure, result.failure.contains("Cannot restore root.qcow2"));
            assertTrue(result.failure, result.failure.contains("errorCode=19"));
        }
    }

    @Test
    public void completedRestoreWithFailedOrSkippedFilesCannotApplyVolumes() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        for (String counter : List.of("totalFailedFiles", "totalFailedFolders", "restoreFileFailures", "restoreFolderFailures",
                "backupFileFailures", "backupFolderFailures", "scanFileFailures", "scanFolderFailures", "skippedItems")) {
            stubRestoreJobState("Completed", "", "\"" + counter + "\":1");
            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result result = client.restoreVolumeArtifact(
                    null, "ablecube4", new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false));
            assertFalse(counter, result.completed);
            assertTrue(counter, result.externalTerminal);
            assertTrue(result.failure, result.failure.contains(counter + "=1"));
        }
    }

    @Test
    public void completedRestoreRejectsTextFailureCountsAndMalformedCounters() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        for (String counters : List.of("\"failures\":\"1 Folders, 0 Files\"", "\"totalFailedFiles\":\"unknown\"")) {
            stubRestoreJobState("Completed", "", counters);
            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result result = client.restoreVolumeArtifact(
                    null, "ablecube4", new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false));
            assertFalse(result.completed);
            assertTrue(result.externalTerminal);
        }
    }

    @Test
    public void unavailableCounterSentinelAndZeroCountsDoNotRejectSuccessfulRestore() throws Exception {
        stubTokenRenewal();
        stubRestoreJobState("Completed", "", "\"totalFailedFiles\":0,\"backupFileFailures\":-999,\"failures\":\"0 Folders, 0 Files\"");
        assertTrue(newClient().restoreVolumeArtifact(null, "ablecube4",
                new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false)).completed);
    }

    @Test
    public void activeRestoreWithFailureCountersRemainsNonTerminal() throws Exception {
        stubTokenRenewal();
        stubRestoreJobState("Running", "", "\"totalFailedFiles\":1,\"endTime\":1792458000");
        org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result result = newClient().restoreVolumeArtifact(
                null, "ablecube4", new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false));
        assertFalse(result.completed);
        assertFalse(result.externalTerminal);
        assertNull(result.failure);
    }

    @Test
    public void unknownRestoreResultRequiresTerminalEvidenceBeforeCleanup() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        stubRestoreJobState("Unrecognized state", "", "");
        org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result pending = client.restoreVolumeArtifact(
                null, "ablecube4", new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false));
        assertFalse(pending.completed);
        assertFalse(pending.externalTerminal);
        stubRestoreJobState("Unconfirmed", "", "");
        assertFalse(client.restoreVolumeArtifact(null, "ablecube4",
                new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false)).externalTerminal);
        stubRestoreJobState("Unrecognized state", "", "\"endTime\":1792458000");
        org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result ended = client.restoreVolumeArtifact(
                null, "ablecube4", new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false));
        assertFalse(ended.completed);
        assertTrue(ended.externalTerminal);
        assertTrue(ended.failure.contains("Unrecognized terminal state"));
    }

    @Test
    public void canceledAndCommittedRestoresNeverCountAsSuccessful() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        for (String state : List.of("Failed", "Failed to Start", "Killed", "Canceled", "Cancelled", "Committed")) {
            stubRestoreJobState(state, "\"reasonForJobDelay\":\"Restore could not finish\"", "");
            org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result result = client.restoreVolumeArtifact(
                    null, "ablecube4", new org.apache.cloudstack.backup.ThirdPartyBackupRestore.Result("1200", false));
            assertFalse(state, result.completed);
            assertTrue(state, result.externalTerminal);
            assertTrue(result.failure.contains("Restore could not finish"));
        }
    }

    @Test
    public void legacyPollingNormalizesWarningAndDoesNotWaitForAnUnknownState() throws Exception {
        stubTokenRenewal();
        AblestackCommvaultClient client = newClient();
        stubRestoreJobState("Completed With Warning", "\"pendingReason\":\"Permission warning\"", "");
        AblestackCommvaultClient.JobStatusResult warning = client.getJobStatusResult("1200", 1000);
        assertEquals("Completed w/ one or more warnings", warning.getStatus());
        assertTrue(warning.getFailureReason().contains("Permission warning"));
        stubRestoreJobState("Unknown state", "", "");
        assertEquals("Unconfirmed", client.getJobStatusResult("1200", 1000).getStatus());
    }

    @Test
    public void failedJobStatusQueryDoesNotClaimCompletionOrTermination() throws Exception {
        stubTokenRenewal();
        wireMockRule.stubFor(post(urlEqualTo("/api/JobDetails")).willReturn(aResponse().withStatus(503)));
        AblestackCommvaultClient.JobStatusResult result = newClient().getJobStatusResult("1200", 1000);
        assertNull(result.getStatus());
        assertFalse(result.isTerminal());
    }

    private void stubRestoreJobState(String state, String progress, String detail) {
        wireMockRule.stubFor(post(urlEqualTo("/api/JobDetails"))
                .willReturn(aResponse().withStatus(200).withBody("{\"job\":{\"jobDetail\":{\"progressInfo\":{\"state\":\""
                        + state + "\"" + (progress.isEmpty() ? "" : "," + progress) + "},\"detailInfo\":{" + detail + "}}}}")));
    }

}
