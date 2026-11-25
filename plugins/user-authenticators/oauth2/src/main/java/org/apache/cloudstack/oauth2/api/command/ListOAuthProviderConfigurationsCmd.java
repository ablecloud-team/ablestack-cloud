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

import com.cloud.domain.Domain;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.DomainService;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.auth.UserOAuth2Authenticator;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.oauth2.OAuth2AuthManager;
import org.apache.cloudstack.oauth2.api.response.OauthProviderResponse;
import org.apache.cloudstack.oauth2.vo.OauthProviderVO;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@APICommand(name = "listOauthProviderConfigurations", description = "List OAuth provider configurations for administrators",
        responseObject = OauthProviderResponse.class, authorized = {RoleType.Admin}, since = "4.23.0")
public class ListOAuthProviderConfigurationsCmd extends BaseListCmd {
    private static final Comparator<OauthProviderVO> ORDER = Comparator.comparing(OauthProviderVO::getProvider, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(OauthProviderVO::getDomainId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(OauthProviderVO::getUuid);

    @Parameter(name = ApiConstants.ID, type = CommandType.STRING, description = "UUID of a provider configuration")
    private String id;

    @Parameter(name = ApiConstants.PROVIDER, type = CommandType.STRING, description = "Exact OAuth provider name")
    private String provider;

    @Parameter(name = ApiConstants.SCOPE, type = CommandType.STRING, description = "Provider scope: all, global or domain")
    private String scope;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class, description = "Domain UUID")
    private Long domainId;

    @Parameter(name = ApiConstants.DOMAIN, type = CommandType.STRING, description = "Domain path (ignored when a domain UUID is supplied)")
    private String domainPath;

    @Inject
    private OAuth2AuthManager oauthManager;

    @Inject
    private DomainService domainService;

    @Override
    public void execute() {
        if (!_accountService.isRootAdmin(CallContext.current().getCallingAccountId())) {
            throw new ServerApiException(ApiErrorCode.ACCOUNT_ERROR, "Only root administrators can list managed OAuth configurations");
        }

        final List<OauthProviderVO> configurations = findConfigurations();
        final Set<String> available = oauthManager.listUserOAuth2AuthenticationProviders().stream()
                .map(UserOAuth2Authenticator::getName).collect(Collectors.toSet());
        final List<OauthProviderResponse> rows = page(configurations).stream()
                .map(row -> toResponse(row, available)).collect(Collectors.toList());

        ListResponse<OauthProviderResponse> response = new ListResponse<>();
        response.setResponses(rows, configurations.size());
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    List<OauthProviderVO> findConfigurations() {
        if (StringUtils.isNotBlank(id)) {
            return oauthManager.listOauthProviders(null, id.trim(), null);
        }

        final boolean hasDomain = domainId != null || StringUtils.isNotBlank(domainPath);
        final String requestedScope = StringUtils.defaultIfBlank(scope, hasDomain ? "domain" : "all").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("all", "global", "domain").contains(requestedScope) || hasDomain != requestedScope.equals("domain")) {
            throw new InvalidParameterValueException("Choose all, global, or a specific domain");
        }
        final Long selectedDomain = hasDomain ? resolveDomain() : null;
        final String keyword = StringUtils.trimToEmpty(getKeyword()).toLowerCase(Locale.ROOT);
        return oauthManager.listOauthProviders(null, null, selectedDomain).stream()
                .filter(row -> requestedScope.equals("all") || Objects.equals(row.getDomainId(), selectedDomain))
                .filter(row -> StringUtils.isBlank(provider) || provider.trim().equalsIgnoreCase(row.getProvider()))
                .filter(row -> row.getProvider().toLowerCase(Locale.ROOT).contains(keyword)
                        || StringUtils.defaultString(row.getDescription()).toLowerCase(Locale.ROOT).contains(keyword))
                .sorted(ORDER)
                .collect(Collectors.toList());
    }

    private Long resolveDomain() {
        if (domainId != null) {
            return domainId == Domain.ROOT_DOMAIN ? null : domainId;
        }
        String path = StringUtils.strip(domainPath.trim(), "/");
        if (path.equalsIgnoreCase("ROOT")) {
            return null;
        }
        if (StringUtils.startsWithIgnoreCase(path, "ROOT/")) {
            path = path.substring(5);
        }
        Domain domain = domainService.findDomainByIdOrPath(null, "/" + path + "/");
        if (domain == null) {
            throw new InvalidParameterValueException("OAuth configuration domain could not be resolved");
        }
        return domain.getId() == Domain.ROOT_DOMAIN ? null : domain.getId();
    }

    private List<OauthProviderVO> page(List<OauthProviderVO> rows) {
        final Long size = getPageSizeVal();
        if (StringUtils.isNotBlank(id) || size == null) {
            return rows;
        }
        final long start = getStartIndex();
        if (size <= 0 || start >= rows.size()) {
            return List.of();
        }
        return rows.subList((int) start, (int) Math.min(rows.size(), start + size));
    }

    private OauthProviderResponse toResponse(OauthProviderVO row, Set<String> available) {
        final Domain domain = row.getDomainId() == null ? null : domainService.getDomain(row.getDomainId());
        final OauthProviderResponse response = OauthProviderResponse.forManagement(row, domain, available.contains(row.getProvider()));
        response.setObjectName(ApiConstants.OAUTH_PROVIDER);
        return response;
    }
}
