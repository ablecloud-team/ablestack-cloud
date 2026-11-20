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

package org.apache.cloudstack.api.command.admin.backup;

import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.NetBackupHostConfigurationResponse;
import org.apache.cloudstack.backup.NetBackupHostConfigurationService;
import org.apache.cloudstack.context.CallContext;

@APICommand(name = "getNetBackupHostConfiguration", description = "Inspect NetBackup Host setup and the offering assignment / VM schedule next steps",
        responseObject = NetBackupHostConfigurationResponse.class, since = "4.23.0", authorized = {RoleType.Admin})
public class GetNetBackupHostConfigurationCmd extends BaseCmd implements AdminCmd {
    @Inject private NetBackupHostConfigurationService service;
    @Parameter(name = ApiConstants.HOST_ID, type = CommandType.UUID, entityType = HostResponse.class, required = true,
            description = "KVM Host to inspect") private Long hostId;
    @Override public void execute() {
        NetBackupHostConfigurationResponse response = service.inspect(hostId);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
    @Override public long getEntityOwnerId() { return CallContext.current().getCallingAccount().getId(); }
}
