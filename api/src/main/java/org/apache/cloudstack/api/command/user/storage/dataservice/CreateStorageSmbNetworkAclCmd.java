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
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.StorageAccessRuleResponse;
import org.apache.cloudstack.api.response.StorageSmbShareResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.storage.dataservice.StorageService;
@APICommand(name="createStorageSmbNetworkAcl",responseObject=StorageAccessRuleResponse.class,
        description="Creates literal source-IP/CIDR allow rules independently of SMB account ACLs.",since="4.23.0",
        authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User})
public class CreateStorageSmbNetworkAclCmd extends BaseStorageServiceAsyncCmd implements UserCmd {
    @Inject private StorageService service;
    @Parameter(name="shareid",type=CommandType.UUID,entityType=StorageSmbShareResponse.class,required=true) private Long shareId;
    @Parameter(name="principaltype",type=CommandType.STRING,description="CIDR or IP_ADDRESS; omitted type is inferred for each literal") private String principalType;
    @Parameter(name="principal",type=CommandType.STRING,description="One literal IP or CIDR") private String principal;
    @Parameter(name="principals",type=CommandType.STRING,description="Comma-separated literal IPs/CIDRs, atomically normalized and deduplicated") private String principals;
    public Long getShareId() { return shareId; }
    public String getPrincipalType() { return principalType; }
    public String getPrincipal() { return principal; }
    public String getPrincipals() { return principals; }

    @Override public String getEventType() { return "STORAGE.SMB.NETWORKACL.CREATE"; }
    @Override public String getEventDescription() { return "Creating SMB source allow rules for share "+shareId; }
    @Override public void execute() { ListResponse<StorageAccessRuleResponse> response=service.createStorageSmbNetworkAcl(this);response.setResponseName(getCommandName());setResponseObject(response); }
}
