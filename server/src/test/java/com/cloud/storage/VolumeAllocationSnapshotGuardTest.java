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
package com.cloud.storage;

import java.util.List;
import java.util.Map;
import com.cloud.storage.snapshot.SnapshotManagerImpl;
import com.cloud.vm.snapshot.VMSnapshotManagerImpl;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.exception.InvalidParameterValueException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeAllocationSnapshotGuardTest {
    @InjectMocks private SnapshotManagerImpl snapshots;
    @InjectMocks private VMSnapshotManagerImpl vmSnapshots;
    @Mock private SnapshotDao snapshotDao;
    @Mock private VMSnapshotDao vmSnapshotDao;
    @Mock private VolumeDao volumes;
    @Mock private VolumeDetailsDao details;
    @Mock private SnapshotVO snapshot;
    @Mock private VMSnapshotVO vmSnapshot;
    @Mock private VolumeVO volume;
    @Before public void setup() {
        when(snapshotDao.findById(1L)).thenReturn(snapshot); when(snapshot.getVolumeId()).thenReturn(2L);
        when(vmSnapshotDao.findById(1L)).thenReturn(vmSnapshot); when(vmSnapshot.getVmId()).thenReturn(3L);
        when(volumes.findByInstance(3L)).thenReturn(List.of(volume)); when(volume.getId()).thenReturn(2L);
    }
    @Test(expected = InvalidParameterValueException.class) public void fullVolumeRevertStopsBeforeAnyStorageAction() {
        when(details.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null)); snapshots.revertSnapshot(1L);
    }
    @Test(expected = InvalidParameterValueException.class) public void explicitThinVolumeRevertAlsoNeedsQualification() {
        when(details.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("off", "raw", null)); snapshots.revertSnapshot(1L);
    }
    @Test(expected = InvalidParameterValueException.class) public void fullVmSnapshotRevertStopsBeforeAnyVmStateChange() throws Exception {
        when(details.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("full", "raw", null)); vmSnapshots.revertToSnapshot(1L);
    }
    @Test(expected = InvalidParameterValueException.class) public void explicitThinVmSnapshotRevertAlsoNeedsQualification() throws Exception {
        when(details.listDetailsKeyPairs(2L)).thenReturn(VolumeAllocationPolicy.request("off", "raw", null)); vmSnapshots.revertToSnapshot(1L);
    }
    @Test public void legacyVolumeRevertPreflightDoesNotWriteAnything() {
        when(details.listDetailsKeyPairs(2L)).thenReturn(Map.of()); ReflectionTestUtils.invokeMethod(snapshots, "validateAllocationRevert", 1L); verify(volumes, never()).update(anyLong(), any());
    }
    @Test public void legacyVmSnapshotRevertPreflightDoesNotWriteAnything() {
        when(details.listDetailsKeyPairs(2L)).thenReturn(Map.of()); ReflectionTestUtils.invokeMethod(vmSnapshots, "validateAllocationRevert", 1L); verify(volumes, never()).update(anyLong(), any());
    }
}
