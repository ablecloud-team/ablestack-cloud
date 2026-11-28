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

import com.cloud.storage.MigrationGuestConfigurationService;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.MigrationGuestConfigurationResponse;
import org.apache.cloudstack.api.response.UserVmResponse;

import javax.inject.Inject;

@APICommand(
        name = "reconcileMigratedGuestConfiguration",
        responseObject = MigrationGuestConfigurationResponse.class,
        description = "Binds and observes a fixed migration helper over QGA file RPCs",
        authorized = {RoleType.Admin},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false)
public class ReconcileMigratedGuestConfigurationCmd extends BaseCmd implements AdminCmd {
    @Inject private MigrationGuestConfigurationService service;

    @Parameter(
            name = "id",
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            required = true)
    private Long id;

    @Parameter(name = "planhash", type = CommandType.STRING, required = true)
    private String planHash;

    @Parameter(name = "operationid", type = CommandType.STRING, required = true)
    private String operationId;

    public Long getId() {
        return id;
    }

    public String getPlanHash() {
        return planHash;
    }

    public String getOperationId() {
        return operationId;
    }

    @Parameter(name = "contractversion", type = CommandType.INTEGER, description = "Guest configuration contract version (1 or 2)")
    private Integer contractVersion;

    public int getContractVersion() {
        int version = contractVersion == null ? 1 : contractVersion;
        if (version != 1 && version != 2) throw new com.cloud.exception.InvalidParameterValueException("GUEST_CONFIG_CONTRACT_UNSUPPORTED");
        return version;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public void execute() {
        var response = service.reconcile(this);
        response.setResponseName(getCommandName());
        response.setObjectName("migrationguestconfiguration");
        setResponseObject(response);
    }
}
