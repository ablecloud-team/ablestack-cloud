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
package org.apache.cloudstack.api.command.user.resource;

import org.apache.log4j.Logger;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.IdentityMapper;
import org.apache.cloudstack.api.Implementation;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ResourceLimitResponse;
import com.cloud.configuration.ResourceLimit;
import com.cloud.user.UserContext;

@Implementation(description="Updates resource limits for an account or domain.", responseObject=ResourceLimitResponse.class)
public class UpdateResourceLimitCmd extends BaseCmd {
    public static final Logger s_logger = Logger.getLogger(UpdateResourceLimitCmd.class.getName());

    private static final String s_name = "updateresourcelimitresponse";


    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name=ApiConstants.ACCOUNT, type=CommandType.STRING, description="Update resource for a specified account. Must be used with the domainId parameter.")
    private String accountName;

    @IdentityMapper(entityTableName="domain")
    @Parameter(name=ApiConstants.DOMAIN_ID, type=CommandType.LONG, description="Update resource limits for all accounts in specified domain. If used with the account parameter, updates resource limits for a specified account in specified domain.")
    private Long domainId;

    @IdentityMapper(entityTableName="projects")
    @Parameter(name=ApiConstants.PROJECT_ID, type=CommandType.LONG, description="Update resource limits for project")
    private Long projectId;

    @Parameter(name=ApiConstants.MAX, type=CommandType.LONG, description="  Maximum resource limit.")
    private Long max;

    @Parameter(name=ApiConstants.RESOURCE_TYPE, type=CommandType.INTEGER, required=true, description="Type of resource to update. Values are 0, 1, 2, 3, and 4. 0 - Instance. Number of instances a user can create. " +
                                                                                        "1 - IP. Number of public IP addresses a user can own. " +
                                                                                        "2 - Volume. Number of disk volumes a user can create." +
                                                                                        "3 - Snapshot. Number of snapshots a user can create." +
                                                                                        "4 - Template. Number of templates that a user can register/create.")
    private Integer resourceType;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getMax() {
        return max;
    }

    public Long getDomainId() {
        return domainId;
    }

    public Integer getResourceType() {
        return resourceType;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public long getEntityOwnerId() {
        Long accountId = finalyzeAccountId(accountName, domainId, projectId, true);
        if (accountId == null) {
            return UserContext.current().getCaller().getId();
        }

        return accountId;
    }

    @Override
    public void execute(){
        ResourceLimit result = _resourceLimitService.updateResourceLimit(finalyzeAccountId(accountName, domainId, projectId, true), getDomainId(), resourceType, max);
        if (result != null || (result == null && max != null && max.longValue() == -1L)){
            ResourceLimitResponse response = _responseGenerator.createResourceLimitResponse(result);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(BaseCmd.INTERNAL_ERROR, "Failed to update resource limit");
        }
    }
}
