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
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageAccessRuleResponse;
import org.apache.cloudstack.api.response.StorageNfsExportResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;

@APICommand(name = "createStorageNfsAcl",
        responseObject = StorageAccessRuleResponse.class,
        description = "Creates an NFS ACL rule for an export.",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        since = "4.21.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class CreateStorageNfsAclCmd extends BaseAsyncCmd implements UserCmd {
    @Inject
    StorageService storageService;

    @Parameter(name = "exportid", type = CommandType.UUID, entityType = StorageNfsExportResponse.class, required = true, description = "NFS export ID")
    private Long exportId;

    @Parameter(name = "principaltype", type = CommandType.STRING, description = "principal type. CIDR or IP_ADDRESS. Defaults to CIDR.")
    private String principalType;

    @Parameter(name = "principal", type = CommandType.STRING, required = true, description = "CIDR or IP address. Multiple values may be comma-separated.")
    private String principal;

    @Parameter(name = "principals", type = CommandType.STRING, description = "comma-separated CIDR or IP address list")
    private String principals;

    @Parameter(name = "permission", type = CommandType.STRING, required = true, description = "READ_ONLY or READ_WRITE")
    private String permission;

    @Parameter(name = "rootsquash", type = CommandType.BOOLEAN, description = "enable root squash for this ACL")
    private Boolean rootSquash;

    @Parameter(name = "allsquash", type = CommandType.BOOLEAN, description = "map all client users to the anonymous NFS user for this ACL")
    private Boolean allSquash;

    @Parameter(name = "anonuid", type = CommandType.INTEGER, description = "anonymous UID used by root/all squash for this ACL")
    private Integer anonUid;

    @Parameter(name = "anongid", type = CommandType.INTEGER, description = "anonymous GID used by root/all squash for this ACL")
    private Integer anonGid;

    @Parameter(name = "sync", type = CommandType.BOOLEAN, description = "use sync export option for this ACL")
    private Boolean sync;

    @Parameter(name = "secure", type = CommandType.BOOLEAN, description = "use secure export option for this ACL")
    private Boolean secure;

    public Long getExportId() {
        return exportId;
    }

    public String getPrincipalType() {
        return principalType;
    }

    public String getPrincipal() {
        return principal;
    }

    public String getPrincipals() {
        return principals;
    }

    public String getPermission() {
        return permission;
    }

    public Boolean getRootSquash() {
        return rootSquash;
    }

    public Boolean getAllSquash() {
        return allSquash;
    }

    public Integer getAnonUid() {
        return anonUid;
    }

    public Integer getAnonGid() {
        return anonGid;
    }

    public Boolean getSync() {
        return sync;
    }

    public Boolean getSecure() {
        return secure;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public String getEventType() {
        return "STORAGE.NFS.ACL.CREATE";
    }

    @Override
    public String getEventDescription() {
        return "Creating Storage Service NFS ACL for export " + exportId;
    }

    @Override
    public void execute() {
        StorageAccessRuleResponse response = storageService.createStorageNfsAcl(this);
        if (response == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create NFS ACL");
        }
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
