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

import com.cloud.dr.DrTestDiskVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@DB
public class DrTestDiskDaoImpl extends GenericDaoBase<DrTestDiskVO, Long> implements DrTestDiskDao {
    private final SearchBuilder<DrTestDiskVO> activeBySession;

    public DrTestDiskDaoImpl() {
        activeBySession = createSearchBuilder();
        activeBySession.and("sessionId", activeBySession.entity().getSessionId(), SearchCriteria.Op.EQ);
        activeBySession.and("removed", activeBySession.entity().getRemoved(), SearchCriteria.Op.NULL);
        activeBySession.done();
    }

    @Override public List<DrTestDiskVO> listActiveBySessionId(long sessionId) {
        SearchCriteria<DrTestDiskVO> sc = activeBySession.create();
        sc.setParameters("sessionId", sessionId);
        return listBy(sc);
    }
}
