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
}
