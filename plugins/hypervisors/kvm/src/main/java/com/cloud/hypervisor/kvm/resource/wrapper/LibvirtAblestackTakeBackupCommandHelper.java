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

import com.cloud.agent.api.Command;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMPhysicalDisk;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import org.apache.cloudstack.backup.BackupAnswer;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

final class LibvirtAblestackTakeBackupCommandHelper {
    private LibvirtAblestackTakeBackupCommandHelper() {
    }

    static BackupAnswer execute(final Command command, final Logger logger, final String trace, final String provider,
            final BackupCommandContext context, final Supplier<String[]> detachedCommandSupplier,
            final Supplier<Pair<Integer, String>> backupTask, final int cleanupFailureExitCode,
            final String failureFallbackMessage, final boolean useResultDetailsOnSuccess,
            final Function<Pair<Integer, String>, Long> backupSizeResolver) {
        logger.info("{} phase=[AGENT_ENTER], vm=[{}], backupPath=[{}], backupType=[{}]",
                trace, context.vmName, context.backupPath, context.backupType);

        if (!context.waitForCompletion) {
            final String[] detachedCommand = detachedCommandSupplier.get();
            if (detachedCommand != null) {
                final boolean liveBandwidthSupported = Arrays.asList(detachedCommand).contains("backup-running");
                return LibvirtAblestackAsyncBackupRunner.startDetached(command, logger, trace, provider, context.jobId,
                        context.vmName, context.backupPath, context.backupType, detachedCommand, liveBandwidthSupported);
            }
            logger.info("{} phase=[AGENT_DETACHED_JAVA_START], provider=[{}], jobId=[{}], vm=[{}], backupPath=[{}], backupType=[{}]",
                    trace, provider, context.jobId, context.vmName, context.backupPath, context.backupType);
            return LibvirtAblestackAsyncBackupRunner.startDetachedBackup(command, logger, trace, provider, context.jobId,
                    context.vmName, context.backupPath, context.backupType);
        }

        final Pair<Integer, String> result = backupTask.get();
        if (result.first() != 0) {
            final String failureDetails = StringUtils.defaultIfBlank(result.second(), failureFallbackMessage);
            logger.warn("{} phase=[AGENT_FAILED], vm=[{}], backupPath=[{}], backupType=[{}], resultCode=[{}], reason=[{}]",
                    trace, context.vmName, context.backupPath, context.backupType, result.first(), failureDetails);
            final BackupAnswer answer = new BackupAnswer(command, false, failureDetails.trim());
            if (result.first() == cleanupFailureExitCode) {
                logger.debug("Backup cleanup failed");
                answer.setNeedsCleanup(true);
            }
            return answer;
        }

        logger.info("{} phase=[AGENT_DONE], vm=[{}], backupPath=[{}], backupType=[{}]",
                trace, context.vmName, context.backupPath, context.backupType);
        final String successDetails = useResultDetailsOnSuccess ? StringUtils.defaultIfBlank(result.second(), "success").trim() : "success";
        final BackupAnswer answer = new BackupAnswer(command, true, successDetails);
        if (backupSizeResolver != null) {
            try {
                answer.setSize(backupSizeResolver.apply(result));
            } catch (final RuntimeException e) {
                logger.warn("Failed to calculate {} backup size for vm=[{}], backupPath=[{}]",
                        provider, context.vmName, context.backupPath, e);
            }
        }
        return answer;
    }

    static List<String> resolveDiskPaths(final LibvirtComputingResource resource, final List<PrimaryDataStoreTO> volumePools,
            final List<String> volumePaths) {
        final List<String> diskPaths = new ArrayList<>();
        if (volumePaths == null) {
            return diskPaths;
        }

        final KVMStoragePoolManager storagePoolMgr = resource.getStoragePoolMgr();
        for (int idx = 0; idx < volumePaths.size(); idx++) {
            final PrimaryDataStoreTO volumePool = volumePools.get(idx);
            final String volumePath = volumePaths.get(idx);
            if (volumePool.getPoolType() != Storage.StoragePoolType.RBD) {
                diskPaths.add(volumePath);
                continue;
            }

            final KVMStoragePool volumeStoragePool = storagePoolMgr.getStoragePool(volumePool.getPoolType(), volumePool.getUuid());
            diskPaths.add(KVMPhysicalDisk.RBDStringBuilder(volumeStoragePool, volumePath));
        }
        return diskPaths;
    }

    static final class BackupCommandContext {
        private final String jobId;
        private final String vmName;
        private final String backupPath;
        private final String backupType;
        private final boolean waitForCompletion;

        BackupCommandContext(final String jobId, final String vmName, final String backupPath,
                final String backupType, final boolean waitForCompletion) {
            this.jobId = jobId;
            this.vmName = vmName;
            this.backupPath = backupPath;
            this.backupType = backupType;
            this.waitForCompletion = waitForCompletion;
        }
    }
}
