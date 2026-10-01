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

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WallAvailabilityTest {
    private HttpServer server;
    private boolean enabled = true;
    private String token = "test-token";
    private String url;
    private int userCode = 200;
    private String permissions = "{\"alert.rules:read\":[\"folders:*\"],\"alert.instances:read\":[\"folders:*\"]}";
    private String health = "{\"database\":\"ok\"}";
    private final AtomicInteger healthCalls = new AtomicInteger();
    private final AtomicInteger rulesCalls = new AtomicInteger();
    private final AtomicLong time = new AtomicLong(1000);
    private WallAvailability gate;

    @Before public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int code = 200;
            String body;
            if (path.equals("/api/health")) { healthCalls.incrementAndGet(); body = health; }
            else if (path.equals("/api/user")) { code = userCode; body = "{\"id\":1,\"isAnonymous\":false}"; }
            else if (path.equals("/api/access-control/user/permissions")) { body = permissions; }
            else { rulesCalls.incrementAndGet(); code = 500; body = "unexpected alert request"; }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        gate = new WallAvailability(() -> enabled, () -> url, () -> token, time::get);
    }
    @After public void tearDown() { server.stop(0); assertEquals(0, rulesCalls.get()); }
    @Test public void disabledDoesNotContactWall() { enabled = false; assertEquals("Disabled", gate.state()); assertEquals(0, healthCalls.get()); }
    @Test public void missingConfigurationDoesNotContactWall() { token = ""; assertEquals("NotConfigured", gate.state()); assertEquals(0, healthCalls.get()); }
    @Test public void readyProbeIsCached() { assertEquals("Ready", gate.state()); assertEquals("Ready", gate.state()); assertEquals(1, healthCalls.get()); }
    @Test public void authenticationFailureAndRecovery() { userCode = 401; assertEquals("AuthenticationFailed", gate.state()); userCode = 200; time.addAndGet(31000); assertEquals("Ready", gate.state()); }
    @Test public void missingAlertPermissionIsNotReady() { permissions = "{}"; assertEquals("AuthenticationFailed", gate.state()); }
    @Test public void invalidResponseIsNotReady() { health = "invalid-json"; assertEquals("InvalidResponse", gate.state()); }
    @Test public void unavailableServiceDoesNotReportEmptyRules() { server.stop(0); assertEquals("ConnectionFailed", gate.state()); }
    @Test public void queryFailureBlocksUntilCooldown() { assertEquals("Ready", gate.state()); gate.failed(new RuntimeException()); assertEquals("Degraded", gate.state()); assertEquals(1, healthCalls.get()); time.addAndGet(300001); assertEquals("Ready", gate.state()); }
    @Test public void configurationChangeInvalidatesCache() { assertEquals("Ready", gate.state()); token = "replacement"; assertEquals("Ready", gate.state()); assertEquals(2, healthCalls.get()); }
    @Test public void exceptionKindsDoNotExposeMessages() { assertEquals("TlsError", WallAvailability.classify(new javax.net.ssl.SSLException("secret"))); assertEquals("Timeout", WallAvailability.classify(new java.net.http.HttpTimeoutException("secret"))); assertEquals("DnsError", WallAvailability.classify(new java.net.UnknownHostException("secret"))); }
}
