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

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StoragePoolResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.storage.browser.DataStoreObjectResponse;
import org.apache.cloudstack.storage.browser.StorageBrowser;
import org.apache.cloudstack.context.CallContext;

import javax.inject.Inject;


@APICommand(name = "createRbdImage", description = "Lists objects at specified path on a storage pool.",
            responseObject = DataStoreObjectResponse.class, since = "4.19.0", requestHasSensitiveInfo = false,
            responseHasSensitiveInfo = false)
public class CreateRbdImageCmd extends BaseListCmd {

    @Inject
    StorageBrowser storageBrowser;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StoragePoolResponse.class, required = true,
                 description = "id of the storage pool")
    private Long storeId;


    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, entityType = StoragePoolResponse.class, required = true,
               description = "id of the storage pool")
    private String RbdName;

    @Parameter(name = ApiConstants.SIZE, type = CommandType.LONG, required = true, description = "path to list on storage pool")
    private long RbdSize;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            description = "the zone where certificates are uploaded")
    private Long zoneId;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getZoneId() {
        return zoneId;
    }

    public Long getStoreId() {
        return storeId;
    }

    public String getRbdName() {
        return RbdName;
    }

    public long getRbdSize() {
        return RbdSize;
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() {
        ListResponse<DataStoreObjectResponse> response = storageBrowser.createRbdImageObjects (this);
        response.setResponseName(getCommandName());
        response.setObjectName(getCommandName());
        this.setResponseObject(response);
    }
}