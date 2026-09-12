/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cloudstack.oauth2.api.command;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiServerService;
import org.apache.cloudstack.oauth2.OAuth2AuthManager;
import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.utils.HttpUtils;

public class ListOAuthProvidersCmdTest {
    private ListOAuthProvidersCmd cmd;
    private AccountService accounts;
    private HttpServletRequest request;
    private HttpSession session;
    private Map<String, Object[]> params;

    @Before
    public void setup() throws Exception {
        cmd = new ListOAuthProvidersCmd();
        cmd._oauth2mgr = mock(OAuth2AuthManager.class);
        cmd.apiServer = mock(ApiServerService.class);
        accounts = mock(AccountService.class);
        FieldUtils.writeField(cmd, "_accountService", accounts, true);
        request = mock(HttpServletRequest.class);
        session = mock(HttpSession.class);
        params = new HashMap<>();
    }

    private void authenticatedSession() {
        Account account = mock(Account.class);
        when(account.getId()).thenReturn(9L);
        when(session.getAttribute("userid")).thenReturn(8L);
        when(session.getAttribute("account")).thenReturn("fixture");
        when(session.getAttribute("accountobj")).thenReturn(account);
        when(cmd.apiServer.verifyUser(8L)).thenReturn(true);
        when(cmd.apiServer.verifyRequest(any(), anyLong(), any())).thenReturn(true);
    }

    @Test
    public void anonymousAndApiKeyOnlyCallsCannotReadSecrets() {
        params.put(ApiConstants.API_KEY, new String[] {"fixture-api-key"});
        assertFalse(cmd.isSecretKeyAllowedForAuthenticatedCaller(params, null, null, request));
        assertFalse(cmd.isSecretKeyAllowedForAuthenticatedCaller(params, session, null, request));
    }

    @Test
    public void onlyValidatedRootAdminSessionCanReadSecrets() {
        authenticatedSession();
        try (MockedStatic<HttpUtils> http = mockStatic(HttpUtils.class)) {
            http.when(() -> HttpUtils.validateSessionKey(any(), any(), any(), any(), any())).thenReturn(true);
            // Normal user and domain admin are both non-root accounts.
            when(accounts.isRootAdmin(9L)).thenReturn(false);
            assertFalse(cmd.isSecretKeyAllowedForAuthenticatedCaller(params, session, null, request));
            when(accounts.isRootAdmin(9L)).thenReturn(true);
            assertTrue(cmd.isSecretKeyAllowedForAuthenticatedCaller(params, session, null, request));
            when(cmd.apiServer.verifyRequest(any(), anyLong(), any())).thenReturn(false);
            assertFalse(cmd.isSecretKeyAllowedForAuthenticatedCaller(params, session, null, request));
        }
    }

    @Test
    public void invalidSessionKeyCannotReadSecrets() {
        authenticatedSession();
        try (MockedStatic<HttpUtils> http = mockStatic(HttpUtils.class)) {
            http.when(() -> HttpUtils.validateSessionKey(any(), any(), any(), any(), any())).thenReturn(false);
            assertFalse(cmd.isSecretKeyAllowedForAuthenticatedCaller(params, session, null, request));
        }
    }

    @Test
    public void immutableProviderResultIsFilteredByExactDomain() throws Exception {
        params.put(ApiConstants.DOMAIN_ID, new String[] {"domain-2"});
        params.put(ApiConstants.ID, new String[] {"provider-3"});
        when(cmd._oauth2mgr.resolveDomainId(params)).thenReturn(2L);
        OauthProviderVO other = new OauthProviderVO();
        other.setDomainId(3L);
        when(cmd._oauth2mgr.listOauthProviders(null, "provider-3", 2L)).thenReturn(Collections.singletonList(other));
        String response = cmd.authenticate("listOauthProvider", params, null, null, "json", new StringBuilder(), request, mock(HttpServletResponse.class));
        assertFalse(response.contains("provider-3"));
        assertTrue(((org.apache.cloudstack.api.response.ListResponse<?>) cmd.getResponseObject()).getResponses().isEmpty());
    }
    @Test
    public void rootScopeResolvesGlobalAndNextRequestClearsFilters() throws Exception {
        params.put(ApiConstants.DOMAIN_ID, new String[] {"root-domain"});
        params.put(ApiConstants.ID, new String[] {"first-provider"});
        when(cmd._oauth2mgr.resolveDomainId(params)).thenReturn(1L);
        cmd.authenticate("listOauthProvider", params, null, null, "json", new StringBuilder(), request, mock(HttpServletResponse.class));
        org.mockito.Mockito.verify(cmd._oauth2mgr).listOauthProviders(null, "first-provider", -1L);
        Map<String, Object[]> next = new HashMap<>();
        when(cmd._oauth2mgr.resolveDomainId(next)).thenReturn(null);
        cmd.authenticate("listOauthProvider", next, null, null, "json", new StringBuilder(), request, mock(HttpServletResponse.class));
        org.junit.Assert.assertNull(cmd.getId());
        org.junit.Assert.assertNull(cmd.getDomainId());
    }
}
