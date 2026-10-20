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
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;
public class StorageNfsServiceSettingsResponse extends BaseResponse {
    @SerializedName("instanceid") @Param(description="Storage Service instance ID") private String instanceId;
    @SerializedName("idmappingmode") @Param(description="Desired service-wide NAME_DOMAIN or NUMERIC policy") private String desired;
    @SerializedName("runtimeidmappingmode") @Param(description="Observed Ganesha owner mode, or UNKNOWN") private String runtime;
    @SerializedName("effectiveidmappingmode") @Param(description="Verified runtime mode, or UNKNOWN") private String effective;
    @SerializedName("idmappingdrift") @Param(description="CONSISTENT, DRIFT, or UNKNOWN") private String drift;
    @SerializedName("configuredendpointcount") @Param(description="Enabled NFS listener count") private int endpoints;
    public void setInstanceId(String value){instanceId=value;}
    public void setDesired(String value){desired=value;}
    public void setRuntime(String value){runtime=value;}
    public String getRuntime(){return runtime;}
    public void setEffective(String value){effective=value;}
    public void setDrift(String value){drift=value;}
    public void setEndpoints(int value){endpoints=value;}
}
