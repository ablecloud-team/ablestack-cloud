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

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.cloud.utils.exception.CloudRuntimeException;

/** Ask RGW to generate the secret; never put a secret key into an Admin Ops URL. */
public final class CephBucketKeyClient {
    private final String url;
    private final String access;
    private final String secret;
    public CephBucketKeyClient(String url, String access, String secret) { this.url = url; this.access = access; this.secret = secret; }
    public void create(String uid, String candidateAccess) {
        HttpURLConnection connection = null;
        try {
            URI endpoint = URI.create(url);
            if (endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null ||
                    !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))) throw new IllegalArgumentException();
            String path = endpoint.getPath().replaceAll("/+$", "") + "/admin/user";
            String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneId.of("GMT")));
            String md5 = "1B2M2Y8AsgTpgAmY7PhCfg==";
            String canonical = "PUT\n" + md5 + "\napplication/json\n" + date + "\n" + path;
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String auth = "AWS " + access + ":" + Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            String query = "key&format=json&key-type=s3&generate-key=true&uid=" + encode(uid) + "&access-key=" + encode(candidateAccess);
            URI target = new URI(endpoint.getScheme(), null, endpoint.getHost(), endpoint.getPort(), path, null, null);
            connection = (HttpURLConnection)URI.create(target.toASCIIString() + "?" + query).toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
            connection.setRequestMethod("PUT"); connection.setRequestProperty("Date", date);
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Content-MD5", md5);
            connection.setRequestProperty("Authorization", auth); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(0);
            connection.getOutputStream().close();
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException();
            // The response may carry all user's secrets. Do not log or retain it: recover by the persisted candidate ID.
        } catch (Exception error) { throw new CloudRuntimeException("Unable to confirm the Glue candidate key; retry the recorded rotation"); }
        finally { if (connection != null) connection.disconnect(); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
