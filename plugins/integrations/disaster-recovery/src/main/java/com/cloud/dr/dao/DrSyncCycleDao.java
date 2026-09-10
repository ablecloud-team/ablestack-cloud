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

package com.cloud.dr.dao;

import java.util.List;
import java.util.Date;

import com.cloud.dr.DrSyncCycleVO;
import com.cloud.utils.db.GenericDao;

public interface DrSyncCycleDao extends GenericDao<DrSyncCycleVO, Long> {
    DrSyncCycleVO findByPlanRunSequence(long planId, String runUuid, long sequence);
    DrSyncCycleVO findByPlanSequence(long planId, long sequence);
    DrSyncCycleVO findByPlanCycleToken(long planId, String cycleToken);
    DrSyncCycleVO findByPlanSchedulerCycle(long planId, String schedulerSessionUuid,
            long schedulerLeaseEpoch, String cycleToken);
    DrSyncCycleVO findActiveByPlanId(long planId);
    DrSyncCycleVO findLatestCompletedByPlanId(long planId);
    DrSyncCycleVO findLatestCompletedByRunIdAndRequestedMode(long runId, String requestedMode);
    DrSyncCycleVO findLatestByPlanId(long planId);
    List<DrSyncCycleVO> listIncompleteBeforeSequence(long planId, long sequence, int limit);
    List<DrSyncCycleVO> listIncompleteAtOrBeforeSequence(long planId, long sequence, int limit);
    void terminalize(long cycleId, String state, String commitState, Date completedAt);
    List<DrSyncCycleVO> listByPlanId(long planId);
    int removeByPlanId(long planId);
}
