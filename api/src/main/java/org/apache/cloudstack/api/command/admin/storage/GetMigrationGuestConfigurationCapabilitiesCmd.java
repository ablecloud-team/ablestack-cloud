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
package org.apache.cloudstack.api.command.admin.storage;

import com.cloud.storage.MigrationGuestConfigurationService;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.HostResponse;
import org.apache.cloudstack.api.response.MigrationGuestConfigurationResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;

import javax.inject.Inject;

@APICommand(
        name = "getMigrationGuestConfigurationCapabilities",
        responseObject = MigrationGuestConfigurationResponse.class,
        description = "Checks a live KVM migration guest configuration worker",
        authorized = {RoleType.Admin},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false)
public class GetMigrationGuestConfigurationCapabilitiesCmd extends BaseCmd implements AdminCmd {
    @Inject private MigrationGuestConfigurationService service;

    @Parameter(
            name = "storageid",
            type = CommandType.UUID,
            entityType = StoragePoolResponse.class,
            required = true)
    private Long storageId;

    @Parameter(name = "hostid", type = CommandType.UUID, entityType = HostResponse.class)
    private Long hostId;

    public Long getStorageId() {
        return storageId;
    }

    public Long getHostId() {
        return hostId;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public void execute() {
        var response = service.capabilities(this);
        response.setResponseName(getCommandName());
        response.setObjectName("migrationguestconfigurationcapability");
        setResponseObject(response);
    }
}
