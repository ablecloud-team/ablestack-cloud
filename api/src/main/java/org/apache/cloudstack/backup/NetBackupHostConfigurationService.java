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

import org.apache.cloudstack.api.response.NetBackupHostConfigurationResponse;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;

public interface NetBackupHostConfigurationService {
    static boolean isAvailable(Host.Type type, HypervisorType hypervisor, String providers) {
        return type == Host.Type.Routing && hypervisor == HypervisorType.KVM
                && BackupProviderNameUtils.isConfigured(providers, BackupProviderNameUtils.ABLESTACK_NETBACKUP);
    }

    NetBackupHostConfigurationResponse inspect(Long hostId);
    NetBackupHostConfigurationResponse configure(Long hostId, String action, String policyName, String moldUrl);
}
