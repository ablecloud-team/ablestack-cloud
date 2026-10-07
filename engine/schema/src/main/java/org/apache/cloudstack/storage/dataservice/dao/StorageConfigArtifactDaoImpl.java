// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.storage.dataservice.dao;

import java.util.List;
import java.util.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.storage.dataservice.StorageConfigArtifactVO;

public class StorageConfigArtifactDaoImpl extends GenericDaoBase<StorageConfigArtifactVO, Long> implements StorageConfigArtifactDao {
    private final SearchBuilder<StorageConfigArtifactVO> scope;
    public StorageConfigArtifactDaoImpl() {
        scope = createSearchBuilder();
        scope.and("instance", scope.entity().getInstanceId(), SearchCriteria.Op.EQ);
        scope.and("uuid", scope.entity().getUuid(), SearchCriteria.Op.EQ);
        scope.done();
    }
    public StorageConfigArtifactVO findByUuid(String uuid) {
        SearchCriteria<StorageConfigArtifactVO> criteria = scope.create();criteria.setParameters("uuid", uuid);return findOneBy(criteria);
    }
    public List<StorageConfigArtifactVO> listByInstance(long instanceId) {
        SearchCriteria<StorageConfigArtifactVO> criteria = scope.create();criteria.setParameters("instance", instanceId);return listBy(criteria);
    }
    @Override
    public boolean promoteVerified(long instanceId, long candidateId, Long expectedActiveId, long expectedActiveRevision) {
        return com.cloud.utils.db.Transaction.execute((com.cloud.utils.db.TransactionCallback<Boolean>) status -> {
            StorageConfigArtifactVO candidate = lockRow(candidateId, true);
            if (candidate == null || candidate.getInstanceId() != instanceId || !"RESTORE_POINT".equals(candidate.getKind())
                    || !"CANDIDATE".equals(candidate.getState())) return false;
            // The generated unique key also protects against lifecycle updates outside this path.
            try (PreparedStatement statement = TransactionLegacy.currentTxn().prepareAutoCloseStatement(
                    "SELECT id,desired_revision FROM storage_service_config_artifact WHERE active_lkg_instance_id=? FOR UPDATE")) {
                statement.setLong(1, instanceId);
                try (ResultSet result = statement.executeQuery()) {
                    Long activeId = null;long activeRevision = 0;
                    if (result.next()) { activeId = result.getLong(1);activeRevision = result.getLong(2); }
                    if (!matchesPromotion(expectedActiveId, expectedActiveRevision, activeId, activeRevision, candidate.getDesiredRevision())) return false;
                    if (activeId != null) {
                        StorageConfigArtifactVO previous = lockRow(activeId, true);
                        previous.setState("SUPERSEDED");previous.setUpdated(new Date());
                        previous.setExpires(new Date(System.currentTimeMillis() + 168 * 3600000L));
                        if (!update(previous.getId(), previous)) throw new CloudRuntimeException("Unable to supersede verified restore point");
                    }
                    candidate.setState("ACTIVE_LKG");candidate.setUpdated(new Date());candidate.setExpires(null);
                    if (!update(candidate.getId(), candidate)) throw new CloudRuntimeException("Unable to promote verified restore point");
                    return true;
                }
            } catch (SQLException failure) {
                throw new CloudRuntimeException("Verified restore-point promotion failed", failure);
            }
        });
    }

    static boolean matchesPromotion(Long expectedId, long expectedRevision, Long activeId, long activeRevision, long candidateRevision) {
        return java.util.Objects.equals(expectedId, activeId) && expectedRevision == activeRevision
                && candidateRevision > activeRevision;
    }

}
