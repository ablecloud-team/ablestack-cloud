// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
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

import java.util.ArrayList;
import java.util.List;
import org.apache.http.HttpVersion;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
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

public class NetBackupStagingPathSyncTest {
    private AblestackNetBackupClient client;
    private String selected;
    private String owner;
    private JSONArray jobs;
    private List<String> names;
    private final List<HttpUriRequest> requests = new ArrayList<>();
    private static final String TARGET = "/new/ablestack-netbackup/bootstrap/custom_policy";
    private static final String OLD = "/old/ablestack-netbackup/bootstrap/custom_policy";

    @Before public void setup() throws Exception {
        client = new AblestackNetBackupClient("http://netbackup.invalid/netbackup", "test-key", 1);
        selected = OLD;
        owner = "host-four";
        jobs = new JSONArray();
        names = List.of("custom policy");
        HttpClient http = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", http);
        when(http.execute(any(HttpUriRequest.class))).thenAnswer(call -> {
            HttpUriRequest request = call.getArgument(0);
            requests.add(request);
            String path = request.getURI().getPath();
            JSONObject body;
            int status = 200;
            if ("PUT".equals(request.getMethod())) {
                JSONObject change = new JSONObject(EntityUtils.toString(((HttpEntityEnclosingRequestBase) request).getEntity()));
                JSONObject attrs = change.getJSONObject("data").getJSONObject("attributes");
                assertEquals(1, attrs.length());
                assertEquals("backupSelection", change.getJSONObject("data").getString("type"));
                selected = attrs.getJSONArray("selections").getString(0);
                assertEquals("version-1", request.getFirstHeader("If-Match").getValue());
                body = new JSONObject(); status = 204;
            } else if (path.endsWith("/config/policies")) {
                JSONArray data = new JSONArray();
                names.forEach(name -> data.put(new JSONObject().put("id", name)));
                body = new JSONObject().put("data", data);
            } else if (path.contains("/admin/jobs")) {
                body = new JSONObject().put("data", jobs);
            } else {
                JSONObject policy = new JSONObject().put("policyType", "Standard")
                        .put("clients", new JSONArray().put(new JSONObject().put("hostName", owner)))
                        .put("policyAttributes", new JSONObject().put("active", false).put("retention", "keep"))
                        .put("schedules", new JSONArray().put(new JSONObject().put("scheduleName", "unchanged")))
                        .put("backupSelections", new JSONObject().put("selections", new JSONArray().put(selected)));
                body = new JSONObject().put("data", new JSONObject().put("attributes", new JSONObject().put("policy", policy)));
            }
            BasicHttpResponse response = new BasicHttpResponse(HttpVersion.HTTP_1_1, status, "test");
            response.setEntity(new StringEntity(body.toString()));
            response.setHeader("ETag", "version-1");
            return response;
        });
    }

    private long writes() { return requests.stream().filter(request -> "PUT".equals(request.getMethod())).count(); }
    private boolean sync(String action) { return client.synchronizeStagingPath("custom policy", "host-four", "192.0.2.4", OLD, TARGET, action); }

    @Test public void alreadyMatchingPathMakesOnlyOneReadAndNoWrite() {
        selected = TARGET;
        assertFalse(sync("APPLY"));
        assertEquals(1, requests.size());
        assertEquals(0, writes());
    }

    @Test public void changedSelectionUsesOnlyTheDedicatedSelectionEndpointAndReadback() {
        assertTrue(sync("APPLY"));
        assertEquals(TARGET, selected);
        assertEquals(1, writes());
        assertFalse(sync("APPLY"));
        assertEquals(1, writes());
    }

    @Test public void checkIsReadOnlyEvenWhenPathDiffers() {
        assertTrue(sync("CHECK"));
        assertEquals(0, writes());
        assertEquals(OLD, selected);
    }

    @Test public void differentClientOrCustomSelectionIsNeverOverwritten() {
        owner = "other-host";
        assertThrows(CloudRuntimeException.class, () -> sync("APPLY"));
        owner = "host-four";
        selected = "/operator/custom/files";
        assertThrows(CloudRuntimeException.class, () -> sync("APPLY"));
        assertEquals(0, writes());
    }

    @Test public void aRunningTransferPreventsPathChanges() {
        jobs.put(new JSONObject().put("id", "73").put("attributes", new JSONObject().put("state", "ACTIVE")));
        assertThrows(CloudRuntimeException.class, () -> sync("APPLY"));
        assertEquals(0, writes());
    }

    @Test public void retryingJobsCannotResumeWithTheOldSelectionDuringPathMigration() {
        for (String state : List.of("REQUEUED", "WAITING_FOR_RETRY")) {
            jobs = new JSONArray().put(new JSONObject().put("id", "73").put("attributes", new JSONObject().put("state", state)));
            assertThrows(CloudRuntimeException.class, () -> sync("APPLY"));
        }
        assertEquals(0, writes());
    }

    @Test public void sanitizedLegacyFilenameResolvesTheActualPolicyNameForTheHost() {
        assertEquals("custom policy", client.resolveStagingPolicyName("custom_policy", "host-four", "192.0.2.4"));
        assertEquals(0, writes());
    }

    @Test public void ambiguousLegacyNamesCannotAuthorizeAnyPolicyWrite() {
        names = List.of("custom policy", "custom_policy");
        assertThrows(CloudRuntimeException.class, () -> client.resolveStagingPolicyName("custom_policy", "host-four", "192.0.2.4"));
        assertEquals(0, writes());
    }
}
