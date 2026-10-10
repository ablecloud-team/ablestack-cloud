// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software distributed under the License is
// distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package org.apache.cloudstack.api.response;

import org.apache.cloudstack.api.BaseResponse;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

public class StorageServiceRuntimeCapabilityResponse extends BaseResponse {
    @SerializedName("instanceid") @Param(description = "Storage Service instance ID") private String instanceId;
    @SerializedName("available") @Param(description = "whether runtime upgrade is available") private Boolean available;
    @SerializedName("currentversion") @Param(description = "current runtime version") private String currentVersion;
    @SerializedName("previousversion") @Param(description = "previous runtime version") private String previousVersion;
    @SerializedName("runtimeabiversion") @Param(description = "runtime ABI version") private String runtimeAbiVersion;
    @SerializedName("desiredstateschemaversion") @Param(description = "desired-state schema version") private String desiredStateSchemaVersion;
    @SerializedName("entrypointsmanaged") @Param(description = "whether runtime entrypoints are managed") private Boolean entrypointsManaged;
    @SerializedName("consumerobservation") @Param(description = "fresh manager, agent and protected template platform observation as JSON") private String consumerObservation;
    @SerializedName("details") @Param(description = "capability details") private String details;

    public void setInstanceId(final String value) { instanceId = value; }
    public void setAvailable(final Boolean value) { available = value; }
    public void setCurrentVersion(final String value) { currentVersion = value; }
    public void setPreviousVersion(final String value) { previousVersion = value; }
    public void setRuntimeAbiVersion(final String value) { runtimeAbiVersion = value; }
    public void setDesiredStateSchemaVersion(final String value) { desiredStateSchemaVersion = value; }
    public void setEntrypointsManaged(final Boolean value) { entrypointsManaged = value; }
    public void setConsumerObservation(final String value) { consumerObservation = value; }
    public void setDetails(final String value) { details = value; }
}
