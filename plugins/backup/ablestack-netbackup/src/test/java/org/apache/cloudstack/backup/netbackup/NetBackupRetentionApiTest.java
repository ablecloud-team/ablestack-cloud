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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.backup.ThirdPartyBackupManifest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.exception.CloudRuntimeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the supplied 10.5/v12 and 11.1/v13 contracts without a live Primary or local HTTP listener. */
public class NetBackupRetentionApiTest {
    private final HttpClient http = mock(HttpClient.class);
    private final List<HttpUriRequest> requests = new ArrayList<>();
    private final Instant early = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusSeconds(86400);
    private final Instant target = early.plusSeconds(86400);
    private AblestackNetBackupClient client;
    private JSONObject image;
    private int postStatus = 204;
    private boolean requireV13;
    private boolean loseResponse;

    @Before public void setup() throws Exception {
        client = new AblestackNetBackupClient("https://primary:1556/netbackup", "synthetic-key", 5);
        ReflectionTestUtils.setField(client, "httpClient", http);
        JSONObject attributes = new JSONObject().put("policyName", "host-policy").put("clientName", "host")
                .put("backupStatus", 0).put("numCopies", 2).put("primaryCopy", 1).put("expiration", early.toString())
                .put("fragments", new JSONArray().put(fragment(1, early)).put(fragment(2, early)));
        image = new JSONObject().put("data", new JSONObject().put("id", "host_123").put("attributes", attributes));
        when(http.execute(any(HttpUriRequest.class))).thenAnswer(call -> {
            HttpUriRequest request = call.getArgument(0);
            requests.add(request);
            assertEquals("synthetic-key", request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue());
            boolean old = request.getFirstHeader(HttpHeaders.ACCEPT).getValue().contains("version=12.0");
            if (requireV13 && old) { return response(request instanceof HttpPost ? 415 : 406, null); }
            if (request instanceof HttpPost) {
                if (loseResponse) { throw new IOException("Response lost"); }
                BasicHttpResponse response = response(postStatus, null);
                if (postStatus == 202) { response.setHeader("X-NetBackup-MPA-Ticket", "ticket-1"); }
                return response;
            }
            return response(200, image.toString());
        });
    }

    @Test public void v12ExtendsOnlyAnExplicitImageCopyAndDisablesDestructiveOptions() throws Exception {
        assertTrue(client.extendImageRetention(artifact(), false, "host-policy", 1, target).isCompleted());
        HttpPost post = posts().get(0);
        assertEquals("/netbackup/catalog/images/update-expiration-date", post.getURI().getPath());
        assertEquals("application/vnd.netbackup+json;version=12.0", post.getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue());
        JSONObject data = new JSONObject(EntityUtils.toString(post.getEntity())).getJSONObject("data");
        assertEquals("byBackupId", data.getString("type"));
        JSONObject attributes = data.getJSONObject("attributes");
        assertEquals("host_123", attributes.getString("backupId"));
        assertEquals("host", attributes.getString("client"));
        assertEquals(1, attributes.getInt("copyNumber"));
        assertEquals(target, Instant.parse(attributes.getString("date")));
        for (String field : List.of("delete", "immediate", "forceIncompleteSLP", "followDependee")) { assertFalse(attributes.getBoolean(field)); }
    }

    @Test public void v13IsTriedOnlyAfterExplicitMediaVersionRejection() {
        requireV13 = true;
        assertTrue(client.extendImageRetention(artifact(), false, "host-policy", 1, target).isCompleted());
        assertEquals(2, posts().size());
        assertTrue(posts().get(1).getFirstHeader(HttpHeaders.CONTENT_TYPE).getValue().contains("version=13.0"));
    }

    @Test public void mpaAcceptanceIsPendingAndReturnsItsTicket() {
        postStatus = 202;
        AblestackNetBackupClient.ExpireImageResult result = client.extendImageRetention(artifact(), false, "host-policy", 1, target);
        assertFalse(result.isCompleted());
        assertEquals("ticket-1", result.getMpaTicket());
        assertEquals(1, posts().size());
    }

    @Test public void fractionalProtectionDateIsRoundedUpToCatalogSecondsAndSentInTheDocumentedFormat() throws Exception {
        Instant precise = target.plusMillis(537).plusNanos(123);
        client.extendImageRetention(artifact(), false, "host-policy", 1, precise);
        String date = new JSONObject(EntityUtils.toString(posts().get(0).getEntity())).getJSONObject("data").getJSONObject("attributes").getString("date");
        assertTrue(date.endsWith(".000Z"));
        assertEquals(target.plusSeconds(1), Instant.parse(date));
        assertFalse(Instant.parse(date).isBefore(precise));
    }

    @Test public void observedSingleCopyCatalogUsesCopyExpirationAndIgnoresOtherEpochDates() {
        JSONObject attributes = image.getJSONObject("data").getJSONObject("attributes");
        attributes.put("numCopies", 1).put("primaryCopy", 1).put("version", 15)
                .put("remoteExpirationDate", "1970-01-01T00:00:00.000Z").put("tirExpirationDate", "1970-01-01T00:00:00.000Z")
                .put("fragments", new JSONArray().put(fragment(1, early).put("fragmentNumber", 1)
                        .put("wormUnlockTime", "1970-01-01T00:00:00.000Z").put("slpTryToKeepDate", "1970-01-01T00:00:00.000Z")));
        assertEquals(java.util.Map.of(1, early), client.imageRetention(artifact(), false, "host-policy").copies);
    }

    @Test public void existingLongerAndInfiniteDatesNeverCauseAnUpdate() {
        assertTrue(client.extendImageRetention(artifact(), false, "host-policy", 1, early.minusSeconds(60)).isCompleted());
        image.getJSONObject("data").getJSONObject("attributes").put("fragments",
                new JSONArray().put(fragment(1, AblestackNetBackupClient.INFINITE_EXPIRATION)).put(fragment(2, early)));
        assertTrue(client.extendImageRetention(artifact(), false, "host-policy", 1, target).isCompleted());
        assertTrue(posts().isEmpty());
    }

    @Test public void restorationChangesOnlyTheVerifiedCopyToAFutureNativeDate() throws Exception {
        Instant original = early.minusSeconds(3600);
        assertTrue(client.restoreImageRetention(artifact(), false, "host-policy", 1, early, original).isCompleted());
        JSONObject attributes = new JSONObject(EntityUtils.toString(posts().get(0).getEntity()))
                .getJSONObject("data").getJSONObject("attributes");
        assertEquals("host_123", attributes.getString("backupId"));
        assertEquals(1, attributes.getInt("copyNumber"));
        assertEquals(original, Instant.parse(attributes.getString("date")));
        for (String field : List.of("delete", "immediate", "forceIncompleteSLP", "followDependee")) {
            assertFalse(attributes.getBoolean(field));
        }
    }

    @Test public void restorationRejectsAnExternalChangeBeforeSubmittingAnyUpdate() {
        image.getJSONObject("data").getJSONObject("attributes").getJSONArray("fragments").getJSONObject(0)
                .put("expiration", target.toString());
        assertThrows(AblestackNetBackupClient.RetentionUpdateRejected.class,
                () -> client.restoreImageRetention(artifact(), false, "host-policy", 1, early, early.minusSeconds(3600)));
        assertTrue(posts().isEmpty());
    }

    @Test public void restorationAlreadyReflectedInTheCatalogDoesNotSubmitAgain() {
        assertTrue(client.restoreImageRetention(artifact(), false, "host-policy", 1, target, early).isCompleted());
        assertTrue(posts().isEmpty());
    }

    @Test public void restorationRejectsPastOrMissingBoundsWithoutChangingImages() {
        assertThrows(IllegalArgumentException.class,
                () -> client.restoreImageRetention(artifact(), false, "host-policy", 1, early, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class,
                () -> client.restoreImageRetention(artifact(), false, "host-policy", 1, null, early));
        assertThrows(IllegalArgumentException.class,
                () -> client.restoreImageRetention(artifact(), false, "host-policy", 1, early, target));
        assertTrue(posts().isEmpty());
    }

    @Test public void restorationPreservesMpaAcceptanceInsteadOfTreatingItAsCompletion() {
        postStatus = 202;
        AblestackNetBackupClient.ExpireImageResult result = client.restoreImageRetention(artifact(), false,
                "host-policy", 1, early, early.minusSeconds(3600));
        assertFalse(result.isCompleted());
        assertEquals("ticket-1", result.getMpaTicket());
        assertEquals(1, posts().size());
    }

    @Test public void missingCopyExpirationDoesNotAuthorizeExtension() {
        image.getJSONObject("data").getJSONObject("attributes").getJSONArray("fragments").getJSONObject(0).remove("expiration");
        assertThrows(RuntimeException.class, () -> client.extendImageRetention(artifact(), false, "host-policy", 1, target));
        assertTrue(posts().isEmpty());
    }

    @Test public void inconsistentFragmentDatesAndIncompleteCopyInventoryAreRejected() {
        image.getJSONObject("data").getJSONObject("attributes").getJSONArray("fragments").put(fragment(1, target));
        assertThrows(CloudRuntimeException.class, () -> client.imageRetention(artifact(), false, "host-policy"));
        image.getJSONObject("data").getJSONObject("attributes").put("fragments", new JSONArray().put(fragment(1, early)));
        assertThrows(CloudRuntimeException.class, () -> client.imageRetention(artifact(), false, "host-policy"));
    }

    @Test public void expiredCopyStillInCatalogCannotAnchorANewIncremental() {
        assertTrue(client.backupImageHasFutureRetention("host_123"));
        image.getJSONObject("data").getJSONObject("attributes").getJSONArray("fragments").getJSONObject(0)
                .put("expiration", Instant.now().minusSeconds(60).toString());
        assertTrue(client.backupImageExists("host_123"));
        assertFalse(client.backupImageHasFutureRetention("host_123"));
        assertThrows(CloudRuntimeException.class, () -> client.imageRetention(artifact(), false, "host-policy"));
        assertTrue(posts().isEmpty());
    }

    @Test public void missingImageSelectsFullButAnUnconfirmedCatalogStillBlocksSubmission() throws Exception {
        when(http.execute(any(HttpUriRequest.class))).thenReturn(response(404, null));
        assertFalse(client.backupImageHasFutureRetention("host_123"));
        when(http.execute(any(HttpUriRequest.class))).thenReturn(response(503, null));
        assertThrows(CloudRuntimeException.class, () -> client.backupImageHasFutureRetention("host_123"));
    }

    @Test public void invalidCopyInventoryIsNotMisclassifiedAsNormalExpiration() {
        image.getJSONObject("data").getJSONObject("attributes").put("numCopies", 3);
        assertThrows(CloudRuntimeException.class, () -> client.backupImageHasFutureRetention("host_123"));
    }

    @Test public void imageIdentityPolicyAndClientMustMatchBeforeUpdating() {
        for (String field : List.of("policyName", "clientName")) {
            JSONObject attributes = image.getJSONObject("data").getJSONObject("attributes");
            String previous = attributes.getString(field);
            attributes.put(field, "other");
            assertThrows(CloudRuntimeException.class, () -> client.extendImageRetention(artifact(), false, "host-policy", 1, target));
            attributes.put(field, previous);
        }
        image.getJSONObject("data").put("id", "foreign_123");
        assertThrows(CloudRuntimeException.class, () -> client.extendImageRetention(artifact(), false, "host-policy", 1, target));
        assertTrue(posts().isEmpty());
    }

    @Test public void pastDatesAndImplicitAllCopiesAreRejectedWithoutNetworkCalls() {
        assertThrows(IllegalArgumentException.class, () -> client.extendImageRetention(artifact(), false, "host-policy", 1, Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> client.extendImageRetention(artifact(), false, "host-policy", 0, target));
        assertTrue(requests.isEmpty());
    }

    @Test public void permissionRejectionAndResponseLossNeverTriggerVersionFallback() {
        postStatus = 403;
        assertThrows(AblestackNetBackupClient.RetentionUpdateRejected.class,
                () -> client.extendImageRetention(artifact(), false, "host-policy", 1, target));
        assertEquals(1, posts().size());
        requests.clear();
        loseResponse = true;
        assertThrows(CloudRuntimeException.class, () -> client.extendImageRetention(artifact(), false, "host-policy", 1, target));
        assertEquals(1, posts().size());
    }

    private List<HttpPost> posts() {
        List<HttpPost> result = new ArrayList<>();
        requests.forEach(request -> { if (request instanceof HttpPost) { result.add((HttpPost) request); } });
        return result;
    }

    private ThirdPartyBackupManifest.Artifact artifact() {
        ThirdPartyBackupManifest.Artifact artifact = new ThirdPartyBackupManifest.Artifact();
        artifact.externalId = "host_123";
        artifact.sourceHost = "host";
        return artifact;
    }

    private JSONObject fragment(int copy, Instant expiration) {
        return new JSONObject().put("copyNumber", copy).put("expiration", expiration.toString());
    }

    private BasicHttpResponse response(int status, String body) throws Exception {
        BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, status, "test");
        if (body != null) { response.setEntity(new StringEntity(body)); }
        return response;
    }
}
