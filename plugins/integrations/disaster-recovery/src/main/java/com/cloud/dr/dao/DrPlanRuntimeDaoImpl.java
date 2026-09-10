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

import com.cloud.dr.DrPlanRuntimeVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@DB
public class DrPlanRuntimeDaoImpl extends GenericDaoBase<DrPlanRuntimeVO, Long> implements DrPlanRuntimeDao {
    private final SearchBuilder<DrPlanRuntimeVO> byPlanSearch;

    public DrPlanRuntimeDaoImpl() {
        byPlanSearch = createSearchBuilder();
        byPlanSearch.and("planId", byPlanSearch.entity().getPlanId(), SearchCriteria.Op.EQ);
        byPlanSearch.done();
    }

    @Override
    public DrPlanRuntimeVO findByPlanId(long planId) {
        SearchCriteria<DrPlanRuntimeVO> sc = byPlanSearch.create();
        sc.setParameters("planId", planId);
        return findOneBy(sc);
    }

    @Override
    public int removeByPlanId(long planId) {
        SearchCriteria<DrPlanRuntimeVO> sc = byPlanSearch.create();
        sc.setParameters("planId", planId);
        return remove(sc);
    }
}
