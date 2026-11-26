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
package org.apache.cloudstack.api.command.admin.vm;

import org.apache.cloudstack.api.response.UserVmResponse;
import com.cloud.storage.MigrationDiskControllerService;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.MigrationDiskControllerResponse;

import javax.inject.Inject;

@APICommand(
        name = "verifyMigratedVirtualMachine",
        responseObject = MigrationDiskControllerResponse.class,
        description = "Binds migration disks before first start or verifies actual running XML",
        authorized = {RoleType.Admin},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false)
public class VerifyMigratedVirtualMachineCmd extends BaseCmd implements AdminCmd {
    @Inject private MigrationDiskControllerService service;

    @Parameter(
            name = "id",
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true)
    private Long id;

    @Parameter(name = "planhash", type = CommandType.STRING, required = true)
    private String planHash;

    @Parameter(
            name = "controllerplan",
            type = CommandType.STRING,
            length = 65536,
            description =
                    "Optional canonical operator intent for initial checkpoint reconciliation")
    private String controllerPlan;

    public String getControllerPlan() {
        return controllerPlan;
    }

    public Long getId() {
        return id;
    }

    public String getPlanHash() {
        return planHash;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public void execute() {
        var response = service.verify(this);
        response.setResponseName(getCommandName());
        response.setObjectName("migrationdiskcontroller");
        setResponseObject(response);
    }
}
