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

import org.libvirt.Domain;
import org.libvirt.DomainInfo.DomainState;
import com.cloud.agent.api.CheckVmGuestAgentCommand;
import com.cloud.agent.api.CheckVmGuestAgentAnswer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.google.gson.JsonParser;

@ResourceWrapper(handles = CheckVmGuestAgentCommand.class)
public class LibvirtCheckVmGuestAgentCommandWrapper
        extends CommandWrapper<CheckVmGuestAgentCommand, CheckVmGuestAgentAnswer, LibvirtComputingResource> {
    @Override public CheckVmGuestAgentAnswer execute(CheckVmGuestAgentCommand cmd, LibvirtComputingResource resource) {
        Domain domain = null;
        try {
            domain = resource.getDomain(resource.getLibvirtUtilitiesHelper().getConnectionByVmName(cmd.getVmName()), cmd.getVmName());
            if (domain == null || !cmd.getVmUuid().equalsIgnoreCase(domain.getUUIDString())) {
                return new CheckVmGuestAgentAnswer(cmd, "DOMAIN_MISMATCH", "Target domain UUID mismatch");
            }
            if (domain.getInfo().state != DomainState.VIR_DOMAIN_RUNNING) {
                return new CheckVmGuestAgentAnswer(cmd, "DOMAIN_NOT_RUNNING", "Target domain is not running");
            }
            String reply = domain.qemuAgentCommand("{\"execute\":\"guest-ping\"}", cmd.getTimeoutSeconds(), 0);
            boolean responded = validPingReply(reply);
            return new CheckVmGuestAgentAnswer(cmd, responded ? "RESPONDED" : "NOT_READY", "guest-ping response checked");
        } catch (Exception e) {
            return new CheckVmGuestAgentAnswer(cmd, "NOT_READY", "Guest agent unavailable or timed out");
        } finally {
            if (domain != null) { try { domain.free(); } catch (Exception ignored) { } }
        }
    }
    static boolean validPingReply(String reply) {
        try {
            com.google.gson.JsonObject result = new JsonParser().parse(reply).getAsJsonObject();
            return result.has("return") && result.get("return").isJsonObject() && !result.has("error");
        } catch (Exception e) { return false; }
    }
}
