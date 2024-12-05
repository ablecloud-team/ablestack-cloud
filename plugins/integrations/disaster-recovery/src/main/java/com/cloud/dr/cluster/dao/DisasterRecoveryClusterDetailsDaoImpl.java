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

import com.cloud.dr.cluster.DisasterRecoveryClusterDetailsVO;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.apache.cloudstack.api.ApiConstants;

@Component
public class DisasterRecoveryClusterDetailsDaoImpl extends GenericDaoBase<DisasterRecoveryClusterDetailsVO, Long> implements DisasterRecoveryClusterDetailsDao {

    protected final SearchBuilder<DisasterRecoveryClusterDetailsVO> ClusterSearch;
    protected final SearchBuilder<DisasterRecoveryClusterDetailsVO> DetailSearch;

    public DisasterRecoveryClusterDetailsDaoImpl() {
        ClusterSearch = createSearchBuilder();
        ClusterSearch.and("clusterId", ClusterSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        ClusterSearch.done();

        DetailSearch = createSearchBuilder();
        DetailSearch.and("clusterId", DetailSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        DetailSearch.and("name", DetailSearch.entity().getName(), SearchCriteria.Op.EQ);
        DetailSearch.done();
    }

    @Override
    public DisasterRecoveryClusterDetailsVO findDetail(long clusterId, String name) {
        SearchCriteria<DisasterRecoveryClusterDetailsVO> sc = DetailSearch.create();
        sc.setParameters("clusterId", clusterId);
        sc.setParameters("name", name);

        DisasterRecoveryClusterDetailsVO detail = findOneIncludingRemovedBy(sc);
        if (ApiConstants.DR_CLUSTER_PRIVATE_KEY.equalsIgnoreCase(name) && detail != null) {
            detail.setValue(DBEncryptionUtil.decrypt(detail.getValue()));
        }
        return detail;
    }

    @Override
    public Map<String, String> findDetails(long clusterId) {
        SearchCriteria<DisasterRecoveryClusterDetailsVO> sc = ClusterSearch.create();
        sc.setParameters("clusterId", clusterId);

        List<DisasterRecoveryClusterDetailsVO> results = search(sc, null);
        Map<String, String> details = new HashMap<String, String>(results.size());
        for (DisasterRecoveryClusterDetailsVO result : results) {
            if (ApiConstants.DR_CLUSTER_PRIVATE_KEY.equalsIgnoreCase(result.getName())) {
                details.put(result.getName(), DBEncryptionUtil.decrypt(result.getValue()));
            } else {
                details.put(result.getName(), result.getValue());
            }
        }
        return details;
    }

    @Override
    public void deleteDetails(long clusterId) {
        SearchCriteria sc = ClusterSearch.create();
        sc.setParameters("clusterId", clusterId);

        List<DisasterRecoveryClusterDetailsVO> results = search(sc, null);
        for (DisasterRecoveryClusterDetailsVO result : results) {
            remove(result.getId());
        }
    }

    @Override
    public void persist(long clusterId, Map<String, String> details) {
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();
        SearchCriteria<DisasterRecoveryClusterDetailsVO> sc = ClusterSearch.create();
        sc.setParameters("clusterId", clusterId);
        expunge(sc);

        for (Map.Entry<String, String> detail : details.entrySet()) {
            String name = detail.getKey();
            String value = detail.getValue();
            if (ApiConstants.DR_CLUSTER_PRIVATE_KEY.equalsIgnoreCase(detail.getKey())) {
                value = DBEncryptionUtil.encrypt(value);
            }
            DisasterRecoveryClusterDetailsVO vo = new DisasterRecoveryClusterDetailsVO(clusterId, name, value);
            persist(vo);
        }
        txn.commit();
    }
}