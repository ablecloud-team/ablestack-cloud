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

import java.util.HashMap;
import java.util.List;

import org.libvirt.Connect;
import org.libvirt.LibvirtException;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmStatsAnswer;
import com.cloud.agent.api.GetVmStatsCommand;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles =  GetVmStatsCommand.class)
public final class LibvirtGetVmStatsCommandWrapper extends CommandWrapper<GetVmStatsCommand, Answer, LibvirtComputingResource> {
    private static final int DOM_JOB_INFO_TIMEOUT_MS = 10000;
    private static final String JOB_TYPE_PREFIX = "Job type:";
    private static final String JOB_TYPE_NONE = "None";


    @Override
    public Answer execute(final GetVmStatsCommand command, final LibvirtComputingResource libvirtComputingResource) {
        final List<String> vmNames = command.getVmNames();
        try {
            final HashMap<String, VmStatsEntry> vmStatsNameMap = new HashMap<String, VmStatsEntry>();
            for (final String vmName : vmNames) {
                if (!isVmStatsCollectable(vmName)) {
                    logger.debug("Skipping VM stats collection for [{}] because a libvirt job is currently active.", vmName);
                    continue;
                }

                final LibvirtUtilitiesHelper libvirtUtilitiesHelper = libvirtComputingResource.getLibvirtUtilitiesHelper();

                final Connect conn = libvirtUtilitiesHelper.getConnectionByVmName(vmName);
                try {
                    final VmStatsEntry statEntry = libvirtComputingResource.getVmStat(conn, vmName);
                    if (statEntry == null) {
                        continue;
                    }

                    vmStatsNameMap.put(vmName, statEntry);
                } catch (Exception e) {
                    logger.warn("Can't get vm stats for [{}], continue", vmName, e);
                }
            }
            return new GetVmStatsAnswer(command, vmStatsNameMap);
        } catch (final LibvirtException e) {
            logger.debug("Can't get vm stats: " + e.toString());
            return new GetVmStatsAnswer(command, null);
        }
    }

    private boolean isVmStatsCollectable(final String vmName) {
        final String output = Script.runSimpleBashScript(String.format(
                "virsh -c qemu:///system domjobinfo %s 2>&1", vmName), DOM_JOB_INFO_TIMEOUT_MS);
        if (output == null) {
            logger.debug("Skipping VM stats collection for [{}] because domjobinfo returned null output.", vmName);
            return false;
        }

        for (final String line : output.split("\\R")) {
            final String trimmedLine = line.trim();
            if (!trimmedLine.startsWith(JOB_TYPE_PREFIX)) {
                continue;
            }

            final String jobType = trimmedLine.substring(JOB_TYPE_PREFIX.length()).trim();
            return JOB_TYPE_NONE.equals(jobType);
        }

        logger.debug("Skipping VM stats collection for [{}] because domjobinfo output did not include a job type. Output: {}", vmName, output);
        return false;
    }
}
