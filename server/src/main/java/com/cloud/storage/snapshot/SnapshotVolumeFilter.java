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

import java.util.LinkedHashSet;
import java.util.Set;

import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

public final class SnapshotVolumeFilter {
    private SnapshotVolumeFilter() {
    }

    public static Long[] forVolume(VolumeDao volumeDao, Long volumeId) {
        Set<Long> volumeIds = new LinkedHashSet<>();
        // The backup remains addressable by its original volume after expunge.
        volumeIds.add(volumeId);
        VolumeVO volume = volumeDao.findById(volumeId);
        if (volume != null && volume.getPoolId() != null && volume.getPath() != null) {
            for (VolumeVO shared : volumeDao.findBySharedVolume(volume.getPoolId(), volume.getPath())) {
                volumeIds.add(shared.getId());
            }
        }
        return volumeIds.toArray(new Long[0]);
    }
}
