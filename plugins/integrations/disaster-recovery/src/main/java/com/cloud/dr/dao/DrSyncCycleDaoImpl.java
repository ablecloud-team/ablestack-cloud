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

import java.util.Date;
import java.util.List;

import com.cloud.dr.DrSyncCycleVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.UpdateBuilder;

@DB
public class DrSyncCycleDaoImpl extends GenericDaoBase<DrSyncCycleVO, Long> implements DrSyncCycleDao {
    private final SearchBuilder<DrSyncCycleVO> byIdentitySearch;
    private final SearchBuilder<DrSyncCycleVO> byPlanSequenceSearch;
    private final SearchBuilder<DrSyncCycleVO> byPlanCycleTokenSearch;
    private final SearchBuilder<DrSyncCycleVO> byPlanSchedulerCycleSearch;
    private final SearchBuilder<DrSyncCycleVO> byPlanSearch;
    private final SearchBuilder<DrSyncCycleVO> activeByPlanSearch;
    private final SearchBuilder<DrSyncCycleVO> completedByPlanSearch;
    private final SearchBuilder<DrSyncCycleVO> completedByRunAndRequestedModeSearch;
    private final SearchBuilder<DrSyncCycleVO> incompleteBeforeSequenceSearch;
    private final SearchBuilder<DrSyncCycleVO> incompleteAtOrBeforeSequenceSearch;

    private static final String[] ACTIVE_STATES = {
            "PREPARING", "SNAPSHOTTING", "TRANSFERRING", "COMMITTING", "RETRYING", "RUNNING"
    };

    public DrSyncCycleDaoImpl() {
        byIdentitySearch = createSearchBuilder();
        byIdentitySearch.and("planId", byIdentitySearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        byIdentitySearch.and("runUuid", byIdentitySearch.entity().getEngineRunUuid(), SearchCriteria.Op.EQ);
        byIdentitySearch.and("sequence", byIdentitySearch.entity().getSequence(), SearchCriteria.Op.EQ);
        byIdentitySearch.done();

        byPlanSequenceSearch = createSearchBuilder();
        byPlanSequenceSearch.and("planId", byPlanSequenceSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        byPlanSequenceSearch.and("sequence", byPlanSequenceSearch.entity().getSequence(), SearchCriteria.Op.EQ);
        byPlanSequenceSearch.and("removed", byPlanSequenceSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        byPlanSequenceSearch.done();

        byPlanCycleTokenSearch = createSearchBuilder();
        byPlanCycleTokenSearch.and("planId", byPlanCycleTokenSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        byPlanCycleTokenSearch.and("cycleToken", byPlanCycleTokenSearch.entity().getCycleToken(), SearchCriteria.Op.EQ);
        byPlanCycleTokenSearch.and("removed", byPlanCycleTokenSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        byPlanCycleTokenSearch.done();

        byPlanSchedulerCycleSearch = createSearchBuilder();
        byPlanSchedulerCycleSearch.and("planId", byPlanSchedulerCycleSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        byPlanSchedulerCycleSearch.and("schedulerSessionUuid", byPlanSchedulerCycleSearch.entity().getSchedulerSessionUuid(), SearchCriteria.Op.EQ);
        byPlanSchedulerCycleSearch.and("schedulerLeaseEpoch", byPlanSchedulerCycleSearch.entity().getSchedulerLeaseEpoch(), SearchCriteria.Op.EQ);
        byPlanSchedulerCycleSearch.and("cycleToken", byPlanSchedulerCycleSearch.entity().getCycleToken(), SearchCriteria.Op.EQ);
        byPlanSchedulerCycleSearch.and("removed", byPlanSchedulerCycleSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        byPlanSchedulerCycleSearch.done();

        byPlanSearch = createSearchBuilder();
        byPlanSearch.and("planId", byPlanSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        byPlanSearch.and("removed", byPlanSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        byPlanSearch.done();

        activeByPlanSearch = createSearchBuilder();
        activeByPlanSearch.and("planId", activeByPlanSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        activeByPlanSearch.and("states", activeByPlanSearch.entity().getState(), SearchCriteria.Op.IN);
        activeByPlanSearch.and("removed", activeByPlanSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        activeByPlanSearch.done();

        completedByPlanSearch = createSearchBuilder();
        completedByPlanSearch.and("planId", completedByPlanSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        completedByPlanSearch.and("completed", completedByPlanSearch.entity().getCompleted(), SearchCriteria.Op.NNULL);
        completedByPlanSearch.and("removed", completedByPlanSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        completedByPlanSearch.done();

        completedByRunAndRequestedModeSearch = createSearchBuilder();
        completedByRunAndRequestedModeSearch.and("runId", completedByRunAndRequestedModeSearch.entity().getRunId(), SearchCriteria.Op.EQ);
        completedByRunAndRequestedModeSearch.and("requestedMode", completedByRunAndRequestedModeSearch.entity().getRequestedMode(), SearchCriteria.Op.EQ);
        completedByRunAndRequestedModeSearch.and("completed", completedByRunAndRequestedModeSearch.entity().getCompleted(), SearchCriteria.Op.NNULL);
        completedByRunAndRequestedModeSearch.and("removed", completedByRunAndRequestedModeSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        completedByRunAndRequestedModeSearch.done();

        incompleteBeforeSequenceSearch = createSearchBuilder();
        incompleteBeforeSequenceSearch.and("planId", incompleteBeforeSequenceSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        incompleteBeforeSequenceSearch.and("sequence", incompleteBeforeSequenceSearch.entity().getSequence(), SearchCriteria.Op.LT);
        incompleteBeforeSequenceSearch.and("completed", incompleteBeforeSequenceSearch.entity().getCompleted(), SearchCriteria.Op.NULL);
        incompleteBeforeSequenceSearch.and("removed", incompleteBeforeSequenceSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        incompleteBeforeSequenceSearch.done();

        incompleteAtOrBeforeSequenceSearch = createSearchBuilder();
        incompleteAtOrBeforeSequenceSearch.and("planId", incompleteAtOrBeforeSequenceSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        incompleteAtOrBeforeSequenceSearch.and("sequence", incompleteAtOrBeforeSequenceSearch.entity().getSequence(), SearchCriteria.Op.LTEQ);
        incompleteAtOrBeforeSequenceSearch.and("completed", incompleteAtOrBeforeSequenceSearch.entity().getCompleted(), SearchCriteria.Op.NULL);
        incompleteAtOrBeforeSequenceSearch.and("removed", incompleteAtOrBeforeSequenceSearch.entity().getRemoved(), SearchCriteria.Op.NULL);
        incompleteAtOrBeforeSequenceSearch.done();
    }

    @Override
    public DrSyncCycleVO findByPlanRunSequence(long planId, String runUuid, long sequence) {
        SearchCriteria<DrSyncCycleVO> sc = byIdentitySearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("runUuid", runUuid);
        sc.setParameters("sequence", sequence);
        return findOneBy(sc);
    }

    @Override
    public DrSyncCycleVO findByPlanSequence(long planId, long sequence) {
        SearchCriteria<DrSyncCycleVO> sc = byPlanSequenceSearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("sequence", sequence);
        List<DrSyncCycleVO> rows = listBy(sc, new Filter(DrSyncCycleVO.class, "completed", false, 0L, 1L));
        return rows != null && !rows.isEmpty() ? rows.get(0) : null;
    }

    @Override
    public DrSyncCycleVO findByPlanCycleToken(long planId, String cycleToken) {
        SearchCriteria<DrSyncCycleVO> sc = byPlanCycleTokenSearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("cycleToken", cycleToken);
        List<DrSyncCycleVO> rows = listBy(sc,
                new Filter(DrSyncCycleVO.class, "completed", false, 0L, 1L));
        return rows != null && !rows.isEmpty() ? rows.get(0) : null;
    }

    @Override
    public DrSyncCycleVO findByPlanSchedulerCycle(long planId, String schedulerSessionUuid,
            long schedulerLeaseEpoch, String cycleToken) {
        SearchCriteria<DrSyncCycleVO> sc = byPlanSchedulerCycleSearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("schedulerSessionUuid", schedulerSessionUuid);
        sc.setParameters("schedulerLeaseEpoch", schedulerLeaseEpoch);
        sc.setParameters("cycleToken", cycleToken);
        return findOneBy(sc);
    }

    @Override
    public DrSyncCycleVO findLatestByPlanId(long planId) {
        List<DrSyncCycleVO> rows = listByPlanId(planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public DrSyncCycleVO findActiveByPlanId(long planId) {
        SearchCriteria<DrSyncCycleVO> sc = activeByPlanSearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("states", (Object[]) ACTIVE_STATES);
        List<DrSyncCycleVO> rows = listBy(sc, new Filter(DrSyncCycleVO.class, "sequence", false, 0L, 1L));
        return rows != null && !rows.isEmpty() ? rows.get(0) : null;
    }

    @Override
    public DrSyncCycleVO findLatestCompletedByPlanId(long planId) {
        SearchCriteria<DrSyncCycleVO> sc = completedByPlanSearch.create();
        sc.setParameters("planId", planId);
        List<DrSyncCycleVO> rows = listBy(sc, new Filter(DrSyncCycleVO.class, "sequence", false, 0L, 1L));
        return rows != null && !rows.isEmpty() ? rows.get(0) : null;
    }

    @Override
    public DrSyncCycleVO findLatestCompletedByRunIdAndRequestedMode(long runId, String requestedMode) {
        SearchCriteria<DrSyncCycleVO> sc = completedByRunAndRequestedModeSearch.create();
        sc.setParameters("runId", runId);
        sc.setParameters("requestedMode", requestedMode);
        List<DrSyncCycleVO> rows = listBy(sc, new Filter(DrSyncCycleVO.class, "sequence", false, 0L, 1L));
        return rows != null && !rows.isEmpty() ? rows.get(0) : null;
    }

    @Override
    public List<DrSyncCycleVO> listIncompleteBeforeSequence(long planId, long sequence, int limit) {
        SearchCriteria<DrSyncCycleVO> sc = incompleteBeforeSequenceSearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("sequence", sequence);
        return listBy(sc, new Filter(DrSyncCycleVO.class, "sequence", true, 0L, (long) Math.max(1, limit)));
    }

    @Override
    public List<DrSyncCycleVO> listIncompleteAtOrBeforeSequence(long planId, long sequence, int limit) {
        SearchCriteria<DrSyncCycleVO> sc = incompleteAtOrBeforeSequenceSearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("sequence", sequence);
        return listBy(sc, new Filter(DrSyncCycleVO.class, "sequence", true, 0L, (long) Math.max(1, limit)));
    }

    @Override
    public void terminalize(long cycleId, String state, String commitState, Date completedAt) {
        DrSyncCycleVO update = createForUpdate();
        UpdateBuilder builder = getUpdateBuilder(update);
        builder.set(update, "state", state);
        builder.set(update, "commitState", commitState);
        builder.set(update, "completed", completedAt != null ? completedAt : new Date());
        builder.set(update, "errorCode", null);
        builder.set(update, "errorMessage", null);
        update(cycleId, builder, update);
    }

    @Override
    public List<DrSyncCycleVO> listByPlanId(long planId) {
        SearchCriteria<DrSyncCycleVO> sc = byPlanSearch.create();
        sc.setParameters("planId", planId);
        return listBy(sc, new Filter(DrSyncCycleVO.class, "sequence", false, null, null));
    }

    @Override
    public int removeByPlanId(long planId) {
        SearchCriteria<DrSyncCycleVO> sc = byPlanSearch.create();
        sc.setParameters("planId", planId);
        return remove(sc);
    }
}
