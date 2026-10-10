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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.lang3.StringUtils;
import org.libvirt.Connect;
import org.libvirt.Domain;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVolumeUseCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtConnection;
import com.cloud.hypervisor.kvm.resource.LibvirtDomainXMLParser;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef.DiskDef;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles = CheckVolumeUseCommand.class)
public final class LibvirtCheckVolumeUseCommandWrapper extends CommandWrapper<CheckVolumeUseCommand, Answer, LibvirtComputingResource> {
    static boolean matches(String source, String requested) {
        if (StringUtils.isBlank(source) || StringUtils.isBlank(requested)) { return false; }
        String leaf = requested.substring(requested.lastIndexOf('/') + 1);
        return source.equals(requested) || source.equals(leaf) || source.endsWith("/" + requested) || source.endsWith("/" + leaf);
    }
    static String resolveKrbd(String source) throws IOException {
        if (source == null || !source.startsWith("/dev/rbd")) { return source; }
        Path path = Path.of(source);
        if (Files.isSymbolicLink(path)) { path = path.toRealPath(); }
        String device = path.getFileName().toString();
        if (!device.matches("rbd[0-9]+")) { return source; }
        Path name = Path.of("/sys/bus/rbd/devices", device.substring(3), "name");
        if (!Files.isRegularFile(name)) { throw new IOException("krbd mapping identity unavailable"); }
        return Files.readString(name).trim();
    }
    @Override public Answer execute(CheckVolumeUseCommand command, LibvirtComputingResource resource) {
        if (StringUtils.isBlank(command.getVolumePath())) { return new Answer(command, false, "SOURCE_RUNTIME_UNKNOWN"); }
        try {
            Connect connection = LibvirtConnection.getConnection();
            for (int id : connection.listDomains()) {
                Domain domain = connection.domainLookupByID(id);
                try {
                    LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
                    if (!parser.parseDomainXML(domain.getXMLDesc(0))) { return new Answer(command, false, "SOURCE_RUNTIME_UNKNOWN"); }
                    for (DiskDef disk : parser.getDisks()) {
                        if (matches(resolveKrbd(disk.getDiskPath()), command.getVolumePath())) {
                            return new Answer(command, false, "SOURCE_IN_USE");
                        }
                    }
                } finally { domain.free(); }
            }
            return new Answer(command, true, "SOURCE_RUNTIME_FREE");
        } catch (Exception unavailable) {
            return new Answer(command, false, "SOURCE_RUNTIME_UNKNOWN");
        }
    }
}
