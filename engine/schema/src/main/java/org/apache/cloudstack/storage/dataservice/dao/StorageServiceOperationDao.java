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
import com.cloud.utils.db.GenericDao;
import org.apache.cloudstack.storage.dataservice.StorageServiceOperationVO;

public interface StorageServiceOperationDao extends GenericDao<StorageServiceOperationVO, Long> {
    StorageServiceOperationVO findByRequest(long instanceId, String requestKey);
    List<StorageServiceOperationVO> listByInstance(long instanceId);
    Admission readAdmission(long instanceId);
    List<org.apache.cloudstack.storage.dataservice.StorageServiceOperationSummary> listSummariesByInstance(long instanceId);
    List<org.apache.cloudstack.storage.dataservice.StorageServiceOperationSummary> listStaleRunningSummaries(java.util.Date before);

    final class Admission {
        private final long committedRevision, runningCount;
        private final Long recoveryRevision;
        public Admission(long committedRevision, long runningCount, Long recoveryRevision) {
            if (committedRevision<0 || runningCount<0 || recoveryRevision!=null && recoveryRevision<0)
                throw new IllegalArgumentException("Invalid operation admission state");
            this.committedRevision=committedRevision;this.runningCount=runningCount;this.recoveryRevision=recoveryRevision;
        }
        public long getCommittedRevision() { return committedRevision; }
        public boolean blocked() { return runningCount>0 || recoveryRevision!=null && recoveryRevision>committedRevision; }
    }
    List<StorageServiceOperationVO> listStaleRunning(java.util.Date before);
    boolean touchHeartbeat(long id, String operationUuid, long instanceId);
}
