//
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
//
package org.apache.cloudstack.oauth2.keycloak;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;

import org.apache.cloudstack.auth.UserOAuth2Authenticator;
import org.apache.cloudstack.oauth2.OAuth2FlowCache;
import org.apache.cloudstack.oauth2.dao.OauthProviderDao;
import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.cxf.rs.security.jose.jwa.SignatureAlgorithm;
import org.apache.cxf.rs.security.jose.jwk.JsonWebKey;
import org.apache.cxf.rs.security.jose.jwk.JwkUtils;
import org.apache.cxf.rs.security.jose.jwk.KeyOperation;
import org.apache.cxf.rs.security.jose.jwk.PublicKeyUse;
import org.apache.cxf.rs.security.jose.jws.JwsJwtCompactConsumer;
import org.apache.cxf.rs.security.jose.jwt.JwtClaims;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.cloud.exception.CloudAuthenticationException;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class KeycloakOAuth2Provider extends AdapterBase implements UserOAuth2Authenticator {
    public static final String KEYCLOAK_PROVIDER = "keycloak";
    private static final String TOKEN_PATH = "/protocol/openid-connect/token";

    @Inject
    OauthProviderDao oauthProviderDao;

    private final OAuth2FlowCache flowCache = new OAuth2FlowCache();
    private CloseableHttpClient httpClient;

    public KeycloakOAuth2Provider() {
        this(HttpClientBuilder.create().useSystemProperties().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(10000)
                        .setConnectionRequestTimeout(10000).setSocketTimeout(20000).build()).build());
    }

    public KeycloakOAuth2Provider(CloseableHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getName() {
        return KEYCLOAK_PROVIDER;
    }

    @Override
    public String getDescription() {
        return "Keycloak OAuth2 Provider Plugin";
    }

    @Override
    public boolean verifyUser(String email, String code) {
        return verifyUser(email, code, null);
    }

    @Override
    public boolean verifyUser(String email, String code, Long domainId) {
        if (StringUtils.isAnyBlank(email, code)) {
            throw new CloudAuthenticationException("Email and authorization code are required");
        }
        OauthProviderVO provider = oauthProviderDao.findByProviderAndDomainWithGlobalFallback(getName(), domainId);
        String verifiedEmail = flowCache.consume(provider, domainId, code, () -> exchangeCode(code, provider));
        if (!email.equals(verifiedEmail)) {
            throw new CloudAuthenticationException("Unable to verify the email address with the provided secret");
        }
        return true;
    }

    @Override
    public String verifySecretCodeAndFetchEmail(String code) {
        return verifySecretCodeAndFetchEmail(code, null);
    }

    @Override
    public String verifySecretCodeAndFetchEmail(String code, Long domainId) {
        OauthProviderVO provider = oauthProviderDao.findByProviderAndDomainWithGlobalFallback(getName(), domainId);
        return flowCache.discover(provider, domainId, code, () -> exchangeCode(code, provider));
    }

    protected String exchangeCode(String code, OauthProviderVO provider) {
        String issuer = getIssuer(provider);
        String auth = provider.getClientId() + ":" + provider.getSecretKey();
        List<NameValuePair> params = new ArrayList<>();
        params.add(new BasicNameValuePair("grant_type", "authorization_code"));
        params.add(new BasicNameValuePair("code", code));
        params.add(new BasicNameValuePair("redirect_uri", provider.getRedirectUri()));
        HttpPost post = new HttpPost(provider.getTokenUrl());
        post.setHeader(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)));
        post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new CloudAuthenticationException("Keycloak rejected the authorization code");
            }
            JsonObject token = JsonParser.parseString(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!token.has("id_token")) {
                throw new CloudAuthenticationException("Keycloak did not return an ID token");
            }
            return validateIdToken(token.get("id_token").getAsString(), provider, issuer);
        } catch (IOException | IllegalArgumentException e) {
            throw new CloudAuthenticationException("Unable to verify the Keycloak authorization response");
        }
    }

    private String getIssuer(OauthProviderVO provider) {
        URI uri = URI.create(provider.getTokenUrl());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getPath().endsWith(TOKEN_PATH)) {
            throw new CloudAuthenticationException("Configure a Keycloak HTTPS realm token endpoint");
        }
        return provider.getTokenUrl().substring(0, provider.getTokenUrl().length() - TOKEN_PATH.length());
    }

    private String validateIdToken(String token, OauthProviderVO provider, String issuer) throws IOException {
        JwsJwtCompactConsumer consumer = new JwsJwtCompactConsumer(token);
        SignatureAlgorithm algorithm = consumer.getJwsHeaders().getSignatureAlgorithm();
        if (!SignatureAlgorithm.isPublicKeyAlgorithm(algorithm) || !consumer.validateCriticalHeaders()) {
            throw new CloudAuthenticationException("Unsupported Keycloak ID token signature");
        }
        String kid = consumer.getJwsHeaders().getKeyId();
        boolean valid = false;
        try (CloseableHttpResponse response = httpClient.execute(new HttpGet(issuer + "/protocol/openid-connect/certs"))) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new CloudAuthenticationException("Unable to load Keycloak signing keys");
            }
            List<JsonWebKey> keys = JwkUtils.readJwkSet(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)).getKeys();
            for (JsonWebKey key : keys) {
                if (!Objects.equals(kid, key.getKeyId())
                        || (key.getPublicKeyUse() != null && key.getPublicKeyUse() != PublicKeyUse.SIGN)
                        || (key.getKeyOperation() != null && !key.getKeyOperation().contains(KeyOperation.VERIFY))
                        || (key.getAlgorithm() != null && !algorithm.getJwaName().equals(key.getAlgorithm()))) {
                    continue;
                }
                if (consumer.verifySignatureWith(key, algorithm)) {
                    valid = true;
                    break;
                }
            }
        }
        if (!valid) {
            throw new CloudAuthenticationException("Invalid Keycloak ID token signature");
        }
        JwtClaims claims = consumer.getJwtClaims();
        long now = Instant.now().getEpochSecond();
        Object azp = claims.getClaim("azp");
        if (!issuer.equals(claims.getIssuer()) || claims.getAudiences() == null
                || !claims.getAudiences().contains(provider.getClientId()) || StringUtils.isBlank(claims.getSubject())
                || claims.getExpiryTime() == null || claims.getExpiryTime() <= now
                || (claims.getNotBefore() != null && claims.getNotBefore() > now)
                || claims.getIssuedAt() == null || claims.getIssuedAt() > now + 60
                || (azp != null && !provider.getClientId().equals(azp))
                || (claims.getAudiences().size() > 1 && azp == null)
                || !Boolean.TRUE.equals(claims.getClaim("email_verified"))) {
            throw new CloudAuthenticationException("Invalid Keycloak ID token claims");
        }
        return (String) claims.getClaim("email");
    }

    @Override
    public String getUserEmailAddress() throws CloudRuntimeException {
        return null;
    }

    public void setHttpClient(CloseableHttpClient httpClient) {
        this.httpClient = httpClient;
    }
}
