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
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonObject;
@EntityReference(value=org.apache.cloudstack.storage.dataservice.StorageSmbServiceName.class)
public class StorageSmbServiceNameResponse extends BaseResponse {
    @SerializedName("id") @Param(description="id") private String id;
    public void setId(String value){id=value;}
    @SerializedName("instanceid") @Param(description="instanceid") private String instanceid;
    public void setInstanceid(String value){instanceid=value;}
    @SerializedName("fqdn") @Param(description="fqdn") private String fqdn;
    public void setFqdn(String value){fqdn=value;}
    @SerializedName("kind") @Param(description="kind") private String kind;
    public void setKind(String value){kind=value;}
    @SerializedName("endpointid") @Param(description="endpointid") private String endpointid;
    public void setEndpointid(String value){endpointid=value;}
    @SerializedName("ip") @Param(description="ip") private String ip;
    public void setIp(String value){ip=value;}
    @SerializedName("port") @Param(description="port") private Integer port;
    public void setPort(Integer value){port=value;}
    @SerializedName("state") @Param(description="state") private String state;
    public void setState(String value){state=value;}
    @SerializedName("revision") @Param(description="revision") private Long revision;
    public void setRevision(Long value){revision=value;}
    @SerializedName("ownership") @Param(description="ownership") private JsonObject ownership;
    public void setOwnership(JsonObject value){ownership=value;}
    @SerializedName("observation") @Param(description="observation") private JsonObject observation;
    public void setObservation(JsonObject value){observation=value;}
    @SerializedName("desired") @Param(description="desired") private JsonObject desired;
    public void setDesired(JsonObject value){desired=value;}
    public StorageSmbServiceNameResponse(){setObjectName("servicename");}
}
