// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software distributed under the License is
// distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package org.apache.cloudstack.api.response;

import java.util.Date;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import org.apache.cloudstack.storage.dataservice.StorageServiceRuntimeBundle;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@EntityReference(value = StorageServiceRuntimeBundle.class)
public class StorageServiceRuntimeBundleResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID) @Param(description = "runtime bundle ID") private String id;
    @SerializedName("version") @Param(description = "runtime bundle version") private String version;
    @SerializedName("runtimeabiversion") @Param(description = "runtime ABI version") private String runtimeAbiVersion;
    @SerializedName("desiredstateschemaversion") @Param(description = "desired-state schema version") private String desiredStateSchemaVersion;
    @SerializedName("serviceimpact") @Param(description = "declared service impact") private String serviceImpact;
    @SerializedName("artifacturl") @Param(description = "runtime bundle URL") private String artifactUrl;
    @SerializedName("artifactsize") @Param(description = "runtime bundle size in bytes") private Long artifactSize;
    @SerializedName("sha256") @Param(description = "runtime bundle SHA-256") private String sha256;
    @SerializedName("manifestsha256") @Param(description = "runtime manifest SHA-256") private String manifestSha256;
    @SerializedName("signingkeyid") @Param(description = "trusted signing key ID") private String signingKeyId;
    @SerializedName("state") @Param(description = "runtime bundle state") private String state;
    @SerializedName(ApiConstants.CREATED) @Param(description = "creation time") private Date created;

    @SerializedName("catalog") @Param(description = "verification, compatibility and lifecycle audit metadata") private String catalog;
    @SerializedName("instances") @Param(description = "UUIDs of instances using this runtime bundle") private java.util.List<String> instances;
    public void setCatalog(String value) { catalog = value; }
    public void setInstances(java.util.List<String> value) { instances = value; }
    public void setId(final String value) { id = value; }
    public void setVersion(final String value) { version = value; }
    public void setRuntimeAbiVersion(final String value) { runtimeAbiVersion = value; }
    public void setDesiredStateSchemaVersion(final String value) { desiredStateSchemaVersion = value; }
    public void setServiceImpact(final String value) { serviceImpact = value; }
    public void setArtifactUrl(final String value) { artifactUrl = value; }
    public void setArtifactSize(final Long value) { artifactSize = value; }
    public void setSha256(final String value) { sha256 = value; }
    public void setManifestSha256(final String value) { manifestSha256 = value; }
    public void setSigningKeyId(final String value) { signingKeyId = value; }
    public void setState(final String value) { state = value; }
    public void setCreated(final Date value) { created = value; }
}
