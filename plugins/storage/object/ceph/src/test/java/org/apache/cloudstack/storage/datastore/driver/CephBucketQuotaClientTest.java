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
package org.apache.cloudstack.storage.datastore.driver;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.object.BucketQuotaState;
import org.junit.Test;

/** Provider snapshots preserve disabled, byte-exact, object-count and raw-check limits. */
public class CephBucketQuotaClientTest {
    private static final String BODY = "{\"owner\":\"owner\",\"id\":\"id\",\"bucket_quota\":{\"enabled\":false,\"max_size\":1073741825,\"max_objects\":17,\"check_on_raw\":true},\"usage\":{\"rgw.main\":{\"size\":1,\"size_actual\":4096}}}";
    private CephBucketQuotaClient reader(String json) {
        return new CephBucketQuotaClient("http://store", "PUBLIC", "PRIVATE") {
            @Override protected JsonObject request(String bucket, String owner, byte[] content) {
                return json == null ? null : JsonParser.parseString(json).getAsJsonObject();
            }
        };
    }
    @Test public void exactBytesAndDisabledQuotaSurviveSnapshot() {
        BucketQuotaState q = reader(BODY).read("bucket", "owner");
        assertFalse(q.isEnabled()); assertEquals(1073741825L, q.getMaxSizeBytes()); assertEquals(17, q.getMaxObjects());
        assertTrue(q.isCheckOnRaw()); assertEquals(4096, q.getUsedBytes()); assertEquals(-1, q.getEffectiveLimitBytes());
    }
    @Test public void missingBucketIsDistinctFromUnreadableUsage() { assertFalse(reader(null).read("bucket", "owner").exists()); }
    @Test public void wrongOwnerIsRejectedWithoutExposingProviderBody() {
        try { reader(BODY).read("bucket", "other"); fail(); }
        catch (CloudRuntimeException e) { assertNull(e.getCause()); assertFalse(e.getMessage().contains("PRIVATE")); }
    }
    @Test public void absentUsageFailsClosed() {
        JsonObject body = JsonParser.parseString(BODY).getAsJsonObject(); body.remove("usage");
        try { reader(body.toString()).read("bucket", "owner"); fail(); } catch (CloudRuntimeException expected) { }
    }
    @Test public void usageOverflowFailsClosed() {
        JsonObject body = JsonParser.parseString(BODY).getAsJsonObject();
        body.getAsJsonObject("usage").getAsJsonObject("rgw.main").addProperty("size", "999999999999999999999");
        try { reader(body.toString()).read("bucket", "owner"); fail(); } catch (CloudRuntimeException expected) { }
    }
    @Test public void writeRestoresAllQuotaFieldsIncludingDisabledAndExactBytes() {
        CephBucketQuotaClient client = new CephBucketQuotaClient("http://store", "PUBLIC", "PRIVATE") {
            @Override protected JsonObject request(String bucket, String owner, byte[] content) {
                JsonObject body = JsonParser.parseString(new String(content, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                assertFalse(body.get("enabled").getAsBoolean()); assertEquals(1073741825L, body.get("max_size").getAsLong());
                assertEquals(17, body.get("max_objects").getAsLong()); assertTrue(body.get("check_on_raw").getAsBoolean());
                return new JsonObject();
            }
        };
        client.write("bucket", "owner", reader(BODY).read("bucket", "owner"));
    }
    @Test public void signedGetIncludesRequiredS3PayloadDigest() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> digest = new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/bucket", exchange -> {
            digest.set(exchange.getRequestHeaders().getFirst("x-amz-content-sha256"));
            assertFalse(exchange.getRequestURI().getQuery().contains("uid="));
            exchange.sendResponseHeaders(404, -1); exchange.close();
        });
        server.start();
        try {
            assertFalse(new CephBucketQuotaClient("http://127.0.0.1:" + server.getAddress().getPort(), "PUBLIC", "PRIVATE").read("missing", "owner").exists());
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest.get());
        } finally { server.stop(0); }
    }
    @Test public void signedWriteMatchesGlueV2AndPayloadDigest() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> digest = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<byte[]> payload = new java.util.concurrent.atomic.AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/admin/bucket", exchange -> {
            assertTrue(exchange.getRequestURI().getQuery().contains("uid=owner"));
            assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("AWS PUBLIC:"));
            digest.set(exchange.getRequestHeaders().getFirst("Content-MD5")); payload.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            new CephBucketQuotaClient("http://127.0.0.1:" + server.getAddress().getPort(), "PUBLIC", "PRIVATE").write("bucket", "owner", reader(BODY).read("bucket", "owner"));
            assertEquals(java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(payload.get())), digest.get());
        } finally { server.stop(0); }
    }

}
