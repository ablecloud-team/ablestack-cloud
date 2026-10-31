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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.sun.net.httpserver.HttpServer;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CephBucketKeyClientTest {
    private HttpServer server;
    private String url;
    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @After public void stop() { server.stop(0); }
    @Test public void secretNeverAppearsInProviderUrlOrBodyAndPayloadIsSigned() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/admin/user", exchange -> {
            try {
                assertEquals("PUT", exchange.getRequestMethod()); String query = exchange.getRequestURI().getRawQuery();
                assertTrue(query.contains("access-key=candidate")); assertTrue(query.contains("generate-key=true"));
                assertFalse(query.contains("secret-key")); assertFalse(query.contains("PRIVATE_ADMIN_SECRET"));
                assertEquals(0, exchange.getRequestBody().readAllBytes().length);
                String date = exchange.getRequestHeaders().getFirst("Date");
                String canonical = "PUT\n1B2M2Y8AsgTpgAmY7PhCfg==\napplication/json\n" + date + "\n/admin/user";
                Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec("PRIVATE_ADMIN_SECRET".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
                assertEquals("AWS admin-access:" + Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))), exchange.getRequestHeaders().getFirst("Authorization"));
                calls.incrementAndGet(); exchange.sendResponseHeaders(200, 0); exchange.close();
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        new CephBucketKeyClient(url, "admin-access", "PRIVATE_ADMIN_SECRET").create("owner", "candidate"); assertEquals(1, calls.get());
    }
    @Test public void neverFollowsRedirectsWithCredentials() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/admin/user", exchange -> { exchange.getResponseHeaders().add("Location", url + "/other"); exchange.sendResponseHeaders(307, -1); exchange.close(); });
        server.createContext("/other", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        assertThrows(CloudRuntimeException.class, () -> new CephBucketKeyClient(url, "admin", "secret").create("owner", "candidate"));
        assertEquals(0, redirected.get());
    }
    @Test public void errorBodyAndUrlAreNotReflectedIntoPublicError() {
        server.createContext("/admin/user", exchange -> { byte[] body = "PRIVATE_ADMIN_SECRET".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(403, body.length); exchange.getResponseBody().write(body); exchange.close(); });
        String message = assertThrows(CloudRuntimeException.class, () -> new CephBucketKeyClient(url, "admin", "PRIVATE_ADMIN_SECRET").create("owner", "candidate")).getMessage();
        assertFalse(message.contains("PRIVATE_")); assertFalse(message.contains(url));
    }
}
