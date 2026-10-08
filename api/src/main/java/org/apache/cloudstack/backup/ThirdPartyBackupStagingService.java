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

package org.apache.cloudstack.backup;

import java.util.function.Supplier;

import com.cloud.host.Host;

/** Shared staging lifecycle for the three ABLESTACK third-party backup providers. */
public interface ThirdPartyBackupStagingService {
    String CONFIG_PREFIX = "backup.thirdparty.staging.";

    static boolean isStagingProvider(String providerName) {
        return BackupProviderNameUtils.isCommvaultFamily(providerName)
                || BackupProviderNameUtils.isNetBackupFamily(providerName)
                || BackupProviderNameUtils.isVeeamFamily(providerName);
    }

    /** Serialize staging configuration edits and validate activation before saving the value. */
    <T> T updateConfiguration(String name, String value, Supplier<T> saveConfiguration);

    void requireEnabled();

    String getStageRootPath(String providerName);

    /** Ensure the configured mount and verify the actual filesystem before querying space. */
    long getAvailableBytes(Host host, String path);

    /** Existing jobs may clean up after staging is disabled; still verify the configured filesystem. */
    default void prepareCleanup(Host host, String path) { getAvailableBytes(host, path); }

    long getCapacityBufferBytes(long requiredBytes);

    int getCapacityBufferPercent();
}
