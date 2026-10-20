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
import org.apache.cloudstack.storage.dataservice.StoragePosixDirectoryPolicyVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

public class StoragePosixDirectoryPolicyDaoImpl extends GenericDaoBase<StoragePosixDirectoryPolicyVO, Long> implements StoragePosixDirectoryPolicyDao {
    private final SearchBuilder<StoragePosixDirectoryPolicyVO> scope;
    private final SearchBuilder<StoragePosixDirectoryPolicyVO> path;
    public StoragePosixDirectoryPolicyDaoImpl() {
        scope = createSearchBuilder();scope.and("instance", scope.entity().getInstanceId(), SearchCriteria.Op.EQ);scope.done();
        path = createSearchBuilder();path.and("instance", path.entity().getInstanceId(), SearchCriteria.Op.EQ);
        path.and("key", path.entity().getPathKey(), SearchCriteria.Op.EQ);path.done();
    }
    public List<StoragePosixDirectoryPolicyVO> listByInstance(long instanceId) {
        SearchCriteria<StoragePosixDirectoryPolicyVO> query = scope.create();query.setParameters("instance", instanceId);return listBy(query);
    }
    public StoragePosixDirectoryPolicyVO findByPath(long instanceId, String pathKey) {
        SearchCriteria<StoragePosixDirectoryPolicyVO> query = path.create();query.setParameters("instance", instanceId);query.setParameters("key", pathKey);return findOneBy(query);
    }
}
