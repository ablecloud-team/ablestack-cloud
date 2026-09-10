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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.cloud.dr.DrFailbackSessionVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.UpdateBuilder;

@DB
public class DrFailbackSessionDaoImpl extends GenericDaoBase<DrFailbackSessionVO, Long> implements DrFailbackSessionDao {
    private final SearchBuilder<DrFailbackSessionVO> activeByRun;
    private final SearchBuilder<DrFailbackSessionVO> activeByPlan;
    private final SearchBuilder<DrFailbackSessionVO> reconcileCandidates;
    private static final String[] RECONCILE_STATES = {
            "REQUESTED", "DISPATCHED", "ENGINE_ACCEPTED", "REVERSE_PREFLIGHT", "REVERSE_SYNCING",
            "DATA_READY", "DATA_EVIDENCE_PENDING", "TARGET_STOPPING", "TARGET_STOPPED", "SOURCE_STARTING",
            "SOURCE_BOOT_VALIDATING", "AUTHORITY_COMMITTING", "COMMIT_VERIFYING",
            "PROTECTION_RESUMING", "ABORTING", "ROLLBACK_FAILED"
    };

    public DrFailbackSessionDaoImpl() {
        activeByRun = createSearchBuilder();
        activeByRun.and("runId", activeByRun.entity().getRunId(), SearchCriteria.Op.EQ);
        activeByRun.and("removed", activeByRun.entity().getRemoved(), SearchCriteria.Op.NULL);
        activeByRun.done();
        activeByPlan = createSearchBuilder();
        activeByPlan.and("planId", activeByPlan.entity().getPlanId(), SearchCriteria.Op.EQ);
        activeByPlan.and("removed", activeByPlan.entity().getRemoved(), SearchCriteria.Op.NULL);
        activeByPlan.done();
        reconcileCandidates = createSearchBuilder();
        reconcileCandidates.and("states", reconcileCandidates.entity().getState(), SearchCriteria.Op.IN);
        reconcileCandidates.and("removed", reconcileCandidates.entity().getRemoved(), SearchCriteria.Op.NULL);
        reconcileCandidates.done();
    }

    @Override
    public DrFailbackSessionVO findActiveByRunId(long runId) {
        SearchCriteria<DrFailbackSessionVO> sc = activeByRun.create();
        sc.setParameters("runId", runId);
        return findOneBy(sc);
    }

    @Override
    public DrFailbackSessionVO findLatestActiveByPlanId(long planId) {
        SearchCriteria<DrFailbackSessionVO> sc = activeByPlan.create();
        sc.setParameters("planId", planId);
        List<DrFailbackSessionVO> sessions = listBy(sc);
        DrFailbackSessionVO latest = null;
        for (DrFailbackSessionVO session : sessions) {
            if (latest == null || session.getId() > latest.getId()) {
                latest = session;
            }
        }
        return latest;
    }

    @Override
    public List<DrFailbackSessionVO> listReconcileCandidates(Date probeBefore, int limit) {
        SearchCriteria<DrFailbackSessionVO> sc = reconcileCandidates.create();
        sc.setParameters("states", (Object[]) RECONCILE_STATES);
        List<DrFailbackSessionVO> rows = listBy(sc,
                new Filter(DrFailbackSessionVO.class, "updated", true, 0L, 200L));
        List<DrFailbackSessionVO> candidates = new ArrayList<>();
        if (rows == null) {
            return candidates;
        }
        int normalizedLimit = Math.max(1, limit);
        for (DrFailbackSessionVO row : rows) {
            if (row.getLastProbeAt() != null && probeBefore != null
                    && !row.getLastProbeAt().before(probeBefore)) {
                continue;
            }
            candidates.add(row);
            if (candidates.size() >= normalizedLimit) {
                break;
            }
        }
        return candidates;
    }

    @Override
    public void clearFailureMetadata(long sessionId) {
        DrFailbackSessionVO update = createForUpdate();
        UpdateBuilder builder = getUpdateBuilder(update);
        builder.set(update, "failurePhase", null);
        builder.set(update, "failedComponent", null);
        builder.set(update, "errorCode", null);
        builder.set(update, "errorMessage", null);
        update(sessionId, builder, update);
    }
}
