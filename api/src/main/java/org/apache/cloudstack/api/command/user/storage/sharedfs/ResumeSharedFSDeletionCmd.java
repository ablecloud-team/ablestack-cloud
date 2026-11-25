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
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.SharedFSDeletionResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
@APICommand(name="resumeSharedFileSystemDeletion",responseObject=SharedFSDeletionResponse.class,description="SharedFS deletion resume",since="4.23.0",requestHasSensitiveInfo=false,authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User})
public class ResumeSharedFSDeletionCmd extends BaseAsyncCmd implements UserCmd {
    @Inject private SharedFSService service;
    @Parameter(name="id",type=CommandType.STRING,description="SharedFS UUID, including a removed service for durable operation lookup.") private String id;
    @Parameter(name="requestid",type=CommandType.STRING,length=64,description="Deletion request key.") private String requestId;
    @Parameter(name="operationid",type=CommandType.STRING,description="Deletion operation UUID.") private String operationId;
    public String getEventType(){return com.cloud.event.EventTypes.EVENT_SHAREDFS_EXPUNGE;}
    public String getEventDescription(){return "Resume SharedFS deletion "+operationId;}
    public long getEntityOwnerId(){return CallContext.current().getCallingAccount().getId();}
    public void execute(){
        SharedFSDeletionResponse response=new SharedFSDeletionResponse(service.resumeDeletion(operationId,requestId));
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}
