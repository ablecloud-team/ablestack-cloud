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
package org.apache.cloudstack.api.command.user.storage.sharedfs;
import javax.inject.Inject;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseListProjectAndAccountResourcesCmd;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.SharedFSDeletionResponse;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
@APICommand(name="listSharedFileSystemDeletionOperations",responseObject=SharedFSDeletionResponse.class,description="List authorized durable SharedFS deletion history, including removed services.",since="4.23.0",requestHasSensitiveInfo=false,authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User})
public class ListSharedFSDeletionOperationsCmd extends BaseListProjectAndAccountResourcesCmd implements UserCmd {
    @Inject private SharedFSService service;
    public long getEntityOwnerId(){return CallContext.current().getCallingAccount().getId();}
    public void execute(){ListResponse<SharedFSDeletionResponse> r=service.listDeletionOperations(this);r.setResponseName(getCommandName());setResponseObject(r);}
}
