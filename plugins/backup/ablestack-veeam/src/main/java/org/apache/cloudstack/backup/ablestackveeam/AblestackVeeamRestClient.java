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

package org.apache.cloudstack.backup.ablestackveeam;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Veeam Backup &amp; Replication (VBR) REST API client (port 9419, v12+/v13).
 *
 * <p>Unlike the Enterprise Manager XML API (9398) used by {@link org.apache.cloudstack.backup.veeam.VeeamClient},
 * the VBR REST API is JSON based, uses OAuth 2.0 bearer tokens and is available on every
 * VBR server without Enterprise Manager. This client covers the read/query operations that
 * can be served over REST (restore point discovery), removing the dependency on
 * PowerShell-over-SSH for those calls.</p>
 *
 * <p>Note: there is no VBR REST endpoint that exports backup disk content to a filesystem
 * path. Disk export for NAS seed creation/restore must still be performed via PowerShell
 * ({@link AblestackVeeamClient#exportRestorePointDisksToStaging}).</p>
 */
public class AblestackVeeamRestClient {

    private static final Logger LOG = LogManager.getLogger(AblestackVeeamRestClient.class);

    private final URI apiURI;
    private final String apiVersion;
    private final String username;
    private final String password;
    private final HttpClient httpClient;

    private String accessToken;

    public AblestackVeeamRestClient(final String url, final String apiVersion, final String username,
            final String password, final boolean validateCertificate, final int timeoutSeconds)
            throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        this.apiURI = new URI(StringUtils.appendIfMissing(url, "/"));
        this.apiVersion = StringUtils.defaultIfBlank(apiVersion, "1.3-rev1");
        this.username = username;
        this.password = password;

        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeoutSeconds * 1000)
                .setConnectionRequestTimeout(timeoutSeconds * 1000)
                .setSocketTimeout(timeoutSeconds * 1000)
                .build();

        if (!validateCertificate) {
            final SSLContext sslContext = SSLUtils.getSSLContext();
            sslContext.init(null, new X509TrustManager[]{new TrustAllManager()}, new SecureRandom());
            final SSLConnectionSocketFactory factory = new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
            this.httpClient = HttpClientBuilder.create().setDefaultRequestConfig(config).setSSLSocketFactory(factory).build();
        } else {
            this.httpClient = HttpClientBuilder.create().setDefaultRequestConfig(config).build();
        }

        authenticate();
    }

    private String endpoint(final String relativePath) {
        return apiURI.toString() + relativePath;
    }

    private void authenticate() {
        final HttpPost request = new HttpPost(endpoint("oauth2/token"));
        request.setHeader("x-api-version", apiVersion);
        request.setHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        final String body = String.format("grant_type=password&username=%s&password=%s",
                urlEncode(username), urlEncode(password));
        request.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        try {
            final HttpResponse response = httpClient.execute(request);
            final int code = response.getStatusLine().getStatusCode();
            final String payload = readBody(response);
            if (code != HttpStatus.SC_OK) {
                throw new CloudRuntimeException(String.format(
                        "Failed to authenticate to Veeam VBR REST API [%s]: HTTP %d %s", apiURI, code, payload));
            }
            final JsonNode node = new ObjectMapper().readTree(payload);
            this.accessToken = node.path("access_token").asText(null);
            if (StringUtils.isBlank(accessToken)) {
                throw new CloudRuntimeException("Veeam VBR REST API returned no access_token");
            }
        } catch (IOException e) {
            throw new CloudRuntimeException("Error authenticating to Veeam VBR REST API: " + e.getMessage(), e);
        }
    }

    /**
     * List restore points for a VM (object) name, newest first. The returned restore-point
     * id is the bare GUID (urn:uuid: prefix stripped) so it stays compatible with the
     * PowerShell disk-export path that looks up {@code Get-VBRRestorePoint} by Id.
     */
    public List<Backup.RestorePoint> listRestorePointsForVm(final String vmName) {
        final String path = String.format("v1/objectRestorePoints?nameFilter=%s&orderColumn=CreationTime&orderAsc=false",
                urlEncode(vmName));
        final JsonNode root = getJson(path);
        final List<Backup.RestorePoint> points = new ArrayList<>();
        final JsonNode data = root.has("data") ? root.get("data") : root;
        if (data == null || !data.isArray()) {
            return points;
        }
        for (final JsonNode rp : data) {
            final String rawId = rp.path("id").asText(null);
            if (StringUtils.isBlank(rawId)) {
                continue;
            }
            final String name = rp.path("name").asText("");
            // Restore points are returned for all objects matching the name filter; keep
            // only those whose name actually corresponds to the requested VM.
            if (StringUtils.isNotBlank(vmName) && StringUtils.isNotBlank(name)
                    && !name.toLowerCase().contains(vmName.toLowerCase())) {
                continue;
            }
            final Date created = parseDate(rp.path("creationTime").asText(null));
            final String type = firstNonBlank(rp.path("type").asText(null), rp.path("pointType").asText(null), "");
            final Backup.RestorePoint restorePoint = new Backup.RestorePoint(stripUrn(rawId), created, type, null, null);
            restorePoint.setJobName(StringUtils.trimToNull(firstNonBlank(
                    rp.path("backupJobName").asText(null),
                    rp.path("jobName").asText(null),
                    rp.path("backupName").asText(null))));
            points.add(restorePoint);
        }
        return points;
    }

    private JsonNode getJson(final String relativePath) {
        try {
            JsonNode node = executeGet(relativePath);
            return node;
        } catch (TokenExpiredException e) {
            authenticate();
            return executeGet(relativePath);
        }
    }

    private JsonNode executeGet(final String relativePath) {
        final HttpGet request = new HttpGet(endpoint(relativePath));
        request.setHeader("x-api-version", apiVersion);
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        try {
            final HttpResponse response = httpClient.execute(request);
            final int code = response.getStatusLine().getStatusCode();
            final String payload = readBody(response);
            if (code == HttpStatus.SC_UNAUTHORIZED) {
                throw new TokenExpiredException();
            }
            if (code != HttpStatus.SC_OK) {
                throw new CloudRuntimeException(String.format(
                        "Veeam VBR REST API GET [%s] failed: HTTP %d %s", relativePath, code, payload));
            }
            return new ObjectMapper().readTree(payload);
        } catch (IOException e) {
            throw new CloudRuntimeException(String.format("Error calling Veeam VBR REST API [%s]: %s", relativePath, e.getMessage()), e);
        }
    }

    private static String readBody(final HttpResponse response) throws IOException {
        final HttpEntity entity = response.getEntity();
        return entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
    }

    private static String urlEncode(final String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String stripUrn(final String id) {
        if (id == null) {
            return null;
        }
        final int idx = id.lastIndexOf(':');
        return idx >= 0 ? id.substring(idx + 1) : id;
    }

    private static String firstNonBlank(final String... values) {
        for (final String v : values) {
            if (StringUtils.isNotBlank(v)) {
                return v;
            }
        }
        return "";
    }

    private static Date parseDate(final String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Date.from(Instant.parse(value));
        } catch (DateTimeParseException e) {
            LOG.warn("Unable to parse Veeam restore point creationTime [{}]", value);
            return null;
        }
    }

    private static final class TokenExpiredException extends RuntimeException {
    }
}
