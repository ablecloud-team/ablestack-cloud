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
package com.cloud.desktop.cluster.dao;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.desktop.cluster.DesktopClusterVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;

@Component
public class DesktopClusterDaoImpl extends GenericDaoBase<DesktopClusterVO, Long> implements DesktopClusterDao {

    private final SearchBuilder<DesktopClusterVO> AccountIdSearch;
    private final SearchBuilder<DesktopClusterVO> GarbageCollectedSearch;
    private final SearchBuilder<DesktopClusterVO> StateSearch;
    private final SearchBuilder<DesktopClusterVO> SameNetworkSearch;
    private final SearchBuilder<DesktopClusterVO> DesktopVersionSearch;

    public DesktopClusterDaoImpl() {
        AccountIdSearch = createSearchBuilder();
        AccountIdSearch.and("account", AccountIdSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
        AccountIdSearch.done();

        GarbageCollectedSearch = createSearchBuilder();
        GarbageCollectedSearch.and("gc", GarbageCollectedSearch.entity().isCheckForGc(), SearchCriteria.Op.EQ);
        GarbageCollectedSearch.and("state", GarbageCollectedSearch.entity().getState(), SearchCriteria.Op.EQ);
        GarbageCollectedSearch.done();

        StateSearch = createSearchBuilder();
        StateSearch.and("state", StateSearch.entity().getState(), SearchCriteria.Op.EQ);
        StateSearch.done();

        SameNetworkSearch = createSearchBuilder();
        SameNetworkSearch.and("network_id", SameNetworkSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        SameNetworkSearch.done();

        DesktopVersionSearch = createSearchBuilder();
        DesktopVersionSearch.and("desktopVersionId", DesktopVersionSearch.entity().getDesktopVersionId(), SearchCriteria.Op.EQ);
        DesktopVersionSearch.done();
    }

    @Override
    public List<DesktopClusterVO> listByAccount(long accountId) {
        SearchCriteria<DesktopClusterVO> sc = AccountIdSearch.create();
        sc.setParameters("account", accountId);
        return listBy(sc, null);
    }

    @Override
    public List<DesktopClusterVO> findDesktopClustersToGarbageCollect() {
        SearchCriteria<DesktopClusterVO> sc = GarbageCollectedSearch.create();
        sc.setParameters("gc", true);
        sc.setParameters("state", DesktopCluster.State.Destroying);
        return listBy(sc);
    }

    @Override
    public List<DesktopClusterVO> findDesktopClustersInState(DesktopCluster.State state) {
        SearchCriteria<DesktopClusterVO> sc = StateSearch.create();
        sc.setParameters("state", state);
        return listBy(sc);
    }

    @Override
    public boolean updateState(DesktopCluster.State currentState, DesktopCluster.Event event, DesktopCluster.State nextState,
    DesktopCluster vo, Object data) {
        // TODO: ensure this update is correct
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();

        DesktopClusterVO ccVo = (DesktopClusterVO)vo;
        ccVo.setState(nextState);
        super.update(ccVo.getId(), ccVo);

        txn.commit();
        return true;
    }

    @Override
    public List<DesktopClusterVO> listByNetworkId(long networkId) {
        SearchCriteria<DesktopClusterVO> sc = SameNetworkSearch.create();
        sc.setParameters("network_id", networkId);
        return this.listBy(sc);
    }

    @Override
    public List<DesktopClusterVO> listAllByDesktopVersion(long desktopVersionId) {
        SearchCriteria<DesktopClusterVO> sc = DesktopVersionSearch.create();
        sc.setParameters("desktopVersionId", desktopVersionId);
        return this.listBy(sc);
    }
}
