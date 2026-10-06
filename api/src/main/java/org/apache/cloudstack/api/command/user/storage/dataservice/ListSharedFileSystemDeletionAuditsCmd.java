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
import org.apache.cloudstack.api.*;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.SharedFSDeletionAuditResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
@APICommand(name="listSharedFileSystemDeletionAudits",responseObject=SharedFSDeletionAuditResponse.class,
        description="Lists service removal audit entries, including preserved volume identities after service removal.",since="4.23.0",
        authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User})
public class ListSharedFileSystemDeletionAuditsCmd extends BaseListCmd implements UserCmd {
    @Inject private SharedFSService service;
    @Parameter(name="sharedfsuuid",type=CommandType.STRING,description="UUID of the active or removed shared filesystem") private String uuid;
    public void execute() {
        ListResponse<SharedFSDeletionAuditResponse> response=service.listSharedFSDeletionAudits(uuid);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}
