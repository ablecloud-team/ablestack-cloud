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

package org.apache.cloudstack.storage.dataservice.dao;

import java.util.Arrays;
import java.util.List;

import org.apache.cloudstack.storage.dataservice.StorageServiceRuntimeUpgradeVO;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class StorageServiceRuntimeUpgradeDaoImpl extends GenericDaoBase<StorageServiceRuntimeUpgradeVO, Long>
        implements StorageServiceRuntimeUpgradeDao {
    private final SearchBuilder<StorageServiceRuntimeUpgradeVO> uuidSearch;
    private final SearchBuilder<StorageServiceRuntimeUpgradeVO> transactionSearch;
    private final SearchBuilder<StorageServiceRuntimeUpgradeVO> instanceSearch;
    private final SearchBuilder<StorageServiceRuntimeUpgradeVO> activeSearch;

    public StorageServiceRuntimeUpgradeDaoImpl() {
        uuidSearch = createSearchBuilder();
        uuidSearch.and("uuid", uuidSearch.entity().getUuid(), SearchCriteria.Op.EQ);
        uuidSearch.done();
        transactionSearch = createSearchBuilder();
        transactionSearch.and("transactionId", transactionSearch.entity().getTransactionId(), SearchCriteria.Op.EQ);
        transactionSearch.done();
        instanceSearch = createSearchBuilder();
        instanceSearch.and("instanceId", instanceSearch.entity().getInstanceId(), SearchCriteria.Op.EQ);
        instanceSearch.done();
        activeSearch = createSearchBuilder();
        activeSearch.and("instanceId", activeSearch.entity().getInstanceId(), SearchCriteria.Op.EQ);
        activeSearch.and("state", activeSearch.entity().getState(), SearchCriteria.Op.IN);
        activeSearch.done();
    }

    public StorageServiceRuntimeUpgradeVO findByUuid(final String uuid) {
        final SearchCriteria<StorageServiceRuntimeUpgradeVO> criteria = uuidSearch.create();
        criteria.setParameters("uuid", uuid);
        return findOneBy(criteria);
    }

    public StorageServiceRuntimeUpgradeVO findByTransactionId(final String transactionId) {
        final SearchCriteria<StorageServiceRuntimeUpgradeVO> criteria = transactionSearch.create();
        criteria.setParameters("transactionId", transactionId);
        return findOneBy(criteria);
    }

    public StorageServiceRuntimeUpgradeVO findActiveByInstanceId(final long instanceId) {
        final SearchCriteria<StorageServiceRuntimeUpgradeVO> criteria = activeSearch.create();
        criteria.setParameters("instanceId", instanceId);
        criteria.setParameters("state", Arrays.asList(StorageServiceRuntimeUpgradeVO.State.RUNNING,
                StorageServiceRuntimeUpgradeVO.State.PREFLIGHT_READY, StorageServiceRuntimeUpgradeVO.State.OBSERVATION_PENDING).toArray());
        return findOneBy(criteria);
    }

    public List<StorageServiceRuntimeUpgradeVO> listByInstanceId(final long instanceId) {
        final SearchCriteria<StorageServiceRuntimeUpgradeVO> criteria = instanceSearch.create();
        criteria.setParameters("instanceId", instanceId);
        return listBy(criteria);
    }
}
