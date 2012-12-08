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
package com.cloud.server.api.response;

import org.apache.cloudstack.api.ApiConstants;
import com.cloud.utils.IdentityProxy;
import org.apache.cloudstack.api.BaseResponse;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class TrafficMonitorResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID) @Param(description="the ID of the external firewall")
    private IdentityProxy id = new IdentityProxy("host");
    
    @SerializedName(ApiConstants.ZONE_ID) @Param(description="the zone ID of the external firewall")
    private IdentityProxy zoneId = new IdentityProxy("data_center");
    
    @SerializedName(ApiConstants.IP_ADDRESS) @Param(description="the management IP address of the external firewall")
    private String ipAddress;
    
    @SerializedName(ApiConstants.NUM_RETRIES) @Param(description="the number of times to retry requests to the external firewall")
    private String numRetries;
    
    @SerializedName(ApiConstants.TIMEOUT) @Param(description="the timeout (in seconds) for requests to the external firewall")
    private String timeout;
    
    public Long getId() {
    	return id.getValue();
    }
    
    public void setId(Long id) {
    	this.id.setValue(id);
    }
    
    public Long getZoneId() {
    	return zoneId.getValue();
    }
    
    public void setZoneId(Long zoneId) {
    	this.zoneId.setValue(zoneId);
    }
    
    public String getIpAddress() {
    	return ipAddress;
    }
    
    public void setIpAddress(String ipAddress) {
    	this.ipAddress = ipAddress;
    }
    
    public String getNumRetries() {
    	return numRetries;
    }
    
    public void setNumRetries(String numRetries) {
    	this.numRetries = numRetries;
    }
    
    public String getTimeout() {
    	return timeout;
    }
    
    public void setTimeout(String timeout) {
    	this.timeout = timeout;
    }
}
