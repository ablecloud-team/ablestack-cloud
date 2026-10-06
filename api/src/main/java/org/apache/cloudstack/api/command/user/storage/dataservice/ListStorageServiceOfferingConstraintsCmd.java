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

package org.apache.cloudstack.api.command.user.storage.dataservice;
import java.util.List;
import javax.inject.Inject;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.*;
import org.apache.cloudstack.api.command.user.UserCmd;
import org.apache.cloudstack.api.response.*;
import org.apache.cloudstack.storage.sharedfs.SharedFSService;
@APICommand(name="listStorageServiceOfferingConstraints",responseObject=StorageServiceOfferingConstraintResponse.class,
        description="Returns the same effective compute-offering requirements used by SharedFS creation.",
        since="4.23.0",requestHasSensitiveInfo=false,responseHasSensitiveInfo=false,
        authorized={RoleType.Admin,RoleType.ResourceAdmin,RoleType.DomainAdmin,RoleType.User})
public class ListStorageServiceOfferingConstraintsCmd extends BaseListCmd implements UserCmd {
    @Inject private SharedFSService service;
    @Parameter(name="zoneid",type=CommandType.UUID,entityType=ZoneResponse.class,required=true) private Long zoneId;
    @Parameter(name="serviceofferingids",type=CommandType.LIST,collectionType=CommandType.UUID,entityType=ServiceOfferingResponse.class,required=true)
    private List<Long> serviceOfferingIds;
    public void execute() {
        ListResponse<StorageServiceOfferingConstraintResponse> response=service.listOfferingConstraints(zoneId,serviceOfferingIds);
        response.setResponseName(getCommandName());setResponseObject(response);
    }
}
