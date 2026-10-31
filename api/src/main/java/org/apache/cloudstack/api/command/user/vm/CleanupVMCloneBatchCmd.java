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
package org.apache.cloudstack.api.command.user.vm;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.*;
import org.apache.cloudstack.api.response.VmCloneBatchResponse;
@APICommand(name="cleanupVMCloneBatch",description="Reconciles physically absent failed clone resources without recreating targets",responseObject=VmCloneBatchResponse.class,authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User},requestHasSensitiveInfo=false,responseHasSensitiveInfo=true)
public class CleanupVMCloneBatchCmd extends BaseAsyncCmd {
    @Parameter(name="requestid",type=CommandType.STRING,required=true,description="Initial clone batch UUID")
    private String requestId;
    @Override public void execute() {
        VmCloneBatchResponse result=_userVmService.cleanupVMCloneBatch(requestId);
        result.setResponseName(getCommandName());setResponseObject(result);
    }
    @Override public String getCommandName(){return "cleanupvmclonebatchresponse";}
    @Override public String getEventType(){return com.cloud.event.EventTypes.EVENT_VM_CLONE;}
    @Override public String getEventDescription(){return "Reconciling failed clone batch "+requestId;}
    @Override public long getEntityOwnerId(){return org.apache.cloudstack.context.CallContext.current().getCallingAccount().getId();}
}
