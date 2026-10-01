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

import java.util.Map;
import org.apache.cloudstack.api.BaseResponse;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class VmProcessSnapshotResponse extends BaseResponse {
    @SerializedName("processstate")
    @Param(description = "Process snapshot contract 1.0; internal host authority is omitted")
    @com.google.gson.annotations.JsonAdapter(ProcessStateAdapter.class)
    private Map<String, Object> processState;
    @SerializedName("ttlseconds")
    @Param(description = "Maximum observation lifetime; actions must revalidate")
    private int ttlSeconds = 10;
    @SerializedName("count") @Param(description = "Matching rows in the complete snapshot") private int count;
    @SerializedName("stale") @Param(description = "Snapshot expired or placement changed") private boolean stale;
    @SerializedName("availability") @Param(description = "OK, PARTIAL or UNAVAILABLE") private String availability;
    public void setPageMetadata(int count, boolean stale, String availability) { this.count = count; this.stale = stale; this.availability = availability; }
    public void setProcessState(Map<String, Object> value) { processState = value; }
    public Map<String, Object> getProcessState() { return processState; }
}
