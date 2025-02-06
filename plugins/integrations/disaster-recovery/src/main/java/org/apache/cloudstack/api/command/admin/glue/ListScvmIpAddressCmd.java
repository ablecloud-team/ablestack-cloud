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

package org.apache.cloudstack.api.command.admin.glue;

import javax.inject.Inject;

import com.cloud.dr.cluster.DisasterRecoveryClusterService;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.response.ScvmIpAddressResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;


@APICommand(name = ListScvmIpAddressCmd.APINAME,
        description = "list scvm ip address",
        responseObject = ScvmIpAddressResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false)
public class ListScvmIpAddressCmd extends BaseListCmd {
    public static final String APINAME = "listScvmIpAddress";

    @Inject
    private DisasterRecoveryClusterService disasterRecoveryClusterService;

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccountId();
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////
    @Override
    public void execute() {
        ListResponse<ScvmIpAddressResponse> response = disasterRecoveryClusterService.listScvmIpAddressResponse(this);
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }
}