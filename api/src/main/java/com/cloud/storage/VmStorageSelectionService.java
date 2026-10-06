/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.cloud.storage;

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.admin.storage.ListDeploymentStoragePoolsCmd;
import org.apache.cloudstack.api.command.user.vm.BaseDeployVMCmd;
import org.apache.cloudstack.api.response.DeploymentStoragePoolResponse;
import com.cloud.dc.DataCenter;
import com.cloud.offering.ServiceOffering;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine;
import com.cloud.template.VirtualMachineTemplate;

public interface VmStorageSelectionService {
    String REQUIRED_POOL = "deployment.storage.pool.uuid";

    List<DeploymentStoragePoolResponse> listPools(ListDeploymentStoragePoolsCmd cmd);
    Map<Long, Long> prepare(BaseDeployVMCmd cmd, DataCenter zone, Account owner, ServiceOffering offering, VirtualMachineTemplate template);
    void bind(UserVm vm, Map<Long, Long> selections);
    Long requiredPool(Volume volume, VirtualMachine vm);
}
