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
import com.cloud.utils.db.*;
import org.apache.cloudstack.storage.dataservice.StorageDirectoryPolicyVO;
public class StorageDirectoryPolicyDaoImpl extends GenericDaoBase<StorageDirectoryPolicyVO,Long> implements StorageDirectoryPolicyDao {
    private final SearchBuilder<StorageDirectoryPolicyVO> byInstance;
    public StorageDirectoryPolicyDaoImpl() {
        byInstance=createSearchBuilder();
        byInstance.and("instanceId",byInstance.entity().getInstanceId(),SearchCriteria.Op.EQ);
        byInstance.done();
    }
    @Override public List<StorageDirectoryPolicyVO> listByInstanceId(long id) {
        SearchCriteria<StorageDirectoryPolicyVO> sc=byInstance.create();
        sc.setParameters("instanceId",id); return listBy(sc);
    }
}
