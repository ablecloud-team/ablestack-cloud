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
import org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO;

public class StorageServiceOperationDaoImpl extends GenericDaoBase<StorageServiceOperationVO, Long> implements StorageServiceOperationDao {
    private final SearchBuilder<StorageServiceOperationVO> scope;
    public StorageServiceOperationDaoImpl() {
        scope = createSearchBuilder();
        scope.and("instance", scope.entity().getInstanceId(), SearchCriteria.Op.EQ);
        scope.and("request", scope.entity().getRequestKey(), SearchCriteria.Op.EQ);
        scope.and("state", scope.entity().getState(), SearchCriteria.Op.EQ);
        scope.and("before", scope.entity().getHeartbeat(), SearchCriteria.Op.LTEQ);
        scope.done();
    }
    public StorageServiceOperationVO findByRequest(long instanceId, String requestKey) {
        SearchCriteria<StorageServiceOperationVO> criteria = scope.create();
        criteria.setParameters("instance", instanceId); criteria.setParameters("request", requestKey);
        return findOneBy(criteria);
    }
    public List<StorageServiceOperationVO> listByInstance(long instanceId) {
        SearchCriteria<StorageServiceOperationVO> criteria = scope.create();
        criteria.setParameters("instance", instanceId);
        return listBy(criteria);
    }
    public List<StorageServiceOperationVO> listStaleRunning(java.util.Date before) {
        SearchCriteria<StorageServiceOperationVO> criteria = scope.create();
        criteria.setParameters("state", "RUNNING");criteria.setParameters("before", before);
        return listBy(criteria, new com.cloud.utils.db.Filter(StorageServiceOperationVO.class, "heartbeat", true, 0L, 20L));
    }

    public boolean touchHeartbeat(long id, String operationUuid, long instanceId) {
        // Update only the lease column: phase/diagnostic changes and terminal commits must never be overwritten.
        try (com.cloud.utils.db.TransactionLegacy transaction = com.cloud.utils.db.TransactionLegacy.open("StorageServiceWriterHeartbeat");
                java.sql.PreparedStatement statement = transaction.prepareAutoCloseStatement(
                "UPDATE cloud.storage_service_operation SET heartbeat=CURRENT_TIMESTAMP WHERE id=? AND uuid=? AND instance_id=? AND state IN ('RUNNING','RECOVERY_REQUIRED')")) {
            statement.setLong(1, id);statement.setString(2, operationUuid);statement.setLong(3, instanceId);
            return statement.executeUpdate() == 1;
        } catch (java.sql.SQLException unavailable) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Unable to renew Storage Service writer heartbeat", unavailable);
        }
    }

}
