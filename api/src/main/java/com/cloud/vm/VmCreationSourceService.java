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
package com.cloud.vm;

import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.ListVirtualMachineCreationSourcesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VmCreationSourceResponse;

public interface VmCreationSourceService {
    String PREFIX = "vm.creation.source.";
    ListResponse<VmCreationSourceResponse> list(ListVirtualMachineCreationSourcesCmd cmd);
    VmCreationSourceResponse validate(ListVirtualMachineCreationSourcesCmd cmd);
    VmCreationSourceResponse validateDeployment(DeployVMCmd cmd);
    void captureSnapshot(Snapshot snapshot, Volume volume);
    void captureVolume(Volume volume);
    void captureRestoredVolume(Snapshot snapshot, Volume volume);
}
