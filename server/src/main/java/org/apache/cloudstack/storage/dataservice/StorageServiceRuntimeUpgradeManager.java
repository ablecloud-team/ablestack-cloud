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
    com.google.gson.JsonObject verifyAvailableBundle(Long bundleId);
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
