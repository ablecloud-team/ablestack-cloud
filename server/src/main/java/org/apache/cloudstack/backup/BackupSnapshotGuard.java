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
package org.apache.cloudstack.backup;

import javax.inject.Inject;

import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

/** Europa safety policy; coexistence requires the separate backup lineage design. */
public class BackupSnapshotGuard extends ManagerBase {
    @Inject private BackupDao backupDao;
    @Inject private com.cloud.agent.AgentManager agentManager;
    @Inject private BackupScheduleDao scheduleDao;
    @Inject private VMSnapshotDao snapshotDao;
    @Inject private VMInstanceDao vmDao;

    public String snapshotReason(long vmId) {
        VMInstanceVO vm = vmDao.findById(vmId);
        if (vm == null || vm.getHypervisorType() != HypervisorType.KVM) {
            return null;
        }
        if (!backupDao.listByVmId(null, vmId).isEmpty()) {
            return "BACKUP_EXISTS";
        }
        if (!scheduleDao.listByVM(vmId).isEmpty()) {
            return "BACKUP_SCHEDULE_EXISTS";
        }
        return null;
    }

    public String backupReason(long vmId) {
        VMInstanceVO vm = vmDao.findById(vmId);
        return vm != null && vm.getHypervisorType() == HypervisorType.KVM
                && !snapshotDao.findByVm(vmId).isEmpty() ? "VM_SNAPSHOT_EXISTS" : null;
    }

    public void checkSnapshot(long vmId) {
        String reason = snapshotReason(vmId);
        if (reason != null) {
            throw new CloudRuntimeException(reason + ": VM snapshot creation and restore are unavailable while backups or backup schedules exist.");
        }
    }

    public void checkRuntime(long vmId) {
        VMInstanceVO vm = vmDao.findById(vmId);
        if (vm == null || vm.getHypervisorType() != HypervisorType.KVM) return;
        Long hostId = vm.getHostId() == null ? vm.getLastHostId() : vm.getHostId();
        if (hostId == null) throw new CloudRuntimeException("BACKUP_TRACKING_UNKNOWN: No host available for tracking inspection.");
        try {
            com.cloud.agent.api.Answer answer = agentManager.send(hostId,
                    new com.cloud.agent.api.CheckVmBackupTrackingCommand(vm.getInstanceName()));
            if (answer == null || !answer.getResult()) {
                throw new CloudRuntimeException(answer == null ? "BACKUP_TRACKING_UNKNOWN: No agent response." : answer.getDetails());
            }
        } catch (com.cloud.exception.AgentUnavailableException | com.cloud.exception.OperationTimedoutException e) {
            throw new CloudRuntimeException("BACKUP_TRACKING_UNKNOWN: Unable to verify backup tracking.", e);
        }
    }

    public void checkBackup(long vmId) {
        if (backupReason(vmId) != null) {
            throw new CloudRuntimeException("VM_SNAPSHOT_EXISTS: Backup creation, scheduling and offering assignment are unavailable while VM snapshots exist.");
        }
    }

    public Lease acquire(long vmId) {
        GlobalLock lock = GlobalLock.getInternLock("vm.backup.snapshot." + vmId);
        if (!lock.lock(3)) {
            lock.releaseRef();
            throw new CloudRuntimeException("BACKUP_SNAPSHOT_BUSY: Another backup or snapshot operation is in progress. Retry after it completes.");
        }
        return new Lease(lock);
    }

    public static final class Lease implements AutoCloseable {
        private final GlobalLock lock;
        private Lease(GlobalLock lock) { this.lock = lock; }
        @Override public void close() {
            try { lock.unlock(); } finally { lock.releaseRef(); }
        }
    }
}
