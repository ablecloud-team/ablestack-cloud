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

import com.cloud.dr.DrTargetResourceClaimVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@DB
public class DrTargetResourceClaimDaoImpl extends GenericDaoBase<DrTargetResourceClaimVO, Long>
        implements DrTargetResourceClaimDao {
    private final SearchBuilder<DrTargetResourceClaimVO> resourceKeySearch;
    private final SearchBuilder<DrTargetResourceClaimVO> roleKeySearch;
    private final SearchBuilder<DrTargetResourceClaimVO> activePlanSearch;

    public DrTargetResourceClaimDaoImpl() {
        resourceKeySearch = createSearchBuilder();
        resourceKeySearch.and("activeResourceKey", resourceKeySearch.entity().getActiveResourceKey(), SearchCriteria.Op.EQ);
        resourceKeySearch.done();
        roleKeySearch = createSearchBuilder();
        roleKeySearch.and("activeRoleKey", roleKeySearch.entity().getActiveRoleKey(), SearchCriteria.Op.EQ);
        roleKeySearch.done();
        activePlanSearch = createSearchBuilder();
        activePlanSearch.and("planId", activePlanSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        activePlanSearch.and("claimState", activePlanSearch.entity().getClaimState(), SearchCriteria.Op.EQ);
        activePlanSearch.done();
    }

    @Override
    public DrTargetResourceClaimVO findActiveByResourceKey(String activeResourceKey) {
        SearchCriteria<DrTargetResourceClaimVO> sc = resourceKeySearch.create();
        sc.setParameters("activeResourceKey", activeResourceKey);
        return findOneBy(sc);
    }

    @Override
    public DrTargetResourceClaimVO findActiveByRoleKey(String activeRoleKey) {
        SearchCriteria<DrTargetResourceClaimVO> sc = roleKeySearch.create();
        sc.setParameters("activeRoleKey", activeRoleKey);
        return findOneBy(sc);
    }

    @Override
    public List<DrTargetResourceClaimVO> listActiveByPlanId(long planId) {
        SearchCriteria<DrTargetResourceClaimVO> sc = activePlanSearch.create();
        sc.setParameters("planId", planId);
        sc.setParameters("claimState", "CLAIMED");
        return listBy(sc);
    }
}
