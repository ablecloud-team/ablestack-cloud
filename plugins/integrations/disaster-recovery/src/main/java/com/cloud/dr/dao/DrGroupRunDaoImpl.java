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

import com.cloud.dr.DrGroupRunVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@DB
public class DrGroupRunDaoImpl extends GenericDaoBase<DrGroupRunVO, Long> implements DrGroupRunDao {
    private final SearchBuilder<DrGroupRunVO> byGroup;
    private final SearchBuilder<DrGroupRunVO> recoverable;

    public DrGroupRunDaoImpl() {
        byGroup = createSearchBuilder();
        byGroup.and("groupUuid", byGroup.entity().getGroupUuid(), SearchCriteria.Op.EQ);
        byGroup.done();
        recoverable = createSearchBuilder();
        recoverable.and("state", recoverable.entity().getState(), SearchCriteria.Op.IN);
        recoverable.done();
    }

    @Override public List<DrGroupRunVO> listByGroupUuid(String groupUuid) {
        SearchCriteria<DrGroupRunVO> sc = byGroup.create();
        sc.setParameters("groupUuid", groupUuid);
        return listBy(sc, new Filter(DrGroupRunVO.class, "created", false, 0L, 100L));
    }

    @Override public List<DrGroupRunVO> listRecoverable() {
        SearchCriteria<DrGroupRunVO> sc = recoverable.create();
        sc.setParameters("state", "QUEUED", "RUNNING");
        return listBy(sc);
    }
}
