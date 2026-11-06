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
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceInstanceDao;
import org.apache.cloudstack.storage.sharedfs.dao.SharedFSDao;
import org.junit.*;
import org.mockito.MockedStatic;

public class SharedFSDeletionPowerGuardTest {
    MockedStatic<ComponentContext> components;
    MockedStatic<GlobalLock> locks;
    UserVmDao vms;
    SharedFSDao filesystems;
    StorageServiceInstanceDao instances;
    GlobalLock lock;
    UserVmVO vm;
    StorageServiceInstanceVO instance;
    @Before public void open() {
        components=mockStatic(ComponentContext.class);locks=mockStatic(GlobalLock.class);
        vms=mock(UserVmDao.class);filesystems=mock(SharedFSDao.class);instances=mock(StorageServiceInstanceDao.class);lock=mock(GlobalLock.class);
        components.when(()->ComponentContext.getComponent(UserVmDao.class)).thenReturn(vms);
        components.when(()->ComponentContext.getComponent(SharedFSDao.class)).thenReturn(filesystems);
        components.when(()->ComponentContext.getComponent(StorageServiceInstanceDao.class)).thenReturn(instances);
        vm=mock(UserVmVO.class);when(vm.getId()).thenReturn(7L);when(vm.getUserVmType()).thenReturn("sharedfsvm");when(vms.findByUuid("vm")).thenReturn(vm);
        SharedFSVO fs=mock(SharedFSVO.class);when(fs.getId()).thenReturn(9L);when(filesystems.findByVm(7L)).thenReturn(fs);
        instance=mock(StorageServiceInstanceVO.class);when(instances.findByVmId(7L)).thenReturn(instance);
        locks.when(()->GlobalLock.getInternLock("sharedfs-deletion-9")).thenReturn(lock);
        when(lock.lock(1)).thenReturn(true);
    }
    @After public void close(){locks.close();components.close();}
    @Test public void ordinaryVmHasNoLockOrStorageLookup(){
        when(vm.getUserVmType()).thenReturn(null);
        try(SharedFSDeletionPowerGuard guard=SharedFSDeletionPowerGuard.enter("vm")){}
        verifyNoInteractions(filesystems,instances,lock);
    }
    @Test public void deletedOrNonUserVmRetainsOriginalBehavior(){
        when(vms.findByUuid("vm")).thenReturn(null);
        try(SharedFSDeletionPowerGuard guard=SharedFSDeletionPowerGuard.enter("vm")){}
        verifyNoInteractions(filesystems,instances,lock);
    }
    @Test public void activeDeletionCannotStartAndReleasesAcquiredLock(){
        when(instance.getActiveLifecycleOperationUuid()).thenReturn("deletion");
        assertEquals("SHAREDFS_DELETION_ACTIVE",assertThrows(CloudRuntimeException.class,()->SharedFSDeletionPowerGuard.enter("vm")).getMessage());
        verify(lock).unlock();verify(lock).releaseRef();
    }
    @Test public void busyWorkerCannotEnterOrUnlockAnotherWorker(){
        when(lock.lock(1)).thenReturn(false);
        assertThrows(CloudRuntimeException.class,()->SharedFSDeletionPowerGuard.enter("vm"));
        verify(lock,never()).unlock();verify(lock).releaseRef();verifyNoInteractions(instances);
    }
    @Test public void normalPowerActionHoldsLockThroughFailureAndAlwaysCloses(){
        assertThrows(IllegalStateException.class,()->{
            try(SharedFSDeletionPowerGuard guard=SharedFSDeletionPowerGuard.enter("vm")){
                verify(lock,never()).unlock();throw new IllegalStateException();
            }
        });
        verify(lock).unlock();verify(lock).releaseRef();
    }
}
