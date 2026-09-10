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

import com.cloud.dr.DrResourceLeaseVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@DB
public class DrResourceLeaseDaoImpl extends GenericDaoBase<DrResourceLeaseVO, Long> implements DrResourceLeaseDao {
    private final SearchBuilder<DrResourceLeaseVO> activeByResource;
    private final SearchBuilder<DrResourceLeaseVO> activeByRun;
    private final SearchBuilder<DrResourceLeaseVO> byRun;

    public DrResourceLeaseDaoImpl() {
        activeByResource = createSearchBuilder();
        activeByResource.and("resourceKey", activeByResource.entity().getResourceKey(), SearchCriteria.Op.EQ);
        activeByResource.and("state", activeByResource.entity().getState(), SearchCriteria.Op.EQ);
        activeByResource.and("expiresAt", activeByResource.entity().getExpiresAt(), SearchCriteria.Op.GT);
        activeByResource.done();

        activeByRun = createSearchBuilder();
        activeByRun.and("runId", activeByRun.entity().getRunId(), SearchCriteria.Op.EQ);
        activeByRun.and("state", activeByRun.entity().getState(), SearchCriteria.Op.EQ);
        activeByRun.and("expiresAt", activeByRun.entity().getExpiresAt(), SearchCriteria.Op.GT);
        activeByRun.done();

        byRun = createSearchBuilder();
        byRun.and("runId", byRun.entity().getRunId(), SearchCriteria.Op.EQ);
        byRun.and("state", byRun.entity().getState(), SearchCriteria.Op.EQ);
        byRun.done();
    }

    @Override
    public List<DrResourceLeaseVO> listActiveByResourceKey(String resourceKey, Date now) {
        SearchCriteria<DrResourceLeaseVO> sc = activeByResource.create();
        sc.setParameters("resourceKey", resourceKey);
        sc.setParameters("state", "ACTIVE");
        sc.setParameters("expiresAt", now);
        return listBy(sc);
    }

    @Override
    public DrResourceLeaseVO findActiveByRunId(long runId, Date now) {
        SearchCriteria<DrResourceLeaseVO> sc = activeByRun.create();
        sc.setParameters("runId", runId);
        sc.setParameters("state", "ACTIVE");
        sc.setParameters("expiresAt", now);
        return findOneBy(sc);
    }

    @Override
    public DrResourceLeaseVO findByRunId(long runId) {
        SearchCriteria<DrResourceLeaseVO> sc = byRun.create();
        sc.setParameters("runId", runId);
        sc.setParameters("state", "ACTIVE");
        return findOneBy(sc);
    }
}
