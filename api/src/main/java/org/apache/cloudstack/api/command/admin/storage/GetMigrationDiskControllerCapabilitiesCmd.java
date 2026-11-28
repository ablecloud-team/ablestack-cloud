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

import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.HostResponse;
import com.cloud.storage.MigrationDiskControllerService;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.admin.AdminCmd;
import org.apache.cloudstack.api.response.MigrationDiskControllerResponse;

import javax.inject.Inject;

@APICommand(
        name = "getMigrationDiskControllerCapabilities",
        responseObject = MigrationDiskControllerResponse.class,
        description = "Queries live KVM migration disk driver capabilities",
        authorized = {RoleType.Admin},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false)
public class GetMigrationDiskControllerCapabilitiesCmd extends BaseCmd implements AdminCmd {
    @Inject private MigrationDiskControllerService service;

    @Parameter(
            name = "storageid",
            type = CommandType.UUID,
            entityType = StoragePoolResponse.class,
            required = true)
    private Long storageId;

    @Parameter(name = "hostid", type = CommandType.UUID, entityType = HostResponse.class)
    private Long hostId;

    @Parameter(name = "machine", type = CommandType.STRING, required = true)
    private String machine;

    @Parameter(name = "firmware", type = CommandType.STRING, required = true)
    private String firmware;

    public Long getStorageId() {
        return storageId;
    }

    public Long getHostId() {
        return hostId;
    }

    public String getMachine() {
        return machine;
    }

    public String getFirmware() {
        return firmware;
    }

    @Override
    public long getEntityOwnerId() {
        return 0;
    }

    @Override
    public void execute() {
        var response = service.capabilities(this);
        response.setResponseName(getCommandName());
        response.setObjectName("migrationdiskcontrollercapability");
        setResponseObject(response);
    }
}
