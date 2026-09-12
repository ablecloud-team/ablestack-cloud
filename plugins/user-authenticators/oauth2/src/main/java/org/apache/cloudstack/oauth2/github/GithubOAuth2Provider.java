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
package org.apache.cloudstack.oauth2.github;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.inject.Inject;

import org.apache.cloudstack.auth.UserOAuth2Authenticator;
import org.apache.cloudstack.oauth2.OAuth2FlowCache;
import org.apache.cloudstack.oauth2.dao.OauthProviderDao;
import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import com.cloud.exception.CloudAuthenticationException;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class GithubOAuth2Provider extends AdapterBase implements UserOAuth2Authenticator {
    @Inject
    OauthProviderDao _oauthProviderDao;

    private final OAuth2FlowCache flowCache = new OAuth2FlowCache();
    private final CloseableHttpClient httpClient;

    public GithubOAuth2Provider() {
        this(HttpClients.custom().useSystemProperties().disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(10000)
                        .setConnectionRequestTimeout(10000).setSocketTimeout(20000).build()).build());
    }

    public GithubOAuth2Provider(CloseableHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getName() {
        return "github";
    }

    @Override
    public String getDescription() {
        return "Github OAuth2 Provider Plugin";
    }

    @Override
    public boolean verifyUser(String email, String code) {
        return verifyUser(email, code, null);
    }

    @Override
    public String verifySecretCodeAndFetchEmail(String code) {
        return verifySecretCodeAndFetchEmail(code, null);
    }

    @Override
    public boolean verifyUser(String email, String code, Long domainId) {
        if (StringUtils.isAnyBlank(email, code)) {
            throw new CloudAuthenticationException("Email and authorization code are required");
        }
        OauthProviderVO provider = _oauthProviderDao.findByProviderAndDomainWithGlobalFallback(getName(), domainId);
        String verifiedEmail = flowCache.consume(provider, domainId, code, () -> exchangeCode(code, provider));
        if (!email.equals(verifiedEmail)) {
            throw new CloudAuthenticationException("Unable to verify the email address with the provided secret");
        }
        return true;
    }

    @Override
    public String verifySecretCodeAndFetchEmail(String code, Long domainId) {
        OauthProviderVO provider = _oauthProviderDao.findByProviderAndDomainWithGlobalFallback(getName(), domainId);
        return flowCache.discover(provider, domainId, code, () -> exchangeCode(code, provider));
    }

    protected String exchangeCode(String code, OauthProviderVO provider) {
        JsonObject request = new JsonObject();
        request.addProperty("client_id", provider.getClientId());
        request.addProperty("client_secret", provider.getSecretKey());
        request.addProperty("code", code);
        request.addProperty("redirect_uri", provider.getRedirectUri());
        HttpPost post = new HttpPost("https://github.com/login/oauth/access_token");
        post.setHeader("Accept", "application/json");
        post.setEntity(new StringEntity(request.toString(), ContentType.APPLICATION_JSON));
        String token;
        try (CloseableHttpResponse response = httpClient.execute(post)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new CloudAuthenticationException("GitHub rejected the authorization code");
            }
            JsonObject json = JsonParser.parseString(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("access_token") || StringUtils.isBlank(json.get("access_token").getAsString())) {
                throw new CloudAuthenticationException("GitHub did not return an access token");
            }
            token = json.get("access_token").getAsString();
        } catch (IOException | IllegalArgumentException e) {
            throw new CloudAuthenticationException("Unable to verify the GitHub authorization response");
        }
        HttpGet get = new HttpGet("https://api.github.com/user/emails");
        get.setHeader("Authorization", "Bearer " + token);
        get.setHeader("Accept", "application/vnd.github+json");
        try (CloseableHttpResponse response = httpClient.execute(get)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new CloudAuthenticationException("Unable to fetch the GitHub email address");
            }
            for (JsonElement entry : JsonParser.parseString(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject email = entry.getAsJsonObject();
                if (email.has("verified") && email.get("verified").getAsBoolean()
                        && email.has("primary") && email.get("primary").getAsBoolean() && email.has("email")) {
                    return email.get("email").getAsString();
                }
            }
            throw new CloudAuthenticationException("GitHub did not return a verified primary email address");
        } catch (IOException | IllegalArgumentException e) {
            throw new CloudAuthenticationException("Unable to verify the GitHub email response");
        }
    }

    @Override
    public String getUserEmailAddress() throws CloudRuntimeException {
        return null;
    }
}
