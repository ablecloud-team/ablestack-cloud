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

package org.apache.cloudstack.api.command.admin.rack;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.RackLayoutResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import com.cloud.user.Account;

// ※ 참고: RackLayoutService는 다음 단계에서 만들 서비스 인터페이스입니다.
import com.cloud.rack.RackLayoutService;

@APICommand(name = UpdateRackLayoutCmd.APINAME,
        description = "Updates or creates a rack layout configuration for a specific zone",
        responseObject = RackLayoutResponse.class,
        responseView = ResponseView.Restricted,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin})
public class UpdateRackLayoutCmd extends BaseCmd {
    public static final String APINAME = "updateRackLayout";

    @Inject
    private RackLayoutService rackLayoutService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true,
            description = "the ID of the zone")
    private Long zoneId;

    @Parameter(name = ApiConstants.NAME, type = CommandType.STRING, required = false,
            description = "name of the layout. Defaults to 'default' if not specified.")
    private String name;

    // Keep the API below the MEDIUMTEXT capacity while allowing multi-rack layouts.
    @Parameter(name = "content", type = CommandType.STRING, required = true, length = 1048576,
            description = "JSON string representing the rack diagram layout")
    private String content;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getZoneId() {
        return zoneId;
    }

    public String getName() {
        return name == null ? "default" : name;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ServerApiException {
        // 서비스 레이어 호출하여 로직 수행 후 결과 받기
        RackLayoutResponse response = rackLayoutService.updateRackLayout(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
