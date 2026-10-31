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
package org.apache.cloudstack.engine.subsystem.api.storage;

import org.apache.cloudstack.storage.object.Bucket;
import org.apache.cloudstack.storage.object.BucketCredentials;

/** Compatibility contract for providers that resolve bucket credentials by account and store. */
public interface ObjectStoreCredentialProvider {
    default BucketCredentials getBucketCredentials(Bucket bucket) {
        return new BucketCredentials(bucket.getAccessKey(), bucket.getSecretKey(), null, null);
    }
    default BucketCredentials rotateBucketCredentials(Bucket bucket, long revision, String operationId, String action, int graceSeconds) {
        throw new com.cloud.utils.exception.CloudRuntimeException("Credential rotation is not supported by this provider");
    }
    default void recoverCredentialRotations(long storeId) { }
    default void validateAdminCredentials(String url, java.util.Map<String, String> details) {
        throw new com.cloud.utils.exception.CloudRuntimeException("Administrator key replacement is not supported by this provider");
    }
}
