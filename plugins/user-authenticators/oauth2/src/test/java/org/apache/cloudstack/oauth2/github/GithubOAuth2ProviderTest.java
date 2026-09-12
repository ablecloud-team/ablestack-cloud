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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.cloudstack.oauth2.dao.OauthProviderDao;
import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.util.EntityUtils;
import org.junit.Before;
import org.junit.Test;
import com.cloud.exception.CloudAuthenticationException;
import com.google.gson.JsonParser;

public class GithubOAuth2ProviderTest {
    private GithubOAuth2Provider provider;
    private OauthProviderVO config;
    private CloseableHttpClient client;
    private AtomicInteger exchanges;
    private String emails;
    private String requestCode;

    @Before
    public void setup() throws Exception {
        client = mock(CloseableHttpClient.class);
        provider = new GithubOAuth2Provider(client);
        provider._oauthProviderDao = mock(OauthProviderDao.class);
        config = new OauthProviderVO();
        config.setProvider("github");
        config.setEnabled(true);
        config.setClientId("client");
        config.setSecretKey("fixture-secret");
        config.setRedirectUri("https://cloud.example/oauth");
        when(provider._oauthProviderDao.findByProviderAndDomainWithGlobalFallback(eq("github"), any())).thenReturn(config);
        exchanges = new AtomicInteger();
        emails = "[{\"email\":\"user@example.com\",\"primary\":true,\"verified\":true}]";
        when(client.execute(any(HttpPost.class))).thenAnswer(call -> {
            HttpPost request = call.getArgument(0);
            assertEquals("https://github.com/login/oauth/access_token", request.getURI().toString());
            assertEquals("application/json", request.getFirstHeader("Accept").getValue());
            requestCode = JsonParser.parseString(EntityUtils.toString(request.getEntity())).getAsJsonObject().get("code").getAsString();
            return exchanges.incrementAndGet() == 1 ? response(200, "{\"access_token\":\"fixture-token\"}") : response(400, "{}");
        });
        when(client.execute(any(HttpGet.class))).thenAnswer(call -> {
            HttpGet request = call.getArgument(0);
            assertEquals("https://api.github.com/user/emails", request.getURI().toString());
            assertEquals("Bearer fixture-token", request.getFirstHeader("Authorization").getValue());
            return response(200, emails);
        });
    }

    private CloseableHttpResponse response(int status, String body) {
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getStatusLine()).thenReturn(new BasicStatusLine(HttpVersion.HTTP_1_1, status, "fixture"));
        when(response.getEntity()).thenReturn(new StringEntity(body, StandardCharsets.UTF_8));
        return response;
    }

    @Test
    public void discoversThenConsumesAndEscapesCode() {
        String code = "code\"with\\escaping";
        assertEquals("user@example.com", provider.verifySecretCodeAndFetchEmail(code, 2L));
        assertEquals(code, requestCode);
        assertTrue(provider.verifyUser("user@example.com", code, 2L));
        assertEquals(1, exchanges.get());
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", code, 2L));
    }

    @Test
    public void rejectsOtherDomainAndEmail() {
        provider.verifySecretCodeAndFetchEmail("code", 2L);
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code", 3L));
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("other@example.com", "code", 2L));
    }

    @Test
    public void rejectsUnverifiedEmail() {
        emails = emails.replace("\"verified\":true", "\"verified\":false");
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code"));
    }

    @Test
    public void rejectsNonPrimaryEmail() {
        emails = emails.replace("\"primary\":true", "\"primary\":false");
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code"));
    }

    @Test
    public void rejectsDisabledProviderBeforeNetwork() {
        config.setEnabled(false);
        assertThrows(CloudAuthenticationException.class, () -> provider.verifyUser("user@example.com", "code"));
        verifyNoInteractions(client);
    }
}
