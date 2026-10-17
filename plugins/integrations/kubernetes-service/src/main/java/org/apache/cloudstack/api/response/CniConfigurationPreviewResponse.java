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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.cloudstack.api.BaseResponse;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class CniConfigurationPreviewResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "Source CNI UUID, absent for a local draft") public String id;
    @SerializedName("userdata") @Param(description = "Base64 of the exact rendered CNI payload") public String userdata;
    @SerializedName("format") @Param(description = "plain, jinja, or binary") public String format;
    @SerializedName("candidates") @Param(description = "Valid variable candidates in source order") public List<String> candidates = new ArrayList<>();
    @SerializedName("params") @Param(description = "Declared variable names") public List<String> params = new ArrayList<>();
    @SerializedName("values") @Param(description = "Declared custom metadata values") public Map<String, String> values = new LinkedHashMap<>();
    @SerializedName("missing") @Param(description = "Declared custom values that were not supplied") public List<String> missing = new ArrayList<>();
    @SerializedName("extra") @Param(description = "Values outside the declared custom names") public List<String> extra = new ArrayList<>();
    @SerializedName("unresolved") @Param(description = "Preserved tokens without a server rendering value") public List<String> unresolved = new ArrayList<>();
    @SerializedName("unsupported") @Param(description = "Expressions not evaluated by the simple server renderer") public List<String> unsupported = new ArrayList<>();
    @SerializedName("asnumber") @Param(description = "Resolved automatic AS number, if known") public Long asnumber;
    @SerializedName("complete") @Param(description = "Whether all tokens are server-rendered and custom values match") public boolean complete;
}
