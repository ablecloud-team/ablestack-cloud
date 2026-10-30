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
package com.cloud.vm;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import org.junit.*;
import java.util.*;
import com.cloud.storage.*;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import com.cloud.storage.dao.*;
import com.cloud.vm.dao.*;
import com.cloud.user.*;
import org.apache.cloudstack.storage.datastore.db.*;
public class VmCloneFailureCleanupTest {
    static class Manager extends VmCloneBatchManager {
        boolean unavailable, committed;int probes;
        @Override protected void verifyAbsent(VolumeVO volume,StoragePoolVO pool){probes++;if(unavailable)throw new com.cloud.utils.exception.CloudRuntimeException("image or active work remains");}
        @Override protected void finishVerifiedVolume(VmCloneBatchVO row,VmCloneBatchData data,VmCloneBatchData.Disk disk,VolumeVO volume,StoragePoolVO pool){committed=true;}
    }
    Manager manager;VmCloneBatchData data;VmCloneBatchVO row;VmCloneBatchData.Disk disk;VolumeVO volume;
    @Before public void setup(){
        manager=new Manager();manager.volumes=mock(VolumeDao.class);manager.volumeDetails=mock(VolumeDetailsDao.class);manager.pools=mock(PrimaryDataStoreDao.class);manager.volumeStores=mock(VolumeDataStoreDao.class);manager.accounts=mock(AccountManager.class);manager.vms=mock(UserVmDao.class);
        org.apache.cloudstack.context.CallContext.register(mock(UserVO.class),mock(AccountVO.class));
        row=new VmCloneBatchVO("11111111-1111-4111-8111-111111111111",10,2,1,"fingerprint","{}");
        data=new VmCloneBatchData();VmCloneBatchData.SourceDisk source=new VmCloneBatchData.SourceDisk();source.id=20;source.uuid="source";source.pool=30L;data.sourceDisks.add(source);
        disk=new VmCloneBatchData.Disk();disk.uuid="22222222-2222-4222-8222-222222222222";disk.sourceId=20;
        volume=mock(VolumeVO.class);when(volume.getId()).thenReturn(50L);when(volume.getUuid()).thenReturn(disk.uuid);when(volume.getAccountId()).thenReturn(2L);when(volume.getPoolId()).thenReturn(30L);when(volume.getRemoved()).thenReturn(new Date());when(volume.getState()).thenReturn(Volume.State.Expunging);when(volume.getInstanceId()).thenReturn(null);
        when(manager.volumes.findByUuidIncludingRemoved(disk.uuid)).thenReturn(volume);when(manager.volumeDetails.listDetailsKeyPairs(50)).thenReturn(Map.of(VolumeAllocationClone.SOURCE,"20"));
        StoragePoolVO pool=mock(StoragePoolVO.class);when(pool.getId()).thenReturn(30L);when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);when(manager.pools.findById(30L)).thenReturn(pool);
    }
    @After public void done(){org.apache.cloudstack.context.CallContext.unregister();}
    void blocked() throws Exception {try{manager.reconcileDisk(row,data,disk);fail("unsafe reconciliation accepted");}catch(com.cloud.utils.exception.CloudRuntimeException expected){}assertFalse(manager.committed);}
    @Test public void removedExpungingDiskCommitsOnlyAfterPhysicalReceipt() throws Exception {manager.reconcileDisk(row,data,disk);assertEquals(1,manager.probes);assertTrue(manager.committed);}
    @Test public void unverifiedImageNeverAdvancesState() throws Exception {manager.unavailable=true;blocked();assertEquals(1,manager.probes);}
    @Test public void attachedDiskIsNeverProbedOrChanged() throws Exception {when(volume.getInstanceId()).thenReturn(40L);blocked();assertEquals(0,manager.probes);}
    @Test public void protectedDiskIsPreserved() throws Exception {when(volume.isDeleteProtection()).thenReturn(true);blocked();}
    @Test public void ownerChangeIsPreserved() throws Exception {when(volume.getAccountId()).thenReturn(9L);blocked();}
    @Test public void poolChangeIsPreserved() throws Exception {when(volume.getPoolId()).thenReturn(31L);blocked();}
    @Test public void liveAllocationCannotBeReconciledAsRemoved() throws Exception {when(volume.getRemoved()).thenReturn(null);blocked();}
    @Test public void storageReferenceBlocksReconciliation() throws Exception {when(manager.volumeStores.findByVolume(50)).thenReturn(mock(VolumeDataStoreVO.class));blocked();}
    @Test public void existingSuccessNeverBecomesCleanupCandidate(){VmCloneBatchData.Item item=new VmCloneBatchData.Item();item.created=true;item.disks.add(disk);assertFalse(manager.cleanupCandidate(row,data,item));}
    @Test public void removedVmRootAssociationIsHistoryOnlyAfterPhysicalProof() throws Exception {
        when(volume.getInstanceId()).thenReturn(40L);UserVmVO target=mock(UserVmVO.class);when(target.getRemoved()).thenReturn(new Date());when(target.getAccountId()).thenReturn(2L);when(manager.vms.findByIdIncludingRemoved(40L)).thenReturn(target);
        VmCloneBatchData.Item item=new VmCloneBatchData.Item();item.vmId=40L;item.disks.add(disk);data.items.add(item);
        manager.reconcileDisk(row,data,disk);assertTrue(manager.committed);assertEquals(1,manager.probes);
    }
    @Test public void pendingPhysicalProofBlocksRecreationEvenForExpungedRows(){VmCloneBatchData.Item item=new VmCloneBatchData.Item();item.cleanupRequired=true;assertEquals("NONE",manager.action(row,data,item));}
}
