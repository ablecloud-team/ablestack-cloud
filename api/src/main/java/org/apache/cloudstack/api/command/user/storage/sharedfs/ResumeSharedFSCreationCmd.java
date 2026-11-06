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
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.SharedFSResponse;
import org.apache.cloudstack.storage.sharedfs.SharedFS;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = "resumeSharedFileSystemCreation", description = "Observes and resumes a journaled initial SharedFS allocation.",
        responseObject = SharedFSResponse.class, entityType = SharedFS.class, since = "4.23.0",
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class ResumeSharedFSCreationCmd extends BaseAsyncCmd implements org.apache.cloudstack.api.command.user.UserCmd {
    @Inject private SharedFSService sharedFSService;
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = SharedFSResponse.class, required = true,
            description = "SharedFS ID from the original creation request") private Long id;
    @Parameter(name = "requestid", type = CommandType.STRING, required = true, description = "Original creation UUID") private String requestId;
    @Parameter(name = "action", type = CommandType.STRING, description = "CONTINUE, MOUNT_EXISTING or CANCEL_PREPARATION") private String action;
    public Long getId() { return id; }
    public String getRequestId() { return requestId; }
    public String getAction() { return action == null ? "CONTINUE" : action; }
    @Override public long getEntityOwnerId() { return CallContext.current().getCallingAccount().getId(); }
    @Override public String getEventType() { return "SHAREDFS.CREATE.RESUME"; }
    @Override public String getEventDescription() { return "Resuming initial SharedFS allocation " + getResourceUuid(ApiConstants.ID); }
    @Override public void execute() {
        try {
            SharedFS fs = sharedFSService.resumeSharedFSCreation(id, requestId, getAction());
            SharedFSResponse response = _responseGenerator.createSharedFSResponse(getResponseView(), fs);
            response.setObjectName("sharedfs"); response.setResponseName(getCommandName()); setResponseObject(response);
        } catch (com.cloud.exception.ResourceUnavailableException | com.cloud.exception.InsufficientCapacityException
                | com.cloud.exception.ResourceAllocationException | com.cloud.exception.OperationTimedoutException e) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Unable to resume initial SharedFS allocation", e);
        }
    }
}
