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
package org.apache.cloudstack.acl;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import org.apache.cloudstack.acl.RolePermissionEntity.Permission;

import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.apache.cloudstack.context.CallContext;

import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.UnavailableCommandException;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectAccount;
import com.cloud.projects.dao.ProjectAccountDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.PluggableService;

public class ProjectRoleBasedApiAccessChecker  extends AdapterBase implements APIAclChecker {

    @Inject
    ProjectAccountDao projectAccountDao;
    @Inject
    ProjectRoleService projectRoleService;
    @Inject
    RoleService roleService;
    @Inject
    AccountService accountService;
    @Inject
    DomainDao domainDao;

    private List<PluggableService> services;
    protected ProjectRoleBasedApiAccessChecker() {
        super();
    }

    private void denyApiAccess(final String commandName) throws PermissionDeniedException {
        throw new PermissionDeniedException("The API " + commandName + " is denied for the user's/account's project role.");
    }

    private boolean hasProjectAdministrativeAccess(User user, Project project) {
        long accountId = user.getAccountId();
        if (accountService.isRootAdmin(accountId)) {
            return true;
        }
        if (accountService.isDomainAdmin(accountId)) {
            Domain domain = domainDao.findById(project.getDomainId());
            if (domain == null) {
                throw new PermissionDeniedException("The project domain is not available.");
            }
            // Match ProjectManagerImpl.canAccessProjectAccount: domain admins
            // manage projects only in their own domain hierarchy.
            accountService.checkAccess(accountService.getAccount(accountId), domain);
            return true;
        }
        return false;
    }

    @Override
    public boolean isEnabled() {
        if (!roleService.isEnabled()) {
            logger.trace("RoleService is disabled. We will not use ProjectRoleBasedApiAccessChecker.");
        }
        return roleService.isEnabled();
    }

    @Override
    public List<String> getApisAllowedToUser(Role role, User user, List<String> apiNames) throws PermissionDeniedException {
        if (!isEnabled()) {
            return apiNames;
        }

        Project project = CallContext.current().getProject();
        if (project == null) {
            if (logger.isTraceEnabled()) {
                logger.trace(String.format("Project is null, ProjectRoleBasedApiAccessChecker only applies to projects, returning APIs [%s] for user [%s] as allowed.", apiNames, user));
            }
            return apiNames;
        }

        if (hasProjectAdministrativeAccess(user, project)) {
            return apiNames;
        }

        long accountID = user.getAccountId();
        ProjectAccount projectUser = projectAccountDao.findByProjectIdUserId(project.getId(), accountID, user.getId());
        if (projectUser != null) {
            if (projectUser.getAccountRole() != ProjectAccount.Role.Admin) {
                filterApisForMember(project, projectUser, apiNames);
            }
            if (logger.isTraceEnabled()) {
                logger.trace(String.format("Returning APIs [%s] as allowed for user [%s].", apiNames, user));
            }
            return apiNames;
        }

        ProjectAccount projectAccount = projectAccountDao.findByProjectIdAccountId(project.getId(), accountID);
        if (projectAccount == null) {
            throw new PermissionDeniedException(String.format("The user [%s] does not belong to the project [%s].", user, project));
        }

        if (projectAccount.getAccountRole() != ProjectAccount.Role.Admin) {
            filterApisForMember(project, projectAccount, apiNames);
        }
        if (logger.isTraceEnabled()) {
            logger.trace(String.format("Returning APIs [%s] as allowed for user [%s].", apiNames, user));
        }
        return apiNames;
    }

    @Override
    public boolean checkAccess(User user, String apiCommandName, ApiKeyPair keyPair, ApiKeyPairPermission... apiKeyPairPermissions) throws PermissionDeniedException {
        if (!isEnabled()) {
            return true;
        }

        Project project = CallContext.current().getProject();
        if (project == null) {
            if (logger.isTraceEnabled()) {
                logger.trace(String.format("Project is null, ProjectRoleBasedApiAccessChecker only applies to projects, returning API [%s] for user [%s] as allowed.", apiCommandName,
                user));
            }
            return true;
        }

        if (hasProjectAdministrativeAccess(user, project)) {
            return true;
        }

        ProjectAccount projectUser = projectAccountDao.findByProjectIdUserId(project.getId(), user.getAccountId(), user.getId());
        if (projectUser != null) {
            if (projectUser.getAccountRole() == ProjectAccount.Role.Admin || isPermitted(project, projectUser, apiCommandName)) {
                return true;
            }
            denyApiAccess(apiCommandName);
        }

        ProjectAccount projectAccount = projectAccountDao.findByProjectIdAccountId(project.getId(), user.getAccountId());
        if (projectAccount != null) {
            if (projectAccount.getAccountRole() == ProjectAccount.Role.Admin || isPermitted(project, projectAccount, apiCommandName)) {
                return true;
            }
            denyApiAccess(apiCommandName);
        }

        // Default deny all
        if ("updateProjectInvitation".equals(apiCommandName)) {
            return true;
        }

        throw new UnavailableCommandException(String.format("The API [%s] does not exist or is not available for this account/user in project [%s].", apiCommandName, project.getUuid()));
    }

    @Override
    public boolean checkAccess(Account account, String apiCommandName, ApiKeyPair keyPair, ApiKeyPairPermission... apiKeyPairPermissions) throws PermissionDeniedException {
        return true;
    }

    @Override
    public List<String> getApisAllowedToAccount(Account account, List<String> apiNames) {
        return apiNames;
    }

    private void filterApisForMember(Project project, ProjectAccount membership, List<String> apiNames) {
        if (membership.getProjectRoleId() == null) {
            return;
        }
        // Resolve the effective role once for the entire discovery response.
        List<ProjectRolePermission> permissions = projectRoleService.findCurrentUserProjectRolePermissions(project.getId());
        apiNames.removeIf(api -> !isPermitted(permissions, api));
    }

    public boolean isPermitted(Project project, ProjectAccount projectUser, String ... apiCommandNames) {
        if (projectUser.getProjectRoleId() == null) {
            return true;
        }

        return isPermitted(projectRoleService.findCurrentUserProjectRolePermissions(project.getId()), apiCommandNames);
    }

    private boolean isPermitted(List<ProjectRolePermission> allProjectRolePermissions, String ... apiCommandNames) {
        for (String api : apiCommandNames) {
            for (ProjectRolePermission permission : allProjectRolePermissions) {
                if (permission.getRule().matches(api)) {
                    return Permission.ALLOW.equals(permission.getPermission());
                }
            }
        }

        return true;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        return true;
    }

    @Override
    public List<RolePermissionEntity> getImplicitRolePermissions(RoleType roleType) {
        return List.of();
    }

    @Override
    public boolean start() {
        return super.start();
    }

    public List<PluggableService> getServices() {
        return services;
    }

    @Inject
    public void setServices(List<PluggableService> services) {
        this.services = services;
    }
}
