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

package org.apache.cloudstack.api.command.user.storage.dataservice;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageAccessRuleResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "updateStorageIscsiAcl",
        responseObject = StorageAccessRuleResponse.class,
        description = "Updates an iSCSI initiator ACL and CHAP settings.",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class UpdateStorageIscsiAclCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StorageAccessRuleResponse.class, required = true, description = "iSCSI ACL ID")
    private Long id;

    @Parameter(name = "initiatoriqn", type = CommandType.STRING, description = "iSCSI initiator IQN")
    private String initiatorIqn;

    @Parameter(name = "permission", type = CommandType.STRING, description = "READ_ONLY or READ_WRITE")
    private String permission;

    @Parameter(name = "chapenabled", type = CommandType.BOOLEAN, description = "enable one-way CHAP authentication")
    private Boolean chapEnabled;

    @Parameter(name = "chapusername", type = CommandType.STRING, description = "CHAP username")
    private String chapUsername;

    @Parameter(name = "chapsecret", type = CommandType.STRING, description = "CHAP secret. Not stored.")
    private String chapSecret;

    @Parameter(name = "mutualchapenabled", type = CommandType.BOOLEAN, description = "enable mutual CHAP authentication")
    private Boolean mutualChapEnabled;

    @Parameter(name = "mutualchapusername", type = CommandType.STRING, description = "mutual CHAP username")
    private String mutualChapUsername;

    @Parameter(name = "mutualchapsecret", type = CommandType.STRING, description = "mutual CHAP secret. Not stored.")
    private String mutualChapSecret;

    public Long getId() {
        return id;
    }

    public String getInitiatorIqn() {
        return initiatorIqn;
    }

    public String getPermission() {
        return permission;
    }

    public Boolean getChapEnabled() {
        return chapEnabled;
    }

    public String getChapUsername() {
        return chapUsername;
    }

    public String getChapSecret() {
        return chapSecret;
    }

    public Boolean getMutualChapEnabled() {
        return mutualChapEnabled;
    }

    public String getMutualChapUsername() {
        return mutualChapUsername;
    }

    public String getMutualChapSecret() {
        return mutualChapSecret;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.ISCSI.ACL.UPDATE";
    }

    @Override
    public String getEventDescription() {
        return "Updating Storage Service iSCSI ACL " + id;
    }

    @Override
    public void execute() {
        StorageAccessRuleResponse response = storageService.updateStorageIscsiAcl(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update iSCSI ACL");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
