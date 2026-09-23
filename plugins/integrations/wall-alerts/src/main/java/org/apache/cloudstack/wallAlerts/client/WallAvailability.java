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

package org.apache.cloudstack.wallAlerts.client;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared, bounded availability probe. Never retrieves alert rules or exposes credentials. */
public class WallAvailability {
    private final HttpClient http;
    private final Supplier<Boolean> enabled;
    private final Supplier<String> url;
    private final Supplier<String> token;
    private final java.util.function.LongSupplier clock;
    private final ObjectMapper json = new ObjectMapper();
    private String lastUrl;
    private String lastToken;
    private boolean lastEnabled;
    private String state = "Unknown";
    private long expires;
    private long checked;

    public WallAvailability(Supplier<Boolean> enabled, Supplier<String> url, Supplier<String> token) {
        this(enabled, url, token, System::currentTimeMillis);
    }

    WallAvailability(Supplier<Boolean> enabled, Supplier<String> url, Supplier<String> token, java.util.function.LongSupplier clock) {
        this.clock = clock;
        this.enabled = enabled;
        this.url = url;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public synchronized String state() {
        String endpoint = url.get();
        String credential = token.get();
        boolean active = Boolean.TRUE.equals(enabled.get());
        if (active == lastEnabled && Objects.equals(endpoint, lastUrl) && Objects.equals(credential, lastToken)
                && clock.getAsLong() < expires) return state;
        lastUrl = endpoint;
        lastToken = credential;
        lastEnabled = active;
        if (!active) return save("Disabled", 30000);
        if (endpoint == null || endpoint.isBlank() || credential == null || credential.isBlank()) return save("NotConfigured", 30000);
        try {
            URI base = URI.create(endpoint);
            if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                    || !("http".equals(base.getScheme()) || "https".equals(base.getScheme()))) return save("NotConfigured", 30000);
            String address = endpoint.replaceAll("/+$", "");
            HttpResponse<String> health = get(address + "/api/health", null);
            if (health.statusCode() != 200) return save("Unavailable", 30000);
            JsonNode healthJson = json.readTree(health.body());
            if (healthJson == null || !"ok".equalsIgnoreCase(healthJson.path("database").asText())) return save("Unavailable", 30000);
            HttpResponse<String> user = get(address + "/api/user", credential);
            if (user.statusCode() == 401 || user.statusCode() == 403) return save("AuthenticationFailed", 30000);
            if (user.statusCode() != 200) return save("Unavailable", 30000);
            JsonNode identity = json.readTree(user.body());
            if (identity == null || !identity.isObject() || !identity.has("id") || identity.path("isAnonymous").asBoolean(false)) {
                return save("AuthenticationFailed", 30000);
            }
            HttpResponse<String> permissionResponse = get(address + "/api/access-control/user/permissions", credential);
            if (permissionResponse.statusCode() == 401 || permissionResponse.statusCode() == 403) return save("AuthenticationFailed", 30000);
            if (permissionResponse.statusCode() != 200) return save("InvalidResponse", 30000);
            JsonNode permissions = json.readTree(permissionResponse.body());
            if (permissions == null || !permissions.isObject()) return save("InvalidResponse", 30000);
            for (String action : new String[]{"alert.rules:read", "alert.instances:read"}) {
                JsonNode scopes = permissions.path(action);
                if (!scopes.isArray() || scopes.size() == 0) return save("AuthenticationFailed", 30000);
            }
            return save("Ready", 30000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return save("Unavailable", 30000);
        } catch (Exception e) {
            return save(classify(e), 30000);
        }
    }

    private HttpResponse<String> get(String address, String credential) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(3))
                .header("Accept", "application/json");
        if (credential != null) request.header("Authorization", "Bearer " + credential);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    public synchronized void failed(RuntimeException error) {
        // A failed rules/permissions/parse request is not evidence of an empty alert list.
        save("Degraded", 300000);
    }

    public synchronized long checkedAt() { return checked; }

    private String save(String value, long ttl) {
        state = value;
        checked = clock.getAsLong();
        expires = checked + ttl;
        return value;
    }

    static String classify(Throwable error) {
        boolean connection = false;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException) return "Timeout";
            if (t instanceof SSLException) return "TlsError";
            if (t instanceof UnknownHostException || t instanceof java.nio.channels.UnresolvedAddressException) return "DnsError";
            if (t instanceof ConnectException) connection = true;
        }
        return connection ? "ConnectionFailed" : "InvalidResponse";
    }
}
