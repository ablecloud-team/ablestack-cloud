// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See the NOTICE file distributed with this
// work for additional information regarding copyright ownership. The ASF
// licenses this file to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0 .
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// License for the specific language governing permissions and limitations.
package org.apache.cloudstack.storage.vmsnapshot;

import java.util.List;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.vm.snapshot.*;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;
import org.apache.cloudstack.engine.subsystem.api.storage.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StorageSnapshotDeleteRetryTest {
    @Test public void partialDeleteAndRetryKeepEveryDiskIdentityIncludingRemovedRows() {
        StorageVMSnapshotStrategy strategy = new StorageVMSnapshotStrategy();
        strategy.vmSnapshotDetailsDao=mock(VMSnapshotDetailsDao.class); strategy.snapshotDao=mock(SnapshotDao.class);
        strategy.storageStrategyFactory=mock(StorageStrategyFactory.class);
        VMSnapshot snapshot=mock(VMSnapshot.class); when(snapshot.getId()).thenReturn(1L);
        VMSnapshotDetailsVO root=new VMSnapshotDetailsVO(1L,"kvmStorageSnapshot","10",false);
        VMSnapshotDetailsVO data=new VMSnapshotDetailsVO(1L,"kvmStorageSnapshot","11",false);
        when(strategy.vmSnapshotDetailsDao.findDetails(1L,"kvmStorageSnapshot")).thenReturn(List.of(root,data));
        SnapshotVO a=mock(SnapshotVO.class),b=mock(SnapshotVO.class); when(a.getId()).thenReturn(10L); when(b.getId()).thenReturn(11L);
        when(strategy.snapshotDao.findByIdIncludingRemoved(10L)).thenReturn(a); when(strategy.snapshotDao.findByIdIncludingRemoved(11L)).thenReturn(b);
        SnapshotStrategy storage=mock(SnapshotStrategy.class);
        when(strategy.storageStrategyFactory.getSnapshotStrategy(any(SnapshotVO.class),eq(SnapshotStrategy.SnapshotOperation.DELETE))).thenReturn(storage);
        when(storage.deleteSnapshot(10L,null)).thenReturn(true); when(storage.deleteSnapshot(11L,null)).thenReturn(false,true);
        try { strategy.deleteDiskSnapshot(snapshot); fail("partial deletion was hidden"); } catch(com.cloud.utils.exception.CloudRuntimeException expected) { }
        verify(strategy.vmSnapshotDetailsDao,never()).remove(anyLong());
        strategy.deleteDiskSnapshot(snapshot);
        verify(storage,times(2)).deleteSnapshot(10L,null); verify(storage,times(2)).deleteSnapshot(11L,null);
        verify(strategy.vmSnapshotDetailsDao,never()).remove(anyLong());
    }
}
