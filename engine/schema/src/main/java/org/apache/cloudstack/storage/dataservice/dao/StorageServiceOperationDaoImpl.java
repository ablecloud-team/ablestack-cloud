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

    private static final String SUMMARY_COLUMNS = "id,uuid,instance_id,request_key,action,state,phase,revision,progress,created_by,created,heartbeat,completed,diagnostic";

    // The DAO's @DB interceptor supplies the transaction; this seam also permits real JDBC contract tests.
    java.sql.PreparedStatement historyStatement(String sql) throws java.sql.SQLException {
        return com.cloud.utils.db.TransactionLegacy.currentTxn().prepareAutoCloseStatement(sql);
    }
    public Admission readAdmission(long instanceId) {
        String sql="SELECT COALESCE(MAX(CASE WHEN state='COMPLETE' THEN revision END),0),"
                +"COALESCE(SUM(CASE WHEN state='RUNNING' THEN 1 ELSE 0 END),0),"
                +"MAX(CASE WHEN state='RECOVERY_REQUIRED' THEN revision END) FROM cloud.storage_service_operation WHERE instance_id=?";
        try (java.sql.PreparedStatement statement=historyStatement(sql)) {
            statement.setLong(1,instanceId);
            try (java.sql.ResultSet result=statement.executeQuery()) {
                if(!result.next())throw new java.sql.SQLException("Missing operation admission state");
                long committed=result.getLong(1);if(result.wasNull())throw new java.sql.SQLException("Missing committed revision");
                long running=result.getLong(2);if(result.wasNull())throw new java.sql.SQLException("Missing running count");
                long recovery=result.getLong(3);Long revision=result.wasNull()?null:recovery;
                if(result.next())throw new java.sql.SQLException("Ambiguous operation admission state");
                return new Admission(committed,running,revision);
            }
        } catch(java.sql.SQLException failure) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Unable to read Storage Service operation admission",failure);
        }
    }
    public List<org.apache.cloudstack.storage.dataservice.StorageServiceOperationSummary> listSummariesByInstance(long instanceId) {
        try (java.sql.PreparedStatement statement=historyStatement("SELECT "+SUMMARY_COLUMNS
                +" FROM cloud.storage_service_operation WHERE instance_id=? ORDER BY created DESC,id DESC")) {
            statement.setLong(1,instanceId);return readSummaries(statement);
        } catch(java.sql.SQLException failure) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Unable to read Storage Service operation metadata",failure);
        }
    }
    public List<org.apache.cloudstack.storage.dataservice.StorageServiceOperationSummary> listStaleRunningSummaries(java.util.Date before) {
        try (java.sql.PreparedStatement statement=historyStatement("SELECT "+SUMMARY_COLUMNS
                +" FROM cloud.storage_service_operation WHERE state='RUNNING' AND heartbeat<=? ORDER BY heartbeat ASC,id ASC LIMIT 20")) {
            statement.setString(1,com.cloud.utils.DateUtil.getDateDisplayString(java.util.TimeZone.getTimeZone("GMT"),before));
            return readSummaries(statement);
        } catch(java.sql.SQLException failure) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Unable to read stale Storage Service operation metadata",failure);
        }
    }
    private List<org.apache.cloudstack.storage.dataservice.StorageServiceOperationSummary> readSummaries(java.sql.PreparedStatement statement)
            throws java.sql.SQLException {
        List<org.apache.cloudstack.storage.dataservice.StorageServiceOperationSummary> rows=new java.util.ArrayList<>();
        try(java.sql.ResultSet result=statement.executeQuery()) {
            while(result.next())rows.add(new org.apache.cloudstack.storage.dataservice.StorageServiceOperationSummary(
                    result.getLong("id"),result.getString("uuid"),result.getLong("instance_id"),result.getString("request_key"),
                    result.getString("action"),result.getString("state"),result.getString("phase"),result.getLong("revision"),
                    result.getInt("progress"),result.getLong("created_by"),historyDate(result,"created"),historyDate(result,"heartbeat"),
                    historyDate(result,"completed"),result.getString("diagnostic")));
        }
        return rows;
    }

    private java.util.Date historyDate(java.sql.ResultSet result,String column) throws java.sql.SQLException {
        String value=result.getString(column);
        // GenericDaoBase also interprets database Date strings as GMT, regardless of the JVM default zone.
        return value==null?null:com.cloud.utils.DateUtil.parseDateString(java.util.TimeZone.getTimeZone("GMT"),value);
    }

    public boolean touchHeartbeat(long id, String operationUuid, long instanceId) {
        // Update only the lease column: phase/diagnostic changes and terminal commits must never be overwritten.
        try (com.cloud.utils.db.TransactionLegacy transaction = com.cloud.utils.db.TransactionLegacy.open("StorageServiceWriterHeartbeat");
                java.sql.PreparedStatement statement = transaction.prepareAutoCloseStatement(
                "UPDATE cloud.storage_service_operation SET heartbeat=? WHERE id=? AND uuid=? AND instance_id=? AND state IN ('RUNNING','RECOVERY_REQUIRED')")) {
            statement.setString(1, com.cloud.utils.DateUtil.getDateDisplayString(java.util.TimeZone.getTimeZone("GMT"), new java.util.Date()));
            statement.setLong(2, id);statement.setString(3, operationUuid);statement.setLong(4, instanceId);
            return statement.executeUpdate() == 1;
        } catch (java.sql.SQLException unavailable) {
            throw new com.cloud.utils.exception.CloudRuntimeException("Unable to renew Storage Service writer heartbeat", unavailable);
        }
    }

}
