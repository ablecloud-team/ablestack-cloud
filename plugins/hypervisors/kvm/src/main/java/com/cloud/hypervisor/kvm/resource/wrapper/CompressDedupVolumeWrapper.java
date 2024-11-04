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

import org.apache.cloudstack.storage.command.CompressDedupVolumeAnswer;
import org.apache.cloudstack.storage.command.CompressDedupVolumeCommand;

import com.cloud.agent.api.Answer;
import com.cloud.exception.InternalErrorException;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.ResourceWrapper;

import com.cloud.resource.CommandWrapper;

@ResourceWrapper(handles =  CompressDedupVolumeCommand.class)
public final class CompressDedupVolumeWrapper extends CommandWrapper<CompressDedupVolumeCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(final CompressDedupVolumeCommand command, final LibvirtComputingResource libvirtComputingResource) {
        try {
            logger.info("CompressDedupVolumeCommand Action Call [ volume path : " +command.getVolume().getPath()+ " ]");
            if (libvirtComputingResource.CompressDedupVolumeCmdLine(command.getAction(), command.getVolume().getPath())) {
                logger.info("CompressDedupVolumeCommand Action >>> Success");
                return new CompressDedupVolumeAnswer(command, "", true);
            } else {
                logger.info("CompressDedupVolumeCommand Action >>> Fail");
                return new CompressDedupVolumeAnswer(command, "", false);
            }
        } catch (InternalErrorException e) {
            return new CompressDedupVolumeAnswer(command, e);
        }
    }
}