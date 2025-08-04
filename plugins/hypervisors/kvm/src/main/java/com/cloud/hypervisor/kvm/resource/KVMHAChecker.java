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
import java.util.stream.Collectors;


import com.cloud.agent.api.to.HostTO;

public class KVMHAChecker extends KVMHABase implements Callable<Boolean> {
    private List<HAStoragePool> storagePools;
    private List<HAStoragePool> gfsStoragePools;
    private List<HAStoragePool> rbdStoragePools;
    private List<HAStoragePool> clvmStoragePools;
    private HostTO host;
    private boolean reportFailureIfOneStorageIsDown;
    private String volumeList;

    public KVMHAChecker(List<HAStoragePool> pools, List<HAStoragePool> gfspools, List<HAStoragePool> rbdpools, List<HAStoragePool> clvmpools, HostTO host, boolean reportFailureIfOneStorageIsDown, String volumeList) {
        this.storagePools = pools;
        this.gfsStoragePools = gfspools;
        this.rbdStoragePools = rbdpools;
        this.clvmStoragePools = clvmpools;
        this.host = host;
        this.reportFailureIfOneStorageIsDown = reportFailureIfOneStorageIsDown;
        this.volumeList = volumeList;
    }

    /*
     * True means heartbeaing is on going, or we can't get it's status. False
     * means heartbeating is stopped definitely
     */
    @Override
    public Boolean checkingHeartBeat() {
        boolean validResult = false;

        // NFS
        for (HAStoragePool pool : storagePools) {
            logger.debug(String.format(
                "Checking heart beat with KVMHAChecker NFS for host IP [%s] in pool [%s]",
                host.getPrivateNetwork().getIp(),
                pool.getPoolUUID()
            ));
            validResult = pool.getPool().checkingHeartBeat(pool, host);
            if (reportFailureIfOneStorageIsDown && !validResult) break;
        }

        // SharedMountPoint(GFS)
        for (HAStoragePool gfspool : gfsStoragePools) {
            logger.debug(String.format(
                "Checking heart beat with KVMHAChecker SharedMountPoint for host IP [%s] in pool [%s]",
                host.getPrivateNetwork().getIp(),
                gfspool.getPoolUUID()
            ));
            validResult = gfspool.getPool().checkingHeartBeat(gfspool, host);
            if (reportFailureIfOneStorageIsDown && !validResult) break;
        }

        // RBD
        for (HAStoragePool rbdpool : rbdStoragePools) {
            logger.debug(String.format(
                "Checking heart beat with KVMHAChecker RBD for host IP [%s] in pool [%s]",
                host.getPrivateNetwork().getIp(),
                rbdpool.monHost
            ));
            validResult = rbdpool.getPool().checkingHeartBeatRBD(rbdpool, host, volumeList);
            if (reportFailureIfOneStorageIsDown && !validResult) break;
        }

        // CLVM
        for (HAStoragePool clvmpool : clvmStoragePools) {
            logger.debug(String.format(
                "Checking heart beat with KVMHAChecker CLVM for host IP [%s] in pool [%s]",
                host.getPrivateNetwork().getIp(),
                clvmpool.poolIp
            ));
            validResult = clvmpool.getPool().checkingHeartBeat(clvmpool, host);
            if (reportFailureIfOneStorageIsDown && !validResult) break;
        }

        if (!validResult) {
            // 마지막 검사한 pool의 정보만 남음(가장 마지막 실패 기준)
            logger.warn("All checks with KVMHAChecker considered it as dead. It may cause a shutdown of the host.");
        }
        return validResult;
    }

    @Override
    public Boolean call() throws Exception {
        return checkingHeartBeat();
    }
}
