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
package org.apache.cloudstack.api.command.admin.dr;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.response.dr.DrRunResponse;

import com.cloud.dr.cluster.DisasterRecoveryClusterEventTypes;

@APICommand(name = StopDrTestFailoverCmd.APINAME, description = "Stop a DR test failover run", responseObject = DrRunResponse.class,
        responseView = ResponseObject.ResponseView.Full, authorized = {RoleType.Admin})
public class StopDrTestFailoverCmd extends AbstractDrPlanActionCmd {
    public static final String APINAME = "stopDrTestFailover";

    @Override
    protected boolean validatesCapabilitiesAtTargetDispatch() {
        return true;
    }

    @Override
    protected String getRunType() {
        return "TEST_CLEANUP";
    }

    @Override
    protected String getApiName() {
        return APINAME;
    }

    @Override
    public String getEventType() {
        return DisasterRecoveryClusterEventTypes.EVENT_DR_PLAN_TEST_FAILOVER;
    }

    @Override
    public String getEventDescription() {
        return "Stopping DR test failover for plan " + getPlanId();
    }
}
