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
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.api.response.SharedFSPreparationResponse;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;

@APICommand(name="getSharedFileSystemPreparation", description="Observes the original initial DATA operation without starting or formatting it.",
    responseObject=SharedFSPreparationResponse.class, entityType=SharedFS.class, since="4.23.0",
    authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User})
public class GetSharedFSPreparationCmd extends BaseCmd implements org.apache.cloudstack.api.command.user.UserCmd {
    @Inject private SharedFSService sharedFSService;
    @Parameter(name=ApiConstants.ID,type=CommandType.UUID,entityType=SharedFSResponse.class,required=true,
        description="SharedFS ID") private Long id;
    @Parameter(name="requestid",type=CommandType.STRING,required=true,description="Original creation UUID") private String requestId;
    @Override public long getEntityOwnerId() { return org.apache.cloudstack.context.CallContext.current().getCallingAccount().getId(); }
    @Override public void execute() {
        SharedFSPreparationResponse response=new SharedFSPreparationResponse();
        response.setPreparationJson(sharedFSService.getSharedFSPreparation(id,requestId));
        response.setObjectName("sharedfilesystempreparation");response.setResponseName(getCommandName());setResponseObject(response);
    }
}
