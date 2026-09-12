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
package org.apache.cloudstack.oauth2;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.domain.Domain;
import com.cloud.exception.CloudAuthenticationException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

/** Short-lived, single-consumption bridge between OAuth discovery and login. */
public final class OAuth2FlowCache {
    private final Cache<String, String> emails;

    public OAuth2FlowCache() {
        this(Ticker.systemTicker());
    }

    OAuth2FlowCache(Ticker ticker) {
        emails = Caffeine.newBuilder().ticker(ticker)
                .expireAfterWrite(60, TimeUnit.SECONDS).maximumSize(1024).build();
    }

    private String key(OauthProviderVO provider, Long domainId, String code) {
        if (provider == null || !provider.isEnabled() || StringUtils.isBlank(code)) {
            throw new CloudAuthenticationException("OAuth provider is unavailable or authorization code is empty");
        }
        Long scope = domainId == null || domainId == Domain.ROOT_DOMAIN ? null : domainId;
        // Length-delimited configuration prevents reuse after a provider is edited.
        StringBuilder context = new StringBuilder();
        for (Object value : Arrays.asList(scope, provider.getId(), provider.getProvider(), provider.getClientId(),
                provider.getSecretKey(), provider.getRedirectUri(), provider.getTokenUrl(), code)) {
            String part = String.valueOf(value);
            context.append(part.length()).append(':').append(part);
        }
        return DigestUtils.sha256Hex(context.toString());
    }

    public String discover(OauthProviderVO provider, Long domainId, String code, Supplier<String> exchange) {
        return emails.get(key(provider, domainId, code), ignored -> requireEmail(exchange.get()));
    }

    public String consume(OauthProviderVO provider, Long domainId, String code, Supplier<String> exchange) {
        String email = emails.asMap().remove(key(provider, domainId, code));
        // Direct login does not create another reusable discovery entry.
        return email != null ? email : requireEmail(exchange.get());
    }

    private String requireEmail(String email) {
        if (StringUtils.isBlank(email)) {
            throw new CloudAuthenticationException("OAuth provider did not return a verified email address");
        }
        return email;
    }
}
