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
// specific language govening permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice.dao;

import java.util.List;

import org.apache.cloudstack.storage.dataservice.StorageServiceInstance;
import org.apache.cloudstack.storage.dataservice.StorageServiceProtocolVO;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class StorageServiceProtocolDaoImpl extends GenericDaoBase<StorageServiceProtocolVO, Long> implements StorageServiceProtocolDao {
    protected final SearchBuilder<StorageServiceProtocolVO> InstanceProtocolSearch;
    protected final SearchBuilder<StorageServiceProtocolVO> InstanceSearch;

    public StorageServiceProtocolDaoImpl() {
        InstanceProtocolSearch = createSearchBuilder();
        InstanceProtocolSearch.and("instanceId", InstanceProtocolSearch.entity().getInstanceId(), SearchCriteria.Op.EQ);
        InstanceProtocolSearch.and("protocol", InstanceProtocolSearch.entity().getProtocol(), SearchCriteria.Op.EQ);
        InstanceProtocolSearch.done();

        InstanceSearch = createSearchBuilder();
        InstanceSearch.and("instanceId", InstanceSearch.entity().getInstanceId(), SearchCriteria.Op.EQ);
        InstanceSearch.done();
    }

    @Override
    public StorageServiceProtocolVO findByInstanceIdAndProtocol(long instanceId, StorageServiceInstance.Protocol protocol) {
        SearchCriteria<StorageServiceProtocolVO> sc = InstanceProtocolSearch.create();
        sc.setParameters("instanceId", instanceId);
        sc.setParameters("protocol", protocol);
        return findOneBy(sc);
    }

    @Override
    public List<StorageServiceProtocolVO> listByInstanceIdAndProtocol(long instanceId, StorageServiceInstance.Protocol protocol) {
        SearchCriteria<StorageServiceProtocolVO> sc = InstanceProtocolSearch.create();
        sc.setParameters("instanceId", instanceId);
        sc.setParameters("protocol", protocol);
        return listBy(sc);
    }

    @Override
    public List<StorageServiceProtocolVO> listByInstanceId(long instanceId) {
        SearchCriteria<StorageServiceProtocolVO> sc = InstanceSearch.create();
        sc.setParameters("instanceId", instanceId);
        return listBy(sc);
    }
}
