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

package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import org.apache.cloudstack.backup.AblestackVeeamGetAvailableBytesCommand;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ResourceWrapper(handles = AblestackVeeamGetAvailableBytesCommand.class)
public class LibvirtAblestackVeeamGetAvailableBytesCommandWrapper
        extends CommandWrapper<AblestackVeeamGetAvailableBytesCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final AblestackVeeamGetAvailableBytesCommand command, final LibvirtComputingResource serverResource) {
        if (StringUtils.isBlank(command.getPath())) {
            return new Answer(command, false, "Veeam available-space path is required.");
        }
        final Path path = Path.of(command.getPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(path);
            return new Answer(command, true, String.valueOf(Files.getFileStore(path).getUsableSpace()));
        } catch (final IOException e) {
            return new Answer(command, false,
                    String.format("Failed to query available space for Veeam path [%s]: %s", path, e.getMessage()));
        }
    }
}
