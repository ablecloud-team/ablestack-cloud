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
import java.util.List;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.storage.object.Bucket;
import org.apache.cloudstack.storage.object.BucketCredentials;

/** The only public response permitted to carry bucket keys. Never log or cache. */
public class BucketCredentialResponse extends BaseResponse {
    @SerializedName("id") @Param(description = "Bucket ID") private String id;
    @SerializedName("url") @Param(description = "Bucket URL") private String url;
    @SerializedName("accesskey") @Param(description = "Access key; present only when reveal=true", isSensitive = true) private String accessKey;
    @SerializedName("usersecretkey") @Param(description = "Secret key; present only when reveal=true", isSensitive = true) private String secretKey;
    @SerializedName("accesskeyhint") @Param(description = "Masked key hint") private String hint;
    @SerializedName("credentialstate") @Param(description = "Credential state") private String state;
    @SerializedName("credentialrevision") @Param(description = "Credential revision") private long revision;
    @SerializedName("rotationid") @Param(description = "Rotation operation ID") private String rotationId;
    @SerializedName("rotationstate") @Param(description = "Rotation stage") private String rotationState;
    @SerializedName("recoveryrequired") @Param(description = "Retry required") private boolean recovery;
    @SerializedName("retireafter") @Param(description = "Old key retirement deadline") private Date retireAfter;
    @SerializedName("affectedbuckets") @Param(description = "Buckets sharing this account/store credential") private List<Target> targets;
    public static class Target {
        @SerializedName("id") private String id;
        @SerializedName("name") private String name;
        public Target(Bucket b) { id = b.getUuid(); name = b.getName(); }
    }
    public BucketCredentialResponse(Bucket bucket, String url, BucketCredentials credential, boolean reveal, List<? extends Bucket> buckets) {
        id = bucket.getUuid(); this.url = url; state = credential.getState(); revision = credential.getRevision();
        rotationId = credential.getRotationId(); rotationState = credential.getRotationState();
        recovery = credential.isRecoveryRequired(); retireAfter = credential.getRetireAfter();
        String key = credential.getAccessKey(); hint = key == null ? null : "********" + key.substring(Math.max(0, key.length() - 4));
        if (reveal) { accessKey = key; secretKey = credential.getSecretKey(); }
        targets = buckets.stream().map(Target::new).collect(java.util.stream.Collectors.toList());
        setObjectName("bucketcredential");
    }
}
