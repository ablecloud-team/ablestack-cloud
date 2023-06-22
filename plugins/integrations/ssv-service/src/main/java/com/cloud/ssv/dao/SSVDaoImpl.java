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
package com.cloud.ssv.dao;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.ssv.SSV;
import com.cloud.ssv.SSVVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;

@Component
public class SSVDaoImpl extends GenericDaoBase<SSVVO, Long> implements SSVDao {

    private final SearchBuilder<SSVVO> AccountIdSearch;
    private final SearchBuilder<SSVVO> StateSearch;
    // private final SearchBuilder<SSVVO> SameNetworkSearch;

    public SSVDaoImpl() {
        AccountIdSearch = createSearchBuilder();
        AccountIdSearch.and("account", AccountIdSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
        AccountIdSearch.done();

        StateSearch = createSearchBuilder();
        StateSearch.and("state", StateSearch.entity().getState(), SearchCriteria.Op.EQ);
        StateSearch.done();

        // SameNetworkSearch = createSearchBuilder();
        // SameNetworkSearch.and("network_id", SameNetworkSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        // SameNetworkSearch.done();

    }

    @Override
    public List<SSVVO> listByAccount(long accountId) {
        SearchCriteria<SSVVO> sc = AccountIdSearch.create();
        sc.setParameters("account", accountId);
        return listBy(sc, null);
    }

    @Override
    public List<SSVVO> findSSVsInState(SSV.State state) {
        SearchCriteria<SSVVO> sc = StateSearch.create();
        sc.setParameters("state", state);
        return listBy(sc);
    }

    @Override
    public boolean updateState(SSV.State currentState, SSV.Event event, SSV.State nextState,
    SSV vo, Object data) {
        // TODO: ensure this update is correct
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();

        SSVVO ssvvo = (SSVVO)vo;
        ssvvo.setState(nextState);
        super.update(ssvvo.getId(), ssvvo);

        txn.commit();
        return true;
    }

    // @Override
    // public List<SSVVO> listByNetworkId(long networkId) {
    //     SearchCriteria<SSVVO> sc = SameNetworkSearch.create();
    //     sc.setParameters("network_id", networkId);
    //     return this.listBy(sc);
    // }

}
