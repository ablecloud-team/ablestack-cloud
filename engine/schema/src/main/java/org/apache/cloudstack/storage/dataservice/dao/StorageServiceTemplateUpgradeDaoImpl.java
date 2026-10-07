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
import org.apache.cloudstack.storage.dataservice.StorageServiceTemplateUpgradeVO;
public class StorageServiceTemplateUpgradeDaoImpl extends GenericDaoBase<StorageServiceTemplateUpgradeVO,Long> implements StorageServiceTemplateUpgradeDao {
    private final SearchBuilder<StorageServiceTemplateUpgradeVO> scope;
    public StorageServiceTemplateUpgradeDaoImpl() {
        scope=createSearchBuilder();
        scope.and("instance",scope.entity().getInstanceId(),SearchCriteria.Op.EQ);
        scope.and("request",scope.entity().getRequestKey(),SearchCriteria.Op.EQ);
        scope.and("state",scope.entity().getState(),SearchCriteria.Op.IN);scope.done();
    }
    public List<StorageServiceTemplateUpgradeVO> listByInstance(long instanceId) {
        SearchCriteria<StorageServiceTemplateUpgradeVO> c=scope.create();c.setParameters("instance",instanceId);return listBy(c);
    }
    public StorageServiceTemplateUpgradeVO findActive(long instanceId) {
        SearchCriteria<StorageServiceTemplateUpgradeVO> c=scope.create();c.setParameters("instance",instanceId);
        c.setParameters("state","RUNNING","RECOVERY_REQUIRED");return findOneBy(c);
    }
    public StorageServiceTemplateUpgradeVO findByRequest(long instanceId,String request) {
        SearchCriteria<StorageServiceTemplateUpgradeVO> c=scope.create();c.setParameters("instance",instanceId);c.setParameters("request",request);return findOneBy(c);
    }
}
