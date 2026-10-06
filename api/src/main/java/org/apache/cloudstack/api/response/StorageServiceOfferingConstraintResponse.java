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

package org.apache.cloudstack.api.response;
import java.util.List;
import com.google.gson.annotations.SerializedName;
import com.cloud.serializer.Param;
import org.apache.cloudstack.api.BaseResponse;
public class StorageServiceOfferingConstraintResponse extends BaseResponse {
    @SerializedName("id") @Param(description="Compute offering ID") private String id;
    @SerializedName("compatible") @Param(description="True only when every effective SharedFS constraint is satisfied") private boolean compatible;
    @SerializedName("reasons") @Param(description="Stable constraint codes") private List<String> reasons;
    @SerializedName("minimumcpu") @Param(description="Zone minimum vCPUs") private int minimumCpu;
    @SerializedName("minimummemory") @Param(description="Zone minimum memory in MiB") private int minimumMemory;
    @SerializedName("fixedresourcesrequired") @Param(description="Fixed CPU and memory are required") private boolean fixedResources=true;
    @SerializedName("harequired") @Param(description="HA is required") private boolean ha=true;
    @SerializedName("dynamicscalingrequired") @Param(description="Online scaling is required") private boolean dynamic=true;
    @SerializedName("zonescalingenabled") @Param(description="Effective zone dynamic scaling flag") private boolean zoneScaling;
    @SerializedName("scalabletemplateready") @Param(description="A ready scalable SystemVM template exists") private boolean templateReady;
    @SerializedName("onlineresizehypervisoravailable") @Param(description="A supported online resize hypervisor exists") private boolean hypervisorReady;
    public StorageServiceOfferingConstraintResponse(String id,List<String> reasons,int cpu,int memory,boolean zone,boolean template,boolean hypervisor) {
        this.id=id;this.reasons=reasons;compatible=reasons.isEmpty();minimumCpu=cpu;minimumMemory=memory;zoneScaling=zone;templateReady=template;hypervisorReady=hypervisor;
        setObjectName("storageserviceofferingconstraint");
    }
    public boolean isCompatible(){return compatible;}
    public List<String> getReasons(){return reasons;}
}
