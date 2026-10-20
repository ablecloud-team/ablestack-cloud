// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice;

import java.util.Set;
import org.junit.Assert;
import org.junit.Test;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.gson.JsonObject;

public class StorageRuntimeFeatureCompatibilityTest {
    @Test public void legacyManifestCannotDiscardAnActiveCommonPolicy() {
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageRuntimeFeatureCompatibility.require(new JsonObject(), Set.of("POSIX_DIRECTORY_POLICY")));
        StorageRuntimeFeatureCompatibility.require(new JsonObject(), Set.of());
    }
    @Test public void signedFeatureListSatisfiesConsumerRequirements() {
        JsonObject manifest = new JsonObject();com.google.gson.JsonArray features = new com.google.gson.JsonArray();
        features.add("POSIX_DIRECTORY_POLICY");features.add("SMB_PARENT_OWNER");manifest.add("supportedFeatures", features);
        StorageRuntimeFeatureCompatibility.require(manifest, Set.of("POSIX_DIRECTORY_POLICY", "SMB_PARENT_OWNER"));
        Assert.assertThrows(CloudRuntimeException.class, () -> StorageRuntimeFeatureCompatibility.require(manifest, Set.of("SMB_NETWORK_ACL")));
    }
    @Test public void commonAndParentOwnershipSemanticsAreIndependentRequirements() {
        JsonObject config = new JsonObject();config.addProperty("relativeSharePath", "shared");config.addProperty("posixPolicyUuid", "id");
        config.addProperty("ownershipInheritance", "INHERIT_PARENT_OWNER");config.addProperty("forceCreateMode", "0775");
        Assert.assertEquals(Set.of("NESTED_FILE_SHARE", "POSIX_DIRECTORY_POLICY", "SMB_PARENT_OWNER", "SMB_CREATION_MODE"), StorageRuntimeFeatureCompatibility.shareFeatures(config, StorageServiceInstance.Protocol.SMB));
    }
}
