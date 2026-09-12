//Licensed to the Apache Software Foundation (ASF) under one
//or more contributor license agreements.  See the NOTICE file
//distributed with this work for additional information
//regarding copyright ownership.  The ASF licenses this file
//to you under the Apache License, Version 2.0 (the
//"License"); you may not use this file except in compliance
//the License.  You may obtain a copy of the License at
//
//http://www.apache.org/licenses/LICENSE-2.0
//
//Unless required by applicable law or agreed to in writing,
//software distributed under the License is distributed on an
//"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
//KIND, either express or implied.  See the License for the
//specific language governing permissions and limitations
//under the License.
package org.apache.cloudstack.oauth2.keycloak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cloudstack.oauth2.dao.OauthProviderDao;
import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.apache.cxf.rs.security.jose.jwk.JwkUtils;
import org.apache.cxf.rs.security.jose.jwk.JsonWebKeys;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Before;
import org.junit.Test;

import com.cloud.exception.CloudAuthenticationException;
import com.google.gson.JsonObject;

public class KeycloakOAuth2ProviderTest {
    private static final String ISSUER = "https://idp.example/realms/europa";
    private KeycloakOAuth2Provider provider;
    private OauthProviderVO config;
    private OauthProviderDao dao;
    private CloseableHttpClient client;
    private KeyPair signingKey;
    private JsonObject claims;
    private String token;
    private AtomicInteger exchanges;

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
        client = mock(CloseableHttpClient.class);
        dao = mock(OauthProviderDao.class);
        provider = new KeycloakOAuth2Provider(client);
        provider.oauthProviderDao = dao;
        config = new OauthProviderVO();
        config.setProvider("keycloak");
        config.setEnabled(true);
        config.setClientId("europa-client");
        config.setSecretKey("fixture-secret");
        config.setTokenUrl(ISSUER + "/protocol/openid-connect/token");
        config.setRedirectUri("https://cloud.example/client/oauth");
        when(dao.findByProviderAndDomainWithGlobalFallback(eq("keycloak"), any())).thenReturn(config);
        long now = Instant.now().getEpochSecond();
        claims = new JsonObject();
        claims.addProperty("iss", ISSUER);
        claims.addProperty("sub", "fixture-user");
        claims.addProperty("aud", "europa-client");
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + 300);
        claims.addProperty("email", "user@example.com");
        claims.addProperty("email_verified", true);
        token = sign(claims, signingKey);
        exchanges = new AtomicInteger();
        when(client.execute(any(HttpPost.class))).thenAnswer(invocation -> {
            HttpPost post = invocation.getArgument(0);
            assertEquals(config.getTokenUrl(), post.getURI().toString());
            assertTrue(post.getFirstHeader("Authorization").getValue().startsWith("Basic "));
            if (exchanges.incrementAndGet() > 1) {
                return response(400, "{\"error\":\"invalid_grant\"}");
            }
            JsonObject body = new JsonObject();
            body.addProperty("id_token", token);
            return response(200, body.toString());
        });
        when(client.execute(any(HttpGet.class))).thenAnswer(invocation -> {
            HttpGet get = invocation.getArgument(0);
            assertEquals(ISSUER + "/protocol/openid-connect/certs", get.getURI().toString());
            JsonWebKeys keys = new JsonWebKeys();
            keys.setKeys(List.of(JwkUtils.fromRSAPublicKey((RSAPublicKey) signingKey.getPublic(), "RS256", "fixture-key")));
            return response(200, JwkUtils.jwkSetToJson(keys));
        });
    }

    private CloseableHttpResponse response(int status, String body) {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, status, "fixture"));
        when(response.getEntity()).thenReturn(new StringEntity(body, StandardCharsets.UTF_8));
        return response;
    }

    private String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(JsonObject payload, KeyPair key) throws Exception {
        String value = encode("{\"alg\":\"RS256\",\"kid\":\"fixture-key\"}") + "." + encode(payload.toString());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update(value.getBytes(StandardCharsets.US_ASCII));
        return value + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private void rejectsClaim(String name, Object value) throws Exception {
        if (value instanceof Number) {
            claims.addProperty(name, (Number) value);
        } else if (value instanceof Boolean) {
            claims.addProperty(name, (Boolean) value);
        } else {
            claims.addProperty(name, (String) value);
        }
        token = sign(claims, signingKey);
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code"));
    }

    @Test
    public void signedDiscoveryLoginAndReplay() {
        assertEquals("keycloak", provider.getName());
        assertEquals("user@example.com", provider.verifySecretCodeAndFetchEmail("code", 2L));
        assertTrue(provider.verifyUser("user@example.com", "code", 2L));
        assertEquals(1, exchanges.get());
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code", 2L));
    }

    @Test
    public void directLoginDoesNotLeaveReusableCacheEntry() {
        assertTrue(provider.verifyUser("user@example.com", "code", 2L));
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code", 2L));
    }

    @Test
    public void discoveryCannotCrossDomains() {
        provider.verifySecretCodeAndFetchEmail("code", 2L);
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code", 3L));
        assertTrue(provider.verifyUser("user@example.com", "code", 2L));
    }

    @Test
    public void emailMismatchConsumesDiscovery() {
        provider.verifySecretCodeAndFetchEmail("code", 2L);
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("other@example.com", "code", 2L));
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code", 2L));
    }

    @Test
    public void wrongSignatureRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        token = sign(claims, generator.generateKeyPair());
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code"));
    }

    @Test
    public void unsignedTokenRejected() {
        token = encode("{\"alg\":\"none\"}") + "." + encode(claims.toString()) + ".";
        assertThrows(RuntimeException.class, () -> provider.verifyUser("user@example.com", "code"));
    }

    @Test public void wrongIssuerRejected() throws Exception { rejectsClaim("iss", "https://other.example/realms/europa"); }
    @Test public void wrongAudienceRejected() throws Exception { rejectsClaim("aud", "other-client"); }
    @Test public void wrongAuthorizedPartyRejected() throws Exception { rejectsClaim("azp", "other-client"); }
    @Test public void expiredTokenRejected() throws Exception { rejectsClaim("exp", Instant.now().getEpochSecond() - 1); }
    @Test public void prematureTokenRejected() throws Exception { rejectsClaim("nbf", Instant.now().getEpochSecond() + 300); }
    @Test public void unverifiedEmailRejected() throws Exception { rejectsClaim("email_verified", false); }

    @Test
    public void disabledProviderAndBlankCodeRejectedBeforeNetwork() {
        config.setEnabled(false);
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code"));
        config.setEnabled(true);
        assertThrows(CloudAuthenticationException.class, () -> provider.verifySecretCodeAndFetchEmail(" "));
        verifyNoInteractions(client);
    }

    @Test
    public void invalidEndpointAndNetworkFailureRejected() throws Exception {
        config.setTokenUrl("http://idp.example/token");
        assertThrows(CloudAuthenticationException.class, () -> provider.verifySecretCodeAndFetchEmail("code"));
        verifyNoInteractions(client);
        config.setTokenUrl(ISSUER + "/protocol/openid-connect/token");
        when(client.execute(any(HttpPost.class))).thenThrow(new IOException("fixture connection failure"));
        assertThrows(CloudAuthenticationException.class, () -> provider.verifySecretCodeAndFetchEmail("code"));
    }
}
