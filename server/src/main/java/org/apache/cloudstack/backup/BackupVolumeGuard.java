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

/** Serialize disk topology changes with backup protection configuration and execution. */
public class BackupVolumeGuard extends ManagerBase {
    @Inject private BackupDao backupDao;
    @Inject private BackupScheduleDao scheduleDao;
    @Inject private VMInstanceDao vmDao;

    public String reason(long vmId) {
        VMInstanceVO vm = vmDao.findById(vmId);
        if (vm == null) return "STATE_UNKNOWN";
        if (vm.getHypervisorType() == HypervisorType.KVM) {
            if (!backupDao.listByVmId(null, vmId).isEmpty()) return "BACKUP_EXISTS";
            if (!scheduleDao.listByVM(vmId).isEmpty()) return "BACKUP_SCHEDULE_EXISTS";
        }
        if (!Boolean.TRUE.equals(BackupManager.BackupEnableAttachDetachVolumes.value())
                && ((vm.getBackupOfferingId() != null && vm.getBackupVolumeList() != null && !vm.getBackupVolumeList().isEmpty())
                    || !backupDao.listByVmId(null, vmId).isEmpty())) return "BACKUP_POLICY";
        return null;
    }

    public void check(long vmId) {
        String reason = reason(vmId);
        if (reason != null) throw new CloudRuntimeException("VOLUME_BACKUP_" + reason
                + ": Volume topology changes are unavailable while backups or backup schedules protect this VM. Check the Backup tab; existing recovery points must be preserved.");
    }

    public void checkWorker(long vmId) {
        VMInstanceVO vm = vmDao.findById(vmId);
        // Internal restore and destroy cleanup retain their disk lifecycle. User
        // requests are separately checked before dispatch and cannot use this path.
        if (vm != null && (vm.getState() == com.cloud.vm.VirtualMachine.State.Restoring
                || vm.getState() == com.cloud.vm.VirtualMachine.State.Destroyed
                || vm.getState() == com.cloud.vm.VirtualMachine.State.Expunging)) return;
        check(vmId);
    }

    public Lease acquire(long vmId) {
        GlobalLock lock = GlobalLock.getInternLock("vm.backup.volume." + vmId);
        if (!lock.lock(3)) {
            lock.releaseRef();
            throw new CloudRuntimeException("VOLUME_BACKUP_BUSY: Backup or volume configuration is being changed. Retry after it completes.");
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
