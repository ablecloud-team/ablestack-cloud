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
package com.cloud.hypervisor.kvm.resource;

import java.util.List;
import java.util.concurrent.Callable;

import com.cloud.agent.api.to.HostTO;

public class KVMHAChecker extends KVMHABase implements Callable<Boolean> {
    private List<HAStoragePool> storagePools;
    private List<HAStoragePool> gfsStoragePools;
    private List<HAStoragePool> rbdStoragePools;
    private List<HAStoragePool> clvmStoragePools;
    private HostTO host;
    private boolean reportIfHeartBeatFailedForOneStoragePool;
    private String volumeList;

    public KVMHAChecker(List<HAStoragePool> pools, List<HAStoragePool> gfspools, List<HAStoragePool> rbdpools, List<HAStoragePool> clvmpools, HostTO host, boolean reportIfHeartBeatFailedForOneStoragePool, String volumeList) {
        this.storagePools = pools;
        this.gfsStoragePools = gfspools;
        this.rbdStoragePools = rbdpools;
        this.clvmStoragePools = clvmpools;
        this.host = host;
        this.reportIfHeartBeatFailedForOneStoragePool = reportIfHeartBeatFailedForOneStoragePool;
        this.volumeList = volumeList;
    }

    /*
     * True means heart beating is on going, or we can't get it's status.
     * False means heart beating is stopped definitely.
     */
    @Override
    public Boolean hasHeartBeat() {
        boolean checked = false;
        boolean unknown = false;
        List<List<HAStoragePool>> poolGroups = java.util.Arrays.asList(storagePools, gfsStoragePools, rbdStoragePools, clvmStoragePools);
        for (List<HAStoragePool> pools : poolGroups) {
            if (pools == null) {
                continue;
            }
            for (HAStoragePool pool : pools) {
                checked = true;
                Boolean alive = pools == rbdStoragePools
                        ? pool.getPool().checkingHeartBeatRBD(pool, host, volumeList)
                        : pool.getPool().hasHeartBeat(pool, host);
                if (alive == null) {
                    unknown = true;
                } else if (reportIfHeartBeatFailedForOneStoragePool && !alive) {
                    return false;
                } else if (!reportIfHeartBeatFailedForOneStoragePool && alive) {
                    return true;
                }
            }
        }
        // No observation, including an unknown result, is not proof of host death.
        if (!checked || unknown) {
            return null;
        }
        return reportIfHeartBeatFailedForOneStoragePool;
    }

    @Override
    public Boolean call() throws Exception {
        return hasHeartBeat();
    }
}
