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
package org.apache.cloudstack.api.command.user.offering;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.IdentityMapper;
import org.apache.cloudstack.api.Implementation;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.ListResponse;
import com.cloud.offering.DiskOffering;

@Implementation(description="Lists all available disk offerings.", responseObject=DiskOfferingResponse.class)
public class ListDiskOfferingsCmd extends BaseListCmd {
    public static final Logger s_logger = Logger.getLogger(ListDiskOfferingsCmd.class.getName());

    private static final String s_name = "listdiskofferingsresponse";

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @IdentityMapper(entityTableName="domain")
    @Parameter(name=ApiConstants.DOMAIN_ID, type=CommandType.LONG, description="the ID of the domain of the disk offering.")
    private Long domainId;

    @IdentityMapper(entityTableName="disk_offering")
    @Parameter(name=ApiConstants.ID, type=CommandType.LONG, description="ID of the disk offering")
    private Long id;

    @Parameter(name=ApiConstants.NAME, type=CommandType.STRING, description="name of the disk offering")
    private String diskOfferingName;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getDomainId() {
        return domainId;
    }

    public Long getId() {
        return id;
    }

    public String getDiskOfferingName() {
        return diskOfferingName;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public String getCommandName() {
        return s_name;
    }

    @Override
    public void execute(){
        List<? extends DiskOffering> result = _mgr.searchForDiskOfferings(this);
        ListResponse<DiskOfferingResponse> response = new ListResponse<DiskOfferingResponse>();
        List<DiskOfferingResponse> diskOfferingResponses = new ArrayList<DiskOfferingResponse>();
        for (DiskOffering offering : result) {
            DiskOfferingResponse diskOffResp = _responseGenerator.createDiskOfferingResponse(offering);
            diskOffResp.setObjectName("diskoffering");
            diskOfferingResponses.add(diskOffResp);
        }

        response.setResponses(diskOfferingResponses);
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }
}
