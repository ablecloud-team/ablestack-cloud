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

package org.apache.cloudstack.api.response.dr;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class DrVmProtectionViewResponse extends BaseResponse {
    @SerializedName("virtualmachineid") @Param(description = "the viewed virtual machine ID") private String virtualMachineId;
    @SerializedName("virtualmachinename") @Param(description = "the viewed virtual machine name") private String virtualMachineName;
    @SerializedName("managementscope") @Param(description = "the authority scope of this view") private String managementScope;
    @SerializedName("configured") @Param(description = "true when a local DR relationship exists") private boolean configured;
    @SerializedName("relationconflict") @Param(description = "true when conflicting active permanent relationships exist") private boolean relationConflict;
    @SerializedName("projectionstate") @Param(description = "the aggregate local projection state") private String projectionState;
    @SerializedName("generated") @Param(description = "the local DB view generation time") private Date generated;
    @SerializedName("association")
    @Param(description = "the DR plan relationships", responseObject = DrVmPlanAssociationResponse.class)
    private List<DrVmPlanAssociationResponse> associations = new ArrayList<DrVmPlanAssociationResponse>();

    public DrVmProtectionViewResponse() { setObjectName("drvmprotectionview"); }
    public String getVirtualMachineId() { return virtualMachineId; }
    public boolean isConfigured() { return configured; }
    public boolean isRelationConflict() { return relationConflict; }
    public String getProjectionState() { return projectionState; }
    public List<DrVmPlanAssociationResponse> getAssociations() { return associations; }
    public void setVirtualMachineId(String value) { virtualMachineId = value; }
    public void setVirtualMachineName(String value) { virtualMachineName = value; }
    public void setManagementScope(String value) { managementScope = value; }
    public void setConfigured(boolean value) { configured = value; }
    public void setRelationConflict(boolean value) { relationConflict = value; }
    public void setProjectionState(String value) { projectionState = value; }
    public void setGenerated(Date value) { generated = value; }
    public void setAssociations(List<DrVmPlanAssociationResponse> value) { associations = value; }
}
