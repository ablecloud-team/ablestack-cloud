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
package org.apache.cloudstack.oauth2.api.command;

import com.cloud.user.DomainService;
import org.apache.cloudstack.oauth2.OAuth2AuthManager;
import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ListOAuthProviderConfigurationsCmdTest {
    private ListOAuthProviderConfigurationsCmd command;
    private OAuth2AuthManager manager;

    @Before
    public void setUp() {
        command = new ListOAuthProviderConfigurationsCmd();
        manager = mock(OAuth2AuthManager.class);
        ReflectionTestUtils.setField(command, "oauthManager", manager);
        ReflectionTestUtils.setField(command, "domainService", mock(DomainService.class));
    }

    private OauthProviderVO row(String provider, Long domain, String description, String uuid) {
        OauthProviderVO configuration = mock(OauthProviderVO.class);
        when(configuration.getProvider()).thenReturn(provider);
        when(configuration.getDomainId()).thenReturn(domain);
        when(configuration.getDescription()).thenReturn(description);
        when(configuration.getUuid()).thenReturn(uuid);
        return configuration;
    }

    @Test
    public void matchesKeywordLiterallyAndSortsAcrossScopes() {
        OauthProviderVO domain = row("github", 20L, "Team A_100%", "2");
        OauthProviderVO global = row("github", null, "Global A_100%", "1");
        OauthProviderVO unrelated = row("google", null, "Other", "3");
        when(manager.listOauthProviders(null, null, null)).thenReturn(List.of(unrelated, domain, global));

        ReflectionTestUtils.setField(command, "keyword", "a_100%");
        assertEquals(List.of(global, domain), command.findConfigurations());

        ReflectionTestUtils.setField(command, "scope", "global");
        assertEquals(List.of(global), command.findConfigurations());
    }

    @Test
    public void domainScopeExcludesGlobalAndOtherDomains() {
        OauthProviderVO matching = row("github", 20L, "Team", "a");
        List<OauthProviderVO> available = List.of(row("github", null, "Global", "b"), matching, row("google", 20L, "Other", "c"));
        when(manager.listOauthProviders(null, null, 20L)).thenReturn(available);
        ReflectionTestUtils.setField(command, "scope", "domain");
        ReflectionTestUtils.setField(command, "domainId", 20L);
        ReflectionTestUtils.setField(command, "provider", "GitHub");
        assertEquals(List.of(matching), command.findConfigurations());
    }

    @Test
    public void detailLookupUsesOnlyTheUuid() {
        OauthProviderVO matching = row("github", 20L, "Team", "a");
        when(manager.listOauthProviders(null, "a", null)).thenReturn(List.of(matching));
        ReflectionTestUtils.setField(command, "id", "a");
        ReflectionTestUtils.setField(command, "keyword", "irrelevant");
        ReflectionTestUtils.setField(command, "scope", "global");
        assertEquals(List.of(matching), command.findConfigurations());
        verify(manager).listOauthProviders(null, "a", null);
    }
}
