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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WallTlsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private HttpsServer server;
    private String url;
    private int userStatus = 200;
    private final AtomicInteger requests = new AtomicInteger();

    @Before
    public void startPrivateTlsEndpoint() throws Exception {
        Path store = temporary.getRoot().toPath().resolve("wall.p12");
        // The endpoint uses an IP; the self-signed certificate deliberately has a different DNS SAN.
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "wall", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "test-only",
                "-keypass", "test-only", "-dname", "CN=wall.invalid", "-ext", "SAN=dns:wall.invalid",
                "-validity", "1", "-noprompt").redirectErrorStream(true)
                .redirectOutput(temporary.newFile("keytool.log")).start();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, keytool.exitValue());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream input = Files.newInputStream(store)) {
            keys.load(input, "test-only".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, "test-only".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body = "[]";
            if (path.equals("/api/health")) {
                body = "{\"database\":\"ok\"}";
            } else if (path.equals("/api/user")) {
                status = userStatus;
                body = "{\"id\":1}";
            } else if (path.equals("/api/access-control/user/permissions")) {
                body = "{\"alert.rules:read\":[\"folders:*\"],\"alert.instances:read\":[\"folders:*\"]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "https://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    private int get(HttpClient client) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url + "/api/v1/provisioning/alert-rules"))
                .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private void assertTlsRejected(HttpClient client) throws Exception {
        try {
            get(client);
            fail("Strict TLS must reject the private untrusted/mismatched certificate");
        } catch (SSLException expected) {
            // Expected: never automatically downgrade after a TLS failure.
        }
    }

    @Test
    public void optOutIsScopedToWallAndStrictCanBeRestored() throws Exception {
        SSLContext defaultContext = SSLContext.getDefault();
        String defaultHostnameProperty = System.getProperty("jdk.internal.httpclient.disableHostnameVerification");
        WallHttpClient clients = new WallHttpClient(Duration.ofSeconds(2));
        assertTlsRejected(clients.get(true));
        assertEquals(0, requests.get());
        assertEquals(200, get(clients.get(false)));
        assertSame(clients.get(false), clients.get(false));
        assertSame(defaultContext, SSLContext.getDefault());
        assertEquals(defaultHostnameProperty, System.getProperty("jdk.internal.httpclient.disableHostnameVerification"));
        assertTlsRejected(HttpClient.newHttpClient());
        assertTlsRejected(clients.get(true));
        assertEquals(1, requests.get());
    }

    @Test
    public void tlsSettingChangeInvalidatesAvailabilityCacheImmediately() {
        AtomicBoolean verify = new AtomicBoolean(true);
        WallAvailability gate = new WallAvailability(() -> true, () -> url, () -> "test-token", verify::get, () -> 1000L);
        assertEquals("TlsError", gate.state());
        verify.set(false);
        assertEquals("Ready", gate.state());
        assertEquals(3, requests.get());
        verify.set(true);
        assertEquals("TlsError", gate.state());
        assertEquals(3, requests.get());
    }

    @Test
    public void privateCertificateIsAcceptedByDefault() {
        assertEquals("false", org.apache.cloudstack.wallAlerts.config.WallConfigKeys.WALL_TLS_VERIFY.defaultValue());
        WallAvailability gate = new WallAvailability(() -> true, () -> url, () -> "test-token");
        assertEquals("Ready", gate.state());
    }

    @Test
    public void disablingTlsVerificationDoesNotBypassAuthentication() {
        userStatus = 401;
        WallAvailability gate = new WallAvailability(() -> true, () -> url, () -> "test-token", () -> false, () -> 1000L);
        assertEquals("AuthenticationFailed", gate.state());
        assertEquals(2, requests.get());
    }
}
