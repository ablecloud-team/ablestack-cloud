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
import com.cloud.agent.api.CheckVmBackupTrackingCommand;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.resource.LibvirtDomainXMLParser;
import com.cloud.hypervisor.kvm.resource.LibvirtVMDef;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;
import com.cloud.utils.script.OutputInterpreter;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.libvirt.Domain;

@ResourceWrapper(handles = CheckVmBackupTrackingCommand.class)
public class LibvirtCheckVmBackupTrackingCommandWrapper extends CommandWrapper<CheckVmBackupTrackingCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(CheckVmBackupTrackingCommand cmd, LibvirtComputingResource resource) {
        Domain domain = null;
        try {
            domain = resource.getDomain(resource.getLibvirtUtilitiesHelper().getConnection(), cmd.getVmName());
            if (!run("virsh", "-c", "qemu:///system", "checkpoint-list", "--domain", cmd.getVmName(), "--name").trim().isEmpty()) {
                return new Answer(cmd, false, "BACKUP_TRACKING_EXISTS: Backup checkpoints remain on the VM.");
            }
            if (domain.isActive() == 1) {
                JsonElement block = new JsonParser().parse(domain.qemuMonitorCommand("{\"execute\":\"query-block\"}", 0));
                if (!block.isJsonObject() || !block.getAsJsonObject().has("return")) {
                    throw new IllegalStateException("Unable to read QEMU block state");
                }
                if (hasBitmaps(block)) return new Answer(cmd, false, "BACKUP_TRACKING_EXISTS: Dirty bitmaps remain on the VM.");
            } else {
                LibvirtDomainXMLParser parser = new LibvirtDomainXMLParser();
                if (!parser.parseDomainXML(domain.getXMLDesc(0)) || parser.getDisks().isEmpty()) {
                    throw new IllegalStateException("Cannot inspect the VM disk configuration");
                }
                for (LibvirtVMDef.DiskDef disk : parser.getDisks()) {
                    if (disk.getDeviceType() != LibvirtVMDef.DiskDef.DeviceType.DISK) continue;
                    String path = disk.getDiskPath();
                    if (path == null || !path.startsWith("/")) throw new IllegalStateException("Cannot verify offline disk tracking");
                    JsonElement info = new JsonParser().parse(run("qemu-img", "info", "--output=json", "--backing-chain", path));
                    if (hasBitmaps(info)) return new Answer(cmd, false, "BACKUP_TRACKING_EXISTS: Persistent disk bitmaps remain.");
                }
            }
            return new Answer(cmd, true, null);
        } catch (Exception e) {
            return new Answer(cmd, false, "BACKUP_TRACKING_UNKNOWN: Cannot verify backup tracking state: " + e.getMessage());
        } finally {
            if (domain != null) try { domain.free(); } catch (Exception ignored) { }
        }
    }

    static boolean hasBitmaps(JsonElement value) {
        if (value.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> field : value.getAsJsonObject().entrySet()) {
                if (("dirty-bitmaps".equals(field.getKey()) || "bitmaps".equals(field.getKey()))
                        && field.getValue().isJsonArray() && field.getValue().getAsJsonArray().size() > 0) return true;
                if (hasBitmaps(field.getValue())) return true;
            }
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) if (hasBitmaps(item)) return true;
        }
        return false;
    }

    private String run(String executable, String... args) {
        Script script = new Script(executable, 10000, logger);
        for (String arg : args) script.add(arg);
        OutputInterpreter.AllLinesParser output = new OutputInterpreter.AllLinesParser();
        String error = script.execute(output);
        if (script.getExitValue() != 0 || error != null) throw new IllegalStateException("Tracking inspection failed: " + error);
        return output.getLines() == null ? "" : output.getLines();
    }
}
