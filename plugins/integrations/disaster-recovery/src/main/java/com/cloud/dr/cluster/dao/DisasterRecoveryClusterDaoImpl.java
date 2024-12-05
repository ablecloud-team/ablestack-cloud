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

package com.cloud.dr.cluster.dao;

import com.cloud.dr.cluster.DisasterRecoveryClusterVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DisasterRecoveryClusterDaoImpl extends GenericDaoBase<DisasterRecoveryClusterVO, Long> implements DisasterRecoveryClusterDao {
    protected SearchBuilder<DisasterRecoveryClusterVO> DisasterRecoveryClusterListSearchBuilder;
    protected SearchBuilder<DisasterRecoveryClusterVO> DisasterRecoveryClusterNameSearchBuilder;

    protected DisasterRecoveryClusterDaoImpl() {
        super();
        DisasterRecoveryClusterListSearchBuilder = createSearchBuilder();
        DisasterRecoveryClusterListSearchBuilder.and("msHostId", DisasterRecoveryClusterListSearchBuilder.entity().getMsHostId(), SearchCriteria.Op.EQ);
        DisasterRecoveryClusterListSearchBuilder.and("name", DisasterRecoveryClusterListSearchBuilder.entity().getName(), SearchCriteria.Op.LIKE);
        DisasterRecoveryClusterListSearchBuilder.and("id", DisasterRecoveryClusterListSearchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        DisasterRecoveryClusterListSearchBuilder.done();

        DisasterRecoveryClusterNameSearchBuilder = createSearchBuilder();
        DisasterRecoveryClusterNameSearchBuilder.and("name", DisasterRecoveryClusterNameSearchBuilder.entity().getName(), SearchCriteria.Op.EQ);
        DisasterRecoveryClusterListSearchBuilder.done();
    }

    @Override
    public List<DisasterRecoveryClusterVO> getDisasterRecoveryClusterList(long id) {
        SearchCriteria<DisasterRecoveryClusterVO> sc = DisasterRecoveryClusterListSearchBuilder.create();
        sc.setParameters("id", id);
        return listBy(sc);
    }

    @Override
    public DisasterRecoveryClusterVO getDisasterRecoveryResult(long msHostId, String name) {
        SearchCriteria<DisasterRecoveryClusterVO> sc = DisasterRecoveryClusterListSearchBuilder.create();
        sc.setParameters("msHostId", msHostId);
        sc.setParameters("name", name);
        List<DisasterRecoveryClusterVO> checks = listBy(sc);
        return checks.isEmpty() ? null : checks.get(0);
    }

    @Override
    public DisasterRecoveryClusterVO findByName(String name) {
        SearchCriteria<DisasterRecoveryClusterVO> sc = DisasterRecoveryClusterNameSearchBuilder.create();
        sc.setParameters("name", name);
        return findOneBy(sc);
    }
}
