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
import java.util.List;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.storage.dataservice.StorageServiceConfigurationOperationVO;
public class StorageServiceConfigurationOperationDaoImpl
        extends GenericDaoBase<StorageServiceConfigurationOperationVO, Long> implements StorageServiceConfigurationOperationDao {
    private final SearchBuilder<StorageServiceConfigurationOperationVO> request;
    private final SearchBuilder<StorageServiceConfigurationOperationVO> instance;
    public StorageServiceConfigurationOperationDaoImpl() {
        request = createSearchBuilder();
        request.and("instanceId", request.entity().getInstanceId(), SearchCriteria.Op.EQ);
        request.and("requestId", request.entity().getRequestId(), SearchCriteria.Op.EQ);
        request.done();
        instance = createSearchBuilder();
        instance.and("instanceId", instance.entity().getInstanceId(), SearchCriteria.Op.EQ);
        instance.done();
    }
    public StorageServiceConfigurationOperationVO findByRequest(final long instanceId, final String requestId) {
        SearchCriteria<StorageServiceConfigurationOperationVO> sc = request.create();
        sc.setParameters("instanceId", instanceId); sc.setParameters("requestId", requestId);
        return findOneBy(sc);
    }
    public List<StorageServiceConfigurationOperationVO> listByInstance(final long instanceId) {
        SearchCriteria<StorageServiceConfigurationOperationVO> sc = instance.create();
        sc.setParameters("instanceId", instanceId);
        return listBy(sc);
    }
}
