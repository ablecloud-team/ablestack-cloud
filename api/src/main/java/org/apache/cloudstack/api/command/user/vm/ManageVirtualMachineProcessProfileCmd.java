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
import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VmProcessActionResponse;
import org.apache.cloudstack.vm.process.VmProcessProfileService;
@APICommand(name="manageVirtualMachineProcessProfile",description="Manage VM-scoped administrator registered fixed execution profiles",responseObject=VmProcessActionResponse.class,requestHasSensitiveInfo=false,responseHasSensitiveInfo=true,since="4.23.0",authorized={RoleType.Admin})
public class ManageVirtualMachineProcessProfileCmd extends BaseCmd {
    @Inject private VmProcessProfileService service;
    @ACL(accessType=AccessType.OperateEntry)
    @Parameter(name="virtualmachineid",type=CommandType.UUID,entityType=UserVmResponse.class,required=true,description="Target VM") private Long vmId;
    @Parameter(name="profileid",type=CommandType.STRING,required=true,description="Guest registered profile UUID") private String profileId;
    @Parameter(name="profileversion",type=CommandType.INTEGER,required=true,description="Immutable registered profile version") private Integer profileVersion;
    @Parameter(name="operation",type=CommandType.STRING,required=true,description="REGISTER, APPROVE or RETIRE") private String operation;
    @Override public void execute(){VmProcessActionResponse r=service.manage(vmId,profileId,profileVersion,operation);r.setObjectName("processprofiles");r.setResponseName(getCommandName());setResponseObject(r);}
    @Override public long getEntityOwnerId(){return com.cloud.user.Account.ACCOUNT_ID_SYSTEM;}
}
