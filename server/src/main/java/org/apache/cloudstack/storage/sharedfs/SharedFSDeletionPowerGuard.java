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
package org.apache.cloudstack.storage.sharedfs;

import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;

/** Serializes power-on/migration effects with DATA-preserving SharedFS deletion. */
public final class SharedFSDeletionPowerGuard implements AutoCloseable {
    private final GlobalLock lock;

    private SharedFSDeletionPowerGuard(GlobalLock lock) {
        this.lock = lock;
    }

    public static SharedFSDeletionPowerGuard enter(String vmUuid) {
        UserVmVO vm = ComponentContext.getComponent(UserVmDao.class).findByUuid(vmUuid);
        if (vm == null || !UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            return new SharedFSDeletionPowerGuard(null);
        }
        SharedFSVO fs = ComponentContext.getComponent(SharedFSDao.class).findByVm(vm.getId());
        if (fs == null) {
            return new SharedFSDeletionPowerGuard(null);
        }
        GlobalLock lock = GlobalLock.getInternLock("sharedfs-deletion-" + fs.getId());
        boolean acquired = false;
        try {
            if (!(acquired = lock.lock(1))) {
                throw new CloudRuntimeException("SHAREDFS_DELETION_ACTIVE");
            }
            StorageServiceInstanceVO instance = ComponentContext.getComponent(StorageServiceInstanceDao.class).findByVmId(vm.getId());
            if (instance != null && instance.getActiveLifecycleOperationUuid() != null) {
                throw new CloudRuntimeException("SHAREDFS_DELETION_ACTIVE");
            }
            return new SharedFSDeletionPowerGuard(lock);
        } catch (RuntimeException e) {
            if (acquired) {
                lock.unlock();
            }
            lock.releaseRef();
            throw e;
        }
    }

    @Override
    public void close() {
        if (lock != null) {
            try {
                lock.unlock();
            } finally {
                lock.releaseRef();
            }
        }
    }
}
