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
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

/** The response contains no plaintext connection values. */
public class VeeamRuntimeConfigResponse extends BaseResponse {
    @SerializedName("version") @Param(description = "Runtime envelope version") public int version = 1;
    @SerializedName("wrappedkey") @Param(description = "AES key wrapped with the request's RSA-OAEP-SHA256 public key") public String wrappedkey;
    @SerializedName("nonce") @Param(description = "Base64 AES-GCM nonce") public String nonce;
    @SerializedName("ciphertext") @Param(description = "Base64 AES-256-GCM runtime settings including the authentication tag") public String ciphertext;
}
