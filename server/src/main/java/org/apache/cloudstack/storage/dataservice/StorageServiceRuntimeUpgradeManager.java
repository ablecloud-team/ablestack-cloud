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
package org.apache.cloudstack.storage.dataservice;

import org.apache.cloudstack.api.command.admin.storage.dataservice.GetStorageServiceRuntimeUpgradeCapabilitiesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceRuntimeBundlesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.ListStorageServiceRuntimeUpgradesCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.PreflightStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RegisterStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpdateStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.DeleteStorageServiceRuntimeBundleCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.RollbackStorageServiceRuntimeUpgradeCmd;
import org.apache.cloudstack.api.command.admin.storage.dataservice.UpgradeStorageServiceRuntimeCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeBundleResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeCapabilityResponse;
import org.apache.cloudstack.api.response.StorageServiceRuntimeUpgradeResponse;

public interface StorageServiceRuntimeUpgradeManager {
    /** Older deployed implementations retain disabled control coverage. */
    default boolean operationControlLinked() { return false; }

    /** Read-only proof is unavailable in older deployed runtime manager families. */
    default com.google.gson.JsonObject freshSignedRuntimeValidationProof(long instanceId, String expectedCliSha256) {
        throw new com.cloud.utils.exception.CloudRuntimeException("Signed validation runtime proof is unavailable in this deployed runtime manager");
    }

    /** Manual latest-state replay on a retained ROOT is always a new activation. */
    default com.google.gson.JsonObject restoreRetainedLatestTemplateRuntime(long instanceId, com.google.gson.JsonObject pin,
            String rootOperationUuid, com.google.gson.JsonObject frozenSourceApproval) {
        throw new com.cloud.utils.exception.CloudRuntimeException("Retained latest runtime activation is unavailable in this deployed runtime manager");
    }

    /** Older deployed families cannot attest a retained latest-state replay. */
    default com.google.gson.JsonObject verifyRetainedLatestTemplateRuntime(long instanceId, com.google.gson.JsonObject pin,
            String rootOperationUuid, com.google.gson.JsonObject frozenSourceApproval) {
        throw new com.cloud.utils.exception.CloudRuntimeException("Retained latest runtime verification is unavailable in this deployed runtime manager");
    }

    com.google.gson.JsonObject verifyAvailableBundle(Long bundleId);
    com.google.gson.JsonObject templateRuntimeCapabilities(long instanceId);
    com.google.gson.JsonObject checkpointTemplateRuntime(long instanceId, String rootOperationUuid);
    com.google.gson.JsonObject restoreTemplateRuntime(long instanceId, com.google.gson.JsonObject pin, String rootOperationUuid, String direction);
    com.google.gson.JsonObject verifyTemplateRuntime(long instanceId, com.google.gson.JsonObject pin, String rootOperationUuid, String direction);
    StorageServiceRuntimeBundleResponse updateBundle(UpdateStorageServiceRuntimeBundleCmd cmd);
    boolean deleteBundle(DeleteStorageServiceRuntimeBundleCmd cmd);
    StorageServiceRuntimeBundleResponse register(RegisterStorageServiceRuntimeBundleCmd cmd);
    ListResponse<StorageServiceRuntimeBundleResponse> listBundles(ListStorageServiceRuntimeBundlesCmd cmd);
    StorageServiceRuntimeCapabilityResponse capabilities(GetStorageServiceRuntimeUpgradeCapabilitiesCmd cmd);
    StorageServiceRuntimeUpgradeResponse preflight(PreflightStorageServiceRuntimeUpgradeCmd cmd);
    StorageServiceRuntimeUpgradeResponse upgrade(UpgradeStorageServiceRuntimeCmd cmd);
    ListResponse<StorageServiceRuntimeUpgradeResponse> listUpgrades(ListStorageServiceRuntimeUpgradesCmd cmd);
    StorageServiceRuntimeUpgradeResponse rollback(RollbackStorageServiceRuntimeUpgradeCmd cmd);
}
