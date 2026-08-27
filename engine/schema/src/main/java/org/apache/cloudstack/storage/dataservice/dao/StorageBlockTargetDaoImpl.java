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
import org.apache.cloudstack.storage.dataservice.StorageBlockTargetVO;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class StorageBlockTargetDaoImpl extends GenericDaoBase<StorageBlockTargetVO, Long> implements StorageBlockTargetDao {
    protected final SearchBuilder<StorageBlockTargetVO> InstanceProtocolSearch;
    protected final SearchBuilder<StorageBlockTargetVO> ProtocolSearch;

    public StorageBlockTargetDaoImpl() {
        InstanceProtocolSearch = createSearchBuilder();
        InstanceProtocolSearch.and("instanceId", InstanceProtocolSearch.entity().getInstanceId(), SearchCriteria.Op.EQ);
        InstanceProtocolSearch.and("protocol", InstanceProtocolSearch.entity().getProtocol(), SearchCriteria.Op.EQ);
        InstanceProtocolSearch.done();

        ProtocolSearch = createSearchBuilder();
        ProtocolSearch.and("protocol", ProtocolSearch.entity().getProtocol(), SearchCriteria.Op.EQ);
        ProtocolSearch.done();
    }

    @Override
    public List<StorageBlockTargetVO> listByInstanceIdAndProtocol(final long instanceId, final StorageServiceInstance.Protocol protocol) {
        final SearchCriteria<StorageBlockTargetVO> sc = InstanceProtocolSearch.create();
        sc.setParameters("instanceId", instanceId);
        sc.setParameters("protocol", protocol);
        return listBy(sc);
    }

    @Override
    public List<StorageBlockTargetVO> listByProtocol(final StorageServiceInstance.Protocol protocol) {
        final SearchCriteria<StorageBlockTargetVO> sc = ProtocolSearch.create();
        sc.setParameters("protocol", protocol);
        return listBy(sc);
    }
}
