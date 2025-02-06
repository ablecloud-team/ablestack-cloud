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
        String hostAndPools = String.format("host IP [%s] in pools [%s]", host.getPrivateNetwork().getIp(), storagePools.stream().map(pool -> pool.getPoolUUID()).collect(Collectors.joining(", ")));

        for (HAStoragePool pool : storagePools) {
            logger.debug(String.format("Checking heart beat with KVMHAChecker NFS for %s", hostAndPools));
            validResult = pool.getPool().checkingHeartBeat(pool, host);
            if (reportFailureIfOneStorageIsDown && !validResult) {
                break;
            }
        }

        hostAndPools = String.format("host IP [%s] in SharedMountPoint pools [%s]", host.getPrivateNetwork().getIp(), gfsStoragePools.stream().map(pool -> pool.getPoolUUID()).collect(Collectors.joining(", ")));
        for (HAStoragePool gfspool : gfsStoragePools) {
            logger.debug(String.format("Checking heart beat with KVMHAChecker SharedMountPoint for %s", hostAndPools));
            validResult = gfspool.getPool().checkingHeartBeat(gfspool, host);
            if (reportFailureIfOneStorageIsDown && !validResult) {
                break;
            }
        }

        hostAndPools = String.format("host IP [%s] in RBD pools [%s]", host.getPrivateNetwork().getIp(), rbdStoragePools.stream().map(pool -> pool.monHost).collect(Collectors.joining(", ")));
        for (HAStoragePool rbdpool : rbdStoragePools) {
            logger.debug(String.format("Checking heart beat with KVMHAChecker RBD for %s", hostAndPools));
            validResult = rbdpool.getPool().checkingHeartBeatRBD(rbdpool, host, volumeList);
            if (reportFailureIfOneStorageIsDown && !validResult) {
                break;
            }
        }
        hostAndPools = String.format("host IP [%s] in CLVM pools [%s]", host.getPrivateNetwork().getIp(), clvmStoragePools.stream().map(pool -> pool.poolIp).collect(Collectors.joining(", ")));
        for (HAStoragePool clvmpool : clvmStoragePools) {
            logger.debug(String.format("Checking heart beat with KVMHAChecker CLVM for %s", hostAndPools));
            validResult = clvmpool.getPool().checkingHeartBeat(clvmpool, host);
            if (reportFailureIfOneStorageIsDown && !validResult) {
                break;
            }
        }
        if (!validResult) {
            logger.warn(String.format("All checks with KVMHAChecker for %s considered it as dead. It may cause a shutdown of the host.", hostAndPools));
        }
        return validResult;
    }

    @Override
    public Boolean call() throws Exception {
        // logger.addAppender(new org.apache.log4j.ConsoleAppender(new
        // org.apache.log4j.PatternLayout(), "System.out"));
        return checkingHeartBeat();
    }
}
