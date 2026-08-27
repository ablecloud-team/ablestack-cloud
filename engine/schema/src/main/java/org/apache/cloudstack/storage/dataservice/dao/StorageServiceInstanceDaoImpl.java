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

import org.apache.cloudstack.storage.dataservice.StorageServiceInstanceVO;

import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

import java.util.List;

public class StorageServiceInstanceDaoImpl extends GenericDaoBase<StorageServiceInstanceVO, Long> implements StorageServiceInstanceDao {
    protected final SearchBuilder<StorageServiceInstanceVO> ZoneSearch;
    protected final SearchBuilder<StorageServiceInstanceVO> VmSearch;

    public StorageServiceInstanceDaoImpl() {
        ZoneSearch = createSearchBuilder();
        ZoneSearch.and("dataCenterId", ZoneSearch.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        ZoneSearch.done();

        VmSearch = createSearchBuilder();
        VmSearch.and("vmId", VmSearch.entity().getVmId(), SearchCriteria.Op.EQ);
        VmSearch.done();
    }

    @Override
    public List<StorageServiceInstanceVO> listByZoneId(Long zoneId) {
        SearchCriteria<StorageServiceInstanceVO> sc = ZoneSearch.create();
        sc.setParameters("dataCenterId", zoneId);
        return listBy(sc);
    }

    @Override
    public StorageServiceInstanceVO findByVmId(long vmId) {
        SearchCriteria<StorageServiceInstanceVO> sc = VmSearch.create();
        sc.setParameters("vmId", vmId);
        return findOneBy(sc);
    }
}
