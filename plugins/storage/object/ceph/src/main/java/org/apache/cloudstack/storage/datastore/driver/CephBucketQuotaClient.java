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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import com.amazonaws.DefaultRequest;
import com.amazonaws.auth.AWS4Signer;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.http.HttpMethodName;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.storage.object.BucketQuotaState;

/** Signed Admin Ops with exact quota snapshots; provider errors never expose credentials. */
public class CephBucketQuotaClient {
    private final String url;
    private final BasicAWSCredentials credentials;
    public CephBucketQuotaClient(String url, String access, String secret) {
        this.url = url; this.credentials = new BasicAWSCredentials(access, secret);
    }
    public BucketQuotaState read(String bucket, String owner) {
        JsonObject info = request(bucket, owner, null);
        if (info == null) return BucketQuotaState.missing();
        try {
            if (!owner.equals(info.get("owner").getAsString())) throw failure();
            JsonObject quota = info.getAsJsonObject("bucket_quota");
            String id = info.get("id").getAsString();
            if (id.isEmpty() || quota == null) throw failure();
            long used = 0;
            JsonObject usage = info.getAsJsonObject("usage");
            if (usage == null) throw failure();
            for (Map.Entry<String, JsonElement> e : usage.entrySet()) {
                JsonObject item = e.getValue().getAsJsonObject();
                long logical = item.get("size").getAsBigDecimal().longValueExact();
                long actual = item.get("size_actual").getAsBigDecimal().longValueExact();
                if (logical < 0 || actual < 0) throw failure();
                used = Math.addExact(used, Math.max(logical, actual));
            }
            long limit = quota.has("max_size") ? quota.get("max_size").getAsLong()
                    : Math.multiplyExact(quota.get("max_size_kb").getAsLong(), 1024L);
            return new BucketQuotaState(true, id, quota.get("enabled").getAsBoolean(), limit,
                    quota.get("max_objects").getAsLong(), used,
                    quota.has("check_on_raw") && quota.get("check_on_raw").getAsBoolean());
        } catch (RuntimeException e) { throw failure(); }
    }
    public void write(String bucket, String owner, BucketQuotaState quota) {
        JsonObject body = new JsonObject();
        body.addProperty("enabled", quota.isEnabled()); body.addProperty("max_size", quota.getMaxSizeBytes());
        body.addProperty("max_objects", quota.getMaxObjects()); body.addProperty("check_on_raw", quota.isCheckOnRaw());
        request(bucket, owner, body.toString().getBytes(StandardCharsets.UTF_8));
    }
    protected JsonObject request(String bucket, String owner, byte[] content) {
        HttpURLConnection connection = null;
        try {
            URI target = URI.create(url);
            if (target.getRawUserInfo() != null || !("http".equals(target.getScheme()) || "https".equals(target.getScheme()))) throw failure();
            URI endpoint = new URI(target.getScheme(), null, target.getHost(), target.getPort(), null, null, null);
            String prefix = target.getPath() == null ? "" : target.getPath().replaceAll("/+$", "");
            DefaultRequest<Void> request = new DefaultRequest<>("s3");
            request.setEndpoint(endpoint); request.setResourcePath(prefix + "/admin/bucket");
            request.setHttpMethod(content == null ? HttpMethodName.GET : HttpMethodName.PUT);
            request.addParameter("bucket", bucket); request.addParameter("uid", owner); request.addParameter("format", "json");
            request.addParameter(content == null ? "stats" : "quota", content == null ? "true" : "");
            byte[] payload = content == null ? new byte[0] : content;
            request.setContent(new ByteArrayInputStream(payload));
            request.addHeader("Content-Type", "application/json");
            AWS4Signer signer = new AWS4Signer(false); signer.setServiceName("s3"); signer.setRegionName("us-east-1");
            signer.sign(request, credentials);
            String query = request.getParameters().entrySet().stream().flatMap(e -> e.getValue().stream().map(v -> encode(e.getKey()) + "=" + encode(v))).collect(Collectors.joining("&"));
            connection = (HttpURLConnection)URI.create(endpoint.toASCIIString() + request.getResourcePath() + "?" + query).toURL().openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(15000); connection.setRequestMethod(request.getHttpMethod().name());
            for (Map.Entry<String, String> h : request.getHeaders().entrySet()) connection.setRequestProperty(h.getKey(), h.getValue());
            if (content != null) { connection.setDoOutput(true); connection.setFixedLengthStreamingMode(payload.length); try (java.io.OutputStream out = connection.getOutputStream()) { out.write(payload); } }
            int status = connection.getResponseCode();
            if (status == 404 && content == null) return null;
            if (status < 200 || status >= 300) throw failure();
            if (content != null) return new JsonObject();
            try (InputStream in = connection.getInputStream()) {
                byte[] bytes = in.readNBytes(1048577); if (bytes.length > 1048576) throw failure();
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception e) { throw failure(); }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~"); }
    private static CloudRuntimeException failure() { return new CloudRuntimeException("Unable to verify the Glue bucket quota or live usage; check the storage connection"); }
}
