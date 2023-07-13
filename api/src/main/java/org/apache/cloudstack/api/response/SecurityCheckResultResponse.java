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

import java.util.Date;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class SecurityCheckResultResponse extends BaseResponse {
    @SerializedName(ApiConstants.SECURITY_CHECK_NAME)
    @Param(description = "the name of the security check on the mshost")
    private String checkName;

    @SerializedName(ApiConstants.RESULT)
    @Param(description = "result of the security check")
    private boolean result;

    @SerializedName(ApiConstants.LAST_UPDATED)
    @Param(description = "the date this mshost was updated")
    private Date lastUpdated;

    @SerializedName(ApiConstants.DETAILS)
    @Param(description = "detailed response generated on running security check")
    private String details;

    public String getCheckName() {
        return checkName;
    }

    public boolean getResult() {
        return result;
    }

    public Date getLastUpdated() {
        return lastUpdated;
    }

    public String getDetails() {
        return details;
    }

    public void setCheckName(String checkName) {
        this.checkName = checkName;
    }

    public void setResult(boolean result) {
        this.result = result;
    }

    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
