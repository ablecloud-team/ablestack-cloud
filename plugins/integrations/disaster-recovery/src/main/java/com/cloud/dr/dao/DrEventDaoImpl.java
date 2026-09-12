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

import com.cloud.dr.DrEventVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@DB
public class DrEventDaoImpl extends GenericDaoBase<DrEventVO, Long> implements DrEventDao {

    private final SearchBuilder<DrEventVO> byPlanSearch;
    private final SearchBuilder<DrEventVO> byRunSearch;
    private final SearchBuilder<DrEventVO> significantByPlanSearch;
    private final SearchBuilder<DrEventVO> byPlanAndEventTypeSearch;

    public DrEventDaoImpl() {
        byPlanSearch = createSearchBuilder();
        byPlanSearch.and("planId", byPlanSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        byPlanSearch.done();

        byRunSearch = createSearchBuilder();
        byRunSearch.and("runId", byRunSearch.entity().getRunId(), SearchCriteria.Op.EQ);
        byRunSearch.done();

        significantByPlanSearch = createSearchBuilder();
        significantByPlanSearch.and("planId", significantByPlanSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        significantByPlanSearch.and("eventType", significantByPlanSearch.entity().getEventType(), SearchCriteria.Op.NEQ);
        significantByPlanSearch.done();

        byPlanAndEventTypeSearch = createSearchBuilder();
        byPlanAndEventTypeSearch.and("planId", byPlanAndEventTypeSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        byPlanAndEventTypeSearch.and("eventType", byPlanAndEventTypeSearch.entity().getEventType(), SearchCriteria.Op.EQ);
        byPlanAndEventTypeSearch.done();
    }

    @Override
    public List<DrEventVO> listCleanupRecords() {
        SearchCriteria<DrEventVO> sc = createSearchCriteria();
        sc.addAnd("eventType", SearchCriteria.Op.EQ, "DR_UNREGISTERED_RESOURCES");
        return listBy(sc, new Filter(DrEventVO.class, "created", false, 0L, 500L));
    }

    @Override
    public List<DrEventVO> listByPlanId(long planId) {
        SearchCriteria<DrEventVO> sc = byPlanSearch.create();
        sc.setParameters("planId", planId);
        return listBy(sc, new Filter(DrEventVO.class, "created", false, null, null));
    }

    @Override
    public List<DrEventVO> listRecentByPlanId(long planId, int limit, boolean includeProjectionRefresh) {
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        SearchCriteria<DrEventVO> sc = includeProjectionRefresh ? byPlanSearch.create() : significantByPlanSearch.create();
        sc.setParameters("planId", planId);
        if (!includeProjectionRefresh) {
            sc.setParameters("eventType", "PROJECTION_REFRESH");
        }
        return listBy(sc, new Filter(DrEventVO.class, "created", false, 0L, (long) normalizedLimit));
    }

    @Override
    public List<DrEventVO> listByRunId(long runId) {
        SearchCriteria<DrEventVO> sc = byRunSearch.create();
        sc.setParameters("runId", runId);
        return listBy(sc, new Filter(DrEventVO.class, "created", false, null, null));
    }

    @Override
    public List<DrEventVO> listRecentByRunId(long runId, int limit) {
        SearchCriteria<DrEventVO> sc = byRunSearch.create();
        sc.setParameters("runId", runId);
        int normalizedLimit = Math.max(1, Math.min(limit, 100));
        return listBy(sc, new Filter(DrEventVO.class, "created", false, 0L, (long) normalizedLimit));
    }

    @Override
    public Pair<List<DrEventVO>, Integer> searchRecentByPlanId(long planId, long offset, long limit, boolean includeProjectionRefresh) {
        SearchCriteria<DrEventVO> sc = includeProjectionRefresh ? byPlanSearch.create() : significantByPlanSearch.create();
        sc.setParameters("planId", planId);
        if (!includeProjectionRefresh) {
            sc.setParameters("eventType", "PROJECTION_REFRESH");
        }
        long normalizedLimit = Math.max(1L, Math.min(limit, 100L));
        return searchAndCount(sc, new Filter(DrEventVO.class, "created", false, Math.max(0L, offset), normalizedLimit));
    }

    @Override
    public Pair<List<DrEventVO>, Integer> searchRecentByRunId(long runId, long offset, long limit) {
        SearchCriteria<DrEventVO> sc = byRunSearch.create();
        sc.setParameters("runId", runId);
        long normalizedLimit = Math.max(1L, Math.min(limit, 100L));
        return searchAndCount(sc, new Filter(DrEventVO.class, "created", false, Math.max(0L, offset), normalizedLimit));
    }

    @Override
    public DrEventVO findLatestByPlanIdAndEventType(long planId, String eventType) {
        SearchCriteria<DrEventVO> sc = byPlanAndEventTypeSearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("eventType", eventType);
        List<DrEventVO> events = listBy(sc, new Filter(DrEventVO.class, "created", false, 0L, 1L));
        return events.isEmpty() ? null : events.get(0);
    }
}
