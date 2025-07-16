//
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
//

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.UpdateHostScsiDeviceCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = UpdateHostScsiDeviceCommand.class)
public final class LibvirtupdateHostScsiDevicesCommandWrapper
        extends CommandWrapper<UpdateHostScsiDeviceCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(final UpdateHostScsiDeviceCommand command,
            final LibvirtComputingResource libvirtComputingResource) {
        try {
            return libvirtComputingResource.updateHostScsiDevices(command, command.getVmName(), command.getXmlConfig(), command.getIsAttach());
        } catch (Exception e) {
            return new Answer(command, false, "SCSI 장치 업데이트 중 오류 발생: " + e.getMessage());
        }
    }
}
