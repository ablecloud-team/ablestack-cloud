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
package org.apache.cloudstack.storage.dataservice;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.cloudstack.storage.dataservice.dao.StorageServiceOperationDao;
import org.mockito.Mockito;

/** The old test histories remain the oracle; only the DAO read contract changes. */
public final class StorageOperationHistoryFixtures {
    private StorageOperationHistoryFixtures() { }
    public static void rows(StorageServiceOperationDao dao,long instanceId,List<StorageServiceOperationVO> history) {
        rows(dao,instanceId,()->history);
    }
    public static void rows(StorageServiceOperationDao dao,long instanceId,Supplier<List<StorageServiceOperationVO>> history) {
        Mockito.when(dao.listSummariesByInstance(instanceId)).thenAnswer(call->history.get().stream()
                .map(StorageServiceOperationSummary::from).collect(Collectors.toList()));
        Mockito.when(dao.readAdmission(instanceId)).thenAnswer(call->{
            List<StorageServiceOperationVO> rows=history.get();
            long committed=rows.stream().filter(row->"COMPLETE".equals(row.getState())).mapToLong(StorageServiceOperationVO::getRevision).max().orElse(0);
            long running=rows.stream().filter(row->"RUNNING".equals(row.getState())).count();
            java.util.OptionalLong recovery=rows.stream().filter(row->"RECOVERY_REQUIRED".equals(row.getState())).mapToLong(StorageServiceOperationVO::getRevision).max();
            return new StorageServiceOperationDao.Admission(committed,running,recovery.isPresent()?recovery.getAsLong():null);
        });
    }
}
