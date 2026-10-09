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

package com.cloud.storage.snapshot;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

public class SnapshotVolumeFilterTest {
    @Test
    public void expungedVolumeKeepsExactBackupFilter() {
        VolumeDao dao = mock(VolumeDao.class);
        assertArrayEquals(new Long[]{42L}, SnapshotVolumeFilter.forVolume(dao, 42L));
    }

    @Test
    public void unplacedVolumeDoesNotMatchUnrelatedNullPaths() {
        VolumeDao dao = mock(VolumeDao.class);
        VolumeVO volume = mock(VolumeVO.class);
        when(dao.findById(42L)).thenReturn(volume);
        assertArrayEquals(new Long[]{42L}, SnapshotVolumeFilter.forVolume(dao, 42L));
        verify(dao, never()).findBySharedVolume(anyLong(), anyString());
    }

    @Test
    public void sharedVolumeIncludesOriginalAndDeduplicatesAliases() {
        VolumeDao dao = mock(VolumeDao.class);
        VolumeVO volume = mock(VolumeVO.class);
        VolumeVO alias = mock(VolumeVO.class);
        when(dao.findById(42L)).thenReturn(volume);
        when(volume.getPoolId()).thenReturn(7L);
        when(volume.getPath()).thenReturn("root-image");
        when(volume.getId()).thenReturn(42L);
        when(alias.getId()).thenReturn(43L);
        when(dao.findBySharedVolume(7L, "root-image")).thenReturn(Arrays.asList(volume, alias, alias));
        assertArrayEquals(new Long[]{42L, 43L}, SnapshotVolumeFilter.forVolume(dao, 42L));
    }

    @Test
    public void emptySharedLookupNeverDropsVolumeRestriction() {
        VolumeDao dao = mock(VolumeDao.class);
        VolumeVO volume = mock(VolumeVO.class);
        when(dao.findById(42L)).thenReturn(volume);
        when(volume.getPoolId()).thenReturn(7L);
        when(volume.getPath()).thenReturn("root-image");
        when(dao.findBySharedVolume(7L, "root-image")).thenReturn(Collections.emptyList());
        assertArrayEquals(new Long[]{42L}, SnapshotVolumeFilter.forVolume(dao, 42L));
    }
}
