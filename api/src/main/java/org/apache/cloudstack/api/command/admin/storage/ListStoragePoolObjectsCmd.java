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
import org.apache.cloudstack.storage.browser.DataStoreObjectResponse;
import org.apache.cloudstack.storage.browser.StorageBrowser;

import javax.inject.Inject;
import java.nio.file.Path;


@APICommand(name = "listStoragePoolObjects", description = "Lists objects at specified path on a storage pool.",
            responseObject = DataStoreObjectResponse.class, since = "4.19.0", requestHasSensitiveInfo = false,
            responseHasSensitiveInfo = false)
public class ListStoragePoolObjectsCmd extends BaseListCmd {

    @Inject
    StorageBrowser storageBrowser;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = StoragePoolResponse.class, required = true,
               description = "id of the storage pool")
    private Long storeId;

    @Parameter(name = ApiConstants.PATH, type = CommandType.STRING, description = "path to list on storage pool")
    private String path;

    @Parameter(name = ApiConstants.KEYWORD, type = CommandType.STRING, description = "keyword to list on storage pool")
    private String keyword;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getStoreId() {
        return storeId;
    }

    public String getPath() {
        if (path == null) {
            path = "/";
        }
        // We prepend "/" to path and normalize to prevent path traversal attacks
        return Path.of(String.format("/%s", path)).normalize().toString().substring(1);
    }

    public String getkeyword() {
        return keyword;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() {
        ListResponse<DataStoreObjectResponse> response = storageBrowser.listPrimaryStoreObjects(this);
        response.setResponseName(getCommandName());
        response.setObjectName(getCommandName());
        this.setResponseObject(response);
    }
}
