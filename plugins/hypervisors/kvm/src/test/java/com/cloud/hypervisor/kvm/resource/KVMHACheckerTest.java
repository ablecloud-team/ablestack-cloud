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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.agent.api.to.HostTO;
import com.cloud.hypervisor.kvm.resource.KVMHABase.HAStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class KVMHACheckerTest {
    @Test
    public void combinesEveryStorageFamilyWithoutOverwritingEarlierResults() {
        for (boolean reportOneFailure : new boolean[]{false, true}) {
            for (int mask = 0; mask < 16; mask++) {
                HostTO host = Mockito.mock(HostTO.class);
                List<List<HAStoragePool>> groups = new ArrayList<>();
                for (int family = 0; family < 4; family++) {
                    HAStoragePool pool = Mockito.mock(HAStoragePool.class);
                    KVMStoragePool storage = Mockito.mock(KVMStoragePool.class);
                    Mockito.when(pool.getPool()).thenReturn(storage);
                    boolean alive = (mask & (1 << family)) != 0;
                    if (family == 2) {
                        Mockito.when(storage.checkingHeartBeatRBD(pool, host, "volume-a,volume-b")).thenReturn(alive);
                    } else {
                        Mockito.when(storage.hasHeartBeat(pool, host)).thenReturn(alive);
                    }
                    groups.add(List.of(pool));
                }
                KVMHAChecker checker = new KVMHAChecker(groups.get(0), groups.get(1), groups.get(2), groups.get(3),
                        host, reportOneFailure, "volume-a,volume-b");
                assertEquals("mask=" + mask + ", reportOneFailure=" + reportOneFailure,
                        reportOneFailure ? mask == 15 : mask != 0, checker.hasHeartBeat());
            }
        }
    }

    @Test
    public void noStorageObservationIsUndetermined() {
        for (boolean reportOneFailure : new boolean[]{false, true}) {
            assertNull(new KVMHAChecker(List.of(), List.of(), List.of(), List.of(), null, reportOneFailure, null).hasHeartBeat());
        }
    }

    @Test
    public void unknownStorageReplyIsNotProofOfDeath() {
        HostTO host = Mockito.mock(HostTO.class);
        HAStoragePool pool = Mockito.mock(HAStoragePool.class);
        KVMStoragePool storage = Mockito.mock(KVMStoragePool.class);
        Mockito.when(pool.getPool()).thenReturn(storage);
        Mockito.when(storage.hasHeartBeat(pool, host)).thenReturn(null);
        for (boolean reportOneFailure : new boolean[]{false, true}) {
            assertNull(new KVMHAChecker(List.of(pool), List.of(), List.of(), List.of(), host, reportOneFailure, null).hasHeartBeat());
        }
    }
}
