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

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


import com.cloud.agent.api.Answer;
import com.cloud.agent.api.FenceAnswer;
import com.cloud.agent.api.FenceCommand;
import com.cloud.hypervisor.kvm.resource.KVMHABase.HAStoragePool;
import com.cloud.hypervisor.kvm.resource.KVMHAChecker;
import com.cloud.hypervisor.kvm.resource.KVMHAMonitor;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;

@ResourceWrapper(handles =  FenceCommand.class)
public final class LibvirtFenceCommandWrapper extends CommandWrapper<FenceCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(final FenceCommand command, final LibvirtComputingResource libvirtComputingResource) {
        final ExecutorService executors = Executors.newSingleThreadExecutor();
        final KVMHAMonitor monitor = libvirtComputingResource.getMonitor();

        final List<HAStoragePool> pools = monitor.getStoragePools();
        final List<HAStoragePool> gfspools = monitor.getGfsStoragePools();
        final List<HAStoragePool> rbdpools = monitor.getRbdStoragePools();
        final List<HAStoragePool> clvmpools = monitor.getClvmStoragePools();

        /**
         * We can only safely fence off hosts when we use NFS
         * On NFS primary storage pools hosts continuesly write
         * a heartbeat. Disable Fencing Off for hosts without NFS
         */
        if (pools.size() == 0) {
            String logline = String.format("No NFS storage pools found. No way to safely fence %s on host %s", command.getVmName(), command.getHostGuid());
            logger.warn(logline);
            return new FenceAnswer(command, false, logline);
        } else if (gfspools.size() == 0) {
            String logline = String.format("No SharedMountPoint storage pools found. No way to safely fence %s on host %s", command.getVmName(), command.getHostGuid());
            logger.warn(logline);
            return new FenceAnswer(command, false, logline);
        } else if (rbdpools.size() == 0) {
            String logline = String.format("No RBD storage pools found. No way to safely fence %s on host %s", command.getVmName(), command.getHostGuid());
            logger.warn(logline);
            return new FenceAnswer(command, false, logline);
        } else if (clvmpools.size() == 0) {
            String logline = String.format("No CLVM storage pools found. No way to safely fence %s on host %s", command.getVmName(), command.getHostGuid());
            logger.warn(logline);
            return new FenceAnswer(command, false, logline);
        }

        final KVMHAChecker ha = new KVMHAChecker(pools, gfspools, rbdpools, clvmpools, command.getHost(), command.isReportCheckFailureIfOneStorageIsDown(), null);

        final Future<Boolean> future = executors.submit(ha);
        try {
            final Boolean result = future.get();
            if (result) {
                return new FenceAnswer(command, false, "Heart is still beating...");
            } else {
                return new FenceAnswer(command);
            }
        } catch (final InterruptedException e) {
            logger.warn("Unable to fence", e);
            return new FenceAnswer(command, false, e.getMessage());
        } catch (final ExecutionException e) {
            logger.warn("Unable to fence", e);
            return new FenceAnswer(command, false, e.getMessage());
        }
    }
}
