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

import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.BaseResponse;

import java.util.List;
import java.util.Map;

public class MigrationDiskControllerResponse extends BaseResponse {
    @SerializedName("id")
    private String id;

    @SerializedName("storageid")
    private String storageId;

    @SerializedName("machine")
    private String machine;

    @SerializedName("firmware")
    private String firmware;

    @SerializedName("contractversion")
    private int contractVersion = 1;

    @SerializedName("supported")
    private boolean supported;

    @SerializedName("verified")
    private boolean verified;

    @SerializedName("scope")
    private String scope;

    @SerializedName("planhash")
    private String planHash;

    @SerializedName("reason")
    private String reason;

    @SerializedName("buses")
    private List<String> buses;

    @SerializedName("observation")
    private Map<String, Object> observation;

    public void capabilityScope(String pool, String model, String fw) {
        storageId = pool;
        machine = model;
        firmware = fw;
    }

    public void supported(boolean value) {
        supported = value;
    }

    public void buses(List<String> value) {
        buses = value;
    }

    public void reason(String value) {
        reason = value;
    }

    public void evidence(String vm, String hash, String phase, Map<String, Object> proof) {
        id = vm;
        planHash = hash;
        scope = phase;
        observation = proof;
        verified = true;
        supported = true;
    }
}
