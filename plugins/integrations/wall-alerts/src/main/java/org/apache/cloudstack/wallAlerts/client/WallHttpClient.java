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

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.apache.log4j.Logger;

/** TLS policy confined to Wall clients; never modifies JVM-wide SSL defaults. */
public final class WallHttpClient {
    private static final Logger LOG = Logger.getLogger(WallHttpClient.class);
    private final Duration connectTimeout;
    private HttpClient client;
    private boolean lastVerify;

    public WallHttpClient(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public synchronized HttpClient get(boolean verify) {
        if (client != null && lastVerify == verify) {
            return client;
        }
        HttpClient.Builder builder = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_NONE))
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (!verify) {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new UnverifiedWallTrustManager()}, null);
                builder.sslContext(context);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Could not configure Wall TLS client", e);
            }
            LOG.warn("Wall TLS certificate and hostname verification is disabled by wall.tls.verify=false");
        }
        client = builder.build();
        lastVerify = verify;
        return client;
    }

    // X509ExtendedTrustManager owns both chain and endpoint checks for Java HttpClient.
    // This configurable compatibility policy is never installed as the default trust manager.
    private static final class UnverifiedWallTrustManager extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
